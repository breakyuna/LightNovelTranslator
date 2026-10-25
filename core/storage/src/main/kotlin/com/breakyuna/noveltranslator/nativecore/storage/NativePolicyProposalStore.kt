package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-local, single-consumption authority for one complete rule/prompt policy. */
class NativePolicyProposalStore(context: Context, private val projects: NativeTextProjectStore,
                                private val runtime: RuntimePort) {
    private val db = NativeAppDatabase.open(context.applicationContext)
    private val mutex = Mutex()
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }
    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })
    suspend fun saved(ref: LocalProjectRef): ProjectPolicySnapshot = io { projects.textPolicy(ref) }

    suspend fun prepare(lease: RunLease, ref: LocalProjectRef, base: ObjectFingerprint,
                        after: ProjectTextPolicy): PolicyApprovalSummary = io {
        require(lease.identity.projectId == ref.projectId && runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            val coroutine = currentCoroutineContext()
            TextRuleValidation.validate(after) { coroutine.ensureActive() }
            val before = projects.textPolicy(ref)
            require(before.fingerprint == base) { "Policy changed while preparing" }
            val proposal = PolicyChangeProposal(ApprovalRequestId(UUID.randomUUID().toString()), ref,
                requireNotNull(lease.identity.chatId), lease.identity.runId, CommandId(UUID.randomUUID().toString()),
                before.revision, before.policy, after)
            currentCoroutineContext().ensureActive()
            transaction {
                val chat = requireNotNull(db.agent().chat(proposal.chat.value))
                require(chat.instanceId == ref.instanceId.value && chat.generation == ref.generation.value)
                check(db.agent().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value) == null)
                check(db.itemProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.policyProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.pdfPageProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.assetImportProposals().unresolved(ref.instanceId.value, ref.generation.value,
                    proposal.chat.value).isEmpty())
                db.policyProposals().insert(NativePolicyProposalRow(proposal.id.value, ref.instanceId.value, ref.generation.value,
                    ref.projectId.value, proposal.chat.value, proposal.run.value, proposal.command.value, proposal.revision,
                    proposal.digest.sha256, proposal.beforeText, proposal.afterText, proposal.added, proposal.updated,
                    proposal.deleted, proposal.reordered, proposal.promptChanged, "PENDING", System.currentTimeMillis().coerceAtLeast(0), null))
            }
            proposal.summary(TextApprovalState.PENDING)
        } finally { runtime.endOperation(operation) }
    }

    /** Creates a fresh pending authority for the user's selected difference. Never applies it. */
    suspend fun select(view: PolicyApprovalSummary, ruleIds: Set<String>, acceptOrder: Boolean,
                       acceptPrompt: Boolean): PolicyApprovalSummary = io {
        val write = runtime.beginProjectWrite()
        try {
            val (row, original) = verified(view)
            require(row.state == "PENDING" && row.consumedAt == null) { "Proposal already resolved" }
            val current = projects.textPolicy(view.ref)
            require(current.revision == original.revision && current.fingerprint == original.before.fingerprint())
            val after = original.selectedPolicy(ruleIds, acceptOrder, acceptPrompt)
            require(after.fingerprint() != original.before.fingerprint()) { "Empty selection" }
            val coroutine = currentCoroutineContext()
            TextRuleValidation.validate(after) { coroutine.ensureActive() }
            if (after.fingerprint() == original.after.fingerprint()) return@io view
            val selected = PolicyChangeProposal(ApprovalRequestId(UUID.randomUUID().toString()), view.ref,
                view.chat, view.run, CommandId(UUID.randomUUID().toString()), view.revision, original.before, after)
            currentCoroutineContext().ensureActive()
            transaction {
                check(db.policyProposals().settle(row.id, "PENDING", "REJECTED") == 1)
                db.policyProposals().insert(NativePolicyProposalRow(selected.id.value, view.ref.instanceId.value,
                    view.ref.generation.value, view.ref.projectId.value, view.chat.value, view.run.value,
                    selected.command.value, selected.revision, selected.digest.sha256, selected.beforeText,
                    selected.afterText, selected.added, selected.updated, selected.deleted, selected.reordered,
                    selected.promptChanged, "PENDING", System.currentTimeMillis().coerceAtLeast(0), null))
            }
            selected.summary(TextApprovalState.PENDING)
        } finally { runtime.endProjectWrite(write) }
    }

    suspend fun recent(ref: LocalProjectRef, chat: ChatId): List<PolicyApprovalSummary> = io {
        projects.open(ref)
        db.policyProposals().recent(ref.instanceId.value, ref.generation.value, chat.value).map { summary(it, ref) }
    }
    suspend fun pending(ref: LocalProjectRef, chat: ChatId): PolicyApprovalSummary? = io {
        projects.open(ref)
        val rows = db.policyProposals().unresolved(ref.instanceId.value, ref.generation.value, chat.value)
        require(rows.size <= 1)
        rows.singleOrNull()?.let { summary(it, ref) }
    }
    suspend fun find(ref: LocalProjectRef, chat: ChatId, id: ApprovalRequestId): PolicyApprovalSummary = io {
        projects.open(ref)
        val row = requireNotNull(db.policyProposals().find(id.value))
        payload(row, ref).also { require(it.chat == chat) }.summary(TextApprovalState.valueOf(row.state))
    }
    suspend fun detail(view: PolicyApprovalSummary): PolicyChangeProposal = io { verified(view).second }
    suspend fun receipt(view: PolicyApprovalSummary): PolicyEditReceipt? = io {
        val (row, proposal) = verified(view)
        if (row.consumedAt == null) null else projects.textPolicyReceipt(proposal.intent())
    }
    suspend fun reject(view: PolicyApprovalSummary) = io {
        val (row, _) = verified(view)
        transaction {
            if (row.state == "PENDING") check(db.policyProposals().settle(row.id, "PENDING", "REJECTED") == 1)
            else require(row.state == "REJECTED")
        }
    }
    suspend fun apply(lease: RunLease, view: PolicyApprovalSummary): TextApprovalState = io {
        val (row, proposal) = verified(view)
        require(lease.identity.projectId == view.ref.projectId && lease.identity.chatId == view.chat &&
            lease.identity.runId == view.run && lease.identity.commandId == view.command &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        var consumed = false
        try {
            if (row.state !in setOf("PENDING", "CONSUMED")) return@io TextApprovalState.valueOf(row.state)
            val newlyConsumed = row.state == "PENDING"
            currentCoroutineContext().ensureActive()
            transaction {
                if (newlyConsumed) check(db.policyProposals().consume(row.id, System.currentTimeMillis().coerceAtLeast(0)) == 1)
                else require(row.consumedAt != null)
            }
            consumed = true
            val existing = projects.textPolicyReceipt(proposal.intent())
            val result = if (existing != null) state(existing) else if (!newlyConsumed) TextApprovalState.CANCELLED
                else state(projects.approvedPolicy(lease, proposal))
            withContext(NonCancellable) { transaction { check(db.policyProposals().settle(row.id, "CONSUMED", result.name) == 1) } }
            result
        } catch (error: Exception) {
            if (consumed) withContext(NonCancellable) { runCatching { reconcileConsumed(row.id, view.ref) } }
            throw error
        } finally { runtime.endOperation(operation) }
    }
    suspend fun reconcile(ref: LocalProjectRef, chat: ChatId) = io {
        if (runtime.snapshot().activeRun != null) return@io
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            db.policyProposals().unresolved(ref.instanceId.value, ref.generation.value, chat.value)
                .filter { it.state == "CONSUMED" }.forEach { reconcileConsumed(it.id, ref) }
        } finally { runtime.endProjectWrite(write) }
    }
    private suspend fun reconcileConsumed(id: String, ref: LocalProjectRef) {
        val row = requireNotNull(db.policyProposals().find(id))
        if (row.state != "CONSUMED") return
        val proposal = payload(row, ref)
        val receipt = projects.textPolicyReceipt(proposal.intent())
        transaction { check(db.policyProposals().settle(row.id, "CONSUMED", receipt?.let(::state)?.name ?: "CANCELLED") == 1) }
    }
    private fun state(receipt: PolicyEditReceipt) =
        if (receipt.status == PolicyEditStatus.COMMITTED) TextApprovalState.COMMITTED else TextApprovalState.CONFLICT
    private suspend fun verified(view: PolicyApprovalSummary): Pair<NativePolicyProposalRow, PolicyChangeProposal> {
        projects.open(view.ref)
        val row = requireNotNull(db.policyProposals().find(view.id.value))
        val proposal = payload(row, view.ref)
        require(proposal.chat == view.chat && proposal.run == view.run && proposal.command == view.command &&
            proposal.revision == view.revision && proposal.digest == view.digest)
        return row to proposal
    }
    private fun payload(row: NativePolicyProposalRow, ref: LocalProjectRef): PolicyChangeProposal {
        require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value && row.projectId == ref.projectId.value)
        require(row.beforeText.length <= ProjectTextPolicyCodec.MAX_CHARS && row.afterText.length <= ProjectTextPolicyCodec.MAX_CHARS)
        require(row.createdAt >= 0 && (row.consumedAt == null || row.consumedAt >= 0))
        if (row.state in setOf("CONSUMED", "COMMITTED", "CONFLICT")) require(row.consumedAt != null)
        if (row.state in setOf("PENDING", "REJECTED")) require(row.consumedAt == null)
        TextApprovalState.valueOf(row.state)
        val proposal = PolicyChangeProposal(ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId), CommandId(row.commandId),
            row.revision, ProjectTextPolicyCodec.decode(row.beforeText), ProjectTextPolicyCodec.decode(row.afterText))
        require(proposal.digest.sha256 == row.digest && proposal.added == row.added && proposal.updated == row.updated &&
            proposal.deleted == row.deleted && proposal.reordered == row.reordered && proposal.promptChanged == row.promptChanged)
        return proposal
    }
    private fun summary(row: NativePolicyProposalListRow, ref: LocalProjectRef): PolicyApprovalSummary {
        require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value && row.projectId == ref.projectId.value)
        require(row.revision >= 0 && row.added in 0..256 && row.updated in 0..256 && row.deleted in 0..256)
        return PolicyApprovalSummary(ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId), CommandId(row.commandId),
            row.revision, ObjectFingerprint(row.digest), row.added, row.updated, row.deleted, row.reordered, row.promptChanged,
            TextApprovalState.valueOf(row.state))
    }
    suspend fun close() = io { db.close() }
}
