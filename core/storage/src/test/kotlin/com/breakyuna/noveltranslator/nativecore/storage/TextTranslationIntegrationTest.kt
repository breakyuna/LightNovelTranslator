package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
class TextTranslationIntegrationTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore
    private lateinit var runs: NativeRunStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
        runs = NativeRunStore(context, store, runtime)
    }
    @After fun cleanup() = runBlocking { runs.close(); store.close(); runtime.close() }
    private suspend fun create(text: String): TextProjectSummary {
        val project = store.createTxt(CommandId("create"), "test", RelativePath("test.txt"), text.toByteArray())
        runtime.selectChat(ChatIdentity(project.ref.projectId, ChatId("workspace")))
        return project
    }
    private fun identity(project: TextProjectSummary) = RunIdentity(project.ref.projectId, null, RunId("run"), CommandId("start"))
    private fun service(model: TranslationModelBackend) = TextTranslationService(runtime, store, store, NativeModelGateway(runtime, model), DurableRunExecutor(runtime, runs))

    @Test fun translatesOnlyCurrentPageAndPreservesProcessedExcludedAndBlankRows() = runBlocking {
        val project = create("keep\nexclude\n\nnew\nnext")
        val rows = store.items(project.ref, 0).items
        store.edit(ManualTextEdit(CommandId("manual"), project.ref, rows[0].id, 0, rows[0].fingerprint, "saved"))
        store.edit(ManualTextEdit(CommandId("exclude"), project.ref, rows[1].id, 1, rows[1].fingerprint, "", TextItemStatus.EXCLUDED))
        val result = service(LocalSimulationModel()).translatePage(project.ref, identity(project), LocalSimulationModel().configuration, 2, 0, 4)
        val after = store.items(project.ref, result.revision).items
        assertEquals(1, result.committedItems); assertEquals(3L, result.revision)
        assertEquals("saved", after[0].translation); assertEquals(TextItemStatus.EXCLUDED, after[1].status)
        assertEquals(TextItemStatus.NONE, after[2].status)
        assertEquals("[SIMULATION] new", after[3].translation); assertEquals(TextItemStatus.NONE, after[4].status)
        val snapshot = ByteArrayOutputStream(); store.saveProject(project.ref) { snapshot }
        val imported = store.importProject(CommandId("roundtrip")) { ByteArrayInputStream(snapshot.toByteArray()) }
        assertEquals(3L, imported.revision)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun secondBatchFailureKeepsFirstBatchAndDoesNotRetry() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        var calls = 0
        val local = LocalSimulationModel()
        val model = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++; if (calls == 2) throw AgentModelFailure(ModelFailureKind.AUTHENTICATION)
                return local.complete(request)
            }
        }
        try { service(model).translatePage(project.ref, identity(project), model.configuration, 0, 0); fail("Expected failure") }
        catch (_: AgentModelFailure) { }
        val after = store.open(project.ref)
        assertEquals(2, calls); assertEquals(20L, after.revision)
        assertEquals(20, store.items(project.ref, after.revision).items.count { it.status == TextItemStatus.PROCESSED })
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun cancellationDuringNextRequestKeepsCommittedFactsAndReleasesRun() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        var calls = 0
        val local = LocalSimulationModel()
        val model = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++; if (calls == 2) throw CancellationException("synthetic cancellation")
                return local.complete(request)
            }
        }
        try { service(model).translatePage(project.ref, identity(project), model.configuration, 0, 0); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(20L, store.open(project.ref).revision); assertEquals(2, calls)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun stopAfterFirstBatchPreventsAnotherBackendCallAndAllowsExplicitContinuation() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        var calls = 0
        val local = LocalSimulationModel()
        val model = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls++; return local.complete(request)
            }
        }
        val service = service(model)
        try {
            service.translatePage(project.ref, identity(project), model.configuration, 0, 0) {
                if (it.committedItems == 20) service.stop()
            }
            fail("Expected stopped runtime")
        } catch (_: RuntimeUnavailable) { }
        assertEquals(1, calls)
        assertEquals(20L, store.open(project.ref).revision)
        assertNull(runtime.snapshot().activeRun)
        val continued = service.translatePage(project.ref, identity(project), model.configuration, 20, 0)
        assertEquals(1, continued.committedItems); assertEquals(21L, continued.revision)
        assertEquals(2, calls)
    }

    @Test fun conflictingSecondItemRollsBackEntireBatchIncludingFirstReceipt() = runBlocking {
        val project = create("one\ntwo")
        val rows = store.items(project.ref, 0).items
        val lease = runtime.beginRun(identity(project), RuntimeOwner.TRANSLATION)
        val edits = listOf(
            ManualTextEdit(CommandId("first"), project.ref, rows[0].id, 0, rows[0].fingerprint, "first-result"),
            ManualTextEdit(CommandId("second"), project.ref, rows[1].id, 1, rows[0].fingerprint, "second-result"))
        try {
            try { store.commitTranslation(lease, TranslationBatch(project.ref, 0, edits)); fail("Accepted conflict") }
            catch (failure: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, failure.reason) }
        } finally { runtime.finishRun(lease) }
        assertEquals(0L, store.open(project.ref).revision)
        assertNull(store.receipt(project.ref, CommandId("first")))
        assertTrue(store.items(project.ref, 0).items.all { it.status == TextItemStatus.NONE })
    }

    @Test fun forgedRunCannotWriteEvenWithSameIdentity() = runBlocking {
        val project = create("one")
        val row = store.items(project.ref, 0).items.single()
        val identity = identity(project)
        val real = runtime.beginRun(identity, RuntimeOwner.TRANSLATION)
        val forged = object : RunLease { override val identity = identity }
        try {
            try { store.commitTranslation(forged, TranslationBatch(project.ref, 0,
                listOf(ManualTextEdit(CommandId("forged"), project.ref, row.id, 0, row.fingerprint, "bad")))); fail("Accepted forged lease") }
            catch (_: RuntimeUnavailable) { }
        } finally { runtime.finishRun(real) }
        assertEquals(0L, store.open(project.ref).revision)
    }
}
