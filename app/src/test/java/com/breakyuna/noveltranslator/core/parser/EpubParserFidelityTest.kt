package com.breakyuna.noveltranslator.core.parser

import kotlin.io.path.createTempDirectory
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EPUB 端到端保真度：章节切分、目录标题、图片标记、字符集与预算容错。
 * 用 ZipOutputStream 现场构造 EPUB 字节，不依赖外部样本文件。
 */
class EpubParserFidelityTest {

    private fun writeEpub(root: File, build: (ZipOutputStream) -> Unit): File {
        val epub = File(root, "book.epub")
        ZipOutputStream(epub.outputStream()).use { zip -> build(zip) }
        return epub
    }

    private fun ZipOutputStream.addText(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.addBytes(path: String, value: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(value)
        closeEntry()
    }

    private val containerXml = """<container><rootfile full-path="OEBPS/content.opf"/></container>"""

    @Test
    fun standardEpubKeepsSpineOrderTitlesParagraphsAndImageMarkers() {
        val root = createTempDirectory(prefix = "epub_fidelity_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <metadata>
                      <dc:title>保真之书</dc:title>
                      <dc:creator>作者甲</dc:creator>
                    </metadata>
                    <manifest>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                      <item id="cover" href="Images/cover.png" media-type="image/png" properties="cover-image"/>
                    </manifest>
                    <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/Text/c1.xhtml",
                    """
                    <html><body>
                    <h1>第一章 觉醒</h1>
                    <p>第一段正文。</p>
                    <img src="../Images/cover.png"/>
                    <p>第二段正文。</p>
                    </body></html>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/Text/c2.xhtml",
                    "<html><body><h1>第二章 远行</h1><p>新的旅程。</p></body></html>"
                )
                zip.addBytes("OEBPS/Images/cover.png", byteArrayOf(1, 2, 3, 4))
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals("保真之书", book.title)
            assertEquals("作者甲", book.author)
            assertEquals(listOf(1, 2), book.chapters.map { it.index })
            assertEquals(listOf("第一章 觉醒", "第二章 远行"), book.chapters.map { it.title })
            // 标题不再重复进正文；图片标记独立成段（导入契约不变）。
            assertEquals("第一段正文。", book.chapters[0].content.lines().first())
            assertTrue(book.chapters[0].content.contains("\n\n[IMG:"))
            assertTrue(book.chapters[0].content.endsWith("第二段正文。"))
            assertFalse(book.chapters[0].content.contains("第一章 觉醒"))
            assertEquals("新的旅程。", book.chapters[1].content)

            assertEquals(1, book.extractedImages.size)
            assertTrue(book.coverFileName!!.endsWith("_cover.png"))
            assertEquals(book.coverFileName, book.extractedImages.single().fileName)
            assertEquals(4, book.extractedImages.single().bytes.size)
            assertTrue(book.chapters.sumOf { it.wordCount } > 0)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun singleFileWithMultipleHeadingsSplitsByHeading() {
        val root = createTempDirectory(prefix = "epub_split_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>合集</dc:title></metadata>
                    <manifest>
                      <item id="all" href="Text/all.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="all"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/Text/all.xhtml",
                    """
                    <html><body>
                    <h2>第一章 觉醒</h2><p>正文一。</p>
                    <h2>第二章 远行</h2><p>正文二。</p>
                    <h2>第三章 归途</h2><p>正文三。</p>
                    </body></html>
                    """.trimIndent()
                )
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals(listOf(1, 2, 3), book.chapters.map { it.index })
            assertEquals(
                listOf("第一章 觉醒", "第二章 远行", "第三章 归途"),
                book.chapters.map { it.title }
            )
            assertEquals(listOf("正文一。", "正文二。", "正文三。"), book.chapters.map { it.content })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun contentBeforeFirstHeadingStaysWithFirstChapter() {
        val root = createTempDirectory(prefix = "epub_lead_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>卷首</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/Text/c1.xhtml",
                    "<html><body><p>卷首语。</p><h1>第一章 觉醒</h1><p>正文。</p></body></html>"
                )
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals(1, book.chapters.size)
            assertEquals("第一章 觉醒", book.chapters.single().title)
            assertTrue(book.chapters.single().content.startsWith("卷首语。"))
            assertTrue(book.chapters.single().content.endsWith("正文。"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun headingOnlyDocumentsAreSkippedWithoutProducingEmptyChapters() {
        val root = createTempDirectory(prefix = "epub_empty_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>空标题</dc:title></metadata>
                    <manifest>
                      <item id="empty" href="Text/empty.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="empty"/><itemref idref="c1"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText("OEBPS/Text/empty.xhtml", "<html><body><h1>只有标题</h1></body></html>")
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body><h1>第一章</h1><p>正文。</p></body></html>")
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals(1, book.chapters.size)
            assertEquals("第一章", book.chapters.single().title)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun navTocTitlesTakePrecedenceOverGenericBodyHeadings() {
        val root = createTempDirectory(prefix = "epub_nav_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>目录优先</dc:title></metadata>
                    <manifest>
                      <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/nav.xhtml",
                    """
                    <html><body><nav epub:type="toc"><ol>
                      <li><a href="Text/c1.xhtml">第一章 觉醒之日</a></li>
                      <li><a href="Text/c2.xhtml">第二章 远方的风</a></li>
                    </ol></nav></body></html>
                    """.trimIndent()
                )
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body><h1>Chapter 1</h1><p>正文一。</p></body></html>")
                zip.addText("OEBPS/Text/c2.xhtml", "<html><body><h1>Chapter 2</h1><p>正文二。</p></body></html>")
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals(listOf("第一章 觉醒之日", "第二章 远方的风"), book.chapters.map { it.title })
            assertEquals(listOf("正文一。", "正文二。"), book.chapters.map { it.content })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fragmentAnchorsMatchHeadingsInsideCombinedFile() {
        val root = createTempDirectory(prefix = "epub_frag_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>锚点匹配</dc:title></metadata>
                    <manifest>
                      <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                      <item id="all" href="Text/all.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="all"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/nav.xhtml",
                    """
                    <html><body><nav epub:type="toc"><ol>
                      <li><a href="Text/all.xhtml#c1">第一章 觉醒之日</a></li>
                      <li><a href="Text/all.xhtml#c2">第二章 远方的风</a></li>
                    </ol></nav></body></html>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/Text/all.xhtml",
                    """
                    <html><body>
                    <h1 id="c1">第一章</h1><p>正文一。</p>
                    <h1 id="c2">第二章</h1><p>正文二。</p>
                    </body></html>
                    """.trimIndent()
                )
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals(listOf("第一章 觉醒之日", "第二章 远方的风"), book.chapters.map { it.title })
            assertEquals(listOf("正文一。", "正文二。"), book.chapters.map { it.content })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun ncxTitlesAreUsedWhenNavDocumentIsMissing() {
        val root = createTempDirectory(prefix = "epub_ncx_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>EPUB2</dc:title></metadata>
                    <manifest>
                      <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/toc.ncx",
                    """
                    <ncx><navMap>
                      <navPoint id="n1"><navLabel><text>第一章 旧版目录</text></navLabel>
                        <content src="Text/c1.xhtml"/></navPoint>
                      <navPoint id="n2"><navLabel><text>第二章 旧版目录</text></navLabel>
                        <content src="Text/c2.xhtml"/></navPoint>
                    </navMap></ncx>
                    """.trimIndent()
                )
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body><h1>1</h1><p>正文一。</p></body></html>")
                zip.addText("OEBPS/Text/c2.xhtml", "<html><body><h1>2</h1><p>正文二。</p></body></html>")
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals(listOf("第一章 旧版目录", "第二章 旧版目录"), book.chapters.map { it.title })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rubyEntitiesTablesAndBreaksSurviveIntoChapterText() {
        val root = createTempDirectory(prefix = "epub_rich_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>富文本</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText(
                    "OEBPS/Text/c1.xhtml",
                    """
                    <html><body>
                    <h1>第一章</h1>
                    <p><ruby>漢字<rt>かんじ</rt></ruby>与 Tom &amp; Jerry &lt;b&gt;</p>
                    <p>第一行<br>第二行</p>
                    <table><tr><td>单元格一</td><td>单元格二</td></tr></table>
                    <p>全角${'\u3000'}空格与制表符保留</p>
                    </body></html>
                    """.trimIndent()
                )
            }

            val content = EpubParser.parseEpubFile(epub).chapters.single().content

            assertTrue(content.contains("漢字与 Tom & Jerry <b>"))
            assertFalse(content.contains("かんじ"))
            assertTrue(content.contains("第一行\n第二行"))
            assertTrue(content.contains("单元格一"))
            assertTrue(content.contains("单元格二"))
            assertTrue(content.contains("全角\u3000空格与制表符保留"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun gbkEncodedDocumentIsDecodedInsteadOfProducingMojibake() {
        val root = createTempDirectory(prefix = "epub_gbk_").toFile()
        try {
            val gbk = Charset.forName("GB18030")
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>GBK 之书</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.putNextEntry(ZipEntry("OEBPS/Text/c1.xhtml"))
                zip.write(
                    "<html><body><h1>中文章节</h1><p>这是正文。</p></body></html>".toByteArray(gbk)
                )
                zip.closeEntry()
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals("中文章节", book.chapters.single().title)
            assertEquals("这是正文。", book.chapters.single().content)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptySpineFallsBackToManifestHtmlDocuments() {
        val root = createTempDirectory(prefix = "epub_nospine_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>无 spine</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                      <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    </package>
                    """.trimIndent()
                )
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body><h1>第一章</h1><p>正文一。</p></body></html>")
                zip.addText("OEBPS/Text/c2.xhtml", "<html><body><h1>第二章</h1><p>正文二。</p></body></html>")
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals(listOf("第一章", "第二章"), book.chapters.map { it.title })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingOpfThrowsReadableError() {
        val root = createTempDirectory(prefix = "epub_noopf_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body><h1>第一章</h1><p>正文。</p></body></html>")
            }

            val error = runCatching { EpubParser.parseEpubFile(epub) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message!!.contains("container.xml"))
            assertTrue(error?.message!!.contains(".opf"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedContainerWithoutAnyOpfThrows() {
        val root = createTempDirectory(prefix = "epub_badcontainer_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", "<container><rootfile/></container>")
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body><h1>第一章</h1><p>正文。</p></body></html>")
            }

            val error = runCatching { EpubParser.parseEpubFile(epub) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message!!.contains("container.xml"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedContainerWithFallbackOpfStillParses() {
        val root = createTempDirectory(prefix = "epub_recover_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", "this is not xml at all <<<")
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>恢复</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body><h1>第一章</h1><p>正文。</p></body></html>")
            }

            val book = EpubParser.parseEpubFile(epub)

            assertEquals("恢复", book.title)
            assertEquals(listOf("第一章"), book.chapters.map { it.title })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedZipEntryThrowsBudgetError() {
        val root = createTempDirectory(prefix = "epub_bomb_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>炸弹</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="Text/huge.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.putNextEntry(ZipEntry("OEBPS/Text/huge.xhtml"))
                zip.write(ByteArray(26 * 1024 * 1024))
                zip.closeEntry()
            }

            val error = runCatching { EpubParser.parseEpubFile(epub) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message!!.contains("25 MB"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tooManyZipEntriesThrows() {
        val root = createTempDirectory(prefix = "epub_entries_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                for (index in 0 until 10_001) {
                    zip.addText("filler/entry_$index.txt", "x")
                }
            }

            val error = runCatching { EpubParser.parseEpubFile(epub) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message!!.contains("条目"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unreadableEpubThrowsInsteadOfReturningEmptyBook() {
        val root = createTempDirectory(prefix = "epub_none_").toFile()
        try {
            val epub = writeEpub(root) { zip ->
                zip.addText("META-INF/container.xml", containerXml)
                zip.addText(
                    "OEBPS/content.opf",
                    """
                    <package><metadata><dc:title>空书</dc:title></metadata>
                    <manifest>
                      <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    </manifest>
                    <spine><itemref idref="c1"/></spine>
                    </package>
                    """.trimIndent()
                )
                zip.addText("OEBPS/Text/c1.xhtml", "<html><body>   </body></html>")
            }

            val error = runCatching { EpubParser.parseEpubFile(epub) }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message!!.contains("未找到可阅读的章节"))
        } finally {
            root.deleteRecursively()
        }
    }
}
