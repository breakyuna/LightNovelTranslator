package com.breakyuna.noveltranslator.core.text

import java.text.BreakIterator
import java.util.Locale

/** 质量检查问题代码。调用方自行决定哪些是阻断项。 */
enum class QualityIssueCode {
    /** 译文残留外文片段。 */
    FOREIGN_RESIDUE,

    /** 译文与源文的结构标点序列不一致。 */
    PUNCTUATION_STRUCTURE,

    /** 译文与源文高度相似，疑似未翻译。 */
    TRANSLATION_SIMILAR,

    /** 译文与源文行数不一致。 */
    LINE_COUNT_MISMATCH,

    /** 译文与源文段数不一致。 */
    PARAGRAPH_COUNT_MISMATCH
}

/** 一条质量检查结果：[code] 是稳定的问题代码，[detail] 供诊断展示。 */
data class QualityIssue(
    val code: QualityIssueCode,
    val detail: String
)

/**
 * 译后质量检查纯函数。
 *
 * 移植自 LinguaGacha `translation-quality-rules.ts`（外文残留、相似度）与
 * `punctuation-structure.ts`（标点结构），并补上行 / 段数不一致检查。
 *
 * 设计约束：每个检查都只回答"是否有问题 + 证据是什么"，不决定是否阻断、
 * 也不触发修复。阻断策略属于引擎职责，否则质检规则会被业务策略污染。
 */
object TranslationQualityRules {

    private const val TRANSLATION_SIMILARITY_THRESHOLD = 0.8

    private val SHORT_ASCII_UPPERCASE_LATIN = Regex("""^[A-Z]{2,4}$""")

    /** 分组只表达结构等价；括号保留开闭方向，引号允许不同语言的排版形式。 */
    private val PUNCTUATION_GROUPS: Map<String, String> = buildMap {
        val groups = mapOf(
            "QUOTE" to "“”„‟「」『』«»\"＂",
            "ROUND_OPEN" to "(（",
            "ROUND_CLOSE" to ")）",
            "SQUARE_OPEN" to "[［【",
            "SQUARE_CLOSE" to "]］】",
            "BRACE_OPEN" to "{｛",
            "BRACE_CLOSE" to "}｝",
            "TITLE_OPEN" to "《",
            "TITLE_CLOSE" to "》"
        )
        for ((group, characters) in groups) {
            for (character in characters) put(character.toString(), group)
        }
    }

    /**
     * 外文残留按连续字素聚合。
     *
     * 孤立 Latin 字素及 2～4 个 ASCII 大写字母组成的片段证据不足
     * （很可能是单位、缩写或按键名），其它片段即使只有一个字素也报告。
     */
    fun collectForeignResidueFragments(text: String, targetLanguage: String): List<String> {
        val fragments = LinkedHashSet<String>()
        var currentFragment = StringBuilder()
        var currentGraphemeCount = 0
        var currentHasOtherResidue = false

        // 片段结束时就地判断证据强度，不让过滤规则泄漏到调用方。
        fun flush() {
            val fragment = currentFragment.toString()
            val isExemptLatinFragment = !currentHasOtherResidue &&
                (currentGraphemeCount == 1 || SHORT_ASCII_UPPERCASE_LATIN.matches(fragment))
            if (fragment.isNotEmpty() && !isExemptLatinFragment) {
                fragments += fragment
            }
            currentFragment = StringBuilder()
            currentGraphemeCount = 0
            currentHasOtherResidue = false
        }

        val breaker = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaker.setText(text)
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            val segment = text.substring(start, end)
            val classification = LanguageCodes.classifyGrapheme(segment, targetLanguage)
            if (classification == LanguageCodes.GraphemeClassification.LATIN_RESIDUE ||
                classification == LanguageCodes.GraphemeClassification.OTHER_RESIDUE
            ) {
                currentFragment.append(segment)
                currentGraphemeCount += 1
                if (classification == LanguageCodes.GraphemeClassification.OTHER_RESIDUE) {
                    currentHasOtherResidue = true
                }
            } else {
                flush()
            }
            start = end
            end = breaker.next()
        }
        flush()

