package com.breakyuna.noveltranslator.nativecore.storage.backup

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import java.util.Collections
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AppBackupScope { SETTINGS, FULL_ARCHIVE }

/**
 * One entry of a schema-2 manifest. [contentClass] is checked against [kind] so a settings
 * payload can never be labelled as an approval audit (or vice versa) on restore.
 */
class AppBackupManifestV2Entry(
    val path: String,
    val kind: FullArchiveEntryKind,
    val bytes: Long,
    val sha256: String,
    val contentClass: AppBackupContentClass = kind.contentClass,
    val consistentCut: Boolean = true,
) {
    init {
        require(path.length in 1..240 && path.none(Char::isISOControl)) {
            "Invalid backup entry path"
        }
        require(bytes >= 0) { "Invalid backup entry size" }
        require(validArchiveSha256(sha256)) { "Invalid backup entry digest" }
        require(contentClass == kind.contentClass) { "Backup entry content class mismatch" }
    }

    override fun toString() =
        "AppBackupManifestV2Entry(path=$path, kind=$kind, bytes=$bytes, consistentCut=$consistentCut)"
}

/**
 * Complete schema-2 manifest. The four false/true flags are structural invariants, not caller
 * options: a decoded manifest that claims executable approvals or a runnable queue is rejected.
 */
class AppBackupManifestV2(
    val scope: AppBackupScope,
    val appVersion: String,
    val appSchemaVersion: Int,
    val queueSchemaVersion: Int,
    val snapshotId: String,
    val cutAtMillis: Long,
    val createdAtMillis: Long,
    val quiesced: Boolean,
    val activeRun: String?,
    val queueRunning: Boolean,
    val totalBytes: Long,
    projectInstances: List<AppBackupProjectInstance>,
    entries: List<AppBackupManifestV2Entry>,
) {
    val projectInstances: List<AppBackupProjectInstance> =
        Collections.unmodifiableList(ArrayList(projectInstances))
    val entries: List<AppBackupManifestV2Entry> =
        Collections.unmodifiableList(ArrayList(entries))

    init {
        require(validArchiveLabel(appVersion, 100)) { "Invalid application version" }
        require(appSchemaVersion >= 1) { "Invalid application schema version" }
        require(queueSchemaVersion >= 1) { "Invalid queue schema version" }
        require(snapshotId.length in 1..128 && snapshotId.none(Char::isISOControl)) {
            "Invalid backup snapshot id"
        }
        require(cutAtMillis >= 0 && createdAtMillis >= 0) { "Invalid backup time" }
        require(activeRun == null || validArchiveLabel(activeRun, 200)) {
            "Invalid active run identity"
        }
        require(totalBytes >= 0) { "Invalid backup size" }
        require(this.projectInstances.size <= AppBackupProjectInstance.MAX_PROJECTS) {
            "Too many project instances"
        }
        require(this.projectInstances.map { it.oldRef }.distinct().size == this.projectInstances.size) {
            "Duplicate project instance identity"
        }
        require(this.projectInstances.map { it.sha256 }.distinct().size == this.projectInstances.size) {
            "Duplicate project snapshot digest"
        }
        require(scope == AppBackupScope.FULL_ARCHIVE || this.projectInstances.isEmpty()) {
            "Settings backup contains project instances"
        }
        require(this.entries.map { it.path }.distinct().size == this.entries.size) {
            "Duplicate backup entry"
        }
        this.entries.forEach { entry ->
            BackupManifestValidator.validatePath(entry.path, entry.kind)
        }
        require(this.entries.fold(0L) { total, entry -> Math.addExact(total, entry.bytes) } == totalBytes) {
            "Backup manifest total does not match entries"
        }
        if (scope == AppBackupScope.SETTINGS) {
            require(this.entries.all { it.kind.settingsKind != null }) {
                "Settings backup contains archive-only entries"
            }
        }
    }

    override fun toString() =
        "AppBackupManifestV2(scope=$scope, entries=${entries.size}, snapshotId=$snapshotId)"
}

internal object AppBackupManifestV2Codec {
    const val FORMAT = "lnt-application-backup"
    const val SCHEMA_VERSION = 2L

    private val ROOT_KEYS = setOf(
        "format", "schema_version", "backup_scope", "app_version", "app_schema_version",
        "queue_schema_version", "snapshot_id", "cut_at", "created_at", "quiesced",
        "active_run", "queue_running", "credentials_excluded",
        "execution_authorization_included", "pending_approvals_included",
        "runnable_queue_included", "total_bytes", "project_instances", "entries",
    )
    private val PROJECT_KEYS = setOf("old_ref", "name", "revision", "sha256")
    private val OLD_REF_KEYS = setOf("project", "instance", "generation")
    private val ENTRY_KEYS = setOf(
        "path", "kind", "bytes", "sha256", "content_class", "consistent_cut",
    )

