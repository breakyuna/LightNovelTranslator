package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Coarse persisted artifact kind, derived from the path when no explicit value is available. */
enum class WorkspaceArtifactKind {
    TEXT,
    MARKDOWN,
    JSON,
    CSV,
    IMAGE,
    PDF,
    ARCHIVE,
    BINARY,
}

class WorkspaceArtifactRecord(
    val id: WorkspaceArtifactId,
    val runId: RunId,
    val path: WorkspacePath,
    val mediaType: String,
    val kind: WorkspaceArtifactKind,
    val sizeBytes: Long,
    val sha256: String,
    val createdAtMillis: Long,
) {
    init {
        require(mediaType.isNotBlank() && mediaType.length <= 128) { "Invalid artifact media type" }
        require(mediaType.none(Char::isISOControl)) { "Control character in artifact media type" }
        requireSha256Hex(sha256)
        require(sizeBytes in 0..WorkspaceHardLimits.MAX_ARTIFACT_BYTES) { "Invalid artifact size" }
        require(createdAtMillis >= 0) { "Invalid artifact time" }
    }

    fun summary(): WorkspaceArtifactSummary =
        WorkspaceArtifactSummary(id, path, mediaType, sizeBytes, sha256)

    override fun toString(): String =
        "WorkspaceArtifactRecord(id=${id.value}, path=${path.value}, size=$sizeBytes)"
}

class WorkspaceArtifactContent(
    val id: WorkspaceArtifactId,
    val sha256: String,
    bytes: ByteArray,
) {
    private val storage: ByteArray = bytes.copyOf()

    init {
        requireSha256Hex(sha256)
        require(storage.size <= WorkspaceHardLimits.MAX_ARTIFACT_BYTES) { "Artifact body too large" }
        require(sha256Hex(storage) == sha256) { "Artifact fingerprint mismatch" }
    }

    val bytes: ByteArray get() = storage.copyOf()

    val sizeBytes: Long get() = storage.size.toLong()

    override fun toString(): String = "WorkspaceArtifactContent(id=${id.value}, size=$sizeBytes)"
}

class WorkspaceArtifactUnavailable(val reason: Reason) : IllegalStateException("Artifact unavailable: $reason") {
    enum class Reason { NOT_FOUND, TOO_LARGE, FINGERPRINT_MISMATCH, READ_FAILED }
}

class WorkspaceArtifactConflict(val reason: Reason, val path: WorkspacePath? = null) :
    IllegalArgumentException("Artifact conflict: $reason") {
    enum class Reason {
        DUPLICATE_PATH,
        DUPLICATE_ID,
        ALREADY_EXISTS,
        PATH_POLICY,
        PROTECTED_PATH,
        STORE_IDENTITY,
        CORRUPT_STORE,
    }

    override fun toString(): String = "WorkspaceArtifactConflict($reason)"
}

class WorkspaceArtifactPruneResult(
    removedRuns: List<RunId>,
    val removedArtifacts: Int,
    val removedBytes: Long,
    val retainedActiveRuns: Int,
    val retainedInactiveRuns: Int,
) {
    val removedRuns: List<RunId> = workspaceFrozenList(removedRuns)

    init {
        require(removedArtifacts >= 0 && removedBytes >= 0) { "Invalid prune totals" }
        require(retainedActiveRuns >= 0 && retainedInactiveRuns >= 0) { "Invalid retained count" }
    }

    override fun toString(): String =
        "WorkspaceArtifactPruneResult(runs=${removedRuns.size}, artifacts=$removedArtifacts, bytes=$removedBytes)"
}

enum class WorkspaceArtifactAvailability { VERIFIED, MISSING, MISMATCH, UNREADABLE }
class WorkspaceArtifactInventory(val record: WorkspaceArtifactRecord, val availability: WorkspaceArtifactAvailability)

/**
 * Persistent artifact store for one project instance/generation workspace root.
 *
 * Layout:
 * - `artifacts/store.json` binds the root to Project/instance/generation.
 * - `artifacts/runs/<run-hash>/run.json` records the original run identity and media metadata.
 * - `artifacts/runs/<run-hash>/<relative path>` is the immutable artifact body.
 *
 * Every save is checked against the run budget and published with temp+fsync+rename. Existing
 * paths are never overwritten, paths that name project/source areas are rejected, and the
 * in-memory index is rebuilt by scanning the directory on startup (no Room and no app wiring).
 * Retention only deletes complete inactive runs; active runs are retained regardless of the
 * configured count.
 */
