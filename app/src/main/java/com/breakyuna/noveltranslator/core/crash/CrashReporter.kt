package com.breakyuna.noveltranslator.core.crash

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import com.breakyuna.noveltranslator.core.logger.LogSanitizer
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a durable crash record before the process dies.
 *
 * [SystemLogger] only publishes to memory and flushes to JSONL on an IO worker, so a fatal
 * exception normally takes the last minutes of context with it. This reporter writes one
 * small text file synchronously on the crashing thread, then chains to the previous
 * [Thread.getDefaultUncaughtExceptionHandler] so Android still shows its own crash dialog and
 * kills the process exactly as before.
 *
 * Failure handling is absolute: every step is wrapped, because a handler that throws would
 * replace the original crash with a second one and lose the first stack trace.
 */
object CrashReporter {
    const val LOG_DIR_NAME = "logs"
    const val CRASH_DIR_NAME = "crash"
    const val LAST_CRASH_FILE_NAME = "last-crash.txt"
    private const val MAX_CRASH_FILES = 10
    private const val MAX_RECENT_LOG_ENTRIES = 40
    private const val MAX_LOG_ENTRY_CHARS = 600
    private const val MAX_STACK_CHARS = 24_000
    private const val TAG = "CRASH"

    private val lock = Any()
    private val lastCrashState = MutableStateFlow<CrashRecord?>(null)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var activityCallbacks: Application.ActivityLifecycleCallbacks? = null

    /** Foreground activity class + process facts; no book, chapter or provider identifiers. */
    private var currentActivity: String? = null
    private var resumedActivities = 0
    private val startedAtMillis = System.currentTimeMillis()

    /** True once [install] has chained itself in front of the system handler. */
    val isInstalled: Boolean get() = installed

    /**
     * Chains the crash handler. Safe to call more than once; the second call is a no-op so the
     * original system handler can never be shadowed by this object.
     */
    fun install(context: Context) {
        synchronized(lock) {
            if (installed) return
            installed = true
            appContext = context.applicationContext
            trackForegroundActivity(context.applicationContext)
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            previousHandler = previous
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                report(thread, error, fatal = true)
                // Chain to the previous handler (Android's KillApplicationHandler).
                runCatching { previous?.uncaughtException(thread, error) }
                if (previous == null) {
                    // 没有前驱处理器意味着这不是 Android 宿主（真机上 RuntimeInit 必然安装
                    // KillApplicationHandler），而是 Robolectric 之类的纯 JVM。
                    // 堆栈必须落盘：Gradle 的 showStandardStreams 只转发与测试事件关联的
                    // 输出，关闭阶段的未捕获异常 stderr 会被整段丢弃（连续五轮只看到 exit
                    // code 看不到原因）。测试 JVM 的工作目录是 app 模块目录，写进
                    // build/test-results 可随 CI artifact 上传。
                    runCatching { error.printStackTrace() }
                    runCatching {
                        val trace = buildString {
                            appendLine("thread=" + thread.name)
                            appendLine("time=" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                            appendLine("fatal=" + true)
                            appendLine(error.stackTraceToString())
                        }
                        listOf("build/test-results", "app/build/test-results").forEach { dir ->
                            runCatching {
                                File(dir).mkdirs()
                                File(dir, "uncaught-crash.txt").writeText(trace, Charsets.UTF_8)
                            }
                        }
                    }
                    // 对齐 JVM 默认行为终止：简单返回会让已消亡的线程拖住 JVM（测试框架挂死）。
                    runCatching { Runtime.getRuntime().exit(1) }
                }
            }
            // Load the previous crash record off the crashing path; a cold start must stay fast.
            Thread({ loadLastCrashAsync() }, "lnt-crash-loader").apply { isDaemon = true }.start()
        }
    }

    /**
     * Records a non-fatal failure (used by tests and by future soft-error reporting). Never
     * throws.
     */
    fun reportNonFatal(thread: Thread, error: Throwable, sessionSummary: String? = null) {
        report(thread, error, fatal = false, sessionSummaryOverride = sessionSummary)
    }

    /** Directory that holds `crash-<timestamp>.txt` files plus `last-crash.txt`. */
    fun crashDirectory(context: Context): File = File(File(context.filesDir, LOG_DIR_NAME), CRASH_DIR_NAME)

    /** Newest persisted record, or null when the app never crashed. */
    fun observeLastCrash(): Flow<CrashRecord?> = lastCrashState.asStateFlow()

    /** Reads the record back from disk; [Dispatchers.IO] bound so callers can use it anywhere. */
    suspend fun readLastCrash(): CrashRecord? = withContext(Dispatchers.IO) { readLastCrashBlocking() }

