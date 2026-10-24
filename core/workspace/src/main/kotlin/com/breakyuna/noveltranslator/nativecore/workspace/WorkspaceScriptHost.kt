package com.breakyuna.noveltranslator.nativecore.workspace

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Synchronous material read port used by the embedded engine. QuickJS host callbacks run on the
 * interpreter thread and cannot suspend, so the bridge requires this narrower port. Production
 * adapters wrap a [WorkspaceMaterialReadPort] with [SynchronousWorkspaceMaterialReadPort]; a
 * port that actually suspends is reported as unavailable instead of being silently awaited on
 * the wrong thread.
 */
interface WorkspaceScriptMaterialReadPort {
    fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent
}

class SynchronousWorkspaceMaterialReadPort(
    private val port: WorkspaceMaterialReadPort,
) : WorkspaceScriptMaterialReadPort {
    override fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent =
        runWorkspacePort { port.read(id, limitBytes) }
}

/** Host-side print/PDF handoff; capabilities and paths are checked before this is called. */
interface WorkspacePrintPort {
    suspend fun print(path: WorkspacePath)
}

class WorkspaceScriptHostUnavailable(val reason: String) : IllegalStateException("Workspace host unavailable: $reason") {
    override fun toString(): String = "WorkspaceScriptHostUnavailable($reason)"
}

class WorkspaceMaterialNotAllowed(val materialId: WorkspaceMaterialId) :
    SecurityException("Workspace material not granted: ${materialId.value}") {
    override fun toString(): String = "WorkspaceMaterialNotAllowed(${materialId.value})"
}

class WorkspaceScriptHostLimitExceeded(val limit: Limit) : IllegalStateException("Workspace host limit exceeded: $limit") {
    enum class Limit { MATERIAL_BYTES, SEARCH_BYTES, FETCH_BYTES }

    override fun toString(): String = "WorkspaceScriptHostLimitExceeded($limit)"
}

class WorkspaceScriptOutputLimitExceeded(val limit: Limit) : IllegalStateException("Workspace output limit exceeded: $limit") {
    enum class Limit {
        CHANGES,
        CHANGE_BYTES,
        TOTAL_CHANGE_BYTES,
        ARTIFACTS,
        ARTIFACT_BYTES,
        TOTAL_ARTIFACT_BYTES,
        LOG_LINES,
    }

    override fun toString(): String = "WorkspaceScriptOutputLimitExceeded($limit)"
}

class WorkspaceScriptTimeoutException(val stage: WorkspaceRunStage = WorkspaceRunStage.SCRIPT) :
    IllegalStateException("Workspace script timed out at $stage") {
    override fun toString(): String = "WorkspaceScriptTimeoutException($stage)"
}

/** Startup cannot be reported as an empty successful run. */
class WorkspaceScriptEngineUnavailable(cause: Throwable? = null) :
    IllegalStateException("Workspace script engine unavailable", cause) {
    override fun toString(): String = "WorkspaceScriptEngineUnavailable"
}

/**
 * All state and authorization for one bridge session. It is confined to the interpreter thread;
 * the supervising thread only reads the abort flag and the final result after the worker joins.
 *
 * Every capability-gated host request goes through [WorkspaceHostPolicy]. Output is decoded by
 * [WorkspaceScriptResultCodec] and a malformed or over-limit emit is never turned into success:
 * the session's invalid-output flag forces a non-zero result even when JavaScript catches the
 * bridge exception.
 */
