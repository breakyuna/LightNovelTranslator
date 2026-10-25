package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import java.util.zip.ZipEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EpubFormatAdapterTest {
    private val adapter = EpubFormatAdapter()
    private val path = RelativePath("book.epub")

    @Test fun readsSpineTextNodesAndExposesTheOpfTitleUnit() {
        val document = adapter.read(epub(), path)
        assertEquals(ProjectAssetFormat.EPUB, document.format)
        assertEquals(listOf("Hello", "World", "Contents", "Contents", "Title"),
            document.units.map { it.source })
        assertTrue(document.units.take(2).all { it.tag == "OEBPS/text/ch1.xhtml" })
        val nav = document.units[2]
        assertEquals("OEBPS/nav.xhtml", nav.tag)
        assertTrue(nav.extraField.contains("\"kind\":\"nav_text\""))
        val ncx = document.units[3]
        assertEquals("OEBPS/toc.ncx", ncx.tag)
        assertTrue(ncx.extraField.contains("\"kind\":\"ncx_text\""))
        val title = document.units.last()
        assertEquals("OEBPS/content.opf", title.tag)
        assertEquals(TextItemStatus.NONE, title.status)
        assertTrue(title.extraField.contains("\"kind\":\"opf_title\""))
        assertTrue(document.units.all { it.extraField.contains("\"start\"") })
        assertTrue(document.units.all { it.extraField.contains("\"digest\"") })
        assertTrue(document.units.all { it.status == TextItemStatus.NONE })
        assertTrue(document.warnings.none { it.code == "epub.missing_mimetype" })
    }

    @Test fun translatedWriteOnlyChangesTargetedTextNodesAndCopiesEveryOtherEntry() {
        val original = epub()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf("Hello" to "Bonjour", "World" to "Monde")),
            FormatWriteOptions())
        val input = FormatArchiveFixtures.unzip(original)
        val result = FormatArchiveFixtures.unzip(output)
        val chapter = FormatArchiveFixtures.text(result.getValue("OEBPS/text/ch1.xhtml"))
        assertTrue(chapter.contains("<p>Bonjour</p>"))
        assertTrue(chapter.contains("<p>Monde</p>"))
        for (name in listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf",
            "OEBPS/nav.xhtml", "OEBPS/toc.ncx", "OEBPS/images/pic.png", "OEBPS/style.css")) {
            assertArrayEquals(name, input.getValue(name), result.getValue(name))
        }
        val reread = adapter.read(output, path)
        assertEquals(setOf("Bonjour", "Monde", "Contents", "Title"),
            reread.units.map { it.source }.toSet())
        assertEquals("mimetype", FormatArchiveFixtures.entryNames(output).first())
        assertEquals(ZipEntry.STORED.toLong(),
            FormatArchiveFixtures.entryMethods(output).first().second.toLong())
    }

    @Test fun bilingualWriteInsertsTheSourceBeforeTheTargetAndDeduplicatesEqualText() {
        val original = epub()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf("Hello" to "Bonjour")),
            FormatWriteOptions(bilingual = true))
        val chapter = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/text/ch1.xhtml"))
        assertTrue(chapter.contains("Hello\nBonjour"))
        assertTrue(chapter.contains("<p>World</p>"))
        val same = document.units.map {
            FormatUnit(it.row, it.source, it.source, TextItemStatus.PROCESSED, it.tag,
                extraField = it.extraField)
        }
        assertArrayEquals(original, adapter.write(original, same, FormatWriteOptions(bilingual = true)))
    }

    @Test fun entitiesAreDecodedForTheUnitAndReescapedOnWrite() {
        val original = epub(chapter = chapterHtml("<p>Tom &amp; Jerry</p>"))
        val document = adapter.read(original, path)
        assertEquals(listOf("Tom & Jerry", "Contents", "Contents", "Title"),
            document.units.map { it.source })
        val output = adapter.write(original, translated(document, mapOf("Tom & Jerry" to "Tom et Jerry")),
            FormatWriteOptions())
        val chapter = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/text/ch1.xhtml"))
        assertTrue(chapter.contains("Tom et Jerry"))
        assertFalse(chapter.contains("&amp;amp;"))
    }

    @Test fun extractsAltTitleTextWhilePreservingMarkupAndRubyReadings() {
        val original = epub(chapter = chapterHtml(
            "<p><ruby>漢<rt>かん</rt></ruby></p>" +
                "<p><img src=\"images/pic.png\" alt=\"Tom &amp; Jerry\" title='Cover'/></p>" +
                "<p title=\"Keep me\">Body</p>",
        ))
        val document = adapter.read(original, path)
        assertEquals(listOf("漢", "Tom & Jerry", "Cover", "Keep me", "Body", "Contents",
            "Contents", "Title"), document.units.map { it.source })
        assertTrue(document.units.all { it.status == TextItemStatus.NONE })
        assertTrue(document.units[1].extraField.contains("\"kind\":\"attr\""))
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))

        val output = adapter.write(original, translated(document, mapOf(
            "漢" to "汉",
            "Tom & Jerry" to "Tom & <Jerry>",
            "Cover" to "L'été & co",
            "Body" to "正文",
        )), FormatWriteOptions())
        val chapter = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/text/ch1.xhtml"))
        assertTrue(chapter.contains("<ruby>汉<rt>かん</rt></ruby>"))
        assertTrue(chapter.contains("alt=\"Tom &amp; &lt;Jerry&gt;\""))
        assertTrue(chapter.contains("title='L&apos;été &amp; co'"))
        assertTrue(chapter.contains("title=\"Keep me\""))
        assertTrue(chapter.contains("<p>正文</p>"))
        assertTrue(chapter.contains("<img src=\"images/pic.png\""))
    }

    @Test fun rubyBaseTextIsTranslatedWhileReadingsAndAnnotationContainersStayLiteral() {
        val original = epub(chapter = chapterHtml(
            "<p><ruby>漢<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>と" +
                "<ruby><rb>字</rb><rtc>じ</rtc></ruby></p>",
        ))
        val document = adapter.read(original, path)
        val chapterUnits = document.units.filter { it.tag == "OEBPS/text/ch1.xhtml" }
        assertEquals(listOf("漢", "と", "字"), chapterUnits.map { it.source })
        assertTrue(chapterUnits.none { it.source in setOf("かん", "じ", "(", ")") })
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))

        val output = adapter.write(original, translated(document, mapOf(
            "漢" to "汉",
            "と" to "et",
            "字" to "字2",
        )), FormatWriteOptions())
        val chapter = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/text/ch1.xhtml"))
        assertTrue(chapter.contains("<ruby>汉<rp>(</rp><rt>かん</rt><rp>)</rp></ruby>"))
        assertTrue(chapter.contains("<ruby><rb>字2</rb><rtc>じ</rtc></ruby>"))
        assertFalse(chapter.contains("<rt>汉</rt>"))
        assertTrue(chapter.contains("et"))
        val reread = adapter.read(output, path)
        assertEquals(listOf("汉", "et", "字2"),
            reread.units.filter { it.tag == "OEBPS/text/ch1.xhtml" }.map { it.source })
    }

    @Test fun unquotedOrUnterminatedAttributesAreLeftLiteral() {
        val original = epub(chapter = chapterHtml(
            "<p><img alt=Cover/>Text</p><span title=\"unterminated>Broken</span>",
        ))
        val document = adapter.read(original, path)
        assertEquals(listOf("Text", "Contents", "Contents", "Title"), document.units.map { it.source })
        assertArrayEquals(original, adapter.write(original, emptyList(), FormatWriteOptions()))
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))
    }

    @Test fun foreignBodyLocatorsCannotWriteNavigationDocuments() {
        val original = epub()
        val document = adapter.read(original, path)
        val first = document.units.first()
        val forged = FormatUnit(first.row, first.source, "should not be written",
            TextItemStatus.PROCESSED, "OEBPS/nav.xhtml", extraField = first.extraField)
        val output = adapter.write(original, listOf(forged), FormatWriteOptions())
        assertArrayEquals(original, output)
        val ncx = FormatUnit(first.row, first.source, "should not be written",
            TextItemStatus.PROCESSED, "OEBPS/toc.ncx", extraField = first.extraField)
        assertArrayEquals(original, adapter.write(original, listOf(ncx), FormatWriteOptions()))
    }

    @Test fun malformedAndUnsafeArchivesFailClosed() {
        rejects(FormatContainerException.Reason.STRUCTURE) {
            adapter.read(FormatArchiveFixtures.zip(listOf(
                "mimetype" to "application/epub+zip".toByteArray())), path)
        }
        rejects(FormatContainerException.Reason.PATH) {
            adapter.read(FormatArchiveFixtures.zip(listOf(
                "../evil" to byteArrayOf(1))), path)
        }
        rejects(FormatContainerException.Reason.PATH) {
            adapter.read(FormatArchiveFixtures.zip(listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to container("../outside.opf").toByteArray(),
                "OEBPS/content.opf" to opf().toByteArray()), stored = setOf("mimetype")), path)
        }
        rejects(FormatContainerException.Reason.PATH) {
            adapter.read(FormatArchiveFixtures.zip(listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
                "META-INF/CONTAINER.XML" to container("OEBPS/content.opf").toByteArray())), path)
        }
    }

    @Test fun invalidUtf8ChapterAndMissingManifestItemAreWarningsNotWholeBookFailures() {
        val original = epub(entries = listOf(
            "OEBPS/text/broken.xhtml" to byteArrayOf(0xff.toByte(), 0xfe.toByte())))
        val brokenOpf = opf().replace(
            "<item id=\"chap\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>",
            "<item id=\"chap\" href=\"text/broken.xhtml\" media-type=\"application/xhtml+xml\"/>",
        )
        val archive = FormatArchiveFixtures.zip(listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
            "OEBPS/content.opf" to brokenOpf.toByteArray(),
            "OEBPS/text/broken.xhtml" to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
        ), stored = setOf("mimetype"))
        val document = adapter.read(archive, path)
        assertTrue(document.units.none { it.tag == "OEBPS/text/broken.xhtml" })
        assertEquals(listOf("Title"), document.units.map { it.source })
        assertTrue(document.warnings.any { it.code == "epub.invalid_utf8" })
        assertTrue(document.warnings.any { it.code == "epub.missing_document" })
        assertTrue(original.isNotEmpty())
    }

    @Test fun missingSpineDocumentProducesAWarning() {
        val brokenOpf = opf().replace("text/ch1.xhtml", "text/missing.xhtml")
        val archive = FormatArchiveFixtures.zip(listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
            "OEBPS/content.opf" to brokenOpf.toByteArray(),
        ), stored = setOf("mimetype"))
        val document = adapter.read(archive, path)
        assertTrue(document.units.none { it.tag == "OEBPS/text/missing.xhtml" })
        assertEquals(listOf("Title"), document.units.map { it.source })
        assertTrue(document.warnings.any { it.code == "epub.missing_document" })
    }

    @Test fun opfTitleLocatorIsTheExactMetadataSlotAndCarriesAStableDigest() {
        val contentOpf = opf(title = "A &amp; B")
        val original = epub(contentOpf = contentOpf)
        val document = adapter.read(original, path)
        val title = document.units.last()
        assertEquals("A & B", title.source)
        assertEquals("OEBPS/content.opf", title.tag)
        val extra = parseJsonObject(title.extraField) ?: throw AssertionError("title extra is not JSON")
        assertEquals("opf_title", extraString(extra, "kind"))
        val start = extraInt(extra, "start") ?: throw AssertionError("missing start")
        val end = extraInt(extra, "end") ?: throw AssertionError("missing end")
        assertEquals("A &amp; B", contentOpf.substring(start, end))
        assertEquals(containerSha256Hex("A &amp; B"), extraString(extra, "digest"))
    }

    @Test fun opfTitleWriteSplicesOnlyTheTitleSlotAndCopiesOtherEntries() {
        val original = epub()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf("Title" to "Titre & co")),
            FormatWriteOptions())
        val input = FormatArchiveFixtures.unzip(original)
        val result = FormatArchiveFixtures.unzip(output)
        val originalOpf = FormatArchiveFixtures.text(input.getValue("OEBPS/content.opf"))
        val expectedOpf = originalOpf.replace(
            "<dc:title>Title</dc:title>",
            "<dc:title>Titre &amp; co</dc:title>",
        )
        assertEquals(expectedOpf, FormatArchiveFixtures.text(result.getValue("OEBPS/content.opf")))
        val reread = adapter.read(output, path)
        assertEquals("Titre & co", reread.units.last().source)
        assertEquals("OEBPS/content.opf", reread.units.last().tag)
        for (name in listOf("mimetype", "META-INF/container.xml", "OEBPS/text/ch1.xhtml",
            "OEBPS/nav.xhtml", "OEBPS/toc.ncx", "OEBPS/images/pic.png", "OEBPS/style.css")) {
            assertArrayEquals(name, input.getValue(name), result.getValue(name))
        }
    }

    @Test fun opfTitleWhitespaceIsNormalizedForTheUnitAndTheExactSlotIsReplaced() {
        val contentOpf = opf(title = "\n  A &amp; B\n")
        val original = epub(contentOpf = contentOpf)
        val document = adapter.read(original, path)
        assertEquals(" A & B ", document.units.last().source)
        val output = adapter.write(original,
            translated(document, mapOf(" A & B " to "Titre")), FormatWriteOptions())
        val result = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/content.opf"))
        assertTrue(result.contains("<dc:title>Titre</dc:title>"))
        assertFalse(result.contains("A &amp; B"))
    }

    @Test fun bilingualModeKeepsOnlyTheTranslatedOpfTitle() {
        val original = epub()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf("Title" to "Titre")),
            FormatWriteOptions(bilingual = true))
        val result = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/content.opf"))
        assertTrue(result.contains("<dc:title>Titre</dc:title>"))
        assertFalse(result.contains("<dc:title>Title\nTitre</dc:title>"))
    }

    @Test fun staleOpfTitleLocatorLeavesTheChangedOpfUntouched() {
        val document = adapter.read(epub(), path)
        val changed = epub(contentOpf = opf(title = "Changed"))
        val output = adapter.write(changed, translated(document, mapOf("Title" to "Titre")),
            FormatWriteOptions())
        assertArrayEquals(changed, output)
    }

    @Test fun duplicateOpfTitleLocatorsApplyOnlyTheFirstTranslation() {
        val original = epub()
        val document = adapter.read(original, path)
        val title = document.units.last()
        val first = FormatUnit(title.row, title.source, "First", TextItemStatus.PROCESSED,
            title.tag, extraField = title.extraField)
        val second = FormatUnit(title.row, title.source, "Second", TextItemStatus.PROCESSED,
            title.tag, extraField = title.extraField)
        val output = adapter.write(original, listOf(first, second), FormatWriteOptions())
        val result = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/content.opf"))
        assertTrue(result.contains("<dc:title>First</dc:title>"))
        assertFalse(result.contains("Second"))
    }

    @Test fun foreignOrStaleOpfLocatorsCannotWriteOutsideTheCurrentTitleSlot() {
        val original = epub()
        val document = adapter.read(original, path)
        val title = document.units.last()
        val extra = parseJsonObject(title.extraField)!!
        val start = extraInt(extra, "start")!!
        val shifted = buildJsonObject {
            put("v", 1)
            put("start", start + 1)
            put("end", extraInt(extra, "end")!!)
            put("digest", extraString(extra, "digest") ?: "")
            put("kind", "opf_title")
        }.toString()
        val shiftedUnit = FormatUnit(title.row, title.source, "Shifted", TextItemStatus.PROCESSED,
            title.tag, extraField = shifted)
        assertArrayEquals(original, adapter.write(original, listOf(shiftedUnit), FormatWriteOptions()))
        val wrongKind = buildJsonObject {
            put("v", 1)
            put("start", start)
            put("end", extraInt(extra, "end")!!)
            put("digest", extraString(extra, "digest") ?: "")
            put("kind", "text")
        }.toString()
        val wrongKindUnit = FormatUnit(title.row, title.source, "Foreign", TextItemStatus.PROCESSED,
            title.tag, extraField = wrongKind)
        assertArrayEquals(original, adapter.write(original, listOf(wrongKindUnit), FormatWriteOptions()))
    }

    @Test fun unchangedOpfTitleUnitReturnsTheOriginalArchiveBytes() {
        val original = epub()
        val document = adapter.read(original, path)
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))
        val sameTitle = document.units.map {
            if (it.tag == "OEBPS/content.opf") {
                FormatUnit(it.row, it.source, it.source, TextItemStatus.PROCESSED, it.tag,
                    extraField = it.extraField)
            } else {
                it
            }
        }
        assertArrayEquals(original, adapter.write(original, sameTitle, FormatWriteOptions()))
        assertArrayEquals(original, adapter.write(original, sameTitle,
            FormatWriteOptions(bilingual = true, deduplicateSame = false)))
    }

    @Test fun opfDescriptionAndSubjectSlotsAreSeparateAndCreatorStaysLiteral() {
        val creator = "<dc:creator id=\"author\">Author &amp; Co</dc:creator>"
        val contentOpf = opf(metadata =
            "<dc:title>Title</dc:title>" +
                "<dc:description xml:lang=\"en\">A &amp; B</dc:description>" +
                "<dc:subject>Fantasy &amp; Magic</dc:subject>" +
                "<dc:subject>Adventure</dc:subject>" +
                creator)
        val original = epub(contentOpf = contentOpf)
        val document = adapter.read(original, path)
        val metadataUnits = document.units.filter { it.tag == "OEBPS/content.opf" }
        assertEquals(listOf("Title", "A & B", "Fantasy & Magic", "Adventure"),
            metadataUnits.map { it.source })
        assertEquals(listOf("opf_title", "opf_description", "opf_subject", "opf_subject"),
            metadataUnits.map { extraKind(it) })
        assertTrue(document.units.none { it.source == "Author & Co" })

        val output = adapter.write(original, translated(document, mapOf(
            "Title" to "Titre",
            "A & B" to "Un & Deux",
            "Fantasy & Magic" to "奇幻 & 魔法",
            "Adventure" to "Aventure",
        )), FormatWriteOptions())
        val input = FormatArchiveFixtures.unzip(original)
        val result = FormatArchiveFixtures.unzip(output)
        val resultOpf = FormatArchiveFixtures.text(result.getValue("OEBPS/content.opf"))
        assertTrue(resultOpf.contains("<dc:title>Titre</dc:title>"))
        assertTrue(resultOpf.contains("<dc:description xml:lang=\"en\">Un &amp; Deux</dc:description>"))
        assertTrue(resultOpf.contains("<dc:subject>奇幻 &amp; 魔法</dc:subject>"))
        assertTrue(resultOpf.contains("<dc:subject>Aventure</dc:subject>"))
        assertTrue(resultOpf.contains(creator))
        for (name in listOf("mimetype", "META-INF/container.xml", "OEBPS/text/ch1.xhtml",
            "OEBPS/nav.xhtml", "OEBPS/toc.ncx", "OEBPS/images/pic.png", "OEBPS/style.css")) {
            assertArrayEquals(name, input.getValue(name), result.getValue(name))
        }
        assertEquals("mimetype", FormatArchiveFixtures.entryNames(output).first())
        assertEquals(ZipEntry.STORED.toLong(),
            FormatArchiveFixtures.entryMethods(output).first().second.toLong())
        val reread = adapter.read(output, path)
        assertEquals(listOf("Titre", "Un & Deux", "奇幻 & 魔法", "Aventure"),
            reread.units.filter { it.tag == "OEBPS/content.opf" }.map { it.source })
        assertArrayEquals(output, adapter.write(output, reread.units, FormatWriteOptions()))
    }

    @Test fun multipleDirectTitleSlotsAreWrittenIndependently() {
        val contentOpf = opf(metadata =
            "<dc:title xml:lang=\"ja\">Main</dc:title>" +
                "<dc:title id=\"subtitle\">Sub &amp; Title</dc:title>" +
                "<dc:subject>Tag</dc:subject>")
        val original = epub(contentOpf = contentOpf)
        val document = adapter.read(original, path)
        val titleUnits = document.units.filter { extraKind(it) == "opf_title" }
        assertEquals(listOf("Main", "Sub & Title"), titleUnits.map { it.source })
        val subjectUnits = document.units.filter { extraKind(it) == "opf_subject" }
        assertEquals(listOf("Tag"), subjectUnits.map { it.source })

        val output = adapter.write(original, translated(document, mapOf(
            "Main" to "Principal",
            "Sub & Title" to "Sous & Titre",
            "Tag" to "Étiquette",
        )), FormatWriteOptions())
        val resultOpf = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/content.opf"))
        assertTrue(resultOpf.contains("<dc:title xml:lang=\"ja\">Principal</dc:title>"))
        assertTrue(resultOpf.contains("<dc:title id=\"subtitle\">Sous &amp; Titre</dc:title>"))
        assertTrue(resultOpf.contains("<dc:subject>Étiquette</dc:subject>"))

        val reread = adapter.read(output, path)
        assertEquals(listOf("Principal", "Sous & Titre"),
            reread.units.filter { extraKind(it) == "opf_title" }.map { it.source })
        assertEquals(listOf("Étiquette"),
            reread.units.filter { extraKind(it) == "opf_subject" }.map { it.source })
    }

    @Test fun opfMetadataEscapingUsesUtf16OffsetsAfterSupplementaryCharacters() {
        val smile = "\uD83D\uDE00"
        val contentOpf = opf(metadata =
            "<dc:title>$smile &amp; 前</dc:title>" +
                "<dc:description>説明</dc:description>" +
                "<dc:subject>Tag</dc:subject>")
        val original = epub(contentOpf = contentOpf)
        val document = adapter.read(original, path)
        val title = document.units.first { extraKind(it) == "opf_title" }
        val description = document.units.first { extraKind(it) == "opf_description" }
        assertEquals("$smile & 前", title.source)
        assertEquals("説明", description.source)
        val titleExtra = parseJsonObject(title.extraField) ?: throw AssertionError("missing title extra")
        val descriptionExtra = parseJsonObject(description.extraField)
            ?: throw AssertionError("missing description extra")
        val titleStart = extraInt(titleExtra, "start") ?: throw AssertionError("missing title start")
        val titleEnd = extraInt(titleExtra, "end") ?: throw AssertionError("missing title end")
        val descriptionStart = extraInt(descriptionExtra, "start")
            ?: throw AssertionError("missing description start")
        val descriptionEnd = extraInt(descriptionExtra, "end")
            ?: throw AssertionError("missing description end")
        assertEquals("$smile &amp; 前", contentOpf.substring(titleStart, titleEnd))
        assertEquals("説明", contentOpf.substring(descriptionStart, descriptionEnd))
        assertTrue(descriptionStart > titleEnd)

        val output = adapter.write(original, translated(document, mapOf(
            "$smile & 前" to "$smile & 后",
            "説明" to "Desc & <More>",
            "Tag" to "Fish & Chips",
        )), FormatWriteOptions())
        val resultOpf = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/content.opf"))
        assertTrue(resultOpf.contains("<dc:title>$smile &amp; 后</dc:title>"))
        assertTrue(resultOpf.contains("<dc:description>Desc &amp; &lt;More&gt;</dc:description>"))
        assertTrue(resultOpf.contains("<dc:subject>Fish &amp; Chips</dc:subject>"))
        val reread = adapter.read(output, path)
        assertEquals("$smile & 后", reread.units.first { extraKind(it) == "opf_title" }.source)
        assertEquals("Desc & <More>",
            reread.units.first { extraKind(it) == "opf_description" }.source)
    }

    @Test fun missingOrBlankDescriptionAndSubjectAddNoUnitsOrBytes() {
        val contentOpf = opf(metadata =
            "<dc:title>Only</dc:title>" +
                "<dc:description> \n\t </dc:description>" +
                "<dc:subject></dc:subject>")
        val original = epub(contentOpf = contentOpf)
        val document = adapter.read(original, path)
        assertEquals(listOf("Hello", "World", "Contents", "Contents", "Only"),
            document.units.map { it.source })
        assertTrue(document.units.none { extraKind(it) == "opf_description" })
        assertTrue(document.units.none { extraKind(it) == "opf_subject" })
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))
    }

    @Test fun creatorMetadataIsNeverExposedOrRewritten() {
        val creator = "<dc:creator>Author Name</dc:creator>"
        val original = epub(contentOpf = opf(metadata = creator + "<dc:title>Title</dc:title>"))
        val document = adapter.read(original, path)
        assertEquals(listOf("Title"),
            document.units.filter { it.tag == "OEBPS/content.opf" }.map { it.source })
        assertTrue(document.units.none { it.source == "Author Name" })
        val output = adapter.write(original, translated(document, mapOf("Title" to "Titre")),
            FormatWriteOptions())
        val resultOpf = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/content.opf"))
        assertTrue(resultOpf.contains(creator))
        assertTrue(resultOpf.contains("<dc:title>Titre</dc:title>"))
        assertFalse(resultOpf.contains("<dc:creator>Titre</dc:creator>"))
    }

    @Test fun blankOpfTitleIsNotExtracted() {
        val document = adapter.read(epub(contentOpf = opf(title = "  \n  ")), path)
        assertEquals(listOf("Hello", "World", "Contents", "Contents"), document.units.map { it.source })
    }

    @Test fun missingOpfTitleAddsNoMetadataUnit() {
        val contentOpf = opf().replace("<dc:title>Title</dc:title>", "")
        val document = adapter.read(epub(contentOpf = contentOpf), path)
        assertEquals(listOf("Hello", "World", "Contents", "Contents"), document.units.map { it.source })
    }

    @Test fun epub3NavLabelsAndNestedListsAreSplicedWithoutTouchingMarkup() {
        val nav = nestedNav()
        val original = epub(chapter = chapterHtml("<p>Hello</p>"), navDocument = nav)
        val document = adapter.read(original, path)
        val navUnits = document.units.filter { it.tag == "OEBPS/nav.xhtml" }
        val navTextUnits = navUnits.filter { extraKind(it) == "nav_text" }
        assertEquals(listOf("Contents", "Chapter & One", "Section A", "Section <B>"),
            navTextUnits.map { it.source })
        assertTrue(navUnits.all { it.status == TextItemStatus.NONE })
        val navAttributeUnits = navUnits.filter { extraKind(it) == "nav_attr" }
        assertEquals(listOf("A & B"), navAttributeUnits.map { it.source })

        val output = adapter.write(original, translated(document, mapOf(
            "Chapter & One" to "Chapitre & Un",
            "Section A" to "Section A (fr)",
            "Section <B>" to "Section <Bé>",
        )), FormatWriteOptions())
        val result = FormatArchiveFixtures.unzip(output)
        val navText = FormatArchiveFixtures.text(result.getValue("OEBPS/nav.xhtml"))
        assertTrue(navText.contains("<h1>Contents</h1>"))
        assertTrue(navText.contains(
            "<a href=\"text/ch1.xhtml#top\" title=\"A &amp; B\">Chapitre &amp; Un</a>"))
        assertTrue(navText.contains("<span class=\"sub\">Section A (fr)</span>"))
        assertTrue(navText.contains("<a href=\"text/ch1.xhtml#b\">Section &lt;Bé&gt;</a>"))
        assertTrue(navText.contains("epub:type=\"toc\""))
        assertTrue(navText.contains("id=\"toc\""))
        assertTrue(navText.contains("<title>Navigation</title>"))
        val input = FormatArchiveFixtures.unzip(original)
        for (name in listOf("mimetype", "META-INF/container.xml", "OEBPS/content.opf",
            "OEBPS/text/ch1.xhtml", "OEBPS/toc.ncx", "OEBPS/images/pic.png", "OEBPS/style.css")) {
            assertArrayEquals(name, input.getValue(name), result.getValue(name))
        }
        assertEquals(listOf("Contents", "Chapitre & Un", "Section A (fr)", "Section <Bé>"),
            adapter.read(output, path).units.filter {
                it.tag == "OEBPS/nav.xhtml" && extraKind(it) == "nav_text"
            }.map { it.source })
        val reread = adapter.read(output, path)
        assertArrayEquals(output, adapter.write(output, reread.units, FormatWriteOptions()))
    }

    @Test fun navTitleAndAltAttributesAreWrittenWhileOtherAttributesStayLiteral() {
        val nav = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
            "<head><title>Navigation</title></head><body><nav epub:type=\"toc\">" +
            "<ol><li><a href=\"text/ch1.xhtml#top\" title=\"Tip &amp; More\" data-id=\"keep\">" +
            "Chapter</a></li>" +
            "<li><img src=\"images/pic.png\" alt=\"Cover &amp; Art\"/></li></ol>" +
            "</nav></body></html>"
        val original = epub(navDocument = nav)
        val document = adapter.read(original, path)
        val navUnits = document.units.filter { it.tag == "OEBPS/nav.xhtml" }
        assertEquals(listOf("Chapter"), navUnits.filter { extraKind(it) == "nav_text" }.map { it.source })
        assertEquals(listOf("Tip & More", "Cover & Art"),
            navUnits.filter { extraKind(it) == "nav_attr" }.map { it.source })

        val output = adapter.write(original, translated(document, mapOf(
            "Chapter" to "Chapitre",
            "Tip & More" to "L'été & plus",
            "Cover & Art" to "Couverture & Art",
        )), FormatWriteOptions())
        val navText = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/nav.xhtml"))
        assertTrue(navText.contains(
            "<a href=\"text/ch1.xhtml#top\" title=\"L&apos;été &amp; plus\" data-id=\"keep\">Chapitre</a>"))
        assertTrue(navText.contains("<img src=\"images/pic.png\" alt=\"Couverture &amp; Art\"/>"))
        assertTrue(navText.contains("<head><title>Navigation</title></head>"))
        val reread = adapter.read(output, path)
        assertEquals(listOf("Chapitre"),
            reread.units.filter { it.tag == "OEBPS/nav.xhtml" && extraKind(it) == "nav_text" }
                .map { it.source })
        assertEquals(listOf("L'été & plus", "Couverture & Art"),
            reread.units.filter { it.tag == "OEBPS/nav.xhtml" && extraKind(it) == "nav_attr" }
                .map { it.source })
    }

    @Test fun ncxLabelsAndNestedNavPointsAreSplicedWithoutTouchingAttributes() {
        val original = epub(ncxDocument = nestedNcx())
        val document = adapter.read(original, path)
        val ncxUnits = document.units.filter { it.tag == "OEBPS/toc.ncx" }
        assertEquals(listOf("Book & Title", "Chapter & One", "Section <B>"),
            ncxUnits.map { it.source })
        assertTrue(ncxUnits.all { it.extraField.contains("\"kind\":\"ncx_text\"") })

        val output = adapter.write(original, translated(document, mapOf(
            "Book & Title" to "Livre & Titre",
            "Chapter & One" to "Chapitre & Un",
            "Section <B>" to "Section <Bé>",
        )), FormatWriteOptions())
        val ncxText = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/toc.ncx"))
        assertTrue(ncxText.contains("<docTitle><text>Livre &amp; Titre</text></docTitle>"))
        assertTrue(ncxText.contains("<navPoint id=\"n1\" playOrder=\"1\">"))
        assertTrue(ncxText.contains("<navPoint id=\"n1-1\" playOrder=\"2\">"))
        assertTrue(ncxText.contains("<content src=\"text/ch1.xhtml\"/>"))
        assertTrue(ncxText.contains("<content src=\"text/ch1.xhtml#b\"/>"))
        assertTrue(ncxText.contains("<text>Chapitre &amp; Un</text>"))
        assertTrue(ncxText.contains("<text>Section &lt;Bé&gt;</text>"))
        assertTrue(ncxText.contains("urn:uuid:test"))
        assertTrue(ncxText.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))
        val reread = adapter.read(output, path)
        assertArrayEquals(output, adapter.write(output, reread.units, FormatWriteOptions()))
    }

    @Test fun navigationLabelsRemainTargetOnlyInBilingualMode() {
        val original = epub(navDocument = nestedNav(), ncxDocument = nestedNcx())
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf(
            "Hello" to "Bonjour",
            "Chapter & One" to "Chapitre & Un",
            "Book & Title" to "Livre & Titre",
        )), FormatWriteOptions(bilingual = true))
        val result = FormatArchiveFixtures.unzip(output)
        val chapter = FormatArchiveFixtures.text(result.getValue("OEBPS/text/ch1.xhtml"))
        assertTrue(chapter.contains("Hello\nBonjour"))
        val navText = FormatArchiveFixtures.text(result.getValue("OEBPS/nav.xhtml"))
        assertTrue(navText.contains(">Chapitre &amp; Un<"))
        assertFalse(navText.contains("Chapter &amp; One\nChapitre"))
        val ncxText = FormatArchiveFixtures.text(result.getValue("OEBPS/toc.ncx"))
        assertTrue(ncxText.contains("<text>Livre &amp; Titre</text>"))
        assertFalse(ncxText.contains("Book &amp; Title\nLivre"))
    }

    @Test fun staleDuplicateOrForeignNavigationLocatorsAreRejected() {
        val original = epub(navDocument = nestedNav())
        val document = adapter.read(original, path)
        val navUnit = document.units.first {
            it.tag == "OEBPS/nav.xhtml" && it.source == "Chapter & One"
        }
        val first = FormatUnit(navUnit.row, navUnit.source, "First", TextItemStatus.PROCESSED,
            navUnit.tag, extraField = navUnit.extraField)
        val second = FormatUnit(navUnit.row, navUnit.source, "Second", TextItemStatus.PROCESSED,
            navUnit.tag, extraField = navUnit.extraField)
        val duplicated = adapter.write(original, listOf(first, second), FormatWriteOptions())
        val duplicatedNav = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(duplicated).getValue("OEBPS/nav.xhtml"))
        assertTrue(duplicatedNav.contains(">First<"))
        assertFalse(duplicatedNav.contains("Second"))

        val changed = epub(navDocument = nestedNav().replace("Chapter &amp; One", "Changed &amp; One"))
        assertArrayEquals(changed, adapter.write(changed, listOf(first), FormatWriteOptions()))

        val wrongSource = FormatUnit(navUnit.row, "Different", "Wrong",
            TextItemStatus.PROCESSED, navUnit.tag, extraField = navUnit.extraField)
        assertArrayEquals(original, adapter.write(original, listOf(wrongSource),
            FormatWriteOptions()))
    }

    @Test fun untouchedOrBlankNavigationTargetsKeepOriginalBytes() {
        val original = epub(navDocument = nestedNav(), ncxDocument = nestedNcx())
        val document = adapter.read(original, path)
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))
        val same = document.units.map {
            if (it.extraField.contains("\"nav_text\"") || it.extraField.contains("\"ncx_text\"")) {
                FormatUnit(it.row, it.source, it.source, TextItemStatus.PROCESSED, it.tag,
                    extraField = it.extraField)
            } else {
                it
            }
        }
        assertArrayEquals(original, adapter.write(original, same, FormatWriteOptions()))
        assertArrayEquals(original, adapter.write(original, same,
            FormatWriteOptions(bilingual = true, deduplicateSame = false)))
        val blank = document.units.map {
            if (it.extraField.contains("\"nav_text\"") || it.extraField.contains("\"ncx_text\"")) {
                FormatUnit(it.row, it.source, "   ", TextItemStatus.PROCESSED, it.tag,
                    extraField = it.extraField)
            } else {
                it
            }
        }
        assertArrayEquals(original, adapter.write(original, blank, FormatWriteOptions()))
    }

    @Test fun navRubyReadingsStayLiteral() {
        val nav = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><nav><ol><li>" +
            "<a href=\"text/ch1.xhtml\"><ruby>漢<rt>かん</rt></ruby></a>" +
            "</li></ol></nav></body></html>"
        val original = epub(navDocument = nav)
        val document = adapter.read(original, path)
        val navUnits = document.units.filter { it.tag == "OEBPS/nav.xhtml" }
        assertEquals(listOf("漢"), navUnits.map { it.source })
        val output = adapter.write(original, translated(document, mapOf("漢" to "汉")),
            FormatWriteOptions())
        val navText = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/nav.xhtml"))
        assertTrue(navText.contains("<ruby>汉<rt>かん</rt></ruby>"))
    }

    @Test fun navigationDocumentInSpineIsExtractedOnceAsNavigationText() {
        val contentOpf = opf().replace(
            "<itemref idref=\"chap\"/>",
            "<itemref idref=\"chap\"/><itemref idref=\"nav\"/>",
        )
        val document = adapter.read(epub(contentOpf = contentOpf), path)
        val navUnits = document.units.filter { it.tag == "OEBPS/nav.xhtml" }
        assertEquals(listOf("Contents"), navUnits.map { it.source })
        assertTrue(navUnits.single().extraField.contains("\"kind\":\"nav_text\""))
        assertEquals(listOf("OEBPS/text/ch1.xhtml", "OEBPS/text/ch1.xhtml", "OEBPS/nav.xhtml",
            "OEBPS/toc.ncx", "OEBPS/content.opf"), document.units.map { it.tag })
    }

    @Test fun epub2NcxIsDiscoveredWithoutANavDocumentOrSpineTocAttribute() {
        val contentOpf = opf()
            .replace("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>", "")
            .replace("<spine toc=\"ncx\">", "<spine>")
        val document = adapter.read(epub(contentOpf = contentOpf), path)
        assertEquals(listOf("Hello", "World", "Contents", "Title"), document.units.map { it.source })
        assertEquals("OEBPS/toc.ncx", document.units[2].tag)
        assertTrue(document.units[2].extraField.contains("\"kind\":\"ncx_text\""))
    }

    @Test fun missingNavigationPathsOrEntriesAreTolerated() {
        val contentOpf = opf()
            .replace("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>", "")
            .replace("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>", "")
            .replace("<spine toc=\"ncx\">", "<spine>")
        val original = epub(contentOpf = contentOpf)
        val document = adapter.read(original, path)
        assertEquals(listOf("Hello", "World", "Title"), document.units.map { it.source })
        val output = adapter.write(original, translated(document, mapOf("Hello" to "Bonjour")),
            FormatWriteOptions())
        assertEquals("Bonjour", adapter.read(output, path).units.first().source)
        val input = FormatArchiveFixtures.unzip(original)
        val result = FormatArchiveFixtures.unzip(output)
        assertArrayEquals(input.getValue("OEBPS/nav.xhtml"), result.getValue("OEBPS/nav.xhtml"))
        assertArrayEquals(input.getValue("OEBPS/toc.ncx"), result.getValue("OEBPS/toc.ncx"))

        val missingEntries = FormatArchiveFixtures.zip(listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
            "OEBPS/content.opf" to opf().toByteArray(),
            "OEBPS/text/ch1.xhtml" to chapterHtml("<p>Hello</p>").toByteArray(),
        ), stored = setOf("mimetype"))
        val missing = adapter.read(missingEntries, path)
        assertEquals(listOf("Hello", "Title"), missing.units.map { it.source })
        assertEquals(2, missing.warnings.count { it.code == "epub.missing_document" })
    }

    @Test fun invalidOrOversizedNavigationDocumentsFailClosed() {
        val oversized = "a".repeat(ContainerLimits.MAX_UNIT_SOURCE_CHARS + 1)
        val oversizedNav = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><nav><ol><li>" +
            "<a href=\"text/ch1.xhtml\">$oversized</a></li></ol></nav></body></html>"
        val bounded = adapter.read(epub(navDocument = oversizedNav), path)
        assertTrue(bounded.units.none { it.tag == "OEBPS/nav.xhtml" })
        assertTrue(bounded.warnings.any { it.code == "epub.text_too_large" })

        val broken = FormatArchiveFixtures.zip(listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container("OEBPS/content.opf").toByteArray(),
            "OEBPS/content.opf" to opf().toByteArray(),
            "OEBPS/text/ch1.xhtml" to chapterHtml("<p>Hello</p>").toByteArray(),
            "OEBPS/nav.xhtml" to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            "OEBPS/toc.ncx" to "<ncx><navMap><navPoint><navLabel><text>Unclosed".toByteArray(),
        ), stored = setOf("mimetype"))
        val document = adapter.read(broken, path)
        assertTrue(document.units.none { it.tag == "OEBPS/nav.xhtml" })
        assertTrue(document.units.none { it.tag == "OEBPS/toc.ncx" })
        assertTrue(document.warnings.any { it.code == "epub.invalid_utf8" })
        assertArrayEquals(broken, adapter.write(broken, document.units, FormatWriteOptions()))
    }

    private fun nestedNav(): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\">" +
            "<head><title>Navigation</title></head><body>" +
            "<nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>" +
            "<li><a href=\"text/ch1.xhtml#top\" title=\"A &amp; B\">Chapter &amp; One</a>" +
            "<ol><li><span class=\"sub\">Section A</span></li>" +
            "<li><a href=\"text/ch1.xhtml#b\">Section &lt;B&gt;</a></li></ol></li>" +
            "</ol></nav></body></html>"

    private fun nestedNcx(): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">" +
            "<head><meta name=\"dtb:uid\" content=\"urn:uuid:test\"/></head>" +
            "<docTitle><text>Book &amp; Title</text></docTitle>" +
            "<navMap>" +
            "<navPoint id=\"n1\" playOrder=\"1\"><navLabel><text>Chapter & One</text></navLabel>" +
            "<content src=\"text/ch1.xhtml\"/>" +
            "<navPoint id=\"n1-1\" playOrder=\"2\"><navLabel><text>Section &lt;B&gt;</text></navLabel>" +
            "<content src=\"text/ch1.xhtml#b\"/></navPoint></navPoint>" +
            "</navMap></ncx>"

    private fun translated(document: FormatDocument, values: Map<String, String>): List<FormatUnit> =
        document.units.map { unit ->
            val target = values[unit.source]
            if (target == null) unit else FormatUnit(unit.row, unit.source, target,
                TextItemStatus.PROCESSED, unit.tag, extraField = unit.extraField)
        }

    private fun extraKind(unit: FormatUnit): String? =
        parseJsonObject(unit.extraField)?.let { extraString(it, "kind") }

    private fun rejects(reason: FormatContainerException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid EPUB")
        } catch (failure: FormatContainerException) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun epub(
        chapter: String = chapterHtml("<p>Hello</p><p>World</p>"),
        entries: List<Pair<String, ByteArray>> = emptyList(),
        contentOpf: String = opf(),
        navDocument: String = nav(),
        ncxDocument: String = ncx(),
    ): ByteArray {
        val content = ArrayList<Pair<String, ByteArray>>()
        content.add("mimetype" to "application/epub+zip".toByteArray())
        content.add("META-INF/container.xml" to container("OEBPS/content.opf").toByteArray())
        content.add("OEBPS/content.opf" to contentOpf.toByteArray())
        content.add("OEBPS/text/ch1.xhtml" to chapter.toByteArray())
        content.add("OEBPS/nav.xhtml" to navDocument.toByteArray())
        content.add("OEBPS/toc.ncx" to ncxDocument.toByteArray())
        content.add("OEBPS/images/pic.png" to byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
        content.add("OEBPS/style.css" to "body { color: black; }".toByteArray())
        content.addAll(entries)
        return FormatArchiveFixtures.zip(content, stored = setOf("mimetype"))
    }

    private fun chapterHtml(body: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter</title></head>" +
            "<body>$body</body></html>"

    private fun container(opfPath: String): String =
        "<?xml version=\"1.0\"?><container version=\"1.0\" " +
            "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>" +
            "<rootfile full-path=\"$opfPath\" media-type=\"application/oebps-package+xml\"/>" +
            "</rootfiles></container>"

    private fun opf(title: String = "Title", metadata: String? = null): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            (metadata ?: "<dc:title>$title</dc:title>") + "</metadata>" +
            "<manifest>" +
            "<item id=\"chap\" href=\"text/ch1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
            "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
            "<item id=\"img\" href=\"images/pic.png\" media-type=\"image/png\"/>" +
            "</manifest><spine toc=\"ncx\"><itemref idref=\"chap\"/></spine></package>"

    private fun nav(): String =
        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><nav epub:type=\"toc\">" +
            "<ol><li><a href=\"text/ch1.xhtml\">Contents</a></li></ol></nav></body></html>"

    private fun ncx(): String =
        "<?xml version=\"1.0\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\"><navMap>" +
            "<navPoint id=\"n1\"><navLabel><text>Contents</text></navLabel>" +
            "<content src=\"text/ch1.xhtml\"/></navPoint></navMap></ncx>"
}
