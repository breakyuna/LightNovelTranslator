package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Wire protocol selected for a configuration. This is a domain fact, not an
 * engine detail: storage, UI and the gateway must agree on it without importing
 * the engine module. [com.breakyuna.noveltranslator.nativecore.engine.ModelProtocol]
 * is the matching wire implementation.
 */
enum class NativeModelProtocol {
    OPENAI_CHAT_COMPLETIONS,
    OPENAI_RESPONSES,
    GOOGLE_GEMINI,
    ANTHROPIC_MESSAGES,
    SAKURA_LLM,
}

class NativeModelSettings(
    val reference: ModelConfigurationRef,
    val endpoint: String,
    val model: String,
    val hasCredential: Boolean = true,
    val protocol: NativeModelProtocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
    val keyCount: Int = if (hasCredential) 1 else 0,
) {
    init {
        require(keyCount in 0..MAX_KEYS)
        require(hasCredential == (keyCount > 0)) { "Credential flag and key count disagree" }
    }

    companion object {
        const val MAX_KEYS = 256
    }
}

/** Credentials never enter configuration snapshots, requests, history or debug output. */
interface NativeModelSettingsPort {
    suspend fun current(): NativeModelSettings?
    suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings
    suspend fun credential(reference: ModelConfigurationRef): String

    /** Ordered credentials for one immutable configuration revision. */
    suspend fun credentials(reference: ModelConfigurationRef): List<String> = listOf(credential(reference))

    /** null explicitly reuses the current credential at the same normalized endpoint; empty means no auth. */
    suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings

    /**
     * Saves an explicit protocol and ordered credential list. The default keeps
     * existing test/fake ports source-compatible and only permits one chat key;
     * production storage overrides it.
     */
    suspend fun saveProfile(endpoint: String, model: String, protocol: NativeModelProtocol,
        keys: List<String>): NativeModelSettings {
        require(protocol == NativeModelProtocol.OPENAI_CHAT_COMPLETIONS && keys.size <= 1)
        return save(endpoint, model, keys.firstOrNull())
    }
}

interface NativeModelCatalogPort {
    /** Explicit metadata request only. A stored credential may only be used at its own endpoint. */
    suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef? = null): List<String>

    /** Protocol-aware discovery; implementations without a protocol list keep the OpenAI path. */
    suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef?,
        protocol: NativeModelProtocol): List<String> = models(endpoint, key, savedCredential)
}

class InvalidModelSettings : IllegalArgumentException("Invalid model settings")

/** Shared by the settings form and storage; never includes submitted values in errors. */
object NativeModelSettingsValidation {
    fun validate(endpoint: String, model: String, key: String) {
        NativeModelEndpoint.normalize(endpoint)
        if (model.isBlank() || model.length > 200 || model.any(Char::isISOControl)) throw InvalidModelSettings()
        validateKey(key)
        try { TextProjectFingerprint.utf8(model) } catch (_: Exception) { throw InvalidModelSettings() }
    }

    fun validateKey(key: String) {
        if (key.length > 8192 || key.any { it.code !in 33..126 }) throw InvalidModelSettings()
    }

    fun validateProfile(endpoint: String, model: String, keys: List<String>) {
        NativeModelEndpoint.normalize(endpoint)
        if (model.isBlank() || model.length > 200 || model.any(Char::isISOControl)) throw InvalidModelSettings()
        if (keys.size > NativeModelSettings.MAX_KEYS) throw InvalidModelSettings()
        keys.forEach(::validateKey)
        try { TextProjectFingerprint.utf8(model) } catch (_: Exception) { throw InvalidModelSettings() }
    }
}

/** OpenAI-compatible base URLs, including loopback, LAN, IPv6 and reverse-proxy prefixes. No DNS lookup. */
object NativeModelEndpoint {
    /** User input only: remove at most one operation suffix before storing the base. */
    fun normalize(input: String): String {
        val base = base(input)
        val path = java.net.URI(base).rawPath.orEmpty()
        val suffix = listOf("/chat/completions", "/models").firstOrNull { path.endsWith(it) }
        return if (suffix == null) base else base.removeSuffix(suffix)
    }

    /** Validate an already stored base without interpreting any of its path as an operation. */
    fun base(input: String): String {
        if (input.length > 2048 || input.any(Char::isISOControl)) throw InvalidModelSettings()
        val uri = try { java.net.URI(input.trim()) } catch (_: Exception) { throw InvalidModelSettings() }
        val scheme = uri.scheme?.lowercase()
        if (scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || uri.port < -1 || uri.port == 0 || uri.port > 65535 ||
            uri.rawAuthority?.endsWith(":") == true) throw InvalidModelSettings()
        val path = uri.rawPath.orEmpty().trimEnd('/')
        if (path.split('/').any { it == "." || it == ".." }) throw InvalidModelSettings()
        return "$scheme://${uri.rawAuthority}$path"
    }

    fun chat(endpoint: String) = base(endpoint) + "/chat/completions"
    fun models(endpoint: String) = base(endpoint) + "/models"
}
