package com.breakyuna.noveltranslator.nativeapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeProjectApp(model: NativeProjectViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var tasksVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var queueVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var queueRefresh by rememberSaveable(state.project?.ref) { mutableStateOf(0) }
    var failureReview by remember(state.project?.ref) { mutableStateOf<TranslationTaskQueueIdentity?>(null) }
    if (queueVisible && state.project != null) NativeTranslationQueueDialog(
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
    if (tasksVisible && state.project != null) NativeTranslationTasksDialog(state.project!!.ref,
        model::translationTaskSummaries, model::inspectTranslationResume,
        { tasksVisible = false }, { spec -> tasksVisible = false; model.resumeTranslationTask(spec) },
        loadPage = model::translationTaskPage,
        inspectReview = model::reviewTranslationTask, terminate = model::terminateTranslationTask,
        loadMembers = model::translationTaskMembers, prepareRetry = model::prepareTaskRetry,
        keepErrors = model::keepTaskErrors, queueManagedRuns = state.queueManagedRuns)
    var usageVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var settingsVisible by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    var appearanceVisible by rememberSaveable { mutableStateOf(false) }
    var agentSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var reviewedItemProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<ItemProposalSummary?>(null) }
    var reviewedPolicyProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<PolicyApprovalSummary?>(null) }
    var reviewedPdfPageProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<PdfPageProposalSummary?>(null) }
    var reviewedAssetImportProposal by remember(state.project?.ref, state.agentChatId) { mutableStateOf<AssetImportProposalSummary?>(null) }
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
    var pendingAppBackup by remember { mutableStateOf<android.net.Uri?>(null) }
    val openAppBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) pendingAppBackup = it
    }
    pendingAppBackup?.let { uri -> AlertDialog(onDismissRequest = { pendingAppBackup = null },
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
    if (skillsVisible) NativeSkillsDialog(model.skillActions,
        dismiss = { skillsVisible = false },
        openPersona = { skillsVisible = false; model.openSettings(NativeSettingsSection.BASIC) })
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
    val context = LocalContext.current
    val activity = context.findActivity()
    var notificationsGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsGranted = it }
    val snackbar = remember { SnackbarHostState() }
    val inputTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importFile(it, false) }
    val inputProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importFile(it, true) }
    val inputAsset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.addAssetFile(it) }
    val saveAsset = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { model.completeAssetExport(it) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), model::completeExport)
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), model::completeExport)
    val saveBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::completeBackupExport)
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importBackup(it) }
    val saveProjectSet = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"), model::completeProjectSetExport)
    val openProjectSet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importProjectSet(it) }
    val message = state.error ?: state.notice
    val text = message?.let { stringResource(it) }
    LaunchedEffect(text) { if (text != null) { snackbar.showSnackbar(text); model.dismissMessage() } }
    var discard by rememberSaveable(state.project?.ref?.instanceId?.value, state.editor?.item?.id?.value) { mutableStateOf(false) }
    var discardPolicyHome by rememberSaveable(state.project?.ref) { mutableStateOf(false) }
    fun home() {
        if (state.policyDraft?.dirty == true) discardPolicyHome = true else model.home()
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
            state.project != null && state.page == NativePage.RULES -> model.selectPage(NativePage.MORE)
            state.project != null && state.page != NativePage.AGENT -> model.selectPage(NativePage.AGENT)
            state.project != null && !state.busy -> home()
        }
    }
    BackHandler(enabled = state.busy || state.project != null || state.editor != null || state.page == NativePage.SETTINGS || state.page == NativePage.MODELS) { back() }
    NativeTheme(state.themeMode) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rail = maxWidth >= 600.dp && state.project != null
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = { TopAppBar(
                    title = { Column {
                        Text(state.project?.name ?: when (state.page) {
                            NativePage.SETTINGS -> stringResource(R.string.native_settings_title)
                            NativePage.MODELS -> stringResource(R.string.native_models_title)
                            else -> stringResource(R.string.native_projects)
                        },
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.project?.let { Text(it.assetPath.value, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } },
                    navigationIcon = { if (state.project != null) IconButton(onClick = { home() }, enabled = !state.busy && state.editor == null) {
                        Icon(Icons.Default.Home, stringResource(R.string.native_projects))
                    } },
                    actions = {
                        if (state.project == null && state.page != NativePage.SETTINGS && state.page != NativePage.MODELS) {
                            IconButton(onClick = { model.openSettings(NativeSettingsSection.BASIC) }, enabled = !state.busy) {
                                Icon(Icons.Default.Settings, stringResource(R.string.native_settings_title))
                            }
                            IconButton(onClick = model::openModels, enabled = !state.busy) {
                                Icon(Icons.Default.Key, stringResource(R.string.native_more_models))
                            }
                        }
                        if (state.project != null) TextButton(onClick = { tasksVisible = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.native_tasks_title))
                        }
                        if (state.project != null) TextButton(onClick = { queueVisible = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.native_queue_title))
                        }
                        TextButton(onClick = { usageVisible = true }) { Text(stringResource(R.string.native_ledger_title)) }
                        IconButton(onClick = model::refresh, enabled = !state.busy && state.editor == null) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.native_refresh))
                    } },
                ) },
                bottomBar = { if (state.project != null && !rail) NavigationBar {
                    NativeNavigationPages.forEach { page -> NavigationBarItem(
                        selected = state.page == page || (page == NativePage.MORE && (state.page == NativePage.RULES || state.page == NativePage.SETTINGS)), onClick = { model.selectPage(page) }, enabled = (!state.busy || state.foregroundExecutionActive) && state.editor == null && state.manualBatch == null,
                        icon = { Icon(page.icon(), contentDescription = null) }, label = { Text(stringResource(page.label())) },
                    ) }
                } },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (rail) NavigationRail { NativeNavigationPages.forEach { page -> NavigationRailItem(
                        selected = state.page == page || (page == NativePage.MORE && (state.page == NativePage.RULES || state.page == NativePage.SETTINGS)), onClick = { model.selectPage(page) }, enabled = (!state.busy || state.foregroundExecutionActive) && state.editor == null && state.manualBatch == null,
                        icon = { Icon(page.icon(), null) }, label = { Text(stringResource(page.label())) },
                    ) } }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.busy && (state.foregroundExecutionActive || state.project == null || state.page != NativePage.AGENT || state.editor != null)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(if (state.foregroundTranslationPausing) R.string.native_background_pausing else R.string.native_working), Modifier.weight(1f).padding(top = 12.dp))
                                if (state.foregroundTranslationCanPause) TextButton(onClick = model::pauseTranslation) {
                                    Text(stringResource(R.string.native_pause))
                                }
                                TextButton(onClick = model::cancelAction) { Text(stringResource(R.string.native_cancel)) }
                            }
                        }
                        key(state.project?.ref?.instanceId?.value, state.project?.ref?.generation?.value, state.page) {
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
                            ) else if (state.project == null) {
                                ProjectsHome(state, onCreate = { if (model.prepareImport(false)) inputTxt.launch(arrayOf("text/*", "application/octet-stream")) },
                                    onImport = { if (model.prepareImport(true)) inputProject.launch(arrayOf("*/*")) }, onOpen = model::open,
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
                                    workspace = model.workspaceActions,
                                    saveWorkspaceArtifact = { artifact ->
                                        model.prepareWorkspaceArtifactExport(artifact)?.let(saveWorkspaceArtifact::launch)
                                    })
                                NativePage.WORKBENCH -> Workbench(
                                    state = state,
                                    cleanup = model::cleanupOutputs,
                                    export = { kind ->
                                        model.prepareExport(kind)?.let { name ->
                                            if (kind == NativeExport.PROJECT) saveProject.launch(name) else saveText.launch(name)
                                        }
                                    },
                                    addAsset = {
                                        if (model.prepareAddAsset()) inputAsset.launch(arrayOf("*/*"))
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
                                    onRules = model::openRules,
                                    onModels = model::openModels,
                                    onSettings = { settingsVisible = true },
                                    onAppearance = { appearanceVisible = true },
                                    onLogs = model::openLogs,
                                    onSkills = { skillsVisible = true },
                                    onBackupExport = { saveAppBackup.launch("lnt-app-backup.zip") },
                                    onBackupImport = { openAppBackup.launch(arrayOf("application/zip", "application/octet-stream")) },
                                    notificationsGranted = notificationsGranted,
                                    requestNotifications = {
                                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    },
                                    cleanup = model::cleanupOutputs,
                                    refresh = model::refresh,
                                    refreshStorage = model::refreshStorageUsage,
                                    onAgentSettings = { agentSettingsVisible = true },
                                    onAppSettings = { model.openSettings(NativeSettingsSection.BASIC) },
                                    onExpertSettings = { model.openSettings(NativeSettingsSection.EXPERT) },
                                    onFiles = model::openFiles,
                                )
                                NativePage.FILES -> NativeFilesPage(
                                    state = state, loadInventory = model::fileInventory,
                                    back = model::closeFiles,
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
                                NativePage.RULES -> NativeTextRulesPage(state, model::changeTextPolicy, model::saveTextPolicy, model::reloadTextPolicy,
                                    model.rulePresetActions, model.promptTemplateActions,
                                    section = state.rulesSection,
                                    selectSection = model::selectRulesSection,
                                    back = { model.selectPage(NativePage.MORE) })
                                NativePage.SETTINGS -> Unit
                                NativePage.MODELS -> Unit
                                NativePage.PROOFREADING -> Proofreading(state, model::edit, model::previousPage, model::nextPage,
                                    model::simulatePage, notificationsGranted, model::queryProofreading,
                                    NativeBatchActions(model::selectItems, model::toggleItem, model::selectPageItems,
                                        model::prepareManualBatch, model::confirmManualBatch, model::dismissManualBatch, model::prepareQueryBatch,
                                        model::prepareQueryReplacement, model::prepareQueryNameAssignment),
                                    model::simulateProject, model::translateRemote,
                                    model::enqueueRemoteTranslation,
                                    qualityActions = NativeQualityActions(model::scanQuality, model::filterQuality, model::clearQuality)) {
                                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (discardPolicyHome) AlertDialog(onDismissRequest = { discardPolicyHome = false },
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
                onUndo = model::undoEditor, onRedo = model::redoEditor)
        }
        state.nameEdit?.let { names ->
            NativeNameEditDialog(names, state.busy, model::dismissNameEdit, model::saveNameEdit)
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.native_discard_title)) },
            text = { Text(stringResource(R.string.native_discard_body)) },
            confirmButton = { TextButton(onClick = { discard = false; model.closeEditor() }) { Text(stringResource(R.string.native_discard)) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_keep_editing)) } },
        )
    }
}

