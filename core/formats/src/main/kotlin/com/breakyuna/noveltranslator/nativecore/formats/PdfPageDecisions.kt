package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Frozen per-page decision for a PDF asset. A page that is not present in the unit list keeps its
 * original page; a page with an explicit [KEEP] decision does exactly the same, while [OMIT] drops
 * it and [TRANSLATE] replaces it with a re-laid-out page.
 */
internal enum class PdfPageDecision(val wire: String) {
    KEEP("keep"),
    OMIT("omit"),
    TRANSLATE("translate");

    companion object {
        fun parse(value: String): PdfPageDecision? = entries.firstOrNull { it.wire == value }
    }
}

/** One validated page binding decoded from a unit's `extraField`. */
internal class PdfPageBinding(
    val page: Int,
    val decision: PdfPageDecision,
    val digest: String,
)

/**
 * Codec for the PDF `extraField`. The wire object is deliberately small and stable:
 *
 * `{"v":1,"page":3,"decision":"translate","digest":"<sha256 of the extracted page text>"}`
 *
 * The digest is provenance for the extracted source; writeback binds by page number and never
 * re-extracts the original, because the frozen asset remains the structural authority.
 */
internal object PdfPageCodec {
    private const val TAG_PREFIX = "page:"
    private const val DIGEST_CHARS = 64
    private const val HEX = "0123456789abcdef"
    private val jsonLimits = JsonInputLimits(PdfFormatBounds.MAX_UNIT_CHARS, 8)

    fun tag(page: Int): String = TAG_PREFIX + page

    /** Text pages default to translate; image-only or empty pages stay untouched by default. */
    fun defaultDecision(source: String): PdfPageDecision =
        if (source.isBlank()) PdfPageDecision.KEEP else PdfPageDecision.TRANSLATE

    fun encode(page: Int, decision: PdfPageDecision, digest: String): String {
        require(page >= 1)
        require(digest.length == DIGEST_CHARS && digest.all { it in HEX })
        return buildJsonObject {
            put("v", 1)
            put("page", page)
            put("decision", decision.wire)
            put("digest", digest)
        }.toString()
    }

    fun decode(unit: FormatUnit, pageCount: Int): PdfPageBinding {
        val root = try {
            JsonObjectValue.parse(unit.extraField, jsonLimits).json
        } catch (_: IllegalArgumentException) {
            throw PdfFormatException(PdfFormatException.Reason.BINDING)
        }
        if (root["v"] != JsonPrimitive(1))
            throw PdfFormatException(PdfFormatException.Reason.BINDING)
        val pagePrimitive = root["page"] as? JsonPrimitive
            ?: throw PdfFormatException(PdfFormatException.Reason.BINDING)
        if (pagePrimitive.isString) throw PdfFormatException(PdfFormatException.Reason.BINDING)
        val page = pagePrimitive.intOrNull
            ?: throw PdfFormatException(PdfFormatException.Reason.BINDING)
        if (page < 1 || page > pageCount) throw PdfFormatException(PdfFormatException.Reason.BINDING)
        if (unit.tag != tag(page)) throw PdfFormatException(PdfFormatException.Reason.BINDING, page)

        val decisionPrimitive = root["decision"] as? JsonPrimitive
            ?: throw PdfFormatException(PdfFormatException.Reason.BINDING, page)
        if (!decisionPrimitive.isString) {
            throw PdfFormatException(PdfFormatException.Reason.BINDING, page)
        }
        val decision = decisionPrimitive.contentOrNull
            ?.let(PdfPageDecision::parse)
            ?: throw PdfFormatException(PdfFormatException.Reason.BINDING, page)

        val digestPrimitive = root["digest"] as? JsonPrimitive
            ?: throw PdfFormatException(PdfFormatException.Reason.BINDING, page)
        if (!digestPrimitive.isString) {
            throw PdfFormatException(PdfFormatException.Reason.BINDING, page)
        }
        val digest = digestPrimitive.content
        if (digest.length != DIGEST_CHARS || digest.any { it !in HEX }) {
            throw PdfFormatException(PdfFormatException.Reason.BINDING, page)
        }
        return PdfPageBinding(page, decision, digest)
    }
}

