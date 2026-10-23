package com.breakyuna.noveltranslator.nativecore.formats

import org.junit.Assert.*
import org.junit.Test

class SubtitleDocumentTest {
    private fun replacement(document: SubtitleDocument, text: String, slot: Int = 0) =
        SubtitleReplacement(document.slots[slot].reference, text)
    private fun rejects(reason: InvalidSubtitle.Reason, block: () -> Unit) {
        try { block(); fail("Accepted invalid subtitle") }
        catch (failure: InvalidSubtitle) { assertEquals(reason, failure.reason) }
    }

    @Test fun srtPreservesBomTimingMetadataAndUnchangedCues() {
        val source = "\uFEFF1\r\n00:00:01,000 --> 00:00:02,000 X1:1 X2:2\r\nhello\r\nworld\r\n\r\n" +
            "2\n00:00:03,000 --> 00:00:04,000\nnext"
        val document = SubtitleDocument.parse(SubtitleType.SRT, source)
        assertEquals(listOf("hello\nworld", "next"), document.slots.map { it.source })
        assertEquals(source, document.render(emptyList()))
        assertEquals(source.replace("hello\r\nworld", "你好\r\n世界"),
            document.render(listOf(replacement(document, "你好\n世界"))))
    }

    @Test fun emptySrtTranslationOmitsTheCueAndRenumbersRetainedCues() {
        val source = "\uFEFF7\n00:00:01,000 --> 00:00:02,000\none\n\n" +
            "9\n00:00:03,000 --> 00:00:04,000\ntwo\n\n"
        val document = SubtitleDocument.parse(SubtitleType.SRT, source)
        assertEquals("\uFEFF1\n00:00:03,000 --> 00:00:04,000\ntwo\n\n",
            document.render(listOf(replacement(document, ""))))
        assertEquals(source, document.render(listOf(replacement(document, "")), bilingual = true))
        assertEquals(source.replace("two\n\n", "two\n译文\n\n"),
            document.render(listOf(replacement(document, "译文", 1)), bilingual = true))
    }

    @Test fun srtRejectsBrokenTimingAndBlankLinesThatWouldInjectAnotherCue() {
        for (timing in listOf("bad timing", "00:00:02,000 --> 00:00:01,000", "00:60:01,000 --> 01:00:02,000",
            "00:00:01,000 --> 00:00:02,000 --> 00:00:03,000"))
            rejects(InvalidSubtitle.Reason.STRUCTURE) { SubtitleDocument.parse(SubtitleType.SRT, "1\n$timing\nsource\n") }
        val document = SubtitleDocument.parse(SubtitleType.SRT, "1\n00:00:01,000 --> 00:00:02,000\nsource\n")
        rejects(InvalidSubtitle.Reason.STRUCTURE) { document.render(listOf(replacement(document, "one\n \ntwo"))) }
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nsource\n", document.render(emptyList()))
    }

    @Test fun srtBilingualSourceAndTargetAreNotDuplicatedUnlessRequested() {
        val source = "3\n00:00:01,000 --> 00:00:02,000\nsource\n\n"
        val document = SubtitleDocument.parse(SubtitleType.SRT, source)
        assertEquals(source, document.render(listOf(replacement(document, "source")), bilingual = true))
        assertEquals(source.replace("source\n\n", "source\nsource\n\n"),
            document.render(listOf(replacement(document, "source")), bilingual = true, deduplicate = false))
    }

    @Test fun anAllEmptySrtExportRetainsTheBomAndCanBeReadAgain() {
        val document = SubtitleDocument.parse(SubtitleType.SRT, "\uFEFF1\n00:00:01,000 --> 00:00:02,000\nsource\n")
        val output = document.render(listOf(replacement(document, "")))
        assertEquals("\uFEFF", output)
        assertTrue(SubtitleDocument.parse(SubtitleType.SRT, output).slots.isEmpty())
        assertTrue(SubtitleDocument.parse(SubtitleType.SRT, "\uFEFF\n\n").slots.isEmpty())
    }

