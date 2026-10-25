package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetPort
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectLimits
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectPort
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
import java.io.File
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Narrow project read port owned by the material source. Production wiring can use
 * [of] to adapt the existing [TextProjectPort] and [ProjectAssetPort] without exposing the
 * full stores to the workspace adapter.
 */
interface WorkspaceMaterialProjectPort {
    /** Current summary for the fixed local instance; the caller must verify its identity. */
    suspend fun summary(ref: LocalProjectRef): TextProjectSummary

    suspend fun assets(ref: LocalProjectRef): List<ProjectAssetSummary>

    suspend fun readAsset(ref: LocalProjectRef, assetId: Long): ByteArray

    companion object {
        /** Adapter from the two native project ports. It performs no retry and no caching. */
        fun of(projects: TextProjectPort, assetPort: ProjectAssetPort): WorkspaceMaterialProjectPort =
            object : WorkspaceMaterialProjectPort {
                override suspend fun summary(ref: LocalProjectRef): TextProjectSummary = projects.open(ref)

                override suspend fun assets(ref: LocalProjectRef): List<ProjectAssetSummary> =
                    assetPort.assets(ref)

                override suspend fun readAsset(ref: LocalProjectRef, assetId: Long): ByteArray =
                    assetPort.readAsset(ref, assetId)
            }
    }
}

class WorkspaceMaterialStaleRevision(val expectedRevision: Long, val actualRevision: Long) :
    IllegalStateException("Workspace material revision changed") {
    override fun toString(): String =
        "WorkspaceMaterialStaleRevision(expected=$expectedRevision, actual=$actualRevision)"
}

class WorkspaceMaterialSnapshotException(val reason: Reason, val subject: String? = null) :
    IllegalStateException("Invalid workspace material snapshot: $reason") {
    enum class Reason {
        PROJECT_IDENTITY,
        STALE_REVISION,
        CHANGED_DURING_SNAPSHOT,
        ASSET_MISMATCH,
        PROJECT_LIMIT,
        DUPLICATE_NAME,
        PATH_POLICY,
        MANIFEST_INVALID,
        READ_FAILED,
        CLEANUP_FAILED,
    }

    /** The subject can contain an identity or a path; callers must not log it as a body. */
    override fun toString(): String = "WorkspaceMaterialSnapshotException($reason)"
}

/** Metadata for one project asset recorded in the workspace manifest. */
class WorkspaceManifestAsset(
    val assetId: Long,
    val path: String,
    val format: ProjectAssetFormat,
    val enabled: Boolean,
    val sha256: String,
    val sizeBytes: Long,
    val itemCount: Int,
    val materialPath: String?,
) {
    init {
        require(assetId > 0) { "Invalid asset identity" }
        require(path.isNotBlank() && path.length <= 4_096 && path.none(Char::isISOControl)) {
            "Invalid asset path"
        }
        requireSha256Hex(sha256)
        require(sizeBytes >= 0) { "Invalid asset size" }
        require(itemCount >= 0) { "Invalid asset item count" }
        if (materialPath != null) {
            require(materialPath.isNotBlank() && materialPath.length <= 4_096) { "Invalid material path" }
            require(materialPath.none(Char::isISOControl)) { "Control character in material path" }
        }
    }

    override fun toString(): String =
        "WorkspaceManifestAsset(id=$assetId, path=${path.substringAfterLast('/')}, size=$sizeBytes)"
}

/**
 * Immutable manifest for one fixed project snapshot. It binds the complete project identity,
 * the exact revision, every asset digest/size/item count and all host-generated materials.
 * [digest] covers the canonical encoding so a truncated or edited manifest is rejected.
 */
