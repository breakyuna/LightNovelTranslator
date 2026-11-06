package com.breakyuna.noveltranslator.nativecore.workspace

internal fun scriptRequest(
    descriptors: List<WorkspaceMaterialDescriptor> = emptyList(),
    capabilities: Set<WorkspaceCapability> = setOf(
        WorkspaceCapability.READ_MATERIALS,
        WorkspaceCapability.PROPOSE_CHANGES,
        WorkspaceCapability.EMIT_ARTIFACTS,
    ),
    limits: WorkspaceScriptLimits = WorkspaceScriptLimits.of(WorkspaceBudget()),
    deadlineAtMillis: Long = System.currentTimeMillis() + 10_000L,
    cancellation: WorkspaceCancellation = WorkspaceCancellation(),
): WorkspaceScriptRequest = WorkspaceScriptRequest(
    commandId = COMMAND_ID,
    projectId = PROJECT_ID,
    chatId = CHAT_ID,
    runId = RUN_ID,
    scriptId = "test-script",
    entryPoint = "main.js",
    materials = descriptors,
    grantedCapabilities = capabilities,
    limits = limits,
    deadlineAtMillis = deadlineAtMillis,
    cancellation = cancellation,
)

internal class FakeScriptMaterialReader(
    private val summaries: Map<WorkspaceMaterialId, WorkspaceMaterialSummary> = emptyMap(),
    private val bodies: Map<WorkspaceMaterialId, ByteArray> = emptyMap(),
) : WorkspaceScriptMaterialReadPort {
    var reads: Int = 0
        private set
    var lastLimit: Long = -1
        private set
    var failure: Exception? = null
    var overrideContent: ((WorkspaceMaterialId, Long) -> WorkspaceMaterialContent)? = null

    override fun read(id: WorkspaceMaterialId, limitBytes: Long): WorkspaceMaterialContent {
        reads += 1
        lastLimit = limitBytes
        failure?.let { throw it }
        overrideContent?.let { return it(id, limitBytes) }
        val summary = summaries[id]
            ?: throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.NOT_FOUND)
        val body = bodies[id] ?: ByteArray(0)
        if (body.size.toLong() > limitBytes) {
            throw WorkspaceMaterialUnavailable(WorkspaceMaterialUnavailable.Reason.TOO_LARGE)
        }
        return WorkspaceMaterialContent(id, summary.sha256, body)
    }
}

internal class FakeSearchPort(
    var outcome: WorkspaceSearchOutcome = WorkspaceSearchOutcome.Found(emptyList(), false),
) : WorkspaceSearchPort {
    var calls: Int = 0
        private set
    var lastRequest: WorkspaceSearchRequest? = null
        private set

    override suspend fun search(request: WorkspaceSearchRequest): WorkspaceSearchOutcome {
        calls += 1
        lastRequest = request
        return outcome
    }
}

internal class FakeFetchPort(
    var outcome: WorkspaceFetchOutcome = WorkspaceFetchOutcome.Cancelled,
) : WorkspaceFetchPort {
    var calls: Int = 0
        private set
    var lastRequest: WorkspaceFetchRequest? = null
        private set

    override suspend fun fetch(request: WorkspaceFetchRequest): WorkspaceFetchOutcome {
        calls += 1
        lastRequest = request
        return outcome
    }
}

internal class RecordingPrintPort : WorkspacePrintPort {
    val paths: MutableList<WorkspacePath> = mutableListOf()

    override suspend fun print(path: WorkspacePath) {
        paths += path
    }
}
