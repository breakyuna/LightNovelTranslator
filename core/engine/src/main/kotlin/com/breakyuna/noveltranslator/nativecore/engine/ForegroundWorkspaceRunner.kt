package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeUnavailable
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.regex.Pattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Fixed UI/observer bounds for one foreground workspace execution. The actual script, input and
 * artifacts remain with the execution adapter; only this bounded presentation tail is exposed.
 */
object ForegroundWorkspaceLimits {
    const val MAX_LOG_LINES: Int = 48
    const val MAX_LOG_CHARS: Int = 512
    const val MAX_ARTIFACTS: Int = 32
    const val MAX_SUMMARY_CHARS: Int = 512
    const val MAX_INPUT_JSON_CHARS: Int = 16_384
    const val MAX_SCRIPT_BYTES: Long = 512L * 1024
}

/**
 * Metadata-only description of one directly executed skill script.
 *
 * The HTTP/Service layer may carry only [LocalProjectRef] and a process token. The script body
 * and any large workspace materials are never part of this object, and [inputJson] is redacted
 * from [toString]. [scriptSha256] and [scriptSizeBytes] are the frozen identity that the
 * execution adapter must re-check against the skill store before running.
 */
data class ForegroundWorkspaceRequest(
    val ref: LocalProjectRef,
    val identity: RunIdentity,
    val skillId: String,
    val entryPoint: String,
    val scriptSha256: String,
    val scriptSizeBytes: Long,
    val projectRevision: Long,
    val policy: ForegroundWorkspacePolicy = ForegroundWorkspacePolicy(),
    val inputJson: String = "{}",
    val allowFetch: Boolean = false,
) {
    init {
        require(identity.projectId == ref.projectId) { "Run does not belong to this project" }
        requireSafeSegment(skillId, 128, "skill identity")
        requireSafeRelativePath(entryPoint, 512, "entry point")
        requireSha256Hex(scriptSha256)
        require(scriptSizeBytes in 0L..ForegroundWorkspaceLimits.MAX_SCRIPT_BYTES) {
            "Skill script too large"
        }
        require(projectRevision >= 0) { "Invalid project revision" }
        require(inputJson.length <= ForegroundWorkspaceLimits.MAX_INPUT_JSON_CHARS) {
            "Skill input too large"
        }
        // Parsing here prevents malformed or unexpectedly deep input from reaching a Service.
        JsonObjectValue.parse(inputJson, JsonInputLimits(ForegroundWorkspaceLimits.MAX_INPUT_JSON_CHARS, 16))
        require(!allowFetch || policy.allowFetch) { "Network access was not granted by the skill policy" }
    }

    /** Alias kept explicit for callers that distinguish script bytes from artifact bytes. */
    val scriptSize: Long get() = scriptSizeBytes

    override fun toString(): String =
        "ForegroundWorkspaceRequest(run=${identity.runId.value}, skill=$skillId, " +
            "entry=$entryPoint, input=<redacted>)"
}

/**
 * A transport-safe snapshot of the skill policy. The workspace module owns the authoritative
 * `WorkspaceSkillPolicy`; the application adapter maps that policy into this engine-local value
 * without making the JVM engine depend on the Android workspace artifact.
 */
