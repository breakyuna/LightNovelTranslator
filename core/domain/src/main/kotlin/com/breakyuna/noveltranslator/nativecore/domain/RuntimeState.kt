package com.breakyuna.noveltranslator.nativecore.domain

/** Process-local coordination. Persistence/reconciliation belongs to run storage. */
enum class RuntimeOwner { AGENT, TRANSLATION, MODEL_PROBE }
enum class RuntimePhase { RUNNING, STOPPING, FINISHING }

data class ActiveRun(
    val identity: RunIdentity,
    val owner: RuntimeOwner,
    val phase: RuntimePhase,
    val outstandingOperations: Int,
)

data class RuntimeSnapshot(
    val revision: Long,
    val selectedChat: ChatIdentity?,
    val activeRun: ActiveRun?,
    val projectWriteRunning: Boolean,
    val skillWriteRunning: Boolean,
    val authenticationWriteRunning: Boolean,
    val closed: Boolean,
)

class RuntimeUnavailable(val reason: Reason) : IllegalStateException("Runtime unavailable: $reason") {
    enum class Reason { BUSY, WRONG_PROJECT, STALE_EXECUTION, STOPPING, CLOSED }
}
