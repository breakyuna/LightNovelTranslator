package com.breakyuna.noveltranslator.nativecore.domain

/**
 * Explicit manual intent to replace the translated names of one item. The command never carries
 * translated body text or a status: those facts stay under [ManualTextEdit]. A null [nameSource]
 * preserves the stored source names; a supplied list replaces them together with the translation.
 * The empty list is an explicit "no names" value, matching [TextItemMetadata]'s nullable lists.
 */
class ManualNameEdit(
    val commandId: CommandId,
    val ref: LocalProjectRef,
    val itemId: ItemId,
    val expectedRevision: Long,
    val baseFingerprint: ObjectFingerprint,
    nameTranslation: List<String>,
    nameSource: List<String>? = null,
) {
    val nameTranslation: List<String> = frozenList(nameTranslation)
    val nameSource: List<String>? = nameSource?.let { frozenList(it) }

    init {
        require(expectedRevision >= 0)
        require(commandId.value.length <= MAX_COMMAND_CHARS)
        require(this.nameTranslation.size <= MAX_NAMES)
        require(this.nameSource == null || this.nameSource.size <= MAX_NAMES)
        val names = (this.nameSource ?: emptyList()) + this.nameTranslation
        require(names.sumOf { it.length.toLong() } <= MAX_TOTAL_NAME_CHARS)
        for (name in names) {
            require(name.length <= MAX_NAME_CHARS && '\u0000' !in name) { "Invalid name" }
            // Strict UTF-8 rejects isolated surrogates instead of hashing replacement bytes.
            TextProjectFingerprint.utf8(name)
        }
    }

    private val digestValue: String by lazy { TextProjectFingerprint.nameCommand(this) }

    fun digest(): String = digestValue

    override fun toString(): String = "ManualNameEdit(redacted)"

    companion object {
        const val MAX_NAMES = 32
        const val MAX_NAME_CHARS = 4_096
        const val MAX_TOTAL_NAME_CHARS = 16_384
        const val MAX_COMMAND_CHARS = 2_048
    }
}

/**
 * Name editing shares the storage write store's revision timeline and receipt namespace with
 * text edits. [ProjectNamePort.checkedNameReceipt] verifies the full immutable payload before
 * returning a replay.
 */
interface ProjectNamePort {
    suspend fun editName(command: ManualNameEdit): ManualEditReceipt
    suspend fun checkedNameReceipt(command: ManualNameEdit): ManualEditReceipt?
    suspend fun nameReceipt(ref: LocalProjectRef, commandId: CommandId): ManualEditReceipt?
}
