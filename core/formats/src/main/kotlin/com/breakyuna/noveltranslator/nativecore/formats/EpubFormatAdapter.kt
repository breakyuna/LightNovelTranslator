package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.BinaryFormatAdapter
import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWarning
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.w3c.dom.Element

/**
 * EPUB package rewriter. It parses only `META-INF/container.xml`, the OPF package document, spine
 * XHTML documents, the EPUB3 navigation document and the EPUB2 NCX, all located through the OPF.
 * The leading text slot of every direct OPF `<dc:title>`, `<dc:description>`, `<dc:subject>`,
 * `<dc:publisher>`, `<dc:date>`, `<dc:language>`, `<dc:rights>`, `<dc:identifier>` and
 * `<dc:series>` child, a `<meta name="calibre:series" content="...">` value, an EPUB3
 * `belongs-to-collection` value, spine text/`alt`/`title` slots, nav text plus nav `alt`/`title`
 * attributes and NCX `<text>` labels are exposed as units and written back with UTF-16 offset
 * splices into the original member bytes, never a DOM re-serialization. Every other member
 * (images, fonts, CSS, OPF markup outside those slots) is copied literally. A locator stores its
 * member path, exact UTF-16 range, a raw-slot digest and a kind discriminator; `read()` emits
 * navigation labels in a stable order (spine documents, then nav text, nav attributes, NCX labels,
 * then OPF metadata in document order), and `write()` rejects stale, foreign, overlapping or
 * duplicate locators instead of writing into the package.
 *
 * Spine items marked `linear="no"` are auxiliary/non-reading-order pages. Their body text is not
 * exposed for translation and `read()` emits `epub.spine_non_linear` instead. Fixed-layout OPFs
 * (`rendition:layout` with `pre-paginated`/`fixed`, Apple `fixed-layout` metadata or a
 * `fixed-layout`/`rendition:layout-pre-paginated` manifest property) are still imported, but
 * `read()` emits `epub.fixed_layout` because reflow-based translation cannot preserve page
 * positioning. Unknown named entities are skipped and reported as `epub.unknown_entity`; common
 * HTML entities are decoded.
 *
 * OPF `dc:creator` is intentionally not exposed. An author name is identity metadata rather than
 * prose, and a generic translation flow would rename a person. The writer therefore never touches
 * creator elements and leaves their bytes (and every child/attribute) intact. Only a direct
 * leading text slot (or a series `content` attribute value) is supported for the other metadata
 * elements; values whose text starts after child markup are left literal rather than guessing at
 * mixed content.
 *
 * Bilingual mode follows LG for metadata and navigation: metadata, nav and NCX labels receive the
 * target only. A source/target pair would turn a single-valued `<dc:title>` into a multi-line
 * value with no defined reader semantics, and duplicating a nav/NCX label would produce two
 * navigable labels (or a newline inside an `<a>` element) and change the document outline. LG
 * explicitly disables bilingual insertion for nav pages and NCX (`allow_bilingual_insert`) and
 * falls back to the source when the target is blank (`read_output_text(..., true)`); a blank
 * target therefore keeps the original label rather than emptying navigation. Ruby `rt`/`rp`/`rtc`
 * readings are deliberately excluded from the text lifecycle (matching LG): they are
 * pronunciation metadata, not prose, and translating them would leave the base text and reading
 * inconsistent. Ruby markup and every reading are copied literally while only the base text is
 * offered, so the translated document keeps a valid ruby structure. Nav extraction considers
 * element text plus `alt`/`title` attributes only; `href`, `id`, `epub:type`, `playOrder`, `src`
 * and every other attribute stay byte-identical.
 */
