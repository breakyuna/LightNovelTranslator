package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class ManualNameEditTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))
    private val base = ObjectFingerprint("a".repeat(64))

    private fun command(
        commandId: String = "name-command",
        identity: LocalProjectRef = ref,
        itemId: Long = 1,
        revision: Long = 0,
        fingerprint: ObjectFingerprint = base,
        nameTranslation: List<String> = listOf("爱丽丝"),
        nameSource: List<String>? = null,
    ) = ManualNameEdit(CommandId(commandId), identity, ItemId(itemId), revision, fingerprint,
        nameTranslation, nameSource)

    @Test fun digestBindsIdentityRevisionBaseAndEveryNameValue() {
        val original = command()
        assertEquals(original.digest(), command().digest())
        assertEquals(64, original.digest().length)
        val changed = listOf(
            command(commandId = "other-command"),
            command(identity = ref.copy(generation = RecoveryGeneration(2))),
            command(itemId = 2),
            command(revision = 1),
            command(fingerprint = ObjectFingerprint("b".repeat(64))),
            command(nameTranslation = listOf("艾丽丝")),
            command(nameTranslation = listOf("爱丽丝", "鲍勃")),
            command(nameTranslation = emptyList()),
            command(nameSource = listOf("Alice")),
            command(nameSource = emptyList()),
        )
        changed.forEach { assertNotEquals(original.digest(), it.digest()) }
    }

    @Test fun nameDigestNeverReusesTheLegacyTextEditV1Encoding() {
        // The v1 text-edit encoding is frozen. This literal documents its exact JSON bytes.
        val text = ManualTextEdit(CommandId("command"), ref, ItemId(1), 0,
            ObjectFingerprint("0".repeat(64)), "body")
        val legacyJson = """{"version":1,"command":"command","project":"project","instance":"instance",""" +
            """"generation":1,"item":1,"revision":0,""" +
            """"base":"0000000000000000000000000000000000000000000000000000000000000000",""" +
            """"translation":"body","status":"PROCESSED"}"""
        assertEquals(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(legacyJson)),
            TextProjectFingerprint.command(text))
        assertNotEquals(TextProjectFingerprint.command(text),
            TextProjectFingerprint.nameCommand(
                command(commandId = "command", fingerprint = ObjectFingerprint("0".repeat(64)))))
    }

    @Test fun payloadIsBoundedAndRejectsNulOrInvalidUnicode() {
        assertThrows(IllegalArgumentException::class.java) {
            command(nameTranslation = List(ManualNameEdit.MAX_NAMES + 1) { "name" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(nameTranslation = listOf("n".repeat(ManualNameEdit.MAX_NAME_CHARS + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(nameTranslation = List(5) { "n".repeat(ManualNameEdit.MAX_NAME_CHARS) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(nameTranslation = List(ManualNameEdit.MAX_NAMES + 1) { "n" }, nameSource = listOf("x"))
        }
        assertThrows(IllegalArgumentException::class.java) { command(nameTranslation = listOf("a\u0000b")) }
        assertThrows(IllegalArgumentException::class.java) { command(nameSource = listOf("a\u0000b")) }
        assertThrows(IllegalArgumentException::class.java) { command(revision = -1) }
        try {
            command(nameTranslation = listOf("\ud800"))
            fail("Accepted an isolated surrogate")
        } catch (error: TextProjectException) {
            assertEquals(TextProjectException.Reason.INVALID_TEXT, error.reason)
        }
    }

    @Test fun commandOwnsASnapshotOfItsNameLists() {
        val translation = mutableListOf("爱丽丝")
        val source = mutableListOf("Alice")
        val edit = command(nameTranslation = translation, nameSource = source)
        translation.clear(); source.clear()
        assertEquals(listOf("爱丽丝"), edit.nameTranslation)
        assertEquals(listOf("Alice"), edit.nameSource)
        assertFalse(edit.toString().contains("爱丽丝"))
    }
}
