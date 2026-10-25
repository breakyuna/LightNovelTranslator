package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Timestamps retained with one durable queue task. They are bookkeeping only: ordering across
 * projects uses the enqueue time, while the exact task order inside one project remains the
 * persisted [TranslationQueuedTask.sequence].
 */
data class TranslationQueueTaskMeta(
    val enqueuedAtMillis: Long,
    val updatedAtMillis: Long,
) {
    init {
        require(enqueuedAtMillis >= 0 && updatedAtMillis >= enqueuedAtMillis) {
            "Invalid translation queue task timestamp"
        }
    }
}

/** One frozen spec, its current immutable task snapshot and its bookkeeping. */
class TranslationQueueEntry(
    val spec: TranslationTaskSpec,
    val task: TranslationQueuedTask,
    val meta: TranslationQueueTaskMeta,
) {
    override fun toString(): String =
        "TranslationQueueEntry(run=${spec.run.runId.value}, state=${task.state})"
}

/**
 * Strictly decoded task facts. Persistence decodes every field and bound before handing them to
 * [TranslationQueueSnapshot.restore]; a malformed task is never admitted partially.
 */
class TranslationQueuedTaskFacts(
    val spec: TranslationTaskSpec,
    val sequence: Long,
    val state: TranslationTaskQueueState,
    batches: List<TranslationTaskFailureBatch>,
    commands: List<TranslationTaskCommandFacts>,
    val executionRevision: Long? = null,
) {
    val batches: List<TranslationTaskFailureBatch> = frozenList(batches)
    val commands: List<TranslationTaskCommandFacts> = frozenList(commands)

    init {
        require(sequence >= 0) { "Invalid translation task sequence" }
        require(executionRevision == null || executionRevision >= spec.revision) {
            "Invalid translation execution revision"
        }
    }

    val identity: TranslationTaskQueueIdentity get() = spec.queueIdentity()
}

/** Decoded command evidence; the aggregate applies the same invariants as a live command. */
class TranslationTaskCommandFacts(
    val commandId: CommandId,
    val action: TranslationTaskCommandAction,
    val batchId: TranslationTaskFailureBatchId?,
    memberIds: List<ItemId>,
    evidence: List<TranslationFailureMember>,
) {
    val memberIds: List<ItemId> = frozenList(memberIds)
    val evidence: List<TranslationFailureMember> = frozenList(evidence)
}

/**
 * Durable queue aggregate of exactly one local project.
 *
 * This type only carries identities, frozen paid policy (including the user's target language and
 * translation instructions), queue states, failure evidence and command facts. It never carries a
 * source text, a translation, a credential or an HTTP header.
 * [revision] is the compare-and-set revision of the stored snapshot; it increases by one per
 * persisted mutation and is never reused.
 */
