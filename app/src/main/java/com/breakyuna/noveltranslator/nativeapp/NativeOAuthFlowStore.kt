package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import android.util.AtomicFile
import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import java.io.ByteArrayOutputStream
import java.io.File
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

/** One authorization request in flight. Secrets are never rendered by [toString]. */
class NativeOAuthPendingFlow(
    val profileId: String,
    val revision: Long,
    val configFingerprint: String,
    val state: String,
    val codeVerifier: String,
    val createdAtMillis: Long,
) {
    init {
        require(PROFILE_ID.matches(profileId)) { "Invalid OAuth profile" }
        require(revision >= 0) { "Invalid OAuth revision" }
        require(FINGERPRINT.matches(configFingerprint)) { "Invalid OAuth fingerprint" }
        require(STATE.matches(state)) { "Invalid OAuth state" }
        require(VERIFIER.matches(codeVerifier)) { "Invalid OAuth verifier" }
        require(createdAtMillis >= 0) { "Invalid OAuth flow time" }
    }

    override fun toString(): String =
        "NativeOAuthPendingFlow(profileId=$profileId, revision=$revision, state=redacted)"

    private companion object {
        val PROFILE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val FINGERPRINT = Regex("[0-9a-f]{64}")
        val STATE = Regex("[A-Za-z0-9\\-._~]{16,256}")
        val VERIFIER = Regex("[A-Za-z0-9\\-._~]{43,128}")
    }
}

enum class NativeOAuthCallbackError {
    ACCESS_DENIED,
    INVALID_REQUEST,
    SERVER_ERROR,
    TEMPORARILY_UNAVAILABLE,
    INVALID_CALLBACK,
    UNKNOWN,
}

/** One callback result, held only until MainActivity consumes it once. */
sealed class NativeOAuthCallbackResult {
    abstract val profileId: String
    abstract val revision: Long
    abstract val configFingerprint: String
    abstract val createdAtMillis: Long

    class Success(
        override val profileId: String,
        override val revision: Long,
        override val configFingerprint: String,
        override val createdAtMillis: Long,
        val code: String,
        val codeVerifier: String,
    ) : NativeOAuthCallbackResult() {
        init {
            require(CALLBACK_PROFILE_ID.matches(profileId)) { "Invalid OAuth profile" }
            require(revision >= 0) { "Invalid OAuth revision" }
            require(CALLBACK_FINGERPRINT.matches(configFingerprint)) { "Invalid OAuth fingerprint" }
            require(createdAtMillis >= 0) { "Invalid OAuth callback time" }
            require(CALLBACK_CODE.matches(code)) { "Invalid OAuth authorization code" }
            require(CALLBACK_VERIFIER.matches(codeVerifier)) { "Invalid OAuth verifier" }
        }

        override fun toString(): String =
            "NativeOAuthCallbackResult.Success(profileId=$profileId, code=redacted)"
    }

    class Failure(
        override val profileId: String,
        override val revision: Long,
        override val configFingerprint: String,
        override val createdAtMillis: Long,
        val error: NativeOAuthCallbackError,
    ) : NativeOAuthCallbackResult() {
        init {
            require(CALLBACK_PROFILE_ID.matches(profileId)) { "Invalid OAuth profile" }
            require(revision >= 0) { "Invalid OAuth revision" }
            require(CALLBACK_FINGERPRINT.matches(configFingerprint)) { "Invalid OAuth fingerprint" }
            require(createdAtMillis >= 0) { "Invalid OAuth callback time" }
        }

        override fun toString(): String =
            "NativeOAuthCallbackResult.Failure(profileId=$profileId, error=$error)"
    }

}

private val CALLBACK_PROFILE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val CALLBACK_FINGERPRINT = Regex("[0-9a-f]{64}")
private val CALLBACK_CODE = Regex("[\\x21-\\x7E]{1,4096}")
private val CALLBACK_VERIFIER = Regex("[A-Za-z0-9\\-._~]{43,128}")
private val CALLBACK_STATE_VALUE = Regex("[\\x21-\\x7E]{1,256}")
private val CALLBACK_CODE_VALUE = Regex("[\\x21-\\x7E]{1,4096}")
private val CALLBACK_ERROR_VALUE = Regex("[\\x21-\\x7E]{1,128}")

