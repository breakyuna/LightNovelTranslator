package com.breakyuna.noveltranslator.nativecore.storage.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPort
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import com.breakyuna.noveltranslator.nativecore.domain.RegisteredProject
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeProjectSetBackupServiceTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var service: NativeProjectSetBackupService
    private lateinit var temporary: File

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        temporary = File(context.cacheDir, "set-backup-test").apply { deleteRecursively(); mkdirs() }
        service = NativeProjectSetBackupService(projects, temporary)
    }

    @After fun cleanup() = runBlocking {
        projects.close(); runtime.close(); temporary.deleteRecursively(); Unit
    }

    @Test fun roundTripRestoresEveryProjectAsANewInstance() = runBlocking {
        val first = projects.createTxt(CommandId("create-one"), "first", RelativePath("one.txt"), "one\ntwo".toByteArray())
        val second = projects.createTxt(CommandId("create-two"), "second", RelativePath("two.txt"), "three".toByteArray())
        val out = ByteArrayOutputStream()
        val summary = service.export { out }
        assertEquals(2, summary.projectCount)
        assertTrue(summary.totalBytes > 0)
        val result = service.restore(CommandId("restore-set")) { ByteArrayInputStream(out.toByteArray()) }
        assertEquals(2, result.importedCount)
        assertEquals(2, result.projects.size)
        assertNotEquals(first.ref, result.projects[0].ref)
        assertNotEquals(second.ref, result.projects[1].ref)
        val names = result.projects.map { it.name }.toSet()
        assertEquals(setOf("first", "second"), names)
        assertTrue(projects.registeredProjects().size >= 4)
    }

    @Test fun overLimitIsRejectedBeforeOpeningDestination() = runBlocking {
        projects.createTxt(CommandId("limit-fixture"), "fixture", RelativePath("source.txt"), "one".toByteArray())
        val fixture = projects.registeredProjects().single()
        val port = object : TextProjectPort by projects {
            override suspend fun registeredProjects(limit: Int): List<RegisteredProject> {
                assertEquals(101, limit)
                return List(101) { fixture }
            }
        }
        val backup = NativeProjectSetBackupService(port, temporary)
        var opened = false
        try {
            backup.export { opened = true; ByteArrayOutputStream() }
            fail("Accepted an incomplete whole-set backup")
        } catch (error: TextProjectException) {
            assertEquals(TextProjectException.Reason.LIMIT_EXCEEDED, error.reason)
        }
        assertTrue(!opened)
        // The production listing must support the overflow probe as well.
        assertEquals(1, projects.registeredProjects(101).size)
    }

    @Test fun manifestPathMismatchIsRejectedBeforeImport() = runBlocking {
        projects.createTxt(CommandId("path-fixture"), "fixture", RelativePath("source.txt"), "one".toByteArray())
        val original = ByteArrayOutputStream()
        service.export { original }
        val changed = ByteArrayOutputStream()
        ZipOutputStream(changed).use { output ->
            ZipInputStream(ByteArrayInputStream(original.toByteArray())).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(if (entry.name == "manifest.json") {
                        bytes.toString(Charsets.UTF_8).replace("projects/0000.lnt", "../outside.lnt").toByteArray()
                    } else bytes)
                    output.closeEntry()
                }
            }
        }
        try {
            service.restore(CommandId("restore-bad-path")) { ByteArrayInputStream(changed.toByteArray()) }
            fail("Accepted a mismatched manifest path")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, projects.registeredProjects().size)
    }

    @Test fun malformedArchiveIsRejectedBeforeImport() = runBlocking {
        projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"), "one".toByteArray())
        val before = projects.registeredProjects().size
        try {
            service.restore(CommandId("restore")) { ByteArrayInputStream("not a zip".toByteArray()) }
            fail("Accepted a non-zip backup")
        } catch (_: Exception) { }
        assertEquals(before, projects.registeredProjects().size)
    }
}
