package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KvJsonFormatAdapterTest {
    private val adapter = KvJsonFormatAdapter()
    private val path = RelativePath("locale/strings.json")

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

    private fun extraField(unit: FormatUnit, name: String): String? =
        (JsonObjectValue.parse(unit.extraField, JsonInputLimits(65_536, 8)).json[name] as? JsonPrimitive)?.content

    private fun rejects(reason: InvalidKvJson.Reason, block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid key/value JSON")
        } catch (failure: InvalidKvJson) {
            assertEquals(reason, failure.reason)
        }
    }

    private val sample = "{\r\n" +
        "    \"greeting\": \"Hello\",\r\n" +
        "    \"nested\": { \"a\": 1, \"b\": [true, null] },\r\n" +
        "    \"count\": 1.50,\r\n" +
        "    \"empty\": \"\",\r\n" +
        "    \"same\": \"same\"\r\n" +
        "}\r\n"

    @Test fun readUsesKeyAsSourceAndValueAsExistingTranslation() {
        val document = adapter.read(sample, path)
        assertEquals(listOf("greeting", "empty", "same"), document.units.map { it.source })
        assertEquals(listOf("Hello", "", "same"), document.units.map { it.translation })
        assertEquals(listOf(1, 2, 3), document.units.map { it.row })
        assertEquals(listOf(TextItemStatus.PROCESSED, TextItemStatus.NONE, TextItemStatus.NONE),
            document.units.map { it.status })
        assertEquals("1", extraField(document.units[0], "index"))
        assertEquals(sample, adapter.write(sample, document.units, FormatWriteOptions()))
    }

    @Test fun writePatchesOnlyProcessedStringValuesAndKeepsEveryOtherByte() {
        val document = adapter.read(sample, path)
        val units = document.units.map { unit ->
            when (unit.source) {
                "greeting" -> translated(unit, "你好\nline")
                "same" -> translated(unit, unit.source)
                else -> unit
            }
        }
        val expected = sample.replace("\"Hello\"", "\"你好\\nline\"")
        assertEquals(expected, adapter.write(sample, units, FormatWriteOptions()))
        assertEquals(expected, adapter.write(sample, units.reversed(), FormatWriteOptions()))
        assertEquals(expected, adapter.write(sample, units,
            FormatWriteOptions(bilingual = true, deduplicateSame = true)))
        assertTrue(expected.contains("\"count\": 1.50"))
        assertTrue(expected.contains("\"b\": [true, null]"))
    }

    @Test fun processedEmptyTranslationIsWrittenAsAnEmptyString() {
        val source = "{\"key\": \"old\"}"
        val document = adapter.read(source, path)
        assertEquals("{\"key\": \"\"}",
            adapter.write(source, listOf(translated(document.units.single(), "")), FormatWriteOptions()))
    }

    @Test fun nonProcessedUnitsNeverOverwriteTheFrozenValue() {
        val document = adapter.read(sample, path)
        val units = document.units.map { unit ->
            if (unit.source == "same") FormatUnit(
                row = unit.row,
                source = unit.source,
                translation = "ignored",
                status = TextItemStatus.NONE,
                extraField = unit.extraField,
            ) else unit
        }
        assertEquals(sample, adapter.write(sample, units, FormatWriteOptions()))
    }

    @Test fun escapedTranslationsRoundTripThroughTheStrictParser() {
        val source = "{\n  \"key\": \"old\"\n}\n"
        val document = adapter.read(source, path)
        val value = "quote \" slash \\ line\nline\ttab\u0001end"
        val output = adapter.write(source, listOf(translated(document.units.single(), value)),
            FormatWriteOptions())
        assertEquals(value, adapter.read(output, path).units.single().translation)
        assertTrue(output.contains("\\u0001"))
    }

    @Test fun emptyKeysAreSkippedAndUnrelatedValuesRemainLiteral() {
        val source = "{\"\": \"placeholder\", \"a\": \"b\", \"n\": 1e+02}"
        val document = adapter.read(source, path)
        assertEquals(listOf("", "a"), document.units.map { it.source })
        assertEquals(TextItemStatus.RULE_SKIPPED, document.units[0].status)
        assertEquals(source, adapter.write(source, document.units, FormatWriteOptions()))
    }

    @Test fun writeRejectsStaleForeignAndTamperedUnits() {
        val document = adapter.read(sample, path)
        val first = document.units.first()
        val stale = FormatUnit(row = 1, source = "other", extraField = first.extraField)
        rejects(InvalidKvJson.Reason.STALE) {
            adapter.write(sample, listOf(stale) + document.units.drop(1), FormatWriteOptions())
        }
        rejects(InvalidKvJson.Reason.BINDING) {
            adapter.write(sample, listOf(FormatUnit(row = 99, source = "x",
                extraField = "{\"index\":99}")) + document.units.drop(1), FormatWriteOptions())
        }
        rejects(InvalidKvJson.Reason.STALE) {
            adapter.write(sample, listOf(FormatUnit(
                row = 1,
                source = "greeting",
                extraField = "{\"index\":1,\"value_sha256\":\"deadbeef\"}",
            )) + document.units.drop(1), FormatWriteOptions())
        }
        rejects(InvalidKvJson.Reason.STALE) {
            adapter.write(sample, listOf(FormatUnit(
                row = 1,
                source = "greeting",
                extraField = "{\"index\":3}",
            )) + document.units.drop(1), FormatWriteOptions())
        }
        rejects(InvalidKvJson.Reason.BINDING) {
            adapter.write(sample, listOf(first, first) + document.units.drop(1), FormatWriteOptions())
        }
    }

    @Test fun malformedJsonIsRejected() {
        rejects(InvalidKvJson.Reason.STRUCTURE) { adapter.read("[]", path) }
        rejects(InvalidKvJson.Reason.STRUCTURE) { adapter.read("{bad}", path) }
        rejects(InvalidKvJson.Reason.STRUCTURE) { adapter.read("{\"a\":\"1\",\"a\":\"2\"}", path) }
    }

    @Test fun emptyObjectAndWhitespaceInputStayStable() {
        val source = "  {  }\r\n"
        val document = adapter.read(source, path)
        assertTrue(document.units.isEmpty())
        assertEquals(source, adapter.write(source, emptyList(), FormatWriteOptions()))
    }

    @Test fun bomAndObjectKeyOrderArePreserved() {
        val source = "\uFEFF{\n  \"b\": \"2\",\n  \"a\": \"1\"\n}\n"
        val document = adapter.read(source, path)
        assertEquals(listOf("b", "a"), document.units.map { it.source })
        assertEquals(source, adapter.write(source, document.units, FormatWriteOptions()))
    }
}
