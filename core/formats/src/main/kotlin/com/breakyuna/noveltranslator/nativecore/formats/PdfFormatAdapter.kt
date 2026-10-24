package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.BinaryFormatAdapter
import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWarning
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.tom_roush.fontbox.ttf.TTFParser
import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.Writer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val FONT_SIZE = 11f
private const val LINE_HEIGHT = 1.45f
private const val PAGE_MARGIN = 36f

/**
 * PDF adapter for page-level extraction and replacement.
 *
 * Reading emits exactly one unit per page, in PDF page order, with the position-sorted text layer
 * (where PDFBox can derive it). The unit `extraField` carries the page number, the explicit
 * `keep`/`omit`/`translate` decision and a digest of the extracted text. Pages without a text layer
 * default to `keep`, so scanned pages are never silently discarded.
 *
 * Writing is a page-level replacement writer, not a text-in-place editor:
 *
 * - `keep` copies the original page (contents, resources, annotations, boxes, rotation).
 * - `omit` drops the page.
 * - `translate` writes the translation as a new text block on a same-size page, in page order.
 *   Original page graphics and the original text layer are not retained on a translated page.
 * - Bilingual output appends the source as a second paragraph after a blank line; content that
 *   does not fit continues on additional pages of the same size.
 *
 * Text is laid out statically from the crop box with a single Unicode font. Rotation values are
 * preserved, but text is drawn in unrotated content coordinates, so pages with a non-zero `/Rotate`
 * show the replacement text rotated with the page. This limitation is intentional and must not be
 * reported as layout fidelity.
 *
 * PDFBox-Android resources are initialized through [PdfBoxRuntime]; importing or writing a PDF
 * without that initialization fails with [PdfFormatException.Reason.RESOURCES]. Plain JVM unit
 * tests cannot exercise this class because the AAR references `android.*` classes; the pure page
 * codec, plan, layout and bounds are covered separately.
 */
