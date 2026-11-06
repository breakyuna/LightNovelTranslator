package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class AgentMessageCodecTest {
    @Test fun preservesThinkingContinuationAndProviderToolIdentity() {
        val continuation = ProviderContinuation("vendor", JsonObjectValue.parse("{\"opaque\":\"value\"}", JsonInputLimits(4096, 16)))
        val message = AgentMessage.Assistant(MessageId("assistant"), listOf(AssistantPart.Text("text"),
            AssistantPart.Thinking("thought", continuation), AssistantPart.ToolCall(AgentToolCall(ToolCallId("local"), "read_item",
                JsonObjectValue.parse("{\"item_id\":1}", JsonInputLimits(4096, 16)), "provider"))), continuation)
        val restored = AgentMessageCodec.decode(AgentMessageCodec.encode(message)) as AgentMessage.Assistant
        assertEquals(continuation, restored.continuation)
        assertEquals(continuation, (restored.parts[1] as AssistantPart.Thinking).continuation)
        assertEquals("provider", (restored.parts[2] as AssistantPart.ToolCall).call.providerCallId)
        assertEquals(ToolCallId("local"), (restored.parts[2] as AssistantPart.ToolCall).call.id)
    }
    @Test fun preservesNativeToolResultWithoutConvertingItToUserText() {
        val message = AgentMessage.ToolResult(MessageId("result"), ToolCallId("call"), "read_item", JsonObjectValue.parse("{\"ok\":true}", JsonInputLimits(4096, 16)), false)
        val restored = AgentMessageCodec.decode(AgentMessageCodec.encode(message)) as AgentMessage.ToolResult
        assertEquals(message.callId, restored.callId); assertEquals(message.result, restored.result); assertFalse(restored.isError)
    }
    @Test fun preservesFrozenImageIdentity() {
        val image = FrozenImageRef(AttachmentId("attachment"), "image/png", "a".repeat(64))
        val message = AgentMessage.User(MessageId("user"), listOf(UserPart.Text("text"), UserPart.Image(image)))
        val restored = AgentMessageCodec.decode(AgentMessageCodec.encode(message)) as AgentMessage.User
        assertEquals(image, (restored.parts[1] as UserPart.Image).image)
    }
    @Test fun rejectsUnsupportedEnvelopeVersion() {
        try { AgentMessageCodec.decode("{\"version\":2,\"id\":\"x\",\"role\":\"system\",\"text\":\"x\"}"); fail("Accepted unknown codec") }
        catch (_: IllegalArgumentException) { }
    }
}
