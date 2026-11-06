package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillFile
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord

internal data class NativeSkillExecutionTarget(
    val ref: com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef,
    val revision: Long,
    val identity: com.breakyuna.noveltranslator.nativecore.domain.RunIdentity,
    val skill: WorkspaceSkillRecord,
    val file: WorkspaceSkillFile,
)

/** Application ports own persistence, execution identity and runtime permissions. */
internal class NativeSkillActions(
    val list: suspend () -> List<WorkspaceSkillRecord>,
    val read: suspend (WorkspaceSkillId) -> String?,
    val create: suspend (WorkspaceSkillDraft) -> WorkspaceSkillRecord,
    val update: suspend (WorkspaceSkillId, WorkspaceSkillDraft, WorkspaceDigest) -> WorkspaceSkillRecord,
    val delete: suspend (WorkspaceSkillId, WorkspaceDigest) -> Unit,
    val setEnabled: suspend (WorkspaceSkillId, Boolean, WorkspaceDigest) -> WorkspaceSkillRecord,
    val setOrder: suspend (WorkspaceSkillId, Int, WorkspaceDigest) -> WorkspaceSkillRecord,
    val writeResource: (suspend (WorkspaceSkillId, String, String, WorkspaceDigest, String?) -> WorkspaceSkillFile)? = null,
    val deleteResource: (suspend (WorkspaceSkillId, String, WorkspaceDigest, String) -> Unit)? = null,
    val renameResource: (suspend (WorkspaceSkillId, String, String, WorkspaceDigest, String) -> Unit)? = null,
    val prepareExecution: ((WorkspaceSkillRecord, WorkspaceSkillFile) -> NativeSkillExecutionTarget?)? = null,
    val execute: (suspend (NativeSkillExecutionTarget, String, Boolean) -> com.breakyuna.noveltranslator.nativecore.workspace.ProjectWorkspaceAnalysisRun)? = null,
    val stopExecution: ((com.breakyuna.noveltranslator.nativecore.domain.RunIdentity) -> Unit)? = null,
    val files: suspend (WorkspaceSkillId) -> List<WorkspaceSkillFile> = { emptyList() },
    val readFileText: suspend (WorkspaceSkillId, String) -> String? = { _, _ -> null },
)
