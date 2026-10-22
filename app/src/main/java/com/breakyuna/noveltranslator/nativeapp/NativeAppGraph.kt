package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore

/** One process owner for native IO and reservations; no legacy engine or model client. */
class NativeAppGraph(context: Context) {
    val runtime = RuntimeCoordinator()
    val projects = NativeTextProjectStore(context.applicationContext, runtime)
}
