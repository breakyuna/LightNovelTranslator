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
 * Recovery-only boot/replace hook. It never dispatches a paid request and never resumes a
 * paused queue: it asks the durable stores to classify abandoned RUNNING facts so the next
 * explicit user action sees an accurate PAUSED/NEEDS_REVIEW/CANCELLED state.
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
                    runCatching { app.nativeGraph.translationQueueScheduler.recover() }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
