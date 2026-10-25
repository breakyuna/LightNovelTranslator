package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** In-process immutable payload. Only the opaque execution token may enter a Service Intent. */
data class ForegroundAgentRequest(
    val ref: LocalProjectRef, val identity: RunIdentity,
    val configuration: ModelConfigurationRef, val input: String,
) {
    init {
        require(identity.projectId == ref.projectId && identity.chatId != null)
        require(input.isNotBlank() && input.length <= 8000)
        TextProjectFingerprint.utf8(input)
    }
    override fun toString() = "ForegroundAgentRequest(run=${identity.runId}, input=<redacted>)"
}

/** Presentation metadata; request acceptance is a persisted user message, not a paid dispatch. */
enum class AgentContextStatus { CHECKING, SUMMARIZING, COMPACTED, FAILED }

data class AgentExecutionProgress(
    val accepted: Boolean = false,
    val timelineRevision: Long = 0,
    val userMessageId: MessageId? = null,
    val contextStatus: AgentContextStatus? = null,
)

enum class ForegroundAgentPhase {
    WAITING_FOR_SERVICE, RUNNING, STOPPING, SUCCEEDED, CANCELLED, FAILED;
    val active get() = this == WAITING_FOR_SERVICE || this == RUNNING || this == STOPPING
}

data class ForegroundAgentSnapshot(
    val revision: Long, val token: String, val request: ForegroundAgentRequest,
    val phase: ForegroundAgentPhase, val progress: AgentExecutionProgress? = null,
)

class ForegroundAgentStartFailed : IllegalStateException("Foreground execution could not start")
class ForegroundAgentConflict : IllegalArgumentException("Foreground command payload changed")

class ForegroundAgentHandle internal constructor(
    val token: String, val request: ForegroundAgentRequest,
    private val completion: CompletableDeferred<AgentExecutionProgress>,
) {
    /** Cancelling a UI waiter never cancels the independently owned execution. */
    suspend fun await(): AgentExecutionProgress = completion.await()
}

/**
 * One process-owned execution. A foreground host must acknowledge the exact token before work.
 * Reservation and durable cleanup outlive UI subscribers. No sticky restart or automatic replay.
 */
