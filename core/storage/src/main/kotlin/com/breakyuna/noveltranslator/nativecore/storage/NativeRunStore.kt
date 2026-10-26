package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Device-local execution journal. Recovery reads facts; it never obtains write/model authority. */
class NativeRunStore(context: Context, private val projects: TextProjectPort, private val runtime: RuntimePort) : RunJournalPort {
    private val db = NativeAppDatabase.open(context)
    private val processId = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private var liveHandle: RunJournalHandle? = null
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }
    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })
    private fun now() = System.currentTimeMillis().coerceAtLeast(0)

    override suspend fun begin(ref: LocalProjectRef, run: RunIdentity, owner: RuntimeOwner): RunJournalHandle = io {
        val active = runtime.snapshot().activeRun
        require(run.projectId == ref.projectId && active != null && active.identity == run && active.owner == owner)
        projects.open(ref)
        recoverAbandoned()
        val id = UUID.randomUUID().toString()
        val time = now()
        transaction {
            check(db.runs().activeCount(processId) == 0) { "Previous execution not settled" }
            db.runs().insert(NativeRunRow(id, processId, ref.instanceId.value, ref.generation.value,
                ref.projectId.value, run.chatId?.value, run.runId.value, run.commandId.value, owner.name,
                "RUNNING", "ACCEPTED", null, 0, 0, "[]", null, null, false, time, time))
        }
        RunJournalHandle(id, ref, run).also { liveHandle = it }
    }

    override suspend fun checkpoint(handle: RunJournalHandle, checkpoint: RunCheckpoint): Unit = io {
        transaction {
            val row = owned(handle)
            check(row.state == "RUNNING")
            val revision = checkpoint.revision
            val request = checkpoint.agentRequest
            val attempt = request?.attemptId ?: checkpoint.translationAttempt
            require(checkpoint.committedItems >= row.committedItems && checkpoint.requests >= row.requests)
            require(row.revision == null || (revision != null && revision >= row.revision))
            require(request == null || request.run == handle.run)
            require(checkpoint.translationAttempt == null || (row.owner == RuntimeOwner.TRANSLATION.name && handle.run.chatId == null))
            // Never erase an unresolved request/commit by beginning another one.
            when (row.stage) {
                "REQUESTING" -> require(checkpoint.stage == StoredRunStage.RESPONSE_RECORDED ||
                    (checkpoint.stage == StoredRunStage.REQUEST_FAILED && attempt?.value == row.requestAttemptId &&
                        checkpoint.requests == row.requests) ||
                    (checkpoint.stage == StoredRunStage.REQUESTING && attempt?.value == row.requestAttemptId &&
                        request?.branchId?.value == row.requestBranchId &&
                        checkpoint.requests == row.requests))
                "MARKING_FAILURE" -> require(checkpoint.stage == StoredRunStage.COMMITTED ||
                    (checkpoint.stage == StoredRunStage.MARKING_FAILURE && checkpoint.pendingCommands == commands(row)))
                "COMMITTING" -> require(checkpoint.stage == StoredRunStage.COMMITTED ||
                    (checkpoint.stage == StoredRunStage.COMMITTING && checkpoint.pendingCommands == commands(row)))
            }
            val failedReceipt = if (checkpoint.stage == StoredRunStage.REQUEST_FAILED) {
                require(row.owner == RuntimeOwner.TRANSLATION.name && row.stage == "REQUESTING" && attempt != null)
                db.translationRequests().find(attempt.value)?.also {
                    require(NativeTranslationAuditStore.matches(it, ref(row), handle.run))
                }
            } else null
            check(db.runs().update(row.copy(stage = checkpoint.stage.name, revision = checkpoint.revision,
                needsReview = row.needsReview || failedReceipt?.state == "DISPATCHED",
                committedItems = checkpoint.committedItems, requests = checkpoint.requests,
                pendingCommands = JsonArray(checkpoint.pendingCommands.map { JsonPrimitive(it.value) }).toString(),
                requestAttemptId = attempt?.value, requestBranchId = checkpoint.agentRequest?.branchId?.value,
                updatedAt = now())) == 1)
        }
    }

    override suspend fun finish(handle: RunJournalHandle, state: StoredRunState): Unit = io {
        try {
            require(state != StoredRunState.RUNNING && state != StoredRunState.INTERRUPTED)
            val row = owned(handle)
            check(row.state == "RUNNING")
            val reconciled = reconcileRequest(reconcileCommit(row))
            // A dispatch checkpoint without a recorded response is conservatively uncertain,
            // including cancellation/failure. No automatic retry is made from this record.
            val uncertain = reconciled.needsReview || reconciled.stage == "REQUESTING"
            check(db.runs().update(reconciled.copy(state = if (uncertain) "INTERRUPTED" else state.name,
                needsReview = uncertain, updatedAt = now())) == 1)
        } finally {
            if (liveHandle == handle) liveHandle = null
        }
    }

    override suspend fun recoverInterrupted(): Unit = io { recoverAbandoned() }

    private suspend fun recoverAbandoned() {
        while (true) {
            val rows = db.runs().abandoned(processId, liveHandle?.executionId)
            if (rows.isEmpty()) return
            for (row in rows) {
                currentCoroutineContext().ensureActive()
                val recovered = try { reconcileRequest(reconcileCommit(row)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { row.copy(needsReview = true) }
                check(db.runs().update(recovered.copy(state = "INTERRUPTED",
                    needsReview = recovered.needsReview || recovered.stage == "REQUESTING", updatedAt = now())) == 1)
            }
        }
    }

    private suspend fun reconcileCommit(row: NativeRunRow): NativeRunRow {
        if (row.stage != "COMMITTING" && row.stage != "MARKING_FAILURE") return row
        val commands = commands(row)
        if (commands.isEmpty()) return row.copy(needsReview = true)
        val receipts = commands.map { projects.receipt(ref(row), it) }
        val committed = receipts.filterNotNull().filter { it.status == ManualEditStatus.COMMITTED }
        if (committed.isEmpty()) return row
        if (committed.size != commands.size) return row.copy(needsReview = true)
        return row.copy(stage = "COMMITTED", revision = committed.maxOf { it.revision },
            committedItems = if (row.stage == "MARKING_FAILURE") row.committedItems else Math.addExact(row.committedItems, commands.size), pendingCommands = "[]")
    }

    private fun reconcileRequest(row: NativeRunRow): NativeRunRow {
        if (row.stage != "REQUESTING" || row.requestAttemptId == null) return row
        if (row.owner == RuntimeOwner.TRANSLATION.name) {
            // The remote adapter must reserve this audit row before HTTP. Absence proves the
            // process never crossed that boundary; legacy checkpoints without attempts stay uncertain.
            val receipt = db.translationRequests().find(row.requestAttemptId) ?: return row.copy(stage = "ACCEPTED")
            if (!NativeTranslationAuditStore.matches(receipt, ref(row), storedHandle(row).run)) return row.copy(needsReview = true)
            return if (receipt.state == "RECEIVED") row.copy(stage = "RESPONSE_RECORDED") else row
        }
        val key = request(row) ?: return row.copy(needsReview = true)
        val receipt = db.agent().request(row.requestAttemptId) ?: return row.copy(stage = "ACCEPTED")
        if (receipt.requestKey != AgentRequestFingerprint.key(key)) return row.copy(needsReview = true)
        return when (receipt.state) {
            "PREPARED" -> row.copy(stage = "ACCEPTED")
            "COMPLETED" -> row.copy(stage = "RESPONSE_RECORDED")
            else -> row
        }
    }

    override suspend fun recent(ref: LocalProjectRef, limit: Int): List<StoredRun> = io {
        require(limit in 1..20)
        db.runs().recent(ref.instanceId.value, ref.generation.value, ref.projectId.value, limit).map { stored(it) }
    }

    /**
     * Settles executions that are still WAITING_APPROVAL for exactly this logical Run after a
     * file-backed approval (workspace files) reached a terminal state. The SQL `approvalState`
     * union cannot see file stores, so without this the previous execution would remain
     * "waiting approval" forever and crowd the recent-run window.
     */
    suspend fun settleWaitingApproval(
        ref: LocalProjectRef,
        run: RunIdentity,
        state: StoredRunState,
    ): Int = io {
        require(run.projectId == ref.projectId && run.chatId != null) { "Approval run belongs to another project" }
        require(state != StoredRunState.RUNNING && state != StoredRunState.INTERRUPTED &&
            state != StoredRunState.WAITING_APPROVAL) { "Invalid approval settlement" }
        val rows = db.runs().waitingApprovals(ref.instanceId.value, ref.generation.value,
            ref.projectId.value, run.runId.value, run.commandId.value, run.chatId.value)
        if (rows.isEmpty()) return@io 0
        transaction {
            rows.forEach { row ->
                check(db.runs().update(row.copy(state = state.name, updatedAt = now())) == 1)
            }
        }
        rows.size
    }

    /**
     * Exact logical-Run lookup. A recent-window lookup is not sufficient: queue reconciliation must
     * observe the real journal fact even when many later executions exist.
     */
    override suspend fun find(ref: LocalProjectRef, run: RunIdentity): StoredRun? = io {
        require(run.projectId == ref.projectId) { "Run belongs to another project" }
        db.runs().findRun(ref.instanceId.value, ref.generation.value, ref.projectId.value,
            run.runId.value, run.commandId.value, run.chatId?.value)?.let { stored(it) }
    }

    private suspend fun stored(row: NativeRunRow): StoredRun {
        val context = currentCoroutineContext()
        val settled = if (row.state == "WAITING_APPROVAL") when (db.runs().approvalState(
            row.instanceId, row.generation, row.projectId, row.chatId, row.runId)) {
            // Item/PDF proposals use COMMITTED/PARTIAL, asset import uses APPLIED. Only a terminal
            // state settles the run; CONSUMED/PENDING/UNKNOWN remain waiting.
            "COMMITTED", "PARTIAL", "APPLIED" -> row.copy(state = "SUCCEEDED")
            "REJECTED", "CANCELLED" -> row.copy(state = "CANCELLED")
            "CONFLICT", "STALE" -> row.copy(state = "FAILED")
            else -> row
        } else row
        if (settled != row) check(db.runs().update(settled.copy(updatedAt = now())) == 1)
        return StoredRun(storedHandle(row), RuntimeOwner.valueOf(row.owner), StoredRunState.valueOf(settled.state),
            RunCheckpoint(StoredRunStage.valueOf(row.stage), row.revision, row.committedItems, row.requests, commands(row), request(row),
                row.requestAttemptId?.takeIf { row.owner == RuntimeOwner.TRANSLATION.name && row.stage == "REQUESTING" }?.let(::RequestAttemptId)),
            row.needsReview, if (row.owner == RuntimeOwner.TRANSLATION.name)
                NativeTranslationAuditStore.totals(db, ref(row), storedHandle(row).run) { context.ensureActive() } else null)
    }

    /**
     * Read-only fixed-task decisions for exactly this logical Run. A member decision is written
     * before its queue command by the adjudicator; after a crash this method is the only source of
     * the original command identity. It never writes, dispatches or invents evidence.
     */
    suspend fun taskDecisionCommands(ref: LocalProjectRef, run: RunIdentity): List<StoredTranslationTaskCommand> = io {
        require(run.projectId == ref.projectId && run.chatId == null) { "Task decision belongs to another project" }
        val id = taskKey(run)
        val sqlite = db.openHelper.readableDatabase
        val decisions = ArrayList<Triple<Long, String, String>>()
        sqlite.query(
            "SELECT rowid, command, action FROM native_translation_task_decisions WHERE task=? ORDER BY rowid",
            arrayOf<Any?>(id),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                decisions.add(Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)))
            }
        }
        val result = ArrayList<StoredTranslationTaskCommand>(decisions.size)
        for ((sequence, command, rawAction) in decisions) {
            currentCoroutineContext().ensureActive()
            val parsed = replayAction(rawAction) ?: continue
            val (action, batchId) = parsed
            try {
                if (action.adjudication) {
                    val members = ArrayList<Pair<ItemId, ObjectFingerprint>>()
                    var valid = true
                    sqlite.query(
                        "SELECT itemId, action, fingerprint FROM native_translation_member_decisions " +
                            "WHERE task=? AND command=? ORDER BY itemId",
                        arrayOf<Any?>(id, command),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            if (cursor.getString(1) != action.name) { valid = false; break }
                            members.add(ItemId(cursor.getLong(0)) to ObjectFingerprint(cursor.getString(2)))
                        }
                    }
                    if (!valid || members.isEmpty()) continue
                    result.add(StoredTranslationTaskCommand(CommandId(command), action, batchId,
                        members.map { it.first }, members.map { it.second }, sequence))
                } else {
                    result.add(StoredTranslationTaskCommand(CommandId(command), action, null,
                        emptyList(), emptyList(), sequence))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                // A corrupt/foreign decision row is never treated as replay authority.
            }
        }
        result
    }

    private fun replayAction(raw: String): Pair<TranslationTaskCommandAction, TranslationTaskFailureBatchId?>? {
        val separator = raw.indexOf(':')
        val name = if (separator < 0) raw else raw.substring(0, separator)
        val action = TranslationTaskCommandAction.entries.firstOrNull { it.name == name } ?: return null
        val batch = if (separator < 0) null else raw.substring(separator + 1)
        return try {
            when {
                action.adjudication -> {
                    if (batch.isNullOrBlank()) null
                    else action to TranslationTaskFailureBatchId(batch)
                }
                action == TranslationTaskCommandAction.TERMINATE_TASK && batch == null -> action to null
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun taskKey(run: RunIdentity): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(
            JsonArray(listOf(run.projectId.value, run.runId.value, run.commandId.value).map(::JsonPrimitive)).toString()))

    private fun commands(row: NativeRunRow): List<CommandId> {
        require(row.pendingCommands.length <= 8192)
        return Json.parseToJsonElement(row.pendingCommands).jsonArray.map { CommandId(it.jsonPrimitive.content) }
            .also { require(it.size <= 20 && it.distinct().size == it.size) }
    }
    private fun ref(row: NativeRunRow) = LocalProjectRef(ProjectId(row.projectId), ProjectInstanceId(row.instanceId), RecoveryGeneration(row.generation))
    private fun request(row: NativeRunRow): AgentRequestKey? = row.requestAttemptId?.takeIf { row.owner == RuntimeOwner.AGENT.name }?.let {
        AgentRequestKey(storedHandle(row).run, BranchId(requireNotNull(row.requestBranchId)), RequestAttemptId(it))
    }
    private fun storedHandle(row: NativeRunRow) = RunJournalHandle(row.executionId, ref(row),
        RunIdentity(ProjectId(row.projectId), row.chatId?.let(::ChatId), RunId(row.runId), CommandId(row.commandId)))
    private fun owned(handle: RunJournalHandle): NativeRunRow = requireNotNull(db.runs().find(handle.executionId)).also {
        require(it.processId == processId && storedHandle(it) == handle) { "Foreign execution" }
    }
    suspend fun close(): Unit = io { db.close() }
}
