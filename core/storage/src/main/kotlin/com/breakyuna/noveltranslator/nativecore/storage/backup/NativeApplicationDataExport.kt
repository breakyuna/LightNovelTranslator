package com.breakyuna.noveltranslator.nativecore.storage.backup

import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.PriceTable
import com.breakyuna.noveltranslator.nativecore.domain.StoredRunState
import com.breakyuna.noveltranslator.nativecore.domain.TranslationTaskQueueState
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable entry locations that only exist in a full-archive application backup. */
object FullArchivePaths {
    const val PROJECT_SNAPSHOT_PREFIX = "projects/"
    const val AGENT_SESSION_PREFIX = "app/agent-sessions/"
    const val APPROVAL_AUDIT_PREFIX = "app/approval-audit/"
    const val QUEUE_HISTORY_PREFIX = "app/queue-history/"
    const val USAGE_HISTORY_PREFIX = "app/usage-history/"
    const val PRICE_HISTORY_PREFIX = "app/price-history/"
    const val DIAGNOSTICS_PREFIX = "app/diagnostics/"

    const val PRICE_TABLES = PRICE_HISTORY_PREFIX + "price-tables.json"
    const val COST_INDEX = USAGE_HISTORY_PREFIX + "cost-index.json"

    fun projectSnapshot(fileName: String): String = PROJECT_SNAPSHOT_PREFIX + fileName
    fun agentSession(relativeName: String): String = AGENT_SESSION_PREFIX + relativeName
    fun approvalAudit(relativeName: String): String = APPROVAL_AUDIT_PREFIX + relativeName
    fun queueHistory(relativeName: String): String = QUEUE_HISTORY_PREFIX + relativeName
    fun usageHistory(relativeName: String): String = USAGE_HISTORY_PREFIX + relativeName
    fun priceHistory(relativeName: String): String = PRICE_HISTORY_PREFIX + relativeName
    fun diagnostics(relativeName: String): String = DIAGNOSTICS_PREFIX + relativeName
}

/**
 * Content classes are the restore authority for a full archive. Approval and queue material is
 * always audit/history, never an executable command or a pending grant.
 */
enum class AppBackupContentClass {
    SETTINGS,
    PROJECT_SNAPSHOT,
    AGENT_SESSION,
    APPROVAL_AUDIT,
    QUEUE_HISTORY,
    USAGE_RECEIPT,
    PRICE_TABLE,
    DIAGNOSTICS,
}

/**
 * Entry kinds accepted by a schema-2 full archive. [settingsKind] maps the same path to the
 * legacy settings-only port, so a schema-2 SETTINGS archive can still be restored through
 * [AppBackupDataPort] without teaching the app port about archive-only kinds.
 */
enum class FullArchiveEntryKind(
    val pathPrefix: String,
    val exactPath: String?,
    val contentClass: AppBackupContentClass,
    val settingsKind: AppBackupEntryKind? = null,
) {
    MODEL_SETTINGS(
        AppBackupPaths.MODEL_SETTINGS,
        AppBackupPaths.MODEL_SETTINGS,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.MODEL_SETTINGS,
    ),
    PRICE_TABLE(
        AppBackupPaths.PRICE_TABLE,
        AppBackupPaths.PRICE_TABLE,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.PRICE_TABLE,
    ),
    PROMPT_TEMPLATES(
        AppBackupPaths.PROMPT_TEMPLATES,
        AppBackupPaths.PROMPT_TEMPLATES,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.PROMPT_TEMPLATES,
    ),
    RULE_PRESET(
        AppBackupPaths.RULE_PRESET_PREFIX,
        null,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.RULE_PRESET,
    ),
    APP_PREFERENCES(
        AppBackupPaths.APP_PREFERENCES,
        AppBackupPaths.APP_PREFERENCES,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.APP_PREFERENCES,
    ),
    NETWORK_SETTINGS(
        AppBackupPaths.NETWORK_SETTINGS,
        AppBackupPaths.NETWORK_SETTINGS,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.NETWORK_SETTINGS,
    ),
    REQUEST_LIMITS(
        AppBackupPaths.REQUEST_LIMITS,
        AppBackupPaths.REQUEST_LIMITS,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.REQUEST_LIMITS,
    ),
    LABORATORY_SETTINGS(
        AppBackupPaths.LABORATORY_SETTINGS,
        AppBackupPaths.LABORATORY_SETTINGS,
        AppBackupContentClass.SETTINGS,
        AppBackupEntryKind.LABORATORY_SETTINGS,
    ),
    PROJECT_SNAPSHOT(
        FullArchivePaths.PROJECT_SNAPSHOT_PREFIX,
        null,
        AppBackupContentClass.PROJECT_SNAPSHOT,
    ),
    AGENT_SESSION(
        FullArchivePaths.AGENT_SESSION_PREFIX,
        null,
        AppBackupContentClass.AGENT_SESSION,
    ),
    APPROVAL_AUDIT(
        FullArchivePaths.APPROVAL_AUDIT_PREFIX,
        null,
        AppBackupContentClass.APPROVAL_AUDIT,
    ),
    QUEUE_HISTORY(
        FullArchivePaths.QUEUE_HISTORY_PREFIX,
        null,
        AppBackupContentClass.QUEUE_HISTORY,
    ),
    USAGE_HISTORY(
        FullArchivePaths.USAGE_HISTORY_PREFIX,
        null,
        AppBackupContentClass.USAGE_RECEIPT,
    ),
    PRICE_HISTORY(
        FullArchivePaths.PRICE_HISTORY_PREFIX,
        null,
        AppBackupContentClass.PRICE_TABLE,
    ),
    DIAGNOSTICS(
        FullArchivePaths.DIAGNOSTICS_PREFIX,
        null,
        AppBackupContentClass.DIAGNOSTICS,
    );

    val isModelSettings: Boolean get() = this == MODEL_SETTINGS
}

