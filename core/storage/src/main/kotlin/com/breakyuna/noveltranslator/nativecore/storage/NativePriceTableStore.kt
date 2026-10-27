package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.io.FileNotFoundException
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Immutable descriptor of one committed price table; [current] marks the active pointer. */
data class PriceTableVersion(
    val version: Long,
    val currency: CurrencyCode,
    val fingerprint: ObjectFingerprint,
    val createdAt: Long,
    val current: Boolean,
) {
    init {
        require(version in 0L..PriceTable.MAX_VERSION) { "Price table version out of range" }
        require(createdAt >= 0L) { "Invalid price table timestamp" }
    }
}

/**
 * User supplied prices; editing never changes usage receipts or triggers model requests.
 *
 * The active table stays in `native-model-prices.json` so existing load/export/backup semantics
 * are unchanged. Every save/replace additionally writes an immutable history table and updates
 * the bounded manifest before atomically moving the current pointer. Loading a version that is
 * absent, uncommitted or damaged fails explicitly instead of falling back to current prices.
 */
class NativePriceTableStore(context: Context) : PriceTableVersionSource {
    private val filesDir = context.filesDir
    private val file = AtomicFile(File(filesDir, CURRENT_FILE_NAME))
    private val manifest = AtomicFile(File(filesDir, HISTORY_MANIFEST_NAME))
    private val historyDir = File(filesDir, HISTORY_DIRECTORY_NAME)

    suspend fun load(): PriceTable = withContext(Dispatchers.IO) { synchronized(LOCK) {
        readTable(file) ?: PriceTable(CurrencyCode.USD, 0, emptyList())
    } }

    /** The version persisted by the current pointer, or null when no table was ever installed. */
    suspend fun currentVersion(): Long? = withContext(Dispatchers.IO) { synchronized(LOCK) {
        readTable(file)?.version
    } }

    /** Committed history in ascending order; the newest entry is always [current]. */
    suspend fun versions(): List<PriceTableVersion> = withContext(Dispatchers.IO) { synchronized(LOCK) {
        val current = readTable(file) ?: return@synchronized emptyList()
        val recorded = readManifest(manifest)
        if (recorded == null || current.version == 0L) {
            return@synchronized listOf(PriceTableVersion(current.version, current.currency,
                ObjectFingerprint(current.fingerprint), createdAt(file), true))
        }
        val committed = recorded.filter { it.version <= current.version }
            .sortedBy { it.version }
            .takeLast(MAX_HISTORY_VERSIONS)
        val active = committed.firstOrNull { it.version == current.version }
            ?: throw IllegalStateException("Current price version is missing from history")
        require(active.currency == current.currency && active.fingerprint.sha256 == current.fingerprint) {
            "Current price versions do not match"
        }
        committed.map { it.copy(current = it.version == current.version) }
    } }

    /** Exact historical table; a missing/removed/damaged version is never replaced by current prices. */
    override suspend fun loadPriceTable(version: Long): PriceTable = load(version)

    suspend fun load(version: Long): PriceTable = withContext(Dispatchers.IO) { synchronized(LOCK) {
        require(version in 0L..PriceTable.MAX_VERSION) { "Price table version out of range" }
        val current = readTable(file) ?: throw FileNotFoundException("Price table is missing")
        require(version <= current.version) { "Price version is newer than the current table" }
        val recorded = readManifest(manifest)
        if (version == current.version) {
            if (version > 0L && recorded != null) {
                val active = recorded.firstOrNull { it.version == version }
                    ?: throw IllegalStateException("Current price version is missing from history")
                require(active.currency == current.currency && active.fingerprint.sha256 == current.fingerprint) {
                    "Current price versions do not match"
                }
            }
            return@synchronized current
        }
        require(version > 0L) { "Price version is not recorded" }
        val descriptor = recorded?.firstOrNull { it.version == version }
            ?: throw FileNotFoundException("Price version $version is not recorded")
        val table = readTable(AtomicFile(historyFile(version)))
            ?: throw FileNotFoundException("Price version $version is missing")
        require(table.version == version) { "Price version does not match its history file" }
        require(table.currency == descriptor.currency &&
            table.fingerprint == descriptor.fingerprint.sha256) {
            "Price version $version is damaged"
        }
        table
    } }

    /** Reject a stale editor rather than silently replacing another saved model price. */
    suspend fun save(expected: String, currency: CurrencyCode, pricing: ModelPricing): PriceTable =
        withContext(Dispatchers.IO) { synchronized(LOCK) {
            val previous = readTable(file) ?: PriceTable(CurrencyCode.USD, 0, emptyList())
            require(previous.fingerprint == expected) { "Price table changed" }
            require(previous.entries.isEmpty() || previous.currency == currency) { "Currency mismatch" }
            val next = PriceTable(currency, Math.addExact(previous.version, 1L),
                previous.entries.filterNot { it.modelKey == pricing.modelKey } + pricing)
            commit(previous, next)
        } }

