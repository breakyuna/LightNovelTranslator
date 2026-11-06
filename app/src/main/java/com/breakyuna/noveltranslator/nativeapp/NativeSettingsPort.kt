package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelHttpProxy
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsCodec
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimitsCodec
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelNetworkSettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeRequestLimitsStore

/** BASIC and EXPERT are sections of one routed app-settings surface. */
enum class NativeSettingsSection { BASIC, EXPERT, LABORATORY }

/**
 * Credential-free expert form values. Parsing mirrors the domain validation instead of
 * trusting the UI: every timeout is a whole number in 1..600, and a proxy is either fully
 * absent or a valid host plus port. Userinfo, schemes and paths never become a proxy value.
 */
data class NativeNetworkSettingsInput(
    val connectTimeoutSeconds: String,
    val readTimeoutSeconds: String,
    val writeTimeoutSeconds: String,
    val callTimeoutSeconds: String,
    val proxyHost: String = "",
    val proxyPort: String = "",
    val followRedirects: Boolean = false,
) {
    fun toSettings(): ModelNetworkSettings {
        val connect = timeout(connectTimeoutSeconds)
        val read = timeout(readTimeoutSeconds)
        val write = timeout(writeTimeoutSeconds)
        val call = timeout(callTimeoutSeconds)
        val host = proxyHost.trim()
        val port = proxyPort.trim()
        val proxy = when {
            host.isEmpty() && port.isEmpty() -> null
            host.isEmpty() || port.isEmpty() -> throw InvalidModelNetworkSettings()
            else -> ModelHttpProxy.of(host, proxyPortNumber(port))
        }
        return ModelNetworkSettings(connect, read, write, call, proxy, followRedirects)
    }

    companion object {
        fun of(settings: ModelNetworkSettings) = NativeNetworkSettingsInput(
            connectTimeoutSeconds = settings.connectTimeoutSeconds.toString(),
            readTimeoutSeconds = settings.readTimeoutSeconds.toString(),
            writeTimeoutSeconds = settings.writeTimeoutSeconds.toString(),
            callTimeoutSeconds = settings.callTimeoutSeconds.toString(),
            proxyHost = settings.proxy?.host.orEmpty(),
            proxyPort = settings.proxy?.port?.toString().orEmpty(),
            followRedirects = settings.followRedirects,
        )

        internal fun timeout(value: String): Int {
            val text = value.trim()
            if (!DIGITS.matches(text)) throw InvalidModelNetworkSettings()
            val parsed = text.toIntOrNull() ?: throw InvalidModelNetworkSettings()
            if (parsed !in ModelNetworkSettings.MIN_TIMEOUT_SECONDS..ModelNetworkSettings.MAX_TIMEOUT_SECONDS) {
                throw InvalidModelNetworkSettings()
            }
            return parsed
        }

        internal fun proxyPortNumber(value: String): Int {
            val text = value.trim()
            if (!DIGITS.matches(text)) throw InvalidModelNetworkSettings()
            val parsed = text.toIntOrNull() ?: throw InvalidModelNetworkSettings()
            if (parsed !in 1..65_535) throw InvalidModelNetworkSettings()
            return parsed
        }

        private val DIGITS = Regex("[0-9]{1,5}")
    }
}

/** Global scheduler form values; bounds match [ModelRequestLimits] exactly. */
data class NativeRequestLimitsInput(
    val concurrency: String,
    val requestsPerMinute: String,
) {
    fun toLimits(): ModelRequestLimits {
        val fixedConcurrency = integer(concurrency, 1, 16)
        val fixedRpm = integer(requestsPerMinute, 0, 60_000)
        return ModelRequestLimits(fixedConcurrency, fixedRpm)
    }

    companion object {
        fun of(limits: ModelRequestLimits) = NativeRequestLimitsInput(
            concurrency = limits.concurrency.toString(),
            requestsPerMinute = limits.requestsPerMinute.toString(),
        )

        private fun integer(value: String, minimum: Int, maximum: Int): Int {
            val text = value.trim()
            if (!DIGITS.matches(text)) throw IllegalArgumentException("Invalid request limit")
            val parsed = text.toIntOrNull() ?: throw IllegalArgumentException("Invalid request limit")
            require(parsed in minimum..maximum) { "Request limit out of range" }
            return parsed
        }

        private val DIGITS = Regex("[0-9]{1,5}")
    }
}

/** Load/save status for the network form. Fingerprints are content hashes, never secrets. */
data class NativeNetworkSettingsState(
    val settings: ModelNetworkSettings = ModelNetworkSettings.DEFAULT,
    val fingerprint: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val conflict: Boolean = false,
    val invalid: Boolean = false,
    /** Incremented only when the form should adopt the stored values (load or successful save). */
    val revision: Long = 0,
)

/** Load/save status for the global request limits. */
data class NativeRequestLimitsState(
    val limits: ModelRequestLimits = ModelRequestLimits(),
    val fingerprint: String? = null,
    val loaded: Boolean = false,
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val conflict: Boolean = false,
    val invalid: Boolean = false,
    val revision: Long = 0,
)

internal data class NativeLoadedNetworkSettings(
    val settings: ModelNetworkSettings,
    val fingerprint: String?,
)

internal data class NativeLoadedRequestLimits(
    val limits: ModelRequestLimits,
    val fingerprint: String?,
)

/**
 * Narrow application boundary over the two already-present non-secret stores. Fingerprints
 * are passed back on save so a stale form cannot overwrite a newer policy. Tests substitute
 * an in-memory implementation; production always uses the AtomicFile stores.
 */
internal interface NativeSettingsPort {
    suspend fun loadNetworkSettings(): NativeLoadedNetworkSettings
    suspend fun saveNetworkSettings(settings: ModelNetworkSettings, expectedFingerprint: String?): NativeLoadedNetworkSettings
    suspend fun loadRequestLimits(): NativeLoadedRequestLimits
    suspend fun saveRequestLimits(limits: ModelRequestLimits, expectedFingerprint: String?): NativeLoadedRequestLimits
}

internal class NativeStoredSettingsPort(
    private val network: NativeModelNetworkSettingsStore,
    private val limits: NativeRequestLimitsStore,
) : NativeSettingsPort {
    override suspend fun loadNetworkSettings(): NativeLoadedNetworkSettings {
        // Read the revision identity before the value so an edit racing this load causes the
        // subsequent save to fail its fingerprint check instead of overwriting newer state.
        val fingerprint = network.fingerprint()
        return NativeLoadedNetworkSettings(network.load(), fingerprint)
    }

    override suspend fun saveNetworkSettings(settings: ModelNetworkSettings, expectedFingerprint: String?): NativeLoadedNetworkSettings {
        val saved = network.save(settings, expectedFingerprint)
        return NativeLoadedNetworkSettings(saved, ModelNetworkSettingsCodec.fingerprint(saved))
    }

    override suspend fun loadRequestLimits(): NativeLoadedRequestLimits {
        val fingerprint = limits.fingerprint()
        return NativeLoadedRequestLimits(limits.load(), fingerprint)
    }

    override suspend fun saveRequestLimits(limits: ModelRequestLimits, expectedFingerprint: String?): NativeLoadedRequestLimits {
        val saved = this.limits.save(limits, expectedFingerprint)
        return NativeLoadedRequestLimits(saved, ModelRequestLimitsCodec.fingerprint(saved))
    }
}
