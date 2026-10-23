package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/** Classified container failure; never contains archive bodies or translated text. */
class FormatContainerException(val reason: Reason) : IllegalArgumentException("Invalid format: $reason") {
    enum class Reason { ARCHIVE, PATH, LIMIT, STRUCTURE, TEXT, ENTRY, UNSUPPORTED }
}

/** Prototype bounds, not measured device budgets. */
internal object ContainerLimits {
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRIES = 200_000
    const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
    const val MAX_TOTAL_UNCOMPRESSED = 256 * 1024 * 1024
    const val MAX_TEXT_ENTRY_BYTES = 32 * 1024 * 1024
    const val MAX_UNITS = 100_000
    const val MAX_NAME_CHARS = 4_096
    const val MAX_OUTPUT_BYTES = 256 * 1024 * 1024
    const val MAX_UNIT_SOURCE_CHARS = 65_536
    const val MAX_JSON_PATH = 64
}

/** One complete ZIP member kept in its original order. */
internal class ZipMember(
    val name: String,
    val method: Int,
    val time: Long,
    val comment: String?,
    val bytes: ByteArray,
) {
    val directory: Boolean get() = name.endsWith('/')
    fun withBytes(value: ByteArray): ZipMember = ZipMember(name, method, time, comment, value)
}

/**
 * Bounded ZIP container. Reads every member into memory so the adapters can edit one part while
 * copying all other member payloads literally. Entry order, compression method and timestamps are
 * retained; re-compression can change compressed bytes but never the untouched payload bytes.
 */
internal object ZipContainer {
    fun read(bytes: ByteArray): LinkedHashMap<String, ZipMember> {
        if (bytes.size > ContainerLimits.MAX_INPUT_BYTES) throw FormatContainerException(FormatContainerException.Reason.LIMIT)
        val result = LinkedHashMap<String, ZipMember>()
        val keys = HashSet<String>()
        val fileKeys = HashSet<String>()
        val directoryKeys = HashSet<String>()
        var total = 0L
        var count = 0
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count += 1
                    if (count > ContainerLimits.MAX_ENTRIES) throw FormatContainerException(FormatContainerException.Reason.LIMIT)
                    val name = validateArchiveName(entry.name ?: throw FormatContainerException(FormatContainerException.Reason.PATH))
                    val directory = name.endsWith('/')
                    val key = name.lowercase()
                    if (!keys.add(key)) throw FormatContainerException(FormatContainerException.Reason.PATH)
                    if (directory) directoryKeys.add(key.removeSuffix("/")) else fileKeys.add(key)
                    val data = if (directory) {
                        val payload = readEntry(zip, 0)
                        if (payload.isNotEmpty()) throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
                        ByteArray(0)
                    } else {
                        val remaining = ContainerLimits.MAX_TOTAL_UNCOMPRESSED - total
                        if (remaining <= 0) throw FormatContainerException(FormatContainerException.Reason.LIMIT)
                        val limit = minOf(ContainerLimits.MAX_ENTRY_BYTES.toLong(), remaining).toInt()
                        val payload = readEntry(zip, limit)
                        total += payload.size
                        payload
                    }
                    if (entry.method == ZipEntry.STORED) {
                        // A zero local size/CRC can mean a data descriptor, so only verify values
                        // that the local header actually provided.
                        if (entry.size > 0 && entry.size != data.size.toLong()) {
                            throw FormatContainerException(FormatContainerException.Reason.ARCHIVE)
                        }
                        if (entry.crc != 0L) {
                            val crc = CRC32()
                            crc.update(data)
                            if (crc.value != entry.crc) {
                                throw FormatContainerException(FormatContainerException.Reason.ARCHIVE)
                            }
                        }
                    }
                    result[name] = ZipMember(name, entry.method, entry.time, entry.comment, data)
                    zip.closeEntry()
                }
            }
        } catch (failure: FormatContainerException) {
            throw failure
        } catch (_: IOException) {
            throw FormatContainerException(FormatContainerException.Reason.ARCHIVE)
        } catch (_: RuntimeException) {
            throw FormatContainerException(FormatContainerException.Reason.ARCHIVE)
        }
        for (file in fileKeys) {
            var slash = file.indexOf('/')
            while (slash >= 0) {
                if (fileKeys.contains(file.substring(0, slash))) {
                    throw FormatContainerException(FormatContainerException.Reason.PATH)
                }
                slash = file.indexOf('/', slash + 1)
            }
        }
        for (directory in directoryKeys) {
            if (fileKeys.contains(directory)) throw FormatContainerException(FormatContainerException.Reason.PATH)
        }
        return result
    }

    fun write(
        members: Map<String, ZipMember>,
        firstEntry: String? = null,
        forcedStored: Set<String> = emptySet(),
    ): ByteArray {
        val ordered = LinkedHashMap<String, ZipMember>()
        if (firstEntry != null) members[firstEntry]?.let { ordered[it.name] = it }
        for (member in members.values) if (member.name != firstEntry) ordered[member.name] = member
        var total = 0L
        for (member in ordered.values) {
            if (member.directory) continue
            total += member.bytes.size
            if (total > ContainerLimits.MAX_TOTAL_UNCOMPRESSED) {
                throw FormatContainerException(FormatContainerException.Reason.LIMIT)
            }
        }
        val output = ByteArrayOutputStream(minOf(total, 16L * 1024 * 1024).toInt())
        try {
            ZipOutputStream(output).use { zip ->
                for (member in ordered.values) {
                    val entry = ZipEntry(member.name)
                    val stored = member.directory || member.name in forcedStored || member.method == ZipEntry.STORED
                    entry.method = if (stored) ZipEntry.STORED else ZipEntry.DEFLATED
                    if (member.time > 0) entry.time = member.time
                    val comment = member.comment
                    if (!comment.isNullOrEmpty() && comment.toByteArray(Charsets.UTF_8).size <= 65_535) {
                        entry.comment = comment
                    }
                    if (stored) {
                        val crc = CRC32()
                        if (!member.directory) crc.update(member.bytes)
                        entry.size = member.bytes.size.toLong()
                        entry.compressedSize = member.bytes.size.toLong()
                        entry.crc = crc.value
                    }
                    zip.putNextEntry(entry)
                    if (!member.directory) zip.write(member.bytes)
                    zip.closeEntry()
                }
            }
        } catch (_: IOException) {
            throw FormatContainerException(FormatContainerException.Reason.ARCHIVE)
        }
        val result = output.toByteArray()
        if (result.size > ContainerLimits.MAX_OUTPUT_BYTES) throw FormatContainerException(FormatContainerException.Reason.LIMIT)
        return result
    }

    private fun readEntry(zip: ZipInputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8_192))
        val buffer = ByteArray(8_192)
        var count = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            count += read
            if (count > limit) throw FormatContainerException(FormatContainerException.Reason.LIMIT)
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}

