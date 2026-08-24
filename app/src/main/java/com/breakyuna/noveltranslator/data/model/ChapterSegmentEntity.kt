package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted source/translation segment relation used by the reader and paragraph re-translation. */
@Entity(
    tableName = "chapter_segments",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chapterId"]),
        Index(value = ["chapterId", "sourceSegmentId"])
    ]
)
data class ChapterSegmentEntity(
    @PrimaryKey val stableKey: String,
    val chapterId: Long,
    val sourceSegmentId: String,
    val translatedSegmentId: String,
    val sourceOrdinal: Int?,
    val translatedOrdinal: Int?,
    val sourceText: String,
    val translatedText: String,
    val segmentType: String,
    val relation: String,
    val sourceHash: String,
    val updatedAt: Long = System.currentTimeMillis()
)
