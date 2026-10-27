package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CompatibleTranslationProtocolTest {
    private val ref = LocalProjectRef(ProjectId("project"), ProjectInstanceId("local"), RecoveryGeneration(1))
    private val configuration = ModelConfigurationRef("remote", 3)
    private val options = RemoteTranslationOptions("English", instructions = "Keep names")
    private val request = TranslationModelRequest(ref,
        RunIdentity(ref.projectId, null, RunId("run"), CommandId("command")), RequestAttemptId("attempt"), configuration,
        listOf(TranslationInput(ItemId(9_007_199_254_740_993L), "原文", ObjectFingerprint("a".repeat(64))),
            TranslationInput(ItemId(2), "source", ObjectFingerprint("b".repeat(64)))), options)
    private fun item(id: JsonElement = JsonPrimitive(request.inputs[0].id.value.toString()),
        fingerprint: String = "a".repeat(64), text: String = "translated"): JsonObject = buildJsonObject {
        put("id", id); put("item_fingerprint", fingerprint); put("text", text)
    }
    private fun content(items: List<JsonElement> = listOf(item(), item(JsonPrimitive("2"), "b".repeat(64))),
        attempt: String = "attempt"): String = buildJsonObject {
        put("request_id", attempt); put("items", JsonArray(items))
    }.toString()
    private fun envelope(content: String = content(), finish: String = "stop", usage: JsonElement? = null) = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", finish); put("message", buildJsonObject { put("content", content) })
        }) })
        if (usage != null) put("usage", usage)
    }.toString()
    private fun rejected(body: String, kind: ModelFailureKind = ModelFailureKind.PARSING) {
        try { CompatibleTranslationProtocol.response(request, body); fail("Accepted malformed output") }
        catch (error: AgentModelFailure) { assertEquals(kind, error.kind) }
    }

    @Test fun precedingContextIsSerializedSeparatelyAndCannotExpandTheResponseIdentitySet() {
        val withContext = TranslationModelRequest(ref, request.run, request.attempt, configuration,
            request.inputs, options, precedings = listOf(TranslationContextLine(ItemId(7), "Previous.", ObjectFingerprint("c".repeat(64)))))
        val body = Json.parseToJsonElement(CompatibleTranslationProtocol.request(withContext, "fixture", options)).jsonObject
        val user = Json.parseToJsonElement(body.getValue("messages").jsonArray.last().jsonObject
            .getValue("content").jsonPrimitive.content).jsonObject
        assertEquals("7", user.getValue("precedings").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(2, CompatibleTranslationProtocol.response(withContext, envelope()).outputs.size)
        try {
            CompatibleTranslationProtocol.response(withContext, envelope(content(listOf(item(), item(JsonPrimitive("2"), "b".repeat(64)),
                item(JsonPrimitive("7"), "c".repeat(64))))))
            fail("Accepted a context-only output")
        } catch (error: AgentModelFailure) { assertEquals(ModelFailureKind.PARSING, error.kind) }
    }

    @Test fun identitiesAboveJavascriptPrecisionAreSerializedAsStringsAndOptionsAreFrozen() {
        val body = Json.parseToJsonElement(CompatibleTranslationProtocol.request(request, "fixture", options)).jsonObject
        assertFalse(body.getValue("stream").jsonPrimitive.boolean)
        val messages = body.getValue("messages").jsonArray
        val user = Json.parseToJsonElement(messages.last().jsonObject.getValue("content").jsonPrimitive.content).jsonObject
        assertEquals("9007199254740993", user.getValue("items").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(user.getValue("items").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.isString)
        assertEquals("English", user.getValue("target_language").jsonPrimitive.content)
        assertEquals("Keep names", user.getValue("instructions").jsonPrimitive.content)
        assertFalse(options.toString().contains("Keep names"))
    }

    @Test fun acceptsCompleteReorderedSetAndKeepsMissingUsageUnknown() {
        val response = CompatibleTranslationProtocol.response(request, envelope(content(listOf(item(JsonPrimitive("2"), "b".repeat(64)), item()))))
        assertEquals(listOf(ItemId(2), request.inputs[0].id), response.outputs.map { it.id })
        assertEquals(UsageSource.UNKNOWN, response.usage.input.source)
    }

    @Test fun rejectsMissingDuplicateUnknownNumericAndStaleIdentities() {
        for (items in listOf(listOf(item()), listOf(item(), item()),
            listOf(item(), item(JsonPrimitive("3"), "b".repeat(64))),
            listOf(item(JsonPrimitive(request.inputs[0].id.value)), item(JsonPrimitive("2"), "b".repeat(64))),
            listOf(item(fingerprint = "c".repeat(64)), item(JsonPrimitive("2"), "b".repeat(64)))))
            rejected(envelope(content(items)))
        rejected(envelope(content(attempt = "old-attempt")))
    }

    @Test fun rejectsDuplicateJsonKeysCodeFencesEmptyResultsAndInjectedNul() {
        rejected(envelope("{\"request_id\":\"attempt\",\"request_id\":\"attempt\",\"items\":[]}"))
        rejected(envelope("```json\n${content()}\n```"))
        for (text in listOf("", " \n", "bad\u0000text", "x".repeat(65_537)))
            rejected(envelope(content(listOf(item(text = text), item(JsonPrimitive("2"), "b".repeat(64))))))
    }

    @Test fun truncationAndFilteringNeverBecomeTranslations() {
        rejected(envelope(finish = "length"), ModelFailureKind.TRUNCATED)
        rejected(envelope(finish = "content_filter"), ModelFailureKind.CONTENT_FILTERED)
        rejected(envelope(finish = "tool_calls"))
    }

    @Test fun actualUsageDoesNotMislabelUncachedInputAsCacheWrites() {
        val response = CompatibleTranslationProtocol.response(request, envelope(usage = buildJsonObject {
            put("prompt_tokens", 100); put("completion_tokens", 25)
            put("prompt_cache_hit_tokens", 20); put("prompt_cache_miss_tokens", 80)
            put("completion_tokens_details", buildJsonObject { put("reasoning_tokens", 10) })
        }))
        assertEquals(100L, response.usage.input.tokens); assertEquals(20L, response.usage.cacheRead.tokens)
        assertEquals(10L, response.usage.thinking.tokens); assertEquals(25L, response.usage.output.tokens)
        assertNull(response.usage.cacheWrite.tokens)
        for (invalid in listOf(JsonPrimitive("5"), JsonPrimitive(-1), JsonPrimitive(1.5)))
            rejected(envelope(usage = buildJsonObject { put("prompt_tokens", invalid) }))
    }
}
