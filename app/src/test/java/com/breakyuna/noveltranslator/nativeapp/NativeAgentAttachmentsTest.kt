package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.AgentAttachmentLimits
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

/** Pure picker/send gates; no Activity, SAF provider or device is required. */
class NativeAgentAttachmentsTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)

    @Test fun boundedReadRejectsOversizedInput() {
        val oversized = ByteArray(AgentAttachmentLimits.MAX_IMAGE_BYTES + 1)
        try {
            readBoundedAttachment(ByteArrayInputStream(oversized))
            fail("Buffered an oversized image")
        } catch (failure: NativeAttachmentReadException) {
            assertEquals(NativeAgentAttachmentFailureReason.TOO_LARGE, failure.reason)
        }
    }

    @Test fun boundedReadKeepsExactPayload() {
        assertArrayEquals(png, readBoundedAttachment(ByteArrayInputStream(png)))
        assertArrayEquals(ByteArray(0), readBoundedAttachment(ByteArrayInputStream(ByteArray(0))))
    }

    @Test fun pickerRejectsUnsupportedOrMisleadingTypes() {
        assertEquals("image/png", resolvePickedAttachmentMime("image/png", png))
        assertEquals("image/png", resolvePickedAttachmentMime("image/*", png))
        assertEquals("image/png", resolvePickedAttachmentMime(null, png))
        try {
            resolvePickedAttachmentMime("image/png", "GIF89a".toByteArray())
            fail("Accepted non-image bytes")
        } catch (failure: NativeAttachmentReadException) {
            assertEquals(NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE, failure.reason)
        }
        try {
            resolvePickedAttachmentMime("image/jpeg", png)
            fail("Accepted a mismatched MIME hint")
        } catch (failure: NativeAttachmentReadException) {
            assertEquals(NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE, failure.reason)
        }
    }

    @Test fun imageOnlyMessageCanBeSentButEmptyMessageCannot() {
        assertTrue(canSendAgentMessage(ready = true, hasApproval = false, text = "", attachmentCount = 1))
        assertTrue(canSendAgentMessage(ready = true, hasApproval = false, text = "text", attachmentCount = 0))
        assertFalse(canSendAgentMessage(ready = true, hasApproval = false, text = "", attachmentCount = 0))
        assertFalse(canSendAgentMessage(ready = false, hasApproval = false, text = "text", attachmentCount = 0))
        assertFalse(canSendAgentMessage(ready = true, hasApproval = true, text = "", attachmentCount = 1))
        assertFalse(canSendAgentMessage(ready = true, hasApproval = false,
            text = "x".repeat(8001), attachmentCount = 1))
    }

    @Test fun attachmentAdmissionEnforcesCountAndTotalBudget() {
        assertNull(agentAttachmentAdmissionFailure(0, 0, 128))
        assertEquals(NativeAgentAttachmentFailureReason.LIMIT,
            agentAttachmentAdmissionFailure(AgentAttachmentLimits.MAX_PER_MESSAGE, 0, 128))
        assertEquals(NativeAgentAttachmentFailureReason.LIMIT,
            agentAttachmentAdmissionFailure(1, AgentAttachmentLimits.MAX_TOTAL_MESSAGE_BYTES.toLong() - 1, 2))
        assertEquals(NativeAgentAttachmentFailureReason.UNSUPPORTED_TYPE,
            agentAttachmentAdmissionFailure(0, 0, 0))
    }

    @Test fun sizeLabelsStayBoundedAndReadable() {
        assertEquals("512 B", formatAgentAttachmentSize(512))
        assertEquals("1.0 KiB", formatAgentAttachmentSize(1024))
        assertEquals("1.0 MiB", formatAgentAttachmentSize(1_048_576))
    }
}
