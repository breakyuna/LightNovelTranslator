package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativePriceTableStoreTest {
    private lateinit var context: Context
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); clear() }
    @After fun clear() {
        for (suffix in listOf("", ".bak", ".new")) File(context.filesDir, "native-model-prices.json$suffix").delete()
        File(context.filesDir, "native-model-prices-history.json").delete()
        File(context.filesDir, "native-model-prices-history.json.bak").delete()
        File(context.filesDir, "native-model-prices-history").deleteRecursively()
    }
    private fun price(model: String) = ModelPricing(TokenPrice.ofMicrosPerMillion(1),
        TokenPrice.ofMicrosPerMillion(2), TokenPrice.ZERO, TokenPrice.ZERO, modelKey = model)

    @Test fun reopensAndRejectsStaleEditorAndCurrencyChanges() = runBlocking {
        val store = NativePriceTableStore(context)
        val empty = store.load()
        assertTrue(empty.entries.isEmpty())
        assertNull(store.currentVersion())
        assertTrue(store.versions().isEmpty())
        val first = store.save(empty.fingerprint, CurrencyCode.USD, price("one"))
        assertEquals(first.fingerprint, NativePriceTableStore(context).load().fingerprint)
        try { store.save(empty.fingerprint, CurrencyCode.USD, price("two")); fail("Accepted stale table") }
        catch (_: IllegalArgumentException) { }
        try { store.save(first.fingerprint, CurrencyCode.of("EUR"), price("two")); fail("Changed currency") }
        catch (_: IllegalArgumentException) { }
        assertEquals(first.fingerprint, store.load().fingerprint)
        assertEquals(1L, store.currentVersion())
        assertEquals(listOf(1L), store.versions().map { it.version })
        val next = store.save(first.fingerprint, CurrencyCode.USD, price("two"))
        assertEquals(setOf("one", "two"), next.entries.map { it.modelKey }.toSet())
        assertEquals(first.version + 1, next.version)
        assertEquals(first.fingerprint, store.load(first.version).fingerprint)
        assertEquals(next.fingerprint, store.load(next.version).fingerprint)
        assertEquals(setOf("one"), store.load(first.version).entries.map { it.modelKey }.toSet())
    }

    @Test fun everySaveCreatesAnImmutableDescribedVersionAndMovesThePointer() = runBlocking {
        val store = NativePriceTableStore(context)
        val first = store.save(store.load().fingerprint, CurrencyCode.USD, price("one"))
        val second = store.replace(PriceTable(CurrencyCode.of("EUR"), 99, listOf(price("two").copy(
            input = TokenPrice.ofMicrosPerMillion(9)))))
        assertEquals(2L, store.currentVersion())
        assertEquals(listOf(1L, 2L), store.versions().map { it.version })
        val versions = store.versions()
        assertEquals(first.fingerprint, versions[0].fingerprint.sha256)
        assertEquals(second.fingerprint, versions[1].fingerprint.sha256)
        assertEquals(CurrencyCode.USD, versions[0].currency)
        assertEquals(CurrencyCode.of("EUR"), versions[1].currency)
        assertFalse(versions[0].current)
        assertTrue(versions[1].current)
        assertTrue(versions.all { it.createdAt >= 0L })
        assertEquals("0.000001000000", priceQuote(store.load(1), "one"))
        assertEquals("0.000009000000", priceQuote(store.load(2), "two"))
    }

    @Test fun historyRetentionIsBoundedButCurrentAndRecentVersionsRemain() = runBlocking {
        val store = NativePriceTableStore(context)
        var current = store.load()
        repeat(NativePriceTableStore.MAX_HISTORY_VERSIONS + 8) { index ->
            current = store.save(current.fingerprint, CurrencyCode.USD, price("model-$index"))
        }
        val expected = (NativePriceTableStore.MAX_HISTORY_VERSIONS + 8).toLong()
        assertEquals(expected, store.currentVersion())
        val versions = store.versions()
        assertEquals(NativePriceTableStore.MAX_HISTORY_VERSIONS, versions.size)
        assertEquals(expected, versions.last().version)
        assertEquals(expected - NativePriceTableStore.MAX_HISTORY_VERSIONS + 1, versions.first().version)
        val files = File(context.filesDir, "native-model-prices-history").listFiles()
            ?.count { it.name.startsWith("v") && it.name.endsWith(".json") }
        assertEquals(NativePriceTableStore.MAX_HISTORY_VERSIONS, files ?: -1)
        assertEquals(current.fingerprint, store.load(expected).fingerprint)
        try { store.load(versions.first().version - 1); fail("Loaded a pruned price version") }
        catch (_: FileNotFoundException) { }
        assertEquals(expected, store.versions().last().version)
    }

    @Test fun damagedOrMissingHistoryFailsExplicitlyAndNeverFallsBackToCurrent() = runBlocking {
        val store = NativePriceTableStore(context)
        val first = store.save(store.load().fingerprint, CurrencyCode.USD, price("one"))
        val second = store.save(first.fingerprint, CurrencyCode.USD, price("two"))
        val history = File(context.filesDir, "native-model-prices-history/v${first.version}.json")
        assertTrue(history.isFile)
        history.writeText("broken")
        try { store.load(first.version); fail("Loaded damaged history") }
        catch (_: IllegalArgumentException) { }
        assertEquals(second.fingerprint, store.load().fingerprint)
        assertTrue(history.delete())
        File(history.path + ".bak").delete()
        try { store.load(first.version); fail("Loaded missing history") }
        catch (_: FileNotFoundException) { }
        assertEquals(second.fingerprint, store.load(second.version).fingerprint)
    }

    @Test fun failedSaveDoesNotAdvanceThePointerOrCreateACommittedVersion() = runBlocking {
        val store = NativePriceTableStore(context)
        val first = store.save(store.load().fingerprint, CurrencyCode.USD, price("one"))
        val versions = store.versions()
        try { store.save("0".repeat(64), CurrencyCode.USD, price("two")); fail("Accepted stale table") }
        catch (_: IllegalArgumentException) { }
        assertEquals(first.fingerprint, store.load().fingerprint)
        assertEquals(1L, store.currentVersion())
        assertEquals(versions.map { it.version }, store.versions().map { it.version })
        assertEquals(setOf("one"), store.load().entries.map { it.modelKey }.toSet())
    }

    @Test fun damagedTableIsNotSilentlyReplacedWithEmptyPrices() = runBlocking {
        val file = File(context.filesDir, "native-model-prices.json")
        file.writeText("broken")
        val store = NativePriceTableStore(context)
        try { store.load(); fail("Accepted damaged prices") } catch (_: IllegalArgumentException) { }
        try { store.save(PriceTable(CurrencyCode.USD, 0, emptyList()).fingerprint, CurrencyCode.USD, price("one"))
            fail("Overwrote damaged prices") } catch (_: IllegalArgumentException) { }
        assertEquals("broken", file.readText())
    }

    private fun priceQuote(table: PriceTable, model: String): String {
        val usage = RequestUsage(
            TokenMeasurement(1_000_000, UsageSource.ACTUAL),
            TokenMeasurement(0, UsageSource.ACTUAL),
            TokenMeasurement(0, UsageSource.ACTUAL),
            TokenMeasurement(0, UsageSource.ACTUAL),
            TokenMeasurement(0, UsageSource.ACTUAL),
        )
        val quote = com.breakyuna.noveltranslator.nativecore.engine.UsageCostCalculator(table)
            .quote(model, usage) as CostQuote.Priced
        return quote.actual.knownTotal.toPlainString()
    }
}
