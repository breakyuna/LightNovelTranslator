package com.breakyuna.noveltranslator

import android.app.Application
import com.breakyuna.noveltranslator.core.crash.CrashReporter
import com.breakyuna.noveltranslator.core.logger.SystemLogger

class TranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Order matters: the logger must be initialized first so a crash record can include
        // the recent in-memory entries, and CrashReporter chains itself in front of the
        // system default handler instead of replacing it.
        SystemLogger.init(this)
        CrashReporter.install(this)
    }
}
