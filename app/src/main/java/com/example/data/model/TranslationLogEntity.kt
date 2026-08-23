package com.example.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_logs",
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
data class TranslationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val chapterIndex: Int,
    val chapterTitle: String,
    val modelName: String,
    val providerName: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val estimatedCost: Double,
    val durationMs: Long,
    val isSuccess: Boolean,
    val message: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
