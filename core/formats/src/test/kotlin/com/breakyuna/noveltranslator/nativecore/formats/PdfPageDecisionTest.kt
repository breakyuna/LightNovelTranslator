package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class PdfPageDecisionTest {
    private val validDigest = "0123456789abcdef".repeat(4)

    @Test fun encodedDecisionRoundTripsWithPageIdentity() {
        val unit = unit(page = JsonPrimitive(3), decision = JsonPrimitive("translate"), tag = "page:3")
        val binding = PdfPageCodec.decode(unit, 7)
        assertEquals(3, binding.page)
        assertEquals(PdfPageDecision.TRANSLATE, binding.decision)
        assertEquals("page:3", PdfPageCodec.tag(3))
    }

    @Test fun decodeAcceptsAllThreeDecisions() {
        for (decision in PdfPageDecision.entries) {
            val binding = PdfPageCodec.decode(unit(decision = JsonPrimitive(decision.wire)), 4)
            assertEquals(decision, binding.decision)
        }
    }

    @Test fun textPagesDefaultToTranslateAndEmptyPagesToKeep() {
        assertEquals(PdfPageDecision.TRANSLATE, PdfPageCodec.defaultDecision("Chapter one"))
        assertEquals(PdfPageDecision.KEEP, PdfPageCodec.defaultDecision(""))
        assertEquals(PdfPageDecision.KEEP, PdfPageCodec.defaultDecision("  \n\t "))
    }

    @Test fun invalidPageDecisionBindingsAreRejected() {
        assertFailure(PdfFormatException.Reason.BINDING) {
            PdfPageCodec.decode(unit(decision = JsonPrimitive("drop")), 4)
        }
        assertFailure(PdfFormatException.Reason.BINDING) {
            PdfPageCodec.decode(unit(page = JsonPrimitive(0), tag = "page:0"), 4)
        }
        assertFailure(PdfFormatException.Reason.BINDING) {
            PdfPageCodec.decode(unit(page = JsonPrimitive(5), tag = "page:5"), 4)
        }
        assertFailure(PdfFormatException.Reason.BINDING) {
            PdfPageCodec.decode(unit(digestValue = "ABC"), 4)
        }
        assertFailure(PdfFormatException.Reason.BINDING) {
            PdfPageCodec.decode(unit(page = JsonPrimitive(1), tag = "page:2"), 4)
        }
    }

    @Test fun extraKeysAreIgnoredForForwardCompatibility() {
        val extra = buildJsonObject {
            put("v", 1)
            put("page", 2)
            put("decision", "omit")
            put("digest", validDigest)
            put("future", "value")
        }.toString()
        val binding = PdfPageCodec.decode(
            FormatUnit(row = 2, source = "ignored", tag = "page:2", extraField = extra),
            3,
        )
        assertEquals(PdfPageDecision.OMIT, binding.decision)
    }

    private fun unit(
        page: JsonElement? = JsonPrimitive(1),
        decision: JsonElement? = JsonPrimitive("translate"),
        digestValue: String? = validDigest,
        tag: String = "page:1",
    ): FormatUnit {
        val pageValue = page
        val decisionValue = decision
        val digestValueResolved = digestValue
        val extra = buildJsonObject {
            put("v", 1)
            if (pageValue != null) put("page", pageValue)
            if (decisionValue != null) put("decision", decisionValue)
            if (digestValueResolved != null) put("digest", digestValueResolved)
        }.toString()
        return FormatUnit(row = 1, source = "source", tag = tag, extraField = extra)
    }

    private fun assertFailure(reason: PdfFormatException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Expected PdfFormatException($reason)")
        } catch (failure: PdfFormatException) {
            assertEquals(reason, failure.reason)
        }
    }
}
