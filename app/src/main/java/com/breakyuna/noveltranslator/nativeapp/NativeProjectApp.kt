package com.breakyuna.noveltranslator.nativeapp

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
import androidx.compose.ui.Modifier
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
    val snackbar = remember { SnackbarHostState() }
    val inputTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importFile(it, false) }
    val inputProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { model.importFile(it, true) }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), model::completeExport)
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain"), model::completeExport)
    val message = state.error ?: state.notice
    val text = message?.let { stringResource(it) }
    LaunchedEffect(text) { if (text != null) { snackbar.showSnackbar(text); model.dismissMessage() } }
    var discard by rememberSaveable(state.project?.ref?.instanceId?.value, state.editor?.item?.id?.value) { mutableStateOf(false) }
    fun back() {
        when {
            state.busy -> Unit
            state.editor != null -> if (state.editor!!.dirty) discard = true else model.closeEditor()
            state.project != null && state.page != NativePage.AGENT -> model.selectPage(NativePage.AGENT)
            state.project != null -> model.home()
        }
    }
    BackHandler(enabled = state.busy || state.project != null || state.editor != null) { back() }
    NativeTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val rail = maxWidth >= 600.dp && state.project != null
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = { TopAppBar(
                    title = { Column {
                        Text(state.project?.name ?: stringResource(R.string.native_projects), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.project?.let { Text(it.assetPath.value, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } },
                    navigationIcon = { if (state.project != null) IconButton(onClick = model::home, enabled = !state.busy && state.editor == null) {
                        Icon(Icons.Default.Home, stringResource(R.string.native_projects))
                    } },
                    actions = { IconButton(onClick = model::refresh, enabled = !state.busy && state.editor == null) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.native_refresh))
                    } },
                ) },
                bottomBar = { if (state.project != null && !rail) NavigationBar {
                    NativePage.entries.forEach { page -> NavigationBarItem(
                        selected = state.page == page, onClick = { model.selectPage(page) }, enabled = !state.busy && state.editor == null,
                        icon = { Icon(page.icon(), contentDescription = null) }, label = { Text(stringResource(page.label())) },
                    ) }
                } },
            ) { padding ->
                Row(Modifier.padding(padding).fillMaxSize()) {
                    if (rail) NavigationRail { NativePage.entries.forEach { page -> NavigationRailItem(
                        selected = state.page == page, onClick = { model.selectPage(page) }, enabled = !state.busy && state.editor == null,
                        icon = { Icon(page.icon(), null) }, label = { Text(stringResource(page.label())) },
                    ) } }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.busy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.native_working), Modifier.padding(top = 12.dp))
                                TextButton(onClick = model::cancelAction) { Text(stringResource(R.string.native_cancel)) }
                            }
                        }
                        key(state.project?.ref?.instanceId?.value, state.project?.ref?.generation?.value, state.page) {
                            if (state.project == null) {
                                ProjectsHome(state, onCreate = { if (model.prepareImport(false)) inputTxt.launch(arrayOf("text/*", "application/octet-stream")) },
                                    onImport = { if (model.prepareImport(true)) inputProject.launch(arrayOf("*/*")) }, onOpen = model::open,
                                    onCopy = model::copy, onEncoding = model::changeEncoding)
                            } else when (state.page) {
                                NativePage.AGENT -> NativeAgentPage(state, model::queryAgentProject, model::prepareAgentEdit, model::allowAgentEdit, model::rejectAgentEdit, model::selectPage, model::agentInput, model::sendAgentMessage, model::saveAgentModel, model::newAgentChat, model::selectAgentChat)
                                NativePage.WORKBENCH -> Workbench(state, model::cleanupOutputs) { kind ->
                                    model.prepareExport(kind)?.let { name ->
                                        if (kind == NativeExport.PROJECT) saveProject.launch(name) else saveText.launch(name)
                                    }
                                }
                                NativePage.PROOFREADING -> Proofreading(state, model::edit, model::previousPage, model::nextPage, model::simulatePage)
                            }
                        }
                    }
                }
            }
        }
        state.editor?.let { draft ->
            Editor(draft, state.busy, onDismiss = { if (draft.dirty) discard = true else model.closeEditor() },
                onText = model::editText, onStatus = model::editStatus, onSave = model::saveEditor,
                onReload = model::reloadEditor, onCancel = model::cancelAction)
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
internal fun Workbench(state: NativeProjectUiState, cleanup: () -> Unit, export: (NativeExport) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(stringResource(R.string.native_workbench), style = MaterialTheme.typography.headlineSmall) }
        item { Text(state.project!!.assetPath.value) }
        item { Text(stringResource(R.string.native_item_count, state.project!!.itemCount)) }
        item { Text(stringResource(R.string.native_translation_pending)) }
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
                          simulate: () -> Unit) {
    var confirm by rememberSaveable(state.project?.ref?.instanceId?.value, state.project?.ref?.generation?.value,
        state.items.firstOrNull()?.id?.value) { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.native_simulation)) },
        text = { Text(stringResource(R.string.native_simulation_confirm)) },
        confirmButton = { TextButton(onClick = { confirm = false; simulate() }, enabled = !state.busy) {
            Text(stringResource(R.string.native_simulation_start))
        } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.native_cancel)) } },
    )
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { confirm = true }, enabled = !state.busy && state.items.any { it.status == TextItemStatus.NONE && it.hasTranslatableSource }) {
            Text(stringResource(R.string.native_simulation))
        }
        state.translationProgress?.let { Text(stringResource(R.string.native_simulation_progress, it.committedItems, it.requests),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = previous, enabled = !state.busy && state.hasPrevious) { Text(stringResource(R.string.native_previous)) }
            TextButton(onClick = next, enabled = !state.busy && state.hasNext) { Text(stringResource(R.string.native_next)) }
        }
        key(state.items.firstOrNull()?.id?.value) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.items.isEmpty()) item { Text(stringResource(R.string.native_empty_items)) }
                items(state.items, key = { it.id.value }) { item ->
                    Card(Modifier.fillMaxWidth().clickable(enabled = !state.busy) { edit(item.id) }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.native_row_status, item.row + 1, stringResource(item.status.label())), style = MaterialTheme.typography.labelLarge)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Editor(draft: NativeEditDraft, busy: Boolean, onDismiss: () -> Unit, onText: (String) -> Unit,
                   onStatus: (TextItemStatus) -> Unit, onSave: () -> Unit, onReload: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.native_edit_row, draft.item.row + 1)) },
                navigationIcon = { IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Default.ArrowBack, stringResource(R.string.native_back)) } },
                actions = { TextButton(onClick = onSave, enabled = !busy && !draft.conflict && draft.dirty) { Text(stringResource(R.string.native_save)) } },
            ) }) { padding ->
                Column(Modifier.padding(padding).imePadding().padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = onCancel) { Text(stringResource(R.string.native_cancel)) } }
                    if (draft.conflict) {
                        Text(stringResource(R.string.native_conflict), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onReload, enabled = !busy) { Text(stringResource(R.string.native_reload_baseline)) }
                    }
                    Text(stringResource(R.string.native_source), style = MaterialTheme.typography.titleSmall)
                    Text(draft.item.source.ifEmpty { stringResource(R.string.native_blank_line) })
                    OutlinedTextField(value = draft.text, onValueChange = onText, enabled = !busy,
                        label = { Text(stringResource(R.string.native_translation)) }, minLines = 5, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.native_status), style = MaterialTheme.typography.titleSmall)
                    TextItemStatus.entries.forEach { status -> Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) { onStatus(status) }.heightIn(min = 48.dp),
                    ) { RadioButton(selected = draft.status == status, onClick = { onStatus(status) }, enabled = !busy)
                        Text(stringResource(status.label()), Modifier.padding(top = 12.dp)) } }
                }
            }
        }
    }
}

private fun NativePage.label(): Int = when (this) {
    NativePage.AGENT -> R.string.native_agent
    NativePage.WORKBENCH -> R.string.native_workbench
    NativePage.PROOFREADING -> R.string.native_proofreading
}
private fun NativePage.icon(): ImageVector = when (this) {
    NativePage.AGENT -> Icons.Default.SmartToy
    NativePage.WORKBENCH -> Icons.Default.TableRows
    NativePage.PROOFREADING -> Icons.Default.Edit
}
private fun TextItemStatus.label(): Int = when (this) {
    TextItemStatus.NONE -> R.string.native_status_none
    TextItemStatus.PROCESSED -> R.string.native_status_processed
    TextItemStatus.EXCLUDED -> R.string.native_status_excluded
}