class WorkspaceMaterialManifest(
    val ref: LocalProjectRef,
    val revision: Long,
    val itemCount: Int,
    val totalBytes: Long,
    assets: List<WorkspaceManifestAsset>,
    materials: List<WorkspaceMaterialSummary>,
) {
    val assets: List<WorkspaceManifestAsset> = workspaceFrozenList(assets)
    val materials: List<WorkspaceMaterialSummary> = workspaceFrozenList(materials)

    init {
        require(revision >= 0) { "Invalid manifest revision" }
        require(itemCount >= 0) { "Invalid manifest item count" }
        require(totalBytes >= 0) { "Invalid manifest size" }
        require(this.assets.map { it.assetId }.distinct().size == this.assets.size) {
            "Duplicate manifest asset"
        }
        require(this.materials.map { it.id }.distinct().size == this.materials.size) {
            "Duplicate manifest material"
        }
        require(this.materials.map { it.path.value }.distinct().size == this.materials.size) {
            "Duplicate manifest path"
        }
        require(this.materials.all { WorkspaceStorageSupport.areaOf(it.path) == it.kind }) {
            "Manifest material is outside its area"
        }
        require(this.materials.all {
            it.id == WorkspaceStorageSupport.materialId(it.kind, it.path, it.sha256)
        }) {
            "Manifest material identity mismatch"
        }
        require(this.assets.all { asset ->
            asset.materialPath == null || this.materials.any { material ->
                material.kind == WorkspaceMaterialKind.SOURCE &&
                    material.path.value == asset.materialPath &&
                    material.sourceRef == assetSource(asset.assetId)
            }
        }) {
            "Manifest asset does not match its material"
        }
        require(this.materials.sumOf { it.sizeBytes } == totalBytes) { "Manifest total mismatch" }
    }

    val digest: WorkspaceDigest by lazy {
        WorkspaceDigest(sha256Hex(WorkspaceMaterialManifestCodec.encode(this, includeDigest = false).toByteArray(Charsets.UTF_8)))
    }

    fun materialForAsset(assetId: Long): WorkspaceMaterialSummary? =
        materials.firstOrNull { it.sourceRef == assetSource(assetId) }

    override fun toString(): String =
        "WorkspaceMaterialManifest(revision=$revision, assets=${assets.size}, materials=${materials.size})"

    companion object {
        fun assetSource(assetId: Long): String = "asset:$assetId"
    }
}

internal object WorkspaceMaterialManifestCodec {
    const val FORMAT = "lnt-workspace-material-manifest"
    const val VERSION = 1L
    const val MAX_BYTES = 4 * 1024 * 1024

    private val ROOT_KEYS = setOf(
        "format", "version", "project", "instance", "generation", "revision", "item_count",
        "total_bytes", "digest", "assets", "materials",
    )
    private val ASSET_KEYS = setOf(
        "asset_id", "path", "format", "enabled", "sha256", "size_bytes", "item_count", "material",
    )
    private val MATERIAL_KEYS = setOf(
        "id", "kind", "path", "sha256", "size_bytes", "media_type", "ordinal", "origin", "source_ref",
    )

