package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TermCategory(val label: String) {
    CHARACTER("Character / 人物"),
    LOCATION("Location / 地点"),
    LORE("Lore & Faction / 势力背景"),
    SKILL("Skill & Ability / 功法技能"),
    ITEM("Item & Equipment / 物品装备"),
    HONORIFIC("Honorific & Title / 称谓头衔"),
    CUSTOM("Custom / 自定义")
}

@Entity(
    tableName = "glossary",
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
data class GlossaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val originalTerm: String,
    val translatedTerm: String,
    val category: TermCategory = TermCategory.CHARACTER,
    val notes: String = "",
    val isAutoExtracted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
