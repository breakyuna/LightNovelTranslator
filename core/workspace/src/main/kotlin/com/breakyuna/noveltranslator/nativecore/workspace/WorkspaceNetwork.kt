package com.breakyuna.noveltranslator.nativecore.workspace

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Failure classification shared by search and fetch. Ports translate transport/provider
 * errors into these values instead of leaking exception text to scripts or logs.
 */
enum class WorkspaceNetworkFailure {
    INVALID_REQUEST,
    POLICY_REJECTED,
    DNS,
    CONNECT,
    TLS,
    TIMEOUT,
    RATE_LIMITED,
    HTTP_STATUS,
    CONTENT_TOO_LARGE,
    UNSUPPORTED_CONTENT,
    CANCELLED,
    INTERNAL,
}

class WorkspaceNetworkError(
    val category: WorkspaceNetworkFailure,
    val httpStatus: Int? = null,
    val retryAfterMillis: Long? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599) { "Invalid HTTP status" }
        require(retryAfterMillis == null || retryAfterMillis >= 0) { "Invalid retry delay" }
    }

    override fun toString(): String =
        "WorkspaceNetworkError($category, status=$httpStatus, retryAfter=$retryAfterMillis)"
}

/** A search snippet is bounded display data; the full page is a separate fetch. */
class WorkspaceSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
) {
    init {
        require(title.length <= 512 && title.none { it.isISOControl() }) { "Invalid search title" }
        require(snippet.length <= 4_096 && snippet.none { it == '\u0000' }) { "Invalid search snippet" }
        WorkspaceUrls.requirePublicHttpUrl(url)
        requireWellFormedUnicode(title)
        requireWellFormedUnicode(snippet)
    }

    override fun toString(): String = "WorkspaceSearchHit(url=${url.substringBefore('?')})"
}

