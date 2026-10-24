package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*

/**
 * Bounded instruction builder for one automatic-context summary request. The actual provider
 * call belongs to the app's [AgentSummaryPort] implementation; this object only renders text.
 */
object AgentCompactionPrompt {
    const val MAX_PROMPT_CHARS = 48_000
    private const val MAX_PRIOR_SUMMARY_CHARS = 16_384
    private const val MAX_MESSAGE_CHARS = 4_000
    private const val TRUNCATION = "\n[truncated]"

    fun build(request: AgentSummaryRequest): String {
        val instructions = buildString {
            append("Summarize the archived earlier part of this translation-project Agent conversation. ")
            append("The result replaces only model context; visible history is unchanged.\n")
            append("Preserve:\n")
            append("- user goals and explicit constraints;\n")
            append("- decisions already made and their reasons;\n")
            append("- verified tool and project facts, including stable item IDs;\n")
            append("- open tasks, pending questions and uncertainties.\n")
            append("Do not include API keys, passwords, tokens, Authorization headers or other credentials.\n")
            append("Do not request, propose or authorize any new action, tool call or approval. ")
            append("This summary is descriptive only.\n")
            append("Do not invent facts. State uncertainty explicitly. ")
            append("Maximum summary characters: ${request.maxSummaryChars}.")
        }
        val prior = request.priorSummary.trim()
        val priorBlock = if (prior.isEmpty()) "" else buildString {
            append("\n\n[Previous committed summary - merge it, keeping earlier goals and constraints]\n")
            append(bound(prior, MAX_PRIOR_SUMMARY_CHARS))
        }
        val archiveHeader = "\n\n[Archived conversation]\n"
        val budget = (MAX_PROMPT_CHARS - instructions.length - priorBlock.length - archiveHeader.length - 64)
            .coerceAtLeast(512)
        val result = instructions + priorBlock + archiveHeader + renderArchive(request.archivedMessages, budget)
        return bound(result, MAX_PROMPT_CHARS)
    }

    private fun renderArchive(messages: List<AgentMessage>, budget: Int): String {
        val rendered = messages.map { renderMessage(it) }
        val total = rendered.sumOf { it.length + 2 }
        if (total <= budget) return rendered.joinToString("\n\n")
        val usable = (budget - 128).coerceAtLeast(1)
        val headBudget = usable / 2
        val tailBudget = usable - headBudget
        val head = StringBuilder()
        var index = 0
        while (index < rendered.size) {
            val piece = rendered[index]
            val extra = if (head.isEmpty()) piece.length else piece.length + 2
            if (head.length + extra > headBudget) break
            if (head.isNotEmpty()) head.append("\n\n")
            head.append(piece)
            index++
        }
        val tail = ArrayDeque<String>()
        var tailChars = 0
        var last = rendered.lastIndex
        while (last >= index) {
            val piece = rendered[last]
            val extra = if (tail.isEmpty()) piece.length else piece.length + 2
            if (tailChars + extra > tailBudget) break
            tail.addFirst(piece)
            tailChars += extra
            last--
        }
        val omitted = last - index + 1
        return buildString {
            append(head)
            if (omitted > 0) {
                if (isNotEmpty()) append("\n\n")
                append("[$omitted archived message(s) omitted to keep this prompt bounded]")
            }
            if (tail.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(tail.joinToString("\n\n"))
            }
        }
    }

    private fun renderMessage(message: AgentMessage): String {
        val text = when (message) {
            is AgentMessage.System -> "[system] " + message.text
            is AgentMessage.User -> "[user] " + message.parts.joinToString("\n") { part ->
                when (part) {
                    is UserPart.Text -> part.text
                    is UserPart.Image -> "[image attachment]"
                }
            }
            is AgentMessage.Assistant -> buildString {
                append("[assistant]")
                message.parts.forEach { part ->
                    when (part) {
                        is AssistantPart.Text -> if (part.text.isNotEmpty()) append("\n").append(part.text)
                        // Private reasoning is not needed to preserve decisions or facts.
                        is AssistantPart.Thinking -> Unit
                        is AssistantPart.ToolCall -> append("\n[tool call ")
                            .append(part.call.name).append("] ").append(part.call.arguments.encode())
                    }
                }
            }
            is AgentMessage.ToolResult -> "[tool result " + message.toolName +
                (if (message.isError) " error]" else "]") + " " + message.result.encode()
        }
        return bound(text, MAX_MESSAGE_CHARS)
    }

    private fun bound(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        var end = (maxChars - TRUNCATION.length).coerceAtLeast(0)
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end) + TRUNCATION
    }
}
