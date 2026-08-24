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
                      <item id="image" href="Images/picture.png" media-type="image/png"/>
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
            assertTrue(File(images, book.extractedImages.first().fileName).isFile)
            assertEquals(0, book.extractedImages.first().bytes.size)

            FileInputStream(epub).use { input ->
                assertEquals("Stream Book", EpubParser.parseEpub(input, images).title)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
