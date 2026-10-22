package com.breakyuna.noveltranslator.nativeapp

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NativePage { AGENT, WORKBENCH, PROOFREADING }
enum class NativeExport { PROJECT, TRANSLATION, BILINGUAL, ORIGINAL }

class ItemPreview(val id: ItemId, val row: Int, val source: String, val translation: String,
                  val status: TextItemStatus, val hasTranslatableSource: Boolean = source.isNotBlank()) {
    companion object {
        fun of(item: TextProjectItem) = ItemPreview(item.id, item.row, item.source.take(240),
            item.translation.take(240), item.status, item.source.isNotBlank())
    }
}

/** Only the actively opened item contains full text. Lists keep bounded previews. */
class NativeEditDraft(
    val ref: LocalProjectRef, val item: TextProjectItem, val revision: Long,
    val fingerprint: ObjectFingerprint, val text: String, val status: TextItemStatus,
    val command: CommandId, val conflict: Boolean = false,
) {
    val dirty get() = text != item.translation || status != item.status
    fun changed(text: String = this.text, status: TextItemStatus = this.status) = NativeEditDraft(
        ref, item, revision, fingerprint, text, status, CommandId(UUID.randomUUID().toString()), conflict,
    )
}

data class NativeProjectUiState(
    val projects: List<RegisteredProject> = emptyList(),
    val project: TextProjectSummary? = null,
    val page: NativePage = NativePage.AGENT,
    val items: List<ItemPreview> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val editor: NativeEditDraft? = null,
    val busy: Boolean = false,
    val notice: Int? = null,
    val error: Int? = null,
    val recoveryProblems: Int = 0,
    val legacyData: Boolean = false,
    val encoding: TxtEncoding = TxtEncoding.UTF8,
    val translationProgress: TranslationProgress? = null,
    val agentMessages: List<AgentChatLine> = emptyList(),
    val approval: TextApproval? = null,
    val agentResults: List<AgentApprovalResult> = emptyList(),
    val agentChatId: ChatId? = null,
    val agentChats: List<ChatId> = emptyList(),
    val modelSettings: NativeModelSettings? = null,
    val agentInput: String = "",
    val agentLoadIncomplete: Boolean = false,
)