/**
 * One full-archive entry supplied by [FullArchiveDataPort]. The writer is invoked at most once
 * and must emit the bytes of the same consistent cut advertised by [FullArchiveCut].
 */
class FullArchiveEntry private constructor(
    val path: String,
    val kind: FullArchiveEntryKind,
    val consistentCut: Boolean,
    private val writer: suspend (OutputStream) -> Unit,
) {
    val contentClass: AppBackupContentClass get() = kind.contentClass

    internal suspend fun writeTo(output: OutputStream) = writer(output)

    override fun toString() =
        "FullArchiveEntry(path=$path, kind=$kind, consistentCut=$consistentCut)"

    companion object {
        fun bytes(
            path: String,
            kind: FullArchiveEntryKind,
            content: ByteArray,
            consistentCut: Boolean = true,
        ): FullArchiveEntry {
            val copy = content.copyOf()
            return FullArchiveEntry(path, kind, consistentCut) { output -> output.write(copy) }
        }

        fun stream(
            path: String,
            kind: FullArchiveEntryKind,
            consistentCut: Boolean = true,
            writer: suspend (OutputStream) -> Unit,
        ): FullArchiveEntry = FullArchiveEntry(path, kind, consistentCut, writer)
    }
}

/**
 * A verified full-archive entry that exists only inside the service's staging directory. The
 * port must copy/decode anything it needs during `beginRestore`; the file is deleted when the
 * restore call returns. [open] does not grant any execution authority.
 */
class FullArchiveStagedEntry internal constructor(
    val path: String,
    val kind: FullArchiveEntryKind,
    private val file: File,
    val bytes: Long,
    val sha256: String,
    val consistentCut: Boolean,
) {
    val contentClass: AppBackupContentClass get() = kind.contentClass

    fun open(): InputStream = FileInputStream(file)

    override fun toString() = "FullArchiveStagedEntry(path=$path, kind=$kind, bytes=$bytes)"
}

class FullArchiveExportOptions(val includeDiagnostics: Boolean = false)

/**
 * Metadata captured while the application is paused for a full-archive cut. [quiesced] means no
 * writer can change a covered store until the export completes; [activeRun] and [queueRunning]
 * must be absent/false for a portable archive.
 */
