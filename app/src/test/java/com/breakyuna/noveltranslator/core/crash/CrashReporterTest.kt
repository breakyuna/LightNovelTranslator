package com.breakyuna.noveltranslator.core.crash

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Crash durability contract: the record must be on disk before the process dies, it must
 * chain to the previous handler, and it must never leak secrets or book content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashReporterTest {
    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    @After
    fun tearDown() {
        // Restore whatever handler the test JVM had before this class ran.
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        CrashReporter.resetForTests()
    }

    @Test
    fun installChainsPreviousHandlerAndStillForwardsTheException() {
        val forwarded = mutableListOf<Pair<String, Throwable>>()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            forwarded += thread.name to error
        }
        val boom = IllegalStateException("chained boom")

        CrashReporter.install(context)
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        assertEquals(1, forwarded.size)
        assertEquals(boom, forwarded.single().second)
    }

    @Test
    fun fatalCrashWritesDurableRecordThatSurvivesARestart() {
        CrashReporter.install(context)
        SystemLogger.info("CRASH_SETUP", "right before the boom")

        val boom = IllegalStateException("reader workbench boom")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), boom)

        val directory = CrashReporter.crashDirectory(context)
        val crashFiles = directory.listFiles { file -> file.name.startsWith("crash-") }.orEmpty()
        assertTrue("expected at least one crash-*.txt", crashFiles.isNotEmpty())
        assertTrue(File(directory, CrashReporter.LAST_CRASH_FILE_NAME).exists())

        // A new process sees an empty in-memory logger; only the file survives.
        CrashReporter.resetForTests()
        assertTrue(SystemLogger.logsFlow.value.isEmpty())
        assertNull(runBlocking { CrashReporter.readLastCrash() })

        CrashReporter.install(context)
        val restored = runBlocking { CrashReporter.readLastCrash() }
        assertNotNull("record must be readable after a simulated restart", restored)
        val record = restored!!
        assertEquals(IllegalStateException::class.java.name, record.exceptionClass)
        assertEquals("reader workbench boom", record.exceptionMessage)
        assertTrue(record.fatal)
        assertEquals(Build.MODEL, record.deviceModel)
        assertEquals(Build.VERSION.RELEASE, record.androidRelease)
        assertEquals(Build.VERSION.SDK_INT, record.androidSdkInt)
        assertTrue("app version must be resolved", record.appVersionName != "unknown")
        assertTrue("recent logs must be captured", record.recentLogs.contains("CRASH_SETUP"))
        assertTrue(record.sessionSummary.contains("activity="))
    }

    @Test
    fun recordNeverContainsSecretsOrBookBodies() {
        CrashReporter.install(context)
        val secret = "sk-abcdef1234567890abcdef1234567890"
        val boom = RuntimeException(
            "GET https://api.example.com/v1/chat failed: Authorization: Bearer $secret " +
                "api_key=$secret Translation: 他站在城墙上，风吹动他的长发。"
        )

        CrashReporter.reportNonFatal(Thread.currentThread(), boom)

        val record = runBlocking { CrashReporter.readLastCrash() }!!
        assertFalse(record.fatal)
        val text = record.formattedText() + record.recentLogs + record.sessionSummary
        assertFalse("API key must not reach disk", text.contains(secret))
        assertFalse("bearer token must not reach disk", text.contains("Bearer $secret"))
        assertTrue("redaction marker expected", text.contains("[REDACTED]"))
        assertFalse("novel body must not reach disk", text.contains("风吹动他的长发"))
    }

    @Test
    fun nonFatalReportKeepsTheProcessAliveAndRecordsTheFailure() {
        CrashReporter.install(context)
        val before = Thread.getDefaultUncaughtExceptionHandler()

        CrashReporter.reportNonFatal(Thread.currentThread(), IllegalStateException("soft failure"))

        assertEquals(before, Thread.getDefaultUncaughtExceptionHandler())
        val record = runBlocking { CrashReporter.readLastCrash() }!!
        assertFalse(record.fatal)
        assertEquals("soft failure", record.exceptionMessage)
        assertTrue(record.headline().contains("IllegalStateException"))
    }

    @Test
    fun encodeParseRoundTripPreservesEveryField() {
        CrashReporter.install(context)
        val boom = IllegalArgumentException("bad argument\nsecond line")
        val record = CrashReporter.buildRecord(Thread.currentThread(), boom, fatal = true)

        val parsed = CrashRecord.parse(record.encode())
        assertNotNull("encoded record must be parseable", parsed)
        assertEquals(record.id, parsed!!.id)
        assertEquals(record.timestamp, parsed.timestamp)
        assertEquals(record.threadName, parsed.threadName)
        assertEquals(record.exceptionClass, parsed.exceptionClass)
        // Newlines are collapsed on purpose: single-line header fields must stay parseable.
        assertEquals(record.exceptionMessage.replace("\n", " ⏎ "), parsed.exceptionMessage)
        assertEquals(record.stackTrace, parsed.stackTrace)
        assertEquals(record.deviceModel, parsed.deviceModel)
        assertEquals(record.androidSdkInt, parsed.androidSdkInt)
        assertEquals(record.appVersionName, parsed.appVersionName)
        assertEquals(record.appVersionCode, parsed.appVersionCode)
        assertEquals(record.fatal, parsed.fatal)
        assertTrue(parsed.sessionSummary.contains("activity="))
    }

    @Test
    fun observeLastCrashEmitsTheWrittenRecord() {
        CrashReporter.install(context)
        CrashReporter.reportNonFatal(Thread.currentThread(), IllegalStateException("observed"))

        val observed = runBlocking {
            var value: CrashRecord? = null
            CrashReporter.observeLastCrash().collect { value = it }
            value
        }
        assertEquals("observed", observed?.exceptionMessage)
    }

    @Test
    fun garbageFileIsNotParsedAsARecord() {
        assertNull(CrashRecord.parse("not a crash record at all"))
        assertNull(CrashRecord.parse(""))
    }

    @Test
    fun clearLastCrashRemovesTheDurableRecord() {
        CrashReporter.install(context)
        CrashReporter.reportNonFatal(Thread.currentThread(), IllegalStateException("to be cleared"))
        assertNotNull(runBlocking { CrashReporter.readLastCrash() })

        runBlocking { CrashReporter.clearLastCrash() }

        assertNull(runBlocking { CrashReporter.readLastCrash() })
    }

    @Test
    fun exportWithLogsContainsBothSections() {
        CrashReporter.install(context)
        CrashReporter.reportNonFatal(Thread.currentThread(), IllegalStateException("exported boom"))
        SystemLogger.info("EXPORT_CHECK", "a log line that must survive")

        val text = runBlocking { CrashReporter.exportTextWithLogs() }
        assertTrue(text.contains("=== LNT CRASH REPORT ==="))
        assertTrue(text.contains("=== NOVEL TRANSLATOR STUDIO - SYSTEM RUNTIME LOG ==="))
        assertTrue(text.contains("IllegalStateException"))
    }
}
