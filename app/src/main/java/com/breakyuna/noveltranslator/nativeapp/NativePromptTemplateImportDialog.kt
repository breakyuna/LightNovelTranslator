package com.breakyuna.noveltranslator.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

/** Incoming data and preview live only in this dialog; confirmation still uses a store CAS. */
@Composable
internal fun NativePromptTemplateImportDialog(ref: LocalProjectRef, baseline: PromptTemplateLibrarySnapshot,
    enabled: Boolean, close: () -> Unit, apply: (PromptTemplateImportPreview) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var modeName by rememberSaveable(ref) { mutableStateOf(PromptTemplateImportMode.MERGE.name) }
    var incoming by remember(ref, baseline.fingerprint) { mutableStateOf<PromptTemplateSet?>(null) }
    var preview by remember(ref, baseline.fingerprint) { mutableStateOf<PromptTemplateImportPreview?>(null) }
    var loading by remember(ref) { mutableStateOf(false) }
    var previewing by remember(ref) { mutableStateOf(false) }
    var error by remember(ref) { mutableStateOf(false) }
    var detail by remember(ref, baseline.fingerprint) { mutableStateOf<PromptTemplateImportChange?>(null) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            loading = true; error = false; incoming = null; preview = null
            scope.launch {
                try {
                    incoming = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size().toLong() + count <= PromptTemplateCodec.MAX_CHARS.toLong() * 4)
                                output.write(buffer, 0, count)
                            }
                            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(output.toByteArray())).toString().removePrefix("\uFEFF")
                            PromptTemplateCodec.decode(text)
                        } ?: throw java.io.IOException("Template source unavailable")
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { loading = false }
            }
        }
    }
    LaunchedEffect(incoming, modeName, baseline.fingerprint) {
        preview = null
        val loaded = incoming ?: return@LaunchedEffect
        previewing = true; error = false
        try {
            preview = withContext(Dispatchers.Default) {
                baseline.templates.previewImport(loaded, PromptTemplateImportMode.valueOf(modeName))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { previewing = false }
    }
    val busy = loading || previewing
    NativePolicyForm(close, stringResource(R.string.native_template_import), "native-template-import-dialog",
        enabled && !busy && preview?.clean == true, { preview?.takeIf { it.clean }?.let(apply) }) {
        Text(stringResource(R.string.native_template_import_notice))
        for (mode in PromptTemplateImportMode.entries) TextButton(onClick = {
            if (modeName != mode.name) { preview = null; modeName = mode.name }
        }, enabled = enabled && !busy, modifier = Modifier.testTag("native-template-import-${mode.name}")) {
            RadioButton(modeName == mode.name, onClick = null)
            Text(stringResource(when (mode) {
                PromptTemplateImportMode.MERGE -> R.string.native_template_import_merge
                PromptTemplateImportMode.UPDATE -> R.string.native_template_import_update
                PromptTemplateImportMode.REPLACE -> R.string.native_template_import_replace
            }))
        }
        TextButton(onClick = {
            try { openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            catch (_: Exception) { error = true }
        }, enabled = enabled && !busy) { Text(stringResource(R.string.native_policy_open_rule_file)) }
        if (busy) Text(stringResource(R.string.native_working))
        if (error) Text(stringResource(R.string.native_prompt_template_invalid), color = MaterialTheme.colorScheme.error)
        preview?.let {
            Text(stringResource(R.string.native_template_import_diff, it.added, it.updated, it.unchanged, it.removed, it.conflicts),
                modifier = Modifier.testTag("native-template-import-diff"))
            LazyColumn(Modifier.fillMaxWidth().height(160.dp)) {
                items(it.changes, key = { change -> change.id }) { change ->
                    TextButton(onClick = { detail = change }) {
                        Text((change.after ?: change.before)?.name.orEmpty() + " · " + stringResource(when (change.disposition) {
                            PromptTemplateImportDisposition.ADDED -> R.string.native_template_added
                            PromptTemplateImportDisposition.UPDATED -> R.string.native_template_updated
                            PromptTemplateImportDisposition.UNCHANGED -> R.string.native_template_unchanged
                            PromptTemplateImportDisposition.REMOVED -> R.string.native_template_removed
                            PromptTemplateImportDisposition.CONFLICT -> R.string.native_template_conflict
                        }))
                    }
                }
            }
        }
    }
    detail?.let { change ->
        NativeContentDialog(onDismissRequest = { detail = null },
            title = { Text((change.after ?: change.before)?.name.orEmpty()) },
            text = {
                    Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.native_template_before))
                        change.before?.let {
                            Text(stringResource(R.string.native_template_metadata, it.name, stringResource(if (it.enabled) R.string.native_prompt_template_enabled else R.string.native_prompt_template_disabled), it.order,
                                it.languages.joinToString(", ") { language -> language.value }))
                            Text(it.body)
                            Text(it.description)
                        }
                        Text(stringResource(R.string.native_template_after))
                        change.after?.let {
                            Text(stringResource(R.string.native_template_metadata, it.name, stringResource(if (it.enabled) R.string.native_prompt_template_enabled else R.string.native_prompt_template_disabled), it.order,
                                it.languages.joinToString(", ") { language -> language.value }))
                            Text(it.body)
                            Text(it.description)
                        }
                    }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text(stringResource(R.string.native_close)) } })
    }

}
