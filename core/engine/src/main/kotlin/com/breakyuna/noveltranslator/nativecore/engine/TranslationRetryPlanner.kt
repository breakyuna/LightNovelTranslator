package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import java.util.Collections

enum class TranslationFailureClass {
    TRANSIENT,
    AUTHENTICATION,
    CONTEXT,
    PROTOCOL,
    CONTENT,
    CONTENT_FILTER,
    EMPTY_RESPONSE,
    UNKNOWN,
}

enum class TranslationRetryOutcome { COMPLETE, RETRY, SPLIT, PERMANENT }

/** Auditable reason for the decision. Model wording never selects the rule. */
enum class TranslationRetryRule {
    COMPLETE,
    RETRY_TRANSIENT,
    RETRY_SINGLE_ITEM,
    SPLIT_BATCH,
    PERMANENT_AUTHENTICATION,
    PERMANENT_CONTEXT_OVERFLOW,
    PERMANENT_CONTENT_FILTER,
    PERMANENT_CONTENT,
    PERMANENT_UNKNOWN,
    PERMANENT_ATTEMPTS_EXHAUSTED,
    PERMANENT_SPLIT_EXHAUSTED,
}

/** Fixed retry ceilings. They are policy, not a provider capability probe. */
data class TranslationRetryBudget(
    val maxAttempts: Int = 3,
    val singleItemRetries: Int = 1,
    val maxSplitDepth: Int = 5,
    val baseBackoffMillis: Long = 500,
    val maxBackoffMillis: Long = 30_000,
) {
    init {
        require(maxAttempts in 1..10) { "Transient attempts are 1..10" }
        require(singleItemRetries in 0..5) { "Single-item retries are 0..5" }
        require(maxSplitDepth in 0..16) { "Split depth is 0..16" }
        require(baseBackoffMillis in 1L..60_000L) { "Base backoff is 1..60000 ms" }
        require(maxBackoffMillis in baseBackoffMillis..600_000L) { "Maximum backoff must cover the base" }
    }
}

/** One item still needing work, with the retry counters of the chunk it came from. */
class TranslationRetryContext(
    items: List<TranslationItemSnapshot>,
    val attempts: Int = 0,
    val splitDepth: Int = 0,
) {
    val items: List<TranslationItemSnapshot> = Collections.unmodifiableList(ArrayList(items))

    init {
        require(this.items.size in 1..20) { "A retry context holds 1..20 items" }
        require(this.items.map { it.id }.toSet().size == this.items.size) { "Duplicate retry items" }
        require(attempts in 0..1_000_000) { "Attempt count is out of range" }
        require(splitDepth in 0..16) { "Split depth is 0..16" }
    }
}

class TranslationRetryBlock internal constructor(
    items: List<TranslationItemSnapshot>,
    val attempts: Int,
    val splitDepth: Int,
) {
    val items: List<TranslationItemSnapshot> = Collections.unmodifiableList(ArrayList(items))

    init {
        require(this.items.size in 1..20) { "A retry block holds 1..20 items" }
        require(this.items.map { it.id }.toSet().size == this.items.size) { "Duplicate retry block items" }
        require(attempts >= 0 && splitDepth in 0..16) { "Invalid retry counters" }
    }

    fun itemIds(): List<ItemId> = items.map { it.id }
    override fun toString(): String =
        "TranslationRetryBlock(items=${items.size}, attempts=$attempts, splitDepth=$splitDepth)"
}

/**
 * A decision only; the caller still has to schedule the blocks and persist the terminal statuses.
 * Committed items never appear in [retryBlocks] or [permanentItemIds].
 */
