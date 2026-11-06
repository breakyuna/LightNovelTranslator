package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkspacePathTest {
    private val policy = PathPolicy.DEFAULT

    @Test
    fun acceptsUnicodeNestedPathsAndNormalizesInput() {
        val path = policy.parse("原稿/第一卷/正文.txt")
        assertEquals("原稿/第一卷/正文.txt", path.value)

        val decomposed = "Cafe\u0301/notes.txt"
        assertEquals("Café/notes.txt", policy.parse(decomposed).value)
    }

    @Test
    fun rejectsAbsoluteTraversalBackslashDriveAndControlPaths() {
        val invalid = listOf(
            "",
            "/absolute.txt",
            "//server/share.txt",
            "a\\b.txt",
            "C:/windows.txt",
            "C:relative.txt",
            "..",
            "../escape.txt",
            "a/../../escape.txt",
            ".",
            "a/./b.txt",
            "a//b.txt",
            "a/",
            "a\u0000b.txt",
            "a\nb.txt",
        )
        invalid.forEach { raw ->
            assertThrows("Expected rejection for $raw", IllegalArgumentException::class.java) {
                policy.parse(raw)
            }
        }
    }

    @Test
    fun rejectsNonNfcAndUnpairedSurrogatesAtTheValueBoundary() {
        assertReason(InvalidWorkspacePath.Reason.NOT_NFC) { WorkspacePath("e\u0301.txt") }
        assertReason(InvalidWorkspacePath.Reason.UNPAIRED_SURROGATE) { WorkspacePath("\ud800.txt") }
        assertReason(InvalidWorkspacePath.Reason.UNPAIRED_SURROGATE) { WorkspacePath("\udc00.txt") }
    }

    @Test
    fun enforcesDepthSegmentPathLengthAndWindowsNames() {
        val deep = (1..33).joinToString("/") { "d$it" }
        assertReason(InvalidWorkspacePath.Reason.TOO_DEEP) { policy.parse(deep) }

        val longSegment = "a".repeat(256)
        assertReason(InvalidWorkspacePath.Reason.SEGMENT_TOO_LONG) { policy.parse(longSegment) }

        val shortPolicy = PathPolicy(maxPathChars = 8)
        assertReason(InvalidWorkspacePath.Reason.PATH_TOO_LONG) { shortPolicy.parse("123456789") }

        listOf("CON", "con.txt", "NUL", "Com1", "lpt9.log").forEach { name ->
            assertReason(InvalidWorkspacePath.Reason.RESERVED_NAME) { policy.parse(name) }
        }
        assertEquals("CON.txt", PathPolicy(rejectWindowsReservedNames = false).parse("CON.txt").value)
    }

    @Test
    fun rejectsTrailingDotAndSpaceSegments() {
        assertReason(InvalidWorkspacePath.Reason.TRAILING_DOT_OR_SPACE) { policy.parse("dir./file.txt") }
        assertReason(InvalidWorkspacePath.Reason.TRAILING_DOT_OR_SPACE) { policy.parse("dir /file.txt") }
    }

    @Test
    fun casePolicyControlsIdentityWithoutRewritingDisplay() {
        val upper = policy.parse("Work/Report.txt")
        val lower = policy.parse("work/report.txt")

        assertFalse(PathPolicy(casePolicy = WorkspaceCasePolicy.EXACT).collides(upper, lower))
        assertTrue(PathPolicy(casePolicy = WorkspaceCasePolicy.FOLDED).collides(upper, lower))
        assertEquals("Work/Report.txt", upper.value)
        assertEquals("work/report.txt", PathPolicy(casePolicy = WorkspaceCasePolicy.FOLDED).identityKey(lower))
    }

    @Test
    fun rootRejectsSymlinkStyleEscapes() {
        val base = File(System.getProperty("java.io.tmpdir")!!, "lnt-workspace-${System.nanoTime()}")
        assertTrue(base.mkdirs())
        try {
            val root = WorkspaceRoot.open(base) { candidate ->
                if (candidate.name == "escape.txt") "/outside/escape.txt" else candidate.absolutePath
            }
            assertEquals(File(base, "nested/inside.txt"), root.resolve(policy.parse("nested/inside.txt")))
            assertReason(InvalidWorkspacePath.Reason.SYMLINK_ESCAPE) { root.resolve(policy.parse("escape.txt")) }
        } finally {
            base.deleteRecursively()
        }
    }

    private fun assertReason(reason: InvalidWorkspacePath.Reason, block: () -> Unit) {
        val failure = assertThrows(InvalidWorkspacePath::class.java) { block() }
        assertEquals(reason, failure.reason)
    }
}
