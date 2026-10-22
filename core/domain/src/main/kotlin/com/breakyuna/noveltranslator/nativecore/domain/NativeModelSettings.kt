package com.breakyuna.noveltranslator.nativecore.domain

class NativeModelSettings(val reference: ModelConfigurationRef, val endpoint: String, val model: String)
/** Credentials never enter configuration snapshots, requests, history or debug output. */
interface NativeModelSettingsPort {
    suspend fun current(): NativeModelSettings?
    suspend fun resolve(reference: ModelConfigurationRef): NativeModelSettings
    suspend fun credential(reference: ModelConfigurationRef): String
    suspend fun save(endpoint: String, model: String, key: String): NativeModelSettings
}

class InvalidModelSettings : IllegalArgumentException("Invalid model settings")

/** Shared by the settings form and storage; never includes submitted values in errors. */
object NativeModelSettingsValidation {
    fun validate(endpoint: String, model: String, key: String) {
        val uri = try { java.net.URI(endpoint.trim()) } catch (_: Exception) { throw InvalidModelSettings() }
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || endpoint.length > 2048 ||
            model.isBlank() || model.length > 200 || model.any(Char::isISOControl) ||
            key.isBlank() || key.length > 8192 || key.any { it.code !in 33..126 }) throw InvalidModelSettings()
        try { TextProjectFingerprint.utf8(model) } catch (_: Exception) { throw InvalidModelSettings() }
    }
}
