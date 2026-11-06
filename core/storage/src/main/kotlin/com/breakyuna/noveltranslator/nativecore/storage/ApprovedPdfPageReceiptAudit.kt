package com.breakyuna.noveltranslator.nativecore.storage

import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.*

/** Validates imported v7 PDF page approval facts. They never grant device-local authority. */
internal object ApprovedPdfPageReceiptAudit {
    fun validate(db: SQLiteDatabase, project: Pair<ProjectId, Long>, budget: NativeImportLimits,
                 charge: (Long) -> Unit, checkActive: () -> Unit) {
        val count = NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM approved_pdf_page_receipts")
        if (count > budget.maxHistoryRows) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        db.rawQuery("SELECT 1 FROM approved_pdf_page_receipts WHERE typeof(instance_id)!='text' " +
            "OR typeof(generation)!='integer' OR typeof(command_id)!='text' OR typeof(binding)!='text' " +
            "OR typeof(asset_id)!='integer' OR typeof(payload)!='text' OR typeof(selected)!='text' " +
            "OR typeof(result)!='text' OR typeof(revision)!='integer' LIMIT 1", null).use {
            require(!it.moveToFirst())
        }
        db.rawQuery("SELECT 1 FROM approved_pdf_page_receipts WHERE length(payload)>CAST(? AS INTEGER) " +
            "OR length(CAST(payload AS BLOB))>CAST(? AS INTEGER) OR length(selected)>65536 " +
            "OR length(result)>524288 OR length(instance_id)>2048 OR length(command_id)>2048 OR length(binding)!=64 LIMIT 1",
            arrayOf(PdfPageChangeProposal.MAX_PAYLOAD_BYTES.toString(),
                PdfPageChangeProposal.MAX_PAYLOAD_BYTES.toString())).use {
            if (it.moveToFirst()) throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
        }
        db.rawQuery("SELECT instance_id,generation,command_id,binding,asset_id,rowid,selected,result,revision " +
            "FROM approved_pdf_page_receipts", null).use { cursor ->
            while (cursor.moveToNext()) {
                checkActive()
                val payload = ApprovedPdfPageReceiptPayload.read(db, cursor.getLong(5), checkActive)
                val selectedText = cursor.getString(6)
                val resultText = cursor.getString(7)
                charge(payload.length.toLong() + selectedText.length + resultText.length)
                val proposal = decode { PdfPageChangeProposalCodec.proposal(payload) }
                val selected = decode { PdfPageChangeProposalCodec.selection(selectedText) }
                val receipt = decode { PdfPageChangeProposalCodec.receipt(resultText) }
                val binding = proposal.binding(selected)
                require(proposal.ref.projectId == project.first &&
                    proposal.ref.instanceId.value == cursor.getString(0) &&
                    proposal.ref.generation.value == cursor.getLong(1) &&
                    proposal.command.value == cursor.getString(2) && proposal.assetId == cursor.getLong(4))
                require(binding.sha256 == cursor.getString(3) && receipt.binding == binding && receipt.command == proposal.command)
                require(receipt.revision == cursor.getLong(8) && receipt.revision in proposal.revision..project.second)
                val all = proposal.members.map { it.page }.toSet()
                require((receipt.accepted + receipt.rejected.keys).toSet() == all)
                require(receipt.accepted.all { it in selected })
                require(receipt.rejected.all { (page, reason) ->
                    (reason == PdfPageProposalRejection.NOT_SELECTED) == (page !in selected)
                })
                val asset = NativeProjectDatabase.assetSummaries(db).firstOrNull { it.id == proposal.assetId }
                    ?: throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT)
                require(asset.format == ProjectAssetFormat.PDF && asset.sha256 == proposal.assetSha256 &&
                    asset.itemCount == proposal.pageCount)
                require(db.rawQuery("SELECT 1 FROM asset_receipts WHERE instance_id=? AND generation=? AND command_id=? " +
                    "AND kind='SETTINGS' AND digest=? AND asset_id=? AND revision=? AND outcome IN ('COMMITTED','CONFLICT','STALE_REVISION')",
                    arrayOf(cursor.getString(0), cursor.getLong(1).toString(), cursor.getString(2), binding.sha256,
                        proposal.assetId.toString(), receipt.revision.toString()))
                    .use { it.moveToFirst() })
                if (receipt.accepted.isNotEmpty()) {
                    val settings = decode { db.rawQuery("SELECT payload FROM asset_revisions WHERE revision=? AND kind='SETTINGS' " +
                        "AND asset_id=? AND instance_id=? AND generation=? AND command_id=?",
                        arrayOf(receipt.revision.toString(), proposal.assetId.toString(), cursor.getString(0),
                            cursor.getLong(1).toString(), cursor.getString(2))).use {
                        require(it.moveToFirst()); ProjectSettingsCodec.decode(it.getString(0))
                    } }
                    val policy = settings.pdfPages.firstOrNull { it.assetId == proposal.assetId }
                        ?: throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT)
                    require(policy.matches(asset))
                    for (page in receipt.accepted) {
                        val member = proposal.members.first { it.page == page }
                        require(policy.mode(page) == member.after)
                    }
                }
                for (table in listOf("write_receipts", "policy_receipts", "approved_item_receipts")) {
                    if (table == "approved_item_receipts" && db.version < 6) continue
                    db.rawQuery("SELECT 1 FROM $table WHERE instance_id=? AND generation=? AND command_id=? LIMIT 1",
                        arrayOf(cursor.getString(0), cursor.getLong(1).toString(), cursor.getString(2))).use {
                        require(!it.moveToFirst())
                    }
                }
            }
        }
    }

    private inline fun <T> decode(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT) }
    catch (_: IllegalStateException) { throw TextProjectException(TextProjectException.Reason.CORRUPT_PROJECT) }
}
