package com.breakyuna.noveltranslator.nativecore.storage

import androidx.room.*

@Entity(tableName = "native_agent_messages", indices = [Index(value = ["instanceId", "generation", "chatId"])])
internal data class NativeAgentMessageRow(@PrimaryKey val id: String, val instanceId: String, val generation: Long,
    val chatId: String, val role: String, val body: String, val createdAt: Long, val payload: String? = null)
@Entity(tableName = "native_agent_approvals", indices = [Index(value = ["commandId"], unique = true),
    Index(value = ["instanceId", "generation", "chatId"])])
internal data class NativeAgentApprovalRow(@PrimaryKey val id: String, val instanceId: String, val generation: Long,
    val projectId: String, val chatId: String, val runId: String, val commandId: String, val digest: String,
    val itemId: Long, val revision: Long, val base: String, val rowIndex: Int, val beforeStatus: String, val beforeText: String, val afterText: String,
    val state: String, val createdAt: Long)
@Entity(tableName = "native_agent_requests")
internal data class NativeAgentRequestRow(@PrimaryKey val attemptId: String, val requestKey: String, val digest: String,
    val state: String, val responseText: String?, val responseId: String?, val usage: String?)

internal data class NativeAgentResultRow(val id: String, val rowIndex: Int, val state: String)

@Entity(tableName = "native_agent_chats", indices = [Index(value = ["instanceId", "generation"])])
internal data class NativeAgentChatRow(@PrimaryKey val id: String, val instanceId: String, val generation: Long, val createdAt: Long, val selected: Boolean)

@Entity(tableName = "native_agent_contexts", primaryKeys = ["instanceId", "generation", "chatId"])
internal data class NativeAgentContextRow(val instanceId: String, val generation: Long, val chatId: String,
    val boundaryId: String, val boundaryTime: Long, val summary: String, val fingerprint: String, val revision: Long)

internal data class NativeAgentMessageListRow(val id: String, val role: String, val preview: String, val createdAt: Long)

@Entity(tableName = "native_agent_branches")
internal data class NativeAgentBranchRow(@PrimaryKey val command: String, val sourceChat: String,
    val targetChat: String, val message: String, val digest: String)

