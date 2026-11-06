package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import java.util.Collections

/** Duplicate identity is either per asset (native default) or project-wide. */
enum class TranslationDuplicateScope { SAME_FILE, PROJECT }

/**
 * Exact-text duplicate identity. The text is never trimmed, case-folded or normalized; two rows
 * are duplicates only when their source strings are byte-for-byte equal. The class suppresses
 * the generated toString so a source body cannot leak through diagnostics.
 */
class TranslationDuplicateKey private constructor(
    val scope: TranslationDuplicateScope,
    val assetId: Long?,
    private val text: String,
) {
    val sourceLength: Int get() = text.length

    init {
        require(text.length <= 65_536) { "Duplicate key exceeds the item limit" }
        require('\u0000' !in text) { "Duplicate key contains a NUL" }
        require((scope == TranslationDuplicateScope.SAME_FILE) == (assetId != null)) {
            "Only a same-file key carries an asset identity"
        }
        require(assetId == null || assetId >= 1) { "Asset identity starts at 1" }
    }

    override fun equals(other: Any?): Boolean = other is TranslationDuplicateKey &&
        scope == other.scope && assetId == other.assetId && text == other.text

    override fun hashCode(): Int = java.util.Objects.hash(scope, assetId, text)
    override fun toString(): String =
        "TranslationDuplicateKey(scope=$scope, asset=$assetId, length=$sourceLength)"

    companion object {
        fun of(scope: TranslationDuplicateScope, assetId: Long, source: String): TranslationDuplicateKey {
            require(assetId >= 1) { "Asset identity starts at 1" }
            return TranslationDuplicateKey(
                scope,
                if (scope == TranslationDuplicateScope.SAME_FILE) assetId else null,
                source,
            )
        }
    }
}

/** Coordinator-owned status transitions only; every other status is left to its owner. */
data class TranslationDuplicateChange(val itemId: ItemId, val from: TextItemStatus, val to: TextItemStatus) {
    init {
        require(from == TextItemStatus.NONE || from == TextItemStatus.DUPLICATED) {
            "The coordinator only owns NONE and DUPLICATED"
        }
        require(to == TextItemStatus.NONE || to == TextItemStatus.DUPLICATED) {
            "The coordinator only owns NONE and DUPLICATED"
        }
        require(from != to) { "A change must change the status" }
    }
}

/**
 * One exact-text duplicate group. [primaryId] is the row whose committed translation may be
 * reused; [derivedIds] must not be sent to the model while the primary is valid. Skipped or
 * excluded rows never become anchors.
 */
class TranslationDuplicateGroup internal constructor(
    val key: TranslationDuplicateKey,
    anchorIds: List<ItemId>,
    candidateIds: List<ItemId>,
    val primaryId: ItemId?,
    derivedIds: List<ItemId>,
) {
    val anchorIds: List<ItemId> = Collections.unmodifiableList(ArrayList(anchorIds))
    val candidateIds: List<ItemId> = Collections.unmodifiableList(ArrayList(candidateIds))
    val derivedIds: List<ItemId> = Collections.unmodifiableList(ArrayList(derivedIds))
    val memberIds: List<ItemId> = Collections.unmodifiableList(
        (this.anchorIds + this.candidateIds).sortedBy { it.value },
    )

    init {
        require(this.anchorIds.isNotEmpty() || this.candidateIds.isNotEmpty()) { "Empty duplicate group" }
        require(this.anchorIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Anchors must be sorted and duplicate-free"
        }
        require(this.candidateIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Candidates must be sorted and duplicate-free"
        }
        val anchors = this.anchorIds.toHashSet()
        val candidates = this.candidateIds.toHashSet()
        val derived = this.derivedIds.toHashSet()
        require(anchors.intersect(candidates).isEmpty()) { "An anchor is not a candidate" }
        require(candidates.containsAll(derived)) { "A derived row must be a candidate" }
        require(this.derivedIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Derived rows must be sorted and duplicate-free"
        }
        require(derived.size == this.derivedIds.size) { "Duplicate derived rows" }
        val primary = primaryId
        require(primary == null || primary in anchors || primary in candidates) {
            "The primary must belong to its group"
        }
        require(primary == null || primary !in derived) { "A derived row cannot be primary" }
        if (anchors.isNotEmpty()) require(primary == this.anchorIds.first()) {
            "An existing committed row represents its group"
        } else if (primary != null) require(primary == this.candidateIds.first()) {
            "The smallest pending row becomes the primary"
        }
    }
}

/**
 * Pure coordination result. It is a decision list, not a mutation: the caller applies [changes]
 * through the same project write path as any other status edit and must handle conflicts there.
 */
