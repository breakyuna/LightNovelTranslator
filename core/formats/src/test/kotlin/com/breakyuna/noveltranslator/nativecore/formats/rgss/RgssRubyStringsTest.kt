package com.breakyuna.noveltranslator.nativecore.formats.rgss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RgssRubyStringsTest {
    @Test fun findsOrdinaryStringsAndDecodesCommonEscapes() {
        val source = """
            # "not a literal"
            a = 'single \' quote'
            b = "double \"quote\" \n tab\t \u3042"
            c = :"not text"
            d = "hello"
        """.trimIndent()
        val result = RgssRubyStrings.scan(source)
        assertEquals(3, result.literals.size)
        assertEquals(1, result.skippedQuotedSymbols)
        assertEquals("single ' quote", result.literals[0].value)
        assertEquals("double \"quote\" \n tab\t あ", result.literals[1].value)
        assertEquals("hello", result.literals[2].value)
    }

    @Test fun rewriteChangesOnlyTheBoundLiteral() {
        val source = "a = 'keep'\nb = \"hello\"\nc = \"keep\"\n"
        val literal = RgssRubyStrings.scan(source).literals.first { it.value == "hello" }
        val rewritten = RgssRubyStrings.rewrite(
            source,
            listOf(
                RubyReplacement(
                    literal.start,
                    literal.end,
                    literal.quote,
                    literal.value,
                    "こんにちは",
                    RgssRubyStrings.sha256Hex(literal.raw),
                ),
            ),
        )
        assertEquals("a = 'keep'\nb = \"こんにちは\"\nc = \"keep\"\n", rewritten)
        assertEquals("こんにちは", RgssRubyStrings.scan(rewritten).literals[1].value)
    }

    @Test fun encodedDoubleQuotedTextCannotBecomeInterpolation() {
        val value = "he said \"hi\"\n#{not code} \\ end"
        val encoded = RgssRubyStrings.encodeLiteral(value, '"')
        val parsed = RgssRubyStrings.scan(encoded).literals.single()
        assertEquals(value, parsed.value)
        assertTrue(encoded.contains("\\#{"))
    }

    @Test fun encodedSingleQuotedTextKeepsSingleQuoteSemantics() {
        val value = "it's a \\ path"
        val encoded = RgssRubyStrings.encodeLiteral(value, '\'')
        val parsed = RgssRubyStrings.scan(encoded).literals.single()
        assertEquals(value, parsed.value)
    }

    @Test fun quotedSymbolsAndCommentsAreSkipped() {
        val source = """
            =begin
            "block comment"
            =end
            x = :"symbol"
            y = 1 # "line comment"
            z = "real"
        """.trimIndent()
        val result = RgssRubyStrings.scan(source)
        assertEquals(1, result.literals.size)
        assertEquals("real", result.literals[0].value)
        assertEquals(1, result.skippedQuotedSymbols)
    }

    @Test fun divisionIsNotRejectedAsRegex() {
        val result = RgssRubyStrings.scan("a = 10 / 2\nb = \"ok\"\n")
        assertEquals(1, result.literals.size)
        assertEquals("ok", result.literals[0].value)
    }

    @Test fun unicodeReplacementKeepsSurrogatePairsIntact() {
        val source = "x = \"😀\"\n"
        val literal = RgssRubyStrings.scan(source).literals.single()
        val rewritten = RgssRubyStrings.rewrite(
            source,
            listOf(RubyReplacement(literal.start, literal.end, literal.quote, literal.value, "😀😀")),
        )
        assertEquals("😀😀", RgssRubyStrings.scan(rewritten).literals.single().value)
    }

    @Test fun rejectsInterpolationHeredocPercentAndRegex() {
        assertRejected("\"hi #{name}\"", RubySourceException.Reason.INTERPOLATION)
        assertRejected("\"hi #@name\"", RubySourceException.Reason.INTERPOLATION)
        assertRejected("x = <<~EOS\nhi\nEOS\n", RubySourceException.Reason.HEREDOC)
        assertRejected("x = %q(hello)", RubySourceException.Reason.PERCENT_LITERAL)
        assertRejected("x = /\"hello\"/", RubySourceException.Reason.REGEX)
    }

    @Test fun rejectsUnterminatedAndUnknownEscapes() {
        assertRejected("x = \"hello", RubySourceException.Reason.UNTERMINATED_STRING)
        assertRejected("x = \"\\q\"", RubySourceException.Reason.ESCAPE)
        assertRejected("=begin\nno end", RubySourceException.Reason.UNTERMINATED_COMMENT)
    }

    @Test fun bindingMismatchAndOverlapAreRejected() {
        val source = "x = \"hello\"\n"
        val literal = RgssRubyStrings.scan(source).literals.single()
        assertEquals(
            RubySourceException.Reason.BINDING,
            failure {
                RgssRubyStrings.rewrite(
                    source,
                    listOf(RubyReplacement(literal.start, literal.end, literal.quote, "wrong", "x")),
                )
            }.reason,
        )
        assertEquals(
            RubySourceException.Reason.BINDING,
            failure {
                RgssRubyStrings.rewrite(
                    source,
                    listOf(
                        RubyReplacement(literal.start, literal.end, literal.quote, literal.value, "a"),
                        RubyReplacement(literal.start, literal.end, literal.quote, literal.value, "b"),
                    ),
                )
            }.reason,
        )
    }

    private fun assertRejected(source: String, reason: RubySourceException.Reason) {
        assertEquals(reason, failure { RgssRubyStrings.scan(source) }.reason)
    }

    private fun failure(block: () -> Unit): RubySourceException {
        try {
            block()
        } catch (failure: RubySourceException) {
            return failure
        }
        fail("Expected RubySourceException")
        throw AssertionError("unreachable")
    }
}