data class ForegroundWorkspacePolicy(
    val allowMaterials: Boolean = true,
    val allowArtifacts: Boolean = true,
    val allowSearch: Boolean = false,
    val allowFetch: Boolean = false,
    val allowProposeChanges: Boolean = false,
    val allowPrint: Boolean = true,
    val maxRunMillis: Int = 30_000,
    val maxOutputBytes: Int = 256 * 1024,
    val maxArtifactBytes: Int = 256 * 1024,
    val maxChangeBytes: Int = 128 * 1024,
) {
    init {
        require(maxRunMillis in 100..1_800_000) { "Invalid skill run limit" }
        require(maxOutputBytes in 1..16 * 1024 * 1024) { "Invalid skill output limit" }
        require(maxArtifactBytes in 1..64 * 1024 * 1024) { "Invalid skill artifact limit" }
        require(maxChangeBytes in 1..64 * 1024 * 1024) { "Invalid skill change limit" }
    }

    /** Capabilities that an adapter may grant to this invocation. */
    fun grants(capability: ForegroundWorkspaceCapability): Boolean = when (capability) {
        ForegroundWorkspaceCapability.READ_MATERIALS -> allowMaterials
        ForegroundWorkspaceCapability.EMIT_ARTIFACTS -> allowArtifacts
        ForegroundWorkspaceCapability.SEARCH -> allowSearch
        ForegroundWorkspaceCapability.FETCH -> allowFetch
        ForegroundWorkspaceCapability.PROPOSE_CHANGES -> allowProposeChanges
        ForegroundWorkspaceCapability.PRINT -> allowPrint
    }

    override fun toString(): String =
        "ForegroundWorkspacePolicy(materials=$allowMaterials, artifacts=$allowArtifacts, " +
            "search=$allowSearch, fetch=$allowFetch, propose=$allowProposeChanges, " +
            "print=$allowPrint, maxRun=$maxRunMillis)"
}

enum class ForegroundWorkspaceCapability {
    READ_MATERIALS,
    EMIT_ARTIFACTS,
    SEARCH,
    FETCH,
    PROPOSE_CHANGES,
    PRINT,
}

enum class ForegroundWorkspaceStage {
    PREPARING,
    RUNNING,
    MATERIALS,
    SCRIPT,
    OUTPUT_VALIDATION,
    PREFLIGHT,
    APPROVAL,
    APPLY,
    ARTIFACTS,
    FINISHING,
}

enum class ForegroundWorkspacePhase {
    PREPARING,
    RUNNING,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    val active: Boolean
        get() = this == PREPARING || this == RUNNING || this == STOPPING
}

enum class ForegroundWorkspaceFailureReason {
    START_FAILED,
    START_TIMEOUT,
    EXECUTION_TIMEOUT,
    EXECUTION_FAILED,
    INTERRUPTED,
}

enum class ForegroundWorkspaceTimeoutStage { START, EXECUTION }

class ForegroundWorkspaceStartFailed : IllegalStateException("Foreground skill execution could not start")
class ForegroundWorkspaceConflict : IllegalArgumentException("Foreground skill command payload changed")
class ForegroundWorkspaceInterrupted : CancellationException("Foreground skill execution was interrupted")

class ForegroundWorkspaceTimedOut(val stage: ForegroundWorkspaceTimeoutStage) :
    IllegalStateException("Foreground skill execution timed out during $stage")

/** Bounded artifact metadata; never the artifact body. */
data class ForegroundWorkspaceArtifact(
    val path: String,
    val sizeBytes: Long,
    val sha256: String? = null,
) {
    init {
        requireSafeRelativePath(path, 512, "artifact path")
        require(sizeBytes in 0L..64L * 1024 * 1024) { "Invalid artifact size" }
        if (sha256 != null) requireSha256Hex(sha256)
    }

    override fun toString(): String =
        "ForegroundWorkspaceArtifact(path=$path, size=$sizeBytes)"
}

/** A port result contains only a short status line and artifact summaries, not file bodies. */
class ForegroundWorkspaceExecutionResult(
    val summary: String? = null,
    artifacts: List<ForegroundWorkspaceArtifact> = emptyList(),
) {
    init {
        val text = summary
        require(text == null || text.length <= ForegroundWorkspaceLimits.MAX_SUMMARY_CHARS) {
            "Skill result summary too long"
        }
        require(text == null || text.none { it.isISOControl() }) {
            "Control character in skill result summary"
        }
        require(artifacts.size <= ForegroundWorkspaceLimits.MAX_ARTIFACTS * 2) {
            "Too many skill artifacts"
        }
    }

    val artifacts: List<ForegroundWorkspaceArtifact> =
        Collections.unmodifiableList(ArrayList(artifacts))

    override fun toString(): String =
        "ForegroundWorkspaceExecutionResult(artifacts=${artifacts.size})"
}

