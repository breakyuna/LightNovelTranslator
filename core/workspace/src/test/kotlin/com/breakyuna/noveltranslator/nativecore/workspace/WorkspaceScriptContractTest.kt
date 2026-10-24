package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceScriptContractTest {
    @Test
    fun hostRequestsRequireTheirDeclaredCapability() {
        val print = WorkspaceHostRequest.Print(WorkspacePath("work/report.pdf"))
        assertThrows(WorkspaceCapabilityDenied::class.java) {
            WorkspaceHostPolicy.authorize(emptySet(), print)
        }
        WorkspaceHostPolicy.authorize(setOf(WorkspaceCapability.PRINT), print)

        val fetch = WorkspaceHostRequest.Fetch("https://example.com/a", 1_024)
        assertThrows(WorkspaceCapabilityDenied::class.java) {
            WorkspaceHostPolicy.authorize(setOf(WorkspaceCapability.SEARCH), fetch)
        }
        WorkspaceHostPolicy.authorize(setOf(WorkspaceCapability.FETCH), fetch)

        val search = WorkspaceHostRequest.Search("query", 3)
        assertEquals(WorkspaceCapability.SEARCH, search.requiredCapability)
    }

    @Test
    fun scriptRequestFreezesReadOnlyDescriptorsAndRejectsDuplicates() {
        val descriptor = materialSummary("mat-1").descriptor()
        assertTrue(descriptor.readOnly)

        val request = WorkspaceScriptRequest(
            commandId = CommandId("command-1"),
            projectId = PROJECT_ID,
            chatId = CHAT_ID,
            runId = RUN_ID,
            scriptId = "script-1",
            entryPoint = "main.js",
            materials = listOf(descriptor),
            grantedCapabilities = setOf(WorkspaceCapability.READ_MATERIALS),
            limits = WorkspaceScriptLimits.of(WorkspaceBudget()),
            deadlineAtMillis = 5_000,
            cancellation = WorkspaceCancellation(),
        )
        assertThrows(UnsupportedOperationException::class.java) {
            (request.materials as MutableList<WorkspaceMaterialDescriptor>).add(descriptor)
        }

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceScriptRequest(
                commandId = CommandId("command-1"),
                projectId = PROJECT_ID,
                chatId = CHAT_ID,
                runId = RUN_ID,
                scriptId = "script-1",
                entryPoint = "main.js",
                materials = listOf(descriptor, descriptor),
                grantedCapabilities = emptySet(),
                limits = WorkspaceScriptLimits.of(WorkspaceBudget()),
                deadlineAtMillis = 5_000,
                cancellation = WorkspaceCancellation(),
            )
        }
    }

    @Test
    fun scriptResultRejectsOutputBeyondItsHardCeiling() {
        val tooManyLogs = List(WorkspaceHardLimits.MAX_LOG_LINES + 1) {
            WorkspaceLogLine(WorkspaceLogStream.STDOUT, "line")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceScriptResult(0, emptyList(), emptyList(), logs = tooManyLogs)
        }
    }
}
