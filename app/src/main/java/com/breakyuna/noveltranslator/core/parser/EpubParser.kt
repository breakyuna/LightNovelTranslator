package com.breakyuna.noveltranslator.core.parser

import com.breakyuna.noveltranslator.core.logger.SystemLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

data class ParsedEpubBook(
    val title: String,
    val author: String,
    val chapters: List<ParsedChapter>,
    val extractedImages: List<ExtractedImage>,
    val coverFileName: String? = null
)

data class ExtractedImage(
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray
)

/**
 * EPUB 导入解析器。
 *
 * 保真度策略（对标 LinguaGacha `epub-ast.ts` 的块级语义，但不复刻其 AST 定位单元：
 * LNT 导出时按 Edition 重新生成文本，导入侧只需保住结构与标记）：
 *
 * 1. 结构：container.xml → OPF manifest/spine → 按 spine 顺序读取 XHTML；
 *    每个文档经 [EpubHtmlExtractor] 做块级提取，标题块（h1-h6）同时是
 *    章内切分锚点——单个 XHTML 内含多个标题时按标题切分。
 * 2. 标题：nav.xhtml（EPUB3）/ NCX（EPUB2）目录标题优先，正文标题兜底，
 *    锚点（`chapter.xhtml#sec1`）按标题 id 精确匹配，数量一致时按序号匹配。
 * 3. 标记：图片统一转 `[IMG:文件名]`（导入契约不变，翻译层保护该标记）；
 *    ruby 默认丢弃读音，见 [EpubHtmlExtractor.RUBY_KEEP_READINGS]。
 * 4. 容错：zip 结构异常（无 OPF / 容器损坏 / 条目数或解压量超预算）显式报错，
 *    错误信息中文可读；可恢复的异常（容器损坏但有备用 OPF、字符集回退）记录
 *    警告后继续解析。
 *
 * 内存策略：只按需读取 zip 条目，单个条目 25 MB、解压总量 250 MB 预算不变；
 * 每个 XHTML 单独解析、提取后即丢弃 DOM，不整树驻留整本书。
 */
object EpubParser {

    /** Compatibility wrapper. New import code should prefer the InputStream/File overload. */
    fun parseEpub(
        epubBytes: ByteArray,
        imagesOutputDirectory: File? = null,
        cropTableOfContents: Boolean = false
    ): ParsedEpubBook {
        return parseEpub(ByteArrayInputStream(epubBytes), imagesOutputDirectory, cropTableOfContents)
    }

    /** Copies only the bounded stream to a temporary ZIP file, never materializing the package in a map. */
    fun parseEpub(
        input: InputStream,
        imagesOutputDirectory: File? = null,
        cropTableOfContents: Boolean = false
    ): ParsedEpubBook {
        val parent = imagesOutputDirectory?.parentFile
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val temporary = File.createTempFile("epub_import_", ".epub", parent)
        try {
            copyInputLimited(input, temporary)
            return parseEpubFile(temporary, imagesOutputDirectory, cropTableOfContents)
        } finally {
            temporary.delete()
        }
    }

    /** Parses a package from disk without retaining every ZIP entry in memory. */
    fun parseEpubFile(
        epubFile: File,
        imagesOutputDirectory: File? = null,
        cropTableOfContents: Boolean = false
    ): ParsedEpubBook {
        require(epubFile.length() <= MAX_EPUB_BYTES) { "EPUB 文件超过 100 MB 导入上限" }
        val entries = linkedMapOf<String, ZipEntry>()
        ZipFile(epubFile).use { zipFile ->
            val allEntries = zipFile.entries()
            while (allEntries.hasMoreElements()) {
                val entry = allEntries.nextElement()
                require(entries.size < MAX_ZIP_ENTRIES) { "EPUB 包含过多文件（超过 $MAX_ZIP_ENTRIES 个条目）" }
                if (!entry.isDirectory) entries[entry.name] = entry
            }

            val budget = ExtractionBudget()
            fun entryFor(path: String): ZipEntry? = zipEntryFor(entries, path)
            var charsetFallbackLogged = false

            fun readTextEntry(entry: ZipEntry): String =
                zipFile.getInputStream(entry).use { input ->
                    decodeTextBytes(readEntryLimited(input, budget)) { message ->
                        // 字符集回退按包只提示一次，避免逐章刷屏。
                        if (!charsetFallbackLogged) {
                            charsetFallbackLogged = true
                            SystemLogger.warn("EPUB_IMPORT", message)
                        }
                    }
                }

            // 1. Locate the OPF package document from META-INF/container.xml.
            val containerEntry = entryFor("META-INF/container.xml")
            var containerProblem: String? = null
            var opfPath: String? = null
            if (containerEntry != null) {
                opfPath = parseOpfPath(readTextEntry(containerEntry))
                if (opfPath == null) containerProblem = "META-INF/container.xml 损坏或缺少 rootfile 声明"
            } else {
                containerProblem = "缺少 META-INF/container.xml"
            }
            val opfEntry = (opfPath ?: entryFor("OEBPS/content.opf")?.name
                ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) })
                ?.let { path -> entryFor(path) }
                // 与条目数 / 解压量 / 无正文等结构性错误保持同一异常类型：
                // require() 系列抛 IllegalArgumentException，这里曾用 error() 抛
                // IllegalStateException，调用方无法用统一方式识别"包结构无效"。
                ?: throw IllegalArgumentException(
                    "EPUB 包结构无效：${containerProblem ?: "未找到 OPF 清单"}，且包内没有任何 .opf 文件"
                )
            if (containerProblem != null) {
                SystemLogger.warn(
                    "EPUB_IMPORT",
                    "EPUB 容器声明异常：$containerProblem；已按备用路径解析 ${opfEntry.name}"
                )
            }

