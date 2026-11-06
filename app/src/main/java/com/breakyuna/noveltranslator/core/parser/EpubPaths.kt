package com.breakyuna.noveltranslator.core.parser

/**
 * EPUB 包内 POSIX 路径工具。
 *
 * manifest href、spine 文档、TOC 锚点与图片引用共用同一套解析语义，
 * 避免各处重复实现导致相对路径解析不一致。
 */
internal object EpubPaths {

    /** 判断路径是否为 EPUB 正文章档（XHTML / HTML）。 */
    fun isHtmlDocument(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".xhtml") || lower.endsWith(".html") ||
            lower.endsWith(".htm") || lower.endsWith(".xhtm")
    }

    /**
     * 把 href 解析为 zip 内规范路径：相对 [baseDir] 拼接，折叠 `.` / `..`，
     * 反斜杠统一为正斜杠。`http(s)` 外链原样返回，由调用方按外链策略处理。
     */
    fun resolve(baseDir: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val normalizedHref = href.replace('\\', '/')
        val combined = if (baseDir.isEmpty() || normalizedHref.startsWith("/")) {
            normalizedHref.removePrefix("/")
        } else {
            "$baseDir$normalizedHref"
        }
        val stack = mutableListOf<String>()
        for (part in combined.split("/")) {
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack += part
            }
        }
        return stack.joinToString("/")
    }

    /** 文档所在目录（含尾部 `/`）；位于 zip 根目录时返回空串。 */
    fun directoryOf(path: String): String =
        path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
}