class ForegroundAgentRunner(
    private val runtime: RuntimePort, private val runs: DurableRunExecutor, private val scope: CoroutineScope,
    private val runTurn: suspend (ForegroundAgentRequest, RunLease, RunProgressRecorder, (AgentExecutionProgress) -> Unit) -> AgentExecutionProgress,
    private val startTimeoutMillis: Long = 10_000,
) {
    private class Session(val handle: ForegroundAgentHandle, val lease: RunLease,
        val completion: CompletableDeferred<AgentExecutionProgress>, var snapshot: ForegroundAgentSnapshot) {
        val ready = CompletableDeferred<Unit>()
        var job: Job? = null
        var stopRequested = false
        var startFailure: ForegroundAgentStartFailed? = null
        var finished = false
    }
    private val monitor = Any()
    private var revision = 0L
    private var current: Session? = null
    private val mutable = MutableStateFlow<ForegroundAgentSnapshot?>(null)
    val state = mutable.asStateFlow()

    init { require(startTimeoutMillis > 0) }

    fun attach(token: String): ForegroundAgentHandle? = synchronized(monitor) {
        current?.handle?.takeIf { it.token == token }
    }

    fun accepts(token: String): Boolean = synchronized(monitor) {
        current?.let { it.handle.token == token && !it.finished } == true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun prepare(request: ForegroundAgentRequest): ForegroundAgentHandle {
        val session = synchronized(monitor) {
            current?.let { existing ->
                if (existing.handle.request.identity.commandId == request.identity.commandId) {
                    if (existing.handle.request != request) throw ForegroundAgentConflict()
                    return existing.handle
                }
                if (!existing.finished) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
            }
            // All competing UI/model/project writes see the reservation before service launch.
            val lease = runtime.beginRunSelectingChat(request.identity, RuntimeOwner.AGENT, workspace(request))
            val token = UUID.randomUUID().toString()
            val completion = CompletableDeferred<AgentExecutionProgress>()
            val handle = ForegroundAgentHandle(token, request, completion)
            Session(handle, lease, completion, ForegroundAgentSnapshot(++revision, token, request,
                ForegroundAgentPhase.WAITING_FOR_SERVICE)).also { current = it }
        }
        publish(session.snapshot)
        try {
            // ATOMIC transfers the reserved lease into finally even for a cancelled owner scope.
            val worker = scope.launch(start = CoroutineStart.ATOMIC) { execute(session) }
            synchronized(monitor) { session.job = worker; if (session.stopRequested) worker.cancel() }
        } catch (_: Exception) {
            runtime.requestStop(session.lease)
            runtime.finishRun(session.lease, workspace(request))
            finish(session, null, ForegroundAgentStartFailed())
        }
        return session.handle
    }

    fun foregroundStarted(token: String): Boolean {
        val (session, snapshot) = synchronized(monitor) {
            val session = current?.takeIf { it.handle.token == token && !it.finished && !it.stopRequested &&
                it.snapshot.phase == ForegroundAgentPhase.WAITING_FOR_SERVICE && !it.ready.isCompleted } ?: return false
            session.snapshot = session.snapshot.copy(revision = ++revision, phase = ForegroundAgentPhase.RUNNING)
            session to session.snapshot
        }
        publish(snapshot)
        // The phase arbitrates acknowledgement vs. failure under the lock. Resume coroutine
        // work outside the lock; a subsequent explicit stop may still cancel this gate.
        return session.ready.complete(Unit)
    }

    fun startFailed(token: String) {
        val (session, snapshot) = synchronized(monitor) {
            val session = current?.takeIf { it.handle.token == token && !it.finished &&
                it.snapshot.phase == ForegroundAgentPhase.WAITING_FOR_SERVICE && !it.ready.isCompleted } ?: return
            // Rejection is final. A late stop must not cancel failed-start journal cleanup
            // or change its durable FAILED outcome to CANCELLED.
            session.startFailure = ForegroundAgentStartFailed()
            session.snapshot = session.snapshot.copy(revision = ++revision, phase = ForegroundAgentPhase.STOPPING)
            session to session.snapshot
        }
        publish(snapshot)
        session.ready.completeExceptionally(requireNotNull(session.startFailure))
    }

    fun stop(token: String): Boolean {
        val (session, snapshot) = synchronized(monitor) {
            val session = current?.takeIf { it.handle.token == token && !it.finished } ?: return false
            if (session.startFailure != null) return true
            session.stopRequested = true
            runtime.requestStop(session.lease)
            session.snapshot = session.snapshot.copy(revision = ++revision, phase = ForegroundAgentPhase.STOPPING)
            session to session.snapshot
        }
        publish(snapshot)
        session.ready.cancel()
        synchronized(monitor) { session.job?.cancel() }
        return true
    }

    private suspend fun execute(session: Session) {
        var result: AgentExecutionProgress? = null
        var failure: Throwable? = null
        try {
            result = runs.executeReserved(session.handle.request.ref, session.lease, RuntimeOwner.AGENT,
                clearSelectedChat = workspace(session.handle.request)) { lease, recorder ->
                try { withTimeout(startTimeoutMillis) { session.ready.await() } }
                catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    startFailed(session.handle.token)
                    throw ForegroundAgentStartFailed()
                }
                currentCoroutineContext().ensureActive()
                runTurn(session.handle.request, lease, recorder) { progress ->
                    val snapshot = synchronized(monitor) {
                        if (current !== session || session.finished) null else
                            session.snapshot.copy(revision = ++revision, progress = progress).also { session.snapshot = it }
                    }
                    if (snapshot != null) publish(snapshot)
                }
            }
        } catch (error: Throwable) { failure = error; if (error !is Exception) throw error }
        finally { finish(session, result, failure) }
    }

    private fun finish(session: Session, result: AgentExecutionProgress?, failure: Throwable?) {
        val snapshot = synchronized(monitor) {
            if (session.finished) return
            session.finished = true
            val phase = when {
                session.startFailure != null || failure is ForegroundAgentStartFailed -> ForegroundAgentPhase.FAILED
                failure == null -> ForegroundAgentPhase.SUCCEEDED
                session.stopRequested || failure is CancellationException -> ForegroundAgentPhase.CANCELLED
                else -> ForegroundAgentPhase.FAILED
            }
            session.snapshot.copy(revision = ++revision, phase = phase, progress = result ?: session.snapshot.progress)
                .also { session.snapshot = it }
        }
        publish(snapshot)
        val terminalFailure = session.startFailure ?: failure
        if (terminalFailure == null) session.completion.complete(requireNotNull(result))
        else session.completion.completeExceptionally(terminalFailure)
    }

    private fun workspace(request: ForegroundAgentRequest) =
        ChatIdentity(request.ref.projectId, requireNotNull(request.identity.chatId))

    private fun publish(value: ForegroundAgentSnapshot) {
        mutable.update { previous -> if (previous == null || value.revision > previous.revision) value else previous }
    }
}
