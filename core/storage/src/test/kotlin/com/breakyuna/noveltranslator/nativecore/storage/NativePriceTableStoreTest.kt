package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
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
    }
    private fun price(model: String) = ModelPricing(TokenPrice.ofMicrosPerMillion(1),
        TokenPrice.ofMicrosPerMillion(2), TokenPrice.ZERO, TokenPrice.ZERO, modelKey = model)

    @Test fun reopensAndRejectsStaleEditorAndCurrencyChanges() = runBlocking {
        val store = NativePriceTableStore(context)
        val empty = store.load()
        assertTrue(empty.entries.isEmpty())
        val first = store.save(empty.fingerprint, CurrencyCode.USD, price("one"))
        assertEquals(first.fingerprint, NativePriceTableStore(context).load().fingerprint)
        try { store.save(empty.fingerprint, CurrencyCode.USD, price("two")); fail("Accepted stale table") }
        catch (_: IllegalArgumentException) { }
        try { store.save(first.fingerprint, CurrencyCode.of("EUR"), price("two")); fail("Changed currency") }
        catch (_: IllegalArgumentException) { }
        assertEquals(first.fingerprint, store.load().fingerprint)
        val next = store.save(first.fingerprint, CurrencyCode.USD, price("two"))
        assertEquals(setOf("one", "two"), next.entries.map { it.modelKey }.toSet())
        assertEquals(first.version + 1, next.version)
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
}
