package com.breakyuna.noveltranslator.nativecore.workspace

import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.ProjectId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectInstanceId
import com.breakyuna.noveltranslator.nativecore.domain.RecoveryGeneration
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectLimits
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectSummary
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkspaceMaterialSourceTest {
    private lateinit var root: File
    private lateinit var ref: LocalProjectRef

    @Before fun setup() {
        root = File(System.getProperty("java.io.tmpdir"), "lnt-material-source-" + UUID.randomUUID())
            .apply { mkdirs() }
        ref = LocalProjectRef(
            ProjectId("project-material"),
            ProjectInstanceId("instance-material"),
            RecoveryGeneration(1),
        )
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun materializesFixedRevisionAndBindsAssetDigestsInManifest() = runBlocking {
        write("uploads/user.txt", "keep me")
        val project = FakeMaterialProject(
            rev = 7,
            assets = listOf(
                asset(1, "novel/ch1.txt", ProjectAssetFormat.TXT, "hello", 2, ordinal = 0),
                asset(2, "notes.md", ProjectAssetFormat.MD_V2, "# Notes\n", 1, ordinal = 1),
                asset(3, "book.pdf", ProjectAssetFormat.PDF, "%PDF", 0, ordinal = 2),
            ),
        )
        val source = WorkspaceMaterialSource(root, ref, 7, project)
        val manifest = source.snapshot()

        assertEquals(ref, manifest.ref)
        assertEquals(7L, manifest.revision)
        assertEquals(3, manifest.itemCount)
        assertEquals(3, manifest.assets.size)
        assertEquals(64, manifest.digest.sha256.length)
        assertTrue(File(root, WorkspaceMaterialSource.MANIFEST_NAME).isFile)

        val text = requireNotNull(source.materialForAsset(1))
        assertEquals("sources/novel/ch1.txt", text.path.value)
        assertEquals(sha256Of("hello"), text.sha256)
        assertEquals("hello", String(source.read(text.id, 16).bytes, Charsets.UTF_8))
        assertNull(source.materialForAsset(3))
        assertFalse(File(root, "sources/book.pdf").exists())
        assertEquals("keep me", File(root, "uploads/user.txt").readText())

        assertEquals(
            listOf("sources/notes.md", "sources/novel/ch1.txt"),
            source.list(WorkspaceMaterialKind.SOURCE).map { it.path.value },
        )
        val dataset = source.list(WorkspaceMaterialKind.DATASET).single()
        assertEquals("datasets/assets.jsonl", dataset.path.value)
        assertTrue(dataset.mediaType.startsWith("application/"))
        assertEquals(1, source.list(WorkspaceMaterialKind.UPLOAD).size)
        assertTrue(File(root, "reference").isDirectory)
        assertTrue(File(root, "work").isDirectory)

        assertTrue(File(root, WorkspaceMaterialSource.MANIFEST_NAME).readText().contains("\"asset_id\":1"))
        val onDisk = WorkspaceMaterialManifestCodec.decode(
            File(root, WorkspaceMaterialSource.MANIFEST_NAME).readText(),
        )
        assertEquals(manifest.digest, onDisk.digest)
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceMaterialManifestCodec.decode(
                File(root, WorkspaceMaterialSource.MANIFEST_NAME).readText()
                    .replace("\"revision\":7", "\"revision\":8"),
            )
        }
        source.verify()
        val second = WorkspaceMaterialSource(root, ref, 7, project).snapshot()
        assertEquals(manifest.digest, second.digest)
    }

    @Test fun rejectsStaleRevisionAndProjectIdentity() = runBlocking {
        val project = FakeMaterialProject(rev = 7)
        val source = WorkspaceMaterialSource(root, ref, 7, project)
        source.snapshot()

        project.rev = 8
        val stale = assertThrows(WorkspaceMaterialStaleRevision::class.java) {
            runBlocking { source.snapshot() }
        }
        assertEquals(7L, stale.expectedRevision)
        assertEquals(8L, stale.actualRevision)
        val staleList = assertThrows(WorkspaceMaterialStaleRevision::class.java) {
            runBlocking { source.list(WorkspaceMaterialKind.SOURCE) }
        }
        assertEquals(8L, staleList.actualRevision)

        project.rev = 7
        project.ref = LocalProjectRef(
            ProjectId("other-project"),
            ref.instanceId,
            ref.generation,
        )
        val identity = assertThrows(WorkspaceMaterialSnapshotException::class.java) {
            runBlocking { source.snapshot() }
        }
        assertEquals(WorkspaceMaterialSnapshotException.Reason.PROJECT_IDENTITY, identity.reason)
    }

    @Test fun rejectsAssetMismatchChangedDuringSnapshotAndDuplicateNames() = runBlocking {
        val project = FakeMaterialProject(
            rev = 7,
            assets = listOf(asset(1, "same.txt", ProjectAssetFormat.TXT, "one", 1)),
            bodies = mutableMapOf(1L to "different".toByteArray(Charsets.UTF_8)),
        )
        val mismatch = assertThrows(WorkspaceMaterialSnapshotException::class.java) {
            runBlocking { WorkspaceMaterialSource(root, ref, 7, project).snapshot() }
        }
        assertEquals(WorkspaceMaterialSnapshotException.Reason.ASSET_MISMATCH, mismatch.reason)

        val duplicateRoot = File(root, "duplicate").apply { mkdirs() }
        val duplicate = FakeMaterialProject(
            rev = 7,
            assets = listOf(
                asset(1, "same.txt", ProjectAssetFormat.TXT, "one", 1, ordinal = 0),
                asset(2, "same.txt", ProjectAssetFormat.TXT, "two", 1, ordinal = 1),
            ),
        )
        val duplicateError = assertThrows(WorkspaceMaterialSnapshotException::class.java) {
            runBlocking { WorkspaceMaterialSource(duplicateRoot, ref, 7, duplicate).snapshot() }
        }
        assertEquals(WorkspaceMaterialSnapshotException.Reason.DUPLICATE_NAME, duplicateError.reason)
    }

    @Test fun rejectsReservedAndNonNormalizedAssetPathsWithoutWritingOutside() = runBlocking {
        val reserved = FakeMaterialProject(
            rev = 3,
            assets = listOf(asset(1, "CON.txt", ProjectAssetFormat.TXT, "one", 1)),
        )
        val pathError = assertThrows(WorkspaceMaterialSnapshotException::class.java) {
            runBlocking { WorkspaceMaterialSource(root, ref, 3, reserved).snapshot() }
        }
        assertEquals(WorkspaceMaterialSnapshotException.Reason.PATH_POLICY, pathError.reason)
        assertFalse(File(root.parentFile, "escape.txt").exists())

        assertThrows(IllegalArgumentException::class.java) { WorkspacePath("../escape.txt") }
        assertThrows(IllegalArgumentException::class.java) { WorkspacePath("/absolute.txt") }
        assertThrows(IllegalArgumentException::class.java) { WorkspacePath("dir/../escape.txt") }
        assertThrows(IllegalArgumentException::class.java) { WorkspacePath("dir\\escape.txt") }
        Unit
    }

    @Test fun enforcesMaterialBudgetAndTextProjectLimits() {
        val one = FakeMaterialProject(
            rev = 5,
            assets = listOf(asset(1, "a.txt", ProjectAssetFormat.TXT, "hello", 1)),
        )
        val count = assertThrows(WorkspaceBudgetExceeded::class.java) {
            runBlocking {
                WorkspaceMaterialSource(root, ref, 5, one, budget = WorkspaceBudget(maxMaterials = 1)).snapshot()
            }
        }
        assertEquals(WorkspaceBudgetKind.MATERIALS, count.kind)

        val perMaterial = assertThrows(WorkspaceBudgetExceeded::class.java) {
            runBlocking {
                WorkspaceMaterialSource(
                    root, ref, 5, one,
                    budget = WorkspaceBudget(maxMaterialBytes = 4, maxTotalMaterialBytes = 8),
                ).snapshot()
            }
        }
        assertEquals(WorkspaceBudgetKind.MATERIAL_BYTES, perMaterial.kind)

        val totalOne = FakeMaterialProject(
            rev = 5,
            assets = listOf(
                asset(1, "large.txt", ProjectAssetFormat.TXT, "x".repeat(600), 1),
            ),
        )
        val total = assertThrows(WorkspaceBudgetExceeded::class.java) {
            runBlocking {
                WorkspaceMaterialSource(
                    root, ref, 5, totalOne,
                    budget = WorkspaceBudget(maxMaterialBytes = 700, maxTotalMaterialBytes = 800),
                ).snapshot()
            }
        }
        assertEquals(WorkspaceBudgetKind.TOTAL_MATERIAL_BYTES, total.kind)

        val sourceLimit = assertThrows(WorkspaceMaterialSnapshotException::class.java) {
            runBlocking {
                WorkspaceMaterialSource(
                    root, ref, 5, one, textLimits = TextProjectLimits(maxSourceBytes = 2),
                ).snapshot()
            }
        }
        assertEquals(WorkspaceMaterialSnapshotException.Reason.PROJECT_LIMIT, sourceLimit.reason)

        val items = assertThrows(WorkspaceMaterialSnapshotException::class.java) {
            runBlocking {
                val twoItems = FakeMaterialProject(
                    rev = 5,
                    assets = listOf(asset(1, "two.txt", ProjectAssetFormat.TXT, "xy", 2)),
                )
                WorkspaceMaterialSource(
                    root, ref, 5, twoItems, textLimits = TextProjectLimits(maxItems = 1),
                ).snapshot()
            }
        }
        assertEquals(WorkspaceMaterialSnapshotException.Reason.PROJECT_LIMIT, items.reason)
    }

    @Test fun readIsBoundedAndDetectsTampering() = runBlocking {
        val project = FakeMaterialProject(
            rev = 2,
            assets = listOf(asset(1, "novel.txt", ProjectAssetFormat.TXT, "alpha", 1)),
        )
        val source = WorkspaceMaterialSource(root, ref, 2, project)
        val material = requireNotNull(source.materialForAsset(1))
        val tooLarge = assertThrows(WorkspaceMaterialUnavailable::class.java) {
            runBlocking { source.read(material.id, 4) }
        }
        assertEquals(WorkspaceMaterialUnavailable.Reason.TOO_LARGE, tooLarge.reason)

        val file = File(root, "sources/novel.txt")
        assertTrue(file.setWritable(true))
        file.writeText("ALPHA")
        val mismatch = assertThrows(WorkspaceMaterialUnavailable::class.java) {
            runBlocking { source.read(material.id, 16) }
        }
        assertEquals(WorkspaceMaterialUnavailable.Reason.FINGERPRINT_MISMATCH, mismatch.reason)
    }

    private fun write(relative: String, text: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun asset(
        id: Long,
        path: String,
        format: ProjectAssetFormat,
        body: String,
        itemCount: Int,
        ordinal: Int = 0,
    ): ProjectAssetSummary = ProjectAssetSummary(
        id = id,
        path = RelativePath(path),
        format = format,
        ordinal = ordinal,
        enabled = true,
        originalSize = body.toByteArray(Charsets.UTF_8).size.toLong(),
        sha256 = sha256Of(body),
        itemCount = itemCount,
    )

    private class FakeMaterialProject(
        var rev: Long,
        var ref: LocalProjectRef = LocalProjectRef(
            ProjectId("project-material"),
            ProjectInstanceId("instance-material"),
            RecoveryGeneration(1),
        ),
        var assets: List<ProjectAssetSummary> = emptyList(),
        val bodies: MutableMap<Long, ByteArray> = mutableMapOf(),
    ) : WorkspaceMaterialProjectPort {
        init {
            assets.forEach { asset ->
                if (bodies[asset.id] == null) {
                    // The helper cannot recover the body, so tests that do not supply bytes use
                    // the asset digest of the known fixture text through `bodyFor`.
                    bodies[asset.id] = ByteArray(0)
                }
            }
        }

        override suspend fun summary(ref: LocalProjectRef): TextProjectSummary = TextProjectSummary(
            ref = this.ref,
            name = "material project",
            revision = rev,
            assetPath = RelativePath("novel.txt"),
            itemCount = assets.sumOf { it.itemCount },
            assets = assets,
        )

        override suspend fun assets(ref: LocalProjectRef): List<ProjectAssetSummary> = assets

        override suspend fun readAsset(ref: LocalProjectRef, assetId: Long): ByteArray {
            val asset = assets.first { it.id == assetId }
            val supplied = bodies[assetId]
            if (supplied != null && supplied.isNotEmpty()) return supplied.copyOf()
            return when (asset.path.value) {
                "novel/ch1.txt" -> "hello".toByteArray(Charsets.UTF_8)
                "notes.md" -> "# Notes\n".toByteArray(Charsets.UTF_8)
                "book.pdf" -> "%PDF".toByteArray(Charsets.UTF_8)
                "novel.txt" -> "alpha".toByteArray(Charsets.UTF_8)
                "a.txt" -> "hello".toByteArray(Charsets.UTF_8)
                "large.txt" -> "x".repeat(600).toByteArray(Charsets.UTF_8)
                "same.txt" -> "one".toByteArray(Charsets.UTF_8)
                else -> error("No fixture body")
            }
        }
    }
}

class WorkspaceArtifactStoreTest {
    private lateinit var root: File
    private lateinit var ref: LocalProjectRef
    private lateinit var clock: FakeClock

    @Before fun setup() {
        root = File(System.getProperty("java.io.tmpdir"), "lnt-artifact-store-" + UUID.randomUUID())
            .apply { mkdirs() }
        ref = LocalProjectRef(
            ProjectId("project-artifacts"),
            ProjectInstanceId("instance-artifacts"),
            RecoveryGeneration(1),
        )
        clock = FakeClock(1_000L)
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun inventoryRetainsMissingAndTamperedDeclarationsWhileHealthyReadsRemainScoped() = runBlocking {
        val run = RunId("inventory-run")
        val store = WorkspaceArtifactStore(root, ref, clock = clock)
        val saved = store.save(run, listOf(
            WorkspaceArtifactDraft(WorkspacePath("good.txt"), "text/plain", "good".toByteArray()),
            WorkspaceArtifactDraft(WorkspacePath("missing.txt"), "text/plain", "missing".toByteArray()),
            WorkspaceArtifactDraft(WorkspacePath("bad.txt"), "text/plain", "original".toByteArray())))
        val missing = root.walkTopDown().single { it.isFile && it.name == "missing.txt" }
        assertTrue(missing.delete())
        val bad = root.walkTopDown().single { it.isFile && it.name == "bad.txt" }
        bad.setWritable(true)
        bad.writeText("tampered") // Same byte length: digest verification must catch it.
        val reopened = WorkspaceArtifactStore(root, ref, clock = clock)
        val inventory = reopened.inventory(run).associateBy { it.record.path.value }
        assertEquals(3, inventory.size)
        assertEquals(WorkspaceArtifactAvailability.VERIFIED, inventory.getValue("good.txt").availability)
        assertEquals(WorkspaceArtifactAvailability.MISSING, inventory.getValue("missing.txt").availability)
        assertEquals(WorkspaceArtifactAvailability.MISMATCH, inventory.getValue("bad.txt").availability)
        val good = saved.single { it.path.value == "good.txt" }
        assertEquals("good", reopened.readForRun(run, good.id, 64).bytes.toString(Charsets.UTF_8))
        assertNull(reopened.recordForRun(RunId("another-run"), good.id))
        assertEquals(1_000L, inventory.getValue("missing.txt").record.createdAtMillis)
        assertThrows(WorkspaceArtifactUnavailable::class.java) {
            runBlocking { reopened.readForRun(run, saved.single { it.path.value == "bad.txt" }.id, 64) }
        }
        Unit
    }

    @Test fun saveListReadAndRescanPreserveMetadata() = runBlocking {
        val store = WorkspaceArtifactStore(root, ref, clock = clock)
        val run = RunId("run-artifacts")
        val saved = store.save(
            run,
            listOf(
                WorkspaceArtifactDraft(WorkspacePath("reports/out.txt"), "text/x-report", "result".toByteArray()),
                WorkspaceArtifactDraft(WorkspacePath("data/table.csv"), "text/csv", "a,b\n1,2\n".toByteArray()),
            ),
        )
        assertEquals(2, saved.size)
        assertEquals(saved.map { it.sha256 }, listOf(sha256Of("result"), sha256Of("a,b\n1,2\n")))
        val listed = store.list(run)
        assertEquals(2, listed.size)
        assertEquals(WorkspaceArtifactKind.TEXT, listed.first { it.path.value.endsWith(".txt") }.kind)
        assertEquals(WorkspaceArtifactKind.CSV, listed.first { it.path.value.endsWith(".csv") }.kind)
        assertEquals("text/x-report", listed.first { it.path.value.endsWith(".txt") }.mediaType)
        assertEquals(0, store.list(RunId("other-run")).size)

        val artifact = saved.first()
        val record = requireNotNull(store.find(artifact.id))
        assertEquals(run, record.runId)
        assertEquals("result", String(store.read(artifact.id, 16).bytes, Charsets.UTF_8))
        val tooLarge = assertThrows(WorkspaceArtifactUnavailable::class.java) {
            runBlocking { store.read(artifact.id, 5) }
        }
        assertEquals(WorkspaceArtifactUnavailable.Reason.TOO_LARGE, tooLarge.reason)

        val reopened = WorkspaceArtifactStore(root, ref, clock = clock)
        val rescanned = reopened.list(run)
        assertEquals(2, rescanned.size)
        assertEquals("text/x-report", rescanned.first { it.path.value.endsWith(".txt") }.mediaType)
        assertEquals("result", String(reopened.read(artifact.id, 16).bytes, Charsets.UTF_8))
        assertTrue(root.walkTopDown().none { it.isFile && it.name.startsWith(".lnt-workspace-") })
    }

    @Test fun rejectsTraversalProtectedPathsDuplicatesAndOverwrite() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { WorkspacePath("../escape.txt") }
        assertThrows(IllegalArgumentException::class.java) { WorkspacePath("/absolute.txt") }
        assertThrows(IllegalArgumentException::class.java) { WorkspacePath("dir/../escape.txt") }

        val store = WorkspaceArtifactStore(root, ref, clock = clock)
        val run = RunId("run-paths")
        val protectedPath = assertThrows(WorkspaceArtifactConflict::class.java) {
            runBlocking {
                store.save(
                    run,
                    listOf(WorkspaceArtifactDraft(WorkspacePath("sources/out.txt"), "text/plain", "x".toByteArray())),
                )
            }
        }
        assertEquals(WorkspaceArtifactConflict.Reason.PROTECTED_PATH, protectedPath.reason)
        assertTrue(store.list().isEmpty())

        val metadataName = assertThrows(WorkspaceArtifactConflict::class.java) {
            runBlocking {
                store.save(
                    run,
                    listOf(WorkspaceArtifactDraft(WorkspacePath("run.json"), "application/json", "{}".toByteArray())),
                )
            }
        }
        assertEquals(WorkspaceArtifactConflict.Reason.PROTECTED_PATH, metadataName.reason)

        val duplicate = assertThrows(WorkspaceArtifactConflict::class.java) {
            runBlocking {
                store.save(
                    run,
                    listOf(
                        WorkspaceArtifactDraft(WorkspacePath("work/a.txt"), "text/plain", "one".toByteArray()),
                        WorkspaceArtifactDraft(WorkspacePath("work/a.txt"), "text/plain", "two".toByteArray()),
                    ),
                )
            }
        }
        assertEquals(WorkspaceArtifactConflict.Reason.DUPLICATE_PATH, duplicate.reason)
        assertTrue(store.list().isEmpty())

        val draft = WorkspaceArtifactDraft(WorkspacePath("work/a.txt"), "text/plain", "one".toByteArray())
        assertEquals(1, store.save(run, listOf(draft)).size)
        val overwrite = assertThrows(WorkspaceArtifactConflict::class.java) {
            runBlocking { store.save(run, listOf(draft)) }
        }
        assertEquals(WorkspaceArtifactConflict.Reason.ALREADY_EXISTS, overwrite.reason)
        val only = store.list(run).single()
        assertEquals("one", String(store.read(only.id, 8).bytes))
    }

    @Test fun failedBatchLeavesNoVisibleArtifactAndTamperingIsDetected() = runBlocking {
        val store = WorkspaceArtifactStore(root, ref, clock = clock)
        val run = RunId("run-atomic")
        assertThrows(WorkspaceArtifactConflict::class.java) {
            runBlocking {
                store.save(
                    run,
                    listOf(
                        WorkspaceArtifactDraft(WorkspacePath("work/valid.txt"), "text/plain", "valid".toByteArray()),
                        WorkspaceArtifactDraft(WorkspacePath("reference/bad.txt"), "text/plain", "bad".toByteArray()),
                    ),
                )
            }
        }
        assertTrue(store.list().isEmpty())
        assertTrue(root.walkTopDown().none { it.isFile && it.name == "valid.txt" })

        val saved = store.save(
            run,
            listOf(WorkspaceArtifactDraft(WorkspacePath("work/valid.txt"), "text/plain", "valid".toByteArray())),
        ).single()
        val file = root.walkTopDown().first { it.isFile && it.name == "valid.txt" }
        assertTrue(file.setWritable(true))
        file.writeText("evil!")
        val mismatch = assertThrows(WorkspaceArtifactUnavailable::class.java) {
            runBlocking { store.read(saved.id, 16) }
        }
        assertEquals(WorkspaceArtifactUnavailable.Reason.FINGERPRINT_MISMATCH, mismatch.reason)
    }

    @Test fun enforcesArtifactCountSingleAndTotalBudgets() {
        val countStore = WorkspaceArtifactStore(
            File(root, "count").apply { mkdirs() },
            ref,
            budget = WorkspaceBudget(maxArtifacts = 1, maxArtifactBytes = 8, maxTotalArtifactBytes = 16),
            clock = clock,
        )
        val count = assertThrows(WorkspaceBudgetExceeded::class.java) {
            runBlocking {
                countStore.save(
                    RunId("run-budget-count"),
                    listOf(
                        WorkspaceArtifactDraft(WorkspacePath("work/a.txt"), "text/plain", "a".toByteArray()),
                        WorkspaceArtifactDraft(WorkspacePath("work/b.txt"), "text/plain", "b".toByteArray()),
                    ),
                )
            }
        }
        assertEquals(WorkspaceBudgetKind.ARTIFACTS, count.kind)

        val singleStore = WorkspaceArtifactStore(
            File(root, "single").apply { mkdirs() },
            ref,
            budget = WorkspaceBudget(maxArtifactBytes = 3, maxTotalArtifactBytes = 8),
            clock = clock,
        )
        val single = assertThrows(WorkspaceBudgetExceeded::class.java) {
            runBlocking {
                singleStore.save(
                    RunId("run-budget-single"),
                    listOf(WorkspaceArtifactDraft(WorkspacePath("work/a.txt"), "text/plain", "abcd".toByteArray())),
                )
            }
        }
        assertEquals(WorkspaceBudgetKind.ARTIFACT_BYTES, single.kind)

        val totalStore = WorkspaceArtifactStore(
            File(root, "total").apply { mkdirs() },
            ref,
            budget = WorkspaceBudget(maxArtifactBytes = 4, maxTotalArtifactBytes = 5),
            clock = clock,
        )
        val total = assertThrows(WorkspaceBudgetExceeded::class.java) {
            runBlocking {
                totalStore.save(
                    RunId("run-budget-total"),
                    listOf(
                        WorkspaceArtifactDraft(WorkspacePath("work/a.txt"), "text/plain", "abc".toByteArray()),
                        WorkspaceArtifactDraft(WorkspacePath("work/b.txt"), "text/plain", "def".toByteArray()),
                    ),
                )
            }
        }
        assertEquals(WorkspaceBudgetKind.TOTAL_ARTIFACT_BYTES, total.kind)
    }

    @Test fun pruneRetainsActiveRunsAndNewestInactiveRuns() = runBlocking {
        val store = WorkspaceArtifactStore(root, ref, clock = clock)
        val first = RunId("run-1")
        val second = RunId("run-2")
        val active = RunId("run-3")
        clock.now = 1_000L
        store.save(first, listOf(WorkspaceArtifactDraft(WorkspacePath("work/first.txt"), "text/plain", "1".toByteArray())))
        clock.now = 2_000L
        store.save(second, listOf(WorkspaceArtifactDraft(WorkspacePath("work/second.txt"), "text/plain", "2".toByteArray())))
        clock.now = 3_000L
        store.save(active, listOf(WorkspaceArtifactDraft(WorkspacePath("work/active.txt"), "text/plain", "3".toByteArray())))

        val result = store.prune(setOf(active), keepInactiveRuns = 1)
        assertEquals(listOf(first), result.removedRuns)
        assertEquals(1, result.removedArtifacts)
        assertEquals(1L, result.removedBytes)
        assertEquals(1, result.retainedActiveRuns)
        assertTrue(store.list(first).isEmpty())
        assertEquals(1, store.list(second).size)
        assertEquals(1, store.list(active).size)

        val secondPrune = store.prune(setOf(active), keepInactiveRuns = 0)
        assertEquals(listOf(second), secondPrune.removedRuns)
        assertEquals(1, store.list(active).size)
        assertEquals(0, secondPrune.retainedInactiveRuns)
    }

    @Test fun storeRootRejectsAnotherProjectIdentity() {
        WorkspaceArtifactStore(root, ref, clock = clock)
        val other = LocalProjectRef(
            ProjectId("other-project"),
            ref.instanceId,
            ref.generation,
        )
        val conflict = assertThrows(WorkspaceArtifactConflict::class.java) {
            WorkspaceArtifactStore(root, other, clock = clock)
        }
        assertEquals(WorkspaceArtifactConflict.Reason.STORE_IDENTITY, conflict.reason)
    }
}
