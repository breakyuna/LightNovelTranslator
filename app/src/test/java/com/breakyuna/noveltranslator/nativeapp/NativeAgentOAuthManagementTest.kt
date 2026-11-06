package com.breakyuna.noveltranslator.nativeapp

import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.TranslatorApplication
import com.breakyuna.noveltranslator.nativecore.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NativeAgentOAuthManagementTest {
    @Test fun staleApprovalCannotInvalidatePendingLogin() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<TranslatorApplication>()
        val graph = app.nativeGraph
        try {
            val profile = graph.modelProfiles.create(NewModelProfile(name = "Public OAuth fixture", purpose = ModelProfilePurpose.BOTH,
                endpoint = "http://127.0.0.1:1234/v1", model = "local", protocol = NativeModelProtocol.OPENAI_CHAT_COMPLETIONS, keys = emptyList()))
            val saved = graph.oauth.saveConfiguration(profile, NativeOAuthConfigDraft("https://example.com/authorize",
                "https://example.com/token", "public-fixture", "models.read"))
            assertTrue(saved.configured)
            assertTrue(graph.oauth.beginLogin(profile) is NativeOAuthLoginStart.Open)
            try {
                graph.oauth.applyReviewedManagement(profile, "0".repeat(64), "remove", null)
                fail("Stale approval invalidated OAuth state")
            } catch (_: IllegalArgumentException) { }
            val after = graph.oauth.load(profile.id)
            assertEquals(saved.fingerprint, after.fingerprint)
            assertTrue(after.pendingLogin)
            graph.oauth.applyReviewedManagement(profile, saved.fingerprint, "cancel_login", null)
            assertFalse(graph.oauth.load(profile.id).pendingLogin)
            assertTrue(graph.oauth.load(profile.id).configured)
        } finally { graph.close() }
    }
}
