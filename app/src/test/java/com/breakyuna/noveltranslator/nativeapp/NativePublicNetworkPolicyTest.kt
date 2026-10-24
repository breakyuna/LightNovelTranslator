package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativeapp.net.NativePublicNetworkPolicy
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePublicNetworkPolicyTest {
    private fun address(value: String): InetAddress = InetAddress.getByName(value)

    @Test fun rejectsIpv4PrivateAndSpecialRanges() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1").forEach {
            assertFalse("expected $it to be non-public", NativePublicNetworkPolicy.isPublic(address(it)))
        }
        assertTrue(NativePublicNetworkPolicy.isPublic(address("172.32.0.1")))
        assertTrue(NativePublicNetworkPolicy.isPublic(address("100.128.0.1")))
        assertTrue(NativePublicNetworkPolicy.isPublic(address("8.8.8.8")))
    }

    @Test fun rejectsIpv6LoopbackAndUniqueLocal() {
        listOf("::1", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1").forEach {
            assertFalse("expected $it to be non-public", NativePublicNetworkPolicy.isPublic(address(it)))
        }
        assertTrue(NativePublicNetworkPolicy.isPublic(address("2001:4860:4860::8888")))
    }

    @Test fun rejectsLocalHostNamesAndCredentials() {
        listOf("http://localhost/", "http://app.local/", "http://service.internal/",
            "http://127.0.0.1/", "http://[::1]/", "https://user:pass@example.com/").forEach {
            assertThrows(IllegalArgumentException::class.java) { NativePublicNetworkPolicy.requirePublicUrl(it) }
        }
        assertEquals("https://example.com/a", NativePublicNetworkPolicy.requirePublicUrl("https://example.com/a").toString())
    }

    @Test fun validatingDnsRejectsAnyPrivateAnswer() {
        val private = NativePublicNetworkPolicy.ValidatingDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(address("93.184.216.34"), address("10.0.0.5"))
        })
        assertThrows(UnknownHostException::class.java) { private.lookup("example.com") }

        val public = NativePublicNetworkPolicy.ValidatingDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(address("93.184.216.34"))
        })
        assertEquals(1, public.lookup("example.com").size)
    }

    @Test fun validatingDnsRejectsLocalNamesWithoutResolution() {
        var called = false
        val dns = NativePublicNetworkPolicy.ValidatingDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                called = true
                return listOf(address("93.184.216.34"))
            }
        })
        assertThrows(UnknownHostException::class.java) { dns.lookup("printer.local") }
        assertFalse(called)
    }
}
