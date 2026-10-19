package com.breakyuna.noveltranslator.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** EPUB3 nav 与 EPUB2 NCX 目录解析。 */
class EpubTocParserTest {

    @Test
    fun parsesNavEntriesInDocumentOrderWithFragments() {
        val html = """
            <html><body><nav epub:type="toc">
            <ol>
              <li><a href="Text/c1.xhtml">第一章 觉醒</a></li>
              <li><a href="Text/c2.xhtml#sec2">第二章 远行</a></li>
            </ol>
            </nav></body></html>
        """.trimIndent()

        val entries = EpubTocParser.parseNav(html, "OEBPS/")

        assertEquals(2, entries.size)
        assertEquals("第一章 觉醒", entries[0].title)
        assertEquals("OEBPS/Text/c1.xhtml", entries[0].docPath)
        assertNull(entries[0].fragment)
        assertEquals("第二章 远行", entries[1].title)
        assertEquals("OEBPS/Text/c2.xhtml", entries[1].docPath)
        assertEquals("sec2", entries[1].fragment)
    }

    @Test
    fun navWithoutTocTypeFallsBackToAllAnchors() {
        val html = """
            <html><body>
            <h1>目录</h1>
            <p><a href="c1.xhtml">第一章</a> <a href="c2.xhtml">第二章</a></p>
            </body></html>
        """.trimIndent()

        val entries = EpubTocParser.parseNav(html, "OEBPS/")

        assertEquals(listOf("c1.xhtml", "c2.xhtml"), entries.map { it.docPath })
        assertEquals(listOf("第一章", "第二章"), entries.map { it.title })
    }

    @Test
    fun skipsAnchorsWithoutHrefOrTitle() {
        val html = """
            <html><body><nav epub:type="toc"><ol>
              <li><a href="c1.xhtml">第一章</a></li>
              <li><a>没有链接</a></li>
              <li><a href="c2.xhtml"> </a></li>
            </ol></nav></body></html>
        """.trimIndent()

        val entries = EpubTocParser.parseNav(html, "OEBPS/")

        // 空标题条目退回 href 末段，不会静默丢失阅读顺序。
        assertEquals(listOf("第一章", "c2.xhtml"), entries.map { it.title })
    }

    @Test
    fun ignoresExternalLinksForSpineMatching() {
        val html = """
            <html><body><nav epub:type="toc"><ol>
              <li><a href="https://example.com/book">在线目录</a></li>
              <li><a href="c1.xhtml">第一章</a></li>
            </ol></nav></body></html>
        """.trimIndent()

        val entries = EpubTocParser.parseNav(html, "OEBPS/")

        assertEquals(2, entries.size)
        assertEquals("https://example.com/book", entries[0].docPath)
        assertEquals("OEBPS/c1.xhtml", entries[1].docPath)
    }

    @Test
    fun parsesNcxEntriesWithNavLabelText() {
        val html = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            <head><meta name="dtb:uid" content="urn:uuid:1"/></head>
            <navMap>
              <navPoint id="n1" playOrder="1">
                <navLabel><text>第一章 觉醒</text></navLabel>
                <content src="Text/c1.xhtml"/>
              </navPoint>
              <navPoint id="n2" playOrder="2">
                <navLabel><text>第二章 远行</text></navLabel>
                <content src="Text/c2.xhtml#start"/>
              </navPoint>
            </navMap>
            </ncx>
        """.trimIndent()

        val entries = EpubTocParser.parseNcx(html, "OEBPS/")

        assertEquals(2, entries.size)
        assertEquals("第一章 觉醒", entries[0].title)
        assertEquals("OEBPS/Text/c1.xhtml", entries[0].docPath)
        assertEquals("第二章 远行", entries[1].title)
        assertEquals("start", entries[1].fragment)
    }

    @Test
    fun ncxWithBareAmpersandStillParses() {
        val html = """
            <ncx><navMap>
              <navPoint id="n1"><navLabel><text>Tom & Jerry</text></navLabel>
              <content src="c1.xhtml?a=1&b=2"/></navPoint>
            </navMap></ncx>
        """.trimIndent()

        val entries = EpubTocParser.parseNcx(html, "OEBPS/")

        assertEquals(1, entries.size)
        assertEquals("Tom & Jerry", entries[0].title)
        assertEquals("OEBPS/c1.xhtml", entries[0].docPath)
    }
}
