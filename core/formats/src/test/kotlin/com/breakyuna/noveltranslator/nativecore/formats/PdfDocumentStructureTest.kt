package com.breakyuna.noveltranslator.nativecore.formats

import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure tests for the document-level structures the PDF writer rebuilds. PDF rendering/page writes
 * need `android.*` at runtime, so the byte-level writer is covered by the device/CI boundary; the
 * page mapping, outline pruning, page-label projection and document-information dictionary copy
 * are the complete decision surface. `PDDocumentInformation` itself is a pure COS wrapper, so the
 * metadata copy can run in this plain-JVM source set.
 */
class PdfDocumentStructureTest {
    @Test fun documentInformationCopyKeepsStandardCustomDateAndTrappedEntries() {
        val created = Calendar.getInstance()
        val source = PDDocumentInformation()
        source.setTitle("Title")
        source.setAuthor("Author")
        source.setSubject("Subject")
        source.setKeywords("one, two")
        source.setCreator("Creator")
        source.setProducer("Producer")
        source.setCreationDate(created)
        source.setCustomMetadataValue("Custom", "Value")
        source.setTrapped("True")

        val copy = copyPdfDocumentInformation(source)
        assertEquals("Title", copy.getTitle())
        assertEquals("Author", copy.getAuthor())
        assertEquals("Subject", copy.getSubject())
        assertEquals("one, two", copy.getKeywords())
        assertEquals("Creator", copy.getCreator())
        assertEquals("Producer", copy.getProducer())
        assertEquals(source.getCreationDate().timeInMillis, copy.getCreationDate().timeInMillis)
        assertEquals("Value", copy.getCustomMetadataValue("Custom"))
        assertEquals("True", copy.getTrapped())
        assertEquals(source.getMetadataKeys(), copy.getMetadataKeys())
        assertNotSame(source.getCOSObject(), copy.getCOSObject())
        copy.setCustomMetadataValue("Custom", "Changed")
        assertEquals("Value", source.getCustomMetadataValue("Custom"))
    }

    @Test fun outputMapDropsOmittedPagesAndShiftsLaterPages() {
        val mapping = mapped(
            PdfPageAction.Keep(1),
            PdfPageAction.Omit(2),
            PdfPageAction.Translate(3, "dritter"),
            PdfPageAction.Keep(4),
        )
        assertEquals(1, mapping.outputPage(1) ?: -1)
        assertNull(mapping.outputPage(2))
        assertEquals(2, mapping.outputPage(3) ?: -1)
        assertEquals(3, mapping.outputPage(4) ?: -1)
        assertNull(mapping.outputPage(0))
        assertNull(mapping.outputPage(5))
    }

    @Test fun actualContinuationPagesShiftLaterBookmarkTargets() {
        // Source 1 translates into output pages 1..3, source 2 is omitted, source 3 starts at 4.
        val mapping = PdfPageOutputMap.fromOutputPages(intArrayOf(1, 0, 4))
        assertEquals(1, mapping.outputPage(1) ?: -1)
        assertNull(mapping.outputPage(2))
        assertEquals(4, mapping.outputPage(3) ?: -1)
    }

    @Test fun outlineUsesActualOutputPageAfterTranslationContinuation() {
        val remapped = PdfOutlinePlan.remap(
            listOf(PdfOutlineEntry("Later", 3)),
            PdfPageOutputMap.fromOutputPages(intArrayOf(1, 0, 4)),
        )
        assertEquals(4, remapped.single().page ?: -1)
    }

    @Test fun outlineDropsBookmarksForOmittedPagesAndRemapsRetainedPages() {
        val remapped = PdfOutlinePlan.remap(
            listOf(
                PdfOutlineEntry("One", 1),
                PdfOutlineEntry("Two", 2),
                PdfOutlineEntry("Three", 3),
            ),
            mapped(PdfPageAction.Keep(1), PdfPageAction.Omit(2), PdfPageAction.Translate(3, "x")),
        )
        assertEquals(listOf("One" to 1, "Three" to 2), remapped.map { it.title to it.page })
    }

