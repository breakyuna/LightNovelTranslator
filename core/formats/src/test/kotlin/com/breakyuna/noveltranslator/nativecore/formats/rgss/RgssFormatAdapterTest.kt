package com.breakyuna.noveltranslator.nativecore.formats.rgss

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RgssFormatAdapterTest {
    private val adapter = RgssFormatAdapter()

    @Test fun readsAndRewritesScriptLiteralsWithoutTouchingOtherEntries() {
        val code = "# comment\nprint(\"Hello\")\nputs 'World'\n"
        val scripts = scripts(code)
        val image = byteArrayOf(1, 2, 3, 4)
        val original = RgssFixtures.v1(
            listOf(
                RgssFixtures.Entry("Data/Scripts.rxdata", scripts),
                RgssFixtures.Entry("Graphics/Pictures/face.png", image),
            ),
        )
        val document = adapter.read(original, RelativePath("Game.rgssad"))
        assertEquals(ProjectAssetFormat.RPGMAKER_RGSS, document.format)
        assertEquals("XP", document.gameEngine)
        assertEquals(2, document.units.size)
        assertEquals(TextItemStatus.RULE_SKIPPED, document.units[0].status)
        assertEquals(setOf("Hello", "World"), document.units.map { it.source }.toSet())

        val translated = document.units.map { unit ->
            if (unit.source == "Hello") {
                FormatUnit(
                    unit.row,
                    unit.source,
                    "Bonjour",
                    TextItemStatus.PROCESSED,
                    unit.tag,
                    extraField = unit.extraField,
                )
            } else {
                unit
            }
        }
        val output = adapter.write(original, translated, FormatWriteOptions())
        val archive = RgssArchiveCodec.read(output)
        val rewritten = RgssScriptsCodec.read(archive.entry("Data/Scripts.rxdata")!!.data)
        assertTrue(rewritten.scripts[0].code.contains("print(\"Bonjour\")"))
        assertTrue(rewritten.scripts[0].code.contains("puts 'World'"))
        assertArrayEquals(image, archive.entry("Graphics/Pictures/face.png")!!.data)
        val reread = adapter.read(output, RelativePath("Game.rgssad"))
        assertTrue(reread.units.any { it.source == "Bonjour" })
        assertTrue(reread.units.none { it.source == "Hello" })
    }

    @Test fun noProcessedUnitsReturnOriginalBytes() {
        val original = RgssFixtures.v1(
            listOf(RgssFixtures.Entry("Data/Scripts.rxdata", scripts("print(\"Hello\")"))),
        )
        val document = adapter.read(original, RelativePath("Game.rgssad"))
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))
    }

    @Test fun readsAndWritesBareScriptsRxdata() {
        val original = scripts("puts 'Hello'")
        val document = adapter.read(original, RelativePath("Scripts.rxdata"))
        assertEquals("RGSS", document.gameEngine)
        assertEquals(1, document.units.size)
        val translated = document.units.map {
            FormatUnit(it.row, it.source, "Bonjour", TextItemStatus.PROCESSED, it.tag, extraField = it.extraField)
        }
        val output = adapter.write(original, translated, FormatWriteOptions())
        val rewritten = RgssScriptsCodec.read(output)
        assertTrue(rewritten.scripts[0].code.contains("puts 'Bonjour'"))
    }

    @Test fun v3CompressedScriptEntryStaysCompressed() {
        val original = RgssFixtures.v3(
            listOf(RgssFixtures.Entry("Data/Scripts.rvdata2", scripts("print(\"Hello\")"), compressed = true)),
        )
        val document = adapter.read(original, RelativePath("Game.rgss3a"))
        assertEquals("VX Ace", document.gameEngine)
        val translated = document.units.map {
            FormatUnit(it.row, it.source, "Bonjour", TextItemStatus.PROCESSED, it.tag, extraField = it.extraField)
        }
        val output = adapter.write(original, translated, FormatWriteOptions())
        val archive = RgssArchiveCodec.read(output)
        val entry = archive.entry("Data/Scripts.rvdata2")!!
        assertTrue(entry.compressed)
        val rewritten = RgssScriptsCodec.read(entry.data)
        assertTrue(rewritten.scripts[0].code.contains("print(\"Bonjour\")"))
    }

    @Test fun unsupportedRubyRejectsOnlyThatScriptWithWarning() {
        val bad = scripts("x = <<~EOS\nhello\nEOS\n")
        val good = scripts("puts 'Good'")
        val original = RgssFixtures.v1(
            listOf(
                RgssFixtures.Entry("Data/Scripts.rxdata", bad),
                RgssFixtures.Entry("Data/Scripts.rvdata2", good),
            ),
        )
        val document = adapter.read(original, RelativePath("Game.rgssad"))
        assertEquals(1, document.units.size)
        assertEquals("Good", document.units[0].source)
        assertTrue(document.warnings.any { it.code == "rgss.ruby_unsupported" })
    }

    @Test fun archiveWithoutScriptsIsAnEmptyValidDocument() {
        val original = RgssFixtures.v1(
            listOf(RgssFixtures.Entry("Graphics/Pictures/face.png", byteArrayOf(1))),
        )
        val document = adapter.read(original, RelativePath("Game.rgssad"))
        assertEquals(0, document.units.size)
        assertTrue(document.warnings.any { it.code == "rgss.no_scripts" })
    }

    @Test fun projectServiceImportsDirectoryExportsArchiveAndDirectory() {
        val root = tempDirectory("rgss-import")
        val outputRoot = tempDirectory("rgss-export")
        try {
            val scriptsFile = File(root, "Data/Scripts.rxdata")
            scriptsFile.parentFile!!.mkdirs()
            FileOutputStream(scriptsFile).use { it.write(scripts("puts 'Hello'")) }
            val image = File(root, "Graphics/Pictures/face.png")
            image.parentFile!!.mkdirs()
            FileOutputStream(image).use { it.write(byteArrayOf(9, 8, 7)) }

            val project = RgssProjectService.importDirectory(root)
            assertEquals(1, project.version)
            assertEquals(2, project.files.size)
            val archiveBytes = RgssProjectService.exportArchive(project)
            val archive = RgssArchiveCodec.read(archiveBytes)
            assertEquals(2, archive.entries.size)
            val scriptArchives = RgssProjectService.readScripts(project)
            assertEquals(1, scriptArchives.size)
            assertEquals("puts 'Hello'", scriptArchives[0].second.scripts[0].code)

            RgssProjectService.exportDirectory(project, outputRoot)
            val exported = File(outputRoot, "Graphics/Pictures/face.png")
            assertArrayEquals(byteArrayOf(9, 8, 7), exported.readBytes())
            assertEquals("puts 'Hello'",
                RgssScriptsCodec.read(File(outputRoot, "Data/Scripts.rxdata").readBytes()).scripts[0].code)
        } finally {
            root.deleteRecursively()
            outputRoot.deleteRecursively()
        }
    }

    @Test fun projectFileRejectsTraversalPath() {
        try {
            RgssProjectFile("../evil.txt", byteArrayOf(1))
        } catch (failure: RgssArchiveException) {
            assertEquals(RgssArchiveException.Reason.PATH, failure.reason)
            return
        }
        org.junit.Assert.fail("Expected RgssArchiveException")
    }

    private fun scripts(code: String): ByteArray =
        RgssScriptsCodec.write(
            RgssScriptsArchive(listOf(RgssScript.utf8(1, "Main", code))),
        )

    private fun tempDirectory(prefix: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        assertTrue(file.mkdirs() || file.isDirectory)
        return file
    }
}
