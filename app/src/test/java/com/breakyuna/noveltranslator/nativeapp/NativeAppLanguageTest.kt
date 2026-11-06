package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The language preference is a stable name and the Activity context carries its locale. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeAppLanguageTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var previous: Locale

    @Before fun clearSettings() {
        previous = Locale.getDefault()
        context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun restoreLocale() {
        Locale.setDefault(previous)
        context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun everyLanguagePersistsAndUnknownValuesFollowTheSystem() {
        NativeAppLanguage.entries.forEach { language ->
            NativeAppSettingsStore(context).saveLanguage(language)
            assertEquals(language, NativeAppSettingsStore(context).language())
        }
        context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE).edit()
            .putString("app_language", "KLINGON").commit()
        Locale.setDefault(Locale.GERMANY)
        assertEquals(NativeAppLanguage.GERMAN, NativeAppSettingsStore(context).language())
    }

    @Test fun missingPreferenceUsesOneOfTheFiveSystemLanguages() {
        Locale.setDefault(Locale.JAPAN)
        assertEquals(NativeAppLanguage.JAPANESE, NativeAppLanguage.parse(null))
        Locale.setDefault(Locale.KOREA)
        assertEquals(NativeAppLanguage.KOREAN, NativeAppLanguage.parse("BROKEN"))
        Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
        assertEquals(NativeAppLanguage.CHINESE, NativeAppLanguage.parse(null))
        Locale.setDefault(Locale.FRANCE)
        assertEquals(NativeAppLanguage.ENGLISH, NativeAppLanguage.parse(null))
    }

    @Test fun aPreferenceWithTheWrongTypeCannotBreakActivityStartup() {
        Locale.setDefault(Locale.US)
        context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE).edit()
            .putInt("app_language", 7).commit()
        assertEquals(NativeAppLanguage.ENGLISH, NativeAppSettingsStore(context).language())
    }

    @Test fun configurationContextOverridesOnlyTheRequestedLanguage() {
        val german = localizedContext(context, NativeAppLanguage.GERMAN)
        assertEquals("de", german.resources.configuration.locales[0].language)
        val korean = localizedContext(context, NativeAppLanguage.KOREAN)
        assertEquals("ko", korean.resources.configuration.locales[0].language)
        assertNotEquals(german.resources.configuration.locales[0], korean.resources.configuration.locales[0])
        assertEquals("de", Locale.forLanguageTag(NativeAppLanguage.GERMAN.tag).language)
    }
}
