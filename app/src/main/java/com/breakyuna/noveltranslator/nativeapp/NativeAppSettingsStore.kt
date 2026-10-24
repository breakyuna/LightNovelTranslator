package com.breakyuna.noveltranslator.nativeapp

import android.content.Context

/** Appearance is the only app-level preference wired so far; other settings stay project facts. */
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
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun themeMode(): NativeThemeMode = NativeThemeMode.parse(preferences.getString(KEY_THEME, null))

    fun saveThemeMode(mode: NativeThemeMode) {
        preferences.edit().putString(KEY_THEME, mode.name).apply()
    }

    private companion object {
        const val FILE = "native_app_settings"
        const val KEY_THEME = "theme_mode"
    }
}
