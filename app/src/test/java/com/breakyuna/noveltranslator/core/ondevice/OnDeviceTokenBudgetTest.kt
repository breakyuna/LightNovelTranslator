package com.breakyuna.noveltranslator.core.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceTokenBudgetTest {

    @Test
    fun `estimate counts cjk as one token per char`() {
        assertEquals(4, OnDeviceTokenBudget.estimateTokens("你好世界"))
        assertEquals(0, OnDeviceTokenBudget.estimateTokens(""))
    }

    @Test
    fun `estimate counts ascii as four chars per token`() {
        // 8 ascii chars -> ceil(8/4) = 2 tokens
        assertEquals(2, OnDeviceTokenBudget.estimateTokens("abcdefgh"))
        // 5 ascii chars -> ceil(5/4) = 2 tokens
        assertEquals(2, OnDeviceTokenBudget.estimateTokens("abcde"))
    }

    @Test
    fun `estimate mixes cjk and ascii`() {
        // 2 cjk + 8 ascii -> 2 + 2 = 4
        assertEquals(4, OnDeviceTokenBudget.estimateTokens("你好abcdefgh"))
    }

    @Test
    fun `short text stays single chunk`() {
        val text = "short chapter text"
        assertEquals(listOf(text), OnDeviceTokenBudget.splitForTokenLimit(text, 100))
    }

    @Test
    fun `blank text yields no chunks`() {
        assertTrue(OnDeviceTokenBudget.splitForTokenLimit("   \n  ", 100).isEmpty())
    }

    @Test
    fun `long text splits at line boundaries without losing content`() {
        val lines = (1..200).map { "这是第 $it 行段落内容，包含一些文字。" }
        val text = lines.joinToString("\n")
        val chunks = OnDeviceTokenBudget.splitForTokenLimit(text, 200)
        assertTrue("expected multiple chunks, got ${chunks.size}", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(
                "chunk over limit: ${OnDeviceTokenBudget.estimateTokens(chunk)}",
                OnDeviceTokenBudget.estimateTokens(chunk) <= 200
            )
        }
        val rejoined = chunks.joinToString("")
        assertEquals(text, rejoined)
    }

    @Test
    fun `single overlong line splits by sentence and preserves content`() {
        val sentence = "这是一个很长的句子用来测试按句切分。"
        val line = sentence.repeat(30)
        val chunks = OnDeviceTokenBudget.splitForTokenLimit(line, 100)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(OnDeviceTokenBudget.estimateTokens(chunk) <= 100)
        }
        assertEquals(line, chunks.joinToString(""))
    }

    @Test
    fun `overlong sentence without punctuation falls back to char split`() {
        val line = "无标点长句".repeat(80) // 320 chars, 320 tokens
        val chunks = OnDeviceTokenBudget.splitForTokenLimit(line, 100)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(OnDeviceTokenBudget.estimateTokens(chunk) <= 100)
        }
        assertEquals(line, chunks.joinToString(""))
    }

    @Test
    fun `ascii paragraph splits by line and respects limit`() {
        val lines = (1..120).map { "line $it: the quick brown fox jumps over the lazy dog" }
        val text = lines.joinToString("\n")
        val chunks = OnDeviceTokenBudget.splitForTokenLimit(text, 100)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(OnDeviceTokenBudget.estimateTokens(chunk) <= 100)
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test
    fun `mixed long line splits by sentence then char as needed`() {
        val line = buildString {
            repeat(10) { append("Sentence number $it with words. ") }
            append("无标点的超长尾巴".repeat(40))
        }
        val chunks = OnDeviceTokenBudget.splitForTokenLimit(line, 100)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(OnDeviceTokenBudget.estimateTokens(chunk) <= 100)
        }
        assertEquals(line, chunks.joinToString(""))
    }

    @Test
    fun `split by sentence keeps trailing punctuation`() {
        val sentences = OnDeviceTokenBudget.splitBySentence("甲。乙！丙？丁")
        assertEquals(listOf("甲。", "乙！", "丙？", "丁"), sentences)
    }
}
