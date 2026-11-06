package com.breakyuna.noveltranslator.core.text

/**
 * 内置默认保护规则集。
 *
 * 移植自 LinguaGacha `builtin/text_preserve/preset/base.json`：基础层在所有
 * 模式下始终启用，与调用方追加的自定义规则叠加。
 *
 * 未移植的内容：KAG / RENPY / RPGMAKER / WOLF 四个引擎预设。它们是游戏脚本
 * 专用的控制符规则，LNT 面向小说正文；下一阶段如支持对应格式再按需引入。
 */
object TextProtectionPresets {

    /** 基础层规则：HTML 标签、空白控制、资源 token。 */
    fun base(tokenPrefix: String = ResourceReferenceSlots.TOKEN_PREFIX): List<TextPreserveEntry> = listOf(
        TextPreserveEntry(pattern = "<br>", info = "换行符 Line break"),
        // JS 的 `\s` 覆盖 Unicode 空白；TextProtectionRule 统一加 `(?U)`，
        // 因此这里直接写 `\s` 即可获得全角空格等成员。
        TextPreserveEntry(pattern = """\s""", info = "空白符 Whitespace"),
        // 资源 token 规则与投影用的 token 格式必须同源，否则模型回显的
        // 占位符不会被当成保护段，译后恢复就失去了保护证据。
        TextPreserveEntry(
            pattern = resourceTokenPattern(tokenPrefix),
            info = "资源引用 Resource URI"
        )
    )

    /** 直接用默认规则构造一个 [TextProtectionRule]。 */
    fun baseRule(
        tokenPrefix: String = ResourceReferenceSlots.TOKEN_PREFIX,
        extraEntries: List<TextPreserveEntry> = emptyList()
    ): TextProtectionRule = TextProtectionRule(base(tokenPrefix) + extraEntries)

    /**
     * 生成与 [ResourceReferenceSlots] token 格式匹配的保护规则。
     *
     * 只匹配 ASCII 数字编号：序号由投影端生成，不会是全角数字。
     */
    fun resourceTokenPattern(tokenPrefix: String = ResourceReferenceSlots.TOKEN_PREFIX): String {
        val escapedPrefix = StringBuilder(tokenPrefix.length * 2)
        for (ch in tokenPrefix) {
            if (ch in ".*+?^\${}()|[]\\") escapedPrefix.append('\\')
            escapedPrefix.append(ch)
        }
        return "$escapedPrefix[0-9]+\\]"
    }
}
