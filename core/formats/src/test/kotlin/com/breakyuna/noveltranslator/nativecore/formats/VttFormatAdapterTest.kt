package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VttFormatAdapterTest {
    private val adapter = VttFormatAdapter()
    private val path = RelativePath("video/captions.vtt")

    private fun translated(unit: FormatUnit, text: String) = FormatUnit(
        row = unit.row,
        source = unit.source,
        translation = text,
        status = TextItemStatus.PROCESSED,
        tag = unit.tag,
        nameSource = unit.nameSource,
        nameTranslation = unit.nameTranslation,
        extraField = unit.extraField,
        skipInternalFilter = unit.skipInternalFilter,
    )

    private fun rejects(reason: InvalidVttFormat.Reason, block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid WebVTT")
        } catch (failure: InvalidVttFormat) {
            assertEquals(reason, failure.reason)
        }
    }

    private val sample = "WEBVTT - sample\r\n" +
        "Kind: captions\r\n" +
        "\r\n" +
        "NOTE do not translate\r\n" +
        "\r\n" +
        "STYLE\r\n" +
        "::cue { color: lime }\r\n" +
        "\r\n" +
        "intro\r\n" +
        "00:00:01.000 --> 00:00:02.500 align:start\r\n" +
        "<v Bob>Hello <b>world</b> &amp; all</v>\r\n" +
        "\r\n" +
        "00:00:03.000 --> 00:00:04.000\r\n" +
        "Second line\r\n" +
        "continues\r\n"

    @Test fun readExtractsSlotsWithStableRowsAndRoundTripsExact() {
        val document = adapter.read(sample, path)
        assertEquals(listOf("Hello ", "world", " & all", "Second line\ncontinues"),
            document.units.map { it.source })
        assertEquals(listOf(11, 11, 11, 14), document.units.map { it.row })
        assertTrue(document.units.all { it.status == TextItemStatus.NONE })
        assertTrue(document.units.all { it.extraField.contains("\"offset\"") })
        assertEquals(sample, adapter.write(sample, document.units, FormatWriteOptions()))
    }

    @Test fun translatedCueKeepsHeaderTimingSettingsAndInlineTags() {
        val document = adapter.read(sample, path)
        val units = document.units.map { unit ->
            when (unit.source) {
                "Hello " -> translated(unit, "你好 ")
                "world" -> translated(unit, "世界")
                " & all" -> translated(unit, " 和全部")
                else -> unit
            }
        }
        val expected = sample.replace(
            "<v Bob>Hello <b>world</b> &amp; all</v>",
            "<v Bob>你好 <b>世界</b> 和全部</v>",
        )
        assertEquals(expected, adapter.write(sample, units, FormatWriteOptions()))
        assertEquals(expected, adapter.write(sample, units.reversed(), FormatWriteOptions()))
    }

    @Test fun bilingualOutputPlacesSourceBeforeTranslationAndKeepsLineEndings() {
        val document = adapter.read(sample, path)
        val units = document.units.map { unit ->
            when (unit.source) {
                "Hello " -> translated(unit, "你好 ")
                "world" -> translated(unit, "世界")
                else -> unit
            }
        }
        val expected = sample.replace(
            "<v Bob>Hello <b>world</b> &amp; all</v>",
            "<v Bob>Hello <b>world</b> &amp; all</v>\r\n" +
                "<v Bob>你好 <b>世界</b> &amp; all</v>",
        )
        assertEquals(expected, adapter.write(sample, units,
            FormatWriteOptions(bilingual = true, deduplicateSame = true)))
    }

    @Test fun bilingualDeduplicationIsDrivenByTheFrozenOptions() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Bob>Hello\n"
        val document = adapter.read(source, path)
        val same = translated(document.units.single(), "Hello")
        assertEquals(source, adapter.write(source, listOf(same),
            FormatWriteOptions(bilingual = true, deduplicateSame = true)))
        assertEquals(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Bob>Hello</v>\n<v Bob>Hello</v>\n",
            adapter.write(source, listOf(same),
                FormatWriteOptions(bilingual = true, deduplicateSame = false)),
        )
    }

    @Test fun completedEmptyCueIsOmittedFromMonolingualOutput() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n\n" +
            "00:00:03.000 --> 00:00:04.000\nKeep\n"
        val document = adapter.read(source, path)
        val units = document.units.map { unit ->
            if (unit.source == "Hello") translated(unit, "") else unit
        }
        assertEquals("WEBVTT\n\n\n00:00:03.000 --> 00:00:04.000\nKeep\n",
            adapter.write(source, units, FormatWriteOptions()))
    }

    @Test fun rubyAndLangTagsFollowLgTargetRules() {
        val rubySource = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<ruby>漢<rt>kan</rt></ruby>\n"
        val rubyDocument = adapter.read(rubySource, path)
        assertEquals(listOf("漢"), rubyDocument.units.map { it.source })
        assertEquals(rubySource.replace("<ruby>漢<rt>kan</rt></ruby>", "漢字"),
            adapter.write(rubySource, listOf(translated(rubyDocument.units.single(), "漢字")),
                FormatWriteOptions()))

        val langSource = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<lang en>Hello</lang>\n"
        val langDocument = adapter.read(langSource, path)
        assertEquals(langSource.replace("<lang en>Hello</lang>", "<lang de>Hallo</lang>"),
            adapter.write(langSource, listOf(translated(langDocument.units.single(), "Hallo")),
                FormatWriteOptions(targetLanguage = "de")))
    }

    @Test fun inlineTimestampsArePreservedUntilACueIsRebuilt() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Bob><00:00:01.100>Hello\n"
        val document = adapter.read(source, path)
        assertEquals(listOf("Hello"), document.units.map { it.source })
        assertEquals(source, adapter.write(source, document.units, FormatWriteOptions()))
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<v Bob>你好\n",
            adapter.write(source, listOf(translated(document.units.single(), "你好")),
                FormatWriteOptions()))
    }

    @Test fun unitsWithoutLocatorsBindByUniqueRowAndSource() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        val manual = FormatUnit(
            row = unit.row,
            source = "Hello",
            translation = "你好",
            status = TextItemStatus.PROCESSED,
        )
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n你好\n",
            adapter.write(source, listOf(manual), FormatWriteOptions()))
    }

    @Test fun writeRejectsForeignDuplicateOrStaleBindings() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        val missing = FormatUnit(
            row = unit.row,
            source = unit.source,
            extraField = "{\"subtitle\":{\"offset\":999999}}",
        )
        rejects(InvalidVttFormat.Reason.BINDING) {
            adapter.write(source, listOf(missing), FormatWriteOptions())
        }
        rejects(InvalidVttFormat.Reason.BINDING) {
            adapter.write(source, listOf(unit, unit), FormatWriteOptions())
        }
        val stale = FormatUnit(
            row = unit.row,
            source = "different",
            extraField = unit.extraField,
        )
        rejects(InvalidVttFormat.Reason.STALE) {
            adapter.write(source, listOf(stale), FormatWriteOptions())
        }
    }

    @Test fun writeRejectsTextThatWouldBreakCueStructure() {
        val source = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val document = adapter.read(source, path)
        val unit = document.units.single()
        rejects(InvalidVttFormat.Reason.STRUCTURE) {
            adapter.write(source, listOf(translated(unit, "one\n\ntwo")), FormatWriteOptions())
        }
        rejects(InvalidVttFormat.Reason.TEXT) {
            adapter.write(source, listOf(translated(unit, "one --> two")), FormatWriteOptions())
        }
    }

    @Test fun malformedDocumentsAreRejected() {
        rejects(InvalidVttFormat.Reason.STRUCTURE) {
            adapter.read("WEBVTT\n\n00:00:02.000 --> 00:00:01.000\nHello\n", path)
        }
        rejects(InvalidVttFormat.Reason.STRUCTURE) {
            adapter.read("WEBVTT\n\n00:60:00.000 --> 00:61:00.000\nHello\n", path)
        }
        rejects(InvalidVttFormat.Reason.STRUCTURE) {
            adapter.read("WEBVTT\nKind: x\n00:00:01.000 --> 00:00:02.000\nHello\n", path)
        }
        rejects(InvalidVttFormat.Reason.STRUCTURE) {
            adapter.read("WEBVTT\n\nNOTE nope --> cue\n", path)
        }
        rejects(InvalidVttFormat.Reason.STRUCTURE) {
            adapter.read("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\n<b>Hello\n", path)
        }
        rejects(InvalidVttFormat.Reason.STRUCTURE) {
            adapter.read("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n\nSTYLE\n::cue{}\n", path)
        }
    }

    @Test fun notesAndStyleBlocksAreNeverTranslated() {
        val document = adapter.read(sample, path)
        assertFalse(document.units.any { it.source.contains("do not translate") })
        assertFalse(document.units.any { it.source.contains("color: lime") })
    }
}
