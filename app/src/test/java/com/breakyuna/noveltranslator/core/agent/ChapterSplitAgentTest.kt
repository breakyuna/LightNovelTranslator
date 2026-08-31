package com.breakyuna.noveltranslator.core.agent

import com.breakyuna.noveltranslator.core.llm.LlmGateway
import com.breakyuna.noveltranslator.core.llm.LlmRequest
import com.breakyuna.noveltranslator.core.llm.LlmResult
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterSplitAgentTest {

    private val provider = ApiProviderEntity(
        id = 1L,
        name = "TestProvider",
        providerType = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "dummy",
        selectedModel = "gpt-4o"
    )

    private fun createMockGateway(handler: suspend (LlmRequest) -> LlmResult): LlmGateway {
        return object : LlmGateway {
            override suspend fun executeCompletion(request: LlmRequest): LlmResult = handler(request)
            override suspend fun fetchAvailableModels(provider: ApiProviderEntity): Result<List<String>> =
                Result.success(listOf("gpt-4o"))
        }
    }

    @Test
    fun parseExtractedRegexFromJsonResponse() {
        val agent = ChapterSplitAgent(createMockGateway { error("unused") })

        val json = """
            {
              "regex": "^\\s*第[0-9]+章.*",
              "sampleHeadings": ["第一章", "第二章"],
              "confidence": "HIGH"
            }
        """.trimIndent()

        val parsed = agent.parseExtractedRegex(json)
        assertEquals("^\\s*第[0-9]+章.*", parsed)
    }

    @Test
    fun parseExtractedRegexFromMarkdownFencedJson() {
        val agent = ChapterSplitAgent(createMockGateway { error("unused") })

        val markdownJson = """
            ```json
            {
              "regex": "(^Chapter\\s+\\d+.*)"
            }
            ```
        """.trimIndent()

        val parsed = agent.parseExtractedRegex(markdownJson)
        assertEquals("(^Chapter\\s+\\d+.*)", parsed)
    }

    @Test
    fun analyzeAndSplitUsesExtractedRegexToSplitFullText() = runBlocking {
        val mockLlmGateway = createMockGateway {
            LlmResult(
                isSuccess = true,
                text = """
                    {
                      "regex": "(^Episode\\s+\\d+:.*)",
                      "sampleHeadings": ["Episode 1: The Start", "Episode 2: The Middle"]
                    }
                """.trimIndent(),
                promptTokens = 100,
                completionTokens = 50,
                durationMs = 200
            )
        }

        val agent = ChapterSplitAgent(mockLlmGateway)
        val text = """
            Episode 1: The Start
            This is the first episode content.
            
            Episode 2: The Middle
            This is the second episode content.
            
            Episode 3: The End
            This is the finale.
        """.trimIndent()

        val chapters = agent.analyzeAndSplit(text, provider)
        assertEquals(3, chapters.size)
        assertEquals("Episode 1: The Start", chapters[0].title)
        assertEquals("Episode 2: The Middle", chapters[1].title)
        assertEquals("Episode 3: The End", chapters[2].title)
    }

    @Test
    fun fallbackWhenAiReturnsInvalidRegex() = runBlocking {
        val mockLlmGateway = createMockGateway {
            LlmResult(
                isSuccess = true,
                text = "invalid output with no regex",
                promptTokens = 100,
                completionTokens = 50,
                durationMs = 200
            )
        }

        val agent = ChapterSplitAgent(mockLlmGateway)
        val text = """
            第一章 启程
            这是第一章的正文。
            
            第二章 冒险
            这是第二章的正文。
        """.trimIndent()

        val chapters = agent.analyzeAndSplit(text, provider)
        assertTrue(chapters.size >= 2)
        assertEquals("第一章 启程", chapters[0].title)
        assertEquals("第二章 冒险", chapters[1].title)
    }
}