class FullArchiveCut(
    val appVersion: String,
    val appSchemaVersion: Int,
    val queueSchemaVersion: Int,
    val cutAtMillis: Long,
    val quiesced: Boolean,
    val activeRun: String?,
    val queueRunning: Boolean,
    projectInstances: List<AppBackupProjectInstance>,
    val diagnosticsIncluded: Boolean,
    val snapshotId: String = UUID.randomUUID().toString(),
) {
    val projectInstances: List<AppBackupProjectInstance> =
        Collections.unmodifiableList(ArrayList(projectInstances))

    init {
        require(validLabel(appVersion, 100)) { "Invalid application version" }
        require(appSchemaVersion >= 1) { "Invalid application schema version" }
        require(queueSchemaVersion >= 1) { "Invalid queue schema version" }
        require(snapshotId.length in 1..128 && snapshotId.none(Char::isISOControl)) {
            "Invalid backup snapshot id"
        }
        require(cutAtMillis >= 0) { "Invalid backup cut time" }
        require(activeRun == null || validLabel(activeRun, 200)) { "Invalid active run identity" }
        require(this.projectInstances.size <= AppBackupProjectInstance.MAX_PROJECTS) {
            "Too many project instances"
        }
        require(this.projectInstances.map { it.oldRef }.distinct().size == this.projectInstances.size) {
            "Duplicate project instance identity"
        }
        require(this.projectInstances.map { it.sha256 }.distinct().size == this.projectInstances.size) {
            "Duplicate project snapshot digest"
        }
    }
}

/** Old project identity and the exact snapshot facts recorded in a full-archive manifest. */
class AppBackupProjectInstance(
    val oldRef: LocalProjectRef,
    val name: String,
    val revision: Long,
    val sha256: String,
) {
    init {
        require(validLabel(name, MAX_NAME_CHARS)) { "Invalid project name" }
        require(revision >= 0) { "Invalid project revision" }
        require(validSha256(sha256)) { "Invalid project snapshot digest" }
    }

    override fun toString() =
        "AppBackupProjectInstance(name=$name, revision=$revision, sha256=$sha256)"

    companion object {
        const val MAX_PROJECTS = 100
        const val MAX_NAME_CHARS = 200
    }
}

class FullArchiveExport(
    val cut: FullArchiveCut,
    entries: List<FullArchiveEntry>,
    val usageHistory: ArchiveUsageHistory = ArchiveUsageHistory.EMPTY,
) {
    val entries: List<FullArchiveEntry> = Collections.unmodifiableList(ArrayList(entries))

    init {
        require(this.entries.size <= 4_096) { "Too many backup entries" }
    }
}

/**
 * Port-declared restore preconditions. [idle] is the authority the service checks before any
 * live mutation; pending approvals and unfinished runs may still exist, but they must be
 * converted to history by the restore session. The init check prevents an "idle" declaration
 * from contradicting an active run or a running queue.
 */
class FullArchiveRestoreState(
    val idle: Boolean,
    val activeRun: String? = null,
    val queueRunning: Boolean = false,
    val runningRuns: Int = 0,
    val waitingApprovalRuns: Int = 0,
    val pendingApprovals: Int = 0,
    val queuedTasks: Int = 0,
    val executionTokens: Int = 0,
) {
    init {
        require(activeRun == null || validLabel(activeRun, 200)) { "Invalid active run identity" }
        require(runningRuns >= 0 && waitingApprovalRuns >= 0 && pendingApprovals >= 0 &&
            queuedTasks >= 0 && executionTokens >= 0) { "Invalid restore counters" }
        require(!idle || (activeRun == null && !queueRunning && runningRuns == 0)) {
            "Idle restore state contradicts an active run or queue"
        }
    }

    override fun toString() =
        "FullArchiveRestoreState(idle=$idle, queueRunning=$queueRunning, " +
            "waitingApprovalRuns=$waitingApprovalRuns, pendingApprovals=$pendingApprovals)"
}

/**
 * Fixed safety policy for a full-archive restore. There is intentionally no constructor path
 * that enables pending approvals, resumes a run, starts a queue task or keeps an execution token.
 */
class FullArchiveRestorePlan(
    val snapshotId: String,
    val cutAtMillis: Long,
    projectInstances: List<AppBackupProjectInstance>,
    val approvalsAuditOnly: Boolean = true,
    val clearPendingApprovals: Boolean = true,
    val pauseUnfinishedRuns: Boolean = true,
    val pauseQueuedTasks: Boolean = true,
    val clearExecutionTokens: Boolean = true,
    val autoStartRuns: Boolean = false,
) {
    val projectInstances: List<AppBackupProjectInstance> =
        Collections.unmodifiableList(ArrayList(projectInstances))

    init {
        require(snapshotId.length in 1..128 && snapshotId.none(Char::isISOControl)) {
            "Invalid restore snapshot id"
        }
        require(cutAtMillis >= 0) { "Invalid restore cut time" }
        require(this.projectInstances.size <= AppBackupProjectInstance.MAX_PROJECTS) {
            "Too many project instances"
        }
        require(approvalsAuditOnly && clearPendingApprovals && pauseUnfinishedRuns &&
            pauseQueuedTasks && clearExecutionTokens && !autoStartRuns) {
            "Restore policy must remain non-executable"
        }
    }
}

