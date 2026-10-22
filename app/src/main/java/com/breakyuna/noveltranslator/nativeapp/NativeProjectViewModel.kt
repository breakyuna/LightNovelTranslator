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
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
                  val status: TextItemStatus) {
    companion object {
        fun of(item: TextProjectItem) = ItemPreview(item.id, item.row, item.source.take(240),
            item.translation.take(240), item.status)
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
    fun cancelAction() { job?.cancel() }
    fun cleanupOutputs() = action {
        projects.cleanupTemporaryOutputs()
        mutable.value = mutable.value.copy(notice = R.string.native_cleaned)
    }
    fun refresh() = action {
        val recovery = projects.recoverProjects()
        val registered = projects.registeredProjects()
        mutable.value = mutable.value.copy(projects = registered, recoveryProblems = recovery.problems.size)
        mutable.value.project?.let { loadPage(it.ref, cursors.last()) }
    }

    fun open(ref: LocalProjectRef) = action { openInternal(ref) }
    fun copy(ref: LocalProjectRef) = action {
        val result = projects.copyProject(commandId(), ref)
        mutable.value = mutable.value.copy(projects = projects.registeredProjects())
        openInternal(result.ref)
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
        mutable.value = mutable.value.copy(project = null, items = emptyList(), hasNext = false, hasPrevious = false)
        refresh()
    }

    fun nextPage() = action {
        val state = mutable.value
        val project = state.project ?: return@action
        if (!state.hasNext || state.items.isEmpty()) return@action
        val next = state.items.last().id.value
        loadPage(project.ref, next)
        cursors.add(next)
        mutable.value = mutable.value.copy(hasPrevious = true)
    }

    fun previousPage() = action {
        val project = mutable.value.project ?: return@action
        if (cursors.size < 2) return@action
        val previous = cursors[cursors.lastIndex - 1]
        loadPage(project.ref, previous)
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
                if (item.id == draft.item.id) ItemPreview(item.id, item.row, item.source, draft.text.take(240), draft.status) else item
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
        mutable.value = mutable.value.copy(project = summary, page = selectedPage, editor = null,
            items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) }, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false)
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
                is RuntimeUnavailable -> R.string.native_busy_error
                is SecurityException -> R.string.native_permission_error
                else -> R.string.native_io_error
            }) }
            finally { if (id == actionId) { mutable.value = mutable.value.copy(busy = false); job = null } }
        }
    }

    companion object { private const val PAGE_SIZE = 40 }
}
