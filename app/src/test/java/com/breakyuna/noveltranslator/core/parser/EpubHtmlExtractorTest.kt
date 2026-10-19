package com.breakyuna.noveltranslator.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** jsoup 块级提取：标题、行内元素、ruby、实体、表格、br、图片与容错。 */
class EpubHtmlExtractorTest {

    @Test
    fun extractsHeadingsParagraphsAndImagesAsSeparateBlocks() {
        val document = EpubHtmlExtractor.extract(
            html = "<html><body><h1 id=\"c1\">第一章 觉醒</h1><p>第一段</p>" +
                "<img src=\"../Images/a.png\"/><p>第二段</p></body></html>",
            htmlDir = "OEBPS/Text/",
            imageNamesByPath = mapOf("OEBPS/Images/a.png" to "hash_a.png")
        )

        assertEquals(4, document.blocks.size)
        assertEquals(1, document.blocks[0].headingLevel)
        assertEquals("c1", document.blocks[0].headingId)
        assertEquals("第一章 觉醒", document.blocks[0].text)
        assertNull(document.blocks[1].headingLevel)
        assertEquals("第一段", document.blocks[1].text)
        assertEquals("[IMG:hash_a.png]", document.blocks[2].text)
        assertEquals("第二段", document.blocks[3].text)
        assertFalse(document.hasTocNavigation)
    }

    @Test
    fun inlineElementsOnlyContributeText() {
        val html = "<p>普通<em>斜体</em><strong>粗体</strong><span>span</span>" +
            "<a href=\"x.xhtml\">链接</a><code>code</code></p>"

        assertEquals("普通斜体粗体span链接code", EpubHtmlExtractor.extract(html).blocks.single().text)
    }

    @Test
    fun dropsRubyReadingsByDefaultAndKeepsThemWhenConfigured() {
        val html = "<p><ruby>漢字<rt>かんじ</rt></ruby>后续</p>"

        assertEquals("漢字后续", EpubHtmlExtractor.extract(html).blocks.single().text)

        val kept = EpubHtmlExtractor.extract(html, rubyKeepReadings = true).blocks.single().text
        assertEquals("漢字（かんじ）后续", kept)
    }

    @Test
    fun dropsRubyParenthesesAndAnnotations() {
        val html = "<p><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp><rtc>annotation</rtc></ruby></p>"

        assertEquals("漢字", EpubHtmlExtractor.extract(html).blocks.single().text)
    }

    @Test
    fun decodesHtmlEntitiesAndNonBreakingSpaces() {
        val html = "<p>Tom &amp; Jerry &lt;tag&gt; &#39;quoted&#39; &hellip;&nbsp;end</p>"

        assertEquals("Tom & Jerry <tag> 'quoted' … end", EpubHtmlExtractor.extract(html).blocks.single().text)
    }

    @Test
    fun keepsIdeographicSpacesAndTabsInsideBlockText() {
        val html = "<p>你好\u3000世界\t制表符</p>"

        assertEquals("你好\u3000世界\t制表符", EpubHtmlExtractor.extract(html).blocks.single().text)
    }

    @Test
    fun keepsBrAsSoftLineBreakInsideParagraph() {
        assertEquals(
            "第一行\n第二行",
            EpubHtmlExtractor.extract("<p>第一行<br>第二行</p>").blocks.single().text
        )
        assertEquals(
            "第一段\n\n第二段",
            EpubHtmlExtractor.extract("<p>第一段<br><br>第二段</p>").blocks.single().text
        )
    }

    @Test
    fun linearizesTablesPerCellAndListsPerItem() {
        val html = "<table><tr><td>单元格一</td><td>单元格二</td></tr></table>" +
            "<ul><li>项目一</li><li>项目二</li></ul>"

        assertEquals(
            listOf("单元格一", "单元格二", "项目一", "项目二"),
            EpubHtmlExtractor.extract(html).blocks.map { it.text }
        )
    }

    @Test
    fun blockquoteAndNestedBlocksBecomeSeparateBlocks() {
        val html = "<blockquote><p>引用一</p><p>引用二</p></blockquote><div>块</div>"

        assertEquals(listOf("引用一", "引用二", "块"), EpubHtmlExtractor.extract(html).blocks.map { it.text })
    }

    @Test
    fun skipsHeadScriptStyleAndTitle() {
        val html = "<html><head><title>文档标题</title><style>p{color:red}</style>" +
            "<script>var a=1;</script></head><body><p>正文</p></body></html>"

        assertEquals(listOf("正文"), EpubHtmlExtractor.extract(html).blocks.map { it.text })
    }

    @Test
    fun recoversMalformedHtml() {
        val html = "<html><body><p>未闭合段落<P>第二段<DIV>块<SPAN>行内"

        assertEquals(
            listOf("未闭合段落", "第二段", "块行内"),
            EpubHtmlExtractor.extract(html).blocks.map { it.text }
        )
    }

    @Test
    fun keepsCdataWrappedText() {
        val html = "<p><![CDATA[被包裹的正文 & 符号]]></p>"

        assertEquals("被包裹的正文 & 符号", EpubHtmlExtractor.extract(html).blocks.single().text)
    }

    @Test
    fun externalImagesKeepBasenameMarkersWhileDataUrisAreSkipped() {
        val external = "<p>前</p><img src=\"https://example.com/dir/cover.png\"/>"
        assertEquals(
            listOf("前", "[IMG:cover.png]"),
            EpubHtmlExtractor.extract(external).blocks.map { it.text }
        )

        val dataUri = "<p>前</p><img src=\"data:image/png;base64,AAAA\"/>"
        assertEquals(listOf("前"), EpubHtmlExtractor.extract(dataUri).blocks.map { it.text })
    }

    @Test
    fun inlineSvgImageUsesXlinkHref() {
        val html = "<p>前</p><svg><image xlink:href=\"../Images/fig1.png\" width=\"10\"/></svg>"

        val document = EpubHtmlExtractor.extract(
            html = html,
            htmlDir = "OEBPS/Text/",
            imageNamesByPath = mapOf("OEBPS/Images/fig1.png" to "hash_fig1.png")
        )
        assertTrue(document.blocks.map { it.text }.contains("[IMG:hash_fig1.png]"))
    }

    @Test
    fun detectsTocNavigationFromNavAttributes() {
        val declared = "<html><body><nav epub:type=\"toc\"><ol>" +
            "<li><a href=\"c1.xhtml\">第一章</a></li></ol></nav></body></html>"
        assertTrue(EpubHtmlExtractor.extract(declared).hasTocNavigation)

        val plain = "<html><body><nav><ol>" +
            "<li><a href=\"c1.xhtml\">第一章</a></li></ol></nav></body></html>"
        assertFalse(EpubHtmlExtractor.extract(plain).hasTocNavigation)
    }
}
