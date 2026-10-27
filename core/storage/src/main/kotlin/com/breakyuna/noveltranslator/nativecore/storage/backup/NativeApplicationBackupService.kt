package com.breakyuna.noveltranslator.nativecore.storage.backup

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable entry locations shared by the app data port and the backup service. */
object AppBackupPaths {
    const val MODEL_SETTINGS = "app/model-settings.json"
    const val PRICE_TABLE = "app/price-table.json"
    const val PROMPT_TEMPLATES = "app/prompt-templates.json"
    const val RULE_PRESET_PREFIX = "app/rule-presets/"
    const val AGENT_CHAT_PREFIX = "app/agent-chats/"
    const val APP_PREFERENCES = "app/preferences.json"
    const val NETWORK_SETTINGS = "app/network-settings.json"
    const val REQUEST_LIMITS = "app/request-limits.json"
    const val LABORATORY_SETTINGS = "app/laboratory-settings.json"

    fun rulePreset(fileName: String): String = RULE_PRESET_PREFIX + fileName
    fun agentChat(relativeName: String): String = AGENT_CHAT_PREFIX + relativeName
}

enum class AppBackupEntryKind(val pathPrefix: String, val exactPath: String?) {
    MODEL_SETTINGS(AppBackupPaths.MODEL_SETTINGS, AppBackupPaths.MODEL_SETTINGS),
    PRICE_TABLE(AppBackupPaths.PRICE_TABLE, AppBackupPaths.PRICE_TABLE),
    PROMPT_TEMPLATES(AppBackupPaths.PROMPT_TEMPLATES, AppBackupPaths.PROMPT_TEMPLATES),
    RULE_PRESET(AppBackupPaths.RULE_PRESET_PREFIX, null),
    AGENT_CHAT_HISTORY(AppBackupPaths.AGENT_CHAT_PREFIX, null),
    APP_PREFERENCES(AppBackupPaths.APP_PREFERENCES, AppBackupPaths.APP_PREFERENCES),
    NETWORK_SETTINGS(AppBackupPaths.NETWORK_SETTINGS, AppBackupPaths.NETWORK_SETTINGS),
    REQUEST_LIMITS(AppBackupPaths.REQUEST_LIMITS, AppBackupPaths.REQUEST_LIMITS),
    LABORATORY_SETTINGS(AppBackupPaths.LABORATORY_SETTINGS, AppBackupPaths.LABORATORY_SETTINGS),
}

/**
 * One non-secret application entry supplied by [AppBackupDataPort]. The writer is invoked at
 * most once per export; it must emit deterministic bytes for the advertised path.
 */
class AppBackupEntry private constructor(
    val path: String,
    val kind: AppBackupEntryKind,
    private val writer: suspend (OutputStream) -> Unit,
) {
    internal suspend fun writeTo(output: OutputStream) = writer(output)

    override fun toString() = "AppBackupEntry(path=$path, kind=$kind)"

    companion object {
        fun bytes(path: String, kind: AppBackupEntryKind, content: ByteArray): AppBackupEntry {
            val copy = content.copyOf()
            return AppBackupEntry(path, kind) { output -> output.write(copy) }
        }

        fun stream(
            path: String,
            kind: AppBackupEntryKind,
            writer: suspend (OutputStream) -> Unit,
        ): AppBackupEntry = AppBackupEntry(path, kind, writer)
    }
}

/**
 * A verified restore entry that exists only in the service's staging directory. [open] may be
 * called during [AppBackupDataPort.beginRestore]; the port must copy or decode what it needs
 * before that call returns. The file is deleted after the restore session ends.
 */
class AppBackupStagedEntry internal constructor(
    val path: String,
    val kind: AppBackupEntryKind,
    private val file: File,
    val bytes: Long,
    val sha256: String,
) {
    fun open(): InputStream = FileInputStream(file)

    override fun toString() = "AppBackupStagedEntry(path=$path, kind=$kind, bytes=$bytes)"
}

/**
 * Result of [AppBackupDataPort.beginRestore]. Implementations must keep the live application
 * state untouched during staging and only apply it in [publish]. Publishing is atomic for the
 * port's own ownership boundary; it must never replace a live project instance, import an
 * execution approval/lease, or turn a credential reference into a usable credential.
 */
interface AppBackupRestoreSession {
    suspend fun publish()

    /** Best-effort cleanup after a failed or cancelled restore. */
    suspend fun discard() {}
}

