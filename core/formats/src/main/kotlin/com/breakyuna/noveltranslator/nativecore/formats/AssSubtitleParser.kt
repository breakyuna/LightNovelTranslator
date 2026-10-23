package com.breakyuna.noveltranslator.nativecore.formats

internal object AssSubtitleParser {
    private val sectionPattern = Regex("^\\s*\\[([^\\]]+)\\]\\s*$")
    private val formatPattern = Regex("^\\s*Format:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val dialoguePattern = Regex("^\\s*Dialogue:\\s*", RegexOption.IGNORE_CASE)
    private val standardLayer = Regex("^(?:[0-9]+|Marked=[0-9]+)$", RegexOption.IGNORE_CASE)
    private val standardTime = Regex("^[0-9]+:[0-9]{2}:[0-9]{2}\\.[0-9]{2}$")

    fun parse(text: String): List<SubtitleUnit> {
        val units = mutableListOf<SubtitleUnit>()
        var events = false
        var fieldCount: Int? = null
        for (line in SubtitleDocument.lines(text)) {
            val visible = if (line.start == 0) line.text.removePrefix("\uFEFF") else line.text
            val section = sectionPattern.matchEntire(visible)
            if (section != null) {
                events = section.groupValues[1].equals("events", ignoreCase = true)
                fieldCount = null
                continue
            }
            if (!events) continue
            val format = formatPattern.matchEntire(line.text)
            if (format != null) {
                val fields = format.groupValues[1].split(',').map { it.trim() }
                if (!fields.last().equals("text", ignoreCase = true) || fields.any { it.isEmpty() } ||
                    fields.count { it.equals("text", ignoreCase = true) } != 1)
                    throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, line.row)
                fieldCount = fields.size
                continue
            }
            val dialogue = dialoguePattern.find(line.text) ?: continue
            val count = fieldCount
            if (count == null) {
                val fields = line.text.substring(dialogue.value.length).split(',')
                if (fields.size < 10 || !standardLayer.matches(fields[0].trim()) ||
                    !standardTime.matches(fields[1].trim()) || !standardTime.matches(fields[2].trim()))
                    throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, line.row)
            }
            var bodyStart = dialogue.value.length
            repeat((count ?: 10) - 1) {
                val comma = line.text.indexOf(',', bodyStart)
                if (comma < 0) throw InvalidSubtitle(InvalidSubtitle.Reason.STRUCTURE, line.row)
                bodyStart = comma + 1
            }
            val source = line.text.substring(bodyStart).replace("\\N", "\n")
            units.add(SubtitleUnit(line.start + bodyStart, line.start + line.text.length, line.row, source, { body, _, _ ->
                body.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\\N")
            }))
        }
        return units
    }
}
