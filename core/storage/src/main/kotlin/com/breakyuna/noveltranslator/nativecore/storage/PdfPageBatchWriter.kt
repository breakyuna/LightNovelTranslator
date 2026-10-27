package com.breakyuna.noveltranslator.nativecore.storage

import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues
import com.breakyuna.noveltranslator.nativecore.domain.*

/**
 * Project-side transaction for one approved PDF page decision batch. The settings payload,
 * global revision, SETTINGS history row, generic asset receipt and immutable approval receipt
 * are committed together under the single project writer.
 */
internal object PdfPageBatchWriter {
    fun receipt(db: SQLiteDatabase, proposal: PdfPageChangeProposal, selected: Set<Int>): PdfPageProposalReceipt? {
        if (db.version < 7) return null
        val binding = proposal.binding(selected)
        return db.rawQuery("SELECT binding,asset_id,rowid,selected,result,revision FROM approved_pdf_page_receipts " +
            "WHERE instance_id=? AND generation=? AND command_id=?",
            arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(), proposal.command.value)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            require(cursor.getString(0) == binding.sha256) { "Approval command binding mismatch" }
            require(cursor.getLong(1) == proposal.assetId) { "Approval asset mismatch" }
            val selectedEncoded = cursor.getString(3)
            require(PdfPageChangeProposalCodec.selection(selectedEncoded) == selected) { "Approval selection mismatch" }
            val payload = ApprovedPdfPageReceiptPayload.read(db, cursor.getLong(2))
            val frozen = PdfPageChangeProposalCodec.proposal(payload)
            require(frozen.payload == proposal.payload && frozen.digest == proposal.digest) { "Approval payload mismatch" }
            val result = PdfPageChangeProposalCodec.receipt(cursor.getString(4))
            val revision = cursor.getLong(5)
            require(result.command == proposal.command && result.binding == binding && result.revision == revision) {
                "Approval receipt mismatch"
            }
            val allPages = proposal.members.map { it.page }.toSet()
            require((result.accepted + result.rejected.keys).toSet() == allPages) { "Approval receipt scope mismatch" }
            require(result.accepted.all { it in selected }) { "Approval accepted an unselected page" }
            require(result.rejected.all { (page, reason) -> (reason == PdfPageProposalRejection.NOT_SELECTED) == (page !in selected) }) {
                "Approval rejection scope mismatch"
            }
            require(db.rawQuery("SELECT 1 FROM asset_receipts WHERE instance_id=? AND generation=? AND command_id=? " +
                "AND kind='SETTINGS' AND digest=? AND asset_id=? AND revision=?",
                arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(), proposal.command.value,
                    binding.sha256, proposal.assetId.toString(), revision.toString())).use { it.moveToFirst() }) {
                "Project receipt mismatch"
            }
            result
        }
    }

    fun apply(db: SQLiteDatabase, proposal: PdfPageChangeProposal, selected: Set<Int>,
              beforeCommit: () -> Unit): PdfPageProposalReceipt {
        NativeProjectDatabase.ensureV7(db)
        return NativeProjectDatabase.transaction(db) {
            applyInTransaction(db, proposal, selected, beforeCommit)
        }
    }

    /**
     * Transaction-internal PDF page batch. [transactionStartRevision] keeps the stale-revision
     * decision on the mixed parent baseline while earlier members advance the global revision.
     * The parent's [conflictPolicy] overrides the child proposal policy for this mixed batch.
     */
    fun applyInTransaction(db: SQLiteDatabase, proposal: PdfPageChangeProposal, selected: Set<Int>,
                           beforeCommit: () -> Unit, transactionStartRevision: Long? = null,
                           conflictPolicy: ConflictPolicy? = null): PdfPageProposalReceipt {
        val binding = proposal.binding(selected)
        val effectivePolicy = conflictPolicy ?: proposal.policy
        receipt(db, proposal, selected)?.let { return it }
        rejectCommandCollision(db, proposal)
        val summary = NativeProjectDatabase.summary(db, proposal.ref)
        val batchRevision = transactionStartRevision ?: summary.revision
        val currentSettings = NativeProjectDatabase.projectSettings(db)
        val asset = summary.assets.firstOrNull { it.id == proposal.assetId }
        val assetMatches = asset != null && asset.format == ProjectAssetFormat.PDF &&
            asset.sha256 == proposal.assetSha256 && asset.itemCount == proposal.pageCount
        val policy = currentSettings.pdfPages.firstOrNull { it.assetId == proposal.assetId }
        val policyMatches = policy == null || (assetMatches && policy.matches(requireNotNull(asset)))
        val rejected = LinkedHashMap<Int, PdfPageProposalRejection>()
        for (member in proposal.members) {
            when {
                member.page !in selected -> rejected[member.page] = PdfPageProposalRejection.NOT_SELECTED
                !assetMatches || !policyMatches -> rejected[member.page] = PdfPageProposalRejection.STALE_ASSET
                effectivePolicy == ConflictPolicy.REJECT_BATCH && batchRevision != proposal.revision ->
                    rejected[member.page] = PdfPageProposalRejection.STALE_REVISION
                currentMode(db, proposal, policy, member.page) != member.before ->
                    rejected[member.page] = PdfPageProposalRejection.STALE_PAGE
            }
        }
        if (effectivePolicy == ConflictPolicy.REJECT_BATCH &&
            rejected.any { it.value != PdfPageProposalRejection.NOT_SELECTED }) {
            for (member in proposal.members) if (member.page in selected && member.page !in rejected) {
                rejected[member.page] = PdfPageProposalRejection.BATCH_CONFLICT
            }
        }
        val accepted = proposal.members.map { it.page }.filter { it !in rejected }.sorted()
        val now = System.currentTimeMillis().coerceAtLeast(0)
        if (accepted.isEmpty()) {
            val result = PdfPageProposalReceipt(proposal.command, binding, summary.revision, emptyList(), rejected, now)
            beforeCommit()
            insertReceipt(db, proposal, selected, result, binding)
            insertAssetReceipt(db, proposal, binding, summary.revision,
                if (rejected.values.all { it == PdfPageProposalRejection.STALE_REVISION })
                    AssetMutationStatus.STALE_REVISION else AssetMutationStatus.CONFLICT)
            return result
        }
        val decisions = (policy?.decisions ?: "-".repeat(proposal.pageCount)).toCharArray()
        for (page in accepted) {
            val after = requireNotNull(proposal.members.firstOrNull { it.page == page }).after
            decisions[page - 1] = after.code
        }
        val target = currentSettings.withPdfPages(currentSettings.pdfPages.filter { it.assetId != proposal.assetId } +
            PdfAssetPagePolicy(proposal.assetId, proposal.assetSha256, decisions.concatToString()))
        ProjectPageTranslationPolicy(target, summary.assets)
        val next = Math.addExact(summary.revision, 1)
        val result = PdfPageProposalReceipt(proposal.command, binding, next, accepted, rejected, now)
        db.execSQL("UPDATE project_settings SET payload=?,fingerprint=? WHERE singleton=1",
            arrayOf(ProjectSettingsCodec.encode(target), target.fingerprint().sha256))
        db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(next))
        db.insertOrThrow("asset_revisions", null, ContentValues().apply {
            put("revision", next); put("kind", AssetMutationKind.SETTINGS.name)
            put("asset_id", proposal.assetId); put("payload", ProjectSettingsCodec.encode(target))
            put("instance_id", proposal.ref.instanceId.value); put("generation", proposal.ref.generation.value)
            put("command_id", proposal.command.value); put("committed_at", now)
        })
        beforeCommit()
        insertReceipt(db, proposal, selected, result, binding)
        insertAssetReceipt(db, proposal, binding, next, AssetMutationStatus.COMMITTED)
        return result
    }

    private fun insertReceipt(db: SQLiteDatabase, proposal: PdfPageChangeProposal, selected: Set<Int>,
                              result: PdfPageProposalReceipt, binding: ObjectFingerprint) {
        db.insertOrThrow("approved_pdf_page_receipts", null, ContentValues().apply {
            put("instance_id", proposal.ref.instanceId.value); put("generation", proposal.ref.generation.value)
            put("command_id", proposal.command.value); put("binding", binding.sha256)
            put("asset_id", proposal.assetId); put("payload", proposal.payload)
            put("selected", PdfPageChangeProposalCodec.selection(selected))
            put("result", PdfPageChangeProposalCodec.receipt(result)); put("revision", result.revision)
        })
    }

    private fun insertAssetReceipt(db: SQLiteDatabase, proposal: PdfPageChangeProposal, binding: ObjectFingerprint,
                                   revision: Long, status: AssetMutationStatus) {
        db.insertOrThrow("asset_receipts", null, ContentValues().apply {
            put("instance_id", proposal.ref.instanceId.value); put("generation", proposal.ref.generation.value)
            put("command_id", proposal.command.value); put("digest", binding.sha256)
            put("outcome", status.name); put("revision", revision); put("asset_id", proposal.assetId)
            put("kind", AssetMutationKind.SETTINGS.name); put("committed_at", System.currentTimeMillis().coerceAtLeast(0))
        })
    }

    private fun currentMode(db: SQLiteDatabase, proposal: PdfPageChangeProposal,
                            policy: PdfAssetPagePolicy?, page: Int): PdfPageMode {
        policy?.mode(page)?.let { return it }
        return db.rawQuery("SELECT tag,extra_field FROM items WHERE asset_id=? AND row_index=?",
            arrayOf(proposal.assetId.toString(), page.toString())).use { cursor ->
            require(cursor.moveToFirst() && cursor.count == 1) { "PDF page missing" }
            PdfPageDecisionBinding.mode(page, cursor.getString(0), cursor.getString(1))
        }
    }

    private fun rejectCommandCollision(db: SQLiteDatabase, proposal: PdfPageChangeProposal) {
        for (table in listOf("write_receipts", "policy_receipts", "asset_receipts")) {
            db.rawQuery("SELECT 1 FROM $table WHERE instance_id=? AND generation=? AND command_id=? LIMIT 1",
                arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(),
                    proposal.command.value)).use {
                require(!it.moveToFirst()) { "Command belongs to another write" }
            }
        }
        if (db.version >= 6) db.rawQuery("SELECT 1 FROM approved_item_receipts " +
            "WHERE instance_id=? AND generation=? AND command_id=? LIMIT 1",
            arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(),
                proposal.command.value)).use {
            require(!it.moveToFirst()) { "Command belongs to an item approval" }
        }
    }
}
