package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Immutable Room proposals plus approval-consumption binding; no transaction spans stores. */
class NativeItemProposalStore(context: Context, private val projects: NativeTextProjectStore,
                              private val runtime: RuntimePort) {
    private val db = NativeAppDatabase.open(context.applicationContext)
    private val mutex = Mutex()
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }
    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    suspend fun prepare(lease: RunLease, ref: LocalProjectRef, patchJson: String,
                        policy: ConflictPolicy, expectedRevision: Long? = null): ItemProposalSummary = io {
        require(lease.identity.projectId == ref.projectId && runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            val patches = ItemProposalCodec.patches(patchJson)
            val snapshot = projects.open(ref)
            require(expectedRevision == null || snapshot.revision == expectedRevision) { "Script snapshot became stale" }
            val members = patches.map { patch ->
                currentCoroutineContext().ensureActive()
                val item = requireNotNull(projects.item(ref, ItemProposalCodec.id(patch)))
                require(item.revision == snapshot.revision) { "Project changed while preparing" }
                ItemProposalCodec.patch(item.item, patch)
            }
            require(projects.open(ref).revision == snapshot.revision)
            val proposal = ItemChangeProposal(ApprovalRequestId(UUID.randomUUID().toString()), ref,
                requireNotNull(lease.identity.chatId), lease.identity.runId, CommandId(UUID.randomUUID().toString()),
                snapshot.revision, policy, members)
            val row = NativeItemProposalRow(proposal.id.value, ref.instanceId.value, ref.generation.value, ref.projectId.value,
                proposal.chat.value, proposal.run.value, proposal.command.value, proposal.revision, policy.name,
                proposal.digest.sha256, members.size, "PENDING", null, null, null, System.currentTimeMillis().coerceAtLeast(0))
            transaction {
                val chat = requireNotNull(db.agent().chat(proposal.chat.value))
                require(chat.instanceId == ref.instanceId.value && chat.generation == ref.generation.value)
                check(db.agent().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value) == null)
                check(db.itemProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.policyProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.pdfPageProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.assetImportProposals().unresolved(ref.instanceId.value, ref.generation.value,
                    proposal.chat.value).isEmpty())
                db.itemProposals().insert(row)
                db.itemProposals().insertMembers(members.mapIndexed { index, member ->
                    NativeItemProposalMemberRow(row.id, member.id.value, index, member.row, member.assetId,
                        member.before.translation != member.after.translation,
                        member.before.nameSource != member.after.nameSource || member.before.nameTranslation != member.after.nameTranslation,
                        member.before.status != member.after.status, member.json().toString())
                })
            }
            summary(row, ref)
        } finally { runtime.endOperation(operation) }
    }

    suspend fun recent(ref: LocalProjectRef, chat: ChatId): List<ItemProposalSummary> = io {
        projects.open(ref)
        db.itemProposals().recent(ref.instanceId.value, ref.generation.value, chat.value).map { summary(it, ref) }
    }

    suspend fun pending(ref: LocalProjectRef, chat: ChatId): ItemProposalSummary? = io {
        projects.open(ref)
        val rows = db.itemProposals().unresolved(ref.instanceId.value, ref.generation.value, chat.value)
        require(rows.size <= 1) { "Multiple unresolved proposals" }
        rows.singleOrNull()?.let { summary(it, ref) }
    }

    suspend fun find(ref: LocalProjectRef, chat: ChatId, id: ApprovalRequestId): ItemProposalSummary = io {
        projects.open(ref)
        summary(requireNotNull(db.itemProposals().find(id.value)), ref).also { require(it.chat == chat) }
    }

    suspend fun receipt(view: ItemProposalSummary): ItemProposalReceipt? = io {
        val row = verifyView(view)
        if (row.binding == null) return@io null
        val proposal = payload(row, view.ref)
        val selected = selection(requireNotNull(row.selected))
        require(row.binding == proposal.binding(selected).sha256)
        projects.approvedItemsReceipt(proposal, selected)
    }

    /** Read-only mixed-approval loader; it never consumes, settles or writes the proposal. */
    internal suspend fun loadMixedProposal(ref: LocalProjectRef, chat: ChatId,
                                           id: ApprovalRequestId): ItemChangeProposal = io {
        projects.open(ref)
        val row = requireNotNull(db.itemProposals().find(id.value))
        require(row.chatId == chat.value)
        payload(row, ref)
    }

    /** Frozen selection of a claimed proposal, checked against the exact stored binding. */
    internal suspend fun loadMixedSelection(ref: LocalProjectRef, chat: ChatId,
                                            id: ApprovalRequestId): Set<ItemId> = io {
        projects.open(ref)
        val row = requireNotNull(db.itemProposals().find(id.value))
        require(row.chatId == chat.value)
        val proposal = payload(row, ref)
        val encoded = requireNotNull(row.selected) { "Proposal selection is not frozen" }
        selection(encoded).also { selected ->
            require(row.binding == proposal.binding(selected).sha256) { "Approval selection mismatch" }
        }
    }

    suspend fun members(view: ItemProposalSummary): List<ItemProposalMemberSummary> = io {
        verifyView(view)
        db.itemProposals().memberList(view.id.value).also { require(it.size == view.count) }.map {
            ItemProposalMemberSummary(ItemId(it.itemId), it.rowIndex, it.assetId, it.textChanged, it.namesChanged, it.statusChanged)
        }
    }

    suspend fun detail(view: ItemProposalSummary, item: ItemId): ItemProposalMember = io {
        verifyView(view)
        val stored = requireNotNull(db.itemProposals().member(view.id.value, item.value))
        val member = ItemProposalCodec.member(stored.payload)
        require(member.id == item && member.row == stored.rowIndex && member.assetId == stored.assetId)
        member
    }

    suspend fun reject(view: ItemProposalSummary) = io {
        val row = verifyView(view)
        transaction {
            if (row.state == "PENDING") check(db.itemProposals().settle(row.id, "PENDING", "REJECTED") == 1)
            else require(row.state == "REJECTED")
        }
    }

    suspend fun apply(lease: RunLease, view: ItemProposalSummary, selection: Set<ItemId>): ItemProposalState = io {
        val row = verifyView(view)
        require(lease.identity.projectId == view.ref.projectId && lease.identity.chatId == view.chat &&
            lease.identity.runId == view.run && lease.identity.commandId == view.command &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        var consumed = false
        try {
            val proposal = payload(row, view.ref)
            val selected = selection.toSet()
            val binding = proposal.binding(selected)
            val encodedSelection = encodeSelection(selected)
            if (row.binding != null) require(row.binding == binding.sha256 && row.selected == encodedSelection)
            if (row.state !in setOf("PENDING", "CONSUMED")) return@io ItemProposalState.valueOf(row.state)
            currentCoroutineContext().ensureActive()
            val newlyConsumed = row.state == "PENDING"
            transaction {
                if (newlyConsumed) check(db.itemProposals().consume(row.id, encodedSelection, binding.sha256,
                    System.currentTimeMillis().coerceAtLeast(0)) == 1)
                else require(row.binding == binding.sha256 && row.selected == encodedSelection)
            }
            consumed = true
            // Re-entry after a previous executor ended can only recover a committed receipt.
            // It does not turn an old consumed intent into a new authorization.
            val previous = projects.approvedItemsReceipt(proposal, selected)
            val result = if (previous != null) previous.state else if (!newlyConsumed) ItemProposalState.CANCELLED
                else projects.approvedItems(lease, proposal, selected).state
            withContext(NonCancellable) { transaction { check(db.itemProposals().settle(row.id, "CONSUMED", result.name) == 1) } }
            result
        } catch (failure: Exception) {
            if (consumed) withContext(NonCancellable) { runCatching { reconcileConsumed(row.id, view.ref) } }
            throw failure
        } finally { runtime.endOperation(operation) }
    }

    /** No replay. A real idle reservation excludes a previous executor before receipt lookup. */
    suspend fun reconcile(ref: LocalProjectRef, chat: ChatId) = io {
        if (runtime.snapshot().activeRun != null) return@io
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            db.itemProposals().unresolved(ref.instanceId.value, ref.generation.value, chat.value)
                .filter { it.state == "CONSUMED" }.forEach { reconcileConsumed(it.id, ref) }
        } finally { runtime.endProjectWrite(write) }
    }

    private suspend fun reconcileConsumed(id: String, ref: LocalProjectRef) {
        val row = requireNotNull(db.itemProposals().find(id))
        if (row.state != "CONSUMED") return
        val proposal = payload(row, ref)
        val selected = selection(requireNotNull(row.selected))
        require(row.binding == proposal.binding(selected).sha256)
        val receipt = projects.approvedItemsReceipt(proposal, selected)
        transaction { check(db.itemProposals().settle(row.id, "CONSUMED", receipt?.state?.name ?: "CANCELLED") == 1) }
    }

    private suspend fun verifyView(view: ItemProposalSummary): NativeItemProposalRow {
        projects.open(view.ref)
        val row = requireNotNull(db.itemProposals().find(view.id.value))
        val verified = summary(row, view.ref)
        require(verified.chat == view.chat && verified.run == view.run && verified.command == view.command &&
            verified.digest == view.digest && verified.revision == view.revision && verified.policy == view.policy && verified.count == view.count)
        return row
    }

    private fun payload(row: NativeItemProposalRow, ref: LocalProjectRef): ItemChangeProposal {
        summary(row, ref)
        val members = db.itemProposals().members(row.id)
        require(members.size == row.count && members.map { it.position } == members.indices.toList())
        val proposal = ItemChangeProposal(ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId),
            CommandId(row.commandId), row.revision, ConflictPolicy.valueOf(row.policy), members.map { stored ->
                ItemProposalCodec.member(stored.payload).also {
                    require(it.id.value == stored.itemId && it.row == stored.rowIndex && it.assetId == stored.assetId)
                }
            })
        require(proposal.digest.sha256 == row.digest) { "Proposal payload mismatch" }
        return proposal
    }

    private fun summary(row: NativeItemProposalRow, ref: LocalProjectRef): ItemProposalSummary {
        require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value && row.projectId == ref.projectId.value)
        require(row.count in 1..ItemChangeProposal.MAX_MEMBERS && row.revision >= 0)
        require((row.binding == null) == (row.selected == null) && (row.binding == null) == (row.consumedAt == null))
        require(row.consumedAt == null || row.consumedAt >= 0)
        return ItemProposalSummary(ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId), CommandId(row.commandId),
            row.revision, ConflictPolicy.valueOf(row.policy), ObjectFingerprint(row.digest), row.count, ItemProposalState.valueOf(row.state))
    }

    private fun encodeSelection(ids: Set<ItemId>) = buildJsonObject {
        put("items", JsonArray(ids.sortedBy { it.value }.map { JsonPrimitive(it.value) }))
    }.toString()
    private fun selection(encoded: String): Set<ItemId> {
        val json = JsonObjectValue.parse(encoded, JsonInputLimits(64 * 1024, 4)).json
        require(json.keys == setOf("items"))
        val ids = (json["items"] as? JsonArray ?: error("Selection required")).map { raw ->
            ItemProposalCodec.id(buildJsonObject { put("item_id", raw) })
        }
        require(ids.size in 1..ItemChangeProposal.MAX_MEMBERS && ids.toSet().size == ids.size)
        return ids.toSet()
    }
    suspend fun close() = io { db.close() }
}
