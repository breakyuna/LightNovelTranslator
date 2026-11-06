package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NativeModelSettingsValidationTest {
    @Test fun acceptsHttpsBaseWithPath() {
        NativeModelSettingsValidation.validate(" https://example.com/v1/ ", "model", "fixture-key")
    }

    @Test fun rejectsUnsafeEndpointAndKeyWithoutExposingSubmittedValues() {
        for (endpoint in listOf("ftp://example.com", "https://user@example.com",
            "https://example.com?token=private", "https://example.com#fragment",
            "https://example.com/" + "x".repeat(2048))) {
            val failure = assertThrows(InvalidModelSettings::class.java) {
                NativeModelSettingsValidation.validate(endpoint, "model", "fixture-key")
            }
            assertFalse(failure.stackTraceToString().contains(endpoint))
            assertFalse(failure.stackTraceToString().contains("fixture-key"))
        }
        for (key in listOf("has space", "非ASCII", "line\nfeed", "x".repeat(8193))) {
            val failure = assertThrows(InvalidModelSettings::class.java) {
                NativeModelSettingsValidation.validate("https://example.com", "model", key)
            }
            assertFalse(failure.stackTraceToString().contains(key))
        }
    }

    @Test fun localHttpAndLanEndpointsDoNotRequireAKey() {
        for (endpoint in listOf("http://127.0.0.1:11434/v1", "http://localhost:1234/v1/", "http://192.168.1.10:8080/v1", "http://[::1]:8080/v1")) {
            NativeModelSettingsValidation.validate(endpoint, "local-model", "")
        }
    }

    @Test fun pastedOperationUrlsAreConvertedOnceBeforeBuildingRequestUrls() {
        for ((input, expected) in listOf(
            " http://localhost:1234/v1/chat/completions/ " to "http://localhost:1234/v1",
            "https://example.com/proxy/v1/models" to "https://example.com/proxy/v1",
            "http://[::1]:11434/v1" to "http://[::1]:11434/v1",
            "http://localhost:8080/chat/completions" to "http://localhost:8080",
            "http://models" to "http://models",
            "https://example.com/api/models/chat/completions" to "https://example.com/api/models",
            "https://example.com/api/chat/completions/models" to "https://example.com/api/chat/completions",
        )) {
            val base = NativeModelEndpoint.normalize(input)
            assertEquals(expected, base)
            assertEquals(expected, NativeModelEndpoint.base(base))
            assertEquals(expected + "/models", NativeModelEndpoint.models(base))
            assertEquals(expected + "/chat/completions", NativeModelEndpoint.chat(base))
            // The settings form reopens with an explicit operation URL, preserving the base.
            assertEquals(base, NativeModelEndpoint.normalize(NativeModelEndpoint.chat(base)))
        }
    }

    @Test fun invalidPortsQueriesAndEmbeddedCredentialsAreRejectedBeforeNetwork() {
        for (endpoint in listOf("http://localhost:0/v1", "http://localhost:65536/v1", "http://localhost:/v1",
            "http://user:secret@localhost:11434/v1", "http://localhost/v1?key=secret", "http://localhost/v1#x", "http://localhost/../v1")) {
            assertThrows(InvalidModelSettings::class.java) { NativeModelEndpoint.normalize(endpoint) }
        }
    }
}
