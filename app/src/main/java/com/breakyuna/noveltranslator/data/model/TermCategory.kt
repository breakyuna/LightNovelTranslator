package com.breakyuna.noveltranslator.data.model

/** Categories allowed for AI terminology candidates and confirmed lexicon entries. */
enum class TermCategory(val label: String) {
    CHARACTER("Character / 人物"),
    LOCATION("Location / 地点"),
    LORE("Lore & Faction / 势力背景"),
    SKILL("Skill & Ability / 功法技能"),
    ITEM("Item & Equipment / 物品装备"),
    HONORIFIC("Honorific & Title / 称谓头衔"),
    CUSTOM("Custom / 自定义")
}
