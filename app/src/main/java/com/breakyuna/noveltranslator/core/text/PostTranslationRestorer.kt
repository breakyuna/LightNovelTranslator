package com.breakyuna.noveltranslator.core.text

private val ESCAPE_RUN_PATTERN = Regex("""\\+""")

// Unicode 将 1～50 的圆圈数字分成三个区段，下标保持实际数值。
private val CIRCLED_NUMBER_BY_VALUE: List<String> = buildList {
    add("")
    for (index in 0 until 20) add(String(Character.toChars(0x2460 + index)))
    for (index in 0 until 15) add(String(Character.toChars(0x3251 + index)))
    for (index in 0 until 15) add(String(Character.toChars(0x32B1 + index)))
}

private val CIRCLED_NUMBER_VALUE_BY_TOKEN: Map<String, String> =
    CIRCLED_NUMBER_BY_VALUE.drop(1)
        .mapIndexed { index, token -> token to (index + 1).toString() }
        .toMap()

private val NUMBER_TOKEN_PATTERN: Regex =
    Regex("""\d+|[${CIRCLED_NUMBER_VALUE_BY_TOKEN.keys.joinToString("")}]""")

// 引号正则的空白用显式 Unicode 集合，与 JS 的 `\s` 对齐；
// 不用 `(?U)\s` 是为了让这两个模式的行为不依赖全局 flag。
private const val WHITESPACE_CLASS =
    "\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"

private val OPENING_QUOTE_PATTERN = Regex("""^([$WHITESPACE_CLASS]*)['"‘“「『]""")
private val CLOSING_QUOTE_PATTERN = Regex("""['"’”」』]([$WHITESPACE_CLASS]*)$""")

/**
 * 译后恢复所需的译前产物。
 *
 * 对应 LinguaGacha `TranslationPrePipelineContext` 中与恢复相关的字段：
 * 行数对应时才有逐行恢复依据，因此 [preparedLines] 必须与请求时的顺序一致。
 */
data class TranslationRestorationContext(
    /** 全部正文保留时恢复原始文本，包括换行形式。 */
    val sourceText: String,
    val preparedLines: List<PreparedSourceLine>,
    val preserveRule: TextProtectionRule?,
    val referenceMappings: List<ResourceReferenceMapping>
)

/**
 * 译后逐行恢复。
 *
 * 移植自 LinguaGacha `translation-post-pipeline.ts` 的 restore_body 与
 * `translation-output-restoration.ts` 的 restore_translation_line：
 * 1. 行数一致才逐行恢复；不一致时整体替换并只还原资源 token；
 * 2. 反斜杠段长度、圆圈数字、标点形态都只在"证据能完整对应"时改写；
 * 3. 每一步都只处理保护段之外的正文，保护段按原样写回。
 */
class PostTranslationRestorer(
    private val sourceLanguage: String = DEFAULT_SOURCE_LANGUAGE,
    private val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
    private val postReplacements: TextReplacements? = null
) {

    /** 模型响应按行对应恢复依据；全部保留时直接返回原文。 */
    fun restore(context: TranslationRestorationContext, translation: String): String {
        if (context.preparedLines.all { it is PreservedSourceLine }) {
            return context.sourceText
        }
        val outputLines = splitTextLines(translation)
        val corresponding = outputLines.size == context.preparedLines.size
        val projected = if (corresponding) {
            context.preparedLines.mapIndexed { index, line ->
                when (line) {
                    is PreservedSourceLine -> line.preparedText
                    is TranslatableSourceLine -> {
                        var restored = restoreTranslationLine(
                            restorationText = line.restorationText,
                            preparedText = line.preparedText,
                            translation = outputLines[index].trim(),
                            preserveRule = context.preserveRule,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage
                        )
                        restored = applyPostReplacements(restored, context.referenceMappings)
                        "${line.leadingWhitespace}$restored${line.trailingWhitespace}"
                    }
                }
            }.joinToString("\n")
        } else {
            outputLines.joinToString("\n") { applyPostReplacements(it, context.referenceMappings) }
        }
        return ResourceReferenceSlots.restore(projected, context.referenceMappings)
    }

    /** 替换只作用于普通文本，临时资源引用由映射单独恢复。 */
    private fun applyPostReplacements(text: String, mappings: List<ResourceReferenceMapping>): String {
        val postReplacements = this.postReplacements ?: return text
        return ResourceReferenceSlots.transformProjected(text, mappings, postReplacements::apply)
    }
}

