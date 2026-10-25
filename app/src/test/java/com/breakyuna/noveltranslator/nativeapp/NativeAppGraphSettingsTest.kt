package com.breakyuna.noveltranslator.nativeapp

import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsUnavailable
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The graph must pass the saved stores to the shared transport and scheduler. These probes
 * fail if a default transport (direct connection) or the four-request default scheduler is
 * still wired instead of the configured provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeAppGraphSettingsTest {
    private lateinit var app: TranslatorApplication
    private lateinit var graph: NativeAppGraph

    private val networkFile get() = File(app.filesDir, "native_model_network_settings.json")
    private val limitsFile get() = File(app.filesDir, "native_model_request_limits.json")

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        graph = app.nativeGraph
        networkFile.delete()
        File(networkFile.path + ".bak").delete()
        limitsFile.delete()
        File(limitsFile.path + ".bak").delete()
    }

    @After fun cleanup() {
        networkFile.delete()
        File(networkFile.path + ".bak").delete()
        limitsFile.delete()
        File(limitsFile.path + ".bak").delete()
    }

    @Test fun corruptNetworkStateFailsClosedInsteadOfDirectlyConnecting() = runBlocking {
        networkFile.writeText("{not-json")
        val request = Request.Builder().url("http://127.0.0.1:1/v1/models").build()
        val failure = runCatching { graph.configuredTransport.execute(request) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is InvalidModelNetworkSettings || failure is ModelNetworkSettingsUnavailable)
    }

    @Test fun savedNetworkSettingsResolveThroughTheGraphProvider() = runBlocking {
        val settings = ModelNetworkSettings(1, 2, 3, 4, followRedirects = true)
        graph.networkSettings.save(settings, null)
        assertEquals(settings.saved(), graph.configuredNetworkSettings())
        assertEquals(settings.saved(), graph.networkSettings.load())
    }

    @Test fun savedGlobalLimitsDriveTheSharedScheduler() = runBlocking {
        graph.requestLimits.save(ModelRequestLimits(1, 0), null)
        val configuration = ModelConfigurationRef("wiring-" + UUID.randomUUID(), 1)
        val firstStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val starts = AtomicInteger()
        withTimeout(10_000) {
            val jobs = List(2) {
                launch(Dispatchers.Default) {
                    graph.configuredScheduler.execute(configuration) {
                        starts.incrementAndGet()
                        val now = active.incrementAndGet()
                        maximum.accumulateAndGet(now) { left, right -> maxOf(left, right) }
                        firstStarted.complete(Unit)
                        release.await()
                        active.decrementAndGet()
                    }
                }
            }
            firstStarted.await()
            delay(150)
            assertEquals("saved concurrency 1 must serialize the second request", 1, maximum.get())
            release.complete(Unit)
            jobs.joinAll()
        }
        assertEquals(2, starts.get())
    }

    @Test fun changedLimitsForAnExistingPoolFailClosedUntilRestart() = runBlocking {
        graph.requestLimits.save(ModelRequestLimits(1, 0), null)
        val configuration = ModelConfigurationRef("limit-change-" + UUID.randomUUID(), 1)
        graph.configuredScheduler.execute(configuration) { }
        val fingerprint = graph.requestLimits.fingerprint()
        assertNotNull(fingerprint)
        graph.requestLimits.save(ModelRequestLimits(2, 0), fingerprint)
        val failure = runCatching { graph.configuredScheduler.execute(configuration) { } }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure is IllegalArgumentException)
    }

    /** Reads the policy through the same provider instance handed to the transport. */
    private suspend fun NativeAppGraph.configuredNetworkSettings() =
        networkSettings.networkSettings()
}
