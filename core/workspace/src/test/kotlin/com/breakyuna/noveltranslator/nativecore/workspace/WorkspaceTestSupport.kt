package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Drives a suspend function that never actually suspends. All fakes in this test suite return
 * immediately, so the driver needs no coroutines library (the workspace module intentionally
 * has no kotlinx-coroutines dependency).
 */
internal fun <T> runWorkspaceSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(outcome: Result<T>) {
                result = outcome
            }
        },
    )
    return requireNotNull(result) { "Test fake suspended unexpectedly" }.getOrThrow()
}

internal fun sha256Of(text: String): String = sha256Of(text.toByteArray(Charsets.UTF_8))

internal fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun digestOf(text: String): WorkspaceDigest = WorkspaceDigest(sha256Of(text))

internal val PROJECT_ID = ProjectId("project-ws")
internal val CHAT_ID = ChatId("chat-ws")
internal val RUN_ID = RunId("run-ws")
internal val COMMAND_ID = CommandId("command-ws")
internal val CHANGE_SET_ID = WorkspaceChangeSetId("changes-ws")

internal class FakeClock(var now: Long = 1_000L) : WorkspaceClock {
    override fun nowMillis(): Long = now
}

internal class FakeMaterialPort(
    private val summaries: List<WorkspaceMaterialSummary>,
    private val bodies: Map<WorkspaceMaterialId, ByteArray> = emptyMap(),
) : WorkspaceMaterialReadPort {
    var readCount: Int = 0
        private set

    override suspend fun list(kind: WorkspaceMaterialKind?): List<WorkspaceMaterialSummary> =
        summaries.filter { kind == null || it.kind == kind }

    override suspend fun summary(id: WorkspaceMaterialId): WorkspaceMaterialSummary? =
        summaries.firstOrNull { it.id == id }

    override suspend fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent {
        readCount += 1
        val summary = summaries.firstOrNull { it.id == id }
            ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
        val bytes = bodies[id] ?: ByteArray(0)
        if (bytes.size > limitBytes) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.TOO_LARGE)
        }
        return WorkspaceMaterialContent(id, summary.sha256, bytes)
    }
}

internal class FakeSnapshotPort : WorkspaceSnapshotPort {
    val fingerprints: MutableMap<String, WorkspaceDigest?> = LinkedHashMap()
    var failure: Exception? = null

    override suspend fun fingerprint(path: WorkspacePath): WorkspaceDigest? {
        failure?.let { throw it }
        return fingerprints[path.value]
    }
}

internal class FakeScriptEngine(
    var resultFactory: () -> WorkspaceScriptResult = { WorkspaceScriptResult(0, emptyList(), emptyList()) },
) : WorkspaceScriptEngine {
    var calls: Int = 0
        private set
    var lastRequest: WorkspaceScriptRequest? = null
    var failure: Exception? = null
    var onExecute: (WorkspaceScriptRequest) -> Unit = {}

    override suspend fun execute(request: WorkspaceScriptRequest): WorkspaceScriptResult {
        calls += 1
        lastRequest = request
        onExecute(request)
        failure?.let { throw it }
        return resultFactory()
    }
}

internal class FakeApprovalPort : WorkspaceApprovalPort {
    var calls: Int = 0
        private set
    var lastRequest: WorkspaceApprovalRequest? = null
    var onRequest: (WorkspaceApprovalRequest, WorkspaceCancellation) -> Unit = { _, _ -> }
    var decisionFactory: (WorkspaceApprovalRequest) -> WorkspaceApprovalDecision = { request ->
        WorkspaceApprovalDecision.Approved(
            requestId = ApprovalRequestId("approval-ws"),
            digest = request.digest,
            approvedAtMillis = 1_000L,
        )
    }

    override suspend fun request(
        request: WorkspaceApprovalRequest,
        cancellation: WorkspaceCancellation,
    ): WorkspaceApprovalDecision {
        calls += 1
        lastRequest = request
        onRequest(request, cancellation)
        return decisionFactory(request)
    }
}

internal class FakeApplyPort : WorkspaceApplyPort {
    var calls: Int = 0
        private set
    var lastApproved: ApprovedWorkspaceChangeSet? = null
    var failure: Exception? = null
    var receiptFactory: (ApprovedWorkspaceChangeSet) -> WorkspaceApplyReceipt = { approved ->
        WorkspaceApplyReceipt(
            commandId = approved.commandId,
            changeSetId = approved.changeSet.id,
            digest = approved.changeSet.digest,
            status = WorkspaceApplyStatus.COMMITTED,
            committed = approved.changeSet.changes.map { change ->
                WorkspaceCommittedChange(
                    path = change.path,
                    operation = change.operation,
                    fingerprint = change.content?.sha256 ?: requireNotNull(change.expectedFingerprint),
                )
            },
            conflicts = emptyList(),
            revision = 1L,
            committedAtMillis = 1_000L,
        )
    }

