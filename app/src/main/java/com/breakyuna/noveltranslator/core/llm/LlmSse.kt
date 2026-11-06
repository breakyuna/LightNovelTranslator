package com.breakyuna.noveltranslator.core.llm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject

/** SSE 的 `data:` 前缀与流结束哨兵，三家 provider 的 SSE 形状一致。 */
private const val SSE_DATA_PREFIX = "data:"
private const val SSE_DONE = "[DONE]"

/**
 * 读可能为 JSON null 的字符串字段。
 *
 * 显式判定 `JSONObject.NULL`，避免把 `"content": null` 当成字面量 "null" 追加进正文：
 * 工具调用轮的 OpenAI 兼容响应正是这个形状。
 */
internal fun JSONObject.optText(key: String): String {
    val value = opt(key)
    return if (value == null || value === JSONObject.NULL) "" else value.toString()
}

/**
 * SSE 帧读取器：按 `data:` 行累积、空行分帧，忽略注释行与 `[DONE]`。
 *
 * 必须在 IO 调度器上收集（[LlmClient.executeStreaming] 整体 `flowOn(Dispatchers.IO)`）：
 * `readUtf8Line` 是阻塞读。取消时经 [call] 主动打断底层连接，否则阻塞读要等下一个数据包
 * 才会返回；循环里也同时检查协程活跃状态。
 */
internal fun readSseFrames(response: Response, call: Call): Flow<JSONObject> = flow {
    currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
    val source = response.body?.source() ?: return@flow
    val pending = StringBuilder()
    while (currentCoroutineContext().isActive) {
        val line = source.readUtf8Line() ?: break
        if (line.isEmpty()) {
            val frame = pending.takeFrame()
            if (frame != null) emit(frame)
            continue
        }
        // ':' 开头是注释 / keep-alive，直接丢弃。
        if (line.startsWith(":")) continue
        if (line.startsWith(SSE_DATA_PREFIX)) {
            if (pending.isNotEmpty()) pending.append('\n')
            pending.append(line.removePrefix(SSE_DATA_PREFIX).trimStart())
        }
    }
    // 末尾没有空行收尾的最后一帧。
    pending.takeFrame()?.let { emit(it) }
}

/** 取出累积的 data 段并清空缓冲；`[DONE]` 与坏 JSON 都返回 null（跳过该帧）。 */
private fun StringBuilder.takeFrame(): JSONObject? {
    if (isEmpty()) return null
    val payload = toString().trim()
    setLength(0)
    if (payload.isEmpty() || payload == SSE_DONE) return null
    return runCatching { JSONObject(payload) }.getOrNull()
}

/**
 * 把已经拿到的全文按小分片回放成 [LlmStreamChunk.TextDelta]。
 *
 * 只在"SSE 不可用、整段回退非流式"时使用：此时界面本来会看到"等待 → 全文突然出现"，
 * 回放让它仍有逐字反馈。[chunkChars] / [delayMs] / [budgetMs] 都设了上限——回放是展示层
 * 的润色，不能把回合时长拉长到影响使用；超出预算后剩余部分一次给完。
 *
 * 无论怎么分片，按顺序拼接所有 delta 一定等于原文：这是回放唯一不可妥协的不变式。
 */
internal fun replayTextAsDeltas(
    text: String,
    chunkChars: Int,
    delayMs: Long,
    budgetMs: Long
): Flow<LlmStreamChunk> = flow {
    if (text.isEmpty()) return@flow
    val step = chunkChars.coerceAtLeast(1)
    // 没有预算就一次给完：调用方明确表示不做润色。
    if (delayMs <= 0L || budgetMs <= 0L) {
        emit(LlmStreamChunk.TextDelta(text))
        return@flow
    }
    var cursor = 0
    var budget = budgetMs
    while (cursor < text.length) {
        val end = minOf(cursor + step, text.length)
        emit(LlmStreamChunk.TextDelta(text.substring(cursor, end)))
        cursor = end
        budget -= delayMs
        if (budget <= 0L || cursor >= text.length) break
        delay(delayMs)
    }
    if (cursor < text.length) {
        emit(LlmStreamChunk.TextDelta(text.substring(cursor)))
    }
}

/**
 * OpenAI 兼容 SSE 的累积状态：文本增量、原生工具调用参数（按 index 合并）、用量。
 *
 * `stream_options.include_usage` 打开时，最后一个 choices 为空的帧携带 usage；
 * 不认这个字段的 provider 干脆不返回 usage，此时调用方按 [TokenCalculator] 估算，
 * 与非流式路径口径一致。
 */
internal class OpenAiStreamAccumulator {

    private val text = StringBuilder()
    private val order = mutableListOf<Int>()
    private val ids = HashMap<Int, String>()
    private val names = HashMap<Int, String>()
    private val arguments = HashMap<Int, StringBuilder>()

    var finishReason: String? = null
        private set
    var promptTokens: Long? = null
        private set
    var completionTokens: Long? = null
        private set
    var cachedTokens: Long = 0
        private set

