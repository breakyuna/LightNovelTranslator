package com.breakyuna.noveltranslator.core.parser

import java.io.BufferedReader
import java.util.Locale

/**
 * 离线确定性小说章节结构检测器
 * 不依赖 AI / LLM，通过多规则候选生成、章节编号解析、聚类序列分析、连续性评分与目录识别，
 * 精确确定 TXT 小说的真实章节结构。
 */
object ChapterStructureDetector {

    private const val MAX_HEADING_LINE_LENGTH = 120
    private const val TOC_MAX_MEDIAN_INTERVAL_CHARS = 200
    private const val TOC_MIN_CONSECUTIVE_ITEMS = 5

    // 正则定义（全部编译为常量提高性能）
    private val REGEX_CHINESE_PATTERN = Regex(
        "^\\s*第\\s*([0-9０-９零一二两三四五六七八九十百千万]+)\\s*[章回節节话卷集幕篇](?:\\s*[:.\\-—]?\\s*(.*))?$"
    )
    private val REGEX_KOREAN_HWA_PATTERN = Regex(
        "^\\s*(\\d+)\\s*화(?:\\s*[:.\\-—]?\\s*(.*))?$"
    )
    private val REGEX_KOREAN_JANG_PATTERN = Regex(
        "^\\s*제\\s*([0-9０-９]+)\\s*장(?:\\s*[:.\\-—]?\\s*(.*))?$"
    )
    private val REGEX_JAPANESE_PATTERN = Regex(
        "^\\s*第\\s*([0-9０-９零一二三四五六七八九十百千万]+|\\d+)\\s*[話回章節編幕](?:\\s*[:.\\-—]?\\s*(.*))?$"
    )
    private val REGEX_ENGLISH_PATTERN = Regex(
        "(?i)^\\s*(chapter|section|book|act)\\s+(\\d+|[IVXLCDM]+)(?:\\s*[:.\\-—]?\\s*(.*))?$"
    )
    private val REGEX_ARABIC_DOT_PATTERN = Regex(
        "^\\s*(\\d{1,5})[\\.\\、\\s]+([^\\s\\d].*)$"
    )
    private val REGEX_ARABIC_STANDALONE_PATTERN = Regex(
        "^\\s*(\\d{1,5})\\s*$"
    )
    private val REGEX_MARKDOWN_PATTERN = Regex(
        "^(#{1,3})\\s+(.*)$"
    )
    private val REGEX_SPECIAL_UNNUMBERED = Regex(
        "(?i)^\\s*(序章|前言|楔子|引言|尾声|后记|番外|间章|幕间|Prologue|Epilogue|Interlude|Afterword|프롤로그|에필로그|후기|외전)(?:\\s*[:.\\-—]?\\s*(.*))?$"
    )

    /**
     * 针对流式 Reader 执行章节结构检测
     */
    fun detect(
        reader: BufferedReader,
        customRegex: String? = null,
        totalEstimatedChars: Long = -1L
    ): ChapterDetectionResult {
        val candidates = mutableListOf<ChapterHeadingCandidate>()
        var lineIndex = 0
        var charOffset = 0L
        var totalChars = 0L

        val customCompiled = customRegex?.trim()?.takeIf(String::isNotBlank)?.let {
            runCatching { Regex(it) }.getOrNull()
        }

        var prevLineBlank = true
        while (true) {
            val line = reader.readLine() ?: break
            val lineLen = line.length.toLong() + 1 // +1 for newline
            val trimmed = line.trim()
            val isBlank = trimmed.isEmpty()

            if (!isBlank && trimmed.length <= MAX_HEADING_LINE_LENGTH) {
                val candidate = matchLine(
                    lineIndex = lineIndex,
                    charOffset = charOffset,
                    rawLine = line,
                    trimmedLine = trimmed,
                    prevLineBlank = prevLineBlank,
                    customRegex = customCompiled
                )
                if (candidate != null) {
                    candidates.add(candidate)
                }
            }

            prevLineBlank = isBlank
            charOffset += lineLen
            lineIndex++
        }
        totalChars = if (totalEstimatedChars > 0) totalEstimatedChars else charOffset

        return analyzeCandidates(candidates, totalChars, customRegex != null)
    }

    /**
     * 针对内存字符串执行章节结构检测
     */
    fun detect(
        fullText: String,
        customRegex: String? = null
    ): ChapterDetectionResult {
        val reader = fullText.reader().buffered()
        return detect(reader, customRegex, fullText.length.toLong())
    }

