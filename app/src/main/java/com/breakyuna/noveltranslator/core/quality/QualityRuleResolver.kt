package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.core.text.TextPreserveEntry
import com.breakyuna.noveltranslator.core.text.TextReplacements
import com.breakyuna.noveltranslator.data.model.QualityRuleSetEntity
import kotlinx.coroutines.CancellationException

/**
 * 一次运行内生效的质量规则快照。
 *
 * [protectionEntries] 是"始终叠加基础规则"之后的最终规则集；
 * [preReplacements] / [postReplacements] 为 null 表示该槽位关闭。
 */
data class ResolvedQualityRules(
    val protectionMode: QualityRuleMode,
    val protectionEntries: List<TextPreserveEntry>,
    val preReplacements: TextReplacements?,
    val postReplacements: TextReplacements?,
    val fromFallback: Boolean = false
) {
    val protectionEnabled: Boolean get() = protectionMode != QualityRuleMode.OFF
}

/**
 * 从数据库解析质量规则，失败时回退默认规则集。
 *
 * 回退策略与引擎现有的 Prompt Profile 物化一致：**读不到数据不等于没有规则**，
 * 旧工程（迁移前没有任何规则行）必须得到与升级前完全一致的行为，
 * 因此回退值就是 [QualityRuleDefaults.fallbackResolvedRules]。
 *
 * 依赖用函数而不是 DAO 注入，使本类可以在纯 JVM 测试里用假数据驱动。
 */
class QualityRuleResolver(
    private val loadRuleSets: suspend (Long) -> List<QualityRuleSetEntity>,
    private val onDiagnostic: (String, Throwable?) -> Unit = { _, _ -> }
) {

    suspend fun resolve(projectId: Long): ResolvedQualityRules {
        val rows = try {
            loadRuleSets(projectId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onDiagnostic("质量规则读取失败，已回退默认规则集: ${error.localizedMessage ?: error.javaClass.simpleName}", error)
            return QualityRuleDefaults.fallbackResolvedRules()
        }
        return resolveFromRows(rows)
    }

    /** 纯函数解析，便于测试与复用。 */
    fun resolveFromRows(rows: List<QualityRuleSetEntity>): ResolvedQualityRules {
        val byType = rows.mapNotNull { row ->
            val type = QualityRuleType.fromStorageKey(row.type) ?: return@mapNotNull null
            type to row
        }.toMap()

        val preserve = resolveProtection(byType[QualityRuleType.TEXT_PRESERVE])
        val pre = resolveReplacements(QualityRuleType.PRE_REPLACEMENT, byType[QualityRuleType.PRE_REPLACEMENT])
        val post = resolveReplacements(QualityRuleType.POST_REPLACEMENT, byType[QualityRuleType.POST_REPLACEMENT])
        return ResolvedQualityRules(
            protectionMode = preserve.first,
            protectionEntries = preserve.second,
            preReplacements = pre,
            postReplacements = post,
            fromFallback = rows.isEmpty()
        )
    }

    /**
     * 保护槽位解析。
     *
     * 基础规则始终叠加：SMART 只用基础规则（= 旧硬编码行为），CUSTOM 再叠加
     * 用户条目，OFF 完全关闭（用户明确选择放弃保护时尊重该选择）。
     */
    private fun resolveProtection(row: QualityRuleSetEntity?): Pair<QualityRuleMode, List<TextPreserveEntry>> {
        val base = QualityRuleDefaults.textPreserveBaseRules
        if (row == null) return QualityRuleMode.SMART to base
        if (!row.enabled) return QualityRuleMode.OFF to emptyList()
        val mode = QualityRuleMode.fromStorageKey(row.mode) ?: QualityRuleMode.defaultMode(QualityRuleType.TEXT_PRESERVE)
        if (mode == QualityRuleMode.OFF) return QualityRuleMode.OFF to emptyList()
        val userEntries = QualityRuleJson.decode(QualityRuleType.TEXT_PRESERVE, row.entriesJson)
            .filterIsInstance<TextPreserveRuleEntry>()
            .filter { it.src.isNotBlank() }
        if (mode == QualityRuleMode.SMART) return QualityRuleMode.SMART to base
        val usable = userEntries.filter { it.isCompilableRegex() }
        val dropped = userEntries.size - usable.size
        if (dropped > 0) {
            onDiagnostic("文本保护有 $dropped 条用户规则无法编译，已跳过", null)
        }
        return QualityRuleMode.CUSTOM to (base + usable.map { it.toTextPreserveEntry() })
    }

    /**
     * 替换槽位解析。
     *
     * 槽位未启用或没有可用条目时返回 null，调用方因此可以直接跳过这一步，
     * 不需要再判断"空规则集"与"关闭"的差别。
     */
    private fun resolveReplacements(type: QualityRuleType, row: QualityRuleSetEntity?): TextReplacements? {
        if (row == null || !row.enabled) return null
        val entries = QualityRuleJson.decode(type, row.entriesJson)
            .filterIsInstance<ReplacementRuleEntry>()
            .filter { it.pattern.isNotBlank() }
        if (entries.isEmpty()) return null
        val mode = QualityRuleMode.fromStorageKey(row.mode)
            ?: QualityRuleMode.defaultMode(type)
        val effective = when (mode) {
            QualityRuleMode.LITERAL -> entries.map { it.copy(isRegex = false) }
            QualityRuleMode.REGEX -> entries.map { it.copy(isRegex = true) }
            else -> entries
        }
        val usable = effective.filter { it.isCompilableRegex() }
        val dropped = effective.size - usable.size
        if (dropped > 0) {
            onDiagnostic("${type.label()}有 $dropped 条规则无法编译，已跳过", null)
        }
        return QualityRuleDefaults.compileReplacements(usable)
    }
}
