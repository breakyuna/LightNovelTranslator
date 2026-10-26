package com.breakyuna.noveltranslator.nativecore.workspace

import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * File-backed [WorkspaceApplyPort] for approved workspace mutations.
 *
 * Writable areas are deliberately limited to `work/`, `uploads/` and `reference/`. Generated
 * `sources/`, `datasets/` and `artifacts/` are never writable, even when a path survived generic
 * preflight. Every write is temp+fsync+rename and every command has an immutable receipt, so a
 * repeated submission returns the same result instead of overwriting newer facts.
 *
 * This adapter never opens a project database. Project-object changes remain in their typed
 * proposal stores; this port only commits the workspace-file half of a mixed batch.
 */
class WorkspaceFileApplyStore(
    root: File,
    private val ref: LocalProjectRef,
    receiptRoot: File,
    private val policy: PathPolicy = PathPolicy.DEFAULT,
    private val now: () -> Long = { System.currentTimeMillis().coerceAtLeast(0) },
) : WorkspaceApplyPort {
    private val base = WorkspaceStorageSupport.openRoot(root)
    private val receipts = WorkspaceStorageSupport.openRoot(receiptRoot)
    private val lock = Mutex()

    override suspend fun apply(
        approved: ApprovedWorkspaceChangeSet,
        conflictPolicy: WorkspaceConflictPolicy,
    ): WorkspaceApplyReceipt = withContext(Dispatchers.IO) {
        lock.withLock {
            require(approved.changeSet.projectId == ref.projectId) {
                "Workspace change set belongs to another project"
            }
            val existing = readReceipt(approved.commandId)
            if (existing != null) {
                require(existing.changeSetId == approved.changeSet.id && existing.digest == approved.changeSet.digest) {
                    "Workspace command was already used for another change set"
                }
                return@withLock existing
            }
            val intent = readIntent(approved.commandId)
            if (intent != null) {
                require(intent.changeSetId == approved.changeSet.id && intent.digest == approved.changeSet.digest) {
                    "Workspace command was already used for another change set"
                }
                val recovered = recover(approved, intent)
                writeReceipt(recovered)
                deleteIntent(approved.commandId)
                return@withLock recovered
            }
            if (approved.resumed) {
                // A durable claim exists but the writer never recorded an intent. Its outcome is
                // indeterminate; do not start a fresh write with the same command.
                val indeterminate = approved.changeSet.changes.map { change ->
                    WorkspaceConflict(change.path, WorkspaceConflictReason.STALE_FINGERPRINT, "indeterminate")
                }
                val rejected = receipt(approved, WorkspaceApplyStatus.REJECTED, emptyList(), indeterminate)
                writeReceipt(rejected)
                return@withLock rejected
            }
            val conflicts = ArrayList<WorkspaceConflict>()
            val accepted = ArrayList<WorkspaceChange>()
            for (change in approved.changeSet.changes) {
                val path = change.path
                if (!isWritableArea(path)) {
                    conflicts += WorkspaceConflict(path, WorkspaceConflictReason.PATH_POLICY, "read_only_area")
                    continue
                }
                if (change.content is WorkspaceChangeContent.FromMaterial) {
                    conflicts += WorkspaceConflict(path, WorkspaceConflictReason.MISSING_CONTENT, "material")
                    continue
                }
                val target = WorkspaceStorageSupport.resolve(base, path)
                val current = fingerprint(target)
                when (change.operation) {
                    WorkspaceChangeOperation.CREATE -> if (current != null) {
                        conflicts += WorkspaceConflict(path, WorkspaceConflictReason.ALREADY_EXISTS, "create")
                    } else accepted += change
                    WorkspaceChangeOperation.UPDATE, WorkspaceChangeOperation.DELETE -> {
                        if (target.isDirectory) {
                            conflicts += WorkspaceConflict(path, WorkspaceConflictReason.PATH_POLICY, "directory")
                        } else if (current == null || current != change.expectedFingerprint) {
                            conflicts += WorkspaceConflict(path, WorkspaceConflictReason.STALE_FINGERPRINT, change.operation.name)
                        } else accepted += change
                    }
                }
            }
            if (conflicts.isNotEmpty() && conflictPolicy == WorkspaceConflictPolicy.REJECT_BATCH) {
                val rejected = receipt(approved, WorkspaceApplyStatus.REJECTED, emptyList(), conflicts)
                writeReceipt(rejected)
                return@withLock rejected
            }
            writeIntent(approved, accepted, conflicts)
            val committed = ArrayList<WorkspaceCommittedChange>()
            for (change in accepted) {
                val target = WorkspaceStorageSupport.resolve(base, change.path, createParents = change.operation != WorkspaceChangeOperation.DELETE)
                try {
                    when (change.operation) {
                        WorkspaceChangeOperation.CREATE, WorkspaceChangeOperation.UPDATE -> {
                            val bytes = (change.content as WorkspaceChangeContent.Inline).bytes
                            if (change.operation == WorkspaceChangeOperation.UPDATE && !target.isFile) {
                                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.STALE_FINGERPRINT, "update")
                                continue
                            }
                            WorkspaceStorageSupport.writeAtomically(target, bytes)
                            val after = fingerprint(target)
                            if (after != change.content.sha256) {
                                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.STALE_FINGERPRINT, "verify")
                            } else {
                                committed += WorkspaceCommittedChange(change.path, change.operation, requireNotNull(after))
                            }
                        }
                        WorkspaceChangeOperation.DELETE -> {
                            if (!target.isFile || !target.delete()) {
                                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.STALE_FINGERPRINT, "delete")
                            } else {
                                committed += WorkspaceCommittedChange(change.path, change.operation, EMPTY_DIGEST)
                            }
                        }
                    }
                } catch (_: Exception) {
                    conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.BUDGET, "write_failed")
                }
            }
            val status = when {
                committed.isEmpty() && conflicts.isNotEmpty() -> WorkspaceApplyStatus.REJECTED
                conflicts.isEmpty() && committed.isNotEmpty() -> WorkspaceApplyStatus.COMMITTED
                committed.isEmpty() -> WorkspaceApplyStatus.REJECTED
                else -> WorkspaceApplyStatus.PARTIAL
            }
            val result = receipt(approved, status, committed, conflicts)
            writeReceipt(result)
            deleteIntent(approved.commandId)
            result
        }
    }

    private fun isWritableArea(path: WorkspacePath): Boolean =
        path.value.startsWith("work/") || path.value.startsWith("uploads/") || path.value.startsWith("reference/")

    private fun fingerprint(file: File): WorkspaceDigest? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return WorkspaceDigest(digest.digest().joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        })
    }

    private fun receipt(
        approved: ApprovedWorkspaceChangeSet,
        status: WorkspaceApplyStatus,
        committed: List<WorkspaceCommittedChange>,
        conflicts: List<WorkspaceConflict>,
    ): WorkspaceApplyReceipt = WorkspaceApplyReceipt(
        approved.commandId,
        approved.changeSet.id,
        approved.changeSet.digest,
        status,
        committed,
        conflicts,
        approved.baseRevision,
        now(),
    )

    private class Intent(
        val command: CommandId,
        val changeSetId: WorkspaceChangeSetId,
        val digest: WorkspaceDigest,
        val changes: List<IntentChange>,
    )

    private class IntentChange(
        val path: WorkspacePath,
        val operation: WorkspaceChangeOperation,
        val expected: WorkspaceDigest?,
        val newSha256: WorkspaceDigest?,
        val newSize: Long,
        /** False when preflight rejected this change before any write was attempted. */
        val accepted: Boolean,
        val rejectionReason: WorkspaceConflictReason?,
        val rejectionDetail: String?,
    )

    private fun intentFile(command: CommandId): File =
        File(receipts, sha256Hex(command.value.toByteArray(Charsets.UTF_8)) + ".intent.json")

    private fun writeIntent(
        approved: ApprovedWorkspaceChangeSet,
        accepted: List<WorkspaceChange>,
        conflicts: List<WorkspaceConflict>,
    ) {
        val acceptedPaths = accepted.mapTo(HashSet()) { it.path.value }
        val conflictsByPath = conflicts.associateBy { it.path.value }
        val text = buildJsonObject {
            put("version", 2)
            put("command", approved.commandId.value)
            put("change_set", approved.changeSet.id.value)
            put("digest", approved.changeSet.digest.sha256)
            put("changes", JsonArray(approved.changeSet.changes.map { change ->
                val rejection = conflictsByPath[change.path.value]
                buildJsonObject {
                    put("path", change.path.value)
                    put("operation", change.operation.name)
                    put("expected", change.expectedFingerprint?.sha256 ?: "")
                    put("new", change.content?.sha256?.sha256 ?: "")
                    put("size", change.content?.sizeBytes ?: 0L)
                    put("accepted", change.path.value in acceptedPaths)
                    put("reason", rejection?.reason?.name ?: "")
                    put("detail", rejection?.detail ?: "")
                }
            }))
        }.toString()
        val atomic = AtomicFile(intentFile(approved.commandId))
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun readIntent(command: CommandId): Intent? {
        val file = intentFile(command)
        if (!file.exists()) return null
        val text = AtomicFile(file).openRead().use { readBounded(it, MAX_INTENT_BYTES).toString(Charsets.UTF_8) }
        val root = com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
            .parse(text, com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits(2 * 1024 * 1024, 8)).json
        // v1 lacked the accepted flag and could not distinguish a preflight rejection from an
        // attempted write; it is treated as unattempted so recovery never claims a false commit.
        val version = root.getValue("version").jsonPrimitive.content
        require(version == "1" || version == "2") { "Unsupported workspace intent version" }
        require(root.getValue("command").jsonPrimitive.content == command.value) { "Workspace intent identity changed" }
        val changes = (root.getValue("changes") as JsonArray).map { element ->
            val row = element as JsonObject
            val expected = row.getValue("expected").jsonPrimitive.content.takeIf { it.isNotEmpty() }?.let(::WorkspaceDigest)
            val newSha = row.getValue("new").jsonPrimitive.content.takeIf { it.isNotEmpty() }?.let(::WorkspaceDigest)
            val accepted = if (version == "2") row.getValue("accepted").jsonPrimitive.content.toBoolean() else false
            val reason = (row["reason"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.takeIf { it.isNotEmpty() }
                ?.let(WorkspaceConflictReason::valueOf)
            val detail = (row["detail"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content?.takeIf { it.isNotEmpty() }
            IntentChange(WorkspacePath(row.getValue("path").jsonPrimitive.content),
                WorkspaceChangeOperation.valueOf(row.getValue("operation").jsonPrimitive.content),
                expected, newSha, row.getValue("size").jsonPrimitive.content.toLong(), accepted, reason, detail)
        }
        return Intent(command, WorkspaceChangeSetId(root.getValue("change_set").jsonPrimitive.content),
            WorkspaceDigest(root.getValue("digest").jsonPrimitive.content), changes)
    }

    private fun recover(approved: ApprovedWorkspaceChangeSet, intent: Intent): WorkspaceApplyReceipt {
        val committed = ArrayList<WorkspaceCommittedChange>()
        val conflicts = ArrayList<WorkspaceConflict>()
        for (change in intent.changes) {
            if (!change.accepted) {
                // Preflight rejected this change and no write was attempted. Replaying the stored
                // conflict is the only truthful receipt (a missing DELETE file is not a success).
                conflicts += WorkspaceConflict(change.path,
                    change.rejectionReason ?: WorkspaceConflictReason.STALE_FINGERPRINT,
                    change.rejectionDetail)
                continue
            }
            val target = try {
                WorkspaceStorageSupport.resolve(base, change.path)
            } catch (_: Exception) {
                null
            }
            val isDirectory = target?.isDirectory == true
            val current = if (isDirectory) null else target?.let(::fingerprint)
            val isNew = !isDirectory && when (change.operation) {
                WorkspaceChangeOperation.DELETE -> current == null
                WorkspaceChangeOperation.CREATE, WorkspaceChangeOperation.UPDATE ->
                    current != null && current == change.newSha256
            }
            if (isNew) {
                committed += WorkspaceCommittedChange(change.path, change.operation,
                    current ?: EMPTY_DIGEST)
            } else {
                val detail = when (change.operation) {
                    WorkspaceChangeOperation.CREATE -> "not_committed"
                    WorkspaceChangeOperation.UPDATE, WorkspaceChangeOperation.DELETE ->
                        if (current == change.expected) "not_committed" else "unknown_state"
                }
                conflicts += WorkspaceConflict(change.path, WorkspaceConflictReason.STALE_FINGERPRINT, detail)
            }
        }
        val status = when {
            committed.isEmpty() && conflicts.isNotEmpty() -> WorkspaceApplyStatus.REJECTED
            conflicts.isEmpty() && committed.isNotEmpty() -> WorkspaceApplyStatus.COMMITTED
            committed.isEmpty() -> WorkspaceApplyStatus.REJECTED
            else -> WorkspaceApplyStatus.PARTIAL
        }
        return receipt(approved, status, committed, conflicts)
    }

    private fun deleteIntent(command: CommandId) {
        try { intentFile(command).delete() } catch (_: Exception) { /* Receipt already wins. */ }
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64L * 1024).toInt())
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Workspace apply evidence exceeds its limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun receiptFile(command: CommandId): File =
        File(receipts, sha256Hex(command.value.toByteArray(Charsets.UTF_8)) + ".json")

    private fun writeReceipt(receipt: WorkspaceApplyReceipt) {
        val text = buildJsonObject {
            put("version", 1)
            put("command", receipt.commandId.value)
            put("change_set", receipt.changeSetId.value)
            put("digest", receipt.digest.sha256)
            put("status", receipt.status.name)
            put("revision", receipt.revision)
            put("at", receipt.committedAtMillis)
            put("committed", JsonArray(receipt.committed.map {
                buildJsonObject {
                    put("path", it.path.value); put("operation", it.operation.name); put("fingerprint", it.fingerprint.sha256)
                }
            }))
            put("conflicts", JsonArray(receipt.conflicts.map {
                buildJsonObject {
                    put("path", it.path.value); put("reason", it.reason.name); put("detail", it.detail ?: "")
                }
            }))
        }.toString()
        val atomic = AtomicFile(receiptFile(receipt.commandId))
        val output: FileOutputStream = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun readReceipt(command: CommandId): WorkspaceApplyReceipt? {
        val file = receiptFile(command)
        if (!file.exists()) return null
        val text = AtomicFile(file).openRead().use { readBounded(it, MAX_RECEIPT_BYTES).toString(Charsets.UTF_8) }
        val root = com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
            .parse(text, com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits(64 * 1024, 8)).json
        require(root.getValue("version").jsonPrimitive.content == "1") { "Unsupported workspace receipt version" }
        require(root.getValue("command").jsonPrimitive.content == command.value) { "Workspace receipt identity changed" }
        val status = WorkspaceApplyStatus.valueOf(root.getValue("status").jsonPrimitive.content)
        val committed = (root.getValue("committed") as JsonArray).map { element ->
            val row = element as JsonObject
            WorkspaceCommittedChange(WorkspacePath(row.getValue("path").jsonPrimitive.content),
                WorkspaceChangeOperation.valueOf(row.getValue("operation").jsonPrimitive.content),
                WorkspaceDigest(row.getValue("fingerprint").jsonPrimitive.content))
        }
        val conflicts = (root.getValue("conflicts") as JsonArray).map { element ->
            val row = element as JsonObject
            val detail = row.getValue("detail").jsonPrimitive.content.takeIf { it.isNotEmpty() }
            WorkspaceConflict(WorkspacePath(row.getValue("path").jsonPrimitive.content),
                WorkspaceConflictReason.valueOf(row.getValue("reason").jsonPrimitive.content), detail)
        }
        return WorkspaceApplyReceipt(command, WorkspaceChangeSetId(root.getValue("change_set").jsonPrimitive.content),
            WorkspaceDigest(root.getValue("digest").jsonPrimitive.content), status, committed, conflicts,
            root.getValue("revision").jsonPrimitive.content.toLong(),
            root.getValue("at").jsonPrimitive.content.toLong())
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { ((it.toInt() and 255).toString(16)).padStart(2, '0') }

    private companion object {
        const val MAX_INTENT_BYTES = 2L * 1024 * 1024
        const val MAX_RECEIPT_BYTES = 64L * 1024
        val EMPTY_DIGEST = WorkspaceDigest(
            MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
                .joinToString("") { ((it.toInt() and 255).toString(16)).padStart(2, '0') },
        )
    }
}
