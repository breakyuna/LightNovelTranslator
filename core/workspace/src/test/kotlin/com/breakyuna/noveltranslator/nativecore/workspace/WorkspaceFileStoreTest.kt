package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkspaceFileStoreTest {
    private lateinit var root: File
    private lateinit var store: WorkspaceFileStore

    @Before fun setup() {
        root = File(System.getProperty("java.io.tmpdir"), "lnt-workspace-" + UUID.randomUUID()).apply { mkdirs() }
        store = WorkspaceFileStore(root)
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun materialsAreListedReadAndFingerprintedWithoutExposingBodiesInSummaries() {
        write("sources/a.txt", "alpha")
        write("uploads/b.json", "{\"b\":1}")
        write("datasets/table.csv", "x,y\n1,2\n")
        val all = runWorkspaceSuspend { store.list(null) }
        assertEquals(3, all.size)
        val source = runWorkspaceSuspend { store.list(WorkspaceMaterialKind.SOURCE) }.single()
        assertEquals(WorkspaceMaterialKind.SOURCE, source.kind)
        assertEquals("sources/a.txt", source.path.value)
        assertEquals(5L, source.sizeBytes)
        assertEquals(64, source.sha256.length)
        assertFalse(source.toString().contains("alpha"))
        val content = runWorkspaceSuspend { store.read(source.id, 5) }
        assertEquals("alpha", String(content.bytes, Charsets.UTF_8))
        assertEquals(source.sha256, content.sha256)
        assertEquals(WorkspaceDigest(source.sha256),
            runWorkspaceSuspend { store.fingerprint(WorkspacePath("sources/a.txt")) })
        assertNull(runWorkspaceSuspend { store.fingerprint(WorkspacePath("sources/missing.txt")) })
    }

    @Test fun readLimitAndChangedFileAreRejectedByTheFingerprintBoundary() {
        write("sources/a.txt", "alpha")
        val source = runWorkspaceSuspend { store.list(WorkspaceMaterialKind.SOURCE) }.single()
        val tooLarge = try {
            runWorkspaceSuspend { store.read(source.id, 4) }; null
        } catch (error: WorkspaceMaterialUnavailable) { error }
        assertEquals(WorkspaceMaterialUnavailable.Reason.TOO_LARGE, tooLarge?.reason)
        write("sources/a.txt", "changed")
        val mismatch = try {
            runWorkspaceSuspend { store.read(source.id, 64) }; null
        } catch (error: WorkspaceMaterialUnavailable) { error }
        assertEquals(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH, mismatch?.reason)
    }

    @Test fun artifactsArePublishedAtomicallyAndNeverOverwriteTheSameRunPath() {
        write("sources/a.txt", "source")
        val run = RunId("run-1")
        val draft = WorkspaceArtifactDraft(WorkspacePath("reports/out.txt"), "text/plain", "result".toByteArray())
        val first = runWorkspaceSuspend { store.save(run, listOf(draft)) }.single()
        assertEquals("text/plain", first.mediaType)
        assertEquals(6L, first.sizeBytes)
        assertEquals(draft.sha256, first.sha256)
        val published = root.walkTopDown().first { it.isFile && it.name == "out.txt" }
        assertEquals("result", published.readText())
        assertThrows(IllegalArgumentException::class.java) {
            runWorkspaceSuspend { store.save(run, listOf(draft)) }
        }
    }

    private fun write(relative: String, text: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
