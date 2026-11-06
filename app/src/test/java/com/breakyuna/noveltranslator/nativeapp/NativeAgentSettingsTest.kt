package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A new install and a malformed preference must not opt the user into extra model calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeAgentSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun clearSettings() {
        context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun automaticSummariesRequirePersistedOptInAndCanBeDisabled() {
        assertFalse(NativeAppSettingsStore(context).automaticContextSummary())
        NativeAppSettingsStore(context).saveAutomaticContextSummary(true)
        assertTrue(NativeAppSettingsStore(context).automaticContextSummary())
        NativeAppSettingsStore(context).saveAutomaticContextSummary(false)
        assertFalse(NativeAppSettingsStore(context).automaticContextSummary())
    }

    @Test fun malformedOptInIsTreatedAsDisabled() {
        context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE).edit()
            .putString("automatic_context_summary", "true").commit()
        assertFalse(NativeAppSettingsStore(context).automaticContextSummary())
    }
}
