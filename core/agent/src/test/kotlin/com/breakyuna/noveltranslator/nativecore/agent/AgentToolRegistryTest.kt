package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentToolRegistryTest {
    private val identity = RunIdentity(ProjectId("p"), ChatId("c"), RunId("r"), CommandId("command"))

    @Test
    fun twoTypedReadToolsShareDefinitionsValidationAndRealRunIdentity() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val seen = mutableListOf<RunIdentity>()
        val registry = AgentToolRegistry(runtime, listOf(
            RegisteredAgentTool("lookup", "synthetic lookup",
                ToolParameterSchema(mapOf("id" to ToolParameter.Integer(1, Long.MAX_VALUE))), ToolEffect.READ_ONLY,
                AgentToolHandler { owner, args ->
                    seen.add(owner)
                    json("""{"id":${args.integer("id")}}""")
                }),
            RegisteredAgentTool("describe", "synthetic description",
                ToolParameterSchema(mapOf("label" to ToolParameter.Text(1, 20))), ToolEffect.READ_ONLY,
                AgentToolHandler { owner, args ->
                    seen.add(owner)
                    assertEquals("synthetic", args.text("label"))
                    json("""{"ok":true}""")
                }),
        ))
        assertEquals(2, registry.definitions().size)
        val result = registry.execute(run, AgentToolCall(ToolCallId("one"), "lookup", json("""{"id":9007199254740993}""")))
        assertEquals("9007199254740993", (result as AgentToolOutcome.Success).result.json["id"].toString())
        assertTrue(registry.execute(run, AgentToolCall(ToolCallId("two"), "describe", json("""{"label":"synthetic"}"""))) is AgentToolOutcome.Success)
        assertEquals(listOf(identity, identity), seen)
        assertEquals(0, runtime.snapshot().activeRun?.outstandingOperations)
        runtime.finishRun(run)
    }

    @Test
    fun allWritePaidAndAuthenticationToolsAreHiddenAndRejectedBeforeHandler() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val effects = ToolEffect.entries.filter { it != ToolEffect.READ_ONLY }
        val registry = AgentToolRegistry(runtime, effects.map { effect ->
            RegisteredAgentTool(effect.name, "synthetic", ToolParameterSchema(emptyMap()), effect,
                AgentToolHandler { _, _ -> calls += 1; json() })
        })
        assertTrue(registry.definitions().isEmpty())
        for (effect in effects) {
            assertEquals("approval_path_unavailable", (registry.execute(run,
                AgentToolCall(ToolCallId(effect.name), effect.name, json())) as AgentToolOutcome.Failure).code)
        }
        assertEquals(0, calls)
        runtime.finishRun(run)
    }

    @Test
    fun invalidParametersNeverReachHandlerAndErrorsDoNotEchoMaterial() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val registry = AgentToolRegistry(runtime, listOf(RegisteredAgentTool("lookup", "synthetic",
            ToolParameterSchema(mapOf("count" to ToolParameter.Integer(1, 10))), ToolEffect.READ_ONLY,
            AgentToolHandler { _, _ -> calls += 1; json() })))
        for (input in listOf("""{"count":1.5}""", """{"count":4294967297}""", """{"count":"private-material"}""")) {
            val result = registry.execute(run, AgentToolCall(ToolCallId("call"), "lookup", json(input))) as AgentToolOutcome.Failure
            assertEquals("invalid_arguments", result.code)
            assertFalse(result.toString().contains("private-material"))
        }
        assertEquals(0, calls)
        runtime.finishRun(run)
    }

    @Test
    fun repeatedReadonlyQueriesAreAllowedAndCanSeeChangedFacts() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var fact = 1
        val registry = AgentToolRegistry(runtime, listOf(RegisteredAgentTool("read", "synthetic",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { _, _ -> json("""{"value":$fact}""") })))
        val first = registry.execute(run, AgentToolCall(ToolCallId("one"), "read", json())) as AgentToolOutcome.Success
        fact = 2
        val second = registry.execute(run, AgentToolCall(ToolCallId("two"), "read", json())) as AgentToolOutcome.Success
        assertEquals("1", first.result.json["value"].toString())
        assertEquals("2", second.result.json["value"].toString())
        runtime.finishRun(run)
    }

    @Test
    fun stopAndForgedLeasePreventToolDispatch() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val registry = AgentToolRegistry(runtime, listOf(RegisteredAgentTool("read", "synthetic",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { _, _ -> calls += 1; json() })))
        val forged = object : RunLease { override val identity = run.identity }
        try {
            registry.execute(forged, AgentToolCall(ToolCallId("one"), "read", json()))
            fail("Expected stale lease")
        } catch (failure: RuntimeUnavailable) { assertEquals(RuntimeUnavailable.Reason.STALE_EXECUTION, failure.reason) }
        runtime.requestStop(run)
        try {
            registry.execute(run, AgentToolCall(ToolCallId("two"), "read", json()))
            fail("Expected Stop")
        } catch (failure: RuntimeUnavailable) { assertEquals(RuntimeUnavailable.Reason.STOPPING, failure.reason) }
        assertEquals(0, calls)
        runtime.finishRun(run)
    }

    @Test
    fun duplicateToolNamesCannotSilentlyReplaceTheRegisteredHandler() {
        val tool = RegisteredAgentTool("read", "synthetic", ToolParameterSchema(emptyMap()),
            ToolEffect.READ_ONLY, AgentToolHandler { _, _ -> json() })
        assertThrows(IllegalArgumentException::class.java) { AgentToolRegistry(runtime(), listOf(tool, tool)) }
    }

    @Test
    fun cancellationIsNotConvertedIntoASuccessOrModelVisibleToolError() = runTest {
        val runtime = runtime()
        val run = runtime.beginRun(identity, RuntimeOwner.AGENT)
        val registry = AgentToolRegistry(runtime, listOf(RegisteredAgentTool("read", "synthetic",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY,
            AgentToolHandler { _, _ -> throw CancellationException("synthetic cancellation") })))
        try {
            registry.execute(run, AgentToolCall(ToolCallId("one"), "read", json()))
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, runtime.snapshot().activeRun?.outstandingOperations)
        runtime.finishRun(run)
    }

    private fun runtime() = RuntimeCoordinator().also { it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!)) }
}
