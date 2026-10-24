package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class RubyMarshalTest {
    @Test fun writesKnownPrimitiveBytes() {
        assertArrayEquals(bytes(4, 8, 0x30), RubyMarshal.serialize(MarshalNil()))
        assertArrayEquals(bytes(4, 8, 0x54), RubyMarshal.serialize(MarshalBool(true)))
        assertArrayEquals(bytes(4, 8, 0x46), RubyMarshal.serialize(MarshalBool(false)))
        assertArrayEquals(bytes(4, 8, 0x69, 0), RubyMarshal.serialize(MarshalInt(0)))
        assertArrayEquals(bytes(4, 8, 0x69, 6), RubyMarshal.serialize(MarshalInt(1)))
        assertArrayEquals(bytes(4, 8, 0x69, 0x7F), RubyMarshal.serialize(MarshalInt(122)))
        assertArrayEquals(bytes(4, 8, 0x69, 1, 0x7B), RubyMarshal.serialize(MarshalInt(123)))
        assertArrayEquals(bytes(4, 8, 0x69, 0xFA), RubyMarshal.serialize(MarshalInt(-1)))
        assertArrayEquals(bytes(4, 8, 0x69, 0x80), RubyMarshal.serialize(MarshalInt(-123)))
        assertArrayEquals(bytes(4, 8, 0x69, 0xFF, 0x84), RubyMarshal.serialize(MarshalInt(-124)))
        assertArrayEquals(bytes(4, 8, 0x69, 4, 0xFF, 0xFF, 0xFF, 0x7F),
            RubyMarshal.serialize(MarshalInt(Int.MAX_VALUE)))
        assertArrayEquals(bytes(4, 8, 0x69, 0xFC, 0x00, 0x00, 0x00, 0x80),
            RubyMarshal.serialize(MarshalInt(Int.MIN_VALUE)))
        assertArrayEquals(bytes(4, 8, 0x22, 7, 0x68, 0x69),
            RubyMarshal.serialize(MarshalString.ofUtf8("hi")))
        assertArrayEquals(bytes(4, 8, 0x3A, 8, 0x66, 0x6F, 0x6F),
            RubyMarshal.serialize(MarshalSymbol.of("foo")))
    }

    @Test fun readsKnownPrimitiveBytes() {
        assertEquals(MarshalNil::class.java, RubyMarshal.parse(bytes(4, 8, 0x30))::class.java)
        assertEquals(true, (RubyMarshal.parse(bytes(4, 8, 0x54)) as MarshalBool).value)
        assertEquals(false, (RubyMarshal.parse(bytes(4, 8, 0x46)) as MarshalBool).value)
        assertEquals(1, (RubyMarshal.parse(bytes(4, 8, 0x69, 6)) as MarshalInt).value)
        assertEquals(-124, (RubyMarshal.parse(bytes(4, 8, 0x69, 0xFF, 0x84)) as MarshalInt).value)
        assertEquals("hi", (RubyMarshal.parse(bytes(4, 8, 0x22, 7, 0x68, 0x69)) as MarshalString).utf8)
        assertEquals("foo", (RubyMarshal.parse(bytes(4, 8, 0x3A, 8, 0x66, 0x6F, 0x6F)) as MarshalSymbol).name)
    }

    @Test fun fixnumValuesRoundTripAtBoundaries() {
        val values = listOf(0, 1, 122, 123, 255, 256, 65_535, Int.MAX_VALUE, -1, -123, -124, -255, Int.MIN_VALUE)
        for (value in values) {
            val parsed = RubyMarshal.parse(RubyMarshal.serialize(MarshalInt(value)))
            assertEquals("value $value", value, (parsed as MarshalInt).value)
        }
    }

    @Test fun bignumRoundTripAndKnownFixture() {
        val values = listOf(
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.valueOf(1L shl 40),
            BigInteger("123456789012345678901234567890"),
            BigInteger("-98765432109876543210987654321"),
        )
        for (value in values) {
            val parsed = RubyMarshal.parse(RubyMarshal.serialize(MarshalBigInt(value)))
            assertEquals(value, (parsed as MarshalBigInt).value)
        }
        val fixture = bytes(4, 8, 0x6C, 0x2B, 8, 0, 0, 0, 0, 1, 0)
        assertEquals(BigInteger.valueOf(1L shl 32), (RubyMarshal.parse(fixture) as MarshalBigInt).value)
    }

    @Test fun symbolLinksAndObjectLinksPreserveIdentity() {
        val symbolStream = bytes(4, 8, 0x5B, 7, 0x3A, 8, 0x66, 0x6F, 0x6F, 0x3B, 0)
        val symbols = RubyMarshal.parse(symbolStream) as MarshalArray
        assertSame(symbols.elements[0], symbols.elements[1])

        // Ruby's object table includes the root array at index 0, so the repeated string is @1.
        val objectStream = bytes(4, 8, 0x5B, 7, 0x22, 7, 0x68, 0x69, 0x40, 6)
        val objects = RubyMarshal.parse(objectStream) as MarshalArray
        assertSame(objects.elements[0], objects.elements[1])

        val shared = MarshalString.ofUtf8("shared")
        val graph = MarshalArray(listOf(shared, shared))
        val roundTrip = RubyMarshal.parse(RubyMarshal.serialize(graph)) as MarshalArray
        assertSame(roundTrip.elements[0], roundTrip.elements[1])
    }

    @Test fun stringEncodingIvarIsPreserved() {
        val fixture = bytes(4, 8, 0x49, 0x22, 7, 0x68, 0x69, 6, 0x3A, 6, 0x45, 0x54)
        val value = RubyMarshal.parse(fixture) as MarshalString
        assertEquals("UTF-8", value.encodingName())
        assertArrayEquals(fixture, RubyMarshal.serialize(value))
    }

    @Test fun objectWithIvarsRoundTrip() {
        val fixture = bytes(
            4, 8, 0x6F, 0x3A, 8, 0x46, 0x6F, 0x6F, 6, 0x3A, 7, 0x40, 0x78, 0x69, 6,
        )
        val value = RubyMarshal.parse(fixture) as MarshalObject
        assertEquals("Foo", value.className)
        assertEquals(1, value.ivars.size)
        assertEquals("@x", value.ivars[0].name)
        assertEquals(1, (value.ivars[0].value as MarshalInt).value)
        val roundTrip = RubyMarshal.parse(RubyMarshal.serialize(value)) as MarshalObject
        assertEquals("Foo", roundTrip.className)
        assertEquals(1, (roundTrip.ivars[0].value as MarshalInt).value)
    }

    @Test fun hashAndDefaultRoundTrip() {
        val fixture = bytes(4, 8, 0x7D, 6, 0x3A, 6, 0x61, 0x69, 6, 0x69, 7)
        val value = RubyMarshal.parse(fixture) as MarshalHash
        assertEquals(1, value.entries.size)
        assertEquals("a", (value.entries[0].key as MarshalSymbol).name)
        assertEquals(1, (value.entries[0].value as MarshalInt).value)
        assertEquals(2, (value.defaultValue as MarshalInt).value)
        val roundTrip = RubyMarshal.parse(RubyMarshal.serialize(value)) as MarshalHash
        assertEquals(2, (roundTrip.defaultValue as MarshalInt).value)
    }

    @Test fun floatRoundTrip() {
        val fixture = bytes(4, 8, 0x66, 8, 0x31, 0x2E, 0x35)
        val value = RubyMarshal.parse(fixture) as MarshalFloat
        assertEquals("1.5", value.text)
        assertEquals(1.5, value.value, 0.0)
        val roundTrip = RubyMarshal.parse(RubyMarshal.serialize(value)) as MarshalFloat
        assertEquals("1.5", roundTrip.text)
        assertEquals("nan", (RubyMarshal.parse(RubyMarshal.serialize(MarshalFloat.of(Double.NaN))) as MarshalFloat).text)
    }

    @Test fun arrayAndHashIdentitySurvivesCycle() {
        val array = MarshalArray()
        array.elements.add(array)
        val parsed = RubyMarshal.parse(RubyMarshal.serialize(array)) as MarshalArray
        assertSame(parsed, parsed.elements[0])

        val shared = MarshalArray(listOf(MarshalInt(1)))
        val hash = MarshalHash(listOf(MarshalPair(MarshalSymbol.of("k"), shared), MarshalPair(MarshalSymbol.of("v"), shared)))
        val roundTrip = RubyMarshal.parse(RubyMarshal.serialize(hash)) as MarshalHash
        assertSame(roundTrip.entries[0].value, roundTrip.entries[1].value)
    }

    @Test fun userDefinedPayloadsStayOpaqueAndRoundTrip() {
        val fixture = bytes(
            4, 8, 0x75, 0x3A, 10, 0x54, 0x61, 0x62, 0x6C, 0x65, 8, 1, 2, 3,
        )
        val value = RubyMarshal.parse(fixture) as MarshalUserData
        assertEquals("Table", value.className)
        assertArrayEquals(bytes(1, 2, 3), value.bytes)
        assertArrayEquals(fixture, RubyMarshal.serialize(value))
    }

    @Test fun unsupportedAndUnknownTypesFailExplicitly() {
        assertEquals(
            MarshalException.Reason.UNSUPPORTED,
            failure { RubyMarshal.parse(bytes(4, 8, 0x55)) }.reason,
        )
        assertEquals(
            MarshalException.Reason.TYPE,
            failure { RubyMarshal.parse(bytes(4, 8, 0x5A)) }.reason,
        )
        assertEquals(
            MarshalException.Reason.LENGTH,
            failure { RubyMarshal.parse(bytes(4, 8, 0x75)) }.reason,
        )
    }

    @Test fun invalidLinksTrailingBytesAndLimitsFail() {
        assertEquals(
            MarshalException.Reason.LINK,
            failure { RubyMarshal.parse(bytes(4, 8, 0x40, 6)) }.reason,
        )
        assertEquals(
            MarshalException.Reason.TRAILING,
            failure { RubyMarshal.parse(bytes(4, 8, 0x30, 0x00)) }.reason,
        )
        assertEquals(
            MarshalException.Reason.LIMIT,
            failure {
                RubyMarshal.parse(bytes(4, 8, 0x22, 8, 0x61, 0x62, 0x63), MarshalLimits(maxStringBytes = 2))
            }.reason,
        )

        var nested: MarshalValue = MarshalArray()
        repeat(6) { nested = MarshalArray(listOf(nested)) }
        assertEquals(
            MarshalException.Reason.DEPTH,
            failure { RubyMarshal.parse(RubyMarshal.serialize(nested), MarshalLimits(maxDepth = 3)) }.reason,
        )
        assertEquals(
            MarshalException.Reason.SYMBOL,
            failure { RubyMarshal.parse(bytes(4, 8, 0x49, 0x3B, 6)) }.reason,
        )
    }

    private fun failure(block: () -> Unit): MarshalException {
        try {
            block()
        } catch (failure: MarshalException) {
            return failure
        }
        fail("Expected MarshalException")
        throw AssertionError("unreachable")
    }

    private fun bytes(vararg values: Int): ByteArray = RgssFixtures.bytes(*values)
}
