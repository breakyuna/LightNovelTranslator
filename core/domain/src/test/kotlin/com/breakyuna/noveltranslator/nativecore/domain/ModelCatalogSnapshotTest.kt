package com.breakyuna.noveltranslator.nativecore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogSnapshotTest {
    private fun snapshot(fetchedAt: Long = 10, descriptors: List<ModelDescriptor> = emptyList()) =
        ModelCatalogSnapshot("https://example.com/v1", NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
            fetchedAt, descriptors)

    @Test fun versionedCodecRoundTripsEmptyOverridesAndExplicitMetadata() {
        val empty = ModelCatalogSnapshot("https://example.com/v1", NativeModelProtocol.GOOGLE_GEMINI, 11)
        val described = ModelCatalogSnapshot("https://example.com/v1", NativeModelProtocol.OPENAI_CHAT_COMPLETIONS,
            10, listOf(ModelDescriptor("m", "Model M", 4096, 512, listOf("TEXT"), listOf("generateContent"))))
        val decoded = ModelCatalogCodec.decode(ModelCatalogCodec.encode(listOf(empty, described)))
        assertEquals(2, decoded.size)
        assertEquals(empty, decoded.single { it.protocol == NativeModelProtocol.GOOGLE_GEMINI })
        assertEquals(described, decoded.single { it.protocol == NativeModelProtocol.OPENAI_CHAT_COMPLETIONS })
        assertTrue(decoded.single { it.protocol == NativeModelProtocol.GOOGLE_GEMINI }.descriptors.isEmpty())
    }

    @Test fun fingerprintCoversMetadataAndEmptyIsNotAbsent() {
        assertNotEquals(snapshot(10).fingerprint, snapshot(11).fingerprint)
        assertNotEquals(snapshot(10).fingerprint,
            snapshot(10, listOf(ModelDescriptor("m"))).fingerprint)
        assertNotEquals(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8("not-a-catalog")),
            snapshot(10).fingerprint)
    }

    @Test fun strictDecodeRejectsUnknownOrTamperedFacts() {
        val encoded = ModelCatalogCodec.encode(listOf(snapshot(10, listOf(ModelDescriptor("m")))))
        assertThrows(InvalidModelCatalog::class.java) {
            ModelCatalogCodec.decode(encoded.dropLast(1) + ",\"extra\":true}")
        }
        assertThrows(InvalidModelCatalog::class.java) {
            ModelCatalogCodec.decode(encoded.replace("\"fetchedAtMillis\":10", "\"fetchedAtMillis\":11"))
        }
        assertThrows(InvalidModelCatalog::class.java) {
            ModelCatalogCodec.decode(encoded.replace("\"formatVersion\":1", "\"formatVersion\":1.0"))
        }
        assertEquals(1, ModelCatalogCodec.decode(encoded).size)
    }

    @Test fun descriptorsFreezeAndDeduplicateExplicitLists() {
        val descriptor = ModelDescriptor("m", modalities = listOf("TEXT", "TEXT", "IMAGE"))
        assertEquals(listOf("TEXT", "IMAGE"), descriptor.modalities)
        assertThrows(IllegalArgumentException::class.java) { ModelDescriptor("m", contextTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { ModelDescriptor("m", displayName = "bad\u0000name") }
    }
}