/** Immutable, bounded observation copied into [ForegroundWorkspaceSnapshot]. */
class ForegroundWorkspaceObservation(
    val stage: ForegroundWorkspaceStage = ForegroundWorkspaceStage.PREPARING,
    logs: List<String> = emptyList(),
    artifacts: List<ForegroundWorkspaceArtifact> = emptyList(),
    val logsTruncated: Boolean = false,
    val artifactsTruncated: Boolean = false,
) {
    init {
        require(logs.size <= ForegroundWorkspaceLimits.MAX_LOG_LINES) { "Too many log lines" }
        require(artifacts.size <= ForegroundWorkspaceLimits.MAX_ARTIFACTS) { "Too many artifacts" }
    }

    val logs: List<String> = Collections.unmodifiableList(ArrayList(logs))
    val artifacts: List<ForegroundWorkspaceArtifact> =
        Collections.unmodifiableList(ArrayList(artifacts))

    override fun toString(): String =
        "ForegroundWorkspaceObservation(stage=$stage, logs=${logs.size}, " +
            "artifacts=${artifacts.size}, truncated=${logsTruncated || artifactsTruncated})"
}

/**
 * Receives only bounded presentation facts from an execution adapter. Implementations may call
 * these methods from a worker thread; the runner copies values under its own lock.
 */
interface ForegroundWorkspaceObserver {
    fun stage(stage: ForegroundWorkspaceStage) = Unit
    fun log(line: String) = Unit
    fun artifact(artifact: ForegroundWorkspaceArtifact) = Unit
}

/**
 * Executes one already-authorized skill invocation. The adapter owns script resolution, runtime
 * leases, project writes and network policy; it must re-check the frozen script identity before
 * running. This runner never dispatches a model request and never parses or stores a script body.
 */
fun interface WorkspaceExecutionPort {
    suspend fun execute(
        request: ForegroundWorkspaceRequest,
        observer: ForegroundWorkspaceObserver,
    ): ForegroundWorkspaceExecutionResult
}

data class ForegroundWorkspaceSnapshot(
    val revision: Long,
    val token: String,
    val request: ForegroundWorkspaceRequest,
    val phase: ForegroundWorkspacePhase,
    val observation: ForegroundWorkspaceObservation = ForegroundWorkspaceObservation(),
    val result: ForegroundWorkspaceExecutionResult? = null,
    val failure: ForegroundWorkspaceFailureReason? = null,
) {
    override fun toString(): String =
        "ForegroundWorkspaceSnapshot(run=${request.identity.runId.value}, phase=$phase, " +
            "logs=${observation.logs.size}, artifacts=${observation.artifacts.size})"
}

class ForegroundWorkspaceHandle internal constructor(
    val token: String,
    val request: ForegroundWorkspaceRequest,
    private val completion: CompletableDeferred<ForegroundWorkspaceExecutionResult>,
) {
    /** Cancelling a UI waiter detaches it; it never cancels the independently scoped execution. */
    suspend fun await(): ForegroundWorkspaceExecutionResult = completion.await()
}

/**
 * One process-owned foreground skill execution.
 *
 * The application scope owns the coroutine. A Service/UI host must attach and acknowledge the
 * exact opaque token before the adapter can run. Cancelling a waiter or destroying a host only
 * detaches presentation; [stop] is the explicit cancellation command and releases the single
 * execution slot only after the adapter's cleanup coroutine has actually finished.
 *
 * Tokens are random, process-local, expire after [startTimeoutMillis] and can acknowledge a
 * start only once. A completed command is never replayed automatically; a different command is
 * required for a new execution.
 */