@Dao
internal interface NativeAgentDao {
    @Insert fun branch(row: NativeAgentBranchRow)
    @Query("SELECT * FROM native_agent_branches WHERE command=:command") fun branch(command: String): NativeAgentBranchRow?
    @Query("SELECT * FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND (createdAt>:afterTime OR (createdAt=:afterTime AND id>:afterId)) AND (createdAt<:endTime OR (createdAt=:endTime AND id<=:endId)) ORDER BY createdAt,id LIMIT 8")
    fun branchPage(instance: String, generation: Long, chat: String, afterTime: Long, afterId: String,
        endTime: Long, endId: String): List<NativeAgentMessageRow>
    @Query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND (createdAt<:time OR (createdAt=:time AND id<=:id))")
    fun branchChars(instance: String, generation: Long, chat: String, time: Long, id: String): Long
    @Query("SELECT * FROM native_agent_chats WHERE instanceId=:instance AND generation=:generation ORDER BY createdAt DESC,id DESC LIMIT 50")
    fun chats(instance: String, generation: Long): List<NativeAgentChatRow>
    @Query("SELECT * FROM native_agent_chats WHERE id=:id") fun chat(id: String): NativeAgentChatRow?
    @Query("UPDATE native_agent_chats SET selected=0 WHERE instanceId=:instance AND generation=:generation")
    fun deselectChats(instance: String, generation: Long): Int
    @Query("UPDATE native_agent_chats SET selected=1 WHERE id=:id") fun selectChat(id: String): Int
    @Insert fun insertChat(row: NativeAgentChatRow)
    @Query("SELECT * FROM native_agent_chats WHERE instanceId=:instance AND generation=:generation AND selected=1 ORDER BY createdAt DESC,id DESC LIMIT 1")
    fun currentChat(instance: String, generation: Long): NativeAgentChatRow?
    @Query("SELECT * FROM native_agent_contexts WHERE instanceId=:instance AND generation=:generation AND chatId=:chat")
    fun context(instance: String, generation: Long, chat: String): NativeAgentContextRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun context(row: NativeAgentContextRow)
    @Query("SELECT id,role,substr(body,1,2000) AS preview,createdAt FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat ORDER BY createdAt DESC,id DESC LIMIT 50")
    fun messagePreviews(instance: String, generation: Long, chat: String): List<NativeAgentMessageListRow>
    @Query("SELECT id,role,substr(body,1,2000) AS preview,createdAt FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND (:time IS NULL OR createdAt<:time OR (createdAt=:time AND id<:id)) ORDER BY createdAt DESC,id DESC LIMIT 31")
    fun messagePage(instance: String, generation: Long, chat: String, time: Long?, id: String?): List<NativeAgentMessageListRow>
    @Query("SELECT * FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND (createdAt>:time OR (createdAt=:time AND id>:id)) ORDER BY createdAt,id LIMIT 32")
    fun archivePage(instance: String, generation: Long, chat: String, time: Long, id: String): List<NativeAgentMessageRow>
    @Query("SELECT * FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND payload IS NOT NULL AND (createdAt>:time OR (createdAt=:time AND id>:id)) ORDER BY createdAt,id LIMIT 257")
    fun contextMessages(instance: String, generation: Long, chat: String, time: Long, id: String): List<NativeAgentMessageRow>
    @Query("SELECT COUNT(*) FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND payload IS NOT NULL AND (createdAt>:time OR (createdAt=:time AND id>:id))")
    fun contextCount(instance: String, generation: Long, chat: String, time: Long, id: String): Int
    @Query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND payload IS NOT NULL AND (createdAt>:time OR (createdAt=:time AND id>:id))")
    fun contextChars(instance: String, generation: Long, chat: String, time: Long, id: String): Long
    @Query("SELECT COUNT(*) FROM native_agent_chats WHERE instanceId=:instance AND generation=:generation")
    fun chatCount(instance: String, generation: Long): Int
    @Query("SELECT id,rowIndex,state FROM native_agent_approvals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state NOT IN ('PENDING','CONSUMED') ORDER BY createdAt DESC LIMIT 10")
    fun results(instance: String, generation: Long, chat: String): List<NativeAgentResultRow>
    @Query("SELECT COALESCE(MAX(createdAt),0) FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat")
    fun lastMessageTime(instance: String, generation: Long, chat: String): Long
    @Insert fun insertMessage(row: NativeAgentMessageRow)
    @Query("SELECT * FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat ORDER BY createdAt DESC, id DESC LIMIT 50")
    fun messages(instance: String, generation: Long, chat: String): List<NativeAgentMessageRow>
    @Query("SELECT * FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND payload IS NOT NULL ORDER BY createdAt,id LIMIT 257")
    fun history(instance: String, generation: Long, chat: String): List<NativeAgentMessageRow>
    @Query("SELECT COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND payload IS NOT NULL")
    fun historyChars(instance: String, generation: Long, chat: String): Long
    @Query("SELECT COUNT(*) FROM native_agent_messages WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND payload IS NOT NULL")
    fun historyCount(instance: String, generation: Long, chat: String): Int
    @Query("SELECT * FROM native_agent_messages WHERE id=:id") fun message(id: String): NativeAgentMessageRow?
    @Insert fun insertApproval(row: NativeAgentApprovalRow)
    @Query("SELECT * FROM native_agent_approvals WHERE id=:id") fun approval(id: String): NativeAgentApprovalRow?
    @Query("SELECT * FROM native_agent_approvals WHERE instanceId=:instance AND generation=:generation AND chatId=:chat AND state IN ('PENDING','CONSUMED') ORDER BY createdAt LIMIT 1")
    fun unresolved(instance: String, generation: Long, chat: String): NativeAgentApprovalRow?
    @Query("UPDATE native_agent_approvals SET state=:next WHERE id=:id AND state=:expected")
    fun settle(id: String, expected: String, next: String): Int
    @Query("UPDATE native_agent_approvals SET state='CANCELLED' WHERE instanceId=:instance AND generation=:generation AND runId=:run AND state='PENDING'")
    fun cancelPending(instance: String, generation: Long, run: String): Int
    @Insert fun insertRequest(row: NativeAgentRequestRow)
    @Query("SELECT * FROM native_agent_requests WHERE attemptId=:attempt") fun request(attempt: String): NativeAgentRequestRow?
    @Query("UPDATE native_agent_requests SET state='DISPATCHED' WHERE attemptId=:attempt AND requestKey=:key AND state='PREPARED'")
    fun dispatch(attempt: String, key: String): Int
    @Query("UPDATE native_agent_requests SET state='COMPLETED', responseText=:text, responseId=:message, usage=:usage WHERE attemptId=:attempt AND requestKey=:key AND state='DISPATCHED'")
    fun complete(attempt: String, key: String, text: String, message: String, usage: String): Int
}
