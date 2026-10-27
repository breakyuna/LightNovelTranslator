package com.breakyuna.noveltranslator.nativecore.workspace

import java.text.Normalizer
import java.util.Locale

@JvmInline
value class WorkspaceSkillId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128) { "Invalid skill identity" }
        require(value.none { it.isISOControl() || it.isWhitespace() || it == '/' || it == '\\' }) {
            "Invalid skill identity"
        }
        requireWellFormedUnicode(value)
    }
}

enum class WorkspaceSkillSource { BUILT_IN, USER }

/**
 * Skill-level grants and resource ceilings. The defaults keep a legacy skill that has no
 * policy metadata on local, read-only work with bounded local output: materials and artifacts
 * are available, while search, fetch, proposal and automatic execution are denied. Built-in and
 * user skills use the same rules; a built-in policy is shipped in its read-only metadata and can
 * only be changed by replacing that package, never by a user edit.
 *
 * The capability mapping is exposed for future execution adapters. Constructing or selecting a
 * policy never executes a skill or grants a run.
 */
data class WorkspaceSkillPolicy(
    val allowMaterials: Boolean = true,
    val allowArtifacts: Boolean = true,
    val allowSearch: Boolean = false,
    val allowFetch: Boolean = false,
    val allowProposeChanges: Boolean = false,
    val allowPrint: Boolean = true,
    val maxRunMillis: Int = DEFAULT_MAX_RUN_MILLIS,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    val maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES,
    val maxChangeBytes: Int = DEFAULT_MAX_CHANGE_BYTES,
    val allowAutoSelect: Boolean = true,
) {
    init {
        require(maxRunMillis in MIN_RUN_MILLIS..MAX_RUN_MILLIS) { "Invalid skill run limit" }
        require(maxOutputBytes in 1..MAX_OUTPUT_BYTES) { "Invalid skill output limit" }
        require(maxArtifactBytes in 1..MAX_ARTIFACT_BYTES) { "Invalid skill artifact limit" }
        require(maxChangeBytes in 1..MAX_CHANGE_BYTES) { "Invalid skill change limit" }
    }

    /** Whether one workspace capability is available under this policy. */
    fun grants(capability: WorkspaceCapability): Boolean = when (capability) {
        WorkspaceCapability.READ_MATERIALS -> allowMaterials
        WorkspaceCapability.EMIT_ARTIFACTS -> allowArtifacts
        WorkspaceCapability.SEARCH -> allowSearch
        WorkspaceCapability.FETCH -> allowFetch
        WorkspaceCapability.PROPOSE_CHANGES -> allowProposeChanges
        WorkspaceCapability.PRINT -> allowPrint
    }

    /** Snapshot of the capabilities this policy grants, in [WorkspaceCapability] order. */
    val grantedCapabilities: Set<WorkspaceCapability>
        get() = workspaceFrozenSet(WorkspaceCapability.values().filter { grants(it) }.toSet())

    /**
     * Returns the intersection of this policy and [ceiling]. Future executors can use this to
     * keep an invocation under both its skill policy and a narrower run policy.
     */
    fun restrictedBy(ceiling: WorkspaceSkillPolicy): WorkspaceSkillPolicy = WorkspaceSkillPolicy(
        allowMaterials = allowMaterials && ceiling.allowMaterials,
        allowArtifacts = allowArtifacts && ceiling.allowArtifacts,
        allowSearch = allowSearch && ceiling.allowSearch,
        allowFetch = allowFetch && ceiling.allowFetch,
        allowProposeChanges = allowProposeChanges && ceiling.allowProposeChanges,
        allowPrint = allowPrint && ceiling.allowPrint,
        maxRunMillis = minOf(maxRunMillis, ceiling.maxRunMillis),
        maxOutputBytes = minOf(maxOutputBytes, ceiling.maxOutputBytes),
        maxArtifactBytes = minOf(maxArtifactBytes, ceiling.maxArtifactBytes),
        maxChangeBytes = minOf(maxChangeBytes, ceiling.maxChangeBytes),
        allowAutoSelect = allowAutoSelect && ceiling.allowAutoSelect,
    )

    override fun toString(): String =
        "WorkspaceSkillPolicy(materials=$allowMaterials, artifacts=$allowArtifacts, " +
            "search=$allowSearch, fetch=$allowFetch, propose=$allowProposeChanges, " +
            "print=$allowPrint, autoSelect=$allowAutoSelect)"

    companion object {
        const val MIN_RUN_MILLIS: Int = 100
        const val DEFAULT_MAX_RUN_MILLIS: Int = 30_000
        const val DEFAULT_MAX_OUTPUT_BYTES: Int = 256 * 1024
        const val DEFAULT_MAX_ARTIFACT_BYTES: Int = 256 * 1024
        const val DEFAULT_MAX_CHANGE_BYTES: Int = 128 * 1024

        val MAX_RUN_MILLIS: Int = WorkspaceHardLimits.MAX_TIMEOUT_MILLIS.toInt()
        val MAX_OUTPUT_BYTES: Int = WorkspaceHardLimits.MAX_OUTPUT_BYTES.toInt()
        val MAX_ARTIFACT_BYTES: Int = WorkspaceHardLimits.MAX_ARTIFACT_BYTES.toInt()
        val MAX_CHANGE_BYTES: Int = WorkspaceHardLimits.MAX_CHANGE_BYTES.toInt()

        /** Safe policy for legacy metadata and for skills created without an explicit policy. */
        val DEFAULT: WorkspaceSkillPolicy = WorkspaceSkillPolicy()
    }
}

