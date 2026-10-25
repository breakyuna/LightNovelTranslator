package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Assert.*
import org.junit.Test

class PdfPagePolicyExportTest {
    private val sha = "a".repeat(64)
    private val asset = ProjectAssetSummary(2, RelativePath("book.pdf"), ProjectAssetFormat.PDF, 1, true, 10, sha, 3)
    private fun units() = (1..3).map { page -> FormatUnit(page, "source $page", "target $page",
        if (page == 3) TextItemStatus.PROCESSED else TextItemStatus.NONE, tag = "page:$page",
        extraField = PdfPageCodec.encode(page, PdfPageDecision.TRANSLATE, sha)) }

    @Test fun storedPolicyChangesExportPlanWithoutMutatingImportedMetadata() {
        val original = units()
        val projected = applyPdfPagePolicy(asset, PdfAssetPagePolicy(2, sha, "kot"), original)
        val plan = PdfPagePlan.plan(projected, 3, FormatWriteOptions())
        assertTrue(plan[0] is PdfPageAction.Keep)
        assertTrue(plan[1] is PdfPageAction.Omit)
        assertEquals("target 3", (plan[2] as PdfPageAction.Translate).text)
        assertEquals(PdfPageDecision.TRANSLATE, PdfPageCodec.decode(original[0], 3).decision)
        assertEquals(sha, PdfPageCodec.decode(projected[0], 3).digest)
    }

    @Test fun explicitTranslationStillRequiresASavedTranslationAndForeignSourceIsRejected() {
        assertThrows(PdfFormatException::class.java) {
            PdfPagePlan.plan(applyPdfPagePolicy(asset, PdfAssetPagePolicy(2, sha, "ttt"), units()), 3, FormatWriteOptions())
        }
        assertThrows(IllegalArgumentException::class.java) {
            applyPdfPagePolicy(asset, PdfAssetPagePolicy(2, "b".repeat(64), "kot"), units())
        }
    }
}
