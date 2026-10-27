package com.breakyuna.noveltranslator.nativecore.workspace.mcp

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkFailure
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchOutcome
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeInvoker(
    private val result: McpToolResult,
) : McpToolInvoker {
    var calls: Int = 0
    var lastArguments: JsonObjectValue? = null

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObjectValue,
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
    ): McpToolResult {
        calls += 1
        lastArguments = arguments
        return result
    }
}

private class ThrowingInvoker(
    private val failure: McpException,
) : McpToolInvoker {
    var calls: Int = 0

    override suspend fun callTool(
        toolName: String,
        arguments: JsonObjectValue,
        cancellation: WorkspaceCancellation,
        deadlineAtMillis: Long,
    ): McpToolResult {
        calls += 1
        throw failure
    }
}

class McpSearchProviderTest {
    private fun request(
        maxResults: Int = 5,
        maxBytesPerResult: Long = 4_096,
        cancellation: WorkspaceCancellation = WorkspaceCancellation(),
        deadlineAtMillis: Long = System.currentTimeMillis() + 30_000,
    ) = WorkspaceSearchRequest.create(
        query = "query",
        deadlineAtMillis = deadlineAtMillis,
        cancellation = cancellation,
        maxResults = maxResults,
        maxBytesPerResult = maxBytesPerResult,
    )

    private fun result(text: String, structured: JsonObjectValue? = null): McpToolResult =
        McpToolResult(
            serverId = "server",
            toolName = "search",
            isError = false,
            content = listOf(McpContent.Text(text)),
            structuredContent = structured,
        )

    private fun json(text: String): JsonObjectValue =
        JsonObjectValue.parse(text, JsonInputLimits(64 * 1024, 16))

    @Test
    fun structuredResultsBecomeBoundedHits() = runBlocking {
        val invoker = FakeInvoker(result(
            text = "ignored",
            structured = json(
                """{"results":[
                    {"title":"First","url":"https://example.com/a","description":"snip a"},
                    {"title":"Second","url":"https://example.com/b","snippet":"snip b"}
                ]}""",
            ),
        ))
        val provider = McpSearchProvider(invoker, McpSearchTool("search"))
        val outcome = provider.search(request(maxResults = 2))

        val found = outcome as WorkspaceSearchOutcome.Found
        assertEquals(2, found.hits.size)
        assertEquals("First", found.hits[0].title)
        assertEquals("snip b", found.hits[1].snippet)
        assertFalse(found.truncated)
    }

    @Test
    fun textJsonResultsAndDirectUrlsAreSupported() = runBlocking {
        val textProvider = McpSearchProvider(
            FakeInvoker(result("""{"results":[{"title":"Text","url":"https://example.com/t","content":"body"}]}""")),
            McpSearchTool("search"),
        )
        val textOutcome = textProvider.search(request()) as WorkspaceSearchOutcome.Found
        assertEquals("https://example.com/t", textOutcome.hits.single().url)
        assertEquals("body", textOutcome.hits.single().snippet)

        val urlProvider = McpSearchProvider(
            FakeInvoker(result("https://example.com/direct")),
            McpSearchTool("search"),
        )
        val urlOutcome = urlProvider.search(request()) as WorkspaceSearchOutcome.Found
        assertEquals("https://example.com/direct", urlOutcome.hits.single().url)
    }

    @Test
    fun privateAndCredentialUrlsAreNotSearchHits() = runBlocking {
        val provider = McpSearchProvider(
            FakeInvoker(result(
                """{"results":[
                    {"title":"Private","url":"http://127.0.0.1/secret","description":"x"},
                    {"title":"Credential","url":"https://user:pass@example.com/a","description":"x"},
                    {"title":"Public","url":"https://example.com/ok","description":"x"}
                ]}""",
            )),
            McpSearchTool("search"),
        )
        val found = provider.search(request()) as WorkspaceSearchOutcome.Found
        assertEquals(1, found.hits.size)
        assertEquals("https://example.com/ok", found.hits.single().url)
    }

