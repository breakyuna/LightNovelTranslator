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
import com.breakyuna.noveltranslator.nativecore.engine.TextLiteralReplacer
import com.breakyuna.noveltranslator.nativecore.engine.ProjectQualityScanner
import com.breakyuna.noveltranslator.nativecore.engine.TextProofreadingInspector
import com.breakyuna.noveltranslator.nativecore.engine.AgentStreamPreviewController
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationHandle
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationRunner
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationRequest
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationStartFailed
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NativePage { AGENT, WORKBENCH, PROOFREADING, RULES }
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

class NativeEditorReplacement(val ref: LocalProjectRef, val command: CommandId, val preview: TextReplacementPreview) {
    override fun toString(): String = "NativeEditorReplacement(redacted)"
}

data class NativeProjectUiState(
    val projects: List<RegisteredProject> = emptyList(),
    val project: TextProjectSummary? = null,
    val page: NativePage = NativePage.AGENT,
    val items: List<ItemPreview> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val proofreadingQuery: TextItemQuery = TextItemQuery(),
    val itemStatistics: TextItemStatistics? = null,
    val matchingItems: Int? = null,
    val qualityView: TextQualitySelection? = null,
    val qualityProgress: TextQualityScanProgress? = null,
    val qualityInvalidated: Boolean = false,
    val selectingItems: Boolean = false,
    val selectedItems: Set<ItemId> = emptySet(),
    val manualBatch: ManualTextBatch? = null,
    val manualBatchConflict: Boolean = false,
    val editor: NativeEditDraft? = null,
    val editorQuality: TextProofreadingResult? = null,
    val editorReplacement: NativeEditorReplacement? = null,
    val busy: Boolean = false,
    val foregroundTranslationActive: Boolean = false,
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
    val recentRuns: List<StoredRun> = emptyList(),
    val runLoadIncomplete: Boolean = false,
    val modelCatalogLoading: Boolean = false,
    val agentMessageDetail: AgentMessageDetail? = null,
    val modelSettingsError: Int? = null,
    val policyDraft: NativePolicyDraft? = null,
    val policyLoadIncomplete: Boolean = false,
    val policyInvalidRuleId: String? = null,
) {
    val qualityCurrent: Boolean get() {
        val current = project ?: return false
        val index = qualityView?.index ?: return false
        return index.ref == current.ref && index.revision == current.revision && index.query == proofreadingQuery &&
            !qualityInvalidated && !policyLoadIncomplete && policyDraft?.dirty != true &&
            (policyDraft == null || policyDraft.base == index.policyFingerprint && policyDraft.revision <= index.revision)
    }
}

