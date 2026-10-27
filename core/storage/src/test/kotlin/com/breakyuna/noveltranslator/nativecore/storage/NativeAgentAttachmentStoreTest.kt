package com.breakyuna.noveltranslator.nativecore.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.nativecore.domain.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** File-boundary tests only; no device, provider or paid model call is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class NativeAgentAttachmentStoreTest {
    private lateinit var root: File
    private lateinit var store: NativeAgentAttachmentStore
    private val project = LocalProjectRef(ProjectId("project"), ProjectInstanceId("instance"),
        RecoveryGeneration(3))

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = File(context.cacheDir, "attachment-test-${UUID.randomUUID()}")
        store = NativeAgentAttachmentStore(root)
    }

    @After fun cleanup() {
        root.deleteRecursively()
    }

    @Test fun saveResolveRoundTripUsesExactBytesAndScopedLayout() = runBlocking {
        val bytes = png(4096)
        val id = store.save(bytes, "image/png", project, "page\n1.png")
        val data = store.resolve(project, id)
        assertEquals(id, data.id)
        assertEquals("image/png", data.mimeType)
        assertEquals("page1.png", data.displayName)
        assertEquals(bytes.size, data.sizeBytes)
        assertEquals(TextProjectFingerprint.bytes(bytes), data.sha256)
        assertArrayEquals(bytes, data.bytes())
        assertArrayEquals(bytes, store.resolveImage(FrozenImageRef(id, "image/png", data.sha256)))

        val file = singleStoredFile()
        assertEquals(project.generation.value.toString(), file.parentFile.name)
        assertTrue(file.name.startsWith(id.value))
        assertTrue(root.walkTopDown().none { it.name.startsWith(".tmp-") })
    }

    @Test fun oversizedEmptyAndForeignMimeAreRejectedBeforeWriting() = runBlocking {
        expectAttachment<AgentAttachmentException.TooLarge> {
            store.save(png(AgentAttachmentLimits.MAX_IMAGE_BYTES + 1), "image/png", project)
        }
        expectAttachment<AgentAttachmentException.Empty> {
            store.save(ByteArray(0), "image/png", project)
        }
        expectAttachment<AgentAttachmentException.InvalidMime> {
            store.save(png(128), "image/jpeg", project)
        }
        expectAttachment<AgentAttachmentException.InvalidMime> {
            store.save(png(128), "image/gif", project)
        }
        assertTrue(root.walkTopDown().none { it.isFile })
    }

    @Test fun projectOrIdIdentityMismatchIsRejected() = runBlocking {
        val id = store.save(png(512), "image/png", project)
        expectAttachment<AgentAttachmentException.IdentityMismatch> {
            store.resolve(project.copy(generation = RecoveryGeneration(4)), id)
        }
        expectAttachment<AgentAttachmentException.IdentityMismatch> {
            store.resolve(project.copy(instanceId = ProjectInstanceId("other-instance")), id)
        }
        expectAttachment<AgentAttachmentException.IdentityMismatch> {
            store.resolve(project.copy(projectId = ProjectId("other-project")), id)
        }
        expectAttachment<AgentAttachmentException.InvalidId> {
            store.resolve(project, AttachmentId("../../escape"))
        }
        expectAttachment<AgentAttachmentException.InvalidId> {
            store.resolveImage(FrozenImageRef(AttachmentId("not-an-attachment-id"), "image/png", "a".repeat(64)))
        }
    }

    @Test fun corruptPayloadOrDigestMismatchFailsClosed() = runBlocking {
        val id = store.save(png(2048), "image/png", project)
        val file = singleStoredFile()
        val stored = file.readBytes()
        stored[stored.lastIndex] = (stored.last().toInt() xor 0xff).toByte()
        file.writeBytes(stored)
        expectAttachment<AgentAttachmentException.Corrupt> { store.resolve(project, id) }
        expectAttachment<AgentAttachmentException.Corrupt> {
            store.resolveImage(FrozenImageRef(id, "image/png", TextProjectFingerprint.bytes(stored)))
        }

        val missing = store.save(png(256), "image/png", project)
        root.walkTopDown().first { it.name.startsWith(missing.value) }.delete()
        expectAttachment<AgentAttachmentException.Unavailable> { store.resolve(project, missing) }
    }

    @Test fun resolverRejectsAChangedFrozenDigest() = runBlocking {
        val id = store.save(png(1024), "image/png", project)
        expectAttachment<AgentAttachmentException.IdentityMismatch> {
            store.resolveImage(FrozenImageRef(id, "image/png", "0".repeat(64)))
        }
        expectAttachment<AgentAttachmentException.IdentityMismatch> {
            store.resolveImage(FrozenImageRef(id, "image/jpeg", TextProjectFingerprint.bytes(png(1024))))
        }
    }

    private fun singleStoredFile(): File =
        root.walkTopDown().first { it.isFile && it.name.endsWith(".lnta") }

    private suspend inline fun <reified T : Throwable> expectAttachment(noinline block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.java.simpleName}")
        } catch (expected: Throwable) {
            if (expected !is T) throw expected
        }
    }

    private fun png(size: Int): ByteArray {
        require(size >= 8)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) +
            ByteArray(size - 8) { (it % 251).toByte() }
    }
}
