package com.breakyuna.noveltranslator.core.text

/**
 * 文本内资源引用的原始半开区间（UTF-16 offset，与 Kotlin 字符串下标一致）。
 *
 * 移植自 LinguaGacha `text-resource-reference.ts` 的 TextResourceReference。
 */
data class ResourceReference(
    val start: Int,
    val end: Int,
    val value: String
)

/** 单次翻译任务内临时 token 与原文的恢复关系。 */
data class ResourceReferenceMapping(
    val token: String,
    val value: String
)

/** 单个字段的投影结果，以及后续字段应延续使用的扁平序号。 */
data class ResourceReferenceProjection(
    val text: String,
    val mappings: List<ResourceReferenceMapping>,
    val nextOrdinal: Int
)

/**
 * 资源引用槽位：识别 → 投影为扁平 token → 译后精确还原。
 *
 * 移植自 LinguaGacha `text-resource-reference.ts`。核心不变量：
 * 1. data URI 优先于一般 URI 与无 scheme 路径，避免长载荷被拆成较短候选；
 * 2. 序号跨字段连续（`nextOrdinal`），并在撞车（源文已含同形 token）时跳过；
 * 3. 还原时长 token 优先 + `(?!\d)` 防前缀误命中，且单次替换不回扫恢复值。
 *
 * token 形如 `[REF:0]`：方括号终结符让 token 不可能是更长序号的前缀，
 * 因此不需要像来源实现那样依赖 `lg-uri/N` 的纯数字结构。
 */
object ResourceReferenceSlots {

    /** 发给模型的临时 token 前缀。内置保护规则据此生成资源 token 规则。 */
    const val TOKEN_PREFIX: String = "[REF:"

    /** 发给模型的临时 token 后缀；终结符同时承担防前缀误命中的作用。 */
    const val TOKEN_SUFFIX: String = "]"

    /**
     * URL scheme 的最大长度（含首字符）。
     *
     * 只服务于 [URI_PATTERN] 的回溯上限；真实 scheme 远短于此。
     */
    private const val MAX_URI_SCHEME_LENGTH: Int = 64

    // 无 scheme 路径没有结构化边界，只对明确的资源扩展名建立候选。
    private val RESOURCE_EXTENSIONS = listOf(
        // 音频
        "mp3", "wav", "ogg", "mid", "flac", "opus", "m4a", "aac",
        // 图片
        "png", "jpg", "jpeg", "gif", "psd", "webp", "heif", "heic",
        "bmp", "svg", "ico", "avif",
        // 视频
        "avi", "mp4", "webm", "mkv", "mov",
        // 字体
        "ttf", "otf", "woff", "woff2",
        // 数据
        "txt", "json", "sav", "mps", "xml", "yaml", "yml", "csv", "bin",
        // 文档
        "md", "pdf", "html", "htm", "xhtml", "xhtm",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "odp", "rtf", "epub",
        // 压缩包
        "7z", "gz", "rar", "zip", "tar"
    )

    // URI 结束字符集：空白、HTML 边界符与常见中英标点。
    // 注意必须显式列出 JS `\s` 的 Unicode 成员（如 U+3000），否则全角空格
    // 会被吞进引用内部。
    private val URI_STOP_CHARS: String =
        "\\s\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff" +
            "<>\"'`，。；：！？、（）【】「」『』"

    // data URI 必须优先于一般 URI 和路径匹配，避免长载荷被拆成较短候选。
    // `\b` 在 JS 中是 ASCII 词边界；`(?U)` 会把它放宽成 Unicode 边界，
    // 因此这里改用等价的 ASCII lookbehind，保持与来源实现一致。
    private val DATA_URI_BASE64_PATTERN = unicodeRegex(
        "(?<![0-9A-Za-z_])data:[a-z][a-z\\d.+-]*/[a-z\\d.+-]+" +
            "(?:;[a-z\\d!#\$&^_.+-]+(?:=[a-z\\d!#\$&^_.+%-]*)?)*;base64,[a-z\\d+/=_-]+"
    )

    private val URI_PATTERN = unicodeRegex(
        // scheme 上限 64：`[a-z]` 在 IGNORE_CASE 下会匹配大写字母，长串无 `://` 的
        // 负载（如 Base64）会让 `[a-z\d+.-]*` 反复贪吃后回溯，形成 O(n^2)。
        // 真实 URL scheme 远短于 64，加上限后每次尝试最多回溯 64 次。
        "(?<![0-9A-Za-z_])[a-z][a-z\\d+.-]{0,$MAX_URI_SCHEME_LENGTH}://[^$URI_STOP_CHARS]+"
    )

