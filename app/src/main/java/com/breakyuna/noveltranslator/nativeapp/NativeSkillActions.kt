package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceDigest
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillRecord

/** Narrow UI-facing surface over the file-backed skill store; no execution or permissions live here. */
internal class NativeSkillActions(
    val list: suspend () -> List<WorkspaceSkillRecord>,
    val read: suspend (WorkspaceSkillId) -> String?,
    val create: suspend (WorkspaceSkillDraft) -> WorkspaceSkillRecord,
    val update: suspend (WorkspaceSkillId, WorkspaceSkillDraft, WorkspaceDigest) -> WorkspaceSkillRecord,
    val delete: suspend (WorkspaceSkillId, WorkspaceDigest) -> Unit,
    val setEnabled: suspend (WorkspaceSkillId, Boolean, WorkspaceDigest) -> WorkspaceSkillRecord,
    val setOrder: suspend (WorkspaceSkillId, Int, WorkspaceDigest) -> WorkspaceSkillRecord,
)
