package com.breakyuna.noveltranslator.nativecore.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceUnicodeHostDeviceTest {
    @Test fun normalizationAndGraphemesPreserveUtf16Coordinates() {
        val host = WorkspaceUnicodeHost()
        assertEquals("JK", host.normalize("ＪＫ", "NFKC"))
        assertEquals("é", host.normalize("e\u0301", "NFC"))
        val segments = Json.parseToJsonElement(host.segments("e\u0301😀Z")).jsonArray
        assertEquals(listOf("e\u0301", "😀", "Z"), segments.map { it.jsonObject.getValue("segment").jsonPrimitive.content })
        assertEquals(listOf(0, 2, 4), segments.map { it.jsonObject.getValue("index").jsonPrimitive.int })
    }

    @Test fun unsupportedFormsAndOversizedInputsAreRejected() {
        val host = WorkspaceUnicodeHost()
        try { host.normalize("text", "invalid"); fail("Accepted invalid form") }
        catch (_: IllegalArgumentException) { }
        try { host.segments("x".repeat(WorkspaceUnicodeHost.MAX_INPUT_CHARS + 1)); fail("Accepted oversized input") }
        catch (_: IllegalArgumentException) { }
    }
}
