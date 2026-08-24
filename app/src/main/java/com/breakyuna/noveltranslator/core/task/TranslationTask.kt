package com.breakyuna.noveltranslator.core.task

import java.util.UUID

enum class TaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class TranslationTaskItem(
    val id: String = UUID.randomUUID().toString(),
    val projectId: Long,
    val projectTitle: String,
    val chapterId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val providerId: Long,
    val providerName: String,
    val modelName: String,
    val status: TaskStatus = TaskStatus.QUEUED,
    val progressPercent: Float = 0f,
    val currentChunk: Int = 0,
    val totalChunks: Int = 1,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val cost: Double = 0.0,
    val currency: String = "USD",
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null
)
