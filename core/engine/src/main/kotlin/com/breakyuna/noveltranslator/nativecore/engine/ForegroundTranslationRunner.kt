package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Metadata only. No text, credentials, UI objects, or executable payload goes into a service Intent. */
data class ForegroundTranslationRequest(
    val ref: LocalProjectRef, val identity: RunIdentity, val configuration: ModelConfigurationRef,
    val expectedRevision: Long, val afterId: Long, val pageSize: Int = 40,
    val scope: TranslationScope = TranslationScope.PAGE,
    val options: RemoteTranslationOptions? = null,
    val continuation: Boolean = false,
) {
    init {
        require(identity.projectId == ref.projectId && identity.chatId == null)
        require(expectedRevision >= 0 && afterId >= 0 && pageSize in 1..100)
        require(scope != TranslationScope.PROJECT || afterId == 0L)
        require(!continuation || options != null)
    }
}

enum class TranslationScope { PAGE, PROJECT }

enum class ForegroundTranslationPhase {
    WAITING_FOR_SERVICE, RUNNING, PAUSING, STOPPING, PAUSED, SUCCEEDED, CANCELLED, FAILED;
    val active get() = this == WAITING_FOR_SERVICE || this == RUNNING || this == PAUSING || this == STOPPING
}

data class ForegroundTranslationSnapshot(
    val revision: Long, val token: String, val request: ForegroundTranslationRequest,
    val phase: ForegroundTranslationPhase, val progress: TranslationProgress? = null,
)

class ForegroundTranslationPaused : CancellationException("Translation paused")
class ForegroundTranslationStartFailed : IllegalStateException("Foreground execution could not start")
class ForegroundTranslationConflict : IllegalArgumentException("Foreground command payload changed")

class ForegroundTranslationHandle internal constructor(
    val token: String, val request: ForegroundTranslationRequest,
    private val completion: CompletableDeferred<TranslationProgress>,
) {
    /** Cancelling a UI waiter never cancels the independently owned execution. */
    suspend fun await(): TranslationProgress = completion.await()
}

/**
 * One process-owned execution. A foreground host must acknowledge the exact token before work.
 * Reservation and durable cleanup outlive UI subscribers. No sticky restart or automatic replay.
 */
