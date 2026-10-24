package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.*
import org.junit.Test

class NativeModelSettingsTest {
    @Test fun credentialFlagAndKeyCountMustDescribeTheSameProfile() {
        val ref = ModelConfigurationRef("model", 1)
        assertEquals(0, NativeModelSettings(ref, "https://example.com", "m", false).keyCount)
        assertEquals(1, NativeModelSettings(ref, "https://example.com", "m", true).keyCount)
        assertEquals(NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
            NativeModelSettings(ref, "https://example.com", "m").protocol)
        assertThrows(IllegalArgumentException::class.java) {
            NativeModelSettings(ref, "https://example.com", "m", true, keyCount = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeModelSettings(ref, "https://example.com", "m", false, keyCount = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeModelSettings(ref, "https://example.com", "m", true, keyCount = NativeModelSettings.MAX_KEYS + 1)
        }
    }
}
