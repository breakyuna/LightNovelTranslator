package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Frozen identity of one queued translation task. The full local project instance/generation,
 * the logical Run/command identity, the exact model configuration revision and the project
 * revision are all part of the identity: a stale pause, resume, cancel or adjudication command
 * must not be applied to a task that merely shares a RunId.
 *
 * This type carries no prompt, source text, translation text or credential. Its [toString]
 * deliberately omits the configuration and project body.
 */
data class TranslationTaskQueueIdentity(
    val ref: LocalProjectRef,
    val run: RunIdentity,
    val configuration: ModelConfigurationRef,
    val revision: Long,
) {
    init {
        require(run.projectId == ref.projectId && run.chatId == null) {
            "A translation task must belong to its project and have no Chat"
        }
        require(revision >= 0) { "Invalid translation task revision" }
    }

    override fun toString(): String =
        "TranslationTaskQueueIdentity(run=${run.runId.value}, revision=$revision, policy=redacted)"
}

/** Builds the frozen queue identity from the same fields that freeze the paid task spec. */
fun TranslationTaskSpec.queueIdentity(): TranslationTaskQueueIdentity =
    TranslationTaskQueueIdentity(
        ref = ref,
        run = run,
        configuration = configuration,
        revision = revision,
    )

/** Stable identity of one bounded failure batch inside a task. */
@JvmInline
value class TranslationTaskFailureBatchId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128 && value.none { it.isISOControl() }) {
            "Invalid translation failure batch identity"
        }
    }
}

/**
 * What is actually known about one failed member. A missing provider receipt is never
 * interchangeable with a received failure, and only a received response may carry usage.
 */
enum class TranslationFailureReceipt {
    /** The provider response and the durable failure receipt were observed. */
    RECEIVED,

    /** A dispatch was recorded, but no response/receipt proves what happened to it. */
    DISPATCHED_NO_RECEIPT,

    /** No model request was dispatched for this member. */
    NOT_DISPATCHED,
}

/**
 * Immutable failure fact for one member. The project item identity, its base fingerprint, the
 * classified reason, receipt state, usage certainty and terminal/committed flags are frozen when
 * the batch is recorded. A manual decision copies this fact as evidence; it never rewrites it.
 */
data class TranslationFailureMember(
    val itemId: ItemId,
    val fingerprint: ObjectFingerprint,
    val failure: ModelFailureKind,
    val receipt: TranslationFailureReceipt,
    val usage: UsageSource = UsageSource.UNKNOWN,
    val committed: Boolean = false,
    val terminal: Boolean = false,
) {
    init {
        require(receipt == TranslationFailureReceipt.RECEIVED || usage == UsageSource.UNKNOWN) {
            "Only a received failure may carry usage"
        }
        require(!committed || receipt == TranslationFailureReceipt.RECEIVED) {
            "A committed member requires a received response"
        }
        require(!terminal || receipt == TranslationFailureReceipt.RECEIVED) {
            "A terminal failure requires a received response"
        }
        require(!(committed && terminal)) { "A committed member cannot be a terminal failure" }
    }

    override fun toString(): String =
        "TranslationFailureMember(item=${itemId.value}, failure=$failure, receipt=$receipt, " +
            "committed=$committed, terminal=$terminal)"
}

/**
 * One bounded failure batch reported by an execution. A task may contain several batches; a
 * decision names exactly one batch and exact members, so adjudicating one batch cannot alter
 * another batch's member facts or decisions. This is the task-level fact batch; it is distinct
 * from the edit-command failure batch used by the text translation service.
 */
class TranslationTaskFailureBatch(
    val id: TranslationTaskFailureBatchId,
    members: List<TranslationFailureMember>,
) {
    val members: List<TranslationFailureMember> = frozenList(members)

    init {
        require(this.members.size in 1..TranslationTaskFailureBatch.MAX_MEMBERS) {
            "A failure batch requires 1..${TranslationTaskFailureBatch.MAX_MEMBERS} members"
        }
        require(this.members.map { it.itemId }.distinct().size == this.members.size) {
            "Duplicate failure member"
        }
        require(this.members.any { !it.committed }) {
            "A failure batch must contain at least one uncommitted member"
        }
    }

    fun member(itemId: ItemId): TranslationFailureMember? =
        members.firstOrNull { it.itemId == itemId }

    fun sameFactsAs(other: TranslationTaskFailureBatch): Boolean =
        id == other.id && members == other.members

    override fun toString(): String =
        "TranslationTaskFailureBatch(id=${id.value}, members=${members.size})"

    companion object {
        /** Matches the bounded model/translation batch size; this is not a device measurement. */
        const val MAX_MEMBERS = 20
    }
}

/** Explicit user intent. A lifecycle command and a batch adjudication have different payloads. */
enum class TranslationTaskCommandAction {
    PAUSE,
    RESUME,
    CANCEL,
    RETRY_MEMBERS,
    SKIP_MEMBERS,
    KEEP_ERROR,
    TERMINATE_TASK;

    val adjudication: Boolean
        get() = this == RETRY_MEMBERS || this == SKIP_MEMBERS || this == KEEP_ERROR
}

/**
 * Auditable record of one applied user command. Batch decisions additionally bind the exact task,
 * batch, selected member IDs and a frozen copy of each selected member's failure facts. Lifecycle
 * commands carry no batch evidence, and [TranslationTaskCommandAction.TERMINATE_TASK] is
 * intentionally task-level because the terminal failure facts of every batch must survive it.
 */
