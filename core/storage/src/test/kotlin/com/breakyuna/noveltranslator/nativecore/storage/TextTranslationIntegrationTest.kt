package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    @Test fun foregroundProjectScopeProcessesEveryPageWithinOneExecution() = runBlocking {
        val project = create((1..45).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(local))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), local.configuration,
                0, 0, 20, TranslationScope.PROJECT))
            assertEquals(0L, store.open(project.ref).revision)
            assertTrue(runner.foregroundStarted(handle.token))
            val result = withTimeout(10_000) { handle.await() }
            assertEquals(45, result.committedItems); assertEquals(3, result.requests)
            assertEquals(45L, store.open(project.ref).revision)
            assertEquals(1, runs.recent(project.ref).size)
            assertEquals(StoredRunState.SUCCEEDED, runs.recent(project.ref).single().state)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun foregroundProjectStopOnAnotherPageRetainsOnlyCommittedFactsAndNeverRestarts() = runBlocking {
        val project = create((1..45).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        val entered = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                if (calls.incrementAndGet() == 2) { entered.complete(Unit); awaitCancellation() }
                return local.complete(request)
            }
        }
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(backend))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), backend.configuration,
                0, 0, 20, TranslationScope.PROJECT))
            assertTrue(runner.foregroundStarted(handle.token))
            withTimeout(10_000) { entered.await() }
            assertTrue(runner.stop(handle.token))
            try { withTimeout(10_000) { handle.await() }; fail("Expected stop") }
            catch (timeout: TimeoutCancellationException) { throw timeout }
            catch (_: CancellationException) { }
            assertEquals(20L, store.open(project.ref).revision)
            assertEquals(2, calls.get())
            assertEquals(StoredRunState.INTERRUPTED, runs.recent(project.ref).single().state)
            runs.recoverInterrupted(); assertEquals(2, calls.get())
            assertEquals(ForegroundTranslationPhase.CANCELLED, runner.state.value!!.phase)
            assertNull(runtime.snapshot().activeRun)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun usageSurvivesUnknownResponsesAndLongOverflowWithoutInterruptingCommits() = runBlocking {
        val project = create("one\ntwo\nthree")
        val local = LocalSimulationModel()
        var calls = 0
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                val measurement = if (++calls <= 2) TokenMeasurement(Long.MAX_VALUE, UsageSource.ACTUAL)
                    else TokenMeasurement(null, UsageSource.UNKNOWN)
                val response = local.complete(request)
                return TranslationModelResponse(response.outputs,
                    RequestUsage(measurement, measurement, measurement, measurement, measurement), response.stop)
            }
        }
        val result = service(backend).translateProject(project.ref, identity(project), backend.configuration, 0, 1)
        assertEquals(3, result.committedItems); assertEquals(3L, result.revision)
        assertEquals(UsageSource.UNKNOWN, result.usage.input.source)
        val totals = requireNotNull(result.usageTotals)
        assertEquals(3L, totals.responses)
        assertEquals(java.math.BigInteger.valueOf(Long.MAX_VALUE).multiply(java.math.BigInteger.valueOf(2)), totals.input.actual)
        assertEquals(1L, totals.input.unknownRequests)
    }

    @Test fun wholeProjectStreamsPastNoOpPagesAndAccumulatesOneDurableRun() = runBlocking {
        val sources = (0..12).map { if (it == 5) "" else "source-$it" }
        val project = create(sources.joinToString("\n"))
        val rows = store.items(project.ref, 0).items
        for (index in 0..3) store.edit(ManualTextEdit(CommandId("saved-$index"), project.ref,
            rows[index].id, index.toLong(), rows[index].fingerprint, "saved-$index"))
        store.edit(ManualTextEdit(CommandId("exclude"), project.ref, rows[4].id, 4, rows[4].fingerprint, "", TextItemStatus.EXCLUDED))
        val requested = mutableListOf<List<ItemId>>()
        val local = LocalSimulationModel()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                requested.add(request.inputs.map { it.id }); return local.complete(request)
            }
        }
        val result = service(backend).translateProject(project.ref, identity(project), backend.configuration, 5, 4)
        assertEquals(7, result.committedItems); assertEquals(3, result.requests); assertEquals(12L, result.revision)
        assertEquals(listOf(listOf(7L, 8L), listOf(9L, 10L, 11L, 12L), listOf(13L)),
            requested.map { page -> page.map { it.value } })
        val after = store.items(project.ref, 12).items
        assertEquals("saved-0", after[0].translation)
        assertEquals(TextItemStatus.EXCLUDED, after[4].status)
        assertEquals(TextItemStatus.NONE, after[5].status)
        assertEquals(0, store.readPage(project.ref).statistics?.translatableUnprocessed)
        assertEquals(1, store.readPage(project.ref).statistics?.unprocessed)
        assertEquals("[SIMULATION] source-12", after.last().translation)
        val journal = runs.recent(project.ref).single()
        assertEquals(StoredRunState.SUCCEEDED, journal.state)
        assertEquals(7, journal.checkpoint.committedItems); assertEquals(3, journal.checkpoint.requests)
        val snapshot = ByteArrayOutputStream(); store.saveProject(project.ref) { snapshot }
        val imported = store.importProject(CommandId("whole-roundtrip")) { ByteArrayInputStream(snapshot.toByteArray()) }
        assertEquals(12L, imported.revision)
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun failureOnAnotherPageKeepsTheFirstPageAndOnlyExplicitNewRunContinues() = runBlocking {
        val project = create((1..45).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        var calls = 0
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                if (++calls == 2) throw AgentModelFailure(ModelFailureKind.AUTHENTICATION)
                return local.complete(request)
            }
        }
        try { service(backend).translateProject(project.ref, identity(project), backend.configuration, 0, 20); fail("Expected failure") }
        catch (failure: AgentModelFailure) { assertEquals(ModelFailureKind.AUTHENTICATION, failure.kind) }
        assertEquals(2, calls); assertEquals(20L, store.open(project.ref).revision)
        runs.recoverInterrupted(); assertEquals(2, calls)
        val next = RunIdentity(project.ref.projectId, null, RunId("next-run"), CommandId("explicit-next"))
        val result = service(local).translateProject(project.ref, next, local.configuration, 20, 20)
        assertEquals(25, result.committedItems); assertEquals(2, result.requests); assertEquals(45L, result.revision)
        assertEquals(45, store.items(project.ref, 45).items.count { it.status == TextItemStatus.PROCESSED })
        assertEquals(2, runs.recent(project.ref).size)
    }

    @Test fun noOpProjectStillChecksRevisionAndNeverCallsTheModel() = runBlocking {
        val project = create("\n\n")
        val local = LocalSimulationModel()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse = error("Blank rows reached model")
        }
        val result = service(backend).translateProject(project.ref, identity(project), backend.configuration, 0, 1)
        assertEquals(0, result.requests); assertEquals(0, result.committedItems); assertEquals(0L, result.revision)
        val next = RunIdentity(project.ref.projectId, null, RunId("wrong-revision"), CommandId("wrong-start"))
        try { service(backend).translateProject(project.ref, next, backend.configuration, 1, 1); fail("Accepted stale revision") }
        catch (_: TextProjectException) { }
        assertNull(runtime.snapshot().activeRun)
    }

    @Test fun foregroundHostUsesOneDurableExecutionAndOnlyCommitsAfterAcknowledgement() = runBlocking {
        val project = create("one\ntwo")
        val local = LocalSimulationModel()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                calls.incrementAndGet(); return local.complete(request)
            }
        }
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(backend))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), backend.configuration, 0, 0))
            assertNotNull(runtime.snapshot().activeRun)
            assertEquals(0, calls.get()); assertEquals(0L, store.open(project.ref).revision)
            assertTrue(runner.foregroundStarted(handle.token))
            val result = withTimeout(10_000) { handle.await() }
            assertEquals(2, result.committedItems); assertEquals(1, calls.get())
            assertEquals(2L, store.open(project.ref).revision)
            assertEquals(StoredRunState.SUCCEEDED, runs.recent(project.ref).single().state)
            assertNull(runtime.snapshot().activeRun)
        } finally { owner.cancelAndJoin() }
    }

    @Test fun foregroundStopDuringNextRequestKeepsSavedBatchAndRequiresReviewWithoutReplay() = runBlocking {
        val project = create((1..21).joinToString("\n") { "source-$it" })
        val local = LocalSimulationModel()
        val enteredSecondRequest = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val backend = object : TranslationModelBackend {
            override val configuration = local.configuration
            override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                if (calls.incrementAndGet() == 2) {
                    enteredSecondRequest.complete(Unit)
                    awaitCancellation()
                }
                return local.complete(request)
            }
        }
        val owner = SupervisorJob()
        val runner = ForegroundTranslationRunner(runtime, DurableRunExecutor(runtime, runs),
            CoroutineScope(owner + Dispatchers.Default), service(backend))
        try {
            val handle = runner.prepare(ForegroundTranslationRequest(project.ref, identity(project), backend.configuration, 0, 0))
            assertTrue(runner.foregroundStarted(handle.token))
            withTimeout(10_000) { enteredSecondRequest.await() }
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { handle.await() }
            waiter.cancelAndJoin()
            assertEquals(ForegroundTranslationPhase.RUNNING, runner.state.value!!.phase)
            assertNotNull(runtime.snapshot().activeRun)
            assertTrue(runner.stop(handle.token))
            try { withTimeout(10_000) { handle.await() }; fail("Expected stopped execution") }
            catch (timeout: TimeoutCancellationException) { throw timeout }
            catch (_: CancellationException) { }
            assertNull(runtime.snapshot().activeRun)
            assertEquals(2, calls.get()); assertEquals(20L, store.open(project.ref).revision)
            assertEquals(20, store.items(project.ref, 20).items.count { it.status == TextItemStatus.PROCESSED })
            assertEquals(20, runner.state.value!!.progress!!.committedItems)
            assertEquals(ForegroundTranslationPhase.CANCELLED, runner.state.value!!.phase)
            // The in-flight request has no recorded response. Journal uncertainty is distinct
            // from the runner's cancellation, and neither attachment nor recovery replays it.
            val stored = runs.recent(project.ref).single()
            assertEquals(StoredRunState.INTERRUPTED, stored.state); assertTrue(stored.needsReview)
            assertEquals(20, stored.checkpoint.committedItems); assertEquals(2, stored.checkpoint.requests)
            runs.recoverInterrupted()
            assertNotNull(runner.attach(handle.token)); assertEquals(2, calls.get())
        } finally { owner.cancelAndJoin() }
    }

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
