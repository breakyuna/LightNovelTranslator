package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ChapterStatus {
    PENDING,
    TRANSLATING,
    COMPLETED,
    ERROR
}

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val chapterIndex: Int,
    val title: String,
    val originalFileName: String, // Relative file name inside project/chapters/
    val translatedFileName: String, // Relative file name inside project/translations/
    val originalWordCount: Int = 0,
    val translatedWordCount: Int = 0,
    val status: ChapterStatus = ChapterStatus.PENDING,
    val summary: String = "", // Chapter context summary for context continuity
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val estimatedCost: Double = 0.0,
    val errorMessage: String? = null,
    val lastTranslatedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
