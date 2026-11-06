package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.agent.*
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.engine.TranslationQueueCoordinator
import com.breakyuna.noveltranslator.nativecore.storage.NativeTextProjectStore
import kotlinx.serialization.json.*

internal class NativeAgentTranslationService(
    private val projects: NativeTextProjectStore,
    private val queue: TranslationQueueCoordinator,
    private val scheduler: NativeTranslationQueueScheduler,
    private val proposals: NativeAgentBusinessProposals,
    private val validateModel: suspend (ModelConfigurationRef) -> Unit,
) {
    private val enqueueSchema = ToolParameterSchema(mapOf(
        "model_id" to ToolParameter.Text(1, 128), "model_revision" to ToolParameter.Integer(0, Long.MAX_VALUE),
        "scope" to ToolParameter.Text(1, 16, required = false), "after_id" to ToolParameter.Integer(0, Long.MAX_VALUE, required = false),
        "page_size" to ToolParameter.Integer(1, 100, required = false),
        "target_language" to ToolParameter.Text(1, 80), "source_language" to ToolParameter.Text(1, 80, required = false),
        "instructions" to ToolParameter.Text(0, 16_384, required = false),
        "start_mode" to ToolParameter.Text(1, 16, required = false),
        "batch_size" to ToolParameter.Integer(1, 20, required = false),
        "batch_characters" to ToolParameter.Integer(256, 32_768, required = false),
        "preceding_lines" to ToolParameter.Integer(0, 16, required = false),
        "additional_requests_per_batch" to ToolParameter.Integer(0, 5, required = false),
        "deduplicate_same_file" to ToolParameter.BooleanValue(required = false),
    ))
    private fun options(args: ValidatedToolArguments) = RemoteTranslationOptions(
        targetLanguage = requireNotNull(args.text("target_language")), sourceLanguage = args.text("source_language") ?: "auto",
        instructions = args.text("instructions") ?: "", batchSize = (args.integer("batch_size") ?: 10).toInt(),
        batchCharacters = (args.integer("batch_characters") ?: 16_384).toInt(),
        startMode = TranslationStartMode.valueOf(args.text("start_mode") ?: "NEW"),
        precedingLines = (args.integer("preceding_lines") ?: 2).toInt(),
        additionalRequestsPerBatch = (args.integer("additional_requests_per_batch") ?: 0).toInt(),
        deduplicateSameFile = args.boolean("deduplicate_same_file") ?: false)
    private fun config(args: ValidatedToolArguments) = ModelConfigurationRef(requireNotNull(args.text("model_id")),
        requireNotNull(args.integer("model_revision")))
    private fun members(text: String): List<ItemId> {
        val array = Json.parseToJsonElement(text).jsonArray
        require(array.size <= TranslationTaskFailureBatch.MAX_MEMBERS)
        val schema = ToolParameterSchema(mapOf("id" to ToolParameter.Integer(1, Long.MAX_VALUE)))
        return array.map { value -> ItemId(requireNotNull(schema.validate(JsonObjectValue.parse(
            buildJsonObject { put("id", value) }.toString(), JsonInputLimits(4096, 4))).integer("id"))) }
            .also { require(it.distinct().size == it.size) }
    }
    private fun failureFacts(task: TranslationQueuedTask): JsonObject = buildJsonObject {
        put("run", task.identity.run.runId.value); put("state", task.state.name)
        put("batches", buildJsonArray { task.batches.forEach { batch -> add(buildJsonObject {
            put("batch", batch.id.value); put("members", buildJsonArray { batch.members.forEach { member -> add(buildJsonObject {
                put("item_id", member.itemId.value); put("fingerprint", member.fingerprint.sha256)
                put("failure", member.failure.name); put("receipt", member.receipt.name); put("usage", member.usage.name)
                put("committed", member.committed); put("terminal", member.terminal)
            }) } })
        }) } })
        put("decisions", buildJsonArray { task.decisions.forEach { decision -> add(buildJsonObject {
            put("action", decision.action.name); put("batch", decision.batchId?.value)
            put("members", JsonArray(decision.memberIds.map { JsonPrimitive(it.value) }))
        }) } })
    }
    private suspend fun snapshot(ref: LocalProjectRef): JsonObject {
        val current = queue.snapshot(ref)
        return buildJsonObject {
            put("queue_revision", current.revision)
            put("tasks", buildJsonArray { current.queue.tasks.forEach { task -> add(buildJsonObject {
                val id = task.identity
                put("run", id.run.runId.value); put("command", id.run.commandId.value)
                put("model_id", id.configuration.id); put("model_revision", id.configuration.revision)
                put("project_revision", id.revision); put("state", task.state.name)
            }) } })
        }
    }
    suspend fun apply(ref: LocalProjectRef, proposal: NativeAgentBusinessProposal) {
        val payload = proposal.payload
        require(snapshot(ref) == payload.getValue("before"))
        val command = CommandId(if (proposal.kind == "enqueue_translation") "agent-enqueue-" + proposal.id else proposal.id)
        when (proposal.kind) {
            "enqueue_translation" -> {
                val args = enqueueSchema.validate(JsonObjectValue.parse(payload.getValue("arguments").toString(), JsonInputLimits(32 * 1024, 8)))
                val configuration = config(args)
                validateModel(configuration)
                val run = RunIdentity(ref.projectId, null, RunId(proposal.id), command)
                val revision = proposal.document.getValue("revision").jsonPrimitive.long
                val whole = (args.text("scope") ?: "project") == "project"
                val after = args.integer("after_id") ?: 0L
                require((args.text("scope") ?: "project") in setOf("project", "page") && (!whole || after == 0L))
                val result = queue.enqueue(TranslationTaskSpec(ref, run, configuration, revision, after,
                    (args.integer("page_size") ?: 40L).toInt(), whole, options(args)), command)
                require(result !is TranslationQueueResult.Rejected)
            }
            "translation_failure_decision" -> {
                val task = queue.snapshot(ref).queue.tasks.single { it.identity.run.runId.value == payload.getValue("run").jsonPrimitive.content }
                require(failureFacts(task) == payload.getValue("facts"))
                val ids = members(payload.getValue("members").toString())
                val decision = CommandId("agent-decision-" + proposal.id)
                val result = when (payload.getValue("operation").jsonPrimitive.content) {
                    "retry" -> scheduler.retryFailureMembers(task.identity,
                        TranslationTaskFailureBatchId(payload.getValue("batch").jsonPrimitive.content), ids, decision)
                    "skip" -> scheduler.skipFailureMembers(task.identity,
                        TranslationTaskFailureBatchId(payload.getValue("batch").jsonPrimitive.content), ids, decision)
                    "keep" -> scheduler.keepFailureMembers(task.identity,
                        TranslationTaskFailureBatchId(payload.getValue("batch").jsonPrimitive.content), ids, decision)
                    "terminate" -> scheduler.terminateFailureTask(task.identity, decision)
                    else -> error("Unknown failure decision")
                }
                require(result.queue !is TranslationQueueResult.Rejected)
            }
            "control_translation" -> {
                val runId = payload.getValue("run").jsonPrimitive.content
                val task = queue.snapshot(ref).queue.tasks.single { it.identity.run.runId.value == runId }
                when (payload.getValue("operation").jsonPrimitive.content) {
                    "pause" -> require(scheduler.pause(task.identity, command) !is TranslationQueueResult.Rejected)
                    "cancel" -> require(scheduler.cancel(task.identity, command) !is TranslationQueueResult.Rejected)
                    // Start/resume wakeups occur only after the approved receipt has been committed.
                    "start", "resume" -> Unit
                    else -> error("Unknown queue command")
                }
            }
            else -> error("Unknown translation proposal")
        }
    }
    suspend fun cancelUncommittedEnqueue(ref: LocalProjectRef, proposal: NativeAgentBusinessProposal) {
        if (proposal.kind != "enqueue_translation") return
        val task = queue.snapshot(ref).queue.tasks.singleOrNull { it.identity.run.runId.value == proposal.id } ?: return
        scheduler.cancel(task.identity, CommandId("cancel-" + proposal.id))
    }
    suspend fun dispatch(ref: LocalProjectRef, proposal: NativeAgentBusinessProposal) {
        if (proposal.kind in setOf("enqueue_translation", "translation_failure_decision")) scheduler.kick()
        else when (proposal.payload.getValue("operation").jsonPrimitive.content) {
            "start" -> scheduler.start(queue.snapshot(ref).queue.tasks.single {
                it.identity.run.runId.value == proposal.payload.getValue("run").jsonPrimitive.content }.identity)
            "resume" -> scheduler.resume(ref, CommandId(proposal.id))
        }
    }
    fun tools(lease: RunLease, ref: LocalProjectRef): List<RegisteredAgentTool> {
        fun result(proposal: NativeAgentBusinessProposal) = JsonObjectValue.parse(buildJsonObject {
            put("approval", proposal.id); put("digest", proposal.digest); put("state", proposal.status)
        }.toString(), JsonInputLimits(4096, 4))
        return listOf(
            RegisteredAgentTool("translation_failure_facts", "Read exact durable failure batches and prior member decisions for one queue task. No retries or writes.",
                ToolParameterSchema(mapOf("run" to ToolParameter.Text(1, 128))), ToolEffect.READ_ONLY, AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val task = queue.snapshot(ref).queue.tasks.single { it.identity.run.runId.value == args.text("run") }
                    JsonObjectValue.parse(failureFacts(task).toString(), JsonInputLimits(512 * 1024, 12))
                }),
            RegisteredAgentTool("prepare_translation_failure_decision", "Prepare explicit review of selected failed members on the SAME task. " +
                "operation is retry/skip/keep/terminate. retry/skip/keep require batch and members_json (a JSON array of exact item IDs, max 20). " +
                "terminate is task-wide and accepts neither batch nor members. Retry can incur charges after approval; uncertain dispatches " +
                "cannot be silently retried. The approval freezes queue revision, failure receipts, fingerprints and member selection.",
                ToolParameterSchema(mapOf("run" to ToolParameter.Text(1, 128), "operation" to ToolParameter.Text(1, 16),
                    "batch" to ToolParameter.Text(1, 128, required = false), "members_json" to ToolParameter.Text(2, 2048, required = false))),
                ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val operation = requireNotNull(args.text("operation")); require(operation in setOf("retry", "skip", "keep", "terminate"))
                    val task = queue.snapshot(ref).queue.tasks.single { it.identity.run.runId.value == args.text("run") }
                    require(!task.state.terminal)
                    val ids = members(args.text("members_json") ?: "[]")
                    val batch = args.text("batch")
                    if (operation == "terminate") require(batch == null && ids.isEmpty()) else {
                        require(task.state.awaitingDecision && ids.isNotEmpty())
                        val facts = requireNotNull(task.batch(TranslationTaskFailureBatchId(requireNotNull(batch))))
                        require(ids.all { facts.member(it) != null })
                    }
                    result(proposals.prepare(ref, run, projects.open(ref).revision, "translation_failure_decision", buildJsonObject {
                        put("before", snapshot(ref)); put("facts", failureFacts(task)); put("run", task.identity.run.runId.value)
                        put("operation", operation); put("batch", batch); put("members", JsonArray(ids.map { JsonPrimitive(it.value) }))
                    }))
                }),
            RegisteredAgentTool("translation_queue", "Read the bound project's translation queue and exact model references; no paid requests.",
                ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                    require(run == lease.identity)
                    JsonObjectValue.parse(snapshot(ref).toString(), JsonInputLimits(512 * 1024, 12))
                }),
            RegisteredAgentTool("prepare_project_translation", "Prepare a project or page-scope paid translation for explicit user approval. " +
                "arguments_json contains model_id, model_revision, target_language and optional scope (project/page), after_id, page_size, source_language, instructions, " +
                "start_mode (NEW/CONTINUE/RESET), batch_size, batch_characters, preceding_lines, additional_requests_per_batch, " +
                "deduplicate_same_file. RESET replaces successful returned translations. The approval displays the complete frozen " +
                "policy and queue snapshot. No model request is made before approval.",
                ToolParameterSchema(mapOf("arguments_json" to ToolParameter.Text(2, 32 * 1024))),
                ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val arguments = JsonObjectValue.parse(requireNotNull(args.text("arguments_json")), JsonInputLimits(32 * 1024, 8))
                    val validated = enqueueSchema.validate(arguments)
                    options(validated); validateModel(config(validated))
                    val scope = validated.text("scope") ?: "project"
                    require(scope in setOf("project", "page") && (scope != "project" || (validated.integer("after_id") ?: 0L) == 0L))
                    result(proposals.prepare(ref, run, projects.open(ref).revision, "enqueue_translation", buildJsonObject {
                        put("arguments", arguments.json); put("before", snapshot(ref))
                    }))
                }),
            RegisteredAgentTool("prepare_translation_control", "Prepare start/pause/cancel/resume of an exact queue task for explicit approval. " +
                "Read translation_queue first. Resume resumes the project queue and may incur paid requests after approval.",
                ToolParameterSchema(mapOf("run" to ToolParameter.Text(1, 128), "operation" to ToolParameter.Text(1, 16))),
                ToolEffect.APPROVAL_PREPARATION, AgentToolHandler { run, args ->
                    require(run == lease.identity)
                    val operation = requireNotNull(args.text("operation"))
                    require(operation in setOf("start", "pause", "cancel", "resume"))
                    val runId = requireNotNull(args.text("run"))
                    val task = queue.snapshot(ref).queue.tasks.single { it.identity.run.runId.value == runId }
                    require(operation != "start" || task.state == TranslationTaskQueueState.QUEUED)
                    require(operation != "resume" || task.state == TranslationTaskQueueState.PAUSED)
                    result(proposals.prepare(ref, run, projects.open(ref).revision, "control_translation", buildJsonObject {
                        put("run", runId); put("operation", operation); put("before", snapshot(ref))
                    }))
                }),
        )
    }
}