class TranslationTaskCommandRecord internal constructor(
    val commandId: CommandId,
    val task: TranslationTaskQueueIdentity,
    val action: TranslationTaskCommandAction,
    val batchId: TranslationTaskFailureBatchId?,
    memberIds: List<ItemId>,
    evidence: List<TranslationFailureMember>,
) {
    val memberIds: List<ItemId> = frozenList(memberIds)
    val evidence: List<TranslationFailureMember> = frozenList(evidence)

    init {
        require(task.run.chatId == null) { "A translation command must not bind a Chat" }
        if (action.adjudication) {
            require(batchId != null) { "A batch decision requires a batch identity" }
            require(this.memberIds.isNotEmpty()) { "A batch decision requires members" }
            require(this.memberIds.size <= TranslationTaskFailureBatch.MAX_MEMBERS)
            require(this.memberIds.distinct().size == this.memberIds.size) {
                "Duplicate decision member"
            }
            require(this.evidence.size == this.memberIds.size) {
                "Decision evidence must cover every selected member"
            }
            require(this.evidence.map { it.itemId } == this.memberIds) {
                "Decision evidence order must match its member identities"
            }
        } else {
            require(batchId == null && this.memberIds.isEmpty() && this.evidence.isEmpty()) {
                "A lifecycle command has no batch evidence"
            }
        }
    }

    override fun toString(): String =
        "TranslationTaskCommandRecord(command=${commandId.value}, action=$action, " +
            "members=${memberIds.size})"
}

/** Logical queue state. FAILED is final for automatic execution but open to explicit adjudication. */
enum class TranslationTaskQueueState {
    QUEUED,
    RUNNING,
    PAUSED,
    NEEDS_REVIEW,
    FAILED,

    /** Every target was translated and every committed result had a proven receipt. */
    COMPLETED,

    /** Processing finished, but some members were explicitly skipped and retain error facts. */
    COMPLETED_WITH_ERRORS,
    CANCELLED;

    /**
     * A task in one of these states owns its project's single active slot. QUEUED tasks do not:
     * several tasks for one project may wait there, but only one may run.
     */
    val occupiesProject: Boolean
        get() = this == RUNNING || this == PAUSED || this == NEEDS_REVIEW || this == FAILED

    /** No further command may change the logical result; facts are retained read-only. */
    val terminal: Boolean
        get() = this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == CANCELLED

    /** Explicit user adjudication is required before this task can run or terminate. */
    val awaitingDecision: Boolean get() = this == NEEDS_REVIEW || this == FAILED
}

/**
 * One immutable task snapshot. [batches] and [commands] are append-only across queue updates;
 * member facts are never rewritten. A decision may cover only members that have no other decision
 * in the same batch, a final skip/keep decision cannot be reopened by another batch, and a task
 * that is dispatchable cannot retain an unresolved failure member.
 */
