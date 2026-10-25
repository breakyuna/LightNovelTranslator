package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MarkdownV2AdapterTest {
    private val adapter = MarkdownV2Adapter()
    private val path = RelativePath("book/chapter.md")

    private fun translated(unit: FormatUnit, text: String) = FormatUnit(
        row = unit.row,
        source = unit.source,
        translation = text,
        status = TextItemStatus.PROCESSED,
        tag = unit.tag,
        nameSource = unit.nameSource,
        nameTranslation = unit.nameTranslation,
        extraField = unit.extraField,
        skipInternalFilter = unit.skipInternalFilter,
    )

    private fun extraField(unit: FormatUnit, name: String): String? {
        val root = JsonObjectValue.parse(unit.extraField, JsonInputLimits(65_536, 8)).json
        val layout = root["markdown"] as? JsonObject ?: root
        return (layout[name] as? JsonPrimitive)?.content
    }

    private fun rejects(reason: InvalidMarkdownV2.Reason, block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid Markdown")
        } catch (failure: InvalidMarkdownV2) {
            assertEquals(reason, failure.reason)
        }
    }

    private val sample = "# Title\n\nIntro line\ncontinued\n\n```md\ncode\n```\n\n- item one\n- item two\n"

    @Test fun readKeepsBlockLayoutAndSkipsCodeFences() {
        val document = adapter.read(sample, path)
        assertEquals(5, document.units.size)
        assertEquals(listOf(1, 3, 6, 10, 11), document.units.map { it.row })
        assertEquals(listOf("# Title", "Intro line\ncontinued", "```md\ncode\n```", "item one", "item two"),
            document.units.map { it.source })
        assertEquals(listOf(TextItemStatus.NONE, TextItemStatus.NONE, TextItemStatus.RULE_SKIPPED,
            TextItemStatus.NONE, TextItemStatus.NONE), document.units.map { it.status })
        assertEquals("", extraField(document.units[0], "before"))
        assertEquals("\n\n", extraField(document.units[1], "before"))
        assertEquals("\n\n", extraField(document.units[2], "before"))
        assertEquals("\n\n- ", extraField(document.units[3], "before"))
        assertEquals("\n- ", extraField(document.units[4], "before"))
        assertEquals("", extraField(document.units[0], "after"))
        assertEquals("\n", extraField(document.units[4], "after"))
        assertEquals(sample, adapter.write(sample, document.units, FormatWriteOptions()))
    }

    @Test fun writeAppliesOnlyProcessedBlocksAndKeepsEveryControlByte() {
        val document = adapter.read(sample, path)
        val units = document.units.map { unit ->
            when (unit.source) {
                "# Title" -> translated(unit, "# 标题")
                "item one" -> translated(unit, "条目一")
                // A rule-skipped code block must never be overwritten even with a translation.
                "```md\ncode\n```" -> translated(unit, "```md\ntranslated\n```")
                else -> unit
            }
        }
        val expected = sample.replace("# Title", "# 标题").replace("item one", "条目一")
        assertEquals(expected, adapter.write(sample, units, FormatWriteOptions()))
        assertEquals(expected, adapter.write(sample, units, FormatWriteOptions(bilingual = true)))
    }

    @Test fun crlfFrontMatterAndTrailingWhitespaceRoundTripExact() {
        val source = "---\r\ntitle: demo\r\n---\r\n\r\n# Heading\r\n\r\nparagraph\r\n"
        val document = adapter.read(source, path)
        assertEquals(TextItemStatus.RULE_SKIPPED, document.units.first().status)
        assertTrue(document.units.first().source.startsWith("---\r\ntitle: demo\r\n---"))
        assertEquals(source, adapter.write(source, document.units, FormatWriteOptions()))
    }

    @Test fun emptyAndWhitespaceOnlyDocumentsKeepTheirBytes() {
        val empty = adapter.read("", path)
        assertEquals(1, empty.units.size)
        assertEquals("", empty.units.single().source)
        assertEquals(TextItemStatus.RULE_SKIPPED, empty.units.single().status)
        assertEquals("", adapter.write("", empty.units, FormatWriteOptions()))

        val whitespace = "\n\n   \r\n"
        val document = adapter.read(whitespace, path)
        assertEquals(whitespace, adapter.write(whitespace, document.units, FormatWriteOptions()))
    }

    @Test fun bindingsUseRowAndSourceNotListPosition() {
        val document = adapter.read(sample, path)
        // Reverse the order: stable rows must still select the same blocks.
        val reversed = document.units.reversed().map { unit ->
            if (unit.source == "Intro line\ncontinued") translated(unit, "译文") else unit
        }
        assertEquals(sample.replace("Intro line\ncontinued", "译文"),
            adapter.write(sample, reversed, FormatWriteOptions()))
    }

    @Test fun writeRejectsDuplicateStaleOrForeignUnitsAndKeepsMissingBlocks() {
        val document = adapter.read(sample, path)
        assertEquals(sample, adapter.write(sample, emptyList(), FormatWriteOptions()))
        // Blocks without a unit keep their frozen source instead of being dropped.
        assertEquals(sample, adapter.write(sample, document.units.dropLast(1), FormatWriteOptions()))
        rejects(InvalidMarkdownV2.Reason.BINDING) {
            adapter.write(sample, document.units + document.units.first(), FormatWriteOptions())
        }
        val stale = FormatUnit(
            row = document.units[0].row,
            source = "different",
            extraField = document.units[0].extraField,
        )
        rejects(InvalidMarkdownV2.Reason.STALE) {
            adapter.write(sample, listOf(stale) + document.units.drop(1), FormatWriteOptions())
        }
        val foreignLayout = FormatUnit(
            row = document.units[0].row,
            source = document.units[0].source,
            extraField = "{\"before\":\"wrong\",\"after\":\"\"}",
        )
        rejects(InvalidMarkdownV2.Reason.STALE) {
            adapter.write(sample, listOf(foreignLayout) + document.units.drop(1), FormatWriteOptions())
        }
    }

    @Test fun emojiParagraphsStayTranslatableWhileInlineCodeOnlyBlocksAreSkipped() {
        val source = "😀\n\n`code`\n"
        val document = adapter.read(source, path)
        assertEquals(listOf("😀", "`code`"), document.units.map { it.source })
        assertEquals(listOf(TextItemStatus.NONE, TextItemStatus.RULE_SKIPPED),
            document.units.map { it.status })
        assertEquals(source, adapter.write(source, document.units, FormatWriteOptions()))
    }

    @Test fun inlineCodeLinksImagesHtmlAndAutolinksAreMaskedThenRestoredExactly() {
        val source = "Use `println(\"x\")` with [docs](https://example.com/a) and " +
            "![cover](img/a.png), then <span class=\"k\">high</span> " +
            "<https://auto.example>.\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        val masked = unit.source

        assertEquals(TextItemStatus.NONE, unit.status)
        assertTrue(masked.contains("⟦LNT_MD_"))
        assertTrue(masked.contains("docs"))
        assertTrue(masked.contains("cover"))
        assertTrue(masked.contains("high"))
        for (raw in listOf("println", "https://example.com/a", "img/a.png",
            "<span", "</span>", "<https://auto.example>")) {
            assertTrue("Masked source leaked $raw", raw !in masked)
        }

        val translation = masked
            .replace("Use", "使用")
            .replace("with", "搭配")
            .replace("docs", "文档")
            .replace("cover", "封面")
            .replace("then", "然后")
            .replace("high", "高亮")
        val expected = source
            .replace("Use", "使用")
            .replace("with", "搭配")
            .replace("docs", "文档")
            .replace("cover", "封面")
            .replace("then", "然后")
            .replace("high", "高亮")
        assertEquals(expected, adapter.write(source, listOf(translated(unit, translation)),
            FormatWriteOptions()))
    }

    @Test fun emphasisDelimiterRunsAreProtectedWhileInnerTextIsTranslatable() {
        val source = "This is **bold**, *italic*, __strong__, _em_, and ~~gone~~.\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals(TextItemStatus.NONE, unit.status)
        assertTrue(unit.source.contains("bold"))
        assertTrue(unit.source.contains("italic"))
        assertTrue(unit.source.contains("strong"))
        assertTrue(unit.source.contains("gone"))
        assertTrue("**" !in unit.source)
        assertTrue("*italic*" !in unit.source)
        assertTrue("~~" !in unit.source)

        val translation = unit.source
            .replace("bold", "粗体")
            .replace("italic", "斜体")
            .replace("strong", "强壮")
            .replace("em", "强调")
            .replace("gone", "删除")
        val expected = "This is **粗体**, *斜体*, __强壮__, _强调_, and ~~删除~~.\n"
        assertEquals(expected, adapter.write(source, listOf(translated(unit, translation)),
            FormatWriteOptions()))
    }

    @Test fun cjkAdjacentUnderscoreEmphasisIsProtected() {
        val source = "这是_重点_内容。\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals(TextItemStatus.NONE, unit.status)
        assertTrue("_重点_" !in unit.source)
        assertTrue(unit.source.contains("重点"))
        val translation = unit.source.replace("重点", "关键")
        assertEquals("这是_关键_内容。\n",
            adapter.write(source, listOf(translated(unit, translation)), FormatWriteOptions()))
    }

    @Test fun nestedListItemsKeepTheirMarkersAndTranslateIndependently() {
        val source = "- outer\n  - inner\n- last\n"
        val document = adapter.read(source, path)
        assertEquals(listOf(1, 2, 3), document.units.map { it.row })
        assertEquals(listOf("outer", "inner", "last"), document.units.map { it.source })
        val units = document.units.map { unit ->
            when (unit.source) {
                "outer" -> translated(unit, "外层")
                "inner" -> translated(unit, "内层")
                else -> translated(unit, "最后")
            }
        }
        assertEquals("- 外层\n  - 内层\n- 最后\n",
            adapter.write(source, units, FormatWriteOptions()))
    }

    @Test fun unclosedInlineConstructsKeepTheBlockVerbatim() {
        val sources = listOf(
            "A `unclosed code\n",
            "A [link](https://example.com\n",
            "A [link][ref\n",
            "A ![alt](img/a.png\n",
            "A ![alt\n",
            "A <span class=\"x\"\n",
            "A <https://example.com\n",
            "A <mailto:reader@example.com\n",
        )
        for (source in sources) {
            val document = adapter.read(source, path)
            val unit = document.units.single()
            assertEquals(source.removeSuffix("\n"), unit.source)
            assertEquals(TextItemStatus.RULE_SKIPPED, unit.status)
            assertEquals(source, adapter.write(source, listOf(translated(unit, "不应写入")),
                FormatWriteOptions()))
        }
    }

    @Test fun unmatchedEmphasisMarkerIsRestoredInsteadOfDropped() {
        val source = "**unclosed and text\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals(TextItemStatus.NONE, unit.status)
        assertTrue("**" !in unit.source)
        assertTrue(unit.source.contains("unclosed"))
        val translation = unit.source.replace("unclosed", "未闭合").replace("text", "文本")
        assertEquals("**未闭合 and 文本\n",
            adapter.write(source, listOf(translated(unit, translation)), FormatWriteOptions()))
    }

    @Test fun missingOrDuplicatedPlaceholdersFailClosed() {
        val source = "A [link](https://example.com) and `code`.\n"
        val unit = adapter.read(source, path).units.single()
        rejects(InvalidMarkdownV2.Reason.TEXT) {
            adapter.write(source, listOf(translated(unit, "A link and code.")),
                FormatWriteOptions())
        }
        rejects(InvalidMarkdownV2.Reason.TEXT) {
            adapter.write(source, listOf(translated(unit, unit.source + unit.source)),
                FormatWriteOptions())
        }
    }

    @Test fun readRejectsNulAndMalformedUnicode() {
        rejects(InvalidMarkdownV2.Reason.TEXT) { adapter.read("a\u0000b", path) }
        rejects(InvalidMarkdownV2.Reason.TEXT) { adapter.read("\uD800", path) }
    }
}