/** Small workspace controller. Every database/file action goes through the native port. */
class NativeProjectViewModel internal constructor(
    application: Application, private val saved: SavedStateHandle,
    private val projects: TextProjectPort, private val backgroundTranslations: ForegroundTranslationRunner,
) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved,
        (application as TranslatorApplication).nativeGraph.projects, application.nativeGraph.backgroundTranslations)

    private val graph = (application as TranslatorApplication).nativeGraph
    private val resolver = application.contentResolver
    private val mutable = MutableStateFlow(NativeProjectUiState(recoveryProblems = saved["native_recovery_problems"] ?: 0, encoding =
        runCatching { TxtEncoding.valueOf(saved["native_encoding"] ?: "UTF8") }.getOrDefault(TxtEncoding.UTF8)))
    val state = mutable.asStateFlow()
    private val agentPreviews = AgentStreamPreviewController()
    val agentStream = agentPreviews.state
    private var job: Job? = null
    private var agentLease: RunLease? = null
    private var agentChat = ChatId("native-agent-main")
    private val cursors = mutableListOf(0L)
    private var actionId = 0L
    private var observedSimulation: ForegroundTranslationHandle? = null

    init { action {
        val snapshot = backgroundTranslations.state.value
        val live = snapshot?.takeIf { it.phase.active }
        val ref = live?.request?.ref ?: restoreRef()
        // Resolve ownership before any fallible IO. Refresh failures must never detach a live run.
        val attachment = snapshot?.takeIf { it.request.ref == ref &&
            (it.phase.active || saved.get<String>("native_simulation_token") == it.token) }
            ?.let { backgroundTranslations.attach(it.token) }
        if (attachment != null) {
            observedSimulation = attachment
            mutable.value = mutable.value.copy(foregroundTranslationActive = live != null)
            if (live != null) saved["native_recovery_pending"] = true
            try {
                refreshSimulationView(running = true) {
                    initializeProjects(application, recover = live == null)
                    openInternal(attachment.request.ref)
                }
                refreshSimulationView(running = true) { restoreSimulationPage(attachment) }
                awaitSimulation(attachment)
            } finally { observedSimulation = null }
        } else {
            initializeProjects(application, recover = true)
            ref?.let { openInternal(it); restoreDraft(it) }
            saved.remove<String>("native_simulation_token")
            saved.remove<ArrayList<Long>>("native_simulation_cursors")
        }
    } }

    private suspend fun initializeProjects(application: Application, recover: Boolean) {
        // Directory recovery takes a write reservation. A live foreground task already owns it.
        if (recover) recoverDirectory()
        try { graph.runStore.recoverInterrupted() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(runLoadIncomplete = true) }
        val registered = projects.registeredProjects()
        val legacy = withContext(Dispatchers.IO) { application.getDatabasePath("light_novel_translation_platform.db").exists() }
        mutable.value = mutable.value.copy(projects = registered, legacyData = legacy)
    }

    private suspend fun recoverDirectory() {
        val recovery = projects.recoverProjects()
        saved["native_recovery_problems"] = recovery.problems.size
        saved["native_recovery_pending"] = false
        mutable.value = mutable.value.copy(recoveryProblems = recovery.problems.size)
    }

    fun changeEncoding(value: TxtEncoding) { if (!mutable.value.busy) {
        saved["native_encoding"] = value.name
        mutable.value = mutable.value.copy(encoding = value)
    } }
    fun dismissMessage() { mutable.value = mutable.value.copy(error = null, notice = null) }
    fun cancelAction() {
        val running = backgroundTranslations.state.value
        if (running?.phase?.active == true && (running.token == observedSimulation?.token ||
            running.request.ref == mutable.value.project?.ref)) {
            backgroundTranslations.stop(running.token)
            return // Keep waiting until durable cleanup releases the actual reservation.
        }
        graph.translations.stop(); agentLease?.let { graph.runtime.requestStop(it) }; job?.cancel()
    }
    fun cleanupOutputs() = action {
        projects.cleanupTemporaryOutputs()
        mutable.value = mutable.value.copy(notice = R.string.native_cleaned)
    }
    fun refresh() = action {
        recoverDirectory()
        val registered = projects.registeredProjects()
        mutable.value = mutable.value.copy(projects = registered)
        mutable.value.project?.let {
            if (mutable.value.qualityView != null) {
                val page = readStandardProofreadingPage(it.ref, mutable.value.proofreadingQuery, 0)
                cursors.clear(); cursors.add(0)
                mutable.value = mutable.value.copy(qualityView = null, qualityInvalidated = false, qualityProgress = null,
                    project = page.summary, items = page.items.take(PAGE_SIZE).map { item -> ItemPreview.of(item) },
                    itemStatistics = page.statistics, matchingItems = page.matchingItems,
                    hasNext = page.items.size > PAGE_SIZE, hasPrevious = false)
            } else loadPage(it.ref, cursors.last())
            loadAgent(it.ref)
            if (mutable.value.policyDraft?.dirty != true) loadTextPolicy(it.ref, restore = false)
        }
    }

    fun open(ref: LocalProjectRef) = action { openInternal(ref) }
    fun copy(ref: LocalProjectRef) = action {
        val result = projects.copyProject(commandId(), ref)
        mutable.value = mutable.value.copy(projects = projects.registeredProjects())
        openInternal(result.ref)
    }

    fun saveAgentModel(endpoint: String, model: String, key: String?, onSaved: () -> Unit) = action {
        mutable.value = mutable.value.copy(modelSettingsError = null)
        try {
            val settings = graph.modelSettings.save(endpoint, model, key)
            mutable.value = mutable.value.copy(modelSettings = settings, notice = R.string.native_model_saved)
            onSaved()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutable.value = mutable.value.copy(modelSettingsError = if (error is InvalidModelSettings) R.string.native_model_settings_error else R.string.native_io_error)
        }
    }
    fun fetchAgentModels(endpoint: String, key: String, savedCredential: ModelConfigurationRef?, onLoaded: (List<String>) -> Unit) = action {
        mutable.value = mutable.value.copy(modelCatalogLoading = true, modelSettingsError = null)
        try {
            val models = graph.modelCatalog.models(endpoint, key, savedCredential)
            currentCoroutineContext().ensureActive()
            onLoaded(models)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutable.value = mutable.value.copy(modelSettingsError = if (error is InvalidModelSettings) R.string.native_model_settings_error
                else if (error is AgentModelFailure && error.kind == ModelFailureKind.AUTHENTICATION) R.string.native_models_auth_error
                else R.string.native_models_fetch_error)
        } finally { mutable.value = mutable.value.copy(modelCatalogLoading = false) }
    }
    fun inspectAgentMessage(id: String) = action {
        val ref = mutable.value.project?.ref ?: return@action
        val detail = graph.agentStore.messageDetail(ref, agentChat, id)
        mutable.value = mutable.value.copy(agentMessageDetail = detail)
    }
    fun closeAgentMessage() { mutable.value = mutable.value.copy(agentMessageDetail = null) }
    private suspend fun refreshAgentTimeline(ref: LocalProjectRef, chat: ChatId) {
        val messages = graph.agentStore.messages(ref, chat)
        withContext(Dispatchers.Main.immediate) {
            if (mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
                mutable.value = mutable.value.copy(agentMessages = messages)
        }
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
        agentPreviews.clear()
        loadAgent(ref)
    }
    fun newAgentChat() = action {
        val ref = mutable.value.project?.ref ?: return@action
        graph.agentStore.newChat(ref)
        agentPreviews.clear()
        loadAgent(ref)
    }
    fun sendAgentMessage() = action {
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val ref = mutable.value.project?.ref ?: return@action
        val configuration = mutable.value.modelSettings?.reference ?: return@action
        val text = mutable.value.agentInput
        if (text.isBlank() || mutable.value.approval != null) return@action
        try {
            agentRun(ref) { lease, recorder ->
                val session = agentPreviews.open(ref, lease.identity)
                var completed = false
                try {
                    val result = graph.conversations.send(lease, ref, configuration, text, recorder, preview = session,
                        updated = { refreshAgentTimeline(ref, requireNotNull(lease.identity.chatId)) }) {
                        withContext(Dispatchers.Main.immediate) {
                            saved.remove<String>("agent_input_text")
                            mutable.value = mutable.value.copy(agentInput = "")
                        }
                    }
                    completed = true
                    result
                } finally { session.close(keepPartial = !completed) }
            }
        } finally { withContext(NonCancellable) { loadAgent(ref) } }
    }

    fun queryAgentProject() = action {
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val ref = mutable.value.project?.ref ?: return@action
        try {
            agentRun(ref) { lease, _ ->
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
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val ref = mutable.value.project?.ref ?: return@action
        val item = ItemId(ToolArguments.long(ToolScalar.NumberToken(itemIdText), "itemId", 1, Long.MAX_VALUE))
        try {
            agentRun(ref) { lease, _ -> graph.agentQueries.prepare(lease, ref, graph.agentStore, item, translation) }
        } finally { withContext(NonCancellable) { loadAgent(ref) } }
    }

    fun rejectAgentEdit() = action {
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val approval = mutable.value.approval ?: return@action
        graph.agentStore.reject(approval.ref, approval.id, approval.digest)
        loadAgent(approval.ref)
    }

    fun allowAgentEdit() = action {
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val approval = mutable.value.approval ?: return@action
        if (approval.state != TextApprovalState.PENDING) return@action
        try {
            val result = agentRun(approval.ref, approval.runId, approval.commandId) { lease, recorder ->
                graph.agentStore.apply(lease, approval.ref, approval.id, approval.digest).also { recorder.approvalResult(it) }
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
            agentLoadIncomplete = incomplete, agentMessageDetail = null)
        loadRuns(ref)
    }

    private suspend fun <T> agentRun(ref: LocalProjectRef, runId: RunId = RunId(UUID.randomUUID().toString()),
                                     command: CommandId = commandId(), block: suspend (RunLease, RunProgressRecorder) -> T): T {
        graph.runtime.selectChat(ChatIdentity(ref.projectId, agentChat))
        agentPreviews.clear()
        try {
            return graph.runs.execute(ref, RunIdentity(ref.projectId, agentChat, runId, command), RuntimeOwner.AGENT) { lease, recorder ->
                agentLease = lease
                try {
                    val result = block(lease, recorder)
                    if (graph.agentStore.pending(ref, agentChat)?.runId == runId) recorder.waitingForApproval()
                    result
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { graph.agentStore.cancelPending(ref, runId) }
                    throw cancelled
                }
            }
        } finally {
            agentLease = null
            graph.runtime.selectChat(null)
        }
    }

    private suspend fun loadRuns(ref: LocalProjectRef) {
        try {
            graph.runStore.recoverInterrupted()
            val runs = graph.runStore.recent(ref)
            mutable.value = mutable.value.copy(recentRuns = runs, runLoadIncomplete = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Keep same-project facts visible, but never hide a recovery/journal failure.
            mutable.value = mutable.value.copy(runLoadIncomplete = true)
        }
    }

    private suspend fun loadTextPolicy(ref: LocalProjectRef, restore: Boolean) {
        try {
            var snapshot = graph.projects.textPolicy(ref)
            val savedDraft = if (restore) decodePolicyDraft(saved.get<ArrayList<String>>("native_policy_draft"))?.takeIf { it.ref == ref } else null
            val receipt = savedDraft?.let { graph.projects.textPolicyReceipt(ProjectPolicyEdit(it.command, it.ref, it.revision, it.base, it.policy)) }
            if (receipt?.status == PolicyEditStatus.COMMITTED) {
                // The save may have committed just before cancellation hid its return value.
                // Re-read latest facts; never replace them with the old submitted draft.
                snapshot = graph.projects.textPolicy(ref)
                check(snapshot.revision >= receipt.revision)
            }
            val draft = if (savedDraft != null && receipt?.status != PolicyEditStatus.COMMITTED) NativePolicyDraft(savedDraft.ref, savedDraft.revision, savedDraft.base,
                savedDraft.policy, savedDraft.command, savedDraft.conflict || receipt != null || snapshot.revision != savedDraft.revision || snapshot.fingerprint != savedDraft.base)
                else NativePolicyDraft.from(snapshot)
            mutable.value = mutable.value.copy(policyDraft = draft, policyLoadIncomplete = false, policyInvalidRuleId = null, editorQuality = null)
            if (draft.dirty) saved["native_policy_draft"] = encodePolicyDraft(draft)
            else saved.remove<ArrayList<String>>("native_policy_draft")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(policyLoadIncomplete = true) }
    }

    private suspend fun refreshCleanTextPolicy(ref: LocalProjectRef) {
        // Item commits advance the shared revision. Only an untouched draft may
        // adopt the latest snapshot; a dirty draft must retain its conflict baseline.
        if (mutable.value.project?.ref == ref && mutable.value.policyDraft?.dirty != true)
            loadTextPolicy(ref, restore = false)
    }

    fun changeTextPolicy(policy: ProjectTextPolicy) {
        if (mutable.value.busy || mutable.value.policyLoadIncomplete) return
        val draft = mutable.value.policyDraft ?: return
        if (draft.ref != mutable.value.project?.ref) return
        try {
            ProjectTextPolicyCodec.encode(policy)
            val changed = draft.changed(policy)
            mutable.value = mutable.value.copy(policyDraft = changed, policyInvalidRuleId = null, editorQuality = null)
            if (changed.dirty) saved["native_policy_draft"] = encodePolicyDraft(changed)
            else saved.remove<ArrayList<String>>("native_policy_draft")
        } catch (_: IllegalArgumentException) { mutable.value = mutable.value.copy(error = R.string.native_policy_invalid) }
    }

    fun reloadTextPolicy() = action {
        val ref = mutable.value.project?.ref ?: return@action
        loadTextPolicy(ref, restore = false)
    }

    fun saveTextPolicy() = action {
        val draft = mutable.value.policyDraft ?: return@action
        if (!draft.dirty || draft.conflict || draft.ref != mutable.value.project?.ref || mutable.value.policyLoadIncomplete) return@action
        try { withContext(Dispatchers.Default) { TextRuleValidation.validate(draft.policy) { ensureActive() } } }
        catch (error: InvalidTextRule) {
            mutable.value = mutable.value.copy(error = R.string.native_policy_invalid, policyInvalidRuleId = error.ruleId)
            return@action
        }
        val receipt = graph.projects.editTextPolicy(ProjectPolicyEdit(draft.command, draft.ref, draft.revision, draft.base, draft.policy))
        if (receipt.status != PolicyEditStatus.COMMITTED) {
            val rejected = NativePolicyDraft(draft.ref, draft.revision, draft.base, draft.policy, draft.command, true)
            saved["native_policy_draft"] = encodePolicyDraft(rejected)
            mutable.value = mutable.value.copy(policyDraft = rejected, error = R.string.native_conflict)
            return@action
        }
        // The receipt is already a committed fact. Refresh failure cannot turn it into a rollback.
        saved.remove<ArrayList<String>>("native_policy_draft")
        mutable.value = mutable.value.copy(policyDraft = NativePolicyDraft.from(ProjectPolicySnapshot(draft.ref, receipt.revision, draft.policy)),
            project = mutable.value.project?.let { it.copy(revision = maxOf(it.revision, receipt.revision)) }, notice = R.string.native_saved)
        try {
            loadTextPolicy(draft.ref, restore = false)
            if (mutable.value.qualityView == null) loadPage(draft.ref, cursors.last())
            else {
                val rows = mutable.value.items
                if (rows.isNotEmpty()) {
                    val page = projects.readItemWindow(draft.ref, mutable.value.proofreadingQuery, TextItemWindow(rows.map { it.id }))
                    mutable.value = mutable.value.copy(project = page.summary, items = page.items.map { ItemPreview.of(it) },
                        itemStatistics = page.statistics, matchingItems = null)
                }
            }
            if (mutable.value.policyLoadIncomplete) mutable.value = mutable.value.copy(error = R.string.native_policy_refresh_failed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(policyLoadIncomplete = true, error = R.string.native_policy_refresh_failed) }
    }

    fun simulatePage() = simulate(com.breakyuna.noveltranslator.nativecore.engine.TranslationScope.PAGE)
    fun simulateProject() = simulate(com.breakyuna.noveltranslator.nativecore.engine.TranslationScope.PROJECT)

    fun translateRemote(ref: LocalProjectRef, revision: Long, configuration: ModelConfigurationRef,
        options: RemoteTranslationOptions, wholeProject: Boolean) = startTranslation(
        if (wholeProject) com.breakyuna.noveltranslator.nativecore.engine.TranslationScope.PROJECT
        else com.breakyuna.noveltranslator.nativecore.engine.TranslationScope.PAGE, ref, revision, configuration, options)

    private fun simulate(scope: com.breakyuna.noveltranslator.nativecore.engine.TranslationScope) =
        startTranslation(scope)

    /** Both scopes require explicit confirmation; the project scope starts at its first item. */
    private fun startTranslation(scope: com.breakyuna.noveltranslator.nativecore.engine.TranslationScope,
        confirmedRef: LocalProjectRef? = null, confirmedRevision: Long? = null,
        configuration: ModelConfigurationRef = graph.simulationConfiguration,
        options: RemoteTranslationOptions? = null) = action {
        if (mutable.value.runLoadIncomplete || mutable.value.policyLoadIncomplete) return@action
        if (mutable.value.policyDraft?.dirty == true) { mutable.value = mutable.value.copy(error = R.string.native_policy_unsaved); return@action }
        val project = mutable.value.project ?: return@action
        if (options != null) {
            if (confirmedRef != project.ref || confirmedRevision != project.revision)
                throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            graph.modelSettings.resolve(configuration) // No latest-model fallback after confirmation.
        }
        if (mutable.value.editor != null || mutable.value.proofreadingQuery.active || mutable.value.qualityView != null || mutable.value.manualBatch != null || mutable.value.selectingItems) return@action
        val ref = project.ref
        val wholeProject = scope == com.breakyuna.noveltranslator.nativecore.engine.TranslationScope.PROJECT
        if (wholeProject) {
            val page = projects.readPage(ref, 0, 1)
            if ((page.statistics?.translatableUnprocessed ?: 0) == 0) return@action
        }
        val after = if (wholeProject) 0L else cursors.last()
        mutable.value = mutable.value.copy(translationProgress = null)
        val handle = backgroundTranslations.prepare(ForegroundTranslationRequest(ref,
            RunIdentity(ref.projectId, null, RunId(UUID.randomUUID().toString()), commandId()),
            configuration, project.revision, after, PAGE_SIZE, scope, options))
        mutable.value = mutable.value.copy(foregroundTranslationActive = true)
        if (wholeProject) { cursors.clear(); cursors.add(0L) }
        saved["native_simulation_token"] = handle.token
        saved["native_simulation_cursors"] = ArrayList(cursors)
        try {
            NativeTranslationService.start(getApplication<Application>(), handle.token)
        } catch (_: Exception) { backgroundTranslations.startFailed(handle.token) }
        awaitSimulation(handle)
    }

    private suspend fun restoreSimulationPage(handle: ForegroundTranslationHandle) {
        val request = handle.request
        val restored = simulationCursors(saved["native_simulation_token"], handle.token,
            saved.get<ArrayList<Long>>("native_simulation_cursors"), request.afterId)
        cursors.clear()
        cursors.addAll(restored)
        saved["native_simulation_token"] = handle.token
        saved["native_simulation_cursors"] = ArrayList(cursors)
        saved["native_ref"] = encodeRef(request.ref)
        saved["native_page"] = NativePage.PROOFREADING.name
        saveProofreadingQuery(request.ref, TextItemQuery())
        mutable.value = mutable.value.copy(page = NativePage.PROOFREADING, proofreadingQuery = TextItemQuery(), qualityView = null, qualityInvalidated = false, qualityProgress = null)
        loadPage(request.ref, request.afterId)
    }

    private suspend fun refreshSimulationView(running: Boolean, block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(error = if (running) R.string.native_background_refresh_failed else R.string.native_io_error)
        }
    }

    private suspend fun awaitSimulation(handle: ForegroundTranslationHandle) = coroutineScope {
        val observer = launch {
            backgroundTranslations.state.collect { snapshot ->
                if (snapshot?.token == handle.token)
                    mutable.value = mutable.value.copy(translationProgress = snapshot.progress,
                        foregroundTranslationActive = snapshot.phase.active)
            }
        }
        try {
            val result = handle.await()
            mutable.value = mutable.value.copy(translationProgress = result,
                notice = if (handle.request.options == null) R.string.native_simulation_done else R.string.native_remote_done)
        } finally {
            observer.cancel()
            // A destroyed UI leaves a live service alone. A settled run re-reads committed facts.
            val snapshot = backgroundTranslations.state.value
            if (snapshot?.token == handle.token && !snapshot.phase.active) {
                mutable.value = mutable.value.copy(foregroundTranslationActive = false)
                withContext(NonCancellable) {
                    if (mutable.value.error == R.string.native_background_refresh_failed)
                        mutable.value = mutable.value.copy(error = null)
                    // Recovery was deferred by a live lease. Retry only after durable cleanup,
                    // independently of the page refresh, and retain old warnings on failure.
                    if (saved.get<Boolean>("native_recovery_pending") == true) refreshSimulationView(running = false) {
                        recoverDirectory()
                        mutable.value = mutable.value.copy(projects = projects.registeredProjects())
                    }
                    refreshSimulationView(running = false) { loadPage(handle.request.ref, handle.request.afterId) }
                    loadTextPolicy(handle.request.ref, restore = true)
                    loadRuns(handle.request.ref)
                }
                if (saved.get<String>("native_simulation_token") == handle.token) {
                    saved.remove<String>("native_simulation_token")
                    saved.remove<ArrayList<Long>>("native_simulation_cursors")
                }
            }
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

    /** Bind before the picker; an imported format asset always targets this project revision. */
    fun prepareAddAsset(): Boolean {
        val project = mutable.value.project ?: return false
        if (mutable.value.busy || mutable.value.policyDraft?.dirty == true) return false
        saved["native_add_asset"] = arrayListOf(commandId().value, project.revision.toString())
        return true
    }

    fun addAssetFile(uri: Uri?) {
        val intent = saved.get<ArrayList<String>>("native_add_asset")?.toList()
        if (uri == null) { saved.remove<ArrayList<String>>("native_add_asset"); return }
        if (intent == null) return
        queuedAction {
            val project = mutable.value.project ?: return@queuedAction
            if (intent[1].toLongOrNull() != project.revision) {
                mutable.value = mutable.value.copy(error = R.string.native_conflict)
                return@queuedAction
            }
            val input = withContext(Dispatchers.IO) {
                val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "asset.bin"
                val basename = rawName.substringAfterLast('/').substringAfterLast('\\')
                    .map { if (it.isISOControl()) '_' else it }.joinToString("").take(180).ifBlank { "asset.bin" }
                val output = ByteArrayOutputStream()
                val coroutine = currentCoroutineContext()
                resolver.openInputStream(uri)?.use { stream ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (output.size().toLong() + read > 64L * 1024 * 1024) {
                            throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                        }
                        output.write(buffer, 0, read)
                    }
                } ?: throw java.io.IOException()
                basename to output.toByteArray()
            }
            val receipt = graph.formatProjects.importAsset(CommandId(intent[0]), project.ref, project.revision,
                RelativePath(input.first), input.second)
            if (receipt.status != AssetMutationStatus.COMMITTED) {
                mutable.value = mutable.value.copy(error = R.string.native_conflict)
                return@queuedAction
            }
            saved.remove<ArrayList<String>>("native_add_asset")
            openInternal(project.ref)
        }
    }

    fun prepareAssetExport(assetId: Long): String? {
        val project = mutable.value.project ?: return null
        if (mutable.value.busy) return null
        val asset = project.assets.firstOrNull { it.id == assetId } ?: return null
        saved["native_asset_export"] = arrayListOf(encodeRef(project.ref).joinToString("|"), assetId.toString())
        val name = asset.path.value.substringAfterLast('/').take(180)
        return name.ifBlank { "asset-${asset.id}" }
    }

    fun completeAssetExport(uri: Uri?) {
        val pending = saved.get<ArrayList<String>>("native_asset_export")
        if (uri == null) { saved.remove<ArrayList<String>>("native_asset_export"); return }
        if (pending == null) return
        val ref = decodeRef(pending[0].split("|"))
        val assetId = pending[1].toLongOrNull() ?: return
        queuedAction {
            val destination = { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException() }
            graph.formatProjects.exportAsset(ref, assetId, FormatWriteOptions(), destination)
            saved.remove<ArrayList<String>>("native_asset_export")
            mutable.value = mutable.value.copy(notice = R.string.native_exported)
        }
    }

    fun selectPage(page: NativePage) {
        if ((mutable.value.busy && !mutable.value.foregroundTranslationActive) || mutable.value.editor != null || mutable.value.manualBatch != null) return
        mutable.value = mutable.value.copy(page = page)
        saved["native_page"] = page.name
    }

    fun discardPolicyAndHome() {
        if (mutable.value.busy) return
        saved.remove<ArrayList<String>>("native_policy_draft")
        mutable.value = mutable.value.copy(policyDraft = null, policyInvalidRuleId = null)
        home()
    }

    fun home() {
        if (mutable.value.busy || mutable.value.editor != null || mutable.value.manualBatch != null || mutable.value.policyDraft?.dirty == true || graph.runtime.snapshot().activeRun != null) return
        agentPreviews.clear()
        saved.remove<ArrayList<String>>("native_policy_draft")
        graph.runtime.selectChat(null)
        saved.remove<ArrayList<String>>("native_ref")
        saved.remove<String>("native_page")
        clearProofreadingQuery()
        cursors.clear(); cursors.add(0)
        mutable.value = mutable.value.copy(project = null, items = emptyList(), hasNext = false, hasPrevious = false,
            proofreadingQuery = TextItemQuery(), itemStatistics = null, matchingItems = null, qualityView = null, qualityInvalidated = false, qualityProgress = null,
            selectingItems = false, selectedItems = emptySet(), manualBatch = null, manualBatchConflict = false,
            agentMessages = emptyList(), approval = null, agentResults = emptyList(), recentRuns = emptyList(), runLoadIncomplete = false,
            agentMessageDetail = null, policyDraft = null, policyLoadIncomplete = false, policyInvalidRuleId = null)
        refresh()
    }

    private val qualityScanner get() = ProjectQualityScanner(projects, graph.projects)

    fun scanQuality(options: TextProofreadingOptions) = action {
        val state = mutable.value
        val project = state.project ?: return@action
        if (state.editor != null || state.manualBatch != null || state.selectingItems ||
            state.policyLoadIncomplete || state.policyDraft?.dirty == true || graph.runtime.snapshot().activeRun != null) return@action
        try {
            val index = qualityScanner.scan(project.ref, project.revision, state.proofreadingQuery, options) { progress ->
                mutable.update { current -> if (current.project?.ref == project.ref) current.copy(qualityProgress = progress) else current }
            }
            val selection = withContext(Dispatchers.Default) { index.select(TextQualityFilter()) { ensureActive() } }
            val page = qualityScanner.page(selection, limit = PAGE_SIZE + 1)
            cursors.clear(); cursors.add(0)
            mutable.value = mutable.value.copy(qualityView = selection, qualityInvalidated = false,
                project = page.summary, items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) },
                matchingItems = page.matchingItems, itemStatistics = page.statistics,
                hasNext = page.items.size > PAGE_SIZE, hasPrevious = false,
                selectingItems = false, selectedItems = emptySet())
        } finally { mutable.update { it.copy(qualityProgress = null) } }
    }

    fun filterQuality(filter: TextQualityFilter) = action {
        val state = mutable.value
        val view = state.qualityView ?: return@action
        if (!state.qualityCurrent || state.editor != null || state.manualBatch != null || state.selectingItems) return@action
        val selection = withContext(Dispatchers.Default) { view.index.select(filter) { ensureActive() } }
        val page = qualityScanner.page(selection, limit = PAGE_SIZE + 1)
        cursors.clear(); cursors.add(0)
        mutable.value = mutable.value.copy(qualityView = selection, project = page.summary,
            items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) }, itemStatistics = page.statistics,
            matchingItems = page.matchingItems, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false)
    }

    fun clearQuality() = action {
        val state = mutable.value
        val ref = state.project?.ref ?: return@action
        if (state.editor != null || state.manualBatch != null || state.selectingItems) return@action
        val page = readStandardProofreadingPage(ref, state.proofreadingQuery, 0)
        cursors.clear(); cursors.add(0)
        mutable.value = mutable.value.copy(qualityView = null, qualityInvalidated = false, qualityProgress = null,
            project = page.summary, items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) },
            itemStatistics = page.statistics, matchingItems = page.matchingItems,
            hasNext = page.items.size > PAGE_SIZE, hasPrevious = false)
    }

    /** Apply explicitly; typing and changing draft controls never read or write the project. */
    fun queryProofreading(query: TextItemQuery) = action {
        val state = mutable.value
        val ref = state.project?.ref ?: return@action
        if (state.editor != null || state.manualBatch != null || graph.runtime.snapshot().activeRun != null) return@action
        val page = readStandardProofreadingPage(ref, query, 0)
        cursors.clear(); cursors.add(0)
        saveProofreadingQuery(ref, query)
        mutable.value = mutable.value.copy(project = page.summary, proofreadingQuery = query, qualityView = null, qualityInvalidated = false, qualityProgress = null,
            items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) }, itemStatistics = page.statistics,
            matchingItems = page.matchingItems, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false,
            selectingItems = false, selectedItems = emptySet(),
            translationProgress = null)
    }

    fun selectItems(enabled: Boolean) {
        if (mutable.value.busy || mutable.value.editor != null || mutable.value.manualBatch != null) return
        mutable.value = mutable.value.copy(selectingItems = enabled, selectedItems = emptySet())
    }
    fun toggleItem(id: ItemId) {
        val state = mutable.value
        if (state.busy || !state.selectingItems || state.manualBatch != null || state.items.none { it.id == id }) return
        mutable.value = state.copy(selectedItems = if (id in state.selectedItems) state.selectedItems - id else state.selectedItems + id)
    }
    fun selectPageItems() {
        val state = mutable.value
        if (!state.busy && state.selectingItems && state.manualBatch == null)
            mutable.value = state.copy(selectedItems = state.items.map { it.id }.toSet())
    }
    fun prepareManualBatch(change: TextBatchChange) = action {
        val state = mutable.value
        val project = state.project ?: return@action
        if (!state.selectingItems || state.editor != null || state.manualBatch != null || state.selectedItems.isEmpty()) return@action
        val ids = state.items.filter { it.id in state.selectedItems }.map { it.id }
        if (ids.size != state.selectedItems.size) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        val batch = projects.prepareBatch(commandId(), project.ref, project.revision, TextItemWindow(ids), change)
        if (batch == null) mutable.value = mutable.value.copy(notice = R.string.native_batch_no_changes)
        else {
            saved["manual_batch"] = encodeManualBatch(batch)
            saved["manual_batch_conflict"] = false
            mutable.value = mutable.value.copy(manualBatch = batch, manualBatchConflict = false)
        }
    }
    fun dismissManualBatch(expected: ManualTextBatch) {
        if (mutable.value.busy || mutable.value.manualBatch?.digest() != expected.digest()) return
        saved.remove<ArrayList<String>>("manual_batch"); saved.remove<Boolean>("manual_batch_conflict")
        mutable.value = mutable.value.copy(manualBatch = null, manualBatchConflict = false)
    }
    fun confirmManualBatch(expected: ManualTextBatch) = action {
        val batch = mutable.value.manualBatch ?: return@action
        if (batch.commandId != expected.commandId || batch.digest() != expected.digest()) return@action
        if (mutable.value.manualBatchConflict || mutable.value.project?.ref != batch.ref) return@action
        val receipt = projects.editBatch(batch)
        if (receipt.status != ManualEditStatus.COMMITTED) {
            saved["manual_batch_conflict"] = true
            mutable.value = mutable.value.copy(manualBatchConflict = true, error = R.string.native_conflict)
            return@action
        }
        saved.remove<ArrayList<String>>("manual_batch"); saved.remove<Boolean>("manual_batch_conflict")
        val applyPreview = (mutable.value.project?.revision ?: Long.MAX_VALUE) <= receipt.revision
        val previews = mutable.value.items.map { item ->
            if (applyPreview && batch.targets.any { it.id == item.id }) ItemPreview(item.id, item.row, item.source,
                if (batch.change.clearsTranslation) "" else item.translation, batch.change.status, item.hasTranslatableSource) else item
        }
        mutable.value = mutable.value.copy(manualBatch = null, manualBatchConflict = false,
            selectingItems = false, selectedItems = emptySet(), items = previews,
            project = mutable.value.project?.let { it.copy(revision = maxOf(it.revision, receipt.revision)) },
            itemStatistics = null, matchingItems = null, notice = R.string.native_batch_done)
        refreshCleanTextPolicy(batch.ref)
        if (previews.isNotEmpty()) {
            try {
                val page = projects.readItemWindow(batch.ref, mutable.value.proofreadingQuery, TextItemWindow(previews.map { it.id }))
                mutable.value = mutable.value.copy(project = page.summary, items = page.items.map { ItemPreview.of(it) },
                    itemStatistics = page.statistics, matchingItems = page.matchingItems)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = mutable.value.copy(error = R.string.native_batch_refresh_failed) }
        }
    }

    fun nextPage() = action {
        val state = mutable.value
        val project = state.project ?: return@action
        if (!state.hasNext || state.items.isEmpty() || state.manualBatch != null) return@action
        val next = state.items.last().id.value
        loadPage(project.ref, next)
        mutable.value = mutable.value.copy(translationProgress = null)
        cursors.add(next)
        mutable.value = mutable.value.copy(hasPrevious = true)
    }

    fun previousPage() = action {
        if (mutable.value.manualBatch != null) return@action
        val project = mutable.value.project ?: return@action
        if (cursors.size < 2) return@action
        val previous = cursors[cursors.lastIndex - 1]
        loadPage(project.ref, previous)
        mutable.value = mutable.value.copy(translationProgress = null)
        cursors.removeAt(cursors.lastIndex)
        mutable.value = mutable.value.copy(hasPrevious = cursors.size > 1)
    }

    fun edit(itemId: ItemId) = action {
        if (mutable.value.selectingItems || mutable.value.manualBatch != null) return@action
        val ref = mutable.value.project?.ref ?: return@action
        val details = projects.item(ref, itemId) ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
        val draft = NativeEditDraft(ref, details.item, details.revision, details.item.fingerprint,
            details.item.translation, details.item.status, commandId())
        setDraft(draft)
    }

    fun prepareEditorReplacement(options: TextReplacementOptions) = action {
        val draft = mutable.value.editor ?: return@action
        mutable.value = mutable.value.copy(editorReplacement = null)
        val preview = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            TextLiteralReplacer.preview(draft.text, options) { context.ensureActive() }
        }
        if (mutable.value.editor === draft && mutable.value.project?.ref == draft.ref)
            mutable.value = mutable.value.copy(editorReplacement = NativeEditorReplacement(draft.ref, draft.command, preview))
    }

    fun applyEditorReplacement(expected: NativeEditorReplacement) {
        val state = mutable.value
        val draft = state.editor ?: return
        if (state.busy || state.editorReplacement !== expected || expected.ref != draft.ref || expected.command != draft.command ||
            draft.ref != state.project?.ref || !expected.preview.changed) return
        setDraft(draft.changed(text = expected.preview.text, status = TextItemStatus.PROCESSED))
    }

    fun navigateEditor(direction: TextItemDirection, discardDirty: Boolean = false) = action {
        val state = mutable.value
        val draft = state.editor ?: return@action
        if (draft.conflict || draft.ref != state.project?.ref || draft.dirty && !discardDirty) return@action
        val view = state.qualityView
        val details = if (view != null) {
            if (!state.qualityCurrent) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            val position = view.ids.binarySearchBy(draft.item.id.value) { it.value }
            if (position < 0) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            val next = position + if (direction == TextItemDirection.NEXT) 1 else -1
            view.ids.getOrNull(next)?.let { id ->
                val item = projects.item(draft.ref, id) ?: throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
                if (item.revision != draft.revision || view.index.fact(id)?.fingerprint != item.item.fingerprint)
                    throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
                item
            }
        } else graph.projects.adjacentTextItem(draft.ref, draft.revision, state.proofreadingQuery, draft.item.id, direction)
        if (details == null) mutable.value = mutable.value.copy(notice = R.string.native_editor_boundary)
        else setDraft(NativeEditDraft(draft.ref, details.item, details.revision, details.item.fingerprint,
            details.item.translation, details.item.status, commandId()))
    }

    fun inspectEditor(options: TextProofreadingOptions) = action {
        val draft = mutable.value.editor ?: return@action
        mutable.value = mutable.value.copy(editorQuality = null)
        if (draft.conflict || mutable.value.policyLoadIncomplete || mutable.value.policyDraft?.dirty == true) {
            mutable.value = mutable.value.copy(error = R.string.native_quality_unavailable)
            return@action
        }
        val snapshot = graph.projects.textPolicy(draft.ref)
        if (snapshot.revision != draft.revision) {
            setDraft(NativeEditDraft(draft.ref, draft.item, draft.revision, draft.fingerprint, draft.text,
                draft.status, draft.command, conflict = true))
            throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        }
        val result = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            TextProofreadingInspector(snapshot.policy) { context.ensureActive() }
                .inspect(draft.item.source, draft.text, draft.status, options)
        }
        if (mutable.value.editor === draft && mutable.value.project?.ref == draft.ref)
            mutable.value = mutable.value.copy(editorQuality = result)
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
                project = mutable.value.project?.copy(revision = result.revision), notice = R.string.native_saved,
                itemStatistics = null, matchingItems = null)
            refreshCleanTextPolicy(draft.ref)
            // Read current values/counts for the same members, even when an edited item no
            // longer matches the filter. Applying the query or changing page reselects members.
            if (rows.isNotEmpty()) {
                val page = projects.readItemWindow(draft.ref, mutable.value.proofreadingQuery,
                    TextItemWindow(rows.map { it.id }))
                mutable.value = mutable.value.copy(project = page.summary,
                    items = page.items.map { ItemPreview.of(it) }, itemStatistics = page.statistics, matchingItems = page.matchingItems)
            }
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
        if (mutable.value.manualBatch != null) return
        val query = restoreProofreadingQuery(ref)
        val page = readStandardProofreadingPage(ref, query, 0)
        val summary = page.summary
        agentPreviews.clear()
        if (graph.runtime.snapshot().activeRun == null) graph.runtime.selectChat(null)
        else if (backgroundTranslations.state.value?.takeIf { it.phase.active }?.request?.ref != ref)
            throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        cursors.clear(); cursors.add(0)
        val same = restoreRef() == ref
        val selectedPage = if (same) runCatching { NativePage.valueOf(saved["native_page"] ?: "AGENT") }.getOrDefault(NativePage.AGENT) else NativePage.AGENT
        if (!same) clearDraft()
        saved["native_ref"] = encodeRef(ref)
        saveProofreadingQuery(ref, query)
        val batch = decodeManualBatch(saved.get<ArrayList<String>>("manual_batch"))?.takeIf {
            it.ref == ref && graph.runtime.snapshot().activeRun == null }
        if (batch != null) saved["native_page"] = NativePage.PROOFREADING.name
        mutable.value = mutable.value.copy(project = summary, page = if (batch != null) NativePage.PROOFREADING else selectedPage,
            editor = null, editorQuality = null, editorReplacement = null, translationProgress = null,
            policyDraft = null, policyLoadIncomplete = true, policyInvalidRuleId = null,
            selectingItems = false, selectedItems = emptySet(), manualBatch = batch,
            manualBatchConflict = batch != null && saved.get<Boolean>("manual_batch_conflict") == true,
            proofreadingQuery = query, qualityView = null, qualityInvalidated = false, qualityProgress = null, itemStatistics = page.statistics, matchingItems = page.matchingItems,
            items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) }, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false,
            agentMessages = emptyList(), approval = null, agentResults = emptyList(), recentRuns = emptyList(),
            runLoadIncomplete = false, agentMessageDetail = null, agentChatId = null, agentInput = "")
        loadAgent(ref)
        loadTextPolicy(ref, restore = true)
    }

    private suspend fun loadPage(ref: LocalProjectRef, after: Long) {
        val page = readProofreadingPage(ref, mutable.value.proofreadingQuery, after)
        mutable.value = mutable.value.copy(project = page.summary, items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) },
            selectingItems = false, selectedItems = emptySet(),
            itemStatistics = page.statistics, matchingItems = page.matchingItems,
            hasNext = page.items.size > PAGE_SIZE, hasPrevious = cursors.size > 1)
    }

    private suspend fun readProofreadingPage(ref: LocalProjectRef, query: TextItemQuery, after: Long): TextProjectPage {
        val view = mutable.value.qualityView
        if (view != null) {
            if (!mutable.value.qualityCurrent || view.index.ref != ref || view.index.query != query)
                throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            return qualityScanner.page(view, after, PAGE_SIZE + 1)
        }
        return readStandardProofreadingPage(ref, query, after)
    }

    private suspend fun readStandardProofreadingPage(ref: LocalProjectRef, query: TextItemQuery, after: Long): TextProjectPage =
        if (query.active) projects.queryPage(ref, query, after, PAGE_SIZE + 1)
        else projects.readPage(ref, after, PAGE_SIZE + 1)

    private fun restoreProofreadingQuery(ref: LocalProjectRef): TextItemQuery {
        if (saved.get<ArrayList<String>>("proof_query_ref") != encodeRef(ref)) return TextItemQuery()
        return runCatching {
            TextItemQuery(saved.get<String>("proof_query_status")?.let(TextItemStatus::valueOf),
                saved.get<String>("proof_query_text") ?: "",
                saved.get<String>("proof_query_field")?.let(TextSearchField::valueOf) ?: TextSearchField.BOTH)
        }.getOrDefault(TextItemQuery())
    }

    private fun saveProofreadingQuery(ref: LocalProjectRef, query: TextItemQuery) {
        saved["proof_query_ref"] = encodeRef(ref)
        saved["proof_query_text"] = query.text
        saved["proof_query_field"] = query.field.name
        val status = query.status
        if (status == null) saved.remove<String>("proof_query_status") else saved["proof_query_status"] = status.name
    }

    private fun clearProofreadingQuery() {
        for (key in listOf("proof_query_ref", "proof_query_text", "proof_query_field", "proof_query_status")) saved.remove<Any>(key)
    }

    private suspend fun restoreDraft(ref: LocalProjectRef) {
        if (mutable.value.manualBatch != null) return
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
        mutable.value = mutable.value.copy(editor = draft, editorQuality = null, editorReplacement = null)
    }
    private fun clearDraft() {
        mutable.value = mutable.value.copy(editorQuality = null, editorReplacement = null)
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
            catch (error: Exception) { mutable.value = mutable.value.copy(
                qualityInvalidated = mutable.value.qualityInvalidated || mutable.value.qualityView != null &&
                    error is TextProjectException && error.reason == TextProjectException.Reason.STALE_PAGE,
                policyInvalidRuleId = (error as? InvalidTextRule)?.ruleId ?: mutable.value.policyInvalidRuleId, error = when (error) {
                is TextProjectException -> when (error.reason) {
                    TextProjectException.Reason.LIMIT_EXCEEDED -> R.string.native_limit
                    TextProjectException.Reason.INVALID_TEXT -> R.string.native_encoding_error
                    TextProjectException.Reason.UNSUPPORTED_VERSION -> R.string.native_version_error
                    TextProjectException.Reason.INVALID_FORMAT, TextProjectException.Reason.CORRUPT_PROJECT -> R.string.native_format_error
                    TextProjectException.Reason.STALE_PAGE, TextProjectException.Reason.COMMAND_CONFLICT -> R.string.native_conflict
                    else -> R.string.native_io_error
                }
                is InvalidAgentHistory -> R.string.native_agent_history_error
                is ForegroundTranslationStartFailed -> R.string.native_background_unavailable
                is InvalidTextRule -> R.string.native_policy_invalid
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
