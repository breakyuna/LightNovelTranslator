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
        assertNotEquals(original.digest(), command(revision = 1).digest())
        assertNotEquals(original.digest(), command(nameTranslation = listOf("艾丽丝")).digest())
        assertNotEquals(original.digest(), command(nameSource = listOf("Alice")).digest())
        assertFalse(original.toString().contains("爱丽丝"))
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
}