    fun encode(manifest: WorkspaceMaterialManifest, includeDigest: Boolean = true): String =
        buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("project", manifest.ref.projectId.value)
            put("instance", manifest.ref.instanceId.value)
            put("generation", manifest.ref.generation.value)
            put("revision", manifest.revision)
            put("item_count", manifest.itemCount)
            put("total_bytes", manifest.totalBytes)
            if (includeDigest) put("digest", manifest.digest.sha256)
            put("assets", JsonArray(manifest.assets.map { asset ->
                buildJsonObject {
                    put("asset_id", asset.assetId)
                    put("path", asset.path)
                    put("format", asset.format.name)
                    put("enabled", asset.enabled)
                    put("sha256", asset.sha256)
                    put("size_bytes", asset.sizeBytes)
                    put("item_count", asset.itemCount)
                    asset.materialPath?.let { put("material", it) }
                }
            }))
            put("materials", JsonArray(manifest.materials.map { material ->
                buildJsonObject {
                    put("id", material.id.value)
                    put("kind", material.kind.name)
                    put("path", material.path.value)
                    put("sha256", material.sha256)
                    put("size_bytes", material.sizeBytes)
                    put("media_type", material.mediaType)
                    put("ordinal", material.ordinal)
                    put("origin", material.origin.name)
                    material.sourceRef?.let { put("source_ref", it) }
                }
            }))
        }.toString()

    fun decode(text: String): WorkspaceMaterialManifest {
        require(text.length <= MAX_BYTES) { "Workspace material manifest is too large" }
        val root = try {
            com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue.parse(
                text,
                com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits(MAX_BYTES, 8),
            ).json
        } catch (failure: Exception) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.MANIFEST_INVALID,
            )
        }
        require(root.keys == ROOT_KEYS) { "Invalid workspace material manifest" }
        require(string(root, "format") == FORMAT) { "Unsupported workspace material manifest" }
        require(integer(root, "version") == VERSION) { "Unsupported workspace material manifest version" }
        val project = string(root, "project") ?: throw IllegalArgumentException("Invalid project identity")
        val instance = string(root, "instance") ?: throw IllegalArgumentException("Invalid instance identity")
        val generation = integer(root, "generation") ?: throw IllegalArgumentException("Invalid generation")
        val revision = integer(root, "revision") ?: throw IllegalArgumentException("Invalid revision")
        val itemCount = integer(root, "item_count")?.toInt() ?: throw IllegalArgumentException("Invalid item count")
        val totalBytes = integer(root, "total_bytes") ?: throw IllegalArgumentException("Invalid total size")
        require(revision >= 0 && itemCount >= 0 && totalBytes >= 0)
        val assets = (root["assets"] as? JsonArray ?: throw IllegalArgumentException("Invalid assets")).map { raw ->
            val asset = raw as? JsonObject ?: throw IllegalArgumentException("Invalid asset")
            require(asset.keys == ASSET_KEYS || asset.keys == ASSET_KEYS - "material") {
                "Invalid asset entry"
            }
            val assetId = integer(asset, "asset_id") ?: throw IllegalArgumentException("Invalid asset id")
            val path = string(asset, "path") ?: throw IllegalArgumentException("Invalid asset path")
            val format = ProjectAssetFormat.entries.firstOrNull { it.name == string(asset, "format") }
                ?: throw IllegalArgumentException("Invalid asset format")
            val enabled = boolean(asset, "enabled") ?: throw IllegalArgumentException("Invalid asset flag")
            val sha = string(asset, "sha256") ?: throw IllegalArgumentException("Invalid asset digest")
            val size = integer(asset, "size_bytes") ?: throw IllegalArgumentException("Invalid asset size")
            val count = integer(asset, "item_count")?.toInt() ?: throw IllegalArgumentException("Invalid asset count")
            val material = string(asset, "material")
            WorkspaceManifestAsset(assetId, path, format, enabled, sha, size, count, material)
        }
        val materials = (root["materials"] as? JsonArray ?: throw IllegalArgumentException("Invalid materials"))
            .map { raw ->
                val material = raw as? JsonObject ?: throw IllegalArgumentException("Invalid material")
                require(material.keys == MATERIAL_KEYS || material.keys == MATERIAL_KEYS - "source_ref") {
                    "Invalid material entry"
                }
                val kind = WorkspaceMaterialKind.entries.firstOrNull {
                    it.name == string(material, "kind")
                } ?: throw IllegalArgumentException("Invalid material kind")
                val origin = WorkspaceMaterialOrigin.entries.firstOrNull {
                    it.name == string(material, "origin")
                } ?: throw IllegalArgumentException("Invalid material origin")
                WorkspaceMaterialSummary(
                    id = WorkspaceMaterialId(
                        string(material, "id") ?: throw IllegalArgumentException("Invalid material id"),
                    ),
                    kind = kind,
                    path = WorkspacePath(
                        string(material, "path") ?: throw IllegalArgumentException("Invalid material path"),
                    ),
                    sha256 = string(material, "sha256") ?: throw IllegalArgumentException("Invalid material digest"),
                    sizeBytes = integer(material, "size_bytes")
                        ?: throw IllegalArgumentException("Invalid material size"),
                    mediaType = string(material, "media_type")
                        ?: throw IllegalArgumentException("Invalid material media type"),
                    ordinal = integer(material, "ordinal")?.toInt()
                        ?: throw IllegalArgumentException("Invalid material ordinal"),
                    origin = origin,
                    sourceRef = string(material, "source_ref"),
                )
            }
        val manifest = WorkspaceMaterialManifest(
            ref = LocalProjectRef(
                com.breakyuna.noveltranslator.nativecore.domain.ProjectId(project),
                com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId(instance),
                com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration(generation),
            ),
            revision = revision,
            itemCount = itemCount,
            totalBytes = totalBytes,
            assets = assets,
            materials = materials,
        )
        val expected = string(root, "digest") ?: throw IllegalArgumentException("Missing manifest digest")
        require(manifest.digest.sha256 == expected) { "Workspace material manifest digest mismatch" }
        return manifest
    }

    private fun string(root: JsonObject, key: String): String? =
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun integer(root: JsonObject, key: String): Long? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun boolean(root: JsonObject, key: String): Boolean? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
}

/**
 * Production material source for one fixed project revision and one workspace run root.
 *
 * The adapter materializes enabled textual project assets byte-for-byte under `sources/` and a
 * deterministic asset metadata table under `datasets/`, writes the manifest last and never
 * rewrites unsupported/binary assets. `reference/`, `uploads/` and `work/` are created but left
 * untouched so user and script material survives snapshots. The returned read port is bounded
 * and rejects any revision that no longer matches the fixed snapshot.
 *
 * Callers should invoke these suspend methods from an IO context; the class performs no
 * dispatcher switch so it can also be used by the synchronous interpreter bridge through
 * [SynchronousWorkspaceMaterialReadPort].
 */
