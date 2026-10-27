package com.breakyuna.noveltranslator.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProjectImportModelsTest {
    private fun candidate(
        id: String,
        name: String,
        size: Long = 128,
        encoding: String? = null,
    ) = NativeImportCandidate(id, name, size, encoding)

    @Test
    fun txtAndEpubAreAcceptedInSelectionOrderAndTheFirstFileSeedsTheProject() {
        val review = reviewNativeProjectImport(
            listOf(
                candidate("source-epub", "Second.EPUB", 200, "UTF-8"),
                candidate("source-txt", "First.TXT", 100, "GB18030"),
            ),
            existingProjectNames = emptySet(),
        )
        assertTrue(review.canCreate)
        assertEquals(listOf("Second.epub", "First.txt"), review.files.map { it.fileName })
        assertEquals(
            listOf(NativeImportFormat.EPUB, NativeImportFormat.TXT),
            review.files.map { it.format },
        )
        assertEquals(listOf(0, 1), review.files.map { it.order })
        assertEquals(listOf("UTF-8", "GB18030"), review.files.map { it.encoding })
        assertEquals(NativeProjectSeed.EPUB_FIRST_ASSET, review.seed)
        assertEquals("Second", review.projectName)
        assertEquals(300L, review.totalBytes)
    }

    @Test
    fun aTxtFirstSelectsTextModeEvenWhenAnEpubFollows() {
        val review = reviewNativeProjectImport(
            listOf(candidate("txt", "volume.txt"), candidate("epub", "volume.epub")),
            existingProjectNames = emptySet(),
        )
        assertTrue(review.canCreate)
        assertEquals(NativeProjectSeed.TXT_TEXT, review.seed)
        assertEquals("volume", review.projectName)
        assertEquals(listOf("volume.txt", "volume.epub"), review.files.map { it.fileName })
    }

    @Test
    fun unsupportedExtensionsAreRejectedWithoutOpeningTheFile() {
        val review = reviewNativeProjectImport(
            listOf(candidate("pdf", "book.pdf"), candidate("image", "cover.jpeg")),
            existingProjectNames = emptySet(),
        )
        assertFalse(review.canCreate)
        assertTrue(review.files.isEmpty())
        assertEquals(
            listOf(NativeImportIssueCode.UNSUPPORTED_EXTENSION, NativeImportIssueCode.UNSUPPORTED_EXTENSION),
            review.issues.map { it.code },
        )
        assertNull(review.seed)
    }

    @Test
    fun emptyAndOversizedFilesFailButTheExactBoundaryIsAccepted() {
        val review = reviewNativeProjectImport(
            listOf(
                candidate("empty", "empty.txt", 0),
                candidate("large", "large.txt", NATIVE_IMPORT_MAX_ASSET_BYTES + 1),
                candidate("boundary", "boundary.epub", NATIVE_IMPORT_MAX_ASSET_BYTES),
            ),
            existingProjectNames = emptySet(),
        )
        assertFalse(review.canCreate)
        assertEquals(listOf("boundary.epub"), review.files.map { it.fileName })
        assertEquals(listOf(0), review.files.map { it.order })
        assertEquals(
            listOf(NativeImportIssueCode.EMPTY_FILE, NativeImportIssueCode.FILE_TOO_LARGE),
            review.issues.map { it.code },
        )
        assertEquals(NativeProjectSeed.EPUB_FIRST_ASSET, review.seed)
        assertEquals(NativeImportFormat.EPUB, review.files.single().format)
        assertEquals(NATIVE_IMPORT_MAX_ASSET_BYTES, review.totalBytes)
    }

    @Test
    fun duplicateSourcesAndCaseInsensitiveSameNamesAreConflicts() {
        val review = reviewNativeProjectImport(
            listOf(
                candidate("one", "Book.TXT"),
                candidate("one", "other.txt"),
                candidate("two", "book.txt"),
            ),
            existingProjectNames = emptySet(),
        )
        assertFalse(review.canCreate)
        assertEquals(listOf("Book.txt"), review.files.map { it.fileName })
        assertEquals(
            listOf(NativeImportIssueCode.DUPLICATE_SOURCE, NativeImportIssueCode.DUPLICATE_NAME),
            review.issues.map { it.code },
        )
        assertEquals("one", review.issues[0].sourceId)
        assertEquals("two", review.issues[1].sourceId)
    }

    @Test
    fun anExistingProjectNameBlocksCreationInsteadOfOverwriting() {
        val review = reviewNativeProjectImport(
            listOf(candidate("source", "Book.epub")),
            existingProjectNames = setOf(" book "),
        )
        assertFalse(review.canCreate)
        assertEquals("Book", review.projectName)
        assertEquals(NativeImportIssueCode.PROJECT_NAME_CONFLICT, review.issues.single().code)
        assertEquals("source", review.issues.single().sourceId)
    }

    @Test
    fun fileNamesAreNormalizedAndByteBoundedWithoutLosingTheExtension() {
        assertEquals("书 名.txt", sanitizeNativeImportFileName("..\\目录\\书 名.TXT"))
        assertEquals("BOOK.txt", sanitizeNativeImportFileName("ＢＯＯＫ．ｔｘｔ"))
        assertEquals("_CON.txt", sanitizeNativeImportFileName("ＣＯＮ.txt"))
        assertEquals("a_b.txt", sanitizeNativeImportFileName("a\u0000b.txt"))
        assertEquals("passwd.txt", sanitizeNativeImportFileName("../../etc/passwd.txt"))

        val longName = sanitizeNativeImportFileName("📚".repeat(60) + ".txt")
        assertTrue(longName.endsWith(".txt"))
        assertTrue(longName.toByteArray(Charsets.UTF_8).size <= 180)
        assertFalse(longName.contains('\uFFFD'))
    }

    @Test
    fun aLongUnsupportedExtensionCannotBecomeTxtThroughTruncation() {
        val review = reviewNativeProjectImport(
            listOf(candidate("source", "book.txt" + "x".repeat(40))),
            existingProjectNames = emptySet(),
        )
        assertFalse(review.canCreate)
        assertTrue(review.files.isEmpty())
        assertEquals(NativeImportIssueCode.UNSUPPORTED_EXTENSION, review.issues.single().code)
    }

    @Test
    fun emptySelectionCannotCreateAProject() {
        val review = reviewNativeProjectImport(emptyList(), existingProjectNames = emptySet())
        assertFalse(review.canCreate)
        assertTrue(review.files.isEmpty())
        assertTrue(review.issues.isEmpty())
        assertNull(review.projectName)
        assertNull(review.seed)
    }
}
