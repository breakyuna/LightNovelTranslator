package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.AgentMessage
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelFailure
import com.breakyuna.noveltranslator.nativecore.domain.AgentModelRequest
import com.breakyuna.noveltranslator.nativecore.domain.AssistantPart
import com.breakyuna.noveltranslator.nativecore.domain.AttachmentId
import com.breakyuna.noveltranslator.nativecore.domain.BranchId
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.FrozenImageRef
import com.breakyuna.noveltranslator.nativecore.domain.MessageId
import com.breakyuna.noveltranslator.nativecore.domain.ModelConfigurationRef
import com.breakyuna.noveltranslator.nativecore.domain.ModelFailureKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfileCapabilities
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfileKind
import com.breakyuna.noveltranslator.nativecore.domain.ModelProfilePurpose
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProfile
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelProtocol
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettings
import com.breakyuna.noveltranslator.nativecore.domain.NativeModelSettingsPort
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.RequestAttemptId
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.UserPart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** Both agent transports must carry the same resolved image or fail before dispatch. */
class CompatibleAgentModelVisionTest {
    private val identity = RunIdentity(ProjectId("project"), ChatId("chat"), RunId("run"), CommandId("send"))
    private val configuration = ModelConfigurationRef("fixture", 1)
    private var protocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val pngBase64 = "iVBORw0KGgo="
    private val imageRef = FrozenImageRef(AttachmentId("image"), "image/png",
        TextProjectFingerprint.bytes(pngBytes))

    private val settings = object : NativeModelSettingsPort {
        override suspend fun current() = NativeModelSettings(configuration, "https://example.com/v1",
            "fixture-model", false, protocol)
        override suspend fun resolve(reference: ModelConfigurationRef) = current()
        override suspend fun credential(reference: ModelConfigurationRef) = ""
        override suspend fun save(endpoint: String, model: String, key: String?): NativeModelSettings =
            error("Not used")
    }

    private val resolver = object : AgentImageResolver {
        override suspend fun resolve(image: FrozenImageRef): ByteArray {
            assertEquals(imageRef, image)
            return pngBytes
        }
    }

    private fun profile(vision: Boolean? = true) =
        NativeModelProfile(
            "profile", "Profile", ModelProfileKind.CUSTOM, ModelProfilePurpose.AGENT, "", 0, true,
            NativeModelSettings(configuration, "https://example.com/v1", "fixture-model", false, protocol),
            capabilities = ModelProfileCapabilities(vision = vision),
        )

    private fun requestWithImage(): AgentModelRequest = AgentModelRequest(identity, BranchId("main"),
        RequestAttemptId("attempt"), configuration,
        listOf(AgentMessage.User(MessageId("user"), listOf(
            UserPart.Text("look"), UserPart.Image(imageRef)))),
        emptyList())

    private fun runtime() = RuntimeCoordinator().also {
        it.selectChat(ChatIdentity(identity.projectId, identity.chatId!!))
    }

