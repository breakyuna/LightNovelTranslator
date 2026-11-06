package com.breakyuna.noveltranslator.nativecore.formats.rgss

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

private const val MAX_LOCATOR_PATH = 1_024
private const val MAX_UNIT_SOURCE = 65_536
private const val MAX_TRANSLATION_CHARS = 131_073
private const val DIGEST_CHARS = 64
internal const val RGSS_DATA_ENCODING_UTF8 = "UTF-8"
internal const val RGSS_DATA_ENCODING_SHIFT_JIS = "Shift_JIS"
private const val ENCODING_UTF8 = RGSS_DATA_ENCODING_UTF8
private const val ENCODING_SHIFT_JIS = RGSS_DATA_ENCODING_SHIFT_JIS
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * Classified failure of a non-script RGSS Marshal data file. The message never contains data bodies;
 * [detail] is limited to structural facts such as a reason name, a node budget or a field label.
 */
class RgssDataException(val reason: Reason, detail: String = "") :
    IllegalArgumentException(
        "RGSS data: $reason" + (if (detail.isEmpty()) "" else " ($detail)"),
    ) {
    enum class Reason {
        MARSHAL,
        STRUCTURE,
        ENCODING,
        LIMIT,
        BINDING,
    }
}

/**
 * Injected bounds for data-file scanning and rewriting. The defaults are prototype budgets, not
 * measured device limits. [marshal] bounds the underlying tree; [maxSteps] bounds traversal work
 * independently of the payload shape so a pathological tree cannot stall the caller forever.
 */
class RgssDataLimits(
    val marshal: MarshalLimits = MarshalLimits(),
    val maxUnits: Int = 100_000,
    val maxUnitSource: Int = 65_536,
    val maxOutputBytes: Int = RgssArchiveLimits.DEFAULT_MAX_OUTPUT_BYTES,
    val maxSteps: Long = 8_000_000L,
) {
    init {
        require(maxUnits > 0)
        require(maxUnitSource in 1..65_536)
        require(maxOutputBytes > 0)
        require(maxSteps > 0)
    }
}

/**
 * One translatable string found in a Marshal data file.
 *
 * [node] is the deterministic preorder ordinal of the distinct Marshal node (shared references and
 * links keep one ordinal). [path] is the human-readable parent slot derived from the same traversal;
 * [digest] is the SHA-256 of the original string body bytes. The internal [range] covers the Marshal
 * length prefix plus the body, so a replacement can change the string size without corrupting the
 * length field. The locator is independent of the string content, and a rewrite re-derives it from
 * the original bytes before touching anything.
 */
class RgssDataString internal constructor(
    val node: Int,
    val path: String,
    val source: String,
    val encoding: String,
    val digest: String,
    originalBytes: ByteArray,
    prefixBytes: ByteArray,
    internal val range: IntRange,
    internal val charset: Charset,
) {
    internal val bytes: ByteArray = originalBytes.copyOf()
    internal val prefix: ByteArray = prefixBytes.copyOf()

    init {
        require(node >= 0)
        require(path.isNotEmpty() && path.length <= MAX_LOCATOR_PATH)
        require(source.isNotEmpty() && source.length <= MAX_UNIT_SOURCE)
        require(encoding == ENCODING_UTF8 || encoding == ENCODING_SHIFT_JIS)
        require(digest.length == DIGEST_CHARS && digest.all { it in HEX_DIGITS })
        require(range.first >= 0 && range.last >= range.first)
    }

    override fun toString(): String = "RgssDataString(node=$node,encoding=$encoding)"
}

/**
 * Result of [RgssDataCodec.scan]. [encoding] is the inferred file encoding, or null when the file
 * contains only ASCII strings in a legacy `.rxdata`/`.rvdata` payload and the encoding cannot be
 * proven. [ambiguousEncoding] records that case, so the adapter can warn and translate nothing.
 */
