package com.breakyuna.noveltranslator.nativeapp

import android.content.Context

internal data class NativeAppPreferences(
    val themeMode: NativeThemeMode = NativeThemeMode.SYSTEM,
    val automaticContextSummary: Boolean = false,
    val diagnosticsIncludeDetails: Boolean = false,
    val language: NativeAppLanguage = NativeAppLanguage.ENGLISH,
    val appPersona: String = "",
)

/** Application appearance; project translation settings remain project facts. */
enum class NativeThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    companion object {
        fun parse(value: String?): NativeThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/**
 * Small non-secret application preference store. Secrets and model endpoints never belong here;
 * they stay in the encrypted model settings store.
 */
internal class NativeAppSettingsStore(context: Context) {
    private val preferences = (context.applicationContext ?: context)
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun themeMode(): NativeThemeMode = NativeThemeMode.parse(
        runCatching { preferences.getString(KEY_THEME, null) }.getOrNull())

    fun saveThemeMode(mode: NativeThemeMode) = synchronized(SETTINGS_LOCK) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
    }

    /** Explicit opt-in covers the extra summary request before a user-started Agent turn. */
    fun automaticContextSummary(): Boolean = runCatching {
        preferences.getBoolean(KEY_AUTO_CONTEXT, false)
    }.getOrDefault(false)

    fun saveAutomaticContextSummary(enabled: Boolean) = synchronized(SETTINGS_LOCK) {
        check(preferences.edit().putBoolean(KEY_AUTO_CONTEXT, enabled).commit()) { "Settings could not be saved" }
    }

    /**
     * Debug detail storage is off unless the user explicitly opts in. The setting only affects
     * diagnostics appended afterwards; existing records are neither upgraded nor erased.
     */
    fun diagnosticsIncludeDetails(): Boolean = runCatching {
        preferences.getBoolean(KEY_DIAGNOSTICS_DETAILS, false)
    }.getOrDefault(false)

    fun saveDiagnosticsIncludeDetails(enabled: Boolean) = synchronized(SETTINGS_LOCK) {
        check(preferences.edit().putBoolean(KEY_DIAGNOSTICS_DETAILS, enabled).commit()) {
            "Settings could not be saved"
        }
    }

    /**
     * The interface language must be available synchronously in `attachBaseContext`, before
     * Activity resources exist. A committed string is therefore persisted with commit().
     */
    fun language(): NativeAppLanguage = NativeAppLanguage.parse(
        runCatching { preferences.getString(KEY_LANGUAGE, null) }.getOrNull())

    fun saveLanguage(language: NativeAppLanguage) = synchronized(SETTINGS_LOCK) {
        check(preferences.edit().putString(KEY_LANGUAGE, language.name).commit()) { "Language could not be saved" }
    }

    /** Application-level Agent style preference; blank means no persona block is added. */
    fun appPersona(): String = NativeAppPersona.normalize(
        runCatching { preferences.getString(KEY_APP_PERSONA, null) }.getOrNull(),
    )

    fun saveAppPersona(value: String) = synchronized(SETTINGS_LOCK) {
        val normalized = NativeAppPersona.requireValid(value)
        check(preferences.edit().putString(KEY_APP_PERSONA, normalized).commit()) {
            "Persona could not be saved"
        }
    }

    fun snapshot(): NativeAppPreferences = synchronized(SETTINGS_LOCK) { NativeAppPreferences(
        themeMode = themeMode(),
        automaticContextSummary = automaticContextSummary(),
        diagnosticsIncludeDetails = diagnosticsIncludeDetails(),
        language = language(),
        appPersona = appPersona(),
    ) }

    /** One committed editor keeps the non-secret preference set mutually consistent. */
    fun restore(preferences: NativeAppPreferences) = synchronized(SETTINGS_LOCK) {
        val normalizedPersona = NativeAppPersona.requireValid(preferences.appPersona)
        check(this.preferences.edit()
            .putString(KEY_THEME, preferences.themeMode.name)
            .putBoolean(KEY_AUTO_CONTEXT, preferences.automaticContextSummary)
            .putBoolean(KEY_DIAGNOSTICS_DETAILS, preferences.diagnosticsIncludeDetails)
            .putString(KEY_LANGUAGE, preferences.language.name)
            .putString(KEY_APP_PERSONA, normalizedPersona)
            .commit()) { "Settings could not be restored" }
    }

    fun replaceReviewed(expected: String, next: NativeAppPreferences) = synchronized(SETTINGS_LOCK) {
        require(NativeAgentPreferencesCodec.fingerprint(snapshot()) == expected) { "Preferences changed" }
        restore(next)
    }

    private companion object {
        val SETTINGS_LOCK = Any()
        const val FILE = "native_app_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_AUTO_CONTEXT = "automatic_context_summary"
        const val KEY_LANGUAGE = "app_language"
        const val KEY_DIAGNOSTICS_DETAILS = "diagnostics_include_details"
        const val KEY_APP_PERSONA = "app_persona"
    }
}
