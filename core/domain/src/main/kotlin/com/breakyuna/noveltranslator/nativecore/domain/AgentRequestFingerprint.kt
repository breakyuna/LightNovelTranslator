package com.breakyuna.noveltranslator.nativecore.domain

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared by storage and Agent adapters; never include credentials in model refs. */
object AgentRequestFingerprint {
    const val FORMAT_VERSION = 1
    /** Preserve the existing app-store key encoding; shared with run-receipt reconciliation. */
    fun key(key: AgentRequestKey): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
        put("project", key.run.projectId.value); put("chat", key.run.chatId?.value); put("run", key.run.runId.value)
        put("command", key.run.commandId.value); put("branch", key.branchId.value); put("attempt", key.attemptId.value)
    }.toString()))
    fun sha256(request: AgentModelRequest): String {
        val encoded = buildJsonObject {
            put("version", FORMAT_VERSION)
            put("project", request.run.projectId.value)
            put("chat", request.run.chatId?.let { JsonPrimitive(it.value) } ?: JsonNull)
            put("run", request.run.runId.value)
            put("command", request.run.commandId.value)
            put("branch", request.branchId.value)
            put("attempt", request.attemptId.value)
            put("model", request.configuration.id)
            put("model_revision", request.configuration.revision)
            put("history", JsonArray(request.history.map(::message)))
            put("tools", JsonArray(request.tools.map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("schema", tool.inputSchema.json)
                }
            }))
        }
        validateUnicode(encoded)
        val bytes = sorted(encoded).toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun continuation(value: ProviderContinuation?): JsonElement = if (value == null) JsonNull else
        buildJsonObject { put("protocol", value.protocol); put("data", value.data.json) }

    private fun message(value: AgentMessage): JsonElement = buildJsonObject {
        put("id", value.id.value)
        when (value) {
            is AgentMessage.System -> { put("role", "system"); put("text", value.text) }
            is AgentMessage.User -> {
                put("role", "user")
                put("parts", JsonArray(value.parts.map { part ->
                    buildJsonObject {
                        when (part) {
                            is UserPart.Text -> { put("type", "text"); put("text", part.text) }
                            is UserPart.Image -> {
                                put("type", "image")
                                put("attachment", part.image.attachmentId.value)
                                put("mime", part.image.mimeType)
                                put("sha256", part.image.sha256)
                            }
                        }
                    }
                }))
            }
            is AgentMessage.Assistant -> {
                put("role", "assistant")
                put("continuation", continuation(value.continuation))
                put("parts", JsonArray(value.parts.map { part ->
                    buildJsonObject {
                        when (part) {
                            is AssistantPart.Text -> { put("type", "text"); put("text", part.text) }
                            is AssistantPart.Thinking -> {
                                put("type", "thinking")
                                put("text", part.text?.let(::JsonPrimitive) ?: JsonNull)
                                put("continuation", continuation(part.continuation))
                            }
                            is AssistantPart.ToolCall -> {
                                put("type", "tool_call")
                                put("id", part.call.id.value)
                                put("provider_call_id", part.call.providerCallId?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("name", part.call.name)
                                put("arguments", part.call.arguments.json)
                            }
                        }
                    }
                }))
            }
            is AgentMessage.ToolResult -> {
                put("role", "tool_result")
                put("call", value.callId.value)
                put("name", value.toolName)
                put("result", value.result.json)
                put("is_error", value.isError)
            }
        }
    }

    // UTF-8 encoding replaces isolated UTF-16 surrogates by default. Reject them
    // before hashing so two distinct invalid strings cannot share a fingerprint.
    private fun validateUnicode(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                value.keys.forEach(::validateText)
                value.values.forEach(::validateUnicode)
            }
            is JsonArray -> value.forEach(::validateUnicode)
            is JsonPrimitive -> if (value.isString) validateText(value.content)
        }
    }

    private fun validateText(value: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char.isHighSurrogate()) {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                    "Request contains invalid Unicode"
                }
                index += 2
            } else {
                require(!char.isLowSurrogate()) { "Request contains invalid Unicode" }
                index += 1
            }
        }
    }

    /** Object order is irrelevant; array/part order and numeric lexemes stay exact. */
    private fun sorted(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(value.map(::sorted))
        is JsonPrimitive -> value
    }
}
