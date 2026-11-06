package com.breakyuna.noveltranslator.nativecore.formats.rgss

import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RgssDataAdapterTest {
    private val adapter = RgssFormatAdapter()

    @Test fun archiveScansScriptsAndDataWithoutChangingUnmodifiedEntries() {
        val scriptBytes = scripts("print(\"Hello\")")
        // Keep one untranslated non-ASCII string so the rewritten legacy file still proves its
        // encoding on a subsequent read.
        val actorsBytes = actorDataWithDescription("アリア", "忠実な騎士")
        val itemsBytes = actorData("ポーション")
        val image = byteArrayOf(9, 8, 7)
        val original = RgssFixtures.v1(
            listOf(
                RgssFixtures.Entry("Data/Scripts.rxdata", scriptBytes),
                RgssFixtures.Entry("Data/Actors.rxdata", actorsBytes),
                RgssFixtures.Entry("Data/Items.rxdata", itemsBytes),
                RgssFixtures.Entry("Graphics/Pictures/face.png", image),
            ),
        )
        val document = adapter.read(original, RelativePath("Game.rgssad"))
        assertEquals(
            setOf("Hello", "アリア", "忠実な騎士", "ポーション"),
            document.units.map { it.source }.toSet(),
        )
        assertTrue(document.warnings.none { it.code == "rgss.data_files_not_scanned" })
        assertTrue(document.metadata.contains("\"data_scanned\":2"))

        val scriptUnit = document.units.first { it.source == "Hello" }
        val dataUnit = document.units.first { it.source == "アリア" }
        assertEquals("Data/Scripts.rxdata", scriptUnit.tag)
        assertTrue(scriptUnit.extraField.contains("\"kind\":\"ruby\""))
        assertEquals("Data/Actors.rxdata", dataUnit.tag)
        assertTrue(dataUnit.extraField.contains("\"kind\":\"marshal\""))

        val translated = document.units.map { unit ->
            when (unit.source) {
                "Hello" -> processed(unit, "Bonjour")
                "アリア" -> processed(unit, "Aria")
                else -> unit
            }
        }
        val output = adapter.write(original, translated, FormatWriteOptions())
        val archive = RgssArchiveCodec.read(output)
        assertTrue(
            RgssScriptsCodec.read(archive.entry("Data/Scripts.rxdata")!!.data)
                .scripts[0].code.contains("Bonjour"),
        )
        assertEquals("Aria", firstName(archive.entry("Data/Actors.rxdata")!!.data))
        assertArrayEquals(itemsBytes, archive.entry("Data/Items.rxdata")!!.data)
        assertArrayEquals(image, archive.entry("Graphics/Pictures/face.png")!!.data)

        val reread = adapter.read(output, RelativePath("Game.rgssad"))
        assertTrue(reread.units.any { it.source == "Aria" })
        assertTrue(reread.units.none { it.source == "アリア" })
    }

    @Test fun ambiguousLegacyDataWarnsWithoutHidingScripts() {
        val original = RgssFixtures.v1(
            listOf(
                RgssFixtures.Entry("Data/Scripts.rxdata", scripts("puts 'Hello'")),
                RgssFixtures.Entry("Data/Items.rxdata", actorData("Potion")),
            ),
        )
        val document = adapter.read(original, RelativePath("Game.rgssad"))
        assertEquals(listOf("Hello"), document.units.map { it.source })
        assertTrue(document.warnings.any { it.code == "rgss.data_encoding" })
        assertTrue(document.metadata.contains("\"data_skipped\":1"))
    }

    @Test fun corruptDataFileIsSkippedWithAWarning() {
        val original = RgssFixtures.v1(
            listOf(
                RgssFixtures.Entry("Data/Scripts.rxdata", scripts("puts 'Hello'")),
                RgssFixtures.Entry("Data/Broken.rxdata", RgssFixtures.bytes(4, 8, 0x75)),
            ),
        )
        val document = adapter.read(original, RelativePath("Game.rgssad"))
        assertEquals(listOf("Hello"), document.units.map { it.source })
        assertTrue(document.warnings.any { it.code == "rgss.data_unsupported" })
        assertTrue(document.metadata.contains("\"data_skipped\":1"))
    }

    @Test fun bareDataFileReadsAndWrites() {
        val original = actorData("Aria")
        val document = adapter.read(original, RelativePath("Data/Actors.rvdata2"))
        assertEquals("RGSS", document.gameEngine)
        assertEquals(1, document.units.size)
        val unit = document.units.single()
        assertTrue(unit.extraField.contains("\"kind\":\"marshal\""))

        val output = adapter.write(
            original,
            listOf(processed(unit, "アリア")),
            FormatWriteOptions(),
        )
        assertEquals("アリア", firstName(output))
    }

    @Test fun v3CompressedDataEntryStaysCompressed() {
        val original = RgssFixtures.v3(
            listOf(
                RgssFixtures.Entry("Data/Actors.rvdata2", actorData("アリア"), compressed = true),
            ),
        )
        val document = adapter.read(original, RelativePath("Game.rgss3a"))
        val unit = document.units.single()
        val output = adapter.write(original, listOf(processed(unit, "Aria")), FormatWriteOptions())
        val entry = RgssArchiveCodec.read(output).entry("Data/Actors.rvdata2")!!
        assertTrue(entry.compressed)
        assertEquals("Aria", firstName(entry.data))
    }

    @Test fun dataUnitRequiresMarshalKindAndValidLocator() {
        val original = RgssFixtures.v1(
            listOf(RgssFixtures.Entry("Data/Actors.rxdata", actorData("アリア"))),
        )
        val unit = adapter.read(original, RelativePath("Game.rgssad")).units.single()

        assertBinding {
            adapter.write(
                original,
                listOf(
                    FormatUnit(
                        unit.row,
                        unit.source,
                        "Aria",
                        TextItemStatus.PROCESSED,
                        unit.tag,
                        extraField = "{\"v\":1,\"kind\":\"ruby\"}",
                    ),
                ),
                FormatWriteOptions(),
            )
        }
        assertBinding {
            adapter.write(
                original,
                listOf(
                    FormatUnit(
                        unit.row,
                        unit.source,
                        "Aria",
                        TextItemStatus.PROCESSED,
                        unit.tag,
                        extraField = "{\"v\":1,\"kind\":\"marshal\",\"node\":0," +
                            "\"parent\":\"root[0].@name\",\"encoding\":\"EUC-JP\",\"digest\":\"" +
                            "0".repeat(64) + "\"}",
                    ),
                ),
                FormatWriteOptions(),
            )
        }
        assertBinding {
            adapter.write(
                original,
                listOf(
                    FormatUnit(
                        unit.row,
                        unit.source,
                        "Aria",
                        TextItemStatus.PROCESSED,
                        unit.tag,
                        extraField = "{\"v\":1,\"kind\":\"marshal\",\"node\":9999," +
                            "\"parent\":\"root[0].@name\",\"encoding\":\"UTF-8\",\"digest\":\"" +
                            "0".repeat(64) + "\"}",
                    ),
                ),
                FormatWriteOptions(),
            )
        }
    }

    @Test fun dataScanCancellationPropagatesInsteadOfBecomingAWarning() {
        val cancelling = RgssFormatAdapter {
            throw IllegalStateException("cancelled")
        }
        val original = RgssFixtures.v1(
            listOf(RgssFixtures.Entry("Data/Actors.rxdata", actorData("アリア"))),
        )
        try {
            cancelling.read(original, RelativePath("Game.rgssad"))
            fail("Expected cancellation")
        } catch (failure: IllegalStateException) {
            assertEquals("cancelled", failure.message)
        }
    }

    @Test fun directoryServiceScansAndReplacesDataNextToScripts() {
        val root = tempDirectory("rgss-data")
        try {
            val scriptsFile = File(root, "Data/Scripts.rxdata")
            scriptsFile.parentFile!!.mkdirs()
            FileOutputStream(scriptsFile).use { it.write(scripts("puts 'Hello'")) }
            val actorsFile = File(root, "Data/Actors.rxdata")
            FileOutputStream(actorsFile).use { it.write(actorData("アリア")) }
            val brokenFile = File(root, "Data/Broken.rxdata")
            FileOutputStream(brokenFile).use { it.write(RgssFixtures.bytes(4, 8, 0x75)) }

            val project = RgssProjectService.importDirectory(root)
            val scanned = RgssProjectService.scanData(project)
            assertEquals(listOf("Data/Actors.rxdata", "Data/Broken.rxdata"), scanned.map { it.path })
            val actors = scanned.first { it.path == "Data/Actors.rxdata" }
            val unit = actors.scan!!.strings.single()
            assertEquals(RgssDataException.Reason.MARSHAL, scanned.first { it.path == "Data/Broken.rxdata" }.failure)

            val replaced = RgssProjectService.replaceData(
                project,
                "Data/Actors.rxdata",
                listOf(RgssDataEdit(unit.node, unit.path, unit.source, unit.digest, unit.encoding, "Aria")),
            )
            val archive = RgssProjectService.exportArchive(replaced)
            assertEquals("Aria", firstName(RgssArchiveCodec.read(archive).entry("Data/Actors.rxdata")!!.data))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun scripts(code: String): ByteArray =
        RgssScriptsCodec.write(RgssScriptsArchive(listOf(RgssScript.utf8(1, "Main", code))))

    private fun actorData(vararg names: String): ByteArray =
        RubyMarshal.serialize(
            MarshalArray(
                names.map {
                    val actor = MarshalObject("RPG::Actor")
                    actor.addIvar("@name", MarshalString.ofUtf8(it))
                    actor
                },
            ),
        )

    private fun actorDataWithDescription(name: String, description: String): ByteArray {
        val actor = MarshalObject("RPG::Actor")
        actor.addIvar("@name", MarshalString.ofUtf8(name))
        actor.addIvar("@description", MarshalString.ofUtf8(description))
        return RubyMarshal.serialize(MarshalArray(listOf(actor)))
    }

    private fun firstName(bytes: ByteArray): String? {
        val root = RubyMarshal.parse(bytes) as MarshalArray
        val actor = root.elements[0] as MarshalObject
        return (actor.ivars.first { it.name == "@name" }.value as MarshalString).utf8
    }

    private fun processed(unit: FormatUnit, translation: String): FormatUnit =
        FormatUnit(
            unit.row,
            unit.source,
            translation,
            TextItemStatus.PROCESSED,
            unit.tag,
            extraField = unit.extraField,
        )

    private fun assertBinding(block: () -> Unit) {
        try {
            block()
        } catch (failure: RgssFormatException) {
            assertEquals(RgssFormatException.Reason.BINDING, failure.reason)
            return
        }
        fail("Expected RgssFormatException")
    }

    private fun tempDirectory(prefix: String): File {
        val file = File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}")
        assertTrue(file.mkdirs() || file.isDirectory)
        return file
    }
}
