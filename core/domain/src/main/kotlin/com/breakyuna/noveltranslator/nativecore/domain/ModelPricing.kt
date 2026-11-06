package com.breakyuna.noveltranslator.nativecore.domain

import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Currency identity for a price table. Only upper-case ASCII letters and digits are
 * accepted; values are never lower-cased silently because that would make differently
 * spelled persisted tables collapse to the same binding.
 */
data class CurrencyCode private constructor(val code: String) {
    init {
        require(code.length in MIN_LENGTH..MAX_LENGTH) { "Invalid currency code length" }
        require(code.all { it in 'A'..'Z' || it in '0'..'9' }) { "Invalid currency code" }
    }

    companion object {
        const val MIN_LENGTH = 3
        const val MAX_LENGTH = 8

        fun of(code: String): CurrencyCode {
            require(code.length in MIN_LENGTH..MAX_LENGTH) { "Invalid currency code length" }
            require(code.all { it in 'A'..'Z' || it in '0'..'9' }) { "Invalid currency code" }
            return CurrencyCode(code)
        }

        val USD = CurrencyCode("USD")
    }
}

/**
 * Integer price in micro-units of [CurrencyCode] per one million tokens.
 *
 * One micro-unit is `1 / 1_000_000` of the currency unit. Keeping the per-million
 * price as an integer avoids binary floating point and makes the canonical encoding
 * exact. The associated currency lives on [PriceTable] so a single table cannot mix
 * currencies.
 *
 * A cost can be smaller than one micro-unit. Cost arithmetic therefore uses the
 * exact twelve-decimal [CostAmount] representation rather than rounding a
 * per-component micro-unit amount.
 */
@JvmInline
value class TokenPrice private constructor(val microsPerMillionTokens: BigInteger) : Comparable<TokenPrice> {
    init {
        require(microsPerMillionTokens.signum() >= 0) { "Token price cannot be negative" }
        require(microsPerMillionTokens <= MAX_MICROS_PER_MILLION) { "Token price exceeds limit" }
    }

    val isZero: Boolean get() = microsPerMillionTokens.signum() == 0

    /** Exact decimal currency amount per one million tokens. */
    fun currencyPerMillion(): BigDecimal = BigDecimal(microsPerMillionTokens, MICROS_SCALE)

    override fun compareTo(other: TokenPrice): Int = microsPerMillionTokens.compareTo(other.microsPerMillionTokens)

    companion object {
        const val MICROS_SCALE = 6

        /** 1_000_000_000_000 micros = 1_000_000 currency units per million tokens. */
        val MAX_MICROS_PER_MILLION: BigInteger = BigInteger.TEN.pow(12)

        /** Explicitly free; callers must choose this instead of omitting a price. */
        val ZERO: TokenPrice = TokenPrice(BigInteger.ZERO)

        fun ofMicrosPerMillion(micros: BigInteger): TokenPrice = TokenPrice(micros)

        fun ofMicrosPerMillion(micros: Long): TokenPrice = TokenPrice(BigInteger.valueOf(micros))

        /**
         * Converts a currency amount per million tokens into integer micros. Amounts
         * finer than one micro-unit per million are rejected rather than rounded.
         */
        fun ofCurrencyPerMillion(amount: BigDecimal): TokenPrice {
            require(amount.signum() >= 0) { "Token price cannot be negative" }
            val normalized = amount.stripTrailingZeros()
            require(normalized.scale() <= MICROS_SCALE) { "Token price precision exceeds 6 decimal places" }
            require(normalized <= BigDecimal(MAX_MICROS_PER_MILLION, MICROS_SCALE)) { "Token price exceeds limit" }
            val micros = normalized.movePointRight(MICROS_SCALE).toBigIntegerExact()
            require(micros <= MAX_MICROS_PER_MILLION) { "Token price exceeds limit" }
            return TokenPrice(micros)
        }
    }
}

/**
 * Thinking is never implicitly free. A price table must either bill thinking at the
 * output rate or state a separate rate, including an explicit [TokenPrice.ZERO] when
 * the provider does not charge for it.
 */
sealed interface ThinkingPricing {
    /** Thinking tokens use exactly [ModelPricing.output]'s rate. */
    data object AsOutput : ThinkingPricing

    /** A distinct rate for thinking tokens. */
    data class Separate(val price: TokenPrice) : ThinkingPricing
}

/**
 * Immutable rates for one model. A null [modelKey] marks the table's single fallback
 * entry; it does not mean "the model is unknown". Exact keys are matched first.
 */
