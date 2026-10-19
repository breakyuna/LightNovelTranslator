package com.breakyuna.noveltranslator.core.quality

import com.breakyuna.noveltranslator.core.text.TextPreserveEntry
import com.breakyuna.noveltranslator.core.text.TextProtectionPresets
import com.breakyuna.noveltranslator.core.text.TextReplacements
import com.breakyuna.noveltranslator.data.model.QualityRuleSetEntity

/**
 * 质量规则的默认值唯一定义处。
 *
 * 设计约束（来自对标审计 T1 / T2）：
 * 1. **默认规则集必须收编旧硬编码保护体系**。`core/translation/TranslationTextProtection`
 *    原先用一条大 alternation 正则描述 `[IMG:...]` / `{{...}}` / `${...}` / HTML 标签 /
 *    HTML 实体；这里把它的每一个分支原样变成一条 [TextPreserveEntry]，
 *    使新体系成为旧行为的超集而不是替换品。
 * 2. **默认行为必须与旧版逐字节一致**。因此基础规则在 LG base 预设之上只追加
 *    "旧模式本来就命中"的规则：`<br>` 在旧模式里由 HTML 标签分支命中（同值），
 *    资源 token 规则只匹配 `[REF:N]`，而当前引擎不会生成这种 token；
 *    LG base 的 `\s` 空白规则**不参与掩敝**（见 [QualityRuleDefaults.maskBlankSegments]
 *    与 `TranslationTextProtection` 的委托实现），所以旧版不掩蔽的空格在新版依然不掩蔽。
 * 3. **替换槽位默认关闭**。新工程写入 2~3 条轻小说常用规则作为"开箱可用的示例"，
 *    但 `enabled = false`：没有任何项目会因为升级而被悄悄改写正文，
 *    旧工程（无规则行）的回退路径也不含任何替换。
 */
object QualityRuleDefaults {

    /**
     * 文本保护基础规则，所有模式下始终叠加。
     *
     * 顺序即规则定义序：`TextProtectionRule` 在同起点冲突时按定义序裁决。
     * 前 5 条按旧 alternation 的分支顺序排列，保证任何同起点冲突的裁决结果
     * 与旧实现一致；后 3 条是 LG base 预设与资源 token 规则。
     */
    val textPreserveBaseRules: List<TextPreserveEntry> = listOf(
        TextPreserveEntry(pattern = """\[IMG:[^\]\r\n]+\]""", info = "图片标记 Image marker"),
        TextPreserveEntry(pattern = """\{\{[^{}\r\n]+\}\}""", info = "花括号占位 Brace placeholder"),
        TextPreserveEntry(pattern = """\$\{[^{}\r\n]+\}""", info = "模板占位 Template placeholder"),
        TextPreserveEntry(
            pattern = """</?[A-Za-z][^>\r\n]{0,80}>""",
            info = "HTML 标签 HTML tag"
        ),
        TextPreserveEntry(
            pattern = """&(?:[A-Za-z][A-Za-z0-9]+|#\d+|#x[0-9A-Fa-f]+);""",
            info = "HTML 实体 HTML entity"
        ),
        // LG base 预设：与 core/text 的 TextProtectionPresets 同源，避免两处定义漂移。
        TextPreserveEntry(pattern = "<br>", info = "换行符 Line break"),
        TextPreserveEntry(pattern = """\s""", info = "空白符 Whitespace"),
        TextPreserveEntry(
            pattern = TextProtectionPresets.resourceTokenPattern(),
            info = "资源引用 Resource URI"
        )
    )

    /**
     * 空白保护段不生成掩敝标记。
     *
     * 旧体系的 alternation 永远不可能匹配纯空白（每个分支都要求至少一个可见字符），
     * 因此"跳过空白段"是新体系与旧行为逐字节一致的充要条件；同时它也让用户
     * 添加 `\s` 之类的规则时不会把正文里的每个空格都变成占位符。
     */
    const val MASK_BLANK_SEGMENTS: Boolean = false

    /** 译前替换默认条目：只做空白归一，不触碰任何文字内容。 */
    val preReplacementDefaultEntries: List<ReplacementRuleEntry> = listOf(
        ReplacementRuleEntry(
            pattern = "\u3000",
            replacement = " ",
            isRegex = true,
            note = "全角空格归一为半角"
        ),
        ReplacementRuleEntry(
            pattern = "[ \\t]{2,}",
            replacement = " ",
            isRegex = true,
            note = "连续半角空白归一"
        )
    )

