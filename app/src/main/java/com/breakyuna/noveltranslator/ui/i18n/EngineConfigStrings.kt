package com.breakyuna.noveltranslator.ui.i18n

import androidx.compose.runtime.Composable

/**
 * Strings for the translation-engine switches exposed in the book workbench.
 *
 * These four project flags (parallel chapters, continuity mode, metadata and
 * prompt cache) used to be reachable only through the agent tool, so their copy
 * lives in its own table next to [WorkbenchUiStrings] to keep that file stable
 * for concurrent edits. English follows the global language switch exactly like
 * the rest of the platform i18n.
 */
data class EngineConfigStrings(
    val sectionTitle: String,
    val sectionSubtitle: String,
    val sectionFooter: String,
    val applyOnCreateHint: String,
    val lockedHint: String,
    val save: String,

    // Parallel chapters
    val parallelTitle: String,
    val parallelSubtitle: String,
    val parallelValue: (Int) -> String,
    val parallelDescription: String,

    // Continuity mode
    val continuityTitle: String,
    val continuitySubtitle: String,
    val continuitySourceTail: String,
    val continuityTranslationTail: String,
    val continuitySourceTailHint: String,
    val continuityTranslationTailHint: String,
    val continuityForcedSerialNote: String,

    // Metadata switch
    val metadataTitle: String,
    val metadataOnSubtitle: String,
    val metadataOffSubtitle: String,

    // Prompt cache switch
    val promptCacheTitle: String,
    val promptCacheOnSubtitle: String,
    val promptCacheOffSubtitle: String
)

private val ChineseEngineConfigStrings = EngineConfigStrings(
    sectionTitle = "翻译引擎",
    sectionSubtitle = "并发、前文连续性与记忆产出",
    sectionFooter = "引擎配置在任务启动时定格；运行中或已暂停的任务沿用启动时的快照。",
    applyOnCreateHint = "创建任务时按当前选择生效。",
    lockedHint = "任务运行中或已暂停时不能修改，请先暂停或结束任务。",
    save = "保存引擎配置",

    parallelTitle = "并发章数",
    parallelSubtitle = "同一本书内同时翻译的章节数",
    parallelValue = { count -> if (count <= 1) "1 章（串行）" else "$count 章" },
    parallelDescription = "大于 1 时同本书的多章并行翻译，速度更快，但瞬时费用速率更高；失败按章隔离，不影响其他章节。",

    continuityTitle = "前文连续性",
    continuitySubtitle = "每章请求注入的前文来源",
    continuitySourceTail = "源文前文",
    continuityTranslationTail = "上章译文",
    continuitySourceTailHint = "默认。前文只取原文，章节之间无依赖，可并行，速度优先。",
    continuityTranslationTailHint = "注入上一章译文作为前文，连贯性优先，但章节互相依赖，强制串行。",
    continuityForcedSerialNote = "已选择上章译文承接：并发章数将按 1 章串行执行。",

    metadataTitle = "章节记忆与术语候选",
    metadataOnSubtitle = "每章产出章节记忆 / 故事记忆 / 术语候选，提升后续章节上下文；每章多花输出 token。",
    metadataOffSubtitle = "默认关闭。不额外生成记忆与术语候选，输出 token 更省。",

    promptCacheTitle = "提示词缓存",
    promptCacheOnSubtitle = "默认开启。命中缓存可降低输入费用（Anthropic 为显式缓存，其余提供方自动生效）。",
    promptCacheOffSubtitle = "关闭后不复用提示词缓存，重复前缀会计入全额输入费用。"
)

private val EnglishEngineConfigStrings = EngineConfigStrings(
    sectionTitle = "Translation engine",
    sectionSubtitle = "Concurrency, continuity and memory output",
    sectionFooter = "Engine settings are frozen when a run starts; running or paused runs keep their startup snapshot.",
    applyOnCreateHint = "Applied with the current selection when the task is created.",
    lockedHint = "Engine settings cannot change while a run is running or paused. Pause or stop it first.",
    save = "Save engine settings",

    parallelTitle = "Parallel chapters",
    parallelSubtitle = "Chapters translated at the same time inside one book",
    parallelValue = { count -> if (count <= 1) "1 chapter (serial)" else "$count chapters" },
    parallelDescription = "Above 1, chapters of the same book run in parallel: faster, but a higher instantaneous spend rate. Failures stay isolated per chapter.",

    continuityTitle = "Continuity",
    continuitySubtitle = "Where the preceding-context block comes from",
    continuitySourceTail = "Source tail",
    continuityTranslationTail = "Previous translation",
    continuitySourceTailHint = "Default. Context comes from the source text only, so chapters are independent and can run in parallel.",
    continuityTranslationTailHint = "Injects the previous chapter's translation: better continuity, but chapters depend on each other and run serially.",
    continuityForcedSerialNote = "Previous-translation continuity is on, so parallel chapters fall back to 1 (serial).",

    metadataTitle = "Chapter & story memory",
    metadataOnSubtitle = "Produces chapter memory, story memory and term candidates per chapter for better later context; costs extra output tokens.",
    metadataOffSubtitle = "Off by default. No extra memory or term candidates, so fewer output tokens.",

    promptCacheTitle = "Prompt cache",
    promptCacheOnSubtitle = "On by default. Cache hits lower input cost (explicit caching on Anthropic, automatic elsewhere).",
    promptCacheOffSubtitle = "When off, repeated prefixes are billed as full input."
)

@Composable
fun engineConfigStrings(): EngineConfigStrings =
    if (LocalAppStrings.current === EnglishStrings) EnglishEngineConfigStrings else ChineseEngineConfigStrings
