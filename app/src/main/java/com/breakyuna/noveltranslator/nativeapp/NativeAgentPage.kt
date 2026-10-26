package com.breakyuna.noveltranslator.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
    wideLayout: Boolean = false) {
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
    LaunchedEffect(interrupted) { if (interrupted) runs = true }
    val workspaceUpload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { workspace?.uploadUri?.invoke(it) }
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
    state.agentMessageDetail?.let { NativeAgentMessageDialog(it, closeDetail) }
    val timeline = rememberLazyListState()
    val committedIds = remember(state.agentMessages) { state.agentMessages.map { it.id }.toSet() }
    val showPreview = rememberAgentStreamPreviewVisible(ref, state.agentChatId, committedIds, stream)
    val followTail by remember { derivedStateOf {
        timeline.layoutInfo.totalItemsCount == 0 ||
            (timeline.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= timeline.layoutInfo.totalItemsCount - 4
    } }
    var openedChat by remember(ref.instanceId, ref.generation, state.agentChatId) { mutableStateOf(false) }
    LaunchedEffect(ref, state.agentChatId, state.agentMessages.lastOrNull()?.id) {
        if (state.agentMessages.isNotEmpty()) {
            if (!openedChat || followTail) {
                val warnings = (if (state.agentLoadIncomplete) 1 else 0) + (if (state.runLoadIncomplete) 1 else 0)
                timeline.animateScrollToItem(warnings + state.agentMessages.lastIndex)
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
                            Text(
                                text = stringResource(R.string.native_agent),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            OutlinedButton(
                                onClick = { showModel = true },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
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
                                    Text(stringResource(R.string.native_agent_choose_chat), maxLines = 1)
                                }
                                DropdownMenu(showChats, { showChats = false }) {
                                    state.agentChats.forEachIndexed { index, chat ->
                                        DropdownMenuItem(
                                            text = {
                                                Text((if (chat == state.agentChatId) "✓ " else "") + stringResource(R.string.native_agent_chat_number, state.agentChats.size - index))
                                            },
                                            enabled = state.agentInput.isBlank(),
                                            onClick = {
                                                showChats = false
                                                selectChat(chat)
                                            },
                                        )
                                    }
                                }
                            }
                            TextButton(
                                onClick = newChat,
                                enabled = ready && !state.hasAgentApproval && state.agentInput.isBlank(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(stringResource(R.string.native_agent_new_chat), maxLines = 1)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("native-agent-page"),
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
                                subtitle = stringResource(R.string.native_agent_welcome_body),
                            ) {
                                OutlinedButton(onClick = query, enabled = ready) {
                                    Text(stringResource(R.string.native_agent_query))
                                }
                            }
                        }
                    }
                    items(state.agentMessages, key = { "message-" + it.id }) { line ->
                        NativeAgentBubble(line, ready, inspect)
                    }
                    if (showPreview && stream != null) {
                        item(key = "stream-preview") {
                            NativeAgentStreamPreview(ref, state.agentChatId, committedIds, stream) {
                                if (followTail && !timeline.isScrollInProgress) {
                                    val warnings = (if (state.agentLoadIncomplete) 1 else 0) + (if (state.runLoadIncomplete) 1 else 0)
                                    timeline.animateScrollToItem(warnings + if (state.agentMessages.isEmpty()) 1 else state.agentMessages.size)
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
                    if (state.busy) {
                        item(key = "working") {
                            Text(
                                text = stringResource(R.string.native_working),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (workspace != null) {
                        item(key = "workspace-files") {
                            OutlinedButton(
                                onClick = { showWorkspace = true },
                                enabled = ready,
                                modifier = Modifier.fillMaxWidth().testTag("native-workspace-open"),
                            ) {
                                Text(stringResource(R.string.native_workspace_open))
                            }
                        }
                    }
                    item(key = "open-editor") {
                        TextButton(
                            onClick = { navigate(NativePage.PROOFREADING) },
                            enabled = !state.busy || state.foregroundExecutionActive,
                        ) {
                            Text(stringResource(R.string.native_open_proofreading))
                        }
                    }
                    if (state.recentRuns.isNotEmpty()) {
                        item(key = "runs") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = { runs = !runs }) {
                                    Text(stringResource(R.string.native_runs_title))
                                }
                                if (runs) NativeRunHistory(state)
                            }
                        }
                    }
                    item(key = "local-tools") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (loadHistory != null) {
                                    TextButton(onClick = { showHistory = true }, enabled = !state.busy) {
                                        Text(stringResource(R.string.native_agent_full_history))
                                    }
                                }
                                if (prepareContext != null) {
                                    TextButton(onClick = { showContext = true }, enabled = ready && !state.hasAgentApproval) {
                                        Text(stringResource(R.string.native_context_title))
                                    }
                                }
                                TextButton(onClick = { tools = !tools }, enabled = !state.busy) {
                                    Text(stringResource(R.string.native_agent_local_tools))
                                }
                            }
                            if (tools) NativeLocalAgentTools(state, query, prepare)
                        }
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
                        } else {
                            Text(
                                text = stringResource(R.string.native_model_remote_notice),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (state.hasAgentApproval) {
                            Text(
                                text = stringResource(R.string.native_agent_waiting_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (loadSteerHistory != null) {
                                TextButton(
                                    onClick = { showSteerHistory = true },
                                    enabled = !state.busy,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(stringResource(R.string.native_steer_history))
                                }
                            }
                            TextButton(
                                onClick = { showQueue = true },
                                enabled = !state.busy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("native-agent-queue-open"),
                            ) {
                                Text(stringResource(R.string.native_agent_queue_button, state.agentQueue?.drafts?.size ?: 0))
                            }
                            TextButton(
                                onClick = queueInput,
                                enabled = ready && !state.hasAgentApproval && state.agentInput.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.testTag("native-agent-queue-add"),
                            ) {
                                Text(stringResource(R.string.native_agent_queue_add))
                            }
                            if (state.foregroundAgentActive) {
                                TextButton(
                                    onClick = steer,
                                    enabled = state.agentInput.isNotBlank() && !state.hasAgentApproval,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text(stringResource(R.string.native_agent_steer))
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = state.agentInput,
                                onValueChange = input,
                                modifier = Modifier.weight(1f),
                                minLines = 2,
                                maxLines = 5,
                                enabled = (ready || state.foregroundAgentActive) && !state.hasAgentApproval,
                                label = { Text(stringResource(R.string.native_agent_message)) },
                            )
                            if (state.busy) {
                                Button(onClick = cancel) {
                                    Text(stringResource(R.string.native_agent_stop))
                                }
                            } else {
                                Button(
                                    onClick = send,
                                    enabled = ready && !state.hasAgentApproval && state.modelSettings != null && state.agentInput.isNotBlank(),
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
                    reviewItemProposal = reviewItemProposal,
                    reviewPolicyProposal = reviewPolicyProposal,
                    reviewPdfPageProposal = reviewPdfPageProposal,
                    reviewAssetImportProposal = reviewAssetImportProposal,
                    reviewWorkspaceChange = reviewWorkspaceChange,
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
    reviewItemProposal: ((ItemProposalSummary) -> Unit)?,
    reviewPolicyProposal: ((PolicyApprovalSummary) -> Unit)?,
    reviewPdfPageProposal: ((PdfPageProposalSummary) -> Unit)?,
    reviewAssetImportProposal: ((AssetImportProposalSummary) -> Unit)?,
    reviewWorkspaceChange: ((WorkspaceChangeProposalSummary) -> Unit)?,
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
                Text(stringResource(R.string.native_agent), style = MaterialTheme.typography.titleMedium)
            }
            if (state.busy) {
                item(key = "running") {
                    Text(
                        text = stringResource(R.string.native_working),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("native-agent-side-running"),
                    )
                }
            }
            state.approval?.let { approval ->
                item(key = "approval") {
                    ElevatedCard(Modifier.fillMaxWidth().testTag("native-agent-side-approval")) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.native_agent_approval_title, approval.row + 1),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(policyApprovalStateLabel(approval.state)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = stringResource(R.string.native_agent_waiting_hint),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (reviewItemProposal != null) {
                items(state.itemProposals, key = { "side-item-" + it.id.value }) { proposal ->
                    OutlinedButton(onClick = { reviewItemProposal(proposal) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
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
                items(state.policyProposals, key = { "side-policy-" + it.id.value }) { proposal ->
                    OutlinedButton(onClick = { reviewPolicyProposal(proposal) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_policy_proposal_summary, stringResource(policyApprovalStateLabel(proposal.state))))
                    }
                }
            }
            if (reviewPdfPageProposal != null) {
                items(state.pdfPageProposals, key = { "side-pdf-" + it.id.value }) { proposal ->
                    OutlinedButton(onClick = { reviewPdfPageProposal(proposal) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
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
                items(state.assetImportProposals, key = { "side-asset-" + it.id.value }) { proposal ->
                    OutlinedButton(onClick = { reviewAssetImportProposal(proposal) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
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
                items(state.workspaceChangeProposals, key = { "side-workspace-change-" + it.id.value }) { proposal ->
                    OutlinedButton(onClick = { reviewWorkspaceChange(proposal) }, enabled = ready, modifier = Modifier.fillMaxWidth()) {
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
            if (workspaceAvailable) {
                item(key = "workspace") {
                    NativeSectionCard(
                        title = stringResource(R.string.native_workspace_title),
                        subtitle = stringResource(R.string.native_workspace_notice),
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
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("native-agent-side-queue"),
                ) {
                    Text(stringResource(R.string.native_agent_queue_button, state.agentQueue?.drafts?.size ?: 0))
                }
            }
            if (state.recentRuns.isNotEmpty()) {
                item(key = "runs") { NativeRunHistory(state) }
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
internal fun NativeAgentBubble(line: AgentChatLine, enabled: Boolean, inspect: (String) -> Unit) {
    val user = line.role == "USER"
    val containerColor = when (line.role) {
        "USER" -> MaterialTheme.colorScheme.secondaryContainer
        "ASSISTANT" -> MaterialTheme.colorScheme.surface
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = when (line.role) {
        "USER" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val badgeTone = when (line.role) {
        "USER" -> NativeTone.PRIMARY
        "ASSISTANT" -> NativeTone.SECONDARY
        else -> NativeTone.NEUTRAL
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(if (user) 0.92f else 1f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NativeStatusBadge(text = roleLabel(line.role), tone = badgeTone)
                    TextButton(
                        onClick = { inspect(line.id) },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(stringResource(R.string.native_agent_message_details), style = MaterialTheme.typography.labelSmall)
                    }
                }
                SelectionContainer {
                    Text(
                        text = line.text.ifBlank { stringResource(R.string.native_agent_tool_activity) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
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
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(roleLabel(detail.role)) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (detail.text.isNotEmpty()) Text(detail.text)
                    if (detail.reasoning.isNotEmpty()) {
                        Text(stringResource(R.string.native_agent_reasoning), style = MaterialTheme.typography.titleSmall)
                        Text(detail.reasoning)
                    }
                    if (detail.tools.isNotEmpty()) {
                        Text(stringResource(R.string.native_agent_tool_details), style = MaterialTheme.typography.titleSmall)
                        Text(detail.tools)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss) { Text(stringResource(R.string.native_close)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboard.setText(
                        AnnotatedString(
                            listOf(detail.text, detail.reasoning, detail.tools)
                                .filter { it.isNotEmpty() }
                                .joinToString("\n\n"),
                        ),
                    )
                    copied = true
                },
            ) {
                Text(stringResource(if (copied) R.string.native_copied else R.string.native_copy_message))
            }
        },
    )
}
