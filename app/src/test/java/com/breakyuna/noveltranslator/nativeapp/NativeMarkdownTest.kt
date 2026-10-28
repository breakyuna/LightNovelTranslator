package com.breakyuna.noveltranslator.nativeapp

import org.junit.Assert.*
import org.junit.Test

class NativeMarkdownTest {
    @Test fun preservesCodeTablesLinksAndNestedFormatting() {
        val blocks = parseNativeMarkdown("# Title\n\n**bold** and [link](https://example.org)\n\n```kt\nval x = 1\n```\n\n| A | B |\n|---|---|\n| a | b |")
        assertEquals("heading", blocks[0].kind)
        assertEquals("bold and link", blocks[1].text.text)
        assertEquals("https://example.org", blocks[1].text.getStringAnnotations("url", 0, blocks[1].text.length).single().item)
        assertEquals("val x = 1", blocks[2].text.text)
        assertEquals("table", blocks[3].kind)
        assertEquals(2, blocks[3].children.size)
    }

    @Test fun incompleteFenceAndMultibyteTextRemainReadable() {
        val blocks = parseNativeMarkdown("你好🙂\n\n```\npartial **literal")
        assertEquals("你好🙂", blocks[0].text.text)
        assertEquals("code", blocks[1].kind)
        assertEquals("partial **literal", blocks[1].text.text)
    }

    @Test fun rawHtmlStaysLiteralAndOrderedListStartsAtItsActualNumber() {
        val blocks = parseNativeMarkdown("<script>alert(1)</script>\n\n3. third\n4. fourth")
        assertTrue(blocks[0].text.text.contains("<script>"))
        assertEquals(listOf("3.", "4."), blocks[1].children.map { it.text.text })
    }
}
