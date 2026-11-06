package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LaboratorySettingsTest {
    @Test
    fun `codec round trips every switch`() {
        val settings = LaboratorySettings(cleanRuby = true, promptEnhancement = true,
            duplicateSourceDeduplication = true)
        assertEquals(settings, LaboratorySettingsCodec.decode(LaboratorySettingsCodec.encode(settings)))
        assertNotEquals(LaboratorySettingsCodec.fingerprint(settings),
            LaboratorySettingsCodec.fingerprint(LaboratorySettings.DEFAULT))
    }

    @Test
    fun `unknown or missing fields fail closed`() {
        for (text in listOf(
            "{}",
            """{"format":"lnt-laboratory-settings","formatVersion":1,"cleanRuby":true}""",
            """{"format":"lnt-laboratory-settings","formatVersion":1,"cleanRuby":true,"promptEnhancement":false,"duplicateSourceDeduplication":false,"extra":true}""",
            """{"format":"other","formatVersion":1,"cleanRuby":true,"promptEnhancement":false,"duplicateSourceDeduplication":false}""",
            """{"format":"lnt-laboratory-settings","formatVersion":2,"cleanRuby":true,"promptEnhancement":false,"duplicateSourceDeduplication":false}""",
            """{"format":"lnt-laboratory-settings","formatVersion":1,"cleanRuby":"yes","promptEnhancement":false,"duplicateSourceDeduplication":false}""",
        )) {
            try {
                LaboratorySettingsCodec.decode(text)
                throw AssertionError("Expected InvalidLaboratorySettings for $text")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }
}
