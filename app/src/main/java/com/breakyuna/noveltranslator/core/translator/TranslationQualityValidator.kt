package com.breakyuna.noveltranslator.core.translator

import com.breakyuna.noveltranslator.core.translation.GlossaryQaStatus
import com.breakyuna.noveltranslator.data.model.GlossaryEntity
import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.ReviewStatus

data class TranslationValidation(
    val isAcceptable: Boolean,
    val problems: List<String>,
    val glossaryStatus: GlossaryQaStatus = GlossaryQaStatus.NONE
)

object TranslationQualityValidator {
    private val refusalSignals = listOf(
        "i can't translate",
        "i cannot translate",
        "unable to translate",
        "cannot assist with",
        "作为一个ai",
        "无法翻译",
        "不能翻译"
    )
    private val imageRegex = Regex("\\[IMG:[^]]+]", RegexOption.IGNORE_CASE)

    fun validate(
        source: String,
        translation: String,
        glossary: List<GlossaryEntity> = emptyList()
    ): TranslationValidation {
        val problems = mutableListOf<String>()
        val trimmed = translation.trim()
        if (trimmed.isBlank()) problems += "empty output"
        if (trimmed.contains("```")) problems += "unexpected Markdown code fence"
        if (refusalSignals.any { trimmed.contains(it, ignoreCase = true) }) problems += "model refusal text"

        val sourceImages = imageRegex.findAll(source).map { it.value }.toList()
        val translatedImages = imageRegex.findAll(translation).map { it.value }.toList()
        if (sourceImages != translatedImages) problems += "image markers changed or missing"

        val sourceParagraphs = paragraphs(source).count { !imageRegex.matches(it) }
        val translatedParagraphs = paragraphs(translation).count { !imageRegex.matches(it) }
        if (sourceParagraphs > 1) {
            val tolerance = maxOf(1, (sourceParagraphs * 0.2).toInt())
            if (kotlin.math.abs(sourceParagraphs - translatedParagraphs) > tolerance) {
                problems += "paragraph coverage mismatch ($sourceParagraphs source vs $translatedParagraphs translated)"
            }
        }

        val sourceLength = source.replace(imageRegex, "").count { !it.isWhitespace() }
        val translatedLength = translation.replace(imageRegex, "").count { !it.isWhitespace() }
        if (sourceLength > 200) {
            val ratio = translatedLength.toDouble() / sourceLength.toDouble()
            val sourceCjkRatio = source.count { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }
                .toDouble() / sourceLength.toDouble()
            // Character expansion differs substantially between CJK and alphabetic languages.
            val minimumRatio = if (sourceCjkRatio >= 0.30) 0.12 else 0.20
            val maximumRatio = if (sourceCjkRatio >= 0.30) 8.0 else 5.0
            if (ratio < minimumRatio) problems += "translation is suspiciously short"
            if (ratio > maximumRatio) problems += "translation is suspiciously long"
        }

        val activeGlossary = glossary
            .filter { it.reviewStatus == ReviewStatus.CONFIRMED.name }
            .distinctBy { LexiconCandidateVoting.normalizeSourceTerm(it.originalTerm) }
            .filter {
                it.originalTerm.isNotBlank() &&
                    it.translatedTerm.isNotBlank() &&
                    source.contains(it.originalTerm.trim(), ignoreCase = true)
            }
        val missingGlossary = activeGlossary.filterNot { entry ->
            translation.contains(entry.translatedTerm.trim(), ignoreCase = true)
        }
        missingGlossary.forEach {
            problems += "GLOSSARY_MISSING: ${it.originalTerm.trim()} -> ${it.translatedTerm.trim()}"
        }
        val appliedCount = activeGlossary.size - missingGlossary.size
        val glossaryStatus = when {
            activeGlossary.isEmpty() -> GlossaryQaStatus.NONE
            appliedCount == activeGlossary.size -> GlossaryQaStatus.APPLIED
            appliedCount == 0 -> GlossaryQaStatus.MISSING
            else -> GlossaryQaStatus.PARTIAL
        }

        return TranslationValidation(problems.distinct().isEmpty(), problems.distinct(), glossaryStatus)
    }

    private fun paragraphs(text: String): List<String> = text
        .split(Regex("\\r?\\n\\s*\\r?\\n+"))
        .map(String::trim)
        .filter(String::isNotBlank)
}
