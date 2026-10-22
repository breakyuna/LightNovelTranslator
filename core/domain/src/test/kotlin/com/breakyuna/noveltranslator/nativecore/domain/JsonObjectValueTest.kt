package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonObjectValueTest {
    private val limits = JsonInputLimits(4096, 16)

    @Test
    fun numericLexemesArePreservedWithoutDoubleConversion() {
        val parsed = JsonObjectValue.parse("""{"id":9007199254740993,"count":1e0}""", limits)
        assertEquals("9007199254740993", parsed.json["id"].toString())
        assertEquals("1e0", parsed.json["count"].toString())
    }

    @Test
    fun rejectsLiteralAndEscapedDuplicateKeysAtEveryObjectDepth() {
        for (text in listOf("""{"a":1,"a":2}""", """{"a":1,"\u0061":2}""",
                            """{"items":[{"id":1,"id":2}]}""")) {
            assertEquals(InvalidJsonPayload.Reason.DUPLICATE_KEY,
                assertThrows(InvalidJsonPayload::class.java) { JsonObjectValue.parse(text, limits) }.reason)
        }
    }

    @Test
    fun sameKeysInSeparateObjectsAndPunctuationInStringsAreValid() {
        val parsed = JsonObjectValue.parse("""{"a":{"id":1},"b":{"id":2},"text":"[,{}]\""}""", limits)
        assertEquals(3, parsed.json.size)
    }

    @Test
    fun rejectsNonObjectsNonJsonPrimitivesAndTrailingInput() {
        for (text in listOf("[]", "null", "1", "true", """{"a":NaN}""", """{"a":foo}""",
                            """{"a":01}""", """{"a":1,}""", "{} {}", """{"a":1""")) {
            assertThrows(InvalidJsonPayload::class.java) { JsonObjectValue.parse(text, limits) }
        }
    }

    @Test
    fun boundsSizeAndDepthBeforeRecursiveParsing() {
        assertEquals(InvalidJsonPayload.Reason.SIZE,
            assertThrows(InvalidJsonPayload::class.java) {
                JsonObjectValue.parse("""{"large":"synthetic"}""", JsonInputLimits(3, 16))
            }.reason)
        assertEquals(InvalidJsonPayload.Reason.DEPTH,
            assertThrows(InvalidJsonPayload::class.java) {
                JsonObjectValue.parse("""{"a":[[]]}""", JsonInputLimits(4096, 2))
            }.reason)
    }

    @Test
    fun exposedTreeIsDeepFrozenAgainstMutation() {
        val parsed = JsonObjectValue.parse("""{"items":[{"id":1}]}""", limits)
        @Suppress("UNCHECKED_CAST")
        val topEntry = parsed.json.entries.first() as MutableMap.MutableEntry<String, JsonElement>
        assertThrows(UnsupportedOperationException::class.java) { topEntry.setValue(JsonPrimitive(2)) }
        val nested = (parsed.json["items"] as JsonArray)[0] as JsonObject
        @Suppress("UNCHECKED_CAST")
        val nestedEntry = nested.entries.first() as MutableMap.MutableEntry<String, JsonElement>
        assertThrows(UnsupportedOperationException::class.java) { nestedEntry.setValue(JsonPrimitive(2)) }
        assertEquals("""{"items":[{"id":1}]}""", parsed.encode())
    }

    @Test
    fun diagnosticsAndDefaultStringNeverEchoMaterial() {
        val text = """{"secret":"private-material"}"""
        assertFalse(JsonObjectValue.parse(text, limits).toString().contains("private-material"))
        val failure = assertThrows(InvalidJsonPayload::class.java) {
            JsonObjectValue.parse(text + "bad", limits)
        }
        assertFalse(failure.toString().contains("private-material"))
    }
}
