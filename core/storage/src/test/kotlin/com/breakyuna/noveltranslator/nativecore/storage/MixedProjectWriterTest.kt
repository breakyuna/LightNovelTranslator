package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.ApprovalRequestId
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportArtifactId
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportPlan
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportProposal
import com.breakyuna.noveltranslator.nativecore.domain.AssetImportUnit
import com.breakyuna.noveltranslator.nativecore.domain.AssetMutationStatus
import com.breakyuna.noveltranslator.nativecore.domain.ChatId
import com.breakyuna.noveltranslator.nativecore.domain.ChatIdentity
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ConflictPolicy
import com.breakyuna.noveltranslator.nativecore.domain.ItemChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.ItemId
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalMember
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalRejection
import com.breakyuna.noveltranslator.nativecore.domain.ItemProposalValue
import com.breakyuna.noveltranslator.nativecore.domain.LocalProjectRef
import com.breakyuna.noveltranslator.nativecore.domain.ManualTextEdit
import com.breakyuna.noveltranslator.nativecore.domain.MixedApproval
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalBinding
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalDigest
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMember
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberKind
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalMemberState
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRejection
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalRequest
import com.breakyuna.noveltranslator.nativecore.domain.MixedApprovalState
import com.breakyuna.noveltranslator.nativecore.domain.NativeImportLimits
import com.breakyuna.noveltranslator.nativecore.domain.NativeProjectFormat
import com.breakyuna.noveltranslator.nativecore.domain.ObjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageChangeMember
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.PdfPageMode
import com.breakyuna.noveltranslator.nativecore.domain.PolicyChangeProposal
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetSummary
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import com.breakyuna.noveltranslator.nativecore.domain.RunId
import com.breakyuna.noveltranslator.nativecore.domain.RunIdentity
import com.breakyuna.noveltranslator.nativecore.domain.RuntimeOwner
import com.breakyuna.noveltranslator.nativecore.domain.TextItemStatus
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectFingerprint
import com.breakyuna.noveltranslator.nativecore.domain.TextProjectLimits
import com.breakyuna.noveltranslator.nativecore.engine.RuntimeCoordinator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MixedProjectWriterTest {
    private lateinit var context: Context
    private lateinit var runtime: RuntimeCoordinator
    private lateinit var projects: NativeTextProjectStore
    private lateinit var approvals: MixedApprovalStore
    private lateinit var ref: LocalProjectRef
    private var sequence = 0
    private val chat = ChatId("mixed-chat")
    private val run = RunId("mixed-run")

    @Before fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("lnt_native_app.db")
        File(context.filesDir, "native_projects").deleteRecursively()
        File(context.cacheDir, "native_project_snapshots").deleteRecursively()
        runtime = RuntimeCoordinator()
        projects = NativeTextProjectStore(context, runtime)
        approvals = MixedApprovalStore(context)
        ref = projects.createTxt(CommandId("create"), "fixture", RelativePath("source.txt"),
            "one\ntwo".toByteArray()).ref
    }

    @After fun cleanup() = runBlocking {
        approvals.close()
        projects.close()
        runtime.close()
        context.deleteDatabase("lnt_native_app.db")
    }

    private fun projectFile(): File =
        File(context.filesDir, "native_projects/${ref.instanceId.value}/project.lnt")

    private fun fp(character: Char): ObjectFingerprint =
        ObjectFingerprint(character.toString().repeat(64))

    private fun revision(): Long = NativeProjectDatabase.open(projectFile()).use { db ->
        NativeProjectDatabase.scalar(db, "SELECT revision FROM project WHERE singleton=1")
    }

    private fun <T> withProject(block: (SQLiteDatabase) -> T): T =
        NativeProjectDatabase.open(projectFile(), writable = true).use(block)

    private suspend fun itemProposal(
        policy: ConflictPolicy = ConflictPolicy.REJECT_BATCH,
    ): ItemChangeProposal {
        val items = projects.readPage(ref).items
        val members = items.map { item ->
            val before = ItemProposalValue(item.translation, item.status,
                item.metadata.nameSource, item.metadata.nameTranslation)
            val after = ItemProposalValue("mixed-${item.id.value}", TextItemStatus.PROCESSED,
                item.metadata.nameSource, item.metadata.nameTranslation)
            ItemProposalMember(item.id, item.row, item.assetId, item.fingerprint, before, after)
        }
        return ItemChangeProposal(ApprovalRequestId("item-${++sequence}"), ref, chat, run,
            CommandId("item-command-$sequence"), projects.open(ref).revision, policy, members)
    }

    private suspend fun policyProposal(
        revision: Long? = null,
    ): PolicyChangeProposal {
        val before = projects.textPolicy(ref).policy
        val after = before.changed(prompt = "mixed prompt ${++sequence}", promptEnabled = true)
        return PolicyChangeProposal(ApprovalRequestId("policy-$sequence"), ref, chat, run,
            CommandId("policy-command-$sequence"), revision ?: projects.open(ref).revision, before, after)
    }

    private suspend fun seedPdf(): ProjectAssetSummary {
        val id = ++sequence
        val units = listOf(1, 2).map { page ->
            AssetImportUnit(slot = page - 1, row = page, source = "page $page", tag = "page:$page",
                extraField = """{"v":1,"page":$page,"decision":"translate"}""")
        }
        val bytes = "pdf-$id".toByteArray()
        val plan = AssetImportPlan(CommandId("seed-pdf-$id"), ref, projects.open(ref).revision,
            RelativePath("seed-$id.pdf"), ProjectAssetFormat.PDF, bytes, units)
        val receipt = projects.importAsset(plan)
        assertEquals(AssetMutationStatus.COMMITTED, receipt.status)
        return projects.assets(ref).first { it.id == requireNotNull(receipt.assetId) }
    }

    private suspend fun pdfProposal(
        asset: ProjectAssetSummary,
        pages: Set<Int> = setOf(1, 2),
        revision: Long? = null,
    ): PdfPageChangeProposal {
        val id = ++sequence
        val members = pages.sorted().map { page ->
            PdfPageChangeMember(page, PdfPageMode.TRANSLATE, PdfPageMode.OMIT)
        }
        return PdfPageChangeProposal(ApprovalRequestId("pdf-$id"), ref, chat, run,
            CommandId("pdf-command-$id"), revision ?: projects.open(ref).revision, asset.id, asset.sha256,
            asset.itemCount, ConflictPolicy.REJECT_BATCH, members)
    }

    private suspend fun assetImport(
        target: String? = null,
        revision: Long? = null,
    ): Pair<AssetImportProposal, AssetImportPlan> {
        val id = ++sequence
        val bytes = "imported-$id".toByteArray()
        val path = RelativePath(target ?: "imported-$id.txt")
        val proposal = AssetImportProposal(ApprovalRequestId("asset-$id"), ref, chat, run,
            CommandId("asset-command-$id"), revision ?: projects.open(ref).revision, run,
            AssetImportArtifactId("artifact-$id"), RelativePath("artifacts/$id.txt"),
            TextProjectFingerprint.bytes(bytes), bytes.size.toLong(), path, true, 1_000L + id)
        val plan = AssetImportPlan(proposal.command, ref, proposal.revision, path,
            ProjectAssetFormat.TXT, bytes, listOf(AssetImportUnit(0, 0, "imported source $id")), true)
        return proposal to plan
    }

    private fun workspaceInput(
        id: String? = null,
        selection: List<String> = listOf("work/a.txt"),
        root: String = "workspace-root",
        command: CommandId? = null,
        digest: ObjectFingerprint = fp('a'),
        revision: Long = 0,
    ): MixedProjectMemberInput.WorkspaceFile {
        val number = ++sequence
        return MixedProjectMemberInput.WorkspaceFile(
            childId = id ?: "workspace-$number",
            childCommandId = command ?: CommandId("workspace-command-$number"),
            childDigest = digest,
            childRevision = revision,
            selection = selection,
            workspaceRootIdentity = root,
        )
    }

    private fun member(
        input: MixedProjectMemberInput,
        selection: List<String> = input.selection,
        childCommand: CommandId = input.childCommandId,
        childDigest: ObjectFingerprint = input.childDigest,
        workspaceRoot: String? =
            (input as? MixedProjectMemberInput.WorkspaceFile)?.workspaceRootIdentity,
    ): MixedApprovalMember = MixedApprovalMember(input.kind, input.childId, childDigest, childCommand,
        selection, emptyList(), workspaceRoot)

    private fun requestWithMembers(
        members: List<MixedApprovalMember>,
        base: Long,
        policy: ConflictPolicy,
    ): MixedApprovalRequest = MixedApprovalRequest(ApprovalRequestId("mixed-${++sequence}"),
        CommandId("mixed-command-$sequence"), ref, chat, run, base, policy, members, 1_000L)

    private fun request(
        inputs: List<MixedProjectMemberInput>,
        base: Long,
        policy: ConflictPolicy,
    ): MixedApprovalRequest = requestWithMembers(inputs.map { member(it) }, base, policy)

    private fun consumed(request: MixedApprovalRequest): MixedApproval =
        MixedApproval(request, request.members, MixedApprovalState.CONSUMED,
            MixedApprovalBinding(request), 2_000L, null)

    private suspend fun storedConsumed(request: MixedApprovalRequest): MixedApproval {
        val prepared = approvals.prepare(request)
        assertTrue("Expected prepared, got $prepared", prepared is MixedApprovalPreparation.Ready)
        val result = approvals.consume((prepared as MixedApprovalPreparation.Ready).approval, at = 2_000L)
        assertTrue("Expected consumed, got $result", result is MixedApprovalConsumption.Won)
        return (result as MixedApprovalConsumption.Won).approval
    }

    private fun apply(
        approval: MixedApproval,
        inputs: List<MixedProjectMemberInput>,
        binding: MixedApprovalBinding = requireNotNull(approval.binding),
        beforeCommit: () -> Unit = {},
    ): MixedProjectWriteResult = withProject { db ->
        MixedProjectWriter.apply(db, ref, approval, binding, inputs, beforeCommit)
    }

    private fun recover(
        approval: MixedApproval,
        inputs: List<MixedProjectMemberInput>,
        binding: MixedApprovalBinding = requireNotNull(approval.binding),
    ): MixedProjectWriteResult = withProject { db ->
        MixedProjectWriter.recover(db, ref, approval, binding, inputs)
    }

    private fun schema(): List<String> = NativeProjectDatabase.open(projectFile()).use { db ->
        db.rawQuery("SELECT type||':'||name||':'||COALESCE(sql,'') FROM sqlite_master ORDER BY type,name", null)
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
    }

    private fun expectRefusal(reason: MixedProjectWriteRefusal, block: () -> Unit) {
        try {
            block()
            fail("Expected refusal $reason")
        } catch (error: MixedProjectWriteException) {
            assertEquals(reason, error.reason)
        }
    }

    @Test fun allEngineeringMembersCommitInOneTransactionAndRepeatIsIdempotent() = runBlocking {
        val pdfAsset = seedPdf()
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val policy = policyProposal(item.revision + itemIds.size)
        val pdf = pdfProposal(pdfAsset, setOf(1, 2), policy.revision + 1)
        val (asset, plan) = assetImport(revision = pdf.revision + 1)
        val inputs = listOf(
            MixedProjectMemberInput.Items(item, itemIds),
            MixedProjectMemberInput.Policy(policy),
            MixedProjectMemberInput.PdfPages(pdf, setOf(1, 2)),
            MixedProjectMemberInput.AssetImport(asset, plan),
        )
        val base = item.revision
        assertEquals(base, item.revision)
        assertEquals(base + itemIds.size, policy.revision)
        assertEquals(policy.revision + 1, pdf.revision)
        assertEquals(pdf.revision + 1, asset.revision)
        val approval = storedConsumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))
        val beforeSchema = schema()

        val result = apply(approval, inputs)

        assertEquals(MixedProjectWriteStatus.COMMITTED, result.status)
        assertNotNull(result.receiptDigest)
        assertEquals(listOf(MixedApprovalMemberKind.ITEM, MixedApprovalMemberKind.POLICY,
            MixedApprovalMemberKind.PDF_PAGE, MixedApprovalMemberKind.ASSET_IMPORT),
            result.members.map { it.kind })
        assertTrue(result.members.all { it.status == MixedProjectMemberStatus.COMMITTED })
        assertTrue(result.members.all { it.receiptDigest != null && it.committedIds.isNotEmpty() })
        result.members.zip(approval.members).forEach { (memberResult, member) ->
            assertEquals(MixedApprovalMemberState.COMMITTED, memberResult.toApprovalReceipt(member).state)
        }
        assertEquals(4, result.toApprovalReceipts(approval).size)
        assertEquals(base + itemIds.size + 1 + 1 + 1, result.revision)
        assertEquals(result.revision, revision())
        assertEquals(beforeSchema, schema())
        NativeProjectDatabase.open(projectFile()).use {
            assertEquals(NativeProjectFormat.SCHEMA_VERSION, it.version)
        }
        assertEquals(ref.projectId, NativeImportValidator.validate(projectFile(),
            TextProjectLimits(), NativeImportLimits()) {})
        assertEquals("mixed-1", projects.item(ref, ItemId(1))!!.item.translation)
        assertEquals("mixed-2", projects.item(ref, ItemId(2))!!.item.translation)
        assertTrue(projects.textPolicy(ref).policy.prompt.startsWith("mixed prompt"))
        val pagePolicy = projects.projectSettings(ref).pdfPages.single { it.assetId == pdfAsset.id }
        assertEquals(PdfPageMode.OMIT, pagePolicy.mode(1))
        assertEquals(PdfPageMode.OMIT, pagePolicy.mode(2))
        assertTrue(projects.assets(ref).any { it.path == plan.path })

        assertEquals(result, apply(approval, inputs))
        assertEquals(result.revision, revision())
    }

    @Test fun injectedFailureRollsBackEveryMemberAndChildReceipt() = runBlocking {
        val pdfAsset = seedPdf()
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val policy = policyProposal(item.revision + itemIds.size)
        val pdf = pdfProposal(pdfAsset, setOf(1, 2), policy.revision + 1)
        val (asset, plan) = assetImport(revision = pdf.revision + 1)
        val inputs = listOf(
            MixedProjectMemberInput.Items(item, itemIds),
            MixedProjectMemberInput.Policy(policy),
            MixedProjectMemberInput.PdfPages(pdf, setOf(1, 2)),
            MixedProjectMemberInput.AssetImport(asset, plan),
        )
        val base = item.revision
        val approval = consumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))
        var failure: IOException? = null

        withProject { db ->
            try {
                MixedProjectWriter.apply(db, ref, approval, requireNotNull(approval.binding), inputs,
                    beforeCommit = {
                        if (NativeProjectDatabase.scalar(db,
                                "SELECT COUNT(*) FROM approved_item_receipts") > 0) {
                            throw IOException("injected after item receipt")
                        }
                    })
                fail("Injected failure did not abort")
            } catch (error: IOException) {
                failure = error
            }
            assertNull(ProjectWriteStore.approvedItemsReceipt(db, item, itemIds))
            assertNull(ProjectWriteStore.approvedPdfPagesReceipt(db, pdf, setOf(1, 2)))
            assertNull(ProjectWriteStore.checkedPolicyReceipt(db, policy.intent()))
            assertNull(ProjectAssetWriteStore.receipt(db, ref, asset.command))
            assertEquals(base, NativeProjectDatabase.scalar(db,
                "SELECT revision FROM project WHERE singleton=1"))
            assertEquals(0L, NativeProjectDatabase.scalar(db, "SELECT COUNT(*) FROM item_revisions"))
        }

        assertNotNull(failure)
        assertEquals(base, revision())
        assertEquals("", projects.item(ref, ItemId(1))!!.item.translation)
        assertEquals("", projects.item(ref, ItemId(2))!!.item.translation)
        assertTrue(projects.projectSettings(ref).pdfPages.isEmpty())
        assertFalse(projects.assets(ref).any { it.path == plan.path })
    }

    @Test fun rejectBatchAssetConflictRollsBackEarlierMembers() = runBlocking {
        val pdfAsset = seedPdf()
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val policy = policyProposal(item.revision + itemIds.size)
        val pdf = pdfProposal(pdfAsset, setOf(1, 2), policy.revision + 1)
        val (asset, plan) = assetImport(target = pdfAsset.path.value, revision = pdf.revision + 1)
        val inputs = listOf(
            MixedProjectMemberInput.Items(item, itemIds),
            MixedProjectMemberInput.Policy(policy),
            MixedProjectMemberInput.PdfPages(pdf, setOf(1, 2)),
            MixedProjectMemberInput.AssetImport(asset, plan),
        )
        val base = item.revision
        val approval = consumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))

        expectRefusal(MixedProjectWriteRefusal.CHILD_REJECTED) { apply(approval, inputs) }

        assertEquals(base, revision())
        assertEquals("", projects.item(ref, ItemId(1))!!.item.translation)
        assertTrue(projects.projectSettings(ref).pdfPages.isEmpty())
        withProject { db ->
            assertNull(ProjectWriteStore.approvedItemsReceipt(db, item, item.members.map { it.id }.toSet()))
            assertNull(ProjectWriteStore.checkedPolicyReceipt(db, policy.intent()))
            assertNull(ProjectWriteStore.approvedPdfPagesReceipt(db, pdf, setOf(1, 2)))
            assertNull(ProjectAssetWriteStore.receipt(db, ref, asset.command))
        }
    }

    @Test fun independentPolicyPersistsPartialReceiptAndListsRejectedObjects() = runBlocking {
        seedPdf()
        // The parent policy is authoritative for the mixed batch; the child still says REJECT_BATCH.
        val item = itemProposal(ConflictPolicy.REJECT_BATCH)
        val selected = setOf(ItemId(1), ItemId(2))
        val input = MixedProjectMemberInput.Items(item, selected)
        val base = projects.open(ref).revision
        val approval = consumed(request(listOf(input), base, ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS))
        val second = projects.item(ref, ItemId(2))!!
        projects.edit(ManualTextEdit(CommandId("human-edit"), ref, second.item.id,
            second.revision, second.item.fingerprint, "human"))

        val result = apply(approval, listOf(input))

        assertEquals(MixedProjectWriteStatus.PARTIAL, result.status)
        val member = result.members.single()
        assertEquals(MixedProjectMemberStatus.PARTIAL, member.status)
        assertEquals(listOf("1"), member.committedIds)
        assertEquals(listOf(MixedProjectRejectedObject("2", ItemProposalRejection.STALE_FINGERPRINT.name)),
            member.rejected)
        assertEquals(MixedApprovalRejection.STALE_FINGERPRINT, member.reason)
        assertNotNull(member.receiptDigest)
        assertEquals(MixedApprovalMemberState.CONFLICT, member.approvalState)
        assertEquals("mixed-1", projects.item(ref, ItemId(1))!!.item.translation)
        assertEquals("human", projects.item(ref, ItemId(2))!!.item.translation)
        assertEquals(base + 2, revision())
        withProject { db ->
            val receipt = ProjectWriteStore.approvedItemsReceipt(db, item, selected)
            assertNotNull(receipt)
            assertEquals(listOf(ItemId(1)), receipt!!.accepted)
            assertEquals(ItemProposalRejection.STALE_FINGERPRINT, receipt.rejected[ItemId(2)])
        }
        val settled = member.toApprovalReceipt(approval.members.single())
        assertEquals(MixedApprovalMemberState.CONFLICT, settled.state)
        assertEquals(MixedApprovalRejection.STALE_FINGERPRINT, settled.rejection)
        assertEquals(member.receiptDigest, settled.receiptDigest)

        assertEquals(result, apply(approval, listOf(input)))
        assertEquals(result.revision, revision())
    }

    @Test fun partialExistingReceiptReturnsPartialWithoutWritingRemainingMembers() = runBlocking {
        val item = itemProposal()
        val policy = policyProposal()
        val itemInput = MixedProjectMemberInput.Items(item, item.members.map { it.id }.toSet())
        val policyInput = MixedProjectMemberInput.Policy(policy)
        val inputs = listOf(itemInput, policyInput)
        val base = projects.open(ref).revision
        val standalone = withProject { db ->
            ProjectWriteStore.approvedItems(db, item, itemInput.selected) {}
        }
        assertEquals(itemInput.selected, standalone.accepted.toSet())
        val approval = consumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))

        val result = recover(approval, inputs)

        assertEquals(MixedProjectWriteStatus.PARTIAL, result.status)
        assertEquals(MixedProjectMemberStatus.COMMITTED, result.members[0].status)
        assertEquals(MixedProjectMemberStatus.INDETERMINATE, result.members[1].status)
        assertEquals(standalone.revision, result.revision)
        assertEquals(standalone.revision, revision())
        assertEquals(1, result.toApprovalReceipts(approval).size)
        withProject { db ->
            assertNotNull(ProjectWriteStore.approvedItemsReceipt(db, item, itemInput.selected))
            assertNull(ProjectWriteStore.checkedPolicyReceipt(db, policy.intent()))
        }
        assertEquals("", projects.textPolicy(ref).policy.prompt)
    }

    @Test fun consumedWithoutAnyChildReceiptRecoversIndeterminate() = runBlocking {
        val item = itemProposal()
        val policy = policyProposal()
        val inputs = listOf(
            MixedProjectMemberInput.Items(item, item.members.map { it.id }.toSet()),
            MixedProjectMemberInput.Policy(policy),
        )
        val base = projects.open(ref).revision
        val approval = consumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))

        val result = recover(approval, inputs)

        assertEquals(MixedProjectWriteStatus.INDETERMINATE, result.status)
        assertNull(result.approvalState)
        assertNull(result.receiptDigest)
        assertTrue(result.members.all { it.status == MixedProjectMemberStatus.INDETERMINATE })
        assertTrue(result.members.all { it.receiptDigest == null && it.committedIds.isEmpty() })
        assertEquals(base, result.revision)
        assertEquals(base, revision())
        withProject { db ->
            assertNull(ProjectWriteStore.approvedItemsReceipt(db, item, setOf(ItemId(1), ItemId(2))))
            assertNull(ProjectWriteStore.checkedPolicyReceipt(db, policy.intent()))
        }
    }

    @Test fun parentBindingMemberReferencesAndSetCompletenessAreEnforcedBeforeWriting() = runBlocking {
        seedPdf()
        val item = itemProposal()
        val itemInput = MixedProjectMemberInput.Items(item, setOf(ItemId(1)))
        val base = projects.open(ref).revision
        val baseRequest = request(listOf(itemInput), base, ConflictPolicy.REJECT_BATCH)
        val approval = consumed(baseRequest)
        val binding = requireNotNull(approval.binding)
        val before = revision()

        val other = request(listOf(itemInput), base, ConflictPolicy.ACCEPT_INDEPENDENT_OBJECTS)
        expectRefusal(MixedProjectWriteRefusal.BINDING) {
            apply(approval, listOf(itemInput), MixedApprovalBinding(other))
        }
        val wrongDigestBinding = MixedApprovalBinding(approval.id, MixedApprovalDigest(fp('e').sha256), approval.commandId,
            approval.ref, approval.chat, approval.run, approval.baseRevision, approval.policy)
        expectRefusal(MixedProjectWriteRefusal.BINDING) {
            apply(approval, listOf(itemInput), wrongDigestBinding)
        }
        val wrongBaseBinding = MixedApprovalBinding(approval.id, approval.digest, approval.commandId,
            approval.ref, approval.chat, approval.run, approval.baseRevision - 1, approval.policy)
        expectRefusal(MixedProjectWriteRefusal.BINDING) {
            apply(approval, listOf(itemInput), wrongBaseBinding)
        }

        val wrongCommandRequest = requestWithMembers(listOf(
            member(itemInput, childCommand = CommandId("wrong-command-${++sequence}"))),
            base, ConflictPolicy.REJECT_BATCH)
        expectRefusal(MixedProjectWriteRefusal.CHILD_COMMAND) {
            apply(consumed(wrongCommandRequest), listOf(itemInput))
        }

        val wrongDigestRequest = requestWithMembers(listOf(member(itemInput, childDigest = fp('f'))),
            base, ConflictPolicy.REJECT_BATCH)
        expectRefusal(MixedProjectWriteRefusal.CHILD_DIGEST) {
            apply(consumed(wrongDigestRequest), listOf(itemInput))
        }

        val wrongSelectionRequest = requestWithMembers(listOf(member(itemInput, selection = listOf("999"))),
            base, ConflictPolicy.REJECT_BATCH)
        expectRefusal(MixedProjectWriteRefusal.CHILD_SELECTION) {
            apply(consumed(wrongSelectionRequest), listOf(itemInput))
        }

        val wrongBaseRequest = request(listOf(itemInput), base + 1, ConflictPolicy.REJECT_BATCH)
        expectRefusal(MixedProjectWriteRefusal.CHILD_REVISION) {
            apply(consumed(wrongBaseRequest), listOf(itemInput))
        }

        val pending = MixedApproval(baseRequest, baseRequest.members, MixedApprovalState.PENDING, null, null, null)
        expectRefusal(MixedProjectWriteRefusal.NOT_CONSUMED) { apply(pending, listOf(itemInput), binding) }

        expectRefusal(MixedProjectWriteRefusal.INVALID_MEMBER_SET) { apply(approval, emptyList()) }

        val policy = policyProposal()
        val policyInput = MixedProjectMemberInput.Policy(policy)
        val twoMemberRequest = request(listOf(member(itemInput), member(policyInput)),
            base, ConflictPolicy.REJECT_BATCH)
        expectRefusal(MixedProjectWriteRefusal.INVALID_MEMBER_SET) {
            apply(consumed(twoMemberRequest), listOf(itemInput))
        }
        expectRefusal(MixedProjectWriteRefusal.DUPLICATE_MEMBER) {
            apply(consumed(twoMemberRequest), listOf(itemInput, itemInput))
        }

        val secondItem = itemProposal()
        val secondInput = MixedProjectMemberInput.Items(secondItem, setOf(ItemId(1)))
        expectRefusal(MixedProjectWriteRefusal.INVALID_MEMBER_SET) {
            apply(approval, listOf(itemInput, secondInput))
        }

        assertEquals(before, revision())
        withProject { db ->
            assertNull(ProjectWriteStore.approvedItemsReceipt(db, item, setOf(ItemId(1))))
        }
    }

    @Test fun resultReceiptsSettleTheParentExactlyOnce() = runBlocking {
        val item = itemProposal()
        val selected = item.members.map { it.id }.toSet()
        val input = MixedProjectMemberInput.Items(item, selected)
        val base = projects.open(ref).revision
        val approval = storedConsumed(request(listOf(input), base, ConflictPolicy.REJECT_BATCH))
        val result = apply(approval, listOf(input))

        val settlement = approvals.settle(approval, MixedApprovalState.COMMITTED,
            result.toApprovalReceipts(approval), at = 3_000L)

        assertTrue("Expected settlement, got $settlement", settlement is MixedApprovalSettlement.Settled)
        val parent = requireNotNull(approvals.find(ref, chat, approval.id))
        assertEquals(MixedApprovalState.COMMITTED, parent.state)
        assertEquals(MixedApprovalMemberState.COMMITTED, parent.members.single().state)
        assertEquals(result.members.single().receiptDigest, parent.members.single().receiptDigest)
        assertEquals(result.revision, revision())
    }

    @Test fun workspaceMembersDeferWhileEngineeringCommitsInOneTransaction() = runBlocking {
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val itemInput = MixedProjectMemberInput.Items(item, itemIds)
        val workspace = workspaceInput(selection = listOf("work/a.txt", "work/b.txt"))
        val inputs = listOf(itemInput, workspace)
        val base = item.revision
        val approval = storedConsumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))
        val beforeSchema = schema()

        val result = apply(approval, inputs)

        assertEquals(MixedProjectWriteStatus.WORKSPACE_DEFERRED, result.status)
        assertEquals(MixedApprovalState.PARTIAL, result.approvalState)
        assertNotNull(result.receiptDigest)
        assertEquals(base + itemIds.size, result.revision)
        assertEquals(result.revision, revision())
        assertEquals(listOf(MixedApprovalMemberKind.ITEM, MixedApprovalMemberKind.WORKSPACE_FILE),
            result.members.map { it.kind })
        val itemResult = result.members.first { it.kind == MixedApprovalMemberKind.ITEM }
        assertEquals(MixedProjectMemberStatus.COMMITTED, itemResult.status)
        assertNotNull(itemResult.receiptDigest)
        val workspaceResult = result.members.first { it.kind == MixedApprovalMemberKind.WORKSPACE_FILE }
        assertEquals(MixedProjectMemberStatus.WORKSPACE_DEFERRED, workspaceResult.status)
        assertNull(workspaceResult.receiptDigest)
        assertTrue(workspaceResult.committedIds.isEmpty())
        assertEquals(MixedApprovalMemberState.INDETERMINATE, workspaceResult.approvalState)
        val receipts = result.toApprovalReceipts(approval)
        assertEquals(listOf(MixedApprovalMemberKind.ITEM), receipts.map { it.kind })
        assertEquals(MixedApprovalMemberState.COMMITTED, receipts.single().state)
        assertEquals("mixed-1", projects.item(ref, ItemId(1))!!.item.translation)
        assertEquals("mixed-2", projects.item(ref, ItemId(2))!!.item.translation)
        assertEquals(beforeSchema, schema())
    }

    @Test fun workspaceOnlyApprovalDefersWithoutOpeningAProjectTransaction() = runBlocking {
        val workspace = workspaceInput()
        val base = projects.open(ref).revision
        val approval = storedConsumed(request(listOf(workspace), base, ConflictPolicy.REJECT_BATCH))
        val beforeSchema = schema()
        val beforeRevision = revision()
        var beforeCommitCalls = 0

        val result = withProject { db ->
            MixedProjectWriter.apply(db, ref, approval, requireNotNull(approval.binding),
                listOf(workspace), beforeCommit = { beforeCommitCalls++ })
                .also { assertFalse(db.inTransaction()) }
        }

        assertEquals(0, beforeCommitCalls)

        assertEquals(MixedProjectWriteStatus.WORKSPACE_DEFERRED, result.status)
        assertNull(result.approvalState)
        assertNotNull(result.receiptDigest)
        assertEquals(beforeRevision, result.revision)
        assertEquals(beforeRevision, revision())
        assertEquals(MixedProjectMemberStatus.WORKSPACE_DEFERRED, result.members.single().status)
        assertTrue(result.toApprovalReceipts(approval).isEmpty())
        assertEquals(beforeSchema, schema())
        assertEquals(result, recover(approval, listOf(workspace)))
        assertEquals(result, recover(approval, emptyList()))
        assertEquals(result, apply(approval, emptyList()))
    }

    @Test fun workspaceReferencesMustMatchTheParentMember() = runBlocking {
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val itemInput = MixedProjectMemberInput.Items(item, itemIds)
        val workspace = workspaceInput(selection = listOf("work/a.txt"))
        val base = item.revision
        val approval = consumed(request(listOf(itemInput, workspace), base, ConflictPolicy.REJECT_BATCH))

        expectRefusal(MixedProjectWriteRefusal.CHILD_DIGEST) {
            apply(approval, listOf(itemInput, MixedProjectMemberInput.WorkspaceFile(
                workspace.childId, workspace.childCommandId, fp('b'), workspace.childRevision,
                workspace.selection, workspace.workspaceRootIdentity,
            )))
        }
        expectRefusal(MixedProjectWriteRefusal.CHILD_SELECTION) {
            apply(approval, listOf(itemInput, MixedProjectMemberInput.WorkspaceFile(
                workspace.childId, workspace.childCommandId, workspace.childDigest,
                workspace.childRevision, listOf("work/other.txt"), workspace.workspaceRootIdentity,
            )))
        }
        expectRefusal(MixedProjectWriteRefusal.CHILD_COMMAND) {
            apply(approval, listOf(itemInput, MixedProjectMemberInput.WorkspaceFile(
                workspace.childId, CommandId("other-command-${++sequence}"), workspace.childDigest,
                workspace.childRevision, workspace.selection, workspace.workspaceRootIdentity,
            )))
        }
        expectRefusal(MixedProjectWriteRefusal.IDENTITY) {
            apply(approval, listOf(itemInput, MixedProjectMemberInput.WorkspaceFile(
                workspace.childId, workspace.childCommandId, workspace.childDigest,
                workspace.childRevision, workspace.selection, "other-workspace-root",
            )))
        }
        expectRefusal(MixedProjectWriteRefusal.INVALID_MEMBER_SET) {
            apply(approval, listOf(itemInput, MixedProjectMemberInput.WorkspaceFile(
                "other-workspace-${++sequence}", workspace.childCommandId, workspace.childDigest,
                workspace.childRevision, workspace.selection, workspace.workspaceRootIdentity,
            )))
        }

        assertEquals(base, revision())
        withProject { db ->
            assertNull(ProjectWriteStore.approvedItemsReceipt(db, item, itemIds))
        }
    }

    @Test fun engineeringFailureRollsBackWhileWorkspaceStaysDeferredForRecovery() = runBlocking {
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val itemInput = MixedProjectMemberInput.Items(item, itemIds)
        val workspace = workspaceInput()
        val inputs = listOf(itemInput, workspace)
        val base = item.revision
        val approval = consumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))
        var failure: IOException? = null

        withProject { db ->
            try {
                MixedProjectWriter.apply(db, ref, approval, requireNotNull(approval.binding), inputs,
                    beforeCommit = {
                        if (NativeProjectDatabase.scalar(db,
                                "SELECT COUNT(*) FROM approved_item_receipts") > 0) {
                            throw IOException("injected after item receipt")
                        }
                    })
                fail("Injected failure did not abort")
            } catch (error: IOException) {
                failure = error
            }
            assertNull(ProjectWriteStore.approvedItemsReceipt(db, item, itemIds))
            assertEquals(base, NativeProjectDatabase.scalar(db,
                "SELECT revision FROM project WHERE singleton=1"))
        }

        assertNotNull(failure)
        assertEquals(base, revision())
        val recovered = recover(approval, inputs)
        assertEquals(MixedProjectWriteStatus.INDETERMINATE, recovered.status)
        assertEquals(MixedProjectMemberStatus.INDETERMINATE,
            recovered.members.first { it.kind == MixedApprovalMemberKind.ITEM }.status)
        val workspaceResult = recovered.members.first {
            it.kind == MixedApprovalMemberKind.WORKSPACE_FILE
        }
        assertEquals(MixedProjectMemberStatus.WORKSPACE_DEFERRED, workspaceResult.status)
        assertNull(workspaceResult.receiptDigest)
        assertTrue(recovered.toApprovalReceipts(approval).isEmpty())
    }

    @Test fun repeatedWorkspaceMixedApplyIsIdempotentWithOrWithoutWorkspaceInput() = runBlocking {
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val itemInput = MixedProjectMemberInput.Items(item, itemIds)
        val workspace = workspaceInput()
        val inputs = listOf(itemInput, workspace)
        val base = item.revision
        val approval = storedConsumed(request(inputs, base, ConflictPolicy.REJECT_BATCH))

        val first = apply(approval, inputs)
        val repeated = apply(approval, inputs)
        val engineeringOnly = apply(approval, listOf(itemInput))

        assertEquals(first, repeated)
        assertEquals(first, engineeringOnly)
        assertEquals(first.revision, revision())
        assertEquals(MixedProjectWriteStatus.WORKSPACE_DEFERRED, engineeringOnly.status)
    }

    @Test fun recoveryKeepsDeferredWorkspaceOutOfEngineeringReceipts() = runBlocking {
        val item = itemProposal()
        val itemIds = item.members.map { it.id }.toSet()
        val itemInput = MixedProjectMemberInput.Items(item, itemIds)
        val workspace = workspaceInput()
        val base = item.revision
        val approval = consumed(request(listOf(itemInput, workspace), base,
            ConflictPolicy.REJECT_BATCH))

        val applied = apply(approval, listOf(itemInput))
        assertEquals(MixedProjectWriteStatus.WORKSPACE_DEFERRED, applied.status)

        val recovered = recover(approval, listOf(itemInput))

        assertEquals(MixedProjectWriteStatus.WORKSPACE_DEFERRED, recovered.status)
        assertTrue(recovered.status != MixedProjectWriteStatus.COMMITTED)
        assertEquals(MixedApprovalState.PARTIAL, recovered.approvalState)
        assertNotNull(recovered.receiptDigest)
        assertEquals(MixedProjectMemberStatus.COMMITTED,
            recovered.members.first { it.kind == MixedApprovalMemberKind.ITEM }.status)
        val workspaceResult = recovered.members.first {
            it.kind == MixedApprovalMemberKind.WORKSPACE_FILE
        }
        assertEquals(MixedProjectMemberStatus.WORKSPACE_DEFERRED, workspaceResult.status)
        assertTrue(workspaceResult.committedIds.isEmpty())
        assertNull(workspaceResult.receiptDigest)
        val receipts = recovered.toApprovalReceipts(approval)
        assertEquals(1, receipts.size)
        assertEquals(MixedApprovalMemberKind.ITEM, receipts.single().kind)
        assertEquals(MixedApprovalMemberState.COMMITTED, receipts.single().state)
        assertEquals(applied.receiptDigest, recovered.receiptDigest)
    }

    @Test fun projectStoreMixedEntryUsesTheSameSingleTransaction() = runBlocking {
        val item = itemProposal()
        val selected = item.members.map { it.id }.toSet()
        val input = MixedProjectMemberInput.Items(item, selected)
        val base = projects.open(ref).revision
        val approval = storedConsumed(request(listOf(input), base, ConflictPolicy.REJECT_BATCH))
        runtime.selectChat(ChatIdentity(ref.projectId, chat))
        val lease = runtime.beginRun(RunIdentity(ref.projectId, chat, run, approval.commandId),
            RuntimeOwner.AGENT)
        try {
            val result = projects.applyMixedProject(lease, approval, requireNotNull(approval.binding),
                listOf(input))
            assertEquals(MixedProjectWriteStatus.COMMITTED, result.status)
            assertEquals(base + selected.size, result.revision)
            assertEquals("mixed-1", projects.item(ref, ItemId(1))!!.item.translation)
        } finally {
            runtime.finishRun(lease)
            runtime.selectChat(null)
        }
    }
}