class ForegroundWorkspaceRunner(
    private val scope: CoroutineScope,
    private val execution: WorkspaceExecutionPort,
    private val startTimeoutMillis: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Session(
        val handle: ForegroundWorkspaceHandle,
        val completion: CompletableDeferred<ForegroundWorkspaceExecutionResult>,
        var snapshot: ForegroundWorkspaceSnapshot,
        val tokenExpiresAtMillis: Long,
    ) {
        val ready = CompletableDeferred<Unit>()
        var job: Job? = null
        var stopRequested = false
        var startFailure: Throwable? = null
        var startTimedOut = false
        var finished = false
        var stage = ForegroundWorkspaceStage.PREPARING
        val logs = ArrayDeque<String>()
        val artifacts = LinkedHashMap<String, ForegroundWorkspaceArtifact>()
        var logsTruncated = false
        var artifactsTruncated = false
        var observer: ForegroundWorkspaceObserver? = null

        fun observation(): ForegroundWorkspaceObservation = ForegroundWorkspaceObservation(
            stage = stage,
            logs = logs.toList(),
            artifacts = artifacts.values.toList(),
            logsTruncated = logsTruncated,
            artifactsTruncated = artifactsTruncated,
        )
    }

    private val monitor = Any()
    private var revision = 0L
    private var current: Session? = null
    private val mutable = MutableStateFlow<ForegroundWorkspaceSnapshot?>(null)
    val state = mutable.asStateFlow()

    init {
        require(startTimeoutMillis > 0) { "Start timeout must be positive" }
    }

    /** Re-attaches to the still-current execution without consuming or refreshing its token. */
    fun attach(token: String): ForegroundWorkspaceHandle? = synchronized(monitor) {
        current?.handle?.takeIf { it.token == token }
    }

    /** Whether the exact token still names a non-terminal execution. */
    fun accepts(token: String): Boolean = synchronized(monitor) {
        current?.let { it.handle.token == token && !it.finished } == true
    }

    /**
     * Reserves the single foreground slot and starts a coroutine that waits for the foreground
     * acknowledgement. Repeating the exact command returns the same handle and never replays it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun prepare(request: ForegroundWorkspaceRequest): ForegroundWorkspaceHandle {
        val session = synchronized(monitor) {
            current?.let { existing ->
                if (existing.handle.request.identity.commandId == request.identity.commandId) {
                    if (existing.handle.request != request) throw ForegroundWorkspaceConflict()
                    return existing.handle
                }
                if (!existing.finished) throw RuntimeUnavailable(RuntimeUnavailable.Reason.BUSY)
            }
            val token = UUID.randomUUID().toString()
            val completion = CompletableDeferred<ForegroundWorkspaceExecutionResult>()
            val handle = ForegroundWorkspaceHandle(token, request, completion)
            val snapshot = ForegroundWorkspaceSnapshot(
                revision = ++revision,
                token = token,
                request = request,
                phase = ForegroundWorkspacePhase.PREPARING,
            )
            Session(
                handle = handle,
                completion = completion,
                snapshot = snapshot,
                tokenExpiresAtMillis = saturatingAdd(clock(), startTimeoutMillis),
            ).also {
                it.observer = Observer(it)
                current = it
            }
        }
        publish(session.snapshot)
        try {
            // ATOMIC guarantees the finally block still settles an already-cancelled owner scope.
            val worker = scope.launch(start = CoroutineStart.ATOMIC) { execute(session) }
            synchronized(monitor) {
                session.job = worker
                if (session.stopRequested) worker.cancel()
            }
        } catch (_: Exception) {
            synchronized(monitor) { session.startFailure = ForegroundWorkspaceStartFailed() }
            finish(session, null, ForegroundWorkspaceStartFailed())
        }
        return session.handle
    }

    /**
     * Consumes the start acknowledgement once. Returns false for a foreign, expired, stopped or
     * already-acknowledged token; expiration settles the execution as FAILED/START_TIMEOUT.
     */
    fun foregroundStarted(token: String): Boolean {
        var expired = false
        val session = synchronized(monitor) {
            val session = current?.takeIf {
                it.handle.token == token && !it.finished && !it.stopRequested &&
                    it.startFailure == null && it.snapshot.phase == ForegroundWorkspacePhase.PREPARING &&
                    !it.ready.isCompleted
            } ?: return false
            if (clock() > session.tokenExpiresAtMillis) {
                expired = true
                session.startTimedOut = true
                session.startFailure = ForegroundWorkspaceTimedOut(ForegroundWorkspaceTimeoutStage.START)
                session.snapshot = session.snapshot.copy(
                    revision = ++revision,
                    phase = ForegroundWorkspacePhase.STOPPING,
                    failure = ForegroundWorkspaceFailureReason.START_TIMEOUT,
                )
            } else {
                session.stage = ForegroundWorkspaceStage.RUNNING
                session.snapshot = session.snapshot.copy(
                    revision = ++revision,
                    phase = ForegroundWorkspacePhase.RUNNING,
                    observation = session.observation(),
                )
            }
            session
        }
        publish(session.snapshot)
        if (expired) {
            // The acknowledged host never opened the gate; settle the waiting worker.
            session.ready.cancel()
            synchronized(monitor) { session.job?.cancel() }
            return false
        }
        // Acknowledgement and rejection are arbitrated under the monitor; resume outside it.
        return session.ready.complete(Unit)
    }

    /** Rejects an unacknowledged start exactly once. Later stop calls keep the failed outcome. */
    fun startFailed(token: String) {
        val session = synchronized(monitor) {
            val active = current?.takeIf {
                it.handle.token == token && !it.finished && it.startFailure == null &&
                    it.snapshot.phase == ForegroundWorkspacePhase.PREPARING && !it.ready.isCompleted
            } ?: return
            active.startFailure = ForegroundWorkspaceStartFailed()
            active.snapshot = active.snapshot.copy(
                revision = ++revision,
                phase = ForegroundWorkspacePhase.STOPPING,
                failure = ForegroundWorkspaceFailureReason.START_FAILED,
            )
            active
        }
        publish(session.snapshot)
        session.ready.completeExceptionally(requireNotNull(session.startFailure))
    }

    /**
     * Requests cancellation only for the matching token. The slot remains occupied and [accepts]
     * stays true until the execution coroutine has run its cleanup and [finish] settles it.
     */
    fun stop(token: String): Boolean {
        val session = synchronized(monitor) {
            val session = current?.takeIf { it.handle.token == token && !it.finished } ?: return false
            if (session.startFailure != null) return true
            if (!session.stopRequested) {
                session.stopRequested = true
                session.snapshot = session.snapshot.copy(
                    revision = ++revision,
                    phase = ForegroundWorkspacePhase.STOPPING,
                )
            }
            session
        }
        publish(session.snapshot)
        session.ready.cancel()
        synchronized(monitor) { session.job?.cancel() }
        return true
    }

    private suspend fun execute(session: Session) {
        var result: ForegroundWorkspaceExecutionResult? = null
        var failure: Throwable? = null
        try {
            try {
                withTimeout(startTimeoutMillis) { session.ready.await() }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                markStartTimedOut(session)
                throw session.startFailure ?: ForegroundWorkspaceTimedOut(ForegroundWorkspaceTimeoutStage.START)
            }
            currentCoroutineContext().ensureActive()
            try {
                result = withTimeout(session.handle.request.policy.maxRunMillis.toLong()) {
                    execution.execute(session.handle.request, requireNotNull(session.observer))
                }
                currentCoroutineContext().ensureActive()
            } catch (_: TimeoutCancellationException) {
                // A stop/parent cancellation wins over the policy deadline; only the runner's own
                // timeout reaches this branch with an active parent context.
                currentCoroutineContext().ensureActive()
                throw ForegroundWorkspaceTimedOut(ForegroundWorkspaceTimeoutStage.EXECUTION)
            }
        } catch (error: Throwable) {
            failure = error
            if (error !is Exception) throw error
        } finally {
            finish(session, result, failure)
        }
    }

    private fun markStartTimedOut(session: Session) {
        val snapshot = synchronized(monitor) {
            if (session.finished || session.startFailure != null) return
            session.startTimedOut = true
            session.startFailure = ForegroundWorkspaceTimedOut(ForegroundWorkspaceTimeoutStage.START)
            session.snapshot = session.snapshot.copy(
                revision = ++revision,
                phase = ForegroundWorkspacePhase.STOPPING,
                failure = ForegroundWorkspaceFailureReason.START_TIMEOUT,
            )
            session.snapshot
        }
        publish(snapshot)
    }

    private fun finish(
        session: Session,
        result: ForegroundWorkspaceExecutionResult?,
        failure: Throwable?,
    ) {
        val snapshot = synchronized(monitor) {
            if (session.finished) return
            session.finished = true
            val timedOut = failure is ForegroundWorkspaceTimedOut &&
                failure.stage == ForegroundWorkspaceTimeoutStage.EXECUTION
            val phase = when {
                session.startFailure != null -> ForegroundWorkspacePhase.FAILED
                session.stopRequested -> ForegroundWorkspacePhase.CANCELLED
                timedOut -> ForegroundWorkspacePhase.FAILED
                failure == null -> ForegroundWorkspacePhase.COMPLETED
                failure is CancellationException -> ForegroundWorkspacePhase.INTERRUPTED
                else -> ForegroundWorkspacePhase.FAILED
            }
            val reason = when {
                session.startTimedOut -> ForegroundWorkspaceFailureReason.START_TIMEOUT
                session.startFailure != null -> ForegroundWorkspaceFailureReason.START_FAILED
                phase == ForegroundWorkspacePhase.CANCELLED -> null
                timedOut -> ForegroundWorkspaceFailureReason.EXECUTION_TIMEOUT
                phase == ForegroundWorkspacePhase.INTERRUPTED -> ForegroundWorkspaceFailureReason.INTERRUPTED
                phase == ForegroundWorkspacePhase.FAILED -> ForegroundWorkspaceFailureReason.EXECUTION_FAILED
                else -> null
            }
            result?.artifacts?.forEach { mergeArtifact(session, it) }
            session.stage = ForegroundWorkspaceStage.FINISHING
            val terminalResult = if (phase == ForegroundWorkspacePhase.COMPLETED) result else null
            session.snapshot = session.snapshot.copy(
                revision = ++revision,
                phase = phase,
                observation = session.observation(),
                result = terminalResult,
                failure = reason,
            )
            session.snapshot
        }
        publish(snapshot)
        val terminalFailure: Throwable? = when (snapshot.phase) {
            ForegroundWorkspacePhase.COMPLETED -> null
            ForegroundWorkspacePhase.CANCELLED ->
                CancellationException("Foreground skill execution cancelled")
            ForegroundWorkspacePhase.INTERRUPTED -> ForegroundWorkspaceInterrupted()
            ForegroundWorkspacePhase.FAILED ->
                session.startFailure ?: failure ?: ForegroundWorkspaceStartFailed()
            ForegroundWorkspacePhase.PREPARING,
            ForegroundWorkspacePhase.RUNNING,
            ForegroundWorkspacePhase.STOPPING -> error("Execution did not reach a terminal phase")
        }
        if (terminalFailure == null) session.completion.complete(requireNotNull(result))
        else session.completion.completeExceptionally(terminalFailure)
    }

    private fun mergeArtifact(session: Session, artifact: ForegroundWorkspaceArtifact) {
        val key = artifact.path + '\u0000' + (artifact.sha256 ?: "")
        if (session.artifacts.containsKey(key)) return
        if (session.artifacts.size >= ForegroundWorkspaceLimits.MAX_ARTIFACTS) {
            val oldest = session.artifacts.keys.firstOrNull() ?: return
            session.artifacts.remove(oldest)
            session.artifactsTruncated = true
        }
        session.artifacts[key] = artifact
    }

    private inner class Observer(private val session: Session) : ForegroundWorkspaceObserver {
        override fun stage(stage: ForegroundWorkspaceStage) {
            val snapshot = synchronized(monitor) {
                if (current !== session || session.finished) return
                session.stage = stage
                session.snapshot = session.snapshot.copy(
                    revision = ++revision,
                    observation = session.observation(),
                )
                session.snapshot
            }
            publish(snapshot)
        }

        override fun log(line: String) {
            val snapshot = synchronized(monitor) {
                if (current !== session || session.finished) return
                if (line.length > ForegroundWorkspaceLimits.MAX_LOG_CHARS) session.logsTruncated = true
                session.logs.addLast(boundedLog(line))
                while (session.logs.size > ForegroundWorkspaceLimits.MAX_LOG_LINES) {
                    session.logs.removeFirst()
                    session.logsTruncated = true
                }
                session.snapshot = session.snapshot.copy(
                    revision = ++revision,
                    observation = session.observation(),
                )
                session.snapshot
            }
            publish(snapshot)
        }

        override fun artifact(artifact: ForegroundWorkspaceArtifact) {
            val snapshot = synchronized(monitor) {
                if (current !== session || session.finished) return
                mergeArtifact(session, artifact)
                session.snapshot = session.snapshot.copy(
                    revision = ++revision,
                    observation = session.observation(),
                )
                session.snapshot
            }
            publish(snapshot)
        }
    }

    private fun publish(value: ForegroundWorkspaceSnapshot) {
        mutable.update { previous ->
            if (previous == null || value.revision > previous.revision) value else previous
        }
    }
}

