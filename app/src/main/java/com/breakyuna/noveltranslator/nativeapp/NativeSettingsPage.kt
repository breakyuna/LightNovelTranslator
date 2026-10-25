package com.breakyuna.noveltranslator.nativeapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.LaboratorySettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource

/**
 * Routed application settings. BASIC and EXPERT share one page so the four bottom tabs stay
 * unchanged. Project text settings remain in the project settings dialog; this page only
 * explains their scope and never duplicates their storage.
 */
@Composable
internal fun NativeSettingsPage(
    section: NativeSettingsSection,
    language: NativeAppLanguage,
    themeMode: NativeThemeMode,
    automaticContextSummary: Boolean,
    appPersona: String,
    personaSaveFailed: Boolean,
    network: NativeNetworkSettingsState,
    limits: NativeRequestLimitsState,
    laboratory: LaboratorySettings = LaboratorySettings.DEFAULT,
    laboratoryLoaded: Boolean = false,
    laboratorySaving: Boolean = false,
    laboratoryError: Boolean = false,
    back: () -> Unit,
    selectSection: (NativeSettingsSection) -> Unit,
    selectLanguage: (NativeAppLanguage) -> Unit,
    selectTheme: (NativeThemeMode) -> Unit,
    setAutomaticContextSummary: (Boolean) -> Unit,
    saveAppPersona: (String) -> Unit,
    reloadNetwork: () -> Unit,
    saveNetwork: (NativeNetworkSettingsInput) -> Unit,
    reloadLimits: () -> Unit,
    saveLimits: (NativeRequestLimitsInput) -> Unit,
    reloadLaboratory: () -> Unit = {},
    saveLaboratory: (LaboratorySettings) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().testTag("native-settings-page")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, modifier = Modifier.testTag("native-settings-back")) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.native_settings_back))
            }
        }
        Column(
            Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.native_settings_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.native_settings_description), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.native_settings_scope_notice), style = MaterialTheme.typography.bodySmall)
            }
            TabRow(selectedTabIndex = section.ordinal, modifier = Modifier.testTag("native-settings-tabs")) {
                NativeSettingsSection.entries.forEach { candidate ->
                    Tab(
                        selected = candidate == section,
                        onClick = { selectSection(candidate) },
                        text = { Text(stringResource(candidate.label())) },
                        modifier = Modifier.testTag("native-settings-tab-${candidate.name}"),
                    )
                }
            }
            when (section) {
                NativeSettingsSection.BASIC -> BasicSettings(language, themeMode, automaticContextSummary,
                    appPersona, personaSaveFailed, selectLanguage, selectTheme,
                    setAutomaticContextSummary, saveAppPersona)
                NativeSettingsSection.EXPERT -> ExpertSettings(network, limits, reloadNetwork, saveNetwork,
                    reloadLimits, saveLimits)
                NativeSettingsSection.LABORATORY -> LaboratorySettingsSection(laboratory, laboratoryLoaded,
                    laboratorySaving, laboratoryError, reloadLaboratory, saveLaboratory)
            }
            Spacer(Modifier.heightIn(min = 12.dp))
        }
    }
}

