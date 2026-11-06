package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.*

internal object NativeAgentPreferencesCodec {
    fun encode(value: NativeAppPreferences): String = buildJsonObject {
        put("theme", value.themeMode.name); put("language", value.language.name)
        put("automatic_context_summary", value.automaticContextSummary)
        put("diagnostics_include_details", value.diagnosticsIncludeDetails); put("persona", value.appPersona)
    }.toString()
    fun fingerprint(value: NativeAppPreferences) = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encode(value)))
    fun decode(value: JsonObject): NativeAppPreferences {
        val schema = ToolParameterSchema(mapOf("theme" to ToolParameter.Text(1, 32), "language" to ToolParameter.Text(1, 32),
            "automatic_context_summary" to ToolParameter.BooleanValue(), "diagnostics_include_details" to ToolParameter.BooleanValue(),
            "persona" to ToolParameter.Text(0, 16_384)))
        val args = schema.validate(JsonObjectValue.parse(value.toString(), JsonInputLimits(128 * 1024, 4)))
        return NativeAppPreferences(NativeThemeMode.valueOf(requireNotNull(args.text("theme"))),
            requireNotNull(args.boolean("automatic_context_summary")), requireNotNull(args.boolean("diagnostics_include_details")),
            NativeAppLanguage.valueOf(requireNotNull(args.text("language"))), NativeAppPersona.requireValid(requireNotNull(args.text("persona"))))
    }
}