class TranslationQueuedTask internal constructor(
    val identity: TranslationTaskQueueIdentity,
    val sequence: Long,
    val state: TranslationTaskQueueState,
    batches: List<TranslationTaskFailureBatch>,
    commands: List<TranslationTaskCommandRecord>,
) {
    val batches: List<TranslationTaskFailureBatch> = frozenList(batches)
    val commands: List<TranslationTaskCommandRecord> = frozenList(commands)
    val decisions: List<TranslationTaskCommandRecord> =
        frozenList(this.commands.filter { it.action.adjudication })

    /**
     * Distinct members with an explicit skip decision, used by completion accounting and by the
     * execution layer. A skip overrides an older RETRY decision for the same item.
     */
    val skippedItemIds: Set<ItemId> = frozenSet(
        decisions.filter { it.action == TranslationTaskCommandAction.SKIP_MEMBERS }
            .flatMap { it.memberIds },
    )

    init {
        require(sequence >= 0) { "Invalid task sequence" }
        require(this.batches.size <= TranslationTaskQueueLimits.HARD_MAX_BATCHES) {
            "Task exceeds the hard failure batch bound"
        }
        require(this.batches.map { it.id }.distinct().size == this.batches.size) {
            "Duplicate failure batch identity"
        }
        require(
            this.commands.size <= TranslationTaskQueueLimits.HARD_MAX_COMMANDS +
                TranslationTaskQueueLimits.TERMINAL_COMMAND_RESERVE,
        ) {
            "Task exceeds the hard command bound"
        }
        require(this.commands.map { it.commandId }.distinct().size == this.commands.size) {
            "Duplicate task command identity"
        }
        require(this.commands.all { it.task == identity }) { "Command belongs to another task" }

        val decided = HashSet<Pair<TranslationTaskFailureBatchId, ItemId>>()
        for (record in this.decisions) {
            val batchId = requireNotNull(record.batchId)
            val batch = requireNotNull(this.batches.firstOrNull { it.id == batchId }) {
                "Decision references an unknown batch"
            }
            for (evidence in record.evidence) {
                require(batch.member(evidence.itemId) == evidence) {
                    "Decision evidence changed a frozen failure fact"
                }
                require(decided.add(batchId to evidence.itemId)) {
                    "A failure member has more than one decision"
                }
            }
        }

        if (state == TranslationTaskQueueState.QUEUED || state == TranslationTaskQueueState.RUNNING) {
            for (batch in this.batches) {
                for (member in batch.members) {
                    if (member.committed) continue
                    val memberAction = decision(batch.id, member.itemId)?.action
                    require(
                        memberAction == TranslationTaskCommandAction.RETRY_MEMBERS ||
                            memberAction == TranslationTaskCommandAction.SKIP_MEMBERS,
                    ) {
                        "A dispatchable task cannot retain an unresolved failure member"
                    }
                    require(member.receipt != TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                        "A retried or skipped member cannot hide an unreceipted dispatch"
                    }
                }
            }
        }
    }

    fun batch(id: TranslationTaskFailureBatchId): TranslationTaskFailureBatch? =
        batches.firstOrNull { it.id == id }

    fun command(id: CommandId): TranslationTaskCommandRecord? =
        commands.firstOrNull { it.commandId == id }

    fun decision(batchId: TranslationTaskFailureBatchId, itemId: ItemId): TranslationTaskCommandRecord? =
        decisions.firstOrNull { it.batchId == batchId && itemId in it.memberIds }

    /**
     * Final action for one item across all batches. A later skip/keep overrides an earlier retry;
     * KEEP wins if two terminal error decisions ever coexist. Null means no failure decision, an
     * unresolved open fact still requires adjudication, or the item is already committed.
     */
    fun finalDecision(itemId: ItemId): TranslationTaskCommandAction? {
        if (isCommitted(itemId) || hasUnresolvedMember(itemId)) return null
        val actions = decisions.filter { itemId in it.memberIds }.map { it.action }
        return when {
            TranslationTaskCommandAction.KEEP_ERROR in actions ->
                TranslationTaskCommandAction.KEEP_ERROR
            TranslationTaskCommandAction.SKIP_MEMBERS in actions ->
                TranslationTaskCommandAction.SKIP_MEMBERS
            TranslationTaskCommandAction.RETRY_MEMBERS in actions ->
                TranslationTaskCommandAction.RETRY_MEMBERS
            else -> null
        }
    }

    fun decisionsFor(batchId: TranslationTaskFailureBatchId): List<TranslationTaskCommandRecord> =
        frozenList(decisions.filter { it.batchId == batchId })

    /** True when the user already skipped or kept an error for this item in any batch. */
    fun hasFinalErrorDecision(itemId: ItemId): Boolean =
        decisions.any { it.action != TranslationTaskCommandAction.RETRY_MEMBERS && itemId in it.memberIds }

    /** True when any recorded batch proved this item was committed; later facts cannot undo it. */
    fun isCommitted(itemId: ItemId): Boolean =
        batches.any { batch -> batch.members.any { it.itemId == itemId && it.committed } }

    /** True when any recorded batch classified this item as a terminal failure; it stays terminal. */
    fun isTerminalFailure(itemId: ItemId): Boolean =
        batches.any { batch -> batch.members.any { it.itemId == itemId && it.terminal } }

    /** True while any batch still has an undecided, uncommitted fact for this item. */
    fun hasUnresolvedMember(itemId: ItemId): Boolean =
        batches.any { batch ->
            batch.members.any { member ->
                member.itemId == itemId && !member.committed && decision(batch.id, itemId) == null
            }
        }

    /** Uncommitted members of one batch that still need an explicit user decision. */
    fun unresolvedMembers(batchId: TranslationTaskFailureBatchId): List<TranslationFailureMember> {
        val batch = batch(batchId) ?: return emptyList()
        return frozenList(batch.members.filter { member ->
            !member.committed && decision(batchId, member.itemId) == null
        })
    }

    internal fun with(
        state: TranslationTaskQueueState = this.state,
        batches: List<TranslationTaskFailureBatch> = this.batches,
        commands: List<TranslationTaskCommandRecord> = this.commands,
    ): TranslationQueuedTask =
        TranslationQueuedTask(
            identity = identity,
            sequence = sequence,
            state = state,
            batches = batches,
            commands = commands,
        )

    override fun toString(): String =
        "TranslationQueuedTask(run=${identity.run.runId.value}, sequence=$sequence, state=$state, " +
            "batches=${batches.size}, commands=${commands.size})"
}

/** Explicit bounds for a queue aggregate. The defaults are safety limits, not measured capacities. */
data class TranslationTaskQueueLimits(
    val maxTasks: Int = DEFAULT_MAX_TASKS,
    val maxBatchesPerTask: Int = DEFAULT_MAX_BATCHES,
    /** Normal command budget; one extra terminal cancel/terminate record is always allowed. */
    val maxCommandsPerTask: Int = DEFAULT_MAX_COMMANDS,
) {
    init {
        require(maxTasks in 1..HARD_MAX_TASKS) { "Invalid task count budget" }
        require(maxBatchesPerTask in 1..HARD_MAX_BATCHES) { "Invalid failure batch budget" }
        require(maxCommandsPerTask in 1..HARD_MAX_COMMANDS) { "Invalid command budget" }
    }

    companion object {
        const val DEFAULT_MAX_TASKS = 100
        const val DEFAULT_MAX_BATCHES = 32
        const val DEFAULT_MAX_COMMANDS = 768
        const val HARD_MAX_TASKS = 100
        const val HARD_MAX_BATCHES = 100
        const val HARD_MAX_COMMANDS = 4_096
        const val TERMINAL_COMMAND_RESERVE = 1
        val DEFAULT = TranslationTaskQueueLimits()
    }
}

/** Why a queue command was refused. A refusal never mutates the returned [TranslationQueueResult.queue]. */
enum class TranslationQueueRejection {
    QUEUE_FULL,
    IDENTITY_CONFLICT,
    UNKNOWN_TASK,
    IDENTITY_MISMATCH,
    UNKNOWN_BATCH,
    BATCH_CONFLICT,
    BATCH_LIMIT,
    COMMAND_LIMIT,
    COMMAND_CONFLICT,
    INVALID_TRANSITION,
    TERMINAL_STATE,
    INVALID_EVIDENCE,
    MEMBER_NOT_IN_BATCH,
    MEMBER_ALREADY_DECIDED,
    MEMBER_STILL_UNRESOLVED,
    COMMITTED_MEMBER,
    UNCERTAIN_RECEIPT,
    TERMINAL_FAILURE,
    COMPLETION_NOT_PROVEN,
    INCOMPLETE_FAILURE_DECISIONS,
}

