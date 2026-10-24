package com.breakyuna.noveltranslator.nativecore.domain

import java.math.BigDecimal
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingTest {
    private fun price(amount: String): TokenPrice = TokenPrice.ofCurrencyPerMillion(BigDecimal(amount))

    private fun pricing(
        modelKey: String? = null,
        input: String = "1",
        output: String = "2",
        cacheRead: String = "0.1",
        cacheWrite: String = "1.5",
        thinking: ThinkingPricing = ThinkingPricing.AsOutput,
    ): ModelPricing = ModelPricing(
        input = price(input),
        output = price(output),
        cacheRead = price(cacheRead),
        cacheWrite = price(cacheWrite),
        thinking = thinking,
        modelKey = modelKey,
    )

    @Test fun tokenPricesRejectNegativeOverLimitAndExcessPrecision() {
        assertThrows(IllegalArgumentException::class.java) {
            TokenPrice.ofCurrencyPerMillion(BigDecimal("-0.000001"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TokenPrice.ofMicrosPerMillion(BigInteger.valueOf(-1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TokenPrice.ofMicrosPerMillion(TokenPrice.MAX_MICROS_PER_MILLION.add(BigInteger.ONE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TokenPrice.ofCurrencyPerMillion(BigDecimal("0.0000001"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TokenPrice.ofCurrencyPerMillion(BigDecimal("1000000.000001"))
        }

        assertEquals(TokenPrice.MAX_MICROS_PER_MILLION, price("1000000").microsPerMillionTokens)
        assertEquals(BigInteger.ZERO, TokenPrice.ZERO.microsPerMillionTokens)
        assertTrue(price("0").isZero)
    }

    @Test fun currencyCodesAreStrictButSupportCommonIdentifiers() {
        assertEquals("USD", CurrencyCode.of("USD").code)
        assertEquals("USDT", CurrencyCode.of("USDT").code)
        assertThrows(IllegalArgumentException::class.java) { CurrencyCode.of("usd") }
        assertThrows(IllegalArgumentException::class.java) { CurrencyCode.of("US") }
        assertThrows(IllegalArgumentException::class.java) { CurrencyCode.of("USD-1") }
        assertThrows(IllegalArgumentException::class.java) { CurrencyCode.of("TOOLONGCODE") }
    }

    @Test fun costAmountKeepsSubMicroPrecisionWithoutDoubleRounding() {
        val subMicro = price("0.35")
        val oneToken = CostAmount.ofTokens(BigInteger.ONE, subMicro)
        assertEquals("0.000000350000", oneToken.toPlainString())
        assertEquals(CostAmount.ofCurrency(BigDecimal("0.00000035")), oneToken)
        assertEquals("0.350000000000", CostAmount.ofTokens(BigInteger.valueOf(1_000_000), subMicro).toPlainString())

        val precise = price("3.123456")
        assertEquals("0.000003123456", CostAmount.ofTokens(BigInteger.ONE, precise).toPlainString())
        assertEquals("3.123456000000",
            CostAmount.ofTokens(BigInteger.valueOf(1_000_000), precise).toPlainString())

        val sum = CostAmount.ofTokens(BigInteger.ONE, subMicro)
            .plus(CostAmount.ofTokens(BigInteger.ONE, precise))
        assertEquals("0.000003473456", sum.toPlainString())

        assertThrows(IllegalArgumentException::class.java) { CostAmount.ofCurrency(BigDecimal("-1")) }
        assertThrows(IllegalArgumentException::class.java) {
            CostAmount.ofCurrency(BigDecimal("0.0000000000001"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostAmount.ofCurrency(BigDecimal.TEN.pow(31))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CostAmount.ofTokens(BigInteger.valueOf(-1), subMicro)
        }
    }

    @Test fun modelKeysAndPriceTableSizeAreBounded() {
        assertEquals(200, ModelPricing.MAX_MODEL_KEY_LENGTH)
        assertThrows(IllegalArgumentException::class.java) { pricing(modelKey = "") }
        assertThrows(IllegalArgumentException::class.java) { pricing(modelKey = "   ") }
        assertThrows(IllegalArgumentException::class.java) { pricing(modelKey = " model") }
        assertThrows(IllegalArgumentException::class.java) { pricing(modelKey = "model ") }
        assertThrows(IllegalArgumentException::class.java) { pricing(modelKey = "a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) {
            pricing(modelKey = "m".repeat(ModelPricing.MAX_MODEL_KEY_LENGTH + 1))
        }

        val tooMany = List(PriceTable.MAX_ENTRIES + 1) { pricing(modelKey = "model-$it") }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTable(CurrencyCode.USD, 1, tooMany)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTable(CurrencyCode.USD, -1, listOf(pricing()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTable(CurrencyCode.USD, PriceTable.MAX_VERSION + 1, listOf(pricing()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTable(CurrencyCode.USD, 1, listOf(pricing(modelKey = "same"), pricing(modelKey = "same")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTable(CurrencyCode.USD, 1, listOf(pricing(modelKey = null), pricing(modelKey = null)))
        }
    }

    @Test fun canonicalEncodingAndFingerprintIgnoreEntryOrderButBindAllFacts() {
        val first = pricing(modelKey = "model-b", input = "1.25")
        val second = pricing(
            modelKey = "model-a",
            input = "0.5",
            thinking = ThinkingPricing.Separate(price("2.5")),
        )
        val fallback = pricing(modelKey = null, input = "0.75")
        val ordered = PriceTable(CurrencyCode.USD, 3, listOf(first, second, fallback))
        val reversed = PriceTable(CurrencyCode.USD, 3, listOf(fallback, second, first))

        assertEquals(ordered.canonicalJson, reversed.canonicalJson)
        assertEquals(ordered.fingerprint, reversed.fingerprint)
        assertEquals(ordered.ref(), reversed.ref())

        val changedRate = PriceTable(CurrencyCode.USD, 3,
            listOf(first.copy(input = price("1.250001")), second, fallback))
        val changedVersion = PriceTable(CurrencyCode.USD, 4, listOf(first, second, fallback))
        val changedCurrency = PriceTable(CurrencyCode.of("CNY"), 3, listOf(first, second, fallback))
        assertNotEquals(ordered.fingerprint, changedRate.fingerprint)
        assertNotEquals(ordered.fingerprint, changedVersion.fingerprint)
        assertNotEquals(ordered.fingerprint, changedCurrency.fingerprint)

        assertEquals(64, ordered.fingerprint.length)
        assertTrue(ordered.fingerprint.all { it in "0123456789abcdef" })
        assertEquals(ordered.fingerprint, ordered.ref().fingerprint.sha256)

        val decoded = PriceTableCodec.decode(ordered.canonicalJson)
        assertEquals(ordered.canonicalJson, decoded.canonicalJson)
        assertEquals(ordered.fingerprint, decoded.fingerprint)
    }

    @Test fun codecRejectsMalformedFractionalAndOutOfRangeEntries() {
        val valid = "{\"format\":\"lnt-model-prices\",\"formatVersion\":1,\"currency\":\"USD\"," +
            "\"version\":1,\"entries\":[{\"model\":\"m\",\"input\":1000000,\"cacheRead\":0," +
            "\"cacheWrite\":0,\"thinking\":{\"mode\":\"output\"},\"output\":2000000}]}"
        val decoded = PriceTableCodec.decode(valid)
        assertEquals(PriceMatch.EXACT, (decoded.lookup("m") as PriceLookup.Matched).match)

        assertThrows(IllegalArgumentException::class.java) {
            PriceTableCodec.decode(valid.replace("\"input\":1000000", "\"input\":-1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTableCodec.decode(valid.replace("\"input\":1000000", "\"input\":1.5"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTableCodec.decode(valid.replace("\"formatVersion\":1", "\"formatVersion\":2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTableCodec.decode(valid.replace("\"mode\":\"output\"", "\"mode\":\"free\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PriceTableCodec.decode(valid.replace("{\"format\"", "{\"extra\":1,\"format\""))
        }
    }

    @Test fun lookupUsesExactKeyThenExplicitFallbackAndReportsMissingKeys() {
        val fallback = pricing(modelKey = null, input = "0.5")
        val exact = pricing(modelKey = "model-a", input = "3")
        val table = PriceTable(CurrencyCode.USD, 1, listOf(fallback, exact))

        val exactMatch = table.lookup("model-a") as PriceLookup.Matched
        assertEquals(PriceMatch.EXACT, exactMatch.match)
        assertEquals(exact, exactMatch.pricing)

        val fallbackMatch = table.lookup("another-model") as PriceLookup.Matched
        assertEquals(PriceMatch.DEFAULT, fallbackMatch.match)
        assertEquals(fallback, fallbackMatch.pricing)

        val defaultMatch = table.lookup(null) as PriceLookup.Matched
        assertEquals(PriceMatch.DEFAULT, defaultMatch.match)

        val invalid = table.lookup(" ") as PriceLookup.Missing
        assertEquals(CostUnavailableReason.INVALID_MODEL_KEY, invalid.reason)

        val exactOnly = PriceTable(CurrencyCode.USD, 1, listOf(exact))
        val missing = exactOnly.lookup("another-model") as PriceLookup.Missing
        assertEquals(CostUnavailableReason.NO_MATCHING_PRICE, missing.reason)
        assertEquals("another-model", missing.requestedModelKey)
        assertNull((exactOnly.lookup(null) as PriceLookup.Missing).requestedModelKey)
    }

    @Test fun thinkingIsEitherExplicitlySeparateOrExplicitlyPricedAsOutput() {
        val output = price("15")
        val separate = pricing(
            output = "15",
            thinking = ThinkingPricing.Separate(price("20")),
        )
        val asOutput = pricing(output = "15", thinking = ThinkingPricing.AsOutput)

        assertEquals(price("20"), separate.priceFor(UsageComponent.THINKING))
        assertEquals(UsageComponent.THINKING, separate.rateSourceFor(UsageComponent.THINKING))
        assertEquals(price("15"), asOutput.priceFor(UsageComponent.THINKING))
        assertEquals(UsageComponent.OUTPUT, asOutput.rateSourceFor(UsageComponent.THINKING))
        assertEquals(output, asOutput.priceFor(UsageComponent.OUTPUT))

        val explicitFree = pricing(thinking = ThinkingPricing.Separate(TokenPrice.ZERO))
        assertTrue(explicitFree.priceFor(UsageComponent.THINKING).isZero)
        assertFalse(separate.priceFor(UsageComponent.THINKING).isZero)
    }
}