class TranslationQueueSnapshot private constructor(
    val ref: LocalProjectRef,
    val revision: Long,
    val queue: TranslationTaskQueue,
    specs: List<TranslationTaskSpec>,
    metas: List<TranslationQueueTaskMeta>,
    val updatedAtMillis: Long,
) {
    val specs: List<TranslationTaskSpec> = frozenList(specs)
    val metas: List<TranslationQueueTaskMeta> = frozenList(metas)
    val entries: List<TranslationQueueEntry>

    init {
        require(revision >= 0) { "Invalid translation queue revision" }
        require(updatedAtMillis >= 0) { "Invalid translation queue timestamp" }
        require(queue.tasks.size == this.specs.size && this.specs.size == this.metas.size) {
            "Translation queue metadata does not cover every task"
        }
        queue.tasks.forEachIndexed { index, task ->
            val spec = this.specs[index]
            require(task.identity == spec.queueIdentity()) {
                "Translation queue task does not match its frozen spec"
            }
            require(spec.ref == ref) { "Translation queue task belongs to another project" }
            require(this.metas[index].updatedAtMillis <= updatedAtMillis) {
                "Translation queue task timestamp is newer than the queue"
            }
        }
        entries = frozenList(queue.tasks.mapIndexed { index, task ->
            TranslationQueueEntry(this.specs[index], task, this.metas[index])
        })
    }

    fun entry(identity: TranslationTaskQueueIdentity): TranslationQueueEntry? =
        entries.firstOrNull { it.task.identity == identity }

    fun spec(identity: TranslationTaskQueueIdentity): TranslationTaskSpec? = entry(identity)?.spec

    fun meta(identity: TranslationTaskQueueIdentity): TranslationQueueTaskMeta? =
        entry(identity)?.meta

    fun task(identity: TranslationTaskQueueIdentity): TranslationQueuedTask? = queue.task(identity)

    fun activeTask(ref: LocalProjectRef): TranslationQueuedTask? = queue.activeTask(ref)

    /**
     * Snapshot after one applied pure-queue operation. [queue] must preserve the previous task
     * order and may only append one new task, whose frozen [newSpec] is supplied. Every other
     * task keeps its spec and timestamps. The revision advances exactly once.
     */
    fun updated(
        queue: TranslationTaskQueue,
        changed: TranslationTaskQueueIdentity,
        nowMillis: Long,
        newSpec: TranslationTaskSpec? = null,
    ): TranslationQueueSnapshot {
        require(queue.limits == this.queue.limits) { "Translation queue limits changed" }
        require(nowMillis >= updatedAtMillis) { "Translation queue timestamp moved backwards" }
        require(queue.task(changed) != null) { "Changed task is not in the updated queue" }
        val appended = queue.tasks.size - this.queue.tasks.size
        require(appended == 0 || appended == 1) { "Translation queue changed more than one task" }
        if (appended == 1) {
            requireNotNull(newSpec) { "A newly admitted task requires its frozen spec" }
        } else {
            require(newSpec == null) { "An existing translation queue cannot gain a spec" }
        }
        val nextSpecs = ArrayList<TranslationTaskSpec>(queue.tasks.size)
        val nextMetas = ArrayList<TranslationQueueTaskMeta>(queue.tasks.size)
        queue.tasks.forEachIndexed { index, task ->
            if (index < this.queue.tasks.size) {
                require(task.identity == this.queue.tasks[index].identity) {
                    "Translation queue order changed"
                }
                nextSpecs.add(this.specs[index])
                val previous = this.metas[index]
                nextMetas.add(
                    if (task.identity == changed) {
                        TranslationQueueTaskMeta(previous.enqueuedAtMillis, nowMillis)
                    } else {
                        previous
                    },
                )
            } else {
                val spec = requireNotNull(newSpec)
                require(spec.queueIdentity() == task.identity) {
                    "New translation task does not match its frozen spec"
                }
                nextSpecs.add(spec)
                nextMetas.add(TranslationQueueTaskMeta(nowMillis, nowMillis))
            }
        }
        return of(
            ref = ref,
            revision = Math.addExact(revision, 1L),
            queue = queue,
            specs = nextSpecs,
            metas = nextMetas,
            updatedAtMillis = nowMillis,
        )
    }

    override fun toString(): String =
        "TranslationQueueSnapshot(revision=$revision, tasks=${queue.tasks.size}, policy=redacted)"

    companion object {
        fun empty(
            ref: LocalProjectRef,
            nowMillis: Long,
            limits: TranslationTaskQueueLimits = TranslationTaskQueueLimits.DEFAULT,
        ): TranslationQueueSnapshot =
            TranslationQueueSnapshot(ref, 0, TranslationTaskQueue.empty(limits), emptyList(), emptyList(), nowMillis)

        /** Builds a validated snapshot from already-frozen queue tasks and their metadata. */
        fun of(
            ref: LocalProjectRef,
            revision: Long,
            queue: TranslationTaskQueue,
            specs: List<TranslationTaskSpec>,
            metas: List<TranslationQueueTaskMeta>,
            updatedAtMillis: Long,
        ): TranslationQueueSnapshot =
            TranslationQueueSnapshot(ref, revision, queue, specs, metas, updatedAtMillis)

        /**
         * Persistence-only restoration. Sequences must be the contiguous insertion order produced
         * by [TranslationTaskQueue], so a file that lost or reordered tasks is rejected instead of
         * silently rebuilding a different queue. Every task invariant is rechecked by the regular
         * aggregate constructors.
         */
        fun restore(
            ref: LocalProjectRef,
            revision: Long,
            updatedAtMillis: Long,
            limits: TranslationTaskQueueLimits,
            tasks: List<TranslationQueuedTaskFacts>,
            metas: List<TranslationQueueTaskMeta>,
        ): TranslationQueueSnapshot {
            require(tasks.size <= limits.maxTasks) { "Translation queue exceeds its task budget" }
            require(tasks.size == metas.size) { "Translation queue metadata count mismatch" }
            tasks.forEachIndexed { index, facts ->
                require(facts.sequence == index.toLong()) {
                    "Translation queue sequence is not contiguous"
                }
                require(facts.spec.ref == ref) {
                    "Translation queue task belongs to another project"
                }
            }
            val restored = tasks.map { facts ->
                val commands = facts.commands.map { command ->
                    TranslationTaskCommandRecord(
                        commandId = command.commandId,
                        task = facts.identity,
                        action = command.action,
                        batchId = command.batchId,
                        memberIds = command.memberIds,
                        evidence = command.evidence,
                    )
                }
                TranslationQueuedTask(
                    identity = facts.identity,
                    sequence = facts.sequence,
                    state = facts.state,
                    batches = facts.batches,
                    commands = commands,
                    executionRevision = facts.executionRevision,
                )
            }
            val queue = TranslationTaskQueue.restore(limits, restored, tasks.size.toLong())
            return TranslationQueueSnapshot(
                ref = ref,
                revision = revision,
                queue = queue,
                specs = tasks.map { it.spec },
                metas = metas,
                updatedAtMillis = updatedAtMillis,
            )
        }
    }
}

/** One readable persisted aggregate plus the fingerprint of the exact stored payload. */
data class StoredTranslationQueue(
    val snapshot: TranslationQueueSnapshot,
    val fingerprint: ObjectFingerprint,
) {
    override fun toString(): String =
        "StoredTranslationQueue(revision=${snapshot.revision}, policy=redacted)"
}

/**
 * Per-project durable queue storage used by the coordinator. A missing file means a new queue;
 * a corrupt, foreign, oversized or unknown-version file must throw and never be reported as
 * empty, because that would silently drop admitted tasks.
 */
interface TranslationQueueStorePort {
    suspend fun load(ref: LocalProjectRef): StoredTranslationQueue?

    /** Every existing project queue, strictly decoded; used only by the global scheduler. */
    suspend fun list(): List<StoredTranslationQueue>

    /**
     * Compare-and-set on the stored revision and payload fingerprint. [next] must advance the
     * expected revision by exactly one (or start at 1 when [expected] is null). The write is
     * atomic and returns only after the new snapshot is durable.
     */
    suspend fun compareAndSet(
        ref: LocalProjectRef,
        expected: StoredTranslationQueue?,
        next: TranslationQueueSnapshot,
    ): StoredTranslationQueue
}

/** Another writer changed the same project queue; nothing was written. */
class TranslationQueueConflict(message: String) : IllegalStateException(message)

/** Stored queue metadata is corrupt, foreign, oversized or from an unknown schema version. */
class TranslationQueueCorrupt(message: String) : IllegalArgumentException(message)