class RgssDataScan internal constructor(
    strings: List<RgssDataString>,
    val encoding: String?,
    val skipped: Int,
    val ambiguousEncoding: Boolean,
) {
    val strings: List<RgssDataString> = Collections.unmodifiableList(ArrayList(strings))

    override fun toString(): String =
        "RgssDataScan(strings=${strings.size},encoding=$encoding,ambiguous=$ambiguousEncoding)"
}

/**
 * One validated data-string edit. The caller must repeat every locator field produced by [scan];
 * [RgssDataCodec.rewrite] rejects the whole file if any field no longer matches the original bytes.
 */
class RgssDataEdit(
    val node: Int,
    val path: String,
    val source: String,
    val digest: String,
    val encoding: String,
    val translation: String,
) {
    init {
        require(node >= 0)
        require(path.isNotEmpty() && path.length <= MAX_LOCATOR_PATH)
        require(source.isNotEmpty() && source.length <= MAX_UNIT_SOURCE)
        require(digest.length == DIGEST_CHARS && digest.all { it in HEX_DIGITS })
        require(encoding == ENCODING_UTF8 || encoding == ENCODING_SHIFT_JIS)
        require(translation.length <= MAX_TRANSLATION_CHARS && '\u0000' !in translation)
    }
}

/**
 * Bounded string extraction and splice-based write-back for non-script RGSS data files
 * (`Data/<name>.rxdata`, `Data/<name>.rvdata`, `Data/<name>.rvdata2` and map variants).
 *
 * ## Whitelist
 *
 * Only these slots are candidates:
 * - `@name`, `@nickname`, `@description`, `@note` on Marshal objects whose class starts with
 *   `RPG::`;
 * - the text parameters of `RPG::EventCommand` for the explicit command codes below.
 *
 * Event command allowlist (parameter index and shape):
 * - `102` index 0: array of choice strings;
 * - `401` index 0, `405` index 0: message/scrolling-text line;
 * - `402` index 1: selected choice label;
 * - `320` index 1, `324` index 1, `325` index 1: change name/nickname/profile.
 *
 * Everything else is skipped: symbols, class names, file names and paths, encoding names, format
 * strings, blank or empty strings, numeric-only strings, `@note` values containing `<`/`>` plugin
 * tags, strings that cannot be decoded, and files whose legacy encoding cannot be proven. Strings
 * rejected after decoding are counted in [RgssDataScan.skipped]; slots excluded by the structural
 * whitelist are not counted. Opaque user-defined `u` payloads (RGSS `Table`) are kept
 * verbatim but do not stop traversal, so map events stored after a table remain reachable.
 *
 * ## Encoding
 *
 * A string with an explicit UTF-8 ivar is read and written as UTF-8. An explicit US-ASCII or other
 * encoding is skipped. `.rvdata2` (VX Ace) is always UTF-8 for strings without an ivar. For a legacy
 * `.rxdata`/`.rvdata` payload, every undecided candidate must decode strictly as UTF-8, otherwise
 * all of them must decode strictly as Shift_JIS, and at least one must contain a decodable
 * non-ASCII letter. Mixed, binary-only or undecodable payloads reject the file. A legacy payload
 * that is ASCII-only is reported as ambiguous instead of being guessed, and a file containing an
 * explicit UTF-8 string defaults to UTF-8 when all other bytes are ASCII.
 *
 * ## Write-back
 *
 * [rewrite] parses the original bytes again, re-runs the same traversal and whitelist, and verifies
 * node ordinal, parent path, original prefix/body bytes and digest for every edit before allocating
 * output. Only each string's encoded length prefix and body are spliced (with a freshly encoded
 * length), so Marshal 4.8 structure, object identity, encoding ivars, unmodified strings/objects and
 * all other bytes stay exactly as they were. Any mismatch, encoding failure, duplicate node, overlap
 * or output budget overflow rejects the whole file; a partial result is never returned.
 *
 * [checkActive] is charged periodically (the existing cancellation pattern in this module), and
 * [RgssDataLimits.maxSteps] bounds total traversal work; callers may throw from [checkActive] to
 * cancel or enforce their own deadline.
 */