/**
 * Application data boundary for full-application backup. The app adapts its native model
 * settings store, price table, rule preset library, prompt template library and (optionally)
 * Agent chat history here. The service never opens Room, a project database or a credential
 * store itself.
 *
 * Security contract:
 * - [exportEntries] returns only non-secret data. Model settings must omit API keys, OAuth
 *   access/refresh tokens and device-bound secrets; credential references are not exportable.
 * - The returned entries must not contain prompts, response bodies, source text, approvals,
 *   leases or execution authorization.
 * - [beginRestore] validates and stages every entry without touching live state. It must throw
 *   rather than partially publish when an entry is invalid, and it must reject an entry kind it
 *   cannot restore instead of silently dropping it. [AppBackupRestoreSession.publish] is the only
 *   mutation point and must not target a live project instance.
 */
interface AppBackupDataPort {
    suspend fun exportEntries(): List<AppBackupEntry>

    suspend fun beginRestore(entries: List<AppBackupStagedEntry>): AppBackupRestoreSession
}

class AppBackupLimits(
    val maxEntries: Int = 512,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 256L * 1024 * 1024,
    val maxManifestBytes: Int = 1024 * 1024,
) {
    init {
        require(maxEntries in 0..4_096)
        require(maxEntryBytes >= 1L && maxEntryBytes <= 1024L * 1024 * 1024)
        require(maxTotalBytes >= 1L && maxTotalBytes <= 4L * 1024 * 1024 * 1024)
        require(maxManifestBytes in 1_024..(16 * 1024 * 1024))
    }
}

class ApplicationBackupSummary(
    val entryCount: Int,
    val totalBytes: Long,
    val createdAtMillis: Long,
    val schemaVersion: Int,
    val backupScope: AppBackupScope = AppBackupScope.SETTINGS,
    val snapshotId: String? = null,
) {
    init {
        require(entryCount >= 0 && totalBytes >= 0 && createdAtMillis >= 0 && schemaVersion >= 1)
        require(snapshotId == null ||
            (snapshotId.length in 1..128 && snapshotId.none(Char::isISOControl))) {
            "Invalid backup snapshot id"
        }
    }

    override fun toString() =
        "ApplicationBackupSummary(entries=$entryCount, bytes=$totalBytes, schema=$schemaVersion, " +
            "scope=$backupScope)"
}

class ApplicationBackupRestoreSummary(
    val entryCount: Int,
    val totalBytes: Long,
    val schemaVersion: Int = 1,
    val backupScope: AppBackupScope = AppBackupScope.SETTINGS,
) {
    init { require(entryCount >= 0 && totalBytes >= 0 && schemaVersion >= 1) }
    override fun toString() =
        "ApplicationBackupRestoreSummary(entries=$entryCount, bytes=$totalBytes, schema=$schemaVersion, " +
            "scope=$backupScope)"
}

/**
 * Versioned full-application backup for non-secret app data. Entries are streamed through
 * [AppBackupDataPort], measured and hashed into a temporary directory, and packed as
 * `manifest.json` plus `data/<entry path>`. Restore extracts to a private staging directory,
 * verifies schema/paths/sizes/SHA-256/budgets, then asks the port to stage and publish.
 *
 * Schema 1 remains the settings-only format produced by [export]. Schema 2 can describe either
 * a settings scope or a full archive supplied by [FullArchiveDataPort]. A full archive includes
 * project snapshots, Agent history, audit-only approvals, queue history and optional diagnostics,
 * but never credentials or executable authorization.
 */
