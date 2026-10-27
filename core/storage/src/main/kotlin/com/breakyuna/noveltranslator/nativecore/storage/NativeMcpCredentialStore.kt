package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One MCP credential value. The value is deliberately available only to the adapter that
 * converts it into the core request-time credential port; it is never serialized in plaintext
 * and [toString] is always redacted.
 */
class NativeMcpCredential private constructor(
    val headerName: String,
    internal val headerValue: String,
) {
    override fun toString(): String = "NativeMcpCredential(header=$headerName, value=redacted)"

    companion object {
        private val HEADER_NAME = Regex("[A-Za-z0-9-]{1,64}")
        private val FORBIDDEN_HEADERS = setOf(
            "host",
            "content-length",
            "content-type",
            "transfer-encoding",
            "connection",
            "accept",
            "mcp-session-id",
            "mcp-protocol-version",
            "proxy-authorization",
        )

        fun of(headerName: String, headerValue: String): NativeMcpCredential {
            require(HEADER_NAME.matches(headerName)) { "Invalid credential header name" }
            require(headerName.lowercase(Locale.ROOT) !in FORBIDDEN_HEADERS) {
                "Credential header is reserved"
            }
            require(headerValue.length in 1..8_192) { "Invalid credential header value" }
            require(headerValue == headerValue.trim()) { "Invalid credential header value" }
            require(headerValue.all { it.code in 0x20..0x7e }) { "Invalid credential header value" }
            return NativeMcpCredential(headerName, headerValue)
        }

        fun bearer(token: String): NativeMcpCredential {
            require(token.length in 1..8_000 && token.all { it.code in 0x21..0x7e }) {
                "Invalid bearer token"
            }
            return of("Authorization", "Bearer $token")
        }
    }
}

class InvalidMcpCredential : IllegalStateException("Invalid MCP credential")

/** Test seam only: production uses [AndroidKeystoreMcpCredentialSealer]. */
internal interface NativeMcpCredentialSealer {
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray
}

/**
 * Encrypted credentials for configured MCP servers. One file exists per server id and the
 * AES-GCM AAD binds the server id, monotonic configuration revision and configuration
 * fingerprint. A copied, tampered or stale file is rejected instead of becoming an
 * unauthenticated request. The file never contains a token in plaintext, is outside every
 * backup export, and this class has no logger.
 */
