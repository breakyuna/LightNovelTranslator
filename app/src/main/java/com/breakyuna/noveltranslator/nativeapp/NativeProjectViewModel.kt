package com.breakyuna.noveltranslator.nativeapp

import kotlinx.serialization.json.long

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.legacy.LegacyExportLimitException
import com.breakyuna.noveltranslator.legacy.LegacyExportProgress
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
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceHandle
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceRequest
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceSnapshot
import com.breakyuna.noveltranslator.nativecore.engine.ModelProbeFailure
import com.breakyuna.noveltranslator.nativecore.engine.ModelProbeResult
import com.breakyuna.noveltranslator.nativecore.engine.TranslationFailureAdjudicationResult
import com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused
import com.breakyuna.noveltranslator.nativecore.engine.UsageCostAggregator
import com.breakyuna.noveltranslator.nativecore.engine.UsageCostSummary
import com.breakyuna.noveltranslator.nativecore.formats.PdfFormatException
import com.breakyuna.noveltranslator.nativecore.formats.rgss.RgssArchiveException
import com.breakyuna.noveltranslator.nativecore.formats.rgss.RgssFormatException
import com.breakyuna.noveltranslator.nativecore.formats.rgss.RgssProjectService
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveExportOptions
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelSettingsStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactRecord
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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

enum class NativePage { AGENT, WORKBENCH, PROOFREADING, MORE, RULES, PROMPTS, SETTINGS, FILES, LOGS, MODELS }

/** Leading-dot TXT seed used only to create an EPUB-first project; user file names cannot collide. */
private const val NATIVE_IMPORT_SEED_PATH = ".lnt-import-seed.txt"
private val PROJECT_IMPORT_BINDING_KEYS = listOf(
    "native_import_digest",
    "native_import_command",
    "native_import_ref",
)

/** Primary destinations are shared by the bottom bar and navigation rail. */
internal val NativeNavigationPages = listOf(NativePage.AGENT, NativePage.WORKBENCH, NativePage.PROOFREADING, NativePage.RULES, NativePage.MORE)
enum class NativeExport { PROJECT, TRANSLATION, BILINGUAL, ORIGINAL }

/** Precise, path-free PDF failure text for imports, exports and page previews. */
internal fun pdfFormatErrorLabel(reason: PdfFormatException.Reason): Int = when (reason) {
    PdfFormatException.Reason.CORRUPT -> R.string.native_pdf_error_corrupt
    PdfFormatException.Reason.ENCRYPTED -> R.string.native_pdf_error_encrypted
    PdfFormatException.Reason.RESOURCES -> R.string.native_pdf_error_resources
    PdfFormatException.Reason.LIMIT -> R.string.native_pdf_error_limit
    PdfFormatException.Reason.BINDING -> R.string.native_pdf_error_binding
    PdfFormatException.Reason.DUPLICATE -> R.string.native_pdf_error_duplicate
    PdfFormatException.Reason.MISSING_TRANSLATION -> R.string.native_pdf_error_missing_translation
    PdfFormatException.Reason.EMPTY_OUTPUT -> R.string.native_pdf_error_empty_output
    PdfFormatException.Reason.EXTRACT -> R.string.native_pdf_error_extract
    PdfFormatException.Reason.RENDER -> R.string.native_pdf_error_render
    PdfFormatException.Reason.FONT -> R.string.native_pdf_error_font
    PdfFormatException.Reason.UNSUPPORTED -> R.string.native_pdf_error_unsupported
}

/**
 * Maps the adapter's structural PDF warnings to one visible import notice. Warnings are
 * ephemeral list metadata; no path or adapter message is displayed.
 */
internal fun pdfImportNotice(warnings: List<FormatWarning>): Int? {
    val codes = warnings.mapTo(HashSet()) { it.code }
    val forms = "pdf.forms_protected" in codes
    val attachments = "pdf.attachments_protected" in codes
    return when {
        forms && attachments -> R.string.native_pdf_import_warning_forms_attachments
        attachments -> R.string.native_pdf_import_warning_attachments
        forms -> R.string.native_pdf_import_warning_forms
        "pdf.no_text_layer" in codes -> R.string.native_pdf_import_warning_no_text_layer
        "pdf.empty_pages" in codes -> R.string.native_pdf_import_warning_empty_pages
        else -> null
    }
}

/**
 * EPUB warnings that affect translation scope or layout fidelity. They are shown after import;
 * no path or body is displayed.
 */
internal fun epubImportNotice(warnings: List<FormatWarning>): Int? {
    val codes = warnings.mapTo(HashSet()) { it.code }
    return when {
        "epub.fixed_layout" in codes -> R.string.native_epub_import_warning_fixed_layout
        "epub.spine_non_linear" in codes -> R.string.native_epub_import_warning_non_linear
        "epub.unknown_entity" in codes -> R.string.native_epub_import_warning_unknown_entity
        else -> null
    }
}

/** One import notice for the active format families; PDF remains available for old projects. */
internal fun formatImportNotice(warnings: List<FormatWarning>): Int? =
    epubImportNotice(warnings) ?: pdfImportNotice(warnings)

/** One minimal generation probe asks for at most this many output tokens. */
internal const val NATIVE_MODEL_PROBE_MAX_OUTPUT_TOKENS = 16

/** Display-only provenance; a stored snapshot itself is always an endpoint result. */
enum class NativeModelCatalogViewSource { LIVE, CACHED, EMPTY_OVERRIDE }

/**
 * Price evidence for the explicit generation confirmation. A missing table or model
 * is reported as unknown instead of inventing a zero price.
 */
class NativeProbeBillingHint(
    val priced: Boolean,
    val currency: String? = null,
    val outputPricePerMillion: String? = null,
) {
    init {
        require(if (priced) currency != null && outputPricePerMillion != null
        else currency == null && outputPricePerMillion == null)
    }
}

/** Transient probe output. Preview text is deliberately not retained in UI state. */
sealed interface NativeModelProbeUi {
    data class Metadata(val modelCount: Int, val elapsedMillis: Long) : NativeModelProbeUi
    data class Generation(
        val outputChars: Int,
        val truncated: Boolean,
        val elapsedMillis: Long,
        /** The complete provider receipt is retained; missing components stay unknown. */
        val usage: RequestUsage?,
    ) : NativeModelProbeUi
    data class Failure(val kind: ModelProbeFailure, val retryAfterMillis: Long? = null) : NativeModelProbeUi
    data object Cancelled : NativeModelProbeUi
}

data class NativeModelCatalogUiState(
    val profileId: String? = null,
    val descriptors: List<ModelDescriptor> = emptyList(),
    val source: NativeModelCatalogViewSource? = null,
    val fetchedAtMillis: Long? = null,
    /** True only when this live/cached result is backed by the saved snapshot file. */
    val persisted: Boolean = false,
    val loading: Boolean = false,
    val error: Int? = null,
    val metadataProbe: NativeModelProbeUi? = null,
    val generationProbe: NativeModelProbeUi? = null,
    val generationRunning: Boolean = false,
    val generationConfirmation: Boolean = false,
    val billing: NativeProbeBillingHint? = null,
) {
    fun descriptor(id: String): ModelDescriptor? = descriptors.firstOrNull { it.id == id }
}

/** Commands exposed through the UI state so the existing page caller need not change. */
class NativeModelCatalogActions(
    val loadCached: (String) -> Unit = {},
    val refresh: (String) -> Unit = {},
    val probeMetadata: (String) -> Unit = {},
    val requestGeneration: (String) -> Unit = {},
    val confirmGeneration: (String) -> Unit = {},
    val dismissConfirmation: () -> Unit = {},
    val cancel: () -> Unit = {},
    val clear: () -> Unit = {},
)

internal fun nativeModelCatalogState(
    profileId: String,
    snapshot: ModelCatalogSnapshot?,
    live: Boolean,
    previous: NativeModelCatalogUiState = NativeModelCatalogUiState(),
    persisted: Boolean = true,
): NativeModelCatalogUiState = previous.copy(
    profileId = profileId,
    descriptors = snapshot?.descriptors.orEmpty(),
    source = when {
        snapshot == null -> null
        snapshot.descriptors.isEmpty() && persisted -> NativeModelCatalogViewSource.EMPTY_OVERRIDE
        live -> NativeModelCatalogViewSource.LIVE
        else -> NativeModelCatalogViewSource.CACHED
    },
    fetchedAtMillis = snapshot?.fetchedAtMillis,
    persisted = snapshot != null && persisted,
    loading = false,
    error = null,
    metadataProbe = null,
    generationProbe = null,
    generationRunning = false,
    generationConfirmation = false,
    billing = null,
)

internal fun nativeModelCatalogLoading(
    profileId: String,
    previous: NativeModelCatalogUiState,
): NativeModelCatalogUiState =
    if (previous.profileId == profileId) previous.copy(loading = true, error = null,
        metadataProbe = null, generationProbe = null, generationConfirmation = false, billing = null)
    else NativeModelCatalogUiState(profileId = profileId, loading = true)

/** A failed request never erases the last successful catalog or its timestamp. */
internal fun nativeModelCatalogFailure(
    previous: NativeModelCatalogUiState,
    profileId: String,
    error: Int,
): NativeModelCatalogUiState =
    if (previous.profileId != null && previous.profileId != profileId) previous
    else previous.copy(profileId = profileId, loading = false, error = error,
        generationConfirmation = false, billing = null)

internal enum class NativeModelProbeCommand {
    IGNORE_BUSY,
    IGNORE_STALE,
    IGNORE_CONFIRMATION,
    SHOW_CONFIRMATION,
    RUN_METADATA,
    RUN_GENERATION,
}

internal fun nativeModelProbeCommand(
    state: NativeModelCatalogUiState,
    profileId: String,
    generation: Boolean,
    confirmed: Boolean,
): NativeModelProbeCommand {
    if (state.loading || state.generationRunning) return NativeModelProbeCommand.IGNORE_BUSY
    if (state.profileId != null && state.profileId != profileId) return NativeModelProbeCommand.IGNORE_STALE
    if (!generation) {
        return if (state.generationConfirmation) NativeModelProbeCommand.IGNORE_BUSY
        else NativeModelProbeCommand.RUN_METADATA
    }
    if (confirmed) {
        return if (state.generationConfirmation) NativeModelProbeCommand.RUN_GENERATION
        else NativeModelProbeCommand.IGNORE_CONFIRMATION
    }
    return if (state.generationConfirmation) NativeModelProbeCommand.IGNORE_BUSY
    else NativeModelProbeCommand.SHOW_CONFIRMATION
}

internal fun nativeProbeBillingHint(table: PriceTable?, model: String): NativeProbeBillingHint {
    if (table == null) return NativeProbeBillingHint(false)
    return when (val lookup = table.lookup(model)) {
        is PriceLookup.Matched -> NativeProbeBillingHint(true, table.currency.code,
            lookup.pricing.output.currencyPerMillion().stripTrailingZeros().toPlainString())
        is PriceLookup.Missing -> NativeProbeBillingHint(false)
    }
}

internal fun nativeProbeUi(result: ModelProbeResult): NativeModelProbeUi = when (result) {
    is ModelProbeResult.MetadataSuccess -> NativeModelProbeUi.Metadata(
        result.snapshot.descriptors.size, result.elapsedMillis)
    is ModelProbeResult.GenerationSuccess -> NativeModelProbeUi.Generation(
        outputChars = result.preview.length,
        truncated = result.truncated,
        elapsedMillis = result.elapsedMillis,
        usage = result.usage,
    )
    is ModelProbeResult.Failure -> NativeModelProbeUi.Failure(result.kind, result.retryAfterMillis)
}

internal fun nativeProbeFailureLabel(kind: ModelProbeFailure): Int = when (kind) {
    ModelProbeFailure.NETWORK -> R.string.native_model_probe_network
    ModelProbeFailure.TIMEOUT -> R.string.native_model_probe_timeout
    ModelProbeFailure.RATE_LIMIT -> R.string.native_model_probe_rate_limit
    ModelProbeFailure.SERVER -> R.string.native_model_probe_server
    ModelProbeFailure.AUTHENTICATION -> R.string.native_model_probe_auth
    ModelProbeFailure.PARSING -> R.string.native_model_probe_parsing
    ModelProbeFailure.CONTENT -> R.string.native_model_probe_content
    ModelProbeFailure.EMPTY_RESPONSE -> R.string.native_model_probe_empty
    ModelProbeFailure.CONFIGURATION -> R.string.native_model_probe_configuration
    ModelProbeFailure.STORAGE -> R.string.native_model_probe_storage
    ModelProbeFailure.UNKNOWN -> R.string.native_model_probe_unknown
}

/**
 * RGSS inventory decodes every member into its own copy, so browsing is bounded to avoid an OOM
 * on a phone. The format is frozen; opening a larger archive stays possible through export.
 */
private const val RGSS_INVENTORY_MAX_BYTES = 24L * 1024 * 1024

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
    /** Steps recovered from the private journal for the currently opened revision. */
    val editorHistoryRestored: Int = 0,
    val editorQuality: TextProofreadingResult? = null,
    val editorReplacement: NativeEditorReplacement? = null,
    val nameEdit: NativeNameEditDraft? = null,
    val busy: Boolean = false,
    val foregroundTranslationActive: Boolean = false,
    val foregroundTranslationCanPause: Boolean = false,
    val foregroundTranslationPausing: Boolean = false,
    val foregroundAgentActive: Boolean = false,
    val foregroundWorkspaceActive: Boolean = false,
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
    val agentAttachments: List<PendingAgentAttachment> = emptyList(),
    val agentAttachmentFailures: List<NativeAgentAttachmentFailure> = emptyList(),
    val agentAttachmentBusy: Boolean = false,
    val agentAttachmentProject: LocalProjectRef? = null,
    val agentAttachmentActions: NativeAgentAttachmentActions? = null,
    val agentQueue: AgentInputQueue? = null,
    val agentQueueRevision: Long = 0,
    val agentQueueIncomplete: Boolean = false,
    val agentLoadIncomplete: Boolean = false,
    val recentRuns: List<StoredRun> = emptyList(),
    val queueManagedRuns: Set<String> = emptySet(),
    val queueManagedTasks: Map<String, TranslationQueuedTask> = emptyMap(),
    val runLoadIncomplete: Boolean = false,
    val modelCatalogLoading: Boolean = false,
    val modelCatalog: NativeModelCatalogUiState = NativeModelCatalogUiState(),
    val modelCatalogActions: NativeModelCatalogActions = NativeModelCatalogActions(),
    val oauth: NativeOAuthUiState = NativeOAuthUiState(),
    val oauthActions: NativeOAuthActions = NativeOAuthActions(),
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
    val workspaceChangeProposals: List<WorkspaceChangeProposalSummary> = emptyList(),
    val mixedApproval: MixedApproval? = null,
    val mixedApprovalMembers: List<NativeMixedApprovalMemberView> = emptyList(),
    val mixedApprovalError: Int? = null,
    val workspaceUploadRevision: Int = 0,
    val supportedFormats: Set<ProjectAssetFormat> = emptySet(),
) {
    val foregroundExecutionActive: Boolean
        get() = foregroundTranslationActive || foregroundAgentActive || foregroundWorkspaceActive
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
    val pendingWorkspaceChangeProposal: WorkspaceChangeProposalSummary? get() =
        workspaceChangeProposals.firstOrNull { it.state == WorkspaceChangeProposalState.PENDING }
    val hasAgentApproval: Boolean get() = approval != null || mixedApproval != null ||
        pendingItemProposal != null ||
        pendingPolicyProposal != null || pendingPdfPageProposal != null || pendingAssetImportProposal != null ||
        pendingWorkspaceChangeProposal != null
    val qualityCurrent: Boolean get() {
        val current = project ?: return false
        val index = qualityView?.index ?: return false
        return index.ref == current.ref && index.revision == current.revision && index.query == proofreadingQuery &&
            !qualityInvalidated && !policyLoadIncomplete && policyDraft?.dirty != true &&
            (policyDraft == null || policyDraft.base == index.policyFingerprint && policyDraft.revision <= index.revision)
    }
}

/** Result of one reviewed file/directory import; [failures] contains only safe display names. */
internal data class NativeProjectImportCreation(
    val ref: LocalProjectRef,
    val failures: List<String> = emptyList(),
)

/**
 * UI-facing handle for the application-owned direct skill runner. Starting returns an opaque
 * process token immediately; closing a dialog only detaches its collector. [stop] is the explicit
 * cancellation command, and a missing/stale token is harmless.
 */
internal class NativeSkillExecutionActions(
    val state: kotlinx.coroutines.flow.StateFlow<ForegroundWorkspaceSnapshot?>,
    val start: (NativeSkillExecutionTarget, String, Boolean) -> String?,
    val stop: (String) -> Boolean,
)

