package com.breakyuna.noveltranslator.nativecore.workspace

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceScriptHostSessionTest {
    @Test fun fetchWaitsForActuallySuspendingPortAndCapsCalls() {
        var calls = 0
        val port = object : WorkspaceFetchPort {
            override suspend fun fetch(request: WorkspaceFetchRequest): WorkspaceFetchOutcome {
                kotlinx.coroutines.delay(1)
                calls++
                return WorkspaceFetchOutcome.Fetched(WorkspaceFetchedDocument(request.url,
                    "text/plain", "x".toByteArray(), false))
            }
        }
        val host = session(scriptRequest(capabilities = setOf(WorkspaceCapability.FETCH)), fetch = port)
        repeat(8) { assertTrue(host.fetch("https://example.com/", 1).contains("\"text\":\"x\"")) }
        assertThrows(WorkspaceScriptHostLimitExceeded::class.java) { host.fetch("https://example.com/", 1) }
        assertEquals(8, calls)
    }

    @Test fun fetchReservationsBoundCumulativeRequestedBytesBeforeAnotherCall() {
        var calls = 0
        val port = object : WorkspaceFetchPort {
            override suspend fun fetch(request: WorkspaceFetchRequest): WorkspaceFetchOutcome {
                calls++
                return WorkspaceFetchOutcome.Fetched(WorkspaceFetchedDocument(request.url,
                    "text/plain", "x".toByteArray(), false))
            }
        }
        val host = session(scriptRequest(capabilities = setOf(WorkspaceCapability.FETCH),
            limits = WorkspaceScriptLimits.of(WorkspaceBudget(maxOutputBytes = 1024))), fetch = port)
        host.fetch("https://example.com/", 1024)
        assertThrows(WorkspaceScriptHostLimitExceeded::class.java) { host.fetch("https://example.com/", 1) }
        assertEquals(1, calls)
    }

    @Test fun readsOnlyDeclaredMaterialsWithTheGrantedCapability() {
        val body = "hello".toByteArray(Charsets.UTF_8)
        val summary = materialSummary("mat-1", body)
        val reader = FakeScriptMaterialReader(mapOf(summary.id to summary), mapOf(summary.id to body))
        val session = session(scriptRequest(listOf(summary.descriptor())), reader)

        assertEquals("hello", session.readMaterial("mat-1"))
        assertEquals(1, reader.reads)
        assertEquals(WorkspaceScriptHostSession.DEFAULT_MAX_MATERIAL_READ_BYTES, reader.lastLimit)
    }

    @Test fun undeclaredMaterialIsRefusedAndForcesANonSuccessResult() {
        val session = session(scriptRequest())
        assertThrows(WorkspaceMaterialNotAllowed::class.java) { session.readMaterial("mat-1") }
        assertEquals(1, session.result(0).exitCode)
        assertTrue(session.result(0).changes.isEmpty())
    }

    @Test fun materialReadLimitAndFingerprintMismatchAreRejected() {
        val body = "hello".toByteArray(Charsets.UTF_8)
        val summary = materialSummary("mat-1", body)
        val reader = FakeScriptMaterialReader(mapOf(summary.id to summary), mapOf(summary.id to body))
        val limited = WorkspaceScriptHostSession(
            request = scriptRequest(listOf(summary.descriptor())),
            materials = reader,
            maxMaterialReadBytes = 4,
            maxTotalMaterialReadBytes = 8,
        )
        val tooLarge = assertThrows(WorkspaceMaterialUnavailable::class.java) {
            limited.readMaterial("mat-1")
        }
        assertEquals(WorkspaceMaterialUnavailable.Reason.TOO_LARGE, tooLarge.reason)

        reader.overrideContent = { id, _ ->
            WorkspaceMaterialContent(id, sha256Of("other"), "other".toByteArray(Charsets.UTF_8))
        }
        val mismatch = assertThrows(WorkspaceMaterialUnavailable::class.java) {
            session(scriptRequest(listOf(summary.descriptor())), reader).readMaterial("mat-1")
        }
        assertEquals(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH, mismatch.reason)
    }

    @Test fun everyCapabilityGatedHostRequestIsAuthorized() {
        assertThrows(WorkspaceCapabilityDenied::class.java) {
            WorkspaceHostPolicy.authorize(emptySet(), WorkspaceHostRequest.ReadMaterial(WorkspaceMaterialId("mat-1")))
        }
        WorkspaceHostPolicy.authorize(
            setOf(WorkspaceCapability.READ_MATERIALS),
            WorkspaceHostRequest.ReadMaterial(WorkspaceMaterialId("mat-1")),
        )
        assertThrows(WorkspaceCapabilityDenied::class.java) {
            WorkspaceHostPolicy.authorize(
                setOf(WorkspaceCapability.EMIT_ARTIFACTS),
                WorkspaceHostRequest.EmitChange(WorkspacePath("work/a.txt")),
            )
        }
        WorkspaceHostPolicy.authorize(
            setOf(WorkspaceCapability.PROPOSE_CHANGES),
            WorkspaceHostRequest.EmitChange(WorkspacePath("work/a.txt")),
        )
        assertThrows(WorkspaceCapabilityDenied::class.java) {
            WorkspaceHostPolicy.authorize(
                setOf(WorkspaceCapability.PROPOSE_CHANGES),
                WorkspaceHostRequest.EmitArtifact(WorkspacePath("work/a.txt")),
            )
        }
        WorkspaceHostPolicy.authorize(
            setOf(WorkspaceCapability.EMIT_ARTIFACTS),
            WorkspaceHostRequest.EmitArtifact(WorkspacePath("work/a.txt")),
        )
    }

    @Test fun searchFetchAndPrintRequireTheirOwnCapabilityAndPort() {
        val query = scriptRequest(capabilities = emptySet())
        val emptySession = session(query)
        assertThrows(WorkspaceCapabilityDenied::class.java) { emptySession.search("query", 2) }
        assertThrows(WorkspaceCapabilityDenied::class.java) {
            emptySession.fetch("https://example.com/a", 100)
        }
        assertThrows(WorkspaceCapabilityDenied::class.java) { emptySession.print("work/a.pdf") }

        val searchPort = FakeSearchPort(
            WorkspaceSearchOutcome.Found(
                listOf(WorkspaceSearchHit("Title", "https://example.com/a", "Snippet")),
                false,
            ),
        )
        val searchSession = session(
            scriptRequest(capabilities = setOf(WorkspaceCapability.SEARCH)),
            search = searchPort,
        )
        val searchJson = searchSession.search("query", 2)
        assertTrue(searchJson.contains("\"title\":\"Title\""))
        assertEquals(1, searchPort.calls)
        assertEquals(2, searchPort.lastRequest!!.maxResults)

        assertThrows(WorkspaceScriptHostUnavailable::class.java) {
            session(scriptRequest(capabilities = setOf(WorkspaceCapability.SEARCH))).search("query", 2)
        }

        val fetchPort = FakeFetchPort(
            WorkspaceFetchOutcome.Fetched(
                WorkspaceFetchedDocument(
                    finalUrl = "https://example.com/a",
                    mediaType = "text/plain",
                    bytes = "body".toByteArray(Charsets.UTF_8),
                    truncated = false,
                ),
            ),
        )
        val fetchSession = session(
            scriptRequest(capabilities = setOf(WorkspaceCapability.FETCH)),
            fetch = fetchPort,
        )
        assertTrue(fetchSession.fetch("https://example.com/a", 100).contains("\"text\":\"body\""))
        assertEquals(1, fetchPort.calls)

        val printPort = RecordingPrintPort()
        val printSession = session(
            scriptRequest(capabilities = setOf(WorkspaceCapability.PRINT)),
            print = printPort,
        )
        printSession.print("work/report.pdf")
        assertEquals(listOf(WorkspacePath("work/report.pdf")), printPort.paths)
        assertThrows(IllegalArgumentException::class.java) { printSession.print("../outside.pdf") }
    }

    @Test fun emitChangeAndEmitArtifactAreBoundedAndNeverBecomeSuccessAfterMalformedOutput() {
        val noCapabilities = session(scriptRequest(capabilities = emptySet()))
        val changeJson = "{\"path\":\"work/a.txt\",\"operation\":\"CREATE\",\"content\":\"value\"}"
        assertThrows(WorkspaceCapabilityDenied::class.java) { noCapabilities.emitChange(changeJson) }
        assertThrows(WorkspaceCapabilityDenied::class.java) {
            noCapabilities.emitArtifact("{\"path\":\"work/a.md\",\"mediaType\":\"text/plain\",\"content\":\"x\"}")
        }

        val proposing = session(scriptRequest(capabilities = setOf(WorkspaceCapability.PROPOSE_CHANGES)))
        proposing.emitChange(changeJson)
        val success = proposing.result(0)
        assertEquals(0, success.exitCode)
        assertEquals(1, success.changes.size)
        assertEquals(WorkspaceChangeOperation.CREATE, success.changes.single().operation)

        assertThrows(WorkspaceScriptOutputException::class.java) {
            proposing.emitChange("{\"path\":\"../escape.txt\",\"operation\":\"CREATE\",\"content\":\"x\"}")
        }
        val rejected = proposing.result(0)
        assertEquals(1, rejected.exitCode)
        assertTrue(rejected.changes.isEmpty())
    }

    @Test fun outputTruncationIsReportedAndLogLinesAreBounded() {
        val limits = WorkspaceScriptLimits(
            maxOutputBytes = 5,
            maxLogLines = 2,
            maxChanges = 1,
            maxArtifacts = 1,
            maxChangeBytes = 16,
            maxArtifactBytes = 16,
        )
        val session = session(scriptRequest(limits = limits))
        session.log("abc")
        session.log("de")
        session.log("f")
        val result = session.result(0)
        assertTrue(result.outputTruncated)
        assertEquals(2, result.logs.size)
        assertEquals("abcde", result.logs.joinToString("") { it.text })

        val longLine = session(
            scriptRequest(
                limits = WorkspaceScriptLimits(
                    maxOutputBytes = 1024 * 1024,
                    maxLogLines = 10,
                    maxChanges = 1,
                    maxArtifacts = 1,
                    maxChangeBytes = 16,
                    maxArtifactBytes = 16,
                ),
            ),
        )
        longLine.log("x".repeat(WorkspaceHardLimits.MAX_LOG_LINE_CHARS + 1))
        val longResult = longLine.result(0)
        assertTrue(longResult.outputTruncated)
        assertEquals(WorkspaceHardLimits.MAX_LOG_LINE_CHARS, longResult.logs.single().text.length)
        assertFalse(longResult.logs.single().text.contains('\u0000'))
    }

    @Test fun cancellationDeadlineAndArtifactLimitsAreTypedFailures() {
        val cancellation = WorkspaceCancellation().also { it.cancel() }
        val cancelled = session(scriptRequest(cancellation = cancellation))
        assertThrows(CancellationException::class.java) { cancelled.readMaterial("mat-1") }

        val timedOut = session(scriptRequest())
        timedOut.markAborted(WorkspaceScriptHostSession.AbortReason.TIMED_OUT)
        assertThrows(WorkspaceScriptTimeoutException::class.java) { timedOut.result(0) }

        val expired = session(scriptRequest(deadlineAtMillis = 1_000L), clock = FakeClock(now = 2_000L))
        assertThrows(WorkspaceScriptTimeoutException::class.java) { expired.log("late") }

        val limits = WorkspaceScriptLimits(
            maxOutputBytes = 1024,
            maxLogLines = 10,
            maxChanges = 1,
            maxArtifacts = 1,
            maxChangeBytes = 4,
            maxArtifactBytes = 4,
        )
        val artifacts = session(scriptRequest(limits = limits))
        artifacts.emitArtifact("{\"path\":\"work/a.bin\",\"mediaType\":\"application/octet-stream\",\"content\":\"1234\"}")
        assertThrows(WorkspaceScriptOutputLimitExceeded::class.java) {
            artifacts.emitArtifact("{\"path\":\"work/b.bin\",\"mediaType\":\"application/octet-stream\",\"content\":\"1234\"}")
        }
        assertTrue(artifacts.result(0).outputTruncated)
    }

    @Test fun synchronousMaterialAdapterRequiresAPortThatDoesNotSuspend() {
        val body = "body".toByteArray(Charsets.UTF_8)
        val id = WorkspaceMaterialId("mat-1")
        val content = WorkspaceMaterialContent(id, sha256Of(body), body)
        val immediate = object : WorkspaceMaterialReadPort {
            override suspend fun list(kind: WorkspaceMaterialKind?): List<WorkspaceMaterialSummary> = emptyList()
            override suspend fun summary(id: WorkspaceMaterialId): WorkspaceMaterialSummary? = null
            override suspend fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent = content
        }
        assertEquals(content.id, SynchronousWorkspaceMaterialReadPort(immediate).read(id, 4).id)

        val suspending = object : WorkspaceMaterialReadPort {
            override suspend fun list(kind: WorkspaceMaterialKind?): List<WorkspaceMaterialSummary> = emptyList()
            override suspend fun summary(id: WorkspaceMaterialId): WorkspaceMaterialSummary? = null
            override suspend fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent {
                return kotlin.coroutines.suspendCoroutine { }
            }
        }
        assertThrows(WorkspaceScriptHostUnavailable::class.java) {
            SynchronousWorkspaceMaterialReadPort(suspending).read(id, 4)
        }
    }

    private fun session(
        request: WorkspaceScriptRequest,
        reader: WorkspaceScriptMaterialReadPort = FakeScriptMaterialReader(),
        search: WorkspaceSearchPort? = null,
        fetch: WorkspaceFetchPort? = null,
        print: WorkspacePrintPort? = null,
        clock: WorkspaceClock = FakeClock(),
    ): WorkspaceScriptHostSession = WorkspaceScriptHostSession(
        request = request,
        materials = reader,
        searchPort = search,
        fetchPort = fetch,
        printPort = print,
        clock = clock,
    )
}
