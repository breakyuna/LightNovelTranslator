package com.breakyuna.noveltranslator.nativecore.workspace

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import app.cash.quickjs.QuickJs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One disposable interpreter in an Android isolated UID: no app files or network permissions.
 * Only the host binder supplied for this invocation grants access to selected materials.
 * Stop kills this process, including non-cooperative JS/native execution, without closing a
 * QuickJS context from another thread. The main process never accepts an engine-provided PID.
 */
class WorkspaceInterpreterService : Service() {
    private val started = AtomicBoolean()
    private val pipes = WorkspaceInterpreterPipes()
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in setOf(WorkspaceInterpreterIpc.EXECUTE, WorkspaceInterpreterIpc.TERMINATE)) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(WorkspaceInterpreterIpc.ENGINE)
            check(Binder.getCallingUid() == applicationInfo.uid) { "Invalid workspace caller" }
            if (code == WorkspaceInterpreterIpc.TERMINATE) {
                terminate()
                return true
            }
            check(started.compareAndSet(false, true)) { "Interpreter already used" }
            val host = requireNotNull(data.readStrongBinder())
            val capabilities = data.readInt()
            val source = WorkspaceInterpreterIpc.readText(data, WorkspaceScriptSourceLimits.MAX_SOURCE_BYTES)
            val invocation = WorkspaceInterpreterIpc.readText(data, 4L * 1024 * 1024)
            val invocationJson = com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue.parse(invocation,
                com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits(4 * 1024 * 1024, 8)).encode()
            // Host death tears down infinite loops even if the client dies before unbinding.
            host.linkToDeath({ terminate() }, 0)
            try {
                val value = QuickJs.create().use { js ->
                    js.set("__lntUnicode", QuickJsWorkspaceScriptEngine.LgUnicodeHost::class.java, WorkspaceUnicodeHost())
                    js.set("__lntHost", QuickJsWorkspaceScriptEngine.LgCoreHost::class.java, object : QuickJsWorkspaceScriptEngine.LgCoreHost {
                        override fun readMaterial(id: String) = call(host, WorkspaceInterpreterIpc.READ, id)
                        override fun log(message: String) { call(host, WorkspaceInterpreterIpc.LOG, message) }
                        override fun emitChange(json: String) { call(host, WorkspaceInterpreterIpc.CHANGE, json) }
                        override fun emitArtifact(json: String) { call(host, WorkspaceInterpreterIpc.ARTIFACT, json) }
                    })
                    if (capabilities and (1 shl WorkspaceCapability.SEARCH.ordinal) != 0) {
                        js.set("__lntSearch", QuickJsWorkspaceScriptEngine.LgSearchHost::class.java, object : QuickJsWorkspaceScriptEngine.LgSearchHost {
                            override fun search(query: String, maxResults: Int) = call(host, WorkspaceInterpreterIpc.SEARCH, query, maxResults)
                        })
                    }
                    if (capabilities and (1 shl WorkspaceCapability.FETCH.ordinal) != 0) {
                        js.set("__lntFetch", QuickJsWorkspaceScriptEngine.LgFetchHost::class.java, object : QuickJsWorkspaceScriptEngine.LgFetchHost {
                            override fun fetch(url: String, maxBytes: Int) = call(host, WorkspaceInterpreterIpc.FETCH, url, maxBytes)
                        })
                    }
                    if (capabilities and (1 shl WorkspaceCapability.PRINT.ordinal) != 0) {
                        js.set("__lntPrint", QuickJsWorkspaceScriptEngine.LgPrintHost::class.java, object : QuickJsWorkspaceScriptEngine.LgPrintHost {
                            override fun print(path: String) { call(host, WorkspaceInterpreterIpc.PRINT, path) }
                        })
                    }
                    js.evaluate("globalThis.__lntInvocation = " + invocationJson, "lnt-invocation.js")
                    js.evaluate(QuickJsWorkspaceScriptEngine.HOST_PRELUDE, "lnt-workspace-host.js")
                    WorkspaceScriptResultCodec.decodeExitCode(js.evaluate("(function(){\n\"use strict\";\n" + source + "\n})()", "workspace.js"))
                }
                requireNotNull(reply).writeNoException()
                reply.writeInt(value)
            } catch (_: Exception) {
                // Script bodies, host errors and native exceptions must not reach crash logs.
                requireNotNull(reply).writeNoException()
                reply.writeInt(1)
            }
            return true
        }
    }

    private fun call(host: IBinder, operation: Int, text: String, number: Int = 0): String {
        val input = Parcel.obtain()
        val output = Parcel.obtain()
        try {
            input.writeInterfaceToken(WorkspaceInterpreterIpc.HOST)
            input.writeInt(number)
            pipes.writeText(input, text)
            check(host.transact(operation, input, output, 0)) { "Host operation unavailable" }
            output.readException()
            check(output.readInt() == 0) { "Host operation rejected" }
            return WorkspaceInterpreterIpc.readText(output)
        } finally { input.recycle(); output.recycle() }
    }

    override fun onBind(intent: Intent): IBinder {
        check(Process.myUid() != applicationInfo.uid) { "Interpreter requires an isolated UID" }
        return endpoint
    }

    private fun terminate() {
        // A bad manifest merge must never turn workspace cancellation into an app-process kill.
        if (Process.myUid() != applicationInfo.uid) Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        pipes.close()
        super.onDestroy()
        terminate()
    }
}
