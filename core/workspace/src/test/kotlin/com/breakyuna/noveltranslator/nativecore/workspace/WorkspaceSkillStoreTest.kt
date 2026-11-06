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

    @Test fun installPublishesCompletePackageAndRejectsOverwriteOrUnsafeResources() {
        runWorkspaceSuspend {
            val id = WorkspaceSkillId("installed-package")
            val draft = WorkspaceSkillDraft(id, "Installed package", "# Skill")
            store.install(draft, mapOf("scripts/check.js" to "lg.log('ok')", "references/guide.md" to "Guide"))
            assertEquals("# Skill", store.readContent(id))
            assertEquals("Guide", store.readFile(id, "references/guide.md")!!.toString(Charsets.UTF_8))
            try { store.install(draft, emptyMap()); org.junit.Assert.fail("Overwrote installed package") }
            catch (_: WorkspaceSkillStoreException) { }
            val rejected = WorkspaceSkillId("unsafe-package")
            try {
                store.install(WorkspaceSkillDraft(rejected, "Unsafe package", "# Unsafe"), mapOf("../escape.js" to "bad"))
                org.junit.Assert.fail("Installed unsafe package")
            } catch (_: WorkspaceSkillStoreException) { }
            assertNull(store.record(rejected))
            assertEquals("# Skill", store.readContent(id))
        }
    }

    @Test fun resourcesRoundTripRejectStaleWritesAndPreserveSkillBody() {
        runWorkspaceSuspend {
            val id = WorkspaceSkillId("resources")
            val record = store.create(WorkspaceSkillDraft(id, "Resources", content = "Original skill"))
            val first = store.writeResource(id, "scripts/main.js", "lg.log('one')", record.fingerprint, null)
            assertEquals("lg.log('one')", store.readFile(id, "scripts/main.js")!!.toString(Charsets.UTF_8))
            val second = store.writeResource(id, "scripts/main.js", "lg.log('two')", record.fingerprint, first.sha256)
            try {
                store.writeResource(id, "scripts/main.js", "stale", record.fingerprint, first.sha256)
                org.junit.Assert.fail("Stale resource overwrote newer bytes")
            } catch (error: WorkspaceSkillStoreException) {
                assertEquals(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT, error.reason)
            }
            store.renameResource(id, "scripts/main.js", "scripts/renamed.js", record.fingerprint, second.sha256)
            assertNull(store.readFile(id, "scripts/main.js"))
            assertEquals("lg.log('two')", store.readFile(id, "scripts/renamed.js")!!.toString(Charsets.UTF_8))
            store.deleteResource(id, "scripts/renamed.js", record.fingerprint, second.sha256)
            assertNull(store.readFile(id, "scripts/renamed.js"))
            assertEquals("Original skill", store.readContent(id))
            assertEquals(record.fingerprint, store.record(id)!!.fingerprint)
        }
    }

    @Test fun resourceWritesRefuseTraversalMetadataAndExistingDestination() {
        runWorkspaceSuspend {
            val id = WorkspaceSkillId("resources")
            val record = store.create(WorkspaceSkillDraft(id, "Resources", content = "Original"))
            for (path in listOf("../escape.js", "/absolute.js", "SKILL.md", "skill.json", "a/../../escape.js")) {
                try {
                    store.writeResource(id, path, "bad", record.fingerprint, null)
                    org.junit.Assert.fail("Accepted unsafe path: " + path)
                } catch (_: WorkspaceSkillStoreException) { }
            }
            val file = store.writeResource(id, "scripts/a.js", "first", record.fingerprint, null)
            store.writeResource(id, "scripts/b.js", "second", record.fingerprint, null)
            try {
                store.renameResource(id, "scripts/a.js", "scripts/b.js", record.fingerprint, file.sha256)
                org.junit.Assert.fail("Overwrote existing destination")
            } catch (error: WorkspaceSkillStoreException) {
                assertEquals(WorkspaceSkillStoreException.Reason.ALREADY_EXISTS, error.reason)
            }
            assertEquals("first", store.readFile(id, "scripts/a.js")!!.toString(Charsets.UTF_8))
            assertEquals("second", store.readFile(id, "scripts/b.js")!!.toString(Charsets.UTF_8))
        }
    }

    @Test fun builtInResourceTreeExposesScriptsAndReferencesForExecution() {
        writeBuiltIn("bundled", "Bundled", "Read scripts/main.js")
        val directory = File(builtInRoot, "bundled")
        File(directory, "scripts").mkdirs()
        File(directory, "scripts/main.js").writeText("lg.log('bundled')")
        File(directory, "references").mkdirs()
        File(directory, "references/help.md").writeText("Reference")
        val id = WorkspaceSkillId("bundled")
        runWorkspaceSuspend {
            assertEquals(setOf("bundled/SKILL.md", "bundled/scripts/main.js", "bundled/references/help.md"),
                store.files(id).map { it.path.value }.toSet())
            assertEquals("Reference", store.readFile(id, "references/help.md")!!.toString(Charsets.UTF_8))
            val source = WorkspaceSkillScriptSource(store, store).resolve(id.value, "scripts/main.js")
            assertEquals("lg.log('bundled')", source.text)
            assertNull(store.readFile(id, "../outside.js"))
            assertNull(store.readFile(id, METADATA_FILE))
        }
    }

    @Test fun resourceTreeRejectsLinksAcrossSkillsAndDirectoryCycles() {
        runWorkspaceSuspend {
            val id = WorkspaceSkillId("links")
            store.create(WorkspaceSkillDraft(id, "Links", content = "body"))
            store.create(WorkspaceSkillDraft(WorkspaceSkillId("other"), "Other", content = "private"))
            val link = File(userRoot, "links/references")
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(userRoot, "other").toPath())
            assertNull(store.readFile(id, "references/SKILL.md"))
            try { store.files(id); org.junit.Assert.fail("Accepted cross-skill link") }
            catch (error: WorkspaceSkillStoreException) {
                assertEquals(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, error.reason)
            }
            java.nio.file.Files.delete(link.toPath())
            java.nio.file.Files.createSymbolicLink(link.toPath(), File(userRoot, "links").toPath())
            try { store.files(id); org.junit.Assert.fail("Accepted directory cycle") }
            catch (error: WorkspaceSkillStoreException) {
                assertEquals(WorkspaceSkillStoreException.Reason.PATH_ESCAPE, error.reason)
            }
            java.nio.file.Files.delete(link.toPath())
        }
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
                WorkspaceSkillDraft(
                    id,
                    "Alpha",
                    content = "next",
                    order = 3,
                    languages = setOf("JA", "en"),
                    tags = setOf("Style", "analysis"),
                ),
                created.fingerprint,
            )
        }
        assertNotEquals(created.fingerprint, updated.fingerprint)
        assertEquals(setOf("ja", "en"), updated.summary.languages)
        assertEquals(setOf("style", "analysis"), updated.summary.tags)
        assertEquals(3, updated.summary.order)
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
    }

    @Test fun builtInSkillsAreReadOnlyAndCanBeOverriddenByUserSkills() {
        writeBuiltIn("builtin-1", "Built In", "built body")
        val id = WorkspaceSkillId("builtin-1")
        val builtIn = runWorkspaceSuspend { store.record(id) }!!
        assertEquals(WorkspaceSkillSource.BUILT_IN, builtIn.summary.source)
        assertEquals("built body", runWorkspaceSuspend { store.readContent(id) })
        val disabled = runWorkspaceSuspend { store.setEnabled(id, false, builtIn.fingerprint) }
        assertFalse(disabled.summary.enabled)
        store = WorkspaceSkillStore.withBuiltInRoot(userRoot, builtInRoot)
        assertFalse(runWorkspaceSuspend { store.record(id) }!!.summary.enabled)
        assertEquals("built body", runWorkspaceSuspend { store.readContent(id) })
        assertStoreReason(WorkspaceSkillStoreException.Reason.STALE_FINGERPRINT) {
            store.setEnabled(id, true, builtIn.fingerprint)
        }
        val ordered = runWorkspaceSuspend { store.setOrder(id, 2, disabled.fingerprint) }
        assertEquals(2, ordered.summary.order)
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
