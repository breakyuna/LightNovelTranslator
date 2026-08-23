package com.example.data.model

data class ModelPreset(
    val id: String,
    val name: String,
    val providerType: ProviderType,
    val defaultBaseUrl: String,
    val recommendedModels: List<String>,
    val defaultModel: String,
    val defaultInputPrice: Double,
    val defaultOutputPrice: Double,
    val currency: String = "USD",
    val defaultMaxContextTokens: Int = 8192,
    val description: String
)

object PresetModels {
    val presets = listOf(
        ModelPreset(
            id = "deepseek",
            name = "DeepSeek API",
            providerType = ProviderType.DEEPSEEK,
            defaultBaseUrl = "https://api.deepseek.com/v1",
            recommendedModels = listOf("deepseek-chat", "deepseek-reasoner"),
            defaultModel = "deepseek-chat",
            defaultInputPrice = 0.14,
            defaultOutputPrice = 0.28,
            currency = "USD",
            defaultMaxContextTokens = 32_768,
            description = "High quality novel & literary translation with low token pricing."
        ),
        ModelPreset(
            id = "gemini",
            name = "Google Gemini Direct",
            providerType = ProviderType.GEMINI_DIRECT,
            defaultBaseUrl = "https://generativelanguage.googleapis.com",
            recommendedModels = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-flash-latest"),
            defaultModel = "gemini-2.5-flash",
            defaultInputPrice = 0.30,
            defaultOutputPrice = 2.50,
            currency = "USD",
            defaultMaxContextTokens = 32_768,
            description = "High speed, large context window, excellent nuanced multilingual comprehension."
        ),
        ModelPreset(
            id = "openai",
            name = "OpenAI API",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://api.openai.com/v1",
            recommendedModels = listOf("gpt-5.2", "gpt-5-mini", "gpt-4.1", "gpt-4.1-mini"),
            defaultModel = "gpt-5-mini",
            defaultInputPrice = 0.25,
            defaultOutputPrice = 2.00,
            currency = "USD",
            defaultMaxContextTokens = 32_768,
            description = "Industry benchmark for conversational clarity and prompt adhering."
        ),
        ModelPreset(
            id = "anthropic",
            name = "Anthropic Claude",
            providerType = ProviderType.ANTHROPIC_CLAUDE,
            defaultBaseUrl = "https://api.anthropic.com/v1",
            recommendedModels = listOf("claude-sonnet-4-6", "claude-opus-4-6", "claude-haiku-4-5-20251001"),
            defaultModel = "claude-haiku-4-5-20251001",
            defaultInputPrice = 1.00,
            defaultOutputPrice = 5.00,
            currency = "USD",
            defaultMaxContextTokens = 32_768,
            description = "Superior literary prose, natural sentence flow, and character tone preservation."
        ),
        ModelPreset(
            id = "siliconflow",
            name = "SiliconFlow (硅基流动)",
            providerType = ProviderType.SILICONFLOW,
            defaultBaseUrl = "https://api.siliconflow.cn/v1",
            recommendedModels = listOf("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen2.5-72B-Instruct", "THUDM/glm-4-9b-chat"),
            defaultModel = "deepseek-ai/DeepSeek-V3",
            defaultInputPrice = 2.0,
            defaultOutputPrice = 8.0,
            currency = "CNY",
            defaultMaxContextTokens = 32_768,
            description = "Fast Domestic China hosted models with low latency."
        ),
        ModelPreset(
            id = "openrouter",
            name = "OpenRouter",
            providerType = ProviderType.OPENROUTER,
            defaultBaseUrl = "https://openrouter.ai/api/v1",
            recommendedModels = listOf("deepseek/deepseek-chat", "anthropic/claude-3.5-sonnet", "openai/gpt-4o-mini", "meta-llama/llama-3.3-70b-instruct"),
            defaultModel = "deepseek/deepseek-chat",
            defaultInputPrice = 0.14,
            defaultOutputPrice = 0.28,
            currency = "USD",
            defaultMaxContextTokens = 32_768,
            description = "Universal aggregator with access to hundreds of AI models."
        ),
        ModelPreset(
            id = "qwen",
            name = "Aliyun DashScope (通义千问)",
            providerType = ProviderType.QWEN_DASHSCOPE,
            defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            recommendedModels = listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen2.5-72b-instruct"),
            defaultModel = "qwen-plus",
            defaultInputPrice = 0.8,
            defaultOutputPrice = 2.0,
            currency = "CNY",
            defaultMaxContextTokens = 32_768,
            description = "Specialized Chinese-English and East Asian language capabilities."
        ),
        ModelPreset(
            id = "zhipu",
            name = "Zhipu AI (智谱GLM)",
            providerType = ProviderType.ZHIPU_GLM,
            defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
            recommendedModels = listOf("glm-4-flash", "glm-4-plus", "glm-4-air"),
            defaultModel = "glm-4-flash",
            defaultInputPrice = 0.1,
            defaultOutputPrice = 0.1,
            currency = "CNY",
            defaultMaxContextTokens = 32_768,
            description = "Fast and economical with generous free tier options."
        ),
        ModelPreset(
            id = "ollama",
            name = "Ollama (Local / LAN)",
            providerType = ProviderType.OLLAMA_LOCAL,
            defaultBaseUrl = "http://localhost:11434/v1",
            recommendedModels = listOf("qwen2.5:7b", "qwen2.5:14b", "deepseek-r1:8b", "llama3.1:8b", "mistral:7b"),
            defaultModel = "qwen2.5:7b",
            defaultInputPrice = 0.0,
            defaultOutputPrice = 0.0,
            currency = "USD",
            defaultMaxContextTokens = 16_384,
            description = "Offline 100% private local LLM. Use localhost or your LAN PC IP:11434."
        ),
        ModelPreset(
            id = "custom",
            name = "Custom OpenAI Compatible Endpoint",
            providerType = ProviderType.OPENAI_COMPATIBLE,
            defaultBaseUrl = "https://your-custom-endpoint.com/v1",
            recommendedModels = listOf("default-model"),
            defaultModel = "default-model",
            defaultInputPrice = 0.5,
            defaultOutputPrice = 1.5,
            currency = "USD",
            defaultMaxContextTokens = 16_384,
            description = "Connect any custom OpenAI or proxy API gateway."
        )
    )
}
