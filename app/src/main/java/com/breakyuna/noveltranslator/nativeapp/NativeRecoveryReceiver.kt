package com.breakyuna.noveltranslator.nativeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Boot/replace hook. It only enqueues the persisted queue job and returns immediately; recovery,
 * intent replay, QUEUED-only claiming and continuation run inside the job. This receiver never
 * waits on a coroutine, holds no `goAsync` window and starts no paid request itself.
 */
class NativeRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // The graph is not required here. A later application open or job start uses the same
        // durable queue and replays the persisted control intents exactly once.
        NativeTranslationQueueWake.schedule(context.applicationContext)
    }
}
