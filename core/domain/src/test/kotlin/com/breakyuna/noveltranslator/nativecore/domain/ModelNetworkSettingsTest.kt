package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelNetworkSettingsTest {
    private fun configured() = ModelNetworkSettings(
        connectTimeoutSeconds = 5,
        readTimeoutSeconds = 31,
        writeTimeoutSeconds = 7,
        callTimeoutSeconds = 45,
        proxy = ModelHttpProxy.of("Proxy.Local", 3128),
        followRedirects = true,
        source = ModelNetworkSettingsSource.SAVED,
    )

    @Test fun defaultsMatchTheHistoricalTransportAndStayDisabled() {
        val settings = ModelNetworkSettings.DEFAULT
        assertEquals(10, settings.connectTimeoutSeconds)
        assertEquals(90, settings.readTimeoutSeconds)
        assertEquals(10, settings.writeTimeoutSeconds)
        assertEquals(90, settings.callTimeoutSeconds)
        assertNull(settings.proxy)
        assertFalse(settings.followRedirects)
        assertEquals(ModelNetworkSettingsSource.DEFAULT, settings.source)
    }

    @Test fun timeoutsAreBoundedAndRejectedOutsideTheRange() {
        for (value in listOf(0, -1, ModelNetworkSettings.MAX_TIMEOUT_SECONDS + 1)) {
            assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettings(connectTimeoutSeconds = value) }
            assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettings(readTimeoutSeconds = value) }
            assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettings(writeTimeoutSeconds = value) }
            assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettings(callTimeoutSeconds = value) }
        }
        assertEquals(ModelNetworkSettings.MIN_TIMEOUT_SECONDS,
            ModelNetworkSettings(connectTimeoutSeconds = ModelNetworkSettings.MIN_TIMEOUT_SECONDS).connectTimeoutSeconds)
        assertEquals(ModelNetworkSettings.MAX_TIMEOUT_SECONDS,
            ModelNetworkSettings(callTimeoutSeconds = ModelNetworkSettings.MAX_TIMEOUT_SECONDS).callTimeoutSeconds)
    }

    @Test fun proxyRejectsSchemesUserInfoPathsQueriesAndInvalidPorts() {
        for (host in listOf(
            "", "   ", "http://proxy.local", "https://proxy.local", "user:pass@proxy.local",
            "proxy.local/path", "proxy.local?x=1", "proxy.local#fragment", "proxy.local:8080",
            "[::1]", "proxy local",
        )) {
            assertThrows(InvalidModelNetworkSettings::class.java) { ModelHttpProxy.of(host, 8080) }
        }
        for (port in listOf(0, -1, 65536)) {
            assertThrows(InvalidModelNetworkSettings::class.java) { ModelHttpProxy.of("proxy.local", port) }
        }
        val normalized = ModelHttpProxy.of("  Proxy.Local  ", 3128)
        assertEquals("proxy.local", normalized.host)
        assertEquals(3128, normalized.port)
    }

    @Test fun proxyAndSettingsToStringNeverExposeTheHost() {
        val proxy = ModelHttpProxy.of("private-proxy.local", 3128)
        assertFalse(proxy.toString().contains("private-proxy"))
        val settings = configured()
        assertFalse(settings.toString().contains("Proxy.Local"))
        assertTrue(settings.toString().contains("proxy=set"))
        assertFalse(ModelNetworkSettings.DEFAULT.toString().contains("proxy.local"))
    }

    @Test fun codecRoundTripsSavedSettingsAndProducesAStableFingerprint() {
        val saved = configured()
        val encoded = ModelNetworkSettingsCodec.encode(saved)
        val decoded = ModelNetworkSettingsCodec.decode(encoded)
        assertEquals(saved, decoded)
        assertEquals(ModelNetworkSettingsSource.SAVED, decoded.source)
        assertEquals(saved.proxy, decoded.proxy)
        assertEquals(ModelNetworkSettingsCodec.fingerprint(saved), ModelNetworkSettingsCodec.fingerprint(decoded))
        assertEquals(64, ModelNetworkSettingsCodec.fingerprint(saved).length)
        assertFalse(encoded.contains("apiKey"))
        assertFalse(encoded.contains("credential"))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("Authorization"))
    }

    @Test fun codecRejectsCredentialOrProxySecretFields() {
        val encoded = ModelNetworkSettingsCodec.encode(configured())
        val root = Json.parseToJsonElement(encoded).jsonObject
        val withRootSecret = buildJsonObject {
            root.forEach { (key, value) -> put(key, value) }
            put("apiKey", JsonPrimitive("secret"))
        }.toString()
        assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettingsCodec.decode(withRootSecret) }
        val proxy = root.getValue("proxy").jsonObject
        val proxyWithSecret = buildJsonObject {
            proxy.forEach { (key, value) -> put(key, value) }
            put("password", JsonPrimitive("secret"))
        }
        val withProxySecret = buildJsonObject {
            root.forEach { (key, value) -> put(key, if (key == "proxy") proxyWithSecret else value) }
        }.toString()
        assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettingsCodec.decode(withProxySecret) }
        val withUserInfo = buildJsonObject {
            root.forEach { (key, value) ->
                put(key, if (key == "proxy") buildJsonObject {
                    put("host", JsonPrimitive("user:pass@proxy.local"))
                    put("port", JsonPrimitive(3128))
                } else value)
            }
        }.toString()
        assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettingsCodec.decode(withUserInfo) }
    }

    @Test fun codecRejectsIllTypedOutOfRangeAndUnknownFields() {
        val encoded = ModelNetworkSettingsCodec.encode(configured())
        val cases = listOf(
            encoded.replace("\"connectTimeoutSeconds\":5", "\"connectTimeoutSeconds\":\"5\""),
            encoded.replace("\"connectTimeoutSeconds\":5", "\"connectTimeoutSeconds\":5.5"),
            encoded.replace("\"connectTimeoutSeconds\":5", "\"connectTimeoutSeconds\":0"),
            encoded.replace("\"connectTimeoutSeconds\":5", "\"connectTimeoutSeconds\":601"),
            encoded.replace("\"port\":3128", "\"port\":65536"),
            encoded.replace("\"followRedirects\":true", "\"followRedirects\":\"true\""),
            encoded.replace("\"proxy\":{", "\"proxyExtra\":{").replace("},\"followRedirects\"", "},\"followRedirects\""),
            encoded.replace("\"formatVersion\":1", "\"formatVersion\":2"),
            encoded.replace("\"format\":\"lnt-model-network-settings\"", "\"format\":\"other\""),
            encoded.dropLast(1) + ",\"extra\":true}",
            "broken",
        )
        for (case in cases) {
            assertThrows(InvalidModelNetworkSettings::class.java) { ModelNetworkSettingsCodec.decode(case) }
        }
    }

    @Test fun proxyCredentialReferenceRoundTripsAndLegacyPayloadStillDecodes() {
        val referenced = configured().copy(proxyCredentialRef = "proxy-ref-1")
        val encoded = ModelNetworkSettingsCodec.encode(referenced)
        assertTrue(encoded.contains("\"proxyCredentialRef\""))
        assertEquals(referenced, ModelNetworkSettingsCodec.decode(encoded))
        assertTrue(referenced.toString().contains("proxyCredential=set"))
        assertFalse(referenced.toString().contains("proxy-ref-1"))

        val root = Json.parseToJsonElement(encoded).jsonObject
        val legacy = buildJsonObject {
            root.forEach { (key, value) -> if (key != "proxyCredentialRef") put(key, value) }
        }.toString()
        val decodedLegacy = ModelNetworkSettingsCodec.decode(legacy)
        assertNull(decodedLegacy.proxyCredentialRef)
        assertEquals(referenced.copy(proxyCredentialRef = null), decodedLegacy)

        assertFalse(ModelNetworkSettingsCodec.encode(configured()).contains("proxyCredentialRef"))
        for (reference in listOf("", "has space", "user:pass@proxy", "user:pass", "user@proxy",
            "../escape", "..\\escape", "a".repeat(129))) {
            assertThrows(InvalidModelNetworkSettings::class.java) {
                ModelNetworkSettings(proxyCredentialRef = reference)
            }
        }
    }

    @Test fun proxyCredentialNeverExposesItsParts() {
        val credential = ModelProxyCredential.of("proxy-user", "proxy-password")
        assertFalse(credential.toString().contains("proxy-user"))
        assertFalse(credential.toString().contains("proxy-password"))
        for (value in listOf("", " leading", "trailing ", "line\nbreak", "non-ascii-é")) {
            assertThrows(IllegalArgumentException::class.java) { ModelProxyCredential.of(value, "password") }
            assertThrows(IllegalArgumentException::class.java) { ModelProxyCredential.of("user", value) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelProxyCredential.of("user:name", "password")
        }
        assertTrue(ModelProxyCredentialReference.isValid("proxy-ref_1"))
        assertTrue(ModelProxyCredentialReference.isValid("-proxy"))
        assertFalse(ModelProxyCredentialReference.isValid("has space"))
    }

    @Test fun codecRoundTripsDefaultAndSavedProvenance() {
        val encodedDefault = ModelNetworkSettingsCodec.encode(ModelNetworkSettings.DEFAULT)
        assertEquals(ModelNetworkSettings.DEFAULT, ModelNetworkSettingsCodec.decode(encodedDefault))
        val saved = configured()
        assertEquals(saved, ModelNetworkSettingsCodec.decode(ModelNetworkSettingsCodec.encode(saved)))
        assertThrows(InvalidModelNetworkSettings::class.java) {
            ModelNetworkSettingsCodec.decode(encodedDefault.replace("\"source\":\"DEFAULT\"", "\"source\":\"OTHER\""))
        }
    }

    @Test fun codecAcceptsExplicitNullProxyAndDisabledRedirects() {
        val plain = ModelNetworkSettings(source = ModelNetworkSettingsSource.SAVED)
        val encoded = ModelNetworkSettingsCodec.encode(plain)
        val root = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(JsonPrimitive(false), root["followRedirects"])
        val decoded = ModelNetworkSettingsCodec.decode(encoded)
        assertNull(decoded.proxy)
        assertFalse(decoded.followRedirects)
        val extra = buildJsonObject { root.forEach { (key, value) -> put(key, value) } }.toString()
        assertEquals(decoded, ModelNetworkSettingsCodec.decode(extra))
    }
}