    /** 消费一帧，返回本帧新增的可见文本（可能为空）。 */
    fun consume(frame: JSONObject): String {
        frame.optJSONObject("usage")?.let { usage ->
            if (usage.has("prompt_tokens")) promptTokens = usage.optLong("prompt_tokens")
            if (usage.has("completion_tokens")) completionTokens = usage.optLong("completion_tokens")
            cachedTokens = usage.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens")
                ?: usage.optLong("prompt_cache_hit_tokens")
                ?: 0L
        }
        val choice = frame.optJSONArray("choices")?.optJSONObject(0) ?: return ""
        choice.optText("finish_reason").takeIf { it.isNotBlank() }?.let { finishReason = it }
        val delta = choice.optJSONObject("delta") ?: return ""
        val appended = delta.optText("content")
        if (appended.isNotEmpty()) text.append(appended)
        val toolCalls = delta.optJSONArray("tool_calls") ?: return appended
        for (index in 0 until toolCalls.length()) {
            val entry = toolCalls.optJSONObject(index) ?: continue
            val slot = entry.optInt("index", index)
            if (slot !in order) order += slot
            entry.optText("id").takeIf { it.isNotBlank() }?.let { ids[slot] = it }
            val function = entry.optJSONObject("function")
            function?.optText("name")?.takeIf { it.isNotBlank() }?.let { names[slot] = it }
            function?.optText("arguments")?.let {
                if (it.isNotEmpty()) arguments.getOrPut(slot) { StringBuilder() }.append(it)
            }
        }
        return appended
    }

    fun text(): String = text.toString()

    /** 按流内出现顺序输出合并完参数的工具调用；名字始终为空的槽位丢弃。 */
    fun toolCalls(): List<LlmToolCall> = order.mapNotNull { slot ->
        val name = names[slot]?.trim().orEmpty()
        if (name.isEmpty()) {
            null
        } else {
            LlmToolCall(
                id = ids[slot]?.takeIf { it.isNotBlank() } ?: "call_$slot",
                name = name,
                argumentsJson = arguments[slot]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: "{}"
            )
        }
    }
}

/**
 * Anthropic SSE 的累积状态：`content_block_delta` 的 `text_delta` 是正文，
 * `message_start` 与 `message_delta` 各带一部分用量。
 */
internal class AnthropicStreamAccumulator {

    private val text = StringBuilder()

    var inputTokens: Long? = null
        private set
    var outputTokens: Long? = null
        private set
    var cachedTokens: Long = 0
        private set
    var stopReason: String? = null
        private set

    /** 消费一帧，返回本帧新增的可见文本（可能为空）。 */
    fun consume(frame: JSONObject): String {
        when (frame.optText("type")) {
            "message_start" -> {
                val usage = frame.optJSONObject("message")?.optJSONObject("usage")
                if (usage != null) {
                    if (usage.has("input_tokens")) inputTokens = usage.optLong("input_tokens")
                    if (usage.has("output_tokens")) outputTokens = usage.optLong("output_tokens")
                    cachedTokens = usage.optLong("cache_read_input_tokens")
                }
            }
            "content_block_delta" -> {
                val delta = frame.optJSONObject("delta") ?: return ""
                if (delta.optText("type") != "text_delta") return ""
                val appended = delta.optText("text")
                if (appended.isNotEmpty()) text.append(appended)
                return appended
            }
            "message_delta" -> {
                val usage = frame.optJSONObject("usage")
                if (usage != null && usage.has("output_tokens")) {
                    outputTokens = usage.optLong("output_tokens")
                }
                frame.optJSONObject("delta")?.optText("stop_reason")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { stopReason = it }
            }
        }
        return ""
    }

    fun text(): String = text.toString()
}

/** Gemini SSE（`alt=sse`）的累积状态：每帧一个候选，parts 顺序拼接。 */
internal class GeminiStreamAccumulator {

    private val text = StringBuilder()

    var promptTokens: Long? = null
        private set
    var completionTokens: Long? = null
        private set
    var finishReason: String? = null
        private set

    /** 消费一帧，返回本帧新增的可见文本（可能为空）。 */
    fun consume(frame: JSONObject): String {
        frame.optJSONObject("usageMetadata")?.let { usage ->
            if (usage.has("promptTokenCount")) promptTokens = usage.optLong("promptTokenCount")
            if (usage.has("candidatesTokenCount")) {
                completionTokens = usage.optLong("candidatesTokenCount")
            }
        }
        val candidate = frame.optJSONArray("candidates")?.optJSONObject(0) ?: return ""
        candidate.optText("finishReason").takeIf { it.isNotBlank() }?.let { finishReason = it }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val appended = StringBuilder()
        for (index in 0 until parts.length()) {
            appended.append(parts.optJSONObject(index)?.optText("text").orEmpty())
        }
        if (appended.isNotEmpty()) text.append(appended)
        return appended.toString()
    }

    fun text(): String = text.toString()
}
