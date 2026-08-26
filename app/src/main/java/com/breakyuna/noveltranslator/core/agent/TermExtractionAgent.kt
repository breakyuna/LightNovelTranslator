package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.core.llm.TranslationPrompts
import com.breakyuna.noveltranslator.core.llm.executeCompletion
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.TermCategory
import org.json.JSONArray
import org.json.JSONObject

class TermExtractionAgent(private val llmClient: LlmGateway) {

    data class ExtractionResult(
        val terms: List<GlossaryEntity>,
        val usage: LlmResult,
        val parseError: String? = null
    )

    suspend fun extractTerms(
        projectId: Long,
        sampleText: String,
        provider: ApiProviderEntity,
        sourceLanguage: String,
        targetLanguage: String,
        existingTerms: Collection<String> = emptyList()
    ): List<GlossaryEntity> {
        val extraction = extractTermsWithUsage(
            projectId,
            sampleText,
            provider,
            sourceLanguage,
            targetLanguage,
            existingTerms
        )
        check(extraction.usage.isSuccess && extraction.usage.text.isNotBlank()) {
            extraction.usage.errorMessage ?: "Terminology extraction returned no usable response"
        }
        check(extraction.parseError == null) { extraction.parseError ?: "Invalid terminology JSON" }
        return extraction.terms
    }

    suspend fun extractTermsWithUsage(
        projectId: Long,
        sampleText: String,
        provider: ApiProviderEntity,
        sourceLanguage: String,
        targetLanguage: String,
        existingTerms: Collection<String> = emptyList()
    ): ExtractionResult {
        val prompt = TranslationPrompts.buildTermExtractionPrompt(
            sampleText,
            sourceLanguage,
            targetLanguage,
            existingTerms
        )

        val result = llmClient.executeCompletion(
            provider = provider,
            systemPrompt = "You are a specialized novel terminologist. Output valid JSON array only.",
            userPrompt = prompt,
            temperature = 0.3f
        )

        return parseExtractionResult(projectId, result)
    }

    companion object {
        fun parseExtractionResult(projectId: Long, result: LlmResult): ExtractionResult {
            if (!result.isSuccess || result.text.isBlank()) return ExtractionResult(emptyList(), result)
            return try {
                ExtractionResult(parseTermsJson(projectId, result.text), result)
            } catch (error: IllegalArgumentException) {
                ExtractionResult(emptyList(), result, error.message ?: "Invalid terminology JSON")
            }
        }

        fun parseTermsJson(projectId: Long, rawText: String): List<GlossaryEntity> {
            val terms = mutableListOf<GlossaryEntity>()
            try {
                val jsonStr = rawText.trim()
                    .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*```$"), "")
                    .trim()
                val jsonArray = when {
                    jsonStr.startsWith("[") -> JSONArray(jsonStr.substring(0, jsonStr.lastIndexOf(']') + 1))
                    jsonStr.startsWith("{") -> {
                        val root = JSONObject(jsonStr.substring(0, jsonStr.lastIndexOf('}') + 1))
                        root.optJSONArray("terms")
                            ?: root.optJSONArray("glossary")
                            ?: root.optJSONArray("items")
                            ?: throw IllegalArgumentException("Terminology response object has no terms array")
                    }
                    else -> {
                        val start = jsonStr.indexOf('[')
                        val end = jsonStr.lastIndexOf(']')
                        if (start < 0 || end <= start) throw IllegalArgumentException("Terminology response has no JSON array")
                        JSONArray(jsonStr.substring(start, end + 1))
                    }
                }
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val orig = firstString(obj, "original", "originalTerm", "source", "sourceTerm").trim()
                    val sugg = firstString(obj, "suggested", "translatedTerm", "target", "targetTerm", "translation").trim()
                    val catStr = firstString(obj, "category", "type").ifBlank { "CUSTOM" }.trim().uppercase()
                    val notes = firstString(obj, "notes", "description", "context").trim()

                    if (orig.isNotBlank() && sugg.isNotBlank()) {
                        val category = when (catStr) {
                            "FACTION", "RACE", "CONCEPT" -> TermCategory.LORE
                            "TITLE" -> TermCategory.HONORIFIC
                            else -> runCatching { TermCategory.valueOf(catStr) }.getOrDefault(TermCategory.CUSTOM)
                        }

                        terms.add(
                            GlossaryEntity(
                                projectId = projectId,
                                originalTerm = orig,
                                translatedTerm = sugg,
                                category = category,
                                notes = notes,
                                isAutoExtracted = true
                            )
                        )
                    }
                }
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid terminology JSON: ${error.message}", error)
            }
            return terms.distinctBy { it.originalTerm.lowercase() }
        }

        private fun firstString(obj: JSONObject, vararg keys: String): String = keys.asSequence()
            .map { obj.optString(it, "") }
            .firstOrNull { it.isNotBlank() && !it.equals("null", true) }
            .orEmpty()
    }
}
