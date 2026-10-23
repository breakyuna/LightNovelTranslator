package com.breakyuna.noveltranslator.nativecore.domain

import kotlinx.serialization.json.*

enum class TextRuleKind { TERM, PROTECT, PRE_REPLACE, POST_REPLACE }

/** Stable rule identity and ordering are project facts. Rule contents never enter diagnostics. */
data class ProjectTextRule(val id: String, val kind: TextRuleKind, val source: String,
    val target: String = "", val note: String = "", val enabled: Boolean = true,
    val regex: Boolean = false, val caseSensitive: Boolean = true) {
    init {
        require(id.isNotBlank() && id.length <= 128 && id.none(Char::isISOControl))
        TextProjectFingerprint.utf8(id)
        require(source.isNotBlank() && source.length <= 4096 && target.length <= 4096 && note.length <= 2048)
        require(kind != TextRuleKind.TERM || !regex)
        for (text in listOf(source, target, note)) {
            require('\u0000' !in text); TextProjectFingerprint.utf8(text)
        }
    }
    override fun toString(): String = "ProjectTextRule(redacted)"
}

class ProjectTextPolicy(rules: List<ProjectTextRule> = emptyList(), val prompt: String = "", val promptEnabled: Boolean = false) {
    val rules: List<ProjectTextRule> = java.util.Collections.unmodifiableList(ArrayList(rules))
    init {
        require(this.rules.size <= 256 && this.rules.map { it.id }.distinct().size == this.rules.size)
        require(prompt.length <= 16_384 && '\u0000' !in prompt); TextProjectFingerprint.utf8(prompt)
    }
    fun changed(rules: List<ProjectTextRule> = this.rules, prompt: String = this.prompt,
        promptEnabled: Boolean = this.promptEnabled) = ProjectTextPolicy(rules, prompt, promptEnabled)
    private val storedFingerprint: ObjectFingerprint by lazy {
        ObjectFingerprint(TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(ProjectTextPolicyCodec.encode(this))))
    }
    fun fingerprint(): ObjectFingerprint = storedFingerprint
    override fun toString(): String = "ProjectTextPolicy(redacted)"
}

/** A native interchange envelope. It deliberately does not claim LG JSON/CSV compatibility. */
object ProjectTextPolicyCodec {
    const val MAX_CHARS = 65_536
    fun encode(policy: ProjectTextPolicy): String = buildJsonObject {
        put("format", "lnt-text-policy"); put("version", 1)
        put("prompt", policy.prompt); put("prompt_enabled", policy.promptEnabled)
        put("rules", JsonArray(policy.rules.map { rule -> buildJsonObject {
            put("id", rule.id); put("kind", rule.kind.name); put("source", rule.source); put("target", rule.target)
            put("note", rule.note); put("enabled", rule.enabled); put("regex", rule.regex); put("case_sensitive", rule.caseSensitive)
        } }))
    }.toString().also { require(it.length <= MAX_CHARS) }

    fun decode(text: String): ProjectTextPolicy {
        val root = JsonObjectValue.parse(text, JsonInputLimits(MAX_CHARS, 8)).json
        require(root.keys == setOf("format", "version", "prompt", "prompt_enabled", "rules"))
        require(string(root, "format") == "lnt-text-policy")
        require(root.getValue("version") == JsonPrimitive(1))
        val rules = root.getValue("rules").jsonArray
        require(rules.size <= 256)
        return ProjectTextPolicy(rules.map { element ->
            val rule = element.jsonObject
            require(rule.keys == setOf("id", "kind", "source", "target", "note", "enabled", "regex", "case_sensitive"))
            ProjectTextRule(string(rule, "id"), TextRuleKind.valueOf(string(rule, "kind")), string(rule, "source"),
                string(rule, "target"), string(rule, "note"), boolean(rule, "enabled"), boolean(rule, "regex"), boolean(rule, "case_sensitive"))
        }, string(root, "prompt"), boolean(root, "prompt_enabled"))
    }
    private fun string(value: JsonObject, key: String): String {
        val item = value.getValue(key).jsonPrimitive
        require(item != JsonNull && item.isString); return item.content
    }
    private fun boolean(value: JsonObject, key: String): Boolean {
        val item = value.getValue(key).jsonPrimitive
        require(!item.isString); return requireNotNull(item.booleanOrNull)
    }
}

class ProjectPolicySnapshot(val ref: LocalProjectRef, val revision: Long, val policy: ProjectTextPolicy) {
    val fingerprint = policy.fingerprint()
}
class ProjectPolicyEdit(val commandId: CommandId, val ref: LocalProjectRef, val expectedRevision: Long,
    val base: ObjectFingerprint, val policy: ProjectTextPolicy) {
    init { require(expectedRevision >= 0 && commandId.value.length <= 2048) }
    fun digest(): String = TextProjectFingerprint.bytes(TextProjectFingerprint.utf8(buildJsonObject {
        put("format", "policy-edit-v1"); put("command", commandId.value); put("project", ref.projectId.value)
        put("instance", ref.instanceId.value); put("generation", ref.generation.value); put("revision", expectedRevision)
        put("base", base.sha256); put("policy", policy.fingerprint().sha256)
    }.toString()))
}
enum class PolicyEditStatus { COMMITTED, STALE_REVISION, STALE_FINGERPRINT }
data class PolicyEditReceipt(val commandId: CommandId, val ref: LocalProjectRef, val status: PolicyEditStatus,
    val revision: Long, val fingerprint: ObjectFingerprint, val committedAtMillis: Long)
interface ProjectTextPolicyPort {
    suspend fun textPolicy(ref: LocalProjectRef): ProjectPolicySnapshot
    suspend fun editTextPolicy(command: ProjectPolicyEdit): PolicyEditReceipt
    /** Read-only reconciliation of a fixed command after a lost response; never executes it. */
    suspend fun textPolicyReceipt(command: ProjectPolicyEdit): PolicyEditReceipt?
}
class TranslationTerm(val source: String, val target: String, val note: String)
