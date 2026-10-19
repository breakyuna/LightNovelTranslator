package com.breakyuna.noveltranslator.core.glossary

import com.breakyuna.noveltranslator.data.model.LexiconKind
import com.breakyuna.noveltranslator.data.model.TermCategory
import java.text.Normalizer
import java.util.Locale

/** One problem found while parsing an import file; [line] is 1-based. */
data class GlossaryImportError(val line: Int, val reason: String)

/**
 * Result of a tolerant glossary file parse.
 *
 * [entries] contains every entry that had a usable `sourceTerm`, even when the same line also
 * produced a non-fatal notice in [errors] (e.g. an unrecognised `kind` that fell back to its
 * default). Entries without a source term are skipped and reported in [errors] only, so one bad
 * line never loses the rest of the file.
 */
data class GlossaryImportParseResult(
    val entries: List<GlossaryTransferEntry> = emptyList(),
    val errors: List<GlossaryImportError> = emptyList(),
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
}

/** How an incoming entry collides with the project's existing entries for the same source term. */
enum class GlossaryDuplicateKind {
    /** 译文相同：导入无副作用。 */
    SAME_TARGET,

    /** 既有条目的译文为空，导入可补齐。 */
    EXISTING_TARGET_EMPTY,

    /** 导入条目的译文为空，覆盖会抹掉既有译文。 */
    INCOMING_TARGET_EMPTY,

    /** 同源术语但译文不同：需要用户决策。 */
    DIFFERENT_TARGET,
}

/** Action the import UI should take for one colliding entry unless the user overrides it. */
enum class GlossaryImportAction { ADD, SKIP, OVERWRITE }

/** One incoming entry that collides with existing entries on the normalized source term. */
data class GlossaryImportDuplicate(
    val normalizedKey: String,
    val kind: GlossaryDuplicateKind,
    /** Index into the `incoming` list passed to [GlossaryIo.previewImport]. */
    val incomingIndex: Int,
    val incoming: GlossaryTransferEntry,
    /** Indexes into the `existing` list; empty for incoming-internal collisions. */
    val existingIndexes: List<Int>,
    val existing: List<GlossaryTransferEntry>,
) {
    val recommendedAction: GlossaryImportAction
        get() = when (kind) {
            GlossaryDuplicateKind.SAME_TARGET -> GlossaryImportAction.SKIP
            GlossaryDuplicateKind.EXISTING_TARGET_EMPTY -> GlossaryImportAction.OVERWRITE
            GlossaryDuplicateKind.INCOMING_TARGET_EMPTY -> GlossaryImportAction.SKIP
            // 真实冲突默认保守跳过，由 UI 提供"覆盖"入口
            GlossaryDuplicateKind.DIFFERENT_TARGET -> GlossaryImportAction.SKIP
        }
}

/**
 * Import preview for the confirmation UI.
 *
 * Mirrors LG's `preview_quality_rule_import`: duplicates are classified into four kinds by the
 * target-field relationship, and the result is split into add / skip / overwrite lists so the page
 * only renders counts and cards. [duplicates] keeps the full detail (indexes + kind +
 * recommendation) for the conflict cards.
 */
data class GlossaryImportPreview(
    val toAdd: List<GlossaryTransferEntry> = emptyList(),
    val duplicates: List<GlossaryImportDuplicate> = emptyList(),
) {
    val nonDuplicateCount: Int get() = toAdd.size
    val duplicateCount: Int get() = duplicates.size

    /** 选择"跳过"时不写入的导入条目（保留既有译文）。 */
    val toSkip: List<GlossaryTransferEntry>
        get() = duplicates.filter { it.recommendedAction == GlossaryImportAction.SKIP }.map { it.incoming }

    /** 建议"覆盖"的导入条目（替换同键既有条目）。 */
    val toOverwrite: List<GlossaryTransferEntry>
        get() = duplicates.filter { it.recommendedAction == GlossaryImportAction.OVERWRITE }.map { it.incoming }

    /** 需要用户逐条决策的冲突（同源术语、译文不同）。 */
    val conflicts: List<GlossaryImportDuplicate>
        get() = duplicates.filter { it.kind == GlossaryDuplicateKind.DIFFERENT_TARGET }
}

/**
 * File IO for glossary entries: JSON/CSV export, tolerant JSON import, and the duplicate preview
 * the confirmation UI consumes. Pure string-level logic — no database, no Android framework.
 *
 * Field aliases accepted on import (LG compatibility): `src`/`source`/`term` for the source term,
 * `dst`/`target`/`translation` for the target term, `info` for notes. Unknown enum values fall back
 * to their defaults instead of failing the import.
 */