class EpubFormatAdapter : BinaryFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.EPUB

    override fun read(bytes: ByteArray, path: RelativePath): FormatDocument {
        val archive = ZipContainer.read(bytes)
        val warnings = ArrayList<FormatWarning>()
        val packageInfo = readPackage(archive, warnings)
        val units = ArrayList<FormatUnit>()
        var limited = false
        if (packageInfo.fixedLayout) {
            addWarning(warnings, "epub.fixed_layout", packageInfo.opfPath,
                "EPUB declares a fixed-layout rendition (rendition:layout); " +
                    "text is extracted without page reflow")
        }
        val processedPaths = HashSet<String>()
        val linearPaths = packageInfo.linearSpinePaths.toHashSet()
        val warnedNonLinear = HashSet<String>()
        for (spineItem in packageInfo.spineItems) {
            val documentPath = spineItem.path
            if (!spineItem.linear) {
                if (documentPath !in linearPaths && documentPath != packageInfo.navPath &&
                    documentPath != packageInfo.ncxPath && isHtmlDocumentPath(documentPath) &&
                    warnedNonLinear.add(documentPath)
                ) {
                    addWarning(warnings, "epub.spine_non_linear", documentPath,
                        "Non-linear spine item (linear=\"no\"); its body text was skipped")
                }
                continue
            }
            if (!isHtmlDocumentPath(documentPath)) continue
            if (documentPath == packageInfo.navPath || documentPath == packageInfo.ncxPath) continue
            if (!processedPaths.add(documentPath)) continue
            val member = archive[documentPath]
            if (member == null) {
                warnings.add(FormatWarning("epub.missing_document", safeWarningPath(documentPath),
                    null, "Spine document is missing from the archive"))
                continue
            }
            if (member.directory) {
                warnings.add(FormatWarning("epub.missing_document", safeWarningPath(documentPath),
                    null, "Spine document is missing from the archive"))
                continue
            }
            if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
                warnings.add(FormatWarning("epub.document_too_large", safeWarningPath(documentPath),
                    null, "Spine document exceeds the text entry bound"))
                continue
            }
            val text = decodeUtf8OrNull(member.bytes)
            if (text == null) {
                warnings.add(FormatWarning("epub.invalid_utf8", safeWarningPath(documentPath),
                    null, "Spine document is not valid UTF-8"))
                continue
            }
            val onUnknownEntity = unknownEntityWarning(warnings, documentPath)
            for (node in XhtmlTextScanner.scan(text, onUnknownEntity = onUnknownEntity)) {
                if (units.size >= ContainerLimits.MAX_UNITS) {
                    limited = true
                    break
                }
                val source = normalizeInlineWhitespace(node.text).removePrefix("\uFEFF")
                if (!hasVisibleText(source)) continue
                if ('\u0000' in source || !isValidUtf8Text(source)) continue
                if (source.length > ContainerLimits.MAX_UNIT_SOURCE_CHARS) {
                    if (warnings.size < 1_000) {
                        warnings.add(FormatWarning("epub.text_too_large", safeWarningPath(documentPath),
                            null, "A text or attribute node exceeds the unit bound"))
                    }
                    continue
                }
                val extra = buildJsonObject {
                    put("v", 1)
                    put("start", node.start)
                    put("end", node.end)
                    put("digest", containerSha256Hex(node.raw))
                    if (node.kind != ScannedNodeKind.TEXT) put("kind", node.kind.extraName)
                }.toString()
                units.add(FormatUnit(units.size, source, "", TextItemStatus.NONE, documentPath,
                    extraField = extra))
            }
            if (limited) break
        }
        val navPath = packageInfo.navPath
        if (!limited && navPath != null && navPath != packageInfo.opfPath &&
            isHtmlDocumentPath(navPath) && processedPaths.add(navPath)
        ) {
            limited = readNavDocument(archive, navPath, units, warnings)
        }
        val ncxPath = packageInfo.ncxPath
        if (!limited && ncxPath != null && ncxPath != packageInfo.opfPath &&
            processedPaths.add(ncxPath)
        ) {
            limited = readNcxDocument(archive, ncxPath, units, warnings)
        }
        if (appendOpfMetadataUnits(packageInfo.opfPath, packageInfo.opfMetadata, units, warnings)) {
            limited = true
        }
        if (limited) warnings.add(FormatWarning("epub.unit_limit", "", null, "EPUB unit limit reached"))
        if (!archive.containsKey("mimetype")) {
            warnings.add(FormatWarning("epub.missing_mimetype", "mimetype", null,
                "EPUB has no mimetype entry"))
        }
        val metadata = buildJsonObject {
            put("v", 1)
            put("opf", packageInfo.opfPath)
            put("spine", packageInfo.spineItems.size)
            packageInfo.navPath?.let { put("nav", it) }
            packageInfo.ncxPath?.let { put("ncx", it) }
        }.toString()
        return FormatDocument(format, units, warnings, "", metadata)
    }

    /**
     * EPUB3 navigation text and `alt`/`title` attribute slots. Text labels are exposed first in
     * document order, then navigation attributes, so the existing label order stays stable.
     * `href`, `id`, `epub:type` and every other attribute stay literal. Nested `<ol>`/`<li>` lists
     * are handled by document order.
     */
    private fun readNavDocument(
        archive: Map<String, ZipMember>,
        documentPath: String,
        units: MutableList<FormatUnit>,
        warnings: MutableList<FormatWarning>,
    ): Boolean {
        val member = archive[documentPath]
        if (member == null || member.directory) {
            addWarning(warnings, "epub.missing_document", documentPath,
                "Navigation document is missing from the archive")
            return false
        }
        if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
            addWarning(warnings, "epub.document_too_large", documentPath,
                "Navigation document exceeds the text entry bound")
            return false
        }
        val text = decodeUtf8OrNull(member.bytes)
        if (text == null) {
            addWarning(warnings, "epub.invalid_utf8", documentPath,
                "Navigation document is not valid UTF-8")
            return false
        }
        val slots = scanNavigationSlots(text, unknownEntityWarning(warnings, documentPath))
        val labels = slots.filter { it.kind == ScannedNodeKind.NAV_TEXT }
        val attributes = slots.filter { it.kind == ScannedNodeKind.NAV_ATTRIBUTE }
        if (appendNavigationUnits(documentPath, labels, ScannedNodeKind.NAV_TEXT, units, warnings)) {
            return true
        }
        return appendNavigationUnits(documentPath, attributes, ScannedNodeKind.NAV_ATTRIBUTE,
            units, warnings)
    }

    /** EPUB2 NCX labels: every local `<text>` element, matching LG's navMap/docTitle extraction. */
    private fun readNcxDocument(
        archive: Map<String, ZipMember>,
        documentPath: String,
        units: MutableList<FormatUnit>,
        warnings: MutableList<FormatWarning>,
    ): Boolean {
        val member = archive[documentPath]
        if (member == null || member.directory) {
            addWarning(warnings, "epub.missing_document", documentPath,
                "NCX document is missing from the archive")
            return false
        }
        if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
            addWarning(warnings, "epub.document_too_large", documentPath,
                "NCX document exceeds the text entry bound")
            return false
        }
        val text = decodeUtf8OrNull(member.bytes)
        if (text == null) {
            addWarning(warnings, "epub.invalid_utf8", documentPath,
                "NCX document is not valid UTF-8")
            return false
        }
        return appendNavigationUnits(documentPath, NcxLabelScanner.find(text,
            unknownEntityWarning(warnings, documentPath)), ScannedNodeKind.NCX_TEXT, units, warnings)
    }

    /**
     * Shared unit assembly for navigation labels. Offsets and the raw-slot digest bind the unit to
     * one member slot; `source` is normalized only for display, exactly like spine text slots.
     */
    private fun appendNavigationUnits(
        documentPath: String,
        slots: List<ScannedTextNode>,
        kind: ScannedNodeKind,
        units: MutableList<FormatUnit>,
        warnings: MutableList<FormatWarning>,
    ): Boolean {
        for (slot in slots) {
            if (units.size >= ContainerLimits.MAX_UNITS) return true
            val source = normalizeInlineWhitespace(slot.text).removePrefix("\uFEFF")
            if (!hasVisibleText(source)) continue
            if ('\u0000' in source || !isValidUtf8Text(source)) continue
            if (source.length > ContainerLimits.MAX_UNIT_SOURCE_CHARS) {
                addWarning(warnings, "epub.text_too_large", documentPath,
                    "A navigation label, attribute or NCX label exceeds the unit bound")
                continue
            }
            val extra = buildJsonObject {
                put("v", 1)
                put("start", slot.start)
                put("end", slot.end)
                put("digest", containerSha256Hex(slot.raw))
                put("kind", kind.extraName)
            }.toString()
            units.add(FormatUnit(units.size, source, "", TextItemStatus.NONE, documentPath,
                extraField = extra))
        }
        return false
    }

    /**
     * Navigation text nodes and `alt`/`title` attribute values share one scanner. Text is decoded
     * tolerantly because nav documents in the wild contain bare ampersands; attribute values use
     * strict XML decoding and remain unsupported when that decoding fails.
     */
    private fun scanNavigationSlots(
        text: String,
        onUnknownEntity: (() -> Unit)? = null,
    ): List<ScannedTextNode> {
        val result = ArrayList<ScannedTextNode>()
        for (node in XhtmlTextScanner.scan(text, tolerantEntities = true,
            onUnknownEntity = onUnknownEntity)) {
            when (node.kind) {
                ScannedNodeKind.TEXT -> result.add(node.withKind(ScannedNodeKind.NAV_TEXT))
                ScannedNodeKind.ATTRIBUTE -> result.add(node.withKind(ScannedNodeKind.NAV_ATTRIBUTE))
                else -> Unit
            }
        }
        return result
    }

    /**
     * OPF metadata slots in OPF document order. Each direct title, description or subject is a
     * separate unit so repeated subjects/titles cannot collapse into one value. Creator elements
     * are not part of the supported metadata kinds and are therefore never exposed or rewritten.
     */
    private fun appendOpfMetadataUnits(
        opfPath: String,
        slots: List<OpfMetadataSlot>,
        units: MutableList<FormatUnit>,
        warnings: MutableList<FormatWarning>,
    ): Boolean {
        for (slot in slots) {
            if (units.size >= ContainerLimits.MAX_UNITS) return true
            if (!slot.kind.translatable) continue
            val source = slot.normalizedText
            when {
                source.length > ContainerLimits.MAX_UNIT_SOURCE_CHARS -> {
                    addWarning(warnings, "epub.text_too_large", opfPath,
                        "An OPF metadata value exceeds the unit bound")
                    continue
                }
                '\u0000' in source || !isValidUtf8Text(source) -> {
                    addWarning(warnings, "epub.invalid_utf8", opfPath,
                        "An OPF metadata value is not valid UTF-8 text")
                    continue
                }
            }
            val extra = buildJsonObject {
                put("v", 1)
                put("start", slot.start)
                put("end", slot.end)
                put("digest", slot.digest)
                put("kind", slot.kind.extraName)
                if (slot.attribute) put("slot", "attr")
            }.toString()
            units.add(FormatUnit(units.size, source, "", TextItemStatus.NONE, opfPath,
                extraField = extra))
        }
        return false
    }

    private fun addWarning(
        warnings: MutableList<FormatWarning>,
        code: String,
        path: String,
        message: String,
    ) {
        if (warnings.size < 1_000) warnings.add(FormatWarning(code, safeWarningPath(path), null, message))
    }

    /**
     * One warning per member is enough to tell the operator that a slot was dropped; a book with
     * many unsupported entities should not flood the warning list and hide structural warnings.
     */
    private fun unknownEntityWarning(
        warnings: MutableList<FormatWarning>,
        path: String,
    ): () -> Unit {
        var reported = false
        return {
            if (!reported) {
                reported = true
                addWarning(warnings, "epub.unknown_entity", path,
                    "An unsupported named entity was skipped in a text or attribute slot")
            }
        }
    }

    override fun write(original: ByteArray, units: List<FormatUnit>, options: FormatWriteOptions): ByteArray {
        val archive = ZipContainer.read(original)
        val packageInfo = try {
            readPackage(archive)
        } catch (_: FormatContainerException) {
            return original
        }
        val navDocumentPath = packageInfo.navPath
            ?.takeIf { it != packageInfo.opfPath && isHtmlDocumentPath(it) }
        val ncxDocumentPath = packageInfo.ncxPath
            ?.takeIf { it != packageInfo.opfPath && it != navDocumentPath }
        val navigationPaths = buildSet {
            navDocumentPath?.let { add(it) }
            ncxDocumentPath?.let { add(it) }
        }
        // New reads expose only linear spine XHTML documents, but projects imported before that
        // rule may already hold digest-bound units for linear="no" documents. Keep writing every
        // spine document so those translations are not silently dropped on export; a fresh import
        // still never creates such units.
        val allowed = packageInfo.spinePaths
            .asSequence()
            .filter { isHtmlDocumentPath(it) && it !in navigationPaths }
            .toSet()
        val byDocument = LinkedHashMap<String, MutableList<FormatUnit>>()
        for (unit in units) {
            if (unit.tag !in allowed) continue
            byDocument.getOrPut(unit.tag) { ArrayList() }.add(unit)
        }
        val opfPath = packageInfo.opfPath
        val opfMetadata = packageInfo.opfMetadata
        val opfUnits = if (opfMetadata.isEmpty()) emptyList() else units.filter { it.tag == opfPath }
        val navUnits = navDocumentPath?.let { path -> units.filter { it.tag == path } } ?: emptyList()
        val ncxUnits = ncxDocumentPath?.let { path -> units.filter { it.tag == path } } ?: emptyList()
        if (byDocument.isEmpty() && opfUnits.isEmpty() && navUnits.isEmpty() && ncxUnits.isEmpty()) {
            return original
        }
        val output = LinkedHashMap<String, ZipMember>(archive)
        var changed = false
        for ((documentPath, documentUnits) in byDocument) {
            val member = archive[documentPath] ?: continue
            if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) continue
            val text = decodeUtf8OrNull(member.bytes) ?: continue
            val textNodes = XhtmlTextScanner.scan(text).associateBy {
                ScannedNodeKey(it.start, it.end, it.kind)
            }
            val replacements = ArrayList<TextReplacement>()
            val claimed = HashSet<Int>()
            for (unit in documentUnits) {
                val extra = parseJsonObject(unit.extraField) ?: continue
                val start = extraInt(extra, "start") ?: continue
                val end = extraInt(extra, "end") ?: continue
                if (start < 0 || end <= start || end > text.length) continue
                if (start in claimed) continue
                val kind = ScannedNodeKind.fromExtra(extraString(extra, "kind"))
                    ?: continue
                val node = textNodes[ScannedNodeKey(start, end, kind)] ?: continue
                if (extraString(extra, "digest") != containerSha256Hex(node.raw)) continue
                val target = effectiveTarget(unit, options) ?: continue
                claimed.add(start)
                val canonicalSource = normalizeInlineWhitespace(node.text)
                val replacement = if (options.bilingual) canonicalSource + "\n" + target else target
                val encoded = if (kind == ScannedNodeKind.ATTRIBUTE) {
                    escapeXmlAttribute(replacement)
                } else {
                    escapeXmlText(replacement)
                }
                replacements.add(TextReplacement(start, end, encoded))
            }
            if (replacements.isEmpty()) continue
            replacements.sortBy { it.start }
            var cursor = 0
            val accepted = ArrayList<TextReplacement>(replacements.size)
            for (replacement in replacements) {
                if (replacement.start < cursor) continue
                accepted.add(replacement)
                cursor = replacement.end
            }
            if (accepted.isEmpty()) continue
            val builder = StringBuilder(text)
            for (replacement in accepted.asReversed()) {
                builder.replace(replacement.start, replacement.end, replacement.text)
            }
            val encoded = encodeUtf8(builder.toString())
            if (!encoded.contentEquals(member.bytes)) {
                output[documentPath] = member.withBytes(encoded)
                changed = true
            }
        }
        if (navDocumentPath != null && navUnits.isNotEmpty()) {
            val member = archive[navDocumentPath]
            if (member != null && !member.directory) {
                val encoded = writeNavigationDocument(member, navUnits) { text ->
                    scanNavigationSlots(text)
                }
                if (encoded != null && encoded.size <= ContainerLimits.MAX_TEXT_ENTRY_BYTES &&
                    !encoded.contentEquals(member.bytes)
                ) {
                    output[navDocumentPath] = member.withBytes(encoded)
                    changed = true
                }
            }
        }
        if (ncxDocumentPath != null && ncxUnits.isNotEmpty()) {
            val member = archive[ncxDocumentPath]
            if (member != null && !member.directory) {
                val encoded = writeNavigationDocument(member, ncxUnits) { text ->
                    NcxLabelScanner.find(text)
                }
                if (encoded != null && encoded.size <= ContainerLimits.MAX_TEXT_ENTRY_BYTES &&
                    !encoded.contentEquals(member.bytes)
                ) {
                    output[ncxDocumentPath] = member.withBytes(encoded)
                    changed = true
                }
            }
        }
        if (opfUnits.isNotEmpty() && opfMetadata.isNotEmpty()) {
            val member = archive[opfPath]
            if (member != null && member.bytes.size <= ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
                val text = decodeUtf8OrNull(member.bytes)
                if (text != null) {
                    val slotsByStart = HashMap<Int, OpfMetadataSlot>(opfMetadata.size)
                    for (slot in opfMetadata) slotsByStart[slot.start] = slot
                    val replacements = ArrayList<TextReplacement>()
                    val claimed = HashSet<Int>()
                    for (unit in opfUnits) {
                        val extra = parseJsonObject(unit.extraField) ?: continue
                        val kind = OpfMetadataKind.fromExtra(extraString(extra, "kind")) ?: continue
                        // Old imports may still carry a persisted unit for identity metadata;
                        // never let a translated value rewrite the language/date/identifier.
                        if (!kind.translatable) continue
                        val start = extraInt(extra, "start") ?: continue
                        val end = extraInt(extra, "end") ?: continue
                        if (start < 0 || end <= start || end > text.length) continue
                        // A metadata locator is accepted only at the exact current slot. The
                        // digest and normalized source close the race where the OPF changed after
                        // read; creator and mixed-content element bytes are never addressed.
                        val slot = slotsByStart[start] ?: continue
                        val attributeSlot = extraString(extra, "slot") == "attr"
                        if (slot.kind != kind || slot.end != end ||
                            slot.attribute != attributeSlot
                        ) {
                            continue
                        }
                        val raw = text.substring(start, end)
                        if (extraString(extra, "digest") != containerSha256Hex(raw)) continue
                        if (unit.source != slot.normalizedText) continue
                        val target = effectiveTarget(unit, options) ?: continue
                        if (target.isBlank() || target == slot.normalizedText) continue
                        val escaped = if (slot.attribute) {
                            escapeXmlAttribute(target)
                        } else {
                            escapeXmlText(target)
                        }
                        if (escaped.isBlank()) continue
                        if (!claimed.add(start)) continue
                        replacements.add(TextReplacement(start, end, escaped))
                    }
                    if (replacements.isNotEmpty()) {
                        replacements.sortBy { it.start }
                        var cursor = 0
                        val accepted = ArrayList<TextReplacement>(replacements.size)
                        for (replacement in replacements) {
                            if (replacement.start < cursor) continue
                            accepted.add(replacement)
                            cursor = replacement.end
                        }
                        if (accepted.isNotEmpty()) {
                            val builder = StringBuilder(text)
                            for (replacement in accepted.asReversed()) {
                                builder.replace(replacement.start, replacement.end, replacement.text)
                            }
                            val encoded = encodeUtf8(builder.toString())
                            if (encoded.size <= ContainerLimits.MAX_TEXT_ENTRY_BYTES &&
                                !encoded.contentEquals(member.bytes)
                            ) {
                                output[opfPath] = member.withBytes(encoded)
                                changed = true
                            }
                        }
                    }
                }
            }
        }
        if (!changed) return original
        return ZipContainer.write(output, firstEntry = "mimetype", forcedStored = setOf("mimetype"))
    }

    /** Decode one navigation member and splice only locators that still match the raw document. */
    private fun writeNavigationDocument(
        member: ZipMember,
        units: List<FormatUnit>,
        scan: (String) -> List<ScannedTextNode>,
    ): ByteArray? {
        if (units.isEmpty()) return null
        if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) return null
        val text = decodeUtf8OrNull(member.bytes) ?: return null
        return spliceNavigationLabels(text, scan(text), units)
    }

    /**
     * Navigation labels and nav attributes always receive the target only. LG disables bilingual
     * insertion for nav pages and NCX and keeps the source when the target is blank, so a missing
     * or blank translation never empties a link, outline label or tooltip. Duplicate or stale
     * locators are ignored per member. Only navigation kinds are accepted here, so a body text
     * locator cannot be applied to nav/NCX bytes.
     */
    private fun spliceNavigationLabels(
        text: String,
        slots: List<ScannedTextNode>,
        units: List<FormatUnit>,
    ): ByteArray? {
        if (slots.isEmpty()) return null
        val byKey = HashMap<ScannedNodeKey, ScannedTextNode>(slots.size)
        for (slot in slots) byKey[ScannedNodeKey(slot.start, slot.end, slot.kind)] = slot
        val replacements = ArrayList<TextReplacement>()
        val claimed = HashSet<Int>()
        for (unit in units) {
            val extra = parseJsonObject(unit.extraField) ?: continue
            val kind = ScannedNodeKind.fromExtra(extraString(extra, "kind")) ?: continue
            if (kind != ScannedNodeKind.NAV_TEXT && kind != ScannedNodeKind.NAV_ATTRIBUTE &&
                kind != ScannedNodeKind.NCX_TEXT
            ) {
                continue
            }
            val start = extraInt(extra, "start") ?: continue
            val end = extraInt(extra, "end") ?: continue
            if (start < 0 || end <= start || end > text.length) continue
            val slot = byKey[ScannedNodeKey(start, end, kind)] ?: continue
            if (extraString(extra, "digest") != containerSha256Hex(slot.raw)) continue
            val canonical = normalizeInlineWhitespace(slot.text).removePrefix("\uFEFF")
            if (unit.source != canonical) continue
            if (!claimed.add(start)) continue
            val target = navigationTarget(unit) ?: continue
            val escaped = if (kind == ScannedNodeKind.NAV_ATTRIBUTE) {
                escapeXmlAttribute(target)
            } else {
                escapeXmlText(target)
            }
            if (escaped.isBlank()) continue
            replacements.add(TextReplacement(start, end, escaped))
        }
        if (replacements.isEmpty()) return null
        replacements.sortBy { it.start }
        var cursor = 0
        val accepted = ArrayList<TextReplacement>(replacements.size)
        for (replacement in replacements) {
            if (replacement.start < cursor) continue
            accepted.add(replacement)
            cursor = replacement.end
        }
        if (accepted.isEmpty()) return null
        val builder = StringBuilder(text)
        for (replacement in accepted.asReversed()) {
            builder.replace(replacement.start, replacement.end, replacement.text)
        }
        return encodeUtf8(builder.toString())
    }

    private fun navigationTarget(unit: FormatUnit): String? {
        if (unit.status != TextItemStatus.PROCESSED) return null
        val target = unit.translation
        // Bilingual is deliberately ignored: a duplicated label would add a second navigable
        // entry and a newline inside the anchor/list text instead of an inline reading aid.
        if (target.isBlank() || target == unit.source) return null
        return target
    }

    private fun readPackage(
        archive: Map<String, ZipMember>,
        warnings: MutableList<FormatWarning>? = null,
    ): EpubPackage {
        val container = archive["META-INF/container.xml"] ?: throw FormatContainerException(
            FormatContainerException.Reason.STRUCTURE)
        if (container.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
            throw FormatContainerException(FormatContainerException.Reason.LIMIT)
        }
        val containerDocument = parseXmlDocument(container.bytes)
        val rootfile = descendants(containerDocument.documentElement, "rootfile").firstOrNull()
            ?: throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
        val fullPath = attributeValue(rootfile, "full-path")
        if (fullPath.isNullOrEmpty()) throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
        val opfPath = resolveArchivePath("", fullPath)
        val opfMember = archive[opfPath] ?: throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
        if (opfMember.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
            throw FormatContainerException(FormatContainerException.Reason.LIMIT)
        }
        val opfText = decodeUtf8(opfMember.bytes)
        val opfDocument = parseXmlDocument(opfMember.bytes)
        val packageElement = opfDocument.documentElement
        val opfDirectory = opfPath.substringBeforeLast('/', "")

        val manifest = descendants(packageElement, "manifest").firstOrNull()
        val items = LinkedHashMap<String, ManifestItem>()
        if (manifest != null) {
            for (item in childElements(manifest)) {
                if (localName(item) != "item") continue
                val id = attributeValue(item, "id") ?: continue
                val href = attributeValue(item, "href") ?: continue
                if (href.isEmpty()) continue
                val itemPath = try {
                    resolveArchivePath(opfDirectory, href)
                } catch (_: FormatContainerException) {
                    continue
                }
                items[id] = ManifestItem(
                    path = itemPath,
                    mediaType = attributeValue(item, "media-type") ?: "",
                    properties = attributeValue(item, "properties") ?: "",
                )
            }
        }

        val navPath = items.values.firstOrNull {
            it.properties.split(Regex("\\s+")).contains("nav")
        }?.path
        val spine = descendants(packageElement, "spine").firstOrNull()
        val tocId = spine?.let { attributeValue(it, "toc") }
        val ncxPath = (tocId?.let { items[it] } ?: items.values.firstOrNull {
            it.mediaType.lowercase().contains("application/x-dtbncx+xml")
        })?.path
        val spineItems = ArrayList<SpineItem>()
        if (spine != null) {
            for (itemref in childElements(spine)) {
                if (localName(itemref) != "itemref") continue
                val idref = attributeValue(itemref, "idref") ?: continue
                val item = items[idref] ?: continue
                if (item.path.isNotEmpty()) {
                    val linear = attributeValue(itemref, "linear")?.trim()?.lowercase() != "no"
                    spineItems.add(SpineItem(item.path, linear))
                }
            }
        }
        val metadataElement = descendants(packageElement, "metadata").firstOrNull()
        val fixedLayout = isFixedLayoutPackage(packageElement, metadataElement, manifest, spine)
        val opfMetadataWarning = warnings?.let { unknownEntityWarning(it, opfPath) }
        return EpubPackage(
            opfPath,
            spineItems,
            navPath,
            ncxPath,
            OpfMetadataScanner.find(opfText, opfMetadataWarning),
            fixedLayout,
        )
    }

    private class EpubPackage(
        val opfPath: String,
        val spineItems: List<SpineItem>,
        val navPath: String?,
        val ncxPath: String?,
        val opfMetadata: List<OpfMetadataSlot>,
        val fixedLayout: Boolean,
    ) {
        val spinePaths: List<String> = spineItems.map { it.path }
        val linearSpinePaths: List<String> = spineItems.filter { it.linear }.map { it.path }
    }

    private class SpineItem(val path: String, val linear: Boolean)
    private class ManifestItem(val path: String, val mediaType: String, val properties: String)
    private class TextReplacement(val start: Int, val end: Int, val text: String)

    /**
     * Fixed layout is still a readable package; this only decides whether to warn. It accepts the
     * EPUB3 `rendition:layout` metadata form, Apple/legacy `fixed-layout` metadata, a layout
     * property on package/spine/itemref and manifest `fixed-layout` property tokens.
     */
    private fun isFixedLayoutPackage(
        packageElement: Element,
        metadata: Element?,
        manifest: Element?,
        spine: Element?,
    ): Boolean {
        if (isFixedLayoutValue(attributeValue(packageElement, "rendition:layout"))) return true
        if (spine != null) {
            if (isFixedLayoutValue(attributeValue(spine, "rendition:layout"))) return true
            if (hasFixedLayoutProperty(attributeValue(spine, "properties"))) return true
            for (itemref in childElements(spine)) {
                if (localName(itemref) == "itemref" &&
                    (isFixedLayoutValue(attributeValue(itemref, "rendition:layout")) ||
                        hasFixedLayoutProperty(attributeValue(itemref, "properties")))
                ) {
                    return true
                }
            }
        }
        if (metadata != null) {
            for (meta in childElements(metadata)) {
                if (localName(meta) != "meta") continue
                val property = attributeValue(meta, "property")?.trim()?.lowercase()
                val name = attributeValue(meta, "name")?.trim()?.lowercase()
                val content = attributeValue(meta, "content")?.trim()?.lowercase()
                val text = elementText(meta).trim().lowercase()
                if (property != null) {
                    if (isRenditionLayoutKey(property) &&
                        (isFixedLayoutValue(text) || isFixedLayoutValue(content))
                    ) {
                        return true
                    }
                    if (property == "fixed-layout" && isFixedLayoutFlag(content ?: text)) return true
                }
                if (name != null) {
                    if (isRenditionLayoutKey(name) &&
                        (isFixedLayoutValue(content) || isFixedLayoutValue(text))
                    ) {
                        return true
                    }
                    if (name == "fixed-layout" && isFixedLayoutFlag(content ?: text)) return true
                }
            }
        }
        if (manifest != null) {
            for (item in childElements(manifest)) {
                if (localName(item) != "item") continue
                if (isFixedLayoutValue(attributeValue(item, "rendition:layout")) ||
                    hasFixedLayoutProperty(attributeValue(item, "properties"))
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun isRenditionLayoutKey(value: String): Boolean =
        value == "rendition:layout" || value.endsWith(":rendition:layout")

    private fun isFixedLayoutValue(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return false
        return normalized == "pre-paginated" || normalized == "prepaginated" ||
            normalized == "fixed" || normalized == "fixed-layout"
    }

    private fun isFixedLayoutFlag(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase() ?: return false
        return normalized == "true" || normalized == "yes" || normalized == "1" ||
            isFixedLayoutValue(normalized)
    }

    private fun hasFixedLayoutProperty(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.split(Regex("\\s+")).any { token ->
            val normalized = token.lowercase()
            normalized == "fixed-layout" ||
                normalized == "rendition:layout-pre-paginated" ||
                normalized == "rendition:layout-fixed" ||
                normalized == "pre-paginated"
        }
    }

    private fun safeWarningPath(value: String): String =
        if (value.length <= 4_096 && value.none(Char::isISOControl)) value else ""
}

/** Exact OPF metadata slot: offsets into the decoded OPF string, normalized source and raw digest. */
private class OpfMetadataSlot(
    val kind: OpfMetadataKind,
    val start: Int,
    val end: Int,
    val normalizedText: String,
    val digest: String,
    val attribute: Boolean = false,
)

/**
 * Metadata that can be safely located as one direct leading text slot. `dc:creator` is omitted on
 * purpose: author names are identity data, not prose, so a generic translation must not rewrite
 * them. `dc:language`, `dc:date` and `dc:identifier` are located for fidelity but are never
 * exposed or written: a language tag, an ISO-8601 date and a book identifier must stay byte-exact,
 * and a generic translation would turn `ja` into “Japanese” or reformat the date. Mixed child
 * content is likewise outside this set. Series additionally accepts a
 * `calibre:series`/`belongs-to-collection` attribute value, which the scanner marks as an
 * attribute slot so writeback uses attribute escaping.
 */
private enum class OpfMetadataKind(
    val localName: String,
    val extraName: String,
    val translatable: Boolean = true,
) {
    TITLE("title", "opf_title"),
    DESCRIPTION("description", "opf_description"),
    SUBJECT("subject", "opf_subject"),
    PUBLISHER("publisher", "opf_publisher"),
    DATE("date", "opf_date", translatable = false),
    LANGUAGE("language", "opf_language", translatable = false),
    RIGHTS("rights", "opf_rights"),
    IDENTIFIER("identifier", "opf_identifier", translatable = false),
    SERIES("series", "opf_series");

    companion object {
        fun fromLocalName(value: String): OpfMetadataKind? =
            values().firstOrNull { it.localName == value }

        fun fromExtra(value: String?): OpfMetadataKind? =
            if (value == null) null else values().firstOrNull { it.extraName == value }
    }
}

/** One decoded text node or attribute value together with its exact UTF-16 splice range. */
private class ScannedTextNode(
    val start: Int,
    val end: Int,
    val raw: String,
    val text: String,
    val kind: ScannedNodeKind,
) {
    fun withKind(value: ScannedNodeKind): ScannedTextNode =
        if (kind == value) this else ScannedTextNode(start, end, raw, text, value)
}

private data class ScannedNodeKey(val start: Int, val end: Int, val kind: ScannedNodeKind)

private enum class ScannedNodeKind(val extraName: String) {
    TEXT("text"),
    ATTRIBUTE("attr"),
    NAV_TEXT("nav_text"),
    NAV_ATTRIBUTE("nav_attr"),
    NCX_TEXT("ncx_text");

    companion object {
        /** Missing kind is the pre-existing text-node locator shape. */
        fun fromExtra(value: String?): ScannedNodeKind? = when (value) {
            null, "text" -> TEXT
            "attr" -> ATTRIBUTE
            "nav_text" -> NAV_TEXT
            "nav_attr" -> NAV_ATTRIBUTE
            "ncx_text" -> NCX_TEXT
            else -> null
        }
    }
}

/**
 * Finds every non-blank direct OPF metadata slot in the raw OPF text. Text metadata uses the
 * element's leading text slot (text before the first child element), matching LG's
 * `read_text_slot`; series additionally supports Calibre's `content` attribute and the EPUB3
 * `belongs-to-collection` text form. Multiple direct values stay separate in document order.
 * `dc:creator` is deliberately not matched. Results keep exact UTF-16 character offsets so
 * writeback can replace only that slot in the original bytes instead of serializing the XML DOM.
 */
private object OpfMetadataScanner {
    private const val MAX_METADATA_SLOTS = 10_000

    fun find(
        text: String,
        onUnknownEntity: (() -> Unit)? = null,
    ): List<OpfMetadataSlot> {
        val result = ArrayList<OpfMetadataSlot>()
        val stack = ArrayList<String>()
        var index = 0
        while (index < text.length && result.size < MAX_METADATA_SLOTS) {
            val tagStart = text.indexOf('<', index)
            if (tagStart < 0) break
            index = tagStart
            when {
                text.startsWith("<!--", index) -> {
                    val close = text.indexOf("-->", index + 4)
                    index = if (close < 0) text.length else close + 3
                }
                text.startsWith("<![CDATA[", index) -> {
                    val close = text.indexOf("]]>", index + 9)
                    index = if (close < 0) text.length else close + 3
                }
                text.startsWith("<?", index) -> {
                    val close = text.indexOf("?>", index + 2)
                    index = if (close < 0) text.length else close + 2
                }
                text.startsWith("<!", index) -> index = XhtmlTextScanner.declarationEnd(text, index)
                text.startsWith("</", index) -> {
                    val close = text.indexOf('>', index + 2)
                    if (close < 0) break
                    val name = XhtmlTextScanner.closingName(text.substring(index + 2, close))
                    if (stack.isNotEmpty()) {
                        val match = stack.lastIndexOf(name)
                        if (match >= 0) {
                            while (stack.size > match) stack.removeAt(stack.size - 1)
                        } else {
                            stack.removeAt(stack.size - 1)
                        }
                    }
                    index = close + 1
                }
                else -> {
                    val close = XhtmlTextScanner.tagEnd(text, index + 1)
                    if (close < 0) break
                    val inside = text.substring(index + 1, close)
                    val selfClosing = inside.trimEnd().endsWith('/')
                    val name = XhtmlTextScanner.startName(inside)
                    if (stack.lastOrNull() == "metadata") {
                        val kind = OpfMetadataKind.fromLocalName(name)
                        if (kind != null && !selfClosing) {
                            textSlot(text, close + 1, kind, onUnknownEntity)?.let(result::add)
                        } else if (name == "meta") {
                            seriesSlot(text, index, close, inside, selfClosing, onUnknownEntity)
                                ?.let(result::add)
                        }
                    }
                    if (name.isNotEmpty() && !selfClosing) stack.add(name)
                    index = close + 1
                }
            }
        }
        return result
    }

    private fun textSlot(
        text: String,
        contentStart: Int,
        kind: OpfMetadataKind,
        onUnknownEntity: (() -> Unit)?,
    ): OpfMetadataSlot? {
        val nextTag = text.indexOf('<', contentStart)
        val slotEnd = if (nextTag < 0) text.length else nextTag
        val raw = text.substring(contentStart, slotEnd)
        val decoded = XhtmlTextScanner.decodeText(raw, onUnknownEntity) ?: return null
        val normalized = normalizeInlineWhitespace(decoded)
        if (!hasVisibleText(normalized)) return null
        return OpfMetadataSlot(kind, contentStart, slotEnd, normalized, containerSha256Hex(raw))
    }

    /**
     * Series has no Dublin Core element in EPUB2. Calibre stores it in a `content` attribute and
     * EPUB3 uses the `belongs-to-collection` text property, so both forms become one SERIES slot
     * with an exact value range. A direct `<dc:series>` element goes through [textSlot] instead.
     */
    private fun seriesSlot(
        text: String,
        tagStart: Int,
        close: Int,
        inside: String,
        selfClosing: Boolean,
        onUnknownEntity: (() -> Unit)?,
    ): OpfMetadataSlot? {
        val attributes = readAttributes(inside, tagStart + 1)
        val property = attributes.firstOrNull { it.name == "property" }?.value
            ?.trim()?.lowercase()
        val name = attributes.firstOrNull { it.name == "name" }?.value
            ?.trim()?.lowercase()
        if (!isSeriesKey(property) && !isSeriesKey(name)) return null
        val content = attributes.firstOrNull { it.name == "content" }
        if (content != null && content.value.isNotBlank()) {
            val raw = text.substring(content.start, content.end)
            val decoded = XhtmlTextScanner.decodeText(raw, onUnknownEntity) ?: return null
            val normalized = normalizeInlineWhitespace(decoded)
            if (!hasVisibleText(normalized)) return null
            return OpfMetadataSlot(
                OpfMetadataKind.SERIES,
                content.start,
                content.end,
                normalized,
                containerSha256Hex(raw),
                attribute = true,
            )
        }
        if (selfClosing) return null
        return textSlot(text, close + 1, OpfMetadataKind.SERIES, onUnknownEntity)
    }

    private fun isSeriesKey(value: String?): Boolean {
        if (value == null) return false
        return value == "series" || value.endsWith(":series") ||
            value == "belongs-to-collection" ||
            value.endsWith(":belongs-to-collection")
    }

    private class TagAttribute(
        val name: String,
        val value: String,
        val start: Int,
        val end: Int,
    )

    private fun readAttributes(inside: String, offset: Int): List<TagAttribute> {
        val result = ArrayList<TagAttribute>()
        var index = 0
        while (index < inside.length && !inside[index].isWhitespace()) index += 1
        while (index < inside.length) {
            while (index < inside.length && inside[index].isWhitespace()) index += 1
            if (index >= inside.length) break
            if (inside[index] == '/' || inside[index] == '>') break
            val nameStart = index
            while (index < inside.length && !inside[index].isWhitespace() &&
                inside[index] != '=' && inside[index] != '/' && inside[index] != '>'
            ) {
                index += 1
            }
            val name = inside.substring(nameStart, index)
                .substringAfterLast(':').lowercase()
            while (index < inside.length && inside[index].isWhitespace()) index += 1
            if (index >= inside.length || inside[index] != '=') continue
            index += 1
            while (index < inside.length && inside[index].isWhitespace()) index += 1
            if (index >= inside.length) break
            val quote = inside[index]
            if (quote != '"' && quote != '\'') {
                while (index < inside.length && !inside[index].isWhitespace() &&
                    inside[index] != '>'
                ) {
                    index += 1
                }
                continue
            }
            val valueStart = index + 1
            val valueEnd = inside.indexOf(quote, valueStart)
            if (valueEnd < 0) break
            result.add(TagAttribute(
                name,
                inside.substring(valueStart, valueEnd),
                offset + valueStart,
                offset + valueEnd,
            ))
            index = valueEnd + 1
        }
        return result
    }
}

/**
 * Small tolerant XHTML scanner used instead of a DOM parse so markup outside the replaced text
 * nodes stays byte-identical. It understands comments, declarations, CDATA, processing
 * instructions, quoted attributes, self-closing tags and raw script/style bodies.
 */
private object XhtmlTextScanner {
    private const val MAX_SCANNED_TEXT_NODES = 200_000

    // Ruby base text (`rb` or bare text) is translated; readings/annotations are not prose and
    // are never offered. The markup itself remains literal because only text slots are spliced.
    private val SKIP_SUBTREES = setOf(
        "head", "script", "style", "code", "pre", "kbd", "samp", "var", "noscript",
        "rt", "rp", "rtc", "svg", "math",
    )

    fun scan(
        text: String,
        tolerantEntities: Boolean = false,
        onUnknownEntity: (() -> Unit)? = null,
    ): List<ScannedTextNode> {
        val result = ArrayList<ScannedTextNode>()
        val stack = ArrayList<String>()
        var skipDepth = -1
        var rawSkip: String? = null
        var index = 0
        while (index < text.length) {
            val raw = rawSkip
            if (raw != null) {
                val close = text.indexOf("</$raw", index, ignoreCase = true)
                if (close < 0) break
                val closeEnd = text.indexOf('>', close + 2 + raw.length)
                if (closeEnd < 0) break
                while (stack.isNotEmpty()) {
                    val top = stack.removeAt(stack.size - 1)
                    if (top == raw) break
                }
                if (skipDepth > stack.size) skipDepth = -1
                rawSkip = null
                index = closeEnd + 1
                continue
            }
            val nextTag = text.indexOf('<', index)
            val textEnd = if (nextTag < 0) text.length else nextTag
            if (textEnd > index && skipDepth < 0) {
                val rawText = text.substring(index, textEnd)
                val decoded = if (tolerantEntities) {
                    decodeTolerantText(rawText, onUnknownEntity)
                } else {
                    decodeXmlText(rawText, onUnknownEntity)
                }
                if (decoded != null) {
                    result.add(ScannedTextNode(index, textEnd, rawText, decoded, ScannedNodeKind.TEXT))
                    if (result.size >= MAX_SCANNED_TEXT_NODES) break
                }
            }
            if (nextTag < 0) break
            index = nextTag
            when {
                text.startsWith("<!--", index) -> {
                    val close = text.indexOf("-->", index + 4)
                    index = if (close < 0) text.length else close + 3
                }
                text.startsWith("<![CDATA[", index) -> {
                    val close = text.indexOf("]]>", index + 9)
                    index = if (close < 0) text.length else close + 3
                }
                text.startsWith("<?", index) -> {
                    val close = text.indexOf("?>", index + 2)
                    index = if (close < 0) text.length else close + 2
                }
                text.startsWith("<!", index) -> index = skipDeclaration(text, index)
                text.startsWith("</", index) -> {
                    val close = text.indexOf('>', index + 2)
                    if (close < 0) break
                    val name = localTag(text.substring(index + 2, close).trim().substringBefore(' '
                        ).substringBefore('\t').substringBefore('\n').substringBefore('\r'))
                    if (stack.isNotEmpty()) {
                        val match = stack.lastIndexOf(name)
                        if (match >= 0) {
                            while (stack.size > match) stack.removeAt(stack.size - 1)
                        } else {
                            stack.removeAt(stack.size - 1)
                        }
                    }
                    if (skipDepth > stack.size) skipDepth = -1
                    index = close + 1
                }
                else -> {
                    val close = findTagEnd(text, index + 1)
                    if (close < 0) break
                    val inside = text.substring(index + 1, close)
                    val selfClosing = inside.trimEnd().endsWith('/')
                    val name = startTagName(inside)
                    if (name.isNotEmpty() && skipDepth < 0 && name !in SKIP_SUBTREES) {
                        scanAttributes(text, index, close, result, onUnknownEntity)
                    }
                    if (name.isNotEmpty() && !selfClosing) {
                        stack.add(name)
                        if (skipDepth < 0 && name in SKIP_SUBTREES) skipDepth = stack.size
                        if (name == "script" || name == "style") rawSkip = name
                    }
                    index = close + 1
                }
            }
        }
        return result
    }

    fun decodeText(raw: String, onUnknownEntity: (() -> Unit)? = null): String? =
        decodeXmlText(raw, onUnknownEntity)
    fun tagEnd(text: String, start: Int): Int = findTagEnd(text, start)
    fun startName(inside: String): String = startTagName(inside)
    fun closingName(inside: String): String = localTag(inside.trim().substringBefore(' '))
    fun declarationEnd(text: String, start: Int): Int = skipDeclaration(text, start)

    /**
     * Tolerant text decoding for NCX and nav documents. LG repairs bare ampersands before parsing,
     * so this decoder keeps them literal while still decoding real entities, `CDATA` sections and
     * comments. Child markup makes the slot ambiguous and is skipped.
     */
    fun decodeTolerantText(
        raw: String,
        onUnknownEntity: (() -> Unit)? = null,
    ): String? {
        if ('<' !in raw && '&' !in raw) return raw
        val output = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            when {
                raw.startsWith("<![CDATA[", index) -> {
                    val close = raw.indexOf("]]>", index + 9)
                    if (close < 0) return null
                    output.append(raw, index + 9, close)
                    index = close + 3
                }
                raw.startsWith("<!--", index) -> {
                    val close = raw.indexOf("-->", index + 4)
                    if (close < 0) return null
                    index = close + 3
                }
                raw.startsWith("<?", index) -> {
                    val close = raw.indexOf("?>", index + 2)
                    if (close < 0) return null
                    index = close + 2
                }
                raw[index] == '<' -> return null
                raw[index] == '&' -> {
                    val semicolon = raw.indexOf(';', index + 1)
                    if (semicolon in (index + 2)..(index + 32)) {
                        val entity = raw.substring(index + 1, semicolon)
                        val decoded = decodeEntity(entity)
                        if (decoded == null) {
                            if (!entity.startsWith("#")) onUnknownEntity?.invoke()
                            return null
                        }
                        output.append(decoded)
                        index = semicolon + 1
                    } else {
                        output.append('&')
                        index += 1
                    }
                }
                else -> {
                    output.append(raw[index])
                    index += 1
                }
            }
        }
        return output.toString()
    }

    private fun scanAttributes(
        text: String,
        tagStart: Int,
        tagEnd: Int,
        output: MutableList<ScannedTextNode>,
        onUnknownEntity: (() -> Unit)?,
    ) {
        var index = tagStart + 1
        while (index < tagEnd && !text[index].isWhitespace()) index++
        while (index < tagEnd && output.size < MAX_SCANNED_TEXT_NODES) {
            while (index < tagEnd && text[index].isWhitespace()) index++
            if (index >= tagEnd) return
            val current = text[index]
            if (current == '/' || current == '>') return
            val nameStart = index
            while (index < tagEnd && !text[index].isWhitespace() &&
                text[index] != '=' && text[index] != '/' && text[index] != '>'
            ) {
                index++
            }
            val name = text.substring(nameStart, index)
            while (index < tagEnd && text[index].isWhitespace()) index++
            if (index >= tagEnd || text[index] != '=') continue
            index++
            while (index < tagEnd && text[index].isWhitespace()) index++
            if (index >= tagEnd) return
            val quote = text[index]
            if (quote != '"' && quote != '\'') {
                // Unquoted HTML attribute values stay literal and are not offered as units.
                while (index < tagEnd && !text[index].isWhitespace() && text[index] != '>') index++
                continue
            }
            val valueStart = index + 1
            val valueEnd = text.indexOf(quote, valueStart)
            if (valueEnd < 0 || valueEnd > tagEnd) return
            val raw = text.substring(valueStart, valueEnd)
            val lower = name.lowercase()
            if (lower == "alt" || lower == "title") {
                val decoded = decodeXmlText(raw, onUnknownEntity)
                if (decoded != null) {
                    output.add(ScannedTextNode(
                        valueStart, valueEnd, raw, decoded, ScannedNodeKind.ATTRIBUTE,
                    ))
                }
            }
            index = valueEnd + 1
        }
    }

    private fun findTagEnd(text: String, start: Int): Int {
        var quote = '\u0000'
        var index = start
        while (index < text.length) {
            val character = text[index]
            if (quote != '\u0000') {
                if (character == quote) quote = '\u0000'
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '>' -> return index
                }
            }
            index += 1
        }
        return -1
    }

    private fun skipDeclaration(text: String, start: Int): Int {
        var index = start + 2
        var brackets = 0
        var quote = '\u0000'
        while (index < text.length) {
            val character = text[index]
            if (quote != '\u0000') {
                if (character == quote) quote = '\u0000'
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '[' -> brackets += 1
                    ']' -> if (brackets > 0) brackets -= 1
                    '>' -> if (brackets == 0) return index + 1
                }
            }
            index += 1
        }
        return text.length
    }

    private fun startTagName(inside: String): String {
        var index = 0
        while (index < inside.length && inside[index].isWhitespace()) index += 1
        val start = index
        while (index < inside.length && !inside[index].isWhitespace() &&
            inside[index] != '/' && inside[index] != '>'
        ) {
            index += 1
        }
        return localTag(inside.substring(start, index))
    }

    private fun localTag(raw: String): String =
        raw.substringAfterLast(':').substringAfterLast('}').lowercase()

    private fun decodeXmlText(
        raw: String,
        onUnknownEntity: (() -> Unit)?,
    ): String? {
        if ('&' !in raw) return raw
        val output = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            if (character != '&') {
                output.append(character)
                index += 1
                continue
            }
            val semicolon = raw.indexOf(';', index + 1)
            if (semicolon < 0 || semicolon - index > 32) {
                // A bare ampersand is common in malformed EPUBs. The whole text node is skipped
                // (strict XML), so report it through the same warning hook as an unknown entity.
                onUnknownEntity?.invoke()
                return null
            }
            val entity = raw.substring(index + 1, semicolon)
            val decoded = decodeEntity(entity)
            if (decoded == null) {
                if (!entity.startsWith("#")) onUnknownEntity?.invoke()
                return null
            }
            output.append(decoded)
            index = semicolon + 1
        }
        return output.toString()
    }

    private fun decodeEntity(entity: String): String? {
        if (entity.startsWith("#x") || entity.startsWith("#X")) {
            val codePoint = entity.substring(2).toIntOrNull(16) ?: return null
            if (!isValidXmlCodePoint(codePoint)) return null
            return String(Character.toChars(codePoint))
        }
        if (entity.startsWith("#")) {
            val codePoint = entity.substring(1).toIntOrNull(10) ?: return null
            if (!isValidXmlCodePoint(codePoint)) return null
            return String(Character.toChars(codePoint))
        }
        return NAMED_ENTITIES[entity]
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00a0", "ensp" to "\u2002", "emsp" to "\u2003", "thinsp" to "\u2009",
        "copy" to "\u00a9", "reg" to "\u00ae", "trade" to "\u2122",
        "hellip" to "\u2026", "mdash" to "\u2014", "ndash" to "\u2013",
        "laquo" to "\u00ab", "raquo" to "\u00bb", "ldquo" to "\u201c", "rdquo" to "\u201d",
        "lsquo" to "\u2018", "rsquo" to "\u2019", "bull" to "\u2022", "middot" to "\u00b7",
        "times" to "\u00d7", "divide" to "\u00f7", "deg" to "\u00b0", "plusmn" to "\u00b1",
        "frac12" to "\u00bd", "frac14" to "\u00bc", "frac34" to "\u00be",
        "sect" to "\u00a7", "para" to "\u00b6", "dagger" to "\u2020", "Dagger" to "\u2021",
        "permil" to "\u2030", "euro" to "\u20ac", "pound" to "\u00a3", "yen" to "\u00a5",
        "cent" to "\u00a2", "curren" to "\u00a4", "iexcl" to "\u00a1", "iquest" to "\u00bf",
        "shy" to "\u00ad", "macr" to "\u00af", "acute" to "\u00b4", "micro" to "\u00b5",
        "ordf" to "\u00aa", "ordm" to "\u00ba", "not" to "\u00ac", "sup1" to "\u00b9",
        "sup2" to "\u00b2", "sup3" to "\u00b3", "uml" to "\u00a8", "cedil" to "\u00b8",
        "sbquo" to "\u201a", "bdquo" to "\u201e", "lsaquo" to "\u2039", "rsaquo" to "\u203a",
        "prime" to "\u2032", "Prime" to "\u2033", "oline" to "\u203e", "frasl" to "\u2044",
        "zwnj" to "\u200c", "zwj" to "\u200d", "lrm" to "\u200e", "rlm" to "\u200f",
        "OElig" to "\u0152", "oelig" to "\u0153", "Scaron" to "\u0160", "scaron" to "\u0161",
        "Yuml" to "\u0178", "fnof" to "\u0192", "circ" to "\u02c6", "tilde" to "\u02dc",
        "thetasym" to "\u03d1", "upsih" to "\u03d2", "piv" to "\u03d6",
        "Agrave" to "\u00c0", "Aacute" to "\u00c1", "Acirc" to "\u00c2", "Atilde" to "\u00c3",
        "Auml" to "\u00c4", "Aring" to "\u00c5", "AElig" to "\u00c6", "Ccedil" to "\u00c7",
        "Egrave" to "\u00c8", "Eacute" to "\u00c9", "Ecirc" to "\u00ca", "Euml" to "\u00cb",
        "Igrave" to "\u00cc", "Iacute" to "\u00cd", "Icirc" to "\u00ce", "Iuml" to "\u00cf",
        "ETH" to "\u00d0", "Ntilde" to "\u00d1", "Ograve" to "\u00d2", "Oacute" to "\u00d3",
        "Ocirc" to "\u00d4", "Otilde" to "\u00d5", "Ouml" to "\u00d6", "Oslash" to "\u00d8",
        "Ugrave" to "\u00d9", "Uacute" to "\u00da", "Ucirc" to "\u00db", "Uuml" to "\u00dc",
        "Yacute" to "\u00dd", "THORN" to "\u00de", "szlig" to "\u00df",
        "agrave" to "\u00e0", "aacute" to "\u00e1", "acirc" to "\u00e2", "atilde" to "\u00e3",
        "auml" to "\u00e4", "aring" to "\u00e5", "aelig" to "\u00e6", "ccedil" to "\u00e7",
        "egrave" to "\u00e8", "eacute" to "\u00e9", "ecirc" to "\u00ea", "euml" to "\u00eb",
        "igrave" to "\u00ec", "iacute" to "\u00ed", "icirc" to "\u00ee", "iuml" to "\u00ef",
        "eth" to "\u00f0", "ntilde" to "\u00f1", "ograve" to "\u00f2", "oacute" to "\u00f3",
        "ocirc" to "\u00f4", "otilde" to "\u00f5", "ouml" to "\u00f6", "oslash" to "\u00f8",
        "ugrave" to "\u00f9", "uacute" to "\u00fa", "ucirc" to "\u00fb", "uuml" to "\u00fc",
        "yacute" to "\u00fd", "thorn" to "\u00fe", "yuml" to "\u00ff",
        "alefsym" to "\u2135", "weierp" to "\u2118", "real" to "\u211c", "image" to "\u2111",
        "larr" to "\u2190", "uarr" to "\u2191", "rarr" to "\u2192", "darr" to "\u2193",
        "harr" to "\u2194", "crarr" to "\u21b5", "lArr" to "\u21d0", "uArr" to "\u21d1",
        "rArr" to "\u21d2", "dArr" to "\u21d3", "hArr" to "\u21d4",
        "forall" to "\u2200", "part" to "\u2202", "exist" to "\u2203", "empty" to "\u2205",
        "nabla" to "\u2207", "isin" to "\u2208", "notin" to "\u2209", "ni" to "\u220b",
        "prod" to "\u220f", "sum" to "\u2211", "minus" to "\u2212", "lowast" to "\u2217",
        "radic" to "\u221a", "prop" to "\u221d", "infin" to "\u221e", "ang" to "\u2220",
        "and" to "\u2227", "or" to "\u2228", "cap" to "\u2229", "cup" to "\u222a",
        "int" to "\u222b", "there4" to "\u2234", "sim" to "\u223c", "cong" to "\u2245",
        "asymp" to "\u2248", "ne" to "\u2260", "equiv" to "\u2261", "le" to "\u2264",
        "ge" to "\u2265", "sub" to "\u2282", "sup" to "\u2283", "nsub" to "\u2284",
        "sube" to "\u2286", "supe" to "\u2287", "oplus" to "\u2295", "otimes" to "\u2297",
        "perp" to "\u22a5", "sdot" to "\u22c5", "lceil" to "\u2308", "rceil" to "\u2309",
        "lfloor" to "\u230a", "rfloor" to "\u230b", "lang" to "\u2329", "rang" to "\u232a",
        "loz" to "\u25ca", "spades" to "\u2660", "clubs" to "\u2663", "hearts" to "\u2665",
        "diams" to "\u2666",
        "Alpha" to "\u0391", "Beta" to "\u0392", "Gamma" to "\u0393", "Delta" to "\u0394",
        "Epsilon" to "\u0395", "Zeta" to "\u0396", "Eta" to "\u0397", "Theta" to "\u0398",
        "Iota" to "\u0399", "Kappa" to "\u039a", "Lambda" to "\u039b", "Mu" to "\u039c",
        "Nu" to "\u039d", "Xi" to "\u039e", "Omicron" to "\u039f", "Pi" to "\u03a0",
        "Rho" to "\u03a1", "Sigma" to "\u03a3", "Tau" to "\u03a4", "Upsilon" to "\u03a5",
        "Phi" to "\u03a6", "Chi" to "\u03a7", "Psi" to "\u03a8", "Omega" to "\u03a9",
        "alpha" to "\u03b1", "beta" to "\u03b2", "gamma" to "\u03b3", "delta" to "\u03b4",
        "epsilon" to "\u03b5", "zeta" to "\u03b6", "eta" to "\u03b7", "theta" to "\u03b8",
        "iota" to "\u03b9", "kappa" to "\u03ba", "lambda" to "\u03bb", "mu" to "\u03bc",
        "nu" to "\u03bd", "xi" to "\u03be", "omicron" to "\u03bf", "pi" to "\u03c0",
        "rho" to "\u03c1", "sigmaf" to "\u03c2", "sigma" to "\u03c3", "tau" to "\u03c4",
        "upsilon" to "\u03c5", "phi" to "\u03c6", "chi" to "\u03c7", "psi" to "\u03c8",
        "omega" to "\u03c9",
    )
}

