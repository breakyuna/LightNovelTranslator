package com.breakyuna.noveltranslator.core.parser

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class ParsedChapter(
    val index: Int,
    val title: String,
    val content: String,
    val wordCount: Int
)

object TxtParser {

    data class CharsetDetection(val charset: Charset, val bomBytes: Int)

    /**
     * Detect charset among UTF-8, GBK, GB18030, UTF-16LE, UTF-16BE
     */
    fun detectCharsetAndRead(bytes: ByteArray): Pair<String, Charset> {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return Pair(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), Charsets.UTF_16LE)
            }
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return Pair(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), Charsets.UTF_16BE)
            }
            if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                return Pair(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), Charsets.UTF_8)
            }
        }

        // Try UTF-8
        try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            return Pair(String(bytes, Charsets.UTF_8), Charsets.UTF_8)
        } catch (e: Exception) {
            // fallback to GBK
        }

        return try {
            val gbkCharset = Charset.forName("GB18030")
            Pair(String(bytes, gbkCharset), gbkCharset)
        } catch (e: Exception) {
            Pair(String(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1)
        }
    }

    /** Detects encoding from a bounded prefix so importing never materializes the whole file. */
    fun detectCharset(file: File, sampleBytes: Int = CHARSET_SAMPLE_BYTES): CharsetDetection {
        require(sampleBytes > 0)
        val sample = ByteArray(sampleBytes)
        val count = file.inputStream().use { input ->
            var total = 0
            while (total < sample.size) {
                val read = input.read(sample, total, sample.size - total)
                if (read < 0) break
                total += read
            }
            total
        }
        val bytes = if (count == sample.size) sample else sample.copyOf(count)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return CharsetDetection(Charsets.UTF_16LE, 2)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return CharsetDetection(Charsets.UTF_16BE, 2)
        }
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return CharsetDetection(Charsets.UTF_8, 3)
        }
        return if (isValidUtf8Prefix(bytes)) {
            CharsetDetection(Charsets.UTF_8, 0)
        } else {
            CharsetDetection(runCatching { Charset.forName("GB18030") }.getOrDefault(Charsets.ISO_8859_1), 0)
        }
    }

    fun openDetectedReader(file: File): BufferedReader {
        val detection = detectCharset(file)
        val input = file.inputStream()
        var remainingBom = detection.bomBytes
        while (remainingBom > 0) {
            val skipped = input.skip(remainingBom.toLong()).toInt()
            if (skipped > 0) remainingBom -= skipped else if (input.read() >= 0) remainingBom-- else break
        }
        return BufferedReader(InputStreamReader(input, detection.charset), DEFAULT_BUFFER_SIZE)
    }

    val REGEX_CHINESE = "(^\\s*第[0-9零一二两三四五六七八九十百千万]+[章回节卷集幕篇].*)"
    val REGEX_ENGLISH = "(^\\s*(Chapter|CHAPTER|Section|SECTION|Book|BOOK|Prologue|PROLOGUE|Epilogue|EPILOGUE|Act|ACT)\\s*(\\d+|[IVXLCDM]+)?.*)"
    val REGEX_MARKDOWN = "(^#{1,3}\\s+.*)"

    /** Validates a user-supplied chapter heading expression before any file work begins. */
    fun validateChapterRegex(regexPattern: String) {
        require(regexPattern.isNotBlank()) { "Chapter regex must not be blank" }
        require(regexPattern.length <= MAX_REGEX_LENGTH) { "Chapter regex is too long" }
        runCatching { Regex(regexPattern) }
            .getOrElse { error ->
                throw IllegalArgumentException(
                    "Invalid chapter regex: ${error.message ?: "syntax error"}",
                    error
                )
            }
    }

    /**
     * Removes a confidently identified leading table of contents without touching the source file.
     * The detector is intentionally conservative: it requires a TOC label or a run of page-numbered
     * heading entries, then waits for a real heading/body boundary before returning a crop point.
     */
    fun cropTableOfContents(
        fullText: String,
        regexPattern: String = REGEX_CHINESE
    ): String {
        if (fullText.isBlank()) return fullText
        validateChapterRegex(regexPattern)
        return cropLeadingTableOfContents(fullText, Regex(regexPattern))
    }

    /**
     * Splits full text into chapters based on regex pattern or fallback chunking
     */
    fun splitIntoChapters(
        fullText: String,
        regexPattern: String = REGEX_CHINESE,
        fallbackChunkWords: Int = 2500,
        cropTableOfContents: Boolean = false
    ): List<ParsedChapter> {
        validateChapterRegex(regexPattern)
        val pattern = Regex(regexPattern)
        val sourceText = if (cropTableOfContents) {
            cropLeadingTableOfContents(fullText, pattern)
        } else {
            fullText
        }
        val lines = sourceText.lines()

        val chapters = mutableListOf<ParsedChapter>()
        var currentTitle = "Preface / Prologue"
        val currentContent = StringBuilder()
        var chapterIndex = 1

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && pattern.matches(trimmed)) {
                if (currentContent.isNotBlank()) {
                    val contentStr = currentContent.toString().trim()
                    chapters.add(
                        ParsedChapter(
                            index = chapterIndex++,
                            title = currentTitle,
                            content = contentStr,
                            wordCount = countWords(contentStr)
                        )
                    )
                    currentContent.clear()
                }
                currentTitle = trimmed
            } else {
                currentContent.append(line).append("\n")
            }
        }

        if (currentContent.isNotBlank()) {
            val contentStr = currentContent.toString().trim()
            chapters.add(
                ParsedChapter(
                    index = chapterIndex,
                    title = currentTitle,
                    content = contentStr,
                    wordCount = countWords(contentStr)
                )
            )
        }

        // If no chapters were detected (single monolithic chapter), perform intelligent character chunking
        if (chapters.size <= 1 && sourceText.length > fallbackChunkWords * 2) {
            return splitByParagraphChunks(sourceText, fallbackChunkWords)
        }

        return chapters
    }

    /**
     * Lazily parses a TXT reader and keeps only one bounded chapter in memory. Files without reliable
     * headings are emitted in fallback-sized chunks instead of becoming one giant String.
     */
    fun chapterSequence(
        reader: BufferedReader,
        regexPattern: String = REGEX_CHINESE,
        fallbackChunkWords: Int = 2500,
        cropTableOfContents: Boolean = false
    ): Sequence<ParsedChapter> = sequence {
        validateChapterRegex(regexPattern)
        require(fallbackChunkWords > 0)
        val pattern = Regex(regexPattern)
        var currentTitle = "Preface / Prologue"
        var currentContent = StringBuilder()
        var currentWordCount = 0
        var chapterIndex = 1
        var foundHeading = false
        var headingPart = 1

        fun drain(title: String): ParsedChapter? {
            if (currentContent.isBlank()) return null
            val content = currentContent.toString().trim()
            val chapter = ParsedChapter(chapterIndex++, title, content, currentWordCount)
            currentContent = StringBuilder()
            currentWordCount = 0
            return chapter
        }

        val chunks = if (cropTableOfContents) {
            val iterator = lineChunks(reader).iterator()
            val prefix = mutableListOf<ReaderChunk>()
            var prefixCharacters = 0
            while (
                iterator.hasNext() &&
                prefix.size < MAX_TOC_SCAN_LINES &&
                prefixCharacters < MAX_TOC_SCAN_CHARS
            ) {
                val chunk = iterator.next()
                prefix += chunk
                prefixCharacters += chunk.text.length + if (chunk.endsLine) 1 else 0
            }
            val cropIndex = findTableOfContentsStart(prefix, pattern)
            val retainedPrefix = if (cropIndex == null) prefix else prefix.drop(cropIndex)
            retainedPrefix.asSequence() + iterator.asSequence()
        } else {
            lineChunks(reader)
        }

        for (chunk in chunks) {
            val line = chunk.text
            val trimmed = line.trim()
            if (chunk.canBeHeading && trimmed.isNotEmpty() && pattern.matches(trimmed)) {
                val completedTitle = if (headingPart > 1) "$currentTitle (Part $headingPart)" else currentTitle
                val completed = drain(completedTitle)
                if (completed != null) yield(completed)
                currentTitle = trimmed
                foundHeading = true
                headingPart = 1
                continue
            }

            currentContent.append(line)
            if (chunk.endsLine) currentContent.append('\n')
            currentWordCount += countWords(line)
            if (!foundHeading && currentWordCount >= fallbackChunkWords) {
                val completed = drain("第 $chapterIndex 章")
                if (completed != null) yield(completed)
            } else if (currentContent.length >= MAX_STREAMED_CHAPTER_CHARS) {
                val partTitle = if (foundHeading) "$currentTitle (Part $headingPart)" else "第 $chapterIndex 章"
                val completed = drain(partTitle)
                if (completed != null) yield(completed)
                if (foundHeading) headingPart++
            }
        }

        val finalTitle = if (headingPart > 1) "$currentTitle (Part $headingPart)" else currentTitle
        val finalChapter = drain(if (foundHeading) finalTitle else "第 $chapterIndex 章")
        if (finalChapter != null) yield(finalChapter)
    }

    private data class ReaderChunk(val text: String, val endsLine: Boolean, val canBeHeading: Boolean)

    private fun cropLeadingTableOfContents(fullText: String, pattern: Regex): String {
        val lines = fullText.lines().map { ReaderChunk(it, endsLine = true, canBeHeading = true) }
        val cropIndex = findTableOfContentsStart(lines, pattern) ?: return fullText
        return lines.drop(cropIndex).joinToString("\n") { it.text }
    }

    private fun findTableOfContentsStart(
        lines: List<ReaderChunk>,
        pattern: Regex
    ): Int? {
        if (lines.isEmpty()) return null
        val scanLimit = minOf(lines.size, MAX_TOC_SCAN_LINES)
        val labelScanLimit = minOf(scanLimit, MAX_TOC_LABEL_SCAN_LINES)
        val tocLabelIndex = lines
            .subList(0, labelScanLimit)
            .indexOfFirst { isTableOfContentsLabel(it.text) }
        val hasExplicitLabel = tocLabelIndex >= 0
        val startIndex = if (hasExplicitLabel) tocLabelIndex + 1 else 0
        val candidateKeys = mutableSetOf<String>()
        var pageNumberedHeadings = 0
        var bodyMarkerSeen = false

        for (index in startIndex until scanLimit) {
            val line = lines[index]
            if (!line.canBeHeading) continue
            val trimmed = line.text.trim()
            if (trimmed.isBlank()) continue

            if (isBodyStartMarker(trimmed) && candidateKeys.isNotEmpty()) {
                bodyMarkerSeen = true
                continue
            }
            if (!pattern.matches(trimmed)) continue

            val key = normalizeTableOfContentsEntry(trimmed)
            val repeatedEntry = key.isNotBlank() && key in candidateKeys
            val looksLikeTocEntry = isPageNumberedTableOfContentsEntry(trimmed)

            if (bodyMarkerSeen) return index
            if (!looksLikeTocEntry && hasExplicitLabel && (pageNumberedHeadings > 0 || repeatedEntry)) {
                return index
            }
            if (!looksLikeTocEntry && !hasExplicitLabel && pageNumberedHeadings >= 2) {
                return index
            }

            if (looksLikeTocEntry) pageNumberedHeadings++
            if (key.isNotBlank()) candidateKeys += key
        }
        return null
    }

    private fun isTableOfContentsLabel(line: String): Boolean {
        val normalized = line.trim().lowercase(Locale.ROOT)
        if (normalized.length > 100) return false
        return normalized.startsWith("目录") || normalized.startsWith("目次") ||
            normalized.contains("table of contents") || normalized.startsWith("contents") ||
            normalized == "content"
    }

    private fun isBodyStartMarker(line: String): Boolean {
        val normalized = line.trim().lowercase(Locale.ROOT)
        return BODY_START_MARKER.matches(normalized)
    }

    private fun isPageNumberedTableOfContentsEntry(line: String): Boolean =
        TOC_PAGE_SUFFIX.containsMatchIn(line.trim())

    private fun normalizeTableOfContentsEntry(line: String): String =
        TOC_PAGE_SUFFIX.replace(line.trim(), "")
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)

    /** Keeps even a malformed TXT containing a multi-megabyte physical line bounded in memory. */
    private fun lineChunks(reader: BufferedReader): Sequence<ReaderChunk> = sequence {
        val readBuffer = CharArray(DEFAULT_BUFFER_SIZE)
        var lineBuffer = StringBuilder()
        var atPhysicalLineStart = true
        while (true) {
            val count = reader.read(readBuffer)
            if (count < 0) break
            for (index in 0 until count) {
                val char = readBuffer[index]
                if (char == '\n') {
                    val text = lineBuffer.toString().removeSuffix("\r")
                    yield(ReaderChunk(text, endsLine = true, canBeHeading = atPhysicalLineStart))
                    lineBuffer = StringBuilder()
                    atPhysicalLineStart = true
                } else {
                    lineBuffer.append(char)
                    if (lineBuffer.length >= MAX_PHYSICAL_LINE_CHARS) {
                        yield(ReaderChunk(lineBuffer.toString(), endsLine = false, canBeHeading = false))
                        lineBuffer = StringBuilder()
                        atPhysicalLineStart = false
                    }
                }
            }
        }
        if (lineBuffer.isNotEmpty()) {
            yield(ReaderChunk(lineBuffer.toString(), endsLine = true, canBeHeading = atPhysicalLineStart))
        }
    }

    private fun splitByParagraphChunks(text: String, targetWordsPerChunk: Int): List<ParsedChapter> {
        val paragraphs = text.split(Regex("\n{2,}|\r\n\r\n"))
        val chapters = mutableListOf<ParsedChapter>()
        var currentChunk = StringBuilder()
        var currentWordCount = 0
        var chapterIndex = 1

        for (para in paragraphs) {
            val paraWords = countWords(para)
            if (currentWordCount + paraWords > targetWordsPerChunk && currentChunk.isNotBlank()) {
                val contentStr = currentChunk.toString().trim()
                chapters.add(
                    ParsedChapter(
                        index = chapterIndex,
                        title = "第 ${chapterIndex} 章",
                        content = contentStr,
                        wordCount = countWords(contentStr)
                    )
                )
                chapterIndex++
                currentChunk = StringBuilder()
                currentWordCount = 0
            }
            currentChunk.append(para).append("\n\n")
            currentWordCount += paraWords
        }

        if (currentChunk.isNotBlank()) {
            val contentStr = currentChunk.toString().trim()
            chapters.add(
                ParsedChapter(
                    index = chapterIndex,
                    title = "第 ${chapterIndex} 章",
                    content = contentStr,
                    wordCount = countWords(contentStr)
                )
            )
        }

        return chapters
    }

    fun countWords(text: String): Int {
        var cjkCount = 0
        var latinWordCount = 0
        var inLatinWord = false

        for (c in text) {
            if (isCjk(c)) {
                cjkCount++
                inLatinWord = false
            } else if (c.isLetterOrDigit()) {
                if (!inLatinWord) {
                    latinWordCount++
                    inLatinWord = true
                }
            } else {
                inLatinWord = false
            }
        }
        return cjkCount + latinWordCount
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) || // CJK Unified Ideographs
                (code in 0x3400..0x4DBF) || // CJK Unified Ideographs Extension A
                (code in 0xF900..0xFAFF) || // CJK Compatibility Ideographs
                (code in 0x3040..0x309F) || // Hiragana
                (code in 0x30A0..0x30FF) || // Katakana
                (code in 0xAC00..0xD7AF)    // Hangul Syllables
    }

    private fun isValidUtf8Prefix(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(ByteBuffer.wrap(bytes), CharBuffer.allocate(bytes.size), false)
        return !result.isError
    }

    private const val CHARSET_SAMPLE_BYTES = 64 * 1024
    private const val MAX_REGEX_LENGTH = 500
    private const val MAX_TOC_SCAN_LINES = 6_000
    private const val MAX_TOC_LABEL_SCAN_LINES = 400
    private const val MAX_TOC_SCAN_CHARS = 512 * 1024
    private const val MAX_PHYSICAL_LINE_CHARS = 64 * 1024
    private const val MAX_STREAMED_CHAPTER_CHARS = 1_000_000
    private val BODY_START_MARKER = Regex(
        "(?:正文|正文开始|本文正文|开始阅读|start of (?:the )?story|begin(?:ning)? of (?:the )?text)",
        RegexOption.IGNORE_CASE
    )
    private val WHITESPACE = Regex("\\s+")
    private val TOC_PAGE_SUFFIX = Regex(
        "(?:\\.{2,}|…{2,}|·{2,}|-{2,}|_{2,}|\\s{2,})\\s*(?:\\d{1,6}|[ivxlcdm]{1,12})\\s*$",
        RegexOption.IGNORE_CASE
    )
}
