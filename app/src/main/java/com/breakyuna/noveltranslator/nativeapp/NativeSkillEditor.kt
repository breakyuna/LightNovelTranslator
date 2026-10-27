package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillLimits
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStoreException
import java.util.Locale

/**
 * UI values for the existing skill draft/update path. The store remains the only skill
 * implementation; this object only parses bounded editor text into [WorkspaceSkillDraft].
 * [policy] is carried unchanged from the selected record so editing the name, body or order
 * never resets grants or budgets to a broader default.
 */
internal data class NativeSkillEditorInput(
    val name: String,
    val description: String,
    val content: String,
    val enabled: Boolean,
    val order: String,
    val languages: String,
    val tags: String,
    val policy: WorkspaceSkillPolicy = WorkspaceSkillPolicy.DEFAULT,
) {
    fun toDraft(id: WorkspaceSkillId): WorkspaceSkillDraft = WorkspaceSkillDraft(
        id = id,
        name = name.trim(),
        content = content,
        description = description.trim(),
        enabled = enabled,
        order = NativeSkillMetadata.parseOrder(order),
        languages = NativeSkillMetadata.parseLabels(
            languages,
            WorkspaceSkillLimits.MAX_LANGUAGES,
            MAX_LANGUAGE_CHARS,
        ),
        tags = NativeSkillMetadata.parseLabels(tags, WorkspaceSkillLimits.MAX_TAGS, MAX_TAG_CHARS),
        policy = policy,
    )

    private companion object {
        const val MAX_LANGUAGE_CHARS = 32
        const val MAX_TAG_CHARS = 64
    }
}

internal object NativeSkillMetadata {
    const val MAX_ORDER = 999_999

    fun parseOrder(raw: String): Int {
        val text = raw.trim()
        require(text.isNotEmpty() && text.length <= 7 && text.all(Char::isDigit)) { "Invalid skill order" }
        val value = text.toIntOrNull() ?: throw IllegalArgumentException("Invalid skill order")
        require(value in 0..MAX_ORDER) { "Invalid skill order" }
        return value
    }

    /** Comma- or line-separated labels, normalized like the store and deduplicated. */
    fun parseLabels(raw: String, maximum: Int, maximumLength: Int): Set<String> {
        val values = LinkedHashSet<String>()
        raw.split(',', '，', ';', '\n', '\r').forEach { part ->
            val label = part.trim().lowercase(Locale.ROOT)
            if (label.isEmpty()) return@forEach
            require(label.length <= maximumLength) { "Skill label too long" }
            require(label.none { it == '\u0000' || it.isISOControl() }) {
                "Control character in skill label"
            }
            values += label
        }
        require(values.size <= maximum) { "Too many skill labels" }
        return values
    }

    fun formatLabels(values: Set<String>): String = values.sorted().joinToString(", ")
}

/** Direct-run network can only narrow the skill policy; it never grants network by itself. */
internal fun nativeDirectExecutionFetchAllowed(
    policy: WorkspaceSkillPolicy,
    requested: Boolean,
): Boolean = requested && policy.allowFetch

/** The direct-run network checkbox is editable only when the stored policy already grants it. */
internal fun nativeSkillNetworkCheckboxEnabled(policy: WorkspaceSkillPolicy): Boolean = policy.allowFetch

internal fun nativeSkillEditorError(failure: Throwable): Int = when {
    failure is WorkspaceSkillStoreException &&
        failure.reason == WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT -> R.string.native_skills_conflict
    failure is WorkspaceSkillStoreException &&
        failure.reason == WorkspaceSkillStoreException.Reason.READ_ONLY -> R.string.native_skills_readonly_error
    failure is IllegalArgumentException ||
        failure is WorkspaceSkillStoreException &&
        (failure.reason == WorkspaceSkillStoreException.Reason.INVALID_METADATA ||
            failure.reason == WorkspaceSkillStoreException.Reason.TOO_LARGE) -> R.string.native_skills_metadata_invalid
    else -> R.string.native_skills_error
}
