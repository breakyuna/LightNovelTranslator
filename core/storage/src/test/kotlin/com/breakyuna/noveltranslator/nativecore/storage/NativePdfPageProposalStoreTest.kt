package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
class NativePdfPageProposalStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var agent: NativeAgentStore
    private lateinit var items: NativeItemProposalStore
    private lateinit var proposals: NativePdfPageProposalStore
    private lateinit var ref: LocalProjectRef
    private lateinit var chat: ChatId
    private lateinit var asset: ProjectAssetSummary
    private var sequence = 0
    private val sha = "a".repeat(64)

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        runtime = RuntimeCoordinator()
        reopen()
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "source".toByteArray()).ref
        chat = agent.currentChat(ref)
        val imported = projects.importAsset(AssetImportPlan(CommandId("pdf"), ref, 0, RelativePath("book.pdf"),
            ProjectAssetFormat.PDF, "%PDF-fixture".toByteArray(), (1..3).map { page ->
                AssetImportUnit(page - 1, page, "page $page", tag = "page:$page", extraField =
                    """{"v":1,"page":$page,"decision":"translate","digest":"$sha"}""")
            }))
        assertEquals(AssetMutationStatus.COMMITTED, imported.status)
        val summary = projects.open(ref)
        asset = summary.assets.single { it.format == ProjectAssetFormat.PDF }
    }

    private fun reopen() {
        projects = NativeTextProjectStore(context, runtime)
        agent = NativeAgentStore(context, projects, runtime)
        items = NativeItemProposalStore(context, projects, runtime)
        proposals = NativePdfPageProposalStore(context, projects, runtime)
    }

    private suspend fun closeStores() {
        proposals.close(); items.close(); agent.close(); projects.close()
    }

    @After fun cleanup() = runBlocking { closeStores(); runtime.close() }

    private fun begin(run: RunId = RunId("run-${++sequence}"), command: CommandId = CommandId("send-${++sequence}")): RunLease {
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        return runtime.beginRun(RunIdentity(ref.projectId, chat, run, command), RuntimeOwner.AGENT)
    }
    private fun end(lease: RunLease) { runtime.finishRun(lease); runtime.selectChat(null) }

    private fun pagesJson(vararg pages: Pair<Int, PdfPageMode>): String = buildJsonObject {
        put("pages", JsonArray(pages.map { (page, mode) -> buildJsonObject {
            put("page", page); put("decision", mode.name)
        } }))
    }.toString()

    private suspend fun prepare(vararg pages: Pair<Int, PdfPageMode>,
                                 policy: ConflictPolicy = ConflictPolicy.REJECT_BATCH): PdfPageProposalSummary {
        val snapshot = projects.open(ref)
        val lease = begin()
        return try {
            proposals.prepare(lease, ref, asset.id, snapshot.revision, asset.sha256, pagesJson(*pages), policy)
        } finally { end(lease) }
    }

    private suspend fun accept(view: PdfPageProposalSummary, selected: Set<Int>): PdfPageProposalState {
        val lease = begin(view.run, view.command)
        return try { proposals.apply(lease, view, selected) } finally { end(lease) }
    }

    private suspend fun proposal(view: PdfPageProposalSummary): PdfPageChangeProposal = withContext(Dispatchers.IO) {
        val db = NativeAppDatabase.open(context)
        try {
            PdfPageChangeProposal(view.id, view.ref, view.chat, view.run, view.command, view.revision,
                view.assetId, view.assetSha256, view.pageCount, view.policy,
                db.pdfPageProposals().pages(view.id.value).map { PdfPageChangeProposalCodec.member(it.payload) })
        } finally { db.close() }
    }

    private suspend fun claim(view: PdfPageProposalSummary, selected: Set<Int>) = withContext(Dispatchers.IO) {
        val db = NativeAppDatabase.open(context)
        try {
            val binding = proposal(view).binding(selected)
            assertEquals(1, db.pdfPageProposals().claim(view.id.value,
                PdfPageChangeProposalCodec.selection(selected), binding.sha256))
        } finally { db.close() }
    }

    private suspend fun policy(): PdfAssetPagePolicy? = projects.projectSettings(ref).pdfPages.singleOrNull()

    @Test fun selectedPagesCommitSettingsOnceWithExactReceiptAndNoItemMutation() = runBlocking {
        val before = projects.items(ref, projects.open(ref).revision, 0, 100).items.map { it.fingerprint }
        val view = prepare(1 to PdfPageMode.KEEP, 2 to PdfPageMode.OMIT)
        assertEquals(PdfPageProposalState.COMMITTED, accept(view, setOf(1, 2)))
        val receipt = proposals.receipt(view)!!
        assertEquals(listOf(1, 2), receipt.accepted)
        assertTrue(receipt.rejected.isEmpty())
        assertEquals(2L, receipt.revision)
        assertEquals("ko-", policy()!!.decisions)
        assertEquals(before, projects.items(ref, 2, 0, 100).items.map { it.fingerprint })
        val replay = proposals.receipt(view)!!
        assertEquals(receipt.revision, replay.revision)
        assertEquals(receipt.accepted, replay.accepted)
        assertEquals(receipt.rejected, replay.rejected)
        assertEquals(PdfPageProposalState.COMMITTED, accept(view, setOf(1, 2)))
        assertEquals(2L, projects.open(ref).revision)
        try { accept(view, setOf(1)); fail("Reused approval for another selection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun unselectedPagesKeepTheirImportedDecisionAndReceiptIsPartial() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP, 2 to PdfPageMode.OMIT)
        assertEquals(PdfPageProposalState.PARTIAL, accept(view, setOf(1)))
        val receipt = proposals.receipt(view)!!
        assertEquals(listOf(1), receipt.accepted)
        assertEquals(PdfPageProposalRejection.NOT_SELECTED, receipt.rejected[2])
        assertEquals("k--", policy()!!.decisions)
        assertEquals(listOf(1, 2, 3), projects.items(ref, 2, 0, 100).items.filter { it.assetId == asset.id }.map { it.row })
    }

    @Test fun rejectSettlesThePendingAuthorityWithoutWritingSettings() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP)
        assertTrue(agent.hasPending(ref, chat))
        proposals.reject(view)
        assertEquals(PdfPageProposalState.REJECTED, proposals.find(ref, chat, view.id).state)
        assertFalse(agent.hasPending(ref, chat))
        assertNull(policy())
        assertEquals(PdfPageProposalState.REJECTED, accept(view, setOf(1)))
    }

    @Test fun rejectBatchRevisionChangeCreatesStableConflictWithoutWritingSettings() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP)
        val item = projects.item(ref, ItemId(1))!!
        projects.edit(ManualTextEdit(CommandId("human"), ref, item.item.id, item.revision, item.item.fingerprint, "human"))
        assertEquals(PdfPageProposalState.CONFLICT, accept(view, setOf(1)))
        assertNull(policy())
        assertEquals(2L, projects.open(ref).revision)
        val receipt = proposals.receipt(view)!!
        assertEquals(emptyList<Int>(), receipt.accepted)
        assertEquals(PdfPageProposalRejection.STALE_REVISION, receipt.rejected[1])
        assertEquals(PdfPageProposalState.CONFLICT, accept(view, setOf(1)))
        val replay = proposals.receipt(view)!!
        assertEquals(receipt.revision, replay.revision)
        assertEquals(receipt.accepted, replay.accepted)
        assertEquals(receipt.rejected, replay.rejected)
        assertEquals(2L, projects.open(ref).revision)
    }

    @Test fun independentPolicyCommitsOnlyThePageWhoseBaseStillMatches() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP, 2 to PdfPageMode.OMIT,
            policy = ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS)
        val current = projects.open(ref)
        val manual = current.settings!!.withPdfPages(listOf(PdfAssetPagePolicy(asset.id, asset.sha256, "o--")))
        assertEquals(AssetMutationStatus.COMMITTED,
            projects.editProjectSettings(CommandId("manual-pages"), ref, current.revision, manual))
        assertEquals(PdfPageProposalState.PARTIAL, accept(view, setOf(1, 2)))
        val receipt = proposals.receipt(view)!!
        assertEquals(listOf(2), receipt.accepted)
        assertEquals(PdfPageProposalRejection.STALE_PAGE, receipt.rejected[1])
        assertEquals("oo-", policy()!!.decisions)
        assertEquals(3L, projects.open(ref).revision)
        val replay = proposals.receipt(view)!!
        assertEquals(receipt.revision, replay.revision)
        assertEquals(receipt.accepted, replay.accepted)
        assertEquals(receipt.rejected, replay.rejected)
    }

    @Test fun missingRoomCompletionRecoversTheStoredProjectReceiptAfterReopen() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP)
        val selected = setOf(1)
        claim(view, selected)
        val frozen = proposal(view)
        val lease = begin(view.run, view.command)
        val receipt = try { projects.approvedPdfPages(lease, frozen, selected) } finally { end(lease) }
        proposals.close(); proposals = NativePdfPageProposalStore(context, projects, runtime)
        proposals.reconcile(ref, chat)
        assertEquals(PdfPageProposalState.COMMITTED, proposals.find(ref, chat, view.id).state)
        val replay = proposals.receipt(view)!!
        assertEquals(receipt.revision, replay.revision)
        assertEquals(receipt.accepted, replay.accepted)
        assertEquals(receipt.rejected, replay.rejected)
        assertEquals("k--", policy()!!.decisions)
    }

    @Test fun projectV6UpgradesForThePdfBatch() = runBlocking {
        val file = File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")
        NativeProjectDatabase.open(file, writable = true).use { db ->
            db.execSQL("DROP TABLE approved_pdf_page_receipts"); db.version = 6
            NativeProjectDatabase.validateSchema(db)
        }
        val view = prepare(1 to PdfPageMode.KEEP)
        assertEquals(PdfPageProposalState.COMMITTED, accept(view, setOf(1)))
        NativeProjectDatabase.open(file).use { db ->
            assertEquals(7, db.version)
            assertEquals("k--", ProjectSettingsCodec.decode(db.rawQuery(
                "SELECT payload FROM project_settings WHERE singleton=1", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }).pdfPages.single().decisions)
            assertEquals(1L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM approved_pdf_page_receipts"))
        }
    }

    @Test fun transactionFailureRollsBackSettingsRevisionAndProjectReceipt() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP)
        val frozen = proposal(view)
        val file = File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")
        NativeProjectDatabase.open(file, writable = true).use { db ->
            try {
                PdfPageBatchWriter.apply(db, frozen, setOf(1)) { throw IOException("injected") }
                fail("Injected failure did not abort")
            } catch (_: IOException) { }
            assertNull(PdfPageBatchWriter.receipt(db, frozen, setOf(1)))
            assertEquals(1L, NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton=1"))
        }
        assertNull(policy())
    }

    @Test fun preparedProposalBindsRevisionAssetAndBlocksOtherApprovalFamilies() = runBlocking {
        val snapshot = projects.open(ref)
        val lease = begin()
        try {
            try {
                proposals.prepare(lease, ref, asset.id, snapshot.revision, "b".repeat(64), pagesJson(1 to PdfPageMode.KEEP))
                fail("Accepted another asset identity")
            } catch (_: IllegalArgumentException) { }
            try {
                proposals.prepare(lease, ref, asset.id, snapshot.revision + 1, asset.sha256, pagesJson(1 to PdfPageMode.KEEP))
                fail("Accepted a stale revision")
            } catch (_: IllegalArgumentException) { }
            val view = proposals.prepare(lease, ref, asset.id, snapshot.revision, asset.sha256,
                pagesJson(1 to PdfPageMode.KEEP))
            val item = projects.item(ref, ItemId(1))!!
            try {
                agent.prepare(lease, ref, ItemId(1), "other"); fail("Created a concurrent text approval")
            } catch (_: IllegalStateException) { }
            try {
                items.prepare(lease, ref,
                    """{"items":[{"item_id":1,"fingerprint":"${item.item.fingerprint.sha256}","translation":"other"}]}""",
                    ConflictPolicy.REJECT_BATCH)
                fail("Created a concurrent item approval")
            } catch (_: IllegalStateException) { }
            agent.cancelPending(ref, view.run)
            assertEquals(PdfPageProposalState.CANCELLED, proposals.find(ref, chat, view.id).state)
            assertFalse(agent.hasPending(ref, chat))
        } finally { end(lease) }
    }

    @Test fun copyCannotConsumeTheOriginalApproval() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP)
        val copy = projects.copyProject(CommandId("copy"), ref)
        try { proposals.find(copy.ref, chat, view.id); fail("Accepted a foreign instance") }
        catch (_: IllegalArgumentException) { }
        val bytes = ByteArrayOutputStream().also { output -> projects.saveProject(ref) { output } }.toByteArray()
        val imported = projects.importProject(CommandId("import")) { ByteArrayInputStream(bytes) }
        try { proposals.find(imported.ref, chat, view.id); fail("Accepted an imported instance") }
        catch (_: IllegalArgumentException) { }
        val frozen = proposal(view)
        val lease = begin(view.run, view.command)
        try {
            try { projects.approvedPdfPages(lease, PdfPageChangeProposal(view.id, copy.ref, view.chat, view.run,
                view.command, view.revision, view.assetId, view.assetSha256, view.pageCount, view.policy, frozen.members),
                setOf(1)); fail("Applied an approval to a copy") }
            catch (_: IllegalArgumentException) { }
        } finally { end(lease) }
        assertNull(projects.projectSettings(copy.ref).pdfPages.singleOrNull())
        assertNull(policy())
    }

    @Test fun committedReceiptSurvivesSnapshotImportAsAuditOnly() = runBlocking {
        val view = prepare(1 to PdfPageMode.KEEP)
        assertEquals(PdfPageProposalState.COMMITTED, accept(view, setOf(1)))
        val bytes = ByteArrayOutputStream().also { output -> projects.saveProject(ref) { output } }.toByteArray()
        val imported = projects.importProject(CommandId("import-receipt")) { ByteArrayInputStream(bytes) }
        assertEquals("k--", projects.projectSettings(imported.ref).pdfPages.single().decisions)
        try { proposals.find(imported.ref, chat, view.id); fail("Reused approval in an imported instance") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun room15To16PreservesItemProposalsAndAddsPdfAuthority() = runBlocking {
        val item = projects.item(ref, ItemId(1))!!
        val lease = begin()
        val itemView = try { items.prepare(lease, ref,
            """{"items":[{"item_id":1,"fingerprint":"${item.item.fingerprint.sha256}","translation":"pending"}]}""",
            ConflictPolicy.REJECT_BATCH) } finally { end(lease) }
        closeStores()
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath("lnt_native_app.db").path,
            null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE native_pdf_page_proposal_pages")
            db.execSQL("DROP TABLE native_pdf_page_proposals")
            db.execSQL("DELETE FROM room_master_table"); db.version = 15
        }
        reopen()
        assertEquals(itemView.digest, items.pending(ref, chat)!!.digest)
        assertTrue(proposals.recent(ref, chat).isEmpty())
        agent.cancelPending(ref, itemView.run)
        val view = prepare(1 to PdfPageMode.KEEP)
        assertEquals(PdfPageProposalState.COMMITTED, accept(view, setOf(1)))
    }
}
