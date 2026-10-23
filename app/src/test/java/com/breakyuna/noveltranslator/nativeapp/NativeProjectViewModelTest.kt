package com.breakyuna.noveltranslator.nativeapp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real native storage and lifecycle scopes; no platform Service or device recreation evidence. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeProjectViewModelTest {
    private lateinit var app: TranslatorApplication
    private lateinit var graph: NativeAppGraph
    private val owner = SupervisorJob()
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("lnt_native_app.db")
        File(app.filesDir, "native_projects").deleteRecursively()
        graph = app.nativeGraph
    }

    @After fun cleanup() = runBlocking {
        try {
            stores.forEach { it.clear() }
            owner.cancelAndJoin()
            graph.agentStore.close(); graph.modelSettings.close(); graph.runStore.close(); graph.projects.close()
            graph.runtime.close()
        } finally { Dispatchers.resetMain() }
    }

    private fun model(saved: SavedStateHandle, projects: TextProjectPort, runner: ForegroundTranslationRunner): Pair<ViewModelStore, NativeProjectViewModel> {
        val store = ViewModelStore().also(stores::add)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeProjectViewModel(app, saved, projects, runner) as T
        })
        return store to provider[NativeProjectViewModel::class.java]
    }

    private fun request(project: TextProjectSummary, after: Long = 0) = ForegroundTranslationRequest(project.ref,
        RunIdentity(project.ref.projectId, null, RunId("run"), CommandId("simulate")), graph.simulationConfiguration,
        project.revision, after)

    @Test fun directProjectSimulationWithOnlyBlankPendingRowsDoesNotReserveOrStartAService() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("blank-ui"), "fixture", RelativePath("source.txt"),
                " \t\n\u00A0\u3000".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            assertEquals(0, model.state.value.itemStatistics?.translatableUnprocessed)
            model.simulateProject(); model.state.first { !it.busy }
            assertNull(graph.backgroundTranslations.state.value)
            assertNull(graph.runtime.snapshot().activeRun)
            assertTrue(graph.runStore.recent(project.ref).isEmpty())
            assertEquals(0L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun manualBatchRequiresConfirmationAndKeepsFilteredMembersAfterAnAtomicStatusChange() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("batch-ui"), "fixture", RelativePath("source.txt"), "one\ntwo\nthree".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.queryProofreading(TextItemQuery(status = TextItemStatus.NONE)); model.state.first { !it.busy }
            model.selectItems(true); model.toggleItem(ItemId(1)); model.toggleItem(ItemId(3)); model.toggleItem(ItemId(999))
            assertEquals(setOf(ItemId(1), ItemId(3)), model.state.value.selectedItems)
            model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val prepared = model.state.first { !it.busy }
            assertNotNull(prepared.manualBatch); assertEquals(0L, graph.projects.open(project.ref).revision)
            model.confirmManualBatch(prepared.manualBatch!!)
            val done = model.state.first { !it.busy }
            assertNull(done.manualBatch); assertFalse(done.selectingItems); assertTrue(done.selectedItems.isEmpty())
            assertEquals(listOf(ItemId(1), ItemId(2), ItemId(3)), done.items.map { it.id })
            assertEquals(listOf(TextItemStatus.EXCLUDED, TextItemStatus.NONE, TextItemStatus.EXCLUDED), done.items.map { it.status })
            assertEquals(1, done.matchingItems); assertEquals(TextItemStatistics(1, 0, 2, 1), done.itemStatistics)
            model.confirmManualBatch(prepared.manualBatch!!); model.state.first { !it.busy }
            assertEquals(2L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun restoredBatchReconcilesAnUnacknowledgedCommitWithoutOverwritingLaterFacts() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("restore-batch"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            var failAfterCommit = true
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun editBatch(command: ManualTextBatch): ManualBatchReceipt {
                    val result = graph.projects.editBatch(command)
                    if (failAfterCommit) { failAfterCommit = false; throw java.io.IOException("Synthetic lost acknowledgement") }
                    return result
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (owner, first) = model(saved, projects, graph.backgroundTranslations)
            first.state.first { !it.busy }
            first.selectItems(true); first.selectPageItems(); first.prepareManualBatch(TextBatchChange.EXCLUDE)
            val prepared = first.state.first { !it.busy }.manualBatch!!
            first.confirmManualBatch(prepared)
            val uncertain = first.state.first { !it.busy }
            assertEquals(prepared.commandId, uncertain.manualBatch!!.commandId)
            assertEquals(2L, graph.projects.open(project.ref).revision)
            owner.clear()
            val item = graph.projects.item(project.ref, ItemId(1))!!
            graph.projects.edit(ManualTextEdit(CommandId("later-edit"), project.ref, item.item.id,
                item.revision, item.item.fingerprint, "later saved", TextItemStatus.PROCESSED))
            val (_, restored) = model(saved, projects, graph.backgroundTranslations)
            val ready = restored.state.first { !it.busy }
            assertEquals(prepared.digest(), ready.manualBatch!!.digest()); assertEquals(NativePage.PROOFREADING, ready.page)
            restored.confirmManualBatch(ready.manualBatch!!)
            val done = restored.state.first { !it.busy }
            assertNull(done.manualBatch); assertEquals(3L, done.project!!.revision)
            assertEquals("later saved", done.items.first().translation)
            assertEquals(TextItemStatus.PROCESSED, done.items.first().status)
        }
    }

    @Test fun knownCommittedBatchIsNotReportedAsRolledBackWhenWindowRefreshFails() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("batch-read-failure"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery, window: TextItemWindow): TextProjectPage {
                    throw java.io.IOException("Synthetic refresh failure")
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.selectItems(true); model.selectPageItems(); model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val batch = model.state.first { !it.busy }.manualBatch!!; model.confirmManualBatch(batch)
            val done = model.state.first { !it.busy }
            assertNull(done.manualBatch); assertEquals(2L, done.project!!.revision)
            assertEquals(R.string.native_batch_refresh_failed, done.error)
            assertNull(done.itemStatistics); assertNull(done.matchingItems)
            assertTrue(done.items.all { it.status == TextItemStatus.EXCLUDED })
            assertTrue(graph.projects.readPage(project.ref).items.all { it.status == TextItemStatus.EXCLUDED })
        }
    }

    @Test fun batchConflictRetainsItsPreviewAndNeverAutomaticallyPreparesANewCommand() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("conflict-batch"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.selectItems(true); model.selectPageItems(); model.prepareManualBatch(TextBatchChange.CLEAR_AND_RESET)
            val noop = model.state.first { !it.busy }
            assertNull(noop.manualBatch); assertEquals(R.string.native_batch_no_changes, noop.notice)
            model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val batch = model.state.first { !it.busy }.manualBatch!!
            val item = graph.projects.item(project.ref, ItemId(2))!!
            graph.projects.edit(ManualTextEdit(CommandId("intervening"), project.ref, item.item.id,
                item.revision, item.item.fingerprint, "saved"))
            model.confirmManualBatch(batch)
            val conflict = model.state.first { !it.busy }
            assertTrue(conflict.manualBatchConflict); assertEquals(batch.commandId, conflict.manualBatch!!.commandId)
            model.confirmManualBatch(batch); model.state.first { !it.busy }
            assertEquals(1L, graph.projects.open(project.ref).revision)
            assertEquals(TextItemStatus.NONE, graph.projects.item(project.ref, ItemId(1))!!.item.status)
            model.dismissManualBatch(batch); assertNull(model.state.value.manualBatch)
            assertNull(saved.get<ArrayList<String>>("manual_batch"))
        }
    }

    @Test fun lateConfirmationAndDismissalFromAnOldPreviewCannotAffectANewerBatch() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("late-batch"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            model.selectItems(true); model.selectPageItems(); model.prepareManualBatch(TextBatchChange.EXCLUDE)
            val first = model.state.first { !it.busy }.manualBatch!!
            model.dismissManualBatch(first); model.prepareManualBatch(TextBatchChange.MARK_PROCESSED)
            val next = model.state.first { !it.busy }.manualBatch!!
            model.confirmManualBatch(first); model.state.first { !it.busy }; model.dismissManualBatch(first)
            assertEquals(next.commandId, model.state.value.manualBatch!!.commandId)
            assertEquals(0L, graph.projects.open(project.ref).revision)
            model.confirmManualBatch(next); model.state.first { !it.busy }
            assertEquals(2L, graph.projects.open(project.ref).revision)
        }
    }

    @Test fun proofreadingQueryKeepsEditedMembersAndSupportsExplicitRequeryPagingAndClear() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("create-query"), "fixture", RelativePath("source.txt"),
                (1..85).joinToString("\n") { if (it % 2 == 1) "needle-$it" else "other-$it" }.toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, graph.projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val query = TextItemQuery(TextItemStatus.NONE, "needle", TextSearchField.SOURCE)
            model.queryProofreading(query)
            val filtered = model.state.first { !it.busy }
            assertEquals(43, filtered.matchingItems); assertTrue(filtered.hasNext); assertFalse(filtered.hasPrevious)
            val members = filtered.items.map { it.id }
            model.edit(members.first()); model.state.first { !it.busy }
            model.editText("saved translation"); model.saveEditor()
            val edited = model.state.first { !it.busy }
            assertNull(edited.editor); assertEquals(members, edited.items.map { it.id })
            assertEquals(TextItemStatus.PROCESSED, edited.items.first().status)
            assertEquals(TextItemStatistics(84, 1, 0, 84), edited.itemStatistics); assertEquals(42, edited.matchingItems)
            model.simulatePage(); model.state.first { !it.busy }
            assertNull(graph.backgroundTranslations.state.value) // A filtered window never widens the simulation scope.
            model.queryProofreading(query)
            val reselected = model.state.first { !it.busy }
            assertEquals(ItemId(3), reselected.items.first().id); assertTrue(reselected.hasNext)
            model.nextPage()
            val next = model.state.first { !it.busy }
            assertEquals(listOf(ItemId(83), ItemId(85)), next.items.map { it.id })
            assertTrue(next.hasPrevious); assertFalse(next.hasNext)
            model.previousPage()
            val previous = model.state.first { !it.busy }
            assertEquals(reselected.items.map { it.id }, previous.items.map { it.id }); assertFalse(previous.hasPrevious)
            model.queryProofreading(TextItemQuery(text = "no matches"))
            val empty = model.state.first { !it.busy }
            assertTrue(empty.items.isEmpty()); assertEquals(0, empty.matchingItems)
            assertFalse(empty.hasPrevious); assertFalse(empty.hasNext)
            model.queryProofreading(TextItemQuery())
            val cleared = model.state.first { !it.busy }
            assertEquals(ItemId(1), cleared.items.first().id); assertEquals(85, cleared.matchingItems)
            assertEquals(TextItemStatistics(84, 1, 0, 84), cleared.itemStatistics)
            assertFalse(cleared.hasPrevious); assertNull(graph.runtime.snapshot().activeRun)
        }
    }

    @Test fun committedEditSurvivesWindowRefreshFailureWithoutShowingOldCountsAsCurrent() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("query-refresh"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray())
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readItemWindow(ref: LocalProjectRef, query: TextItemQuery, window: TextItemWindow): TextProjectPage {
                    throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val (_, model) = model(saved, projects, graph.backgroundTranslations)
            model.state.first { !it.busy }
            val query = TextItemQuery(status = TextItemStatus.NONE)
            model.queryProofreading(query); model.state.first { !it.busy }
            model.edit(ItemId(1)); model.state.first { !it.busy }
            model.editText("saved"); model.saveEditor()
            val failedRefresh = model.state.first { !it.busy }
            assertNull(failedRefresh.editor); assertEquals(R.string.native_io_error, failedRefresh.error)
            assertEquals(listOf(ItemId(1), ItemId(2)), failedRefresh.items.map { it.id })
            assertEquals("saved", failedRefresh.items.first().translation)
            assertNull(failedRefresh.itemStatistics); assertNull(failedRefresh.matchingItems)
            assertEquals("saved", graph.projects.item(project.ref, ItemId(1))!!.item.translation)
            model.queryProofreading(query)
            val refreshed = model.state.first { !it.busy }
            assertEquals(listOf(ItemId(2)), refreshed.items.map { it.id })
            assertEquals(1, refreshed.matchingItems); assertEquals(TextItemStatistics(1, 1, 0, 1), refreshed.itemStatistics)
        }
    }

    @Test fun queryFailureRetainsTheWindowAndSavedFiltersAreBoundToTheFullProjectIdentity() = runBlocking {
        withTimeout(10_000) {
            val first = graph.projects.createTxt(CommandId("first-query"), "first", RelativePath("first.txt"), "needle\nother".toByteArray())
            val second = graph.projects.createTxt(CommandId("second-query"), "second", RelativePath("second.txt"), "another".toByteArray())
            val rejectQueries = AtomicBoolean(false)
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun queryPage(ref: LocalProjectRef, query: TextItemQuery, afterId: Long, limit: Int): TextProjectPage {
                    if (rejectQueries.get()) throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                    return graph.projects.queryPage(ref, query, afterId, limit)
                }
            }
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(first.ref.projectId.value,
                first.ref.instanceId.value, first.ref.generation.value.toString())))
            val (owner, original) = model(saved, projects, graph.backgroundTranslations)
            original.state.first { !it.busy }
            val query = TextItemQuery(text = "needle")
            original.queryProofreading(query)
            val before = original.state.first { !it.busy }
            rejectQueries.set(true)
            original.queryProofreading(TextItemQuery(text = "other"))
            val failed = original.state.first { !it.busy }
            assertEquals(R.string.native_io_error, failed.error)
            assertEquals(query, failed.proofreadingQuery); assertEquals(before.items.map { it.id }, failed.items.map { it.id })
            rejectQueries.set(false); owner.clear()
            val (_, recreated) = model(saved, projects, graph.backgroundTranslations)
            val restored = recreated.state.first { !it.busy }
            assertEquals(query, restored.proofreadingQuery); assertEquals(1, restored.matchingItems)
            recreated.open(second.ref)
            val switched = recreated.state.first { !it.busy }
            assertEquals(TextItemQuery(), switched.proofreadingQuery)
            assertEquals(second.ref, switched.project!!.ref); assertEquals(1, switched.matchingItems)
            assertEquals("another", switched.items.single().source)
        }
    }

    @Test fun recreatedViewModelsAttachAcrossABatchCommitAndRecoverDirectoryAfterSettlement() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"),
                (1..61).joinToString("\n") { "source-$it" }.toByteArray())
            val enteredFirst = CompletableDeferred<Unit>(); val enteredSecond = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>(); val releaseSecond = CompletableDeferred<Unit>()
            val calls = AtomicInteger()
            val local = LocalSimulationModel()
            val backend = object : TranslationModelBackend {
                override val configuration = local.configuration
                override suspend fun complete(request: TranslationModelRequest): TranslationModelResponse {
                    when (calls.incrementAndGet()) {
                        1 -> { enteredFirst.complete(Unit); releaseFirst.await() }
                        2 -> { enteredSecond.complete(Unit); releaseSecond.await() }
                        else -> error("Reattachment must not replay a request")
                    }
                    return local.complete(request)
                }
            }
            val translations = TextTranslationService(graph.runtime, graph.projects, graph.projects,
                NativeModelGateway(graph.runtime, backend), graph.runs)
            val runner = ForegroundTranslationRunner(graph.runtime, graph.runs, CoroutineScope(owner + Dispatchers.Default), translations)
            val handle = runner.prepare(request(project, 40)); runner.foregroundStarted(handle.token)
            enteredFirst.await()
            val recoveries = AtomicInteger()
            var firstRead = true
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readPage(ref: LocalProjectRef, afterId: Long, limit: Int): TextProjectPage {
                    if (firstRead) {
                        firstRead = false
                        val old = graph.projects.open(ref)
                        releaseFirst.complete(Unit); enteredSecond.await()
                        assertTrue(graph.projects.open(ref).revision > old.revision)
                    }
                    return graph.projects.readPage(ref, afterId, limit)
                }
                override suspend fun items(ref: LocalProjectRef, revision: Long, afterId: Long, limit: Int): TextItemPage =
                    error("UI refresh must use one consistent page read")
                override suspend fun recoverProjects(): ProjectRecoveryReport {
                    recoveries.incrementAndGet()
                    assertNull(graph.runtime.snapshot().activeRun)
                    return graph.projects.recoverProjects()
                }
            }
            fun remembered() = SavedStateHandle(mapOf("native_simulation_token" to handle.token,
                "native_simulation_cursors" to arrayListOf(0L, 40L), "native_recovery_problems" to 2))
            val (firstStore, firstModel) = model(remembered(), projects, runner)
            val first = firstModel.state.first { it.page == NativePage.PROOFREADING && it.items.size == 21 &&
                it.translationProgress?.committedItems == 20 }
            assertTrue(first.busy); assertEquals(project.ref, first.project!!.ref)
            assertEquals(20L, first.project.revision); assertTrue(first.hasPrevious); assertFalse(first.hasNext)
            assertEquals(2, first.recoveryProblems); assertEquals(0, recoveries.get()); assertNull(first.error)
            firstStore.clear(); firstModel.viewModelScope.coroutineContext.job.join()
            assertEquals(ForegroundTranslationPhase.RUNNING, runner.state.value!!.phase)
            assertNotNull(graph.runtime.snapshot().activeRun)

            val saved = remembered()
            val (_, secondModel) = model(saved, projects, runner)
            val second = secondModel.state.first { it.items.size == 21 && it.translationProgress?.committedItems == 20 }
            assertTrue(second.busy); assertTrue(second.hasPrevious); assertEquals(2, second.recoveryProblems)
            assertEquals(0, recoveries.get())
            releaseSecond.complete(Unit)
            val done = secondModel.state.first { !it.busy }
            assertEquals(21, done.translationProgress!!.committedItems)
            assertTrue(done.items.all { it.status == TextItemStatus.PROCESSED })
            assertEquals(21L, done.project!!.revision); assertEquals(0, done.recoveryProblems)
            assertEquals(1, recoveries.get()); assertEquals(2, calls.get())
            assertEquals(1, graph.runStore.recent(project.ref).size)
            assertEquals(0, saved.get<Int>("native_recovery_problems"))
            assertNull(saved.get<String>("native_simulation_token")); assertNull(graph.runtime.snapshot().selectedChat)
        }
    }

    @Test fun failedPageReadsStillObserveAndStopTheRunAndFailedRecoveryRetainsWarnings() = runBlocking {
        withTimeout(10_000) {
            val project = graph.projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "source".toByteArray())
            val entered = CompletableDeferred<Unit>()
            val runner = ForegroundTranslationRunner(graph.runtime, graph.runs, CoroutineScope(owner + Dispatchers.Default),
                translate = { _, _, _, emit ->
                    emit(TranslationProgress(0, 0, 0, unknownRequestUsage()))
                    entered.complete(Unit); awaitCancellation()
                })
            val handle = runner.prepare(request(project)); runner.foregroundStarted(handle.token); entered.await()
            val rejectReads = AtomicBoolean(true)
            val recoveries = AtomicInteger()
            val projects = object : TextProjectPort by graph.projects {
                override suspend fun readPage(ref: LocalProjectRef, afterId: Long, limit: Int): TextProjectPage {
                    if (rejectReads.get()) throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                    return graph.projects.readPage(ref, afterId, limit)
                }
                override suspend fun recoverProjects(): ProjectRecoveryReport {
                    recoveries.incrementAndGet()
                    throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                }
            }
            val saved = SavedStateHandle(mapOf("native_recovery_problems" to 3))
            val (_, model) = model(saved, projects, runner)
            val attached = model.state.first { it.error == R.string.native_background_refresh_failed && it.translationProgress != null }
            assertTrue(attached.busy); assertNull(attached.project)
            assertEquals(3, attached.recoveryProblems); assertEquals(0, recoveries.get())
            rejectReads.set(false)
            model.cancelAction() // No project summary is loaded; ownership still comes from the handle.
            val done = model.state.first { !it.busy }
            assertEquals(ForegroundTranslationPhase.CANCELLED, runner.state.value!!.phase)
            assertEquals(project.ref, done.project!!.ref); assertEquals(3, done.recoveryProblems)
            assertEquals(1, recoveries.get()); assertEquals(true, saved.get<Boolean>("native_recovery_pending"))
            assertEquals(R.string.native_io_error, done.error)
            assertEquals(StoredRunState.CANCELLED, graph.runStore.recent(project.ref).single().state)
            assertNull(graph.runtime.snapshot().activeRun)
        }
    }
}
