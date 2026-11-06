package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.storage.NativeAgentStore
import kotlinx.serialization.json.*

internal class NativeAgentChatService(private val agent: NativeAgentStore, private val projects: TextProjectPort,
    private val proposals: NativeAgentBusinessProposals) {
    suspend fun apply(ref: LocalProjectRef, sourceChat: ChatId, proposal: NativeAgentBusinessProposal): JsonObject {
        val payload = proposal.payload
        require(agent.currentChat(ref) == sourceChat)
        val operation = payload.getValue("operation").jsonPrimitive.content
        val target = when (operation) {
            "new" -> agent.newChat(ref)
            "select" -> ChatId(payload.getValue("target_chat").jsonPrimitive.content).also { agent.selectChat(ref, it) }
            "branch", "context" -> {
                val preview = AgentBranchPreview(ref, sourceChat, MessageId(payload.getValue("message_id").jsonPrimitive.content),
                    ObjectFingerprint(payload.getValue("source_fingerprint").jsonPrimitive.content),
                    payload.getValue("messages").jsonPrimitive.int, payload.getValue("editable").jsonPrimitive.boolean)
                if (operation == "context") {
                    agent.saveReviewedContext(preview, payload.getValue("summary").jsonPrimitive.content)
                    sourceChat
                } else agent.createBranch(preview, CommandId(proposal.id),
                    payload["replacement"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
            }
            else -> error("Unknown chat action")
        }
        return buildJsonObject { put("chat", target.value) }
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> = listOf(
        RegisteredAgentTool("agent_chats", "List chat identities in the bound project; selection changes require prepare_chat_management.",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                require(run == lease.identity)
                JsonObjectValue.parse(buildJsonObject {
                    put("current", run.chatId!!.value); put("chats", JsonArray(agent.chats(ref).map { JsonPrimitive(it.value) }))
                    put("context_summary", agent.contextSummary(ref, requireNotNull(run.chatId)))
                }.toString(), JsonInputLimits(64 * 1024, 4))
            }),
        RegisteredAgentTool("prepare_chat_management", "Prepare new/select/branch/context for explicit approval. " +
            "select requires target_chat from agent_chats. branch/context require a closed message_id in the CURRENT chat's durable history. " +
            "branch may include replacement text only for a plain-text editable target and never copies execution grants. " +
            "context requires summary (max 16384 characters) and archives only the immutable closed prefix through message_id; the new tool tail is retained. " +
            "No message is deleted and no model request is started. new/select preserve composer drafts.",
            ToolParameterSchema(mapOf("operation" to ToolParameter.Text(1, 16), "target_chat" to ToolParameter.Text(1, 128, required = false),
                "message_id" to ToolParameter.Text(1, 128, required = false), "replacement" to ToolParameter.Text(1, 8000, required = false),
                "summary" to ToolParameter.Text(1, 16_384, required = false))), ToolEffect.APPROVAL_PREPARATION,
            AgentToolHandler { run, args ->
                require(run == lease.identity)
                val chat = requireNotNull(run.chatId); val operation = requireNotNull(args.text("operation"))
                require(operation in setOf("new", "select", "branch", "context"))
                val target = args.text("target_chat"); val message = args.text("message_id")
                val replacement = args.text("replacement"); val summary = args.text("summary")
                val preview = when (operation) {
                    "new" -> { require(target == null && message == null && replacement == null && summary == null); null }
                    "select" -> { require(message == null && replacement == null && summary == null && target != null)
                        require(ChatId(target) in agent.chats(ref)); null }
                    else -> {
                        require(target == null && message != null)
                        require(if (operation == "context") summary != null && summary.isNotBlank() && replacement == null else summary == null)
                        agent.prepareBranchForAgent(lease, ref, chat, MessageId(message)).also {
                            require(replacement == null || it.editable && replacement.isNotBlank())
                        }
                    }
                }
                val proposal = proposals.prepare(ref, run, projects.open(ref).revision, "chat_management", buildJsonObject {
                    put("operation", operation); put("target_chat", target); put("replacement", replacement)
                    put("summary", summary); put("previous_summary", agent.contextSummary(ref, chat))
                    preview?.let { put("message_id", it.message.value); put("source_fingerprint", it.fingerprint.sha256)
                        put("messages", it.messages); put("editable", it.editable)
                        put("target_text", agent.messageDetail(ref, chat, it.message.value).text) }
                })
                JsonObjectValue.parse(buildJsonObject {
                    put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
                }.toString(), JsonInputLimits(4096, 4))
            }),
    )
}
