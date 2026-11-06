package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*

/** One original planning unit owns its retry budget, even after it is split into children. */
class TranslationRetrySession private constructor(
    snapshots: List<TranslationItemSnapshot>, initial: TranslationRetryCheckpoint,
) {
    private val snapshots = snapshots.associateBy { it.id }
    var checkpoint = initial
        private set
    private val planner = TranslationRetryPlanner(TranslationRetryBudget(
        maxAttempts = initial.initialAdditional.coerceAtLeast(1), maxSplitDepth = initial.initialAdditional))
    init {
        require(snapshots.size == this.snapshots.size)
        val needed = initial.pending.flatMap { it.ids } + initial.active?.ids.orEmpty()
        require(needed.all { id ->
            this.snapshots[id]?.fingerprint == initial.targets.single { it.id == id }.fingerprint
        })
    }
    val pending: Boolean get() = checkpoint.pending.isNotEmpty()
    fun next(): TranslationRetryContext {
        check(checkpoint.active == null && checkpoint.terminalFailure == null)
        val head = checkpoint.pending.first()
        return TranslationRetryContext(head.ids.map { snapshots.getValue(it) }, head.attempts, head.splitDepth)
    }
    fun begin(attempt: RequestAttemptId) {
        check(checkpoint.active == null && checkpoint.terminalFailure == null)
        val head = checkpoint.pending.first()
        replace(checkpoint.pending.drop(1), head, attempt, null, 0)
    }
    fun succeeded() {
        check(checkpoint.active != null && checkpoint.terminalFailure == null)
        replace(checkpoint.pending, null, null, null, 0)
    }
    /** The replacement request allowance is charged before the delay/next dispatch. */
    fun failed(failure: ModelFailureKind, nowMillis: Long): TranslationRetryDecision {
        require(nowMillis >= 0)
        check(checkpoint.terminalFailure == null)
        val active = requireNotNull(checkpoint.active)
        val decision = planner.decide(TranslationRetryContext(active.ids.map { snapshots.getValue(it) },
            active.attempts, active.splitDepth), failure)
        val blocks = decision.retryBlocks
        if (checkpoint.remainingAdditional == 0 || decision.outcome == TranslationRetryOutcome.PERMANENT ||
            blocks.isEmpty() || blocks.size > checkpoint.remainingAdditional) {
            replace(checkpoint.pending, active, checkpoint.attempt, failure, 0)
        } else {
            val due = if (nowMillis > Long.MAX_VALUE - decision.backoffMillis) Long.MAX_VALUE
                else nowMillis + decision.backoffMillis
            checkpoint = TranslationRetryCheckpoint(checkpoint.targets, checkpoint.initialAdditional,
                checkpoint.remainingAdditional - blocks.size,
                blocks.map { TranslationRetryPending(it.itemIds(), it.attempts, it.splitDepth) } + checkpoint.pending,
                notBeforeMillis = due)
        }
        return decision
    }
    private fun replace(pending: List<TranslationRetryPending>, active: TranslationRetryPending?,
        attempt: RequestAttemptId?, failure: ModelFailureKind?, due: Long) {
        checkpoint = TranslationRetryCheckpoint(checkpoint.targets, checkpoint.initialAdditional,
            checkpoint.remainingAdditional, pending, active, attempt, failure, due)
    }
    companion object {
        fun start(items: List<TranslationItemSnapshot>, additional: Int): TranslationRetrySession =
            TranslationRetrySession(items, TranslationRetryCheckpoint(items.map { TranslationRetryTarget(it.id, it.fingerprint) },
                additional, additional, listOf(TranslationRetryPending(items.map { it.id }))))
        /** An active attempt must first be reconciled with durable request/project receipts. */
        fun restore(items: List<TranslationItemSnapshot>, checkpoint: TranslationRetryCheckpoint): TranslationRetrySession =
            TranslationRetrySession(items, checkpoint)
    }
}

/** Retry decisions depend on identity/order/counters only; source bodies are deliberately absent. */
object DefaultTranslationRetryRecoveryPolicy : TranslationRetryRecoveryPolicy {
    override fun afterFailure(checkpoint: TranslationRetryCheckpoint, failure: ModelFailureKind,
        nowMillis: Long): TranslationRetryCheckpoint {
        require(checkpoint.active != null && checkpoint.terminalFailure == null)
        val identities = checkpoint.targets.mapIndexed { index, target ->
            TranslationItemSnapshot(target.id, 1, index, "", TextItemStatus.NONE, target.fingerprint)
        }
        val session = TranslationRetrySession.restore(identities, checkpoint)
        session.failed(failure, nowMillis)
        return session.checkpoint
    }
}
