package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*

/** Read-only, bounded TXT-body checks. Never writes, calls a model, or silently suppresses failures. */
class TextProofreadingInspector(private val policy: ProjectTextPolicy, private val checkActive: () -> Unit = {}) {
    private val processor by lazy {
        TextRuleValidation.validate(policy, checkActive)
        TextPolicyProcessor(policy, checkActive)
    }
    private val terms by lazy {
        policy.rules.filter { it.enabled && it.kind == TextRuleKind.TERM && it.target.isNotEmpty() }.map {
            checkActive(); TextRuleValidation.compile(it)
        }
    }
    fun inspect(source: String, translation: String, status: TextItemStatus,
        options: TextProofreadingOptions = TextProofreadingOptions(),
        nameSource: List<String> = emptyList(), nameTranslation: List<String> = emptyList()): TextProofreadingResult {
        require(source.length <= 65_536 && translation.length <= 65_536)
        for (text in listOf(source, translation)) { require('\u0000' !in text); TextProjectFingerprint.utf8(text) }
        require(nameSource.size <= 32 && nameTranslation.size <= 32)
        nameSource.forEach { require(it.length <= 4_096 && '\u0000' !in it); TextProjectFingerprint.utf8(it) }
        nameTranslation.forEach { require(it.length <= 4_096 && '\u0000' !in it); TextProjectFingerprint.utf8(it) }
        checkActive()
        val processor = this.processor
        val codes = mutableSetOf<TextWarningCode>()
        val missing = mutableListOf<String>()
        val hits = linkedMapOf<String, Int>()
        fun result(evaluated: Boolean) = TextProofreadingResult(codes, missing, evaluated, policy.fingerprint(), options, hits)
        if (status != TextItemStatus.PROCESSED || translation.isBlank()) return result(false)
        val prepared = processor.prepareProofreading(source)
        val left = processor.proofreadingSource(prepared)
        val right = processor.proofreadingTranslation(translation, prepared.budget)
        if (left.preserved != right.preserved) codes.add(TextWarningCode.TEXT_PRESERVE)
        if (lineCount(source) != lineCount(translation)) codes.add(TextWarningCode.LINE_COUNT_MISMATCH)
        if (punctuation(left.natural) != punctuation(right.natural)) codes.add(TextWarningCode.PUNCTUATION_MISMATCH)
        val residue = hasForeignResidue(right.natural, options.target)
        if (residue) codes.add(TextWarningCode.FOREIGN_CHAR_RESIDUE)
        val sharedHan = options.target in listOf(ProofreadingLanguage.ZH, ProofreadingLanguage.ZH_HANT) &&
            options.source in listOf(ProofreadingLanguage.JA, ProofreadingLanguage.KO)
        if ((!sharedHan || residue) && similar(left.natural, right.natural, prepared.budget)) codes.add(TextWarningCode.SIMILARITY)
        for (compiled in terms) {
            checkActive()
            val rule = compiled.rule
            val bodyHit = rule.scope != TextRuleScope.NAME &&
                count(compiled, source, prepared.budget).also { if (it > 0) hits[rule.id] = it } > 0
            if (bodyHit && !contains(translation, rule.target, !rule.caseSensitive, prepared.budget))
                missing.add(rule.id)
            if (rule.scope != TextRuleScope.BODY) {
                val sources = if (nameSource.isEmpty()) emptyList() else nameSource
                val translations = if (nameTranslation.isEmpty()) nameSource else nameTranslation
                var nameOccurrences = 0
                var nameMissing = false
                for ((index, name) in sources.withIndex()) {
                    val occurrences = count(compiled, name, prepared.budget)
                    nameOccurrences += occurrences
                    if (occurrences > 0) {
                        val translated = translations.getOrNull(index).orEmpty()
                        if (!contains(translated, rule.target, !rule.caseSensitive, prepared.budget)) nameMissing = true
                    }
                }
                if (nameOccurrences > 0) {
                    hits[rule.id] = (hits[rule.id] ?: 0) + nameOccurrences
                    if (nameMissing && rule.id !in missing) missing.add(rule.id)
                }
            }
        }
        if (missing.isNotEmpty()) codes.add(TextWarningCode.GLOSSARY)
        checkActive()
        return result(true)
    }

    private fun lineCount(text: String): Int {
        var count = 1; var previous = '\u0000'
        for ((i, c) in text.withIndex()) {
            if (i % 256 == 0) checkActive()
            if (c == '\r' || c == '\n' && previous != '\r') count++
            previous = c
        }
        return count
    }

