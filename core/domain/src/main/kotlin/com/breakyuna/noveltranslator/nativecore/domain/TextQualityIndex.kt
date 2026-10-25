package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Collections

enum class TextQualityOutcome { ALL, NOT_EVALUATED, NO_WARNING, ANY_WARNING }

data class TextQualityFilter(val outcome: TextQualityOutcome = TextQualityOutcome.ALL,
    val warning: TextWarningCode? = null, val termRuleId: String? = null,
    /** true selects items where the term occurred; false selects missing-term evidence. */
    val termMatched: Boolean? = null) {
    init {
        require(outcome == TextQualityOutcome.ANY_WARNING || warning == null)
        require(termMatched == null || termRuleId != null)
        require(termRuleId == null || termRuleId.isNotBlank() && termRuleId.length <= 128)
        require(termMatched == null || termRuleId != null)
    }
    fun matches(fact: TextQualityFact): Boolean {
        val termEvidence = termRuleId == null || when (termMatched) {
            true -> (fact.result.termHits[termRuleId] ?: 0) > 0
            false -> termRuleId in fact.result.missingTermRuleIds
            null -> termRuleId in fact.result.missingTermRuleIds
        }
        return when (outcome) {
            TextQualityOutcome.ALL -> termEvidence
            TextQualityOutcome.NOT_EVALUATED -> !fact.result.evaluated && termEvidence
            TextQualityOutcome.NO_WARNING -> fact.result.evaluated && fact.result.codes.isEmpty() && termEvidence
            TextQualityOutcome.ANY_WARNING -> fact.result.codes.isNotEmpty() &&
                (warning == null || warning in fact.result.codes) && termEvidence
        }
    }
    override fun toString(): String = "TextQualityFilter(outcome=$outcome, warning=$warning)"
}

class TextQualityFact(val id: ItemId, val fingerprint: ObjectFingerprint, val result: TextProofreadingResult)

data class TextQualityScanProgress(val scanned: Int, val total: Int) {
    init { require(scanned in 0..total) }
}

/** Ephemeral metadata only. Never serialize to saved-instance state or treat as write authority. */
class TextQualityIndex(val ref: LocalProjectRef, val revision: Long, val query: TextItemQuery,
    val options: TextProofreadingOptions, val policyFingerprint: ObjectFingerprint,
    facts: List<TextQualityFact>, val scanned: Int) {
    val facts: List<TextQualityFact> = Collections.unmodifiableList(ArrayList(facts))
    val warningCounts: Map<TextWarningCode, Int>
    val missingTermCounts: Map<String, Int>
    val termHitCounts: Map<String, Int>
    val termHitItemCounts: Map<String, Int>
    val evaluatedCount: Int
    val cleanCount: Int
    init {
        require(revision >= 0 && scanned in 0..100_000 && facts.size <= scanned)
        require(this.facts.zipWithNext().all { (a, b) -> a.id.value < b.id.value })
        require(this.facts.all { it.result.policyFingerprint == policyFingerprint && it.result.options == options })
        evaluatedCount = this.facts.count { it.result.evaluated }
        cleanCount = this.facts.count { it.result.evaluated && it.result.codes.isEmpty() }
        val warnings = linkedMapOf<TextWarningCode, Int>()
        for (code in TextWarningCode.entries) warnings[code] = this.facts.count { code in it.result.codes }
        warningCounts = Collections.unmodifiableMap(warnings)
        val terms = linkedMapOf<String, Int>()
        for (fact in this.facts) for (id in fact.result.missingTermRuleIds) terms[id] = (terms[id] ?: 0) + 1
        missingTermCounts = Collections.unmodifiableMap(terms)
        val hits = linkedMapOf<String, Int>()
        val hitItems = linkedMapOf<String, Int>()
        for (fact in this.facts) for ((id, count) in fact.result.termHits) {
            hits[id] = (hits[id] ?: 0) + count
            hitItems[id] = (hitItems[id] ?: 0) + 1
        }
        termHitCounts = Collections.unmodifiableMap(hits)
        termHitItemCounts = Collections.unmodifiableMap(hitItems)
    }
    fun fact(id: ItemId): TextQualityFact? = facts.binarySearchBy(id.value) { it.id.value }.let {
        if (it >= 0) facts[it] else null
    }
    fun select(filter: TextQualityFilter, checkActive: () -> Unit = {}): TextQualitySelection {
        val ids = ArrayList<ItemId>()
        for ((i, fact) in facts.withIndex()) {
            if (i % 128 == 0) checkActive()
            if (filter.matches(fact)) ids.add(fact.id)
        }
        return TextQualitySelection(this, filter, ids)
    }
}

class TextQualitySelection internal constructor(val index: TextQualityIndex, val filter: TextQualityFilter, ids: List<ItemId>) {
    val ids: List<ItemId> = Collections.unmodifiableList(ArrayList(ids))
    fun window(afterId: Long, limit: Int): List<ItemId> {
        require(afterId >= 0 && limit in 1..100)
        val found = ids.binarySearchBy(afterId) { it.value }
        val start = if (found >= 0) found + 1 else -found - 1
        return ids.subList(start, minOf(start + limit, ids.size))
    }
}
