package com.example.core.agent

import com.example.core.llm.LlmClient
import com.example.core.llm.TranslationPrompts
import com.example.core.parser.ParsedChapter
import com.example.core.parser.TxtParser
import com.example.data.model.ApiProviderEntity
import org.json.JSONArray

class ChapterSplitAgent(private val llmClient: LlmClient) {

    suspend fun analyzeAndSplit(
        fullText: String,
        provider: ApiProviderEntity
    ): List<ParsedChapter> {
        val sample = fullText.take(12000)
        val prompt = TranslationPrompts.buildAgentChapterSplitPrompt(sample)

        val result = llmClient.executeCompletion(
            provider = provider,
            systemPrompt = "You are an expert novel structuring assistant. Output valid JSON only.",
            userPrompt = prompt,
            temperature = 0.2f
        )

        if (!result.isSuccess || result.text.isBlank()) {
            // Fallback to standard regex split
            return TxtParser.splitIntoChapters(fullText, TxtParser.REGEX_CHINESE)
        }

        try {
            // Clean markdown code fence if present
            var jsonStr = result.text.trim()
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.substringAfter("```json").substringBeforeLast("```").trim()
            } else if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.substringAfter("```").substringBeforeLast("```").trim()
            }

            val jsonArray = JSONArray(jsonStr)
            val markers = mutableListOf<Pair<String, String>>() // (title, firstSentence)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val title = obj.optString("title", "Chapter ${i + 1}")
                val sentence = obj.optString("firstSentence", "").trim()
                if (sentence.isNotEmpty()) {
                    markers.add(Pair(title, sentence))
                }
            }

            if (markers.isEmpty()) {
                return TxtParser.splitIntoChapters(fullText, TxtParser.REGEX_CHINESE)
            }

            // Split fullText using detected markers
            val chapters = mutableListOf<ParsedChapter>()
            var currentIndex = 0

            for (i in markers.indices) {
                val (title, sentence) = markers[i]
                val searchSub = sentence.take(15)
                val foundPos = fullText.indexOf(searchSub, currentIndex)
                val startPos = if (foundPos != -1) foundPos else currentIndex

                val nextStartPos = if (i + 1 < markers.size) {
                    val nextSub = markers[i + 1].second.take(15)
                    val nextPos = fullText.indexOf(nextSub, startPos + searchSub.length)
                    if (nextPos != -1) nextPos else fullText.length
                } else {
                    fullText.length
                }

                if (startPos < nextStartPos && startPos < fullText.length) {
                    val content = fullText.substring(startPos, minOf(nextStartPos, fullText.length)).trim()
                    if (content.isNotBlank()) {
                        chapters.add(
                            ParsedChapter(
                                index = i + 1,
                                title = title,
                                content = content,
                                wordCount = TxtParser.countWords(content)
                            )
                        )
                    }
                }
                currentIndex = nextStartPos
            }

            return if (chapters.isNotEmpty()) chapters else TxtParser.splitIntoChapters(fullText, TxtParser.REGEX_CHINESE)
        } catch (e: Exception) {
            return TxtParser.splitIntoChapters(fullText, TxtParser.REGEX_CHINESE)
        }
    }
}
