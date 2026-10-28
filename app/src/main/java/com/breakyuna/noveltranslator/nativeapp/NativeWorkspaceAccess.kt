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
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFileStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceHardLimits
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialKind
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialManifest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialProjectPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialSource
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceMaterialSummary
import com.breakyuna.noveltranslator.nativecore.workspace.PathPolicy
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

/** Upload leaf limits leave room for a UUID prefix inside the 255-byte segment bound. */
private const val MAX_UPLOAD_LEAF_BYTES = 200
private const val DEFAULT_UPLOAD_NAME = "upload.bin"

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

    /** Read-only view of the private workspace root for file proposal application. */
    internal fun workspaceRoot(ref: LocalProjectRef): File = rootFor(ref)

    /**
     * Read-only inventory of files a workspace proposal may update/delete. It exposes only paths,
     * fingerprints and sizes; bodies remain in the private root and are never copied to UI state.
     */
    suspend fun inventory(ref: LocalProjectRef): List<WorkspaceMaterialSummary> = withContext(Dispatchers.IO) {
        WorkspaceFileStore(rootFor(ref)).list()
            .filter { it.kind in setOf(WorkspaceMaterialKind.WORK, WorkspaceMaterialKind.UPLOAD,
                WorkspaceMaterialKind.REFERENCE) }
    }

    /**
     * Copies one user-selected file into `uploads/` with a bounded name and no overwrite. The
     * returned material is immediately visible to workspace scripts and Agent read tools.
     */
    suspend fun upload(ref: LocalProjectRef, displayName: String, bytes: ByteArray): WorkspaceMaterialSummary =
        withContext(Dispatchers.IO) {
            require(bytes.size.toLong() in 0..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Upload too large" }
            val leaf = uploadLeaf(displayName)
            val preferred = PathPolicy.DEFAULT.parse("uploads/$leaf")
            val path = if (File(rootFor(ref), preferred.value).exists()) {
                PathPolicy.DEFAULT.parse("uploads/" + java.util.UUID.randomUUID() + "-" + leaf)
            } else preferred
            WorkspaceFileStore(rootFor(ref)).saveUpload(path, bytes)
        }

    /**
     * One safe `uploads/` leaf name. Control characters and separators become `_`; the UTF-8 byte
     * bound keeps room for the duplicate-name UUID inside the 255-byte segment limit, and names the
     * path policy still rejects (trailing dots, Windows devices) are repaired or prefixed instead
     * of failing the whole upload.
     */
    private fun uploadLeaf(displayName: String): String {
        val cleaned = displayName.substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isISOControl() || it in "/\\") '_' else it }
            .joinToString("")
            .trim()
            .trimEnd('.', ' ')
            .ifBlank { DEFAULT_UPLOAD_NAME }
        val bounded = truncateUtf8(cleaned, MAX_UPLOAD_LEAF_BYTES)
            .trimEnd('.', ' ')
            .ifBlank { DEFAULT_UPLOAD_NAME }
        return if (isReservedUploadName(bounded)) "_$bounded" else bounded
    }

    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val builder = StringBuilder(value.length)
        var index = 0
        var used = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            builder.appendCodePoint(codePoint)
            used += size
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    private fun isReservedUploadName(leaf: String): Boolean {
        val base = leaf.substringBefore('.').trimEnd(' ', '.').uppercase(Locale.ROOT)
        if (base in setOf("CON", "PRN", "AUX", "NUL")) return true
        return base.length == 4 && (base.startsWith("COM") || base.startsWith("LPT")) &&
            base.last() in '1'..'9'
    }

    /** Agent imports accept only user-uploaded material IDs and re-verify their content digest. */
    internal suspend fun readVerifiedUpload(ref: LocalProjectRef, id: WorkspaceMaterialId,
        expectedSha256: String): ByteArray = withContext(Dispatchers.IO) {
        val store = WorkspaceFileStore(rootFor(ref))
        val summary = store.list(WorkspaceMaterialKind.UPLOAD).single { it.id == id }
        require(summary.sha256 == expectedSha256)
        val content = store.read(id, WorkspaceHardLimits.MAX_SINGLE_BYTES)
        require(content.sha256 == expectedSha256)
        content.bytes
    }

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
        withContext(Dispatchers.IO) { materialSource(ref, revision).list(null) }

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
            val summary = source.list(null).firstOrNull { it.id == id }
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