class NativeOAuthCallbackParameters(
    val state: String?,
    val code: String?,
    val error: String?,
)

/** Bounded parsing of query parameters. Error descriptions are deliberately not retained. */
internal fun nativeOAuthCallbackParameters(read: (String) -> String?): NativeOAuthCallbackParameters =
    NativeOAuthCallbackParameters(
        state = read("state")?.takeIf { CALLBACK_STATE_VALUE.matches(it) },
        code = read("code")?.takeIf { CALLBACK_CODE_VALUE.matches(it) },
        error = read("error")?.takeIf { CALLBACK_ERROR_VALUE.matches(it) },
    )

internal fun nativeOAuthCallbackError(value: String?): NativeOAuthCallbackError = when (value) {
    "access_denied" -> NativeOAuthCallbackError.ACCESS_DENIED
    "invalid_request", "invalid_scope", "unauthorized_client", "unsupported_response_type" ->
        NativeOAuthCallbackError.INVALID_REQUEST
    "server_error" -> NativeOAuthCallbackError.SERVER_ERROR
    "temporarily_unavailable" -> NativeOAuthCallbackError.TEMPORARILY_UNAVAILABLE
    else -> NativeOAuthCallbackError.UNKNOWN
}

class NativeOAuthFlowException(
    val reason: Reason,
) : Exception("OAuth flow error: $reason") {
    enum class Reason { INVALID, CORRUPT, UNAVAILABLE }

    override fun toString(): String = "NativeOAuthFlowException(reason=$reason)"
}

/**
 * Durable PKCE state and one-time callback handoff. Pending flows expire after ten
 * minutes; callback results are consumed once and expire after five minutes. A
 * mismatched state never consumes the legitimate pending flow. There is no logger
 * and no token material in either file.
 */
