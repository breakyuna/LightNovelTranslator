package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.domain.JsonInputLimits
import com.breakyuna.noveltranslator.nativecore.domain.JsonObjectValue
import com.breakyuna.noveltranslator.nativecore.storage.NativeMcpServerEntry
import com.breakyuna.noveltranslator.nativecore.storage.NativeMcpServerRecord
import com.breakyuna.noveltranslator.nativecore.storage.NativeMcpSettingsSnapshot
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceCancellation
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkError
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceNetworkFailure
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchHit
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchOutcome
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchPort
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSearchRequest
import com.breakyuna.noveltranslator.nativecore.workspace.mcp.McpContent
import com.breakyuna.noveltranslator.nativecore.workspace.mcp.McpToolCatalog
import com.breakyuna.noveltranslator.nativecore.workspace.mcp.McpToolDescriptor
import com.breakyuna.noveltranslator.nativecore.workspace.mcp.McpToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMcpToolBridgeTest {
    private val fingerprint = "a".repeat(64)

    @Test fun draftConversionIsDeterministicAndValidated() {
        val draft = NativeMcpServerDraft(
            id = " alpha ",
            name = " Search ",
            url = " https://mcp.example.com/rpc ",
            enabled = true,
            allowPrivateNetwork = false,
            allowedToolsText = "search, lookup\nsearch",
            timeoutMillisText = "30000",
            maxToolsText = "64",
        )
        val first = draft.toRecord()
        val second = draft.toRecord()
        assertEquals(first, second)
        assertEquals("alpha", first.id)
        assertEquals(setOf("search", "lookup"), first.allowedTools)
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(id = "bad id").toRecord()
        }
        assertThrows(IllegalArgumentException::class.java) {
            draft.copy(maxToolsText = "0").toRecord()
        }
    }

    @Test fun argumentsMustBeABoundedJsonObject() {
        assertEquals(setOf("query"), NativeMcpToolJson.parseArguments("""{"query":"a"}""").json.keys)
        assertThrows(IllegalArgumentException::class.java) {
            NativeMcpToolJson.parseArguments("""["a"]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeMcpToolJson.parseArguments("""{"q":"${"x".repeat(NativeMcpToolJson.MAX_ARGUMENT_CHARS)}"}""")
        }
    }

    @Test fun agentCatalogOnlyExposesReadOnlyToolsAndBoundsDescriptions() {
        val catalog = McpToolCatalog(
            "alpha",
            listOf(
                descriptor("read", readOnly = true, description = "d".repeat(2_000)),
                descriptor("write", readOnly = false, description = "must not be exposed"),
            ),
            truncated = false,
        )
        val text = NativeMcpToolJson.readOnlyCatalog(catalog)
        val root = JsonObjectValue.parse(text, JsonInputLimits(128 * 1024, 8)).json
        assertTrue(root.getValue("external_untrusted").jsonPrimitive.content.toBoolean())
        assertEquals(1, root.getValue("tool_count").jsonPrimitive.content.toInt())
        val tool = root.getValue("tools").let { element ->
            (element as kotlinx.serialization.json.JsonArray).single().jsonObject
        }
        assertEquals("read", tool.getValue("name").jsonPrimitive.content)
        assertTrue(tool.getValue("description").jsonPrimitive.content.length <= 512)
        assertFalse(text.contains("must not be exposed"))

        assertEquals("read", nativeMcpReadOnlyTool(catalog, "read").externalName)
        assertThrows(NativeMcpRejected::class.java) {
            nativeMcpReadOnlyTool(catalog, "write")
        }
        assertThrows(NativeMcpRejected::class.java) {
            nativeMcpReadOnlyTool(catalog, "missing")
        }
    }

    @Test fun toolResultIsBoundedAndMarkedExternalUntrusted() {
        val result = McpToolResult(
            serverId = "alpha",
            toolName = "read",
            isError = false,
            content = listOf(McpContent.Text("x".repeat(100_000))),
            structuredContent = null,
        )
        val text = NativeMcpToolJson.result(result)
        val root = JsonObjectValue.parse(text, JsonInputLimits(512 * 1024, 8)).json
        assertTrue(root.getValue("external_untrusted").jsonPrimitive.content.toBoolean())
        assertTrue(root.getValue("truncated").jsonPrimitive.content.toBoolean())
        val content = root.getValue("content") as kotlinx.serialization.json.JsonArray
        val item = content.single().jsonObject
        assertTrue(item.getValue("text").jsonPrimitive.content.length <= 32 * 1024)
    }

    @Test fun searchSelectionUsesOnlyEnabledServersWithSearchAllowlist() {
        val snapshot = NativeMcpSettingsSnapshot(
            servers = listOf(
                entry("alpha", enabled = true, tools = listOf("search")),
                entry("beta", enabled = false, tools = listOf("search")),
                entry("gamma", enabled = true, tools = listOf("lookup")),
            ),
            revision = 1,
            fingerprint = fingerprint,
        )
        assertEquals(listOf("alpha"), nativeMcpSearchCandidates(snapshot).map { it.config.id })
    }

    @Test fun searchFallbackAdvancesAfterPrimaryFailureAndNeverReplacesCancellation() = runBlocking {
        val primaryFailure = WorkspaceSearchOutcome.Failed(
            WorkspaceNetworkError(WorkspaceNetworkFailure.CONNECT),
        )
        val hit = WorkspaceSearchHit("title", "https://example.com/a", "snippet")
        val primary = FakeSearch(primaryFailure)
        val fallbackHit = FakeSearch(WorkspaceSearchOutcome.Found(listOf(hit), truncated = false))
        val request = request()
        val outcome = NativeMcpSearchFallback(primary) { listOf(fallbackHit) }.search(request)
        assertTrue(outcome is WorkspaceSearchOutcome.Found)
        assertEquals(1, primary.calls)
        assertEquals(1, fallbackHit.calls)

        val primaryHit = FakeSearch(WorkspaceSearchOutcome.Found(listOf(hit), truncated = false))
        val shouldNotRun = FakeSearch(WorkspaceSearchOutcome.Found(emptyList(), false))
        val primaryOutcome = NativeMcpSearchFallback(primaryHit) { listOf(shouldNotRun) }.search(request)
        assertTrue(primaryOutcome is WorkspaceSearchOutcome.Found)
        assertEquals(0, shouldNotRun.calls)

        val cancelled = FakeSearch(WorkspaceSearchOutcome.Cancelled)
        val notCalled = FakeSearch(WorkspaceSearchOutcome.Found(emptyList(), false))
        val cancelledOutcome = NativeMcpSearchFallback(cancelled) { listOf(notCalled) }.search(request)
        assertTrue(cancelledOutcome is WorkspaceSearchOutcome.Cancelled)
        assertEquals(0, notCalled.calls)

        val disabled = NativeMcpSearchFallback(FakeSearch(primaryFailure)) { emptyList() }
            .search(request)
        assertEquals(
            WorkspaceNetworkFailure.CONNECT,
            (disabled as WorkspaceSearchOutcome.Failed).error.category,
        )
    }

    private fun entry(id: String, enabled: Boolean, tools: List<String>) = NativeMcpServerEntry(
        config = NativeMcpServerRecord(
            id = id,
            name = id,
            url = "https://mcp.example.com/rpc",
            enabled = enabled,
            allowedTools = tools,
        ),
        revision = 1,
        fingerprint = fingerprint,
    )

    private fun descriptor(name: String, readOnly: Boolean, description: String) = McpToolDescriptor(
        serverId = "alpha",
        name = name,
        description = description,
        inputSchema = JsonObjectValue.parse("{}", JsonInputLimits(16, 4)),
        readOnly = readOnly,
    )

    private fun request() = WorkspaceSearchRequest.create(
        query = "query",
        deadlineAtMillis = System.currentTimeMillis() + 5_000,
        cancellation = WorkspaceCancellation(),
    )

    private class FakeSearch(private val outcome: WorkspaceSearchOutcome) : WorkspaceSearchPort {
        var calls = 0
        override suspend fun search(request: WorkspaceSearchRequest): WorkspaceSearchOutcome {
            calls += 1
            return outcome
        }
    }
}
