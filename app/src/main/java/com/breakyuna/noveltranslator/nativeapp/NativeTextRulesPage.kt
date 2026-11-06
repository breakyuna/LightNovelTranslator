package com.breakyuna.noveltranslator.nativeapp

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.semantics.selected
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
    wideLayout: Boolean = false,
    /** Reports whether the wide inline editor holds unapplied fields so navigation can be gated. */
    onEditingChange: (Boolean) -> Unit = {},
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
    var selectedRuleId by rememberSaveable(ref, section) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val exchangeScope = rememberCoroutineScope()
    var pendingLgExport by rememberSaveable(ref) { mutableStateOf<String?>(null) }
    var pendingExportKind by rememberSaveable(ref) { mutableStateOf(TextRuleKind.TERM.name) }
    var pendingExportFormat by rememberSaveable(ref) { mutableStateOf("json") }
    var exporting by remember(ref) { mutableStateOf(false) }
    LaunchedEffect(editing) { onEditingChange(editing != null) }
    DisposableEffect(Unit) { onDispose { onEditingChange(false) } }
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
    val selectedRule = visibleRules.firstOrNull { it.id == selectedRuleId } ?: visibleRules.firstOrNull()
    val applyBatch: (Boolean) -> Unit = { enabled ->
        if (draft != null) {
            val changed = setRulesEnabled(draft.policy.rules, selectedIds, enabled)
            if (changed != draft.policy.rules) change(draft.policy.changed(rules = changed))
            selectedIds = emptySet()
        }
    }
    val beginEdit: (String) -> Unit = { id -> selectedRuleId = id; editing = id }
    val closeEditor: () -> Unit = { editing = null; invalidEntry = false }
    val applyEditedRule: (ProjectTextRule) -> Unit = { rule ->
        draft?.let { current ->
            val rules = current.policy.rules.toMutableList()
            val index = rules.indexOfFirst { it.id == rule.id }
            if (index < 0) rules.add(rule) else rules[index] = rule
            val policy = current.policy.changed(rules = rules)
            try {
                ProjectTextPolicyCodec.encode(policy)
                change(policy)
                selectedRuleId = rule.id
                editing = null
                invalidEntry = false
            } catch (_: IllegalArgumentException) { invalidEntry = true }
        }
    }
    val onSelected: (ProjectTextRule, Boolean) -> Unit = { rule, checked ->
        selectedIds = if (checked) selectedIds + rule.id else selectedIds - rule.id
    }
    val onEnabled: (ProjectTextRule, Boolean) -> Unit = { rule, enabled ->
        if (draft != null) change(draft.policy.changed(rules = draft.policy.rules.map {
            if (it.id == rule.id) it.copy(enabled = enabled) else it
        }))
    }
    val onDelete: (ProjectTextRule) -> Unit = { rule ->
        if (draft != null) {
            selectedIds = selectedIds - rule.id
            if (selectedRuleId == rule.id) selectedRuleId = null
            change(draft.policy.changed(rules = draft.policy.rules.filterNot { it.id == rule.id }))
        }
    }
    val onMove: (ProjectTextRule, Int) -> Unit = { rule, other ->
        if (draft != null) {
            val index = draft.policy.rules.indexOfFirst { it.id == rule.id }
            change(draft.policy.changed(rules = movedRule(draft.policy.rules, index, other)))
        }
    }
    val canEdit = writable && editing == null
    val openDetail: ((ProjectTextRule) -> Unit)? = if (editing == null) {
        { rule -> selectedRuleId = rule.id }
    } else null
    val headerContent: @Composable () -> Unit = {
        NativeSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = back,
                    enabled = editing == null,
                    modifier = Modifier.testTag("native-rules-back"),
                ) {
                    Text(stringResource(R.string.native_back))
                }
                if (draft != null) {
                    NativeStatusBadge(
                        text = if (draft.dirty) stringResource(R.string.native_rules_prompt_unsaved) else stringResource(R.string.native_saved),
                        tone = if (draft.dirty) NativeTone.PRIMARY else NativeTone.NEUTRAL,
                    )
                }
            }
            Text(
                text = stringResource(section.title()),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("native-rules-title"),
            )
        }
    }
    val sectionsContent: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().testTag("native-rules-sections"),
            ) {
                items(NativeRuleSection.entries, key = { it.name }) { entry ->
                    FilterChip(
                        selected = entry == section,
                        onClick = { selectSection(entry) },
                        enabled = editing == null,
                        label = { Text(stringResource(entry.label())) },
                        modifier = Modifier.testTag("native-rules-section-${entry.slug}"),
                    )
                }
            }
        }
    }
    val actionsContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = save,
                enabled = writable && editing == null && draft?.dirty == true,
                modifier = Modifier.weight(1.4f).testTag("native-policy-save"),
            ) {
                Text(stringResource(R.string.native_save_changes), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = {
                    if (draft?.dirty == true) reloading = true else { selectedIds = emptySet(); reload() }
                },
                enabled = !state.busy && editing == null,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.native_policy_reload), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val statusContent: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.policyLoadIncomplete) {
                NativeBanner(text = stringResource(R.string.native_policy_load_error), tone = NativeTone.ERROR)
            }
            if (draft?.conflict == true) {
                NativeBanner(text = stringResource(R.string.native_conflict), tone = NativeTone.ERROR)
            }
            val invalidRuleId = state.policyInvalidRuleId
            if (invalidRuleId != null) {
                val label = draft?.policy?.rules?.firstOrNull { it.id == invalidRuleId }?.source
                    ?.let { boundRuleText(it, 80).text } ?: invalidRuleId
                Text(
                    text = stringResource(R.string.native_rules_invalid_rule, label),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("native-rules-invalid"),
                )
            }
        }
    }
    val toolbarContent: @Composable () -> Unit = {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("native-rules-toolbar"),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    enabled = editing == null,
                    onValueChange = { if (it.length <= 200) { search = it; selectedIds = emptySet() } },
                    label = { Text(stringResource(R.string.native_rules_search)) },
                    singleLine = true,
                    trailingIcon = {
                        if (search.isNotEmpty()) {
                            IconButton(onClick = { search = ""; selectedIds = emptySet() }) {
                                Icon(Icons.Default.Clear, stringResource(R.string.native_rules_search_clear))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("native-rules-search"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.native_rules_selected, selectedIds.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { selectedIds = visibleRules.map { it.id }.toSet() },
                        enabled = allVisibleSelected.not() && visibleRules.isNotEmpty() && canEdit,
                        modifier = Modifier.testTag("native-rules-select-all"),
                    ) {
                        Text(stringResource(R.string.native_rules_select_all))
                    }
                    TextButton(
                        onClick = { selectedIds = emptySet() },
                        enabled = selectedIds.isNotEmpty() && canEdit,
                        modifier = Modifier.testTag("native-rules-clear-selection"),
                    ) {
                        Text(stringResource(R.string.native_rules_clear_selection))
                    }
                }
                if (selectedIds.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { applyBatch(true) },
                        enabled = canEdit && selectedIds.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag("native-rules-enable-selected"),
                    ) {
                        Text(stringResource(R.string.native_rules_enable_selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { applyBatch(false) },
                        enabled = canEdit && selectedIds.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag("native-rules-disable-selected"),
                    ) {
                        Text(stringResource(R.string.native_rules_disable_selected), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                FilledTonalButton(
                    onClick = { beginEdit(UUID.randomUUID().toString()) },
                    enabled = writable && editing == null && draft?.policy?.rules?.size?.let { it < 256 } == true,
                    modifier = Modifier.fillMaxWidth().testTag("native-policy-add"),
                ) {
                    Text(stringResource(R.string.native_policy_add))
                }
            }
        }
    }
    val promptContent: @Composable () -> Unit = {
        if (draft != null) {
            NativeSectionCard {
                var previewPrompt by rememberSaveable(ref) { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(
                        checked = draft.policy.promptEnabled,
                        onCheckedChange = { change(draft.policy.changed(promptEnabled = it)) },
                        enabled = writable,
                        modifier = Modifier.testTag("native-rules-prompt-enabled"),
                    )
                    Text(stringResource(R.string.native_policy_prompt_enabled), style = MaterialTheme.typography.titleSmall)
                }
                TextButton(onClick = { previewPrompt = !previewPrompt }) {
                    Text(stringResource(if (previewPrompt) R.string.native_model_action_edit else R.string.native_workspace_preview))
                }
                if (previewPrompt) SelectionContainer { NativeMarkdown(draft.policy.prompt) }
                else OutlinedTextField(
                    value = draft.policy.prompt,
                    onValueChange = { value ->
                        if (value.length <= 16_384) runCatching { draft.policy.changed(prompt = value) }.getOrNull()?.let(change)
                    },
                    label = { Text(stringResource(R.string.native_policy_prompt)) },
                    enabled = writable,
                    minLines = 8,
                    maxLines = 18,
                    modifier = Modifier.fillMaxWidth().testTag("native-policy-prompt"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { templateOpen = true },
                        enabled = writable,
                        modifier = Modifier.testTag("native-prompt-template-open"),
                    ) {
                        Text(stringResource(R.string.native_prompt_template))
                    }
                    Text(
                        text = stringResource(R.string.native_rules_chars, draft.policy.prompt.length, 16_384),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (draft.dirty) {
                    Text(
                        text = stringResource(R.string.native_rules_prompt_unsaved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("native-rules-prompt-unsaved"),
                    )
                }
            }
        }
    }
    val glossaryContent: @Composable () -> Unit = {
        NativeSectionCard {
            Text(stringResource(R.string.native_rules_glossary_scope), style = MaterialTheme.typography.bodySmall)
            when {
                qualityEvidence != null -> Text(
                    text = stringResource(R.string.native_rules_evidence_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                qualityIndex != null -> Text(
                    text = stringResource(R.string.native_rules_evidence_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = stringResource(R.string.native_rules_evidence_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    val sampleContent: @Composable () -> Unit = {
        draft?.let { NativeRuleSamplePanel(ref, section, it.policy, enabled = !state.busy && editing == null) }
    }
    val exchangeContent: @Composable () -> Unit = {
        if (draft != null && ruleKind != null) {
            val interaction = writable && editing == null
            NativeSectionCard(
                subtitle = null,
            ) {
                if (presets != null) {
                    OutlinedButton(onClick = { library = true }, enabled = interaction, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.native_preset_library))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exchange = true }, enabled = interaction, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.native_policy_import))
                    }
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(ProjectTextPolicyCodec.encode(draft.policy))) },
                        enabled = !state.busy && editing == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.native_policy_export))
                    }
                }
                if (exchangeError) {
                    Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            runCatching { LgRuleJsonCodec.encode(draft.policy, ruleKind) }
                                .onSuccess { clipboard.setText(AnnotatedString(it)); exchangeError = false }
                                .onFailure { exchangeError = true }
                        },
                        enabled = !state.busy && editing == null,
                    ) {
                        Text(stringResource(R.string.native_policy_export_lg_json))
                    }
                    TextButton(
                        onClick = {
                            runCatching { LgRuleJsonCodec.encode(draft.policy, ruleKind) }
                                .onSuccess {
                                    pendingLgExport = it
                                    pendingExportKind = ruleKind.name
                                    pendingExportFormat = "json"
                                    try { exportLgFile.launch("rules-${ruleKind.name.lowercase(Locale.ROOT)}.json") }
                                    catch (_: Exception) { pendingLgExport = null; exchangeError = true }
                                }.onFailure { exchangeError = true }
                        },
                        enabled = !state.busy && editing == null && !exporting && pendingLgExport == null,
                    ) {
                        Text(stringResource(R.string.native_policy_save_lg_json))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (format in listOf("csv", "xlsx")) {
                        TextButton(
                            onClick = {
                                runCatching { LgRuleJsonCodec.encode(draft.policy, ruleKind) }.onSuccess {
                                    pendingLgExport = it
                                    pendingExportKind = ruleKind.name
                                    pendingExportFormat = format
                                    try {
                                        val name = "rules-${ruleKind.name.lowercase(Locale.ROOT)}.$format"
                                        if (format == "csv") exportCsvFile.launch(name) else exportXlsxFile.launch(name)
                                    } catch (_: Exception) { pendingLgExport = null; exchangeError = true }
                                }.onFailure { exchangeError = true }
                            },
                            enabled = !state.busy && editing == null && !exporting && pendingLgExport == null,
                        ) {
                            Text(stringResource(if (format == "csv") R.string.native_policy_save_csv else R.string.native_policy_save_xlsx))
                        }
                    }
                    TextButton(
                        onClick = {
                            runCatching { RuleExchangeCodec.encodePolicy(draft.policy) }
                                .onSuccess { clipboard.setText(AnnotatedString(it)); exchangeError = false }
                                .onFailure { exchangeError = true }
                        },
                        enabled = !state.busy && editing == null,
                    ) {
                        Text(stringResource(R.string.native_policy_export_exchange))
                    }
                }
            }
        }
    }
    if (reloading) NativeContentDialog(onDismissRequest = { reloading = false },
        title = { Text(stringResource(R.string.native_policy_reload)) },
        text = { Text(stringResource(R.string.native_policy_discard_body)) },
        confirmButton = { TextButton(onClick = { reloading = false; selectedIds = emptySet(); reload() }, enabled = !state.busy) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { reloading = false }) { Text(stringResource(R.string.native_cancel)) } })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = wideLayout || maxWidth >= NativeTwoPaneBreakpoint

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
            if (exchange && ruleKind != null) key(ref, draft.command, draft.policy.fingerprint()) {
                NativePolicyImportDialog(ref, draft.policy, ruleKind, close = { exchange = false }) { expected, policy ->
                    if (writable && draft.policy.fingerprint() == expected) {
                        change(policy); exchange = false; selectedIds = emptySet()
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            Surface(Modifier.fillMaxWidth()) { Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { actionsContent() } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (wide && section != NativeRuleSection.PROMPTS) {
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1.2f).fillMaxHeight().testTag("native-policy-page"),
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "header") { headerContent() }
                            item(key = "sections") { sectionsContent() }
                            item(key = "status") { statusContent() }
                            if (draft != null && ruleKind != null) {
                                item(key = "toolbar") { toolbarContent() }
                                if (visibleRules.isEmpty()) item(key = "empty") { Text(stringResource(R.string.native_policy_empty)) }
                                nativeRuleItems(visibleRules, draft.policy.rules, selectedIds, canEdit,
                                    state.policyInvalidRuleId, qualityEvidence, editing ?: selectedRule?.id,
                                    onSelected, onEnabled, { beginEdit(it.id) }, onDelete, onMove, openDetail)
                            }
                        }
                        VerticalDivider()
                        LazyColumn(Modifier.weight(1f).fillMaxHeight().imePadding().testTag("native-rules-detail"),
                            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "detail-title") {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(section.title()), style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                            if (draft != null && section == NativeRuleSection.PROMPTS) {
                                item(key = "prompt") { promptContent() }
                            }
                            if (draft != null && ruleKind != null) {
                                val editingId = editing
                                when {
                                    editingId != null -> item(key = "rule-editor-" + editingId) {
                                        val editorKind = draft.policy.rules.firstOrNull { it.id == editingId }?.kind ?: ruleKind
                                        NativeTextRuleEditor(ref, editingId, draft.policy.rules.firstOrNull { it.id == editingId },
                                            editorKind, invalid = invalidEntry, close = closeEditor,
                                            inlineForm = true, apply = applyEditedRule)
                                    }
                                    selectedRule != null -> item(key = "rule-detail-" + selectedRule.id) {
                                        NativeRuleDetail(selectedRule, canEdit, selectedRule.id == state.policyInvalidRuleId,
                                            qualityEvidence?.missingTermCounts?.get(selectedRule.id)?.takeIf { it > 0 },
                                            qualityEvidence?.termHitCounts?.get(selectedRule.id)?.takeIf { it > 0 },
                                            onEdit = { if (canEdit) beginEdit(selectedRule.id) })
                                    }
                                    else -> item(key = "empty") { Text(stringResource(R.string.native_policy_empty)) }
                                }
                                if (editing == null) {
                                    if (section == NativeRuleSection.GLOSSARY) item(key = "glossary-scope") { glossaryContent() }
                                    if (section == NativeRuleSection.TEXT_PRESERVE || section == NativeRuleSection.PRE_REPLACE ||
                                        section == NativeRuleSection.POST_REPLACE) item(key = "sample") { sampleContent() }
                                    item(key = "exchange") { exchangeContent() }
                                }
                            }
                            if (draft == null && state.policyLoadIncomplete) {
                                item(key = "load-error") { Text(stringResource(R.string.native_policy_load_error), color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("native-policy-page"), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "header") { headerContent() }
                        item(key = "sections") { sectionsContent() }
                        item(key = "status") { statusContent() }
                        if (draft != null && section == NativeRuleSection.PROMPTS) {
                            item(key = "prompt") { promptContent() }
                        }
                        if (draft != null && ruleKind != null) {
                            item(key = "toolbar") { toolbarContent() }
                            if (editing != null && draft.policy.rules.none { it.id == editing }) item(key = "new-rule") {
                                NativeTextRuleEditor(ref, requireNotNull(editing), null, ruleKind,
                                    invalid = invalidEntry, close = closeEditor, inlineForm = true, apply = applyEditedRule)
                            }
                            if (section == NativeRuleSection.GLOSSARY) item(key = "glossary-scope") { glossaryContent() }
                            if (section == NativeRuleSection.TEXT_PRESERVE || section == NativeRuleSection.PRE_REPLACE ||
                                section == NativeRuleSection.POST_REPLACE) item(key = "sample") { sampleContent() }
                            if (visibleRules.isEmpty()) item(key = "empty") { Text(stringResource(R.string.native_policy_empty)) }
                            nativeRuleItems(visibleRules, draft.policy.rules, selectedIds, canEdit,
                                state.policyInvalidRuleId, qualityEvidence, null,
                                onSelected, onEnabled, { beginEdit(it.id) }, onDelete, onMove, null,
                                editor = { rule -> if (editing == rule.id) NativeTextRuleEditor(ref, rule.id, rule, rule.kind,
                                    invalid = invalidEntry, close = closeEditor, inlineForm = true, apply = applyEditedRule) })
                            item(key = "exchange") { exchangeContent() }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.nativeRuleItems(
    visibleRules: List<ProjectTextRule>,
    allRules: List<ProjectTextRule>,
    selectedIds: Set<String>,
    writable: Boolean,
    invalidRuleId: String?,
    qualityEvidence: TextQualityIndex?,
    highlightedRuleId: String?,
    onSelected: (ProjectTextRule, Boolean) -> Unit,
    onEnabled: (ProjectTextRule, Boolean) -> Unit,
    onEdit: (ProjectTextRule) -> Unit,
    onDelete: (ProjectTextRule) -> Unit,
    onMove: (ProjectTextRule, Int) -> Unit,
    onOpen: ((ProjectTextRule) -> Unit)?,
    editor: (@Composable (ProjectTextRule) -> Unit)? = null,
) {
    itemsIndexed(visibleRules, key = { _, rule -> "rule-" + rule.id }) { visibleIndex, rule ->
        NativeRuleCard(rule, visibleIndex, visibleRules, allRules,
            selected = rule.id in selectedIds,
            writable = writable,
            invalid = rule.id == invalidRuleId,
            missingIn = qualityEvidence?.missingTermCounts?.get(rule.id)?.takeIf { it > 0 },
            hitIn = qualityEvidence?.termHitCounts?.get(rule.id)?.takeIf { it > 0 },
            highlighted = rule.id == highlightedRuleId,
            onSelected = { checked -> onSelected(rule, checked) },
            onEnabled = { enabled -> onEnabled(rule, enabled) },
            onEdit = { onEdit(rule) },
            onDelete = { onDelete(rule) },
            onMove = { other -> onMove(rule, other) },
            onOpen = onOpen?.let { open -> { open(rule) } })
        editor?.invoke(rule)
    }
}

@Composable
private fun NativeRuleDetail(
    rule: ProjectTextRule,
    writable: Boolean,
    invalid: Boolean,
    missingIn: Int?,
    hitIn: Int?,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-rules-selected-rule"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.native_rules_rule_editor), style = MaterialTheme.typography.titleSmall)
                NativeStatusBadge(
                    text = stringResource(if (rule.enabled) R.string.native_rules_enabled else R.string.native_rules_disabled),
                    tone = if (rule.enabled) NativeTone.SUCCESS else NativeTone.NEUTRAL,
                )
            }
            Text(rule.source, style = MaterialTheme.typography.titleMedium)
            if (rule.target.isNotEmpty()) {
                Text(rule.target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (rule.note.isNotBlank()) {
                Text(rule.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (rule.regex) {
                    NativeStatusBadge(stringResource(R.string.native_policy_regex), tone = NativeTone.SECONDARY)
                }
                if (rule.kind == TextRuleKind.TERM) {
                    NativeStatusBadge(
                        stringResource(
                            when (rule.scope) {
                                TextRuleScope.BODY -> R.string.native_rules_scope_body
                                TextRuleScope.NAME -> R.string.native_rules_scope_name
                                TextRuleScope.BOTH -> R.string.native_rules_scope_both
                            },
                        ),
                        tone = NativeTone.PRIMARY,
                    )
                }
            }
            if (hitIn != null) {
                Text(stringResource(R.string.native_rules_evidence_hit, hitIn), style = MaterialTheme.typography.bodySmall)
            }
            if (missingIn != null) {
                Text(
                    text = stringResource(R.string.native_rules_evidence_missing, missingIn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (invalid) {
                Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(
                onClick = onEdit,
                enabled = writable,
                modifier = Modifier.testTag("native-rules-selected-edit"),
            ) {
                Text(stringResource(R.string.native_policy_edit))
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
    highlighted: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val selectLabel = stringResource(R.string.native_rules_select_rule)
    val enabledLabel = stringResource(if (rule.enabled) R.string.native_rules_enabled else R.string.native_rules_disabled)
    val containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    val borderColor = when {
        invalid -> MaterialTheme.colorScheme.error
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable { onOpen.invoke() } else Modifier)
            .then(if (highlighted) Modifier.semantics { this.selected = true } else Modifier)
            .testTag("native-rule-${rule.id}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelected,
                    enabled = writable,
                    modifier = Modifier
                        .semantics { contentDescription = selectLabel }
                        .testTag("native-rule-select-${rule.id}"),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(rule.source, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (rule.target.isNotEmpty()) {
                        Text(
                            text = rule.target,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (rule.note.isNotBlank()) {
                        Text(
                            text = rule.note,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NativeStatusBadge(
                            text = stringResource(if (rule.enabled) R.string.native_rules_enabled else R.string.native_rules_disabled),
                            tone = if (rule.enabled) NativeTone.SUCCESS else NativeTone.NEUTRAL,
                        )
                        if (rule.regex) {
                            NativeStatusBadge(text = stringResource(R.string.native_policy_regex), tone = NativeTone.SECONDARY)
                        }
                        if (rule.kind == TextRuleKind.TERM) {
                            NativeStatusBadge(
                                text = stringResource(
                                    when (rule.scope) {
                                        TextRuleScope.BODY -> R.string.native_rules_scope_body
                                        TextRuleScope.NAME -> R.string.native_rules_scope_name
                                        TextRuleScope.BOTH -> R.string.native_rules_scope_both
                                    },
                                ),
                                tone = NativeTone.PRIMARY,
                            )
                        }
                    }
                    if (hitIn != null) {
                        Text(stringResource(R.string.native_rules_evidence_hit, hitIn), style = MaterialTheme.typography.bodySmall)
                    }
                    if (missingIn != null) {
                        Text(
                            text = stringResource(R.string.native_rules_evidence_missing, missingIn),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (invalid) {
                Text(
                    text = stringResource(R.string.native_policy_invalid),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("native-rule-invalid-${rule.id}"),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val previous = visibleRules.getOrNull(visibleIndex - 1)?.let { neighbor ->
                allRules.indexOfFirst { it.id == neighbor.id }
            } ?: -1
            val next = visibleRules.getOrNull(visibleIndex + 1)?.let { neighbor ->
                allRules.indexOfFirst { it.id == neighbor.id }
            } ?: -1
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = rule.enabled,
                    onCheckedChange = onEnabled,
                    enabled = writable,
                    modifier = Modifier
                        .semantics { contentDescription = enabledLabel }
                        .testTag("native-rule-enabled-${rule.id}"),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { if (previous >= 0) onMove(previous) },
                    enabled = writable && previous >= 0,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("native-rule-up-${rule.id}"),
                ) { Text(stringResource(R.string.native_policy_up)) }
                TextButton(
                    onClick = { if (next >= 0) onMove(next) },
                    enabled = writable && next >= 0,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("native-rule-down-${rule.id}"),
                ) { Text(stringResource(R.string.native_policy_down)) }
                TextButton(
                    onClick = onEdit,
                    enabled = writable,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("native-rule-edit-${rule.id}"),
                ) { Text(stringResource(R.string.native_policy_edit)) }
                TextButton(
                    onClick = onDelete,
                    enabled = writable,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("native-rule-delete-${rule.id}"),
                ) { Text(stringResource(R.string.native_policy_delete)) }
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
    Card(
        modifier = Modifier.fillMaxWidth().testTag("native-rule-sample"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.native_rules_sample_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.native_rules_sample_hint, NATIVE_RULE_SAMPLE_MAX_CHARS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.native_rules_sample_scope),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = sample,
                onValueChange = {
                    if (it.length <= NATIVE_RULE_SAMPLE_MAX_CHARS) { sample = it; preview = null; invalid = false }
                },
                label = { Text(stringResource(R.string.native_rules_sample)) },
                enabled = enabled,
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().testTag("native-rule-sample-text"),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.native_rules_chars, sample.length, NATIVE_RULE_SAMPLE_MAX_CHARS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
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
                        },
                        enabled = enabled && !testing && sample.isNotEmpty() && sectionRules.isNotEmpty(),
                        modifier = Modifier.testTag("native-rule-sample-run"),
                    ) {
                        Text(stringResource(R.string.native_rules_sample_run))
                    }
                    TextButton(
                        onClick = { sample = ""; preview = null; invalid = false },
                        enabled = enabled && !testing,
                        modifier = Modifier.testTag("native-rule-sample-clear"),
                    ) {
                        Text(stringResource(R.string.native_rules_sample_clear))
                    }
                }
            }
            if (sectionRules.isEmpty()) {
                Text(stringResource(R.string.native_rules_sample_empty), style = MaterialTheme.typography.bodySmall)
            } else if (enabledRules == 0) {
                Text(stringResource(R.string.native_rules_sample_no_enabled), style = MaterialTheme.typography.bodySmall)
            }
            if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
            preview?.let { outcome ->
                if (outcome.matches.isEmpty() && outcome.errors.isEmpty() && !outcome.failed) {
                    Text(stringResource(R.string.native_rules_sample_no_matches), style = MaterialTheme.typography.bodySmall)
                }
                outcome.matches.forEach { match ->
                    val rule = byId[match.ruleId]
                    Text(stringResource(R.string.native_rules_sample_matches, match.count), style = MaterialTheme.typography.titleSmall)
                    Text(boundRuleText(rule?.source.orEmpty(), 80).text, style = MaterialTheme.typography.bodySmall)
                    if (!match.enabled) Text(stringResource(R.string.native_rules_disabled), style = MaterialTheme.typography.bodySmall)
                    match.excerpts.forEach { excerpt ->
                        Text(excerpt, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp))
                    }
                    if (match.truncated) {
                        Text(stringResource(R.string.native_rules_sample_match_truncated), style = MaterialTheme.typography.bodySmall)
                    }
                }
                outcome.errors.forEach { id ->
                    val rule = byId[id]
                    Text(
                        text = stringResource(
                            R.string.native_rules_sample_error,
                            boundRuleText(rule?.source.orEmpty().takeIf { it.isNotEmpty() } ?: id, 80).text,
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (outcome.failed) {
                    Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
                }
                outcome.result?.let { result ->
                    Text(stringResource(R.string.native_rules_sample_result), style = MaterialTheme.typography.titleSmall)
                    if (result.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.native_rules_sample_result_empty),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("native-rule-sample-result"),
                        )
                    } else {
                        Text(
                            text = result.text,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.testTag("native-rule-sample-result"),
                        )
                    }
                    if (result.truncated) {
                        Text(stringResource(R.string.native_rules_sample_result_truncated), style = MaterialTheme.typography.bodySmall)
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
private fun NativeTextRuleEditor(
    ref: LocalProjectRef,
    id: String,
    existing: ProjectTextRule?,
    kind: TextRuleKind,
    invalid: Boolean,
    close: () -> Unit,
    inlineForm: Boolean = false,
    apply: (ProjectTextRule) -> Unit,
) {
    var source by rememberSaveable(ref, id) { mutableStateOf(existing?.source.orEmpty()) }
    var target by rememberSaveable(ref, id) { mutableStateOf(existing?.target.orEmpty()) }
    var note by rememberSaveable(ref, id) { mutableStateOf(existing?.note.orEmpty()) }
    var regex by rememberSaveable(ref, id) { mutableStateOf(existing?.regex ?: (kind == TextRuleKind.PROTECT)) }
    var sensitive by rememberSaveable(ref, id) { mutableStateOf(existing?.caseSensitive ?: true) }
    var enabled by rememberSaveable(ref, id) { mutableStateOf(existing?.enabled ?: true) }
    var scopeName by rememberSaveable(ref, id) { mutableStateOf((existing?.scope ?: TextRuleScope.BODY).name) }
    val ruleScope = runCatching { TextRuleScope.valueOf(scopeName) }.getOrDefault(TextRuleScope.BODY)
    val rule = remember(source, target, note, regex, sensitive, enabled, ruleScope) {
        runCatching {
            ProjectTextRule(id, existing?.kind ?: kind, source, target, note, enabled, regex, sensitive, ruleScope)
        }.getOrNull()
    }
    var discard by rememberSaveable(ref, id) { mutableStateOf(false) }
    val dirty = source != existing?.source.orEmpty() || target != existing?.target.orEmpty() ||
        note != existing?.note.orEmpty() || regex != (existing?.regex ?: (kind == TextRuleKind.PROTECT)) ||
        sensitive != (existing?.caseSensitive ?: true) || enabled != (existing?.enabled ?: true) ||
        ruleScope != (existing?.scope ?: TextRuleScope.BODY)
    val requestClose: () -> Unit = { if (dirty) discard = true else close() }
    BackHandler(enabled = inlineForm) { requestClose() }
    val title = stringResource(R.string.native_rules_rule_editor)
    val fields: @Composable ColumnScope.() -> Unit = {
        if (invalid) Text(stringResource(R.string.native_policy_invalid), color = MaterialTheme.colorScheme.error)
        Text(stringResource((existing?.kind ?: kind).section().title()), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = source,
            onValueChange = { if (it.length <= 4096) source = it },
            label = { Text(stringResource(R.string.native_policy_source)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().testTag("native-policy-source"),
        )
        NativeExpandedTextEditor(listOf(ref, id, "source"), stringResource(R.string.native_policy_source), source,
            { if (it.length <= 4096) source = it })
        if (kind != TextRuleKind.PROTECT) {
            OutlinedTextField(
                value = target,
                onValueChange = { if (it.length <= 4096) target = it },
                label = { Text(stringResource(R.string.native_policy_target)) },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("native-policy-target"),
            )
        }
        if (kind != TextRuleKind.PROTECT) NativeExpandedTextEditor(listOf(ref, id, "target"),
            stringResource(R.string.native_policy_target), target, { if (it.length <= 4096) target = it })
        OutlinedTextField(
            value = note,
            onValueChange = { if (it.length <= 2048) note = it },
            label = { Text(stringResource(R.string.native_policy_note)) },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().testTag("native-policy-note"),
        )
        if (kind == TextRuleKind.TERM) {
            Text(stringResource(R.string.native_rules_scope), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (candidate in TextRuleScope.entries) {
                    FilterChip(
                        selected = candidate == ruleScope,
                        onClick = { scopeName = candidate.name },
                        label = {
                            Text(
                                stringResource(
                                    when (candidate) {
                                        TextRuleScope.BODY -> R.string.native_rules_scope_body
                                        TextRuleScope.NAME -> R.string.native_rules_scope_name
                                        TextRuleScope.BOTH -> R.string.native_rules_scope_both
                                    },
                                ),
                            )
                        },
                        modifier = Modifier.testTag("native-rule-scope-${candidate.name}"),
                    )
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
        if (kind == TextRuleKind.PRE_REPLACE || kind == TextRuleKind.POST_REPLACE) {
            Text(stringResource(R.string.native_rules_empty_target), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(sensitive, { sensitive = it }, modifier = Modifier.testTag("native-policy-case"))
            Text(stringResource(R.string.native_policy_case))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(enabled, { enabled = it }, modifier = Modifier.testTag("native-policy-enabled"))
            Text(stringResource(R.string.native_rules_enabled))
        }
    }
    if (inlineForm) {
        Card(
            modifier = Modifier.fillMaxWidth().testTag("native-rules-inline-editor"),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                fields()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = requestClose) { Text(stringResource(R.string.native_cancel)) }
                    TextButton(
                        onClick = { rule?.let(apply) },
                        enabled = rule != null,
                        modifier = Modifier.testTag("native-policy-apply"),
                    ) {
                        Text(stringResource(R.string.native_policy_apply))
                    }
                }
            }
        }
    } else {
        NativePolicyForm(requestClose, title, "native-policy-rule-dialog", rule != null, { rule?.let(apply) }) {
            fields()
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.native_discard_title)) },
        text = { Text(stringResource(R.string.native_discard_body)) },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_cancel)) } })

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
internal fun NativePolicyForm(
    close: () -> Unit,
    title: String,
    tag: String,
    enabled: Boolean,
    apply: () -> Unit,
    applyLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() })
            Surface(
                modifier = Modifier.padding(16.dp).widthIn(max = 580.dp).fillMaxWidth().heightIn(max = 600.dp).testTag(tag),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = content,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
