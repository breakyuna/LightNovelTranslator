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
 * XLSX adapter for string cells. Reads all worksheets in workbook order and writes only the
 * selected string cells: shared strings stay at their existing indices, inline strings are
 * replaced in place, and styles, formulas, numbers and every unrelated ZIP member stay intact.
 *
 * Formula cells are never extracted or rewritten, even when they carry a cached `t="str"` value,
 * because replacing that value would leave a formula/result mismatch. Rich-text shared/inline
 * strings with `<r>` or phonetic (`rPh`/`phoneticPr`) runs are also left untouched: flattening
 * them into one `<t>` would lose per-run formatting or phonetic data, so they are reported as
 * skipped instead of being silently corrupted.
 */
class XlsxFormatAdapter : BinaryFormatAdapter {
    override val format: ProjectAssetFormat = ProjectAssetFormat.XLSX

    override fun read(bytes: ByteArray, path: RelativePath): FormatDocument {
        val archive = ZipContainer.read(bytes)
        val packageInfo = readWorkbookPackage(archive)
        val warnings = ArrayList<FormatWarning>()
        val sharedPath = packageInfo.sharedStringsPath
        val sharedStrings: List<SharedStringEntry> = if (sharedPath == null) {
            emptyList()
        } else {
            val member = archive[sharedPath]
            if (member == null || member.directory) {
                warnings.add(FormatWarning("xlsx.missing_shared_strings", safePath(sharedPath),
                    null, "Declared shared strings part is missing"))
                emptyList()
            } else if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
                warnings.add(FormatWarning("xlsx.shared_strings_too_large", safePath(sharedPath),
                    null, "Shared strings part exceeds the text entry bound"))
                emptyList()
            } else {
                try {
                    val document = parseXmlDocument(member.bytes)
                    childElements(document.documentElement)
                        .filter { localName(it) == "si" }
                        .map { SharedStringEntry(sharedText(it), hasRichRuns(it)) }
                } catch (_: FormatContainerException) {
                    warnings.add(FormatWarning("xlsx.invalid_shared_strings", safePath(sharedPath),
                        null, "Shared strings XML could not be parsed"))
                    emptyList()
                }
            }
        }
        val units = ArrayList<FormatUnit>()
        var limited = false
        for (sheet in packageInfo.sheets) {
            if (limited) break
            val member = archive[sheet.path]
            if (member == null) {
                warnings.add(FormatWarning("xlsx.missing_sheet", safePath(sheet.path),
                    null, "Worksheet is missing from the archive"))
                continue
            }
            if (member.directory) {
                warnings.add(FormatWarning("xlsx.missing_sheet", safePath(sheet.path),
                    null, "Worksheet is missing from the archive"))
                continue
            }
            if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
                warnings.add(FormatWarning("xlsx.sheet_too_large", safePath(sheet.path),
                    null, "Worksheet exceeds the text entry bound"))
                continue
            }
            val document = try {
                parseXmlDocument(member.bytes)
            } catch (_: FormatContainerException) {
                warnings.add(FormatWarning("xlsx.invalid_sheet", safePath(sheet.path),
                    null, "Worksheet XML could not be parsed"))
                continue
            }
            val sheetData = firstDescendant(document.documentElement, "sheetData") ?: continue
            for (row in childElements(sheetData)) {
                if (localName(row) != "row") continue
                val declaredRow = attributeValue(row, "r")?.toIntOrNull()?.takeIf { it in 1..1_048_576 }
                var cellOrdinal = 0
                for (cell in childElements(row)) {
                    if (localName(cell) != "c") continue
                    val ordinal = cellOrdinal
                    cellOrdinal += 1
                    if (hasFormula(cell)) continue
                    val rawReference = attributeValue(cell, "r")?.trim()
                    val reference = rawReference?.uppercase()
                    val cellReference = if (reference != null && isCellReference(reference)) {
                        reference
                    } else {
                        columnName(ordinal) + (declaredRow ?: 1).toString()
                    }
                    if (!isCellReference(cellReference)) continue
                    val rowNumber = declaredRow
                        ?: cellReference.dropWhile { it.isLetter() }.toIntOrNull()?.takeIf { it in 1..1_048_576 }
                        ?: 1
                    val type = attributeValue(cell, "t")
                    var sharedIndex: Int? = null
                    val source = when (type) {
                        "s" -> {
                            val index = childElements(cell).firstOrNull { localName(it) == "v" }
                                ?.let { elementText(it).trim().toIntOrNull() }
                            if (index == null || index !in sharedStrings.indices) {
                                if (warnings.size < 1_000) {
                                    warnings.add(FormatWarning("xlsx.shared_string_missing",
                                        safePath(sheet.name + "!" + cellReference), rowNumber,
                                        "Shared string index is missing or out of range"))
                                }
                                null
                            } else if (sharedStrings[index].rich) {
                                warnRichText(warnings, sheet.name + "!" + cellReference, rowNumber)
                                null
                            } else {
                                sharedIndex = index
                                sharedStrings[index].text
                            }
                        }
                        "inlineStr" -> {
                            val inline = childElements(cell).firstOrNull { localName(it) == "is" }
                            if (inline == null) {
                                null
                            } else if (hasRichRuns(inline)) {
                                warnRichText(warnings, sheet.name + "!" + cellReference, rowNumber)
                                null
                            } else {
                                sharedText(inline)
                            }
                        }
                        else -> null
                    } ?: continue
                    if (!hasVisibleText(source)) continue
                    if (source.length > ContainerLimits.MAX_UNIT_SOURCE_CHARS) {
                        if (warnings.size < 1_000) {
                            warnings.add(FormatWarning("xlsx.text_too_large",
                                safePath(sheet.name + "!" + cellReference), rowNumber,
                                "String cell exceeds the unit bound"))
                        }
                        continue
                    }
                    if (units.size >= ContainerLimits.MAX_UNITS) {
                        limited = true
                        break
                    }
                    val tag = sheet.name + "!" + cellReference
                    if (tag.length > ContainerLimits.MAX_NAME_CHARS || tag.any(Char::isISOControl)) continue
                    val extra = buildJsonObject {
                        put("v", 1)
                        put("kind", if (sharedIndex != null) "shared" else "inline")
                        sharedIndex?.let { put("index", it) }
                        put("sheet", sheet.name)
                        put("cell", cellReference)
                        put("digest", containerSha256Hex(source))
                    }.toString()
                    units.add(FormatUnit(rowNumber, source, "", TextItemStatus.NONE, tag, extraField = extra))
                }
                if (limited) break
            }
        }
        if (limited) warnings.add(FormatWarning("xlsx.unit_limit", "", null, "XLSX unit limit reached"))
        val metadata = buildJsonObject {
            put("v", 1)
            put("sheets", packageInfo.sheets.size)
            put("shared_strings", sharedPath != null)
        }.toString()
        return FormatDocument(format, units, warnings, "", metadata)
    }

    override fun write(original: ByteArray, units: List<FormatUnit>, options: FormatWriteOptions): ByteArray {
        val archive = ZipContainer.read(original)
        val packageInfo = try {
            readWorkbookPackage(archive)
        } catch (_: FormatContainerException) {
            return original
        }
        val sharedGroups = LinkedHashMap<Int, MutableList<FormatUnit>>()
        val inlineGroups = LinkedHashMap<String, MutableList<FormatUnit>>()
        for (unit in units) {
            val extra = parseJsonObject(unit.extraField) ?: continue
            val kind = extraString(extra, "kind") ?: continue
            val sheet = extraString(extra, "sheet") ?: continue
            val cell = extraString(extra, "cell") ?: continue
            if (unit.tag != sheet + "!" + cell) continue
            when (kind) {
                "shared" -> {
                    val index = extraInt(extra, "index") ?: continue
                    if (index < 0) continue
                    sharedGroups.getOrPut(index) { ArrayList() }.add(unit)
                }
                "inline" -> inlineGroups.getOrPut(sheet + "!" + cell) { ArrayList() }.add(unit)
            }
        }
        if (sharedGroups.isEmpty() && inlineGroups.isEmpty()) return original
        val output = LinkedHashMap<String, ZipMember>(archive)
        var changed = false

        if (sharedGroups.isNotEmpty()) {
            val sharedPath = packageInfo.sharedStringsPath
            val member = sharedPath?.let { archive[it] }
            if (member != null && member.bytes.size <= ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
                val document = try {
                    parseXmlDocument(member.bytes)
                } catch (_: FormatContainerException) {
                    null
                }
                if (document != null) {
                    val sharedItems = childElements(document.documentElement)
                        .filter { localName(it) == "si" }
                    var documentChanged = false
                    for ((index, group) in sharedGroups) {
                        if (index !in sharedItems.indices) continue
                        val item = sharedItems[index]
                        if (hasRichRuns(item)) continue
                        val current = sharedText(item)
                        if (!group.all { unit ->
                                val extra = parseJsonObject(unit.extraField)
                                extra != null && extraString(extra, "digest") == containerSha256Hex(current)
                            }
                        ) {
                            continue
                        }
                        val candidates = group.mapNotNull { effectiveTarget(it, options) }.distinct()
                        if (candidates.size != 1) continue
                        val target = candidates.single()
                        val replacement = if (options.bilingual) current + "\n" + target else target
                        if (replacement == current || replacement.length > MAX_CELL_CHARS) continue
                        replaceTextContainer(item, replacement)
                        documentChanged = true
                    }
                    if (documentChanged) {
                        output[member.name] = member.withBytes(serializeXml(document))
                        changed = true
                    }
                }
            }
        }

        if (inlineGroups.isNotEmpty()) {
            val sheetPaths = HashMap<String, String>()
            for (sheet in packageInfo.sheets) {
                if (!sheetPaths.containsKey(sheet.name)) sheetPaths[sheet.name] = sheet.path
            }
            val byPath = LinkedHashMap<String, MutableList<FormatUnit>>()
            for (group in inlineGroups.values) {
                for (unit in group) {
                    val extra = parseJsonObject(unit.extraField) ?: continue
                    val sheet = extraString(extra, "sheet") ?: continue
                    val targetPath = sheetPaths[sheet] ?: continue
                    byPath.getOrPut(targetPath) { ArrayList() }.add(unit)
                }
            }
            for ((sheetPath, docUnits) in byPath) {
                val member = archive[sheetPath] ?: continue
                if (member.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) continue
                val document = try {
                    parseXmlDocument(member.bytes)
                } catch (_: FormatContainerException) {
                    continue
                }
                val cells = HashMap<String, Element>()
                for (cell in descendants(document.documentElement, "c")) {
                    val reference = attributeValue(cell, "r")?.uppercase() ?: continue
                    if (isCellReference(reference)) cells[reference] = cell
                }
                var documentChanged = false
                val applied = HashSet<String>()
                for (unit in docUnits) {
                    val extra = parseJsonObject(unit.extraField) ?: continue
                    val reference = extraString(extra, "cell")?.uppercase() ?: continue
                    if (reference in applied) continue
                    val cell = cells[reference] ?: continue
                    if (attributeValue(cell, "t") != "inlineStr" || hasFormula(cell)) continue
                    val inline = childElements(cell).firstOrNull { localName(it) == "is" } ?: continue
                    if (hasRichRuns(inline)) continue
                    val current = sharedText(inline)
                    if (extraString(extra, "digest") != containerSha256Hex(current)) continue
                    val target = effectiveTarget(unit, options) ?: continue
                    val replacement = if (options.bilingual) current + "\n" + target else target
                    if (replacement == current || replacement.length > MAX_CELL_CHARS) continue
                    applied.add(reference)
                    replaceTextContainer(inline, replacement)
                    documentChanged = true
                }
                if (documentChanged) {
                    output[sheetPath] = member.withBytes(serializeXml(document))
                    changed = true
                }
            }
        }

        if (!changed) return original
        return ZipContainer.write(output)
    }

    internal fun readWorkbookPackage(archive: Map<String, ZipMember>): WorkbookPackage {
        val workbookPath = findWorkbookPath(archive)
        val workbookMember = archive[workbookPath] ?: throw FormatContainerException(
            FormatContainerException.Reason.STRUCTURE)
        if (workbookMember.bytes.size > ContainerLimits.MAX_TEXT_ENTRY_BYTES) {
            throw FormatContainerException(FormatContainerException.Reason.LIMIT)
        }
        val workbookDocument = parseXmlDocument(workbookMember.bytes)
        val workbookDirectory = workbookPath.substringBeforeLast('/', "")
        val relationships = parseRelationships(archive[relationshipsPath(workbookPath)])
        val sheetsElement = descendants(workbookDocument.documentElement, "sheets").firstOrNull()
        val sheets = ArrayList<SheetReference>()
        if (sheetsElement != null) {
            for ((index, sheet) in childElements(sheetsElement).withIndex()) {
                if (localName(sheet) != "sheet") continue
                val name = attributeValue(sheet, "name")?.takeIf { it.isNotEmpty() } ?: "Sheet${index + 1}"
                val relationship = relationshipId(sheet)?.let { relationships[it] }
                val target = relationship?.target
                val sheetPath = if (target == null || relationship == null || relationship.external) {
                    val fallback = workbookDirectory + "/worksheets/sheet${index + 1}.xml"
                    try {
                        resolveArchivePath("", fallback)
                    } catch (_: FormatContainerException) {
                        null
                    }
                } else {
                    try {
                        resolveArchivePath(workbookDirectory, target)
                    } catch (_: FormatContainerException) {
                        null
                    }
                }
                if (sheetPath != null) sheets.add(SheetReference(name, sheetPath))
            }
        }
        val sharedPath = relationships.values.firstOrNull {
            !it.external && it.type.lowercase().endsWith("/sharedstrings")
        }?.target?.let {
            try {
                resolveArchivePath(workbookDirectory, it)
            } catch (_: FormatContainerException) {
                null
            }
        } ?: ((if (workbookDirectory.isEmpty()) "sharedStrings.xml" else "$workbookDirectory/sharedStrings.xml")
            .takeIf { archive.containsKey(it) })
        return WorkbookPackage(sheets, sharedPath)
    }

    private fun findWorkbookPath(archive: Map<String, ZipMember>): String {
        val rootRelationships = archive["_rels/.rels"]
        if (rootRelationships != null) {
            val document = try {
                parseXmlDocument(rootRelationships.bytes)
            } catch (_: FormatContainerException) {
                null
            }
            if (document != null) {
                val officeDocument = descendants(document.documentElement, "Relationship").firstOrNull { relationship ->
                    attributeValue(relationship, "Type")?.lowercase()?.endsWith("/officedocument") == true &&
                        attributeValue(relationship, "TargetMode")?.equals("External", ignoreCase = true) != true
                }
                val target = officeDocument?.let { attributeValue(it, "Target") }
                if (target != null && target.isNotEmpty() && !target.contains("://")) {
                    try {
                        val path = resolveArchivePath("", target)
                        if (archive.containsKey(path)) return path
                    } catch (_: FormatContainerException) {
                        // Fall through to the standard package path.
                    }
                }
            }
        }
        return "xl/workbook.xml"
    }

    private fun parseRelationships(member: ZipMember?): Map<String, Relationship> {
        if (member == null) return emptyMap()
        val document = try {
            parseXmlDocument(member.bytes)
        } catch (_: FormatContainerException) {
            return emptyMap()
        }
        val relationships = LinkedHashMap<String, Relationship>()
        for (relationship in descendants(document.documentElement, "Relationship")) {
            val id = attributeValue(relationship, "Id") ?: continue
            val target = attributeValue(relationship, "Target") ?: continue
            if (target.isEmpty()) continue
            relationships[id] = Relationship(
                target = target,
                type = attributeValue(relationship, "Type") ?: "",
                external = attributeValue(relationship, "TargetMode")?.equals("External", ignoreCase = true) == true ||
                    target.contains("://"),
            )
        }
        return relationships
    }

    private fun relationshipsPath(partPath: String): String {
        val slash = partPath.lastIndexOf('/')
        val directory = if (slash >= 0) partPath.substring(0, slash) else ""
        val file = if (slash >= 0) partPath.substring(slash + 1) else partPath
        return if (directory.isEmpty()) "_rels/$file.rels" else "$directory/_rels/$file.rels"
    }

    /** Shared strings and inline strings share the same visible-text shape: direct t or r/t runs. */
    internal fun sharedText(element: Element): String {
        val output = StringBuilder()
        for (child in childElements(element)) {
            when (localName(child)) {
                "t" -> output.append(elementText(child))
                "r" -> childElements(child).firstOrNull { localName(it) == "t" }
                    ?.let { output.append(elementText(it)) }
            }
        }
        return output.toString()
    }

    /**
     * A rich-text container keeps per-run formatting or phonetic (`rPh`/`phoneticPr`) data that a
     * single replacement `<t>` would lose.
     */
    internal fun hasRichRuns(element: Element): Boolean =
        childElements(element).any {
            when (localName(it)) {
                "r", "rPh", "phoneticPr" -> true
                else -> false
            }
        }

    private fun warnRichText(warnings: MutableList<FormatWarning>, path: String, row: Int) {
        if (warnings.size >= 1_000) return
        warnings.add(FormatWarning("xlsx.rich_text_skipped", safePath(path), row,
            "Rich-text runs are preserved but not extracted"))
    }

    private fun hasFormula(cell: Element): Boolean =
        childElements(cell).any { localName(it) == "f" }

    private fun replaceTextContainer(parent: Element, text: String) {
        val document = parent.ownerDocument
        while (parent.hasChildNodes()) parent.removeChild(parent.firstChild)
        val namespace = parent.namespaceURI
        val qualified = qualifiedName(parent, "t")
        val element = if (namespace == null) document.createElement(qualified)
        else document.createElementNS(namespace, qualified)
        val value = validXmlText(text)
        if (value.isNotEmpty() && (value.first().isWhitespace() || value.last().isWhitespace() ||
                '\n' in value || '\r' in value || '\t' in value)
        ) {
            element.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
        }
        element.appendChild(document.createTextNode(value))
        parent.appendChild(element)
    }

    private fun qualifiedName(parent: Element, local: String): String {
        val name = parent.nodeName
        val colon = name.indexOf(':')
        return if (colon > 0) name.substring(0, colon + 1) + local else local
    }

    private fun isCellReference(value: String): Boolean {
        var index = 0
        while (index < value.length && value[index] in 'A'..'Z') index += 1
        if (index == 0 || index > 3 || index == value.length) return false
        val row = value.substring(index).toIntOrNull() ?: return false
        return row in 1..1_048_576
    }

    private fun columnName(index: Int): String {
        val output = StringBuilder()
        var value = index + 1
        while (value > 0) {
            val remainder = (value - 1) % 26
            output.append(('A'.code + remainder).toChar())
            value = (value - 1) / 26
        }
        return output.reverse().toString()
    }

    private fun safePath(value: String): String =
        if (value.length <= 4_096 && value.none(Char::isISOControl)) value else ""

    internal class SheetReference(val name: String, val path: String)
    internal class WorkbookPackage(val sheets: List<SheetReference>, val sharedStringsPath: String?)
    private class SharedStringEntry(val text: String, val rich: Boolean)
    private class Relationship(val target: String, val type: String, val external: Boolean)

    private companion object {
        /** Excel's hard cell-text limit; a longer value would be truncated or repaired by Excel. */
        private const val MAX_CELL_CHARS = 32_767
    }
}
