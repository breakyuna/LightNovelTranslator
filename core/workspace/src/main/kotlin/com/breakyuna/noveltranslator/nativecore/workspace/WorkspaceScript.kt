package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cooperative cancellation for one workspace run. The core cannot forcibly stop an engine or
 * host call; it checks this token at every stage boundary and both the engine and network
 * ports must poll it. A production engine adapter is responsible for interrupting its VM.
 */
class WorkspaceCancellation {
    private val cancelled = AtomicBoolean(false)

    val isCancelled: Boolean get() = cancelled.get()

    /** Returns false when the run was already cancelled. */
    fun cancel(): Boolean = cancelled.compareAndSet(false, true)

    fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("Workspace run cancelled")
    }
}

/**
 * Capabilities a script may use. The host grants a subset per run; a request for anything
 * outside the grant is refused before execution and again when checking host requests.
 */
enum class WorkspaceCapability {
    READ_MATERIALS,
    PROPOSE_CHANGES,
    EMIT_ARTIFACTS,
    SEARCH,
    FETCH,
    PRINT,
}

class WorkspaceCapabilityDenied(val capability: WorkspaceCapability) :
    SecurityException("Workspace capability denied: $capability") {
    override fun toString(): String = "WorkspaceCapabilityDenied($capability)"
}

/**
 * Host requests a script may issue. Each variant declares exactly one required capability;
 * there is no generic "read any file" or "run process" request.
 */
sealed interface WorkspaceHostRequest {
    val requiredCapability: WorkspaceCapability

    class Search(val query: String, val maxResults: Int) : WorkspaceHostRequest {
        init {
            require(query.isNotBlank() && query.length <= 512) { "Invalid search query" }
            require(query.none { it.isISOControl() }) { "Control character in search query" }
            require(maxResults in 1..20) { "Invalid search result count" }
        }

        override val requiredCapability: WorkspaceCapability get() = WorkspaceCapability.SEARCH
    }

    class Fetch(val url: String, val maxBytes: Long) : WorkspaceHostRequest {
        init {
            require(url.length in 1..4_096) { "Invalid fetch URL" }
            require(maxBytes in 1..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Invalid fetch limit" }
        }

        override val requiredCapability: WorkspaceCapability get() = WorkspaceCapability.FETCH
    }

    class Print(val documentPath: WorkspacePath) : WorkspaceHostRequest {
        override val requiredCapability: WorkspaceCapability get() = WorkspaceCapability.PRINT
    }

    /** Reads one material by identity; the descriptor grant still limits which ids are legal. */
    class ReadMaterial(val materialId: WorkspaceMaterialId) : WorkspaceHostRequest {
        override val requiredCapability: WorkspaceCapability get() = WorkspaceCapability.READ_MATERIALS
    }

    /** Proposes one file mutation; the body is validated before the request is constructed. */
    class EmitChange(val path: WorkspacePath) : WorkspaceHostRequest {
        override val requiredCapability: WorkspaceCapability get() = WorkspaceCapability.PROPOSE_CHANGES
    }

    /** Publishes one new file; the sink still verifies the approved path and digest. */
    class EmitArtifact(val path: WorkspacePath) : WorkspaceHostRequest {
        override val requiredCapability: WorkspaceCapability get() = WorkspaceCapability.EMIT_ARTIFACTS
    }
}

object WorkspaceHostPolicy {
    /** Refuses a host request whose capability was not granted for this run. */
    fun authorize(granted: Set<WorkspaceCapability>, request: WorkspaceHostRequest) {
        if (request.requiredCapability !in granted) {
            throw WorkspaceCapabilityDenied(request.requiredCapability)
        }
    }
}

enum class WorkspaceLogStream { STDOUT, STDERR, HOST }

/** One bounded log line; text is display data, never an authorization. */
class WorkspaceLogLine(val stream: WorkspaceLogStream, val text: String) {
    init {
        require(text.length <= WorkspaceHardLimits.MAX_LOG_LINE_CHARS) { "Log line too long" }
        require('\u0000' !in text) { "NUL in log line" }
        requireWellFormedUnicode(text)
    }

