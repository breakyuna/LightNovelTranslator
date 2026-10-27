package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Device-local, single-consumption authority for one PDF page decision batch. Preparation only
 * stores decision codes; PDF source text never enters Room or a list projection. Selection is
 * frozen before the project write and consumption is recorded only after the project receipt
 * becomes durable, so a retry can recover the same result instead of applying it twice.
 */
class NativePdfPageProposalStore(context: Context, private val projects: NativeTextProjectStore,
                                 private val runtime: RuntimePort) {
    private val db = NativeAppDatabase.open(context.applicationContext)
    private val mutex = Mutex()
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); block() }
    }
    private fun <T> transaction(block: () -> T): T = db.runInTransaction(Callable { block() })

    suspend fun prepare(lease: RunLease, ref: LocalProjectRef, assetId: Long, baseRevision: Long,
                        baseSha256: String, pagesJson: String, policy: ConflictPolicy = ConflictPolicy.REJECT_BATCH):
        PdfPageProposalSummary = io {
        require(lease.identity.projectId == ref.projectId && runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            val targets = PdfPageChangeProposalCodec.targets(pagesJson)
            val snapshot = projects.open(ref)
            require(baseRevision >= 0 && snapshot.revision == baseRevision) { "Project revision changed" }
            val asset = snapshot.assets.firstOrNull { it.id == assetId }
                ?: throw IllegalArgumentException("Unknown PDF asset")
            require(asset.format == ProjectAssetFormat.PDF && asset.sha256 == baseSha256 &&
                asset.itemCount in 1..PdfPageChangeProposal.MAX_PAGES) { "PDF asset identity changed" }
            val settings = snapshot.settings ?: ProjectSettings()
            val existing = settings.pdfPages.firstOrNull { it.assetId == asset.id }
            if (existing != null) require(existing.matches(asset)) { "PDF page policy is stale" }
            val imported = projects.pdfPageDecisions(ref, snapshot.revision, asset, targets.map { it.page })
            val members = targets.map { target ->
                currentCoroutineContext().ensureActive()
                val before = existing?.mode(target.page) ?: requireNotNull(imported[target.page])
                PdfPageChangeMember(target.page, before, target.decision)
            }
            require(projects.open(ref).revision == snapshot.revision) { "Project changed while preparing" }
            val proposal = PdfPageChangeProposal(ApprovalRequestId(UUID.randomUUID().toString()), ref,
                requireNotNull(lease.identity.chatId), lease.identity.runId, CommandId(UUID.randomUUID().toString()),
                snapshot.revision, asset.id, asset.sha256, asset.itemCount, policy, members)
            val row = NativePdfPageProposalRow(proposal.id.value, ref.instanceId.value, ref.generation.value,
                ref.projectId.value, proposal.chat.value, proposal.run.value, proposal.command.value, proposal.revision,
                proposal.assetId, proposal.assetSha256, proposal.pageCount, policy.name, proposal.digest.sha256,
                members.size, "PENDING", null, null, null, null, System.currentTimeMillis().coerceAtLeast(0))
            transaction {
                val chat = requireNotNull(db.agent().chat(proposal.chat.value))
                require(chat.instanceId == ref.instanceId.value && chat.generation == ref.generation.value)
                check(db.agent().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value) == null)
                check(db.itemProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.policyProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.pdfPageProposals().unresolved(ref.instanceId.value, ref.generation.value, proposal.chat.value).isEmpty())
                check(db.assetImportProposals().unresolved(ref.instanceId.value, ref.generation.value,
                    proposal.chat.value).isEmpty())
                db.pdfPageProposals().insert(row)
                db.pdfPageProposals().insertPages(proposal.members.mapIndexed { index, member ->
                    NativePdfPageProposalPageRow(row.id, member.page, index, member.json().toString())
                })
            }
            summary(row, ref)
        } finally { runtime.endOperation(operation) }
    }

    suspend fun recent(ref: LocalProjectRef, chat: ChatId): List<PdfPageProposalSummary> = io {
        projects.open(ref)
        db.pdfPageProposals().recent(ref.instanceId.value, ref.generation.value, chat.value).map { summary(it, ref) }
    }

    suspend fun pending(ref: LocalProjectRef, chat: ChatId): PdfPageProposalSummary? = io {
        projects.open(ref)
        val rows = db.pdfPageProposals().unresolved(ref.instanceId.value, ref.generation.value, chat.value)
        require(rows.size <= 1) { "Multiple unresolved PDF proposals" }
        rows.singleOrNull()?.let { summary(it, ref) }
    }

    suspend fun find(ref: LocalProjectRef, chat: ChatId, id: ApprovalRequestId): PdfPageProposalSummary = io {
        projects.open(ref)
        summary(requireNotNull(db.pdfPageProposals().find(id.value)), ref).also { require(it.chat == chat) }
    }

    /** Read-only mixed-approval loader; it never claims or settles the proposal. */
    internal suspend fun loadMixedProposal(ref: LocalProjectRef, chat: ChatId,
                                           id: ApprovalRequestId): PdfPageChangeProposal = io {
        projects.open(ref)
        val row = requireNotNull(db.pdfPageProposals().find(id.value))
        require(row.chatId == chat.value)
        payload(row, ref)
    }

    /** Frozen page selection of a claimed proposal, checked against its exact binding. */
    internal suspend fun loadMixedSelection(ref: LocalProjectRef, chat: ChatId,
                                            id: ApprovalRequestId): Set<Int> = io {
        projects.open(ref)
        val row = requireNotNull(db.pdfPageProposals().find(id.value))
        require(row.chatId == chat.value)
        val proposal = payload(row, ref)
        val encoded = requireNotNull(row.selected) { "Proposal selection is not frozen" }
        selection(encoded).also { selected ->
            require(row.binding == proposal.binding(selected).sha256) { "Approval selection mismatch" }
        }
    }

    suspend fun members(view: PdfPageProposalSummary): List<PdfPageChangeMemberSummary> = io {
        verifyView(view)
        pageRows(view).map { row ->
            val member = PdfPageChangeProposalCodec.member(row.payload)
            require(member.page == row.page)
            PdfPageChangeMemberSummary(member.page, member.before, member.after)
        }
    }

    suspend fun receipt(view: PdfPageProposalSummary): PdfPageProposalReceipt? = io {
        val row = verifyView(view)
        if (row.binding == null) return@io null
        val proposal = payload(row, view.ref)
        val selected = selection(requireNotNull(row.selected))
        require(row.binding == proposal.binding(selected).sha256)
        projects.approvedPdfPageReceipt(proposal, selected)
    }

    suspend fun reject(view: PdfPageProposalSummary) = io {
        val row = verifyView(view)
        if (row.state != "PENDING") return@io
        if (row.binding != null) {
            val proposal = payload(row, view.ref)
            val selected = selection(requireNotNull(row.selected))
            require(row.binding == proposal.binding(selected).sha256)
            val receipt = projects.approvedPdfPageReceipt(proposal, selected)
            if (receipt != null) {
                finishConsumption(row.id, selected, requireNotNull(row.binding), receipt)
                return@io
            }
        }
        transaction {
            if (row.state == "PENDING") check(db.pdfPageProposals().settle(row.id, "PENDING", "REJECTED") == 1)
            else require(row.state == "REJECTED")
        }
    }

    suspend fun apply(lease: RunLease, view: PdfPageProposalSummary, selection: Set<Int>): PdfPageProposalState = io {
        val row = verifyView(view)
        require(lease.identity.projectId == view.ref.projectId && lease.identity.chatId == view.chat &&
            lease.identity.runId == view.run && lease.identity.commandId == view.command &&
            runtime.snapshot().activeRun?.owner == RuntimeOwner.AGENT)
        val operation = runtime.beginOperation(lease, OperationKind.TOOL)
        try {
            val proposal = payload(row, view.ref)
            val selected = selection.toSet()
            require(selected.isNotEmpty() && selected.all { page -> proposal.members.any { it.page == page } })
            val binding = proposal.binding(selected)
            val encodedSelection = PdfPageChangeProposalCodec.selection(selected)
            if (row.binding != null) require(row.binding == binding.sha256 && row.selected == encodedSelection)
            if (row.state !in setOf("PENDING", "CONSUMED")) return@io PdfPageProposalState.valueOf(row.state)
            if (row.state == "CONSUMED") {
                require(row.binding == binding.sha256 && row.selected == encodedSelection)
                val existing = projects.approvedPdfPageReceipt(proposal, selected)
                    ?: return@io PdfPageProposalState.CANCELLED
                withContext(NonCancellable) { finishConsumption(row.id, selected, binding.sha256, existing) }
                return@io existing.state
            }
            if (row.binding == null) {
                transaction {
                    if (db.pdfPageProposals().claim(row.id, encodedSelection, binding.sha256) == 0) {
                        val claimed = requireNotNull(db.pdfPageProposals().find(row.id))
                        require(claimed.binding == binding.sha256 && claimed.selected == encodedSelection)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            // Always read the project receipt first: a prior executor may have committed and
            // only the final Room settlement was lost.
            val receipt = projects.approvedPdfPageReceipt(proposal, selected)
                ?: projects.approvedPdfPages(lease, proposal, selected)
            withContext(NonCancellable) { finishConsumption(row.id, selected, binding.sha256, receipt) }
            receipt.state
        } finally { runtime.endOperation(operation) }
    }

    /** No replay. A real idle reservation excludes a previous executor before receipt lookup. */
    suspend fun reconcile(ref: LocalProjectRef, chat: ChatId) = io {
        if (runtime.snapshot().activeRun != null) return@io
        val write = runtime.beginProjectWrite()
        try {
            projects.open(ref)
            db.pdfPageProposals().recoverable(ref.instanceId.value, ref.generation.value, chat.value).forEach { row ->
                if (row.state == "PENDING" && row.binding == null) return@forEach
                val proposal = payload(row, ref)
                val selected = selection(requireNotNull(row.selected))
                require(row.binding == proposal.binding(selected).sha256)
                val receipt = projects.approvedPdfPageReceipt(proposal, selected)
                if (receipt != null) {
                    if (row.state == "PENDING") finishConsumption(row.id, selected, requireNotNull(row.binding), receipt)
                    else transaction {
                        check(db.pdfPageProposals().settleResult(row.id, row.state, receipt.state.name,
                            PdfPageChangeProposalCodec.receipt(receipt), System.currentTimeMillis().coerceAtLeast(0)) == 1)
                    }
                } else if (row.state == "CONSUMED") {
                    transaction { check(db.pdfPageProposals().settle(row.id, "CONSUMED", "CANCELLED") == 1) }
                }
            }
        } finally { runtime.endProjectWrite(write) }
    }

    private suspend fun finishConsumption(id: String, selected: Set<Int>, binding: String,
                                          receipt: PdfPageProposalReceipt) {
        val encoded = PdfPageChangeProposalCodec.selection(selected)
        val result = PdfPageChangeProposalCodec.receipt(receipt)
        val now = System.currentTimeMillis().coerceAtLeast(0)
        transaction {
            if (db.pdfPageProposals().consume(id, encoded, binding, result, now) == 1) {
                check(db.pdfPageProposals().settle(id, "CONSUMED", receipt.state.name) == 1)
            } else {
                val current = requireNotNull(db.pdfPageProposals().find(id))
                require(current.binding == binding && current.selected == encoded) { "Approval selection mismatch" }
                require(current.state in setOf("CONSUMED", "COMMITTED", "PARTIAL", "CONFLICT", "CANCELLED", "REJECTED")) {
                    "Approval is still pending"
                }
                if (current.state != receipt.state.name) {
                    check(db.pdfPageProposals().settleResult(id, current.state, receipt.state.name, result, now) == 1)
                }
            }
        }
    }

    private suspend fun verifyView(view: PdfPageProposalSummary): NativePdfPageProposalRow {
        projects.open(view.ref)
        val row = requireNotNull(db.pdfPageProposals().find(view.id.value))
        val verified = summary(row, view.ref)
        require(verified.chat == view.chat && verified.run == view.run && verified.command == view.command &&
            verified.digest == view.digest && verified.revision == view.revision && verified.policy == view.policy &&
            verified.count == view.count && verified.assetId == view.assetId && verified.assetSha256 == view.assetSha256 &&
            verified.pageCount == view.pageCount)
        return row
    }

    private fun payload(row: NativePdfPageProposalRow, ref: LocalProjectRef): PdfPageChangeProposal {
        summary(row, ref)
        val stored = pageRows(row.id)
        require(stored.size == row.count)
        val proposal = PdfPageChangeProposal(ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId),
            CommandId(row.commandId), row.revision, row.assetId, row.sha256, row.pageCount,
            ConflictPolicy.valueOf(row.policy), stored.map { storedPage ->
                PdfPageChangeProposalCodec.member(storedPage.payload).also {
                    require(it.page == storedPage.page)
                }
            })
        require(proposal.digest.sha256 == row.digest) { "Proposal payload mismatch" }
        return proposal
    }

    private fun pageRows(view: PdfPageProposalSummary): List<NativePdfPageProposalPageListRow> =
        db.pdfPageProposals().pages(view.id.value).also { require(it.size == view.count) }

    private fun pageRows(id: String): List<NativePdfPageProposalPageListRow> =
        db.pdfPageProposals().pages(id)

    private fun summary(row: NativePdfPageProposalRow, ref: LocalProjectRef): PdfPageProposalSummary {
        require(row.instanceId == ref.instanceId.value && row.generation == ref.generation.value && row.projectId == ref.projectId.value)
        require(row.count in 1..row.pageCount && row.pageCount in 1..PdfPageChangeProposal.MAX_PAGES && row.revision >= 0)
        require(row.assetId > 0 && row.sha256.length == 64 && row.sha256.all { it in "0123456789abcdef" })
        require((row.binding == null) == (row.selected == null))
        require(row.consumedAt == null || row.binding != null)
        require(row.consumedAt == null || row.consumedAt >= 0)
        if (row.state in setOf("PENDING", "REJECTED", "CANCELLED")) require(row.consumedAt == null)
        return PdfPageProposalSummary(ApprovalRequestId(row.id), ref, ChatId(row.chatId), RunId(row.runId),
            CommandId(row.commandId), row.revision, row.assetId, row.sha256, row.pageCount,
            ConflictPolicy.valueOf(row.policy), ObjectFingerprint(row.digest), row.count,
            PdfPageProposalState.valueOf(row.state))
    }

    private fun selection(encoded: String): Set<Int> = PdfPageChangeProposalCodec.selection(encoded)

    suspend fun close() = io { db.close() }
}
