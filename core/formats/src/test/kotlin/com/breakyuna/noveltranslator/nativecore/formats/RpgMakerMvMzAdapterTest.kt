package com.breakyuna.noveltranslator.nativecore.formats

import com.breakyuna.noveltranslator.nativecore.domain.FormatDocument
import com.breakyuna.noveltranslator.nativecore.domain.FormatUnit
import com.breakyuna.noveltranslator.nativecore.domain.FormatWriteOptions
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RpgMakerMvMzAdapterTest {
    private val adapter = RpgMakerMvMzAdapter()
    private val path = RelativePath("game.zip")

    @Test fun readsDatabaseFieldsEventBlocksChoicesAndPluginLiterals() {
        val document = adapter.read(rpg(), path)
        assertEquals(ProjectAssetFormat.RPGMAKER_MV_MZ, document.format)
        assertEquals("MZ", document.gameEngine)
        assertEquals(TextItemStatus.NONE, unit(document, "data/Actors.json", "Hero").status)
        assertEquals(TextItemStatus.RULE_SKIPPED, unit(document, "data/Actors.json", "HeroBattler").status)
        assertEquals(TextItemStatus.NONE, unit(document, "data/System.json", "Quest").status)
        assertEquals(TextItemStatus.NONE, unit(document, "data/System.json", "Attack").status)
        assertEquals(TextItemStatus.NONE, unit(document, "data/Map001.json", "Town").status)
        val message = unit(document, "data/Map001.json", "Hello\nWorld")
        assertEquals(TextItemStatus.NONE, message.status)
        assertTrue(message.extraField.contains("\"kind\":\"block\""))
        assertTrue(message.extraField.contains("\"code\":401"))
        assertEquals(TextItemStatus.NONE, unit(document, "data/Map001.json", "Yes").status)
        assertEquals(TextItemStatus.NONE, unit(document, "data/Map001.json", "No").status)
        assertEquals(TextItemStatus.RULE_SKIPPED, unit(document, "js/plugins.js", "A plugin").status)
        assertEquals(TextItemStatus.RULE_SKIPPED,
            unit(document, "js/plugins/Extra.js", "Extra text").status)
    }

    @Test fun writeUpdatesFieldsBlocksChoicesAndPluginLiteralsAndCopiesMedia() {
        val original = rpg()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf(
            ("data/Actors.json" to "Hero") to "Héros",
            ("data/System.json" to "Quest") to "Quête",
            ("data/System.json" to "Attack") to "Attaque",
            ("data/Map001.json" to "Town") to "Ville",
            ("data/Map001.json" to "Hello\nWorld") to "Bonjour\nMonde",
            ("data/Map001.json" to "Yes") to "Oui",
            ("data/Map001.json" to "No") to "Non",
            ("js/plugins.js" to "A plugin") to "Un plugin",
            ("js/plugins/Extra.js" to "Extra text") to "Texte extra",
        )), FormatWriteOptions())
        val input = FormatArchiveFixtures.unzip(original)
        val result = FormatArchiveFixtures.unzip(output)
        val actors = Json.parseToJsonElement(FormatArchiveFixtures.text(result.getValue("data/Actors.json")))
        val actor = (actors as JsonArray)[1] as JsonObject
        assertEquals("Héros", (actor["name"] as JsonPrimitive).content)
        assertEquals("HeroBattler", (actor["battlerName"] as JsonPrimitive).content)
        val system = Json.parseToJsonElement(FormatArchiveFixtures.text(result.getValue("data/System.json"))) as JsonObject
        assertEquals("Quête", (system["gameTitle"] as JsonPrimitive).content)
        assertEquals("0123456789abcdef0123456789abcdef",
            (system["encryptionKey"] as JsonPrimitive).content)
        val terms = system["terms"] as JsonObject
        assertEquals("Attaque", ((terms["basic"] as JsonArray)[0] as JsonPrimitive).content)
        val map = Json.parseToJsonElement(FormatArchiveFixtures.text(result.getValue("data/Map001.json"))) as JsonObject
        assertEquals("Ville", (map["displayName"] as JsonPrimitive).content)
        val list = mapList(map)
        val firstLine = list[1] as JsonObject
        val secondLine = list[2] as JsonObject
        assertEquals(401, (firstLine["code"] as JsonPrimitive).intOrNull ?: -1)
        assertEquals("Bonjour", ((firstLine["parameters"] as JsonArray)[0] as JsonPrimitive).content)
        assertEquals("Monde", ((secondLine["parameters"] as JsonArray)[0] as JsonPrimitive).content)
        val choices = ((list[3] as JsonObject)["parameters"] as JsonArray)[0] as JsonArray
        assertEquals("Oui", (choices[0] as JsonPrimitive).content)
        assertEquals("Non", (choices[1] as JsonPrimitive).content)
        assertEquals("Oui",
            (((list[4] as JsonObject)["parameters"] as JsonArray)[1] as JsonPrimitive).content)
        assertEquals("Non",
            (((list[5] as JsonObject)["parameters"] as JsonArray)[1] as JsonPrimitive).content)
        val plugins = FormatArchiveFixtures.text(result.getValue("js/plugins.js"))
        assertTrue(plugins.contains("MyPlugin"))
        assertTrue(plugins.contains("Un plugin"))
        assertTrue(FormatArchiveFixtures.text(result.getValue("js/plugins/Extra.js")).contains("Texte extra"))
        for (name in listOf("img/pictures/pic.rpgmvp", "audio/theme.ogg", "js/rmmz_core.js")) {
            assertArrayEquals(name, input.getValue(name), result.getValue(name))
        }
        val reread = adapter.read(output, path)
        assertEquals("Héros", reread.units.first { it.tag == "data/Actors.json" && it.source == "Héros" }.source)
        assertEquals("Bonjour\nMonde",
            reread.units.first { it.tag == "data/Map001.json" && it.source == "Bonjour\nMonde" }.source)
    }

    @Test fun bilingualMessageBlockRebuildsEveryLineInSourceOrder() {
        val original = rpg()
        val document = adapter.read(original, path)
        val output = adapter.write(original, translated(document, mapOf(
            ("data/Map001.json" to "Hello\nWorld") to "Bonjour\nMonde",
        )), FormatWriteOptions(bilingual = true))
        val map = Json.parseToJsonElement(FormatArchiveFixtures.text(
            FormatArchiveFixtures.unzip(output).getValue("data/Map001.json"))) as JsonObject
        val list = mapList(map)
        val lines = (1..4).map { index ->
            val command = list[index] as JsonObject
            assertEquals(401, (command["code"] as JsonPrimitive).intOrNull ?: -1)
            assertEquals(0, (command["indent"] as JsonPrimitive).intOrNull ?: -1)
            ((command["parameters"] as JsonArray)[0] as JsonPrimitive).content
        }
        assertEquals(listOf("Hello", "World", "Bonjour", "Monde"), lines)
    }

    @Test fun writeWithoutProcessedUnitsReturnsTheOriginalArchive() {
        val original = rpg()
        val document = adapter.read(original, path)
        assertArrayEquals(original, adapter.write(original, document.units, FormatWriteOptions()))
    }

    @Test fun invalidJsonIsAWarningAndOtherDataFilesStillRead() {
        val document = adapter.read(rpg(actors = "{"), path)
        assertTrue(document.warnings.any { it.code == "rpgmaker.invalid_json" })
        assertEquals("Quest", unit(document, "data/System.json", "Quest").source)
    }

    @Test fun unterminatedScriptIsAWarningAndOtherPluginFilesStillRead() {
        val document = adapter.read(rpg(plugins = "var x = \"unterminated;"), path)
        assertTrue(document.warnings.any { it.code == "rpgmaker.script_syntax" })
        assertEquals(TextItemStatus.RULE_SKIPPED,
            unit(document, "js/plugins/Extra.js", "Extra text").status)
    }

    @Test fun wwwPrefixIsPreservedInTagsAndMvEngineIsDetected() {
        val original = rpg(prefix = "www/", engine = "mv")
        val document = adapter.read(original, path)
        assertEquals("MV", document.gameEngine)
        assertEquals("Hero", unit(document, "www/data/Actors.json", "Hero").source)
        val output = adapter.write(original, translated(document, mapOf(
            ("www/data/Actors.json" to "Hero") to "Héros",
        )), FormatWriteOptions())
        val result = FormatArchiveFixtures.unzip(output)
        val actors = Json.parseToJsonElement(FormatArchiveFixtures.text(result.getValue("www/data/Actors.json")))
        assertEquals("Héros", (((actors as JsonArray)[1] as JsonObject)["name"] as JsonPrimitive).content)
        assertTrue(!result.containsKey("data/Actors.json"))
    }

    @Test fun javascriptEscapingRoundTripsThroughTheSameScanner() {
        val original = rpg()
        val document = adapter.read(original, path)
        val target = "He said \"hi\"\nnext"
        val output = adapter.write(original, translated(document, mapOf(
            ("js/plugins.js" to "A plugin") to target,
        )), FormatWriteOptions())
        val reread = adapter.read(output, path)
        assertEquals(target, unit(reread, "js/plugins.js", target).source)
    }

    @Test fun malformedAndUnsafeArchivesFailClosed() {
        rejects(FormatContainerException.Reason.PATH) {
            adapter.read(FormatArchiveFixtures.zip(listOf("../evil" to byteArrayOf(1))), path)
        }
        rejects(FormatContainerException.Reason.STRUCTURE) {
            adapter.read(FormatArchiveFixtures.zip(listOf(
                "readme.txt" to "not a game".toByteArray())), path)
        }
    }

    @Test fun genericZipImportIsPromotedOnlyForRpgMakerProjects() {
        assertEquals(ProjectAssetFormat.RPGMAKER_MV_MZ, resolveImportFormat("game.zip", rpg()))
        assertEquals(ProjectAssetFormat.RPGMAKER_MV_MZ,
            resolveImportFormat("game.bin", rpg(prefix = "www/")))
        assertEquals(ProjectAssetFormat.EPUB, resolveImportFormat("book.epub", rpg()))
        assertEquals(ProjectAssetFormat.TXT, resolveImportFormat("notes.txt", rpg()))
        assertEquals(ProjectAssetFormat.BINARY, resolveImportFormat("game.zip",
            FormatArchiveFixtures.zip(listOf("readme.txt" to "not a game".toByteArray()))))
        assertEquals(ProjectAssetFormat.BINARY, resolveImportFormat("game.zip",
            byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x00)))
        assertTrue(RpgMakerArchiveSniffer.matches(rpg()))
        assertFalse(RpgMakerArchiveSniffer.matches(
            FormatArchiveFixtures.zip(listOf("readme.txt" to "not a game".toByteArray()))))
        assertFalse(RpgMakerArchiveSniffer.matches(
            FormatArchiveFixtures.zip(listOf("../evil" to byteArrayOf(1),
                "data/System.json" to "{}".toByteArray()))))
    }

    private fun unit(document: FormatDocument, tag: String, source: String): FormatUnit =
        document.units.first { it.tag == tag && it.source == source }

    private fun translated(
        document: FormatDocument,
        values: Map<Pair<String, String>, String>,
    ): List<FormatUnit> = document.units.map { unit ->
        val target = values[unit.tag to unit.source]
        if (target == null) unit else FormatUnit(unit.row, unit.source, target,
            TextItemStatus.PROCESSED, unit.tag, extraField = unit.extraField)
    }

    private fun mapList(map: JsonObject): JsonArray {
        val events = map["events"] as JsonArray
        val event = events[1] as JsonObject
        val pages = event["pages"] as JsonArray
        val page = pages[0] as JsonObject
        return page["list"] as JsonArray
    }

    private fun rejects(reason: FormatContainerException.Reason, block: () -> Unit) {
        try {
            block()
            fail("Accepted invalid RPG Maker archive")
        } catch (failure: FormatContainerException) {
            assertEquals(reason, failure.reason)
        }
    }

    private fun rpg(
        prefix: String = "",
        engine: String = "mz",
        actors: String = ACTORS,
        system: String = SYSTEM,
        map: String = MAP,
        plugins: String = PLUGINS,
    ): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries.add(prefix + "data/System.json" to system.toByteArray())
        entries.add(prefix + "data/Actors.json" to actors.toByteArray())
        entries.add(prefix + "data/Map001.json" to map.toByteArray())
        entries.add(prefix + "js/plugins.js" to plugins.toByteArray())
        entries.add(prefix + "js/plugins/Extra.js" to EXTRA_JS.toByteArray())
        if (engine == "mz") {
            entries.add(prefix + "js/rmmz_core.js" to "// engine marker\n".toByteArray())
        } else {
            entries.add(prefix + "js/rpg_core.js" to "// engine marker\n".toByteArray())
        }
        entries.add(prefix + "img/pictures/pic.rpgmvp" to "RPGMV-encrypted-media".toByteArray())
        entries.add(prefix + "audio/theme.ogg" to "OggS-media".toByteArray())
        return FormatArchiveFixtures.zip(entries)
    }

    private companion object {
        private const val ACTORS =
            """[null,{"name":"Hero","nickname":"H","profile":"A hero.","battlerName":"HeroBattler"}]"""

        private const val SYSTEM =
            """{"gameTitle":"Quest","currencyUnit":"G","terms":{"basic":["Attack","Defend"]},"hasEncryptedImages":true,"encryptionKey":"0123456789abcdef0123456789abcdef"}"""

        private const val MAP =
            """{"displayName":"Town","events":[null,{"id":1,"name":"EV001","pages":[{"list":[{"code":101,"indent":0,"parameters":[0,"",0,2,"Hero",0]},{"code":401,"indent":0,"parameters":["Hello"]},{"code":401,"indent":0,"parameters":["World"]},{"code":102,"indent":0,"parameters":[["Yes","No"]]},{"code":402,"indent":0,"parameters":[0,"Yes"]},{"code":402,"indent":0,"parameters":[1,"No"]},{"code":404,"indent":0,"parameters":[]},{"code":0,"indent":0,"parameters":[]}]}]}]}"""

        private val PLUGINS =
            "var \$plugins = [{\"name\":\"MyPlugin\",\"status\":true,\"description\":\"A plugin\"," +
                "\"parameters\":{\"Greeting\":\"Hi\"}}];"

        private const val EXTRA_JS = "TextManager.extra = \"Extra text\";"
    }
}