/** Small workspace controller. Every database/file action goes through the native port. */
class NativeProjectViewModel internal constructor(
    application: Application, private val saved: SavedStateHandle,
    private val projects: TextProjectPort, private val backgroundTranslations: ForegroundTranslationRunner,
    settingsPort: NativeSettingsPort? = null,
    pdfPageThumbnailSource: PdfPageThumbnailSource? = null,
    proxyCredentialWriter: NativeProxyCredentialWriter? = null,
    usageCostReader: (suspend (UsageLedgerQuery) -> UsageCostSummary)? = null,
) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved,
        (application as TranslatorApplication).nativeGraph.projects, application.nativeGraph.backgroundTranslations,
        NativeStoredSettingsPort(application.nativeGraph.networkSettings, application.nativeGraph.requestLimits))

    private val draftUndoHistory = NativeDraftUndoHistory()
    private val draftUndoStore = NativeDraftUndoStore(application)
    private val graph = (application as TranslatorApplication).nativeGraph
    private val businessProposalState = MutableStateFlow<NativeAgentBusinessProposal?>(null)
    internal val agentBusinessProposal = businessProposalState.asStateFlow()
    internal val agentManagementRequests get() = graph.agentManagementRequests.pending
    internal fun consumeAgentManagementRequest(request: NativeAgentManagementRequest): Boolean =
        graph.agentManagementRequests.consume(request)
    private val agentRecreateState = MutableStateFlow(false)
    internal val agentPreferencesRecreate = agentRecreateState.asStateFlow()
    internal fun consumeAgentPreferencesRecreate(): Boolean {
        val pending = agentRecreateState.value
        agentRecreateState.value = false
        return pending
    }


    private val composerDraftMemory = mutableMapOf<Pair<LocalProjectRef, ChatId>, String>()
    private val composerRevisions = mutableMapOf<Pair<LocalProjectRef, ChatId>, Long>()
    private var agentInspectionVersion = 0L
    private val composerDrafts by lazy {
        NativeAgentComposerDrafts(File(application.filesDir, "native-agent-composer"), viewModelScope) { ref, chat ->
            if (mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
                mutable.value = mutable.value.copy(notice = R.string.native_agent_draft_save_failed)
        }
    }
    private fun saveComposer(ref: LocalProjectRef, chat: ChatId, text: String) {
        composerDraftMemory[ref to chat] = text
        composerRevisions[ref to chat] = (composerRevisions[ref to chat] ?: 0L) + 1
        saved["agent_input_ref"] = encodeRef(ref); saved["agent_input_chat"] = chat.value
        saved["agent_input_text"] = text
        composerDrafts.save(ref, chat, text)
    }

    private val pdfPageThumbnails = NativePdfPageThumbnailLoader(
        pdfPageThumbnailSource ?: PdfPageThumbnailSource { ref, revision, asset, page ->
            graph.formatProjects.renderPdfPage(ref, revision, asset, page)
        },
    )
    private val appSettings = NativeAppSettingsStore(application)
    private val settingsStorage: NativeSettingsPort = settingsPort ?: NativeStoredSettingsPort(
        graph.networkSettings, graph.requestLimits)
    private val proxyCredentials: NativeProxyCredentialWriter =
        proxyCredentialWriter ?: StoreProxyCredentialWriter(graph.proxyCredentials)
    private val usageCostsReader = usageCostReader
    private val proxyCredentialState = MutableStateFlow(NativeProxyCredentialUiState())
    /**
     * Proxy credential commands deliberately live outside the form input/state types: the
     * password is a one-shot argument and never enters UI state, SavedStateHandle or diagnostics.
     */
    internal val proxyCredentialActions = NativeProxyCredentialActions(
        state = proxyCredentialState.asStateFlow(),
        saveNetwork = ::saveNetworkWithProxyCredential,
        clear = ::clearProxyCredential,
    )
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
        execute = null,
        stopExecution = { identity ->
            graph.backgroundWorkspaceExecutions.state.value
                ?.takeIf { it.request.identity == identity && it.phase.active }
                ?.let { graph.backgroundWorkspaceExecutions.stop(it.token) }
                ?: false
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
    internal val skillExecutionActions = NativeSkillExecutionActions(
        state = graph.backgroundWorkspaceExecutions.state,
        start = ::executeSkill,
        stop = { token -> graph.backgroundWorkspaceExecutions.stop(token) },
    )
    /** MCP configuration/credential commands shared with the More-page dialog. */
    internal val mcpActions: NativeMcpSettingsActions get() = graph.mcp
    private val resolver = application.contentResolver
    private val manualBatchFiles = NativeManualBatchFiles(application)
    private val legacyExport = MutableStateFlow(NativeLegacyExportState())
    internal val legacyExportState = legacyExport.asStateFlow()
    private var legacyExportJob: Job? = null
    private var legacyExportRunToken: String? = null
    private var legacyExportCommand: String? = null
    private var legacyExportDirectoryName: String? = null
    internal val legacyExportActions: LegacyExportActions = object : LegacyExportActions {
        override fun start(outputDirectory: String) = startLegacyExport(outputDirectory)
        override fun cancel() = cancelLegacyExport()
    }
    private val projectImport = MutableStateFlow(NativeProjectImportUiState())
    internal val projectImportState = projectImport.asStateFlow()
    private val projectImportVisible = MutableStateFlow(false)
    internal val projectImportVisibleState = projectImportVisible.asStateFlow()
    private var importCandidates: List<NativeImportCandidate> = emptyList()
    private val agentAttachmentActions = object : NativeAgentAttachmentActions {
        override fun add(uris: List<Uri>) = addAgentAttachments(uris)
        override fun remove(id: AttachmentId) {
            mutable.update { current ->
                current.copy(agentAttachments = current.agentAttachments.filterNot { it.id == id })
            }
        }
        override fun clearFailures() {
            mutable.update { it.copy(agentAttachmentFailures = emptyList()) }
        }
    }
    private val modelCatalogActions = NativeModelCatalogActions(
        loadCached = ::loadModelCatalog,
        refresh = ::refreshModelCatalog,
        probeMetadata = ::probeModelMetadata,
        requestGeneration = ::probeModelGeneration,
        confirmGeneration = ::confirmModelGenerationProbe,
        dismissConfirmation = ::dismissModelGenerationConfirmation,
        cancel = ::cancelModelCatalogProbe,
        clear = ::clearModelCatalog,
    )
    private val oauthActions = NativeOAuthActions(
        load = ::loadOAuth,
        saveConfiguration = ::saveOAuthConfiguration,
        removeConfiguration = ::removeOAuthConfiguration,
        login = ::loginOAuth,
        cancel = ::cancelOAuth,
        refreshStatus = ::refreshOAuthStatus,
        logout = ::logoutOAuth,
    )
    private val mutable: MutableStateFlow<NativeProjectUiState> = MutableStateFlow(NativeProjectUiState(recoveryProblems = saved["native_recovery_problems"] ?: 0, encoding =
        runCatching { TxtEncoding.valueOf(saved["native_encoding"] ?: "UTF8") }.getOrDefault(TxtEncoding.UTF8),
        supportedFormats = graph.formatAdapters,
        modelCatalogActions = modelCatalogActions,
        oauthActions = oauthActions,
        appLanguage = appSettings.language(),
        appPersona = appSettings.appPersona(),
        diagnosticsIncludeDetails = appSettings.diagnosticsIncludeDetails(),
        agentAttachmentActions = agentAttachmentActions,
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
                if (!isNativeFormatActive(target.value)) {
                    throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
                }
                val bytes = graph.workspace.readVerifiedArtifactBytes(ref, artifact.runId, artifact.id,
                    artifact.sha256, artifact.sizeBytes)
                val receipt = graph.formatProjects.importAsset(command, ref, revision, target, bytes)
                if (receipt.status != AssetMutationStatus.COMMITTED)
                    throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
                // The commit receipt is authoritative even if refreshing the projection fails.
                mutable.value = mutable.value.copy(qualityView = null, qualityInvalidated = true)
                try {
                    loadPage(ref, cursors.last()); loadAgent(ref)
                    formatImportNotice(receipt.warnings)?.let { warning ->
                        mutable.value = mutable.value.copy(notice = warning)
                    }
                }
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
        uploadUri = { uri -> uploadWorkspaceFile(uri) },
    )
    /**
     * Copies one SAF file into the project workspace uploads/ area. This is a workspace material,
     * not a project asset and not a model image; the Agent can list/read it through workspace tools.
     */
    fun uploadWorkspaceFile(uri: Uri) = action {
        val project = mutable.value.project ?: return@action
        val input = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val rawName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: "upload.bin"
            val output = ByteArrayOutputStream()
            val coroutine = currentCoroutineContext()
            resolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutine.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > 32L * 1024 * 1024) {
                        throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                    }
                    output.write(buffer, 0, count)
                }
            } ?: throw java.io.IOException()
            rawName to output.toByteArray()
        }
        withContext(Dispatchers.IO) {
            graph.workspace.upload(project.ref, input.first, input.second)
        }
        mutable.value = mutable.value.copy(notice = R.string.native_saved,
            workspaceUploadRevision = mutable.value.workspaceUploadRevision + 1)
    }

    private val agentPreviews = graph.agentPreviews
    val agentStream = agentPreviews.state
    private var job: Job? = null
    private var agentLease: RunLease? = null
    private var agentChat = ChatId("native-agent-main")
    private var agentQueueStored: StoredAgentInputQueue? = null
    private val cursors = mutableListOf(0L)
    private var actionId = 0L
    private var observedSimulation: ForegroundTranslationHandle? = null
    private var workspaceObservation: Job? = null

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
        // A process-owned direct skill execution is re-attached from its opaque token. If the
        // process died, attach returns null and only Run/artifact history remains visible; the
        // skill is never replayed automatically.
        val workspaceSnapshot = graph.backgroundWorkspaceExecutions.state.value
        val workspaceToken = saved.get<String>("native_skill_token")
        val workspaceAttachment = workspaceSnapshot?.takeIf {
            it.phase.active || workspaceToken == it.token
        }?.let { attachSkillExecution(it.token) }
        if (workspaceAttachment != null) {
            saved["native_skill_token"] = workspaceAttachment.token
            val live = workspaceSnapshot?.phase?.active == true
            refreshSimulationView(running = live) {
                initializeProjects(application, recover = !live)
                openInternal(workspaceAttachment.request.ref)
            }
            awaitSkillExecution(workspaceAttachment, ownsBusy = live)
            return@action
        }
        saved.remove<String>("native_skill_token")
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
        viewModelScope.launch {
            graph.oauth.state.collect { oauthState ->
                mutable.value = mutable.value.copy(oauth = oauthState)
            }
        }
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
                // A manual/foreground translation also owns the runtime slot. Once it settles,
                // an explicitly QUEUED task may continue; the job only re-reads durable facts.
                if (!snapshot.phase.active) graph.translationQueueScheduler.scheduleWake()
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
            graph.translationQueueScheduler.recoverExclusive()
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
        requireLegacyTask(ref, run)
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
    /** Historical costs by each receipt's captured price version; never reprices with the current table. */
    internal suspend fun usageCosts(query: UsageLedgerQuery): UsageCostSummary =
        usageCostsReader?.invoke(query)
            ?: UsageCostAggregator(graph.usageLedger, graph.priceTables).aggregate(query)

    fun changeEncoding(value: TxtEncoding) { if (!mutable.value.busy) {
        saved["native_encoding"] = value.name
        mutable.value = mutable.value.copy(encoding = value)
        if (projectImportVisible.value && !projectImport.value.busy) {
            viewModelScope.launch { refreshProjectImportReview() }
        }
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
        val workspace = graph.backgroundWorkspaceExecutions.state.value
        if (workspace?.phase?.active == true && (saved.get<String>("native_skill_token") == workspace.token ||
            workspace.request.ref == mutable.value.project?.ref)) {
            graph.backgroundWorkspaceExecutions.stop(workspace.token)
            return // Wait for application-owned script/run cleanup.
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

    internal suspend fun clearDiagnostics() {
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

    /**
     * The full archive is distinct from settings-only backup. Diagnostics are an explicit
     * opt-in captured with the command intent so the confirmation dialog cannot drift.
     */
    fun prepareFullArchiveExport(includeDiagnostics: Boolean): String? {
        val state = mutable.value
        if (state.busy || state.editor != null || state.manualBatch != null || state.selectingItems ||
            state.runLoadIncomplete || state.policyDraft?.dirty == true ||
            graph.runtime.snapshot().activeRun != null) return null
        saved["native_full_archive_export"] =
            arrayListOf(commandId().value, includeDiagnostics.toString())
        return "lnt-full-archive.zip"
    }

    fun completeFullArchiveExport(uri: Uri?) {
        val pending = saved.remove<ArrayList<String>>("native_full_archive_export")?.toList()
        if (uri == null || pending == null || pending.size != 2) return
        queuedAction {
            val options = FullArchiveExportOptions(pending[1].toBooleanStrict())
            graph.applicationBackups.exportFullArchive(options) {
                resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Backup destination unavailable")
            }
            mutable.value = mutable.value.copy(notice = R.string.native_full_archive_exported)
        }
    }

    /** SAF command alias kept in the same vocabulary as the application service. */
    fun exportFullArchive(uri: Uri?) = completeFullArchiveExport(uri)

    fun prepareFullArchiveImport(): Boolean {
        val state = mutable.value
        if (state.busy) return false
        if (state.editor != null || state.manualBatch != null || state.selectingItems ||
            state.runLoadIncomplete || state.policyDraft?.dirty == true) return false
        val active = graph.runtime.snapshot().activeRun != null
        val existing = saved.get<ArrayList<String>>("native_full_archive_import")?.toList()
        val command = existing?.singleOrNull() ?: commandId().value
        val intent = fullArchiveImportIntent(existing, command, idle = !active)
        if (intent == null) {
            mutable.value = mutable.value.copy(error = R.string.native_full_archive_not_idle)
            return false
        }
        saved["native_full_archive_import"] = ArrayList(intent)
        return true
    }

    fun cancelFullArchiveImport() {
        saved.remove<ArrayList<String>>("native_full_archive_import")
    }

    /** Preflight before opening the picker so an active run gets the explicit idle message. */
    fun requestFullArchiveImport(onReady: () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            val idle = try {
                graph.fullArchiveDataPort.restoreState().idle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!idle) {
                mutable.value = mutable.value.copy(error = R.string.native_full_archive_not_idle)
                return@launch
            }
            if (prepareFullArchiveImport()) onReady()
        }
    }

    /** Restore owns its own idle check; no restored approval/queue may start work. */
    fun importFullArchive(uri: Uri?) {
        val intent = saved.remove<ArrayList<String>>("native_full_archive_import")?.toList()
        if (uri == null || intent == null || intent.size != 1) return
        queuedAction {
            val restoreState = graph.fullArchiveDataPort.restoreState()
            if (!restoreState.idle) throw FullArchiveRestoreNotIdle()
            graph.fullArchiveDataPort.useImportCommand(CommandId(intent.single()))
            try {
                graph.applicationBackups.restore {
                    resolver.openInputStream(uri) ?: throw java.io.IOException("Backup source unavailable")
                }
            } catch (failure: IllegalArgumentException) {
                val idle = try {
                    graph.fullArchiveDataPort.restoreState().idle
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!idle) throw FullArchiveRestoreNotIdle()
                throw failure
            }
            val registered = projects.registeredProjects()
            mutable.value = mutable.value.copy(projects = registered,
                notice = R.string.native_full_archive_restored)
            mutable.value.project?.let { loadAgent(it.ref) }
        }
    }
    /** Private app/project footprint. Manual and lazy; never walks the tree on the main thread. */
    suspend fun fileInventory(ref: LocalProjectRef, revision: Long): ProjectFileInventory {
        require(mutable.value.project?.let { it.ref == ref && it.revision == revision } == true)
        val result = graph.projects.fileInventory(ref, revision)
        require(mutable.value.project?.let { it.ref == ref && it.revision == revision } == true)
        return result
    }

    /**
     * Read-only member metadata for one stored RGSS archive. Bytes are read through the asset port,
     * decoded only to obtain sizes and flags, and never returned to the UI. Validation failures use
     * the existing path-free [TextProjectException] reasons.
     */
    internal suspend fun rgssInventory(ref: LocalProjectRef, assetId: Long): NativeRgssInventory =
        withContext(Dispatchers.IO) {
            val assetStore = projects as? ProjectAssetPort
                ?: throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
            val asset = assetStore.assets(ref).firstOrNull { it.id == assetId }
                ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
            if (asset.format != ProjectAssetFormat.RPGMAKER_RGSS) {
                throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
            }
            val bytes = assetStore.readAsset(ref, assetId)
            if (bytes.size.toLong() > RGSS_INVENTORY_MAX_BYTES) {
                throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            try {
                rgssInventoryOf(RgssProjectService.importArchive(bytes), bytes.size.toLong())
            } catch (failure: RgssArchiveException) {
                throw TextProjectException(when (failure.reason) {
                    RgssArchiveException.Reason.LIMIT -> TextProjectException.Reason.LIMIT_EXCEEDED
                    RgssArchiveException.Reason.VERSION -> TextProjectException.Reason.UNSUPPORTED_VERSION
                    else -> TextProjectException.Reason.INVALID_FORMAT
                })
            } catch (failure: RgssFormatException) {
                throw TextProjectException(when (failure.reason) {
                    RgssFormatException.Reason.LIMIT -> TextProjectException.Reason.LIMIT_EXCEEDED
                    RgssFormatException.Reason.IO -> TextProjectException.Reason.STORAGE_IO
                    else -> TextProjectException.Reason.INVALID_FORMAT
                })
            }
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
            val previous = graph.modelProfiles.find(NativeModelSettingsStore.DEFAULT_PROFILE_ID)
            val settings = graph.modelSettings.save(endpoint, model, key)
            clearOldOAuthCredential(previous)
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
            val legacyProfile = if (selected == null) {
                graph.modelProfiles.find(NativeModelSettingsStore.DEFAULT_PROFILE_ID)
            } else {
                null
            }
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
            clearOldOAuthCredential(selected ?: legacyProfile)
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
        protocol: NativeModelProtocol, keys: List<String>?) = modelMutation {
        val previous = graph.modelProfiles.find(id)
        graph.modelProfiles.saveConfiguration(id, endpoint, model, protocol, keys)
        clearOldOAuthCredential(previous)
    }

    /** Saves the immutable configuration revision first, then the editable metadata in one action. */
    fun updateModelProfile(id: String, edit: ModelProfileEdit, endpoint: String, model: String,
        protocol: NativeModelProtocol, keys: List<String>?) = modelMutation {
        val previous = graph.modelProfiles.find(id)
        graph.modelProfiles.saveConfiguration(id, endpoint, model, protocol, keys)
        graph.modelProfiles.edit(id, edit)
        clearOldOAuthCredential(previous)
    }

    /** A new model revision cannot reuse the previous revision's encrypted OAuth token. */
    private suspend fun clearOldOAuthCredential(previous: NativeModelProfile?) {
        previous ?: return
        val current = graph.modelProfiles.find(previous.id) ?: return
        if (current.reference == previous.reference) return
        try {
            graph.oauth.clearCredential(previous.reference)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The new revision still cannot read the old token; cleanup is best effort.
        }
    }

    fun copyModelProfile(id: String, name: String) = modelMutation { graph.modelProfiles.copy(id, name) }

    fun deleteModelProfile(id: String) = modelMutation {
        val profile = graph.modelProfiles.find(id)
        if (profile != null) {
            try {
                graph.oauth.clearProfile(profile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Profile deletion must not be blocked by best-effort OAuth cleanup.
            }
        }
        graph.modelProfiles.delete(id)
    }

    fun moveModelProfile(id: String, offset: Int) = modelMutation { graph.modelProfiles.move(id, offset) }

    fun selectModelProfile(id: String, purpose: ModelProfilePurpose) = modelMutation {
        graph.modelProfiles.select(id, purpose)
    }

    private var oauthJob: Job? = null
    private var oauthCallbackJob: Job? = null

    private fun oauthRunActive(): Boolean =
        mutable.value.foregroundExecutionActive || graph.runtime.snapshot().activeRun != null

    private fun oauthActionBlocked(): Boolean = mutable.value.busy || oauthRunActive()

    private fun reportOAuthFailure(profileId: String?, failure: NativeOAuthUiFailure) {
        viewModelScope.launch {
            try {
                graph.oauth.reportFailure(profileId, failure)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The sanitized OAuth failure is best-effort UI state.
            }
        }
    }

    fun loadOAuth(profileId: String) {
        oauthJob = viewModelScope.launch {
            try {
                graph.oauth.load(profileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.UNKNOWN)
            }
        }
    }

    fun refreshOAuthStatus(profileId: String) = loadOAuth(profileId)

    fun saveOAuthConfiguration(profileId: String, draft: NativeOAuthConfigDraft) {
        if (oauthActionBlocked()) {
            reportOAuthFailure(profileId, NativeOAuthUiFailure.BUSY)
            return
        }
        oauthJob?.cancel()
        oauthJob = viewModelScope.launch {
            try {
                val profile = graph.modelProfiles.find(profileId)
                if (profile == null) {
                    graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.PROFILE_MISSING)
                } else {
                    graph.oauth.saveConfiguration(profile, draft)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.UNKNOWN)
            }
        }
    }

    fun removeOAuthConfiguration(profileId: String) {
        if (oauthActionBlocked()) {
            reportOAuthFailure(profileId, NativeOAuthUiFailure.BUSY)
            return
        }
        oauthJob?.cancel()
        oauthJob = viewModelScope.launch {
            try {
                val profile = graph.modelProfiles.find(profileId)
                if (profile == null) {
                    graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.PROFILE_MISSING)
                } else {
                    graph.oauth.removeConfiguration(profile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.UNKNOWN)
            }
        }
    }

    fun loginOAuth(profileId: String) {
        if (oauthActionBlocked()) {
            reportOAuthFailure(profileId, NativeOAuthUiFailure.BUSY)
            return
        }
        oauthJob?.cancel()
        oauthJob = viewModelScope.launch {
            try {
                val profile = graph.modelProfiles.find(profileId)
                if (profile == null) {
                    graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.PROFILE_MISSING)
                    return@launch
                }
                when (val start = graph.oauth.beginLogin(profile)) {
                    is NativeOAuthLoginStart.Open -> {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(start.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            getApplication<Application>().startActivity(intent)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            graph.oauth.cancelLogin(profileId)
                            graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.NO_BROWSER)
                        }
                    }
                    is NativeOAuthLoginStart.Rejected -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.UNKNOWN)
            }
        }
    }

    fun cancelOAuth(profileId: String) {
        oauthJob?.cancel()
        oauthCallbackJob?.cancel()
        oauthJob = viewModelScope.launch {
            try {
                graph.oauth.cancelLogin(profileId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.UNKNOWN)
            }
        }
    }

    fun logoutOAuth(profileId: String) {
        if (oauthActionBlocked()) {
            reportOAuthFailure(profileId, NativeOAuthUiFailure.BUSY)
            return
        }
        oauthJob?.cancel()
        oauthJob = viewModelScope.launch {
            try {
                val profile = graph.modelProfiles.find(profileId)
                if (profile == null) {
                    graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.PROFILE_MISSING)
                } else {
                    graph.oauth.logout(profile)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                graph.oauth.reportFailure(profileId, NativeOAuthUiFailure.UNKNOWN)
            }
        }
    }

    /**
     * Called from MainActivity.onResume/onNewIntent. The flow store is one-time, so
     * repeated lifecycle calls cannot exchange the same code twice. An active run
     * discards the callback instead of starting a token request.
     */
    fun consumeOAuthCallback() {
        if (oauthCallbackJob?.isActive == true) return
        oauthCallbackJob = viewModelScope.launch {
            try {
                graph.oauth.consumeCallback(allowNetwork = !oauthRunActive())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                try {
                    graph.oauth.reportFailure(null, NativeOAuthUiFailure.UNKNOWN)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Nothing else to surface; no token or request body is logged.
                }
            }
        }
    }

    fun loadModelProfileKeys(id: String) = action {
        val profile = graph.modelProfiles.find(id) ?: return@action
        val keys = graph.modelKeys.describe(profile.reference).map {
            ModelProfileKeyState(it.id, it.enabled, it.consecutiveFailures, it.cooldownUntilMillis,
                it.lastFailure?.name)
        }
        mutable.value = mutable.value.copy(modelProfileKeys = keys)
        val previous = mutable.value.modelCatalog
        mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogLoading(id, previous))
        val snapshot = try { cachedCatalog(profile) }
        catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(modelCatalog = mutable.value.modelCatalog.copy(loading = false))
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogFailure(
                mutable.value.modelCatalog, id, R.string.native_model_catalog_cache_error))
            return@action
        }
        if (mutable.value.modelCatalog.profileId != id) return@action
        mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogState(id, snapshot,
            live = false, previous = mutable.value.modelCatalog))
    }

    fun setModelKeyEnabled(id: String, keyId: String, enabled: Boolean) = action {
        val profile = graph.modelProfiles.find(id) ?: return@action
        val keys = graph.modelKeys.setEnabled(profile.reference, keyId, enabled).map {
            ModelProfileKeyState(it.id, it.enabled, it.consecutiveFailures, it.cooldownUntilMillis,
                it.lastFailure?.name)
        }
        mutable.value = mutable.value.copy(modelProfileKeys = keys)
    }

    /** Cancels only the current catalog load/probe action, not unrelated background runs. */
    fun cancelModelCatalogProbe() {
        val current = mutable.value.modelCatalog
        if (!current.loading && !current.generationRunning) return
        job?.cancel()
    }

    /** Clears transient probe/catalog UI only; the saved snapshot and profiles stay untouched. */
    fun clearModelCatalog() {
        if (mutable.value.modelCatalog.generationRunning) return
        mutable.value = mutable.value.copy(modelCatalog = NativeModelCatalogUiState())
    }

    fun dismissModelGenerationConfirmation() {
        val current = mutable.value.modelCatalog
        if (current.generationRunning) return
        mutable.value = mutable.value.copy(modelCatalog = current.copy(
            generationConfirmation = false, billing = null))
    }

    private suspend fun cachedCatalog(profile: NativeModelProfile): ModelCatalogSnapshot? =
        graph.modelCatalogSnapshots.load(profile.settings.endpoint, profile.settings.protocol)

    private fun adoptCatalogProfile(profileId: String) {
        val current = mutable.value.modelCatalog
        if (current.profileId != profileId && !current.generationRunning) {
            mutable.value = mutable.value.copy(modelCatalog = NativeModelCatalogUiState(profileId = profileId))
        }
    }

    /** Reads only the app-private snapshot; it never contacts the provider. */
    fun loadModelCatalog(profileId: String) = action {
        val profile = graph.modelProfiles.find(profileId) ?: return@action
        val previous = mutable.value.modelCatalog
        mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogLoading(profileId, previous))
        val snapshot = try { cachedCatalog(profile) }
        catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(modelCatalog = mutable.value.modelCatalog.copy(loading = false))
            throw cancelled
        }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogFailure(
                mutable.value.modelCatalog, profileId, R.string.native_model_catalog_cache_error))
            return@action
        }
        if (mutable.value.modelCatalog.profileId != profileId) return@action
        mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogState(
            profileId = profileId, snapshot = snapshot, live = false,
            previous = mutable.value.modelCatalog))
    }

    /** Explicit GET-only refresh. Success replaces the snapshot, including an empty result. */
    fun refreshModelCatalog(profileId: String) = action {
        adoptCatalogProfile(profileId)
        if (nativeModelProbeCommand(mutable.value.modelCatalog, profileId,
                generation = false, confirmed = false) != NativeModelProbeCommand.RUN_METADATA) return@action
        val profile = graph.modelProfiles.find(profileId) ?: return@action
        val previous = mutable.value.modelCatalog
        mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogLoading(profileId, previous))
        val result = try {
            graph.modelCatalogProbes.probeMetadata(profile.settings.endpoint, "",
                profile.reference, profile.settings.protocol)
        } catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(modelCatalog = mutable.value.modelCatalog.copy(
                loading = false, metadataProbe = NativeModelProbeUi.Cancelled))
            throw cancelled
        }
        if (mutable.value.modelCatalog.profileId != profileId) return@action
        mutable.value = mutable.value.copy(modelCatalog = when (result) {
            is ModelProbeResult.MetadataSuccess -> nativeModelCatalogState(profileId, result.snapshot,
                live = true, previous = mutable.value.modelCatalog)
            is ModelProbeResult.Failure -> nativeModelCatalogFailure(mutable.value.modelCatalog, profileId,
                nativeProbeFailureLabel(result.kind))
            is ModelProbeResult.GenerationSuccess -> mutable.value.modelCatalog
        })
    }

    /** Explicit GET-only capability probe; no generation or billing receipt is involved. */
    fun probeModelMetadata(profileId: String) = action {
        adoptCatalogProfile(profileId)
        if (nativeModelProbeCommand(mutable.value.modelCatalog, profileId,
                generation = false, confirmed = false) != NativeModelProbeCommand.RUN_METADATA) return@action
        val profile = graph.modelProfiles.find(profileId) ?: return@action
        val previous = mutable.value.modelCatalog
        mutable.value = mutable.value.copy(modelCatalog = nativeModelCatalogLoading(profileId, previous))
        val result = try {
            graph.modelCatalogMetadataProbe.probeMetadata(profile.settings.endpoint, "",
                profile.reference, profile.settings.protocol)
        } catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(modelCatalog = mutable.value.modelCatalog.copy(
                loading = false, metadataProbe = NativeModelProbeUi.Cancelled))
            throw cancelled
        }
        if (mutable.value.modelCatalog.profileId != profileId) return@action
        mutable.value = mutable.value.copy(modelCatalog = when (result) {
            is ModelProbeResult.MetadataSuccess -> {
                val next = nativeModelCatalogState(profileId, result.snapshot,
                    live = true, previous = mutable.value.modelCatalog, persisted = false)
                next.copy(metadataProbe = nativeProbeUi(result))
            }
            is ModelProbeResult.Failure -> mutable.value.modelCatalog.copy(
                loading = false,
                metadataProbe = nativeProbeUi(result),
                error = nativeProbeFailureLabel(result.kind),
            )
            is ModelProbeResult.GenerationSuccess -> mutable.value.modelCatalog
        })
    }

    /**
     * The explicit confirmation entry point. It loads price evidence and opens the
     * dialog; it never sends a request itself. [confirmModelGenerationProbe] is the
     * only path that dispatches the single free-form completion.
     */
    fun probeModelGeneration(profileId: String) = action {
        adoptCatalogProfile(profileId)
        if (nativeModelProbeCommand(mutable.value.modelCatalog, profileId,
                generation = true, confirmed = false) != NativeModelProbeCommand.SHOW_CONFIRMATION) return@action
        val profile = graph.modelProfiles.find(profileId) ?: return@action
        val price = try { graph.priceTables.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val current = mutable.value.modelCatalog
        if (current.profileId != null && current.profileId != profileId) return@action
        mutable.value = mutable.value.copy(modelCatalog = current.copy(
            profileId = profileId,
            generationConfirmation = true,
            billing = nativeProbeBillingHint(price, profile.settings.model),
            generationProbe = null,
            error = null,
        ))
    }

    /** Runs exactly one confirmed probe through the shared scheduler/protocol transport. */
    fun confirmModelGenerationProbe(profileId: String) = action {
        val current = mutable.value.modelCatalog
        if (nativeModelProbeCommand(current, profileId, generation = true, confirmed = true)
            != NativeModelProbeCommand.RUN_GENERATION) return@action
        val profile = graph.modelProfiles.find(profileId) ?: run {
            dismissModelGenerationConfirmation(); return@action
        }
        mutable.value = mutable.value.copy(modelCatalog = current.copy(
            generationConfirmation = false,
            generationRunning = true,
            generationProbe = null,
            billing = null,
            error = null,
        ))
        try {
            val result = graph.modelCatalogProbes.probeGeneration(profile.reference)
            if (mutable.value.modelCatalog.profileId != profileId) return@action
            mutable.value = mutable.value.copy(modelCatalog = mutable.value.modelCatalog.copy(
                generationRunning = false,
                generationProbe = nativeProbeUi(result),
            ))
        } catch (cancelled: CancellationException) {
            mutable.value = mutable.value.copy(modelCatalog = mutable.value.modelCatalog.copy(
                generationRunning = false,
                generationProbe = NativeModelProbeUi.Cancelled,
            ))
            throw cancelled
        } finally {
            if (mutable.value.modelCatalog.generationRunning) {
                mutable.value = mutable.value.copy(modelCatalog = mutable.value.modelCatalog.copy(
                    generationRunning = false))
            }
        }
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
    fun inspectAgentMessage(id: String) {
        val ref = mutable.value.project?.ref ?: return
        val chat = agentChat
        val inspection = ++agentInspectionVersion
        viewModelScope.launch {
            try {
                val detail = graph.agentStore.messageDetail(ref, chat, id)
                if (agentInspectionVersion == inspection && mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
                    mutable.value = mutable.value.copy(agentMessageDetail = detail)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (agentInspectionVersion == inspection && mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
                    mutable.value = mutable.value.copy(notice = R.string.native_agent_load_error)
            }
        }
    }
    fun closeAgentMessage() { agentInspectionVersion++; mutable.value = mutable.value.copy(agentMessageDetail = null) }
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
            saveComposer(it.ref, agentChat, text)
            mutable.value = mutable.value.copy(agentInput = text)
        }
    }

    private var agentAttachmentJob: Job? = null
    private var agentAttachmentGeneration = 0L

    private fun clearAgentAttachments() {
        agentAttachmentGeneration++
        agentAttachmentJob?.cancel()
        agentAttachmentJob = null
        mutable.value = mutable.value.copy(agentAttachments = emptyList(),
            agentAttachmentFailures = emptyList(), agentAttachmentBusy = false, agentAttachmentProject = null)
    }

    /**
     * Reads each SAF document sequentially on IO, enforces the 1 MiB per-image and 4-image/total
     * budgets, persists only verified bytes, and reports one failure per rejected item. No image
     * bytes or paths enter logs; a project switch cancels this job and clears pending attachments.
     */
    private fun addAgentAttachments(uris: List<Uri>) {
        val state = mutable.value
        val ref = state.project?.ref ?: return
        if (uris.isEmpty() || state.agentAttachmentBusy || agentAttachmentJob?.isActive == true) return
        val baseCount = state.agentAttachments.size
        var totalBytes = state.agentAttachments.sumOf { it.sizeBytes }
        val accepted = ArrayList<PendingAgentAttachment>()
        val generation = agentAttachmentGeneration
        mutable.value = state.copy(agentAttachmentBusy = true, agentAttachmentProject = ref,
            agentAttachmentFailures = emptyList())
        agentAttachmentJob = viewModelScope.launch {
            suspend fun record(name: String, reason: NativeAgentAttachmentFailureReason) {
                withContext(Dispatchers.Main.immediate) {
                    mutable.update { current ->
                        if (current.project?.ref != ref || generation != agentAttachmentGeneration) current
                        else current.copy(
                            agentAttachmentFailures = (current.agentAttachmentFailures +
                                NativeAgentAttachmentFailure(name, reason)).take(8))
                    }
                }
            }
            try {
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    if (mutable.value.project?.ref != ref || generation != agentAttachmentGeneration) return@launch
                    val name = withContext(Dispatchers.IO) { pickedAttachmentName(resolver, uri) }
                    if (baseCount + accepted.size >= AgentAttachmentLimits.MAX_PER_MESSAGE) {
                        record(name, NativeAgentAttachmentFailureReason.LIMIT)
                        continue
                    }
                    val picked = try {
                        withContext(Dispatchers.IO) { readPickedAttachment(resolver, uri) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: NativeAttachmentReadException) {
                        record(failure.displayName, failure.reason)
                        continue
                    } catch (_: Exception) {
                        record(name, NativeAgentAttachmentFailureReason.UNREADABLE)
                        continue
                    }
                    val admission = agentAttachmentAdmissionFailure(
                        baseCount + accepted.size, totalBytes, picked.sizeBytes)
                    if (admission != null) {
                        record(picked.displayName, admission)
                        continue
                    }
                    val id = try {
                        graph.agentAttachments.save(picked.bytes, picked.mimeType, ref, picked.displayName)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: AgentAttachmentException) {
                        record(picked.displayName, when (failure) {
                            is AgentAttachmentException.TooLarge -> NativeAgentAttachmentFailureReason.TOO_LARGE
                            is AgentAttachmentException.InvalidMime,
                            is AgentAttachmentException.Empty -> NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE
                            is AgentAttachmentException.Limit -> NativeAgentAttachmentFailureReason.LIMIT
                            else -> NativeAgentAttachmentFailureReason.SAVE_FAILED
                        })
                        continue
                    } catch (_: Exception) {
                        record(picked.displayName, NativeAgentAttachmentFailureReason.SAVE_FAILED)
                        continue
                    }
                    val pending = PendingAgentAttachment(id, uri, picked.displayName, picked.mimeType,
                        picked.sizeBytes.toLong())
                    totalBytes += picked.sizeBytes
                    accepted += pending
                    mutable.update { current ->
                        if (current.project?.ref == ref && generation == agentAttachmentGeneration) current.copy(
                            agentAttachments = current.agentAttachments + pending)
                        else current
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    mutable.update { current ->
                        if (current.project?.ref == ref && generation == agentAttachmentGeneration)
                            current.copy(agentAttachmentBusy = false)
                        else current
                    }
                }
                if (agentAttachmentJob === currentCoroutineContext()[Job]) agentAttachmentJob = null
            }
        }
    }

    internal suspend fun prepareAgentBranch(ref: LocalProjectRef, chat: ChatId, id: MessageId): AgentBranchPreview {
        require(!mutable.value.busy && mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
        return graph.agentStore.prepareBranch(ref, chat, id)
    }
    internal suspend fun createAgentBranch(preview: AgentBranchPreview, command: CommandId, replacement: String?) {
        val current = mutable.value
        require(!current.busy && current.project?.ref == preview.ref && current.agentChatId == preview.chat &&
            current.agentInput.isBlank() && current.agentAttachments.isEmpty() && !current.hasAgentApproval)
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
        saveComposer(ref, requireNotNull(run.chatId), attempt.text)
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
            execution.request.identity.chatId != state.agentChatId || text.isBlank() ||
            state.agentAttachments.isNotEmpty()) return
        val command = AgentSteerCommand(commandId(), execution.request.identity,
            ChatIdentity(ref.projectId, requireNotNull(state.agentChatId)), text)
        steerSubmitting = true
        viewModelScope.launch {
            try {
                when (graph.steering.submit(ref, command)) {
                    is AgentSteerSubmitResult.Accepted, is AgentSteerSubmitResult.Duplicate -> {
                        if (mutable.value.project?.ref == ref && mutable.value.agentChatId == command.chat.chatId &&
                            mutable.value.agentInput == text) {
                            saveComposer(ref, agentChat, "")
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
        if (text.isBlank() || mutable.value.agentAttachments.isNotEmpty()) return@action
        val queue = mutable.value.agentQueue ?: AgentInputQueue.empty(ChatIdentity(ref.projectId, agentChat))
        val draft = AgentInputDraft.create(commandId(), queue.chat, text)
        val updated = when (val result = queue.enqueue(draft)) {
            is AgentInputQueueUpdate.Applied -> result.queue
            is AgentInputQueueUpdate.Rejected -> throw TextProjectException(TextProjectException.Reason.INVALID_TEXT)
        }
        persistAgentQueue(updated)
        saveComposer(ref, agentChat, "")
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
        val existing = mutable.value.agentInput
        val replacement = if (existing.isNotEmpty()) {
            require(mutable.value.agentAttachments.isEmpty())
            when (val swapped = updated.enqueue(AgentInputDraft.create(commandId(), queue.chat, existing))) {
                is AgentInputQueueUpdate.Applied -> swapped.queue
                is AgentInputQueueUpdate.Rejected -> return@action
            }
        } else updated
        persistAgentQueue(replacement)
        mutable.value.project?.let { saveComposer(it.ref, agentChat, draft.text) }
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
        if (state.agentLoadIncomplete || state.runLoadIncomplete || state.hasAgentApproval ||
            state.agentAttachments.isNotEmpty()) return@action
        val ref = state.project?.ref ?: return@action
        val configuration = state.modelSettings?.reference ?: return@action
        val queue = state.agentQueue ?: return@action
        if (queue.paused || queue.pendingAppend != null) return@action
        val draft = queue.head ?: return@action
        if (draft.text.length > 8000) return@action
        val sourceLanguage = state.project?.settings?.sourceLanguage?.trim()
        graph.updateSkillContextHints(NativeSkillContextHints(
            task = draft.text,
            language = sourceLanguage?.takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) },
        ))
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
        try {
            awaitQueuedAgent(handle, started.append)
        } finally {
            graph.updateSkillContextHints(NativeSkillContextHints())
        }
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
        saveComposer(ref, agentChat, mutable.value.agentInput)
        graph.agentStore.selectChat(ref, chat)
        agentPreviews.clear()
        loadAgent(ref)
    }
    fun newAgentChat() = action {
        val ref = mutable.value.project?.ref ?: return@action
        saveComposer(ref, agentChat, mutable.value.agentInput)
        graph.agentStore.newChat(ref)
        agentPreviews.clear()
        loadAgent(ref)
    }
    fun sendAgentMessage() = action {
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val ref = mutable.value.project?.ref ?: return@action
        val configuration = mutable.value.modelSettings?.reference ?: return@action
        val state = mutable.value
        val text = state.agentInput
        val composerRevision = composerRevisions[ref to agentChat] ?: 0L
        val attachments = state.agentAttachments
        if (state.agentAttachmentBusy ||
            !canSendAgentMessage(ready = true, hasApproval = state.hasAgentApproval, text = text,
                attachmentCount = attachments.size)) return@action
        val attachmentIds = attachments.map { it.id }
        if (attachments.isNotEmpty() && state.agentAttachmentProject != ref) return@action
        val sourceLanguage = state.project?.settings?.sourceLanguage?.trim()
        graph.updateSkillContextHints(NativeSkillContextHints(
            task = text,
            language = sourceLanguage?.takeIf { it.isNotEmpty() && !it.equals("auto", ignoreCase = true) },
        ))
        val handle = graph.backgroundAgents.prepare(ForegroundAgentRequest(ref,
            RunIdentity(ref.projectId, agentChat, RunId(UUID.randomUUID().toString()), commandId()),
            configuration, text, attachmentIds))
        saved["native_agent_token"] = handle.token
        mutable.value = mutable.value.copy(foregroundAgentActive = true, agentContextStatus = null,
            agentRunId = handle.request.identity.runId)
        try { NativeAgentService.start(getApplication<Application>(), handle.token) }
        catch (_: Exception) { graph.backgroundAgents.startFailed(handle.token) }
        try {
            awaitAgent(handle, composerRevision)
        } finally {
            graph.updateSkillContextHints(NativeSkillContextHints())
        }
    }

    private fun samePendingAttachments(pending: List<PendingAgentAttachment>, ids: List<AttachmentId>): Boolean =
        pending.map { it.id } == ids

    private fun clearAcceptedAttachments(ref: LocalProjectRef, ids: List<AttachmentId>) {
        mutable.update { current ->
            if (current.project?.ref == ref && samePendingAttachments(current.agentAttachments, ids))
                current.copy(agentAttachments = emptyList(), agentAttachmentFailures = emptyList())
            else current
        }
    }

    private suspend fun awaitAgent(handle: ForegroundAgentHandle, submittedRevision: Long? = null) = coroutineScope {
        val ref = handle.request.ref
        val chat = requireNotNull(handle.request.identity.chatId)
        val attachmentIds = handle.request.attachmentIds
        var acceptedInputCleared = false
        val observer = launch {
            var timelineRevision = -1L
            graph.backgroundAgents.state.collect { snapshot ->
                if (snapshot?.token != handle.token) return@collect
                mutable.value = mutable.value.copy(foregroundAgentActive = snapshot.phase.active,
                    agentContextStatus = snapshot.progress?.contextStatus.takeIf {
                        mutable.value.project?.ref == ref && mutable.value.agentChatId == chat
                    })
                if (snapshot.progress?.accepted == true && !acceptedInputCleared) {
                    acceptedInputCleared = true
                    if (mutable.value.project?.ref == ref && mutable.value.agentChatId == chat &&
                        mutable.value.agentInput == handle.request.input &&
                        (submittedRevision == null || (composerRevisions[ref to chat] ?: 0L) == submittedRevision)) {
                        saveComposer(ref, chat, "")
                        mutable.value = mutable.value.copy(agentInput = "")
                    }
                }
                if (snapshot.progress?.accepted == true && mutable.value.project?.ref == ref) {
                    clearAcceptedAttachments(ref, attachmentIds)
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
                if (!acceptedInputCleared && graph.backgroundAgents.state.value?.progress?.accepted == true &&
                    saved.get<ArrayList<String>>("agent_input_ref")?.toList() == encodeRef(ref).toList() &&
                    saved.get<String>("agent_input_chat") == chat.value &&
                    saved.get<String>("agent_input_text") == handle.request.input &&
                    (submittedRevision == null || (composerRevisions[ref to chat] ?: 0L) == submittedRevision)) {
                    saveComposer(ref, chat, "")
                    if (mutable.value.project?.ref == ref && mutable.value.agentChatId == chat)
                        mutable.value = mutable.value.copy(agentInput = "")
                }
                if (graph.backgroundAgents.state.value?.progress?.accepted == true)
                    clearAcceptedAttachments(ref, attachmentIds)
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
        if (view.state == ItemProposalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
        graph.itemProposals.reject(view)
        loadAgent(view.ref)
    }

    fun acceptItemProposal(view: ItemProposalSummary, selection: Set<ItemId>) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (view.state == ItemProposalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
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
        if (view.state == TextApprovalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
        graph.policyProposals.reject(view)
        loadAgent(view.ref)
    }
    fun acceptPolicyProposal(view: PolicyApprovalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (view.state == TextApprovalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
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
        if (view.state == PdfPageProposalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
        graph.pdfPageProposals.reject(view)
        loadAgent(view.ref)
    }

    fun acceptPdfPageProposal(view: PdfPageProposalSummary, selection: Set<Int>) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (view.state == PdfPageProposalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
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
        if (view.state == AssetImportProposalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
        graph.assetImportProposals.reject(view)
        loadAgent(view.ref)
    }

    fun acceptAssetImportProposal(view: AssetImportProposalSummary) = action {
        require(mutable.value.project?.ref == view.ref && mutable.value.agentChatId == view.chat)
        if (view.state == AssetImportProposalState.PENDING &&
            graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
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

    fun allowWorkspaceChange() = action {
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val view = mutable.value.pendingWorkspaceChangeProposal ?: return@action
        if (graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
        try {
            val result = agentRun(view.ref, view.run, view.command) { lease, recorder ->
                graph.workspaceChanges.apply(lease, view).also {
                    recorder.itemProposalResult(when (it) {
                        WorkspaceChangeProposalState.APPLIED -> ItemProposalState.COMMITTED
                        // A partial file batch must not be recorded as a successful run.
                        WorkspaceChangeProposalState.PARTIAL -> ItemProposalState.CONFLICT
                        WorkspaceChangeProposalState.REJECTED -> ItemProposalState.REJECTED
                        WorkspaceChangeProposalState.CONFLICT -> ItemProposalState.CONFLICT
                        WorkspaceChangeProposalState.PENDING -> ItemProposalState.PENDING
                    })
                }
            }
            mutable.value = mutable.value.copy(notice = when (result) {
                WorkspaceChangeProposalState.APPLIED -> R.string.native_saved
                WorkspaceChangeProposalState.PARTIAL -> R.string.native_workspace_change_partial
                WorkspaceChangeProposalState.REJECTED -> R.string.native_agent_result_rejected
                WorkspaceChangeProposalState.CONFLICT -> R.string.native_conflict
                WorkspaceChangeProposalState.PENDING -> R.string.native_agent_unresolved
            })
            settleWorkspaceApproval(view, result)
        } finally {
            withContext(NonCancellable) { loadPage(view.ref, cursors.last()); loadAgent(view.ref) }
        }
    }

    fun rejectWorkspaceChange() = action {
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val view = mutable.value.pendingWorkspaceChangeProposal ?: return@action
        if (graph.mixedApprovalCoordinator.childConsumptionBlocked(view.command)) {
            mutable.value = mutable.value.copy(error = R.string.native_mixed_approval_single_blocked)
            return@action
        }
        val result = graph.workspaceChanges.reject(view)
        mutable.value = mutable.value.copy(notice = when (result) {
            WorkspaceChangeProposalState.REJECTED -> R.string.native_agent_result_rejected
            WorkspaceChangeProposalState.CONFLICT -> R.string.native_conflict
            else -> R.string.native_agent_unresolved
        })
        settleWorkspaceApproval(view, result)
        loadAgent(view.ref)
    }

    /**
     * The workspace proposal is file-backed, so the run journal's SQL `approvalState` union cannot
     * settle the execution that prepared it. Close that WAITING_APPROVAL execution explicitly;
     * otherwise it would stay "waiting approval" in every later run-history read.
     */
    private suspend fun settleWorkspaceApproval(
        view: WorkspaceChangeProposalSummary,
        state: WorkspaceChangeProposalState,
    ) {
        val terminal = when (state) {
            WorkspaceChangeProposalState.APPLIED -> StoredRunState.SUCCEEDED
            // The new execution records PARTIAL as a conflict, so the superseded one must not
            // claim success for a batch that only partially committed.
            WorkspaceChangeProposalState.PARTIAL -> StoredRunState.FAILED
            WorkspaceChangeProposalState.REJECTED -> StoredRunState.CANCELLED
            WorkspaceChangeProposalState.CONFLICT -> StoredRunState.FAILED
            WorkspaceChangeProposalState.PENDING -> return
        }
        runCatching {
            graph.runStore.settleWaitingApproval(
                view.ref,
                RunIdentity(view.ref.projectId, view.chat, view.run, view.command),
                terminal,
            )
        }
    }

    /** One short child-command run used only to settle an existing mixed receipt, never a model call. */
    private val mixedChildRuns = NativeMixedChildRun { ref, identity, block ->
        val chat = requireNotNull(identity.chatId)
        graph.runtime.selectChat(ChatIdentity(ref.projectId, chat))
        try {
            graph.runs.execute(ref, identity, RuntimeOwner.AGENT) { lease, _ -> block(lease) }
        } finally {
            runCatching { graph.runtime.selectChat(null) }
        }
    }

    fun prepareMixedApproval() = action {
        val current = mutable.value
        val ref = current.project?.ref ?: return@action
        val chat = current.agentChatId ?: return@action
        if (current.agentLoadIncomplete || current.runLoadIncomplete) return@action
        when (val plan = graph.mixedApprovalCoordinator.prepare(ref, chat)) {
            is NativeMixedApprovalPlan.Ready -> mutable.value = mutable.value.copy(
                mixedApproval = plan.approval,
                mixedApprovalMembers = plan.members,
                mixedApprovalError = null,
                error = null,
            )
            is NativeMixedApprovalPlan.Blocked -> mutable.value = mutable.value.copy(
                mixedApprovalError = plan.reason.messageRes(),
                error = plan.reason.messageRes(),
            )
        }
    }

    fun acceptMixedApproval() = action {
        val approval = mutable.value.mixedApproval ?: return@action
        if (approval.state != MixedApprovalState.PENDING ||
            mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        try {
            val outcome = agentRun(approval.ref, approval.run, approval.commandId) { lease, _ ->
                graph.mixedApprovalCoordinator.approve(lease, approval)
            }
            withContext(NonCancellable) {
                runCatching {
                    graph.mixedApprovalCoordinator.settleResolvedChildren(
                        approval.ref, approval.chat, mixedChildRuns,
                    )
                }
            }
            val notice = when (outcome.state) {
                NativeMixedApprovalOutcomeState.SETTLED -> when (outcome.approvalState) {
                    MixedApprovalState.COMMITTED -> R.string.native_saved
                    MixedApprovalState.PARTIAL -> R.string.native_mixed_approval_partial
                    MixedApprovalState.CONFLICT -> R.string.native_mixed_approval_conflict
                    MixedApprovalState.REJECTED -> R.string.native_agent_result_rejected
                    MixedApprovalState.CANCELLED -> R.string.native_agent_result_cancelled
                    else -> R.string.native_mixed_approval_unresolved
                }
                NativeMixedApprovalOutcomeState.INDETERMINATE ->
                    R.string.native_mixed_approval_unresolved
                NativeMixedApprovalOutcomeState.BLOCKED -> null
            }
            if (outcome.state == NativeMixedApprovalOutcomeState.SETTLED) {
                settleMixedRun(approval, outcome.approvalState)
            }
            mutable.value = mutable.value.copy(
                mixedApproval = null,
                mixedApprovalMembers = emptyList(),
                mixedApprovalError = null,
                notice = notice,
                error = if (outcome.state == NativeMixedApprovalOutcomeState.BLOCKED)
                    outcome.blockReason?.messageRes() else null,
            )
        } finally {
            withContext(NonCancellable) {
                loadPage(approval.ref, cursors.last())
                loadAgent(approval.ref)
            }
        }
    }

    fun rejectMixedApproval() = action {
        val approval = mutable.value.mixedApproval ?: return@action
        if (mutable.value.agentLoadIncomplete || mutable.value.runLoadIncomplete) return@action
        val outcome = graph.mixedApprovalCoordinator.reject(approval)
        withContext(NonCancellable) {
            runCatching {
                graph.mixedApprovalCoordinator.settleResolvedChildren(
                    approval.ref, approval.chat, mixedChildRuns,
                )
            }
        }
        mutable.value = mutable.value.copy(
            mixedApproval = null,
            mixedApprovalMembers = emptyList(),
            mixedApprovalError = null,
            notice = when {
                outcome.state != NativeMixedApprovalOutcomeState.SETTLED -> null
                outcome.approvalState == MixedApprovalState.REJECTED ->
                    R.string.native_agent_result_rejected
                outcome.approvalState == MixedApprovalState.CANCELLED ->
                    R.string.native_agent_result_cancelled
                else -> null
            },
            error = if (outcome.state == NativeMixedApprovalOutcomeState.BLOCKED)
                outcome.blockReason?.messageRes() else null,
        )
        if (outcome.state == NativeMixedApprovalOutcomeState.SETTLED) {
            settleMixedRun(approval, outcome.approvalState)
        }
        loadAgent(approval.ref)
    }

    private suspend fun settleMixedRun(approval: MixedApproval, state: MixedApprovalState?) {
        val terminal = when (state) {
            MixedApprovalState.COMMITTED -> StoredRunState.SUCCEEDED
            MixedApprovalState.PARTIAL,
            MixedApprovalState.CONFLICT,
            -> StoredRunState.FAILED
            MixedApprovalState.REJECTED,
            MixedApprovalState.CANCELLED,
            -> StoredRunState.CANCELLED
            else -> return
        }
        runCatching {
            graph.runStore.settleWaitingApproval(
                approval.ref,
                RunIdentity(approval.ref.projectId, approval.chat, approval.run, approval.commandId),
                terminal,
            )
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

    internal fun decideAgentBusinessProposal(expected: NativeAgentBusinessProposal, approve: Boolean) = action {
        val ref = mutable.value.project?.ref ?: return@action
        val chat = mutable.value.agentChatId ?: return@action
        require(graph.runtime.snapshot().activeRun == null)
        require(graph.agentBusinessProposals.pending(ref, chat)?.digest == expected.digest)
        try {
            if (!approve) {
                graph.agentBusinessProposals.transition(ref, chat, expected,
                    if (expected.status == "APPLYING") "NEEDS_REVIEW" else "REJECTED")
            } else {
                require(expected.status == "PENDING")
                if (expected.kind in setOf("project_settings", "asset_operation", "application_import", "enqueue_translation")) {
                    val current = mutable.value
                    require(current.editor == null && current.manualBatch == null && !current.selectingItems && current.policyDraft?.dirty != true)
                }
                require(projects.open(ref).revision == expected.document.getValue("revision").jsonPrimitive.long)
                when (expected.kind) {
                    "install_skill" -> {
                        writeSkill {
                            val pkg = NativeAgentSkillPackage.decode(expected.payload)
                            require(graph.workspaceSkills.list().none { it.summary.id == pkg.draft.id ||
                                it.summary.conflictNameKey == pkg.draft.name.lowercase(java.util.Locale.ROOT) })
                            val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                            // Mark APPLYING before publication. An interrupted install requires inspection,
                            // never automatic replay of the old authorization.
                            graph.workspaceSkills.install(pkg.draft, pkg.resources)
                            graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                        }
                    }
                    "skill_maintenance" -> {
                        writeSkill {
                            val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                            graph.agentSkillMaintenance.apply(expected.payload)
                            graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                        }
                    }
                    "application_settings" -> {
                        val lock = graph.runtime.beginProjectWrite()
                        try {
                            val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                            graph.agentApplicationService.apply(expected.payload)
                            graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                            if (expected.payload.getValue("kind").jsonPrimitive.content == "preferences") {
                                val updated = appSettings.snapshot()
                                graph.diagnostics.setIncludeDetails(updated.diagnosticsIncludeDetails)
                                val languageChanged = updated.language != mutable.value.appLanguage
                                mutable.value = mutable.value.copy(themeMode = updated.themeMode, appLanguage = updated.language,
                                    automaticContextSummary = updated.automaticContextSummary,
                                    diagnosticsIncludeDetails = updated.diagnosticsIncludeDetails, appPersona = updated.appPersona)
                                if (languageChanged) agentRecreateState.value = true
                            }
                        } finally { graph.runtime.endProjectWrite(lock) }
                    }
                    "clear_diagnostics" -> {
                        val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                        graph.agentDiagnosticsService.apply(expected)
                        graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                    }
                    "chat_management" -> {
                        saveComposer(ref, agentChat, mutable.value.agentInput)
                        val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                        val result = graph.agentChatService.apply(ref, chat, expected)
                        graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED", result)
                        agentPreviews.clear()
                    }
                    "oauth_management" -> {
                        val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                        graph.agentAuthService.apply(expected)
                        graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                    }
                    "rule_preset_save", "rule_preset_delete", "mcp_server_save", "mcp_server_delete" -> {
                        val lock = graph.runtime.beginProjectWrite()
                        try {
                            val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                            graph.agentLibraryService.apply(expected)
                            graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                        } finally { graph.runtime.endProjectWrite(lock) }
                    }
                    "application_import" -> {
                        val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                        graph.agentImportService.apply(ref, expected)
                        graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                        reloadModelProfiles()
                    }
                    "model_management", "model_profile_save" -> {
                        val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                        val result = graph.agentModelManagement.apply(expected)
                        graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED", result)
                        reloadModelProfiles()
                    }
                    "application_export" -> {
                        val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                        val result = graph.agentExportService.apply(ref, expected)
                        graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED", result)
                    }
                    "project_settings", "asset_operation", "copy_project" -> {
                        val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                        graph.agentProjectManagement.apply(ref, expected)
                        graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                    }
                    "enqueue_translation", "control_translation", "translation_failure_decision" -> {
                        // Keep drain from reserving a paid run before the approval receipt is durable.
                        val guard = graph.runtime.beginProjectWrite()
                        var committed = false
                        try {
                            val applying = graph.agentBusinessProposals.transition(ref, chat, expected, "APPLYING")
                            graph.agentTranslationService.apply(ref, expected)
                            graph.agentBusinessProposals.transition(ref, chat, applying, "COMMITTED")
                            committed = true
                        } finally {
                            try {
                                if (!committed) withContext(NonCancellable) {
                                    graph.agentTranslationService.cancelUncommittedEnqueue(ref, expected)
                                }
                            } finally { graph.runtime.endProjectWrite(guard) }
                        }
                        graph.agentTranslationService.dispatch(ref, expected)
                    }
                    else -> error("Unsupported business proposal")
                }
            }
            if (approve && mutable.value.project?.ref == ref) {
                val summary = projects.open(ref)
                if (summary.revision != mutable.value.project?.revision) {
                    mutable.value = mutable.value.copy(project = summary, qualityView = null, qualityInvalidated = false,
                        qualityProgress = null, selectingItems = false, selectedItems = emptySet())
                    cursors.clear(); cursors.add(0L)
                    loadPage(ref, 0L)
                    refreshCleanTextPolicy(ref)
                }
                mutable.value = mutable.value.copy(projects = graph.projects.registeredProjects(100), notice = R.string.native_saved)
            }
            businessProposalState.value = graph.agentBusinessProposals.pending(ref, chat)
            if (!graph.agentWorkspace.hasPending(ref, chat)) {
                graph.runStore.settleWaitingApproval(ref,
                    RunIdentity(ref.projectId, chat, RunId(expected.document.getValue("run").jsonPrimitive.content),
                        CommandId(expected.document.getValue("command").jsonPrimitive.content)),
                    if (approve) StoredRunState.SUCCEEDED else if (expected.status == "APPLYING") StoredRunState.FAILED else StoredRunState.CANCELLED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val committed = graph.agentBusinessProposals.status(ref, chat, expected.id)?.status == "COMMITTED"
            if (!committed) throw failure
            mutable.value = mutable.value.copy(notice = R.string.native_saved, error = R.string.native_agent_business_refresh_failed)
        } finally {
            withContext(NonCancellable) {
                try {
                    businessProposalState.value = graph.agentBusinessProposals.pending(ref, chat)
                    loadAgent(ref)
                } catch (failure: Exception) {
                    if (graph.agentBusinessProposals.status(ref, chat, expected.id)?.status != "COMMITTED") throw failure
                    mutable.value = mutable.value.copy(notice = R.string.native_saved, error = R.string.native_agent_business_refresh_failed)
                }
            }
        }
    }

    private suspend fun loadAgent(ref: LocalProjectRef) {
        // Fail closed until every proposal/history read below succeeds. A cancelled load must not
        // leave the previous project's approvals looking actionable.
        mutable.value = mutable.value.copy(agentLoadIncomplete = true)
        var incomplete = false
        suspend fun <T> loadOr(fallback: T, block: suspend () -> T): T = try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { incomplete = true; fallback }
        val selectedChat = loadOr<ChatId?>(null) { graph.agentStore.currentChat(ref) }
        if (selectedChat != null) agentChat = selectedChat
        businessProposalState.value = selectedChat?.let { graph.agentBusinessProposals.pending(ref, it) }
        val mixedChildSettlementAllowed = graph.runtime.snapshot().activeRun == null
        var recoveredInterruptedTool = false
        if (selectedChat != null) {
            loadOr(Unit) { graph.agentStore.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.itemProposals.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.policyProposals.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.pdfPageProposals.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.assetImportProposals.reconcile(ref, selectedChat) }
            loadOr(Unit) { graph.workspaceChanges.reconcile(ref, selectedChat) }
            // A consumed mixed parent is classified from durable receipts. Child rows are closed
            // afterwards through their own stores; this never replays a mixed command.
            loadOr(Unit) {
                graph.mixedApprovalCoordinator.reconcile(ref, selectedChat)
                if (mixedChildSettlementAllowed) {
                    graph.mixedApprovalCoordinator.settleResolvedChildren(
                        ref, selectedChat, mixedChildRuns,
                    )
                }
            }
            // A process death between a committed assistant tool call and its result leaves the
            // canonical history unsendable. The checkpoint is evidence for the same boundary, but
            // the message table remains authoritative; close only open calls and never re-run a
            // tool with an unknown side effect. A live execution is never repaired here.
            if (graph.backgroundAgents.state.value?.phase?.active != true) {
                val checkpoint = try {
                    graph.agentCheckpoints.latest(ref, selectedChat, BranchId("main"))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (checkpoint?.stage == CheckpointStage.TOOL_PENDING) {
                    val repaired = try {
                        graph.agentStore.repairPendingToolTail(ref, selectedChat)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        incomplete = true
                        0
                    }
                    recoveredInterruptedTool = repaired > 0
                }
            }
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
        val workspaceChangeProposals = if (selectedChat == null) emptyList() else
            loadOr(emptyList<WorkspaceChangeProposalSummary>()) { graph.workspaceChanges.recent(ref, selectedChat) }
        val mixed = if (selectedChat == null) null else
            loadOr<NativeMixedApprovalSnapshot?>(null) {
                graph.mixedApprovalCoordinator.reviewable(ref, selectedChat)
            }
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
        val legacyDraft = if (saved.get<ArrayList<String>>("agent_input_ref")?.toList() == encodeRef(ref).toList() &&
            saved.get<String>("agent_input_chat") == selectedChat?.value) saved.get<String>("agent_input_text") else null
        val draft = selectedChat?.let { chat ->
            composerDraftMemory[ref to chat] ?: legacyDraft ?: composerDrafts.load(ref, chat)
        }.orEmpty()
        mutable.value = mutable.value.copy(agentMessages = messages, approval = approval, agentResults = results, itemProposals = proposals, policyProposals = policyProposals,
            pdfPageProposals = pdfPageProposals, assetImportProposals = assetImportProposals,
            workspaceChangeProposals = workspaceChangeProposals,
            mixedApproval = mixed?.approval,
            mixedApprovalMembers = mixed?.members ?: emptyList(),
            agentContextStatus = mutable.value.agentContextStatus.takeIf { mutable.value.agentChatId == selectedChat },
            agentChatId = selectedChat, agentChats = chats, modelSettings = settings,
            translationModelSettings = translationSettings, modelProfiles = profiles, agentInput = draft,
            agentQueue = queue, agentQueueRevision = storedQueue?.revision ?: 0,
            agentQueueIncomplete = incomplete, agentLoadIncomplete = incomplete, agentMessageDetail = null,
            notice = if (recoveredInterruptedTool) R.string.native_agent_interrupted_recovered else mutable.value.notice)
        loadRuns(ref)
    }

    /**
     * Freezes one direct script run, saves its opaque token, starts the non-exported foreground
     * host and attaches an application-scope observer. Returning does not wait for completion;
     * closing a dialog detaches presentation only. No model request or automatic replay occurs.
     */
    private fun executeSkill(target: NativeSkillExecutionTarget, input: String, allowFetch: Boolean): String? {
        val current = mutable.value
        val selected = current.project ?: return null
        if (current.busy || selected.ref != target.ref || selected.revision != target.revision ||
            current.agentChatId != target.identity.chatId || agentChat != target.identity.chatId ||
            current.editor != null || current.manualBatch != null || !target.skill.summary.enabled
        ) return null
        val validInput = try {
            JsonObjectValue.parse(input, JsonInputLimits(16_384, 16))
            true
        } catch (_: Exception) {
            false
        }
        if (!validInput) return null
        // The record (including its fingerprint) and policy were frozen when the resource tree
        // opened. Only this narrows the stored grant; the adapter re-resolves the enabled skill
        // under the same ceiling and rechecks script SHA-256/size before running.
        val frozenPolicy = target.skill.summary.policy
        val effectiveFetch = nativeDirectExecutionFetchAllowed(frozenPolicy, allowFetch)
        val request = try {
            ForegroundWorkspaceRequest(
                ref = target.ref,
                identity = target.identity,
                skillId = target.skill.summary.id.value,
                entryPoint = target.file.path.value.removePrefix(target.skill.summary.id.value + "/"),
                scriptSha256 = target.file.sha256,
                scriptSizeBytes = target.file.sizeBytes,
                projectRevision = target.revision,
                policy = frozenPolicy.toForegroundWorkspacePolicy(),
                inputJson = input,
                allowFetch = effectiveFetch,
            )
        } catch (_: Exception) {
            return null
        }
        val handle = try {
            graph.backgroundWorkspaceExecutions.prepare(request)
        } catch (_: Exception) {
            mutable.value = current.copy(error = R.string.native_skill_run_failed, notice = null)
            return null
        }
        saved["native_skill_token"] = handle.token
        mutable.value = mutable.value.copy(busy = true, error = null, notice = null,
            foregroundWorkspaceActive = true)
        try {
            NativeWorkspaceExecutionService.start(getApplication<Application>(), handle.token)
        } catch (_: Exception) {
            graph.backgroundWorkspaceExecutions.startFailed(handle.token)
        }
        observeSkillExecution(handle)
        return handle.token
    }

    /**
     * Re-attaches only the exact still-current process token. Process death leaves no runner state,
     * so this returns null instead of recreating or replaying the old Run.
     */
    internal fun attachSkillExecution(token: String): ForegroundWorkspaceHandle? {
        val snapshot = graph.backgroundWorkspaceExecutions.state.value ?: return null
        if (snapshot.token != token) return null
        val handle = graph.backgroundWorkspaceExecutions.attach(token) ?: return null
        mutable.value = mutable.value.copy(
            foregroundWorkspaceActive = snapshot.phase.active,
            busy = snapshot.phase.active || mutable.value.busy,
        )
        return handle
    }

    private fun observeSkillExecution(handle: ForegroundWorkspaceHandle) {
        workspaceObservation?.cancel()
        workspaceObservation = viewModelScope.launch {
            try {
                awaitSkillExecution(handle, ownsBusy = true)
            } catch (cancelled: CancellationException) {
                // ViewModel destruction detaches. The application runner and saved token remain
                // available for the next process/session to re-attach; nothing is replayed.
                throw cancelled
            }
        }
    }

    private suspend fun awaitSkillExecution(
        handle: ForegroundWorkspaceHandle,
        ownsBusy: Boolean,
    ) = coroutineScope {
        val observer = launch {
            graph.backgroundWorkspaceExecutions.state.collect { snapshot ->
                if (snapshot?.token != handle.token) return@collect
                mutable.value = mutable.value.copy(
                    foregroundWorkspaceActive = snapshot.phase.active,
                )
            }
        }
        try {
            handle.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
        } catch (_: Exception) {
            // The terminal snapshot below carries the classified failure.
        } finally {
            withContext(NonCancellable) {
                observer.cancel()
                val snapshot = graph.backgroundWorkspaceExecutions.state.value
                    ?.takeIf { it.token == handle.token }
                val terminal = snapshot != null && !snapshot.phase.active
                if (terminal) {
                    if (saved.get<String>("native_skill_token") == handle.token) {
                        saved.remove<String>("native_skill_token")
                    }
                    if (mutable.value.project?.ref == handle.request.ref) {
                        runCatching { loadAgent(handle.request.ref) }
                    }
                    // A direct script run owns the runtime slot. Its terminal state may have
                    // unblocked an explicitly QUEUED translation task, so wake the durable queue
                    // without starting any new paid request here.
                    graph.translationQueueScheduler.scheduleWake()
                }
                mutable.value = mutable.value.copy(
                    foregroundWorkspaceActive = snapshot?.phase?.active == true,
                    busy = if (ownsBusy && terminal) false else mutable.value.busy,
                )
            }
        }
    }

    private suspend fun <T> agentRun(ref: LocalProjectRef, runId: RunId = RunId(UUID.randomUUID().toString()),
                                     command: CommandId = commandId(), block: suspend (RunLease, RunProgressRecorder) -> T): T {
        val chat = agentChat
        graph.runtime.selectChat(ChatIdentity(ref.projectId, chat))
        agentPreviews.clear()
        try {
            return graph.runs.execute(ref, RunIdentity(ref.projectId, chat, runId, command), RuntimeOwner.AGENT) { lease, recorder ->
                agentLease = lease
                try {
                    val result = block(lease, recorder)
                    if (graph.agentStore.pending(ref, chat)?.runId == runId ||
                        graph.itemProposals.pending(ref, chat)?.run == runId ||
                        graph.policyProposals.pending(ref, chat)?.run == runId ||
                        graph.pdfPageProposals.pending(ref, chat)?.run == runId ||
                        graph.assetImportProposals.pending(ref, chat)?.run == runId ||
                        graph.workspaceChanges.pending(ref, chat)?.run == runId) recorder.waitingForApproval()
                    result
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        graph.agentStore.cancelPending(ref, runId)
                        runCatching { graph.workspaceChanges.cancelRun(ref, chat, runId) }
                        // A workspace proposal is file-backed; settle the waiting execution that
                        // prepared it instead of leaving it in the run history forever.
                        runCatching {
                            graph.runStore.settleWaitingApproval(
                                ref,
                                RunIdentity(ref.projectId, chat, runId, command),
                                StoredRunState.CANCELLED,
                            )
                        }
                    }
                    throw cancelled
                }
            }
        } finally {
            agentLease = null
            graph.runtime.selectChat(null)
        }
    }

    private suspend fun refreshQueueManagedTasks(ref: LocalProjectRef) {
        val tasks = runCatching {
            graph.translationQueue.snapshot(ref).queue.tasks.associateBy { it.identity.run.runId.value }
        }.getOrNull() ?: return
        mutable.value = mutable.value.copy(queueManagedRuns = tasks.keys, queueManagedTasks = tasks)
    }

    private suspend fun loadRuns(ref: LocalProjectRef) {
        try {
            graph.runStore.recoverInterrupted()
            val runs = graph.runStore.recent(ref)
            val queueTasks = runCatching {
                graph.translationQueue.snapshot(ref).queue.tasks.associateBy { it.identity.run.runId.value }
            }.getOrDefault(mutable.value.queueManagedTasks)
            val chat = mutable.value.agentChatId
            val kept = mutable.value.agentRunId?.takeIf { runId ->
                runs.any { it.owner == RuntimeOwner.AGENT && it.handle.run.chatId == chat &&
                    it.handle.run.runId == runId }
            }
            val selected = kept ?: runs.firstOrNull {
                it.owner == RuntimeOwner.AGENT && it.handle.run.chatId == chat
            }?.handle?.run?.runId
            mutable.value = mutable.value.copy(recentRuns = runs, runLoadIncomplete = false,
                agentRunId = selected, queueManagedRuns = queueTasks.keys, queueManagedTasks = queueTasks)
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
        refreshQueueManagedTasks(ref)
        graph.translationQueueScheduler.kick()
    }

    /** Explicit start of a QUEUED task. It still only runs when it is the deterministic head. */
    fun startQueuedTranslation(identity: TranslationTaskQueueIdentity) = action {
        graph.translationQueueScheduler.start(identity)
        refreshQueueManagedTasks(identity.ref)
    }

    /** Pause reaches the live runner first, then persists the queue outcome. */
    fun pauseQueuedTranslation(identity: TranslationTaskQueueIdentity) = action {
        val result = graph.translationQueueScheduler.pause(identity, commandId())
        if (result is TranslationQueueResult.Rejected)
            throw com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused(result.reason)
        refreshQueueManagedTasks(identity.ref)
    }

    fun cancelQueuedTranslation(identity: TranslationTaskQueueIdentity) = action {
        val result = graph.translationQueueScheduler.cancel(identity, commandId())
        if (result is TranslationQueueResult.Rejected)
            throw com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueRefused(result.reason)
        refreshQueueManagedTasks(identity.ref)
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
        refreshQueueManagedTasks(identity.ref)
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

    /**
     * Selects a SAF tree as the legacy export root. The tree itself is never modified until an
     * explicit start; the export is published as a new child directory and an existing child is
     * never overwritten. Only the opaque URI is kept in UI state.
     */
    fun setLegacyExportDirectory(uri: Uri?) {
        if (uri == null || legacyExport.value.status == NativeLegacyExportStatus.RUNNING) return
        legacyExportDirectoryName = null
        viewModelScope.launch {
            val displayName = withContext(Dispatchers.IO) { queryTreeDisplayName(uri) }
            legacyExport.update { current ->
                if (current.status == NativeLegacyExportStatus.RUNNING) current
                else current.copy(
                    outputDirectory = uri.toString(),
                    outputDisplayName = displayName,
                    status = NativeLegacyExportStatus.IDLE,
                    progress = null,
                    failures = emptyList(),
                    result = null,
                    message = null,
                )
            }
            runCatching {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    fun beginLegacyExport() {
        if (legacyExport.value.status == NativeLegacyExportStatus.RUNNING) return
        legacyExportRunToken = null
        legacyExportJob?.cancel()
        legacyExportJob = null
        legacyExportCommand = UUID.randomUUID().toString()
        legacyExportDirectoryName = null
        legacyExport.value = NativeLegacyExportState()
    }

    /** Starts exactly one export for the current directory; the panel's running state is the gate. */
    fun startLegacyExport(outputDirectory: String) {
        if (legacyExport.value.status == NativeLegacyExportStatus.RUNNING) return
        val tree = runCatching { Uri.parse(outputDirectory) }.getOrNull()
        if (tree == null || tree.scheme != "content") {
            legacyExport.update { it.copy(status = NativeLegacyExportStatus.FAILED,
                message = getApplication<Application>().getString(R.string.native_legacy_export_unavailable)) }
            return
        }
        val command = legacyExportCommand ?: UUID.randomUUID().toString().also { legacyExportCommand = it }
        val token = UUID.randomUUID().toString()
        legacyExportRunToken = token
        legacyExport.update { current ->
            current.copy(
                outputDirectory = tree.toString(),
                status = NativeLegacyExportStatus.RUNNING,
                progress = null,
                failures = emptyList(),
                result = null,
                message = null,
            )
        }
        legacyExportJob = viewModelScope.launch {
            runLegacyExport(tree, command, token)
        }
    }

    fun cancelLegacyExport() {
        if (legacyExport.value.status != NativeLegacyExportStatus.RUNNING) return
        legacyExportRunToken = null
        legacyExportJob?.cancel()
        legacyExportJob = null
        legacyExport.update {
            it.copy(status = NativeLegacyExportStatus.CANCELLED,
                message = getApplication<Application>().getString(R.string.native_legacy_export_cancelled))
        }
    }

    private suspend fun runLegacyExport(tree: Uri, command: String, token: String) {
        val legacyDatabase = getApplication<Application>()
            .getDatabasePath("light_novel_translation_platform.db")
        fun update(block: (NativeLegacyExportState) -> NativeLegacyExportState) {
            if (legacyExportRunToken == token) legacyExport.update(block)
        }
        try {
            if (withContext(Dispatchers.IO) { !legacyDatabase.isFile || !legacyDatabase.canRead() }) {
                update { it.copy(status = NativeLegacyExportStatus.FAILED,
                    message = getApplication<Application>().getString(R.string.native_legacy_export_missing_database)) }
                return
            }
            val directoryName = legacyExportDirectoryName ?: newLegacyExportDirectoryName(command).also {
                legacyExportDirectoryName = it
            }
            val work = File(getApplication<Application>().cacheDir,
                "legacy-export-$command-${System.nanoTime()}")
            if (work.exists()) {
                throw java.io.IOException("Export work path already exists")
            }
            var result: com.breakyuna.noveltranslator.legacy.LegacyExportResult? = null
            try {
                result = graph.legacyExports.exportAll(
                    legacyDatabase = legacyDatabase,
                    outputDirectory = work,
                    onProgress = { progress ->
                        update { current ->
                            current.copy(status = NativeLegacyExportStatus.RUNNING, progress = progress)
                        }
                    },
                    onFailure = { failure ->
                        update { current -> current.copy(failures = current.failures + failure) }
                    },
                )
                withContext(Dispatchers.IO) { copyLegacyExportToTree(tree, work, directoryName) }
            } finally {
                withContext(Dispatchers.IO + NonCancellable) { work.deleteRecursively() }
            }
            val completed = requireNotNull(result)
            update { current ->
                val rootName = current.outputDisplayName?.substringBeforeLast('/')?.takeIf { it.isNotBlank() }
                current.copy(
                    status = NativeLegacyExportStatus.COMPLETED,
                    progress = LegacyExportProgress(
                        phase = com.breakyuna.noveltranslator.legacy.LegacyExportPhase.COMPLETED,
                        completed = completed.manifest.files.size,
                        total = completed.manifest.files.size,
                    ),
                    failures = completed.failures,
                    result = completed,
                    outputDisplayName = if (rootName == null) directoryName else "$rootName/$directoryName",
                    message = null,
                )
            }
            // The export itself does not touch projects; refresh their registry view anyway so a
            // completed background operation never leaves a stale app-level list.
            runCatching { withContext(Dispatchers.IO) { projects.registeredProjects() } }
                .onSuccess { registered -> mutable.value = mutable.value.copy(projects = registered) }
        } catch (cancelled: CancellationException) {
            update {
                it.copy(status = NativeLegacyExportStatus.CANCELLED,
                    message = getApplication<Application>().getString(R.string.native_legacy_export_cancelled))
            }
            throw cancelled
        } catch (failure: LegacyExportLimitException) {
            update {
                it.copy(status = NativeLegacyExportStatus.FAILED,
                    message = getApplication<Application>().getString(R.string.native_legacy_export_limit))
            }
        } catch (_: Exception) {
            update {
                it.copy(status = NativeLegacyExportStatus.FAILED,
                    message = getApplication<Application>().getString(R.string.native_legacy_export_unavailable))
            }
        } finally {
            if (legacyExportRunToken == token) {
                legacyExportJob = null
                legacyExportRunToken = null
            }
        }
    }

    private fun newLegacyExportDirectoryName(command: String): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return "lnt-legacy-export-${format.format(java.util.Date())}-${command.take(8)}"
    }

    /**
     * Copies the completed private package into one newly created SAF directory. The provider is
     * never asked to overwrite: an existing name is rejected, a renamed child aborts the copy, and
     * only this export's new directory is removed if the copy fails or is cancelled.
     */
    private suspend fun copyLegacyExportToTree(tree: Uri, source: File, directoryName: String): String =
        withContext(Dispatchers.IO) {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            if (queryChildNames(tree, rootId).any { it.equals(directoryName, ignoreCase = true) }) {
                throw java.io.IOException("Export directory already exists")
            }
            val created = DocumentsContract.createDocument(
                resolver,
                root,
                DocumentsContract.Document.MIME_TYPE_DIR,
                directoryName,
            ) ?: throw java.io.IOException("Unable to create export directory")
            var published = false
            try {
                if (queryDisplayName(created) != directoryName) {
                    throw java.io.IOException("Provider renamed the export directory")
                }
                copyExportDirectory(source, created)
                published = true
            } finally {
                if (!published) runCatching { DocumentsContract.deleteDocument(resolver, created) }
            }
            directoryName
        }

    private suspend fun copyExportDirectory(source: File, parent: Uri) {
        val root = source.canonicalFile
        val rootPrefix = root.path + File.separator
        val coroutine = currentCoroutineContext()
        val entries = source.listFiles() ?: throw java.io.IOException("Unable to read the export package")
        entries.sortedBy { it.name }.forEach { file ->
            coroutine.ensureActive()
            val canonical = file.canonicalFile
            if (canonical != root && !canonical.path.startsWith(rootPrefix)) {
                throw java.io.IOException("Export file escaped its directory")
            }
            val mime = if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR
            else legacyExportMimeType(file.name)
            val child = DocumentsContract.createDocument(resolver, parent, mime, file.name)
                ?: throw java.io.IOException("Unable to create export entry")
            if (queryDisplayName(child) != file.name) {
                throw java.io.IOException("Provider renamed an export entry")
            }
            if (file.isDirectory) {
                copyExportDirectory(file, child)
            } else {
                resolver.openOutputStream(child, "wt")?.use { output ->
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutine.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw java.io.IOException("Unable to write export entry")
            }
        }
    }

    private fun legacyExportMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "json" -> "application/json"
        "txt" -> "text/plain"
        "epub" -> "application/epub+zip"
        "csv" -> "text/csv"
        else -> "application/octet-stream"
    }

    private fun queryChildNames(tree: Uri, documentId: String): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val names = HashSet<String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let { names += it }
            }
        } ?: throw java.io.IOException("Unable to list the export directory")
        return names
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun queryTreeDisplayName(uri: Uri): String? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(uri)
        queryDisplayName(DocumentsContract.buildDocumentUriUsingTree(uri, id))
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }

    fun beginProjectImport(): Boolean {
        if (mutable.value.busy) return false
        importCandidates = emptyList()
        projectImport.value = NativeProjectImportUiState()
        projectImportVisible.value = true
        for (key in PROJECT_IMPORT_BINDING_KEYS) saved.remove<Any>(key)
        return true
    }

    fun addProjectImportFiles(uris: List<Uri>) {
        if (uris.isEmpty() || projectImport.value.busy || mutable.value.busy) return
        viewModelScope.launch {
            projectImport.update { it.copy(busy = true, error = null) }
            try {
                val documents = withContext(Dispatchers.IO) { uris.map(::readImportDocument) }
                importCandidates = importCandidates + documents.map { document ->
                    NativeImportCandidate(
                        sourceId = document.uri.toString(),
                        fileName = document.name,
                        sizeBytes = document.size,
                    )
                }
                refreshProjectImportReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                projectImport.update {
                    it.copy(error = getApplication<Application>().getString(R.string.native_import_read_failed))
                }
            } finally {
                projectImport.update { it.copy(busy = false) }
            }
        }
    }

    fun addProjectImportDirectory(uri: Uri) {
        if (projectImport.value.busy || mutable.value.busy) return
        viewModelScope.launch {
            projectImport.update { it.copy(busy = true, error = null) }
            try {
                val documents = withContext(Dispatchers.IO) { enumerateImportDirectory(uri) }
                if (documents.isEmpty()) {
                    projectImport.update {
                        it.copy(error = getApplication<Application>().getString(R.string.native_import_directory_empty))
                    }
                    return@launch
                }
                importCandidates = importCandidates + documents.map { document ->
                    NativeImportCandidate(
                        sourceId = document.uri.toString(),
                        fileName = document.name,
                        sizeBytes = document.size,
                    )
                }
                refreshProjectImportReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                projectImport.update {
                    it.copy(error = getApplication<Application>().getString(R.string.native_import_read_failed))
                }
            } finally {
                projectImport.update { it.copy(busy = false) }
            }
        }
    }

    fun removeProjectImportCandidate(sourceId: String) {
        if (projectImport.value.busy) return
        importCandidates = importCandidates.filterNot { it.sourceId == sourceId }
        viewModelScope.launch {
            projectImport.update { it.copy(busy = true, error = null) }
            try {
                refreshProjectImportReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                projectImport.update {
                    it.copy(error = getApplication<Application>().getString(R.string.native_import_read_failed))
                }
            } finally {
                projectImport.update { it.copy(busy = false) }
            }
        }
    }

    fun cancelProjectImport() {
        if (projectImport.value.busy) return
        projectImportVisible.value = false
        projectImport.value = NativeProjectImportUiState()
        importCandidates = emptyList()
        for (key in PROJECT_IMPORT_BINDING_KEYS) saved.remove<Any>(key)
    }

    /**
     * Revalidates the frozen review against the current registry, then creates exactly one new
     * project. All store calls use deterministic command ids so a retried submission reuses prior
     * receipts instead of creating a duplicate project or duplicate assets.
     */
    internal fun createProjectFromImport(review: NativeProjectImportReview) {
        if (projectImport.value.busy || !review.canCreate || review != projectImport.value.review) return
        action {
            projectImport.update { it.copy(busy = true, error = null) }
            try {
                val digest = projectImportDigest(review)
                val priorDigest = saved.get<String>("native_import_digest")
                val priorRef = if (priorDigest == digest) {
                    saved.get<ArrayList<String>>("native_import_ref")?.let(::decodeRef)
                } else {
                    null
                }
                if (priorRef != null) {
                    val existing = runCatching { projects.open(priorRef) }.getOrNull()
                    if (existing != null) {
                        mutable.value = mutable.value.copy(
                            projects = withContext(Dispatchers.IO) { projects.registeredProjects() },
                        )
                        openInternal(existing.ref)
                        projectImportVisible.value = false
                        projectImport.value = NativeProjectImportUiState()
                        mutable.value = mutable.value.copy(notice = R.string.native_import_created)
                        return@action
                    }
                }
                val names = withContext(Dispatchers.IO) {
                    projects.registeredProjects().mapNotNull { it.summary?.name }.toSet()
                }
                val fresh = reviewNativeProjectImport(labeledImportCandidates(), names)
                if (fresh != review) {
                    projectImport.update {
                        it.copy(review = fresh,
                            error = getApplication<Application>().getString(R.string.native_import_review_changed))
                    }
                    return@action
                }
                val command = if (priorDigest == digest) {
                    saved.get<String>("native_import_command") ?: commandId().value
                } else {
                    commandId().value
                }
                saved["native_import_digest"] = digest
                saved["native_import_command"] = command
                saved.remove<ArrayList<String>>("native_import_ref")
                val created = createProjectFromImportReview(
                    review = review,
                    command = CommandId(command),
                    readBytes = ::readProjectImportBytes,
                )
                mutable.value = mutable.value.copy(
                    projects = withContext(Dispatchers.IO) { projects.registeredProjects() },
                )
                if (created.failures.isEmpty()) saved["native_import_ref"] = encodeRef(created.ref)
                val opened = runCatching { openInternal(created.ref) }
                if (created.failures.isEmpty() && opened.isSuccess) {
                    projectImportVisible.value = false
                    projectImport.value = NativeProjectImportUiState()
                    mutable.value = mutable.value.copy(notice = R.string.native_import_created)
                } else {
                    val failures = created.failures +
                        if (opened.isFailure) listOf(getApplication<Application>().getString(R.string.native_import_open_failed)) else emptyList()
                    projectImport.update {
                        it.copy(error = getApplication<Application>().getString(
                            R.string.native_import_partial_failure,
                            failures.joinToString("\n"),
                        ))
                    }
                }
            } catch (cancelled: CancellationException) {
                projectImport.update {
                    it.copy(error = getApplication<Application>().getString(R.string.native_import_cancelled))
                }
                throw cancelled
            } catch (failure: Exception) {
                projectImport.update {
                    it.copy(error = projectImportErrorMessage(failure))
                }
                throw failure
            } finally {
                projectImport.update { it.copy(busy = false) }
            }
        }
    }

    internal suspend fun createProjectFromImportReview(
        review: NativeProjectImportReview,
        command: CommandId,
        readBytes: suspend (NativeImportFile) -> ByteArray,
    ): NativeProjectImportCreation {
        require(review.canCreate)
        val first = review.files.first()
        val projectName = requireNotNull(review.projectName)
        val failures = ArrayList<String>()
        val summary = when (review.seed) {
            NativeProjectSeed.TXT_TEXT -> {
                val bytes = readBytes(first)
                projects.createTxt(command, projectName, RelativePath(first.fileName), bytes,
                    mutable.value.encoding)
            }
            NativeProjectSeed.EPUB_FIRST_ASSET -> {
                val firstBytes = readBytes(first)
                val seedPath = RelativePath(NATIVE_IMPORT_SEED_PATH)
                val seed = projects.createTxt(CommandId("${command.value}:seed"), projectName,
                    seedPath, ByteArray(0), TxtEncoding.UTF8)
                val seedAsset = seed.assets.firstOrNull {
                    it.path == seedPath && it.format == ProjectAssetFormat.TXT
                }
                val imported = try {
                    graph.formatProjects.importAsset(
                        CommandId("${command.value}:asset:0"),
                        seed.ref,
                        0L,
                        RelativePath(first.fileName),
                        firstBytes,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return NativeProjectImportCreation(seed.ref, listOf(first.fileName))
                }
                requireCommittedAsset(imported)
                if (seedAsset != null) {
                    try {
                        val assets = projects as? ProjectAssetPort
                            ?: throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                        requireCommittedAsset(assets.removeAsset(
                            CommandId("${command.value}:seed-remove"),
                            seed.ref,
                            1L,
                            seedAsset.id,
                        ))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return NativeProjectImportCreation(
                            seed.ref,
                            listOf(getApplication<Application>().getString(R.string.native_import_seed_cleanup_failed)),
                        )
                    }
                }
                seed
            }
            null -> throw TextProjectException(TextProjectException.Reason.INVALID_FORMAT)
        }
        var expectedRevision = if (review.seed == NativeProjectSeed.TXT_TEXT) 0L else 2L
        for (file in review.files.drop(1)) {
            try {
                val bytes = readBytes(file)
                val receipt = graph.formatProjects.importAsset(
                    CommandId("${command.value}:asset:${file.order}"),
                    summary.ref,
                    expectedRevision,
                    RelativePath(file.fileName),
                    bytes,
                )
                requireCommittedAsset(receipt)
                expectedRevision = receipt.revision
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures += file.fileName
            }
        }
        return NativeProjectImportCreation(summary.ref, failures.toList())
    }

    private fun requireCommittedAsset(receipt: AssetMutationReceipt) {
        when (receipt.status) {
            AssetMutationStatus.COMMITTED -> Unit
            AssetMutationStatus.STALE_REVISION -> throw TextProjectException(
                TextProjectException.Reason.STALE_PAGE)
            else -> throw TextProjectException(TextProjectException.Reason.COMMAND_CONFLICT)
        }
    }

    private suspend fun readProjectImportBytes(file: NativeImportFile): ByteArray =
        withContext(Dispatchers.IO) {
            val limit = if (file.format == NativeImportFormat.TXT) {
                minOf(NATIVE_IMPORT_MAX_ASSET_BYTES, TextProjectLimits().maxSourceBytes.toLong())
            } else {
                NATIVE_IMPORT_MAX_ASSET_BYTES
            }
            val uri = Uri.parse(file.sourceId)
            val stream = resolver.openInputStream(uri)
                ?: throw java.io.IOException("Import source is unavailable")
            val bytes = try {
                readBoundedBytes(stream, limit)
            } finally {
                runCatching { stream.close() }
            }
            if (bytes.size.toLong() != file.sizeBytes) {
                throw java.io.IOException("Import source changed after review")
            }
            bytes
        }

    private suspend fun refreshProjectImportReview() {
        val names = withContext(Dispatchers.IO) {
            projects.registeredProjects().mapNotNull { it.summary?.name }.toSet()
        }
        projectImport.update {
            it.copy(review = reviewNativeProjectImport(labeledImportCandidates(), names), error = null)
        }
    }

    private fun labeledImportCandidates(): List<NativeImportCandidate> {
        val encoding = mutable.value.encoding.charsetName
        return importCandidates.map { candidate ->
            candidate.copy(
                encoding = if (detectNativeImportFormat(candidate.fileName) == NativeImportFormat.TXT) {
                    encoding
                } else {
                    null
                },
            )
        }
    }

    private fun projectImportDigest(review: NativeProjectImportReview): String =
        TextProjectFingerprint.bytes(buildString {
            append("native-project-import-v1\n")
            append(review.projectName).append('\n')
            append(review.seed).append('\n')
            review.files.forEach { file ->
                append(file.sourceId).append('\n')
                append(file.fileName).append('\n')
                append(file.format).append('\n')
                append(file.sizeBytes).append('\n')
                append(file.encoding).append('\n')
            }
        }.toByteArray(Charsets.UTF_8))

    private fun projectImportErrorMessage(failure: Exception): String =
        when ((failure as? TextProjectException)?.reason) {
            TextProjectException.Reason.LIMIT_EXCEEDED ->
                getApplication<Application>().getString(R.string.native_import_error_limit)
            TextProjectException.Reason.INVALID_TEXT,
            TextProjectException.Reason.INVALID_FORMAT ->
                getApplication<Application>().getString(R.string.native_import_error_format)
            TextProjectException.Reason.COMMAND_CONFLICT,
            TextProjectException.Reason.STALE_PAGE ->
                getApplication<Application>().getString(R.string.native_import_error_conflict)
            else -> getApplication<Application>().getString(R.string.native_import_error_generic)
        }

    private data class ImportDocument(val uri: Uri, val name: String, val size: Long)

    private fun readImportDocument(uri: Uri): ImportDocument {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        }
        val displayName = name?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "import.bin"
        val bytes = size?.takeIf { it >= 0L } ?: countBoundedBytes(uri, NATIVE_IMPORT_MAX_ASSET_BYTES)
        return ImportDocument(uri, displayName, bytes)
    }

    private fun enumerateImportDirectory(tree: Uri): List<ImportDocument> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(rootId to 0)
        val documents = ArrayList<ImportDocument>()
        while (pending.isNotEmpty() && documents.size < 200) {
            val (documentId, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < 4) pending.add(childId to depth + 1)
                        continue
                    }
                    if (detectNativeImportFormat(name) == null) continue
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                    val reported = if (cursor.isNull(3)) -1L else cursor.getLong(3)
                    val size = if (reported < 0L) {
                        countBoundedBytes(childUri, NATIVE_IMPORT_MAX_ASSET_BYTES)
                    } else {
                        reported
                    }
                    documents += ImportDocument(childUri, name, size)
                    if (documents.size >= 200) break
                }
            } ?: throw java.io.IOException("Unable to list the selected directory")
        }
        return documents.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun countBoundedBytes(uri: Uri, limit: Long): Long {
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) break
            }
        } ?: throw java.io.IOException("Import source is unavailable")
        return total
    }

    private suspend fun readBoundedBytes(input: java.io.InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        val coroutine = currentCoroutineContext()
        while (true) {
            coroutine.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size().toLong() + read > limit) {
                throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
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
            if (!isNativeFormatActive(input.first)) {
                mutable.value = mutable.value.copy(error = R.string.native_format_frozen)
                return@queuedAction
            }
            val receipt = graph.formatProjects.importAsset(CommandId(intent[0]), project.ref, project.revision,
                RelativePath(input.first), input.second)
            if (receipt.status != AssetMutationStatus.COMMITTED) {
                mutable.value = mutable.value.copy(error = R.string.native_conflict)
                return@queuedAction
            }
            saved.remove<ArrayList<String>>("native_add_asset")
            openInternal(project.ref)
            formatImportNotice(receipt.warnings)?.let { warning ->
                mutable.value = mutable.value.copy(notice = warning)
            }
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
        val section = when (page) {
            NativePage.PROMPTS -> NativeRuleSection.PROMPTS
            NativePage.RULES -> runCatching {
                NativeRuleSection.valueOf(saved["native_rules_last_section"] ?: mutable.value.rulesSection.name)
            }.getOrDefault(NativeRuleSection.GLOSSARY).takeUnless { it == NativeRuleSection.PROMPTS } ?: NativeRuleSection.GLOSSARY
            else -> mutable.value.rulesSection
        }
        mutable.value = mutable.value.copy(page = page, rulesSection = section)
        saved["native_rules_section"] = section.name
        if (section != NativeRuleSection.PROMPTS) saved["native_rules_last_section"] = section.name
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

    /** Open a rules or prompt destination while preserving the shared unsaved policy. */
    fun openRules(section: NativeRuleSection) {
        if ((mutable.value.busy && !mutable.value.foregroundExecutionActive) ||
            mutable.value.editor != null || mutable.value.manualBatch != null) return
        mutable.value = mutable.value.copy(page = if (section == NativeRuleSection.PROMPTS) NativePage.PROMPTS else NativePage.RULES, rulesSection = section)
        saved["native_page"] = mutable.value.page.name
        saved["native_rules_section"] = section.name
        if (section != NativeRuleSection.PROMPTS) saved["native_rules_last_section"] = section.name
    }

    /** Section navigation only changes the visible section; the policy draft and page stay put. */
    fun selectRulesSection(section: NativeRuleSection) {
        if (mutable.value.page != NativePage.RULES && mutable.value.page != NativePage.PROMPTS) return
        mutable.value = mutable.value.copy(rulesSection = section, page = if (section == NativeRuleSection.PROMPTS) NativePage.PROMPTS else NativePage.RULES)
        saved["native_page"] = mutable.value.page.name
        saved["native_rules_section"] = section.name
        if (section != NativeRuleSection.PROMPTS) saved["native_rules_last_section"] = section.name
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
        if (!current.loaded || current.loading || current.loadFailed || current.saving ||
            proxyCredentialState.value.busy) return
        val settings = try {
            val parsed = input.toSettings()
            // Preserve an attached credential only for the same proxy endpoint; changing the
            // host or port must not silently send the old secret to a different server.
            parsed.copy(proxyCredentialRef = current.settings.proxyCredentialRef
                ?.takeIf { parsed.proxy == current.settings.proxy })
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

    /**
     * Saves the non-secret network form and applies one credential disposition in one
     * fingerprint-checked command. A new credential is written before the reference so a
     * model request can never observe a reference without its file; if the settings commit
     * fails, the new orphan is removed best-effort. Passwords stay in local coroutine memory.
     */
    internal fun saveNetworkWithProxyCredential(
        input: NativeNetworkSettingsInput,
        change: NativeProxyCredentialChange,
    ) {
        val current = mutable.value.networkSettingsState
        if (!current.loaded || current.loading || current.loadFailed || current.saving ||
            proxyCredentialState.value.busy) return
        val parsed = try {
            input.toSettings()
        } catch (_: IllegalArgumentException) {
            proxyCredentialState.update { it.copy(error = R.string.native_settings_proxy_invalid,
                notice = null) }
            return
        }
        val replacement = change as? NativeProxyCredentialChange.Replace
        val credential = if (replacement == null) null else {
            if (parsed.proxy == null) {
                proxyCredentialState.update {
                    it.copy(error = R.string.native_settings_proxy_auth_requires_proxy, notice = null) }
                return
            }
            try {
                ModelProxyCredential.of(replacement.username, replacement.password)
            } catch (_: IllegalArgumentException) {
                proxyCredentialState.update { it.copy(error = R.string.native_settings_proxy_invalid,
                    notice = null) }
                return
            }
        }
        val oldReference = current.settings.proxyCredentialRef
        proxyCredentialState.update { it.copy(busy = true, clearing = false, error = null, notice = null) }
        viewModelScope.launch {
            var newReference: String? = null
            try {
                val targetReference = when (change) {
                    // Keep the reference for the same endpoint or for later use with the proxy
                    // switched off; a different host never receives the old secret.
                    NativeProxyCredentialChange.Keep -> oldReference?.takeIf {
                        parsed.proxy == null || parsed.proxy == current.settings.proxy
                    }
                    NativeProxyCredentialChange.DetachKeepFile,
                    NativeProxyCredentialChange.Delete -> null
                    is NativeProxyCredentialChange.Replace -> UUID.randomUUID().toString().also { reference ->
                        withContext(Dispatchers.IO) {
                            proxyCredentials.save(reference, requireNotNull(credential))
                        }
                        newReference = reference
                    }
                }
                val saved = withContext(Dispatchers.IO) {
                    settingsStorage.saveNetworkSettings(
                        parsed.copy(proxyCredentialRef = targetReference), current.fingerprint)
                }
                val deleteOld = oldReference != null && oldReference != newReference &&
                    (change is NativeProxyCredentialChange.Replace ||
                        change == NativeProxyCredentialChange.Delete)
                val oldDeleted = if (deleteOld) withContext(Dispatchers.IO) {
                    runCatching { proxyCredentials.clear(requireNotNull(oldReference)) }.isSuccess
                } else true
                mutable.value = mutable.value.copy(
                    networkSettingsState = NativeNetworkSettingsState(settings = saved.settings,
                        fingerprint = saved.fingerprint, loaded = true, loading = false,
                        revision = current.revision + 1),
                    notice = R.string.native_settings_network_saved)
                proxyCredentialState.update { it.copy(busy = false, clearing = false,
                    error = if (!oldDeleted) R.string.native_settings_proxy_clear_failed else null,
                    notice = when {
                        change is NativeProxyCredentialChange.Replace ->
                            R.string.native_settings_proxy_saved_notice
                        change == NativeProxyCredentialChange.Delete && oldDeleted ->
                            R.string.native_settings_proxy_cleared
                        change == NativeProxyCredentialChange.DetachKeepFile ->
                            R.string.native_settings_proxy_detached_notice
                        else -> null
                    },
                    revision = it.revision + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                newReference?.let { reference ->
                    withContext(Dispatchers.IO + NonCancellable) {
                        runCatching { proxyCredentials.clear(reference) }
                    }
                }
                val conflict = applyNetworkSaveFailure(current)
                proxyCredentialState.update { it.copy(busy = false, clearing = false,
                    error = if (conflict) R.string.native_settings_network_conflict
                    else R.string.native_settings_proxy_credential_failed,
                    notice = null) }
            }
        }
    }

    /** Idempotent clear: deleting the reference and the encrypted file is safe to repeat. */
    internal fun clearProxyCredential() {
        val current = mutable.value.networkSettingsState
        if (!current.loaded || current.loading || current.loadFailed || current.saving ||
            proxyCredentialState.value.busy) return
        val reference = current.settings.proxyCredentialRef
        if (reference == null) {
            proxyCredentialState.update { it.copy(error = null,
                notice = R.string.native_settings_proxy_cleared) }
            return
        }
        proxyCredentialState.update { it.copy(busy = true, clearing = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    settingsStorage.saveNetworkSettings(
                        current.settings.copy(proxyCredentialRef = null), current.fingerprint)
                }
                val cleared = withContext(Dispatchers.IO) {
                    runCatching { proxyCredentials.clear(reference) }.isSuccess
                }
                mutable.value = mutable.value.copy(
                    networkSettingsState = NativeNetworkSettingsState(settings = saved.settings,
                        fingerprint = saved.fingerprint, loaded = true, loading = false,
                        revision = current.revision + 1),
                    notice = if (cleared) R.string.native_settings_proxy_cleared else null)
                proxyCredentialState.update { it.copy(busy = false, clearing = false,
                    error = if (cleared) null else R.string.native_settings_proxy_clear_failed,
                    notice = if (cleared) R.string.native_settings_proxy_cleared else null,
                    revision = it.revision + 1) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val conflict = applyNetworkSaveFailure(current)
                proxyCredentialState.update { it.copy(busy = false, clearing = false,
                    error = if (conflict) R.string.native_settings_network_conflict
                    else R.string.native_settings_proxy_credential_failed,
                    notice = null) }
            }
        }
    }

    /** Applies the same conflict/fail-closed projection as a network save failure. */
    private suspend fun applyNetworkSaveFailure(current: NativeNetworkSettingsState): Boolean {
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
        return conflict
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
        clearAgentAttachments()
        saved.remove<ArrayList<String>>("native_policy_draft")
        saved.remove<String>("native_rules_section")
        saved.remove<String>("native_rules_last_section")
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
            agentMessages = emptyList(), approval = null, agentResults = emptyList(), itemProposals = emptyList(), policyProposals = emptyList(), pdfPageProposals = emptyList(), assetImportProposals = emptyList(), workspaceChangeProposals = emptyList(), mixedApproval = null, mixedApprovalMembers = emptyList(), mixedApprovalError = null, workspaceUploadRevision = 0, recentRuns = emptyList(), runLoadIncomplete = false,
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
        prepareQueryTextChange(options, null, TextBatchChange.REPLACE_TRANSLATION, expected)
    internal fun prepareQueryNameAssignment(names: List<String>, expected: NativeQueryReplacementScope,
        change: TextBatchChange = TextBatchChange.ASSIGN_NAMES) =
        prepareQueryTextChange(null, names.toList(), change, expected)
    private fun prepareQueryTextChange(options: TextReplacementOptions?, names: List<String>?,
        nameChange: TextBatchChange, expected: NativeQueryReplacementScope) = action {
        val current = mutable.value
        val project = current.project ?: return@action
        require(names == null || nameChange in setOf(TextBatchChange.ASSIGN_NAMES, TextBatchChange.ASSIGN_SOURCE_NAMES))
        if (project.ref != expected.ref || project.revision != expected.revision || current.proofreadingQuery != expected.query ||
            current.qualityView !== expected.quality) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        if (current.editor != null || current.manualBatch != null || current.selectingItems ||
            current.policyDraft?.dirty == true || current.policyLoadIncomplete || current.runLoadIncomplete) return@action
        val quality = current.qualityView
        if (quality != null && !current.qualityCurrent) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
        val port = projects as? QueryManualBatchPort ?: return@action
        val batch = when {
            names != null && nameChange == TextBatchChange.ASSIGN_SOURCE_NAMES ->
                port.prepareSourceNameAssignmentBatch(commandId(), project.ref, project.revision,
                    current.proofreadingQuery, names, quality)
            names != null -> port.prepareNameAssignmentBatch(commandId(), project.ref, project.revision,
                current.proofreadingQuery, names, quality)
            else -> port.prepareReplacementBatch(commandId(), project.ref, project.revision,
                current.proofreadingQuery, requireNotNull(options), quality)
        }
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
            if (batch.nameAssignment == null && batch.replacement == null && applyPreview && item.id in affectedIds) ItemPreview(item.id, item.row, item.source,
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
        val key = draftUndoKey(draft)
        val snapshot = draftUndoSnapshot(draft)
        val restored = if (forward) draftUndoHistory.redo(key, snapshot) else draftUndoHistory.undo(key, snapshot)
        if (restored != null) {
            // A fresh command: undo/redo never reuses an earlier save authorization.
            setDraft(draft.changed(text = restored.text, status = restored.status), recordHistory = false)
            persistDraftHistory()
        }
    }

    /** Keeps the open draft but drops every stored/session undo/redo state behind it. */
    fun clearEditorHistory() {
        if (mutable.value.busy) return
        val draft = mutable.value.editor ?: return
        val key = draftUndoKey(draft)
        draftUndoHistory.reset(key, draftUndoSnapshot(draft))
        draftUndoStore.requestClear(key)
        mutable.value = mutable.value.copy(editorCanUndo = false, editorCanRedo = false,
            editorHistoryRestored = 0)
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
            graph.backgroundAgents.state.value?.takeIf { it.phase.active }?.request?.ref != ref &&
            graph.backgroundWorkspaceExecutions.state.value?.takeIf { it.phase.active }?.request?.ref != ref)
            throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
        cursors.clear(); cursors.add(0)
        val same = restoreRef() == ref
        var selectedPage = if (same) runCatching { NativePage.valueOf(saved["native_page"] ?: "AGENT") }.getOrDefault(NativePage.AGENT) else NativePage.AGENT
        val selectedSection = if (selectedPage == NativePage.PROMPTS) NativeRuleSection.PROMPTS else if (same) runCatching {
            NativeRuleSection.valueOf(saved["native_rules_section"] ?: NativeRuleSection.GLOSSARY.name)
        }.getOrDefault(NativeRuleSection.GLOSSARY) else NativeRuleSection.GLOSSARY
        if (selectedPage == NativePage.RULES && selectedSection == NativeRuleSection.PROMPTS) selectedPage = NativePage.PROMPTS
        val selectedSettingsSection = if (same) runCatching {
            NativeSettingsSection.valueOf(saved["native_settings_section"] ?: NativeSettingsSection.BASIC.name)
        }.getOrDefault(NativeSettingsSection.BASIC) else NativeSettingsSection.BASIC
        if (!same || mutable.value.editor != null || mutable.value.nameEdit != null) {
            // Switching project/asset or reloading the same project closes the draft; its
            // journal must not reappear behind the next editor instance.
            clearDraft()
        }
        if (!same) {
            clearAgentAttachments()
            saved.remove<String>("native_rules_section")
            saved.remove<String>("native_rules_last_section")
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
            editor = null, nameEdit = null, editorQuality = null, editorReplacement = null, translationProgress = null,
            policyDraft = null, policyLoadIncomplete = true, policyInvalidRuleId = null,
            selectingItems = false, selectedItems = emptySet(), manualBatch = batch, manualBatchQuery = diskBatch != null,
            manualBatchConflict = batch != null && saved.get<Boolean>("manual_batch_conflict") == true,
            proofreadingQuery = query, qualityView = null, qualityInvalidated = false, qualityProgress = null, itemStatistics = page.statistics, matchingItems = page.matchingItems, matchingStatistics = page.matchingStatistics,
            items = page.items.take(PAGE_SIZE).map { ItemPreview.of(it) }, hasNext = page.items.size > PAGE_SIZE, hasPrevious = false,
            agentMessages = emptyList(), approval = null, agentResults = emptyList(), itemProposals = emptyList(), policyProposals = emptyList(), pdfPageProposals = emptyList(), assetImportProposals = emptyList(), workspaceChangeProposals = emptyList(), mixedApproval = null, mixedApprovalMembers = emptyList(), mixedApprovalError = null, workspaceUploadRevision = 0, recentRuns = emptyList(),
            runLoadIncomplete = true, agentLoadIncomplete = true, agentMessageDetail = null, agentChatId = null, agentInput = "", agentContextStatus = null)
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
        val revision = (saved.get<Long>("draft_revision") ?: details.revision).coerceAtLeast(0)
        val fingerprint = saved.get<String>("draft_fingerprint")
            ?.let { runCatching { ObjectFingerprint(it) }.getOrNull() } ?: details.item.fingerprint
        val assetId = saved.get<Long>("draft_asset")?.takeIf { it > 0 } ?: details.item.assetId
        val savedKey = NativeDraftUndoKey(ref, assetId, revision, ItemId(itemId), fingerprint,
            NativeDraftUndoScope.BODY_STATUS)
        if (receipt?.status == ManualEditStatus.COMMITTED) {
            draftUndoStore.requestClear(savedKey)
            clearDraft()
            mutable.value = mutable.value.copy(notice = R.string.native_saved)
            return
        }
        val conflict = receipt != null || revision != details.revision ||
            fingerprint != details.item.fingerprint
        val draft = NativeEditDraft(ref, details.item, revision, fingerprint,
            saved["draft_text"] ?: details.item.translation,
            saved.get<String>("draft_status")?.let { runCatching { TextItemStatus.valueOf(it) }.getOrNull() }
                ?: details.item.status,
            command, conflict = conflict)
        if (conflict) {
            // A stale revision/fingerprint never restores an older undo stack or save right.
            draftUndoStore.requestClear(savedKey)
            draftUndoHistory.reset(draftUndoKey(draft), draftUndoSnapshot(draft))
            setDraft(draft, recordHistory = false)
            return
        }
        val key = draftUndoKey(draft)
        when (val loaded = withContext(Dispatchers.IO) { draftUndoStore.load(key) }) {
            is NativeDraftUndoLoad.Restored -> {
                if (draftUndoHistory.restore(loaded.timeline, key, draftUndoSnapshot(draft))) {
                    setDraft(draft, recordHistory = false,
                        restoredSteps = draftUndoHistory.undoCount)
                } else {
                    draftUndoHistory.reset(key, draftUndoSnapshot(draft))
                    setDraft(draft, recordHistory = false)
                    draftUndoStore.requestClear(key)
                }
            }
            NativeDraftUndoLoad.Corrupt, NativeDraftUndoLoad.Unsupported -> {
                draftUndoHistory.reset(key, draftUndoSnapshot(draft))
                setDraft(draft, recordHistory = false)
                mutable.value = mutable.value.copy(notice = R.string.native_editor_history_corrupt)
            }
            NativeDraftUndoLoad.Missing, NativeDraftUndoLoad.Stale -> {
                draftUndoHistory.reset(key, draftUndoSnapshot(draft))
                setDraft(draft, recordHistory = false)
            }
        }
    }

    private fun setDraft(draft: NativeEditDraft, recordHistory: Boolean = true,
                         restoredSteps: Int = 0) {
        val key = draftUndoKey(draft)
        val snapshot = draftUndoSnapshot(draft)
        val previousKey = draftUndoHistory.key
        if (recordHistory) {
            val changed = draftUndoHistory.record(key, snapshot)
            if (previousKey != null && previousKey != key) draftUndoStore.requestClear(previousKey)
            if (changed) persistDraftHistory()
        } else if (draftUndoHistory.key != key) {
            if (previousKey != null) draftUndoStore.requestClear(previousKey)
            draftUndoHistory.reset(key, snapshot)
        }
        saved["draft_item"] = draft.item.id.value; saved["draft_revision"] = draft.revision
        saved["draft_asset"] = draft.item.assetId
        saved["draft_fingerprint"] = draft.fingerprint.sha256; saved["draft_text"] = draft.text
        saved["draft_status"] = draft.status.name; saved["draft_command"] = draft.command.value
        mutable.value = mutable.value.copy(editor = draft, editorQuality = null, editorReplacement = null,
            editorCanUndo = draftUndoHistory.canUndo, editorCanRedo = draftUndoHistory.canRedo,
            editorHistoryRestored = restoredSteps)
    }
    private fun clearDraft() {
        val key = draftUndoHistory.key
        draftUndoHistory.clear()
        if (key != null) draftUndoStore.requestClear(key)
        mutable.value = mutable.value.copy(editorQuality = null, editorReplacement = null,
            editorCanUndo = false, editorCanRedo = false, editorHistoryRestored = 0)
        for (savedKey in listOf("draft_item", "draft_revision", "draft_asset", "draft_fingerprint",
            "draft_text", "draft_status", "draft_command")) saved.remove<Any>(savedKey)
    }

    private fun draftUndoKey(draft: NativeEditDraft) = NativeDraftUndoKey(draft.ref,
        draft.item.assetId, draft.revision, draft.item.id, draft.fingerprint,
        NativeDraftUndoScope.BODY_STATUS)

    private fun draftUndoSnapshot(draft: NativeEditDraft) = NativeDraftUndoSnapshot(draft.text,
        draft.status, draft.item.metadata.nameSource.orEmpty(), draft.item.metadata.nameTranslation.orEmpty())

    private fun persistDraftHistory() {
        val timeline = draftUndoHistory.timeline() ?: return
        if (timeline.states.size <= 1) return
        draftUndoStore.requestSave(timeline)
    }

    internal suspend fun awaitDraftUndoWrites() = draftUndoStore.awaitIdle()

    override fun onCleared() {
        // A destroyed ViewModel keeps the journal for the saved draft; explicit close/save clears it.
        draftUndoHistory.timeline()?.takeIf { it.states.size > 1 }?.let(draftUndoStore::requestSave)
        super.onCleared()
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
                "action", error.javaClass.simpleName + when (error) {
                    is AgentModelFailure -> ": ${error.kind.name}"
                    is TextProjectException -> ": ${error.reason.name}"
                    is RuntimeUnavailable -> ": ${error.reason.name}"
                    else -> ""
                })
        }
        mutable.value = mutable.value.copy(
            qualityInvalidated = mutable.value.qualityInvalidated || mutable.value.qualityView != null &&
                error is TextProjectException && error.reason == TextProjectException.Reason.STALE_PAGE,
            policyInvalidRuleId = (error as? InvalidTextRule)?.ruleId ?: mutable.value.policyInvalidRuleId,
            error = when (error) {
                is PdfFormatException -> pdfFormatErrorLabel(error.reason)
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
                is FullArchiveRestoreNotIdle -> R.string.native_full_archive_not_idle
                is RuntimeUnavailable -> R.string.native_busy_error
                is SecurityException -> R.string.native_permission_error
                else -> R.string.native_io_error
            },
        )
    }

    companion object { private const val PAGE_SIZE = 40 }
}
