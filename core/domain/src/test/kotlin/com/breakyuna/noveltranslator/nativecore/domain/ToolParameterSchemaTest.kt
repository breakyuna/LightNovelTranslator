package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolParameterSchemaTest {
    private val limits = JsonInputLimits(4096, 16)
    private val schema = ToolParameterSchema(mapOf(
        "id" to ToolParameter.Integer(1, Long.MAX_VALUE),
        "label" to ToolParameter.Text(0, 1, required = false),
        "enabled" to ToolParameter.BooleanValue(required = false),
    ))

    @Test
    fun schemaAndValidationShareIntegerBoundsAndRequiredFields() {
        val definition = schema.definition("lookup", "synthetic")
        val id = definition.inputSchema.json["properties"]!!.jsonObject["id"]!!.jsonObject
        assertEquals(Long.MAX_VALUE.toString(), id["maximum"].toString())
        assertEquals("[\"id\"]", definition.inputSchema.json["required"].toString())
        val values = schema.validate(parse("""{"id":9007199254740993,"enabled":false}"""))
        assertEquals(9007199254740993L, values.integer("id"))
        assertEquals(false, values.boolean("enabled"))
        assertEquals(null, values.text("label"))
    }

    @Test
    fun rejectsFractionalOverflowCoercionUnknownAndMissingFields() {
        for (text in listOf("{}", """{"id":0}""", """{"id":1.5}""", """{"id":9223372036854775808}""",
                            """{"id":"1"}""", """{"id":true}""", """{"id":1,"extra":1}""",
                            """{"id":1,"enabled":"false"}""", """{"id":1,"label":null}""")) {
            assertThrows(InvalidToolParameters::class.java) { schema.validate(parse(text)) }
        }
    }

    @Test
    fun textLengthMatchesJsonSchemaUnicodeCodePoints() {
        assertEquals("😀", schema.validate(parse("""{"id":1,"label":"😀"}""")).text("label"))
        assertThrows(InvalidToolParameters::class.java) { schema.validate(parse("""{"id":1,"label":"😀😀"}""")) }
    }

    private fun parse(text: String) = JsonObjectValue.parse(text, limits)
}
