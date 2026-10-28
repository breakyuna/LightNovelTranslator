package com.breakyuna.noveltranslator.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.AgentStreamPreviewState
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactRecord
import kotlinx.coroutines.flow.StateFlow

/** Conversation first. Local exercises and execution diagnostics are explicitly expandable. */
@Composable
internal fun NativeAgentPage(state: NativeProjectUiState, query: () -> Unit, prepare: (String, String) -> Unit,
    allow: () -> Unit, reject: () -> Unit, navigate: (NativePage) -> Unit,
    input: (String) -> Unit = {}, send: () -> Unit = {},
    configure: (String, String, String?, () -> Unit) -> Unit = { _, _, _, _ -> },
    newChat: () -> Unit = {}, selectChat: (ChatId) -> Unit = {},
    fetchModels: (String, String, ModelConfigurationRef?, (List<String>) -> Unit) -> Unit = { _, _, _, _ -> },
    cancel: () -> Unit = {}, inspect: (String) -> Unit = {}, closeDetail: () -> Unit = {},
    stream: StateFlow<AgentStreamPreviewState>? = null,
    configureProfile: ((String, String, NativeModelProtocol, List<String>?, () -> Unit) -> Unit)? = null,
    fetchProfile: ((String, String, ModelConfigurationRef?, NativeModelProtocol, (List<String>) -> Unit) -> Unit)? = null,
    queueInput: () -> Unit = {}, useQueued: (CommandId) -> Unit = {},
    deleteQueued: (CommandId) -> Unit = {}, toggleQueue: () -> Unit = {}, sendQueued: () -> Unit = {}, steer: () -> Unit = {},
    loadSteerHistory: (suspend (LocalProjectRef, ChatId, AgentSteerRunCursor?) -> AgentSteerRunPage)? = null,
    loadSteerDetail: (suspend (LocalProjectRef, ChatId, RunIdentity) -> StoredAgentSteerLedger?)? = null,
    restoreSteer: (LocalProjectRef, RunIdentity, CommandId) -> Unit = { _, _, _ -> },
    prepareContext: (suspend (LocalProjectRef, ChatId) -> AgentContextPreview)? = null,
    saveContext: (suspend (AgentContextPreview, String) -> Unit)? = null,
    loadHistory: (suspend (LocalProjectRef, ChatId, AgentMessageCursor?) -> AgentMessagePage)? = null,
    historyDetail: (suspend (LocalProjectRef, ChatId, String) -> AgentMessageDetail)? = null,
    prepareBranch: (suspend (LocalProjectRef, ChatId, MessageId) -> AgentBranchPreview)? = null,
    createBranch: (suspend (AgentBranchPreview, CommandId, String?) -> Unit)? = null,
    reviewItemProposal: ((ItemProposalSummary) -> Unit)? = null,
    reviewPolicyProposal: ((PolicyApprovalSummary) -> Unit)? = null,
    reviewPdfPageProposal: ((PdfPageProposalSummary) -> Unit)? = null,
    reviewAssetImportProposal: ((AssetImportProposalSummary) -> Unit)? = null,
    reviewWorkspaceChange: ((WorkspaceChangeProposalSummary) -> Unit)? = null,
    workspace: NativeWorkspaceActions? = null,
    saveWorkspaceArtifact: (WorkspaceArtifactRecord) -> Unit = {},
    wideLayout: Boolean = false, reviewApplicationProposal: (() -> Unit)? = null) {
    val ref = state.project!!.ref
    val ready = !state.busy && !state.agentLoadIncomplete && !state.runLoadIncomplete
    var showModel by remember(ref.instanceId, ref.generation) { mutableStateOf(false) }
    var showChats by remember(ref.instanceId, ref.generation) { mutableStateOf(false) }
    var showHistory by remember(ref, state.agentChatId) { mutableStateOf(false) }
    var showContext by remember(ref, state.agentChatId) { mutableStateOf(false) }
    var showSteerHistory by remember(ref, state.agentChatId) { mutableStateOf(false) }
    var showQueue by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf(false) }
    var tools by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf(false) }
    var runs by rememberSaveable(ref.instanceId.value, ref.generation.value) { mutableStateOf(false) }
    var showWorkspace by rememberSaveable(ref.instanceId.value, ref.generation.value,
        state.agentChatId?.value, state.agentRunId?.value) { mutableStateOf(false) }
    val interrupted = state.recentRuns.any { it.state == StoredRunState.INTERRUPTED }
    var showActions by remember(ref, state.agentChatId) { mutableStateOf(false) }
    val workspaceUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { workspace?.uploadUri?.invoke(it) }
    }
    val attachmentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) state.agentAttachmentActions?.add(uris)
    }
    if (showWorkspace && workspace != null) NativeWorkspaceDialog(
        ref = ref, revision = state.project!!.revision, chatId = state.agentChatId,
        runId = state.agentRunId, actions = workspace,
        dismiss = { showWorkspace = false }, saveArtifact = saveWorkspaceArtifact,
        upload = { workspaceUpload.launch(arrayOf("*/*")) },
        refreshKey = state.workspaceUploadRevision,
    )
    if (showModel) NativeModelDialog(state.modelSettings, state.busy, state.modelSettingsError, state.modelCatalogLoading,
        { showModel = false }, configure, fetchModels, cancel, configureProfile, fetchProfile)
    if (showQueue) state.agentQueue?.let {
        NativeAgentQueueDialog(it, state.busy, { showQueue = false }, useQueued, deleteQueued, toggleQueue) {
            showQueue = false
            sendQueued()
        }
    }
    if (showSteerHistory && loadSteerHistory != null && loadSteerDetail != null) state.agentChatId?.let { chat ->
        NativeSteerHistoryDialog(ref, chat, loadSteerHistory, loadSteerDetail, restoreSteer, { showSteerHistory = false })
    }
    if (showContext && prepareContext != null && saveContext != null) state.agentChatId?.let { chat ->
        NativeAgentContextDialog(ref, chat, prepareContext, saveContext, { showContext = false })
    }
    if (showHistory && loadHistory != null && historyDetail != null) state.agentChatId?.let { chat ->
        NativeAgentHistoryDialog(ref, chat, loadHistory, historyDetail, { showHistory = false }, prepareBranch, createBranch)
    }
    if (runs || tools) NativeContentDialog(
        onDismissRequest = { runs = false; tools = false },
        title = { Text(stringResource(if (runs) R.string.native_runs_title else R.string.native_agent_local_tools)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (runs) NativeRunHistory(state) else NativeLocalAgentTools(state, query, prepare)
            }
        },
        confirmButton = {
            TextButton(onClick = { runs = false; tools = false }) { Text(stringResource(R.string.native_close)) }
        },
    )
    state.agentMessageDetail?.let { NativeAgentMessageDialog(it, closeDetail) }
    val timeline = key(ref.instanceId, ref.generation, state.agentChatId) { rememberLazyListState() }
    val fullBodies = remember(ref, state.agentChatId) { mutableStateMapOf<String, AgentMessageDetail>() }
    val lastLine = state.agentMessages.lastOrNull()
    val needsFullBody = lastLine?.role == "ASSISTANT" && lastLine.text.length >= 2000 && historyDetail != null
    val lastBodyReady = !needsFullBody || lastLine?.id?.let { fullBodies.containsKey(it) } == true
    LaunchedEffect(ref, state.agentChatId, lastLine?.id) {
        val chat = state.agentChatId
        if (needsFullBody && chat != null && lastLine != null && historyDetail != null) {
            try {
                fullBodies[lastLine.id] = historyDetail(ref, chat, lastLine.id)
                if (fullBodies.size > 8) fullBodies.keys.firstOrNull { it != lastLine.id }?.let { fullBodies.remove(it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* The explicit full-message action remains available. */ }
        }
    }
    val committedIds = remember(state.agentMessages, lastBodyReady) {
        state.agentMessages.filter { lastBodyReady || it.id != lastLine?.id }.map { it.id }.toSet()
    }
    val detailLoader: (suspend (String) -> AgentMessageDetail)? = if (historyDetail != null && state.agentChatId != null) {
        { id -> historyDetail(ref, requireNotNull(state.agentChatId), id) }
    } else null
    val showPreview = rememberAgentStreamPreviewVisible(ref, state.agentChatId, committedIds, stream)
    var followTail by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val scrolling = remember(timeline, ref, state.agentChatId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) followTail = false
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !timeline.canScrollForward) followTail = true
                return Offset.Zero
            }
        }
    }
    suspend fun revealTail(index: Int) {
        if (timeline.layoutInfo.visibleItemsInfo.none { it.index == index }) timeline.scrollToItem(index)
        val item = timeline.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
        val distance = item.offset.toLong() + item.size - timeline.layoutInfo.viewportEndOffset + timeline.layoutInfo.afterContentPadding
        if (distance > 0) timeline.scrollBy(distance.toFloat())
    }
    var openedChat by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf(false) }
    LaunchedEffect(ref, state.agentChatId, state.agentMessages.lastOrNull()?.id) {
        if (state.agentMessages.isNotEmpty()) {
            if (!openedChat || followTail) {
                val warnings = (if (state.agentLoadIncomplete) 1 else 0) + (if (state.runLoadIncomplete) 1 else 0)
                revealTail(warnings + state.agentMessages.lastIndex)
            }
            openedChat = true
        } else {
            timeline.scrollToItem(0)
            openedChat = false
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = wideLayout || maxWidth >= NativeTwoPaneBreakpoint
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (interrupted) TextButton(
                            onClick = { runs = true },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                            modifier = Modifier.testTag("native-agent-interrupted"),
                        ) {
                            Text(
                                stringResource(R.string.native_runs_interrupted) + " · " + stringResource(R.string.native_runs_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        state.agentContextStatus?.let { status ->
                            Text(
                                text = stringResource(
                                    when (status) {
                                        com.breakyuna.noveltranslator.nativecore.engine.AgentContextStatus.CHECKING -> R.string.native_auto_context_checking
                                        com.breakyuna.noveltranslator.nativecore.engine.AgentContextStatus.SUMMARIZING -> R.string.native_auto_context_summarizing
                                        com.breakyuna.noveltranslator.nativecore.engine.AgentContextStatus.COMPACTED -> R.string.native_auto_context_saved
                                        com.breakyuna.noveltranslator.nativecore.engine.AgentContextStatus.FAILED -> R.string.native_auto_context_failed
                                    },
                                ),
                                modifier = Modifier.testTag("native-auto-context-status"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showModel = true },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = state.modelSettings?.model ?: stringResource(R.string.native_model_configure),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Box {
                                TextButton(
                                    onClick = { showChats = true },
                                    enabled = !state.busy,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    val currentChatIndex = state.agentChats.indexOf(state.agentChatId)
                                    Text(
                                        if (currentChatIndex >= 0) stringResource(R.string.native_agent_chat_number, state.agentChats.size - currentChatIndex)
                                        else stringResource(R.string.native_agent_choose_chat),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                DropdownMenu(showChats, { showChats = false }) {
                                    state.agentChats.forEachIndexed { index, chat ->
                                        DropdownMenuItem(
                                            text = {
                                                Text((if (chat == state.agentChatId) "✓ " else "") + stringResource(R.string.native_agent_chat_number, state.agentChats.size - index))
                                            },
                                            enabled = ready && !state.hasAgentApproval && state.agentAttachments.isEmpty(),
                                            onClick = {
                                                showChats = false
                                                selectChat(chat)
                                            },
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = newChat,
                                enabled = ready && !state.hasAgentApproval && state.agentAttachments.isEmpty()) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.native_agent_new_chat))
                            }
                            Box {
                                IconButton(onClick = { showActions = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.native_agent_actions))
                                }
                                DropdownMenu(showActions, { showActions = false }) {
                                    if (state.agentInput.isNotBlank()) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_agent_queue_add)) },
                                        enabled = ready && !state.hasAgentApproval && state.agentAttachments.isEmpty(),
                                        onClick = { showActions = false; queueInput() },
                                    )
                                    if (workspace != null) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_workspace_open)) },
                                        enabled = ready,
                                        onClick = { showActions = false; showWorkspace = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_agent_queue_button, state.agentQueue?.drafts?.size ?: 0)) },
                                        enabled = state.agentQueue != null,
                                        onClick = { showActions = false; showQueue = true },
                                    )
                                    if (loadHistory != null && historyDetail != null && state.agentChatId != null) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_agent_full_history)) },
                                        enabled = true,
                                        onClick = { showActions = false; showHistory = true },
                                    )
                                    if (prepareContext != null && saveContext != null && state.agentChatId != null) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_context_title)) },
                                        enabled = ready && !state.hasAgentApproval,
                                        onClick = { showActions = false; showContext = true },
                                    )
                                    if (loadSteerHistory != null && loadSteerDetail != null && state.agentChatId != null) DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_steer_history)) },
                                        enabled = !state.busy,
                                        onClick = { showActions = false; showSteerHistory = true },
                                    )
                                    if (state.recentRuns.isNotEmpty()) DropdownMenuItem(
                                        text = { Text(stringResource(if (interrupted) R.string.native_runs_interrupted else R.string.native_runs_title)) },
                                        enabled = true,
                                        onClick = { showActions = false; runs = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_open_proofreading)) },
                                        enabled = !state.busy || state.foregroundExecutionActive,
                                        onClick = { showActions = false; navigate(NativePage.PROOFREADING) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.native_agent_local_tools)) },
                                        enabled = !state.busy,
                                        onClick = { showActions = false; tools = true },
                                    )
                                }
                            }
                        }
                    }
                }
                reviewApplicationProposal?.let { review ->
                    TextButton(onClick = review, modifier = Modifier.padding(horizontal = 12.dp)
                        .testTag("native-agent-application-approval")) {
                        Text(stringResource(R.string.native_agent_business_review))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrolling).testTag("native-agent-page"),
                    state = timeline,
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.agentLoadIncomplete) {
                        item(key = "load-error") {
                            NativeBanner(
                                text = stringResource(R.string.native_agent_load_error),
                                tone = NativeTone.ERROR,
                            )
                        }
                    }
                    if (state.runLoadIncomplete) {
                        item(key = "run-error") {
                            NativeBanner(
                                text = stringResource(R.string.native_runs_load_error),
                                tone = NativeTone.ERROR,
                            )
                        }
                    }
                    if (state.agentMessages.isEmpty()) {
                        item(key = "empty") {
                            NativeSectionCard(
                                title = stringResource(R.string.native_agent_welcome),
                                subtitle = null,
                            ) {
                                OutlinedButton(onClick = query, enabled = ready) {
                                    Text(stringResource(R.string.native_agent_query))
                                }
                            }
                        }
                    }
                    items(state.agentMessages, key = { "message-" + it.id }) { line ->
                        NativeAgentBubble(line, true, inspect, load = detailLoader, body = fullBodies[line.id], onResized = {
                            if (followTail && !showPreview && line.id == lastLine?.id) scope.launch {
                                withFrameNanos { }
                                if (followTail && !timeline.isScrollInProgress) {
                                    val warnings = (if (state.agentLoadIncomplete) 1 else 0) + (if (state.runLoadIncomplete) 1 else 0)
                                    revealTail(warnings + state.agentMessages.lastIndex)
                                }
                            }
                        })
                    }
                    if (showPreview && stream != null) {
                        item(key = "stream-preview") {
                            Box(Modifier.testTag("native-agent-stream-slot")) {
                                NativeAgentStreamPreview(ref, state.agentChatId, committedIds, stream) {
                                    if (followTail && !timeline.isScrollInProgress) {
                                        val warnings = (if (state.agentLoadIncomplete) 1 else 0) + (if (state.runLoadIncomplete) 1 else 0)
                                        val index = warnings + if (state.agentMessages.isEmpty()) 1 else state.agentMessages.size
                                        revealTail(index)
                                    }
                                }
                            }
                        }
                    }
                    state.approval?.let { approval ->
                        item(key = "approval-" + approval.id.value) {
                            NativeApprovalCard(approval, ready, allow, reject)
                        }
                    }
                    if (reviewItemProposal != null) {
                        items(state.itemProposals, key = { "proposal-" + it.id.value }) { proposal ->
                            OutlinedButton(
                                onClick = { reviewItemProposal(proposal) },
                                enabled = ready,
                                modifier = Modifier.fillMaxWidth().testTag("native-item-proposal-${proposal.id.value}"),
                            ) {
                                Text(
                                    stringResource(
                                        R.string.native_item_proposal_summary,
                                        proposal.count,
                                        stringResource(itemProposalStateLabel(proposal.state)),
                                    ),
                                )
                            }
                        }
                    }
                    if (reviewPolicyProposal != null) {
                        items(state.policyProposals, key = { "policy-proposal-" + it.id.value }) { proposal ->
                            OutlinedButton(
                                onClick = { reviewPolicyProposal(proposal) },
                                enabled = ready,
                                modifier = Modifier.fillMaxWidth().testTag("native-policy-proposal-${proposal.id.value}"),
                            ) {
                                Text(stringResource(R.string.native_policy_proposal_summary, stringResource(policyApprovalStateLabel(proposal.state))))
                            }
                        }
                    }
                    if (reviewPdfPageProposal != null) {
                        items(state.pdfPageProposals, key = { "pdf-proposal-" + it.id.value }) { proposal ->
                            OutlinedButton(
                                onClick = { reviewPdfPageProposal(proposal) },
                                enabled = ready,
                                modifier = Modifier.fillMaxWidth().testTag("native-pdf-proposal-${proposal.id.value}"),
                            ) {
                                Text(
                                    stringResource(
                                        R.string.native_pdf_proposal_summary,
                                        proposal.count,
                                        stringResource(pdfPageProposalStateLabel(proposal.state)),
                                    ),
                                )
                            }
                        }
                    }
                    if (reviewAssetImportProposal != null) {
                        items(state.assetImportProposals, key = { "asset-import-proposal-" + it.id.value }) { proposal ->
                            OutlinedButton(
                                onClick = { reviewAssetImportProposal(proposal) },
                                enabled = ready,
                                modifier = Modifier.fillMaxWidth().testTag("native-asset-import-proposal-${proposal.id.value}"),
                            ) {
                                Text(
                                    stringResource(
                                        R.string.native_asset_import_summary,
                                        proposal.target.value,
                                        stringResource(assetImportProposalStateLabel(proposal.state)),
                                    ),
                                )
                            }
                        }
                    }
                    if (reviewWorkspaceChange != null) {
                        items(state.workspaceChangeProposals, key = { "workspace-change-" + it.id.value }) { proposal ->
                            OutlinedButton(
                                onClick = { reviewWorkspaceChange(proposal) },
                                enabled = ready,
                                modifier = Modifier.fillMaxWidth().testTag("native-workspace-change-${proposal.id.value}"),
                            ) {
                                Text(
                                    stringResource(
                                        R.string.native_workspace_change_counts,
                                        proposal.summary.created,
                                        proposal.summary.updated,
                                        proposal.summary.deleted,
                                        proposal.summary.totalBytes,
                                    ),
                                )
                            }
                        }
                    }
                    items(state.agentResults, key = { "result-" + it.id }) { result ->
                        NativeStatusBadge(
                            text = stringResource(R.string.native_edit_row, result.row + 1) + " · " + stringResource(
                                when (result.state) {
                                    TextApprovalState.COMMITTED -> R.string.native_agent_result_saved
                                    TextApprovalState.REJECTED -> R.string.native_agent_result_rejected
                                    TextApprovalState.CONFLICT -> R.string.native_agent_result_conflict
                                    TextApprovalState.CANCELLED -> R.string.native_agent_result_cancelled
                                    else -> R.string.native_agent_unresolved
                                },
                            ),
                            tone = when (result.state) {
                                TextApprovalState.COMMITTED -> NativeTone.SUCCESS
                                TextApprovalState.CONFLICT, TextApprovalState.REJECTED -> NativeTone.ERROR
                                else -> NativeTone.NEUTRAL
                            },
                        )
                    }
                    if (state.busy && !showPreview) {
                        item(key = "working") {
                            Text(
                                text = stringResource(R.string.native_working),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                }
                if (!followTail && timeline.canScrollForward) {
                    SmallFloatingActionButton(
                        onClick = { followTail = true; scope.launch { revealTail((timeline.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).testTag("native-agent-latest"),
                    ) { Icon(Icons.Default.ArrowDownward, stringResource(R.string.native_agent_latest)) }
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (state.modelSettings == null) {
                            TextButton(
                                onClick = { showModel = true },
                                enabled = !state.busy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(stringResource(R.string.native_model_missing))
                            }
                        }
                        if (state.hasAgentApproval) {
                            Text(
                                text = stringResource(R.string.native_agent_waiting_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        NativeAgentAttachmentList(
                            attachments = state.agentAttachments,
                            failures = state.agentAttachmentFailures,
                            enabled = ready && !state.hasAgentApproval && !state.agentAttachmentBusy,
                            actions = state.agentAttachmentActions,
                        )
                        OutlinedTextField(
                            value = state.agentInput,
                            onValueChange = input,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 5,
                            shape = RoundedCornerShape(16.dp),
                            enabled = (ready || state.foregroundAgentActive) && !state.hasAgentApproval,
                            placeholder = { Text(stringResource(R.string.native_agent_message)) },
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (state.agentAttachmentActions != null) {
                                NativeAgentAttachmentButton(
                                    enabled = ready && !state.hasAgentApproval && !state.agentAttachmentBusy &&
                                        state.agentAttachments.size < AgentAttachmentLimits.MAX_PER_MESSAGE,
                                    onClick = { attachmentPicker.launch(arrayOf("image/*")) },
                                )
                            }
                            if (state.foregroundAgentActive && state.agentInput.isNotBlank()) {
                                TextButton(onClick = steer, enabled = state.agentAttachments.isEmpty() && !state.hasAgentApproval) {
                                    Text(stringResource(R.string.native_agent_steer))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (state.busy) {
                                Button(onClick = cancel) {
                                    Text(stringResource(R.string.native_agent_stop))
                                }
                            } else {
                                Button(
                                    onClick = send,
                                    enabled = state.modelSettings != null && !state.agentAttachmentBusy &&
                                        canSendAgentMessage(ready, state.hasAgentApproval,
                                            state.agentInput, state.agentAttachments.size),
                                ) {
                                    Text(stringResource(R.string.native_agent_send))
                                }
                            }
                        }
                    }
                }
            }
            if (wide) {
                VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant)
                NativeAgentSidePanel(
                    state = state,
                    workspaceAvailable = workspace != null,
                    openWorkspace = { showWorkspace = true },
                    openQueue = { showQueue = true },
                    openRuns = { runs = true },
                    modifier = Modifier.width(NativeAgentSidePanelWidth).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun NativeAgentSidePanel(
    state: NativeProjectUiState,
    workspaceAvailable: Boolean,
    openWorkspace: () -> Unit,
    openQueue: () -> Unit,
    openRuns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = !state.busy && !state.agentLoadIncomplete && !state.runLoadIncomplete
    Surface(
        modifier = modifier.testTag("native-agent-side-panel"),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "title") {
                Text(stringResource(R.string.native_agent_resources), style = MaterialTheme.typography.titleMedium)
            }
            if (workspaceAvailable) {
                item(key = "workspace") {
                    NativeSectionCard(
                        title = stringResource(R.string.native_workspace_title),
                        subtitle = null,
                    ) {
                        OutlinedButton(
                            onClick = openWorkspace,
                            enabled = ready,
                            modifier = Modifier.fillMaxWidth().testTag("native-agent-side-workspace"),
                        ) {
                            Text(stringResource(R.string.native_workspace_open))
                        }
                    }
                }
            }
            item(key = "queue") {
                OutlinedButton(
                    onClick = openQueue,
                    enabled = true,
                    modifier = Modifier.fillMaxWidth().testTag("native-agent-side-queue"),
                ) {
                    Text(stringResource(R.string.native_agent_queue_button, state.agentQueue?.drafts?.size ?: 0))
                }
            }
            if (state.recentRuns.isNotEmpty()) {
                item(key = "runs") {
                    TextButton(onClick = openRuns, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_runs_title))
                    }
                }
            }
        }
    }
}

@Composable
private fun roleLabel(role: String): String = stringResource(
    when (role) {
        "USER" -> R.string.native_role_you
        "ASSISTANT" -> R.string.native_role_assistant
        "TOOL" -> R.string.native_role_tool
        else -> R.string.native_role_system
    },
)

@Composable
internal fun NativeAgentBubble(line: AgentChatLine, enabled: Boolean, inspect: (String) -> Unit,
    load: (suspend (String) -> AgentMessageDetail)? = null, body: AgentMessageDetail? = null,
    onResized: () -> Unit = {}) {
    val user = line.role == "USER"
    val operation = line.role == "TOOL" || line.role == "ASSISTANT" && line.text.isBlank()
    var expanded by rememberSaveable(line.id) { mutableStateOf(false) }
    var detail by remember(line.id) { mutableStateOf<AgentMessageDetail?>(null) }
    var loading by remember(line.id) { mutableStateOf(false) }
    var failed by remember(line.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var copied by remember(line.id) { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1800); copied = false } }
    fun expand() {
        expanded = !expanded
        if (expanded && detail == null && load != null && !loading) scope.launch {
            loading = true; failed = false
            try { detail = load(line.id) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier.widthIn(max = if (user) 560.dp else 720.dp).fillMaxWidth().onSizeChanged { onResized() },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = if (operation) 2.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (operation) {
                    TextButton(onClick = { if (load == null) inspect(line.id) else expand() }, enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(line.text.ifBlank { stringResource(R.string.native_agent_tool_activity) },
                            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                    if (expanded) {
                        if (loading) Text(stringResource(R.string.native_working), style = MaterialTheme.typography.labelSmall)
                        if (failed) TextButton(onClick = { expanded = false; expand() }) { Text(stringResource(R.string.native_refresh)) }
                        detail?.let { NativeCodeBlock(listOf(it.text, it.tools).filter(String::isNotEmpty).joinToString("\n\n")) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(roleLabel(line.role), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)

                    }
                    SelectionContainer {
                        if (user) Text(body?.text ?: detail?.text ?: line.text, style = MaterialTheme.typography.bodyMedium)
                        else NativeMarkdown(body?.text ?: detail?.text ?: line.text)
                    }
                    (body ?: detail)?.let { full ->
                        if (full.reasoning.isNotEmpty()) NativeAgentFoldout(stringResource(R.string.native_agent_reasoning), stateKey = line.id) {
                            NativeMarkdown(full.reasoning)
                        }
                        if (full.tools.isNotEmpty()) NativeAgentFoldout(stringResource(R.string.native_agent_tool_details), stateKey = line.id) {
                            NativeCodeBlock(full.tools)
                        }
                    }
                    if (load != null && detail == null && body == null) {
                        TextButton(onClick = { if (!expanded || failed) { expanded = false; expand() } }, enabled = !loading) {
                            Text(stringResource(if (loading) R.string.native_working else R.string.native_agent_expand_message))
                        }
                        if (failed) Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (failed) Text(stringResource(R.string.native_agent_load_error), color = MaterialTheme.colorScheme.error)
                IconButton(enabled = enabled && !loading, onClick = {
                    scope.launch {
                        loading = true; failed = false
                        try {
                            val full = body ?: detail ?: load?.invoke(line.id)
                            if (full != null) detail = full
                            clipboard.setText(AnnotatedString(if (operation && full != null)
                                listOf(full.text, full.tools).filter(String::isNotEmpty).joinToString("\n\n")
                                else full?.text ?: line.text))
                            copied = true
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { loading = false }
                    }
                }) {
                    Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        stringResource(if (copied) R.string.native_copied else R.string.native_copy_message), Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun NativeLocalAgentTools(state: NativeProjectUiState, query: () -> Unit, prepare: (String, String) -> Unit) {
    val ref = state.project!!.ref
    val ready = !state.busy && !state.agentLoadIncomplete && !state.runLoadIncomplete
    var selected by rememberSaveable(
        ref.instanceId.value,
        ref.generation.value,
        state.agentChatId?.value,
        state.items.firstOrNull()?.id?.value,
    ) { mutableStateOf(state.items.firstOrNull()?.id?.value) }
    var draft by rememberSaveable(ref.instanceId.value, ref.generation.value, state.agentChatId?.value) { mutableStateOf("") }
    var expanded by remember(ref, state.agentChatId) { mutableStateOf(false) }
    val entry = state.items.firstOrNull { it.id.value == selected }
    NativeSectionCard(
        title = stringResource(R.string.native_agent_prepare),
        subtitle = stringResource(R.string.native_agent_local_notice),
    ) {
        if (state.agentMessages.isNotEmpty()) {
            OutlinedButton(onClick = query, enabled = ready) {
                Text(stringResource(R.string.native_agent_query))
            }
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = ready && !state.hasAgentApproval && state.items.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (entry == null) stringResource(R.string.native_empty_items) else stringResource(R.string.native_edit_row, entry.row + 1))
            }
            DropdownMenu(expanded, { expanded = false }) {
                state.items.forEach { row ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.native_edit_row, row.row + 1) + ": " + row.source.take(40), maxLines = 2) },
                        onClick = {
                            selected = row.id.value
                            expanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= 65_536) draft = it },
            enabled = ready && !state.hasAgentApproval,
            label = { Text(stringResource(R.string.native_translation)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
        )
        OutlinedButton(
            onClick = { entry?.let { prepare(it.id.value.toString(), draft) } },
            enabled = ready && !state.hasAgentApproval && entry != null,
        ) {
            Text(stringResource(R.string.native_agent_prepare))
        }
    }
}

@Composable
private fun NativeApprovalCard(approval: TextApproval, enabled: Boolean, allow: () -> Unit, reject: () -> Unit) {
    NativeSectionCard(
        title = stringResource(R.string.native_agent_approval_title, approval.row + 1),
        subtitle = stringResource(R.string.native_agent_approval_effect),
        badge = stringResource(R.string.native_row_status, approval.row + 1, stringResource(approval.beforeStatus.label())),
        badgeTone = NativeTone.PRIMARY,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.native_agent_before), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(approval.before.ifEmpty { stringResource(R.string.native_empty_result) })
                }
            }
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.native_agent_after), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(approval.after.ifEmpty { stringResource(R.string.native_empty_result) })
                }
            }
        }
        if (approval.state == TextApprovalState.CONSUMED) {
            Text(stringResource(R.string.native_agent_unresolved), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = allow,
                enabled = enabled && approval.state == TextApprovalState.PENDING,
            ) {
                Text(stringResource(R.string.native_agent_allow_once))
            }
            OutlinedButton(
                onClick = reject,
                enabled = enabled && approval.state == TextApprovalState.PENDING,
            ) {
                Text(stringResource(R.string.native_agent_reject))
            }
        }
    }
}

@Composable
internal fun NativeAgentMessageDialog(detail: AgentMessageDetail, dismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(detail.id) { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        NativeDialogSurface(
            title = { Text(roleLabel(detail.role)) },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectionContainer {
                        if (detail.role == "ASSISTANT") NativeMarkdown(detail.text)
                        else if (detail.role == "TOOL") NativeCodeBlock(detail.text)
                        else Text(detail.text)
                    }
                    if (detail.reasoning.isNotEmpty()) NativeAgentFoldout(stringResource(R.string.native_agent_reasoning), stateKey = detail.id) {
                        SelectionContainer { NativeMarkdown(detail.reasoning) }
                    }
                    if (detail.tools.isNotEmpty()) NativeAgentFoldout(stringResource(R.string.native_agent_tool_details), stateKey = detail.id) {
                        NativeCodeBlock(detail.tools)
                    }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) } },
            dismissButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(detail.text)); copied = true }) {
                    Text(stringResource(if (copied) R.string.native_copied else R.string.native_copy_message))
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(listOf(detail.text, detail.reasoning, detail.tools).filter(String::isNotEmpty).joinToString("\n\n")))
                }) { Text(stringResource(R.string.native_copy_all)) }
            },
        )
    }
}

@Composable
internal fun NativeAgentFoldout(title: String, modifier: Modifier = Modifier, stateKey: Any? = title,
    forceExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(stateKey, title) { mutableStateOf(false) }
    LaunchedEffect(forceExpanded) { if (forceExpanded) expanded = true }
    Column(modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text(title, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) content()
    }
}
