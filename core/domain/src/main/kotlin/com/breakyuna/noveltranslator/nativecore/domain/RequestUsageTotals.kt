package com.breakyuna.noveltranslator.nativecore.domain

import java.math.BigInteger

/** Known facts survive later unknown receipts. Estimated and actual amounts never get mixed. */
data class TokenUsageTotal(
    val actual: BigInteger = BigInteger.ZERO,
    val estimated: BigInteger = BigInteger.ZERO,
    val unknownRequests: Long = 0,
) {
    init { require(actual.signum() >= 0 && estimated.signum() >= 0 && unknownRequests >= 0) }
    fun add(measurement: TokenMeasurement): TokenUsageTotal = when (measurement.source) {
        UsageSource.ACTUAL -> copy(actual = actual.add(BigInteger.valueOf(requireNotNull(measurement.tokens))))
        UsageSource.ESTIMATED -> copy(estimated = estimated.add(BigInteger.valueOf(requireNotNull(measurement.tokens))))
        UsageSource.UNKNOWN -> copy(unknownRequests = Math.addExact(unknownRequests, 1))
    }
}

/** Response receipts only: this count is neither dispatch count nor a claim about billing. */
data class RequestUsageTotals(
    val responses: Long = 0,
    val input: TokenUsageTotal = TokenUsageTotal(),
    val cacheRead: TokenUsageTotal = TokenUsageTotal(),
    val cacheWrite: TokenUsageTotal = TokenUsageTotal(),
    val thinking: TokenUsageTotal = TokenUsageTotal(),
    val output: TokenUsageTotal = TokenUsageTotal(),
) {
    init {
        require(responses >= 0)
        require(listOf(input, cacheRead, cacheWrite, thinking, output).all { it.unknownRequests <= responses })
    }
    fun add(usage: RequestUsage): RequestUsageTotals = RequestUsageTotals(Math.addExact(responses, 1),
        input.add(usage.input), cacheRead.add(usage.cacheRead), cacheWrite.add(usage.cacheWrite),
        thinking.add(usage.thinking), output.add(usage.output))
}
