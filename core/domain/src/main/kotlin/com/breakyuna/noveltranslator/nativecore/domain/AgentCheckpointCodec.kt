package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

/**
 * Lossless JSON v1 encoding for [CheckpointEnvelope]. Message bodies use [AgentMessageCodec];
 * pending calls, compaction summary, accepted commands and token usage are explicit fields.
 * Unknown top-level/nested keys, versions or stage names are rejected instead of guessed.
 */
object AgentCheckpointCodec {
    const val VERSION = 1
    const val MAX_ENCODED_BYTES = 4 * 1024 * 1024

    private val ROOT_KEYS = setOf(
        "version", "scope", "run", "branch", "strategy_version", "stage", "messages",
        "pending_tool_calls", "summary", "accepted_commands", "usage",
    )
    private val SCOPE_KEYS = setOf("project", "instance", "generation")
    private val RUN_KEYS = setOf("project", "chat", "run", "command")
    private val SUMMARY_KEYS = setOf("up_to", "text", "version")
    private val USAGE_KEYS = setOf("input", "cache_read", "cache_write", "thinking", "output")
    private val MEASUREMENT_KEYS = setOf("tokens", "source")

    fun encode(envelope: CheckpointEnvelope): String {
        val ref = requireNotNull(envelope.projectRef) {
            "A durable Agent checkpoint requires a project instance/generation scope"
        }
        require(ref.projectId == envelope.run.projectId) { "Checkpoint scope belongs to another project" }
        // Never silently downgrade a future envelope: only the version this codec understands may
        // be persisted, and decode returns the same value rather than a hard-coded constant.
        require(envelope.version == VERSION) { "Unsupported Agent checkpoint version" }
        val text = buildJsonObject {
            put("version", VERSION)
            put("scope", buildJsonObject {
                put("project", ref.projectId.value)
                put("instance", ref.instanceId.value)
                put("generation", ref.generation.value)
            })
            put("run", buildJsonObject {
                put("project", envelope.run.projectId.value)
                put("chat", requireNotNull(envelope.run.chatId).value)
                put("run", envelope.run.runId.value)
                put("command", envelope.run.commandId.value)
            })
            put("branch", envelope.branchId.value)
            put("strategy_version", envelope.strategyVersion)
            put("stage", envelope.stage.name)
            put("messages", JsonArray(envelope.messages.map { JsonPrimitive(AgentMessageCodec.encode(it)) }))
            put("pending_tool_calls", JsonArray(envelope.pendingToolCalls.map { JsonPrimitive(it.value) }))
            put("summary", envelope.summary?.let { summary ->
                buildJsonObject {
                    put("up_to", summary.upToMessageId.value)
                    put("text", summary.text)
                    put("version", summary.version)
                }
            } ?: JsonNull)
            put("accepted_commands", JsonArray(envelope.acceptedCommands.map { JsonPrimitive(it.value) }))
            put("usage", buildJsonObject {
                put("input", measurement(envelope.usage.input))
                put("cache_read", measurement(envelope.usage.cacheRead))
                put("cache_write", measurement(envelope.usage.cacheWrite))
                put("thinking", measurement(envelope.usage.thinking))
                put("output", measurement(envelope.usage.output))
            })
        }.toString()
        if (TextProjectFingerprint.utf8(text).size > MAX_ENCODED_BYTES) {
            throw AgentCheckpointLimitException("Agent checkpoint exceeds the encoded size limit")
        }
        return text
    }

