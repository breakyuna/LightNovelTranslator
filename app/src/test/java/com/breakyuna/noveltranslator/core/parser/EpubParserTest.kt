package com.breakyuna.noveltranslator.core.parser

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubParserTest {
    @Test
    fun fileParserReadsSpineOrderAndStreamsImagesToDisk() {
        val root = createTempDir(prefix = "epub_parser_test_")
        try {
            val epub = File(root, "book.epub")
            ZipOutputStream(epub.outputStream()).use { zip ->
                fun add(path: String, value: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                fun addBytes(path: String, value: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value)
                    zip.closeEntry()
                }
                add("META-INF/container.xml", """
                    <container><rootfile full-path="OEBPS/content.opf"/></container>
                """.trimIndent())
                add("OEBPS/content.opf", """
                    <package><metadata><dc:title>Stream Book</dc:title><dc:creator>Author</dc:creator></metadata>
                    <manifest>
                      <item id="second" href="Text/second.xhtml" media-type="application/xhtml+xml"/>
                      <item id="first" href="Text/first.xhtml" media-type="application/xhtml+xml"/>
                      <item id="image" href="Images/picture.png" media-type="image/png" properties="cover-image"/>
                    </manifest>
                    <spine><itemref idref="first"/><itemref idref="second"/></spine>
                    </package>
                """.trimIndent())
                add("OEBPS/Text/second.xhtml", "<html><body><h1>Second</h1><p>two</p></body></html>")
                add("OEBPS/Text/first.xhtml", "<html><body><h1>First</h1><img src=\"../Images/picture.png\"/><p>one</p></body></html>")
                addBytes("OEBPS/Images/picture.png", byteArrayOf(1, 2, 3, 4))
            }

            val images = File(root, "images")
            val book = EpubParser.parseEpubFile(epub, images)

            assertEquals("Stream Book", book.title)
            assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
            assertTrue(book.chapters.first().content.contains("[IMG:"))
            assertEquals(1, book.extractedImages.size)
            assertEquals(book.extractedImages.first().fileName, book.coverFileName)
            assertTrue(File(images, book.extractedImages.first().fileName).isFile)
            assertEquals(0, book.extractedImages.first().bytes.size)

            FileInputStream(epub).use { input ->
                assertEquals("Stream Book", EpubParser.parseEpub(input, images).title)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun optionalTocCroppingSkipsNavigationDocuments() {
        val root = createTempDir(prefix = "epub_toc_test_")
        try {
            val epub = File(root, "book.epub")
            ZipOutputStream(epub.outputStream()).use { zip ->
                fun add(path: String, value: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                add("META-INF/container.xml", """
                    <container><rootfile full-path="OEBPS/content.opf"/></container>
                """.trimIndent())
                add("OEBPS/content.opf", """
                    <package><metadata><dc:title>TOC Book</dc:title></metadata>
                    <manifest>
                      <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml"/>
                      <item id="first" href="Text/first.xhtml" media-type="application/xhtml+xml"/>
                      <item id="second" href="Text/second.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="nav"/><itemref idref="first"/><itemref idref="second"/></spine>
                    </package>
                """.trimIndent())
                add("OEBPS/nav.xhtml", """
                    <html><body><nav epub:type="toc"><h1>Contents</h1>
                    <ol><li><a href="Text/first.xhtml">First</a></li><li><a href="Text/second.xhtml">Second</a></li></ol>
                    </nav></body></html>
                """.trimIndent())
                add("OEBPS/Text/first.xhtml", "<html><body><h1>First</h1><p>one</p></body></html>")
                add("OEBPS/Text/second.xhtml", "<html><body><h1>Second</h1><p>two</p></body></html>")
            }

            val book = EpubParser.parseEpubFile(epub, cropTableOfContents = true)

            assertEquals(listOf("First", "Second"), book.chapters.map { it.title })
        } finally {
            root.deleteRecursively()
        }
    }
}
