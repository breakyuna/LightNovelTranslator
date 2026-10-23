package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.engine.unknownRequestUsage
import java.io.File
import kotlinx.coroutines.*
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
class NativeTranslationAuditStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var audit: NativeTranslationAuditStore
    private lateinit var runs: NativeRunStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); projects = NativeTextProjectStore(context, runtime)
        audit = NativeTranslationAuditStore(context, runtime, projects); runs = NativeRunStore(context, projects, runtime)
    }
    @After fun cleanup() = runBlocking { audit.close(); runs.close(); projects.close(); runtime.close() }
    private suspend fun project() = projects.createTxt(CommandId("create"), "test", RelativePath("test.txt"), "source".toByteArray())
    private fun begin(ref: LocalProjectRef): RunLease {
        runtime.selectChat(ChatIdentity(ref.projectId, ChatId("workspace")))
        return runtime.beginRun(RunIdentity(ref.projectId, null, RunId("run"), CommandId("start")), RuntimeOwner.TRANSLATION)
    }
    private fun request(ref: LocalProjectRef, lease: RunLease) = TranslationModelRequest(ref, lease.identity, RequestAttemptId("attempt"),
        ModelConfigurationRef("remote", 1), listOf(TranslationInput(ItemId(1), "source", ObjectFingerprint("a".repeat(64)))),
        RemoteTranslationOptions("en"))

    @Test fun completeResponseSurvivesParsingFailureAndReopenWithoutReplayOrTextStorage() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        val request = request(project.ref, lease)
        runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUESTING, 0, requests = 1, translationAttempt = request.attempt))
        val handle = audit.dispatched(lease, request, "b".repeat(64))
        val usage = unknownRequestUsage().copy(input = TokenMeasurement(123, UsageSource.ACTUAL))
        audit.received(handle, usage, ModelFailureKind.PARSING)
        audit.received(handle, usage, ModelFailureKind.PARSING)
        runs.finish(record, StoredRunState.FAILED); runtime.finishRun(lease)
        audit.close(); audit = NativeTranslationAuditStore(context, runtime, projects)
        runs.close(); runs = NativeRunStore(context, projects, runtime); runs.recoverInterrupted()
        val restored = runs.recent(project.ref).single()
        assertEquals(StoredRunState.FAILED, restored.state); assertFalse(restored.needsReview)
        assertEquals(StoredRunStage.RESPONSE_RECORDED, restored.checkpoint.stage)
        assertEquals("123", restored.translationUsage!!.input.actual.toString())
        assertEquals(1L, restored.translationUsage!!.responses)
        assertEquals(0L, projects.open(project.ref).revision)
    }

    @Test fun duplicateAttemptAndForeignReceiptCannotMutateAuditFacts() = runBlocking {
        val project = project(); val lease = begin(project.ref); val request = request(project.ref, lease)
        val handle = audit.dispatched(lease, request, "b".repeat(64))
        try { audit.dispatched(lease, request, "b".repeat(64)); fail("Re-dispatched attempt") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        try { audit.received(handle.copy(ref = project.ref.copy(generation = RecoveryGeneration(2))), unknownRequestUsage(), null); fail("Accepted foreign receipt") }
        catch (_: IllegalArgumentException) { }
        audit.received(handle, unknownRequestUsage(), null)
        try { audit.received(handle, unknownRequestUsage(), ModelFailureKind.CONTENT); fail("Changed terminal receipt") }
        catch (_: IllegalArgumentException) { }
        runtime.finishRun(lease)
    }

    @Test fun crashBeforeAuditReservationIsUnsentButDispatchedRequestStaysUncertain() = runBlocking {
        val project = project(); val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        runs.checkpoint(record, RunCheckpoint(StoredRunStage.REQUESTING, 0, requests = 1, translationAttempt = RequestAttemptId("unsent")))
        runs.finish(record, StoredRunState.CANCELLED); runtime.finishRun(lease)
        assertFalse(runs.recent(project.ref).single().needsReview)
        val next = runtime.beginRun(lease.identity.copy(runId = RunId("next"), commandId = CommandId("next")), RuntimeOwner.TRANSLATION)
        val second = runs.begin(project.ref, next.identity, RuntimeOwner.TRANSLATION)
        val request = request(project.ref, next)
        runs.checkpoint(second, RunCheckpoint(StoredRunStage.REQUESTING, 0, requests = 1, translationAttempt = request.attempt))
        audit.dispatched(next, request, "b".repeat(64))
        runs.finish(second, StoredRunState.CANCELLED); runtime.finishRun(next)
        val interrupted = runs.recent(project.ref).first { it.handle == second }
        assertEquals(StoredRunState.INTERRUPTED, interrupted.state); assertTrue(interrupted.needsReview)
    }

    @Test fun v3MigrationPreservesRegistrationsModelsAndRuns() = runBlocking {
        val project = project()
        val originalModels = NativeModelSettingsStore(context, runtime)
        val originalModel = try { originalModels.save("http://localhost:1234/v1", "fixture", "") }
            finally { originalModels.close() }
        val lease = begin(project.ref)
        val record = runs.begin(project.ref, lease.identity, RuntimeOwner.TRANSLATION)
        runs.finish(record, StoredRunState.SUCCEEDED); runtime.finishRun(lease)
        audit.close(); runs.close(); projects.close()
        withContext(Dispatchers.IO) {
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE native_translation_requests"); db.execSQL("DELETE FROM room_master_table"); db.version = 3
            }
        }
        projects = NativeTextProjectStore(context, runtime); runs = NativeRunStore(context, projects, runtime)
        audit = NativeTranslationAuditStore(context, runtime, projects)
        assertEquals(project.ref, projects.registeredProjects().single().ref)
        assertEquals(StoredRunState.SUCCEEDED, runs.recent(project.ref).single().state)
        val model = NativeModelSettingsStore(context, runtime)
        try {
            assertEquals(originalModel.reference, model.current()!!.reference)
            assertEquals("fixture", model.resolve(originalModel.reference).model)
            assertEquals("", model.credential(originalModel.reference))
        } finally { model.close() }
    }
}