    /** Canonical non-secret JSON for application backup/export. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) { synchronized(LOCK) {
        (readTable(file) ?: PriceTable(CurrencyCode.USD, 0, emptyList())).canonicalJson
    } }

    /**
     * Replaces every model price as one atomic table. The version always advances so an old
     * editor or cost quote cannot reuse the previous table reference.
     */
    suspend fun replace(table: PriceTable): PriceTable = withContext(Dispatchers.IO) { synchronized(LOCK) {
        val previous = readTable(file) ?: PriceTable(CurrencyCode.USD, 0, emptyList())
        val next = PriceTable(table.currency, Math.addExact(previous.version, 1L), table.entries)
        commit(previous, next)
    } }

    /** Writes history first, then the bounded manifest, and only then moves the current pointer. */
    private fun commit(previous: PriceTable, next: PriceTable): PriceTable {
        require(next.version > previous.version) { "Price version did not advance" }
        val recorded = readManifest(manifest)
        val committed = ArrayList<PriceTableVersion>()
        if (recorded == null) {
            if (previous.version > 0L && fileExists(file)) {
                val descriptor = PriceTableVersion(previous.version, previous.currency,
                    ObjectFingerprint(previous.fingerprint), createdAt(file), false)
                writeHistory(previous, overwrite = false)
                committed += descriptor
            }
        } else {
            // A pointer may lag by at most the table whose manifest write preceded a failed commit.
            require(recorded.none { it.version > previous.version + 1L }) {
                "Price history is ahead of the current pointer"
            }
            recorded.filterTo(committed) { it.version <= previous.version }
            if (previous.version > 0L && fileExists(file)) {
                val active = committed.firstOrNull { it.version == previous.version }
                    ?: throw IllegalStateException("Current price version is missing from history")
                require(active.currency == previous.currency &&
                    active.fingerprint.sha256 == previous.fingerprint) {
                    "Current price versions do not match"
                }
            }
        }
        writeHistory(next, overwrite = true)
        val createdAt = maxOf(System.currentTimeMillis().coerceAtLeast(0L),
            committed.maxOfOrNull { it.createdAt } ?: 0L)
        committed += PriceTableVersion(next.version, next.currency,
            ObjectFingerprint(next.fingerprint), createdAt, true)
        val retained = committed.sortedBy { it.version }.takeLast(MAX_HISTORY_VERSIONS)
        require(retained.any { it.version == next.version }) { "Price history lost the new version" }
        writeManifest(retained)
        writeTable(file, next)
        // The pointer is committed; a pruning failure must not report the successful save as lost.
        try { deleteUnretainedHistory(retained.mapTo(HashSet<Long>()) { it.version }) } catch (_: Exception) { }
        return next
    }

    private fun readManifest(file: AtomicFile): List<PriceTableVersion>? {
        val text = readText(file, HISTORY_MANIFEST_MAX_CHARS) ?: return null
        return decodeManifest(text)
    }

    private fun writeManifest(entries: List<PriceTableVersion>) {
        writeText(manifest, encodeManifest(entries))
    }

    private fun writeHistory(table: PriceTable, overwrite: Boolean) {
        if (!historyDir.isDirectory && !historyDir.mkdirs()) {
            throw IllegalStateException("Cannot create price history")
        }
        val target = AtomicFile(historyFile(table.version))
        if (target.baseFile.exists() && !overwrite) {
            val existing = readTable(target)
                ?: throw FileNotFoundException("Price version ${table.version} is missing")
            require(existing.version == table.version && existing.fingerprint == table.fingerprint) {
                "Price version ${table.version} is already committed differently"
            }
            return
        }
        writeTable(target, table)
    }

    private fun deleteUnretainedHistory(retained: Set<Long>) {
        historyDir.listFiles()?.forEach { candidate ->
            val match = HISTORY_FILE_PATTERN.matchEntire(candidate.name) ?: return@forEach
            val version = match.groupValues[1].toLongOrNull() ?: return@forEach
            if (version !in retained) AtomicFile(candidate).delete()
        }
    }

    private fun historyFile(version: Long) = File(historyDir, "v$version.json")

    private fun createdAt(file: AtomicFile): Long =
        file.baseFile.lastModified().coerceAtLeast(0L)

    private fun fileExists(file: AtomicFile): Boolean =
        file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun encodeManifest(entries: List<PriceTableVersion>): String = buildJsonObject {
        put("format", HISTORY_FORMAT)
        put("formatVersion", HISTORY_FORMAT_VERSION)
        put("entries", JsonArray(entries.map { entry ->
            buildJsonObject {
                put("version", entry.version)
                put("currency", entry.currency.code)
                put("fingerprint", entry.fingerprint.sha256)
                put("createdAt", entry.createdAt)
            }
        }))
    }.toString()

