package com.breakyuna.noveltranslator.nativecore.workspace

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceScriptSourceTest {
    private lateinit var root: File
    private lateinit var catalog: FileWorkspaceScriptCatalog

    @Before fun setup() {
        root = File(System.getProperty("java.io.tmpdir"), "lnt-script-source-" + UUID.randomUUID()).apply { mkdirs() }
        catalog = FileWorkspaceScriptCatalog(root)
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun resolvesUtf8SourceWithSizeAndFingerprint() {
        write("hello/main.js", "return 7;")
        val source = runWorkspaceSuspend { catalog.resolve("hello", "main.js") }
        assertEquals("hello", source.scriptId)
        assertEquals("main.js", source.entryPoint)
        assertEquals("return 7;", source.text)
        assertEquals(9L, source.sizeBytes)
        assertEquals(sha256Of("return 7;"), source.sha256)
        assertTrue(source.toString().contains(source.sha256))
        assertTrue(!source.toString().contains("return 7"))
    }

    @Test fun rejectsTraversalAbsoluteBackslashControlAndReservedIdentities() {
        write("hello/main.js", "return 0;")
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID) {
            runWorkspaceSuspend { catalog.resolve("../outside", "main.js") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID) {
            runWorkspaceSuspend { catalog.resolve("..", "main.js") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID) {
            runWorkspaceSuspend { catalog.resolve("CON", "main.js") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_SCRIPT_ID) {
            runWorkspaceSuspend { catalog.resolve("bad\u0000id", "main.js") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT) {
            runWorkspaceSuspend { catalog.resolve("hello", "../outside.js") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT) {
            runWorkspaceSuspend { catalog.resolve("hello", "/etc/passwd") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT) {
            runWorkspaceSuspend { catalog.resolve("hello", "nested\\main.js") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENTRY_POINT) {
            runWorkspaceSuspend { catalog.resolve("hello", "bad\nentry.js") }
        }
    }

    @Test fun rejectsSourceAboveTheHardLimitBeforeLoadingIt() {
        val path = File(root, "big/main.js")
        path.parentFile.mkdirs()
        path.writeBytes(ByteArray(WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES.toInt() + 1) { 'a'.code.toByte() })
        assertReason(WorkspaceScriptSourceUnavailable.Reason.TOO_LARGE) {
            runWorkspaceSuspend { catalog.resolve("big", "main.js") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceScriptSource(
                scriptId = "big",
                entryPoint = "main.js",
                text = "a".repeat(WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES.toInt() + 1),
            )
        }
    }

    @Test fun rejectsInvalidUtf8AndMissingFiles() {
        val invalid = File(root, "broken/main.js")
        invalid.parentFile.mkdirs()
        invalid.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        assertReason(WorkspaceScriptSourceUnavailable.Reason.INVALID_ENCODING) {
            runWorkspaceSuspend { catalog.resolve("broken", "main.js") }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.NOT_FOUND) {
            runWorkspaceSuspend { catalog.resolve("missing", "main.js") }
        }
    }

    @Test fun rejectsSymlinkThatLeavesThePrivateRoot() {
        write("linked/main.js", "return 1;")
        val escaping = FileWorkspaceScriptCatalog(root) { candidate ->
            if (candidate.name == "main.js" && candidate.parentFile?.name == "linked") {
                "/outside/main.js"
            } else {
                candidate.absolutePath
            }
        }
        assertReason(WorkspaceScriptSourceUnavailable.Reason.ESCAPE) {
            runWorkspaceSuspend { escaping.resolve("linked", "main.js") }
        }
    }

    @Test fun catalogRequiresAnExistingPrivateRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            FileWorkspaceScriptCatalog(File(root, "missing-root"))
        }
    }

    private fun assertReason(
        reason: WorkspaceScriptSourceUnavailable.Reason,
        block: () -> Unit,
    ) {
        val failure = assertThrows(WorkspaceScriptSourceUnavailable::class.java) { block() }
        assertEquals(reason, failure.reason)
    }

    private fun write(relative: String, text: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
