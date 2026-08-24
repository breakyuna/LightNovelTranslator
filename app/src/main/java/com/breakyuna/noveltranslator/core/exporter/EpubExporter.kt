package com.breakyuna.noveltranslator.core.exporter

import com.breakyuna.noveltranslator.data.model.ChapterEntity
import com.breakyuna.noveltranslator.data.model.ProjectEntity
import com.breakyuna.noveltranslator.core.project.ProjectFileManager
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubExporter {

    fun exportEpub(
        project: ProjectEntity,
        chapters: List<ChapterEntity>,
        fileManager: ProjectFileManager
    ): File {
        val exportsDir = fileManager.getExportsDir(project.id)
        val sanitizedTitle = project.title.replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim().take(80).ifBlank { "translated_novel" }
        val exportFile = File(exportsDir, "${sanitizedTitle}_translated.epub")

        val imagesDir = fileManager.getImagesDir(project.id)
        val imageFiles = imagesDir.listFiles()?.filter { it.isFile } ?: emptyList()

        ZipOutputStream(FileOutputStream(exportFile)).use { zos ->
            // 1. mimetype (MUST BE FIRST, UNCOMPRESSED)
            val mimetypeEntry = ZipEntry("mimetype")
            mimetypeEntry.method = ZipEntry.STORED
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            mimetypeEntry.size = mimeBytes.size.toLong()
            mimetypeEntry.compressedSize = mimeBytes.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(mimeBytes)
            mimetypeEntry.crc = crc.value
            zos.putNextEntry(mimetypeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            // 2. META-INF/container.xml
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
   <rootfiles>
      <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
   </rootfiles>
</container>""".trimIndent()
            addZipEntry(zos, "META-INF/container.xml", containerXml.toByteArray(Charsets.UTF_8))

            // 3. CSS Style
            val css = """
body {
    font-family: serif, "Source Han Serif", "Songti SC", "SimSun", Georgia;
    line-height: 1.8;
    margin: 5%;
    padding: 0;
    color: #222222;
}
h1 {
    font-size: 1.5em;
    font-weight: bold;
    text-align: center;
    margin-top: 1.5em;
    margin-bottom: 1.2em;
    color: #111111;
}
p {
    text-indent: 2em;
    margin-top: 0.6em;
    margin-bottom: 0.6em;
}
img {
    max-width: 100%;
    height: auto;
    display: block;
    margin: 1.5em auto;
}
            """.trimIndent()
            addZipEntry(zos, "OEBPS/style.css", css.toByteArray(Charsets.UTF_8))

            // 4. Chapter XHTMLs
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()
            val navPoints = StringBuilder()

            manifestItems.append("""    <item id="style" href="style.css" media-type="text/css"/>${"\n"}""")
            manifestItems.append("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>${"\n"}""")

            // Add embedded images to manifest
            imageFiles.forEachIndexed { idx, imgFile ->
                val ext = imgFile.extension.lowercase()
                val mime = when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    "svg" -> "image/svg+xml"
                    else -> "image/jpeg"
                }
                manifestItems.append("""    <item id="img_$idx" href="images/${escapeXml(imgFile.name)}" media-type="$mime"/>${"\n"}""")
                addZipEntry(zos, "OEBPS/images/${imgFile.name}", imgFile.readBytes())
            }

            chapters.forEachIndexed { index, chapter ->
                val chapNum = index + 1
                val fileHref = "chapter_$chapNum.xhtml"
                val chapId = "chap_$chapNum"

                manifestItems.append("""    <item id="$chapId" href="$fileHref" media-type="application/xhtml+xml"/>${"\n"}""")
                spineItems.append("""    <itemref idref="$chapId"/>${"\n"}""")
                navPoints.append("""
    <navPoint id="navPoint-$chapNum" playOrder="$chapNum">
      <navLabel><text>${escapeXml(chapter.title)}</text></navLabel>
      <content src="$fileHref"/>
    </navPoint>""")

                val translatedText = fileManager.readTranslatedChapter(project.id, chapter.translatedFileName)
                val rawText = if (translatedText.isNotBlank()) translatedText else fileManager.readOriginalChapter(project.id, chapter.originalFileName)
                val paragraphsHtml = rawText.lines()
                    .filter { it.trim().isNotEmpty() }
                    .joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("[IMG:") && trimmed.endsWith("]")) {
                            val imgFile = trimmed.removePrefix("[IMG:").removeSuffix("]")
                            "    <p class=\"illustration\"><img src=\"images/${escapeXml(imgFile)}\" alt=\"illustration\"/></p>"
                        } else {
                            "    <p>${escapeXml(trimmed)}</p>"
                        }
                    }

                val langCode = when {
                    project.targetLanguage.contains("zh", ignoreCase = true) || project.targetLanguage.contains("中文") -> "zh"
                    project.targetLanguage.contains("en", ignoreCase = true) || project.targetLanguage.contains("English", ignoreCase = true) -> "en"
                    project.targetLanguage.contains("ja", ignoreCase = true) -> "ja"
                    project.targetLanguage.contains("ko", ignoreCase = true) -> "ko"
                    project.targetLanguage.contains("fr", ignoreCase = true) -> "fr"
                    project.targetLanguage.contains("de", ignoreCase = true) -> "de"
                    project.targetLanguage.contains("es", ignoreCase = true) -> "es"
                    project.targetLanguage.contains("ru", ignoreCase = true) -> "ru"
                    else -> "zh"
                }

                val chapterXhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$langCode">
<head>
  <title>${escapeXml(chapter.title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h1>${escapeXml(chapter.title)}</h1>
$paragraphsHtml
</body>
</html>"""
                addZipEntry(zos, "OEBPS/$fileHref", chapterXhtml.toByteArray(Charsets.UTF_8))
            }

            // 5. OEBPS/toc.ncx
            val ncxXml = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="urn:uuid:novel-trans-${project.id}"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${escapeXml(project.title)}</text></docTitle>
  <docAuthor><text>${escapeXml(project.author)}</text></docAuthor>
  <navMap>
$navPoints
  </navMap>
</ncx>"""
            addZipEntry(zos, "OEBPS/toc.ncx", ncxXml.toByteArray(Charsets.UTF_8))

            val langCode = when {
                project.targetLanguage.contains("zh", ignoreCase = true) || project.targetLanguage.contains("中文") -> "zh"
                project.targetLanguage.contains("en", ignoreCase = true) || project.targetLanguage.contains("English", ignoreCase = true) -> "en"
                project.targetLanguage.contains("ja", ignoreCase = true) -> "ja"
                project.targetLanguage.contains("ko", ignoreCase = true) -> "ko"
                else -> "zh"
            }

            // 6. OEBPS/content.opf
            val opfXml = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookID" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${escapeXml(project.title)}</dc:title>
    <dc:creator>${escapeXml(project.author)}</dc:creator>
    <dc:language>$langCode</dc:language>
    <dc:identifier id="BookID">urn:uuid:novel-trans-${project.id}</dc:identifier>
  </metadata>
  <manifest>
$manifestItems
  </manifest>
  <spine toc="ncx">
$spineItems
  </spine>
</package>"""
            addZipEntry(zos, "OEBPS/content.opf", opfXml.toByteArray(Charsets.UTF_8))
        }

        return exportFile
    }

    private fun addZipEntry(zos: ZipOutputStream, path: String, data: ByteArray) {
        val entry = ZipEntry(path)
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
