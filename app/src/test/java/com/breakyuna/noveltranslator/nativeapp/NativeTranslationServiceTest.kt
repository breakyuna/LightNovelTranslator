package com.breakyuna.noveltranslator.nativeapp

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.TranslatorApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Platform wiring only. Real notification/time-limit/background behavior still needs a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeTranslationServiceTest {
    @Test fun manifestDeclaresPrivateDataProcessingWithoutStoppingOnTaskRemoval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getServiceInfo(ComponentName(context, NativeTranslationService::class.java), 0)
        assertFalse(info.exported)
        assertEquals(0, info.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        if (Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }

    @Test fun nullRestartOrObsoleteIntentCannotRecreateAProcessExecution() {
        val controller = Robolectric.buildService(NativeTranslationService::class.java).create()
        val service = controller.get()
        val graph = ApplicationProvider.getApplicationContext<TranslatorApplication>().nativeGraph
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            val stale = Intent(service, NativeTranslationService::class.java)
                .setAction("com.breakyuna.noveltranslator.native.SIMULATE").putExtra("native_execution_token", "obsolete")
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(stale, 0, 2))
            assertNull(graph.backgroundTranslations.state.value)
            assertNull(graph.runtime.snapshot().activeRun)
        } finally {
            controller.destroy()
            runBlocking { graph.agentStore.close(); graph.agentInputs.close(); graph.modelSettings.close(); graph.runStore.close(); graph.projects.close() }
            graph.runtime.close()
        }
    }
}
