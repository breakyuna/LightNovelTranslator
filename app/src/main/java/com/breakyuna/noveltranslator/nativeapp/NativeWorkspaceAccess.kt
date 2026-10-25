package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetPort
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactKind
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactRecord
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceArtifactUnavailable
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceHardLimits
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialManifest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialProjectPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialSource
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialSummary
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialUnavailable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The text body cap for one model/UI preview; full artifacts are only written by SAF on demand. */
internal const val WORKSPACE_TEXT_PREVIEW_BYTES: Int = 64 * 1024

/**
 * App-owned private workspace composition. One root per Project instance/generation holds the
 * regenerated material areas and the persistent artifact store; artifact bodies are already
 * partitioned per run by [WorkspaceArtifactStore]. No project database is opened here and no
 * material is ever written back to the project.
 */
internal class NativeWorkspaceAccess(
    context: Context,
    private val projects: TextProjectPort,
    private val assets: ProjectAssetPort,
    root: File = File(context.applicationContext.filesDir, "workspace"),
) {
    private val base = File(root, "projects")
    private val lock = Mutex()
    private val materialSources = HashMap<Pair<LocalProjectRef, Long>, WorkspaceMaterialSource>()
    private val materialLocks = HashMap<LocalProjectRef, Mutex>()
    private val artifactStores = HashMap<LocalProjectRef, WorkspaceArtifactStore>()

    private fun rootFor(ref: LocalProjectRef): File = File(base, "ws-" + identityHash(ref))

    suspend fun projectRevision(ref: LocalProjectRef): Long = withContext(Dispatchers.IO) {
        val summary = projects.open(ref)
        require(summary.ref == ref) { "Workspace project identity changed" }
        summary.revision
    }

    private suspend fun materialSource(ref: LocalProjectRef, revision: Long): WorkspaceMaterialSource =
        withContext(Dispatchers.IO) {
            require(revision >= 0) { "Invalid workspace revision" }
            lock.withLock {
                materialSources.getOrPut(ref to revision) {
                    WorkspaceMaterialSource(
                        rootFor(ref), ref, revision,
                        WorkspaceMaterialProjectPort.of(projects, assets),
                    )
                }
            }
        }

    suspend fun manifest(ref: LocalProjectRef, revision: Long): WorkspaceMaterialManifest =
        withContext(Dispatchers.IO) { withMaterialRoot(ref) { materialSource(ref, revision).snapshot() } }

    suspend fun listMaterials(ref: LocalProjectRef, revision: Long): List<WorkspaceMaterialSummary> =
        manifest(ref, revision).materials

    /**
     * Reads one manifest-approved material. The opaque id is never interpreted as a path; the
     * manifest supplies the path and digest, and the source re-verifies both before returning.
     */
    suspend fun readMaterialText(
        ref: LocalProjectRef,
        revision: Long,
        id: WorkspaceMaterialId,
        limitBytes: Int = WORKSPACE_TEXT_PREVIEW_BYTES,
    ): WorkspaceTextValue = withContext(Dispatchers.IO) {
        require(limitBytes in 1..WORKSPACE_TEXT_PREVIEW_BYTES) { "Invalid workspace material read limit" }
        withMaterialRoot(ref) {
            val source = materialSource(ref, revision)
            val summary = source.snapshot().materials.firstOrNull { it.id == id }
                ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
            if (summary.sizeBytes > limitBytes.toLong()) {
                throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.TOO_LARGE)
            }
            val content = source.read(id, limitBytes.toLong())
            val text = decodeText(content.bytes)
                ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.READ_FAILED)
            WorkspaceTextValue(
                path = summary.path.value,
                sha256 = summary.sha256,
                sizeBytes = summary.sizeBytes,
                mediaType = summary.mediaType,
                kind = summary.kind.name,
                text = text,
            )
        }
    }

    private suspend fun artifactStore(ref: LocalProjectRef): WorkspaceArtifactStore = withContext(Dispatchers.IO) {
        lock.withLock {
            artifactStores.getOrPut(ref) { WorkspaceArtifactStore(rootFor(ref), ref) }
        }
    }

    suspend fun artifactInventory(ref: LocalProjectRef, runId: RunId) = withContext(Dispatchers.IO) {
        artifactStore(ref).inventory(runId)
    }

    suspend fun listArtifacts(ref: LocalProjectRef, runId: RunId? = null): List<WorkspaceArtifactRecord> =
        withContext(Dispatchers.IO) { artifactStore(ref).list(runId) }

    suspend fun readArtifactText(
        ref: LocalProjectRef,
        runId: RunId,
        id: WorkspaceArtifactId,
        limitBytes: Int = WORKSPACE_TEXT_PREVIEW_BYTES,
    ): WorkspaceTextValue = withContext(Dispatchers.IO) {
        require(limitBytes in 1..WORKSPACE_TEXT_PREVIEW_BYTES) { "Invalid workspace artifact read limit" }
        val store = artifactStore(ref)
        val record = store.recordForRun(runId, id)
            ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
        if (record.runId != runId) {
            throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
        }
        if (!isTextArtifact(record)) {
            throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.READ_FAILED)
        }
        if (record.sizeBytes > limitBytes.toLong()) {
            throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.TOO_LARGE)
        }
        val content = store.readForRun(runId, id, limitBytes.toLong())
        val text = decodeText(content.bytes)
            ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.READ_FAILED)
        WorkspaceTextValue(
            path = record.path.value,
            sha256 = record.sha256,
            sizeBytes = record.sizeBytes,
            mediaType = record.mediaType,
            kind = record.kind.name,
            text = text,
        )
    }

    /** Full artifact bytes are only used by an explicit SAF save, never by a preview or log. */
    suspend fun readArtifactBytes(ref: LocalProjectRef, runId: RunId, id: WorkspaceArtifactId): ByteArray =
        withContext(Dispatchers.IO) {
            val store = artifactStore(ref)
            val record = store.recordForRun(runId, id)
                ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
            if (record.runId != runId) {
                throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
            }
            store.readForRun(runId, id, WorkspaceHardLimits.MAX_ARTIFACT_BYTES).bytes
        }

    /** Metadata lookup restricted to the bound run; the opaque id is never a path. */
    suspend fun artifact(ref: LocalProjectRef, runId: RunId, id: WorkspaceArtifactId): WorkspaceArtifactRecord =
        withContext(Dispatchers.IO) {
            val record = artifactStore(ref).recordForRun(runId, id)
                ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
            if (record.runId != runId) {
                throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
            }
            record
        }

    /**
     * Reads one artifact body only when the caller's frozen SHA-256 and size still match both
     * the persisted record and the verified bytes. A changed artifact is never imported under an
     * older approval.
     */
    suspend fun readVerifiedArtifactBytes(ref: LocalProjectRef, runId: RunId, id: WorkspaceArtifactId,
                                          expectedSha256: String, expectedSize: Long): ByteArray =
        withContext(Dispatchers.IO) {
            require(SHA256_HEX.matches(expectedSha256)) { "Invalid expected artifact SHA-256" }
            require(expectedSize in 0..WorkspaceHardLimits.MAX_ARTIFACT_BYTES) { "Invalid expected artifact size" }
            val store = artifactStore(ref)
            val record = store.recordForRun(runId, id)
                ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
            if (record.runId != runId || record.sha256 != expectedSha256 || record.sizeBytes != expectedSize) {
                throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.FINGERPRINT_MISMATCH)
            }
            val content = store.readForRun(runId, id, WorkspaceHardLimits.MAX_ARTIFACT_BYTES)
            if (content.sha256 != expectedSha256 || content.sizeBytes != expectedSize) {
                throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.FINGERPRINT_MISMATCH)
            }
            content.bytes
        }

    /**
     * Publishes script output through the project-bound store, then applies inactive-run
     * retention while keeping the current run. Artifact publication never touches project data.
     */
    suspend fun publishArtifacts(
        ref: LocalProjectRef,
        runId: RunId,
        drafts: List<WorkspaceArtifactDraft>,
        keepInactiveRuns: Int = 8,
    ): List<WorkspaceArtifactRecord> = withContext(Dispatchers.IO) {
        require(keepInactiveRuns in 0..64) { "Invalid artifact run retention" }
        val store = artifactStore(ref)
        val saved = store.save(runId, drafts)
        val records = store.list(runId).associateBy { it.id }
        store.prune(setOf(runId), keepInactiveRuns)
        saved.mapNotNull { records[it.id] }
    }

    private fun isTextArtifact(record: WorkspaceArtifactRecord): Boolean =
        record.kind in setOf(
            WorkspaceArtifactKind.TEXT,
            WorkspaceArtifactKind.MARKDOWN,
            WorkspaceArtifactKind.JSON,
            WorkspaceArtifactKind.CSV,
        ) || run {
            val type = record.mediaType.substringBefore(';').trim().lowercase(Locale.ROOT)
            type.startsWith("text/") || type in TEXT_APPLICATION_TYPES
        }

    /**
     * Different revisions share one material root. Serialize snapshot/read per project so two
     * source instances cannot rewrite the generated manifest and source files at the same time.
     */
    private suspend fun <T> withMaterialRoot(ref: LocalProjectRef, block: suspend () -> T): T {
        val mutex = lock.withLock { materialLocks.getOrPut(ref) { Mutex() } }
        return mutex.withLock { block() }
    }

    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val TEXT_APPLICATION_TYPES = setOf(
            "application/json", "application/x-ndjson", "application/jsonl",
            "application/xml", "application/xhtml+xml", "application/javascript",
            "application/x-javascript", "application/yaml", "application/x-yaml",
            "application/toml", "application/sql", "application/x-subrip",
        )

        private fun identityHash(ref: LocalProjectRef): String =
            MessageDigest.getInstance("SHA-256").digest(
                (ref.projectId.value + "\u0000" + ref.instanceId.value + "\u0000" +
                    ref.generation.value).toByteArray(Charsets.UTF_8),
            ).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

        private fun decodeText(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}

