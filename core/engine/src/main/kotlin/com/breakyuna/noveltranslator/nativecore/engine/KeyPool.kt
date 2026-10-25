package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelKeyState
import com.breakyuna.noveltranslator.nativecore.domain.ModelKeyPoolState
import com.breakyuna.noveltranslator.nativecore.domain.ModelKeyFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsValidation
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale
import java.util.TimeZone

/** The only failures that justify taking one credential out of rotation. */
enum class KeyFailureKind {
    AUTHENTICATION,
    RATE_LIMIT,
    SERVER,
    NETWORK;

    companion object {
        /** Timeout is a network-class failure for credential purposes; content/parsing failures are not. */
        fun classify(failure: ModelFailureKind): KeyFailureKind? = when (failure) {
            ModelFailureKind.AUTHENTICATION -> AUTHENTICATION
            ModelFailureKind.RATE_LIMIT -> RATE_LIMIT
            ModelFailureKind.SERVER -> SERVER
            ModelFailureKind.NETWORK, ModelFailureKind.TIMEOUT -> NETWORK
            else -> null
        }
    }
}

/**
 * One immutable credential fact. [toString] is safe for logs; the credential is
 * only available through the explicit [credential] property.
 */
class KeyEntry internal constructor(
    val id: String,
    val credential: String,
    val enabled: Boolean,
    val consecutiveFailures: Int,
    val cooldownUntilMillis: Long,
    val lastFailure: KeyFailureKind?,
) {
    init {
        require(id.isNotBlank() && id.length <= 64 && id.none(Char::isISOControl))
        require(credential.isNotEmpty())
        require(consecutiveFailures >= 0)
        require(cooldownUntilMillis >= 0)
    }

    fun isAvailable(nowMillis: Long): Boolean = enabled && cooldownUntilMillis <= nowMillis

    fun isCooling(nowMillis: Long): Boolean = enabled && cooldownUntilMillis > nowMillis

    override fun toString(): String =
        "KeyEntry(id=$id, enabled=$enabled, failures=$consecutiveFailures, cooldownUntilMillis=$cooldownUntilMillis, lastFailure=$lastFailure, credential=redacted)"
}

/**
 * A selected credential and the pool with its round-robin cursor advanced. The
 * caller must pass [pool] to later failure/success updates; mutating neither this
 * object nor [KeyEntry] is possible.
 */
class KeySelection internal constructor(val entry: KeyEntry, val pool: KeyPool) {
    val credential: String get() = entry.credential

    override fun toString(): String = "KeySelection(key=${entry.id}, pool=$pool)"
}

/**
 * Immutable multi-credential pool. This is a pure business model: it does not
 * start requests, log, or mutate a shared registry. The owner serializes
 * select/record operations under its own mutex and publishes the returned pool.
 *
 * Cooldown steps mirror LinguaGacha: 15 s, 30 s, 60 s, then 60 s. A server
 * `Retry-After` can only extend the local step. An authentication failure
 * disables the key immediately; only an explicit [setEnabled] call restores it.
 */
class KeyPool private constructor(entries: List<KeyEntry>, private val nextIndex: Int) {
    val entries: List<KeyEntry> = Collections.unmodifiableList(ArrayList(entries))

    init {
        require(entries.isEmpty() || nextIndex in entries.indices)
        require(this.entries.map { it.id }.toSet().size == this.entries.size)
    }

    val size: Int get() = entries.size

    val enabledCount: Int get() = entries.count { it.enabled }

    /** Credential-free durable facts; no endpoint or credential value is serialized. */
    fun state(configuration: ModelConfigurationRef): ModelKeyPoolState = ModelKeyPoolState(configuration, nextIndex,
        entries.map { ModelKeyState(it.enabled, it.consecutiveFailures, it.cooldownUntilMillis,
            it.lastFailure?.let { kind -> ModelKeyFailure.valueOf(kind.name) }) })

    /** Credentials remain owned by the current immutable revision; disk stores only ordinal facts. */
    fun restore(state: ModelKeyPoolState): KeyPool {
        require(state.keys.size == entries.size)
        return KeyPool(entries.mapIndexed { index, entry ->
            val saved = state.keys[index]
            KeyEntry(entry.id, entry.credential, saved.enabled, saved.failures, saved.cooldownUntilMillis,
                saved.lastFailure?.let { KeyFailureKind.valueOf(it.name) })
        }, state.nextIndex)
    }

    /**
     * Returns the next enabled, non-cooling key in round-robin order, or null when
     * every key is disabled/cooling. [KeySelection.pool] must be used for the next
     * operation so the cursor advances.
     */
    fun select(nowMillis: Long = System.currentTimeMillis()): KeySelection? {
        if (entries.isEmpty()) return null
        val now = nowMillis.coerceAtLeast(0)
        for (step in entries.indices) {
            val index = (nextIndex + step) % entries.size
            val entry = entries[index]
            if (entry.isAvailable(now)) {
                return KeySelection(entry, KeyPool(entries, (index + 1) % entries.size))
            }
        }
        return null
    }

