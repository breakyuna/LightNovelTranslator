package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.AgentChatLine
import com.breakyuna.noveltranslator.nativecore.domain.AgentMessage
import com.breakyuna.noveltranslator.nativecore.domain.AgentMessageCodec
import com.breakyuna.noveltranslator.nativecore.domain.AgentApprovalResult
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LaboratorySettingsCodec
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsCodec
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimitsCodec
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.PriceTableCodec
import com.breakyuna.noveltranslator.nativecore.domain.PromptTemplateCodec
import com.breakyuna.noveltranslator.nativecore.domain.PromptTemplateSet
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RegisteredProject
import com.breakyuna.noveltranslator.nativecore.domain.RulePreset
import com.breakyuna.noveltranslator.nativecore.domain.RulePresetCodec
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeSnapshot
import com.breakyuna.noveltranslator.nativecore.domain.StoredRun
import com.breakyuna.noveltranslator.nativecore.domain.StoredRunState
import com.breakyuna.noveltranslator.nativecore.domain.TextApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectException
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
import com.breakyuna.noveltranslator.nativecore.domain.TranslationQueueStorePort
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerCursor
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerQuery
import com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerReadPort
import com.breakyuna.noveltranslator.nativecore.storage.NativeLaboratorySettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelNetworkSettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelSettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePriceTableStore
import com.breakyuna.noveltranslator.nativecore.storage.NativePromptTemplateStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeRequestLimitsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeRulePresetStore
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppArchiveRestorePolicy
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupPaths
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupProjectInstance
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupRestoreReceipt
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupRestoreReceiptState
import com.breakyuna.noveltranslator.nativecore.storage.backup.AppBackupRestoreSession
import com.breakyuna.noveltranslator.nativecore.storage.backup.ArchiveUsageHistory
import com.breakyuna.noveltranslator.nativecore.storage.backup.ArchiveUsageReceipt
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveCut
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveDataPort
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveEntry
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveEntryKind
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveExport
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveExportOptions
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchivePaths
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveRestoreJournal
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveRestorePlan
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveRestoreState
import com.breakyuna.noveltranslator.nativecore.storage.backup.FullArchiveStagedEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** The project operations needed by a full archive; kept small so ports are testable. */
internal interface FullArchiveProjectSource {
    fun beginCut(): AutoCloseable = AutoCloseable {}
    suspend fun registered(): List<RegisteredProject>
    suspend fun save(ref: LocalProjectRef, destination: () -> OutputStream)
    suspend fun importProject(commandId: CommandId, source: () -> InputStream): TextProjectSummary
}

/** User-visible Agent material only. Checkpoints are deliberately not part of this boundary. */
internal interface FullArchiveAgentSource {
    suspend fun chats(ref: LocalProjectRef): List<ChatId>
    suspend fun messages(ref: LocalProjectRef, chat: ChatId): List<AgentChatLine>
    suspend fun history(ref: LocalProjectRef, chat: ChatId): List<AgentMessage>
    suspend fun approvals(ref: LocalProjectRef, chat: ChatId): List<AgentApprovalResult>
}

/** Body-free proposal/approval projection; no rewrite is attempted on restore. */
internal class FullArchiveProposalAudit(
    val kind: String,
    val id: String,
    val chat: String,
    val run: String,
    val command: String,
    val revision: Long,
    val state: String,
    val count: Int = 0,
)

internal interface FullArchiveProposalSource {
    suspend fun recent(ref: LocalProjectRef, chat: ChatId): List<FullArchiveProposalAudit>
}

internal interface FullArchiveRunSource {
    suspend fun recent(ref: LocalProjectRef, limit: Int = 100): List<StoredRun>
}

internal interface FullArchiveDiagnosticsSource {
    suspend fun export(output: OutputStream): Long
}

/**
 * Settings are written from the existing settings-backup entries (same paths and codecs). The
 * full-archive port owns a separate adapter only because a different Gradle module cannot invoke
 * the internal writer of AppBackupEntry directly.
 */
internal interface FullArchiveSettingsPort {
    suspend fun recover() {}
    suspend fun committed() {}
    suspend fun exportEntries(): List<FullArchiveEntry>
    suspend fun beginRestore(entries: List<FullArchiveStagedEntry>): AppBackupRestoreSession
}

internal data class FullArchiveImportedProject(
    val oldRef: LocalProjectRef,
    val commandId: String,
    val newRef: LocalProjectRef? = null,
)

internal interface FullArchiveProjectRollback {
    suspend fun rollback(imports: List<FullArchiveImportedProject>)
}

/**
 * Removes only projects created by this restore attempt. A project row is deleted only when its
 * creation command is the exact random command of this attempt, so a stale ref can never remove
 * an older user project. Published-but-not-indexed directories are found through their private
 * registration command as well.
 */
