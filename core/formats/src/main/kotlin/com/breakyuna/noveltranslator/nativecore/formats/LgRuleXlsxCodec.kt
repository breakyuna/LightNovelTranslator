package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*

/** LG's first worksheet / first five columns contract, without evaluating formulas or links. */
object LgRuleXlsxCodec {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_PART = 512 * 1024
    private val columns = listOf("src", "dst", "info", "regex", "case_sensitive")

    fun decode(bytes: ByteArray, kind: TextRuleKind, current: ProjectTextPolicy = ProjectTextPolicy()): RuleExchange =
        LgRuleJsonCodec.decode(toJson(bytes), kind, current)

    fun toJson(bytes: ByteArray): String {
        require(bytes.size <= MAX_BYTES)
        // Apply the small rule-file budget before the generic format container allocates its members.
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var total = 0L; var entries = 0
            val buffer = ByteArray(4096)
            while (zip.nextEntry != null) {
                require(++entries <= 128)
                var part = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    part += count; total += count
                    require(part <= MAX_PART && total <= MAX_BYTES)
                }
                zip.closeEntry()
            }
        }
        val archive = ZipContainer.read(bytes)
        val adapter = XlsxFormatAdapter()
        val workbook = adapter.readWorkbookPackage(archive)
        val sheet = workbook.sheets.firstOrNull() ?: throw IllegalArgumentException("Missing rule sheet")
        val shared = workbook.sharedStringsPath?.let { path ->
            val member = requireNotNull(archive[path])
            childElements(parseXmlDocument(member.bytes).documentElement).filter { localName(it) == "si" }
                .map(adapter::sharedText)
        }.orEmpty()
        val member = requireNotNull(archive[sheet.path])
        val sheetData = firstDescendant(parseXmlDocument(member.bytes).documentElement, "sheetData")
            ?: throw IllegalArgumentException("Missing rule cells")
        val result = ArrayList<JsonObject>()
        val seenRows = HashSet<Int>()
        for (row in childElements(sheetData).filter { localName(it) == "row" }) {
            require(seenRows.size < RuleExchange.MAX_RULES + 1)
            val number = requireNotNull(attributeValue(row, "r")?.toIntOrNull())
            require(number in 1..1_048_576 && seenRows.add(number))
            val values = MutableList(5) { "" }
            val seen = HashSet<Int>()
            for (cell in childElements(row).filter { localName(it) == "c" }) {
                val reference = requireNotNull(attributeValue(cell, "r"))
                require(reference.matches(Regex("[A-Z]{1,3}[1-9][0-9]*")))
                require(reference.dropWhile { it in 'A'..'Z' }.toIntOrNull() == number)
                val column = reference.takeWhile { it in 'A'..'Z' }
                if (column.length != 1 || column[0] !in 'A'..'E') continue
                val index = column[0] - 'A'
                require(seen.add(index))
                val children = childElements(cell)
                require(children.none { localName(it) == "f" })
                val value = children.firstOrNull { localName(it) == "v" }?.let(::elementText).orEmpty()
                values[index] = when (attributeValue(cell, "t")) {
                    "s" -> shared[requireNotNull(value.toIntOrNull()).also { require(it in shared.indices) }]
                    "inlineStr" -> adapter.sharedText(requireNotNull(children.firstOrNull { localName(it) == "is" }))
                    "b" -> when (value) { "1" -> "true"; "0" -> "false"; else -> throw IllegalArgumentException("Invalid rule boolean") }
                    null, "n", "str" -> value
                    else -> throw IllegalArgumentException("Unsupported rule cell")
                }.trim()
            }
            if (values[0].isEmpty() || values[0] == "src" && values[1] == "dst") continue
            require(result.size < RuleExchange.MAX_RULES)
            result.add(buildJsonObject {
                put("src", values[0]); put("dst", values[1]); put("info", values[2])
                for (index in 3..4) {
                    val flag = values[index].lowercase(java.util.Locale.ROOT)
                    require(flag in setOf("", "true", "false"))
                    put(columns[index], flag == "true")
                }
            })
        }
        return JsonArray(result).toString().also { require(it.length <= LgRuleJsonCodec.MAX_CHARS) }
    }

    fun encode(policy: ProjectTextPolicy, kind: TextRuleKind): ByteArray {
        LgRuleJsonCodec.encode(policy, kind)
        val rows = listOf(columns) + policy.rules.filter { it.kind == kind }.map {
            listOf(it.source, it.target, it.note, it.regex.toString(), it.caseSensitive.toString())
        }
        fun xml(value: String): String {
            require(validXmlText(value) == value)
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;")
        }
        val sheet = buildString {
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { index, row ->
                append("<row r=\"${index + 1}\">")
                row.forEachIndexed { column, value ->
                    append("<c r=\"${'A' + column}${index + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    append(xml(value)); append("</t></is></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="rules" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet,
        )
        val members = parts.mapValues { (name, text) ->
            ZipMember(name, ZipEntry.DEFLATED, 0, null, TextProjectFingerprint.utf8(text))
        }
        require(members.values.all { it.bytes.size <= MAX_PART })
        return ZipContainer.write(members).also { require(it.size <= MAX_BYTES) }
    }
}
