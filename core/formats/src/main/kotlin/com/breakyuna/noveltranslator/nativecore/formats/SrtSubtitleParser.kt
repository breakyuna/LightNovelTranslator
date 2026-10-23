package com.breakyuna.noveltranslator.nativecore.formats

internal object SrtSubtitleParser {
    private const val TIME = "[0-9]{1,9}:[0-9]{2}:[0-9]{2},[0-9]{3}"
    private val timing = Regex("^($TIME)[ \\t]+-->[ \\t]+($TIME)(?:[ \\t]+([^\\r\\n]*))?$")
    private val index = Regex("^[0-9]+$")

    fun parse(text: String): List<SubtitleUnit> {
        val lines = SubtitleDocument.lines(text)
        val units = mutableListOf<SubtitleUnit>()
        var position = 0
        while (position < lines.size) {
            val current = lines[position]
            val visible = if (current.start == 0) current.text.removePrefix("\uFEFF") else current.text
            if (visible.isBlank()) { position++; continue }
            val start = position
            while (position < lines.size && lines[position].text.isNotBlank()) position++
            val end = position
            while (position < lines.size && lines[position].text.isBlank()) position++
            val first = lines[start]
            val bom = if (first.start == 0 && first.text.startsWith('\uFEFF')) 1 else 0
            if (end - start < 3 || !index.matches(first.text.substring(bom).trim()))
                throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, first.row)
            val timeLine = lines[start + 1]
            val parsed = timing.matchEntire(timeLine.text.trim()) ?: throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, timeLine.row)
            if (parsed.groupValues[3].contains("-->") || time(parsed.groupValues[2], timeLine.row) <= time(parsed.groupValues[1], timeLine.row))
                throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, timeLine.row)
            val unitStart = first.start + bom
            val unitEnd = if (position < lines.size) lines[position].start else text.length
            val firstBody = lines[start + 2]
            val lastBody = lines[end - 1]
            val source = lines.subList(start + 2, end).joinToString("\n") { it.text }
            val sourceIndex = first.text.substring(bom)
            val indentation = sourceIndex.takeWhile(Char::isWhitespace)
            val numberEnding = sourceIndex.takeLastWhile(Char::isWhitespace)
            val suffix = text.substring(lastBody.start + lastBody.text.length, unitEnd)
            val numberTail = text.substring(first.start + first.text.length, firstBody.start)
            fun header(ordinal: Int) = (if (ordinal == 0) sourceIndex else indentation + ordinal + numberEnding) + numberTail
            units.add(SubtitleUnit(unitStart, unitEnd, firstBody.row, source, { body, defaultEnding, ordinal ->
                val normalized = body.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n', '\r')
                if (normalized.trimEnd().split('\n').any { it.isBlank() }) throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, firstBody.row)
                val ending = firstBody.ending.ifEmpty { timeLine.ending.ifEmpty { defaultEnding } }
                header(ordinal) + normalized.replace("\n", ending) + suffix
            }, { ordinal ->
                indentation + ordinal + numberEnding + text.substring(first.start + first.text.length, unitEnd)
            }))
        }
        return units
    }

    private fun time(value: String, row: Int): Long {
        val parts = value.replace(',', ':').split(':').map { it.toLong() }
        if (parts[1] >= 60 || parts[2] >= 60) throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, row)
        return ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000 + parts[3]
    }
}
