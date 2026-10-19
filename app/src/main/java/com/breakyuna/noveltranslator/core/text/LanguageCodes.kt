package com.breakyuna.noveltranslator.core.text

/**
 * 书写系统词表与目标语言分类。
 *
 * 移植自 LinguaGacha `domain/language.ts`。只用 JDK 的
 * `Character.UnicodeScript` 与 `Character.getType`，不引入 Android 依赖，
 * 因此可在纯 JVM 单测里跑。
 *
 * 与来源实现的偏差：JS 用 `\p{Script_Extensions=...}`，JDK 只有 `Script`。
 * 差异集中在共享文字（如汉字同时属于 Han/Hiragana/Katakana 的 Extensions），
 * 对"是否属于目标语言"的判断结论一致，因此按 Script 判定并保留来源的
 * 附属字符（auxiliary）白名单来覆盖 `Script=Common` 的假名辅助字符。
 */
object LanguageCodes {

    /** 特殊值：表示"任意原文语言"（关闭语言过滤）。 */
    const val ALL_LANGUAGE_CODE: String = "ALL"

    /** 源语言列表不包含繁中变体，避免预过滤把简繁当成可精确区分的源语言。 */
    val SOURCE_LANGUAGE_CODES: List<String> = listOf(
        "ZH", "EN", "JA", "KO", "RU", "AR", "DE", "FR",
        "PL", "ES", "IT", "PT", "HU", "TR", "TH", "ID", "VI"
    )

    /** 目标语言列表允许繁中作为原生目标，并贴近中文排列。 */
    val TARGET_LANGUAGE_CODES: List<String> = listOf(
        "ZH", "ZH-HANT", "EN", "JA", "KO", "RU", "AR", "DE", "FR",
        "PL", "ES", "IT", "PT", "HU", "TR", "TH", "ID", "VI"
    )

    private val CJK_WRITING_SYSTEMS = setOf(WritingSystem.HAN, WritingSystem.KANA, WritingSystem.HANGUL)

    /** 语言定义只声明允许的书写系统，字符分类统一由书写系统词表负责。 */
    private val WRITING_SYSTEMS_BY_LANGUAGE: Map<String, Set<WritingSystem>> = mapOf(
        ALL_LANGUAGE_CODE to emptySet(),
        "ZH" to setOf(WritingSystem.HAN),
        "ZH-HANT" to setOf(WritingSystem.HAN),
        "EN" to setOf(WritingSystem.LATIN),
        "JA" to setOf(WritingSystem.HAN, WritingSystem.KANA),
        "KO" to setOf(WritingSystem.HAN, WritingSystem.HANGUL),
        "RU" to setOf(WritingSystem.CYRILLIC),
        "AR" to setOf(WritingSystem.ARABIC),
        "DE" to setOf(WritingSystem.LATIN),
        "FR" to setOf(WritingSystem.LATIN),
        "PL" to setOf(WritingSystem.LATIN),
        "ES" to setOf(WritingSystem.LATIN),
        "IT" to setOf(WritingSystem.LATIN),
        "PT" to setOf(WritingSystem.LATIN),
        "HU" to setOf(WritingSystem.LATIN),
        "TR" to setOf(WritingSystem.LATIN),
        "TH" to setOf(WritingSystem.THAI),
        "ID" to setOf(WritingSystem.LATIN),
        "VI" to setOf(WritingSystem.LATIN)
    )

    /**
     * 字素簇按目标语言区分的四态。
     *
     * 优先级：其它残留 > Latin 残留 > 允许文字 > 中性内容。
     */
    enum class GraphemeClassification {
        ALLOWED,
        LATIN_RESIDUE,
        OTHER_RESIDUE,
        NEUTRAL
    }

    /** 语言码入口统一大小写与空白处理，未知值显式返回 null。 */
    fun normalizeLanguageCode(value: String): String? {
        val normalized = value.trim().uppercase()
        return if (normalized in WRITING_SYSTEMS_BY_LANGUAGE) normalized else null
    }

    /** 源语言接受 ALL，但拒绝仅供目标侧使用的繁中变体。 */
    fun normalizeSourceLanguageCode(value: String): String? {
        val languageCode = normalizeLanguageCode(value) ?: return null
        if (languageCode == ALL_LANGUAGE_CODE) return languageCode
        return if (languageCode in SOURCE_LANGUAGE_CODES) languageCode else null
    }

    /** 目标语言必须来自目标词表，因而不接受 ALL。 */
    fun normalizeTargetLanguageCode(value: String): String? {
        val languageCode = normalizeLanguageCode(value) ?: return null
        return if (languageCode in TARGET_LANGUAGE_CODES) languageCode else null
    }

