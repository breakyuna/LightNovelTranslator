package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.CostAmount
import com.breakyuna.noveltranslator.nativecore.domain.CurrencyCode
import com.breakyuna.noveltranslator.nativecore.domain.DurableUsageSummary
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelPricing
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.PriceTableVersionSource
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsage
import com.breakyuna.noveltranslator.nativecore.domain.RequestUsageTotals
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ThinkingPricing
import com.breakyuna.noveltranslator.nativecore.domain.TokenMeasurement
import com.breakyuna.noveltranslator.nativecore.domain.TokenPrice
import com.breakyuna.noveltranslator.nativecore.domain.UsageCostState
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerCursor
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerEntry
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerPage
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerQuery
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerReadPort
import com.breakyuna.noveltranslator.nativecore.domain.UsageReceiptChannel
import com.breakyuna.noveltranslator.nativecore.domain.UsageReceiptState
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import java.io.FileNotFoundException
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageCostAggregatorTest {
    private fun price(amount: String): TokenPrice = TokenPrice.ofCurrencyPerMillion(BigDecimal(amount))

    private fun pricing(model: String, input: String) = ModelPricing(
        input = price(input),
        output = price("0"),
        cacheRead = price("0"),
        cacheWrite = price("0"),
        thinking = ThinkingPricing.AsOutput,
        modelKey = model,
    )

    private fun table(version: Long, currency: CurrencyCode, model: String, input: String) =
        PriceTable(currency, version, listOf(pricing(model, input)))

    private fun actual(tokens: Long) = TokenMeasurement(tokens, UsageSource.ACTUAL)
    private fun usage(input: TokenMeasurement = actual(0)): RequestUsage =
        RequestUsage(input, actual(0), actual(0), actual(0), actual(0))
    private val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)

    private fun entry(
        id: String,
        version: Long?,
        currency: CurrencyCode?,
        model: String?,
        usage: RequestUsage?,
        state: UsageReceiptState = UsageReceiptState.RECEIVED,
        fingerprint: ObjectFingerprint? = null,
    ) = UsageLedgerEntry(
        id = id,
        channel = UsageReceiptChannel.TRANSLATION,
        attempt = RequestAttemptId(id),
        ref = null,
        run = RunIdentity(ProjectId("project"), null, RunId("run"), CommandId("command")),
        configuration = ModelConfigurationRef("configuration", 1),
        model = model,
        state = state,
        usage = usage,
        stop = null,
        failure = null,
        priceVersion = version,
        priceCurrency = currency,
        priceFingerprint = fingerprint,
        pricedModelKey = model,
        costState = if (version == null) UsageCostState.UNKNOWN else UsageCostState.KNOWN,
    )

    private class PagedLedger(private val all: List<UsageLedgerEntry>) : UsageLedgerReadPort {
        var pages = 0
        override suspend fun summary(query: UsageLedgerQuery): DurableUsageSummary =
            DurableUsageSummary(0, 0, 0, RequestUsageTotals())
        override suspend fun entries(query: UsageLedgerQuery, cursor: UsageLedgerCursor?): UsageLedgerPage {
            pages++
            val start = cursor?.before?.toInt() ?: 0
            val end = minOf(start + 2, all.size)
            val next = if (end < all.size) UsageLedgerCursor(query, end.toLong()) else null
            return UsageLedgerPage(all.subList(start, end), next)
        }
    }

    private class Prices(private val tables: Map<Long, PriceTable>) : PriceTableVersionSource {
        override suspend fun loadPriceTable(version: Long): PriceTable =
            tables[version] ?: throw FileNotFoundException("Price version $version is missing")
    }

    @Test fun receiptsArePricedByTheirOwnHistoricalVersionNotTheLatestTable() = runBlocking {
        val v1 = table(1, CurrencyCode.USD, "model", "3")
        val v2 = table(2, CurrencyCode.USD, "model", "5")
        val ledger = PagedLedger(listOf(
            entry("a", 1, CurrencyCode.USD, "model", usage(actual(1_000_000))),
            entry("b", 2, CurrencyCode.USD, "model", usage(actual(1_000_000))),
            entry("c", 1, CurrencyCode.USD, "model", usage(actual(1_000_000))),
        ))
        val summary = UsageCostAggregator(ledger, Prices(mapOf(1L to v1, 2L to v2))).aggregate()

        assertEquals(3L, summary.scannedReceipts)
        assertEquals(2, ledger.pages)
        val known = summary.knownGroups.single()
        assertEquals(CurrencyCode.USD, known.currency)
        assertEquals(UsageCostState.KNOWN, known.state)
        assertEquals("11.000000000000", known.actual?.toPlainString())
        assertEquals("0.000000000000", known.estimated?.toPlainString())
        assertEquals(listOf(1L, 2L), known.priceVersions)
        assertEquals(3L, known.receipts)
    }

    @Test fun differentCurrenciesAreSeparateBucketsAndAreNeverAddedTogether() = runBlocking {
        val usd = table(1, CurrencyCode.USD, "model", "1")
        val eur = table(2, CurrencyCode.of("EUR"), "model", "2")
        val ledger = PagedLedger(listOf(
            entry("a", 1, CurrencyCode.USD, "model", usage(actual(1_000_000))),
            entry("b", 2, CurrencyCode.of("EUR"), "model", usage(actual(1_000_000))),
        ))
        val summary = UsageCostAggregator(ledger, Prices(mapOf(1L to usd, 2L to eur))).aggregate()

        assertEquals(2, summary.knownGroups.size)
        assertEquals("1.000000000000",
            summary.group(CurrencyCode.USD, UsageCostState.KNOWN)?.actual?.toPlainString())
        assertEquals("2.000000000000",
            summary.group(CurrencyCode.of("EUR"), UsageCostState.KNOWN)?.actual?.toPlainString())
        assertFalse(summary.groups.any { it.actual?.toPlainString() == "3.000000000000" })
    }

    @Test fun missingVersionLegacyAndUnpricedReceiptsAreUnknownWithoutZeroAmounts() = runBlocking {
        val v1 = table(1, CurrencyCode.USD, "model", "1")
        val ledger = PagedLedger(listOf(
            entry("missing", 9, CurrencyCode.USD, "model", usage(actual(1_000_000))),
            entry("legacy", null, null, "model", usage(actual(1_000_000))),
            entry("unpriced", 1, CurrencyCode.USD, "absent", usage(actual(1_000_000))),
        ))
        val summary = UsageCostAggregator(ledger, Prices(mapOf(1L to v1))).aggregate()

        assertTrue(summary.knownGroups.isEmpty())
        assertEquals(1L, summary.missingVersionReceipts)
        assertEquals(3L, summary.unknownReceipts)
        val missing = summary.group(CurrencyCode.USD, UsageCostState.MISSING_VERSION)!!
        assertNull(missing.actual)
        assertNull(missing.estimated)
        val legacy = summary.group(null, UsageCostState.UNKNOWN)!!
        assertNull(legacy.actual)
        assertNull(legacy.estimated)
        val unpriced = summary.group(CurrencyCode.USD, UsageCostState.UNKNOWN)!!
        assertNull(unpriced.actual)
        assertEquals(1L, unpriced.unpricedReceipts)
        assertTrue(summary.groups.none { it.actual?.isZero == true || it.estimated?.isZero == true })
    }

    @Test fun incompleteUsageAndUnresolvedReceiptsStayUnknownRatherThanZeroCost() = runBlocking {
        val v1 = table(1, CurrencyCode.USD, "model", "1")
        val ledger = PagedLedger(listOf(
            entry("partial", 1, CurrencyCode.USD, "model",
                usage(TokenMeasurement(10, UsageSource.ACTUAL)).copy(output = unknown)),
            entry("dispatched", 1, CurrencyCode.USD, "model", null, UsageReceiptState.DISPATCHED),
        ))
        val summary = UsageCostAggregator(ledger, Prices(mapOf(1L to v1))).aggregate()

        assertTrue(summary.knownGroups.isEmpty())
        val unknownGroup = summary.group(CurrencyCode.USD, UsageCostState.UNKNOWN)!!
        assertNull(unknownGroup.actual)
        assertNull(unknownGroup.estimated)
        assertEquals(2L, unknownGroup.receipts)
        assertEquals(1L, unknownGroup.unknownUsageReceipts)
        assertEquals(1L, unknownGroup.unresolvedReceipts)
    }

    @Test fun mismatchedPriceFingerprintIsUnknownInsteadOfUsingAnotherTable() = runBlocking {
        val v1 = table(1, CurrencyCode.USD, "model", "1")
        val ledger = PagedLedger(listOf(
            entry("a", 1, CurrencyCode.USD, "model", usage(actual(1_000_000)),
                fingerprint = ObjectFingerprint("a".repeat(64))),
        ))
        val summary = UsageCostAggregator(ledger, Prices(mapOf(1L to v1))).aggregate()

        assertTrue(summary.knownGroups.isEmpty())
        val unknownGroup = summary.group(CurrencyCode.USD, UsageCostState.UNKNOWN)!!
        assertNull(unknownGroup.actual)
        assertEquals(1L, unknownGroup.receipts)
    }

    @Test fun exactBigIntegerSumsSurviveMoreThanALongOfUnscaledCost() = runBlocking {
        val v1 = table(1, CurrencyCode.USD, "model", "1")
        val ledger = PagedLedger((0 until 3).map { index ->
            entry("a-$index", 1, CurrencyCode.USD, "model", usage(actual(Long.MAX_VALUE)))
        })
        val summary = UsageCostAggregator(ledger, Prices(mapOf(1L to v1))).aggregate()

        val known = summary.knownGroups.single()
        assertEquals("27670116110564.327421", known.actual?.toPlainString())
        // Three exact per-receipt amounts; the unscaled sum is far beyond a 64-bit value.
        assertEquals(BigInteger("27670116110564327421000000"), known.actual?.amount?.unscaledValue())
        assertEquals(CostAmount.ofCurrency(BigDecimal("27670116110564.327421")), known.actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun cursorThatDoesNotAdvanceIsRejected(): Unit = runBlocking {
        val ledger = object : UsageLedgerReadPort {
            override suspend fun summary(query: UsageLedgerQuery): DurableUsageSummary =
                DurableUsageSummary(0, 0, 0, RequestUsageTotals())
            override suspend fun entries(query: UsageLedgerQuery, cursor: UsageLedgerCursor?): UsageLedgerPage =
                UsageLedgerPage(listOf(entry("a", 1, CurrencyCode.USD, "model", usage())),
                    UsageLedgerCursor(query, 1))
        }
        UsageCostAggregator(ledger, Prices(mapOf(1L to table(1, CurrencyCode.USD, "model", "1"))))
            .aggregate()
    }
}
