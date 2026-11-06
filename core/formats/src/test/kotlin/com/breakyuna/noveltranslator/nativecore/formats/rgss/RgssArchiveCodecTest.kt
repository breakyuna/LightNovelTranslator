package com.breakyuna.noveltranslator.nativecore.formats.rgss

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RgssArchiveCodecTest {
    @Test fun v1WriterAndReaderRoundTrip() {
        val first = ByteArray(37) { (it * 11).toByte() }
        val second = "hello rgss".toByteArray(Charsets.UTF_8)
        val archive = RgssArchive(
            1,
            listOf(
                RgssArchiveEntry("Data/Scripts.rxdata", first),
                RgssArchiveEntry("Graphics/Pictures/face.png", second),
            ),
        )
        val bytes = RgssArchiveCodec.write(archive)
        assertEquals(0x52, bytes[0].toInt() and 0xFF)
        assertEquals(1, bytes[7].toInt() and 0xFF)
        val read = RgssArchiveCodec.read(bytes)
        assertEquals(1, read.version)
        assertEquals(listOf("Data/Scripts.rxdata", "Graphics/Pictures/face.png"), read.entries.map { it.path })
        assertArrayEquals(first, read.entries[0].data)
        assertArrayEquals(second, read.entries[1].data)
        assertTrue(read.entries.none { it.compressed })
    }

    @Test fun v1ReadsIndependentFixture() {
        val fixture = RgssFixtures.v1(
            listOf(
                RgssFixtures.Entry("Data/Scripts.rxdata", "script".toByteArray(Charsets.UTF_8)),
                RgssFixtures.Entry("Audio/BGM/theme.ogg", ByteArray(0)),
            ),
        )
        val archive = RgssArchiveCodec.read(fixture)
        assertEquals(1, archive.version)
        assertEquals(2, archive.entries.size)
        assertEquals("Data/Scripts.rxdata", archive.entries[0].path)
        assertArrayEquals("script".toByteArray(Charsets.UTF_8), archive.entries[0].data)
        assertArrayEquals(ByteArray(0), archive.entries[1].data)
    }

    @Test fun v3RoundTripKeepsCompressedAndPlainShape() {
        val compressed = "compress me ".repeat(20).toByteArray(Charsets.UTF_8)
        val plain = "already plain".toByteArray(Charsets.UTF_8)
        val archive = RgssArchive(
            3,
            listOf(
                RgssArchiveEntry("Data/Scripts.rvdata2", compressed, true),
                RgssArchiveEntry("Data/Actors.rvdata2", plain, false),
            ),
        )
        val bytes = RgssArchiveCodec.write(archive)
        val read = RgssArchiveCodec.read(bytes)
        assertEquals(3, read.version)
        assertEquals(2, read.entries.size)
        assertTrue(read.entries[0].compressed)
        assertEquals(false, read.entries[1].compressed)
        assertArrayEquals(compressed, read.entries[0].data)
        assertArrayEquals(plain, read.entries[1].data)
    }

    @Test fun v3ReadsIndependentFixture() {
        val payload = "vx ace payload".toByteArray(Charsets.UTF_8)
        val fixture = RgssFixtures.v3(
            listOf(
                RgssFixtures.Entry("Data/Scripts.rvdata2", payload, compressed = true),
                RgssFixtures.Entry("Graphics/System/Window.png", ByteArray(0)),
            ),
        )
        val archive = RgssArchiveCodec.read(fixture)
        assertEquals(3, archive.version)
        assertEquals(2, archive.entries.size)
        assertTrue(archive.entries[0].compressed)
        assertArrayEquals(payload, archive.entries[0].data)
        assertEquals(0, archive.entries[1].data.size)
    }

    @Test fun emptyArchivesAreValid() {
        val v1 = RgssArchiveCodec.write(RgssArchive(1, emptyList()))
        assertEquals(8, v1.size)
        assertEquals(0, RgssArchiveCodec.read(v1).entries.size)
        val v3 = RgssArchiveCodec.write(RgssArchive(3, emptyList()))
        assertEquals(0, RgssArchiveCodec.read(v3).entries.size)
    }

    @Test fun rejectsUnknownHeaderAndVersion() {
        val badHeader = RgssFixtures.bytes(0x52, 0x47, 0x53, 0x53, 0x41, 0x44, 0x01, 0x01)
        assertEquals(
            RgssArchiveException.Reason.HEADER,
            failure { RgssArchiveCodec.read(badHeader) }.reason,
        )
        val versionTwo = RgssFixtures.v1(emptyList<RgssFixtures.Entry>()).copyOf()
            .also { it[7] = 0x02.toByte() }
        assertEquals(
            RgssArchiveException.Reason.VERSION,
            failure { RgssArchiveCodec.read(versionTwo) }.reason,
        )
        val unknownThree = RgssFixtures.v3(emptyList<RgssFixtures.Entry>()).copyOf()
            .also { it[7] = 0x04.toByte() }
        assertEquals(
            RgssArchiveException.Reason.VERSION,
            failure { RgssArchiveCodec.read(unknownThree) }.reason,
        )
    }

    @Test fun rejectsTraversalAndAbsoluteNames() {
        for (name in listOf("../evil.txt", "..\\evil.txt", "/etc/passwd", "C:\\game\\evil.txt")) {
            val fixture = RgssFixtures.v1(listOf(RgssFixtures.Entry(name, ByteArray(1))))
            assertEquals(
                RgssArchiveException.Reason.PATH,
                failure { RgssArchiveCodec.read(fixture) }.reason,
            )
        }
        val v3 = RgssFixtures.v3(listOf(RgssFixtures.Entry("..\\evil.txt", ByteArray(1))))
        assertEquals(
            RgssArchiveException.Reason.PATH,
            failure { RgssArchiveCodec.read(v3) }.reason,
        )
    }

    @Test fun rejectsDuplicatePathsCaseInsensitively() {
        val fixture = RgssFixtures.v1(
            listOf(
                RgssFixtures.Entry("Data/a.txt", ByteArray(1)),
                RgssFixtures.Entry("data/A.TXT", ByteArray(1)),
            ),
        )
        assertEquals(
            RgssArchiveException.Reason.DUPLICATE,
            failure { RgssArchiveCodec.read(fixture) }.reason,
        )
        val v3 = RgssFixtures.v3(
            listOf(
                RgssFixtures.Entry("Data/a.txt", ByteArray(1)),
                RgssFixtures.Entry("data/A.TXT", ByteArray(1)),
            ),
        )
        assertEquals(
            RgssArchiveException.Reason.DUPLICATE,
            failure { RgssArchiveCodec.read(v3) }.reason,
        )
    }

    @Test fun rejectsOutOfBoundsV3Offset() {
        val fixture = RgssFixtures.v3(
            listOf(RgssFixtures.Entry("Data/Scripts.rvdata2", "x".toByteArray(Charsets.UTF_8))),
        )
        val base = (RgssFixtures.V1_KEY * 9) + 3
        putI32(fixture, 12, 0x7FFFFFFF xor base)
        assertEquals(
            RgssArchiveException.Reason.OFFSET,
            failure { RgssArchiveCodec.read(fixture) }.reason,
        )
    }

    @Test fun rejectsInvalidZlibMarker() {
        val fixture = RgssFixtures.v3(
            listOf(
                RgssFixtures.Entry(
                    "Data/Scripts.rvdata2",
                    RgssFixtures.bytes(0x78, 0x9C, 0x00, 0x01, 0x02, 0x03),
                ),
            ),
        )
        assertEquals(
            RgssArchiveException.Reason.COMPRESSION,
            failure { RgssArchiveCodec.read(fixture) }.reason,
        )
    }

    @Test fun inflationHonorsEntryBudget() {
        val big = ByteArray(10_000) { 'A'.code.toByte() }
        val fixture = RgssFixtures.v3(
            listOf(RgssFixtures.Entry("Data/Map001.rvdata2", big, compressed = true)),
        )
        assertEquals(
            RgssArchiveException.Reason.LIMIT,
            failure {
                RgssArchiveCodec.read(fixture, RgssArchiveLimits(maxEntryBytes = 32))
            }.reason,
        )
    }

    @Test fun rejectsV1CompressionFlag() {
        val entry = RgssArchiveEntry("Data/a", ByteArray(1), true)
        assertEquals(
            RgssArchiveException.Reason.STRUCTURE,
            failure { RgssArchive(1, listOf(entry)) }.reason,
        )
    }

    @Test fun rejectsTruncatedEntryTable() {
        val fixture = RgssFixtures.v3(
            listOf(RgssFixtures.Entry("Data/Scripts.rvdata2", ByteArray(8))),
        )
        val truncated = fixture.copyOf(fixture.size - 12)
        val rejected = failure { RgssArchiveCodec.read(truncated) }
        assertTrue(
            rejected.reason == RgssArchiveException.Reason.TRUNCATED ||
                rejected.reason == RgssArchiveException.Reason.OFFSET,
        )
        val v1 = RgssFixtures.v1(
            listOf(RgssFixtures.Entry("Data/Scripts.rxdata", "payload".toByteArray(Charsets.UTF_8))),
        )
        assertEquals(
            RgssArchiveException.Reason.TRUNCATED,
            failure { RgssArchiveCodec.read(v1.copyOf(v1.size - 2)) }.reason,
        )
    }

    private fun failure(block: () -> Unit): RgssArchiveException {
        try {
            block()
        } catch (failure: RgssArchiveException) {
            return failure
        }
        fail("Expected RgssArchiveException")
        throw AssertionError("unreachable")
    }

    private fun putI32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }
}
