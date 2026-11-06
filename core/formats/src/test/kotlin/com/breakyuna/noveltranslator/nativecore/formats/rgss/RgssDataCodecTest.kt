package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RgssDataCodecTest {
    private val file = "Data/Actors.rvdata2"

    @Test fun extractsOnlyWhitelistedRpgAndEventText() {
        val actor = actor("Aria", nickname = "Ari", description = "A gentle knight.")
        actor.addIvar("@note", MarshalString.ofUtf8("<battle: 2>"))
        val commands = listOf(
            command(401, MarshalString.ofUtf8("Hello there")),
            command(
                102,
                MarshalArray(listOf(MarshalString.ofUtf8("Fight"), MarshalString.ofUtf8("Run"))),
                MarshalInt(1),
            ),
            command(402, MarshalInt(0), MarshalString.ofUtf8("Fight")),
            command(320, MarshalInt(1), MarshalString.ofUtf8("Aria")),
            command(101, MarshalString.ofUtf8("Face01"), MarshalInt(0), MarshalInt(0), MarshalInt(2)),
            command(241, MarshalString.ofUtf8("Theme"), MarshalInt(100), MarshalInt(100)),
            command(355, MarshalString.ofUtf8("\$gameVariables[1] = 2")),
        )
        val elements = ArrayList<MarshalValue>()
        elements.add(actor)
        elements.addAll(commands)
        elements.add(MarshalSymbol.of("SymbolName"))
        val root = MarshalArray(elements)
        val scan = RgssDataCodec.scan(RubyMarshal.serialize(root), file)

        assertEquals("UTF-8", scan.encoding)
        assertEquals(
            setOf("Aria", "Ari", "A gentle knight.", "Hello there", "Fight", "Run"),
            scan.strings.map { it.source }.toSet(),
        )
        assertEquals(8, scan.strings.size)
        assertTrue(scan.strings.none { it.source == "<battle: 2>" })
        assertTrue(scan.strings.none { it.source == "Face01" })
        assertTrue(scan.strings.none { it.source == "Theme" })
        assertTrue(scan.strings.none { it.source == "\$gameVariables[1] = 2" })
        assertTrue(scan.strings.any { it.path == "root[0].@name" })
        assertTrue(scan.strings.any { it.path == "root[1].@parameters[0]" })
        assertTrue(scan.strings.any { it.path == "root[2].@parameters[0][0]" })
        assertTrue(scan.strings.any { it.path == "root[4].@parameters[1]" })
    }

    @Test fun rpgControlCodesAreNotMistakenForPaths() {
        val text = "\\C[1]Aria\\C[0] asks: Yes/No to \\N[1]."
        val scan = RgssDataCodec.scan(data(command(401, MarshalString.ofUtf8(text))), file)
        assertEquals(text, scan.strings.single().source)
    }

    @Test fun skipsValuesThatAreNotDisplayText() {
        val ascii = MarshalString.ofUtf8("ASCII text")
        ascii.addIvar("E", MarshalBool(false))
        val names = listOf("Aria", "", "UTF-8", "RPG::Actor", "Sword.png", "12345", "   ", "%s", "Data/Actors.rxdata")
        val root = MarshalArray(
            names.map { actor(it) } +
                MarshalObject("RPG::Actor").apply {
                    addIvar("@note", MarshalString.ofUtf8("<common_event: 1>"))
                } +
                MarshalObject("RPG::Actor").apply { addIvar("@name", ascii) },
        )
        val scan = RgssDataCodec.scan(RubyMarshal.serialize(root), file)

        assertEquals(listOf("Aria"), scan.strings.map { it.source })
        assertTrue(scan.skipped >= 1)
    }

    @Test fun sharedStringIsExtractedOnceAndBothLinksChange() {
        val shared = MarshalString.ofUtf8("Aria")
        val actor = MarshalObject("RPG::Actor")
        actor.addIvar("@name", shared)
        val original = RubyMarshal.serialize(MarshalArray(listOf(shared, shared, actor)))
        val scan = RgssDataCodec.scan(original, file)
        val unit = scan.strings.single()
        assertEquals("root[2].@name", unit.path)

        val output = RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア")))
        val root = RubyMarshal.parse(output) as MarshalArray
        assertSame(root.elements[0], root.elements[1])
        val parsedActor = root.elements[2] as MarshalObject
        val name = parsedActor.ivars.first { it.name == "@name" }.value as MarshalString
        assertSame(root.elements[0], name)
        assertEquals("アリア", name.utf8)
    }

    @Test fun opaqueUserDataDoesNotBlockMapEventTextOrChangeOnWrite() {
        val map = MarshalObject("RPG::Map")
        map.addIvar("@data", MarshalUserData("Table", byteArrayOf(1, 2, 3, 4)))
        map.addIvar("@events", MarshalArray(listOf(command(401, MarshalString.ofUtf8("Hello map")))))
        val original = data(map)
        val unit = RgssDataCodec.scan(original, "Data/Map001.rvdata2").strings.single()
        assertEquals("Hello map", unit.source)
        assertEquals("root[0].@events[0].@parameters[0]", unit.path)

        val output = RgssDataCodec.rewrite(original, "Data/Map001.rvdata2", listOf(edit(unit, "Bonjour")))
        val parsed = (RubyMarshal.parse(output) as MarshalArray).elements[0] as MarshalObject
        val table = parsed.ivars.first { it.name == "@data" }.value as MarshalUserData
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), table.bytes)
        val events = parsed.ivars.first { it.name == "@events" }.value as MarshalArray
        val command = events.elements[0] as MarshalObject
        val parameters = command.ivars.first { it.name == "@parameters" }.value as MarshalArray
        assertEquals("Bonjour", (parameters.elements[0] as MarshalString).utf8)
    }

    @Test fun sharedObjectContainingTextIsExtractedOnce() {
        val actor = actor("Aria")
        val original = RubyMarshal.serialize(MarshalArray(listOf(actor, actor)))
        val scan = RgssDataCodec.scan(original, file)
        val unit = scan.strings.single()
        assertEquals("root[0].@name", unit.path)

        val output = RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア")))
        val root = RubyMarshal.parse(output) as MarshalArray
        assertSame(root.elements[0], root.elements[1])
        assertEquals("アリア", name(root.elements[0]))
        assertEquals("アリア", name(root.elements[1]))
    }

    @Test fun duplicateTextAtDifferentNodesIsIndependent() {
        val original = data(actor("Aria"), actor("Aria"))
        val scan = RgssDataCodec.scan(original, file)
        assertEquals(2, scan.strings.size)
        assertEquals(2, scan.strings.map { it.node }.toSet().size)
        val first = scan.strings.first { it.path == "root[0].@name" }

        val output = RgssDataCodec.rewrite(original, file, listOf(edit(first, "アリア")))
        val root = RubyMarshal.parse(output) as MarshalArray
        assertEquals("アリア", name(root.elements[0]))
        assertEquals("Aria", name(root.elements[1]))
    }

    @Test fun rewriteSplicesOnlyTheStringBody() {
        val original = data(
            actor("Aria", nickname = "Ari", description = "A gentle knight."),
            actor("Borin", nickname = "Bo", description = "A quiet mage."),
        )
        val unit = RgssDataCodec.scan(original, file).strings.first { it.source == "Aria" }
        val output = RgssDataCodec.rewrite(original, file, listOf(edit(unit, "Aris")))

        val needle = "Aria".toByteArray(Charsets.UTF_8)
        val replacement = "Aris".toByteArray(Charsets.UTF_8)
        val index = indexOf(original, needle)
        assertTrue(index > 0)
        assertArrayEquals(original.copyOfRange(0, index), output.copyOfRange(0, index))
        assertArrayEquals(
            original.copyOfRange(index + needle.size, original.size),
            output.copyOfRange(index + replacement.size, output.size),
        )
        assertEquals(original.size, output.size)
        val root = RubyMarshal.parse(output) as MarshalArray
        assertEquals("Aris", name(root.elements[0]))
        assertEquals("Ari", nickname(root.elements[0]))
        assertEquals("A gentle knight.", description(root.elements[0]))
        assertEquals("Borin", name(root.elements[1]))
        assertEquals("A quiet mage.", description(root.elements[1]))
    }

    @Test fun multipleEditsInOneFileAreAppliedTogether() {
        val original = data(
            actor("Aria", description = "Knight"),
            actor("Borin", description = "Mage"),
        )
        val unit = RgssDataCodec.scan(original, file).strings
        val output = RgssDataCodec.rewrite(
            original,
            file,
            listOf(
                edit(unit.first { it.source == "Aria" }, "アリア"),
                edit(unit.first { it.source == "Knight" }, "騎士"),
                edit(unit.first { it.source == "Mage" }, "魔法使い"),
            ),
        )
        val root = RubyMarshal.parse(output) as MarshalArray
        assertEquals("アリア", name(root.elements[0]))
        assertEquals("騎士", description(root.elements[0]))
        assertEquals("Borin", name(root.elements[1]))
        assertEquals("魔法使い", description(root.elements[1]))
    }

    @Test fun longTranslationRewritesTheMarshalLengthPrefix() {
        val original = data(actor("Aria"))
        val unit = RgssDataCodec.scan(original, file).strings.single()
        val translation = "あ".repeat(200)
        val output = RgssDataCodec.rewrite(original, file, listOf(edit(unit, translation)))
        val parsed = (RubyMarshal.parse(output) as MarshalArray).elements[0] as MarshalObject
        val parsedName = parsed.ivars.first { it.name == "@name" }.value as MarshalString
        assertEquals(translation, parsedName.utf8)
        assertArrayEquals(translation.toByteArray(Charsets.UTF_8), parsedName.bytes)
        assertTrue(output.size > original.size)
    }

    @Test fun stringEncodingIvarAndUnchangedValuesArePreserved() {
        val name = MarshalString.ofUtf8("Aria")
        name.addIvar("E", MarshalBool(true))
        val actor = MarshalObject("RPG::Actor")
        actor.addIvar("@name", name)
        actor.addIvar("@description", MarshalString.ofUtf8("A gentle knight."))
        val original = data(actor)
        val unit = RgssDataCodec.scan(original, file).strings.single { it.source == "Aria" }
        assertEquals("UTF-8", unit.encoding)

        val output = RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア")))
        val parsed = (RubyMarshal.parse(output) as MarshalArray).elements[0] as MarshalObject
        val parsedName = parsed.ivars.first { it.name == "@name" }.value as MarshalString
        assertEquals("UTF-8", parsedName.encodingName())
        assertEquals("アリア", parsedName.utf8)
        assertEquals("A gentle knight.", description(parsed))
    }

    @Test fun legacyShiftJisIsDetectedAndWrittenBackInShiftJis() {
        val shiftJis = Charset.forName("Shift_JIS")
        val first = MarshalObject("RPG::Actor")
        first.addIvar("@name", MarshalString.ofBytes("薬".toByteArray(shiftJis)))
        val second = MarshalObject("RPG::Actor")
        // "ソ" contains the 0x5C trail byte, which must not be mistaken for a path separator.
        second.addIvar("@name", MarshalString.ofBytes("ソード".toByteArray(shiftJis)))
        val original = data(first, second)
        val scan = RgssDataCodec.scan(original, "Data/Items.rxdata")
        assertEquals("Shift_JIS", scan.encoding)
        assertEquals(setOf("薬", "ソード"), scan.strings.map { it.source }.toSet())
        val unit = scan.strings.first { it.source == "薬" }
        assertEquals("Shift_JIS", unit.encoding)

        val output = RgssDataCodec.rewrite(
            original,
            "Data/Items.rxdata",
            listOf(edit(unit, "回復薬")),
        )
        val root = RubyMarshal.parse(output) as MarshalArray
        val parsedName = (root.elements[0] as MarshalObject)
            .ivars.first { it.name == "@name" }.value as MarshalString
        assertArrayEquals("回復薬".toByteArray(shiftJis), parsedName.bytes)
        val untouched = (root.elements[1] as MarshalObject)
            .ivars.first { it.name == "@name" }.value as MarshalString
        assertArrayEquals("ソード".toByteArray(shiftJis), untouched.bytes)
    }

    @Test fun vxAceNeverInfersShiftJisFromMislabeledBytes() {
        val shiftJis = Charset.forName("Shift_JIS")
        val actor = MarshalObject("RPG::Actor")
        actor.addIvar("@name", MarshalString.ofBytes("薬".toByteArray(shiftJis)))
        val scan = RgssDataCodec.scan(data(actor), "Data/Items.rvdata2")
        assertEquals("UTF-8", scan.encoding)
        assertTrue(scan.strings.isEmpty())
        assertTrue(scan.skipped >= 1)
    }

    @Test fun asciiOnlyLegacyDataIsAmbiguousButVxAceDefaultsToUtf8() {
        val original = data(actor("Potion"))
        val legacy = RgssDataCodec.scan(original, "Data/Items.rxdata")
        assertTrue(legacy.ambiguousEncoding)
        assertTrue(legacy.strings.isEmpty())
        assertNull(legacy.encoding)

        val vxAce = RgssDataCodec.scan(original, "Data/Items.rvdata2")
        assertEquals("UTF-8", vxAce.encoding)
        assertEquals("Potion", vxAce.strings.single().source)
    }

    @Test fun rewriteRejectsStaleOrMismatchedLocators() {
        val original = data(actor("Aria", description = "A gentle knight."))
        val unit = RgssDataCodec.scan(original, file).strings.first { it.source == "Aria" }

        assertEquals(
            RgssDataException.Reason.BINDING,
            failure { RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア", node = unit.node + 1))) }.reason,
        )
        assertEquals(
            RgssDataException.Reason.BINDING,
            failure { RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア", path = "root[9]"))) }.reason,
        )
        assertEquals(
            RgssDataException.Reason.BINDING,
            failure {
                RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア", digest = "0".repeat(64))))
            }.reason,
        )
        assertEquals(
            RgssDataException.Reason.BINDING,
            failure { RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア", source = "Other"))) }.reason,
        )
        assertEquals(
            RgssDataException.Reason.BINDING,
            failure {
                RgssDataCodec.rewrite(original, file, listOf(edit(unit, "アリア", encoding = "Shift_JIS")))
            }.reason,
        )
        assertEquals(
            RgssDataException.Reason.BINDING,
            failure {
                val another = edit(unit, "アリア")
                RgssDataCodec.rewrite(original, file, listOf(another, another))
            }.reason,
        )

        val changed = data(actor("Bob", description = "A gentle knight."))
        assertEquals(
            RgssDataException.Reason.BINDING,
            failure { RgssDataCodec.rewrite(changed, file, listOf(edit(unit, "アリア"))) }.reason,
        )
    }

    @Test fun corruptFilesAndBudgetsAreRejected() {
        val original = data(actor("Aria", description = "A gentle knight."))
        assertEquals(
            RgssDataException.Reason.MARSHAL,
            failure { RgssDataCodec.scan(original + byteArrayOf(0), file) }.reason,
        )
        assertEquals(
            RgssDataException.Reason.MARSHAL,
            failure { RgssDataCodec.scan(RgssFixtures.bytes(4, 8, 0x75), file) }.reason,
        )
        assertEquals(
            RgssDataException.Reason.LIMIT,
            failure {
                RgssDataCodec.scan(
                    original,
                    file,
                    RgssDataLimits(marshal = MarshalLimits(maxNodes = 1)),
                )
            }.reason,
        )
        assertEquals(
            RgssDataException.Reason.LIMIT,
            failure { RgssDataCodec.scan(original, file, RgssDataLimits(maxSteps = 1)) }.reason,
        )
        assertEquals(
            RgssDataException.Reason.LIMIT,
            failure {
                RgssDataCodec.scan(original, file, RgssDataLimits(maxUnits = 1))
            }.reason,
        )

        val unit = RgssDataCodec.scan(original, file).strings.first { it.source == "Aria" }
        assertEquals(
            RgssDataException.Reason.LIMIT,
            failure {
                RgssDataCodec.rewrite(
                    original,
                    file,
                    listOf(edit(unit, "アリア")),
                    RgssDataLimits(maxOutputBytes = original.size),
                )
            }.reason,
        )
    }

    @Test fun unencodableTranslationRejectsTheWholeFile() {
        val shiftJis = Charset.forName("Shift_JIS")
        val actor = MarshalObject("RPG::Actor")
        actor.addIvar("@name", MarshalString.ofBytes("薬".toByteArray(shiftJis)))
        val original = data(actor)
        val unit = RgssDataCodec.scan(original, "Data/Items.rxdata").strings.single()
        assertEquals(
            RgssDataException.Reason.ENCODING,
            failure {
                RgssDataCodec.rewrite(original, "Data/Items.rxdata", listOf(edit(unit, "😀")))
            }.reason,
        )
    }

    @Test fun noOpRewriteAndCancellationHooksBehave() {
        val original = data(actor("Aria"))
        assertArrayEquals(original, RgssDataCodec.rewrite(original, file, emptyList()))

        val unit = RgssDataCodec.scan(original, file).strings.single()
        assertArrayEquals(original, RgssDataCodec.rewrite(original, file, listOf(edit(unit, "Aria"))))

        var calls = 0
        RgssDataCodec.scan(original, file) { calls += 1 }
        assertTrue(calls >= 2)

        assertEquals(
            RgssDataException.Reason.LIMIT,
            failure {
                RgssDataCodec.scan(original, file) {
                    throw RgssDataException(RgssDataException.Reason.LIMIT, "cancelled")
                }
            }.reason,
        )
    }

    private fun edit(
        string: RgssDataString,
        translation: String,
        node: Int = string.node,
        path: String = string.path,
        source: String = string.source,
        digest: String = string.digest,
        encoding: String = string.encoding,
    ): RgssDataEdit = RgssDataEdit(node, path, source, digest, encoding, translation)

    private fun data(vararg values: MarshalValue): ByteArray =
        RubyMarshal.serialize(MarshalArray(values.toList()))

    private fun actor(
        name: String,
        nickname: String? = null,
        description: String? = null,
    ): MarshalObject {
        val value = MarshalObject("RPG::Actor")
        value.addIvar("@name", MarshalString.ofUtf8(name))
        nickname?.let { value.addIvar("@nickname", MarshalString.ofUtf8(it)) }
        description?.let { value.addIvar("@description", MarshalString.ofUtf8(it)) }
        return value
    }

    private fun command(code: Int, vararg parameters: MarshalValue): MarshalObject {
        val value = MarshalObject("RPG::EventCommand")
        value.addIvar("@code", MarshalInt(code))
        value.addIvar("@indent", MarshalInt(0))
        value.addIvar("@parameters", MarshalArray(parameters.toList()))
        return value
    }

    private fun name(value: MarshalValue): String? =
        nameOf(value as MarshalObject, "@name")?.utf8

    private fun nickname(value: MarshalValue): String? =
        nameOf(value as MarshalObject, "@nickname")?.utf8

    private fun description(value: MarshalValue): String? =
        nameOf(value as MarshalObject, "@description")?.utf8

    private fun nameOf(value: MarshalObject, ivar: String): MarshalString? =
        value.ivars.firstOrNull { it.name == ivar }?.value as? MarshalString

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        for (start in 0..haystack.size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private fun failure(block: () -> Unit): RgssDataException {
        try {
            block()
        } catch (failure: RgssDataException) {
            return failure
        }
        fail("Expected RgssDataException")
        throw AssertionError("unreachable")
    }
}
