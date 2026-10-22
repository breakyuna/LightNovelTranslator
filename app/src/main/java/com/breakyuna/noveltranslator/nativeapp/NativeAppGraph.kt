package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import com.breakyuna.noveltranslator.nativecore.agent.NativeKoogWorkspace
import com.breakyuna.noveltranslator.nativecore.agent.NativeKoogConversation
import com.breakyuna.noveltranslator.nativecore.engine.CompatibleAgentModel
import com.breakyuna.noveltranslator.nativecore.storage.NativeModelSettingsStore
import com.breakyuna.noveltranslator.nativecore.storage.NativeAgentStore
import com.breakyuna.noveltranslator.nativecore.engine.LocalSimulationModel
import com.breakyuna.noveltranslator.nativecore.engine.NativeModelGateway
import com.breakyuna.noveltranslator.nativecore.engine.TextTranslationService
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore

/** One process owner for native IO and reservations; no legacy engine or model client. */
class NativeAppGraph(context: Context) {
    val runtime = RuntimeCoordinator()
    val projects = NativeTextProjectStore(context.applicationContext, runtime)
    val agentStore = NativeAgentStore(context.applicationContext, projects, runtime)
    val agentQueries = NativeKoogWorkspace(runtime, projects, agentStore)
    private val simulation = LocalSimulationModel()
    val simulationConfiguration = simulation.configuration
    val modelSettings = NativeModelSettingsStore(context.applicationContext, runtime)
    private val gateway = NativeModelGateway(runtime, simulation, CompatibleAgentModel(modelSettings, runtime))
    val conversations = NativeKoogConversation(runtime, projects, agentStore, agentStore, agentStore, gateway)
    val translations = TextTranslationService(runtime, projects, projects, gateway)
}
