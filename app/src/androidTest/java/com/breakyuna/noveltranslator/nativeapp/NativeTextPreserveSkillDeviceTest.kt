package com.breakyuna.noveltranslator.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.breakyuna.noveltranslator.nativecore.domain.*
import com.breakyuna.noveltranslator.nativecore.workspace.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual APK script and isolated interpreter, not a duplicated JS fixture. */
@RunWith(AndroidJUnit4::class)
class NativeTextPreserveSkillDeviceTest {
    @Test fun bundledGlossaryMatchesNormalizedTextAndPreservesOriginalRanges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ref = LocalProjectRef(ProjectId("glossary-project"), ProjectInstanceId("glossary-instance"), RecoveryGeneration(1))
        val source = "Straße ＪＫ e\u0301"
        val item = TextProjectItem(ItemId(1), 0, source, "", TextItemStatus.NONE,
            TextProjectFingerprint.item(ItemId(1), 0, source, "", TextItemStatus.NONE))
        val summary = TextProjectSummary(ref, "Glossary", 3, RelativePath("source.txt"), 1)
        val projects = Proxy.newProxyInstance(TextProjectPort::class.java.classLoader,
            arrayOf(TextProjectPort::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> summary
                "items" -> TextItemPage(3, if ((args[2] as Long) == 0L) listOf(item) else emptyList())
                else -> error("Unexpected operation: " + method.name)
            }
        } as TextProjectPort
        val lease = object : RunLease {
            override val identity = RunIdentity(ref.projectId, ChatId("glossary-chat"), RunId("glossary-run"), CommandId("glossary-command"))
        }
        val script = context.assets.open("workspace-skills/glossary/scripts/main.js").bufferedReader().use { it.readText() }
        var report: String? = null
        val result = ProjectWorkspaceAnalysis(context, projects,
            artifactPublisher = ProjectWorkspaceArtifactPublisher { _, _, drafts ->
                report = drafts.single().bytes.toString(Charsets.UTF_8); emptyList()
            }).analyze(ref, lease, script, 3, inputJson = """
                {"patterns":[{"key":"ss","text":"SS","case_sensitive":false},
                {"key":"width","text":"JK","case_sensitive":true},
                {"key":"accent","text":"é","case_sensitive":true}]}
            """.trimIndent())
        assertEquals(0, result.result.exitCode)
        val patterns = Json.parseToJsonElement(requireNotNull(report)).jsonObject.getValue("patterns").jsonArray
        assertTrue(patterns.all { it.jsonObject.getValue("matched_item_count").jsonPrimitive.int == 1 })
        val ranges = patterns[0].jsonObject.getValue("matches").jsonArray[0].jsonObject.getValue("ranges").jsonArray
        assertEquals(1, ranges.size)
        assertEquals(4, ranges[0].jsonObject.getValue("start").jsonPrimitive.int)
        assertEquals(5, ranges[0].jsonObject.getValue("end").jsonPrimitive.int)
    }

    @Test fun realBundledScanKeepsCountsWhenEvidenceIsLimitedAndReportsInvalidPatterns() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ref = LocalProjectRef(ProjectId("scan-project"), ProjectInstanceId("scan-instance"), RecoveryGeneration(1))
        val source = "TAG\nTAG"
        val item = TextProjectItem(ItemId(1), 0, source, "", TextItemStatus.NONE,
            TextProjectFingerprint.item(ItemId(1), 0, source, "", TextItemStatus.NONE), 1, 0)
        val summary = TextProjectSummary(ref, "Scan", 3, RelativePath("source.txt"), 1,
            assets = listOf(ProjectAssetSummary(1, RelativePath("source.txt"), ProjectAssetFormat.TXT,
                0, true, source.toByteArray().size.toLong(), "a".repeat(64), 1)))
        val projects = Proxy.newProxyInstance(TextProjectPort::class.java.classLoader,
            arrayOf(TextProjectPort::class.java)) { _, method, args ->
            when (method.name) {
                "open" -> summary
                "items" -> TextItemPage(3, if ((args[2] as Long) == 0L) listOf(item) else emptyList())
                else -> error("Unexpected operation: " + method.name)
            }
        } as TextProjectPort
        val lease = object : RunLease {
            override val identity = RunIdentity(ref.projectId, ChatId("scan-chat"), RunId("scan-run"), CommandId("scan-command"))
        }
        val script = context.assets.open("workspace-skills/text-preserve/scripts/main.js")
            .bufferedReader().use { it.readText() }
        var report: String? = null
        val analysis = ProjectWorkspaceAnalysis(context, projects,
            artifactPublisher = ProjectWorkspaceArtifactPublisher { actualRef, _, drafts ->
                assertEquals(ref, actualRef)
                report = drafts.single().bytes.toString(Charsets.UTF_8)
                emptyList()
            })
        val result = analysis.analyze(ref, lease, script, 3, inputJson = """
            {"patterns":[{"key":"tag","source":"TAG"},{"key":"zero","source":"(?=T)"},
            {"key":"bad","source":"["}],"max_matches_per_pattern":1}
        """.trimIndent())
        assertEquals(0, result.result.exitCode)
        val json = Json.parseToJsonElement(requireNotNull(report)).jsonObject
        assertEquals(1, json.getValue("scanned_item_count").jsonPrimitive.int)
        val patterns = json.getValue("patterns").jsonArray
        val tag = patterns[0].jsonObject
        assertEquals(2, tag.getValue("match_count").jsonPrimitive.int)
        assertFalse(tag.getValue("matches_complete").jsonPrimitive.boolean)
        assertEquals(1, tag.getValue("matches").jsonArray.size)
        assertEquals(2, tag.getValue("file_counts").jsonObject.getValue("source.txt").jsonPrimitive.int)
        assertEquals(2, patterns[1].jsonObject.getValue("zero_length_match_count").jsonPrimitive.int)
        assertTrue(patterns[2].jsonObject.getValue("error") is JsonPrimitive)
    }
}
