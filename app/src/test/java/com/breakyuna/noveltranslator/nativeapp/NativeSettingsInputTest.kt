package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelRequestLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static form validation must mirror the domain constructors before any store write. */
class NativeSettingsInputTest {
    private fun network(
        connect: String = "10",
        read: String = "90",
        write: String = "10",
        call: String = "90",
        host: String = "",
        port: String = "",
        redirects: Boolean = false,
    ) = NativeNetworkSettingsInput(connect, read, write, call, host, port, redirects)

    @Test fun timeoutsAndProxyRoundTripThroughTheDomainValue() {
        val settings = network(connect = "1", read = "600", write = "2", call = "3",
            host = "Proxy.Example", port = "8080", redirects = true).toSettings()
        assertEquals(1, settings.connectTimeoutSeconds)
        assertEquals(600, settings.readTimeoutSeconds)
        assertEquals(2, settings.writeTimeoutSeconds)
        assertEquals(3, settings.callTimeoutSeconds)
        assertEquals("proxy.example", settings.proxy?.host)
        assertEquals(8080, settings.proxy?.port)
        assertTrue(settings.followRedirects)
    }

    @Test fun everyTimeoutIsBoundedAndIntegral() {
        assertThrows(InvalidModelNetworkSettings::class.java) { network(connect = "0").toSettings() }
        assertThrows(InvalidModelNetworkSettings::class.java) { network(write = "601").toSettings() }
    }

    @Test fun proxyRequiresHostAndPortTogetherAndRejectsCredentials() {
        assertNull(network().toSettings().proxy)
        assertThrows(InvalidModelNetworkSettings::class.java) { network(host = "proxy.example").toSettings() }
        assertThrows(InvalidModelNetworkSettings::class.java) { network(host = "user:pass@proxy.example", port = "8080").toSettings() }
    }

    @Test fun requestLimitsAreBoundedAndCanonicalized() {
        assertEquals(ModelRequestLimits(1, 0), NativeRequestLimitsInput("1", "0").toLimits())
        assertEquals(ModelRequestLimits(16, 60_000), NativeRequestLimitsInput("16", "60000").toLimits())
        assertThrows(IllegalArgumentException::class.java) { NativeRequestLimitsInput("0", "0").toLimits() }
        assertThrows(IllegalArgumentException::class.java) { NativeRequestLimitsInput("4", "60001").toLimits() }
    }

    @Test fun existingValuesPopulateTheFormWithoutChangingThem() {
        val settings = NativeNetworkSettingsInput(
            "10", "90", "10", "90", "proxy.example", "3128", true).toSettings()
        val form = NativeNetworkSettingsInput.of(settings)
        assertEquals(settings, form.toSettings())
        val limits = NativeRequestLimitsInput.of(ModelRequestLimits(7, 120))
        assertEquals(ModelRequestLimits(7, 120), limits.toLimits())
    }
}