@Composable
private fun BasicSettings(
    language: NativeAppLanguage,
    themeMode: NativeThemeMode,
    automaticContextSummary: Boolean,
    appPersona: String,
    personaSaveFailed: Boolean,
    selectLanguage: (NativeAppLanguage) -> Unit,
    selectTheme: (NativeThemeMode) -> Unit,
    setAutomaticContextSummary: (Boolean) -> Unit,
    saveAppPersona: (String) -> Unit,
) {
    SettingsCard(R.string.native_settings_theme, R.string.native_settings_theme_description) {
        NativeThemeMode.entries.forEach { candidate ->
            ChoiceRow(
                selected = candidate == themeMode,
                label = stringResource(candidate.label()),
                onClick = { selectTheme(candidate) },
                tag = "native-settings-theme-${candidate.name}",
            )
        }
    }
    SettingsCard(R.string.native_settings_language, R.string.native_settings_language_description) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NativeAppLanguage.entries.forEach { candidate ->
                ChoiceRow(
                    selected = candidate == language,
                    label = stringResource(candidate.label()),
                    onClick = { if (candidate != language) selectLanguage(candidate) },
                    tag = "native-settings-language-${candidate.name}",
                )
            }
        }
        Text(stringResource(R.string.native_settings_language_restart), style = MaterialTheme.typography.bodySmall)
    }
    SettingsCard(R.string.native_auto_context_title, null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.native_auto_context_title), Modifier.weight(1f))
            Switch(automaticContextSummary, setAutomaticContextSummary,
                modifier = Modifier.testTag("native-settings-auto-context"))
        }
        Text(stringResource(R.string.native_auto_context_description), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.native_settings_auto_context_cost), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    PersonaCard(appPersona, personaSaveFailed, saveAppPersona)
    SettingsCard(R.string.native_settings_project_scope, null) {
        Text(stringResource(R.string.native_settings_project_scope_body), style = MaterialTheme.typography.bodySmall)
    }
    SettingsCard(R.string.native_settings_output, null) {
        Text(stringResource(R.string.native_settings_output_body), style = MaterialTheme.typography.bodySmall)
    }
    SettingsCard(R.string.native_settings_storage, null) {
        Text(stringResource(R.string.native_settings_storage_body), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PersonaCard(saved: String, saveFailed: Boolean, save: (String) -> Unit) {
    var draft by rememberSaveable(saved) { mutableStateOf(saved) }
    val valid = remember(draft) { NativeAppPersona.isValid(draft) }
    SettingsCard(R.string.native_settings_persona_title,
        R.string.native_settings_persona_description) {
        Text(stringResource(R.string.native_settings_persona_notice), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= NativeAppPersona.MAX_CHARS + 1) draft = it },
            label = { Text(stringResource(R.string.native_settings_persona_label)) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("native-settings-persona"),
        )
        Text(
            stringResource(
                R.string.native_settings_persona_counter,
                draft.length,
                NativeAppPersona.MAX_CHARS,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.native_settings_persona_hint), style = MaterialTheme.typography.bodySmall)
        if (!valid) Text(stringResource(R.string.native_settings_persona_invalid),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (saveFailed) Text(stringResource(R.string.native_settings_persona_save_failed),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(
            onClick = { save(draft) },
            enabled = valid,
            modifier = Modifier.testTag("native-settings-persona-save"),
        ) { Text(stringResource(R.string.native_save)) }
    }
}

@Composable
private fun ExpertSettings(
    network: NativeNetworkSettingsState,
    limits: NativeRequestLimitsState,
    reloadNetwork: () -> Unit,
    saveNetwork: (NativeNetworkSettingsInput) -> Unit,
    reloadLimits: () -> Unit,
    saveLimits: (NativeRequestLimitsInput) -> Unit,
    reloadLaboratory: () -> Unit = {},
    saveLaboratory: (LaboratorySettings) -> Unit = {},
) {
    Card(Modifier.fillMaxWidth().testTag("native-settings-fail-closed")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.native_settings_fail_closed_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.native_settings_fail_closed_body), style = MaterialTheme.typography.bodySmall)
        }
    }
    NetworkCard(network, reloadNetwork, saveNetwork)
    RequestLimitsCard(limits, reloadLimits, saveLimits)
}

@Composable
private fun NetworkCard(
    state: NativeNetworkSettingsState,
    reload: () -> Unit,
    save: (NativeNetworkSettingsInput) -> Unit,
) {
    SettingsCard(R.string.native_settings_network_title, R.string.native_settings_network_description) {
        when {
            state.loadFailed -> {
                Text(stringResource(R.string.native_settings_network_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = reload, modifier = Modifier.testTag("native-settings-network-reload")) {
                    Text(stringResource(R.string.native_settings_reload))
                }
            }
            state.loading || !state.loaded -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.native_settings_loading))
            }
            else -> {
                if (state.conflict) Text(stringResource(R.string.native_settings_network_conflict),
                    color = MaterialTheme.colorScheme.error)
                if (state.invalid) Text(stringResource(R.string.native_settings_network_invalid),
                    color = MaterialTheme.colorScheme.error)
                if (state.saveFailed) Text(stringResource(R.string.native_settings_save_failed),
                    color = MaterialTheme.colorScheme.error)
                Text(stringResource(
                    if (state.settings.source == ModelNetworkSettingsSource.SAVED)
                        R.string.native_settings_network_source_saved
                    else R.string.native_settings_network_source_default
                ), style = MaterialTheme.typography.bodySmall)
                state.fingerprint?.let { fingerprint ->
                    Text(stringResource(R.string.native_settings_fingerprint, fingerprint.take(8)),
                        style = MaterialTheme.typography.bodySmall)
                }
                key(state.revision) {
                    NetworkForm(state, reload, save)
                }
            }
        }
    }
}

