package com.breakyuna.noveltranslator

import android.app.Application
import com.breakyuna.noveltranslator.core.crash.CrashReporter
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import com.breakyuna.noveltranslator.nativeapp.NativeAppGraph

class TranslatorApplication : Application() {
    val nativeGraph: NativeAppGraph by lazy { NativeAppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        // Isolated script services use a disposable UID without app files/permissions. They
        // must not initialize PDF resources, disk loggers, crash files or the application graph.
        if (android.os.Process.myUid() != applicationInfo.uid) return
        // PDFBox-Android reads its CMap/font metadata from assets and must be initialized once
        // before the first PDF import; the binary adapter itself has no Context parameter.
        com.breakyuna.noveltranslator.nativecore.formats.PdfBoxRuntime.initialize(this)
        // Order matters: the logger must be initialized first so a crash record can include
        // the recent in-memory entries, and CrashReporter chains itself in front of the
        // system default handler instead of replacing it.
        SystemLogger.init(this)
        CrashReporter.install(this)
    }
}