internal class NativeFullArchiveProjectRollback(
    private val context: Context,
) : FullArchiveProjectRollback {
    override suspend fun rollback(imports: List<FullArchiveImportedProject>) = withContext(Dispatchers.IO) {
        if (imports.isEmpty()) return@withContext
        val commands = imports.mapTo(HashSet()) { it.commandId }
        val instanceIds = LinkedHashSet<String>()
        val database = context.getDatabasePath(DATABASE_NAME)
        if (database.isFile) {
            val db = SQLiteDatabase.openDatabase(database.path, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                db.beginTransaction()
                try {
                    for (item in imports) {
                        val id = item.newRef?.instanceId?.value ?: findInstanceByCommand(db, item.commandId)
                            ?: continue
                        val command = findCreationCommand(db, id) ?: continue
                        if (command != item.commandId) continue
                        db.delete("project_instances", "instanceId = ?", arrayOf(id))
                        instanceIds.add(id)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.close()
            }
        }
        val root = File(context.filesDir, PROJECT_DIRECTORY)
        if (root.isDirectory) {
            root.listFiles()?.forEach { directory ->
                val command = readRegistrationCommand(File(directory, REGISTRATION_FILE))
                if (command != null && command in commands) {
                    val normalized = runCatching { UUID.fromString(directory.name).toString() }.getOrNull()
                    if (normalized == directory.name) instanceIds.add(directory.name)
                }
            }
        }
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@withContext
        for (id in instanceIds) {
            val directory = File(canonicalRoot, id)
            if (runCatching { directory.canonicalFile.parentFile }.getOrNull() != canonicalRoot) continue
            runCatching { directory.deleteRecursively() }
        }
    }

    private fun findInstanceByCommand(db: SQLiteDatabase, command: String): String? =
        db.rawQuery(
            "SELECT instanceId FROM project_instances WHERE creationCommand = ? LIMIT 1",
            arrayOf(command),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun findCreationCommand(db: SQLiteDatabase, instanceId: String): String? =
        db.rawQuery(
            "SELECT creationCommand FROM project_instances WHERE instanceId = ? LIMIT 1",
            arrayOf(instanceId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun readRegistrationCommand(file: File): String? {
        if (!file.isFile || file.length() !in 1..MAX_REGISTRATION_BYTES) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            root["command"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private companion object {
        const val DATABASE_NAME = "lnt_native_app.db"
        const val PROJECT_DIRECTORY = "native_projects"
        const val REGISTRATION_FILE = "registration.json"
        const val MAX_REGISTRATION_BYTES = 16_384L
    }
}

/**
 * App-side full-archive adapter. The core service remains the path/digest/manifest authority and
 * the only component that opens the ZIP; this class provides the non-secret store projections and
 * owns the durable receipt journal plus read-only restore history.
 *
 * Export excludes credentials, key state, OAuth/MCP tokens, checkpoint bodies and execution
 * authorization. Restore imports project snapshots as new local instances, applies non-secret
 * settings, and copies all Agent/approval/queue/run material into an app-private read-only audit
 * directory. It never inserts pending approvals, runnable queue tasks or execution tokens.
 */
internal class NativeFullArchiveDataPort(
    private val projects: FullArchiveProjectSource,
    private val settings: FullArchiveSettingsPort,
    private val agent: FullArchiveAgentSource,
    private val proposals: FullArchiveProposalSource,
    private val runs: FullArchiveRunSource,
    private val queues: TranslationQueueStorePort,
    private val usage: UsageLedgerReadPort?,
    private val diagnostics: FullArchiveDiagnosticsSource?,
    private val runtimeSnapshot: () -> RuntimeSnapshot,
    private val projectRollback: FullArchiveProjectRollback,
    private val appVersion: String,
    private val journalFile: File,
    private val historyRoot: File,
    private val exportDirectory: File = File(historyRoot.parentFile ?: historyRoot, "native_full_archive_export"),
    private val clock: () -> Long = { System.currentTimeMillis().coerceAtLeast(0) },
    private val appSchemaVersion: Int = 19,
    private val queueSchemaVersion: Int = 2,
    private val historicalPrices: com.breakyuna.noveltranslator.nativecore.domain.PriceTableVersionSource? = null,
) : FullArchiveDataPort, FullArchiveRestoreJournal {
    private val commandLock = Any()
    private var pendingImportCommand: String? = null

    /** Binds the fixed SAF command to this restore attempt; the next beginRestore consumes it. */
    fun useImportCommand(commandId: CommandId) {
        synchronized(commandLock) { pendingImportCommand = commandId.value }
    }

    override suspend fun exportArchive(options: FullArchiveExportOptions): FullArchiveExport =
        withContext(Dispatchers.IO) {
            val cut = projects.beginCut()
            try {
                val runtime = runtimeSnapshot()
                require(runtime.activeRun == null && !runtime.projectWriteRunning) {
                    "Full archive export requires an idle runtime"
                }
                require(!runtime.authenticationWriteRunning && !runtime.skillWriteRunning) {
                    "Full archive export requires an idle runtime"
                }
                val registered = projects.registered()
                if (registered.size > MAX_PROJECTS) {
                    throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
                }
                val readable = registered.map { project ->
                    project.summary ?: throw TextProjectException(TextProjectException.Reason.STORAGE_IO)
                }
                val queueSnapshots = queues.list()
                require(queueSnapshots.none { stored ->
                    stored.snapshot.entries.any { it.task.state == TranslationTaskQueueState.RUNNING }
                }) { "Full archive export requires a stopped translation queue" }
                if (options.includeDiagnostics && diagnostics == null) {
                    throw IllegalArgumentException("Diagnostics were requested but are unavailable")
                }

                val entries = ArrayList<FullArchiveEntry>()
                val instances = ArrayList<AppBackupProjectInstance>(readable.size)
                resetExportDirectory(exportDirectory)
                readable.forEachIndexed { index, project ->
                    val name = index.toString().padStart(4, '0') + ".lnt"
                    val file = File(exportDirectory, name)
                    FileOutputStream(file).use { output ->
                        projects.save(project.ref) { output }
                    }
                    require(file.length() in 0..MAX_PROJECT_BYTES)
                    val digest = sha256(file)
                    instances += AppBackupProjectInstance(project.ref, project.name, project.revision, digest)
                    entries += FullArchiveEntry.stream(
                        FullArchivePaths.projectSnapshot(name),
                        FullArchiveEntryKind.PROJECT_SNAPSHOT,
                    ) { output ->
                        try {
                            FileInputStream(file).use { input -> input.copyTo(output, COPY_BUFFER) }
                        } finally {
                            file.delete()
                        }
                    }
                }
                entries += settings.exportEntries()
                entries += agentEntries(readable)
                entries += approvalAudit(readable)
                entries += queueHistory(queueSnapshots)
                entries += runHistory(readable)
                usage?.let { ledger ->
                    val file = File(exportDirectory, "receipts.jsonl")
                    ledger.export(UsageLedgerQuery()) { FileOutputStream(file) }
                    entries += frozenEntry(file, FullArchivePaths.usageHistory("receipts.jsonl"), FullArchiveEntryKind.USAGE_HISTORY)
                }
                if (options.includeDiagnostics) {
                    val file = File(exportDirectory, "diagnostics.jsonl")
                    FileOutputStream(file).use { requireNotNull(diagnostics).export(it) }
                    entries += frozenEntry(file, FullArchivePaths.diagnostics("diagnostics.jsonl"), FullArchiveEntryKind.DIAGNOSTICS)
                }
                FullArchiveExport(
                    cut = FullArchiveCut(
                        appVersion = appVersion,
                        appSchemaVersion = appSchemaVersion,
                        queueSchemaVersion = queueSchemaVersion,
                        cutAtMillis = clock(),
                        quiesced = true,
                        activeRun = null,
                        queueRunning = false,
                        projectInstances = instances,
                        diagnosticsIncluded = options.includeDiagnostics,
                    ),
                    entries = entries,
                    usageHistory = usageHistory(),
                )
            } finally { cut.close() }
        }

    override suspend fun restoreState(): FullArchiveRestoreState = withContext(Dispatchers.IO) {
        val runtime = runtimeSnapshot()
        val registered = projects.registered()
        if (registered.any { !it.readable }) {
            return@withContext FullArchiveRestoreState(
                idle = false,
                activeRun = runtime.activeRun?.identity?.runId?.value,
                queueRunning = false,
                runningRuns = 0,
                waitingApprovalRuns = 0,
                pendingApprovals = 0,
                queuedTasks = 0,
                executionTokens = runtime.activeRun?.outstandingOperations ?: 0,
            )
        }
        var runningRuns = 0
        var waitingApprovalRuns = 0
        var pendingApprovals = 0
        registered.forEach { project ->
            val ref = project.ref
            runs.recent(ref, APPROVAL_SCAN_LIMIT).forEach { run ->
                when (run.state) {
                    StoredRunState.RUNNING -> runningRuns += 1
                    StoredRunState.WAITING_APPROVAL -> waitingApprovalRuns += 1
                    else -> Unit
                }
            }
            agent.chats(ref).forEach { chat ->
                pendingApprovals += agent.approvals(ref, chat).count { it.state == TextApprovalState.PENDING }
                pendingApprovals += proposals.recent(ref, chat).count { it.state == "PENDING" }
            }
        }
        val storedQueues = queues.list()
        val queueRunning = storedQueues.any { stored ->
            stored.snapshot.entries.any { it.task.state == TranslationTaskQueueState.RUNNING }
        }
        val queuedTasks = storedQueues.sumOf { stored ->
            stored.snapshot.entries.count { it.task.state == TranslationTaskQueueState.QUEUED }
        }
        FullArchiveRestoreState(
            idle = runtime.activeRun == null && !runtime.projectWriteRunning &&
                !runtime.authenticationWriteRunning && !runtime.skillWriteRunning &&
                !queueRunning && runningRuns == 0 && waitingApprovalRuns == 0 &&
                pendingApprovals == 0,
            activeRun = runtime.activeRun?.identity?.runId?.value,
            queueRunning = queueRunning,
            runningRuns = runningRuns,
            waitingApprovalRuns = waitingApprovalRuns,
            pendingApprovals = pendingApprovals,
            queuedTasks = queuedTasks,
            executionTokens = runtime.activeRun?.outstandingOperations ?: 0,
        )
    }

    override val restoreJournal: FullArchiveRestoreJournal get() = this

    override suspend fun beginRestore(
        plan: FullArchiveRestorePlan,
        entries: List<FullArchiveStagedEntry>,
    ): AppBackupRestoreSession = withContext(Dispatchers.IO) {
        val projectEntries = entries.filter { it.kind == FullArchiveEntryKind.PROJECT_SNAPSHOT }
        val byDigest = projectEntries.groupBy { it.sha256 }
        plan.projectInstances.forEach { instance ->
            require(byDigest[instance.sha256]?.size == 1) {
                "Project instance does not map to exactly one snapshot"
            }
        }
        require(projectEntries.size == plan.projectInstances.size) {
            "Project snapshots do not match the restore plan"
        }
        val settingsEntries = entries.filter { it.kind.settingsKind != null }
        val historyEntries = entries.filter {
            it.kind != FullArchiveEntryKind.PROJECT_SNAPSHOT && it.kind.settingsKind == null
        }
        if (read()?.state == AppBackupRestoreReceiptState.COMMITTED) settings.committed()
        val settingsSession = settings.beginRestore(settingsEntries)
        val attempt = nextAttemptId()
        val mappingFile = mappingFile(plan.snapshotId)
        val directory = File(historyRoot, "restore-$attempt")
        val imports = ArrayList<FullArchiveImportedProject>(plan.projectInstances.size)
        val mapping = RestoreMapping(plan.snapshotId, attempt, directory.name, imports.toList(), "PREPARED")
        writeMapping(mappingFile, mapping)
        RestoreSession(
            plan = plan,
            projectEntries = byDigest.mapValues { it.value.single() },
            historyEntries = historyEntries,
            settingsSession = settingsSession,
            attempt = attempt,
            imports = imports,
            mappingFile = mappingFile,
            historyDirectory = directory,
        )
    }

    override suspend fun recoverPendingRestore(receipt: AppBackupRestoreReceipt) = withContext(Dispatchers.IO) {
        val file = mappingFile(receipt.snapshotId)
        val mapping = readMapping(file) ?: return@withContext
        if (mapping.snapshotId != receipt.snapshotId) return@withContext
        settings.recover()
        projectRollback.rollback(mapping.imports)
        deleteHistoryDirectory(mapping.historyDirectory)
        file.delete()
        Unit
    }

    override suspend fun read(): AppBackupRestoreReceipt? = withContext(Dispatchers.IO) {
        if (!journalFile.isFile) return@withContext null
        val text = journalFile.readText(Charsets.UTF_8)
        val root = try {
            Json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid full archive restore receipt")
        }
        require(root["format"]?.jsonPrimitive?.content == RECEIPT_FORMAT) {
            "Unsupported full archive restore receipt"
        }
        require(root["version"]?.jsonPrimitive?.long == 1L) {
            "Unsupported full archive restore receipt"
        }
        val snapshotId = root["snapshot_id"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Invalid restore receipt snapshot")
        val state = AppBackupRestoreReceiptState.entries.firstOrNull {
            it.name == root["state"]?.jsonPrimitive?.content
        } ?: throw IllegalArgumentException("Invalid restore receipt state")
        val preparedAt = root["prepared_at"]?.jsonPrimitive?.long
            ?: throw IllegalArgumentException("Invalid restore receipt time")
        val updatedAt = root["updated_at"]?.jsonPrimitive?.long
            ?: throw IllegalArgumentException("Invalid restore receipt time")
        val cutAt = root["cut_at"]?.jsonPrimitive?.long
            ?: throw IllegalArgumentException("Invalid restore receipt time")
        val paths = (root["paths"] as? JsonArray)?.map { value ->
            value.jsonPrimitive.content
        } ?: throw IllegalArgumentException("Invalid restore receipt paths")
        val projects = (root["project_instances"] as? JsonArray)?.map { value ->
            val obj = value.jsonObject
            val ref = obj["old_ref"]?.jsonObject
                ?: throw IllegalArgumentException("Invalid restore receipt project")
            AppBackupProjectInstance(
                oldRef = LocalProjectRef(
                    ProjectId(ref.getValue("project").jsonPrimitive.content),
                    ProjectInstanceId(ref.getValue("instance").jsonPrimitive.content),
                    RecoveryGeneration(ref.getValue("generation").jsonPrimitive.long),
                ),
                name = obj.getValue("name").jsonPrimitive.content,
                revision = obj.getValue("revision").jsonPrimitive.long,
                sha256 = obj.getValue("sha256").jsonPrimitive.content,
            )
        } ?: throw IllegalArgumentException("Invalid restore receipt projects")
        val failure = root["failure"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        AppBackupRestoreReceipt(
            snapshotId = snapshotId,
            state = state,
            preparedAtMillis = preparedAt,
            updatedAtMillis = updatedAt,
            cutAtMillis = cutAt,
            paths = paths,
            projectInstances = projects,
            failure = failure,
        )
    }

    override suspend fun write(receipt: AppBackupRestoreReceipt): Unit = withContext(Dispatchers.IO) {
        journalFile.parentFile?.mkdirs()
        val root = buildJsonObject {
            put("format", RECEIPT_FORMAT)
            put("version", 1)
            put("snapshot_id", receipt.snapshotId)
            put("state", receipt.state.name)
            put("prepared_at", receipt.preparedAtMillis)
            put("updated_at", receipt.updatedAtMillis)
            put("cut_at", receipt.cutAtMillis)
            put("paths", JsonArray(receipt.paths.map(::JsonPrimitive)))
            put("project_instances", JsonArray(receipt.projectInstances.map { instance ->
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
            put("failure", receipt.failure?.let(::JsonPrimitive) ?: JsonNull)
        }
        if (receipt.state == AppBackupRestoreReceiptState.FAILED) settings.recover()
        atomicWrite(journalFile, root.toString().toByteArray(Charsets.UTF_8))
        val mapping = mappingFile(receipt.snapshotId)
        if (receipt.state == AppBackupRestoreReceiptState.COMMITTED) {
            settings.committed()
            mapping.delete()
        } else if (receipt.state == AppBackupRestoreReceiptState.FAILED) {
            readMapping(mapping)?.let { failed ->
                runCatching { deleteHistoryDirectory(failed.historyDirectory) }
            }
            mapping.delete()
        }
        Unit
    }

    private fun deleteHistoryDirectory(name: String) {
        val root = runCatching { historyRoot.canonicalFile }.getOrNull() ?: return
        val directory = runCatching { File(root, name).canonicalFile }.getOrNull() ?: return
        if (directory.parentFile != root) return
        directory.deleteRecursively()
    }

    private suspend fun agentEntries(projects: List<TextProjectSummary>): List<FullArchiveEntry> {
        val result = ArrayList<FullArchiveEntry>()
        projects.forEachIndexed { index, project ->
            val chats = agent.chats(project.ref)
            if (chats.isEmpty()) return@forEachIndexed
            val json = buildJsonObject {
                put("format", AGENT_FORMAT)
                put("version", 1)
                putProjectScope(this, project.ref)
                put("sessions", JsonArray(chats.map { chat ->
                    val lines = agent.messages(project.ref, chat)
                    val typed = try {
                        agent.history(project.ref, chat)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    buildJsonObject {
                        put("chat", chat.value)
                        put("messages", JsonArray(lines.map { line ->
                            buildJsonObject {
                                put("id", line.id)
                                put("role", line.role)
                                put("text", line.text)
                            }
                        }))
                        if (typed != null) {
                            put("history", JsonArray(typed.map { message ->
                                Json.parseToJsonElement(AgentMessageCodec.encode(message))
                            }))
                        } else {
                            put("history_status", "PREVIEW_ONLY")
                        }
                    }
                }))
                put("branch_chats_included", true)
                // Branch creation copies a visible history prefix into another chat; every chat is
                // exported, so branch-visible conversations are covered without checkpoint bodies.
            }
            result += FullArchiveEntry.bytes(
                FullArchivePaths.agentSession("project-" + index.toString().padStart(4, '0') + ".json"),
                FullArchiveEntryKind.AGENT_SESSION,
                TextProjectFingerprint.utf8(json.toString()),
            )
        }
        return result
    }

    private suspend fun approvalAudit(projects: List<TextProjectSummary>): List<FullArchiveEntry> {
        val json = buildJsonObject {
            put("format", APPROVAL_FORMAT)
            put("version", 1)
            put("mode", "HISTORICAL_ONLY")
            put("execution_authorization", false)
            put("pending_approvals", false)
            put("approvals_historical", AppArchiveRestorePolicy.APPROVALS_ARE_HISTORICAL)
            put("execution_tokens", AppArchiveRestorePolicy.restoredExecutionTokens())
            put("projects", JsonArray(projects.map { project ->
                val chats = agent.chats(project.ref)
                buildJsonObject {
                    putProjectScope(this, project.ref)
                    put("chats", JsonArray(chats.map { chat ->
                        buildJsonObject {
                            put("chat", chat.value)
                            put("approvals", JsonArray(agent.approvals(project.ref, chat).map { approval ->
                                buildJsonObject {
                                    put("id", approval.id)
                                    put("row", approval.row)
                                    put("state", historicalApprovalState(approval.state))
                                    put("original_state", approval.state.name)
                                    put("executable", false)
                                }
                            }))
                            put("proposals", JsonArray(proposals.recent(project.ref, chat).map { proposal ->
                                buildJsonObject {
                                    put("kind", proposal.kind)
                                    put("id", proposal.id)
                                    put("run", proposal.run)
                                    put("command", proposal.command)
                                    put("revision", proposal.revision)
                                    put("count", proposal.count)
                                    put("state", historicalProposalState(proposal.state))
                                    put("original_state", proposal.state)
                                    put("executable", false)
                                }
                            }))
                        }
                    }))
                }
            }))
        }
        return listOf(
            FullArchiveEntry.bytes(
                FullArchivePaths.approvalAudit("audit.json"),
                FullArchiveEntryKind.APPROVAL_AUDIT,
                TextProjectFingerprint.utf8(json.toString()),
            ),
        )
    }

    private fun queueHistory(stored: List<com.breakyuna.noveltranslator.nativecore.domain.StoredTranslationQueue>): List<FullArchiveEntry> {
        val json = buildJsonObject {
            put("format", QUEUE_FORMAT)
            put("version", 1)
            put("mode", "HISTORICAL_ONLY")
            put("execution_authorization", false)
            put("runnable", false)
            put("queues", JsonArray(stored.map { queue ->
                val snapshot = queue.snapshot
                buildJsonObject {
                    putProjectScope(this, snapshot.ref)
                    put("revision", snapshot.revision)
                    put("updated_at", snapshot.updatedAtMillis)
                    put("tasks", JsonArray(snapshot.entries.map { entry ->
                        buildJsonObject {
                            put("sequence", entry.task.sequence)
                            put("state", AppArchiveRestorePolicy.queueState(entry.task.state).name)
                            put("original_state", entry.task.state.name)
                            put("restored_state", AppArchiveRestorePolicy.queueState(entry.task.state).name)
                            put("run", entry.spec.run.runId.value)
                            put("command", entry.spec.run.commandId.value)
                            put("configuration", entry.spec.configuration.id)
                            put("configuration_revision", entry.spec.configuration.revision)
                            put("revision", entry.spec.revision)
                            put("execution_revision", entry.task.executionRevision?.let(::JsonPrimitive) ?: JsonNull)
                            put("enqueued_at", entry.meta.enqueuedAtMillis)
                            put("updated_at", entry.meta.updatedAtMillis)
                            put("executable", false)
                        }
                    }))
                }
            }))
        }
        return listOf(
            FullArchiveEntry.bytes(
                FullArchivePaths.queueHistory("queues.json"),
                FullArchiveEntryKind.QUEUE_HISTORY,
                TextProjectFingerprint.utf8(json.toString()),
            ),
        )
    }

    private suspend fun runHistory(projects: List<TextProjectSummary>): List<FullArchiveEntry> {
        val json = buildJsonObject {
            put("format", RUN_FORMAT)
            put("version", 1)
            put("mode", "HISTORY_ONLY")
            put("execution_authorization", false)
            put("runnable", false)
            put("runs", JsonArray(projects.flatMap { project ->
                runs.recent(project.ref, RUN_SCAN_LIMIT).map { run ->
                    buildJsonObject {
                        putProjectScope(this, project.ref)
                        put("execution", run.handle.executionId)
                        put("run", run.handle.run.runId.value)
                        put("command", run.handle.run.commandId.value)
                        put("owner", run.owner.name)
                        put("state", AppArchiveRestorePolicy.runState(run.state).name)
                        put("original_state", run.state.name)
                        put("restored_state", AppArchiveRestorePolicy.runState(run.state).name)
                        put("needs_review", run.needsReview)
                        put("stage", run.checkpoint.stage.name)
                        put("revision", run.checkpoint.revision?.let(::JsonPrimitive) ?: JsonNull)
                        put("committed_items", run.checkpoint.committedItems)
                        put("requests", run.checkpoint.requests)
                        put("executable", false)
                    }
                }
            }))
        }
        return listOf(
            FullArchiveEntry.bytes(
                FullArchivePaths.usageHistory("runs.json"),
                FullArchiveEntryKind.USAGE_HISTORY,
                TextProjectFingerprint.utf8(json.toString()),
            ),
        )
    }

    private suspend fun usageHistory(): ArchiveUsageHistory {
        val ledger = usage ?: return ArchiveUsageHistory.EMPTY
        val receipts = ArrayList<ArchiveUsageReceipt>()
        val tables = LinkedHashMap<Long, com.breakyuna.noveltranslator.nativecore.domain.PriceTable>()
        val seenIds = HashSet<String>()
        var cursor: UsageLedgerCursor? = null
        var lastCursor: UsageLedgerCursor? = null
        do {
            val page = ledger.entries(UsageLedgerQuery(), cursor)
            page.entries.forEach { entry ->
                if (seenIds.add(entry.id)) {
                    val version = entry.priceVersion
                    val table = if (version == null) null else tables[version] ?: try {
                        historicalPrices?.loadPriceTable(version)?.also { tables[version] = it }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                    val receiptUsage = entry.usage
                    val receiptFingerprint = entry.priceFingerprint
                    val quote = if (table != null && receiptUsage != null &&
                        (receiptFingerprint == null || receiptFingerprint.sha256 == table.fingerprint) &&
                        (entry.priceCurrency == null || entry.priceCurrency == table.currency)) {
                        com.breakyuna.noveltranslator.nativecore.engine.UsageCostCalculator(table)
                            .quote(entry.pricedModelKey ?: entry.model, receiptUsage)
                    } else null
                    val cost = (quote as? com.breakyuna.noveltranslator.nativecore.domain.CostQuote.Priced)
                        ?.actual?.takeIf { it.isComplete }?.knownTotal?.amount?.stripTrailingZeros()?.toPlainString()
                    receipts += ArchiveUsageReceipt(entry.id, version ?: 0, entry.priceFingerprint?.sha256, cost)
                }
            }
            require(receipts.size <= MAX_USAGE_RECEIPTS) { "Usage history exceeds the archive budget" }
            lastCursor = cursor
            cursor = page.next
            require(cursor == null || cursor != lastCursor) { "Usage ledger cursor did not advance" }
        } while (cursor != null)
        return ArchiveUsageHistory(priceTables = tables.values.toList(), receipts = receipts)
    }

    private fun frozenEntry(file: File, path: String, kind: FullArchiveEntryKind): FullArchiveEntry =
        FullArchiveEntry.stream(path, kind) { output ->
            try { FileInputStream(file).use { it.copyTo(output, COPY_BUFFER) } }
            finally { file.delete() }
        }

    private fun resetExportDirectory(directory: File) {
        if (directory.exists()) require(directory.deleteRecursively()) {
            "Unable to reset full archive export cache"
        }
        if (!directory.isDirectory) require(directory.mkdirs()) {
            "Unable to create full archive export cache"
        }
    }

    private fun nextAttemptId(): String {
        val fixed = synchronized(commandLock) {
            pendingImportCommand.also { pendingImportCommand = null }
        }
        val base = fixed?.takeIf { it.isNotBlank() }?.take(96) ?: "local"
        return (base + "-" + UUID.randomUUID().toString())
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
            .joinToString("")
    }

    private fun mappingFile(snapshotId: String): File =
        File(journalFile.parentFile ?: File("."), "restore-" + sha256(snapshotId.toByteArray(Charsets.UTF_8)) + ".json")

    private fun writeMapping(file: File, mapping: RestoreMapping) {
        val json = buildJsonObject {
            put("format", MAPPING_FORMAT)
            put("version", 1)
            put("snapshot_id", mapping.snapshotId)
            put("attempt", mapping.attempt)
            put("history_directory", mapping.historyDirectory)
            put("state", mapping.state)
            put("imports", JsonArray(mapping.imports.map { imported ->
                buildJsonObject {
                    put("old_ref", buildJsonObject {
                        put("project", imported.oldRef.projectId.value)
                        put("instance", imported.oldRef.instanceId.value)
                        put("generation", imported.oldRef.generation.value)
                    })
                    put("command", imported.commandId)
                    imported.newRef?.let { ref ->
                        put("new_ref", buildJsonObject {
                            put("project", ref.projectId.value)
                            put("instance", ref.instanceId.value)
                            put("generation", ref.generation.value)
                        })
                    }
                }
            }))
        }
        atomicWrite(file, json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readMapping(file: File): RestoreMapping? {
        if (!file.isFile) return null
        val root = runCatching {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return null
        if (root["format"]?.jsonPrimitive?.content != MAPPING_FORMAT) return null
        val snapshotId = root["snapshot_id"]?.jsonPrimitive?.content ?: return null
        val attempt = root["attempt"]?.jsonPrimitive?.content ?: return null
        val historyDirectory = root["history_directory"]?.jsonPrimitive?.content ?: return null
        val state = root["state"]?.jsonPrimitive?.content ?: "PREPARED"
        val imports = (root["imports"] as? JsonArray)?.mapNotNull { value ->
            runCatching {
                val item = value.jsonObject
                fun ref(name: String, value: JsonObject): LocalProjectRef {
                    val ref = value.getValue(name).jsonObject
                    return LocalProjectRef(
                        ProjectId(ref.getValue("project").jsonPrimitive.content),
                        ProjectInstanceId(ref.getValue("instance").jsonPrimitive.content),
                        RecoveryGeneration(ref.getValue("generation").jsonPrimitive.long),
                    )
                }
                FullArchiveImportedProject(
                    oldRef = ref("old_ref", item),
                    commandId = item.getValue("command").jsonPrimitive.content,
                    newRef = (item["new_ref"] as? JsonObject)?.let {
                        runCatching { ref("new_ref", item) }.getOrNull()
                    },
                )
            }.getOrNull()
        } ?: emptyList()
        return RestoreMapping(snapshotId, attempt, historyDirectory, imports, state)
    }

    private inner class RestoreSession(
        private val plan: FullArchiveRestorePlan,
        private val projectEntries: Map<String, FullArchiveStagedEntry>,
        private val historyEntries: List<FullArchiveStagedEntry>,
        private val settingsSession: AppBackupRestoreSession,
        private val attempt: String,
        private val imports: MutableList<FullArchiveImportedProject>,
        private val mappingFile: File,
        private val historyDirectory: File,
    ) : AppBackupRestoreSession {
        private var closed = false

        override suspend fun publish() = withContext(Dispatchers.IO) {
            if (closed) return@withContext
            try {
                plan.projectInstances.forEachIndexed { index, instance ->
                    val entry = requireNotNull(projectEntries[instance.sha256])
                    val commandId = "full-archive-$attempt-$index"
                    imports += FullArchiveImportedProject(instance.oldRef, commandId)
                    persist("PREPARED")
                    val summary = entry.open().use { input ->
                        projects.importProject(CommandId(commandId)) { input }
                    }
                    require(summary.ref.instanceId.value != instance.oldRef.instanceId.value) {
                        "Project import did not create a new local instance"
                    }
                    imports[index] = imports[index].copy(newRef = summary.ref)
                    persist("PUBLISHED")
                }
                writeHistory()
                settingsSession.publish()
                persist("PUBLISHED")
            } catch (failure: Throwable) {
                throw failure
            }
        }

        override suspend fun discard() = withContext(Dispatchers.IO) {
            if (closed) return@withContext
            settingsSession.discard()
            closed = true
            runCatching { projectRollback.rollback(imports.toList()) }
            runCatching { historyDirectory.deleteRecursively() }
            runCatching { mappingFile.delete() }
            Unit
        }

        private fun persist(state: String) {
            writeMapping(mappingFile, RestoreMapping(plan.snapshotId, attempt, historyDirectory.name, imports.toList(), state))
        }

        private fun writeHistory() {
            if (!historyDirectory.isDirectory) require(historyDirectory.mkdirs()) {
                "Unable to create restore history"
            }
            val canonicalRoot = historyDirectory.canonicalFile
            historyEntries.forEach { entry ->
                val target = File(canonicalRoot, entry.path)
                val canonical = target.canonicalFile
                require(canonical.path.startsWith(canonicalRoot.path + File.separator)) {
                    "Restore history path escapes its directory"
                }
                canonical.parentFile?.mkdirs()
                entry.open().use { input ->
                    FileOutputStream(canonical).use { output -> input.copyTo(output, COPY_BUFFER) }
                }
            }
            val manifest = buildJsonObject {
                put("format", HISTORY_FORMAT)
                put("version", 1)
                put("snapshot_id", plan.snapshotId)
                put("cut_at", plan.cutAtMillis)
                put("mode", "READ_ONLY")
                put("execution_authorization", false)
                put("pending_approvals", false)
                put("runnable_queue", false)
                put("execution_tokens", AppArchiveRestorePolicy.restoredExecutionTokens())
                put("projects", JsonArray(imports.map { imported ->
                    buildJsonObject {
                        put("old", buildJsonObject {
                            put("project", imported.oldRef.projectId.value)
                            put("instance", imported.oldRef.instanceId.value)
                            put("generation", imported.oldRef.generation.value)
                        })
                        imported.newRef?.let { ref ->
                            put("new", buildJsonObject {
                                put("project", ref.projectId.value)
                                put("instance", ref.instanceId.value)
                                put("generation", ref.generation.value)
                            })
                        }
                        put("command", imported.commandId)
                    }
                }))
                put("entries", JsonArray(historyEntries.map { JsonPrimitive(it.path) }))
            }
            atomicWrite(File(canonicalRoot, "manifest.json"), manifest.toString().toByteArray(Charsets.UTF_8))
        }
    }

    private data class RestoreMapping(
        val snapshotId: String,
        val attempt: String,
        val historyDirectory: String,
        val imports: List<FullArchiveImportedProject>,
        val state: String,
    )

    private companion object {
        const val MAX_PROJECTS = 100
        const val MAX_PROJECT_BYTES = 1024L * 1024 * 1024
        const val MAX_USAGE_RECEIPTS = 100_000
        const val APPROVAL_SCAN_LIMIT = 100
        const val RUN_SCAN_LIMIT = 100
        const val COPY_BUFFER = 64 * 1024
        const val RECEIPT_FORMAT = "lnt-full-archive-restore-receipt"
        const val MAPPING_FORMAT = "lnt-full-archive-restore-mapping"
        const val HISTORY_FORMAT = "lnt-full-archive-history"
        const val AGENT_FORMAT = "lnt-full-archive-agent"
        const val APPROVAL_FORMAT = "lnt-full-archive-approval-audit"
        const val QUEUE_FORMAT = "lnt-full-archive-queue-history"
        const val RUN_FORMAT = "lnt-full-archive-run-history"
    }
}

/**
 * Settings adapter used only by the full archive. Entry paths, codecs and key-exclusion rules are
 * the same as the settings backup; it is separate only because a different Gradle module cannot
 * invoke the internal writer of AppBackupEntry directly.
 */
internal class NativeFullArchiveSettingsPort(
    private val models: NativeModelSettingsStore,
    private val prices: NativePriceTableStore,
    private val templates: NativePromptTemplateStore,
    private val presets: NativeRulePresetStore,
    private val preferences: NativeAppSettingsStore? = null,
    private val network: NativeModelNetworkSettingsStore? = null,
    private val limits: NativeRequestLimitsStore? = null,
    private val laboratory: NativeLaboratorySettingsStore? = null,
    private val checkpoint: NativeArchiveSettingsCheckpoint? = null,
) : FullArchiveSettingsPort {
    override suspend fun recover() { checkpoint?.rollback() }
    override suspend fun committed() { checkpoint?.commit() }
    override suspend fun exportEntries(): List<FullArchiveEntry> {
        val result = ArrayList<FullArchiveEntry>()
        models.current()?.let { settings ->
            val json = buildJsonObject {
                put("schema", 1)
                put("endpoint", settings.endpoint)
                put("model", settings.model)
                put("protocol", settings.protocol.name)
                put("credentialsExcluded", true)
            }.toString()
            result += FullArchiveEntry.bytes(
                AppBackupPaths.MODEL_SETTINGS,
                FullArchiveEntryKind.MODEL_SETTINGS,
                TextProjectFingerprint.utf8(json),
            )
        }
        result += FullArchiveEntry.bytes(
            AppBackupPaths.PRICE_TABLE,
            FullArchiveEntryKind.PRICE_TABLE,
            TextProjectFingerprint.utf8(prices.exportJson()),
        )
        val library = templates.read()
        result += FullArchiveEntry.bytes(
            AppBackupPaths.PROMPT_TEMPLATES,
            FullArchiveEntryKind.PROMPT_TEMPLATES,
            TextProjectFingerprint.utf8(PromptTemplateCodec.encode(library.templates)),
        )
        presets.list().forEachIndexed { index, summary ->
            val preset = presets.load(summary.id)
            result += FullArchiveEntry.bytes(
                AppBackupPaths.rulePreset("preset-$index.json"),
                FullArchiveEntryKind.RULE_PRESET,
                TextProjectFingerprint.utf8(RulePresetCodec.encode(preset)),
            )
        }
        preferences?.let { store ->
            val value = store.snapshot()
            val json = buildJsonObject {
                put("schema", 1)
                put("theme", value.themeMode.name)
                put("automaticContextSummary", value.automaticContextSummary)
                put("diagnosticsIncludeDetails", value.diagnosticsIncludeDetails)
                put("language", value.language.name)
            }.toString()
            result += FullArchiveEntry.bytes(
                AppBackupPaths.APP_PREFERENCES,
                FullArchiveEntryKind.APP_PREFERENCES,
                TextProjectFingerprint.utf8(json),
            )
        }
        network?.let { store ->
            result += FullArchiveEntry.bytes(
                AppBackupPaths.NETWORK_SETTINGS,
                FullArchiveEntryKind.NETWORK_SETTINGS,
                TextProjectFingerprint.utf8(ModelNetworkSettingsCodec.encode(store.load().copy(proxyCredentialRef = null))),
            )
        }
        limits?.let { store ->
            result += FullArchiveEntry.bytes(
                AppBackupPaths.REQUEST_LIMITS,
                FullArchiveEntryKind.REQUEST_LIMITS,
                TextProjectFingerprint.utf8(ModelRequestLimitsCodec.encode(store.load())),
            )
        }
        laboratory?.let { store ->
            result += FullArchiveEntry.bytes(
                AppBackupPaths.LABORATORY_SETTINGS,
                FullArchiveEntryKind.LABORATORY_SETTINGS,
                TextProjectFingerprint.utf8(LaboratorySettingsCodec.encode(store.load())),
            )
        }
        return result
    }

    override suspend fun beginRestore(entries: List<FullArchiveStagedEntry>): AppBackupRestoreSession {
        var model: RestoredModel? = null
        var price: PriceTable? = null
        var restoredTemplates: PromptTemplateSet? = null
        var restoredPreferences: NativeAppPreferences? = null
        var restoredNetwork: com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings? = null
        var restoredLimits: com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits? = null
        var restoredLaboratory: com.breakyuna.noveltranslator.nativecore.domain.LaboratorySettings? = null
        val restoredPresets = ArrayList<RulePreset>()
        val seenPresets = HashSet<String>()
        for (entry in entries) {
            val bytes = entry.open().use { input -> readBounded(input, MAX_SETTINGS_ENTRY_BYTES) }
            val text = decodeUtf8(bytes)
            when (entry.kind) {
                FullArchiveEntryKind.MODEL_SETTINGS -> {
                    require(model == null) { "Duplicate model settings" }
                    val root = strictJson(text)
                    require(root["credentialsExcluded"]?.jsonPrimitive?.content == "true") {
                        "Backup does not exclude credentials"
                    }
                    require(root["schema"]?.jsonPrimitive?.content == "1") {
                        "Unsupported model settings schema"
                    }
                    val endpoint = root["endpoint"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing endpoint")
                    val modelName = root["model"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing model")
                    val protocol = NativeModelProtocol.entries.firstOrNull {
                        it.name == root["protocol"]?.jsonPrimitive?.content
                    } ?: throw IllegalArgumentException("Unknown model protocol")
                    model = RestoredModel(endpoint, modelName, protocol)
                }
                FullArchiveEntryKind.PRICE_TABLE -> {
                    require(price == null) { "Duplicate price table" }
                    price = PriceTableCodec.decode(text)
                }
                FullArchiveEntryKind.PROMPT_TEMPLATES -> {
                    require(restoredTemplates == null) { "Duplicate prompt templates" }
                    restoredTemplates = PromptTemplateCodec.decode(text)
                }
                FullArchiveEntryKind.RULE_PRESET -> {
                    val preset = RulePresetCodec.decode(text)
                    require(seenPresets.add(preset.id)) { "Duplicate rule preset" }
                    restoredPresets += preset
                }
                FullArchiveEntryKind.APP_PREFERENCES -> {
                    require(restoredPreferences == null) { "Duplicate preferences" }
                    require(preferences != null) { "Preferences are not available in this build" }
                    val root = strictJson(text)
                    require(root["schema"]?.jsonPrimitive?.content == "1") {
                        "Unsupported preferences schema"
                    }
                    restoredPreferences = NativeAppPreferences(
                        themeMode = NativeThemeMode.parse(root["theme"]?.jsonPrimitive?.content),
                        automaticContextSummary = root["automaticContextSummary"]?.jsonPrimitive?.content
                            ?.toBooleanStrictOrNull()
                            ?: throw IllegalArgumentException("Missing automatic context flag"),
                        diagnosticsIncludeDetails = root["diagnosticsIncludeDetails"]?.jsonPrimitive?.content
                            ?.toBooleanStrictOrNull()
                            ?: throw IllegalArgumentException("Missing diagnostics flag"),
                        language = NativeAppLanguage.parse(root["language"]?.jsonPrimitive?.content),
                        appPersona = preferences.appPersona(),
                    )
                }
                FullArchiveEntryKind.NETWORK_SETTINGS -> {
                    require(restoredNetwork == null) { "Duplicate network settings" }
                    require(network != null) { "Network settings are not available in this build" }
                    restoredNetwork = ModelNetworkSettingsCodec.decode(text).copy(proxyCredentialRef = null)
                }
                FullArchiveEntryKind.REQUEST_LIMITS -> {
                    require(restoredLimits == null) { "Duplicate request limits" }
                    require(limits != null) { "Request limits are not available in this build" }
                    restoredLimits = ModelRequestLimitsCodec.decode(text)
                }
                FullArchiveEntryKind.LABORATORY_SETTINGS -> {
                    require(restoredLaboratory == null) { "Duplicate laboratory settings" }
                    require(laboratory != null) { "Laboratory settings are not available in this build" }
                    restoredLaboratory = LaboratorySettingsCodec.decode(text)
                }
                else -> throw IllegalArgumentException("Unsupported full archive settings entry")
            }
        }
        val fixedModel = model
        val fixedPrice = price
        val fixedTemplates = restoredTemplates
        val fixedPreferences = restoredPreferences
        val fixedNetwork = restoredNetwork
        val fixedLimits = restoredLimits
        val fixedLaboratory = restoredLaboratory
        return object : AppBackupRestoreSession {
            override suspend fun discard() { checkpoint?.rollback() }
            override suspend fun publish() {
                checkpoint?.prepare()
                fixedModel?.let { models.saveProfile(it.endpoint, it.model, it.protocol, emptyList()) }
                fixedPrice?.let { prices.replace(it) }
                fixedTemplates?.let { restored ->
                    val current = this@NativeFullArchiveSettingsPort.templates.read()
                    this@NativeFullArchiveSettingsPort.templates.replace(current.fingerprint, restored)
                }
                restoredPresets.forEach { preset ->
                    val existing = runCatching { presets.load(preset.id) }.getOrNull()
                    presets.save(preset, existing?.let(RulePresetCodec::fingerprint))
                }
                fixedPreferences?.let { requireNotNull(preferences).restore(it) }
                fixedNetwork?.let {
                    requireNotNull(network).save(it, this@NativeFullArchiveSettingsPort.network?.fingerprint())
                }
                fixedLimits?.let {
                    requireNotNull(limits).save(it, this@NativeFullArchiveSettingsPort.limits?.fingerprint())
                }
                fixedLaboratory?.let {
                    requireNotNull(laboratory).save(it, this@NativeFullArchiveSettingsPort.laboratory?.fingerprint())
                }
            }
        }
    }

    private fun strictJson(text: String): JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (failure: Exception) {
        throw IllegalArgumentException("Invalid backup JSON", failure)
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (failure: Exception) {
        throw IllegalArgumentException("Backup entry is not valid UTF-8", failure)
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= limit) { "Backup entry is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class RestoredModel(
        val endpoint: String,
        val model: String,
        val protocol: NativeModelProtocol,
    )

    private companion object {
        const val MAX_SETTINGS_ENTRY_BYTES = 8 * 1024 * 1024
    }
}

/** A UI/port preflight failure with a user-visible idle message, not a generic I/O error. */
internal class FullArchiveRestoreNotIdle : IllegalStateException("Full archive restore requires idle application data")

/** A UI command is idempotent while the same saved intent is still pending. */
internal fun fullArchiveImportIntent(
    previous: List<String>?,
    commandId: String,
    idle: Boolean,
): List<String>? {
    if (!idle) return null
    if (previous == null) return listOf(commandId)
    return previous.takeIf { it.size == 1 && it.single() == commandId }
}

internal fun historicalApprovalState(state: TextApprovalState): String =
    if (state == TextApprovalState.PENDING) "HISTORICAL_PENDING" else state.name

internal fun historicalProposalState(state: String): String =
    if (state == "PENDING") "HISTORICAL_PENDING" else state

private fun putProjectScope(json: kotlinx.serialization.json.JsonObjectBuilder, ref: LocalProjectRef) {
    json.put("project", ref.projectId.value)
    json.put("instance", ref.instanceId.value)
    json.put("generation", ref.generation.value)
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try {
        output.write(bytes)
        output.fd.sync()
        atomic.finishWrite(output)
    } catch (failure: Throwable) {
        atomic.failWrite(output)
        throw failure
    }
}

/** Used by tests and diagnostics; never logs content. */
internal fun readBoundedArchiveEntry(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(output.size() + read <= limit) { "Archive entry is too large" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