@Composable
private fun formatLabel(format: com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat): String =
    when (format) {
        com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat.RPGMAKER_RGSS ->
            stringResource(R.string.native_format_rpgmaker_rgss)
        else -> format.name
    }

@Composable
internal fun ProjectsHome(state: NativeProjectUiState, onCreate: () -> Unit, onImport: () -> Unit,
                         onOpen: (LocalProjectRef) -> Unit, onCopy: (LocalProjectRef) -> Unit, onEncoding: (TxtEncoding) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.native_home_title), style = MaterialTheme.typography.headlineSmall) }
        item { Text(stringResource(R.string.native_home_description), style = MaterialTheme.typography.bodyMedium) }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreate, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_create_txt)) }
            OutlinedButton(onClick = onImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_import_project)) }
            Box {
                TextButton(onClick = { expanded = true }, enabled = !state.busy) { Text(stringResource(R.string.native_encoding, state.encoding.charsetName)) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { TxtEncoding.entries.forEach { encoding ->
                    DropdownMenuItem(text = { Text(encoding.charsetName) }, onClick = { onEncoding(encoding); expanded = false })
                } }
            }
        } }
        if (state.legacyData) item { Text(stringResource(R.string.native_legacy_preserved), style = MaterialTheme.typography.bodySmall) }
        if (state.recoveryProblems > 0) item { Text(stringResource(R.string.native_recovery_warning, state.recoveryProblems), color = MaterialTheme.colorScheme.error) }
        item { Text(stringResource(R.string.native_registered_projects), style = MaterialTheme.typography.titleMedium) }
        if (state.projects.isEmpty()) item { Text(stringResource(R.string.native_empty_projects)) }
        items(state.projects, key = { it.ref.instanceId.value }) { entry ->
            Card(Modifier.fillMaxWidth()) {
                ListItem(
                    modifier = Modifier.clickable(enabled = entry.readable && !state.busy) { onOpen(entry.ref) },
                    headlineContent = { Text(entry.summary?.name ?: stringResource(R.string.native_unavailable)) },
                    supportingContent = { Text(entry.summary?.let { stringResource(R.string.native_item_count, it.itemCount) }
                        ?: stringResource(R.string.native_unavailable_description)) },
                    leadingContent = { Icon(Icons.Default.FolderOpen, null) },
                    trailingContent = { if (entry.readable) IconButton(onClick = { onCopy(entry.ref) }, enabled = !state.busy) {
                        Icon(Icons.Default.ContentCopy, stringResource(R.string.native_copy))
                    } },
                )
            }
        }
    }
}

