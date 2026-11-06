package com.breakyuna.noveltranslator.nativeapp

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.formats.PdfFormatException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val PdfThumbnailImageTag = "pdf-preview-thumbnail"
internal const val PdfThumbnailLoadingTag = "pdf-preview-thumbnail-loading"
internal const val PdfThumbnailFailedTag = "pdf-preview-thumbnail-failed"
internal fun pdfPageRowTag(page: Int): String = "pdf-page-row-$page"
internal fun pdfPageEditTag(page: Int): String = "pdf-page-edit-$page"

@Composable
internal fun NativePdfPagesDialog(state: NativeProjectUiState, asset: ProjectAssetSummary,
    close: () -> Unit, save: (NativeProjectSettingsChange) -> Unit,
    loadPage: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> TextProjectItem)? = null,
    loadThumbnail: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> ByteArray)? = null,
    editPage: ((LocalProjectRef, Long, ProjectAssetSummary, Int) -> Unit)? = null) {
    val project = remember(state.project?.ref, asset.id) { requireNotNull(state.project) }
    val initial = remember(project.ref, asset.id) { project.settings ?: ProjectSettings() }
    val valid = asset.format == ProjectAssetFormat.PDF && asset.itemCount in 1..5_000
    val original = initial.pdfPages.firstOrNull { it.assetId == asset.id }
    val starting = original?.takeIf { it.matches(asset) }?.decisions ?: "-".repeat(asset.itemCount.coerceIn(0, 5_000))
    var decisions by rememberSaveable(project.ref, asset.id, asset.sha256) { mutableStateOf(starting) }
    var from by rememberSaveable(project.ref, asset.id) { mutableStateOf("1") }
    var through by rememberSaveable(project.ref, asset.id) { mutableStateOf(asset.itemCount.toString()) }
    var bulk by rememberSaveable(project.ref, asset.id) { mutableStateOf('-') }
    val range = from.toIntOrNull()?.let { first -> through.toIntOrNull()?.let { last ->
        (first..last).takeIf { first >= 1 && last >= first && last <= decisions.length }
    } }
    val settings = remember(initial, decisions) {
        val other = initial.pdfPages.filter { it.assetId != asset.id }
        runCatching { initial.withPdfPages(if (decisions.all { it == '-' }) other else
            other + PdfAssetPagePolicy(asset.id, asset.sha256, decisions)) }.getOrNull()
    }
    val command = rememberSaveable(project.ref, asset.id, decisions) { UUID.randomUUID().toString() }
    var submitted by remember(project.ref, asset.id) { mutableStateOf(false) }
    var previewPage by remember(project.ref, asset.id, project.revision) { mutableStateOf<Int?>(null) }
    val current = state.project
    LaunchedEffect(state.busy, current?.revision, submitted) {
        if (submitted && !state.busy && current != null && current.ref == project.ref && current.revision > project.revision &&
            current.settings?.fingerprint() == settings?.fingerprint()) close()
    }
    val eligible = valid && !state.busy && current != null && current.ref == project.ref && current.revision == project.revision &&
        state.editor == null && state.manualBatch == null && !state.selectingItems &&
        !state.policyLoadIncomplete && !state.runLoadIncomplete && state.policyDraft?.dirty != true
    var discard by rememberSaveable(project.ref, asset.id) { mutableStateOf(false) }
    val requestClose: () -> Unit = {
        if (!state.busy) {
            if (decisions != starting) discard = true else close()
        }
    }
    if (discard) NativeContentDialog(onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.native_discard_title)) },
        text = { Text(stringResource(R.string.native_discard_body)) },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.native_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.native_keep_editing)) } })
    NativePanelHost(inline = true, onDismissRequest = {
        if (previewPage != null) previewPage = null else requestClose()
    }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Keep the measured panel alive when returning from a preview to the page list.
            Surface(Modifier.testTag(if (previewPage == null) "pdf-pages-list" else "pdf-page-preview")
                .widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 500.dp),
                shape = MaterialTheme.shapes.extraLarge) {
                previewPage?.let { page ->
                    NativePdfPagePreview(project.ref, project.revision, asset, page,
                        loadPage, loadThumbnail, { previewPage = null },
                        edit = if (editPage != null && !state.busy && decisions == starting && current?.revision == project.revision &&
                            state.editor == null && state.manualBatch == null && !state.selectingItems &&
                            !state.policyLoadIncomplete && !state.runLoadIncomplete && state.policyDraft?.dirty != true)
                            ({ previewPage = null; close(); editPage(project.ref, project.revision, asset, page) }) else null,
                        previous = if (page > 1) ({ previewPage = page - 1 }) else null,
                        next = if (page < decisions.length) ({ previewPage = page + 1 }) else null)
                    return@Surface
                }
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.native_pdf_pages), style = MaterialTheme.typography.headlineSmall)
                    Text(asset.path.value, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(from, { if (it.length <= 4 && it.all(Char::isDigit)) from = it },
                            label = { Text(stringResource(R.string.native_pdf_from)) }, singleLine = true,
                            enabled = eligible, modifier = Modifier.weight(1f))
                        OutlinedTextField(through, { if (it.length <= 4 && it.all(Char::isDigit)) through = it },
                            label = { Text(stringResource(R.string.native_pdf_through)) }, singleLine = true,
                            enabled = eligible, modifier = Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PdfModeMenu(bulk, eligible, { bulk = it }, Modifier.weight(1f))
                        TextButton(onClick = { range?.let { selected ->
                            decisions = decisions.toCharArray().also { chars -> selected.forEach { chars[it - 1] = bulk } }.concatToString()
                        } }, enabled = eligible && range != null) { Text(stringResource(R.string.native_pdf_apply_range)) }
                    }
                    if (editPage != null && decisions != starting) Text(stringResource(R.string.native_pdf_edit_settings_first),
                        style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f)) {
                        items((1..decisions.length).toList(), key = { it }) { page ->
                            Column(Modifier.fillMaxWidth().testTag(pdfPageRowTag(page))) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.native_pdf_page_number, page), Modifier.weight(1f))
                                    PdfModeMenu(decisions[page - 1], eligible, { code ->
                                        decisions = decisions.toCharArray().also { it[page - 1] = code }.concatToString()
                                    })
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (loadPage != null || loadThumbnail != null) TextButton(onClick = { previewPage = page },
                                        enabled = eligible) { Text(stringResource(R.string.native_pdf_preview)) }
                                    if (editPage != null) TextButton(onClick = {
                                        close(); editPage(project.ref, project.revision, asset, page)
                                    }, enabled = eligible && decisions == starting, modifier = Modifier.testTag(pdfPageEditTag(page))) {
                                        Text(stringResource(R.string.native_pdf_edit_page))
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(requestClose, enabled = !state.busy) { Text(stringResource(R.string.native_cancel)) }
                        TextButton(onClick = { settings?.let {
                            submitted = true
                            save(NativeProjectSettingsChange(project.ref, project.revision, CommandId(command), it))
                        } }, enabled = eligible && settings != null && settings.fingerprint() != initial.fingerprint()) {
                            Text(stringResource(R.string.native_asset_confirm))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativePdfPagePreview(ref: LocalProjectRef, revision: Long, asset: ProjectAssetSummary, page: Int,
    loadPage: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> TextProjectItem)?,
    loadThumbnail: (suspend (LocalProjectRef, Long, ProjectAssetSummary, Int) -> ByteArray)?,
    close: () -> Unit, edit: (() -> Unit)? = null, previous: (() -> Unit)? = null, next: (() -> Unit)? = null) {
    var item by remember(ref, revision, asset.id, asset.sha256, page) { mutableStateOf<TextProjectItem?>(null) }
    var failed by remember(ref, revision, asset.id, asset.sha256, page) { mutableStateOf(false) }
    var thumbnail by remember(ref, revision, asset.id, asset.sha256, page) { mutableStateOf<ImageBitmap?>(null) }
    var thumbnailError by remember(ref, revision, asset.id, asset.sha256, page) { mutableStateOf<Int?>(null) }
    val loadText = loadPage
    val loadImage = loadThumbnail
    LaunchedEffect(ref, revision, asset.id, asset.sha256, page) {
        val loader = loadText ?: return@LaunchedEffect
        try { item = loader(ref, revision, asset, page) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    LaunchedEffect(ref, revision, asset.id, asset.sha256, page) {
        val loader = loadImage ?: return@LaunchedEffect
        try {
            val bytes = loader(ref, revision, asset, page)
            val decoded = withContext(Dispatchers.Default) { decodePdfThumbnail(bytes) }
            if (decoded == null) thumbnailError = R.string.native_pdf_thumbnail_failed else thumbnail = decoded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PdfFormatException) {
            thumbnailError = pdfFormatErrorLabel(error.reason)
        } catch (_: Exception) {
            thumbnailError = R.string.native_pdf_thumbnail_failed
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.native_pdf_page_number, page), style = MaterialTheme.typography.titleLarge)
        // The thumbnail and selectable text share one scroller; actions keep their height.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loadThumbnail != null) {
                val renderError = thumbnailError
                when {
                    thumbnail != null -> Image(bitmap = requireNotNull(thumbnail),
                        contentDescription = stringResource(R.string.native_pdf_thumbnail_description, page),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).testTag(PdfThumbnailImageTag))
                    renderError != null -> Text(stringResource(renderError),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(PdfThumbnailFailedTag))
                    else -> Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag(PdfThumbnailLoadingTag)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.native_pdf_thumbnail_loading))
                    }
                }
            }
            if (failed) Text(stringResource(R.string.native_pdf_preview_failed))
            else if (item == null && loadPage != null) CircularProgressIndicator()
            item?.let { loaded ->
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(loaded.status.label()), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.native_source), style = MaterialTheme.typography.titleMedium)
                        Text(loaded.source.ifEmpty { stringResource(R.string.native_pdf_no_text) })
                        Text(stringResource(R.string.native_translation), style = MaterialTheme.typography.titleMedium)
                        Text(loaded.translation.ifEmpty { stringResource(if (loaded.status == TextItemStatus.PROCESSED)
                            R.string.native_empty_result else R.string.native_empty_translation) })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { previous?.invoke() }, enabled = previous != null) { Text(stringResource(R.string.native_previous)) }
            TextButton(onClick = { next?.invoke() }, enabled = next != null) { Text(stringResource(R.string.native_next)) }
        }
        if (edit != null) TextButton(onClick = edit, enabled = item != null && !failed) {
            Text(stringResource(R.string.native_pdf_edit_page))
        }
        TextButton(onClick = close, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.native_close)) }
    }
}