    private val RESOURCE_PATH_PATTERN = unicodeRegex(
        // 前导 lookbehind 保证只从"路径字符游程"的起点开始匹配。游程内部的起点
        // 即便能命中，也必然被同一游程起点的更长命中覆盖（findAll 取最左），
        // 因此语义不变，却把每条游程的回溯从 O(n^2) 降到 O(n)。
        "(?<![\\p{L}\\p{M}\\p{N}_@%+~.-])" +
            "[\\p{L}\\p{M}\\p{N}_@%+~.-]+(?:[\\\\/][\\p{L}\\p{M}\\p{N}_@%+~.-]+)*" +
            "\\.(?:${RESOURCE_EXTENSIONS.joinToString("|")})" +
            "(?![\\p{L}\\p{M}\\p{N}_-])" +
            "(?:[?#][^$URI_STOP_CHARS]+)?"
    )

    // URI 正则允许常见 ASCII 标点，命中结束后再按成对结构裁掉句末标点。
    private val SIMPLE_TRAILING_PUNCTUATION = setOf(
        ".", ",", ";", ":", "!", "?", "。", "，", "；", "：", "！", "？", "、"
    )

    private val CLOSING_TO_OPENING = mapOf(
        ")" to "(", "]" to "[", "}" to "{",
        "）" to "（", "】" to "【", "」" to "「", "』" to "『"
    )

    private data class ReferenceCandidate(
        val start: Int,
        val end: Int,
        val value: String,
        val priority: Int
    )

    /** 识别文本内的 Base64 data URI、带 `://` scheme 的 URI 与无 scheme 资源路径。 */
    fun collect(text: String): List<ResourceReference> {
        val candidates = ArrayList<ReferenceCandidate>()
        candidates += collectPatternCandidates(text, DATA_URI_BASE64_PATTERN, priority = 0)
        candidates += collectPatternCandidates(text, URI_PATTERN, priority = 1)
        candidates += collectPatternCandidates(text, RESOURCE_PATH_PATTERN, priority = 2)
        // 起点升序 → 优先级升序 → 终点降序（同起点取更长候选）。
        val sorted = candidates.sortedWith(
            compareBy({ it.start }, { it.priority }, { -it.end })
        )

        val references = ArrayList<ResourceReference>()
        var cursor = 0
        for (candidate in sorted) {
            if (candidate.start < cursor || candidate.end <= candidate.start) continue
            references += ResourceReference(candidate.start, candidate.end, candidate.value)
            cursor = candidate.end
        }
        return references
    }

    /** 移除全部已识别引用，供调用方按自己的正文规则判断剩余内容。 */
    fun remove(text: String, references: List<ResourceReference> = collect(text)): String =
        replaceReferenceRanges(text, references) { "" }

    /** 保持资源边界，返回替换规则可独立处理的普通文本段，包含空边界段。 */
    fun splitAround(text: String): List<String> {
        val parts = ArrayList<String>()
        var cursor = 0
        for (reference in collect(text)) {
            parts += text.substring(cursor, reference.start)
            cursor = reference.end
        }
        parts += text.substring(cursor)
        return parts
    }

    /** 读取本次投影实际分配的标记位置，供统计排除仅命中临时编号的规则。 */
    fun collectProjected(
        text: String,
        mappings: List<ResourceReferenceMapping>
    ): List<ResourceReference> {
        val pattern = buildMappingTokenPattern(mappings) ?: return emptyList()
        return pattern.findAll(text).map { match ->
            ResourceReference(match.range.first, match.range.last + 1, match.value)
        }.toList()
    }

    /**
     * 把引用替换为任务内扁平编号 token，并返回后续字段应继续使用的序号。
     *
     * [startOrdinal] 允许调用方跨字段延续编号，保证同一任务内 token 唯一。
     */
    fun project(
        text: String,
        startOrdinal: Int = 0,
        tokenPrefix: String = TOKEN_PREFIX,
        tokenSuffix: String = TOKEN_SUFFIX
    ): ResourceReferenceProjection {
        val references = collect(text)
        val mappings = ArrayList<ResourceReferenceMapping>(references.size)
        var nextOrdinal = maxOf(0, startOrdinal)
        val projected = buildString {
            var cursor = 0
            for (reference in references) {
                append(text, cursor, reference.start)
                // 序号跳过源文已有的同形 token，否则投影后无法区分临时占位与原文内容。
                var token = "$tokenPrefix$nextOrdinal$tokenSuffix"
                while (text.contains(token)) {
                    nextOrdinal += 1
                    token = "$tokenPrefix$nextOrdinal$tokenSuffix"
                }
                nextOrdinal += 1
                mappings += ResourceReferenceMapping(token, reference.value)
                append(token)
                cursor = reference.end
            }
            append(text, cursor, text.length)
        }
        return ResourceReferenceProjection(projected, mappings, nextOrdinal)
    }