/** Bounded text plus verified metadata; the body is deliberately absent from toString(). */
internal class WorkspaceTextValue(
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val mediaType: String,
    val kind: String,
    val text: String,
) {
    override fun toString(): String =
        "WorkspaceTextValue(path=$path, size=$sizeBytes, kind=$kind)"
}

/**
 * One fixed-revision view for a single bound Agent run. The first tool call resolves the
 * revision once; every later material read reuses it, so a project revision change fails the
 * run instead of silently mixing snapshots.
 */
internal class NativeWorkspaceRun(
    val ref: LocalProjectRef,
    val lease: RunLease,
    private val access: NativeWorkspaceAccess,
) {
    private val revisionLock = Mutex()
    private var fixedRevision: Long? = null

    val runId: RunId get() = lease.identity.runId

    suspend fun revision(): Long = revisionLock.withLock {
        fixedRevision ?: access.projectRevision(ref).also { fixedRevision = it }
    }

    suspend fun materials(): List<WorkspaceMaterialSummary> = access.listMaterials(ref, revision())

    suspend fun readMaterialText(
        id: WorkspaceMaterialId,
        limitBytes: Int = WORKSPACE_TEXT_PREVIEW_BYTES,
    ): WorkspaceTextValue = access.readMaterialText(ref, revision(), id, limitBytes)

    suspend fun inventory() = access.artifactInventory(ref, runId)

    suspend fun artifacts(): List<WorkspaceArtifactRecord> = access.listArtifacts(ref, runId)

    suspend fun artifact(id: WorkspaceArtifactId): WorkspaceArtifactRecord = access.artifact(ref, runId, id)

    suspend fun readArtifactText(
        id: WorkspaceArtifactId,
        limitBytes: Int = WORKSPACE_TEXT_PREVIEW_BYTES,
    ): WorkspaceTextValue = access.readArtifactText(ref, runId, id, limitBytes)

    suspend fun readArtifactBytes(
        id: WorkspaceArtifactId,
        expectedSha256: String,
        expectedSize: Long,
    ): ByteArray = access.readVerifiedArtifactBytes(ref, runId, id, expectedSha256, expectedSize)
}
