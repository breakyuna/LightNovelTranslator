package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.data.model.LexiconCandidateVoting
import com.breakyuna.noveltranslator.data.model.TermCategory
import java.util.Locale

data class TermValidationRejection(
    val sourceTerm: String,
    val reason: String
)

sealed interface TermValidationResult {
    data class Accepted(
        val originalTerm: String,
        val translatedTerm: String,
        val category: TermCategory,
        val notes: String
    ) : TermValidationResult

    data class Rejected(val rejection: TermValidationRejection) : TermValidationResult
}

/**
 * Code-level guardrails for model terminology output. Prompt rules improve recall, while these
 * checks prevent an invalid model response from becoming either a candidate or a glossary rule.
 */
object TermCandidateValidator {
    const val MAX_SOURCE_TERM_LENGTH = 120
    const val MAX_TARGET_TERM_LENGTH = 160
    const val MAX_NOTES_LENGTH = 300

    val allowedCategories: Set<String> = LexiconCandidateVoting.aiCategories

    fun validate(
        original: String,
        suggested: String,
        categoryRaw: String,
        notes: String = "",
        sourceText: String? = null
    ): TermValidationResult {
        val source = original.trim()
        val target = suggested.trim()
        val category = categoryRaw.trim().uppercase(Locale.ROOT)
        val cleanNotes = notes.trim().take(MAX_NOTES_LENGTH)

        fun reject(reason: String): TermValidationResult = TermValidationResult.Rejected(
            TermValidationRejection(source, reason)
        )

        if (source.isBlank()) return reject("original is blank")
        if (target.isBlank()) return reject("suggested is blank")
        if (sourceText != null && !sourceText.contains(source)) {
            return reject("original is not an exact substring of the scan window")
        }
        if (source.length > MAX_SOURCE_TERM_LENGTH) return reject("original exceeds $MAX_SOURCE_TERM_LENGTH characters")
        if (target.length > MAX_TARGET_TERM_LENGTH) return reject("suggested exceeds $MAX_TARGET_TERM_LENGTH characters")
        if (source.any { it.code < 32 || it.code == 127 }) return reject("original contains control characters")
        if (target.any { it.code < 32 || it.code == 127 }) return reject("suggested contains control characters")
        if (cleanNotes.any { it.code < 32 || it.code == 127 }) return reject("notes contain control characters")
        if (source.none(Char::isLetterOrDigit)) return reject("original has no letters or digits")
        if (source.filter(Char::isLetterOrDigit).all(Char::isDigit)) return reject("original is numeric only")
        if (target.none(Char::isLetterOrDigit)) return reject("suggested has no letters or digits")
        if (target.filter(Char::isLetterOrDigit).all(Char::isDigit)) return reject("suggested is numeric only")
        if (category !in allowedCategories) return reject("unsupported AI category '$category'")
        val unchanged = LexiconCandidateVoting.normalizeSourceTerm(source) ==
            LexiconCandidateVoting.normalizeSourceTerm(target)
        val ordinaryLookingUnchangedAscii = unchanged && source.any(Char::isLetter) && source.all { char ->
            char.code < 128 && (char.isLowerCase() || !char.isLetterOrDigit())
        }
        if (ordinaryLookingUnchangedAscii) {
            return reject("unchanged ordinary-looking ASCII term requires stronger naming evidence")
        }

        return TermValidationResult.Accepted(
            originalTerm = source,
            translatedTerm = target,
            category = TermCategory.valueOf(category),
            notes = cleanNotes
        )
    }
}