    private fun decodeManifest(text: String): List<PriceTableVersion> {
        val root = JsonObjectValue.parse(text, JsonInputLimits(HISTORY_MANIFEST_MAX_CHARS, 8)).json
        require(root.keys == MANIFEST_ROOT_KEYS) { "Invalid price history fields" }
        require(string(root, "format") == HISTORY_FORMAT) { "Unsupported price history format" }
        require(long(root, "formatVersion", 1L, 1L) == 1L) { "Unsupported price history encoding" }
        val entries = root.getValue("entries") as? JsonArray
            ?: throw IllegalArgumentException("Invalid price history entries")
        require(entries.size <= MAX_HISTORY_VERSIONS + 1) { "Price history exceeds limit" }
        val decoded = entries.map { element ->
            val entry = element as? JsonObject ?: throw IllegalArgumentException("Invalid price history entry")
            require(entry.keys == MANIFEST_ENTRY_KEYS) { "Invalid price history entry fields" }
            PriceTableVersion(
                version = long(entry, "version", 0L, PriceTable.MAX_VERSION),
                currency = CurrencyCode.of(string(entry, "currency")),
                fingerprint = ObjectFingerprint(string(entry, "fingerprint")),
                createdAt = long(entry, "createdAt", 0L, Long.MAX_VALUE),
                current = false,
            )
        }
        require(decoded.map { it.version }.distinct().size == decoded.size) {
            "Duplicate price history version"
        }
        return decoded.sortedBy { it.version }
    }

    companion object {
        const val MAX_HISTORY_VERSIONS = 32

        private val MANIFEST_ROOT_KEYS = setOf("format", "formatVersion", "entries")
        private val MANIFEST_ENTRY_KEYS = setOf("version", "currency", "fingerprint", "createdAt")

        /** Snapshot used when a usage receipt is reserved; never cached across a save. */
        internal fun currentTable(filesDir: File): PriceTable? = synchronized(LOCK) {
            readTable(AtomicFile(File(filesDir, CURRENT_FILE_NAME)))
        }
    }
}

private const val CURRENT_FILE_NAME = "native-model-prices.json"
private const val HISTORY_MANIFEST_NAME = "native-model-prices-history.json"
private const val HISTORY_DIRECTORY_NAME = "native-model-prices-history"
private const val HISTORY_FORMAT = "lnt-price-history"
private const val HISTORY_FORMAT_VERSION = 1L
private const val HISTORY_MANIFEST_MAX_CHARS = 65_536
private val HISTORY_FILE_PATTERN = Regex("v([0-9]{1,16})\\.json")
private val LOCK = Any()

private fun readText(file: AtomicFile, maxChars: Int): String? {
    val input = try { file.openRead() } catch (missing: FileNotFoundException) {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw missing
        return null
    }
    return input.use {
        val bytes = ByteArray(maxChars * 4 + 1)
        var size = 0
        while (size < bytes.size) {
            val count = it.read(bytes, size, bytes.size - size)
            if (count < 0) break
            size += count
        }
        require(size < bytes.size) { "Price data too large" }
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, 0, size)).toString()
    }
}

private fun readTable(file: AtomicFile): PriceTable? =
    readText(file, PriceTableCodec.MAX_CHARS)?.let { PriceTableCodec.decode(it) }

private fun writeText(file: AtomicFile, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val output = file.startWrite()
    try { output.write(bytes); file.finishWrite(output) }
    catch (failure: Throwable) { file.failWrite(output); throw failure }
}

private fun writeTable(file: AtomicFile, table: PriceTable) = writeText(file, PriceTableCodec.encode(table))

private fun string(obj: JsonObject, key: String): String {
    val primitive = obj.getValue(key) as? JsonPrimitive
        ?: throw IllegalArgumentException("Invalid string field")
    require(primitive.isString) { "String field must be a string" }
    return primitive.content
}

private fun long(obj: JsonObject, key: String, minimum: Long, maximum: Long): Long {
    val primitive = obj.getValue(key) as? JsonPrimitive
        ?: throw IllegalArgumentException("Invalid numeric field")
    require(!primitive.isString) { "Numeric field must be a number" }
    val decimal = try { BigDecimal(primitive.content) }
    catch (_: NumberFormatException) { throw IllegalArgumentException("Invalid numeric field") }
    require(decimal.signum() >= 0 && decimal <= BigDecimal.valueOf(maximum)) {
        "Numeric field out of range"
    }
    val normalized = decimal.stripTrailingZeros()
    require(normalized.scale() <= 0) { "Numeric field must be an integer" }
    val value = normalized.toBigIntegerExact()
    require(value >= BigInteger.valueOf(minimum)) { "Numeric field out of range" }
    return value.toLong()
}
