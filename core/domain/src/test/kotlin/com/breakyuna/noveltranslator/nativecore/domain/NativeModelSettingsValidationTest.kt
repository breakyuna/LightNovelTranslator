package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertThrows
import org.junit.Test

class NativeModelSettingsValidationTest {
    @Test fun acceptsHttpsBaseWithPath() {
        NativeModelSettingsValidation.validate(" https://example.com/v1/ ", "model", "fixture-key")
    }

    @Test fun rejectsUnsafeEndpointAndKeyWithoutExposingSubmittedValues() {
        for (endpoint in listOf("http://example.com", "https://user@example.com",
            "https://example.com?token=private", "https://example.com#fragment",
            "https://example.com/" + "x".repeat(2048))) {
            assertThrows(InvalidModelSettings::class.java) {
                NativeModelSettingsValidation.validate(endpoint, "model", "fixture-key")
            }
        }
        for (key in listOf("", "has space", "非ASCII", "line\nfeed", "x".repeat(8193))) {
            assertThrows(InvalidModelSettings::class.java) {
                NativeModelSettingsValidation.validate("https://example.com", "model", key)
            }
        }
    }
}
