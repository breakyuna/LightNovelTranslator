package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolArgumentsTest {
    @Test
    fun rejectsFractionalAndOverflowingWriteParametersBeforeConversion() {
        for (token in listOf("1.5", "4294967297", "2147483648", "-2147483649")) {
            assertThrows(InvalidToolArgument::class.java) {
                ToolArguments.int(ToolScalar.NumberToken(token), "count", Int.MIN_VALUE, Int.MAX_VALUE)
            }
        }
    }

    @Test
    fun preservesLongIdentitiesBeyondDoublePrecision() {
        assertEquals(9007199254740993L, readLong("9007199254740993"))
        assertEquals(Long.MAX_VALUE, readLong("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, readLong("-9223372036854775808"))
        for (token in listOf("9223372036854775808", "-9223372036854775809", "1e100000", "1e2147483648", "1e-2147483648")) {
            assertThrows(InvalidToolArgument::class.java) { readLong(token) }
        }
    }

    @Test
    fun acceptsIntegralJsonRepresentationsWithinTheDeclaredRange() {
        for (token in listOf("1", "1.0", "1e0", "10e-1")) {
            assertEquals(1L, ToolArguments.long(ToolScalar.NumberToken(token), "id", 1, 1))
        }
        assertEquals(0L, readLong("-0"))
    }

    @Test
    fun neverCoercesMissingTextBooleanNullOrNonJsonNumbers() {
        val values = listOf(null, ToolScalar.Text("1"), ToolScalar.BooleanValue(true), ToolScalar.NullValue)
        for (value in values) {
            assertThrows(InvalidToolArgument::class.java) { ToolArguments.int(value, "count", 1, 10) }
        }
        for (token in listOf("NaN", "Infinity", "+1", "01", " 1", "1 ", "0x1", ".1", "1.")) {
            assertThrows(InvalidToolArgument::class.java) { readLong(token) }
        }
    }

    @Test
    fun errorsDoNotEchoSuppliedMaterial() {
        val error = assertThrows(InvalidToolArgument::class.java) { readLong("private-material") }
        assertEquals("Invalid tool argument: id (TYPE)", error.message)
    }

    private fun readLong(token: String): Long =
        ToolArguments.long(ToolScalar.NumberToken(token), "id", Long.MIN_VALUE, Long.MAX_VALUE)
}
