package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.DurableRunExecutor
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.engine.unknownRequestUsage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
class NativeRunStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var runs: NativeRunStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); projects = NativeTextProjectStore(context, runtime)
        runs = NativeRunStore(context, projects, runtime)
    }
    @After fun cleanup() = runBlocking { runs.close(); projects.close(); runtime.close() }
    private suspend fun project() = projects.createTxt(CommandId("create"), "test", RelativePath("test.txt"), "one\ntwo".toByteArray())
    private fun begin(ref: LocalProjectRef): RunLease {
        runtime.selectChat(ChatIdentity(ref.projectId, ChatId("workspace")))
        return runtime.beginRun(RunIdentity(ref.projectId, null, RunId("run"), CommandId("start")), RuntimeOwner.TRANSLATION)
    }
    private suspend fun reopen() { runs.close(); runs = NativeRunStore(context, projects, runtime); runs.recoverInterrupted() }

    @Test fun findReturnsTheExactRunAndRejectsAnotherRunIdentity() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        runs.finish(record, StoredRunState.SUCCEEDED)
        runtime.finishRun(lease)

        val found = runs.find(project.ref, lease.identity)
        assertNotNull(found)
        assertEquals(record.executionId, found!!.handle.executionId)
        assertEquals(StoredRunState.SUCCEEDED, found.state)
        assertNull(runs.find(project.ref, lease.identity.copy(runId = RunId("another"))))
        assertNull(runs.find(project.ref, lease.identity.copy(commandId = CommandId("another"))))
    }

    @Test fun recoveryOfFailureStatusWritesDoesNotCountThemAsTranslations() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        val rows = projects.readPage(project.ref).items
        val edits = rows.mapIndexed { offset, item -> ManualTextEdit(CommandId("error-$offset"), project.ref,
            item.id, offset.toLong(), item.fingerprint, item.translation, TextItemStatus.ERROR) }
        runs.checkpoint(record, RunCheckpoint(StoredRunStage.MARKING_FAILURE, 0,
            pendingCommands = edits.map { it.commandId }))
        projects.markTranslationFailures(lease, TranslationFailureBatch(project.ref, 0, edits))
        runtime.finishRun(lease)
        reopen()
        val restored = runs.recent(project.ref).single()
        assertEquals(0, restored.checkpoint.committedItems)
        assertEquals(2L, restored.checkpoint.revision)
        assertEquals(StoredRunStage.COMMITTED, restored.checkpoint.stage)
        assertFalse(restored.needsReview)
        assertTrue(projects.readPage(project.ref).items.all { it.status == TextItemStatus.ERROR })
    }

    @Test fun retryAfterUnknownDispatchPreservesReviewEvenIfLaterRequestSucceeds() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        val row = projects.readPage(project.ref).items.first()
        val first = RequestAttemptId("unknown-first")
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUESTING, 0, requests = 1, translationAttempt = first))
            audit.dispatched(lease, TranslationModelRequest(project.ref, lease.identity, first,
                ModelConfigurationRef("remote", 1), listOf(TranslationInput(row.id, row.source, row.fingerprint)),
                RemoteTranslationOptions("en")), "a".repeat(64))
            runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUEST_FAILED, 0, requests = 1, translationAttempt = first))
            runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUESTING, 0, requests = 2,
                translationAttempt = RequestAttemptId("replacement")))
            runs.checkpoint(record, RunCheckpoint(StoredRunStage.RESPONSE_RECORDED, 0, requests = 2))
            runs.finish(record, StoredRunState.SUCCEEDED)
            runtime.finishRun(lease)
            val restored = runs.recent(project.ref).single()
            assertTrue(restored.needsReview)
            assertEquals(StoredRunState.INTERRUPTED, restored.state)
            assertEquals(2, restored.checkpoint.requests)
        } finally { audit.close() }
    }

    @Test fun abandonedRequestIsInterruptedWithoutReplayAndRecoveryIsIdempotent() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUESTING, 0, requests = 1))
        runtime.finishRun(lease); reopen(); runs.recoverInterrupted()
        val restored = runs.recent(project.ref).single()
        assertEquals(StoredRunState.INTERRUPTED, restored.state); assertTrue(restored.needsReview)
        assertEquals(0L, projects.open(project.ref).revision)
        try { runs.checkpoint(record, RunCheckpoint(StoredRunStage.ACCEPTED)); fail("Accepted old process handle") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun recoveryDoesNotInterruptLiveExecutionAndCancelledDispatchRemainsUncertain() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        runs.recoverInterrupted()
        assertEquals(StoredRunState.RUNNING, runs.recent(project.ref).single().state)
        runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUESTING, requests = 1))
        runs.finish(record, StoredRunState.CANCELLED); runtime.finishRun(lease)
        assertEquals(StoredRunState.INTERRUPTED, runs.recent(project.ref).single().state)
    }

    @Test fun committedBatchIsRecoveredFromExactReceiptsWithoutWritingAgain() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        val edits = projects.items(project.ref, 0).items.mapIndexed { index, item ->
            ManualTextEdit(CommandId("edit-$index"), project.ref, item.id, index.toLong(), item.fingerprint, "translated-$index")
        }
        runs.checkpoint(record, RunCheckpoint(StoredRunStage.COMMITTING, 0, requests = 1, pendingCommands = edits.map { it.commandId }))
        projects.commitTranslation(lease, TranslationBatch(project.ref, 0, edits))
        runtime.finishRun(lease); reopen()
        val restored = runs.recent(project.ref).single()
        assertEquals(StoredRunState.INTERRUPTED, restored.state); assertFalse(restored.needsReview)
        assertEquals(2, restored.checkpoint.committedItems); assertEquals(2L, restored.checkpoint.revision)
        runs.recoverInterrupted(); assertEquals(2L, projects.open(project.ref).revision)
    }

    @Test fun uncommittedBatchDoesNotCreateTranslationsOnRecovery() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        runs.checkpoint(record, RunCheckpoint(StoredRunStage.COMMITTING, 0, requests = 1, pendingCommands = listOf(CommandId("never-committed"))))
        runtime.finishRun(lease); reopen()
        assertEquals(0, runs.recent(project.ref).single().checkpoint.committedItems)
        assertEquals(0L, projects.open(project.ref).revision)
    }

    @Test fun copiesAndGenerationsCannotReadOrModifyOtherExecutionRecords() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        val foreign = project.ref.copy(generation = RecoveryGeneration(2))
        assertTrue(runs.recent(foreign).isEmpty())
        try { runs.finish(record.copy(ref = foreign), StoredRunState.SUCCEEDED); fail("Accepted foreign generation") }
        catch (_: IllegalArgumentException) { }
        runs.finish(record, StoredRunState.SUCCEEDED); runtime.finishRun(lease)
        val copy = projects.copyProject(CommandId("copy"), project.ref)
        assertTrue(runs.recent(copy.ref).isEmpty())
    }

    @Test fun migrationFromV2PreservesProjectAndAgentRecordsAndAddsRunJournal() = runBlocking {
        val original = project()
        val agent = NativeAgentStore(context, projects, runtime)
        try { agent.append(original.ref, ChatId("chat"), "SYSTEM", "keep") } finally { agent.close() }
        runs.close(); projects.close()
        withContext(Dispatchers.IO) {
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                // Rebuild a genuine v2 app DB: drop every later table and the v5 model columns.
                db.execSQL("DROP TABLE native_runs")
                db.execSQL("DROP TABLE native_translation_requests")
                db.execSQL("DROP TABLE native_translation_staged")
                db.execSQL("DROP TABLE native_translation_responses")
                db.execSQL("DROP TABLE native_translation_request_inputs")
                db.execSQL("DROP TABLE native_translation_targets")
                db.execSQL("DROP TABLE native_translation_tasks")
                db.execSQL("DROP TABLE native_usage_receipts")
                db.execSQL("ALTER TABLE native_model_settings RENAME TO fixture_v5_models")
                db.execSQL("CREATE TABLE native_model_settings (revision INTEGER NOT NULL PRIMARY KEY, endpoint TEXT NOT NULL, model TEXT NOT NULL, encryptedKey TEXT NOT NULL)")
                db.execSQL("INSERT INTO native_model_settings(revision,endpoint,model,encryptedKey) SELECT revision,endpoint,model,encryptedKey FROM fixture_v5_models")
                db.execSQL("DROP TABLE fixture_v5_models")
                db.execSQL("DELETE FROM room_master_table"); db.version = 2
            }
        }
        projects = NativeTextProjectStore(context, runtime); runs = NativeRunStore(context, projects, runtime)
        assertEquals(original.ref, projects.registeredProjects().single().ref)
        val reopenedAgent = NativeAgentStore(context, projects, runtime)
        try { assertEquals("keep", reopenedAgent.messages(original.ref, ChatId("chat")).single().text) }
        finally { reopenedAgent.close() }
        val lease = begin(original.ref)
        val record = runs.begin(original.ref, lease.identity, RuntimeOwner.TRANSLATION)
        runs.finish(record, StoredRunState.SUCCEEDED); runtime.finishRun(lease)
        assertEquals(StoredRunState.SUCCEEDED, runs.recent(original.ref).single().state)
    }

    @Test fun recordedAgentResponseIsReconciledWithoutReissuingRequestOrAppendingHistory() = runBlocking {
        val project = project(); val chat = ChatId("chat")
        runtime.selectChat(ChatIdentity(project.ref.projectId, chat))
        val lease = runtime.beginRun(RunIdentity(project.ref.projectId, chat, RunId("agent"), CommandId("send")), RuntimeOwner.AGENT)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.AGENT)
        val request = AgentModelRequest(lease.identity, BranchId("main"), RequestAttemptId("attempt"),
            ModelConfigurationRef("fixture", 1), listOf(AgentMessage.User(MessageId("input"), listOf(UserPart.Text("hello")))), emptyList())
        val agent = NativeAgentStore(context, projects, runtime)
        try {
            runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUESTING, requests = 1, agentRequest = request.key()))
            agent.reserveRequest(request); assertTrue(agent.markDispatched(request.key()))
            agent.completeRequest(request.key(), AgentModelResponse(
                AgentMessage.Assistant(MessageId("response"), listOf(AssistantPart.Text("known response"))), unknownRequestUsage(), ModelStopReason.COMPLETE))
            runtime.finishRun(lease); reopen()
            val restored = runs.recent(project.ref).single()
            assertEquals(StoredRunState.INTERRUPTED, restored.state)
            assertEquals(StoredRunStage.RESPONSE_RECORDED, restored.checkpoint.stage); assertFalse(restored.needsReview)
            assertTrue(agent.history(project.ref, chat).isEmpty())
            assertEquals(0L, projects.open(project.ref).revision)
        } finally { agent.close() }
    }

    @Test fun conflictingApprovalPersistsFailedForBothWaitingAndApprovalExecutions() = runBlocking {
        val project = project(); val chat = ChatId("chat")
        runtime.selectChat(ChatIdentity(project.ref.projectId, chat))
        val identity = RunIdentity(project.ref.projectId, chat, RunId("agent"), CommandId("prepare"))
        val executor = DurableRunExecutor(runtime, runs)
        val agent = NativeAgentStore(context, projects, runtime)
        try {
            val approval = executor.execute(project.ref, identity, RuntimeOwner.AGENT) { lease, recorder ->
                agent.prepare(lease, project.ref, ItemId(1), "approved").also { recorder.waitingForApproval() }
            }
            assertEquals(StoredRunState.WAITING_APPROVAL, runs.recent(project.ref).single().state)
            projects.edit(ManualTextEdit(CommandId("manual"), project.ref, approval.itemId,
                approval.revision, approval.base, "manual change"))
            val result = executor.execute(project.ref, identity.copy(commandId = approval.commandId), RuntimeOwner.AGENT) { lease, recorder ->
                agent.apply(lease, project.ref, approval.id, approval.digest).also { recorder.approvalResult(it) }
            }
            assertEquals(TextApprovalState.CONFLICT, result)
            val records = runs.recent(project.ref)
            assertEquals(2, records.size)
            assertEquals(2, records.map { it.handle.executionId }.distinct().size)
            assertTrue(records.all { it.state == StoredRunState.FAILED })
            reopen()
            assertTrue(runs.recent(project.ref).all { it.state == StoredRunState.FAILED })
            assertEquals("manual change", projects.item(project.ref, approval.itemId)!!.item.translation)
            assertEquals(1L, projects.open(project.ref).revision)
        } finally { agent.close() }
    }

    @Test fun approvalExecutionKeepsLogicalRunAndSettlesPriorWaitingRecord() = runBlocking {
        val project = project(); val chat = ChatId("chat")
        runtime.selectChat(ChatIdentity(project.ref.projectId, chat))
        val identity = RunIdentity(project.ref.projectId, chat, RunId("agent"), CommandId("prepare"))
        val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val first = runs.begin(project.ref, identity, RuntimeOwner.AGENT)
        val agent = NativeAgentStore(context, projects, runtime)
        try {
            val approval = agent.prepare(lease, project.ref, ItemId(1), "approved")
            runs.finish(first, StoredRunState.WAITING_APPROVAL); runtime.finishRun(lease)
            val approving = runtime.beginRun(identity.copy(commandId = approval.commandId), RuntimeOwner.AGENT)
            val second = runs.begin(project.ref, approving.identity, RuntimeOwner.AGENT)
            assertNotEquals(first.executionId, second.executionId); assertEquals(first.run.runId, second.run.runId)
            assertEquals(TextApprovalState.COMMITTED, agent.apply(approving, project.ref, approval.id, approval.digest))
            runs.finish(second, StoredRunState.SUCCEEDED); runtime.finishRun(approving)
            assertTrue(runs.recent(project.ref).all { it.state == StoredRunState.SUCCEEDED })
            assertEquals(1L, projects.open(project.ref).revision)
        } finally { agent.close() }
    }
}
