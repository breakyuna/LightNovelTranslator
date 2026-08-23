package com.example.core.parser

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.regex.Pattern

data class ParsedEpubBook(
    val title: String,
    val author: String,
    val chapters: List<ParsedChapter>,
    val extractedImages: List<ExtractedImage>
)

data class ExtractedImage(
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray
)

object EpubParser {

    fun parseEpub(epubBytes: ByteArray, imagesOutputDirectory: File? = null): ParsedEpubBook {
        val zipMap = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(epubBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zis.readBytes()
                    zipMap[entry.name] = bytes
                }
                entry = zis.nextEntry
            }
        }

        // 1. Find rootfile from META-INF/container.xml
        val containerBytes = zipMap["META-INF/container.xml"] ?: zipMap["meta-inf/container.xml"]
        var opfPath = "OEBPS/content.opf"
        if (containerBytes != null) {
            val containerXml = String(containerBytes, Charsets.UTF_8)
            val fullPathMatcher = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(containerXml)
            if (fullPathMatcher.find()) {
                opfPath = fullPathMatcher.group(1) ?: opfPath
            }
        }

        // Normalize OPF directory
        val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
        val opfBytes = zipMap[opfPath] ?: zipMap.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }?.value

        var title = "Unknown Novel"
        var author = "Unknown Author"
        val manifest = mutableMapOf<String, Pair<String, String>>() // id -> (href, mediaType)
        val spineItems = mutableListOf<String>() // list of idref
        val extractedImages = mutableListOf<ExtractedImage>()

        if (opfBytes != null) {
            val opfXml = String(opfBytes, Charsets.UTF_8)

            // Extract Title
            val titleMatcher = Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
            if (titleMatcher.find()) {
                title = titleMatcher.group(1)?.trim() ?: title
            }

            // Extract Creator / Author
            val authorMatcher = Pattern.compile("<dc:creator[^>]*>([^<]+)</dc:creator>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
            if (authorMatcher.find()) {
                author = authorMatcher.group(1)?.trim() ?: author
            }

            // Extract Manifest items
            val itemMatcher = Pattern.compile("<item\\s+([^>]+)/>|<item\\s+([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
            while (itemMatcher.find()) {
                val itemAttrs = (itemMatcher.group(1) ?: itemMatcher.group(2)) ?: ""
                val idMatch = Pattern.compile("id\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
                val hrefMatch = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
                val typeMatch = Pattern.compile("media-type\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)

                if (idMatch.find() && hrefMatch.find()) {
                    val id = idMatch.group(1)!!
                    val href = hrefMatch.group(1)!!
                    val type = if (typeMatch.find()) typeMatch.group(1)!! else "text/html"
                    manifest[id] = Pair(href, type)

                    // Extract image assets
                    if (type.startsWith("image/", ignoreCase = true)) {
                        val fullImagePath = resolvePath(opfDir, href)
                        val imgBytes = zipMap[fullImagePath] ?: zipMap[href]
                        if (imgBytes != null) {
                            val fileName = href.substringAfterLast("/")
                            extractedImages.add(ExtractedImage(fileName, type, imgBytes))
                            if (imagesOutputDirectory != null) {
                                val outFile = File(imagesOutputDirectory, fileName)
                                outFile.writeBytes(imgBytes)
                            }
                        }
                    }
                }
            }

            // Extract Spine
            val spineMatcher = Pattern.compile("<itemref\\s+[^>]*idref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
            while (spineMatcher.find()) {
                val idref = spineMatcher.group(1)
                if (idref != null) {
                    spineItems.add(idref)
                }
            }
        }

        // Extract HTML chapters following Spine order
        val chapters = mutableListOf<ParsedChapter>()
        var chapterIndex = 1

        val htmlFilesToProcess = if (spineItems.isNotEmpty()) {
            spineItems.mapNotNull { manifest[it]?.first }
        } else {
            manifest.values.filter { it.second.contains("html") }.map { it.first }
        }

        for (href in htmlFilesToProcess) {
            val fullHtmlPath = resolvePath(opfDir, href)
            val htmlBytes = zipMap[fullHtmlPath] ?: zipMap[href] ?: continue
            val htmlContent = String(htmlBytes, Charsets.UTF_8)
            val (chapterTitle, textBody) = cleanHtmlToPlainText(htmlContent, chapterIndex)

            if (textBody.isNotBlank()) {
                chapters.add(
                    ParsedChapter(
                        index = chapterIndex++,
                        title = chapterTitle,
                        content = textBody,
                        wordCount = TxtParser.countWords(textBody)
                    )
                )
            }
        }

        if (chapters.isEmpty()) {
            // Fallback if spine wasn't parsed correctly: parse any html/xhtml in zip
            for ((key, bytes) in zipMap) {
                if (key.endsWith(".html", ignoreCase = true) || key.endsWith(".xhtml", ignoreCase = true) || key.endsWith(".htm", ignoreCase = true)) {
                    val htmlContent = String(bytes, Charsets.UTF_8)
                    val (cTitle, textBody) = cleanHtmlToPlainText(htmlContent, chapterIndex)
                    if (textBody.isNotBlank()) {
                        chapters.add(
                            ParsedChapter(
                                index = chapterIndex++,
                                title = cTitle,
                                content = textBody,
                                wordCount = TxtParser.countWords(textBody)
                            )
                        )
                    }
                }
            }
        }

        return ParsedEpubBook(
            title = title,
            author = author,
            chapters = chapters,
            extractedImages = extractedImages
        )
    }

    private fun resolvePath(baseDir: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val combined = if (baseDir.isEmpty() || href.startsWith("/")) {
            href.removePrefix("/")
        } else {
            "$baseDir$href"
        }
        val parts = combined.split("/").filter { it.isNotEmpty() }
        val stack = mutableListOf<String>()
        for (part in parts) {
            when (part) {
                "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun cleanHtmlToPlainText(html: String, fallbackIndex: Int): Pair<String, String> {
        // Extract Heading if available
        var extractedTitle = "Chapter $fallbackIndex"
        val hMatcher = Pattern.compile("<h[1-3][^>]*>(.*?)</h[1-3]>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL).matcher(html)
        if (hMatcher.find()) {
            val rawH = hMatcher.group(1)?.replace(Regex("<[^>]+>"), "")?.trim()
            if (!rawH.isNullOrBlank()) {
                extractedTitle = rawH
            }
        }

        // Preserve images as [IMG:filename]
        var processed = html
        val imgMatcher = Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(processed)
        val imgReplacements = mutableListOf<Pair<String, String>>()
        while (imgMatcher.find()) {
            val fullTag = imgMatcher.group(0)!!
            val src = imgMatcher.group(1)!!
            val fileName = src.substringAfterLast("/")
            imgReplacements.add(Pair(fullTag, "\n\n[IMG:$fileName]\n\n"))
        }
        for ((tag, rep) in imgReplacements) {
            processed = processed.replace(tag, rep)
        }

        val svgImgMatcher = Pattern.compile("<image[^>]+(?:xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(processed)
        val svgReplacements = mutableListOf<Pair<String, String>>()
        while (svgImgMatcher.find()) {
            val fullTag = svgImgMatcher.group(0)!!
            val src = svgImgMatcher.group(1)!!
            val fileName = src.substringAfterLast("/")
            svgReplacements.add(Pair(fullTag, "\n\n[IMG:$fileName]\n\n"))
        }
        for ((tag, rep) in svgReplacements) {
            processed = processed.replace(tag, rep)
        }

        // Replace <br> and <p> with newlines
        val cleaned = processed
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("</div>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

        return Pair(extractedTitle, cleaned)
    }
}
