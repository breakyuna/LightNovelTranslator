package com.breakyuna.noveltranslator.nativeapp

import android.content.res.Configuration
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectAssetFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Wires the reviewed file/folder import to real native stores; no SAF provider is simulated. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeProjectImportWiringTest {
    private lateinit var app: TranslatorApplication
    private lateinit var graph: NativeAppGraph
    private val stores = mutableListOf<ViewModelStore>()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase("lnt_native_app.db")
        app.deleteDatabase("light_novel_translation_platform.db")
        File(app.filesDir, "native_projects").deleteRecursively()
        graph = app.nativeGraph
    }

    @After fun cleanup() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
        runBlocking { graph.close() }
    }

    private fun model(): NativeProjectViewModel {
        val store = ViewModelStore().also(stores::add)
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NativeProjectViewModel(app, SavedStateHandle(), graph.projects,
                    graph.backgroundTranslations) as T
        })
        return provider[NativeProjectViewModel::class.java]
    }

    private fun review(
        candidates: List<NativeImportCandidate>,
        existing: Set<String> = emptySet(),
    ) = reviewNativeProjectImport(candidates, existing)

    @Test fun txtFirstCreatesTheProjectAndLaterFilesAsAssetsIdempotently() = runBlocking {
        val first = "first body".toByteArray()
        val second = "second body".toByteArray()
        val frozen = review(listOf(
            NativeImportCandidate("test://first", "Book.txt", first.size.toLong()),
            NativeImportCandidate("test://second", "Extra.txt", second.size.toLong()),
        ))
        assertTrue(frozen.canCreate)
        val model = model()
        val created = model.createProjectFromImportReview(frozen, CommandId("txt-import")) { file ->
            if (file.sourceId == "test://first") first else second
        }
        assertTrue(created.failures.isEmpty())
        val summary = graph.projects.open(created.ref)
        assertEquals("Book", summary.name)
        assertEquals(listOf(ProjectAssetFormat.TXT, ProjectAssetFormat.TXT),
            summary.assets.map { it.format })
        assertEquals(listOf("first body", "second body"),
            graph.projects.readPage(created.ref).items.map { it.source })

        val repeated = model.createProjectFromImportReview(frozen, CommandId("txt-import")) { file ->
            if (file.sourceId == "test://first") first else second
        }
        assertEquals(created.ref, repeated.ref)
        assertTrue(repeated.failures.isEmpty())
        assertEquals(2, graph.projects.open(created.ref).assets.size)
        assertEquals(1, graph.projects.registeredProjects().size)
    }

    @Test fun epubFirstIsTheFirstAssetAndThePlaceholderSeedIsRemoved() = runBlocking {
        val epub = epubBytes()
        val txt = "extra text".toByteArray()
        val frozen = review(listOf(
            NativeImportCandidate("test://book", "Book.epub", epub.size.toLong()),
            NativeImportCandidate("test://extra", "Extra.txt", txt.size.toLong()),
        ))
        assertTrue(frozen.canCreate)
        assertEquals(NativeProjectSeed.EPUB_FIRST_ASSET, frozen.seed)
        val model = model()
        val created = model.createProjectFromImportReview(frozen, CommandId("epub-import")) { file ->
            if (file.sourceId == "test://book") epub else txt
        }
        assertTrue(created.failures.isEmpty())
        val summary = graph.projects.open(created.ref)
        assertEquals(listOf(ProjectAssetFormat.EPUB, ProjectAssetFormat.TXT),
            summary.assets.map { it.format })
        assertTrue(summary.assets.none { it.path.value == ".lnt-import-seed.txt" })
        assertTrue(graph.projects.readPage(created.ref).items.any { it.source == "Hello epub" })

        val repeated = model.createProjectFromImportReview(frozen, CommandId("epub-import")) { file ->
            if (file.sourceId == "test://book") epub else txt
        }
        assertEquals(created.ref, repeated.ref)
        assertTrue(repeated.failures.isEmpty())
        assertEquals(2, graph.projects.open(created.ref).assets.size)
    }

    @Test fun aBrokenLaterAssetIsReportedWithoutClaimingFullSuccess() = runBlocking {
        val first = "created text".toByteArray()
        val broken = "not an epub".toByteArray()
        val frozen = review(listOf(
            NativeImportCandidate("test://txt", "Created.txt", first.size.toLong()),
            NativeImportCandidate("test://broken", "Broken.epub", broken.size.toLong()),
        ))
        assertTrue(frozen.canCreate)
        val model = model()
        val created = model.createProjectFromImportReview(frozen, CommandId("partial-import")) { file ->
            if (file.sourceId == "test://txt") first else broken
        }
        assertEquals(listOf("Broken.epub"), created.failures)
        val summary = graph.projects.open(created.ref)
        assertEquals(listOf(ProjectAssetFormat.TXT), summary.assets.map { it.format })
        assertEquals("created text", graph.projects.readPage(created.ref).items.single().source)
    }

    @Test fun cancellingBeforeTheFirstStoreWriteLeavesNoProject() = runBlocking {
        val frozen = review(listOf(
            NativeImportCandidate("test://cancel", "Cancel.txt", 4),
        ))
        assertTrue(frozen.canCreate)
        val model = model()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            model.createProjectFromImportReview(frozen, CommandId("cancel-import")) {
                started.complete(Unit)
                gate.await()
                "text".toByteArray()
            }
        }
        started.await()
        job.cancel()
        val failure = runCatching { job.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(0, graph.projects.registeredProjects().size)
    }

    @Test fun anExistingRegistryNameBlocksCreationBeforeAnyWrite() = runBlocking {
        graph.projects.createTxt(CommandId("existing-project"), "Book",
            com.breakyuna.noveltranslator.nativecore.domain.RelativePath("existing.txt"),
            "existing".toByteArray())
        val names = graph.projects.registeredProjects().mapNotNull { it.summary?.name }.toSet()
        val frozen = review(listOf(
            NativeImportCandidate("test://book", "Book.epub", epubBytes().size.toLong()),
        ), names)
        assertFalse(frozen.canCreate)
        assertEquals(NativeImportIssueCode.PROJECT_NAME_CONFLICT, frozen.issues.single().code)
        val model = model()
        model.createProjectFromImport(frozen)
        assertEquals(1, graph.projects.registeredProjects().size)
    }

    @Test fun missingLegacyDatabaseFailsVisiblyWithoutTouchingFiles() = runBlocking {
        val model = model()
        model.beginLegacyExport()
        model.startLegacyExport("content://missing/tree")
        val state = withTimeout(10_000) {
            model.legacyExportState.first { it.status == NativeLegacyExportStatus.FAILED }
        }
        assertNotNull(state.message)
        assertFalse(app.getDatabasePath("light_novel_translation_platform.db").exists())
    }

    @Test fun corruptLegacyDatabaseFailsWithoutDeletingIt() = runBlocking {
        val database = app.getDatabasePath("light_novel_translation_platform.db")
        database.parentFile?.mkdirs()
        database.writeText("not a database")
        val model = model()
        model.beginLegacyExport()
        model.startLegacyExport("content://missing/tree")
        val state = withTimeout(10_000) {
            model.legacyExportState.first { it.status == NativeLegacyExportStatus.FAILED }
        }
        assertNotNull(state.message)
        assertTrue(database.isFile)
        assertEquals("not a database", database.readText())
    }

    @Test fun newLabelsResolveInEverySupportedLocale() {
        val ids = listOf(
            R.string.native_import_title,
            R.string.native_import_description,
            R.string.native_import_create,
            R.string.native_legacy_export_title,
            R.string.native_legacy_export_privacy,
            R.string.native_more_legacy_export_boundary,
        )
        for (language in listOf("en", "zh", "de", "ja", "ko")) {
            val configuration = Configuration(app.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val localized = app.createConfigurationContext(configuration)
            ids.forEach { id ->
                assertTrue("$language:${localized.getString(id)}", localized.getString(id).isNotBlank())
            }
        }
    }

    private fun epubBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            add("mimetype", "application/epub+zip")
            add("META-INF/container.xml",
                """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""" +
                    """<rootfiles><rootfile full-path="OEBPS/content.opf" """ +
                    """media-type="application/oebps-package+xml"/></rootfiles></container>""")
            add("OEBPS/content.opf",
                """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">""" +
                    """<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""" +
                    """<dc:title>Book</dc:title></metadata><manifest>""" +
                    """<item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>""" +
                    """</manifest><spine><itemref idref="chapter"/></spine></package>""")
            add("OEBPS/chapter.xhtml",
                """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml">""" +
                    """<head><title>Book</title></head><body><p>Hello epub</p></body></html>""")
        }
        return output.toByteArray()
    }
}
