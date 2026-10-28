package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.*

internal object NativeAgentModelProfileCodec {
    private val schema = ToolParameterSchema(mapOf(
        "name" to ToolParameter.Text(1, 128), "kind" to ToolParameter.Text(1, 64), "purpose" to ToolParameter.Text(1, 32),
        "group" to ToolParameter.Text(0, 80), "enabled" to ToolParameter.BooleanValue(),
        "endpoint" to ToolParameter.Text(1, 2048), "model" to ToolParameter.Text(1, 256), "protocol" to ToolParameter.Text(1, 64),
        "concurrency" to ToolParameter.Integer(1, 16, required = false), "requests_per_minute" to ToolParameter.Integer(0, 60_000, required = false),
        "tools" to ToolParameter.BooleanValue(required = false), "vision" to ToolParameter.BooleanValue(required = false),
        "reasoning" to ToolParameter.BooleanValue(required = false),
        "context_tokens" to ToolParameter.Integer(1, 10_000_000, required = false), "output_tokens" to ToolParameter.Integer(1, 10_000_000, required = false),
        "temperature" to ToolParameter.Text(1, 64, required = false), "top_p" to ToolParameter.Text(1, 64, required = false),
        "generation_output_tokens" to ToolParameter.Integer(1, 10_000_000, required = false),
        "reasoning_effort" to ToolParameter.Text(1, 64, required = false), "service_tier" to ToolParameter.Text(1, 64, required = false)))
    fun decode(value: JsonObject): NewModelProfile {
        val args = schema.validate(JsonObjectValue.parse(value.toString(), JsonInputLimits(32 * 1024, 4)))
        fun text(key: String) = requireNotNull(args.text(key))
        val concurrency = args.integer("concurrency"); val rpm = args.integer("requests_per_minute")
        require((concurrency == null) == (rpm == null))
        return NewModelProfile(text("name"), ModelProfileKind.valueOf(text("kind")), ModelProfilePurpose.valueOf(text("purpose")),
            text("group"), requireNotNull(args.boolean("enabled")), text("endpoint"), text("model"),
            NativeModelProtocol.valueOf(text("protocol")), emptyList(),
            limits = concurrency?.let { ModelProfileLimits(it.toInt(), requireNotNull(rpm).toInt()) },
            capabilities = ModelProfileCapabilities(args.boolean("tools"), args.boolean("vision"), args.boolean("reasoning"),
                args.integer("context_tokens")?.toInt(), args.integer("output_tokens")?.toInt()),
            generation = ModelGenerationSettings(args.text("temperature")?.toBigDecimal()?.toDouble(),
                args.text("top_p")?.toBigDecimal()?.toDouble(), args.integer("generation_output_tokens")?.toInt(),
                args.text("reasoning_effort"), args.text("service_tier"))).also { NativeModelProfileValidation.validate(it) }
    }
    fun encode(profile: NativeModelProfile): JsonObject = buildJsonObject {
        put("name", profile.name); put("kind", profile.kind.name); put("purpose", profile.purpose.name)
        put("group", profile.group); put("enabled", profile.enabled); put("endpoint", profile.settings.endpoint)
        put("model", profile.settings.model); put("protocol", profile.settings.protocol.name)
        profile.limits?.let { put("concurrency", it.concurrency); put("requests_per_minute", it.requestsPerMinute) }
        profile.capabilities.let { value ->
            value.tools?.let { put("tools", it) }; value.vision?.let { put("vision", it) }; value.reasoning?.let { put("reasoning", it) }
            value.contextWindowTokens?.let { put("context_tokens", it) }; value.maxOutputTokens?.let { put("output_tokens", it) }
        }
        profile.generation.let { value ->
            value.temperature?.let { put("temperature", it.toString()) }; value.topP?.let { put("top_p", it.toString()) }
            value.maxOutputTokens?.let { put("generation_output_tokens", it) }; value.reasoningEffort?.let { put("reasoning_effort", it) }
            value.serviceTier?.let { put("service_tier", it) }
        }
    }
}
