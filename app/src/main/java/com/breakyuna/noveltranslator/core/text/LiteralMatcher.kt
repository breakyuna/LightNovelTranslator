package com.breakyuna.noveltranslator.core.text

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/** 一个字面量模式：[key] 是跨页面、统计与校对链路共用的命中身份。 */
data class LiteralPattern(
    val key: String,
    val text: String,
    val caseSensitive: Boolean
)

/** 原文 UTF-16 半开区间。 */
data class TextRange(
    val start: Int,
    val end: Int
)

/** 单个模式的全部命中范围，按扫描顺序排列。 */
data class LiteralPatternMatch(
    val key: String,
    val ranges: List<TextRange>
)

/** Aho–Corasick 节点；[fail] 是转移失败时回退到的最长可匹配后缀。 */
private class AhoNode {
    val next: MutableMap<Char, Int> = HashMap()
    var fail: Int = 0
    val outputs: MutableList<Int> = ArrayList()
}

/** 单个大小写桶的自动机；[patternLengths] 按桶内下标保存模式长度。 */
private class AhoMatcher(
    val nodes: List<AhoNode>,
    val patternLengths: List<Int>
)

/** 大小写桶：把桶内下标映射回活跃模式下标。 */
private class Bucket(
    val matcher: AhoMatcher,
    val indexes: List<Int>,
    val caseSensitive: Boolean
)

/** 规范化文本及其每个 UTF-16 单元对应的原文字素起止点。 */
private class NormalizedText(
    val text: String,
    val starts: IntArray,
    val ends: IntArray
)

/** 规范化阶段的中间片段；Jamo 等跨字素组合需要向后合并。 */
private class Segment(
    var text: String,
    var start: Int,
    var end: Int
)

/**
 * 多模式字面匹配器：Aho–Corasick + NFKC + casefold。
 *
 * 移植自 LinguaGacha `literal-matcher.ts`。关键决策：
 * 1. 按 `caseSensitive` 分桶，敏感桶不过大小写折叠，两桶共享一次字素分割；
 * 2. 规范化会把一个原文字素展开成多个 UTF-16 单元（如 `ß`→`ss`、Jamo 合成），
 *    因此为每个规范化单元记录原文字素起止点，命中范围才能映射回原文；
 * 3. 同一模式因字符展开产生的相同原文范围只报告一次。
 *
 * 接口与实现分离：实现类是文件私有的，编译期结构不外泄，
 * 与来源实现"只暴露 LiteralMatcher 类型 + compile_literal_patterns 工厂"一致。
 */
interface LiteralMatcher {

    /** 实际参与匹配的模式（空文本已跳过），保持输入顺序。 */
    val patterns: List<LiteralPattern>

    /** 只判断是否存在任一命中，首个命中后立即停止。 */
    fun matches(text: String): Boolean

    /** 每个命中身份只访问一次；计数与分组无需构造原文坐标。 */
    fun scanKeys(text: String, visit: (String) -> Unit)

    /** 按扫描顺序流式返回命中及原文范围。 */
    fun scan(text: String, visit: (String, TextRange) -> Unit)

