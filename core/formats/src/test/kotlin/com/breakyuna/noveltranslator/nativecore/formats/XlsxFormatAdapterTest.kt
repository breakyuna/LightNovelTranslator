package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XlsxFormatAdapterTest {
    private val adapter = XlsxFormatAdapter()
    private val path = RelativePath("book.xlsx")

    @Test fun readsStringCellsFromSharedAndInlineStorageOnly() {
        val document = adapter.read(xlsx(), path)
        assertEquals(ProjectAssetFormat.XLSX, document.format)
        assertEquals(4, document.units.size)
        val tags = document.units.map { it.tag }.toSet()
        assertEquals(setOf("Sheet1!A1", "Sheet1!C1", "Sheet1!D1", "Sheet1!A2"), tags)
        val a1 = document.units.first { it.tag == "Sheet1!A1" }
        assertEquals("Hello", a1.source)
        assertEquals(1, a1.row)
        assertTrue(a1.extraField.contains("\"kind\":\"shared\""))
        assertTrue(a1.extraField.contains("\"index\":0"))
        val a2 = document.units.first { it.tag == "Sheet1!A2" }
        assertEquals("World", a2.source)
        assertEquals(2, a2.row)
        val d1 = document.units.first { it.tag == "Sheet1!D1" }
        assertEquals("Inline", d1.source)
        assertTrue(d1.extraField.contains("\"kind\":\"inline\""))
        assertTrue(document.units.none { it.tag == "Sheet1!B1" || it.tag == "Sheet1!B2" || it.tag == "Sheet1!C2" })
    }

    @Test fun writeUpdatesSharedStringsAndInlineCellsWithoutBreakingOtherIndices() {
        val original = xlsx()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf(
            "Sheet1!A1" to "Bonjour",
            "Sheet1!A2" to "Monde",
            "Sheet1!D1" to "En ligne",
        )), FormatWriteOptions())
        val input = FormatArchiveFixtures.unzip(original)
        val result = FormatArchiveFixtures.unzip(output)
        val shared = FormatArchiveFixtures.text(result.getValue("xl/sharedStrings.xml"))
        assertTrue(shared.contains("Bonjour"))
        assertTrue(shared.contains("Monde"))
        assertTrue(shared.contains("Unused"))
        val sheet = FormatArchiveFixtures.text(result.getValue("xl/worksheets/sheet1.xml"))
        assertTrue(sheet.contains("En ligne"))
        assertTrue(sheet.contains("<v>42</v>"))
        assertTrue(sheet.contains("SUM(B1,1)"))
        assertTrue(sheet.contains("CONCAT("))
        assertTrue(sheet.contains("t=\"s\""))
        for (name in listOf("[Content_Types].xml", "xl/styles.xml", "docProps/core.xml",
            "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels")) {
            assertArrayEquals(name, input.getValue(name), result.getValue(name))
        }
        val reread = adapter.read(output, path)
        assertEquals("Bonjour", reread.units.first { it.tag == "Sheet1!A1" }.source)
        assertEquals("Monde", reread.units.first { it.tag == "Sheet1!A2" }.source)
        assertEquals("En ligne", reread.units.first { it.tag == "Sheet1!D1" }.source)
    }

    @Test fun bilingualInlineCellKeepsTheSourceAndUsesXmlSpacePreserve() {
        val original = xlsx()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf("Sheet1!D1" to "En ligne")),
            FormatWriteOptions(bilingual = true))
        val sheet = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("xl/worksheets/sheet1.xml"))
        assertTrue(sheet.contains("Inline"))
        assertTrue(sheet.contains("En ligne"))
        assertTrue(sheet.contains("xml:space"))
    }

    @Test fun bilingualSharedStringKeepsSourceAndTargetAtTheSameIndex() {
        val original = xlsx()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf("Sheet1!A1" to "Bonjour")),
            FormatWriteOptions(bilingual = true))
        val shared = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("xl/sharedStrings.xml"))
        assertTrue(shared.contains("Hello"))
        assertTrue(shared.contains("Bonjour"))
        val reread = adapter.read(output, path)
        assertEquals("Hello\nBonjour", reread.units.first { it.tag == "Sheet1!A1" }.source)
    }

    @Test fun conflictingTranslationsForOneSharedStringAreNotApplied() {
        val original = xlsx()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf(
            "Sheet1!A1" to "Bonjour",
            "Sheet1!C1" to "Salut",
        )), FormatWriteOptions())
        assertArrayEquals(original, output)
        val shared = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("xl/sharedStrings.xml"))
        assertTrue(shared.contains("Hello"))
    }

    @Test fun writeWithoutProcessedUnitsReturnsTheOriginalWorkbook() {
        val original = xlsx()
        val document = adapter.read(original, path)
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))
    }

    @Test fun malformedAndUnsafeArchivesFailClosed() {
        rejects(FormatContainerException.Reason.STRUCTURE) {
            adapter.read(FormatArchiveFixtures.zip(listOf(
                "[Content_Types].xml" to CONTENT_TYPES.toByteArray())), path)
        }
        rejects(FormatContainerException.Reason.PATH) {
            adapter.read(FormatArchiveFixtures.zip(listOf("../evil" to byteArrayOf(1))), path)
        }
        rejects(FormatContainerException.Reason.STRUCTURE) {
            adapter.read(FormatArchiveFixtures.zip(listOf(
                "xl/workbook.xml" to "not xml".toByteArray())), path)
        }
    }

    @Test fun richTextAndFormulaCellsArePreservedAndNotExtracted() {
        val shared = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "count=\"3\" uniqueCount=\"3\">" +
            "<si><t>Hello</t></si>" +
            "<si><r><rPr><b/></rPr><t>Bold</t></r><r><t> Plain</t></r></si>" +
            "<si><t>Base</t><rPh sb=\"0\" eb=\"4\"><t>Yomi</t></rPh></si></sst>"
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetData><row r=\"1\">" +
            "<c r=\"A1\" t=\"s\"><v>0</v></c>" +
            "<c r=\"B1\" t=\"s\"><v>1</v></c>" +
            "<c r=\"C1\" t=\"str\"><f>CONCAT(\"a\",\"b\")</f><v>ab</v></c>" +
            "<c r=\"D1\" t=\"inlineStr\"><is><r><rPr><i/></rPr><t>Italic</t></r>" +
            "<r><t> tail</t></r></is></c>" +
            "<c r=\"E1\" t=\"inlineStr\"><is><t>Simple</t></is></c>" +
            "<c r=\"F1\" t=\"s\"><v>2</v></c>" +
            "</row></sheetData></worksheet>"
        val original = xlsx(sheet = sheet, shared = shared)
        val document = adapter.read(original, path)
        assertEquals(listOf("Hello", "Simple"), document.units.map { it.source })
        assertTrue(document.warnings.any { it.code == "xlsx.rich_text_skipped" })
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))

        val output = adapter.write(original, translated(document, mapOf(
            "Sheet1!A1" to "Bonjour",
            "Sheet1!E1" to "Simple traduit",
        )), FormatWriteOptions())
        val result = FormatArchiveFixtures.unzip(output)
        val sharedOut = FormatArchiveFixtures.text(result.getValue("xl/sharedStrings.xml"))
        assertTrue(sharedOut.contains("Bold"))
        assertTrue(sharedOut.contains("Plain"))
        assertTrue(sharedOut.contains("Yomi"))
        assertTrue(sharedOut.contains("<rPr>"))
        assertTrue(sharedOut.contains("Bonjour"))
        val sheetOut = FormatArchiveFixtures.text(result.getValue("xl/worksheets/sheet1.xml"))
        assertTrue(sheetOut.contains("Italic"))
        assertTrue(sheetOut.contains("tail"))
        assertTrue(sheetOut.contains("CONCAT("))
        assertTrue(sheetOut.contains("Simple traduit"))
        assertEquals(listOf("Bonjour", "Simple traduit"),
            adapter.read(output, path).units.map { it.source })
    }

    @Test fun outOfRangeSharedStringIndexIsAWarningAndInlineCellsStillRead() {
        val sheet = SHEET.replace("<c r=\"A1\" t=\"s\"><v>0</v></c>",
            "<c r=\"A1\" t=\"s\"><v>99</v></c>")
        val document = adapter.read(xlsx(sheet = sheet), path)
        assertTrue(document.warnings.any { it.code == "xlsx.shared_string_missing" })
        assertEquals("World", document.units.first { it.tag == "Sheet1!A2" }.source)
        assertEquals("Inline", document.units.first { it.tag == "Sheet1!D1" }.source)
        assertTrue(document.units.none { it.tag == "Sheet1!A1" })
    }

    @Test fun missingSharedStringsPartIsAWarningAndInlineCellsStillRead() {
        val document = adapter.read(xlsx(includeShared = false), path)
        assertTrue(document.warnings.any { it.code == "xlsx.missing_shared_strings" })
        assertEquals("Inline", document.units.first { it.tag == "Sheet1!D1" }.source)
        assertTrue(document.units.none { it.tag == "Sheet1!A1" })
    }

    private fun translated(document: FormatDocument, values: Map<String, String>): List<FormatUnit> =
        document.units.map { unit ->
            val target = values[unit.tag]
            if (target == null) unit else FormatUnit(unit.row, unit.source, target,
                TextItemStatus.PROCESSED, unit.tag, extraField = unit.extraField)
        }

    private fun rejects(reason: FormatContainerException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid XLSX")
        } catch (failure: FormatContainerException) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun xlsx(
        sheet: String = SHEET,
        shared: String = SHARED,
        includeShared: Boolean = true,
    ): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries.add("[Content_Types].xml" to CONTENT_TYPES.toByteArray())
        entries.add("_rels/.rels" to ROOT_RELS.toByteArray())
        entries.add("xl/workbook.xml" to WORKBOOK.toByteArray())
        entries.add("xl/_rels/workbook.xml.rels" to RELS.toByteArray())
        entries.add("xl/styles.xml" to STYLES.toByteArray())
        entries.add("xl/worksheets/sheet1.xml" to sheet.toByteArray())
        entries.add("docProps/core.xml" to "<core>metadata</core>".toByteArray())
        if (includeShared) entries.add("xl/sharedStrings.xml" to shared.toByteArray())
        return FormatArchiveFixtures.zip(entries)
    }

    private companion object {
        private const val CONTENT_TYPES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" " +
                "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "</Types>"

        private const val ROOT_RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" " +
                "Target=\"xl/workbook.xml\"/></Relationships>"

        private const val WORKBOOK =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

        private const val RELS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" " +
                "Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" " +
                "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" " +
                "Target=\"sharedStrings.xml\"/></Relationships>"

        private const val SHARED =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                "count=\"4\" uniqueCount=\"3\">" +
                "<si><t>Hello</t></si><si><t>World</t></si><si><t>Unused</t></si></sst>"

        private const val SHEET =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<sheetData>" +
                "<row r=\"1\">" +
                "<c r=\"A1\" t=\"s\"><v>0</v></c>" +
                "<c r=\"B1\"><v>42</v></c>" +
                "<c r=\"C1\" s=\"1\" t=\"s\"><v>0</v></c>" +
                "<c r=\"D1\" t=\"inlineStr\"><is><t>Inline</t></is></c>" +
                "</row>" +
                "<row r=\"2\">" +
                "<c r=\"A2\" t=\"s\"><v>1</v></c>" +
                "<c r=\"B2\"><f>SUM(B1,1)</f><v>43</v></c>" +
                "<c r=\"C2\" t=\"str\"><f>CONCAT(\"x\",\"y\")</f><v>xy</v></c>" +
                "</row>" +
                "</sheetData></worksheet>"

        private const val STYLES =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<fonts count=\"1\"><font><sz val=\"11\"/></font></fonts>" +
                "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>" +
                "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
                "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs></styleSheet>"
    }
}
