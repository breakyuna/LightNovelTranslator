package com.breakyuna.noveltranslator.nativecore.workspace

import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceSkillStoreTest {
    private lateinit var userRoot: File
    private lateinit var builtInRoot: File
    private lateinit var store: WorkspaceSkillStore

    @Before fun setup() {
        userRoot = tempRoot("user")
        builtInRoot = tempRoot("builtin")
        store = WorkspaceSkillStore.withBuiltInRoot(userRoot, builtInRoot)
    }

    @After fun cleanup() {
        userRoot.deleteRecursively()
        builtInRoot.deleteRecursively()
    }

    @Test fun createsReadsAndListsUserSkillsInDeterministicOrder() {
        val created = runWorkspaceSuspend {
            store.create(
                WorkspaceSkillDraft(
                    id = WorkspaceSkillId("alpha"),
                    name = "Alpha",
                    description = "first",
                    content = "body",
                    order = 2,
                    languages = setOf("JA"),
                    tags = setOf("Analysis"),
                ),
            )
        }
        assertEquals(WorkspaceSkillSource.USER, created.summary.source)
        assertEquals(setOf("ja"), created.summary.languages)
        assertEquals(setOf("analysis"), created.summary.tags)
        assertEquals(64, created.fingerprint.sha256.length)
        assertEquals(
            created.fingerprint,
            runWorkspaceSuspend { store.record(WorkspaceSkillId("alpha")) }!!.fingerprint,
        )
        assertEquals("body", runWorkspaceSuspend { store.readContent(WorkspaceSkillId("alpha")) })
        assertEquals("bo", runWorkspaceSuspend { store.readContent(WorkspaceSkillId("alpha"), 2) })

        runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("gamma"), "Gamma", content = "g", order = 1))
        }
        runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("beta"), "Beta", content = "b", order = 1))
        }
        val listed = runWorkspaceSuspend { store.list() }
        assertEquals(listOf("Beta", "Gamma", "Alpha"), listed.map { it.summary.name })
        assertTrue(listed.all { it.summary.source == WorkspaceSkillSource.USER })
    }

    @Test fun casFingerprintRejectsStaleUpdatesAndAllMetadataMutations() {
        val id = WorkspaceSkillId("alpha")
        val created = runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(id, "Alpha", content = "body", order = 1))
        }
        assertStoreReason(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT) {
            store.update(
                id,
                WorkspaceSkillDraft(id, "Alpha", content = "next"),
                WorkspaceDigest(sha256Of("stale")),
            )
        }
        assertEquals("body", runWorkspaceSuspend { store.readContent(id) })

        val updated = runWorkspaceSuspend {
            store.update(
                id,
                WorkspaceSkillDraft(id, "Alpha", content = "next", order = 3),
                created.fingerprint,
            )
        }
        assertNotEquals(created.fingerprint, updated.fingerprint)
        assertEquals("next", runWorkspaceSuspend { store.readContent(id) })

        val disabled = runWorkspaceSuspend { store.setEnabled(id, false, updated.fingerprint) }
        assertFalse(disabled.summary.enabled)
        assertStoreReason(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT) {
            store.setEnabled(id, true, created.fingerprint)
        }

        val reordered = runWorkspaceSuspend { store.setOrder(id, 0, disabled.fingerprint) }
        assertEquals(0, reordered.summary.order)
        val renamed = runWorkspaceSuspend { store.rename(id, "Renamed", reordered.fingerprint) }
        assertEquals("Renamed", renamed.summary.name)

        runWorkspaceSuspend { store.delete(id, renamed.fingerprint) }
        assertNull(runWorkspaceSuspend { store.record(id) })
    }

    @Test fun duplicateNamesAndInvalidIdentitiesAreRejected() {
        runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("alpha"), "Alpha", content = "a"))
        }
        val beta = runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("beta"), "Beta", content = "b"))
        }
        assertStoreReason(WorkspaceSkillStoreException.Reason.DUPLICATE_NAME) {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("gamma"), "alpha", content = "c"))
        }
        assertStoreReason(WorkspaceSkillStoreException.Reason.DUPLICATE_NAME) {
            store.rename(beta.summary.id, "Alpha", beta.fingerprint)
        }
        assertStoreReason(WorkspaceSkillStoreException.Reason.INVALID_ID) {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId(".."), "Dot", content = "x"))
        }
        assertStoreReason(WorkspaceSkillStoreException.Reason.INVALID_ID) {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("CON"), "Reserved", content = "x"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceSkillDraft(
                WorkspaceSkillId("oversize"),
                "Oversize",
                content = "x".repeat(WorkspaceSkillLimits.MAX_CONTENT_BYTES + 1),
            )
        }
    }

    @Test fun builtInSkillsAreReadOnlyAndCanBeOverriddenByUserSkills() {
        writeBuiltIn("builtin-1", "Built In", "built body")
        val id = WorkspaceSkillId("builtin-1")
        val builtIn = runWorkspaceSuspend { store.record(id) }!!
        assertEquals(WorkspaceSkillSource.BUILT_IN, builtIn.summary.source)
        assertEquals("built body", runWorkspaceSuspend { store.readContent(id) })
        assertStoreReason(WorkspaceSkillStoreException.Reason.READ_ONLY) {
            store.setEnabled(id, false, builtIn.fingerprint)
        }
        assertStoreReason(WorkspaceSkillStoreException.Reason.READ_ONLY) {
            store.delete(id, builtIn.fingerprint)
        }
        assertStoreReason(WorkspaceSkillStoreException.Reason.READ_ONLY) {
            store.update(id, WorkspaceSkillDraft(id, "Built In", content = "changed"), builtIn.fingerprint)
        }

        val userOverride = runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(id, "Built In", content = "user body"))
        }
        assertEquals(WorkspaceSkillSource.USER, userOverride.summary.source)
        val merged = runWorkspaceSuspend { store.list() }
        assertEquals(1, merged.size)
        assertEquals(WorkspaceSkillSource.USER, merged.single().summary.source)
        assertEquals("user body", runWorkspaceSuspend { store.readContent(id) })

        runWorkspaceSuspend { store.delete(id, runWorkspaceSuspend { store.record(id) }!!.fingerprint) }
        assertEquals(WorkspaceSkillSource.BUILT_IN, runWorkspaceSuspend { store.record(id) }!!.summary.source)
    }

    @Test fun builtInNameIsShadowedByAUserSkillWithTheSameName() {
        writeBuiltIn("builtin-1", "Shared", "built")
        runWorkspaceSuspend {
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("user-1"), "Shared", content = "user"))
        }
        val listed = runWorkspaceSuspend { store.list() }
        assertEquals(1, listed.size)
        assertEquals(WorkspaceSkillId("user-1"), listed.single().summary.id)
    }

    @Test fun tamperedContentIsDetectedOnRead() {
        val id = WorkspaceSkillId("alpha")
        runWorkspaceSuspend { store.create(WorkspaceSkillDraft(id, "Alpha", content = "body")) }
        File(File(userRoot, "alpha"), "SKILL.md").writeText("tampered")
        assertStoreReason(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT) { store.readContent(id) }
    }

    @Test fun symlinkEscapeIsRejected() {
        val escaped = File(userRoot, "escaped")
        escaped.mkdirs()
        File(escaped, METADATA_FILE).writeText("{}")
        File(escaped, CONTENT_FILE).writeText("outside")
        val escaping = WorkspaceSkillStore(
            userRoot,
            canonicalizer = { candidate ->
                if (candidate.parentFile?.name == "escaped") "/outside/" + candidate.name else candidate.absolutePath
            },
        )
        assertStoreReason(WorkspaceSkillStoreException.Reason.PATH_ESCAPE) { escaping.list() }
    }

    @Test fun metadataTamperingWithUnknownFieldsIsRejected() {
        val id = WorkspaceSkillId("alpha")
        val created = runWorkspaceSuspend { store.create(WorkspaceSkillDraft(id, "Alpha", content = "body")) }
        val metadata = File(File(userRoot, "alpha"), "skill.json")
        metadata.writeText(
            "{\"name\":\"Alpha\",\"description\":\"\",\"enabled\":true,\"order\":0," +
                "\"languages\":[],\"tags\":[],\"contentSha256\":\"${created.summary.contentSha256}\"," +
                "\"unexpected\":1}",
        )
        assertStoreReason(WorkspaceSkillStoreException.Reason.INVALID_METADATA) { store.list() }
    }

    private fun writeBuiltIn(id: String, name: String, content: String) {
        val skillId = WorkspaceSkillId(id)
        val directory = File(builtInRoot, id)
        directory.mkdirs()
        File(directory, CONTENT_FILE).writeText(content)
        val summary = WorkspaceSkillSummary(
            id = skillId,
            name = name,
            description = "built in",
            enabled = true,
            order = 5,
            source = WorkspaceSkillSource.BUILT_IN,
            languages = setOf("ja"),
            tags = setOf("builtin"),
            contentPath = WorkspacePath("$id/$CONTENT_FILE"),
            contentSha256 = sha256Of(content),
        )
        File(directory, METADATA_FILE).writeBytes(WorkspaceSkillMetadataCodec.encode(summary))
    }

    private fun assertStoreReason(
        reason: WorkspaceSkillStoreException.Reason,
        block: suspend () -> Unit,
    ) {
        val failure = assertThrows(WorkspaceSkillStoreException::class.java) { runWorkspaceSuspend { block() } }
        assertEquals(reason, failure.reason)
    }

    private fun tempRoot(kind: String): File =
        File(System.getProperty("java.io.tmpdir"), "lnt-skill-$kind-" + UUID.randomUUID()).apply { mkdirs() }
}