    /**
     * 译后替换默认条目：轻小说中文排版常用整理。
     *
     * 参照 LG `builtin/post_translation_replacement/preset/02_首尾半角引号转直角引号.json`
     * 的思路自行编写，未从该仓库拷贝任何未确认内容。`(?m)` 让锚点按行生效，
     * 因为译后替换按段应用，一段可能包含多行。
     */
    val postReplacementDefaultEntries: List<ReplacementRuleEntry> = listOf(
        ReplacementRuleEntry(
            pattern = "(?m)^\"",
            replacement = "\u300C",
            isRegex = true,
            note = "行首半角双引号转直角引号"
        ),
        ReplacementRuleEntry(
            pattern = "(?m)\"$",
            replacement = "\u300D",
            isRegex = true,
            note = "行尾半角双引号转直角引号"
        ),
        ReplacementRuleEntry(
            pattern = """\.\.\.""",
            replacement = "\u2026",
            isRegex = true,
            note = "三个半角点转省略号"
        )
    )

    /** 新建工程时写入的三个槽位默认值（替换槽位默认关闭）。 */
    fun defaultRuleSets(projectId: Long, now: Long = System.currentTimeMillis()): List<QualityRuleSetEntity> =
        listOf(
            QualityRuleSetEntity(
                translationProjectId = projectId,
                type = QualityRuleType.TEXT_PRESERVE.storageKey,
                enabled = true,
                mode = QualityRuleMode.SMART.name,
                entriesJson = QualityRuleJson.encode(QualityRuleType.TEXT_PRESERVE, emptyList()),
                revision = 1,
                updatedAt = now
            ),
            QualityRuleSetEntity(
                translationProjectId = projectId,
                type = QualityRuleType.PRE_REPLACEMENT.storageKey,
                enabled = false,
                mode = QualityRuleMode.CUSTOM.name,
                entriesJson = QualityRuleJson.encode(
                    QualityRuleType.PRE_REPLACEMENT,
                    preReplacementDefaultEntries
                ),
                revision = 1,
                updatedAt = now
            ),
            QualityRuleSetEntity(
                translationProjectId = projectId,
                type = QualityRuleType.POST_REPLACEMENT.storageKey,
                enabled = false,
                mode = QualityRuleMode.CUSTOM.name,
                entriesJson = QualityRuleJson.encode(
                    QualityRuleType.POST_REPLACEMENT,
                    postReplacementDefaultEntries
                ),
                revision = 1,
                updatedAt = now
            )
        )

    /** 单个槽位的默认值，供"恢复默认"使用。 */
    fun defaultRuleSet(
        projectId: Long,
        type: QualityRuleType,
        now: Long = System.currentTimeMillis()
    ): QualityRuleSetEntity = defaultRuleSets(projectId, now).first { it.type == type.storageKey }

    /**
     * 回退规则集：DB 不可用、项目没有任何规则行或解析失败时使用。
     *
     * 它等于旧版行为：保护收编旧硬编码模式，替换槽位为空。
     */
    fun fallbackResolvedRules(): ResolvedQualityRules = ResolvedQualityRules(
        protectionMode = QualityRuleMode.SMART,
        protectionEntries = textPreserveBaseRules,
        preReplacements = null,
        postReplacements = null,
        fromFallback = true
    )

    /** 供日志与设置页展示的条目默认值。 */
    fun defaultEntries(type: QualityRuleType): List<QualityRuleEntry> = when (type) {
        QualityRuleType.TEXT_PRESERVE -> emptyList()
        QualityRuleType.PRE_REPLACEMENT -> preReplacementDefaultEntries
        QualityRuleType.POST_REPLACEMENT -> postReplacementDefaultEntries
    }

    /** 替换槽位默认是否启用：false，保证升级不改变任何既有翻译行为。 */
    fun defaultEnabled(type: QualityRuleType): Boolean = when (type) {
        QualityRuleType.TEXT_PRESERVE -> true
        QualityRuleType.PRE_REPLACEMENT, QualityRuleType.POST_REPLACEMENT -> false
    }

    /**
     * 把条目编译成可执行的替换规则集；单条正则无法编译时跳过该条。
     *
     * 保存前已经过 [QualityRuleValidator] 校验，这里仍然是防御性的：
     * 历史数据或外部写入可能绕过校验层，一条坏规则不应让整次翻译失败。
     */
    fun compileReplacements(entries: List<ReplacementRuleEntry>): TextReplacements? {
        val compiled = entries.mapNotNull { entry -> entry.takeIf { it.isCompilableRegex() } }
        if (compiled.isEmpty()) return null
        return runCatching { TextReplacements(compiled.map { it.toTextReplacementEntry() }) }
            .getOrNull()
    }
}