internal class WorkspaceScriptHostSession(
    private val request: WorkspaceScriptRequest,
    private val materials: WorkspaceScriptMaterialReadPort,
    private val searchPort: WorkspaceSearchPort? = null,
    private val fetchPort: WorkspaceFetchPort? = null,
    private val printPort: WorkspacePrintPort? = null,
    private val clock: WorkspaceClock = WorkspaceClock.SYSTEM,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    private val maxMaterialReadBytes: Long = DEFAULT_MAX_MATERIAL_READ_BYTES,
    private val maxTotalMaterialReadBytes: Long = DEFAULT_MAX_TOTAL_MATERIAL_READ_BYTES,
    private val maxTotalChangeBytes: Long = DEFAULT_MAX_TOTAL_CHANGE_BYTES,
    private val maxTotalArtifactBytes: Long = DEFAULT_MAX_TOTAL_ARTIFACT_BYTES,
) {
    enum class AbortReason { CANCELLED, TIMED_OUT }

    private val descriptors: Map<WorkspaceMaterialId, WorkspaceMaterialDescriptor> =
        request.materials.associateBy { it.id }
    private val logs = ArrayList<WorkspaceLogLine>()
    private val changes = ArrayList<WorkspaceChange>()
    private val artifacts = ArrayList<WorkspaceArtifactDraft>()
    private var outputBytes: Long = 0
    private var invalidOutput: Boolean = false
    private var truncated: Boolean = false
    private var materialBytes: Long = 0
    private var changeBytes: Long = 0
    private var artifactBytes: Long = 0

    @Volatile
    var abortReason: AbortReason? = null
        private set

    init {
        require(maxMaterialReadBytes in 1..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Invalid material read limit" }
        require(maxTotalMaterialReadBytes in maxMaterialReadBytes..WorkspaceHardLimits.MAX_TOTAL_BYTES) {
            "Invalid total material read limit"
        }
        require(maxTotalChangeBytes in 1..WorkspaceHardLimits.MAX_TOTAL_BYTES) { "Invalid total change limit" }
        require(maxTotalArtifactBytes in 1..WorkspaceHardLimits.MAX_TOTAL_BYTES) { "Invalid total artifact limit" }
    }

    val outputTruncated: Boolean get() = truncated

    fun checkActive() {
        when (abortReason) {
            AbortReason.CANCELLED -> throw CancellationException("Workspace run cancelled")
            AbortReason.TIMED_OUT -> throw WorkspaceScriptTimeoutException(WorkspaceRunStage.SCRIPT)
            null -> Unit
        }
        if (request.cancellation.isCancelled) {
            abortReason = AbortReason.CANCELLED
            throw CancellationException("Workspace run cancelled")
        }
        if (clock.nowMillis() > request.deadlineAtMillis) {
            abortReason = AbortReason.TIMED_OUT
            throw WorkspaceScriptTimeoutException(WorkspaceRunStage.SCRIPT)
        }
    }

    fun markAborted(reason: AbortReason) {
        abortReason = reason
    }

    fun readMaterial(rawId: String): String {
        checkActive()
        val id = try {
            WorkspaceMaterialId(rawId)
        } catch (failure: IllegalArgumentException) {
            throw WorkspaceMaterialNotAllowed(WorkspaceMaterialId("invalid"))
        }
        val descriptor = descriptors[id]
        if (descriptor == null) {
            invalidOutput = true
            throw WorkspaceMaterialNotAllowed(id)
        }
        authorize(WorkspaceHostRequest.ReadMaterial(id))
        val remaining = maxTotalMaterialReadBytes - materialBytes
        if (remaining <= 0) throw WorkspaceScriptHostLimitExceeded(WorkspaceScriptHostLimitExceeded.Limit.MATERIAL_BYTES)
        val limit = minOf(maxMaterialReadBytes, remaining)
        val content = try {
            materials.read(id, limit)
        } catch (failure: WorkspaceMaterialUnavailable) {
            throw failure
        } catch (failure: CancellationException) {
            abortReason = AbortReason.CANCELLED
            throw failure
        } catch (failure: Exception) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.READ_FAILED)
        }
        if (content.id != id ||
            content.sha256 != descriptor.sha256 ||
            content.sizeBytes != descriptor.sizeBytes ||
            content.sizeBytes > limit
        ) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH)
        }
        val text = try {
            decodeUtf8(content.bytes)
        } catch (failure: CharacterCodingException) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.READ_FAILED)
        }
        materialBytes += content.sizeBytes
        return text
    }

    fun log(text: String) {
        checkActive()
        // Logs are display-only and carry no capability; they are always bounded by lines and bytes.
        val bounded = sanitizeLog(text)
        if (logs.size + 1 > request.limits.maxLogLines) {
            truncated = true
            return
        }
        val line = WorkspaceLogLine(WorkspaceLogStream.HOST, bounded)
        val bytes = bounded.toByteArray(Charsets.UTF_8).size.toLong()
        if (bytes > request.limits.maxOutputBytes - outputBytes) {
            truncated = true
            return
        }
        logs += line
        outputBytes += bytes
    }

    fun emitChange(json: String) {
        checkActive()
        requireCapability(WorkspaceCapability.PROPOSE_CHANGES)
        if (changes.size + 1 > request.limits.maxChanges) {
            truncated = true
            throw WorkspaceScriptOutputLimitExceeded(WorkspaceScriptOutputLimitExceeded.Limit.CHANGES)
        }
        val change = try {
            WorkspaceScriptResultCodec.decodeChange(json, request, request.limits, policy)
        } catch (failure: WorkspaceScriptOutputException) {
            invalidOutput = true
            throw failure
        }
        authorize(WorkspaceHostRequest.EmitChange(change.path))
        if (change.sizeBytes > request.limits.maxChangeBytes) {
            truncated = true
            throw WorkspaceScriptOutputLimitExceeded(WorkspaceScriptOutputLimitExceeded.Limit.CHANGE_BYTES)
        }
        if (change.sizeBytes > maxTotalChangeBytes - changeBytes) {
            truncated = true
            throw WorkspaceScriptOutputLimitExceeded(WorkspaceScriptOutputLimitExceeded.Limit.TOTAL_CHANGE_BYTES)
        }
        changes += change
        changeBytes += change.sizeBytes
    }

    fun emitArtifact(json: String) {
        checkActive()
        requireCapability(WorkspaceCapability.EMIT_ARTIFACTS)
        if (artifacts.size + 1 > request.limits.maxArtifacts) {
            truncated = true
            throw WorkspaceScriptOutputLimitExceeded(WorkspaceScriptOutputLimitExceeded.Limit.ARTIFACTS)
        }
        val artifact = try {
            WorkspaceScriptResultCodec.decodeArtifact(json, request.limits, policy)
        } catch (failure: WorkspaceScriptOutputException) {
            invalidOutput = true
            throw failure
        }
        authorize(WorkspaceHostRequest.EmitArtifact(artifact.path))
        if (artifact.sizeBytes > request.limits.maxArtifactBytes) {
            truncated = true
            throw WorkspaceScriptOutputLimitExceeded(WorkspaceScriptOutputLimitExceeded.Limit.ARTIFACT_BYTES)
        }
        if (artifact.sizeBytes > maxTotalArtifactBytes - artifactBytes) {
            truncated = true
            throw WorkspaceScriptOutputLimitExceeded(WorkspaceScriptOutputLimitExceeded.Limit.TOTAL_ARTIFACT_BYTES)
        }
        artifacts += artifact
        artifactBytes += artifact.sizeBytes
    }

    fun search(query: String, maxResults: Int): String {
        checkActive()
        val hostRequest = WorkspaceHostRequest.Search(query, maxResults)
        authorize(hostRequest)
        val port = searchPort ?: throw WorkspaceScriptHostUnavailable("search port")
        val searchRequest = WorkspaceSearchRequest.create(
            query = query,
            deadlineAtMillis = request.deadlineAtMillis,
            cancellation = request.cancellation,
            maxResults = maxResults,
        )
        val outcome = runWorkspacePort { port.search(searchRequest) }
        return when (outcome) {
            is WorkspaceSearchOutcome.Found -> {
                val json = WorkspaceBridgeJson.search(outcome.hits, outcome.truncated)
                if (json.length.toLong() > request.limits.maxOutputBytes) {
                    throw WorkspaceScriptHostLimitExceeded(WorkspaceScriptHostLimitExceeded.Limit.SEARCH_BYTES)
                }
                json
            }
            is WorkspaceSearchOutcome.Failed ->
                throw WorkspaceScriptHostUnavailable("search " + outcome.error.category.name)
            WorkspaceSearchOutcome.Cancelled -> throw WorkspaceScriptHostUnavailable("search cancelled")
        }
    }

    fun fetch(url: String, maxBytes: Int): String {
        checkActive()
        if (maxBytes < 1) throw IllegalArgumentException("Invalid fetch limit")
        if (maxBytes.toLong() > request.limits.maxOutputBytes) {
            throw WorkspaceScriptHostLimitExceeded(WorkspaceScriptHostLimitExceeded.Limit.FETCH_BYTES)
        }
        val hostRequest = WorkspaceHostRequest.Fetch(url, maxBytes.toLong())
        authorize(hostRequest)
        val port = fetchPort ?: throw WorkspaceScriptHostUnavailable("fetch port")
        val fetchRequest = WorkspaceFetchRequest.create(
            url = url,
            deadlineAtMillis = request.deadlineAtMillis,
            cancellation = request.cancellation,
            maxBytes = hostRequest.maxBytes,
        )
        val outcome = runWorkspacePort { port.fetch(fetchRequest) }
        return when (outcome) {
            is WorkspaceFetchOutcome.Fetched -> {
                val json = WorkspaceBridgeJson.fetched(outcome.document)
                if (json.length.toLong() > request.limits.maxOutputBytes) {
                    throw WorkspaceScriptHostLimitExceeded(WorkspaceScriptHostLimitExceeded.Limit.FETCH_BYTES)
                }
                json
            }
            is WorkspaceFetchOutcome.Failed ->
                throw WorkspaceScriptHostUnavailable("fetch " + outcome.error.category.name)
            WorkspaceFetchOutcome.Cancelled -> throw WorkspaceScriptHostUnavailable("fetch cancelled")
        }
    }

    fun print(rawPath: String) {
        checkActive()
        val path = try {
            policy.parse(rawPath)
        } catch (failure: InvalidWorkspacePath) {
            throw IllegalArgumentException("Invalid print path")
        }
        authorize(WorkspaceHostRequest.Print(path))
        val port = printPort ?: throw WorkspaceScriptHostUnavailable("print port")
        runWorkspacePort { port.print(path) }
    }

    fun result(exitCode: Int): WorkspaceScriptResult {
        throwIfAborted()
        if (invalidOutput) {
            return WorkspaceScriptResult(1, emptyList(), emptyList(), logs, truncated)
        }
        if (exitCode != 0) {
            return WorkspaceScriptResult(exitCode, emptyList(), emptyList(), logs, truncated)
        }
        return WorkspaceScriptResult(exitCode, changes, artifacts, logs, truncated)
    }

    fun failureResult(): WorkspaceScriptResult {
        throwIfAborted()
        return WorkspaceScriptResult(1, emptyList(), emptyList(), logs, truncated)
    }

    private fun throwIfAborted() {
        when (abortReason) {
            AbortReason.CANCELLED -> throw CancellationException("Workspace run cancelled")
            AbortReason.TIMED_OUT -> throw WorkspaceScriptTimeoutException(WorkspaceRunStage.SCRIPT)
            null -> Unit
        }
    }

    private fun requireCapability(capability: WorkspaceCapability) {
        if (capability !in request.grantedCapabilities) {
            invalidOutput = true
            throw WorkspaceCapabilityDenied(capability)
        }
    }

    private fun authorize(hostRequest: WorkspaceHostRequest) {
        try {
            WorkspaceHostPolicy.authorize(request.grantedCapabilities, hostRequest)
        } catch (failure: WorkspaceCapabilityDenied) {
            invalidOutput = true
            throw failure
        }
    }

    private fun sanitizeLog(text: String): String {
        val limit = WorkspaceHardLimits.MAX_LOG_LINE_CHARS
        var end = text.length
        if (end > limit) {
            end = limit
            if (end > 0 && text[end - 1].isHighSurrogate()) end -= 1
            truncated = true
        }
        val builder = StringBuilder(end)
        var index = 0
        while (index < end) {
            val character = text[index]
            when {
                character == '\u0000' -> builder.append('\uFFFD')
                character.isHighSurrogate() -> {
                    if (index + 1 < end && text[index + 1].isLowSurrogate()) {
                        builder.append(character).append(text[index + 1])
                        index += 2
                        continue
                    }
                    builder.append('\uFFFD')
                }
                character.isLowSurrogate() -> builder.append('\uFFFD')
                else -> builder.append(character)
            }
            index += 1
        }
        return builder.toString()
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    companion object {
        const val DEFAULT_MAX_MATERIAL_READ_BYTES: Long = 8L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_MATERIAL_READ_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_CHANGE_BYTES: Long = 64L * 1024 * 1024
        const val DEFAULT_MAX_TOTAL_ARTIFACT_BYTES: Long = 64L * 1024 * 1024
    }
}

/**
 * Runs a suspend port only when it completes without actually suspending. The embedded engine
 * calls host code from a synchronous JS callback, so a real suspension cannot be awaited there.
 */
internal fun <T> runWorkspacePort(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                if (outcome == null) outcome = result
            }
        },
    )
    val completed = outcome ?: throw WorkspaceScriptHostUnavailable("host port suspended")
    return completed.getOrThrow()
}
