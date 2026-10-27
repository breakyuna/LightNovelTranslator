package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.CancellationException

/** A user pause/cancel intent, persisted before live runner control is attempted. */
enum class TranslationQueueControlIntentKind { PENDING_PAUSE, PENDING_CANCEL }

/**
 * Durable intent to stop one exact queued task. The command identity is the same command that is
 * later written to the queue, so replay after a crash is idempotent and never invents a new user
 * action. No source text, prompt or credential is part of this value.
 */
data class TranslationQueueControlIntent(
    val identity: TranslationTaskQueueIdentity,
    val command: CommandId,
    val kind: TranslationQueueControlIntentKind,
) {
    init {
        require(identity.run.chatId == null) { "A control intent belongs to a translation task" }
    }

    override fun toString(): String =
        "TranslationQueueControlIntent(run=${identity.run.runId.value}, kind=$kind)"
}

/**
 * Narrow persistence port for pause/cancel intents. [clear] must remove the intent only when the
 * stored value still equals [intent]; a newer cancel must not be deleted by a finishing pause.
 * Implementations may only store the redacted identity/command/kind, never execution material.
 */
interface TranslationQueueControlIntentPort {
    /** Intents still awaiting their queue command. A corrupt store must throw, not return empty. */
    suspend fun pending(): List<TranslationQueueControlIntent>

    /** Persists one intent atomically, replacing an older intent for the same task identity. */
    suspend fun save(intent: TranslationQueueControlIntent)

    /** Removes [intent] only if it is still the pending value for that task. */
    suspend fun clear(intent: TranslationQueueControlIntent)
}

/** Default for callers that have not wired durable intent storage yet; it stores nothing. */
object NoTranslationQueueControlIntents : TranslationQueueControlIntentPort {
    override suspend fun pending(): List<TranslationQueueControlIntent> = emptyList()
    override suspend fun save(intent: TranslationQueueControlIntent) = Unit
    override suspend fun clear(intent: TranslationQueueControlIntent) = Unit
}

/** One read-only reconciliation observation. Detail tokens are classifications, never user text. */
enum class TranslationQueueReconciliationIssue {
    /** Fixed-task targets are ERROR/uncertain but the queue task has no failure batch. */
    ERROR_TARGETS_WITHOUT_FAILURE_BATCH,

    /** The fixed-task stage and the durable queue state describe different lifecycle points. */
    TASK_STAGE_QUEUE_STATE_MISMATCH,

    /** A still-open batch member is no longer an ERROR target of the fixed task. */
    BATCH_MEMBER_NOT_IN_TARGET,

    /** Paged and exact fixed-task reads disagree about a member's frozen facts. */
    FAILURE_EVIDENCE_CONFLICT,

    /** Failure or recovery evidence could not be read safely. */
    EVIDENCE_UNREADABLE,

    /** A RUNNING queue task has no durable Run execution evidence. */
    RUNNING_WITHOUT_EXECUTION,

    /** A RUNNING task has an unresolved dispatch or a REQUESTING journal without a receipt. */
    RUNNING_WITHOUT_RECEIPT,
}

/** Reconciliation report status; DEGRADED means at least one read failed. */
enum class TranslationQueueReconciliationStatus { CLEAN, ATTENTION, DEGRADED }

/** One issue bound to the exact task identity. Repeated observations are counted, not duplicated. */
data class TranslationQueueReconciliationFinding(
    val identity: TranslationTaskQueueIdentity,
    val issue: TranslationQueueReconciliationIssue,
    val count: Int = 1,
) {
    init { require(count > 0) { "Invalid reconciliation count" } }

    override fun toString(): String =
        "TranslationQueueReconciliationFinding(run=${identity.run.runId.value}, issue=$issue, count=$count)"
}

/**
 * Read-only reconciliation report. A non-zero [rebuiltFailureBatches] is only present for the
 * explicit rebuild pass; [findings] alone never changes queue or task state.
 */
