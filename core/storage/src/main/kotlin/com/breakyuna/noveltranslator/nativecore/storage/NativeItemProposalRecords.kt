package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

@Entity(tableName = "native_item_proposals", indices = [Index(value = ["commandId"], unique = true),
    Index(value = ["instanceId", "generation", "chatId"])])
internal data class NativeItemProposalRow(
    @PrimaryKey val id: String, val instanceId: String, val generation: Long, val projectId: String,
    val chatId: String, val runId: String, val commandId: String, val revision: Long,
    val policy: String, val digest: String, val count: Int, val state: String,
    val selected: String?, val binding: String?, val consumedAt: Long?, val createdAt: Long,
)

@Entity(tableName = "native_item_proposal_members", primaryKeys = ["proposalId", "itemId"])
internal data class NativeItemProposalMemberRow(val proposalId: String, val itemId: Long, val position: Int,
    val rowIndex: Int, val assetId: Long, val textChanged: Boolean, val namesChanged: Boolean,
    val statusChanged: Boolean, val payload: String)

internal data class NativeItemProposalMemberListRow(val itemId: Long, val rowIndex: Int, val assetId: Long,
    val textChanged: Boolean, val namesChanged: Boolean, val statusChanged: Boolean)

@Dao
internal interface NativeItemProposalDao {
    @Insert fun insert(row: NativeItemProposalRow)
    @Insert fun insertMembers(rows: List<NativeItemProposalMemberRow>)
    @Query("SELECT * FROM native_item_proposals WHERE id=:id") fun find(id: String): NativeItemProposalRow?
    @Query("SELECT * FROM native_item_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state IN ('PENDING','CONSUMED') ORDER BY createdAt,id LIMIT 2")
    fun unresolved(instance: String, generation: Long, chat: String): List<NativeItemProposalRow>
    @Query("SELECT * FROM native_item_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat ORDER BY createdAt DESC,id DESC LIMIT 50")
    fun recent(instance: String, generation: Long, chat: String): List<NativeItemProposalRow>
    @Query("SELECT * FROM native_item_proposal_members WHERE proposalId=:id ORDER BY position")
    fun members(id: String): List<NativeItemProposalMemberRow>
    @Query("SELECT itemId,rowIndex,assetId,textChanged,namesChanged,statusChanged FROM native_item_proposal_members WHERE proposalId=:id ORDER BY position")
    fun memberList(id: String): List<NativeItemProposalMemberListRow>
    @Query("SELECT * FROM native_item_proposal_members WHERE proposalId=:id AND itemId=:item")
    fun member(id: String, item: Long): NativeItemProposalMemberRow?
    @Query("UPDATE native_item_proposals SET state=:state WHERE id=:id AND state=:expected")
    fun settle(id: String, expected: String, state: String): Int
    @Query("UPDATE native_item_proposals SET state='CONSUMED', selected=:selected, binding=:binding, consumedAt=:at WHERE id=:id AND state='PENDING' AND selected IS NULL AND binding IS NULL AND consumedAt IS NULL")
    fun consume(id: String, selected: String, binding: String, at: Long): Int
    @Query("UPDATE native_item_proposals SET state='CANCELLED' WHERE instanceId=:instance AND generation=:generation AND runId=:run AND state='PENDING'")
    fun cancel(instance: String, generation: Long, run: String): Int
}
