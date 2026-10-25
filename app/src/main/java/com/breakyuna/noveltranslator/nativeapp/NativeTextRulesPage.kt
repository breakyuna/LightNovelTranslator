package com.breakyuna.noveltranslator.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.formats.LgRuleXlsxCodec
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * U05—U08: glossary, text protection, pre/post replacement and project prompts share one routed
 * page so one policy draft survives every section switch and process recreation.
 *
 * Glossary scope gap: [ProjectTextRule] stores source/target/note/enabled/regex/case sensitivity
 * and order, but no name/body scope. No name-only scope is invented here; the UI states that the
 * native model applies term rules to body text. Hit evidence is read from an already completed
 * quality scan and never triggers a project scan from composition; per-rule source hit counts are
 * therefore not available without a separate, user-started scan.
 */
@Composable
internal fun NativeTextRulesPage(
    state: NativeProjectUiState,
    change: (ProjectTextPolicy) -> Unit,
    save: () -> Unit,
    reload: () -> Unit,
    presets: NativeRulePresetActions? = null,
    templates: NativePromptTemplateActions? = null,
    section: NativeRuleSection = state.rulesSection,
    selectSection: (NativeRuleSection) -> Unit = { _ -> },
    back: () -> Unit = {},
) {
    val ref = state.project?.ref ?: return
    val draft = state.policyDraft?.takeIf { it.ref == ref }
    val ruleKind = section.kind
    var editing by rememberSaveable(ref) { mutableStateOf<String?>(null) }
    var templateOpen by rememberSaveable(ref) { mutableStateOf(false) }
    var library by rememberSaveable(ref) { mutableStateOf(false) }
    var exchange by rememberSaveable(ref) { mutableStateOf(false) }
    var reloading by rememberSaveable(ref) { mutableStateOf(false) }
    var exchangeError by remember(ref) { mutableStateOf(false) }
    var invalidEntry by remember(ref) { mutableStateOf(false) }
    var search by rememberSaveable(ref, section) { mutableStateOf("") }
    var selectedIds by remember(ref, section) { mutableStateOf<Set<String>>(emptySet()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val exchangeScope = rememberCoroutineScope()
    var pendingLgExport by rememberSaveable(ref) { mutableStateOf<String?>(null) }
    var pendingExportKind by rememberSaveable(ref) { mutableStateOf(TextRuleKind.TERM.name) }
    var pendingExportFormat by rememberSaveable(ref) { mutableStateOf("json") }
    var exporting by remember(ref) { mutableStateOf(false) }
    val finishExport: (android.net.Uri?) -> Unit = { uri ->
        val text = pendingLgExport
        val frozenKind = TextRuleKind.valueOf(pendingExportKind)
        val frozenFormat = pendingExportFormat
        pendingLgExport = null
        if (uri != null && text != null) {
            exporting = true
            exchangeScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val bytes = when (frozenFormat) {
                            "csv" -> TextProjectFingerprint.utf8(RuleCsvCodec.encode(LgRuleJsonCodec.decode(text, frozenKind).policy(), frozenKind))
                            "xlsx" -> LgRuleXlsxCodec.encode(LgRuleJsonCodec.decode(text, frozenKind).policy(), frozenKind)
                            else -> TextProjectFingerprint.utf8(text)
                        }
                        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                            output.write(bytes); output.flush()
                        } ?: throw java.io.IOException("Rule destination unavailable")
                    }
                    exchangeError = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { exchangeError = true }
                finally { exporting = false }
            }
        }
    }
    val exportLgFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), finishExport)
    val exportCsvFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), finishExport)
    val exportXlsxFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), finishExport)
    val writable = !state.busy && !state.policyLoadIncomplete && draft != null && !draft.conflict
    val qualityIndex = state.qualityView?.index
    val qualityEvidence = qualityIndex?.takeIf { state.qualityCurrent }
    val visibleRules = if (ruleKind == null) emptyList() else filterPolicyRules(
        draft?.policy?.rules.orEmpty(), section, search)
    val allVisibleSelected = visibleRules.isNotEmpty() && visibleRules.all { it.id in selectedIds }
    val applyBatch: (Boolean) -> Unit = { enabled ->
        if (draft != null) {
            val changed = setRulesEnabled(draft.policy.rules, selectedIds, enabled)
            if (changed != draft.policy.rules) change(draft.policy.changed(rules = changed))
            selectedIds = emptySet()
        }
    }
    if (reloading) AlertDialog(onDismissRequest = { reloading = false },
        title = { Text(stringResource(R.string.native_policy_reload)) },
        text = { Text(stringResource(R.string.native_policy_discard_body)) },
        confirmButton = { TextButton(onClick = { reloading = false; selectedIds = emptySet(); reload() }, enabled = !state.busy) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { reloading = false }) { Text(stringResource(R.string.native_cancel)) } })
    if (draft != null) {
        if (templateOpen && section == NativeRuleSection.PROMPTS) key(ref, draft.command) {
            NativePromptTemplateDialog(requireNotNull(state.project), draft.policy, writable,
                close = { templateOpen = false }, library = templates) { expected, policy ->
                if (writable && draft.policy.fingerprint() == expected) { change(policy); templateOpen = false }
            }
        }
        if (library && presets != null && ruleKind != null) key(ref, draft.command) {
            NativeRulePresetLibrary(ref, draft.policy, presets, writable, close = { library = false }) { expected, policy ->
                if (writable && draft.policy.fingerprint() == expected) {
                    change(policy); library = false; selectedIds = emptySet()
                }
            }
        }
        editing?.let { id ->
            val editorKind = draft.policy.rules.firstOrNull { it.id == id }?.kind ?: ruleKind
            if (editorKind != null) NativeTextRuleEditor(ref, id, draft.policy.rules.firstOrNull { it.id == id }, editorKind,
                invalid = invalidEntry, close = { editing = null; invalidEntry = false }) { rule ->
                val rules = draft.policy.rules.toMutableList()
                val index = rules.indexOfFirst { it.id == rule.id }
                if (index < 0) rules.add(rule) else rules[index] = rule
                val policy = draft.policy.changed(rules = rules)
                try { ProjectTextPolicyCodec.encode(policy); change(policy); editing = null; invalidEntry = false }
                catch (_: IllegalArgumentException) { invalidEntry = true }
            }
        }
        if (exchange && ruleKind != null) key(ref, draft.command, draft.policy.fingerprint()) {
            NativePolicyImportDialog(ref, draft.policy, ruleKind, close = { exchange = false }) { expected, policy ->
                if (writable && draft.policy.fingerprint() == expected) {
                    change(policy); exchange = false; selectedIds = emptySet()
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("native-policy-page"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = back, modifier = Modifier.testTag("native-rules-back")) {
                    Text(stringResource(R.string.native_more))
                }
                Text(stringResource(section.title()), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.testTag("native-rules-title"))
                Text(stringResource(R.string.native_rules_save_target, stringResource(section.title())),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("native-rules-save-target"))
            }
        }
        item(key = "sections") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.native_rules_section_nav), style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("native-rules-sections")) {
                    items(NativeRuleSection.entries, key = { it.name }) { entry ->
                        FilterChip(selected = entry == section, onClick = { selectSection(entry) },
                            label = { Text(stringResource(entry.label())) },
                            modifier = Modifier.testTag("native-rules-section-${entry.slug}"))
                    }
                }
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = save, enabled = writable && draft?.dirty == true,
                    modifier = Modifier.fillMaxWidth().testTag("native-policy-save")) {
                    Text(stringResource(R.string.native_rules_save_section, stringResource(section.title())))
                }
                TextButton(onClick = {
                    if (draft?.dirty == true) reloading = true else { selectedIds = emptySet(); reload() }
                }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.native_policy_reload)) }
            }
        }
        if (state.policyLoadIncomplete) item(key = "load-error") {
            Text(stringResource(R.string.native_policy_load_error), color = MaterialTheme.colorScheme.error)
        }
        if (draft?.conflict == true) item(key = "conflict") {
            Text(stringResource(R.string.native_conflict), color = MaterialTheme.colorScheme.error)
        }
        if (draft?.dirty == true) item(key = "unsaved") {
            Text(stringResource(R.string.native_policy_unsaved), style = MaterialTheme.typography.bodySmall)
        }
        val invalidRuleId = state.policyInvalidRuleId
        if (invalidRuleId != null) item(key = "invalid-rule") {
            val rule = draft?.policy?.rules?.firstOrNull { it.id == invalidRuleId }
            val label = rule?.source?.let { boundRuleText(it, 80).text } ?: invalidRuleId
            Text(stringResource(R.string.native_rules_invalid_rule, label),
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("native-rules-invalid"))
        }
        if (draft != null && section == NativeRuleSection.PROMPTS) {
            item(key = "prompt") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(draft.policy.promptEnabled, { change(draft.policy.changed(promptEnabled = it)) },
                            enabled = writable, modifier = Modifier.testTag("native-rules-prompt-enabled"))
                        Text(stringResource(R.string.native_policy_prompt_enabled))
                    }
                    OutlinedTextField(draft.policy.prompt, { value ->
                        if (value.length <= 16_384) runCatching { draft.policy.changed(prompt = value) }.getOrNull()?.let(change)
                    }, label = { Text(stringResource(R.string.native_policy_prompt)) }, enabled = writable,
                        minLines = 8, maxLines = 18,
                        modifier = Modifier.fillMaxWidth().testTag("native-policy-prompt"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { templateOpen = true }, enabled = writable,
                            modifier = Modifier.testTag("native-prompt-template-open")) {
                            Text(stringResource(R.string.native_prompt_template))
                        }
                        Text(stringResource(R.string.native_rules_chars, draft.policy.prompt.length, 16_384),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(R.string.native_rules_prompt_hint), style = MaterialTheme.typography.bodySmall)
                    if (draft.dirty) Text(stringResource(R.string.native_rules_prompt_unsaved),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("native-rules-prompt-unsaved"))
                    Text(stringResource(R.string.native_rules_prompt_no_default), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (draft != null && ruleKind != null) {
            item(key = "toolbar") {
                Column(Modifier.testTag("native-rules-toolbar"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(search, { if (it.length <= 200) { search = it; selectedIds = emptySet() } },
                        label = { Text(stringResource(R.string.native_rules_search)) }, singleLine = true,
                        trailingIcon = {
                            if (search.isNotEmpty()) IconButton(onClick = { search = ""; selectedIds = emptySet() }) {
                                Icon(Icons.Default.Clear, stringResource(R.string.native_rules_search_clear))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("native-rules-search"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.native_rules_selected, selectedIds.size),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { selectedIds = visibleRules.map { it.id }.toSet() },
                            enabled = allVisibleSelected.not() && visibleRules.isNotEmpty(),
                            modifier = Modifier.testTag("native-rules-select-all")) {
                            Text(stringResource(R.string.native_rules_select_all))
                        }
                        TextButton(onClick = { selectedIds = emptySet() }, enabled = selectedIds.isNotEmpty(),
                            modifier = Modifier.testTag("native-rules-clear-selection")) {
                            Text(stringResource(R.string.native_rules_clear_selection))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { applyBatch(true) }, enabled = writable && selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f).testTag("native-rules-enable-selected")) {
                            Text(stringResource(R.string.native_rules_enable_selected))
                        }
                        OutlinedButton(onClick = { applyBatch(false) }, enabled = writable && selectedIds.isNotEmpty(),
                            modifier = Modifier.weight(1f).testTag("native-rules-disable-selected")) {
                            Text(stringResource(R.string.native_rules_disable_selected))
                        }
                    }
                    TextButton(onClick = { editing = UUID.randomUUID().toString() },
                        enabled = writable && draft.policy.rules.size < 256,
                        modifier = Modifier.testTag("native-policy-add")) {
                        Text(stringResource(R.string.native_policy_add))
                    }
                }
            }
            if (section == NativeRuleSection.GLOSSARY) item(key = "glossary-scope") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.native_rules_glossary_scope), style = MaterialTheme.typography.bodySmall)
                    when {
                        qualityEvidence != null -> Text(stringResource(R.string.native_rules_evidence_hint),
                            style = MaterialTheme.typography.bodySmall)
                        qualityIndex != null -> Text(stringResource(R.string.native_rules_evidence_stale),
                            style = MaterialTheme.typography.bodySmall)
                        else -> Text(stringResource(R.string.native_rules_evidence_none),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (section == NativeRuleSection.TEXT_PRESERVE || section == NativeRuleSection.PRE_REPLACE ||
                section == NativeRuleSection.POST_REPLACE) item(key = "sample") {
                NativeRuleSamplePanel(ref, section, draft.policy, enabled = !state.busy)
            }
            if (visibleRules.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.native_policy_empty))
            }
            itemsIndexed(visibleRules, key = { _, rule -> "rule-" + rule.id }) { visibleIndex, rule ->
                NativeRuleCard(rule, visibleIndex, visibleRules, draft.policy.rules,
                    selected = rule.id in selectedIds,
                    writable = writable,
                    invalid = rule.id == state.policyInvalidRuleId,
                    missingIn = qualityEvidence?.missingTermCounts?.get(rule.id)?.takeIf { it > 0 },
                    hitIn = qualityEvidence?.termHitCounts?.get(rule.id)?.takeIf { it > 0 },
                    onSelected = { checked ->
                        selectedIds = if (checked) selectedIds + rule.id else selectedIds - rule.id
                    },
                    onEnabled = { enabled ->
                        change(draft.policy.changed(rules = draft.policy.rules.map {
                            if (it.id == rule.id) it.copy(enabled = enabled) else it
                        }))
                    },
                    onEdit = { editing = rule.id },
                    onDelete = {
                        selectedIds = selectedIds - rule.id
                        change(draft.policy.changed(rules = draft.policy.rules.filterNot { it.id == rule.id }))
                    },
                    onMove = { other ->
                        val index = draft.policy.rules.indexOfFirst { it.id == rule.id }
                        change(draft.policy.changed(rules = movedRule(draft.policy.rules, index, other)))
                    })
            }
            item(key = "exchange") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.native_policy_exchange_notice), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.native_policy_lg_json_notice, stringResource(ruleKind.label())),
                        style = MaterialTheme.typography.bodySmall)
                    if (presets != null) TextButton(onClick = { library = true }, enabled = writable) {
                        Text(stringResource(R.string.native_preset_library))
                    }
                    Row {
                        TextButton(onClick = { exchange = true }, enabled = writable) { Text(stringResource(R.string.native_policy_import)) }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(ProjectTextPolicyCodec.encode(draft.policy))) },
                            enabled = !state.busy) { Text(stringResource(R.string.native_policy_export)) }
                    }
                    if (exchangeError) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
                    Row {
                        TextButton(onClick = {
                            runCatching { LgRuleJsonCodec.encode(draft.policy, ruleKind) }
                                .onSuccess { clipboard.setText(AnnotatedString(it)); exchangeError = false }
                                .onFailure { exchangeError = true }
                        }, enabled = !state.busy) { Text(stringResource(R.string.native_policy_export_lg_json)) }
                    }
                    TextButton(onClick = {
                        runCatching { LgRuleJsonCodec.encode(draft.policy, ruleKind) }
                            .onSuccess {
                                pendingLgExport = it
                                pendingExportKind = ruleKind.name; pendingExportFormat = "json"
                                try { exportLgFile.launch("rules-${ruleKind.name.lowercase(Locale.ROOT)}.json") }
                                catch (_: Exception) { pendingLgExport = null; exchangeError = true }
                            }.onFailure { exchangeError = true }
                    }, enabled = !state.busy && !exporting && pendingLgExport == null) {
                        Text(stringResource(R.string.native_policy_save_lg_json))
                    }
                    for (format in listOf("csv", "xlsx")) TextButton(onClick = {
                        runCatching { LgRuleJsonCodec.encode(draft.policy, ruleKind) }.onSuccess {
                            pendingLgExport = it; pendingExportKind = ruleKind.name; pendingExportFormat = format
                            try {
                                val name = "rules-${ruleKind.name.lowercase(Locale.ROOT)}.$format"
                                if (format == "csv") exportCsvFile.launch(name) else exportXlsxFile.launch(name)
                            } catch (_: Exception) { pendingLgExport = null; exchangeError = true }
                        }.onFailure { exchangeError = true }
                    }, enabled = !state.busy && !exporting && pendingLgExport == null) {
                        Text(stringResource(if (format == "csv") R.string.native_policy_save_csv else R.string.native_policy_save_xlsx))
                    }
                    Row {
                        TextButton(onClick = {
                            runCatching { RuleExchangeCodec.encodePolicy(draft.policy) }
                                .onSuccess { clipboard.setText(AnnotatedString(it)); exchangeError = false }
                                .onFailure { exchangeError = true }
                        }, enabled = !state.busy) {
                            Text(stringResource(R.string.native_policy_export_exchange))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeRuleCard(
    rule: ProjectTextRule,
    visibleIndex: Int,
    visibleRules: List<ProjectTextRule>,
    allRules: List<ProjectTextRule>,
    selected: Boolean,
    writable: Boolean,
    invalid: Boolean,
    missingIn: Int?,
    hitIn: Int?,
    onSelected: (Boolean) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val selectLabel = stringResource(R.string.native_rules_select_rule)
    val enabledLabel = stringResource(if (rule.enabled) R.string.native_rules_enabled else R.string.native_rules_disabled)
    Card(Modifier.fillMaxWidth().testTag("native-rule-${rule.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(selected, onSelected, enabled = writable,
                    modifier = Modifier.semantics { contentDescription = selectLabel }
                        .testTag("native-rule-select-${rule.id}"))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(rule.source, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (rule.target.isNotEmpty()) Text(rule.target, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (rule.note.isNotBlank()) Text(rule.note, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (rule.enabled) R.string.native_rules_enabled else R.string.native_rules_disabled),
                        style = MaterialTheme.typography.bodySmall)
                    if (rule.regex) Text(stringResource(R.string.native_policy_regex),
                        style = MaterialTheme.typography.bodySmall)
                    if (rule.kind == TextRuleKind.TERM) Text(stringResource(
                        when (rule.scope) {
                            TextRuleScope.BODY -> R.string.native_rules_scope_body
                            TextRuleScope.NAME -> R.string.native_rules_scope_name
                            TextRuleScope.BOTH -> R.string.native_rules_scope_both
                        }), style = MaterialTheme.typography.bodySmall)
                    if (hitIn != null) Text(stringResource(R.string.native_rules_evidence_hit, hitIn),
                        style = MaterialTheme.typography.bodySmall)
                    if (missingIn != null) Text(stringResource(R.string.native_rules_evidence_missing, missingIn),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("native-rule-invalid-${rule.id}"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rule.enabled, onEnabled, enabled = writable,
                    modifier = Modifier.semantics { contentDescription = enabledLabel }
                        .testTag("native-rule-enabled-${rule.id}"))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit, enabled = writable,
                    modifier = Modifier.testTag("native-rule-edit-${rule.id}")) { Text(stringResource(R.string.native_policy_edit)) }
                TextButton(onClick = onDelete, enabled = writable,
                    modifier = Modifier.testTag("native-rule-delete-${rule.id}")) { Text(stringResource(R.string.native_policy_delete)) }
            }
            Row {
                val index = allRules.indexOfFirst { it.id == rule.id }
                val previous = visibleRules.getOrNull(visibleIndex - 1)?.let { neighbor ->
                    allRules.indexOfFirst { it.id == neighbor.id }
                } ?: -1
                val next = visibleRules.getOrNull(visibleIndex + 1)?.let { neighbor ->
                    allRules.indexOfFirst { it.id == neighbor.id }
                } ?: -1
                TextButton(onClick = { if (previous >= 0) onMove(previous) }, enabled = writable && previous >= 0,
                    modifier = Modifier.testTag("native-rule-up-${rule.id}")) { Text(stringResource(R.string.native_policy_up)) }
                TextButton(onClick = { if (next >= 0) onMove(next) }, enabled = writable && next >= 0,
                    modifier = Modifier.testTag("native-rule-down-${rule.id}")) { Text(stringResource(R.string.native_policy_down)) }
            }
        }
    }
}

@Composable
private fun NativeRuleSamplePanel(
    ref: LocalProjectRef,
    section: NativeRuleSection,
    policy: ProjectTextPolicy,
    enabled: Boolean,
) {
    var sample by rememberSaveable(ref, section) { mutableStateOf("") }
    var preview by remember(ref, section) { mutableStateOf<NativeRuleSampleOutcome?>(null) }
    var testing by remember(ref, section) { mutableStateOf(false) }
    var invalid by remember(ref, section) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val kind = section.kind ?: return
    val sectionRules = remember(policy, kind) { policy.rules.filter { it.kind == kind } }
    val enabledRules = sectionRules.count { it.enabled }
    val byId = remember(policy) { policy.rules.associateBy { it.id } }
    val latestPolicy by rememberUpdatedState(policy)
    LaunchedEffect(policy) { preview = null }
    Card(Modifier.fillMaxWidth().testTag("native-rule-sample")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.native_rules_sample_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.native_rules_sample_hint, NATIVE_RULE_SAMPLE_MAX_CHARS),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.native_rules_sample_scope), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(sample, {
                if (it.length <= NATIVE_RULE_SAMPLE_MAX_CHARS) { sample = it; preview = null; invalid = false }
            }, label = { Text(stringResource(R.string.native_rules_sample)) }, enabled = enabled,
                minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("native-rule-sample-text"))
            Text(stringResource(R.string.native_rules_chars, sample.length, NATIVE_RULE_SAMPLE_MAX_CHARS),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val current = sample
                    testing = true; invalid = false
                    scope.launch {
                        try {
                            val outcome = withContext(Dispatchers.Default) {
                                previewRuleSample(policy, section, current) { ensureActive() }
                            }
                            if (sample == current && latestPolicy === policy) preview = outcome
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { invalid = true }
                        finally { testing = false }
                    }
                }, enabled = enabled && !testing && sample.isNotEmpty() && sectionRules.isNotEmpty(),
                    modifier = Modifier.testTag("native-rule-sample-run")) {
                    Text(stringResource(R.string.native_rules_sample_run))
                }
                TextButton(onClick = { sample = ""; preview = null; invalid = false }, enabled = enabled && !testing,
                    modifier = Modifier.testTag("native-rule-sample-clear")) {
                    Text(stringResource(R.string.native_rules_sample_clear))
                }
            }
            if (sectionRules.isEmpty()) Text(stringResource(R.string.native_rules_sample_empty),
                style = MaterialTheme.typography.bodySmall)
            else if (enabledRules == 0) Text(stringResource(R.string.native_rules_sample_no_enabled),
                style = MaterialTheme.typography.bodySmall)
            if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
            preview?.let { outcome ->
                if (outcome.matches.isEmpty() && outcome.errors.isEmpty() && !outcome.failed)
                    Text(stringResource(R.string.native_rules_sample_no_matches), style = MaterialTheme.typography.bodySmall)
                outcome.matches.forEach { match ->
                    val rule = byId[match.ruleId]
                    Text(stringResource(R.string.native_rules_sample_matches, match.count),
                        style = MaterialTheme.typography.titleSmall)
                    Text(boundRuleText(rule?.source.orEmpty(), 80).text,
                        style = MaterialTheme.typography.bodySmall)
                    if (!match.enabled) Text(stringResource(R.string.native_rules_disabled),
                        style = MaterialTheme.typography.bodySmall)
                    match.excerpts.forEach { excerpt ->
                        Text(excerpt, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp))
                    }
                    if (match.truncated) Text(stringResource(R.string.native_rules_sample_match_truncated),
                        style = MaterialTheme.typography.bodySmall)
                }
                outcome.errors.forEach { id ->
                    val rule = byId[id]
                    Text(stringResource(R.string.native_rules_sample_error,
                        boundRuleText(rule?.source.orEmpty().takeIf { it.isNotEmpty() } ?: id, 80).text),
                        color = MaterialTheme.colorScheme.error)
                }
                if (outcome.failed)
                    Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
                outcome.result?.let { result ->
                    Text(stringResource(R.string.native_rules_sample_result), style = MaterialTheme.typography.titleSmall)
                    if (result.text.isEmpty()) Text(stringResource(R.string.native_rules_sample_result_empty),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("native-rule-sample-result"))
                    else Text(result.text, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("native-rule-sample-result"))
                    if (result.truncated) Text(stringResource(R.string.native_rules_sample_result_truncated),
                        style = MaterialTheme.typography.bodySmall)
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
    var enabled by rememberSaveable(ref, id) { mutableStateOf(existing?.enabled ?: true) }
    var scopeName by rememberSaveable(ref, id) { mutableStateOf((existing?.scope ?: TextRuleScope.BODY).name) }
    val ruleScope = runCatching { TextRuleScope.valueOf(scopeName) }.getOrDefault(TextRuleScope.BODY)
    val rule = remember(source, target, note, regex, sensitive, enabled, ruleScope) { runCatching {
        ProjectTextRule(id, existing?.kind ?: kind, source, target, note, enabled, regex, sensitive, ruleScope)
    }.getOrNull() }
    NativePolicyForm(close, stringResource(R.string.native_rules_rule_editor), "native-policy-rule-dialog", rule != null,
        { rule?.let(apply) }) {
        if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
        Text(stringResource((existing?.kind ?: kind).section().title()), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(source, { if (it.length <= 4096) source = it }, label = { Text(stringResource(R.string.native_policy_source)) },
            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("native-policy-source"))
        if (kind != TextRuleKind.PROTECT) OutlinedTextField(target, { if (it.length <= 4096) target = it },
            label = { Text(stringResource(R.string.native_policy_target)) },
            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("native-policy-target"))
        OutlinedTextField(note, { if (it.length <= 2048) note = it }, label = { Text(stringResource(R.string.native_policy_note)) },
            minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().testTag("native-policy-note"))
        if (kind == TextRuleKind.TERM) {
            Text(stringResource(R.string.native_rules_scope), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (candidate in TextRuleScope.entries) {
                    FilterChip(selected = candidate == ruleScope, onClick = { scopeName = candidate.name },
                        label = { Text(stringResource(when (candidate) {
                            TextRuleScope.BODY -> R.string.native_rules_scope_body
                            TextRuleScope.NAME -> R.string.native_rules_scope_name
                            TextRuleScope.BOTH -> R.string.native_rules_scope_both
                        })) }, modifier = Modifier.testTag("native-rule-scope-${candidate.name}"))
                }
            }
        }
        if (kind != TextRuleKind.TERM) {
            Text(stringResource(R.string.native_policy_regex_hint), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(regex, { regex = it }, modifier = Modifier.testTag("native-policy-regex"))
                Text(stringResource(R.string.native_policy_regex))
            }
        }
        if (kind == TextRuleKind.PRE_REPLACE || kind == TextRuleKind.POST_REPLACE)
            Text(stringResource(R.string.native_rules_empty_target), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sensitive, { sensitive = it }, modifier = Modifier.testTag("native-policy-case"))
            Text(stringResource(R.string.native_policy_case))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(enabled, { enabled = it }, modifier = Modifier.testTag("native-policy-enabled"))
            Text(stringResource(R.string.native_rules_enabled))
        }
    }
}

