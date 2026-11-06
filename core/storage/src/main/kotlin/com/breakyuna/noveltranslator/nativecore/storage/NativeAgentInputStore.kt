package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputAppend
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputAppendReceipt
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputAppendSettlement
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputDraft
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputQueue
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputQueueLimits
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputStatePort
import com.breakyuna.noveltranslator.nativecore.domain.AgentRunStage
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerAttempt
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerCommand
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerFailure
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerState
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerStatus
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MessageId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectWriteLease
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePort
import com.breakyuna.noveltranslator.nativecore.domain.StoredAgentInputQueue
import com.breakyuna.noveltranslator.nativecore.domain.StoredAgentSteerLedger
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Device-local persistence for the process-local Agent input queue and steer ledger.
 *
 * The store is an append/replace boundary, not an input engine: it never calls
 * [AgentInputQueue.beginAppend], [AgentInputQueue.acknowledgeAppended], [AgentSteerState.submit]
 * or [AgentSteerState.injectAtToolBoundary]. A restored snapshot therefore cannot send, append,
 * inject or replay anything by itself.
 *
 * Reads are explicitly read-only. Writes require a [ProjectWriteLease] obtained from the real
 * runtime and are rejected unless that runtime currently has its single project-write slot held.
 * The store cannot inspect an opaque lease's ownership; the runtime's unique slot is the
 * serialization boundary.
 */