@Composable
private fun NetworkForm(
    state: NativeNetworkSettingsState,
    reload: () -> Unit,
    save: (NativeNetworkSettingsInput) -> Unit,
) {
    val initial = state.settings
    var connect by rememberSaveable { mutableStateOf(initial.connectTimeoutSeconds.toString()) }
    var read by rememberSaveable { mutableStateOf(initial.readTimeoutSeconds.toString()) }
    var write by rememberSaveable { mutableStateOf(initial.writeTimeoutSeconds.toString()) }
    var call by rememberSaveable { mutableStateOf(initial.callTimeoutSeconds.toString()) }
    var proxyHost by rememberSaveable { mutableStateOf(initial.proxy?.host.orEmpty()) }
    var proxyPort by rememberSaveable { mutableStateOf(initial.proxy?.port?.toString().orEmpty()) }
    var redirects by rememberSaveable { mutableStateOf(initial.followRedirects) }
    val input = NativeNetworkSettingsInput(connect, read, write, call, proxyHost, proxyPort, redirects)
    val valid = remember(input) { runCatching { input.toSettings() }.isSuccess }
    Text(stringResource(R.string.native_settings_timeout_hint), style = MaterialTheme.typography.bodySmall)
    TimeoutField(R.string.native_settings_connect_timeout, connect, { connect = it }, "native-settings-connect-timeout")
    TimeoutField(R.string.native_settings_read_timeout, read, { read = it }, "native-settings-read-timeout")
    TimeoutField(R.string.native_settings_write_timeout, write, { write = it }, "native-settings-write-timeout")
    TimeoutField(R.string.native_settings_call_timeout, call, { call = it }, "native-settings-call-timeout")
    HorizontalDivider()
    Text(stringResource(R.string.native_settings_proxy_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.native_settings_proxy_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = proxyHost,
        onValueChange = { if (it.length <= 253) proxyHost = it },
        label = { Text(stringResource(R.string.native_settings_proxy_host)) },
        singleLine = true,
        enabled = !state.saving,
        modifier = Modifier.fillMaxWidth().testTag("native-settings-proxy-host"),
    )
    OutlinedTextField(
        value = proxyPort,
        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) proxyPort = it },
        label = { Text(stringResource(R.string.native_settings_proxy_port)) },
        singleLine = true,
        enabled = !state.saving,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("native-settings-proxy-port"),
    )
    Text(stringResource(R.string.native_settings_no_credentials), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !state.saving) { redirects = !redirects },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(redirects, { redirects = it }, enabled = !state.saving,
            modifier = Modifier.testTag("native-settings-redirects"))
        Text(stringResource(R.string.native_settings_redirects), Modifier.weight(1f))
    }
    Text(stringResource(R.string.native_settings_redirects_description), style = MaterialTheme.typography.bodySmall)
    if (!valid) Text(stringResource(R.string.native_settings_network_invalid),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { save(input) }, enabled = valid && !state.saving,
            modifier = Modifier.weight(1f).testTag("native-settings-network-save")) {
            Text(stringResource(if (state.saving) R.string.native_settings_saving else R.string.native_save))
        }
        TextButton(onClick = reload, enabled = !state.saving,
            modifier = Modifier.weight(1f).testTag("native-settings-network-reload")) {
            Text(stringResource(R.string.native_settings_reload))
        }
    }
}

@Composable
private fun RequestLimitsCard(
    state: NativeRequestLimitsState,
    reload: () -> Unit,
    save: (NativeRequestLimitsInput) -> Unit,
) {
    SettingsCard(R.string.native_settings_request_title, R.string.native_settings_request_description) {
        when {
            state.loadFailed -> {
                Text(stringResource(R.string.native_settings_request_load_failed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = reload, modifier = Modifier.testTag("native-settings-limits-reload")) {
                    Text(stringResource(R.string.native_settings_reload))
                }
            }
            state.loading || !state.loaded -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.native_settings_loading))
            }
            else -> {
                if (state.conflict) Text(stringResource(R.string.native_settings_request_conflict),
                    color = MaterialTheme.colorScheme.error)
                if (state.invalid) Text(stringResource(R.string.native_settings_request_invalid),
                    color = MaterialTheme.colorScheme.error)
                if (state.saveFailed) Text(stringResource(R.string.native_settings_save_failed),
                    color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.native_settings_request_scope), style = MaterialTheme.typography.bodySmall)
                state.fingerprint?.let { fingerprint ->
                    Text(stringResource(R.string.native_settings_fingerprint, fingerprint.take(8)),
                        style = MaterialTheme.typography.bodySmall)
                }
                key(state.revision) {
                    RequestLimitsForm(state, reload, save)
                }
            }
        }
    }
}