/** Small workspace controller. Every database/file action goes through the native port. */
class NativeProjectViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val graph = (application as TranslatorApplication).nativeGraph
    private val projects: TextProjectPort = graph.projects
    private val resolver = application.contentResolver
    private val mutable = MutableStateFlow(NativeProjectUiState(encoding =
        runCatching { TxtEncoding.valueOf(saved["native_encoding"] ?: "UTF8") }.getOrDefault(TxtEncoding.UTF8)))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var agentLease: RunLease? = null
    private var agentChat = ChatId("native-agent-main")
    private val cursors = mutableListOf(0L)
    private var actionId = 0L

    init { action {
        val recovery = projects.recoverProjects()
        val registered = projects.registeredProjects()
        val legacy = withContext(Dispatchers.IO) { application.getDatabasePath("light_novel_translation_platform.db").exists() }
        mutable.value = mutable.value.copy(projects = registered, recoveryProblems = recovery.problems.size, legacyData = legacy)
        restoreRef()?.let { ref -> openInternal(ref); restoreDraft(ref) }
    } }

    fun changeEncoding(value: TxtEncoding) { if (!mutable.value.busy) {
        saved["native_encoding"] = value.name
        mutable.value = mutable.value.copy(encoding = value)
    } }
    fun dismissMessage() { mutable.value = mutable.value.copy(error = null, notice = null) }
    fun cancelAction() { graph.translations.stop(); agentLease?.let { graph.runtime.requestStop(it) }; job?.cancel() }
    fun cleanupOutputs() = action {
        projects.cleanupTemporaryOutputs()
        mutable.value = mutable.value.copy(notice = R.string.native_cleaned)
    }
    fun refresh() = action {
        val recovery = projects.recoverProjects()
        val registered = projects.registeredProjects()
        mutable.value = mutable.value.copy(projects = registered, recoveryProblems = recovery.problems.size)
        mutable.value.project?.let { loadPage(it.ref, cursors.last()); loadAgent(it.ref) }
    }

    fun open(ref: LocalProjectRef) = action { openInternal(ref) }
    fun copy(ref: LocalProjectRef) = action {
        val result = projects.copyProject(commandId(), ref)
        mutable.value = mutable.value.copy(projects = projects.registeredProjects())
        openInternal(result.ref)
    }

    fun saveAgentModel(endpoint: String, model: String, key: String, onSaved: () -> Unit) = action {
        val settings = graph.modelSettings.save(endpoint, model, key)
        mutable.value = mutable.value.copy(modelSettings = settings, notice = R.string.native_model_saved)
        onSaved()
    }
    fun agentInput(text: String) {
        if (mutable.value.busy || text.length > 8000) return
        mutable.value.project?.let {
            saved["agent_input_ref"] = encodeRef(it.ref); saved["agent_input_chat"] = agentChat.value
            saved["agent_input_text"] = text
            mutable.value = mutable.value.copy(agentInput = text)
        }
    }
    fun selectAgentChat(chat: ChatId) = action {
        val ref = mutable.value.project?.ref ?: return@action
        graph.agentStore.selectChat(ref, chat)
        loadAgent(ref)
    }
    fun newAgentChat() = action {
        val ref = mutable.value.project?.ref ?: return@action
        graph.agentStore.newChat(ref)
        loadAgent(ref)
    }
    fun sendAgentMessage() = action {
        if (mutable.value.agentLoadIncomplete) return@action
        val ref = mutable.value.project?.ref ?: return@action
        val configuration = mutable.value.modelSettings?.reference ?: return@action
        val text = mutable.value.agentInput
        if (text.isBlank() || mutable.value.approval != null) return@action
        try {
            agentRun(ref) { lease ->
                graph.conversations.send(lease, ref, configuration, text) {
                    withContext(Dispatchers.Main.immediate) {
                        saved.remove<String>("agent_input_text")
                        mutable.value = mutable.value.copy(agentInput = "")
                    }
                }
            }
        } finally { withContext(NonCancellable) { loadAgent(ref) } }
    }

    fun queryAgentProject() = action {
        if (mutable.value.agentLoadIncomplete) return@action
        val ref = mutable.value.project?.ref ?: return@action
        try {
            agentRun(ref) { lease ->
                graph.agentStore.append(ref, agentChat, "USER", getApplication<Application>().getString(R.string.native_agent_query))
                val text = graph.agentQueries.query(lease, ref)
                val result = JsonObjectValue.parse(text, JsonInputLimits(16_384, 8)).json
                val count = ToolArguments.long(ToolScalar.NumberToken(result.getValue("items").jsonPrimitive.content), "items", 0, 100_000)
                val display = getApplication<Application>().getString(R.string.native_agent_summary,
                    result.getValue("name").jsonPrimitive.content, result.getValue("asset").jsonPrimitive.content, count)
                graph.agentStore.append(ref, agentChat, "ASSISTANT", display)
            }
        } finally { withContext(NonCancellable) { loadAgent(ref) } }
    }

    fun prepareAgentEdit(itemIdText: String, translation: String) = action {
        if (mutable.value.agentLoadIncomplete) return@action
        val ref = mutable.value.project?.ref ?: return@action
        val item = ItemId(ToolArguments.long(ToolScalar.NumberToken(itemIdText), "itemId", 1, Long.MAX_VALUE))
        try {
            agentRun(ref) { lease -> graph.agentQueries.prepare(lease, ref, graph.agentStore, item, translation) }
        } finally { withContext(NonCancellable) { loadAgent(ref) } }
    }

    fun rejectAgentEdit() = action {
        if (mutable.value.agentLoadIncomplete) return@action
        val approval = mutable.value.approval ?: return@action
        graph.agentStore.reject(approval.ref, approval.id, approval.digest)
        loadAgent(approval.ref)
    }

    fun allowAgentEdit() = action {
        if (mutable.value.agentLoadIncomplete) return@action
        val approval = mutable.value.approval ?: return@action
        if (approval.state != TextApprovalState.PENDING) return@action
        try {
            val result = agentRun(approval.ref, approval.runId, approval.commandId) { lease ->
                graph.agentStore.apply(lease, approval.ref, approval.id, approval.digest)
            }
            mutable.value = mutable.value.copy(notice = when (result) {
                TextApprovalState.COMMITTED -> R.string.native_saved
                TextApprovalState.CONFLICT -> R.string.native_conflict
                TextApprovalState.CANCELLED -> R.string.native_agent_result_cancelled
                TextApprovalState.REJECTED -> R.string.native_agent_result_rejected
                else -> R.string.native_agent_unresolved
            })
        } finally {
            withContext(NonCancellable) { loadPage(approval.ref, cursors.last()); loadAgent(approval.ref) }
        }
    }

    private suspend fun loadAgent(ref: LocalProjectRef) {
        var incomplete = false
        suspend fun <T> loadOr(fallback: T, block: suspend () -> T): T = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { incomplete = true; fallback }
        val selectedChat = loadOr<ChatId?>(null) { graph.agentStore.currentChat(ref) }
        if (selectedChat != null) agentChat = selectedChat
        if (selectedChat != null) loadOr(Unit) { graph.agentStore.reconcile(ref, selectedChat) }
        val messages = if (selectedChat == null) emptyList() else
            loadOr(emptyList<AgentChatLine>()) { graph.agentStore.messages(ref, selectedChat) }
        val approval = if (selectedChat == null) null else
            loadOr<TextApproval?>(null) { graph.agentStore.pending(ref, selectedChat) }
        val results = if (selectedChat == null) emptyList() else
            loadOr(emptyList<AgentApprovalResult>()) { graph.agentStore.results(ref, selectedChat) }
        val settings = loadOr<NativeModelSettings?>(mutable.value.modelSettings) { graph.modelSettings.current() }
        val chats = loadOr(selectedChat?.let { listOf(it) } ?: emptyList()) { graph.agentStore.chats(ref) }
        val draft = if (saved.get<ArrayList<String>>("agent_input_ref")?.toList() == encodeRef(ref).toList() &&
            saved.get<String>("agent_input_chat") == selectedChat?.value) saved.get<String>("agent_input_text") ?: "" else ""
        mutable.value = mutable.value.copy(agentMessages = messages, approval = approval, agentResults = results,
            agentChatId = selectedChat, agentChats = chats, modelSettings = settings, agentInput = draft,
            agentLoadIncomplete = incomplete)
    }

    private suspend fun <T> agentRun(ref: LocalProjectRef, runId: RunId = RunId(UUID.randomUUID().toString()),
                                     command: CommandId = commandId(), block: suspend (RunLease) -> T): T {
        graph.runtime.selectChat(ChatIdentity(ref.projectId, agentChat))
        val lease = graph.runtime.beginRun(RunIdentity(ref.projectId, agentChat, runId, command), RuntimeOwner.AGENT)
        agentLease = lease
        try { return block(lease) }
        catch (cancelled: CancellationException) {
            withContext(NonCancellable) { graph.agentStore.cancelPending(ref, runId) }
            throw cancelled
        } finally {
            graph.runtime.finishRun(lease)
            agentLease = null
            graph.runtime.selectChat(null)
        }
    }

    /** Explicitly confirmed local simulation, limited to the currently loaded proofreading page. */
    fun simulatePage() = action {
        val project = mutable.value.project ?: return@action
        if (mutable.value.editor != null) return@action
        val ref = project.ref
        val after = cursors.last()
        mutable.value = mutable.value.copy(translationProgress = null)
        graph.runtime.selectChat(ChatIdentity(ref.projectId, ChatId("translation-workspace")))
        try {
            graph.translations.translatePage(ref,
                RunIdentity(ref.projectId, null, RunId(UUID.randomUUID().toString()), commandId()),
                graph.simulationConfiguration, project.revision, after, PAGE_SIZE) { progress ->
                mutable.value = mutable.value.copy(translationProgress = progress)
            }
            mutable.value = mutable.value.copy(notice = R.string.native_simulation_done)
        } finally {
            // Re-read committed facts even when cancellation reached us after the commit boundary.
            graph.runtime.selectChat(null)
            withContext(NonCancellable) { loadPage(ref, after) }
        }
    }

    fun prepareImport(native: Boolean): Boolean {
        if (mutable.value.busy) return false
        saved[importKey(native)] = arrayListOf(commandId().value, mutable.value.encoding.name)
        return true
    }

    fun importFile(uri: Uri?, native: Boolean) {
        val key = importKey(native)
        if (uri == null) { saved.remove<ArrayList<String>>(key); return }
        val intent = saved.get<ArrayList<String>>(key)?.toList() ?: return
        queuedAction {
            val command = CommandId(intent[0])
            val result = if (native) {
                projects.importProject(command) { resolver.openInputStream(uri) ?: throw java.io.IOException() }
            } else {
                val input = withContext(Dispatchers.IO) {
                    val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "source.txt"
                    val basename = rawName.substringAfterLast('/').substringAfterLast('\\')
                        .map { if (it.isISOControl()) '_' else it }.joinToString("").take(180)
                    val filename = if (basename.endsWith(".txt", true)) basename else "$basename.txt"
                    val output = ByteArrayOutputStream()
                    val coroutine = currentCoroutineContext()
                    resolver.openInputStream(uri)?.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutine.ensureActive()
                            val read = stream.read(buffer)
                            if (read < 0) break
                            if (output.size().toLong() + read > TextProjectLimits().maxSourceBytes) {
                                throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                            }
                            output.write(buffer, 0, read)
                        }
                    } ?: throw java.io.IOException()
                    filename to output.toByteArray()
                }
                projects.createTxt(command, input.first.removeSuffix(".txt").take(180).ifBlank { "TXT" },
                    RelativePath(input.first), input.second, TxtEncoding.valueOf(intent[1]))
            }
            mutable.value = mutable.value.copy(projects = projects.registeredProjects())
            openInternal(result.ref)
            if (saved.get<ArrayList<String>>(key)?.toList() == intent) saved.remove<ArrayList<String>>(key)
        }
    }

    fun selectPage(page: NativePage) {
        if (mutable.value.busy || mutable.value.editor != null) return
        mutable.value = mutable.value.copy(page = page)
        saved["native_page"] = page.name
    }

    fun home() {
        if (mutable.value.busy || mutable.value.editor != null) return
        graph.runtime.selectChat(null)
        saved.remove<ArrayList<String>>("native_ref")
        saved.remove<String>("native_page")
        cursors.clear(); cursors.add(0)
        mutable.value = mutable.value.copy(project = null, items = emptyList(), hasNext = false, hasPrevious = false,
            agentMessages = emptyList(), approval = null, agentResults = emptyList())
        refresh()
    }

    fun nextPage() = action {
        val state = mutable.value
        val project = state.project ?: return@action
        if (!state.hasNext || state.items.isEmpty()) return@action
        val next = state.items.last().id.value
        loadPage(project.ref, next)
        mutable.value = mutable.value.copy(translationProgress = null)
        cursors.add(next)
        mutable.value = mutable.value.copy(hasPrevious = true)
    }

    fun previousPage() = action {
        val project = mutable.value.project ?: return@action
        if (cursors.size < 2) return@action
        val previous = cursors[cursors.lastIndex - 1]
        loadPage(project.ref, previous)
        mutable.value = mutable.value.copy(translationProgress = null)
        cursors.removeAt(cursors.lastIndex)
        mutable.value = mutable.value.copy(hasPrevious = cursors.size > 1)
    }

    fun edit(itemId: ItemId) = action {
        val ref = mutable.value.project?.ref ?: return@action
        val details = projects.item(ref, itemId) ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
        val draft = NativeEditDraft(ref, details.item, details.revision, details.item.fingerprint,
            details.item.translation, details.item.status, commandId())
        setDraft(draft)
    }

    fun editText(text: String) {
        if (mutable.value.busy) return
        if (text.length > TextProjectLimits().maxItemChars) { mutable.value = mutable.value.copy(error = R.string.native_limit); return }
        mutable.value.editor?.let { setDraft(it.changed(text = text, status = TextItemStatus.PROCESSED)) }
    }
    fun editStatus(status: TextItemStatus) {
        if (!mutable.value.busy) mutable.value.editor?.let { setDraft(it.changed(status = status)) }
    }
    fun closeEditor() { if (!mutable.value.busy) { clearDraft(); mutable.value = mutable.value.copy(editor = null) } }

    fun reloadEditor() = action {
        val previous = mutable.value.editor ?: return@action
        val details = projects.item(previous.ref, previous.item.id) ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
        setDraft(NativeEditDraft(previous.ref, details.item, details.revision, details.item.fingerprint,
            previous.text, previous.status, commandId()))
    }

    fun saveEditor() = action {
        val draft = mutable.value.editor ?: return@action
        if (mutable.value.project?.ref != draft.ref) throw TextProjectException(TextProjectException.Reason.WRONG_IDENTITY)
        val result = projects.edit(ManualTextEdit(draft.command, draft.ref, draft.item.id, draft.revision,
            draft.fingerprint, draft.text, draft.status))
        if (result.status == ManualEditStatus.COMMITTED) {
            clearDraft()
            val rows = mutable.value.items.map { item ->
                if (item.id == draft.item.id) ItemPreview(item.id, item.row, item.source, draft.text.take(240), draft.status, item.hasTranslatableSource) else item
            }
            mutable.value = mutable.value.copy(editor = null, items = rows,
                project = mutable.value.project?.copy(revision = result.revision), notice = R.string.native_saved)
        } else {
            setDraft(NativeEditDraft(draft.ref, draft.item, draft.revision, draft.fingerprint, draft.text,
                draft.status, draft.command, conflict = true))
            mutable.value = mutable.value.copy(error = R.string.native_conflict)
        }
    }

    /** Bind before showing the system picker; its late result never targets a different project. */
    fun prepareExport(kind: NativeExport): String? {
        val project = mutable.value.project ?: return null
        if (mutable.value.busy) return null
        saved["native_export"] = encodeRef(project.ref).apply { add(kind.name) }
        val name = project.name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").take(100)
        return when (kind) {
            NativeExport.PROJECT -> "$name.lnt"
            NativeExport.TRANSLATION -> "$name-translated.txt"
            NativeExport.BILINGUAL -> "$name-bilingual.txt"
            NativeExport.ORIGINAL -> "$name-original.txt"
        }
    }

    fun completeExport(uri: Uri?) {
        if (uri == null) { saved.remove<ArrayList<String>>("native_export"); return }
        val pending = saved.remove<ArrayList<String>>("native_export")?.toList() ?: return
        queuedAction {
            val ref = decodeRef(pending)
            val destination = { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException() }
            when (NativeExport.valueOf(pending[3])) {
                NativeExport.PROJECT -> projects.saveProject(ref, destination)
                NativeExport.TRANSLATION -> projects.exportTxt(ref, destination)
                NativeExport.BILINGUAL -> projects.exportBilingual(ref, true, destination)
                NativeExport.ORIGINAL -> projects.exportOriginal(ref, destination)
            }
            mutable.value = mutable.value.copy(notice = R.string.native_exported)
        }
    }

    private suspend fun openInternal(ref: LocalProjectRef) {
        val summary = projects.open(ref)
        val page = projects.items(ref, summary.revision, limit = PAGE_SIZE + 1)
        graph.runtime.selectChat(null)
        cursors.clear(); cursors.add(0)
        val same = restoreRef() == ref
        val selectedPage = if (same) runCatching { NativePage.valueOf(saved["native_page"] ?: "AGENT") }.getOrDefault(NativePage.AGENT) else NativePage.AGENT
        if (!same) clearDraft()
        saved["native_ref"] = encodeRef(ref)
        mutable.value = mutable.value.copy(project = summary, page = selectedPage, editor = null, translationProgress = null,
            items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) }, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false,
            agentMessages = emptyList(), approval = null, agentResults = emptyList())
        loadAgent(ref)
    }

    private suspend fun loadPage(ref: LocalProjectRef, after: Long) {
        val summary = projects.open(ref)
        val page = projects.items(ref, summary.revision, after, PAGE_SIZE + 1)
        mutable.value = mutable.value.copy(project = summary, items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) },
            hasNext = page.items.size > PAGE_SIZE, hasPrevious = cursors.size > 1)
    }

    private suspend fun restoreDraft(ref: LocalProjectRef) {
        val itemId = saved.get<Long>("draft_item") ?: return
        val details = projects.item(ref, ItemId(itemId)) ?: return
        val command = saved.get<String>("draft_command")?.let(::CommandId) ?: commandId()
        val receipt = projects.receipt(ref, command)
        if (receipt?.status == ManualEditStatus.COMMITTED) { clearDraft(); mutable.value = mutable.value.copy(notice = R.string.native_saved); return }
        val revision = saved.get<Long>("draft_revision") ?: details.revision
        val fingerprint = saved.get<String>("draft_fingerprint")?.let(::ObjectFingerprint) ?: details.item.fingerprint
        setDraft(NativeEditDraft(ref, details.item, revision, fingerprint,
            saved["draft_text"] ?: details.item.translation,
            saved.get<String>("draft_status")?.let(TextItemStatus::valueOf) ?: details.item.status,
            command, conflict = receipt != null || revision != details.revision || fingerprint != details.item.fingerprint))
    }

    private fun setDraft(draft: NativeEditDraft) {
        saved["draft_item"] = draft.item.id.value; saved["draft_revision"] = draft.revision
        saved["draft_fingerprint"] = draft.fingerprint.sha256; saved["draft_text"] = draft.text
        saved["draft_status"] = draft.status.name; saved["draft_command"] = draft.command.value
        mutable.value = mutable.value.copy(editor = draft)
    }
    private fun clearDraft() {
        for (key in listOf("draft_item", "draft_revision", "draft_fingerprint", "draft_text", "draft_status", "draft_command")) saved.remove<Any>(key)
    }
    private fun restoreRef(): LocalProjectRef? = saved.get<ArrayList<String>>("native_ref")?.let(::decodeRef)
    private fun encodeRef(ref: LocalProjectRef) = arrayListOf(ref.projectId.value, ref.instanceId.value, ref.generation.value.toString())
    private fun decodeRef(values: List<String>) = LocalProjectRef(ProjectId(values[0]), ProjectInstanceId(values[1]), RecoveryGeneration(values[2].toLong()))
    private fun commandId() = CommandId(UUID.randomUUID().toString())
    private fun importKey(native: Boolean) = if (native) "native_input_project" else "native_input_txt"

    private fun queuedAction(block: suspend () -> Unit) {
        viewModelScope.launch { state.first { !it.busy }; action(block) }
    }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        val id = ++actionId
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
        job = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { mutable.value = mutable.value.copy(notice = R.string.native_cancelled); throw error }
            catch (error: Exception) { mutable.value = mutable.value.copy(error = when (error) {
                is TextProjectException -> when (error.reason) {
                    TextProjectException.Reason.LIMIT_EXCEEDED -> R.string.native_limit
                    TextProjectException.Reason.INVALID_TEXT -> R.string.native_encoding_error
                    TextProjectException.Reason.UNSUPPORTED_VERSION -> R.string.native_version_error
                    TextProjectException.Reason.INVALID_FORMAT, TextProjectException.Reason.CORRUPT_PROJECT -> R.string.native_format_error
                    TextProjectException.Reason.STALE_PAGE, TextProjectException.Reason.COMMAND_CONFLICT -> R.string.native_conflict
                    else -> R.string.native_io_error
                }
                is InvalidAgentHistory -> R.string.native_agent_history_error
                is InvalidModelSettings -> R.string.native_model_settings_error
                is AgentApprovalUnavailable -> R.string.native_agent_load_error
                is InvalidToolArgument -> R.string.native_agent_invalid_item
                is AgentModelFailure -> when (error.kind) {
                    ModelFailureKind.AUTHENTICATION -> R.string.native_model_auth_error
                    ModelFailureKind.NETWORK, ModelFailureKind.TIMEOUT -> R.string.native_model_network_error
                    ModelFailureKind.RATE_LIMIT, ModelFailureKind.SERVER -> R.string.native_model_rate_error
                    ModelFailureKind.CONTEXT_OVERFLOW -> R.string.native_model_context_error
                    else -> R.string.native_agent_response_error
                }
                is com.breakyuna.noveltranslator.nativecore.agent.AgentAttemptUncertain,
                is com.breakyuna.noveltranslator.nativecore.agent.AgentResponseNotStored -> R.string.native_agent_uncertain
                is RuntimeUnavailable -> R.string.native_busy_error
                is SecurityException -> R.string.native_permission_error
                else -> R.string.native_io_error
            }) }
            finally { if (id == actionId) { mutable.value = mutable.value.copy(busy = false); job = null } }
        }
    }

    companion object { private const val PAGE_SIZE = 40 }
}
