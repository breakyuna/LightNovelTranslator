package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import java.security.MessageDigest
import java.util.Collections

/** How a token count was obtained. A unit is exact only when every contributing count is exact. */
enum class TokenCountAccuracy { EXACT, ESTIMATED }

data class TranslationTokenMeasurement(val tokens: Long, val accuracy: TokenCountAccuracy) {
    init {
        require(tokens in 0L..MAX_TOKENS) { "Token count is out of range" }
    }

    companion object { const val MAX_TOKENS = 100_000_000L }
}

/**
 * Pure token counter supplied by the caller. The planner has no tokenizer dependency and
 * never falls back to a network/service when a count is missing.
 */
fun interface TranslationTokenCounter {
    fun measure(source: String): TranslationTokenMeasurement
}

/**
 * Conservative character heuristic for the first slice. One estimated token per non-empty
 * character overestimates Latin text but avoids the dangerous CJK underestimate of a /4 rule,
 * and always reports ESTIMATED.
 */
object EstimatedTranslationTokenCounter : TranslationTokenCounter {
    override fun measure(source: String): TranslationTokenMeasurement =
        TranslationTokenMeasurement(
            if (source.isEmpty()) 0 else source.length.toLong(),
            TokenCountAccuracy.ESTIMATED,
        )
}

/**
 * Pure source classification provided by the caller (language prefilter, rule engine).
 * Implementations must not perform IO or mutate project state.
 */
interface TranslationSourceGate {
    fun ruleSkip(item: TranslationItemSnapshot): Boolean
    fun languageSkip(item: TranslationItemSnapshot): Boolean
}

object AcceptAllTranslationSourceGate : TranslationSourceGate {
    override fun ruleSkip(item: TranslationItemSnapshot): Boolean = false
    override fun languageSkip(item: TranslationItemSnapshot): Boolean = false
}

/**
 * Fixed planning budgets. Values mirror the transport boundary (1..20 items, at most 65_536
 * request characters) and are deliberately explicit rather than device-measured.
 */
data class TranslationPlanLimits(
    val maxItemsPerUnit: Int = 20,
    val maxCharactersPerUnit: Int = 16_384,
    val maxLinesPerUnit: Int = 256,
    val maxTokensPerUnit: Long = 4_096,
    val precedingLines: Int = 2,
    val precedingCharacters: Int = 2_048,
    val precedingTokens: Long = 1_024,
    val maxItemCharacters: Int = 65_500,
) {
    init {
        require(maxItemsPerUnit in 1..20) { "A unit holds 1..20 items" }
        require(maxCharactersPerUnit in 256..32_768) { "Character budget is 256..32768" }
        require(maxLinesPerUnit in 1..4_096) { "Line budget is 1..4096" }
        require(maxTokensPerUnit in 1L..1_000_000L) { "Token budget is 1..1000000" }
        require(precedingLines in 0..64) { "Preceding context is bounded to 64 lines" }
        require(precedingCharacters in 0..16_384) { "Preceding character budget is 0..16384" }
        require(precedingTokens in 0L..65_536L) { "Preceding token budget is 0..65536" }
        require(maxItemCharacters in 1..65_500) { "Item character limit is 1..65500" }
        require(precedingLines == 0 || precedingCharacters > 0 && precedingTokens > 0) {
            "Enabled preceding context requires positive budgets"
        }
    }
}

enum class TranslationSkipReason {
    EMPTY_SOURCE,
    SOURCE_TOO_LARGE,
    RULE_SKIPPED,
    LANGUAGE_SKIPPED,
}

data class TranslationSkippedItem(val id: ItemId, val reason: TranslationSkipReason)

