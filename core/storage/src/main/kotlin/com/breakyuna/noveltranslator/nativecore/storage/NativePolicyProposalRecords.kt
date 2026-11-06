package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

@Entity(tableName = "native_policy_proposals", indices = [Index(value = ["commandId"], unique = true),
    Index(value = ["instanceId", "generation", "chatId"])])
internal data class NativePolicyProposalRow(@PrimaryKey val id: String, val instanceId: String, val generation: Long,
    val projectId: String, val chatId: String, val runId: String, val commandId: String, val revision: Long,
    val digest: String, val beforeText: String, val afterText: String, val added: Int, val updated: Int, val deleted: Int,
    val reordered: Boolean, val promptChanged: Boolean, val state: String, val createdAt: Long, val consumedAt: Long?)

internal data class NativePolicyProposalListRow(val id: String, val instanceId: String, val generation: Long,
    val projectId: String, val chatId: String, val runId: String, val commandId: String, val revision: Long,
    val digest: String, val added: Int, val updated: Int, val deleted: Int, val reordered: Boolean,
    val promptChanged: Boolean, val state: String)

@Dao
internal interface NativePolicyProposalDao {
    @Insert fun insert(row: NativePolicyProposalRow)
    @Query("SELECT * FROM native_policy_proposals WHERE id=:id") fun find(id: String): NativePolicyProposalRow?
    @Query("SELECT id,instanceId,generation,projectId,chatId,runId,commandId,revision,digest,added,updated,deleted,reordered,promptChanged,state FROM native_policy_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat ORDER BY createdAt DESC,id DESC LIMIT 50")
    fun recent(instance: String, generation: Long, chat: String): List<NativePolicyProposalListRow>
    @Query("SELECT id,instanceId,generation,projectId,chatId,runId,commandId,revision,digest,added,updated,deleted,reordered,promptChanged,state FROM native_policy_proposals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state IN ('PENDING','CONSUMED') ORDER BY createdAt,id LIMIT 2")
    fun unresolved(instance: String, generation: Long, chat: String): List<NativePolicyProposalListRow>
    @Query("UPDATE native_policy_proposals SET state=:state WHERE id=:id AND state=:expected")
    fun settle(id: String, expected: String, state: String): Int
    @Query("UPDATE native_policy_proposals SET state='CONSUMED',consumedAt=:at WHERE id=:id AND state='PENDING' AND consumedAt IS NULL")
    fun consume(id: String, at: Long): Int
    @Query("UPDATE native_policy_proposals SET state='CANCELLED' WHERE instanceId=:instance AND generation=:generation AND runId=:run AND state='PENDING'")
    fun cancel(instance: String, generation: Long, run: String): Int
}
