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
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspacePhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Non-exported, non-sticky foreground host for one direct skill execution.
 *
 * The Intent carries only the opaque process token; script text, input JSON, credentials and
 * project material never enter it. If notification permission is denied the execution still
 * runs but notification updates are skipped; the service never converts that into a new start.
 */
class NativeWorkspaceExecutionService : Service() {
    private val runner get() = (application as TranslatorApplication).nativeGraph.backgroundWorkspaceExecutions
    private val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var host: NativeWorkspaceExecutionHost

    override fun onCreate() {
        super.onCreate()
        host = NativeWorkspaceExecutionHost(runner, observerScope, ::promote, ::update) { startId ->
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            START -> NativeWorkspaceExecutionHost.Command.START
            STOP -> NativeWorkspaceExecutionHost.Command.STOP
            else -> NativeWorkspaceExecutionHost.Command.IGNORE
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

    private fun promote(snapshot: ForegroundWorkspaceSnapshot) {
        val manager = notifications()
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.native_skill_execution_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = notification(snapshot)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    private fun update(snapshot: ForegroundWorkspaceSnapshot) {
        if (Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            notifications().notify(NOTIFICATION, notification(snapshot))
        }
    }

    private fun notifications(): NotificationManager =
        requireNotNull(getSystemService(NotificationManager::class.java))

    private fun notification(snapshot: ForegroundWorkspaceSnapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, NativeWorkspaceExecutionService::class.java)
                .setAction(STOP)
                .setData(Uri.parse("lnt-native-skill:${snapshot.token}"))
                .putExtra(TOKEN, snapshot.token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (snapshot.phase) {
            ForegroundWorkspacePhase.PREPARING -> getString(R.string.native_skill_execution_starting)
            ForegroundWorkspacePhase.STOPPING -> getString(R.string.native_skill_execution_stopping)
            else -> getString(R.string.native_skill_execution_running)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.native_skill_run))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.native_skill_execution_stop), stop)
            .build()
    }

    companion object {
        private const val START = "com.breakyuna.noveltranslator.native.RUN_SKILL"
        private const val STOP = "com.breakyuna.noveltranslator.native.STOP_SKILL"
        private const val TOKEN = "native_execution_token"
        private const val CHANNEL = "native_skill_execution"
        private const val NOTIFICATION = 6103

        fun start(context: Context, token: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NativeWorkspaceExecutionService::class.java)
                    .setAction(START)
                    .putExtra(TOKEN, token),
            )
        }
    }
}
