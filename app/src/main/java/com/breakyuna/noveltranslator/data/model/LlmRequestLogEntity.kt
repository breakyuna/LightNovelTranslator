package com.breakyuna.noveltranslator.data.model

@androidx.room.Entity(
    tableName = "llm_request_logs",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["projectId"]),
        androidx.room.Index(value = ["runId"]),
        androidx.room.Index(value = ["timestamp"])
    ]
)
data class LlmRequestLogEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long? = null,
    /** Null for global operations such as provider connection tests. */
    val projectId: Long?,
    val chapterId: Long? = null,
    val chapterIndex: Int? = null,
    val chunkIndex: Int? = null,
    val attemptNumber: Int,
    val operation: String,
    val providerId: Long,
    val providerName: String,
    val modelName: String,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val currency: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val usageSource: String,
    val estimatedCost: Double,
    val durationMs: Long,
    val httpStatus: Int? = null,
    val errorCategory: String? = null,
    val errorMessage: String? = null,
    val finishReason: String? = null,
    val requestId: String? = null,
    val isSuccess: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
