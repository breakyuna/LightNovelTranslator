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
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabelRange
import com.tom_roush.pdfbox.pdmodel.common.PDPageLabels
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val FONT_SIZE = 11f
private const val LINE_HEIGHT = 1.45f
private const val PAGE_MARGIN = 36f
private const val MAX_XMP_BYTES = 16 * 1024 * 1024
private const val MAX_OUTLINE_ITEMS = 10_000
private const val MAX_OUTLINE_DEPTH = 64
private const val MAX_OUTLINE_TITLE_CHARS = 4_096
private const val MAX_PAGE_LABEL_CHARS = 4_096
private val AF_KEY: COSName = COSName.getPDFName("AF")

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
 * Document-level structure is preserved when pages change: PDFBox document information and XMP
 * metadata are copied, page labels are projected onto the retained pages, and outline/bookmark
 * items are rebuilt in output page order. Bookmarks whose page was omitted (or whose destination
 * cannot be resolved locally) are removed; valid child bookmarks are promoted rather than dropped
 * with an omitted parent, so no output destination can point past the new page tree. Outline
 * destination fit parameters are normalized to a plain page destination.
 *
 * Interactive form fields and embedded files/attachments are not rebuildable from page copies
 * alone. A page-changing write therefore fails with [PdfFormatException.Reason.UNSUPPORTED]
 * instead of silently producing a broken form or attachment catalog; the caller keeps the
 * original asset. The original bytes are also returned unchanged when every page is kept. Reads
 * add explicit warnings for those structures so a UI can explain why export is refusing.
 *
 * Text is laid out statically from the crop box with a single Unicode font. Rotation values are
 * preserved, but text is drawn in unrotated content coordinates, so pages with a non-zero `/Rotate`
 * show the replacement text rotated with the page. This limitation is intentional and must not be
 * reported as layout fidelity.
 *
 * PDFBox-Android resources are initialized through [PdfBoxRuntime]; importing or writing a PDF
 * without that initialization fails with [PdfFormatException.Reason.RESOURCES]. Plain JVM unit
 * tests cannot exercise this class because the AAR references `android.*` classes; the pure page
 * codec, plan, layout, bounds, outline/page-label projection and document-information copy are
 * covered separately.
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
            val hasForms = hasFormFields(document)
            val hasAttachments = hasEmbeddedAttachments(document)
            if (hasForms) {
                warnings.add(FormatWarning(
                    "pdf.forms_protected",
                    path.value,
                    null,
                    "Document has form fields; a page-changing export is refused to keep the original intact",
                ))
            }
            if (hasAttachments) {
                warnings.add(FormatWarning(
                    "pdf.attachments_protected",
                    path.value,
                    null,
                    "Document has embedded files; a page-changing export is refused to keep the original intact",
                ))
            }
            val pdfVersion = documentVersion(document)
            val metadata = buildJsonObject {
                put("v", 1)
                put("pages", pageCount)
                put("text_pages", textPages)
                put("empty_pages", emptyPages)
                if (pdfVersion != null) put("pdf_version", pdfVersion)
                if (hasForms) put("forms", true)
                if (hasAttachments) put("attachments", true)
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
            PdfWriteGuard.check(hasFormFields(source), hasEmbeddedAttachments(source), actions)
            val outputPageBySource = IntArray(pageCount)
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
                        outputPageBySource[action.page - 1] = outputPages
                        output.addPage(importPage(output, source.getPage(action.page - 1)))
                    }
                    is PdfPageAction.Omit -> Unit
                    is PdfPageAction.Translate -> {
                        val font = loadedFont ?: throw PdfFormatException(PdfFormatException.Reason.FONT, action.page)
                        val startPage = outputPages + 1
                        outputPages = renderReplacement(output, source, action, font.font, outputPages)
                        outputPageBySource[action.page - 1] = startPage
                    }
                }
            }
            if (outputPages == 0) throw PdfFormatException(PdfFormatException.Reason.EMPTY_OUTPUT)
            val mapping = PdfPageOutputMap.fromOutputPages(outputPageBySource)
            applyPageLabels(source, output, mapping)
            applyOutline(source, output, mapping)
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
        copyDocumentInformation(source, output)
        copyXmpMetadata(source, output)
        copyCatalogPreferences(source, output)
    }

    /**
     * Copies every entry in the document information dictionary, including custom keys, dates and
     * the Trapped name. The old writer recreated six standard strings and silently dropped the rest.
     */
    private fun copyDocumentInformation(source: PDDocument, output: PDDocument) {
        try {
            output.documentInformation = copyPdfDocumentInformation(source.documentInformation)
        } catch (_: RuntimeException) {
            // Document information is optional; the page tree is the part that must survive.
        }
    }

    /**
     * Copies the catalog XMP packet byte for byte. XMP is not reconstructed from document
     * information because that would lose producer-specific schemas.
     */
    private fun copyXmpMetadata(source: PDDocument, output: PDDocument) {
        try {
            val original = source.documentCatalog.getMetadata() ?: return
            val bytes = original.exportXMPMetadata().use { readBounded(it, MAX_XMP_BYTES) }
            if (bytes.isEmpty()) return
            val copy = PDMetadata(output)
            copy.importXMPMetadata(bytes)
            output.documentCatalog.setMetadata(copy)
        } catch (failure: PdfFormatException) {
            throw failure
        } catch (_: Exception) {
            // Broken optional XMP must not destroy the page tree.
        }
    }

    /** Page mode/layout/language/viewer preferences are safe catalog-level settings to keep. */
    private fun copyCatalogPreferences(source: PDDocument, output: PDDocument) {
        try {
            val catalog = source.documentCatalog
            val dictionary = catalog.cosObject
            if (dictionary.containsKey(COSName.PAGE_MODE)) {
                output.documentCatalog.setPageMode(catalog.getPageMode())
            }
            if (dictionary.containsKey(COSName.PAGE_LAYOUT)) {
                output.documentCatalog.setPageLayout(catalog.getPageLayout())
            }
            catalog.getLanguage()?.let { language ->
                if (language.length <= 64 && language.none(Char::isISOControl)) {
                    output.documentCatalog.setLanguage(language)
                }
            }
            val viewerPreferences = dictionary.getDictionaryObject(COSName.VIEWER_PREFERENCES)
            if (viewerPreferences is COSDictionary) {
                output.documentCatalog.cosObject.setItem(
                    COSName.VIEWER_PREFERENCES,
                    COSDictionary(viewerPreferences),
                )
            }
        } catch (_: RuntimeException) {
            // Optional catalog preferences never block the page conversion.
        }
    }

    /**
     * Rebuilds page labels so every retained page keeps the label it had in the source. Each source
     * page becomes a prefix-only range on its output page; a translated page's continuation pages
     * inherit the same range. Omitted pages consume no output label range.
     */
    private fun applyPageLabels(
        source: PDDocument,
        output: PDDocument,
        mapping: PdfPageOutputMap,
    ) {
        val labels: List<String?> = try {
            val pageLabels = source.documentCatalog.getPageLabels() ?: return
            pageLabels.getLabelsByPageIndices().map { it }
        } catch (_: Exception) {
            return
        }
        val assignments = PdfPageLabelPlan.assignments(labels, mapping)
        if (assignments.isEmpty()) return
        try {
            val target = PDPageLabels(output)
            for (assignment in assignments) {
                val range = PDPageLabelRange()
                range.setPrefix(assignment.label)
                range.setStyle(null)
                target.setLabelItem(assignment.outputPage - 1, range)
            }
            output.documentCatalog.setPageLabels(target)
        } catch (_: Exception) {
            // Malformed optional labels never block the page conversion.
        }
    }

    private fun applyOutline(
        source: PDDocument,
        output: PDDocument,
        mapping: PdfPageOutputMap,
    ) {
        val entries = try {
            readOutline(source)
        } catch (failure: PdfFormatException) {
            throw failure
        } catch (_: Exception) {
            return
        }
        if (entries.isEmpty()) return
        val remapped = PdfOutlinePlan.remap(entries, mapping)
        if (remapped.isEmpty()) return
        try {
            val outline = PDDocumentOutline()
            for (entry in remapped) outline.addLast(buildOutlineItem(output, entry))
            output.documentCatalog.setDocumentOutline(outline)
        } catch (failure: PdfFormatException) {
            throw failure
        } catch (_: Exception) {
            // A malformed outline is optional structure; never corrupt the page export for it.
        }
    }

    private fun readOutline(source: PDDocument): List<PdfOutlineEntry> {
        val root = try {
            source.documentCatalog.getDocumentOutline()
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()
        val pageIndexes = IdentityHashMap<COSDictionary, Int>()
        var index = 0
        try {
            for (page in source.documentCatalog.getPages()) {
                pageIndexes[page.cosObject] = index
                index++
            }
        } catch (_: Exception) {
            return emptyList()
        }
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        var count = 0
        fun readSiblings(first: PDOutlineItem?, depth: Int): List<PdfOutlineEntry> {
            if (depth > MAX_OUTLINE_DEPTH) {
                throw PdfFormatException(PdfFormatException.Reason.LIMIT)
            }
            val result = ArrayList<PdfOutlineEntry>()
            var current = first
            while (current != null) {
                val dictionary = current.getCOSObject()
                if (!visited.add(dictionary)) break
                count++
                if (count > MAX_OUTLINE_ITEMS) {
                    throw PdfFormatException(PdfFormatException.Reason.LIMIT)
                }
                val title = try {
                    current.getTitle() ?: ""
                } catch (_: Exception) {
                    ""
                }
                if (title.length > MAX_OUTLINE_TITLE_CHARS) {
                    throw PdfFormatException(PdfFormatException.Reason.LIMIT)
                }
                val page = try {
                    current.findDestinationPage(source)?.let { pageIndexes[it.cosObject]?.plus(1) }
                } catch (_: Exception) {
                    null
                }
                val children = try {
                    readSiblings(current.getFirstChild(), depth + 1)
                } catch (failure: PdfFormatException) {
                    throw failure
                } catch (_: Exception) {
                    emptyList()
                }
                result.add(PdfOutlineEntry(
                    title = title,
                    page = page,
                    bold = try { current.isBold() } catch (_: Exception) { false },
                    italic = try { current.isItalic() } catch (_: Exception) { false },
                    open = try { current.isNodeOpen() } catch (_: Exception) { false },
                    children = children,
                ))
                current = try {
                    current.getNextSibling()
                } catch (_: Exception) {
                    null
                }
            }
            return result
        }
        return readSiblings(root.getFirstChild(), 0)
    }

    private fun buildOutlineItem(output: PDDocument, entry: PdfOutlineEntry): PDOutlineItem {
        val item = PDOutlineItem()
        item.setTitle(entry.title)
        if (entry.bold) item.setBold(true)
        if (entry.italic) item.setItalic(true)
        entry.page?.let { page ->
            if (page < 1 || page > output.numberOfPages) {
                throw PdfFormatException(PdfFormatException.Reason.LIMIT)
            }
            item.setDestination(output.getPage(page - 1))
        }
        for (child in entry.children) item.addLast(buildOutlineItem(output, child))
        if (entry.open) item.openNode()
        return item
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8_192))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun hasFormFields(document: PDDocument): Boolean = try {
        val form = document.documentCatalog.cosObject
            .getDictionaryObject(COSName.ACRO_FORM)
        if (form == null) {
            false
        } else if (form !is COSDictionary) {
            true
        } else {
            form.containsKey(COSName.XFA) ||
                (form.getDictionaryObject(COSName.FIELDS) as? COSArray)?.size()?.let { it > 0 } == true
        }
    } catch (_: Exception) {
        // An unreadable form dictionary is treated as present; refusing is safer than dropping it.
        true
    }

    private fun hasEmbeddedAttachments(document: PDDocument): Boolean = try {
        val catalog = document.documentCatalog.cosObject
        if (catalog.getDictionaryObject(AF_KEY) != null) {
            true
        } else {
            val names = catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary
            if (names?.getDictionaryObject(COSName.EMBEDDED_FILES) != null ||
                names?.getDictionaryObject(COSName.EMBEDDED_FDFS) != null
            ) {
                true
            } else {
                document.documentCatalog.getPages().any { page ->
                    page.cosObject.getDictionaryObject(AF_KEY) != null
                }
            }
        }
    } catch (_: Exception) {
        true
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

/**
 * Copies the complete document information dictionary. The dictionary copy keeps standard,
 * custom, date and Trapped entries and is independent of the source document.
 */
internal fun copyPdfDocumentInformation(source: PDDocumentInformation): PDDocumentInformation =
    PDDocumentInformation(COSDictionary(source.getCOSObject()))

/**
 * Maps a frozen source page number to the first output page produced by its action. Omitted pages
 * have no output page; a translated page maps to the first page of its replacement block, so an
 * outline destination never points at a continuation page or past the output tree. Production
 * writers must use [fromOutputPages] because a translated page can span multiple output pages.
 */
internal class PdfPageOutputMap private constructor(private val pages: IntArray) {
    fun outputPage(sourcePage: Int): Int? {
        if (sourcePage < 1 || sourcePage > pages.size) return null
        return pages[sourcePage - 1].takeIf { it > 0 }
    }

    companion object {
        /**
         * Plan-only mapping for tests and callers that know every kept/translated action produces
         * exactly one page. Production writes use [fromOutputPages] because a translated page may
         * span several continuation pages.
         */
        fun from(actions: List<PdfPageAction>): PdfPageOutputMap {
            val maxPage = actions.maxOfOrNull { it.page } ?: 0
            val pages = IntArray(maxPage)
            var outputPage = 0
            for (action in actions.sortedBy { it.page }) {
                if (action.page < 1 || action.page > maxPage) continue
                if (action is PdfPageAction.Omit) {
                    pages[action.page - 1] = 0
                } else {
                    outputPage++
                    pages[action.page - 1] = outputPage
                }
            }
            return PdfPageOutputMap(pages)
        }

        fun fromOutputPages(outputPages: IntArray): PdfPageOutputMap =
            PdfPageOutputMap(outputPages.copyOf())
    }
}

/** Source-page outline item before or after page remapping. [page] is 1-based in both stages. */
internal data class PdfOutlineEntry(
    val title: String,
    val page: Int?,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val open: Boolean = false,
    val children: List<PdfOutlineEntry> = emptyList(),
)

/**
 * Drops outline items whose source page was omitted or is out of range. Valid children of a
 * dropped item are promoted to its parent; structural items without a destination survive only
 * when they still have a valid descendant. This keeps hierarchy where meaningful and guarantees
 * that no returned destination is outside the output page tree.
 */
internal object PdfOutlinePlan {
    fun remap(entries: List<PdfOutlineEntry>, mapping: PdfPageOutputMap): List<PdfOutlineEntry> {
        var count = 0
        fun visit(nodes: List<PdfOutlineEntry>, depth: Int): List<PdfOutlineEntry> {
            if (depth > MAX_OUTLINE_DEPTH) throw PdfFormatException(PdfFormatException.Reason.LIMIT)
            val result = ArrayList<PdfOutlineEntry>()
            for (entry in nodes) {
                count++
                if (count > MAX_OUTLINE_ITEMS) {
                    throw PdfFormatException(PdfFormatException.Reason.LIMIT)
                }
                val children = visit(entry.children, depth + 1)
                if (entry.page == null) {
                    if (children.isNotEmpty()) result.add(entry.copy(page = null, children = children))
                    continue
                }
                val target = mapping.outputPage(entry.page)
                if (target == null) {
                    result.addAll(children)
                } else {
                    result.add(entry.copy(page = target, children = children))
                }
            }
            return result
        }
        return visit(entries, 0)
    }
}

internal class PdfPageLabelAssignment(val outputPage: Int, val label: String)

/** Page labels of retained source pages, projected to their output page indices. */
internal object PdfPageLabelPlan {
    fun assignments(
        sourceLabels: List<String?>,
        mapping: PdfPageOutputMap,
    ): List<PdfPageLabelAssignment> {
        val result = ArrayList<PdfPageLabelAssignment>()
        for ((index, label) in sourceLabels.withIndex()) {
            if (label.isNullOrEmpty() || label.length > MAX_PAGE_LABEL_CHARS) continue
            val outputPage = mapping.outputPage(index + 1) ?: continue
            result.add(PdfPageLabelAssignment(outputPage, label))
        }
        return result
    }
}

/** Refuses page-changing writes that would silently detach form fields or embedded files. */
internal object PdfWriteGuard {
    fun check(
        hasForms: Boolean,
        hasAttachments: Boolean,
        actions: List<PdfPageAction>,
    ) {
        if (actions.none { it !is PdfPageAction.Keep }) return
        if (hasForms || hasAttachments) {
            throw PdfFormatException(PdfFormatException.Reason.UNSUPPORTED)
        }
    }
}
