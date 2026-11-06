package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ConflictPolicy
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.MixedApproval
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalBinding
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMember
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberKind
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberReceipt
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberState
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRejection
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRequest
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * One prepared mixed approval. Only child references and digests are stored; child bodies stay
 * with their own proposal stores and project files.
 */
@Entity(
    tableName = "native_mixed_approvals",
    indices = [
        Index(value = ["commandId"], unique = true),
        Index(value = ["instanceId", "generation", "chatId", "state"]),
        Index(value = ["instanceId", "generation", "chatId", "createdAt"]),
    ],
)
internal data class NativeMixedApprovalRow(
    @PrimaryKey val id: String,
    val commandId: String,
    val instanceId: String,
    val generation: Long,
    val projectId: String,
    val chatId: String,
    val runId: String,
    val baseRevision: Long,
    val policy: String,
    val digest: String,
    val count: Int,
    val state: String,
    val binding: String?,
    val claimedAt: Long?,
    val settledAt: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "native_mixed_members",
    primaryKeys = ["approvalId", "position"],
    indices = [
        Index(value = ["approvalId", "kind", "childId"], unique = true),
        Index(value = ["childCommandId"], unique = true),
        Index(value = ["kind", "childId"]),
    ],
)
internal data class NativeMixedMemberRow(
    val approvalId: String,
    val position: Int,
    val kind: String,
    val childId: String,
    val childDigest: String,
    val childCommandId: String,
    val selection: String,
    val selectionDigest: String,
    val expectedFingerprints: String,
    val workspaceRootIdentity: String?,
    val state: String,
    val receiptDigest: String?,
    val rejection: String?,
)

@Dao
internal interface NativeMixedApprovalDao {
    @Insert fun insert(row: NativeMixedApprovalRow)
    @Insert fun insertMembers(rows: List<NativeMixedMemberRow>)

    @Query("SELECT * FROM native_mixed_approvals WHERE id=:id")
    fun find(id: String): NativeMixedApprovalRow?

    @Query("SELECT * FROM native_mixed_approvals WHERE commandId=:command")
    fun byCommand(command: String): NativeMixedApprovalRow?

    @Query("SELECT * FROM native_mixed_approvals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state IN ('PREPARED','PENDING','CONSUMED') ORDER BY createdAt,id LIMIT 2")
    fun active(instance: String, generation: Long, chat: String): List<NativeMixedApprovalRow>

    @Query("SELECT * FROM native_mixed_approvals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat ORDER BY createdAt DESC,id DESC LIMIT :limit")
    fun recent(instance: String, generation: Long, chat: String, limit: Int): List<NativeMixedApprovalRow>

    @Query("SELECT * FROM native_mixed_members WHERE approvalId=:id ORDER BY position")
    fun members(id: String): List<NativeMixedMemberRow>

    @Query("SELECT * FROM native_mixed_members WHERE childCommandId=:command LIMIT 1")
    fun memberByChildCommand(command: String): NativeMixedMemberRow?

    @Query("UPDATE native_mixed_approvals SET state='CONSUMED',binding=:binding,claimedAt=:at WHERE id=:id AND state='PENDING' AND binding IS NULL AND claimedAt IS NULL")
    fun consume(id: String, binding: String, at: Long): Int

    @Query("UPDATE native_mixed_approvals SET state='PENDING' WHERE id=:id AND state='PREPARED' AND binding IS NULL AND claimedAt IS NULL AND settledAt IS NULL")
    fun activate(id: String): Int

    @Query("UPDATE native_mixed_approvals SET state=:state,settledAt=:at WHERE id=:id AND state=:expected AND settledAt IS NULL")
    fun settle(id: String, expected: String, state: String, at: Long): Int

    @Query("UPDATE native_mixed_members SET state=:state,receiptDigest=:receipt,rejection=:rejection WHERE approvalId=:id AND position=:position AND state='PENDING'")
    fun settleMember(id: String, position: Int, state: String, receipt: String?, rejection: String?): Int
}

