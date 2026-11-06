package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import java.util.Collections

/**
 * User-visible batch start mode. The mode decides which statuses are eligible, never the UI.
 *
 * NEW starts a fresh pass over untouched rows; previously failed rows need an explicit
 * CONTINUE or RESET so a fresh run cannot silently pay for the same failures again.
 * CONTINUE also finishes rows that ended in ERROR. RESET re-opens every translatable row,
 * including already translated rows, and clears their committed translation on write.
 */
enum class TranslationRunMode { NEW, CONTINUE, RESET }

enum class TranslationCommandScopeKind { ALL, ITEMS }

/** What the write phase must do with a selected row. RESET clears the old translation first. */
enum class TranslationTargetAction { TRANSLATE, RETRANSLATE }

/** Why a row of the snapshot is not part of this command's target set. */
enum class TranslationSelectionReason {
    NOT_IN_SCOPE,
    FAILED_ITEM,
    ALREADY_TRANSLATED,
    EXCLUDED,
    RULE_SKIPPED,
    LANGUAGE_SKIPPED,
    DUPLICATED,
}

enum class TranslationModeStatus {
    APPLICABLE,
    STALE_IDENTITY,
    STALE_REVISION,
    UNKNOWN_SCOPE_ITEMS,
}

/**
 * Frozen scope. An ITEMS scope stores a sorted, duplicate-free identity list so a later caller
 * mutation, list reorder or re-read of the project cannot widen or reshuffle the command.
 */
class TranslationCommandScope private constructor(
    val kind: TranslationCommandScopeKind,
    ids: List<ItemId>,
) {
    private val storedIds: List<ItemId> = Collections.unmodifiableList(ArrayList(ids))
    private val membership: Set<ItemId>? =
        if (kind == TranslationCommandScopeKind.ITEMS) Collections.unmodifiableSet(LinkedHashSet(storedIds)) else null

    val itemIds: List<ItemId> get() = storedIds

    init {
        require(storedIds.size <= MAX_ITEMS) { "Translation scope exceeds the project item limit" }
        require(kind == TranslationCommandScopeKind.ITEMS || storedIds.isEmpty()) {
            "An ALL scope carries no item identities"
        }
        require(storedIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "An item scope must be sorted and duplicate-free"
        }
    }

    fun contains(id: ItemId): Boolean = membership?.contains(id) ?: true

    override fun equals(other: Any?): Boolean =
        other is TranslationCommandScope && kind == other.kind && storedIds == other.storedIds

    override fun hashCode(): Int = java.util.Objects.hash(kind, storedIds)
    override fun toString(): String = "TranslationCommandScope(kind=$kind, items=${storedIds.size})"

    companion object {
        const val MAX_ITEMS = 100_000

        fun all(): TranslationCommandScope = TranslationCommandScope(TranslationCommandScopeKind.ALL, emptyList())

        fun items(ids: Iterable<ItemId>): TranslationCommandScope {
            val sorted = ArrayList<ItemId>()
            for (id in ids) {
                sorted.add(id)
                require(sorted.size <= MAX_ITEMS) { "Translation scope exceeds the project item limit" }
            }
            require(sorted.isNotEmpty()) { "An item scope requires at least one item" }
            sorted.sortWith(compareBy<ItemId> { it.value })
            require(sorted.zipWithNext().all { (left, right) -> left.value < right.value }) {
                "An item scope contains duplicate identities"
            }
            return TranslationCommandScope(TranslationCommandScopeKind.ITEMS, sorted)
        }
    }
}

/** One immutable project row. Bodies never enter the generated diagnostics of this class. */
class TranslationItemSnapshot(
    val id: ItemId,
    val assetId: Long,
    val row: Int,
    val source: String,
    val status: TextItemStatus,
    val fingerprint: ObjectFingerprint,
) {
    init {
        require(assetId >= 1) { "Asset identity starts at 1" }
        require(row >= 0) { "Row must not be negative" }
        require(source.length <= MAX_SOURCE_CHARS) { "Item source exceeds the project limit" }
        require('\u0000' !in source) { "Item source contains a NUL" }
        TextProjectFingerprint.utf8(source)
    }

    override fun toString(): String =
        "TranslationItemSnapshot(id=${id.value}, asset=$assetId, row=$row, status=$status)"

    companion object { const val MAX_SOURCE_CHARS = 65_536 }
}

/**
 * Read-only project snapshot for one command. The revision is the read transaction the
 * caller observed; planning never re-reads or writes the project.
 */
class TranslationTargetSnapshot(
    val ref: LocalProjectRef,
    val revision: Long,
    items: List<TranslationItemSnapshot>,
) {
    val items: List<TranslationItemSnapshot> = Collections.unmodifiableList(ArrayList(items))
    private val index: Map<ItemId, TranslationItemSnapshot> by lazy { this.items.associateBy { it.id } }

    init {
        require(revision >= 0) { "Revision must not be negative" }
        require(this.items.size <= TranslationCommandScope.MAX_ITEMS) { "Snapshot exceeds the project item limit" }
        require(this.items.map { it.id }.toSet().size == this.items.size) {
            "Snapshot contains duplicate item identities"
        }
    }

    fun item(id: ItemId): TranslationItemSnapshot? = index[id]
}

data class TranslationTarget(val id: ItemId, val action: TranslationTargetAction)

data class TranslationSelectionExclusion(val id: ItemId, val reason: TranslationSelectionReason)

