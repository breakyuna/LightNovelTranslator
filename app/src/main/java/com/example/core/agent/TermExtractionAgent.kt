package com.example.core.agent

import com.example.core.llm.LlmClient
import com.example.core.llm.LlmResult
import com.example.core.llm.TranslationPrompts
import com.example.data.model.ApiProviderEntity
import com.example.data.model.GlossaryEntity
import com.example.data.model.TermCategory
import org.json.JSONArray

class TermExtractionAgent(private val llmClient: LlmClient) {

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
                var jsonStr = rawText.trim()
                if (jsonStr.contains("```json")) {
                    jsonStr = jsonStr.substringAfter("```json").substringBeforeLast("```").trim()
                } else if (jsonStr.contains("```")) {
                    jsonStr = jsonStr.substringAfter("```").substringBeforeLast("```").trim()
                }

                val jsonArray = JSONArray(jsonStr)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val orig = (if (obj.has("original")) obj.optString("original") else obj.optString("originalTerm", "")).trim()
                    val sugg = (if (obj.has("suggested")) obj.optString("suggested") else obj.optString("translatedTerm", "")).trim()
                    val catStr = obj.optString("category", "CHARACTER").trim().uppercase()
                    val notes = obj.optString("notes", "").trim()

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
                throw IllegalArgumentException("Invalid terminology JSON", error)
            }
            return terms
        }
    }
}
