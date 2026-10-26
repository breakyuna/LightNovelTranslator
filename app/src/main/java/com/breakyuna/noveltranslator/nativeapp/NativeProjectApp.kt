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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var reviewedWorkspaceChange by remember(state.project?.ref, state.agentChatId) { mutableStateOf<WorkspaceChangeProposalSummary?>(null) }
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
    var rgssAssetId by rememberSaveable(state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value) { mutableStateOf<Long?>(null) }
    val rgssProject = state.project
    val rgssAsset = rgssAssetId?.let { id -> rgssProject?.assets?.firstOrNull { it.id == id } }
    if (rgssProject != null && rgssAsset != null) NativeRgssInventoryDialog(rgssProject.ref, rgssAsset,
        model::rgssInventory, { rgssAssetId = null })
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
            val rail = maxWidth >= NativeRailBreakpoint && state.project != null
            val wide = maxWidth >= NativeTwoPaneBreakpoint && state.project != null
            val subpage = state.page == NativePage.SETTINGS || state.page == NativePage.MODELS ||
                state.page == NativePage.FILES || state.page == NativePage.LOGS || state.page == NativePage.RULES
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
                                        TextButton(
                                            onClick = { tasksVisible = true },
                                            enabled = !state.busy,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            Text(stringResource(R.string.native_tasks_title), maxLines = 1)
                                        }
                                        TextButton(
                                            onClick = { queueVisible = true },
                                            enabled = !state.busy,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        ) {
                                            Text(stringResource(R.string.native_queue_title), maxLines = 1)
                                        }
                                    }
                                    TextButton(
                                        onClick = { usageVisible = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(stringResource(R.string.native_ledger_title), maxLines = 1)
                                    }
                                    IconButton(
                                        onClick = model::refresh,
                                        enabled = !state.busy && state.editor == null && !ruleEditorOpen,
                                    ) {
                                        Icon(Icons.Default.Refresh, stringResource(R.string.native_refresh))
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
                                        onClick = { model.selectPage(page) },
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
                                    onClick = { model.selectPage(page) },
                                    enabled = (!state.busy || state.foregroundExecutionActive) && state.editor == null && state.manualBatch == null && !ruleEditorOpen,
                                    icon = { Icon(page.icon(), null) },
                                    label = { Text(stringResource(page.label()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                )
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.busy && (state.foregroundExecutionActive || state.project == null || state.page != NativePage.AGENT || state.editor != null)) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(if (state.foregroundTranslationPausing) R.string.native_background_pausing else R.string.native_working),
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
                                    reviewWorkspaceChange = { reviewedWorkspaceChange = it },
                                    workspace = model.workspaceActions,
                                    saveWorkspaceArtifact = { artifact ->
                                        model.prepareWorkspaceArtifactExport(artifact)?.let(saveWorkspaceArtifact::launch)
                                    },
                                    wideLayout = wide)
                                NativePage.WORKBENCH -> Workbench(
                                    state = state,
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
                                NativePage.RULES -> NativeTextRulesPage(state, model::changeTextPolicy, model::saveTextPolicy, model::reloadTextPolicy,
                                    model.rulePresetActions, model.promptTemplateActions,
                                    section = state.rulesSection,
                                    selectSection = model::selectRulesSection,
                                    back = { model.selectPage(NativePage.MORE) },
                                    wideLayout = wide,
                                    onEditingChange = { ruleEditorOpen = it })
                                NativePage.SETTINGS -> Unit
                                NativePage.MODELS -> Unit
                                NativePage.PROOFREADING -> Proofreading(state, model::edit, model::previousPage, model::nextPage,
                                    model::simulatePage, notificationsGranted, model::queryProofreading,
                                    NativeBatchActions(model::selectItems, model::toggleItem, model::selectPageItems,
                                        model::prepareManualBatch, model::confirmManualBatch, model::dismissManualBatch, model::prepareQueryBatch,
                                        model::prepareQueryReplacement, model::prepareQueryNameAssignment),
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
internal fun ProjectsHome(
    state: NativeProjectUiState,
    onCreate: () -> Unit,
    onImport: () -> Unit,
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
                subtitle = stringResource(R.string.native_home_description),
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
) {
    val project = requireNotNull(state.project)
    var settingsOpen by remember(project.ref) { mutableStateOf(false) }
    var pdfAsset by remember(project.ref) { mutableStateOf<ProjectAssetSummary?>(null) }
    pdfAsset?.let { asset ->
        if (editSettings != null) {
            NativePdfPagesDialog(state, asset, { pdfAsset = null }, editSettings, loadPdfPage, loadPdfThumbnail, editPdfPage)
        }
    }
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
            AlertDialog(
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
        modifier = Modifier.fillMaxSize(),
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
                                modifier = Modifier.weight(1f).testTag("native-workbench-files"),
                            ) {
                                Text(stringResource(R.string.native_files_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (editSettings != null) {
                            OutlinedButton(
                                onClick = { settingsOpen = true },
                                enabled = canManage,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.native_project_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (exportBackup != null || importBackup != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (exportBackup != null) {
                            OutlinedButton(
                                onClick = exportBackup,
                                enabled = canManage,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.native_backup_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (importBackup != null) {
                            OutlinedButton(
                                onClick = importBackup,
                                enabled = canManage,
                                modifier = Modifier.weight(1f),
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
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.native_project_set_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (importAllBackups != null) {
                            OutlinedButton(
                                onClick = importAllBackups,
                                enabled = canManage,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.native_project_set_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        if (state.recentRuns.isNotEmpty()) {
            item(key = "workbench-runs") { NativeRunHistory(state) }
        }
        val assets = state.project?.assets.orEmpty()
        item(key = "assets-header") {
            NativeSectionCard(
                title = stringResource(R.string.native_assets),
                subtitle = stringResource(R.string.native_assets_description),
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
                                modifier = Modifier.weight(1f),
                            )
                        }
                        val index = assets.indexOfFirst { it.id == asset.id }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { mutateAsset(intent(asset.id, NativeAssetChange.MOVE_UP)) },
                                enabled = canManage && index > 0,
                            ) {
                                Text(stringResource(R.string.native_asset_move_up))
                            }
                            TextButton(
                                onClick = { mutateAsset(intent(asset.id, NativeAssetChange.MOVE_DOWN)) },
                                enabled = canManage && index < assets.lastIndex,
                            ) {
                                Text(stringResource(R.string.native_asset_move_down))
                            }
                            TextButton(
                                onClick = { confirmation = intent(asset.id, NativeAssetChange.RESET) },
                                enabled = canManage,
                            ) {
                                Text(stringResource(R.string.native_asset_reset))
                            }
                            TextButton(
                                onClick = { confirmation = intent(asset.id, NativeAssetChange.REMOVE) },
                                enabled = canManage && assets.size > 1,
                            ) {
                                Text(stringResource(R.string.native_asset_remove))
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
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.native_export_translation), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { export(NativeExport.BILINGUAL) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
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
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.native_export_original), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(
                        onClick = cleanup,
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.native_cleanup), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    text = stringResource(R.string.native_export_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    var filtering by rememberSaveable(
        state.project?.ref?.projectId?.value,
        state.project?.ref?.instanceId?.value,
        state.project?.ref?.generation?.value,
    ) { mutableStateOf(false) }
    if (filtering) {
        NativeProofreadingQueryDialog(state, onDismiss = { filtering = false }) {
            filtering = false
            query(it)
        }
    }
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
    if (remote && remoteTranslation != null && state.translationModelSettings != null) {
        NativeRemoteTranslationDialog(state, onDismiss = { remote = false }, start = remoteTranslation, queue = queueTranslation)
    }
    if (confirm) {
        AlertDialog(
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
        val headerHeight = maxHeight * if (wide) 0.42f else 0.55f
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
                        if (qualityActions != null) NativeQualityPanel(state, qualityActions)
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
                                    onClick = { remote = true },
                                    modifier = Modifier.weight(1f),
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
                            OutlinedButton(
                                onClick = { wholeProject = !pendingPage && pendingProject; confirm = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                enabled = !state.busy && state.policyDraft?.dirty != true && !state.policyLoadIncomplete &&
                                    !state.selectingItems && !state.runLoadIncomplete && !state.proofreadingQuery.active &&
                                    state.qualityView == null && (pendingPage || pendingProject),
                            ) {
                                Text(stringResource(R.string.native_simulation), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { filtering = true },
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
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    header()
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
    val containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
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
                color = if (item.translation.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
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
                ProofreadingItemCard(
                    item = item,
                    state = state,
                    batchActions = batchActions,
                    selected = true,
                    expandedPreview = true,
                    onOpen = {
                        if (state.selectingItems && batchActions != null) batchActions.toggle(item.id) else onEdit(item.id)
                    },
                )
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
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
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
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                                IconButton(onClick = onDismiss, enabled = !busy) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.native_back))
                                }
                            },
                            actions = {
                                TextButton(onClick = onSave, enabled = !busy && !draft.conflict && draft.dirty) {
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
                        LinearProgressIndicator(Modifier.fillMaxWidth())
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
                                                enabled = !busy && !draft.conflict && navigationEnabled,
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
                    editorError?.let {
                        NativeBanner(text = stringResource(it), tone = NativeTone.ERROR)
                    }
                    editorNotice?.let {
                        NativeBanner(text = stringResource(it), tone = NativeTone.NEUTRAL)
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
                    NativeSectionCard(title = stringResource(R.string.native_source)) {
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
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NativeEditorQuality(draft, busy, qualityEnabled, quality, onInspect)
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
        AlertDialog(
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
    this == NativePage.MORE || this == NativePage.RULES || this == NativePage.SETTINGS ||
        this == NativePage.FILES || this == NativePage.LOGS || this == NativePage.MODELS

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

internal fun TextItemStatus.tone(): NativeTone = when (this) {
    TextItemStatus.PROCESSED -> NativeTone.SUCCESS
    TextItemStatus.ERROR -> NativeTone.ERROR
    TextItemStatus.NONE -> NativeTone.PRIMARY
    TextItemStatus.EXCLUDED,
    TextItemStatus.RULE_SKIPPED,
    TextItemStatus.LANGUAGE_SKIPPED,
    TextItemStatus.DUPLICATED -> NativeTone.NEUTRAL
}