data class TranslationBudgetUsage(
    val items: Int,
    val characters: Long,
    val lines: Int,
    val tokens: Long,
    val accuracy: TokenCountAccuracy,
) {
    init {
        require(items >= 0 && characters >= 0 && lines >= 0 && tokens >= 0) { "Negative budget usage" }
    }

    fun plus(characters: Long, lines: Int, measurement: TranslationTokenMeasurement): TranslationBudgetUsage {
        require(characters >= 0 && lines >= 0) { "Negative item measurement" }
        return TranslationBudgetUsage(
            Math.addExact(items, 1),
            Math.addExact(this.characters, characters),
            Math.addExact(this.lines, lines),
            Math.addExact(tokens, measurement.tokens),
            if (accuracy == TokenCountAccuracy.EXACT && measurement.accuracy == TokenCountAccuracy.EXACT) {
                TokenCountAccuracy.EXACT
            } else TokenCountAccuracy.ESTIMATED,
        )
    }

    companion object {
        val EMPTY = TranslationBudgetUsage(0, 0, 0, 0, TokenCountAccuracy.EXACT)
    }
}

/** Context-only row. It is never written back and remains within the unit's asset. */
class TranslationPreceding(
    val id: ItemId,
    val row: Int,
    val source: String,
    val fingerprint: ObjectFingerprint,
) {
    init {
        require(row >= 0) { "Row must not be negative" }
        require(source.isNotBlank()) { "A preceding row carries source text" }
    }

    override fun toString(): String = "TranslationPreceding(id=${id.value}, row=$row)"
}

/**
 * One request-sized chunk. All members belong to the same asset; [precedings] are earlier rows
 * from that same asset only. [overBudget] is true only when a single item cannot fit any budget,
 * in which case it is isolated instead of being silently dropped.
 */
class TranslationPlanUnit internal constructor(
    val index: Int,
    val assetId: Long,
    items: List<TranslationItemSnapshot>,
    precedings: List<TranslationPreceding>,
    val usage: TranslationBudgetUsage,
    val contextUsage: TranslationBudgetUsage,
    val overBudget: Boolean,
) {
    val items: List<TranslationItemSnapshot> = Collections.unmodifiableList(ArrayList(items))
    val precedings: List<TranslationPreceding> = Collections.unmodifiableList(ArrayList(precedings))

    init {
        require(index >= 0 && assetId >= 1) { "Invalid unit identity" }
        require(this.items.size in 1..20) { "A unit holds 1..20 items" }
        require(this.items.all { it.assetId == assetId }) { "A unit never mixes assets" }
        require(this.items.map { it.id }.toSet().size == this.items.size) { "Duplicate unit items" }
        require(usage.items == this.items.size) { "Usage item count disagrees with members" }
        require(usage.characters == this.items.sumOf { it.source.length.toLong() }) {
            "Usage character count disagrees with members"
        }
        require(usage.lines == this.items.sumOf { translationLineCount(it.source) }) {
            "Usage line count disagrees with members"
        }
        require(contextUsage.items == this.precedings.size) { "Context count disagrees with precedings" }
        require(contextUsage.characters == this.precedings.sumOf { it.source.length.toLong() }) {
            "Context character count disagrees with precedings"
        }
        require(contextUsage.lines == this.precedings.sumOf { translationLineCount(it.source) }) {
            "Context line count disagrees with precedings"
        }
    }

    fun itemIds(): List<ItemId> = items.map { it.id }
    override fun toString(): String = "TranslationPlanUnit(index=$index, asset=$assetId, items=${items.size})"
}

/**
 * Immutable planning result. A refused mode result produces an empty plan instead of a partial
 * one, so callers cannot accidentally translate a stale revision. The fingerprint covers only
 * identities, budgets, statuses and source object fingerprints; source bodies are never encoded.
 */
