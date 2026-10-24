package com.breakyuna.noveltranslator.nativecore.formats

/**
 * Static text layout for page replacement. It performs no PDF work: callers pass a width
 * measurement callback, so the wrapping rules are testable on a plain JVM without PDFBox.
 *
 * This is intentionally a simple block layout, not an attempt to reproduce the original page
 * composition. Text is wrapped to the page width, written from the top of the crop box and may
 * continue on additional pages of the same size.
 */
internal object PdfTextLayout {
    /**
     * Normalizes PDF text extraction output: CR/CRLF become LF, tabs and LF survive, other C0/C1
     * controls and unpaired surrogates are removed. Paragraph boundaries are preserved.
     */
    fun sanitize(raw: String): String {
        val output = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val codePoint = raw.codePointAt(index)
            val count = Character.charCount(codePoint)
            when {
                codePoint == '\r'.code -> {
                    output.append('\n')
                    if (index + count < raw.length && raw[index + count] == '\n') index += 1
                }
                codePoint == '\n'.code || codePoint == '\t'.code -> output.append(codePoint.toChar())
                codePoint < 0x20 || codePoint in 0x7F..0x9F -> Unit
                codePoint in 0xD800..0xDFFF -> Unit
                codePoint == 0xFEFF -> Unit
                else -> output.appendCodePoint(codePoint)
            }
            index += count
        }
        return output.toString()
    }

    /**
     * Greedy line breaking. Latin text breaks at spaces, CJK text may break between characters,
     * and a single word longer than the line is split by code point instead of overflowing.
     * Every paragraph (including an empty one) produces at least one line.
     */
    fun wrap(text: String, maxWidth: Float, measure: (Int) -> Float): List<String> {
        require(maxWidth > 0f)
        val output = ArrayList<String>()
        val normalized = sanitize(text)
        for (paragraph in normalized.split('\n')) {
            if (paragraph.isEmpty()) {
                output.add("")
                continue
            }
            val line = StringBuilder()
            var lineWidth = 0f

            fun flushLine() {
                output.add(line.toString().trimEnd())
                line.setLength(0)
                lineWidth = 0f
            }

            for (atom in atoms(paragraph)) {
                if (atom.whitespace) {
                    if (line.isEmpty()) continue
                    val spaceWidth = measure(' '.code)
                    if (lineWidth + spaceWidth > maxWidth) {
                        flushLine()
                        continue
                    }
                    line.append(' ')
                    lineWidth += spaceWidth
                    continue
                }
                val atomWidth = widthOf(atom.text, measure)
                if (line.isNotEmpty() && lineWidth + atomWidth > maxWidth) flushLine()
                if (atomWidth <= maxWidth) {
                    line.append(atom.text)
                    lineWidth += atomWidth
                    continue
                }
                // A single unbreakable token (URL, long word) still has to fit; split by code point.
                var index = 0
                while (index < atom.text.length) {
                    val codePoint = atom.text.codePointAt(index)
                    val codePointWidth = measure(codePoint)
                    if (line.isNotEmpty() && lineWidth + codePointWidth > maxWidth) flushLine()
                    line.appendCodePoint(codePoint)
                    lineWidth += codePointWidth
                    index += Character.charCount(codePoint)
                }
            }
            flushLine()
        }
        return output
    }

    private fun atoms(paragraph: String): List<Atom> {
        val result = ArrayList<Atom>()
        val word = StringBuilder()

        fun flushWord() {
            if (word.isEmpty()) return
            result.add(Atom(word.toString(), false))
            word.setLength(0)
        }

        var index = 0
        while (index < paragraph.length) {
            val codePoint = paragraph.codePointAt(index)
            when {
                isSpace(codePoint) -> {
                    flushWord()
                    result.add(Atom(" ", true))
                }
                isBreakable(codePoint) -> {
                    flushWord()
                    result.add(Atom(String(Character.toChars(codePoint)), false))
                }
                codePoint < 0x20 || codePoint in 0x7F..0x9F -> Unit
                else -> word.appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
        flushWord()
        return result
    }

    private fun widthOf(text: String, measure: (Int) -> Float): Float {
        var width = 0f
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += measure(codePoint)
            index += Character.charCount(codePoint)
        }
        return width
    }

    private fun isSpace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

    private fun isBreakable(codePoint: Int): Boolean =
        codePoint in 0x2E80..0x9FFF ||
            codePoint in 0xA960..0xA97F ||
            codePoint in 0xAC00..0xD7AF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE1F ||
            codePoint in 0xFF00..0xFFEF ||
            codePoint in 0x1B000..0x1B16F ||
            codePoint in 0x20000..0x3FFFF

    private class Atom(val text: String, val whitespace: Boolean)
}
