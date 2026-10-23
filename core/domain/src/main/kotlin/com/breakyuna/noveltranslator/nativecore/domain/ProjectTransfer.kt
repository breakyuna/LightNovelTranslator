package com.breakyuna.noveltranslator.nativecore.domain

/** Bounded import of the native TXT v1/v2 subset, not an LG/ZIP/backup importer. */
data class NativeImportLimits(
    val maxFileBytes: Long = 256L * 1024 * 1024,
    val maxHistoryRows: Int = 200_000,
    val maxValidationChars: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxFileBytes in 1..512L * 1024 * 1024)
        require(maxHistoryRows in 1..1_000_000)
        require(maxValidationChars in 1..256L * 1024 * 1024)
    }
}

data class RecoveryProblem(val instanceId: ProjectInstanceId, val reason: TextProjectException.Reason)
class ProjectRecoveryReport(val recovered: Int, problems: List<RecoveryProblem>) {
    val problems: List<RecoveryProblem> = java.util.Collections.unmodifiableList(ArrayList(problems))
}

class TextItemDetails(val revision: Long, val item: TextProjectItem)
