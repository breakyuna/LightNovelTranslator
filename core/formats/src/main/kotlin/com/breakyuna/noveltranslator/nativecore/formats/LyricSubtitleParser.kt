package com.breakyuna.noveltranslator.nativecore.formats

internal object LyricSubtitleParser {
    private const val TIME = "[0-9]{1,3}:[0-9]{1,2}(?:[.:][0-9]{1,6})?"
    private val bracketTime = Regex("\\[$TIME\\]")
    private val timePattern = Regex("\\[$TIME\\]|<$TIME>")

    fun parse(text: String): List<SubtitleUnit> = SubtitleDocument.lines(text).mapNotNull { line ->
        val bom = if (line.start == 0 && line.text.startsWith('\uFEFF')) "\uFEFF" else ""
        val visible = line.text.removePrefix(bom)
        fun validate(tag: String) {
            val seconds = tag.substring(1, tag.lastIndex).substringAfter(':').substringBefore('.').substringBefore(':').toInt()
            if (seconds >= 60) throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, line.row)
        }
        var cursor = 0
        while (cursor < visible.length && (visible[cursor] == ' ' || visible[cursor] == '\t')) cursor++
        val firstTimestamp = cursor
        // Scan repeated timestamps iteratively, avoiding regex-stack growth on hostile prefixes.
        while (cursor < visible.length) {
            var next = cursor
            while (next < visible.length && (visible[next] == ' ' || visible[next] == '\t')) next++
            val match = bracketTime.find(visible, next) ?: break
            if (match.range.first != next) break
            validate(match.value)
            cursor = match.range.last + 1
        }
        if (cursor == firstTimestamp) return@mapNotNull null
        val prefix = visible.substring(0, cursor)
        val tail = visible.substring(cursor)
        val source = buildString {
            var start = 0
            for (match in timePattern.findAll(tail)) {
                validate(match.value)
                append(tail.substring(start, match.range.first))
                val next = match.range.last + 1
                // Removed word timing must not concatenate adjacent words. Preserve existing
                // whitespace rather than adding a second separator around the removed tag.
                if (isNotEmpty() && !last().isWhitespace() && next < tail.length && !tail[next].isWhitespace()) append(' ')
                start = next
            }
            append(tail.substring(start))
        }
        SubtitleUnit(line.start, line.start + line.text.length + line.ending.length, line.row, source, { body, defaultEnding, _ ->
            if (timePattern.containsMatchIn(body)) throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, line.row)
            val ending = line.ending.ifEmpty { defaultEnding }
            // A translation newline repeats this exact source timestamp group.
            val parts = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val size = bom.length.toLong() + parts.sumOf { it.length.toLong() } + parts.size.toLong() * prefix.length +
                (parts.size - 1L) * ending.length + line.ending.length
            if (size > SubtitleDocument.MAX_OUTPUT) throw InvalidSubtitle(InvalidSubtitle.Reason.LIMIT, line.row)
            bom + parts.joinToString(ending) { prefix + it } + line.ending
        })
    }
}
