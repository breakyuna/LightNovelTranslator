package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelStopReason
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.UsageSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ModelProtocolsTest {
    private val tool = ModelToolSpec("lookup", "Look up a value", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) })
    })

    private fun textRequest(model: String = "fixture") = ModelProtocolRequest(
        model,
        "system prompt",
        listOf(ModelProtocolMessage(ModelMessageRole.USER, "hello")),
        emptyList(),
        128,
        0.2,
    )

    private fun request(
        model: String = "fixture",
        tools: List<ModelToolSpec> = listOf(tool),
        maxOutputTokens: Int? = 128,
    ) = ModelProtocolRequest(
        model,
        "system prompt",
        listOf(
            ModelProtocolMessage(ModelMessageRole.USER, "hello"),
            ModelProtocolMessage(
                ModelMessageRole.ASSISTANT,
                "",
                listOf(ModelToolCall("call-1", "lookup", "{\"q\":\"x\"}")),
            ),
            ModelProtocolMessage(ModelMessageRole.TOOL, "result", toolCallId = "call-1", toolName = "lookup"),
        ),
        tools,
        maxOutputTokens,
        0.2,
    )

    private fun rejected(protocol: ModelProtocol, body: String): ModelFailureKind {
        try {
            ModelProtocols.response(protocol, body)
        } catch (failure: AgentModelFailure) {
            return failure.kind
        }
        throw AssertionError("Accepted malformed response")
    }

    private fun parse(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun chatRequestKeepsToolCallIdentityAndExactArgumentString() {
        val body = parse(ModelProtocols.request(ModelProtocol.OPENAI_CHAT_COMPLETIONS, request()))
        assertEquals("fixture", body.getValue("model").jsonPrimitive.content)
        assertFalse(body.getValue("stream").jsonPrimitive.content.toBoolean())
        assertEquals(128, body.getValue("max_tokens").jsonPrimitive.content.toInt())
        val messages = body.getValue("messages").jsonArray
        assertEquals("system", messages[0].jsonObject.getValue("role").jsonPrimitive.content)
        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant.getValue("role").jsonPrimitive.content)
        val call = assistant.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals("call-1", call.getValue("id").jsonPrimitive.content)
        assertEquals("function", call.getValue("type").jsonPrimitive.content)
        assertEquals("{\"q\":\"x\"}", call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
        val result = messages[3].jsonObject
        assertEquals("tool", result.getValue("role").jsonPrimitive.content)
        assertEquals("call-1", result.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("lookup", body.getValue("tools").jsonArray.single().jsonObject
            .getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun chatResponseParsesTextThinkingToolCallsAndUsage() {
        val body = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("finish_reason", "tool_calls")
                    put("message", buildJsonObject {
                        put("content", "hello")
                        put("reasoning_content", "think")
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("id", "call-1")
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "lookup")
                                    put("arguments", "{\"q\":\"x\"}")
                                })
                            })
                        })
                    })
                })
            })
            put("usage", buildJsonObject {
                put("prompt_tokens", 100)
                put("completion_tokens", 20)
                put("prompt_tokens_details", buildJsonObject { put("cached_tokens", 40) })
                put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", 5) })
            })
        }.toString()
        val response = ModelProtocols.response(ModelProtocol.OPENAI_CHAT_COMPLETIONS, body)
        assertEquals("hello", response.text)
        assertEquals("think", response.thinking)
        assertEquals(ModelStopReason.TOOL_CALLS, response.stopReason)
        assertEquals("call-1", response.toolCalls.single().id)
        assertEquals("{\"q\":\"x\"}", response.toolCalls.single().argumentsJson)
        assertEquals(100L, response.usage.input.tokens)
        assertEquals(40L, response.usage.cacheRead.tokens)
        assertEquals(5L, response.usage.thinking.tokens)
        assertEquals(20L, response.usage.output.tokens)
        assertEquals(UsageSource.ACTUAL, response.usage.input.source)
        assertEquals(UsageSource.UNKNOWN, response.usage.cacheWrite.source)
        assertNull(response.usage.cacheWrite.tokens)
    }

    @Test
    fun chatRejectsTruncationFilteringProviderErrorsAndMalformedShapes() {
        assertEquals(ModelFailureKind.TRUNCATED, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}"""))
        assertEquals(ModelFailureKind.CONTENT_FILTERED, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}"""))
        assertEquals(ModelFailureKind.CONTENT, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"error":{"message":"provider failed"}}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS, """{"choices":[]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[{"finish_reason":"banana","message":{"content":"x"}}]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[],"choices":[]}"""))
        assertEquals(ModelFailureKind.EMPTY_RESPONSE, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[{"finish_reason":"stop","message":{"content":""}}]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c","type":"function","function":{"name":"lookup","arguments":"{bad"}}]}}]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[{"finish_reason":"stop","message":{"content":"x","tool_calls":[{"id":"c","type":"function","function":{"name":"lookup","arguments":"{}"}}]}}]}"""))
        assertEquals(ModelFailureKind.CONTENT, rejected(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[]}""" + " ".repeat(2 * 1024 * 1024)))
    }

    @Test
    fun responsesRequestUsesNativeItemsAndResponseParsesReasoningAndCalls() {
        val requestBody = parse(ModelProtocols.request(ModelProtocol.OPENAI_RESPONSES, request()))
        assertEquals("system prompt", requestBody.getValue("instructions").jsonPrimitive.content)
        assertFalse(requestBody.getValue("stream").jsonPrimitive.content.toBoolean())
        assertFalse(requestBody.getValue("store").jsonPrimitive.content.toBoolean())
        val input = requestBody.getValue("input").jsonArray
        assertEquals("user", input[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("input_text", input[0].jsonObject.getValue("content").jsonArray.single().jsonObject
            .getValue("type").jsonPrimitive.content)
        assertEquals("function_call", input[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("call-1", input[1].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("function_call_output", input[2].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("lookup", requestBody.getValue("tools").jsonArray.single().jsonObject
            .getValue("name").jsonPrimitive.content)

        val body = buildJsonObject {
            put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject {
                    put("type", "reasoning")
                    put("summary", buildJsonArray { add(buildJsonObject {
                        put("type", "summary_text"); put("text", "think")
                    }) })
                })
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    put("content", buildJsonArray { add(buildJsonObject {
                        put("type", "output_text"); put("text", "hello")
                    }) })
                })
                add(buildJsonObject {
                    put("type", "function_call")
                    put("call_id", "call-1")
                    put("name", "lookup")
                    put("arguments", "{\"q\":\"x\"}")
                })
            })
            put("usage", buildJsonObject {
                put("input_tokens", 90)
                put("output_tokens", 12)
                put("input_tokens_details", buildJsonObject { put("cached_tokens", 30); put("cache_write_tokens", 4) })
                put("output_tokens_details", buildJsonObject { put("reasoning_tokens", 3) })
            })
        }.toString()
        val response = ModelProtocols.response(ModelProtocol.OPENAI_RESPONSES, body)
        assertEquals("hello", response.text)
        assertEquals("think", response.thinking)
        assertEquals(ModelStopReason.TOOL_CALLS, response.stopReason)
        assertEquals("{\"q\":\"x\"}", response.toolCalls.single().argumentsJson)
        assertEquals(90L, response.usage.input.tokens)
        assertEquals(30L, response.usage.cacheRead.tokens)
        assertEquals(4L, response.usage.cacheWrite.tokens)
        assertEquals(3L, response.usage.thinking.tokens)
        assertEquals(12L, response.usage.output.tokens)

        assertEquals(ModelFailureKind.TRUNCATED, rejected(ModelProtocol.OPENAI_RESPONSES,
            """{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}"""))
        assertEquals(ModelFailureKind.CONTENT_FILTERED, rejected(ModelProtocol.OPENAI_RESPONSES,
            """{"status":"incomplete","incomplete_details":{"reason":"content_filter"},"output":[]}"""))
        assertEquals(ModelFailureKind.CONTENT, rejected(ModelProtocol.OPENAI_RESPONSES, """{"status":"failed"}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.OPENAI_RESPONSES,
            """{"status":"completed","output":[{"type":"web_search_call"}]}"""))
    }

    @Test
    fun geminiRequestUsesSystemInstructionAndFunctionResponse() {
        val requestBody = parse(ModelProtocols.request(ModelProtocol.GOOGLE_GEMINI, request()))
        assertEquals("system prompt", requestBody.getValue("systemInstruction").jsonObject
            .getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        val contents = requestBody.getValue("contents").jsonArray
        assertEquals("user", contents[0].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("model", contents[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("lookup", contents[1].jsonObject.getValue("parts").jsonArray.single().jsonObject
            .getValue("functionCall").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("lookup", contents[2].jsonObject.getValue("parts").jsonArray.single().jsonObject
            .getValue("functionResponse").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(128, requestBody.getValue("generationConfig").jsonObject
            .getValue("maxOutputTokens").jsonPrimitive.content.toInt())
        assertEquals("lookup", requestBody.getValue("tools").jsonArray.single().jsonObject
            .getValue("functionDeclarations").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun geminiResponseParsesThoughtFunctionCallAndUsage() {
        val body = buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("finishReason", "STOP")
                    put("content", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", "hello") })
                            add(buildJsonObject { put("text", "think"); put("thought", true) })
                            add(buildJsonObject {
                                put("functionCall", buildJsonObject {
                                    put("id", "g-1")
                                    put("name", "lookup")
                                    put("args", buildJsonObject { put("q", "x") })
                                })
                            })
                        })
                    })
                })
            })
            put("usageMetadata", buildJsonObject {
                put("promptTokenCount", 80)
                put("cachedContentTokenCount", 20)
                put("candidatesTokenCount", 9)
                put("thoughtsTokenCount", 2)
            })
        }.toString()
        val response = ModelProtocols.response(ModelProtocol.GOOGLE_GEMINI, body)
        assertEquals("hello", response.text)
        assertEquals("think", response.thinking)
        assertEquals("g-1", response.toolCalls.single().id)
        assertEquals("{\"q\":\"x\"}", response.toolCalls.single().argumentsJson)
        assertEquals(80L, response.usage.input.tokens)
        assertEquals(20L, response.usage.cacheRead.tokens)
        assertEquals(2L, response.usage.thinking.tokens)
        assertEquals(9L, response.usage.output.tokens)

        val noId = """{"candidates":[{"finishReason":"stop","content":{"parts":[{"functionCall":{"name":"lookup","args":{}}}]}}]}"""
        assertEquals("gemini-call-0", ModelProtocols.response(ModelProtocol.GOOGLE_GEMINI, noId).toolCalls.single().id)

        assertEquals(ModelFailureKind.CONTENT_FILTERED, rejected(ModelProtocol.GOOGLE_GEMINI,
            """{"promptFeedback":{"blockReason":"SAFETY"},"candidates":[]}"""))
        assertEquals(ModelFailureKind.TRUNCATED, rejected(ModelProtocol.GOOGLE_GEMINI,
            """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[{"text":"x"}]}}]}"""))
        assertEquals(ModelFailureKind.CONTENT_FILTERED, rejected(ModelProtocol.GOOGLE_GEMINI,
            """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.GOOGLE_GEMINI,
            """{"candidates":[{"finishReason":"MALFORMED_FUNCTION_CALL","content":{"parts":[]}}]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.GOOGLE_GEMINI,
            """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"inlineData":{}}]}}]}"""))
        assertEquals(ModelFailureKind.EMPTY_RESPONSE, rejected(ModelProtocol.GOOGLE_GEMINI,
            """{"candidates":[]}"""))
    }

    @Test
    fun anthropicRequestAndResponseKeepMessagesShapeAndCacheUsage() {
        val noLimit = ModelProtocolRequest(
            "fixture",
            "system prompt",
            listOf(ModelProtocolMessage(ModelMessageRole.USER, "hello")),
            listOf(tool),
            null,
        )
        val requestBody = parse(ModelProtocols.request(ModelProtocol.ANTHROPIC_MESSAGES, noLimit))
        assertEquals(64_000, requestBody.getValue("max_tokens").jsonPrimitive.content.toInt())
        assertFalse(requestBody.containsKey("stream"))
        assertEquals("system prompt", requestBody.getValue("system").jsonPrimitive.content)
        assertEquals("object", requestBody.getValue("tools").jsonArray.single().jsonObject
            .getValue("input_schema").jsonObject.getValue("type").jsonPrimitive.content)

        val withCalls = parse(ModelProtocols.request(ModelProtocol.ANTHROPIC_MESSAGES, request()))
        val assistant = withCalls.getValue("messages").jsonArray[1].jsonObject
        assertEquals("tool_use", assistant.getValue("content").jsonArray.single().jsonObject
            .getValue("type").jsonPrimitive.content)
        val result = withCalls.getValue("messages").jsonArray[2].jsonObject
        assertEquals("user", result.getValue("role").jsonPrimitive.content)
        assertEquals("tool_result", result.getValue("content").jsonArray.single().jsonObject
            .getValue("type").jsonPrimitive.content)

        val body = buildJsonObject {
            put("type", "message")
            put("role", "assistant")
            put("stop_reason", "tool_use")
            put("content", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "hello") })
                add(buildJsonObject { put("type", "thinking"); put("thinking", "think") })
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", "call-1")
                    put("name", "lookup")
                    put("input", buildJsonObject { put("q", "x") })
                })
            })
            put("usage", buildJsonObject {
                put("input_tokens", 70)
                put("cache_read_input_tokens", 25)
                put("cache_creation_input_tokens", 6)
                put("output_tokens", 8)
            })
        }.toString()
        val response = ModelProtocols.response(ModelProtocol.ANTHROPIC_MESSAGES, body)
        assertEquals("hello", response.text)
        assertEquals("think", response.thinking)
        assertEquals("{\"q\":\"x\"}", response.toolCalls.single().argumentsJson)
        assertEquals(70L, response.usage.input.tokens)
        assertEquals(25L, response.usage.cacheRead.tokens)
        assertEquals(6L, response.usage.cacheWrite.tokens)
        assertEquals(8L, response.usage.output.tokens)
        assertEquals(UsageSource.UNKNOWN, response.usage.thinking.source)

        assertEquals(ModelFailureKind.TRUNCATED, rejected(ModelProtocol.ANTHROPIC_MESSAGES,
            """{"type":"message","stop_reason":"max_tokens","content":[]}"""))
        assertEquals(ModelFailureKind.CONTENT_FILTERED, rejected(ModelProtocol.ANTHROPIC_MESSAGES,
            """{"type":"message","stop_reason":"refusal","content":[]}"""))
        assertEquals(ModelFailureKind.CONTENT, rejected(ModelProtocol.ANTHROPIC_MESSAGES,
            """{"type":"error","error":{"type":"authentication_error","message":"bad"}}"""))
        assertEquals(ModelFailureKind.CONTEXT_OVERFLOW, rejected(ModelProtocol.ANTHROPIC_MESSAGES,
            """{"type":"message","stop_reason":"model_context_window_exceeded","content":[]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.ANTHROPIC_MESSAGES,
            """{"type":"message","stop_reason":"pause_turn","content":[]}"""))
    }

    @Test
    fun sakuraIsTextOnlyAndParsesOpenAiEnvelope() {
        val body = parse(ModelProtocols.request(ModelProtocol.SAKURA_LLM, textRequest()))
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("stream_options"))
        assertFalse(body.getValue("stream").jsonPrimitive.content.toBoolean())
        assertEquals("hello", body.getValue("messages").jsonArray[1].jsonObject
            .getValue("content").jsonPrimitive.content)

        val response = ModelProtocols.response(ModelProtocol.SAKURA_LLM,
            """{"choices":[{"finish_reason":"stop","message":{"content":"译文"}}]}""")
        assertEquals("译文", response.text)
        assertEquals(UsageSource.UNKNOWN, response.usage.input.source)

        try {
            ModelProtocols.request(ModelProtocol.SAKURA_LLM, request())
            throw AssertionError("Accepted tools for SakuraLLM")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.CONTENT, failure.kind)
        }
        assertEquals(ModelFailureKind.TRUNCATED, rejected(ModelProtocol.SAKURA_LLM,
            """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}"""))
        assertEquals(ModelFailureKind.PARSING, rejected(ModelProtocol.SAKURA_LLM,
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[]}}]}"""))
    }

    @Test
    fun textAndArgumentsAreRedactedFromToString() {
        val response = ModelProtocols.response(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"secret text","tool_calls":[{"id":"c","type":"function","function":{"name":"lookup","arguments":"{\"q\":\"secret arg\"}"}}]}}]}""")
        assertFalse(response.toString().contains("secret text"))
        assertFalse(response.toString().contains("secret arg"))
        assertFalse(response.toolCalls.single().toString().contains("secret arg"))
        assertFalse(request().toString().contains("system prompt"))
        assertFalse(request().toString().contains("hello"))
    }

    @Test
    fun transportEndpointsAndAuthHeadersFollowProtocol() {
        val transport = ModelProtocolTransport()
        val chat = ModelProtocolRequest("fixture", null, listOf(ModelProtocolMessage(ModelMessageRole.USER, "hello")))
        val chatPlan = transport.plan(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://example.com/v1/chat/completions", "fixture", "secret-value", chat)
        assertEquals("https://example.com/v1/chat/completions", chatPlan.url)
        assertEquals("Bearer secret-value", chatPlan.headers["Authorization"])
        assertFalse(chatPlan.toString().contains("secret-value"))
        assertFalse(chatPlan.toString().contains("hello"))

        val responsesPlan = transport.plan(ModelProtocol.OPENAI_RESPONSES,
            "https://example.com/v1/responses", "fixture", "", chat)
        assertEquals("https://example.com/v1/responses", responsesPlan.url)
        assertNull(responsesPlan.headers["Authorization"])

        val anthropic = transport.plan(ModelProtocol.ANTHROPIC_MESSAGES,
            "https://api.anthropic.com", "fixture", "secret-value", chat)
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.url)
        assertEquals("secret-value", anthropic.headers["x-api-key"])
        assertEquals("2023-06-01", anthropic.headers["anthropic-version"])
        assertNull(anthropic.headers["Authorization"])

        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/fixture:generateContent",
            transport.endpointFor(ModelProtocol.GOOGLE_GEMINI, "https://generativelanguage.googleapis.com", "fixture"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent",
            transport.endpointFor(ModelProtocol.GOOGLE_GEMINI, "https://generativelanguage.googleapis.com/v1beta", "models/gemini-x"))
        val gemini = transport.plan(ModelProtocol.GOOGLE_GEMINI,
            "https://generativelanguage.googleapis.com", "fixture", "secret-value", chat)
        assertEquals("secret-value", gemini.headers["x-goog-api-key"])
        assertNull(gemini.headers["Authorization"])

        val sakura = transport.plan(ModelProtocol.SAKURA_LLM,
            "http://localhost:5000/v1", "fixture", "", chat)
        assertEquals("http://localhost:5000/v1/chat/completions", sakura.url)
        assertNull(sakura.headers["Authorization"])
    }

    @Test
    fun transportRejectsModelMismatchAndUnsafeGeminiModelPath() {
        val transport = ModelProtocolTransport()
        val chat = ModelProtocolRequest("fixture", null, listOf(ModelProtocolMessage(ModelMessageRole.USER, "hello")))
        try {
            transport.plan(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "https://example.com/v1", "other", "", chat)
            throw AssertionError("Accepted model mismatch")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.CONTENT, failure.kind)
        }
        try {
            transport.endpointFor(ModelProtocol.GOOGLE_GEMINI, "https://example.com", "../fixture")
            throw AssertionError("Accepted unsafe model path")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.CONTENT, failure.kind)
        }
    }

    @Test
    fun transportCompleteUsesSharedClientAndParsesNonStreamingPayload() = kotlinx.coroutines.runBlocking {
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("POST", chain.request().method)
            assertEquals("Bearer secret-value", chain.request().header("Authorization"))
            assertEquals("application/json", chain.request().header("Accept"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"choices":[{"finish_reason":"stop","message":{"content":"hello"}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport = ModelProtocolTransport(CompatibleHttpTransport(client))
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "https://example.com/v1", "fixture")
        val response = transport.complete(ModelProtocol.OPENAI_CHAT_COMPLETIONS, settings, "secret-value", textRequest())
        assertEquals("hello", response.text)
        assertEquals(ModelStopReason.COMPLETE, response.stopReason)
    }

    @Test
    fun transportRejectsVisionBeforeBuildingARequest() {
        var dispatched = false
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            dispatched = true
            throw AssertionError("Capability rejection must happen before HTTP")
        }.build()
        val transport = ModelProtocolTransport(CompatibleHttpTransport(client), CapabilityCatalog.of(
            CapabilityModelRef(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "fixture") to
                ModelCapabilities(vision = CapabilitySupport.UNSUPPORTED),
        ))
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://127.0.0.1:1/v1", "fixture")
        val imageRequest = ModelProtocolRequest("fixture", null, listOf(ModelProtocolMessage(
            ModelMessageRole.USER, "hello", images = listOf(ModelProtocolImage("image/png", byteArrayOf(1, 2, 3))))))
        try {
            kotlinx.coroutines.runBlocking {
                transport.complete(ModelProtocol.OPENAI_CHAT_COMPLETIONS, settings, "", imageRequest)
            }
            throw AssertionError("Accepted vision")
        } catch (failure: UnsupportedModelCapability) {
            assertEquals(CapabilityFeature.VISION, failure.feature)
            assertEquals(CapabilityRejectionReason.UNSUPPORTED, failure.reason)
            assertFalse(dispatched)
        }
    }

    @Test
    fun transportRejectsSakuraToolsBeforeBuildingARequest() {
        val catalog = CapabilityCatalog.of(
            CapabilityModelRef(ModelProtocol.SAKURA_LLM, "fixture") to ModelCapabilities(
                tools = CapabilitySupport.SUPPORTED,
            ),
        )
        var dispatched = false
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            dispatched = true
            throw AssertionError("Capability rejection must happen before HTTP")
        }.build()
        val transport = ModelProtocolTransport(CompatibleHttpTransport(client), catalog)
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://127.0.0.1:1/v1", "fixture")
        try {
            kotlinx.coroutines.runBlocking {
                transport.complete(ModelProtocol.SAKURA_LLM, settings, "", request())
            }
            throw AssertionError("Accepted Sakura tools")
        } catch (failure: UnsupportedModelCapability) {
            assertEquals(CapabilityFeature.TOOLS, failure.feature)
            assertEquals(CapabilityRejectionReason.UNSUPPORTED, failure.reason)
            assertFalse(dispatched)
        }
    }

}