class NativeMcpCredentialStore internal constructor(
    private val directory: File,
    private val sealer: NativeMcpCredentialSealer,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, DIRECTORY_NAME),
        AndroidKeystoreMcpCredentialSealer(),
    )

    suspend fun save(
        serverId: String,
        revision: Long,
        configFingerprint: String,
        credential: NativeMcpCredential,
    ): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireServerId(serverId)
            require(revision >= 1) { "Invalid credential revision" }
            require(SHA256.matches(configFingerprint)) { "Invalid credential fingerprint" }
            currentCoroutineContext().ensureActive()
            val plaintext = encodePayload(credential)
            val sealed = try {
                sealer.seal(plaintext, aad(serverId, revision, configFingerprint))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidMcpCredential()
            }
            val envelope = buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("serverId", serverId)
                put("revision", revision)
                put("configFingerprint", configFingerprint)
                put("ciphertext", Base64.encodeToString(sealed, Base64.NO_WRAP))
            }.toString().toByteArray(Charsets.UTF_8)
            if (envelope.size > MAX_FILE_BYTES) throw InvalidMcpCredential()
            if (!directory.isDirectory && !directory.mkdirs()) throw InvalidMcpCredential()
            val atomic = AtomicFile(file(serverId))
            val output = try {
                atomic.startWrite()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidMcpCredential()
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
                throw InvalidMcpCredential()
            }
        }
    }

    suspend fun load(
        serverId: String,
        revision: Long,
        configFingerprint: String,
    ): NativeMcpCredential? = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireServerId(serverId)
            require(revision >= 1) { "Invalid credential revision" }
            require(SHA256.matches(configFingerprint)) { "Invalid credential fingerprint" }
            currentCoroutineContext().ensureActive()
            val source = file(serverId)
            if (!source.exists() && !File(source.path + BACKUP_SUFFIX).exists()) return@withLock null
            val bytes = try {
                AtomicFile(source).openRead().use { readBounded(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidMcpCredential()
            }
            decode(serverId, revision, configFingerprint, bytes)
        }
    }

    /**
     * Reads the secret only into [block], so callers outside this module never hold a public
     * raw-value property. The core credential port uses this to construct its own value.
     */
    suspend fun <T> withCredential(
        serverId: String,
        revision: Long,
        configFingerprint: String,
        block: (headerName: String, headerValue: String) -> T,
    ): T? {
        val credential = load(serverId, revision, configFingerprint) ?: return null
        return block(credential.headerName, credential.headerValue)
    }

    suspend fun has(serverId: String, revision: Long, configFingerprint: String): Boolean {
        return try {
            load(serverId, revision, configFingerprint) != null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun clear(serverId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            requireServerId(serverId)
            currentCoroutineContext().ensureActive()
            try {
                AtomicFile(file(serverId)).delete()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw InvalidMcpCredential()
            }
        }
    }

    private fun encodePayload(credential: NativeMcpCredential): ByteArray {
        val bytes = buildJsonObject {
            put("headerName", credential.headerName)
            put("headerValue", credential.headerValue)
        }.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PAYLOAD_BYTES) throw InvalidMcpCredential()
        return bytes
    }

    private fun decode(
        serverId: String,
        revision: Long,
        configFingerprint: String,
        bytes: ByteArray,
    ): NativeMcpCredential {
        try {
            if (bytes.size > MAX_FILE_BYTES) throw InvalidMcpCredential()
            val root = JsonObjectValue.parse(strictUtf8(bytes), JsonInputLimits(MAX_FILE_BYTES.toInt(), 8)).json
            require(root.keys == ENVELOPE_FIELDS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "version") == VERSION)
            require(string(root, "serverId") == serverId)
            require(integer(root, "revision") == revision)
            require(string(root, "configFingerprint") == configFingerprint)
            val sealed = Base64.decode(string(root, "ciphertext"), Base64.NO_WRAP)
            require(sealed.size > IV_BYTES)
            val plaintext = sealer.open(sealed, aad(serverId, revision, configFingerprint))
            if (plaintext.size > MAX_PAYLOAD_BYTES) throw InvalidMcpCredential()
            val payload = JsonObjectValue.parse(
                strictUtf8(plaintext), JsonInputLimits(MAX_PAYLOAD_BYTES, 8),
            ).json
            require(payload.keys == PAYLOAD_FIELDS)
            return NativeMcpCredential.of(
                string(payload, "headerName"),
                string(payload, "headerValue"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: InvalidMcpCredential) {
            throw invalid
        } catch (_: Exception) {
            throw InvalidMcpCredential()
        }
    }

    private fun file(serverId: String): File {
        val name = MessageDigest.getInstance("SHA-256")
            .digest("$FORMAT\u0000$serverId".toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
        return File(directory, "$name.json")
    }

    private fun aad(serverId: String, revision: Long, configFingerprint: String): ByteArray =
        buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("serverId", serverId)
            put("revision", revision)
            put("configFingerprint", configFingerprint)
        }.toString().toByteArray(Charsets.UTF_8)

    private suspend fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > MAX_FILE_BYTES) throw InvalidMcpCredential()
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
        throw InvalidMcpCredential()
    }

    private fun string(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw InvalidMcpCredential()

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: throw InvalidMcpCredential()
        require(!primitive.isString && INTEGER.matches(primitive.content)) { "Invalid MCP credential" }
        return primitive.content.toLongOrNull() ?: throw InvalidMcpCredential()
    }

    private fun requireServerId(serverId: String) {
        require(SERVER_ID.matches(serverId)) { "Invalid MCP server id" }
    }

    private companion object {
        val mutex = Mutex()
        const val DIRECTORY_NAME = "native_mcp_credentials"
        const val FORMAT = "lnt-native-mcp-credential"
        const val VERSION = 1L
        const val BACKUP_SUFFIX = ".bak"
        const val MAX_FILE_BYTES = 64L * 1024
        const val MAX_PAYLOAD_BYTES = 32 * 1024
        val ENVELOPE_FIELDS = setOf(
            "format", "version", "serverId", "revision", "configFingerprint", "ciphertext",
        )
        val PAYLOAD_FIELDS = setOf("headerName", "headerValue")
        val SERVER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val SHA256 = Regex("[0-9a-f]{64}")
        val INTEGER = Regex("0|[1-9][0-9]*")
    }
}

/** Android Keystore AES-GCM. Its alias is separate from API-key and OAuth storage. */
private class AndroidKeystoreMcpCredentialSealer : NativeMcpCredentialSealer {
    override fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        cipher.updateAAD(aad)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret(),
            GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)),
        )
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
        const val ALIAS = "lnt_native_mcp_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
