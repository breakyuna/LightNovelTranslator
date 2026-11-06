package com.breakyuna.noveltranslator.nativecore.domain

import java.math.BigDecimal

/** Preserve the original JSON numeric token; decoding via Double loses identity. */
sealed interface ToolScalar {
    data class NumberToken(val lexeme: String) : ToolScalar {
        override fun toString(): String = "NumberToken(redacted)"
    }
    data class Text(val value: String) : ToolScalar {
        override fun toString(): String = "Text(redacted)"
    }
    data class BooleanValue(val value: Boolean) : ToolScalar
    data object NullValue : ToolScalar
}

/** Diagnostics identify the field and contract only, never echo untrusted values. */
class InvalidToolArgument(val field: String, val reason: Reason) : IllegalArgumentException(
    "Invalid tool argument: $field ($reason)",
) {
    enum class Reason { MISSING, TYPE, INTEGER, RANGE }
}

internal object JsonNumberSyntax {
    val pattern = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")
}

object ToolArguments {
    /**
     * Accept integral JSON numbers, including 1.0 / 1e0, without truncation or
     * overflow. Strings and booleans are never coerced to paid/write parameters.
     * An adapter must pass a numeric token directly from its JSON parser.
     */
    fun long(
        value: ToolScalar?,
        field: String,
        minimum: Long,
        maximum: Long,
    ): Long {
        require(minimum <= maximum) { "Invalid argument bounds" }
        if (value == null) throw InvalidToolArgument(field, InvalidToolArgument.Reason.MISSING)
        if (value !is ToolScalar.NumberToken) {
            throw InvalidToolArgument(field, InvalidToolArgument.Reason.TYPE)
        }
        if (!JsonNumberSyntax.pattern.matches(value.lexeme)) {
            throw InvalidToolArgument(field, InvalidToolArgument.Reason.TYPE)
        }
        val decimal = try {
            BigDecimal(value.lexeme)
        } catch (_: NumberFormatException) {
            throw InvalidToolArgument(field, InvalidToolArgument.Reason.RANGE)
        }
        if (decimal < BigDecimal.valueOf(minimum) || decimal > BigDecimal.valueOf(maximum)) {
            throw InvalidToolArgument(field, InvalidToolArgument.Reason.RANGE)
        }
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            // Bounds already fit Long; an exact conversion can only fail for a fraction.
            throw InvalidToolArgument(field, InvalidToolArgument.Reason.INTEGER)
        }
    }

    fun int(value: ToolScalar?, field: String, minimum: Int, maximum: Int): Int =
        long(value, field, minimum.toLong(), maximum.toLong()).toInt()
}
