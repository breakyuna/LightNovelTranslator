package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit fixed limits. Zero RPM uses a concurrency-sized, one-second token bucket. */
data class ModelRequestLimits(val concurrency: Int = 4, val requestsPerMinute: Int = 0) {
    init {
        require(concurrency in 1..16)
        require(requestsPerMinute in 0..60_000)
    }
}

/** Optional persisted defaults. A failed read must not invent a larger allowance. */
interface ModelRequestLimitsProvider {
    suspend fun current(): ModelRequestLimits
}

/** Versioned non-secret encoding; no per-configuration or per-model data is stored. */
object ModelRequestLimitsCodec {
    const val FORMAT = "lnt-model-request-limits"
    const val FORMAT_VERSION = 1
    const val MAX_CHARS = 1_024

    private val ROOT_KEYS = setOf("format", "formatVersion", "concurrency", "requestsPerMinute")
    private val INTEGER = Regex("0|[1-9][0-9]*")

    fun encode(limits: ModelRequestLimits): String {
        val encoded = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("concurrency", limits.concurrency)
            put("requestsPerMinute", limits.requestsPerMinute)
        }.toString()
        require(encoded.length <= MAX_CHARS) { "Request limits encoding exceeds limit" }
        return encoded
    }

    /** SHA-256 of [encode]; lower-case hexadecimal. */
    fun fingerprint(limits: ModelRequestLimits): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encode(limits)))

    /** Strict inverse of [encode]; unknown or ill-typed fields are rejected. */
    fun decode(text: String): ModelRequestLimits {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 4)).json
        require(root.keys == ROOT_KEYS) { "Invalid request limits fields" }
        require(string(root, "format") == FORMAT) { "Unsupported request limits format" }
        require(integer(root, "formatVersion") == FORMAT_VERSION.toLong()) { "Unsupported request limits encoding" }
        return ModelRequestLimits(
            concurrency = integer(root, "concurrency", 1L, 16L).toInt(),
            requestsPerMinute = integer(root, "requestsPerMinute", 0L, 60_000L).toInt(),
        )
    }

    private fun integer(value: JsonObject, key: String, minimum: Long = 0, maximum: Long = Long.MAX_VALUE): Long {
        val primitive = value.getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid request limits field")
        require(!primitive.isString && INTEGER.matches(primitive.content)) { "Request limit must be an integer" }
        val parsed = primitive.content.toLongOrNull() ?: throw IllegalArgumentException("Invalid request limits field")
        require(parsed in minimum..maximum) { "Request limit out of range" }
        return parsed
    }

    private fun string(value: JsonObject, key: String): String {
        val primitive = value.getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid request limits field")
        require(primitive.isString) { "Request limits string field must be a string" }
        return primitive.content
    }
}