class TranslationRetryDecision internal constructor(
    val outcome: TranslationRetryOutcome,
    val rule: TranslationRetryRule,
    val failure: ModelFailureKind,
    val failureClass: TranslationFailureClass,
    retryBlocks: List<TranslationRetryBlock>,
    permanentItemIds: List<ItemId>,
    committedItemIds: List<ItemId>,
    val backoffMillis: Long,
) {
    val retryBlocks: List<TranslationRetryBlock> = Collections.unmodifiableList(ArrayList(retryBlocks))
    val permanentItemIds: List<ItemId> = Collections.unmodifiableList(ArrayList(permanentItemIds))
    val committedItemIds: List<ItemId> = Collections.unmodifiableList(ArrayList(committedItemIds))

    init {
        require(backoffMillis >= 0) { "Backoff must not be negative" }
        val retried = HashSet<ItemId>()
        for (block in this.retryBlocks) for (item in block.items) {
            require(retried.add(item.id)) { "An item is scheduled twice" }
        }
        val permanent = HashSet<ItemId>(this.permanentItemIds)
        require(permanent.size == this.permanentItemIds.size) { "Duplicate permanent items" }
        val committed = HashSet<ItemId>(this.committedItemIds)
        require(committed.size == this.committedItemIds.size) { "Duplicate committed items" }
        require(retried.none { it in permanent } && retried.none { it in committed } &&
            permanent.none { it in committed }) { "Outcomes must be disjoint" }
        require(this.permanentItemIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Permanent identities must be sorted"
        }
        require(this.committedItemIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Committed identities must be sorted"
        }
        when (outcome) {
            TranslationRetryOutcome.COMPLETE -> require(this.retryBlocks.isEmpty() && permanent.isEmpty()) {
                "A complete decision has no work"
            }

            TranslationRetryOutcome.RETRY -> require(this.retryBlocks.isNotEmpty() && permanent.isEmpty()) {
                "A retry decision retries the same work"
            }

            TranslationRetryOutcome.SPLIT -> require(this.retryBlocks.size >= 2 && permanent.isEmpty()) {
                "A split decision produces at least two blocks"
            }

            TranslationRetryOutcome.PERMANENT -> require(this.retryBlocks.isEmpty() && permanent.isNotEmpty()) {
                "A permanent decision retries nothing"
            }
        }
        if (outcome == TranslationRetryOutcome.RETRY) require(backoffMillis > 0) {
            "A retry carries a positive backoff"
        }
    }

    fun retryItemIds(): List<ItemId> = retryBlocks.flatMap { it.itemIds() }
    override fun toString(): String =
        "TranslationRetryDecision(outcome=$outcome, rule=$rule, failure=$failure, retries=${retryBlocks.size}, permanent=${permanentItemIds.size})"
}

/**
 * Ports LG's conservative convergence policy and makes every step auditable:
 * non-transient protocol/context failures halve the batch down to one item, a single item gets
 * a bounded retry, and authentication/content-filter/unknown failures are terminal immediately.
 * Committed rows are filtered before any decision, so a lost response can never cause a second
 * paid request for a row the project already accepted.
 */