        return fragments.toList()
    }

    /**
     * 文本相似先走包含关系快判，再使用字符集合 Jaccard。
     *
     * 与来源实现一致：Jaccard 基于 UTF-16 字符集合，因此对长度差异不敏感，
     * 只作为"几乎没动"的粗筛。
     */
    fun isTranslationTextSimilar(left: String, right: String): Boolean {
        val leftText = left.trim()
        val rightText = right.trim()
        if (leftText.isEmpty() || rightText.isEmpty()) return false
        return leftText.contains(rightText) ||
            rightText.contains(leftText) ||
            jaccardSimilarity(leftText, rightText) > TRANSLATION_SIMILARITY_THRESHOLD
    }

    /**
     * 相似度 issue 是独立质量裁决。
     *
     * 日 / 韩译中文时沿用既有政策：要求同时出现非中文书写证据，
     * 因为日文汉字与中文汉字大量同形，单纯的"没变"不足以证明漏译。
     */
    fun hasTranslationSimilarityIssue(
        src: String,
        dst: String,
        sourceLanguage: String,
        targetLanguage: String,
        hasForeignResidue: Boolean
    ): Boolean {
        if (!isTranslationTextSimilar(src, dst)) return false
        val target = LanguageCodes.normalizeLanguageCode(targetLanguage)
        if (target != "ZH" && target != "ZH-HANT") return true
        val source = LanguageCodes.normalizeLanguageCode(sourceLanguage)
        if (source != "JA" && source != "KO") return true
        return hasForeignResidue
    }

    /** 按出现顺序提取结构标记，正文和换行不进入序列。 */
    fun collectPunctuationStructure(text: String): List<String> {
        val structure = ArrayList<String>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            PUNCTUATION_GROUPS[character]?.let { structure += it }
            index += Character.charCount(codePoint)
        }
        return structure
    }

    /**
     * 比较已剥离保护段和资源引用的正文。
     *
     * 整对增删及补齐原文半边结构都属于差异；同组错配会通过，
     * 更细的方向检查需要语言语义，不在这里做。
     */
    fun hasPunctuationStructureMismatch(src: String, dst: String): Boolean {
        val source = collectPunctuationStructure(src)
        val translation = collectPunctuationStructure(dst)
        return source.size != translation.size ||
            source.indices.any { source[it] != translation[it] }
    }

    /** 段数按空行分隔的非空行块计数。 */
    fun countParagraphs(text: String): Int {
        var count = 0
        var inParagraph = false
        for (line in splitTextLines(text)) {
            if (line.isBlank()) {
                inParagraph = false
            } else if (!inParagraph) {
                count += 1
                inParagraph = true
            }
        }
        return count
    }

    /**
     * 一次性跑完本模块负责的全部检查，返回问题列表。
     *
     * [preserveRule] 非空时，外文残留 / 相似度 / 标点结构都基于剥离保护段后的
     * 自然文本判断，与 LinguaGacha 校对页的 `natural_src` / `natural_dst` 一致；
     * 行 / 段数检查始终基于原始文本，因为换行是结构证据。
     *
     * 术语检查不在这里：它需要 [CompiledGlossary]，由调用方组合。
     */
    fun checkTranslationQuality(
        src: String,
        dst: String,
        sourceLanguage: String,
        targetLanguage: String,
        preserveRule: TextProtectionRule? = null
    ): List<QualityIssue> {
        val issues = ArrayList<QualityIssue>()
        val naturalSrc = preserveRule?.analyze(src)?.unpreservedText ?: src
        val naturalDst = preserveRule?.analyze(dst)?.unpreservedText ?: dst

        val fragments = collectForeignResidueFragments(naturalDst, targetLanguage)
        if (fragments.isNotEmpty()) {
            issues += QualityIssue(
                QualityIssueCode.FOREIGN_RESIDUE,
                "fragments=${fragments.joinToString("|") { it }}"
            )
        }

        if (hasPunctuationStructureMismatch(naturalSrc, naturalDst)) {
            issues += QualityIssue(
                QualityIssueCode.PUNCTUATION_STRUCTURE,
                "source=${collectPunctuationStructure(naturalSrc).joinToString("")}" +
                    " translation=${collectPunctuationStructure(naturalDst).joinToString("")}"
            )
        }

        if (hasTranslationSimilarityIssue(
                src = naturalSrc,
                dst = naturalDst,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                hasForeignResidue = fragments.isNotEmpty()
            )
        ) {
            issues += QualityIssue(
                QualityIssueCode.TRANSLATION_SIMILAR,
                "jaccard=${"%.3f".format(Locale.ROOT, jaccardSimilarity(naturalSrc, naturalDst))}"
            )
        }

        val sourceLines = splitTextLines(src).size
        val translationLines = splitTextLines(dst).size
        if (sourceLines != translationLines) {
            issues += QualityIssue(
                QualityIssueCode.LINE_COUNT_MISMATCH,
                "source=$sourceLines translation=$translationLines"
            )
        }

        val sourceParagraphs = countParagraphs(src)
        val translationParagraphs = countParagraphs(dst)
        if (sourceParagraphs != translationParagraphs) {
            issues += QualityIssue(
                QualityIssueCode.PARAGRAPH_COUNT_MISMATCH,
                "source=$sourceParagraphs translation=$translationParagraphs"
            )
        }

        return issues
    }

    /** 基于 UTF-16 字符集合的 Jaccard 相似度，与来源实现一致。 */
    fun jaccardSimilarity(left: String, right: String): Double {
        val leftSet = left.toSet()
        val rightSet = right.toSet()
        val union = leftSet union rightSet
        if (union.isEmpty()) return 0.0
        val intersection = leftSet intersect rightSet
        return intersection.size.toDouble() / union.size.toDouble()
    }
}