object RgssDataCodec {
    fun scan(
        bytes: ByteArray,
        fileName: String,
        limits: RgssDataLimits = RgssDataLimits(),
        checkActive: () -> Unit = {},
    ): RgssDataScan = prepare(bytes, fileName, limits, checkActive).scan

    fun rewrite(
        original: ByteArray,
        fileName: String,
        edits: List<RgssDataEdit>,
        limits: RgssDataLimits = RgssDataLimits(),
        checkActive: () -> Unit = {},
    ): ByteArray {
        if (edits.isEmpty()) return original.copyOf()
        if (edits.size > limits.maxUnits) {
            throw RgssDataException(RgssDataException.Reason.LIMIT, "edits")
        }
        val prepared = prepare(original, fileName, limits, checkActive)
        if (prepared.scan.ambiguousEncoding) {
            throw RgssDataException(RgssDataException.Reason.ENCODING, "ambiguous")
        }
        val budget = Budget(limits.maxSteps, checkActive)
        val seenNodes = HashSet<Int>()
        val replacements = ArrayList<Replacement>(edits.size)
        var encodedTotal = 0L
        for (edit in edits) {
            budget.step()
            if (!seenNodes.add(edit.node)) {
                throw RgssDataException(RgssDataException.Reason.BINDING, "duplicate node")
            }
            val string = prepared.byNode[edit.node]
                ?: throw RgssDataException(RgssDataException.Reason.BINDING, "node")
            if (string.path != edit.path) {
                throw RgssDataException(RgssDataException.Reason.BINDING, "path")
            }
            if (string.source != edit.source) {
                throw RgssDataException(RgssDataException.Reason.BINDING, "source")
            }
            if (string.digest != edit.digest) {
                throw RgssDataException(RgssDataException.Reason.BINDING, "digest")
            }
            if (string.encoding != edit.encoding) {
                throw RgssDataException(RgssDataException.Reason.BINDING, "encoding")
            }
            if (edit.translation == edit.source) continue
            val range = string.range
            val bodyStart = range.first + string.prefix.size
            if (range.first < 0 || bodyStart < range.first || bodyStart > range.last + 1 ||
                range.last >= original.size ||
                bodyStart + string.bytes.size - 1 != range.last ||
                !original.copyOfRange(range.first, bodyStart).contentEquals(string.prefix) ||
                !original.copyOfRange(bodyStart, range.last + 1).contentEquals(string.bytes)
            ) {
                throw RgssDataException(RgssDataException.Reason.BINDING, "range")
            }
            val encoded = encodeStrict(string.charset, edit.translation)
                ?: throw RgssDataException(RgssDataException.Reason.ENCODING, "translation")
            if (encoded.size > limits.marshal.maxStringBytes) {
                throw RgssDataException(RgssDataException.Reason.LIMIT, "string bytes")
            }
            val lengthBytes = encodeMarshalLength(encoded.size)
            val replacement = ByteArray(lengthBytes.size + encoded.size)
            System.arraycopy(lengthBytes, 0, replacement, 0, lengthBytes.size)
            System.arraycopy(encoded, 0, replacement, lengthBytes.size, encoded.size)
            encodedTotal += replacement.size.toLong()
            if (encodedTotal > limits.maxOutputBytes.toLong()) {
                throw RgssDataException(RgssDataException.Reason.LIMIT, "output bytes")
            }
            replacements.add(Replacement(range, replacement))
        }
        if (replacements.isEmpty()) return original.copyOf()
        replacements.sortBy { it.range.first }
        var previousEnd = -1
        var delta = 0L
        for (replacement in replacements) {
            if (replacement.range.first <= previousEnd) {
                throw RgssDataException(RgssDataException.Reason.BINDING, "overlap")
            }
            previousEnd = replacement.range.last
            delta += replacement.bytes.size.toLong() - (replacement.range.last - replacement.range.first + 1).toLong()
        }
        val outputSize = original.size.toLong() + delta
        if (outputSize <= 0L || outputSize > limits.maxOutputBytes.toLong() || outputSize > Int.MAX_VALUE.toLong()) {
            throw RgssDataException(RgssDataException.Reason.LIMIT, "output bytes")
        }
        val output = ByteArray(outputSize.toInt())
        var sourcePosition = 0
        var targetPosition = 0
        for (replacement in replacements) {
            budget.step()
            val length = replacement.range.first - sourcePosition
            System.arraycopy(original, sourcePosition, output, targetPosition, length)
            targetPosition += length
            System.arraycopy(replacement.bytes, 0, output, targetPosition, replacement.bytes.size)
            targetPosition += replacement.bytes.size
            sourcePosition = replacement.range.last + 1
        }
        System.arraycopy(original, sourcePosition, output, targetPosition, original.size - sourcePosition)
        checkActive()
        return output
    }

