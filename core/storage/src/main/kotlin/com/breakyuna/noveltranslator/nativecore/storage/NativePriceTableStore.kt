package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** User supplied prices; editing never changes usage receipts or triggers model requests. */
class NativePriceTableStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "native-model-prices.json"))

    suspend fun load(): PriceTable = withContext(Dispatchers.IO) { synchronized(LOCK) { read() } }

    /** Reject a stale editor rather than silently replacing another saved model price. */
    suspend fun save(expected: String, currency: CurrencyCode, pricing: ModelPricing): PriceTable =
        withContext(Dispatchers.IO) { synchronized(LOCK) {
            val previous = read()
            require(previous.fingerprint == expected) { "Price table changed" }
            require(previous.entries.isEmpty() || previous.currency == currency) { "Currency mismatch" }
            val next = PriceTable(currency, Math.addExact(previous.version, 1L),
                previous.entries.filterNot { it.modelKey == pricing.modelKey } + pricing)
            val bytes = PriceTableCodec.encode(next).toByteArray(Charsets.UTF_8)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (failure: Throwable) { file.failWrite(output); throw failure }
            next
        } }

    /** Canonical non-secret JSON for application backup/export. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) { synchronized(LOCK) { read().canonicalJson } }

    /**
     * Replaces every model price as one atomic table. The version always advances so an old
     * editor or cost quote cannot reuse the previous table reference.
     */
    suspend fun replace(table: PriceTable): PriceTable = withContext(Dispatchers.IO) { synchronized(LOCK) {
        val previous = read()
        val next = PriceTable(table.currency, Math.addExact(previous.version, 1L), table.entries)
        val bytes = PriceTableCodec.encode(next).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        next
    } }

    private fun read(): PriceTable {
        val input = try { file.openRead() } catch (missing: java.io.FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw missing
            return PriceTable(CurrencyCode.USD, 0, emptyList())
        }
        return input.use {
            val bytes = ByteArray(PriceTableCodec.MAX_CHARS * 4 + 1)
            var size = 0
            while (size < bytes.size) {
                val count = it.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            require(size < bytes.size) { "Price table too large" }
            PriceTableCodec.decode(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, size)).toString())
        }
    }

    private companion object { val LOCK = Any() }
}