class TranslationQueueReconciliationReport(
    val status: TranslationQueueReconciliationStatus,
    val scannedQueues: Int,
    val scannedTasks: Int,
    val readFailures: Int,
    findingsList: List<TranslationQueueReconciliationFinding>,
    val rebuiltFailureBatches: Int = 0,
) {
    val findings: List<TranslationQueueReconciliationFinding> =
        java.util.Collections.unmodifiableList(ArrayList(findingsList))

    init {
        require(scannedQueues >= 0 && scannedTasks >= 0 && readFailures >= 0 && rebuiltFailureBatches >= 0)
    }

    fun issueCount(issue: TranslationQueueReconciliationIssue): Int =
        findings.filter { it.issue == issue }.sumOf { it.count }

    override fun toString(): String =
        "TranslationQueueReconciliationReport(status=$status, queues=$scannedQueues, tasks=$scannedTasks, " +
            "readFailures=$readFailures, rebuilt=$rebuiltFailureBatches, findings=${findings.size})"
}

/** Outcome of one bounded rebuild pass. It never dispatches work or changes a terminal task. */
class TranslationQueueFailureBatchRebuildResult(
    val rebuilt: Int,
    val conflicts: Int,
    val readFailures: Int,
) {
    init { require(rebuilt >= 0 && conflicts >= 0 && readFailures >= 0) }
}

/**
 * Reads durable task/queue facts for diagnostics and rebuilds a missing failure batch left by a
 * crash between `tasks.failed()` and `recordFailureBatch()`.
 *
 * Rebuilding appends only the exact members returned by the fixed-task port. The member page and
 * the exact member read must agree on identity, fingerprint, receipt, failure class, usage and
 * committed/terminal flags. A missing or changed member never becomes a guessed batch: a RUNNING
 * task is stopped at NEEDS_REVIEW and the diagnostic count is retained. No method here launches a
 * paid request, creates a Run, retries a member or requeues a task.
 */
