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
import com.breakyuna.noveltranslator.nativecore.engine.ForegroundTranslationRunner
import com.breakyuna.noveltranslator.nativecore.formats.NativeFormatAdapters
import com.breakyuna.noveltranslator.nativecore.formats.ProjectFormatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One process owner for native IO and reservations; no legacy engine or model client. */
class NativeAppGraph(context: Context) {
    val runtime = RuntimeCoordinator()
    val projects = NativeTextProjectStore(context.applicationContext, runtime)
    val runStore = NativeRunStore(context.applicationContext, projects, runtime)
    val runs = DurableRunExecutor(runtime, runStore)
    val agentStore = NativeAgentStore(context.applicationContext, projects, runtime)
    val agentQueries = NativeKoogWorkspace(runtime, projects, agentStore)
    private val simulation = LocalSimulationModel()
    val simulationConfiguration = simulation.configuration
    val modelSettings = NativeModelSettingsStore(context.applicationContext, runtime)
    val translationAudit = com.breakyuna.noveltranslator.nativecore.storage.NativeTranslationAuditStore(context.applicationContext, runtime, projects)
    private val http = CompatibleHttpTransport()
    private val gateway = NativeModelGateway(runtime, simulation, CompatibleAgentModel(modelSettings, runtime, http),
        CompatibleModelCatalog(modelSettings, runtime, http),
        remoteTranslation = CompatibleTranslationModel(modelSettings, runtime, translationAudit, http))
    val modelCatalog: NativeModelCatalogPort = gateway
    val conversations = NativeKoogConversation(runtime, projects, agentStore, agentStore, agentStore, gateway)
    val formatAdapters = NativeFormatAdapters.install()
    val formatProjects = ProjectFormatService(projects, projects)
    val translations = TextTranslationService(runtime, projects, projects, gateway, runs)
    private val executionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val backgroundTranslations = ForegroundTranslationRunner(runtime, runs, executionScope, translations)
}
