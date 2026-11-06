package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.CommandId
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicyCodec
import com.breakyuna.noveltranslator.nativecore.domain.RelativePath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Persona is a bounded app preference, never project data, backup data or diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativePersonaTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences
        get() = context.getSharedPreferences("native_app_settings", Context.MODE_PRIVATE)

    private var graph: NativeAppGraph? = null

    @Before fun setup() {
        preferences.edit().clear().commit()
    }

    @After fun cleanup() = runBlocking {
        try { graph?.close() } finally { preferences.edit().clear().commit() }
        Unit
    }

    @Test fun personaIsBoundedTrimmedAndPersistedAcrossStoreInstances() {
        val store = NativeAppSettingsStore(context)
        assertEquals("", store.appPersona())
        store.saveAppPersona("  Warm, concise; keep names.\nSecond line\t.  ")
        assertEquals("Warm, concise; keep names.\nSecond line\t.",
            NativeAppSettingsStore(context).appPersona())

        assertThrows(IllegalArgumentException::class.java) {
            store.saveAppPersona("x".repeat(NativeAppPersona.MAX_CHARS + 1))
        }

        store.saveAppPersona("x".repeat(NativeAppPersona.MAX_CHARS))
        assertEquals(NativeAppPersona.MAX_CHARS, NativeAppSettingsStore(context).appPersona().length)

        store.saveAppPersona("  ")
        assertEquals("", NativeAppSettingsStore(context).appPersona())
    }

    @Test fun personaStaysOutOfProjectPolicyApplicationBackupAndDiagnostics() = runBlocking {
        val persona = "PERSONA-ONLY-" + UUID.randomUUID()
        NativeAppSettingsStore(context).saveAppPersona(persona)
        val app = context as TranslatorApplication
        val graph = app.nativeGraph
        this@NativePersonaTest.graph = graph
        val project = graph.projects.createTxt(CommandId("persona-scope"), "persona-scope-" + UUID.randomUUID(),
            RelativePath("source.txt"), "source line".toByteArray())
        val policy = graph.projects.textPolicy(project.ref).policy
        assertFalse(ProjectTextPolicyCodec.encode(policy).contains(persona))

        val projectBackup = ByteArrayOutputStream()
        graph.backups.export(project.ref) { projectBackup }
        assertFalse(unzipText(projectBackup.toByteArray()).contains(persona))

        val backup = ByteArrayOutputStream()
        graph.applicationBackups.export { backup }
        assertFalse(unzipText(backup.toByteArray()).contains(persona))

        graph.diagnostics.clear()
        NativeAppSettingsStore(context).saveAppPersona(persona)
        val page = graph.diagnostics.query()
        val records = page.entries.mapNotNull { graph.diagnostics.read(it.id) }
        assertTrue(records.none { record ->
            record.message.contains(persona) || record.detail?.contains(persona) == true
        })
    }

    private fun unzipText(bytes: ByteArray): String {
        val text = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                text.append(zip.readBytes().toString(Charsets.UTF_8)).append('\n')
            }
        }
        return text.toString()
    }
}
