package com.breakyuna.noveltranslator.core.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * EPUB 正文文档中提取出的一个内容块。
 *
 * [headingLevel] 非空表示该块是标题（章节切分锚点）；为空表示正文或图片标记块。
 * [headingId] 记录标题元素的 `id`，供 TOC 锚点（`chapter.xhtml#sec1`）精确匹配。
 */
data class EpubHtmlBlock(
    val headingLevel: Int?,
    val headingId: String?,
    val text: String
)

/** 单个 XHTML / HTML 文档的块级提取结果。 */
data class EpubHtmlDocument(
    val blocks: List<EpubHtmlBlock>,
    val hasTocNavigation: Boolean
)

/**
 * 基于 jsoup 的 EPUB 正文提取器：HTML → 块级文本。
 *
 * 对标 LinguaGacha `epub-ast.ts` 的块语义，但不复刻其三种 AST 定位单元
 * （每行槽位 / 块文本 / 文本游程）——那些定位信息服务于"译文写回原始 DOM"，
 * 而 LNT 的 Edition 模型在导出时重新生成文本，导入侧只需要
 * "尽量保住结构与标记，让翻译层有机会保护它们"。
 *
 * 块边界规则：
 * 1. h1-h6 是章节标题候选（同时是章内切分锚点）；p / div / li / tr / td /
 *    blockquote / figure / figcaption / dl / dt / dd / section / article 等
 *    非行内元素在前后刷新块，嵌套结构按此线性化（table → 逐单元格文本）。
 * 2. 行内元素（b / i / em / strong / ruby / span / a / code 等）只贡献文本，
 *    不产生块边界——它们对译文没有结构意义，正文由翻译层的
 *    `core/text` 保护体系统一处理。
 * 3. `<br>` 保留为块内软换行（连续两个以上折叠为一个空行）；`<hr>` 只作边界。
 * 4. `<img>` / `<image>` 转为 `[IMG:文件名]` 标记块：导入契约不变，
 *    翻译层 `TranslationTextProtection` 已把该标记纳入保护候选，
 *    QA 也会校验图片标记不丢失。data URI 内联图不产出标记（避免把 base64
 *    塞进正文）；外链图片按既有策略产出 basename 标记。
 *
 * Ruby 处理见 [RUBY_KEEP_READINGS]。
 *
 * 容错：jsoup HTML 解析器自动修复未闭合标签、大小写混乱、裸 `&` 等；
 * CDATA 包裹的正文先剥离 CDATA 记号，避免整段被当作注释丢弃。
 *
 * 内存策略：每次调用只解析一个文档，返回后 DOM 即可被 GC 回收，
 * 不会把整本书的解析树驻留内存；单文档大小由调用方的条目预算约束。
 */
object EpubHtmlExtractor {

    /**
     * Ruby 处理策略（可配置常量）。
     *
     * `false`（默认）：`<ruby>base<rt>reading</rt></ruby>` 丢弃 `<rt>` 读音，
     * 只保留 base 文本。中文读者不需要振假名；语义与 `core/text` 的
     * `RubyCleaner.clean`（cleanRuby）对齐——那边同样只留正文、剥离读音标记，
     * 因此导入侧先剥离不会让译文层失去处理机会，也不会双重剥离。
     *
     * `true`：读音以全角括号保留在正文中（`base（reading）`），此时读音会
     * 随正文一起翻译；仅用于需要保留注音的罕见场景。
     */
    const val RUBY_KEEP_READINGS: Boolean = false

    private val HEADING_LEVELS = mapOf(
        "h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6
    )

    /** 整棵子树丢弃：脚本、样式、文档头、无障碍标题与 ruby 括号/注解容器。 */
    private val SKIP_SUBTREE_TAGS = setOf(
        "head", "script", "style", "noscript", "template", "title",
        "rp", "rtc"
    )

    /** 行内标签：只贡献文本，不产生块边界。 */
    private val INLINE_TAGS = setOf(
        "a", "abbr", "acronym", "b", "bdi", "bdo", "big", "cite", "code", "data",
        "dfn", "del", "em", "font", "i", "ins", "kbd", "label", "mark", "nobr",
        "output", "q", "rb", "ruby", "s", "samp", "small", "span", "strike",
        "strong", "sub", "sup", "tt", "time", "u", "var"
    )