    fun encode(manifest: AppBackupManifestV2): String = buildJsonObject {
        put("format", FORMAT)
        put("schema_version", SCHEMA_VERSION)
        put("backup_scope", manifest.scope.name)
        put("app_version", manifest.appVersion)
        put("app_schema_version", manifest.appSchemaVersion)
        put("queue_schema_version", manifest.queueSchemaVersion)
        put("snapshot_id", manifest.snapshotId)
        put("cut_at", manifest.cutAtMillis)
        put("created_at", manifest.createdAtMillis)
        put("quiesced", manifest.quiesced)
        put("active_run", manifest.activeRun?.let { JsonPrimitive(it) } ?: JsonNull)
        put("queue_running", manifest.queueRunning)
        put("credentials_excluded", true)
        put("execution_authorization_included", false)
        put("pending_approvals_included", false)
        put("runnable_queue_included", false)
        put("total_bytes", manifest.totalBytes)
        put("project_instances", JsonArray(manifest.projectInstances.map { instance ->
            buildJsonObject {
                put("old_ref", buildJsonObject {
                    put("project", instance.oldRef.projectId.value)
                    put("instance", instance.oldRef.instanceId.value)
                    put("generation", instance.oldRef.generation.value)
                })
                put("name", instance.name)
                put("revision", instance.revision)
                put("sha256", instance.sha256)
            }
        }))
        put("entries", JsonArray(manifest.entries.map { entry ->
            buildJsonObject {
                put("path", entry.path)
                put("kind", entry.kind.name)
                put("bytes", entry.bytes)
                put("sha256", entry.sha256)
                put("content_class", entry.contentClass.name)
                put("consistent_cut", entry.consistentCut)
            }
        }))
    }.toString()

    fun decode(text: String, limits: AppBackupLimits): AppBackupManifestV2 {
        require(text.length <= limits.maxManifestBytes) { "Backup manifest is too large" }
        val root = try {
            JsonObjectValue.parse(text, JsonInputLimits(limits.maxManifestBytes, 8)).json
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid application backup manifest")
        }
        require(root.keys == ROOT_KEYS) { "Invalid application backup manifest" }
        require(string(root, "format") == FORMAT) { "Unsupported application backup format" }
        require(integer(root, "schema_version") == SCHEMA_VERSION) {
            "Unsupported application backup version"
        }
        val scope = AppBackupScope.entries.firstOrNull { it.name == string(root, "backup_scope") }
            ?: throw IllegalArgumentException("Unknown backup scope")
        require(boolean(root, "credentials_excluded") == true) {
            "Backup does not exclude credentials"
        }
        require(boolean(root, "execution_authorization_included") == false) {
            "Backup contains execution authorization"
        }
        require(boolean(root, "pending_approvals_included") == false) {
            "Backup contains pending approvals"
        }
        require(boolean(root, "runnable_queue_included") == false) {
            "Backup contains a runnable queue"
        }
        val totalBytes = integer(root, "total_bytes")
            ?: throw IllegalArgumentException("Invalid backup size")
        require(totalBytes in 0L..limits.maxTotalBytes) { "Backup exceeds size budget" }
        val projectInstances = decodeProjectInstances(root, limits)
        val entries = decodeEntries(root, limits)
        return AppBackupManifestV2(
            scope = scope,
            appVersion = string(root, "app_version")
                ?: throw IllegalArgumentException("Invalid application version"),
            appSchemaVersion = boundedInt(root, "app_schema_version", 1..Int.MAX_VALUE),
            queueSchemaVersion = boundedInt(root, "queue_schema_version", 1..Int.MAX_VALUE),
            snapshotId = string(root, "snapshot_id")
                ?: throw IllegalArgumentException("Invalid backup snapshot id"),
            cutAtMillis = nonNegative(root, "cut_at"),
            createdAtMillis = nonNegative(root, "created_at"),
            quiesced = boolean(root, "quiesced")
                ?: throw IllegalArgumentException("Invalid quiesced flag"),
            activeRun = nullableString(root, "active_run"),
            queueRunning = boolean(root, "queue_running")
                ?: throw IllegalArgumentException("Invalid queue flag"),
            totalBytes = totalBytes,
            projectInstances = projectInstances,
            entries = entries,
        )
    }

