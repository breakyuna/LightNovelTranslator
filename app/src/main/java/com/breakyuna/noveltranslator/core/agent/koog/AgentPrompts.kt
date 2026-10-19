package com.breakyuna.noveltranslator.core.agent.koog

import com.breakyuna.noveltranslator.core.agent.AgentToolSpec

/** system prompt 资源加载器；Android 侧由 assets 实现，测试可由字符串提供。 */
interface AgentPromptAssetLoader {
    fun loadAsset(fileName: String): String?
}

object AgentPrompts {

    /** 默认资源路径，对应 app/src/main/assets/agent/system-prompt.md。 */
    const val SYSTEM_PROMPT_ASSET = "agent/system-prompt.md"

    /**
     * 资源缺失时的兜底指令。只定义角色与边界，工具协议与目录仍由代码追加，
     * 因此资源损坏不会导致协议缺失。
     */
    const val DEFAULT_BASE_PROMPT = """你是 LightNovelTranslator 的内置对话助手，服务于轻小说的本地阅读与 LLM 翻译工作台。

职责边界：
- 你可以通过工具查询书架、书籍状态、翻译任务、术语扫描结果与系统日志，并在用户确认后执行翻译相关操作。
- 所有事实必须来自工具回执或用户输入，不得编造书籍、章节、任务状态或 token 用量。
- 回复使用简洁中文；先给结论，再给必要的细节；不要输出思考过程。
- 涉及付费请求或数据修改的操作，先说明计划并等待用户确认。"""

    /**
     * 组装完整 system prompt：基础指令 + 运行环境 + 工具协议 + 工具目录。
     * 协议与目录由代码生成，保证与解析器实现不会漂移。
     */
    fun buildSystemPrompt(
        basePrompt: String,
        specs: List<AgentToolSpec>,
        maxToolRounds: Int,
        runtimeContext: String
    ): String = buildString {
        appendLine(basePrompt.trim())
        appendLine()
        appendLine("## 当前运行环境")
        appendLine(runtimeContext.trim())
        appendLine()
        append(AgentToolProtocol.renderProtocolInstructions(maxToolRounds))
        appendLine()
        appendLine()
        appendLine("## 可用工具目录")
        append(AgentToolProtocol.renderToolCatalog(specs))
    }.trim()

    /**
     * 读取基础指令：优先资源，其次调用方注入，最后兜底常量。
     * 资源存在但为空视为损坏，退到兜底而不是发送空指令。
     */
    fun resolveBasePrompt(assetLoader: AgentPromptAssetLoader?, override: String?): String =
        override?.takeIf { it.isNotBlank() }
            ?: assetLoader?.loadAsset(SYSTEM_PROMPT_ASSET)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_BASE_PROMPT
}
