package com.breakyuna.noveltranslator.nativecore.storage

import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*

/** Lightweight proof of one item revision, reconstructed from its bounded immutable batch. */
internal class ApprovedItemRevisionEvidence(val instance: String, val generation: Long, val command: String,
    val item: Long, val row: Int, val asset: Long, val base: ObjectFingerprint, val after: ObjectFingerprint,
    val committedAt: Long)

/** Validates imported v6 audit facts. These facts never grant device-local execution authority. */
internal object ApprovedItemReceiptAudit {
    fun validate(db: SQLiteDatabase, project: Pair<ProjectId, Long>, budget: NativeImportLimits,
                 charge: (Long) -> Unit, checkActive: () -> Unit): Map<Long, ApprovedItemRevisionEvidence> {
        val count = NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM approved_item_receipts")
        if (count > budget.maxHistoryRows) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        db.rawQuery("SELECT 1 FROM approved_item_receipts WHERE typeof(instance_id)!='text' OR typeof(generation)!='integer' " +
            "OR typeof(command_id)!='text' OR typeof(binding)!='text' OR typeof(payload)!='text' OR typeof(selected)!='text' " +
            "OR typeof(result)!='text' OR typeof(revision)!='integer' LIMIT 1", null).use { require(!it.moveToFirst()) }
        db.rawQuery("SELECT 1 FROM approved_item_receipts WHERE length(payload)>CAST(? AS INTEGER) OR length(CAST(payload AS BLOB))>CAST(? AS INTEGER) " +
            "OR length(selected)>65536 OR length(result)>524288 OR length(instance_id)>2048 OR length(command_id)>2048 OR length(binding)!=64 LIMIT 1",
            arrayOf(ItemChangeProposal.MAX_PAYLOAD_BYTES.toString(), ItemChangeProposal.MAX_PAYLOAD_BYTES.toString())).use {
            // Bound parameters are explicitly cast for Android SQLite's TEXT binding affinity.
            if (it.moveToFirst()) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        }
        val evidence = HashMap<Long, ApprovedItemRevisionEvidence>()
        db.rawQuery("SELECT instance_id,generation,command_id,binding,rowid,selected,result,revision FROM approved_item_receipts", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                val payload = ApprovedItemReceiptPayload.read(db, cursor.getLong(4), checkActive)
                charge(payload.length.toLong() + cursor.getString(5).length + cursor.getString(6).length)
                val proposal = decode { ItemProposalCodec.proposal(payload) }
                val selected = decode { ItemProposalCodec.selection(cursor.getString(5)) }
                val receipt = decode { ItemProposalCodec.receipt(cursor.getString(6)) }
                val binding = proposal.binding(selected)
                require(proposal.ref.projectId == project.first && proposal.ref.instanceId.value == cursor.getString(0) &&
                    proposal.ref.generation.value == cursor.getLong(1) && proposal.command.value == cursor.getString(2))
                require(binding.sha256 == cursor.getString(3) && receipt.binding == binding && receipt.command == proposal.command)
                require(receipt.revision == cursor.getLong(7) && receipt.revision in proposal.revision..project.second)
                val all = proposal.members.map { it.id }.toSet()
                require((receipt.accepted + receipt.rejected.keys).toSet() == all)
                require(receipt.accepted.all { it in selected })
                require(receipt.rejected.all { (id, reason) -> (reason == ItemProposalRejection.NOT_SELECTED) == (id !in selected) })
                val acceptedMembers = proposal.members.filter { it.id in receipt.accepted }
                require(acceptedMembers.map { it.id } == receipt.accepted)
                if (receipt.accepted.isNotEmpty()) require(receipt.revision - receipt.accepted.size >= proposal.revision)
                if (proposal.policy == ConflictPolicy.REJECT_BATCH && receipt.accepted.isNotEmpty()) {
                    require(receipt.revision - receipt.accepted.size == proposal.revision)
                    require(receipt.rejected.values.all { it == ItemProposalRejection.NOT_SELECTED })
                }
                for ((index, member) in acceptedMembers.withIndex()) {
                    checkActive()
                    val revision = receipt.revision - acceptedMembers.size + index + 1
                    val item = db.rawQuery("SELECT ${NativeProjectDatabase.ITEM_COLUMNS} FROM " +
                        "(SELECT * FROM items UNION ALL SELECT * FROM archived_items) WHERE id=?",
                        arrayOf(member.id.value.toString())).use {
                        require(it.moveToFirst()); NativeProjectDatabase.item(it, db.version)
                    }
                    require(item.row == member.row && item.assetId == member.assetId)
                    require(fingerprint(item, member.before) == member.base)
                    require(evidence.put(revision, ApprovedItemRevisionEvidence(proposal.ref.instanceId.value,
                        proposal.ref.generation.value, proposal.command.value, member.id.value, member.row,
                        member.assetId, member.base, fingerprint(item, member.after), receipt.committedAtMillis)) == null)
                }
                for (table in listOf("write_receipts", "policy_receipts", "asset_receipts")) {
                    db.rawQuery("SELECT 1 FROM $table WHERE instance_id=? AND generation=? AND command_id=? LIMIT 1",
                        arrayOf(proposal.ref.instanceId.value, proposal.ref.generation.value.toString(), proposal.command.value)).use {
                        require(!it.moveToFirst())
                    }
                }
            }
        }
        return evidence
    }

    private fun fingerprint(item: TextProjectItem, value: ItemProposalValue): ObjectFingerprint =
        TextProjectFingerprint.item(item.id, item.row, item.source, value.translation, value.status,
            TextItemMetadata(item.metadata.tag, value.nameSource, value.nameTranslation,
                item.metadata.extraField, item.metadata.skipInternalFilter), item.assetId, item.slot)

    private inline fun <T> decode(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT) }
    catch (_: IllegalStateException) { throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT) }
}
