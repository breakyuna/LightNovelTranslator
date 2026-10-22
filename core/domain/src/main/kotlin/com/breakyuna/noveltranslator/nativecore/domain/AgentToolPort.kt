package com.breakyuna.noveltranslator.nativecore.domain

import java.util.Collections
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class ToolParameter(val required: Boolean) {
    class Integer(val minimum: Long, val maximum: Long, required: Boolean = true) : ToolParameter(required) {
        init { require(minimum <= maximum) }
    }
    class Text(val minChars: Int, val maxChars: Int, required: Boolean = true) : ToolParameter(required) {
        init { require(minChars >= 0 && maxChars >= minChars) }
    }
    class BooleanValue(required: Boolean = true) : ToolParameter(required)
}

class InvalidToolParameters(val field: String) : IllegalArgumentException("Invalid tool parameter: $field")

/** Prototype scalar subset; nested/union schemas are not advertised as supported. */
class ToolParameterSchema(parameters: Map<String, ToolParameter>) {
    private val parameters: Map<String, ToolParameter> = Collections.unmodifiableMap(LinkedHashMap(parameters))
    init { require(this.parameters.keys.all { it.isNotBlank() && it.none { char -> char.isISOControl() } }) }

    fun definition(name: String, description: String): AgentToolDefinition {
        val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                parameters.forEach { (name, parameter) ->
                    put(name, buildJsonObject {
                        when (parameter) {
                            is ToolParameter.Integer -> {
                                put("type", "integer"); put("minimum", parameter.minimum); put("maximum", parameter.maximum)
                            }
                            is ToolParameter.Text -> {
                                put("type", "string"); put("minLength", parameter.minChars); put("maxLength", parameter.maxChars)
                            }
                            is ToolParameter.BooleanValue -> put("type", "boolean")
                        }
                    })
                }
            })
            put("required", buildJsonArray {
                parameters.filterValues { it.required }.keys.forEach { add(JsonPrimitive(it)) }
            })
        }
        // Trusted schema generated from bounded application definitions, not model input.
        val text = schema.toString()
        return AgentToolDefinition(name, description, JsonObjectValue.parse(text, JsonInputLimits(text.length, 16)))
    }

    fun validate(arguments: JsonObjectValue): ValidatedToolArguments {
        val values = linkedMapOf<String, Any>()
        if (arguments.json.keys.any { it !in parameters }) throw InvalidToolParameters("unknown_field")
        parameters.forEach { (name, parameter) ->
            val value = arguments.json[name]
            if (value == null) {
                if (parameter.required) throw InvalidToolParameters(name)
            } else {
                if (value !is JsonPrimitive || value === JsonNull) throw InvalidToolParameters(name)
                values[name] = when (parameter) {
                    is ToolParameter.Integer -> {
                        if (value.isString) throw InvalidToolParameters(name)
                        try {
                            ToolArguments.long(ToolScalar.NumberToken(value.content), name, parameter.minimum, parameter.maximum)
                        } catch (_: InvalidToolArgument) {
                            throw InvalidToolParameters(name)
                        }
                    }
                    is ToolParameter.Text -> {
                        val length = value.content.codePointCount(0, value.content.length)
                        if (!value.isString || length !in parameter.minChars..parameter.maxChars) {
                            throw InvalidToolParameters(name)
                        }
                        value.content
                    }
                    is ToolParameter.BooleanValue -> {
                        if (value.isString) throw InvalidToolParameters(name)
                        value.booleanOrNull ?: throw InvalidToolParameters(name)
                    }
                }
            }
        }
        return ValidatedToolArguments(values)
    }
}

class ValidatedToolArguments internal constructor(values: Map<String, Any>) {
    private val values = Collections.unmodifiableMap(LinkedHashMap(values))
    fun integer(name: String): Long? = values[name]?.let { it as Long }
    fun text(name: String): String? = values[name]?.let { it as String }
    fun boolean(name: String): Boolean? = values[name]?.let { it as Boolean }
    override fun toString(): String = "ValidatedToolArguments(redacted)"
}

enum class ToolEffect { READ_ONLY, PROJECT_WRITE, WORKSPACE_WRITE, PAID_REQUEST, AUTHENTICATION }
sealed interface AgentToolOutcome {
    data class Success(val result: JsonObjectValue) : AgentToolOutcome
    data class Failure(val code: String, val field: String? = null) : AgentToolOutcome
}

interface AgentToolPort {
    fun definitions(): List<AgentToolDefinition>
    suspend fun execute(run: RunLease, call: AgentToolCall): AgentToolOutcome
}
