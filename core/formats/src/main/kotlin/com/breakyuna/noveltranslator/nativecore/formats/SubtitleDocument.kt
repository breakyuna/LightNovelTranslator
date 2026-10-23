package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import java.util.Collections

enum class SubtitleType { SRT, LRC, ASS, SSA }

class InvalidSubtitle(val reason: Reason, val row: Int? = null) : IllegalArgumentException("Invalid subtitle: $reason") {
    enum class Reason { STRUCTURE, TEXT, LIMIT, BINDING }
}

/** Stable reference to this decoded asset and source slot, not a mutable list index. */
data class SubtitleSlotRef(val documentSha256: String, val offset: Int, val sourceSha256: String)
class SubtitleTextSlot(val reference: SubtitleSlotRef, val row: Int, val source: String)
class SubtitleReplacement(val reference: SubtitleSlotRef, val text: String)

internal data class SubtitleLine(val start: Int, val row: Int, val text: String, val ending: String)
internal class SubtitleUnit(val start: Int, val end: Int, val row: Int, val source: String,
    val render: (String, String, Int) -> String,
    val renumberOriginal: ((Int) -> String)? = null)

/**
 * Decoded-text format core. No file IO, project mutation, Android objects or model calls.
 * The frozen asset determines all control fields. Unknown/stale/duplicate references fail
 * before any output is returned. Untouched units and all non-text regions remain literal.
 */
class SubtitleDocument private constructor(
    val type: SubtitleType, private val original: String, private val units: List<SubtitleUnit>,
) {
    val sha256: String = TextProjectFingerprint.bytes(encoded(original))
    private val bindings = units.filter { it.source.isNotBlank() }.associateBy { it.start }
    val slots: List<SubtitleTextSlot> = Collections.unmodifiableList(bindings.values.map {
        SubtitleTextSlot(reference(it), it.row, it.source)
    })
    private val newline = lines(original).firstOrNull { it.ending.isNotEmpty() }?.ending ?: "\n"

    private fun reference(unit: SubtitleUnit) = SubtitleSlotRef(sha256, unit.start,
        TextProjectFingerprint.bytes(encoded(unit.source)))

    fun render(replacements: List<SubtitleReplacement>, bilingual: Boolean = false, deduplicate: Boolean = true): String {
        if (replacements.size > slots.size) throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING)
        val targets = mutableMapOf<Int, String>()
        // Validate the whole set before building output. Empty text is a deliberate result.
        for (replacement in replacements) {
            val ref = replacement.reference
            val unit = bindings[ref.offset] ?: throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING)
            if (ref != reference(unit) || targets.containsKey(ref.offset)) throw InvalidSubtitle(InvalidSubtitle.Reason.BINDING)
            if (replacement.text.length > 65_536) throw InvalidSubtitle(InvalidSubtitle.Reason.LIMIT, unit.row)
            encoded(replacement.text)
            targets[ref.offset] = replacement.text
        }
        val output = StringBuilder()
        fun append(text: String) {
            if (output.length.toLong() + text.length > MAX_OUTPUT) throw InvalidSubtitle(InvalidSubtitle.Reason.LIMIT)
            output.append(text)
        }
        var cursor = 0
        var ordinal = 0
        for (unit in units) {
            append(original.substring(cursor, unit.start))
            val target = targets[unit.start]
            if (type == SubtitleType.SRT && !bilingual && target != null && target.isBlank()) {
                // A completed empty SRT result omits its whole cue. Retained cues are contiguous.
                cursor = unit.end
                continue
            }
            ordinal++
            if (target == null || (target == unit.source && (!bilingual || deduplicate))) {
                val numbered = unit.renumberOriginal
                append(if (!bilingual && targets.isNotEmpty() && numbered != null) numbered(ordinal) else original.substring(unit.start, unit.end))
            } else {
                val body = if (bilingual) {
                    if (target.isBlank() || (deduplicate && target == unit.source)) unit.source else unit.source + "\n" + target
                } else target
                append(unit.render(body, newline, if (bilingual && type == SubtitleType.SRT) 0 else ordinal))
            }
            cursor = unit.end
        }
        append(original.substring(cursor))
        val result = output.toString()
        if (encoded(result).size > MAX_OUTPUT) throw InvalidSubtitle(InvalidSubtitle.Reason.LIMIT)
        return result
    }

    companion object {
        private const val MAX_INPUT = 8 * 1024 * 1024
        internal const val MAX_OUTPUT = 16 * 1024 * 1024
        fun parse(type: SubtitleType, text: String): SubtitleDocument {
            if (text.length > MAX_INPUT || encoded(text).size > MAX_INPUT) throw InvalidSubtitle(InvalidSubtitle.Reason.LIMIT)
            val parsed = when (type) {
                SubtitleType.SRT -> SrtSubtitleParser.parse(text)
                SubtitleType.LRC -> LyricSubtitleParser.parse(text)
                SubtitleType.ASS, SubtitleType.SSA -> AssSubtitleParser.parse(text)
            }
            if (parsed.size > 100_000) throw InvalidSubtitle(InvalidSubtitle.Reason.LIMIT)
            return SubtitleDocument(type, text, parsed)
        }

        internal fun encoded(text: String): ByteArray {
            if ('\u0000' in text) throw InvalidSubtitle(InvalidSubtitle.Reason.TEXT)
            return try { TextProjectFingerprint.utf8(text) }
            catch (_: Exception) { throw InvalidSubtitle(InvalidSubtitle.Reason.TEXT) }
        }

        internal fun lines(text: String): List<SubtitleLine> {
            val result = mutableListOf<SubtitleLine>()
            var start = 0
            while (start < text.length) {
                if (result.size >= 100_000) throw InvalidSubtitle(InvalidSubtitle.Reason.LIMIT)
                var end = start
                while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
                val ending = when {
                    end == text.length -> ""
                    text[end] == '\r' && text.getOrNull(end + 1) == '\n' -> "\r\n"
                    else -> text[end].toString()
                }
                result.add(SubtitleLine(start, result.size, text.substring(start, end), ending))
                start = end + ending.length
            }
            return result
        }
    }
}
