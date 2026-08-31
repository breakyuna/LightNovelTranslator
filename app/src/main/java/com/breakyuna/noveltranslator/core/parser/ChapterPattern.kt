package com.breakyuna.noveltranslator.core.parser

/**
 * 章节模式分类
 */
enum class ChapterPatternType {
    CHINESE_NUMBERED,
    JAPANESE_NUMBERED,
    KOREAN_NUMBERED,
    ENGLISH_NUMBERED,
    ARABIC_NUMBERED,
    MARKDOWN_HEADING,
    CUSTOM_REGEX,
    UNNUMBERED_SPECIAL
}

/**
 * 章节标题候选数据结构
 */
data class ChapterHeadingCandidate(
    val lineIndex: Int,
    val charOffset: Long,
    val rawText: String,
    val normalizedText: String,
    val patternType: ChapterPatternType,
    val chapterNumber: Int?,
    val titlePart: String?,
    val confidence: Float
)

/**
 * 章节聚类序列数据结构
 */
data class ChapterCandidateSequence(
    val candidates: List<ChapterHeadingCandidate>,
    val patternType: ChapterPatternType,
    val continuityScore: Float,
    val layoutScore: Float,
    val lengthScore: Float,
    val tocPenalty: Float,
    val totalScore: Float
)

/**
 * 章节结构检测最终结果
 */
data class ChapterDetectionResult(
    val headings: List<ChapterHeadingCandidate>,
    val confidence: Float, // 0..100
    val detectedPattern: String?,
    val ignoredPrefixRange: LongRange?,
    val detectedTocRange: LongRange?,
    val warnings: List<String>,
    val stats: ChapterDetectionStats? = null
)

/**
 * 章节检测统计详情（供 UI 和日志展示）
 */
data class ChapterDetectionStats(
    val totalEstimatedChapters: Int,
    val continuityRate: Float, // 0..1
    val medianChapterChars: Int,
    val ignoredTocCount: Int,
    val frontMatterChars: Long,
    val firstChapterTitle: String?
)
