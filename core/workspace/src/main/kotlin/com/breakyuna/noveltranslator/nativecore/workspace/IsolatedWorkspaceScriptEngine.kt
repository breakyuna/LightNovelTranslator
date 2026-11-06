package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production interpreter adapter. The application owns source resolution, all capabilities,
 * output validation and budgets; the isolated process only evaluates JS. No model, credentials,
 * filesystem root, project store or approval authority is sent to it. Every invocation uses a
 * fresh bound service and always terminates it, including after normal completion.
 */
class IsolatedWorkspaceScriptEngine(
    context: Context,
    private val sources: WorkspaceScriptSourcePort,
    private val materials: WorkspaceScriptMaterialReadPort,
    private val search: WorkspaceSearchPort? = null,
    private val fetch: WorkspaceFetchPort? = null,
    private val print: WorkspacePrintPort? = null,
    private val clock: WorkspaceClock = WorkspaceClock.SYSTEM,
) : WorkspaceScriptEngine {
    private val context = context.applicationContext

    override suspend fun execute(request: WorkspaceScriptRequest): WorkspaceScriptResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val remainingMillis = (request.deadlineAtMillis - clock.nowMillis()).coerceAtMost(WorkspaceHardLimits.MAX_TIMEOUT_MILLIS)
            val deadlineRealtime = SystemClock.elapsedRealtime() + remainingMillis.coerceAtLeast(0)
            fun checkActive() {
                request.cancellation.throwIfCancelled()
                if (clock.nowMillis() > request.deadlineAtMillis || SystemClock.elapsedRealtime() >= deadlineRealtime)
                    throw WorkspaceScriptTimeoutException()
            }
            currentCoroutineContext().ensureActive()
            checkActive()
            val source = sources.resolve(request.scriptId, request.entryPoint)
            require(source.scriptId == request.scriptId && source.entryPoint == request.entryPoint)
            val session = WorkspaceScriptHostSession(request, materials, search, fetch, print, clock,
                maxMaterialReadBytes = request.limits.maxMaterialReadBytes,
                maxTotalMaterialReadBytes = request.limits.maxTotalMaterialReadBytes)
            val gate = Any()
            val active = AtomicBoolean(true)
            val invalidHostCall = AtomicBoolean()
            val pipes = WorkspaceInterpreterPipes()
            val connectionReady = CountDownLatch(1)
            val completed = CountDownLatch(1)
            val remote = AtomicReference<IBinder?>(null)
            val exit = AtomicReference<Int?>(null)
            val failed = AtomicBoolean()
            val processDied = CountDownLatch(1)
            val host = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code !in WorkspaceInterpreterIpc.READ..WorkspaceInterpreterIpc.PRINT) {
                        return super.onTransact(code, data, reply, flags)
                    }
                    data.enforceInterface(WorkspaceInterpreterIpc.HOST)
                    val response = requireNotNull(reply)
                    try {
                        check(active.get())
                        val number = data.readInt()
                        val inputLimit = when (code) {
                            WorkspaceInterpreterIpc.READ -> 512L
                            WorkspaceInterpreterIpc.SEARCH -> 2_048L
                            WorkspaceInterpreterIpc.FETCH -> 16_384L
                            WorkspaceInterpreterIpc.PRINT -> 4_096L
                            WorkspaceInterpreterIpc.LOG -> 64L * 1024
                            else -> minOf(WorkspaceInterpreterIpc.MAX_TEXT_BYTES,
                                request.limits.maxOutputBytes * 6 + 65_536)
                        }
                        val text = pipes.readText(data, inputLimit)
                        val result = synchronized(gate) {
                            check(active.get())
                            session.checkActive()
                            when (code) {
                                WorkspaceInterpreterIpc.READ -> session.readMaterial(text)
                                WorkspaceInterpreterIpc.LOG -> { session.log(text); "" }
                                WorkspaceInterpreterIpc.CHANGE -> { session.emitChange(text); "" }
                                WorkspaceInterpreterIpc.ARTIFACT -> { session.emitArtifact(text); "" }
                                WorkspaceInterpreterIpc.SEARCH -> session.search(text, number)
                                WorkspaceInterpreterIpc.FETCH -> session.fetch(text, number)
                                WorkspaceInterpreterIpc.PRINT -> { session.print(text); "" }
                                else -> error("Invalid host operation")
                            }
                        }
                        response.writeNoException()
                        response.writeInt(0)
                        pipes.writeText(response, result)
                    } catch (_: Exception) {
                        // A caught JS bridge exception must never allow partial proposals to pass.
                        invalidHostCall.set(true)
                        response.setDataPosition(0)
                        response.setDataSize(0)
                        response.writeNoException()
                        response.writeInt(1)
                    }
                    return true
                }
            }
            val death = IBinder.DeathRecipient {
                failed.set(true)
                processDied.countDown()
                completed.countDown()
                connectionReady.countDown()
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    if (active.get()) {
                        remote.set(service)
                        try { service.linkToDeath(death, 0) } catch (_: Exception) { failed.set(true) }
                    }
                    connectionReady.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) { death.binderDied() }
                override fun onNullBinding(name: ComponentName) { death.binderDied() }
                override fun onBindingDied(name: ComponentName) { death.binderDied() }
            }
            var bound = false
            var completedNormally = false
            try {
                bound = context.bindService(Intent(context, WorkspaceInterpreterService::class.java), connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_NOT_FOREGROUND)
                if (!bound) throw WorkspaceScriptEngineUnavailable()
                while (!connectionReady.await(25, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    checkActive()
                }
                currentCoroutineContext().ensureActive()
                checkActive()
                val service = remote.get()
                if (service == null || failed.get()) throw WorkspaceScriptEngineUnavailable()
                Thread({
                    val input = Parcel.obtain()
                    val output = Parcel.obtain()
                    try {
                        input.writeInterfaceToken(WorkspaceInterpreterIpc.ENGINE)
                        input.writeStrongBinder(host)
                        input.writeInt(request.grantedCapabilities.fold(0) { mask, cap -> mask or (1 shl cap.ordinal) })
                        pipes.writeText(input, source.text)
                        pipes.writeText(input, WorkspaceInvocationJson.encode(request))
                        check(service.transact(WorkspaceInterpreterIpc.EXECUTE, input, output, 0))
                        output.readException()
                        val code = output.readInt()
                        require(code in 0..255)
                        exit.set(code)
                    } catch (_: Throwable) { failed.set(true) }
                    finally { input.recycle(); output.recycle(); completed.countDown() }
                }, "lnt-isolated-workspace").apply { isDaemon = true; start() }
                while (!completed.await(25, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    checkActive()
                }
                currentCoroutineContext().ensureActive()
                checkActive()
                if (failed.get() || exit.get() == null) throw WorkspaceScriptEngineUnavailable()
                synchronized(gate) {
                    active.set(false)
                    completedNormally = true
                    if (invalidHostCall.get()) session.failureResult() else session.result(requireNotNull(exit.get()))
                }
            } finally {
                active.set(false)
                if (!completedNormally) request.cancellation.cancel()
                // Revocation precedes process termination: late callbacks cannot publish output.
                pipes.close()
                remote.get()?.let { service ->
                    val input = Parcel.obtain()
                    try {
                        input.writeInterfaceToken(WorkspaceInterpreterIpc.ENGINE)
                        service.transact(WorkspaceInterpreterIpc.TERMINATE, input, null, IBinder.FLAG_ONEWAY)
                    } catch (_: Exception) { /* An already-dead process needs no termination. */ }
                    finally { input.recycle() }
                }
                if (bound) {
                    runCatching { context.unbindService(connection) }
                    if (remote.get() != null) runCatching { processDied.await(2, TimeUnit.SECONDS) }
                }
            }
        }
    }

    companion object {
        // Android binds one service instance per component, including across engine adapters.
        private val mutex = Mutex()
    }
}
