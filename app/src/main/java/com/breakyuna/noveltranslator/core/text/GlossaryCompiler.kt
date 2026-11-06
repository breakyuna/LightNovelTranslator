package com.breakyuna.noveltranslator.core.text

/**
 * 一条术语。
 *
 * [entryId] 是跨页面、统计与校对链路共用的命中身份，必须稳定；
 * [src] / [dst] 为空白的条目不参与匹配（源文空白无法命中，目标空白不形成告警）。
 */
data class GlossaryEntry(
    val entryId: String,
    val src: String,
    val dst: String,
    val info: String = "",
    val caseSensitive: Boolean = false
)

/** 源侧命中：术语 + 命中的源文本（调用方自行决定字段归属）。 */
data class GlossarySourceMatch(
    val entry: GlossaryEntry
)

/** 单条术语的应用结果。 */
data class GlossaryApplication(
    val entryId: String,
    val src: String,
    val dst: String,
    val caseSensitive: Boolean,
    val applied: Boolean
)

/** 术语应用的四态，与仓库现有 `GlossaryQaStatus` 的词表保持一致。 */
enum class GlossaryApplicationState {
    NONE,
    APPLIED,
    PARTIAL,
    MISSING
}

/** 一次译后验证的结果。 */
data class GlossaryVerification(
    val applications: List<GlossaryApplication>
) {
    val state: GlossaryApplicationState
        get() = resolveGlossaryApplicationState(applications)
}

/**
 * 编译后的术语表。
 *
 * 移植自 LinguaGacha `glossary.ts` 的 compile_glossary / match_glossary_source /
 * evaluate_glossary_applications：源文与目标各自编译成字面匹配器，
 * 因此两侧都自动获得 NFKC + casefold 语义。
 *
 * 与来源实现的差异：LNT 的术语按单文本校验（一章正文 + 一章译文），
 * 不区分 `src`/`name_src` 字段；下一阶段接入引擎时如需分字段，
 * 可以按字段各建一个 [CompiledGlossary] 实例。
 */
class CompiledGlossary internal constructor(
    val entries: List<GlossaryEntry>,
    private val sourceMatcher: LiteralMatcher,
    private val targetMatcher: LiteralMatcher
) {

    /** 只在源文里查找术语，按输入顺序返回命中的规范术语。 */
    fun matchSource(text: String): List<GlossarySourceMatch> {
        val matchedIds = HashSet<String>()
        sourceMatcher.scanKeys(text) { key -> matchedIds += key }
        if (matchedIds.isEmpty()) return emptyList()
        return entries.filter { it.entryId in matchedIds }.map { GlossarySourceMatch(it) }
    }

    /** 按源文命中逐一确认译文字段是否包含术语译文。 */
    fun verifyApplication(
        sourceMatches: List<GlossarySourceMatch>,
        translatedText: String
    ): GlossaryVerification {
        val appliedIds = HashSet<String>()
        targetMatcher.scanKeys(translatedText) { key -> appliedIds += key }
        val applications = sourceMatches.mapNotNull { match ->
            val entry = match.entry
            // 目标空白的术语不形成告警，与来源实现一致。
            if (entry.dst.isBlank()) {
                null
            } else {
                GlossaryApplication(
                    entryId = entry.entryId,
                    src = entry.src,
                    dst = entry.dst,
                    caseSensitive = entry.caseSensitive,
                    applied = entry.entryId in appliedIds
                )
            }
        }
        return GlossaryVerification(applications)
    }
}

/**
 * 编译术语表。
 *
 * 重复 [GlossaryEntry.entryId] 只保留首条：entry_id 同时是字面匹配器的 key，
 * 重复会让编译直接失败；术语表来自项目配置，去重比抛错更稳妥。
 */
fun compileGlossary(entries: List<GlossaryEntry>): CompiledGlossary {
    val distinct = LinkedHashMap<String, GlossaryEntry>()
    for (entry in entries) {
        if (entry.entryId !in distinct) distinct[entry.entryId] = entry
    }
    // 源文空白的术语无法命中，直接剔除。
    val matchable = distinct.values.filter { it.src.isNotBlank() }
    return CompiledGlossary(
        entries = matchable,
        sourceMatcher = compileLiteralPatterns(
            matchable.map { LiteralPattern(it.entryId, it.src, it.caseSensitive) }
        ),
        targetMatcher = compileLiteralPatterns(
            matchable.mapNotNull { entry ->
                if (entry.dst.isBlank()) {
                    null
                } else {
                    LiteralPattern(entry.entryId, entry.dst, entry.caseSensitive)
                }
            }
        )
    )
}

/** 将逐条应用结果收敛为四态。 */
fun resolveGlossaryApplicationState(applications: List<GlossaryApplication>): GlossaryApplicationState {
    if (applications.isEmpty()) return GlossaryApplicationState.NONE
    val appliedCount = applications.count { it.applied }
    return when (appliedCount) {
        applications.size -> GlossaryApplicationState.APPLIED
        0 -> GlossaryApplicationState.MISSING
        else -> GlossaryApplicationState.PARTIAL
    }
}

/** 生成供提示词与诊断信息复用的稳定单行术语表示。 */
fun formatGlossaryEntry(entry: GlossaryEntry): String =
    if (entry.info.isEmpty()) {
        "${entry.src} -> ${entry.dst}"
    } else {
        "${entry.src} -> ${entry.dst} #${entry.info}"
    }
