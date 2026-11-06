package com.breakyuna.noveltranslator.nativecore.formats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure render-bounds coverage. PDFBox rasterization needs Android `Bitmap` and an initialized
 * resource loader, so actual PNG pixels/Document lifecycle remain a device or authorised CI gap;
 * this source set verifies every fail-before-render bound and PNG envelope check.
 */
class PdfPageRenderBoundsTest {
    @Test fun pageRequestRejectsZeroOutOfRangeAndTooManyPages() {
        PdfPageRenderBounds.checkPageRequest(1, 1)
        PdfPageRenderBounds.checkPageRequest(5_000, 5_000)
        assertFailure(PdfFormatException.Reason.CORRUPT, null) {
            PdfPageRenderBounds.checkPageRequest(1, 0)
        }
        assertFailure(PdfFormatException.Reason.LIMIT, null) {
            PdfPageRenderBounds.checkPageRequest(1, PdfFormatBounds.MAX_PAGES + 1)
        }
        assertFailure(PdfFormatException.Reason.LIMIT, 0) {
            PdfPageRenderBounds.checkPageRequest(0, 3)
        }
        assertFailure(PdfFormatException.Reason.LIMIT, 4) {
            PdfPageRenderBounds.checkPageRequest(4, 3)
        }
    }

    @Test fun portraitAndRotatedPagesStayInsideTheLongEdgeBound() {
        val portrait = PdfPageRenderBounds.pixelSize(595f, 842f, 0)
        val landscape = PdfPageRenderBounds.pixelSize(595f, 842f, 90)
        val square = PdfPageRenderBounds.pixelSize(1_200f, 1_200f, 0)
        for ((width, height) in listOf(portrait, landscape, square)) {
            assertTrue("width=$width", width in 1..PdfPageRenderBounds.MAX_LONG_EDGE_PX)
            assertTrue("height=$height", height in 1..PdfPageRenderBounds.MAX_LONG_EDGE_PX)
            assertTrue(width.toLong() * height <= PdfPageRenderBounds.MAX_PIXELS.toLong())
        }
        assertTrue(kotlin.math.abs(portrait.first - landscape.second) <= 1)
        assertTrue(kotlin.math.abs(portrait.second - landscape.first) <= 1)
    }

    @Test fun smallAndExtremelyLargePagesAreBothBounded() {
        val tiny = PdfPageRenderBounds.pixelSize(10f, 20f, 0)
        assertTrue(tiny.first <= 20 && tiny.second <= 40)
        val extreme = PdfPageRenderBounds.pixelSize(20_000f, 100f, 0)
        assertTrue(extreme.first <= PdfPageRenderBounds.MAX_LONG_EDGE_PX)
        assertTrue(extreme.second >= 1)
    }

    @Test fun invalidPageGeometryIsTyped() {
        assertFailure(PdfFormatException.Reason.LIMIT, null) {
            PdfPageRenderBounds.scaleFor(0f, 100f, 0)
        }
        assertFailure(PdfFormatException.Reason.LIMIT, null) {
            PdfPageRenderBounds.scaleFor(Float.NaN, 100f, 0)
        }
        assertFailure(PdfFormatException.Reason.LIMIT, null) {
            PdfPageRenderBounds.scaleFor(PdfPageRenderBounds.MAX_PAGE_POINTS + 1f, 100f, 0)
        }
    }

    @Test fun bitmapAndPngBudgetsAreCheckedBeforeReturningBytes() {
        PdfPageRenderBounds.checkPixels(1_200, 1_200)
        assertFailure(PdfFormatException.Reason.LIMIT, null) {
            PdfPageRenderBounds.checkPixels(1_201, 1)
        }
        assertFailure(PdfFormatException.Reason.RENDER, null) {
            PdfPageRenderBounds.checkPixels(0, 1)
        }
        PdfPageRenderBounds.checkPngSize(PdfPageRenderBounds.MAX_PNG_BYTES)
        assertFailure(PdfFormatException.Reason.LIMIT, null) {
            PdfPageRenderBounds.checkPngSize(PdfPageRenderBounds.MAX_PNG_BYTES + 1)
        }
        assertFailure(PdfFormatException.Reason.RENDER, null) {
            PdfPageRenderBounds.checkPngSize(8)
        }
    }

    @Test fun pngSignatureIsRequired() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(8)
        PdfPageRenderBounds.checkPng(png)
        assertFailure(PdfFormatException.Reason.RENDER, 2) {
            PdfPageRenderBounds.checkPng(ByteArray(16), 2)
        }
    }

    @Test fun headerlessInputAndOutOfRangePageFailBeforePdfBox() {
        val renderer = PdfPageRenderer()
        assertFailure(PdfFormatException.Reason.CORRUPT, null) {
            renderer.renderPage(ByteArray(0), 1, 1)
        }
        assertFailure(PdfFormatException.Reason.CORRUPT, null) {
            renderer.renderPage("not a pdf".toByteArray(), 1, 1)
        }
        val header = "%PDF-1.7".toByteArray()
        assertFailure(PdfFormatException.Reason.LIMIT, 0) {
            renderer.renderPage(header, 0, 1)
        }
        assertFailure(PdfFormatException.Reason.LIMIT, 2) {
            renderer.renderPage(header, 2, 1)
        }
    }

    private fun assertFailure(reason: PdfFormatException.Reason, page: Int?, block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("Expected PdfFormatException($reason)")
        } catch (failure: PdfFormatException) {
            assertEquals(reason, failure.reason)
            assertEquals(page, failure.page)
        }
    }
}
