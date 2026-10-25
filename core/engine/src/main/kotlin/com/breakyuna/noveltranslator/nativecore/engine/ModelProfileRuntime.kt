package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Maps stored profile facts to the engine's tri-state catalog. An absent or
 * unknown fact stays UNKNOWN and is rejected when a caller requires it.
 */
internal fun NativeModelProfile.capabilityCatalog(protocol: ModelProtocol): CapabilityCatalog =
    CapabilityCatalog.of(CapabilityModelRef(protocol, settings.model) to ModelCapabilities(
        tools = capabilities.tools.toCapability(),
        vision = capabilities.vision.toCapability(),
        reasoning = capabilities.reasoning.toCapability(),
        contextWindowTokens = capabilities.contextWindowTokens,
        maxOutputTokens = capabilities.maxOutputTokens,
    ))

private fun Boolean?.toCapability(): CapabilitySupport = when (this) {
    true -> CapabilitySupport.SUPPORTED
    false -> CapabilitySupport.UNSUPPORTED
    null -> CapabilitySupport.UNKNOWN
}

private val STRUCTURAL_BODY_KEYS = setOf(
    "model", "messages", "input", "contents", "system", "system_instruction",
    "instructions", "systemInstruction",
    "tools", "tool_choice", "stream", "stream_options", "prompt",
)

/**
 * Applies persisted generation fields and the user's extra JSON object to an
 * already built request. Structural fields are rejected instead of silently
 * overriding the protocol codec; credentials never enter a body.
 */
internal fun applyProfileRequestOverrides(body: String, profile: NativeModelProfile?,
    protocol: ModelProtocol): String {
    if (profile == null) return body
    val parsed = try { Json.parseToJsonElement(body) } catch (_: Exception) { return body }
    if (parsed !is JsonObject) return body
    val values = LinkedHashMap(parsed)
    val generation = profile.generation
    when (protocol) {
        ModelProtocol.OPENAI_CHAT_COMPLETIONS, ModelProtocol.SAKURA_LLM -> {
            generation.temperature?.let { values["temperature"] = JsonPrimitive(it) }
            generation.topP?.let { values["top_p"] = JsonPrimitive(it) }
            generation.maxOutputTokens?.let { values["max_tokens"] = JsonPrimitive(it) }
            generation.reasoningEffort?.let { values["reasoning_effort"] = JsonPrimitive(it) }
            generation.serviceTier?.let { values["service_tier"] = JsonPrimitive(it) }
        }
        ModelProtocol.OPENAI_RESPONSES -> {
            generation.temperature?.let { values["temperature"] = JsonPrimitive(it) }
            generation.topP?.let { values["top_p"] = JsonPrimitive(it) }
            generation.maxOutputTokens?.let { values["max_output_tokens"] = JsonPrimitive(it) }
            generation.reasoningEffort?.let { values["reasoning"] = buildJsonObject { put("effort", it) } }
            generation.serviceTier?.let { values["service_tier"] = JsonPrimitive(it) }
        }
        ModelProtocol.ANTHROPIC_MESSAGES -> {
            generation.temperature?.let { values["temperature"] = JsonPrimitive(it) }
            generation.topP?.let { values["top_p"] = JsonPrimitive(it) }
            generation.maxOutputTokens?.let { values["max_tokens"] = JsonPrimitive(it) }
        }
        ModelProtocol.GOOGLE_GEMINI -> {
            val existing = values["generationConfig"] as? JsonObject
            val config = LinkedHashMap(existing ?: emptyMap())
            generation.temperature?.let { config["temperature"] = JsonPrimitive(it) }
            generation.topP?.let { config["topP"] = JsonPrimitive(it) }
            generation.maxOutputTokens?.let { config["maxOutputTokens"] = JsonPrimitive(it) }
            if (config.isNotEmpty()) values["generationConfig"] = JsonObject(config)
        }
    }
    val extra = profile.extraBody ?: return JsonObject(values).toString()
    val objectValue = try { Json.parseToJsonElement(extra) as? JsonObject } catch (_: Exception) { null }
        ?: throw AgentModelFailure(ModelFailureKind.CONTENT)
    objectValue.keys.forEach {
        if (it in STRUCTURAL_BODY_KEYS) throw AgentModelFailure(ModelFailureKind.CONTENT)
    }
    objectValue.forEach { (key, value) -> values[key] = value }
    return JsonObject(values).toString()
}
