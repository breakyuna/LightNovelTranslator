package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.RunId

/**
 * Stable identity of one workspace material. It is opaque to scripts; the descriptor carries
 * only metadata and the host resolves bytes through [WorkspaceMaterialReadPort].
 */
@JvmInline
value class WorkspaceMaterialId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128) { "Invalid material identity" }
        require(value.none { it.isISOControl() || it == '/' || it == '\\' }) { "Invalid material identity" }
    }
}

/** LG workspace areas whose lifetime differs (ARCHITECTURE §7.3). */
enum class WorkspaceMaterialKind {
    /** Regenerated from fixed project assets; never writable by scripts. */
    SOURCE,
    /** Derived tables/analysis refreshed with the project snapshot. */
    DATASET,
    /** Versioned reference documents shipped with the workspace. */
    REFERENCE,
    /** User-provided material retained across turns. */
    UPLOAD,
    /** Intermediate script output retained across turns. */
    WORK,
    /** Saved script result addressed by identity. */
    ARTIFACT,
}

enum class WorkspaceMaterialOrigin {
    PROJECT_ASSET,
    USER_IMPORT,
    SCRIPT_OUTPUT,
    HOST_REQUEST,
}

/**
 * Lightweight material metadata. It intentionally has no body field: UI state, logs and
 * approval descriptions use summaries and load bytes only by [WorkspaceMaterialId].
 */
class WorkspaceMaterialSummary(
    val id: WorkspaceMaterialId,
    val kind: WorkspaceMaterialKind,
    val path: WorkspacePath,
    val sha256: String,
    val sizeBytes: Long,
    val mediaType: String,
    val ordinal: Int = 0,
    val origin: WorkspaceMaterialOrigin = WorkspaceMaterialOrigin.PROJECT_ASSET,
    val sourceRef: String? = null,
) {
    init {
        requireSha256Hex(sha256)
        require(sizeBytes in 0..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Invalid material size" }
        require(mediaType.isNotBlank() && mediaType.length <= 128) { "Invalid media type" }
        require(mediaType.none { it.isISOControl() }) { "Control character in media type" }
        require(ordinal >= 0) { "Invalid material order" }
        if (sourceRef != null) {
            require(sourceRef.isNotBlank() && sourceRef.length <= 256) { "Invalid material source" }
            require(sourceRef.none { it.isISOControl() }) { "Control character in material source" }
        }
    }

    fun descriptor(): WorkspaceMaterialDescriptor = WorkspaceMaterialDescriptor(
        id = id,
        path = path,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        mediaType = mediaType,
        ordinal = ordinal,
    )

    /** Bounded, body-free description safe for logs and model context. */
    override fun toString(): String =
        "WorkspaceMaterialSummary(id=${id.value}, kind=$kind, path=${path.value}, size=$sizeBytes)"
}

/** Exactly what a script may know about one material: read-only metadata, no handle. */
data class WorkspaceMaterialDescriptor(
    val id: WorkspaceMaterialId,
    val path: WorkspacePath,
    val sha256: String,
    val sizeBytes: Long,
    val mediaType: String,
    val ordinal: Int,
) {
    init {
        requireSha256Hex(sha256)
        require(sizeBytes in 0..WorkspaceHardLimits.MAX_SINGLE_BYTES)
        require(mediaType.isNotBlank() && mediaType.length <= 128)
        require(ordinal >= 0)
    }

    val readOnly: Boolean get() = true
}

/** Material body plus its verified fingerprint; never part of a summary or run outcome. */
class WorkspaceMaterialContent(
    val id: WorkspaceMaterialId,
    val sha256: String,
    bytes: ByteArray,
) {
    private val storage: ByteArray = bytes.copyOf()

    init {
        requireSha256Hex(sha256)
        require(storage.size <= WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Material body too large" }
        require(sha256Hex(storage) == sha256) { "Material fingerprint mismatch" }
    }

    /** Defensive copy: material bodies are never shared mutable state. */
    val bytes: ByteArray get() = storage.copyOf()

    val sizeBytes: Long get() = storage.size.toLong()

    override fun toString(): String = "WorkspaceMaterialContent(id=${id.value}, size=$sizeBytes)"
}

class WorkspaceMaterialUnavailable(val reason: Reason) : IllegalStateException("Material unavailable: $reason") {
    enum class Reason { NOT_FOUND, TOO_LARGE, FINGERPRINT_MISMATCH, READ_FAILED }
}

/**
 * Host-side material boundary. [list] and [summary] must never load bodies; [read] is the
 * only body path and must enforce [limitBytes] before allocating. Implementations belong to
 * the storage/workspace adapter, not to this pure core.
 */
interface WorkspaceMaterialReadPort {
    suspend fun list(kind: WorkspaceMaterialKind? = null): List<WorkspaceMaterialSummary>
    suspend fun summary(id: WorkspaceMaterialId): WorkspaceMaterialSummary?

    /** Reads one body on demand; callers must not keep it in UI state. */
    suspend fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent
}

@JvmInline
value class WorkspaceArtifactId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128) { "Invalid artifact identity" }
        require(value.none { it.isISOControl() }) { "Invalid artifact identity" }
    }
}

/** Bounded new file produced by a script. The sink owns atomic publication. */
class WorkspaceArtifactDraft(
    val path: WorkspacePath,
    val mediaType: String,
    bytes: ByteArray,
) {
    private val storage: ByteArray = bytes.copyOf()
    val sha256: String

    init {
        require(mediaType.isNotBlank() && mediaType.length <= 128) { "Invalid media type" }
        require(mediaType.none { it.isISOControl() }) { "Control character in media type" }
        require(storage.size <= WorkspaceHardLimits.MAX_ARTIFACT_BYTES) { "Artifact too large" }
        sha256 = sha256Hex(storage)
    }

    /** Defensive copy for the publication adapter. */
    val bytes: ByteArray get() = storage.copyOf()

    val sizeBytes: Long get() = storage.size.toLong()

    override fun toString(): String = "WorkspaceArtifactDraft(path=${path.value}, size=$sizeBytes)"
}

class WorkspaceArtifactSummary(
    val id: WorkspaceArtifactId,
    val path: WorkspacePath,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        requireSha256Hex(sha256)
        require(sizeBytes in 0..WorkspaceHardLimits.MAX_ARTIFACT_BYTES) { "Invalid artifact size" }
        require(mediaType.isNotBlank() && mediaType.length <= 128) { "Invalid media type" }
    }

    override fun toString(): String =
        "WorkspaceArtifactSummary(id=${id.value}, path=${path.value}, size=$sizeBytes)"
}

/**
 * Publication port for artifacts. Implementations must write under the workspace root,
 * reject overwriting project sources, and return exactly one summary per draft. A failure
 * must leave no partially written file visible.
 */
interface WorkspaceArtifactSink {
    suspend fun save(runId: RunId, drafts: List<WorkspaceArtifactDraft>): List<WorkspaceArtifactSummary>
}
