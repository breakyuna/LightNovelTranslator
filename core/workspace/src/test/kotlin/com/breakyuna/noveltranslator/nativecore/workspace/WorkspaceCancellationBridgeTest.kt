package com.breakyuna.noveltranslator.nativecore.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCancellationBridgeTest {
    @Test
    fun cancellingTheJobCancelsTheWorkspaceToken() = runBlocking {
        val token = WorkspaceCancellation()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            val owner = currentCoroutineContext()[Job]
            val bridge = linkWorkspaceCancellation(token, owner)
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                releaseWorkspaceCancellation(bridge, owner)
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue(token.isCancelled)
    }

    @Test
    fun normalCompletionDoesNotCancelTheToken() = runBlocking {
        val token = WorkspaceCancellation()
        val job = launch {
            val context = currentCoroutineContext()
            val bridge = linkWorkspaceCancellation(token, context[Job])
            releaseWorkspaceCancellation(bridge, context[Job])
        }
        job.join()

        assertFalse(token.isCancelled)
    }
}
