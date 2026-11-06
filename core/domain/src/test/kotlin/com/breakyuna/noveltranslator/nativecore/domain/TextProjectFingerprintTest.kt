package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class TextProjectFingerprintTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"), RecoveryGeneration(1))

    @Test fun itemFingerprintIncludesSourceTranslationStatusAndStableLocation() {
        val fingerprint = TextProjectFingerprint.item(ItemId(1), 0, "source", "", TextItemStatus.NONE)
        assertNotEquals(fingerprint, TextProjectFingerprint.item(ItemId(1), 0, "source", "", TextItemStatus.PROCESSED))
        assertNotEquals(fingerprint, TextProjectFingerprint.item(ItemId(2), 0, "source", "", TextItemStatus.NONE))
        assertNotEquals(fingerprint, TextProjectFingerprint.item(ItemId(1), 1, "source", "", TextItemStatus.NONE))
        assertNotEquals(fingerprint, TextProjectFingerprint.item(ItemId(1), 0, "different", "", TextItemStatus.NONE))
        assertNotEquals(fingerprint, TextProjectFingerprint.item(ItemId(1), 0, "source", "translation", TextItemStatus.NONE))
    }

    @Test fun commandFingerprintBindsIdentityGenerationRevisionAndExactBody() {
        val base = ObjectFingerprint("0".repeat(64))
        fun command(local: LocalProjectRef = ref, revision: Long = 0, text: String = "body") =
            ManualTextEdit(CommandId("command"), local, ItemId(1), revision, base, text)
        val fingerprint = TextProjectFingerprint.command(command())
        assertNotEquals(fingerprint, TextProjectFingerprint.command(command(ref.copy(generation = RecoveryGeneration(2)))))
        assertNotEquals(fingerprint, TextProjectFingerprint.command(command(revision = 1)))
        assertNotEquals(fingerprint, TextProjectFingerprint.command(command(text = "body\n")))
        assertEquals(fingerprint, TextProjectFingerprint.command(command()))
    }

    @Test fun isolatedSurrogateIsNotSilentlyReplaced() {
        try { TextProjectFingerprint.utf8("\ud800"); fail("Expected invalid Unicode") }
        catch (error: TextProjectException) { assertEquals(TextProjectException.Reason.INVALID_TEXT, error.reason) }
    }
}
