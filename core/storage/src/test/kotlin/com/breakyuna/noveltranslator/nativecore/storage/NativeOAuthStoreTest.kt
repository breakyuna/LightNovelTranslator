package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AES-GCM is supplied by a JVM fake; this suite never requires AndroidKeyStore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeOAuthStoreTest {
    private lateinit var directory: File
    private lateinit var store: NativeOAuthStore
    private val reference = ModelConfigurationRef("profile-a", 4)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "oauth-test-${UUID.randomUUID()}")
        store = NativeOAuthStore(directory, TestSealer())
    }

    @After fun cleanup() {
        directory.deleteRecursively()
    }

    @Test fun saveAndLoadRoundTripNeverStoresPlaintext() = runBlocking {
        val credential = OAuthCredential.of("access-secret-value", "refresh-secret-value",
            1_700_000_000_000L, listOf("scope-a", "scope-b"), "Bearer")
        store.save(reference, credential)
        val file = fileFor(reference)
        assertTrue(file.isFile)
        val stored = file.readText()
        assertFalse(stored.contains("access-secret-value"))
        assertFalse(stored.contains("refresh-secret-value"))
        assertTrue(stored.contains("ciphertext"))

        val loaded = requireNotNull(store.load(reference))
        assertEquals("access-secret-value", loaded.accessToken)
        assertEquals("refresh-secret-value", loaded.refreshToken)
        assertEquals(1_700_000_000_000L, loaded.expiresAtMillis)
        assertEquals(listOf("scope-a", "scope-b"), loaded.scopes)
        assertEquals("Bearer", loaded.tokenType)
        assertFalse(loaded.toString().contains("access-secret-value"))

        assertNull(store.load(ModelConfigurationRef("profile-a", 5)))
    }

    @Test fun clearRemovesOnlyTheRequestedRevision() = runBlocking {
        val other = ModelConfigurationRef("profile-a", 5)
        store.save(reference, OAuthCredential.of("access-a"))
        store.save(other, OAuthCredential.of("access-b"))
        store.clear(reference)
        assertFalse(fileFor(reference).exists())
        assertNull(store.load(reference))
        assertEquals("access-b", requireNotNull(store.load(other)).accessToken)
    }

    @Test fun copiedEnvelopeIsRejectedByIdentityAndAad() = runBlocking {
        val target = ModelConfigurationRef("profile-b", 1)
        store.save(reference, OAuthCredential.of("access-secret-value", "refresh-secret-value"))
        val source = fileFor(reference)
        val copied = source.readText()
            .replace("\"profileId\":\"${reference.id}\"", "\"profileId\":\"${target.id}\"")
            .replace("\"revision\":${reference.revision}", "\"revision\":${target.revision}")
        fileFor(target).writeText(copied)

        try {
            store.load(target)
            fail("Accepted a credential sealed for another identity")
        } catch (failure: OAuthFailure) {
            assertEquals(OAuthFailureKind.STORE_CORRUPT, failure.kind)
        }
        assertFalse(copied.contains("access-secret-value"))
    }

    @Test fun corruptOrWrongKindFilesAreRejectedInsteadOfBecomingNoAuth() = runBlocking {
        store.save(reference, OAuthCredential.of("access-secret-value"))
        val file = fileFor(reference)
        val valid = file.readText()
        for (content in listOf(
            "{",
            valid.replace("\"kind\":\"oauth\"", "\"kind\":\"api-key\""),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.dropLast(2) + "\"unexpected\":true}",
        )) {
            file.writeText(content)
            try {
                store.load(reference)
                fail("Accepted corrupt OAuth state")
            } catch (failure: OAuthFailure) {
                assertEquals(OAuthFailureKind.STORE_CORRUPT, failure.kind)
            }
        }
    }

    private fun fileFor(value: ModelConfigurationRef): File {
        val identity = buildJsonArray {
            add(JsonPrimitive(value.id))
            add(JsonPrimitive(value.revision))
            add(JsonPrimitive("oauth"))
        }.toString()
        val name = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$name.json")
    }

    private class TestSealer : OAuthTokenSealer {
        private val key = SecretKeySpec(ByteArray(32) { 11 }, "AES")

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
