package com.breakyuna.noveltranslator.nativecore.workspace

import app.cash.quickjs.QuickJs
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Embedded QuickJS adapter for [WorkspaceScriptEngine].
 *
 * Each execution gets exactly one QuickJS context, created on a dedicated worker thread and
 * closed in `finally`. Script source is resolved through [WorkspaceScriptSourcePort] and never
 * stored in [WorkspaceScriptRequest]. Only the small `lg` bridge is installed: material reads,
 * logs, change/artifact proposals and (when the matching capability was granted) search, fetch
 * and print. File, process and network globals are deleted from the context before user code
 * runs, and every host call is re-authorized by [WorkspaceScriptHostSession].
 *
 * This in-process adapter is for cooperative fixtures only. Production uses
 * [IsolatedWorkspaceScriptEngine]. QuickJS exposes no interrupt handler in this binding, so a
 * tight loop can leave an abandoned daemon thread. A native context is closed only by its
 * owning interpreter thread; closing it concurrently with evaluation can crash the process.
 *
 * The engine has no Node modules, `require`, `process`, `fs`, timers or async task queue; a
 * script is a single synchronous top-level body. Its return value is an integral exit code in
 * `0..255` (absent/undefined means 0).
 *
 * [execute] blocks the calling thread while the worker runs, so callers must invoke it off the
 * UI thread. Host ports invoked by the bridge must complete synchronously and honor the request
 * deadline themselves; the supervising thread can reject late results but cannot forcibly stop
 * JS or a blocked host port. Use the isolated adapter for production execution.
 */
