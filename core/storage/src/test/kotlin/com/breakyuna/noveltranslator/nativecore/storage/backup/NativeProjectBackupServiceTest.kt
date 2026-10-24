package com.breakyuna.noveltranslator.nativecore.storage.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
class NativeProjectBackupServiceTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var service: NativeProjectBackupService
    private lateinit var temporary: File

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        temporary = File(context.cacheDir, "backup-test").apply { deleteRecursively(); mkdirs() }
        service = NativeProjectBackupService(projects, temporary)
    }

    @After fun cleanup() = runBlocking {
        projects.close(); runtime.close(); temporary.deleteRecursively()
    }

    @Test fun roundTripRestoresProjectAsNewIdentityWithTranslatedItems() = runBlocking {
        val original = projects.createTxt(CommandId("create"), "backup", RelativePath("source.txt"), "one\ntwo".toByteArray())
        val row = projects.readPage(original.ref).items.first()
        projects.edit(ManualTextEdit(CommandId("edit"), original.ref, row.id, original.revision,
            row.fingerprint, "translated"))
        val bytes = ByteArrayOutputStream()
        val summary = service.export(original.ref) { bytes }
        assertEquals("backup", summary.name)
        assertTrue(summary.projectBytes > 0)
        assertEquals(64, summary.sha256.length)
        val restored = service.restore(CommandId("restore")) { ByteArrayInputStream(bytes.toByteArray()) }
        assertNotEquals(original.ref, restored.ref)
        assertEquals("backup", restored.name)
        assertEquals(2, restored.itemCount)
        val page = projects.readPage(restored.ref)
        assertEquals("translated", page.items.first().translation)
    }

    @Test fun notAZipIsRejectedWithoutCreatingProject() = runBlocking {
        val before = projects.registeredProjects().size
        try {
            service.restore(CommandId("restore")) { ByteArrayInputStream("not a zip".toByteArray()) }
            fail("Accepted a non-zip backup")
        } catch (_: Exception) { }
        assertEquals(before, projects.registeredProjects().size)
    }

    @Test fun manifestDigestMismatchIsRejected() = runBlocking {
        val original = projects.createTxt(CommandId("create"), "backup", RelativePath("source.txt"), "one".toByteArray())
        val exported = ByteArrayOutputStream()
        service.export(original.ref) { exported }
        val entries = unzip(exported.toByteArray())
        val manifest = entries.getValue("manifest.json")
        val tampered = zip(listOf("manifest.json" to manifest, "project.lnt" to "tampered".toByteArray()))
        val before = projects.registeredProjects().size
        try {
            service.restore(CommandId("restore")) { ByteArrayInputStream(tampered) }
            fail("Accepted a digest mismatch")
        } catch (_: Exception) { }
        assertEquals(before, projects.registeredProjects().size)
    }

    @Test fun duplicateOrUnknownEntriesAreRejected() = runBlocking {
        val before = projects.registeredProjects().size
        for (entries in listOf(
            listOf("manifest.json" to ByteArray(0), "manifest.json" to ByteArray(0), "project.lnt" to ByteArray(0)),
            listOf("manifest.json" to ByteArray(0), "project.lnt" to ByteArray(0), "extra.txt" to ByteArray(0)),
        )) {
            try {
                service.restore(CommandId("restore")) { ByteArrayInputStream(zip(entries)) }
                fail("Accepted an invalid backup")
            } catch (_: Exception) { }
        }
        assertEquals(before, projects.registeredProjects().size)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
