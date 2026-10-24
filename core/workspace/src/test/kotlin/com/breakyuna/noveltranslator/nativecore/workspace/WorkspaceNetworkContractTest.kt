package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNetworkContractTest {
    private val cancellation = WorkspaceCancellation()

    @Test
    fun searchRequestIsBoundedAndValidated() {
        val request = WorkspaceSearchRequest.create(
            query = "workspace api",
            deadlineAtMillis = 5_000,
            cancellation = cancellation,
            maxResults = 3,
            maxBytesPerResult = 4_096,
            language = "ja",
        )
        assertEquals("workspace api", request.query)
        assertEquals(3, request.maxResults)

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchRequest.create("", 5_000, cancellation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchRequest.create("q", 5_000, cancellation, maxResults = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchRequest.create("q", 5_000, cancellation, maxResults = 21)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchRequest.create("q", 5_000, cancellation, language = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchRequest.create("q", deadlineAtMillis = 0, cancellation = cancellation)
        }
    }

    @Test
    fun hitsAndFetchesRejectNonHttpSchemesAndCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchHit("title", "ftp://example.com/a", "snippet")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSearchHit("title", "https://user:secret@example.com/a", "snippet")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFetchRequest.create("file:///etc/passwd", 5_000, cancellation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFetchRequest.create("https://user:secret@example.com/a", 5_000, cancellation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFetchRequest.create("https://example.com/a", 5_000, cancellation, maxBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFetchRequest.create("https://example.com/a", 5_000, cancellation, allowedMediaTypes = emptySet())
        }

        val hit = WorkspaceSearchHit("Title", "https://example.com/a", "Snippet")
        assertEquals("https://example.com/a", hit.url)
    }

    @Test
    fun failureCategoriesSeparateCancellationTimeoutAndContentProblems() {
        val categories = WorkspaceNetworkFailure.values().toSet()
        assertTrue(WorkspaceNetworkFailure.CANCELLED in categories)
        assertTrue(WorkspaceNetworkFailure.TIMEOUT in categories)
        assertTrue(WorkspaceNetworkFailure.CONTENT_TOO_LARGE in categories)
        assertTrue(WorkspaceNetworkFailure.HTTP_STATUS in categories)
        assertTrue(WorkspaceNetworkFailure.POLICY_REJECTED in categories)

        val rateLimited = WorkspaceNetworkError(
            WorkspaceNetworkFailure.RATE_LIMITED,
            httpStatus = 429,
            retryAfterMillis = 30_000,
        )
        assertEquals(429, rateLimited.httpStatus)
        assertEquals(30_000L, rateLimited.retryAfterMillis)
        assertEquals("WorkspaceNetworkError(RATE_LIMITED, status=429, retryAfter=30000)", rateLimited.toString())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceNetworkError(WorkspaceNetworkFailure.DNS, httpStatus = 42)
        }
    }

    @Test
    fun fetchOutcomeKeepsOnlyBoundedBodies() {
        val document = WorkspaceFetchedDocument(
            finalUrl = "https://example.com/page",
            mediaType = "text/plain",
            bytes = "body".toByteArray(Charsets.UTF_8),
            truncated = false,
        )
        assertEquals(4L, document.sizeBytes)
        assertEquals(sha256Of("body"), document.sha256)
        assertEquals("WorkspaceFetchedDocument(mediaType=text/plain, size=4, truncated=false)", document.toString())
    }
}