class TranslationPlan(
    val selection: TranslationModeResult,
    val limits: TranslationPlanLimits,
    units: List<TranslationPlanUnit>,
    skipped: List<TranslationSkippedItem>,
) {
    val units: List<TranslationPlanUnit> = Collections.unmodifiableList(ArrayList(units))
    val skipped: List<TranslationSkippedItem> = Collections.unmodifiableList(ArrayList(skipped))

    val mode: TranslationRunMode get() = selection.mode
    val expectedRevision: Long get() = selection.expectedRevision
    val status: TranslationModeStatus get() = selection.status
    val targetCount: Int get() = selection.targets.size
    val tokenCount: Long get() = units.sumOf { it.usage.tokens }
    val tokenAccuracy: TokenCountAccuracy
        get() = if (units.all { it.usage.accuracy == TokenCountAccuracy.EXACT }) {
            TokenCountAccuracy.EXACT
        } else TokenCountAccuracy.ESTIMATED

    init {
        require(this.units.withIndex().all { (index, unit) -> unit.index == index }) {
            "Unit indices must be sequential"
        }
        val planned = HashSet<ItemId>()
        for (unit in this.units) for (item in unit.items) {
            require(planned.add(item.id)) { "An item appears twice in one plan" }
        }
        val skippedIds = HashSet<ItemId>()
        for (entry in this.skipped) {
            require(skippedIds.add(entry.id)) { "An item is skipped twice" }
            require(entry.id !in planned) { "An item cannot be both planned and skipped" }
        }
        val targetIds = HashSet<ItemId>(selection.targets.size)
        for (target in selection.targets) targetIds.add(target.id)
        require(planned.size + skippedIds.size == targetIds.size) { "Every target must be planned or skipped" }
        require(targetIds.containsAll(planned) && targetIds.containsAll(skippedIds)) {
            "Planned rows must belong to the command target set"
        }
        if (selection.status != TranslationModeStatus.APPLICABLE) {
            require(this.units.isEmpty() && this.skipped.isEmpty()) { "A refused plan carries no work" }
        }
    }

    fun plannedItemIds(): List<ItemId> = units.flatMap { it.itemIds() }

    private val fingerprintValue: ObjectFingerprint by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        fun feedText(value: String) {
            digest.update(TextProjectFingerprint.utf8(value))
            digest.update(0.toByte())
        }
        fun feedInt(value: Int) = feedText(value.toString())
        fun feedLong(value: Long) = feedText(value.toString())
        fun feedBool(value: Boolean) = feedText(if (value) "1" else "0")

        feedText("lnt-translation-plan")
        feedInt(1)
        feedText(selection.status.name)
        feedText(selection.mode.name)
        feedLong(selection.expectedRevision)
        feedText(selection.scope.kind.name)
        feedInt(selection.scope.itemIds.size)
        for (id in selection.scope.itemIds) feedLong(id.value)
        val targetIds = selection.targets.map { it.id.value }.sorted()
        feedInt(targetIds.size)
        for (id in targetIds) feedLong(id)
        feedInt(limits.maxItemsPerUnit)
        feedInt(limits.maxCharactersPerUnit)
        feedInt(limits.maxLinesPerUnit)
        feedLong(limits.maxTokensPerUnit)
        feedInt(limits.precedingLines)
        feedInt(limits.precedingCharacters)
        feedLong(limits.precedingTokens)
        feedInt(limits.maxItemCharacters)
        feedInt(units.size)
        for (unit in units) {
            feedLong(unit.assetId)
            feedBool(unit.overBudget)
            feedInt(unit.items.size)
            for (item in unit.items) {
                feedLong(item.id.value)
                feedText(item.fingerprint.sha256)
            }
            feedInt(unit.usage.items)
            feedLong(unit.usage.characters)
            feedInt(unit.usage.lines)
            feedLong(unit.usage.tokens)
            feedText(unit.usage.accuracy.name)
            feedInt(unit.precedings.size)
            for (preceding in unit.precedings) {
                feedLong(preceding.id.value)
                feedText(preceding.fingerprint.sha256)
            }
            feedInt(unit.contextUsage.items)
            feedLong(unit.contextUsage.characters)
            feedInt(unit.contextUsage.lines)
            feedLong(unit.contextUsage.tokens)
            feedText(unit.contextUsage.accuracy.name)
        }
        feedInt(skipped.size)
        for (entry in skipped) {
            feedLong(entry.id.value)
            feedText(entry.reason.name)
        }
        ObjectFingerprint(TextProjectFingerprint.bytes(digest.digest()))
    }

    fun fingerprint(): ObjectFingerprint = fingerprintValue
    override fun toString(): String =
        "TranslationPlan(mode=$mode, status=$status, targets=$targetCount, units=${units.size}, skipped=${skipped.size})"
}