    /** 判断语言族时必须先归一化，避免小写配置让 CJK 分支失效。 */
    fun isCjkLanguageCode(value: String): Boolean {
        val languageCode = normalizeLanguageCode(value) ?: return false
        return WRITING_SYSTEMS_BY_LANGUAGE.getValue(languageCode).any { it in CJK_WRITING_SYSTEMS }
    }

    /** 中日韩正文任意命中入口，用于下游排除含自然语言正文的控制段候选。 */
    fun hasCjkLanguageCharacter(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            if (matchesBodyCharacter(codePoint, character, WritingSystem.HAN, emptySet()) ||
                matchesBodyCharacter(codePoint, character, WritingSystem.KANA, KANA_AUXILIARY_CHARACTERS) ||
                matchesBodyCharacter(codePoint, character, WritingSystem.HANGUL, emptySet())
            ) {
                return true
            }
            index += Character.charCount(codePoint)
        }
        return false
    }

    /**
     * 字素簇按目标语言区分类别。
     *
     * 任一字符命中"其它残留"立即返回；Latin 残留与允许文字按来源实现的
     * 覆盖规则保留（后者不覆盖前者）。
     */
    fun classifyGrapheme(grapheme: String, languageCode: String): GraphemeClassification {
        val language = normalizeLanguageCode(languageCode)
            ?: return GraphemeClassification.NEUTRAL
        // ALL 表示关闭语言过滤，没有可允许的书写系统；显式按中性处理，
        // 避免调用方误把配置值当目标语言时把所有内容判成残留。
        if (language == ALL_LANGUAGE_CODE) return GraphemeClassification.NEUTRAL
        val targetSystems = WRITING_SYSTEMS_BY_LANGUAGE.getValue(language)

        var classification = GraphemeClassification.NEUTRAL
        var index = 0
        while (index < grapheme.length) {
            val codePoint = grapheme.codePointAt(index)
            val character = String(Character.toChars(codePoint))
            val bodySystems = matchingWritingSystems(codePoint, character, body = true)
            val matchedSystems = if (bodySystems.isNotEmpty()) {
                bodySystems
            } else {
                matchingWritingSystems(codePoint, character, body = false)
            }
            if (matchedSystems.isNotEmpty()) {
                val characterClassification = classifyWritingSystems(matchedSystems, targetSystems)
                if (characterClassification == GraphemeClassification.OTHER_RESIDUE) {
                    return GraphemeClassification.OTHER_RESIDUE
                }
                if (characterClassification == GraphemeClassification.LATIN_RESIDUE ||
                    classification == GraphemeClassification.NEUTRAL
                ) {
                    classification = characterClassification
                }
                index += Character.charCount(codePoint)
                continue
            }

            // 未登记但明确属于其它 Script 的字母也属于非目标文字；
            // Common / Inherited 保持中性（附标、数字、标点、空白）。
            if (Character.isLetter(codePoint) && !isCommonOrInherited(codePoint)) {
                return GraphemeClassification.OTHER_RESIDUE
            }
            index += Character.charCount(codePoint)
        }
        return classification
    }

    /** 字符命中的任一书写系统被目标语言接受，即视为允许。 */
    private fun classifyWritingSystems(
        matchedSystems: Set<WritingSystem>,
        targetSystems: Set<WritingSystem>
    ): GraphemeClassification {
        if (matchedSystems.any { it in targetSystems }) return GraphemeClassification.ALLOWED
        return if (matchedSystems.all { it == WritingSystem.LATIN }) {
            GraphemeClassification.LATIN_RESIDUE
        } else {
            GraphemeClassification.OTHER_RESIDUE
        }
    }

    private fun matchingWritingSystems(
        codePoint: Int,
        character: String,
        body: Boolean
    ): Set<WritingSystem> {
        val result = HashSet<WritingSystem>()
        for (system in WritingSystem.entries) {
            val definition = WRITING_SYSTEM_DEFINITIONS.getValue(system)
            val matched = if (body) {
                definition.matchesBody(codePoint, character)
            } else {
                definition.matchesAuxiliary(codePoint, character)
            }
            if (matched) result += system
        }
        return result
    }

    private fun matchesBodyCharacter(
        codePoint: Int,
        character: String,
        system: WritingSystem,
        excluded: Set<String>
    ): Boolean = WRITING_SYSTEM_DEFINITIONS.getValue(system).matchesBody(codePoint, character) &&
        character !in excluded

    private fun isCommonOrInherited(codePoint: Int): Boolean {
        val script = Character.UnicodeScript.of(codePoint)
        return script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED
    }

    private fun scriptWritingSystem(codePoint: Int): WritingSystem? =
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN -> WritingSystem.HAN
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> WritingSystem.KANA
            Character.UnicodeScript.HANGUL -> WritingSystem.HANGUL
            Character.UnicodeScript.LATIN -> WritingSystem.LATIN
            Character.UnicodeScript.CYRILLIC -> WritingSystem.CYRILLIC
            Character.UnicodeScript.ARABIC -> WritingSystem.ARABIC
            Character.UnicodeScript.THAI -> WritingSystem.THAI
            else -> null
        }

    /** 书写系统定义：正文字符与附属字符分开判定。 */
    private enum class WritingSystem {
        HAN, KANA, HANGUL, LATIN, CYRILLIC, ARABIC, THAI
    }

    private class WritingSystemDefinition(
        val matchesBody: (Int, String) -> Boolean,
        val matchesAuxiliary: (Int, String) -> Boolean
    )

    // 这些字符属于假名书写系统，但不能独立证明一段文本含有日文正文；
    // 它们是 Script=Common 的辅助符号，必须靠白名单识别。
    private val KANA_AUXILIARY_CHARACTERS = setOf(
        "\u3099", "\u309A", "\u309B", "\u309C", "\u30FC", "\u3031", "\u3032",
        "\u3033", "\u3034", "\u3035", "\uFF70", "\uFF9E", "\uFF9F"
    )

    // Tatweel 是 Common Script 的阿拉伯书写延长线。
    private val ARABIC_AUXILIARY_CHARACTERS = setOf("\u0640")

    private val WRITING_SYSTEM_DEFINITIONS: Map<WritingSystem, WritingSystemDefinition> = mapOf(
        WritingSystem.HAN to WritingSystemDefinition(
            matchesBody = { codePoint, character ->
                scriptLetter(codePoint, character, WritingSystem.HAN, emptySet())
            },
            matchesAuxiliary = { _, _ -> false }
        ),
        WritingSystem.KANA to WritingSystemDefinition(
            matchesBody = { codePoint, character ->
                scriptLetter(codePoint, character, WritingSystem.KANA, KANA_AUXILIARY_CHARACTERS)
            },
            matchesAuxiliary = { _, character -> character in KANA_AUXILIARY_CHARACTERS }
        ),
        WritingSystem.HANGUL to WritingSystemDefinition(
            matchesBody = { codePoint, character ->
                scriptLetter(codePoint, character, WritingSystem.HANGUL, emptySet())
            },
            matchesAuxiliary = { codePoint, _ ->
                isMark(codePoint) && scriptWritingSystem(codePoint) == WritingSystem.HANGUL
            }
        ),
        WritingSystem.LATIN to WritingSystemDefinition(
            matchesBody = { codePoint, character ->
                scriptLetter(codePoint, character, WritingSystem.LATIN, emptySet())
            },
            matchesAuxiliary = { _, _ -> false }
        ),
        WritingSystem.CYRILLIC to WritingSystemDefinition(
            matchesBody = { codePoint, character ->
                scriptLetter(codePoint, character, WritingSystem.CYRILLIC, emptySet())
            },
            matchesAuxiliary = { _, _ -> false }
        ),
        WritingSystem.ARABIC to WritingSystemDefinition(
            matchesBody = { codePoint, character ->
                scriptLetter(codePoint, character, WritingSystem.ARABIC, ARABIC_AUXILIARY_CHARACTERS)
            },
            matchesAuxiliary = { codePoint, character ->
                character in ARABIC_AUXILIARY_CHARACTERS ||
                    (isMark(codePoint) && scriptWritingSystem(codePoint) == WritingSystem.ARABIC)
            }
        ),
        WritingSystem.THAI to WritingSystemDefinition(
            matchesBody = { codePoint, character ->
                scriptLetter(codePoint, character, WritingSystem.THAI, emptySet())
            },
            matchesAuxiliary = { codePoint, _ ->
                isMark(codePoint) && scriptWritingSystem(codePoint) == WritingSystem.THAI
            }
        )
    )

    /** Script 只在字符本身是正文 Letter 时成立，附属字符由独立 matcher 处理。 */
    private fun scriptLetter(
        codePoint: Int,
        character: String,
        system: WritingSystem,
        excludedCharacters: Set<String>
    ): Boolean = Character.isLetter(codePoint) &&
        scriptWritingSystem(codePoint) == system &&
        character !in excludedCharacters

    /** `\p{M}`：组合用附加符号，不能独立证明语言正文。 */
    private fun isMark(codePoint: Int): Boolean {
        // Character.getType 返回 Int，而 JDK 的类型常量是 Byte，必须转换后比较。
        val type = Character.getType(codePoint).toByte()
        return type == Character.NON_SPACING_MARK ||
            type == Character.COMBINING_SPACING_MARK ||
            type == Character.ENCLOSING_MARK
    }
}
