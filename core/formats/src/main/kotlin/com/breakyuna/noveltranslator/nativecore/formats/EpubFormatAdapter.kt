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

/**
 * EPUB package rewriter. It parses only `META-INF/container.xml`, the OPF package document and
 * spine XHTML documents. Every other member (nav, NCX, images, fonts, CSS, OPF itself) is copied
 * literally, and XHTML edits are offset splices inside existing text nodes rather than a whole
 * document re-serialization.
 */
class EpubFormatAdapter : BinaryFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.EPUB

    override fun read(bytes: ByteArray, path: RelativePath): FormatDocument {
        val archive = ZipContainer.read(bytes)
        val packageInfo = readPackage(archive)
        val units = ArrayList<FormatUnit>()
        val warnings = ArrayList<FormatWarning>()
        var limited = false
        val readDocuments = HashSet<String>()
        for (documentPath in packageInfo.spinePaths) {
            if (!isHtmlDocumentPath(documentPath)) continue
            if (documentPath == packageInfo.navPath || documentPath == packageInfo.ncxPath) continue
            if (!readDocuments.add(documentPath)) continue
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
            for (node in XhtmlTextScanner.scan(text)) {
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
                            null, "A text node exceeds the unit bound"))
                    }
                    continue
                }
                val extra = extraField(
                    "v" to jsonInt(1),
                    "start" to jsonInt(node.start),
                    "end" to jsonInt(node.end),
                    "digest" to jsonString(containerSha256Hex(node.raw)),
                )
                units.add(FormatUnit(units.size, source, "", TextItemStatus.NONE, documentPath,
                    extraField = extra))
            }
            if (limited) break
        }
        if (limited) warnings.add(FormatWarning("epub.unit_limit", "", null, "EPUB unit limit reached"))
        if (!archive.containsKey("mimetype")) {
            warnings.add(FormatWarning("epub.missing_mimetype", "mimetype", null,
                "EPUB has no mimetype entry"))
        }
        val metadata = buildJsonObject {
            put("v", 1)
            put("opf", packageInfo.opfPath)
            put("spine", packageInfo.spinePaths.size)
            packageInfo.navPath?.let { put("nav", it) }
            packageInfo.ncxPath?.let { put("ncx", it) }
        }.toString()
        return FormatDocument(format, units, warnings, "", metadata)
    }

    override fun write(original: ByteArray, units: List<FormatUnit>, options: FormatWriteOptions): ByteArray {
        val archive = ZipContainer.read(original)
        val packageInfo = try {
            readPackage(archive)
        } catch (_: FormatContainerException) {
            return original
        }
        val protectedPaths = buildSet {
            packageInfo.navPath?.let { add(it) }
            packageInfo.ncxPath?.let { add(it) }
        }
        // Only spine XHTML documents explicitly exposed by read() are writable.
        val allowed = packageInfo.spinePaths
            .asSequence()
            .filter { isHtmlDocumentPath(it) && it !in protectedPaths }
            .toSet()
        val byDocument = LinkedHashMap<String, MutableList<FormatUnit>>()
        for (unit in units) {
            if (unit.tag !in allowed) continue
            byDocument.getOrPut(unit.tag) { ArrayList() }.add(unit)
        }
        if (byDocument.isEmpty()) return original
        val output = LinkedHashMap<String, ZipMember>(archive)
        var changed = false
        for ((documentPath, documentUnits) in byDocument) {
            val member = archive[documentPath] ?: continue
            if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) continue
            val text = decodeUtf8OrNull(member.bytes) ?: continue
            val textNodes = XhtmlTextScanner.scan(text).associateBy { it.start to it.end }
            val replacements = ArrayList<TextReplacement>()
            val claimed = HashSet<Int>()
            for (unit in documentUnits) {
                val extra = parseJsonObject(unit.extraField) ?: continue
                val start = extraInt(extra, "start") ?: continue
                val end = extraInt(extra, "end") ?: continue
                if (start < 0 || end <= start || end > text.length) continue
                if (start in claimed) continue
                val node = textNodes[start to end] ?: continue
                if (extraString(extra, "digest") != containerSha256Hex(node.raw)) continue
                val target = effectiveTarget(unit, options) ?: continue
                claimed.add(start)
                val canonicalSource = normalizeInlineWhitespace(node.text)
                val replacement = if (options.bilingual) canonicalSource + "\n" + target else target
                replacements.add(TextReplacement(start, end, escapeXmlText(replacement)))
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
        if (!changed) return original
        return ZipContainer.write(output, firstEntry = "mimetype", forcedStored = setOf("mimetype"))
    }

    private fun readPackage(archive: Map<String, ZipMember>): EpubPackage {
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
        val spinePaths = ArrayList<String>()
        if (spine != null) {
            for (itemref in childElements(spine)) {
                if (localName(itemref) != "itemref") continue
                val idref = attributeValue(itemref, "idref") ?: continue
                val item = items[idref] ?: continue
                if (item.path.isNotEmpty()) spinePaths.add(item.path)
            }
        }
        return EpubPackage(opfPath, spinePaths, navPath, ncxPath)
    }

    private class EpubPackage(
        val opfPath: String,
        val spinePaths: List<String>,
        val navPath: String?,
        val ncxPath: String?,
    )

    private class ManifestItem(val path: String, val mediaType: String, val properties: String)
    private class TextReplacement(val start: Int, val end: Int, val text: String)

    private fun safeWarningPath(value: String): String =
        if (value.length <= 4_096 && value.none(Char::isISOControl)) value else ""
}

/** One decoded text node together with its exact UTF-16 splice range. */
private class ScannedTextNode(val start: Int, val end: Int, val raw: String, val text: String)

/**
 * Small tolerant XHTML scanner used instead of a DOM parse so markup outside the replaced text
 * nodes stays byte-identical. It understands comments, declarations, CDATA, processing
 * instructions, quoted attributes, self-closing tags and raw script/style bodies.
 */
private object XhtmlTextScanner {
    private const val MAX_SCANNED_TEXT_NODES = 200_000
    private val SKIP_SUBTREES = setOf(
        "head", "script", "style", "code", "pre", "kbd", "samp", "var", "noscript",
        "rt", "rp", "svg", "math",
    )

    fun scan(text: String): List<ScannedTextNode> {
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
                val decoded = decodeXmlText(rawText)
                if (decoded != null) {
                    result.add(ScannedTextNode(index, textEnd, rawText, decoded))
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

    private fun decodeXmlText(raw: String): String? {
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
            if (semicolon < 0 || semicolon - index > 32) return null
            val decoded = decodeEntity(raw.substring(index + 1, semicolon)) ?: return null
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
    )
}