@Composable
internal fun Workbench(state: NativeProjectUiState, cleanup: () -> Unit, export: (NativeExport) -> Unit,
    addAsset: () -> Unit, exportAsset: (Long) -> Unit,
    mutateAsset: ((NativeAssetMutation) -> Unit)? = null,
    editSettings: ((NativeProjectSettingsChange) -> Unit)? = null,
    exportAssetBilingual: ((Long) -> Unit)? = null,
    exportBackup: (() -> Unit)? = null, importBackup: (() -> Unit)? = null,
    exportAllBackups: (() -> Unit)? = null, importAllBackups: (() -> Unit)? = null,
    loadPdfPage: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> TextProjectItem)? = null,
    loadPdfThumbnail: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> ByteArray)? = null,
    editPdfPage: ((LocalProjectRef, Long, ProjectAssetSummary, Int) -> Unit)? = null,
    openFiles: (() -> Unit)? = null) {
    val project = requireNotNull(state.project)
    var settingsOpen by remember(project.ref) { mutableStateOf(false) }
    var pdfAsset by remember(project.ref) { mutableStateOf<ProjectAssetSummary?>(null) }
    pdfAsset?.let { asset -> if (editSettings != null) NativePdfPagesDialog(state, asset,
        { pdfAsset = null }, editSettings, loadPdfPage, loadPdfThumbnail, editPdfPage) }
    if (settingsOpen && editSettings != null) NativeProjectSettingsDialog(state, { settingsOpen = false }, editSettings)
    var confirmation by remember(project.ref, project.revision) { mutableStateOf<NativeAssetMutation?>(null) }
    val canManage = !state.busy && state.editor == null && state.manualBatch == null && !state.selectingItems &&
        state.policyDraft?.dirty != true && !state.policyLoadIncomplete && !state.runLoadIncomplete
    fun intent(assetId: Long, change: NativeAssetChange) = NativeAssetMutation(project.ref, project.revision,
        assetId, change, com.breakyuna.noveltranslator.nativecore.domain.CommandId(java.util.UUID.randomUUID().toString()))
    confirmation?.let { fixed ->
        val asset = project.assets.firstOrNull { it.id == fixed.assetId }
        if (asset != null) AlertDialog(onDismissRequest = { if (!state.busy) confirmation = null },
            title = { Text(stringResource(if (fixed.change == NativeAssetChange.REMOVE) R.string.native_asset_remove else R.string.native_asset_reset)) },
            text = { Text(stringResource(if (fixed.change == NativeAssetChange.REMOVE) R.string.native_asset_remove_warning else R.string.native_asset_reset_warning,
                asset.path.value, asset.itemCount)) },
            confirmButton = { TextButton(onClick = { confirmation = null; mutateAsset?.invoke(fixed) }, enabled = canManage) {
                Text(stringResource(R.string.native_asset_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !state.busy) {
                Text(stringResource(R.string.native_cancel)) } })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.native_workbench), style = MaterialTheme.typography.headlineSmall) }
        if (openFiles != null) item {
            OutlinedButton(onClick = openFiles, enabled = canManage, modifier = Modifier.testTag("native-workbench-files")) {
                Text(stringResource(R.string.native_files_title))
            }
        }
        if (editSettings != null) item {
            OutlinedButton(onClick = { settingsOpen = true }, enabled = canManage) {
                Text(stringResource(R.string.native_project_settings))
            }
        }
        if (exportBackup != null || importBackup != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exportBackup != null) OutlinedButton(onClick = exportBackup, enabled = canManage) {
                    Text(stringResource(R.string.native_backup_export))
                }
                if (importBackup != null) OutlinedButton(onClick = importBackup, enabled = canManage) {
                    Text(stringResource(R.string.native_backup_import))
                }
            }
        }
        if (exportAllBackups != null || importAllBackups != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exportAllBackups != null) OutlinedButton(onClick = exportAllBackups, enabled = canManage) {
                    Text(stringResource(R.string.native_project_set_export))
                }
                if (importAllBackups != null) OutlinedButton(onClick = importAllBackups, enabled = canManage) {
                    Text(stringResource(R.string.native_project_set_import))
                }
            }
        }
        item { Text(state.project!!.assetPath.value) }
        item { Text(stringResource(R.string.native_item_count, state.project!!.itemCount)) }
        item { Text(stringResource(R.string.native_translation_pending)) }
        if (state.recentRuns.isNotEmpty()) item { NativeRunHistory(state) }
        item {
            Text(stringResource(R.string.native_assets), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.native_assets_description), style = MaterialTheme.typography.bodySmall)
        }
        val assets = state.project?.assets.orEmpty()
        if (assets.isEmpty()) item { Text(stringResource(R.string.native_asset_empty)) }
        items(assets, key = { "asset-" + it.id }) { asset ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(asset.path.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.native_asset_summary, formatLabel(asset.format), asset.itemCount),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { exportAsset(asset.id) }, enabled = !state.busy) {
                        Text(stringResource(R.string.native_export_asset))
                    }
                }
                if (exportAssetBilingual != null) TextButton(onClick = { exportAssetBilingual(asset.id) }, enabled = !state.busy) {
                    Text(stringResource(R.string.native_export_bilingual))
                }
                if (asset.format == ProjectAssetFormat.PDF && editSettings != null) {
                    TextButton(onClick = { pdfAsset = asset }, enabled = canManage && asset.itemCount in 1..5_000) {
                        Text(stringResource(R.string.native_pdf_pages))
                    }
                }
                if (mutateAsset != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(asset.enabled, onCheckedChange = { enabled ->
                            mutateAsset(intent(asset.id, if (enabled) NativeAssetChange.ENABLE else NativeAssetChange.DISABLE))
                        }, enabled = canManage)
                        Text(stringResource(R.string.native_asset_translation_enabled), modifier = Modifier.weight(1f))
                    }
                    val index = assets.indexOfFirst { it.id == asset.id }
                    Row {
                        TextButton(onClick = { mutateAsset(intent(asset.id, NativeAssetChange.MOVE_UP)) }, enabled = canManage && index > 0) {
                            Text(stringResource(R.string.native_asset_move_up))
                        }
                        TextButton(onClick = { mutateAsset(intent(asset.id, NativeAssetChange.MOVE_DOWN)) }, enabled = canManage && index < assets.lastIndex) {
                            Text(stringResource(R.string.native_asset_move_down))
                        }
                    }
                    Row {
                        TextButton(onClick = { confirmation = intent(asset.id, NativeAssetChange.RESET) }, enabled = canManage) {
                            Text(stringResource(R.string.native_asset_reset))
                        }
                        TextButton(onClick = { confirmation = intent(asset.id, NativeAssetChange.REMOVE) }, enabled = canManage && assets.size > 1) {
                            Text(stringResource(R.string.native_asset_remove))
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = addAsset, enabled = !state.busy) {
                Text(stringResource(R.string.native_add_asset))
            }
        }
        item { Button(onClick = { export(NativeExport.PROJECT) }, enabled = !state.busy) { Text(stringResource(R.string.native_save_project)) } }
        item { OutlinedButton(onClick = { export(NativeExport.TRANSLATION) }, enabled = !state.busy) { Text(stringResource(R.string.native_export_translation)) } }
        item { OutlinedButton(onClick = { export(NativeExport.BILINGUAL) }, enabled = !state.busy) { Text(stringResource(R.string.native_export_bilingual)) } }
        item { OutlinedButton(onClick = { export(NativeExport.ORIGINAL) }, enabled = !state.busy) { Text(stringResource(R.string.native_export_original)) } }
        item { Text(stringResource(R.string.native_export_description), style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = cleanup, enabled = !state.busy) { Text(stringResource(R.string.native_cleanup)) } }
    }
}

