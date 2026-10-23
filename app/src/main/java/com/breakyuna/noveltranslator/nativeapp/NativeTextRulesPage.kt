package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun NativeTextRulesPage(state: NativeProjectUiState, change: (ProjectTextPolicy) -> Unit,
    save: () -> Unit, reload: () -> Unit) {
    val ref = state.project?.ref ?: return
    val draft = state.policyDraft?.takeIf { it.ref == ref }
    var kindName by rememberSaveable(ref) { mutableStateOf(TextRuleKind.TERM.name) }
    val kind = TextRuleKind.valueOf(kindName)
    var menu by remember { mutableStateOf(false) }
    var editing by rememberSaveable(ref) { mutableStateOf<String?>(null) }
    var exchange by rememberSaveable(ref) { mutableStateOf(false) }
    var reloading by rememberSaveable(ref) { mutableStateOf(false) }
    var invalidEntry by remember(ref) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val writable = !state.busy && !state.policyLoadIncomplete && draft != null && !draft.conflict
    if (reloading) AlertDialog(onDismissRequest = { reloading = false },
        title = { Text(stringResource(R.string.native_policy_reload)) },
        text = { Text(stringResource(R.string.native_policy_discard_body)) },
        confirmButton = { TextButton(onClick = { reloading = false; reload() }, enabled = !state.busy) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { reloading = false }) { Text(stringResource(R.string.native_cancel)) } })
    if (draft != null) {
        editing?.let { id -> NativeTextRuleEditor(ref, id, draft.policy.rules.firstOrNull { it.id == id }, kind,
            invalid = invalidEntry, close = { editing = null; invalidEntry = false }) { rule ->
            val rules = draft.policy.rules.toMutableList()
            val index = rules.indexOfFirst { it.id == rule.id }
            if (index < 0) rules.add(rule) else rules[index] = rule
            val policy = draft.policy.changed(rules = rules)
            try { ProjectTextPolicyCodec.encode(policy); change(policy); editing = null; invalidEntry = false }
            catch (_: IllegalArgumentException) { invalidEntry = true }
        } }
        if (exchange) NativePolicyImportDialog(ref, close = { exchange = false }) {
            change(it); exchange = false // Preparing a replacement draft never saves it.
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("native-policy-page"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "title") { Text(stringResource(R.string.native_policy_title), style = MaterialTheme.typography.headlineSmall) }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth()) {
                Button(onClick = save, enabled = writable && draft?.dirty == true,
                    modifier = Modifier.weight(1f).testTag("native-policy-save")) { Text(stringResource(R.string.native_save)) }
                TextButton(onClick = { if (draft?.dirty == true) reloading = true else reload() }, enabled = !state.busy,
                    modifier = Modifier.weight(1f)) { Text(stringResource(R.string.native_policy_reload)) }
            }
        }
        if (state.policyLoadIncomplete) item(key = "load-error") { Text(stringResource(R.string.native_policy_load_error), color = MaterialTheme.colorScheme.error) }
        if (draft?.conflict == true) item(key = "conflict") { Text(stringResource(R.string.native_conflict), color = MaterialTheme.colorScheme.error) }
        if (draft?.dirty == true) item(key = "unsaved") { Text(stringResource(R.string.native_policy_unsaved), style = MaterialTheme.typography.bodySmall) }
        if (draft != null) {
            item(key = "prompt") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(draft.policy.promptEnabled, { change(draft.policy.changed(promptEnabled = it)) }, enabled = writable)
                        Text(stringResource(R.string.native_policy_prompt_enabled))
                    }
                    OutlinedTextField(draft.policy.prompt, { if (it.length <= 16_384)
                        runCatching { draft.policy.changed(prompt = it) }.getOrNull()?.let(change) },
                        label = { Text(stringResource(R.string.native_policy_prompt)) }, enabled = writable, minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("native-policy-prompt"))
                }
            }
            item(key = "kind") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text(stringResource(kind.label())) }
                        DropdownMenu(menu, { menu = false }) { TextRuleKind.entries.forEach { value ->
                            DropdownMenuItem(text = { Text(stringResource(value.label())) }, onClick = { kindName = value.name; menu = false })
                        } }
                    }
                    TextButton(onClick = { editing = UUID.randomUUID().toString() }, enabled = writable && draft.policy.rules.size < 256,
                        modifier = Modifier.testTag("native-policy-add")) { Text(stringResource(R.string.native_policy_add)) }
                }
            }
            val rules = draft.policy.rules.filter { it.kind == kind }
            if (rules.isEmpty()) item(key = "empty") { Text(stringResource(R.string.native_policy_empty)) }
            itemsIndexed(rules, key = { _, rule -> "rule-" + rule.id }) { visibleIndex, rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(rule.source, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (rule.target.isNotEmpty()) Text(rule.target, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (rule.id == state.policyInvalidRuleId) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(rule.enabled, { checked -> change(draft.policy.changed(rules = draft.policy.rules.map { if (it.id == rule.id) it.copy(enabled = checked) else it })) }, enabled = writable)
                            TextButton(onClick = { editing = rule.id }, enabled = writable) { Text(stringResource(R.string.native_policy_edit)) }
                            TextButton(onClick = { change(draft.policy.changed(rules = draft.policy.rules.filterNot { it.id == rule.id })) }, enabled = writable) { Text(stringResource(R.string.native_policy_delete)) }
                        }
                        Row {
                            val index = draft.policy.rules.indexOfFirst { it.id == rule.id }
                            val previous = rules.getOrNull(visibleIndex - 1)?.let { neighbor -> draft.policy.rules.indexOfFirst { it.id == neighbor.id } } ?: -1
                            val next = rules.getOrNull(visibleIndex + 1)?.let { neighbor -> draft.policy.rules.indexOfFirst { it.id == neighbor.id } } ?: -1
                            TextButton(onClick = { change(draft.policy.changed(rules = movedRule(draft.policy.rules, index, previous))) }, enabled = writable && previous >= 0) { Text(stringResource(R.string.native_policy_up)) }
                            TextButton(onClick = { change(draft.policy.changed(rules = movedRule(draft.policy.rules, index, next))) }, enabled = writable && next >= 0) { Text(stringResource(R.string.native_policy_down)) }
                        }
                    }
                }
            }
            item(key = "exchange") {
                Column {
                    Text(stringResource(R.string.native_policy_exchange_notice), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { exchange = true }, enabled = writable) { Text(stringResource(R.string.native_policy_import)) }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(ProjectTextPolicyCodec.encode(draft.policy))) }, enabled = !state.busy) { Text(stringResource(R.string.native_policy_export)) }
                    }
                }
            }
        }
    }
}

