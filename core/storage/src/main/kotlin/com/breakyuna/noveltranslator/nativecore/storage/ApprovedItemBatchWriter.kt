package com.breakyuna.noveltranslator.nativecore.storage

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*

/** Private implementation of ProjectWriteStore's approval-bound item transaction. */
internal object ApprovedItemBatchWriter {
    fun receipt(db: SQLiteDatabase, proposal: ItemChangeProposal, selected: Set<ItemId>): ItemProposalReceipt? {
        if (db.version < 6) return null
        return db.rawQuery("SELECT binding,result,revision,rowid,selected FROM approved_item_receipts WHERE instance_id=? AND generation=? AND command_id=?",
            arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(), proposal.command.value)).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            require(cursor.getString(0) == proposal.binding(selected).sha256) { "Approval command binding mismatch" }
            require(ApprovedItemReceiptPayload.read(db, cursor.getLong(3)) == proposal.payload && ItemProposalCodec.selection(cursor.getString(4)) == selected)
            val result = ItemProposalCodec.receipt(cursor.getString(1))
            require(result.command == proposal.command && result.binding == proposal.binding(selected) &&
                result.revision == cursor.getLong(2)) { "Approval receipt mismatch" }
            require((result.accepted + result.rejected.keys).toSet() == proposal.members.map { it.id }.toSet())
            result
        }
    }

    fun apply(db: SQLiteDatabase, proposal: ItemChangeProposal, selected: Set<ItemId>,
              beforeCommit: () -> Unit): ItemProposalReceipt {
        val binding = proposal.binding(selected)
        NativeProjectDatabase.ensureV6(db)
        return NativeProjectDatabase.transaction(db) {
            receipt(db, proposal, selected)?.let { return@transaction it }
            for (table in listOf("write_receipts", "policy_receipts", "asset_receipts")) {
                db.rawQuery("SELECT 1 FROM $table WHERE instance_id=? AND generation=? AND command_id=? LIMIT 1",
                    arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(), proposal.command.value)).use {
                    require(!it.moveToFirst()) { "Command belongs to another write" }
                }
            }
            ProjectWriteStore.rejectApprovedPdfPageCommand(db, proposal.ref, proposal.command)
            db.rawQuery("SELECT 1 FROM write_receipts WHERE instance_id=? AND generation=? AND command_id LIKE ? LIMIT 1",
                arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(),
                    ManualTextBatch.receiptPrefix(proposal.command) + "%")).use {
                require(!it.moveToFirst()) { "Command belongs to a manual batch" }
            }
            var revision = NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton=1")
            if (revision < proposal.revision) throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
            val originals = HashMap<ItemId, TextProjectItem>()
            val rejected = LinkedHashMap<ItemId, ItemProposalRejection>()
            for (member in proposal.members) {
                beforeCommit()
                when {
                    member.id !in selected -> rejected[member.id] = ItemProposalRejection.NOT_SELECTED
                    proposal.policy == ConflictPolicy.REJECT_BATCH && revision != proposal.revision ->
                        rejected[member.id] = ItemProposalRejection.STALE_REVISION
                    else -> {
                        val item = db.rawQuery("SELECT ${NativeProjectDatabase.ITEM_COLUMNS} FROM items WHERE id=?",
                            arrayOf(member.id.value.toString())).use {
                            if (it.moveToFirst()) NativeProjectDatabase.item(it, db.version) else null
                        }
                        if (item == null) rejected[member.id] = ItemProposalRejection.UNKNOWN_ITEM
                        else if (item.fingerprint != member.base || item.row != member.row || item.assetId != member.assetId)
                            rejected[member.id] = ItemProposalRejection.STALE_FINGERPRINT
                        else {
                            // Fingerprints are the primary guard; the frozen before value is also
                            // checked so a corrupted proposal cannot display a different base.
                            val current = ItemProposalValue(item.translation, item.status, item.metadata.nameSource, item.metadata.nameTranslation)
                            require(current.json() == member.before.json()) { "Proposal base mismatch" }
                            originals[member.id] = item
                        }
                    }
                }
            }
            if (proposal.policy == ConflictPolicy.REJECT_BATCH && rejected.any { it.value != ItemProposalRejection.NOT_SELECTED }) {
                for (member in proposal.members) if (member.id in selected && member.id !in rejected)
                    rejected[member.id] = ItemProposalRejection.BATCH_CONFLICT
            }
            val accepted = ArrayList<ItemId>()
            val now = System.currentTimeMillis().coerceAtLeast(0)
            for (member in proposal.members) {
                if (member.id in rejected) continue
                beforeCommit()
                val item = requireNotNull(originals[member.id])
                val after = member.after
                val metadata = TextItemMetadata(item.metadata.tag, after.nameSource, after.nameTranslation,
                    item.metadata.extraField, item.metadata.skipInternalFilter)
                val fingerprint = TextProjectFingerprint.item(item.id, item.row, item.source, after.translation,
                    after.status, metadata, item.assetId, item.slot)
                revision = Math.addExact(revision, 1)
                check(db.update("items", ContentValues().apply {
                    put("translation", after.translation); put("status", after.status.name)
                    put("name_source", NativeProjectDatabase.encodeNames(after.nameSource))
                    put("name_translation", NativeProjectDatabase.encodeNames(after.nameTranslation))
                    put("fingerprint", fingerprint.sha256)
                }, "id=?", arrayOf(item.id.value.toString())) == 1)
                db.insertOrThrow("item_revisions", null, ContentValues().apply {
                    put("revision", revision); put("item_id", item.id.value)
                    put("previous_fingerprint", item.fingerprint.sha256); put("fingerprint", fingerprint.sha256)
                    put("translation", after.translation); put("status", after.status.name)
                    put("instance_id", proposal.ref.instanceId.value); put("generation", proposal.ref.generation.value)
                    put("command_id", proposal.command.value); put("committed_at", now)
                    put("name_source", NativeProjectDatabase.encodeNameSnapshot(after.nameSource))
                    put("name_translation", NativeProjectDatabase.encodeNameSnapshot(after.nameTranslation))
                })
                accepted.add(item.id)
            }
            db.execSQL("UPDATE project SET revision=? WHERE singleton=1", arrayOf(revision))
            val result = ItemProposalReceipt(proposal.command, binding, revision, accepted, rejected, now)
            db.insertOrThrow("approved_item_receipts", null, ContentValues().apply {
                put("instance_id", proposal.ref.instanceId.value); put("generation", proposal.ref.generation.value)
                put("command_id", proposal.command.value); put("binding", binding.sha256)
                put("payload", proposal.payload); put("selected", ItemProposalCodec.selection(selected))
                put("result", ItemProposalCodec.receipt(result)); put("revision", revision)
            })
            beforeCommit()
            result
        }
    }
}