/** Canonical member path; rejects traversal, absolute paths, Windows aliases and controls. */
internal fun validateArchiveName(raw: String): String {
    if (raw.isEmpty() || raw.length > ContainerLimits.MAX_NAME_CHARS) {
        throw FormatContainerException(FormatContainerException.Reason.PATH)
    }
    if (raw.any(Char::isISOControl)) {
        throw FormatContainerException(FormatContainerException.Reason.PATH)
    }
    // LG normalizes Windows separators before validating; traversal is still rejected below.
    val normalized = raw.replace('\\', '/')
    val body = if (normalized.endsWith('/')) normalized.dropLast(1) else normalized
    if (body.isEmpty() || body.startsWith('/')) throw FormatContainerException(FormatContainerException.Reason.PATH)
    if (body.length >= 2 && body[1] == ':' && body[0].isLetter()) {
        throw FormatContainerException(FormatContainerException.Reason.PATH)
    }
    val segments = body.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
        throw FormatContainerException(FormatContainerException.Reason.PATH)
    }
    return normalized
}

/** Resolve a URI-ish relative part path and reject any escape above the package root. */
internal fun resolveArchivePath(baseDirectory: String, href: String): String {
    val withoutFragment = href.substringBefore('#').substringBefore('?').replace('\\', '/')
    val decoded = percentDecodeOrOriginal(withoutFragment)
    val combined = if (decoded.startsWith('/')) decoded else {
        if (baseDirectory.isEmpty()) decoded else baseDirectory.trimEnd('/') + "/" + decoded
    }
    val output = ArrayList<String>()
    for (segment in combined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> {
                if (output.isEmpty()) throw FormatContainerException(FormatContainerException.Reason.PATH)
                output.removeAt(output.size - 1)
            }
            else -> output.add(segment)
        }
    }
    if (output.isEmpty()) throw FormatContainerException(FormatContainerException.Reason.PATH)
    return validateArchiveName(output.joinToString("/"))
}

/** Decode UTF-8 percent escapes; invalid escapes stay literal, as in common EPUB tools. */
private fun percentDecodeOrOriginal(value: String): String {
    if ('%' !in value) return value
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character == '%' && index + 2 < value.length) {
            val high = hexValue(value[index + 1])
            val low = hexValue(value[index + 2])
            if (high >= 0 && low >= 0) {
                bytes.write((high shl 4) or low)
                index += 3
                continue
            }
        }
        val encoded = character.toString().toByteArray(Charsets.UTF_8)
        bytes.write(encoded, 0, encoded.size)
        index += 1
    }
    return try {
        decodeUtf8(bytes.toByteArray())
    } catch (_: FormatContainerException) {
        value
    }
}

