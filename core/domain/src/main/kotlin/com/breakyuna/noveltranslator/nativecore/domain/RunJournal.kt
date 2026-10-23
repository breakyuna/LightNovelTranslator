package com.breakyuna.noveltranslator.nativecore.domain

/** Local execution facts, never an authorization to replay a model request or project write. */
enum class StoredRunState { RUNNING, WAITING_APPROVAL, SUCCEEDED, CANCELLED, FAILED, INTERRUPTED }
enum class StoredRunStage { ACCEPTED, REQUESTING, RESPONSE_RECORDED, COMMITTING, COMMITTED }

class RunCheckpoint(
    val stage: StoredRunStage,
    val revision: Long? = null,
    val committedItems: Int = 0,
    val requests: Int = 0,
    pendingCommands: List<CommandId> = emptyList(),
    val agentRequest: AgentRequestKey? = null,
) {
    val pendingCommands: List<CommandId> = frozenList(pendingCommands)
    init {
        require(revision == null || revision >= 0)
        require(committedItems >= 0 && requests >= 0)
        require(this.pendingCommands.size <= 20 && this.pendingCommands.distinct().size == this.pendingCommands.size)
        require(this.pendingCommands.isEmpty() || stage == StoredRunStage.COMMITTING)
    }
}

/** Execution identity is distinct from logical RunId: approving a waiting Run creates a new execution. */
data class RunJournalHandle(val executionId: String, val ref: LocalProjectRef, val run: RunIdentity)
class StoredRun(
    val handle: RunJournalHandle, val owner: RuntimeOwner, val state: StoredRunState,
    val checkpoint: RunCheckpoint, val needsReview: Boolean,
)

interface RunJournalPort {
    suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner): RunJournalHandle
    suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint)
    /** Only finalizes the calling process's execution; uncertainty can override the requested terminal state. */
    suspend fun finish(handle: RunJournalHandle, state: StoredRunState)
    /** Reconcile dead-process records and project receipts; never execute requests or writes. */
    suspend fun recoverInterrupted()
    suspend fun recent(ref: LocalProjectRef, limit: Int = 5): List<StoredRun>
}

/** Used by model/tool orchestrators without depending on the engine implementation. */
interface RunProgressRecorder {
    suspend fun checkpoint(value: RunCheckpoint)
    fun waitingForApproval()
    /** Record the actual approval result, including non-throwing conflict/cancellation. */
    fun approvalResult(state: TextApprovalState)
}
