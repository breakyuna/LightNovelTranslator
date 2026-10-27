package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredential
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeModelProxyCredentialStoreTest {
    private lateinit var directory: File
    private lateinit var store: NativeModelProxyCredentialStore

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "proxy-credentials-${UUID.randomUUID()}")
        store = NativeModelProxyCredentialStore(directory, TestSealer())
    }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    @Test fun saveAndLoadNeverStorePlaintextAndRedactDiagnostics() = runBlocking {
        val credential = ModelProxyCredential.of("proxy-user", "proxy-password")
        store.save("proxy-ref", credential)
        val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val stored = file.readText()
        assertFalse(stored.contains("proxy-user"))
        assertFalse(stored.contains("proxy-password"))
        assertTrue(stored.contains("ciphertext"))
        assertFalse(credential.toString().contains("proxy-user"))
        assertFalse(credential.toString().contains("proxy-password"))

        val loaded = requireNotNull(store.load("proxy-ref"))
        assertEquals("proxy-user", loaded.username)
        assertEquals("proxy-password", loaded.password)
        assertNull(store.load("other-ref"))
    }

    @Test fun tamperedReferenceOrTransplantedCiphertextFailsClosed() = runBlocking {
        store.save("alpha", ModelProxyCredential.of("user-a", "password-a"))
        val alphaFile = directory.listFiles()!!.single { it.name.endsWith(".json") }
        val alpha = alphaFile.readText()

        alphaFile.writeText(alpha.replace("\"reference\":\"alpha\"", "\"reference\":\"beta\""))
        assertThrows(InvalidModelProxyCredential::class.java) {
            runBlocking { store.load("alpha") }
        }

        alphaFile.writeText(alpha)
        store.save("beta", ModelProxyCredential.of("user-b", "password-b"))
        val betaFile = directory.listFiles()!!.single {
            it.name.endsWith(".json") && it.readText().contains("\"reference\":\"beta\"")
        }
        val transplant = buildJsonObject {
            Json.parseToJsonElement(alpha).jsonObject.forEach { (key, value) -> put(key, value) }
            put("reference", "beta")
        }.toString()
        betaFile.writeText(transplant)
        assertThrows(InvalidModelProxyCredential::class.java) {
            runBlocking { store.load("beta") }
        }
    }

    @Test fun corruptEnvelopeAndInvalidReferenceAreRejected() = runBlocking {
        for (content in listOf("{", "{}", "{\"format\":\"lnt-native-model-proxy-credential\"}")) {
            store.save("alpha", ModelProxyCredential.of("user", "password"))
            val file = directory.listFiles()!!.single { it.name.endsWith(".json") }
            file.writeText(content)
            assertThrows(InvalidModelProxyCredential::class.java) {
                runBlocking { store.load("alpha") }
            }
        }
        assertThrows(InvalidModelProxyCredential::class.java) {
            runBlocking { store.load("../escape") }
        }
        assertThrows(InvalidModelProxyCredential::class.java) {
            runBlocking { store.save("has space", ModelProxyCredential.of("user", "password")) }
        }
    }

    @Test fun clearRemovesOnlyTheRequestedReference() = runBlocking {
        store.save("alpha", ModelProxyCredential.of("user-a", "password-a"))
        store.save("beta", ModelProxyCredential.of("user-b", "password-b"))
        store.clear("alpha")
        assertNull(store.load("alpha"))
        assertEquals("user-b", requireNotNull(store.load("beta")).username)
    }

    private class TestSealer : NativeModelProxyCredentialSealer {
        private val key = SecretKeySpec(ByteArray(32) { 29 }, "AES")

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
