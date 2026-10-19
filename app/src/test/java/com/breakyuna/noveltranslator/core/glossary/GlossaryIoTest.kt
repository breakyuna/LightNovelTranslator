package com.breakyuna.noveltranslator.core.glossary

import com.breakyuna.noveltranslator.data.model.LexiconEntryEntity
import com.breakyuna.noveltranslator.data.model.LexiconKind
import com.breakyuna.noveltranslator.data.model.LexiconSource
import com.breakyuna.noveltranslator.data.model.ReviewStatus
import com.breakyuna.noveltranslator.data.model.TermCategory
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryIoTest {

    private val sampleEntries = listOf(
        GlossaryTransferEntry(
            sourceTerm = "アリス",
            targetTerm = "爱丽丝",
            kind = "PROPER_NOUN",
            category = "CHARACTER",
            aliases = listOf("Alice", "アリス"),
            notes = "含,逗号与\"引号\"",
            caseSensitive = true,
            exactMatch = false,
            priority = 7,
        ),
        GlossaryTransferEntry(sourceTerm = "魔導具", targetTerm = "魔导具", kind = "TERMINOLOGY", category = "ITEM")
    )

    // ---------- JSON 导出 / 往返 ----------

    @Test
    fun jsonRoundTripPreservesEveryField() {
        val json = GlossaryIo.exportToJson(sampleEntries)
        val parsed = GlossaryIo.parseImportJson(json)
        assertTrue(parsed.errors.isEmpty())
        assertEquals(sampleEntries, parsed.entries)
    }

    @Test
    fun jsonExportUsesStablePrettyLayoutAndBomIsOptional() {
        assertEquals(
            """[
    {
        "sourceTerm": "a",
        "targetTerm": "b",
        "kind": "PROPER_NOUN",
        "category": "CUSTOM",
        "aliases": [],
        "notes": "",
        "caseSensitive": false,
        "exactMatch": true,
        "priority": 0
    }
]""",
            GlossaryIo.exportToJson(listOf(GlossaryTransferEntry(sourceTerm = "a", targetTerm = "b")))
        )
        assertEquals("[]", GlossaryIo.exportToJson(emptyList()))
        assertTrue(GlossaryIo.exportToJson(sampleEntries, includeBom = true).startsWith("\uFEFF"))
        assertFalse(GlossaryIo.exportToJson(sampleEntries).startsWith("\uFEFF"))
        // 导入侧同样容忍 BOM（Excel/记事本保存的 UTF-8）
        assertEquals(
            sampleEntries,
            GlossaryIo.parseImportJson(GlossaryIo.exportToJson(sampleEntries, includeBom = true)).entries
        )
    }

    @Test
    fun compactJsonRoundTripsWithoutNewlines() {
        val entries = listOf(GlossaryTransferEntry(sourceTerm = "a", targetTerm = "b"))
        val json = GlossaryIo.exportToJson(entries, pretty = false)
        assertEquals(
            """[{"sourceTerm":"a","targetTerm":"b","kind":"PROPER_NOUN","category":"CUSTOM","aliases":[],"notes":"","caseSensitive":false,"exactMatch":true,"priority":0}]""",
            json
        )
        assertEquals(entries, GlossaryIo.parseImportJson(json).entries)

        val withAliases = listOf(
            GlossaryTransferEntry(sourceTerm = "a", targetTerm = "b", aliases = listOf("x", "y"))
        )
        val compact = GlossaryIo.exportToJson(withAliases, pretty = false)
        assertFalse(compact.contains("\n"))
        assertEquals(withAliases, GlossaryIo.parseImportJson(compact).entries)
    }

    @Test
    fun jsonEscapesControlCharactersAndQuotes() {
        val entries = listOf(GlossaryTransferEntry(sourceTerm = "a\"b\\c", targetTerm = "x\ny\tz"))
        val parsed = GlossaryIo.parseImportJson(GlossaryIo.exportToJson(entries))
        assertEquals(entries, parsed.entries)
    }

    // ---------- 容错解析 ----------

    @Test
    fun parsesCodeFencedJson() {
        val text = "下面是术语表：\n```json\n[\n  {\"src\": \"アリス\", \"dst\": \"爱丽丝\", \"info\": \"女主\"}\n]\n```"
        val parsed = GlossaryIo.parseImportJson(text)
        assertTrue(parsed.errors.isEmpty())
        assertEquals(
            listOf(GlossaryTransferEntry(sourceTerm = "アリス", targetTerm = "爱丽丝", notes = "女主")),
            parsed.entries
        )
    }

    @Test
    fun parsesWrapperObjects() {
        for (text in listOf(
            """{"entries": [{"sourceTerm": "甲", "targetTerm": "A"}]}""",
            """{"glossary": [{"sourceTerm": "甲", "targetTerm": "A"}]}""",
            """{"meta": {"version": 1}, "terms": [{"sourceTerm": "甲", "targetTerm": "A"}]}"""
        )) {
            val parsed = GlossaryIo.parseImportJson(text)
            assertTrue("$text -> ${parsed.errors}", parsed.errors.isEmpty())
            assertEquals(listOf("甲"), parsed.entries.map { it.sourceTerm })
        }
    }

    @Test
    fun acceptsFieldAliases() {
        val text = """[
            {"source": "甲", "translation": "A"},
            {"term": "乙", "target": "B", "comment": "备注"}
        ]"""
        val parsed = GlossaryIo.parseImportJson(text)
        assertTrue(parsed.errors.isEmpty())
        assertEquals(listOf("甲", "乙"), parsed.entries.map { it.sourceTerm })
        assertEquals(listOf("A", "B"), parsed.entries.map { it.targetTerm })
        assertEquals("备注", parsed.entries[1].notes)
    }

    @Test
    fun badLineDoesNotKillOtherEntries() {
        val text = listOf(
            "[",
            "  {\"sourceTerm\": \"甲\", \"targetTerm\": \"A\"},",
            "  {\"targetTerm\": \"B\"},",
            "  {\"sourceTerm\": \"丙\", \"targetTerm\": \"C\"}",
            "]",
        ).joinToString("\n")
        val parsed = GlossaryIo.parseImportJson(text)
        assertEquals(listOf("甲", "丙"), parsed.entries.map { it.sourceTerm })
        assertEquals(1, parsed.errors.size)
        assertEquals(3, parsed.errors.single().line)
        assertTrue(parsed.errors.single().reason.contains("sourceTerm"))
        assertTrue(parsed.hasErrors)
    }

    @Test
    fun malformedJsonElementIsReportedAndSkipped() {
        val text = listOf(
            "[",
            "  {\"sourceTerm\": \"甲\", \"targetTerm\": \"A\"},",
            "  {\"sourceTerm\": \"乙\", \"targetTerm\": },",
            "  {\"sourceTerm\": \"丙\", \"targetTerm\": \"C\"}",
            "]",
        ).joinToString("\n")
        val parsed = GlossaryIo.parseImportJson(text)
        assertEquals(listOf("甲", "丙"), parsed.entries.map { it.sourceTerm })
        assertEquals(listOf(3), parsed.errors.map { it.line })
    }

    @Test
    fun nonObjectEntriesAreReportedWithTheirLine() {
        val text = listOf(
            "[",
            "  {\"sourceTerm\": \"甲\", \"targetTerm\": \"A\"},",
            "  \"坏行\",",
            "  42",
            "]",
        ).joinToString("\n")
        val parsed = GlossaryIo.parseImportJson(text)
        assertEquals(listOf("甲"), parsed.entries.map { it.sourceTerm })
        assertEquals(listOf(3, 4), parsed.errors.map { it.line })
    }

    @Test
    fun documentWithoutEntryArrayIsRejected() {
        val parsed = GlossaryIo.parseImportJson("""{"version": 1, "note": "没有条目"}""")
        assertTrue(parsed.entries.isEmpty())
        assertTrue(parsed.errors.any { it.reason.contains("未找到条目数组") })
    }

    @Test
    fun unparsableDocumentReportsRootError() {
        val parsed = GlossaryIo.parseImportJson("这不是 JSON")
        assertTrue(parsed.entries.isEmpty())
        assertEquals(1, parsed.errors.size)
    }

    @Test
    fun emptyOrFenceOnlyInputIsRejected() {
        assertTrue(GlossaryIo.parseImportJson("").errors.isNotEmpty())
        assertTrue(GlossaryIo.parseImportJson("```json\n```").errors.isNotEmpty())
    }

    @Test
    fun coercesLooseTypesAndNotifiesAboutFallbacks() {
        val text = """
            [
              {"src": "甲", "dst": "A", "case_sensitive": "true", "exact_match": "no", "priority": "abc"},
              {"src": "乙", "dst": "B", "kind": "MYSTERY", "category": "NOWHERE"}
            ]
        """.trimIndent()
        val parsed = GlossaryIo.parseImportJson(text)
        assertEquals(2, parsed.entries.size)
        val first = parsed.entries[0]
        assertTrue(first.caseSensitive)
        assertFalse(first.exactMatch)
        assertEquals(0, first.priority)
        val second = parsed.entries[1]
        assertEquals(LexiconKind.PROPER_NOUN.name, second.kind)
        assertEquals(TermCategory.CUSTOM.name, second.category)
        assertEquals(3, parsed.errors.size) // priority + kind + category 三条通知
    }

    @Test
    fun aliasesAcceptArraysAndDelimitedStrings() {
        val text = """[
            {"sourceTerm": "甲", "aliases": ["A", "B", "  "]},
            {"sourceTerm": "乙", "aliases": "C;D|E,F"}
        ]"""
        val parsed = GlossaryIo.parseImportJson(text)
        assertEquals(listOf("A", "B"), parsed.entries[0].aliases)
        assertEquals(listOf("C", "D", "E", "F"), parsed.entries[1].aliases)
        assertTrue(parsed.errors.isEmpty())
    }

    // ---------- 重复四分类 ----------

    @Test
    fun fourDuplicateKindsAreClassified() {
        val existing = listOf(
            GlossaryTransferEntry(sourceTerm = "同名", targetTerm = "旧译"),
            GlossaryTransferEntry(sourceTerm = "空译", targetTerm = ""),
            GlossaryTransferEntry(sourceTerm = "异译", targetTerm = "原译"),
        )
        val incoming = listOf(
            GlossaryTransferEntry(sourceTerm = "同名", targetTerm = "旧译"),
            GlossaryTransferEntry(sourceTerm = "空译", targetTerm = "新译"),
            GlossaryTransferEntry(sourceTerm = "异译", targetTerm = ""),
            GlossaryTransferEntry(sourceTerm = "异译", targetTerm = "不同译"),
            GlossaryTransferEntry(sourceTerm = "全新", targetTerm = "新"),
        )
        val preview = GlossaryIo.previewImport(existing, incoming)
        assertEquals(listOf("全新"), preview.toAdd.map { it.sourceTerm })
        assertEquals(1, preview.nonDuplicateCount)
        assertEquals(4, preview.duplicateCount)
        val byKey = preview.duplicates.associateBy { "${it.incoming.sourceTerm}/${it.incoming.targetTerm}" }
        assertEquals(GlossaryDuplicateKind.SAME_TARGET, byKey["同名/旧译"]!!.kind)
        assertEquals(GlossaryDuplicateKind.EXISTING_TARGET_EMPTY, byKey["空译/新译"]!!.kind)
        assertEquals(GlossaryDuplicateKind.INCOMING_TARGET_EMPTY, byKey["异译/"]!!.kind)
        assertEquals(GlossaryDuplicateKind.DIFFERENT_TARGET, byKey["异译/不同译"]!!.kind)

        assertEquals(listOf("新译"), preview.toOverwrite.map { it.targetTerm })
        assertEquals(listOf("旧译", "", "不同译"), preview.toSkip.map { it.targetTerm })
        assertEquals(1, preview.conflicts.size)
        assertEquals(GlossaryImportAction.OVERWRITE, byKey["空译/新译"]!!.recommendedAction)
        assertEquals(GlossaryImportAction.SKIP, byKey["同名/旧译"]!!.recommendedAction)
        assertEquals(GlossaryImportAction.SKIP, byKey["异译/不同译"]!!.recommendedAction)
        assertEquals(listOf(1), byKey["空译/新译"]!!.existingIndexes)
        assertEquals(1, byKey["空译/新译"]!!.incomingIndex)
    }

    @Test
    fun duplicatesMatchOnNormalizedSourceTerm() {
        val existing = listOf(GlossaryTransferEntry(sourceTerm = "ALICE", targetTerm = "爱丽丝"))
        val preview = GlossaryIo.previewImport(
            existing,
            listOf(GlossaryTransferEntry(sourceTerm = "ａｌｉｃｅ", targetTerm = "爱丽丝"))
        )
        assertEquals(0, preview.toAdd.size)
        assertEquals(1, preview.duplicateCount)
        assertEquals(GlossaryDuplicateKind.SAME_TARGET, preview.duplicates.single().kind)
    }

    @Test
    fun caseSensitiveEntriesOnlyCollideWithThemselves() {
        val preview = GlossaryIo.previewImport(
            listOf(GlossaryTransferEntry(sourceTerm = "ALICE", targetTerm = "爱丽丝", caseSensitive = true)),
            listOf(GlossaryTransferEntry(sourceTerm = "alice", targetTerm = "爱丽丝"))
        )
        assertEquals(1, preview.toAdd.size)
        assertTrue(preview.duplicates.isEmpty())
    }

    @Test
    fun laterIncomingEntryWinsForTheSameKey() {
        val preview = GlossaryIo.previewImport(
            emptyList(),
            listOf(
                GlossaryTransferEntry(sourceTerm = "甲", targetTerm = "第一译"),
                GlossaryTransferEntry(sourceTerm = "甲", targetTerm = "第二译"),
            )
        )
        assertEquals(listOf("第二译"), preview.toAdd.map { it.targetTerm })
        assertEquals(1, preview.duplicateCount)
        val duplicate = preview.duplicates.single()
        assertEquals("第一译", duplicate.incoming.targetTerm)
        assertEquals(GlossaryDuplicateKind.DIFFERENT_TARGET, duplicate.kind)
        assertTrue(duplicate.existingIndexes.isEmpty())
    }

    // ---------- CSV 导出 ----------

    @Test
    fun csvEscapesCommasQuotesAndNewlines() {
        val entries = listOf(
            GlossaryTransferEntry(
                sourceTerm = "含,逗号",
                targetTerm = "含\"引号",
                notes = "第一行\n第二行",
                aliases = listOf("a;b", "c")
            )
        )
        val csv = GlossaryIo.exportToCsv(entries, includeBom = false)
        val header = "sourceTerm,targetTerm,kind,category,aliases,notes,caseSensitive,exactMatch,priority"
        val row = "\"含,逗号\",\"含\"\"引号\",PROPER_NOUN,CUSTOM,a;b;c,\"第一行\n第二行\",false,true,0"
        assertEquals(listOf(header, row, ""), csv.split("\r\n")) // 行尾 CRLF 产生一个空片段
        assertTrue(GlossaryIo.exportToCsv(entries).startsWith("\uFEFF"))
    }

    // ---------- 实体映射 ----------

    @Test
    fun entityMappingRoundTripsAliasesAndNormalizesEnums() {
        val entity = LexiconEntryEntity(
            translationProjectId = 5,
            sourceTerm = "アリス",
            targetTerm = "爱丽丝",
            kind = "TERMINOLOGY",
            category = "CHARACTER",
            aliases = "Alice, アリス ; Alice2|Bob",
            notes = "备注",
            caseSensitive = true,
            exactMatch = false,
            priority = 3,
            source = "AI",
            reviewStatus = "CANDIDATE",
        )
        val transfer = entity.toTransfer()
        assertEquals(listOf("Alice", "アリス", "Alice2", "Bob"), transfer.aliases)
        assertTrue(transfer.caseSensitive)
        assertFalse(transfer.exactMatch)
        assertEquals(3, transfer.priority)
        assertEquals("TERMINOLOGY", transfer.kind)

        val restored = transfer.fromTransfer(translationProjectId = 9)
        assertEquals("アリス", restored.sourceTerm)
        assertEquals("Alice,アリス,Alice2,Bob", restored.aliases)
        assertEquals(9, restored.translationProjectId)
        assertEquals(LexiconSource.IMPORTED.name, restored.source)
        assertEquals(ReviewStatus.CONFIRMED.name, restored.reviewStatus)

        val sloppy = GlossaryTransferEntry(sourceTerm = "  x  ", kind = "proper_noun", category = "bogus")
            .fromTransfer(translationProjectId = 1)
        assertEquals("x", sloppy.sourceTerm)
        assertEquals(LexiconKind.PROPER_NOUN.name, sloppy.kind)
        assertEquals(TermCategory.CUSTOM.name, sloppy.category)
    }

    @Test
    fun shippedPresetsParseWithoutErrors() {
        val dir = presetDir() ?: error("未找到预设目录（工作目录：${File("").absolutePath}）")
        val files = dir.listFiles { file -> file.extension == "json" }?.sortedBy { it.name }.orEmpty()
        assertTrue("预设目录为空：${dir.absolutePath}", files.isNotEmpty())
        for (file in files) {
            val parsed = GlossaryIo.parseImportJson(file.readText())
            assertTrue("${file.name}: ${parsed.errors}", parsed.errors.isEmpty())
            assertTrue(file.name, parsed.entries.isNotEmpty())
            assertTrue(file.name, parsed.entries.all { it.sourceTerm.isNotBlank() })
            // 预设与导出格式互为往返
            assertEquals(
                file.name,
                parsed.entries,
                GlossaryIo.parseImportJson(GlossaryIo.exportToJson(parsed.entries)).entries
            )
        }
    }

    /** Gradle 单元测试的工作目录默认是模块目录；同时兼容从仓库根目录运行的情况。 */
    private fun presetDir(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            for (relative in listOf("src/main/assets/glossary/presets", "app/src/main/assets/glossary/presets")) {
                val candidate = File(dir, relative)
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }
}
