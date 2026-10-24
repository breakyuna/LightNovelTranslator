package com.breakyuna.noveltranslator.nativecore.domain

/** Credential/body-free snapshot. Persist this before dispatch or before waiting for a retry. */
class TranslationRetryCheckpoint(
    targets: List<TranslationRetryTarget>,
    val initialAdditional: Int,
    val remainingAdditional: Int,
    pending: List<TranslationRetryPending>,
    val active: TranslationRetryPending? = null,
    val attempt: RequestAttemptId? = null,
    val terminalFailure: ModelFailureKind? = null,
    val notBeforeMillis: Long = 0,
) {
    val targets = frozenList(targets)
    val pending = frozenList(pending)
    init {
        require(this.targets.size in 1..20 && this.targets.map { it.id }.distinct().size == this.targets.size)
        require(initialAdditional in 0..5 && remainingAdditional in 0..initialAdditional && notBeforeMillis >= 0)
        require((active == null) == (attempt == null))
        require(terminalFailure == null || active != null)
        val queued = this.pending.flatMap { it.ids } + active?.ids.orEmpty()
        require(queued.size <= 20 && queued.distinct().size == queued.size)
        require(queued.all { id -> this.targets.any { it.id == id } })
    }
    override fun toString() = "TranslationRetryCheckpoint(targets=${targets.size}, remaining=$remainingAdditional)"
}

data class TranslationRetryTarget(val id: ItemId, val fingerprint: ObjectFingerprint)
class TranslationRetryPending(ids: List<ItemId>, val attempts: Int = 0, val splitDepth: Int = 0) {
    val ids = frozenList(ids)
    init {
        require(this.ids.size in 1..20 && this.ids.distinct().size == this.ids.size)
        require(attempts in 0..1_000_000 && splitDepth in 0..16)
    }
}

/** Local private checkpoint storage; identities are never inferred from the selected project. */
interface TranslationRetryCheckpointPort {
    suspend fun read(lease: RunLease, ref: LocalProjectRef, configuration: ModelConfigurationRef): TranslationRetryCheckpoint?
    suspend fun write(lease: RunLease, ref: LocalProjectRef, configuration: ModelConfigurationRef,
        checkpoint: TranslationRetryCheckpoint)
}

/** Pure policy shared by live execution and recovery; this does not authorize model dispatch. */
fun interface TranslationRetryRecoveryPolicy {
    fun afterFailure(checkpoint: TranslationRetryCheckpoint, failure: ModelFailureKind, nowMillis: Long): TranslationRetryCheckpoint
}