private fun hexValue(character: Char): Int = when (character) {
    in '0'..'9' -> character - '0'
    in 'a'..'f' -> character - 'a' + 10
    in 'A'..'F' -> character - 'A' + 10
    else -> -1
}

internal fun decodeUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: CharacterCodingException) {
    throw FormatContainerException(FormatContainerException.Reason.TEXT)
}

internal fun decodeUtf8OrNull(bytes: ByteArray): String? = try {
    decodeUtf8(bytes)
} catch (_: FormatContainerException) {
    null
}

internal fun encodeUtf8(value: String): ByteArray = try {
    val buffer = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(java.nio.CharBuffer.wrap(value))
    ByteArray(buffer.remaining()).also { buffer.get(it) }
} catch (_: CharacterCodingException) {
    throw FormatContainerException(FormatContainerException.Reason.TEXT)
}

internal fun containerSha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }

internal fun containerSha256Hex(value: String): String = try {
    containerSha256Hex(encodeUtf8(value))
} catch (_: FormatContainerException) {
    // Locator digests must not abort a whole archive; the unit itself is rejected as invalid text.
    containerSha256Hex(value.toByteArray(Charsets.UTF_8))
}

internal fun isValidUtf8Text(value: String): Boolean = try {
    encodeUtf8(value)
    true
} catch (_: FormatContainerException) {
    false
}

/**
 * Target text a writer may apply. Non-processed units never change the source asset; a processed
 * unit equal to its source is a no-op so untouched bytes can stay literal.
 */
internal fun effectiveTarget(unit: FormatUnit, options: FormatWriteOptions): String? {
    if (unit.status != TextItemStatus.PROCESSED) return null
    val target = unit.translation
    if (!options.bilingual) return if (target == unit.source) null else target
    if (target.isEmpty()) return null
    if (options.deduplicateSame && target == unit.source) return null
    return target
}

/** Collapse inline whitespace like the reviewed LG EPUB slot protocol, without trimming. */
internal fun normalizeInlineWhitespace(value: String): String {
    if ('\r' !in value && '\n' !in value && '\t' !in value && "  " !in value) return value
    val output = StringBuilder(value.length)
    var pendingSpace = false
    for (character in value) {
        if (character == ' ' || character == '\r' || character == '\n' || character == '\t') {
            if (!pendingSpace) {
                output.append(' ')
                pendingSpace = true
            }
        } else {
            output.append(character)
            pendingSpace = false
        }
    }
    return output.toString()
}

internal fun hasVisibleText(value: String): Boolean =
    value.any { !it.isWhitespace() && it != '\uFEFF' }

internal fun isHtmlDocumentPath(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm") || lower.endsWith(".xhtm")
}

internal fun parseXmlDocument(bytes: ByteArray): Document {
    val text = decodeUtf8(bytes).removePrefix("\uFEFF")
    if (text.contains("<!ENTITY", ignoreCase = true)) {
        throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
    }
    try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Internal entity declarations are rejected above; predefined entities must still decode.
        factory.isExpandEntityReferences = true
        setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureQuietly(factory, "http://apache.org/xml/features/disallow-doctype-decl", false)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttributeQuietly(factory, "http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttributeQuietly(factory, "http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(text)))
    } catch (failure: FormatContainerException) {
        throw failure
    } catch (_: Exception) {
        throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
    }
}

private fun setFeatureQuietly(factory: DocumentBuilderFactory, name: String, value: Boolean) {
    try { factory.setFeature(name, value) } catch (_: Exception) { /* platform parser may not expose it */ }
}

private fun setAttributeQuietly(factory: DocumentBuilderFactory, name: String, value: String) {
    try { factory.setAttribute(name, value) } catch (_: Exception) { /* platform parser may not expose it */ }
}

internal fun serializeXml(document: Document): ByteArray {
    try {
        val factory = TransformerFactory.newInstance()
        try { factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) } catch (_: Exception) { }
        try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") } catch (_: Exception) { }
        try { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") } catch (_: Exception) { }
        val transformer = factory.newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        val output = ByteArrayOutputStream()
        transformer.transform(DOMSource(document), StreamResult(output))
        return output.toByteArray()
    } catch (_: Exception) {
        throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
    }
}

internal fun localName(node: Node): String {
    val local = node.localName
    if (!local.isNullOrEmpty()) return local
    return node.nodeName.substringAfterLast(':').substringAfterLast('}')
}

internal fun childElements(parent: Element): List<Element> {
    val result = ArrayList<Element>()
    var child = parent.firstChild
    while (child != null) {
        if (child.nodeType == Node.ELEMENT_NODE) result.add(child as Element)
        child = child.nextSibling
    }
    return result
}