    private class Prepared(
        val scan: RgssDataScan,
        val byNode: Map<Int, RgssDataString>,
    )

    private class Replacement(val range: IntRange, val bytes: ByteArray)

    private class Candidate(
        val node: Int,
        val path: String,
        val bytes: ByteArray,
        val prefix: ByteArray,
        val range: IntRange,
        val declared: Declared,
        val note: Boolean,
    )

    private enum class Declared { UTF8, ASCII, SHIFT_JIS, NONE, OTHER }

    private class Budget(private val maxSteps: Long, private val checkActive: () -> Unit) {
        private var steps = 0L

        fun step() {
            steps += 1
            if (steps > maxSteps) throw RgssDataException(RgssDataException.Reason.LIMIT, "steps")
            if (steps % 4_096 == 0L) checkActive()
        }
    }

    private fun prepare(
        bytes: ByteArray,
        fileName: String,
        limits: RgssDataLimits,
        checkActive: () -> Unit,
    ): Prepared {
        checkActive()
        val budget = Budget(limits.maxSteps, checkActive)
        val root = parse(bytes, limits, budget)
        checkActive()
        val ordinals = index(root, budget)
        checkActive()
        val candidates = collect(root, ordinals, limits, budget)
        checkActive()
        val fileEncoding = resolveFileEncoding(candidates, fileName, budget)
        if (fileEncoding == null && candidates.any { it.declared == Declared.NONE }) {
            checkActive()
            return Prepared(
                RgssDataScan(emptyList(), null, candidates.size, ambiguousEncoding = true),
                emptyMap(),
            )
        }
        val initialCapacity = minOf(candidates.size, limits.maxUnits)
        val strings = ArrayList<RgssDataString>(initialCapacity)
        val byNode = HashMap<Int, RgssDataString>(initialCapacity * 2)
        var skipped = 0
        for (candidate in candidates) {
            budget.step()
            val charset = when (candidate.declared) {
                Declared.UTF8 -> Charsets.UTF_8
                Declared.ASCII, Declared.OTHER -> null
                Declared.SHIFT_JIS -> SHIFT_JIS
                Declared.NONE -> fileEncoding
            }
            if (charset == null) {
                skipped += 1
                continue
            }
            val encoding = if (charset == Charsets.UTF_8) ENCODING_UTF8 else ENCODING_SHIFT_JIS
            val text = decodeStrict(charset, candidate.bytes)
            if (text == null || !isTranslatable(text, candidate.note)) {
                skipped += 1
                continue
            }
            if (text.length > limits.maxUnitSource) {
                skipped += 1
                continue
            }
            if (strings.size >= limits.maxUnits) {
                throw RgssDataException(RgssDataException.Reason.LIMIT, "units")
            }
            val string = RgssDataString(
                candidate.node,
                candidate.path,
                text,
                encoding,
                sha256Hex(candidate.bytes),
                candidate.bytes,
                candidate.prefix,
                candidate.range,
                charset,
            )
            strings.add(string)
            byNode[candidate.node] = string
        }
        checkActive()
        val inferred = when {
            strings.isEmpty() && fileEncoding == Charsets.UTF_8 -> ENCODING_UTF8
            strings.isEmpty() && fileEncoding != null -> ENCODING_SHIFT_JIS
            strings.isEmpty() -> null
            strings.all { it.encoding == ENCODING_UTF8 } -> ENCODING_UTF8
            strings.all { it.encoding == ENCODING_SHIFT_JIS } -> ENCODING_SHIFT_JIS
            else -> null
        }
        return Prepared(RgssDataScan(strings, inferred, skipped, ambiguousEncoding = false), byNode)
    }