class NativeApplicationBackupService(
    private val port: AppBackupDataPort,
    private val temporaryDirectory: File,
    private val limits: AppBackupLimits = AppBackupLimits(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val fullArchivePort: FullArchiveDataPort? = null,
) {
    suspend fun export(destination: () -> OutputStream): ApplicationBackupSummary = withContext(Dispatchers.IO) {
        val supplied = port.exportEntries()
        require(supplied.size <= limits.maxEntries) { "Too many backup entries" }
        val paths = HashSet<String>()
        supplied.forEach { entry ->
            validatePath(entry.path, entry.kind)
            require(paths.add(entry.path)) { "Duplicate backup entry" }
        }
        val directory = directory()
        val temporary = ArrayList<File>()
        val entries = ArrayList<ManifestEntry>()
        try {
            var total = 0L
            supplied.sortedBy { it.path }.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val remaining = limits.maxTotalBytes - total
                require(remaining > 0) { "Backup exceeds size budget" }
                val limit = minOf(limits.maxEntryBytes, remaining)
                val file = File.createTempFile("lnt-app-backup-", ".bin", directory)
                temporary.add(file)
                val bounded = LimitedOutputStream(FileOutputStream(file), limit)
                try {
                    entry.writeTo(bounded)
                } finally {
                    runCatching { bounded.close() }
                }
                require(bounded.written <= limit && file.length() == bounded.written) {
                    "Backup entry exceeds size budget"
                }
                if (entry.kind == AppBackupEntryKind.MODEL_SETTINGS) validateModelSettings(file)
                total = Math.addExact(total, file.length())
                require(total <= limits.maxTotalBytes)
                entries.add(ManifestEntry(entry.path, entry.kind, file.length(), sha256(file)))
            }
            val manifest = BackupManifest(entries, total, clock().coerceAtLeast(0))
            val target = destination()
            try {
                ZipOutputStream(target.buffered()).use { zip ->
                    zip.setLevel(6)
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(BackupManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    entries.forEachIndexed { index, entry ->
                        currentCoroutineContext().ensureActive()
                        zip.putNextEntry(ZipEntry(DATA_PREFIX + entry.path))
                        FileInputStream(temporary[index]).use { input ->
                            input.copyTo(zip, 64 * 1024)
                        }
                        zip.closeEntry()
                    }
                }
            } finally {
                runCatching { target.close() }
            }
            ApplicationBackupSummary(entries.size, total, manifest.createdAtMillis, SCHEMA_VERSION)
        } finally {
            temporary.forEach { file -> file.delete() }
        }
    }

    suspend fun exportFullArchive(
        options: FullArchiveExportOptions = FullArchiveExportOptions(),
        destination: () -> OutputStream,
    ): ApplicationBackupSummary = withContext(Dispatchers.IO) {
        val archivePort = requireNotNull(fullArchivePort) {
            "Full archive data port is not configured"
        }
        val exported = archivePort.exportArchive(options)
        val cut = exported.cut
        require(cut.quiesced) { "Full archive cut is not quiesced" }
        require(cut.activeRun == null) { "Full archive cut has an active run" }
        require(!cut.queueRunning) { "Full archive cut has a running queue" }
        require(cut.diagnosticsIncluded == options.includeDiagnostics) {
            "Diagnostics inclusion does not match the requested cut"
        }
        val supplied = ArrayList<FullArchiveEntry>(exported.entries.size + 2)
        supplied.addAll(exported.entries)
        supplied.addAll(exported.usageHistory.entries())
        require(supplied.size <= limits.maxEntries) { "Too many backup entries" }
        val paths = HashSet<String>()
        supplied.forEach { entry ->
            validatePath(entry.path, entry.kind)
            require(paths.add(entry.path)) { "Duplicate backup entry" }
            require(entry.consistentCut) { "Backup entry is not from the consistent cut" }
            if (!options.includeDiagnostics) {
                require(entry.kind != FullArchiveEntryKind.DIAGNOSTICS) {
                    "Diagnostics require an explicit opt-in"
                }
            }
        }
        require(cut.projectInstances.size <= limits.maxEntries) { "Too many project instances" }
        val directory = directory()
        val temporary = ArrayList<File>()
        val staged = ArrayList<StagedFullArchiveEntry>()
        try {
            var total = 0L
            supplied.sortedBy { it.path }.forEach { entry ->
                currentCoroutineContext().ensureActive()
                val remaining = limits.maxTotalBytes - total
                require(remaining > 0) { "Backup exceeds size budget" }
                val limit = minOf(limits.maxEntryBytes, remaining)
                val file = File.createTempFile("lnt-full-backup-", ".bin", directory)
                temporary.add(file)
                val bounded = LimitedOutputStream(FileOutputStream(file), limit)
                try {
                    entry.writeTo(bounded)
                } finally {
                    runCatching { bounded.close() }
                }
                require(bounded.written <= limit && file.length() == bounded.written) {
                    "Backup entry exceeds size budget"
                }
                if (entry.kind.isModelSettings) validateModelSettings(file)
                total = Math.addExact(total, file.length())
                require(total <= limits.maxTotalBytes)
                staged.add(StagedFullArchiveEntry(entry, file, file.length(), sha256(file)))
            }
            val snapshotDigests = staged
                .filter { it.entry.kind == FullArchiveEntryKind.PROJECT_SNAPSHOT }
                .map { it.sha256 }
            require(snapshotDigests.size == cut.projectInstances.size) {
                "Project instance list does not match project snapshots"
            }
            require(cut.projectInstances.all { it.sha256 in snapshotDigests }) {
                "Project snapshot digest is missing from the archive"
            }
            val manifestEntries = staged.map { value ->
                AppBackupManifestV2Entry(
                    path = value.entry.path,
                    kind = value.entry.kind,
                    bytes = value.bytes,
                    sha256 = value.sha256,
                )
            }
            val manifest = AppBackupManifestV2(
                scope = AppBackupScope.FULL_ARCHIVE,
                appVersion = cut.appVersion,
                appSchemaVersion = cut.appSchemaVersion,
                queueSchemaVersion = cut.queueSchemaVersion,
                snapshotId = cut.snapshotId,
                cutAtMillis = cut.cutAtMillis,
                createdAtMillis = clock().coerceAtLeast(0),
                quiesced = true,
                activeRun = null,
                queueRunning = false,
                totalBytes = total,
                projectInstances = cut.projectInstances,
                entries = manifestEntries,
            )
            val target = destination()
            try {
                ZipOutputStream(target.buffered()).use { zip ->
                    zip.setLevel(6)
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(AppBackupManifestV2Codec.encode(manifest).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    staged.forEach { value ->
                        currentCoroutineContext().ensureActive()
                        zip.putNextEntry(ZipEntry(DATA_PREFIX + value.entry.path))
                        FileInputStream(value.file).use { input ->
                            input.copyTo(zip, 64 * 1024)
                        }
                        zip.closeEntry()
                    }
                }
            } finally {
                runCatching { target.close() }
            }
            ApplicationBackupSummary(
                entryCount = manifest.entries.size,
                totalBytes = manifest.totalBytes,
                createdAtMillis = manifest.createdAtMillis,
                schemaVersion = AppBackupManifestV2Codec.SCHEMA_VERSION.toInt(),
                backupScope = AppBackupScope.FULL_ARCHIVE,
                snapshotId = manifest.snapshotId,
            )
        } finally {
            temporary.forEach { file -> file.delete() }
        }
    }

    /**
     * Rolls back additions from a restore whose process died after writing PREPARED. Callers may
     * also invoke this at application startup before offering a new restore.
     */
    suspend fun recoverPendingRestore(): Boolean = withContext(Dispatchers.IO) {
        val archivePort = fullArchivePort ?: return@withContext false
        recoverPendingRestore(archivePort)
    }

    suspend fun restore(source: () -> InputStream): ApplicationBackupRestoreSummary =
        withContext(Dispatchers.IO) {
            val directory = directory()
            val staging = File.createTempFile("lnt-app-restore-", ".tmp", directory).apply {
                require(delete() && mkdirs()) { "Unable to create restore staging directory" }
            }
            try {
                val manifestFile = File(staging, MANIFEST_ENTRY)
                val dataFiles = LinkedHashMap<String, File>()
                var manifestSeen = false
                var total = 0L
                ZipInputStream(source().buffered()).use { zip ->
                    while (true) {
                        val zipEntry = zip.nextEntry ?: break
                        currentCoroutineContext().ensureActive()
                        require(!zipEntry.isDirectory && '\\' !in zipEntry.name) { "Invalid backup entry" }
                        when {
                            zipEntry.name == MANIFEST_ENTRY -> {
                                require(!manifestSeen) { "Duplicate backup manifest" }
                                manifestSeen = true
                                copyBounded(zip, manifestFile, limits.maxManifestBytes.toLong())
                            }
                            zipEntry.name.startsWith(DATA_PREFIX) -> {
                                val path = zipEntry.name.removePrefix(DATA_PREFIX)
                                validatePath(path, null as AppBackupEntryKind?)
                                require(path !in dataFiles) { "Duplicate backup entry" }
                                require(dataFiles.size < limits.maxEntries) { "Too many backup entries" }
                                val remaining = limits.maxTotalBytes - total
                                require(remaining > 0) { "Backup exceeds size budget" }
                                val limit = minOf(limits.maxEntryBytes, remaining)
                                val file = File.createTempFile("lnt-app-restore-entry-", ".bin", staging)
                                val copied = copyBounded(zip, file, limit)
                                total = Math.addExact(total, copied)
                                require(total <= limits.maxTotalBytes) { "Backup exceeds size budget" }
                                dataFiles[path] = file
                            }
                            else -> throw IllegalArgumentException("Unexpected backup entry")
                        }
                        zip.closeEntry()
                    }
                }
                require(manifestSeen) { "Missing backup manifest" }
                val manifestText = manifestFile.readTextBounded(limits.maxManifestBytes)
                when (readManifestSchemaVersion(manifestText)) {
                    1L -> restoreSettingsV1(manifestText, dataFiles, total)
                    2L -> restoreVersion2(manifestText, dataFiles, total)
                    else -> throw IllegalArgumentException("Unsupported application backup version")
                }
            } finally {
                staging.deleteRecursively()
            }
        }

    private suspend fun restoreSettingsV1(
        manifestText: String,
        dataFiles: Map<String, File>,
        archiveBytes: Long,
    ): ApplicationBackupRestoreSummary {
        val manifest = BackupManifestCodec.decode(manifestText, limits)
        require(manifest.entries.size == dataFiles.size) { "Manifest does not match archive" }
        require(manifest.totalBytes == archiveBytes) { "Manifest total does not match archive" }
        val staged = manifest.entries.map { entry ->
            val file = dataFiles[entry.path] ?: throw IllegalArgumentException("Missing backup entry")
            require(file.length() == entry.bytes) { "Backup entry size mismatch" }
            require(sha256(file) == entry.sha256) { "Backup entry digest mismatch" }
            if (entry.kind == AppBackupEntryKind.MODEL_SETTINGS) validateModelSettings(file)
            AppBackupStagedEntry(entry.path, entry.kind, file, entry.bytes, entry.sha256)
        }
        currentCoroutineContext().ensureActive()
        publishAppRestore(staged)
        return ApplicationBackupRestoreSummary(staged.size, manifest.totalBytes)
    }

    private suspend fun restoreVersion2(
        manifestText: String,
        dataFiles: Map<String, File>,
        archiveBytes: Long,
    ): ApplicationBackupRestoreSummary {
        val manifest = AppBackupManifestV2Codec.decode(manifestText, limits)
        require(manifest.entries.size == dataFiles.size) { "Manifest does not match archive" }
        require(manifest.totalBytes == archiveBytes) { "Manifest total does not match archive" }
        require(manifest.quiesced) { "Backup was not taken at a quiesced cut" }
        require(manifest.activeRun == null) { "Backup was taken during an active run" }
        require(!manifest.queueRunning) { "Backup was taken while the queue was running" }
        require(manifest.entries.all { it.consistentCut }) {
            "Backup contains an entry outside its consistent cut"
        }
        val staged = manifest.entries.map { entry ->
            val file = dataFiles[entry.path] ?: throw IllegalArgumentException("Missing backup entry")
            require(file.length() == entry.bytes) { "Backup entry size mismatch" }
            require(sha256(file) == entry.sha256) { "Backup entry digest mismatch" }
            if (entry.kind.isModelSettings) validateModelSettings(file)
            FullArchiveStagedEntry(
                path = entry.path,
                kind = entry.kind,
                file = file,
                bytes = entry.bytes,
                sha256 = entry.sha256,
                consistentCut = entry.consistentCut,
            )
        }
        if (manifest.scope == AppBackupScope.SETTINGS) {
            require(manifest.projectInstances.isEmpty()) { "Settings backup contains project instances" }
            val legacy = staged.map { entry ->
                val kind = entry.kind.settingsKind
                    ?: throw IllegalArgumentException("Unsupported settings backup entry")
                AppBackupStagedEntry(entry.path, kind, dataFile(dataFiles, entry.path),
                    entry.bytes, entry.sha256)
            }
            currentCoroutineContext().ensureActive()
            publishAppRestore(legacy)
            return ApplicationBackupRestoreSummary(
                entryCount = staged.size,
                totalBytes = manifest.totalBytes,
                schemaVersion = AppBackupManifestV2Codec.SCHEMA_VERSION.toInt(),
                backupScope = AppBackupScope.SETTINGS,
            )
        }
        val archivePort = requireNotNull(fullArchivePort) {
            "Full archive data port is not configured"
        }
        val snapshotDigests = staged
            .filter { it.kind == FullArchiveEntryKind.PROJECT_SNAPSHOT }
            .map { it.sha256 }
        require(snapshotDigests.size == manifest.projectInstances.size) {
            "Project instance list does not match project snapshots"
        }
        require(snapshotDigests.distinct().size == snapshotDigests.size) {
            "Duplicate project snapshot entry"
        }
        require(manifest.projectInstances.all { it.sha256 in snapshotDigests }) {
            "Project snapshot digest is missing from the archive"
        }
        currentCoroutineContext().ensureActive()
        recoverPendingRestore(archivePort)
        val state = archivePort.restoreState()
        require(state.idle) { "Application data is not idle; restore was not started" }
        val plan = FullArchiveRestorePlan(
            snapshotId = manifest.snapshotId,
            cutAtMillis = manifest.cutAtMillis,
            projectInstances = manifest.projectInstances,
        )
        val journal = archivePort.restoreJournal
        val preparedAt = clock().coerceAtLeast(0)
        val session = archivePort.beginRestore(
            plan,
            java.util.Collections.unmodifiableList(ArrayList(staged)),
        )
        val prepared = AppBackupRestoreReceipt(
            snapshotId = manifest.snapshotId,
            state = AppBackupRestoreReceiptState.PREPARED,
            preparedAtMillis = preparedAt,
            updatedAtMillis = preparedAt,
            cutAtMillis = manifest.cutAtMillis,
            paths = staged.map { it.path },
            projectInstances = manifest.projectInstances,
        )
        try {
            journal.write(prepared)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    session.discard()
                } catch (_: Exception) {
                }
            }
            throw failure
        }
        try {
            session.publish()
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try {
                    session.discard()
                } catch (_: Exception) {
                }
                try {
                    journal.write(prepared.transition(
                        AppBackupRestoreReceiptState.FAILED,
                        clock().coerceAtLeast(0),
                        failure.javaClass.simpleName.take(200).ifBlank { "RESTORE_FAILED" },
                    ))
                } catch (journalFailure: Throwable) {
                    failure.addSuppressed(journalFailure)
                }
            }
            throw failure
        }
        withContext(NonCancellable) {
            journal.write(prepared.transition(
                AppBackupRestoreReceiptState.COMMITTED,
                clock().coerceAtLeast(0),
            ))
        }
        return ApplicationBackupRestoreSummary(
            entryCount = staged.size,
            totalBytes = manifest.totalBytes,
            schemaVersion = AppBackupManifestV2Codec.SCHEMA_VERSION.toInt(),
            backupScope = AppBackupScope.FULL_ARCHIVE,
        )
    }

    private suspend fun publishAppRestore(staged: List<AppBackupStagedEntry>) {
        var published = false
        var session: AppBackupRestoreSession? = null
        try {
            val started = port.beginRestore(java.util.Collections.unmodifiableList(ArrayList(staged)))
            session = started
            started.publish()
            published = true
        } finally {
            if (!published) {
                val pending = session
                if (pending != null) {
                    withContext(NonCancellable) {
                        try {
                            pending.discard()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    private suspend fun recoverPendingRestore(archivePort: FullArchiveDataPort): Boolean {
        val journal = archivePort.restoreJournal
        val receipt = journal.read() ?: return false
        if (receipt.state != AppBackupRestoreReceiptState.PREPARED) return false
        archivePort.recoverPendingRestore(receipt)
        journal.write(receipt.transition(
            AppBackupRestoreReceiptState.FAILED,
            clock().coerceAtLeast(0),
            "INTERRUPTED",
        ))
        return true
    }

    private fun dataFile(dataFiles: Map<String, File>, path: String): File =
        dataFiles[path] ?: throw IllegalArgumentException("Missing backup entry")

    private fun readManifestSchemaVersion(text: String): Long {
        val root = try {
            JsonObjectValue.parse(text, JsonInputLimits(limits.maxManifestBytes, 8)).json
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid application backup manifest")
        }
        val value = root["schema_version"] as? JsonPrimitive
            ?: throw IllegalArgumentException("Invalid application backup manifest")
        require(!value.isString) { "Invalid application backup manifest" }
        return value.content.toLongOrNull()
            ?: throw IllegalArgumentException("Invalid application backup manifest")
    }

    private fun validateModelSettings(file: File) {
        require(file.length() <= MAX_MODEL_SETTINGS_BYTES) { "Model settings backup entry is too large" }
        val text = file.readBytes().toString(Charsets.UTF_8).lowercase(Locale.ROOT)
        require(MODEL_SECRET_MARKERS.none { marker -> marker in text }) {
            "Model settings backup entry contains credentials or OAuth tokens"
        }
    }

    private fun validatePath(path: String, expected: AppBackupEntryKind?) =
        BackupManifestValidator.validatePath(path, expected)

    private fun validatePath(path: String, expected: FullArchiveEntryKind) =
        BackupManifestValidator.validatePath(path, expected)

    private suspend fun copyBounded(input: InputStream, target: File, limit: Long): Long {
        var total = 0L
        FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count.toLong())
                require(total <= limit) { "Backup entry exceeds size budget" }
                output.write(buffer, 0, count)
            }
        }
        return total
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextBounded(limit: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        FileInputStream(this).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size().toLong() + count <= limit) { "Backup manifest is too large" }
                bytes.write(buffer, 0, count)
            }
        }
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
    }

    private fun directory(): File {
        check(temporaryDirectory.isDirectory || temporaryDirectory.mkdirs()) {
            "Unable to create backup temporary directory"
        }
        return temporaryDirectory
    }

    private class LimitedOutputStream(
        private val delegate: OutputStream,
        private val limit: Long,
    ) : OutputStream() {
        var written = 0L
            private set

        override fun write(value: Int) {
            require(written + 1L <= limit) { "Backup entry exceeds size budget" }
            delegate.write(value)
            written++
        }

        override fun write(buffer: ByteArray, offset: Int, count: Int) {
            require(count >= 0 && written + count.toLong() <= limit) { "Backup entry exceeds size budget" }
            delegate.write(buffer, offset, count)
            written += count.toLong()
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATA_PREFIX = "data/"
        const val SCHEMA_VERSION = 1
        const val MAX_MODEL_SETTINGS_BYTES = 1024L * 1024
        /**
         * Conservative textual guard for the model-settings entry only. The port is still the
         * authority that omits credentials; this catches an accidental key/token field before
         * it reaches a portable archive.
         */
        val MODEL_SECRET_MARKERS = listOf(
            "api_key", "apikey", "api-key", "access_token", "access-token", "accesstoken",
            "refresh_token", "refresh-token", "refreshtoken", "client_secret", "client-secret",
            "clientsecret", "oauth_token", "oauth-token", "oauthtoken", "private_key",
            "private-key", "privatekey", "password", "authorization", "bearer ", "sk-", "aiza",
            "keystore", "key_state", "key-state", "keychain", "model_key", "model-key",
        )
    }
}

private class ManifestEntry(
    val path: String,
    val kind: AppBackupEntryKind,
    val bytes: Long,
    val sha256: String,
)

private class StagedFullArchiveEntry(
    val entry: FullArchiveEntry,
    val file: File,
    val bytes: Long,
    val sha256: String,
)

private class BackupManifest(
    val entries: List<ManifestEntry>,
    val totalBytes: Long,
    val createdAtMillis: Long,
)

private object BackupManifestCodec {
    private const val FORMAT = "lnt-application-backup"
    private const val VERSION = 1L
    private val MANIFEST_KEYS = setOf(
        "format", "schema_version", "created_at", "credentials_excluded",
        "execution_authorization_included", "project_instances_included", "total_bytes", "entries",
    )
    private val ENTRY_KEYS = setOf("path", "kind", "bytes", "sha256")

    fun encode(manifest: BackupManifest): String = buildJsonObject {
        put("format", FORMAT)
        put("schema_version", VERSION)
        put("created_at", manifest.createdAtMillis)
        put("credentials_excluded", true)
        put("execution_authorization_included", false)
        put("project_instances_included", false)
        put("total_bytes", manifest.totalBytes)
        put("entries", JsonArray(manifest.entries.map { entry ->
            buildJsonObject {
                put("path", entry.path)
                put("kind", entry.kind.name)
                put("bytes", entry.bytes)
                put("sha256", entry.sha256)
            }
        }))
    }.toString()

    fun decode(text: String, limits: AppBackupLimits): BackupManifest {
        require(text.length <= limits.maxManifestBytes) { "Backup manifest is too large" }
        val root = try {
            JsonObjectValue.parse(text, JsonInputLimits(limits.maxManifestBytes, 6)).json
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid application backup manifest")
        }
        require(root.keys == MANIFEST_KEYS) { "Invalid application backup manifest" }
        require(string(root, "format") == FORMAT) { "Unsupported application backup format" }
        require(integer(root, "schema_version") == VERSION) { "Unsupported application backup version" }
        require(boolean(root, "credentials_excluded") == true) { "Backup does not exclude credentials" }
        require(boolean(root, "execution_authorization_included") == false) {
            "Backup contains execution authorization"
        }
        require(boolean(root, "project_instances_included") == false) {
            "Backup contains live project instances"
        }
        val createdAt = integer(root, "created_at") ?: throw IllegalArgumentException("Invalid backup time")
        require(createdAt >= 0)
        val totalBytes = integer(root, "total_bytes") ?: throw IllegalArgumentException("Invalid backup size")
        require(totalBytes >= 0L && totalBytes <= limits.maxTotalBytes) { "Backup exceeds size budget" }
        val array = root["entries"] as? JsonArray ?: throw IllegalArgumentException("Invalid backup entries")
        require(array.size <= limits.maxEntries) { "Too many backup entries" }
        val paths = HashSet<String>()
        val entries = array.map { raw ->
            val entry = raw as? JsonObject ?: throw IllegalArgumentException("Invalid backup entry")
            require(entry.keys == ENTRY_KEYS) { "Invalid backup entry" }
            val path = string(entry, "path") ?: throw IllegalArgumentException("Invalid backup entry path")
            val kind = AppBackupEntryKind.entries.firstOrNull { it.name == string(entry, "kind") }
                ?: throw IllegalArgumentException("Unknown backup entry kind")
            BackupManifestValidator.validatePath(path, kind)
            require(paths.add(path)) { "Duplicate backup entry" }
            val bytes = integer(entry, "bytes") ?: throw IllegalArgumentException("Invalid entry size")
            require(bytes >= 0L && bytes <= limits.maxEntryBytes) { "Backup entry exceeds size budget" }
            val digest = string(entry, "sha256") ?: throw IllegalArgumentException("Invalid entry digest")
            require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid entry digest"
            }
            ManifestEntry(path, kind, bytes, digest)
        }
        val sum = entries.fold(0L) { total, entry -> Math.addExact(total, entry.bytes) }
        require(sum == totalBytes) { "Backup manifest total does not match entries" }
        return BackupManifest(entries, totalBytes, createdAt)
    }

    private fun string(root: JsonObject, key: String): String? =
        (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun integer(root: JsonObject, key: String): Long? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    private fun boolean(root: JsonObject, key: String): Boolean? =
        (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let { value ->
            when (value) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
}

/**
 * Single path authority for export, archive extraction and manifest decoding. Keeping it in
 * one object prevents the manifest validator from accepting a path the extractor would later
 * write outside staging.
 */
internal object BackupManifestValidator {
    private const val MAX_PATH_CHARS = 240
    private val FORBIDDEN_MARKERS = listOf(
        "credential", "secret", "token", "oauth", "apikey", "api-key", "api_key",
        "password", "authorization", "private-key", "private_key",
        "keystore", "key-state", "key_state", "keychain",
    )

    fun validatePath(path: String, kind: AppBackupEntryKind?) =
        validatePath(path, kind?.exactPath, kind?.pathPrefix)

    fun validatePath(path: String, kind: FullArchiveEntryKind) =
        validatePath(path, kind.exactPath, kind.pathPrefix)

    private fun validatePath(path: String, exact: String?, prefix: String?) {
        require(path.length in 1..MAX_PATH_CHARS) { "Invalid backup entry path" }
        require(path.none(Char::isISOControl) && '\\' !in path && !path.startsWith("/")) {
            "Invalid backup entry path"
        }
        val segments = path.split('/')
        require(segments.all { segment ->
            segment.length in 1..100 && segment != "." && segment != ".." &&
                segment.first().isLetterOrDigit() &&
                segment.all { it.isLetterOrDigit() || it in "._-" }
        }) { "Invalid backup entry path" }
        require(segments.none { segment ->
            val lower = segment.lowercase(Locale.ROOT)
            FORBIDDEN_MARKERS.any { marker -> marker in lower }
        }) { "Credentials and execution authorization are not backup entries" }
        if (exact != null) {
            require(path == exact) { "Backup entry path does not match its kind" }
        } else if (prefix != null) {
            require(path.startsWith(prefix) && path.length > prefix.length) {
                "Backup entry path does not match its kind"
            }
        }
    }
}
