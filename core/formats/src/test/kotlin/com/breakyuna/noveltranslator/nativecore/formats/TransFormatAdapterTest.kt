package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransFormatAdapterTest {
    private val adapter = TransFormatAdapter()
    private val path = RelativePath("demo.trans")

    @Test
    fun readsIndexedRowsAndKeepsEngineContext() {
        val source = """
            {
              "project": {
                "indexOriginal": 1,
                "indexTranslation": 2,
                "gameEngine": "kag",
                "files": {
                  "file.json": {
                    "data": [["id-1", "原文", "译文"]],
                    "tags": [["keep"]],
                    "context": [["ctx"]],
                    "parameters": [[{"note": "meta"}]],
                    "unknown": {"n": 1.50, "b": true, "z": null}
                  },
                  "other.json": {"data": [["id-2", "Hello", ""]]}
                },
                "unknownRoot": [1, true, "x"]
              },
              "top": 3
            }
        """.trimIndent()

        val document = adapter.read(source, path)

        assertEquals(2, document.units.size)
        assertEquals("kag", document.gameEngine)
        val first = document.units[0]
        assertEquals("原文", first.source)
        assertEquals("译文", first.translation)
        assertEquals(TextItemStatus.PROCESSED, first.status)
        assertEquals("file.json", first.tag)
        val firstExtra = parse(first.extraField)
        assertEquals(listOf("keep"), strings(firstExtra["tag"]))
        assertEquals(listOf("ctx"), strings(firstExtra["context"]))
        assertEquals("file.json", (((firstExtra["trans_ref"] as JsonObject)["file_key"]) as JsonPrimitive).content)
        assertEquals("0", (((firstExtra["trans_ref"] as JsonObject)["row_index"]) as JsonPrimitive).content)
        assertEquals("kag", (firstExtra["engine"] as JsonPrimitive).content)
        assertFalse(first.skipInternalFilter)

        val second = document.units[1]
        assertEquals("Hello", second.source)
        assertEquals("", second.translation)
        assertEquals(TextItemStatus.NONE, second.status)
        assertEquals("other.json", second.tag)

        val metadata = parse(document.metadata)
        assertEquals("1", (metadata["index_original"] as JsonPrimitive).content)
        assertEquals("2", (metadata["index_translation"] as JsonPrimitive).content)
        assertEquals("2", (metadata["file_count"] as JsonPrimitive).content)
    }

    @Test
    fun writesOnlyTargetedRowsAndPreservesUnknownFieldsAndTypes() {
        val source = """
            {
              "project": {
                "indexOriginal": 1,
                "indexTranslation": 2,
                "gameEngine": "kag",
                "files": {
                  "file.json": {
                    "data": [["id-1", "原文", "译文"]],
                    "tags": [["keep"]],
                    "context": [["ctx"]],
                    "parameters": [[{"note": "meta"}]],
                    "unknown": {"n": 1.50, "b": true, "z": null}
                  },
                  "other.json": {"data": [["id-2", "Hello", ""]]}
                },
                "unknownRoot": [1, true, "x"]
              },
              "top": 3
            }
        """.trimIndent()
        val document = adapter.read(source, path)
        val changed = copyUnit(
            document.units[1],
            translation = "你好",
            status = TextItemStatus.PROCESSED,
        )

        val output = adapter.write(source, listOf(document.units[0], changed))
        val root = parse(output)
        val project = root["project"] as JsonObject
        val files = project["files"] as JsonObject
        val firstEntry = files["file.json"] as JsonObject
        val firstRow = (firstEntry["data"] as JsonArray)[0] as JsonArray
        assertEquals("id-1", (firstRow[0] as JsonPrimitive).content)
        assertEquals("原文", (firstRow[1] as JsonPrimitive).content)
        assertEquals("译文", (firstRow[2] as JsonPrimitive).content)
        val secondRow = ((files["other.json"] as JsonObject)["data"] as JsonArray)[0] as JsonArray
        assertEquals("id-2", (secondRow[0] as JsonPrimitive).content)
        assertEquals("Hello", (secondRow[1] as JsonPrimitive).content)
        assertEquals("你好", (secondRow[2] as JsonPrimitive).content)

        val unknown = firstEntry["unknown"] as JsonObject
        val number = unknown["n"] as JsonPrimitive
        assertFalse(number.isString)
        assertEquals("1.50", number.content)
        val boolean = unknown["b"] as JsonPrimitive
        assertFalse(boolean.isString)
        assertEquals("true", boolean.content)
        assertTrue(unknown["z"] is JsonNull)
        assertEquals(listOf("keep"), strings((firstEntry["tags"] as JsonArray)[0]))
        assertEquals(listOf("ctx"), strings((firstEntry["context"] as JsonArray)[0]))
        val parameter = ((firstEntry["parameters"] as JsonArray)[0] as JsonArray)[0] as JsonObject
        assertEquals("meta", (parameter["note"] as JsonPrimitive).content)
        val unknownRoot = project["unknownRoot"] as JsonArray
        assertEquals("1", (unknownRoot[0] as JsonPrimitive).content)
        assertEquals("true", (unknownRoot[1] as JsonPrimitive).content)
        assertEquals("x", (unknownRoot[2] as JsonPrimitive).content)
        assertEquals("3", (root["top"] as JsonPrimitive).content)

        val reread = adapter.read(output, path)
        assertEquals(2, reread.units.size)
        assertEquals("你好", reread.units[1].translation)
    }

    @Test
    fun unchangedAndNonProcessedUnitsReturnTheOriginalText() {
        val source = """{"project":{"files":{"a.json":{"data":[["source","译文"]],"tags":[[]],"context":[[]],"parameters":[[]]}}}}"""
        val document = adapter.read(source, path)

        assertEquals(source, adapter.write(source, document.units))
        val untouched = copyUnit(
            document.units.single(),
            translation = "not committed",
            status = TextItemStatus.NONE,
        )
        assertEquals(source, adapter.write(source, listOf(untouched)))
        val cleared = copyUnit(
            document.units.single(),
            translation = "",
            status = TextItemStatus.PROCESSED,
        )
        val clearedRoot = parse(adapter.write(source, listOf(cleared)))
        val clearedProject = clearedRoot["project"] as JsonObject
        val clearedFiles = clearedProject["files"] as JsonObject
        val clearedEntry = clearedFiles["a.json"] as JsonObject
        val clearedRow = (clearedEntry["data"] as JsonArray)[0] as JsonArray
        assertEquals("", (clearedRow[1] as JsonPrimitive).content)
    }

    @Test
    fun missingOrStaleLocatorsAreRejectedBeforeWriting() {
        val source = """{"project":{"files":{"a.json":{"data":[["source","译文"]],"tags":[[]],"context":[[]],"parameters":[[]]}}}}"""
        val unit = adapter.read(source, path).units.single()

        rejects(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, extraField = "{}")))
        }
        rejects(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, extraField = refExtra("a.json", 99))))
        }
        rejects(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, extraField = refExtra("missing.json", 0))))
        }
        rejects(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, tag = "missing.json")))
        }
        rejects(InvalidTransRenpyFormat.Reason.MISSING_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, extraField = """{"trans_ref":{"file_key":"a.json","row_index":"0"}}""")))
        }
        rejects(InvalidTransRenpyFormat.Reason.STALE_LOCATOR) {
            adapter.write(source, listOf(copyUnit(unit, source = "changed", status = TextItemStatus.PROCESSED)))
        }
        rejects(InvalidTransRenpyFormat.Reason.DUPLICATE_LOCATOR) {
            adapter.write(source, listOf(unit, unit))
        }
        assertTrue(unit.extraField.isNotEmpty())
    }

    @Test
    fun acceptsAndPreservesALeadingBom() {
        val source = "\uFEFF" +
            """{"project":{"files":{"a.json":{"data":[["source",""]]}}}}"""
        val document = adapter.read(source, path)
        assertEquals(1, document.units.size)
        val changed = copyUnit(
            document.units.single(),
            translation = "译文",
            status = TextItemStatus.PROCESSED,
        )
        val output = adapter.write(source, listOf(changed))
        assertTrue(output.startsWith('\uFEFF'))
        assertEquals("译文", adapter.read(output, path).units.single().translation)
    }

    @Test
    fun rejectsMalformedContainers() {
        rejects(InvalidTransRenpyFormat.Reason.MALFORMED) { adapter.read("[]", path) }
        rejects(InvalidTransRenpyFormat.Reason.MALFORMED) { adapter.read("{", path) }
        rejects(InvalidTransRenpyFormat.Reason.MALFORMED) { adapter.read("""{"project":[]}""", path) }
        rejects(InvalidTransRenpyFormat.Reason.MALFORMED) {
            adapter.read("""{"project":{"files":[]}}""", path)
        }
        assertEquals(0, adapter.read("""{"project":{"files":{}}}""", path).units.size)
    }

    @Test
    fun portsRpgMakerFiltersAndGoldTags() {
        val source = """
            {
              "project": {
                "gameEngine": "rmmz",
                "files": {
                  "plugin.js": {"data": [["script", ""]], "tags": [[]], "context": [[]]},
                  "data/CommonEvents.json": {
                    "data": [["command", ""], ["text", ""], ["name", ""]],
                    "tags": [[], [], []],
                    "context": [
                      ["CommonEvents/1/list/0/MZ Plugin Command/command"],
                      ["CommonEvents/1/list/0/MZ Plugin Command/text"],
                      ["MapInfos/1/name"]
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val units = adapter.read(source, path).units

        assertEquals(4, units.size)
        assertEquals(TextItemStatus.RULE_SKIPPED, units[0].status)
        assertEquals(listOf("gold"), extraTags(units[0]))
        assertEquals(TextItemStatus.RULE_SKIPPED, units[1].status)
        assertEquals(listOf("gold"), extraTags(units[1]))
        assertEquals(TextItemStatus.NONE, units[2].status)
        assertEquals(emptyList<String>(), extraTags(units[2]))
        assertEquals(TextItemStatus.RULE_SKIPPED, units[3].status)
        assertEquals(listOf("gold"), extraTags(units[3]))
    }

    @Test
    fun portsWolfBlockedTextAndAddressRules() {
        val source = """
            {
              "project": {
                "gameEngine": "wolf",
                "files": {
                  "a.json": {
                    "data": [["block_me"], ["keep"]],
                    "context": [
                      ["common/110.json/commands/29/Database/stringArgs/1"],
                      ["common/110.json/commands/29/Database/stringArgs/0"]
                    ]
                  },
                  "b.json": {
                    "data": [["block_me"], ["keep"]],
                    "context": [
                      ["DataBase.json/types/1/data/2/data/3/value"],
                      ["DataBase.json/types/1/data/2/data/3/value"]
                    ]
                  }
                }
              }
            }
        """.trimIndent()

        val units = adapter.read(source, path).units

        assertEquals(4, units.size)
        assertEquals(TextItemStatus.RULE_SKIPPED, units[0].status)
        assertEquals(listOf("gold"), extraTags(units[0]))
        assertEquals(TextItemStatus.NONE, units[1].status)
        assertEquals(emptyList<String>(), extraTags(units[1]))
        assertEquals(TextItemStatus.RULE_SKIPPED, units[2].status)
        assertEquals(listOf("gold"), extraTags(units[2]))
        assertEquals(TextItemStatus.NONE, units[3].status)
        assertEquals(emptyList<String>(), extraTags(units[3]))
    }

    @Test
    fun writesGoldTagsAndMixedPartitionParameters() {
        val source = """
            {
              "project": {
                "gameEngine": "wolf",
                "files": {
                  "common/1.json": {
                    "data": [["混合", ""]],
                    "tags": [[]],
                    "context": [["common/1.json/Message/stringArgs/0", "common/1.json/name"]],
                    "parameters": [[]]
                  }
                }
              }
            }
        """.trimIndent()
        val document = adapter.read(source, path)
        val unit = document.units.single()
        assertEquals(TextItemStatus.NONE, unit.status)
        assertEquals(listOf("gold"), extraTags(unit))
        val translated = copyUnit(unit, translation = "译文", status = TextItemStatus.PROCESSED)

        val root = parse(adapter.write(source, listOf(translated)))
        val entry = ((root["project"] as JsonObject)["files"] as JsonObject)["common/1.json"] as JsonObject
        val row = (entry["data"] as JsonArray)[0] as JsonArray
        assertEquals("混合", (row[0] as JsonPrimitive).content)
        assertEquals("译文", (row[1] as JsonPrimitive).content)
        assertEquals(listOf("gold"), strings((entry["tags"] as JsonArray)[0]))
        val parameters = (entry["parameters"] as JsonArray)[0] as JsonArray
        assertEquals("common/1.json/Message/stringArgs/0",
            ((parameters[0] as JsonObject)["contextStr"] as JsonPrimitive).content)
        assertEquals("", ((parameters[0] as JsonObject)["translation"] as JsonPrimitive).content)
        assertEquals("common/1.json/name",
            ((parameters[1] as JsonObject)["contextStr"] as JsonPrimitive).content)
        assertEquals("混合", ((parameters[1] as JsonObject)["translation"] as JsonPrimitive).content)
    }

    @Test
    fun keepsAquaSkipSemanticsAndRejectsBilingualOutput() {
        val source = """{"project":{"files":{"a.json":{"data":[["source",""]],"tags":[["aqua"]],"context":[["x"]]}}}}"""
        val document = adapter.read(source, path)
        val unit = document.units.single()

        assertTrue(unit.skipInternalFilter)
        assertEquals(TextItemStatus.NONE, unit.status)
        assertEquals(listOf("aqua"), extraTags(unit))
        assertEquals(source, adapter.write(source, document.units))
        rejects(InvalidTransRenpyFormat.Reason.UNSUPPORTED_OPTION) {
            adapter.write(source, emptyList(), FormatWriteOptions(bilingual = true))
        }
    }

    private fun copyUnit(
        unit: FormatUnit,
        source: String = unit.source,
        translation: String = unit.translation,
        status: TextItemStatus = unit.status,
        tag: String = unit.tag,
        extraField: String = unit.extraField,
    ): FormatUnit = FormatUnit(
        row = unit.row,
        source = source,
        translation = translation,
        status = status,
        tag = tag,
        extraField = extraField,
        skipInternalFilter = unit.skipInternalFilter,
    )

    private fun refExtra(fileKey: String, rowIndex: Int): String =
        """{"trans_ref":{"file_key":"$fileKey","row_index":$rowIndex}}"""

    private fun extraTags(unit: FormatUnit): List<String> =
        strings(parse(unit.extraField)["tag"])

    private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

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
private fun TransFormatAdapter.write(original: String, units: List<FormatUnit>): String =
    write(original, units, FormatWriteOptions())
