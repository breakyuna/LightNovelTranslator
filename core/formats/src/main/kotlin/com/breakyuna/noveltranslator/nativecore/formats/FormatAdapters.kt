package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatAdapterRegistry
import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextFormatAdapter
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Bridges the existing decoded subtitle core to the shared format contract. The frozen asset
 * remains the structural authority; offsets bind a unit to exactly one source slot.
 */
class SubtitleFormatAdapter(
    private val type: SubtitleType,
    override val format: ProjectAssetFormat,
) : TextFormatAdapter {
    init {
        require(
            (type == SubtitleType.SRT && format == ProjectAssetFormat.SRT) ||
                (type == SubtitleType.LRC && format == ProjectAssetFormat.LRC) ||
                (type == SubtitleType.ASS && format == ProjectAssetFormat.ASS) ||
                (type == SubtitleType.SSA && format == ProjectAssetFormat.SSA),
        ) { "Subtitle type and asset format must agree" }
    }

    override fun read(text: String, path: RelativePath): FormatDocument {
        val document = SubtitleDocument.parse(type, text)
        val units = document.slots.map { slot ->
            FormatUnit(slot.row, slot.source, "", TextItemStatus.NONE, type.name,
                extraField = buildJsonObject {
                    put("subtitle", buildJsonObject {
                        put("offset", slot.reference.offset)
                        put("source_sha256", slot.reference.sourceSha256)
                    })
                }.toString())
        }
        return FormatDocument(format, units, gameEngine = type.name)
    }

    override fun write(original: String, units: List<FormatUnit>, options: FormatWriteOptions): String {
        val document = SubtitleDocument.parse(type, original)
        val byOffset = document.slots.associateBy { it.reference.offset }
        val replacements = ArrayList<SubtitleReplacement>()
        val seen = HashSet<Int>()
        for (unit in units) {
            if (unit.status != TextItemStatus.PROCESSED) continue
            val offset = subtitleOffset(unit)
            val slot = byOffset[offset] ?: throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING, unit.row)
            if (!seen.add(offset)) throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING, unit.row)
            if (slot.source != unit.source) throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING, unit.row)
            replacements.add(SubtitleReplacement(slot.reference, unit.translation))
        }
        return document.render(replacements, options.bilingual, options.deduplicateSame)
    }

    private fun subtitleOffset(unit: FormatUnit): Int {
        val root = try { com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue.parse(
            unit.extraField,
            com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits(65_536, 8),
        ).json } catch (_: IllegalArgumentException) {
            throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING, unit.row)
        }
        val subtitle = root["subtitle"] as? kotlinx.serialization.json.JsonObject
            ?: throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING, unit.row)
        val offset = (subtitle["offset"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.intOrNull ?: throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING, unit.row)
        if (offset < 0) throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING, unit.row)
        return offset
    }
}

/**
 * Explicit catalog. Nothing is installed by static initialization: the application chooses the
 * set it can actually write, and an unavailable format stays rejected rather than silently
 * falling back to a lossy writer.
 */
object NativeFormatAdapters {
    private val all = listOf(
        MarkdownV2Adapter(),
        VttFormatAdapter(),
        SubtitleFormatAdapter(SubtitleType.SRT, ProjectAssetFormat.SRT),
        SubtitleFormatAdapter(SubtitleType.LRC, ProjectAssetFormat.LRC),
        SubtitleFormatAdapter(SubtitleType.ASS, ProjectAssetFormat.ASS),
        SubtitleFormatAdapter(SubtitleType.SSA, ProjectAssetFormat.SSA),
        KvJsonFormatAdapter(),
        MessageJsonFormatAdapter(),
        TransFormatAdapter(),
        RenpyFormatAdapter(),
        EpubFormatAdapter(),
        XlsxFormatAdapter(),
        RpgMakerMvMzAdapter(),
    )

    fun install(): Set<ProjectAssetFormat> {
        all.filterIsInstance<TextFormatAdapter>().forEach { FormatAdapterRegistry.register(it) }
        all.filterIsInstance<com.breakyuna.noveltranslator.nativecore.domain.BinaryFormatAdapter>()
            .forEach { FormatAdapterRegistry.register(it) }
        return FormatAdapterRegistry.supported()
    }

    /** Formats with a writer in this build; TXT remains owned by the existing text store. */
    fun supported(): Set<ProjectAssetFormat> = all.map { it.format }.toSet() +
        ProjectAssetFormat.TXT
}
