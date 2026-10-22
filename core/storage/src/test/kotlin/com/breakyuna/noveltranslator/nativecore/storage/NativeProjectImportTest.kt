package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
class NativeProjectImportTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var store: NativeTextProjectStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator()
        store = NativeTextProjectStore(context, runtime)
    }
    @After fun cleanup() = runBlocking { store.close(); runtime.close() }

    @Test fun exportedProjectImportsAsNewInstanceWithFactsButNoReusableLocalReceipt() = runBlocking {
        val source = create("first\r\nsecond")
        val item = store.items(source.ref, 0).items.first()
        val command = CommandId("manual")
        store.edit(ManualTextEdit(command, source.ref, item.id, 0, item.fingerprint, "译文"))
        val bytes = snapshot(source.ref)
        val copy = store.importProject(CommandId("import")) { ByteArrayInputStream(bytes) }
        assertEquals(source.ref.projectId, copy.ref.projectId)
        assertNotEquals(source.ref.instanceId, copy.ref.instanceId)
        assertEquals(1L, copy.revision)
        assertEquals("译文", store.items(copy.ref, 1).items.first().translation)
        assertNull(store.receipt(copy.ref, command))
        val original = ByteArrayOutputStream()
        store.exportOriginal(copy.ref) { original }
        assertEquals("first\r\nsecond", original.toString("UTF-8"))
        val repeat = store.importProject(CommandId("import")) { ByteArrayInputStream(bytes) }
        assertEquals(copy.ref, repeat.ref)
        store.close(); store = NativeTextProjectStore(context, runtime)
        assertEquals(2, store.registeredProjects().size)
        assertEquals(copy.ref, store.open(copy.ref).ref)
    }

    @Test fun explicitDuplicatePreservesSourceAndGetsIndependentIdentity() = runBlocking {
        val source = create("source")
        val copy = store.copyProject(CommandId("copy"), source.ref)
        assertNotEquals(source.ref.instanceId, copy.ref.instanceId)
        val item = store.items(copy.ref, 0).items.single()
        store.edit(ManualTextEdit(CommandId("edit"), copy.ref, item.id, 0, item.fingerprint, "copy only"))
        assertEquals("", store.items(source.ref, 0).items.single().translation)
    }

    @Test fun publishedDirectoryCanBeRegisteredAfterIndexWriteWasInterrupted() = runBlocking {
        val source = create("source")
        removeRegistration(source.ref)
        assertTrue(store.registeredProjects().isEmpty())
        val recovered = store.recoverProjects()
        assertEquals(1, recovered.recovered)
        assertTrue(recovered.problems.isEmpty())
        assertEquals(source.ref, store.registeredProjects().single().ref)
        assertEquals(source.ref, store.createTxt(CommandId("create"), "Example", RelativePath("source.txt"), "source".toByteArray()).ref)
        assertEquals(0, store.recoverProjects().recovered)
    }

    @Test fun malformedRecoveryManifestDoesNotHideOtherRecoverableProjectsOrDeleteFiles() = runBlocking {
        val first = create("first")
        val second = store.createTxt(CommandId("second"), "Second", RelativePath("b.txt"), "b".toByteArray())
        removeRegistration(first.ref); removeRegistration(second.ref)
        val manifest = File(directory(first.ref), "registration.json")
        manifest.writeText("{broken")
        val result = store.recoverProjects()
        assertEquals(1, result.recovered)
        assertEquals(1, result.problems.size)
        assertTrue(File(directory(first.ref), "project.lnt").isFile)
        assertEquals(second.ref, store.registeredProjects().single().ref)
    }

    @Test fun foreignSchemaIsRejectedAndTheSelectedFileIsNotChanged() = runBlocking {
        val source = create("source")
        val file = File(directory(source.ref), "project.lnt")
        NativeProjectDatabase.open(file, true).use {
            it.execSQL("CREATE TRIGGER injected AFTER UPDATE ON items BEGIN SELECT 1; END")
        }
        val bytes = file.readBytes()
        expect(TextProjectException.Reason.INVALID_FORMAT) {
            store.importProject(CommandId("bad")) { ByteArrayInputStream(bytes) }
        }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1, store.registeredProjects().size)
    }

    @Test fun corruptedAssetAndItemFingerprintCannotBecomeRegisteredProjects() = runBlocking {
        val source = create("source")
        val file = File(directory(source.ref), "project.lnt")
        NativeProjectDatabase.open(file, true).use { it.execSQL("UPDATE items SET fingerprint = ?", arrayOf("0".repeat(64))) }
        expect(TextProjectException.Reason.CORRUPT_PROJECT) {
            store.importProject(CommandId("bad-item")) { file.inputStream() }
        }
        NativeProjectDatabase.open(file, true).use {
            val valid = TextProjectFingerprint.item(ItemId(1), 0, "source", "", TextItemStatus.NONE)
            it.execSQL("UPDATE items SET fingerprint = ?", arrayOf(valid.sha256))
            it.execSQL("UPDATE asset_chunks SET data = ?", arrayOf("broken".toByteArray()))
        }
        expect(TextProjectException.Reason.CORRUPT_PROJECT) {
            store.importProject(CommandId("bad-asset")) { file.inputStream() }
        }
        assertEquals(1, store.registeredProjects().size)
    }

    @Test fun cancellationAndStreamCloseFailureLeaveNoPublishedInstance() = runBlocking {
        try {
            store.importProject(CommandId("cancel")) { object : InputStream() {
                override fun read(): Int = throw CancellationException("Synthetic cancellation")
            } }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        val source = create("source")
        val bytes = snapshot(source.ref)
        expect(TextProjectException.Reason.STORAGE_IO) {
            store.importProject(CommandId("close-failure")) { object : ByteArrayInputStream(bytes) {
                override fun close() { throw java.io.IOException("Synthetic close failure") }
            } }
        }
        assertEquals(1, store.registeredProjects().size)
        assertTrue(File(context.filesDir, "native_projects").listFiles()!!.none { it.name.startsWith(".staging-") })
    }

    @Test fun importFileSizeAndItemBudgetsAreEnforcedBeforeRegistration() = runBlocking {
        store.close()
        store = NativeTextProjectStore(context, runtime, importLimits = NativeImportLimits(maxFileBytes = 32))
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            store.importProject(CommandId("too-big")) { ByteArrayInputStream(ByteArray(33)) }
        }
        store.close(); store = NativeTextProjectStore(context, runtime)
        val source = create("one\ntwo")
        val bytes = snapshot(source.ref)
        store.close(); store = NativeTextProjectStore(context, runtime, TextProjectLimits(maxItems = 1))
        expect(TextProjectException.Reason.LIMIT_EXCEEDED) {
            store.importProject(CommandId("too-many")) { ByteArrayInputStream(bytes) }
        }
        assertEquals(1, store.registeredProjects().size)
    }

    @Test fun bilingualGenerationDeduplicatesOnlyWhenSelected() = runBlocking {
        val source = create("one\ntwo")
        val item = store.items(source.ref, 0).items.first()
        store.edit(ManualTextEdit(CommandId("edit"), source.ref, item.id, 0, item.fingerprint, "一"))
        val deduplicated = ByteArrayOutputStream()
        store.exportBilingual(source.ref, true) { deduplicated }
        assertEquals("one\n一\ntwo", deduplicated.toString("UTF-8"))
        val repeated = ByteArrayOutputStream()
        store.exportBilingual(source.ref, false) { repeated }
        assertEquals("one\n一\ntwo\ntwo", repeated.toString("UTF-8"))
    }

    @Test fun outputCleanupNeverRemovesProjectFactsOrUnownedFiles() = runBlocking {
        val source = create("source")
        val cache = File(context.cacheDir, "native_project_snapshots").apply { mkdirs() }
        val generated = File(cache, "snapshot-synthetic.txt").apply { writeText("derived") }
        val unowned = File(cache, "unowned.txt").apply { writeText("keep") }
        assertTrue(store.cleanupTemporaryOutputs() >= 1)
        assertFalse(generated.exists())
        assertTrue(unowned.exists())
        assertEquals(source.ref, store.open(source.ref).ref)
    }

    private suspend fun create(text: String) = store.createTxt(CommandId("create"), "Example", RelativePath("source.txt"), text.toByteArray())
    private suspend fun snapshot(ref: LocalProjectRef): ByteArray {
        val bytes = ByteArrayOutputStream(); store.saveProject(ref) { bytes }; return bytes.toByteArray()
    }
    private fun directory(ref: LocalProjectRef) = File(context.filesDir, "native_projects/${ref.instanceId.value}")
    private fun removeRegistration(ref: LocalProjectRef) {
        val app = NativeAppDatabase.open(context)
        try { app.openHelper.writableDatabase.execSQL("DELETE FROM project_instances WHERE instanceId = ?", arrayOf(ref.instanceId.value)) }
        finally { app.close() }
    }
    private suspend fun expect(reason: TextProjectException.Reason, block: suspend () -> Any?) {
        try { block(); fail("Expected $reason") } catch (error: TextProjectException) { assertEquals(reason, error.reason) }
    }
}
