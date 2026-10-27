package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeMcpSettingsStoreTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After fun cleanup() = clear()

    private fun clear() {
        File(context.filesDir, "native-mcp-settings.json").delete()
        File(context.filesDir, "native-mcp-settings.json.bak").delete()
    }

    private fun record(
        id: String = "alpha",
        name: String = "Search",
        enabled: Boolean = true,
        privateNetwork: Boolean = false,
        tools: Collection<String> = listOf("search"),
    ) = NativeMcpServerRecord(
        id = id,
        name = name,
        url = "https://mcp.example.com/rpc",
        enabled = enabled,
        allowPrivateNetwork = privateNetwork,
        allowedTools = tools,
        timeoutMillis = 30_000,
        maxTools = 64,
    )

    @Test fun missingIsEmptyAndRoundTripPreservesRevision() = runBlocking {
        val store = NativeMcpSettingsStore(context)
        val empty = store.load()
        assertEquals(NativeMcpSettingsSnapshot.EMPTY, empty)
        assertTrue(empty.fingerprint.all { it in "0123456789abcdef" })

        val saved = store.upsert(record(), empty.fingerprint)
        assertEquals(1L, saved.revision)
        assertEquals(1L, saved.servers.single().revision)
        assertEquals(record(), saved.servers.single().config)
        assertEquals(saved, NativeMcpSettingsStore(context).load())
    }

    @Test fun identicalUpsertIsIdempotentAndStaleFingerprintIsRejected(): Unit = runBlocking {
        val store = NativeMcpSettingsStore(context)
        val first = store.upsert(record(), NativeMcpSettingsSnapshot.EMPTY.fingerprint)
        val second = store.upsert(record(), first.fingerprint)
        assertEquals(first.revision, second.revision)
        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.servers.single().revision, second.servers.single().revision)

        val changed = store.upsert(record(name = "Search 2"), first.fingerprint)
        assertEquals(2L, changed.revision)
        assertEquals(2L, changed.servers.single().revision)
        assertThrows(McpSettingsConflict::class.java) {
            runBlocking { store.upsert(record(name = "Search 3"), first.fingerprint) }
        }
    }

    @Test fun enableAndDeleteUseCasAndAreIdempotent() = runBlocking {
        val store = NativeMcpSettingsStore(context)
        val saved = store.upsert(record(), NativeMcpSettingsSnapshot.EMPTY.fingerprint)
        val disabled = store.setEnabled("alpha", false, saved.fingerprint)
        assertFalse(disabled.servers.single().config.enabled)
        assertEquals(2L, disabled.servers.single().revision)
        val same = store.setEnabled("alpha", false, disabled.fingerprint)
        assertEquals(disabled.fingerprint, same.fingerprint)
        val removed = store.remove("alpha", same.fingerprint)
        assertTrue(removed.servers.isEmpty())
        assertEquals(removed, store.remove("alpha", removed.fingerprint))
    }

    @Test fun privateLiteralRequiresOptIn() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeMcpServerRecord(
                id = "alpha", name = "Local", url = "http://127.0.0.1:8080/mcp",
                allowPrivateNetwork = false,
            )
        }
        NativeMcpServerRecord(
            id = "alpha", name = "Local", url = "http://127.0.0.1:8080/mcp",
            allowPrivateNetwork = true,
        )
        assertThrows(IllegalArgumentException::class.java) {
            NativeMcpServerRecord(
                id = "alpha", name = "Local", url = "https://user:pass@example.com/mcp",
            )
        }
    }

    @Test fun toolNamesAreBoundedAndNeverContainWhitespace() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeMcpServerRecord(
                id = "alpha", name = "Search", url = "https://mcp.example.com/rpc",
                allowedTools = listOf("bad tool"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeMcpServerRecord(
                id = "alpha", name = "Search", url = "https://mcp.example.com/rpc",
                allowedTools = listOf("x".repeat(129)),
            )
        }
    }

    @Test fun secretFieldIsRejectedByTheRecordCodec() {
        val encoded = NativeMcpServerRecordCodec.encode(record())
        val withToken = encoded.dropLast(1) + ",\"token\":\"secret\"}"
        assertThrows(InvalidMcpSettings::class.java) {
            NativeMcpServerRecordCodec.decode(withToken)
        }
        assertTrue(NativeMcpServerRecordCodec.isSecretField("Authorization"))
        assertTrue(NativeMcpServerRecordCodec.isSecretField("mcp_token"))
        assertFalse(NativeMcpServerRecordCodec.isSecretField("allowedTools"))
    }

    @Test fun corruptFileFailsClosedInsteadOfBecomingEmptyConfiguration(): Unit = runBlocking {
        val store = NativeMcpSettingsStore(context)
        store.upsert(record(), NativeMcpSettingsSnapshot.EMPTY.fingerprint)
        File(context.filesDir, "native-mcp-settings.json").writeText("not-json")
        assertThrows(InvalidMcpSettings::class.java) {
            runBlocking { NativeMcpSettingsStore(context).load() }
        }
    }
}