/**
 * Deterministic planner over an already-read snapshot. It performs no IO: callers own the
 * snapshot read, the token counter and the source/language gate.
 */
class TranslationPlanner(
    private val limits: TranslationPlanLimits = TranslationPlanLimits(),
    private val gate: TranslationSourceGate = AcceptAllTranslationSourceGate,
    private val tokenCounter: TranslationTokenCounter = EstimatedTranslationTokenCounter,
) {
    fun plan(snapshot: TranslationTargetSnapshot, command: TranslationModeCommand): TranslationPlan {
        val selection = command.applyTo(snapshot)
        if (selection.status != TranslationModeStatus.APPLICABLE) {
            return TranslationPlan(selection, limits, emptyList(), emptyList())
        }

        val targetIds = HashSet<ItemId>(selection.targets.size)
        for (target in selection.targets) targetIds.add(target.id)

        // Group by asset in first-seen asset order, then canonicalize within each file by row/id.
        // This keeps a unit single-file even if the caller's snapshot arrived out of order.
        val byAsset = LinkedHashMap<Long, MutableList<TranslationItemSnapshot>>()
        for (item in snapshot.items) {
            byAsset.getOrPut(item.assetId) { ArrayList() }.add(item)
        }
        for (items in byAsset.values) {
            items.sortWith(compareBy<TranslationItemSnapshot>({ it.row }, { it.id.value }))
        }

        val units = ArrayList<TranslationPlanUnit>()
        val skipped = ArrayList<TranslationSkippedItem>()
        for ((assetId, all) in byAsset) {
            val positions = HashMap<ItemId, Int>(all.size)
            all.forEachIndexed { index, item -> positions[item.id] = index }

            val eligible = ArrayList<TranslationItemSnapshot>()
            for (item in all) {
                if (item.id !in targetIds) continue
                val reason = classify(item)
                if (reason == null) eligible.add(item) else skipped.add(TranslationSkippedItem(item.id, reason))
            }

            var cursor = 0
            while (cursor < eligible.size) {
                val unitItems = ArrayList<TranslationItemSnapshot>()
                var usage = TranslationBudgetUsage.EMPTY
                while (cursor < eligible.size) {
                    val item = eligible[cursor]
                    val measurement = tokenCounter.measure(item.source)
                    val lineCount = translationLineCount(item.source)
                    val candidate = usage.plus(item.source.length.toLong(), lineCount, measurement)
                    val fits = unitItems.isEmpty() || (
                        unitItems.size < limits.maxItemsPerUnit &&
                            candidate.characters <= limits.maxCharactersPerUnit &&
                            candidate.lines <= limits.maxLinesPerUnit &&
                            candidate.tokens <= limits.maxTokensPerUnit
                        )
                    if (!fits) break
                    unitItems.add(item)
                    usage = candidate
                    cursor++
                }
                check(unitItems.isNotEmpty()) { "Planner failed to make progress" }
                val first = unitItems.first()
                val context = precedingContext(all, positions, first)
                val overBudget = unitItems.size > limits.maxItemsPerUnit ||
                    usage.characters > limits.maxCharactersPerUnit ||
                    usage.lines > limits.maxLinesPerUnit ||
                    usage.tokens > limits.maxTokensPerUnit
                units.add(TranslationPlanUnit(units.size, assetId, unitItems, context.precedings,
                    usage, context.usage, overBudget))
            }
        }
        return TranslationPlan(selection, limits, units, skipped)
    }

    private fun classify(item: TranslationItemSnapshot): TranslationSkipReason? {
        if (item.source.isBlank()) return TranslationSkipReason.EMPTY_SOURCE
        if (item.source.length > limits.maxItemCharacters) return TranslationSkipReason.SOURCE_TOO_LARGE
        if (gate.ruleSkip(item)) return TranslationSkipReason.RULE_SKIPPED
        if (gate.languageSkip(item)) return TranslationSkipReason.LANGUAGE_SKIPPED
        return null
    }

    /**
     * Mirrors LG's preceding extraction: walk backwards inside one file, ignore rows that are
     * skipped from translation, stop at a blank/non-sentence boundary, and admit only rows that
     * fit both the line count and context budgets. The scan itself is bounded so a long run of
     * skipped rows cannot make planning quadratic.
     */
    private fun precedingContext(
        all: List<TranslationItemSnapshot>,
        positions: Map<ItemId, Int>,
        first: TranslationItemSnapshot,
    ): ContextSelection {
        if (limits.precedingLines == 0 || limits.precedingCharacters == 0 || limits.precedingTokens == 0L) {
            return ContextSelection(emptyList(), TranslationBudgetUsage.EMPTY)
        }
        val start = positions[first.id] ?: return ContextSelection(emptyList(), TranslationBudgetUsage.EMPTY)
        val newestFirst = ArrayList<TranslationPreceding>()
        var usage = TranslationBudgetUsage.EMPTY
        var index = start - 1
        var scanned = 0
        while (index >= 0 && newestFirst.size < limits.precedingLines && scanned < MAX_PRECEDING_SCAN) {
            scanned++
            val item = all[index]
            index--
            if (isTranslationSkipped(item.status)) continue
            val source = item.source.trim()
            if (source.isEmpty()) break
            if (source.last() !in END_LINE_PUNCTUATION) break
            val measurement = tokenCounter.measure(source)
            val candidate = usage.plus(source.length.toLong(), translationLineCount(source), measurement)
            if (candidate.characters > limits.precedingCharacters || candidate.tokens > limits.precedingTokens) break
            newestFirst.add(TranslationPreceding(item.id, item.row, source, item.fingerprint))
            usage = candidate
        }
        val ordered = ArrayList<TranslationPreceding>(newestFirst.size)
        for (index in newestFirst.indices.reversed()) ordered.add(newestFirst[index])
        return ContextSelection(ordered, usage)
    }

    private class ContextSelection(
        val precedings: List<TranslationPreceding>,
        val usage: TranslationBudgetUsage,
    )

    private companion object {
        const val MAX_PRECEDING_SCAN = 512
        val END_LINE_PUNCTUATION: Set<Char> =
            setOf('.', '。', '?', '？', '!', '！', '…', '\'', '"', '」', '』')
    }
}

private fun isTranslationSkipped(status: TextItemStatus): Boolean = when (status) {
    TextItemStatus.EXCLUDED,
    TextItemStatus.RULE_SKIPPED,
    TextItemStatus.LANGUAGE_SKIPPED,
    TextItemStatus.DUPLICATED -> true

    TextItemStatus.NONE, TextItemStatus.PROCESSED, TextItemStatus.ERROR -> false
}

/** Non-blank line count with CR/LF and lone CR separators, matching the proofreading reader. */
private fun translationLineCount(source: String): Int {
    var lines = 0
    var lineHasContent = false
    var index = 0
    while (index < source.length) {
        when (source[index]) {
            '\r' -> {
                if (index + 1 < source.length && source[index + 1] == '\n') index++
                if (lineHasContent) lines++
                lineHasContent = false
            }

            '\n' -> {
                if (lineHasContent) lines++
                lineHasContent = false
            }

            else -> if (!source[index].isWhitespace()) lineHasContent = true
        }
        index++
    }
    if (lineHasContent) lines++
    return lines
}
