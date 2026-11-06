package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The diagnostics detail opt-in is explicit, off by default and survives a new store instance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeDiagnosticsSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var previous = false

    @Before fun setup() {
        previous = NativeAppSettingsStore(context).diagnosticsIncludeDetails()
        NativeAppSettingsStore(context).saveDiagnosticsIncludeDetails(false)
    }

    @After fun cleanup() {
        NativeAppSettingsStore(context).saveDiagnosticsIncludeDetails(previous)
    }

    @Test fun debugDetailsDefaultOffAndPersistAcrossStoreInstances() {
        assertFalse(NativeAppSettingsStore(context).diagnosticsIncludeDetails())
        NativeAppSettingsStore(context).saveDiagnosticsIncludeDetails(true)
        assertTrue(NativeAppSettingsStore(context).diagnosticsIncludeDetails())
        NativeAppSettingsStore(context).saveDiagnosticsIncludeDetails(false)
        assertFalse(NativeAppSettingsStore(context).diagnosticsIncludeDetails())
    }
}
