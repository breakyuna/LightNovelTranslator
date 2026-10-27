package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.CostAmount
import com.breakyuna.noveltranslator.nativecore.domain.CostQuote
import com.breakyuna.noveltranslator.nativecore.domain.CurrencyCode
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.PriceTableVersionSource
import com.breakyuna.noveltranslator.nativecore.domain.UsageCostState
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerCursor
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerEntry
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerQuery
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerReadPort
import com.breakyuna.noveltranslator.nativecore.domain.UsageReceiptState
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Collections
import java.util.TreeSet
import kotlinx.coroutines.CancellationException

/**
 * One cost bucket. KNOWN carries complete actual/estimated totals for exactly [currency].
 * UNKNOWN and MISSING_VERSION deliberately carry null amounts: a missing price version or
 * unpriced receipt is never rendered as zero. Different currencies are always separate
 * buckets and are never added together.
 */
data class UsageCostGroup(
    val currency: CurrencyCode?,
    val state: UsageCostState,
    val actual: CostAmount?,
    val estimated: CostAmount?,
    val receipts: Long,
    val priceVersions: List<Long>,
    val unresolvedReceipts: Long = 0,
    val unpricedReceipts: Long = 0,
    val unknownUsageReceipts: Long = 0,
) {
    init {
        require(receipts > 0) { "Empty cost group" }
        require((state == UsageCostState.KNOWN) == (actual != null && estimated != null)) {
            "Only a known cost group has amounts"
        }
        require(state != UsageCostState.KNOWN || currency != null) { "Known cost requires a currency" }
        require(unresolvedReceipts >= 0 && unpricedReceipts >= 0 && unknownUsageReceipts >= 0)
        require(unresolvedReceipts + unpricedReceipts + unknownUsageReceipts <= receipts)
        require(priceVersions == priceVersions.distinct().sorted()) { "Price versions are not canonical" }
    }
}

/** Projection of all visible receipts; pages are consumed one at a time and never retained. */
class UsageCostSummary(groups: List<UsageCostGroup>, val scannedReceipts: Long) {
    val groups: List<UsageCostGroup> = Collections.unmodifiableList(ArrayList(groups))

    init {
        require(scannedReceipts >= 0)
        require(this.groups.sumOf { it.receipts } <= scannedReceipts) { "Cost groups exceed scanned receipts" }
    }

    val knownGroups: List<UsageCostGroup>
        get() = groups.filter { it.state == UsageCostState.KNOWN }

    /** Every receipt without a complete known amount, including missing historical versions. */
    val unknownReceipts: Long
        get() = groups.filter { it.state != UsageCostState.KNOWN }.sumOf { it.receipts }

    val missingVersionReceipts: Long
        get() = groups.filter { it.state == UsageCostState.MISSING_VERSION }.sumOf { it.receipts }

    fun group(currency: CurrencyCode?, state: UsageCostState): UsageCostGroup? =
        groups.firstOrNull { it.currency == currency && it.state == state }
}

/**
 * Historical cost aggregation keyed by each receipt's captured price version. It reads the
 * ledger through its paging port, keeps only running BigInteger sums and counts, and loads a
 * historical table only by the version recorded on the receipt. Current prices are never used
 * to price an older receipt, and no missing version is converted into a zero amount.
 */