class ForegroundTranslationRunner(
    private val runtime: RuntimePort, private val runs: DurableRunExecutor, private val scope: CoroutineScope,
    private val translate: suspend (ForegroundTranslationRequest, RunLease, RunProgressRecorder, (TranslationProgress) -> Unit) -> TranslationProgress,
    private val startTimeoutMillis: Long = 10_000,
) {
    constructor(runtime: RuntimePort, runs: DurableRunExecutor, scope: CoroutineScope, translations: TextTranslationService) :
        this(runtime, runs, scope, { request, lease, recorder, progress ->
            when (request.scope) {
                TranslationScope.PAGE -> translations.translateReservedPage(request.ref, lease, recorder, request.configuration,
                    request.expectedRevision, request.afterId, request.pageSize, progress, request.options, request.continuation)
                TranslationScope.PROJECT -> translations.translateReservedProject(request.ref, lease, recorder, request.configuration,
                    request.expectedRevision, request.pageSize, progress, request.options, request.continuation)
            }
        })

    private class Session(val handle: ForegroundTranslationHandle, val lease: RunLease,
        val completion: CompletableDeferred<TranslationProgress>, var snapshot: ForegroundTranslationSnapshot) {
        val ready = CompletableDeferred<Unit>()
        var job: Job? = null
        var stopRequested = false
        var pauseRequested = false
        var startFailure: ForegroundTranslationStartFailed? = null
        var finished = false
    }
    private val monitor = Any()
    private var revision = 0L
    private var current: Session? = null
    private val mutable = MutableStateFlow<ForegroundTranslationSnapshot?>(null)
    val state = mutable.asStateFlow()

    init { require(startTimeoutMillis > 0) }

    fun attach(token: String): ForegroundTranslationHandle? = synchronized(monitor) {
        current?.handle?.takeIf { it.token == token }
    }

    fun accepts(token: String): Boolean = synchronized(monitor) {
        current?.let { it.handle.token == token && !it.finished } == true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun prepare(request: ForegroundTranslationRequest): ForegroundTranslationHandle {
        val session = synchronized(monitor) {
            current?.let { existing ->
                if (existing.handle.request.identity.commandId == request.identity.commandId) {
                    if (existing.handle.request == request && !(existing.finished && request.continuation &&
                        existing.snapshot.phase in setOf(ForegroundTranslationPhase.PAUSED,
                            ForegroundTranslationPhase.CANCELLED, ForegroundTranslationPhase.FAILED))) return existing.handle
                    // Explicit continuation may only advance revision on the exact frozen task payload.
                    val previous = existing.handle.request
                    if (!existing.finished || !request.continuation || request.expectedRevision < previous.expectedRevision ||
                        request.copy(expectedRevision = previous.expectedRevision, continuation = previous.continuation) != previous)
                        throw ForegroundTranslationConflict()
                }
                if (!existing.finished) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
            }
            // All competing UI/model/project writes see the reservation before service launch.
            val lease = runtime.beginRunSelectingChat(request.identity, RuntimeOwner.TRANSLATION, workspace(request))
            val token = UUID.randomUUID().toString()
            val completion = CompletableDeferred<TranslationProgress>()
            val handle = ForegroundTranslationHandle(token, request, completion)
            Session(handle, lease, completion, ForegroundTranslationSnapshot(++revision, token, request,
                ForegroundTranslationPhase.WAITING_FOR_SERVICE)).also { current = it }
        }
        publish(session.snapshot)
        try {
            // ATOMIC transfers the reserved lease into finally even for a cancelled owner scope.
            val worker = scope.launch(start = CoroutineStart.ATOMIC) { execute(session) }
            synchronized(monitor) {
                session.job = worker
                if (session.stopRequested) worker.cancel()
                else if (session.pauseRequested && runtime.snapshot().activeRun?.outstandingModelRequests == 0)
                    worker.cancel(ForegroundTranslationPaused())
            }
        } catch (_: Exception) {
            runtime.requestStop(session.lease)
            runtime.finishRun(session.lease, workspace(request))
            finish(session, null, ForegroundTranslationStartFailed())
        }
        return session.handle
    }

    fun foregroundStarted(token: String): Boolean {
        val (session, snapshot) = synchronized(monitor) {
            val session = current?.takeIf { it.handle.token == token && !it.finished && !it.stopRequested &&
                it.snapshot.phase == ForegroundTranslationPhase.WAITING_FOR_SERVICE && !it.ready.isCompleted } ?: return false
            session.snapshot = session.snapshot.copy(revision = ++revision, phase = ForegroundTranslationPhase.RUNNING)
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
                it.snapshot.phase == ForegroundTranslationPhase.WAITING_FOR_SERVICE && !it.ready.isCompleted } ?: return
            // Rejection is final. A late stop must not cancel failed-start journal cleanup
            // or change its durable FAILED outcome to CANCELLED.
            session.startFailure = ForegroundTranslationStartFailed()
            session.snapshot = session.snapshot.copy(revision = ++revision, phase = ForegroundTranslationPhase.STOPPING)
            session to session.snapshot
        }
        publish(snapshot)
        session.ready.completeExceptionally(requireNotNull(session.startFailure))
    }

    /** Available after durable task preparation. In-flight model operations are allowed to settle. */
    fun pause(token: String): Boolean {
        val (session, snapshot) = synchronized(monitor) {
            val session = current?.takeIf { it.handle.token == token && !it.finished && !it.stopRequested &&
                it.startFailure == null && it.handle.request.options != null && it.snapshot.progress != null &&
                it.snapshot.phase in setOf(ForegroundTranslationPhase.RUNNING, ForegroundTranslationPhase.PAUSING) } ?: return false
            if (session.pauseRequested) return true
            if (!runtime.requestPause(session.lease)) return false
            session.pauseRequested = true
            session.snapshot = session.snapshot.copy(revision = ++revision, phase = ForegroundTranslationPhase.PAUSING)
            session to session.snapshot
        }
        publish(snapshot)
        // The pause barrier already prevents new model leases. Wake a queued/RPM waiter promptly.
        if (runtime.snapshot().activeRun?.outstandingModelRequests == 0)
            synchronized(monitor) { session.job?.cancel(ForegroundTranslationPaused()) }
        return true
    }

    fun stop(token: String): Boolean {
        val (session, snapshot) = synchronized(monitor) {
            val session = current?.takeIf { it.handle.token == token && !it.finished } ?: return false
            if (session.startFailure != null) return true
            session.stopRequested = true
            runtime.requestStop(session.lease)
            session.snapshot = session.snapshot.copy(revision = ++revision, phase = ForegroundTranslationPhase.STOPPING)
            session to session.snapshot
        }
        publish(snapshot)
        session.ready.cancel()
        synchronized(monitor) { session.job?.cancel() }
        return true
    }

    private suspend fun execute(session: Session) {
        var result: TranslationProgress? = null
        var failure: Throwable? = null
        try {
            result = runs.executeReserved(session.handle.request.ref, session.lease, RuntimeOwner.TRANSLATION,
                clearSelectedChat = workspace(session.handle.request)) { lease, recorder ->
                try { withTimeout(startTimeoutMillis) { session.ready.await() } }
                catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    startFailed(session.handle.token)
                    throw ForegroundTranslationStartFailed()
                }
                currentCoroutineContext().ensureActive()
                translate(session.handle.request, lease, recorder) { progress ->
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

    private fun finish(session: Session, result: TranslationProgress?, failure: Throwable?) {
        val snapshot = synchronized(monitor) {
            if (session.finished) return
            session.finished = true
            val phase = when {
                session.startFailure != null || failure is ForegroundTranslationStartFailed -> ForegroundTranslationPhase.FAILED
                session.pauseRequested && !session.stopRequested && (failure == null || failure is CancellationException ||
                    failure is RuntimeUnavailable && failure.reason == RuntimeUnavailable.Reason.STOPPING) -> ForegroundTranslationPhase.PAUSED
                failure == null -> ForegroundTranslationPhase.SUCCEEDED
                session.stopRequested || failure is CancellationException -> ForegroundTranslationPhase.CANCELLED
                else -> ForegroundTranslationPhase.FAILED
            }
            session.snapshot.copy(revision = ++revision, phase = phase, progress = result ?: session.snapshot.progress)
                .also { session.snapshot = it }
        }
        publish(snapshot)
        val terminalFailure = session.startFailure ?: if (snapshot.phase == ForegroundTranslationPhase.PAUSED)
            ForegroundTranslationPaused() else failure
        if (terminalFailure == null) session.completion.complete(requireNotNull(result))
        else session.completion.completeExceptionally(terminalFailure)
    }

    private fun workspace(request: ForegroundTranslationRequest) =
        ChatIdentity(request.ref.projectId, ChatId("translation-workspace"))

    private fun publish(value: ForegroundTranslationSnapshot) {
        mutable.update { previous -> if (previous == null || value.revision > previous.revision) value else previous }
    }
}
