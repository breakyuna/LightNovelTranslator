package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

/** Real SQLite/Room and synthetic files in Robolectric; not Android device/SAF evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeTextProjectStoreTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // These paths belong to this test's sandbox, never to a real installation.
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        File(context.cacheDir, "native_project_snapshots").deleteRecursively()
        runtime = RuntimeCoordinator()
        store = NativeTextProjectStore(context, runtime)
    }

    @After fun cleanup() = runBlocking {
        store.close()
        runtime.close()
    }

    @Test fun createsEditsClosesAndRediscoversProjectWithDurableReceipt() = runBlocking {
        val source = "first\r\nsecond\r\n".toByteArray()
        val project = store.createTxt(CommandId("create"), "Example", RelativePath("source.txt"), source)
        assertEquals(2, project.itemCount)
        val item = store.items(project.ref, 0).items.first()
        val command = ManualTextEdit(CommandId("edit"), project.ref, item.id, 0, item.fingerprint, "译文")
        val receipt = store.edit(command)
        assertEquals(ManualEditStatus.COMMITTED, receipt.status)
        assertEquals(1L, receipt.revision)
        store.close()
        store = NativeTextProjectStore(context, runtime)
        val listed = store.registeredProjects().single()
        assertEquals(project.ref, listed.ref)
        assertEquals(project.itemCount, listed.summary!!.itemCount)
        assertNull(listed.failure)
        assertEquals(receipt, store.receipt(project.ref, command.commandId))
        assertEquals(receipt, store.edit(command))
        assertEquals("译文", store.items(project.ref, 1).items.first().translation)
        val original = ByteArrayOutputStream()
        store.exportOriginal(project.ref) { original }
        assertArrayEquals(source, original.toByteArray())
        val translated = ByteArrayOutputStream()
        store.exportTxt(project.ref) { translated }
        assertEquals("译文\nsecond", translated.toString("UTF-8"))
    }

    @Test fun creationRetriesReuseInstanceAndChangedContentConflicts() = runBlocking {
        val first = create("a")
        assertEquals(first.ref, create("a").ref)
        expect(TextProjectException.Reason.COMMAND_CONFLICT) { create("different") }
        assertEquals(1, store.registeredProjects().size)
    }

    @Test fun pageSnapshotReadsCurrentSummaryAndRowsWhileOldRevisionReadsStillFailClosed() = runBlocking {
        val project = create("first\nsecond")
        val before = store.readPage(project.ref, limit = 1)
        val item = before.items.single()
        val receipt = store.edit(ManualTextEdit(CommandId("edit-page"), project.ref, item.id,
            before.summary.revision, item.fingerprint, "translated"))
        expect(TextProjectException.Reason.STALE_PAGE) { store.items(project.ref, before.summary.revision) }
        val after = store.readPage(project.ref, limit = 1)
        assertEquals(receipt.revision, after.summary.revision)
        assertEquals("translated", after.items.single().translation)
        assertEquals("", before.items.single().translation)
        assertEquals(0L, before.summary.revision)
        assertEquals("second", store.readPage(project.ref, afterId = item.id.value, limit = 1).items.single().source)
    }

    @Test fun repeatedCommandCannotChangeItsApprovedManualIntent() = runBlocking {
        val project = create("source")
        val item = store.items(project.ref, 0).items.single()
        val original = ManualTextEdit(CommandId("edit"), project.ref, item.id, 0, item.fingerprint, "one")
        store.edit(original)
        expect(TextProjectException.Reason.COMMAND_CONFLICT) {
            store.edit(ManualTextEdit(original.commandId, project.ref, item.id, 0, item.fingerprint, "two"))
        }
        assertEquals("one", store.items(project.ref, 1).items.single().translation)
    }

    @Test fun conflictReceiptRemainsTerminalEvenAfterAnotherWrite() = runBlocking {
        val project = create("source")
        val item = store.items(project.ref, 0).items.single()
        val stale = ManualTextEdit(CommandId("stale"), project.ref, item.id, 0,
            ObjectFingerprint("0".repeat(64)), "stale")
        val rejected = store.edit(stale)
        assertEquals(ManualEditStatus.STALE_FINGERPRINT, rejected.status)
        store.edit(ManualTextEdit(CommandId("good"), project.ref, item.id, 0, item.fingerprint, "good"))
        assertEquals(rejected, store.edit(stale))
        assertEquals(1L, store.open(project.ref).revision)
    }

    @Test fun cancellationAtCommitRollsBackContentRevisionAndReceipt() = runBlocking {
        val project = create("source")
        val item = store.items(project.ref, 0).items.single()
        val command = ManualTextEdit(CommandId("edit"), project.ref, item.id, 0, item.fingerprint, "next")
        NativeProjectDatabase.open(file(project), writable = true).use { db ->
            try {
                ProjectWriteStore.edit(db, command) { throw CancellationException("Synthetic interruption") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
        }
        assertEquals(0L, store.open(project.ref).revision)
        assertNull(store.receipt(project.ref, command.commandId))
        assertEquals("", store.items(project.ref, 0).items.single().translation)
        assertEquals(ManualEditStatus.COMMITTED, store.edit(command).status)
    }

    @Test fun pageRevisionAndLocalGenerationRejectStaleCallers() = runBlocking {
        val project = create("a\nb")
        val first = store.items(project.ref, 0, limit = 1).items.single()
        assertEquals("b", store.items(project.ref, 0, afterId = first.id.value, limit = 1).items.single().source)
        store.edit(ManualTextEdit(CommandId("edit"), project.ref, first.id, 0, first.fingerprint, "new"))
        expect(TextProjectException.Reason.STALE_PAGE) { store.items(project.ref, 0, afterId = first.id.value) }
        expect(TextProjectException.Reason.WRONG_IDENTITY) {
            store.open(project.ref.copy(generation = RecoveryGeneration(2)))
        }
    }

    @Test fun originalIsChunkedAndSurvivesCallerBufferMutation() = runBlocking {
        val bytes = ("x".repeat(65_000) + "\n" + "y".repeat(65_000)).toByteArray()
        val expected = bytes.copyOf()
        val project = store.createTxt(CommandId("create"), "Chunks", RelativePath("source.txt"), bytes)
        bytes.fill(0)
        NativeProjectDatabase.open(file(project)).use {
            assertTrue(NativeProjectDatabase.scalar(it, "SELECT COUNT(*) FROM asset_chunks") > 1)
            assertTrue(NativeProjectDatabase.scalar(it, "SELECT MAX(length(data)) FROM asset_chunks") <= 65536)
        }
        val original = ByteArrayOutputStream()
        store.exportOriginal(project.ref) { original }
        assertArrayEquals(expected, original.toByteArray())
    }

    @Test fun emptyAssetRemainsPresentWithoutInventingAnItem() = runBlocking {
        val project = create("")
        assertEquals(0, project.itemCount)
        assertTrue(store.items(project.ref, 0).items.isEmpty())
        val original = ByteArrayOutputStream()
        store.exportOriginal(project.ref) { original }
        assertEquals(0, original.size())
        NativeProjectDatabase.open(file(project)).use {
            assertEquals(1L, NativeProjectDatabase.scalar(it, "SELECT COUNT(*) FROM assets"))
        }
    }

    @Test fun processedEmptyTranslationIsRetainedAndUnprocessedTextFallsBackToSource() = runBlocking {
        val project = create("a\nb")
        val item = store.items(project.ref, 0).items.first()
        store.edit(ManualTextEdit(CommandId("edit"), project.ref, item.id, 0, item.fingerprint, ""))
        val output = ByteArrayOutputStream()
        store.exportTxt(project.ref) { output }
        assertEquals("\nb", output.toString("UTF-8"))
    }

    @Test fun snapshotIsValidStandaloneLntAndDestinationCloseFailurePreservesFacts() = runBlocking {
        val project = create("source")
        val item = store.items(project.ref, 0).items.single()
        store.edit(ManualTextEdit(CommandId("edit"), project.ref, item.id, 0, item.fingerprint, "saved"))
        val exported = File(context.cacheDir, "exported.lnt")
        store.saveProject(project.ref) { exported.outputStream() }
        NativeProjectDatabase.open(exported).use { db ->
            assertEquals(NativeProjectFormat.SCHEMA_VERSION, db.version)
            assertEquals(1, NativeProjectDatabase.summary(db, project.ref).itemCount)
            assertEquals(1L, NativeProjectDatabase.summary(db, project.ref).revision)
            assertEquals(1L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM item_revisions"))
            assertEquals(1L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM write_receipts"))
        }
        val saved = store.items(project.ref, 1).items.single()
        store.edit(ManualTextEdit(CommandId("next"), project.ref, saved.id, 1, saved.fingerprint, "newer"))
        NativeProjectDatabase.open(exported).use { db ->
            db.rawQuery("SELECT translation FROM items", null).use {
                assertTrue(it.moveToFirst()); assertEquals("saved", it.getString(0))
            }
        }
        try {
            store.saveProject(project.ref) { object : ByteArrayOutputStream() {
                override fun close() { throw IOException("Synthetic provider close failure") }
            } }
            fail("Expected close failure")
        } catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.STORAGE_IO, error.reason) }
        assertEquals(2L, store.open(project.ref).revision)
        assertTrue(File(context.cacheDir, "native_project_snapshots").listFiles()!!.any { it.extension == "lnt" })
    }

    @Test fun unknownItemAndStaleRevisionReturnDurableNonWritingOutcomes() = runBlocking {
        val project = create("source")
        val item = store.items(project.ref, 0).items.single()
        val unknown = ManualTextEdit(CommandId("missing"), project.ref, ItemId(999), 0, item.fingerprint, "no")
        assertEquals(ManualEditStatus.UNKNOWN_ITEM, store.edit(unknown).status)
        store.edit(ManualTextEdit(CommandId("good"), project.ref, item.id, 0, item.fingerprint, "yes"))
        val stale = ManualTextEdit(CommandId("stale"), project.ref, item.id, 0, item.fingerprint, "no")
        val receipt = store.edit(stale)
        assertEquals(ManualEditStatus.STALE_REVISION, receipt.status)
        assertEquals(receipt, store.receipt(project.ref, stale.commandId))
        assertEquals(ManualEditStatus.UNKNOWN_ITEM, store.edit(unknown).status)
        assertEquals("yes", store.items(project.ref, 1).items.single().translation)
    }

    @Test fun corruptOriginalIsRejectedBeforeOpeningTheDestination() = runBlocking {
        val project = create("source")
        NativeProjectDatabase.open(file(project), writable = true).use {
            it.execSQL("UPDATE asset_chunks SET data = ? WHERE part = 0", arrayOf("broken".toByteArray()))
        }
        var opened = false
        expect(TextProjectException.Reason.CORRUPT_PROJECT) {
            store.exportOriginal(project.ref) { opened = true; ByteArrayOutputStream() }
        }
        assertFalse(opened)
    }

    @Test fun unreadableRegisteredInstanceIsReportedWithoutHidingOthers() = runBlocking {
        val first = store.createTxt(CommandId("create-1"), "One", RelativePath("a.txt"), "one".toByteArray())
        val broken = store.createTxt(CommandId("create-2"), "Two", RelativePath("b.txt"), "two".toByteArray())
        File(context.filesDir, "native_projects/${broken.ref.instanceId.value}").deleteRecursively()
        val listed = store.registeredProjects()
        assertEquals(2, listed.size)
        assertEquals(first.ref, listed.single { it.failure == null }.ref)
        assertEquals(TextProjectException.Reason.UNKNOWN_PROJECT, listed.single { it.failure != null }.failure)
    }

    @Test fun walProjectIsRejectedWithoutModifyingTheFile() = runBlocking {
        val project = create("source")
        val target = file(project)
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA journal_mode = WAL", null).use { assertTrue(it.moveToFirst()) }
        }
        val before = target.readBytes()
        expect(TextProjectException.Reason.INVALID_FORMAT) { store.open(project.ref) }
        assertArrayEquals(before, target.readBytes())
    }

    @Test fun snapshotPreservesAutoincrementHighWaterMarkAfterDeletion() = runBlocking {
        val project = create("a\nb\nc")
        NativeProjectDatabase.open(file(project), writable = true).use { db ->
            db.execSQL("DELETE FROM items WHERE id = 3")
        }
        val exported = File(context.cacheDir, "sequence.lnt")
        store.saveProject(project.ref) { exported.outputStream() }
        SQLiteDatabase.openDatabase(exported.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL(
                "INSERT INTO items(asset_id, row_index, slot, source, translation, status, fingerprint, " +
                    "tag, name_source, name_translation, extra_field, skip_internal_filter) " +
                    "VALUES (1, 2, 2, 'c', '', 'NONE', ?, '', NULL, NULL, '{}', 0)",
                arrayOf("0".repeat(64)),
            )
            db.rawQuery("SELECT MAX(id) FROM items", null).use {
                assertTrue(it.moveToFirst())
                assertEquals(4L, it.getLong(0))
            }
        }
    }

    @Test fun wrongFormatAndFutureVersionAreRejectedWithoutChangingTheFile() = runBlocking {
        val project = create("source")
        val target = file(project)
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = NativeProjectFormat.SCHEMA_VERSION + 1
        }
        val before = target.readBytes()
        expect(TextProjectException.Reason.UNSUPPORTED_VERSION) { store.open(project.ref) }
        assertArrayEquals(before, target.readBytes())
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version = NativeProjectFormat.SCHEMA_VERSION
            it.execSQL("PRAGMA application_id = 0")
        }
        val foreign = target.readBytes()
        expect(TextProjectException.Reason.INVALID_FORMAT) { store.open(project.ref) }
        assertArrayEquals(foreign, target.readBytes())
    }

    @Test fun activeAgentRunBlocksManualEditsWithoutCreatingAReceipt() = runBlocking {
        val project = create("source")
        val chat = ChatId("chat")
        runtime.selectChat(ChatIdentity(project.ref.projectId, chat))
        val run = runtime.beginRun(RunIdentity(project.ref.projectId, chat, RunId("run"), CommandId("run-command")), RuntimeOwner.AGENT)
        val item = store.items(project.ref, 0).items.single()
        val command = ManualTextEdit(CommandId("edit"), project.ref, item.id, 0, item.fingerprint, "new")
        try {
            store.edit(command)
            fail("Expected runtime exclusion")
        } catch (_: RuntimeUnavailable) { }
        assertNull(store.receipt(project.ref, command.commandId))
        runtime.finishRun(run)
        assertEquals(ManualEditStatus.COMMITTED, store.edit(command).status)
    }

    private suspend fun create(source: String): TextProjectSummary = store.createTxt(
        CommandId("create"), "Example", RelativePath("source.txt"), source.toByteArray(),
    )

    private fun file(project: TextProjectSummary): File =
        File(context.filesDir, "native_projects/${project.ref.instanceId.value}/project.lnt")

    private suspend fun expect(reason: TextProjectException.Reason, block: suspend () -> Any?) {
        try { block(); fail("Expected $reason") } catch (error: TextProjectException) { assertEquals(reason, error.reason) }
    }
    @Test fun txtExportPreservesTheOriginalLineSeparator() = runBlocking {
        val project = create("one\r\ntwo")
        val output = ByteArrayOutputStream()
        store.exportTxt(project.ref) { output }
        assertEquals("one\r\ntwo", output.toString("UTF-8"))
    }
}
