package com.breakyuna.noveltranslator.nativeapp

/**
 * Bounded private-storage projection for the files page. The categories are disjoint:
 * project + material + artifact + other == [filesBytes], and
 * filesBytes + cacheBytes == [totalPrivateBytes]. Cache is reported separately because the
 * system may reclaim it without user action. These are byte counts, not a performance claim.
 */
data class NativeStorageUsage(
    val projectBytes: Long,
    val workspaceMaterialBytes: Long,
    val workspaceArtifactBytes: Long,
    val otherFilesBytes: Long,
    val cacheBytes: Long,
) {
    init {
        require(projectBytes >= 0 && workspaceMaterialBytes >= 0 && workspaceArtifactBytes >= 0)
        require(otherFilesBytes >= 0 && cacheBytes >= 0)
    }

    val filesBytes: Long
        get() = saturatingAdd(
            saturatingAdd(projectBytes, workspaceMaterialBytes),
            saturatingAdd(workspaceArtifactBytes, otherFilesBytes),
        )

    val totalPrivateBytes: Long get() = saturatingAdd(filesBytes, cacheBytes)

    val workspaceBytes: Long get() = saturatingAdd(workspaceMaterialBytes, workspaceArtifactBytes)

    private fun saturatingAdd(left: Long, right: Long): Long {
        val sum = left + right
        return if (sum < 0) Long.MAX_VALUE else sum
    }
}