private fun saturatingAdd(value: Long, delta: Long): Long =
    if (value > Long.MAX_VALUE - delta) Long.MAX_VALUE else value + delta

private fun requireSha256Hex(value: String) {
    require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Invalid SHA-256"
    }
}

private fun requireSafeSegment(value: String, maxChars: Int, label: String) {
    require(value.isNotBlank() && value.length <= maxChars) { "Invalid $label" }
    require(value.none { it.isISOControl() || it.isWhitespace() || it == '/' || it == '\\' }) {
        "Invalid $label"
    }
}

private fun requireSafeRelativePath(value: String, maxChars: Int, label: String) {
    require(value.isNotBlank() && value.length <= maxChars) { "Invalid $label" }
    require(value.none(Char::isISOControl) && '\\' !in value && !value.startsWith('/')) {
        "Invalid $label"
    }
    require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "Invalid $label"
    }
}

private val CONTROL_CHARS: Pattern = Pattern.compile("[\\u0000-\\u001F\\u007F]")
private val BEARER_TOKEN: Pattern = Pattern.compile(
    "\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}",
    Pattern.CASE_INSENSITIVE,
)
private val SK_TOKEN: Pattern = Pattern.compile(
    "\\bsk-[A-Za-z0-9_-]{8,}",
    Pattern.CASE_INSENSITIVE,
)
private val KEYED_SECRET: Pattern = Pattern.compile(
    "\\b(api[_-]?key|access[_-]?token|auth[_-]?token|token|authorization|password|secret)\\b" +
        "\\s*[:=]\\s*[^\\s,;]+",
    Pattern.CASE_INSENSITIVE,
)

/** Scrubs common credential shapes, control characters and any body beyond the fixed tail. */
private fun boundedLog(line: String): String {
    var value = CONTROL_CHARS.matcher(line).replaceAll(" ")
    value = BEARER_TOKEN.matcher(value).replaceAll("Bearer <redacted>")
    value = SK_TOKEN.matcher(value).replaceAll("<redacted>")
    value = KEYED_SECRET.matcher(value).replaceAll("\$1=<redacted>")
    val max = ForegroundWorkspaceLimits.MAX_LOG_CHARS
    if (value.length <= max) return value
    var end = max - 3
    if (end > 0 && value[end - 1].isHighSurrogate()) end -= 1
    return value.substring(0, end) + "..."
}
