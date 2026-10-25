package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.*
import java.io.File
import java.math.BigInteger
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
class NativeTranslationTaskStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var tasks: NativeTranslationTaskStore
    private lateinit var ledger: FakeLedger
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        File(context.filesDir, "native_translation_retries").deleteRecursively()
        runtime = RuntimeCoordinator(); projects = NativeTextProjectStore(context, runtime)
        ledger = FakeLedger()
        tasks = NativeTranslationTaskStore(context, projects, runtime, DefaultTranslationRetryRecoveryPolicy, ledger)
    }
    @After fun cleanup() = runBlocking { tasks.close(); projects.close(); runtime.close() }
    private suspend fun spec(): TranslationTaskSpec {
        val project = projects.createTxt(CommandId("create"), "test", RelativePath("test.txt"), "one\ntwo\nthree".toByteArray())
        return TranslationTaskSpec(project.ref, RunIdentity(project.ref.projectId, null, RunId("run"), CommandId("command")),
            ModelConfigurationRef("fixture", 1), 0, 0, 40, true, RemoteTranslationOptions("en", startMode = TranslationStartMode.RESET))
    }
    private fun begin(spec: TranslationTaskSpec) = runtime.beginRunSelectingChat(spec.run, RuntimeOwner.TRANSLATION,
        ChatIdentity(spec.ref.projectId, ChatId("translation-workspace")))

    @Test fun explicitTerminationRetainsUnknownRequestAndDecisionAcrossReopen() = runBlocking {
        val original = spec()
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val item = projects.items(original.ref, 0, 0, 40).items.first()
            val attempt = RequestAttemptId("termination-uncertain")
            tasks.requesting(lease, 0, attempt, listOf(item.id), 1)
            audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                listOf(TranslationInput(item.id, item.source, item.fingerprint)), original.options), "b".repeat(64))
            tasks.failed(lease)
            runtime.finishRun(lease)
            val review = tasks.review(original.ref, original.run)
            assertEquals(1, review.unresolvedRequests)
            tasks.terminate(review, CommandId("terminate"))
            tasks.close()
            tasks = NativeTranslationTaskStore(context, projects, runtime, DefaultTranslationRetryRecoveryPolicy)
            tasks.terminate(review, CommandId("terminate")) // lost-response replay returns its durable receipt
            val after = tasks.review(original.ref, original.run)
            assertEquals("CANCELLED", after.stage)
            assertEquals(review.remaining, after.remaining)
            assertEquals(1, after.unresolvedRequests)
            assertEquals(item.fingerprint, projects.item(original.ref, item.id)!!.item.fingerprint)
            try { tasks.resume(original.ref, original.run); fail("Resumed terminated task") }
            catch (_: IllegalArgumentException) { }
            val db = NativeAppDatabase.open(context)
            try { withContext(Dispatchers.IO) {
                assertEquals("DISPATCHED", db.translationRequests().find(attempt.value)!!.state)
                val receipt = db.translationTaskDecisions().find("terminate")!!
                assertEquals("NEEDS_REVIEW", receipt.previousStage)
                assertEquals(review.fingerprint.sha256, receipt.fingerprint)
            } } finally { db.close() }
        } finally { audit.close() }
    }

    @Test fun staleOrForgedReviewCannotTerminateAndActiveTaskRejectsReview() = runBlocking {
        val original = spec()
        val lease = begin(original); tasks.open(lease, original)
        try { tasks.review(original.ref, original.run); fail("Reviewed active execution") }
        catch (_: RuntimeUnavailable) { }
        runtime.finishRun(lease)
        val review = tasks.review(original.ref, original.run)
        try { tasks.terminate(review.copy(requests = review.requests + 1), CommandId("forged")); fail("Accepted changed evidence") }
        catch (_: IllegalArgumentException) { }
        tasks.terminate(review, CommandId("first"))
        try { tasks.terminate(review, CommandId("stale")); fail("Accepted stale review") }
        catch (_: IllegalArgumentException) { }
        val db = NativeAppDatabase.open(context)
        try { withContext(Dispatchers.IO) {
            assertNull(db.translationTaskDecisions().find("forged"))
            assertNull(db.translationTaskDecisions().find("stale"))
        } } finally { db.close() }
    }

    @Test fun explicitErrorRetryFreezesOnlyChosenMembersAndKeepsOriginalFailure() = runBlocking {
        val original = spec()
        val lease = begin(original); tasks.open(lease, original)
        val first = projects.items(original.ref, 0, 0, 40).items.first()
        val edit = ManualTextEdit(CommandId("mark-error"), original.ref, first.id, 0,
            first.fingerprint, "", TextItemStatus.ERROR)
        tasks.committing(lease, 0, listOf(edit), failures = true)
        val revision = projects.markTranslationFailures(lease, TranslationFailureBatch(original.ref, 0, listOf(edit)))
        tasks.committed(lease, revision, 0)
        tasks.failed(lease)
        runtime.finishRun(lease)
        val review = tasks.review(original.ref, original.run)
        val page = tasks.members(original.ref, original.run)
        val chosen = page.members.filter { it.status == TextItemStatus.ERROR }
        assertEquals(listOf(first.id), chosen.map { it.id })
        val next = tasks.retryErrors(review, page.revision, chosen, CommandId("retry-selected"))
        assertNotEquals(original.run, next.run)
        assertEquals(TranslationStartMode.RESET, next.options.startMode)
        assertEquals(next, tasks.retryErrors(review, page.revision, chosen, CommandId("retry-selected")))
        assertEquals("FAILED", tasks.review(original.ref, original.run).stage)
        try {
            tasks.retryErrors(review, page.revision, page.members.filter { it.status != TextItemStatus.ERROR }, CommandId("wrong-members"))
            fail("Accepted non-error targets")
        } catch (_: IllegalArgumentException) { }
        val nextLease = begin(next)
        try {
            val progress = tasks.open(nextLease, next, resuming = true)
            assertEquals(0, progress.requests)
            val items = projects.items(next.ref, next.revision, 0, 40).items
            assertEquals(setOf(first.id), tasks.remaining(nextLease, next.ref, items))
        } finally { runtime.finishRun(nextLease) }
    }

    @Test fun outcomeEvidenceComesFromTargetsReceiptsAndLedgerWithoutSyntheticActualUsage() = runBlocking {
        val original = spec()
        val lease = begin(original)
        try {
            tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            assertEquals(3, items.size)
            var evidence = tasks.outcome(original.ref, original.run, emptySet())
            assertEquals("READY", evidence.stage)
            assertEquals(3, evidence.targetItems)
            assertEquals(0, evidence.committedItems)
            assertEquals(0, evidence.skippedItems)
            assertEquals(0, evidence.receiptedItems)
            assertEquals(0, evidence.unresolvedDispatches)
            assertEquals(UsageSource.UNKNOWN, evidence.usage)

            // An explicit skip names real unfinished targets; an unknown member is never counted.
            evidence = tasks.outcome(original.ref, original.run, items.take(2).map { it.id }.toSet())
            assertEquals(2, evidence.skippedItems)
            try {
                tasks.outcome(original.ref, original.run, setOf(ItemId(9_999)))
                fail("Accepted a non-target skip")
            } catch (_: IllegalArgumentException) { }

            ledger.value = DurableUsageSummary(1, 1, 0,
                RequestUsageTotals(1, input = TokenUsageTotal(actual = BigInteger.TEN)))
            evidence = tasks.outcome(original.ref, original.run, emptySet())
            assertEquals(UsageSource.ACTUAL, evidence.usage)

            val first = items.first()
            val edit = ManualTextEdit(CommandId("outcome-commit"), original.ref, first.id, 0,
                first.fingerprint, "translated")
            tasks.committing(lease, 0, listOf(edit))
            val revision = projects.commitTranslation(lease, TranslationBatch(original.ref, 0, listOf(edit)))
            tasks.committed(lease, revision, 1)
            evidence = tasks.outcome(original.ref, original.run, items.drop(1).map { it.id }.toSet())
            assertEquals(3, evidence.targetItems)
            assertEquals(1, evidence.committedItems)
            assertEquals(1, evidence.receiptedItems)
            assertEquals(2, evidence.skippedItems)
            assertEquals(UsageSource.ACTUAL, evidence.usage)
        } finally { runtime.finishRun(lease) }
    }

    @Test fun unreceiptedTaskCannotCreateAnErrorRetry() = runBlocking {
        val original = spec()
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val item = projects.items(original.ref, 0, 0, 40).items.first()
            val edit = ManualTextEdit(CommandId("uncertain-error"), original.ref, item.id, 0,
                item.fingerprint, "", TextItemStatus.ERROR)
            // The item can be an error while another request remains uncertain; that is not retry authorization.
            val revision = projects.markTranslationFailures(lease, TranslationFailureBatch(original.ref, 0, listOf(edit)))
            val attempt = RequestAttemptId("retry-unresolved")
            audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                listOf(TranslationInput(item.id, item.source, item.fingerprint)), original.options), "c".repeat(64))
            tasks.failed(lease); runtime.finishRun(lease)
            val review = tasks.review(original.ref, original.run)
            val page = tasks.members(original.ref, original.run)
            try {
                tasks.retryErrors(review, revision, page.members.filter { it.status == TextItemStatus.ERROR }, CommandId("unsafe-retry"))
                fail("Retried unreceipted dispatch")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, tasks.recent(original.ref).size)
            assertEquals(1, tasks.review(original.ref, original.run).unresolvedRequests)
        } finally { audit.close() }
    }

    @Test fun keptErrorsAreDurableAndCannotBecomeANewRetryDecision() = runBlocking {
        val original = spec()
        val lease = begin(original); tasks.open(lease, original)
        val item = projects.items(original.ref, 0, 0, 40).items.first()
        val edit = ManualTextEdit(CommandId("keep-marker"), original.ref, item.id, 0,
            item.fingerprint, "", TextItemStatus.ERROR)
        tasks.committing(lease, 0, listOf(edit), failures = true)
        val revision = projects.markTranslationFailures(lease, TranslationFailureBatch(original.ref, 0, listOf(edit)))
        tasks.committed(lease, revision, 0); tasks.failed(lease); runtime.finishRun(lease)
        val review = tasks.review(original.ref, original.run)
        val members = tasks.members(original.ref, original.run).members.filter { it.status == TextItemStatus.ERROR }
        tasks.keepErrors(review, revision, members, CommandId("keep-errors"))
        tasks.close(); tasks = NativeTranslationTaskStore(context, projects, runtime, DefaultTranslationRetryRecoveryPolicy)
        tasks.keepErrors(review, revision, members, CommandId("keep-errors"))
        val after = tasks.members(original.ref, original.run)
        assertEquals("KEEP_ERROR", after.members.first { it.id == item.id }.decision)
        assertEquals("FAILED", tasks.review(original.ref, original.run).stage)
        assertEquals(revision, projects.open(original.ref).revision)
        try { tasks.retryErrors(review, revision, members, CommandId("reopen-kept")); fail("Reopened final decision") }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, tasks.recent(original.ref).size)
    }

    @Test fun terminatedTaskWithStagedOutputCannotBypassResumeThroughOpen() = runBlocking {
        val original = spec()
        val lease = begin(original); tasks.open(lease, original)
        val item = projects.items(original.ref, 0, 0, 40).items.first()
        tasks.stage(lease, listOf(TranslationTaskOutput(item.id, item.fingerprint, "saved output")))
        runtime.finishRun(lease)
        val review = tasks.review(original.ref, original.run)
        tasks.terminate(review, CommandId("terminate-staged"))
        val nextLease = begin(original)
        try {
            try { tasks.open(nextLease, original, resuming = true); fail("Reopened terminal task through recoverable output") }
            catch (_: IllegalArgumentException) { }
        } finally { runtime.finishRun(nextLease) }
        assertEquals("CANCELLED", tasks.review(original.ref, original.run).stage)
    }

    @Test fun terminalRunWithAnUnresolvedDispatchRetainsReviewStateAndOriginalAudit() = runBlocking {
        val original = spec(); val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val item = projects.items(original.ref, 0, 0, 40).items.first()
            val attempt = RequestAttemptId("uncertain-terminal")
            tasks.requesting(lease, 0, attempt, listOf(item.id), 1)
            audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                listOf(TranslationInput(item.id, item.source, item.fingerprint)), original.options), "a".repeat(64))
            tasks.failed(lease); tasks.failed(lease)
            runtime.finishRun(lease)
            val task = tasks.recent(original.ref).single()
            assertEquals("NEEDS_REVIEW", task.stage)
            assertEquals(1, task.progress.requests); assertEquals(0, task.progress.committed)
            try { tasks.resume(original.ref, original.run); fail("Resumed uncertain terminal request") }
            catch (_: TranslationTaskNeedsReview) { }
            val db = NativeAppDatabase.open(context)
            try {
                withContext(Dispatchers.IO) {
                    assertEquals("DISPATCHED", db.translationRequests().find(attempt.value)!!.state)
                }
            } finally { db.close() }
        } finally { audit.close() }
    }

    @Test fun committedFailureMarkersAreReconciledWithoutBecomingSuccessfulTranslations() = runBlocking { failureMarkersRemainFailed(false) }
    @Test fun acknowledgedFailureMarkersCannotBeResumedAsASuccessfulTask() = runBlocking { failureMarkersRemainFailed(true) }

    private suspend fun failureMarkersRemainFailed(acknowledged: Boolean) {
        val original = spec()
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val attempt = RequestAttemptId("terminal-failure")
            val session = TranslationRetrySession.start(items.map {
                TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint)
            }, 0)
            session.begin(attempt)
            tasks.requesting(lease, 0, attempt, items.map { it.id }, 1)
            val receipt = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                items.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "a".repeat(64))
            audit.received(receipt, unknownRequestUsage(), ModelFailureKind.PARSING)
            session.failed(ModelFailureKind.PARSING, 0)
            retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            val edits = items.mapIndexed { index, item -> ManualTextEdit(CommandId("failure-$index"), original.ref, item.id,
                index.toLong(), item.fingerprint, item.translation, TextItemStatus.ERROR) }
            tasks.committing(lease, 0, edits, failures = true)
            val revision = projects.markTranslationFailures(lease, TranslationFailureBatch(original.ref, 0, edits))
            if (acknowledged) tasks.committed(lease, revision, 0)
            runtime.finishRun(lease)
            try { tasks.resume(original.ref, original.run); fail("Failure markers became successful task") }
            catch (_: TranslationTaskNeedsReview) { }
            val task = tasks.recent(original.ref).single()
            assertEquals("FAILED", task.stage)
            assertEquals(0, task.progress.committed); assertEquals(0, task.remaining)
            assertEquals(revision, task.progress.revision)
            assertEquals(List(items.size) { TextItemStatus.ERROR }, projects.readPage(original.ref).items.map { it.status })
            assertEquals(items.map { it.translation }, projects.readPage(original.ref).items.map { it.translation })
            val inspection = begin(original)
            try {
                val saved = requireNotNull(retryStore.read(inspection, original.ref, original.configuration))
                assertEquals(ModelFailureKind.PARSING, saved.terminalFailure)
                assertEquals(attempt, saved.attempt)
            } finally { runtime.finishRun(inspection) }
        } finally { audit.close() }
    }

    @Test fun rawChildResponseRecoversDerivedItemsBeforeRequestingSibling() = runBlocking { partiallyCommittedChildResumes(0, false) }
    @Test fun partiallyCommittedDerivedChildReconcilesReceiptBeforeDrainingTheRest() = runBlocking { partiallyCommittedChildResumes(20, false) }
    @Test fun partiallyAcknowledgedDerivedChildDrainsTheRestBeforeRequestingSibling() = runBlocking { partiallyCommittedChildResumes(20, true) }

    private suspend fun partiallyCommittedChildResumes(committedCount: Int, acknowledged: Boolean) {
        val source = List(15) { "alpha" } + List(15) { "beta" } + "gamma"
        val project = projects.createTxt(CommandId("partial-create"), "partial", RelativePath("partial.txt"), source.joinToString("\n").toByteArray())
        val original = TranslationTaskSpec(project.ref, RunIdentity(project.ref.projectId, null, RunId("partial-run"), CommandId("partial-command")),
            ModelConfigurationRef("fixture", 1), 0, 0, 40, true,
            RemoteTranslationOptions("en", precedingLines = 0, additionalRequestsPerBatch = 2, deduplicateSameFile = true))
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        val journal = NativeRunStore(context, projects, runtime)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val primary = listOf(items[0], items[15], items[30])
            val session = TranslationRetrySession.start(primary.map {
                TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint)
            }, 2)
            val parent = RequestAttemptId("partial-parent")
            session.begin(parent); tasks.requesting(lease, 0, parent, items.map { it.id }, 1)
            val failed = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, parent, original.configuration,
                primary.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "a".repeat(64))
            audit.received(failed, unknownRequestUsage(), ModelFailureKind.PARSING)
            session.failed(ModelFailureKind.PARSING, 0)
            val child = RequestAttemptId("partial-child")
            session.begin(child); retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            tasks.requesting(lease, 0, child, items.take(30).map { it.id }, 2)
            val receipt = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, child, original.configuration,
                primary.take(2).map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "b".repeat(64))
            audit.receivedResponse(receipt, TranslationModelResponse(primary.take(2).map { TranslationOutput(it.id, "cached-${it.source}") },
                unknownRequestUsage(), ModelStopReason.COMPLETE))
            if (committedCount > 0) {
                tasks.stage(lease, items.take(30).map { TranslationTaskOutput(it.id, it.fingerprint, "cached-${it.source}") })
                val edits = tasks.staged(lease)
                assertEquals(committedCount, edits.size)
                tasks.committing(lease, 0, edits)
                val revision = projects.commitTranslation(lease, TranslationBatch(original.ref, 0, edits))
                if (acknowledged) tasks.committed(lease, revision, committedCount)
            }
            runtime.finishRun(lease)
            val confirmed = tasks.resume(original.ref, original.run)
            val requested = mutableListOf<ItemId>()
            val gateway = object : TranslationModelGateway {
                override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                    // All cached active outputs must be committed before another paid operation.
                    assertEquals(30, projects.readPage(original.ref).items.count { it.status == TextItemStatus.PROCESSED })
                    requested.addAll(request.inputs.map { it.id })
                    val recorded = audit.dispatched(lease, request, "c".repeat(64))
                    val response = TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "sibling saved") },
                        unknownRequestUsage(), ModelStopReason.COMPLETE)
                    audit.receivedResponse(recorded, response); return response
                }
            }
            val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks,
                retryCheckpoints = retryStore)
            val result = service.resume(confirmed)
            assertEquals(listOf(items.last().id), requested)
            assertEquals(31, result.committedItems); assertEquals(3, result.requests)
            assertEquals(items.take(30).map { "cached-${it.source}" } + "sibling saved",
                projects.readPage(original.ref).items.map { it.translation })
            assertEquals("COMPLETE", tasks.recent(original.ref).single().stage)
        } finally { audit.close(); journal.close() }
    }

    @Test fun dedupRecoveryRejectsAnOutstandingTargetThatHasNoProvenPrimary() = runBlocking {
        val original = spec().let { it.copy(options = it.options.copy(additionalRequestsPerBatch = 2, deduplicateSameFile = true)) }
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val primary = items.take(2)
            val session = TranslationRetrySession.start(primary.map {
                TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint)
            }, 2)
            val attempt = RequestAttemptId("bad-derived-parent")
            session.begin(attempt); tasks.requesting(lease, 0, attempt, items.map { it.id }, 1)
            val receipt = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt,
                original.configuration, primary.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "f".repeat(64))
            audit.received(receipt, unknownRequestUsage(), ModelFailureKind.PARSING)
            session.failed(ModelFailureKind.PARSING, 0)
            retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            runtime.finishRun(lease)
            try { tasks.resume(original.ref, original.run); fail("Invented a derived target mapping") }
            catch (_: TranslationTaskNeedsReview) { }
            assertEquals(0L, projects.open(original.ref).revision)
        } finally { audit.close() }
    }

    @Test fun deduplicatedFailedParentResumesOriginalPrimaryQueueAndAllDerivedItems() = runBlocking { deduplicatedQueueResumes(false) }
    @Test fun deduplicatedChildCommitKeepsTheOriginalSiblingAndItsDerivedItem() = runBlocking { deduplicatedQueueResumes(true) }

    private suspend fun deduplicatedQueueResumes(interruptChild: Boolean) {
        val project = projects.createTxt(CommandId("dedup-create"), "dedup", RelativePath("dedup.txt"),
            "one\none\ntwo\ntwo\nthree\nthree".toByteArray())
        val original = TranslationTaskSpec(project.ref, RunIdentity(project.ref.projectId, null, RunId("dedup-run"), CommandId("dedup-command")),
            ModelConfigurationRef("fixture", 1), 0, 0, 40, true,
            RemoteTranslationOptions("en", precedingLines = 0, additionalRequestsPerBatch = 2, deduplicateSameFile = true))
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        val journal = NativeRunStore(context, projects, runtime)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val primary = listOf(items[0], items[2], items[4])
            val session = TranslationRetrySession.start(primary.map {
                TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint)
            }, 2)
            val parent = RequestAttemptId("dedup-parent")
            session.begin(parent); tasks.requesting(lease, 0, parent, items.map { it.id }, 1)
            val receipt = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, parent, original.configuration,
                primary.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "a".repeat(64))
            audit.received(receipt, unknownRequestUsage(), ModelFailureKind.PARSING)
            session.failed(ModelFailureKind.PARSING, 0)
            retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            runtime.finishRun(lease)
            val requested = mutableListOf<List<ItemId>>()
            val gateway = object : TranslationModelGateway {
                override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                    requested.add(request.inputs.map { it.id })
                    val recorded = audit.dispatched(lease, request, "b".repeat(64))
                    val response = TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "translated-${it.source}") },
                        unknownRequestUsage(), ModelStopReason.COMPLETE)
                    audit.receivedResponse(recorded, response); return response
                }
            }
            val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks,
                retryCheckpoints = retryStore)
            var confirmed = tasks.resume(original.ref, original.run)
            if (interruptChild) {
                try {
                    service.resume(confirmed) { if (it.committedItems == 4) throw kotlinx.coroutines.CancellationException("child committed") }
                    fail("Missed interruption")
                } catch (_: kotlinx.coroutines.CancellationException) { }
                confirmed = tasks.resume(original.ref, original.run)
            }
            val result = service.resume(confirmed)
            assertEquals(listOf(primary.take(2).map { it.id }, primary.drop(2).map { it.id }), requested)
            assertEquals(6, result.committedItems); assertEquals(3, result.requests)
            assertEquals(items.map { "translated-${it.source}" }, projects.readPage(original.ref).items.map { it.translation })
            assertEquals("COMPLETE", tasks.recent(original.ref).single().stage)
        } finally { audit.close(); journal.close() }
    }

    @Test fun childCommitBeforeTaskAcknowledgementKeepsSiblingQueue() = runBlocking { committedChildResumes(false) }
    @Test fun childCommitBeforeRetryAcknowledgementKeepsSiblingQueue() = runBlocking { committedChildResumes(true) }

    private suspend fun committedChildResumes(taskAcknowledged: Boolean) {
        val original = spec().let { it.copy(options = it.options.copy(precedingLines = 0, additionalRequestsPerBatch = 2)) }
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        val journal = NativeRunStore(context, projects, runtime)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val snapshots = items.map { TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint) }
            val session = TranslationRetrySession.start(snapshots, 2)
            val parent = RequestAttemptId("parent")
            session.begin(parent)
            tasks.requesting(lease, 0, parent, items.map { it.id }, 1)
            val parentReceipt = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, parent,
                original.configuration, items.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "a".repeat(64))
            audit.received(parentReceipt, unknownRequestUsage(), ModelFailureKind.PARSING)
            session.failed(ModelFailureKind.PARSING, 0)
            val childItems = items.take(2); val child = RequestAttemptId("first-child")
            session.begin(child); retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            tasks.requesting(lease, 0, child, childItems.map { it.id }, 2)
            val childReceipt = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, child,
                original.configuration, childItems.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "b".repeat(64))
            audit.receivedResponse(childReceipt, TranslationModelResponse(childItems.map { TranslationOutput(it.id, "first saved") },
                unknownRequestUsage(), ModelStopReason.COMPLETE))
            tasks.stage(lease, childItems.map { TranslationTaskOutput(it.id, it.fingerprint, "first saved") })
            val edits = tasks.staged(lease)
            tasks.committing(lease, 0, edits)
            val revision = projects.commitTranslation(lease, TranslationBatch(original.ref, 0, edits))
            if (taskAcknowledged) tasks.committed(lease, revision, childItems.size)
            // Die before retrySession.succeeded() and its file checkpoint write.
            runtime.finishRun(lease)
            val confirmed = tasks.resume(original.ref, original.run)
            assertEquals(revision, confirmed.revision)
            assertEquals(confirmed, tasks.resume(original.ref, original.run))
            val requested = mutableListOf<ItemId>()
            val gateway = object : TranslationModelGateway {
                override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                    requested.addAll(request.inputs.map { it.id })
                    val receipt = audit.dispatched(lease, request, "c".repeat(64))
                    val response = TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "sibling saved") },
                        unknownRequestUsage(), ModelStopReason.COMPLETE)
                    audit.receivedResponse(receipt, response); return response
                }
            }
            val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks,
                retryCheckpoints = retryStore)
            val result = service.resume(confirmed)
            assertEquals(items.drop(2).map { it.id }, requested)
            assertEquals(3, result.requests); assertEquals(3, result.committedItems)
            assertEquals(listOf("first saved", "first saved", "sibling saved"), projects.readPage(original.ref).items.map { it.translation })
            assertEquals("COMPLETE", tasks.recent(original.ref).single().stage)
        } finally { audit.close(); journal.close() }
    }

    @Test fun receivedFailureBeforeDecisionIsRecoveredOnceAndNeverReplenishesBudget() = runBlocking {
        val base = spec()
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        try {
            for (budget in listOf(0, 2)) {
                val original = base.copy(run = RunIdentity(base.ref.projectId, null, RunId("decision-$budget"),
                    CommandId("decision-$budget")), options = base.options.copy(additionalRequestsPerBatch = budget))
                val lease = begin(original); tasks.open(lease, original)
                val items = projects.items(original.ref, 0, 0, 40).items
                val attempt = RequestAttemptId("failed-before-decision-$budget")
                val session = TranslationRetrySession.start(items.map {
                    TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint)
                }, budget)
                session.begin(attempt); retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
                tasks.requesting(lease, 0, attempt, items.map { it.id }, 1)
                val receipt = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt,
                    original.configuration, items.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "e".repeat(64))
                audit.received(receipt, unknownRequestUsage(), ModelFailureKind.PARSING)
                runtime.finishRun(lease)
                if (budget == 0) {
                    try { tasks.resume(original.ref, original.run); fail("Retried without allowance") }
                    catch (_: TranslationTaskNeedsReview) { }
                } else {
                    val confirmed = tasks.resume(original.ref, original.run)
                    assertEquals(confirmed, tasks.resume(original.ref, original.run))
                }
                val inspection = begin(original)
                try {
                    val saved = requireNotNull(retryStore.read(inspection, original.ref, original.configuration))
                    assertEquals(0, saved.remainingAdditional)
                    if (budget == 0) {
                        assertEquals(ModelFailureKind.PARSING, saved.terminalFailure)
                        assertEquals(attempt, saved.attempt)
                    } else {
                        assertNull(saved.active); assertNull(saved.terminalFailure)
                        assertEquals(listOf(items.take(2).map { it.id }, items.drop(2).map { it.id }), saved.pending.map { it.ids })
                    }
                } finally { runtime.finishRun(inspection) }
            }
        } finally { audit.close() }
    }

    @Test fun unrecordedActiveRetryReturnsToItsOriginalQueueWithoutRestoringBudget() = runBlocking {
        val base = spec()
        for (requestCheckpoint in listOf(false, true)) {
            val original = base.copy(run = RunIdentity(base.ref.projectId, null, RunId("unsent-$requestCheckpoint"),
                CommandId("unsent-$requestCheckpoint")), options = base.options.copy(additionalRequestsPerBatch = 2))
            val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
            var lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val snapshots = items.map { TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint) }
            val session = TranslationRetrySession.start(snapshots, 2)
            session.begin(RequestAttemptId("prior-$requestCheckpoint")); session.failed(ModelFailureKind.NETWORK, 0)
            val unsent = RequestAttemptId("unsent-$requestCheckpoint")
            session.begin(unsent)
            retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            if (requestCheckpoint) tasks.requesting(lease, 0, unsent, items.map { it.id }, 1)
            runtime.finishRun(lease)
            val confirmed = tasks.resume(original.ref, original.run)
            assertEquals(confirmed, tasks.resume(original.ref, original.run))
            lease = begin(confirmed)
            try {
                val saved = requireNotNull(retryStore.read(lease, original.ref, original.configuration))
                assertNull(saved.active); assertNull(saved.attempt)
                assertEquals(1, saved.remainingAdditional)
                assertEquals(1, saved.pending.single().attempts)
                assertEquals(items.map { it.id }, saved.pending.single().ids)
                assertEquals("READY", tasks.recent(original.ref).first { it.run == original.run }.stage)
            } finally { runtime.finishRun(lease) }
        }
    }

    @Test fun activeRetryWithADispatchAuditIsNeverRequeuedAsUnsent() = runBlocking {
        val original = spec()
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val attempt = RequestAttemptId("paid-active")
            val session = TranslationRetrySession.start(items.map {
                TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint)
            }, 0)
            session.begin(attempt); retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            tasks.requesting(lease, 0, attempt, items.map { it.id }, 1)
            audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                items.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "d".repeat(64))
            runtime.finishRun(lease)
            try { tasks.resume(original.ref, original.run); fail("Requeued paid request") } catch (_: TranslationTaskNeedsReview) { }
            val inspection = begin(original)
            try { assertEquals(attempt, retryStore.read(inspection, original.ref, original.configuration)!!.attempt) }
            finally { runtime.finishRun(inspection) }
        } finally { audit.close() }
    }

    @Test fun auditedFailedParentResumesOriginalSplitChildrenWithoutReplayingParent() = runBlocking {
        val original = spec().let { it.copy(options = it.options.copy(precedingLines = 0, additionalRequestsPerBatch = 2)) }
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        val journal = NativeRunStore(context, projects, runtime)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val snapshots = items.map { TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint) }
            val session = TranslationRetrySession.start(snapshots, 2)
            val attempt = RequestAttemptId("failed-parent")
            session.begin(attempt)
            tasks.requesting(lease, 0, attempt, items.map { it.id }, 1)
            val parent = TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                items.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options)
            val handle = audit.dispatched(lease, parent, "a".repeat(64))
            audit.received(handle, unknownRequestUsage(), ModelFailureKind.PARSING)
            session.failed(ModelFailureKind.PARSING, 0)
            retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            runtime.finishRun(lease)
            val confirmed = tasks.resume(original.ref, original.run)
            val requested = mutableListOf<List<ItemId>>()
            val gateway = object : TranslationModelGateway {
                override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                    requested.add(request.inputs.map { it.id })
                    val receipt = audit.dispatched(lease, request, "b".repeat(64))
                    val response = TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "saved") },
                        unknownRequestUsage(), ModelStopReason.COMPLETE)
                    audit.receivedResponse(receipt, response)
                    return response
                }
            }
            val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks,
                retryCheckpoints = retryStore)
            val result = service.resume(confirmed)
            assertEquals(listOf(items.take(2).map { it.id }, items.drop(2).map { it.id }), requested)
            assertEquals(3, result.requests); assertEquals(items.size, result.committedItems)
            assertEquals("COMPLETE", tasks.recent(original.ref).single().stage)
            val inspection = begin(original)
            try {
                val saved = requireNotNull(retryStore.read(inspection, original.ref, original.configuration))
                assertEquals(0, saved.remainingAdditional); assertTrue(saved.pending.isEmpty()); assertNull(saved.active)
            } finally { runtime.finishRun(inspection) }
        } finally { audit.close(); journal.close() }
    }

    @Test fun savedSplitQueueCannotOverrideAnUnresolvedPaidParent() = runBlocking {
        val original = spec().let { it.copy(options = it.options.copy(additionalRequestsPerBatch = 2)) }
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            val attempt = RequestAttemptId("unresolved-parent")
            tasks.requesting(lease, 0, attempt, items.map { it.id }, 1)
            audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                items.map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options), "c".repeat(64))
            val session = TranslationRetrySession.start(items.map {
                TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint)
            }, 2)
            session.begin(attempt); session.failed(ModelFailureKind.PARSING, 0)
            retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
            runtime.finishRun(lease)
            try { tasks.resume(original.ref, original.run); fail("Replayed uncertain paid parent") }
            catch (_: TranslationTaskNeedsReview) { }
            assertEquals(0L, projects.open(original.ref).revision)
        } finally { audit.close() }
    }

    @Test fun persistedPendingUnitResumesWithoutFreshPlanningOrBudgetReplenishment() = runBlocking {
        val original = spec().let { it.copy(options = it.options.copy(precedingLines = 0, additionalRequestsPerBatch = 1)) }
        val retryStore = NativeTranslationRetryCheckpointStore(context, runtime)
        val lease = begin(original); tasks.open(lease, original)
        val items = projects.items(original.ref, 0, 0, 40).items
        val snapshots = items.map { TranslationItemSnapshot(it.id, it.assetId, it.row, it.source, it.status, it.fingerprint) }
        val session = TranslationRetrySession.start(snapshots, 1)
        session.begin(RequestAttemptId("prior-response")); session.failed(ModelFailureKind.NETWORK, 0)
        retryStore.write(lease, original.ref, original.configuration, session.checkpoint)
        runtime.finishRun(lease)
        val journal = NativeRunStore(context, projects, runtime); var calls = 0
        val failure = AgentModelFailure(ModelFailureKind.NETWORK)
        val gateway = object : TranslationModelGateway {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                calls++; assertEquals(items.map { it.id }, request.inputs.map { it.id }); throw failure
            }
        }
        try {
            val confirmed = tasks.resume(original.ref, original.run)
            val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks,
                retryCheckpoints = retryStore)
            try { service.resume(confirmed); fail("Replenished retry budget") } catch (error: AgentModelFailure) { assertSame(failure, error) }
            assertEquals(1, calls)
            val inspection = begin(original)
            try {
                val saved = requireNotNull(retryStore.read(inspection, original.ref, original.configuration))
                assertEquals(0, saved.remainingAdditional)
                assertEquals(ModelFailureKind.NETWORK, saved.terminalFailure)
            } finally { runtime.finishRun(inspection) }
        } finally { journal.close() }
    }

    @Test fun rejectedDispatchDoesNotWriteAFailedRequestCheckpointOrRetryIt() = runBlocking {
        val original = spec().let { it.copy(options = it.options.copy(additionalRequestsPerBatch = 5)) }
        val failure = AgentModelFailure(ModelFailureKind.AUTHENTICATION)
        var calls = 0
        val gateway = object : DispatchAwareTranslationGateway {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse = error("Bypassed dispatch hook")
            override suspend fun completePrepared(lease: RunLease, request: TranslationModelRequest,
                beforeDispatch: suspend () -> Unit): TranslationModelResponse {
                calls++; throw failure
            }
        }
        val journal = NativeRunStore(context, projects, runtime)
        try {
            runtime.selectChat(ChatIdentity(original.ref.projectId, ChatId("translation-workspace")))
            val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks)
            try {
                service.translateProject(original.ref, original.run, original.configuration, original.revision, options = original.options)
                fail("Accepted rejected dispatch")
            } catch (error: AgentModelFailure) { assertSame(failure, error) }
            assertEquals(1, calls)
            assertEquals(0, tasks.recent(original.ref).single().progress.requests)
            val run = journal.recent(original.ref).single()
            assertEquals(StoredRunState.FAILED, run.state)
            assertEquals(StoredRunStage.ACCEPTED, run.checkpoint.stage)
            assertEquals(0, run.checkpoint.requests)
        } finally { journal.close() }
    }

    @Test fun supplierFailureWhilePausingDoesNotBecomeASuccessfulPauseOrConsumeAnotherRequest() = runBlocking {
        val base = spec()
        val journal = NativeRunStore(context, projects, runtime)
        try {
            for (budget in listOf(0, 2)) {
                val original = base.copy(run = RunIdentity(base.ref.projectId, null, RunId("pause-failure-$budget"),
                    CommandId("pause-failure-$budget")), options = base.options.copy(additionalRequestsPerBatch = budget))
                var calls = 0
                val failure = AgentModelFailure(ModelFailureKind.NETWORK)
                val gateway = object : TranslationModelGateway {
                    override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                        calls++
                        assertTrue(runtime.requestPause(lease))
                        throw failure
                    }
                }
                runtime.selectChat(ChatIdentity(original.ref.projectId, ChatId("translation-workspace")))
                val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks)
                try {
                    service.translateProject(original.ref, original.run, original.configuration, original.revision,
                        options = original.options)
                    fail("Provider failure was lost")
                } catch (error: AgentModelFailure) { assertSame(failure, error) }
                assertEquals(1, calls)
                assertEquals(StoredRunState.FAILED, journal.recent(original.ref, 20).first { it.handle.run == original.run }.state)
                assertNull(runtime.snapshot().activeRun)
                assertEquals(0L, projects.open(original.ref).revision)
            }
        } finally { journal.close() }
    }

    @Test fun olderTasksRemainReachableWhenANewTaskIsInsertedBetweenPages() = runBlocking {
        val base = spec()
        suspend fun add(index: Int) {
            val spec = base.copy(run = RunIdentity(base.ref.projectId, null, RunId("run-$index"), CommandId("command-$index")))
            val lease = begin(spec)
            try { tasks.open(lease, spec) } finally { runtime.finishRun(lease) }
        }
        repeat(45) { add(it) }
        val first = tasks.page(base.ref)
        assertEquals(20, first.tasks.size); assertNotNull(first.nextCursor)
        assertEquals("run-44", first.tasks.first().run.runId.value)
        add(45)
        val second = tasks.page(base.ref, first.nextCursor)
        val third = tasks.page(base.ref, second.nextCursor)
        assertEquals(20, second.tasks.size); assertEquals(5, third.tasks.size)
        assertNull(third.nextCursor)
        val all = first.tasks + second.tasks + third.tasks
        assertEquals(45, all.map { it.run }.distinct().size)
        assertFalse(all.any { it.run.runId.value == "run-45" })
        assertEquals("run-45", tasks.page(base.ref).tasks.first().run.runId.value)
        assertTrue(tasks.page(base.ref.copy(generation = RecoveryGeneration(2))).tasks.isEmpty())
        assertTrue(tasks.page(base.ref.copy(instanceId = ProjectInstanceId("another"))).tasks.isEmpty())
        try { tasks.page(base.ref, 0); fail("Accepted invalid cursor") } catch (_: IllegalArgumentException) { }
    }

    @Test fun crashAfterCommitReconcilesOriginalCommandsAndResetNeverSelectsThemAgain() = runBlocking {
        val spec = spec(); val lease = begin(spec); val items = projects.items(spec.ref, 0, 0, 40).items
        assertTrue(items.size >= 2)
        tasks.open(lease, spec)
        val edit = ManualTextEdit(CommandId("edit"), spec.ref, items.first().id, 0, items.first().fingerprint, "saved")
        tasks.committing(lease, 0, listOf(edit))
        val revision = projects.commitTranslation(lease, TranslationBatch(spec.ref, 0, listOf(edit)))
        // Model a process death before the separate task acknowledgement.
        runtime.finishRun(lease); tasks.close(); tasks = NativeTranslationTaskStore(context, projects, runtime, DefaultTranslationRetryRecoveryPolicy)
        val resumed = tasks.resume(spec.ref, spec.run)
        assertEquals(revision, resumed.revision); assertEquals(spec.options, resumed.options)
        val next = begin(resumed)
        try {
            val progress = tasks.open(next, resumed, resuming = true)
            assertEquals(1, progress.committed); assertEquals(0L, progress.initialRevision)
            val selected = tasks.remaining(next, spec.ref, projects.items(spec.ref, revision, 0, 40).items)
            assertFalse(items.first().id in selected); assertEquals(items.size - 1, selected.size)
        } finally { runtime.finishRun(next) }
    }

    @Test fun noAuditProvesUnsentButDispatchedWithoutACommitNeverAutomaticallyRetries() = runBlocking {
        val spec = spec(); var lease = begin(spec); val item = projects.items(spec.ref, 0, 0, 40).items.first()
        tasks.open(lease, spec); tasks.requesting(lease, 0, RequestAttemptId("unsent"), listOf(item.id), 1)
        runtime.finishRun(lease)
        val resumed = tasks.resume(spec.ref, spec.run)
        lease = begin(resumed)
        tasks.open(lease, resumed, resuming = true)
        tasks.requesting(lease, 0, RequestAttemptId("dispatched"), listOf(item.id), 2)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            audit.dispatched(lease, TranslationModelRequest(spec.ref, spec.run, RequestAttemptId("dispatched"), spec.configuration,
                listOf(TranslationInput(item.id, item.source, item.fingerprint)), spec.options), "a".repeat(64))
        } finally { audit.close(); runtime.finishRun(lease) }
        try { tasks.resume(spec.ref, spec.run); fail("Replayed an uncertain request") } catch (_: TranslationTaskNeedsReview) { }
    }

    @Test fun externalChangesOrAlteredFrozenConfigurationRejectContinuation() = runBlocking {
        val spec = spec(); val lease = begin(spec); tasks.open(lease, spec); runtime.finishRun(lease)
        val wrong = spec.copy(configuration = ModelConfigurationRef("other", 1))
        val other = begin(wrong)
        try { tasks.open(other, wrong, resuming = true); fail("Changed original model") }
        catch (_: IllegalArgumentException) { } finally { runtime.finishRun(other) }
        val item = projects.items(spec.ref, 0, 0, 40).items.first()
        projects.edit(ManualTextEdit(CommandId("outside"), spec.ref, item.id, 0, item.fingerprint, "manual"))
        try { tasks.resume(spec.ref, spec.run); fail("Rebased an old task") }
        catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, error.reason) }
    }

    @Test fun resumedResetServiceSkipsCommittedTargetsAndKeepsTheOriginalRun() = runBlocking {
        val original = spec().let { it.copy(options = it.options.copy(batchSize = 1)) }
        val requests = mutableListOf<ItemId>()
        val gateway = object : TranslationModelGateway {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                requests.addAll(request.inputs.map { it.id })
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "translated") },
                    unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val journal = NativeRunStore(context, projects, runtime)
        try {
            runtime.selectChat(ChatIdentity(original.ref.projectId, ChatId("translation-workspace")))
            val service = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks)
            try {
                service.translateProject(original.ref, original.run, original.configuration, original.revision,
                    options = original.options) { if (it.committedItems == 1) throw kotlinx.coroutines.CancellationException("UI stop") }
                fail("Expected interruption")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            val resumed = tasks.resume(original.ref, original.run)
            val result = service.resume(resumed)
            assertEquals(original.run, result.run)
            assertEquals(3, result.committedItems)
            assertEquals(3, requests.size); assertEquals(3, requests.distinct().size)
            assertEquals("COMPLETE", tasks.recent(original.ref).single().stage)
        } finally { journal.close() }
    }

    @Test fun v6UpgradePreservesProjectFactsAndDoesNotInventOldTaskManifests() = runBlocking {
        val spec = spec()
        tasks.close(); projects.close()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE native_translation_staged")
                db.execSQL("DROP TABLE native_translation_responses")
                db.execSQL("DROP TABLE native_translation_request_inputs")
                db.execSQL("DROP TABLE native_translation_targets"); db.execSQL("DROP TABLE native_translation_tasks")
                db.execSQL("DELETE FROM room_master_table"); db.version = 6
            }
        }
        projects = NativeTextProjectStore(context, runtime); tasks = NativeTranslationTaskStore(context, projects, runtime, DefaultTranslationRetryRecoveryPolicy)
        assertEquals(spec.ref, projects.registeredProjects().single().ref)
        assertTrue(tasks.recent(spec.ref).isEmpty())
        val lease = begin(spec)
        try { tasks.open(lease, spec); assertEquals(3, tasks.recent(spec.ref).single().remaining) }
        finally { runtime.finishRun(lease) }
    }

    @Test fun cachedProviderResponseIsRestoredThroughRulesWithoutRepeatingThoseModelTargets() = runBlocking {
        val original = spec(); val lease = begin(original)
        val items = projects.items(original.ref, 0, 0, 40).items
        tasks.open(lease, original)
        val attempt = RequestAttemptId("cached-response")
        tasks.requesting(lease, 0, attempt, items.take(2).map { it.id }, 1)
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val request = TranslationModelRequest(original.ref, original.run, attempt, original.configuration,
                items.take(2).map { TranslationInput(it.id, it.source, it.fingerprint) }, original.options)
            val handle = audit.dispatched(lease, request, "a".repeat(64))
            audit.receivedResponse(handle, TranslationModelResponse(items.take(2).map { TranslationOutput(it.id, "cached") },
                unknownRequestUsage(), ModelStopReason.COMPLETE))
        } finally { audit.close(); runtime.finishRun(lease) }
        tasks.close(); tasks = NativeTranslationTaskStore(context, projects, runtime, DefaultTranslationRetryRecoveryPolicy)
        val resumed = tasks.resume(original.ref, original.run)
        val requested = mutableListOf<ItemId>()
        val gateway = object : TranslationModelGateway {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse {
                requested.addAll(request.inputs.map { it.id })
                return TranslationModelResponse(request.inputs.map { TranslationOutput(it.id, "new") }, unknownRequestUsage(), ModelStopReason.COMPLETE)
            }
        }
        val journal = NativeRunStore(context, projects, runtime)
        try {
            val result = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks).resume(resumed)
            assertEquals(3, result.committedItems)
            assertEquals(listOf(items.last().id), requested)
            assertEquals("cached", projects.item(original.ref, items.first().id)!!.item.translation)
        } finally { journal.close() }
    }

    @Test fun finalStagingPreservesCommandsAcrossCrashBeforeWriteAndNeedsNoModel() = runBlocking {
        val original = spec(); val lease = begin(original); val items = projects.items(original.ref, 0, 0, 40).items
        tasks.open(lease, original)
        tasks.stage(lease, items.map { TranslationTaskOutput(it.id, it.fingerprint, "final") })
        val edits = tasks.staged(lease)
        tasks.committing(lease, 0, edits) // Crash before the project transaction starts.
        runtime.finishRun(lease)
        val resumed = tasks.resume(original.ref, original.run)
        val gateway = object : TranslationModelGateway {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse = error("Model must not run")
        }
        val journal = NativeRunStore(context, projects, runtime)
        try {
            val result = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks).resume(resumed)
            assertEquals(3, result.committedItems)
            edits.forEach { assertEquals(ManualEditStatus.COMMITTED, projects.receipt(original.ref, it.commandId)!!.status) }
        } finally { journal.close() }
    }

    @Test fun stagedRowsLeftAfterFirstAtomicChunkResumeWithoutRewritingCommittedItems() = runBlocking {
        val project = projects.createTxt(CommandId("many"), "many", RelativePath("many.txt"),
            (1..30).joinToString("\n") { "source $it" }.toByteArray())
        val original = TranslationTaskSpec(project.ref, RunIdentity(project.ref.projectId, null, RunId("many"), CommandId("many-run")),
            ModelConfigurationRef("fixture", 1), 0, 0, 40, true, RemoteTranslationOptions("en", startMode = TranslationStartMode.RESET))
        val lease = begin(original); val items = projects.items(original.ref, 0, 0, 40).items
        tasks.open(lease, original); tasks.stage(lease, items.map { TranslationTaskOutput(it.id, it.fingerprint, "staged") })
        val edits = tasks.staged(lease); assertEquals(20, edits.size)
        tasks.committing(lease, 0, edits)
        val revision = projects.commitTranslation(lease, TranslationBatch(original.ref, 0, edits))
        tasks.committed(lease, revision, 20); runtime.finishRun(lease)
        val resumed = tasks.resume(original.ref, original.run)
        val gateway = object : TranslationModelGateway {
            override suspend fun complete(lease: RunLease, request: TranslationModelRequest): TranslationModelResponse = error("No new paid request")
        }
        val journal = NativeRunStore(context, projects, runtime)
        try {
            val result = TextTranslationService(runtime, projects, projects, gateway, DurableRunExecutor(runtime, journal), tasks).resume(resumed)
            assertEquals(30, result.committedItems); assertEquals(30L, result.revision)
            assertEquals("COMPLETE", tasks.recent(original.ref).single().stage)
        } finally { journal.close() }
    }

    @Test fun v7UpgradeKeepsTheOriginalTaskButDoesNotInventProviderOutputs() = runBlocking {
        val original = spec(); val lease = begin(original); tasks.open(lease, original); runtime.finishRun(lease)
        tasks.close(); projects.close()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
                null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE native_translation_staged"); db.execSQL("DROP TABLE native_translation_responses")
                db.execSQL("DROP TABLE native_translation_request_inputs"); db.execSQL("DELETE FROM room_master_table"); db.version = 7
            }
        }
        projects = NativeTextProjectStore(context, runtime); tasks = NativeTranslationTaskStore(context, projects, runtime, DefaultTranslationRetryRecoveryPolicy)
        assertEquals(original, tasks.resume(original.ref, original.run))
        assertEquals(3, tasks.recent(original.ref).single().remaining)
    }

    @Test fun partiallyCommittedPaidResponseCannotBecomeAnotherPaidRequestAfterRestart() = runBlocking {
        val spec = spec(); val lease = begin(spec); val items = projects.items(spec.ref, 0, 0, 40).items
        tasks.open(lease, spec); tasks.requesting(lease, 0, RequestAttemptId("response"), items.take(2).map { it.id }, 1)
        val edit = ManualTextEdit(CommandId("first"), spec.ref, items.first().id, 0, items.first().fingerprint, "saved")
        tasks.committing(lease, 0, listOf(edit)); val revision = projects.commitTranslation(lease, TranslationBatch(spec.ref, 0, listOf(edit)))
        tasks.committed(lease, revision, 1); runtime.finishRun(lease)
        assertEquals("RESPONSE_RECORDED", tasks.recent(spec.ref).single().stage)
        try { tasks.resume(spec.ref, spec.run); fail("Lost paid response was resent") }
        catch (_: TranslationTaskNeedsReview) { }
    }

    @Test fun failureEvidenceMatrixUsesPersistedReceiptsAndNeverInventsUsage() = runBlocking {
        val original = spec()
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val items = projects.items(original.ref, 0, 0, 40).items
            assertEquals(3, items.size)
            val notDispatched = RequestAttemptId("evidence-not-dispatched")
            tasks.requesting(lease, 0, notDispatched, listOf(items[0].id), 1)
            val uncertain = RequestAttemptId("evidence-uncertain")
            tasks.requesting(lease, 0, uncertain, listOf(items[1].id), 2)
            audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, uncertain,
                original.configuration, listOf(TranslationInput(items[1].id, items[1].source, items[1].fingerprint)),
                original.options), "a".repeat(64))
            val received = RequestAttemptId("evidence-received")
            tasks.requesting(lease, 0, received, listOf(items[2].id), 3)
            val handle = audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, received,
                original.configuration, listOf(TranslationInput(items[2].id, items[2].source, items[2].fingerprint)),
                original.options), "b".repeat(64))
            audit.received(handle, actualUsage(), ModelFailureKind.PARSING)

            val edits = items.mapIndexed { index, item ->
                ManualTextEdit(CommandId("evidence-failure-$index"), original.ref, item.id,
                    index.toLong(), item.fingerprint, item.translation, TextItemStatus.ERROR)
            }
            tasks.committing(lease, 0, edits, failures = true)
            val revision = projects.markTranslationFailures(lease, TranslationFailureBatch(original.ref, 0, edits))
            tasks.committed(lease, revision, 0)
            tasks.failed(lease)
            runtime.finishRun(lease)

            val evidence = tasks.failures(original.ref, original.run, 0L)
            assertEquals(original.ref, evidence.review.ref)
            assertEquals(original.run, evidence.review.run)
            val byId = evidence.members.associateBy { it.itemId }
            assertEquals(3, byId.size)
            assertEquals(TranslationFailureReceipt.NOT_DISPATCHED, byId.getValue(items[0].id).receipt)
            assertEquals(UsageSource.UNKNOWN, byId.getValue(items[0].id).usage)
            assertEquals(ModelFailureKind.UNKNOWN, byId.getValue(items[0].id).failure)
            assertEquals(TranslationFailureReceipt.DISPATCHED_NO_RECEIPT, byId.getValue(items[1].id).receipt)
            assertEquals(UsageSource.UNKNOWN, byId.getValue(items[1].id).usage)
            val receivedFact = byId.getValue(items[2].id)
            assertEquals(TranslationFailureReceipt.RECEIVED, receivedFact.receipt)
            assertEquals(ModelFailureKind.PARSING, receivedFact.failure)
            assertEquals(UsageSource.ACTUAL, receivedFact.usage)
            assertTrue(receivedFact.terminal)
            assertTrue(evidence.members.none { it.committed })
            assertEquals(items.map { it.fingerprint }, evidence.members.map { it.fingerprint })
        } finally { audit.close() }
    }

    @Test fun retryDecisionReopensTheSameTaskAndSelectionNeverCreatesANewRun() = runBlocking {
        val original = spec()
        val (revision, item) = failSingleTarget(original)
        val evidence = tasks.failures(original.ref, original.run, 0L)
        val member = evidence.members.single()
        val receipt = tasks.recordFailureDecision(
            evidence = evidence,
            batchId = TranslationTaskFailureBatchId("batch-retry-same"),
            action = TranslationTaskCommandAction.RETRY_MEMBERS,
            members = listOf(member),
            command = CommandId("retry-same-task"),
        )
        assertEquals(0, receipt.retryBudget)
        assertEquals(listOf(item.id), receipt.memberIds)
        val after = tasks.review(original.ref, original.run)
        assertEquals("READY", after.stage)
        assertEquals(revision, after.revision)
        assertEquals(1, tasks.recent(original.ref).size)

        // A direct replay is idempotent and keeps the same fixed task row.
        val replay = tasks.recordFailureDecision(
            evidence, TranslationTaskFailureBatchId("batch-retry-same"),
            TranslationTaskCommandAction.RETRY_MEMBERS, listOf(member), CommandId("retry-same-task"))
        assertEquals(0, replay.retryBudget)
        assertEquals(1, tasks.recent(original.ref).size)

        val lease = begin(original)
        try {
            assertEquals(setOf(item.id), tasks.retrySelection(lease))
            val opened = tasks.open(lease, original.copy(revision = revision), resuming = true)
            assertEquals(0, opened.committed)
        } finally { runtime.finishRun(lease) }

        // A changed fingerprint must not be reopened under the old command.
        val current = projects.item(original.ref, item.id)!!.item
        projects.edit(ManualTextEdit(CommandId("outside-edit"), original.ref, item.id, revision,
            current.fingerprint, "changed"))
        try {
            tasks.recordFailureDecision(
                tasks.failures(original.ref, original.run, 0L), TranslationTaskFailureBatchId("batch-retry-stale"),
                TranslationTaskCommandAction.RETRY_MEMBERS, listOf(member), CommandId("retry-stale"))
            fail("Reopened a changed project item")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, tasks.recent(original.ref).size)
    }

    @Test fun uncertainReceiptCannotBeRetriedOrSkippedButCanBeKept() = runBlocking {
        val original = spec()
        val item = failSingleTarget(original, uncertain = true).second
        val evidence = tasks.failures(original.ref, original.run, 0L)
        val member = evidence.members.single()
        assertEquals(TranslationFailureReceipt.DISPATCHED_NO_RECEIPT, member.receipt)
        for (action in listOf(TranslationTaskCommandAction.RETRY_MEMBERS,
            TranslationTaskCommandAction.SKIP_MEMBERS)) {
            try {
                tasks.recordFailureDecision(evidence, TranslationTaskFailureBatchId("batch-uncertain"),
                    action, listOf(member), CommandId("decision-${action.name}"))
                fail("Adjudicated an uncertain receipt")
            } catch (_: IllegalArgumentException) { }
        }
        tasks.recordFailureDecision(evidence, TranslationTaskFailureBatchId("batch-uncertain"),
            TranslationTaskCommandAction.KEEP_ERROR, listOf(member), CommandId("keep-uncertain"))
        assertEquals("FAILED", tasks.review(original.ref, original.run).stage)
        val lease = begin(original)
        try { assertTrue(tasks.retrySelection(lease).isEmpty()) } finally { runtime.finishRun(lease) }
    }

    @Test fun skipAndFinalizeCompletedWithErrorsMatchTargetRowsAndNeverFabricateUsage() = runBlocking {
        val original = spec()
        val lease = begin(original); tasks.open(lease, original)
        val items = projects.items(original.ref, 0, 0, 40).items
        // Commit two real targets.
        tasks.stage(lease, items.take(2).map { TranslationTaskOutput(it.id, it.fingerprint, "translated") })
        val edits = tasks.staged(lease)
        tasks.committing(lease, 0, edits)
        val committedRevision = projects.commitTranslation(lease, TranslationBatch(original.ref, 0, edits))
        tasks.committed(lease, committedRevision, 2)
        // Mark the third target as a received local failure.
        val base = projects.open(original.ref).revision
        val failureEdit = ManualTextEdit(CommandId("skip-complete-error"), original.ref, items[2].id, base,
            items[2].fingerprint, items[2].translation, TextItemStatus.ERROR)
        tasks.committing(lease, base, listOf(failureEdit), failures = true)
        val failureRevision = projects.markTranslationFailures(lease,
            TranslationFailureBatch(original.ref, base, listOf(failureEdit)))
        tasks.committed(lease, failureRevision, 2)
        tasks.failed(lease)
        runtime.finishRun(lease)
        ledger.value = DurableUsageSummary(1, 1, 0,
            RequestUsageTotals(1, input = TokenUsageTotal(actual = BigInteger.TEN)))

        val evidence = tasks.failures(original.ref, original.run, 0L)
        val member = evidence.members.single()
        tasks.recordFailureDecision(evidence, TranslationTaskFailureBatchId("batch-skip"),
            TranslationTaskCommandAction.SKIP_MEMBERS, listOf(member), CommandId("skip-final"))
        assertEquals("READY", tasks.review(original.ref, original.run).stage)
        val outcome = tasks.outcome(original.ref, original.run, setOf(items[2].id))
        assertEquals(3, outcome.targetItems); assertEquals(2, outcome.committedItems)
        assertEquals(1, outcome.skippedItems); assertEquals(UsageSource.ACTUAL, outcome.usage)

        tasks.completeWithErrors(original.ref, original.run, setOf(items[2].id),
            CommandId("complete-with-errors"))
        assertEquals("COMPLETE", tasks.review(original.ref, original.run).stage)
        tasks.completeWithErrors(original.ref, original.run, setOf(items[2].id),
            CommandId("complete-with-errors"))
        assertEquals(1, tasks.recent(original.ref).size)
        try {
            tasks.completeWithErrors(original.ref, original.run, setOf(items[0].id),
                CommandId("complete-foreign-target"))
            fail("Accepted a committed target as a skip")
        } catch (_: IllegalArgumentException) { }
        try {
            tasks.completeWithErrors(original.ref, original.run, emptySet(),
                CommandId("complete-missing-skips"))
            fail("Completed while a target remained unfinished")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun unknownLedgerUsageCannotFinalizeCompletedWithErrors() = runBlocking {
        val original = spec()
        val item = failSingleTarget(original).second
        val evidence = tasks.failures(original.ref, original.run, 0L)
        tasks.recordFailureDecision(evidence, TranslationTaskFailureBatchId("batch-unknown-ledger"),
            TranslationTaskCommandAction.SKIP_MEMBERS, listOf(evidence.members.single()),
            CommandId("skip-unknown-ledger"))
        try {
            tasks.completeWithErrors(original.ref, original.run, setOf(item.id),
                CommandId("complete-unknown-ledger"))
            fail("Fabricated completion from unknown usage")
        } catch (_: IllegalArgumentException) { }
        assertEquals("READY", tasks.review(original.ref, original.run).stage)
        ledger.value = DurableUsageSummary(1, 1, 0,
            RequestUsageTotals(1, input = TokenUsageTotal(actual = BigInteger.ONE)))
        tasks.completeWithErrors(original.ref, original.run, setOf(item.id),
            CommandId("complete-unknown-ledger"))
        assertEquals("COMPLETE", tasks.review(original.ref, original.run).stage)
    }

    @Test fun completionRefusesAnUnresolvedDispatch() = runBlocking {
        val original = spec()
        val item = failSingleTarget(original, uncertain = true).second
        val evidence = tasks.failures(original.ref, original.run, 0L)
        tasks.recordFailureDecision(evidence, TranslationTaskFailureBatchId("batch-uncertain-complete"),
            TranslationTaskCommandAction.KEEP_ERROR, listOf(evidence.members.single()),
            CommandId("keep-before-complete"))
        try {
            tasks.completeWithErrors(original.ref, original.run, setOf(item.id),
                CommandId("uncertain-complete"))
            fail("Completed an unresolved dispatch")
        } catch (_: IllegalArgumentException) { }
        assertEquals("FAILED", tasks.review(original.ref, original.run).stage)
    }

    @Test fun skipSelectionExcludesOnlyActiveExplicitSkipDecisions() = runBlocking {
        val original = spec()
        val lease = begin(original); tasks.open(lease, original)
        val item = projects.items(original.ref, 0, 0, 40).items.first()
        val edit = ManualTextEdit(CommandId("skip-selection-failure"), original.ref, item.id, 0,
            item.fingerprint, item.translation, TextItemStatus.ERROR)
        tasks.committing(lease, 0, listOf(edit), failures = true)
        val revision = projects.markTranslationFailures(lease, TranslationFailureBatch(original.ref, 0, listOf(edit)))
        tasks.committed(lease, revision, 0)
        tasks.failed(lease)
        assertEquals(emptySet<ItemId>(), tasks.skipSelection(lease))
        runtime.finishRun(lease)
        val evidence = tasks.failures(original.ref, original.run, 0L)
        tasks.recordFailureDecision(evidence, TranslationTaskFailureBatchId("batch-skip-selection"),
            TranslationTaskCommandAction.SKIP_MEMBERS, evidence.members, CommandId("skip-selection"))
        val resumed = begin(original)
        assertEquals(setOf(item.id), tasks.skipSelection(resumed))
        runtime.finishRun(resumed)
    }

    private suspend fun failSingleTarget(
        original: TranslationTaskSpec,
        uncertain: Boolean = false,
    ): Pair<Long, TextProjectItem> {
        val audit = NativeTranslationAuditStore(context, runtime, projects)
        try {
            val lease = begin(original); tasks.open(lease, original)
            val item = projects.items(original.ref, 0, 0, 40).items.first()
            val attempt = RequestAttemptId("failure-${original.run.runId.value}")
            tasks.requesting(lease, 0, attempt, listOf(item.id), 1)
            if (uncertain) {
                audit.dispatched(lease, TranslationModelRequest(original.ref, original.run, attempt,
                    original.configuration, listOf(TranslationInput(item.id, item.source, item.fingerprint)),
                    original.options), "c".repeat(64))
            }
            val edit = ManualTextEdit(CommandId("failure-mark-${original.run.runId.value}"),
                original.ref, item.id, 0, item.fingerprint, item.translation, TextItemStatus.ERROR)
            tasks.committing(lease, 0, listOf(edit), failures = true)
            val revision = projects.markTranslationFailures(lease, TranslationFailureBatch(original.ref, 0, listOf(edit)))
            tasks.committed(lease, revision, 0)
            tasks.failed(lease)
            runtime.finishRun(lease)
            return revision to item
        } finally { audit.close() }
    }

    private fun actualUsage(): RequestUsage = RequestUsage(
        TokenMeasurement(10, UsageSource.ACTUAL),
        TokenMeasurement(2, UsageSource.ACTUAL),
        TokenMeasurement(3, UsageSource.ACTUAL),
        TokenMeasurement(4, UsageSource.ACTUAL),
        TokenMeasurement(5, UsageSource.ACTUAL),
    )

    private class FakeLedger : UsageLedgerReadPort {
        var value = DurableUsageSummary(0, 0, 0, RequestUsageTotals())
        override suspend fun summary(query: UsageLedgerQuery): DurableUsageSummary = value
    }
}