class PdfFormatAdapter : BinaryFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.PDF

    override fun read(bytes: ByteArray, path: RelativePath): FormatDocument {
        PdfFormatBounds.checkInput(bytes)
        PdfFormatBounds.checkPath(path)
        requirePdfBoxReady()
        val warnings = ArrayList<FormatWarning>()
        val document = openDocument(bytes)
        try {
            val pageCount = pageCount(document)
            PdfFormatBounds.checkPageCount(pageCount)
            val collector = try {
                PageTextCollector()
            } catch (_: IOException) {
                throw PdfFormatException(PdfFormatException.Reason.EXTRACT)
            } catch (_: RuntimeException) {
                throw PdfFormatException(PdfFormatException.Reason.EXTRACT)
            }
            collector.sortByPosition = true
            try {
                collector.writeText(document, NullWriter)
            } catch (failure: PdfFormatException) {
                throw failure
            } catch (_: IOException) {
                throw PdfFormatException(PdfFormatException.Reason.EXTRACT)
            } catch (_: RuntimeException) {
                throw PdfFormatException(PdfFormatException.Reason.EXTRACT)
            }
            if (collector.pages.size != pageCount) {
                throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
            }

            val units = ArrayList<FormatUnit>(pageCount)
            var textPages = 0
            var emptyPages = 0
            var totalCharacters = 0L
            for (index in 0 until pageCount) {
                val page = index + 1
                val source = PdfTextLayout.sanitize(collector.pages[index].toString()).trim()
                PdfFormatBounds.checkPageText(source.length, page)
                totalCharacters += source.length
                if (totalCharacters > PdfFormatBounds.MAX_TOTAL_TEXT_CHARS) {
                    throw PdfFormatException(PdfFormatException.Reason.LIMIT, page)
                }
                if (source.isBlank()) emptyPages++ else textPages++
                units.add(
                    FormatUnit(
                        row = page,
                        source = source,
                        translation = "",
                        status = TextItemStatus.NONE,
                        tag = PdfPageCodec.tag(page),
                        extraField = PdfPageCodec.encode(
                            page,
                            PdfPageCodec.defaultDecision(source),
                            containerSha256Hex(source),
                        ),
                    ),
                )
            }
            if (textPages == 0) {
                warnings.add(
                    FormatWarning(
                        "pdf.no_text_layer",
                        path.value,
                        null,
                        "No page has an extractable text layer; every page defaults to keep",
                    ),
                )
            } else if (emptyPages > 0) {
                warnings.add(
                    FormatWarning(
                        "pdf.empty_pages",
                        path.value,
                        null,
                        "$emptyPages page(s) have no extractable text and default to keep",
                    ),
                )
            }
            val pdfVersion = documentVersion(document)
            val metadata = buildJsonObject {
                put("v", 1)
                put("pages", pageCount)
                put("text_pages", textPages)
                put("empty_pages", emptyPages)
                if (pdfVersion != null) put("pdf_version", pdfVersion)
            }.toString()
            return FormatDocument(format, units, warnings, "", metadata)
        } finally {
            runCatching { document.close() }
        }
    }

    override fun write(
        original: ByteArray,
        units: List<FormatUnit>,
        options: FormatWriteOptions,
    ): ByteArray {
        PdfFormatBounds.checkInput(original)
        if (units.isEmpty()) return original
        requirePdfBoxReady()
        val source = openDocument(original)
        var loadedFont: LoadedPdfFont? = null
        val output = PDDocument()
        try {
            val pageCount = pageCount(source)
            PdfFormatBounds.checkPageCount(pageCount)
            val actions = PdfPagePlan.plan(units, pageCount, options)
            if (actions.none { it !is PdfPageAction.Keep }) return original
            val translated = actions.filterIsInstance<PdfPageAction.Translate>()
            if (translated.isEmpty() && actions.all { it is PdfPageAction.Omit }) {
                throw PdfFormatException(PdfFormatException.Reason.EMPTY_OUTPUT)
            }
            if (translated.isNotEmpty()) {
                loadedFont = openFont(
                    output,
                    PdfPagePlan.requiredCodePoints(actions),
                    translated.first().page,
                )
            }
            copyDocumentSettings(source, output)
            var outputPages = 0
            for (action in actions) {
                when (action) {
                    is PdfPageAction.Keep -> {
                        outputPages++
                        PdfFormatBounds.checkOutputPages(outputPages)
                        output.addPage(importPage(output, source.getPage(action.page - 1)))
                    }
                    is PdfPageAction.Omit -> Unit
                    is PdfPageAction.Translate -> {
                        val font = loadedFont ?: throw PdfFormatException(PdfFormatException.Reason.FONT, action.page)
                        outputPages = renderReplacement(output, source, action, font.font, outputPages)
                    }
                }
            }
            if (outputPages == 0) throw PdfFormatException(PdfFormatException.Reason.EMPTY_OUTPUT)
            val buffer = ByteArrayOutputStream(
                minOf(original.size + 64 * 1024, PdfFormatBounds.MAX_OUTPUT_BYTES),
            )
            output.save(buffer)
            val result = buffer.toByteArray()
            PdfFormatBounds.checkOutputSize(result.size)
            return result
        } catch (failure: PdfFormatException) {
            throw failure
        } catch (_: IOException) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        } catch (_: RuntimeException) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
        } finally {
            runCatching { loadedFont?.close() }
            runCatching { output.close() }
            runCatching { source.close() }
        }
    }

    private fun requirePdfBoxReady() {
        if (!PdfBoxRuntime.isInitialized()) {
            throw PdfFormatException(PdfFormatException.Reason.RESOURCES)
        }
    }

    private fun openDocument(bytes: ByteArray): PDDocument {
        val document = try {
            PDDocument.load(bytes)
        } catch (_: InvalidPasswordException) {
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

    private fun pageCount(document: PDDocument): Int = try {
        document.numberOfPages
    } catch (_: IOException) {
        throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
    } catch (_: RuntimeException) {
        throw PdfFormatException(PdfFormatException.Reason.CORRUPT)
    }

    private fun documentVersion(document: PDDocument): String? = try {
        document.documentCatalog.version?.takeIf { version ->
            version.isNotEmpty() && version.length <= 16 && version.none(Char::isISOControl)
        }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Keeps a page through [PDDocument.importPage], which copies content, boxes, rotation and
     * annotations and preserves direct page resources. PDFBox's own PageExtractor does the same;
     * only resources inherited from the page tree have to be copied explicitly.
     */
    private fun importPage(output: PDDocument, page: PDPage): PDPage {
        val imported = output.importPage(page)
        val resources = page.resources
        if (resources != null && !page.cosObject.containsKey(COSName.RESOURCES)) {
            imported.resources = resources
        }
        return imported
    }

    private fun renderReplacement(
        output: PDDocument,
        source: PDDocument,
        action: PdfPageAction.Translate,
        font: PDType0Font,
        outputPages: Int,
    ): Int {
        val original = source.getPage(action.page - 1)
        val crop = effectiveBox(original)
        if (!(crop.width > 0f) || !(crop.height > 0f)) {
            throw PdfFormatException(PdfFormatException.Reason.CORRUPT, action.page)
        }
        val media = copyRectangle(original.mediaBox)
        val margin = minOf(PAGE_MARGIN, crop.width / 8f, crop.height / 8f).coerceAtLeast(1f)
        val contentWidth = crop.width - 2f * margin
        val leading = FONT_SIZE * LINE_HEIGHT
        val linesPerPage = (((crop.height - 2f * margin) / leading).toInt()).coerceAtLeast(1)
        requireGlyphs(font, action.text, action.page)
        val lines = try {
            PdfTextLayout.wrap(action.text, contentWidth, measure(font, action.page))
        } catch (failure: PdfFormatException) {
            throw failure
        } catch (_: IllegalArgumentException) {
            throw PdfFormatException(PdfFormatException.Reason.FONT, action.page)
        }
        if (lines.size > linesPerPage * PdfFormatBounds.MAX_CONTINUATION_PAGES) {
            throw PdfFormatException(PdfFormatException.Reason.LIMIT, action.page)
        }
        var count = outputPages
        var index = 0
        while (index < lines.size) {
            count++
            PdfFormatBounds.checkOutputPages(count)
            val page = PDPage(media)
            page.cropBox = copyRectangle(crop)
            page.rotation = original.rotation
            output.addPage(page)
            val end = minOf(lines.size, index + linesPerPage)
            PDPageContentStream(output, page).use { stream ->
                stream.beginText()
                stream.setFont(font, FONT_SIZE)
                stream.newLineAtOffset(crop.lowerLeftX + margin, crop.upperRightY - margin - FONT_SIZE)
                var firstLine = true
                for (lineIndex in index until end) {
                    if (!firstLine) stream.newLineAtOffset(0f, -leading)
                    firstLine = false
                    val line = lines[lineIndex]
                    if (line.isNotEmpty()) stream.showText(line)
                }
                stream.endText()
            }
            index = end
        }
        return count
    }

    private fun measure(font: PDType0Font, page: Int): (Int) -> Float = { codePoint ->
        val text = String(Character.toChars(codePoint))
        val width = try {
            font.getStringWidth(text)
        } catch (_: IllegalArgumentException) {
            throw PdfFormatException(PdfFormatException.Reason.FONT, page)
        } catch (_: IOException) {
            throw PdfFormatException(PdfFormatException.Reason.FONT, page)
        }
        width / 1000f * FONT_SIZE
    }

    private fun requireGlyphs(font: PDType0Font, text: String, page: Int) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            if (codePoint >= 0x20 && codePoint != 0xFEFF && codePoint !in 0x7F..0x9F) {
                // The layout turns every whitespace run into an ASCII space token.
                val glyph = if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                    ' '.code
                } else {
                    codePoint
                }
                val supported = try {
                    font.hasGlyph(glyph)
                } catch (_: IOException) {
                    false
                }
                if (!supported) throw PdfFormatException(PdfFormatException.Reason.FONT, page)
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun openFont(
        document: PDDocument,
        required: Set<Int>,
        firstPage: Int,
    ): LoadedPdfFont {
        for (candidate in PdfBoxRuntime.fontCandidates()) {
            val bytes = candidate.bytes
            val file = candidate.file
            val loaded = try {
                when {
                    bytes != null -> openFontBytes(document, bytes, required)
                    file != null -> openFontFile(document, file, required)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            if (loaded != null) return loaded
        }
        throw PdfFormatException(PdfFormatException.Reason.FONT, firstPage)
    }

    private fun openFontBytes(
        document: PDDocument,
        bytes: ByteArray,
        required: Set<Int>,
    ): LoadedPdfFont? {
        if (!fontCovers(bytes, required)) return null
        val font = PDType0Font.load(document, ByteArrayInputStream(bytes), true)
        return LoadedPdfFont(font)
    }

    private fun openFontFile(
        document: PDDocument,
        file: File,
        required: Set<Int>,
    ): LoadedPdfFont? {
        if (!file.isFile || file.length() <= 0) return null
        if (file.name.endsWith(".ttc", ignoreCase = true)) {
            return openCollectionFont(document, file, required)
        }
        if (!fontCovers(file, required)) return null
        return LoadedPdfFont(PDType0Font.load(document, file))
    }

    private fun openCollectionFont(
        document: PDDocument,
        file: File,
        required: Set<Int>,
    ): LoadedPdfFont? {
        val collection = try {
            TrueTypeCollection(file)
        } catch (_: Exception) {
            return null
        }
        var chosen: TrueTypeFont? = null
        try {
            collection.processAllFonts(object : TrueTypeCollection.TrueTypeFontProcessor {
                override fun process(ttf: TrueTypeFont) {
                    if (chosen == null && covers(ttf, required)) chosen = ttf
                }
            })
        } catch (_: Exception) {
            runCatching { collection.close() }
            return null
        }
        val selected = chosen
        if (selected == null) {
            runCatching { collection.close() }
            return null
        }
        return try {
            LoadedPdfFont(PDType0Font.load(document, selected, true), listOf(collection))
        } catch (failure: Exception) {
            runCatching { collection.close() }
            throw failure
        }
    }

    private fun fontCovers(file: File, required: Set<Int>): Boolean {
        val font = try {
            TTFParser().parse(file)
        } catch (_: Exception) {
            return false
        }
        return try {
            covers(font, required)
        } finally {
            runCatching { font.close() }
        }
    }

    private fun fontCovers(bytes: ByteArray, required: Set<Int>): Boolean {
        val font = try {
            TTFParser().parse(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            return false
        }
        return try {
            covers(font, required)
        } finally {
            runCatching { font.close() }
        }
    }

    private fun covers(font: TrueTypeFont, required: Set<Int>): Boolean {
        if (required.isEmpty()) return true
        val cmap = try {
            font.getUnicodeCmapLookup(false)
        } catch (_: IOException) {
            return false
        } ?: return false
        for (codePoint in required) {
            val glyph = try {
                cmap.getGlyphId(codePoint)
            } catch (_: RuntimeException) {
                0
            }
            if (glyph == 0) return false
        }
        return true
    }

    private fun effectiveBox(page: PDPage): PDRectangle {
        val crop = page.cropBox
        if (crop.width > 0f && crop.height > 0f) return crop
        return page.mediaBox
    }

    private fun copyRectangle(box: PDRectangle): PDRectangle =
        PDRectangle(box.lowerLeftX, box.lowerLeftY, box.width, box.height)

    private fun copyDocumentSettings(source: PDDocument, output: PDDocument) {
        try {
            source.documentCatalog.version?.let { version ->
                if (version.isNotEmpty() && version.length <= 16 && version.none(Char::isISOControl)) {
                    output.documentCatalog.version = version
                }
            }
        } catch (_: RuntimeException) {
            // Version metadata is optional; never block the conversion on it.
        }
        try {
            val original = source.documentInformation
            val copy = PDDocumentInformation()
            original.title?.let { copy.title = it }
            original.author?.let { copy.author = it }
            original.subject?.let { copy.subject = it }
            original.keywords?.let { copy.keywords = it }
            original.creator?.let { copy.creator = it }
            original.producer?.let { copy.producer = it }
            output.documentInformation = copy
        } catch (_: RuntimeException) {
            // Document information is optional; the page tree is the part that must survive.
        }
    }

    private class LoadedPdfFont(
        val font: PDType0Font,
        private val owners: List<Closeable> = emptyList(),
    ) : Closeable {
        override fun close() {
            for (owner in owners) runCatching { owner.close() }
        }
    }

    /**
     * One-pass text extraction that keeps PDFBox's per-page grouping: `startPage` starts a new
     * builder, so no page-separator heuristic or repeated document scan is needed. Text is counted
     * while it is appended, so a highly compressed PDF cannot allocate far beyond the budgets
     * before the caller notices.
     */
    private class PageTextCollector : PDFTextStripper() {
        val pages = ArrayList<StringBuilder>()
        private var current: StringBuilder? = null
        private var totalCharacters = 0L

        override fun startPage(page: PDPage) {
            val builder = StringBuilder()
            pages.add(builder)
            current = builder
            super.startPage(page)
        }

        override fun writeString(text: String) {
            appendText(text)
        }

        override fun writeLineSeparator() {
            appendText("\n")
        }

        override fun writeWordSeparator() {
            appendText(" ")
        }

        override fun endPage(page: PDPage) {
            current = null
            super.endPage(page)
        }

        private fun appendText(text: String) {
            val builder = current ?: return
            if (builder.length + text.length > PdfFormatBounds.MAX_UNIT_CHARS) {
                throw PdfFormatException(PdfFormatException.Reason.LIMIT, pages.size)
            }
            val nextTotal = totalCharacters + text.length
            if (nextTotal > PdfFormatBounds.MAX_TOTAL_TEXT_CHARS) {
                throw PdfFormatException(PdfFormatException.Reason.LIMIT, pages.size)
            }
            totalCharacters = nextTotal
            builder.append(text)
        }
    }

    /** PDFTextStripper still writes page/article separators for subclasses; discard them. */
    private object NullWriter : Writer() {
        override fun write(buffer: CharArray, offset: Int, count: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
