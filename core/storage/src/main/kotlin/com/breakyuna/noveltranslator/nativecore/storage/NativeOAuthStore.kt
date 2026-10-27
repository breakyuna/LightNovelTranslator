package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredential
import com.breakyuna.noveltranslator.nativecore.domain.OAuthCredentialStore
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailure
import com.breakyuna.noveltranslator.nativecore.domain.OAuthFailureKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test seam only: production uses [AndroidKeystoreOAuthTokenSealer]. */
internal interface OAuthTokenSealer {
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * OAuth credentials for the native model profiles. This store is independent of
 * the API-key Room columns and uses its own Keystore alias, AES-GCM key, AAD and
 * file directory.
 *
 * Each file is bound to exactly one `(profileId, revision, kind)` identity. A
 * tampered envelope, a copied file, a different revision, a corrupt payload or a
 * failed authentication tag is rejected. Absence is the only case that returns
 * null; a damaged file never becomes an unauthenticated request.
 *
 * Writes use [AtomicFile] (new temporary file, fsync, atomic rename). The class
 * has no logger and never places token material in an exception message.
 */
class NativeOAuthStore internal constructor(
    private val directory: File,
    private val sealer: OAuthTokenSealer,
) : OAuthCredentialStore {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIRECTORY_NAME),
        AndroidKeystoreOAuthTokenSealer(),
    )

    override suspend fun load(reference: ModelConfigurationRef): OAuthCredential? = withContext(Dispatchers.IO) {
        libraryMutex.withLock {
            currentCoroutineContext().ensureActive()
            val source = file(reference)
            if (!source.exists() && !File(source.path + BACKUP_SUFFIX).exists()) return@withLock null
            val bytes = try {
                AtomicFile(source).openRead().use { input -> readBounded(input) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
            }
            decode(reference, bytes)
        }
    }

    override suspend fun save(reference: ModelConfigurationRef,
        credential: OAuthCredential): Unit = withContext(Dispatchers.IO) {
        libraryMutex.withLock {
            currentCoroutineContext().ensureActive()
            val plaintext = encodePayload(credential)
            val sealed = try {
                sealer.seal(plaintext, aad(reference, AAD_KIND))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            }
            val envelope = buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("profileId", reference.id)
                put("revision", reference.revision)
                put("kind", AAD_KIND)
                put("ciphertext", Base64.encodeToString(sealed, Base64.NO_WRAP))
            }.toString().toByteArray(Charsets.UTF_8)
            if (envelope.size > MAX_FILE_BYTES) throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            }
            val atomic = AtomicFile(file(reference))
            val output = try {
                atomic.startWrite()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            }
            try {
                output.write(envelope)
                // AtomicFile.finishWrite also fsyncs; the explicit sync keeps the
                // durability contract visible for this credential file.
                output.fd.sync()
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (cancelled: CancellationException) {
                atomic.failWrite(output)
                throw cancelled
            } catch (_: Exception) {
                atomic.failWrite(output)
                throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            }
        }
    }

    override suspend fun clear(reference: ModelConfigurationRef): Unit = withContext(Dispatchers.IO) {
        libraryMutex.withLock {
            currentCoroutineContext().ensureActive()
            val source = file(reference)
            try {
                AtomicFile(source).delete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            }
            if (source.exists() || File(source.path + BACKUP_SUFFIX).exists()) {
                throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
            }
        }
    }

    private fun encodePayload(credential: OAuthCredential): ByteArray {
        val text = buildJsonObject {
            put("accessToken", credential.accessToken)
            put("refreshToken", credential.refreshToken?.let { JsonPrimitive(it) } ?: JsonNull)
            put("expiresAtMillis", credential.expiresAtMillis?.let { JsonPrimitive(it) } ?: JsonNull)
            put("scopes", buildJsonArray { credential.scopes.forEach { add(JsonPrimitive(it)) } })
            put("tokenType", credential.tokenType)
        }.toString()
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PAYLOAD_BYTES) throw OAuthFailure(OAuthFailureKind.STORE_UNAVAILABLE)
        return bytes
    }

    private fun decode(reference: ModelConfigurationRef, bytes: ByteArray): OAuthCredential {
        try {
            if (bytes.size > MAX_FILE_BYTES) throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
            val root = JsonObjectValue.parse(strictUtf8(bytes), JsonInputLimits(MAX_FILE_BYTES, 8)).json
            require(root.keys == ENVELOPE_FIELDS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "version") == VERSION)
            require(string(root, "profileId") == reference.id)
            require(integer(root, "revision") == reference.revision)
            require(string(root, "kind") == AAD_KIND)
            val sealed = Base64.decode(string(root, "ciphertext"), Base64.NO_WRAP)
            require(sealed.size > IV_BYTES)
            val plaintext = sealer.open(sealed, aad(reference, AAD_KIND))
            if (plaintext.size > MAX_PAYLOAD_BYTES) throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
            val payload = JsonObjectValue.parse(strictUtf8(plaintext), JsonInputLimits(MAX_PAYLOAD_BYTES, 8)).json
            require(payload.keys == PAYLOAD_FIELDS)
            val accessToken = string(payload, "accessToken")
            val refreshToken = if (payload.getValue("refreshToken") == JsonNull) null
            else string(payload, "refreshToken")
            val expiresAtMillis = if (payload.getValue("expiresAtMillis") == JsonNull) null
            else integer(payload, "expiresAtMillis")
            val scopesValue = payload["scopes"] as? JsonArray ?: error("Invalid OAuth scopes")
            require(scopesValue.size <= OAuthCredential.MAX_SCOPES)
            val scopes = scopesValue.map { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: error("Invalid OAuth scope")
            }
            return OAuthCredential.of(accessToken, refreshToken, expiresAtMillis, scopes,
                string(payload, "tokenType"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OAuthFailure) {
            throw failure
        } catch (_: Exception) {
            throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
        }
    }

    private suspend fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_FILE_BYTES) {
                throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw OAuthFailure(OAuthFailureKind.STORE_CORRUPT)
    }

    private fun file(reference: ModelConfigurationRef): File {
        val identity = buildJsonArray {
            add(JsonPrimitive(reference.id))
            add(JsonPrimitive(reference.revision))
            add(JsonPrimitive(AAD_KIND))
        }.toString()
        val name = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$name.json")
    }

    private fun aad(reference: ModelConfigurationRef, kind: String): ByteArray = buildJsonArray {
        add(JsonPrimitive(FORMAT))
        add(JsonPrimitive(VERSION))
        add(JsonPrimitive(kind))
        add(JsonPrimitive(reference.id))
        add(JsonPrimitive(reference.revision))
    }.toString().toByteArray(Charsets.UTF_8)

    private fun string(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Invalid OAuth credential field")

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: error("Invalid OAuth credential field")
        require(!primitive.isString && INTEGER.matches(primitive.content)) { "Invalid OAuth credential field" }
        return primitive.content.toLongOrNull() ?: error("Invalid OAuth credential field")
    }

    private companion object {
        val libraryMutex = Mutex()
        const val DIRECTORY_NAME = "native_oauth_credentials"
        const val FORMAT = "lnt-native-oauth"
        const val VERSION = 1L
        const val AAD_KIND = "oauth"
        const val BACKUP_SUFFIX = ".bak"
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_PAYLOAD_BYTES = 128 * 1024
        val ENVELOPE_FIELDS = setOf("format", "version", "profileId", "revision", "kind", "ciphertext")
        val PAYLOAD_FIELDS = setOf("accessToken", "refreshToken", "expiresAtMillis", "scopes", "tokenType")
        val INTEGER = Regex("0|[1-9][0-9]*")
    }
}

/** Android Keystore-backed AES-GCM. The alias is deliberately separate from API-key storage. */
private class AndroidKeystoreOAuthTokenSealer : OAuthTokenSealer {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret(),
            GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.copyOfRange(IV_BYTES, sealed.size))
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "lnt_native_oauth_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