object GlossaryIo {

    private val SOURCE_TERM_KEYS = listOf("sourceterm", "source_term", "src", "source", "term")
    private val TARGET_TERM_KEYS = listOf("targetterm", "target_term", "dst", "target", "translation")
    private val NOTES_KEYS = listOf("notes", "note", "info", "comment")
    private val CASE_SENSITIVE_KEYS = listOf("casesensitive", "case_sensitive")
    private val EXACT_MATCH_KEYS = listOf("exactmatch", "exact_match")
    private val ALIAS_KEYS = listOf("aliases", "alias")
    private val PRIORITY_KEYS = listOf("priority")
    private val WRAPPER_ARRAY_KEYS = listOf("entries", "glossary", "terms", "data", "items")
    private val KIND_NAMES = LexiconKind.values().map { it.name }.toSet()
    private val CATEGORY_NAMES = TermCategory.values().map { it.name }.toSet()

    private val CSV_HEADER = listOf(
        "sourceTerm", "targetTerm", "kind", "category", "aliases", "notes", "caseSensitive", "exactMatch", "priority"
    )

    /** Serializes entries as a UTF-8 JSON array (no BOM by default; the same shape presets use). */
    fun exportToJson(
        entries: List<GlossaryTransferEntry>,
        pretty: Boolean = true,
        includeBom: Boolean = false,
    ): String {
        val body = when {
            entries.isEmpty() -> "[]"
            !pretty -> entries.joinToString(",", "[", "]") { jsonEntryObject(it, fieldIndent = "", closingIndent = "") }
            else -> buildString {
                append("[\n")
                entries.forEachIndexed { index, entry ->
                    append(jsonEntryObject(entry, fieldIndent = "    ", closingIndent = "  "))
                    append(if (index == entries.lastIndex) "\n" else ",\n")
                }
                append("]")
            }
        }
        return if (includeBom) "\uFEFF$body" else body
    }

    /**
     * Serializes entries as RFC 4180 CSV (CRLF line endings). BOM defaults to on so Excel detects
     * UTF-8. Aliases are joined with `;` to keep the field quote-free; there is deliberately no CSV
     * import path (JSON is the round-trip format).
     */
    fun exportToCsv(entries: List<GlossaryTransferEntry>, includeBom: Boolean = true): String {
        val body = buildString {
            append(CSV_HEADER.joinToString(",") { csvField(it) }).append("\r\n")
            for (entry in entries) {
                val fields = listOf(
                    entry.sourceTerm,
                    entry.targetTerm,
                    entry.kind,
                    entry.category,
                    entry.aliases.joinToString(";"),
                    entry.notes,
                    entry.caseSensitive.toString(),
                    entry.exactMatch.toString(),
                    entry.priority.toString(),
                )
                append(fields.joinToString(",") { csvField(it) }).append("\r\n")
            }
        }
        return if (includeBom) "\uFEFF$body" else body
    }

    /**
     * Tolerant JSON import. Accepts a bare array, a wrapper object (`{"entries": [...]}`), a
     * Markdown code fence around either, and LG-style field aliases. Per-entry problems are
     * reported with line numbers and never discard the remaining entries.
     */
    fun parseImportJson(text: String): GlossaryImportParseResult {
        // BOM 常见于 Excel/记事本保存的 UTF-8 文件，先剥掉再解析
        val payload = LenientJson.stripCodeFence(text).removePrefix("\uFEFF")
        if (payload.isBlank()) {
            return GlossaryImportParseResult(errors = listOf(GlossaryImportError(1, "文件为空或只包含代码围栏")))
        }
        val (root, parseErrors) = LenientJson(payload).parseDocument()
        val errors = parseErrors.mapTo(mutableListOf()) { GlossaryImportError(it.line, it.message) }
        if (root == null) {
            return GlossaryImportParseResult(errors = errors)
        }
        val items = extractEntryArray(root)
        if (items == null) {
            errors += GlossaryImportError(root.line, "未找到条目数组：支持顶层数组或 {\"entries\": [...]} 形式的包装对象")
            return GlossaryImportParseResult(errors = errors)
        }

        val entries = mutableListOf<GlossaryTransferEntry>()
        for (item in items) {
            if (item !is JsonObject) {
                errors += GlossaryImportError(item.line, "条目必须是 JSON 对象")
                continue
            }
            readEntry(item, errors)?.let { entries += it }
        }
        return GlossaryImportParseResult(entries = entries, errors = errors)
    }

