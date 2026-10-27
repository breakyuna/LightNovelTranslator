package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.breakyuna.noveltranslator.nativecore.domain.InvalidModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredential
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredentialPort
import com.breakyuna.noveltranslator.nativecore.domain.ModelProxyCredentialReference
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test seam only: production uses [AndroidKeystoreModelProxyCredentialSealer]. */
internal interface NativeModelProxyCredentialSealer {
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * Encrypted proxy Basic credentials keyed by the opaque [reference] saved in
 * [com.breakyuna.noveltranslator.nativecore.domain.ModelNetworkSettings].
 *
 * Each file uses AES-GCM with AAD binding the format, version and reference, so a
 * copied, renamed or tampered file is rejected instead of authenticating another
 * proxy. Writes use [AtomicFile]; absence is the only null result. This store uses
 * an independent Keystore alias, is outside backup export, has no logger and never
 * places a username, password or file path in an exception or [toString].
 */
class NativeModelProxyCredentialStore internal constructor(
    private val directory: File,
    private val sealer: NativeModelProxyCredentialSealer,
) : ModelProxyCredentialPort {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIRECTORY_NAME),
        AndroidKeystoreModelProxyCredentialSealer(),
    )

    override suspend fun load(reference: String): ModelProxyCredential? = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireReference(reference)
            currentCoroutineContext().ensureActive()
            val source = file(reference)
            if (!source.exists() && !File(source.path + BACKUP_SUFFIX).exists()) return@withLock null
            val bytes = try {
                AtomicFile(source).openRead().use { input -> readBounded(input) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidModelProxyCredential()
            }
            decode(reference, bytes)
        }
    }

    suspend fun save(reference: String, credential: ModelProxyCredential): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireReference(reference)
            currentCoroutineContext().ensureActive()
            val sealed = try {
                sealer.seal(encodePayload(credential), aad(reference))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidModelProxyCredential()
            }
            val envelope = buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("reference", reference)
                put("ciphertext", Base64.encodeToString(sealed, Base64.NO_WRAP))
            }.toString().toByteArray(Charsets.UTF_8)
            if (envelope.size > MAX_FILE_BYTES) throw InvalidModelProxyCredential()
            if (!directory.isDirectory && !directory.mkdirs()) throw InvalidModelProxyCredential()
            val atomic = AtomicFile(file(reference))
            val output = try {
                atomic.startWrite()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidModelProxyCredential()
            }
            try {
                output.write(envelope)
                output.fd.sync()
                currentCoroutineContext().ensureActive()
                atomic.finishWrite(output)
            } catch (cancelled: CancellationException) {
                atomic.failWrite(output)
                throw cancelled
            } catch (_: Exception) {
                atomic.failWrite(output)
                throw InvalidModelProxyCredential()
            }
        }
    }

    suspend fun clear(reference: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireReference(reference)
            currentCoroutineContext().ensureActive()
            val source = file(reference)
            try {
                AtomicFile(source).delete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidModelProxyCredential()
            }
            if (source.exists() || File(source.path + BACKUP_SUFFIX).exists()) {
                throw InvalidModelProxyCredential()
            }
        }
    }

    private fun encodePayload(credential: ModelProxyCredential): ByteArray {
        val bytes = buildJsonObject {
            put("username", credential.username)
            put("password", credential.password)
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PAYLOAD_BYTES) throw InvalidModelProxyCredential()
        return bytes
    }

    private fun decode(reference: String, bytes: ByteArray): ModelProxyCredential {
        try {
            if (bytes.size > MAX_FILE_BYTES) throw InvalidModelProxyCredential()
            val root = JsonObjectValue.parse(strictUtf8(bytes),
                JsonInputLimits(MAX_FILE_BYTES.toInt(), 8)).json
            require(root.keys == ENVELOPE_FIELDS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "version") == VERSION)
            require(string(root, "reference") == reference)
            val sealed = Base64.decode(string(root, "ciphertext"), Base64.NO_WRAP)
            require(sealed.size > IV_BYTES)
            val plaintext = sealer.open(sealed, aad(reference))
            if (plaintext.size > MAX_PAYLOAD_BYTES) throw InvalidModelProxyCredential()
            val payload = JsonObjectValue.parse(strictUtf8(plaintext),
                JsonInputLimits(MAX_PAYLOAD_BYTES, 8)).json
            require(payload.keys == PAYLOAD_FIELDS)
            return ModelProxyCredential.of(string(payload, "username"), string(payload, "password"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: InvalidModelProxyCredential) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidModelProxyCredential()
        }
    }

    private fun file(reference: String): File {
        val name = MessageDigest.getInstance("SHA-256")
            .digest("$FORMAT\u0000$reference".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        return File(directory, "$name.json")
    }

    private fun aad(reference: String): ByteArray = buildJsonArray {
        add(JsonPrimitive(FORMAT))
        add(JsonPrimitive(VERSION))
        add(JsonPrimitive(reference))
    }.toString().toByteArray(Charsets.UTF_8)

    private suspend fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_FILE_BYTES) throw InvalidModelProxyCredential()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        throw InvalidModelProxyCredential()
    }

    private fun string(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidModelProxyCredential()

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: throw InvalidModelProxyCredential()
        require(!primitive.isString && INTEGER.matches(primitive.content)) {
            "Invalid proxy credential field"
        }
        return primitive.content.toLongOrNull() ?: throw InvalidModelProxyCredential()
    }

    private fun requireReference(reference: String) {
        if (!ModelProxyCredentialReference.isValid(reference)) throw InvalidModelProxyCredential()
    }

    private companion object {
        val mutex = Mutex()
        const val DIRECTORY_NAME = "native_model_proxy_credentials"
        const val FORMAT = "lnt-native-model-proxy-credential"
        const val VERSION = 1L
        const val BACKUP_SUFFIX = ".bak"
        const val MAX_FILE_BYTES = 64L * 1024
        const val MAX_PAYLOAD_BYTES = 32 * 1024
        val ENVELOPE_FIELDS = setOf("format", "version", "reference", "ciphertext")
        val PAYLOAD_FIELDS = setOf("username", "password")
        val INTEGER = Regex("0|[1-9][0-9]*")
        const val IV_BYTES = 12
    }
}

/** Android Keystore AES-GCM. Its alias is separate from API-key, OAuth and MCP storage. */
private class AndroidKeystoreModelProxyCredentialSealer : NativeModelProxyCredentialSealer {
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
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "lnt_native_model_proxy_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
