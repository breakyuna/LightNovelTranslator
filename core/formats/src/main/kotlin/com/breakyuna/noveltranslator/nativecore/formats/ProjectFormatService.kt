package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.AssetImportPlan
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportUnit
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationReceipt
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetPort
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPort
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectItem
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Connects pure format adapters to the asset store. It performs no format interpretation itself:
 * bytes are frozen, the adapter produces units, and every write still passes the asset store's
 * revision/command receipt. Text is decoded strictly and re-encoded with the original charset/BOM.
 */
class ProjectFormatService(
    private val projects: ProjectAssetPort,
    private val items: TextProjectPort,
    private val pdfImages: PdfPageImageRenderer = PdfPageImageRenderer { bytes, page, expected ->
        PdfPageRenderer().renderPage(bytes, page, expected)
    },
) {
    suspend fun importAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        path: RelativePath, bytes: ByteArray, enabled: Boolean = true): AssetMutationReceipt {
        require(expectedRevision >= 0 && bytes.size <= MAX_INPUT_BYTES)
        val format = resolveImportFormat(path.value, bytes)
        if (format == ProjectAssetFormat.BINARY) fail(TextProjectException.Reason.INVALID_FORMAT)
        val document = when (format) {
            ProjectAssetFormat.TXT -> txtDocument(bytes)
            else -> {
                val textAdapter = com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry.text(format)
                if (textAdapter != null) {
                    val decoded = FormatTextCodec.decode(bytes)
                    textAdapter.read(decoded.text, path)
                } else {
                    val binaryAdapter = com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry.binary(format)
                        ?: fail(TextProjectException.Reason.INVALID_FORMAT)
                    binaryAdapter.read(bytes, path)
                }
            }
        }
        val units = document.units.mapIndexed { index, unit ->
            AssetImportUnit(index, unit.row, unit.source, unit.translation, unit.status, unit.tag,
                unit.nameSource, unit.nameTranslation, unit.extraField, unit.skipInternalFilter)
        }
        return projects.importAsset(AssetImportPlan(commandId, ref, expectedRevision, path, format,
            bytes, units, enabled, document.metadata))
    }

    suspend fun exportAsset(ref: LocalProjectRef, assetId: Long, options: FormatWriteOptions,
        destination: () -> OutputStream) {
        val snapshot = items.open(ref)
        val assets = snapshot.assets
        val asset = assets.firstOrNull { it.id == assetId } ?: fail(TextProjectException.Reason.UNKNOWN_PROJECT)
        val original = projects.readAsset(ref, assetId)
        val policy = (snapshot.settings ?: projects.projectSettings(ref)).pdfPages.firstOrNull { it.assetId == asset.id }
        val units = applyPdfPagePolicy(asset, policy, readUnits(ref, asset, snapshot.revision))
        val output = when (asset.format) {
            ProjectAssetFormat.TXT -> {
                val decoded = FormatTextCodec.decode(original)
                decoded.encode(txtWrite(decoded.text, units, options))
            }
            else -> {
                val textAdapter = com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry.text(asset.format)
                if (textAdapter != null) {
                    val decoded = FormatTextCodec.decode(original)
                    textAdapter.write(decoded.text, units, options).let { decoded.encode(it) }
                } else {
                    val binaryAdapter = com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry.binary(asset.format)
                        ?: fail(TextProjectException.Reason.INVALID_FORMAT)
                    binaryAdapter.write(original, units, options)
                }
            }
        }
        if (output.size > MAX_OUTPUT_BYTES) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        if (items.open(ref).revision != snapshot.revision) fail(TextProjectException.Reason.STALE_PAGE)
        destination().use { it.write(output); it.flush() }
    }

    /**
     * Reads the fixed PDF asset, checks the caller's project revision, asset identity and page
     * count, then renders exactly one original page. This is a read-only preview: project settings,
     * page decisions, item fingerprints, history and the original asset never change.
     *
     * The asset bytes are re-hashed before rendering, and the project revision/asset summary is
     * checked again afterwards. A late result from a superseded revision is therefore rejected
     * instead of being returned to a dialog that now shows a different project.
     *
     * This is blocking PDFBox work. The app's ViewModel calls it from `Dispatchers.IO`; callers
     * must not invoke it on the main thread.
     */
    suspend fun renderPdfPage(
        ref: LocalProjectRef,
        revision: Long,
        asset: ProjectAssetSummary,
        page: Int,
    ): ByteArray {
        if (revision < 0) fail(TextProjectException.Reason.STALE_PAGE)
        if (asset.format != ProjectAssetFormat.PDF) fail(TextProjectException.Reason.INVALID_FORMAT)
        if (asset.itemCount !in 1..PdfFormatBounds.MAX_PAGES || page !in 1..asset.itemCount) {
            fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        }
        val snapshot = items.open(ref)
        if (snapshot.revision != revision || snapshot.assets.firstOrNull { it.id == asset.id } != asset) {
            fail(TextProjectException.Reason.STALE_PAGE)
        }
        val bytes = projects.readAsset(ref, asset.id)
        if (bytes.size.toLong() != asset.originalSize ||
            TextProjectFingerprint.bytes(bytes) != asset.sha256 ||
            bytes.size !in 1..PdfFormatBounds.MAX_INPUT_BYTES) {
            fail(TextProjectException.Reason.CORRUPT_PROJECT)
        }
        val png = try {
            pdfImages.renderPage(bytes, page, asset.itemCount)
        } catch (failure: PdfFormatException) {
            throw PdfFormatException(failure.reason, page)
        }
        val after = items.open(ref)
        if (after.revision != revision || after.assets.firstOrNull { it.id == asset.id } != asset) {
            fail(TextProjectException.Reason.STALE_PAGE)
        }
        if (png.size !in 1..PdfPageRenderBounds.MAX_PNG_BYTES) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        return png
    }

    private suspend fun readUnits(ref: LocalProjectRef, asset: ProjectAssetSummary, revision: Long): List<FormatUnit> {
        var after = 0L
        val result = ArrayList<FormatUnit>()
        while (true) {
            val page = items.items(ref, revision, after, PAGE)
            val selected = page.items.filter { it.assetId == asset.id }
            for (item in selected) {
                if (result.size >= 100_000) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
                result.add(formatUnit(item))
            }
            if (page.items.isEmpty() || page.items.size < PAGE) break
            val next = page.items.last().id.value
            if (next <= after) fail(TextProjectException.Reason.CORRUPT_PROJECT)
            after = next
        }
        return result
    }

    private fun formatUnit(item: TextProjectItem) = FormatUnit(item.row, item.source, item.translation,
        item.status, item.metadata.tag, item.metadata.nameSource, item.metadata.nameTranslation,
        item.metadata.extraField, item.metadata.skipInternalFilter)

    private fun txtDocument(bytes: ByteArray): com.breakyuna.noveltranslator.nativecore.domain.FormatDocument {
        val decoded = FormatTextCodec.decode(bytes)
        val lines = splitLines(decoded.text)
        if (lines.size > 100_000) fail(TextProjectException.Reason.LIMIT_EXCEEDED)
        val units = lines.mapIndexed { index, line ->
            com.breakyuna.noveltranslator.nativecore.domain.FormatUnit(index, line)
        }
        return com.breakyuna.noveltranslator.nativecore.domain.FormatDocument(ProjectAssetFormat.TXT, units)
    }

    private fun txtWrite(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String {
        val newline = when {
            original.contains("\r\n") -> "\r\n"
            original.contains('\r') -> "\r"
            else -> "\n"
        }
        val endsWithNewline = original.endsWith("\r\n") || original.endsWith("\r") || original.endsWith("\n")
        val lines = splitLines(original)
        val byRow = units.filter { it.row in lines.indices }.associateBy { it.row }
        val output = StringBuilder()
        lines.forEachIndexed { index, source ->
            if (index > 0) output.append(newline)
            val unit = byRow[index]
            val target = if (unit != null && unit.status == com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus.PROCESSED)
                unit.translation else source
            when {
                !options.bilingual || (options.deduplicateSame && source == target) -> output.append(target)
                target.isEmpty() -> output.append(source)
                else -> output.append(source).append(newline).append(target)
            }
        }
        if (endsWithNewline) output.append(newline)
        return output.toString()
    }

    private fun splitLines(text: String): List<String> {
        val result = ArrayList<String>()
        var start = 0
        var index = 0
        while (index < text.length) {
            if (text[index] == '\n' || text[index] == '\r') {
                result.add(text.substring(start, index))
                if (text[index] == '\r' && text.getOrNull(index + 1) == '\n') index++
                start = index + 1
            }
            index++
        }
        if (start < text.length) result.add(text.substring(start))
        return result
    }

    private fun fail(reason: TextProjectException.Reason): Nothing = throw TextProjectException(reason)

    companion object {
        const val MAX_INPUT_BYTES = 64 * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 128 * 1024 * 1024
        private const val PAGE = 100
    }
}

/**
 * Import-time format resolution. Extension detection remains authoritative, but a generic ZIP
 * whose content is an RPG Maker MV/MZ project (`data/System.json`, optionally under `www/`) is
 * promoted to the game adapter so a picked `game.zip` is reachable without renaming it.
 * Unrelated ZIP/BINARY content still fails the import instead of being guessed.
 */
internal fun resolveImportFormat(path: String, bytes: ByteArray): ProjectAssetFormat {
    val detected = ProjectAssetFormat.detect(path)
    if (detected != ProjectAssetFormat.BINARY) return detected
    if (RpgMakerArchiveSniffer.matches(bytes)) return ProjectAssetFormat.RPGMAKER_MV_MZ
    return ProjectAssetFormat.BINARY
}
