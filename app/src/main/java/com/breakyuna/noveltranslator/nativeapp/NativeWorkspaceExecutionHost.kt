package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspacePhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceRunner
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundWorkspaceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Service lifetime only; the skill execution remains owned by the application runner.
 * Commands and the observer scope run on the Service main thread.
 *
 * A stale/foreign token never acknowledges, stops or promotes another execution. Notification
 * failure or platform timeout stops only the token this host actually owns; the runner keeps the
 * slot until its adapter cleanup finishes.
 */
internal class NativeWorkspaceExecutionHost(
    private val runner: ForegroundWorkspaceRunner,
    private val scope: CoroutineScope,
    private val promote: (ForegroundWorkspaceSnapshot) -> Unit,
    private val update: (ForegroundWorkspaceSnapshot) -> Unit,
    private val dismiss: (Int) -> Unit,
) {
    enum class Command { START, STOP, IGNORE }

    private var observer: Job? = null
    private var watching: String? = null
    private var latestStartId = 0

    fun command(command: Command, token: String?, startId: Int) {
        latestStartId = startId
        val snapshot = runner.state.value?.takeIf {
            it.token == token && it.phase.active && runner.accepts(it.token)
        }
        if (snapshot == null || command == Command.IGNORE) {
            settleIdleHost()
            return
        }
        if (command == Command.STOP) {
            runner.stop(snapshot.token)
            settleIdleHost()
            return
        }

        val ownedToken = snapshot.token
        observer?.cancel()
        observer = null
        watching = ownedToken
        try {
            promote(snapshot)
            // The notification/foreground callback must actually run before opening the gate.
            runner.foregroundStarted(ownedToken)
        } catch (_: Exception) {
            if (runner.state.value?.let {
                    it.token == ownedToken && it.phase == ForegroundWorkspacePhase.PREPARING
                } == true
            ) {
                runner.startFailed(ownedToken)
            } else {
                runner.stop(ownedToken)
            }
            release()
            return
        }
        // Install the Job before running the initial StateFlow emission, which may be terminal.
        observer = scope.launch(start = CoroutineStart.LAZY) {
            runner.state.collect { current ->
                if (watching != ownedToken) return@collect
                if (current == null || current.token != ownedToken || !current.phase.active) {
                    // StateFlow may conflate an old terminal value after a new run prepared.
                    release()
                } else try {
                    update(current)
                } catch (_: Exception) {
                    runner.stop(ownedToken)
                    release()
                }
            }
        }
        observer?.start()
    }

    fun timeout() {
        watching?.let { runner.stop(it) }
        release() // The platform deadline cannot wait for durable application cleanup.
    }

    fun destroy() {
        watching?.let { runner.stop(it) }
        watching = null
        observer?.cancel()
        observer = null
    }

    private fun settleIdleHost() {
        val current = runner.state.value
        if (watching == null || current == null || current.token != watching || !current.phase.active) release()
    }

    private fun release() {
        watching = null
        observer?.cancel()
        observer = null
        dismiss(latestStartId)
    }
}
