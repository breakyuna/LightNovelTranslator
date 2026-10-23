package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class QualityScanLimits(val characters: Long = 16_777_216L, val resultEntries: Int = 250_000) {
    init { require(characters in 1..16_777_216L && resultEntries in 1..250_000) }
}

/** Revision-fenced paged reads; a changed project or failed item never publishes a partial index. */
class ProjectQualityScanner(private val projects: TextProjectPort, private val policies: ProjectTextPolicyPort,
    private val limits: QualityScanLimits = QualityScanLimits()) {
    suspend fun scan(ref: LocalProjectRef, expectedRevision: Long, query: TextItemQuery,
        options: TextProofreadingOptions, progress: (TextQualityScanProgress) -> Unit = {}): TextQualityIndex = withContext(Dispatchers.Default) {
        val context = currentCoroutineContext()
        val summary = projects.open(ref)
        val snapshot = policies.textPolicy(ref)
        if (summary.revision != expectedRevision || snapshot.revision != expectedRevision || snapshot.ref != ref)
            stale()
        TextRuleValidation.validate(snapshot.policy) { context.ensureActive() }
        val inspector = TextProofreadingInspector(snapshot.policy) { context.ensureActive() }
        val facts = ArrayList<TextQualityFact>()
        var after = 0L; var scanned = 0; var characters = 0L; var entries = 0
        progress(TextQualityScanProgress(0, summary.itemCount))
        while (true) {
            context.ensureActive()
            val page = projects.items(ref, expectedRevision, after, 20)
            if (page.revision != expectedRevision || page.items.size > 20) stale()
            if (page.items.isEmpty()) break
            for (item in page.items) {
                context.ensureActive()
                if (item.id.value <= after || ++scanned > summary.itemCount) stale()
                after = item.id.value
                characters += item.source.length.toLong() + item.translation.length
                if (characters > limits.characters) limit()
                if (!matches(item, query)) continue
                val result = inspector.inspect(item.source, item.translation, item.status, options)
                entries += result.codes.size + result.missingTermRuleIds.size
                if (entries > limits.resultEntries) limit()
                facts.add(TextQualityFact(item.id, item.fingerprint, result))
            }
            progress(TextQualityScanProgress(scanned, summary.itemCount))
        }
        context.ensureActive()
        if (scanned != summary.itemCount || projects.open(ref).revision != expectedRevision) stale()
        val index = TextQualityIndex(ref, expectedRevision, query, options, snapshot.fingerprint, facts, scanned)
        context.ensureActive()
        index
    }

    suspend fun page(selection: TextQualitySelection, afterId: Long = 0, limit: Int = 41): TextProjectPage {
        val index = selection.index
        val ids = selection.window(afterId, limit)
        val result = if (ids.isEmpty()) TextProjectPage(projects.open(index.ref), emptyList(), matchingItems = selection.ids.size)
            else projects.readItemWindow(index.ref, index.query, TextItemWindow(ids))
        if (result.summary.revision != index.revision || result.summary.ref != index.ref || result.items.map { it.id } != ids) stale()
        for (item in result.items) if (index.fact(item.id)?.fingerprint != item.fingerprint) stale()
        return TextProjectPage(result.summary, result.items, result.statistics, selection.ids.size)
    }

    private fun matches(item: TextProjectItem, query: TextItemQuery): Boolean {
        if (query.status != null && query.status != item.status) return false
        if (query.text.isEmpty()) return true
        return when (query.field) {
            TextSearchField.SOURCE -> item.source.contains(query.text)
            TextSearchField.TRANSLATION -> item.translation.contains(query.text)
            TextSearchField.BOTH -> item.source.contains(query.text) || item.translation.contains(query.text)
        }
    }
    private fun stale(): Nothing = throw TextProjectException(TextProjectException.Reason.STALE_PAGE)
    private fun limit(): Nothing = throw TextProjectException(TextProjectException.Reason.LIMIT_EXCEEDED)
}