    /** 按 pattern 顺序聚合原文范围；[keys] 只选择需要收集范围的身份。 */
    fun match(text: String, keys: Set<String>? = null): List<LiteralPatternMatch>
}
private class LiteralMatcherImpl(
    private val activePatterns: List<LiteralPattern>,
    private val buckets: List<Bucket>,
    private val caseByKey: Map<String, Boolean>
) : LiteralMatcher {

    override val patterns: List<LiteralPattern> get() = activePatterns

    override fun matches(text: String): Boolean = buckets.any { bucket ->
        scanAhoText(bucket.matcher, normalizeLiteralText(text, bucket.caseSensitive)) { _, _ -> true }
    }

    /** 每个命中身份只访问一次；计数与分组无需构造原文坐标。 */
    override fun scanKeys(text: String, visit: (String) -> Unit) {
        for (bucket in buckets) {
            val seen = HashSet<Int>()
            scanAhoText(bucket.matcher, normalizeLiteralText(text, bucket.caseSensitive)) { index, _ ->
                if (seen.add(index)) visit(activePatterns[bucket.indexes[index]].key)
                seen.size == bucket.indexes.size
            }
        }
    }

    /** 按扫描顺序流式返回命中及原文范围。 */
    override fun scan(text: String, visit: (String, TextRange) -> Unit) {
        scanRanges(text, null) { index, range -> visit(activePatterns[index].key, range) }
    }

    /** 按 pattern 顺序聚合原文范围；[keys] 只选择需要收集范围的身份。 */
    override fun match(text: String, keys: Set<String>?): List<LiteralPatternMatch> {
        val rangesByIndex = LinkedHashMap<Int, MutableList<TextRange>>()
        scanRanges(text, keys) { index, range ->
            rangesByIndex.getOrPut(index) { ArrayList() } += range
        }
        return rangesByIndex.entries
            .sortedBy { it.key }
            .map { LiteralPatternMatch(activePatterns[it.key].key, it.value) }
    }

    /**
     * 仅为选中身份涉及的大小写桶构造坐标，两桶共享一次字素分割。
     *
     * 未登记的 key 不贡献大小写模式：全部选中 key 都未知时不过任何桶，
     * 与来源实现把未知 key 映射到 `undefined` 的效果一致。
     */
    private fun scanRanges(text: String, selectedKeys: Set<String>?, visit: (Int, TextRange) -> Unit) {
        val selectedBuckets = if (selectedKeys == null) {
            buckets
        } else {
            val modes = selectedKeys.mapNotNull { key -> caseByKey[key] }.toSet()
            buckets.filter { it.caseSensitive in modes }
        }
        if (selectedBuckets.isEmpty() || text.isEmpty()) return
        val inputs = normalizeTextWithSourceRanges(text, selectedBuckets.map { it.caseSensitive })
        for (bucket in selectedBuckets) {
            val input = inputs.getValue(bucket.caseSensitive)
            collectMatches(bucket.matcher, input, bucket.indexes, selectedKeys) { index, range ->
                visit(index, range)
            }
        }
    }

    /**
     * 把规范化坐标回写到原文 UTF-16 范围。
     *
     * 同一 grapheme 展开产生的相同范围只保留一次，否则 `ß`→`ss` 这类
     * 双单元展开会让同一命中被报告两遍。
     */
    private fun collectMatches(
        matcher: AhoMatcher,
        input: NormalizedText,
        bucketIndexes: List<Int>,
        selectedKeys: Set<String>?,
        visit: (Int, TextRange) -> Unit
    ) {
        val previousRangeByPattern = HashMap<Int, TextRange>()
        scanAhoText(matcher, input.text) { bucketIndex, textIndex ->
            val patternIndex = bucketIndexes[bucketIndex]
            if (selectedKeys != null && activePatterns[patternIndex].key !in selectedKeys) {
                return@scanAhoText false
            }
            val patternLength = matcher.patternLengths[bucketIndex]
            val foldedStart = textIndex - patternLength + 1
            val start = input.starts[foldedStart]
            val end = input.ends[textIndex]
            val previous = previousRangeByPattern[patternIndex]
            if (previous != null && previous.start == start && previous.end == end) {
                return@scanAhoText false
            }
            val range = TextRange(start, end)
            previousRangeByPattern[patternIndex] = range
            visit(patternIndex, range)
            false
        }
    }

    /** 所有消费方式共用规范化文本扫描；访问者返回 true 时提前结束。 */
    private fun scanAhoText(matcher: AhoMatcher, text: String, visit: (Int, Int) -> Boolean): Boolean {
        var nodeIndex = 0
        for (index in 0 until text.length) {
            nodeIndex = advanceAhoMatcher(matcher, nodeIndex, text[index])
            for (output in matcher.nodes[nodeIndex].outputs) {
                if (visit(output, index)) return true
            }
        }
        return false
    }

    /** 敏感与不敏感路径共用同一 failure-link 推进规则。 */
    private fun advanceAhoMatcher(matcher: AhoMatcher, nodeIndex: Int, character: Char): Int {
        var next = nodeIndex
        while (next != 0 && !matcher.nodes[next].next.containsKey(character)) {
            next = matcher.nodes[next].fail
        }
        return matcher.nodes[next].next[character] ?: 0
    }

    /**
     * 合并规范化后会跨字素组合的片段，再建立到原文 UTF-16 范围的映射。
     *
     * Jamo（`ㄱ`+`ㅏ`）与半角 Jamo（`ﾡ`+`ￂ`）各自 NFKC 后能 NFC 合成
     * 一个完整音节，但分属不同字素簇；这里按"合成结果变了就向前合并"的规则
     * 让坐标覆盖参与组合的双方。
     */
    private fun normalizeTextWithSourceRanges(text: String, caseModes: List<Boolean>): Map<Boolean, NormalizedText> {
        val segments = ArrayList<Segment>()
        val breaker = BreakIterator.getCharacterInstance(Locale.ROOT)
        breaker.setText(text)
        var start = breaker.first()
        var end = breaker.next()
        while (end != BreakIterator.DONE) {
            val value = normalizeLiteralText(text.substring(start, end), caseSensitive = true)
            val previous = segments.lastOrNull()
            val joined = if (previous == null) value else previous.text + value
            val composed = Normalizer.normalize(joined, Normalizer.Form.NFC)
            if (previous != null && composed != joined) {
                previous.text = composed
                previous.end = end
            } else {
                segments += Segment(value, start, end)
            }
            start = end
            end = breaker.next()
        }

        val inputs = LinkedHashMap<Boolean, NormalizedText>()
        for (caseSensitive in caseModes) {
            val builder = StringBuilder(text.length)
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            for (segment in segments) {
                val value = if (caseSensitive) segment.text else foldLiteralText(segment.text)
                builder.append(value)
                repeat(value.length) {
                    starts += segment.start
                    ends += segment.end
                }
            }
            inputs[caseSensitive] = NormalizedText(builder.toString(), starts.toIntArray(), ends.toIntArray())
        }
        return inputs
    }
}