            // 2. Read OPF metadata, manifest and spine.
            val opfDir = EpubPaths.directoryOf(opfEntry.name)
            val packageInfo = parseOpf(readTextEntry(opfEntry))
            val title = packageInfo.title ?: "Unknown Novel"
            val author = packageInfo.author ?: "Unknown Author"
            val manifestById = packageInfo.manifest.associateBy { it.id }
            val explicitCoverIds = mutableSetOf<String>()
            explicitCoverIds += packageInfo.coverIds

            val extractedImages = mutableListOf<ExtractedImage>()
            val imageNamesByPath = mutableMapOf<String, String>()
            var coverFileName: String? = null

            for (item in packageInfo.manifest) {
                if (item.properties.split(WHITESPACE).any { it.equals("cover-image", true) }) {
                    explicitCoverIds += item.id
                }
                if (!item.mediaType.startsWith("image/", ignoreCase = true)) continue

                val fullImagePath = EpubPaths.resolve(opfDir, item.href)
                val imageEntry = entryFor(fullImagePath) ?: entryFor(item.href) ?: continue
                val baseName = item.href.substringAfterLast("/").substringBefore('?')
                    .replace(Regex("[^A-Za-z0-9._-]"), "_")
                    .take(100)
                    .ifBlank { "illustration" }
                val fileName = "${Integer.toHexString(fullImagePath.hashCode())}_$baseName"
                imageNamesByPath[fullImagePath] = fileName
                if (item.id in explicitCoverIds || (coverFileName == null && item.href.contains("cover", true))) {
                    coverFileName = fileName
                }
                val imageBytes = if (imagesOutputDirectory == null) {
                    zipFile.getInputStream(imageEntry).use { input -> readEntryLimited(input, budget) }
                } else {
                    val outFile = File(imagesOutputDirectory, fileName)
                    outFile.parentFile?.mkdirs()
                    zipFile.getInputStream(imageEntry).use { input ->
                        writeEntryLimited(input, outFile, budget)
                    }
                    ByteArray(0)
                }
                extractedImages += ExtractedImage(fileName, item.mediaType, imageBytes)
            }

            // 3. Read the navigation document (EPUB3 nav, EPUB2 NCX fallback).
            val tocEntries = readTableOfContents(
                zipFile = zipFile,
                packageInfo = packageInfo,
                manifestById = manifestById,
                opfDir = opfDir,
                entries = entries,
                readTextEntry = { entry -> readTextEntry(entry) }
            )
            val tocEntriesByPath = tocEntries.groupBy { it.docPath }

            // 4. Read only spine HTML documents, in the order declared by the EPUB.
            val htmlFilesToProcess = if (packageInfo.spineIds.isNotEmpty()) {
                packageInfo.spineIds.mapNotNull { manifestById[it] }
                    .filter { it.mediaType.contains("html", ignoreCase = true) }
                    .map { it.href }
            } else {
                packageInfo.manifest
                    .filter { it.mediaType.contains("html", ignoreCase = true) }
                    .map { it.href }
            }

            val chapters = mutableListOf<ParsedChapter>()
            var chapterIndex = 1