    private fun parse(root: ByteArray, limits: RgssDataLimits, budget: Budget): MarshalValue {
        budget.step()
        return try {
            RubyMarshal.parse(root, limits.marshal)
        } catch (failure: MarshalException) {
            val reason = if (failure.reason == MarshalException.Reason.LIMIT) {
                RgssDataException.Reason.LIMIT
            } else {
                RgssDataException.Reason.MARSHAL
            }
            throw RgssDataException(reason, failure.reason.name)
        }
    }

    /**
     * Assigns one ordinal per distinct Marshal node in preorder. Links and shared references return
     * the ordinal already assigned to that node, so a shared string is never extracted twice and no
     * later node can shift.
     */
    private fun index(root: MarshalValue, budget: Budget): IdentityHashMap<MarshalValue, Int> {
        val map = IdentityHashMap<MarshalValue, Int>()
        fun visit(value: MarshalValue) {
            budget.step()
            if (map.containsKey(value)) return
            map[value] = map.size
            when (value) {
                is MarshalArray -> for (element in value.elements) visit(element)
                is MarshalHash -> {
                    for (entry in value.entries) {
                        visit(entry.key)
                        visit(entry.value)
                    }
                    value.defaultValue?.let { visit(it) }
                }
                is MarshalObject -> for (ivar in value.ivars) visit(ivar.value)
                else -> Unit
            }
            if (value !is MarshalObject) {
                for (ivar in value.ivars) visit(ivar.value)
            }
        }
        visit(root)
        return map
    }

    /**
     * Collects whitelisted string slots in the same preorder. Only the first whitelisted path for a
     * shared node becomes a candidate; later references reuse the same node identity on write.
     */
    private fun collect(
        root: MarshalValue,
        ordinals: Map<MarshalValue, Int>,
        limits: RgssDataLimits,
        budget: Budget,
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>()
        val extracted = HashSet<Int>()
        val visited = IdentityHashMap<MarshalValue, Boolean>()
        fun candidate(value: MarshalString, path: String, note: Boolean) {
            budget.step()
            val node = ordinals[value] ?: return
            if (!extracted.add(node)) return
            if (value.bytes.size > limits.maxUnitSource * 4) return
            if (!isPotentialText(value.bytes, note)) return
            val body = value.bodyRange ?: return
            val prefix = value.lengthRange ?: return
            val prefixBytes = value.lengthBytes ?: return
            if (prefix.last + 1 != body.first) return
            candidates.add(
                Candidate(node, path, value.bytes, prefixBytes, prefix.first..body.last, declared(value), note),
            )
        }
        fun visit(value: MarshalValue, path: String) {
            budget.step()
            if (visited.put(value, true) != null) return
            when (value) {
                is MarshalObject -> {
                    if (value.className.startsWith("RPG::")) {
                        for (ivar in value.ivars) {
                            if (ivar.name !in TEXT_IVARS) continue
                            val text = ivar.value as? MarshalString ?: continue
                            candidate(text, childPath(path, "." + ivar.name), ivar.name == "@note")
                        }
                        if (value.className == EVENT_COMMAND_CLASS) {
                            collectEventCommand(value, path) { text, slot ->
                                candidate(text, slot, false)
                            }
                        }
                    }
                    for (ivar in value.ivars) visit(ivar.value, childPath(path, "." + ivar.name))
                }
                is MarshalArray -> value.elements.forEachIndexed { index, element ->
                    visit(element, childPath(path, "[$index]"))
                }
                is MarshalHash -> {
                    value.entries.forEachIndexed { index, entry ->
                        visit(entry.key, childPath(path, "{key $index}"))
                        visit(entry.value, childPath(path, "{value $index}"))
                    }
                    value.defaultValue?.let { visit(it, childPath(path, "{default}")) }
                }
                is MarshalUserData -> for (ivar in value.ivars) {
                    visit(ivar.value, childPath(path, "." + ivar.name))
                }
                else -> Unit
            }
        }
        visit(root, "root")
        return candidates
    }