/** One output page decision in original page order. */
internal sealed class PdfPageAction {
    abstract val page: Int

    class Keep(override val page: Int) : PdfPageAction()
    class Omit(override val page: Int) : PdfPageAction()
    class Translate(override val page: Int, val text: String) : PdfPageAction()
}

/**
 * Pure resolution of unit decisions into an output page plan. Pages without a unit are kept, so a
 * partial translation can never silently drop the rest of the document. Translation text is
 * composed here (including the bilingual paragraph block) so the writer only performs layout.
 */
internal object PdfPagePlan {
    fun plan(
        units: List<FormatUnit>,
        pageCount: Int,
        options: FormatWriteOptions,
    ): List<PdfPageAction> {
        if (pageCount <= 0) throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        if (units.size > PdfFormatBounds.MAX_UNITS) {
            throw PdfFormatException(PdfFormatException.Reason.LIMIT)
        }
        val bound = HashMap<Int, BoundUnit>()
        for (unit in units) {
            val binding = PdfPageCodec.decode(unit, pageCount)
            if (bound.put(binding.page, BoundUnit(binding, unit)) != null) {
                throw PdfFormatException(PdfFormatException.Reason.DUPLICATE, binding.page)
            }
        }
        val actions = ArrayList<PdfPageAction>(pageCount)
        for (page in 1..pageCount) {
            val entry = bound[page]
            when (entry?.binding?.decision ?: PdfPageDecision.KEEP) {
                PdfPageDecision.KEEP -> actions.add(PdfPageAction.Keep(page))
                PdfPageDecision.OMIT -> actions.add(PdfPageAction.Omit(page))
                PdfPageDecision.TRANSLATE -> actions.add(PdfPageAction.Translate(page, translationText(entry!!, options)))
            }
        }
        return actions
    }

    /** Code points that the replacement font must cover; whitespace still needs a real glyph. */
    fun requiredCodePoints(actions: List<PdfPageAction>): Set<Int> {
        val result = LinkedHashSet<Int>()
        for (action in actions) {
            if (action !is PdfPageAction.Translate) continue
            var index = 0
            val text = action.text
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                when {
                    codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code -> Unit
                    codePoint < 0x20 || codePoint in 0x7F..0x9F || codePoint == 0xFEFF -> Unit
                    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) -> result.add(' '.code)
                    else -> result.add(codePoint)
                }
                index += Character.charCount(codePoint)
            }
        }
        return result
    }

    private fun translationText(entry: BoundUnit, options: FormatWriteOptions): String {
        val unit = entry.unit
        if (unit.status != TextItemStatus.PROCESSED || unit.translation.isBlank()) {
            throw PdfFormatException(PdfFormatException.Reason.MISSING_TRANSLATION, entry.binding.page)
        }
        val translation = unit.translation
        if (!options.bilingual) return translation
        val source = unit.source
        if (options.deduplicateSame && translation == source) return translation
        if (source.isBlank()) return translation
        // A blank line is a separate paragraph and survives the static layout as its own line block.
        return translation + "\n\n" + source
    }

    private class BoundUnit(val binding: PdfPageBinding, val unit: FormatUnit)
}

/** Export projection only: historical item metadata/fingerprints remain untouched. */
internal fun applyPdfPagePolicy(
    asset: com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary,
    policy: com.breakyuna.noveltranslator.nativecore.domain.PdfAssetPagePolicy?,
    units: List<FormatUnit>,
): List<FormatUnit> {
    if (policy == null) return units
    require(policy.matches(asset))
    return units.map { unit ->
        val binding = PdfPageCodec.decode(unit, policy.decisions.length)
        val mode = policy.mode(binding.page)
        if (mode == null) unit else FormatUnit(unit.row, unit.source, unit.translation, unit.status,
            unit.tag, unit.nameSource, unit.nameTranslation,
            PdfPageCodec.encode(binding.page, PdfPageDecision.valueOf(mode.name), binding.digest), unit.skipInternalFilter)
    }
}