            fun parseSpineDocument(rawHref: String) {
                val resolvedPath = EpubPaths.resolve(opfDir, rawHref)
                val htmlEntry = entryFor(resolvedPath) ?: entryFor(rawHref) ?: return
                val fullPath = htmlEntry.name
                val html = readTextEntry(htmlEntry)
                val htmlDir = EpubPaths.directoryOf(fullPath)
                val document = EpubHtmlExtractor.extract(html, htmlDir, imageNamesByPath)
                val firstHeading = document.blocks
                    .firstOrNull { it.headingLevel != null }?.text?.trim().orEmpty()
                if (cropTableOfContents &&
                    (document.hasTocNavigation || isTableOfContentsDocument(fullPath, html, firstHeading))
                ) {
                    return
                }
                val sections = splitIntoSections(document.blocks)
                if (sections.isEmpty()) return
                val tocForDoc = tocEntriesByPath[fullPath].orEmpty()
                for ((sectionIndex, section) in sections.withIndex()) {
                    val content = section.parts.joinToString("\n\n").trim()
                    if (content.isBlank()) continue
                    val chapterTitle =
                        resolveChapterTitle(section, sectionIndex, sections.size, tocForDoc, chapterIndex)
                    chapters += ParsedChapter(chapterIndex++, chapterTitle, content, TxtParser.countWords(content))
                }
            }

            htmlFilesToProcess.forEach { rawHref -> parseSpineDocument(rawHref) }
            if (chapters.isEmpty()) {
                entries.keys.filter(EpubPaths::isHtmlDocument).forEach { path -> parseSpineDocument(path) }
            }
            require(chapters.isNotEmpty()) {
                "EPUB 中未找到可阅读的章节内容（spine 为空或所有正文文件均无文本）"
            }

