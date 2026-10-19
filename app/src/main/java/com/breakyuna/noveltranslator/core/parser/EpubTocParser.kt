package com.breakyuna.noveltranslator.core.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 目录（TOC）条目：标题 + 目标正文文档（zip 内规范路径）+ 片段锚点。
 */
data class EpubTocEntry(
    val title: String,
    val docPath: String,
    val fragment: String?
)

/**
 * EPUB 目录解析：EPUB3 `nav.xhtml`（`epub:type="toc"`）与 EPUB2 NCX（`toc.ncx`）。
 *
 * 目录标题优先于正文标题：包级目录通常是章节的规范名称，而正文标题有时缺失，
 * 或退化为 "Chapter 1" 之类的占位文本；`spine` 顺序仍由阅读顺序决定。
 *
 * 用 jsoup HTML 解析器而不是 XML 解析器：坏包的目录文档经常出现未转义 `&`、
 * 未闭合标签，HTML 容错解析能多救回一批条目；条目本身只是短文本，
 * 标签名归一不会造成损失。
 */
object EpubTocParser {

    /** 解析 EPUB3 导航文档；未声明 `epub:type="toc"` 时退回全文档链接。 */
    fun parseNav(html: String, navDir: String): List<EpubTocEntry> {
        val document = Jsoup.parse(html)
        val navRoots = document.getElementsByTag("nav").filter { isTocNavigation(it) }
        val roots: List<Element> = if (navRoots.isEmpty()) listOfNotNull(document.body()) else navRoots
        val entries = mutableListOf<EpubTocEntry>()
        for (root in roots) {
            for (anchor in root.getElementsByTag("a")) {
                entries += entryFor(anchor.attr("href").trim(), anchor.text().trim(), navDir) ?: continue
            }
        }
        return entries
    }

    /** 解析 EPUB2 NCX：按 navPoint 文档序取 `navLabel/text` 标题与 `content/@src` 目标。 */
    fun parseNcx(html: String, ncxDir: String): List<EpubTocEntry> {
        val document = Jsoup.parse(html)
        val entries = mutableListOf<EpubTocEntry>()
        for (navPoint in document.getElementsByTag("navpoint")) {
            val src = navPoint.getElementsByTag("content").firstOrNull()?.attr("src")?.trim().orEmpty()
            if (src.isEmpty()) continue
            val label = navPoint.getElementsByTag("text").firstOrNull()?.text()?.trim().orEmpty()
            entries += entryFor(src, label, ncxDir) ?: continue
        }
        return entries
    }

    private val WHITESPACE = Regex("\\s+")

    private fun isTocNavigation(nav: Element): Boolean = nav.attributes().any { attribute ->
        val key = attribute.key.lowercase()
        (key == "epub:type" || key == "type" || key.endsWith(":type")) &&
            attribute.value.lowercase().split(WHITESPACE).any { it == "toc" || it == "doc-toc" }
    }

    /** 标题为空时退回 href 末段，保证条目不会因为缺标题而被丢弃。 */
    private fun entryFor(href: String, title: String, baseDir: String): EpubTocEntry? {
        if (href.isEmpty() || href.startsWith("data:", ignoreCase = true)) return null
        val pathAndFragment = href.substringBefore('?')
        val fragment = pathAndFragment.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val path = pathAndFragment.substringBefore('#')
        val docPath = EpubPaths.resolve(baseDir, path)
        if (docPath.isEmpty()) return null
        return EpubTocEntry(
            title = title.ifBlank { path.substringAfterLast('/') },
            docPath = docPath,
            fragment = fragment
        )
    }
}
