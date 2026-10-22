package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.agent.AgentToolHost
import com.breakyuna.noveltranslator.core.llm.LlmErrorCategory
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AgentSession 端到端行为测试：经 Koog functional strategy 完整跑“用户输入 →
 * LLM 请求 → 工具协议解析 → AgentToolHost 执行 → 结果回传 → 最终答复”的回合循环。
 *
 * 全部使用内存假实现（ScriptedGateway / RecordingToolHost / InMemoryAgentSessionStore），
 * 不触达真实网络与 Room 持久化（存储走进程内实现）。
 *
 * 需要 Robolectric：回合循环必经 `AgentToolProtocol` / `AgentToolHost.executeTool`，
 * 二者用 android 的 org.json，而本模块单元测试未开启 returnDefaultValues，
 * 纯 JVM 下 android.jar 是调用即抛的桩。与 AgentToolProtocolTest / AgentToolHostTest 一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentSessionTest {

    private val provider = ApiProviderEntity(
        id = 3L,
        name = "TestProvider",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://example.invalid/v1",
        apiKey = "dummy-key-not-a-real-secret",
        selectedModel = "gpt-4o-mini",
        temperature = 0.7f,
        maxContextTokens = 8192
    )

    private class ScriptedGateway : LlmGateway {
        val requests = mutableListOf<LlmRequest>()
        private val responses = mutableListOf<LlmResult>()

        fun enqueue(vararg results: LlmResult): ScriptedGateway {
            responses += results
            return this
        }

        override suspend fun executeCompletion(request: LlmRequest): LlmResult {
            requests += request
            return responses.getOrElse(requests.size - 1) { responses.last() }
        }

        override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
            Result.success(listOf("gpt-4o-mini"))
    }

    /** 第一次请求在途时调用 session.cancel()，用于验证“取消后不再发起新的付费请求”。 */
    private class CancelOnFirstCallGateway : LlmGateway {
        val requests = mutableListOf<LlmRequest>()
        private val responses = mutableListOf<LlmResult>()
        var session: AgentSession? = null
        var armed = true

        fun enqueue(vararg results: LlmResult): CancelOnFirstCallGateway {
            responses += results
            return this
        }

        override suspend fun executeCompletion(request: LlmRequest): LlmResult {
            requests += request
            if (armed && requests.size == 1) {
                session?.cancel()
            }
            return responses.getOrElse(requests.size - 1) { responses.last() }
        }

        override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
            Result.success(emptyList())
    }

    /** 第一次请求挂起在门上，用于验证运行中忽略并发输入。 */
    private inner class GatedGateway(private val gate: CompletableDeferred<Unit>) : LlmGateway {
        val requests = mutableListOf<LlmRequest>()

        override suspend fun executeCompletion(request: LlmRequest): LlmResult {
            requests += request
            if (requests.size == 1) gate.await()
            return result("最终答复")
        }

        override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
            Result.success(emptyList())
    }

    private class RecordingToolHost : AgentToolHost {
        val invocations = mutableListOf<String>()
        var listBooksOutput = "共 2 本书：A、B"
        /** 非空时 listBooks 抛错，用于验证工具异常被归一成失败回执而不是打断回合。 */
        var listBooksError: Throwable? = null

        override suspend fun listBooks(): String {
            invocations += "list_books"
            listBooksError?.let { throw it }
            return listBooksOutput
        }

        override suspend fun getBookStatus(bookId: Long): String {
            invocations += "get_book_status:$bookId"
            return "Book#$bookId 状态正常"
        }

        override suspend fun startTranslation(
            projectId: Long,
            rangeStart: Int?,
            rangeEnd: Int?
        ): String {
            invocations += "start_translation:$projectId:$rangeStart:$rangeEnd"
            return "已启动"
        }

        override suspend fun pauseTranslation(projectId: Long): String {
            invocations += "pause_translation:$projectId"
            return "已暂停"
        }

        override suspend fun scanGlossary(
            bookId: Long,
            startChapter: Int?,
            endChapter: Int?,
            projectId: Long?
        ): String {
            invocations += "scan_glossary:$bookId:$startChapter:$endChapter:$projectId"
            return "扫描完成"
        }

        override suspend fun resplitChapters(bookId: Long, previewOnly: Boolean): String {
            invocations += "resplit_chapters:$bookId:$previewOnly"
            return "识别完成"
        }

        override suspend fun getRecentLog(projectId: Long?, limit: Int): String {
            invocations += "get_recent_log:$projectId:$limit"
            return "日志 $limit 条"
        }

        override suspend fun askUser(prompt: String, options: List<String>): String {
            invocations += "ask_user:$prompt"
            return "用户回答"
        }

        override suspend fun updateTranslationConfig(
            projectId: Long,
            parallelChapters: Int?,
            continuityMode: String?,
            promptCacheEnabled: Boolean?
        ): String {
            invocations += "update_translation_config:$projectId"
            return "配置已更新"
        }

        override suspend fun summarizeChapter(bookId: Long, chapterIndex: Int, projectId: Long?): String = "摘要"
        override suspend fun getStoryMemory(projectId: Long): String = "记忆"
    }

    private fun result(text: String, promptTokens: Long = 10L, completionTokens: Long = 4L) =
        LlmResult(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = "stop"
        )

    private fun callBlock(name: String, argumentsJson: String): String =
        AgentToolProtocol.TOOL_CALL_OPEN +
            """{"name":"$name","arguments":$argumentsJson}""" +
            AgentToolProtocol.TOOL_CALL_CLOSE

    private fun config(
        maxToolRounds: Int = 6,
        maxHistoryTurns: Int = 12,
        provider: ApiProviderEntity? = this.provider
    ) = AgentSessionConfig(
        provider = provider,
        modelName = "gpt-4o-mini",
        temperature = 0.4f,
        maxTokens = 2048,
        maxToolRounds = maxToolRounds,
        maxHistoryTurns = maxHistoryTurns,
        compressAfterMessages = 100,
        maxTranscriptChars = 60_000
    )

    private fun newSession(
        gateway: LlmGateway,
        host: AgentToolHost,
        cfg: AgentSessionConfig,
        store: AgentSessionStore,
        sessionId: Long? = null
    ) = AgentSession(
        sessionId = sessionId,
        gateway = gateway,
        toolHost = host,
        config = cfg,
        store = store,
        promptAssetLoader = null,
        systemPromptOverride = "系统提示：测试",
        texts = AgentSessionTexts(),
        now = { 1_000L }
    )

    // ---------- 输入边界 ----------

    @Test
    fun sendWithoutProviderSetsErrorAndSkipsGateway() = runTest {
        val gateway = ScriptedGateway()
        val session = newSession(gateway, RecordingToolHost(), config(provider = null), InMemoryAgentSessionStore())

        session.send("你好")

        assertTrue(session.state.value.error!!.contains("尚未选择可用的模型提供方"))
        assertTrue(gateway.requests.isEmpty())
        assertTrue(session.state.value.messages.isEmpty())
    }

    @Test
    fun blankInputIsIgnored() = runTest {
        val gateway = ScriptedGateway().enqueue(result("不应被请求"))
        val session = newSession(gateway, RecordingToolHost(), config(), InMemoryAgentSessionStore())

        session.send("   \t  ")

        assertTrue(gateway.requests.isEmpty())
        assertTrue(session.state.value.messages.isEmpty())
    }

    // ---------- 正常回合 ----------

    @Test
    fun finalAnswerPersistsMessagesUsageAndSession() = runTest {
        val gateway = ScriptedGateway().enqueue(result("书架上有 2 本书。", promptTokens = 30, completionTokens = 12))
        val store = InMemoryAgentSessionStore()
        val session = newSession(gateway, RecordingToolHost(), config(), store)

        session.send("书架上有哪些书")

        val messages = session.state.value.messages
        assertEquals(2, messages.size)
        assertEquals(AgentRole.USER, messages[0].role)
        assertEquals("书架上有哪些书", messages[0].content)
        assertEquals(AgentRole.ASSISTANT, messages[1].role)
        assertEquals("书架上有 2 本书。", messages[1].content)
        assertEquals(1L, session.state.value.sessionId)
        assertEquals(30L, session.state.value.usage.promptTokens)
        assertEquals(12L, session.state.value.usage.completionTokens)
        assertEquals(1, session.state.value.usage.requestCount)
        assertEquals(2, store.loadMessages(1L).size)
    }

    @Test
    fun usageIsPersistedOnMessagesAndRestoredOnReopen() = runTest {
        val store = InMemoryAgentSessionStore()
        val session = newSession(
            ScriptedGateway().enqueue(result("最终答复", promptTokens = 30, completionTokens = 12)),
            RecordingToolHost(),
            config(),
            store
        )

        session.send("书架")

        val sessionId = session.state.value.sessionId!!
        val stored = store.loadMessages(sessionId)
        // 用量必须挂在消息行上，否则重开会话时无从重建（此前这两列恒为 0）。
        assertEquals(30L, stored.sumOf { it.promptTokens.toLong() })
        assertEquals(12L, stored.sumOf { it.completionTokens.toLong() })

        val reopened = newSession(
            ScriptedGateway(),
            RecordingToolHost(),
            config(),
            store,
            sessionId = sessionId
        )
        reopened.loadPersistedHistory()

        assertEquals(30L, reopened.state.value.usage.promptTokens)
        assertEquals(12L, reopened.state.value.usage.completionTokens)
        assertEquals(1, reopened.state.value.usage.requestCount)
    }

    @Test
    fun replyLanguageFromConfigReachesTheModel() = runTest {
        val gateway = ScriptedGateway().enqueue(result("ok"))
        val session = newSession(
            gateway,
            RecordingToolHost(),
            config().copy(replyLanguage = "English"),
            InMemoryAgentSessionStore()
        )

        session.send("hi")

        // 英文界面不该再被硬编码的"回复语言：中文"覆盖。
        assertTrue(gateway.requests.single().systemPrompt.contains("回复语言：English"))
    }

    @Test
    fun requestUsageCallbackReportsSuccessAndFailure() = runTest {
        val failure = LlmResult(
            text = "",
            promptTokens = 7,
            completionTokens = 0,
            isSuccess = false,
            errorCategory = LlmErrorCategory.SERVER_ERROR,
            errorMessage = "boom"
        )
        val gateway = ScriptedGateway().enqueue(
            result("完成", promptTokens = 5, completionTokens = 2),
            failure
        )
        val seen = mutableListOf<LlmResult>()
        val session = newSession(
            gateway,
            RecordingToolHost(),
            config().copy(onRequestUsage = { seen += it }),
            InMemoryAgentSessionStore()
        )

        session.send("第一次")
        session.send("第二次")

        // 失败调用同样上报：审计里少一条就等于漏计费。
        assertEquals(2, seen.size)
        assertTrue(seen[0].isSuccess)
        assertEquals(5L, seen[0].promptTokens)
        assertFalse(seen[1].isSuccess)
    }

    @Test
    fun storeFailureDoesNotLeaveSessionStuckRunning() = runTest {
        val failingStore = object : AgentSessionStore by InMemoryAgentSessionStore() {
            override suspend fun createSession(title: String, providerId: Long?, modelName: String?): Long =
                throw IllegalStateException("db down")
        }
        val gateway = ScriptedGateway().enqueue(result("不应被请求"))
        val session = newSession(gateway, RecordingToolHost(), config(), failingStore)

        runCatching { session.send("你好") }

        // 建会话失败要冒泡，但 running 必须复位，否则整个 Agent 页锁死到进程重启。
        assertFalse(session.isRunning)
        assertFalse(session.state.value.isRunning)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun sessionTitleTakesFirstTwentyChars() = runTest {
        val gateway = ScriptedGateway().enqueue(result("好的"))
        val session = newSession(gateway, RecordingToolHost(), config(), InMemoryAgentSessionStore())
        val longInput = "一二三四五六七八九十" + "abcdefghijklmnopqrstuvwxyz"

        session.send(longInput)

        assertEquals(longInput.take(20), session.state.value.title)
    }

    // ---------- 工具回合 ----------

    @Test
    fun toolRoundExecutesHostAndPersistsProtocolMessages() = runTest {
        val block = callBlock("list_books", "{}")
        val gateway = ScriptedGateway().enqueue(
            result(block, promptTokens = 40, completionTokens = 8),
            result("共 2 本书，分别是 A 和 B。", promptTokens = 60, completionTokens = 15)
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config(), InMemoryAgentSessionStore())

        session.send("书架")

        assertEquals(listOf("list_books"), host.invocations)
        val messages = session.state.value.messages
        assertEquals(
            listOf(AgentRole.USER, AgentRole.TOOL_CALL, AgentRole.TOOL_RESULT, AgentRole.ASSISTANT),
            messages.map { it.role }
        )
        // 工具调用消息按模型原样重建，供后续回合精确回放
        assertEquals(block, messages[1].content)
        assertEquals("{}", messages[1].toolArgsJson)
        assertEquals("list_books", messages[1].toolName)
        assertFalse(messages[2].isError)
        assertTrue(messages[2].content.contains("name=\"list_books\""))
        assertTrue(messages[2].content.contains("ok=\"true\""))
        assertTrue(messages[2].content.contains("共 2 本书：A、B"))
        assertTrue(messages[2].toolResultJson!!.contains("共 2 本书：A、B"))
        assertEquals("共 2 本书，分别是 A 和 B。", messages[3].content)
        // 第二次请求把工具结果作为用户消息带回
        assertTrue(gateway.requests[1].userPrompt.contains("共 2 本书：A、B"))
        assertTrue(gateway.requests[1].userPrompt.contains("tool_result"))
        assertEquals(2, session.state.value.usage.requestCount)
        assertEquals(100L, session.state.value.usage.promptTokens)
    }

    @Test
    fun multipleCallsInOneRoundExecuteInOrderAndShareOneResultMessage() = runTest {
        val firstBlock = callBlock("list_books", "{}")
        val secondBlock = callBlock("get_recent_log", """{"limit":5}""")
        val gateway = ScriptedGateway().enqueue(
            result(firstBlock + "\n" + secondBlock, promptTokens = 50, completionTokens = 10),
            result("汇总完成", promptTokens = 40, completionTokens = 10)
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config(), InMemoryAgentSessionStore())

        session.send("汇总一下")

        // 同一轮内的多个调用按出现顺序串行执行
        assertEquals(listOf("list_books", "get_recent_log:null:5"), host.invocations)

        val messages = session.state.value.messages
        assertEquals(
            listOf(
                AgentRole.USER,
                AgentRole.TOOL_CALL, AgentRole.TOOL_RESULT,
                AgentRole.TOOL_CALL, AgentRole.TOOL_RESULT,
                AgentRole.ASSISTANT
            ),
            messages.map { it.role }
        )
        // 每次调用各自落一条 TOOL_CALL / TOOL_RESULT（与持久化“各占一行”一致），
        // content 保存送入模型的协议原文，供后续回合精确回放
        val calls = messages.filter { it.role == AgentRole.TOOL_CALL }
        assertEquals(listOf("list_books", "get_recent_log"), calls.map { it.toolName })
        assertEquals(firstBlock, calls[0].content)
        assertEquals(secondBlock, calls[1].content)
        val toolResults = messages.filter { it.role == AgentRole.TOOL_RESULT }
        assertEquals(listOf("list_books", "get_recent_log"), toolResults.map { it.toolName })
        assertTrue(toolResults[0].content.contains("共 2 本书：A、B"))
        assertTrue(toolResults[1].content.contains("日志 5 条"))
        assertTrue(toolResults.all { !it.isError })

        // 一轮内的全部结果合并成同一条用户消息回传模型：多个调用共享这一条结果消息
        assertTrue(gateway.requests[1].userPrompt.contains("tool_result"))
        assertTrue(gateway.requests[1].userPrompt.contains("共 2 本书：A、B"))
        assertTrue(gateway.requests[1].userPrompt.contains("日志 5 条"))
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun blankToolOutputBecomesPlaceholderInsteadOfBreakingTurn() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result(callBlock("list_books", "{}")),
            result("工具没有返回内容，我直接说明。")
        )
        val host = RecordingToolHost().apply { listBooksOutput = "   " }
        val session = newSession(gateway, host, config(), InMemoryAgentSessionStore())

        session.send("书架")

        val toolResult = session.state.value.messages.single { it.role == AgentRole.TOOL_RESULT }
        assertTrue(toolResult.content.contains("（无返回内容）"))
        assertEquals("工具没有返回内容，我直接说明。", session.state.value.messages.last().content)
    }

    // ---------- 取消边界 ----------

    @Test
    fun cancelDuringRequestStopsFurtherLlmCalls() = runTest {
        val gateway = CancelOnFirstCallGateway().enqueue(
            result(callBlock("list_books", "{}")),
            result("第二回合的答复")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config(), InMemoryAgentSessionStore())
        gateway.session = session

        session.send("书架")

        // 取消后：没有第二次 LLM 请求，工具也不再执行
        assertEquals(1, gateway.requests.size)
        assertTrue(host.invocations.isEmpty())
        val last = session.state.value.messages.last()
        assertEquals(AgentRole.SYSTEM, last.role)
        assertEquals("已取消本次请求。", last.content)
        assertFalse(last.isError)

        // 取消信号在回合结束后重置，下一回合可以正常请求
        gateway.armed = false
        session.send("再问一次")
        assertEquals(2, gateway.requests.size)
        assertEquals("第二回合的答复", session.state.value.messages.last().content)
    }

    // ---------- PART4 ----------
}
