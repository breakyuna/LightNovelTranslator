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
        id, name, description, value, order, source, languages, tags, contentPath, contentSha256,
    )

    fun withOrder(value: Int): WorkspaceSkillSummary = WorkspaceSkillSummary(
        id, name, description, enabled, value, source, languages, tags, contentPath, contentSha256,
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