/**
 * Result of one pure queue operation. [queue] is always the complete snapshot after the operation:
 * a rejection and an unchanged result return the input snapshot itself, never a partially updated
 * one. A duplicate command returns the original [TranslationTaskCommandRecord].
 */
sealed interface TranslationQueueResult {
    val queue: TranslationTaskQueue

    class Applied(
        override val queue: TranslationTaskQueue,
        val task: TranslationQueuedTask,
    ) : TranslationQueueResult

    class Duplicate(
        override val queue: TranslationTaskQueue,
        val task: TranslationQueuedTask,
        val command: TranslationTaskCommandRecord,
    ) : TranslationQueueResult

    class Unchanged(
        override val queue: TranslationTaskQueue,
        val task: TranslationQueuedTask? = null,
    ) : TranslationQueueResult

    class Rejected(
        override val queue: TranslationTaskQueue,
        val reason: TranslationQueueRejection,
    ) : TranslationQueueResult
}

/**
 * Completion evidence supplied by the execution layer. A paid task is complete only when every
 * target is either committed with an observed receipt or explicitly skipped, no dispatched request
 * still lacks a receipt, and usage is not unknown. Counts and usage certainty are not inferred from
 * "the last call looked successful".
 */
data class TranslationTaskCompletionFacts(
    val committedItems: Int,
    val skippedItems: Int,
    val receiptedItems: Int,
    val targetItems: Int,
    val unreceiptedDispatches: Int,
    val usage: UsageSource,
) {
    init {
        require(
            committedItems >= 0 && skippedItems >= 0 && receiptedItems >= 0 && targetItems >= 0 &&
                unreceiptedDispatches >= 0,
        )
        require(committedItems <= targetItems) { "Committed items exceed the task target" }
        require(skippedItems <= targetItems) { "Skipped items exceed the task target" }
        require(receiptedItems <= targetItems) { "Receipted items exceed the task target" }
    }
}

/**
 * Pure, bounded, immutable translation task queue and manual-adjudication state machine.
 *
 * Invariants:
 * - tasks are ordered by a stable insertion [TranslationQueuedTask.sequence];
 * - at most one task may be RUNNING in this aggregate, and at most one task per local project may
 *   occupy the project slot ([TranslationTaskQueueState.occupiesProject]);
 * - enqueue, pause, resume, cancel and every adjudication return a new snapshot; no method mutates
 *   the receiver, performs I/O, starts a model request or writes a project;
 * - each user command is identified by [CommandId]. Replaying the exact command returns
 *   [TranslationQueueResult.Duplicate] with the original record; reusing that command ID for a
 *   different action/payload is refused. A new command after a terminal state or after the task
 *   moved on is refused;
 * - failure batches and their member facts are append-only. A decision covers one batch and exact
 *   members, and a member can have only one decision in a given batch. A new failure for a retried
 *   item must be a new batch after the previous batch was decided; an item cannot have two open
 *   batches and cannot be re-reported after a committed/terminal/final-error fact;
 * - a member with [TranslationFailureReceipt.DISPATCHED_NO_RECEIPT] cannot be retried or skipped; it
 *   can only be kept as an error or terminated after explicit user acknowledgement;
 * - a committed member cannot be retried or skipped, and a terminal failure member cannot be
 *   retried; [TranslationTaskCommandAction.TERMINATE_TASK]/cancel never remove the failure facts;
 * - completion requires every uncommitted failure member to have an explicit RETRY or SKIP decision
 *   plus [TranslationTaskCompletionFacts] proving committed + skipped targets, receipts for every
 *   commit, known usage and no unreceipted dispatch. KEEP_ERROR is never a successful completion;
 *   a non-zero skipped count ends an otherwise proven completion as
 *   [TranslationTaskQueueState.COMPLETED_WITH_ERRORS].
 *
 * Transitions:
 * - QUEUED -> RUNNING | CANCELLED
 * - RUNNING -> PAUSED | NEEDS_REVIEW | FAILED | COMPLETED | COMPLETED_WITH_ERRORS | CANCELLED
 * - PAUSED -> QUEUED | NEEDS_REVIEW | FAILED | CANCELLED
 * - NEEDS_REVIEW -> QUEUED (all members RETRY/SKIP) | FAILED | CANCELLED
 * - FAILED -> QUEUED (all members RETRY/SKIP) | CANCELLED
 * - COMPLETED, COMPLETED_WITH_ERRORS and CANCELLED are terminal; only an exact command replay
 *   returns Duplicate.
 *
 * FAILED is not terminal: it stops automatic execution and requires an explicit user command
 * (retry/skip/keep/terminate). The caller remains responsible for stopping real work, persisting
 * this snapshot atomically and reconciling provider receipts; this class never authorizes a paid
 * dispatch by itself.
 */
