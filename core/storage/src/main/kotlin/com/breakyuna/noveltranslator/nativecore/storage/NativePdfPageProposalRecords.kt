package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

@Entity(tableName = "native_pdf_page_proposals", indices = [Index(value = ["commandId"], unique = true),
    Index(value = ["instanceId", "generation", "chatId"])])
internal data class NativePdfPageProposalRow(
    @PrimaryKey val id: String, val instanceId: String, val generation: Long, val projectId: String,
    val chatId: String, val runId: String, val commandId: String, val revision: Long,
    val assetId: Long, val sha256: String, val pageCount: Int, val policy: String, val digest: String,
    val count: Int, val state: String, val selected: String?, val binding: String?, val result: String?,
    val consumedAt: Long?, val createdAt: Long,
)

@Entity(tableName = "native_pdf_page_proposal_pages", primaryKeys = ["proposalId", "page"])
internal data class NativePdfPageProposalPageRow(
    val proposalId: String, val page: Int, val position: Int, val payload: String,
)

internal data class NativePdfPageProposalPageListRow(val page: Int, val payload: String)

@Dao
internal interface NativePdfPageProposalDao {
    @Insert fun insert(row: NativePdfPageProposalRow)
    @Insert fun insertPages(rows: List<NativePdfPageProposalPageRow>)
    @Query("SELECT * FROM native_pdf_page_proposals WHERE id=:id") fun find(id: String): NativePdfPageProposalRow?
    @Query("SELECT * FROM native_pdf_page_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state IN ('PENDING','CONSUMED') ORDER BY createdAt,id LIMIT 2")
    fun unresolved(instance: String, generation: Long, chat: String): List<NativePdfPageProposalRow>
    @Query("SELECT * FROM native_pdf_page_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state IN ('PENDING','CONSUMED','CANCELLED') AND binding IS NOT NULL ORDER BY createdAt,id LIMIT 50")
    fun recoverable(instance: String, generation: Long, chat: String): List<NativePdfPageProposalRow>
    @Query("SELECT * FROM native_pdf_page_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat ORDER BY createdAt DESC,id DESC LIMIT 50")
    fun recent(instance: String, generation: Long, chat: String): List<NativePdfPageProposalRow>
    @Query("SELECT page,payload FROM native_pdf_page_proposal_pages WHERE proposalId=:id ORDER BY position")
    fun pages(id: String): List<NativePdfPageProposalPageListRow>
    @Query("UPDATE native_pdf_page_proposals SET selected=:selected, binding=:binding WHERE id=:id AND state='PENDING' AND selected IS NULL AND binding IS NULL")
    fun claim(id: String, selected: String, binding: String): Int
    @Query("UPDATE native_pdf_page_proposals SET state='CONSUMED', result=:result, consumedAt=:at WHERE id=:id AND state='PENDING' AND selected=:selected AND binding=:binding AND result IS NULL AND consumedAt IS NULL")
    fun consume(id: String, selected: String, binding: String, result: String, at: Long): Int
    @Query("UPDATE native_pdf_page_proposals SET state=:state WHERE id=:id AND state=:expected")
    fun settle(id: String, expected: String, state: String): Int
    @Query("UPDATE native_pdf_page_proposals SET state=:state, result=:result, consumedAt=:at WHERE id=:id AND state=:expected")
    fun settleResult(id: String, expected: String, state: String, result: String, at: Long): Int
    @Query("UPDATE native_pdf_page_proposals SET state='CANCELLED' WHERE instanceId=:instance AND generation=:generation AND runId=:run AND state='PENDING'")
    fun cancel(instance: String, generation: Long, run: String): Int
}
