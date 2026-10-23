package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MessageJsonFormatAdapterTest {
    private val adapter = MessageJsonFormatAdapter()
    private val path = RelativePath("game/messages.json")

    private fun translated(
        unit: FormatUnit,
        text: String,
        name: List<String>? = unit.nameTranslation,
    ) = FormatUnit(
        row = unit.row,
        source = unit.source,
        translation = text,
        status = TextItemStatus.PROCESSED,
        tag = unit.tag,
        nameSource = unit.nameSource,
        nameTranslation = name,
        extraField = unit.extraField,
        skipInternalFilter = unit.skipInternalFilter,
    )

    private fun rejects(reason: InvalidMessageJson.Reason, block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid message JSON")
        } catch (failure: InvalidMessageJson) {
            assertEquals(reason, failure.reason)
        }
    }

    private val sample = "[\r\n" +
        "  {\"name\": \"Alice\", \"message\": \"Hello\", \"voice\": \"a.wav\"},\r\n" +
        "  {\"names\": [\"Bob\", \"B\"], \"message\": \"Hi\", \"tag\": 7},\r\n" +
        "  {\"message\": 42},\r\n" +
        "  {\"other\": true}\r\n" +
        "]\r\n"

    @Test fun readKeepsMessageRowsAndNameFields() {
        val document = adapter.read(sample, path)
        assertEquals(2, document.units.size)
        assertEquals(listOf("Hello", "Hi"), document.units.map { it.source })
        assertEquals(listOf(1, 2), document.units.map { it.row })
        assertEquals(listOf(listOf("Alice"), listOf("Bob", "B")),
            document.units.map { it.nameSource })
        assertTrue(document.units.all { it.status == TextItemStatus.NONE })
        assertTrue(document.units.all { it.nameTranslation == null })
        assertEquals(sample, adapter.write(sample, document.units, FormatWriteOptions()))
    }

    @Test fun writePatchesMessagesAndPreservesUnknownFieldsAndEntries() {
        val document = adapter.read(sample, path)
        val units = document.units.map { unit ->
            if (unit.source == "Hello") translated(unit, "你好") else unit
        }
        val expected = sample.replace("\"Hello\"", "\"你好\"")
        assertEquals(expected, adapter.write(sample, units, FormatWriteOptions()))
        assertEquals(expected, adapter.write(sample, units.reversed(), FormatWriteOptions()))
        assertTrue(expected.contains("\"voice\": \"a.wav\""))
        assertTrue(expected.contains("\"tag\": 7"))
        assertTrue(expected.contains("{\"message\": 42}"))
        assertTrue(expected.contains("{\"other\": true}"))
        assertEquals(expected, adapter.write(sample, units,
            FormatWriteOptions(bilingual = true, deduplicateSame = true)))
    }

    @Test fun translatedNamesFollowScalarAndArrayShapes() {
        val document = adapter.read(sample, path)
        val units = document.units.map { unit ->
            when (unit.row) {
                1 -> translated(unit, "你好", listOf("艾丽丝"))
                2 -> translated(unit, "嗨", listOf("鲍勃"))
                else -> unit
            }
        }
        val expected = sample
            .replace("\"Hello\"", "\"你好\"")
            .replace("\"Hi\"", "\"嗨\"")
            .replace("\"Alice\"", "\"艾丽丝\"")
            .replace("\"Bob\"", "\"鲍勃\"")
        assertEquals(expected, adapter.write(sample, units,
            FormatWriteOptions(writeTranslatedNames = true)))
        // Without the frozen option the translated names must not leak into the output.
        val withoutNames = sample
            .replace("\"Hello\"", "\"你好\"")
            .replace("\"Hi\"", "\"嗨\"")
        assertEquals(withoutNames, adapter.write(sample, units, FormatWriteOptions()))
        assertTrue(expected.contains("\"names\": [\"鲍勃\", \"B\"]"))
    }

    @Test fun emptyProcessedMessageIsWrittenAndUnprocessedUnitsAreIgnored() {
        val document = adapter.read(sample, path)
        val cleared = document.units.map { unit ->
            if (unit.source == "Hello") translated(unit, "") else unit
        }
        assertEquals(sample.replace("\"Hello\"", "\"\""),
            adapter.write(sample, cleared, FormatWriteOptions()))

        val untouched = document.units.map { unit ->
            if (unit.source == "Hello") FormatUnit(
                row = unit.row,
                source = unit.source,
                translation = "ignored",
                status = TextItemStatus.NONE,
                nameSource = unit.nameSource,
                extraField = unit.extraField,
            ) else unit
        }
        assertEquals(sample, adapter.write(sample, untouched, FormatWriteOptions()))
    }

    @Test fun namePreferenceMatchesLgAndNonStringNamesAreSkipped() {
        val source = "[\n" +
            "  {\"name\": \"Scalar\", \"names\": [\"Array\"], \"message\": \"one\"},\n" +
            "  {\"names\": [1, \"Two\", null], \"message\": \"two\"}\n" +
            "]\n"
        val document = adapter.read(source, path)
        assertEquals(listOf(listOf("Scalar"), listOf("Two")), document.units.map { it.nameSource })
        assertEquals(source, adapter.write(source, document.units, FormatWriteOptions()))
        val named = document.units.map { unit ->
            if (unit.row == 2) translated(unit, unit.source, listOf("二")) else unit
        }
        assertEquals(source.replace("\"Two\"", "\"二\""),
            adapter.write(source, named, FormatWriteOptions(writeTranslatedNames = true)))
    }

    @Test fun writeRejectsStaleForeignDuplicateOrRemovedEntries() {
        val document = adapter.read(sample, path)
        val first = document.units.first()
        val stale = FormatUnit(row = 1, source = "different", extraField = first.extraField)
        rejects(InvalidMessageJson.Reason.STALE) {
            adapter.write(sample, listOf(stale) + document.units.drop(1), FormatWriteOptions())
        }
        rejects(InvalidMessageJson.Reason.BINDING) {
            adapter.write(sample, listOf(FormatUnit(row = 3, source = "x",
                extraField = "{\"index\":2}")) + document.units.drop(1), FormatWriteOptions())
        }
        rejects(InvalidMessageJson.Reason.STALE) {
            adapter.write(sample, listOf(FormatUnit(
                row = 1,
                source = "Hello",
                extraField = "{\"index\":1}",
            )) + document.units.drop(1), FormatWriteOptions())
        }
        rejects(InvalidMessageJson.Reason.BINDING) {
            adapter.write(sample, listOf(first, first) + document.units.drop(1), FormatWriteOptions())
        }
    }

    @Test fun malformedRootsAndInvalidTextAreRejected() {
        rejects(InvalidMessageJson.Reason.STRUCTURE) { adapter.read("{}", path) }
        rejects(InvalidMessageJson.Reason.STRUCTURE) { adapter.read("[bad]", path) }
        rejects(InvalidMessageJson.Reason.STRUCTURE) {
            adapter.read("[{\"message\":\"a\",\"message\":\"b\"}]", path)
        }
        rejects(InvalidMessageJson.Reason.STRUCTURE) {
            adapter.read("[{\"message\":\"\\q\"}]", path)
        }
        rejects(InvalidMessageJson.Reason.TEXT) {
            adapter.read("[{\"message\":\"x\u0000y\"}]", path)
        }
        rejects(InvalidMessageJson.Reason.TEXT) {
            adapter.read("[{\"message\":\"\uD800\"}]", path)
        }
        rejects(InvalidMessageJson.Reason.TEXT) {
            adapter.read("[{\"message\":\"\\uD800\"}]", path)
        }
    }

    @Test fun emptyArrayAndNonMessageEntriesStayStable() {
        val source = "  [\r\n  ]\r\n"
        val document = adapter.read(source, path)
        assertTrue(document.units.isEmpty())
        assertEquals(source, adapter.write(source, emptyList(), FormatWriteOptions()))
    }
}