class TranslationDuplicatePlan(
    val enabled: Boolean,
    val scope: TranslationDuplicateScope,
    groups: List<TranslationDuplicateGroup>,
    changes: List<TranslationDuplicateChange>,
    val inspected: Int,
) {
    val groups: List<TranslationDuplicateGroup> = Collections.unmodifiableList(ArrayList(groups))
    val changes: List<TranslationDuplicateChange> = Collections.unmodifiableList(ArrayList(changes))
    private val changeById: Map<ItemId, TranslationDuplicateChange> by lazy { this.changes.associateBy { it.itemId } }
    private val representatives: Map<ItemId, ItemId> = buildMap {
        for (group in this@TranslationDuplicatePlan.groups) {
            val primary = group.primaryId ?: continue
            for (member in group.memberIds) put(member, primary)
        }
    }
    private val derivedByPrimary: Map<ItemId, List<ItemId>> = buildMap {
        for (group in this@TranslationDuplicatePlan.groups) {
            val primary = group.primaryId ?: continue
            if (group.derivedIds.isNotEmpty()) put(primary, group.derivedIds)
        }
    }

    init {
        require(inspected >= 0) { "Inspected count must not be negative" }
        val changed = HashSet<ItemId>(this.changes.size)
        for (change in this.changes) require(changed.add(change.itemId)) { "An item changes twice" }
    }

    /** Current row status after applying this plan's decisions. */
    fun statusAfter(itemId: ItemId, current: TextItemStatus): TextItemStatus {
        val change = changeById[itemId] ?: return current
        if (current == change.to) return current
        require(current == change.from) { "The plan was computed from a different status" }
        return change.to
    }

    /** Representative row for a group member, or null when the row is not part of a group. */
    fun primaryFor(itemId: ItemId): ItemId? = representatives[itemId]

    /** Rows that currently inherit [primaryId]'s translation. */
    fun derivedFor(primaryId: ItemId): List<ItemId> = derivedByPrimary[primaryId] ?: emptyList()

    override fun toString(): String =
        "TranslationDuplicatePlan(enabled=$enabled, scope=$scope, groups=${groups.size}, changes=${changes.size})"
}

/**
 * Ports LG's passive duplicate coordinator. It never edits the input list and never touches
 * statuses other than NONE/DUPLICATED. A row that becomes PROCESSED or ERROR anchors its group;
 * if the anchor later loses that status, the next smallest pending row is promoted instead.
 */
object TranslationDeduplication {
    fun coordinate(
        items: List<TranslationItemSnapshot>,
        scope: TranslationDuplicateScope = TranslationDuplicateScope.SAME_FILE,
        enabled: Boolean = true,
        affectedItemIds: Set<ItemId>? = null,
    ): TranslationDuplicatePlan {
        require(items.size <= TranslationCommandScope.MAX_ITEMS) { "Duplicate scan exceeds the project item limit" }
        require(items.map { it.id }.toSet().size == items.size) { "Duplicate item identities" }

        val groups = LinkedHashMap<TranslationDuplicateKey, MutableGroup>()
        for (item in items) {
            val key = TranslationDuplicateKey.of(scope, item.assetId, item.source)
            val group = groups.getOrPut(key) { MutableGroup() }
            when (item.status) {
                TextItemStatus.NONE, TextItemStatus.DUPLICATED -> group.candidates.add(item.id)
                TextItemStatus.PROCESSED, TextItemStatus.ERROR -> group.anchors.add(item.id)
                // EXCLUDED/RULE_SKIPPED/LANGUAGE_SKIPPED belong to another owner and never anchor
                // a group; otherwise a filtered row could hide a translatable duplicate forever.
                TextItemStatus.EXCLUDED,
                TextItemStatus.RULE_SKIPPED,
                TextItemStatus.LANGUAGE_SKIPPED -> Unit
            }
        }

        val statusById = HashMap<ItemId, TextItemStatus>(items.size)
        for (item in items) statusById[item.id] = item.status

        val affectedKeys: Set<TranslationDuplicateKey>? = affectedItemIds?.let { affected ->
            val keys = HashSet<TranslationDuplicateKey>()
            for (item in items) if (item.id in affected) keys.add(TranslationDuplicateKey.of(scope, item.assetId, item.source))
            keys
        }

        val nextGroups = ArrayList<TranslationDuplicateGroup>()
        val changes = ArrayList<TranslationDuplicateChange>()
        for ((key, group) in groups) {
            if (affectedKeys != null && key !in affectedKeys) continue
            group.anchors.sortBy { it.value }
            group.candidates.sortBy { it.value }
            val needsRestore = group.candidates.any { statusById.getValue(it) == TextItemStatus.DUPLICATED }
            // A lone pending row is not a duplicate unless it was derived from a primary that has
            // since disappeared; that row must still be restored instead of staying DUPLICATED.
            if (group.anchors.size + group.candidates.size < 2 && !needsRestore) continue

            val anchor = group.anchors.firstOrNull()
            val primary = anchor ?: group.candidates.firstOrNull()
            val derived = when {
                !enabled -> emptyList<ItemId>()
                anchor != null -> group.candidates
                else -> group.candidates.drop(1)
            }
            val derivedSet = derived.toHashSet()
            nextGroups.add(TranslationDuplicateGroup(key, group.anchors, group.candidates, primary, derived))

            for (candidate in group.candidates) {
                val current = statusById.getValue(candidate)
                val target = if (candidate in derivedSet) TextItemStatus.DUPLICATED else TextItemStatus.NONE
                if (current != target) changes.add(TranslationDuplicateChange(candidate, current, target))
            }
        }
        changes.sortBy { it.itemId.value }
        return TranslationDuplicatePlan(enabled, scope, nextGroups, changes, items.size)
    }

    private class MutableGroup {
        val anchors = ArrayList<ItemId>()
        val candidates = ArrayList<ItemId>()
    }
}