enum class MixedApprovalPreparationConflict { IDENTITY, COMMAND, CHILD_COMMAND, BUSY }

sealed interface MixedApprovalPreparation {
    data class Ready(val approval: MixedApproval, val created: Boolean) : MixedApprovalPreparation
    data class Conflict(val reason: MixedApprovalPreparationConflict) : MixedApprovalPreparation
}

enum class MixedApprovalRefusalReason {
    IDENTITY,
    COMMAND,
    BINDING,
    TIME,
    NOT_PENDING,
    NOT_PREPARED,
    ALREADY_SETTLED,
    STATE,
    TARGET_STATE,
    INDETERMINATE,
    RECEIPT_UNKNOWN_MEMBER,
    RECEIPT_DUPLICATE,
    RECEIPT_CHILD_DIGEST,
    RECEIPT_CHILD_COMMAND,
    RECEIPT_SELECTION,
    RECEIPT_BEFORE_CONSUME,
    RECEIPT_MISSING,
}

sealed interface MixedApprovalConsumption {
    data class Won(val approval: MixedApproval) : MixedApprovalConsumption
    data class AlreadyConsumed(val approval: MixedApproval) : MixedApprovalConsumption
    data class Refused(
        val reason: MixedApprovalRefusalReason,
        val approval: MixedApproval? = null,
    ) : MixedApprovalConsumption
    data class NotFound(val id: ApprovalRequestId) : MixedApprovalConsumption
}

sealed interface MixedApprovalSettlement {
    data class Settled(val approval: MixedApproval) : MixedApprovalSettlement
    data class AlreadySettled(val approval: MixedApproval) : MixedApprovalSettlement
    data class Refused(
        val reason: MixedApprovalRefusalReason,
        val approval: MixedApproval? = null,
    ) : MixedApprovalSettlement
    data class NotFound(val id: ApprovalRequestId) : MixedApprovalSettlement
}

sealed interface MixedApprovalActivation {
    data class Activated(val approval: MixedApproval) : MixedApprovalActivation
    data class AlreadyActive(val approval: MixedApproval) : MixedApprovalActivation
    data class Refused(
        val reason: MixedApprovalRefusalReason,
        val approval: MixedApproval? = null,
    ) : MixedApprovalActivation
    data class NotFound(val id: ApprovalRequestId) : MixedApprovalActivation
}

/**
 * Durable mixed-approval parent and child references in Room v19.
 *
 * Preparation, consumption and settlement are each one Room transaction. The active parent is
 * unique per (instance, generation, chat), a parent command is unique, and a child command can
 * belong to only one mixed member. Consuming a parent binds the exact parent command and digest;
 * a second consume returns the stored consumed record, never a new authorization. Child members
 * have no independent consume API: only the parent can carry them to a terminal state, and
 * [referencingParent] lets a child store detect mixed membership before deciding anything.
 */
