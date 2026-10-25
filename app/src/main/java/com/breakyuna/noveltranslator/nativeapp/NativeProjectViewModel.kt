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
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundAgentHandle
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundAgentRequest
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundAgentStartFailed
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationPhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationPaused
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationHandle
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationRunner
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationRequest
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationStartFailed
import com.breakyuna.noveltranslator.nativecore.engine.TranslationFailureAdjudicationResult
import com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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

enum class NativePage { AGENT, WORKBENCH, PROOFREADING, MORE, RULES, SETTINGS, FILES, LOGS, MODELS }

/** RULES, SETTINGS, FILES and LOGS remain routed pages; the bottom bar stays at four items. */
internal val NativeNavigationPages = listOf(NativePage.AGENT, NativePage.WORKBENCH, NativePage.PROOFREADING, NativePage.MORE)
enum class NativeExport { PROJECT, TRANSLATION, BILINGUAL, ORIGINAL }

class ItemPreview(val id: ItemId, val row: Int, val source: String, val translation: String,
                  val status: TextItemStatus, val hasTranslatableSource: Boolean = source.isNotBlank(), val assetId: Long = 1) {
    companion object {
        fun of(item: TextProjectItem) = ItemPreview(item.id, item.row, item.source.take(240),
            item.translation.take(240), item.status, item.source.isNotBlank(), item.assetId)
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

/**
 * Name editing is an explicit, separate command so it can preserve the body/status draft and
 * still use the immutable name command payload. The command burns one project revision.
 */
class NativeNameEditDraft(
    val ref: LocalProjectRef, val itemId: ItemId, val revision: Long,
    val fingerprint: ObjectFingerprint, val sourceText: String, val translationText: String,
) {
    override fun toString(): String = "NativeNameEditDraft(redacted)"
}

data class NativeProjectUiState(
    val projects: List<RegisteredProject> = emptyList(),
    val project: TextProjectSummary? = null,
    val page: NativePage = NativePage.AGENT,
    val rulesSection: NativeRuleSection = NativeRuleSection.GLOSSARY,
    val items: List<ItemPreview> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val proofreadingQuery: TextItemQuery = TextItemQuery(),
    val itemStatistics: TextItemStatistics? = null,
    val matchingItems: Int? = null,
    val matchingStatistics: TextItemStatistics? = null,
    val qualityView: TextQualitySelection? = null,
    val qualityProgress: TextQualityScanProgress? = null,
    val qualityInvalidated: Boolean = false,
    val selectingItems: Boolean = false,
    val selectedItems: Set<ItemId> = emptySet(),
    val manualBatch: ManualTextBatch? = null,
    val manualBatchConflict: Boolean = false,
    val manualBatchQuery: Boolean = false,
    val editor: NativeEditDraft? = null,
    val editorCanUndo: Boolean = false, val editorCanRedo: Boolean = false,
    val editorQuality: TextProofreadingResult? = null,
    val editorReplacement: NativeEditorReplacement? = null,
    val nameEdit: NativeNameEditDraft? = null,
    val busy: Boolean = false,
    val foregroundTranslationActive: Boolean = false,
    val foregroundTranslationCanPause: Boolean = false,
    val foregroundTranslationPausing: Boolean = false,
    val foregroundAgentActive: Boolean = false,
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
    val agentRunId: RunId? = null,
    val agentChats: List<ChatId> = emptyList(),
    val modelSettings: NativeModelSettings? = null,
    val translationModelSettings: NativeModelSettings? = null,
    val modelProfiles: List<NativeModelProfile> = emptyList(),
    val modelProfileKeys: List<ModelProfileKeyState> = emptyList(),
    val modelProfilesLoading: Boolean = false,
    val modelProfilesError: Int? = null,
    val agentInput: String = "",
    val agentQueue: AgentInputQueue? = null,
    val agentQueueRevision: Long = 0,
    val agentQueueIncomplete: Boolean = false,
    val agentLoadIncomplete: Boolean = false,
    val recentRuns: List<StoredRun> = emptyList(),
    val queueManagedRuns: Set<String> = emptySet(),
    val runLoadIncomplete: Boolean = false,
    val modelCatalogLoading: Boolean = false,
    val agentMessageDetail: AgentMessageDetail? = null,
    val modelSettingsError: Int? = null,
    val policyDraft: NativePolicyDraft? = null,
    val policyLoadIncomplete: Boolean = false,
    val policyInvalidRuleId: String? = null,
    val storageBytes: Long? = null,
    val storageUsage: NativeStorageUsage? = null,
    val diagnosticsIncludeDetails: Boolean = false,
    val themeMode: NativeThemeMode = NativeThemeMode.SYSTEM,
    val automaticContextSummary: Boolean = false,
    val appPersona: String = "",
    val appPersonaSaveFailed: Boolean = false,
    val appLanguage: NativeAppLanguage = NativeAppLanguage.ENGLISH,
    val settingsSection: NativeSettingsSection = NativeSettingsSection.BASIC,
    val networkSettingsState: NativeNetworkSettingsState = NativeNetworkSettingsState(),
    val requestLimitsState: NativeRequestLimitsState = NativeRequestLimitsState(),
    val laboratorySettings: LaboratorySettings = LaboratorySettings.DEFAULT,
    val laboratoryFingerprint: String? = null,
    val laboratoryLoaded: Boolean = false,
    val laboratorySaving: Boolean = false,
    val laboratoryError: Boolean = false,
    val agentContextStatus: com.breakyuna.noveltranslator.nativecore.engine.AgentContextStatus? = null,
    val itemProposals: List<ItemProposalSummary> = emptyList(),
    val policyProposals: List<PolicyApprovalSummary> = emptyList(),
    val pdfPageProposals: List<PdfPageProposalSummary> = emptyList(),
    val assetImportProposals: List<AssetImportProposalSummary> = emptyList(),
    val supportedFormats: Set<ProjectAssetFormat> = emptySet(),
) {
    val foregroundExecutionActive: Boolean get() = foregroundTranslationActive || foregroundAgentActive
    val pendingItemProposal: ItemProposalSummary? get() = itemProposals.firstOrNull {
        it.state == ItemProposalState.PENDING || it.state == ItemProposalState.CONSUMED
    }
    val pendingPolicyProposal: PolicyApprovalSummary? get() = policyProposals.firstOrNull {
        it.state == TextApprovalState.PENDING || it.state == TextApprovalState.CONSUMED
    }
    val pendingPdfPageProposal: PdfPageProposalSummary? get() = pdfPageProposals.firstOrNull {
        it.state == PdfPageProposalState.PENDING || it.state == PdfPageProposalState.CONSUMED
    }
    val pendingAssetImportProposal: AssetImportProposalSummary? get() = assetImportProposals.firstOrNull {
        it.state == AssetImportProposalState.PENDING
    }
    val hasAgentApproval: Boolean get() = approval != null || pendingItemProposal != null ||
        pendingPolicyProposal != null || pendingPdfPageProposal != null || pendingAssetImportProposal != null
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
    settingsPort: NativeSettingsPort? = null,
    pdfPageThumbnailSource: PdfPageThumbnailSource? = null,
) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved,
        (application as TranslatorApplication).nativeGraph.projects, application.nativeGraph.backgroundTranslations,
        NativeStoredSettingsPort(application.nativeGraph.networkSettings, application.nativeGraph.requestLimits))

    private val draftHistory = NativeDraftHistory()
    private val graph = (application as TranslatorApplication).nativeGraph
    private val pdfPageThumbnails = NativePdfPageThumbnailLoader(
        pdfPageThumbnailSource ?: PdfPageThumbnailSource { ref, revision, asset, page ->
            graph.formatProjects.renderPdfPage(ref, revision, asset, page)
        },
    )
    private val appSettings = NativeAppSettingsStore(application)
    private val settingsStorage: NativeSettingsPort = settingsPort ?: NativeStoredSettingsPort(
        graph.networkSettings, graph.requestLimits)
    private val nameProjects = projects as? ProjectNamePort
    val nameEditingAvailable: Boolean get() = nameProjects != null
    internal val rulePresetActions = NativeRulePresetActions(graph.rulePresets::list, graph.rulePresets::load,
        { preset, expected -> graph.rulePresets.save(preset, expected) }, graph.rulePresets::remove)
    internal val promptTemplateActions = NativePromptTemplateActions(graph.promptTemplates::read, graph.promptTemplates::replace)
    private suspend fun <T> writeSkill(block: suspend () -> T): T {
        val lease = graph.runtime.beginSkillWrite()
        try { return withContext(Dispatchers.IO) { block() } }
        finally { graph.runtime.endSkillWrite(lease) }
    }

    internal val skillActions = NativeSkillActions(
        list = { withContext(Dispatchers.IO) { graph.workspaceSkills.list() } },
        read = { id -> withContext(Dispatchers.IO) { graph.workspaceSkills.readContent(id) } },
        create = { draft -> writeSkill { graph.workspaceSkills.create(draft) } },
        update = { id, draft, expected -> writeSkill { graph.workspaceSkills.update(id, draft, expected) } },
        delete = { id, expected -> writeSkill { graph.workspaceSkills.delete(id, expected) } },
        setEnabled = { id, enabled, expected -> writeSkill { graph.workspaceSkills.setEnabled(id, enabled, expected) } },
        setOrder = { id, order, expected -> writeSkill { graph.workspaceSkills.setOrder(id, order, expected) } },
        writeResource = { id, path, text, expected, sha ->
            val lease = graph.runtime.beginSkillWrite()
            try { withContext(Dispatchers.IO) { graph.workspaceSkills.writeResource(id, path, text, expected, sha) } }
            finally { graph.runtime.endSkillWrite(lease) }
        },
        deleteResource = { id, path, expected, sha ->
            val lease = graph.runtime.beginSkillWrite()
            try { withContext(Dispatchers.IO) { graph.workspaceSkills.deleteResource(id, path, expected, sha) } }
            finally { graph.runtime.endSkillWrite(lease) }
        },
        renameResource = { id, path, destination, expected, sha ->
            val lease = graph.runtime.beginSkillWrite()
            try { withContext(Dispatchers.IO) { graph.workspaceSkills.renameResource(id, path, destination, expected, sha) } }
            finally { graph.runtime.endSkillWrite(lease) }
        },
        prepareExecution = { skill, file ->
            val current = mutable.value
            val project = current.project
            val chat = current.agentChatId
            if (project == null || chat == null || current.busy || current.agentLoadIncomplete ||
                current.runLoadIncomplete || current.editor != null || current.manualBatch != null || !skill.summary.enabled)
                null
            else NativeSkillExecutionTarget(project.ref, project.revision,
                RunIdentity(project.ref.projectId, chat, RunId(UUID.randomUUID().toString()), commandId()), skill, file)
        },
        execute = { target, input, allowFetch -> executeSkill(target, input, allowFetch) },
        stopExecution = { identity ->
            agentLease?.takeIf { it.identity == identity }?.let { lease ->
                graph.runtime.requestStop(lease)
                job?.cancel()
            }
        },
        files = { id -> withContext(Dispatchers.IO) { graph.workspaceSkills.files(id) } },
        readFileText = { id, path -> withContext(Dispatchers.IO) {
            val bytes = graph.workspaceSkills.readFile(id, path, 64 * 1024) ?: return@withContext null
            try {
                Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { null }
        } },
    )
    private val resolver = application.contentResolver
    private val manualBatchFiles = NativeManualBatchFiles(application)
    private val mutable = MutableStateFlow(NativeProjectUiState(recoveryProblems = saved["native_recovery_problems"] ?: 0, encoding =
        runCatching { TxtEncoding.valueOf(saved["native_encoding"] ?: "UTF8") }.getOrDefault(TxtEncoding.UTF8),
        supportedFormats = graph.formatAdapters,
        appLanguage = appSettings.language(),
        appPersona = appSettings.appPersona(),
        diagnosticsIncludeDetails = appSettings.diagnosticsIncludeDetails(),
        settingsSection = runCatching {
            NativeSettingsSection.valueOf(saved["native_settings_section"] ?: NativeSettingsSection.BASIC.name)
        }.getOrDefault(NativeSettingsSection.BASIC)))
    val state = mutable.asStateFlow()
    internal val workspaceActions = NativeWorkspaceActions(
        inventory = { ref, run ->
            require(mutable.value.project?.ref == ref && mutable.value.agentRunId == run)
            graph.workspace.artifactInventory(ref, run)
        },
        importArtifact = { ref, revision, artifact, path, command ->
            queuedActionResult {
                val current = mutable.value
                val selectedProject = requireNotNull(current.project)
                require(selectedProject.ref == ref && selectedProject.revision == revision &&
                    current.agentRunId == artifact.runId && current.editor == null && current.manualBatch == null)
                val target = RelativePath(path)
                val bytes = graph.workspace.readVerifiedArtifactBytes(ref, artifact.runId, artifact.id,
                    artifact.sha256, artifact.sizeBytes)
                val receipt = graph.formatProjects.importAsset(command, ref, revision, target, bytes)
                if (receipt.status != AssetMutationStatus.COMMITTED)
                    throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
                // The commit receipt is authoritative even if refreshing the projection fails.
                mutable.value = mutable.value.copy(qualityView = null, qualityInvalidated = true)
                try { loadPage(ref, cursors.last()); loadAgent(ref) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutable.value = mutable.value.copy(error = R.string.native_workspace_import_refresh)
                }
            }.await()
        },
        materials = { ref, revision -> withContext(Dispatchers.IO) {
            val current = mutable.value.project
            require(current != null && current.ref == ref && current.revision == revision)
            graph.workspace.listMaterials(ref, revision)
        } },
        readMaterial = { ref, revision, id -> withContext(Dispatchers.IO) {
            val current = mutable.value.project
            require(current != null && current.ref == ref && current.revision == revision)
            graph.workspace.readMaterialText(ref, revision, id).text
        } },
        artifacts = { ref, runId -> withContext(Dispatchers.IO) {
            require(mutable.value.project?.ref == ref)
            if (runId == null) emptyList() else graph.workspace.listArtifacts(ref, runId)
        } },
        readArtifact = { ref, runId, id -> withContext(Dispatchers.IO) {
            require(mutable.value.project?.ref == ref)
            graph.workspace.readArtifactText(ref, runId, id).text
        } },
    )
    private val agentPreviews = graph.agentPreviews
    val agentStream = agentPreviews.state
    private var job: Job? = null
    private var agentLease: RunLease? = null
    private var agentChat = ChatId("native-agent-main")
    private var agentQueueStored: StoredAgentInputQueue? = null
    private val cursors = mutableListOf(0L)
    private var actionId = 0L
    private var observedSimulation: ForegroundTranslationHandle? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val mode = appSettings.themeMode()
            val automatic = appSettings.automaticContextSummary()
            val language = appSettings.language()
            val persona = appSettings.appPersona()
            val network = readNetworkSettings()
            val limits = readRequestLimits()
            val laboratory = readLaboratorySettings()
            mutable.update { it.copy(themeMode = mode, automaticContextSummary = automatic,
                appLanguage = language, appPersona = persona, networkSettingsState = network,
                requestLimitsState = limits, laboratorySettings = laboratory.settings,
                laboratoryFingerprint = laboratory.fingerprint, laboratoryLoaded = laboratory.loaded,
                laboratoryError = laboratory.failed) }
        }
        action {
        // Attach the application-owned Agent before any fallible project IO or recovery.
        val agentSnapshot = graph.backgroundAgents.state.value
        val agentAttachment = agentSnapshot?.takeIf { it.phase.active ||
            saved.get<String>("native_agent_token") == it.token }?.let { graph.backgroundAgents.attach(it.token) }
        if (agentAttachment != null) {
            mutable.value = mutable.value.copy(foregroundAgentActive = agentSnapshot?.phase?.active == true)
            if (agentSnapshot?.phase?.active == true) saved["native_recovery_pending"] = true
            saved["native_agent_token"] = agentAttachment.token
            refreshSimulationView(running = true) {
                initializeProjects(application, recover = agentSnapshot?.phase?.active != true)
                openInternal(agentAttachment.request.ref)
            }
            val pending = mutable.value.agentQueue?.pendingAppend?.takeIf {
                it.run == agentAttachment.request.identity &&
                    it.draft.text == agentAttachment.request.input &&
                    mutable.value.project?.ref == agentAttachment.request.ref
            }
            if (pending != null) awaitQueuedAgent(agentAttachment, pending)
            else awaitAgent(agentAttachment)
            return@action
        }
        saved.remove<String>("native_agent_token")
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
    init {
        // Resume only durable QUEUED work after the initial recovery/load action settles. The
        // application scheduler enforces global/project slots and never changes the visible page.
        viewModelScope.launch {
            state.first { !it.busy }
            if (!mutable.value.runLoadIncomplete && mutable.value.projects.isNotEmpty())
                graph.translationQueueScheduler.kick()
        }
        viewModelScope.launch {
            backgroundTranslations.state.collect { snapshot ->
                if (snapshot == null) return@collect
                val current = mutable.value
                if (current.project?.ref != snapshot.request.ref) return@collect
                mutable.value = current.copy(
                    translationProgress = snapshot.progress ?: current.translationProgress,
                    foregroundTranslationActive = snapshot.phase.active,
                    foregroundTranslationCanPause = snapshot.phase == ForegroundTranslationPhase.RUNNING &&
                        snapshot.request.options != null,
                    foregroundTranslationPausing = snapshot.phase == ForegroundTranslationPhase.PAUSING,
                )
            }
        }
        viewModelScope.launch {
            graph.translationQueueScheduler.events.collect { event ->
                val current = mutable.value
                if (current.project?.ref != event.ref || current.busy) return@collect
                if (current.editor != null || current.manualBatch != null || current.selectingItems ||
                    current.policyDraft?.dirty == true || current.proofreadingQuery.active ||
                    current.qualityView != null) return@collect
                val state = event.state
                val settled = state == null || state.terminal ||
                    state == TranslationTaskQueueState.PAUSED ||
                    state == TranslationTaskQueueState.NEEDS_REVIEW ||
                    state == TranslationTaskQueueState.FAILED
                if (!settled) return@collect
                mutable.value = mutable.value.copy(foregroundTranslationActive = false,
                    foregroundTranslationCanPause = false, foregroundTranslationPausing = false)
                refreshSimulationView(running = false) {
                    loadPage(event.ref, 0)
                    loadRuns(event.ref)
                }
            }
        }
    }

    private suspend fun initializeProjects(application: Application, recover: Boolean) {
        // Directory recovery takes a write reservation. A live foreground task already owns it.
        if (recover) recoverDirectory()
        try { graph.runStore.recoverInterrupted() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(runLoadIncomplete = true) }
        // Reconcile abandoned queue RUNNING facts before any page/queue projection is shown.
        // Recovery only pauses or routes to review; it never resumes a paid task automatically.
        try {
            graph.translationQueueScheduler.recover()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep the queue projection available; the next kick retries recovery.
        }
        val registered = projects.registeredProjects()
        val legacy = withContext(Dispatchers.IO) { application.getDatabasePath("light_novel_translation_platform.db").exists() }
        mutable.value = mutable.value.copy(projects = registered, legacyData = legacy)
        runCatching { graph.diagnostics.append(
            com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticLevel.INFO,
            "projects", "Loaded ${registered.size} project record(s)") }
    }

    private suspend fun recoverDirectory() {
        val recovery = projects.recoverProjects()
        saved["native_recovery_problems"] = recovery.problems.size
        saved["native_recovery_pending"] = false
        mutable.value = mutable.value.copy(recoveryProblems = recovery.problems.size)
    }

    private suspend fun requireLegacyTask(ref: LocalProjectRef, run: RunIdentity) {
        if (graph.translationQueue.snapshot(ref).queue.tasks.any { it.identity.run == run })
            throw TranslationTaskNeedsReview()
    }

    internal suspend fun reviewTranslationTask(ref: LocalProjectRef, run: RunIdentity): TranslationTaskReview {
        requireLegacyTask(ref, run)
        val current = mutable.value
        if (current.busy || current.project?.ref != ref) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        return graph.translationTasks.review(ref, run)
    }
    internal suspend fun terminateTranslationTask(review: TranslationTaskReview, command: CommandId) {
        val current = mutable.value
        if (current.busy || current.project?.ref != review.ref) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        requireLegacyTask(review.ref, review.run)
        graph.translationTasks.terminate(review, command)
    }

    internal suspend fun translationTaskMembers(ref: LocalProjectRef, run: RunIdentity, after: Long): TranslationTaskMemberPage {
        if (mutable.value.busy || mutable.value.project?.ref != ref) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        return graph.translationTasks.members(ref, run, after)
    }
    internal suspend fun keepTaskErrors(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId) {
        if (mutable.value.busy || mutable.value.project?.ref != review.ref) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        requireLegacyTask(review.ref, review.run)
        graph.translationTasks.keepErrors(review, revision, members, command)
    }

    internal suspend fun prepareTaskRetry(review: TranslationTaskReview, revision: Long,
        members: List<TranslationTaskMemberReview>, command: CommandId): NativeTranslationResumePreview {
        val current = mutable.value
        if (current.busy || current.project?.ref != review.ref || current.editor != null || current.manualBatch != null ||
            current.policyDraft?.dirty == true) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        requireLegacyTask(review.ref, review.run)
        val spec = graph.translationTasks.retryErrors(review, revision, members, command)
        return NativeTranslationResumePreview(spec, graph.modelSettings.resolve(spec.configuration).model)
    }

    internal suspend fun translationTaskPage(ref: LocalProjectRef, before: Long?): TranslationTaskPage = graph.translationTasks.page(ref, before)
    internal suspend fun translationTaskSummaries(ref: LocalProjectRef): List<TranslationTaskSummary> = graph.translationTasks.recent(ref)
    internal suspend fun translationQueueSnapshot(ref: LocalProjectRef): TranslationQueueSnapshot =
        graph.translationQueue.snapshot(ref)
    internal suspend fun translationQueueTask(identity: TranslationTaskQueueIdentity): TranslationQueuedTask {
        if (mutable.value.project?.ref != identity.ref)
            throw RuntimeUnavailable(RuntimeUnavailable.Reason.WRONG_PROJECT)
        return graph.translationQueue.snapshot(identity.ref).task(identity)
            ?: throw TranslationQueueRefused(TranslationQueueRejection.UNKNOWN_TASK)
    }

    /** Bounded fixed-task failure facts; the dialog never receives source or translation text. */
    internal suspend fun translationFailureEvidence(
        identity: TranslationTaskQueueIdentity,
        after: Long = 0,
    ): TranslationTaskFailureEvidence {
        if (mutable.value.project?.ref != identity.ref)
            throw RuntimeUnavailable(RuntimeUnavailable.Reason.WRONG_PROJECT)
        if (graph.translationQueue.snapshot(identity.ref).task(identity) == null)
            throw TranslationQueueRefused(TranslationQueueRejection.UNKNOWN_TASK)
        val evidence = graph.translationQueueScheduler.failureEvidence(identity, after)
        if (evidence.review.ref != identity.ref || evidence.review.run != identity.run)
            throw TranslationQueueRefused(TranslationQueueRejection.IDENTITY_MISMATCH)
        return evidence
    }

    /**
     * Member actions are queued through the ViewModel busy gate and generate one command identity
     * per batch. They call the application scheduler, which records the decision on the SAME task;
     * no Run, task or paid request is created here. The returned deferred completes after the
     * durable receipt/refusal, not on an optimistic UI assumption.
     */
    internal fun retryTranslationFailureMembers(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): Deferred<TranslationFailureAdjudicationResult> = queuedFailureAction(identity, requireDecision = true) { task ->
        val rejection = failureSelectionRejection(task, batchId, memberIds)
        if (rejection != null) return@queuedFailureAction failureActionRejection(identity, rejection)
        val result = graph.translationQueueScheduler.retryFailureMembers(
            identity, batchId, memberIds, commandId())
        refreshAfterFailureDecision(identity.ref)
        result
    }

    internal fun skipTranslationFailureMembers(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): Deferred<TranslationFailureAdjudicationResult> = queuedFailureAction(identity, requireDecision = true) { task ->
        val rejection = failureSelectionRejection(task, batchId, memberIds)
        if (rejection != null) return@queuedFailureAction failureActionRejection(identity, rejection)
        val result = graph.translationQueueScheduler.skipFailureMembers(
            identity, batchId, memberIds, commandId())
        refreshAfterFailureDecision(identity.ref)
        result
    }

    internal fun keepTranslationFailureMembers(
        identity: TranslationTaskQueueIdentity,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): Deferred<TranslationFailureAdjudicationResult> = queuedFailureAction(identity, requireDecision = true) { task ->
        val rejection = failureSelectionRejection(task, batchId, memberIds)
        if (rejection != null) return@queuedFailureAction failureActionRejection(identity, rejection)
        val result = graph.translationQueueScheduler.keepFailureMembers(
            identity, batchId, memberIds, commandId())
        refreshAfterFailureDecision(identity.ref)
        result
    }

    internal fun terminateTranslationFailureTask(
        identity: TranslationTaskQueueIdentity,
    ): Deferred<TranslationFailureAdjudicationResult> = queuedFailureAction(identity, requireDecision = false) {
        val result = graph.translationQueueScheduler.terminateFailureTask(identity, commandId())
        refreshAfterFailureDecision(identity.ref)
        result
    }

    private fun queuedFailureAction(
        identity: TranslationTaskQueueIdentity,
        requireDecision: Boolean,
        block: suspend (TranslationQueuedTask) -> TranslationFailureAdjudicationResult,
    ): Deferred<TranslationFailureAdjudicationResult> = queuedActionResult {
        if (mutable.value.project?.ref != identity.ref)
            throw RuntimeUnavailable(RuntimeUnavailable.Reason.WRONG_PROJECT)
        val snapshot = graph.translationQueue.snapshot(identity.ref)
        val task = snapshot.task(identity)
            ?: return@queuedActionResult TranslationFailureAdjudicationResult(
                TranslationQueueResult.Rejected(snapshot.queue, TranslationQueueRejection.UNKNOWN_TASK))
        if (task.state.terminal) return@queuedActionResult TranslationFailureAdjudicationResult(
            TranslationQueueResult.Rejected(snapshot.queue, TranslationQueueRejection.TERMINAL_STATE))
        if (requireDecision && !task.state.awaitingDecision)
            return@queuedActionResult TranslationFailureAdjudicationResult(
                TranslationQueueResult.Rejected(snapshot.queue, TranslationQueueRejection.INVALID_TRANSITION))
        block(task)
    }

    private fun failureSelectionRejection(
        task: TranslationQueuedTask,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): TranslationQueueRejection? {
        if (memberIds.isEmpty() || memberIds.size > TranslationTaskFailureBatch.MAX_MEMBERS ||
            memberIds.distinct().size != memberIds.size
        ) return TranslationQueueRejection.INVALID_EVIDENCE
        val batch = task.batch(batchId) ?: return TranslationQueueRejection.UNKNOWN_BATCH
        if (memberIds.any { batch.member(it) == null })
            return TranslationQueueRejection.MEMBER_NOT_IN_BATCH
        return null
    }

    private suspend fun failureActionRejection(
        identity: TranslationTaskQueueIdentity,
        reason: TranslationQueueRejection,
    ): TranslationFailureAdjudicationResult = TranslationFailureAdjudicationResult(
        TranslationQueueResult.Rejected(graph.translationQueue.snapshot(identity.ref).queue, reason))

    private suspend fun refreshAfterFailureDecision(ref: LocalProjectRef) {
        refreshSimulationView(running = false) {
            loadPage(ref, cursors.lastOrNull() ?: 0L)
            loadRuns(ref)
        }
    }

    internal suspend fun inspectTranslationResume(ref: LocalProjectRef, run: RunIdentity): NativeTranslationResumePreview {
        val current = mutable.value
        if (current.busy || current.project?.ref != ref || current.editor != null || current.manualBatch != null || current.policyDraft?.dirty == true)
            throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        requireLegacyTask(ref, run)
        val spec = graph.translationTasks.resume(ref, run)
        val model = graph.modelSettings.resolve(spec.configuration)
        return NativeTranslationResumePreview(spec, model.model)
    }
    internal fun resumeTranslationTask(spec: TranslationTaskSpec) = action {
        if (mutable.value.project?.ref != spec.ref || mutable.value.editor != null || mutable.value.manualBatch != null ||
            mutable.value.policyDraft?.dirty == true) return@action
        requireLegacyTask(spec.ref, spec.run)
        val confirmed = graph.translationTasks.resume(spec.ref, spec.run)
        if (confirmed != spec) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        graph.modelSettings.resolve(spec.configuration)
        val handle = backgroundTranslations.prepare(ForegroundTranslationRequest(spec.ref, spec.run, spec.configuration,
            spec.revision, spec.afterId, spec.pageSize, if (spec.wholeProject)
                com.breakyuna.noveltranslator.nativecore.engine.TranslationScope.PROJECT else com.breakyuna.noveltranslator.nativecore.engine.TranslationScope.PAGE,
            spec.options, continuation = true))
        saved["native_simulation_token"] = handle.token
        saved["native_recovery_pending"] = true
        mutable.value = mutable.value.copy(foregroundTranslationActive = true)
        try { NativeTranslationService.start(getApplication<Application>(), handle.token) }
        catch (_: Exception) { backgroundTranslations.startFailed(handle.token) }
        refreshSimulationView(running = true) { restoreSimulationPage(handle) }
        awaitSimulation(handle)
    }

    fun prepareUsageExport(query: UsageLedgerQuery): String? {
        val current = mutable.value
        if (current.busy) {
            mutable.value = current.copy(error = R.string.native_busy_error, notice = null)
            return null
        }
        if (query.run != null || query.configuration != null ||
            query.ref != null && query.ref != current.project?.ref ||
            query.chat != null && query.chat != current.agentChatId) {
            mutable.value = current.copy(error = R.string.native_conflict, notice = null)
            return null
        }
        saved["native_usage_export"] = arrayListOf(query.ref?.projectId?.value.orEmpty(),
            query.ref?.instanceId?.value.orEmpty(), query.ref?.generation?.value?.toString().orEmpty(), query.chat?.value.orEmpty())
        return "lnt-usage-ledger.jsonl"
    }
    fun completeUsageExport(uri: Uri?) {
        val pending = saved.remove<ArrayList<String>>("native_usage_export")?.toList() ?: return
        if (uri == null || pending.size != 4) return
        queuedAction {
            val ref = if (pending[0].isEmpty()) {
                require(pending.take(3).all { it.isEmpty() }); null
            } else decodeRef(pending)
            if (ref != null && mutable.value.project?.ref != ref)
                throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            val query = UsageLedgerQuery(ref, pending[3].takeIf { it.isNotEmpty() }?.let(::ChatId))
            graph.usageLedger.export(query) { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException() }
            mutable.value = mutable.value.copy(notice = R.string.native_exported)
        }
    }

    internal suspend fun loadPrices(): PriceTable = graph.priceTables.load()
    internal suspend fun savePrice(expected: String, currency: CurrencyCode, pricing: ModelPricing): PriceTable =
        graph.priceTables.save(expected, currency, pricing)

    internal suspend fun usageEntries(query: UsageLedgerQuery, cursor: UsageLedgerCursor?): UsageLedgerPage = graph.usageLedger.entries(query, cursor)
    internal suspend fun usageSummary(query: UsageLedgerQuery): DurableUsageSummary = graph.usageLedger.summary(query)

    fun changeEncoding(value: TxtEncoding) { if (!mutable.value.busy) {
        saved["native_encoding"] = value.name
        mutable.value = mutable.value.copy(encoding = value)
    } }
    fun dismissMessage() { mutable.value = mutable.value.copy(error = null, notice = null) }
    fun pauseTranslation() {
        val running = backgroundTranslations.state.value ?: return
        if (running.token == observedSimulation?.token || running.request.ref == mutable.value.project?.ref)
            backgroundTranslations.pause(running.token)
    }
    fun cancelAction() {
        val running = backgroundTranslations.state.value
        if (running?.phase?.active == true && (running.token == observedSimulation?.token ||
            running.request.ref == mutable.value.project?.ref)) {
            backgroundTranslations.stop(running.token)
            return // Keep waiting until durable cleanup releases the actual reservation.
        }
        val agent = graph.backgroundAgents.state.value
        if (agent?.phase?.active == true && (saved.get<String>("native_agent_token") == agent.token ||
            agent.request.ref == mutable.value.project?.ref)) {
            graph.backgroundAgents.stop(agent.token)
            return // Wait for application-owned request and journal cleanup.
        }
        graph.translations.stop(); agentLease?.let { graph.runtime.requestStop(it) }; job?.cancel()
    }
    fun cleanupOutputs() = action {
        projects.cleanupTemporaryOutputs()
        manualBatchFiles.cleanupExcept(saved.get<String>("manual_batch_disk"))
        val usage = privateStorageUsage()
        mutable.value = mutable.value.copy(notice = R.string.native_cleaned,
            storageBytes = usage.totalPrivateBytes, storageUsage = usage)
    }

    internal suspend fun diagnosticPage(
        cursor: com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticCursor?,
        level: com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticLevel? = null,
        category: String? = null,
    ) = graph.diagnostics.query(50, cursor, level, category)

    internal suspend fun diagnosticCategories(): List<String> = graph.diagnostics.categories()

    internal suspend fun diagnosticDetail(id: String) = graph.diagnostics.read(id)

    /**
     * Debug detail storage is an explicit, persisted opt-in that only affects new records.
     * Persist and apply the same value so a process restart keeps the chosen boundary.
     */
    fun setDiagnosticsIncludeDetails(enabled: Boolean) = action {
        if (enabled == mutable.value.diagnosticsIncludeDetails) return@action
        withContext(Dispatchers.IO) { appSettings.saveDiagnosticsIncludeDetails(enabled) }
        graph.diagnostics.setIncludeDetails(enabled)
        mutable.update { it.copy(diagnosticsIncludeDetails = enabled) }
    }

    fun openLogs() {
        if (pageTransitionBlocked()) return
        saved["native_logs_origin"] = (if (mutable.value.page == NativePage.WORKBENCH) NativePage.WORKBENCH
            else NativePage.MORE).name
        mutable.value = mutable.value.copy(page = NativePage.LOGS)
        saved["native_page"] = NativePage.LOGS.name
    }

    fun closeLogs() {
        val origin = runCatching { NativePage.valueOf(saved["native_logs_origin"] ?: NativePage.MORE.name) }
            .getOrDefault(NativePage.MORE)
        saved.remove<String>("native_logs_origin")
        if (mutable.value.project == null) home() else selectPage(
            if (origin == NativePage.WORKBENCH) NativePage.WORKBENCH else NativePage.MORE)
    }

    /** Files stay project-scoped; the recorded origin restores the Workbench or More page. */
    fun openFiles() {
        if (mutable.value.project == null || pageTransitionBlocked()) return
        saved["native_files_origin"] = (if (mutable.value.page == NativePage.WORKBENCH) NativePage.WORKBENCH
            else NativePage.MORE).name
        mutable.value = mutable.value.copy(page = NativePage.FILES)
        saved["native_page"] = NativePage.FILES.name
    }

    fun closeFiles() {
        val origin = runCatching { NativePage.valueOf(saved["native_files_origin"] ?: NativePage.MORE.name) }
            .getOrDefault(NativePage.MORE)
        saved.remove<String>("native_files_origin")
        if (mutable.value.project == null) home() else selectPage(
            if (origin == NativePage.WORKBENCH) NativePage.WORKBENCH else NativePage.MORE)
    }

    private fun pageTransitionBlocked(): Boolean =
        (mutable.value.busy && !mutable.value.foregroundExecutionActive) ||
            mutable.value.editor != null || mutable.value.manualBatch != null

    fun completeDiagnosticsExport(uri: Uri?) = action {
        if (uri == null) return@action
        val stream = resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Diagnostics destination unavailable")
        stream.use { graph.diagnostics.export(it) }
        mutable.value = mutable.value.copy(notice = R.string.native_logs_exported)
    }

    fun clearDiagnostics() = action {
        graph.diagnostics.clear()
        mutable.value = mutable.value.copy(notice = R.string.native_logs_cleared)
    }

    fun completeApplicationBackupExport(uri: Uri?) = action {
        if (uri == null) return@action
        graph.applicationBackups.export {
            resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Backup destination unavailable")
        }
        mutable.value = mutable.value.copy(notice = R.string.native_app_backup_exported)
    }

    fun importApplicationBackup(uri: Uri?) = action {
        if (uri == null) return@action
        graph.applicationBackups.restore {
            resolver.openInputStream(uri) ?: throw java.io.IOException("Backup source unavailable")
        }
        mutable.value = mutable.value.copy(notice = R.string.native_app_backup_restored)
    }
    /** Private app/project footprint. Manual and lazy; never walks the tree on the main thread. */
    suspend fun fileInventory(ref: LocalProjectRef, revision: Long): ProjectFileInventory {
        require(mutable.value.project?.let { it.ref == ref && it.revision == revision } == true)
        val result = graph.projects.fileInventory(ref, revision)
        require(mutable.value.project?.let { it.ref == ref && it.revision == revision } == true)
        return result
    }

    fun refreshStorageUsage() = action {
        val usage = privateStorageUsage()
        mutable.value = mutable.value.copy(storageBytes = usage.totalPrivateBytes, storageUsage = usage)
    }

    private suspend fun privateStorageUsage(): NativeStorageUsage = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val filesRoot = app.filesDir
        val workspaceRoot = java.io.File(filesRoot, "workspace")
        val projectRoot = java.io.File(filesRoot, "native_projects")
        fun sizeOf(root: java.io.File): Long = if (!root.exists()) 0L else root.walkTopDown()
            .filter { it.isFile }.sumOf { it.length().coerceAtLeast(0L) }
        val workspaceFiles: List<java.io.File> = if (!workspaceRoot.exists()) emptyList() else workspaceRoot.walkTopDown()
            .filter { it.isFile }.toList()
        val artifactBytes = workspaceFiles.filter { file ->
            file.relativeTo(workspaceRoot).invariantSeparatorsPath.split('/').any { it == "artifacts" }
        }.sumOf { it.length().coerceAtLeast(0L) }
        val workspaceBytes = workspaceFiles.sumOf { it.length().coerceAtLeast(0L) }
        val projectBytes = sizeOf(projectRoot)
        val filesBytes = sizeOf(filesRoot)
        val cacheBytes = sizeOf(app.cacheDir)
        val otherFiles = (filesBytes - projectBytes - workspaceBytes).coerceAtLeast(0L)
        NativeStorageUsage(
            projectBytes = projectBytes,
            workspaceMaterialBytes = (workspaceBytes - artifactBytes).coerceAtLeast(0L),
            workspaceArtifactBytes = artifactBytes.coerceAtMost(workspaceBytes),
            otherFilesBytes = otherFiles,
            cacheBytes = cacheBytes,
        )
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
                    itemStatistics = page.statistics, matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics,
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
    fun saveAgentModelProfile(endpoint: String, model: String, protocol: NativeModelProtocol,
        keys: List<String>?, onSaved: () -> Unit) = action {
        mutable.value = mutable.value.copy(modelSettingsError = null)
        try {
            val selected = graph.modelProfiles.selected(ModelProfilePurpose.AGENT)
            val settings = if (selected != null) {
                graph.modelProfiles.saveConfiguration(selected.id, endpoint, model, protocol, keys).settings
            } else {
                val resolved = keys ?: run {
                    val current = graph.modelSettings.current() ?: throw InvalidModelSettings()
                    if (NativeModelEndpoint.base(current.endpoint) != NativeModelEndpoint.normalize(endpoint))
                        throw InvalidModelSettings()
                    graph.modelSettings.credentials(current.reference)
                }
                graph.modelSettings.saveProfile(endpoint, model, protocol, resolved)
            }
            reloadModelProfiles()
            mutable.value = mutable.value.copy(modelSettings = settings, notice = R.string.native_model_saved)
            onSaved()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutable.value = mutable.value.copy(modelSettingsError = if (error is InvalidModelSettings || error is InvalidModelProfile)
                R.string.native_model_settings_error else R.string.native_io_error)
        }
    }

    fun openModels() {
        if (pageTransitionBlocked()) return
        saved["native_models_origin"] = (if (mutable.value.project == null) NativePage.AGENT else NativePage.MORE).name
        mutable.value = mutable.value.copy(page = NativePage.MODELS, modelProfilesError = null)
        saved["native_page"] = NativePage.MODELS.name
        refreshModelProfiles()
    }

    fun closeModels() {
        val origin = runCatching { NativePage.valueOf(saved["native_models_origin"] ?: NativePage.MORE.name) }
            .getOrDefault(if (mutable.value.project == null) NativePage.AGENT else NativePage.MORE)
        saved.remove<String>("native_models_origin")
        if (mutable.value.project == null) selectPage(NativePage.AGENT)
        else selectPage(if (origin == NativePage.WORKBENCH) NativePage.MORE else NativePage.MORE)
    }

    fun refreshModelProfiles() = action {
        mutable.value = mutable.value.copy(modelProfilesLoading = true, modelProfilesError = null)
        try { reloadModelProfiles() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(modelProfilesError = R.string.native_model_profiles_error) }
        finally { mutable.value = mutable.value.copy(modelProfilesLoading = false) }
    }

    private suspend fun reloadModelProfiles() {
        val profiles = graph.modelProfiles.list()
        val agent = profiles.firstOrNull { it.enabled && ModelProfilePurpose.AGENT in it.selectedFor }?.settings
            ?: graph.modelSettings.current()
        val translation = profiles.firstOrNull { it.enabled && ModelProfilePurpose.TRANSLATION in it.selectedFor }?.settings
        mutable.value = mutable.value.copy(modelProfiles = profiles, modelSettings = agent,
            translationModelSettings = translation, modelProfileKeys = emptyList())
    }

    private fun modelMutation(block: suspend () -> Unit) = action {
        mutable.value = mutable.value.copy(modelProfilesLoading = true, modelProfilesError = null)
        try {
            block()
            reloadModelProfiles()
            mutable.value = mutable.value.copy(notice = R.string.native_model_saved)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            mutable.value = mutable.value.copy(modelProfilesError = if (error is InvalidModelProfile || error is InvalidModelSettings)
                R.string.native_model_settings_error else R.string.native_model_profiles_error)
        } finally { mutable.value = mutable.value.copy(modelProfilesLoading = false) }
    }

    fun createModelProfile(profile: NewModelProfile) = modelMutation { graph.modelProfiles.create(profile) }

    fun editModelProfile(id: String, edit: ModelProfileEdit) = modelMutation { graph.modelProfiles.edit(id, edit) }

    fun saveModelProfileConfiguration(id: String, endpoint: String, model: String,
        protocol: NativeModelProtocol, keys: List<String>?) =
        modelMutation { graph.modelProfiles.saveConfiguration(id, endpoint, model, protocol, keys) }

    /** Saves the immutable configuration revision first, then the editable metadata in one action. */
    fun updateModelProfile(id: String, edit: ModelProfileEdit, endpoint: String, model: String,
        protocol: NativeModelProtocol, keys: List<String>?) = modelMutation {
        graph.modelProfiles.saveConfiguration(id, endpoint, model, protocol, keys)
        graph.modelProfiles.edit(id, edit)
    }

    fun copyModelProfile(id: String, name: String) = modelMutation { graph.modelProfiles.copy(id, name) }

    fun deleteModelProfile(id: String) = modelMutation { graph.modelProfiles.delete(id) }

    fun moveModelProfile(id: String, offset: Int) = modelMutation { graph.modelProfiles.move(id, offset) }

    fun selectModelProfile(id: String, purpose: ModelProfilePurpose) = modelMutation {
        graph.modelProfiles.select(id, purpose)
    }

    fun loadModelProfileKeys(id: String) = action {
        val profile = graph.modelProfiles.find(id) ?: return@action
        val keys = graph.modelKeys.describe(profile.reference).map {
            ModelProfileKeyState(it.id, it.enabled, it.consecutiveFailures, it.cooldownUntilMillis,
                it.lastFailure?.name)
        }
        mutable.value = mutable.value.copy(modelProfileKeys = keys)
    }

    fun setModelKeyEnabled(id: String, keyId: String, enabled: Boolean) = action {
        val profile = graph.modelProfiles.find(id) ?: return@action
        val keys = graph.modelKeys.setEnabled(profile.reference, keyId, enabled).map {
            ModelProfileKeyState(it.id, it.enabled, it.consecutiveFailures, it.cooldownUntilMillis,
                it.lastFailure?.name)
        }
        mutable.value = mutable.value.copy(modelProfileKeys = keys)
    }

    fun fetchAgentModels(endpoint: String, key: String, savedCredential: ModelConfigurationRef?, onLoaded: (List<String>) -> Unit) =
        fetchAgentModelsInternal(NativeModelProtocol.OPENAI_CHAT_COMPLETIONS, endpoint, key, savedCredential, onLoaded)

    fun fetchAgentModelsProfile(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol, onLoaded: (List<String>) -> Unit) =
        fetchAgentModelsInternal(protocol, endpoint, key, savedCredential, onLoaded)

    private fun fetchAgentModelsInternal(protocol: NativeModelProtocol, endpoint: String, key: String,
        savedCredential: ModelConfigurationRef?, onLoaded: (List<String>) -> Unit) = action {
        mutable.value = mutable.value.copy(modelCatalogLoading = true, modelSettingsError = null)
        try {
            val models = graph.modelCatalog.models(endpoint, key, savedCredential, protocol)
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
        if ((mutable.value.busy && !mutable.value.foregroundAgentActive) || text.length > 8000) return
        mutable.value.project?.let {
            saved["agent_input_ref"] = encodeRef(it.ref); saved["agent_input_chat"] = agentChat.value
            saved["agent_input_text"] = text
            mutable.value = mutable.value.copy(agentInput = text)
        }
    }

    internal suspend fun prepareAgentBranch(ref: LocalProjectRef, chat: ChatId, id: MessageId): AgentBranchPreview {
        require(!mutable.value.busy && mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
        return graph.agentStore.prepareBranch(ref, chat, id)
    }
    internal suspend fun createAgentBranch(preview: AgentBranchPreview, command: CommandId, replacement: String?) {
        val current = mutable.value
        require(!current.busy && current.project?.ref == preview.ref && current.agentChatId == preview.chat &&
            current.agentInput.isBlank() && !current.hasAgentApproval)
        graph.agentStore.createBranch(preview, command, replacement)
        loadAgent(preview.ref)
    }

    internal suspend fun agentHistoryPage(ref: LocalProjectRef, chat: ChatId, cursor: AgentMessageCursor?): AgentMessagePage =
        graph.agentStore.messagePage(ref, chat, cursor)
    internal suspend fun agentHistoryDetail(ref: LocalProjectRef, chat: ChatId, id: String): AgentMessageDetail =
        graph.agentStore.messageDetail(ref, chat, id)

    internal suspend fun prepareAgentContext(ref: LocalProjectRef, chat: ChatId): AgentContextPreview {
        require(!mutable.value.busy && mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
        return graph.agentStore.prepareContext(ref, chat)
    }
    internal suspend fun saveAgentContext(preview: AgentContextPreview, summary: String) {
        require(!mutable.value.busy && mutable.value.project?.ref == preview.ref && mutable.value.agentChatId == preview.chat)
        graph.agentStore.compactContext(preview, summary)
    }

    internal suspend fun steerHistory(ref: LocalProjectRef, chat: ChatId, cursor: AgentSteerRunCursor?): AgentSteerRunPage =
        graph.agentInputs.steerRunPage(ref, chat, cursor)
    internal suspend fun steerDetail(ref: LocalProjectRef, chat: ChatId, run: RunIdentity): StoredAgentSteerLedger? =
        graph.agentInputs.loadSteerLedger(ref, chat, run)
    fun restoreSteerInput(ref: LocalProjectRef, run: RunIdentity, id: CommandId) = action {
        val current = mutable.value
        require(current.project?.ref == ref && current.agentChatId == run.chatId && current.agentInput.isBlank())
        require(graph.runtime.snapshot().activeRun?.identity != run)
        val ledger = graph.agentInputs.loadSteerLedger(ref, requireNotNull(run.chatId), run)
            ?: throw IllegalStateException("Missing steer ledger")
        val attempt = ledger.state.attempts.single { it.id == id }
        require(attempt.status == AgentSteerStatus.ACCEPTED || attempt.status == AgentSteerStatus.CANCELLED)
        // Recheck after IO so a late response cannot overwrite a newly edited draft or another Chat.
        require(mutable.value.project?.ref == ref && mutable.value.agentChatId == run.chatId && mutable.value.agentInput.isBlank())
        saved["agent_input_ref"] = encodeRef(ref); saved["agent_input_chat"] = requireNotNull(run.chatId).value
        saved["agent_input_text"] = attempt.text
        mutable.value = mutable.value.copy(agentInput = attempt.text)
    }

    private var steerSubmitting = false
    fun steerAgentInput() {
        if (steerSubmitting) return
        val state = mutable.value
        val ref = state.project?.ref ?: return
        val text = state.agentInput
        val execution = graph.backgroundAgents.state.value ?: return
        if (!state.foregroundAgentActive || !execution.phase.active || execution.request.ref != ref ||
            execution.request.identity.chatId != state.agentChatId || text.isBlank()) return
        val command = AgentSteerCommand(commandId(), execution.request.identity,
            ChatIdentity(ref.projectId, requireNotNull(state.agentChatId)), text)
        steerSubmitting = true
        viewModelScope.launch {
            try {
                when (graph.steering.submit(ref, command)) {
                    is AgentSteerSubmitResult.Accepted, is AgentSteerSubmitResult.Duplicate -> {
                        if (mutable.value.project?.ref == ref && mutable.value.agentChatId == command.chat.chatId &&
                            mutable.value.agentInput == text) {
                            saved["agent_input_text"] = ""
                            mutable.value = mutable.value.copy(agentInput = "", notice = R.string.native_agent_steer_accepted)
                        }
                    }
                    is AgentSteerSubmitResult.Rejected -> throw IllegalStateException("Steer rejected")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (mutable.value.project?.ref == ref) mutable.value = mutable.value.copy(error = R.string.native_agent_steer_failed)
            } finally { steerSubmitting = false }
        }
    }

    fun queueAgentInput() = action {
        val ref = mutable.value.project?.ref ?: return@action
        val text = mutable.value.agentInput
        if (text.isBlank()) return@action
        val queue = mutable.value.agentQueue ?: AgentInputQueue.empty(ChatIdentity(ref.projectId, agentChat))
        val draft = AgentInputDraft.create(commandId(), queue.chat, text)
        val updated = when (val result = queue.enqueue(draft)) {
            is AgentInputQueueUpdate.Applied -> result.queue
            is AgentInputQueueUpdate.Rejected -> throw TextProjectException(TextProjectException.Reason.INVALID_TEXT)
        }
        persistAgentQueue(updated)
        saved.remove<String>("agent_input_text")
        mutable.value = mutable.value.copy(agentInput = "")
    }

    fun useAgentDraft(id: CommandId) = action {
        val queue = mutable.value.agentQueue ?: return@action
        val draft = queue.drafts.firstOrNull { it.id == id } ?: return@action
        if (draft.text.length > 8000) return@action
        val updated = when (val result = queue.delete(id)) {
            is AgentInputQueueUpdate.Applied -> result.queue
            is AgentInputQueueUpdate.Rejected -> return@action
        }
        persistAgentQueue(updated)
        mutable.value.project?.let { ref ->
            saved["agent_input_ref"] = encodeRef(ref.ref); saved["agent_input_chat"] = agentChat.value
            saved["agent_input_text"] = draft.text
        }
        mutable.value = mutable.value.copy(agentInput = draft.text)
    }

    fun deleteAgentDraft(id: CommandId) = action {
        val queue = mutable.value.agentQueue ?: return@action
        val updated = when (val result = queue.delete(id)) {
            is AgentInputQueueUpdate.Applied -> result.queue
            is AgentInputQueueUpdate.Rejected -> return@action
        }
        persistAgentQueue(updated)
    }

    fun toggleAgentQueuePaused() = action {
        val queue = mutable.value.agentQueue ?: return@action
        val updated = when (val result = if (queue.paused) queue.resume() else queue.pause()) {
            is AgentInputQueueUpdate.Applied -> result.queue
            is AgentInputQueueUpdate.Rejected -> return@action
        }
        persistAgentQueue(updated)
    }

    /**
     * Sends exactly the persisted queue head. The append is reserved and persisted before the
     * foreground service starts; only the accepted user-message receipt removes the draft.
     */
    fun sendQueuedAgentMessage() = action {
        val state = mutable.value
        if (state.agentLoadIncomplete || state.runLoadIncomplete || state.hasAgentApproval) return@action
        val ref = state.project?.ref ?: return@action
        val configuration = state.modelSettings?.reference ?: return@action
        val queue = state.agentQueue ?: return@action
        if (queue.paused || queue.pendingAppend != null) return@action
        val draft = queue.head ?: return@action
        if (draft.text.length > 8000) return@action
        val request = ForegroundAgentRequest(ref,
            RunIdentity(ref.projectId, agentChat, RunId(UUID.randomUUID().toString()), commandId()),
            configuration, draft.text)
        val started = when (val result = queue.beginAppend(request.identity,
            RequestAttemptId(UUID.randomUUID().toString()))) {
            is AgentInputAppendStartResult.Started -> result
            is AgentInputAppendStartResult.Rejected -> return@action
        }
        persistAgentQueue(started.queue)
        val handle = try { graph.backgroundAgents.prepare(request) }
        catch (error: Exception) {
            val released = started.queue.releaseAppend(started.append.attemptId)
            if (released is AgentInputQueueUpdate.Applied) {
                try {
                    persistAgentQueue(released.queue,
                        AgentInputAppendSettlement.Released(started.append.draft.id, started.append.attemptId))
                } catch (_: Exception) { mutable.value = mutable.value.copy(agentQueueIncomplete = true) }
            }
            throw error
        }
        saved["native_agent_token"] = handle.token
        mutable.value = mutable.value.copy(foregroundAgentActive = true, agentContextStatus = null,
            agentRunId = handle.request.identity.runId)
        try { NativeAgentService.start(getApplication<Application>(), handle.token) }
        catch (_: Exception) { graph.backgroundAgents.startFailed(handle.token) }
        awaitQueuedAgent(handle, started.append)
    }

    private suspend fun settleQueuedAppend(append: AgentInputAppend, messageId: MessageId): Boolean {
        val current = mutable.value.agentQueue ?: return false
        val receipt = AgentInputAppendReceipt(append.draft.id, append.draft.chat,
            append.run, append.attemptId, append.revision, messageId)
        return when (val result = current.acknowledgeAppended(receipt)) {
            is AgentInputAppendResult.Consumed -> try {
                persistAgentQueue(result.queue, AgentInputAppendSettlement.Confirmed(receipt))
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.value = mutable.value.copy(agentQueueIncomplete = true)
                false
            }
            is AgentInputAppendResult.Ignored -> {
                mutable.value = mutable.value.copy(agentQueueIncomplete = true)
                false
            }
        }
    }

    private suspend fun awaitQueuedAgent(handle: ForegroundAgentHandle, append: AgentInputAppend) = coroutineScope {
        val ref = handle.request.ref
        val chat = requireNotNull(handle.request.identity.chatId)
        var accepted = false
        var settled = false
        var success = false
        val observer = launch {
            var timelineRevision = -1L
            graph.backgroundAgents.state.collect { snapshot ->
                if (snapshot?.token != handle.token) return@collect
                mutable.value = mutable.value.copy(foregroundAgentActive = snapshot.phase.active,
                    agentContextStatus = snapshot.progress?.contextStatus.takeIf {
                        mutable.value.project?.ref == ref && mutable.value.agentChatId == chat
                    })
                val progress = snapshot.progress
                if (progress?.accepted == true) accepted = true
                val messageId = progress?.userMessageId
                if (!settled && accepted && messageId != null) {
                    settled = settleQueuedAppend(append, messageId)
                }
                val next = progress?.timelineRevision ?: 0L
                if (timelineRevision != next) {
                    timelineRevision = next
                    refreshSimulationView(running = snapshot.phase.active) { refreshAgentTimeline(ref, chat) }
                }
            }
        }
        try { handle.await(); success = true } finally {
            observer.cancel()
            withContext(NonCancellable) {
                observer.join()
                // Cancellation detaches the UI; only a terminal runner may release pending.
                val terminal = graph.backgroundAgents.state.value?.let {
                    it.token == handle.token && !it.phase.active
                } == true
                if (!terminal) return@withContext
                if (!settled) {
                    val finalProgress = graph.backgroundAgents.state.value
                        ?.takeIf { it.token == handle.token }?.progress
                    if (finalProgress?.accepted == true) {
                        accepted = true
                        finalProgress.userMessageId?.let { messageId ->
                            settled = settleQueuedAppend(append, messageId)
                        }
                    }
                }
                if (!settled && !accepted) {
                    val current = mutable.value.agentQueue
                    val pending = current?.pendingAppend
                    if (current != null && pending?.attemptId == append.attemptId) {
                        when (val released = current.releaseAppend(append.attemptId)) {
                            is AgentInputQueueUpdate.Applied -> try {
                                persistAgentQueue(released.queue,
                                    AgentInputAppendSettlement.Released(append.draft.id, append.attemptId))
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                mutable.value = mutable.value.copy(agentQueueIncomplete = true)
                            }
                            is AgentInputQueueUpdate.Rejected ->
                                mutable.value = mutable.value.copy(agentQueueIncomplete = true)
                        }
                    }
                }
                mutable.value = mutable.value.copy(foregroundAgentActive = false)
                if (saved.get<String>("native_agent_token") == handle.token) saved.remove<String>("native_agent_token")
                if (saved.get<Boolean>("native_recovery_pending") == true) refreshSimulationView(running = false) {
                    recoverDirectory()
                    mutable.value = mutable.value.copy(projects = projects.registeredProjects())
                }
                if (mutable.value.project?.ref == ref) {
                    refreshSimulationView(running = false) { loadPage(ref, cursors.last()) }
                    loadAgent(ref)
                }
            }
        }
        if (success) maybeSendNextQueued(ref, chat)
    }

    private fun maybeSendNextQueued(ref: LocalProjectRef, chat: ChatId) {
        viewModelScope.launch {
            state.first { !it.busy }
            val current = mutable.value
            val queue = current.agentQueue ?: return@launch
            if (current.project?.ref != ref || current.agentChatId != chat ||
                current.foregroundAgentActive || current.agentQueueIncomplete ||
                current.hasAgentApproval || queue.paused || queue.pendingAppend != null || queue.head == null) return@launch
            sendQueuedAgentMessage()
        }
    }

    private suspend fun persistAgentQueue(
        queue: AgentInputQueue,
        settlement: AgentInputAppendSettlement? = null,
    ) {
        val ref = mutable.value.project?.ref ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
        val current = agentQueueStored?.takeIf { it.queue.chat == queue.chat }
            ?: StoredAgentInputQueue.initial(ref, queue)
        val lease = graph.runtime.beginProjectWrite()
        try {
            val stored = graph.agentInputs.replaceInputQueue(lease, current.withQueue(queue), settlement)
            agentQueueStored = stored
            mutable.value = mutable.value.copy(agentQueue = stored.queue, agentQueueRevision = stored.revision,
                agentQueueIncomplete = false)
        } finally { graph.runtime.endProjectWrite(lease) }
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
        if (text.isBlank() || mutable.value.hasAgentApproval) return@action
        val handle = graph.backgroundAgents.prepare(ForegroundAgentRequest(ref,
            RunIdentity(ref.projectId, agentChat, RunId(UUID.randomUUID().toString()), commandId()), configuration, text))
        saved["native_agent_token"] = handle.token
        mutable.value = mutable.value.copy(foregroundAgentActive = true, agentContextStatus = null,
            agentRunId = handle.request.identity.runId)
        try { NativeAgentService.start(getApplication<Application>(), handle.token) }
        catch (_: Exception) { graph.backgroundAgents.startFailed(handle.token) }
        awaitAgent(handle)
    }

    private suspend fun awaitAgent(handle: ForegroundAgentHandle) = coroutineScope {
        val ref = handle.request.ref
        val chat = requireNotNull(handle.request.identity.chatId)
        val observer = launch {
            var timelineRevision = -1L
            graph.backgroundAgents.state.collect { snapshot ->
                if (snapshot?.token != handle.token) return@collect
                mutable.value = mutable.value.copy(foregroundAgentActive = snapshot.phase.active,
                    agentContextStatus = snapshot.progress?.contextStatus.takeIf {
                        mutable.value.project?.ref == ref && mutable.value.agentChatId == chat
                    })
                if (snapshot.progress?.accepted == true && mutable.value.project?.ref == ref &&
                    mutable.value.agentChatId == chat && mutable.value.agentInput == handle.request.input) {
                    saved.remove<String>("agent_input_text")
                    mutable.value = mutable.value.copy(agentInput = "")
                }
                val next = snapshot.progress?.timelineRevision ?: 0L
                if (timelineRevision != next) {
                    timelineRevision = next
                    refreshSimulationView(running = snapshot.phase.active) { refreshAgentTimeline(ref, chat) }
                }
            }
        }
        try { handle.await() }
        finally {
            observer.cancel()
            val settled = graph.backgroundAgents.state.value?.let { it.token == handle.token && !it.phase.active } == true
            // UI cancellation only detaches. It never stops the application runner.
            if (settled) withContext(NonCancellable) {
                mutable.value = mutable.value.copy(foregroundAgentActive = false)
                // A fast completion may be conflated before the UI observer sees acceptance.
                if (graph.backgroundAgents.state.value?.progress?.accepted == true &&
                    saved.get<ArrayList<String>>("agent_input_ref")?.toList() == encodeRef(ref).toList() &&
                    saved.get<String>("agent_input_chat") == chat.value &&
                    saved.get<String>("agent_input_text") == handle.request.input) {
                    saved.remove<String>("agent_input_text")
                    if (mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
                        mutable.value = mutable.value.copy(agentInput = "")
                }
                if (saved.get<String>("native_agent_token") == handle.token) saved.remove<String>("native_agent_token")
                if (saved.get<Boolean>("native_recovery_pending") == true) refreshSimulationView(running = false) {
                    recoverDirectory()
                    mutable.value = mutable.value.copy(projects = projects.registeredProjects())
                }
                if (mutable.value.project?.ref == ref) {
                    refreshSimulationView(running = false) { loadPage(ref, cursors.last()) }
                    loadAgent(ref)
                }
            }
        }
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

    fun editPdfPage(ref: LocalProjectRef, revision: Long, asset: ProjectAssetSummary, page: Int) = action {
        val current = mutable.value
        require(current.project?.let { it.ref == ref && it.revision == revision } == true)
        require(current.editor == null && current.manualBatch == null && !current.selectingItems &&
            !current.policyLoadIncomplete && !current.runLoadIncomplete && current.policyDraft?.dirty != true)
        val reader = projects as? PdfPageReadPort ?: throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
        val item = reader.pdfPage(ref, revision, asset, page)
        require(mutable.value.project?.let { it.ref == ref && it.revision == revision } == true)
        require(item.assetId == asset.id && item.row == page && item.metadata.tag == "page:$page")
        setDraft(NativeEditDraft(ref, item, revision, item.fingerprint, item.translation, item.status, commandId()))
        mutable.value = mutable.value.copy(notice = R.string.native_pdf_edit_notice)
    }

    suspend fun pdfPage(ref: LocalProjectRef, revision: Long, asset: ProjectAssetSummary, page: Int): TextProjectItem {
        require(mutable.value.project?.let { it.ref == ref && it.revision == revision } == true)
        val reader = projects as? PdfPageReadPort ?: throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
        return reader.pdfPage(ref, revision, asset, page).also {
            require(mutable.value.project?.let { current -> current.ref == ref && current.revision == revision } == true)
        }
    }

    /**
     * Renders one original PDF page for the selected preview. The result is not written to the UI
     * state; the dialog only receives bytes. The loader rejects a render whose page, asset or
     * project revision was superseded while PDFBox was working.
     */
    suspend fun pdfPageThumbnail(ref: LocalProjectRef, revision: Long, asset: ProjectAssetSummary,
                                 page: Int): ByteArray {
        if (asset.format != ProjectAssetFormat.PDF) {
            throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
        }
        if (asset.itemCount !in 1..5_000 || page !in 1..asset.itemCount) {
            throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        }
        val current = mutable.value.project
        if (current?.ref != ref || current.revision != revision || current.assets.none { it == asset }) {
            throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        }
        return pdfPageThumbnails.load(
            PdfPageThumbnailKey(ref, revision, asset.id, asset.sha256, page), asset,
        ) {
            val latest = mutable.value.project
            latest != null && latest.ref == ref && latest.revision == revision &&
                latest.assets.any { it == asset }
        }
    }

    suspend fun itemProposalMembers(view: ItemProposalSummary): List<ItemProposalMemberSummary> {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.itemProposals.members(view).also {
            require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        }
    }

    suspend fun itemProposalDetail(view: ItemProposalSummary, id: ItemId): ItemProposalMember {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.itemProposals.detail(view, id).also {
            require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        }
    }

    suspend fun itemProposalReceipt(view: ItemProposalSummary): ItemProposalReceipt? {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.itemProposals.receipt(view)
    }

    fun rejectItemProposal(view: ItemProposalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        graph.itemProposals.reject(view)
        loadAgent(view.ref)
    }

    fun acceptItemProposal(view: ItemProposalSummary, selection: Set<ItemId>) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete || view.state != ItemProposalState.PENDING) return@action
        try {
            val result = agentRun(view.ref, view.run, view.command) { lease, recorder ->
                graph.itemProposals.apply(lease, view, selection.toSet()).also { recorder.itemProposalResult(it) }
            }
            mutable.value = mutable.value.copy(notice = when (result) {
                ItemProposalState.COMMITTED -> R.string.native_saved
                ItemProposalState.PARTIAL -> R.string.native_item_proposal_partial
                ItemProposalState.CONFLICT -> R.string.native_conflict
                ItemProposalState.REJECTED -> R.string.native_agent_result_rejected
                ItemProposalState.CANCELLED -> R.string.native_agent_result_cancelled
                else -> R.string.native_agent_unresolved
            })
        } finally {
            withContext(NonCancellable) { loadPage(view.ref, cursors.last()); loadAgent(view.ref) }
        }
    }

    suspend fun policyProposalDetail(view: PolicyApprovalSummary): PolicyChangeProposal {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.policyProposals.detail(view).also {
            require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        }
    }
    suspend fun policyProposalReceipt(view: PolicyApprovalSummary): PolicyEditReceipt? {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.policyProposals.receipt(view)
    }
    suspend fun selectPolicyProposal(view: PolicyApprovalSummary, rules: Set<String>, order: Boolean,
                                     prompt: Boolean): PolicyApprovalSummary = queuedActionResult {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        require(!mutable.value.agentLoadIncomplete && !mutable.value.runLoadIncomplete)
        graph.policyProposals.select(view, rules, order, prompt).also { loadAgent(view.ref) }
    }.await()

    fun rejectPolicyProposal(view: PolicyApprovalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        graph.policyProposals.reject(view)
        loadAgent(view.ref)
    }
    fun acceptPolicyProposal(view: PolicyApprovalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete || view.state != TextApprovalState.PENDING) return@action
        try {
            val result = agentRun(view.ref, view.run, view.command) { lease, recorder ->
                graph.policyProposals.apply(lease, view).also { recorder.approvalResult(it) }
            }
            mutable.value = mutable.value.copy(notice = when (result) {
                TextApprovalState.COMMITTED -> R.string.native_saved
                TextApprovalState.CONFLICT -> R.string.native_conflict
                TextApprovalState.REJECTED -> R.string.native_agent_result_rejected
                TextApprovalState.CANCELLED -> R.string.native_agent_result_cancelled
                else -> R.string.native_agent_unresolved
            })
        } finally {
            withContext(NonCancellable) {
                loadPage(view.ref, cursors.last())
                // Dirty drafts retain their original baseline and become conflicted; clean
                // drafts refresh to the actual committed policy, never a preview copy.
                loadTextPolicy(view.ref, restore = true)
                loadAgent(view.ref)
            }
        }
    }

    suspend fun pdfPageProposalMembers(view: PdfPageProposalSummary): List<PdfPageChangeMemberSummary> {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.pdfPageProposals.members(view).also {
            require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        }
    }

    suspend fun pdfPageProposalReceipt(view: PdfPageProposalSummary): PdfPageProposalReceipt? {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.pdfPageProposals.receipt(view)
    }

    fun rejectPdfPageProposal(view: PdfPageProposalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        graph.pdfPageProposals.reject(view)
        loadAgent(view.ref)
    }

    fun acceptPdfPageProposal(view: PdfPageProposalSummary, selection: Set<Int>) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete ||
            view.state != PdfPageProposalState.PENDING) return@action
        try {
            val result = agentRun(view.ref, view.run, view.command) { lease, recorder ->
                graph.pdfPageProposals.apply(lease, view, selection.toSet()).also {
                    recorder.itemProposalResult(if (it == PdfPageProposalState.PARTIAL) ItemProposalState.PARTIAL
                    else if (it == PdfPageProposalState.COMMITTED) ItemProposalState.COMMITTED
                    else if (it == PdfPageProposalState.CONFLICT) ItemProposalState.CONFLICT else ItemProposalState.CANCELLED)
                }
            }
            mutable.value = mutable.value.copy(notice = when (result) {
                PdfPageProposalState.COMMITTED -> R.string.native_saved
                PdfPageProposalState.PARTIAL -> R.string.native_pdf_proposal_partial
                PdfPageProposalState.CONFLICT -> R.string.native_conflict
                PdfPageProposalState.REJECTED -> R.string.native_agent_result_rejected
                PdfPageProposalState.CANCELLED -> R.string.native_agent_result_cancelled
                else -> R.string.native_agent_unresolved
            })
        } finally {
            withContext(NonCancellable) { loadPage(view.ref, cursors.last()); loadAgent(view.ref) }
        }
    }

    suspend fun assetImportProposalReceipt(view: AssetImportProposalSummary): AssetImportReceipt? {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        return graph.assetImportProposals.receipt(view)
    }

    fun rejectAssetImportProposal(view: AssetImportProposalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        graph.assetImportProposals.reject(view)
        loadAgent(view.ref)
    }

    fun acceptAssetImportProposal(view: AssetImportProposalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete ||
            view.state != AssetImportProposalState.PENDING) return@action
        try {
            val result = agentRun(view.ref, view.run, view.command) { lease, recorder ->
                graph.assetImportProposals.apply(lease, view).also {
                    recorder.itemProposalResult(if (it == AssetImportProposalState.APPLIED) ItemProposalState.COMMITTED
                    else if (it == AssetImportProposalState.STALE) ItemProposalState.CONFLICT
                    else if (it == AssetImportProposalState.CONFLICT) ItemProposalState.CONFLICT
                    else if (it == AssetImportProposalState.REJECTED) ItemProposalState.REJECTED
                    else if (it == AssetImportProposalState.CANCELLED) ItemProposalState.CANCELLED
                    else ItemProposalState.PENDING)
                }
            }
            mutable.value = mutable.value.copy(notice = when (result) {
                AssetImportProposalState.APPLIED -> R.string.native_saved
                AssetImportProposalState.STALE -> R.string.native_asset_import_stale
                AssetImportProposalState.CONFLICT -> R.string.native_asset_import_conflict
                AssetImportProposalState.REJECTED -> R.string.native_agent_result_rejected
                AssetImportProposalState.CANCELLED -> R.string.native_agent_result_cancelled
                else -> R.string.native_agent_unresolved
            })
        } finally {
            withContext(NonCancellable) { loadPage(view.ref, cursors.last()); loadAgent(view.ref) }
        }
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
        if (selectedChat != null) {
            loadOr(Unit) { graph.agentStore.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.itemProposals.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.policyProposals.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.pdfPageProposals.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.assetImportProposals.reconcile(ref, selectedChat) }
        }
        val messages = if (selectedChat == null) emptyList() else
            loadOr(emptyList<AgentChatLine>()) { graph.agentStore.messages(ref, selectedChat) }
        val approval = if (selectedChat == null) null else
            loadOr<TextApproval?>(null) { graph.agentStore.pending(ref, selectedChat) }
        val results = if (selectedChat == null) emptyList() else
            loadOr(emptyList<AgentApprovalResult>()) { graph.agentStore.results(ref, selectedChat) }
        val proposals = if (selectedChat == null) emptyList() else
            loadOr(emptyList<ItemProposalSummary>()) { graph.itemProposals.recent(ref, selectedChat) }
        val policyProposals = if (selectedChat == null) emptyList() else
            loadOr(emptyList<PolicyApprovalSummary>()) { graph.policyProposals.recent(ref, selectedChat) }
        val pdfPageProposals = if (selectedChat == null) emptyList() else
            loadOr(emptyList<PdfPageProposalSummary>()) { graph.pdfPageProposals.recent(ref, selectedChat) }
        val assetImportProposals = if (selectedChat == null) emptyList() else
            loadOr(emptyList<AssetImportProposalSummary>()) { graph.assetImportProposals.recent(ref, selectedChat) }
        val settings = loadOr<NativeModelSettings?>(mutable.value.modelSettings) { graph.modelSettings.current() }
        val translationSettings = loadOr<NativeModelSettings?>(mutable.value.translationModelSettings) {
            graph.modelProfiles.selected(ModelProfilePurpose.TRANSLATION)?.settings
        }
        val profiles = loadOr(mutable.value.modelProfiles) { graph.modelProfiles.list() }
        val chats = loadOr(selectedChat?.let { listOf(it) } ?: emptyList()) { graph.agentStore.chats(ref) }
        val storedQueue = if (selectedChat == null) null else
            loadOr<StoredAgentInputQueue?>(null) { graph.agentInputs.loadInputQueue(ref, selectedChat) }
        agentQueueStored = storedQueue
        val queue = storedQueue?.queue ?: selectedChat?.let {
            AgentInputQueue.empty(ChatIdentity(ref.projectId, it))
        }
        val draft = if (saved.get<ArrayList<String>>("agent_input_ref")?.toList() == encodeRef(ref).toList() &&
            saved.get<String>("agent_input_chat") == selectedChat?.value) saved.get<String>("agent_input_text") ?: "" else ""
        mutable.value = mutable.value.copy(agentMessages = messages, approval = approval, agentResults = results, itemProposals = proposals, policyProposals = policyProposals,
            pdfPageProposals = pdfPageProposals, assetImportProposals = assetImportProposals,
            agentContextStatus = mutable.value.agentContextStatus.takeIf { mutable.value.agentChatId == selectedChat },
            agentChatId = selectedChat, agentChats = chats, modelSettings = settings,
            translationModelSettings = translationSettings, modelProfiles = profiles, agentInput = draft,
            agentQueue = queue, agentQueueRevision = storedQueue?.revision ?: 0,
            agentQueueIncomplete = incomplete, agentLoadIncomplete = incomplete, agentMessageDetail = null)
        loadRuns(ref)
    }

    /** User-triggered local script run. The caller owns cancellation; no model is called. */
    private suspend fun executeSkill(target: NativeSkillExecutionTarget, input: String, allowFetch: Boolean) =
        withContext(Dispatchers.Main.immediate) {
            val current = mutable.value
            val selected = requireNotNull(current.project)
            require(!current.busy && selected.ref == target.ref && selected.revision == target.revision &&
                current.agentChatId == target.identity.chatId && agentChat == target.identity.chatId &&
                current.editor == null && current.manualBatch == null)
            JsonObjectValue.parse(input, JsonInputLimits(16_384, 16))
            val action = ++actionId
            job = currentCoroutineContext()[Job]
            mutable.value = current.copy(busy = true, error = null, notice = null)
            try {
                agentRun(target.ref, target.identity.runId, target.identity.commandId) { lease, _ ->
                    val operation = graph.runtime.beginOperation(lease, OperationKind.SCRIPT)
                    try {
                        val source = withContext(Dispatchers.IO) {
                            val skill = requireNotNull(graph.workspaceSkills.record(target.skill.summary.id))
                            require(skill.fingerprint == target.skill.fingerprint && skill.summary.enabled)
                            val entry = target.file.path.value.removePrefix(skill.summary.id.value + "/")
                            val file = graph.workspaceSkills.files(skill.summary.id).single { it.path == target.file.path }
                            require(file.sha256 == target.file.sha256 && file.sizeBytes == target.file.sizeBytes)
                            com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillScriptSource(
                                graph.workspaceSkills, graph.workspaceSkills).resolve(skill.summary.id.value, entry).also {
                                require(it.sha256 == target.file.sha256 && it.sizeBytes == target.file.sizeBytes)
                            }
                        }
                        val result = graph.workspaceAnalysis.analyze(target.ref, lease, source.text, target.revision,
                            allowFetch = allowFetch, inputJson = input)
                        check(result.result.exitCode == 0 && !result.result.outputTruncated) { "Skill did not complete" }
                        result
                    } finally { graph.runtime.endOperation(operation) }
                }
            } finally {
                withContext(NonCancellable) {
                    if (mutable.value.project?.ref == target.ref) runCatching { loadAgent(target.ref) }
                    if (actionId == action) {
                        mutable.value = mutable.value.copy(busy = false)
                        job = null
                    }
                }
            }
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
                    if (graph.agentStore.pending(ref, agentChat)?.runId == runId ||
                        graph.itemProposals.pending(ref, agentChat)?.run == runId ||
                        graph.policyProposals.pending(ref, agentChat)?.run == runId ||
                        graph.pdfPageProposals.pending(ref, agentChat)?.run == runId ||
                        graph.assetImportProposals.pending(ref, agentChat)?.run == runId) recorder.waitingForApproval()
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
            val queueRuns = runCatching {
                graph.translationQueue.snapshot(ref).queue.tasks.map { it.identity.run.runId.value }.toSet()
            }.getOrDefault(mutable.value.queueManagedRuns)
            val chat = mutable.value.agentChatId
            val kept = mutable.value.agentRunId?.takeIf { runId ->
                runs.any { it.owner == RuntimeOwner.AGENT && it.handle.run.chatId == chat &&
                    it.handle.run.runId == runId }
            }
            val selected = kept ?: runs.firstOrNull {
                it.owner == RuntimeOwner.AGENT && it.handle.run.chatId == chat
            }?.handle?.run?.runId
            mutable.value = mutable.value.copy(recentRuns = runs, runLoadIncomplete = false,
                agentRunId = selected, queueManagedRuns = queueRuns)
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

    /**
     * Admits the same frozen paid spec to the durable queue and kicks the application-level
     * cross-project scheduler. The ViewModel never owns the drain loop or navigates another
     * project; a surviving Activity only observes the existing foreground runner.
     */
    fun enqueueRemoteTranslation(ref: LocalProjectRef, revision: Long, configuration: ModelConfigurationRef,
        options: RemoteTranslationOptions, wholeProject: Boolean) = action {
        val current = mutable.value
        val project = current.project ?: return@action
        if (current.runLoadIncomplete || current.policyLoadIncomplete || current.policyDraft?.dirty == true ||
            current.editor != null || current.proofreadingQuery.active || current.qualityView != null ||
            current.manualBatch != null || current.selectingItems) return@action
        if (ref != project.ref || revision != project.revision)
            throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        graph.modelSettings.resolve(configuration)
        val after = if (wholeProject) 0L else cursors.last()
        val identity = RunIdentity(ref.projectId, null, RunId(UUID.randomUUID().toString()), commandId())
        val spec = TranslationTaskSpec(ref, identity, configuration, project.revision, after, PAGE_SIZE, wholeProject, options)
        val queued = graph.translationQueue.enqueue(spec, identity.commandId)
        if (queued is TranslationQueueResult.Rejected)
            throw com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused(queued.reason)
        graph.translationQueueScheduler.kick()
    }

    /** Explicit start of a QUEUED task. It still only runs when it is the deterministic head. */
    fun startQueuedTranslation(identity: TranslationTaskQueueIdentity) = action {
        graph.translationQueueScheduler.start(identity)
    }

    /** Pause reaches the live runner first, then persists the queue outcome. */
    fun pauseQueuedTranslation(identity: TranslationTaskQueueIdentity) = action {
        val result = graph.translationQueueScheduler.pause(identity, commandId())
        if (result is TranslationQueueResult.Rejected)
            throw com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused(result.reason)
    }

    fun cancelQueuedTranslation(identity: TranslationTaskQueueIdentity) = action {
        val result = graph.translationQueueScheduler.cancel(identity, commandId())
        if (result is TranslationQueueResult.Rejected)
            throw com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused(result.reason)
    }

    /**
     * State-aware queue command used by the dialog's compatibility callback. RUNNING pauses,
     * QUEUED starts the deterministic head and PAUSED resumes with a new explicit command.
     * Start/resume hand execution to the application scheduler so a destroyed ViewModel cannot
     * leave a paid task without a recorded queue outcome.
     */
    fun resumeQueuedTranslation(identity: TranslationTaskQueueIdentity) = action {
        val task = graph.translationQueue.snapshot(identity.ref).task(identity) ?: return@action
        when (task.state) {
            TranslationTaskQueueState.RUNNING -> {
                val result = graph.translationQueueScheduler.pause(identity, commandId())
                if (result is TranslationQueueResult.Rejected)
                    throw com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused(result.reason)
            }
            TranslationTaskQueueState.QUEUED ->
                graph.translationQueueScheduler.start(identity)
            TranslationTaskQueueState.PAUSED ->
                graph.translationQueueScheduler.resume(identity.ref, commandId())
            else -> return@action
        }
    }

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
            val statistics = page.statistics ?: return@action
            val mode = options?.startMode ?: TranslationStartMode.NEW
            val eligible = statistics.translatableUnprocessed > 0 ||
                (mode != TranslationStartMode.NEW && statistics.errors > 0) ||
                (mode == TranslationStartMode.RESET && statistics.processed > 0)
            if (!eligible) return@action
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
                        foregroundTranslationActive = snapshot.phase.active,
                        foregroundTranslationCanPause = snapshot.phase == ForegroundTranslationPhase.RUNNING &&
                            snapshot.request.options != null && snapshot.progress != null,
                        foregroundTranslationPausing = snapshot.phase == ForegroundTranslationPhase.PAUSING)
            }
        }
        try {
            val result = handle.await()
            mutable.value = mutable.value.copy(translationProgress = result,
                notice = if (handle.request.options == null) R.string.native_simulation_done else R.string.native_remote_done)
        } catch (_: ForegroundTranslationPaused) {
            mutable.value = mutable.value.copy(notice = R.string.native_paused)
        } finally {
            observer.cancel()
            // A destroyed UI leaves a live service alone. A settled run re-reads committed facts.
            val snapshot = backgroundTranslations.state.value
            if (snapshot?.token == handle.token && !snapshot.phase.active) {
                mutable.value = mutable.value.copy(foregroundTranslationActive = false,
                    foregroundTranslationCanPause = false, foregroundTranslationPausing = false)
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

    internal fun saveProjectSettings(intent: NativeProjectSettingsChange) = action {
        val current = mutable.value
        val project = current.project ?: return@action
        if (current.editor != null || current.manualBatch != null || current.selectingItems ||
            current.policyDraft?.dirty == true || current.policyLoadIncomplete || current.runLoadIncomplete) return@action
        if (project.ref != intent.ref || project.revision != intent.revision)
            throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        val assets = projects as? ProjectAssetPort ?: return@action
        val receipt = assets.editProjectSettings(intent.command, intent.ref, intent.revision, intent.settings)
        if (receipt.status != AssetMutationStatus.COMMITTED) {
            mutable.value = mutable.value.copy(error = R.string.native_conflict)
            return@action
        }
        mutable.value = mutable.value.copy(project = project.copy(revision = receipt.revision, settings = intent.settings),
            qualityView = null, qualityInvalidated = false, qualityProgress = null, notice = R.string.native_saved)
        cursors.clear(); cursors.add(0L)
        refreshCleanTextPolicy(intent.ref)
        loadPage(intent.ref, 0)
    }

    internal fun mutateAsset(intent: NativeAssetMutation) = action {
        val current = mutable.value
        val project = current.project ?: return@action
        if (current.editor != null || current.manualBatch != null || current.selectingItems ||
            current.policyDraft?.dirty == true || current.policyLoadIncomplete || current.runLoadIncomplete) return@action
        if (project.ref != intent.ref || project.revision != intent.revision)
            throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        val assetStore = projects as? ProjectAssetPort ?: return@action
        val assets = project.assets
        val index = assets.indexOfFirst { it.id == intent.assetId }
        if (index < 0) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        val receipt = when (intent.change) {
            NativeAssetChange.REMOVE -> {
                if (assets.size <= 1) return@action
                assetStore.removeAsset(intent.command, intent.ref, intent.revision, intent.assetId)
            }
            NativeAssetChange.ENABLE, NativeAssetChange.DISABLE -> assetStore.setAssetEnabled(intent.command,
                intent.ref, intent.revision, intent.assetId, intent.change == NativeAssetChange.ENABLE)
            NativeAssetChange.RESET -> assetStore.resetAsset(intent.command, intent.ref, intent.revision, intent.assetId)
            NativeAssetChange.MOVE_UP, NativeAssetChange.MOVE_DOWN -> {
                val destination = index + if (intent.change == NativeAssetChange.MOVE_UP) -1 else 1
                if (destination !in assets.indices) return@action
                val order = assets.map { it.id }.toMutableList()
                val moved = order[index]; order[index] = order[destination]; order[destination] = moved
                assetStore.reorderAssets(intent.command, intent.ref, intent.revision, order)
            }
        }
        if (receipt.status != AssetMutationStatus.COMMITTED) {
            mutable.value = mutable.value.copy(error = R.string.native_conflict)
            return@action
        }
        // Publish the committed revision before any refresh that can fail.
        mutable.value = mutable.value.copy(project = project.copy(revision = receipt.revision), notice = R.string.native_saved,
            qualityView = null, qualityInvalidated = false, qualityProgress = null,
            editorQuality = null, editorReplacement = null)
        cursors.clear(); cursors.add(0L)
        refreshCleanTextPolicy(intent.ref)
        loadPage(intent.ref, 0)
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

    fun prepareAssetExport(assetId: Long, bilingual: Boolean = false): String? {
        val project = mutable.value.project ?: return null
        if (mutable.value.busy) return null
        val asset = project.assets.firstOrNull { it.id == assetId } ?: return null
        val settings = project.settings ?: ProjectSettings()
        saved["native_asset_export"] = encodeRef(project.ref).apply {
            add(assetId.toString()); add(project.revision.toString()); add(bilingual.toString())
            add(settings.bilingualDeduplicateSame.toString()); add(settings.writeTranslatedNames.toString()); add(settings.targetLanguage)
        }
        val name = asset.path.value.substringAfterLast('/').take(180)
        return name.ifBlank { "asset-${asset.id}" }
    }

    fun completeAssetExport(uri: Uri?) {
        val pending = saved.get<ArrayList<String>>("native_asset_export")
        if (uri == null) { saved.remove<ArrayList<String>>("native_asset_export"); return }
        if (pending == null) return
        if (pending.size != 9) { saved.remove<ArrayList<String>>("native_asset_export"); return }
        val ref = decodeRef(pending.take(3))
        val assetId = pending[3].toLongOrNull() ?: return
        val revision = pending[4].toLongOrNull() ?: return
        val options = FormatWriteOptions(pending[5].toBooleanStrict(), pending[6].toBooleanStrict(),
            pending[7].toBooleanStrict(), pending[8])
        queuedAction {
            val destination = { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException() }
            if (mutable.value.project?.ref != ref || projects.open(ref).revision != revision)
                throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            graph.formatProjects.exportAsset(ref, assetId, options, destination)
            saved.remove<ArrayList<String>>("native_asset_export")
            mutable.value = mutable.value.copy(notice = R.string.native_exported)
        }
    }

    /**
     * Binds one reviewed artifact identity before the SAF picker. The full body is read only
     * after the user chooses a destination, never while the review dialog is composing.
     */
    fun prepareWorkspaceArtifactExport(artifact: WorkspaceArtifactRecord): String? {
        val state = mutable.value
        val project = state.project ?: return null
        if (state.busy || state.agentRunId != artifact.runId) return null
        val name = artifact.path.value.substringAfterLast('/').take(180)
            .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("").ifBlank { "artifact" }
        saved["native_workspace_artifact"] = encodeRef(project.ref).apply {
            add(artifact.runId.value); add(artifact.id.value); add(name); add(artifact.sha256); add(artifact.sizeBytes.toString())
        }
        return name
    }

    fun completeWorkspaceArtifactExport(uri: Uri?) {
        val pending = saved.remove<ArrayList<String>>("native_workspace_artifact")?.toList() ?: return
        if (uri == null || pending.size != 8) return
        val ref = decodeRef(pending)
        val runId = runCatching { RunId(pending[3]) }.getOrNull() ?: return
        val artifactId = runCatching { WorkspaceArtifactId(pending[4]) }.getOrNull() ?: return
        queuedAction {
            if (mutable.value.project?.ref != ref)
                throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            try {
                val bytes = graph.workspace.readVerifiedArtifactBytes(ref, runId, artifactId,
                    pending[6], pending[7].toLongOrNull() ?: throw IllegalArgumentException("Invalid artifact size"))
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                        ?: throw java.io.IOException("Artifact destination unavailable")
                }
                mutable.value = mutable.value.copy(notice = R.string.native_workspace_saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = R.string.native_workspace_save_failed)
            }
        }
    }

    fun selectPage(page: NativePage) {
        if ((mutable.value.busy && !mutable.value.foregroundExecutionActive) || mutable.value.editor != null || mutable.value.manualBatch != null) return
        mutable.value = mutable.value.copy(page = page)
        saved["native_page"] = page.name
    }

    /** More (or the project-free top bar) opens the routed app settings surface. */
    fun openSettings(section: NativeSettingsSection) {
        if ((mutable.value.busy && !mutable.value.foregroundExecutionActive) ||
            mutable.value.editor != null || mutable.value.manualBatch != null) return
        mutable.value = mutable.value.copy(page = NativePage.SETTINGS, settingsSection = section)
        saved["native_page"] = NativePage.SETTINGS.name
        saved["native_settings_section"] = section.name
        ensureSettingsLoaded()
    }

    /**
     * Initial settings IO can race the larger project-restore action. The page only needs the
     * values when it is visible, so retry once the action has settled instead of showing a
     * permanent loading state.
     */
    private fun ensureSettingsLoaded() {
        if (mutable.value.networkSettingsState.loaded && mutable.value.requestLimitsState.loaded &&
            mutable.value.laboratoryLoaded) return
        viewModelScope.launch {
            state.first { !it.busy }
            val automatic = withContext(Dispatchers.IO) { appSettings.automaticContextSummary() }
            val theme = withContext(Dispatchers.IO) { appSettings.themeMode() }
            val language = withContext(Dispatchers.IO) { appSettings.language() }
            val persona = withContext(Dispatchers.IO) { appSettings.appPersona() }
            mutable.update { it.copy(automaticContextSummary = automatic, themeMode = theme,
                appLanguage = language, appPersona = persona) }
            if (!mutable.value.networkSettingsState.loaded) reloadNetworkSettings()
            if (!mutable.value.requestLimitsState.loaded) reloadRequestLimits()
            if (!mutable.value.laboratoryLoaded) reloadLaboratorySettings()
        }
    }

    fun selectSettingsSection(section: NativeSettingsSection) {
        if (mutable.value.page != NativePage.SETTINGS) return
        mutable.value = mutable.value.copy(settingsSection = section)
        saved["native_settings_section"] = section.name
        if (section == NativeSettingsSection.LABORATORY && !mutable.value.laboratoryLoaded && !mutable.value.busy)
            reloadLaboratorySettings()
    }

    /** More opens a concrete U05—U08 destination without changing the four main tabs. */
    fun openRules(section: NativeRuleSection) {
        if ((mutable.value.busy && !mutable.value.foregroundExecutionActive) ||
            mutable.value.editor != null || mutable.value.manualBatch != null) return
        mutable.value = mutable.value.copy(page = NativePage.RULES, rulesSection = section)
        saved["native_page"] = NativePage.RULES.name
        saved["native_rules_section"] = section.name
    }

    /** Section navigation only changes the visible section; the policy draft and page stay put. */
    fun selectRulesSection(section: NativeRuleSection) {
        if (mutable.value.page != NativePage.RULES) return
        mutable.value = mutable.value.copy(rulesSection = section)
        saved["native_rules_section"] = section.name
    }

    fun setThemeMode(mode: NativeThemeMode) {
        mutable.value = mutable.value.copy(themeMode = mode)
        viewModelScope.launch(Dispatchers.IO) { runCatching { appSettings.saveThemeMode(mode) } }
    }

    fun setAutomaticContextSummary(enabled: Boolean) = action {
        withContext(Dispatchers.IO) { appSettings.saveAutomaticContextSummary(enabled) }
        mutable.update { it.copy(automaticContextSummary = enabled) }
    }

    /**
     * Application persona is not a project rule. Invalid text never reaches storage, and a
     * failed save keeps the caller's visible draft intact while exposing an inline error.
     */
    fun saveAppPersona(text: String) {
        val normalized = try {
            NativeAppPersona.requireValid(text)
        } catch (_: IllegalArgumentException) {
            mutable.update { it.copy(appPersonaSaveFailed = true) }
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appSettings.saveAppPersona(normalized) }
                mutable.update { it.copy(appPersona = normalized, appPersonaSaveFailed = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(appPersonaSaveFailed = true) }
            }
        }
    }

    /**
     * Persists the interface language, then recreates the Activity through [applied]. The
     * Activity-scoped view model (project, chat, queue, editor and rule drafts) survives that
     * configuration change; this is not a process restart.
     */
    fun setAppLanguage(language: NativeAppLanguage, applied: () -> Unit = {}) {
        if (language == mutable.value.appLanguage) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { appSettings.saveLanguage(language) }
                mutable.update { it.copy(appLanguage = language, notice = R.string.native_settings_language_saved) }
                applied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = R.string.native_settings_language_failed) }
            }
        }
    }

    fun reloadNetworkSettings() {
        val current = mutable.value.networkSettingsState
        if (current.saving) return
        mutable.update { it.copy(networkSettingsState = it.networkSettingsState.copy(
            loading = true, loadFailed = false, conflict = false, invalid = false, saveFailed = false)) }
        viewModelScope.launch {
            val loaded = readNetworkSettings(current.revision + 1)
            mutable.update { state ->
                state.copy(networkSettingsState = if (loaded.loadFailed) state.networkSettingsState.copy(
                    loading = false, loadFailed = true) else loaded)
            }
        }
    }

    fun saveNetworkSettings(input: NativeNetworkSettingsInput) {
        val current = mutable.value.networkSettingsState
        if (!current.loaded || current.loading || current.loadFailed || current.saving) return
        val settings = try {
            input.toSettings()
        } catch (_: IllegalArgumentException) {
            mutable.update { it.copy(networkSettingsState = it.networkSettingsState.copy(
                invalid = true, conflict = false, saveFailed = false)) }
            return
        }
        mutable.update { it.copy(networkSettingsState = it.networkSettingsState.copy(
            saving = true, invalid = false, conflict = false, saveFailed = false)) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    settingsStorage.saveNetworkSettings(settings, current.fingerprint)
                }
                mutable.update { it.copy(
                    networkSettingsState = NativeNetworkSettingsState(settings = saved.settings,
                        fingerprint = saved.fingerprint, loaded = true, loading = false,
                        revision = current.revision + 1),
                    notice = R.string.native_settings_network_saved) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val refreshed = readNetworkSettings(current.revision)
                val conflict = refreshed.loaded && refreshed.fingerprint != current.fingerprint
                mutable.update { state ->
                    val base = state.networkSettingsState
                    val next = if (conflict) base.copy(settings = refreshed.settings,
                        fingerprint = refreshed.fingerprint, loaded = refreshed.loaded, loading = false,
                        saving = false, conflict = true, invalid = false, saveFailed = false)
                    else base.copy(saving = false, conflict = false, invalid = false,
                        saveFailed = !refreshed.loadFailed,
                        loadFailed = refreshed.loadFailed || base.loadFailed)
                    state.copy(networkSettingsState = next)
                }
            }
        }
    }

    fun reloadRequestLimits() {
        val current = mutable.value.requestLimitsState
        if (current.saving) return
        mutable.update { it.copy(requestLimitsState = it.requestLimitsState.copy(
            loading = true, loadFailed = false, conflict = false, invalid = false, saveFailed = false)) }
        viewModelScope.launch {
            val loaded = readRequestLimits(current.revision + 1)
            mutable.update { state ->
                state.copy(requestLimitsState = if (loaded.loadFailed) state.requestLimitsState.copy(
                    loading = false, loadFailed = true) else loaded)
            }
        }
    }

    fun saveRequestLimits(input: NativeRequestLimitsInput) {
        val current = mutable.value.requestLimitsState
        if (!current.loaded || current.loading || current.loadFailed || current.saving) return
        val limits = try {
            input.toLimits()
        } catch (_: IllegalArgumentException) {
            mutable.update { it.copy(requestLimitsState = it.requestLimitsState.copy(
                invalid = true, conflict = false, saveFailed = false)) }
            return
        }
        mutable.update { it.copy(requestLimitsState = it.requestLimitsState.copy(
            saving = true, invalid = false, conflict = false, saveFailed = false)) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    settingsStorage.saveRequestLimits(limits, current.fingerprint)
                }
                mutable.update { it.copy(
                    requestLimitsState = NativeRequestLimitsState(limits = saved.limits,
                        fingerprint = saved.fingerprint, loaded = true, loading = false,
                        revision = current.revision + 1),
                    notice = R.string.native_settings_request_saved) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val refreshed = readRequestLimits(current.revision)
                val conflict = refreshed.loaded && refreshed.fingerprint != current.fingerprint
                mutable.update { state ->
                    val base = state.requestLimitsState
                    val next = if (conflict) base.copy(limits = refreshed.limits,
                        fingerprint = refreshed.fingerprint, loaded = refreshed.loaded, loading = false,
                        saving = false, conflict = true, invalid = false, saveFailed = false)
                    else base.copy(saving = false, conflict = false, invalid = false,
                        saveFailed = !refreshed.loadFailed,
                        loadFailed = refreshed.loadFailed || base.loadFailed)
                    state.copy(requestLimitsState = next)
                }
            }
        }
    }

    private suspend fun readNetworkSettings(revision: Long = 1L): NativeNetworkSettingsState = try {
        val loaded = settingsStorage.loadNetworkSettings()
        NativeNetworkSettingsState(settings = loaded.settings, fingerprint = loaded.fingerprint,
            loaded = true, loading = false, revision = revision)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NativeNetworkSettingsState(revision = revision, loaded = false, loading = false, loadFailed = true)
    }

    private suspend fun readLaboratorySettings(): LoadedLaboratory = try {
        val settings = graph.laboratory.load()
        LoadedLaboratory(settings, graph.laboratory.fingerprint(), loaded = true, failed = false)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        LoadedLaboratory(LaboratorySettings.DEFAULT, null, loaded = false, failed = true)
    }

    private data class LoadedLaboratory(val settings: LaboratorySettings, val fingerprint: String?,
        val loaded: Boolean, val failed: Boolean)

    fun reloadLaboratorySettings() = action {
        mutable.value = mutable.value.copy(laboratorySaving = true, laboratoryError = false)
        try {
            val loaded = readLaboratorySettings()
            mutable.value = mutable.value.copy(laboratorySettings = loaded.settings,
                laboratoryFingerprint = loaded.fingerprint, laboratoryLoaded = loaded.loaded,
                laboratoryError = loaded.failed)
        } finally { mutable.value = mutable.value.copy(laboratorySaving = false) }
    }

    fun saveLaboratorySettings(settings: LaboratorySettings) = action {
        mutable.value = mutable.value.copy(laboratorySaving = true, laboratoryError = false)
        try {
            val saved = graph.laboratory.save(settings, mutable.value.laboratoryFingerprint)
            mutable.value = mutable.value.copy(laboratorySettings = saved,
                laboratoryFingerprint = graph.laboratory.fingerprint(), laboratoryLoaded = true,
                notice = R.string.native_laboratory_saved)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(laboratoryError = true) }
        finally { mutable.value = mutable.value.copy(laboratorySaving = false) }
    }

    private suspend fun readRequestLimits(revision: Long = 1L): NativeRequestLimitsState = try {
        val loaded = settingsStorage.loadRequestLimits()
        NativeRequestLimitsState(limits = loaded.limits, fingerprint = loaded.fingerprint,
            loaded = true, loading = false, revision = revision)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NativeRequestLimitsState(revision = revision, loaded = false, loading = false, loadFailed = true)
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
        saved.remove<String>("native_rules_section")
        saved.remove<String>("native_settings_section")
        saved.remove<String>("native_logs_origin")
        saved.remove<String>("native_files_origin")
        graph.runtime.selectChat(null)
        saved.remove<ArrayList<String>>("native_ref")
        saved.remove<String>("native_page")
        clearProofreadingQuery()
        cursors.clear(); cursors.add(0)
        mutable.value = mutable.value.copy(project = null, items = emptyList(), hasNext = false, hasPrevious = false,
            page = NativePage.AGENT, rulesSection = NativeRuleSection.GLOSSARY,
            settingsSection = NativeSettingsSection.BASIC,
            proofreadingQuery = TextItemQuery(), itemStatistics = null, matchingItems = null, matchingStatistics = null, qualityView = null, qualityInvalidated = false, qualityProgress = null,
            selectingItems = false, selectedItems = emptySet(), manualBatch = null, manualBatchConflict = false,
            agentMessages = emptyList(), approval = null, agentResults = emptyList(), itemProposals = emptyList(), policyProposals = emptyList(), pdfPageProposals = emptyList(), assetImportProposals = emptyList(), recentRuns = emptyList(), runLoadIncomplete = false,
            agentContextStatus = null,
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
                matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics, itemStatistics = page.statistics,
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
            matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false)
    }

    fun clearQuality() = action {
        val state = mutable.value
        val ref = state.project?.ref ?: return@action
        if (state.editor != null || state.manualBatch != null || state.selectingItems) return@action
        val page = readStandardProofreadingPage(ref, state.proofreadingQuery, 0)
        cursors.clear(); cursors.add(0)
        mutable.value = mutable.value.copy(qualityView = null, qualityInvalidated = false, qualityProgress = null,
            project = page.summary, items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) },
            itemStatistics = page.statistics, matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics,
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
            matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false,
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
            mutable.value = mutable.value.copy(manualBatch = batch, manualBatchConflict = false, manualBatchQuery = false)
        }
    }
    fun prepareQueryBatch(change: TextBatchChange) = action {
        val current = mutable.value
        val project = current.project ?: return@action
        if (current.editor != null || current.manualBatch != null ||
            current.policyDraft?.dirty == true || current.policyLoadIncomplete || current.runLoadIncomplete) return@action
        val port = projects as? QueryManualBatchPort ?: return@action
        val quality = current.qualityView
        val batch = if (quality != null) {
            if (!current.qualityCurrent) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            port.prepareQualityBatch(commandId(), project.ref, project.revision, quality, change)
        } else port.prepareQueryBatch(commandId(), project.ref, project.revision, current.proofreadingQuery, change)
        if (batch == null) { mutable.value = mutable.value.copy(notice = R.string.native_batch_no_changes); return@action }
        val digest = manualBatchFiles.save(batch)
        saved.remove<ArrayList<String>>("manual_batch")
        saved["manual_batch_disk"] = digest
        saved["manual_batch_conflict"] = false
        mutable.value = mutable.value.copy(manualBatch = batch, manualBatchConflict = false, manualBatchQuery = true)
    }

    fun dismissManualBatch(expected: ManualTextBatch) {
        if (mutable.value.busy || mutable.value.manualBatch?.digest() != expected.digest()) return
        saved.remove<ArrayList<String>>("manual_batch"); saved.remove<Boolean>("manual_batch_conflict")
        val disk = saved.remove<String>("manual_batch_disk")
        if (disk != null) viewModelScope.launch { manualBatchFiles.remove(disk) }
        mutable.value = mutable.value.copy(manualBatch = null, manualBatchConflict = false, manualBatchQuery = false)
    }
    internal fun prepareQueryReplacement(options: TextReplacementOptions, expected: NativeQueryReplacementScope) =
        prepareQueryTextChange(options, null, expected)
    internal fun prepareQueryNameAssignment(names: List<String>, expected: NativeQueryReplacementScope) =
        prepareQueryTextChange(null, names.toList(), expected)
    private fun prepareQueryTextChange(options: TextReplacementOptions?, names: List<String>?, expected: NativeQueryReplacementScope) = action {
        val current = mutable.value
        val project = current.project ?: return@action
        if (project.ref != expected.ref || project.revision != expected.revision || current.proofreadingQuery != expected.query ||
            current.qualityView !== expected.quality) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        if (current.editor != null || current.manualBatch != null || current.selectingItems ||
            current.policyDraft?.dirty == true || current.policyLoadIncomplete || current.runLoadIncomplete) return@action
        val quality = current.qualityView
        if (quality != null && !current.qualityCurrent) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        val port = projects as? QueryManualBatchPort ?: return@action
        val batch = if (names != null) port.prepareNameAssignmentBatch(commandId(), project.ref, project.revision,
            current.proofreadingQuery, names, quality)
        else port.prepareReplacementBatch(commandId(), project.ref, project.revision,
            current.proofreadingQuery, requireNotNull(options), quality)
        if (batch == null) { mutable.value = mutable.value.copy(notice = R.string.native_batch_no_changes); return@action }
        val digest = manualBatchFiles.save(batch)
        saved.remove<ArrayList<String>>("manual_batch")
        saved["manual_batch_disk"] = digest; saved["manual_batch_conflict"] = false
        mutable.value = mutable.value.copy(manualBatch = batch, manualBatchConflict = false, manualBatchQuery = true)
    }
    fun confirmManualBatch(expected: ManualTextBatch) = action {
        val batch = mutable.value.manualBatch ?: return@action
        if (batch.commandId != expected.commandId || batch.digest() != expected.digest()) return@action
        if (mutable.value.manualBatchConflict || mutable.value.project?.ref != batch.ref) return@action
        val affectedIds = withContext(Dispatchers.Default) { batch.targets.mapTo(HashSet()) { it.id } }
        val receipt = projects.editBatch(batch)
        if (receipt.status != ManualEditStatus.COMMITTED) {
            saved["manual_batch_conflict"] = true
            mutable.value = mutable.value.copy(manualBatchConflict = true, error = R.string.native_conflict)
            return@action
        }
        saved.remove<ArrayList<String>>("manual_batch"); saved.remove<Boolean>("manual_batch_conflict")
        saved.remove<String>("manual_batch_disk")?.let { digest -> viewModelScope.launch { manualBatchFiles.remove(digest) } }
        val qualityWasActive = mutable.value.qualityView != null
        val applyPreview = (mutable.value.project?.revision ?: Long.MAX_VALUE) <= receipt.revision
        val previews = mutable.value.items.map { item ->
            if (batch.replacement == null && applyPreview && item.id in affectedIds) ItemPreview(item.id, item.row, item.source,
                if (batch.change.clearsTranslation) "" else item.translation, batch.change.status, item.hasTranslatableSource, item.assetId) else item
        }
        mutable.value = mutable.value.copy(manualBatch = null, manualBatchConflict = false, manualBatchQuery = false,
            selectingItems = false, selectedItems = emptySet(), items = if (batch.replacement == null) previews else emptyList(),
            project = mutable.value.project?.let { it.copy(revision = maxOf(it.revision, receipt.revision)) },
            itemStatistics = null, matchingItems = null, matchingStatistics = null,
            qualityInvalidated = qualityWasActive || mutable.value.qualityInvalidated, notice = R.string.native_batch_done)
        refreshCleanTextPolicy(batch.ref)
        if (previews.isNotEmpty()) {
            try {
                val page = projects.readItemWindow(batch.ref, mutable.value.proofreadingQuery, TextItemWindow(previews.map { it.id }))
                mutable.value = mutable.value.copy(project = page.summary, items = page.items.map { ItemPreview.of(it) },
                    itemStatistics = page.statistics, matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics)
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

    fun undoEditor() = restoreEditorHistory(false)
    fun redoEditor() = restoreEditorHistory(true)
    private fun restoreEditorHistory(forward: Boolean) {
        val current = mutable.value
        val draft = current.editor ?: return
        if (current.busy || current.nameEdit != null || draft.conflict || current.project?.ref != draft.ref) return
        draftHistory.restore(draft, forward)?.let { setDraft(it, recordHistory = false) }
    }

    fun editText(text: String) {
        if (mutable.value.busy) return
        if (text.length > TextProjectLimits().maxItemChars) { mutable.value = mutable.value.copy(error = R.string.native_limit); return }
        mutable.value.editor?.let { setDraft(it.changed(text = text, status = TextItemStatus.PROCESSED)) }
    }
    fun editStatus(status: TextItemStatus) {
        if (!mutable.value.busy) mutable.value.editor?.let { setDraft(it.changed(status = status)) }
    }

    fun beginNameEdit() {
        val state = mutable.value
        val draft = state.editor ?: return
        if (state.busy || state.nameEdit != null || nameProjects == null || draft.conflict ||
            state.project?.ref != draft.ref) return
        val names = draft.item.metadata
        mutable.value = state.copy(nameEdit = NativeNameEditDraft(
            draft.ref, draft.item.id, draft.revision, draft.fingerprint,
            names.nameSource.orEmpty().joinToString("\n"),
            names.nameTranslation.orEmpty().joinToString("\n"),
        ))
    }

    fun dismissNameEdit() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(nameEdit = null)
    }

    fun saveNameEdit(sourceText: String, translationText: String) = action {
        val names = nameProjects ?: return@action
        val draft = mutable.value.nameEdit ?: return@action
        val editor = mutable.value.editor ?: return@action
        if (editor.ref != draft.ref || editor.item.id != draft.itemId) return@action
        val source = if (sourceText == draft.sourceText) null else parseNameLines(sourceText)
        val translation = parseNameLines(translationText)
        if (source == null && translation == parseNameLines(draft.translationText)) {
            mutable.value = mutable.value.copy(nameEdit = null)
            return@action
        }
        try {
            validateNames(source, translation)
        } catch (_: TextProjectException) {
            mutable.value = mutable.value.copy(error = R.string.native_names_invalid)
            return@action
        }
        val receipt = names.editName(ManualNameEdit(commandId(), draft.ref, draft.itemId,
            draft.revision, draft.fingerprint, translation, source))
        if (receipt.status != ManualEditStatus.COMMITTED) {
            mutable.value = mutable.value.copy(nameEdit = null, error = R.string.native_conflict)
            return@action
        }
        val details = projects.item(draft.ref, draft.itemId)
            ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
        val current = mutable.value.editor
        if (current != null && current.ref == editor.ref && current.item.id == editor.item.id) {
            setDraft(NativeEditDraft(draft.ref, details.item, details.revision, details.item.fingerprint,
                current.text, current.status, commandId()))
        }
        mutable.value = mutable.value.copy(nameEdit = null, notice = R.string.native_names_saved)
    }

    private fun parseNameLines(text: String): List<String> =
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    private fun validateNames(source: List<String>?, translation: List<String>) {
        val all = (source ?: emptyList()) + translation
        if (translation.size > ManualNameEdit.MAX_NAMES || (source?.size ?: 0) > ManualNameEdit.MAX_NAMES ||
            all.sumOf { it.length.toLong() } > ManualNameEdit.MAX_TOTAL_NAME_CHARS ||
            all.any { it.length > ManualNameEdit.MAX_NAME_CHARS || '\u0000' in it }) {
            throw TextProjectException(TextProjectException.Reason.INVALID_TEXT)
        }
    }

    fun closeEditor() { if (!mutable.value.busy) { clearDraft(); mutable.value = mutable.value.copy(editor = null, nameEdit = null) } }

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
                if (item.id == draft.item.id) ItemPreview(item.id, item.row, item.source, draft.text.take(240), draft.status, item.hasTranslatableSource, item.assetId) else item
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
                    items = page.items.map { ItemPreview.of(it) }, itemStatistics = page.statistics, matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics)
            }
        } else {
            setDraft(NativeEditDraft(draft.ref, draft.item, draft.revision, draft.fingerprint, draft.text,
                draft.status, draft.command, conflict = true))
            mutable.value = mutable.value.copy(error = R.string.native_conflict)
        }
    }

    /** Bind before showing the system picker; its late result never targets a different project. */
    /**
     * Exports enabled assets into a private cache directory and hands only those files to the
     * system chooser. The model and workspace artifact paths never become share inputs.
     */
    fun shareProjectFiles(onReady: (List<File>) -> Unit) = action {
        val project = mutable.value.project ?: return@action
        if (mutable.value.editor != null || mutable.value.manualBatch != null || mutable.value.selectingItems) return@action
        val settings = project.settings ?: ProjectSettings()
        val options = FormatWriteOptions(bilingual = false, deduplicateSame = settings.bilingualDeduplicateSame,
            writeTranslatedNames = settings.writeTranslatedNames, targetLanguage = settings.targetLanguage)
        val root = File(getApplication<Application>().cacheDir, "share")
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw java.io.IOException("Share directory unavailable")
        root.listFiles()?.filter { it != directory }?.forEach { it.deleteRecursively() }
        val enabled = project.assets.filter { it.enabled }.take(NativeFileDelivery.MAX_FILES)
        if (enabled.isEmpty()) throw java.io.IOException("No enabled assets to share")
        val used = HashSet<String>()
        val files = withContext(Dispatchers.IO) {
            enabled.map { asset ->
                val base = asset.path.value.substringAfterLast('/').map { character ->
                    if (character == '/' || character == '\\' || character.isISOControl()) '_' else character
                }.joinToString("").take(120).ifBlank { "asset-${asset.id}" }
                var name = base
                var suffix = 1
                while (!used.add(name)) {
                    val dot = base.lastIndexOf('.')
                    name = if (dot > 0) base.substring(0, dot) + "-${suffix++}" + base.substring(dot)
                    else base + "-${suffix++}"
                }
                val file = File(directory, name)
                graph.formatProjects.exportAsset(project.ref, asset.id, options) { FileOutputStream(file) }
                if (projects.open(project.ref).revision != project.revision)
                    throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
                file
            }
        }
        onReady(files)
        mutable.value = mutable.value.copy(notice = R.string.native_exported)
    }

    fun prepareExport(kind: NativeExport): String? {
        val project = mutable.value.project ?: return null
        if (mutable.value.busy) return null
        saved["native_export"] = encodeRef(project.ref).apply {
            add(kind.name); add(project.revision.toString()); add((project.settings?.bilingualDeduplicateSame ?: true).toString())
        }
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
        if (pending.size != 6) return
        queuedAction {
            val ref = decodeRef(pending)
            if (mutable.value.project?.ref != ref || projects.open(ref).revision != pending[4].toLong())
                throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            val destination = { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException() }
            when (NativeExport.valueOf(pending[3])) {
                NativeExport.PROJECT -> projects.saveProject(ref, destination)
                NativeExport.TRANSLATION -> projects.exportTxt(ref, destination)
                NativeExport.BILINGUAL -> projects.exportBilingual(ref, pending[5].toBooleanStrict(), destination)
                NativeExport.ORIGINAL -> projects.exportOriginal(ref, destination)
            }
            mutable.value = mutable.value.copy(notice = R.string.native_exported)
        }
    }

    /** Bind before showing the system picker; restore always creates a new project identity. */
    fun prepareBackupExport(): String? {
        val project = mutable.value.project ?: return null
        val state = mutable.value
        if (state.busy || state.editor != null || state.manualBatch != null || state.selectingItems ||
            state.runLoadIncomplete || state.policyDraft?.dirty == true || graph.runtime.snapshot().activeRun != null) return null
        saved["native_backup_export"] = encodeRef(project.ref).apply { add(project.revision.toString()) }
        val name = project.name.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("").take(100).ifBlank { "project" }
        return "$name.lntbackup.zip"
    }

    fun completeBackupExport(uri: Uri?) {
        val pending = saved.remove<ArrayList<String>>("native_backup_export")?.toList()
        if (uri == null || pending == null || pending.size != 4) return
        queuedAction {
            val ref = decodeRef(pending)
            if (mutable.value.project?.ref != ref || projects.open(ref).revision != pending[3].toLong())
                throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            graph.backups.export(ref) { resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException() }
            mutable.value = mutable.value.copy(notice = R.string.native_backup_exported)
        }
    }

    fun prepareBackupImport(): Boolean {
        val state = mutable.value
        if (state.busy || state.editor != null || state.manualBatch != null || state.selectingItems ||
            state.runLoadIncomplete || state.policyDraft?.dirty == true || graph.runtime.snapshot().activeRun != null) return false
        saved["native_backup_import"] = arrayListOf(commandId().value)
        return true
    }

    fun importBackup(uri: Uri?) {
        val intent = saved.remove<ArrayList<String>>("native_backup_import")?.toList()
        if (uri == null || intent == null || intent.size != 1) return
        queuedAction {
            val restored = graph.backups.restore(CommandId(intent.single())) {
                resolver.openInputStream(uri) ?: throw java.io.IOException()
            }
            mutable.value = mutable.value.copy(projects = projects.registeredProjects())
            openInternal(restored.ref)
            mutable.value = mutable.value.copy(notice = R.string.native_backup_restored)
        }
    }

    fun prepareProjectSetExport(): String? {
        val state = mutable.value
        if (state.busy || state.editor != null || state.manualBatch != null || state.selectingItems ||
            state.runLoadIncomplete || state.policyDraft?.dirty == true || graph.runtime.snapshot().activeRun != null) return null
        saved["native_project_set_export"] = arrayListOf(commandId().value)
        return "projects-backup.zip"
    }

    fun completeProjectSetExport(uri: Uri?) {
        saved.remove<ArrayList<String>>("native_project_set_export") ?: return
        if (uri == null) return
        queuedAction {
            val summary = graph.projectSetBackups.export {
                resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException()
            }
            mutable.value = mutable.value.copy(notice = R.string.native_project_set_backup_exported)
            require(summary.projectCount > 0)
        }
    }

    fun prepareProjectSetImport(): Boolean {
        val state = mutable.value
        if (state.busy || state.editor != null || state.manualBatch != null || state.selectingItems ||
            state.runLoadIncomplete || state.policyDraft?.dirty == true || graph.runtime.snapshot().activeRun != null) return false
        saved["native_project_set_import"] = arrayListOf(commandId().value)
        return true
    }

    fun importProjectSet(uri: Uri?) {
        val intent = saved.remove<ArrayList<String>>("native_project_set_import")?.toList()
        if (uri == null || intent == null || intent.size != 1) return
        queuedAction {
            val result = graph.projectSetBackups.restore(CommandId(intent.single())) {
                resolver.openInputStream(uri) ?: throw java.io.IOException()
            }
            mutable.value = mutable.value.copy(projects = projects.registeredProjects(),
                notice = R.string.native_project_set_backup_restored)
            require(result.importedCount > 0)
        }
    }

    private suspend fun openInternal(ref: LocalProjectRef) {
        if (mutable.value.manualBatch != null) return
        val query = restoreProofreadingQuery(ref)
        val page = readStandardProofreadingPage(ref, query, 0)
        val summary = page.summary
        if (graph.backgroundAgents.state.value?.phase?.active != true) agentPreviews.clear()
        if (graph.runtime.snapshot().activeRun == null) graph.runtime.selectChat(null)
        else if (backgroundTranslations.state.value?.takeIf { it.phase.active }?.request?.ref != ref &&
            graph.backgroundAgents.state.value?.takeIf { it.phase.active }?.request?.ref != ref)
            throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        cursors.clear(); cursors.add(0)
        val same = restoreRef() == ref
        val selectedPage = if (same) runCatching { NativePage.valueOf(saved["native_page"] ?: "AGENT") }.getOrDefault(NativePage.AGENT) else NativePage.AGENT
        val selectedSection = if (same) runCatching {
            NativeRuleSection.valueOf(saved["native_rules_section"] ?: NativeRuleSection.GLOSSARY.name)
        }.getOrDefault(NativeRuleSection.GLOSSARY) else NativeRuleSection.GLOSSARY
        val selectedSettingsSection = if (same) runCatching {
            NativeSettingsSection.valueOf(saved["native_settings_section"] ?: NativeSettingsSection.BASIC.name)
        }.getOrDefault(NativeSettingsSection.BASIC) else NativeSettingsSection.BASIC
        if (!same) {
            clearDraft()
            saved.remove<String>("native_rules_section")
            saved.remove<String>("native_settings_section")
            saved.remove<String>("native_logs_origin")
            saved.remove<String>("native_files_origin")
        }
        saved["native_ref"] = encodeRef(ref)
        saveProofreadingQuery(ref, query)
        val diskToken = saved.get<String>("manual_batch_disk")
        val diskBatch = diskToken?.let { manualBatchFiles.load(it) }
        if (diskToken != null && diskBatch == null) {
            saved.remove<String>("manual_batch_disk")
            mutable.value = mutable.value.copy(notice = R.string.native_batch_restore_failed)
        }
        val batch = (diskBatch ?: decodeManualBatch(saved.get<ArrayList<String>>("manual_batch")))?.takeIf {
            it.ref == ref && graph.runtime.snapshot().activeRun == null }
        if (batch != null) saved["native_page"] = NativePage.PROOFREADING.name
        mutable.value = mutable.value.copy(project = summary, page = if (batch != null) NativePage.PROOFREADING else selectedPage,
            rulesSection = if (batch != null) NativeRuleSection.GLOSSARY else selectedSection,
            settingsSection = if (batch != null) NativeSettingsSection.BASIC else selectedSettingsSection,
            editor = null, editorQuality = null, editorReplacement = null, translationProgress = null,
            policyDraft = null, policyLoadIncomplete = true, policyInvalidRuleId = null,
            selectingItems = false, selectedItems = emptySet(), manualBatch = batch, manualBatchQuery = diskBatch != null,
            manualBatchConflict = batch != null && saved.get<Boolean>("manual_batch_conflict") == true,
            proofreadingQuery = query, qualityView = null, qualityInvalidated = false, qualityProgress = null, itemStatistics = page.statistics, matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics,
            items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) }, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false,
            agentMessages = emptyList(), approval = null, agentResults = emptyList(), itemProposals = emptyList(), policyProposals = emptyList(), pdfPageProposals = emptyList(), assetImportProposals = emptyList(), recentRuns = emptyList(),
            runLoadIncomplete = false, agentMessageDetail = null, agentChatId = null, agentInput = "", agentContextStatus = null)
        if (selectedPage == NativePage.SETTINGS) ensureSettingsLoaded()
        loadAgent(ref)
        loadTextPolicy(ref, restore = true)
    }

    private suspend fun loadPage(ref: LocalProjectRef, after: Long) {
        val page = readProofreadingPage(ref, mutable.value.proofreadingQuery, after)
        mutable.value = mutable.value.copy(project = page.summary, items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) },
            selectingItems = false, selectedItems = emptySet(),
            itemStatistics = page.statistics, matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics,
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
                saved.get<String>("proof_query_field")?.let(TextSearchField::valueOf) ?: TextSearchField.BOTH,
                saved.get<Long>("proof_query_asset"))
        }.getOrDefault(TextItemQuery())
    }

    private fun saveProofreadingQuery(ref: LocalProjectRef, query: TextItemQuery) {
        saved["proof_query_ref"] = encodeRef(ref)
        saved["proof_query_text"] = query.text
        saved["proof_query_field"] = query.field.name
        if (query.assetId == null) saved.remove<Long>("proof_query_asset") else saved["proof_query_asset"] = query.assetId
        val status = query.status
        if (status == null) saved.remove<String>("proof_query_status") else saved["proof_query_status"] = status.name
    }

    private fun clearProofreadingQuery() {
        for (key in listOf("proof_query_ref", "proof_query_text", "proof_query_field", "proof_query_status", "proof_query_asset")) saved.remove<Any>(key)
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

    private fun setDraft(draft: NativeEditDraft, recordHistory: Boolean = true) {
        if (recordHistory) draftHistory.record(draft)
        saved["draft_item"] = draft.item.id.value; saved["draft_revision"] = draft.revision
        saved["draft_fingerprint"] = draft.fingerprint.sha256; saved["draft_text"] = draft.text
        saved["draft_status"] = draft.status.name; saved["draft_command"] = draft.command.value
        mutable.value = mutable.value.copy(editor = draft, editorQuality = null, editorReplacement = null,
            editorCanUndo = draftHistory.canUndo, editorCanRedo = draftHistory.canRedo)
    }
    private fun clearDraft() {
        draftHistory.clear()
        mutable.value = mutable.value.copy(editorQuality = null, editorReplacement = null,
            editorCanUndo = false, editorCanRedo = false)
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
            catch (error: Exception) { recordActionFailure(error) }
            finally { if (id == actionId) { mutable.value = mutable.value.copy(busy = false); job = null } }
        }
    }

    /**
     * Action wrapper for a dialog that must await a durable result. The deferred completes only
     * after the busy flag is released, so an immediate post-action reload can read fresh state.
     */
    private fun <T> queuedActionResult(block: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        viewModelScope.launch {
            try {
                state.first { !it.busy }
                if (mutable.value.busy) {
                    result.completeExceptionally(RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY))
                    return@launch
                }
                val id = ++actionId
                job = currentCoroutineContext()[Job]
                mutable.value = mutable.value.copy(busy = true, error = null, notice = null)
                var value: T? = null
                var failure: Throwable? = null
                try {
                    value = block()
                } catch (cancelled: CancellationException) {
                    mutable.value = mutable.value.copy(notice = R.string.native_cancelled)
                    failure = cancelled
                } catch (error: Exception) {
                    recordActionFailure(error)
                    failure = error
                } finally {
                    if (id == actionId) {
                        mutable.value = mutable.value.copy(busy = false)
                        job = null
                    }
                }
                val failed = failure
                when {
                    failed is CancellationException -> {
                        result.cancel(failed)
                        throw failed
                    }
                    failed != null -> result.completeExceptionally(failed)
                    else -> {
                        @Suppress("UNCHECKED_CAST")
                        result.complete(value as T)
                    }
                }
            } catch (cancelled: CancellationException) {
                result.cancel(cancelled)
                throw cancelled
            }
        }
        return result
    }

    private suspend fun recordActionFailure(error: Exception) {
        runCatching {
            graph.diagnostics.append(
                com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticLevel.ERROR,
                "action", error.javaClass.simpleName)
        }
        mutable.value = mutable.value.copy(
            qualityInvalidated = mutable.value.qualityInvalidated || mutable.value.qualityView != null &&
                error is TextProjectException && error.reason == TextProjectException.Reason.STALE_PAGE,
            policyInvalidRuleId = (error as? InvalidTextRule)?.ruleId ?: mutable.value.policyInvalidRuleId,
            error = when (error) {
                is TextProjectException -> when (error.reason) {
                    TextProjectException.Reason.LIMIT_EXCEEDED -> R.string.native_limit
                    TextProjectException.Reason.INVALID_TEXT -> R.string.native_encoding_error
                    TextProjectException.Reason.UNSUPPORTED_VERSION -> R.string.native_version_error
                    TextProjectException.Reason.INVALID_FORMAT, TextProjectException.Reason.CORRUPT_PROJECT -> R.string.native_format_error
                    TextProjectException.Reason.STALE_PAGE, TextProjectException.Reason.COMMAND_CONFLICT -> R.string.native_conflict
                    else -> R.string.native_io_error
                }
                is InvalidAgentHistory -> R.string.native_agent_history_error
                is ForegroundTranslationStartFailed, is ForegroundAgentStartFailed -> R.string.native_background_unavailable
                is InvalidTextRule -> R.string.native_policy_invalid
                is InvalidModelSettings, is InvalidModelProfile -> R.string.native_model_settings_error
                is AgentApprovalUnavailable -> R.string.native_agent_load_error
                is TranslationTaskNeedsReview -> R.string.native_tasks_review
                is TranslationResponseNotStored -> R.string.native_tasks_response_not_saved
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
                is InvalidModelNetworkSettings, is ModelNetworkSettingsUnavailable -> R.string.native_settings_network_load_failed
                is RuntimeUnavailable -> R.string.native_busy_error
                is SecurityException -> R.string.native_permission_error
                else -> R.string.native_io_error
            },
        )
    }

    companion object { private const val PAGE_SIZE = 40 }
}
