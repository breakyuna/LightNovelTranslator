package com.breakyuna.noveltranslator.nativecore.storage

import android.database.sqlite.SQLiteDatabase
import com.breakyuna.noveltranslator.nativecore.domain.ItemChangeProposal

/** Keeps the immutable receipt body out of CursorWindow rows, including during import audit. */
internal object ApprovedItemReceiptPayload {
    fun read(db: SQLiteDatabase, rowId: Long, checkActive: () -> Unit = {}): String {
        val args = arrayOf(rowId.toString())
        val size = db.rawQuery(
            "SELECT length(CAST(payload AS BLOB)) FROM approved_item_receipts WHERE rowid=?", args
        ).use {
            require(it.moveToFirst() && !it.isNull(0))
            it.getLong(0).also { bytes -> require(bytes in 0..ItemChangeProposal.MAX_PAYLOAD_BYTES.toLong()) }.toInt()
        }
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            checkActive()
            val count = minOf(64 * 1024, size - offset)
            db.rawQuery(
                "SELECT substr(CAST(payload AS BLOB),?,?) FROM approved_item_receipts WHERE rowid=?",
                arrayOf((offset + 1).toString(), count.toString(), rowId.toString())
            ).use {
                require(it.moveToFirst())
                val chunk = it.getBlob(0)
                require(chunk.size == count)
                chunk.copyInto(bytes, offset)
            }
            offset += count
        }
        // Decode only after joining bytes: a chunk can end inside a UTF-8 code point.
        return bytes.toString(Charsets.UTF_8)
    }
}