/**
 * Skill metadata only. [contentPath] and [contentSha256] locate the real SKILL.md and
 * references; the registry never loads their bodies. A body is read only on explicit
 * reference through [WorkspaceSkillDocumentPort].
 */
class WorkspaceSkillSummary(
    val id: WorkspaceSkillId,
    val name: String,
    val description: String,
    val enabled: Boolean,
    val order: Int,
    val source: WorkspaceSkillSource,
    languages: Set<String> = emptySet(),
    tags: Set<String> = emptySet(),
    val contentPath: WorkspacePath? = null,
    val contentSha256: String? = null,
    val policy: WorkspaceSkillPolicy = WorkspaceSkillPolicy.DEFAULT,
) {
    val languages: Set<String> = workspaceFrozenSet(languages.map { it.trim().lowercase(Locale.ROOT) }.toSet())
    val tags: Set<String> = workspaceFrozenSet(tags.map { it.trim().lowercase(Locale.ROOT) }.toSet())

    init {
        requireNormalizedName(name)
        require(name.length <= 128) { "Skill name too long" }
        require(description.length <= 1_024) { "Skill description too long" }
        require(description.none { it.isISOControl() }) { "Control character in skill description" }
        requireWellFormedUnicode(description)
        require(order >= 0) { "Invalid skill order" }
        require(this.languages.all { it.isNotEmpty() && it.length <= 32 }) { "Invalid skill language" }
        require(this.tags.all { it.isNotEmpty() && it.length <= 64 }) { "Invalid skill tag" }
        require((contentPath == null) == (contentSha256 == null)) {
            "Skill content path and fingerprint belong together"
        }
        if (contentSha256 != null) requireSha256Hex(contentSha256)
    }

    fun withEnabled(value: Boolean): WorkspaceSkillSummary = WorkspaceSkillSummary(
        id, name, description, value, order, source, languages, tags, contentPath, contentSha256, policy,
    )

    fun withOrder(value: Int): WorkspaceSkillSummary = WorkspaceSkillSummary(
        id, name, description, enabled, value, source, languages, tags, contentPath, contentSha256, policy,
    )

    /** Case-folded key used only for conflict detection, never for stored display. */
    val conflictNameKey: String get() = Normalizer.normalize(name.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    override fun toString(): String =
        "WorkspaceSkillSummary(id=${id.value}, name=$name, source=$source, enabled=$enabled, order=$order)"

    private companion object {
        fun requireNormalizedName(value: String) {
            require(value.isNotBlank()) { "Empty skill name" }
            require(value == value.trim()) { "Skill name has outer whitespace" }
            require(value == Normalizer.normalize(value, Normalizer.Form.NFC)) { "Skill name is not NFC" }
            require(value.none { it.isISOControl() }) { "Control character in skill name" }
            requireWellFormedUnicode(value)
        }
    }
}

/**
 * Explicit skill body access. Implementations read from the versioned skill directory and
 * enforce [maxChars]; the default capacity keeps a model-visible SKILL.md bounded.
 */
interface WorkspaceSkillDocumentPort {
    suspend fun read(id: WorkspaceSkillId, maxChars: Int = 256 * 1024): String?
}

enum class WorkspaceSkillConflictReason { DUPLICATE_ID, DUPLICATE_NAME }

class WorkspaceSkillConflict(
    val reason: WorkspaceSkillConflictReason,
    val existing: WorkspaceSkillId,
    val incoming: WorkspaceSkillId,
    val name: String,
) {
    override fun toString(): String =
        "WorkspaceSkillConflict($reason, existing=${existing.value}, incoming=${incoming.value})"
}

sealed interface WorkspaceSkillRegistration {
    class Registered(val registry: SkillRegistry) : WorkspaceSkillRegistration
    class Conflicted(conflicts: List<WorkspaceSkillConflict>) : WorkspaceSkillRegistration {
        val conflicts: List<WorkspaceSkillConflict> = workspaceFrozenList(conflicts)
    }
}

sealed interface WorkspaceSkillMutation {
    class Updated(val registry: SkillRegistry) : WorkspaceSkillMutation
    class NotFound(val id: WorkspaceSkillId) : WorkspaceSkillMutation
}

/**
 * Immutable, sorted skill catalog. Ordering is `(order, case-folded name, id)` so the
 * display order stays deterministic after enable/disable or reordering. Registering a
 * duplicate ID or a duplicate display name is refused instead of silently overriding a
 * built-in skill; user-override semantics remain an R21 adapter task.
 */
class SkillRegistry private constructor(skills: List<WorkspaceSkillSummary>) {
    val skills: List<WorkspaceSkillSummary> = workspaceFrozenList(
        skills.sortedWith(
            compareBy<WorkspaceSkillSummary>({ it.order }, { it.conflictNameKey }, { it.id.value }),
        ),
    )

    fun find(id: WorkspaceSkillId): WorkspaceSkillSummary? = skills.firstOrNull { it.id == id }

    fun list(
        enabledOnly: Boolean = false,
        language: String? = null,
        tag: String? = null,
    ): List<WorkspaceSkillSummary> {
        val normalizedLanguage = language?.trim()?.lowercase(Locale.ROOT)
        val normalizedTag = tag?.trim()?.lowercase(Locale.ROOT)
        require(language == null || normalizedLanguage!!.isNotEmpty()) { "Empty language filter" }
        require(tag == null || normalizedTag!!.isNotEmpty()) { "Empty tag filter" }
        return workspaceFrozenList(skills.filter { skill ->
            (!enabledOnly || skill.enabled) &&
                (normalizedLanguage == null || skill.languages.isEmpty() || normalizedLanguage in skill.languages) &&
                (normalizedTag == null || normalizedTag in skill.tags)
        })
    }

    fun register(skill: WorkspaceSkillSummary): WorkspaceSkillRegistration {
        val conflicts = ArrayList<WorkspaceSkillConflict>(2)
        skills.forEach { existing ->
            if (existing.id == skill.id) {
                conflicts += WorkspaceSkillConflict(
                    WorkspaceSkillConflictReason.DUPLICATE_ID, existing.id, skill.id, skill.name,
                )
            } else if (existing.conflictNameKey == skill.conflictNameKey) {
                conflicts += WorkspaceSkillConflict(
                    WorkspaceSkillConflictReason.DUPLICATE_NAME, existing.id, skill.id, skill.name,
                )
            }
        }
        if (conflicts.isNotEmpty()) return WorkspaceSkillRegistration.Conflicted(conflicts)
        val updated = ArrayList<WorkspaceSkillSummary>(skills.size + 1)
        updated += skills
        updated += skill
        return WorkspaceSkillRegistration.Registered(SkillRegistry(updated))
    }

    fun setEnabled(id: WorkspaceSkillId, enabled: Boolean): WorkspaceSkillMutation {
        if (skills.none { it.id == id }) return WorkspaceSkillMutation.NotFound(id)
        return WorkspaceSkillMutation.Updated(
            SkillRegistry(skills.map { if (it.id == id) it.withEnabled(enabled) else it }),
        )
    }

    fun setOrder(id: WorkspaceSkillId, order: Int): WorkspaceSkillMutation {
        require(order >= 0) { "Invalid skill order" }
        if (skills.none { it.id == id }) return WorkspaceSkillMutation.NotFound(id)
        return WorkspaceSkillMutation.Updated(
            SkillRegistry(skills.map { if (it.id == id) it.withOrder(order) else it }),
        )
    }

    override fun toString(): String = "SkillRegistry(skills=${skills.size})"

    companion object {
        val EMPTY: SkillRegistry = SkillRegistry(emptyList())

        /** Registers all skills in order; the first conflict aborts with that conflict. */
        fun of(skills: List<WorkspaceSkillSummary>): WorkspaceSkillRegistration {
            var registry = EMPTY
            skills.forEach { skill ->
                when (val result = registry.register(skill)) {
                    is WorkspaceSkillRegistration.Registered -> registry = result.registry
                    is WorkspaceSkillRegistration.Conflicted -> return result
                }
            }
            return WorkspaceSkillRegistration.Registered(registry)
        }
    }
}