    /**
     * Classifies incoming entries against the existing project entries by normalized source term.
     *
     * Matching key: NFKC + whitespace-collapsed source term, case-folded unless the entry is
     * case-sensitive (same shape as `LexiconCandidateVoting.normalizeSourceTerm`), so `Alice` and
     * `ＡＬＩＣＥ` collide while a case-sensitive entry only collides with itself.
     */
    fun previewImport(
        existing: List<GlossaryTransferEntry>,
        incoming: List<GlossaryTransferEntry>,
    ): GlossaryImportPreview {
        val existingByKey = existing.withIndex().groupBy { termKey(it.value) }
        val incomingByKey = incoming.withIndex().groupBy { termKey(it.value) }
        val toAdd = mutableListOf<Pair<Int, GlossaryTransferEntry>>()
        val duplicates = mutableListOf<GlossaryImportDuplicate>()

        for ((key, items) in incomingByKey) {
            val kept = items.last() // 同键多条导入：后者覆盖前者，避免数据库唯一索引冲突
            val existingItems = existingByKey[key].orEmpty()
            if (existingItems.isEmpty()) {
                toAdd += kept.index to kept.value
                items.dropLast(1).forEach { shadowed ->
                    duplicates += GlossaryImportDuplicate(
                        normalizedKey = key,
                        kind = classifyKind(listOf(kept.value.targetTerm), shadowed.value.targetTerm),
                        incomingIndex = shadowed.index,
                        incoming = shadowed.value,
                        existingIndexes = emptyList(),
                        existing = emptyList(),
                    )
                }
                continue
            }
            // 与 LG 一致：每条导入条目分别与既有条目的译文比较，分类互不影响
            items.forEach { item ->
                duplicates += GlossaryImportDuplicate(
                    normalizedKey = key,
                    kind = classifyKind(existingItems.map { it.value.targetTerm }, item.value.targetTerm),
                    incomingIndex = item.index,
                    incoming = item.value,
                    existingIndexes = existingItems.map { it.index },
                    existing = existingItems.map { it.value },
                )
            }
        }
        return GlossaryImportPreview(
            toAdd = toAdd.sortedBy { it.first }.map { it.second },
            duplicates = duplicates.sortedBy { it.incomingIndex },
        )
    }

    /** Normalized match key; the case-sensitivity flag is part of the key, as in LG. */
    private fun termKey(entry: GlossaryTransferEntry): String {
        val normalized = Normalizer.normalize(
            entry.sourceTerm.trim().replace(Regex("\\s+"), " "),
            Normalizer.Form.NFKC
        )
        val folded = if (entry.caseSensitive) normalized else normalized.lowercase(Locale.ROOT)
        return "$folded|${entry.caseSensitive}"
    }

    /** Ported from LG `classify_duplicate_kind`; the order of checks is part of the contract. */
    private fun classifyKind(existingTargets: List<String>, incomingTarget: String): GlossaryDuplicateKind {
        val targets = existingTargets.map { it.trim() }
        val incoming = incomingTarget.trim()
        return when {
            targets.all { it == incoming } -> GlossaryDuplicateKind.SAME_TARGET
            targets.any { it.isEmpty() } && incoming.isNotEmpty() -> GlossaryDuplicateKind.EXISTING_TARGET_EMPTY
            incoming.isEmpty() && targets.any { it.isNotEmpty() } -> GlossaryDuplicateKind.INCOMING_TARGET_EMPTY
            else -> GlossaryDuplicateKind.DIFFERENT_TARGET
        }
    }

    private fun extractEntryArray(root: JsonNode): List<JsonNode>? = when (root) {
        is JsonArray -> root.items
        is JsonObject -> findArrayField(root, depth = 0)?.items
        else -> null
    }