data class ModelPricing(
    val input: TokenPrice,
    val output: TokenPrice,
    val cacheRead: TokenPrice,
    val cacheWrite: TokenPrice,
    val thinking: ThinkingPricing = ThinkingPricing.AsOutput,
    val modelKey: String? = null,
) {
    init {
        modelKey?.let(::validateModelKey)
    }

    fun priceFor(component: UsageComponent): TokenPrice = when (component) {
        UsageComponent.INPUT -> input
        UsageComponent.CACHE_READ -> cacheRead
        UsageComponent.CACHE_WRITE -> cacheWrite
        UsageComponent.THINKING -> when (val rule = thinking) {
            ThinkingPricing.AsOutput -> output
            is ThinkingPricing.Separate -> rule.price
        }
        UsageComponent.OUTPUT -> output
    }

    /** Audit source of the effective rate; only thinking can borrow the output rate. */
    fun rateSourceFor(component: UsageComponent): UsageComponent =
        if (component == UsageComponent.THINKING && thinking == ThinkingPricing.AsOutput) {
            UsageComponent.OUTPUT
        } else {
            component
        }

    companion object {
        const val MAX_MODEL_KEY_LENGTH = 200
    }
}

enum class PriceMatch { EXACT, DEFAULT }

enum class CostUnavailableReason { NO_MATCHING_PRICE, INVALID_MODEL_KEY }

sealed interface PriceLookup {
    data class Matched(val pricing: ModelPricing, val match: PriceMatch) : PriceLookup
    data class Missing(val requestedModelKey: String?, val reason: CostUnavailableReason) : PriceLookup
}

/** Stable binding of stored project/model settings to one immutable price table. */
data class PriceTableRef(
    val currency: CurrencyCode,
    val version: Long,
    val fingerprint: ObjectFingerprint,
) {
    init {
        require(version in 0L..PriceTable.MAX_VERSION) { "Price table version out of range" }
    }
}

/**
 * Immutable, currency-uniform price table. Entries are sorted by an explicit key
 * order so the canonical encoding and fingerprint do not depend on insertion order.
 * At most one entry may omit its model key; that entry is the fallback for models
 * without an exact match. Exact keys are case-sensitive.
 */
class PriceTable(
    val currency: CurrencyCode,
    val version: Long,
    pricing: List<ModelPricing>,
) {
    init {
        require(version in 0L..MAX_VERSION) { "Price table version out of range" }
        require(pricing.size <= MAX_ENTRIES) { "Price table exceeds entry limit" }
        val keys = pricing.map { it.modelKey }
        require(keys.toSet().size == keys.size) { "Duplicate model key in price table" }
    }

    val entries: List<ModelPricing> = frozenList(pricing.sortedWith(ENTRY_ORDER))
    val defaultPricing: ModelPricing? = entries.firstOrNull { it.modelKey == null }

    private val byModel: Map<String, ModelPricing> = run {
        val index = LinkedHashMap<String, ModelPricing>()
        entries.forEach { entry -> entry.modelKey?.let { index[it] = entry } }
        java.util.Collections.unmodifiableMap(index)
    }

    /** Exact key first, then the explicit fallback entry; never invents a zero rate. */
    fun lookup(modelKey: String?): PriceLookup {
        if (modelKey != null) {
            if (!isValidModelKey(modelKey)) {
                return PriceLookup.Missing(modelKey, CostUnavailableReason.INVALID_MODEL_KEY)
            }
            byModel[modelKey]?.let { return PriceLookup.Matched(it, PriceMatch.EXACT) }
        }
        val fallback = defaultPricing
            ?: return PriceLookup.Missing(modelKey, CostUnavailableReason.NO_MATCHING_PRICE)
        return PriceLookup.Matched(fallback, PriceMatch.DEFAULT)
    }

    /** Canonical JSON used for persistence and fingerprinting. */
    val canonicalJson: String by lazy(LazyThreadSafetyMode.PUBLICATION) { PriceTableCodec.encode(this) }

    /** SHA-256 of [canonicalJson]; 64 lower-case hexadecimal characters. */
    val fingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) { PriceTableCodec.fingerprint(this) }

    fun ref(): PriceTableRef = PriceTableRef(currency, version, ObjectFingerprint(fingerprint))

    override fun toString(): String = "PriceTable(currency=${currency.code}, version=$version, entries=${entries.size})"

    companion object {
        const val MAX_ENTRIES = 256
        const val MAX_VERSION = 9_007_199_254_740_991L

        private val ENTRY_ORDER: Comparator<ModelPricing> = Comparator { left, right ->
            val leftKey = left.modelKey
            val rightKey = right.modelKey
            when {
                leftKey == null && rightKey == null -> 0
                leftKey == null -> -1
                rightKey == null -> 1
                else -> leftKey.compareTo(rightKey)
            }
        }
    }
}

/**
 * Versioned canonical price-table encoding. It contains only currency, schema/table
 * versions, model keys and integer rates; no endpoint, credential or request data.
 */
object PriceTableCodec {
    const val FORMAT = "lnt-model-prices"
    const val FORMAT_VERSION = 1
    const val MAX_CHARS = 262_144

    private val ROOT_KEYS = setOf("format", "formatVersion", "currency", "version", "entries")
    private val ENTRY_KEYS = setOf("model", "input", "cacheRead", "cacheWrite", "thinking", "output")

    fun encode(table: PriceTable): String {
        val encoded = buildJsonObject {
            put("format", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("currency", table.currency.code)
            put("version", table.version)
            put("entries", JsonArray(table.entries.map { encodeEntry(it) }))
        }.toString()
        require(encoded.length <= MAX_CHARS) { "Price table encoding exceeds limit" }
        return encoded
    }

    fun fingerprint(table: PriceTable): String =
        TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(encode(table)))

