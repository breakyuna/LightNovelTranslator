package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

/** Versioned lossless native message envelope; never flatten tools/thinking into user text. */
object AgentMessageCodec {
    private fun continuation(value: ProviderContinuation?): JsonElement = value?.let {
        buildJsonObject { put("protocol", it.protocol); put("data", it.data.json) }
    } ?: JsonNull
    private fun continuation(value: JsonElement?): ProviderContinuation? = if (value == null || value == JsonNull) null else
        value.jsonObject.let { ProviderContinuation(it.getValue("protocol").jsonPrimitive.content,
            JsonObjectValue.parse(it.getValue("data").toString(), JsonInputLimits(2 * 1024 * 1024, 64))) }

    fun encode(message: AgentMessage): String = buildJsonObject {
        put("version", 1); put("id", message.id.value)
        when (message) {
            is AgentMessage.System -> { put("role", "system"); put("text", message.text) }
            is AgentMessage.User -> {
                put("role", "user"); put("parts", JsonArray(message.parts.map { part -> buildJsonObject {
                    when (part) {
                        is UserPart.Text -> { put("type", "text"); put("text", part.text) }
                        is UserPart.Image -> {
                            put("type", "image"); put("attachment", part.image.attachmentId.value)
                            put("mime", part.image.mimeType); put("sha256", part.image.sha256)
                        }
                    }
                } }))
            }
            is AgentMessage.Assistant -> {
                put("role", "assistant"); put("continuation", continuation(message.continuation))
                put("parts", JsonArray(message.parts.map { part -> buildJsonObject {
                    when (part) {
                        is AssistantPart.Text -> { put("type", "text"); put("text", part.text) }
                        is AssistantPart.Thinking -> {
                            put("type", "thinking"); put("text", part.text?.let(::JsonPrimitive) ?: JsonNull)
                            put("continuation", continuation(part.continuation))
                        }
                        is AssistantPart.ToolCall -> {
                            put("type", "tool_call"); put("id", part.call.id.value); put("name", part.call.name)
                            put("arguments", part.call.arguments.json)
                            put("provider_id", part.call.providerCallId?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    }
                } }))
            }
            is AgentMessage.ToolResult -> {
                put("role", "tool"); put("call", message.callId.value); put("name", message.toolName)
                put("result", message.result.json); put("error", message.isError)
            }
        }
    }.also(::checkUnicode).toString().also { require(it.length <= 2 * 1024 * 1024); TextProjectFingerprint.utf8(it) }

    private fun checkUnicode(value: JsonElement) {
        when (value) {
            is JsonObject -> value.forEach { (key, child) -> TextProjectFingerprint.utf8(key); checkUnicode(child) }
            is JsonArray -> value.forEach(::checkUnicode)
            is JsonPrimitive -> if (value.isString) TextProjectFingerprint.utf8(value.content)
        }
    }

    fun decode(text: String): AgentMessage {
        val obj = JsonObjectValue.parse(text, JsonInputLimits(2 * 1024 * 1024, 64)).json
        checkUnicode(obj)
        require(obj.getValue("version").jsonPrimitive.content == "1")
        val id = MessageId(obj.getValue("id").jsonPrimitive.content)
        return when (obj.getValue("role").jsonPrimitive.content) {
            "system" -> AgentMessage.System(id, obj.getValue("text").jsonPrimitive.content)
            "user" -> AgentMessage.User(id, obj.getValue("parts").jsonArray.map { element ->
                val part = element.jsonObject
                when (part.getValue("type").jsonPrimitive.content) {
                    "text" -> UserPart.Text(part.getValue("text").jsonPrimitive.content)
                    "image" -> UserPart.Image(FrozenImageRef(AttachmentId(part.getValue("attachment").jsonPrimitive.content),
                        part.getValue("mime").jsonPrimitive.content, part.getValue("sha256").jsonPrimitive.content))
                    else -> error("Unsupported user part")
                }
            })
            "assistant" -> AgentMessage.Assistant(id, obj.getValue("parts").jsonArray.map { element ->
                val part = element.jsonObject
                when (part.getValue("type").jsonPrimitive.content) {
                    "text" -> AssistantPart.Text(part.getValue("text").jsonPrimitive.content)
                    "thinking" -> AssistantPart.Thinking(part["text"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                        continuation(part["continuation"]))
                    "tool_call" -> AssistantPart.ToolCall(AgentToolCall(ToolCallId(part.getValue("id").jsonPrimitive.content),
                        part.getValue("name").jsonPrimitive.content,
                        JsonObjectValue.parse(part.getValue("arguments").toString(), JsonInputLimits(2 * 1024 * 1024, 64)),
                        part["provider_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content))
                    else -> error("Unsupported assistant part")
                }
            }, continuation(obj["continuation"]))
            "tool" -> AgentMessage.ToolResult(id, ToolCallId(obj.getValue("call").jsonPrimitive.content),
                obj.getValue("name").jsonPrimitive.content,
                JsonObjectValue.parse(obj.getValue("result").toString(), JsonInputLimits(2 * 1024 * 1024, 64)),
                obj.getValue("error").jsonPrimitive.boolean)
            else -> error("Unsupported message role")
        }
    }
}

interface AgentConversationPort {
    suspend fun modelHistory(ref: LocalProjectRef, chat: ChatId): List<AgentMessage> = history(ref, chat)
    suspend fun history(ref: LocalProjectRef, chat: ChatId): List<AgentMessage>
    suspend fun appendMessage(ref: LocalProjectRef, chat: ChatId, message: AgentMessage)
}
