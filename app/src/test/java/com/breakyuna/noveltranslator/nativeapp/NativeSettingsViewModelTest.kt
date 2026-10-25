package com.breakyuna.noveltranslator.nativeapp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsSource
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import java.io.File
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

/** View-model states keep the form, expose fingerprints and refresh after real save outcomes. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeSettingsViewModelTest {
    private lateinit var app: TranslatorApplication
    private lateinit var graph: NativeAppGraph
    private lateinit var port: FakeSettingsPort
    private lateinit var previousLocale: Locale
    private val stores = mutableListOf<ViewModelStore>()

    private class FakeSettingsPort : NativeSettingsPort {
        var network = NativeLoadedNetworkSettings(ModelNetworkSettings.DEFAULT, null)
        var limits = NativeLoadedRequestLimits(ModelRequestLimits(), null)
        var networkSaves = 0
        var limitSaves = 0
        var failNetworkLoad = false
        var failLimitsLoad = false

        override suspend fun loadNetworkSettings(): NativeLoadedNetworkSettings {
            if (failNetworkLoad) throw InvalidModelNetworkSettings()
            return network
        }
        override suspend fun saveNetworkSettings(settings: ModelNetworkSettings, expectedFingerprint: String?): NativeLoadedNetworkSettings {
            if (expectedFingerprint != network.fingerprint) throw IllegalArgumentException("Network settings changed")
            networkSaves++
            network = NativeLoadedNetworkSettings(settings.saved(), "network-$networkSaves")
            return network
        }

        override suspend fun loadRequestLimits(): NativeLoadedRequestLimits {
            if (failLimitsLoad) throw IllegalStateException("Damaged request limits")
            return limits
        }
        override suspend fun saveRequestLimits(limits: ModelRequestLimits, expectedFingerprint: String?): NativeLoadedRequestLimits {
            if (expectedFingerprint != this.limits.fingerprint) throw IllegalArgumentException("Request limits changed")
            limitSaves++
            this.limits = NativeLoadedRequestLimits(limits, "limits-$limitSaves")
            return this.limits
        }
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("native_app_settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "native_projects").deleteRecursively()
        graph = app.nativeGraph
        port = FakeSettingsPort()
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

    private fun model(): NativeProjectViewModel = model(SavedStateHandle())

    private fun model(saved: SavedStateHandle): NativeProjectViewModel {
        val store = ViewModelStore().also(stores::add)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NativeProjectViewModel(app, saved, graph.projects, graph.backgroundTranslations, port) as T
        })
        return provider[NativeProjectViewModel::class.java]
    }

    private suspend fun NativeProjectViewModel.ready() {
        state.first { !it.busy }
        if (!state.value.networkSettingsState.loaded) reloadNetworkSettings()
        if (!state.value.requestLimitsState.loaded) reloadRequestLimits()
        state.first { it.networkSettingsState.loaded && it.requestLimitsState.loaded }
    }

    @Test fun networkSaveRefreshesFingerprintAndAConflictKeepsTheFormRetryable() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            assertFalse(model.state.value.networkSettingsState.loadFailed)
            assertNull(model.state.value.networkSettingsState.fingerprint)

            model.saveNetworkSettings(NativeNetworkSettingsInput("10", "90", "10", "90", "proxy.example", "8080"))
            model.state.first { !it.networkSettingsState.saving }
            val saved = model.state.value.networkSettingsState
            assertEquals("network-1", saved.fingerprint)
            assertEquals(ModelNetworkSettingsSource.SAVED, saved.settings.source)
            assertEquals("proxy.example", saved.settings.proxy?.host)

            // Another writer replaces the policy while this form still carries network-1.
            port.network = NativeLoadedNetworkSettings(ModelNetworkSettings(1, 2, 3, 4,
                source = ModelNetworkSettingsSource.SAVED), "external")
            model.saveNetworkSettings(NativeNetworkSettingsInput("20", "90", "10", "90"))
            model.state.first { !it.networkSettingsState.saving }
            val conflict = model.state.value.networkSettingsState
            assertTrue(conflict.conflict)
            assertEquals("external", conflict.fingerprint)
            assertEquals(2, conflict.settings.readTimeoutSeconds)
            assertEquals(2L, conflict.revision)

            // Retry uses the refreshed fingerprint and succeeds.
            model.saveNetworkSettings(NativeNetworkSettingsInput("20", "90", "10", "90"))
            model.state.first { !it.networkSettingsState.saving }
            val retried = model.state.value.networkSettingsState
            assertFalse(retried.conflict)
            assertEquals("network-2", retried.fingerprint)
            assertEquals(20, retried.settings.connectTimeoutSeconds)
            assertEquals(2, port.networkSaves)
        }
    }

    @Test fun invalidNetworkInputNeverReachesStorageAndKeepsLoadedState() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            model.saveNetworkSettings(NativeNetworkSettingsInput("0", "90", "10", "90"))
            val state = model.state.value.networkSettingsState
            assertTrue(state.invalid)
            assertEquals(0, port.networkSaves)
            assertTrue(state.loaded)
        }
    }

    @Test fun requestLimitSaveConflictAndBoundsAreHandledSeparately() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            model.saveRequestLimits(NativeRequestLimitsInput("8", "120"))
            model.state.first { !it.requestLimitsState.saving }
            assertEquals(ModelRequestLimits(8, 120), model.state.value.requestLimitsState.limits)
            assertEquals("limits-1", model.state.value.requestLimitsState.fingerprint)

            port.limits = NativeLoadedRequestLimits(ModelRequestLimits(2, 0), "external-limits")
            model.saveRequestLimits(NativeRequestLimitsInput("4", "60"))
            model.state.first { !it.requestLimitsState.saving }
            assertTrue(model.state.value.requestLimitsState.conflict)
            assertEquals("external-limits", model.state.value.requestLimitsState.fingerprint)
            assertEquals(2L, model.state.value.requestLimitsState.revision)

            model.saveRequestLimits(NativeRequestLimitsInput("17", "0"))
            assertTrue(model.state.value.requestLimitsState.invalid)
            assertEquals(1, port.limitSaves)
        }
    }

    @Test fun languageSaveUpdatesStateAndInvokesTheRecreateCallbackOnlyAfterCommit() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            var recreated = 0
            model.setAppLanguage(NativeAppLanguage.GERMAN) { recreated++ }
            model.state.first { it.appLanguage == NativeAppLanguage.GERMAN }
            withTimeout(5_000) { while (recreated == 0) kotlinx.coroutines.yield() }
            assertEquals(NativeAppLanguage.GERMAN, NativeAppSettingsStore(app).language())
            assertEquals(1, recreated)
            model.setAppLanguage(NativeAppLanguage.GERMAN) { recreated++ }
            assertEquals(1, recreated)
        }
    }

    @Test fun appPersonaPersistsTrimmedAndRejectsInvalidDraftWithoutLosingSavedValue() = runBlocking {
        withTimeout(20_000) {
            val model = model()
            model.ready()
            model.saveAppPersona("  Warm and concise  ")
            model.state.first { it.appPersona == "Warm and concise" }
            assertEquals("Warm and concise", NativeAppSettingsStore(app).appPersona())
            model.saveAppPersona("x".repeat(NativeAppPersona.MAX_CHARS + 1))
            model.state.first { it.appPersonaSaveFailed && !it.busy }
            assertEquals("Warm and concise", model.state.value.appPersona)
            assertEquals("Warm and concise", NativeAppSettingsStore(app).appPersona())
        }
    }

    @Test fun corruptSavedStateBecomesAnExplicitFailClosedState() = runBlocking {
        withTimeout(20_000) {
            port.failNetworkLoad = true
            port.failLimitsLoad = true
            val model = model()
            model.state.first { !it.busy }
            model.reloadNetworkSettings()
            model.reloadRequestLimits()
            model.state.first { it.networkSettingsState.loadFailed && it.requestLimitsState.loadFailed &&
                !it.networkSettingsState.loading && !it.requestLimitsState.loading }
            assertFalse(model.state.value.networkSettingsState.loaded)
            assertFalse(model.state.value.requestLimitsState.loaded)
            assertEquals(0, port.networkSaves)
            assertEquals(0, port.limitSaves)
        }
    }

    @Test fun settingsRoutePreservesProjectAndChatAndReturnsToMore() = runBlocking {
        withTimeout(20_000) {
            val project = graph.projects.createTxt(CommandId("settings-route"), "settings",
                RelativePath("source.txt"), "source line".toByteArray())
            val saved = SavedStateHandle(mapOf("native_ref" to arrayListOf(project.ref.projectId.value,
                project.ref.instanceId.value, project.ref.generation.value.toString())))
            val model = model(saved)
            model.ready()
            model.state.first { it.project != null }
            val ref = model.state.value.project!!.ref
            val chat = model.state.value.agentChatId
            model.openSettings(NativeSettingsSection.EXPERT)
            assertEquals(NativePage.SETTINGS, model.state.value.page)
            assertEquals(NativeSettingsSection.EXPERT, model.state.value.settingsSection)
            assertEquals(NativePage.SETTINGS.name, saved["native_page"])
            assertEquals(NativeSettingsSection.EXPERT.name, saved["native_settings_section"])
            assertEquals(ref, model.state.value.project?.ref)
            assertEquals(chat, model.state.value.agentChatId)
            model.selectSettingsSection(NativeSettingsSection.BASIC)
            assertEquals(NativeSettingsSection.BASIC, model.state.value.settingsSection)
            model.selectPage(NativePage.MORE)
            assertEquals(NativePage.MORE, model.state.value.page)
            assertEquals(ref, model.state.value.project?.ref)
            assertEquals(chat, model.state.value.agentChatId)
        }
    }
}