internal class TranslationQueueReconciler(
    private val coordinator: TranslationQueueCoordinator,
    private val recovery: TranslationQueueRecoveryPort,
    private val failures: TranslationTaskFailureEvidencePort?,
) {
    /** Strictly read-only: it may request rebuild counts as zero and never writes queue/task state. */
    suspend fun report(): TranslationQueueReconciliationReport {
        val stored = try {
            coordinator.storedQueues()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return TranslationQueueReconciliationReport(
                status = TranslationQueueReconciliationStatus.DEGRADED,
                scannedQueues = 0,
                scannedTasks = 0,
                readFailures = 1,
                findingsList = emptyList(),
            )
        }
        val findings = ArrayList<TranslationQueueReconciliationFinding>()
        var readFailures = 0
        for (queue in stored) {
            for (task in queue.snapshot.queue.tasks) {
                val entry = queue.snapshot.entry(task.identity) ?: continue
                val dispatch = TranslationQueueDispatch(queue, entry)
                val evidence = try {
                    recovery.evidence(dispatch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    readFailures++
                    findings.add(finding(task, TranslationQueueReconciliationIssue.EVIDENCE_UNREADABLE))
                    null
                }
                if (evidence != null) {
                    val stage = evidence.task?.stage
                    if (stageMismatch(task, stage)) {
                        findings.add(finding(task, TranslationQueueReconciliationIssue.TASK_STAGE_QUEUE_STATE_MISMATCH))
                    }
                    if (task.state == TranslationTaskQueueState.RUNNING && !evidence.live) {
                        reportRunningEvidence(task, evidence, findings)
                    }
                } else if (task.state == TranslationTaskQueueState.RUNNING) {
                    findings.add(finding(task, TranslationQueueReconciliationIssue.RUNNING_WITHOUT_EXECUTION))
                }
                readFailures += reportFailureFacts(task, findings)
                if (failures != null && task.batches.isEmpty() &&
                    task.state in FAILURE_REBUILD_STATES
                ) {
                    when (val read = readFailureMembers(task)) {
                        is FailureRead.Ready -> if (read.members.isNotEmpty()) {
                            findings.add(finding(
                                task,
                                TranslationQueueReconciliationIssue.ERROR_TARGETS_WITHOUT_FAILURE_BATCH,
                                read.members.size,
                            ))
                        }
                        FailureRead.Empty, FailureRead.Unavailable -> Unit
                        FailureRead.Conflict -> {
                            readFailures++
                            findings.add(finding(task, TranslationQueueReconciliationIssue.FAILURE_EVIDENCE_CONFLICT))
                        }
                        FailureRead.Unreadable -> {
                            readFailures++
                            findings.add(finding(task, TranslationQueueReconciliationIssue.EVIDENCE_UNREADABLE))
                        }
                    }
                }
            }
        }
        val status = when {
            readFailures > 0 -> TranslationQueueReconciliationStatus.DEGRADED
            findings.isNotEmpty() -> TranslationQueueReconciliationStatus.ATTENTION
            else -> TranslationQueueReconciliationStatus.CLEAN
        }
        return TranslationQueueReconciliationReport(
            status = status,
            scannedQueues = stored.size,
            scannedTasks = stored.sumOf { it.snapshot.queue.tasks.size },
            readFailures = readFailures,
            findingsList = findings,
        )
    }

    /**
     * Rebuilds at most one bounded failure batch per task. A RUNNING task is moved to
     * NEEDS_REVIEW before the caller's generic recovery could misclassify it as PAUSED/FAILED
     * without member evidence. Existing batches, QUEUED tasks and terminal tasks are untouched.
     */
    suspend fun rebuildFailureBatches(): TranslationQueueFailureBatchRebuildResult {
        val stored = try {
            coordinator.storedQueues()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return TranslationQueueFailureBatchRebuildResult(rebuilt = 0, conflicts = 0, readFailures = 1)
        }
        var rebuilt = 0
        var conflicts = 0
        var readFailures = 0
        for (queue in stored) {
            for (task in queue.snapshot.queue.tasks) {
                if (task.batches.isNotEmpty() || task.state !in FAILURE_REBUILD_STATES) continue
                val entry = queue.snapshot.entry(task.identity) ?: continue
                val evidence = try {
                    recovery.evidence(TranslationQueueDispatch(queue, entry))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The fixed-task read below can still prove the batch; retain the failed
                    // diagnostic and let generic recovery handle a task with no member facts.
                    readFailures++
                    null
                }
                // A live execution is never a crash window. An absent fixed-task row normally
                // means the launch has not created targets yet, so an unreadable/empty read below
                // stays diagnostic-only; actual persisted ERROR members are still authoritative.
                if (evidence?.live == true) continue
                when (val read = readFailureMembers(task)) {
                    is FailureRead.Ready -> {
                        if (read.members.isEmpty()) continue
                        val batch = TranslationTaskFailureBatch(
                            translationFailureBatchId(task, read.members),
                            read.members,
                        )
                        val result = try {
                            coordinator.recordFailureBatch(
                                task.identity,
                                batch,
                                requireRunning = task.state == TranslationTaskQueueState.RUNNING,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            conflicts++
                            stopRunningForReview(task)
                            continue
                        }
                        when (result) {
                            is TranslationQueueResult.Applied -> rebuilt++
                            is TranslationQueueResult.Duplicate,
                            is TranslationQueueResult.Unchanged,
                            -> Unit
                            is TranslationQueueResult.Rejected -> {
                                conflicts++
                                stopRunningForReview(task)
                            }
                        }
                    }
                    FailureRead.Conflict -> {
                        conflicts++
                        // A conflict can only exist after persisted member facts were observed.
                        stopRunningForReview(task)
                    }
                    FailureRead.Unreadable -> {
                        readFailures++
                        // A RUNNING task whose fixed-target facts cannot be read is uncertain.
                        // Recovery must not guess success or fall through to a paid dispatch.
                        stopRunningForReview(task)
                    }
                    FailureRead.Empty, FailureRead.Unavailable -> Unit
                }
            }
        }
        return TranslationQueueFailureBatchRebuildResult(rebuilt, conflicts, readFailures)
    }

    private suspend fun stopRunningForReview(task: TranslationQueuedTask) {
        if (task.state != TranslationTaskQueueState.RUNNING) return
        try {
            coordinator.markNeedsReview(task.identity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The diagnostic count still records that recovery could not settle this task.
        }
    }

    private fun reportRunningEvidence(
        task: TranslationQueuedTask,
        evidence: TranslationQueueRecoveryEvidence,
        findings: MutableList<TranslationQueueReconciliationFinding>,
    ) {
        val run = evidence.run
        if (run == null) {
            findings.add(finding(task, TranslationQueueReconciliationIssue.RUNNING_WITHOUT_EXECUTION))
        }
        val taskEvidence = evidence.task
        val unresolved = taskEvidence != null && taskEvidence.unresolvedDispatches > 0
        val requesting = run?.stage == StoredRunStage.REQUESTING
        val batchUncertain = task.batches.any { batch ->
            batch.members.any { member ->
                !member.committed && member.receipt == TranslationFailureReceipt.DISPATCHED_NO_RECEIPT
            }
        }
        if (unresolved || requesting || run?.needsReview == true || batchUncertain) {
            findings.add(finding(task, TranslationQueueReconciliationIssue.RUNNING_WITHOUT_RECEIPT))
        }
    }

    /** Checks only still-open members: a resolved retry may legitimately become PROCESSED later. */
    private suspend fun reportFailureFacts(
        task: TranslationQueuedTask,
        findings: MutableList<TranslationQueueReconciliationFinding>,
    ): Int {
        val port = failures ?: return 0
        if (task.batches.isEmpty()) return 0
        var readFailures = 0
        for (batch in task.batches) {
            val open = batch.members.filter { member ->
                !member.committed && task.decision(batch.id, member.itemId) == null
            }
            if (open.isEmpty()) continue
            val fresh = try {
                port.failureMembers(task.identity.ref, task.identity.run, open.map { it.itemId })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                // The store refuses an exact selection whose target row no longer exists.
                findings.add(finding(task, TranslationQueueReconciliationIssue.BATCH_MEMBER_NOT_IN_TARGET))
                continue
            } catch (_: TranslationTaskNeedsReview) {
                findings.add(finding(task, TranslationQueueReconciliationIssue.FAILURE_EVIDENCE_CONFLICT))
                continue
            } catch (_: Exception) {
                readFailures++
                findings.add(finding(task, TranslationQueueReconciliationIssue.EVIDENCE_UNREADABLE))
                continue
            }
            val byId = fresh.associateBy { it.itemId }
            for (member in open) {
                val exact = byId[member.itemId]
                when {
                    exact == null -> findings.add(finding(
                        task,
                        TranslationQueueReconciliationIssue.BATCH_MEMBER_NOT_IN_TARGET,
                    ))
                    exact != member -> findings.add(finding(
                        task,
                        TranslationQueueReconciliationIssue.FAILURE_EVIDENCE_CONFLICT,
                    ))
                }
            }
        }
        return readFailures
    }

    private suspend fun readFailureMembers(task: TranslationQueuedTask): FailureRead {
        val port = failures ?: return FailureRead.Unavailable
        val selected = LinkedHashMap<ItemId, TranslationFailureMember>()
        var after = 0L
        var pages = 0
        try {
            while (pages < RECONCILIATION_FAILURE_PAGES &&
                selected.size < TranslationTaskFailureBatch.MAX_MEMBERS
            ) {
                val page = port.failures(task.identity.ref, task.identity.run, after)
                if (page.review.ref != task.identity.ref || page.review.run != task.identity.run) {
                    return FailureRead.Conflict
                }
                for (member in page.members) {
                    if (selected.size >= TranslationTaskFailureBatch.MAX_MEMBERS) break
                    if (member.committed || task.isCommitted(member.itemId) ||
                        task.hasFinalErrorDecision(member.itemId) ||
                        task.hasUnresolvedMember(member.itemId)
                    ) continue
                    val previous = selected.putIfAbsent(member.itemId, member)
                    if (previous != null && previous != member) return FailureRead.Conflict
                }
                val next = page.next ?: break
                if (next <= after) return FailureRead.Conflict
                after = next
                pages++
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            // A first-page failure with no observed member may simply mean no fixed task exists.
            return if (selected.isEmpty()) FailureRead.Unreadable else FailureRead.Conflict
        } catch (_: TranslationTaskNeedsReview) {
            return if (selected.isEmpty()) FailureRead.Unreadable else FailureRead.Conflict
        } catch (_: Exception) {
            return FailureRead.Unreadable
        }
        if (selected.isEmpty()) return FailureRead.Empty
        val ordered = selected.values.sortedBy { it.itemId.value }
            .take(TranslationTaskFailureBatch.MAX_MEMBERS)
        // The page is only a candidate set. The exact target/audit read is the authority; any
        // disagreement stops recovery instead of freezing a guessed member fact.
        val exact = try {
            port.failureMembers(task.identity.ref, task.identity.run, ordered.map { it.itemId })
                .sortedBy { it.itemId.value }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return FailureRead.Conflict
        } catch (_: TranslationTaskNeedsReview) {
            return FailureRead.Conflict
        } catch (_: Exception) {
            return FailureRead.Unreadable
        }
        if (exact != ordered) return FailureRead.Conflict
        return FailureRead.Ready(ordered)
    }

    private fun stageMismatch(task: TranslationQueuedTask, stage: String?): Boolean {
        if (stage == null) return false
        return when (task.state) {
            TranslationTaskQueueState.RUNNING ->
                stage == "COMPLETE" || stage == "CANCELLED" || stage == "FAILED" || stage == "NEEDS_REVIEW"
            TranslationTaskQueueState.QUEUED ->
                stage == "COMPLETE" || stage == "CANCELLED" || stage == "FAILED" || stage == "NEEDS_REVIEW"
            TranslationTaskQueueState.PAUSED -> stage == "COMPLETE" || stage == "CANCELLED"
            TranslationTaskQueueState.NEEDS_REVIEW -> stage == "COMPLETE" || stage == "CANCELLED"
            TranslationTaskQueueState.FAILED -> stage == "COMPLETE" || stage == "CANCELLED"
            TranslationTaskQueueState.COMPLETED, TranslationTaskQueueState.COMPLETED_WITH_ERRORS ->
                stage != "COMPLETE"
            // Cancellation deliberately leaves the fixed task's last checkpoint for audit.
            TranslationTaskQueueState.CANCELLED -> false
        }
    }

    private fun finding(
        task: TranslationQueuedTask,
        issue: TranslationQueueReconciliationIssue,
        count: Int = 1,
    ): TranslationQueueReconciliationFinding =
        TranslationQueueReconciliationFinding(task.identity, issue, count)

    private sealed interface FailureRead {
        class Ready(val members: List<TranslationFailureMember>) : FailureRead
        data object Empty : FailureRead
        data object Unavailable : FailureRead
        data object Conflict : FailureRead
        data object Unreadable : FailureRead
    }
}

/** Same deterministic identity used by the settlement path, so replay cannot duplicate a batch. */
internal fun translationFailureBatchId(
    task: TranslationQueuedTask,
    members: List<TranslationFailureMember>,
): TranslationTaskFailureBatchId {
    val round = task.batches.size + 1
    val material = buildString {
        append(task.identity.run.commandId.value); append('\u0000')
        append(task.identity.revision); append('\u0000')
        append(round)
        for (member in members) {
            append('\u0000'); append(member.itemId.value)
            append('\u0000'); append(member.fingerprint.sha256)
            append('\u0000'); append(member.failure.name)
            append('\u0000'); append(member.receipt.name)
            append('\u0000'); append(member.usage.name)
            append('\u0000'); append(member.committed)
            append('\u0000'); append(member.terminal)
        }
    }
    val digest = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(material))
    return TranslationTaskFailureBatchId("queue-failure-$round-$digest")
}

/** Bounds one diagnostics/rebuild read to the same target window as execution settlement. */
private const val RECONCILIATION_FAILURE_PAGES = 100

private val FAILURE_REBUILD_STATES: Set<TranslationTaskQueueState> = setOf(
    TranslationTaskQueueState.RUNNING,
    TranslationTaskQueueState.PAUSED,
    TranslationTaskQueueState.NEEDS_REVIEW,
    TranslationTaskQueueState.FAILED,
)