/** 默认按日译中配置，与来源实现的测试默认值一致。 */
const val DEFAULT_SOURCE_LANGUAGE: String = "JA"
const val DEFAULT_TARGET_LANGUAGE: String = "ZH"

/**
 * 按同一行的显式源文投影恢复模型输出。
 *
 * 每一步只在证据能够完整对应时改写，任何一步证据不足都原样返回上一步结果，
 * 因此整体是单调收敛的：重复调用同一输出不会继续改动（幂等）。
 */
fun restoreTranslationLine(
    restorationText: String,
    preparedText: String,
    translation: String,
    preserveRule: TextProtectionRule? = null,
    sourceLanguage: String = DEFAULT_SOURCE_LANGUAGE,
    targetLanguage: String = DEFAULT_TARGET_LANGUAGE
): String {
    var result = removeExtraPreservedSegments(preparedText, translation, preserveRule)
    result = restoreEscapeRunLengths(restorationText, result)
    result = restoreCircledNumberForms(restorationText, result)
    return stabilizePunctuationForms(
        source = restorationText,
        translation = result,
        preserveRule = preserveRule,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage
    )
}

/** 只有期望保护段按原顺序完整存在时，才删除模型额外生成的保护段。 */
private fun removeExtraPreservedSegments(
    expectedText: String,
    translation: String,
    rule: TextProtectionRule?
): String {
    if (rule == null) return translation
    val expected = collectNonBlankTextPreserveSegments(expectedText, rule)
    val actual = collectNonBlankTextPreserveSegments(translation, rule)
    if (expected.size >= actual.size) return translation

    val extraIndexes = findExtraSegmentIndexes(expected, actual) ?: return translation
    var segmentIndex = 0
    return rule.replace(translation) { match, _ ->
        if (match.isBlank()) {
            match
        } else if (extraIndexes.contains(segmentIndex++)) {
            ""
        } else {
            match
        }
    }
}

/** 仅当期望序列完整构成实际序列的子序列时，返回可安全删除的位置。 */
private fun findExtraSegmentIndexes(expected: List<String>, actual: List<String>): Set<Int>? {
    val extraIndexes = HashSet<Int>()
    var expectedIndex = 0
    actual.forEachIndexed { index, segment ->
        if (expectedIndex < expected.size && segment == expected[expectedIndex]) {
            expectedIndex += 1
        } else {
            extraIndexes += index
        }
    }
    return if (expectedIndex == expected.size) extraIndexes else null
}

private data class EscapeRun(val value: String, val followingCharacter: String)

/** 反斜杠段数量和后继字符全部对应时，按出现顺序恢复每段长度。 */
private fun restoreEscapeRunLengths(source: String, translation: String): String {
    val sourceRuns = collectEscapeRuns(source)
    val translationRuns = collectEscapeRuns(translation)
    if (sourceRuns.isEmpty() || sourceRuns.size != translationRuns.size) return translation
    if (sourceRuns.indices.any { sourceRuns[it].followingCharacter != translationRuns[it].followingCharacter }) {
        return translation
    }
    if (sourceRuns.indices.all { sourceRuns[it].value == translationRuns[it].value }) return translation
    var index = 0
    return ESCAPE_RUN_PATTERN.replace(translation) { sourceRuns.getOrNull(index++)?.value ?: "" }
}

/** 后继字符用于确认反斜杠段仍对应同一个控制结构，而非仅仅数量相同。 */
private fun collectEscapeRuns(text: String): List<EscapeRun> =
    ESCAPE_RUN_PATTERN.findAll(text).map { match ->
        val value = match.value
        val followingText = text.substring(match.range.last + 1)
        val followingCharacter = if (followingText.isEmpty()) {
            ""
        } else {
            String(Character.toChars(followingText.codePointAt(0)))
        }
        EscapeRun(value, followingCharacter)
    }.toList()

