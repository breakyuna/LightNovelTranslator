package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.agent.NativeKoogWorkspace
import com.breakyuna.noveltranslator.nativecore.agent.NativeKoogConversation
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeAgentStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private val chat = ChatId("chat")
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
    }
    @After fun cleanup() = runBlocking { agent.close(); projects.close(); runtime.close() }
    @Test fun manualSummaryChangesOnlyModelContextAndRejectsStalePreview() = runBlocking {
        val ref = project().ref
        repeat(3) { index ->
            agent.appendMessage(ref, chat, AgentMessage.User(MessageId("user-$index"), listOf(UserPart.Text("request $index"))))
            agent.appendMessage(ref, chat, AgentMessage.Assistant(MessageId("assistant-$index"), listOf(AssistantPart.Text("reply $index"))))
        }
        val history = agent.history(ref, chat)
        val preview = agent.prepareContext(ref, chat)
        assertEquals(2, preview.archivedMessages)
        assertEquals(4, preview.retainedMessages)
        agent.compactContext(preview, "First request completed; preserve project facts.")
        val model = agent.modelHistory(ref, chat)
        assertEquals(5, model.size)
        assertTrue(model.first() is AgentMessage.System)
        assertEquals(history.drop(2).map { it.id }, model.drop(1).map { it.id })
        assertEquals(history.map { it.id }, agent.history(ref, chat).map { it.id })
        try { agent.compactContext(preview, "stale"); fail("Accepted stale context preview") }
        catch (_: IllegalArgumentException) { }
        agent.close(); agent = NativeAgentStore(context, projects, runtime)
        assertEquals(model.map { it.id }, agent.modelHistory(ref, chat).map { it.id })
        assertEquals(history.size, agent.history(ref, chat).size)
    }

    @Test fun contextPreviewRejectsANewMessageAfterPreparation() = runBlocking {
        val ref = project().ref
        repeat(3) { index ->
            agent.appendMessage(ref, chat, AgentMessage.User(MessageId("u-$index"), listOf(UserPart.Text("request"))))
            agent.appendMessage(ref, chat, AgentMessage.Assistant(MessageId("a-$index"), listOf(AssistantPart.Text("reply"))))
        }
        val preview = agent.prepareContext(ref, chat)
        agent.appendMessage(ref, chat, AgentMessage.User(MessageId("new-user"), listOf(UserPart.Text("new request"))))
        try { agent.compactContext(preview, "outdated"); fail("Saved a summary over a changed conversation") }
        catch (_: IllegalArgumentException) { }
        assertFalse(agent.modelHistory(ref, chat).any { it is AgentMessage.System })
    }

    @Test fun compactedConversationCanGrowPastTheOriginalContextLimitWithoutDeletingHistory() = runBlocking {
        val ref = project().ref
        repeat(140) { index ->
            agent.appendMessage(ref, chat, AgentMessage.User(MessageId("long-u-$index"), listOf(UserPart.Text("request $index"))))
            agent.appendMessage(ref, chat, AgentMessage.Assistant(MessageId("long-a-$index"), listOf(AssistantPart.Text("answer $index"))))
            if (index >= 9 && index % 10 == 9) agent.compactContext(agent.prepareContext(ref, chat), "Summary through turn $index")
        }
        assertEquals(5, agent.modelHistory(ref, chat).size)
        val db = NativeAppDatabase.open(context)
        try { withContext(Dispatchers.IO) { assertEquals(280, db.agent().historyCount(ref.instanceId.value, ref.generation.value, chat.value)) } }
        finally { db.close() }
    }

    @Test fun completeHistoryUsesScopedStableCursorAndLoadsFullBodiesOnlyOnDetail() = runBlocking {
        val ref = project().ref
        val db = NativeAppDatabase.open(context)
        try {
            withContext(Dispatchers.IO) {
                repeat(65) { index ->
                    db.agent().insertMessage(NativeAgentMessageRow("history-" + index.toString().padStart(3, '0'),
                        ref.instanceId.value, ref.generation.value, chat.value, "USER", "x".repeat(5000), 100L))
                }
            }
            val first = agent.messagePage(ref, chat)
            assertEquals(30, first.lines.size)
            assertTrue(first.lines.all { it.text.length == 2000 })
            withContext(Dispatchers.IO) {
                db.agent().insertMessage(NativeAgentMessageRow("new-message", ref.instanceId.value,
                    ref.generation.value, chat.value, "USER", "new", 101L))
            }
            val second = agent.messagePage(ref, chat, first.next)
            val third = agent.messagePage(ref, chat, second.next)
            assertEquals(30, second.lines.size); assertEquals(5, third.lines.size); assertNull(third.next)
            assertEquals(65, (first.lines + second.lines + third.lines).map { it.id }.distinct().size)
            assertEquals(5000, agent.messageDetail(ref, chat, third.lines.first().id).text.length)
            try { agent.messagePage(ref, ChatId("other"), first.next); fail("Accepted foreign cursor") }
            catch (_: IllegalArgumentException) { }
            assertEquals("new-message", agent.messagePage(ref, chat).lines.last().id)
        } finally { db.close() }
    }

    @Test fun editedHistoryBranchKeepsOriginalAndIsIdempotentWithoutApprovals() = runBlocking {
        val ref = project().ref
        val source = agent.currentChat(ref)
        agent.appendMessage(ref, source, AgentMessage.User(MessageId("branch-user"), listOf(UserPart.Text("original"))))
        agent.appendMessage(ref, source, AgentMessage.Assistant(MessageId("branch-assistant"), listOf(AssistantPart.Text("reply"))))
        val preview = agent.prepareBranch(ref, source, MessageId("branch-user"))
        assertTrue(preview.editable)
        val target = agent.createBranch(preview, CommandId("branch-command"), "edited")
        assertNotEquals(source, target)
        assertEquals(target, agent.currentChat(ref))
        assertEquals(target, agent.createBranch(preview, CommandId("branch-command"), "edited"))
        assertEquals(2, agent.chats(ref).size)
        assertEquals("original", ((agent.history(ref, source).first() as AgentMessage.User).parts.single() as UserPart.Text).text)
        val copied = agent.history(ref, target)
        assertEquals(1, copied.size)
        assertEquals("edited", ((copied.single() as AgentMessage.User).parts.single() as UserPart.Text).text)
        assertNotEquals("branch-user", copied.single().id.value)
        assertNull(agent.pending(ref, target))
        assertEquals(0L, projects.open(ref).revision)
        try { agent.createBranch(preview, CommandId("branch-command"), "different"); fail("Changed branch command") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun incompleteToolPrefixCannotBecomeABranchAndStalePreviewIsRejected() = runBlocking {
        val ref = project().ref
        val source = agent.currentChat(ref)
        agent.appendMessage(ref, source, AgentMessage.User(MessageId("tool-user"), listOf(UserPart.Text("query"))))
        val call = AgentToolCall(ToolCallId("tool-call"), "query_project", JsonObjectValue.parse("{}", JsonInputLimits(1024, 4)))
        agent.appendMessage(ref, source, AgentMessage.Assistant(MessageId("tool-assistant"), listOf(AssistantPart.ToolCall(call))))
        try { agent.prepareBranch(ref, source, MessageId("tool-assistant")); fail("Copied an unresolved call") }
        catch (_: InvalidAgentHistory) { }
        agent.appendMessage(ref, source, AgentMessage.ToolResult(MessageId("tool-result"), call.id, call.name,
            JsonObjectValue.parse("{}", JsonInputLimits(1024, 4)), false))
        val preview = agent.prepareBranch(ref, source, MessageId("tool-result"))
        assertFalse(preview.editable)
        agent.appendMessage(ref, source, AgentMessage.Assistant(MessageId("later"), listOf(AssistantPart.Text("done"))))
        try { agent.createBranch(preview, CommandId("stale-branch"), null); fail("Branched stale history") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, agent.chats(ref).size)
    }

    @Test fun longBranchCopiesArchivedMessagesAndRemapsInheritedSummaryBoundary() = runBlocking {
        val ref = project().ref
        val source = agent.currentChat(ref)
        repeat(140) { index ->
            agent.appendMessage(ref, source, AgentMessage.User(MessageId("archive-u-$index"), listOf(UserPart.Text("question $index"))))
            agent.appendMessage(ref, source, AgentMessage.Assistant(MessageId("archive-a-$index"), listOf(AssistantPart.Text("answer $index"))))
            if (index >= 9 && index % 10 == 9) agent.compactContext(agent.prepareContext(ref, source), "Summary $index")
        }
        val preview = agent.prepareBranch(ref, source, MessageId("archive-a-139"))
        assertEquals(280, preview.messages)
        val target = agent.createBranch(preview, CommandId("long-branch"), "new final answer")
        val model = agent.modelHistory(ref, target)
        assertEquals(5, model.size)
        assertTrue((model.first() as AgentMessage.System).text.contains("Summary 139"))
        assertEquals("new final answer", ((model.last() as AgentMessage.Assistant).parts.single() as AssistantPart.Text).text)
        val db = NativeAppDatabase.open(context)
        try { withContext(Dispatchers.IO) {
            assertEquals(280, db.agent().historyCount(ref.instanceId.value, ref.generation.value, source.value))
            assertEquals(280, db.agent().historyCount(ref.instanceId.value, ref.generation.value, target.value))
            val old = db.agent().context(ref.instanceId.value, ref.generation.value, source.value)!!
            val inherited = db.agent().context(ref.instanceId.value, ref.generation.value, target.value)!!
            assertNotEquals(old.boundaryId, inherited.boundaryId)
            assertEquals(target.value, db.agent().message(inherited.boundaryId)!!.chatId)
        } } finally { db.close() }
        // Editing inside the archived prefix must not inherit a summary of the old message.
        val earlier = agent.prepareBranch(ref, source, MessageId("archive-a-0"))
        val earlyChat = agent.createBranch(earlier, CommandId("early-branch"), "edited old reply")
        val earlyHistory = agent.modelHistory(ref, earlyChat)
        assertEquals(2, earlyHistory.size)
        assertFalse(earlyHistory.any { it is AgentMessage.System })
    }

    @Test fun automaticContextRetainsThePolicyWindowAndRejectsAStalePreview() = runBlocking {
        val ref = project().ref
        repeat(4) { index ->
            agent.appendMessage(ref, chat, AgentMessage.User(MessageId("auto-u-$index"), listOf(UserPart.Text("request $index"))))
            agent.appendMessage(ref, chat, AgentMessage.Assistant(MessageId("auto-a-$index"), listOf(AssistantPart.Text("reply $index"))))
        }
        val preview = agent.prepareAutoContext(ref, chat, 2)
        assertEquals(4, preview.context.archivedMessages)
        assertEquals(4, preview.context.retainedMessages)
        assertEquals(0L, preview.revision)
        val singleTurn = agent.prepareAutoContext(ref, chat, 1)
        assertEquals(6, singleTurn.context.archivedMessages)
        assertEquals(2, singleTurn.context.retainedMessages)
        agent.saveAutoContext(preview, "Automatic summary through turn 1")
        val model = agent.modelHistory(ref, chat)
        assertEquals(5, model.size)
        assertTrue((model.first() as AgentMessage.System).text.contains("Automatic summary through turn 1"))
        assertEquals(8, agent.history(ref, chat).size)

        repeat(2) { index ->
            val turn = index + 4
            agent.appendMessage(ref, chat, AgentMessage.User(MessageId("auto-u-$turn"), listOf(UserPart.Text("request $turn"))))
            agent.appendMessage(ref, chat, AgentMessage.Assistant(MessageId("auto-a-$turn"), listOf(AssistantPart.Text("reply $turn"))))
        }
        val next = agent.prepareAutoContext(ref, chat, 2)
        assertEquals(1L, next.revision)
        assertEquals("Automatic summary through turn 1", next.context.previousSummary)
        agent.appendMessage(ref, chat, AgentMessage.User(MessageId("auto-stale"), listOf(UserPart.Text("new request"))))
        try { agent.saveAutoContext(next, "Stale summary"); fail("Saved an automatic summary over a changed conversation") }
        catch (_: IllegalArgumentException) { }
        assertEquals("Automatic summary through turn 1",
            (agent.modelHistory(ref, chat).first() as AgentMessage.System).text.substringAfter('\n'))
    }

    private suspend fun project() = projects.createTxt(CommandId("create"), "test", RelativePath("test.txt"), "source".toByteArray())
    private fun begin(ref: LocalProjectRef, run: RunId = RunId("run"), command: CommandId = CommandId("start")): RunLease {
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        return runtime.beginRun(RunIdentity(ref.projectId, chat, run, command), RuntimeOwner.AGENT)
    }
    /** Room 2.7's KMP RoomDatabase exposes close() but no longer implements Closeable. */
    private suspend fun <T> withAppDatabase(block: (NativeAppDatabase) -> T): T = withContext(Dispatchers.IO) {
        val db = NativeAppDatabase.open(context)
        try { return@withContext block(db) } finally { db.close() }
    }
    private suspend fun prepare(ref: LocalProjectRef): TextApproval {
        val lease = begin(ref)
        try { return agent.prepare(lease, ref, ItemId(1), "approved text") }
        finally { runtime.finishRun(lease) }
    }
    private suspend fun allow(approval: TextApproval): TextApprovalState {
        val lease = begin(approval.ref, approval.runId, approval.commandId)
        try { return agent.apply(lease, approval.ref, approval.id, approval.digest) }
        finally { runtime.finishRun(lease) }
    }
    @Test fun fixedDifferenceSurvivesReopenAndAllowOnceDoesNotApplyTwice() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        agent.close(); agent = NativeAgentStore(context, projects, runtime)
        val restored = agent.pending(project.ref, chat)!!
        assertEquals(approval.digest, restored.digest); assertEquals("approved text", restored.after)
        assertEquals(0L, projects.open(project.ref).revision)
        assertEquals(TextApprovalState.COMMITTED, allow(restored))
        assertEquals(TextApprovalState.COMMITTED, allow(restored))
        assertEquals(1L, projects.open(project.ref).revision)
        assertEquals("approved text", projects.item(project.ref, ItemId(1))!!.item.translation)
    }
    @Test fun rejectionNeverChangesProjectAndCannotBeApprovedLater() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        agent.reject(project.ref, approval.id, approval.digest)
        assertEquals(TextApprovalState.REJECTED, allow(approval))
        assertEquals(0L, projects.open(project.ref).revision)
    }
    @Test fun changedBaselineProducesDurableConflict() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        projects.edit(ManualTextEdit(CommandId("manual"), project.ref, ItemId(1), 0, approval.base, "new manual"))
        assertEquals(TextApprovalState.CONFLICT, allow(approval))
        assertEquals(TextApprovalState.CONFLICT, allow(approval))
        assertEquals("new manual", projects.item(project.ref, ItemId(1))!!.item.translation)
        assertEquals(1L, projects.open(project.ref).revision)
    }
    @Test fun consumedWithoutProjectReceiptIsTerminatedOnRestartAndNeverAutoExecuted() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        withAppDatabase { db -> db.agent().settle(approval.id.value, "PENDING", "CONSUMED") }
        agent.close(); agent = NativeAgentStore(context, projects, runtime)
        agent.reconcile(project.ref, chat)
        assertNull(agent.pending(project.ref, chat))
        assertEquals(TextApprovalState.CANCELLED, allow(approval))
        assertEquals(0L, projects.open(project.ref).revision)
    }
    @Test fun projectCommitBeforeRoomFinalizationIsRecoveredFromExactCommandReceipt() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        withAppDatabase { db -> db.agent().settle(approval.id.value, "PENDING", "CONSUMED") }
        val lease = begin(project.ref, approval.runId, approval.commandId)
        try { projects.approvedEdit(lease, ManualTextEdit(approval.commandId, project.ref, approval.itemId,
            approval.revision, approval.base, approval.after)) }
        finally { runtime.finishRun(lease) }
        agent.reconcile(project.ref, chat)
        assertNull(agent.pending(project.ref, chat))
        assertEquals(TextApprovalState.COMMITTED, allow(approval))
        assertEquals(1L, projects.open(project.ref).revision)
    }
    @Test fun tamperedPayloadFailsDigestValidationAndDoesNotWrite() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        withAppDatabase { db -> db.openHelper.writableDatabase.execSQL(
            "UPDATE native_agent_approvals SET afterText = 'tampered' WHERE id = ?", arrayOf(approval.id.value)) }
        try { allow(approval); fail("Accepted changed payload") } catch (_: IllegalArgumentException) { }
        assertEquals(0L, projects.open(project.ref).revision)
    }
    @Test fun receiptMismatchDuringReconcileKeepsConsumedAndDoesNotReplay() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        withAppDatabase { db -> db.agent().settle(approval.id.value, "PENDING", "CONSUMED") }
        projects.edit(ManualTextEdit(approval.commandId, project.ref, approval.itemId,
            approval.revision, approval.base, "different intent"))
        try { agent.reconcile(project.ref, chat); fail("Accepted mismatched receipt") }
        catch (_: AgentApprovalUnavailable) { }
        assertEquals(TextApprovalState.CONSUMED, agent.pending(project.ref, chat)!!.state)
        assertEquals(1L, projects.open(project.ref).revision)
        assertEquals("different intent", projects.item(project.ref, approval.itemId)!!.item.translation)
        assertNotNull(agent.messages(project.ref, chat))
        assertNotNull(agent.results(project.ref, chat))
    }

    @Test fun tamperedApprovalDuringReconcileRemainsUnresolvedAndCannotBeApproved() = runBlocking {
        val project = project(); val approval = prepare(project.ref)
        withAppDatabase { db ->
            db.agent().settle(approval.id.value, "PENDING", "CONSUMED")
            db.openHelper.writableDatabase.execSQL(
                "UPDATE native_agent_approvals SET afterText = 'tampered' WHERE id = ?", arrayOf(approval.id.value))
        }
        try { agent.reconcile(project.ref, chat); fail("Accepted changed payload") }
        catch (_: AgentApprovalUnavailable) { }
        withAppDatabase { db ->
            assertEquals("CONSUMED", db.agent().approval(approval.id.value)!!.state)
        }
        try { allow(approval); fail("Approved changed payload") } catch (_: IllegalArgumentException) { }
        assertEquals(0L, projects.open(project.ref).revision)
    }

    @Test fun migrationFromV1KeepsProjectIndexAndAddsAgentTables() = runBlocking {
        val original = project()
        agent.close(); projects.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE native_agent_chats")
            db.execSQL("DROP TABLE native_model_settings")
            db.execSQL("DROP TABLE native_agent_messages")
            db.execSQL("DROP TABLE native_agent_approvals")
            db.execSQL("DROP TABLE native_agent_requests")
            db.execSQL("DROP TABLE native_runs")
            db.execSQL("DELETE FROM room_master_table")
            db.version = 1
        }
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        assertEquals(original.ref, projects.registeredProjects().single().ref)
        agent.append(original.ref, chat, "SYSTEM", "migration")
        assertEquals(1, agent.messages(original.ref, chat).size)
    }
    @Test fun importedCopyCannotConsumeOriginalApproval() = runBlocking {
        val original = project(); val approval = prepare(original.ref)
        val copy = projects.copyProject(CommandId("copy"), original.ref)
        val lease = begin(original.ref, approval.runId, approval.commandId)
        try {
            try { agent.apply(lease, copy.ref, approval.id, approval.digest); fail("Accepted foreign instance") }
            catch (_: IllegalArgumentException) { }
        } finally { runtime.finishRun(lease) }
        assertEquals(0L, projects.open(original.ref).revision)
        assertEquals(0L, projects.open(copy.ref).revision)
    }

    @Test fun fullMessageDetailsRemainBoundToProjectGenerationAndChat() = runBlocking {
        val project = project()
        val body = "full message ".repeat(300)
        val message = AgentMessage.Assistant(MessageId("full-message"), listOf(
            AssistantPart.Text(body), AssistantPart.Thinking("fixture thinking", null)))
        agent.appendMessage(project.ref, chat, message)
        val preview = agent.messages(project.ref, chat).single()
        assertTrue(preview.text.length <= 2000)
        val detail = agent.messageDetail(project.ref, chat, message.id.value)
        assertEquals(body, detail.text); assertEquals("fixture thinking", detail.reasoning)
        try { agent.messageDetail(project.ref, ChatId("other-chat"), message.id.value); fail() } catch (_: IllegalArgumentException) { }
        try {
            agent.messageDetail(project.ref.copy(generation = RecoveryGeneration(project.ref.generation.value + 1)), chat, message.id.value)
            fail("Accepted a different recovery generation")
        } catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.WRONG_IDENTITY, error.reason) }
        val copy = projects.copyProject(CommandId("details-copy"), project.ref)
        try { agent.messageDetail(copy.ref, chat, message.id.value); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun dispatchedRequestIsUncertainUntilResponseAndUsageArePersisted() = runBlocking {
        val project = project()
        val request = AgentModelRequest(RunIdentity(project.ref.projectId, chat, RunId("request-run"), CommandId("request-command")),
            BranchId("main"), RequestAttemptId("attempt"), ModelConfigurationRef("local", 1),
            listOf(AgentMessage.User(MessageId("user"), listOf(UserPart.Text("query")))), emptyList())
        assertEquals(RequestReservation.Prepared, agent.reserveRequest(request))
        assertTrue(agent.markDispatched(request.key()))
        agent.close(); agent = NativeAgentStore(context, projects, runtime)
        assertEquals(RequestReservation.Uncertain, agent.reserveRequest(request))
        assertFalse(agent.markDispatched(request.key()))
        val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)
        val response = AgentModelResponse(AgentMessage.Assistant(MessageId("response"), listOf(AssistantPart.Text("result"))),
            RequestUsage(unknown, unknown, unknown, unknown, unknown), ModelStopReason.COMPLETE)
        agent.completeRequest(request.key(), response)
        val stored = agent.reserveRequest(request) as RequestReservation.Completed
        assertEquals("result", (stored.response.message.parts.single() as AssistantPart.Text).text)
        assertEquals(UsageSource.UNKNOWN, stored.response.usage.output.source)
    }

    @Test fun structuredToolLoopPersistsPairsAndStopsForApprovalWithoutWriting() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        var calls = 0
        val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)
        val model = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                calls++
                val call = if (calls == 1) AgentToolCall(ToolCallId("read"), "read_item", JsonObjectValue.parse("{\"item_id\":1}", JsonInputLimits(4096, 16)), "provider-read")
                    else AgentToolCall(ToolCallId("prepare"), "prepare_text_edit", JsonObjectValue.parse("{\"item_id\":1,\"translation\":\"model proposal\"}", JsonInputLimits(4096, 16)), "provider-prepare")
                return AgentModelResponse(AgentMessage.Assistant(MessageId("response-$calls"), listOf(AssistantPart.ToolCall(call))),
                    RequestUsage(unknown, unknown, unknown, unknown, unknown), ModelStopReason.TOOL_CALLS)
            }
        }
        try {
            val result = NativeKoogConversation(runtime, projects, agent, agent, agent, model).send(lease, project.ref,
                ModelConfigurationRef("fixture", 1), "Translate the first item")
            assertTrue(result.waitingApproval)
        } finally { runtime.finishRun(lease) }
        assertEquals(2, calls)
        assertEquals(0L, projects.open(project.ref).revision)
        val history = agent.history(project.ref, chat)
        AgentHistory.validate(history)
        assertEquals(2, history.filterIsInstance<AgentMessage.ToolResult>().size)
        assertEquals("model proposal", agent.pending(project.ref, chat)!!.after)
    }
    @Test fun stopAfterAssistantCommitCompletesPendingCalls() = runBlocking {
        verifyStopAfterCommit(cancelAfterResult = false)
    }

    @Test fun stopAfterToolResultCommitDoesNotDuplicateResult() = runBlocking {
        verifyStopAfterCommit(cancelAfterResult = true)
    }

    private suspend fun verifyStopAfterCommit(cancelAfterResult: Boolean) = coroutineScope {
        val project = project()
        val lease = begin(project.ref)
        var requests = 0
        val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)
        val model = object : AgentModelPort {
            override suspend fun complete(request: AgentModelRequest, emit: suspend (AgentModelEvent) -> Unit): AgentModelResponse {
                requests++
                val parts = if (requests == 1) listOf("one", "two").map { id ->
                    AssistantPart.ToolCall(AgentToolCall(ToolCallId(id), "project_summary",
                        JsonObjectValue.parse("{}", JsonInputLimits(128, 4))))
                } else listOf(AssistantPart.Text("continued"))
                return AgentModelResponse(AgentMessage.Assistant(MessageId("response-$requests"), parts),
                    RequestUsage(unknown, unknown, unknown, unknown, unknown),
                    if (requests == 1) ModelStopReason.TOOL_CALLS else ModelStopReason.COMPLETE)
            }
        }
        try {
            val turn = launch {
                val turnJob = currentCoroutineContext()[Job]!!
                val port = object : AgentConversationPort by agent {
                    override suspend fun appendMessage(ref: LocalProjectRef, chat: ChatId, message: AgentMessage) {
                        agent.appendMessage(ref, chat, message)
                        if ((!cancelAfterResult && message is AgentMessage.Assistant) ||
                            (cancelAfterResult && message is AgentMessage.ToolResult)) turnJob.cancel()
                    }
                }
                NativeKoogConversation(runtime, projects, agent, port, agent, model).send(lease, project.ref,
                    ModelConfigurationRef("fixture", 1), "Read project")
            }
            turn.join()
            assertTrue(turn.isCancelled)
            val history = agent.history(project.ref, chat)
            AgentHistory.validate(history)
            val results = history.filterIsInstance<AgentMessage.ToolResult>()
            assertEquals(2, results.size)
            assertEquals(2, results.map { it.callId }.toSet().size)
            assertEquals(if (cancelAfterResult) 1 else 2, results.count { it.isError })
            assertEquals(1, requests)
            NativeKoogConversation(runtime, projects, agent, agent, agent, model).send(lease, project.ref,
                ModelConfigurationRef("fixture", 1), "Continue")
            AgentHistory.validate(agent.history(project.ref, chat))
            assertEquals(2, requests)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun chatSelectionSurvivesRestartAndSeparatesStructuredHistory() = runBlocking {
        val project = project()
        val first = agent.currentChat(project.ref)
        agent.appendMessage(project.ref, first, AgentMessage.User(MessageId("first-message"), listOf(UserPart.Text("first"))))
        val second = agent.newChat(project.ref)
        assertNotEquals(first, second); assertTrue(agent.history(project.ref, second).isEmpty())
        agent.selectChat(project.ref, first)
        agent.close(); agent = NativeAgentStore(context, projects, runtime)
        assertEquals(first, agent.currentChat(project.ref))
        assertEquals(1, agent.history(project.ref, first).size)
        assertEquals(2, agent.chats(project.ref).size)
    }

    @Test fun koogPreparationProducesPendingDifferenceWithoutProjectWrite() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        try {
            val result = NativeKoogWorkspace(runtime, projects, agent).prepare(lease, project.ref, agent, ItemId(1), "from local workflow")
            assertTrue(result.contains("PENDING"))
        } finally { runtime.finishRun(lease) }
        val approval = agent.pending(project.ref, chat)!!
        assertEquals("from local workflow", approval.after)
        assertEquals(0L, projects.open(project.ref).revision)
        assertEquals(TextApprovalState.COMMITTED, allow(approval))
        assertEquals(1L, projects.open(project.ref).revision)
    }

    @Test fun koogQueryUsesRealProjectToolAndPersistsRequestCompletion() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        try {
            val text = NativeKoogWorkspace(runtime, projects, agent).query(lease, project.ref)
            assertTrue(text.contains("test.txt")); assertTrue(text.contains("items"))
            agent.append(project.ref, chat, "ASSISTANT", text)
        } finally { runtime.finishRun(lease) }
        agent.close(); agent = NativeAgentStore(context, projects, runtime)
        assertEquals(1, agent.messages(project.ref, chat).size)
        assertEquals(0L, projects.open(project.ref).revision)
    }
}
