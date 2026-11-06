package com.breakyuna.noveltranslator.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NativeProjectApp(model: NativeProjectViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val businessProposal by model.agentBusinessProposal.collectAsStateWithLifecycle()
    val currentBusinessProposal = state.project?.ref?.let { ref -> state.agentChatId?.let { chat ->
        businessProposal?.takeIf { it.boundTo(ref, chat) }
    } }
    var businessReviewVisible by remember(currentBusinessProposal?.id) { mutableStateOf(currentBusinessProposal != null) }
    if (businessReviewVisible) currentBusinessProposal?.let { proposal ->
        val reviewChunks by produceState<List<String>>(emptyList(), proposal.digest) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(), proposal.payload).chunked(4096)
            }
        }
        val businessTitle = when (proposal.kind) {
            "install_skill" -> R.string.native_agent_business_kind_install_skill
            "skill_maintenance" -> R.string.native_agent_business_kind_skill_maintenance
            "application_settings" -> R.string.native_agent_business_kind_application_settings
            "project_settings" -> R.string.native_agent_business_kind_project_settings
            "asset_operation" -> R.string.native_agent_business_kind_asset_operation
            "copy_project" -> R.string.native_agent_business_kind_copy_project
            "model_management" -> R.string.native_agent_business_kind_model_management
            "application_export" -> R.string.native_agent_business_kind_application_export
            "application_import" -> R.string.native_agent_business_kind_application_import
            "enqueue_translation" -> R.string.native_agent_business_kind_enqueue_translation
            "control_translation" -> R.string.native_agent_business_kind_control_translation
            "translation_failure_decision" -> R.string.native_agent_business_kind_translation_failure
            "model_profile_save" -> R.string.native_agent_business_kind_model_profile_save
            "rule_preset_save" -> R.string.native_agent_business_kind_rule_preset_save
            "rule_preset_delete" -> R.string.native_agent_business_kind_rule_preset_delete
            "mcp_server_save" -> R.string.native_agent_business_kind_mcp_save
            "mcp_server_delete" -> R.string.native_agent_business_kind_mcp_delete
            "oauth_management" -> R.string.native_agent_business_kind_oauth
            "chat_management" -> R.string.native_agent_business_kind_chat
            "clear_diagnostics" -> R.string.native_agent_business_kind_clear_diagnostics
            else -> R.string.native_agent_business_review
        }
        NativeContentDialog(onDismissRequest = { businessReviewVisible = false },
            title = { Text(stringResource(businessTitle)) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        item(key = "scope") { Text(stringResource(R.string.native_agent_business_scope,
                            state.project!!.name)) }
                        item(key = "description") { Text(stringResource(R.string.native_agent_business_review_description)) }
                        item(key = "status") { Text(stringResource(if (proposal.status == "PENDING")
                            R.string.native_agent_business_pending else R.string.native_agent_business_interrupted)) }
                        items(count = reviewChunks.size, key = { "review-$it" }) { index -> Text(reviewChunks[index]) }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !state.busy && reviewChunks.isNotEmpty() && proposal.status == "PENDING", onClick = {
                model.decideAgentBusinessProposal(proposal, true)
            }) { Text(stringResource(R.string.native_agent_allow_once)) } },
            dismissButton = { TextButton(enabled = !state.busy, onClick = {
                model.decideAgentBusinessProposal(proposal, false)
            }) { Text(stringResource(if (proposal.status == "PENDING") R.string.native_agent_reject else R.string.native_close)) } })
    }

    val pageStates = rememberSaveableStateHolder()
    var manageActions by remember { mutableStateOf(false) }
    val workspaceExecution by model.skillExecutionActions.state.collectAsStateWithLifecycle()
    val importState by model.projectImportState.collectAsStateWithLifecycle()
    val importVisible by model.projectImportVisibleState.collectAsStateWithLifecycle()
    val legacyExportState by model.legacyExportState.collectAsStateWithLifecycle()
    var legacyExportVisible by remember { mutableStateOf(false) }
    var tasksVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var queueVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var queueRefresh by rememberSaveable(state.project?.ref) { mutableStateOf(0) }
    var failureReview by remember(state.project?.ref) { mutableStateOf<TranslationTaskQueueIdentity?>(null) }
    val taskPanels: @Composable () -> Unit = {
        if (queueVisible && failureReview == null && state.project != null) NativeTranslationQueueDialog(
            load = model::translationQueueSnapshot, ref = state.project!!.ref,
            resume = model::resumeQueuedTranslation, cancel = model::cancelQueuedTranslation,
            review = { failureReview = it }, refreshKey = queueRefresh,
            dismiss = { queueVisible = false })
        failureReview?.let { identity ->
            NativeTranslationFailureReviewDialog(
                identity = identity,
                loadTask = model::translationQueueTask,
                loadEvidence = { target, after -> model.translationFailureEvidence(target, after) },
                retry = { target, batch, members ->
                    model.retryTranslationFailureMembers(target, batch, members).await()
                },
                skip = { target, batch, members ->
                    model.skipTranslationFailureMembers(target, batch, members).await()
                },
                keep = { target, batch, members ->
                    model.keepTranslationFailureMembers(target, batch, members).await()
                },
                terminate = { target -> model.terminateTranslationFailureTask(target).await() },
                dismiss = { failureReview = null; queueRefresh++ },
                onChanged = { queueRefresh++ },
            )
        }
        if (tasksVisible && failureReview == null && !queueVisible && state.project != null) NativeTranslationTasksDialog(state.project!!.ref,
            model::translationTaskSummaries, model::inspectTranslationResume,
            { tasksVisible = false }, { spec -> tasksVisible = false; model.resumeTranslationTask(spec) },
            loadPage = model::translationTaskPage,
            inspectReview = model::reviewTranslationTask, terminate = model::terminateTranslationTask,
            loadMembers = model::translationTaskMembers, prepareRetry = model::prepareTaskRetry,
            keepErrors = model::keepTaskErrors, queueManagedRuns = state.queueManagedRuns,
            queueReviewableRuns = state.queueManagedTasks.values.filter {
                it.state.awaitingDecision && it.batches.isNotEmpty()
            }.mapTo(HashSet()) { it.identity.run.runId.value },
            reviewQueueTask = { run ->
                tasksVisible = false
                val task = state.queueManagedTasks[run.runId.value]
                if (task != null && task.state.awaitingDecision && task.batches.isNotEmpty())
                    failureReview = task.identity
                else queueVisible = true
            })
    }
    var usageVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var settingsVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var appearanceVisible by rememberSaveable { mutableStateOf(false) }
    var agentSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var reviewedItemProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<ItemProposalSummary?>(null) }
    var reviewedPolicyProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<PolicyApprovalSummary?>(null) }
    var reviewedPdfPageProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<PdfPageProposalSummary?>(null) }
    var reviewedAssetImportProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<AssetImportProposalSummary?>(null) }
    var reviewedWorkspaceChange by remember(state.project?.ref, state.agentChatId) { mutableStateOf<WorkspaceChangeProposalSummary?>(null) }
    var mixedReviewVisible by remember(state.project?.ref, state.agentChatId) { mutableStateOf(false) }
    LaunchedEffect(state.mixedApproval?.id) {
        if (state.mixedApproval != null) mixedReviewVisible = true
    }
    if (mixedReviewVisible) state.mixedApproval?.let { approval ->
        NativeMixedApprovalDialog(
            snapshot = NativeMixedApprovalSnapshot(approval, state.mixedApprovalMembers),
            busy = state.busy,
            error = state.mixedApprovalError,
            allow = model::acceptMixedApproval,
            reject = model::rejectMixedApproval,
            dismiss = { mixedReviewVisible = false },
        )
    }
    // The wide rules page keeps unapplied fields in local state; block navigation until it closes.
    var ruleEditorOpen by remember(state.project?.ref) { mutableStateOf(false) }
    reviewedWorkspaceChange?.let { proposal -> NativeWorkspaceChangeDialog(proposal,
        { model.allowWorkspaceChange(); reviewedWorkspaceChange = null },
        { model.rejectWorkspaceChange(); reviewedWorkspaceChange = null },
        { reviewedWorkspaceChange = null }) }
    reviewedAssetImportProposal?.let { proposal -> NativeAssetImportProposalDialog(proposal,
        model::assetImportProposalReceipt, model::acceptAssetImportProposal, model::rejectAssetImportProposal,
        { reviewedAssetImportProposal = null }) }
    reviewedPdfPageProposal?.let { proposal -> NativePdfPageProposalDialog(proposal,
        model::pdfPageProposalMembers, model::pdfPageProposalReceipt,
        model::acceptPdfPageProposal, model::rejectPdfPageProposal, { reviewedPdfPageProposal = null }) }
    reviewedPolicyProposal?.let { proposal -> NativePolicyProposalDialog(proposal, model::policyProposalDetail,
        model::policyProposalReceipt, model::acceptPolicyProposal, model::rejectPolicyProposal, { reviewedPolicyProposal = null },
        select = model::selectPolicyProposal) }
    reviewedItemProposal?.let { proposal -> NativeItemProposalDialog(proposal,
        model::itemProposalMembers, model::itemProposalDetail, model::itemProposalReceipt,
        model::acceptItemProposal, model::rejectItemProposal,
        { reviewedItemProposal = null }) }
    var skillsVisible by rememberSaveable { mutableStateOf(false) }
    val saveLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson"), model::completeDiagnosticsExport)
    val saveAppBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::completeApplicationBackupExport)
    val saveFullArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::completeFullArchiveExport)
    var fullArchiveExportVisible by remember { mutableStateOf(false) }
    var includeFullArchiveDiagnostics by remember { mutableStateOf(false) }
    var pendingFullArchive by remember { mutableStateOf<android.net.Uri?>(null) }
    val openFullArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) pendingFullArchive = it else model.cancelFullArchiveImport()
    }
    if (fullArchiveExportVisible) {
        NativeContentDialog(
            onDismissRequest = { fullArchiveExportVisible = false },
            title = { Text(stringResource(R.string.native_full_archive_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_full_archive_export_confirm))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = includeFullArchiveDiagnostics,
                            onCheckedChange = { includeFullArchiveDiagnostics = it },
                            modifier = Modifier.testTag("native-full-archive-diagnostics"),
                        )
                        Text(stringResource(R.string.native_full_archive_include_diagnostics))
                    }
                    Text(
                        text = stringResource(R.string.native_full_archive_include_diagnostics_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    fullArchiveExportVisible = false
                    model.prepareFullArchiveExport(includeFullArchiveDiagnostics)?.let(saveFullArchive::launch)
                }) { Text(stringResource(R.string.native_full_archive_export)) }
            },
            dismissButton = {
                TextButton(onClick = { fullArchiveExportVisible = false }) {
                    Text(stringResource(R.string.native_cancel))
                }
            },
        )
    }
    pendingFullArchive?.let { uri ->
        NativeContentDialog(
            onDismissRequest = { pendingFullArchive = null; model.cancelFullArchiveImport() },
            title = { Text(stringResource(R.string.native_full_archive_import_title)) },
            text = { Text(stringResource(R.string.native_full_archive_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingFullArchive = null
                    model.importFullArchive(uri)
                }) { Text(stringResource(R.string.native_full_archive_import)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingFullArchive = null
                    model.cancelFullArchiveImport()
                }) {
                    Text(stringResource(R.string.native_cancel))
                }
            },
        )
    }
    var pendingAppBackup by remember { mutableStateOf<android.net.Uri?>(null) }
    val openAppBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) pendingAppBackup = it
    }
    pendingAppBackup?.let { uri -> NativeContentDialog(onDismissRequest = { pendingAppBackup = null },
        title = { Text(stringResource(R.string.native_app_backup_restore_title)) },
        text = { Text(stringResource(R.string.native_app_backup_restore_confirm)) },
        confirmButton = { TextButton(onClick = {
            pendingAppBackup = null
            model.importApplicationBackup(uri)
        }) { Text(stringResource(R.string.native_app_backup_import)) } },
        dismissButton = { TextButton(onClick = { pendingAppBackup = null }) {
            Text(stringResource(R.string.native_cancel))
        } })
    }
    if (appearanceVisible) NativeThemeDialog(state.themeMode, { appearanceVisible = false }, model::setThemeMode)
    if (agentSettingsVisible) NativeAgentSettingsDialog(state.automaticContextSummary,
        { agentSettingsVisible = false }, model::setAutomaticContextSummary)
    val skillNavigation = remember(state.project?.ref) { NativeSkillNavigationGuard() }
    var pendingSkillNavigation by remember(state.project?.ref) { mutableStateOf<(() -> Unit)?>(null) }
    fun navigateFromSkills(action: () -> Unit) {
        when {
            skillNavigation.busy -> Unit
            skillNavigation.dirty -> pendingSkillNavigation = action
            else -> action()
        }
    }
    pendingSkillNavigation?.let { action ->
        NativeContentDialog(onDismissRequest = { pendingSkillNavigation = null },
            title = { Text(stringResource(R.string.native_discard_title)) },
            text = { Text(stringResource(R.string.native_discard_body)) },
            confirmButton = { TextButton(onClick = {
                if (!skillNavigation.busy) {
                    pendingSkillNavigation = null
                    skillsVisible = false
                    action()
                }
            }) { Text(stringResource(R.string.native_discard)) } },
            dismissButton = { TextButton(onClick = { pendingSkillNavigation = null }) {
                Text(stringResource(R.string.native_keep_editing))
            } })
    }
    val skillPanel: @Composable () -> Unit = {
    if (skillsVisible) CompositionLocalProvider(LocalNativeSkillExecution provides model.skillExecutionActions) {
        NativeSkillsDialog(model.skillActions,
            inline = true, dismiss = { skillsVisible = false }, navigationGuard = skillNavigation,
            openPersona = { navigateFromSkills { skillsVisible = false; model.openSettings(NativeSettingsSection.BASIC) } })
    }
    }
    if (settingsVisible && state.project != null) NativeProjectSettingsDialog(state, { settingsVisible = false }, model::saveProjectSettings)
    val saveUsage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson"), model::completeUsageExport)
    val saveWorkspaceArtifact = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*"), model::completeWorkspaceArtifactExport)
    if (usageVisible) NativeUsageLedgerDialog(state.project?.ref, state.agentChatId,
        model::usageSummary, { usageVisible = false }, model::usageEntries,
        loadPrices = model::loadPrices, savePrice = model::savePrice,
        export = if (state.busy) null else { query ->
            val filename = model.prepareUsageExport(query)
            if (filename != null) saveUsage.launch(filename)
            else usageVisible = false // Reveal the rejection message outside the modal dialog.
        })
    var rgssAssetId by rememberSaveable(state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value) { mutableStateOf<Long?>(null) }
    val rgssProject = state.project
    val rgssAsset = rgssAssetId?.let { id -> rgssProject?.assets?.firstOrNull { it.id == id } }
    if (rgssProject != null && rgssAsset != null) NativeRgssInventoryDialog(rgssProject.ref, rgssAsset,
        model::rgssInventory, { rgssAssetId = null })
    val context = LocalContext.current
    val activity = context.findActivity()
    val managementRequest by model.agentManagementRequests.collectAsStateWithLifecycle()
    var requestedModelProfile by remember(state.project?.ref) { mutableStateOf<String?>(null) }
    var requestedMcpCredentials by remember { mutableStateOf(false) }
    var requestedWorkspace by remember(state.project?.ref, state.agentChatId) { mutableStateOf(false) }
    var requestedSkills by remember(state.project?.ref, state.agentChatId) { mutableStateOf(false) }
    var managementUploadRef by remember { mutableStateOf<LocalProjectRef?>(null) }
    var managementUploadChat by remember { mutableStateOf<ChatId?>(null) }
    val managementUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val current = model.state.value
        if (uri != null && !current.busy && current.project?.ref == managementUploadRef &&
            current.agentChatId == managementUploadChat) model.workspaceActions.uploadUri?.invoke(uri)
        managementUploadRef = null
        managementUploadChat = null
    }
    if (requestedSkills) NativeSkillsDialog(model.skillActions, dismiss = { requestedSkills = false })
    if (requestedWorkspace) state.project?.let { project ->
        NativeWorkspaceDialog(ref = project.ref, revision = project.revision, chatId = state.agentChatId,
            runId = state.agentRunId, actions = model.workspaceActions,
            dismiss = { requestedWorkspace = false },
            saveArtifact = { artifact -> model.prepareWorkspaceArtifactExport(artifact)?.let(saveWorkspaceArtifact::launch) },
            upload = {
                managementUploadRef = project.ref
                managementUploadChat = state.agentChatId
                managementUpload.launch(arrayOf("*/*"))
            }, refreshKey = state.workspaceUploadRevision)
    }
    if (requestedMcpCredentials) NativeMcpSettingsDialog(model.mcpActions, dismiss = { requestedMcpCredentials = false })
    LaunchedEffect(managementRequest, state.busy, state.project?.ref, state.agentChatId) {
        val request = managementRequest ?: return@LaunchedEffect
        if (request.ref != state.project?.ref || request.chat != state.agentChatId) {
            model.consumeAgentManagementRequest(request)
            return@LaunchedEffect
        }
        if (state.busy || !model.consumeAgentManagementRequest(request)) return@LaunchedEffect
        when (request.area) {
            "model_profiles", "model_credentials", "model_advanced", "provider_probe", "oauth", "model_catalog" -> {
                navigateFromSkills {
                    requestedModelProfile = request.profileId
                    model.openModels()
                }
            }
            "mcp_credentials" -> requestedMcpCredentials = true
            "proxy_credentials" -> navigateFromSkills { model.openSettings(NativeSettingsSection.EXPERT) }
            "legacy_export" -> { model.beginLegacyExport(); legacyExportVisible = true }
            "project_import" -> model.beginProjectImport()
            "workspace" -> requestedWorkspace = true
            "skills" -> requestedSkills = true
            "files" -> navigateFromSkills { model.openFiles() }
            "diagnostics" -> navigateFromSkills { model.openLogs() }
            "usage" -> usageVisible = true
        }
    }
    val agentRecreate by model.agentPreferencesRecreate.collectAsStateWithLifecycle()
    LaunchedEffect(agentRecreate) {
        if (agentRecreate && activity != null && model.consumeAgentPreferencesRecreate()) activity.recreate()
    }
    var notificationsGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
    val snackbar = remember { SnackbarHostState() }
    val inputTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importFile(it, false) }
    val inputProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importFile(it, true) }
    val inputAsset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.addAssetFile(it) }
    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        model.addProjectImportFiles(uris)
    }
    val importDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.addProjectImportDirectory(uri)
    }
    val legacyExportDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        model.setLegacyExportDirectory(uri)
    }
    val saveAsset = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { model.completeAssetExport(it) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), model::completeExport)
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), model::completeExport)
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::completeBackupExport)
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importBackup(it) }
    val saveProjectSet = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::completeProjectSetExport)
    val openProjectSet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importProjectSet(it) }
    if (importVisible) {
        NativeProjectImportDialog(
            state = importState,
            actions = NativeProjectImportActions(
                chooseFiles = {
                    importFiles.launch(arrayOf("text/plain", "application/epub+zip", "*/*"))
                },
                chooseDirectory = { importDirectory.launch(null) },
                remove = model::removeProjectImportCandidate,
                create = model::createProjectFromImport,
                cancel = model::cancelProjectImport,
            ),
            labels = projectImportLabels(),
        )
    }
    if (legacyExportVisible) {
        NativeLegacyExportPanel(
            state = legacyExportState,
            actions = model.legacyExportActions,
            labels = legacyExportLabels(),
            chooseOutputDirectory = { legacyExportDirectory.launch(null) },
            dismiss = { legacyExportVisible = false },
        )
    }
    val message = state.error ?: state.notice
    val text = message?.let { stringResource(it) }
    LaunchedEffect(text) { if (text != null) { snackbar.showSnackbar(text); model.dismissMessage() } }
    var discard by rememberSaveable(state.project?.ref?.instanceId?.value, state.editor?.item?.id?.value) { mutableStateOf(false) }
    var discardPolicyHome by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    fun home() {
        navigateFromSkills {
            if (state.policyDraft?.dirty == true) discardPolicyHome = true else model.home()
        }
    }
    fun back() {
        when {
            state.busy && !state.foregroundExecutionActive -> Unit
            state.editor != null -> if (state.editor!!.dirty) discard = true else model.closeEditor()
            state.page == NativePage.SETTINGS -> if (state.project != null) model.selectPage(NativePage.MORE)
                else model.home()
            state.page == NativePage.FILES -> model.closeFiles()
            state.page == NativePage.LOGS -> model.closeLogs()
            state.page == NativePage.MODELS -> model.closeModels()
            state.project != null && state.page == NativePage.PROMPTS -> model.selectPage(NativePage.MORE)
            state.project != null && state.page == NativePage.RULES -> model.selectPage(NativePage.AGENT)
            state.project != null && state.page != NativePage.AGENT -> model.selectPage(NativePage.AGENT)
            state.project != null && !state.busy -> home()
        }
    }
    BackHandler(enabled = state.busy || state.project != null || state.editor != null || state.page == NativePage.SETTINGS || state.page == NativePage.MODELS) { back() }
    NativeTheme(state.themeMode) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rail = maxWidth >= NativeRailBreakpoint && state.project != null
            val wide = maxWidth >= NativeTwoPaneBreakpoint && state.project != null
            val panelMaxHeight = maxHeight * 0.5f
            val subpage = state.page == NativePage.SETTINGS || state.page == NativePage.MODELS ||
                state.page == NativePage.FILES || state.page == NativePage.LOGS
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (!subpage) {
                        Column {
                            TopAppBar(
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                                title = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = state.project?.name ?: stringResource(R.string.native_projects),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        state.project?.let {
                                            Text(
                                                text = it.assetPath.value,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                },
                                navigationIcon = {
                                    if (state.project != null) {
                                        IconButton(
                                            onClick = { home() },
                                            enabled = !state.busy && state.editor == null && !ruleEditorOpen,
                                        ) {
                                            Icon(Icons.Default.Home, stringResource(R.string.native_projects))
                                        }
                                    }
                                },
                                actions = {
                                    if (state.project == null) {
                                        IconButton(
                                            onClick = { model.openSettings(NativeSettingsSection.BASIC) },
                                            enabled = !state.busy,
                                        ) {
                                            Icon(Icons.Default.Settings, stringResource(R.string.native_settings_title))
                                        }
                                        IconButton(onClick = model::openModels, enabled = !state.busy) {
                                            Icon(Icons.Default.Key, stringResource(R.string.native_more_models))
                                        }
                                    }
                                    if (state.project != null) {
                                        if (state.page == NativePage.AGENT && state.mixedApprovalReviewAvailable) {
                                            TextButton(
                                                onClick = {
                                                    if (state.mixedApproval != null) {
                                                        mixedReviewVisible = true
                                                    } else {
                                                        model.prepareMixedApproval()
                                                    }
                                                },
                                                enabled = !state.busy,
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.testTag("native-mixed-approval-review"),
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.native_mixed_approval_review_all,
                                                        state.mixedApprovalMemberCount,
                                                    ),
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                    }
                                    Box {
                                        IconButton(onClick = { manageActions = true }) {
                                            Icon(Icons.Default.MoreVert, stringResource(R.string.native_more))
                                        }
                                        DropdownMenu(manageActions, { manageActions = false }) {
                                            if (state.project != null) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.native_tasks_title)) },
                                                    onClick = { manageActions = false; tasksVisible = !tasksVisible; queueVisible = false })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.native_queue_title)) },
                                                    onClick = { manageActions = false; queueVisible = !queueVisible; tasksVisible = false })
                                            }
                                            DropdownMenuItem(text = { Text(stringResource(R.string.native_ledger_title)) },
                                                onClick = { manageActions = false; usageVisible = true })
                                            DropdownMenuItem(text = { Text(stringResource(R.string.native_refresh)) },
                                                enabled = !state.busy && !ruleEditorOpen,
                                                onClick = { manageActions = false; model.refresh() })
                                        }
                                    }
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                },
                bottomBar = {
                    if (state.project != null && !rail) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp,
                            ) {
                                NativeNavigationPages.forEach { page ->
                                    NavigationBarItem(
                                        selected = state.page == page || (page == NativePage.MORE && state.page.belongsToMore()),
                                        onClick = { if (page != state.page) navigateFromSkills { model.selectPage(page) } },
                                        enabled = (!state.busy || state.foregroundExecutionActive) && state.editor == null && state.manualBatch == null && !ruleEditorOpen,
                                        icon = { Icon(page.icon(), contentDescription = null) },
                                        label = { Text(stringResource(page.label()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    )
                                }
                            }
                        }
                    }
                },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (rail) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            NativeNavigationPages.forEach { page ->
                                NavigationRailItem(
                                    selected = state.page == page || (page == NativePage.MORE && state.page.belongsToMore()),
                                    onClick = { if (page != state.page) navigateFromSkills { model.selectPage(page) } },
                                    enabled = (!state.busy || state.foregroundExecutionActive) && state.editor == null && state.manualBatch == null && !ruleEditorOpen,
                                    icon = { Icon(page.icon(), null) },
                                    label = { Text(stringResource(page.label()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.busy && state.page != NativePage.AGENT) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(
                                                when {
                                                    state.foregroundTranslationPausing -> R.string.native_background_pausing
                                                    state.foregroundWorkspaceActive ->
                                                        skillExecutionPhaseLabel(workspaceExecution) ?: R.string.native_working
                                                    else -> R.string.native_working
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (state.foregroundTranslationCanPause) {
                                            TextButton(onClick = model::pauseTranslation) {
                                                Text(stringResource(R.string.native_pause))
                                            }
                                        }
                                        TextButton(onClick = model::cancelAction) {
                                            Text(stringResource(R.string.native_cancel))
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                            }
                        }
                        if (queueVisible || tasksVisible || failureReview != null) {
                            Box(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight)) { taskPanels() }
                        }
                        pageStates.SaveableStateProvider("${state.project?.ref?.instanceId?.value}:${state.project?.ref?.generation?.value}:${state.page.name}") {
                            if (state.page == NativePage.SETTINGS) NativeSettingsPage(
                                state.settingsSection, state.appLanguage, state.themeMode,
                                state.automaticContextSummary, state.appPersona, state.appPersonaSaveFailed,
                                state.networkSettingsState, state.requestLimitsState,
                                state.laboratorySettings, state.laboratoryLoaded,
                                state.laboratorySaving, state.laboratoryError,
                                back = {
                                    if (state.project != null) model.selectPage(NativePage.MORE)
                                    else model.selectPage(NativePage.AGENT)
                                },
                                selectSection = model::selectSettingsSection,
                                selectLanguage = { language ->
                                    model.setAppLanguage(language) { activity?.recreate() }
                                },
                                selectTheme = model::setThemeMode,
                                setAutomaticContextSummary = model::setAutomaticContextSummary,
                                saveAppPersona = model::saveAppPersona,
                                reloadNetwork = model::reloadNetworkSettings,
                                saveNetwork = model::saveNetworkSettings,
                                reloadLimits = model::reloadRequestLimits,
                                saveLimits = model::saveRequestLimits,
                                reloadLaboratory = model::reloadLaboratorySettings,
                                saveLaboratory = model::saveLaboratorySettings,
                            ) else if (state.page == NativePage.MODELS) NativeModelsPage(
                                state = state,
                                back = model::closeModels,
                                refresh = model::refreshModelProfiles,
                                create = model::createModelProfile,
                                update = model::updateModelProfile,
                                copy = model::copyModelProfile,
                                delete = model::deleteModelProfile,
                                move = model::moveModelProfile,
                                select = model::selectModelProfile,
                                loadKeys = model::loadModelProfileKeys,
                                setKeyEnabled = model::setModelKeyEnabled,
                                requestedProfileId = requestedModelProfile,
                                requestedProfileOpened = { requestedModelProfile = null },
                            ) else if (state.project == null) {
                                ProjectsHome(state, onCreate = { if (model.prepareImport(false)) inputTxt.launch(arrayOf("text/*", "application/octet-stream")) },
                                    onImport = { if (model.prepareImport(true)) inputProject.launch(arrayOf("*/*")) },
                                    onImportFiles = { model.beginProjectImport() },
                                    onOpen = model::open,
                                    onCopy = model::copy, onEncoding = model::changeEncoding)
                            } else when (state.page) {
                                NativePage.AGENT -> NativeAgentPage(state, model::queryAgentProject, model::prepareAgentEdit,
                                    model::allowAgentEdit, model::rejectAgentEdit, model::selectPage, model::agentInput,
                                    model::sendAgentMessage, model::saveAgentModel, model::newAgentChat, model::selectAgentChat,
                                    model::fetchAgentModels, model::cancelAction, model::inspectAgentMessage, model::closeAgentMessage,
                                    model.agentStream, model::saveAgentModelProfile, model::fetchAgentModelsProfile,
                                    model::queueAgentInput, model::useAgentDraft, model::deleteAgentDraft, model::toggleAgentQueuePaused,
                                    model::sendQueuedAgentMessage, model::steerAgentInput,
                                    model::steerHistory, model::steerDetail, model::restoreSteerInput,
                                    model::prepareAgentContext, model::saveAgentContext,
                                    model::agentHistoryPage, model::agentHistoryDetail,
                                    model::prepareAgentBranch, model::createAgentBranch,
                                    reviewItemProposal = { reviewedItemProposal = it },
                                    reviewPolicyProposal = { reviewedPolicyProposal = it },
                                    reviewPdfPageProposal = { reviewedPdfPageProposal = it },
                                    reviewAssetImportProposal = { reviewedAssetImportProposal = it },
                                    reviewWorkspaceChange = { reviewedWorkspaceChange = it },
                                    workspace = model.workspaceActions,
                                    saveWorkspaceArtifact = { artifact ->
                                        model.prepareWorkspaceArtifactExport(artifact)?.let(saveWorkspaceArtifact::launch)
                                    },
                                    selectProfile = { model.selectModelProfile(it, ModelProfilePurpose.AGENT) },
                                    refreshProfiles = model::refreshModelProfiles,
                                    wideLayout = wide,
                                    reviewApplicationProposal = if (currentBusinessProposal == null) null else ({ businessReviewVisible = true }))
                                NativePage.WORKBENCH -> Workbench(
                                    state = state,
                                    openTasks = { tasksVisible = !tasksVisible; queueVisible = false },
                                    openQueue = { queueVisible = !queueVisible; tasksVisible = false },
                                    cleanup = model::cleanupOutputs,
                                    export = { kind ->
                                        model.prepareExport(kind)?.let { name ->
                                            if (kind == NativeExport.PROJECT) saveProject.launch(name) else saveText.launch(name)
                                        }
                                    },
                                    addAsset = {
                                        if (model.prepareAddAsset()) inputAsset.launch(arrayOf(
                                            "text/plain", "application/epub+zip", "application/octet-stream"))
                                    },
                                    mutateAsset = model::mutateAsset,
                                    editSettings = model::saveProjectSettings,
                                    loadPdfPage = model::pdfPage,
                                    loadPdfThumbnail = model::pdfPageThumbnail, editPdfPage = model::editPdfPage,
                                    exportAssetBilingual = { assetId ->
                                        model.prepareAssetExport(assetId, true)?.let { saveAsset.launch(it) }
                                    },
                                    exportAsset = { assetId ->
                                        model.prepareAssetExport(assetId)?.let { saveAsset.launch(it) }
                                    },
                                    exportBackup = { model.prepareBackupExport()?.let { saveBackup.launch(it) } },
                                    importBackup = { if (model.prepareBackupImport()) openBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                                    exportAllBackups = { model.prepareProjectSetExport()?.let { saveProjectSet.launch(it) } },
                                    importAllBackups = { if (model.prepareProjectSetImport()) openProjectSet.launch(arrayOf("application/zip", "application/octet-stream")) },
                                    openFiles = model::openFiles,
                                )
                                NativePage.MORE -> NativeMorePage(
                                    state = state,
                                    onRules = { section -> navigateFromSkills { model.openRules(section) } },
                                    onModels = { navigateFromSkills { model.openModels() } },
                                    onSettings = { settingsVisible = true },
                                    onAppearance = { appearanceVisible = true },
                                    onLogs = { navigateFromSkills { model.openLogs() } },
                                    onSkills = { skillsVisible = true },
                                    skillPanel = if (skillsVisible) skillPanel else null,
                                    onBackupExport = { saveAppBackup.launch("lnt-app-backup.zip") },
                                    onBackupImport = { openAppBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                                    onFullArchiveExport = { fullArchiveExportVisible = true },
                                    onFullArchiveImport = {
                                        model.requestFullArchiveImport {
                                            openFullArchive.launch(arrayOf("application/zip", "application/octet-stream"))
                                        }
                                    },
                                    onLegacyExport = {
                                        model.beginLegacyExport()
                                        legacyExportVisible = true
                                    },
                                    notificationsGranted = notificationsGranted,
                                    requestNotifications = {
                                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    cleanup = model::cleanupOutputs,
                                    refresh = model::refresh,
                                    refreshStorage = model::refreshStorageUsage,
                                    onAgentSettings = { agentSettingsVisible = true },
                                    onAppSettings = { navigateFromSkills { model.openSettings(NativeSettingsSection.BASIC) } },
                                    onExpertSettings = { navigateFromSkills { model.openSettings(NativeSettingsSection.EXPERT) } },
                                    onFiles = { navigateFromSkills { model.openFiles() } },
                                )
                                NativePage.FILES -> NativeFilesPage(
                                    state = state, loadInventory = model::fileInventory,
                                    back = model::closeFiles,
                                    browseRgss = { _, assetId -> rgssAssetId = assetId },
                                    exportAsset = { assetId ->
                                        model.prepareAssetExport(assetId)?.let { saveAsset.launch(it) }
                                    },
                                    exportBilingual = { assetId ->
                                        model.prepareAssetExport(assetId, true)?.let { saveAsset.launch(it) }
                                    },
                                    saveProject = { model.prepareExport(NativeExport.PROJECT)?.let(saveProject::launch) },
                                    exportBackup = { model.prepareBackupExport()?.let { saveBackup.launch(it) } },
                                    exportAllBackups = { model.prepareProjectSetExport()?.let { saveProjectSet.launch(it) } },
                                    cleanup = model::cleanupOutputs,
                                    refreshStorage = model::refreshStorageUsage,
                                    shareAll = {
                                        model.shareProjectFiles { files ->
                                            NativeFileDelivery.share(context, files)?.let { intent ->
                                                context.startActivity(Intent.createChooser(intent,
                                                    context.getString(R.string.native_files_share_all)))
                                            }
                                        }
                                    },
                                )
                                NativePage.LOGS -> NativeLogsPage(
                                    includeDetails = state.diagnosticsIncludeDetails,
                                    back = model::closeLogs,
                                    loadPage = model::diagnosticPage,
                                    loadDetail = model::diagnosticDetail,
                                    loadCategories = model::diagnosticCategories,
                                    setIncludeDetails = model::setDiagnosticsIncludeDetails,
                                    export = { saveLogs.launch("lnt-diagnostics.jsonl") },
                                    clear = model::clearDiagnostics,
                                )
                                NativePage.RULES, NativePage.PROMPTS -> NativeTextRulesPage(state, model::changeTextPolicy, model::saveTextPolicy, model::reloadTextPolicy,
                                    model.rulePresetActions, model.promptTemplateActions,
                                    section = state.rulesSection,
                                    selectSection = model::selectRulesSection,
                                    back = { model.selectPage(if (state.page == NativePage.PROMPTS) NativePage.MORE else NativePage.AGENT) },
                                    wideLayout = wide,
                                    onEditingChange = { ruleEditorOpen = it })
                                NativePage.SETTINGS -> Unit
                                NativePage.MODELS -> Unit
                                NativePage.PROOFREADING -> Proofreading(state, model::edit, model::previousPage, model::nextPage,
                                    model::simulatePage, notificationsGranted, model::queryProofreading,
                                    NativeBatchActions(model::selectItems, model::toggleItem, model::selectPageItems,
                                        model::prepareManualBatch, model::confirmManualBatch, model::dismissManualBatch, model::prepareQueryBatch,
                                        model::prepareQueryReplacement,
                                        { names, scope, change -> model.prepareQueryNameAssignment(names, scope, change) }),
                                    model::simulateProject, model::translateRemote,
                                    model::enqueueRemoteTranslation,
                                    qualityActions = NativeQualityActions(model::scanQuality, model::filterQuality, model::clearQuality),
                                    wideLayout = wide) {
                                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (discardPolicyHome) NativeContentDialog(onDismissRequest = { discardPolicyHome = false },
            title = { Text(stringResource(R.string.native_policy_title)) }, text = { Text(stringResource(R.string.native_policy_discard_body)) },
            confirmButton = { TextButton(onClick = { discardPolicyHome = false; model.discardPolicyAndHome() }) { Text(stringResource(R.string.native_discard)) } },
            dismissButton = { TextButton(onClick = { discardPolicyHome = false }) { Text(stringResource(R.string.native_keep_editing)) } })
        state.editor?.let { draft ->
            Editor(draft, state.busy, onDismiss = { if (draft.dirty) discard = true else model.closeEditor() },
                onText = model::editText, onStatus = model::editStatus, onSave = model::saveEditor,
                onReload = model::reloadEditor, onCancel = model::cancelAction,
                quality = state.editorQuality, editorError = state.error, editorNotice = state.notice,
                onNavigate = model::navigateEditor, navigationEnabled = state.qualityView == null || state.qualityCurrent, qualityEnabled = !state.policyLoadIncomplete && state.policyDraft?.dirty != true,
                onInspect = model::inspectEditor, replacement = state.editorReplacement,
                onPrepareReplacement = model::prepareEditorReplacement, onApplyReplacement = model::applyEditorReplacement,
                onEditNames = if (model.nameEditingAvailable) model::beginNameEdit else null,
                canUndo = state.editorCanUndo, canRedo = state.editorCanRedo,
                restoredHistory = state.editorHistoryRestored,
                onUndo = model::undoEditor, onRedo = model::redoEditor,
                onClearHistory = model::clearEditorHistory,
                nameEditing = state.nameEdit != null,
                nameContent = { state.nameEdit?.let { names ->
                    NativeNameEditDialog(names, state.busy, model::dismissNameEdit, model::saveNameEdit, inline = true)
                } })
        }
        if (discard) NativeContentDialog(onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.native_discard_title)) },
            text = { Text(stringResource(R.string.native_discard_body)) },
            confirmButton = { TextButton(onClick = { discard = false; model.closeEditor() }) { Text(stringResource(R.string.native_discard)) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_keep_editing)) } },
        )
    }
}

@Composable
internal fun ProjectsHome(
    state: NativeProjectUiState,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onImportFiles: () -> Unit,
    onOpen: (LocalProjectRef) -> Unit,
    onCopy: (LocalProjectRef) -> Unit,
    onEncoding: (TxtEncoding) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hero") {
            NativeSectionCard(
                title = stringResource(R.string.native_home_title),
                subtitle = null,
                badge = state.encoding.charsetName,
                badgeTone = NativeTone.PRIMARY,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onCreate,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.native_create_txt), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onImport,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.native_import_project), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                OutlinedButton(
                    onClick = onImportFiles,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("native-home-import-files"),
                ) {
                    Text(stringResource(R.string.native_import_from_files))
                }
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.native_encoding, state.encoding.charsetName))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        TxtEncoding.entries.forEach { encoding ->
                            DropdownMenuItem(
                                text = { Text(encoding.charsetName) },
                                onClick = {
                                    onEncoding(encoding)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
        if (state.legacyData) {
            item(key = "legacy-banner") {
                NativeBanner(
                    text = stringResource(R.string.native_legacy_preserved),
                    tone = NativeTone.NEUTRAL,
                )
            }
        }
        if (state.recoveryProblems > 0) {
            item(key = "recovery-banner") {
                NativeBanner(
                    text = stringResource(R.string.native_recovery_warning, state.recoveryProblems),
                    tone = NativeTone.ERROR,
                )
            }
        }
        item(key = "projects-header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.native_registered_projects),
                    style = MaterialTheme.typography.titleMedium,
                )
                NativeStatusBadge(
                    text = state.projects.size.toString(),
                    tone = if (state.projects.isEmpty()) NativeTone.NEUTRAL else NativeTone.PRIMARY,
                )
            }
        }
        if (state.projects.isEmpty()) {
            item(key = "projects-empty") {
                NativeSectionCard {
                    Text(
                        text = stringResource(R.string.native_empty_projects),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(state.projects, key = { it.ref.instanceId.value }) { entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                border = BorderStroke(
                    1.dp,
                    if (entry.readable) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                ),
            ) {
                ListItem(
                    modifier = Modifier.clickable(enabled = entry.readable && !state.busy) { onOpen(entry.ref) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    headlineContent = {
                        Text(
                            text = entry.summary?.name ?: stringResource(R.string.native_unavailable),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = entry.summary?.let { stringResource(R.string.native_item_count, it.itemCount) }
                                ?: stringResource(R.string.native_unavailable_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (entry.readable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            contentColor = if (entry.readable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    },
                    trailingContent = {
                        if (entry.readable) {
                            IconButton(onClick = { onCopy(entry.ref) }, enabled = !state.busy) {
                                Icon(Icons.Default.ContentCopy, stringResource(R.string.native_copy))
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun Workbench(
    state: NativeProjectUiState,
    cleanup: () -> Unit,
    export: (NativeExport) -> Unit,
    addAsset: () -> Unit,
    exportAsset: (Long) -> Unit,
    mutateAsset: ((NativeAssetMutation) -> Unit)? = null,
    editSettings: ((NativeProjectSettingsChange) -> Unit)? = null,
    exportAssetBilingual: ((Long) -> Unit)? = null,
    exportBackup: (() -> Unit)? = null,
    importBackup: (() -> Unit)? = null,
    exportAllBackups: (() -> Unit)? = null,
    importAllBackups: (() -> Unit)? = null,
    loadPdfPage: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> TextProjectItem)? = null,
    loadPdfThumbnail: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> ByteArray)? = null,
    editPdfPage: ((LocalProjectRef, Long, ProjectAssetSummary, Int) -> Unit)? = null,
    openFiles: (() -> Unit)? = null,
    openTasks: (() -> Unit)? = null,
    openQueue: (() -> Unit)? = null,
) {
    val project = requireNotNull(state.project)
    var backupExpanded by rememberSaveable(project.ref) { mutableStateOf(false) }
    var runsOpen by remember(project.ref) { mutableStateOf(false) }
    if (runsOpen) NativeContentDialog(
        onDismissRequest = { runsOpen = false },
        title = { Text(stringResource(R.string.native_runs_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) { NativeRunHistory(state) }
        },
        confirmButton = { TextButton(onClick = { runsOpen = false }) { Text(stringResource(R.string.native_close)) } },
    )
    var settingsOpen by remember(project.ref) { mutableStateOf(false) }
    var pdfAsset by remember(project.ref) { mutableStateOf<ProjectAssetSummary?>(null) }
    if (settingsOpen && editSettings != null) {
        NativeProjectSettingsDialog(state, { settingsOpen = false }, editSettings)
    }
    var confirmation by remember(project.ref, project.revision) { mutableStateOf<NativeAssetMutation?>(null) }
    val canManage = !state.busy && state.editor == null && state.manualBatch == null && !state.selectingItems &&
        state.policyDraft?.dirty != true && !state.policyLoadIncomplete && !state.runLoadIncomplete
    fun intent(assetId: Long, change: NativeAssetChange) = NativeAssetMutation(
        project.ref,
        project.revision,
        assetId,
        change,
        com.breakyuna.noveltranslator.nativecore.domain.CommandId(java.util.UUID.randomUUID().toString()),
    )
    confirmation?.let { fixed ->
        val asset = project.assets.firstOrNull { it.id == fixed.assetId }
        if (asset != null) {
            NativeContentDialog(
                onDismissRequest = { if (!state.busy) confirmation = null },
                title = { Text(stringResource(if (fixed.change == NativeAssetChange.REMOVE) R.string.native_asset_remove else R.string.native_asset_reset)) },
                text = {
                    Text(
                        stringResource(
                            if (fixed.change == NativeAssetChange.REMOVE) R.string.native_asset_remove_warning else R.string.native_asset_reset_warning,
                            asset.path.value,
                            asset.itemCount,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmation = null; mutateAsset?.invoke(fixed) }, enabled = canManage) {
                        Text(stringResource(R.string.native_asset_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmation = null }, enabled = !state.busy) {
                        Text(stringResource(R.string.native_cancel))
                    }
                },
            )
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.TopCenter).widthIn(max = 1080.dp).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "workbench-overview") {
            NativeSectionCard(
                title = stringResource(R.string.native_workbench),
                subtitle = project.assetPath.value,
                badge = stringResource(R.string.native_item_count, project.itemCount),
                badgeTone = NativeTone.PRIMARY,
            ) {
                Text(
                    text = stringResource(R.string.native_translation_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.itemStatistics?.let { stats ->
                    val total = stats.total.coerceAtLeast(1)
                    val progress = stats.processed.toFloat() / total.toFloat()
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NativeStatusBadge(
                            text = "${stringResource(R.string.native_status_processed)}: ${stats.processed}",
                            tone = NativeTone.SUCCESS,
                        )
                        NativeStatusBadge(
                            text = "${stringResource(R.string.native_status_none)}: ${stats.unprocessed}",
                            tone = NativeTone.NEUTRAL,
                        )
                        if (stats.errors > 0) {
                            NativeStatusBadge(
                                text = "${stringResource(R.string.native_status_error)}: ${stats.errors}",
                                tone = NativeTone.ERROR,
                            )
                        }
                    }
                }
                if (openFiles != null || editSettings != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (openFiles != null) {
                            OutlinedButton(
                                onClick = openFiles,
                                enabled = canManage,
                                modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp).testTag("native-workbench-files"),
                            ) {
                                Text(stringResource(R.string.native_files_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (editSettings != null) {
                            OutlinedButton(
                                onClick = { settingsOpen = true },
                                enabled = canManage,
                                modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                            ) {
                                Text(stringResource(R.string.native_project_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (exportBackup != null || importBackup != null || exportAllBackups != null || importAllBackups != null) {
                    TextButton(onClick = { backupExpanded = !backupExpanded }) {
                        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.native_backup_actions))
                        Icon(if (backupExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    }
                }
                if (backupExpanded) {
                    if (exportBackup != null || importBackup != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (exportBackup != null) {
                                OutlinedButton(
                                    onClick = exportBackup,
                                    enabled = canManage,
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                                ) {
                                    Text(stringResource(R.string.native_backup_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (importBackup != null) {
                                OutlinedButton(
                                    onClick = importBackup,
                                    enabled = canManage,
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                                ) {
                                    Text(stringResource(R.string.native_backup_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (exportAllBackups != null || importAllBackups != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (exportAllBackups != null) {
                                OutlinedButton(
                                    onClick = exportAllBackups,
                                    enabled = canManage,
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                                ) {
                                    Text(stringResource(R.string.native_project_set_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (importAllBackups != null) {
                                OutlinedButton(
                                    onClick = importAllBackups,
                                    enabled = canManage,
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                                ) {
                                    Text(stringResource(R.string.native_project_set_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (state.recentRuns.isNotEmpty()) {
            item(key = "workbench-runs") {
                TextButton(onClick = { runsOpen = true }) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.native_runs_title))
                    if (state.recentRuns.any { it.state == StoredRunState.INTERRUPTED }) {
                        Spacer(Modifier.width(8.dp))
                        NativeStatusBadge(text = stringResource(R.string.native_runs_interrupted), tone = NativeTone.WARNING)
                    }
                }
            }
        }
        item(key = "task-controls") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                openTasks?.let { TextButton(onClick = it) { Text(stringResource(R.string.native_tasks_title)) } }
                openQueue?.let { TextButton(onClick = it) { Text(stringResource(R.string.native_queue_title)) } }
            }
        }
        val assets = state.project?.assets.orEmpty()
        if (pdfAsset != null) item(key = "pdf-panel") {
            Box(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
    pdfAsset?.let { asset ->
        if (editSettings != null) {
            NativePdfPagesDialog(state, asset, { pdfAsset = null }, editSettings, loadPdfPage, loadPdfThumbnail, editPdfPage)
        }
    }
            }
        }
        item(key = "assets-header") {
            NativeSectionCard(
                title = stringResource(R.string.native_assets),
                subtitle = null,
                headerAction = {
                    OutlinedButton(onClick = addAsset, enabled = !state.busy) {
                        Text(stringResource(R.string.native_add_asset))
                    }
                },
            ) {
                if (assets.isEmpty()) {
                    Text(
                        text = stringResource(R.string.native_asset_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(assets, key = { "asset-" + it.id }) { asset ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = asset.path.value,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = stringResource(
                                    R.string.native_asset_summary,
                                    stringResource(nativeFormatLabelRes(asset.format)),
                                    asset.itemCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { exportAsset(asset.id) }, enabled = !state.busy) {
                            Text(stringResource(R.string.native_export_asset))
                        }
                    }
                    if (exportAssetBilingual != null || (asset.format == ProjectAssetFormat.PDF && editSettings != null)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (exportAssetBilingual != null) {
                                TextButton(onClick = { exportAssetBilingual(asset.id) }, enabled = !state.busy) {
                                    Text(stringResource(R.string.native_export_bilingual))
                                }
                            }
                            if (asset.format == ProjectAssetFormat.PDF && editSettings != null) {
                                TextButton(
                                    onClick = { pdfAsset = asset },
                                    enabled = canManage && asset.itemCount in 1..5_000,
                                ) {
                                    Text(stringResource(R.string.native_pdf_pages))
                                }
                            }
                        }
                    }
                    if (mutateAsset != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(
                                checked = asset.enabled,
                                onCheckedChange = { enabled ->
                                    mutateAsset(intent(asset.id, if (enabled) NativeAssetChange.ENABLE else NativeAssetChange.DISABLE))
                                },
                                enabled = canManage,
                            )
                            Text(
                                text = stringResource(R.string.native_asset_translation_enabled),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                            )
                        }
                        val index = assets.indexOfFirst { it.id == asset.id }
                        var assetMenu by remember(asset.id) { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { assetMenu = true }) { Text(stringResource(R.string.native_more)) }
                            DropdownMenu(assetMenu, { assetMenu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.native_asset_move_up)) },
                                    enabled = canManage && index > 0,
                                    onClick = { assetMenu = false; mutateAsset(intent(asset.id, NativeAssetChange.MOVE_UP)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.native_asset_move_down)) },
                                    enabled = canManage && index < assets.lastIndex,
                                    onClick = { assetMenu = false; mutateAsset(intent(asset.id, NativeAssetChange.MOVE_DOWN)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.native_asset_reset)) }, enabled = canManage,
                                    onClick = { assetMenu = false; confirmation = intent(asset.id, NativeAssetChange.RESET) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.native_asset_remove)) }, enabled = canManage && assets.size > 1,
                                    onClick = { assetMenu = false; confirmation = intent(asset.id, NativeAssetChange.REMOVE) })
                            }
                        }
                    }
                }
            }
        }
        item(key = "workbench-exports") {
            NativeSectionCard {
                Button(
                    onClick = { export(NativeExport.PROJECT) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.native_save_project))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { export(NativeExport.TRANSLATION) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                    ) {
                        Text(stringResource(R.string.native_export_translation), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { export(NativeExport.BILINGUAL) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                    ) {
                        Text(stringResource(R.string.native_export_bilingual), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { export(NativeExport.ORIGINAL) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                    ) {
                        Text(stringResource(R.string.native_export_original), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(
                        onClick = cleanup,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                    ) {
                        Text(stringResource(R.string.native_cleanup), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Proofreading(
    state: NativeProjectUiState,
    edit: (ItemId) -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    simulate: () -> Unit,
    notificationsGranted: Boolean = true,
    query: (TextItemQuery) -> Unit = {},
    batchActions: NativeBatchActions? = null,
    simulateProject: (() -> Unit)? = null,
    remoteTranslation: NativeRemoteTranslationAction? = null,
    queueTranslation: NativeRemoteTranslationAction? = null,
    qualityActions: NativeQualityActions? = null,
    wideLayout: Boolean = false,
    requestNotifications: () -> Unit = {},
) {
    if (state.manualBatch != null && batchActions != null) NativeManualBatchDialog(state, batchActions)
    val operationStates = rememberSaveableStateHolder()
    var filtering by rememberSaveable(
        state.project?.ref?.projectId?.value,
        state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value,
    ) { mutableStateOf(false) }
    var confirm by rememberSaveable(
        state.project?.ref?.projectId?.value,
        state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value,
        state.items.firstOrNull()?.id?.value,
    ) { mutableStateOf(false) }
    var wholeProject by rememberSaveable(
        state.project?.ref?.projectId?.value,
        state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value,
    ) { mutableStateOf(false) }
    val enabledAssets = state.project?.assets.orEmpty().filter { it.enabled }.map { it.id }.toSet()
    val pendingPage = state.items.any {
        it.status == TextItemStatus.NONE && it.hasTranslatableSource &&
            (state.project?.assets.isNullOrEmpty() || it.assetId in enabledAssets)
    }
    val pendingProject = simulateProject != null && (state.itemStatistics?.translatableUnprocessed ?: 0) > 0
    var remote by rememberSaveable(state.project?.ref, state.items.firstOrNull()?.id?.value) { mutableStateOf(false) }
    if (confirm) {
        NativeContentDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.native_simulation)) },
            text = {
                Column(
                    Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (simulateProject != null) {
                        TextButton(onClick = { wholeProject = false }, enabled = pendingPage && !state.busy) {
                            RadioButton(selected = !wholeProject, onClick = null)
                            Text(stringResource(R.string.native_simulation_scope_page))
                        }
                        TextButton(onClick = { wholeProject = true }, enabled = pendingProject && !state.busy) {
                            RadioButton(selected = wholeProject, onClick = null)
                            Text(stringResource(R.string.native_simulation_scope_project))
                        }
                    }
                    Text(stringResource(if (wholeProject) R.string.native_simulation_project_confirm else R.string.native_simulation_confirm))
                    Text(stringResource(R.string.native_background_notice), style = MaterialTheme.typography.bodySmall)
                    if (!notificationsGranted) {
                        TextButton(onClick = requestNotifications, enabled = !state.busy) {
                            Text(stringResource(R.string.native_background_notifications))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        if (wholeProject) simulateProject?.invoke() else simulate()
                    },
                    enabled = !state.busy && !state.runLoadIncomplete && !state.policyLoadIncomplete &&
                        state.policyDraft?.dirty != true && !state.proofreadingQuery.active && state.qualityView == null &&
                        (if (wholeProject) pendingProject else pendingPage),
                ) {
                    Text(stringResource(R.string.native_simulation_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.native_cancel)) }
            },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = wideLayout || maxWidth >= NativeTwoPaneBreakpoint
        val headerHeight = maxHeight * if (wide) 0.30f else 0.36f
        var selectedItemId by rememberSaveable(
            state.project?.ref?.projectId?.value,
            state.project?.ref?.instanceId?.value,
            state.project?.ref?.generation?.value,
        ) { mutableStateOf<Long?>(null) }
        val selectedItem = state.items.firstOrNull { it.id.value == selectedItemId } ?: state.items.firstOrNull()
        val header: @Composable () -> Unit = {
            key(state.project?.ref?.projectId?.value, state.project?.ref?.instanceId?.value, state.project?.ref?.generation?.value) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = headerHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (qualityActions != null) NativeAgentFoldout(stringResource(R.string.native_quality_title)) { NativeQualityPanel(state, qualityActions) }
                        if (state.policyDraft?.dirty == true) {
                            Text(
                                text = stringResource(R.string.native_policy_unsaved),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        if (remoteTranslation != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = { remote = !remote; filtering = false },
                                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp),
                                    enabled = state.translationModelSettings != null && !state.policyLoadIncomplete &&
                                        state.policyDraft?.dirty != true && !state.busy && state.editor == null &&
                                        state.manualBatch == null && !state.selectingItems && !state.runLoadIncomplete &&
                                        !state.proofreadingQuery.active && state.qualityView == null &&
                                        (pendingPage || pendingProject || (state.itemStatistics?.processed ?: 0) > 0 || (state.itemStatistics?.errors ?: 0) > 0),
                                ) {
                                    Text(stringResource(R.string.native_remote_translate))
                                }
                            }
                            if (state.translationModelSettings == null) {
                                Text(
                                    text = stringResource(R.string.native_remote_configure),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            var toolsMenu by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { toolsMenu = true }, modifier = Modifier.testTag("native-proof-actions")) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.native_more))
                                }
                                DropdownMenu(toolsMenu, { toolsMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_simulation)) },
                                        onClick = { toolsMenu = false; wholeProject = !pendingPage && pendingProject; confirm = true },
                                        enabled = !state.busy && state.policyDraft?.dirty != true && !state.policyLoadIncomplete &&
                                            !state.selectingItems && !state.runLoadIncomplete && !state.proofreadingQuery.active &&
                                            state.qualityView == null && (pendingPage || pendingProject),
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { filtering = !filtering; remote = false },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text(stringResource(R.string.native_proof_filter), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (batchActions != null) {
                                OutlinedButton(
                                    onClick = { batchActions.selecting(!state.selectingItems) },
                                    enabled = !state.busy,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        stringResource(if (state.selectingItems) R.string.native_batch_cancel_selection else R.string.native_batch_select),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (batchActions != null) NativeQueryBatchButton(state, batchActions)
                        if (state.selectingItems && batchActions != null) NativeBatchToolbar(state, batchActions)
                        if (state.proofreadingQuery.active) {
                            state.matchingItems?.let { count ->
                                Text(
                                    text = stringResource(R.string.native_proof_matching, count),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (state.runLoadIncomplete) {
                            Text(
                                text = stringResource(R.string.native_runs_load_error),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        state.translationProgress?.let {
                            Text(
                                text = stringResource(R.string.native_simulation_progress, it.committedItems, it.requests),
                                modifier = Modifier.padding(horizontal = 16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        state.translationProgress?.let { progress ->
                            progress.usageTotals?.let { totals ->
                                state.project?.ref?.let { NativeTranslationUsage(it, totals, progress.run) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = previous,
                                enabled = !state.busy && state.hasPrevious && (state.qualityView == null || state.qualityCurrent),
                            ) {
                                Text(stringResource(R.string.native_previous))
                            }
                            TextButton(
                                onClick = next,
                                enabled = !state.busy && state.hasNext && (state.qualityView == null || state.qualityCurrent),
                            ) {
                                Text(stringResource(R.string.native_next))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        val operationPanels: @Composable () -> Unit = {
            if (filtering) NativeProofreadingQueryDialog(state, { filtering = false }, apply = query, inline = true)
            if (remote && remoteTranslation != null && state.translationModelSettings != null)
                operationStates.SaveableStateProvider("translation:${state.project?.ref}:${state.translationModelSettings?.reference}") {
                    NativeRemoteTranslationDialog(state, { remote = false }, remoteTranslation, queueTranslation, inline = true)
                }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    header()
                    operationPanels()
                    ProofreadingItemList(
                        state = state,
                        batchActions = batchActions,
                        selectedItemId = selectedItem?.id?.value,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) { selectedItemId = it.value }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ProofreadingItemDetail(
                    state = state,
                    item = selectedItem,
                    batchActions = batchActions,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onEdit = edit,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                operationPanels()
                ProofreadingItemList(
                    state = state,
                    batchActions = batchActions,
                    selectedItemId = null,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { edit(it) }
            }
        }
    }
}

@Composable
private fun ProofreadingItemList(
    state: NativeProjectUiState,
    batchActions: NativeBatchActions?,
    selectedItemId: Long?,
    modifier: Modifier = Modifier,
    onOpen: (ItemId) -> Unit,
) {
    key(
        state.project?.ref?.projectId?.value,
        state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value,
        state.items.firstOrNull()?.id?.value,
    ) {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.items.isEmpty()) {
                item(key = "empty-items") {
                    NativeSectionCard {
                        Text(
                            text = stringResource(R.string.native_empty_items),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(state.items, key = { it.id.value }) { item ->
                ProofreadingItemCard(
                    item = item,
                    state = state,
                    batchActions = batchActions,
                    selected = item.id.value == selectedItemId,
                    expandedPreview = false,
                    onOpen = {
                        if (state.selectingItems && batchActions != null) batchActions.toggle(item.id) else onOpen(item.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProofreadingItemCard(
    item: ItemPreview,
    state: NativeProjectUiState,
    batchActions: NativeBatchActions?,
    selected: Boolean,
    expandedPreview: Boolean,
    onOpen: () -> Unit,
) {
    val selectionLabel = stringResource(R.string.native_batch_select_row, item.row.toLong() + 1)
    val containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else MaterialTheme.colorScheme.surface
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !state.busy) { onOpen() }
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.selectingItems && batchActions != null) {
                        Checkbox(
                            checked = item.id in state.selectedItems,
                            onCheckedChange = { batchActions.toggle(item.id) },
                            enabled = !state.busy,
                            modifier = Modifier.semantics { contentDescription = selectionLabel },
                        )
                    }
                    NativeStatusBadge(
                        text = stringResource(R.string.native_row_status, item.row + 1, stringResource(item.status.label())),
                        tone = item.status.tone(),
                    )
                }
                ProofreadingQualitySummary(state, item.id)
            }
            Text(
                text = item.source.ifEmpty { stringResource(R.string.native_blank_line) },
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.source.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = if (expandedPreview) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = item.translation.ifEmpty {
                    stringResource(if (item.status == TextItemStatus.PROCESSED) R.string.native_empty_result else R.string.native_empty_translation)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expandedPreview) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                color = if (item.translation.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ProofreadingQualitySummary(state: NativeProjectUiState, itemId: ItemId) {
    if (!state.qualityCurrent) return
    val result = state.qualityView?.index?.fact(itemId)?.result ?: return
    val (summary, tone) = when {
        !result.evaluated -> stringResource(R.string.native_quality_skipped) to NativeTone.NEUTRAL
        result.codes.isEmpty() -> stringResource(R.string.native_quality_clear) to NativeTone.SUCCESS
        else -> stringResource(R.string.native_quality_row_warnings, result.codes.size) to NativeTone.ERROR
    }
    NativeStatusBadge(text = summary, tone = tone)
}

@Composable
private fun ProofreadingItemDetail(
    state: NativeProjectUiState,
    item: ItemPreview?,
    batchActions: NativeBatchActions?,
    modifier: Modifier = Modifier,
    onEdit: (ItemId) -> Unit,
) {
    if (item == null) {
        Box(modifier.testTag("native-proofreading-detail"), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.native_empty_items), Modifier.padding(16.dp))
        }
        return
    }
    val selectionLabel = stringResource(R.string.native_batch_select_row, item.row.toLong() + 1)
    Column(
        modifier = modifier.testTag("native-proofreading-detail").padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.native_row_status, item.row + 1, stringResource(item.status.label())),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.selectingItems) {
                    Text(
                        text = stringResource(R.string.native_batch_selected, state.selectedItems.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.selectingItems && batchActions != null) {
                Checkbox(
                    checked = item.id in state.selectedItems,
                    onCheckedChange = { batchActions.toggle(item.id) },
                    enabled = !state.busy,
                    modifier = Modifier.semantics {
                        contentDescription = selectionLabel
                    },
                )
            }
            Button(onClick = { onEdit(item.id) }, enabled = !state.busy && !state.selectingItems) {
                Text(stringResource(R.string.native_edit_row, item.row + 1))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        key(item.id.value) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProofreadingQualitySummary(state, item.id)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.native_source), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(item.source.ifEmpty { stringResource(R.string.native_blank_line) },
                                    style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.native_translation), style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(item.translation.ifEmpty {
                                    stringResource(if (item.status == TextItemStatus.PROCESSED) R.string.native_empty_result else R.string.native_empty_translation)
                                }, style = MaterialTheme.typography.bodyLarge,
                                    color = if (item.translation.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Editor(
    draft: NativeEditDraft,
    busy: Boolean,
    onDismiss: () -> Unit,
    onText: (String) -> Unit,
    onStatus: (TextItemStatus) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onCancel: () -> Unit,
    quality: TextProofreadingResult? = null,
    qualityEnabled: Boolean = true,
    editorError: Int? = null,
    onInspect: (TextProofreadingOptions) -> Unit = {},
    editorNotice: Int? = null,
    navigationEnabled: Boolean = true,
    onNavigate: ((TextItemDirection, Boolean) -> Unit)? = null,
    replacement: NativeEditorReplacement? = null,
    onPrepareReplacement: ((TextReplacementOptions) -> Unit)? = null,
    onApplyReplacement: ((NativeEditorReplacement) -> Unit)? = null,
    onEditNames: (() -> Unit)? = null,
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    restoredHistory: Int = 0,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onClearHistory: (() -> Unit)? = null,
    nameContent: (@Composable () -> Unit)? = null,
    nameEditing: Boolean = false,
) {
    var replacing by rememberSaveable(
        draft.ref.projectId.value,
        draft.ref.instanceId.value,
        draft.ref.generation.value,
        draft.item.id.value,
    ) { mutableStateOf(false) }
    var pendingMove by rememberSaveable(
        draft.ref.projectId.value,
        draft.ref.instanceId.value,
        draft.ref.generation.value,
        draft.item.id.value,
        draft.command.value,
    ) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!busy && !nameEditing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (!busy && !draft.conflict && event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                    when {
                        event.key == Key.Z && event.isShiftPressed && canRedo -> { onRedo(); true }
                        event.key == Key.Z && !event.isShiftPressed && canUndo -> { onUndo(); true }
                        event.key == Key.Y && canRedo -> { onRedo(); true }
                        else -> false
                    }
                } else false
            },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                            title = { Text(stringResource(R.string.native_edit_row, draft.item.row + 1)) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss, enabled = !busy && !nameEditing) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.native_back))
                                }
                            },
                            actions = {
                                TextButton(onClick = onSave, enabled = !busy && !nameEditing && !draft.conflict && draft.dirty) {
                                    Text(stringResource(R.string.native_save))
                                }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .imePadding()
                        .padding(16.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (busy) {
                        NativeBusyIndicator()
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.native_cancel)) }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = onUndo,
                                        enabled = !busy && !draft.conflict && canUndo,
                                        modifier = Modifier.testTag("native-editor-undo"),
                                    ) {
                                        Text(stringResource(R.string.native_editor_undo))
                                    }
                                    TextButton(
                                        onClick = onRedo,
                                        enabled = !busy && !draft.conflict && canRedo,
                                        modifier = Modifier.testTag("native-editor-redo"),
                                    ) {
                                        Text(stringResource(R.string.native_editor_redo))
                                    }
                                }
                                if (onNavigate != null) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextItemDirection.entries.forEach { direction ->
                                            TextButton(
                                                onClick = {
                                                    if (draft.dirty) pendingMove = direction.name else onNavigate(direction, false)
                                                },
                                                enabled = !busy && !nameEditing && !draft.conflict && navigationEnabled,
                                            ) {
                                                Text(stringResource(if (direction == TextItemDirection.PREVIOUS) R.string.native_editor_previous else R.string.native_editor_next))
                                            }
                                        }
                                    }
                                }
                            }
                            if (onPrepareReplacement != null || onEditNames != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (onPrepareReplacement != null) {
                                        OutlinedButton(onClick = { replacing = true }, enabled = !busy) {
                                            Text(stringResource(R.string.native_replace_title))
                                        }
                                    }
                                    if (onEditNames != null) {
                                        OutlinedButton(
                                            onClick = onEditNames,
                                            enabled = !busy && !draft.conflict,
                                            modifier = Modifier.testTag("native-edit-names"),
                                        ) {
                                            Text(stringResource(R.string.native_names_edit))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    nameContent?.invoke()
                    editorError?.let {
                        NativeBanner(text = stringResource(it), tone = NativeTone.ERROR)
                    }
                    editorNotice?.let {
                        NativeBanner(text = stringResource(it), tone = NativeTone.NEUTRAL)
                    }
                    if (restoredHistory > 0) {
                        NativeBanner(
                            text = stringResource(R.string.native_editor_history_restored, restoredHistory),
                            tone = NativeTone.NEUTRAL,
                            action = {
                                if (onClearHistory != null) {
                                    TextButton(onClick = onClearHistory, enabled = !busy) {
                                        Text(stringResource(R.string.native_editor_history_clear))
                                    }
                                }
                            },
                        )
                    }
                    if (draft.conflict) {
                        NativeBanner(
                            text = stringResource(R.string.native_conflict),
                            tone = NativeTone.ERROR,
                            action = {
                                OutlinedButton(onClick = onReload, enabled = !busy) {
                                    Text(stringResource(R.string.native_reload_baseline))
                                }
                            },
                        )
                    }
                    NativeAgentFoldout(stringResource(R.string.native_source), stateKey = listOf(draft.ref, draft.item.id)) {
                        Text(
                            text = draft.item.source.ifEmpty { stringResource(R.string.native_blank_line) },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedTextField(
                        value = draft.text,
                        onValueChange = onText,
                        enabled = !busy,
                        label = { Text(stringResource(R.string.native_translation)) },
                        minLines = 8,
                        maxLines = 18,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NativeExpandedTextEditor(listOf(draft.ref, draft.item.id),
                        stringResource(R.string.native_translation), draft.text, onText, !busy)
                    NativeAgentFoldout(stringResource(R.string.native_quality_title), stateKey = listOf(draft.ref, draft.item.id), forceExpanded = editorError != null) { NativeEditorQuality(draft, busy, qualityEnabled, quality, onInspect) }
                    NativeSectionCard(title = stringResource(R.string.native_status)) {
                        val manualStatuses = TextItemStatus.entries.filter { it.manual }
                        manualStatuses.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                pair.forEach { status ->
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(enabled = !busy) { onStatus(status) }
                                            .heightIn(min = 44.dp),
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (draft.status == status) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerLow
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (draft.status == status) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = draft.status == status,
                                                onClick = { onStatus(status) },
                                                enabled = !busy,
                                            )
                                            Text(
                                                text = stringResource(status.label()),
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (replacing && onPrepareReplacement != null && onApplyReplacement != null) {
        NativeEditorReplacementDialog(
            draft,
            busy,
            editorError,
            replacement,
            { replacing = false },
            onPrepareReplacement,
            { replacing = false; onApplyReplacement(it) },
            onCancel,
        )
    }
    if (pendingMove != null && onNavigate != null) {
        NativeContentDialog(
            onDismissRequest = { pendingMove = null },
            title = { Text(stringResource(R.string.native_discard_title)) },
            text = { Text(stringResource(R.string.native_editor_move_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val direction = TextItemDirection.valueOf(pendingMove!!)
                        pendingMove = null
                        onNavigate(direction, true)
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.native_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMove = null }) {
                    Text(stringResource(R.string.native_keep_editing))
                }
            },
        )
    }
}

private fun NativePage.belongsToMore(): Boolean =
    this == NativePage.MORE || this == NativePage.SETTINGS ||
        this == NativePage.FILES || this == NativePage.LOGS || this == NativePage.MODELS || this == NativePage.PROMPTS

private fun NativePage.label(): Int = when (this) {
    NativePage.AGENT -> R.string.native_agent
    NativePage.WORKBENCH -> R.string.native_workbench
    NativePage.PROOFREADING -> R.string.native_proofreading
    NativePage.MORE -> R.string.native_more
    NativePage.RULES -> R.string.native_nav_rules
    NativePage.PROMPTS -> R.string.native_rules_prompts
    NativePage.SETTINGS -> R.string.native_settings_title
    NativePage.FILES -> R.string.native_files_title
    NativePage.LOGS -> R.string.native_logs_title
    NativePage.MODELS -> R.string.native_models_title
}

private fun NativePage.icon(): ImageVector = when (this) {
    NativePage.AGENT -> Icons.Default.SmartToy
    NativePage.WORKBENCH -> Icons.Default.TableRows
    NativePage.PROOFREADING -> Icons.Default.Edit
    NativePage.MORE -> Icons.Default.MoreVert
    NativePage.RULES -> Icons.Default.Rule
    NativePage.PROMPTS -> Icons.Default.EditNote
    NativePage.SETTINGS -> Icons.Default.Settings
    NativePage.FILES -> Icons.Default.Folder
    NativePage.LOGS -> Icons.Default.BugReport
    NativePage.MODELS -> Icons.Default.Key
}

internal fun TextItemStatus.label(): Int = when (this) {
    TextItemStatus.NONE -> R.string.native_status_none
    TextItemStatus.PROCESSED -> R.string.native_status_processed
    TextItemStatus.ERROR -> R.string.native_status_error
    TextItemStatus.EXCLUDED -> R.string.native_status_excluded
    TextItemStatus.RULE_SKIPPED -> R.string.native_status_rule_skipped
    TextItemStatus.LANGUAGE_SKIPPED -> R.string.native_status_language_skipped
    TextItemStatus.DUPLICATED -> R.string.native_status_duplicated
}

internal fun TextItemStatus.tone(): NativeTone = when (this) {
    TextItemStatus.PROCESSED -> NativeTone.SUCCESS
    TextItemStatus.ERROR -> NativeTone.ERROR
    TextItemStatus.NONE -> NativeTone.PRIMARY
    TextItemStatus.EXCLUDED,
    TextItemStatus.RULE_SKIPPED,
    TextItemStatus.LANGUAGE_SKIPPED,
    TextItemStatus.DUPLICATED -> NativeTone.NEUTRAL
}

/** Five-language labels for reviewed TXT/EPUB creation; no label is assembled from raw keys. */
@Composable
private fun projectImportLabels(): NativeProjectImportLabels {
    val context = LocalContext.current
    val issues = mapOf(
        NativeImportIssueCode.UNSUPPORTED_EXTENSION to stringResource(R.string.native_import_issue_unsupported),
        NativeImportIssueCode.EMPTY_FILE to stringResource(R.string.native_import_issue_empty),
        NativeImportIssueCode.FILE_TOO_LARGE to stringResource(R.string.native_import_issue_too_large),
        NativeImportIssueCode.DUPLICATE_SOURCE to stringResource(R.string.native_import_issue_duplicate_source),
        NativeImportIssueCode.DUPLICATE_NAME to stringResource(R.string.native_import_issue_duplicate_name),
        NativeImportIssueCode.PROJECT_NAME_CONFLICT to stringResource(R.string.native_import_issue_project_conflict),
    )
    val txtSeed = stringResource(R.string.native_import_seed_txt)
    val epubSeed = stringResource(R.string.native_import_seed_epub)
    val txtFormat = stringResource(R.string.native_format_txt)
    val epubFormat = stringResource(R.string.native_format_epub)
    return NativeProjectImportLabels(
        title = stringResource(R.string.native_import_title),
        description = stringResource(R.string.native_import_description),
        note = stringResource(R.string.native_import_note),
        chooseFiles = stringResource(R.string.native_import_choose_files),
        chooseDirectory = stringResource(R.string.native_import_choose_directory),
        noFiles = stringResource(R.string.native_import_no_files),
        projectName = stringResource(R.string.native_import_project_name),
        seed = stringResource(R.string.native_import_seed),
        format = stringResource(R.string.native_import_format),
        size = stringResource(R.string.native_import_size),
        encoding = stringResource(R.string.native_import_encoding),
        unknownEncoding = stringResource(R.string.native_import_unknown_encoding),
        remove = stringResource(R.string.native_import_remove),
        issues = stringResource(R.string.native_import_issues),
        create = stringResource(R.string.native_import_create),
        cancel = stringResource(R.string.native_cancel),
        formatText = { format ->
            when (format) {
                NativeImportFormat.TXT -> txtFormat
                NativeImportFormat.EPUB -> epubFormat
            }
        },
        seedText = { seed ->
            when (seed) {
                NativeProjectSeed.TXT_TEXT -> txtSeed
                NativeProjectSeed.EPUB_FIRST_ASSET -> epubSeed
            }
        },
        issueText = { code -> issues.getValue(code) },
        bytesText = { bytes -> android.text.format.Formatter.formatFileSize(context, bytes) },
    )
}

/** Five-language labels for the read-only legacy panel. */
@Composable
private fun legacyExportLabels(): NativeLegacyExportLabels {
    val context = LocalContext.current
    val phases = mapOf(
        com.breakyuna.noveltranslator.legacy.LegacyExportPhase.PREPARING to
            stringResource(R.string.native_legacy_export_phase_preparing),
        com.breakyuna.noveltranslator.legacy.LegacyExportPhase.BOOK to
            stringResource(R.string.native_legacy_export_phase_book),
        com.breakyuna.noveltranslator.legacy.LegacyExportPhase.EDITION to
            stringResource(R.string.native_legacy_export_phase_edition),
        com.breakyuna.noveltranslator.legacy.LegacyExportPhase.CHAT to
            stringResource(R.string.native_legacy_export_phase_chat),
        com.breakyuna.noveltranslator.legacy.LegacyExportPhase.FINALIZING to
            stringResource(R.string.native_legacy_export_phase_finalizing),
        com.breakyuna.noveltranslator.legacy.LegacyExportPhase.COMPLETED to
            stringResource(R.string.native_legacy_export_phase_completed),
    )
    return NativeLegacyExportLabels(
        title = stringResource(R.string.native_legacy_export_title),
        description = stringResource(R.string.native_legacy_export_description),
        privacyNote = stringResource(R.string.native_legacy_export_privacy),
        outputDirectory = stringResource(R.string.native_legacy_export_output_directory),
        chooseDirectory = stringResource(R.string.native_legacy_export_choose_directory),
        noDirectory = stringResource(R.string.native_legacy_export_no_directory),
        start = stringResource(R.string.native_legacy_export_start),
        cancel = stringResource(R.string.native_cancel),
        close = stringResource(R.string.native_legacy_export_close),
        idle = stringResource(R.string.native_legacy_export_idle),
        running = stringResource(R.string.native_legacy_export_running),
        completed = stringResource(R.string.native_legacy_export_completed),
        failed = stringResource(R.string.native_legacy_export_failed),
        cancelled = stringResource(R.string.native_legacy_export_cancelled_state),
        failures = stringResource(R.string.native_legacy_export_failures),
        noFailures = stringResource(R.string.native_legacy_export_no_failures),
        progressText = { completed, total ->
            context.getString(R.string.native_legacy_export_progress, completed, total)
        },
        phaseText = { phase -> phases.getValue(phase) },
        resultText = { files, bytes, failures ->
            context.getString(
                R.string.native_legacy_export_result,
                files,
                android.text.format.Formatter.formatFileSize(context, bytes),
                failures,
            )
        },
        failureText = { failure ->
            context.getString(
                R.string.native_legacy_export_failure,
                failure.scope,
                failure.message,
                failure.path ?: "",
            )
        },
    )
}