class WorkspaceArtifactStore(
    root: File,
    private val ref: LocalProjectRef,
    private val budget: WorkspaceBudget = WorkspaceBudget(),
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    private val clock: WorkspaceClock = WorkspaceClock.SYSTEM,
) : WorkspaceArtifactSink {
    private val base = WorkspaceStorageSupport.openRoot(root)
    private val artifactsRoot = WorkspaceStorageSupport.ensureDirectory(base, WorkspacePath("artifacts"))
    private val runsRoot = WorkspaceStorageSupport.ensureDirectory(
        base,
        WorkspacePath("artifacts/runs"),
    )
    private val lock = Mutex()
    private var index: Map<WorkspaceArtifactId, WorkspaceArtifactRecord>? = null
    private var scannedRuns: List<ScannedRun>? = null

    init {
        ensureStoreMetadata()
    }

    /**
     * Convenience for Android composition. [rootDirectoryName] is one private child of the app
     * files directory; it must already be unique for the fixed project instance/generation.
     */
    constructor(
        context: Context,
        rootDirectoryName: String,
        ref: LocalProjectRef,
        budget: WorkspaceBudget = WorkspaceBudget(),
        policy: PathPolicy = PathPolicy.DEFAULT,
        clock: WorkspaceClock = WorkspaceClock.SYSTEM,
    ) : this(
        File(
            context.applicationContext.filesDir,
            requirePrivateDirectoryName(rootDirectoryName),
        ),
        ref, budget, policy, clock,
    )

    /** Lists artifacts, optionally only those of one run. The result is ordered by run/path. */
    suspend fun list(runId: RunId? = null): List<WorkspaceArtifactRecord> = lock.withLock {
        val records = ensureIndex().values
            .filter { runId == null || it.runId == runId }
            .sortedWith(compareBy<WorkspaceArtifactRecord> { it.runId.value }.thenBy { it.path.value })
        workspaceFrozenList(records)
    }

    suspend fun find(id: WorkspaceArtifactId): WorkspaceArtifactRecord? = lock.withLock {
        ensureIndex()[id]
    }

    /** Rebuilds the in-memory metadata index by scanning the artifact directory. */
    suspend fun refresh(): Int = lock.withLock {
        index = scanIndex()
        index?.size ?: 0
    }

    override suspend fun save(
        runId: RunId,
        drafts: List<WorkspaceArtifactDraft>,
    ): List<WorkspaceArtifactSummary> = lock.withLock {
        if (drafts.isEmpty()) return@withLock emptyList()
        ensureStoreMetadata()
        val maxArtifacts = minOf(budget.maxArtifacts, WorkspaceHardLimits.MAX_ARTIFACTS)
        val maxArtifactBytes = minOf(budget.maxArtifactBytes, WorkspaceHardLimits.MAX_ARTIFACT_BYTES)
        val maxTotalArtifactBytes = minOf(budget.maxTotalArtifactBytes, WorkspaceHardLimits.MAX_TOTAL_BYTES)
        if (drafts.size > maxArtifacts) {
            throw WorkspaceBudgetExceeded(
                WorkspaceBudgetKind.ARTIFACTS, maxArtifacts.toLong(), drafts.size.toLong(),
            )
        }

        val prepared = drafts.map { draft ->
            val bytes = draft.bytes
            val path = validateArtifactPath(draft.path)
            if (bytes.size.toLong() > maxArtifactBytes) {
                throw WorkspaceBudgetExceeded(
                    WorkspaceBudgetKind.ARTIFACT_BYTES, maxArtifactBytes, bytes.size.toLong(),
                )
            }
            PreparedArtifact(path, draft.mediaType, bytes, draft.sha256)
        }
        val identities = HashSet<String>(prepared.size * 2)
        val preparedIds = HashSet<WorkspaceArtifactId>(prepared.size * 2)
        prepared.forEach { artifact ->
            if (!identities.add(policy.identityKey(artifact.path))) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.DUPLICATE_PATH, artifact.path,
                )
            }
            if (!preparedIds.add(artifactId(runId, artifact.path))) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.DUPLICATE_ID, artifact.path,
                )
            }
        }
        val runHash = runHashOf(runId)
        val currentRecords = ensureIndex().values.filter { it.runId == runId }
        val totalAfter = currentRecords.sumOf { it.sizeBytes } + prepared.sumOf { it.sizeBytes }
        if (currentRecords.size + prepared.size > maxArtifacts) {
            throw WorkspaceBudgetExceeded(
                WorkspaceBudgetKind.ARTIFACTS, maxArtifacts.toLong(),
                (currentRecords.size + prepared.size).toLong(),
            )
        }
        if (totalAfter > maxTotalArtifactBytes) {
            throw WorkspaceBudgetExceeded(
                WorkspaceBudgetKind.TOTAL_ARTIFACT_BYTES, maxTotalArtifactBytes, totalAfter,
            )
        }
        val existingPaths = currentRecords.associateBy { policy.identityKey(it.path) }
        prepared.forEach { artifact ->
            val key = policy.identityKey(artifact.path)
            if (key in existingPaths) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.ALREADY_EXISTS, artifact.path,
                )
            }
            currentRecords.forEach { existing ->
                if (isHierarchyConflict(existing.path.value, artifact.path.value)) {
                    throw WorkspaceArtifactConflict(
                        WorkspaceArtifactConflict.Reason.DUPLICATE_PATH, artifact.path,
                    )
                }
            }
        }

        val runDirectory = WorkspaceStorageSupport.ensureDirectory(
            base,
            WorkspacePath("artifacts/runs/$runHash"),
        )
        val metadataFile = File(runDirectory, RUN_METADATA)
        val metadata = if (metadataFile.exists()) {
            readRunMetadata(metadataFile)
        } else {
            null
        }
        if (metadata != null && metadata.runId != runId) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.STORE_IDENTITY)
        }
        prepared.forEach { artifact ->
            val target = runTarget(runDirectory, artifact.path)
            if (target.exists()) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.ALREADY_EXISTS, artifact.path,
                )
            }
        }

        val createdAt = metadata?.createdAtMillis ?: clock.nowMillis().coerceAtLeast(0)
        val runRecords = ArrayList<WorkspaceArtifactRecord>(currentRecords.size + prepared.size)
        runRecords += currentRecords
        // Publish run metadata before the first body. If the process dies during the batch,
        // the directory remains scannable and unindexed files can be adopted on restart.
        if (metadata == null) {
            writeRunMetadata(runDirectory, runId, createdAt, runRecords)
        }
        val published = ArrayList<File>(prepared.size)
        try {
            prepared.forEach { artifact ->
                val target = runTarget(runDirectory, artifact.path)
                WorkspaceStorageSupport.writeAtomically(target, artifact.bytes, readOnly = true)
                published += target
                runRecords += WorkspaceArtifactRecord(
                    id = artifactId(runId, artifact.path),
                    runId = runId,
                    path = artifact.path,
                    mediaType = artifact.mediaType,
                    kind = artifact.kind,
                    sizeBytes = artifact.bytes.size.toLong(),
                    sha256 = artifact.sha256,
                    createdAtMillis = createdAt,
                )
            }
            writeRunMetadata(runDirectory, runId, createdAt, runRecords)
        } catch (failure: Exception) {
            // Best-effort rollback of only this batch; older artifacts stay untouched.
            published.forEach { file -> runCatching { file.delete() } }
            throw failure
        }

        index = null
        scannedRuns = null
        ensureIndex()
        prepared.map { artifact ->
            WorkspaceArtifactSummary(
                id = artifactId(runId, artifact.path),
                path = artifact.path,
                mediaType = artifact.mediaType,
                sizeBytes = artifact.bytes.size.toLong(),
                sha256 = artifact.sha256,
            )
        }
    }

    /**
     * Retention for complete inactive runs. [keepInactiveRuns] newest inactive runs are kept;
     * every run in [activeRuns] is kept even when that exceeds the count. Corrupt or unknown
     * directories are left untouched rather than guessed at.
     */
    suspend fun prune(
        activeRuns: Set<RunId>,
        keepInactiveRuns: Int = 8,
    ): WorkspaceArtifactPruneResult = lock.withLock {
        require(keepInactiveRuns >= 0) { "Invalid inactive run retention" }
        ensureStoreMetadata()
        val active = activeRuns.toSet()
        val runs = scannedRuns ?: scanRuns().also { scannedRuns = it }
        val sorted = runs.sortedWith(
            compareByDescending<ScannedRun> { it.createdAtMillis }.thenByDescending { it.runId.value },
        )
        var inactiveKept = 0
        var retainedActive = 0
        var retainedInactive = 0
        var removedArtifacts = 0
        var removedBytes = 0L
        val removedRuns = ArrayList<RunId>()
        sorted.forEach { run ->
            if (run.runId in active) {
                retainedActive += 1
                return@forEach
            }
            if (inactiveKept < keepInactiveRuns) {
                inactiveKept += 1
                retainedInactive += 1
                return@forEach
            }
            if (!WorkspaceStorageSupport.deleteTree(base, run.directory)) return@forEach
            removedRuns += run.runId
            removedArtifacts += run.records.size
            removedBytes += run.records.sumOf { it.sizeBytes }
        }
        index = null
        scannedRuns = null
        ensureIndex()
        WorkspaceArtifactPruneResult(
            removedRuns = removedRuns,
            removedArtifacts = removedArtifacts,
            removedBytes = removedBytes,
            retainedActiveRuns = retainedActive,
            retainedInactiveRuns = retainedInactive,
        )
    }

    /** Declared metadata stays visible even if a body is missing or corrupt. Never adopts bytes. */
    suspend fun inventory(runId: RunId): List<WorkspaceArtifactInventory> = lock.withLock {
        val metadata = recordedRun(runId) ?: return@withLock emptyList()
        val directory = WorkspaceStorageSupport.resolve(base, WorkspacePath("artifacts/runs/" + runHashOf(runId)))
        metadata.artifacts.values.sortedBy { it.path.value }.map { artifact ->
            currentCoroutineContext().ensureActive()
            val record = recordedArtifact(metadata, artifact)
            val availability = try {
                val file = runTarget(directory, record.path, createParents = false)
                when {
                    !file.isFile -> WorkspaceArtifactAvailability.MISSING
                    file.length() != record.sizeBytes -> WorkspaceArtifactAvailability.MISMATCH
                    else -> {
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        file.inputStream().use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > record.sizeBytes) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
                        if (total == record.sizeBytes && actual == record.sha256) WorkspaceArtifactAvailability.VERIFIED
                        else WorkspaceArtifactAvailability.MISMATCH
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WorkspaceArtifactAvailability.UNREADABLE }
            WorkspaceArtifactInventory(record, availability)
        }
    }

    suspend fun recordForRun(runId: RunId, id: WorkspaceArtifactId): WorkspaceArtifactRecord? = lock.withLock {
        recordedRun(runId)?.let { metadata ->
            metadata.artifacts.values.singleOrNull { it.id == id }?.let { recordedArtifact(metadata, it) }
        }
    }

    /** Reads one declared body independently of unrelated corrupt runs; still verifies all bytes. */
    suspend fun readForRun(runId: RunId, id: WorkspaceArtifactId, limitBytes: Long): WorkspaceArtifactContent = lock.withLock {
        require(limitBytes >= 0)
        val metadata = recordedRun(runId) ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
        val declared = metadata.artifacts.values.singleOrNull { it.id == id }
            ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
        if (declared.sizeBytes > limitBytes) throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.TOO_LARGE)
        val directory = WorkspaceStorageSupport.resolve(base, WorkspacePath("artifacts/runs/" + runHashOf(runId)))
        val file = runTarget(directory, declared.path, createParents = false)
        if (!file.isFile) throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
        currentCoroutineContext().ensureActive()
        val bytes = WorkspaceStorageSupport.readBounded(file, minOf(limitBytes, WorkspaceHardLimits.MAX_ARTIFACT_BYTES))
        currentCoroutineContext().ensureActive()
        if (bytes.size.toLong() != declared.sizeBytes || sha256Hex(bytes) != declared.sha256)
            throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.FINGERPRINT_MISMATCH)
        WorkspaceArtifactContent(id, declared.sha256, bytes)
    }

    private fun recordedRun(runId: RunId): RunMetadata? {
        ensureStoreMetadata()
        val directory = WorkspaceStorageSupport.resolve(base, WorkspacePath("artifacts/runs/" + runHashOf(runId)))
        val file = File(directory, RUN_METADATA)
        if (!file.isFile) return null
        val metadata = readRunMetadata(file)
        require(metadata.runId == runId) { "Artifact run identity changed" }
        return metadata
    }

    private fun recordedArtifact(metadata: RunMetadata, artifact: MetadataArtifact) = WorkspaceArtifactRecord(
        artifact.id, metadata.runId, artifact.path, artifact.mediaType, artifact.kind,
        artifact.sizeBytes, artifact.sha256, metadata.createdAtMillis)

    suspend fun read(id: WorkspaceArtifactId, limitBytes: Long): WorkspaceArtifactContent = lock.withLock {
        require(limitBytes >= 0) { "Invalid artifact read limit" }
        val record = ensureIndex()[id]
            ?: throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
        if (record.sizeBytes > limitBytes) {
            throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.TOO_LARGE)
        }
        val runDirectory = WorkspaceStorageSupport.resolve(
            base,
            WorkspacePath("artifacts/runs/" + runHashOf(record.runId)),
        )
        val file = runTarget(runDirectory, record.path, createParents = false)
        if (!file.isFile) throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.NOT_FOUND)
        val bytes = try {
            WorkspaceStorageSupport.readBounded(
                file,
                minOf(limitBytes, WorkspaceHardLimits.MAX_ARTIFACT_BYTES),
            )
        } catch (failure: Exception) {
            throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.READ_FAILED)
        }
        if (bytes.size.toLong() != record.sizeBytes || sha256Hex(bytes) != record.sha256) {
            throw WorkspaceArtifactUnavailable(WorkspaceArtifactUnavailable.Reason.FINGERPRINT_MISMATCH)
        }
        WorkspaceArtifactContent(record.id, record.sha256, bytes)
    }

    private fun ensureIndex(): Map<WorkspaceArtifactId, WorkspaceArtifactRecord> {
        index?.let { return it }
        val runs = scanRuns()
        val result = LinkedHashMap<WorkspaceArtifactId, WorkspaceArtifactRecord>()
        runs.forEach { run ->
            run.records.forEach { record ->
                if (result.put(record.id, record) != null) {
                    throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.DUPLICATE_ID, record.path)
                }
            }
        }
        scannedRuns = runs
        index = result
        return result
    }

    private fun scanIndex(): Map<WorkspaceArtifactId, WorkspaceArtifactRecord> {
        index = null
        return ensureIndex()
    }

    private fun scanRuns(): List<ScannedRun> {
        val directories = runsRoot.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val sorted = directories.sortedBy { it.name }
        require(sorted.size <= MAX_RUNS) { "Too many artifact runs" }
        return sorted.mapNotNull { directory ->
            val absolute = directory.absoluteFile
            val canonical = try {
                directory.canonicalFile
            } catch (_: IOException) {
                return@mapNotNull null
            }
            if (canonical.path != absolute.path) return@mapNotNull null
            scanRun(absolute)
        }
    }

    private fun scanRun(directory: File): ScannedRun? {
        val metadataFile = File(directory, RUN_METADATA)
        if (!metadataFile.exists()) {
            // An empty directory may be a crashed first save; never infer a RunId from a hash.
            return null
        }
        val metadata = try {
            readRunMetadata(metadataFile)
        } catch (_: Exception) {
            return null
        }
        if (metadataFile.length() > RUN_METADATA_MAX_BYTES) return null
        val files = try {
            WorkspaceStorageSupport.listRegularFiles(
                directory,
                policy.maxDepth,
                WorkspaceHardLimits.MAX_ARTIFACTS + 1,
            )
        } catch (_: Exception) {
            return null
        }
        val byPath = LinkedHashMap<String, WorkspaceArtifactRecord>()
        files.forEach { file ->
            val relative = file.relativeTo(directory).path.replace(File.separatorChar, '/')
            if (relative == RUN_METADATA || relative.startsWith(TEMP_PREFIX)) {
                if (relative.startsWith(TEMP_PREFIX)) runCatching { file.delete() }
                return@forEach
            }
            val path = try {
                validateArtifactPath(policy.parse(relative))
            } catch (_: Exception) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.PATH_POLICY,
                )
            }
            val size = file.length()
            if (size !in 0..WorkspaceHardLimits.MAX_ARTIFACT_BYTES) {
                throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE, path)
            }
            val digest = try {
                WorkspaceStorageSupport.sha256(file)
            } catch (_: IOException) {
                throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE, path)
            }
            val declared = metadata.artifacts[path.value]
            if (declared != null) {
                if (declared.path != path || declared.sizeBytes != size || declared.sha256 != digest) {
                    throw WorkspaceArtifactConflict(
                        WorkspaceArtifactConflict.Reason.CORRUPT_STORE, path,
                    )
                }
            }
            val id = artifactId(metadata.runId, path)
            if (byPath.put(path.value, WorkspaceArtifactRecord(
                id = id,
                runId = metadata.runId,
                path = path,
                mediaType = declared?.mediaType ?: WorkspaceStorageSupport.mediaType(path.value),
                kind = declared?.kind ?: kindOf(path.value),
                sizeBytes = size,
                sha256 = digest,
                createdAtMillis = metadata.createdAtMillis,
            )) != null) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.DUPLICATE_PATH, path,
                )
            }
        }
        return ScannedRun(
            runId = metadata.runId,
            directory = directory,
            createdAtMillis = metadata.createdAtMillis,
            records = byPath.values.toList(),
        )
    }

    private fun ensureStoreMetadata() {
        val file = File(artifactsRoot, STORE_METADATA)
        if (file.exists()) {
            val metadata = try {
                readStoreMetadata(file)
            } catch (failure: WorkspaceArtifactConflict) {
                throw failure
            } catch (failure: Exception) {
                throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE)
            }
            if (metadata.ref != ref) {
                throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.STORE_IDENTITY)
            }
            return
        }
        val bytes = storeMetadataBytes(ref)
        WorkspaceStorageSupport.writeAtomically(file, bytes, readOnly = true)
    }

    private fun readStoreMetadata(file: File): StoreMetadata {
        if (!WorkspaceStorageSupport.isSafeRegularTarget(file)) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE)
        }
        val text = try {
            WorkspaceStorageSupport.readBounded(file, STORE_METADATA_MAX_BYTES.toLong())
                .toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE)
        }
        val root = try {
            JsonObjectValue.parse(text, JsonInputLimits(STORE_METADATA_MAX_BYTES, 4)).json
        } catch (_: Exception) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE)
        }
        require(root.keys == STORE_KEYS) { "Invalid artifact store metadata" }
        require(string(root, "format") == STORE_FORMAT) { "Invalid artifact store format" }
        require(integer(root, "version") == STORE_VERSION) { "Invalid artifact store version" }
        val project = string(root, "project") ?: throw IllegalArgumentException("Invalid project")
        val instance = string(root, "instance") ?: throw IllegalArgumentException("Invalid instance")
        val generation = integer(root, "generation") ?: throw IllegalArgumentException("Invalid generation")
        return StoreMetadata(
            LocalProjectRef(
                com.breakyuna.noveltranslator.nativecore.domain.ProjectId(project),
                com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId(instance),
                com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration(generation),
            ),
        )
    }

    private fun readRunMetadata(file: File): RunMetadata {
        if (!WorkspaceStorageSupport.isSafeRegularTarget(file)) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE)
        }
        val text = try {
            WorkspaceStorageSupport.readBounded(file, RUN_METADATA_MAX_BYTES.toLong())
                .toString(Charsets.UTF_8)
        } catch (_: Exception) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE)
        }
        val root = try {
            JsonObjectValue.parse(text, JsonInputLimits(RUN_METADATA_MAX_BYTES, 6)).json
        } catch (_: Exception) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.CORRUPT_STORE)
        }
        require(root.keys == RUN_KEYS) { "Invalid artifact run metadata" }
        require(string(root, "format") == RUN_FORMAT) { "Invalid artifact run format" }
        require(integer(root, "version") == RUN_VERSION) { "Invalid artifact run version" }
        val runId = RunId(string(root, "run_id") ?: throw IllegalArgumentException("Invalid run id"))
        val createdAt = integer(root, "created_at") ?: throw IllegalArgumentException("Invalid run time")
        require(createdAt >= 0)
        val artifacts = (root["artifacts"] as? JsonArray ?: throw IllegalArgumentException("Invalid artifacts"))
            .map { raw ->
                val entry = raw as? JsonObject ?: throw IllegalArgumentException("Invalid artifact")
                require(entry.keys == RUN_ARTIFACT_KEYS) { "Invalid artifact entry" }
                val path = policy.parse(
                    string(entry, "path") ?: throw IllegalArgumentException("Invalid artifact path"),
                )
                val id = WorkspaceArtifactId(
                    string(entry, "id") ?: throw IllegalArgumentException("Invalid artifact id"),
                )
                val mediaType = string(entry, "media_type")
                    ?: throw IllegalArgumentException("Invalid artifact media type")
                val kind = WorkspaceArtifactKind.entries.firstOrNull {
                    it.name == string(entry, "kind")
                } ?: throw IllegalArgumentException("Invalid artifact kind")
                val size = integer(entry, "size_bytes") ?: throw IllegalArgumentException("Invalid size")
                val sha = string(entry, "sha256") ?: throw IllegalArgumentException("Invalid digest")
                MetadataArtifact(path, id, mediaType, kind, size, sha)
            }
        val byPath = LinkedHashMap<String, MetadataArtifact>()
        artifacts.forEach { artifact ->
            if (artifact.id != artifactId(runId, artifact.path)) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.CORRUPT_STORE, artifact.path,
                )
            }
            if (byPath.put(artifact.path.value, artifact) != null) {
                throw WorkspaceArtifactConflict(
                    WorkspaceArtifactConflict.Reason.DUPLICATE_PATH, artifact.path,
                )
            }
        }
        return RunMetadata(runId, createdAt, byPath)
    }

    private fun writeRunMetadata(
        directory: File,
        runId: RunId,
        createdAtMillis: Long,
        records: List<WorkspaceArtifactRecord>,
    ) {
        val bytes = buildJsonObject {
            put("format", RUN_FORMAT)
            put("version", RUN_VERSION)
            put("run_id", runId.value)
            put("created_at", createdAtMillis)
            put("artifacts", JsonArray(records.sortedBy { it.path.value }.map { record ->
                buildJsonObject {
                    put("id", record.id.value)
                    put("path", record.path.value)
                    put("media_type", record.mediaType)
                    put("kind", record.kind.name)
                    put("size_bytes", record.sizeBytes)
                    put("sha256", record.sha256)
                }
            }))
        }.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= RUN_METADATA_MAX_BYTES) { "Artifact run metadata is too large" }
        WorkspaceStorageSupport.writeAtomically(File(directory, RUN_METADATA), bytes, readOnly = true)
    }

    private fun validateArtifactPath(path: WorkspacePath): WorkspacePath {
        val parsed = try {
            policy.parse(path.value)
        } catch (_: InvalidWorkspacePath) {
            throw WorkspaceArtifactConflict(WorkspaceArtifactConflict.Reason.PATH_POLICY, path)
        }
        val first = parsed.value.substringBefore('/')
        if (first in PROTECTED_AREAS || parsed.value in PROTECTED_NAMES ||
            parsed.value.startsWith(TEMP_PREFIX)
        ) {
            throw WorkspaceArtifactConflict(
                WorkspaceArtifactConflict.Reason.PROTECTED_PATH, parsed,
            )
        }
        return parsed
    }

    private fun runTarget(
        runDirectory: File,
        path: WorkspacePath,
        createParents: Boolean = true,
    ): File {
        val target = WorkspaceStorageSupport.resolve(
            base,
            WorkspacePath("artifacts/runs/" + runDirectory.name + "/" + path.value),
            createParents = createParents,
        )
        require(WorkspaceStorageSupport.isWithin(runDirectory, target)) {
            "Artifact path escaped its run"
        }
        return target
    }

    private fun artifactId(runId: RunId, path: WorkspacePath): WorkspaceArtifactId =
        WorkspaceArtifactId(
            "artifact-" + sha256Hex((runId.value + ":" + path.value).toByteArray(Charsets.UTF_8)).take(40),
        )

    private fun runHashOf(runId: RunId): String =
        sha256Hex(runId.value.toByteArray(Charsets.UTF_8)).take(32)

    private fun isHierarchyConflict(existing: String, candidate: String): Boolean =
        existing == candidate ||
            existing.startsWith(candidate + "/") ||
            candidate.startsWith(existing + "/")

    private fun kindOf(path: String): WorkspaceArtifactKind =
        when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "txt", "srt", "vtt", "lrc", "ass", "ssa", "rpy" -> WorkspaceArtifactKind.TEXT
            "md", "markdown" -> WorkspaceArtifactKind.MARKDOWN
            "json", "jsonl" -> WorkspaceArtifactKind.JSON
            "csv" -> WorkspaceArtifactKind.CSV
            "png", "jpg", "jpeg", "webp", "gif" -> WorkspaceArtifactKind.IMAGE
            "pdf" -> WorkspaceArtifactKind.PDF
            "zip", "epub", "7z", "tar", "gz" -> WorkspaceArtifactKind.ARCHIVE
            else -> WorkspaceArtifactKind.BINARY
        }

    private fun storeMetadataBytes(ref: LocalProjectRef): ByteArray = buildJsonObject {
        put("format", STORE_FORMAT)
        put("version", STORE_VERSION)
        put("project", ref.projectId.value)
        put("instance", ref.instanceId.value)
        put("generation", ref.generation.value)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun string(root: JsonObject, key: String): String? =
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun integer(root: JsonObject, key: String): Long? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private class PreparedArtifact(
        val path: WorkspacePath,
        val mediaType: String,
        val bytes: ByteArray,
        val sha256: String,
    ) {
        val kind: WorkspaceArtifactKind = kindOf(path.value)
        val sizeBytes: Long = bytes.size.toLong()

        init {
            require(mediaType.isNotBlank() && mediaType.length <= 128) { "Invalid artifact media type" }
            require(mediaType.none(Char::isISOControl)) { "Control character in artifact media type" }
            require(bytes.size <= WorkspaceHardLimits.MAX_ARTIFACT_BYTES) { "Artifact too large" }
            require(sha256Hex(bytes) == sha256) { "Artifact fingerprint mismatch" }
        }

        private companion object {
            fun kindOf(path: String): WorkspaceArtifactKind = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "txt", "srt", "vtt", "lrc", "ass", "ssa", "rpy" -> WorkspaceArtifactKind.TEXT
                "md", "markdown" -> WorkspaceArtifactKind.MARKDOWN
                "json", "jsonl" -> WorkspaceArtifactKind.JSON
                "csv" -> WorkspaceArtifactKind.CSV
                "png", "jpg", "jpeg", "webp", "gif" -> WorkspaceArtifactKind.IMAGE
                "pdf" -> WorkspaceArtifactKind.PDF
                "zip", "epub", "7z", "tar", "gz" -> WorkspaceArtifactKind.ARCHIVE
                else -> WorkspaceArtifactKind.BINARY
            }
        }
    }

    private class StoreMetadata(val ref: LocalProjectRef)
    private class MetadataArtifact(
        val path: WorkspacePath,
        val id: WorkspaceArtifactId,
        val mediaType: String,
        val kind: WorkspaceArtifactKind,
        val sizeBytes: Long,
        val sha256: String,
    )

    private class RunMetadata(
        val runId: RunId,
        val createdAtMillis: Long,
        val artifacts: Map<String, MetadataArtifact>,
    )

    private class ScannedRun(
        val runId: RunId,
        val directory: File,
        val createdAtMillis: Long,
        val records: List<WorkspaceArtifactRecord>,
    )

    companion object {
        const val STORE_METADATA = "store.json"
        const val RUN_METADATA = "run.json"
        private const val STORE_FORMAT = "lnt-workspace-artifact-store"
        private const val STORE_VERSION = 1L
        private const val RUN_FORMAT = "lnt-workspace-artifact-run"
        private const val RUN_VERSION = 1L
        private const val STORE_METADATA_MAX_BYTES = 64 * 1024
        private const val RUN_METADATA_MAX_BYTES = 4 * 1024 * 1024
        private const val MAX_RUNS = 4_096
        private const val TEMP_PREFIX = ".lnt-workspace-"
        private val STORE_KEYS = setOf("format", "version", "project", "instance", "generation")
        private val RUN_KEYS = setOf("format", "version", "run_id", "created_at", "artifacts")
        private val RUN_ARTIFACT_KEYS = setOf(
            "id", "path", "media_type", "kind", "size_bytes", "sha256",
        )
        private val PROTECTED_AREAS = setOf(
            "sources", "datasets", "reference", "uploads", "artifacts",
        )
        private val PROTECTED_NAMES = setOf(
            "material-manifest.json", STORE_METADATA, RUN_METADATA,
        )

        private fun requirePrivateDirectoryName(name: String): String {
            require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", "..")) {
                "Invalid workspace directory name"
            }
            require(name.none(Char::isISOControl)) { "Invalid workspace directory name" }
            return name
        }
    }
}