class MixedApprovalStore(context: Context) {
    private val db = NativeAppDatabase.open(context.applicationContext)
    private val mutex = Mutex()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            block()
        }
    }

    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    suspend fun prepare(
        request: MixedApprovalRequest,
        state: MixedApprovalState = MixedApprovalState.PENDING,
    ): MixedApprovalPreparation = io {
        require(state == MixedApprovalState.PREPARED || state == MixedApprovalState.PENDING) {
            "A mixed approval is prepared or pending"
        }
        transaction {
            val existingRow = db.mixedApprovals().find(request.id.value)
            if (existingRow != null) {
                val existing = decodeApproval(existingRow, db.mixedApprovals().members(existingRow.id))
                return@transaction if (existing.request.sameContent(request)) {
                    MixedApprovalPreparation.Ready(existing, created = false)
                } else {
                    MixedApprovalPreparation.Conflict(MixedApprovalPreparationConflict.IDENTITY)
                }
            }
            if (db.mixedApprovals().byCommand(request.commandId.value) != null ||
                db.mixedApprovals().memberByChildCommand(request.commandId.value) != null) {
                return@transaction MixedApprovalPreparation.Conflict(MixedApprovalPreparationConflict.COMMAND)
            }
            if (db.mixedApprovals().active(request.ref.instanceId.value, request.ref.generation.value,
                    request.chat.value).isNotEmpty()) {
                return@transaction MixedApprovalPreparation.Conflict(MixedApprovalPreparationConflict.BUSY)
            }
            for (member in request.members) {
                if (db.mixedApprovals().memberByChildCommand(member.childCommandId.value) != null ||
                    db.mixedApprovals().byCommand(member.childCommandId.value) != null) {
                    return@transaction MixedApprovalPreparation.Conflict(
                        MixedApprovalPreparationConflict.CHILD_COMMAND,
                    )
                }
            }
            val approval = MixedApproval(request, request.members, state, null, null, null)
            db.mixedApprovals().insert(parentRow(approval))
            db.mixedApprovals().insertMembers(memberRows(approval))
            MixedApprovalPreparation.Ready(approval, created = true)
        }
    }

    /** PREPARED -> PENDING; activation only makes the parent reviewable, not authorized. */
    suspend fun activate(
        approval: MixedApproval,
        at: Long = now(),
    ): MixedApprovalActivation = io {
        transaction {
            val row = db.mixedApprovals().find(approval.id.value)
                ?: return@transaction MixedApprovalActivation.NotFound(approval.id)
            val current = decodeApproval(row, db.mixedApprovals().members(row.id))
            if (!current.request.sameContent(approval.request)) {
                return@transaction MixedApprovalActivation.Refused(
                    MixedApprovalRefusalReason.IDENTITY, current,
                )
            }
            when (current.state) {
                MixedApprovalState.PENDING -> MixedApprovalActivation.AlreadyActive(current)
                MixedApprovalState.PREPARED -> {
                    if (at < current.createdAtMillis) {
                        return@transaction MixedApprovalActivation.Refused(
                            MixedApprovalRefusalReason.TIME, current,
                        )
                    }
                    check(db.mixedApprovals().activate(current.id.value) == 1) {
                        "Mixed approval state changed"
                    }
                    MixedApprovalActivation.Activated(MixedApproval(
                        current.request, current.members, MixedApprovalState.PENDING, null, null, null,
                    ))
                }
                else -> MixedApprovalActivation.Refused(
                    MixedApprovalRefusalReason.NOT_PREPARED, current,
                )
            }
        }
    }

    /** PENDING -> CONSUMED once, binding the exact command and prepared digest. */
    suspend fun consume(
        approval: MixedApproval,
        commandId: CommandId = approval.commandId,
        at: Long = now(),
    ): MixedApprovalConsumption = io {
        transaction {
            val row = db.mixedApprovals().find(approval.id.value)
                ?: return@transaction MixedApprovalConsumption.NotFound(approval.id)
            val current = decodeApproval(row, db.mixedApprovals().members(row.id))
            if (!current.request.sameContent(approval.request)) {
                return@transaction MixedApprovalConsumption.Refused(
                    MixedApprovalRefusalReason.IDENTITY, current,
                )
            }
            if (commandId != current.commandId) {
                return@transaction MixedApprovalConsumption.Refused(
                    MixedApprovalRefusalReason.COMMAND, current,
                )
            }
            if (at < current.createdAtMillis) {
                return@transaction MixedApprovalConsumption.Refused(MixedApprovalRefusalReason.TIME, current)
            }
            when (current.state) {
                MixedApprovalState.CONSUMED -> {
                    val binding = current.binding
                    if (binding == null || binding.commandId != commandId) {
                        MixedApprovalConsumption.Refused(MixedApprovalRefusalReason.BINDING, current)
                    } else {
                        MixedApprovalConsumption.AlreadyConsumed(current)
                    }
                }
                MixedApprovalState.PENDING -> {
                    val binding = MixedApprovalBinding(current.request)
                    check(db.mixedApprovals().consume(current.id.value, binding.fingerprint.sha256, at) == 1) {
                        "Mixed approval state changed"
                    }
                    MixedApprovalConsumption.Won(MixedApproval(
                        current.request, current.members, MixedApprovalState.CONSUMED,
                        binding, at, null,
                    ))
                }
                else -> MixedApprovalConsumption.Refused(
                    MixedApprovalRefusalReason.NOT_PENDING, current,
                )
            }
        }
    }

    /**
     * Settles only to a terminal state. Member receipts must match the member's child digest,
     * child command and selection digest; a missing receipt is never interpreted as success.
     */
    suspend fun settle(
        approval: MixedApproval,
        state: MixedApprovalState,
        receipts: List<MixedApprovalMemberReceipt> = emptyList(),
        at: Long = now(),
    ): MixedApprovalSettlement = io {
        require(state.terminal) { "A mixed approval can only settle to a terminal state" }
        transaction {
            val row = db.mixedApprovals().find(approval.id.value)
                ?: return@transaction MixedApprovalSettlement.NotFound(approval.id)
            val current = decodeApproval(row, db.mixedApprovals().members(row.id))
            if (!current.request.sameContent(approval.request)) {
                return@transaction MixedApprovalSettlement.Refused(
                    MixedApprovalRefusalReason.IDENTITY, current,
                )
            }
            val claimedAtMillis = current.claimedAtMillis
            if (at < current.createdAtMillis ||
                (claimedAtMillis != null && at < claimedAtMillis)) {
                return@transaction MixedApprovalSettlement.Refused(MixedApprovalRefusalReason.TIME, current)
            }
            if (current.state.terminal) {
                return@transaction if (current.state == state) {
                    MixedApprovalSettlement.AlreadySettled(current)
                } else {
                    MixedApprovalSettlement.Refused(MixedApprovalRefusalReason.ALREADY_SETTLED, current)
                }
            }
            val lookup = receiptLookup(current, receipts)
            if (lookup.reason != null) {
                return@transaction MixedApprovalSettlement.Refused(lookup.reason, current)
            }
            val byKey = requireNotNull(lookup.byKey)
            when (current.state) {
                MixedApprovalState.PREPARED, MixedApprovalState.PENDING -> {
                    if (receipts.isNotEmpty()) {
                        return@transaction MixedApprovalSettlement.Refused(
                            MixedApprovalRefusalReason.RECEIPT_BEFORE_CONSUME, current,
                        )
                    }
                    if (state != MixedApprovalState.REJECTED && state != MixedApprovalState.CANCELLED) {
                        return@transaction MixedApprovalSettlement.Refused(
                            MixedApprovalRefusalReason.TARGET_STATE, current,
                        )
                    }
                    val members = current.members.map { member ->
                        if (state == MixedApprovalState.REJECTED) {
                            member.withOutcome(
                                MixedApprovalMemberState.REJECTED,
                                rejection = MixedApprovalRejection.USER_REJECTED,
                            )
                        } else {
                            member.withOutcome(MixedApprovalMemberState.CANCELLED)
                        }
                    }
                    val next = nextApproval(current, members, state, at)
                        ?: return@transaction MixedApprovalSettlement.Refused(
                            MixedApprovalRefusalReason.TARGET_STATE, current,
                        )
                    persist(current, next)
                    MixedApprovalSettlement.Settled(next)
                }
                MixedApprovalState.CONSUMED -> {
                    if (receipts.isEmpty() &&
                        (state == MixedApprovalState.REJECTED || state == MixedApprovalState.CANCELLED)) {
                        return@transaction MixedApprovalSettlement.Refused(
                            MixedApprovalRefusalReason.INDETERMINATE, current,
                        )
                    }
                    val members = current.members.map { member ->
                        val receipt = byKey[member.identityKey]
                        when {
                            receipt != null -> member.applyReceipt(receipt)
                            state == MixedApprovalState.COMMITTED ->
                                return@transaction MixedApprovalSettlement.Refused(
                                    MixedApprovalRefusalReason.RECEIPT_MISSING, current,
                                )
                            state == MixedApprovalState.PARTIAL || state == MixedApprovalState.CONFLICT ->
                                member.withOutcome(MixedApprovalMemberState.INDETERMINATE)
                            else -> return@transaction MixedApprovalSettlement.Refused(
                                MixedApprovalRefusalReason.RECEIPT_MISSING, current,
                            )
                        }
                    }
                    if ((state == MixedApprovalState.REJECTED || state == MixedApprovalState.CANCELLED) &&
                        byKey.size != current.members.size) {
                        return@transaction MixedApprovalSettlement.Refused(
                            MixedApprovalRefusalReason.RECEIPT_MISSING, current,
                        )
                    }
                    val next = nextApproval(current, members, state, at)
                        ?: return@transaction MixedApprovalSettlement.Refused(
                            MixedApprovalRefusalReason.TARGET_STATE, current,
                        )
                    persist(current, next)
                    MixedApprovalSettlement.Settled(next)
                }
                else -> MixedApprovalSettlement.Refused(MixedApprovalRefusalReason.STATE, current)
            }
        }
    }

    /** Cancels an unconsumed parent. A consumed parent must be classified from receipts. */
    suspend fun cancel(
        approval: MixedApproval,
        at: Long = now(),
    ): MixedApprovalSettlement = settle(approval, MixedApprovalState.CANCELLED, emptyList(), at)

    suspend fun find(ref: LocalProjectRef, chat: ChatId, id: ApprovalRequestId): MixedApproval? = io {
        transaction {
            val row = db.mixedApprovals().find(id.value) ?: return@transaction null
            require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value &&
                row.projectId == ref.projectId.value && row.chatId == chat.value) {
                "Mixed approval belongs to another identity"
            }
            decodeApproval(row, db.mixedApprovals().members(row.id))
        }
    }

    suspend fun active(ref: LocalProjectRef, chat: ChatId): MixedApproval? = io {
        transaction {
            val rows = db.mixedApprovals().active(ref.instanceId.value, ref.generation.value, chat.value)
            require(rows.size <= 1) { "Multiple active mixed approvals" }
            rows.singleOrNull()?.let { row ->
                require(row.projectId == ref.projectId.value) { "Mixed approval belongs to another project" }
                decodeApproval(row, db.mixedApprovals().members(row.id))
            }
        }
    }

    suspend fun recent(ref: LocalProjectRef, chat: ChatId, limit: Int = 50): List<MixedApproval> = io {
        require(limit in 1..100) { "Invalid mixed approval query limit" }
        transaction {
            db.mixedApprovals().recent(ref.instanceId.value, ref.generation.value, chat.value, limit)
                .map { row ->
                    require(row.projectId == ref.projectId.value) {
                        "Mixed approval belongs to another project"
                    }
                    decodeApproval(row, db.mixedApprovals().members(row.id))
                }
        }
    }

    /** Finds the parent that references a child command, if any. Used to block child-only consumption. */
    suspend fun referencingParent(childCommandId: CommandId): MixedApproval? = io {
        transaction {
            val member = db.mixedApprovals().memberByChildCommand(childCommandId.value)
                ?: return@transaction null
            val row = requireNotNull(db.mixedApprovals().find(member.approvalId)) {
                "Mixed parent is missing"
            }
            decodeApproval(row, db.mixedApprovals().members(row.id))
        }
    }

    suspend fun close() = io { db.close() }

    private fun nextApproval(
        current: MixedApproval,
        members: List<MixedApprovalMember>,
        state: MixedApprovalState,
        at: Long,
    ): MixedApproval? = try {
        MixedApproval(current.request, members, state, current.binding, current.claimedAtMillis, at)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun persist(current: MixedApproval, next: MixedApproval) {
        check(db.mixedApprovals().settle(next.id.value, current.state.name, next.state.name,
            requireNotNull(next.settledAtMillis)) == 1) { "Mixed approval state changed" }
        next.members.forEachIndexed { position, member ->
            check(db.mixedApprovals().settleMember(next.id.value, position, member.state.name,
                member.receiptDigest?.sha256, member.rejection?.name) == 1) {
                "Mixed member state changed"
            }
        }
    }

    private class ReceiptLookup(
        val byKey: Map<String, MixedApprovalMemberReceipt>?,
        val reason: MixedApprovalRefusalReason?,
    )

    private fun receiptLookup(
        approval: MixedApproval,
        receipts: List<MixedApprovalMemberReceipt>,
    ): ReceiptLookup {
        val members = approval.members.associateBy { it.identityKey }
        val byKey = LinkedHashMap<String, MixedApprovalMemberReceipt>(receipts.size)
        receipts.forEach { receipt ->
            val member = members[receipt.identityKey]
                ?: return ReceiptLookup(null, MixedApprovalRefusalReason.RECEIPT_UNKNOWN_MEMBER)
            if (byKey.put(receipt.identityKey, receipt) != null) {
                return ReceiptLookup(null, MixedApprovalRefusalReason.RECEIPT_DUPLICATE)
            }
            if (receipt.childDigest != member.childDigest) {
                return ReceiptLookup(null, MixedApprovalRefusalReason.RECEIPT_CHILD_DIGEST)
            }
            if (receipt.childCommandId != member.childCommandId) {
                return ReceiptLookup(null, MixedApprovalRefusalReason.RECEIPT_CHILD_COMMAND)
            }
            if (receipt.selectionDigest != member.selectionDigest) {
                return ReceiptLookup(null, MixedApprovalRefusalReason.RECEIPT_SELECTION)
            }
        }
        return ReceiptLookup(byKey, null)
    }

    private fun parentRow(approval: MixedApproval) = NativeMixedApprovalRow(
        id = approval.id.value,
        commandId = approval.commandId.value,
        instanceId = approval.ref.instanceId.value,
        generation = approval.ref.generation.value,
        projectId = approval.ref.projectId.value,
        chatId = approval.chat.value,
        runId = approval.run.value,
        baseRevision = approval.baseRevision,
        policy = approval.policy.name,
        digest = approval.digest.sha256,
        count = approval.members.size,
        state = approval.state.name,
        binding = approval.binding?.fingerprint?.sha256,
        claimedAt = approval.claimedAtMillis,
        settledAt = approval.settledAtMillis,
        createdAt = approval.createdAtMillis,
    )

    private fun memberRows(approval: MixedApproval): List<NativeMixedMemberRow> =
        approval.members.mapIndexed { position, member ->
            NativeMixedMemberRow(
                approvalId = approval.id.value,
                position = position,
                kind = member.kind.name,
                childId = member.childId,
                childDigest = member.childDigest.sha256,
                childCommandId = member.childCommandId.value,
                selection = encodeSelection(member.selection),
                selectionDigest = member.selectionDigest.sha256,
                expectedFingerprints = encodeFingerprints(member.expectedFingerprints),
                workspaceRootIdentity = member.workspaceRootIdentity,
                state = member.state.name,
                receiptDigest = member.receiptDigest?.sha256,
                rejection = member.rejection?.name,
            )
        }

    private fun decodeApproval(
        row: NativeMixedApprovalRow,
        memberRows: List<NativeMixedMemberRow>,
    ): MixedApproval {
        val request = decodeRequest(row, memberRows)
        val members = memberRows.map(::member)
        val binding = row.binding?.let { stored ->
            val value = MixedApprovalBinding(request)
            require(value.fingerprint.sha256 == stored) { "Mixed approval binding changed" }
            value
        }
        return MixedApproval(request, members, MixedApprovalState.valueOf(row.state), binding,
            row.claimedAt, row.settledAt)
    }

    private fun decodeRequest(
        row: NativeMixedApprovalRow,
        memberRows: List<NativeMixedMemberRow>,
    ): MixedApprovalRequest {
        require(row.count in 1..MixedApprovalRequest.MAX_MEMBERS) { "Invalid mixed approval count" }
        require(memberRows.size == row.count) { "Mixed approval member count changed" }
        require(memberRows.all { it.approvalId == row.id }) { "Mixed approval member parent changed" }
        require(memberRows.mapIndexed { index, member -> member.position == index }.all { it }) {
            "Mixed approval member order changed"
        }
        val members = memberRows.map(::member)
        require(members.zipWithNext().all { (left, right) ->
            left.kind.ordinal < right.kind.ordinal ||
                (left.kind == right.kind && left.childId < right.childId)
        }) { "Mixed approval member order changed" }
        val request = MixedApprovalRequest(
            id = ApprovalRequestId(row.id),
            commandId = CommandId(row.commandId),
            ref = LocalProjectRef(ProjectId(row.projectId), ProjectInstanceId(row.instanceId),
                RecoveryGeneration(row.generation)),
            chat = ChatId(row.chatId),
            run = RunId(row.runId),
            baseRevision = row.baseRevision,
            policy = ConflictPolicy.valueOf(row.policy),
            members = members.map { it.asPending() },
            createdAtMillis = row.createdAt,
        )
        require(request.digest.sha256 == row.digest) { "Mixed approval digest changed" }
        return request
    }

    private fun member(row: NativeMixedMemberRow): MixedApprovalMember {
        val member = MixedApprovalMember(
            kind = MixedApprovalMemberKind.valueOf(row.kind),
            childId = row.childId,
            childDigest = ObjectFingerprint(row.childDigest),
            childCommandId = CommandId(row.childCommandId),
            selection = decodeSelection(row.selection),
            expectedFingerprints = decodeFingerprints(row.expectedFingerprints),
            workspaceRootIdentity = row.workspaceRootIdentity,
            state = MixedApprovalMemberState.valueOf(row.state),
            receiptDigest = row.receiptDigest?.let { ObjectFingerprint(it) },
            rejection = row.rejection?.let { MixedApprovalRejection.valueOf(it) },
        )
        require(member.selectionDigest == ObjectFingerprint(row.selectionDigest)) {
            "Mixed member selection digest changed"
        }
        return member
    }

    private fun encodeSelection(selection: List<String>): String =
        JsonArray(selection.map { JsonPrimitive(it) }).toString()

    private fun encodeFingerprints(fingerprints: List<ObjectFingerprint>): String =
        JsonArray(fingerprints.map { JsonPrimitive(it.sha256) }).toString()

    private fun decodeSelection(encoded: String): List<String> {
        require(encoded.length <=
            MixedApprovalMember.MAX_SELECTION * (MixedApprovalMember.MAX_SELECTION_ENTRY * 2 + 16)) {
            "Invalid mixed selection"
        }
        val element = try {
            Json.parseToJsonElement(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid mixed selection")
        }
        val array = element as? JsonArray ?: throw IllegalArgumentException("Invalid mixed selection")
        require(array.size <= MixedApprovalMember.MAX_SELECTION) { "Invalid mixed selection" }
        return array.map { raw ->
            val value = raw as? JsonPrimitive ?: throw IllegalArgumentException("Invalid mixed selection")
            require(value.isString && value.content.length <= MixedApprovalMember.MAX_SELECTION_ENTRY)
            value.content
        }
    }

    private fun decodeFingerprints(encoded: String): List<ObjectFingerprint> {
        require(encoded.length <= MixedApprovalMember.MAX_SELECTION * 68) {
            "Invalid mixed fingerprints"
        }
        val element = try {
            Json.parseToJsonElement(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid mixed fingerprints")
        }
        val array = element as? JsonArray ?: throw IllegalArgumentException("Invalid mixed fingerprints")
        require(array.size <= MixedApprovalMember.MAX_SELECTION) { "Invalid mixed fingerprints" }
        return array.map { raw ->
            val value = raw as? JsonPrimitive ?: throw IllegalArgumentException("Invalid mixed fingerprints")
            require(value.isString)
            ObjectFingerprint(value.content)
        }
    }

    private fun now(): Long = System.currentTimeMillis().coerceAtLeast(0)
}
