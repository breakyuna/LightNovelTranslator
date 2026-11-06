package com.breakyuna.noveltranslator.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class PrecedingContextBuilderTest {

    private fun line(sourceId: String, text: String) = PrecedingSourceLine(sourceId, text)

    @Test
    fun takes_source_lines_up_to_the_threshold_and_only_sentence_final_ones() {
        val items = listOf(
            line("book-1", "\u7B2C\u4E00\u53E5\u3002"),
            line("book-1", "\u7B2C\u4E8C\u53E5\u8FD8\u6CA1"),
            line("book-1", "\u7B2C\u4E09\u53E5\u3002"),
            line("book-1", "\u7B2C\u56DB\u53E5\uFF01"),
            line("book-1", "\u76EE\u6807\u884C\u3002")
        )
        val builder = PrecedingContextBuilder(threshold = 5)

        // 从目标行向前取：只接纳以句末标点结尾的行，半句话处停止。
        val preceding = builder.build("book-1", items, startIndex = 5)

        assertEquals(
            listOf("\u7B2C\u4E09\u53E5\u3002", "\u7B2C\u56DB\u53E5\uFF01", "\u76EE\u6807\u884C\u3002"),
            preceding.map { it.text }
        )
    }

    @Test
    fun stops_at_the_threshold_even_when_more_lines_qualify() {
        val items = (1..10).map { line("book-1", "\u7B2C$it\u53E5\u3002") }
        val builder = PrecedingContextBuilder(threshold = 4)

        val preceding = builder.build("book-1", items, startIndex = 10)

        // 只取最近的 4 行，并按文档顺序返回。
        assertEquals(
            listOf("\u7B2C7\u53E5\u3002", "\u7B2C8\u53E5\u3002", "\u7B2C9\u53E5\u3002", "\u7B2C10\u53E5\u3002"),
            preceding.map { it.text }
        )
    }

    @Test
    fun does_not_cross_the_source_boundary() {
        val items = listOf(
            line("book-2", "\u53E6\u4E00\u672C\u4E66\u7684\u7ED3\u5C3E\u3002"),
            line("book-2", "\u7EE7\u7EED\u5199\u3002"),
            line("book-1", "\u672C\u4E66\u7B2C\u4E00\u884C\u3002")
        )
        val builder = PrecedingContextBuilder(threshold = 5)

        // 跨来源即停止：另一本书的结尾不能当成当前章节的上文。
        val preceding = builder.build("book-1", items, startIndex = 3)

        assertEquals(listOf("\u672C\u4E66\u7B2C\u4E00\u884C\u3002"), preceding.map { it.text })
    }

    @Test
    fun stops_at_a_blank_line() {
        val items = listOf(
            line("book-1", "\u7B2C\u4E00\u6BB5\u3002"),
            line("book-1", ""),
            line("book-1", "\u76EE\u6807\u884C\u3002")
        )
        val builder = PrecedingContextBuilder(threshold = 5)

        // 空行中断前文：空白行之前的"第一段。"不会被取到。
        val preceding = builder.build("book-1", items, startIndex = 3)

        assertEquals(listOf("\u76EE\u6807\u884C\u3002"), preceding.map { it.text })
    }

    @Test
    fun a_whitespace_only_line_also_stops_the_search() {
        val items = listOf(
            line("book-1", "\u7B2C\u4E00\u6BB5\u3002"),
            line("book-1", "   "),
            line("book-1", "\u76EE\u6807\u884C\u3002")
        )
        val builder = PrecedingContextBuilder(threshold = 5)

        assertEquals(
            listOf("\u76EE\u6807\u884C\u3002"),
            builder.build("book-1", items, startIndex = 3).map { it.text }
        )
    }

    @Test
    fun zero_threshold_disables_preceding_context() {
        val items = listOf(line("book-1", "\u6709\u6548\u53E5\u5B50\u3002"))
        val builder = PrecedingContextBuilder(threshold = 0)

        assertEquals(emptyList<String>(), builder.build("book-1", items, 1).map { it.text })
        assertEquals(
            emptyList<String>(),
            builder.buildPreceding("book-1", items).map { it.text }
        )
    }

    @Test
    fun custom_end_punctuation_set_is_honoured() {
        val items = listOf(
            line("book-1", "ends with dot."),
            line("book-1", "ends with tilde~")
        )
        val builder = PrecedingContextBuilder(
            threshold = 5,
            endLinePunctuation = setOf('~')
        )

        // 只接纳以 `~` 结尾的行，`.` 结尾的行立即中断搜索。
        val preceding = builder.build("book-1", items, startIndex = 2)

        assertEquals(listOf("ends with tilde~"), preceding.map { it.text })
    }

    @Test
    fun build_preceding_takes_the_lines_immediately_before_the_chunk() {
        val preceding = listOf(
            line("book-1", "\u524D\u4E00\u53E5\u3002"),
            line("book-1", "\u524D\u4E24\u53E5\u3002")
        )
        val builder = PrecedingContextBuilder(threshold = 5)

        assertEquals(
            listOf("\u524D\u4E00\u53E5\u3002", "\u524D\u4E24\u53E5\u3002"),
            builder.buildPreceding("book-1", preceding).map { it.text }
        )
    }

    @Test
    fun start_index_at_or_below_zero_returns_empty() {
        val items = listOf(line("book-1", "\u53E5\u5B50\u3002"))
        val builder = PrecedingContextBuilder(threshold = 5)

        assertEquals(emptyList<String>(), builder.build("book-1", items, 0).map { it.text })
        assertEquals(emptyList<String>(), builder.build("book-1", items, -3).map { it.text })
    }

    @Test
    fun default_threshold_is_disabled_like_the_reference_implementation() {
        assertEquals(0, PrecedingContextBuilder.DEFAULT_THRESHOLD)
        assertEquals(
            setOf('.', '\u3002', '?', '\uFF1F', '!', '\uFF01', '\u2026', '\'', '"', '\u300D', '\u300F'),
            PrecedingContextBuilder.DEFAULT_END_LINE_PUNCTUATION
        )
    }
}
