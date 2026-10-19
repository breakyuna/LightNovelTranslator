package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.agent.AgentToolHost
import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回合循环加固测试：静默失败纠正、相同调用熔断、终态兜底与答复清洗。
 *
 * 全部经 Koog functional strategy 真实跑回合循环（内存假网关 + 内存存储），
 * 与 AgentSessionTest 同构；覆盖的行为：
 * - 模型想调工具但格式没解析出来 → 先回一次格式纠正，仍失败才降级为答复；
 * - 同一回合内相同 (工具名 + 归一化参数) 调用第 2 次起不再执行、不再付费；
 * - 纠正轮次计入 maxToolRounds；空白终态有系统消息兜底；
 * - 全角调用块（step-5-preview 实证故障）能端到端执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTurnHardeningTest {

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

    private class RecordingToolHost : AgentToolHost {
        val invocations = mutableListOf<String>()

        override suspend fun listBooks(): String {
            invocations += "list_books"
            return "共 1 本书"
        }

        override suspend fun getBookStatus(bookId: Long): String {
            invocations += "get_book_status:$bookId"
            return "状态正常"
        }

        override suspend fun startTranslation(
            projectId: Long,
            rangeStart: Int?,
            rangeEnd: Int?
        ): String {
            invocations += "start_translation:$projectId"
            return "已启动"
        }

        override suspend fun pauseTranslation(projectId: Long): String {
            invocations += "pause_translation:$projectId"
            return "已暂停"
        }

        override suspend fun scanGlossary(bookId: Long): String {
            invocations += "scan_glossary:$bookId"
            return "扫描完成"
        }

        override suspend fun resplitChapters(bookId: Long, previewOnly: Boolean): String {
            invocations += "resplit_chapters:$bookId:$previewOnly"
            return "识别完成"
        }

        override suspend fun getRecentLog(projectId: Long?, limit: Int): String {
            invocations += "get_recent_log:$limit"
            return "日志 $limit 条"
        }

        override suspend fun askUser(prompt: String, options: List<String>): String = "用户回答"

        override suspend fun updateTranslationConfig(
            projectId: Long,
            parallelChapters: Int?,
            continuityMode: String?,
            metadataEnabled: Boolean?,
            promptCacheEnabled: Boolean?
        ): String = "配置已更新"
    }

    private fun result(text: String, promptTokens: Long = 10L, completionTokens: Long = 4L) =
        LlmResult(
            text = text,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            isSuccess = true,
            finishReason = "stop"
        )

    private fun callBlock(name: String = "list_books", argumentsJson: String = "{}"): String =
        AgentToolProtocol.TOOL_CALL_OPEN +
            """{"name":"$name","arguments":$argumentsJson}""" +
            AgentToolProtocol.TOOL_CALL_CLOSE

    private fun config(maxToolRounds: Int = 6) = AgentSessionConfig(
        provider = provider,
        modelName = "gpt-4o-mini",
        temperature = 0.4f,
        maxTokens = 2048,
        maxToolRounds = maxToolRounds,
        maxHistoryTurns = 12,
        compressAfterMessages = 100,
        maxTranscriptChars = 60_000
    )

    private fun newSession(
        gateway: LlmGateway,
        host: AgentToolHost,
        cfg: AgentSessionConfig
    ) = AgentSession(
        sessionId = null,
        gateway = gateway,
        toolHost = host,
        config = cfg,
        store = InMemoryAgentSessionStore(),
        promptAssetLoader = null,
        systemPromptOverride = "系统提示：测试",
        texts = AgentSessionTexts(),
        now = { 1_000L }
    )

    @Test
    fun silentFailureGetsOneCorrectionThenSucceeds() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result("我来调用 list_books 查看书架（arguments: {}）"),
            result(callBlock()),
            result("书架上有 1 本书。")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        assertEquals(listOf("list_books"), host.invocations)
        assertEquals(3, gateway.requests.size)
        // 第二次请求携带格式纠正回执，注明疑似工具名。
        assertTrue(gateway.requests[1].userPrompt.contains("疑似工具调用"))
        assertTrue(gateway.requests[1].userPrompt.contains("list_books"))
        assertEquals("书架上有 1 本书。", session.state.value.messages.last().content)
        assertEquals(3, session.state.value.usage.requestCount)
    }

    @Test
    fun silentFailureTwiceDegradesToAnswer() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result("我想调用 list_books 查书架 {}"),
            result("还是调用 list_books 吧 {}")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        assertTrue(host.invocations.isEmpty())
        assertEquals(2, gateway.requests.size)
        assertEquals("还是调用 list_books 吧 {}", session.state.value.messages.last().content)
    }

    @Test
    fun correctionConsumesToolRound() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result("我想调用 list_books 查书架 {}"),
            result(callBlock()),
            result("抱歉，无法完成。")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config(maxToolRounds = 1))

        session.send("书架")

        // maxToolRounds=1：纠正已耗尽唯一一轮，随后的合法调用不再执行。
        assertTrue(host.invocations.isEmpty())
        assertEquals(3, gateway.requests.size)
        assertEquals("抱歉，无法完成。", session.state.value.messages.last().content)
    }

    @Test
    fun duplicateCallInSameResponseIsNotExecutedTwice() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result(callBlock() + "\n" + callBlock()),
            result("汇总完成")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        assertEquals(listOf("list_books"), host.invocations)
        val messages = session.state.value.messages
        assertEquals(2, messages.count { it.role == AgentRole.TOOL_CALL })
        val results = messages.filter { it.role == AgentRole.TOOL_RESULT }
        assertEquals(2, results.size)
        assertTrue(results.count { it.content.contains("duplicate_call") } == 1)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun duplicateCallAcrossRoundsIsNotReExecuted() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result(callBlock()),
            result(callBlock()),
            result("好了")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        assertEquals(listOf("list_books"), host.invocations)
        val results = session.state.value.messages.filter { it.role == AgentRole.TOOL_RESULT }
        assertEquals(2, results.size)
        assertTrue(results.last().content.contains("duplicate_call"))
        assertEquals(3, gateway.requests.size)
    }

    @Test
    fun differentArgumentsAreNotDuplicates() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result(callBlock("get_recent_log", """{"limit":5}""")),
            result(callBlock("get_recent_log", """{"limit":10}""")),
            result("好了")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("日志")

        assertEquals(listOf("get_recent_log:5", "get_recent_log:10"), host.invocations)
        assertEquals(3, gateway.requests.size)
    }

    @Test
    fun blankFinalAnswerGetsSystemFallback() = runTest {
        val thinkOpen = "<" + "think" + ">"
        val thinkClose = "</" + "think" + ">"
        val gateway = ScriptedGateway().enqueue(result(thinkOpen + "推理" + thinkClose))
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        val last = session.state.value.messages.last()
        assertEquals(AgentRole.SYSTEM, last.role)
        assertTrue(last.isError)
        assertTrue(last.content.contains("模型没有返回可用内容"))
        assertEquals(1, session.state.value.usage.requestCount)
    }

    @Test
    fun residualResultBlockIsStrippedFromFinalAnswer() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result(
                "结果如下\n" +
                    AgentToolProtocol.renderToolResultBlock("list_books", true, "共 1 本书") +
                    "请查收"
            )
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        assertEquals("结果如下\n请查收", session.state.value.messages.last().content)
        assertTrue(host.invocations.isEmpty())
    }

    @Test
    fun proseMixedWithCallStillExecutes() = runTest {
        val gateway = ScriptedGateway().enqueue(
            result("我来查一下\n" + callBlock() + "\n马上"),
            result("书架有 1 本书")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        assertEquals(listOf("list_books"), host.invocations)
        assertEquals("书架有 1 本书", session.state.value.messages.last().content)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun fullWidthCallBlockExecutesEndToEnd() = runTest {
        // step-5-preview 实证故障：全角标签 + 全角引号的调用块必须能执行。
        val fullWidthBlock = "＜tool_call＞" +
            "｛＂name＂：＂list_books＂，＂arguments＂：｛｝｝" +
            "＜/tool_call＞"
        val gateway = ScriptedGateway().enqueue(
            result(fullWidthBlock),
            result("书架上有 1 本书。")
        )
        val host = RecordingToolHost()
        val session = newSession(gateway, host, config())

        session.send("书架")

        assertEquals(listOf("list_books"), host.invocations)
        assertEquals("书架上有 1 本书。", session.state.value.messages.last().content)
        assertEquals(2, gateway.requests.size)
    }
}