/**
 * Finds NCX `<text>` label content slots without a DOM parse so writeback can replace only the
 * label range. `docTitle` and `navLabel` text is accepted; `<docAuthor><text>` is skipped because
 * an author is identity metadata. All local names are matched so a namespace prefix such as
 * `<ncx:text>` works. `navPoint` nesting needs no special handling: labels are emitted in document
 * order, while `id`, `playOrder`, `src` and every other attribute stay untouched.
 */
private object NcxLabelScanner {
    private const val MAX_SCANNED_LABELS = 200_000

    fun find(
        text: String,
        onUnknownEntity: (() -> Unit)? = null,
    ): List<ScannedTextNode> {
        val result = ArrayList<ScannedTextNode>()
        var index = 0
        // `docAuthor` also contains a `<text>` element, but an author is identity metadata and
        // must never be translated (the OPF dc:creator is deliberately left untouched too).
        var docAuthorDepth = 0
        while (index < text.length && result.size < MAX_SCANNED_LABELS) {
            val tagStart = text.indexOf('<', index)
            if (tagStart < 0) break
            index = tagStart
            when {
                text.startsWith("<!--", index) -> {
                    val close = text.indexOf("-->", index + 4)
                    index = if (close < 0) text.length else close + 3
                }
                text.startsWith("<![CDATA[", index) -> {
                    val close = text.indexOf("]]>", index + 9)
                    index = if (close < 0) text.length else close + 3
                }
                text.startsWith("<?", index) -> {
                    val close = text.indexOf("?>", index + 2)
                    index = if (close < 0) text.length else close + 2
                }
                text.startsWith("<!", index) -> index = XhtmlTextScanner.declarationEnd(text, index)
                text.startsWith("</", index) -> {
                    val close = text.indexOf('>', index + 2)
                    if (close < 0) break
                    if (docAuthorDepth > 0 &&
                        XhtmlTextScanner.closingName(text.substring(index + 2, close)) == "docauthor"
                    ) {
                        docAuthorDepth -= 1
                    }
                    index = close + 1
                }
                else -> {
                    val close = XhtmlTextScanner.tagEnd(text, index + 1)
                    if (close < 0) break
                    val inside = text.substring(index + 1, close)
                    val selfClosing = inside.trimEnd().endsWith('/')
                    val name = XhtmlTextScanner.startName(inside)
                    if (!selfClosing && name == "docauthor") {
                        docAuthorDepth += 1
                        index = close + 1
                        continue
                    }
                    // The NCX schema places navMap after docAuthor. Reset the skip there so a
                    // malformed missing `</docAuthor>` cannot hide the whole outline.
                    if (!selfClosing && name == "navmap") docAuthorDepth = 0
                    if (!selfClosing && name == "text" && docAuthorDepth == 0) {
                        val slot = findLabelSlot(text, close + 1)
                        // An unterminated label makes every following range ambiguous; stop
                        // instead of rescanning the tail once per malformed start tag.
                        if (slot == null) break
                        val raw = text.substring(slot.contentStart, slot.contentEnd)
                        val decoded = XhtmlTextScanner.decodeTolerantText(raw, onUnknownEntity)
                        if (decoded != null) {
                            result.add(ScannedTextNode(slot.contentStart, slot.contentEnd, raw, decoded,
                                ScannedNodeKind.NCX_TEXT))
                        }
                        index = slot.after
                        continue
                    }
                    index = close + 1
                }
            }
        }
        return result
    }

