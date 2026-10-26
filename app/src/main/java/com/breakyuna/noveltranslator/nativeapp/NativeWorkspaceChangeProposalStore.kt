package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RunLease
import com.breakyuna.noveltranslator.nativecore.domain.RuntimePort
import com.breakyuna.noveltranslator.nativecore.workspace.ApprovedWorkspaceChangeSet
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyReceipt
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApplyStatus
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApprovalDecision
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceApprovalGrants
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSet
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceChangeSetCodec
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceConflictPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceFileApplyStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Classified proposal-store failure; no file content or path is ever included. */
internal class WorkspaceChangeProposalException(
    val reason: Reason,
    cause: Throwable? = null,
) : IllegalStateException("Workspace change proposal unavailable: $reason", cause) {
    enum class Reason { CORRUPT, NOT_FOUND, LIMIT, PERSISTENCE }
}

/**
 * File-backed, non-Room authority for prepared workspace file batches.
 *
 * Preparation freezes an inline-only change set under
 * `filesDir/native_workspace_changes/v1/<instance>/<generation>/<chat>/`. A Chat can have only
 * one unresolved proposal. Apply writes a claim first, then delegates to
 * [WorkspaceFileApplyStore] with `ACCEPT_UNCONFLICTED`; the proposal is only terminal after the
 * writer's durable receipt is verified. An interrupted claim is never replayed: a later apply
 * or [reconcile] records CONFLICT instead.
 *
 * Production should pass [RuntimePort] so [reconcile] can observe an active run; the optional
 * null form exists for file-only tests and callers that have already proven no run is active.
 */
