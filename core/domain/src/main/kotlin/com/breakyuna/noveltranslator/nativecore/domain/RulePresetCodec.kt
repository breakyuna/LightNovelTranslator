package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

/** Native preset envelope. It never claims compatibility with LG preset files. */
object RulePresetCodec {
    const val MAX_CHARS = 80_000
    fun encode(preset: RulePreset): String = buildJsonObject {
        put("format", "lnt-rule-preset"); put("version", 1)
        put("id", preset.id); put("name", preset.name); put("description", preset.description)
        put("exchange", JsonObjectValue.parse(RuleExchangeCodec.encode(preset.toExchange()), JsonInputLimits(65_536, 8)).json)
    }.toString().also { require(it.length <= MAX_CHARS) }

    fun fingerprint(preset: RulePreset): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encode(preset)))

    fun decode(text: String): RulePreset {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 10)).json
        require(root.keys == setOf("format", "version", "id", "name", "description", "exchange"))
        require(root["format"] == JsonPrimitive("lnt-rule-preset") && root["version"] == JsonPrimitive(1))
        fun string(key: String): String {
            val value = root.getValue(key) as JsonPrimitive
            require(value.isString); return value.content
        }
        return RulePreset.fromExchange(id = string("id"), name = string("name"), description = string("description"),
            exchange = RuleExchangeCodec.decode(root.getValue("exchange").toString()))
    }
}