    /** Strict inverse of [encode]; unknown fields and fractional rates are rejected. */
    fun decode(text: String): PriceTable {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 8)).json
        require(root.keys == ROOT_KEYS) { "Invalid price table fields" }
        require(string(root, "format") == FORMAT) { "Unsupported price table format" }
        require(integer(root, "formatVersion", BigInteger.ONE) == BigInteger.valueOf(FORMAT_VERSION.toLong())) {
            "Unsupported price table encoding"
        }
        val currency = CurrencyCode.of(string(root, "currency"))
        val version = long(root, "version", 0L, PriceTable.MAX_VERSION)
        val entries = root.getValue("entries") as? JsonArray
            ?: throw IllegalArgumentException("Invalid price entries")
        require(entries.size <= PriceTable.MAX_ENTRIES) { "Price table exceeds entry limit" }
        return PriceTable(currency, version, entries.map { decodeEntry(it) })
    }

    private fun decodeEntry(element: JsonElement): ModelPricing {
        val entry = element as? JsonObject ?: throw IllegalArgumentException("Invalid price entry")
        require(entry.keys == ENTRY_KEYS) { "Invalid price entry fields" }
        val modelKey = when (val model = entry.getValue("model")) {
            JsonNull -> null
            is JsonPrimitive -> {
                require(model.isString) { "Model key must be a string or null" }
                model.content
            }
            else -> throw IllegalArgumentException("Model key must be a string or null")
        }
        val thinking = entry.getValue("thinking") as? JsonObject
            ?: throw IllegalArgumentException("Invalid thinking pricing")
        val rule = when {
            thinking.keys == setOf("mode") -> {
                require(string(thinking, "mode") == "output") { "Unsupported thinking pricing" }
                ThinkingPricing.AsOutput
            }
            thinking.keys == setOf("mode", "microsPerMillion") -> {
                require(string(thinking, "mode") == "separate") { "Unsupported thinking pricing" }
                ThinkingPricing.Separate(rate(thinking, "microsPerMillion"))
            }
            else -> throw IllegalArgumentException("Invalid thinking pricing")
        }
        return ModelPricing(
            input = rate(entry, "input"),
            output = rate(entry, "output"),
            cacheRead = rate(entry, "cacheRead"),
            cacheWrite = rate(entry, "cacheWrite"),
            thinking = rule,
            modelKey = modelKey,
        )
    }

    private fun rate(obj: JsonObject, key: String): TokenPrice =
        TokenPrice.ofMicrosPerMillion(integer(obj, key, TokenPrice.MAX_MICROS_PER_MILLION))

    private fun integer(obj: JsonObject, key: String, maximum: BigInteger): BigInteger {
        val primitive = obj.getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid numeric field")
        require(!primitive.isString) { "Numeric field must be a number" }
        val decimal = try {
            BigDecimal(primitive.content)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid numeric field")
        }
        require(decimal.signum() >= 0 && decimal <= BigDecimal(maximum)) { "Numeric field out of range" }
        val normalized = decimal.stripTrailingZeros()
        require(normalized.scale() <= 0) { "Numeric field must be an integer" }
        return normalized.toBigIntegerExact()
    }

    private fun long(obj: JsonObject, key: String, minimum: Long, maximum: Long): Long {
        val value = integer(obj, key, BigInteger.valueOf(maximum))
        require(value >= BigInteger.valueOf(minimum)) { "Numeric field out of range" }
        return value.toLong()
    }

    private fun string(obj: JsonObject, key: String): String {
        val primitive = obj.getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid string field")
        require(primitive.isString) { "String field must be a string" }
        return primitive.content
    }

    private fun encodeEntry(entry: ModelPricing) = buildJsonObject {
        put("model", entry.modelKey?.let { JsonPrimitive(it) } ?: JsonNull)
        put("input", entry.input.microsPerMillionTokens)
        put("cacheRead", entry.cacheRead.microsPerMillionTokens)
        put("cacheWrite", entry.cacheWrite.microsPerMillionTokens)
        put("thinking", when (val rule = entry.thinking) {
            ThinkingPricing.AsOutput -> buildJsonObject { put("mode", "output") }
            is ThinkingPricing.Separate -> buildJsonObject {
                put("mode", "separate")
                put("microsPerMillion", rule.price.microsPerMillionTokens)
            }
        })
        put("output", entry.output.microsPerMillionTokens)
    }
}

private fun validateModelKey(value: String) {
    require(isValidModelKey(value)) { "Invalid model key" }
}

private fun isValidModelKey(value: String): Boolean =
    value.isNotBlank() &&
        value.length <= ModelPricing.MAX_MODEL_KEY_LENGTH &&
        !value.first().isWhitespace() &&
        !value.last().isWhitespace() &&
        value.none { it.isISOControl() } &&
        hasValidUtf8(value)

private fun hasValidUtf8(value: String): Boolean = try {
    TextProjectFingerprint.utf8(value)
    true
} catch (_: TextProjectException) {
    false
}
