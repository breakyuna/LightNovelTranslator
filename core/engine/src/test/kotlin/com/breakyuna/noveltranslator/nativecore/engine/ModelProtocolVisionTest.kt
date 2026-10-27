package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure-JVM vision coverage: wire shapes, bounds and pre-dispatch capability gates. */
class ModelProtocolVisionTest {
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val pngBase64 = "iVBORw0KGgo="
    private val png = ModelProtocolImage("image/png", pngBytes)

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun textRequest(protocolModel: String = "fixture") = ModelProtocolRequest(
        protocolModel,
        "system prompt",
        listOf(ModelProtocolMessage(ModelMessageRole.USER, "hello")),
    )

    private fun imageRequest(
        model: String = "fixture",
        text: String = "look",
        images: List<ModelProtocolImage> = listOf(png),
    ) = ModelProtocolRequest(
        model,
        "system prompt",
        listOf(ModelProtocolMessage(ModelMessageRole.USER, text, images = images)),
    )

    @Test
    fun chatImageUsesDataUrlWhileTextOnlyStaysAPlainString() {
        val body = parse(ModelProtocols.request(ModelProtocol.OPENAI_CHAT_COMPLETIONS, imageRequest()))
        val content = body.getValue("messages").jsonArray[1].jsonObject.getValue("content").jsonArray
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("look", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        val image = content[1].jsonObject
        assertEquals("image_url", image.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/png;base64,$pngBase64", image.getValue("image_url").jsonObject
            .getValue("url").jsonPrimitive.content)

        val textOnly = parse(ModelProtocols.request(ModelProtocol.OPENAI_CHAT_COMPLETIONS, textRequest()))
        assertTrue(textOnly.getValue("messages").jsonArray[1].jsonObject.getValue("content") is JsonPrimitive)
    }

    @Test
    fun userImagesPreserveOrderAcrossWhitelistedMimeTypes() {
        val request = ModelProtocolRequest("fixture", null, listOf(ModelProtocolMessage(
            ModelMessageRole.USER, "", images = listOf(
                ModelProtocolImage("image/jpeg", byteArrayOf(1)),
                ModelProtocolImage("image/webp", byteArrayOf(2)),
            ))))
        val content = parse(ModelProtocols.request(ModelProtocol.OPENAI_CHAT_COMPLETIONS, request))
            .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("data:image/jpeg;base64,AQ==", content[0].jsonObject
            .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("data:image/webp;base64,Ag==", content[1].jsonObject
            .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun responsesImageUsesInputImage() {
        val body = parse(ModelProtocols.request(ModelProtocol.OPENAI_RESPONSES, imageRequest()))
        val content = body.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("input_text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        val image = content[1].jsonObject
        assertEquals("input_image", image.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/png;base64,$pngBase64", image.getValue("image_url").jsonPrimitive.content)
    }

    @Test
    fun anthropicImageUsesBase64SourceAndMediaType() {
        val body = parse(ModelProtocols.request(ModelProtocol.ANTHROPIC_MESSAGES, imageRequest()))
        val content = body.getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        val image = content[1].jsonObject
        assertEquals("image", image.getValue("type").jsonPrimitive.content)
        val source = image.getValue("source").jsonObject
        assertEquals("base64", source.getValue("type").jsonPrimitive.content)
        assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
        assertEquals(pngBase64, source.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun geminiImageUsesInlineData() {
        val body = parse(ModelProtocols.request(ModelProtocol.GOOGLE_GEMINI, imageRequest()))
        val parts = body.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray
        assertEquals("look", parts[0].jsonObject.getValue("text").jsonPrimitive.content)
        val inline = parts[1].jsonObject.getValue("inline_data").jsonObject
        assertEquals("image/png", inline.getValue("mime_type").jsonPrimitive.content)
        assertEquals(pngBase64, inline.getValue("data").jsonPrimitive.content)
    }

    @Test
    fun sakuraRejectsImagesInsteadOfDroppingThem() {
        try {
            ModelProtocols.request(ModelProtocol.SAKURA_LLM, imageRequest())
            fail("Accepted an image for SakuraLLM")
        } catch (failure: AgentModelFailure) {
            assertEquals(ModelFailureKind.CONTENT, failure.kind)
        }
    }

    @Test
    fun imageOnlyUserTurnIsAllowedButEmptyTextWithoutImageIsNot() {
        val imageOnly = ModelProtocolMessage(ModelMessageRole.USER, "", images = listOf(png))
        val content = parse(ModelProtocols.request(ModelProtocol.OPENAI_CHAT_COMPLETIONS,
            ModelProtocolRequest("fixture", null, listOf(imageOnly))))
            .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content.single().jsonObject.getValue("type").jsonPrimitive.content)

        assertRejected { ModelProtocolMessage(ModelMessageRole.USER, "") }
    }

    @Test
    fun assistantAndToolMessagesNeverCarryImages() {
        assertRejected {
            ModelProtocolMessage(ModelMessageRole.ASSISTANT, "x", images = listOf(png))
        }
        assertRejected {
            ModelProtocolMessage(ModelMessageRole.TOOL, "x", toolCallId = "call", toolName = "lookup",
                images = listOf(png))
        }
    }

    @Test
    fun imageRejectsNonWhitelistedOrControlledMimeAndOversizedBytes() {
        assertRejected { ModelProtocolImage("image/gif", pngBytes) }
        assertRejected { ModelProtocolImage("image/png\u0000", pngBytes) }
        assertRejected { ModelProtocolImage("image/png", ByteArray(0)) }
        assertRejected { ModelProtocolImage("image/png", ByteArray(ModelProtocolImage.MAX_IMAGE_BYTES + 1)) }
        assertRejected { ModelProtocolImage("image/png", pngBytes, "0".repeat(64)) }
    }

    @Test
    fun requestRejectsCombinedImageBytesAboveTheTotalBudget() {
        val half = ModelProtocolImage.MAX_TOTAL_IMAGE_BYTES / 2 + 1
        val first = ModelProtocolImage("image/png", ByteArray(half))
        val second = ModelProtocolImage("image/png", ByteArray(half))
        assertRejected {
            ModelProtocolRequest("fixture", null, listOf(
                ModelProtocolMessage(ModelMessageRole.USER, "one", images = listOf(first)),
                ModelProtocolMessage(ModelMessageRole.USER, "two", images = listOf(second)),
            ))
        }
    }

    @Test
    fun imageToStringNeverContainsThePayloadOrDataUrl() {
        val image = ModelProtocolImage("image/png", pngBytes, TextProjectFingerprint.bytes(pngBytes))
        assertFalse(image.toString().contains(pngBase64))
        assertFalse(image.toString().contains("data:image"))
        val message = ModelProtocolMessage(ModelMessageRole.USER, "secret text", images = listOf(image))
        assertFalse(message.toString().contains("secret text"))
        assertFalse(message.toString().contains(pngBase64))
        val request = imageRequest()
        assertFalse(request.toString().contains(pngBase64))
    }

    @Test
    fun transportAllowsPlainTextEvenWhenCallerSetsVisionRequirement() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"choices":[{"finish_reason":"stop","message":{"content":"hello"}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport = ModelProtocolTransport(CompatibleHttpTransport(client))
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "https://example.com/v1", "fixture")
        val response = transport.complete(ModelProtocol.OPENAI_CHAT_COMPLETIONS, settings, "", textRequest(),
            CapabilityRequirement(vision = true))
        assertEquals("hello", response.text)
        assertEquals(1, calls)
    }

    @Test
    fun transportDispatchesImageOnlyAfterVisionIsProvenSupported() = runBlocking {
        var calls = 0
        var body = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"choices":[{"finish_reason":"stop","message":{"content":"seen"}}]}"""
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val catalog = CapabilityCatalog.of(
            CapabilityModelRef(ModelProtocol.OPENAI_CHAT_COMPLETIONS, "fixture") to
                ModelCapabilities(vision = CapabilitySupport.SUPPORTED),
        )
        val transport = ModelProtocolTransport(CompatibleHttpTransport(client), catalog)
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "https://example.com/v1", "fixture")
        val response = transport.complete(ModelProtocol.OPENAI_CHAT_COMPLETIONS, settings, "", imageRequest())
        assertEquals("seen", response.text)
        assertEquals(1, calls)
        assertTrue(body.contains("data:image/png;base64,$pngBase64"))
    }

    @Test
    fun transportRejectsUnknownVisionBeforeDispatchForAnImageRequest() = runBlocking {
        var dispatched = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            dispatched = true
            throw AssertionError("Vision rejection must happen before HTTP")
        }.build()
        val transport = ModelProtocolTransport(CompatibleHttpTransport(client))
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://127.0.0.1:1/v1", "fixture")
        try {
            transport.complete(ModelProtocol.OPENAI_CHAT_COMPLETIONS, settings, "", imageRequest())
            fail("Accepted an image with unknown vision capability")
        } catch (failure: UnsupportedModelCapability) {
            assertEquals(CapabilityFeature.VISION, failure.feature)
            assertEquals(CapabilityRejectionReason.UNKNOWN, failure.reason)
            assertFalse(dispatched)
        }
    }

    @Test
    fun transportRejectsSakuraImageEvenIfCatalogClaimsSupport() = runBlocking {
        var dispatched = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            dispatched = true
            throw AssertionError("Vision rejection must happen before HTTP")
        }.build()
        val catalog = CapabilityCatalog.of(
            CapabilityModelRef(ModelProtocol.SAKURA_LLM, "fixture") to
                ModelCapabilities(vision = CapabilitySupport.SUPPORTED),
        )
        val transport = ModelProtocolTransport(CompatibleHttpTransport(client), catalog)
        val settings = NativeModelSettings(ModelConfigurationRef("fixture", 1), "http://127.0.0.1:1/v1", "fixture")
        try {
            transport.complete(ModelProtocol.SAKURA_LLM, settings, "", imageRequest())
            fail("Accepted an image for SakuraLLM")
        } catch (failure: UnsupportedModelCapability) {
            assertEquals(CapabilityFeature.VISION, failure.feature)
            assertEquals(CapabilityRejectionReason.UNSUPPORTED, failure.reason)
            assertFalse(dispatched)
        }
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
