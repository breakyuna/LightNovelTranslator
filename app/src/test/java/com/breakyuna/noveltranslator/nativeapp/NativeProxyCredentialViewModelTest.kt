package com.breakyuna.noveltranslator.nativeapp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Proxy commands save a Keystore-backed credential without placing the secret in UI state. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeProxyCredentialViewModelTest {
    private lateinit var app: TranslatorApplication
    private lateinit var graph: NativeAppGraph
    private lateinit var settings: FakeSettingsPort
    private lateinit var writer: FakeWriter
    private lateinit var previousLocale: Locale
    private val stores = mutableListOf<ViewModelStore>()

    private class FakeSettingsPort : NativeSettingsPort {
        var network = NativeLoadedNetworkSettings(ModelNetworkSettings.DEFAULT, null)
        var networkSaves = 0
        override suspend fun loadNetworkSettings() = network
        override suspend fun saveNetworkSettings(settings: ModelNetworkSettings,
            expectedFingerprint: String?): NativeLoadedNetworkSettings {
            if (expectedFingerprint != network.fingerprint) throw IllegalArgumentException("Network settings changed")
            networkSaves++
            network = NativeLoadedNetworkSettings(settings.saved(), "network-$networkSaves")
            return network
        }
        override suspend fun loadRequestLimits() = NativeLoadedRequestLimits(ModelRequestLimits(), null)
        override suspend fun saveRequestLimits(limits: ModelRequestLimits,
            expectedFingerprint: String?) = NativeLoadedRequestLimits(limits, "limits")
    }

    private class FakeWriter : NativeProxyCredentialWriter {
        val saved = mutableListOf<Pair<String, ModelProxyCredential>>()
        val cleared = mutableListOf<String>()
        var failSave = false
        var failClear = false

        override suspend fun save(reference: String, credential: ModelProxyCredential) {
            if (failSave) throw InvalidModelProxyCredential()
            saved += reference to credential
        }
        override suspend fun clear(reference: String) {
            if (failClear) throw InvalidModelProxyCredential()
            cleared += reference
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("native_app_settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        graph = app.nativeGraph
        settings = FakeSettingsPort()
        writer = FakeWriter()
    }

    @After fun cleanup() = runBlocking {
        try {
            stores.forEach { it.clear() }
            app.getSharedPreferences("native_app_settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            graph.close()
        } finally {
            Locale.setDefault(previousLocale)
            Dispatchers.resetMain()
        }
    }

    private fun model(): NativeProjectViewModel {
        val store = ViewModelStore().also(stores::add)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NativeProjectViewModel(app, SavedStateHandle(), graph.projects,
                    graph.backgroundTranslations, settings, proxyCredentialWriter = writer) as T
        })
        return provider[NativeProjectViewModel::class.java]
    }

    private suspend fun NativeProjectViewModel.ready() {
        state.first { !it.busy }
        if (!state.value.networkSettingsState.loaded) reloadNetworkSettings()
        state.first { it.networkSettingsState.loaded }
    }

    private fun input(host: String = "proxy.example", port: String = "8080") =
        NativeNetworkSettingsInput("10", "90", "10", "90", host, port)

    @Test fun replaceAndClearAreIdempotentAndNeverPutThePasswordInState() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            val actions = model.proxyCredentialActions
            actions.saveNetwork(input(), NativeProxyCredentialChange.Replace("proxy-user", "proxy-secret"))
            actions.state.first { !it.busy && it.revision > 0 }
            val reference = writer.saved.single().first
            assertEquals("proxy-user", writer.saved.single().second.username)
            assertEquals("proxy-secret", writer.saved.single().second.password)
            assertEquals(reference, settings.network.settings.proxyCredentialRef)
            assertEquals(reference, model.state.value.networkSettingsState.settings.proxyCredentialRef)
            assertNull(actions.state.value.error)
            assertFalse(model.state.value.toString().contains("proxy-secret"))
            assertFalse(actions.state.value.toString().contains("proxy-secret"))
            assertFalse(settings.network.toString().contains("proxy-secret"))
            assertFalse(writer.saved.single().second.toString().contains("proxy-secret"))
            assertFalse(NativeProxyCredentialChange.Replace("proxy-user", "proxy-secret")
                .toString().contains("proxy-secret"))

            actions.clear()
            actions.state.first { !it.busy && it.revision > 1 }
            assertEquals(listOf(reference), writer.cleared)
            assertNull(settings.network.settings.proxyCredentialRef)
            assertNull(model.state.value.networkSettingsState.settings.proxyCredentialRef)
            assertEquals(R.string.native_settings_proxy_cleared, model.state.value.notice)

            // A second clear is a no-op, not an error or a second file deletion.
            actions.clear()
            actions.state.first { !it.busy }
            assertEquals(1, writer.cleared.size)
            assertNull(actions.state.value.error)
        }
    }

    @Test fun invalidAndFailingCredentialCommandsMapToVisibleErrorsWithoutSavingSettings() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            val actions = model.proxyCredentialActions
            actions.saveNetwork(input(), NativeProxyCredentialChange.Replace("bad:user", "secret"))
            assertEquals(R.string.native_settings_proxy_invalid, actions.state.value.error)
            assertEquals(0, settings.networkSaves)
            assertTrue(writer.saved.isEmpty())

            writer.failSave = true
            actions.saveNetwork(input(), NativeProxyCredentialChange.Replace("proxy-user", "proxy-secret"))
            actions.state.first { !it.busy && it.error != null }
            assertEquals(R.string.native_settings_proxy_credential_failed, actions.state.value.error)
            assertEquals(0, settings.networkSaves)
            assertNull(settings.network.settings.proxyCredentialRef)
            assertNull(model.state.value.networkSettingsState.settings.proxyCredentialRef)
            assertFalse(actions.state.value.toString().contains("proxy-secret"))
        }
    }

    @Test fun keepAndDeleteUseTheExplicitReferenceInsteadOfRepricingOrDroppingIt() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            val actions = model.proxyCredentialActions
            actions.saveNetwork(input(), NativeProxyCredentialChange.Replace("proxy-user", "proxy-secret"))
            actions.state.first { !it.busy && it.revision > 0 }
            val reference = requireNotNull(settings.network.settings.proxyCredentialRef)

            // Keeping the proxy off preserves the encrypted reference for later use.
            actions.saveNetwork(input(host = "", port = ""),
                NativeProxyCredentialChange.Keep)
            actions.state.first { !it.busy && it.revision > 1 }
            assertNull(settings.network.settings.proxy)
            assertEquals(reference, settings.network.settings.proxyCredentialRef)
            assertTrue(writer.cleared.isEmpty())

            // Deleting clears the reference and removes the encrypted file.
            actions.saveNetwork(input(host = "", port = ""), NativeProxyCredentialChange.Delete)
            actions.state.first { !it.busy && it.revision > 2 }
            assertNull(settings.network.settings.proxyCredentialRef)
            assertEquals(listOf(reference), writer.cleared)
        }
    }
}