class TranslationTaskQueue private constructor(
    val limits: TranslationTaskQueueLimits,
    tasks: List<TranslationQueuedTask>,
    private val nextSequence: Long,
) {
    val tasks: List<TranslationQueuedTask> = frozenList(tasks)

    init {
        require(this.tasks.size <= limits.maxTasks) { "Queue exceeds its task budget" }
        require(this.tasks.zipWithNext().all { (left, right) -> left.sequence < right.sequence }) {
            "Task queue order is not stable"
        }
        require(this.tasks.map { it.identity }.distinct().size == this.tasks.size) {
            "Duplicate task identity"
        }
        require(this.tasks.all { it.batches.size <= limits.maxBatchesPerTask }) {
            "Task exceeds its failure batch budget"
        }
        require(
            this.tasks.all {
                it.commands.size <= limits.maxCommandsPerTask +
                    TranslationTaskQueueLimits.TERMINAL_COMMAND_RESERVE
            },
        ) {
            "Task exceeds its command budget"
        }
        require(this.tasks.isEmpty() || nextSequence > this.tasks.last().sequence) {
            "Next sequence is not after the queue tail"
        }

        val activeProjects = HashSet<LocalProjectRef>()
        for (task in this.tasks) {
            if (task.state.occupiesProject) {
                require(activeProjects.add(task.identity.ref)) {
                    "A project has more than one active translation task"
                }
            }
        }
        require(this.tasks.count { it.state == TranslationTaskQueueState.RUNNING } <= 1) {
            "Only one translation task may execute at a time"
        }
    }

    fun task(identity: TranslationTaskQueueIdentity): TranslationQueuedTask? =
        tasks.firstOrNull { it.identity == identity }

    fun activeTask(ref: LocalProjectRef): TranslationQueuedTask? =
        tasks.firstOrNull { it.identity.ref == ref && it.state.occupiesProject }

    fun tasksFor(ref: LocalProjectRef): List<TranslationQueuedTask> =
        frozenList(tasks.filter { it.identity.ref == ref })

    /**
     * Admit one frozen task at the tail. Repeating the exact identity is idempotent. Reusing a Run
     * or command identity with a different project/configuration/revision is a conflict; two
     * different tasks for one project are allowed to wait, but never to run at the same time.
     */
    /** Admits a task from its frozen paid spec, including the exact revision and configuration. */
    fun enqueue(spec: TranslationTaskSpec): TranslationQueueResult = enqueue(spec.queueIdentity())

    fun enqueue(identity: TranslationTaskQueueIdentity): TranslationQueueResult {
        val existing = task(identity)
        if (existing != null) return TranslationQueueResult.Unchanged(this, existing)
        if (related(identity) != null) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.IDENTITY_CONFLICT)
        }
        if (tasks.size >= limits.maxTasks) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.QUEUE_FULL)
        }
        val queued = TranslationQueuedTask(
            identity = identity,
            sequence = nextSequence,
            state = TranslationTaskQueueState.QUEUED,
            batches = emptyList(),
            commands = emptyList(),
        )
        val next = TranslationTaskQueue(limits, tasks + queued, nextSequence + 1)
        return TranslationQueueResult.Applied(next, queued)
    }

    /**
     * Starts the oldest QUEUED task whose project has no active task. This aggregate executes at
     * most one task at a time, so tasks for different projects wait in the same stable queue. A
     * blocked earlier task keeps its sequence position and does not prevent another project from
     * using an idle slot.
     */
    fun dispatchNext(): TranslationQueueResult {
        if (tasks.any { it.state == TranslationTaskQueueState.RUNNING }) {
            return TranslationQueueResult.Unchanged(this)
        }
        val activeProjects = HashSet<LocalProjectRef>()
        for (task in tasks) {
            if (task.state.occupiesProject) activeProjects.add(task.identity.ref)
        }
        val next = tasks.firstOrNull {
            it.state == TranslationTaskQueueState.QUEUED && it.identity.ref !in activeProjects
        } ?: return TranslationQueueResult.Unchanged(this)
        val running = next.with(state = TranslationTaskQueueState.RUNNING)
        return TranslationQueueResult.Applied(replace(running), running)
    }

    /** PAUSED retains the task's original queue position. Only the exact identity may be paused. */
    fun pause(identity: TranslationTaskQueueIdentity, commandId: CommandId): TranslationQueueResult =
        lifecycle(
            identity = identity,
            commandId = commandId,
            action = TranslationTaskCommandAction.PAUSE,
            from = setOf(TranslationTaskQueueState.RUNNING),
            to = TranslationTaskQueueState.PAUSED,
        )

    /** Resuming only requeues the exact paused task; it never dispatches a paid request by itself. */
    fun resume(identity: TranslationTaskQueueIdentity, commandId: CommandId): TranslationQueueResult =
        lifecycle(
            identity = identity,
            commandId = commandId,
            action = TranslationTaskCommandAction.RESUME,
            from = setOf(TranslationTaskQueueState.PAUSED),
            to = TranslationTaskQueueState.QUEUED,
        )

    /** Cancels any non-terminal task. Already committed/failed facts remain in the snapshot. */
    fun cancel(identity: TranslationTaskQueueIdentity, commandId: CommandId): TranslationQueueResult =
        lifecycle(
            identity = identity,
            commandId = commandId,
            action = TranslationTaskCommandAction.CANCEL,
            from = NON_TERMINAL_TRANSLATION_STATES,
            to = TranslationTaskQueueState.CANCELLED,
        )

    /**
     * Explicit adjudication result for a task that must stop, usually after NEEDS_REVIEW/FAILED.
     * It is task-level; every batch, member fact and prior decision is retained.
     */
    fun terminate(identity: TranslationTaskQueueIdentity, commandId: CommandId): TranslationQueueResult =
        lifecycle(
            identity = identity,
            commandId = commandId,
            action = TranslationTaskCommandAction.TERMINATE_TASK,
            from = NON_TERMINAL_TRANSLATION_STATES,
            to = TranslationTaskQueueState.CANCELLED,
        )

    /**
     * Execution-level stop for facts that forbid automatic continuation, such as an unreceipted
     * dispatch. Only RUNNING may stop; replaying the same outcome is idempotent, a terminal task
     * is never reopened and no command record is invented because this is not user intent.
     */
    fun markNeedsReview(identity: TranslationTaskQueueIdentity): TranslationQueueResult =
        executionStop(identity, TranslationTaskQueueState.NEEDS_REVIEW)

    /**
     * Execution-level stop for a task-level failure that has no per-member batch. Failure facts
     * already recorded are retained; a later member batch may still be recorded and adjudicated,
     * but without member evidence the task cannot return to QUEUED and must be terminated or
     * replaced by a new explicitly admitted task.
     */
    fun markFailed(identity: TranslationTaskQueueIdentity): TranslationQueueResult =
        executionStop(identity, TranslationTaskQueueState.FAILED)

    /** Retries exactly the selected, uncommitted, non-terminal and non-uncertain members. */
    fun retryMembers(
        identity: TranslationTaskQueueIdentity,
        commandId: CommandId,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): TranslationQueueResult =
        adjudicate(identity, commandId, batchId, TranslationTaskCommandAction.RETRY_MEMBERS, memberIds)

    /**
     * Skips exactly the selected known failures, keeping their frozen error facts in the task.
     * The task may continue for the other members and can end as COMPLETED_WITH_ERRORS. A member
     * whose dispatch has no receipt cannot be skipped; keep or terminate it instead.
     */
    fun skipMembers(
        identity: TranslationTaskQueueIdentity,
        commandId: CommandId,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): TranslationQueueResult =
        adjudicate(identity, commandId, batchId, TranslationTaskCommandAction.SKIP_MEMBERS, memberIds)

    /**
     * Keeps the error mark for exactly the selected members and stops as FAILED. Use [skipMembers]
     * when the task should continue without those members; termination is a separate command.
     */
    fun keepError(
        identity: TranslationTaskQueueIdentity,
        commandId: CommandId,
        batchId: TranslationTaskFailureBatchId,
        memberIds: List<ItemId>,
    ): TranslationQueueResult =
        adjudicate(identity, commandId, batchId, TranslationTaskCommandAction.KEEP_ERROR, memberIds)

    /**
     * Appends one failure batch. Re-reporting identical facts is idempotent; changing them conflicts.
     * A terminal task accepts only an exact replay: a new late batch is refused instead of silently
     * changing a completed/cancelled snapshot. A new batch also cannot reopen an item whose skip or
     * keep-error decision is already final, re-report an item already proven committed or terminal,
     * or create a second open batch for an item that is still undecided in another batch.
     */
    fun recordFailure(
        identity: TranslationTaskQueueIdentity,
        batch: TranslationTaskFailureBatch,
    ): TranslationQueueResult {
        val current = task(identity) ?: return missing(identity)
        val existing = current.batch(batch.id)
        if (existing != null) {
            return if (existing.sameFactsAs(batch)) {
                TranslationQueueResult.Unchanged(this, current)
            } else {
                TranslationQueueResult.Rejected(this, TranslationQueueRejection.BATCH_CONFLICT)
            }
        }
        if (current.state.terminal) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.TERMINAL_STATE)
        }
        if (current.batches.size >= limits.maxBatchesPerTask) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.BATCH_LIMIT)
        }
        if (current.state !in FAILURE_RECORDING_STATES) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.INVALID_TRANSITION)
        }
        if (batch.members.any { current.hasFinalErrorDecision(it.itemId) }) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.MEMBER_ALREADY_DECIDED)
        }
        if (batch.members.any { current.isCommitted(it.itemId) }) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.COMMITTED_MEMBER)
        }
        if (batch.members.any { current.isTerminalFailure(it.itemId) }) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.TERMINAL_FAILURE)
        }
        if (batch.members.any { current.hasUnresolvedMember(it.itemId) }) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.MEMBER_STILL_UNRESOLVED)
        }
        val nextState = stateFor(current.batches + batch, current.decisionActions())
        val updated = current.with(state = nextState, batches = current.batches + batch)
        return TranslationQueueResult.Applied(replace(updated), updated)
    }

    /**
     * Records proven completion. Unknown usage, an unreceipted dispatch or commit, a shortfall or
     * over-count against the target, an unaccounted skip, or any uncommitted member without a
     * RETRY/SKIP decision refuses the transition. A task with explicit skipped members ends as
     * [TranslationTaskQueueState.COMPLETED_WITH_ERRORS]; failure facts are never removed.
     */
    fun complete(
        identity: TranslationTaskQueueIdentity,
        facts: TranslationTaskCompletionFacts,
    ): TranslationQueueResult {
        val current = task(identity) ?: return missing(identity)
        val proven = facts.targetItems > 0 &&
            facts.committedItems.toLong() + facts.skippedItems == facts.targetItems.toLong() &&
            facts.receiptedItems == facts.committedItems && facts.unreceiptedDispatches == 0 &&
            facts.usage != UsageSource.UNKNOWN &&
            facts.skippedItems == current.skippedItemIds.size
        if (current.state == TranslationTaskQueueState.COMPLETED ||
            current.state == TranslationTaskQueueState.COMPLETED_WITH_ERRORS
        ) {
            return if (proven) {
                TranslationQueueResult.Unchanged(this, current)
            } else {
                TranslationQueueResult.Rejected(
                    this,
                    TranslationQueueRejection.COMPLETION_NOT_PROVEN,
                )
            }
        }
        if (current.state.terminal) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.TERMINAL_STATE)
        }
        if (current.state != TranslationTaskQueueState.RUNNING) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.INVALID_TRANSITION)
        }
        if (!proven) {
            return TranslationQueueResult.Rejected(
                this,
                TranslationQueueRejection.COMPLETION_NOT_PROVEN,
            )
        }
        if (!current.failureFactsAllowCompletion()) {
            return TranslationQueueResult.Rejected(
                this,
                TranslationQueueRejection.INCOMPLETE_FAILURE_DECISIONS,
            )
        }
        val terminalState = if (facts.skippedItems == 0) {
            TranslationTaskQueueState.COMPLETED
        } else {
            TranslationTaskQueueState.COMPLETED_WITH_ERRORS
        }
        val completed = current.with(state = terminalState)
        return TranslationQueueResult.Applied(replace(completed), completed)
    }

    private fun executionStop(
        identity: TranslationTaskQueueIdentity,
        to: TranslationTaskQueueState,
    ): TranslationQueueResult {
        val current = task(identity) ?: return missing(identity)
        if (current.state == to) return TranslationQueueResult.Unchanged(this, current)
        if (current.state.terminal) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.TERMINAL_STATE)
        }
        if (current.state != TranslationTaskQueueState.RUNNING) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.INVALID_TRANSITION)
        }
        val updated = current.with(state = to)
        return TranslationQueueResult.Applied(replace(updated), updated)
    }

    private fun lifecycle(
        identity: TranslationTaskQueueIdentity,
        commandId: CommandId,
        action: TranslationTaskCommandAction,
        from: Set<TranslationTaskQueueState>,
        to: TranslationTaskQueueState,
    ): TranslationQueueResult {
        val current = task(identity) ?: return missing(identity)
        val existing = current.command(commandId)
        if (existing != null) {
            return if (existing.action == action) {
                TranslationQueueResult.Duplicate(this, current, existing)
            } else {
                TranslationQueueResult.Rejected(this, TranslationQueueRejection.COMMAND_CONFLICT)
            }
        }
        if (current.state.terminal) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.TERMINAL_STATE)
        }
        if (current.state !in from) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.INVALID_TRANSITION)
        }
        val terminalSafety = action == TranslationTaskCommandAction.CANCEL ||
            action == TranslationTaskCommandAction.TERMINATE_TASK
        if (!terminalSafety && current.commands.size >= limits.maxCommandsPerTask) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.COMMAND_LIMIT)
        }
        val record = TranslationTaskCommandRecord(
            commandId = commandId,
            task = identity,
            action = action,
            batchId = null,
            memberIds = emptyList(),
            evidence = emptyList(),
        )
        val updated = current.with(state = to, commands = current.commands + record)
        return TranslationQueueResult.Applied(replace(updated), updated)
    }

    private fun adjudicate(
        identity: TranslationTaskQueueIdentity,
        commandId: CommandId,
        batchId: TranslationTaskFailureBatchId,
        action: TranslationTaskCommandAction,
        memberIds: List<ItemId>,
    ): TranslationQueueResult {
        val current = task(identity) ?: return missing(identity)
        if (memberIds.isEmpty() || memberIds.size > TranslationTaskFailureBatch.MAX_MEMBERS ||
            memberIds.distinct().size != memberIds.size
        ) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.INVALID_EVIDENCE)
        }
        // Canonical member order makes an identical command replay independent of caller order.
        val orderedMemberIds = memberIds.sortedBy { it.value }
        val existing = current.command(commandId)
        if (existing != null) {
            return if (existing.action == action && existing.batchId == batchId &&
                existing.memberIds == orderedMemberIds
            ) {
                TranslationQueueResult.Duplicate(this, current, existing)
            } else {
                TranslationQueueResult.Rejected(this, TranslationQueueRejection.COMMAND_CONFLICT)
            }
        }
        if (current.state.terminal) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.TERMINAL_STATE)
        }
        if (!current.state.awaitingDecision) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.INVALID_TRANSITION)
        }
        if (current.commands.size >= limits.maxCommandsPerTask) {
            return TranslationQueueResult.Rejected(this, TranslationQueueRejection.COMMAND_LIMIT)
        }
        val batch = current.batch(batchId)
            ?: return TranslationQueueResult.Rejected(this, TranslationQueueRejection.UNKNOWN_BATCH)

        val selected = ArrayList<TranslationFailureMember>(orderedMemberIds.size)
        for (itemId in orderedMemberIds) {
            val member = batch.member(itemId)
                ?: return TranslationQueueResult.Rejected(
                    this,
                    TranslationQueueRejection.MEMBER_NOT_IN_BATCH,
                )
            if (current.decision(batchId, itemId) != null ||
                current.hasFinalErrorDecision(itemId)
            ) {
                return TranslationQueueResult.Rejected(
                    this,
                    TranslationQueueRejection.MEMBER_ALREADY_DECIDED,
                )
            }
            if (current.isCommitted(itemId)) {
                return TranslationQueueResult.Rejected(
                    this,
                    TranslationQueueRejection.COMMITTED_MEMBER,
                )
            }
            if (current.isTerminalFailure(itemId) && !member.terminal) {
                return TranslationQueueResult.Rejected(
                    this,
                    TranslationQueueRejection.TERMINAL_FAILURE,
                )
            }
            when (action) {
                TranslationTaskCommandAction.RETRY_MEMBERS -> {
                    if (member.committed) {
                        return TranslationQueueResult.Rejected(
                            this,
                            TranslationQueueRejection.COMMITTED_MEMBER,
                        )
                    }
                    if (member.terminal) {
                        return TranslationQueueResult.Rejected(
                            this,
                            TranslationQueueRejection.TERMINAL_FAILURE,
                        )
                    }
                    if (member.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                        return TranslationQueueResult.Rejected(
                            this,
                            TranslationQueueRejection.UNCERTAIN_RECEIPT,
                        )
                    }
                }
                TranslationTaskCommandAction.SKIP_MEMBERS -> {
                    if (member.committed) {
                        return TranslationQueueResult.Rejected(
                            this,
                            TranslationQueueRejection.COMMITTED_MEMBER,
                        )
                    }
                    if (member.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT) {
                        return TranslationQueueResult.Rejected(
                            this,
                            TranslationQueueRejection.UNCERTAIN_RECEIPT,
                        )
                    }
                }
                TranslationTaskCommandAction.KEEP_ERROR -> {
                    if (member.committed) {
                        return TranslationQueueResult.Rejected(
                            this,
                            TranslationQueueRejection.COMMITTED_MEMBER,
                        )
                    }
                }
                else -> return TranslationQueueResult.Rejected(
                    this,
                    TranslationQueueRejection.INVALID_TRANSITION,
                )
            }
            selected.add(member)
        }

        val decisions = current.decisionActions()
        for (member in selected) {
            decisions[FailureMemberKey(batchId, member.itemId)] = action
        }
        val nextState = stateFor(current.batches, decisions)
        val record = TranslationTaskCommandRecord(
            commandId = commandId,
            task = identity,
            action = action,
            batchId = batchId,
            memberIds = orderedMemberIds,
            evidence = selected,
        )
        val updated = current.with(state = nextState, commands = current.commands + record)
        return TranslationQueueResult.Applied(replace(updated), updated)
    }

    private fun missing(identity: TranslationTaskQueueIdentity): TranslationQueueResult.Rejected =
        TranslationQueueResult.Rejected(
            this,
            if (related(identity) != null) TranslationQueueRejection.IDENTITY_MISMATCH
            else TranslationQueueRejection.UNKNOWN_TASK,
        )

    private fun related(identity: TranslationTaskQueueIdentity): TranslationQueuedTask? =
        tasks.firstOrNull { it.identity.run.runId == identity.run.runId }
            ?: tasks.firstOrNull { it.identity.run.commandId == identity.run.commandId }

    private fun replace(updated: TranslationQueuedTask): TranslationTaskQueue {
        val index = tasks.indexOfFirst { it.identity == updated.identity }
        require(index >= 0) { "Unknown task identity" }
        val copied = ArrayList<TranslationQueuedTask>(tasks.size)
        copied.addAll(tasks)
        copied[index] = updated
        return TranslationTaskQueue(limits, copied, nextSequence)
    }

    override fun toString(): String =
        "TranslationTaskQueue(tasks=${tasks.size}, " +
            "running=${tasks.count { it.state == TranslationTaskQueueState.RUNNING }})"

    companion object {
        fun empty(
            limits: TranslationTaskQueueLimits = TranslationTaskQueueLimits.DEFAULT,
        ): TranslationTaskQueue = TranslationTaskQueue(limits, emptyList(), 0)

        /**
         * Persistence-only restoration. The durable store validates every identity, sequence and
         * bound before calling this; live transitions never use this entry point.
         */
        internal fun restore(
            limits: TranslationTaskQueueLimits,
            tasks: List<TranslationQueuedTask>,
            nextSequence: Long,
        ): TranslationTaskQueue = TranslationTaskQueue(limits, tasks, nextSequence)
    }
}