    /** Preferred wrapper keys first, then any array-valued field, so `{"glossary": [...]}` works. */
    private fun findArrayField(node: JsonObject, depth: Int): JsonArray? {
        if (depth > 3) return null
        for (key in WRAPPER_ARRAY_KEYS) {
            val value = node.fields.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == key }?.value
            if (value is JsonArray) return value
        }
        for (value in node.fields.values) {
            when (value) {
                is JsonArray -> return value
                is JsonObject -> findArrayField(value, depth + 1)?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun readEntry(
        objectNode: JsonObject,
        errors: MutableList<GlossaryImportError>
    ): GlossaryTransferEntry? {
        val fields = objectNode.fields
        fun field(aliases: List<String>): JsonNode? {
            for (alias in aliases) {
                fields.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == alias }?.let { return it.value }
            }
            return null
        }

        val sourceNode = field(SOURCE_TERM_KEYS)
        val sourceTerm = scalarText(sourceNode)
        if (sourceTerm.isBlank()) {
            errors += GlossaryImportError(objectNode.line, "缺少 sourceTerm 字段")
            return null
        }
        val kindNode = field(listOf("kind"))
        val kind = scalarText(kindNode).trim().uppercase(Locale.ROOT)
        if (kind.isNotEmpty() && kind !in KIND_NAMES) {
            errors += GlossaryImportError(kindNode?.line ?: objectNode.line, "未知 kind：$kind，已按默认值处理")
        }
        val categoryNode = field(listOf("category"))
        val category = scalarText(categoryNode).trim().uppercase(Locale.ROOT)
        if (category.isNotEmpty() && category !in CATEGORY_NAMES) {
            errors += GlossaryImportError(categoryNode?.line ?: objectNode.line, "未知 category：$category，已按默认值处理")
        }
        val aliasNode = field(ALIAS_KEYS)
        val aliases = when (aliasNode) {
            is JsonArray -> aliasNode.items.mapNotNull { item ->
                when (item) {
                    is JsonString -> item.value.trim().takeIf { it.isNotEmpty() }
                    else -> {
                        errors += GlossaryImportError(item.line, "aliases 数组包含非字符串项，已忽略")
                        null
                    }
                }
            }
            is JsonString ->
                aliasNode.value.split(',', ';', '|').map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
        var priority = 0
        field(PRIORITY_KEYS)?.let { node ->
            val raw = scalarText(node)
            if (raw.isNotEmpty() && raw.toIntOrNull() == null) {
                errors += GlossaryImportError(node.line, "priority 不是整数：$raw，已按 0 处理")
            }
            priority = raw.toIntOrNull() ?: 0
        }
        return GlossaryTransferEntry(
            sourceTerm = sourceTerm,
            targetTerm = scalarText(field(TARGET_TERM_KEYS)),
            kind = kind.takeIf { it in KIND_NAMES } ?: LexiconKind.PROPER_NOUN.name,
            category = category.takeIf { it in CATEGORY_NAMES } ?: TermCategory.CUSTOM.name,
            aliases = aliases,
            notes = scalarText(field(NOTES_KEYS)),
            caseSensitive = booleanField(field(CASE_SENSITIVE_KEYS), defaultValue = false),
            exactMatch = booleanField(field(EXACT_MATCH_KEYS), defaultValue = true),
            priority = priority,
        )
    }

    /** Coerces any scalar node to text; non-scalars (objects/arrays) yield an empty string. */
    private fun scalarText(node: JsonNode?): String = when (node) {
        null -> ""
        is JsonString -> node.value
        is JsonNumber -> node.raw
        is JsonBoolean -> node.value.toString()
        is JsonNull -> ""
        else -> "" // JsonObject / JsonArray 不是标量
    }

    private fun booleanField(node: JsonNode?, defaultValue: Boolean): Boolean = when (node) {
        null -> defaultValue
        is JsonBoolean -> node.value
        is JsonString -> when (node.value.trim().lowercase(Locale.ROOT)) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> defaultValue
        }
        is JsonNumber -> node.raw.toDoubleOrNull()?.let { it != 0.0 } ?: defaultValue
        else -> defaultValue
    }

    private fun jsonEntryObject(entry: GlossaryTransferEntry, fieldIndent: String, closingIndent: String): String {
        val fields = listOf(
            "sourceTerm" to jsonString(entry.sourceTerm),
            "targetTerm" to jsonString(entry.targetTerm),
            "kind" to jsonString(entry.kind),
            "category" to jsonString(entry.category),
            "aliases" to if (entry.aliases.isEmpty()) {
                "[]"
            } else {
                entry.aliases.joinToString(", ", "[", "]") { jsonString(it) }
            },
            "notes" to jsonString(entry.notes),
            "caseSensitive" to entry.caseSensitive.toString(),
            "exactMatch" to entry.exactMatch.toString(),
            "priority" to entry.priority.toString(),
        )
        if (fieldIndent.isEmpty()) {
            return fields.joinToString(",", "{", "}") { (key, value) -> "${jsonString(key)}:$value" }
        }
        return buildString {
            append("{\n")
            fields.forEachIndexed { index, (key, value) ->
                append(fieldIndent).append(jsonString(key)).append(": ").append(value)
                append(if (index == fields.lastIndex) "\n" else ",\n")
            }
            append(closingIndent).append("}")
        }
    }

    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch.code < 0x20) {
                    val hex = ch.code.toString(16)
                    out.append("\\u").append("0".repeat(4 - hex.length)).append(hex)
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
