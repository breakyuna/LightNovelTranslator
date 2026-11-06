package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class AgentAttachmentTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2)
    private val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray() + byteArrayOf(1)

    @Test fun mimeWhitelistNormalizesAliasesAndRejectsWildcards() {
        assertEquals("image/png", AgentAttachmentMime.normalize("IMAGE/PNG"))
        assertEquals("image/jpeg", AgentAttachmentMime.normalize("image/jpg"))
        assertEquals("image/jpeg", AgentAttachmentMime.normalize("image/pjpeg; charset=binary"))
        assertEquals("image/webp", AgentAttachmentMime.normalize("image/webp"))
        assertNull(AgentAttachmentMime.normalize("image/gif"))
        assertNull(AgentAttachmentMime.normalize("image/*"))
        assertNull(AgentAttachmentMime.normalize(null))
    }

    @Test fun contentSniffingNeverTrustsTheDeclaredType() {
        assertEquals("image/png", AgentAttachmentMime.detect(png))
        assertEquals("image/jpeg", AgentAttachmentMime.detect(jpeg))
        assertEquals("image/webp", AgentAttachmentMime.detect(webp))
        assertNull(AgentAttachmentMime.detect("not-an-image".toByteArray()))
        assertTrue(AgentAttachmentMime.matches("image/png", png))
        assertFalse(AgentAttachmentMime.matches("image/jpeg", png))
    }

    @Test fun dataCopiesBytesAndRejectsForeignMetadata() {
        val digest = TextProjectFingerprint.bytes(png)
        val data = AgentAttachmentData(AttachmentId("attachment"), "image/png", digest, png, "page.png")
        val first = data.bytes()
        first[0] = 0
        assertEquals(0x89.toByte(), data.bytes()[0])
        assertFalse(data.toString().contains("page.png"))
        assertFalse(AgentAttachmentMime.matches("image/jpeg", png))
        try {
            AgentAttachmentData(AttachmentId("attachment"), "image/gif", digest, png, "page.gif")
            fail("Accepted a foreign MIME type")
        } catch (_: IllegalArgumentException) {
        }
    }
}