/** Pure result of [TranslationModeCommand.applyTo]; it grants no write authorization by itself. */
class TranslationModeResult(
    val mode: TranslationRunMode,
    val expectedRevision: Long,
    val scope: TranslationCommandScope,
    val status: TranslationModeStatus,
    targets: List<TranslationTarget>,
    exclusions: List<TranslationSelectionExclusion>,
    unknownScopeItemIds: List<ItemId> = emptyList(),
) {
    val targets: List<TranslationTarget> = Collections.unmodifiableList(ArrayList(targets))
    val exclusions: List<TranslationSelectionExclusion> = Collections.unmodifiableList(ArrayList(exclusions))
    val unknownScopeItemIds: List<ItemId> = Collections.unmodifiableList(ArrayList(unknownScopeItemIds))
    private val targetIndex: Map<ItemId, TranslationTarget> by lazy { this.targets.associateBy { it.id } }

    init {
        val targetIds = this.targets.mapTo(HashSet(this.targets.size)) { it.id }
        val exclusionIds = this.exclusions.mapTo(HashSet(this.exclusions.size)) { it.id }
        require(targetIds.size == this.targets.size) { "Duplicate targets" }
        require(exclusionIds.size == this.exclusions.size) { "Duplicate exclusions" }
        require(targetIds.none { it in exclusionIds }) { "A row cannot be both targeted and excluded" }
        require(this.unknownScopeItemIds.zipWithNext().all { (left, right) -> left.value < right.value }) {
            "Unknown scope identities must be sorted and duplicate-free"
        }
        require((status == TranslationModeStatus.UNKNOWN_SCOPE_ITEMS) == this.unknownScopeItemIds.isNotEmpty()) {
            "Only an unknown-scope refusal names missing identities"
        }
        if (status != TranslationModeStatus.APPLICABLE) require(this.targets.isEmpty()) {
            "A refused command selects nothing"
        }
    }

    fun target(id: ItemId): TranslationTarget? = targetIndex[id]
    fun isTargeted(id: ItemId): Boolean = targetIndex.containsKey(id)
}

/**
 * One batch command. [expectedRevision] binds the target set to an observed project revision;
 * identity is checked first so a snapshot restored from another instance is never planned.
 */
class TranslationModeCommand(
    val ref: LocalProjectRef,
    val mode: TranslationRunMode,
    val expectedRevision: Long,
    val scope: TranslationCommandScope = TranslationCommandScope.all(),
    val commandId: CommandId? = null,
) {
    init {
        require(expectedRevision >= 0) { "Expected revision must not be negative" }
        require(commandId == null || commandId.value.length <= MAX_COMMAND_ID_LENGTH) {
            "Command identity is too long"
        }
    }

    fun applyTo(snapshot: TranslationTargetSnapshot): TranslationModeResult {
        if (snapshot.ref != ref) return refused(TranslationModeStatus.STALE_IDENTITY)
        if (snapshot.revision != expectedRevision) return refused(TranslationModeStatus.STALE_REVISION)
        if (scope.kind == TranslationCommandScopeKind.ITEMS) {
            val existing = HashSet<ItemId>(snapshot.items.size)
            for (item in snapshot.items) existing.add(item.id)
            val unknown = scope.itemIds.filter { it !in existing }
            if (unknown.isNotEmpty()) return refused(TranslationModeStatus.UNKNOWN_SCOPE_ITEMS, unknown)
        }

        val action =
            if (mode == TranslationRunMode.RESET) TranslationTargetAction.RETRANSLATE else TranslationTargetAction.TRANSLATE
        val targets = ArrayList<TranslationTarget>()
        val exclusions = ArrayList<TranslationSelectionExclusion>()
        for (item in snapshot.items) {
            if (!scope.contains(item.id)) {
                exclusions.add(TranslationSelectionExclusion(item.id, TranslationSelectionReason.NOT_IN_SCOPE))
                continue
            }
            val reason = exclusionReason(item.status)
            if (reason != null) {
                exclusions.add(TranslationSelectionExclusion(item.id, reason))
                continue
            }
            targets.add(TranslationTarget(item.id, action))
        }
        return TranslationModeResult(mode, expectedRevision, scope, TranslationModeStatus.APPLICABLE, targets, exclusions)
    }

    private fun refused(status: TranslationModeStatus, unknown: List<ItemId> = emptyList()): TranslationModeResult =
        TranslationModeResult(mode, expectedRevision, scope, status, emptyList(), emptyList(), unknown)

    private fun exclusionReason(status: TextItemStatus): TranslationSelectionReason? = when (status) {
        TextItemStatus.NONE -> null
        TextItemStatus.ERROR ->
            if (mode == TranslationRunMode.NEW) TranslationSelectionReason.FAILED_ITEM else null

        TextItemStatus.PROCESSED ->
            if (mode == TranslationRunMode.RESET) null else TranslationSelectionReason.ALREADY_TRANSLATED

        TextItemStatus.EXCLUDED -> TranslationSelectionReason.EXCLUDED
        TextItemStatus.RULE_SKIPPED -> TranslationSelectionReason.RULE_SKIPPED
        TextItemStatus.LANGUAGE_SKIPPED -> TranslationSelectionReason.LANGUAGE_SKIPPED
        TextItemStatus.DUPLICATED -> TranslationSelectionReason.DUPLICATED
    }

    companion object { const val MAX_COMMAND_ID_LENGTH = 128 }
}
