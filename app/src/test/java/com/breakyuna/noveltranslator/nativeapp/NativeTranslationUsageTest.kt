package com.breakyuna.noveltranslator.nativeapp

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.R
import com.breakyuna.noveltranslator.nativecore.domain.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w640dp-h360dp-land-mdpi", sdk = [35])
class NativeTranslationUsageTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val ref = LocalProjectRef(ProjectId("fixture"), ProjectInstanceId("instance"), RecoveryGeneration(1))

    @Test fun actualEstimatedAndMissingUsageRemainSeparateAndTheDialogCanBeClosed() {
        val measured = TokenMeasurement(12, UsageSource.ACTUAL)
        val estimate = TokenMeasurement(7, UsageSource.ESTIMATED)
        val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)
        val totals = RequestUsageTotals().add(RequestUsage(measured, unknown, unknown, estimate, measured))
            .add(RequestUsage(unknown, unknown, unknown, unknown, unknown))
        compose.setContent { NativeTheme { NativeTranslationUsage(ref, totals) } }
        compose.onNodeWithTag("native-usage-toggle").performClick()
        compose.onNodeWithText(context.getString(R.string.native_usage_quantity,
            context.getString(R.string.native_usage_input), "12", "0", 1L)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_usage_quantity,
            context.getString(R.string.native_usage_thinking), "0", "7", 1L)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_usage_notice)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.native_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.native_usage_notice)).assertDoesNotExist()
    }

    @Test fun aRunWithoutResponsesDoesNotPretendToHaveZeroMeasuredTokens() {
        compose.setContent { NativeTheme { NativeTranslationUsage(ref, RequestUsageTotals()) } }
        compose.onNodeWithTag("native-usage-toggle").assertDoesNotExist()
    }

    @Test fun anotherExecutionDoesNotReuseAnOpenUsageDialog() {
        val run = mutableStateOf(RunIdentity(ref.projectId, null, RunId("first"), CommandId("first")))
        val unknown = TokenMeasurement(null, UsageSource.UNKNOWN)
        val totals = RequestUsageTotals().add(RequestUsage(unknown, unknown, unknown, unknown, unknown))
        compose.setContent { NativeTheme { NativeTranslationUsage(ref, totals, run.value) } }
        compose.onNodeWithTag("native-usage-toggle").performClick()
        compose.onNodeWithText(context.getString(R.string.native_usage_notice)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { run.value = RunIdentity(ref.projectId, null, RunId("next"), CommandId("next")) }
        compose.onNodeWithText(context.getString(R.string.native_usage_notice)).assertDoesNotExist()
    }
}