private enum class NativeRuleImportKind { POLICY, EXCHANGE, PRESET, LG_JSON, CSV, LG_XLSX }

@Composable
private fun NativePolicyImportDialog(ref: LocalProjectRef, baseline: ProjectTextPolicy, ruleKind: TextRuleKind, close: () -> Unit,
    apply: (ObjectFingerprint, ProjectTextPolicy) -> Unit) {
    var text by rememberSaveable(ref) { mutableStateOf("") }
    var modeName by rememberSaveable(ref) { mutableStateOf(NativeRuleImportKind.POLICY.name) }
    var overwrite by rememberSaveable(ref) { mutableStateOf(false) }
    var includePrompt by rememberSaveable(ref) { mutableStateOf(true) }
    var preview by remember(ref) { mutableStateOf<ProjectTextPolicy?>(null) }
    var counts by remember(ref) { mutableStateOf<List<Int>?>(null) }
    var invalid by remember(ref) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var reading by remember(ref) { mutableStateOf(false) }
    val mode = NativeRuleImportKind.valueOf(modeName)
    val external = mode in setOf(NativeRuleImportKind.LG_JSON, NativeRuleImportKind.CSV, NativeRuleImportKind.LG_XLSX)
    fun invalidate() { preview = null; counts = null; invalid = false }
    val context = LocalContext.current
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            reading = true; invalidate()
            scope.launch {
                try {
                    text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(4096)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                val limit = if (mode == NativeRuleImportKind.LG_XLSX) LgRuleXlsxCodec.MAX_BYTES.toLong() else RulePresetCodec.MAX_CHARS.toLong() * 4
                                require(bytes.size().toLong() + count <= limit)
                                bytes.write(buffer, 0, count)
                            }
                            if (mode == NativeRuleImportKind.LG_XLSX) return@use LgRuleXlsxCodec.toJson(bytes.toByteArray())
                            val decoded = Charsets.UTF_8.newDecoder()
                                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString().removePrefix("\uFEFF")
                            require(decoded.length <= RulePresetCodec.MAX_CHARS)
                            decoded
                        } ?: throw java.io.IOException("Rule source unavailable")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { invalid = true }
                finally { reading = false }
            }
        }
    }
    NativePolicyForm(close, stringResource(R.string.native_policy_import), "native-policy-import-dialog", preview != null && !reading,
        { preview?.let { apply(baseline.fingerprint(), it) } }) {
        Text(stringResource(R.string.native_policy_exchange_notice))
        NativeRuleImportKind.entries.forEach { kind ->
            val label = when (kind) {
                NativeRuleImportKind.POLICY -> R.string.native_policy_import_policy
                NativeRuleImportKind.EXCHANGE -> R.string.native_policy_import_exchange
                NativeRuleImportKind.PRESET -> R.string.native_policy_import_preset
                NativeRuleImportKind.LG_JSON -> R.string.native_policy_import_lg_json
                NativeRuleImportKind.CSV -> R.string.native_policy_import_csv
                NativeRuleImportKind.LG_XLSX -> R.string.native_policy_import_xlsx
            }
            TextButton(onClick = {
                if (mode != kind && (mode == NativeRuleImportKind.LG_XLSX || kind == NativeRuleImportKind.LG_XLSX)) text = ""
                modeName = kind.name; invalidate()
            }, enabled = !reading) {
                RadioButton(mode == kind, onClick = null); Text(stringResource(label))
            }
        }
        if (mode != NativeRuleImportKind.POLICY) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(overwrite, { overwrite = it; invalidate() }, enabled = !reading)
            Text(stringResource(if (mode == NativeRuleImportKind.PRESET) R.string.native_policy_preset_replace else R.string.native_policy_exchange_overwrite), Modifier.weight(1f))
        }
        if (external) {
            Text(stringResource(R.string.native_policy_lg_json_notice, stringResource(ruleKind.label())))
        }
        if (!external) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includePrompt, { includePrompt = it; invalidate() }, enabled = !reading)
            Text(stringResource(R.string.native_policy_include_prompt), Modifier.weight(1f))
        }
        if (mode != NativeRuleImportKind.LG_XLSX) OutlinedTextField(text, { if (it.length <= RulePresetCodec.MAX_CHARS) { text = it; invalidate() } },
            minLines = 4, maxLines = 8, enabled = !reading, label = { Text(stringResource(if (mode == NativeRuleImportKind.CSV) R.string.native_policy_csv else R.string.native_policy_json)) }, modifier = Modifier.fillMaxWidth().testTag("native-policy-import-text"))
        TextButton(onClick = {
            try { importFile.launch(when (mode) {
                NativeRuleImportKind.LG_XLSX -> arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")
                NativeRuleImportKind.CSV -> arrayOf("text/csv", "text/plain", "application/octet-stream")
                else -> arrayOf("application/json", "text/plain", "application/octet-stream")
            }) }
            catch (_: Exception) { invalid = true }
        }, enabled = !reading) { Text(stringResource(R.string.native_policy_open_rule_file)) }
        TextButton(onClick = {
            reading = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) {
                        when (mode) {
                            NativeRuleImportKind.POLICY -> {
                                val decoded = ProjectTextPolicyCodec.decode(text)
                                val policy = if (includePrompt) decoded else decoded.changed(prompt = baseline.prompt, promptEnabled = baseline.promptEnabled)
                                TextRuleValidation.validate(policy) { ensureActive() }
                                policy to listOf(0, 0, 0, 0)
                            }
                            NativeRuleImportKind.EXCHANGE, NativeRuleImportKind.LG_JSON, NativeRuleImportKind.CSV, NativeRuleImportKind.LG_XLSX -> {
                                val result = com.breakyuna.noveltranslator.nativecore.engine.RulePreviewService().preview(
                                    baseline, when (mode) {
                                        NativeRuleImportKind.CSV -> RuleCsvCodec.decode(text, ruleKind, baseline)
                                        NativeRuleImportKind.LG_JSON, NativeRuleImportKind.LG_XLSX -> LgRuleJsonCodec.decode(text, ruleKind, baseline)
                                        else -> RuleExchangeCodec.decode(text)
                                    },
                                    if (overwrite) RuleExchangeDuplicatePolicy.OVERWRITE else RuleExchangeDuplicatePolicy.SKIP,
                                    includePrompt && !external) { ensureActive() }
                                (if (result.clean) result.preview.policy else null) to
                                    listOf(result.added.size, result.updated.size, result.unchanged.size, result.conflicting.size)
                            }
                            NativeRuleImportKind.PRESET -> {
                                val result = RulePresetCodec.decode(text).applyTo(baseline,
                                    if (overwrite) RulePresetApplyMode.REPLACE else RulePresetApplyMode.MERGE, includePrompt)
                                result.policy to listOf(result.diff.addedRuleIds.size, result.diff.updatedRuleIds.size,
                                    result.diff.unchangedRuleIds.size, result.diff.removedRuleIds.size)
                            }
                        }
                    }
                    preview = result.first; counts = result.second; invalid = result.first == null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { preview = null; counts = null; invalid = true }
                finally { reading = false }
            }
        }, enabled = !reading && text.isNotEmpty()) { Text(stringResource(R.string.native_policy_preview)) }
        if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
        if (mode != NativeRuleImportKind.POLICY) counts?.let {
            Text(stringResource(if (mode == NativeRuleImportKind.PRESET) R.string.native_policy_preset_diff else R.string.native_policy_exchange_diff,
                it[0], it[1], it[2], it[3]))
        }
        preview?.let { Text(stringResource(R.string.native_policy_preview_count, it.rules.size)) }
    }
}

@Composable
internal fun NativePolicyForm(close: () -> Unit, title: String, tag: String, enabled: Boolean,
    apply: () -> Unit, applyLabel: String? = null, content: @Composable ColumnScope.() -> Unit) {
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
                        TextButton(onClick = apply, enabled = enabled, modifier = Modifier.testTag("native-policy-apply")) {
                            Text(applyLabel ?: stringResource(R.string.native_policy_apply))
                        }
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
