package com.breakyuna.noveltranslator.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.breakyuna.noveltranslator.data.model.AgentMessageEntity
import com.breakyuna.noveltranslator.data.model.AgentSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentSessionDao {
    @Insert
    suspend fun insert(session: AgentSessionEntity): Long

    @Update
    suspend fun update(session: AgentSessionEntity)

    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_sessions WHERE id = :sessionId")
    fun observeById(sessionId: Long): Flow<AgentSessionEntity?>

    @Query("SELECT * FROM agent_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: Long): AgentSessionEntity?

    @Delete
    suspend fun delete(session: AgentSessionEntity)

    @Query("DELETE FROM agent_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)
}

@Dao
interface AgentMessageDao {
    @Insert
    suspend fun insert(message: AgentMessageEntity): Long

    @Insert
    suspend fun insertAll(messages: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySession(sessionId: Long): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: Long): List<AgentMessageEntity>

    @Query("UPDATE agent_messages SET content = :content WHERE id = :messageId")
    suspend fun updateContent(messageId: Long, content: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
