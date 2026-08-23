package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ProviderType(val displayName: String) {
    OPENAI_COMPATIBLE("OpenAI Compatible"),
    ANTHROPIC_CLAUDE("Anthropic Claude"),
    GEMINI_DIRECT("Google Gemini"),
    DEEPSEEK("DeepSeek API"),
    SILICONFLOW("SiliconFlow (硅基流动)"),
    OPENROUTER("OpenRouter"),
    OLLAMA_LOCAL("Ollama / Local LLM"),
    ZHIPU_GLM("Zhipu AI (智谱GLM)"),
    QWEN_DASHSCOPE("Aliyun DashScope (通义千问)"),
    MOONSHOT("Moonshot AI (Kimi)")
}

@Entity(tableName = "api_providers")
data class ApiProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val providerType: ProviderType,
    val baseUrl: String,
    val apiKey: String,
    val selectedModel: String,
    val inputPricePerMillion: Double = 0.5, // e.g. $0.5 / 1M tokens or CNY
    val outputPricePerMillion: Double = 1.5, // e.g. $1.5 / 1M tokens
    val currency: String = "USD", // "USD", "CNY"
    val temperature: Float = 0.4f,
    val maxContextTokens: Int = 8192,
    val isDefault: Boolean = false,
    val customHeadersJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)
