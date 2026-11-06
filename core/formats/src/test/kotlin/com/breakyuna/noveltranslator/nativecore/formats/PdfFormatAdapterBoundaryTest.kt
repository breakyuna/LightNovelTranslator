package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Adapter-boundary checks that run before PDFBox is touched. Full PDF parsing and encryption
 * detection need Android classes (`android.util.Log`) and are covered by the device/CI boundary
 * described in [PdfFormatAdapter], not by this plain-JVM test source set.
 */
class PdfFormatAdapterBoundaryTest {
    @Test fun headerlessInputIsRejectedBeforePdfBoxLoad() {
        assertFailure(PdfFormatException.Reason.CORRUPT) {
            PdfFormatAdapter().read("this is not a pdf".toByteArray(), RelativePath("book.pdf"))
        }
    }

    @Test fun emptyInputIsRejectedBeforePdfBoxLoad() {
        assertFailure(PdfFormatException.Reason.CORRUPT) {
            PdfFormatAdapter().read(ByteArray(0), RelativePath("book.pdf"))
        }
    }

    private fun assertFailure(reason: PdfFormatException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Expected PdfFormatException($reason)")
        } catch (failure: PdfFormatException) {
            assertEquals(reason, failure.reason)
        }
    }
}
