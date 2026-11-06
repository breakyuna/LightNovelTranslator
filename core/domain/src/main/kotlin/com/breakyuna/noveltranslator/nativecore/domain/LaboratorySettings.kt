package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Application-scoped laboratory switches that have a real next-run consumer.
 * A switch is never persisted here unless the translation pipeline actually reads
 * it; MTool has no baseline parser in this build and therefore has no toggle.
 */
data class LaboratorySettings(
    val cleanRuby: Boolean = false,
    val promptEnhancement: Boolean = false,
    val duplicateSourceDeduplication: Boolean = false,
) {
    fun saved(): LaboratorySettings = copy()

    companion object {
        /** Baseline used when no saved override exists or the file is unreadable. */
        val DEFAULT = LaboratorySettings()
    }
}

interface LaboratorySettingsProvider {
    suspend fun current(): LaboratorySettings
}

class InvalidLaboratorySettings : IllegalArgumentException("Invalid laboratory settings")

/**
 * Strict, credential-free envelope. Unknown fields are rejected so a future
 * experimental secret cannot be written into this file by accident.
 */
object LaboratorySettingsCodec {
    const val FORMAT = "lnt-laboratory-settings"
    const val FORMAT_VERSION = 1
    const val MAX_CHARS = 8_192

    private val ROOT_KEYS = setOf(
        "format", "formatVersion", "cleanRuby", "promptEnhancement", "duplicateSourceDeduplication",
    )

    fun encode(settings: LaboratorySettings): String {
        val encoded = buildString {
            append('{')
            append("\"format\":").append(quote(FORMAT)).append(',')
            append("\"formatVersion\":").append(FORMAT_VERSION).append(',')
            append("\"cleanRuby\":").append(settings.cleanRuby).append(',')
            append("\"promptEnhancement\":").append(settings.promptEnhancement).append(',')
            append("\"duplicateSourceDeduplication\":").append(settings.duplicateSourceDeduplication)
            append('}')
        }
        if (encoded.length > MAX_CHARS) throw InvalidLaboratorySettings()
        return encoded
    }

    fun fingerprint(settings: LaboratorySettings): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encode(settings)))

    fun decode(text: String): LaboratorySettings {
        if (text.length > MAX_CHARS) throw InvalidLaboratorySettings()
        val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            ?: throw InvalidLaboratorySettings()
        if (root.keys != ROOT_KEYS) throw InvalidLaboratorySettings()
        if (string(root, "format") != FORMAT || integer(root, "formatVersion") != FORMAT_VERSION)
            throw InvalidLaboratorySettings()
        return LaboratorySettings(
            cleanRuby = boolean(root, "cleanRuby"),
            promptEnhancement = boolean(root, "promptEnhancement"),
            duplicateSourceDeduplication = boolean(root, "duplicateSourceDeduplication"),
        )
    }

    private fun string(root: JsonObject, key: String): String {
        val value = root[key] as? JsonPrimitive ?: throw InvalidLaboratorySettings()
        if (!value.isString) throw InvalidLaboratorySettings()
        return value.content
    }

    private fun integer(root: JsonObject, key: String): Int {
        val item = root[key] as? JsonPrimitive ?: throw InvalidLaboratorySettings()
        if (item.isString) throw InvalidLaboratorySettings()
        val value = item.content
        if (value.isEmpty() || value.length > 9 || value.any { it !in '0'..'9' }) throw InvalidLaboratorySettings()
        return value.toIntOrNull() ?: throw InvalidLaboratorySettings()
    }

    private fun boolean(root: JsonObject, key: String): Boolean {
        val item = root[key] as? JsonPrimitive ?: throw InvalidLaboratorySettings()
        if (item.isString) throw InvalidLaboratorySettings()
        return item.booleanOrNull ?: throw InvalidLaboratorySettings()
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(character)
            }
        }
        append('"')
    }
}
