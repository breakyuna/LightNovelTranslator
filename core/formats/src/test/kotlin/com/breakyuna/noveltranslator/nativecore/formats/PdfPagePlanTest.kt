package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdfPagePlanTest {
    // PDFBox-Android calls android.util.Log, so a real PDF write cannot run in this plain-JVM test
    // source set (no Robolectric dependency in :core:formats). Page order and keep/omit/translate
    // behavior are therefore covered at the plan level, which is the entire decision surface of
    // the writer; PDF byte-level output needs a device/Robolectric test and is reported as open.
    private val digest = "0123456789abcdef".repeat(4)

    @Test fun unlistedPagesKeepAndActionsFollowOriginalOrder() {
        val actions = PdfPagePlan.plan(
            listOf(
                unit(3, "translate", source = "third", translation = "dritter"),
                unit(1, "omit"),
            ),
            pageCount = 4,
            options = FormatWriteOptions(),
        )
        assertEquals(listOf("omit:1", "keep:2", "translate:3", "keep:4"), actions.map(::describe))
        val translated = actions[2] as PdfPageAction.Translate
        assertEquals("dritter", translated.text)
    }

    @Test fun keepCopiesTheOriginalPageEvenWhenATranslationExists() {
        val actions = PdfPagePlan.plan(
            listOf(unit(1, "keep", translation = "ignored")),
            pageCount = 1,
            options = FormatWriteOptions(),
        )
        assertEquals(listOf("keep:1"), actions.map(::describe))
    }

    @Test fun omitDropsThePage() {
        val actions = PdfPagePlan.plan(
            listOf(unit(2, "omit")),
            pageCount = 3,
            options = FormatWriteOptions(),
        )
        assertEquals(listOf("keep:1", "omit:2", "keep:3"), actions.map(::describe))
    }

    @Test fun translateRequiresAProcessedStatus() {
        assertFailure(PdfFormatException.Reason.MISSING_TRANSLATION, page = 1) {
            PdfPagePlan.plan(
                listOf(unit(1, "translate", status = TextItemStatus.NONE)),
                pageCount = 1,
                options = FormatWriteOptions(),
            )
        }
    }

    @Test fun translateRejectsBlankText() {
        assertFailure(PdfFormatException.Reason.MISSING_TRANSLATION, page = 2) {
            PdfPagePlan.plan(
                listOf(unit(2, "translate", translation = " \n\t ")),
                pageCount = 2,
                options = FormatWriteOptions(),
            )
        }
    }

    @Test fun bilingualAppendsTheSourceAsASecondParagraph() {
        val actions = PdfPagePlan.plan(
            listOf(unit(1, "translate", source = "Source", translation = "Target")),
            pageCount = 1,
            options = FormatWriteOptions(bilingual = true),
        )
        assertEquals("Target\n\nSource", (actions.single() as PdfPageAction.Translate).text)
    }

    @Test fun bilingualDeduplicatesIdenticalTextByDefault() {
        val actions = PdfPagePlan.plan(
            listOf(unit(1, "translate", source = "Same", translation = "Same")),
            pageCount = 1,
            options = FormatWriteOptions(bilingual = true),
        )
        assertEquals("Same", (actions.single() as PdfPageAction.Translate).text)
    }

    @Test fun bilingualKeepsBothCopiesWhenDeduplicationIsDisabled() {
        val actions = PdfPagePlan.plan(
            listOf(unit(1, "translate", source = "Same", translation = "Same")),
            pageCount = 1,
            options = FormatWriteOptions(bilingual = true, deduplicateSame = false),
        )
        assertEquals("Same\n\nSame", (actions.single() as PdfPageAction.Translate).text)
    }

    @Test fun duplicatePageUnitsAreRejected() {
        assertFailure(PdfFormatException.Reason.DUPLICATE, page = 1) {
            PdfPagePlan.plan(
                listOf(unit(1, "keep"), unit(1, "omit")),
                pageCount = 1,
                options = FormatWriteOptions(),
            )
        }
    }

    @Test fun tagMustBindToTheClaimedPage() {
        assertFailure(PdfFormatException.Reason.BINDING) {
            PdfPagePlan.plan(
                listOf(unit(1, "keep", tag = "page:2")),
                pageCount = 2,
                options = FormatWriteOptions(),
            )
        }
    }

    @Test fun pageBeyondTheDocumentIsRejected() {
        assertFailure(PdfFormatException.Reason.BINDING) {
            PdfPagePlan.plan(
                listOf(unit(5, "keep")),
                pageCount = 4,
                options = FormatWriteOptions(),
            )
        }
    }

    @Test fun zeroPageDocumentIsCorrupt() {
        assertFailure(PdfFormatException.Reason.CORRUPT) {
            PdfPagePlan.plan(emptyList(), pageCount = 0, options = FormatWriteOptions())
        }
    }

    @Test fun allPagesOmittedProducesOnlyOmitActions() {
        val actions = PdfPagePlan.plan(
            listOf(unit(1, "omit"), unit(2, "omit")),
            pageCount = 2,
            options = FormatWriteOptions(),
        )
        assertEquals(listOf("omit:1", "omit:2"), actions.map(::describe))
        assertTrue(actions.all { it is PdfPageAction.Omit })
    }

    @Test fun requiredCodePointsNormalizeWhitespaceAndSkipLineBreaks() {
        val required = PdfPagePlan.requiredCodePoints(
            listOf(PdfPageAction.Translate(1, "A\u00A0中\nB\tC\uFEFF")),
        )
        assertEquals(setOf('A'.code, ' '.code, '中'.code, 'B'.code, 'C'.code), required)
    }

    @Test fun requiredCodePointsIgnoreKeepAndOmitPages() {
        val required = PdfPagePlan.requiredCodePoints(
            listOf(PdfPageAction.Keep(1), PdfPageAction.Omit(2)),
        )
        assertTrue(required.isEmpty())
    }

    private fun unit(
        page: Int,
        decision: String,
        source: String = "source $page",
        translation: String = "target $page",
        status: TextItemStatus = TextItemStatus.PROCESSED,
        tag: String = "page:$page",
    ): FormatUnit = FormatUnit(
        row = page,
        source = source,
        translation = translation,
        status = status,
        tag = tag,
        extraField = PdfPageCodec.encode(page, decisionOf(decision), digest),
    )

    private fun decisionOf(wire: String): PdfPageDecision =
        PdfPageDecision.entries.first { it.wire == wire }

    private fun describe(action: PdfPageAction): String = when (action) {
        is PdfPageAction.Keep -> "keep:${action.page}"
        is PdfPageAction.Omit -> "omit:${action.page}"
        is PdfPageAction.Translate -> "translate:${action.page}"
    }

    private fun assertFailure(
        reason: PdfFormatException.Reason,
        page: Int? = null,
        block: () -> Unit,
    ) {
        try {
            block()
            fail("Expected PdfFormatException($reason)")
        } catch (failure: PdfFormatException) {
            assertEquals(reason, failure.reason)
            if (page != null) assertEquals(page, failure.page)
        }
    }
}
