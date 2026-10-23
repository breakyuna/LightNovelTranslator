package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import java.util.zip.ZipEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EpubFormatAdapterTest {
    private val adapter = EpubFormatAdapter()
    private val path = RelativePath("book.epub")

    @Test fun readsSpineTextNodesAndLeavesHeadAndMetadataAlone() {
        val document = adapter.read(epub(), path)
        assertEquals(ProjectAssetFormat.EPUB, document.format)
        assertEquals(listOf("Hello", "World"), document.units.map { it.source })
        assertTrue(document.units.all { it.tag == "OEBPS/text/ch1.xhtml" })
        assertTrue(document.units.all { it.extraField.contains("\"start\"") })
        assertTrue(document.units.all { it.extraField.contains("\"digest\"") })
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
        assertEquals(setOf("Bonjour", "Monde"), reread.units.map { it.source }.toSet())
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
        assertEquals(listOf("Tom & Jerry"), document.units.map { it.source })
        val output = adapter.write(original, translated(document, mapOf("Tom & Jerry" to "Tom et Jerry")),
            FormatWriteOptions())
        val chapter = FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("OEBPS/text/ch1.xhtml"))
        assertTrue(chapter.contains("Tom et Jerry"))
        assertFalse(chapter.contains("&amp;amp;"))
    }

    @Test fun navAndNcxAreProtectedEvenWhenAUnitClaimsTheirPath() {
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
        assertTrue(document.units.isEmpty())
        assertTrue(document.warnings.any { it.code == "epub.invalid_utf8" })
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
        assertTrue(document.units.isEmpty())
        assertTrue(document.warnings.any { it.code == "epub.missing_document" })
    }

    private fun translated(document: FormatDocument, values: Map<String, String>): List<FormatUnit> =
        document.units.map { unit ->
            val target = values[unit.source]
            if (target == null) unit else FormatUnit(unit.row, unit.source, target,
                TextItemStatus.PROCESSED, unit.tag, extraField = unit.extraField)
        }

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
    ): ByteArray {
        val content = ArrayList<Pair<String, ByteArray>>()
        content.add("mimetype" to "application/epub+zip".toByteArray())
        content.add("META-INF/container.xml" to container("OEBPS/content.opf").toByteArray())
        content.add("OEBPS/content.opf" to opf().toByteArray())
        content.add("OEBPS/text/ch1.xhtml" to chapter.toByteArray())
        content.add("OEBPS/nav.xhtml" to nav().toByteArray())
        content.add("OEBPS/toc.ncx" to ncx().toByteArray())
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

    private fun opf(): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Title</dc:title></metadata>" +
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
