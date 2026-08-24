package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TranslationPrompts
import com.breakyuna.noveltranslator.core.llm.executeCompletion
import com.breakyuna.noveltranslator.core.parser.ParsedChapter
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import org.json.JSONArray

class ChapterSplitAgent(private val llmClient: LlmGateway) {

    suspend fun analyzeAndSplit(
        fullText: String,
        provider: ApiProviderEntity,
        onProgress: ((completedWindows: Int, totalWindows: Int) -> Unit)? = null,
        onUsage: ((LlmResult) -> Unit)? = null
    ): List<ParsedChapter> {
        if (fullText.isBlank()) return emptyList()
        require(fullText.length <= MAX_AI_SPLIT_CHARS) {
            "AI splitting is limited to 2,000,000 characters; use regex splitting for larger books"
        }
        val locatedMarkers = mutableListOf<Pair<Int, String>>()
        val step = WINDOW_CHARS - WINDOW_OVERLAP_CHARS
        val totalWindows = maxOf(1, (fullText.length - WINDOW_OVERLAP_CHARS + step - 1) / step)
        var completedWindows = 0
        var windowStart = 0
        while (windowStart < fullText.length) {
            val windowEnd = minOf(fullText.length, windowStart + WINDOW_CHARS)
            val window = fullText.substring(windowStart, windowEnd)
            val result = llmClient.executeCompletion(
                provider = provider,
                systemPrompt = "You are an expert novel structuring assistant. Output valid JSON only.",
                userPrompt = TranslationPrompts.buildAgentChapterSplitPrompt(window),
                temperature = 0.2f,
                maxTokens = 3000
            )
            onUsage?.invoke(result)
            require(result.isSuccess && result.text.isNotBlank()) {
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
        if (markers.isEmpty()) return TxtParser.splitIntoChapters(fullText, TxtParser.REGEX_CHINESE)
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
        return chapters.ifEmpty { TxtParser.splitIntoChapters(fullText, TxtParser.REGEX_CHINESE) }
    }

    private fun parseMarkers(raw: String): List<Pair<String, String>>? = runCatching {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
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
        private const val MAX_AI_SPLIT_CHARS = 2_000_000
    }
}
