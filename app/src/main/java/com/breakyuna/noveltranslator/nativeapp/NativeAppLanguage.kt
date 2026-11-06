package com.breakyuna.noveltranslator.nativeapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * The five interface languages backed by `native_strings.xml`. The stored value is a stable
 * enum name, never a translated label. A missing or malformed preference follows the system
 * language when it is one of the five, otherwise English.
 */
enum class NativeAppLanguage(val tag: String) {
    CHINESE("zh"),
    ENGLISH("en"),
    JAPANESE("ja"),
    KOREAN("ko"),
    GERMAN("de");

    companion object {
        fun parse(value: String?): NativeAppLanguage =
            entries.firstOrNull { it.name == value } ?: fromLocale(Locale.getDefault())

        fun fromLocale(locale: Locale): NativeAppLanguage = when (locale.language.lowercase(Locale.ROOT)) {
            "zh" -> CHINESE
            "ja" -> JAPANESE
            "ko" -> KOREAN
            "de" -> GERMAN
            else -> ENGLISH
        }
    }
}

/**
 * API 24-compatible locale override for [Activity.attachBaseContext]. No AppCompat is involved,
 * so the preference must be readable synchronously before the Activity's resources are built.
 */
internal fun localizedContext(base: Context, language: NativeAppLanguage): Context {
    val locale = Locale.forLanguageTag(language.tag)
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return base.createConfigurationContext(configuration)
}

/** Compose's LocalContext may be a theme wrapper; recreate must target the owning Activity. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
