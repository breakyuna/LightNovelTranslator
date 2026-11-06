package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.core.text.TextPreserveEntry
import com.breakyuna.noveltranslator.core.text.TextReplacementEntry
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * 质量规则槽位类型。
 *
 * 对照 LinguaGacha `src/domain/quality.ts` 的四槽位模型，术语表槽位已由
 * `LexiconEntryEntity` 承担（LNT 的术语模型更丰富），因此这里只有三个槽位：
 * 文本保护、译前替换、译后替换。
 *
 * [storageKey] 是落库的稳定值，不允许随枚举顺序变化。
 */
enum class QualityRuleType(val storageKey: String) {
    TEXT_PRESERVE("TEXT_PRESERVE"),
    PRE_REPLACEMENT("PRE_REPLACEMENT"),
    POST_REPLACEMENT("POST_REPLACEMENT");

    companion object {
        fun fromStorageKey(raw: String?): QualityRuleType? = entries.firstOrNull { it.storageKey == raw }
    }
}

/**
 * 槽位模式。
 *
 * - 文本保护沿用 LinguaGacha 三态：OFF 完全关闭、SMART 只用内置基础规则、
 *   CUSTOM 在内置基础规则之上叠加用户规则；
 * - 替换槽位描述"条目如何解释"：LITERAL 全部按字面量、REGEX 全部按正则、
 *   CUSTOM 尊重每条目自己的 `isRegex`（默认）。
 *
 * 替换槽位没有 OFF：槽位总开关由实体上的 `enabled` 承担（对齐 LG 的
 * `*_enable` meta），避免同一个语义出现两个互相矛盾的来源。
 */
enum class QualityRuleMode {
    OFF,
    SMART,
    CUSTOM,
    LITERAL,
    REGEX;

    companion object {
        fun allowedModes(type: QualityRuleType): List<QualityRuleMode> = when (type) {
            QualityRuleType.TEXT_PRESERVE -> listOf(OFF, SMART, CUSTOM)
            QualityRuleType.PRE_REPLACEMENT, QualityRuleType.POST_REPLACEMENT ->
                listOf(LITERAL, REGEX, CUSTOM)
        }

        fun defaultMode(type: QualityRuleType): QualityRuleMode = when (type) {
            QualityRuleType.TEXT_PRESERVE -> SMART
            QualityRuleType.PRE_REPLACEMENT, QualityRuleType.POST_REPLACEMENT -> CUSTOM
        }

        fun fromStorageKey(raw: String?): QualityRuleMode? = entries.firstOrNull { it.name == raw }
    }
}

/** 一条质量规则条目。三个槽位共用同一套产品层结构，[note] 只服务于展示。 */
sealed interface QualityRuleEntry {
    val note: String
}

/**
 * 文本保护条目：用户自定义正则。
 *
 * 与 `core/text` 的 `TextPreserveEntry` 一一对应，差别只在字段命名
 * （产品层沿用 LG 的 `src` / `info` 语义）。
 */
data class TextPreserveRuleEntry(
    val src: String,
    override val note: String = ""
) : QualityRuleEntry

/**
 * 译前 / 译后替换条目。
 *
 * 语义与 `core/text` 的 `TextReplacementEntry` 一致：字面量走 NFKC + casefold
 * 匹配，正则允许 `$1` / `${name}` 捕获组语法。
 */
data class ReplacementRuleEntry(
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    override val note: String = ""
) : QualityRuleEntry

fun TextPreserveRuleEntry.toTextPreserveEntry(): TextPreserveEntry =
    TextPreserveEntry(pattern = src, info = note)

fun ReplacementRuleEntry.toTextReplacementEntry(): TextReplacementEntry =
    TextReplacementEntry(src = pattern, dst = replacement, regex = isRegex, caseSensitive = caseSensitive)

/**
 * 条目数组的 JSON 编解码。
 *
 * 每个规则集只承载一种槽位，因此数组是同质的：不需要 Moshi 多态适配器，
 * 反射适配器（`KotlinJsonAdapterFactory`）即可工作，且对未知字段自动跳过，
 * 便于后续追加字段而不破坏旧数据。
 */
object QualityRuleJson {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()

    private val textPreserveListType = Types.newParameterizedType(
        List::class.java,
        TextPreserveRuleEntry::class.java
    )

    private val replacementListType = Types.newParameterizedType(
        List::class.java,
        ReplacementRuleEntry::class.java
    )

    fun encode(type: QualityRuleType, entries: List<QualityRuleEntry>): String = when (type) {
        QualityRuleType.TEXT_PRESERVE ->
            moshi.adapter<List<TextPreserveRuleEntry>>(textPreserveListType).toJson(entries.filterIsInstance<TextPreserveRuleEntry>())
        QualityRuleType.PRE_REPLACEMENT, QualityRuleType.POST_REPLACEMENT ->
            moshi.adapter<List<ReplacementRuleEntry>>(replacementListType).toJson(entries.filterIsInstance<ReplacementRuleEntry>())
    }

    /**
     * 解析条目数组。类型不符或结构损坏时返回空列表而不是抛错：
     * 规则数据来自用户配置，一条坏记录不应让整本书的翻译失败，
     * 配置层（[QualityRuleValidator]）负责在保存前拦截。
     */
    fun decode(type: QualityRuleType, json: String): List<QualityRuleEntry> {
        if (json.isBlank()) return emptyList()
        val normalized = json.trim()
        if (!normalized.startsWith("[")) return emptyList()
        return runCatching {
            when (type) {
                QualityRuleType.TEXT_PRESERVE ->
                    moshi.adapter<List<TextPreserveRuleEntry>>(textPreserveListType).fromJson(normalized).orEmpty()
                QualityRuleType.PRE_REPLACEMENT, QualityRuleType.POST_REPLACEMENT ->
                    moshi.adapter<List<ReplacementRuleEntry>>(replacementListType).fromJson(normalized).orEmpty()
            }
        }.getOrElse { emptyList() }
    }
}
