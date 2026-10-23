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
) {
    suspend fun importAsset(commandId: CommandId, ref: LocalProjectRef, expectedRevision: Long,
        path: RelativePath, bytes: ByteArray, enabled: Boolean = true): AssetMutationReceipt {
        require(expectedRevision >= 0 && bytes.size <= MAX_INPUT_BYTES)
        val format = ProjectAssetFormat.detect(path.value)
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
        val assets = projects.assets(ref)
        val asset = assets.firstOrNull { it.id == assetId } ?: fail(TextProjectException.Reason.UNKNOWN_PROJECT)
        val original = projects.readAsset(ref, assetId)
        val units = readUnits(ref, asset)
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
        destination().use { it.write(output); it.flush() }
    }

    private suspend fun readUnits(ref: LocalProjectRef, asset: ProjectAssetSummary): List<FormatUnit> {
        val revision = items.open(ref).revision
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
        val lines = splitLines(original)
        val byRow = units.filter { it.row in lines.indices }.associateBy { it.row }
        val output = StringBuilder()
        lines.forEachIndexed { index, source ->
            if (index > 0) output.append('\n')
            val unit = byRow[index]
            val target = if (unit != null && unit.status == com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus.PROCESSED)
                unit.translation else source
            when {
                !options.bilingual || (options.deduplicateSame && source == target) -> output.append(target)
                target.isEmpty() -> output.append(source)
                else -> output.append(source).append('\n').append(target)
            }
        }
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