    /** Earliest time at which an enabled key can be selected, or null if none is enabled. */
    fun nextAvailableAt(nowMillis: Long = System.currentTimeMillis()): Long? {
        val enabled = entries.filter { it.enabled }
        if (enabled.isEmpty()) return null
        val now = nowMillis.coerceAtLeast(0)
        val waiting = enabled.map { it.cooldownUntilMillis }.filter { it > now }
        return waiting.minOrNull() ?: now
    }

    fun recordSuccess(keyId: String): KeyPool = update(keyId) { entry ->
        KeyEntry(entry.id, entry.credential, entry.enabled, 0, 0, null)
    }

    /**
     * Records a classified failure. [retryAfterMillis] is the parsed 429 hint; it is
     * capped to one day and can never shorten the local cooldown. Auth failures
     * disable the entry and clear any pending cooldown.
     */
    fun recordFailure(
        keyId: String,
        kind: KeyFailureKind,
        nowMillis: Long = System.currentTimeMillis(),
        retryAfterMillis: Long? = null,
    ): KeyPool = update(keyId) { entry ->
        val failures = if (entry.consecutiveFailures == Int.MAX_VALUE) Int.MAX_VALUE
        else entry.consecutiveFailures + 1
        if (kind == KeyFailureKind.AUTHENTICATION) {
            KeyEntry(entry.id, entry.credential, false, failures, 0, kind)
        } else {
            val ladder = COOLDOWN_STEPS_MILLIS[(failures - 1).coerceAtMost(COOLDOWN_STEPS_MILLIS.lastIndex)]
            val serverDelay = retryAfterMillis
                ?.coerceIn(0L, RetryAfterHeader.MAX_DELAY_MILLIS)
                ?: 0L
            val delay = maxOf(ladder, serverDelay)
            val now = nowMillis.coerceAtLeast(0)
            val until = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
            KeyEntry(entry.id, entry.credential, entry.enabled, failures, until, kind)
        }
    }

    /** Re-enabling is an explicit operator action and starts a clean state. */
    fun setEnabled(keyId: String, enabled: Boolean): KeyPool = update(keyId) { entry ->
        if (enabled) KeyEntry(entry.id, entry.credential, true, 0, 0, null)
        else KeyEntry(entry.id, entry.credential, false, entry.consecutiveFailures, entry.cooldownUntilMillis, entry.lastFailure)
    }

    private fun update(keyId: String, transform: (KeyEntry) -> KeyEntry): KeyPool {
        val index = entries.indexOfFirst { it.id == keyId }
        require(index >= 0) { "Unknown key" }
        val updated = ArrayList(entries)
        updated[index] = transform(entries[index])
        return KeyPool(updated, nextIndex)
    }

    override fun toString(): String =
        "KeyPool(keys=${entries.joinToString { "${it.id}:${if (it.enabled) "enabled" else "disabled"}:${it.lastFailure ?: "none"}" }})"

    companion object {
        const val MAX_KEYS = 256
        val COOLDOWN_STEPS_MILLIS: List<Long> = Collections.unmodifiableList(listOf(15_000L, 30_000L, 60_000L))

        /**
         * Trims, drops blanks and duplicates like LinguaGacha's `collect_api_keys`,
         * then validates every key without echoing it. Empty input is an empty pool
         * (explicit no-auth mode), not a fake key.
         */
        fun of(secrets: List<String>): KeyPool {
            val values = secrets.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (values.size > MAX_KEYS) throw IllegalArgumentException("Too many credentials")
            values.forEach { NativeModelSettingsValidation.validateKey(it) }
            return KeyPool(values.mapIndexed { index, value ->
                KeyEntry("key-${index + 1}", value, true, 0, 0, null)
            }, 0)
        }

        fun fromRawCredential(raw: String): KeyPool =
            of(raw.split(Regex("\\r\\n|\\n|\\r")))
    }
}

/**
 * Bounded parser for the HTTP `Retry-After` header. Delta-seconds and RFC 1123
 * dates are accepted; absent, malformed and non-positive values return null. A
 * one-day ceiling prevents a hostile far-future date from pinning a key forever.
 */
object RetryAfterHeader {
    const val MAX_DELAY_MILLIS = 86_400_000L

    fun parseMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.all { it in '0'..'9' }) {
            val seconds = raw.toLongOrNull() ?: return MAX_DELAY_MILLIS
            if (seconds <= 0) return null
            return if (seconds > MAX_DELAY_MILLIS / 1_000) MAX_DELAY_MILLIS else seconds * 1_000
        }
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val position = ParsePosition(0)
        val date = format.parse(raw, position) ?: return null
        if (position.index != raw.length) return null
        val delay = date.time - nowMillis
        if (delay <= 0) return null
        return delay.coerceAtMost(MAX_DELAY_MILLIS)
    }
}
