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

    @Test fun readRejectsNulAndMalformedUnicode() {
        rejects(InvalidMarkdownV2.Reason.TEXT) { adapter.read("a\u0000b", path) }
        rejects(InvalidMarkdownV2.Reason.TEXT) { adapter.read("\uD800", path) }
    }
}
