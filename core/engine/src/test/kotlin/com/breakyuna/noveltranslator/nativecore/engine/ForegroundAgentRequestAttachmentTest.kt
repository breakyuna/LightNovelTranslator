package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

/** Request contract: existing text callers keep the default, image-only is explicit. */
class ForegroundAgentRequestAttachmentTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private fun request(input: String, ids: List<AttachmentId> = emptyList()) = ForegroundAgentRequest(ref,
        RunIdentity(ref.projectId, ChatId("chat"), RunId("run"), CommandId("command")),
        ModelConfigurationRef("fixture", 1), input, ids)

    @Test fun textOnlyRequestKeepsTheLegacyDefault() {
        val value = request("translate")
        assertTrue(value.attachmentIds.isEmpty())
        assertFalse(value.toString().contains("translate"))
    }

    @Test fun imageOnlyRequestIsValidAndStillBounded() {
        assertTrue(request("", listOf(AttachmentId("a"))).attachmentIds.isNotEmpty())
        try {
            request("")
            fail("Accepted a user turn with neither text nor images")
        } catch (_: IllegalArgumentException) {
        }
    }
}