enum class AppBackupRestoreReceiptState { PREPARED, COMMITTED, FAILED }

/**
 * Durable marker for the exact additions of one restore. A PREPARED receipt found after a crash
 * means the port must remove the listed additions before any new restore may start.
 */
class AppBackupRestoreReceipt(
    val snapshotId: String,
    val state: AppBackupRestoreReceiptState,
    val preparedAtMillis: Long,
    val updatedAtMillis: Long,
    val cutAtMillis: Long,
    paths: List<String>,
    projectInstances: List<AppBackupProjectInstance>,
    val failure: String? = null,
) {
    val paths: List<String> = Collections.unmodifiableList(ArrayList(paths))
    val projectInstances: List<AppBackupProjectInstance> =
        Collections.unmodifiableList(ArrayList(projectInstances))

    init {
        require(snapshotId.length in 1..128 && snapshotId.none(Char::isISOControl)) {
            "Invalid restore receipt snapshot id"
        }
        require(preparedAtMillis >= 0 && updatedAtMillis >= preparedAtMillis && cutAtMillis >= 0) {
            "Invalid restore receipt time"
        }
        require(this.paths.size <= 4_096) { "Too many restore receipt paths" }
        require(this.paths.all { it.length in 1..240 && it.none(Char::isISOControl) }) {
            "Invalid restore receipt path"
        }
        require(this.paths.distinct().size == this.paths.size) { "Duplicate restore receipt path" }
        require(this.projectInstances.size <= AppBackupProjectInstance.MAX_PROJECTS) {
            "Too many restore receipt projects"
        }
        require(failure == null || validLabel(failure, 200)) { "Invalid restore failure code" }
        require(state == AppBackupRestoreReceiptState.FAILED || failure == null) {
            "Only a failed restore receipt carries a failure code"
        }
    }

    fun transition(
        next: AppBackupRestoreReceiptState,
        atMillis: Long,
        failure: String? = null,
    ): AppBackupRestoreReceipt = AppBackupRestoreReceipt(
        snapshotId = snapshotId,
        state = next,
        preparedAtMillis = preparedAtMillis,
        updatedAtMillis = atMillis.coerceAtLeast(preparedAtMillis),
        cutAtMillis = cutAtMillis,
        paths = paths,
        projectInstances = projectInstances,
        failure = if (next == AppBackupRestoreReceiptState.FAILED) failure else null,
    )

    override fun toString() = "AppBackupRestoreReceipt(snapshotId=$snapshotId, state=$state, paths=${paths.size})"
}

/** Device-local, durable receipt storage implemented by the app's restore adapter. */
interface FullArchiveRestoreJournal {
    suspend fun read(): AppBackupRestoreReceipt?

    /** Must not return before the receipt is durable; a later crash must still see it. */
    suspend fun write(receipt: AppBackupRestoreReceipt)
}

/**
 * Full-application export/restore boundary. Implementations are the only component allowed to
 * read Room stores and project snapshots for an archive; core never opens either database.
 *
 * Export contract:
 * - Return one entry per readable project snapshot using the same `registeredProjects` /
 *   `saveProject` rules as [NativeProjectSetBackupService], plus user-visible Agent sessions,
 *   read-only approval/proposal audits and a queue history snapshot. Diagnostics are included
 *   only when [FullArchiveExportOptions.includeDiagnostics] is true.
 * - Never export API keys, Keystore material, key-state records, OAuth tokens, request headers,
 *   raw execution leases/tokens or executable approvals.
 * - Every entry must belong to the same quiesced cut. [FullArchiveEntry.writer] must not read a
 *   later revision.
 *
 * Restore contract:
 * - [restoreState] is checked before any mutation and must declare [FullArchiveRestoreState.idle].
 * - [beginRestore] stages/validates every entry without touching live state; [AppBackupRestoreSession.publish]
 *   is the only mutation point. It must import project snapshots as new local instances (never
 *   overwrite an existing project), remove PENDING approvals, settle RUNNING/WAITING_APPROVAL
 *   runs to NEEDS_REVIEW or PAUSED, convert QUEUED/RUNNING queue tasks to PAUSED, clear execution
 *   tokens, and must not dispatch, resume or start anything.
 * - Publishing a failure must remove only additions of this restore and preserve prior data.
 */
