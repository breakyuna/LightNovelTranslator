package com.breakyuna.noveltranslator.core.parser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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

    /** Compatibility wrapper. New import code should prefer the InputStream/File overload. */
    fun parseEpub(epubBytes: ByteArray, imagesOutputDirectory: File? = null): ParsedEpubBook {
        return parseEpub(ByteArrayInputStream(epubBytes), imagesOutputDirectory)
    }

    /** Copies only the bounded stream to a temporary ZIP file, never materializing the package in a map. */
    fun parseEpub(input: InputStream, imagesOutputDirectory: File? = null): ParsedEpubBook {
        val parent = imagesOutputDirectory?.parentFile
            ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val temporary = File.createTempFile("epub_import_", ".epub", parent)
        try {
            copyInputLimited(input, temporary)
            return parseEpubFile(temporary, imagesOutputDirectory)
        } finally {
            temporary.delete()
        }
    }

    /** Parses a package from disk without retaining every ZIP entry in memory. */
    fun parseEpubFile(epubFile: File, imagesOutputDirectory: File? = null): ParsedEpubBook {
        require(epubFile.length() <= MAX_EPUB_BYTES) { "EPUB exceeds the 100 MB import limit" }
        val entries = linkedMapOf<String, ZipEntry>()
        ZipFile(epubFile).use { zipFile ->
            val allEntries = zipFile.entries()
            while (allEntries.hasMoreElements()) {
                val entry = allEntries.nextElement()
                require(entries.size < MAX_ZIP_ENTRIES) { "EPUB contains too many files" }
                if (!entry.isDirectory) entries[entry.name] = entry
            }

            val budget = ExtractionBudget()
            fun entryFor(path: String): ZipEntry? = entries[path]
                ?: entries.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value

            // 1. Find rootfile from META-INF/container.xml.
            val containerEntry = entryFor("META-INF/container.xml")
            var opfPath = "OEBPS/content.opf"
            if (containerEntry != null) {
                val containerXml = zipFile.getInputStream(containerEntry).use {
                    String(readEntryLimited(it, budget), Charsets.UTF_8)
                }
                val fullPathMatcher = Pattern.compile("full-path\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(containerXml)
                if (fullPathMatcher.find()) opfPath = fullPathMatcher.group(1) ?: opfPath
            }

            val opfDir = if (opfPath.contains("/")) opfPath.substringBeforeLast("/") + "/" else ""
            val opfEntry = entryFor(opfPath)
                ?: entries.entries.firstOrNull { it.key.endsWith(".opf", ignoreCase = true) }?.value
            val opfXml = opfEntry?.let {
                zipFile.getInputStream(it).use { input ->
                    String(readEntryLimited(input, budget), Charsets.UTF_8)
                }
            }

            var title = "Unknown Novel"
            var author = "Unknown Author"
            val manifest = mutableMapOf<String, Pair<String, String>>()
            val spineItems = mutableListOf<String>()
            val extractedImages = mutableListOf<ExtractedImage>()
            val imageNamesByPath = mutableMapOf<String, String>()

            if (opfXml != null) {
                val titleMatcher = Pattern.compile("<dc:title[^>]*>([^<]+)</dc:title>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
                if (titleMatcher.find()) title = titleMatcher.group(1)?.trim() ?: title

                val authorMatcher = Pattern.compile("<dc:creator[^>]*>([^<]+)</dc:creator>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
                if (authorMatcher.find()) author = authorMatcher.group(1)?.trim() ?: author

                val itemMatcher = Pattern.compile("<item\\s+([^>]+)/>|<item\\s+([^>]+)>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
                while (itemMatcher.find()) {
                    val itemAttrs = itemMatcher.group(1) ?: itemMatcher.group(2) ?: ""
                    val idMatch = Pattern.compile("id\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
                    val hrefMatch = Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
                    val typeMatch = Pattern.compile("media-type\\s*=\\s*[\"']([^\"']+)[\"']").matcher(itemAttrs)
                    if (!idMatch.find() || !hrefMatch.find()) continue

                    val id = idMatch.group(1)!!
                    val href = hrefMatch.group(1)!!
                    val type = if (typeMatch.find()) typeMatch.group(1)!! else "text/html"
                    manifest[id] = href to type

                    if (type.startsWith("image/", ignoreCase = true)) {
                        val fullImagePath = resolvePath(opfDir, href)
                        val imageEntry = entryFor(fullImagePath) ?: entryFor(href)
                        if (imageEntry != null) {
                            val baseName = href.substringAfterLast("/").substringBefore('?')
                                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                                .take(100)
                                .ifBlank { "illustration" }
                            val fileName = "${Integer.toHexString(fullImagePath.hashCode())}_$baseName"
                            imageNamesByPath[fullImagePath] = fileName
                            val imageBytes = if (imagesOutputDirectory == null) {
                                zipFile.getInputStream(imageEntry).use { input ->
                                    readEntryLimited(input, budget)
                                }
                            } else {
                                val outFile = File(imagesOutputDirectory, fileName)
                                outFile.parentFile?.mkdirs()
                                zipFile.getInputStream(imageEntry).use { input ->
                                    writeEntryLimited(input, outFile, budget)
                                }
                                ByteArray(0)
                            }
                            extractedImages.add(ExtractedImage(fileName, type, imageBytes))
                        }
                    }
                }

                val spineMatcher = Pattern.compile("<itemref\\s+[^>]*idref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfXml)
                while (spineMatcher.find()) spineMatcher.group(1)?.let(spineItems::add)
            }

            // 2. Read only spine HTML entries, in the order declared by the EPUB.
            val chapters = mutableListOf<ParsedChapter>()
            var chapterIndex = 1
            val htmlFilesToProcess = if (spineItems.isNotEmpty()) {
                spineItems.mapNotNull { manifest[it]?.first }
            } else {
                manifest.values.filter { it.second.contains("html") }.map { it.first }
            }

            fun parseHtml(path: String) {
                val fullHtmlPath = resolvePath(opfDir, path)
                val htmlEntry = entryFor(fullHtmlPath) ?: entryFor(path) ?: return
                val htmlContent = zipFile.getInputStream(htmlEntry).use { input ->
                    String(readEntryLimited(input, budget), Charsets.UTF_8)
                }
                val htmlDir = fullHtmlPath.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }
                val (chapterTitle, textBody) = cleanHtmlToPlainText(htmlContent, chapterIndex, htmlDir, imageNamesByPath)
                if (textBody.isNotBlank()) {
                    chapters.add(ParsedChapter(chapterIndex++, chapterTitle, textBody, TxtParser.countWords(textBody)))
                }
            }

            htmlFilesToProcess.forEach(::parseHtml)
            if (chapters.isEmpty()) {
                entries.keys.filter {
                    it.endsWith(".html", ignoreCase = true) ||
                        it.endsWith(".xhtml", ignoreCase = true) ||
                        it.endsWith(".htm", ignoreCase = true)
                }.forEach(::parseHtml)
            }

            return ParsedEpubBook(title, author, chapters, extractedImages)
        }
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

    private fun cleanHtmlToPlainText(
        html: String,
        fallbackIndex: Int,
        htmlDir: String,
        imageNamesByPath: Map<String, String>
    ): Pair<String, String> {
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
            val normalizedPath = resolvePath(htmlDir, src.substringBefore('#').substringBefore('?'))
            val fileName = imageNamesByPath[normalizedPath] ?: src.substringAfterLast("/").substringBefore('?')
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
            val normalizedPath = resolvePath(htmlDir, src.substringBefore('#').substringBefore('?'))
            val fileName = imageNamesByPath[normalizedPath] ?: src.substringAfterLast("/").substringBefore('?')
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
            require(total <= MAX_ENTRY_BYTES) { "EPUB contains an oversized file" }
            require(budget.total <= MAX_UNCOMPRESSED_BYTES) {
                "EPUB expands beyond the 250 MB safety limit"
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
                require(total <= MAX_ENTRY_BYTES) { "EPUB contains an oversized file" }
                require(budget.total <= MAX_UNCOMPRESSED_BYTES) {
                    "EPUB expands beyond the 250 MB safety limit"
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
                require(total <= MAX_EPUB_BYTES) { "EPUB exceeds the 100 MB import limit" }
                output.write(buffer, 0, count)
            }
        }
    }

    private const val MAX_EPUB_BYTES = 100 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 25 * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 250L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 10_000
}