    @Test fun lyricsPreserveMetadataTimestampGroupsMixedEndingsAndAnUnterminatedTail() {
        val source = "[ar:Artist]\r\n[00:01.00][00:02.00]<00:01.10>hello<00:01.20> world\r\n[00:03.00]next"
        val document = SubtitleDocument.parse(SubtitleType.LRC, source)
        assertEquals(listOf("hello world", "next"), document.slots.map { it.source })
        assertEquals(source, document.render(emptyList()))
        assertEquals("[ar:Artist]\r\n[00:01.00][00:02.00]你好\r\n[00:01.00][00:02.00]世界\r\n[00:03.00]next",
            document.render(listOf(replacement(document, "你好\n世界"))))
        assertEquals(source, document.render(listOf(replacement(document, "hello world"))))
    }

    @Test fun lyricTimestampGroupsPreserveSpacesAndTabsWhenTranslationsExpand() {
        val prefix = "[00:01.00] \t[00:02.00]"
        val source = prefix + "text\r\n"
        val document = SubtitleDocument.parse(SubtitleType.LRC, source)
        assertEquals("text", document.slots.single().source)
        assertEquals(prefix + "译文\r\n" + prefix + "下一行\r\n",
            document.render(listOf(replacement(document, "译文\n下一行"))))
        assertEquals(prefix + "text\r\n" + prefix + "译文\r\n",
            document.render(listOf(replacement(document, "译文")), bilingual = true))
        assertEquals(source, document.render(emptyList()))
        assertEquals(source, document.render(listOf(replacement(document, "text"))))
    }

    @Test fun lyricInlineTimingKeepsWordBoundariesWithoutDuplicatingExistingWhitespace() {
        for (body in listOf("hello<00:01.20>world", "hello<00:01.20> world",
            "hello <00:01.20>world", "hello[00:01.20]world", "hello<00:01.20><00:01.30>world")) {
            val source = "[00:01.00]" + body
            val document = SubtitleDocument.parse(SubtitleType.LRC, source)
            assertEquals("hello world", document.slots.single().source)
            assertEquals(source, document.render(emptyList()))
            assertEquals(source, document.render(listOf(replacement(document, "hello world"))))
            assertEquals("[00:01.00]译文", document.render(listOf(replacement(document, "译文"))))
        }
        val untouched = "hello<00:01.20>world"
        val document = SubtitleDocument.parse(SubtitleType.LRC, untouched)
        assertTrue(document.slots.isEmpty())
        assertEquals(untouched, document.render(emptyList()))
    }

    @Test fun lyricBilingualOutputUsesTheSameTimestampGroupAndHonoursDeduplication() {
        val document = SubtitleDocument.parse(SubtitleType.LRC, "[0:01]source\n")
        assertEquals("[0:01]source\n[0:01]译文\n", document.render(listOf(replacement(document, "译文")), bilingual = true))
        assertEquals("[0:01]source\n", document.render(listOf(replacement(document, "source")), bilingual = true))
        assertEquals("[0:01]source\n[0:01]source\n",
            document.render(listOf(replacement(document, "source")), bilingual = true, deduplicate = false))
        assertEquals("[0:01]source\n", document.render(listOf(replacement(document, "")), bilingual = true))
        assertEquals("[0:01]\n", document.render(listOf(replacement(document, ""))))
    }

    @Test fun lyricBomIsPreservedOnceWhenATranslationExpandsTheFirstLine() {
        val document = SubtitleDocument.parse(SubtitleType.LRC, "\uFEFF[0:01]source")
        assertEquals("\uFEFF[0:01]one\n[0:01]two", document.render(listOf(replacement(document, "one\ntwo"))))
    }

    @Test fun invalidSourceOrInjectedTargetTimeTagsAreRejected() {
        rejects(InvalidSubtitle.Reason.STRUCTURE) { SubtitleDocument.parse(SubtitleType.LRC, "[0:60]invalid") }
        rejects(InvalidSubtitle.Reason.STRUCTURE) { SubtitleDocument.parse(SubtitleType.LRC, "[0:01]bad<0:99>time") }
        val document = SubtitleDocument.parse(SubtitleType.LRC, "[0:01]source")
        for (target in listOf("[0:02]injected", "<0:02>injected"))
            rejects(InvalidSubtitle.Reason.STRUCTURE) { document.render(listOf(replacement(document, target))) }
    }

