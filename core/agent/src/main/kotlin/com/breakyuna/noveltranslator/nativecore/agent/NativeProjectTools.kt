package com.breakyuna.noveltranslator.nativecore.agent

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Project binding belongs to the host, never to a model-selected default project. */
fun nativeProjectTools(runtime: RuntimePort, projects: TextProjectPort, ref: LocalProjectRef): AgentToolRegistry =
    AgentToolRegistry(runtime, listOf(
        RegisteredAgentTool("project_summary", "Read the bound project summary and assets",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                require(run.projectId == ref.projectId)
                val project = projects.open(ref)
                JsonObjectValue.parse(buildJsonObject {
                    put("name", project.name); put("asset", project.assetPath.value)
                    put("items", project.itemCount); put("revision", project.revision)
                    project.settings?.let { settings ->
                        put("source_language", settings.sourceLanguage)
                        put("target_language", settings.targetLanguage)
                        put("bilingual_deduplicate_same", settings.bilingualDeduplicateSame)
                        put("write_translated_names", settings.writeTranslatedNames)
                    }
                    put("assets", buildJsonArray {
                        project.assets.forEach { asset ->
                            add(buildJsonObject {
                                put("id", asset.id); put("path", asset.path.value); put("format", asset.format.name)
                                put("ordinal", asset.ordinal); put("enabled", asset.enabled)
                                put("bytes", asset.originalSize); put("items", asset.itemCount)
                                put("sha256", asset.sha256)
                            })
                        }
                    })
                }.toString(), JsonInputLimits(256 * 1024, 16))
            }),
        RegisteredAgentTool("read_item", "Read one stable text item in the bound project",
            ToolParameterSchema(mapOf("item_id" to ToolParameter.Integer(1, Long.MAX_VALUE))), ToolEffect.READ_ONLY,
            AgentToolHandler { run, args ->
                require(run.projectId == ref.projectId)
                val detail = projects.item(ref, ItemId(requireNotNull(args.integer("item_id"))))
                    ?: throw TextProjectException(TextProjectException.Reason.UNKNOWN_PROJECT)
                JsonObjectValue.parse(buildJsonObject {
                    put("item_id", detail.item.id.value); put("row", detail.item.row + 1)
                    put("source", detail.item.source); put("translation", detail.item.translation)
                    put("status", detail.item.status.name); put("revision", detail.revision)
                    put("asset_id", detail.item.assetId); put("slot", detail.item.slot)
                    put("fingerprint", detail.item.fingerprint.sha256)
                }.toString(), JsonInputLimits(1024 * 1024, 16))
            }),
        RegisteredAgentTool("list_items", "List bounded item metadata without full bodies",
            ToolParameterSchema(mapOf(
                "after_id" to ToolParameter.Integer(0, Long.MAX_VALUE, required = false),
                "limit" to ToolParameter.Integer(1, 50, required = false),
                "status" to ToolParameter.Text(0, 32, required = false),
            )), ToolEffect.READ_ONLY, AgentToolHandler { run, args ->
                require(run.projectId == ref.projectId)
                val after = args.integer("after_id") ?: 0L
                val limit = (args.integer("limit") ?: 20L).toInt()
                val status = status(args.text("status"))
                val query = TextItemQuery(status = status)
                val page = if (query.active) projects.queryPage(ref, query, after, limit)
                else projects.readPage(ref, after, limit)
                itemPageJson(page, limit)
            }),
        RegisteredAgentTool("search_items", "Literal substring search over the bound project",
            ToolParameterSchema(mapOf(
                "text" to ToolParameter.Text(1, TextItemQuery.MAX_TEXT_LENGTH),
                "limit" to ToolParameter.Integer(1, 20, required = false),
                "status" to ToolParameter.Text(0, 32, required = false),
            )), ToolEffect.READ_ONLY, AgentToolHandler { run, args ->
                require(run.projectId == ref.projectId)
                val text = requireNotNull(args.text("text"))
                val limit = (args.integer("limit") ?: 20L).toInt()
                val query = TextItemQuery(status = status(args.text("status")), text = text)
                itemPageJson(projects.queryPage(ref, query, 0, limit), limit)
            }),
        RegisteredAgentTool("project_statistics", "Read whole-project status counts",
            ToolParameterSchema(emptyMap()), ToolEffect.READ_ONLY, AgentToolHandler { run, _ ->
                require(run.projectId == ref.projectId)
                val page = projects.readPage(ref, limit = 1)
                JsonObjectValue.parse(buildJsonObject {
                    put("revision", page.summary.revision); put("total", page.summary.itemCount)
                    put("assets", page.summary.assets.size)
                    page.statistics?.let { statistics ->
                        put("unprocessed", statistics.unprocessed); put("processed", statistics.processed)
                        put("excluded", statistics.excluded); put("skipped", statistics.skipped)
                        put("errors", statistics.errors); put("duplicated", statistics.duplicated)
                        put("translatable_unprocessed", statistics.translatableUnprocessed)
                    }
                }.toString(), JsonInputLimits(16_384, 8))
            }),
    ))

private fun status(value: String?): TextItemStatus? = value?.takeIf { it.isNotBlank() }?.let { raw ->
    TextItemStatus.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown status")
}

private fun itemPageJson(page: TextProjectPage, limit: Int): JsonObjectValue = JsonObjectValue.parse(buildJsonObject {
    put("revision", page.summary.revision)
    put("matching", page.matchingItems)
    put("returned", page.items.size)
    put("next_after_id", page.items.takeIf { it.size == limit }?.lastOrNull()?.id?.value ?: 0L)
    put("items", buildJsonArray {
        page.items.forEach { item ->
            add(buildJsonObject {
                put("item_id", item.id.value); put("row", item.row + 1)
                put("status", item.status.name); put("asset_id", item.assetId); put("slot", item.slot)
                put("source_chars", item.source.length); put("translation_chars", item.translation.length)
                put("fingerprint", item.fingerprint.sha256)
            })
        }
    })
}.toString(), JsonInputLimits(512 * 1024, 16))


/** Preparation only: persists a fixed proposal; cannot consume approval or edit project rows. */
fun nativeApprovalTools(runtime: RuntimePort, workspace: NativeAgentWorkspacePort, ref: LocalProjectRef,
                        lease: RunLease): AgentToolRegistry = AgentToolRegistry(runtime, listOf(
    RegisteredAgentTool("prepare_text_edit", "Prepare one fixed text edit for explicit user approval",
        ToolParameterSchema(mapOf("item_id" to ToolParameter.Integer(1, Long.MAX_VALUE),
            "translation" to ToolParameter.Text(0, 65_536))), ToolEffect.APPROVAL_PREPARATION,
        AgentToolHandler { run, args ->
            require(run == lease.identity && run.projectId == ref.projectId)
            val approval = workspace.prepare(lease, ref, ItemId(requireNotNull(args.integer("item_id"))),
                requireNotNull(args.text("translation")))
            JsonObjectValue.parse(buildJsonObject {
                put("approval", approval.id.value); put("digest", approval.digest); put("state", "PENDING")
            }.toString(), JsonInputLimits(16_384, 8))
        })))
