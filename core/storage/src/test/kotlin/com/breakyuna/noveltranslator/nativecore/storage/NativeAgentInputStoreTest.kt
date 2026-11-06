package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputAppendReceipt
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputAppendResult
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputAppendSettlement
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputAppendStartResult
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputDraft
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputQueue
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputQueueLimits
import com.breakyuna.noveltranslator.nativecore.domain.AgentInputQueueUpdate
import com.breakyuna.noveltranslator.nativecore.domain.AgentMessage
import com.breakyuna.noveltranslator.nativecore.domain.AgentMessageCodec
import com.breakyuna.noveltranslator.nativecore.domain.AgentRunStage
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerAttempt
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerCommand
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerFailure
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerInjectionResult
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerStageResult
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerState
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerStatus
import com.breakyuna.noveltranslator.nativecore.domain.AgentSteerSubmitResult
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MessageId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectWriteLease
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.StoredAgentInputQueue
import com.breakyuna.noveltranslator.nativecore.domain.StoredAgentSteerLedger
import com.breakyuna.noveltranslator.nativecore.domain.UserPart
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeAgentInputStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var store: NativeAgentInputStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        store = NativeAgentInputStore(context, projects, runtime)
    }

    @After
    fun cleanup() = runBlocking {
        store.close()
        projects.close()
        runtime.close()
    }

    @Test
    fun writesRequireAnActiveRuntimeWriteLease() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val fakeLease = object : ProjectWriteLease {}
        expectRejected {
            store.replaceInputQueue(
                fakeLease,
                StoredAgentInputQueue.initial(project.ref, queueOf(chat, null)),
                null,
            )
        }
        assertNull(store.loadInputQueue(project.ref, chat.chatId))
    }

    @Test
    fun queueOrderPauseAndPendingAppendRoundTrip() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val run = agentRun(project.ref, chat.chatId)
        var queue = queueOf(
            chat,
            run,
            draft(chat, "a", "first", run),
            draft(chat, "b", "second"),
        )
        queue = (queue.edit(CommandId("b"), "second edited") as AgentInputQueueUpdate.Applied).queue
        val started = queue.beginAppend(run, RequestAttemptId("attempt-1")) as AgentInputAppendStartResult.Started
        queue = (started.queue.pause() as AgentInputQueueUpdate.Applied).queue
        val saved = save(
            StoredAgentInputQueue.initial(project.ref, queue)
                .withPendingMessage(MessageId("message-a")),
        )
        assertEquals(1L, saved.revision)

        store.close()
        store = NativeAgentInputStore(context, projects, runtime)
        val loaded = store.loadInputQueue(project.ref, chat.chatId)!!
        assertTrue(loaded.queue.paused)
        assertEquals(listOf("a", "b"), loaded.queue.drafts.map { it.id.value })
        assertEquals(2L, loaded.queue.drafts.single { it.id == CommandId("b") }.revision)
        assertEquals("second edited", loaded.queue.drafts.single { it.id == CommandId("b") }.text)
        assertEquals(run, loaded.queue.run)
        assertEquals("attempt-1", loaded.queue.pendingAppend?.attemptId?.value)
        assertEquals("message-a", loaded.pendingMessageId?.value)

        val receipt = AgentInputAppendReceipt(
            CommandId("a"),
            chat,
            run,
            RequestAttemptId("attempt-1"),
            1,
            MessageId("message-a"),
        )
        withDatabase { db ->
            val payload = AgentMessageCodec.encode(
                AgentMessage.User(
                    MessageId("message-a"),
                    listOf(UserPart.Text("first")),
                ),
            )
            db.agent().insertMessage(
                NativeAgentMessageRow(
                    "message-a",
                    project.ref.instanceId.value,
                    project.ref.generation.value,
                    chat.chatId.value,
                    "USER",
                    "first",
                    1L,
                    payload,
                ),
            )
        }
        val consumed = started.queue.acknowledgeAppended(receipt) as AgentInputAppendResult.Consumed
        val confirmed = save(
            loaded.withQueue(consumed.queue),
            AgentInputAppendSettlement.Confirmed(receipt),
        )
        assertEquals(2L, confirmed.revision)
        assertEquals("message-a", confirmed.lastAppendReceipt?.messageId?.value)

        store.close()
        store = NativeAgentInputStore(context, projects, runtime)
        val reloaded = store.loadInputQueue(project.ref, chat.chatId)!!
        assertNull(reloaded.queue.pendingAppend)
        assertEquals(listOf("b"), reloaded.queue.drafts.map { it.id.value })
        assertEquals("second edited", reloaded.queue.drafts.single().text)
        assertEquals("message-a", reloaded.lastAppendReceipt?.messageId?.value)
        assertEquals(2L, reloaded.revision)
    }

    @Test
    fun confirmationRequiresTheCanonicalHistoryMessage() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val run = agentRun(project.ref, chat.chatId)
        val queue = queueOf(chat, run, draft(chat, "a", "first", run))
        val started = queue.beginAppend(run, RequestAttemptId("attempt-1")) as AgentInputAppendStartResult.Started
        val saved = save(StoredAgentInputQueue.initial(project.ref, started.queue))
        val receipt = AgentInputAppendReceipt(
            CommandId("a"),
            chat,
            run,
            RequestAttemptId("attempt-1"),
            1,
            MessageId("missing-message"),
        )
        val consumed = started.queue.acknowledgeAppended(receipt) as AgentInputAppendResult.Consumed
        expectRejected {
            save(saved.withQueue(consumed.queue), AgentInputAppendSettlement.Confirmed(receipt))
        }
        val unchanged = store.loadInputQueue(project.ref, chat.chatId)!!
        assertEquals("attempt-1", unchanged.queue.pendingAppend?.attemptId?.value)
        assertEquals("first", unchanged.queue.drafts.single().text)
    }

    @Test
    fun draftRunRebindingDoesNotRequireARevisionBump() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val run = agentRun(project.ref, chat.chatId)
        val initialQueue = queueOf(chat, run, draft(chat, "a", "first"))
        val saved = save(StoredAgentInputQueue.initial(project.ref, initialQueue))
        val started = initialQueue.beginAppend(run, RequestAttemptId("attempt-1")) as AgentInputAppendStartResult.Started
        val pending = save(saved.withQueue(started.queue))
        assertEquals("attempt-1", pending.queue.pendingAppend?.attemptId?.value)
        assertEquals(1L, pending.queue.drafts.single().revision)
        assertEquals(run, pending.queue.drafts.single().run)

        val releasedQueue = (pending.queue.releaseAppend(RequestAttemptId("attempt-1")) as AgentInputQueueUpdate.Applied).queue
        val released = save(
            pending.withQueue(releasedQueue),
            AgentInputAppendSettlement.Released(CommandId("a"), RequestAttemptId("attempt-1")),
        )
        val endedQueue = (released.queue.endRun(run) as AgentInputQueueUpdate.Applied).queue
        val ended = save(released.withQueue(endedQueue))
        assertNull(ended.queue.run)
        assertNull(ended.queue.drafts.single().run)
        assertEquals(1L, ended.queue.drafts.single().revision)
        assertNull(store.loadInputQueue(project.ref, chat.chatId)!!.queue.drafts.single().run)
    }

    @Test
    fun sameDraftIdentityIsIsolatedPerChat() = runBlocking {
        val project = project()
        val chatA = chat(project.ref, "chat-a")
        val chatB = chat(project.ref, "chat-b")
        val savedA = save(
            StoredAgentInputQueue.initial(
                project.ref,
                queueOf(chatA, null, draft(chatA, "same", "first chat")),
            ),
        )
        val savedB = save(
            StoredAgentInputQueue.initial(
                project.ref,
                queueOf(chatB, null, draft(chatB, "same", "second chat")),
            ),
        )
        assertEquals(1L, savedA.revision)
        assertEquals(1L, savedB.revision)
        assertEquals("first chat", store.loadInputQueue(project.ref, chatA.chatId)!!.queue.drafts.single().text)
        assertEquals("second chat", store.loadInputQueue(project.ref, chatB.chatId)!!.queue.drafts.single().text)

        val changedA = (savedA.queue.edit(CommandId("same"), "first chat edited") as AgentInputQueueUpdate.Applied).queue
        save(savedA.withQueue(changedA))
        assertEquals("second chat", store.loadInputQueue(project.ref, chatB.chatId)!!.queue.drafts.single().text)
        assertEquals(1L, store.loadInputQueue(project.ref, chatB.chatId)!!.revision)
    }

    @Test
    fun lateReceiptAndStaleDraftRevisionCannotOverwriteNewerFacts() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val run = agentRun(project.ref, chat.chatId)
        var queue = queueOf(chat, run, draft(chat, "a", "first", run))
        val started = queue.beginAppend(run, RequestAttemptId("attempt-1")) as AgentInputAppendStartResult.Started
        val first = save(StoredAgentInputQueue.initial(project.ref, started.queue))
        val releasedQueue = (first.queue.releaseAppend(RequestAttemptId("attempt-1")) as AgentInputQueueUpdate.Applied).queue
        val wrongAttempt = AgentInputAppendReceipt(
            CommandId("a"),
            chat,
            run,
            RequestAttemptId("attempt-2"),
            1,
            MessageId("old-message"),
        )
        expectRejected {
            save(first.withQueue(releasedQueue), AgentInputAppendSettlement.Confirmed(wrongAttempt))
        }
        assertEquals("attempt-1", store.loadInputQueue(project.ref, chat.chatId)!!.queue.pendingAppend?.attemptId?.value)

        val released = save(
            first.withQueue(releasedQueue),
            AgentInputAppendSettlement.Released(CommandId("a"), RequestAttemptId("attempt-1")),
        )
        queue = (released.queue.edit(CommandId("a"), "edited") as AgentInputQueueUpdate.Applied).queue
        val edited = save(released.withQueue(queue))
        val startedAgain = queue.beginAppend(run, RequestAttemptId("attempt-2")) as AgentInputAppendStartResult.Started
        val second = save(edited.withQueue(startedAgain.queue))

        val releaseSecond = (second.queue.releaseAppend(RequestAttemptId("attempt-2")) as AgentInputQueueUpdate.Applied).queue
        val staleReceipt = AgentInputAppendReceipt(
            CommandId("a"),
            chat,
            run,
            RequestAttemptId("attempt-1"),
            2,
            MessageId("old-message"),
        )
        expectRejected {
            save(second.withQueue(releaseSecond), AgentInputAppendSettlement.Confirmed(staleReceipt))
        }
        assertEquals("attempt-2", store.loadInputQueue(project.ref, chat.chatId)!!.queue.pendingAppend?.attemptId?.value)

        val releasedAgain = save(
            second.withQueue(releaseSecond),
            AgentInputAppendSettlement.Released(CommandId("a"), RequestAttemptId("attempt-2")),
        )
        val staleDraft = AgentInputDraft.restore(CommandId("a"), chat, run, "first", 1)
        val staleQueue = AgentInputQueue.restore(
            chat,
            edited.queue.limits,
            run,
            false,
            listOf(staleDraft),
            null,
        )
        expectRejected {
            save(StoredAgentInputQueue(project.ref, releasedAgain.revision, staleQueue))
        }
        val unchanged = store.loadInputQueue(project.ref, chat.chatId)!!
        assertEquals("edited", unchanged.queue.drafts.single().text)
        assertEquals(2L, unchanged.queue.drafts.single().revision)
    }

    @Test
    fun replacingQueueRemovesOldDraftsAtomicallyAndRejectsStaleRevision() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val original = save(
            StoredAgentInputQueue.initial(
                project.ref,
                queueOf(
                    chat,
                    null,
                    draft(chat, "a", "first"),
                    draft(chat, "b", "second"),
                    draft(chat, "c", "third"),
                ),
            ),
        )
        val replacement = queueOf(chat, null, draft(chat, "c", "third"))
        val replaced = save(original.withQueue(replacement))
        assertEquals(2L, replaced.revision)
        val loaded = store.loadInputQueue(project.ref, chat.chatId)!!
        assertEquals(listOf("c"), loaded.queue.drafts.map { it.id.value })
        withDatabase { db ->
            assertEquals(
                1,
                db.agentInput().inputDraftCount(
                    project.ref.instanceId.value,
                    project.ref.generation.value,
                    project.ref.projectId.value,
                    chat.chatId.value,
                ),
            )
        }

        expectRejected { save(original) }
        val afterFailure = store.loadInputQueue(project.ref, chat.chatId)!!
        assertEquals(listOf("c"), afterFailure.queue.drafts.map { it.id.value })
        assertEquals(2L, afterFailure.revision)

        val emptied = save(loaded.withQueue(AgentInputQueue.empty(chat)))
        assertEquals(3L, emptied.revision)
        assertTrue(store.loadInputQueue(project.ref, chat.chatId)!!.queue.drafts.isEmpty())
    }

    @Test
    fun steerInjectionAndCancellationFactsRoundTrip() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val run = agentRun(project.ref, chat.chatId)
        val initial = saveSteer(
            StoredAgentSteerLedger.initial(project.ref, AgentSteerState.start(run, chat)),
        )
        var state = AgentSteerState.start(run, chat)
        state = (state.submit(steer(run, chat, "s1", "first")) as AgentSteerSubmitResult.Accepted).state
        state = (state.submit(steer(run, chat, "s2", "second")) as AgentSteerSubmitResult.Accepted).state
        state = (state.moveTo(AgentRunStage.TOOL_LOOP_BOUNDARY) as AgentSteerStageResult.Applied).state
        state = (state.injectAtToolBoundary() as AgentSteerInjectionResult.Injected).state
        state = (state.submit(steer(run, chat, "s3", "third")) as AgentSteerSubmitResult.Accepted).state
        state = (state.stop() as AgentSteerStageResult.Applied).state
        state = (state.markStopped() as AgentSteerStageResult.Applied).state
        assertEquals(3, state.attempts.size)

        val saved = saveSteer(StoredAgentSteerLedger(project.ref, initial.revision, state))
        assertEquals(2L, saved.revision)
        store.close()
        store = NativeAgentInputStore(context, projects, runtime)
        val loaded = store.loadSteerLedger(project.ref, chat.chatId, run)!!
        assertEquals(AgentRunStage.STOPPED, loaded.state.stage)
        assertEquals(listOf("s1", "s2", "s3"), loaded.state.attempts.map { it.id.value })
        assertEquals(
            listOf(AgentSteerStatus.INJECTED, AgentSteerStatus.INJECTED, AgentSteerStatus.CANCELLED),
            loaded.state.attempts.map { it.status },
        )
        assertEquals("first", loaded.state.attempts[0].text)
        assertEquals(AgentRunStage.TOOL_LOOP_BOUNDARY, loaded.state.attempts[0].injectedDuring)
        assertEquals(AgentSteerStatus.CANCELLED, loaded.state.attempts[2].status)
        assertEquals(AgentRunStage.TOOL_LOOP_BOUNDARY, loaded.state.attempts[2].submittedDuring)
        assertEquals("third", loaded.state.attempts[2].text)
        assertEquals(AgentSteerFailure.RUN_STOPPING, loaded.state.attempts[2].reason)

        assertNull(store.loadSteerLedger(project.ref, chat.chatId, agentRun(project.ref, chat.chatId, id = "other-run")))
    }

    @Test
    fun terminalSteerOutcomeCannotRegress() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val run = agentRun(project.ref, chat.chatId)
        val initial = saveSteer(
            StoredAgentSteerLedger.initial(project.ref, AgentSteerState.start(run, chat)),
        )
        var state = AgentSteerState.start(run, chat)
        state = (state.submit(steer(run, chat, "s1", "first")) as AgentSteerSubmitResult.Accepted).state
        state = (state.moveTo(AgentRunStage.TOOL_LOOP_BOUNDARY) as AgentSteerStageResult.Applied).state
        state = (state.injectAtToolBoundary() as AgentSteerInjectionResult.Injected).state
        val saved = saveSteer(StoredAgentSteerLedger(project.ref, initial.revision, state))

        val original = saved.state.attempts.single()
        val regressed = AgentSteerAttempt.restore(
            AgentSteerCommand(CommandId("s1"), run, chat, "first"),
            run,
            chat,
            AgentRunStage.MODEL_REQUEST,
            AgentSteerStatus.CANCELLED,
            AgentSteerFailure.RUN_FINISHED,
            null,
        )
        val regressedState = AgentSteerState.restore(
            run,
            chat,
            AgentRunStage.STOPPED,
            listOf(regressed),
        )
        expectRejected {
            saveSteer(StoredAgentSteerLedger(project.ref, saved.revision, regressedState))
        }
        val unchanged = store.loadSteerLedger(project.ref, chat.chatId, run)!!
        assertEquals(AgentSteerStatus.INJECTED, unchanged.state.attempts.single().status)
        assertEquals(original.injectedDuring, unchanged.state.attempts.single().injectedDuring)
    }

    @Test
    fun duplicateDraftAndSteerIdentitiesAreRejectedBySchema() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val run = agentRun(project.ref, chat.chatId)
        withDatabase { db ->
            db.agentInput().insertInputQueue(queueRow(project.ref, chat.chatId, revision = 1))
            val draftRow = draftRow(project.ref, chat.chatId, "duplicate", position = 0)
            db.agentInput().insertInputDrafts(listOf(draftRow))
            assertThrows(SQLiteException::class.java) {
                db.agentInput().insertInputDrafts(listOf(draftRow.copy(position = 1)))
            }

            db.agentInput().insertSteerRun(steerRunRow(project.ref, chat.chatId, run, revision = 1))
            val attemptRow = steerAttemptRow(project.ref, chat.chatId, run, "duplicate-steer", position = 0)
            db.agentInput().insertSteerAttempts(listOf(attemptRow))
            assertThrows(SQLiteException::class.java) {
                db.agentInput().insertSteerAttempts(listOf(attemptRow.copy(position = 1)))
            }
        }
        Unit
    }

    @Test
    fun persistedRowsOverBudgetAreRejectedOnRead() = runBlocking {
        val project = project()
        val chat = chat(project.ref, "over-budget")
        val run = agentRun(project.ref, chat.chatId)
        withDatabase { db ->
            db.agentInput().insertInputQueue(
                queueRow(project.ref, chat.chatId, revision = 1, maxDrafts = 2, maxDraftChars = 5, maxTotalChars = 10),
            )
            db.agentInput().insertInputDrafts(listOf(
                draftRow(project.ref, chat.chatId, "too-long", position = 0, text = "123456"),
            ))
            db.agentInput().insertSteerRun(steerRunRow(project.ref, chat.chatId, run, revision = 1))
            db.agentInput().insertSteerAttempts(listOf(
                steerAttemptRow(project.ref, chat.chatId, run, "too-long-steer", position = 0, text = "x".repeat(8_001)),
            ))
        }
        expectRejected { store.loadInputQueue(project.ref, chat.chatId) }
        expectRejected { store.loadSteerLedger(project.ref, chat.chatId, run) }
    }

    @Test
    fun migrationFromV8KeepsLegacyDataAndAddsInputTables() = runBlocking {
        val project = project()
        val agent = NativeAgentStore(context, projects, runtime)
        val legacyChat = agent.currentChat(project.ref)
        agent.append(project.ref, legacyChat, "USER", "legacy message")
        agent.close()
        store.close()
        projects.close()

        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("lnt_native_app.db").path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { db ->
            db.execSQL("DROP TABLE IF EXISTS native_agent_input_drafts")
            db.execSQL("DROP TABLE IF EXISTS native_agent_input_queues")
            db.execSQL("DROP TABLE IF EXISTS native_agent_steer_attempts")
            db.execSQL("DROP TABLE IF EXISTS native_agent_steer_runs")
            db.execSQL("DELETE FROM room_master_table")
            db.version = 8
        }

        projects = NativeTextProjectStore(context, runtime)
        store = NativeAgentInputStore(context, projects, runtime)
        val reopened = NativeAgentStore(context, projects, runtime)
        try {
            assertEquals(legacyChat, reopened.currentChat(project.ref))
            assertEquals(1, reopened.messages(project.ref, legacyChat).size)
            assertEquals("legacy message", reopened.messages(project.ref, legacyChat).single().text)
        } finally {
            reopened.close()
        }

        val queueChat = ChatIdentity(project.ref.projectId, legacyChat)
        val saved = save(
            StoredAgentInputQueue.initial(
                project.ref,
                queueOf(queueChat, null, draft(queueChat, "new", "after migration")),
            ),
        )
        assertEquals(1L, saved.revision)
        assertEquals(
            "after migration",
            store.loadInputQueue(project.ref, legacyChat)!!.queue.drafts.single().text,
        )
    }

    @Test
    fun steerRunPagingRemainsStableWhenAnExistingLedgerIsUpdated() = runBlocking {
        val project = project()
        val chat = chat(project.ref)
        val snapshots = (0..24).map { index ->
            val run = agentRun(project.ref, chat.chatId, "run-" + index.toString().padStart(2, '0'), "command-$index")
            saveSteer(StoredAgentSteerLedger.initial(project.ref, AgentSteerState.start(run, chat)))
        }
        val first = store.steerRunPage(project.ref, chat.chatId)
        assertEquals(20, first.runs.size)
        assertEquals("run-24", first.runs.first().run.runId.value)
        val changed = snapshots.last()
        val finished = (changed.state.finish() as AgentSteerStageResult.Applied).state
        saveSteer(changed.withState(finished)) // rewrites the row; rowid must not be the pagination cursor
        val second = store.steerRunPage(project.ref, chat.chatId, first.next)
        assertEquals(5, second.runs.size)
        assertNull(second.next)
        val all = first.runs + second.runs
        assertEquals(25, all.map { it.run }.distinct().size)
        assertEquals(emptyList<com.breakyuna.noveltranslator.nativecore.domain.AgentSteerRunSummary>(),
            store.steerRunPage(project.ref, ChatId("other-chat")).runs)
        store.close(); store = NativeAgentInputStore(context, projects, runtime)
        assertEquals(AgentRunStage.FINISHED, store.loadSteerLedger(project.ref, chat.chatId, changed.state.run)!!.state.stage)
    }

    private suspend fun project() = projects.createTxt(
        CommandId("create"),
        "test",
        RelativePath("test.txt"),
        "source".toByteArray(),
    )

    private fun chat(ref: LocalProjectRef, id: String = "chat") =
        ChatIdentity(ref.projectId, ChatId(id))

    private fun agentRun(
        ref: LocalProjectRef,
        chatId: ChatId,
        id: String = "run",
        command: String = "run-command",
    ) = RunIdentity(ref.projectId, chatId, RunId(id), CommandId(command))

    private fun draft(
        chat: ChatIdentity,
        id: String,
        text: String,
        run: RunIdentity? = null,
    ) = AgentInputDraft.create(CommandId(id), chat, text, run)

    private fun queueOf(
        chat: ChatIdentity,
        run: RunIdentity?,
        vararg drafts: AgentInputDraft,
    ): AgentInputQueue {
        var queue = AgentInputQueue.empty(chat, run = run)
        for (draft in drafts) {
            val result = queue.enqueue(draft)
            assertTrue(result is AgentInputQueueUpdate.Applied)
            queue = (result as AgentInputQueueUpdate.Applied).queue
        }
        return queue
    }

    private fun steer(
        run: RunIdentity,
        chat: ChatIdentity,
        id: String,
        text: String,
    ) = AgentSteerCommand(CommandId(id), run, chat, text)

    private suspend fun save(
        snapshot: StoredAgentInputQueue,
        settlement: AgentInputAppendSettlement? = null,
    ): StoredAgentInputQueue {
        val lease = runtime.beginProjectWrite()
        try {
            return store.replaceInputQueue(lease, snapshot, settlement)
        } finally {
            runtime.endProjectWrite(lease)
        }
    }

    private suspend fun saveSteer(snapshot: StoredAgentSteerLedger): StoredAgentSteerLedger {
        val lease = runtime.beginProjectWrite()
        try {
            return store.replaceSteerLedger(lease, snapshot)
        } finally {
            runtime.endProjectWrite(lease)
        }
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected the store to reject the snapshot")
        } catch (_: IllegalArgumentException) {
            // Explicit validation / identity / budget rejection.
        } catch (_: IllegalStateException) {
            // Persisted integrity or stale-state rejection.
        }
    }

    private suspend fun <T> withDatabase(block: (NativeAppDatabase) -> T): T = withContext(Dispatchers.IO) {
        val db = NativeAppDatabase.open(context)
        try {
            return@withContext block(db)
        } finally {
            db.close()
        }
    }

    private fun queueRow(
        ref: LocalProjectRef,
        chatId: ChatId,
        revision: Long,
        maxDrafts: Int = AgentInputQueueLimits.DEFAULT.maxDrafts,
        maxDraftChars: Int = AgentInputQueueLimits.DEFAULT.maxDraftChars,
        maxTotalChars: Int = AgentInputQueueLimits.DEFAULT.maxTotalChars,
    ) = NativeAgentInputQueueRow(
        ref.instanceId.value,
        ref.generation.value,
        ref.projectId.value,
        chatId.value,
        false,
        null,
        null,
        maxDrafts,
        maxDraftChars,
        maxTotalChars,
        revision,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L,
    )

    private fun draftRow(
        ref: LocalProjectRef,
        chatId: ChatId,
        id: String,
        position: Int,
        text: String = "draft",
    ) = NativeAgentInputDraftRow(
        ref.instanceId.value,
        ref.generation.value,
        ref.projectId.value,
        chatId.value,
        id,
        position,
        text,
        1L,
        null,
        null,
        "QUEUED",
    )

    private fun steerRunRow(
        ref: LocalProjectRef,
        chatId: ChatId,
        run: RunIdentity,
        revision: Long,
    ) = NativeAgentSteerRunRow(
        ref.instanceId.value,
        ref.generation.value,
        ref.projectId.value,
        chatId.value,
        run.runId.value,
        run.commandId.value,
        AgentRunStage.MODEL_REQUEST.name,
        revision,
        1L,
    )

    private fun steerAttemptRow(
        ref: LocalProjectRef,
        chatId: ChatId,
        run: RunIdentity,
        id: String,
        position: Int,
        text: String = "steer",
    ) = NativeAgentSteerAttemptRow(
        ref.instanceId.value,
        ref.generation.value,
        ref.projectId.value,
        chatId.value,
        run.runId.value,
        run.commandId.value,
        id,
        position,
        text,
        AgentRunStage.MODEL_REQUEST.name,
        AgentSteerStatus.ACCEPTED.name,
        null,
        null,
    )
}
