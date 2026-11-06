package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings
import com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettingsProvider
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkspaceHttpFetchTest {
    private fun port() = WorkspaceHttpFetch(object : ModelNetworkSettingsProvider {
        override suspend fun networkSettings() = ModelNetworkSettings.DEFAULT
    })

    @Test fun publicPolicyRejectsLoopbackLanCarrierNatAndUniqueLocal() {
        val fetch = port()
        for (address in listOf("127.0.0.1", "0.0.0.0", "10.0.0.1", "172.16.1.1", "192.168.1.1",
            "169.254.1.1", "100.64.0.1", "198.18.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1")) {
            assertFalse(address, fetch.publicAddress(InetAddress.getByName(address)))
        }
        assertTrue(fetch.publicAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(fetch.publicAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test fun cancellationBeforeFetchDoesNotReadSettingsOrStartNetwork() {
        runBlocking {
            val fetch = WorkspaceHttpFetch(object : ModelNetworkSettingsProvider {
                override suspend fun networkSettings(): ModelNetworkSettings = error("Cancelled fetch read settings")
            })
            val cancellation = WorkspaceCancellation().also { it.cancel() }
            assertSame(WorkspaceFetchOutcome.Cancelled, fetch.fetch(WorkspaceFetchRequest.create(
                "https://example.com/", System.currentTimeMillis() + 1000, cancellation)))
        }
    }

    @Test fun unavailableProxySettingsAreNotReplacedByDirectAccess() {
        runBlocking {
            val fetch = WorkspaceHttpFetch(object : ModelNetworkSettingsProvider {
                override suspend fun networkSettings(): ModelNetworkSettings = error("Unavailable")
            })
            val outcome = fetch.fetch(WorkspaceFetchRequest.create("https://example.com/",
                System.currentTimeMillis() + 1000, WorkspaceCancellation()))
            assertTrue(outcome is WorkspaceFetchOutcome.Failed)
        }
    }
}
