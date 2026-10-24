package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.agent.NativeKoogWorkspace
import com.breakyuna.noveltranslator.nativecore.agent.NativeKoogConversation
import com.breakyuna.noveltranslator.nativecore.engine.CompatibleTranslationModel
import com.breakyuna.noveltranslator.nativecore.engine.CompatibleAgentModel
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelSettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeAgentStore
import com.breakyuna.noveltranslator.nativecore.engine.LocalSimulationModel
import com.breakyuna.noveltranslator.nativecore.engine.NativeModelGateway
import com.breakyuna.noveltranslator.nativecore.engine.TextTranslationService
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeRunStore
import com.breakyuna.noveltranslator.nativecore.engine.DurableRunExecutor
import com.breakyuna.noveltranslator.nativecore.engine.CompatibleHttpTransport
import com.breakyuna.noveltranslator.nativecore.engine.CompatibleModelCatalog
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelCatalogPort
import com.breakyuna.noveltranslator.nativecore.domain.AgentSummaryPort
import com.breakyuna.noveltranslator.nativecore.domain.AgentSummaryRequest
import com.breakyuna.noveltranslator.nativecore.domain.AgentCompactionGatePort
import com.breakyuna.noveltranslator.nativecore.domain.AgentCompactionBlock
import com.breakyuna.noveltranslator.nativecore.domain.AgentCompactionResult
import com.breakyuna.noveltranslator.nativecore.engine.AgentCompactionService
import com.breakyuna.noveltranslator.nativecore.engine.AgentCompactionPrompt
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationRunner
import com.breakyuna.noveltranslator.nativecore.formats.NativeFormatAdapters
import com.breakyuna.noveltranslator.nativecore.formats.ProjectFormatService
import com.breakyuna.noveltranslator.nativecore.storage.backup.NativeProjectBackupService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One process owner for native IO and reservations; no legacy engine or model client. */
class NativeAppGraph(context: Context) {
    val priceTables = com.breakyuna.noveltranslator.nativecore.storage.NativePriceTableStore(context.applicationContext)
    val diagnostics = com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticsStore(
        java.io.File(context.applicationContext.filesDir, "native_diagnostics"))
    val workspaceSkills = com.breakyuna.noveltranslator.nativecore.workspace.FileWorkspaceSkillStore(
        java.io.File(context.applicationContext.filesDir, "workspace_skills"))
    val runtime = RuntimeCoordinator()
    val promptTemplates = com.breakyuna.noveltranslator.nativecore.storage.NativePromptTemplateStore(context.applicationContext)
    val rulePresets = com.breakyuna.noveltranslator.nativecore.storage.NativeRulePresetStore(context.applicationContext)
    val projects = NativeTextProjectStore(context.applicationContext, runtime)
    val backups = NativeProjectBackupService(projects, context.cacheDir)
    val projectSetBackups = com.breakyuna.noveltranslator.nativecore.storage.backup.NativeProjectSetBackupService(
        projects, context.cacheDir)
    val runStore = NativeRunStore(context.applicationContext, projects, runtime)
    val runs = DurableRunExecutor(runtime, runStore)
    val agentStore = NativeAgentStore(context.applicationContext, projects, runtime)
    val agentInputs = com.breakyuna.noveltranslator.nativecore.storage.NativeAgentInputStore(
        context.applicationContext, projects, runtime)
    val steering = com.breakyuna.noveltranslator.nativecore.engine.AgentSteerController(runtime, agentInputs)
    val agentQueries = NativeKoogWorkspace(runtime, projects, agentStore)
    private val simulation = LocalSimulationModel()
    val simulationConfiguration = simulation.configuration
    val modelSettings = NativeModelSettingsStore(context.applicationContext, runtime)
    val applicationBackups = com.breakyuna.noveltranslator.nativecore.storage.backup.NativeApplicationBackupService(
        NativeAppBackupDataPort(modelSettings, priceTables, promptTemplates, rulePresets),
        context.cacheDir)
    val translationAudit = com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationAuditStore(context.applicationContext, runtime, projects)
    private val http = CompatibleHttpTransport()
    val usageLedger: com.breakyuna.noveltranslator.nativecore.domain.UsageLedgerReadPort =
        com.breakyuna.noveltranslator.nativecore.storage.NativeUsageLedgerStore(context.applicationContext)
    private val modelKeys = com.breakyuna.noveltranslator.nativecore.engine.ModelKeyPool(modelSettings,
        com.breakyuna.noveltranslator.nativecore.storage.NativeModelKeyStateStore(context.applicationContext))
    private val gateway = NativeModelGateway(runtime, simulation,
        CompatibleAgentModel(modelSettings, runtime, transport = http, keys = modelKeys),
        CompatibleModelCatalog(modelSettings, runtime, http),
        remoteTranslation = CompatibleTranslationModel(modelSettings, runtime, translationAudit,
            transport = http, keys = modelKeys))
    val modelCatalog: NativeModelCatalogPort = gateway
    val conversations = NativeKoogConversation(runtime, projects, agentStore, agentStore, agentStore, gateway, steering)
    val formatAdapters = NativeFormatAdapters.install()
    val formatProjects = ProjectFormatService(projects, projects)
    val translationTasks = com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationTaskStore(context.applicationContext, projects, runtime,
        com.breakyuna.noveltranslator.nativecore.engine.DefaultTranslationRetryRecoveryPolicy)
    val translationQueueStore = com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationQueueStore(context.applicationContext)
    val translationQueue = com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueCoordinator(translationQueueStore)
    val translations = TextTranslationService(runtime, projects, projects, gateway, runs, translationTasks, usageLedger,
        com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationRetryCheckpointStore(context.applicationContext, runtime))
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val agentPreviews = com.breakyuna.noveltranslator.nativecore.engine.AgentStreamPreviewController()
    val backgroundAgents = com.breakyuna.noveltranslator.nativecore.engine.ForegroundAgentRunner(runtime, runs, executionScope,
        runTurn = { request, lease, recorder, emit ->
            val session = agentPreviews.open(request.ref, request.identity)
            var completed = false
            var progress = com.breakyuna.noveltranslator.nativecore.engine.AgentExecutionProgress()
            var steerStarted = false
            var steerStage = com.breakyuna.noveltranslator.nativecore.domain.AgentRunStage.FAILED
            try {
                steering.start(lease, request.ref)
                steerStarted = true
                val chat = requireNotNull(request.identity.chatId)
                val compaction = AgentCompactionService(
                    conversation = agentStore,
                    summaries = object : AgentSummaryPort {
                        override suspend fun summarize(summary: AgentSummaryRequest): String =
                            conversations.summarize(lease, request.configuration,
                                AgentCompactionPrompt.build(summary), recorder)
                    },
                    contexts = agentStore,
                    gate = object : AgentCompactionGatePort {
                        override suspend fun blockedReason(ref: com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef,
                                                           blockedChat: com.breakyuna.noveltranslator.nativecore.domain.ChatId) =
                            if (agentStore.pending(ref, blockedChat) != null) AgentCompactionBlock.PENDING_APPROVAL else null
                    },
                )
                val compactionResult = compaction.compact(request.ref, chat)
                if (compactionResult is AgentCompactionResult.Failed) {
                    runCatching { diagnostics.append(
                        com.breakyuna.noveltranslator.nativecore.storage.NativeDiagnosticLevel.WARN,
                        "agent", "Context compaction failed: ${compactionResult.reason}") }
                }
                conversations.send(lease, request.ref, request.configuration, request.input, recorder, session,
                    updated = { progress = progress.copy(timelineRevision = progress.timelineRevision + 1); emit(progress) },
                    accepted = { messageId ->
                        progress = progress.copy(accepted = true, userMessageId = messageId)
                        emit(progress)
                    })
                if (agentStore.pending(request.ref, requireNotNull(request.identity.chatId))?.runId == request.identity.runId)
                    recorder.waitingForApproval()
                completed = true
                steerStage = com.breakyuna.noveltranslator.nativecore.domain.AgentRunStage.FINISHED
                progress
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                steerStage = com.breakyuna.noveltranslator.nativecore.domain.AgentRunStage.STOPPED
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    agentStore.cancelPending(request.ref, request.identity.runId)
                }
                throw cancelled
            } finally {
                try {
                    if (steerStarted) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        steering.finish(lease, steerStage)
                    }
                } finally { session.close(keepPartial = !completed) }
            }
        })
    val backgroundTranslations = ForegroundTranslationRunner(runtime, runs, executionScope, translations)
}
