package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.engine.ForegroundAgentPhase
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundAgentRunner
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundAgentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Service lifetime only; execution remains owned by the application runner.
 * Commands and the observer scope run on the same dispatcher (the Service main thread).
 * Platform effects are callbacks so delayed/stale Intent and StateFlow conflation are testable.
 */
internal class NativeAgentHost(
    private val runner: ForegroundAgentRunner,
    private val scope: CoroutineScope,
    private val promote: (ForegroundAgentSnapshot) -> Unit,
    private val update: (ForegroundAgentSnapshot) -> Unit,
    private val dismiss: (Int) -> Unit,
) {
    enum class Command { START, STOP, IGNORE }

    private var observer: Job? = null
    private var watching: String? = null
    private var latestStartId = 0

    fun command(command: Command, token: String?, startId: Int) {
        latestStartId = startId
        val snapshot = runner.state.value?.takeIf { it.token == token && it.phase.active && runner.accepts(it.token) }
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
            // The callback must actually enter foreground before opening this gate.
            runner.foregroundStarted(ownedToken)
        } catch (_: Exception) {
            if (runner.state.value?.let { it.token == ownedToken &&
                    it.phase == ForegroundAgentPhase.WAITING_FOR_SERVICE } == true) runner.startFailed(ownedToken)
            else runner.stop(ownedToken)
            release()
            return
        }
        // Install the Job before running the initial StateFlow emission, which may be terminal.
        observer = scope.launch(start = CoroutineStart.LAZY) {
            runner.state.collect { current ->
                if (watching != ownedToken) return@collect
                if (current == null || current.token != ownedToken || !current.phase.active) {
                    // StateFlow can skip an old terminal value if a new run has already prepared.
                    // Release this host without stopping the newer execution.
                    release()
                } else try { update(current) }
                catch (_: Exception) {
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
        // A pending execution without an acknowledged host does not justify keeping this
        // started Service alive. Its own START Intent must arrive, or the runner times out.
        if (watching == null || current == null || current.token != watching || !current.phase.active) release()
    }

    private fun release() {
        watching = null
        observer?.cancel()
        observer = null
        dismiss(latestStartId)
    }
}