            val rasterCover = coverFileName?.takeIf { fileName ->
                extractedImages.firstOrNull { it.fileName == fileName }?.mediaType != "image/svg+xml"
            } ?: extractedImages.firstOrNull { it.mediaType != "image/svg+xml" }?.fileName
            return ParsedEpubBook(
                title = title,
                author = author,
                chapters = chapters,
                extractedImages = extractedImages,
                coverFileName = rasterCover
            )
        }
    }

    /** 大小写不敏感地按 zip 内规范路径取条目。 */
    private fun zipEntryFor(entries: Map<String, ZipEntry>, path: String): ZipEntry? =
        entries[path] ?: entries.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value

    /**
     * 从 container.xml 解析 OPF rootfile 的 full-path。
     * jsoup XML 解析优先（容忍属性顺序 / 单引号 / 注释），失败回退正则。
     */
    private fun parseOpfPath(containerXml: String): String? {
        val fromJsoup = runCatching {
            val document = Jsoup.parse(containerXml, "", Parser.xmlParser())
            document.getElementsByTag("rootfile")
                .firstOrNull()
                ?.attr("full-path")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (fromJsoup != null) return fromJsoup

        val fullPathMatcher = Pattern.compile(
            "full-path\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(containerXml)
        return if (fullPathMatcher.find()) {
            fullPathMatcher.group(1)?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }

    private data class OpfManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private data class OpfPackage(
        val title: String?,
        val author: String?,
        val coverIds: List<String>,
        val manifest: List<OpfManifestItem>,
        val spineIds: List<String>,
        val spineTocId: String?
    )

    /**
     * 解析 OPF：jsoup XML 解析优先；当它拿不到任何 manifest 条目时回退到
     * 既有正则实现（对病态 OPF 的兜底），元数据取二者合并结果。
     */
    private fun parseOpf(xml: String): OpfPackage {
        val parsed = runCatching { parseOpfWithJsoup(xml) }.getOrNull()
        if (parsed != null && parsed.manifest.isNotEmpty()) return parsed
        val fallback = runCatching { parseOpfWithRegex(xml) }.getOrNull()
        if (fallback != null && fallback.manifest.isNotEmpty()) {
            return fallback.copy(
                title = fallback.title ?: parsed?.title,
                author = fallback.author ?: parsed?.author
            )
        }
        return parsed ?: fallback ?: OpfPackage(null, null, emptyList(), emptyList(), emptyList(), null)
    }

    private fun parseOpfWithJsoup(xml: String): OpfPackage {
        val document = Jsoup.parse(xml, "", Parser.xmlParser())
        var title: String? = null
        var author: String? = null
        val coverIds = mutableListOf<String>()
        val manifest = mutableListOf<OpfManifestItem>()
        val spineIds = mutableListOf<String>()
        var spineTocId: String? = null
        for (element in document.getAllElements()) {
            when (localName(element.tagName())) {
                "title" -> if (title == null) {
                    val text = element.text().trim()
                    if (text.isNotEmpty()) title = text
                }
                "creator" -> if (author == null) {
                    val text = element.text().trim()
                    if (text.isNotEmpty()) author = text
                }
                "meta" -> if (element.attr("name").trim().equals("cover", ignoreCase = true)) {
                    element.attr("content").trim().takeIf { it.isNotEmpty() }?.let(coverIds::add)
                }
                "item" -> {
                    val id = element.attr("id").trim()
                    val href = element.attr("href").trim()
                    if (id.isNotEmpty() && href.isNotEmpty()) {
                        manifest += OpfManifestItem(
                            id = id,
                            href = href,
                            mediaType = element.attr("media-type").trim().ifEmpty { "text/html" },
                            properties = element.attr("properties").trim()
                        )
                    }
                }
                "itemref" -> element.attr("idref").trim().takeIf { it.isNotEmpty() }?.let(spineIds::add)
                "spine" -> element.attr("toc").trim().takeIf { it.isNotEmpty() }?.let { spineTocId = it }
            }
        }
        return OpfPackage(title, author, coverIds, manifest, spineIds, spineTocId)
    }

    private fun parseOpfWithRegex(xml: String): OpfPackage {
        var title: String? = null
        var author: String? = null
        val coverIds = mutableListOf<String>()
        val manifest = mutableListOf<OpfManifestItem>()
        val spineIds = mutableListOf<String>()

        val titleMatcher = Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>", Pattern.CASE_INSENSITIVE).matcher(xml)
        if (titleMatcher.find()) title = titleMatcher.group(1)?.trim()?.takeIf { it.isNotEmpty() }

        val authorMatcher = Pattern.compile("<dc:creator[^>]*>([^<]+)</dc:creator>", Pattern.CASE_INSENSITIVE).matcher(xml)
        if (authorMatcher.find()) author = authorMatcher.group(1)?.trim()?.takeIf { it.isNotEmpty() }

        val metaMatcher = Pattern.compile("<meta\\s+([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(xml)
        while (metaMatcher.find()) {
            val attrs = metaMatcher.group(1) ?: continue
            val nameMatch = Pattern.compile("name\\s*=\\s*[\"']cover[\"']", Pattern.CASE_INSENSITIVE).matcher(attrs)
            val contentMatch = Pattern.compile("content\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(attrs)
            if (nameMatch.find() && contentMatch.find()) coverIds += contentMatch.group(1)!!
        }

        val itemMatcher = Pattern.compile("<item\\s+([^>]+)/>|<item\\s+([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(xml)
        while (itemMatcher.find()) {
            val itemAttrs = itemMatcher.group(1) ?: itemMatcher.group(2) ?: ""
            val idMatch = Pattern.compile("id\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
            val hrefMatch = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
            val typeMatch = Pattern.compile("media-type\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
            val propertiesMatch = Pattern.compile("properties\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(itemAttrs)
            if (!idMatch.find() || !hrefMatch.find()) continue
            manifest += OpfManifestItem(
                id = idMatch.group(1)!!,
                href = hrefMatch.group(1)!!,
                mediaType = if (typeMatch.find()) typeMatch.group(1)!! else "text/html",
                properties = if (propertiesMatch.find()) propertiesMatch.group(1)!! else ""
            )
        }

        val spineMatcher = Pattern.compile("<itemref\\s+[^>]*idref\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(xml)
        while (spineMatcher.find()) spineMatcher.group(1)?.let(spineIds::add)

        val spineTocMatcher = Pattern.compile(
            "<spine\\b[^>]*\\btoc\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE
        ).matcher(xml)
        val spineTocId = if (spineTocMatcher.find()) spineTocMatcher.group(1) else null

        return OpfPackage(title, author, coverIds, manifest, spineIds, spineTocId)
    }

    /** 命名空间前缀（dc: / epub:）与 Clark 记法只比较本地名。 */
    private fun localName(tagName: String): String =
        tagName.substringAfterLast(':').substringAfterLast('}')

    /**
     * 读取目录：EPUB3 nav（manifest properties="nav"）优先；
     * nav 缺失或解析不出条目时回退 NCX（spine@toc → media-type → 包内 .ncx）。
     */
    private fun readTableOfContents(
        zipFile: ZipFile,
        packageInfo: OpfPackage,
        manifestById: Map<String, OpfManifestItem>,
        opfDir: String,
        entries: Map<String, ZipEntry>,
        readTextEntry: (ZipEntry) -> String
    ): List<EpubTocEntry> {
        val navItem = packageInfo.manifest.firstOrNull { item ->
            item.properties.split(WHITESPACE).any { it.equals("nav", true) }
        }
        val ncxItem = packageInfo.spineTocId?.let(manifestById::get)
            ?: packageInfo.manifest.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", true) }
        val ncxPath = ncxItem?.let { EpubPaths.resolve(opfDir, it.href) }
            ?: entries.keys.firstOrNull { it.endsWith(".ncx", ignoreCase = true) }

        val navEntries = navItem?.let { item ->
            val path = EpubPaths.resolve(opfDir, item.href)
            zipEntryFor(entries, path)?.let { entry ->
                // 字符集 / 预算错误必须抛出，只容忍目录解析本身的失败。
                runCatching {
                    EpubTocParser.parseNav(readTextEntry(entry), EpubPaths.directoryOf(path))
                }.getOrNull()
            }
        }.orEmpty()
        if (navEntries.isNotEmpty()) return navEntries

        return ncxPath?.let { path ->
            zipEntryFor(entries, path)?.let { entry ->
                runCatching {
                    EpubTocParser.parseNcx(readTextEntry(entry), EpubPaths.directoryOf(path))
                }.getOrNull()
            }
        }.orEmpty()
    }

    /** 章内小节：标题块开新节，其余块归入当前节。 */
    private class EpubSection(
        var title: String?,
        val headingId: String?,
        val parts: MutableList<String>
    )

    /**
     * 把块序列切成章内小节。
     * 第一个标题之前的内容并入第一节（与既有"全文归首章"行为一致）；
     * 只有标题没有正文的小节向上/向下合并，避免产出空章节。
     */
    private fun splitIntoSections(blocks: List<EpubHtmlBlock>): List<EpubSection> {
        val sections = mutableListOf<EpubSection>()
        val leadingParts = mutableListOf<String>()
        for (block in blocks) {
            if (block.headingLevel != null) {
                sections += EpubSection(
                    title = block.text.trim().ifBlank { null },
                    headingId = block.headingId,
                    parts = mutableListOf()
                )
            } else if (sections.isEmpty()) {
                leadingParts += block.text
            } else {
                sections.last().parts += block.text
            }
        }
        if (sections.isEmpty()) {
            if (leadingParts.isEmpty()) return emptyList()
            return listOf(EpubSection(null, null, leadingParts))
        }
        if (leadingParts.isNotEmpty()) sections.first().parts.addAll(0, leadingParts)

        val merged = mutableListOf<EpubSection>()
        val pendingTitles = mutableListOf<String>()
        for (section in sections) {
            val hasContent = section.parts.any { it.isNotBlank() }
            if (!hasContent) {
                section.title?.let { pendingTitles += it }
                continue
            }
            section.title = (pendingTitles + listOfNotNull(section.title))
                .joinToString(" ")
                .trim()
                .ifBlank { null }
            pendingTitles.clear()
            merged += section
        }
        // 末尾只剩标题没有正文：标题并回上一节正文，整篇只有标题则整篇跳过。
        if (pendingTitles.isNotEmpty() && merged.isNotEmpty()) {
            merged.last().parts += pendingTitles.joinToString(" ")
        }
        return merged
    }

    /**
     * 章节标题解析：目录标题优先，正文标题兜底，最后退化为 `Chapter N`。
     * 目录条目优先按锚点（标题 id）匹配，数量一致时按小节序号匹配。
     */
    private fun resolveChapterTitle(
        section: EpubSection,
        sectionIndex: Int,
        sectionCount: Int,
        tocForDoc: List<EpubTocEntry>,
        chapterIndex: Int
    ): String {
        val fallback = "Chapter $chapterIndex"
        val tocTitle = matchTocEntry(section, sectionIndex, sectionCount, tocForDoc)?.title?.trim()
        val heading = section.title?.trim()
        return (tocTitle?.takeIf { it.isNotEmpty() }
            ?: heading?.takeIf { it.isNotEmpty() }
            ?: fallback)
            .take(MAX_CHAPTER_TITLE_CHARS)
            .trim()
            .ifBlank { fallback }
    }

    private fun matchTocEntry(
        section: EpubSection,
        sectionIndex: Int,
        sectionCount: Int,
        tocForDoc: List<EpubTocEntry>
    ): EpubTocEntry? {
        if (tocForDoc.isEmpty()) return null
        section.headingId?.let { headingId ->
            tocForDoc.firstOrNull { entry -> entry.fragment == headingId }?.let { entry -> return entry }
        }
        if (tocForDoc.size == sectionCount) return tocForDoc.getOrNull(sectionIndex)
        return null
    }

    private fun isTableOfContentsDocument(path: String, html: String, title: String): Boolean {
        val linkMatcher = Pattern.compile("<a\\b[^>]*href\\s*=", Pattern.CASE_INSENSITIVE).matcher(html)
        var linkCount = 0
        while (linkMatcher.find() && linkCount < 8) linkCount++

        val hasTocNavigation = TOC_NAVIGATION_MARKER.containsMatchIn(html)
        val pathStem = path.substringAfterLast('/').substringBeforeLast('.', path).lowercase(Locale.ROOT)
        val pathSuggestsToc = pathStem.contains("toc") || pathStem.contains("contents") ||
            pathStem.contains("navigation") || pathStem == "nav" || pathStem.contains("目次") || pathStem.contains("目录")
        val titleSuggestsToc = title.trim().lowercase(Locale.ROOT) in setOf(
            "目录", "目次", "contents", "content", "table of contents"
        )

        return hasTocNavigation ||
            (pathSuggestsToc && linkCount >= 1) ||
            (titleSuggestsToc && linkCount >= 1)
    }

    /**
     * 解码 zip 内文本条目（XHTML / OPF / NCX / container.xml）。
     *
     * 顺序：BOM → UTF-8 严格 → `<meta charset>` 声明 → GB18030。
     * 旧实现一律按 UTF-8 解码，GBK 包会静默产出乱码；这里与
     * [TxtParser.detectCharsetAndRead] 的取舍对齐，严格 UTF-8 失败后补救。
     */
    private fun decodeTextBytes(bytes: ByteArray, onCharsetFallback: (String) -> Unit = {}): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            // 继续按声明字符集 / GB18030 兜底
        }
        // meta charset 声明只在前 2KB 内嗅探，足够覆盖 <head>。
        val declared = META_CHARSET_PATTERN.find(
            String(bytes, 0, minOf(bytes.size, CHARSET_SNIFF_BYTES), StandardCharsets.ISO_8859_1)
        )?.groupValues?.getOrNull(1)?.trim()
        val declaredCharset = declared?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
        val charset = declaredCharset?.takeIf { it != StandardCharsets.UTF_8 }
            ?: runCatching { Charset.forName("GB18030") }.getOrDefault(StandardCharsets.ISO_8859_1)
        onCharsetFallback(
            "文本条目不是合法 UTF-8，已按 ${charset.name()} 解码" +
                (declared?.let { "（meta charset=$it）" }.orEmpty())
        )
        return String(bytes, charset)
    }

    private class ExtractionBudget(var total: Long = 0)

    private fun readEntryLimited(input: InputStream, budget: ExtractionBudget): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            budget.total += count
            require(total <= MAX_ENTRY_BYTES) { "EPUB 内单个文件超过 25 MB 上限" }
            require(budget.total <= MAX_UNCOMPRESSED_BYTES) {
                "EPUB 解压后总量超过 250 MB 安全上限"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeEntryLimited(input: InputStream, outputFile: File, budget: ExtractionBudget) {
        outputFile.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                budget.total += count
                require(total <= MAX_ENTRY_BYTES) { "EPUB 内单个文件超过 25 MB 上限" }
                require(budget.total <= MAX_UNCOMPRESSED_BYTES) {
                    "EPUB 解压后总量超过 250 MB 安全上限"
                }
                output.write(buffer, 0, count)
            }
        }
    }

    private fun copyInputLimited(input: InputStream, outputFile: File) {
        outputFile.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_EPUB_BYTES) { "EPUB 文件超过 100 MB 导入上限" }
                output.write(buffer, 0, count)
            }
        }
    }

    private const val MAX_EPUB_BYTES = 100 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 25 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 250L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_CHAPTER_TITLE_CHARS = 300
    private const val CHARSET_SNIFF_BYTES = 2048
    private val WHITESPACE = Regex("\\s+")
    private val META_CHARSET_PATTERN = Regex(
        "<meta[^>]+charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_:.-]+)",
        RegexOption.IGNORE_CASE
    )
    private val TOC_NAVIGATION_MARKER = Regex(
        "<nav\\b[^>]*(?:epub:type|role)\\s*=\\s*[\"'][^\"']*(?:\\btoc\\b|\\bdoc-toc\\b)[^\"']*[\"']",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
}
