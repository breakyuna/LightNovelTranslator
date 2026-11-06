package com.breakyuna.noveltranslator.nativecore.domain

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections

/** The five receipt components. Every cost projection reports all five, including explicit zeros. */
enum class UsageComponent { INPUT, CACHE_READ, CACHE_WRITE, THINKING, OUTPUT }

/** Actual and estimated costs are independent projections and are never added together. */
enum class CostBasis { ACTUAL, ESTIMATED }

/**
 * [COMPLETE] means every receipt for this projection had a known token count.
 * [INCOMPLETE_UNKNOWN] means at least one component has unknown receipts; [UsageCost.knownTotal]
 * is then a lower bound, not a bill and not a zero claim.
 */
enum class CostStatus { COMPLETE, INCOMPLETE_UNKNOWN }

/**
 * Exact non-negative currency amount with twelve decimal places. A token price is an
 * integer number of micro-units per million tokens, so `tokens * price` is an exact
 * integer coefficient at this scale: no Double and no intermediate rounding.
 */
@JvmInline
value class CostAmount private constructor(val amount: BigDecimal) : Comparable<CostAmount> {
    init {
        require(amount.signum() >= 0) { "Cost cannot be negative" }
        require(amount.scale() == SCALE) { "Cost scale must be $SCALE" }
        require(amount <= MAX_AMOUNT) { "Cost exceeds limit" }
    }

    val isZero: Boolean get() = amount.signum() == 0

    operator fun plus(other: CostAmount): CostAmount = CostAmount(amount.add(other.amount))

    override fun compareTo(other: CostAmount): Int = amount.compareTo(other.amount)

    fun toPlainString(): String = amount.toPlainString()

    companion object {
        const val SCALE = 12

        /** 1_000_000_000_000_000_000_000_000_000_000 currency units. */
        val MAX_AMOUNT: BigDecimal = BigDecimal.TEN.pow(30).setScale(SCALE)

        val ZERO: CostAmount = CostAmount(BigDecimal.ZERO.setScale(SCALE))

        /** Rejects values finer than [SCALE] instead of rounding them. */
        fun ofCurrency(amount: BigDecimal): CostAmount {
            require(amount.signum() >= 0) { "Cost cannot be negative" }
            val normalized = amount.stripTrailingZeros()
            require(normalized.scale() <= SCALE) { "Cost precision exceeds $SCALE decimal places" }
            require(normalized <= MAX_AMOUNT) { "Cost exceeds limit" }
            return CostAmount(normalized.setScale(SCALE))
        }

        /**
         * Exact cost for [tokens] at [price]. `price` is micro-units per million
         * tokens, hence the resulting currency amount has scale twelve.
         */
        fun ofTokens(tokens: BigInteger, price: TokenPrice): CostAmount {
            require(tokens.signum() >= 0) { "Token count cannot be negative" }
            if (price.isZero) return ZERO
            val maxTokens = MAX_AMOUNT.unscaledValue().divide(price.microsPerMillionTokens)
            require(tokens <= maxTokens) { "Cost exceeds limit" }
            return CostAmount(BigDecimal(tokens.multiply(price.microsPerMillionTokens), SCALE))
        }
    }
}

/**
 * One priced component. [unknownRequests] is never folded into [tokens] or [amount];
 * a positive value means the projection is incomplete even when [amount] is zero.
 * [rateSource] identifies whether thinking borrowed the output rate.
 */
class CostLine(
    val component: UsageComponent,
    val tokens: BigInteger,
    val price: TokenPrice,
    val amount: CostAmount,
    val unknownRequests: Long,
    val rateSource: UsageComponent = component,
) {
    init {
        require(tokens.signum() >= 0) { "Token count cannot be negative" }
        require(unknownRequests >= 0) { "Unknown request count cannot be negative" }
        require(rateSource == component ||
            (component == UsageComponent.THINKING && rateSource == UsageComponent.OUTPUT)) {
            "Invalid rate source"
        }
        require(amount == CostAmount.ofTokens(tokens, price)) { "Cost line amount does not match tokens and price" }
    }

    val hasUnknownRequests: Boolean get() = unknownRequests > 0L
}

/**
 * Cost of exactly one basis (actual or estimated). The five lines are always present
 * and ordered by [UsageComponent]. [knownTotal] sums only known tokens; when
 * [status] is [CostStatus.INCOMPLETE_UNKNOWN] it must not be displayed or billed as a
 * complete cost. There is deliberately no combined actual-plus-estimated total.
 */
class UsageCost(
    val basis: CostBasis,
    val priceTable: PriceTableRef,
    costLines: List<CostLine>,
) {
    val lines: List<CostLine> = Collections.unmodifiableList(ArrayList(costLines.sortedBy { it.component.ordinal }))

    init {
        require(this.lines.map { it.component } == UsageComponent.entries.toList()) {
            "Usage cost requires all five components exactly once"
        }
    }

    val knownTotal: CostAmount = this.lines.fold(CostAmount.ZERO) { total, line -> total + line.amount }

    /** Tokens that contributed to [knownTotal]; zero proves no known tokens, not a complete zero bill. */
    val knownTokensTotal: BigInteger = this.lines.fold(BigInteger.ZERO) { total, line -> total.add(line.tokens) }

    val unknownRequestsByComponent: Map<UsageComponent, Long> = Collections.unmodifiableMap(
        this.lines.associateTo(LinkedHashMap<UsageComponent, Long>()) {
            it.component to it.unknownRequests
        },
    )

    val hasUnknownUsage: Boolean = unknownRequestsByComponent.values.any { it > 0L }
    val isComplete: Boolean get() = !hasUnknownUsage
    val status: CostStatus = if (hasUnknownUsage) CostStatus.INCOMPLETE_UNKNOWN else CostStatus.COMPLETE

    fun line(component: UsageComponent): CostLine = lines[component.ordinal]

    override fun toString(): String =
        "UsageCost(basis=$basis, total=${knownTotal.toPlainString()}, status=$status)"
}

/**
 * A price lookup result. [Priced] always carries separate actual and estimated costs;
 * [Unpriced] is an explicit missing-rate result and never substitutes a zero cost.
 */
sealed interface CostQuote {
    val requestedModelKey: String?
    val priceTable: PriceTableRef

    data class Priced(
        override val requestedModelKey: String?,
        val matchedModelKey: String?,
        val match: PriceMatch,
        override val priceTable: PriceTableRef,
        val actual: UsageCost,
        val estimated: UsageCost,
    ) : CostQuote {
        init {
            require(actual.basis == CostBasis.ACTUAL) { "Actual cost has the wrong basis" }
            require(estimated.basis == CostBasis.ESTIMATED) { "Estimated cost has the wrong basis" }
            require(actual.priceTable == priceTable && estimated.priceTable == priceTable) {
                "Cost projections use different price tables"
            }
        }

        val hasUnknownUsage: Boolean get() = actual.hasUnknownUsage || estimated.hasUnknownUsage
        val status: CostStatus
            get() = if (hasUnknownUsage) CostStatus.INCOMPLETE_UNKNOWN else CostStatus.COMPLETE
    }

    data class Unpriced(
        override val requestedModelKey: String?,
        val reason: CostUnavailableReason,
        override val priceTable: PriceTableRef,
    ) : CostQuote
}