interface FullArchiveDataPort {
    suspend fun exportArchive(options: FullArchiveExportOptions): FullArchiveExport

    suspend fun restoreState(): FullArchiveRestoreState

    val restoreJournal: FullArchiveRestoreJournal

    suspend fun beginRestore(
        plan: FullArchiveRestorePlan,
        entries: List<FullArchiveStagedEntry>,
    ): AppBackupRestoreSession

    /** Removes the additions listed by an interrupted PREPARED receipt; never resumes work. */
    suspend fun recoverPendingRestore(receipt: AppBackupRestoreReceipt)
}

/**
 * Shared state projection used by data ports when importing history. It is deliberately a pure
 * mapping so it can be unit-tested without a database and cannot accidentally retain a live state.
 */
object AppArchiveRestorePolicy {
    fun runState(state: StoredRunState): StoredRunState = when (state) {
        StoredRunState.RUNNING, StoredRunState.WAITING_APPROVAL -> StoredRunState.INTERRUPTED
        else -> state
    }

    fun queueState(state: TranslationTaskQueueState): TranslationTaskQueueState = when (state) {
        TranslationTaskQueueState.QUEUED, TranslationTaskQueueState.RUNNING ->
            TranslationTaskQueueState.PAUSED
        else -> state
    }

    /** A restored approval is audit text only; it never becomes a pending executable grant. */
    const val APPROVALS_ARE_HISTORICAL: Boolean = true

    /** No execution token survives a full-archive restore. */
    fun restoredExecutionTokens(): Int = 0
}

/**
 * Historical usage receipt with the exact price-table binding it referenced. A missing table
 * version resolves to UNKNOWN with no cost value; it is never folded into a zero amount.
 */
class ArchiveUsageReceipt(
    val id: String,
    val priceVersion: Long,
    val priceFingerprint: String? = null,
    val knownCost: String? = null,
) {
    init {
        require(validLabel(id, 200)) { "Invalid usage receipt id" }
        require(priceVersion in 0L..PriceTable.MAX_VERSION) { "Invalid price table version" }
        require(priceFingerprint == null || validSha256(priceFingerprint)) {
            "Invalid price table fingerprint"
        }
        require(knownCost == null || COST_PATTERN.matches(knownCost)) { "Invalid known cost" }
    }

    override fun toString() =
        "ArchiveUsageReceipt(id=$id, priceVersion=$priceVersion, hasCost=${knownCost != null})"

    private companion object {
        val COST_PATTERN = Regex("(0|[1-9][0-9]*)(\\.[0-9]{1,12})?")
    }
}

enum class ArchiveUsageCostStatus { KNOWN, UNKNOWN }

enum class ArchiveUsageCostReason {
    MISSING_PRICE_VERSION,
    PRICE_FINGERPRINT_MISMATCH,
    NO_RECEIPT_COST,
}

class ArchiveUsageCost(
    val receiptId: String,
    val priceVersion: Long,
    val status: ArchiveUsageCostStatus,
    val cost: String?,
    val reason: ArchiveUsageCostReason?,
) {
    init {
        require(validLabel(receiptId, 200)) { "Invalid usage receipt id" }
        require(priceVersion in 0L..PriceTable.MAX_VERSION) { "Invalid price table version" }
        when (status) {
            ArchiveUsageCostStatus.KNOWN -> {
                require(cost != null && reason == null) { "Known cost requires a value" }
            }
            ArchiveUsageCostStatus.UNKNOWN -> {
                require(cost == null && reason != null) { "Unknown cost must not invent a value" }
            }
        }
    }

    override fun toString() =
        "ArchiveUsageCost(receiptId=$receiptId, status=$status, reason=$reason)"
}