    private fun collectEventCommand(
        command: MarshalObject,
        path: String,
        candidate: (MarshalString, String) -> Unit,
    ) {
        val code = command.ivars.firstOrNull { it.name == "@code" }?.value as? MarshalInt ?: return
        val parameters = command.ivars.firstOrNull { it.name == "@parameters" }?.value as? MarshalArray
            ?: return
        val slots = EVENT_TEXT_PARAMETERS[code.value] ?: return
        for ((index, mode) in slots) {
            val parameter = parameters.elements.getOrNull(index) ?: continue
            val parameterPath = childPath(path, ".@parameters[$index]")
            when (mode) {
                ParameterMode.TEXT -> (parameter as? MarshalString)?.let { candidate(it, parameterPath) }
                ParameterMode.LIST -> if (parameter is MarshalArray) {
                    parameter.elements.forEachIndexed { elementIndex, element ->
                        (element as? MarshalString)?.let {
                            candidate(it, childPath(parameterPath, "[$elementIndex]"))
                        }
                    }
                }
            }
        }
    }

    private fun resolveFileEncoding(
        candidates: List<Candidate>,
        fileName: String,
        budget: Budget,
    ): Charset? {
        val undecided = candidates.filter { it.declared == Declared.NONE }
        if (undecided.isEmpty()) return null
        // VX Ace runs on Ruby 1.9+, whose default external encoding is UTF-8. Data strings either
        // carry an explicit UTF-8 ivar or are plain UTF-8, so never infer Shift_JIS here.
        if (isVxAceName(fileName)) return Charsets.UTF_8
        var hasNonAscii = false
        var allUtf8 = true
        var allShiftJis = true
        var utf8Letters = false
        var shiftJisLetters = false
        for (candidate in undecided) {
            budget.step()
            if (candidate.bytes.any { (it.toInt() and 0xFF) >= 0x80 }) hasNonAscii = true
            val utf8 = decodeStrict(Charsets.UTF_8, candidate.bytes)
            if (utf8 == null) {
                allUtf8 = false
            } else if (utf8.any { it.code >= 0x80 && it.isLetter() }) {
                utf8Letters = true
            }
            val shiftJis = SHIFT_JIS
            val sjis = shiftJis?.let { decodeStrict(it, candidate.bytes) }
            if (sjis == null) {
                allShiftJis = false
            } else if (sjis.any { it.code >= 0x80 && it.isLetter() }) {
                shiftJisLetters = true
            }
        }
        if (!hasNonAscii) {
            val explicitUtf8 = candidates.any { it.declared == Declared.UTF8 }
            return if (explicitUtf8) Charsets.UTF_8 else null
        }
        // A payload made only of binary-looking bytes must not choose an encoding for the file.
        if (allUtf8 && utf8Letters) return Charsets.UTF_8
        if (allShiftJis && shiftJisLetters && SHIFT_JIS != null) return SHIFT_JIS
        throw RgssDataException(RgssDataException.Reason.ENCODING, "data file")
    }