class TranslationRetryPlanner(
    private val budget: TranslationRetryBudget = TranslationRetryBudget(),
) {
    fun decide(
        context: TranslationRetryContext,
        failure: ModelFailureKind,
        committedItemIds: Set<ItemId> = emptySet(),
    ): TranslationRetryDecision {
        val contextIds = context.items.mapTo(HashSet<ItemId>()) { it.id }
        require(contextIds.containsAll(committedItemIds)) {
            "A committed identity is not part of the retry context"
        }
        val committed = context.items.filter { it.id in committedItemIds }.map { it.id }.sortedBy { it.value }
        val pending = context.items.filter { it.id !in committedItemIds }
        if (pending.isEmpty()) {
            return TranslationRetryDecision(TranslationRetryOutcome.COMPLETE, TranslationRetryRule.COMPLETE,
                failure, classify(failure), emptyList(), emptyList(), committed, 0)
        }

        val failureClass = classify(failure)
        return when (failureClass) {
            TranslationFailureClass.TRANSIENT, TranslationFailureClass.EMPTY_RESPONSE ->
                retryTransient(context, pending, committed, failure, failureClass)

            TranslationFailureClass.AUTHENTICATION -> permanent(pending, committed, failure, failureClass,
                TranslationRetryRule.PERMANENT_AUTHENTICATION)

            TranslationFailureClass.CONTENT_FILTER -> permanent(pending, committed, failure, failureClass,
                TranslationRetryRule.PERMANENT_CONTENT_FILTER)

            TranslationFailureClass.UNKNOWN -> permanent(pending, committed, failure, failureClass,
                TranslationRetryRule.PERMANENT_UNKNOWN)

            TranslationFailureClass.CONTEXT -> when {
                pending.size > 1 && context.splitDepth < budget.maxSplitDepth ->
                    split(context, pending, committed, failure, failureClass)

                pending.size > 1 -> permanent(pending, committed, failure, failureClass,
                    TranslationRetryRule.PERMANENT_SPLIT_EXHAUSTED)

                else -> permanent(pending, committed, failure, failureClass,
                    TranslationRetryRule.PERMANENT_CONTEXT_OVERFLOW)
            }

            TranslationFailureClass.PROTOCOL -> when {
                pending.size > 1 && context.splitDepth < budget.maxSplitDepth ->
                    split(context, pending, committed, failure, failureClass)

                pending.size > 1 -> permanent(pending, committed, failure, failureClass,
                    TranslationRetryRule.PERMANENT_SPLIT_EXHAUSTED)

                context.attempts >= budget.singleItemRetries -> permanent(pending, committed, failure,
                    failureClass, TranslationRetryRule.PERMANENT_ATTEMPTS_EXHAUSTED)

                else -> retrySingle(context, pending, committed, failure, failureClass)
            }

            TranslationFailureClass.CONTENT -> when {
                pending.size > 1 && context.splitDepth < budget.maxSplitDepth ->
                    split(context, pending, committed, failure, failureClass)

                pending.size > 1 -> permanent(pending, committed, failure, failureClass,
                    TranslationRetryRule.PERMANENT_SPLIT_EXHAUSTED)

                else -> permanent(pending, committed, failure, failureClass,
                    TranslationRetryRule.PERMANENT_CONTENT)
            }
        }
    }

    private fun retryTransient(
        context: TranslationRetryContext,
        pending: List<TranslationItemSnapshot>,
        committed: List<ItemId>,
        failure: ModelFailureKind,
        failureClass: TranslationFailureClass,
    ): TranslationRetryDecision {
        if (context.attempts >= budget.maxAttempts) {
            return permanent(pending, committed, failure, failureClass,
                TranslationRetryRule.PERMANENT_ATTEMPTS_EXHAUSTED)
        }
        val attempt = context.attempts + 1
        val block = TranslationRetryBlock(pending, attempt, context.splitDepth)
        return TranslationRetryDecision(TranslationRetryOutcome.RETRY, TranslationRetryRule.RETRY_TRANSIENT,
            failure, failureClass, listOf(block), emptyList(), committed, backoff(attempt))
    }

    private fun retrySingle(
        context: TranslationRetryContext,
        pending: List<TranslationItemSnapshot>,
        committed: List<ItemId>,
        failure: ModelFailureKind,
        failureClass: TranslationFailureClass,
    ): TranslationRetryDecision {
        val attempt = context.attempts + 1
        val block = TranslationRetryBlock(pending, attempt, context.splitDepth)
        return TranslationRetryDecision(TranslationRetryOutcome.RETRY, TranslationRetryRule.RETRY_SINGLE_ITEM,
            failure, failureClass, listOf(block), emptyList(), committed, backoff(attempt))
    }

    private fun split(
        context: TranslationRetryContext,
        pending: List<TranslationItemSnapshot>,
        committed: List<ItemId>,
        failure: ModelFailureKind,
        failureClass: TranslationFailureClass,
    ): TranslationRetryDecision {
        val size = (pending.size + 1) / 2
        val first = pending.subList(0, size)
        val second = pending.subList(size, pending.size)
        check(first.isNotEmpty() && second.isNotEmpty()) { "A split requires two non-empty halves" }
        fun block(items: List<TranslationItemSnapshot>): TranslationRetryBlock = TranslationRetryBlock(
            items,
            attempts = if (items.size == 1) 0 else context.attempts + 1,
            splitDepth = context.splitDepth + 1,
        )
        return TranslationRetryDecision(TranslationRetryOutcome.SPLIT, TranslationRetryRule.SPLIT_BATCH,
            failure, failureClass, listOf(block(first), block(second)), emptyList(), committed, 0)
    }

    private fun permanent(
        pending: List<TranslationItemSnapshot>,
        committed: List<ItemId>,
        failure: ModelFailureKind,
        failureClass: TranslationFailureClass,
        rule: TranslationRetryRule,
    ): TranslationRetryDecision = TranslationRetryDecision(TranslationRetryOutcome.PERMANENT, rule,
        failure, failureClass, emptyList(), pending.map { it.id }.sortedBy { it.value }, committed, 0)

    private fun backoff(attempt: Int): Long {
        var value = budget.baseBackoffMillis
        var remaining = attempt - 1
        while (remaining > 0 && value < budget.maxBackoffMillis) {
            value = if (value > budget.maxBackoffMillis / 2) budget.maxBackoffMillis else value * 2
            remaining--
        }
        return minOf(value, budget.maxBackoffMillis)
    }

    private fun classify(failure: ModelFailureKind): TranslationFailureClass = when (failure) {
        ModelFailureKind.NETWORK,
        ModelFailureKind.TIMEOUT,
        ModelFailureKind.RATE_LIMIT,
        ModelFailureKind.SERVER -> TranslationFailureClass.TRANSIENT

        ModelFailureKind.AUTHENTICATION -> TranslationFailureClass.AUTHENTICATION
        ModelFailureKind.CONTEXT_OVERFLOW -> TranslationFailureClass.CONTEXT
        ModelFailureKind.PARSING, ModelFailureKind.TRUNCATED -> TranslationFailureClass.PROTOCOL
        ModelFailureKind.CONTENT -> TranslationFailureClass.CONTENT
        ModelFailureKind.CONTENT_FILTERED -> TranslationFailureClass.CONTENT_FILTER
        ModelFailureKind.EMPTY_RESPONSE -> TranslationFailureClass.EMPTY_RESPONSE
        ModelFailureKind.UNKNOWN -> TranslationFailureClass.UNKNOWN
    }
}
