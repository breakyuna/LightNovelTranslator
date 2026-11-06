package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

/** Strict native template-library exchange; it is independent of project rendered prompts. */
object PromptTemplateCodec {
    const val MAX_CHARS = 2_000_000
    fun encode(templates: PromptTemplateSet): String = buildJsonObject {
        put("format", "lnt-prompt-templates"); put("version", 1)
        put("templates", JsonArray(templates.ordered().map { template -> buildJsonObject {
            put("id", template.id); put("name", template.name); put("description", template.description)
            put("enabled", template.enabled); put("order", template.order); put("body", template.body)
            put("languages", JsonArray(template.languages.map { JsonPrimitive(it.value) }))
        } }))
    }.toString().also { require(it.length <= MAX_CHARS) }
    fun decode(text: String): PromptTemplateSet {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 8)).json
        require(root.keys == setOf("format", "version", "templates"))
        require(root["format"] == JsonPrimitive("lnt-prompt-templates") && root["version"] == JsonPrimitive(1))
        val entries = root.getValue("templates") as JsonArray
        require(entries.size <= PromptTemplateSet.MAX_TEMPLATES)
        return PromptTemplateSet(entries.map { element ->
            val entry = element as JsonObject
            require(entry.keys == setOf("id", "name", "description", "enabled", "order", "body", "languages"))
            fun string(key: String): String = (entry.getValue(key) as JsonPrimitive).also { require(it.isString) }.content
            val enabled = (entry.getValue("enabled") as JsonPrimitive).also { require(!it.isString) }.booleanOrNull
                ?: throw IllegalArgumentException("Template enabled type")
            val order = (entry.getValue("order") as JsonPrimitive).also { require(!it.isString) }.content
            val parsedOrder = ToolArguments.long(ToolScalar.NumberToken(order), "order", 0, PromptTemplate.MAX_ORDER.toLong()).toInt()
            val languages = entry.getValue("languages") as JsonArray
            require(languages.size <= PromptTemplate.MAX_LANGUAGE_TAGS)
            PromptTemplate(string("id"), string("name"), string("description"), enabled,
                languages.map { PromptLanguageTag((it as JsonPrimitive).also { value -> require(value.isString) }.content) },
                parsedOrder, string("body"))
        })
    }
    fun fingerprint(templates: PromptTemplateSet): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encode(templates)))
}

class PromptTemplateLibrarySnapshot(val templates: PromptTemplateSet, val fingerprint: String)
