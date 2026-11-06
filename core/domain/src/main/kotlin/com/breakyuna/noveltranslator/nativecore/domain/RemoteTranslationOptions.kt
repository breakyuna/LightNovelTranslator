package com.breakyuna.noveltranslator.nativecore.domain

/** Eligibility is frozen with the paid confirmation; RESET replaces only successfully returned rows. */
enum class TranslationStartMode {
    NEW, CONTINUE, RESET;

    fun accepts(status: TextItemStatus): Boolean = when (status) {
        TextItemStatus.NONE -> true
        TextItemStatus.ERROR -> this != NEW
        TextItemStatus.PROCESSED -> this == RESET
        else -> false
    }
}

/** Frozen per-run policy. No credentials; prompts are deliberately omitted from toString. */
data class RemoteTranslationOptions(
    val targetLanguage: String,
    val sourceLanguage: String = "auto",
    val instructions: String = "",
    val batchSize: Int = 10,
    val batchCharacters: Int = 16_384,
    val startMode: TranslationStartMode = TranslationStartMode.NEW,
    val precedingLines: Int = 2,
    val additionalRequestsPerBatch: Int = 0,
    val deduplicateSameFile: Boolean = false,
) {
    init {
        require(targetLanguage.isNotBlank() && targetLanguage.length <= 80 && targetLanguage.none(Char::isISOControl))
        require(sourceLanguage.isNotBlank() && sourceLanguage.length <= 80 && sourceLanguage.none(Char::isISOControl))
        require(instructions.length <= 16_384 && '\u0000' !in instructions)
        require(additionalRequestsPerBatch in 0..5)
        require(precedingLines in 0..16)
        require(batchSize in 1..20 && batchCharacters in 256..32_768)
        TextProjectFingerprint.utf8(targetLanguage)
        TextProjectFingerprint.utf8(sourceLanguage)
        TextProjectFingerprint.utf8(instructions)
    }

    override fun toString(): String = "RemoteTranslationOptions(redacted)"
}
