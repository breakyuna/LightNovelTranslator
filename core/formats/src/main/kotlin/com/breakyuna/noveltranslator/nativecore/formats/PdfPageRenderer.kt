package com.breakyuna.noveltranslator.nativecore.formats

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Renders one original PDF page to PNG bytes on demand.
 *
 * This is deliberately a single-page renderer: the page decision dialog asks for the page the
 * user is previewing, never for the whole document. The long edge is bounded to
 * [PdfPageRenderBounds.MAX_LONG_EDGE_PX] pixels and the PNG to
 * [PdfPageRenderBounds.MAX_PNG_BYTES] bytes. The source page is only read; the PDF asset is not
 * rewritten.
 *
 * Rendering does file/codec work and must be called from a background dispatcher. The renderer
 * closes its [PDDocument], recycles the temporary Android bitmap and writes no temporary file, so
 * a failed or successful render does not retain native PDFBox state.
 */
fun interface PdfPageImageRenderer {
    /**
     * @throws PdfFormatException with a typed reason when the input, request, page tree or render
     * result is outside the accepted bounds.
     */
    fun renderPage(bytes: ByteArray, page: Int, expectedPageCount: Int): ByteArray
}

class PdfPageRenderer : PdfPageImageRenderer {
    override fun renderPage(bytes: ByteArray, page: Int, expectedPageCount: Int): ByteArray {
        PdfFormatBounds.checkInput(bytes)
        PdfPageRenderBounds.checkPageRequest(page, expectedPageCount)
        if (!PdfBoxRuntime.isInitialized()) throw PdfFormatException(PdfFormatException.Reason.RESOURCES, page)
        val document = openDocument(bytes)
        try {
            val pageCount = try {
                document.numberOfPages
            } catch (_: IOException) {
                throw PdfFormatException(PdfFormatException.Reason.CORRUPT, page)
            } catch (_: RuntimeException) {
                throw PdfFormatException(PdfFormatException.Reason.CORRUPT, page)
            }
            PdfFormatBounds.checkPageCount(pageCount)
            if (pageCount != expectedPageCount) throw PdfFormatException(PdfFormatException.Reason.BINDING, page)
            val sourcePage = try {
                document.getPage(page - 1)
            } catch (_: IOException) {
                throw PdfFormatException(PdfFormatException.Reason.CORRUPT, page)
            } catch (_: RuntimeException) {
                throw PdfFormatException(PdfFormatException.Reason.CORRUPT, page)
            }
            val scale = pageScale(sourcePage, page)
            val bitmap = try {
                val renderer = PDFRenderer(document)
                // The thumbnail is already downscaled; device-side image subsampling keeps
                // embedded scan pages from being decoded at full resolution first.
                renderer.setSubsamplingAllowed(true)
                renderer.renderImage(page - 1, scale)
            } catch (_: IOException) {
                throw PdfFormatException(PdfFormatException.Reason.RENDER, page)
            } catch (_: RuntimeException) {
                throw PdfFormatException(PdfFormatException.Reason.RENDER, page)
            }
            try {
                PdfPageRenderBounds.checkPixels(bitmap.width, bitmap.height, page)
                val output = BoundedPngOutput(PdfPageRenderBounds.MAX_PNG_BYTES)
                val compressed = try {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                }
                if (!compressed || output.exceeded) {
                    throw PdfFormatException(
                        if (output.exceeded) PdfFormatException.Reason.LIMIT else PdfFormatException.Reason.RENDER,
                        page,
                    )
                }
                val png = output.toByteArray()
                PdfPageRenderBounds.checkPng(png, page)
                return png
            } finally {
                bitmap.recycle()
            }
        } catch (failure: PdfFormatException) {
            throw failure
        } catch (_: IOException) {
            throw PdfFormatException(PdfFormatException.Reason.RENDER, page)
        } catch (_: RuntimeException) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT, page)
        } finally {
            runCatching { document.close() }
        }
    }

    private fun openDocument(bytes: ByteArray): PDDocument {
        val document = try {
            PDDocument.load(bytes)
        } catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw PdfFormatException(PdfFormatException.Reason.ENCRYPTED)
        } catch (_: IOException) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        } catch (_: RuntimeException) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        }
        if (document.isEncrypted) {
            runCatching { document.close() }
            throw PdfFormatException(PdfFormatException.Reason.ENCRYPTED)
        }
        return document
    }

    private fun pageScale(page: PDPage, pageNumber: Int): Float {
        val box = try {
            page.cropBox
        } catch (_: RuntimeException) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT, pageNumber)
        }
        if (box.width <= 0f || box.height <= 0f) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT, pageNumber)
        }
        return PdfPageRenderBounds.scaleFor(box.width, box.height, page.rotation, pageNumber)
    }

    /**
     * Collects PNG output without ever growing past the compressed image budget. PDFBox may catch
     * the resulting IOException and merely return false, so [exceeded] is checked separately.
     */
    private class BoundedPngOutput(private val limit: Int) :
        ByteArrayOutputStream(minOf(limit, 64 * 1024)) {
        var exceeded: Boolean = false
            private set

        override fun write(value: Int) {
            guard(1)
            super.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            guard(length)
            super.write(buffer, offset, length)
        }

        private fun guard(length: Int) {
            if (length < 0 || size().toLong() + length.toLong() > limit.toLong()) {
                exceeded = true
                throw IOException("PNG size limit")
            }
        }
    }
}