class NativeOAuthFlowStore internal constructor(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIRECTORY_NAME))

    private val mutex = Mutex()

    suspend fun savePending(flow: NativeOAuthPendingFlow): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            // A new authorization request must not consume a stale callback from an older one.
            delete(CALLBACK_FILE)
            val bytes = buildJsonObject {
                put("format", FORMAT)
                put("version", VERSION)
                put("kind", PENDING_KIND)
                put("profileId", flow.profileId)
                put("revision", flow.revision)
                put("configFingerprint", flow.configFingerprint)
                put("state", flow.state)
                put("codeVerifier", flow.codeVerifier)
                put("createdAtMillis", flow.createdAtMillis)
            }.toString().toByteArray(Charsets.UTF_8)
            write(PENDING_FILE, bytes)
        }
    }

    suspend fun pending(): NativeOAuthPendingFlow? = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val flow = readPending() ?: return@withLock null
            if (expired(flow.createdAtMillis, PENDING_TTL_MILLIS)) {
                delete(PENDING_FILE)
                return@withLock null
            }
            flow
        }
    }

    /**
     * One-time consumption. A null result covers absence, expiry and any state mismatch;
     * only a state match deletes the pending record.
     */
    suspend fun consumePending(state: String): NativeOAuthPendingFlow? = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val flow = readPending() ?: return@withLock null
            if (expired(flow.createdAtMillis, PENDING_TTL_MILLIS)) {
                delete(PENDING_FILE)
                return@withLock null
            }
            if (!constantTimeEquals(flow.state, state)) return@withLock null
            delete(PENDING_FILE)
            flow
        }
    }

    suspend fun clearPending(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            delete(PENDING_FILE)
        }
    }

    suspend fun saveCallback(result: NativeOAuthCallbackResult): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val bytes = if (result is NativeOAuthCallbackResult.Success) {
                buildJsonObject {
                    put("format", FORMAT)
                    put("version", VERSION)
                    put("kind", CALLBACK_KIND)
                    put("profileId", result.profileId)
                    put("revision", result.revision)
                    put("configFingerprint", result.configFingerprint)
                    put("createdAtMillis", result.createdAtMillis)
                    put("result", CODE_RESULT)
                    put("code", result.code)
                    put("codeVerifier", result.codeVerifier)
                }.toString().toByteArray(Charsets.UTF_8)
            } else {
                val failure = result as NativeOAuthCallbackResult.Failure
                buildJsonObject {
                    put("format", FORMAT)
                    put("version", VERSION)
                    put("kind", CALLBACK_KIND)
                    put("profileId", failure.profileId)
                    put("revision", failure.revision)
                    put("configFingerprint", failure.configFingerprint)
                    put("createdAtMillis", failure.createdAtMillis)
                    put("result", ERROR_RESULT)
                    put("error", failure.error.name)
                }.toString().toByteArray(Charsets.UTF_8)
            }
            write(CALLBACK_FILE, bytes)
        }
    }

    /** One-time callback consumption; expired or absent results are deleted and ignored. */
    suspend fun consumeCallback(): NativeOAuthCallbackResult? = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val result = readCallback() ?: return@withLock null
            if (expired(result.createdAtMillis, CALLBACK_TTL_MILLIS)) {
                delete(CALLBACK_FILE)
                return@withLock null
            }
            delete(CALLBACK_FILE)
            result
        }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            var failure: NativeOAuthFlowException? = null
            try {
                delete(PENDING_FILE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: NativeOAuthFlowException) {
                failure = failed
            }
            try {
                delete(CALLBACK_FILE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: NativeOAuthFlowException) {
                if (failure == null) failure = failed
            }
            failure?.let { throw it }
        }
    }

    private fun expired(createdAtMillis: Long, ttlMillis: Long): Boolean {
        val now = clock().coerceAtLeast(0)
        return createdAtMillis > now || now - createdAtMillis >= ttlMillis
    }

    private suspend fun readPending(): NativeOAuthPendingFlow? {
        val bytes = read(PENDING_FILE) ?: return null
        try {
            if (bytes.size > MAX_FILE_BYTES) throw NativeOAuthFlowException(
                NativeOAuthFlowException.Reason.CORRUPT)
            val root = JsonObjectValue.parse(strictUtf8(bytes), JsonInputLimits(MAX_FILE_BYTES, 8)).json
            require(root.keys == PENDING_FIELDS)
            require(string(root, "format") == FORMAT)
            require(integer(root, "version") == VERSION)
            require(string(root, "kind") == PENDING_KIND)
            return NativeOAuthPendingFlow(
                profileId = string(root, "profileId"),
                revision = integer(root, "revision"),
                configFingerprint = string(root, "configFingerprint"),
                state = string(root, "state"),
                codeVerifier = string(root, "codeVerifier"),
                createdAtMillis = integer(root, "createdAtMillis"),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.CORRUPT)
        }
    }

    private suspend fun readCallback(): NativeOAuthCallbackResult? {
        val bytes = read(CALLBACK_FILE) ?: return null
        try {
            if (bytes.size > MAX_FILE_BYTES) throw NativeOAuthFlowException(
                NativeOAuthFlowException.Reason.CORRUPT)
            val root = JsonObjectValue.parse(strictUtf8(bytes), JsonInputLimits(MAX_FILE_BYTES, 8)).json
            require(string(root, "format") == FORMAT)
            require(integer(root, "version") == VERSION)
            require(string(root, "kind") == CALLBACK_KIND)
            val common = CallbackCommon(
                profileId = string(root, "profileId"),
                revision = integer(root, "revision"),
                configFingerprint = string(root, "configFingerprint"),
                createdAtMillis = integer(root, "createdAtMillis"),
                result = string(root, "result"),
            )
            return when (common.result) {
                CODE_RESULT -> {
                    require(root.keys == CALLBACK_CODE_FIELDS)
                    NativeOAuthCallbackResult.Success(
                        common.profileId, common.revision, common.configFingerprint,
                        common.createdAtMillis, string(root, "code"), string(root, "codeVerifier"),
                    )
                }
                ERROR_RESULT -> {
                    require(root.keys == CALLBACK_ERROR_FIELDS)
                    NativeOAuthCallbackResult.Failure(
                        common.profileId, common.revision, common.configFingerprint,
                        common.createdAtMillis,
                        NativeOAuthCallbackError.valueOf(string(root, "error")),
                    )
                }
                else -> error("Invalid OAuth callback result")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.CORRUPT)
        }
    }

    private class CallbackCommon(
        val profileId: String,
        val revision: Long,
        val configFingerprint: String,
        val createdAtMillis: Long,
        val result: String,
    )

    private suspend fun read(source: File): ByteArray? {
        if (!source.exists() && !File(source.path + BACKUP_SUFFIX).exists()) return null
        return try {
            AtomicFile(source).openRead().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_FILE_BYTES) throw NativeOAuthFlowException(
                        NativeOAuthFlowException.Reason.CORRUPT)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: NativeOAuthFlowException) {
            throw failure
        } catch (_: Exception) {
            throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.CORRUPT)
        }
    }

    private suspend fun write(name: String, bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.INVALID)
        if (!directory.isDirectory && !directory.mkdirs()) throw NativeOAuthFlowException(
            NativeOAuthFlowException.Reason.UNAVAILABLE)
        val atomic = AtomicFile(File(directory, name))
        val output = try {
            atomic.startWrite()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.UNAVAILABLE)
        }
        try {
            output.write(bytes)
            output.fd.sync()
            currentCoroutineContext().ensureActive()
            atomic.finishWrite(output)
        } catch (cancelled: CancellationException) {
            atomic.failWrite(output)
            throw cancelled
        } catch (_: Exception) {
            atomic.failWrite(output)
            throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.UNAVAILABLE)
        }
    }

    private fun delete(name: String) {
        val source = File(directory, name)
        if (!source.exists() && !File(source.path + BACKUP_SUFFIX).exists()) return
        try {
            AtomicFile(source).delete()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.UNAVAILABLE)
        }
        if (source.exists() || File(source.path + BACKUP_SUFFIX).exists()) {
            throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.UNAVAILABLE)
        }
    }

    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw NativeOAuthFlowException(NativeOAuthFlowException.Reason.CORRUPT)
    }

    private fun string(value: JsonObject, key: String): String =
        (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Invalid OAuth flow field")

    private fun integer(value: JsonObject, key: String): Long {
        val primitive = value[key] as? JsonPrimitive ?: error("Invalid OAuth flow field")
        require(!primitive.isString && INTEGER.matches(primitive.content)) { "Invalid OAuth flow field" }
        return primitive.content.toLongOrNull() ?: error("Invalid OAuth flow field")
    }

    private fun constantTimeEquals(first: String, second: String): Boolean =
        java.security.MessageDigest.isEqual(
            first.toByteArray(Charsets.US_ASCII),
            second.toByteArray(Charsets.US_ASCII),
        )

    private companion object {
        const val DIRECTORY_NAME = "native_oauth_flow"
        const val FORMAT = "lnt-native-oauth-flow"
        const val VERSION = 1L
        const val PENDING_KIND = "pending"
        const val CALLBACK_KIND = "callback"
        const val CODE_RESULT = "code"
        const val ERROR_RESULT = "error"
        const val PENDING_FILE = "pending.json"
        const val CALLBACK_FILE = "callback.json"
        const val BACKUP_SUFFIX = ".bak"
        const val MAX_FILE_BYTES = 64 * 1024
        const val PENDING_TTL_MILLIS = 10 * 60_000L
        const val CALLBACK_TTL_MILLIS = 5 * 60_000L
        val INTEGER = Regex("0|[1-9][0-9]*")
        val PENDING_FIELDS = setOf(
            "format", "version", "kind", "profileId", "revision", "configFingerprint",
            "state", "codeVerifier", "createdAtMillis",
        )
        val CALLBACK_CODE_FIELDS = setOf(
            "format", "version", "kind", "profileId", "revision", "configFingerprint",
            "createdAtMillis", "result", "code", "codeVerifier",
        )
        val CALLBACK_ERROR_FIELDS = setOf(
            "format", "version", "kind", "profileId", "revision", "configFingerprint",
            "createdAtMillis", "result", "error",
        )
    }
}
