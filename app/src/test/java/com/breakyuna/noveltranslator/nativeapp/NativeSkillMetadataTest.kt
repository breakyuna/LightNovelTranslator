package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillDraft
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillId
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillPolicy
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStore
import com.breakyuna.noveltranslator.nativecore.workspace.WorkspaceSkillStoreException
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

class NativeSkillMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun editorDraftUpdatesLanguagesTagsOrderAndEnabledThroughTheExistingCasPath() = runBlocking<Unit> {
        val root = temporary.newFolder("skills")
        val store = WorkspaceSkillStore(root)
        try {
            val id = WorkspaceSkillId("user-alpha")
            val created = store.create(WorkspaceSkillDraft(id, "Alpha", content = "body", order = 1))
            val input = NativeSkillEditorInput(
                name = "  Alpha  ",
                description = "  First  ",
                content = "body",
                enabled = false,
                order = "4",
                languages = "JA, en, ja",
                tags = "Style, theme",
            )
            val updated = store.update(id, input.toDraft(id), created.fingerprint)
            assertEquals("Alpha", updated.summary.name)
            assertEquals("First", updated.summary.description)
            assertFalse(updated.summary.enabled)
            assertEquals(4, updated.summary.order)
            assertEquals(setOf("ja", "en"), updated.summary.languages)
            assertEquals(setOf("style", "theme"), updated.summary.tags)

            val stale = runCatching {
                store.update(id, input.copy(name = "Changed").toDraft(id), created.fingerprint)
            }.exceptionOrNull()
            assertTrue(stale is WorkspaceSkillStoreException)
            assertEquals(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT,
                (stale as WorkspaceSkillStoreException).reason)
            assertEquals("Alpha", store.record(id)!!.summary.name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun legacyMetadataWithoutPolicyResolvesToTheSafeDefault() = runBlocking<Unit> {
        val root = temporary.newFolder("legacy-skills")
        val directory = File(root, "legacy").apply { require(mkdirs()) }
        val body = "legacy body"
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        File(directory, "SKILL.md").writeText(body, Charsets.UTF_8)
        File(directory, "skill.json").writeText(
            """{"name":"Legacy","contentSha256":"$sha"}""",
            Charsets.UTF_8,
        )
        val store = WorkspaceSkillStore(root)
        try {
            val policy = requireNotNull(store.record(WorkspaceSkillId("legacy"))).summary.policy
            assertEquals(WorkspaceSkillPolicy.DEFAULT, policy)
            assertTrue(policy.allowMaterials)
            assertTrue(policy.allowArtifacts)
            assertTrue(policy.allowPrint)
            assertFalse(policy.allowSearch)
            assertFalse(policy.allowFetch)
            assertFalse(policy.allowProposeChanges)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun editorDraftPreservesExistingPolicyAndNewDraftsUseSafeDefault() = runBlocking<Unit> {
        val root = temporary.newFolder("policy-skills")
        val store = WorkspaceSkillStore(root)
        try {
            val id = WorkspaceSkillId("user-policy")
            val custom = WorkspaceSkillPolicy(
                allowSearch = true,
                allowFetch = true,
                allowProposeChanges = true,
                maxRunMillis = 5_000,
            )
            val created = store.create(WorkspaceSkillDraft(id, "Policy", content = "body", policy = custom))
            val input = NativeSkillEditorInput(
                name = "Policy renamed",
                description = "Edited",
                content = "body changed",
                enabled = true,
                order = "2",
                languages = "zh",
                tags = "style",
                policy = created.summary.policy,
            )
            val updated = store.update(id, input.toDraft(id), created.fingerprint)
            assertEquals(custom, updated.summary.policy)
            assertEquals("Policy renamed", updated.summary.name)
            assertEquals("body changed", store.readContent(id))

            val fresh = NativeSkillEditorInput(
                name = "Fresh",
                description = "",
                content = "new body",
                enabled = true,
                order = "0",
                languages = "",
                tags = "",
            ).toDraft(WorkspaceSkillId("fresh"))
            assertEquals(WorkspaceSkillPolicy.DEFAULT, fresh.policy)
            assertFalse(fresh.policy.allowFetch)
            assertFalse(fresh.policy.allowProposeChanges)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun directRunNetworkCanOnlyNarrowTheStoredPolicy() {
        assertFalse(nativeDirectExecutionFetchAllowed(WorkspaceSkillPolicy.DEFAULT, requested = true))
        assertFalse(nativeDirectExecutionFetchAllowed(
            WorkspaceSkillPolicy(allowFetch = true), requested = false))
        assertTrue(nativeDirectExecutionFetchAllowed(
            WorkspaceSkillPolicy(allowFetch = true), requested = true))
        assertFalse(nativeSkillNetworkCheckboxEnabled(WorkspaceSkillPolicy.DEFAULT))
        assertTrue(nativeSkillNetworkCheckboxEnabled(WorkspaceSkillPolicy(allowFetch = true)))
    }

    @Test fun labelsAreBoundedNormalizedAndConflictErrorsStayEditorLocal() {
        assertEquals(setOf("ja", "en"), NativeSkillMetadata.parseLabels(" JA, en\nja ", 2, 32))
        assertEquals("en, ja", NativeSkillMetadata.formatLabels(setOf("ja", "en")))
        assertThrows(IllegalArgumentException::class.java) { NativeSkillMetadata.parseOrder("-1") }

        val stale = WorkspaceSkillStoreException(
            WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT,
            WorkspaceSkillId("user-alpha"),
        )
        assertEquals(R.string.native_skills_conflict, nativeSkillEditorError(stale))
        assertEquals(R.string.native_skills_readonly_error, nativeSkillEditorError(
            WorkspaceSkillStoreException(WorkspaceSkillStoreException.Reason.READ_ONLY)))
        assertEquals(R.string.native_skills_metadata_invalid,
            nativeSkillEditorError(IllegalArgumentException("invalid")))
        assertEquals(R.string.native_skills_error, nativeSkillEditorError(IllegalStateException("io")))
    }
}