/** 全部数字值和形式边界都可解释时，一次性恢复源文圆圈数字。 */
private fun restoreCircledNumberForms(source: String, translation: String): String {
    val sourceTokens = NUMBER_TOKEN_PATTERN.findAll(source).map { it.value }.toList()
    val translationTokens = NUMBER_TOKEN_PATTERN.findAll(translation).map { it.value }.toList()
    if (sourceTokens.none { it in CIRCLED_NUMBER_VALUE_BY_TOKEN }) return translation
    if (sourceTokens.size != translationTokens.size) return translation
    if (sourceTokens.indices.any { numberTokenValue(sourceTokens[it]) != numberTokenValue(translationTokens[it]) }) {
        return translation
    }
    // 源文是普通数字而译文擅自改成圆圈数字，同样属于形式漂移。
    if (sourceTokens.indices.any {
            sourceTokens[it] !in CIRCLED_NUMBER_VALUE_BY_TOKEN &&
                translationTokens[it] in CIRCLED_NUMBER_VALUE_BY_TOKEN
        }
    ) {
        return translation
    }

    var index = 0
    return NUMBER_TOKEN_PATTERN.replace(translation) { match ->
        val sourceToken = sourceTokens.getOrNull(index++) ?: ""
        if (sourceToken in CIRCLED_NUMBER_VALUE_BY_TOKEN) sourceToken else match.value
    }
}

/** 统一普通数字与圆圈数字的数值，供逐项对应检查。 */
private fun numberTokenValue(token: String): String? {
    CIRCLED_NUMBER_VALUE_BY_TOKEN[token]?.let { return it }
    if (token.isEmpty() || token.any { it !in '0'..'9' }) return null
    // 去掉前导零但至少保留一位，与 JS `replace(/^0+(?=\d)/, "")` 等价。
    val trimmed = token.trimStart('0')
    return if (trimmed.isEmpty()) "0" else trimmed
}

private data class PunctuationFormRule(val source: String, val alternatives: List<String>)

// 结构符号和源文 CJK 形式在所有翻译方向都保持，避免模型擅自改写工程文本形态。
private val STABLE_SOURCE_FORM_RULES: List<PunctuationFormRule> = listOf(
    PunctuationFormRule("\u3000", listOf(" ")),
    PunctuationFormRule("\uFF1A", listOf(":")),
    PunctuationFormRule("\u30FB", listOf("\u00B7")),
    PunctuationFormRule("\uFF1F", listOf("?")),
    PunctuationFormRule("\uFF01", listOf("!")),
    PunctuationFormRule("\u2014", listOf("\u002D", "\u2015")),
    PunctuationFormRule("\u2015", listOf("\u002D", "\u2014")),
    PunctuationFormRule("<", listOf("\uFF1C", "\u300A")),
    PunctuationFormRule(">", listOf("\uFF1E", "\u300B")),
    PunctuationFormRule("\uFF1C", listOf("<", "\u300A")),
    PunctuationFormRule("\uFF1E", listOf(">", "\u300B")),
    PunctuationFormRule("[", listOf("\u3010")),
    PunctuationFormRule("]", listOf("\u3011")),
    PunctuationFormRule("\u3010", listOf("[")),
    PunctuationFormRule("\u3011", listOf("]")),
    PunctuationFormRule("(", listOf("\uFF08")),
    PunctuationFormRule(")", listOf("\uFF09")),
    PunctuationFormRule("\uFF08", listOf("(")),
    PunctuationFormRule("\uFF09", listOf(")")),
    PunctuationFormRule("\u300C", listOf("\u2018", "\u201C", "\u300E")),
    PunctuationFormRule("\u300D", listOf("\u2019", "\u201D", "\u300F")),
    PunctuationFormRule("\u300E", listOf("\u2018", "\u201C", "\u300C")),
    PunctuationFormRule("\u300F", listOf("\u2019", "\u201D", "\u300D")),
    PunctuationFormRule("\u2018", listOf("\u201C", "\u300C", "\u300E")),
    PunctuationFormRule("\u2019", listOf("\u201D", "\u300D", "\u300F")),
    PunctuationFormRule("\u201C", listOf("\u2018", "\u300C", "\u300E")),
    PunctuationFormRule("\u201D", listOf("\u2019", "\u300D", "\u300F"))
)

