package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "Unknown",
    val sourceFileName: String,
    val fileType: String, // "TXT", "EPUB"
    val projectDirPath: String,
    val sourceLanguage: String = "Auto",
    val targetLanguage: String = "Chinese",
    val translationStyle: String = "Literary Novel", // "Literary Novel", "Faithful", "Fluent & Natural", "Light Novel"
    val totalChapters: Int = 0,
    val translatedChapters: Int = 0,
    val totalOriginalWords: Int = 0,
    val totalPromptTokens: Long = 0,
    val totalCompletionTokens: Long = 0,
    val totalCost: Double = 0.0,
    @ColumnInfo(defaultValue = "''") val costCurrency: String = "USD",
    val defaultProviderId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