    @Test fun assKeepsControlFieldsStylesCommentsCommasAndPhysicalNewlines() {
        val source = "[Script Info]\r\nTitle: fixture\r\n[V4+ Styles]\nFormat: Name, Text\nStyle: Default,do not translate\n" +
            "[Events]\r\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n" +
            "Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,comment\r\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\i1}hello, world\\Nnext"
        val document = SubtitleDocument.parse(SubtitleType.ASS, source)
        assertEquals(1, document.slots.size)
        assertEquals("{\\i1}hello, world\nnext", document.slots.single().source)
        assertEquals(source, document.render(emptyList()))
        val expectedPrefix = source.substringBefore("{\\i1}")
        assertEquals(expectedPrefix + "{\\i1}你好,世界\\N下一行", document.render(listOf(replacement(document, "{\\i1}你好,世界\r\n下一行"))))
    }

    @Test fun ssaUsesTheStandardMarkedLayoutAndBilingualTextStaysInsideTheTextField() {
        val source = "[Events]\nDialogue: Marked=0,0:00:01.00,0:00:02.00,Default,,0,0,0,,source\n"
        val document = SubtitleDocument.parse(SubtitleType.SSA, source)
        assertEquals(source.replace("source\n", "source\\N译文\n"),
            document.render(listOf(replacement(document, "译文")), bilingual = true))
    }

    @Test fun assCustomLayoutsBindToFieldBoundariesAndResetOnANewEventsSection() {
        val source = "[Events]\nFormat: Start,Text\nDialogue: same,same\n[Events]\n" +
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,last\n"
        val document = SubtitleDocument.parse(SubtitleType.ASS, source)
        assertEquals(listOf("same", "last"), document.slots.map { it.source })
        assertEquals(source.replace("Dialogue: same,same", "Dialogue: same,changed"),
            document.render(listOf(replacement(document, "changed"))))
    }

    @Test fun assRejectsAmbiguousOrIncompleteTextFieldsAndUnknownStandardLayouts() {
        for (source in listOf("[Events]\nFormat: Text,Start\n", "[Events]\nFormat: Text,Text\n",
            "[Events]\nFormat: Start,End,Text\nDialogue: missing,body\n", "[Events]\nDialogue: unrecognized\n"))
            rejects(InvalidSubtitle.Reason.STRUCTURE) { SubtitleDocument.parse(SubtitleType.ASS, source) }
    }

    @Test fun referencesRejectDuplicateSlotsChangedAssetsAndForgedSourceBindings() {
        val first = SubtitleDocument.parse(SubtitleType.LRC, "[ar:first]\n[0:01]same\n")
        val second = SubtitleDocument.parse(SubtitleType.LRC, "[ar:other]\n[0:01]same\n")
        val target = replacement(first, "translated")
        rejects(InvalidSubtitle.Reason.BINDING) { second.render(listOf(target)) }
        rejects(InvalidSubtitle.Reason.BINDING) { first.render(listOf(target, target)) }
        rejects(InvalidSubtitle.Reason.BINDING) { first.render(listOf(SubtitleReplacement(
            target.reference.copy(sourceSha256 = "0".repeat(64)), "translated"))) }
        assertEquals("[ar:first]\n[0:01]same\n", first.render(emptyList()))
    }

    @Test fun invalidUnicodeOversizedTargetsAndExpansionBombsFailClosed() {
        rejects(InvalidSubtitle.Reason.TEXT) { SubtitleDocument.parse(SubtitleType.LRC, "[0:01]\uD800") }
        val document = SubtitleDocument.parse(SubtitleType.LRC, "[0:01]source")
        rejects(InvalidSubtitle.Reason.TEXT) { document.render(listOf(replacement(document, "bad\u0000text"))) }
        rejects(InvalidSubtitle.Reason.TEXT) { document.render(listOf(replacement(document, "\uD800"))) }
        rejects(InvalidSubtitle.Reason.LIMIT) { document.render(listOf(replacement(document, "x".repeat(65_537)))) }
        val repeatedPrefix = SubtitleDocument.parse(SubtitleType.LRC, "[0:01]".repeat(1000) + "source")
        rejects(InvalidSubtitle.Reason.LIMIT) { repeatedPrefix.render(listOf(replacement(repeatedPrefix, "\n".repeat(10_000)))) }
        rejects(InvalidSubtitle.Reason.LIMIT) { SubtitleDocument.parse(SubtitleType.LRC, "\n".repeat(100_001)) }
    }
}