internal class NativeWorkspaceChangeProposalStore(
    private val changesRoot: File,
    private val workspaceRoots: (LocalProjectRef) -> File,
    private val receiptRoot: File,
    private val activeRun: (() -> RunIdentity?)? = null,
    private val now: () -> Long = { System.currentTimeMillis().coerceAtLeast(0) },
    private val applyApproved: suspend (LocalProjectRef, ApprovedWorkspaceChangeSet) -> WorkspaceApplyReceipt =
        { ref, approved ->
            WorkspaceFileApplyStore(
                root = workspaceRoots(ref),
                ref = ref,
                receiptRoot = receiptRoot,
            ).apply(approved, WorkspaceConflictPolicy.ACCEPT_UNCONFLICTED)
        },
) {
    private val mutex = Mutex()

    constructor(
        context: Context,
        workspace: NativeWorkspaceAccess,
        runtime: RuntimePort? = null,
    ) : this(
        changesRoot = File(context.applicationContext.filesDir, "native_workspace_changes"),
        workspaceRoots = { ref -> workspace.workspaceRoot(ref) },
        receiptRoot = File(context.applicationContext.filesDir, "native_workspace_receipts"),
        activeRun = runtime?.let { port ->
            val provider: () -> RunIdentity? = { port.snapshot().activeRun?.identity }
            provider
        },
    )

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    /**
     * Freezes one inline batch. A repeated Run/command returns the already frozen record when
     * the change set identity and digest still match, regardless of the caller's new base revision.
     */
    suspend fun prepare(
        lease: RunLease,
        ref: LocalProjectRef,
        baseRevision: Long,
        changeSet: WorkspaceChangeSet,
    ): WorkspaceChangeProposalSummary = io {
        val identity = lease.identity
        val chat = requireNotNull(identity.chatId) { "Workspace changes require a bound Chat" }
        require(identity.projectId == ref.projectId) { "Workspace lease belongs to another project" }
        require(changeSet.projectId == ref.projectId) { "Workspace changes belong to another project" }
        require(changeSet.chatId == chat) { "Workspace changes belong to another Chat" }
        require(changeSet.runId == identity.runId) { "Workspace changes belong to another run" }
        require(baseRevision >= 0) { "Invalid workspace base revision" }
        require(changeSet.changes.isNotEmpty()) { "Empty workspace change set" }

        val existing = commandCandidates(identity.commandId)
            .filter { it.command == identity.commandId }
            .also {
                if (it.size > 1) throw WorkspaceChangeProposalException(
                    WorkspaceChangeProposalException.Reason.CORRUPT,
                )
            }
            .singleOrNull()
        if (existing != null) {
            require(existing.ref == ref && existing.chat == chat && existing.run == identity.runId) {
                "Workspace command belongs to another run or Chat"
            }
            require(existing.digest == changeSet.digest && existing.changeSet.id == changeSet.id) {
                "Workspace command was already used for another change set"
            }
            return@io existing.summary()
        }

        val encoded = WorkspaceChangeSetCodec.encode(changeSet)
        val records = readChat(ref, chat)
        check(records.none { it.state == WorkspaceChangeProposalState.PENDING }) {
            "One pending workspace change proposal per Chat"
        }
        ensureCapacity()
        val record = WorkspaceChangeProposalRecord(
            id = ApprovalRequestId(UUID.randomUUID().toString()),
            ref = ref,
            chat = chat,
            run = identity.runId,
            command = identity.commandId,
            baseRevision = baseRevision,
            changeSet = changeSet,
            encodedChangeSet = encoded,
            digest = changeSet.digest,
            state = WorkspaceChangeProposalState.PENDING,
            createdAt = now(),
            binding = null,
            claimedAt = null,
            receipt = null,
        )
        write(record)
        record.summary()
    }

    suspend fun recent(ref: LocalProjectRef, chat: ChatId): List<WorkspaceChangeProposalSummary> = io {
        readChat(ref, chat)
            .sortedWith(compareByDescending<WorkspaceChangeProposalRecord> { it.createdAt }
                .thenByDescending { it.id.value })
            .map { it.summary() }
    }

    suspend fun pending(ref: LocalProjectRef, chat: ChatId): WorkspaceChangeProposalSummary? = io {
        val records = readChat(ref, chat).filter { it.state == WorkspaceChangeProposalState.PENDING }
        if (records.size > 1) throw WorkspaceChangeProposalException(
            WorkspaceChangeProposalException.Reason.CORRUPT,
        )
        records.singleOrNull()?.summary()
    }

    suspend fun find(
        ref: LocalProjectRef,
        chat: ChatId,
        id: ApprovalRequestId,
    ): WorkspaceChangeProposalSummary = io {
        loadById(ref, chat, id).summary()
    }

    /**
     * Claims and applies one PENDING proposal. The view's immutable identity fields must match
     * the persisted record; a terminal record is returned without invoking the writer again.
     */
    suspend fun apply(
        lease: RunLease,
        view: WorkspaceChangeProposalSummary,
    ): WorkspaceChangeProposalState = io {
        val identity = lease.identity
        require(identity.projectId == view.ref.projectId && identity.chatId == view.chat &&
            identity.runId == view.run && identity.commandId == view.command) {
            "Workspace proposal does not match this run"
        }
        val record = loadById(view.ref, view.chat, view.id)
        verifyView(record, view)
        ensureCommandUnique(record)
        require(view.state == WorkspaceChangeProposalState.PENDING ||
            view.state == record.state) {
            "Workspace proposal view state changed"
        }
        if (record.state != WorkspaceChangeProposalState.PENDING) return@io record.state

        val binding = NativeWorkspaceChangeProposalCodec.binding(record.digest, record.command)
        val resumed = record.binding != null
        val claimed = if (!resumed) {
            record.copy(binding = binding, claimedAt = now()).also { write(it) }
        } else {
            require(record.binding == binding) { "Workspace proposal binding changed" }
            // A claimed record may have crossed the writer boundary. The applier reconciles its
            // durable intent; it must not start a fresh write for the same command.
            record
        }
        val approvedAt = requireNotNull(claimed.claimedAt)
        val approved = try {
            WorkspaceApprovalGrants.issue(
                changeSet = claimed.changeSet,
                commandId = claimed.command,
                approvalRequestId = claimed.id,
                approvedAtMillis = approvedAt,
                decision = WorkspaceApprovalDecision.Approved(claimed.id, claimed.digest, approvedAt),
                baseRevision = claimed.baseRevision,
                resumed = resumed,
            )
        } catch (cancelled: CancellationException) {
            markConflict(claimed)
            throw cancelled
        } catch (_: Exception) {
            markConflict(claimed)
            return@io WorkspaceChangeProposalState.CONFLICT
        }
        val receipt = try {
            applyApproved(claimed.ref, approved)
        } catch (cancelled: CancellationException) {
            markConflict(claimed)
            throw cancelled
        } catch (_: Exception) {
            markConflict(claimed)
            return@io WorkspaceChangeProposalState.CONFLICT
        }
        val settled = try {
            require(receipt.commandId == claimed.command &&
                receipt.changeSetId == claimed.changeSet.id &&
                receipt.digest == claimed.digest) {
                "Workspace apply receipt identity changed"
            }
            if (resumed && receipt.status == WorkspaceApplyStatus.REJECTED) {
                // A claimed command without a durable writer intent is indeterminate; never
                // convert it into an ordinary rejection that suggests nothing was written.
                markConflict(claimed)
                return@io WorkspaceChangeProposalState.CONFLICT
            }
            val state = when (receipt.status) {
                WorkspaceApplyStatus.COMMITTED -> WorkspaceChangeProposalState.APPLIED
                WorkspaceApplyStatus.PARTIAL -> WorkspaceChangeProposalState.PARTIAL
                WorkspaceApplyStatus.REJECTED -> WorkspaceChangeProposalState.REJECTED
            }
            val proposalReceipt = WorkspaceChangeProposalReceipt(
                status = receipt.status,
                committed = receipt.committed.size,
                conflicts = receipt.conflicts.size,
                revision = receipt.revision,
                at = receipt.committedAtMillis,
                committedPaths = receipt.committed.map { it.path.value },
                conflictPaths = receipt.conflicts.map { it.path.value },
            )
            withContext(NonCancellable) {
                write(claimed.copy(state = state, receipt = proposalReceipt))
            }
            state
        } catch (cancelled: CancellationException) {
            markConflict(claimed)
            throw cancelled
        } catch (_: Exception) {
            markConflict(claimed)
            WorkspaceChangeProposalState.CONFLICT
        }
        settled
    }

    /** Rejects only an unclaimed PENDING proposal; a claimed one is already ambiguous. */
    suspend fun reject(view: WorkspaceChangeProposalSummary): WorkspaceChangeProposalState = io {
        val record = loadById(view.ref, view.chat, view.id)
        verifyView(record, view)
        require(view.state == WorkspaceChangeProposalState.PENDING ||
            view.state == record.state) {
            "Workspace proposal view state changed"
        }
        if (record.state != WorkspaceChangeProposalState.PENDING) return@io record.state
        val settled = if (record.binding == null) {
            record.copy(state = WorkspaceChangeProposalState.REJECTED)
        } else {
            record.copy(state = WorkspaceChangeProposalState.CONFLICT)
        }
        write(settled)
        settled.state
    }

    /**
     * Settles an explicit Agent cancellation. An unclaimed pending proposal is rejected; a
     * claimed one crossed the writer boundary and becomes CONFLICT without replaying it.
     */
    suspend fun cancelRun(ref: LocalProjectRef, chat: ChatId, run: RunId): Int = io {
        var settled = 0
        readChat(ref, chat).forEach { record ->
            if (record.run != run || record.state != WorkspaceChangeProposalState.PENDING) return@forEach
            write(record.copy(state = if (record.binding == null) WorkspaceChangeProposalState.REJECTED
                else WorkspaceChangeProposalState.CONFLICT))
            settled += 1
        }
        settled
    }

    /**
     * Reconciles this Chat only when no run is active. A claim that never received a receipt is
     * CONFLICT; the original apply is not replayed automatically. Returns the settled count.
     */
    suspend fun reconcile(ref: LocalProjectRef, chat: ChatId): Int = io {
        if (activeRun?.invoke() != null) return@io 0
        var settled = 0
        readChat(ref, chat).forEach { record ->
            if (record.state == WorkspaceChangeProposalState.PENDING && record.binding != null) {
                write(record.copy(state = WorkspaceChangeProposalState.CONFLICT))
                settled += 1
            }
        }
        settled
    }

    /** Best-effort conversion of a claimed record to CONFLICT; never replays the command. */
    private suspend fun markConflict(record: WorkspaceChangeProposalRecord) {
        withContext(NonCancellable) {
            runCatching {
                write(record.copy(state = WorkspaceChangeProposalState.CONFLICT))
            }
        }
    }

    private fun verifyView(record: WorkspaceChangeProposalRecord, view: WorkspaceChangeProposalSummary) {
        require(record.id == view.id && record.ref == view.ref && record.chat == view.chat &&
            record.run == view.run && record.command == view.command &&
            record.baseRevision == view.baseRevision && record.digest == view.digest &&
            record.changeSet.summary == view.summary &&
            record.changeSet.changes.take(64).map { it.path.value } == view.paths &&
            record.createdAt == view.createdAt) {
            "Workspace proposal view changed"
        }
    }

    private fun ensureCommandUnique(record: WorkspaceChangeProposalRecord) {
        val matches = commandCandidates(record.command).filter { it.command == record.command }
        if (matches.size > 1) throw WorkspaceChangeProposalException(
            WorkspaceChangeProposalException.Reason.CORRUPT,
        )
        val other = matches.singleOrNull()
        require(other == null || (other.id == record.id && other.ref == record.ref &&
            other.chat == record.chat && other.digest == record.digest)) {
            "Workspace command was already used for another change set"
        }
    }

    private fun loadById(ref: LocalProjectRef, chat: ChatId, id: ApprovalRequestId): WorkspaceChangeProposalRecord {
        val matches = readChat(ref, chat).filter { it.id == id }
        return when (matches.size) {
            0 -> throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.NOT_FOUND)
            1 -> matches.single()
            else -> throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.CORRUPT)
        }
    }

    private fun readChat(ref: LocalProjectRef, chat: ChatId): List<WorkspaceChangeProposalRecord> {
        if (!changesRoot.exists()) return emptyList()
        if (!changesRoot.isDirectory) throw WorkspaceChangeProposalException(
            WorkspaceChangeProposalException.Reason.CORRUPT,
        )
        val directory = partitionDirectory(ref, chat)
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory) throw WorkspaceChangeProposalException(
            WorkspaceChangeProposalException.Reason.CORRUPT,
        )
        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && isProposalFile(it.name) }
            .sortedBy { it.name }
        return files.map { file ->
            val record = readFile(file)
            if (record.ref != ref || record.chat != chat) {
                throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.CORRUPT)
            }
            record
        }
    }

    private fun commandCandidates(command: CommandId): List<WorkspaceChangeProposalRecord> {
        val suffix = "-c-" + sha256Hex(command.value.toByteArray(Charsets.UTF_8)) + ".json"
        return allProposalFiles()
            .filter { it.name.endsWith(suffix) }
            .map { readFile(it) }
    }

    private fun allProposalFiles(): List<File> {
        if (!changesRoot.exists()) return emptyList()
        if (!changesRoot.isDirectory) throw WorkspaceChangeProposalException(
            WorkspaceChangeProposalException.Reason.CORRUPT,
        )
        val result = ArrayList<File>()
        fun visit(directory: File) {
            val children = directory.listFiles() ?: throw WorkspaceChangeProposalException(
                WorkspaceChangeProposalException.Reason.CORRUPT,
            )
            children.forEach { child ->
                if (child.isDirectory) {
                    visit(child)
                } else if (child.isFile && isProposalFile(child.name)) {
                    result += child
                }
            }
        }
        visit(changesRoot)
        return result
    }

    private fun readFile(file: File): WorkspaceChangeProposalRecord {
        try {
            if (!file.isFile) {
                throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.NOT_FOUND)
            }
            if (file.length() !in 1L..NativeWorkspaceChangeProposalCodec.MAX_BYTES) {
                throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.CORRUPT)
            }
            val bytes = AtomicFile(file).openRead().use { input ->
                readBounded(input, NativeWorkspaceChangeProposalCodec.MAX_BYTES)
            }
            val record = NativeWorkspaceChangeProposalCodec.decode(decodeUtf8(bytes))
            require(file.absoluteFile == fileFor(record).absoluteFile) {
                "Workspace proposal filename changed"
            }
            return record
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: WorkspaceChangeProposalException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkspaceChangeProposalException(
                WorkspaceChangeProposalException.Reason.CORRUPT,
                failure,
            )
        }
    }

    private fun write(record: WorkspaceChangeProposalRecord) {
        try {
            val file = fileFor(record)
            val parent = file.parentFile
                ?: throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.PERSISTENCE)
            if (!parent.exists() && !parent.mkdirs()) {
                throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.PERSISTENCE)
            }
            check(parent.isDirectory) { "Workspace proposal directory is unavailable" }
            val bytes = NativeWorkspaceChangeProposalCodec.encode(record).toByteArray(Charsets.UTF_8)
            if (bytes.size.toLong() > NativeWorkspaceChangeProposalCodec.MAX_BYTES) {
                throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.LIMIT)
            }
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                output.flush()
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (failure: Throwable) {
                atomic.failWrite(output)
                if (failure is Exception) {
                    throw WorkspaceChangeProposalException(
                        WorkspaceChangeProposalException.Reason.PERSISTENCE,
                        failure,
                    )
                }
                throw failure
            }
        } catch (failure: WorkspaceChangeProposalException) {
            throw failure
        } catch (failure: Exception) {
            throw WorkspaceChangeProposalException(
                WorkspaceChangeProposalException.Reason.PERSISTENCE,
                failure,
            )
        }
    }

    /**
     * Retains at most 64 proposal files. Only terminal entries are pruned, oldest first; a
     * damaged file makes the whole preparation fail instead of silently discarding evidence.
     */
    private fun ensureCapacity() {
        val files = allProposalFiles()
        if (files.size < MAX_TOTAL_PROPOSALS) return
        val records = files.map { file -> file to readFile(file) }
        var count = records.size
        records.filter { (_, record) -> record.state != WorkspaceChangeProposalState.PENDING }
            .sortedWith(
                compareBy<Pair<File, WorkspaceChangeProposalRecord>> { it.second.createdAt }
                    .thenBy { it.first.name },
            )
            .forEach { (file, _) ->
                if (count < MAX_TOTAL_PROPOSALS) return@forEach
                if (!file.delete()) {
                    throw WorkspaceChangeProposalException(
                        WorkspaceChangeProposalException.Reason.PERSISTENCE,
                    )
                }
                count -= 1
            }
        if (count >= MAX_TOTAL_PROPOSALS) {
            throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.LIMIT)
        }
    }

    private fun partitionDirectory(ref: LocalProjectRef, chat: ChatId): File = File(
        File(
            File(File(changesRoot, "v1"), "i-" + sha256Hex(ref.instanceId.value.toByteArray(Charsets.UTF_8))),
            "g-" + ref.generation.value,
        ),
        "c-" + sha256Hex(chat.value.toByteArray(Charsets.UTF_8)),
    )

    private fun fileFor(record: WorkspaceChangeProposalRecord): File = File(
        partitionDirectory(record.ref, record.chat),
        proposalFileName(record.id, record.command),
    )

    private fun proposalFileName(id: ApprovalRequestId, command: CommandId): String =
        "p-" + sha256Hex(id.value.toByteArray(Charsets.UTF_8)) +
            "-c-" + sha256Hex(command.value.toByteArray(Charsets.UTF_8)) + ".json"

    private fun isProposalFile(name: String): Boolean =
        name.startsWith("p-") && name.endsWith(".json")

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64L * 1024).toInt())
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) {
                throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.CORRUPT)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw WorkspaceChangeProposalException(WorkspaceChangeProposalException.Reason.CORRUPT)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val MAX_TOTAL_PROPOSALS = 64
    }
}
