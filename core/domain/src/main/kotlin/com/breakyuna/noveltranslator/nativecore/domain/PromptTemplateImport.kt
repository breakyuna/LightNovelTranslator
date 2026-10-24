package com.breakyuna.noveltranslator.nativecore.domain

enum class PromptTemplateImportMode { MERGE, UPDATE, REPLACE }
enum class PromptTemplateImportDisposition { ADDED, UPDATED, UNCHANGED, REMOVED, CONFLICT }

class PromptTemplateImportChange(val id: String, val disposition: PromptTemplateImportDisposition,
    val before: PromptTemplate?, val after: PromptTemplate?) {
    override fun toString() = "PromptTemplateImportChange(disposition=$disposition)"
}

/** Frozen library-to-library difference; never renders a prompt or writes a project. */
class PromptTemplateImportPreview(
    val expected: String, val templates: PromptTemplateSet,
    val added: Int, val updated: Int, val unchanged: Int, val removed: Int, val conflicts: Int,
    changes: List<PromptTemplateImportChange>,
) {
    val changes: List<PromptTemplateImportChange> = frozenList(changes)
    val clean: Boolean get() = conflicts == 0
    override fun toString() = "PromptTemplateImportPreview(added=$added, updated=$updated, unchanged=$unchanged, removed=$removed, conflicts=$conflicts)"
}

fun PromptTemplateSet.previewImport(incoming: PromptTemplateSet,
    mode: PromptTemplateImportMode = PromptTemplateImportMode.MERGE): PromptTemplateImportPreview {
    val existing = templates.associateBy { it.id }
    val next: LinkedHashMap<String, PromptTemplate> = if (mode == PromptTemplateImportMode.REPLACE) linkedMapOf() else LinkedHashMap(existing)
    var added = 0; var updated = 0; var unchanged = 0; var conflicts = 0
    val changes = ArrayList<PromptTemplateImportChange>()
    fun fingerprint(template: PromptTemplate) = PromptTemplateCodec.fingerprint(PromptTemplateSet(listOf(template)))
    for (template in incoming.templates) {
        val old = existing[template.id]
        val disposition = when {
            old == null -> { next[template.id] = template; added++; PromptTemplateImportDisposition.ADDED }
            fingerprint(old) == fingerprint(template) -> { next[template.id] = old; unchanged++; PromptTemplateImportDisposition.UNCHANGED }
            mode == PromptTemplateImportMode.MERGE -> { conflicts++; PromptTemplateImportDisposition.CONFLICT }
            else -> { next[template.id] = template; updated++; PromptTemplateImportDisposition.UPDATED }
        }
        changes.add(PromptTemplateImportChange(template.id, disposition, old, template))
    }
    val removed = if (mode == PromptTemplateImportMode.REPLACE) existing.keys.count { key -> incoming.templates.none { it.id == key } } else 0
    if (mode == PromptTemplateImportMode.REPLACE) for ((id, old) in existing) {
        if (incoming.templates.none { it.id == id }) changes.add(PromptTemplateImportChange(id, PromptTemplateImportDisposition.REMOVED, old, null))
    }
    return PromptTemplateImportPreview(PromptTemplateCodec.fingerprint(this), PromptTemplateSet(next.values.toList()),
        added, updated, unchanged, removed, conflicts, changes)
}