    private val IMAGE_TAGS = setOf("img", "image")
    private val IMAGE_SRC_ATTRIBUTES = listOf("src", "xlink:href", "href")
    private val CDATA_PATTERN = Regex("<!\\s*\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 提取单个正文文档。
     *
     * @param html 已按正确字符集解码的 HTML / XHTML 源码
     * @param htmlDir 该文档在 zip 内的目录（含尾部 `/`），用于解析相对图片路径
     * @param imageNamesByPath manifest 图片路径 → 导出文件名（[EpubParser] 预生成）
     * @param rubyKeepReadings 是否保留 ruby 读音，见 [RUBY_KEEP_READINGS]
     */
    fun extract(
        html: String,
        htmlDir: String = "",
        imageNamesByPath: Map<String, String> = emptyMap(),
        rubyKeepReadings: Boolean = RUBY_KEEP_READINGS
    ): EpubHtmlDocument {
        val document = Jsoup.parse(stripCdata(html))
        // head/title/script/style 不属于正文；先摘除避免任何泄漏路径。
        document.select("script, style, noscript, template, head").remove()
        val builder = BlockBuilder(htmlDir, imageNamesByPath, rubyKeepReadings)
        builder.walkChildren(document.body() ?: document)
        builder.finish()
        return EpubHtmlDocument(
            blocks = builder.blocks,
            hasTocNavigation = document.getElementsByTag("nav").any { nav -> isTocNavigation(nav) }
        )
    }

    /** `epub:type` / `role` / `type` 含 toc 或 doc-toc 的 nav 即目录导航。 */
    private fun isTocNavigation(nav: Element): Boolean = nav.attributes().any { attribute ->
        val key = attribute.key.lowercase()
        (key == "epub:type" || key == "type" || key.endsWith(":type")) &&
            attribute.value.lowercase().split(WHITESPACE_RUN).any { it == "toc" || it == "doc-toc" }
    }

    /**
     * 剥离 CDATA 记号但保留其内容。
     *
     * jsoup 的 HTML 解析器把 `<![CDATA[...]]>` 当作注释丢弃；EPUB 里 CDATA
     * 偶尔用于包裹正文文本，直接丢弃会静默丢段。
     */
    private fun stripCdata(html: String): String =
        if (!html.contains("CDATA[")) html else CDATA_PATTERN.replace(html) { it.groupValues[1] }

    /** 单文档的块构建器：顺序遍历 DOM，按块边界切分文本。 */
    private class BlockBuilder(
        private val htmlDir: String,
        private val imageNamesByPath: Map<String, String>,
        private val rubyKeepReadings: Boolean
    ) {
        val blocks = mutableListOf<EpubHtmlBlock>()
        private var current = StringBuilder()
        private var currentHeadingLevel: Int? = null
        private var currentHeadingId: String? = null

        fun walkChildren(parent: Node) {
            for (node in parent.childNodes()) {
                when (node) {
                    is TextNode -> current.append(node.wholeText)
                    is Element -> walkElement(node)
                    else -> Unit // Comment / DocumentType / DataNode 不产生正文
                }
            }
        }

        private fun walkElement(element: Element) {
            when (val tag = element.tagName().lowercase()) {
                in SKIP_SUBTREE_TAGS -> return
                "br" -> {
                    current.append('\n')
                    return
                }
                "wbr" -> return // 软换行机会，不产生字符
                "hr" -> {
                    flush()
                    return
                }
                in IMAGE_TAGS -> {
                    flush()
                    emitImage(element)
                    return
                }
                "rt" -> {
                    // 读音默认丢弃；保留时包上全角括号随正文一起翻译。
                    if (rubyKeepReadings) {
                        val reading = element.text().trim()
                        if (reading.isNotEmpty()) current.append('（').append(reading).append('）')
                    }
                    return
                }
                in HEADING_LEVELS -> {
                    flush()
                    currentHeadingLevel = HEADING_LEVELS.getValue(tag)
                    currentHeadingId = element.id().takeIf { it.isNotBlank() }
                    walkChildren(element)
                    flush()
                    return
                }
                in INLINE_TAGS -> {
                    walkChildren(element)
                    return
                }
                else -> {
                    // 块级容器：前后刷新，形成独立块。
                    flush()
                    walkChildren(element)
                    flush()
                    return
                }
            }
        }

        private fun emitImage(element: Element) {
            val src = IMAGE_SRC_ATTRIBUTES
                .map { element.attr(it).trim() }
                .firstOrNull { it.isNotEmpty() }
                ?: return
            // data URI 内联图不进入正文：会把 base64 载荷变成无法解析的标记。
            if (src.startsWith("data:", ignoreCase = true)) return
            val href = src.substringBefore('#').substringBefore('?')
            if (href.isBlank()) return
            val normalizedPath = EpubPaths.resolve(htmlDir, href)
            val fileName = imageNamesByPath[normalizedPath]
                ?: imageNamesByPath.entries.firstOrNull { it.key.equals(href, ignoreCase = true) }?.value
                ?: href.substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: return
            blocks += EpubHtmlBlock(null, null, "[IMG:$fileName]")
        }

        /** 结束当前块：标题块即使为空也保留（它是章节切分的结构锚点）。 */
        private fun flush() {
            val raw = current.toString()
            current = StringBuilder()
            val headingLevel = currentHeadingLevel
            val headingId = currentHeadingId
            currentHeadingLevel = null
            currentHeadingId = null
            if (headingLevel != null) {
                blocks += EpubHtmlBlock(headingLevel, headingId, ImportTextNormalizer.normalizeBlockText(raw))
                return
            }
            if (raw.isBlank()) return
            val text = ImportTextNormalizer.normalizeBlockText(raw)
            if (text.isNotBlank()) blocks += EpubHtmlBlock(null, null, text)
        }

        fun finish() = flush()
    }
}