/** Pure request/render bounds shared by the renderer and its JVM tests. */
internal object PdfPageRenderBounds {
    /** Long-edge cap for one original-page thumbnail. */
    const val MAX_LONG_EDGE_PX = 1_200

    /** Secondary bitmap bound; a 1200 x 1200 thumbnail is the largest accepted image. */
    const val MAX_PIXELS = MAX_LONG_EDGE_PX * MAX_LONG_EDGE_PX

    /** Compressed PNG cap. */
    const val MAX_PNG_BYTES = 8 * 1024 * 1024

    /** Extreme PDF boxes are rejected before PDFBox allocates a bitmap for them. */
    const val MAX_PAGE_POINTS = 20_000f

    /** PDFBox's `scale` argument is relative to 72 dpi. */
    const val MAX_SCALE = 2f

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun checkPageRequest(page: Int, pageCount: Int) {
        if (pageCount <= 0) throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        if (pageCount > PdfFormatBounds.MAX_PAGES) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
        if (page < 1 || page > pageCount) throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
    }

    /**
     * Scale that keeps the rotated page's long edge within the pixel budget. A two-pixel margin
     * absorbs PDFBox's rounding, and the absolute scale cap keeps small pages from being inflated.
     */
    fun scaleFor(widthPoints: Float, heightPoints: Float, rotation: Int, page: Int? = null): Float {
        val (width, height) = rotatedDimensions(widthPoints, heightPoints, rotation, page)
        val longEdge = maxOf(width, height)
        if (!longEdge.isFinite() || longEdge <= 0f || longEdge > MAX_PAGE_POINTS) {
            throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
        }
        val scale = minOf(MAX_SCALE, (MAX_LONG_EDGE_PX - 2f) / longEdge)
        if (!scale.isFinite() || scale <= 0f) throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
        return scale
    }

    /** Rounded pixel size used by tests; the renderer validates the bitmap PDFBox returns. */
    fun pixelSize(widthPoints: Float, heightPoints: Float, rotation: Int, page: Int? = null): Pair<Int, Int> {
        val scale = scaleFor(widthPoints, heightPoints, rotation, page)
        val (width, height) = rotatedDimensions(widthPoints, heightPoints, rotation, page)
        return ceilToInt(width * scale) to ceilToInt(height * scale)
    }

    fun checkPixels(width: Int, height: Int, page: Int? = null) {
        if (width <= 0 || height <= 0) throw PdfFormatException(PdfFormatException.Reason.RENDER, page)
        if (width > MAX_LONG_EDGE_PX || height > MAX_LONG_EDGE_PX ||
            width.toLong() * height.toLong() > MAX_PIXELS.toLong()) {
            throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
        }
    }

    fun checkPng(bytes: ByteArray, page: Int? = null) {
        checkPngSize(bytes.size, page)
        if (bytes.size < PNG_SIGNATURE.size) throw PdfFormatException(PdfFormatException.Reason.RENDER, page)
        for (index in PNG_SIGNATURE.indices) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                throw PdfFormatException(PdfFormatException.Reason.RENDER, page)
            }
        }
    }

    fun checkPngSize(size: Int, page: Int? = null) {
        if (size <= PNG_SIGNATURE.size) throw PdfFormatException(PdfFormatException.Reason.RENDER, page)
        if (size > MAX_PNG_BYTES) throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
    }

    private fun rotatedDimensions(
        widthPoints: Float,
        heightPoints: Float,
        rotation: Int,
        page: Int?,
    ): Pair<Float, Float> {
        if (!widthPoints.isFinite() || !heightPoints.isFinite() ||
            widthPoints <= 0f || heightPoints <= 0f ||
            widthPoints > MAX_PAGE_POINTS || heightPoints > MAX_PAGE_POINTS) {
            throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
        }
        val normalized = ((rotation % 360) + 360) % 360
        return if (normalized % 180 == 90) heightPoints to widthPoints else widthPoints to heightPoints
    }

    private fun ceilToInt(value: Float): Int {
        val rounded = kotlin.math.ceil(value.toDouble())
        if (rounded > Int.MAX_VALUE.toDouble()) {
            throw PdfFormatException(PdfFormatException.Reason.LIMIT)
        }
        return rounded.toInt()
    }
}
