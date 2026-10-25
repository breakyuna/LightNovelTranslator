package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RenpyFormatAdapterTest {
    private val adapter = RenpyFormatAdapter()
    private val path = RelativePath("script.rpy")

    @Test
    fun extractsStringsAndLabelDialogueOnly() {
        val source = """
            translate schinese strings:
                old "START"
                new "开始"
                old "https://example.com/guide"
                new ""
            translate schinese python:
                ${'$'} hidden = "do not extract"
            translate schinese start:
                # game/script.rpy:10
                # "Alice" "Hello"
                "爱丽丝" "你好"
                # "Bob" "Hello"
                "Bob" "你好"
        """.trimIndent() + "\n"

        val document = adapter.read(source, path)

        assertEquals(3, document.units.size)
        assertEquals("renpy", document.gameEngine)
        val strings = document.units[0]
        assertEquals("START", strings.source)
        assertEquals("开始", strings.translation)
        assertEquals(TextItemStatus.PROCESSED, strings.status)
        assertEquals(2, strings.row)
        val stringsExtra = parse(strings.extraField)
        val stringsRenpy = stringsExtra["renpy"] as JsonObject
        assertEquals("STRINGS", (((stringsRenpy["block"] as JsonObject)["kind"]) as JsonPrimitive).content)
        assertEquals("2", (((stringsRenpy["pair"] as JsonObject)["template_line"]) as JsonPrimitive).content)
        assertEquals("3", (((stringsRenpy["pair"] as JsonObject)["target_line"]) as JsonPrimitive).content)

        val alice = document.units[1]
        assertEquals("Hello", alice.source)
        assertEquals("你好", alice.translation)
        assertEquals(listOf("Alice"), alice.nameSource)
        assertEquals(listOf("爱丽丝"), alice.nameTranslation)
        assertEquals(TextItemStatus.PROCESSED, alice.status)
        assertEquals(10, alice.row)
        val aliceRenpy = parse(alice.extraField)["renpy"] as JsonObject
        assertEquals("LABEL", (((aliceRenpy["block"] as JsonObject)["kind"]) as JsonPrimitive).content)
        val aliceSlots = aliceRenpy["slots"] as JsonArray
        assertEquals("NAME", (((aliceSlots[0] as JsonObject)["role"]) as JsonPrimitive).content)
        assertEquals("DIALOGUE", (((aliceSlots[1] as JsonObject)["role"]) as JsonPrimitive).content)

        val bob = document.units[2]
        assertEquals(listOf("Bob"), bob.nameSource)
        assertNull(bob.nameTranslation)
        assertEquals(TextItemStatus.PROCESSED, bob.status)
        assertTrue(document.warnings.isEmpty())
        assertEquals("3", (parse(document.metadata)["unit_count"] as JsonPrimitive).content)
    }

    @Test
    fun unchangedWriteIsByteIdenticalAndChangedWriteKeepsEscapesAndPython() {
        val source = "translate schinese start:\r\n" +
            "    # game/script.rpy:3\r\n" +
            "    # e \"Hello \\\"world\\\"\\nnext\"\r\n" +
            "    e \"你好 \\\"世界\\\"\\n下一行\"\r\n" +
            "translate schinese python:\r\n" +
            "    \$ block = \"python \\\"kept\\\"\"\r\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals("Hello \"world\"\nnext", unit.source)
        assertEquals("你好 \"世界\"\n下一行", unit.translation)

        assertEquals(source, adapter.write(source, document.units))

        val changed = copyUnit(
            unit,
            translation = "新 \"台词\"\n下一行",
            status = TextItemStatus.PROCESSED,
        )
        val output = adapter.write(source, listOf(changed))
        val oldTargetLine = "    e \"你好 \\\"世界\\\"\\n下一行\"\r\n"
        val newTargetLine = "    e \"新 \\\"台词\\\"\\n下一行\"\r\n"
        assertEquals(source.replace(oldTargetLine, newTargetLine), output)
        assertTrue(output.contains("    \$ block = \"python \\\"kept\\\"\"\r\n"))
        assertEquals("新 \"台词\"\n下一行", adapter.read(output, path).units.single().translation)
    }

    @Test
    fun extractsMultilineTripleStringsAndPreservesPythonBlocks() {
        val source = "translate schinese strings:\n" +
            "    old \"normal\"\n" +
            "    new \"\"\n" +
            "    old \"\"\"multi\n" +
            "line\"\"\"\n" +
            "    new \"\"\n" +
            "translate schinese python:\n" +
            "    \$ block = \"\"\"python\n" +
            "text\"\"\"\n"
        val document = adapter.read(source, path)
        assertEquals(listOf("normal", "multi\nline"), document.units.map { it.source })
        assertEquals(listOf(TextItemStatus.NONE, TextItemStatus.NONE),
            document.units.map { it.status })
        assertTrue(document.warnings.isEmpty())
        assertEquals("2", (parse(document.metadata)["multiline_strings"] as JsonPrimitive).content)
        assertEquals("0", (parse(document.metadata)["unsupported_statements"] as JsonPrimitive).content)

        val normal = document.units[0]
        val changed = copyUnit(normal, translation = "普通", status = TextItemStatus.PROCESSED)
        val output = adapter.write(source, listOf(changed))
        assertEquals(source.replaceFirst("    new \"\"\n", "    new \"普通\"\n"), output)
        assertTrue(output.contains("    old \"\"\"multi\nline\"\"\"\n"))
        assertTrue(output.contains("    \$ block = \"\"\"python\ntext\"\"\"\n"))

        val triple = document.units[1]
        val tripleOutput = adapter.write(source, listOf(
            copyUnit(triple, translation = "译文\n第二行", status = TextItemStatus.PROCESSED),
        ))
        assertTrue(tripleOutput.contains("    new \"译文\\n第二行\"\n"))
        assertTrue(tripleOutput.contains("    old \"\"\"multi\nline\"\"\"\n"))
        assertTrue(tripleOutput.contains("    \$ block = \"\"\"python\ntext\"\"\"\n"))
        assertEquals("译文\n第二行", adapter.read(tripleOutput, path).units[1].translation)

        rejects(InvalidTransRenpyFormat.Reason.UNTERMINATED_STRING) {
            adapter.read("translate schinese python:\n    \$ x = \"\"\"unterminated\n", path)
        }
        rejects(InvalidTransRenpyFormat.Reason.UNTERMINATED_STRING) {
            adapter.read("translate schinese start:\n    e \"unterminated\n", path)
        }
    }

    @Test
    fun tripleQuotedDialogueSpanningLinesIsRewrittenAsOneNormalLiteral() {
        val source = "translate schinese start:\n" +
            "    # e \"First line\\nsecond line\"\n" +
            "    e \"\"\"First line\n" +
            "second line\"\"\"\n" +
            "translate schinese python:\n" +
            "    \$ block = \"\"\"python\n" +
            "text\"\"\"\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals("First line\nsecond line", unit.source)
        assertEquals(TextItemStatus.NONE, unit.status)
        assertEquals(source, adapter.write(source, document.units))

        val changed = copyUnit(unit, translation = "译文\n第二行", status = TextItemStatus.PROCESSED)
        val expected = "translate schinese start:\n" +
            "    # e \"First line\\nsecond line\"\n" +
            "    e \"译文\\n第二行\"\n" +
            "translate schinese python:\n" +
            "    \$ block = \"\"\"python\n" +
            "text\"\"\"\n"
        assertEquals(expected, adapter.write(source, listOf(changed)))
        assertEquals("译文\n第二行", adapter.read(expected, path).units.single().translation)
    }

    @Test
    fun rawStringsRoundTripAndChangedOutputUsesNormalQuotes() {
        val source = "translate schinese start:\n" +
            "    # e r\"a  \\n\"\n" +
            "    e r\"a  \\n\"\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals("a  \\n", unit.source)
        assertEquals(TextItemStatus.NONE, unit.status)
        assertEquals(source, adapter.write(source, document.units))

        val changed = copyUnit(unit, translation = "新  值", status = TextItemStatus.PROCESSED)
        val output = adapter.write(source, listOf(changed))
        assertTrue(output.contains("新"))
        assertEquals("新  值", adapter.read(output, path).units.single().translation)
    }

    @Test
    fun characterNameAndTrailingArgumentsKeepTheirStructure() {
        val source = "translate schinese start:\n" +
            "    # Character(\"Man\") \"Hello\" with PushMove(\"x\")\n" +
            "    Character(\"Man\") \"你好\" with PushMove(\"y\")\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals(listOf("Man"), unit.nameSource)
        assertNull(unit.nameTranslation)

        val changed = copyUnit(unit, translation = "新", status = TextItemStatus.PROCESSED)
        val expected = "translate schinese start:\n" +
            "    # Character(\"Man\") \"Hello\" with PushMove(\"x\")\n" +
            "    Character(\"Man\") \"新\" with PushMove(\"x\")\n"
        assertEquals(expected, adapter.write(source, listOf(changed)))
    }

    @Test
    fun singleQuotedStringsArePreservedAndNeverBecomeUnits() {
        val source = "translate schinese start:\n" +
            "    # e 'single'\n" +
            "    e 'single'\n" +
            "translate schinese strings:\n" +
            "    old \"kept\"\n" +
            "    new \"\"\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals("kept", unit.source)

        val changed = copyUnit(unit, translation = "保留", status = TextItemStatus.PROCESSED)
        val output = adapter.write(source, listOf(changed))
        assertEquals(source.replaceFirst("    new \"\"\n", "    new \"保留\"\n"), output)
        assertTrue(output.contains("    # e 'single'\n    e 'single'\n"))
    }

    @Test
    fun singleQuotedTripleStringsUseTheSameExtractionAndEscapingRules() {
        val source = "translate schinese start:\n" +
            "    # e '''He said \"hi\" C:\\\\tmp\\nnext'''\n" +
            "    e '''他说 \"你好\" C:\\\\tmp\\n下一行'''\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals("He said \"hi\" C:\\tmp\nnext", unit.source)
        assertEquals("他说 \"你好\" C:\\tmp\n下一行", unit.translation)
        assertEquals(TextItemStatus.PROCESSED, unit.status)
        assertEquals(source, adapter.write(source, document.units))

        val changed = copyUnit(
            unit,
            translation = "新 \"台词\" C:\\tmp\n下一行",
            status = TextItemStatus.PROCESSED,
        )
        val expected = "translate schinese start:\n" +
            "    # e '''He said \"hi\" C:\\\\tmp\\nnext'''\n" +
            "    e \"新 \\\"台词\\\" C:\\\\tmp\\n下一行\"\n"
        assertEquals(expected, adapter.write(source, listOf(changed)))
        assertEquals("新 \"台词\" C:\\tmp\n下一行",
            adapter.read(expected, path).units.single().translation)
    }

    @Test
    fun doubleQuotedTripleStringsAlsoDecodeQuotesAndBackslashes() {
        val source = "translate schinese strings:\n" +
            "    old \"\"\"He said \"hi\" and it's C:\\\\tmp\nnext\"\"\"\n" +
            "    new \"\"\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals("He said \"hi\" and it's C:\\tmp\nnext", unit.source)
        assertEquals(TextItemStatus.NONE, unit.status)

        val changed = copyUnit(
            unit,
            translation = "他说 \"你好\" C:\\tmp\n下一行",
            status = TextItemStatus.PROCESSED,
        )
        val expected = source.replaceFirst("    new \"\"\n",
            "    new \"他说 \\\"你好\\\" C:\\\\tmp\\n下一行\"\n")
        assertEquals(expected, adapter.write(source, listOf(changed)))
        assertEquals("他说 \"你好\" C:\\tmp\n下一行",
            adapter.read(expected, path).units.single().translation)
    }

    @Test
    fun singleAndDoubleTripleStringsInOneBlockAreLocatedIndependently() {
        val source = "translate schinese strings:\n" +
            "    old '''single\none'''\n" +
            "    new \"\"\n" +
            "    old \"\"\"double\ntwo\"\"\"\n" +
            "    new \"\"\n" +
            "    old \"plain\"\n" +
            "    new \"\"\n"
        val document = adapter.read(source, path)
        assertEquals(listOf("single\none", "double\ntwo", "plain"),
            document.units.map { it.source })
        assertEquals(listOf(TextItemStatus.NONE, TextItemStatus.NONE, TextItemStatus.NONE),
            document.units.map { it.status })
        assertEquals("2", (parse(document.metadata)["multiline_strings"] as JsonPrimitive).content)
        assertEquals("0", (parse(document.metadata)["unsupported_statements"] as JsonPrimitive).content)
        assertTrue(document.warnings.isEmpty())

        val output = adapter.write(source, listOf(
            copyUnit(document.units[0], translation = "单引号", status = TextItemStatus.PROCESSED),
            copyUnit(document.units[2], translation = "普通", status = TextItemStatus.PROCESSED),
        ))
        val expected = "translate schinese strings:\n" +
            "    old '''single\none'''\n" +
            "    new \"单引号\"\n" +
            "    old \"\"\"double\ntwo\"\"\"\n" +
            "    new \"\"\n" +
            "    old \"plain\"\n" +
            "    new \"普通\"\n"
        assertEquals(expected, output)
        assertEquals(listOf("单引号", "", "普通"), adapter.read(output, path).units.map { it.translation })
    }

    @Test
    fun singleTripleDialogueWithTrailingTripleArgumentKeepsTheOtherLiteral() {
        val source = "translate schinese start:\n" +
            "    # Character(\"Man\") '''Hello''' with PushMove(\"\"\"x\"\"\")\n" +
            "    Character(\"Man\") '''你好''' with PushMove(\"\"\"x\"\"\")\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals("Hello", unit.source)
        assertEquals("你好", unit.translation)
        assertEquals(listOf("Man"), unit.nameSource)

        val changed = copyUnit(unit, translation = "新台词", status = TextItemStatus.PROCESSED)
        val expected = "translate schinese start:\n" +
            "    # Character(\"Man\") '''Hello''' with PushMove(\"\"\"x\"\"\")\n" +
            "    Character(\"Man\") \"新台词\" with PushMove(\"\"\"x\"\"\")\n"
        assertEquals(expected, adapter.write(source, listOf(changed)))
    }

    @Test
    fun unclosedSingleTripleStringsFailClosed() {
        rejects(InvalidTransRenpyFormat.Reason.UNTERMINATED_STRING) {
            adapter.read("translate schinese start:\n    e '''unterminated\n", path)
        }
        rejects(InvalidTransRenpyFormat.Reason.UNTERMINATED_STRING) {
            adapter.read("translate schinese python:\n    \$ x = '''unterminated\n", path)
        }
    }

    @Test
    fun missingStaleAndDuplicateLocatorsFailClosedButLegacyMetadataIsRebuilt() {
        val source = "translate schinese start:\n    # e \"Hello\"\n    e \"你好\"\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()

        rejects(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, row = 999, extraField = "{}")))
        }
        rejects(InvalidTransRenpyFormat.Reason.STALE_LOCATOR) {
            adapter.write(
                source,
                listOf(
                    copyUnit(
                        unit,
                        extraField = unit.extraField.replace(
                            "\"template_raw_sha256\":\"",
                            "\"template_raw_sha256\":\"bad",
                        ),
                    ),
                ),
            )
        }
        rejects(InvalidTransRenpyFormat.Reason.STALE_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, source = "Other")))
        }
        rejects(InvalidTransRenpyFormat.Reason.DUPLICATE_LOCATOR) {
            val changed = copyUnit(unit, translation = "甲", status = TextItemStatus.PROCESSED)
            adapter.write(source, listOf(changed, changed))
        }

        val compat = copyUnit(unit, translation = "兼容", status = TextItemStatus.PROCESSED, extraField = "{}")
        assertEquals(
            source.replace("e \"你好\"", "e \"兼容\""),
            adapter.write(source, listOf(compat)),
        )
        assertEquals(source, adapter.write(source, listOf(copyUnit(unit, translation = "ignored", status = TextItemStatus.NONE))))
        assertEquals(
            source.replace("e \"你好\"", "e \"\""),
            adapter.write(source, listOf(copyUnit(unit, translation = "", status = TextItemStatus.PROCESSED))),
        )
    }

    @Test
    fun nameSlotsFollowTheWriteTranslatedNamesOption() {
        val source = "translate schinese start:\n    # \"Alice\" \"Hello\"\n    \"爱丽丝\" \"你好\"\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals(listOf("Alice"), unit.nameSource)
        assertEquals(listOf("爱丽丝"), unit.nameTranslation)

        assertEquals(
            "translate schinese start:\n    # \"Alice\" \"Hello\"\n    \"Alice\" \"你好\"\n",
            adapter.write(source, document.units),
        )
        assertEquals(
            source,
            adapter.write(source, document.units, FormatWriteOptions(writeTranslatedNames = true)),
        )
    }

    @Test
    fun resourceReferencesAndPlaceholderOnlyTextAreNotUnits() {
        val source = "translate schinese strings:\n" +
            "    old \"bg/scene.PNG\"\n" +
            "    new \"\"\n" +
            "    old \"[player_name]\"\n" +
            "    new \"\"\n" +
            "    old \"{b}{/b}\"\n" +
            "    new \"\"\n" +
            "    old \"{image=gui/icon.png}\"\n" +
            "    new \"\"\n" +
            "    old \"请看 https://example.com/guide\"\n" +
            "    new \"\"\n"
        val units = adapter.read(source, path).units
        assertEquals(
            listOf("{image=gui/icon.png}", "请看 https://example.com/guide"),
            units.map { it.source },
        )
    }

    @Test
    fun rejectsInvalidUnicodeUnterminatedStringsAndMalformedHeaders() {
        rejects(InvalidTransRenpyFormat.Reason.INVALID_TEXT) { adapter.read("\uD800", path) }
        rejects(InvalidTransRenpyFormat.Reason.INVALID_TEXT) { adapter.read("\u0000", path) }
        rejects(InvalidTransRenpyFormat.Reason.INVALID_TEXT) {
            adapter.read(
                "translate schinese start:\n    # e \"x\"\n    e \"\\uD800\"\n",
                path,
            )
        }
        rejects(InvalidTransRenpyFormat.Reason.UNTERMINATED_STRING) {
            adapter.read("translate schinese start:\n    e \"unterminated\n", path)
        }

        val malformedHeader = "translate schinese start\n    e \"Hello\"\n"
        val document = adapter.read(malformedHeader, path)
        assertTrue(document.units.isEmpty())
        assertEquals(malformedHeader, adapter.write(malformedHeader, emptyList()))
    }

    private fun copyUnit(
        unit: FormatUnit,
        row: Int = unit.row,
        source: String = unit.source,
        translation: String = unit.translation,
        status: TextItemStatus = unit.status,
        nameSource: List<String>? = unit.nameSource,
        nameTranslation: List<String>? = unit.nameTranslation,
        extraField: String = unit.extraField,
    ): FormatUnit = FormatUnit(
        row = row,
        source = source,
        translation = translation,
        status = status,
        tag = unit.tag,
        nameSource = nameSource,
        nameTranslation = nameTranslation,
        extraField = extraField,
        skipInternalFilter = unit.skipInternalFilter,
    )

    private fun parse(text: String): JsonObject =
        JsonObjectValue.parse(text, JsonInputLimits(8 * 1024 * 1024, 32)).json

    private fun rejects(
        expected: InvalidTransRenpyFormat.Reason,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected $expected")
        } catch (failure: InvalidTransRenpyFormat) {
            assertEquals(expected, failure.reason)
        }
    }
}

/** Test convenience: production callers always pass a frozen output policy. */
private fun RenpyFormatAdapter.write(original: String, units: List<FormatUnit>): String =
    write(original, units, FormatWriteOptions())