    /** Removes the persisted record (and the in-memory copy) so the UI can dismiss it. */
    suspend fun clearLastCrash() {
        val context = appContext ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = crashDirectory(context)
                directory.listFiles()?.forEach { file ->
                    if (file.name.startsWith("crash-") || file.name == LAST_CRASH_FILE_NAME) file.delete()
                }
            }
        }
        lastCrashState.value = null
    }

    // ---------------------------------------------------------------- internals

    private fun report(
        thread: Thread,
        error: Throwable,
        fatal: Boolean,
        sessionSummaryOverride: String? = null
    ) {
        // Never let the reporting path throw: the original crash must survive it.
        runCatching {
            val context = appContext ?: return
            val record = buildRecord(thread, error, fatal, sessionSummaryOverride)
            writeRecord(context, record)
            lastCrashState.value = record
            // Also surface it in the session log; the durable file above is what survives.
            runCatching {
                SystemLogger.error(
                    tag = TAG,
                    message = "Fatal Exception in ${record.threadName}: ${record.exceptionClass}: ${record.exceptionMessage}",
                    details = LogSanitizer.bounded(record.stackTrace, MAX_STACK_CHARS)
                )
            }
            Log.e(TAG, "Uncaught exception in thread ${record.threadName}", error)
        }
    }

    internal fun buildRecord(
        thread: Thread,
        error: Throwable,
        fatal: Boolean,
        sessionSummaryOverride: String? = null
    ): CrashRecord {
        val context = appContext
        val (versionName, versionCode) = resolveVersion(context)
        return CrashRecord(
            id = "crash-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()),
            timestamp = System.currentTimeMillis(),
            threadName = LogSanitizer.singleLine(thread.name),
            exceptionClass = LogSanitizer.singleLine(error.javaClass.name),
            exceptionMessage = LogSanitizer.singleLine(error.message ?: ""),
            stackTrace = LogSanitizer.bounded(
                LogSanitizer.sanitize(Log.getStackTraceString(error) ?: error.stackTraceToString()),
                MAX_STACK_CHARS
            ),
            deviceManufacturer = LogSanitizer.singleLine(Build.MANUFACTURER ?: "unknown"),
            deviceModel = LogSanitizer.singleLine(Build.MODEL ?: "unknown"),
            androidRelease = LogSanitizer.singleLine(Build.VERSION.RELEASE ?: "unknown"),
            androidSdkInt = Build.VERSION.SDK_INT,
            appVersionName = versionName,
            appVersionCode = versionCode,
            sessionSummary = sessionSummaryOverride ?: buildSessionSummary(),
            recentLogs = buildRecentLogs(),
            fatal = fatal
        )
    }

    private fun buildSessionSummary(): String = runCatching {
        buildString {
            appendLine("activity=${currentActivity ?: "none"}")
            appendLine("foreground=${resumedActivities > 0}")
            appendLine("uptimeMs=${System.currentTimeMillis() - startedAtMillis}")
            appendLine("logsInMemory=${SystemLogger.logsFlow.value.size}")
        }.trimEnd()
    }.getOrDefault("")

    private fun buildRecentLogs(): String = runCatching {
        SystemLogger.logsFlow.value
            .takeLast(MAX_RECENT_LOG_ENTRIES)
            .joinToString("\n") { entry ->
                "[${entry.formattedDate}] [${entry.level}] [${entry.tag}] " +
                    LogSanitizer.singleLine(entry.message) +
                    entry.details?.let { "\n  └ " + LogSanitizer.singleLine(it).take(MAX_LOG_ENTRY_CHARS) }.orEmpty()
            }
            .let { LogSanitizer.bounded(it, 8_000) }
    }.getOrDefault("")

    private fun writeRecord(context: Context, record: CrashRecord) {
        val directory = crashDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) return
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(record.timestamp))
        val target = File(directory, "crash-$timestamp.txt")
        val text = record.encode()
        runCatching { target.writeText(text, Charsets.UTF_8) }
        // last-crash.txt is the "newest" pointer the UI reads after a restart.
        runCatching { File(directory, LAST_CRASH_FILE_NAME).writeText(text, Charsets.UTF_8) }
        pruneOldRecords(directory)
    }

    private fun pruneOldRecords(directory: File) {
        runCatching {
            directory.listFiles { file -> file.name.startsWith("crash-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CRASH_FILES)
                ?.forEach { it.delete() }
        }
    }

    private fun loadLastCrashAsync() {
        runCatching {
            val context = appContext ?: return
            lastCrashState.value = readLastCrashBlocking(context)
        }
    }

    private fun readLastCrashBlocking(context: Context? = appContext): CrashRecord? {
        val resolved = context ?: return null
        return runCatching {
            val directory = crashDirectory(resolved)
            val newest = directory.listFiles { file -> file.name.startsWith("crash-") }
                ?.maxByOrNull { it.lastModified() }
                ?: File(directory, LAST_CRASH_FILE_NAME).takeIf { it.exists() }
                ?: return null
            CrashRecord.parse(newest.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun resolveVersion(context: Context?): Pair<String, Long> {
        if (context == null) return "unknown" to -1L
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            (info.versionName ?: "unknown") to code
        }.getOrDefault("unknown" to -1L)
    }

    private fun trackForegroundActivity(context: Context) {
        if (context !is Application) return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                currentActivity = activity.javaClass.name
                resumedActivities++
            }

            override fun onActivityPaused(activity: android.app.Activity) {
                resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        }
        activityCallbacks = callbacks
        context.registerActivityLifecycleCallbacks(callbacks)
    }

    /** Test hook: drops the cached context/handler without touching the real system handler. */
    internal fun resetForTests() {
        synchronized(lock) {
            val context = appContext as? Application
            activityCallbacks?.let { callbacks ->
                runCatching { context?.unregisterActivityLifecycleCallbacks(callbacks) }
            }
            activityCallbacks = null
            appContext = null
            previousHandler = null
            installed = false
            currentActivity = null
            resumedActivities = 0
            lastCrashState.value = null
        }
    }
}

/** Convenience for callers that only need the formatted report of the last crash. */
suspend fun CrashReporter.lastCrashText(): String? = readLastCrash()?.formattedText()

/**
 * UI friendly export that bundles the last crash record with the persisted runtime log, in the
 * order a support conversation wants them: what happened, then what led up to it.
 */
suspend fun CrashReporter.exportTextWithLogs(): String {
    val crash = readLastCrash()
    val logs = SystemLogger.exportText()
    return buildString {
        if (crash != null) {
            append(crash.formattedText())
            appendLine()
            appendLine("================ RUNTIME LOG ================")
            appendLine()
        }
        append(logs)
    }
}