    private fun transport(payload: String, contentType: String = "text/event-stream",
        observe: (Request) -> Unit = {}): CompatibleHttpTransport =
        CompatibleHttpTransport(OkHttpClient.Builder().addInterceptor { chain ->
            observe(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(payload.toResponseBody(contentType.toMediaType())).build()
        }.build())

    private fun sentBody(request: Request): String =
        Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()

    private val chatSse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"seen\"}," +
        "\"finish_reason\":null}]}\n\n" +
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

    @Test
    fun chatSendsResolvedImageAsDataUrl() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        var body = ""
        val model = CompatibleAgentModel(settings, runtime,
            transport(chatSse) { calls++; body = sentBody(it) },
            profiles = { profile() }, imageResolver = resolver)
        try {
            val response = model.complete(requestWithImage()) { }
            assertEquals(1, calls)
            assertEquals("seen", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            val content = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                .single().jsonObject.getValue("content").jsonArray
            assertEquals("look", content[0].jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals("data:image/png;base64,$pngBase64", content[1].jsonObject
                .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        } finally { runtime.finishRun(lease) }
    }

    @Test
    fun chatTextRequestWithVisionProfileStillDispatches() = runBlocking {
        val textRequest = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("attempt"), configuration,
            listOf(AgentMessage.User(MessageId("text-user"), listOf(UserPart.Text("hello")))), emptyList())
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        var body = ""
        val model = CompatibleAgentModel(settings, runtime,
            transport(chatSse) { calls++; body = sentBody(it) }, profiles = { profile() })
        try {
            val response = model.complete(textRequest) { }
            assertEquals(1, calls)
            assertEquals("seen", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            val content = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                .single().jsonObject.getValue("content")
            assertEquals("hello", content.jsonPrimitive.content)
        } finally { runtime.finishRun(lease) }
    }

    @Test
    fun chatRejectsMissingResolverBeforeHttp() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val model = CompatibleAgentModel(settings, runtime,
            transport(chatSse) { calls++ }, profiles = { profile() })
        try {
            try {
                model.complete(requestWithImage()) { }
                fail("Sent an unresolved image")
            } catch (failure: AgentModelFailure) {
                assertEquals(ModelFailureKind.CONTENT, failure.kind)
            }
            assertEquals(0, calls)
        } finally { runtime.finishRun(lease) }
    }

    @Test
    fun chatRejectsUnknownVisionCapabilityBeforeHttp() = runBlocking {
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val model = CompatibleAgentModel(settings, runtime,
            transport(chatSse) { calls++ }, profiles = { profile(vision = null) }, imageResolver = resolver)
        try {
            try {
                model.complete(requestWithImage()) { }
                fail("Sent an image with unknown vision capability")
            } catch (failure: AgentModelFailure) {
                assertEquals(ModelFailureKind.CONTENT, failure.kind)
            }
            assertEquals(0, calls)
        } finally { runtime.finishRun(lease) }
    }

    @Test
    fun chatRejectsOversizedImageBodyBeforeHttp() = runBlocking {
        val largeBytes = ByteArray(ModelProtocolImage.MAX_IMAGE_BYTES) { 7 }
        val largeRef = FrozenImageRef(AttachmentId("large-image"), "image/png",
            TextProjectFingerprint.bytes(largeBytes))
        val largeRequest = AgentModelRequest(identity, BranchId("main"), RequestAttemptId("attempt"), configuration,
            listOf(AgentMessage.User(MessageId("large-user"), listOf(
                UserPart.Text("x".repeat(800_000)), UserPart.Image(largeRef)))),
            emptyList())
        val largeResolver = object : AgentImageResolver {
            override suspend fun resolve(image: FrozenImageRef): ByteArray = largeBytes
        }
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val model = CompatibleAgentModel(settings, runtime,
            transport(chatSse) { calls++ }, profiles = { profile() }, imageResolver = largeResolver)
        try {
            try {
                model.complete(largeRequest) { }
                fail("Sent a request above the body budget")
            } catch (failure: AgentModelFailure) {
                assertEquals(ModelFailureKind.CONTEXT_OVERFLOW, failure.kind)
            }
            assertEquals(0, calls)
        } finally { runtime.finishRun(lease) }
    }

    @Test
    fun chatRejectsUnreadableImageAndDigestMismatchBeforeHttp() = runBlocking {
        val failing = object : AgentImageResolver {
            override suspend fun resolve(image: FrozenImageRef): ByteArray =
                throw IllegalStateException("unreadable")
        }
        val mismatched = object : AgentImageResolver {
            override suspend fun resolve(image: FrozenImageRef): ByteArray = byteArrayOf(1, 2, 3)
        }
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        try {
            for (candidate in listOf(failing, mismatched)) {
                val model = CompatibleAgentModel(settings, runtime,
                    transport(chatSse) { calls++ }, profiles = { profile() }, imageResolver = candidate)
                try {
                    model.complete(requestWithImage()) { }
                    fail("Sent a broken image")
                } catch (failure: AgentModelFailure) {
                    assertEquals(ModelFailureKind.CONTENT, failure.kind)
                }
            }
            assertEquals(0, calls)
        } finally { runtime.finishRun(lease) }
    }

    @Test
    fun anthropicProtocolSendsResolvedImageAsBase64Source() = runBlocking {
        protocol = NativeModelProtocol.ANTHROPIC_MESSAGES
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        var body = ""
        val payload = """{"type":"message","role":"assistant","stop_reason":"end_turn",
            "content":[{"type":"text","text":"seen"}]}"""
        val model = CompatibleAgentModel(settings, runtime,
            transport(payload, "application/json") { calls++; body = sentBody(it) },
            profiles = { profile() }, imageResolver = resolver)
        try {
            val response = model.complete(requestWithImage()) { }
            assertEquals(1, calls)
            assertEquals("seen", response.message.parts.filterIsInstance<AssistantPart.Text>().single().text)
            val content = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                .single().jsonObject.getValue("content").jsonArray
            assertEquals("look", content[0].jsonObject.getValue("text").jsonPrimitive.content)
            val source = content[1].jsonObject.getValue("source").jsonObject
            assertEquals("base64", source.getValue("type").jsonPrimitive.content)
            assertEquals("image/png", source.getValue("media_type").jsonPrimitive.content)
            assertEquals(pngBase64, source.getValue("data").jsonPrimitive.content)
        } finally { runtime.finishRun(lease) }
    }

    @Test
    fun anthropicProtocolRejectsMissingResolverBeforeHttp() = runBlocking {
        protocol = NativeModelProtocol.ANTHROPIC_MESSAGES
        val runtime = runtime(); val lease = runtime.beginRun(identity, RuntimeOwner.AGENT)
        var calls = 0
        val model = CompatibleAgentModel(settings, runtime,
            transport("{}", "application/json") { calls++ }, profiles = { profile() })
        try {
            try {
                model.complete(requestWithImage()) { }
                fail("Sent an unresolved image")
            } catch (failure: AgentModelFailure) {
                assertEquals(ModelFailureKind.CONTENT, failure.kind)
            }
            assertEquals(0, calls)
        } finally { runtime.finishRun(lease) }
    }
}
