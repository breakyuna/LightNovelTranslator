package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentRequestFingerprintTest {
    private val run = RunIdentity(ProjectId("p"), ChatId("c"), RunId("r"), CommandId("command"))

    @Test
    fun objectOrderIsCanonicalButNumericIdentityStaysExact() {
        assertEquals(digest("""{"a":1,"b":9007199254740993}"""), digest("""{"b":9007199254740993,"a":1}"""))
        assertNotEquals(digest("""{"id":9007199254740993}"""), digest("""{"id":9007199254740992}"""))
    }

    @Test
    fun modelRevisionAndAssistantContinuationParticipateInTheFingerprint() {
        val parts = listOf(AssistantPart.Text("answer"))
        val plain = AgentMessage.Assistant(MessageId("a"), parts)
        val signed = AgentMessage.Assistant(MessageId("a"), parts,
            ProviderContinuation("synthetic", parse("""{"signature":"token"}""")))
        assertNotEquals(hash(listOf(plain), 1), hash(listOf(plain), 2))
        assertNotEquals(hash(listOf(plain), 1), hash(listOf(signed), 1))
    }

    @Test
    fun imageArtifactAndPartOrderParticipateInTheFingerprint() {
        val first = FrozenImageRef(AttachmentId("image"), "image/png", "a".repeat(64))
        val second = first.copy(sha256 = "b".repeat(64))
        val text = UserPart.Text("synthetic")
        fun imageHash(image: FrozenImageRef, reverse: Boolean = false): String {
            val parts = listOf(text, UserPart.Image(image))
            return hash(listOf(AgentMessage.User(MessageId("u"), if (reverse) parts.reversed() else parts)), 1)
        }
        assertNotEquals(imageHash(first), imageHash(second))
        assertNotEquals(imageHash(first), imageHash(first, reverse = true))
    }

    @Test
    fun invalidUnicodeCannotCollapseIntoTheSameUtf8Fingerprint() {
        for (text in listOf("\uD800", "\uD801", "\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) {
                hash(listOf(AgentMessage.User(MessageId("u"), listOf(UserPart.Text(text)))), 1)
            }
        }
    }

    @Test
    fun unknownUsageIsDistinctFromActualZero() {
        TokenMeasurement(null, UsageSource.UNKNOWN)
        TokenMeasurement(0, UsageSource.ACTUAL)
        assertThrows(IllegalArgumentException::class.java) { TokenMeasurement(null, UsageSource.ACTUAL) }
        assertThrows(IllegalArgumentException::class.java) { TokenMeasurement(0, UsageSource.UNKNOWN) }
    }

    private fun digest(schema: String): String = AgentRequestFingerprint.sha256(AgentModelRequest(
        run, BranchId("main"), RequestAttemptId("attempt"), ModelConfigurationRef("model", 1),
        listOf(AgentMessage.User(MessageId("u"), listOf(UserPart.Text("synthetic")))),
        listOf(AgentToolDefinition("lookup", "synthetic", parse(schema))),
    ))
    private fun hash(history: List<AgentMessage>, revision: Long) = AgentRequestFingerprint.sha256(
        AgentModelRequest(run, BranchId("main"), RequestAttemptId("attempt"), ModelConfigurationRef("model", revision), history, emptyList()),
    )
    private fun parse(text: String) = JsonObjectValue.parse(text, JsonInputLimits(4096, 16))
}
