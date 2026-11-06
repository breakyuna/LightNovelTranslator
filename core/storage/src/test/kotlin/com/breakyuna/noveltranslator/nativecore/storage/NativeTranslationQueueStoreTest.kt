package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueCoordinator
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeTranslationQueueStoreTest {
    private val projectA = ref("project-a", "instance-a")
    private val projectB = ref("project-b", "instance-b")
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        requireNotNull(newStore().fileFor(projectA).parentFile).deleteRecursively()
    }

    @Test
    fun snapshotRoundTripsEveryFactAcrossReopen() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val first = spec(
            projectA,
            "run-1",
            options = RemoteTranslationOptions(
                targetLanguage = "zh",
                sourceLanguage = "ja",
                instructions = "Keep names",
                batchSize = 7,
                batchCharacters = 4_096,
                startMode = TranslationStartMode.CONTINUE,
                precedingLines = 3,
                additionalRequestsPerBatch = 2,
                deduplicateSameFile = true,
            ),
        )
        val second = spec(projectA, "run-2")
        coordinator.enqueue(first, first.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        val batch = batch(
            "batch-1",
            member(1),
            member(2, receipt = TranslationFailureReceipt.DISPATCHED_NO_RECEIPT),
        )
        coordinator.recordFailureBatch(first.queueIdentity(), batch)
        coordinator.keepErrors(first.queueIdentity(), CommandId("keep-2"), batch.id, listOf(ItemId(2)))
        coordinator.retryMembers(first.queueIdentity(), CommandId("retry-1"), batch.id, listOf(ItemId(1)))
        coordinator.enqueue(second, second.run.commandId)
        val before = requireNotNull(store.load(projectA))

        val reopened = newStore()
        val loaded = requireNotNull(reopened.load(projectA))
        assertEquals(before.snapshot.revision, loaded.snapshot.revision)
        assertEquals(before.fingerprint, loaded.fingerprint)
        assertEquals(before.snapshot.updatedAtMillis, loaded.snapshot.updatedAtMillis)
        assertEquals(listOf(0L, 1L), loaded.snapshot.entries.map { it.task.sequence })
        assertEquals(first, loaded.snapshot.spec(first.queueIdentity()))
        assertEquals(second, loaded.snapshot.spec(second.queueIdentity()))
        assertEquals(before.snapshot.metas, loaded.snapshot.metas)

        val restored = requireNotNull(loaded.snapshot.task(first.queueIdentity()))
        assertEquals(TranslationTaskQueueState.FAILED, restored.state)
        assertEquals(1, restored.batches.size)
        assertEquals(batch.members, restored.batches.single().members)
        assertEquals(
            listOf(TranslationTaskCommandAction.KEEP_ERROR, TranslationTaskCommandAction.RETRY_MEMBERS),
            restored.commands.map { it.action },
        )
        assertEquals(batch.id, restored.decisions.last().batchId)
        assertEquals(listOf(ItemId(1)), restored.decisions.last().memberIds)
        assertEquals(TranslationTaskQueueState.QUEUED, loaded.snapshot.task(second.queueIdentity())!!.state)
        assertEquals(1, reopened.list().size)
    }

    @Test
    fun legacyVersionOneQueueDecodesWithoutAnExecutionRevisionAndUpgradesOnWrite() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val task = spec(projectA, "run-legacy")
        coordinator.enqueue(task, task.run.commandId)
        val current = Json.parseToJsonElement(file(projectA).readText()).jsonObject
        val legacyRoot = buildJsonObject {
            current.forEach { (key, value) ->
                when (key) {
                    "version" -> put(key, JsonPrimitive(1))
                    "fingerprint" -> Unit
                    "tasks" -> put(key, JsonArray(value.jsonArray.map { row ->
                        buildJsonObject {
                            row.jsonObject.forEach { (name, child) ->
                                if (name != "execution_revision") put(name, child)
                            }
                        }
                    }))
                    else -> put(key, value)
                }
            }
        }
        val fingerprint = ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(legacyRoot.toString())))
        val document = buildJsonObject {
            legacyRoot.forEach { (key, value) -> put(key, value) }
            put("fingerprint", fingerprint.sha256)
        }
        file(projectA).writeText(document.toString())

        val reopened = newStore()
        val loaded = requireNotNull(reopened.load(projectA))
        val legacy = requireNotNull(loaded.snapshot.task(task.queueIdentity()))
        assertNull(legacy.executionRevision)
        assertEquals(task.revision, legacy.actionableRevision)
        assertEquals(0, legacy.commands.size)

        val refreshed = newCoordinator(reopened).revalidateRevision(
            task.queueIdentity(), CommandId("revalidate-legacy"), 9)
        assertTrue(refreshed is TranslationQueueResult.Applied)
        val saved = requireNotNull(newStore().load(projectA)).snapshot.task(task.queueIdentity())!!
        assertEquals(9L, saved.executionRevision)
        assertEquals(9L, saved.actionableRevision)
        assertEquals(TranslationTaskCommandAction.REVALIDATE_REVISION, saved.commands.single().action)
        assertEquals(task.revision, saved.identity.revision)
    }

    @Test
    fun corruptUnknownOversizedLostTailAndForeignFilesAreRejected() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val first = spec(projectA, "run-1")
        val second = spec(projectA, "run-2")
        coordinator.enqueue(first, first.run.commandId)
        coordinator.enqueue(second, second.run.commandId)
        val valid = file(projectA).readText()
        val root = Json.parseToJsonElement(valid).jsonObject

        file(projectA).writeText(valid.dropLast(20))
        expectCorrupt { store.load(projectA) }

        file(projectA).writeText(root.withValue("version", JsonPrimitive(3)).toString())
        expectCorrupt { store.load(projectA) }

        val revision = root.getValue("revision").jsonPrimitive.long
        file(projectA).writeText(root.withValue("revision", JsonPrimitive(revision + 1)).toString())
        expectCorrupt { store.load(projectA) }

        val tasks = root.getValue("tasks").jsonArray
        assertEquals(2, tasks.size)
        file(projectA).writeText(
            root.withValue("tasks", JsonArray(tasks.dropLast(1))).toString(),
        )
        expectCorrupt { store.load(projectA) }

        file(projectA).writeBytes(ByteArray(NativeTranslationQueueStore.MAX_BYTES + 1) { ' '.code.toByte() })
        expectCorrupt { store.load(projectA) }
        expectCorrupt { store.list() }
        val third = spec(projectA, "run-3")
        try {
            coordinator.enqueue(third, third.run.commandId)
            fail("A corrupt queue was overwritten")
        } catch (_: TranslationQueueCorrupt) {
        }

        file(projectA).writeText(valid)
        assertNotNull(newStore().load(projectA))
        val other = ref("project-other", "instance-other")
        file(projectA).copyTo(file(other))
        expectCorrupt { newStore().load(other) }

        assertNull(newStore().load(projectB))
        val target = file(projectA)
        target.delete()
        File(target.path + ".new").writeText(valid.take(10))
        expectCorrupt { newStore().load(projectA) }
    }

    @Test
    fun compareAndSetRejectsStaleRevisionAndFingerprint() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val task = spec(projectA, "run-1")
        coordinator.enqueue(task, task.run.commandId)
        val first = requireNotNull(store.load(projectA))
        val dispatched = first.snapshot.queue.dispatchNext() as TranslationQueueResult.Applied
        val secondSnapshot = first.snapshot.updated(
            queue = dispatched.queue,
            changed = dispatched.task.identity,
            nowMillis = first.snapshot.updatedAtMillis + 1,
        )
        val second = store.compareAndSet(projectA, first, secondSnapshot)
        assertEquals(2L, second.snapshot.revision)

        val staleNext = first.snapshot.updated(
            queue = dispatched.queue,
            changed = dispatched.task.identity,
            nowMillis = first.snapshot.updatedAtMillis + 2,
        )
        try {
            store.compareAndSet(projectA, first, staleNext)
            fail("A stale revision was accepted")
        } catch (_: TranslationQueueConflict) {
        }

        val third = second.snapshot.updated(
            queue = second.snapshot.queue,
            changed = dispatched.task.identity,
            nowMillis = second.snapshot.updatedAtMillis + 1,
        )
        try {
            store.compareAndSet(
                projectA,
                StoredTranslationQueue(second.snapshot, ObjectFingerprint("0".repeat(64))),
                third,
            )
            fail("A stale fingerprint was accepted")
        } catch (_: TranslationQueueConflict) {
        }

        val empty = TranslationQueueSnapshot.of(
            ref = projectA,
            revision = 0,
            queue = second.snapshot.queue,
            specs = second.snapshot.specs,
            metas = second.snapshot.metas,
            updatedAtMillis = second.snapshot.updatedAtMillis,
        )
        try {
            store.compareAndSet(projectA, null, empty)
            fail("An existing queue was re-created")
        } catch (_: TranslationQueueConflict) {
        }

        try {
            store.compareAndSet(
                projectA,
                second,
                TranslationQueueSnapshot.of(
                    ref = projectA,
                    revision = 9,
                    queue = third.queue,
                    specs = third.specs,
                    metas = third.metas,
                    updatedAtMillis = third.updatedAtMillis,
                ),
            )
            fail("A skipped revision was accepted")
        } catch (_: IllegalArgumentException) {
        }

        val current = requireNotNull(store.load(projectA))
        assertEquals(2L, current.snapshot.revision)
        assertEquals(TranslationTaskQueueState.RUNNING, current.snapshot.task(task.queueIdentity())!!.state)
    }

    @Test
    fun interruptedWritesKeepTheLastPublishedSnapshot() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val first = spec(projectA, "run-1")
        coordinator.enqueue(first, first.run.commandId)
        val published = requireNotNull(store.load(projectA))
        val target = file(projectA)

        // A process died before AtomicFile published the new document: the base file still wins.
        File(target.path + ".new").writeText("{broken")
        assertEquals(published.fingerprint, requireNotNull(newStore().load(projectA)).fingerprint)
        val second = spec(projectA, "run-2")
        assertTrue(coordinator.enqueue(second, second.run.commandId) is TranslationQueueResult.Applied)
        val afterSecond = requireNotNull(store.load(projectA))

        // A legacy backup left by an interrupted published write is restored by AtomicFile.
        File(target.path + ".new").delete()
        val backup = File(target.path + ".bak")
        target.copyTo(backup, overwrite = true)
        target.delete()
        val recovered = requireNotNull(newStore().load(projectA))
        assertEquals(afterSecond.snapshot.revision, recovered.snapshot.revision)
        assertEquals(afterSecond.fingerprint, recovered.fingerprint)

        // An interrupted first write cannot be reported as an empty queue.
        target.delete()
        backup.delete()
        File(target.path + ".new").writeText("{broken")
        expectCorrupt { newStore().load(projectA) }
    }

    @Test
    fun storedDocumentCarriesNoBodyOrCredentialFields() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val task = spec(projectA, "run-1")
        coordinator.enqueue(task, task.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        coordinator.recordFailureBatch(task.queueIdentity(), batch("batch-1", member(1)))
        val text = file(projectA).readText()

        val keys = LinkedHashSet<String>()
        fun collect(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    keys.add(key)
                    collect(child)
                }
                is JsonArray -> value.forEach { collect(it) }
                else -> Unit
            }
        }
        collect(Json.parseToJsonElement(text))
        val forbidden = setOf(
            "source", "translation", "text", "body", "content",
            "prompt", "api_key", "authorization", "bearer", "header",
        )
        assertTrue("Unexpected body/credential field: ${keys intersect forbidden}", (keys intersect forbidden).isEmpty())
        assertTrue("fingerprint" in keys)
        assertFalse(text.contains("sk-live-secret"))
        assertFalse(text.contains("source body"))
    }

    @Test
    fun admissionOrderAcrossProjectsSurvivesReopen() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val a1 = spec(projectA, "run-a1")
        val b1 = spec(projectB, "run-b1")
        val a2 = spec(projectA, "run-a2")
        coordinator.enqueue(a1, a1.run.commandId)
        coordinator.enqueue(b1, b1.run.commandId)
        coordinator.enqueue(a2, a2.run.commandId)

        val scheduler = newCoordinator(newStore())
        assertEquals("run-a1", requireNotNull(scheduler.nextRunnable()).spec.run.runId.value)
        assertTrue(scheduler.markCompleted(a1.queueIdentity(), completion(1)) is TranslationQueueResult.Applied)
        assertNull(scheduler.nextRunnable(projectA))
        assertEquals("run-b1", requireNotNull(scheduler.nextRunnable(projectB)).spec.run.runId.value)
    }

    @Test
    fun reopenedRunningTaskIsNotRedispatched() = runBlocking {
        val store = newStore()
        val coordinator = newCoordinator(store)
        val task = spec(projectA, "run-1")
        coordinator.enqueue(task, task.run.commandId)
        requireNotNull(coordinator.nextRunnable())
        val before = requireNotNull(store.load(projectA))

        val reopened = newStore()
        val restarted = newCoordinator(reopened)
        assertNull(restarted.nextRunnable())
        assertTrue(restarted.resumeQueue(projectA) is TranslationQueueResult.Unchanged)
        assertEquals(before.snapshot.revision, requireNotNull(reopened.load(projectA)).snapshot.revision)
        assertEquals(
            TranslationTaskQueueState.RUNNING,
            requireNotNull(reopened.load(projectA)).snapshot.task(task.queueIdentity())!!.state,
        )
        assertTrue(restarted.markFailed(task.queueIdentity()) is TranslationQueueResult.Applied)
    }

    private fun newStore(): NativeTranslationQueueStore = NativeTranslationQueueStore(context)

    private fun newCoordinator(store: NativeTranslationQueueStore): TranslationQueueCoordinator {
        var now = 1_000L
        return TranslationQueueCoordinator(store, clock = { now++ })
    }

    private fun file(ref: LocalProjectRef): File = newStore().fileFor(ref)

    private fun JsonObject.withValue(key: String, value: JsonElement): JsonObject =
        buildJsonObject {
            this@withValue.forEach { (name, element) -> put(name, element) }
            put(key, value)
        }

    private suspend fun expectCorrupt(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a corrupt translation queue")
        } catch (_: TranslationQueueCorrupt) {
        }
    }

    private fun spec(
        ref: LocalProjectRef,
        runId: String,
        options: RemoteTranslationOptions = RemoteTranslationOptions(targetLanguage = "zh"),
    ): TranslationTaskSpec = TranslationTaskSpec(
        ref = ref,
        run = RunIdentity(
            projectId = ref.projectId,
            chatId = null,
            runId = RunId(runId),
            commandId = CommandId("command-$runId"),
        ),
        configuration = ModelConfigurationRef("model", 3),
        revision = 4,
        afterId = 0,
        pageSize = 20,
        wholeProject = false,
        options = options,
    )

    private fun ref(project: String, instance: String): LocalProjectRef = LocalProjectRef(
        projectId = ProjectId(project),
        instanceId = ProjectInstanceId(instance),
        generation = RecoveryGeneration(1),
    )

    private fun member(
        id: Long,
        receipt: TranslationFailureReceipt = TranslationFailureReceipt.RECEIVED,
    ): TranslationFailureMember = TranslationFailureMember(
        itemId = ItemId(id),
        fingerprint = ObjectFingerprint("d".repeat(64)),
        failure = ModelFailureKind.NETWORK,
        receipt = receipt,
    )

    private fun batch(id: String, vararg members: TranslationFailureMember): TranslationTaskFailureBatch =
        TranslationTaskFailureBatch(TranslationTaskFailureBatchId(id), members.toList())

    private fun completion(committed: Int): TranslationTaskCompletionFacts = TranslationTaskCompletionFacts(
        committedItems = committed,
        skippedItems = 0,
        receiptedItems = committed,
        targetItems = committed,
        unreceiptedDispatches = 0,
        usage = UsageSource.ACTUAL,
    )
}
