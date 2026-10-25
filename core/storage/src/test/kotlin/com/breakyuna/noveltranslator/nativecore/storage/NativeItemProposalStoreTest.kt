package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
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
class NativeItemProposalStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var proposals: NativeItemProposalStore
    private lateinit var ref: LocalProjectRef
    private lateinit var chat: ChatId
    private var sequence = 0

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        proposals = NativeItemProposalStore(context, projects, runtime)
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "one\ntwo".toByteArray()).ref
        chat = agent.currentChat(ref)
    }
    @After fun cleanup() = runBlocking { proposals.close(); agent.close(); projects.close(); runtime.close() }

    private fun begin(run: RunId = RunId("run-${++sequence}"), command: CommandId = CommandId("send-${++sequence}")): RunLease {
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        return runtime.beginRun(RunIdentity(ref.projectId, chat, run, command), RuntimeOwner.AGENT)
    }
    private fun end(lease: RunLease) { runtime.finishRun(lease); runtime.selectChat(null) }
    private suspend fun prepare(policy: ConflictPolicy = ConflictPolicy.REJECT_BATCH): ItemProposalSummary {
        val items = projects.readPage(ref).items
        val patches = buildJsonObject { put("items", JsonArray(items.map { item -> buildJsonObject {
            put("item_id", item.id.value); put("fingerprint", item.fingerprint.sha256)
            put("translation", "translated-${item.id.value}")
            put("name_translation", JsonArray(listOf(JsonPrimitive("译名-${item.id.value}"))))
        } })) }.toString()
        val lease = begin()
        return try { proposals.prepare(lease, ref, patches, policy) } finally { end(lease) }
    }
    private suspend fun accept(view: ItemProposalSummary, ids: Set<ItemId>): ItemProposalState {
        val lease = begin(view.run, view.command)
        return try { proposals.apply(lease, view, ids) } finally { end(lease) }
    }
    private suspend fun payload(view: ItemProposalSummary): ItemChangeProposal = withContext(Dispatchers.IO) {
        val db = NativeAppDatabase.open(context)
        try { ItemChangeProposal(view.id, view.ref, view.chat, view.run, view.command, view.revision, view.policy,
            db.itemProposals().members(view.id.value).map { ItemProposalCodec.member(it.payload) }) }
        finally { db.close() }
    }
    private suspend fun consume(view: ItemProposalSummary, selected: Set<ItemId>) = withContext(Dispatchers.IO) {
        val db = NativeAppDatabase.open(context)
        try {
            val binding = payload(view).binding(selected)
            val encoded = buildJsonObject { put("items", JsonArray(selected.sortedBy { it.value }.map { JsonPrimitive(it.value) })) }.toString()
            assertEquals(1, db.itemProposals().consume(view.id.value, encoded, binding.sha256, System.currentTimeMillis().coerceAtLeast(0)))
        } finally { db.close() }
    }

    @Test fun selectedMembersCommitTextAndNamesOnceWithExactReceipt() = runBlocking {
        val view = prepare()
        assertTrue(agent.hasPending(ref, chat))
        assertEquals(ItemProposalState.PARTIAL, accept(view, setOf(ItemId(1))))
        assertEquals("translated-1", projects.item(ref, ItemId(1))!!.item.translation)
        assertEquals(listOf("译名-1"), projects.item(ref, ItemId(1))!!.item.metadata.nameTranslation)
        assertEquals("", projects.item(ref, ItemId(2))!!.item.translation)
        val receipt = proposals.receipt(view)!!
        assertEquals(listOf(ItemId(1)), receipt.accepted)
        assertEquals(mapOf(ItemId(2) to ItemProposalRejection.NOT_SELECTED), receipt.rejected)
        assertEquals(ItemProposalState.PARTIAL, accept(view, setOf(ItemId(1))))
        assertEquals(receipt.revision, projects.open(ref).revision)
        try { accept(view, setOf(ItemId(1), ItemId(2))); fail("Reused approval for a larger selection") }
        catch (_: IllegalArgumentException) { }
        assertFalse(agent.hasPending(ref, chat))
    }

    @Test fun largeUnicodeReceiptSurvivesLookupReplayCopyAndImport() = runBlocking {
        ref = projects.createTxt(CommandId("large-create"), "large", RelativePath("large.txt"),
            (1..24).joinToString("\n") { "source-$it" }.toByteArray()).ref
        chat = agent.currentChat(ref)
        val translation = "译文😀".repeat(10_000)
        val items = projects.readPage(ref).items
        assertEquals(24, items.size)
        val patches = buildJsonObject { put("items", JsonArray(items.map { item -> buildJsonObject {
            put("item_id", item.id.value); put("fingerprint", item.fingerprint.sha256)
            put("translation", translation)
        } })) }.toString()
        val lease = begin()
        val view = try { proposals.prepare(lease, ref, patches, ConflictPolicy.REJECT_BATCH) }
            finally { end(lease) }
        val proposal = payload(view)
        assertTrue(proposal.payload.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024)
        val selected = items.map { it.id }.toSet()
        assertEquals(ItemProposalState.COMMITTED, accept(view, selected))
        val receipt = proposals.receipt(view)!!
        assertEquals(selected, receipt.accepted.toSet())
        assertEquals(ItemProposalState.COMMITTED, accept(view, selected))
        assertEquals(receipt.revision, projects.open(ref).revision)
        val copy = projects.copyProject(CommandId("large-copy"), ref)
        assertEquals(translation, projects.item(copy.ref, items.first().id)!!.item.translation)
        val bytes = ByteArrayOutputStream().also { output -> projects.saveProject(ref) { output } }.toByteArray()
        val imported = projects.importProject(CommandId("large-import")) { bytes.inputStream() }
        assertEquals(translation, projects.item(imported.ref, items.last().id)!!.item.translation)
    }

    @Test fun independentPolicyCommitsOnlyUnchangedMembersAndNeverReappliesRejectedOnes() = runBlocking {
        val view = prepare(ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS)
        val first = projects.item(ref, ItemId(1))!!
        projects.edit(ManualTextEdit(CommandId("human"), ref, first.item.id, first.revision, first.item.fingerprint, "human"))
        assertEquals(ItemProposalState.PARTIAL, accept(view, setOf(ItemId(1), ItemId(2))))
        val receipt = proposals.receipt(view)!!
        assertEquals(listOf(ItemId(2)), receipt.accepted)
        assertEquals(ItemProposalRejection.STALE_FINGERPRINT, receipt.rejected[ItemId(1)])
        assertEquals("human", projects.item(ref, ItemId(1))!!.item.translation)
        assertEquals(receipt.revision, projects.open(ref).revision)
        assertEquals(ItemProposalState.PARTIAL, accept(view, setOf(ItemId(1), ItemId(2))))
        assertEquals(receipt.revision, projects.open(ref).revision)
    }

    @Test fun rejectBatchPolicyRecordsNoWriteTerminalWhenRevisionChanged() = runBlocking {
        val view = prepare()
        val first = projects.item(ref, ItemId(1))!!
        projects.edit(ManualTextEdit(CommandId("human"), ref, first.item.id, first.revision, first.item.fingerprint, "human"))
        assertEquals(ItemProposalState.CONFLICT, accept(view, setOf(ItemId(1), ItemId(2))))
        assertEquals(1L, projects.open(ref).revision)
        assertEquals("", projects.item(ref, ItemId(2))!!.item.translation)
        assertTrue(proposals.receipt(view)!!.accepted.isEmpty())
        assertEquals(ItemProposalState.CONFLICT, accept(view, setOf(ItemId(1), ItemId(2))))
    }

    @Test fun consumedWithoutProjectReceiptIsCancelledWithoutReplay() = runBlocking {
        val view = prepare()
        consume(view, setOf(ItemId(1), ItemId(2)))
        proposals.reconcile(ref, chat)
        assertEquals(ItemProposalState.CANCELLED, proposals.find(ref, chat, view.id).state)
        assertEquals(0L, projects.open(ref).revision)
        assertNull(proposals.receipt(view))
    }

    @Test fun committedProjectReceiptReconcilesAfterMissingRoomCompletion() = runBlocking {
        val view = prepare()
        val selected = setOf(ItemId(1), ItemId(2))
        consume(view, selected)
        val lease = begin(view.run, view.command)
        val receipt = try { projects.approvedItems(lease, payload(view), selected) } finally { end(lease) }
        proposals.close(); proposals = NativeItemProposalStore(context, projects, runtime)
        proposals.reconcile(ref, chat)
        assertEquals(ItemProposalState.COMMITTED, proposals.find(ref, chat, view.id).state)
        assertEquals(receipt.revision, projects.open(ref).revision)
        assertEquals(receipt.accepted, proposals.receipt(view)!!.accepted)
    }

    @Test fun transactionFailureRollsBackRowsNamesRevisionsAndReceiptTogether() = runBlocking {
        val view = prepare()
        val proposal = payload(view)
        val selected = setOf(ItemId(1), ItemId(2))
        val file = File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")
        NativeProjectDatabase.open(file, writable = true).use { db ->
            try {
                ProjectWriteStore.approvedItems(db, proposal, selected) {
                    if (NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM item_revisions") > 0) throw IOException("injected")
                }
                fail("Injected failure did not abort")
            } catch (_: IOException) { }
            assertNull(ProjectWriteStore.approvedItemsReceipt(db, proposal, selected))
            assertEquals(0L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM item_revisions"))
        }
        assertEquals(0L, projects.open(ref).revision)
        assertEquals("", projects.item(ref, ItemId(1))!!.item.translation)
        assertNull(projects.item(ref, ItemId(1))!!.item.metadata.nameTranslation)
    }

    @Test fun singleAndBatchApprovalsShareOnePendingSlotAndCancellation() = runBlocking {
        val view = prepare()
        val lease = begin()
        try {
            try { agent.prepare(lease, ref, ItemId(1), "other"); fail("Created a second pending approval") }
            catch (_: IllegalStateException) { }
        } finally { end(lease) }
        agent.cancelPending(ref, view.run)
        assertEquals(ItemProposalState.CANCELLED, proposals.find(ref, chat, view.id).state)
        assertFalse(agent.hasPending(ref, chat))
    }

    @Test fun projectWriterRejectsAnUnconsumedProposalEvenWithAnActiveAgentLease() = runBlocking {
        val view = prepare()
        val lease = begin(view.run, view.command)
        try {
            try { projects.approvedItems(lease, payload(view), setOf(ItemId(1))); fail("Skipped approval consumption") }
            catch (_: IllegalArgumentException) { }
        } finally { end(lease) }
        assertEquals(0L, projects.open(ref).revision)
    }

    @Test fun v5ProjectUpgradesWithoutLosingNameHistoryAndCopiesCannotReuseApproval() = runBlocking {
        val first = projects.item(ref, ItemId(1))!!
        projects.editName(ManualNameEdit(CommandId("name"), ref, first.item.id, first.revision,
            first.item.fingerprint, listOf("old name")))
        val file = File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")
        NativeProjectDatabase.open(file, writable = true).use { db ->
            db.execSQL("DROP TABLE approved_item_receipts")
            db.execSQL("DROP TABLE approved_pdf_page_receipts"); db.version = 5
            NativeProjectDatabase.validateSchema(db)
        }
        val view = prepare()
        assertEquals(ItemProposalState.COMMITTED, accept(view, setOf(ItemId(1), ItemId(2))))
        NativeProjectDatabase.open(file).use { db ->
            assertEquals(6, db.version)
            assertEquals(3L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM item_revisions"))
            assertEquals("[\"old name\"]", db.rawQuery("SELECT name_translation FROM item_revisions WHERE revision=1", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            })
        }
        val copy = projects.copyProject(CommandId("copy"), ref)
        try { proposals.find(copy.ref, chat, view.id); fail("Reused approval in copied instance") }
        catch (_: IllegalArgumentException) { }
        assertEquals("translated-1", projects.item(copy.ref, ItemId(1))!!.item.translation)
    }

    @Test fun room13To15PreservesExistingChatAndAddsProposalTables() = runBlocking {
        agent.append(ref, chat, "SYSTEM", "history survives")
        proposals.close(); agent.close(); projects.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE native_policy_proposals")
            db.execSQL("DROP TABLE native_item_proposal_members")
            db.execSQL("DROP TABLE native_item_proposals")
            db.execSQL("DELETE FROM room_master_table"); db.version = 13
        }
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        proposals = NativeItemProposalStore(context, projects, runtime)
        assertEquals("history survives", agent.messages(ref, chat).single().text)
        val view = prepare()
        assertEquals(2, proposals.members(view).size)
    }

    @Test fun importingAGroupReceiptRejectsTamperedPayloadMissingReceiptAndWrongCommitTime() = runBlocking {
        val view = prepare()
        assertEquals(ItemProposalState.COMMITTED, accept(view, setOf(ItemId(1), ItemId(2))))
        val bytes = ByteArrayOutputStream().also { output -> projects.saveProject(ref) { output } }.toByteArray()
        suspend fun rejects(label: String, mutate: (android.database.sqlite.SQLiteDatabase) -> Unit) {
            val file = File.createTempFile("proposal-audit-", ".lnt", context.cacheDir)
            try {
                file.writeBytes(bytes)
                NativeProjectDatabase.open(file, writable = true).use(mutate)
                try { projects.importProject(CommandId(label)) { file.inputStream() }; fail("Imported damaged batch audit") }
                catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.CORRUPT_PROJECT, error.reason) }
            } finally { file.delete() }
        }
        rejects("tampered-payload") { db -> db.execSQL("UPDATE approved_item_receipts SET payload=replace(payload,'translated-1','changed-1')") }
        rejects("missing-receipt") { db -> db.execSQL("DELETE FROM approved_item_receipts") }
        rejects("wrong-time") { db -> db.execSQL("UPDATE item_revisions SET committed_at=committed_at+1") }
        assertEquals(1, projects.registeredProjects().size)
    }
}
