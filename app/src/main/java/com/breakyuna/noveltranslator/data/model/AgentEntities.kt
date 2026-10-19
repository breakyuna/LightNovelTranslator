package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One agent conversation. Sessions are project-agnostic: a session may talk about a book, a
 * translation project or the app itself, so every scope column is nullable.
 */
@Entity(
    tableName = "agent_sessions",
    indices = [Index("updatedAt"), Index("bookId"), Index("translationProjectId")]
)
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val bookId: Long? = null,
    val translationProjectId: Long? = null,
    val providerId: Long? = null,
    val modelName: String? = null,
    /** ACTIVE | ARCHIVED */
    val state: String = "ACTIVE",
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String = ""
)

/**
 * One message inside a session. Tool calls and their results are stored as separate rows so the
 * agent loop can rebuild the exact ordered history it sent to the model.
 */
@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("createdAt")]
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** USER | ASSISTANT | TOOL_CALL | TOOL_RESULT | SYSTEM */
    val role: String,
    val content: String = "",
    val toolName: String? = null,
    val toolCallId: String? = null,
    val toolArgsJson: String? = null,
    val toolResultJson: String? = null,
    val isError: Boolean = false,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val createdAt: Long
)