private fun movedRule(rules: List<ProjectTextRule>, index: Int, other: Int): List<ProjectTextRule> = rules.toMutableList().apply {
    require(index in indices && other in indices)
    val current = this[index]; this[index] = this[other]; this[other] = current
}

@Composable
private fun NativeTextRuleEditor(ref: LocalProjectRef, id: String, existing: ProjectTextRule?, kind: TextRuleKind,
    invalid: Boolean, close: () -> Unit, apply: (ProjectTextRule) -> Unit) {
    var source by rememberSaveable(ref, id) { mutableStateOf(existing?.source.orEmpty()) }
    var target by rememberSaveable(ref, id) { mutableStateOf(existing?.target.orEmpty()) }
    var note by rememberSaveable(ref, id) { mutableStateOf(existing?.note.orEmpty()) }
    var regex by rememberSaveable(ref, id) { mutableStateOf(existing?.regex ?: (kind == TextRuleKind.PROTECT)) }
    var sensitive by rememberSaveable(ref, id) { mutableStateOf(existing?.caseSensitive ?: true) }
    val rule = remember(source, target, note, regex, sensitive) { runCatching {
        ProjectTextRule(id, existing?.kind ?: kind, source, target, note, existing?.enabled ?: true, regex, sensitive)
    }.getOrNull() }
    NativePolicyForm(close, stringResource(R.string.native_policy_edit), "native-policy-rule-dialog", rule != null,
        { rule?.let(apply) }) {
        if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
        OutlinedTextField(source, { if (it.length <= 4096) source = it }, label = { Text(stringResource(R.string.native_policy_source)) },
            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("native-policy-source"))
        if (kind != TextRuleKind.PROTECT) OutlinedTextField(target, { if (it.length <= 4096) target = it }, label = { Text(stringResource(R.string.native_policy_target)) },
            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("native-policy-target"))
        OutlinedTextField(note, { if (it.length <= 2048) note = it }, label = { Text(stringResource(R.string.native_policy_note)) }, modifier = Modifier.fillMaxWidth())
        if (kind != TextRuleKind.TERM) Text(stringResource(R.string.native_policy_regex_hint), style = MaterialTheme.typography.bodySmall)
        if (kind != TextRuleKind.TERM) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(regex, { regex = it }); Text(stringResource(R.string.native_policy_regex))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sensitive, { sensitive = it }); Text(stringResource(R.string.native_policy_case))
        }
    }
}

@Composable
private fun NativePolicyImportDialog(ref: LocalProjectRef, close: () -> Unit, apply: (ProjectTextPolicy) -> Unit) {
    var text by rememberSaveable(ref) { mutableStateOf("") }
    // Parsing is performed only after explicit Preview, never in a Composable or on each key press.
    var preview by remember(ref) { mutableStateOf<ProjectTextPolicy?>(null) }
    var invalid by remember(ref) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var reading by remember(ref) { mutableStateOf(false) }
    NativePolicyForm(close, stringResource(R.string.native_policy_import), "native-policy-import-dialog", preview != null && !reading,
        { preview?.let(apply) }) {
        Text(stringResource(R.string.native_policy_exchange_notice))
        OutlinedTextField(text, { if (it.length <= ProjectTextPolicyCodec.MAX_CHARS) { text = it; preview = null; invalid = false } },
            minLines = 4, maxLines = 8, enabled = !reading, label = { Text(stringResource(R.string.native_policy_json)) }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = {
            reading = true
            scope.launch {
                try { preview = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    ProjectTextPolicyCodec.decode(text).also { TextRuleValidation.validate(it) { ensureActive() } }
                }; invalid = false }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { preview = null; invalid = true }
                finally { reading = false }
            }
        }, enabled = !reading && text.isNotEmpty()) { Text(stringResource(R.string.native_policy_preview)) }
        if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
        preview?.let { Text(stringResource(R.string.native_policy_preview_count, it.rules.size)) }
    }
}

@Composable
private fun NativePolicyForm(close: () -> Unit, title: String, tag: String, enabled: Boolean,
    apply: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() })
            Surface(Modifier.padding(20.dp).widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 580.dp).testTag(tag),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = close) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(onClick = apply, enabled = enabled, modifier = Modifier.testTag("native-policy-apply")) { Text(stringResource(R.string.native_policy_apply)) }
                    }
                }
            }
        }
    }
}

private fun TextRuleKind.label() = when (this) {
    TextRuleKind.TERM -> R.string.native_policy_terms
    TextRuleKind.PROTECT -> R.string.native_policy_protect
    TextRuleKind.PRE_REPLACE -> R.string.native_policy_pre
    TextRuleKind.POST_REPLACE -> R.string.native_policy_post
}