    fun decode(text: String): CheckpointEnvelope {
        if (TextProjectFingerprint.utf8(text).size > MAX_ENCODED_BYTES) {
            throw AgentCheckpointLimitException("Agent checkpoint exceeds the encoded size limit")
        }
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_ENCODED_BYTES, 24)).json
        root.requireKeys(ROOT_KEYS)
        require(number(root, "version") == VERSION.toLong()) { "Unsupported Agent checkpoint version" }

        val scope = objectAt(root, "scope")
        scope.requireKeys(SCOPE_KEYS)
        val ref = LocalProjectRef(
            ProjectId(string(scope, "project")),
            ProjectInstanceId(string(scope, "instance")),
            RecoveryGeneration(number(scope, "generation")),
        )

        val run = objectAt(root, "run")
        run.requireKeys(RUN_KEYS)
        val identity = RunIdentity(
            ProjectId(string(run, "project")),
            ChatId(string(run, "chat")),
            RunId(string(run, "run")),
            CommandId(string(run, "command")),
        )

        val stage = CheckpointStage.entries.firstOrNull { it.name == string(root, "stage") }
            ?: throw IllegalArgumentException("Unsupported Agent checkpoint stage")
        val messages = array(root, "messages").map { element ->
            require(element is JsonPrimitive && element.isString) { "Invalid Agent checkpoint message" }
            AgentMessageCodec.decode(element.content)
        }
        val pending = array(root, "pending_tool_calls").map { element ->
            require(element is JsonPrimitive && element.isString) { "Invalid pending tool call" }
            ToolCallId(element.content)
        }
        val summary = when (val value = root["summary"]) {
            null, JsonNull -> null
            is JsonObject -> {
                value.requireKeys(SUMMARY_KEYS)
                CompactionSummary(
                    MessageId(string(value, "up_to")),
                    string(value, "text"),
                    bounded(value, "version", Int.MAX_VALUE),
                )
            }
            else -> throw IllegalArgumentException("Invalid compaction summary")
        }
        val accepted = array(root, "accepted_commands").map { element ->
            require(element is JsonPrimitive && element.isString) { "Invalid accepted command" }
            CommandId(element.content)
        }
        val usage = objectAt(root, "usage")
        usage.requireKeys(USAGE_KEYS)
        return CheckpointEnvelope(
            version = bounded(root, "version", Int.MAX_VALUE),
            strategyVersion = bounded(root, "strategy_version", Int.MAX_VALUE),
            run = identity,
            branchId = BranchId(string(root, "branch")),
            stage = stage,
            messages = messages,
            pendingToolCalls = pending,
            summary = summary,
            acceptedCommands = accepted,
            usage = RequestUsage(
                measurement(usage, "input"),
                measurement(usage, "cache_read"),
                measurement(usage, "cache_write"),
                measurement(usage, "thinking"),
                measurement(usage, "output"),
            ),
            projectRef = ref,
        )
    }

    private fun measurement(value: TokenMeasurement): JsonObject = buildJsonObject {
        put("tokens", value.tokens?.let(::JsonPrimitive) ?: JsonNull)
        put("source", value.source.name)
    }

    private fun measurement(row: JsonObject, key: String): TokenMeasurement {
        val value = objectAt(row, key)
        value.requireKeys(MEASUREMENT_KEYS)
        val tokens = when (val token = value["tokens"]) {
            null, JsonNull -> null
            is JsonPrimitive -> {
                require(!token.isString && token.content.matches(Regex("0|[1-9][0-9]*"))) {
                    "Invalid token measurement"
                }
                token.content.toLongOrNull() ?: throw IllegalArgumentException("Invalid token measurement")
            }
            else -> throw IllegalArgumentException("Invalid token measurement")
        }
        return TokenMeasurement(tokens, UsageSource.valueOf(string(value, "source")))
    }

    private fun JsonObject.requireKeys(expected: Set<String>) {
        require(keys == expected) { "Invalid Agent checkpoint fields" }
    }

    private fun objectAt(row: JsonObject, key: String): JsonObject =
        row[key] as? JsonObject ?: throw IllegalArgumentException("Invalid Agent checkpoint object")

    private fun array(row: JsonObject, key: String): JsonArray =
        row[key] as? JsonArray ?: throw IllegalArgumentException("Invalid Agent checkpoint array")

    private fun string(row: JsonObject, key: String): String {
        val value = row[key] as? JsonPrimitive ?: throw IllegalArgumentException("Invalid Agent checkpoint string")
        require(value.isString) { "Invalid Agent checkpoint string" }
        return value.content
    }

    private fun number(row: JsonObject, key: String): Long {
        val value = row[key] as? JsonPrimitive ?: throw IllegalArgumentException("Invalid Agent checkpoint number")
        require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*"))) {
            "Invalid Agent checkpoint number"
        }
        return value.content.toLongOrNull() ?: throw IllegalArgumentException("Invalid Agent checkpoint number")
    }

    private fun bounded(row: JsonObject, key: String, max: Int): Int =
        number(row, key).also { require(it in 0..max.toLong()) { "Invalid Agent checkpoint number" } }.toInt()
}