// 非 CJK 原文翻译到 CJK 时允许模型采用目标排版，其余方向恢复这些 ASCII 形式。
private val ADAPTABLE_ASCII_FORM_RULES: List<PunctuationFormRule> = listOf(
    PunctuationFormRule(" ", listOf("\u3000")),
    PunctuationFormRule(":", listOf("\uFF1A")),
    PunctuationFormRule("\u00B7", listOf("\u30FB")),
    PunctuationFormRule("?", listOf("\uFF1F")),
    PunctuationFormRule("!", listOf("\uFF01")),
    PunctuationFormRule("\u002D", listOf("\u2014", "\u2015"))
)

/** 标点先恢复源文稳定形态，再按语言方向处理可适配形态与 CJK 引号。 */
private fun stabilizePunctuationForms(
    source: String,
    translation: String,
    preserveRule: TextProtectionRule?,
    sourceLanguage: String,
    targetLanguage: String
): String {
    var result = restoreBoundaryQuotes(source, translation, targetLanguage, preserveRule)
    result = restoreSourcePunctuationForms(source, result, STABLE_SOURCE_FORM_RULES, preserveRule)
    val targetIsCjk = LanguageCodes.isCjkLanguageCode(targetLanguage)
    val sourceIsCjk = LanguageCodes.isCjkLanguageCode(sourceLanguage)
    // 非 CJK 译 CJK 时允许模型采用目标排版，其余方向都恢复源文 ASCII 形式。
    if (!(targetIsCjk && !sourceIsCjk)) {
        result = restoreSourcePunctuationForms(source, result, ADAPTABLE_ASCII_FORM_RULES, preserveRule)
    }
    return if (targetIsCjk) {
        transformUnpreserved(result, preserveRule) { text ->
            text.replace("\u201C", "\u300C").replace("\u201D", "\u300D")
        }
    } else {
        result
    }
}

/** 每条规则都基于去除保护段后的总量证据执行，保护段本身保持原样。 */
private fun restoreSourcePunctuationForms(
    source: String,
    translation: String,
    rules: List<PunctuationFormRule>,
    preserveRule: TextProtectionRule?
): String {
    val visibleSource = removePreservedSegments(source, preserveRule)
    var result = translation
    for (rule in rules) {
        val visibleTranslation = removePreservedSegments(result, preserveRule)
        if (!canRestoreByTotalCount(visibleSource, visibleTranslation, rule.source, rule.alternatives)) {
            continue
        }
        result = transformUnpreserved(result, preserveRule) { text ->
            rule.alternatives.fold(text) { current, alternative ->
                current.replace(alternative, rule.source)
            }
        }
    }
    return result
}

/** 总量能够唯一解释模型使用的等价形式时，才授权全局恢复该符号。 */
private fun canRestoreByTotalCount(
    source: String,
    translation: String,
    sourceForm: String,
    alternatives: List<String>
): Boolean {
    val sourceCount = countToken(source, sourceForm)
    val sourceAlternativeCount = alternatives.sumOf { countToken(source, it) }
    val translationCount = countToken(translation, sourceForm)
    val translationAlternativeCount = alternatives.sumOf { countToken(translation, it) }
    return sourceCount > 0 &&
        sourceCount != sourceAlternativeCount &&
        sourceCount > translationCount &&
        sourceCount == translationCount + translationAlternativeCount
}