    private fun decodeProjectInstances(
        root: JsonObject,
        limits: AppBackupLimits,
    ): List<AppBackupProjectInstance> {
        val array = root["project_instances"] as? JsonArray
            ?: throw IllegalArgumentException("Invalid project instances")
        require(array.size <= minOf(AppBackupProjectInstance.MAX_PROJECTS, limits.maxEntries)) {
            "Too many project instances"
        }
        return array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Invalid project instance")
            require(obj.keys == PROJECT_KEYS) { "Invalid project instance fields" }
            val ref = obj["old_ref"] as? JsonObject
                ?: throw IllegalArgumentException("Invalid old project reference")
            require(ref.keys == OLD_REF_KEYS) { "Invalid old project reference fields" }
            val project = string(ref, "project")
                ?: throw IllegalArgumentException("Invalid old project id")
            val instance = string(ref, "instance")
                ?: throw IllegalArgumentException("Invalid old instance id")
            require(validArchiveLabel(project, 200) && validArchiveLabel(instance, 200)) {
                "Invalid old project identity"
            }
            val generation = integer(ref, "generation")
                ?: throw IllegalArgumentException("Invalid old project generation")
            require(generation >= 1) { "Invalid old project generation" }
            AppBackupProjectInstance(
                oldRef = LocalProjectRef(
                    ProjectId(project),
                    ProjectInstanceId(instance),
                    RecoveryGeneration(generation),
                ),
                name = string(obj, "name")
                    ?: throw IllegalArgumentException("Invalid project name"),
                revision = nonNegative(obj, "revision"),
                sha256 = string(obj, "sha256")
                    ?: throw IllegalArgumentException("Invalid project snapshot digest"),
            )
        }
    }

    private fun decodeEntries(
        root: JsonObject,
        limits: AppBackupLimits,
    ): List<AppBackupManifestV2Entry> {
        val array = root["entries"] as? JsonArray
            ?: throw IllegalArgumentException("Invalid backup entries")
        require(array.size <= limits.maxEntries) { "Too many backup entries" }
        return array.map { raw ->
            val obj = raw as? JsonObject ?: throw IllegalArgumentException("Invalid backup entry")
            require(obj.keys == ENTRY_KEYS) { "Invalid backup entry fields" }
            val path = string(obj, "path")
                ?: throw IllegalArgumentException("Invalid backup entry path")
            val kind = FullArchiveEntryKind.entries.firstOrNull { it.name == string(obj, "kind") }
                ?: throw IllegalArgumentException("Unknown backup entry kind")
            BackupManifestValidator.validatePath(path, kind)
            val contentClass = AppBackupContentClass.entries.firstOrNull {
                it.name == string(obj, "content_class")
            } ?: throw IllegalArgumentException("Unknown backup content class")
            val bytes = nonNegative(obj, "bytes")
            require(bytes <= limits.maxEntryBytes) { "Backup entry exceeds size budget" }
            AppBackupManifestV2Entry(
                path = path,
                kind = kind,
                bytes = bytes,
                sha256 = string(obj, "sha256")
                    ?: throw IllegalArgumentException("Invalid entry digest"),
                contentClass = contentClass,
                consistentCut = boolean(obj, "consistent_cut")
                    ?: throw IllegalArgumentException("Invalid consistent cut flag"),
            )
        }
    }

    private fun string(root: JsonObject, key: String): String? =
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun nullableString(root: JsonObject, key: String): String? = when (val value = root[key]) {
        JsonNull -> null
        is JsonPrimitive -> {
            require(value.isString) { "Invalid backup field" }
            value.content.also {
                require(it.length in 1..200 && it.none(Char::isISOControl)) {
                    "Invalid backup field"
                }
            }
        }
        else -> throw IllegalArgumentException("Invalid backup field")
    }

    private fun integer(root: JsonObject, key: String): Long? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun nonNegative(root: JsonObject, key: String): Long {
        val value = integer(root, key) ?: throw IllegalArgumentException("Invalid backup number")
        require(value >= 0) { "Invalid backup number" }
        return value
    }

    private fun boundedInt(root: JsonObject, key: String, range: IntRange): Int {
        val value = integer(root, key) ?: throw IllegalArgumentException("Invalid backup number")
        require(value in range.first.toLong()..range.last.toLong()) {
            "Invalid backup number"
        }
        return value.toInt()
    }

    private fun boolean(root: JsonObject, key: String): Boolean? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
}
