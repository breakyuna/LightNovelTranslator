package com.breakyuna.noveltranslator.data.model

data class ModelPreset(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val defaultBaseUrl: String,
    val recommendedModels: List<String>,
    val defaultModel: String,
    val defaultInputPrice: Double = 0.0,
    val defaultOutputPrice: Double = 0.0,
    val currency: String = "USD",
    val defaultMaxContextTokens: Int = 32_768,
    val description: String
)

/** UI templates only; the endpoint model list is authoritative and can be refreshed in-app. */
object PresetModels {
    val presets = listOf(
        ModelPreset("openai", "OpenAI / ChatGPT", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", listOf("gpt-5.6", "gpt-5.6-luna", "gpt-5.4-mini"), "gpt-5.6-luna", 0.25, 2.0, description = "OpenAI 官方 API"),
        ModelPreset("deepseek", "DeepSeek", ProviderType.DEEPSEEK, "https://api.deepseek.com/v1", listOf("deepseek-v4-flash", "deepseek-v4", "deepseek-reasoner"), "deepseek-v4-flash", 0.14, 0.28, description = "DeepSeek 官方 API"),
        ModelPreset("gemini", "Google Gemini", ProviderType.GEMINI_DIRECT, "https://generativelanguage.googleapis.com", listOf("gemini-3.1-pro-preview", "gemini-3.6-flash-preview", "gemini-flash-latest"), "gemini-3.6-flash-preview", 0.3, 2.5, description = "Gemini 原生 API"),
        ModelPreset("anthropic", "Anthropic Claude", ProviderType.ANTHROPIC_CLAUDE, "https://api.anthropic.com/v1", listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5-20251001"), "claude-sonnet-4-6", 3.0, 15.0, description = "Anthropic 原生 Messages API"),
        ModelPreset("xai", "xAI / Grok", ProviderType.XAI, "https://api.x.ai/v1", listOf("grok-4.20", "grok-4.1-fast", "grok-4"), "grok-4.1-fast", description = "xAI OpenAI-compatible API"),
        ModelPreset("moonshot", "Moonshot / Kimi", ProviderType.MOONSHOT, "https://api.moonshot.ai/v1", listOf("kimi-k2.5", "kimi-k2-thinking", "moonshot-v1-auto"), "kimi-k2.5", description = "Moonshot 官方兼容端点"),
        ModelPreset("minimax", "MiniMax", ProviderType.MINIMAX, "https://api.minimax.io/v1", listOf("MiniMax-M3", "MiniMax-M2.5", "MiniMax-Text-01"), "MiniMax-M3", description = "MiniMax 官方兼容端点"),
        ModelPreset("openrouter", "OpenRouter", ProviderType.OPENROUTER, "https://openrouter.ai/api/v1", listOf("openai/gpt-5.6-luna", "anthropic/claude-sonnet-4.6", "google/gemini-3.6-flash-preview", "deepseek/deepseek-v4-flash"), "openai/gpt-5.6-luna", description = "多模型聚合端点"),
        ModelPreset("qwen", "Alibaba Cloud / Qwen", ProviderType.QWEN_DASHSCOPE, "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen3.5-plus", "qwen3-max", "qwen3-coder-plus"), "qwen3.5-plus", currency = "CNY", description = "阿里云百炼兼容端点"),
        ModelPreset("zhipu", "Zhipu / GLM", ProviderType.ZHIPU_GLM, "https://open.bigmodel.cn/api/paas/v4", listOf("glm-5", "glm-4.7", "glm-4.5-flash"), "glm-4.5-flash", currency = "CNY", description = "智谱 OpenAI-compatible API"),
        ModelPreset("siliconflow", "SiliconFlow", ProviderType.SILICONFLOW, "https://api.siliconflow.cn/v1", listOf("deepseek-ai/DeepSeek-V4", "Qwen/Qwen3.5-397B-A17B", "Pro/zai-org/GLM-5"), "deepseek-ai/DeepSeek-V4", currency = "CNY", description = "硅基流动聚合端点"),
        ModelPreset("ollama", "Ollama / Local", ProviderType.OLLAMA_LOCAL, "http://localhost:11434/v1", listOf("qwen3:8b", "qwen3:14b", "gemma3:12b", "llama3.3:70b"), "qwen3:8b", 0.0, 0.0, defaultMaxContextTokens = 16_384, description = "本机或局域网 Ollama"),
        ModelPreset("openai_compatible", "自定义 OpenAI 兼容", ProviderType.OPENAI_COMPATIBLE, "https://your-endpoint.example/v1", emptyList(), "", description = "任何 OpenAI Chat Completions 兼容端点"),
        ModelPreset("anthropic_compatible", "自定义 Anthropic 兼容", ProviderType.ANTHROPIC_CLAUDE, "https://your-endpoint.example/v1", emptyList(), "", description = "任何 Anthropic Messages 兼容端点")
    )
}