/** 以去除保护段后的源文首尾确定引号恢复目标。 */
private fun restoreBoundaryQuotes(
    source: String,
    translation: String,
    targetLanguage: String,
    preserveRule: TextProtectionRule?
): String {
    val visibleSource = removePreservedSegments(source, preserveRule).trim()
    val opening = resolveOpeningQuote(visibleSource, targetLanguage)
    val closing = resolveClosingQuote(visibleSource, targetLanguage)
    if (opening == null && closing == null) return translation
    return transformUnpreservedBoundaries(translation, preserveRule, opening, closing)
}

/** 保留日式开引号，中文、日文、韩文目标另接受对应弯引号。 */
private fun resolveOpeningQuote(source: String, targetLanguage: String): String? {
    val quote = firstCodePoint(source)
    if (quote == "\u300C" || quote == "\u300E") return quote
    if (!LanguageCodes.isCjkLanguageCode(targetLanguage)) return null
    if (quote == "\u201C") return "\u300C"
    return if (quote == "\u2018") quote else null
}

/** 闭引号按与开引号相同的语言规则选择恢复形式。 */
private fun resolveClosingQuote(source: String, targetLanguage: String): String? {
    val quote = lastCodePoint(source)
    if (quote == "\u300D" || quote == "\u300F") return quote
    if (!LanguageCodes.isCjkLanguageCode(targetLanguage)) return null
    if (quote == "\u201D") return "\u300D"
    return if (quote == "\u2019") quote else null
}

/** 取字符串第一个码位对应的字符；空串返回空串。 */
private fun firstCodePoint(text: String): String =
    if (text.isEmpty()) "" else String(Character.toChars(text.codePointAt(0)))

/** 取字符串最后一个码位对应的字符；空串返回空串。 */
private fun lastCodePoint(text: String): String {
    if (text.isEmpty()) return ""
    val index = if (Character.isLowSurrogate(text[text.length - 1])) text.length - 2 else text.length - 1
    return String(Character.toChars(text.codePointAt(index)))
}

/** 只修改首尾非空正文片段，跳过保护段和空白片段。 */
private fun transformUnpreservedBoundaries(
    text: String,
    rule: TextProtectionRule?,
    opening: String?,
    closing: String?
): String {
    if (rule == null) return replaceBoundaryQuotes(text, opening, closing)
    val chunks = ArrayList<String>()
    rule.transformUnpreserved(text) { chunk ->
        chunks += chunk
        chunk
    }
    val firstIndex = chunks.indexOfFirst { it.isNotBlank() }
    val lastIndex = chunks.indexOfLast { it.isNotBlank() }
    var index = 0
    return rule.transformUnpreserved(text) { chunk ->
        val currentIndex = index++
        replaceBoundaryQuotes(
            text = chunk,
            opening = if (currentIndex == firstIndex) opening else null,
            closing = if (currentIndex == lastIndex) closing else null
        )
    }
}

/** 仅替换已有的边界引号，并保留紧邻空白。 */
private fun replaceBoundaryQuotes(text: String, opening: String?, closing: String?): String {
    var result = text
    if (opening != null) {
        result = OPENING_QUOTE_PATTERN.replace(result) { match ->
            match.groupValues[1] + opening
        }
    }
    if (closing != null) {
        result = CLOSING_QUOTE_PATTERN.replace(result) { match ->
            closing + match.groupValues[1]
        }
    }
    return result
}

/** 存在保护规则时，仅转换规则之外的正文。 */
private fun transformUnpreserved(
    text: String,
    rule: TextProtectionRule?,
    transform: (String) -> String
): String = if (rule == null) transform(text) else rule.transformUnpreserved(text, transform)

/** 为标点计数生成排除保护段的文本。 */
private fun removePreservedSegments(text: String, rule: TextProtectionRule?): String =
    if (rule == null) text else rule.replace(text, "")

/** 按完整字面量计数，支持多字符标点。 */
private fun countToken(text: String, token: String): Int {
    if (token.isEmpty()) return 0
    var count = 0
    var index = text.indexOf(token)
    while (index >= 0) {
        count += 1
        index = text.indexOf(token, index + token.length)
    }
    return count
}
