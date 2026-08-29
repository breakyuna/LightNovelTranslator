package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.core.llm.TranslationPrompts
import com.breakyuna.noveltranslator.core.parser.ParsedChapter
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import org.json.JSONArray

class ChapterSplitAgent(private val llmClient: LlmGateway) {

    suspend fun analyzeAndSplit(
        fullText: String,
        provider: ApiProviderEntity,
        onProgress: ((completedWindows: Int, totalWindows: Int) -> Unit)? = null,
        onUsage: ((LlmResult) -> Unit)? = null,
        controlSignal: TranslationControlSignal? = null
    ): List<ParsedChapter> {
        if (fullText.isBlank()) return emptyList()
        require(fullText.length <= MAX_AI_SPLIT_CHARS) {
            "AI splitting is limited to 2,000,000 characters; use regex splitting for larger books"
        }
        val locatedMarkers = mutableListOf<Pair<Int, String>>()
        val step = WINDOW_CHARS - WINDOW_OVERLAP_CHARS
        val totalWindows = if (fullText.length <= WINDOW_CHARS) {
            1
        } else {
            1 + (fullText.length - WINDOW_CHARS + step - 1) / step
        }
        var completedWindows = 0
        var windowStart = 0
        while (windowStart < fullText.length) {
            val windowEnd = minOf(fullText.length, windowStart + WINDOW_CHARS)
            val window = fullText.substring(windowStart, windowEnd)
            val result = llmClient.executeCompletion(
                LlmRequest(
                    provider = provider,
                    systemPrompt = "You are an expert novel-structuring assistant. Output valid JSON only.",
                    userPrompt = TranslationPrompts.buildAgentChapterSplitPrompt(window),
                    temperature = 0.2f,
                    maxTokens = 3000,
                    operation = "CHAPTER_SPLIT",
                    controlSignal = controlSignal
                )
            )
            onUsage?.invoke(result)
            require(result.isSuccess && !result.isTruncated && result.text.isNotBlank()) {
                "AI splitter window ${completedWindows + 1}/$totalWindows failed: ${result.errorMessage ?: "empty response"}"
            }
            val parsedMarkers = parseMarkers(result.text)
                ?: error("AI splitter returned invalid JSON in window ${completedWindows + 1}/$totalWindows")
            parsedMarkers.forEach { (title, sentence) ->
                val needle = sentence.take(30)
                val localPosition = window.indexOf(needle)
                if (localPosition >= 0) locatedMarkers += (windowStart + localPosition) to title
            }
            completedWindows++
            onProgress?.invoke(completedWindows, totalWindows)
            if (windowEnd == fullText.length) break
            windowStart = windowEnd - WINDOW_OVERLAP_CHARS
        }

        val markers = locatedMarkers.sortedBy { it.first }.distinctBy { it.first }
        if (markers.isEmpty()) return fallbackSplit(fullText)
        val chapters = mutableListOf<ParsedChapter>()
        markers.forEachIndexed { index, (start, title) ->
            val end = markers.getOrNull(index + 1)?.first ?: fullText.length
            val chapterStart = if (index == 0) 0 else start
            val content = fullText.substring(chapterStart, end).trim()
            if (content.isNotBlank()) chapters += ParsedChapter(
                index = chapters.size + 1,
                title = title,
                content = content,
                wordCount = TxtParser.countWords(content)
            )
        }
        return chapters.ifEmpty { fallbackSplit(fullText) }
    }

    /** Keeps a rejected/empty AI response from forcing English or Markdown books into one chunk. */
    private fun fallbackSplit(fullText: String): List<ParsedChapter> {
        val patterns = listOf(
            TxtParser.REGEX_CHINESE,
            TxtParser.REGEX_JAPANESE,
            TxtParser.REGEX_KOREAN,
            TxtParser.REGEX_ENGLISH,
            TxtParser.REGEX_MARKDOWN
        )
        val pattern = patterns
            .map { candidate ->
                val regex = Regex(candidate)
                candidate to fullText.lineSequence().count { line -> regex.matches(line.trim()) }
            }
            .maxByOrNull { it.second }
            ?.takeIf { it.second > 0 }
            ?.first
            ?: TxtParser.REGEX_CHINESE
        return TxtParser.splitIntoChapters(fullText, pattern)
    }

    private fun parseMarkers(raw: String): List<Pair<String, String>>? = runCatching {
        val cleaned = raw.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val array = JSONArray(cleaned)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val sentence = item.optString("firstSentence").trim()
                if (sentence.isNotBlank()) add(item.optString("title", "Chapter ${i + 1}") to sentence)
            }
        }
    }.getOrNull()

    companion object {
        private const val WINDOW_CHARS = 22_000
        private const val WINDOW_OVERLAP_CHARS = 1_000
        const val MAX_AI_SPLIT_CHARS = 2_000_000
    }
}