    private class LabelSlot(val contentStart: Int, val contentEnd: Int, val after: Int)

    /** Locate the matching `</text>` while skipping comments, CDATA and processing instructions. */
    private fun findLabelSlot(text: String, contentStart: Int): LabelSlot? {
        var index = contentStart
        while (index < text.length) {
            val open = text.indexOf('<', index)
            if (open < 0) return null
            when {
                text.startsWith("<![CDATA[", open) -> {
                    val close = text.indexOf("]]>", open + 9)
                    if (close < 0) return null
                    index = close + 3
                }
                text.startsWith("<!--", open) -> {
                    val close = text.indexOf("-->", open + 4)
                    if (close < 0) return null
                    index = close + 3
                }
                text.startsWith("<?", open) -> {
                    val close = text.indexOf("?>", open + 2)
                    if (close < 0) return null
                    index = close + 2
                }
                text.startsWith("</", open) -> {
                    val close = text.indexOf('>', open + 2)
                    if (close < 0) return null
                    val name = XhtmlTextScanner.closingName(text.substring(open + 2, close))
                    if (name == "text") return LabelSlot(contentStart, open, close + 1)
                    index = close + 1
                }
                else -> {
                    val close = XhtmlTextScanner.tagEnd(text, open + 1)
                    if (close < 0) return null
                    index = close + 1
                }
            }
        }
        return null
    }
}