    /**
     * 单次替换当前任务实际生成的 token。
     *
     * 只用一个联合正则扫一遍，恢复值不再参与后续匹配，因此恢复值中
     * 形如 token 的文本不会被二次替换。
     */
    fun restore(text: String, mappings: List<ResourceReferenceMapping>): String {
        val pattern = buildMappingTokenPattern(mappings) ?: return text
        val valueByToken = HashMap<String, String>()
        for (mapping in mappings) valueByToken[mapping.token] = mapping.value
        return pattern.replace(text) { match -> valueByToken[match.value] ?: match.value }
    }

    /** 仅转换临时 token 之间的文本，保证本地清理和替换不改写引用占位符。 */
    fun transformProjected(
        text: String,
        mappings: List<ResourceReferenceMapping>,
        transform: (String) -> String
    ): String {
        val pattern = buildMappingTokenPattern(mappings) ?: return transform(text)
        val result = StringBuilder(text.length)
        var cursor = 0
        for (match in pattern.findAll(text)) {
            result.append(transform(text.substring(cursor, match.range.first)))
            result.append(match.value)
            cursor = match.range.last + 1
        }
        result.append(transform(text.substring(cursor)))
        return result.toString()
    }

    /** 将单个识别模式的命中转成带裁决优先级的候选区间。 */
    private fun collectPatternCandidates(
        text: String,
        pattern: Regex,
        priority: Int
    ): List<ReferenceCandidate> {
        val candidates = ArrayList<ReferenceCandidate>()
        for (match in pattern.findAll(text)) {
            val rawValue = match.value
            val start = match.range.first
            if (rawValue.isEmpty()) continue
            val end = trimReferenceEnd(text, start, start + rawValue.length)
            candidates += ReferenceCandidate(start, end, text.substring(start, end), priority)
        }
        return candidates
    }

    /** 裁掉句末标点和未配对闭合符，同时保留 URI 自身的成对括号。 */
    private fun trimReferenceEnd(text: String, start: Int, initialEnd: Int): Int {
        var end = initialEnd
        while (end > start) {
            val last = text[end - 1].toString()
            if (last in SIMPLE_TRAILING_PUNCTUATION) {
                end -= 1
                continue
            }
            val opening = CLOSING_TO_OPENING[last] ?: break
            val value = text.substring(start, end)
            if (countCharacter(value, last) <= countCharacter(value, opening)) break
            end -= 1
        }
        return end
    }

    /** 统计单个字符，用于判断 URI 末尾闭合符是否有对应开符。 */
    private fun countCharacter(text: String, character: String): Int {
        var count = 0
        var index = text.indexOf(character)
        while (index >= 0) {
            count += 1
            index = text.indexOf(character, index + character.length)
        }
        return count
    }

    /**
     * 构建精确 token 联合正则：长 token 优先且不得命中更长序号的前缀。
     *
     * 与来源实现的差异：token 以 `]` 终结，`[REF:1]` 不可能是 `[REF:12]` 的前缀，
     * 因此只需要 `(?!\d)` 兜住"映射里只有短序号、文本里是长序号"的情况。
     */
    private fun buildMappingTokenPattern(mappings: List<ResourceReferenceMapping>): Regex? {
        val tokens = mappings.map { it.token }.distinct()
        if (tokens.isEmpty()) return null
        val alternation = tokens
            .sortedByDescending { it.length }
            .joinToString("|") { escapeRegexp(it) }
        return Regex("(?:$alternation)(?!\\d)")
    }

    /** 转义动态 token，保证恢复映射只按字面量匹配。 */
    private fun escapeRegexp(text: String): String {
        val sb = StringBuilder(text.length * 2)
        for (ch in text) {
            if (ch in ".*+?^\${}()|[]\\") sb.append('\\')
            sb.append(ch)
        }
        return sb.toString()
    }

    /** 按已排序且互不重叠的半开区间重建文本。 */
    private inline fun replaceReferenceRanges(
        text: String,
        references: List<ResourceReference>,
        replacement: (ResourceReference) -> String
    ): String {
        val result = StringBuilder(text.length)
        var cursor = 0
        for (reference in references) {
            result.append(text, cursor, reference.start)
            result.append(replacement(reference))
            cursor = reference.end
        }
        result.append(text, cursor, text.length)
        return result.toString()
    }

    /**
     * 统一加上 `(?U)`，让 `\s`/`\d` 等走 Unicode 语义以对齐 JS 的 `u` flag。
     *
     * 含 `\b` 的模式已在源码里改用等价的 ASCII lookbehind，不受 `(?U)` 影响。
     */
    private fun unicodeRegex(pattern: String): Regex =
        Regex("(?U)$pattern", RegexOption.IGNORE_CASE)
}