object ArchivePriceResolver {
    fun resolve(
        receipt: ArchiveUsageReceipt,
        priceTables: List<PriceTable>,
    ): ArchiveUsageCost {
        val sameVersion = priceTables.filter { it.version == receipt.priceVersion }
        val matching = sameVersion.firstOrNull { table ->
            receipt.priceFingerprint == null || table.fingerprint == receipt.priceFingerprint
        }
        return when {
            sameVersion.isEmpty() -> ArchiveUsageCost(
                receipt.id,
                receipt.priceVersion,
                ArchiveUsageCostStatus.UNKNOWN,
                null,
                ArchiveUsageCostReason.MISSING_PRICE_VERSION,
            )
            matching == null -> ArchiveUsageCost(
                receipt.id,
                receipt.priceVersion,
                ArchiveUsageCostStatus.UNKNOWN,
                null,
                ArchiveUsageCostReason.PRICE_FINGERPRINT_MISMATCH,
            )
            receipt.knownCost == null -> ArchiveUsageCost(
                receipt.id,
                receipt.priceVersion,
                ArchiveUsageCostStatus.UNKNOWN,
                null,
                ArchiveUsageCostReason.NO_RECEIPT_COST,
            )
            else -> ArchiveUsageCost(
                receipt.id,
                receipt.priceVersion,
                ArchiveUsageCostStatus.KNOWN,
                receipt.knownCost,
                null,
            )
        }
    }
}

/**
 * Read-only historical pricing material for a full archive. Cost projections are generated from
 * the archived tables, not from the currently configured table, so a missing old version is
 * visible as UNKNOWN in the archive instead of appearing as a zero cost.
 */
class ArchiveUsageHistory(
    priceTables: List<PriceTable> = emptyList(),
    receipts: List<ArchiveUsageReceipt> = emptyList(),
) {
    val priceTables: List<PriceTable> = Collections.unmodifiableList(ArrayList(priceTables))
    val receipts: List<ArchiveUsageReceipt> = Collections.unmodifiableList(ArrayList(receipts))

    init {
        require(this.priceTables.size <= MAX_PRICE_TABLES) { "Too many archived price tables" }
        require(this.receipts.size <= MAX_RECEIPTS) { "Too many archived usage receipts" }
        require(this.priceTables.map { it.fingerprint }.distinct().size == this.priceTables.size) {
            "Duplicate archived price table"
        }
        require(this.receipts.map { it.id }.distinct().size == this.receipts.size) {
            "Duplicate archived usage receipt"
        }
    }

    fun costIndex(): List<ArchiveUsageCost> = receipts.map { receipt ->
        ArchivePriceResolver.resolve(receipt, priceTables)
    }

    /** Generated audit entries; content is derived only from immutable archived facts. */
    fun entries(): List<FullArchiveEntry> {
        val result = ArrayList<FullArchiveEntry>(2)
        if (priceTables.isNotEmpty()) {
            val json = buildJsonObject {
                put("format", "lnt-archive-price-history")
                put("version", 1)
                put("tables", JsonArray(priceTables.map { table ->
                    Json.parseToJsonElement(table.canonicalJson)
                }))
            }.toString()
            result += FullArchiveEntry.bytes(
                FullArchivePaths.PRICE_TABLES,
                FullArchiveEntryKind.PRICE_HISTORY,
                json.toByteArray(Charsets.UTF_8),
            )
        }
        if (receipts.isNotEmpty()) {
            val costs = costIndex()
            val json = buildJsonObject {
                put("format", "lnt-archive-usage-costs")
                put("version", 1)
                put("receipts", JsonArray(costs.map { cost ->
                    buildJsonObject {
                        put("receipt", cost.receiptId)
                        put("price_version", cost.priceVersion)
                        put("status", cost.status.name)
                        put("cost", cost.cost?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("reason", cost.reason?.let { JsonPrimitive(it.name) } ?: JsonNull)
                    }
                }))
            }.toString()
            result += FullArchiveEntry.bytes(
                FullArchivePaths.COST_INDEX,
                FullArchiveEntryKind.USAGE_HISTORY,
                json.toByteArray(Charsets.UTF_8),
            )
        }
        return result
    }

    companion object {
        val EMPTY = ArchiveUsageHistory()

        private const val MAX_PRICE_TABLES = 64
        private const val MAX_RECEIPTS = 100_000
    }
}

private fun validLabel(value: String, maxChars: Int): Boolean =
    value.isNotBlank() && value.length <= maxChars && value.none(Char::isISOControl)

private fun validSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun validArchiveLabel(value: String, maxChars: Int): Boolean =
    validLabel(value, maxChars)

internal fun validArchiveSha256(value: String): Boolean = validSha256(value)