class QuickJsWorkspaceScriptEngine(
    private val sources: WorkspaceScriptSourcePort,
    private val materials: WorkspaceScriptMaterialReadPort,
    private val search: WorkspaceSearchPort? = null,
    private val fetch: WorkspaceFetchPort? = null,
    private val print: WorkspacePrintPort? = null,
    private val clock: WorkspaceClock = WorkspaceClock.SYSTEM,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    private val maxMaterialReadBytes: Long = DEFAULT_MAX_MATERIAL_READ_BYTES,
    private val maxTotalMaterialReadBytes: Long = DEFAULT_MAX_TOTAL_MATERIAL_READ_BYTES,
    private val maxTotalChangeBytes: Long = DEFAULT_MAX_TOTAL_CHANGE_BYTES,
    private val maxTotalArtifactBytes: Long = DEFAULT_MAX_TOTAL_ARTIFACT_BYTES,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val shutdownGraceMillis: Long = DEFAULT_SHUTDOWN_GRACE_MILLIS,
) : WorkspaceScriptEngine {
    constructor(
        sources: WorkspaceScriptSourcePort,
        materials: WorkspaceMaterialReadPort,
        search: WorkspaceSearchPort? = null,
        fetch: WorkspaceFetchPort? = null,
        print: WorkspacePrintPort? = null,
    ) : this(
        sources = sources,
        materials = SynchronousWorkspaceMaterialReadPort(materials),
        search = search,
        fetch = fetch,
        print = print,
    )

    init {
        require(maxMaterialReadBytes in 1..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Invalid material read limit" }
        require(maxTotalMaterialReadBytes in maxMaterialReadBytes..WorkspaceHardLimits.MAX_TOTAL_BYTES) {
            "Invalid total material read limit"
        }
        require(maxTotalChangeBytes in 1..WorkspaceHardLimits.MAX_TOTAL_BYTES) { "Invalid total change limit" }
        require(maxTotalArtifactBytes in 1..WorkspaceHardLimits.MAX_TOTAL_BYTES) { "Invalid total artifact limit" }
        require(pollIntervalMillis in 1..1_000) { "Invalid poll interval" }
        require(shutdownGraceMillis in 0..5_000) { "Invalid shutdown grace" }
    }

    override suspend fun execute(request: WorkspaceScriptRequest): WorkspaceScriptResult {
        request.cancellation.throwIfCancelled()
        if (clock.nowMillis() > request.deadlineAtMillis) {
            throw WorkspaceScriptTimeoutException(WorkspaceRunStage.SCRIPT)
        }
        val source = sources.resolve(request.scriptId, request.entryPoint)
        if (source.scriptId != request.scriptId || source.entryPoint != request.entryPoint) {
            throw IllegalArgumentException("Script source identity mismatch")
        }
        if (source.sizeBytes > WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES) {
            throw WorkspaceScriptSourceUnavailable(WorkspaceScriptSourceUnavailable.Reason.TOO_LARGE)
        }
        request.cancellation.throwIfCancelled()
        if (clock.nowMillis() > request.deadlineAtMillis) {
            throw WorkspaceScriptTimeoutException(WorkspaceRunStage.SCRIPT)
        }
        val session = WorkspaceScriptHostSession(
            request = request,
            materials = materials,
            searchPort = search,
            fetchPort = fetch,
            printPort = print,
            clock = clock,
            policy = policy,
            maxMaterialReadBytes = maxMaterialReadBytes,
            maxTotalMaterialReadBytes = maxTotalMaterialReadBytes,
            maxTotalChangeBytes = maxTotalChangeBytes,
            maxTotalArtifactBytes = maxTotalArtifactBytes,
        )
        return runInterpreter(request, source, session)
    }

    private fun runInterpreter(
        request: WorkspaceScriptRequest,
        source: WorkspaceScriptSource,
        session: WorkspaceScriptHostSession,
    ): WorkspaceScriptResult {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<WorkerOutcome?>(null)
        val context = AtomicReference<QuickJs?>(null)
        val closeLock = Any()

        fun closeContext() {
            synchronized(closeLock) {
                val quickJs = context.getAndSet(null)
                if (quickJs != null) {
                    try {
                        quickJs.close()
                    } catch (failure: Throwable) {
                        // A close failure must not replace the typed run outcome; the context is
                        // already unreachable and the process will reclaim it at exit.
                    }
                }
            }
        }

        val worker = Thread({
            var sourceStarted = false
            try {
                val quickJs = try {
                    QuickJs.create()
                } catch (failure: LinkageError) {
                    throw WorkspaceScriptEngineUnavailable(failure)
                } catch (failure: OutOfMemoryError) {
                    throw WorkspaceScriptEngineUnavailable(failure)
                }
                context.set(quickJs)
                session.checkActive()
                bindHost(quickJs, request, session)
                quickJs.evaluate("globalThis.__lntInvocation = " + WorkspaceInvocationJson.encode(request), "lnt-invocation.js")
                quickJs.evaluate(HOST_PRELUDE, "lnt-workspace-host.js")
                session.checkActive()
                sourceStarted = true
                val value = quickJs.evaluate(wrapSource(source.text), sourceName(source))
                val exitCode = WorkspaceScriptResultCodec.decodeExitCode(value)
                outcome.set(
                    WorkerOutcome(
                        result = session.result(exitCode),
                        failure = null,
                        finishedAtMillis = clock.nowMillis(),
                    ),
                )
            } catch (failure: Throwable) {
                val mapped = when {
                    failure is WorkspaceScriptEngineUnavailable -> failure
                    failure is CancellationException -> failure
                    failure is WorkspaceScriptTimeoutException -> failure
                    !sourceStarted -> WorkspaceScriptEngineUnavailable(failure)
                    else -> failure
                }
                outcome.set(
                    WorkerOutcome(
                        result = null,
                        failure = mapped,
                        finishedAtMillis = clock.nowMillis(),
                    ),
                )
            } finally {
                closeContext()
                latch.countDown()
            }
        }, "lnt-quickjs-" + request.runId.value.take(24))
        worker.isDaemon = true
        worker.start()

        while (true) {
            if (latch.await(pollIntervalMillis, TimeUnit.MILLISECONDS)) break
            if (request.cancellation.isCancelled) {
                session.markAborted(WorkspaceScriptHostSession.AbortReason.CANCELLED)
                abort(worker, latch)
                throw CancellationException("Workspace run cancelled")
            }
            if (clock.nowMillis() > request.deadlineAtMillis) {
                session.markAborted(WorkspaceScriptHostSession.AbortReason.TIMED_OUT)
                abort(worker, latch)
                throw WorkspaceScriptTimeoutException(WorkspaceRunStage.SCRIPT)
            }
        }
        closeContext()

        val finished = outcome.get() ?: throw WorkspaceScriptEngineUnavailable()
        if (request.cancellation.isCancelled) throw CancellationException("Workspace run cancelled")
        if (session.abortReason == WorkspaceScriptHostSession.AbortReason.CANCELLED) {
            throw CancellationException("Workspace run cancelled")
        }
        if (session.abortReason == WorkspaceScriptHostSession.AbortReason.TIMED_OUT ||
            finished.finishedAtMillis > request.deadlineAtMillis
        ) {
            throw WorkspaceScriptTimeoutException(WorkspaceRunStage.SCRIPT)
        }
        val failure = finished.failure
        if (failure != null) {
            when (failure) {
                is WorkspaceScriptEngineUnavailable -> throw failure
                is CancellationException -> throw failure
                is WorkspaceScriptTimeoutException -> throw failure
                is WorkspaceScriptSourceUnavailable -> throw failure
                else -> return session.failureResult()
            }
        }
        return finished.result ?: session.failureResult()
    }

    private fun abort(worker: Thread, latch: CountDownLatch) {
        worker.interrupt()
        if (shutdownGraceMillis > 0) {
            try {
                latch.await(shutdownGraceMillis, TimeUnit.MILLISECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun bindHost(
        quickJs: QuickJs,
        request: WorkspaceScriptRequest,
        session: WorkspaceScriptHostSession,
    ) {
        quickJs.set("__lntUnicode", LgUnicodeHost::class.java, WorkspaceUnicodeHost())
        quickJs.set("__lntHost", LgCoreHost::class.java, CoreHost(session))
        if (WorkspaceCapability.SEARCH in request.grantedCapabilities) {
            quickJs.set("__lntSearch", LgSearchHost::class.java, SearchHost(session))
        }
        if (WorkspaceCapability.FETCH in request.grantedCapabilities) {
            quickJs.set("__lntFetch", LgFetchHost::class.java, FetchHost(session))
        }
        if (WorkspaceCapability.PRINT in request.grantedCapabilities) {
            quickJs.set("__lntPrint", LgPrintHost::class.java, PrintHost(session))
        }
    }

    private fun wrapSource(source: String): String = "(function(){\n\"use strict\";\n" + source + "\n})()"

    private fun sourceName(source: WorkspaceScriptSource): String =
        source.scriptId + "/" + source.entryPoint

    private class WorkerOutcome(
        val result: WorkspaceScriptResult?,
        val failure: Throwable?,
        val finishedAtMillis: Long,
    )

    interface LgUnicodeHost {
        fun normalize(text: String, form: String): String
        fun segments(text: String): String
    }

    internal interface LgCoreHost {
        fun readMaterial(id: String): String

        fun log(message: String)

        fun emitChange(json: String)

        fun emitArtifact(json: String)
    }

    internal interface LgSearchHost {
        fun search(query: String, maxResults: Int): String
    }

    internal interface LgFetchHost {
        fun fetch(url: String, maxBytes: Int): String
    }

    internal interface LgPrintHost {
        fun print(path: String)
    }

    private class CoreHost(private val session: WorkspaceScriptHostSession) : LgCoreHost {
        override fun readMaterial(id: String): String = session.readMaterial(id)
        override fun log(message: String) = session.log(message)
        override fun emitChange(json: String) = session.emitChange(json)
        override fun emitArtifact(json: String) = session.emitArtifact(json)
    }

    private class SearchHost(private val session: WorkspaceScriptHostSession) : LgSearchHost {
        override fun search(query: String, maxResults: Int): String = session.search(query, maxResults)
    }

    private class FetchHost(private val session: WorkspaceScriptHostSession) : LgFetchHost {
        override fun fetch(url: String, maxBytes: Int): String = session.fetch(url, maxBytes)
    }

    private class PrintHost(private val session: WorkspaceScriptHostSession) : LgPrintHost {
        override fun print(path: String) = session.print(path)
    }

    companion object {
        const val DEFAULT_MAX_MATERIAL_READ_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_MATERIAL_READ_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_CHANGE_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_ARTIFACT_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_POLL_INTERVAL_MILLIS: Long = 25L
        const val DEFAULT_SHUTDOWN_GRACE_MILLIS: Long = 100L

        /**
         * Installs `lg` and removes ambient globals. It is evaluated once per context before the
         * user source. Keep this string free of host paths and material content.
         */
        internal val HOST_PRELUDE: String = """
            (function () {
              "use strict";
              var host = __lntHost;
              var unicodeHost = __lntUnicode;
              var searchHost = typeof __lntSearch === "undefined" ? null : __lntSearch;
              var fetchHost = typeof __lntFetch === "undefined" ? null : __lntFetch;
              var printHost = typeof __lntPrint === "undefined" ? null : __lntPrint;
              var invocation = typeof __lntInvocation === "undefined" ? { arguments: {}, materials: [] } : __lntInvocation;
              for (var m = 0; m < invocation.materials.length; m++) Object.freeze(invocation.materials[m]);
              function toJson(value) {
                var text = JSON.stringify(value);
                if (typeof text !== "string") throw new TypeError("workspace value is not JSON");
                return text;
              }
              var api = {
                arguments: Object.freeze(invocation.arguments),
                materials: Object.freeze(invocation.materials),
                readMaterial: function (id) { return host.readMaterial(String(id)); },
                log: function (message) { host.log(String(message)); },
                emitChange: function (change) { host.emitChange(toJson(change)); },
                emitArtifact: function (artifact) { host.emitArtifact(toJson(artifact)); }
              };
              api.unicode = Object.freeze({
                normalize: function (text, form) {
                  if (typeof text !== "string" || typeof form !== "string") throw new TypeError("Expected text and form");
                  return unicodeHost.normalize(text, form);
                },
                segments: function (text) {
                  if (typeof text !== "string") throw new TypeError("Expected text");
                  return JSON.parse(unicodeHost.segments(text));
                }
              });
              if (searchHost !== null) {
                api.search = function (query, maxResults) {
                  if (typeof maxResults !== "number" || !isFinite(maxResults) ||
                      Math.floor(maxResults) !== maxResults || maxResults < 1 || maxResults > 20) {
                    throw new RangeError("search maxResults must be an integer in 1..20");
                  }
                  return JSON.parse(searchHost.search(String(query), maxResults));
                };
              }
              if (fetchHost !== null) {
                api.fetch = function (url, maxBytes) {
                  if (typeof maxBytes !== "number" || !isFinite(maxBytes) ||
                      Math.floor(maxBytes) !== maxBytes || maxBytes < 1 || maxBytes > 268435456) {
                    throw new RangeError("fetch maxBytes must be an integer in 1..268435456");
                  }
                  return JSON.parse(fetchHost.fetch(String(url), maxBytes));
                };
              }
              if (printHost !== null) {
                api.print = function (path) { printHost.print(String(path)); };
              }
              Object.defineProperty(globalThis, "lg", {
                value: Object.freeze(api), writable: false, enumerable: false, configurable: false
              });
              var blocked = ["std", "os", "process", "require", "module", "exports",
                             "fetch", "XMLHttpRequest", "WebSocket", "console",
                             "setTimeout", "setInterval", "queueMicrotask", "print"];
              for (var i = 0; i < blocked.length; i++) {
                try { delete globalThis[blocked[i]]; } catch (ignored) { }
              }
              try { delete globalThis.__lntUnicode; } catch (ignored) { }
              try { delete globalThis.__lntHost; } catch (ignored) { }
              try { delete globalThis.__lntSearch; } catch (ignored) { }
              try { delete globalThis.__lntFetch; } catch (ignored) { }
              try { delete globalThis.__lntPrint; } catch (ignored) { }
              try { delete globalThis.__lntInvocation; } catch (ignored) { }
            })();
        """.trimIndent()
    }
}
