package com.breakyuna.noveltranslator.nativecore.workspace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectWorkspaceProposalDeviceTest {
    @Test fun completePagedSnapshotProducesTypedItemProposalDirectlyWithoutLogRoundTrip() = runBlocking {
        val ref = LocalProjectRef(ProjectId("fixture-project"), ProjectInstanceId("fixture-instance"), RecoveryGeneration(1))
        val rows = (1..102).map { index ->
            val id = ItemId(index.toLong())
            val source = "source-$index"
            TextProjectItem(id, index - 1, source, "", TextItemStatus.NONE,
                TextProjectFingerprint.item(id, index - 1, source, "", TextItemStatus.NONE))
        }
        val summary = TextProjectSummary(ref, "fixture", 3, RelativePath("source.txt"), rows.size)
        // Only snapshot reads exist in this fixture; an accidental project write fails immediately.
        val projects = Proxy.newProxyInstance(TextProjectPort::class.java.classLoader, arrayOf(TextProjectPort::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> { check(args[0] == ref); summary }
                "items" -> {
                    check(args[0] == ref && args[1] == 3L)
                    TextItemPage(3, rows.filter { it.id.value > args[2] as Long }.take(args[3] as Int))
                }
                else -> error("Unexpected project operation: ${method.name}")
            }
        } as TextProjectPort
        val lease = object : RunLease {
            override val identity = RunIdentity(ref.projectId, ChatId("fixture-chat"), RunId("fixture-run"), CommandId("fixture-command"))
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        val result = ProjectWorkspaceAnalysis(context, projects).proposedChanges(ref, lease, """
            var patches = [];
            lg.materials.forEach(function (material) {
              lg.readMaterial(material.id).split('\n').forEach(function (line) {
                if (line.length === 0) return;
                var row = JSON.parse(line);
                patches.push({ item_id: row.item_id, fingerprint: row.fingerprint, translation: 'translated-' + row.item_id });
              });
            });
            lg.emitChange({path:'changes/items.json', operation:'CREATE', content:JSON.stringify({items:patches})});
            return 0;
        """.trimIndent())
        assertEquals(3L, result.revision)
        val patches = ItemProposalCodec.patches(result.json)
        assertEquals(102, patches.size)
        assertEquals(ItemId(102), ItemProposalCodec.id(patches.last()))
    }
}
