package com.breakyuna.noveltranslator.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import com.breakyuna.noveltranslator.core.quality.QualityRuleEntry
import com.breakyuna.noveltranslator.core.quality.QualityRuleJson
import com.breakyuna.noveltranslator.core.quality.QualityRuleMode
import com.breakyuna.noveltranslator.core.quality.QualityRuleType

/**
 * 一个翻译工程的质量规则槽位。
 *
 * 对照 LinguaGacha `src/domain/quality.ts` 的槽位模型，但**不动词表**：
 * 术语表槽位由 [LexiconEntryEntity] 承担（模型更丰富），这里只有
 * 文本保护、译前替换、译后替换三个槽位。
 *
 * 复合主键（工程 + 槽位）保证一个工程的每个槽位最多一行，因此"整个工程的
 * 三类规则一起保存"可以在一个事务里完成而不会留下半套状态。
 */
@Entity(
    tableName = "quality_rule_sets",
    primaryKeys = ["translationProjectId", "type"],
    foreignKeys = [
        ForeignKey(
            entity = TranslationProjectV2Entity::class,
            parentColumns = ["id"],
            childColumns = ["translationProjectId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class QualityRuleSetEntity(
    val translationProjectId: Long,
    /** [QualityRuleType.storageKey]，落库稳定值。 */
    val type: String,
    /** 槽位总开关。关闭时该槽位完全不参与翻译流程。 */
    val enabled: Boolean,
    /** [QualityRuleMode.name]，非法值按槽位默认值归一。 */
    val mode: String,
    /** Moshi 序列化的条目数组，条目类型由 [type] 决定。 */
    val entriesJson: String,
    /** 每次用户保存自增，供设置页展示与后续导入冲突检测。 */
    val revision: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** 解析后的条目列表；数据损坏时返回空列表而不是抛错。 */
    fun entries(): List<QualityRuleEntry> =
        QualityRuleType.fromStorageKey(type)?.let { QualityRuleJson.decode(it, entriesJson) }.orEmpty()

    /** 归一后的模式；未知值回落槽位默认值。 */
    fun resolvedMode(): QualityRuleMode =
        QualityRuleMode.fromStorageKey(mode)
            ?: QualityRuleType.fromStorageKey(type)?.let { QualityRuleMode.defaultMode(it) }
            ?: QualityRuleMode.OFF
}

/** 槽位存储键常量，供 DAO 查询与 UI 直接引用而不用拼字符串。 */
object QualityRuleTypes {
    const val TEXT_PRESERVE: String = QualityRuleType.TEXT_PRESERVE.storageKey
    const val PRE_REPLACEMENT: String = QualityRuleType.PRE_REPLACEMENT.storageKey
    const val POST_REPLACEMENT: String = QualityRuleType.POST_REPLACEMENT.storageKey
}
