package com.breakyuna.noveltranslator.core.llm


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
        return inputCost + outputCost
    }

    fun formatCost(cost: Double, currency: String = "USD"): String {
        val normalized = currency.trim().uppercase()
        val amount = String.format("%.4f", cost)
        return when (normalized) {
            "USD" -> "\$$amount"
            "CNY", "RMB" -> "¥$amount"
            "", "UNKNOWN", "MIXED" -> "$amount (${normalized.ifBlank { "?" }})"
            else -> "$amount $normalized"
        }
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
        // Reserve enough output room for languages whose translation expands substantially.
        val outputBudget = minOf(16_384L, maxOf(2_048L, totalCtx / 2))
        val availableInputTokens = totalCtx - outputBudget - overheadEstimate
        // Long-context models should translate a normal chapter in one request when possible.
        return maxOf(600L, minOf(availableInputTokens, 12_000L))
    }

    fun formatTokenCount(tokens: Long): String {
        return when {
            tokens >= 1_000_000 -> String.format("%.2fM", tokens / 1_000_000.0)
            tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000.0)
            else -> tokens.toString()
        }
    }
}
