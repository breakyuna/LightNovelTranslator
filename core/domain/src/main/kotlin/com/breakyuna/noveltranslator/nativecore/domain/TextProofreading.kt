package com.breakyuna.noveltranslator.nativecore.domain

/** The order is shared by the inspector and every result consumer. */
enum class TextWarningCode {
    FOREIGN_CHAR_RESIDUE, SIMILARITY, LINE_COUNT_MISMATCH, GLOSSARY, TEXT_PRESERVE, PUNCTUATION_MISMATCH,
}

enum class ProofreadingLanguage(val tag: String) {
    AUTO("auto"), ZH("zh"), ZH_HANT("zh-Hant"), JA("ja"), KO("ko"), EN("en"), DE("de"),
}

data class TextProofreadingOptions(
    val source: ProofreadingLanguage = ProofreadingLanguage.AUTO,
    val target: ProofreadingLanguage = ProofreadingLanguage.ZH,
) {
    init { require(target != ProofreadingLanguage.AUTO) }
}

/** No novel excerpts or editor positions: rule IDs only, bounded by the policy limit. */
class TextProofreadingResult(
    codes: Collection<TextWarningCode>, missingTermRuleIds: List<String>,
    val evaluated: Boolean, val policyFingerprint: ObjectFingerprint,
    val options: TextProofreadingOptions,
) {
    val codes: List<TextWarningCode> = java.util.Collections.unmodifiableList(
        TextWarningCode.entries.filter { it in codes })
    val missingTermRuleIds: List<String> = java.util.Collections.unmodifiableList(ArrayList(missingTermRuleIds))
    init {
        require(this.missingTermRuleIds.size <= 256 && this.missingTermRuleIds.distinct().size == this.missingTermRuleIds.size)
        require(this.missingTermRuleIds.all { it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl) })
        require(evaluated || this.codes.isEmpty() && this.missingTermRuleIds.isEmpty())
    }
    override fun toString(): String = "TextProofreadingResult(evaluated=$evaluated, codes=$codes)"
}