    private fun punctuation(text: String): List<Int> = buildList {
        for ((i, c) in text.withIndex()) {
            if (i % 256 == 0) checkActive()
            val group = PUNCTUATION.indexOfFirst { c in it }
            if (group >= 0) add(group)
        }
    }

    private fun similar(source: String, translation: String, budget: TextPolicyProcessor.RuleBudget): Boolean {
        val left = source.trim(); val right = translation.trim()
        if (left.isEmpty() || right.isEmpty()) return false
        if (contains(left, right, false, budget) || contains(right, left, false, budget)) return true
        fun characters(text: String): Set<Int> {
            val result = mutableSetOf<Int>(); var i = 0
            while (i < text.length) { checkActive(); val point = text.codePointAt(i); result.add(point); i += Character.charCount(point) }
            return result
        }
        val a = characters(left); val b = characters(right)
        val intersection = a.count { it in b }
        return intersection.toDouble() / (a.size + b.size - intersection) > 0.8
    }

    private fun count(compiled: CompiledTextRule,
        text: String, budget: TextPolicyProcessor.RuleBudget): Int {
        val matcher = compiled.pattern.matcher(TextPolicyProcessor.BoundedSequence(text, budget))
        var count = 0
        while (matcher.find()) {
            if (++count > 65_537) throw InvalidTextRule(compiled.rule.id, InvalidTextRule.Reason.LIMIT)
        }
        return count
    }

    /** KMP keeps long repetitive drafts cancellable; platform substring scans have no work hook. */
    private fun contains(text: String, needle: String, ignoreCase: Boolean,
        budget: TextPolicyProcessor.RuleBudget): Boolean {
        if (needle.isEmpty()) return true
        if (needle.length > text.length) return false
        fun equal(a: Char, b: Char): Boolean { budget.charge(); return a.equals(b, ignoreCase) }
        val prefix = IntArray(needle.length)
        var matched = 0
        for (i in 1 until needle.length) {
            while (matched > 0 && !equal(needle[i], needle[matched])) matched = prefix[matched - 1]
            if (equal(needle[i], needle[matched])) matched++
            prefix[i] = matched
        }
        matched = 0
        for (c in text) {
            while (matched > 0 && !equal(c, needle[matched])) matched = prefix[matched - 1]
            if (equal(c, needle[matched])) matched++
            if (matched == needle.length) return true
        }
        return false
    }

    /** Conservative script heuristic; code-point groups, not LG's Intl grapheme segmenter. */
    private fun hasForeignResidue(text: String, target: ProofreadingLanguage): Boolean {
        var latin = 0; var shortUpper = true
        fun flush(): Boolean {
            val found = latin > 1 && !(latin in 2..4 && shortUpper)
            latin = 0; shortUpper = true
            return found
        }
        var i = 0
        while (i < text.length) {
            checkActive()
            val point = text.codePointAt(i); i += Character.charCount(point)
            val isLatin = point in 0x41..0x5a || point in 0x61..0x7a || point in 0xc0..0x24f || point in 0x1e00..0x1eff
            val isHan = point in 0x3400..0x4dbf || point in 0x4e00..0x9fff || point in 0xf900..0xfaff || point in 0x20000..0x323af
            val isKana = point in 0x3040..0x30ff || point in 0x31f0..0x31ff || point in 0xff66..0xff9d
            val isHangul = point in 0x1100..0x11ff || point in 0x3130..0x318f || point in 0xac00..0xd7af
            if (isLatin && Character.isLetter(point) && target !in listOf(ProofreadingLanguage.EN, ProofreadingLanguage.DE)) {
                latin++; shortUpper = shortUpper && point in 0x41..0x5a; continue
            }
            // Combining marks stay with a Latin fragment, and prevent the ASCII acronym exemption.
            if (latin > 0 && Character.getType(point) in listOf(Character.NON_SPACING_MARK.toInt(),
                    Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())) { shortUpper = false; continue }
            if (flush()) return true
            if (isHan && Character.isLetter(point) && target !in listOf(ProofreadingLanguage.ZH, ProofreadingLanguage.ZH_HANT, ProofreadingLanguage.JA)) return true
            if (Character.isLetter(point) && (isKana && target != ProofreadingLanguage.JA || isHangul && target != ProofreadingLanguage.KO)) return true
        }
        return flush()
    }

    companion object {
        private val PUNCTUATION = listOf("“”„‟「」『』«»\"＂", "(（", ")）", "[［【", "]］】", "{｛", "}｝", "《", "》")
    }
}