    @Test
    fun resultCountAndSnippetBytesAreTruncated() = runBlocking {
        val provider = McpSearchProvider(
            FakeInvoker(result(
                """{"results":[
                    {"title":"One","url":"https://example.com/1","description":"abcdef"},
                    {"title":"Two","url":"https://example.com/2","description":"abcdef"},
                    {"title":"Three","url":"https://example.com/3","description":"abcdef"}
                ]}""",
            )),
            McpSearchTool("search"),
        )
        val found = provider.search(request(maxResults = 2, maxBytesPerResult = 4)) as WorkspaceSearchOutcome.Found
        assertEquals(2, found.hits.size)
        assertTrue(found.truncated)
        assertTrue(found.hits.all { it.snippet.toByteArray(Charsets.UTF_8).size <= 4 })
    }

    @Test
    fun exactResultCountIsNotMarkedTruncated() = runBlocking {
        val provider = McpSearchProvider(
            FakeInvoker(result("""{"results":[{"title":"One","url":"https://example.com/1"}]}""")),
            McpSearchTool("search"),
        )
        val found = provider.search(request(maxResults = 1)) as WorkspaceSearchOutcome.Found
        assertEquals(1, found.hits.size)
        assertFalse(found.truncated)
    }

    @Test
    fun toolErrorMapsToProviderFailure() = runBlocking {
        val failed = McpToolResult(
            serverId = "server",
            toolName = "search",
            isError = true,
            content = listOf(McpContent.Text("provider-private-error")),
            structuredContent = null,
        )
        val outcome = McpSearchProvider(FakeInvoker(failed), McpSearchTool("search")).search(request())
        val failure = outcome as WorkspaceSearchOutcome.Failed
        assertEquals(WorkspaceNetworkFailure.INTERNAL, failure.error.category)
        assertFalse(failure.error.toString().contains("provider-private-error"))
    }

    @Test
    fun timeoutAndHttpStatusMapToWorkspaceCategories() = runBlocking {
        val timeout = McpSearchProvider(
            ThrowingInvoker(McpException(McpFailureKind.TIMEOUT)),
            McpSearchTool("search"),
        ).search(request()) as WorkspaceSearchOutcome.Failed
        assertEquals(WorkspaceNetworkFailure.TIMEOUT, timeout.error.category)

        val rateLimited = McpSearchProvider(
            ThrowingInvoker(McpException(McpFailureKind.HTTP_STATUS, httpStatus = 429, retryAfterMillis = 2_000)),
            McpSearchTool("search"),
        ).search(request()) as WorkspaceSearchOutcome.Failed
        assertEquals(WorkspaceNetworkFailure.RATE_LIMITED, rateLimited.error.category)
        assertEquals(429, rateLimited.error.httpStatus ?: -1)
        assertEquals(2_000L, rateLimited.error.retryAfterMillis ?: -1L)
    }

    @Test
    fun cancellationDoesNotCallTheTool() = runBlocking {
        val invoker = FakeInvoker(result("""{"results":[]}"""))
        val cancellation = WorkspaceCancellation().also { it.cancel() }
        val outcome = McpSearchProvider(invoker, McpSearchTool("search")).search(request(cancellation = cancellation))
        assertEquals(WorkspaceSearchOutcome.Cancelled, outcome)
        assertEquals(0, invoker.calls)
    }

    @Test
    fun malformedOutputIsNormalEmptySuccessAndDoesNotFabricateHits() = runBlocking {
        val provider = McpSearchProvider(FakeInvoker(result("not a result")), McpSearchTool("search"))
        val found = provider.search(request()) as WorkspaceSearchOutcome.Found
        assertTrue(found.hits.isEmpty())
        assertFalse(found.truncated)
    }

    @Test
    fun customArgumentFieldsAreUsed() = runBlocking {
        val invoker = FakeInvoker(result("""{"results":[]}"""))
        val provider = McpSearchProvider(
            invoker,
            McpSearchTool(toolName = "web_search", queryField = "q", limitField = "count"),
        )
        provider.search(request(maxResults = 3))
        val arguments = requireNotNull(invoker.lastArguments).json
        assertEquals("query", arguments["q"]?.toString()?.trim('"'))
        assertEquals("3", arguments["count"]?.toString())
    }
}
