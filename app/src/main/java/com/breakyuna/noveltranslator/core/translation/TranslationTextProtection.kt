package com.breakyuna.noveltranslator.core.translation

import com.breakyuna.noveltranslator.core.quality.QualityRuleDefaults
import com.breakyuna.noveltranslator.core.text.TextPreserveEntry
import com.breakyuna.noveltranslator.core.text.TextProtectionRule

/**
 * Text that must survive a model round trip byte-for-byte. The model sees a short opaque marker;
 * the original value is restored locally after decoding and is then verified by deterministic QA.
 */
data class ProtectedToken(val marker: String, val original: String)

data class ProtectedText(val masked: String, val tokens: List<ProtectedToken>)

/**
 * 掩蔽执行体。
 *
 * 合并历史（对标审计 T2）：这里原先是一条硬编码的 alternation 正则。规则驱动体系
 * （`core/text` 的 `TextProtectionRule`）落地后，本类**委托**新体系而不是与之并存：
 * 硬编码模式的每一个分支都收编为 [QualityRuleDefaults.textPreserveBaseRules] 里的一条
 * `TextPreserveEntry`，由产品层（设置页）决定最终规则集。
 *
 * 选择"旧类委托新体系"而不是"在调用点组合"的理由：
 * 1. 掩蔽标记格式（`__LNT_PROTECTED_n__`）、编号顺序与恢复语义是 QA
 *    （`PROTECTED_TOKEN_CHANGED`）和分块预算切分的契约，必须只有一处实现；
 * 2. 委托后 `protect(text)` 的默认路径仍然命中同一份基础规则，无规则数据的旧工程
 *    与升级前逐字节一致；调用点组合会把"默认规则"的职责扩散到每个调用方。
 *
 * 保持不变的两个关键语义：
 * - **空白段不掩蔽**。旧 alternation 的每个分支都要求至少一个可见字符，因此旧实现
 *   永远不可能命中纯空白；跳过空白段是新旧行为一致的充要条件，也避免用户添加
 *   `\s` 之类规则时把正文里每个空格都变成占位符。
 * - **按源文顺序编号**。`TextProtectionRule` 的重叠裁决已保证候选项按 index 排序，
 *   因此编号顺序与旧实现的 `findAll` 一致。
 */
object TranslationTextProtection {

    const val MARKER_PREFIX: String = "__LNT_PROTECTED_"
    const val MARKER_SUFFIX: String = "__"

    /** 默认规则的编译结果：整个进程只编译一次。 */
    private val defaultRule: TextProtectionRule by lazy {
        TextProtectionRule(QualityRuleDefaults.textPreserveBaseRules)
    }

    // 单条目缓存：一次运行内规则集不变，按内容命中，避免每段都重新编译正则。
    @Volatile
    private var cachedEntries: List<TextPreserveEntry>? = null

    @Volatile
    private var cachedRule: TextProtectionRule? = null

    /** 用默认（收编旧行为的基础）规则掩蔽。 */
    fun protect(text: String): ProtectedText = mask(text, defaultRule)

    /**
     * 用调用方给定的规则集掩蔽。空规则集表示"不保护"，原文直出。
     *
     * 单条正则无法编译时整组规则降级为不保护并保留原文：运行期不抛异常，
     * 配置层的 [com.breakyuna.noveltranslator.core.quality.QualityRuleValidator]
     * 负责在保存前拦截坏规则。
     */
    fun protect(text: String, entries: List<TextPreserveEntry>): ProtectedText {
        if (text.isEmpty()) return ProtectedText(text, emptyList())
        if (entries.isEmpty()) return ProtectedText(text, emptyList())
        return mask(text, ruleFor(entries) ?: return ProtectedText(text, emptyList()))
    }

    /**
     * Restore only markers belonging to this segment. Unknown or duplicated markers are kept so
     * QA can report a structural mismatch instead of silently accepting model output.
     */
    fun restore(text: String, tokens: List<ProtectedToken>): String =
        tokens.fold(text) { current, token -> current.replace(token.marker, token.original) }

    /**
     * 源文 / 译文里的保护段序列，QA 用它检测"保护段被改动"。
     *
     * 始终按默认基础规则计算，不跟随项目的自定义规则：QA 是跨工程的确定性安全网，
     * 它的 token 集必须稳定，否则一次规则编辑就能让已落库章节的完整性判定翻转。
     * 用户自定义规则由 `residualMarkerPattern`（模型回吐了未恢复的标记）与
     * `[IMG:]` 专项检查兜底。
     */
    fun tokenValues(text: String): List<String> = protect(text).tokens.map { it.original }

    private fun mask(text: String, rule: TextProtectionRule): ProtectedText {
        val tokens = ArrayList<ProtectedToken>()
        val masked = rule.replace(text) { value, _ ->
            if (value.isBlank()) {
                value
            } else {
                val marker = MARKER_PREFIX + tokens.size + MARKER_SUFFIX
                tokens += ProtectedToken(marker, value)
                marker
            }
        }
        return ProtectedText(masked, tokens)
    }

    private fun ruleFor(entries: List<TextPreserveEntry>): TextProtectionRule? {
        if (cachedEntries == entries) cachedRule?.let { return it }
        val compiled = runCatching { TextProtectionRule(entries) }.getOrNull()
        synchronized(this) {
            cachedEntries = entries
            cachedRule = compiled
        }
        return compiled
    }
}
