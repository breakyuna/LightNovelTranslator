package com.breakyuna.noveltranslator.data.model

enum class TranslationRunState {
    QUEUED, RUNNING, RETRY_WAIT, PAUSE_REQUESTED, PAUSED, INTERRUPTED, COMPLETED, FAILED, CANCELLED
}

@androidx.room.Entity(
    tableName = "translation_runs",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index(value = ["projectId"]), androidx.room.Index(value = ["state"])]
)
data class TranslationRunEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val providerId: Long,
    val providerName: String,
    val modelName: String,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val currency: String,
    val state: String = TranslationRunState.QUEUED.name,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastErrorCategory: String? = null,
    val lastErrorMessage: String? = null,
    val nextRetryAt: Long? = null,
    val totalPromptTokens: Long = 0,
    val totalCompletionTokens: Long = 0,
    val totalCost: Double = 0.0
)
