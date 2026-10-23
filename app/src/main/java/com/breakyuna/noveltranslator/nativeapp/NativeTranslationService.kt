package com.breakyuna.noveltranslator.nativeapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.breakyuna.noveltranslator.MainActivity
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationPhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationSnapshot
import kotlinx.coroutines.*

/** A non-exported, non-sticky foreground host. Intents can only refer to an existing process token. */
class NativeTranslationService : Service() {
    private val runner get() = (application as TranslatorApplication).nativeGraph.backgroundTranslations
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: NativeTranslationHost

    override fun onCreate() {
        super.onCreate()
        host = NativeTranslationHost(runner, observerScope, ::promote, ::update) { startId ->
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            START -> NativeTranslationHost.Command.START
            STOP -> NativeTranslationHost.Command.STOP
            else -> NativeTranslationHost.Command.IGNORE
        }
        host.command(command, intent?.getStringExtra(TOKEN), startId)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        host.timeout()
    }

    override fun onDestroy() {
        host.destroy()
        observerScope.cancel()
        super.onDestroy()
    }

    private fun promote(snapshot: ForegroundTranslationSnapshot) {
        val manager = notifications()
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.native_background_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = notification(snapshot)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification)
    }

    private fun update(snapshot: ForegroundTranslationSnapshot) {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            notifications().notify(NOTIFICATION, notification(snapshot))
    }

    private fun notifications(): NotificationManager = requireNotNull(getSystemService(NotificationManager::class.java))

    private fun notification(snapshot: ForegroundTranslationSnapshot): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 0, Intent(this, NativeTranslationService::class.java)
            .setAction(STOP).setData(Uri.parse("lnt-native-run:${snapshot.token}")).putExtra(TOKEN, snapshot.token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (snapshot.phase) {
            ForegroundTranslationPhase.WAITING_FOR_SERVICE -> getString(R.string.native_background_starting)
            ForegroundTranslationPhase.STOPPING -> getString(R.string.native_background_stopping)
            else -> snapshot.progress?.let { getString(R.string.native_simulation_progress, it.committedItems, it.requests) }
                ?: getString(R.string.native_background_running)
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.native_simulation)).setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.native_agent_stop), stop).build()
    }

    companion object {
        private const val START = "com.breakyuna.noveltranslator.native.SIMULATE"
        private const val STOP = "com.breakyuna.noveltranslator.native.STOP_SIMULATION"
        private const val TOKEN = "native_execution_token"
        private const val CHANNEL = "native_translation"
        private const val NOTIFICATION = 6101

        fun start(context: Context, token: String) {
            ContextCompat.startForegroundService(context, Intent(context, NativeTranslationService::class.java)
                .setAction(START).putExtra(TOKEN, token))
        }
    }
}
