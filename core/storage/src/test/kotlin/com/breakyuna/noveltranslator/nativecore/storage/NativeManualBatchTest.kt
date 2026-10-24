package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
class NativeManualBatchTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db"); File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator(); store = NativeTextProjectStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }
    private suspend fun create() = store.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "one\ntwo\nthree".toByteArray())
    private suspend fun prepare(project: TextProjectSummary, change: TextBatchChange = TextBatchChange.EXCLUDE) =
        requireNotNull(store.prepareBatch(CommandId("batch"), project.ref, store.open(project.ref).revision,
            TextItemWindow(listOf(ItemId(1), ItemId(2))), change))
    private suspend fun edit(ref: LocalProjectRef, id: Long, command: CommandId = CommandId("manual-$id")) {
        val item = requireNotNull(store.item(ref, ItemId(id)))
        assertEquals(ManualEditStatus.COMMITTED, store.edit(ManualTextEdit(command, ref, item.item.id,
            item.revision, item.item.fingerprint, "saved-$id", TextItemStatus.PROCESSED)).status)
    }

    @Test fun preparationDoesNotWriteAndStatusBatchPreservesTextAndReplaysWithoutReapplying() = runBlocking {
        val project = create(); edit(project.ref, 1)
        val batch = prepare(project)
        assertEquals(1L, store.open(project.ref).revision)
        assertNull(store.receipt(project.ref, batch.itemCommand(0)))
        val result = store.editBatch(batch)
        assertEquals(ManualEditStatus.COMMITTED, result.status); assertEquals(2, result.affectedItems); assertEquals(3L, result.revision)
        val rows = store.readPage(project.ref).items
        assertEquals("saved-1", rows[0].translation)
        assertEquals(listOf(TextItemStatus.EXCLUDED, TextItemStatus.EXCLUDED, TextItemStatus.NONE), rows.map { it.status })
        assertNull(store.prepareBatch(CommandId("no-op"), project.ref, 3, TextItemWindow(listOf(ItemId(1), ItemId(2))), TextBatchChange.EXCLUDE))
        edit(project.ref, 1, CommandId("later"))
        assertEquals(result, store.editBatch(batch)); assertEquals(4L, store.open(project.ref).revision)
        assertEquals(TextItemStatus.PROCESSED, store.item(project.ref, ItemId(1))!!.item.status)
    }

    @Test fun clearBatchAndItsPerItemHistoryRemainValidAfterNativeSnapshotImport() = runBlocking {
        val project = create(); edit(project.ref, 1); edit(project.ref, 2)
        val batch = requireNotNull(store.prepareBatch(CommandId("clear"), project.ref, 2,
            TextItemWindow(listOf(ItemId(2))), TextBatchChange.CLEAR_AND_RESET))
        store.editBatch(batch)
        val output = ByteArrayOutputStream(); store.saveProject(project.ref) { output }
        val imported = store.importProject(CommandId("import-batch")) { ByteArrayInputStream(output.toByteArray()) }
        assertEquals(3L, imported.revision)
        val rows = store.readPage(imported.ref).items
        assertEquals("saved-1", rows[0].translation)
        assertEquals("", rows[1].translation); assertEquals(TextItemStatus.NONE, rows[1].status)
        assertEquals("three", rows[2].source)
    }

    @Test fun changedPayloadAndSingleEditCannotReuseTheLogicalBatchCommand() = runBlocking {
        val project = create(); val batch = prepare(project); store.editBatch(batch)
        val changed = ManualTextBatch(batch.commandId, batch.ref, batch.expectedRevision,
            TextBatchChange.CLEAR_AND_RESET, batch.targets)
        expectConflict { store.editBatch(changed) }
        val item = store.item(project.ref, ItemId(1))!!
        expectConflict { store.edit(ManualTextEdit(batch.commandId, project.ref, item.item.id,
            item.revision, item.item.fingerprint, "forbidden")) }
        assertEquals(2L, store.open(project.ref).revision)
    }

    @Test fun staleRevisionRejectsTheEntireBatchAndItsRejectionRemainsDurable() = runBlocking {
        val project = create(); val batch = prepare(project)
        edit(project.ref, 3)
        val rejected = store.editBatch(batch)
        assertEquals(ManualEditStatus.STALE_REVISION, rejected.status); assertEquals(0, rejected.affectedItems)
        assertEquals(1L, store.open(project.ref).revision)
        assertEquals(TextItemStatus.NONE, store.item(project.ref, ItemId(1))!!.item.status)
        edit(project.ref, 3, CommandId("later"))
        assertEquals(rejected, store.editBatch(batch)); assertEquals(2L, store.open(project.ref).revision)
        val output = ByteArrayOutputStream(); store.saveProject(project.ref) { output }
        val imported = store.importProject(CommandId("import-rejected-batch")) { ByteArrayInputStream(output.toByteArray()) }
        assertEquals(2L, imported.revision) // Rejected group receipts also obey the existing v1 format.
    }

    @Test fun lastTargetFingerprintConflictNeverCommitsTheFirstTarget() = runBlocking {
        val project = create(); val prepared = prepare(project)
        val targets = prepared.targets.toMutableList()
        targets[1] = targets[1].copy(fingerprint = ObjectFingerprint("0".repeat(64)))
        val batch = ManualTextBatch(prepared.commandId, prepared.ref, 0, prepared.change, targets)
        val result = store.editBatch(batch)
        assertEquals(ManualEditStatus.STALE_FINGERPRINT, result.status)
        assertEquals(0L, store.open(project.ref).revision)
        assertTrue(store.readPage(project.ref).items.all { it.status == TextItemStatus.NONE })
        assertEquals(result, store.editBatch(batch))
    }

    @Test fun cancellationAfterTheFirstWriteRollsBackAllRowsRevisionsAndReceipts() = runBlocking {
        val project = create(); val batch = prepare(project)
        val lease = runtime.beginProjectWrite()
        try {
            NativeProjectDatabase.open(File(context.filesDir, "native_projects/${project.ref.instanceId.value}/project.lnt"), true).use { db ->
                try { ProjectWriteStore.editBatch(db, batch) { throw CancellationException("Synthetic interruption") }; fail() }
                catch (_: CancellationException) { }
            }
        } finally { runtime.endProjectWrite(lease) }
        assertEquals(0L, store.open(project.ref).revision)
        for (index in batch.targets.indices) assertNull(store.receipt(project.ref, batch.itemCommand(index)))
        assertTrue(store.readPage(project.ref).items.all { it.status == TextItemStatus.NONE })
        assertEquals(2, store.editBatch(batch).affectedItems)
    }

    @Test fun partialReceiptNamespaceIsRejectedRatherThanCompletedAndSingleCommandReuseAlsoFails() = runBlocking {
        val project = create(); val batch = prepare(project)
        edit(project.ref, 2, batch.itemCommand(1))
        expectConflict { store.editBatch(batch) }
        assertNull(store.receipt(project.ref, batch.itemCommand(0)))
        assertEquals(TextItemStatus.NONE, store.item(project.ref, ItemId(1))!!.item.status)
        val other = requireNotNull(store.prepareBatch(CommandId("existing-single"), project.ref, 1,
            TextItemWindow(listOf(ItemId(1))), TextBatchChange.EXCLUDE))
        edit(project.ref, 3, other.commandId)
        expectConflict { store.editBatch(other) }
        assertEquals(2L, store.open(project.ref).revision)
    }

    @Test fun activeRunAndWrongGenerationCannotAcquireManualBatchWriteAuthority() = runBlocking {
        val project = create(); val batch = prepare(project)
        val chat = ChatId("chat"); runtime.selectChat(ChatIdentity(project.ref.projectId, chat))
        val lease = runtime.beginRun(RunIdentity(project.ref.projectId, chat, RunId("run"), CommandId("run")), RuntimeOwner.AGENT)
        try { try { store.editBatch(batch); fail() } catch (_: RuntimeUnavailable) { } }
        finally { runtime.finishRun(lease) }
        val foreign = ManualTextBatch(batch.commandId, project.ref.copy(generation = RecoveryGeneration(2)), 0, batch.change, batch.targets)
        try { store.editBatch(foreign); fail() }
        catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.WRONG_IDENTITY, error.reason) }
        assertEquals(0L, store.open(project.ref).revision)
    }

    private suspend fun expectConflict(block: suspend () -> Any?) {
        try { block(); fail() } catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.COMMAND_CONFLICT, error.reason) }
    }

    @Test fun queryReplacementCoversMoreThanOnePagePreservesStatusesAndReplaysWithoutReapplying() = runBlocking {
        val project = store.createTxt(CommandId("large-create"), "large", RelativePath("source.txt"),
            (1..105).joinToString("\n") { "source $it" }.toByteArray())
        for (id in 1L..105L) {
            val item = store.item(project.ref, ItemId(id))!!
            store.edit(ManualTextEdit(CommandId("seed-$id"), project.ref, ItemId(id), item.revision, item.item.fingerprint,
                "old-$id", if (id % 2 == 0L) TextItemStatus.EXCLUDED else TextItemStatus.ERROR))
        }
        val options = TextReplacementOptions("old", "\$1\\1")
        val batch = requireNotNull(store.prepareReplacementBatch(CommandId("replace-all"), project.ref, 105, TextItemQuery(), options))
        assertEquals(105, batch.targets.size); assertEquals(105L, store.open(project.ref).revision)
        val receipt = store.editBatch(batch)
        assertEquals(105, receipt.affectedItems); assertEquals(210L, receipt.revision)
        for (id in listOf(1L, 100L, 105L)) {
            val item = store.item(project.ref, ItemId(id))!!.item
            assertEquals("\$1\\1-$id", item.translation); assertEquals("source $id", item.source)
            assertEquals(if (id % 2 == 0L) TextItemStatus.EXCLUDED else TextItemStatus.ERROR, item.status)
        }
        edit(project.ref, 105, CommandId("later-edit"))
        assertEquals(receipt, store.editBatch(batch))
        assertEquals("saved-105", store.item(project.ref, ItemId(105))!!.item.translation)
    }

    @Test fun queryReplacementHonorsStatusAndSearchAndBindsReplacementParametersToReceipts() = runBlocking {
        val project = create()
        for (id in 1L..3L) {
            val item = store.item(project.ref, ItemId(id))!!
            store.edit(ManualTextEdit(CommandId("seed-$id"), project.ref, ItemId(id), item.revision, item.item.fingerprint,
                if (id == 3L) "unrelated" else "needle $id", if (id == 1L) TextItemStatus.PROCESSED else TextItemStatus.EXCLUDED))
        }
        val query = TextItemQuery(status = TextItemStatus.EXCLUDED, text = "needle", field = TextSearchField.TRANSLATION)
        val batch = requireNotNull(store.prepareReplacementBatch(CommandId("filtered"), project.ref, 3, query,
            TextReplacementOptions("needle", "changed")))
        assertEquals(listOf(ItemId(2)), batch.targets.map { it.id })
        store.editBatch(batch)
        assertEquals("needle 1", store.item(project.ref, ItemId(1))!!.item.translation)
        assertEquals("changed 2", store.item(project.ref, ItemId(2))!!.item.translation)
        assertEquals(TextItemStatus.EXCLUDED, store.item(project.ref, ItemId(2))!!.item.status)
        val altered = ManualTextBatch(batch.commandId, batch.ref, batch.expectedRevision, batch.change, batch.targets,
            TextReplacementOptions("needle", "different"))
        expectConflict { store.editBatch(altered) }
        assertNull(store.prepareReplacementBatch(CommandId("no-op"), project.ref, 4, TextItemQuery(), TextReplacementOptions("changed", "changed")))
    }

    @Test fun replacementCancellationAfterFirstItemRollsBackEveryRevisionAndReceipt() = runBlocking {
        val project = create(); edit(project.ref, 1); edit(project.ref, 2)
        val batch = requireNotNull(store.prepareReplacementBatch(CommandId("cancel-replacement"), project.ref, 2,
            TextItemQuery(), TextReplacementOptions("saved", "replacement")))
        val write = runtime.beginProjectWrite()
        try {
            NativeProjectDatabase.open(File(context.filesDir, "native_projects/${project.ref.instanceId.value}/project.lnt"), true).use { db ->
                try {
                    ProjectWriteStore.editBatch(db, batch) {
                        if (NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton=1") > 2L)
                            throw CancellationException("Interrupted after first replacement")
                    }
                    fail("Ignored interruption")
                } catch (_: CancellationException) { }
            }
        } finally { runtime.endProjectWrite(write) }
        assertEquals(2L, store.open(project.ref).revision)
        assertEquals("saved-1", store.item(project.ref, ItemId(1))!!.item.translation)
        assertEquals("saved-2", store.item(project.ref, ItemId(2))!!.item.translation)
        batch.targets.indices.forEach { assertNull(store.receipt(project.ref, batch.itemCommand(it))) }
        assertEquals(2, store.editBatch(batch).affectedItems)
    }

    @Test fun qualityReplacementUsesOnlyTheFixedWarningMembersAndRejectsAnOutdatedIndex() = runBlocking {
        val project = create()
        val policy = store.textPolicy(project.ref)
        store.editTextPolicy(ProjectPolicyEdit(CommandId("glossary"), project.ref, policy.revision, policy.fingerprint,
            ProjectTextPolicy(listOf(ProjectTextRule("term", TextRuleKind.TERM, "one", "expected")))))
        edit(project.ref, 1); edit(project.ref, 2)
        val revision = store.open(project.ref).revision
        val scanner = com.breakyuna.noveltranslator.nativecore.engine.ProjectQualityScanner(store, store)
        val index = scanner.scan(project.ref, revision, TextItemQuery(), TextProofreadingOptions())
        val quality = index.select(TextQualityFilter(TextQualityOutcome.ANY_WARNING, TextWarningCode.GLOSSARY))
        val batch = requireNotNull(store.prepareReplacementBatch(CommandId("quality-replace"), project.ref, revision,
            TextItemQuery(), TextReplacementOptions("saved", "fixed"), quality))
        assertEquals(listOf(ItemId(1)), batch.targets.map { it.id })
        store.editBatch(batch)
        assertEquals("fixed-1", store.item(project.ref, ItemId(1))!!.item.translation)
        assertEquals("saved-2", store.item(project.ref, ItemId(2))!!.item.translation)
        try {
            store.prepareReplacementBatch(CommandId("stale-quality"), project.ref, revision + 1, TextItemQuery(),
                TextReplacementOptions("saved", "wrong"), quality)
            fail("Accepted an outdated quality index")
        } catch (failure: TextProjectException) { assertEquals(TextProjectException.Reason.STALE_PAGE, failure.reason) }
    }

    @Test fun regexQueryReplacementCommitsCaptureResultsAndRejectsLiteralReinterpretation() = runBlocking {
        val project = create(); edit(project.ref, 1); edit(project.ref, 2)
        val batch = requireNotNull(store.prepareReplacementBatch(CommandId("regex-batch"), project.ref, 2,
            TextItemQuery(), TextReplacementOptions("saved-(\\d)", "item-\$1", regex = true)))
        assertEquals(2, batch.targets.size)
        val receipt = store.editBatch(batch)
        assertEquals("item-1", store.item(project.ref, ItemId(1))!!.item.translation)
        assertEquals("item-2", store.item(project.ref, ItemId(2))!!.item.translation)
        assertEquals(receipt, store.editBatch(batch))
        val literal = ManualTextBatch(batch.commandId, batch.ref, batch.expectedRevision, batch.change, batch.targets,
            requireNotNull(batch.replacement).copy(regex = false))
        expectConflict { store.editBatch(literal) }
        val output = ByteArrayOutputStream(); store.saveProject(project.ref) { output }
        val imported = store.importProject(CommandId("import-regex")) { ByteArrayInputStream(output.toByteArray()) }
        assertEquals("item-2", store.item(imported.ref, ItemId(2))!!.item.translation)
    }
}