@Composable
private fun RequestLimitsForm(
    state: NativeRequestLimitsState,
    reload: () -> Unit,
    save: (NativeRequestLimitsInput) -> Unit,
) {
    val initial = state.limits
    var concurrency by rememberSaveable { mutableStateOf(initial.concurrency.toString()) }
    var rpm by rememberSaveable { mutableStateOf(initial.requestsPerMinute.toString()) }
    val input = NativeRequestLimitsInput(concurrency, rpm)
    val valid = remember(input) { runCatching { input.toLimits() }.isSuccess }
    Text(stringResource(R.string.native_settings_request_hint), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = concurrency,
        onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) concurrency = it },
        label = { Text(stringResource(R.string.native_settings_concurrency)) },
        singleLine = true,
        enabled = !state.saving,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("native-settings-concurrency"),
    )
    OutlinedTextField(
        value = rpm,
        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) rpm = it },
        label = { Text(stringResource(R.string.native_settings_rpm)) },
        singleLine = true,
        enabled = !state.saving,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("native-settings-rpm"),
    )
    if (!valid) Text(stringResource(R.string.native_settings_request_invalid),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { save(input) }, enabled = valid && !state.saving,
            modifier = Modifier.weight(1f).testTag("native-settings-limits-save")) {
            Text(stringResource(if (state.saving) R.string.native_settings_saving else R.string.native_save))
        }
        TextButton(onClick = reload, enabled = !state.saving,
            modifier = Modifier.weight(1f).testTag("native-settings-limits-reload")) {
            Text(stringResource(R.string.native_settings_reload))
        }
    }
}

@Composable
private fun TimeoutField(label: Int, value: String, update: (String) -> Unit, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) update(it) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun SettingsCard(title: Int, description: Int?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            description?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LaboratorySettingsSection(settings: LaboratorySettings, loaded: Boolean, saving: Boolean,
    failed: Boolean, reload: () -> Unit, save: (LaboratorySettings) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.native_laboratory_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.native_laboratory_description), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.native_laboratory_scope_notice), style = MaterialTheme.typography.bodySmall)
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failed) Text(stringResource(R.string.native_laboratory_error), color = MaterialTheme.colorScheme.error)
        if (!loaded && !saving) {
            Text(stringResource(R.string.native_laboratory_loading), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = reload) { Text(stringResource(R.string.native_refresh)) }
        }
        LaboratorySwitch(stringResource(R.string.native_laboratory_clean_ruby),
            stringResource(R.string.native_laboratory_clean_ruby_description),
            settings.cleanRuby, enabled = loaded && !saving) {
            save(settings.copy(cleanRuby = it))
        }
        LaboratorySwitch(stringResource(R.string.native_laboratory_prompt),
            stringResource(R.string.native_laboratory_prompt_description),
            settings.promptEnhancement, enabled = loaded && !saving) {
            save(settings.copy(promptEnhancement = it))
        }
        LaboratorySwitch(stringResource(R.string.native_laboratory_dedup),
            stringResource(R.string.native_laboratory_dedup_description),
            settings.duplicateSourceDeduplication, enabled = loaded && !saving) {
            save(settings.copy(duplicateSourceDeduplication = it))
        }
        Text(stringResource(R.string.native_laboratory_mtool_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LaboratorySwitch(title: String, description: String, checked: Boolean, enabled: Boolean,
    change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = change)
    }
}

private fun NativeSettingsSection.label(): Int = when (this) {
    NativeSettingsSection.BASIC -> R.string.native_settings_basic
    NativeSettingsSection.EXPERT -> R.string.native_settings_expert
    NativeSettingsSection.LABORATORY -> R.string.native_settings_laboratory
}

private fun NativeThemeMode.label(): Int = when (this) {
    NativeThemeMode.SYSTEM -> R.string.native_theme_system
    NativeThemeMode.LIGHT -> R.string.native_theme_light
    NativeThemeMode.DARK -> R.string.native_theme_dark
}

private fun NativeAppLanguage.label(): Int = when (this) {
    NativeAppLanguage.CHINESE -> R.string.native_language_chinese
    NativeAppLanguage.ENGLISH -> R.string.native_language_english
    NativeAppLanguage.JAPANESE -> R.string.native_language_japanese
    NativeAppLanguage.KOREAN -> R.string.native_language_korean
    NativeAppLanguage.GERMAN -> R.string.native_language_german
}
