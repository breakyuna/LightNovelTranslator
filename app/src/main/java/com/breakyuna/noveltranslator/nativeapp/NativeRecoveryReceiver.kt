package com.breakyuna.noveltranslator.nativeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.breakyuna.noveltranslator.TranslatorApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Boot/replace recovery hook. It first classifies abandoned RUNNING facts, then lets the durable
 * queue scheduler continue explicitly QUEUED tasks. Recovery never resumes a PAUSED/NEEDS_REVIEW
 * task and never retries an uncertain request; foreground-service start failures are contained by
 * the scheduler and leave the queue unchanged for the next app open.
 */
class NativeRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext as? TranslatorApplication
        if (app == null) { pending.finish(); return }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(30_000) {
                    runCatching { app.nativeGraph.runStore.recoverInterrupted() }
                    runCatching { app.nativeGraph.translationQueueScheduler.recoverExclusive() }
                    // Only explicitly admitted QUEUED tasks can be claimed. `kick` contains its
                    // own foreground-service failure and never retries PAUSED/uncertain work.
                    // Joining keeps the broadcast alive while the first drain pass runs; the
                    // timeout only stops waiting and never cancels the application-scope job.
                    app.nativeGraph.translationQueueScheduler.kick()?.join()
                }
            } finally {
                pending.finish()
            }
        }
    }
}