    override suspend fun apply(
        approved: ApprovedWorkspaceChangeSet,
        conflictPolicy: WorkspaceConflictPolicy,
    ): WorkspaceApplyReceipt {
        calls += 1
        lastApproved = approved
        failure?.let { throw it }
        return receiptFactory(approved)
    }
}

internal class FakeArtifactSink : WorkspaceArtifactSink {
    var calls: Int = 0
        private set
    val saved: MutableList<WorkspaceArtifactDraft> = mutableListOf()
    var failure: Exception? = null

    override suspend fun save(
        runId: RunId,
        drafts: List<WorkspaceArtifactDraft>,
    ): List<WorkspaceArtifactSummary> {
        calls += 1
        failure?.let { throw it }
        saved += drafts
        return drafts.mapIndexed { index, draft ->
            WorkspaceArtifactSummary(
                id = WorkspaceArtifactId("artifact-$index"),
                path = draft.path,
                mediaType = draft.mediaType,
                sizeBytes = draft.sizeBytes,
                sha256 = draft.sha256,
            )
        }
    }
}

internal fun materialSummary(
    id: String,
    body: ByteArray = "material-body".toByteArray(Charsets.UTF_8),
    kind: WorkspaceMaterialKind = WorkspaceMaterialKind.UPLOAD,
    path: String = "uploads/$id.txt",
): WorkspaceMaterialSummary = WorkspaceMaterialSummary(
    id = WorkspaceMaterialId(id),
    kind = kind,
    path = WorkspacePath(path),
    sha256 = sha256Of(body),
    sizeBytes = body.size.toLong(),
    mediaType = "text/plain",
)

internal fun createChange(path: String, text: String): WorkspaceChange = WorkspaceChange(
    path = WorkspacePath(path),
    operation = WorkspaceChangeOperation.CREATE,
    expectedFingerprint = null,
    content = WorkspaceChangeContent.Inline(text.toByteArray(Charsets.UTF_8)),
)

internal fun updateChange(path: String, text: String, base: WorkspaceDigest): WorkspaceChange = WorkspaceChange(
    path = WorkspacePath(path),
    operation = WorkspaceChangeOperation.UPDATE,
    expectedFingerprint = base,
    content = WorkspaceChangeContent.Inline(text.toByteArray(Charsets.UTF_8)),
)

internal fun deleteChange(path: String, base: WorkspaceDigest): WorkspaceChange = WorkspaceChange(
    path = WorkspacePath(path),
    operation = WorkspaceChangeOperation.DELETE,
    expectedFingerprint = base,
    content = null,
)

internal fun runRequest(
    budget: WorkspaceBudget = WorkspaceBudget(),
    materialIds: List<WorkspaceMaterialId> = emptyList(),
    grantedCapabilities: Set<WorkspaceCapability> = setOf(
        WorkspaceCapability.READ_MATERIALS,
        WorkspaceCapability.PROPOSE_CHANGES,
        WorkspaceCapability.EMIT_ARTIFACTS,
    ),
): WorkspaceRunRequest = WorkspaceRunRequest(
    commandId = COMMAND_ID,
    projectId = PROJECT_ID,
    chatId = CHAT_ID,
    runId = RUN_ID,
    changeSetId = CHANGE_SET_ID,
    scriptId = "test-script",
    entryPoint = "main.js",
    materialIds = materialIds,
    grantedCapabilities = grantedCapabilities,
    budget = budget,
)

internal class RuntimeFixture(
    materialSummaries: List<WorkspaceMaterialSummary> = emptyList(),
    materialBodies: Map<WorkspaceMaterialId, ByteArray> = emptyMap(),
) {
    val materials = FakeMaterialPort(materialSummaries, materialBodies)
    val engine = FakeScriptEngine()
    val snapshots = FakeSnapshotPort()
    val approvals = FakeApprovalPort()
    val apply = FakeApplyPort()
    val artifacts = FakeArtifactSink()
    val clock = FakeClock()
    val events: MutableList<String> = mutableListOf()

    val runtime: WorkspaceRuntime = WorkspaceRuntime(
        materials = materials,
        engine = engine,
        snapshots = snapshots,
        approvals = approvals,
        applyPort = apply,
        artifactSink = object : WorkspaceArtifactSink {
            override suspend fun save(
                runId: RunId,
                drafts: List<WorkspaceArtifactDraft>,
            ): List<WorkspaceArtifactSummary> {
                events += "artifact"
                return artifacts.save(runId, drafts)
            }
        },
        clock = clock,
    )

    init {
        approvals.onRequest = { _, _ -> events += "approval" }
        val receiptFactory = apply.receiptFactory
        apply.receiptFactory = { approved ->
            events += "apply"
            receiptFactory(approved)
        }
    }

    fun run(
        request: WorkspaceRunRequest = runRequest(),
        cancellation: WorkspaceCancellation = WorkspaceCancellation(),
    ): WorkspaceRunOutcome = runWorkspaceSuspend { runtime.run(request, cancellation) }
}
