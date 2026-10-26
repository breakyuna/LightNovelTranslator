package com.breakyuna.noveltranslator.nativecore.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSearchChainTest {
    private fun request(): WorkspaceSearchRequest = WorkspaceSearchRequest.create(
        "query", System.currentTimeMillis() + 60_000, WorkspaceCancellation(), maxResults = 2)

    @Test
    fun emptyFoundStopsAndDoesNotFallBack() = runBlocking {
        var second = 0
        val chain = WorkspaceSearchChain(listOf(
            provider { WorkspaceSearchOutcome.Found(emptyList(), false) },
            provider { second++; WorkspaceSearchOutcome.Found(emptyList(), false) },
        ))
        val result = chain.search(request())
        assertTrue(result is WorkspaceSearchOutcome.Found)
        assertEquals(0, second)
    }

    @Test
    fun providerFailureFallsBackAndLastFailureIsReported() = runBlocking {
        val calls = ArrayList<String>()
        val chain = WorkspaceSearchChain(listOf(
            provider {
                calls += "first"
                WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.TIMEOUT))
            },
            provider {
                calls += "second"
                WorkspaceSearchOutcome.Found(listOf(
                    WorkspaceSearchHit("Title", "https://example.com/a", "Snippet"),
                ), false)
            },
        ))
        val result = chain.search(request())
        assertEquals(listOf("first", "second"), calls)
        assertTrue(result is WorkspaceSearchOutcome.Found)

        val failed = WorkspaceSearchChain(listOf(
            provider { WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.DNS)) },
            provider { WorkspaceSearchOutcome.Failed(WorkspaceNetworkError(WorkspaceNetworkFailure.HTTP_STATUS, 503)) },
        )).search(request())
        val failure = failed as WorkspaceSearchOutcome.Failed
        assertEquals(WorkspaceNetworkFailure.HTTP_STATUS, failure.error.category)
    }

    @Test
    fun cancellationStopsTheWholeChain() = runBlocking {
        val cancellation = WorkspaceCancellation()
        cancellation.cancel()
        var calls = 0
        val chain = WorkspaceSearchChain(listOf(
            provider { calls++; WorkspaceSearchOutcome.Found(emptyList(), false) },
        ))
        val request = WorkspaceSearchRequest.create(
            "query", System.currentTimeMillis() + 60_000, cancellation, maxResults = 2)
        assertEquals(WorkspaceSearchOutcome.Cancelled, chain.search(request))
        assertEquals(0, calls)
    }

    private fun provider(block: suspend (WorkspaceSearchRequest) -> WorkspaceSearchOutcome) =
        object : WorkspaceSearchPort {
            override suspend fun search(request: WorkspaceSearchRequest): WorkspaceSearchOutcome =
                block(request)
        }
}