private fun <T> frozenSet(values: Collection<T>): Set<T> =
    java.util.Collections.unmodifiableSet(java.util.LinkedHashSet(values))

private data class FailureMemberKey(
    val batchId: TranslationTaskFailureBatchId,
    val itemId: ItemId,
)

private val NON_TERMINAL_TRANSLATION_STATES: Set<TranslationTaskQueueState> =
    TranslationTaskQueueState.entries.filter { !it.terminal }.toSet()

private val FAILURE_RECORDING_STATES: Set<TranslationTaskQueueState> = setOf(
    TranslationTaskQueueState.RUNNING,
    TranslationTaskQueueState.PAUSED,
    TranslationTaskQueueState.NEEDS_REVIEW,
    TranslationTaskQueueState.FAILED,
)

private fun TranslationQueuedTask.decisionActions(): Map<FailureMemberKey, TranslationTaskCommandAction> {
    val result = LinkedHashMap<FailureMemberKey, TranslationTaskCommandAction>()
    for (record in decisions) {
        val batchId = record.batchId ?: continue
        for (itemId in record.memberIds) {
            result[FailureMemberKey(batchId, itemId)] = record.action
        }
    }
    return result
}

private fun stateFor(
    batches: List<TranslationTaskFailureBatch>,
    decisions: Map<FailureMemberKey, TranslationTaskCommandAction>,
): TranslationTaskQueueState {
    val undecided = ArrayList<TranslationFailureMember>()
    for (batch in batches) {
        for (member in batch.members) {
            if (!member.committed && decisions[FailureMemberKey(batch.id, member.itemId)] == null) {
                undecided.add(member)
            }
        }
    }
    if (undecided.any { it.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT }) {
        return TranslationTaskQueueState.NEEDS_REVIEW
    }
    if (undecided.isNotEmpty()) return TranslationTaskQueueState.FAILED
    if (decisions.values.any { it == TranslationTaskCommandAction.KEEP_ERROR }) {
        return TranslationTaskQueueState.FAILED
    }
    if (decisions.values.any {
            it == TranslationTaskCommandAction.SKIP_MEMBERS ||
                it == TranslationTaskCommandAction.RETRY_MEMBERS
        }
    ) {
        return TranslationTaskQueueState.QUEUED
    }
    return TranslationTaskQueueState.FAILED
}

private fun TranslationQueuedTask.failureFactsAllowCompletion(): Boolean {
    for (batch in batches) {
        for (member in batch.members) {
            if (member.committed) continue
            val memberDecision = decision(batch.id, member.itemId) ?: return false
            if (memberDecision.action != TranslationTaskCommandAction.RETRY_MEMBERS &&
                memberDecision.action != TranslationTaskCommandAction.SKIP_MEMBERS
            ) {
                return false
            }
        }
    }
    return true
}
