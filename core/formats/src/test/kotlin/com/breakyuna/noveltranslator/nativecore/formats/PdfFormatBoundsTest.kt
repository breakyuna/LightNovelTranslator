package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfFormatBoundsTest {
    @Test fun emptyInputIsCorrupt() {
        assertFailure(PdfFormatException.Reason.CORRUPT) {
            PdfFormatBounds.checkInput(ByteArray(0))
        }
    }

    @Test fun inputWithoutPdfHeaderIsCorrupt() {
        assertFailure(PdfFormatException.Reason.CORRUPT) {
            PdfFormatBounds.checkInput("not a pdf at all".toByteArray())
        }
    }

    @Test fun headerIsFoundAfterLeadingJunk() {
        val bytes = ByteArray(1_000) { 'x'.code.toByte() } + "%PDF-1.7".toByteArray()
        assertTrue(PdfFormatBounds.hasHeader(bytes))
        PdfFormatBounds.checkInput(bytes)
    }

    @Test fun headerSearchIsBoundedToTheFirstKiB() {
        val outside = ByteArray(2_048) { ' '.code.toByte() }
        "%PDF-1.7".toByteArray().copyInto(outside, 1_025)
        assertFalse(PdfFormatBounds.hasHeader(outside))
    }

    @Test fun oversizedInputIsALimitFailure() {
        assertFailure(PdfFormatException.Reason.LIMIT) {
            PdfFormatBounds.checkInputSize(PdfFormatBounds.MAX_INPUT_BYTES + 1)
        }
        assertFailure(PdfFormatException.Reason.LIMIT) {
            PdfFormatBounds.checkInputSize(Int.MAX_VALUE)
        }
        PdfFormatBounds.checkInputSize(PdfFormatBounds.MAX_INPUT_BYTES)
    }

    @Test fun zeroPageDocumentAndPageLimitAreTyped() {
        assertFailure(PdfFormatException.Reason.CORRUPT) {
            PdfFormatBounds.checkPageCount(0)
        }
        PdfFormatBounds.checkPageCount(PdfFormatBounds.MAX_PAGES)
        assertFailure(PdfFormatException.Reason.LIMIT) {
            PdfFormatBounds.checkPageCount(PdfFormatBounds.MAX_PAGES + 1)
        }
    }

    @Test fun pageTextLimitCarriesThePageNumber() {
        PdfFormatBounds.checkPageText(PdfFormatBounds.MAX_UNIT_CHARS, 1)
        try {
            PdfFormatBounds.checkPageText(PdfFormatBounds.MAX_UNIT_CHARS + 1, 7)
            fail("Expected PdfFormatException")
        } catch (failure: PdfFormatException) {
            org.junit.Assert.assertEquals(PdfFormatException.Reason.LIMIT, failure.reason)
            org.junit.Assert.assertEquals(7, failure.page)
        }
    }

    @Test fun encryptedDocumentsAreRejected() {
        PdfFormatBounds.checkEncrypted(false)
        assertFailure(PdfFormatException.Reason.ENCRYPTED) {
            PdfFormatBounds.checkEncrypted(true)
        }
    }

    @Test fun outputSizeAndPageBudgetsAreTyped() {
        PdfFormatBounds.checkOutputSize(PdfFormatBounds.MAX_OUTPUT_BYTES)
        assertFailure(PdfFormatException.Reason.LIMIT) {
            PdfFormatBounds.checkOutputSize(PdfFormatBounds.MAX_OUTPUT_BYTES + 1)
        }
        PdfFormatBounds.checkOutputPages(PdfFormatBounds.MAX_OUTPUT_PAGES)
        assertFailure(PdfFormatException.Reason.LIMIT) {
            PdfFormatBounds.checkOutputPages(PdfFormatBounds.MAX_OUTPUT_PAGES + 1)
        }
    }

    @Test fun oversizedPathIsALimitFailure() {
        PdfFormatBounds.checkPath(RelativePath("a".repeat(PdfFormatBounds.MAX_PATH_CHARS - 4) + ".pdf"))
        assertFailure(PdfFormatException.Reason.LIMIT) {
            PdfFormatBounds.checkPath(RelativePath("a".repeat(PdfFormatBounds.MAX_PATH_CHARS - 3) + ".pdf"))
        }
    }

    private fun assertFailure(reason: PdfFormatException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Expected PdfFormatException($reason)")
        } catch (failure: PdfFormatException) {
            org.junit.Assert.assertEquals(reason, failure.reason)
        }
    }
}
