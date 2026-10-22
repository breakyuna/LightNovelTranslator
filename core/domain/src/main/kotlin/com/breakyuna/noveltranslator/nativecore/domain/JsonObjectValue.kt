package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Protocol input limits, supplied by the caller; not measured device budgets. */
data class JsonInputLimits(val maxChars: Int, val maxDepth: Int) {
    init { require(maxChars > 0 && maxDepth in 1..128) }
}

class InvalidJsonPayload(val reason: Reason) : IllegalArgumentException("Invalid JSON payload: $reason") {
    enum class Reason { SIZE, DEPTH, SYNTAX, DUPLICATE_KEY, OBJECT_REQUIRED }
}

/**
 * An independently parsed JSON object. No caller-owned mutable map is retained.
 * toString deliberately omits content; explicit JSON export can contain material.
 */
class JsonObjectValue private constructor(val json: JsonObject) {
    fun encode(): String = json.toString()
    override fun toString(): String = "JsonObjectValue(redacted)"
    override fun equals(other: Any?): Boolean = other is JsonObjectValue && json == other.json
    override fun hashCode(): Int = json.hashCode()

    companion object {
        private val parser = Json { isLenient = false }

        fun parse(text: String, limits: JsonInputLimits): JsonObjectValue {
            if (text.length > limits.maxChars) throw InvalidJsonPayload(InvalidJsonPayload.Reason.SIZE)
            checkStructure(text, limits.maxDepth)
            val element = try {
                parser.parseToJsonElement(text)
            } catch (_: SerializationException) {
                throw InvalidJsonPayload(InvalidJsonPayload.Reason.SYNTAX)
            } catch (_: IllegalArgumentException) {
                throw InvalidJsonPayload(InvalidJsonPayload.Reason.SYNTAX)
            }
            if (element !is JsonObject) throw InvalidJsonPayload(InvalidJsonPayload.Reason.OBJECT_REQUIRED)
            validatePrimitives(element)
            return JsonObjectValue(element)
        }

        private fun validatePrimitives(value: JsonElement) {
            when (value) {
                is JsonObject -> value.values.forEach(::validatePrimitives)
                is JsonArray -> value.forEach(::validatePrimitives)
                is JsonPrimitive -> if (value !== JsonNull && !value.isString &&
                    value.content != "true" && value.content != "false" &&
                    !JsonNumberSyntax.pattern.matches(value.content)
                ) throw InvalidJsonPayload(InvalidJsonPayload.Reason.SYNTAX)
            }
        }

        private class Frame(val objectFrame: Boolean) {
            var expectingKey = objectFrame
            val keys = mutableSetOf<String>()
        }

        /** Bound nesting before parser recursion and reject decoded duplicate keys. */
        private fun checkStructure(text: String, maxDepth: Int) {
            val frames = mutableListOf<Frame>()
            var cursor = 0
            while (cursor < text.length) {
                when (text[cursor]) {
                    '{', '[' -> {
                        frames.add(Frame(text[cursor] == '{'))
                        if (frames.size > maxDepth) throw InvalidJsonPayload(InvalidJsonPayload.Reason.DEPTH)
                    }
                    '}', ']' -> {
                        if (frames.isEmpty() || frames.last().objectFrame != (text[cursor] == '}')) {
                            throw InvalidJsonPayload(InvalidJsonPayload.Reason.SYNTAX)
                        }
                        frames.removeAt(frames.lastIndex)
                    }
                    ',' -> frames.lastOrNull()?.let { if (it.objectFrame) it.expectingKey = true }
                    '"' -> {
                        val begin = cursor
                        cursor += 1
                        while (cursor < text.length && text[cursor] != '"') {
                            if (text[cursor] == '\\') cursor += 1
                            cursor += 1
                        }
                        if (cursor >= text.length) throw InvalidJsonPayload(InvalidJsonPayload.Reason.SYNTAX)
                        frames.lastOrNull()?.let { frame ->
                            if (frame.objectFrame && frame.expectingKey) {
                                val key = try {
                                    (parser.parseToJsonElement(text.substring(begin, cursor + 1)) as JsonPrimitive).content
                                } catch (_: IllegalArgumentException) {
                                    throw InvalidJsonPayload(InvalidJsonPayload.Reason.SYNTAX)
                                }
                                if (!frame.keys.add(key)) throw InvalidJsonPayload(InvalidJsonPayload.Reason.DUPLICATE_KEY)
                                frame.expectingKey = false
                            }
                        }
                    }
                }
                cursor += 1
            }
            if (frames.isNotEmpty()) throw InvalidJsonPayload(InvalidJsonPayload.Reason.SYNTAX)
        }
    }
}