class UsageCostAggregator(
    private val ledger: UsageLedgerReadPort,
    private val prices: PriceTableVersionSource,
) {
    suspend fun aggregate(query: UsageLedgerQuery = UsageLedgerQuery()): UsageCostSummary {
        val totals = LinkedHashMap<GroupKey, GroupTotals>()
        val tables = HashMap<Long, PriceTable?>()
        var scanned = 0L
        var cursor: UsageLedgerCursor? = null
        while (true) {
            val page = ledger.entries(query, cursor)
            for (entry in page.entries) {
                scanned = Math.addExact(scanned, 1L)
                accumulate(entry, totals, tables)
            }
            val next = page.next ?: break
            require(cursor == null || next.before < cursor.before) { "Usage cursor did not advance" }
            cursor = next
        }
        return UsageCostSummary(totals.map { (key, value) -> value.finish(key) }, scanned)
    }

    private suspend fun accumulate(
        entry: UsageLedgerEntry,
        totals: MutableMap<GroupKey, GroupTotals>,
        tables: MutableMap<Long, PriceTable?>,
    ) {
        val version = entry.priceVersion
        val usage = entry.usage
        if (entry.state != UsageReceiptState.RECEIVED || usage == null) {
            group(totals, entry.priceCurrency, UsageCostState.UNKNOWN)
                .addUnknown(version, unresolved = true)
            return
        }
        if (version == null) {
            // Legacy receipt: no captured version, so no historical quote can be reconstructed.
            group(totals, entry.priceCurrency, UsageCostState.UNKNOWN).addUnknown(null)
            return
        }
        val table = table(version, prices, tables)
        if (table == null) {
            group(totals, entry.priceCurrency, UsageCostState.MISSING_VERSION).addUnknown(version)
            return
        }
        if (entry.priceCurrency != null && entry.priceCurrency != table.currency) {
            group(totals, entry.priceCurrency, UsageCostState.UNKNOWN).addUnknown(version)
            return
        }
        if (entry.priceFingerprint != null && entry.priceFingerprint.sha256 != table.fingerprint) {
            group(totals, table.currency, UsageCostState.UNKNOWN).addUnknown(version)
            return
        }
        val model = entry.pricedModelKey ?: entry.model
        when (val quote = UsageCostCalculator(table).quote(model, usage)) {
            is CostQuote.Unpriced -> group(totals, table.currency, UsageCostState.UNKNOWN)
                .addUnknown(version, unpriced = true)
            is CostQuote.Priced -> {
                if (!quote.actual.isComplete || !quote.estimated.isComplete) {
                    group(totals, table.currency, UsageCostState.UNKNOWN)
                        .addUnknown(version, unknownUsage = true)
                } else {
                    group(totals, table.currency, UsageCostState.KNOWN)
                        .addKnown(quote, version)
                }
            }
        }
    }

    private suspend fun table(
        version: Long,
        prices: PriceTableVersionSource,
        cache: MutableMap<Long, PriceTable?>,
    ): PriceTable? {
        if (cache.containsKey(version)) return cache[version]
        val table = try {
            prices.loadPriceTable(version).takeIf { it.version == version }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        cache[version] = table
        return table
    }

    private fun group(
        totals: MutableMap<GroupKey, GroupTotals>,
        currency: CurrencyCode?,
        state: UsageCostState,
    ): GroupTotals = totals.getOrPut(GroupKey(currency, state)) { GroupTotals() }

    private data class GroupKey(val currency: CurrencyCode?, val state: UsageCostState)

    private class GroupTotals {
        private var receipts = 0L
        private var unresolved = 0L
        private var unpriced = 0L
        private var unknownUsage = 0L
        private var actual = BigInteger.ZERO
        private var estimated = BigInteger.ZERO
        private val versions = TreeSet<Long>()

        fun addKnown(quote: CostQuote.Priced, version: Long) {
            receipts = Math.addExact(receipts, 1L)
            versions += version
            actual = actual.add(quote.actual.knownTotal.amount.unscaledValue())
            estimated = estimated.add(quote.estimated.knownTotal.amount.unscaledValue())
        }

        fun addUnknown(version: Long?, unresolved: Boolean = false,
            unpriced: Boolean = false, unknownUsage: Boolean = false) {
            receipts = Math.addExact(receipts, 1L)
            if (version != null) versions += version
            if (unresolved) this.unresolved = Math.addExact(this.unresolved, 1L)
            if (unpriced) this.unpriced = Math.addExact(this.unpriced, 1L)
            if (unknownUsage) this.unknownUsage = Math.addExact(this.unknownUsage, 1L)
        }

        fun finish(key: GroupKey): UsageCostGroup {
            val known = key.state == UsageCostState.KNOWN
            return UsageCostGroup(
                currency = key.currency,
                state = key.state,
                actual = if (known) amount(actual) else null,
                estimated = if (known) amount(estimated) else null,
                receipts = receipts,
                priceVersions = versions.toList(),
                unresolvedReceipts = unresolved,
                unpricedReceipts = unpriced,
                unknownUsageReceipts = unknownUsage,
            )
        }

        /** Sums exact scale-12 unscaled values; exceeds the domain limit explicitly. */
        private fun amount(unscaled: BigInteger): CostAmount {
            require(unscaled.signum() >= 0) { "Cost cannot be negative" }
            require(unscaled <= CostAmount.MAX_AMOUNT.unscaledValue()) { "Usage cost total exceeds limit" }
            return CostAmount.ofCurrency(BigDecimal(unscaled, CostAmount.SCALE))
        }
    }
}
