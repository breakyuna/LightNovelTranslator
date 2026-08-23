package com.example.core.llm

import kotlin.math.roundToInt

object TokenCalculator {

    /**
     * Approximate token counter when exact tokenizer is unavailable:
     * - English: ~1 token per 4 characters / ~0.75 tokens per word
     * - CJK: ~1.5 to 2 tokens per Chinese/Japanese/Korean character
     */
    fun estimateTokens(text: String): Long {
        if (text.isEmpty()) return 0
        var cjkCount = 0
        var nonCjkCharCount = 0

        for (c in text) {
            val ub = Character.UnicodeBlock.of(c)
            if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA ||
                ub == Character.UnicodeBlock.HANGUL_SYLLABLES
            ) {
                cjkCount++
            } else {
                nonCjkCharCount++
            }
        }

        val cjkTokens = (cjkCount * 1.6).toLong()
        val otherTokens = (nonCjkCharCount / 3.8).toLong()
        return maxOf(1L, cjkTokens + otherTokens)
    }

    /**
     * Calculate cost based on input/output pricing per million tokens
     */
    fun calculateCost(
        promptTokens: Long,
        completionTokens: Long,
        inputPricePerMillion: Double,
        outputPricePerMillion: Double
    ): Double {
        val inputCost = (promptTokens.toDouble() / 1_000_000.0) * inputPricePerMillion
        val outputCost = (completionTokens.toDouble() / 1_000_000.0) * outputPricePerMillion
        val total = inputCost + outputCost
        return (total * 10000.0).roundToInt() / 10000.0 // Round to 4 decimal places
    }

    fun formatCost(cost: Double, currency: String = "USD"): String {
        val symbol = if (currency.equals("CNY", ignoreCase = true) || currency.equals("RMB", ignoreCase = true)) "¥" else "$"
        return "$symbol${String.format("%.4f", cost)}"
    }

    /**
     * Dynamically calculate maximum usable body tokens per chunk
     * based on the provider's maxContextTokens, estimated overhead, and safe output token headroom.
     */
    fun calculateChunkBudget(
        maxContextTokens: Int,
        overheadEstimate: Long = 800L
    ): Long {
        val totalCtx = maxContextTokens.coerceAtLeast(4096).toLong()
        // Allocate up to 40% of context to target translation output, or at least 2000 tokens
        val outputBudget = minOf(4096L, totalCtx / 3)
        val availableInputTokens = totalCtx - outputBudget - overheadEstimate
        // CJK characters take ~1.6 tokens each. Aim for ~1500 to 3000 tokens per chunk for best translation coherence
        return maxOf(600L, minOf(availableInputTokens, 3000L))
    }

    fun formatTokenCount(tokens: Long): String {
        return when {
            tokens >= 1_000_000 -> String.format("%.2fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }
}