    /**
     * 匹配单行文本，生成候选标题
     */
    private fun matchLine(
        lineIndex: Int,
        charOffset: Long,
        rawLine: String,
        trimmedLine: String,
        prevLineBlank: Boolean,
        customRegex: Regex?
    ): ChapterHeadingCandidate? {
        // 1. 自定义正则优先（行首匹配语义）
        if (customRegex != null) {
            val match = customRegex.find(trimmedLine)
            if (match != null && match.range.first == 0) {
                val num = ChapterNumberParser.parseNumber(match.value)
                return ChapterHeadingCandidate(
                    lineIndex = lineIndex,
                    charOffset = charOffset,
                    rawText = rawLine,
                    normalizedText = trimmedLine,
                    patternType = ChapterPatternType.CUSTOM_REGEX,
                    chapterNumber = num,
                    titlePart = trimmedLine.substring(match.range.last + 1).trim().takeIf(String::isNotBlank),
                    confidence = if (num != null) 0.9f else 0.75f
                )
            }
        }

        // 2. 中文模式：第 N 章/回/节/话
        REGEX_CHINESE_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val numStr = m.groupValues[1]
            val num = ChapterNumberParser.parseNumber(numStr)
            val titlePart = m.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotBlank)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.CHINESE_NUMBERED,
                chapterNumber = num,
                titlePart = titlePart,
                confidence = if (num != null) 0.95f else 0.8f
            )
        }

        // 3. 韩文模式：N화 / 제N장
        REGEX_KOREAN_HWA_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val numStr = m.groupValues[1]
            val num = ChapterNumberParser.parseNumber(numStr)
            val titlePart = m.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotBlank)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.KOREAN_NUMBERED,
                chapterNumber = num,
                titlePart = titlePart,
                confidence = 0.95f
            )
        }
        REGEX_KOREAN_JANG_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val numStr = m.groupValues[1]
            val num = ChapterNumberParser.parseNumber(numStr)
            val titlePart = m.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotBlank)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.KOREAN_NUMBERED,
                chapterNumber = num,
                titlePart = titlePart,
                confidence = 0.92f
            )
        }

        // 4. 日文模式：第 N 話/回/章/節
        REGEX_JAPANESE_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val numStr = m.groupValues[1]
            val num = ChapterNumberParser.parseNumber(numStr)
            val titlePart = m.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotBlank)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.JAPANESE_NUMBERED,
                chapterNumber = num,
                titlePart = titlePart,
                confidence = 0.93f
            )
        }

        // 5. 英文模式：Chapter 1 / Section II
        REGEX_ENGLISH_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val numStr = m.groupValues[2]
            val num = ChapterNumberParser.parseNumber(numStr)
            val titlePart = m.groupValues.getOrNull(3)?.trim()?.takeIf(String::isNotBlank)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.ENGLISH_NUMBERED,
                chapterNumber = num,
                titlePart = titlePart,
                confidence = 0.9f
            )
        }

        // 6. 特殊不带编号章节：序章 / 前言 / 番外 / 후기 / Prologue
        REGEX_SPECIAL_UNNUMBERED.matchEntire(trimmedLine)?.let { m ->
            val keyword = m.groupValues[1]
            val titlePart = m.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotBlank)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.UNNUMBERED_SPECIAL,
                chapterNumber = null,
                titlePart = titlePart ?: keyword,
                confidence = 0.85f
            )
        }

        // 7. Markdown 标题：# / ## / ###
        REGEX_MARKDOWN_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val textPart = m.groupValues[2].trim()
            val num = ChapterNumberParser.parseNumber(textPart)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.MARKDOWN_HEADING,
                chapterNumber = num,
                titlePart = textPart,
                confidence = 0.7f
            )
        }

        // 8. 纯数字 / 点号序列：1. 标题 或 001 标题
        REGEX_ARABIC_DOT_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val numStr = m.groupValues[1]
            val num = ChapterNumberParser.parseNumber(numStr)
            val titlePart = m.groupValues[2].trim()
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.ARABIC_NUMBERED,
                chapterNumber = num,
                titlePart = titlePart,
                confidence = 0.6f
            )
        }
        REGEX_ARABIC_STANDALONE_PATTERN.matchEntire(trimmedLine)?.let { m ->
            val numStr = m.groupValues[1]
            val num = ChapterNumberParser.parseNumber(numStr)
            return ChapterHeadingCandidate(
                lineIndex = lineIndex,
                charOffset = charOffset,
                rawText = rawLine,
                normalizedText = trimmedLine,
                patternType = ChapterPatternType.ARABIC_NUMBERED,
                chapterNumber = num,
                titlePart = null,
                confidence = 0.5f
            )
        }

        return null
    }

    /**
     * 聚类候选并评分，选出最佳章节序列
     */
    private fun analyzeCandidates(
        allCandidates: List<ChapterHeadingCandidate>,
        totalChars: Long,
        isCustomRegex: Boolean
    ): ChapterDetectionResult {
        if (allCandidates.isEmpty()) {
            return ChapterDetectionResult(
                headings = emptyList(),
                confidence = 0f,
                detectedPattern = null,
                ignoredPrefixRange = null,
                detectedTocRange = null,
                warnings = listOf("未在文本中匹配到任何章节标题候选")
            )
        }

        // 1. 按 PatternType 聚类
        val groups = allCandidates.groupBy { it.patternType }
        val scoredSequences = mutableListOf<Pair<ChapterCandidateSequence, LongRange?>>()
        val specialUnnumbered = groups[ChapterPatternType.UNNUMBERED_SPECIAL].orEmpty()

        for ((type, list) in groups) {
            if (type == ChapterPatternType.UNNUMBERED_SPECIAL) continue

            // 检查并切分可能的目录 (TOC) 与正文 (BODY)
            val (cleanedList, tocRange) = splitTocAndBody(list)
            val fullCandidateList = mergeSpecialsIntoSequence(cleanedList, specialUnnumbered)

            val scored = scoreSequence(
                candidates = fullCandidateList,
                patternType = type,
                totalChars = totalChars,
                hasToc = tocRange != null
            )
            scoredSequences.add(Pair(scored, tocRange))
        }

        // 若没有任何主序列，尝试仅评估特殊章节（如纯短篇或序章）
        if (scoredSequences.isEmpty() && specialUnnumbered.isNotEmpty()) {
            val scored = scoreSequence(
                candidates = specialUnnumbered,
                patternType = ChapterPatternType.UNNUMBERED_SPECIAL,
                totalChars = totalChars,
                hasToc = false
            )
            scoredSequences.add(Pair(scored, null))
        }

        if (scoredSequences.isEmpty()) {
            return ChapterDetectionResult(
                headings = emptyList(),
                confidence = 0f,
                detectedPattern = null,
                ignoredPrefixRange = null,
                detectedTocRange = null,
                warnings = listOf("未能形成有效章节序列")
            )
        }

        // 选出综合得分最高的序列
        val bestPair = scoredSequences.maxByOrNull { it.first.totalScore } ?: scoredSequences.first()
        val bestSequence = bestPair.first
        val detectedTocRange = bestPair.second
        val bestHeadings = bestSequence.candidates.sortedBy { it.charOffset }

        // 计算前置杂项区域与目录区域
        val firstHeadingOffset = bestHeadings.firstOrNull()?.charOffset ?: 0L
        val ignoredPrefixRange = if (firstHeadingOffset > 0) 0L until firstHeadingOffset else null

        val warnings = mutableListOf<String>()
        if (bestSequence.totalScore < 65f) {
            warnings.add("章节置信度较低 (${bestSequence.totalScore.toInt()}%)，建议检查规则或使用自定义分章")
        }
        if (bestSequence.continuityScore < 15f) {
            warnings.add("章节编号连续性较低，可能存在较多缺章或乱序")
        }

        val continuityRate = if (bestHeadings.size > 1) {
            var consecutiveCount = 0
            for (i in 1 until bestHeadings.size) {
                val prevNum = bestHeadings[i - 1].chapterNumber
                val currNum = bestHeadings[i].chapterNumber
                if (prevNum != null && currNum != null && currNum == prevNum + 1) {
                    consecutiveCount++
                }
            }
            consecutiveCount.toFloat() / (bestHeadings.size - 1)
        } else 1.0f

        val intervals = mutableListOf<Int>()
        for (i in 0 until bestHeadings.size - 1) {
            intervals.add((bestHeadings[i + 1].charOffset - bestHeadings[i].charOffset).toInt().coerceAtLeast(0))
        }
        val medianChars = if (intervals.isNotEmpty()) {
            intervals.sorted()[intervals.size / 2]
        } else {
            (totalChars - firstHeadingOffset).toInt().coerceAtLeast(0)
        }

        val patternDescription = when (bestSequence.patternType) {
            ChapterPatternType.CHINESE_NUMBERED -> "中文章节 (第N章/回)"
            ChapterPatternType.KOREAN_NUMBERED -> "韩文章节 (N화 / 제N장)"
            ChapterPatternType.JAPANESE_NUMBERED -> "日文章节 (第N話/章)"
            ChapterPatternType.ENGLISH_NUMBERED -> "英文章节 (Chapter N)"
            ChapterPatternType.ARABIC_NUMBERED -> "数字编号 (N. 标题)"
            ChapterPatternType.MARKDOWN_HEADING -> "Markdown 标题 (# 标题)"
            ChapterPatternType.CUSTOM_REGEX -> "自定义规则"
            ChapterPatternType.UNNUMBERED_SPECIAL -> "特殊序章/番外"
        }

        val stats = ChapterDetectionStats(
            totalEstimatedChapters = bestHeadings.size,
            continuityRate = continuityRate,
            medianChapterChars = medianChars,
            ignoredTocCount = if (detectedTocRange != null) allCandidates.count { it.charOffset in detectedTocRange } else 0,
            frontMatterChars = firstHeadingOffset,
            firstChapterTitle = bestHeadings.firstOrNull()?.normalizedText
        )

        return ChapterDetectionResult(
            headings = bestHeadings,
            confidence = bestSequence.totalScore,
            detectedPattern = patternDescription,
            ignoredPrefixRange = ignoredPrefixRange,
            detectedTocRange = detectedTocRange,
            warnings = warnings,
            stats = stats
        )
    }

    /**
     * 将符合条件的特殊章节（序章、前言、番外等）智能融合到主序列中
     */
    private fun mergeSpecialsIntoSequence(
        mainSequence: List<ChapterHeadingCandidate>,
        specials: List<ChapterHeadingCandidate>
    ): List<ChapterHeadingCandidate> {
        if (specials.isEmpty()) return mainSequence
        if (mainSequence.isEmpty()) return specials

        val result = mutableListOf<ChapterHeadingCandidate>()
        result.addAll(mainSequence)

        val firstMainOffset = mainSequence.first().charOffset
        val lastMainOffset = mainSequence.last().charOffset

        for (special in specials) {
            // 序章/前言：若出现在第一章之前合理范围内（且不是相隔数百兆），保留为首章
            if (special.charOffset < firstMainOffset) {
                val distance = firstMainOffset - special.charOffset
                if (distance < 50_000) {
                    result.add(special)
                }
            } else if (special.charOffset > lastMainOffset) {
                // 后记/尾声/番外：出现在最后一章之后，保留
                result.add(special)
            } else {
                // 夹在中间的间章/番外，若间距合理也融合
                result.add(special)
            }
        }

        return result.sortedBy { it.charOffset }
    }

    /**
     * 智能识别并切分 TOC 目录序列与正文序列
     * 例如文件开头存在密集的 1~100 章目录列表，其后是 1~100 章正文
     */
    private fun splitTocAndBody(
        candidates: List<ChapterHeadingCandidate>
    ): Pair<List<ChapterHeadingCandidate>, LongRange?> {
        if (candidates.size < TOC_MIN_CONSECUTIVE_ITEMS) {
            return Pair(candidates, null)
        }

        // 计算相邻候选之间的字符距离
        val intervals = mutableListOf<Long>()
        for (i in 0 until candidates.size - 1) {
            intervals.add(candidates[i + 1].charOffset - candidates[i].charOffset)
        }

        // 寻找密集的目录段
        var denseRunEndIndex = -1
        var consecutiveDenseCount = 0

        for (i in intervals.indices) {
            if (intervals[i] < TOC_MAX_MEDIAN_INTERVAL_CHARS) {
                consecutiveDenseCount++
            } else {
                if (consecutiveDenseCount >= TOC_MIN_CONSECUTIVE_ITEMS) {
                    denseRunEndIndex = i
                    break
                }
                consecutiveDenseCount = 0
            }
        }
        if (denseRunEndIndex == -1 && consecutiveDenseCount >= TOC_MIN_CONSECUTIVE_ITEMS) {
            denseRunEndIndex = consecutiveDenseCount
        }

        // 如果在文件开头发现了密集的 TOC 序列，且后面还存在正常章节候选
        if (denseRunEndIndex in 1 until candidates.size) {
            val tocCandidates = candidates.subList(0, denseRunEndIndex)
            val bodyCandidates = candidates.subList(denseRunEndIndex, candidates.size)

            val tocStart = tocCandidates.first().charOffset
            val tocEnd = bodyCandidates.first().charOffset - 1
            return Pair(bodyCandidates, tocStart..tocEnd)
        }

        return Pair(candidates, null)
    }

    /**
     * 对候选序列进行综合评分（0..100）
     */
    private fun scoreSequence(
        candidates: List<ChapterHeadingCandidate>,
        patternType: ChapterPatternType,
        totalChars: Long,
        hasToc: Boolean
    ): ChapterCandidateSequence {
        if (candidates.isEmpty()) {
            return ChapterCandidateSequence(emptyList(), patternType, 0f, 0f, 0f, 0f, 0f)
        }

        var continuityScore = 0f
        var layoutScore = 0f
        var lengthScore = 0f
        var tocPenalty = 0f

        val numbered = candidates.filter { it.chapterNumber != null }
        val numberedCount = numbered.size

        // 1. 章节编号连续性评分（满分 40 分）
        if (numberedCount >= 2) {
            var consecutivePlusOneCount = 0
            var monotonicIncreaseCount = 0
            var duplicateNumberCount = 0
            var reverseOrderCount = 0

            for (i in 1 until numbered.size) {
                val prev = numbered[i - 1].chapterNumber!!
                val curr = numbered[i].chapterNumber!!
                when {
                    curr == prev + 1 -> {
                        consecutivePlusOneCount++
                        monotonicIncreaseCount++
                    }
                    curr > prev -> {
                        monotonicIncreaseCount++
                    }
                    curr == prev -> {
                        duplicateNumberCount++
                    }
                    else -> {
                        reverseOrderCount++
                    }
                }
            }

            val totalPairs = (numbered.size - 1).toFloat()
            val consecRatio = consecutivePlusOneCount / totalPairs
            val monoRatio = monotonicIncreaseCount / totalPairs

            continuityScore += 15f * (numberedCount.toFloat() / candidates.size)
            continuityScore += 15f * consecRatio
            continuityScore += 10f * monoRatio

            if (duplicateNumberCount > totalPairs * 0.2f) continuityScore -= 15f
            if (reverseOrderCount > totalPairs * 0.1f) continuityScore -= 20f
        } else if (candidates.size == 1 && patternType == ChapterPatternType.UNNUMBERED_SPECIAL) {
            continuityScore = 25f
        } else {
            continuityScore = 25f
        }

        // 2. 章节长度与间距分布评分（满分 30 分）
        val intervals = mutableListOf<Long>()
        for (i in 0 until candidates.size - 1) {
            intervals.add(candidates[i + 1].charOffset - candidates[i].charOffset)
        }

        if (intervals.isNotEmpty()) {
            val sortedIntervals = intervals.sorted()
            val median = sortedIntervals[sortedIntervals.size / 2]
            val ultraShortCount = intervals.count { it < 10 }
            val ultraShortRatio = ultraShortCount.toFloat() / intervals.size

            when {
                median in 10..100000 -> lengthScore += 20f
                else -> lengthScore += 10f
            }

            if (ultraShortRatio < 0.1f) {
                lengthScore += 10f
            } else if (ultraShortRatio > 0.3f) {
                tocPenalty += 25f
            }
        } else {
            lengthScore = 30f
        }

        // 3. 覆盖范围与模板规范性评分（满分 30 分）
        val firstOffset = candidates.first().charOffset
        val lastOffset = candidates.last().charOffset
        val spanRatio = if (totalChars > 0) ((lastOffset - firstOffset).toFloat() / totalChars).coerceIn(0f, 1f) else 1f

        if (totalChars <= 0 || spanRatio > 0.2f) {
            layoutScore += 15f
        } else {
            layoutScore += 10f
        }

        // 模板基础分
        when (patternType) {
            ChapterPatternType.CHINESE_NUMBERED,
            ChapterPatternType.KOREAN_NUMBERED,
            ChapterPatternType.JAPANESE_NUMBERED -> layoutScore += 15f
            ChapterPatternType.ENGLISH_NUMBERED -> layoutScore += 15f
            ChapterPatternType.CUSTOM_REGEX -> layoutScore += 15f
            ChapterPatternType.MARKDOWN_HEADING -> layoutScore += 15f
            ChapterPatternType.ARABIC_NUMBERED -> layoutScore += 12f
            ChapterPatternType.UNNUMBERED_SPECIAL -> layoutScore += 10f
        }

        // TOC 惩罚（如果成功分离了 TOC，则不额外对正文施加惩罚）
        if (hasToc && candidates.isEmpty()) {
            tocPenalty += 10f
        }

        val rawTotal = (continuityScore + layoutScore + lengthScore - tocPenalty)
        val finalScore = rawTotal.coerceIn(0f, 100f)

        return ChapterCandidateSequence(
            candidates = candidates,
            patternType = patternType,
            continuityScore = continuityScore,
            layoutScore = layoutScore,
            lengthScore = lengthScore,
            tocPenalty = tocPenalty,
            totalScore = finalScore
        )
    }
}
