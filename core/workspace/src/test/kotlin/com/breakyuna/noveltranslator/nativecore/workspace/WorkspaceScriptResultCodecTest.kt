package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceScriptResultCodecTest {
    @Test fun decodesCreateUpdateAndDeleteChanges() {
        val defaults = WorkspaceScriptLimits.of(WorkspaceBudget())
        val create = WorkspaceScriptResultCodec.decodeChange(
            "{\"path\":\"work/a.txt\",\"operation\":\"CREATE\",\"content\":\"hello\"}",
            scriptRequest(),
            defaults,
        )
        assertEquals(WorkspacePath("work/a.txt"), create.path)
        assertEquals(WorkspaceChangeOperation.CREATE, create.operation)
        assertNull(create.expectedFingerprint)
        assertEquals("hello", String((create.content as WorkspaceChangeContent.Inline).bytes, Charsets.UTF_8))

        val base = WorkspaceDigest(sha256Of("base"))
        val update = WorkspaceScriptResultCodec.decodeChange(
            "{\"path\":\"work/a.txt\",\"operation\":\"UPDATE\",\"baseSha256\":\"${base.sha256}\",\"content\":\"next\"}",
            scriptRequest(),
            defaults,
        )
        assertEquals(WorkspaceChangeOperation.UPDATE, update.operation)
        assertEquals(base, update.expectedFingerprint)
        assertEquals("next", String((update.content as WorkspaceChangeContent.Inline).bytes, Charsets.UTF_8))

        val delete = WorkspaceScriptResultCodec.decodeChange(
            "{\"path\":\"work/a.txt\",\"operation\":\"DELETE\",\"baseSha256\":\"${base.sha256}\"}",
            scriptRequest(),
            defaults,
        )
        assertEquals(WorkspaceChangeOperation.DELETE, delete.operation)
        assertNull(delete.content)
    }

    @Test fun decodesMaterialBackedChangesAndRejectsUnknownMaterial() {
        val body = "material-body".toByteArray(Charsets.UTF_8)
        val summary = materialSummary("mat-1", body)
        val request = scriptRequest(listOf(summary.descriptor()))
        val base = WorkspaceDigest(sha256Of("base"))
        val change = WorkspaceScriptResultCodec.decodeChange(
            "{\"path\":\"work/a.txt\",\"operation\":\"UPDATE\",\"baseSha256\":\"${base.sha256}\",\"fromMaterial\":\"mat-1\"}",
            request,
            WorkspaceScriptLimits.of(WorkspaceBudget()),
        )
        val content = change.content as WorkspaceChangeContent.FromMaterial
        assertEquals(summary.id, content.materialId)
        assertEquals(summary.sha256, content.sha256.sha256)
        assertEquals(summary.sizeBytes, content.sizeBytes)

        val failure = assertThrows(WorkspaceScriptOutputException::class.java) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"work/a.txt\",\"operation\":\"UPDATE\",\"baseSha256\":\"${base.sha256}\",\"fromMaterial\":\"missing\"}",
                request,
                WorkspaceScriptLimits.of(WorkspaceBudget()),
            )
        }
        assertEquals(WorkspaceScriptOutputException.Reason.UNKNOWN_MATERIAL, failure.reason)
    }

    @Test fun rejectsMalformedUnknownDuplicateAndInvalidShapes() {
        val defaults = WorkspaceScriptLimits.of(WorkspaceBudget())
        assertReason(WorkspaceScriptOutputException.Reason.MALFORMED_JSON) {
            WorkspaceScriptResultCodec.decodeChange("{", scriptRequest(), defaults)
        }
        assertReason(WorkspaceScriptOutputException.Reason.MALFORMED_JSON) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"work/a.txt\",\"operation\":\"CREATE\",\"content\":\"a\",\"content\":\"b\"}",
                scriptRequest(),
                defaults,
            )
        }
        assertReason(WorkspaceScriptOutputException.Reason.UNSUPPORTED_FIELD) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"work/a.txt\",\"operation\":\"CREATE\",\"content\":\"a\",\"extra\":true}",
                scriptRequest(),
                defaults,
            )
        }
        assertReason(WorkspaceScriptOutputException.Reason.INVALID_PATH) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"../escape.txt\",\"operation\":\"CREATE\",\"content\":\"a\"}",
                scriptRequest(),
                defaults,
            )
        }
        assertReason(WorkspaceScriptOutputException.Reason.INVALID_OPERATION) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"work/a.txt\",\"operation\":\"APPEND\",\"content\":\"a\"}",
                scriptRequest(),
                defaults,
            )
        }
        val base = WorkspaceDigest(sha256Of("base"))
        assertReason(WorkspaceScriptOutputException.Reason.INVALID_CONTENT) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"work/a.txt\",\"operation\":\"CREATE\",\"baseSha256\":\"${base.sha256}\",\"content\":\"a\"}",
                scriptRequest(),
                defaults,
            )
        }
        assertReason(WorkspaceScriptOutputException.Reason.INVALID_CONTENT) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"work/a.txt\",\"operation\":\"DELETE\",\"baseSha256\":\"${base.sha256}\",\"content\":\"a\"}",
                scriptRequest(),
                defaults,
            )
        }
    }

    @Test fun enforcesTheExistingChangeSizeLimit() {
        val limits = WorkspaceScriptLimits(
            maxOutputBytes = 1024,
            maxLogLines = 10,
            maxChanges = 2,
            maxArtifacts = 2,
            maxChangeBytes = 4,
            maxArtifactBytes = 4,
        )
        assertReason(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE) {
            WorkspaceScriptResultCodec.decodeChange(
                "{\"path\":\"work/a.txt\",\"operation\":\"CREATE\",\"content\":\"12345\"}",
                scriptRequest(),
                limits,
            )
        }
        assertReason(WorkspaceScriptOutputException.Reason.CONTENT_TOO_LARGE) {
            WorkspaceScriptResultCodec.decodeArtifact(
                "{\"path\":\"work/a.bin\",\"mediaType\":\"application/octet-stream\",\"bytesBase64\":\"AAEC/wA=\"}",
                limits,
            )
        }
    }

    @Test fun decodesArtifactsFromTextAndStrictBase64() {
        val defaults = WorkspaceScriptLimits.of(WorkspaceBudget())
        val text = WorkspaceScriptResultCodec.decodeArtifact(
            "{\"path\":\"work/a.md\",\"mediaType\":\"text/markdown\",\"content\":\"# title\"}",
            defaults,
        )
        assertEquals("text/markdown", text.mediaType)
        assertEquals("# title", String(text.bytes, Charsets.UTF_8))
        assertEquals(sha256Of("# title"), text.sha256)

        val binary = WorkspaceScriptResultCodec.decodeArtifact(
            "{\"path\":\"work/a.bin\",\"mediaType\":\"application/octet-stream\",\"bytesBase64\":\"AAEC/w==\"}",
            defaults,
        )
        assertArrayEquals(byteArrayOf(0, 1, 2, 0xFF.toByte()), binary.bytes)

        assertEquals("AAEC/w==", WorkspaceBase64.encode(byteArrayOf(0, 1, 2, 0xFF.toByte())))
        assertReason(WorkspaceScriptOutputException.Reason.INVALID_CONTENT) {
            WorkspaceScriptResultCodec.decodeArtifact(
                "{\"path\":\"work/a.bin\",\"mediaType\":\"application/octet-stream\",\"bytesBase64\":\"!!!!\"}",
                defaults,
            )
        }
        assertReason(WorkspaceScriptOutputException.Reason.INVALID_MEDIA_TYPE) {
            WorkspaceScriptResultCodec.decodeArtifact(
                "{\"path\":\"work/a.md\",\"mediaType\":\"\",\"content\":\"x\"}",
                defaults,
            )
        }
        assertReason(WorkspaceScriptOutputException.Reason.INVALID_CONTENT) {
            WorkspaceScriptResultCodec.decodeArtifact(
                "{\"path\":\"work/a.md\",\"mediaType\":\"text/plain\",\"content\":\"x\",\"bytesBase64\":\"eA==\"}",
                defaults,
            )
        }
    }

    @Test fun exitCodeIsStrictAndIntegral() {
        assertEquals(0, WorkspaceScriptResultCodec.decodeExitCode(null))
        assertEquals(0, WorkspaceScriptResultCodec.decodeExitCode(0))
        assertEquals(7, WorkspaceScriptResultCodec.decodeExitCode(7))
        assertEquals(255, WorkspaceScriptResultCodec.decodeExitCode(255))
        listOf<Any>(1.5, -1, 256, true, "0", Double.NaN).forEach { value ->
            assertReason(WorkspaceScriptOutputException.Reason.INVALID_EXIT_CODE) {
                WorkspaceScriptResultCodec.decodeExitCode(value)
            }
        }
    }

    private fun assertReason(reason: WorkspaceScriptOutputException.Reason, block: () -> Unit) {
        val failure = assertThrows(WorkspaceScriptOutputException::class.java) { block() }
        assertEquals(reason, failure.reason)
    }
}
