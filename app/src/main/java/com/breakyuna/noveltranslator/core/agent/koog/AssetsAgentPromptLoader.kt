package com.breakyuna.noveltranslator.core.agent.koog

import android.content.Context
import com.breakyuna.noveltranslator.core.logger.SystemLogger
import java.io.IOException

/**
 * 从 Android assets 加载 Agent 资源（如 system prompt 基础指令）。
 *
 * 资源缺失或读取失败时返回 null，由 [AgentPrompts.resolveBasePrompt] 退到兜底指令，
 * 不因资源损坏阻断整个 Agent 层启动。
 */
class AssetsAgentPromptLoader(private val context: Context) : AgentPromptAssetLoader {

    override fun loadAsset(fileName: String): String? = try {
        context.assets.open(fileName).use { input -> input.readBytes().toString(Charsets.UTF_8) }
    } catch (error: IOException) {
        SystemLogger.warn(TAG, "Agent 资源加载失败: $fileName (${error.localizedMessage})")
        null
    }

    private companion object {
        const val TAG = "AGENT_CHAT"
    }
}
