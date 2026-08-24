package com.breakyuna.noveltranslator.data.model

enum class TranslationChunkState { PENDING, RUNNING, RETRY_WAIT, COMPLETED, FAILED, CANCELLED }

@androidx.room.Entity(
    tableName = "translation_chunks",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = TranslationRunEntity::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        ),
        androidx.room.ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["runId", "chapterId", "chunkIndex", "parentChunkKey"], unique = true),
        androidx.room.Index(value = ["chapterId"])
    ]
)
data class TranslationChunkEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val chapterId: Long,
    val chapterIndex: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val sourceHash: String,
    val parentChunkId: Long? = null,
    /** Non-null uniqueness key because SQLite treats NULL values as distinct in unique indexes. */
    val parentChunkKey: Long = parentChunkId ?: ROOT_PARENT_KEY,
    val state: String = TranslationChunkState.PENDING.name,
    val attemptCount: Int = 0,
    val translatedTempFileName: String? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cost: Double = 0.0,
    val durationMs: Long = 0,
    val lastErrorCategory: String? = null,
    val lastErrorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROOT_PARENT_KEY = 0L
    }
}
