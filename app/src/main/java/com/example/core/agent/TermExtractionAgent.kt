package com.example.core.agent

import com.example.core.llm.LlmClient
import com.example.core.llm.TranslationPrompts
import com.example.data.model.ApiProviderEntity
import com.example.data.model.GlossaryEntity
import com.example.data.model.TermCategory
import org.json.JSONArray

class TermExtractionAgent(private val llmClient: LlmClient) {

    suspend fun extractTerms(
        projectId: Long,
        sampleText: String,
        provider: ApiProviderEntity
    ): List<GlossaryEntity> {
        val prompt = TranslationPrompts.buildTermExtractionPrompt(sampleText)

        val result = llmClient.executeCompletion(
            provider = provider,
            systemPrompt = "You are a specialized novel terminologist. Output valid JSON array only.",
            userPrompt = prompt,
            temperature = 0.3f
        )

        if (!result.isSuccess || result.text.isBlank()) {
            return emptyList()
        }

        val terms = mutableListOf<GlossaryEntity>()
        try {
            var jsonStr = result.text.trim()
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.substringAfter("```json").substringBeforeLast("```").trim()
            } else if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.substringAfter("```").substringBeforeLast("```").trim()
            }

            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val orig = obj.optString("original", "").trim()
                val sugg = obj.optString("suggested", "").trim()
                val catStr = obj.optString("category", "CHARACTER").trim().uppercase()
                val notes = obj.optString("notes", "").trim()

                if (orig.isNotBlank() && sugg.isNotBlank()) {
                    val category = try {
                        TermCategory.valueOf(catStr)
                    } catch (e: Exception) {
                        TermCategory.CHARACTER
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
        } catch (e: Exception) {
            // Error parsing JSON
        }
        return terms
    }
}