internal fun descendants(root: Element, local: String): List<Element> {
    val result = ArrayList<Element>()
    val stack = java.util.ArrayDeque<Element>()
    val rootChildren = childElements(root)
    for (index in rootChildren.indices.reversed()) stack.addLast(rootChildren[index])
    var visited = 0
    while (stack.isNotEmpty()) {
        visited += 1
        if (visited > 1_000_000) {
            throw FormatContainerException(FormatContainerException.Reason.LIMIT)
        }
        val element = stack.removeLast()
        if (localName(element) == local) result.add(element)
        val children = childElements(element)
        for (index in children.indices.reversed()) stack.addLast(children[index])
    }
    return result
}

internal fun firstDescendant(root: Element, local: String): Element? {
    val stack = java.util.ArrayDeque<Element>()
    val rootChildren = childElements(root)
    for (index in rootChildren.indices.reversed()) stack.addLast(rootChildren[index])
    var visited = 0
    while (stack.isNotEmpty()) {
        visited += 1
        if (visited > 1_000_000) {
            throw FormatContainerException(FormatContainerException.Reason.LIMIT)
        }
        val element = stack.removeLast()
        if (localName(element) == local) return element
        val children = childElements(element)
        for (index in children.indices.reversed()) stack.addLast(children[index])
    }
    return null
}

internal fun attributeValue(element: Element, name: String): String? {
    if (element.hasAttribute(name)) return element.getAttribute(name)
    val attributes = element.attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (localName(attribute) == name || attribute.nodeName == name) return attribute.nodeValue
    }
    return null
}

internal fun relationshipId(element: Element): String? {
    val attributes = element.attributes
    for (index in 0 until attributes.length) {
        val attribute = attributes.item(index)
        if (localName(attribute) == "id" &&
            (attribute.namespaceURI?.contains("relationships") == true || attribute.nodeName.startsWith("r:"))
        ) {
            return attribute.nodeValue
        }
    }
    return attributeValue(element, "r:id")
}

internal fun elementText(element: Element): String = element.textContent ?: ""

internal fun parseJsonObject(value: String): JsonObject? = try {
    Json.parseToJsonElement(value) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun jsonPathArray(path: List<Any>): JsonArray = buildJsonArray {
    for (key in path) when (key) {
        is Int -> add(JsonPrimitive(key))
        is String -> add(JsonPrimitive(key))
        else -> throw FormatContainerException(FormatContainerException.Reason.STRUCTURE)
    }
}

internal fun parseJsonPath(element: JsonElement?): List<Any>? {
    val array = element as? JsonArray ?: return null
    if (array.size > ContainerLimits.MAX_JSON_PATH) return null
    val path = ArrayList<Any>(array.size)
    for (entry in array) {
        val primitive = entry as? JsonPrimitive ?: return null
        if (primitive.isString) {
            val key = primitive.content
            if ('\u0000' in key || !isValidUtf8Text(key)) return null
            path.add(key)
        } else {
            path.add(primitive.intOrNull ?: return null)
        }
    }
    return path
}

internal fun extraString(extra: JsonObject, key: String): String? =
    (extra[key] as? JsonPrimitive)?.contentOrNull

internal fun extraInt(extra: JsonObject, key: String): Int? =
    (extra[key] as? JsonPrimitive)?.intOrNull

/** XML 1.0 valid text with entity escaping; invalid controls/surrogates are dropped. */
internal fun escapeXmlText(value: String): String {
    val output = StringBuilder(value.length + 16)
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val width = Character.charCount(codePoint)
        if (isValidXmlCodePoint(codePoint)) {
            when (codePoint) {
                '&'.code -> output.append("&amp;")
                '<'.code -> output.append("&lt;")
                '>'.code -> output.append("&gt;")
                else -> output.appendCodePoint(codePoint)
            }
        }
        index += width
    }
    return output.toString()
}

/** XML text used through the DOM, where the serializer escapes markup itself. */
internal fun validXmlText(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (isValidXmlCodePoint(codePoint)) output.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return output.toString()
}

internal fun isValidXmlCodePoint(codePoint: Int): Boolean =
    codePoint == 0x09 || codePoint == 0x0a || codePoint == 0x0d ||
        (codePoint in 0x20..0xd7ff) || (codePoint in 0xe000..0xfffd) ||
        (codePoint in 0x10000..0x10ffff)

internal fun extraField(vararg entries: Pair<String, JsonElement>): String =
    JsonObject(linkedMapOf<String, JsonElement>(*entries)).toString()

internal fun jsonString(value: String): JsonElement = JsonPrimitive(value)
internal fun jsonInt(value: Int): JsonElement = JsonPrimitive(value)
