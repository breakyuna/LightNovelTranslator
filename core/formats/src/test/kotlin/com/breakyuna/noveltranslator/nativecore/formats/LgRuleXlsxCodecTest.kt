package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class LgRuleXlsxCodecTest {
    private val kind = TextRuleKind.PRE_REPLACE
    private val rule = ProjectTextRule("r", kind, "a<&", "b\r\nc", "説明", regex = true, caseSensitive = true)
    private val policy = ProjectTextPolicy(listOf(rule))

    @Test fun exportedWorkbookRoundTripsFiveColumnsAndOriginalIdentity() {
        val bytes = LgRuleXlsxCodec.encode(policy, kind)
        assertEquals(rule, LgRuleXlsxCodec.decode(bytes, kind, policy).rules.single())
        val document = XlsxFormatAdapter().read(bytes, RelativePath("rules.xlsx"))
        assertEquals(10, document.units.size)
        assertTrue(document.warnings.isEmpty())
    }

    @Test fun sharedStringsAndBooleanCellsUseTheSameExternalSchema() {
        val members = FormatArchiveFixtures.unzip(LgRuleXlsxCodec.encode(policy, kind))
        members["xl/sharedStrings.xml"] = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>source</t></si></sst>""".toByteArray()
        members["xl/worksheets/sheet1.xml"] = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="2"><c r="A2" t="s"><v>0</v></c><c r="B2" t="inlineStr"><is><r><t>target</t></r></is></c><c r="D2" t="b"><v>1</v></c><c r="E2" t="b"><v>0</v></c></row></sheetData></worksheet>""".toByteArray()
        val result = LgRuleXlsxCodec.decode(FormatArchiveFixtures.zip(members.toList()), kind).rules.single()
        assertEquals("source", result.source); assertEquals("target", result.target)
        assertTrue(result.regex); assertFalse(result.caseSensitive)
    }

    @Test fun formulaDuplicateCellAndInvalidSharedIndexRejectTheWholeImport() {
        for (cells in listOf("<c r=\"A2\"><f>EXTERNAL()</f><v>source</v></c>",
            "<c r=\"A2\" t=\"s\"><v>999</v></c>",
            "<c r=\"A2\"/><c r=\"A2\"/>")) {
            val members = FormatArchiveFixtures.unzip(LgRuleXlsxCodec.encode(policy, kind))
            members["xl/worksheets/sheet1.xml"] = "<worksheet><sheetData><row r=\"2\">$cells</row></sheetData></worksheet>".toByteArray()
            try { LgRuleXlsxCodec.decode(FormatArchiveFixtures.zip(members.toList()), kind); fail("Accepted invalid rule workbook") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun oversizeInflationAndDisabledRulesAreRejected() {
        val inflated = FormatArchiveFixtures.zip(listOf("large.xml" to ByteArray(513 * 1024) { 32 }))
        try { LgRuleXlsxCodec.toJson(inflated); fail("Accepted oversized part") }
        catch (_: IllegalArgumentException) { }
        try { LgRuleXlsxCodec.encode(ProjectTextPolicy(listOf(rule.copy(enabled = false))), kind); fail("Enabled disabled rule") }
        catch (_: IllegalArgumentException) { }
    }
}