@Composable
internal fun Proofreading(state: NativeProjectUiState, edit: (ItemId) -> Unit, previous: () -> Unit, next: () -> Unit,
                          simulate: () -> Unit, notificationsGranted: Boolean = true,
                          query: (TextItemQuery) -> Unit = {}, batchActions: NativeBatchActions? = null,
                          simulateProject: (() -> Unit)? = null,
                          remoteTranslation: NativeRemoteTranslationAction? = null,
                          queueTranslation: NativeRemoteTranslationAction? = null,
                          qualityActions: NativeQualityActions? = null, requestNotifications: () -> Unit = {}) {
    if (state.manualBatch != null && batchActions != null) NativeManualBatchDialog(state, batchActions)
    var filtering by rememberSaveable(state.project?.ref?.projectId?.value, state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value) { mutableStateOf(false) }
    if (filtering) NativeProofreadingQueryDialog(state, onDismiss = { filtering = false }) {
        filtering = false; query(it)
    }
    var confirm by rememberSaveable(state.project?.ref?.projectId?.value, state.project?.ref?.instanceId?.value, state.project?.ref?.generation?.value,
        state.items.firstOrNull()?.id?.value) { mutableStateOf(false) }
    var wholeProject by rememberSaveable(state.project?.ref?.projectId?.value, state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value) { mutableStateOf(false) }
    val enabledAssets = state.project?.assets.orEmpty().filter { it.enabled }.map { it.id }.toSet()
    val pendingPage = state.items.any { it.status == TextItemStatus.NONE && it.hasTranslatableSource &&
        (state.project?.assets.isNullOrEmpty() || it.assetId in enabledAssets) }
    val pendingProject = simulateProject != null && (state.itemStatistics?.translatableUnprocessed ?: 0) > 0
    var remote by rememberSaveable(state.project?.ref, state.items.firstOrNull()?.id?.value) { mutableStateOf(false) }
    if (remote && remoteTranslation != null && state.translationModelSettings != null)
        NativeRemoteTranslationDialog(state, onDismiss = { remote = false }, start = remoteTranslation, queue = queueTranslation)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.native_simulation)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                if (!notificationsGranted) TextButton(onClick = requestNotifications, enabled = !state.busy) {
                    Text(stringResource(R.string.native_background_notifications))
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            confirm = false
            if (wholeProject) simulateProject?.invoke() else simulate()
        }, enabled = !state.busy && !state.runLoadIncomplete && !state.policyLoadIncomplete && state.policyDraft?.dirty != true && !state.proofreadingQuery.active && state.qualityView == null &&
            (if (wholeProject) pendingProject else pendingPage)) {
            Text(stringResource(R.string.native_simulation_start))
        } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.native_cancel)) } },
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerHeight = maxHeight * 0.55f
        Column(Modifier.fillMaxSize()) {
            key(state.project?.ref?.projectId?.value, state.project?.ref?.instanceId?.value, state.project?.ref?.generation?.value) {
                Column(Modifier.fillMaxWidth().heightIn(max = headerHeight).verticalScroll(rememberScrollState())) {
                    if (qualityActions != null) NativeQualityPanel(state, qualityActions)
                    if (state.policyDraft?.dirty == true) Text(stringResource(R.string.native_policy_unsaved), Modifier.padding(horizontal = 16.dp))
                    if (remoteTranslation != null) {
                        TextButton(onClick = { remote = true }, modifier = Modifier.fillMaxWidth(),
                            enabled = state.translationModelSettings != null && !state.policyLoadIncomplete && state.policyDraft?.dirty != true && !state.busy && state.editor == null && state.manualBatch == null &&
                                !state.selectingItems && !state.runLoadIncomplete && !state.proofreadingQuery.active && state.qualityView == null &&
                                (pendingPage || pendingProject || (state.itemStatistics?.processed ?: 0) > 0 || (state.itemStatistics?.errors ?: 0) > 0)) {
                            Text(stringResource(R.string.native_remote_translate))
                        }
                        if (state.translationModelSettings == null) Text(stringResource(R.string.native_remote_configure), Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { wholeProject = !pendingPage && pendingProject; confirm = true }, modifier = Modifier.weight(1f),
                            enabled = !state.busy && state.policyDraft?.dirty != true && !state.policyLoadIncomplete && !state.selectingItems && !state.runLoadIncomplete && !state.proofreadingQuery.active && state.qualityView == null && (pendingPage || pendingProject)) {
                            Text(stringResource(R.string.native_simulation), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { filtering = true }, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.native_proof_filter), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (batchActions != null) TextButton(onClick = { batchActions.selecting(!state.selectingItems) },
                            enabled = !state.busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(if (state.selectingItems) R.string.native_batch_cancel_selection else R.string.native_batch_select),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (batchActions != null) NativeQueryBatchButton(state, batchActions)
                    if (state.selectingItems && batchActions != null) NativeBatchToolbar(state, batchActions)
                    if (state.proofreadingQuery.active) state.matchingItems?.let { count ->
                        Text(stringResource(R.string.native_proof_matching, count), Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.runLoadIncomplete) Text(stringResource(R.string.native_runs_load_error), color = MaterialTheme.colorScheme.error)
                    state.translationProgress?.let { Text(stringResource(R.string.native_simulation_progress, it.committedItems, it.requests),
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                    state.translationProgress?.let { progress -> progress.usageTotals?.let { totals ->
                        state.project?.ref?.let { NativeTranslationUsage(it, totals, progress.run) }
                    } }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = previous, enabled = !state.busy && state.hasPrevious && (state.qualityView == null || state.qualityCurrent)) { Text(stringResource(R.string.native_previous)) }
                        TextButton(onClick = next, enabled = !state.busy && state.hasNext && (state.qualityView == null || state.qualityCurrent)) { Text(stringResource(R.string.native_next)) }
                    }
                }
            }
            key(state.project?.ref?.projectId?.value, state.project?.ref?.instanceId?.value,
                state.project?.ref?.generation?.value, state.items.firstOrNull()?.id?.value) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.items.isEmpty()) item { Text(stringResource(R.string.native_empty_items)) }
                    items(state.items, key = { it.id.value }) { item ->
                        val selectionLabel = stringResource(R.string.native_batch_select_row, item.row.toLong() + 1)
                        Card(Modifier.fillMaxWidth().clickable(enabled = !state.busy) {
                            if (state.selectingItems && batchActions != null) batchActions.toggle(item.id) else edit(item.id)
                        }) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row {
                                    if (state.selectingItems && batchActions != null) Checkbox(item.id in state.selectedItems,
                                        onCheckedChange = { batchActions.toggle(item.id) }, enabled = !state.busy,
                                        modifier = Modifier.semantics { contentDescription = selectionLabel })
                                    Text(stringResource(R.string.native_row_status, item.row + 1, stringResource(item.status.label())), style = MaterialTheme.typography.labelLarge)
                                }
                                if (state.qualityCurrent) state.qualityView?.index?.fact(item.id)?.result?.let { result ->
                                    val summary = when {
                                        !result.evaluated -> stringResource(R.string.native_quality_skipped)
                                        result.codes.isEmpty() -> stringResource(R.string.native_quality_clear)
                                        else -> stringResource(R.string.native_quality_row_warnings, result.codes.size)
                                    }
                                    Text(summary, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(item.source.ifEmpty { stringResource(R.string.native_blank_line) }, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                HorizontalDivider()
                                Text(item.translation.ifEmpty { stringResource(if (item.status == TextItemStatus.PROCESSED) R.string.native_empty_result else R.string.native_empty_translation) },
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.primary)
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
internal fun Editor(draft: NativeEditDraft, busy: Boolean, onDismiss: () -> Unit, onText: (String) -> Unit,
                   onStatus: (TextItemStatus) -> Unit, onSave: () -> Unit, onReload: () -> Unit, onCancel: () -> Unit,
                   quality: TextProofreadingResult? = null, qualityEnabled: Boolean = true, editorError: Int? = null,
                   onInspect: (TextProofreadingOptions) -> Unit = {}, editorNotice: Int? = null,
                   navigationEnabled: Boolean = true, onNavigate: ((TextItemDirection, Boolean) -> Unit)? = null,
                   replacement: NativeEditorReplacement? = null, onPrepareReplacement: ((TextReplacementOptions) -> Unit)? = null,
                   onApplyReplacement: ((NativeEditorReplacement) -> Unit)? = null,
                   onEditNames: (() -> Unit)? = null,
                   canUndo: Boolean = false, canRedo: Boolean = false,
                   onUndo: () -> Unit = {}, onRedo: () -> Unit = {}) {
    var replacing by rememberSaveable(draft.ref.projectId.value, draft.ref.instanceId.value,
        draft.ref.generation.value, draft.item.id.value) { mutableStateOf(false) }
    var pendingMove by rememberSaveable(draft.ref.projectId.value, draft.ref.instanceId.value,
        draft.ref.generation.value, draft.item.id.value, draft.command.value) { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (!busy && !draft.conflict && event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                when {
                    event.key == Key.Z && event.isShiftPressed && canRedo -> { onRedo(); true }
                    event.key == Key.Z && !event.isShiftPressed && canUndo -> { onUndo(); true }
                    event.key == Key.Y && canRedo -> { onRedo(); true }
                    else -> false
                }
            } else false
        }) {
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.native_edit_row, draft.item.row + 1)) },
                navigationIcon = { IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.ArrowBack, stringResource(R.string.native_back)) } },
                actions = { TextButton(onClick = onSave, enabled = !busy && !draft.conflict && draft.dirty) { Text(stringResource(R.string.native_save)) } },
            ) }) { padding ->
                Column(Modifier.padding(padding).imePadding().padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = onCancel) { Text(stringResource(R.string.native_cancel)) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onUndo, enabled = !busy && !draft.conflict && canUndo,
                            modifier = Modifier.testTag("native-editor-undo")) { Text(stringResource(R.string.native_editor_undo)) }
                        TextButton(onClick = onRedo, enabled = !busy && !draft.conflict && canRedo,
                            modifier = Modifier.testTag("native-editor-redo")) { Text(stringResource(R.string.native_editor_redo)) }
                    }
                    editorError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                    editorNotice?.let { Text(stringResource(it)) }
                    if (onNavigate != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextItemDirection.entries.forEach { direction ->
                            TextButton(onClick = {
                                if (draft.dirty) pendingMove = direction.name else onNavigate(direction, false)
                            }, enabled = !busy && !draft.conflict && navigationEnabled) {
                                Text(stringResource(if (direction == TextItemDirection.PREVIOUS) R.string.native_editor_previous else R.string.native_editor_next))
                            }
                        }
                    }
                    if (draft.conflict) {
                        Text(stringResource(R.string.native_conflict), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onReload, enabled = !busy) { Text(stringResource(R.string.native_reload_baseline)) }
                    }
                    if (onPrepareReplacement != null) OutlinedButton(onClick = { replacing = true }, enabled = !busy) {
                        Text(stringResource(R.string.native_replace_title))
                    }
                    if (onEditNames != null) OutlinedButton(onClick = onEditNames, enabled = !busy && !draft.conflict,
                        modifier = Modifier.testTag("native-edit-names")) {
                        Text(stringResource(R.string.native_names_edit))
                    }
                    Text(stringResource(R.string.native_source), style = MaterialTheme.typography.titleSmall)
                    Text(draft.item.source.ifEmpty { stringResource(R.string.native_blank_line) })
                    OutlinedTextField(value = draft.text, onValueChange = onText, enabled = !busy,
                        label = { Text(stringResource(R.string.native_translation)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                    NativeEditorQuality(draft, busy, qualityEnabled, quality, onInspect)
                    Text(stringResource(R.string.native_status), style = MaterialTheme.typography.titleSmall)
                    TextItemStatus.entries.filter { it.manual }.forEach { status -> Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) { onStatus(status) }.heightIn(min = 48.dp),
                    ) { RadioButton(selected = draft.status == status, onClick = { onStatus(status) }, enabled = !busy)
                        Text(stringResource(status.label()), Modifier.padding(top = 12.dp)) } }
                }
            }
        }
    }
    if (replacing && onPrepareReplacement != null && onApplyReplacement != null)
        NativeEditorReplacementDialog(draft, busy, editorError, replacement, { replacing = false }, onPrepareReplacement,
            { replacing = false; onApplyReplacement(it) }, onCancel)
    if (pendingMove != null && onNavigate != null) AlertDialog(onDismissRequest = { pendingMove = null },
        title = { Text(stringResource(R.string.native_discard_title)) },
        text = { Text(stringResource(R.string.native_editor_move_confirm)) },
        confirmButton = { TextButton(onClick = {
            val direction = TextItemDirection.valueOf(pendingMove!!)
            pendingMove = null; onNavigate(direction, true)
        }, enabled = !busy) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { pendingMove = null }) { Text(stringResource(R.string.native_keep_editing)) } })

}

private fun NativePage.label(): Int = when (this) {
    NativePage.AGENT -> R.string.native_agent
    NativePage.WORKBENCH -> R.string.native_workbench
    NativePage.PROOFREADING -> R.string.native_proofreading
    NativePage.MORE -> R.string.native_more
    NativePage.RULES -> R.string.native_policy_title
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
    NativePage.RULES -> Icons.Default.Settings
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
