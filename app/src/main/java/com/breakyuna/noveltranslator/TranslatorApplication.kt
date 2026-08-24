package com.breakyuna.noveltranslator

import android.app.Application
import com.breakyuna.noveltranslator.core.logger.SystemLogger

class TranslatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SystemLogger.init(this)
    }
}