    @Test fun omittedParentPromotesValidChildBookmarksInsteadOfLosingThem() {
        val remapped = PdfOutlinePlan.remap(
            listOf(
                PdfOutlineEntry(
                    title = "Part",
                    page = 2,
                    children = listOf(PdfOutlineEntry("Chapter", 3)),
                ),
            ),
            mapped(PdfPageAction.Keep(1), PdfPageAction.Omit(2), PdfPageAction.Keep(3)),
        )
        assertEquals(1, remapped.size)
        assertEquals("Chapter", remapped.single().title)
        assertEquals(2, remapped.single().page ?: -1)
    }

    @Test fun structuralParentSurvivesOnlyWithAValidDescendant() {
        val remapped = PdfOutlinePlan.remap(
            listOf(
                PdfOutlineEntry("Part", null, children = listOf(PdfOutlineEntry("Chapter", 1))),
                PdfOutlineEntry("Empty", null),
                PdfOutlineEntry("Lost", null, children = listOf(PdfOutlineEntry("Gone", 2))),
            ),
            mapped(PdfPageAction.Keep(1), PdfPageAction.Omit(2)),
        )
        assertEquals(1, remapped.size)
        assertEquals("Part", remapped.single().title)
        assertNull(remapped.single().page)
        assertEquals(listOf("Chapter" to 1), remapped.single().children.map { it.title to it.page })
    }

    @Test fun outOfRangeBookmarkDestinationIsDropped() {
        val remapped = PdfOutlinePlan.remap(
            listOf(PdfOutlineEntry("Past end", 9)),
            mapped(PdfPageAction.Keep(1)),
        )
        assertTrue(remapped.isEmpty())
    }

    @Test fun pageLabelsFollowRetainedSourcePages() {
        val labels = listOf("Cover", "1", "2", "3")
        val assignments = PdfPageLabelPlan.assignments(
            labels,
            mapped(
                PdfPageAction.Omit(1),
                PdfPageAction.Keep(2),
                PdfPageAction.Translate(3, "drei"),
                PdfPageAction.Keep(4),
            ),
        )
        assertEquals(
            listOf(1 to "1", 2 to "2", 3 to "3"),
            assignments.map { it.outputPage to it.label },
        )
    }

    @Test fun pageLabelsAfterAContinuationSkipToTheActualOutputPage() {
        val assignments = PdfPageLabelPlan.assignments(
            listOf("A", "B", "C"),
            PdfPageOutputMap.fromOutputPages(intArrayOf(1, 0, 4)),
        )
        assertEquals(listOf(1 to "A", 4 to "C"), assignments.map { it.outputPage to it.label })
    }

    @Test fun translatedPagesKeepTheLabelOnTheirFirstOutputPage() {
        val assignments = PdfPageLabelPlan.assignments(
            listOf("A", "B"),
            mapped(PdfPageAction.Translate(1, "a"), PdfPageAction.Keep(2)),
        )
        assertEquals(listOf(1 to "A", 2 to "B"), assignments.map { it.outputPage to it.label })
    }

    @Test fun pageChangingWriteIsRefusedForFormsAndAttachments() {
        val actions = listOf(PdfPageAction.Keep(1), PdfPageAction.Omit(2))
        assertUnsupported {
            PdfWriteGuard.check(hasForms = true, hasAttachments = false, actions = actions)
        }
        assertUnsupported {
            PdfWriteGuard.check(hasForms = false, hasAttachments = true, actions = actions)
        }
        PdfWriteGuard.check(hasForms = false, hasAttachments = false, actions = actions)
    }

    @Test fun keepOnlyWriteNeverReachesTheProtectionGuard() {
        PdfWriteGuard.check(
            hasForms = true,
            hasAttachments = true,
            actions = listOf(PdfPageAction.Keep(1), PdfPageAction.Keep(2)),
        )
    }

    private fun mapped(vararg actions: PdfPageAction): PdfPageOutputMap =
        PdfPageOutputMap.from(actions.toList())

    private fun assertUnsupported(block: () -> Unit) {
        try {
            block()
            fail("Expected PdfFormatException(UNSUPPORTED)")
        } catch (failure: PdfFormatException) {
            assertEquals(PdfFormatException.Reason.UNSUPPORTED, failure.reason)
        }
    }
}
