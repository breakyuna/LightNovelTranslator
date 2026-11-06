package com.breakyuna.noveltranslator.nativecore.formats.rgss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RgssScriptsCodecTest {
    @Test fun roundTripPreservesScriptsAndDeflatesCode() {
        val archive = RgssScriptsArchive(
            listOf(
                RgssScript.utf8(1, "Main", "puts 'Hello'"),
                RgssScript.utf8(2, "日本語", "print(\"こんにちは\")"),
                RgssScript.utf8(3, "Empty", ""),
            ),
        )
        val bytes = RgssScriptsCodec.write(archive)
        assertTrue(containsZlibMarker(bytes))
        val read = RgssScriptsCodec.read(bytes)
        assertEquals(3, read.scripts.size)
        assertEquals(listOf(1, 2, 3), read.scripts.map { it.id })
        assertEquals("Main", read.scripts[0].name)
        assertEquals("puts 'Hello'", read.scripts[0].code)
        assertEquals("日本語", read.scripts[1].name)
        assertEquals("print(\"こんにちは\")", read.scripts[1].code)
        assertEquals("", read.scripts[2].code)
    }

    @Test fun emptyArchiveRoundTrip() {
        val bytes = RgssScriptsCodec.write(RgssScriptsArchive(emptyList()))
        assertEquals(0, RgssScriptsCodec.read(bytes).scripts.size)
    }

    @Test fun readsPlainCodeVariant() {
        val bytes = root(
            entry(1, "Main".toByteArray(Charsets.UTF_8), "puts 1".toByteArray(Charsets.UTF_8)),
        )
        val archive = RgssScriptsCodec.read(bytes)
        assertEquals(1, archive.scripts.size)
        assertEquals("puts 1", archive.scripts[0].code)
        val rewritten = RgssScriptsCodec.write(archive)
        assertEquals("puts 1", RgssScriptsCodec.read(rewritten).scripts[0].code)
    }

    @Test fun shiftJisNamesAndCodeAreDecodedAndReEncoded() {
        val shiftJis = java.nio.charset.Charset.forName("Shift_JIS")
        val bytes = root(
            entry(
                1,
                "メイン".toByteArray(shiftJis),
                "puts 'こんにちは'".toByteArray(shiftJis),
            ),
        )
        val archive = RgssScriptsCodec.read(bytes)
        assertEquals("メイン", archive.scripts[0].name)
        assertEquals("puts 'こんにちは'", archive.scripts[0].code)
        val readBack = RgssScriptsCodec.read(RgssScriptsCodec.write(archive))
        assertEquals("メイン", readBack.scripts[0].name)
        assertEquals("puts 'こんにちは'", readBack.scripts[0].code)
    }

    @Test fun rejectsBadRootAndWrongEntryShape() {
        val stringRoot = RubyMarshal.serialize(MarshalString.ofUtf8("not an array"))
        assertEquals(
            RgssScriptsException.Reason.STRUCTURE,
            failure { RgssScriptsCodec.read(stringRoot) }.reason,
        )
        val wrong = RubyMarshal.serialize(
            MarshalArray(listOf(MarshalArray(listOf(MarshalInt(1), MarshalString.ofUtf8("only two"))))),
        )
        assertEquals(
            RgssScriptsException.Reason.STRUCTURE,
            failure { RgssScriptsCodec.read(wrong) }.reason,
        )
    }

    @Test fun rejectsDuplicateIdsAndBadScriptShape() {
        val duplicate = root(
            entry(1, "A".toByteArray(Charsets.UTF_8), "a".toByteArray(Charsets.UTF_8)),
            entry(1, "B".toByteArray(Charsets.UTF_8), "b".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            RgssScriptsException.Reason.ID,
            failure { RgssScriptsCodec.read(duplicate) }.reason,
        )
        val negative = root(entry(-1, "A".toByteArray(Charsets.UTF_8), "a".toByteArray(Charsets.UTF_8)))
        assertEquals(
            RgssScriptsException.Reason.ID,
            failure { RgssScriptsCodec.read(negative) }.reason,
        )
    }

    @Test fun rejectsCorruptZlibAndCodeBudget() {
        val corrupt = root(
            entry(
                1,
                "A".toByteArray(Charsets.UTF_8),
                RgssFixtures.bytes(0x78, 0x9C, 0x00, 0x01, 0x02),
            ),
        )
        assertEquals(
            RgssScriptsException.Reason.COMPRESSION,
            failure { RgssScriptsCodec.read(corrupt) }.reason,
        )
        val plain = root(entry(1, "A".toByteArray(Charsets.UTF_8), "12345678".toByteArray(Charsets.UTF_8)))
        assertEquals(
            RgssScriptsException.Reason.LIMIT,
            failure { RgssScriptsCodec.read(plain, RgssScriptsLimits(maxCodeBytes = 4)) }.reason,
        )
    }

    @Test fun rejectsTooManyScripts() {
        val entries = (1..3).map {
            entry(it, "S$it".toByteArray(Charsets.UTF_8), "code$it".toByteArray(Charsets.UTF_8))
        }
        val bytes = root(*entries.toTypedArray())
        assertEquals(
            RgssScriptsException.Reason.LIMIT,
            failure { RgssScriptsCodec.read(bytes, RgssScriptsLimits(maxScripts = 2)) }.reason,
        )
    }

    private fun root(vararg entries: MarshalArray): ByteArray =
        RubyMarshal.serialize(MarshalArray(entries.toList()))

    private fun entry(id: Int, name: ByteArray, code: ByteArray): MarshalArray =
        MarshalArray(
            listOf(
                MarshalInt(id),
                MarshalString.ofBytes(name),
                MarshalString.ofBytes(code),
            ),
        )

    private fun containsZlibMarker(bytes: ByteArray): Boolean {
        for (index in 0 until bytes.size - 1) {
            if ((bytes[index].toInt() and 0xFF) == 0x78 &&
                (bytes[index + 1].toInt() and 0xFF) == 0x9C
            ) {
                return true
            }
        }
        return false
    }

    private fun failure(block: () -> Unit): RgssScriptsException {
        try {
            block()
        } catch (failure: RgssScriptsException) {
            return failure
        }
        fail("Expected RgssScriptsException")
        throw AssertionError("unreachable")
    }
}
