package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

/** LG external quality-rule JSON, independent of native project/preset envelopes.
 * The selected kind is explicit because LG files carry no rule kind or prompt.
 * Strict JSON is required; malformed documents are never silently repaired or partially imported.
 */
object LgRuleJsonCodec {
    const val MAX_CHARS = 65_536

    fun decode(text: String, kind: TextRuleKind, current: ProjectTextPolicy = ProjectTextPolicy()): RuleExchange {
        require(text.length <= MAX_CHARS)
        val envelope = JsonObjectValue.parse("{\"entries\":$text}", JsonInputLimits(MAX_CHARS + 16, 10)).json
        require(envelope.keys == setOf("entries"))
        val root = envelope.getValue("entries")
        val rows = ArrayList<ProjectTextRule>()
        fun add(source: String, target: String, note: String = "", regex: Boolean = false, sensitive: Boolean = false) {
            require(rows.size < RuleExchange.MAX_RULES)
            val canonical = buildJsonArray {
                add(kind.name); add(source); add(regex); add(sensitive)
            }.toString()
            val derived = "lgj1-" + TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(canonical)).take(32)
            val rule = ProjectTextRule(derived, kind, source, target, note, regex = regex, caseSensitive = sensitive)
            val matches = current.rules.filter { ruleSourceKey(it) == ruleSourceKey(rule) }
            // Never pick one of several existing rules arbitrarily for an external row without an ID.
            require(matches.size <= 1)
            rows.add(if (matches.size == 1) rule.copy(id = matches.single().id, enabled = matches.single().enabled) else rule)
        }
        when (root) {
            is JsonObject -> root.forEach { (source, value) ->
                require(value == JsonNull || value is JsonPrimitive && value.isString)
                add(source, if (value == JsonNull) "" else (value as JsonPrimitive).content)
            }
            is JsonArray -> {
                require(root.size <= RuleExchange.MAX_RULES + 1)
                val actorTable = root.firstOrNull() == JsonNull && root.drop(1).any {
                    it is JsonObject && "id" in it && ("name" in it || "nickname" in it)
                }
                root.forEachIndexed { index, value ->
                    if (index == 0 && actorTable) return@forEachIndexed
                    val row = value as? JsonObject ?: throw IllegalArgumentException("Invalid rule row")
                    var recognized = false
                    if ("src" in row) {
                        add(string(row, "src"), string(row, "dst", ""), string(row, "info", ""),
                            boolean(row, "regex"), boolean(row, "case_sensitive"))
                        recognized = true
                    }
                    if ("id" in row && ("name" in row || "nickname" in row)) {
                        val id = row.getValue("id") as? JsonPrimitive ?: throw IllegalArgumentException("Invalid actor identity")
                        require(!id.isString && id.content.matches(Regex("[1-9][0-9]*")))
                        require(id.content.toLongOrNull() != null)
                        for ((field, prefixes) in listOf("name" to listOf("\\n", "\\N"), "nickname" to listOf("\\nn", "\\NN"))) {
                            val target = string(row, field, "").trim()
                            if (target.isNotEmpty()) prefixes.forEach { add("$it[${id.content}]", target) }
                        }
                        recognized = true
                    }
                    require(recognized)
                }
            }
            else -> throw IllegalArgumentException("Invalid rule document")
        }
        return RuleExchange(rows)
    }

    /** External files cannot represent disabled rules. Reject rather than silently enable them. */
    fun encode(policy: ProjectTextPolicy, kind: TextRuleKind): String {
        val selected = policy.rules.filter { it.kind == kind }
        require(selected.all { it.enabled })
        return JsonArray(selected.map { rule -> buildJsonObject {
            put("src", rule.source); put("dst", rule.target); put("info", rule.note)
            put("regex", rule.regex); put("case_sensitive", rule.caseSensitive)
        } }).toString().also { require(it.length <= MAX_CHARS) }
    }

    private fun string(row: JsonObject, key: String, default: String? = null): String {
        val value = row[key] ?: return requireNotNull(default)
        if (value == JsonNull && default != null) return default
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Invalid rule field")
    }
    private fun boolean(row: JsonObject, key: String): Boolean {
        val value = row[key] ?: return false
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw IllegalArgumentException("Invalid rule flag")
    }
}
