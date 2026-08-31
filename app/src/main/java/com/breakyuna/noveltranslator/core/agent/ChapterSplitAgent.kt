package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TranslationControlSignal
import com.breakyuna.noveltranslator.core.llm.TranslationPrompts
import com.breakyuna.noveltranslator.core.parser.ParsedChapter
import com.breakyuna.noveltranslator.core.parser.TxtParser
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import org.json.JSONObject

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
            "AI splitting is limited to $MAX_AI_SPLIT_CHARS characters; use regex splitting for larger books"
        }

        val sample = fullText.take(SAMPLE_CHARS)
        onProgress?.invoke(0, 1)

        val result = llmClient.executeCompletion(
            LlmRequest(
                provider = provider,
                systemPrompt = "You are an expert novel-structuring assistant. Output valid JSON only.",
                userPrompt = TranslationPrompts.buildAgentChapterSplitPrompt(sample),
                temperature = 0.1f,
                maxTokens = 1000,
                operation = "CHAPTER_SPLIT",
                controlSignal = controlSignal
            )
        )
        onUsage?.invoke(result)

        require(result.isSuccess && result.text.isNotBlank()) {
            "AI chapter regex extraction failed: ${result.errorMessage ?: "empty response"}"
        }

        val extractedRegex = parseExtractedRegex(result.text)
        val chapters = if (!extractedRegex.isNullOrBlank()) {
            runCatching {
                TxtParser.validateChapterRegex(extractedRegex)
                TxtParser.splitIntoChapters(fullText, extractedRegex)
            }.getOrNull()
        } else {
            null
        }

        onProgress?.invoke(1, 1)

        return if (!chapters.isNullOrEmpty() && (chapters.size > 1 || fullText.length < 5000)) {
            chapters
        } else {
            fallbackSplit(fullText, sample)
        }
    }

    /**
     * Parses the regular expression from the LLM output.
     */
    internal fun parseExtractedRegex(raw: String): String? = runCatching {
        val cleaned = raw.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()

        val jsonRegex = runCatching {
            val json = JSONObject(cleaned)
            json.optString("regex")
                .ifBlank { json.optString("pattern") }
                .ifBlank { json.optString("chapterRegex") }
                .trim()
        }.getOrNull()

        if (!jsonRegex.isNullOrBlank()) return@runCatching jsonRegex

        // Fallback parser if JSON is malformed
        val regexMatch = Regex(""""(?:regex|pattern|chapterRegex)"\s*:\s*"((?:\\.|[^"\\])*)"""", RegexOption.IGNORE_CASE)
            .find(cleaned)
        regexMatch?.groupValues?.get(1)?.replace("\\\\", "\\")?.replace("\\\"", "\"")?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Fallback split using offline heuristic / language-based regex detection.
     */
    private fun fallbackSplit(fullText: String, sample: String): List<ParsedChapter> {
        val inferredRegex = TxtParser.inferChapterRegex(sample)
        return TxtParser.splitIntoChapters(fullText, inferredRegex)
    }

    companion object {
        const val SAMPLE_CHARS = 20_000
        const val MAX_AI_SPLIT_CHARS = 10_000_000
    }
}

