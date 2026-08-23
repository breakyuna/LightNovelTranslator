package com.example.core.parser

import java.io.ByteArrayInputStream
import java.nio.charset.Charset

data class ParsedChapter(
    val index: Int,
    val title: String,
    val content: String,
    val wordCount: Int
)

object TxtParser {

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

    val REGEX_CHINESE = "(^\\s*第[0-9零一二两三四五六七八九十百千万]+[章回节卷集幕篇].*)"
    val REGEX_ENGLISH = "(^\\s*(Chapter|CHAPTER|Section|SECTION|Book|BOOK|Prologue|PROLOGUE|Epilogue|EPILOGUE|Act|ACT)\\s*(\\d+|[IVXLCDM]+)?.*)"
    val REGEX_MARKDOWN = "(^#{1,3}\\s+.*)"

    /**
     * Splits full text into chapters based on regex pattern or fallback chunking
     */
    fun splitIntoChapters(
        fullText: String,
        regexPattern: String = REGEX_CHINESE,
        fallbackChunkWords: Int = 2500
    ): List<ParsedChapter> {
        val lines = fullText.lines()
        val pattern = try {
            Regex(regexPattern, RegexOption.MULTILINE)
        } catch (e: Exception) {
            Regex(REGEX_CHINESE, RegexOption.MULTILINE)
        }

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
        if (chapters.size <= 1 && fullText.length > fallbackChunkWords * 2) {
            return splitByParagraphChunks(fullText, fallbackChunkWords)
        }

        return chapters
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
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA ||
                ub == Character.UnicodeBlock.HANGUL_SYLLABLES
    }
}