/**
 * 全仓字面量匹配的唯一规范化规则；大小写标志只控制 casefold。
 *
 * 与来源实现的顺序严格一致：先 NFKC，再处理德语展开与希腊词尾变体，
 * 最后统一小写。`ẞ` 必须先于 `ß` 替换，否则小写化会把 `ẞ` 变成 `ß`。
 */
fun normalizeLiteralText(text: String, caseSensitive: Boolean): String {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
    return if (caseSensitive) normalized else foldLiteralText(normalized)
}

/** 在 NFKC 结果上统一德语展开及希腊词尾变体。 */
private fun foldLiteralText(normalized: String): String =
    normalized
        .replace("\u1E9E", "ss") // ẞ → ss
        .replace("\u00DF", "ss") // ß → ss
        .lowercase(Locale.ROOT)
        .replace("\u03C2", "\u03C3") // ς → σ

/** 编译大小写敏感与不敏感字面量；空文本跳过，重复身份直接拒绝。 */
fun compileLiteralPatterns(patterns: List<LiteralPattern>): LiteralMatcher {
    val caseByKey = HashMap<String, Boolean>()
    for (pattern in patterns) {
        require(pattern.key !in caseByKey) { "Duplicate literal pattern key: ${pattern.key}." }
        caseByKey[pattern.key] = pattern.caseSensitive
    }

    val activePatterns = patterns.filter { it.text.isNotEmpty() }
    val buckets = ArrayList<Bucket>()
    for (caseSensitive in listOf(true, false)) {
        val indexes = activePatterns.indices.filter { activePatterns[it].caseSensitive == caseSensitive }
        val matcher = buildAhoMatcher(
            indexes.map { normalizeLiteralText(activePatterns[it].text, caseSensitive) }
        ) ?: continue
        buckets += Bucket(matcher, indexes, caseSensitive)
    }
    return LiteralMatcherImpl(activePatterns, buckets, caseByKey)
}

/**
 * 构造 Aho–Corasick 自动机。
 *
 * BFS 逐层设置 failure link，并把失败节点的 outputs 合并进当前节点，
 * 这样扫描时只需看当前节点的 outputs 就能拿到全部后缀命中。
 */
private fun buildAhoMatcher(patterns: List<String>): AhoMatcher? {
    if (patterns.isEmpty()) return null
    val nodes = ArrayList<AhoNode>()
    nodes += AhoNode()
    patterns.forEachIndexed { patternIndex, pattern ->
        var nodeIndex = 0
        for (index in 0 until pattern.length) {
            val character = pattern[index]
            val existing = nodes[nodeIndex].next[character]
            if (existing != null) {
                nodeIndex = existing
                continue
            }
            val createdIndex = nodes.size
            nodes[nodeIndex].next[character] = createdIndex
            nodes += AhoNode()
            nodeIndex = createdIndex
        }
        nodes[nodeIndex].outputs += patternIndex
    }

    // 队列在遍历中继续增长，必须用下标推进而不是固定次数循环。
    val queue = ArrayList<Int>(nodes[0].next.values)
    var queueIndex = 0
    while (queueIndex < queue.size) {
        val node = nodes[queue[queueIndex]]
        queueIndex += 1
        for ((character, childIndex) in node.next) {
            queue += childIndex
            var failIndex = node.fail
            while (failIndex != 0 && !nodes[failIndex].next.containsKey(character)) {
                failIndex = nodes[failIndex].fail
            }
            val fallbackIndex = nodes[failIndex].next[character] ?: 0
            val child = nodes[childIndex]
            child.fail = fallbackIndex
            child.outputs += nodes[fallbackIndex].outputs
        }
    }
    return AhoMatcher(nodes, patterns.map { it.length })
}
