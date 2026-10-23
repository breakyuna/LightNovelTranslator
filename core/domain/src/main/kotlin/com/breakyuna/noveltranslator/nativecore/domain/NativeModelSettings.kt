package com.breakyuna.noveltranslator.nativecore.domain

class NativeModelSettings(val reference: ModelConfigurationRef, val endpoint: String, val model: String, val hasCredential: Boolean = true)
/** Credentials never enter configuration snapshots, requests, history or debug output. */
interface NativeModelSettingsPort {
    suspend fun current(): NativeModelSettings?
    suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings
    suspend fun credential(reference: ModelConfigurationRef): String
    /** null explicitly reuses the current credential at the same normalized endpoint; empty means no auth. */
    suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings
}

interface NativeModelCatalogPort {
    /** Explicit metadata request only. A stored credential may only be used at its own endpoint. */
    suspend fun models(endpoint: String, key: String, savedCredential: ModelConfigurationRef? = null): List<String>
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