class WorkspaceMaterialSource(
    root: File,
    private val ref: LocalProjectRef,
    private val revision: Long,
    private val project: WorkspaceMaterialProjectPort,
    private val budget: WorkspaceBudget = WorkspaceBudget(),
    private val textLimits: TextProjectLimits = TextProjectLimits(),
    private val policy: PathPolicy = PathPolicy.DEFAULT,
) : WorkspaceMaterialReadPort {
    private val base = WorkspaceStorageSupport.openRoot(root)
    private val lock = Mutex()
    private var cached: WorkspaceMaterialManifest? = null

    /**
     * Convenience for Android composition; [rootDirectoryName] is one private child of the app
     * files directory. The session-caller is still responsible for making the name unique per
     * project instance/generation (for example a hash of [LocalProjectRef] plus Chat/Run).
     */
    constructor(
        context: Context,
        rootDirectoryName: String,
        ref: LocalProjectRef,
        revision: Long,
        project: WorkspaceMaterialProjectPort,
        budget: WorkspaceBudget = WorkspaceBudget(),
        textLimits: TextProjectLimits = TextProjectLimits(),
        policy: PathPolicy = PathPolicy.DEFAULT,
    ) : this(
        File(
            context.applicationContext.filesDir,
            requirePrivateDirectoryName(rootDirectoryName),
        ),
        ref, revision, project, budget, textLimits, policy,
    )

    init {
        require(revision >= 0) { "Invalid fixed revision" }
        ensureAreas()
    }

    /** Materializes or revalidates the fixed snapshot and returns its manifest. */
    suspend fun snapshot(): WorkspaceMaterialManifest = lock.withLock {
        val summary = currentSummary()
        val assets = currentAssets()
        val existing = cached ?: readManifest()
        if (existing != null && matches(existing, summary, assets) && filesPresent(existing)) {
            cached = existing
            return@withLock existing
        }
        materialize(summary, assets)
    }

    /** Re-hashes every generated material and rechecks the project revision/assets. */
    suspend fun verify(): WorkspaceMaterialManifest = lock.withLock {
        val manifest = cached ?: readManifest() ?: materialize(currentSummary(), currentAssets())
        manifest.materials.forEach { material ->
            val file = resolve(material.path)
            if (!file.isFile || file.length() != material.sizeBytes ||
                WorkspaceStorageSupport.sha256(file) != material.sha256
            ) {
                throw WorkspaceMaterialSnapshotException(
                    WorkspaceMaterialSnapshotException.Reason.MANIFEST_INVALID,
                    material.path.value,
                )
            }
        }
        val summary = currentSummary()
        val assets = currentAssets()
        if (!matches(manifest, summary, assets)) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.CHANGED_DURING_SNAPSHOT,
            )
        }
        cached = manifest
        manifest
    }

    /** Finds the material that mirrors one project asset, if the asset is supported. */
    suspend fun materialForAsset(assetId: Long): WorkspaceMaterialSummary? =
        snapshot().materialForAsset(assetId)

    override suspend fun list(kind: WorkspaceMaterialKind?): List<WorkspaceMaterialSummary> =
        lock.withLock { listLocked(kind) }

    private suspend fun listLocked(kind: WorkspaceMaterialKind?): List<WorkspaceMaterialSummary> {
        ensureSnapshotLocked()
        val result = ArrayList<WorkspaceMaterialSummary>()
        val kinds = if (kind == null) {
            listOf(
                WorkspaceMaterialKind.SOURCE,
                WorkspaceMaterialKind.DATASET,
                WorkspaceMaterialKind.REFERENCE,
                WorkspaceMaterialKind.UPLOAD,
                WorkspaceMaterialKind.WORK,
            )
        } else {
            listOf(kind)
        }
        val seen = HashSet<String>()
        kinds.forEach { area ->
            if (area == WorkspaceMaterialKind.ARTIFACT) return@forEach
            val directory = File(base, WorkspaceStorageSupport.directoryName(area))
            if (!directory.isDirectory) return@forEach
            val files = WorkspaceStorageSupport.listRegularFiles(
                directory,
                policy.maxDepth,
                WorkspaceHardLimits.MAX_MATERIALS,
            )
            for (file in files) {
                val relative = file.relativeTo(base).path.replace(File.separatorChar, '/')
                val path = try {
                    policy.parse(relative)
                } catch (_: InvalidWorkspacePath) {
                    continue
                }
                if (!seen.add(path.value)) continue
                val size = file.length()
                if (size !in 0..WorkspaceHardLimits.MAX_SINGLE_BYTES) continue
                val digest = try {
                    WorkspaceStorageSupport.sha256(file)
                } catch (_: IOException) {
                    throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.READ_FAILED)
                }
                val id = WorkspaceStorageSupport.materialId(area, path, digest)
                val recorded = cached?.materials?.firstOrNull { it.path.value == path.value }
                result += WorkspaceMaterialSummary(
                    id = id,
                    kind = area,
                    path = path,
                    sha256 = digest,
                    sizeBytes = size,
                    mediaType = recorded?.mediaType ?: WorkspaceStorageSupport.mediaType(path.value),
                    ordinal = recorded?.ordinal ?: result.size,
                    origin = recorded?.origin ?: defaultOrigin(area),
                    sourceRef = recorded?.sourceRef,
                )
            }
        }
        require(result.size <= WorkspaceHardLimits.MAX_MATERIALS) { "Too many workspace materials" }
        return result.sortedWith(compareBy<WorkspaceMaterialSummary> { it.kind.name }.thenBy { it.path.value })
    }

    override suspend fun summary(id: WorkspaceMaterialId): WorkspaceMaterialSummary? {
        val kind = WorkspaceStorageSupport.kindOf(id) ?: return null
        return list(kind).firstOrNull { it.id == id }
    }

    override suspend fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent =
        lock.withLock {
            require(limitBytes >= 0) { "Invalid material read limit" }
            val kind = WorkspaceStorageSupport.kindOf(id)
                ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
            // The identity binds one path to the digest observed when it was listed. A rewritten
            // file must produce FINGERPRINT_MISMATCH instead of appearing as new content.
            val prefix = id.value.substringBeforeLast('-')
            val expectedSha = id.value.substringAfterLast('-')
            val summary = listLocked(kind).firstOrNull { it.id.value.substringBeforeLast('-') == prefix }
                ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
            if (summary.sha256 != expectedSha) {
                throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH)
            }
            if (summary.sizeBytes > limitBytes) {
                throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.TOO_LARGE)
            }
            val file = resolve(summary.path)
            if (!file.isFile) throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
            val bytes = try {
                WorkspaceStorageSupport.readBounded(file, minOf(limitBytes, WorkspaceHardLimits.MAX_SINGLE_BYTES))
            } catch (_: Exception) {
                throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.READ_FAILED)
            }
            if (bytes.size.toLong() != summary.sizeBytes || sha256Hex(bytes) != summary.sha256) {
                throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH)
            }
            WorkspaceMaterialContent(summary.id, summary.sha256, bytes)
        }

    private suspend fun ensureSnapshotLocked(): WorkspaceMaterialManifest {
        val summary = currentSummary()
        val assets = currentAssets()
        val existing = cached ?: readManifest()
        if (existing != null && matches(existing, summary, assets) && filesPresent(existing)) {
            cached = existing
            return existing
        }
        return materialize(summary, assets)
    }

    private suspend fun currentSummary(): TextProjectSummary {
        val summary = try {
            project.summary(ref)
        } catch (failure: java.util.concurrent.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.READ_FAILED,
            )
        }
        if (summary.ref != ref) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.PROJECT_IDENTITY,
            )
        }
        if (summary.revision != revision) {
            throw WorkspaceMaterialStaleRevision(revision, summary.revision)
        }
        if (summary.itemCount !in 0..textLimits.maxItems) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.PROJECT_LIMIT,
            )
        }
        return summary
    }

    private suspend fun currentAssets(): List<ProjectAssetSummary> {
        val assets = try {
            project.assets(ref)
        } catch (failure: java.util.concurrent.CancellationException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.READ_FAILED,
            )
        }
        require(assets.size <= WorkspaceHardLimits.MAX_MATERIALS) { "Too many project assets" }
        require(assets.map { it.id }.distinct().size == assets.size) { "Duplicate project asset" }
        assets.forEach { asset ->
            if (asset.itemCount !in 0..textLimits.maxItems) {
                throw WorkspaceMaterialSnapshotException(
                    WorkspaceMaterialSnapshotException.Reason.PROJECT_LIMIT,
                    asset.id.toString(),
                )
            }
        }
        return assets
    }

    private fun matches(
        manifest: WorkspaceMaterialManifest,
        summary: TextProjectSummary,
        assets: List<ProjectAssetSummary>,
    ): Boolean {
        if (manifest.ref != ref || summary.ref != ref) return false
        if (manifest.revision != revision || summary.revision != revision) return false
        if (manifest.itemCount != summary.itemCount) return false
        if (manifest.assets.size != assets.size) return false
        if (assets.isNotEmpty() &&
            assets.sumOf { it.itemCount.toLong() } != summary.itemCount.toLong()
        ) return false
        val sorted = assets.sortedWith(compareBy<ProjectAssetSummary> { it.ordinal }.thenBy { it.id })
        return sorted.withIndex().all { (index, asset) ->
            val declared = manifest.assets[index]
            declared.assetId == asset.id &&
                declared.path == asset.path.value &&
                declared.format == asset.format &&
                declared.enabled == asset.enabled &&
                declared.sha256 == asset.sha256 &&
                declared.sizeBytes == asset.originalSize &&
                declared.itemCount == asset.itemCount &&
                declared.materialPath == expectedMaterialPath(asset)
        }
    }

    private suspend fun materialize(
        summary: TextProjectSummary,
        assets: List<ProjectAssetSummary>,
    ): WorkspaceMaterialManifest {
        // A manifest is the completion marker. Read the previous metadata before removing it,
        // then delete it before touching generated files so a crash cannot leave an older
        // manifest pointing at a half-written snapshot.
        val manifestFile = File(base, MANIFEST_NAME)
        val previous = readManifest()
        if (manifestFile.exists()) {
            require(WorkspaceStorageSupport.isSafeRegularTarget(manifestFile)) {
                "Workspace material manifest is not a regular file"
            }
            require(manifestFile.delete()) { "Workspace material manifest could not be replaced" }
        }
        cached = null

        ensureAreas()
        val desired = ArrayList<DesiredMaterial>()
        val manifestAssets = ArrayList<WorkspaceManifestAsset>()
        val sorted = assets.sortedWith(compareBy<ProjectAssetSummary> { it.ordinal }.thenBy { it.id })
        val seenPaths = HashSet<String>(sorted.size * 2)
        var ordinal = 0
        try {
            if (sorted.isNotEmpty() && sorted.sumOf { it.itemCount.toLong() } != summary.itemCount.toLong()) {
                throw WorkspaceMaterialSnapshotException(
                    WorkspaceMaterialSnapshotException.Reason.ASSET_MISMATCH,
                )
            }
            sorted.forEach { asset ->
                val assetPath = asset.path.value
                validateAssetPath(assetPath)
                val targetPath = expectedMaterialPath(asset)?.let { policy.parse(it) }
                if (targetPath != null) {
                    if (!seenPaths.add(policy.identityKey(targetPath))) {
                        throw WorkspaceMaterialSnapshotException(
                            WorkspaceMaterialSnapshotException.Reason.DUPLICATE_NAME,
                            targetPath.value,
                        )
                    }
                    if (asset.originalSize > textLimits.maxSourceBytes.toLong()) {
                        throw WorkspaceMaterialSnapshotException(
                            WorkspaceMaterialSnapshotException.Reason.PROJECT_LIMIT,
                            asset.id.toString(),
                        )
                    }
                    val bytes = try {
                        project.readAsset(ref, asset.id)
                    } catch (failure: java.util.concurrent.CancellationException) {
                        throw failure
                    } catch (failure: Exception) {
                        throw WorkspaceMaterialSnapshotException(
                            WorkspaceMaterialSnapshotException.Reason.READ_FAILED,
                            asset.id.toString(),
                        )
                    }
                    if (bytes.size.toLong() != asset.originalSize || sha256Hex(bytes) != asset.sha256) {
                        throw WorkspaceMaterialSnapshotException(
                            WorkspaceMaterialSnapshotException.Reason.ASSET_MISMATCH,
                            asset.id.toString(),
                        )
                    }
                    val material = WorkspaceMaterialSummary(
                        id = WorkspaceStorageSupport.materialId(
                            WorkspaceMaterialKind.SOURCE, targetPath, asset.sha256,
                        ),
                        kind = WorkspaceMaterialKind.SOURCE,
                        path = targetPath,
                        sha256 = asset.sha256,
                        sizeBytes = bytes.size.toLong(),
                        mediaType = WorkspaceStorageSupport.mediaType(targetPath.value),
                        ordinal = ordinal++,
                        origin = WorkspaceMaterialOrigin.PROJECT_ASSET,
                        sourceRef = WorkspaceMaterialManifest.assetSource(asset.id),
                    )
                    desired += DesiredMaterial(material, bytes)
                    manifestAssets += WorkspaceManifestAsset(
                        asset.id, assetPath, asset.format, asset.enabled, asset.sha256,
                        asset.originalSize, asset.itemCount, targetPath.value,
                    )
                } else {
                    manifestAssets += WorkspaceManifestAsset(
                        asset.id, assetPath, asset.format, asset.enabled, asset.sha256,
                        asset.originalSize, asset.itemCount, null,
                    )
                }
            }

            val datasetPath = policy.parse("datasets/assets.jsonl")
            val dataset = datasetBytes(manifestAssets, summary).toByteArray(Charsets.UTF_8)
            if (dataset.size > textLimits.maxSourceBytes) {
                throw WorkspaceMaterialSnapshotException(
                    WorkspaceMaterialSnapshotException.Reason.PROJECT_LIMIT,
                    datasetPath.value,
                )
            }
            val datasetSummary = WorkspaceMaterialSummary(
                id = WorkspaceStorageSupport.materialId(
                    WorkspaceMaterialKind.DATASET, datasetPath, sha256Hex(dataset),
                ),
                kind = WorkspaceMaterialKind.DATASET,
                path = datasetPath,
                sha256 = sha256Hex(dataset),
                sizeBytes = dataset.size.toLong(),
                mediaType = "application/x-ndjson",
                ordinal = ordinal,
                origin = WorkspaceMaterialOrigin.HOST_REQUEST,
                sourceRef = "project-assets",
            )
            desired += DesiredMaterial(datasetSummary, dataset)
            requireNoPathHierarchy(desired)

            val meter = WorkspaceBudgetMeter(budget)
            desired.forEach { entry -> meter.recordMaterial(entry.summary.sizeBytes) }

            val manifest = WorkspaceMaterialManifest(
                ref = ref,
                revision = revision,
                itemCount = summary.itemCount,
                totalBytes = desired.sumOf { it.summary.sizeBytes },
                assets = manifestAssets,
                materials = desired.map { it.summary },
            )

            // Remove stale generated source/dataset files before writing the new snapshot. The
            // manifest is already gone, so readers cannot observe this intermediate state.
            previous?.materials?.forEach { material ->
                if (material.kind != WorkspaceMaterialKind.SOURCE &&
                    !(material.kind == WorkspaceMaterialKind.DATASET &&
                        material.path.value == "datasets/assets.jsonl")
                ) return@forEach
                if (desired.none { it.summary.path.value == material.path.value }) {
                    try {
                        WorkspaceStorageSupport.deleteRegularFile(base, resolve(material.path))
                    } catch (failure: Exception) {
                        throw WorkspaceMaterialSnapshotException(
                            WorkspaceMaterialSnapshotException.Reason.CLEANUP_FAILED,
                            material.path.value,
                        )
                    }
                }
            }
            sweepSources(desired.map { it.summary.path.value }.toSet())

            desired.forEach { entry ->
                val file = resolve(entry.summary.path, createParents = true)
                WorkspaceStorageSupport.writeAtomically(file, entry.bytes, readOnly = true)
            }

            val bytes = WorkspaceMaterialManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
            WorkspaceStorageSupport.writeAtomically(manifestFile, bytes, readOnly = true)
            cached = manifest

            val afterSummary = currentSummary()
            val afterAssets = currentAssets()
            if (!matches(manifest, afterSummary, afterAssets)) {
                WorkspaceStorageSupport.deleteRegularFile(base, manifestFile)
                cached = null
                throw WorkspaceMaterialSnapshotException(
                    WorkspaceMaterialSnapshotException.Reason.CHANGED_DURING_SNAPSHOT,
                )
            }
            return manifest
        } catch (failure: WorkspaceBudgetExceeded) {
            throw failure
        } catch (failure: WorkspaceMaterialSnapshotException) {
            throw failure
        } catch (failure: InvalidWorkspacePath) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.PATH_POLICY,
                failure.toString(),
            )
        }
    }

    private fun expectedMaterialPath(asset: ProjectAssetSummary): String? =
        if (asset.enabled && asset.format.textual) "sources/" + asset.path.value else null

    private fun requireNoPathHierarchy(desired: List<DesiredMaterial>) {
        val paths = desired.mapTo(HashSet(desired.size * 2)) { it.summary.path.value }
        desired.forEach { entry ->
            val path = entry.summary.path.value
            var index = path.indexOf('/')
            while (index > 0) {
                if (path.substring(0, index) in paths) {
                    throw WorkspaceMaterialSnapshotException(
                        WorkspaceMaterialSnapshotException.Reason.DUPLICATE_NAME,
                        path,
                    )
                }
                index = path.indexOf('/', index + 1)
            }
        }
    }

    private fun validateAssetPath(path: String) {
        val normalized = policy.normalize(path)
        if (normalized != path) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.PATH_POLICY,
            )
        }
        val destination = "sources/" + path
        try {
            policy.parse(destination)
        } catch (_: InvalidWorkspacePath) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.PATH_POLICY,
            )
        }
    }

    private fun datasetBytes(assets: List<WorkspaceManifestAsset>, summary: TextProjectSummary): String {
        val builder = StringBuilder(assets.size * 160 + 32)
        assets.forEach { asset ->
            builder.append(buildJsonObject {
                put("asset_id", asset.assetId)
                put("path", asset.path)
                put("format", asset.format.name)
                put("enabled", asset.enabled)
                put("sha256", asset.sha256)
                put("size_bytes", asset.sizeBytes)
                put("item_count", asset.itemCount)
                if (asset.materialPath != null) put("material", asset.materialPath)
            }.toString())
            builder.append('\n')
        }
        builder.append(buildJsonObject {
            put("project", ref.projectId.value)
            put("instance", ref.instanceId.value)
            put("generation", ref.generation.value)
            put("revision", summary.revision)
            put("item_count", summary.itemCount)
            put("assets", assets.size)
            put("total_asset_bytes", assets.sumOf { it.sizeBytes })
        }.toString())
        builder.append('\n')
        return builder.toString()
    }

    private fun filesPresent(manifest: WorkspaceMaterialManifest): Boolean =
        manifest.materials.all { material ->
            val file = try {
                resolve(material.path)
            } catch (_: Exception) {
                return@all false
            }
            file.isFile && file.length() == material.sizeBytes
        }

    private fun readManifest(): WorkspaceMaterialManifest? {
        val file = File(base, MANIFEST_NAME)
        if (!file.exists()) return null
        val manifest = try {
            if (!WorkspaceStorageSupport.isSafeRegularTarget(file)) {
                throw WorkspaceMaterialSnapshotException(
                    WorkspaceMaterialSnapshotException.Reason.MANIFEST_INVALID,
                )
            }
            val bytes = WorkspaceStorageSupport.readBounded(file, WorkspaceMaterialManifestCodec.MAX_BYTES.toLong())
            WorkspaceMaterialManifestCodec.decode(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        if (manifest.ref != ref) {
            throw WorkspaceMaterialSnapshotException(
                WorkspaceMaterialSnapshotException.Reason.PROJECT_IDENTITY,
            )
        }
        return if (manifest.revision == revision) manifest else null
    }

    private fun ensureAreas() {
        val areas = setOf(
            WorkspaceMaterialKind.SOURCE,
            WorkspaceMaterialKind.DATASET,
            WorkspaceMaterialKind.REFERENCE,
            WorkspaceMaterialKind.UPLOAD,
            WorkspaceMaterialKind.WORK,
        )
        areas.forEach { area ->
            WorkspaceStorageSupport.ensureDirectory(
                base,
                WorkspacePath(WorkspaceStorageSupport.directoryName(area)),
            )
        }
    }

    private fun sweepSources(keep: Set<String>) {
        val directory = File(base, WorkspaceStorageSupport.directoryName(WorkspaceMaterialKind.SOURCE))
        if (!directory.isDirectory) return
        WorkspaceStorageSupport.listRegularFiles(
            directory,
            policy.maxDepth,
            WorkspaceHardLimits.MAX_MATERIALS,
        ).forEach { file ->
            val relative = file.relativeTo(base).path.replace(File.separatorChar, '/')
            if (relative !in keep) {
                try {
                    WorkspaceStorageSupport.deleteRegularFile(base, file)
                } catch (_: Exception) {
                    throw WorkspaceMaterialSnapshotException(
                        WorkspaceMaterialSnapshotException.Reason.CLEANUP_FAILED,
                        relative,
                    )
                }
            }
        }
    }

    private fun resolve(path: WorkspacePath, createParents: Boolean = false): File =
        WorkspaceStorageSupport.resolve(base, path, createParents)

    private fun defaultOrigin(kind: WorkspaceMaterialKind): WorkspaceMaterialOrigin = when (kind) {
        WorkspaceMaterialKind.SOURCE, WorkspaceMaterialKind.DATASET -> WorkspaceMaterialOrigin.PROJECT_ASSET
        WorkspaceMaterialKind.UPLOAD -> WorkspaceMaterialOrigin.USER_IMPORT
        WorkspaceMaterialKind.REFERENCE, WorkspaceMaterialKind.WORK, WorkspaceMaterialKind.ARTIFACT ->
            WorkspaceMaterialOrigin.HOST_REQUEST
    }

    private class DesiredMaterial(val summary: WorkspaceMaterialSummary, val bytes: ByteArray)

    companion object {
        const val MANIFEST_NAME = "material-manifest.json"

        private fun requirePrivateDirectoryName(name: String): String {
            require(name.isNotBlank() && '/' !in name && '\\' !in name && name !in setOf(".", "..")) {
                "Invalid workspace directory name"
            }
            require(name.none(Char::isISOControl)) { "Invalid workspace directory name" }
            return name
        }
    }
}