    private fun declared(value: MarshalString): Declared {
        val name = value.encodingName() ?: return Declared.NONE
        return when (name.uppercase(Locale.ROOT).replace('_', '-')) {
            "UTF-8", "UTF8" -> Declared.UTF8
            "US-ASCII", "ASCII" -> Declared.ASCII
            "SHIFT-JIS", "SJIS" -> Declared.SHIFT_JIS
            else -> Declared.OTHER
        }
    }

    /**
     * Encoding-independent pre-gate. It runs before file encoding inference so a file path or note
     * tag stored in Shift_JIS cannot make an otherwise UTF-8 file look mixed. Only ASCII-safe tests
     * are applied here; the decoded text is checked again by [isTranslatable].
     */
    private fun isPotentialText(bytes: ByteArray, note: Boolean): Boolean {
        if (bytes.isEmpty()) return false
        var hasAsciiLetter = false
        var hasNonAscii = false
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            if (value >= 0x80) {
                hasNonAscii = true
                continue
            }
            val character = value.toChar()
            if (character.isISOControl() && character != '\n' && character != '\r' && character != '\t') {
                return false
            }
            if (character in 'A'..'Z' || character in 'a'..'z') hasAsciiLetter = true
        }
        if (!hasNonAscii && !hasAsciiLetter) return false
        val latin = String(bytes, Charsets.ISO_8859_1)
        if (latin.isBlank()) return false
        // Do not test ASCII `/` or `\` here: 0x5C is a valid Shift_JIS trail byte and would
        // misclassify ordinary Japanese text before decoding. The decoded gate handles paths.
        if (hasAssetExtension(latin)) return false
        if (latin.trim().lowercase(Locale.ROOT) in ENCODING_NAMES) return false
        if (latin.trim().lowercase(Locale.ROOT) in NONE_VALUES) return false
        if (CONSTANT_PATH.matches(latin.trim())) return false
        if (FORMAT_DIRECTIVE.containsMatchIn(latin)) return false
        if (note && ('<' in latin || '>' in latin)) return false
        return true
    }

    /**
     * Conservative text gate applied after strict decoding. Returning false only skips that string;
     * it never guesses that an ambiguous value is safe to translate.
     */
    private fun isTranslatable(text: String, note: Boolean): Boolean {
        if (text.isEmpty() || text.isBlank()) return false
        if (text.none { it.isLetter() }) return false
        for (character in text) {
            if (character == '\u0000') return false
            if (character.isISOControl() && character != '\n' && character != '\r' && character != '\t') {
                return false
            }
        }
        if (looksLikePath(text)) return false
        if (hasAssetExtension(text)) return false
        if (text.trim().lowercase(Locale.ROOT) in ENCODING_NAMES) return false
        if (text.trim().lowercase(Locale.ROOT) in NONE_VALUES) return false
        if (CONSTANT_PATH.matches(text.trim())) return false
        if (FORMAT_DIRECTIVE.containsMatchIn(text)) return false
        if (note && ('<' in text || '>' in text)) return false
        return true
    }

    /**
     * Path detection stays narrow because RPG Maker text uses backslash control codes such as
     * `\C[1]` and `\N[1]`. Rejected: absolute Unix paths, UNC prefixes, drive-letter paths, URLs,
     * known RGSS folder roots (`Data/...`, `Graphics/...`), and long slash-separated tokens without
     * whitespace. Ordinary text such as `Yes/No` remains eligible, and relative paths that name a
     * known asset type are covered by [hasAssetExtension].
     */
    private fun looksLikePath(text: String): Boolean {
        val value = text.trim()
        if (value.startsWith("/") || value.startsWith("\\\\")) return true
        if (value.length >= 3 && value[1] == ':' && (value[2] == '\\' || value[2] == '/')) return true
        if (value.contains("://")) return true
        if ('/' in value) {
            val first = value.substringBefore('/').lowercase(Locale.ROOT)
            if (first in PATH_ROOTS) return true
            if (value.count { it == '/' } >= 2 && value.none { it.isWhitespace() }) return true
        }
        if ('\\' in value) {
            val first = value.substringBefore('\\').lowercase(Locale.ROOT)
            if (first in PATH_ROOTS) return true
        }
        return false
    }

    private fun hasAssetExtension(text: String): Boolean {
        if (text.any { it.isWhitespace() }) return false
        val lower = text.lowercase(Locale.ROOT)
        val dot = lower.lastIndexOf('.')
        if (dot <= 0 || dot == lower.length - 1) return false
        return lower.substring(dot + 1) in ASSET_EXTENSIONS
    }

    private fun isVxAceName(fileName: String): Boolean =
        fileName.substringAfterLast('/').lowercase(Locale.ROOT).endsWith(".rvdata2")

    private fun childPath(base: String, suffix: String): String {
        if (base.length + suffix.length <= MAX_LOCATOR_PATH) return base + suffix
        return (base + suffix).substring(0, MAX_LOCATOR_PATH)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        val output = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            output.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return output.toString()
    }

    private fun decodeStrict(charset: Charset, bytes: ByteArray): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun encodeStrict(charset: Charset, value: String): ByteArray? = try {
        val buffer = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(buffer.remaining()).also { buffer.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

    private enum class ParameterMode { TEXT, LIST }

    private val TEXT_IVARS = setOf("@name", "@nickname", "@description", "@note")

    private const val EVENT_COMMAND_CLASS = "RPG::EventCommand"

    /** Explicit command-code allowlist; no inference from arbitrary `@parameters` content. */
    private val EVENT_TEXT_PARAMETERS: Map<Int, List<Pair<Int, ParameterMode>>> = mapOf(
        102 to listOf(0 to ParameterMode.LIST),
        401 to listOf(0 to ParameterMode.TEXT),
        402 to listOf(1 to ParameterMode.TEXT),
        405 to listOf(0 to ParameterMode.TEXT),
        320 to listOf(1 to ParameterMode.TEXT),
        324 to listOf(1 to ParameterMode.TEXT),
        325 to listOf(1 to ParameterMode.TEXT),
    )

    private val PATH_ROOTS = setOf(
        "data", "graphics", "audio", "system", "fonts", "movies", "pictures", "battlebacks",
        "characters", "faces", "tilesets", "animations", "autotiles", "fogs", "panoramas", "icons",
        "windowskins", "transitions",
    )

    private val ASSET_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "bmp", "gif", "ogg", "mp3", "wav", "mid", "midi", "wma", "avi",
        "mp4", "webm", "txt", "rxdata", "rvdata", "rvdata2", "dll", "so", "ttf", "otf",
    )

    private val ENCODING_NAMES = setOf(
        "utf-8", "utf8", "us-ascii", "ascii", "shift-jis", "shift_jis", "sjis", "windows-31j",
        "cp932", "euc-jp", "gb18030", "gbk", "big5", "iso-8859-1", "utf-16", "utf-16le",
        "utf-16be", "ascii-8bit", "binary", "7bit", "8bit",
    )

    private val NONE_VALUES = setOf("none", "null", "nil", "n/a", "(none)", "-")

    private val CONSTANT_PATH = Regex("[A-Z][A-Za-z0-9_]*(::[A-Z][A-Za-z0-9_]*)+")

    // A printf/Ruby format directive. Flags deliberately exclude a literal space so ordinary text
    // such as "50% off" is not misclassified as a format string.
    private val FORMAT_DIRECTIVE = Regex("%[-+0#]*[0-9]*(\\.[0-9]+)?[sdiouxXeEfgGaAcp]")

    private val SHIFT_JIS: Charset? = try {
        Charset.forName("Shift_JIS")
    } catch (_: Exception) {
        null
    }
}