    override fun toString(): String = "WorkspaceLogLine($stream, ${text.length} chars)"
}

/**
 * Exactly one script invocation. Materials are descriptors, not handles; the engine resolves
 * bytes through the host bridge and must stop at [deadlineAtMillis] or [cancellation].
 */
class WorkspaceScriptRequest(
    val commandId: CommandId,
    val projectId: ProjectId,
    val chatId: ChatId,
    val runId: RunId,
    val scriptId: String,
    val entryPoint: String,
    arguments: Map<String, String> = emptyMap(),
    materials: List<WorkspaceMaterialDescriptor>,
    grantedCapabilities: Set<WorkspaceCapability>,
    val limits: WorkspaceScriptLimits,
    val deadlineAtMillis: Long,
    val cancellation: WorkspaceCancellation,
) {
    val arguments: Map<String, String> = workspaceFrozenMap(arguments)
    val materials: List<WorkspaceMaterialDescriptor> = workspaceFrozenList(materials)
    val grantedCapabilities: Set<WorkspaceCapability> = workspaceFrozenSet(grantedCapabilities)

    init {
        require(scriptId.isNotBlank() && scriptId.length <= 256) { "Invalid script identity" }
        require(scriptId.none { it.isISOControl() }) { "Control character in script identity" }
        require(entryPoint.isNotBlank() && entryPoint.length <= 512) { "Invalid entry point" }
        require(entryPoint.none { it.isISOControl() }) { "Control character in entry point" }
        require(this.arguments.size <= 64) { "Too many script arguments" }
        this.arguments.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= 128) { "Invalid script argument" }
            require(key.none { it.isISOControl() }) { "Control character in script argument" }
            require(value.length <= 4_096 && '\u0000' !in value) { "Invalid script argument value" }
        }
        require(materials.size <= WorkspaceHardLimits.MAX_MATERIALS) { "Too many script materials" }
        require(materials.map { it.id }.toSet().size == materials.size) { "Duplicate script material" }
        require(materials.isEmpty() || WorkspaceCapability.READ_MATERIALS in grantedCapabilities) {
            "Materials require the read capability"
        }
        require(deadlineAtMillis > 0) { "Invalid script deadline" }
    }

    override fun toString(): String =
        "WorkspaceScriptRequest(script=$scriptId, materials=${materials.size}, capabilities=$grantedCapabilities)"
}

/**
 * Bounded script output. The engine must stop at [WorkspaceScriptLimits] and set
 * [outputTruncated] when it stopped early; the runtime discards truncated output instead of
 * approving a partial batch. A non-zero [exitCode] is a script failure, not a change request.
 */
class WorkspaceScriptResult(
    val exitCode: Int,
    changes: List<WorkspaceChange>,
    artifacts: List<WorkspaceArtifactDraft>,
    logs: List<WorkspaceLogLine> = emptyList(),
    val outputTruncated: Boolean = false,
) {
    val changes: List<WorkspaceChange> = workspaceFrozenList(changes)
    val artifacts: List<WorkspaceArtifactDraft> = workspaceFrozenList(artifacts)
    val logs: List<WorkspaceLogLine> = workspaceFrozenList(logs)

    init {
        require(exitCode >= 0) { "Invalid script exit code" }
        require(this.changes.size <= WorkspaceHardLimits.MAX_CHANGES) { "Too many script changes" }
        require(this.artifacts.size <= WorkspaceHardLimits.MAX_ARTIFACTS) { "Too many script artifacts" }
        require(this.logs.size <= WorkspaceHardLimits.MAX_LOG_LINES) { "Too many script log lines" }
    }

    override fun toString(): String =
        "WorkspaceScriptResult(exit=$exitCode, changes=${changes.size}, artifacts=${artifacts.size}, logs=${logs.size})"
}

/**
 * Isolated script execution port. A production adapter runs the embedded engine (R03 still
 * selects it) and bridges only declared capabilities. It must honor cancellation and the
 * deadline; this core cannot kill a non-cooperative engine and never pretends it did.
 */
interface WorkspaceScriptEngine {
    suspend fun execute(request: WorkspaceScriptRequest): WorkspaceScriptResult
}
