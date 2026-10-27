package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AES-GCM is supplied by a JVM fake; AndroidKeyStore is never required in this suite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeMcpCredentialStoreTest {
    private lateinit var directory: File
    private lateinit var store: NativeMcpCredentialStore
    private val fingerprint = "a".repeat(64)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "mcp-credentials-${UUID.randomUUID()}")
        store = NativeMcpCredentialStore(directory, TestSealer())
    }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    @Test fun saveAndLoadNeverStorePlaintext() = runBlocking {
        store.save("alpha", 2, fingerprint, NativeMcpCredential.bearer("token-secret-value"))
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val stored = file.readText()
        assertFalse(stored.contains("token-secret-value"))
        assertFalse(stored.contains("Bearer"))
        assertTrue(stored.contains("ciphertext"))

        val loaded = requireNotNull(store.load("alpha", 2, fingerprint))
        assertEquals("Authorization", loaded.headerName)
        assertEquals("Bearer token-secret-value", loaded.headerValue)
        assertFalse(loaded.toString().contains("token-secret-value"))
        assertEquals(
            "Bearer token-secret-value",
            store.withCredential("alpha", 2, fingerprint) { _, value -> value },
        )
        assertThrows(InvalidMcpCredential::class.java) {
            runBlocking { store.load("alpha", 3, fingerprint) }
        }
        assertThrows(InvalidMcpCredential::class.java) {
            runBlocking { store.load("alpha", 2, "b".repeat(64)) }
        }
    }

    @Test fun changedRevisionOrFingerprintIsRejectedInsteadOfReused() = runBlocking {
        store.save("alpha", 2, fingerprint, NativeMcpCredential.bearer("token-secret-value"))
        assertThrows(InvalidMcpCredential::class.java) {
            runBlocking { store.load("alpha", 3, fingerprint) }
        }
        assertThrows(InvalidMcpCredential::class.java) {
            runBlocking { store.load("alpha", 2, "b".repeat(64)) }
        }
        assertFalse(store.has("alpha", 3, fingerprint))
    }

    @Test fun clearRemovesOnlyTheRequestedServer() = runBlocking {
        store.save("alpha", 1, fingerprint, NativeMcpCredential.bearer("token-a"))
        store.save("beta", 1, fingerprint, NativeMcpCredential.bearer("token-b"))
        store.clear("alpha")
        assertNull(store.load("alpha", 1, fingerprint))
        assertEquals(
            "Bearer token-b",
            requireNotNull(store.load("beta", 1, fingerprint)).headerValue,
        )
    }

    @Test fun corruptOrTamperedEnvelopeFailsClosed() = runBlocking {
        store.save("alpha", 1, fingerprint, NativeMcpCredential.bearer("token-secret-value"))
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val valid = file.readText()
        for (content in listOf(
            "{",
            valid.dropLast(2) + "\"extra\":true}",
            valid.replace("\"revision\":1", "\"revision\":2"),
            valid.replace("\"ciphertext\":\"", "\"ciphertext\":\"AAAA"),
        )) {
            file.writeText(content)
            assertThrows(InvalidMcpCredential::class.java) {
                runBlocking { store.load("alpha", 1, fingerprint) }
            }
        }
        assertFalse(store.has("alpha", 1, fingerprint))
    }

    private class TestSealer : NativeMcpCredentialSealer {
        private val key = SecretKeySpec(ByteArray(32) { 23 }, "AES")

        override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            return cipher.iv + cipher.doFinal(plaintext)
        }

        override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
            require(sealed.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed.copyOfRange(12, sealed.size))
        }
    }
}
