package com.breakyuna.noveltranslator

import android.app.Application
import com.breakyuna.noveltranslator.core.logger.SystemLogger

import android.util.Log

class TranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemLogger.init(this)
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                SystemLogger.error(
                    tag = "CRASH",
                    message = "Fatal Exception in ${thread.name}: ${throwable.message}",
                    details = stackTrace
                )
            } catch (e: Exception) {
                // Ignore errors during crash handling
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