class NativeAgentInputStore(
    context: Context,
    private val projects: NativeTextProjectStore,
    private val runtime: RuntimePort,
) : AgentInputStatePort {
    private val db = NativeAppDatabase.open(context)
    private val mutex = Mutex()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })
    private fun now() = System.currentTimeMillis().coerceAtLeast(0)

    override suspend fun loadInputQueue(ref: LocalProjectRef, chat: ChatId): StoredAgentInputQueue? = io {
        projects.open(ref)
        transaction { readQueue(ref, chat) }
    }

    override suspend fun replaceInputQueue(
        write: ProjectWriteLease,
        snapshot: StoredAgentInputQueue,
        settlement: AgentInputAppendSettlement?,
    ): StoredAgentInputQueue = io {
        requireWriteLease(write)
        val ref = snapshot.ref
        val chat = snapshot.queue.chat.chatId
        require(snapshot.queue.chat.projectId == ref.projectId) {
            "Queue Chat belongs to a different Project"
        }
        projects.open(ref)
        transaction {
            val current = readQueue(ref, chat)
            validateQueueTransition(current, snapshot, settlement)
            val revision = Math.addExact(current?.revision ?: 0L, 1L)
            val pendingMessageId = nextPendingMessageId(current, snapshot)
            val receipt = when (settlement) {
                is AgentInputAppendSettlement.Confirmed -> settlement.receipt
                else -> current?.lastAppendReceipt
            }
            val stored = StoredAgentInputQueue(ref, revision, snapshot.queue, pendingMessageId, receipt)
            db.agentInput().deleteInputDrafts(
                ref.instanceId.value,
                ref.generation.value,
                ref.projectId.value,
                chat.value,
            )
            db.agentInput().deleteInputQueue(
                ref.instanceId.value,
                ref.generation.value,
                ref.projectId.value,
                chat.value,
            )
            db.agentInput().insertInputQueue(queueRow(stored, now()))
            val drafts = draftRows(stored)
            if (drafts.isNotEmpty()) db.agentInput().insertInputDrafts(drafts)
            stored
        }
    }

    override suspend fun loadSteerLedger(
        ref: LocalProjectRef,
        chat: ChatId,
        run: RunIdentity,
    ): StoredAgentSteerLedger? = io {
        require(run.projectId == ref.projectId && run.chatId == chat) {
            "Steer Run belongs to a different Project/Chat"
        }
        projects.open(ref)
        transaction { readSteer(ref, chat, run) }
    }

    override suspend fun replaceSteerLedger(
        write: ProjectWriteLease,
        snapshot: StoredAgentSteerLedger,
    ): StoredAgentSteerLedger = io {
        requireWriteLease(write)
        val ref = snapshot.ref
        val run = snapshot.state.run
        val chat = snapshot.state.chat.chatId
        require(run.projectId == ref.projectId && run.chatId == chat) {
            "Steer Run belongs to a different Project/Chat"
        }
        projects.open(ref)
        transaction {
            val current = readSteer(ref, chat, run)
            validateSteerTransition(current, snapshot)
            val revision = Math.addExact(current?.revision ?: 0L, 1L)
            val stored = StoredAgentSteerLedger(ref, revision, snapshot.state)
            db.agentInput().deleteSteerAttempts(
                ref.instanceId.value,
                ref.generation.value,
                ref.projectId.value,
                chat.value,
                run.runId.value,
                run.commandId.value,
            )
            db.agentInput().deleteSteerRun(
                ref.instanceId.value,
                ref.generation.value,
                ref.projectId.value,
                chat.value,
                run.runId.value,
                run.commandId.value,
            )
            db.agentInput().insertSteerRun(steerRunRow(stored, now()))
            val attempts = steerAttemptRows(stored)
            if (attempts.isNotEmpty()) db.agentInput().insertSteerAttempts(attempts)
            stored
        }
    }

    /** Metadata only; attempt text is loaded only after a run is explicitly opened. */
    suspend fun steerRunPage(ref: LocalProjectRef, chat: ChatId,
        cursor: com.breakyuna.noveltranslator.nativecore.domain.AgentSteerRunCursor? = null):
        com.breakyuna.noveltranslator.nativecore.domain.AgentSteerRunPage = io {
        projects.open(ref)
        val rows = db.agentInput().steerRunPage(ref.instanceId.value, ref.generation.value, ref.projectId.value,
            chat.value, cursor?.run?.value, cursor?.command?.value)
        val runs = rows.take(20).map { row ->
            require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value &&
                row.projectId == ref.projectId.value && row.chatId == chat.value && row.revision >= 0)
            com.breakyuna.noveltranslator.nativecore.domain.AgentSteerRunSummary(
                RunIdentity(ref.projectId, chat, RunId(row.runId), CommandId(row.runCommandId)),
                AgentRunStage.valueOf(row.stage), row.revision)
        }
        com.breakyuna.noveltranslator.nativecore.domain.AgentSteerRunPage(runs, rows.takeIf { it.size > 20 }?.get(19)?.let {
            com.breakyuna.noveltranslator.nativecore.domain.AgentSteerRunCursor(RunId(it.runId), CommandId(it.runCommandId))
        })
    }

    suspend fun close(): Unit = io { db.close() }

    /**
     * The runtime owns the only write slot. Holding [write] is the caller's proof; the store can
     * only verify that the slot is actually occupied and remains open.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun requireWriteLease(write: ProjectWriteLease) {
        val snapshot = runtime.snapshot()
        require(!snapshot.closed) { "Runtime is closed" }
        require(snapshot.projectWriteRunning) { "A real Runtime project write lease is required" }
    }

    private fun readQueue(ref: LocalProjectRef, chat: ChatId): StoredAgentInputQueue? {
        val row = db.agentInput().inputQueue(
            ref.instanceId.value,
            ref.generation.value,
            ref.projectId.value,
            chat.value,
        ) ?: run {
            check(
                db.agentInput().inputDraftCount(
                    ref.instanceId.value,
                    ref.generation.value,
                    ref.projectId.value,
                    chat.value,
                ) == 0,
            ) { "Draft rows without a queue" }
            return null
        }
        require(
            row.instanceId == ref.instanceId.value &&
                row.generation == ref.generation.value &&
                row.projectId == ref.projectId.value &&
                row.chatId == chat.value,
        ) { "Persisted queue identity mismatch" }
        val queueChat = ChatIdentity(ProjectId(row.projectId), ChatId(row.chatId))
        val limits = AgentInputQueueLimits(row.maxDrafts, row.maxDraftChars, row.maxTotalChars)
        val draftRows = db.agentInput().inputDrafts(
            ref.instanceId.value,
            ref.generation.value,
            ref.projectId.value,
            chat.value,
        )
        require(draftRows.size <= AgentInputQueueLimits.HARD_MAX_DRAFTS) {
            "Persisted draft count exceeds the hard budget"
        }
        require(draftRows.map { it.position } == draftRows.indices.toList()) {
            "Persisted draft positions are not contiguous"
        }
        require(draftRows.all {
            it.instanceId == row.instanceId &&
                it.generation == row.generation &&
                it.projectId == row.projectId &&
                it.chatId == row.chatId
        }) { "Persisted draft identity mismatch" }

        val pendingDraftId = row.pendingDraftId
        val pendingDraftRevision = row.pendingDraftRevision
        val pendingAttemptId = row.pendingAttemptId
        val pendingFields = listOf(pendingDraftId, pendingDraftRevision, pendingAttemptId, row.pendingMessageId)
        require(pendingFields.none { it == null } || pendingFields.all { it == null } ||
            (pendingDraftId != null && pendingDraftRevision != null && pendingAttemptId != null)) {
            "Incomplete pending append state"
        }
        require((row.runId == null) == (row.runCommandId == null)) {
            "Incomplete queue Run binding"
        }
        val run = row.runId?.let {
            RunIdentity(
                ProjectId(row.projectId),
                ChatId(row.chatId),
                RunId(it),
                CommandId(requireNotNull(row.runCommandId)),
            )
        }
        val drafts = draftRows.map { draftRow ->
            require((draftRow.runId == null) == (draftRow.runCommandId == null)) {
                "Incomplete draft Run binding"
            }
            val draftRun = draftRow.runId?.let {
                RunIdentity(
                    ProjectId(row.projectId),
                    ChatId(row.chatId),
                    RunId(it),
                    CommandId(requireNotNull(draftRow.runCommandId)),
                )
            }
            val expectedState = if (draftRow.draftId == pendingDraftId) "PENDING_APPEND" else "QUEUED"
            require(draftRow.state == expectedState) { "Persisted draft state does not match its queue" }
            AgentInputDraft.restore(
                CommandId(draftRow.draftId),
                queueChat,
                draftRun,
                draftRow.text,
                draftRow.revision,
            )
        }
        val pending = if (pendingDraftId == null) {
            require(row.pendingMessageId == null) { "Pending message without a pending append" }
            null
        } else {
            val pendingRun = requireNotNull(run) { "Pending append requires a queue Run binding" }
            val head = requireNotNull(drafts.firstOrNull()) {
                "Pending append requires a persisted head draft"
            }
            require(head.id.value == pendingDraftId && head.revision == pendingDraftRevision) {
                "Pending append must be the head draft with its persisted revision"
            }
            AgentInputAppend(
                head,
                pendingRun,
                RequestAttemptId(requireNotNull(pendingAttemptId)),
            )
        }
        val queue = AgentInputQueue.restore(queueChat, limits, run, row.paused, drafts, pending)
        return StoredAgentInputQueue(
            ref,
            row.revision,
            queue,
            row.pendingMessageId?.let(::MessageId),
            readReceipt(row, queueChat),
        )
    }

    private fun readReceipt(
        row: NativeAgentInputQueueRow,
        chat: ChatIdentity,
    ): AgentInputAppendReceipt? {
        val stringFields = listOf(
            row.lastReceiptRunId,
            row.lastReceiptRunCommandId,
            row.lastReceiptDraftId,
            row.lastReceiptAttemptId,
            row.lastReceiptMessageId,
        )
        val revision = row.lastReceiptDraftRevision
        val present = stringFields.count { it != null } + (if (revision != null) 1 else 0)
        require(present == 0 || present == 6) { "Incomplete append receipt" }
        if (present == 0) return null
        return AgentInputAppendReceipt(
            CommandId(requireNotNull(row.lastReceiptDraftId)),
            chat,
            RunIdentity(
                ProjectId(row.projectId),
                ChatId(row.chatId),
                RunId(requireNotNull(row.lastReceiptRunId)),
                CommandId(requireNotNull(row.lastReceiptRunCommandId)),
            ),
            RequestAttemptId(requireNotNull(row.lastReceiptAttemptId)),
            requireNotNull(revision),
            MessageId(requireNotNull(row.lastReceiptMessageId)),
        )
    }

    private fun validateQueueTransition(
        current: StoredAgentInputQueue?,
        next: StoredAgentInputQueue,
        settlement: AgentInputAppendSettlement?,
    ) {
        if (current == null) {
            require(next.revision == 0L) { "Stale input queue revision" }
            require(settlement == null) { "No pending append to settle" }
            return
        }
        require(next.revision == current.revision) { "Stale input queue revision" }
        current.lastAppendReceipt?.draftId?.let { consumedId ->
            require(next.queue.drafts.none { it.id == consumedId }) {
                "A consumed draft identity cannot be reused"
            }
        }
        val currentPending = current.queue.pendingAppend
        val nextPending = next.queue.pendingAppend
        if (currentPending != null && nextPending == null) {
            val proof = requireNotNull(settlement) {
                "A pending append can only be cleared by an explicit receipt or release"
            }
            when (proof) {
                is AgentInputAppendSettlement.Confirmed -> {
                    val receipt = proof.receipt
                    require(receipt.draftId == currentPending.draft.id) { "Receipt draft mismatch" }
                    require(receipt.chat == current.queue.chat) { "Receipt Chat mismatch" }
                    require(receipt.run == currentPending.run) { "Receipt Run mismatch" }
                    require(receipt.attemptId == currentPending.attemptId) { "Receipt attempt mismatch" }
                    require(receipt.draftRevision == currentPending.draft.revision) {
                        "Receipt revision mismatch"
                    }
                    if (current.pendingMessageId != null) {
                        require(receipt.messageId == current.pendingMessageId) {
                            "Receipt message mismatch"
                        }
                    }
                    require(next.queue.drafts.none { it.id == currentPending.draft.id }) {
                        "A confirmed append must consume its draft"
                    }
                    validateReceiptMessage(next.ref, next.queue.chat.chatId, receipt)
                }

                is AgentInputAppendSettlement.Released -> {
                    require(proof.draftId == currentPending.draft.id) { "Release draft mismatch" }
                    require(proof.attemptId == currentPending.attemptId) { "Release attempt mismatch" }
                    val remaining = next.queue.drafts.firstOrNull { it.id == currentPending.draft.id }
                    require(
                        remaining != null &&
                            remaining.revision == currentPending.draft.revision &&
                            remaining.text == currentPending.draft.text &&
                            remaining.run == currentPending.draft.run,
                    ) { "A released append must keep the exact draft" }
                }
            }
        } else if (currentPending != null) {
            require(settlement == null) { "A retained pending append cannot have a settlement" }
            require(
                nextPending != null &&
                    nextPending.draft.id == currentPending.draft.id &&
                    nextPending.draft.revision == currentPending.draft.revision &&
                    nextPending.draft.text == currentPending.draft.text &&
                    nextPending.run == currentPending.run &&
                    nextPending.attemptId == currentPending.attemptId,
            ) { "Pending append identity cannot change" }
            if (current.pendingMessageId != null && next.pendingMessageId != null) {
                require(current.pendingMessageId == next.pendingMessageId) {
                    "Pending message identity cannot change"
                }
            }
        } else {
            require(settlement == null) { "No pending append to settle" }
        }

        val previous = current.queue.drafts.associateBy { it.id }
        for (draft in next.queue.drafts) {
            val old = previous[draft.id] ?: continue
            require(draft.revision >= old.revision) {
                "A stale draft revision cannot overwrite a newer draft"
            }
            if (draft.revision == old.revision) {
                require(draft.text == old.text) {
                    "Draft text changed without a revision bump"
                }
            }
        }
    }

    private fun validateReceiptMessage(
        ref: LocalProjectRef,
        chat: ChatId,
        receipt: AgentInputAppendReceipt,
    ) {
        val message = requireNotNull(db.agent().message(receipt.messageId.value)) {
            "Append receipt message is not in canonical history"
        }
        require(
            message.instanceId == ref.instanceId.value &&
                message.generation == ref.generation.value &&
                message.chatId == chat.value,
        ) { "Append receipt message belongs to a different Project/Chat" }
        require(message.role == "USER") { "Append receipt must reference a user message" }
        require(message.payload != null) {
            "Append receipt message is not canonical typed history"
        }
    }

    private fun nextPendingMessageId(
        current: StoredAgentInputQueue?,
        next: StoredAgentInputQueue,
    ): MessageId? {
        val pending = next.queue.pendingAppend ?: return null
        val currentPending = current?.queue?.pendingAppend
        if (currentPending == null) return next.pendingMessageId
        require(currentPending.draft.id == pending.draft.id)
        return next.pendingMessageId ?: current.pendingMessageId
    }

    private fun queueRow(snapshot: StoredAgentInputQueue, updatedAt: Long): NativeAgentInputQueueRow {
        val ref = snapshot.ref
        val queue = snapshot.queue
        val receipt = snapshot.lastAppendReceipt
        return NativeAgentInputQueueRow(
            ref.instanceId.value,
            ref.generation.value,
            ref.projectId.value,
            queue.chat.chatId.value,
            queue.paused,
            queue.run?.runId?.value,
            queue.run?.commandId?.value,
            queue.limits.maxDrafts,
            queue.limits.maxDraftChars,
            queue.limits.maxTotalChars,
            snapshot.revision,
            queue.pendingAppend?.draft?.id?.value,
            queue.pendingAppend?.draft?.revision,
            queue.pendingAppend?.attemptId?.value,
            snapshot.pendingMessageId?.value,
            receipt?.run?.runId?.value,
            receipt?.run?.commandId?.value,
            receipt?.draftId?.value,
            receipt?.draftRevision,
            receipt?.attemptId?.value,
            receipt?.messageId?.value,
            updatedAt,
        )
    }

    private fun draftRows(snapshot: StoredAgentInputQueue): List<NativeAgentInputDraftRow> {
        val ref = snapshot.ref
        val queue = snapshot.queue
        val pendingId = queue.pendingAppend?.draft?.id
        return queue.drafts.mapIndexed { position, draft ->
            NativeAgentInputDraftRow(
                ref.instanceId.value,
                ref.generation.value,
                ref.projectId.value,
                queue.chat.chatId.value,
                draft.id.value,
                position,
                draft.text,
                draft.revision,
                draft.run?.runId?.value,
                draft.run?.commandId?.value,
                if (draft.id == pendingId) "PENDING_APPEND" else "QUEUED",
            )
        }
    }

    private fun readSteer(
        ref: LocalProjectRef,
        chat: ChatId,
        run: RunIdentity,
    ): StoredAgentSteerLedger? {
        require(run.projectId == ref.projectId && run.chatId == chat) {
            "Steer Run belongs to a different Project/Chat"
        }
        val row = db.agentInput().steerRun(
            ref.instanceId.value,
            ref.generation.value,
            ref.projectId.value,
            chat.value,
            run.runId.value,
            run.commandId.value,
        )
        val attemptRows = db.agentInput().steerAttempts(
            ref.instanceId.value,
            ref.generation.value,
            ref.projectId.value,
            chat.value,
            run.runId.value,
            run.commandId.value,
        )
        if (row == null) {
            check(attemptRows.isEmpty()) { "Steer attempts without a ledger" }
            return null
        }
        require(
            row.instanceId == ref.instanceId.value &&
                row.generation == ref.generation.value &&
                row.projectId == ref.projectId.value &&
                row.chatId == chat.value &&
                row.runId == run.runId.value &&
                row.runCommandId == run.commandId.value,
        ) { "Persisted steer identity mismatch" }
        require(attemptRows.size <= AgentSteerState.MAX_ATTEMPTS) {
            "Persisted steer count exceeds the hard budget"
        }
        require(attemptRows.map { it.position } == attemptRows.indices.toList()) {
            "Persisted steer positions are not contiguous"
        }
        require(attemptRows.all {
            it.instanceId == row.instanceId &&
                it.generation == row.generation &&
                it.projectId == row.projectId &&
                it.chatId == row.chatId &&
                it.runId == row.runId &&
                it.runCommandId == row.runCommandId
        }) { "Persisted steer attempt identity mismatch" }
        val steerChat = ChatIdentity(ProjectId(row.projectId), ChatId(row.chatId))
        var totalChars = 0
        val attempts = attemptRows.map { attemptRow ->
            val status = AgentSteerStatus.valueOf(attemptRow.status)
            require(status != AgentSteerStatus.REJECTED) {
                "Rejected steer attempts are not persisted"
            }
            val command = AgentSteerCommand(
                CommandId(attemptRow.steerId),
                run,
                steerChat,
                attemptRow.text,
            )
            totalChars = Math.addExact(totalChars, command.text.length)
            AgentSteerAttempt.restore(
                command,
                run,
                steerChat,
                AgentRunStage.valueOf(attemptRow.submittedDuring),
                status,
                attemptRow.reason?.let { AgentSteerFailure.valueOf(it) },
                attemptRow.injectedDuring?.let { AgentRunStage.valueOf(it) },
            )
        }
        require(totalChars <= StoredAgentSteerLedger.MAX_TOTAL_TEXT_CHARS) {
            "Persisted steer text exceeds the hard budget"
        }
        val state = AgentSteerState.restore(
            run,
            steerChat,
            AgentRunStage.valueOf(row.stage),
            attempts,
        )
        return StoredAgentSteerLedger(ref, row.revision, state)
    }

    private fun validateSteerTransition(
        current: StoredAgentSteerLedger?,
        next: StoredAgentSteerLedger,
    ) {
        if (current == null) {
            require(next.revision == 0L) { "Stale steer ledger revision" }
            return
        }
        require(next.revision == current.revision) { "Stale steer ledger revision" }
        val oldState = current.state
        val newState = next.state
        require(oldState.run == newState.run && oldState.chat == newState.chat) {
            "Steer Run/Chat cannot change"
        }
        require(newState.attempts.size >= oldState.attempts.size) {
            "Persisted steer attempts cannot be removed"
        }
        oldState.attempts.forEachIndexed { index, oldAttempt ->
            val newAttempt = newState.attempts[index]
            require(
                newAttempt.id == oldAttempt.id &&
                    newAttempt.text == oldAttempt.text &&
                    newAttempt.submittedDuring == oldAttempt.submittedDuring,
            ) { "Existing steer attempt identity cannot change" }
            validateSteerAttempt(oldAttempt, newAttempt)
        }
        when (oldState.stage) {
            AgentRunStage.STOPPED, AgentRunStage.FINISHED, AgentRunStage.FAILED ->
                require(newState.stage == oldState.stage) { "A terminal steer stage cannot change" }

            AgentRunStage.STOPPING ->
                require(newState.stage == AgentRunStage.STOPPING || newState.stage == AgentRunStage.STOPPED)

            else -> Unit
        }
    }

    private fun validateSteerAttempt(old: AgentSteerAttempt, next: AgentSteerAttempt) {
        when (old.status) {
            AgentSteerStatus.ACCEPTED -> when (next.status) {
                AgentSteerStatus.ACCEPTED -> require(
                    next.reason == null && next.injectedDuring == null,
                ) { "Accepted steer cannot gain an outcome" }

                AgentSteerStatus.INJECTED -> require(
                    next.reason == null && next.injectedDuring == AgentRunStage.TOOL_LOOP_BOUNDARY,
                ) { "Injected steer must record the tool-loop boundary" }

                AgentSteerStatus.CANCELLED -> require(
                    next.reason != null && next.injectedDuring == null,
                ) { "Cancelled steer requires a reason and no injection" }

                AgentSteerStatus.REJECTED -> require(false) {
                    "Rejected steer attempts are not persisted"
                }
            }

            AgentSteerStatus.INJECTED -> require(
                next.status == AgentSteerStatus.INJECTED &&
                    next.injectedDuring == old.injectedDuring &&
                    next.reason == null,
            ) { "An injected steer is terminal" }

            AgentSteerStatus.CANCELLED -> require(
                next.status == AgentSteerStatus.CANCELLED &&
                    next.reason == old.reason &&
                    next.injectedDuring == null,
            ) { "A cancelled steer is terminal" }

            AgentSteerStatus.REJECTED -> require(false) {
                "Rejected steer attempts are not persisted"
            }
        }
    }

    private fun steerRunRow(snapshot: StoredAgentSteerLedger, updatedAt: Long): NativeAgentSteerRunRow {
        val ref = snapshot.ref
        val run = snapshot.state.run
        return NativeAgentSteerRunRow(
            ref.instanceId.value,
            ref.generation.value,
            ref.projectId.value,
            requireNotNull(run.chatId).value,
            run.runId.value,
            run.commandId.value,
            snapshot.state.stage.name,
            snapshot.revision,
            updatedAt,
        )
    }

    private fun steerAttemptRows(snapshot: StoredAgentSteerLedger): List<NativeAgentSteerAttemptRow> {
        val ref = snapshot.ref
        val chat = snapshot.state.chat
        val run = snapshot.state.run
        return snapshot.state.attempts.mapIndexed { position, attempt ->
            NativeAgentSteerAttemptRow(
                ref.instanceId.value,
                ref.generation.value,
                ref.projectId.value,
                chat.chatId.value,
                run.runId.value,
                run.commandId.value,
                attempt.id.value,
                position,
                attempt.text,
                attempt.submittedDuring.name,
                attempt.status.name,
                attempt.reason?.name,
                attempt.injectedDuring?.name,
            )
        }
    }
}