/** Decodes the bounded renderer PNG away from the main thread; the bitmap stays in Compose state. */
private suspend fun decodePdfThumbnail(bytes: ByteArray): ImageBitmap? = withContext(Dispatchers.Default) {
    if (bytes.isEmpty()) return@withContext null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    } catch (_: RuntimeException) {
        return@withContext null
    }
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width !in 1..1_200 || height !in 1..1_200) return@withContext null
    var sample = 1
    while (width / sample > 720 || height / sample > 720) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: RuntimeException) {
        null
    } ?: return@withContext null
    try {
        bitmap.asImageBitmap()
    } catch (_: RuntimeException) {
        bitmap.recycle()
        null
    }
}

@Composable
private fun PdfModeMenu(code: Char, enabled: Boolean, select: (Char) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf('-' to R.string.native_pdf_default, 'k' to R.string.native_pdf_keep,
        'o' to R.string.native_pdf_omit, 't' to R.string.native_pdf_translate)
    Box(modifier) {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text(stringResource(modes.first { it.first == code }.second))
        }
        DropdownMenu(expanded && enabled, { expanded = false }) {
            modes.forEach { (value, label) -> DropdownMenuItem(text = { Text(stringResource(label)) },
                onClick = { expanded = false; select(value) }) }
        }
    }
}