class WorkspaceSearchRequest private constructor(
    val query: String,
    val maxResults: Int,
    val maxBytesPerResult: Long,
    val language: String?,
    val deadlineAtMillis: Long,
    val cancellation: WorkspaceCancellation,
) {
    init {
        require(query.isNotBlank() && query.length <= 512) { "Invalid search query" }
        require(query.none { it.isISOControl() }) { "Control character in search query" }
        requireWellFormedUnicode(query)
        require(maxResults in 1..20) { "Invalid search result budget" }
        require(maxBytesPerResult in 1..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Invalid search byte budget" }
        require(language == null || (language.isNotBlank() && language.length <= 32)) { "Invalid search language" }
        require(deadlineAtMillis > 0) { "Invalid search deadline" }
    }

    companion object {
        fun create(
            query: String,
            deadlineAtMillis: Long,
            cancellation: WorkspaceCancellation,
            maxResults: Int = 5,
            maxBytesPerResult: Long = 8_192,
            language: String? = null,
        ): WorkspaceSearchRequest = WorkspaceSearchRequest(
            query, maxResults, maxBytesPerResult, language, deadlineAtMillis, cancellation,
        )
    }
}

sealed interface WorkspaceSearchOutcome {
    class Found(hits: List<WorkspaceSearchHit>, val truncated: Boolean) : WorkspaceSearchOutcome {
        val hits: List<WorkspaceSearchHit> = workspaceFrozenList(hits)

        init {
            require(this.hits.size <= 20) { "Search result budget exceeded" }
        }
    }

    class Failed(val error: WorkspaceNetworkError) : WorkspaceSearchOutcome

    data object Cancelled : WorkspaceSearchOutcome
}

/**
 * Search port (DeepSeek-first chain and MCP fallbacks are adapter policy, R22). An
 * implementation must return an empty [WorkspaceSearchOutcome.Found] for a normal empty
 * result, must not fall back silently, must keep every hit inside
 * [WorkspaceSearchRequest.maxBytesPerResult] and must observe cancellation for the whole chain.
 */
interface WorkspaceSearchPort {
    suspend fun search(request: WorkspaceSearchRequest): WorkspaceSearchOutcome
}

class WorkspaceFetchRequest private constructor(
    val url: String,
    val maxBytes: Long,
    val allowedMediaTypes: Set<String>,
    val deadlineAtMillis: Long,
    val cancellation: WorkspaceCancellation,
) {
    init {
        WorkspaceUrls.requirePublicHttpUrl(url)
        require(maxBytes in 1..WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Invalid fetch byte budget" }
        require(allowedMediaTypes.isNotEmpty()) { "Fetch requires a media type policy" }
        require(allowedMediaTypes.all { it.isNotBlank() && it.length <= 128 }) { "Invalid media type policy" }
        require(deadlineAtMillis > 0) { "Invalid fetch deadline" }
    }

    companion object {
        fun create(
            url: String,
            deadlineAtMillis: Long,
            cancellation: WorkspaceCancellation,
            maxBytes: Long = 4L * 1024 * 1024,
            allowedMediaTypes: Set<String> = DEFAULT_MEDIA_TYPES,
        ): WorkspaceFetchRequest = WorkspaceFetchRequest(
            url, maxBytes, workspaceFrozenSet(allowedMediaTypes), deadlineAtMillis, cancellation,
        )

        val DEFAULT_MEDIA_TYPES: Set<String> = setOf(
            "text/plain",
            "text/html",
            "text/markdown",
            "application/json",
            "application/xml",
            "application/pdf",
        )
    }
}

/** Bounded fetched document; the body is external data, never an instruction source. */
class WorkspaceFetchedDocument(
    val finalUrl: String,
    val mediaType: String,
    bytes: ByteArray,
    val truncated: Boolean,
) {
    private val storage: ByteArray = bytes.copyOf()
    val sha256: String

    init {
        WorkspaceUrls.requirePublicHttpUrl(finalUrl)
        require(mediaType.isNotBlank() && mediaType.length <= 128) { "Invalid media type" }
        require(storage.size <= WorkspaceHardLimits.MAX_SINGLE_BYTES) { "Fetched document too large" }
        sha256 = sha256Hex(storage)
    }

    /** Defensive copy; external content is not shared mutable state. */
    val bytes: ByteArray get() = storage.copyOf()

    val sizeBytes: Long get() = storage.size.toLong()

    override fun toString(): String =
        "WorkspaceFetchedDocument(mediaType=$mediaType, size=$sizeBytes, truncated=$truncated)"
}

sealed interface WorkspaceFetchOutcome {
    class Fetched(val document: WorkspaceFetchedDocument) : WorkspaceFetchOutcome

    class Failed(val error: WorkspaceNetworkError) : WorkspaceFetchOutcome

    data object Cancelled : WorkspaceFetchOutcome
}

/**
 * Single-document fetch port. Implementations must stop at [WorkspaceFetchRequest.maxBytes]
 * (marking [WorkspaceFetchedDocument.truncated] only when that is explicitly allowed), reject
 * media types outside [WorkspaceFetchRequest.allowedMediaTypes], observe cancellation, and
 * never silently fall back to a direct connection when a proxy fails. Redirects and loopback
 * policy are adapter concerns.
 */
interface WorkspaceFetchPort {
    suspend fun fetch(request: WorkspaceFetchRequest): WorkspaceFetchOutcome
}

private object WorkspaceUrls {
    fun requirePublicHttpUrl(value: String) {
        require(value.length in 1..4_096) { "Invalid URL" }
        require(value.none { it.isISOControl() || it.isWhitespace() }) { "Invalid URL" }
        val uri = try {
            URI(value)
        } catch (failure: URISyntaxException) {
            throw IllegalArgumentException("Invalid URL", failure)
        }
        require(uri.isAbsolute) { "URL must be absolute" }
        val host = uri.host
        require(host != null && host.isNotBlank()) { "URL requires a host" }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "URL scheme is not allowed" }
        require(uri.userInfo == null) { "URL credentials are not allowed" }
    }
}
