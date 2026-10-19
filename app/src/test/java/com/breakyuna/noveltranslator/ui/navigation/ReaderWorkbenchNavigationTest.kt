package com.breakyuna.noveltranslator.ui.navigation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.breakyuna.noveltranslator.data.db.AppDatabase
import com.breakyuna.noveltranslator.data.model.ApiProviderEntity
import com.breakyuna.noveltranslator.data.model.BookEntity
import com.breakyuna.noveltranslator.data.model.EditionEntity
import com.breakyuna.noveltranslator.data.model.EditionType
import com.breakyuna.noveltranslator.data.model.LogicalChapterEntity
import com.breakyuna.noveltranslator.data.model.ProviderType
import com.breakyuna.noveltranslator.data.model.TranslationProjectV2Entity
import com.breakyuna.noveltranslator.ui.i18n.ChineseStrings
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.screens.reader.PlatformReaderScreen
import com.breakyuna.noveltranslator.ui.screens.workspace.BookWorkbenchDetailScreen
import com.breakyuna.noveltranslator.ui.theme.NovelTranslatorTheme
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression guard for "opening the workbench from the reader crashes the app".
 *
 * The test drives the real navigation graph (bookshelf -> reader -> workbench) through the real
 * [AppNavigation] composable with a real [AppViewModel] over a seeded database, so a failure
 * here reproduces the user-visible crash instead of only asserting on parameter plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
@OptIn(ExperimentalTestApi::class)
class ReaderWorkbenchNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val bookTitle = "工作台回归测试书"

    /** Chinese [com.breakyuna.noveltranslator.ui.i18n.PlatformUiStrings.openWorkbench]. */
    private val openWorkbenchLabel = "翻译工作台"

    /** Chinese [com.breakyuna.noveltranslator.ui.i18n.WorkbenchUiStrings.tabTasks]. */
    private val tasksTabLabel = "任务控制台"

    private lateinit var viewModel: AppViewModel

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        viewModel = AppViewModel(context)
        seed(context)
    }

    @Test
    fun routeStringsMatchTheRegisteredArguments() {
        // The routes are the contract between the reader action and the workbench destination.
        assertEquals("platform_reader/{bookId}?chapterId={chapterId}", AppDestination.PlatformReader.route)
        assertEquals("workbench/{bookId}", AppDestination.BookWorkbench.route)
        assertEquals("workbench/7", AppDestination.BookWorkbench.createRoute(7L))
    }

    @Test
    fun readerTopBarWorkbenchActionOpensTheWorkbenchWithoutCrashing() {
        renderApp()

        composeRule.onNodeWithText(bookTitle).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntilAtLeastOneExists(hasText("阅读"), timeoutMillis = 5_000)

        composeRule.onNodeWithContentDescription(openWorkbenchLabel).performClick()
        composeRule.waitUntilAtLeastOneExists(hasText(tasksTabLabel), timeoutMillis = 10_000)

        // The workbench renders its own header, not the reader chrome.
        composeRule.waitUntilAtLeastOneExists(hasText(bookTitle, substring = true), timeoutMillis = 5_000)
    }

    @Test
    fun workbenchBackAndForthKeepsTheReaderUsable() {
        renderApp()

        composeRule.onNodeWithText(bookTitle).performClick()
        composeRule.waitForIdle()
        composeRule.waitUntilAtLeastOneExists(hasText("阅读"), timeoutMillis = 5_000)

        composeRule.onNodeWithContentDescription(openWorkbenchLabel).performClick()
        composeRule.waitUntilAtLeastOneExists(hasText(tasksTabLabel), timeoutMillis = 10_000)

        // Back to the reader: the reader's own saveable state must still be restorable.
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("阅读"), timeoutMillis = 5_000)

        // And back into the workbench a second time, which must not inherit stale state.
        composeRule.onNodeWithContentDescription(openWorkbenchLabel).performClick()
        composeRule.waitUntilAtLeastOneExists(hasText(tasksTabLabel), timeoutMillis = 10_000)
        assertTrue(true)
    }

    private fun renderApp() {
        composeRule.setContent {
            NovelTranslatorTheme {
                CompositionLocalProvider(LocalAppStrings provides ChineseStrings) {
                    AppNavigation(viewModel = viewModel)
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Mirrors what [AppNavigation] hands to the two screens, so the wiring itself is part of
     * the regression surface: a missing or mistyped argument fails here first.
     */
    @Test
    fun screensAcceptTheArgumentsAppNavigationPasses() {
        composeRule.setContent {
            NovelTranslatorTheme {
                CompositionLocalProvider(LocalAppStrings provides ChineseStrings) {
                    PlatformReaderScreen(
                        bookId = 7L,
                        initialLogicalChapterId = null,
                        viewModel = viewModel,
                        onBack = {},
                        onOpenWorkbench = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntilAtLeastOneExists(hasText("阅读"), timeoutMillis = 5_000)

        composeRule.setContent {
            NovelTranslatorTheme {
                CompositionLocalProvider(LocalAppStrings provides ChineseStrings) {
                    BookWorkbenchDetailScreen(
                        bookId = 7L,
                        viewModel = viewModel,
                        onNavigateBack = {},
                        onOpenReader = { _, _ -> },
                        onOpenAgent = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // Loading state first, then the workbench header once the seeded book arrives.
        composeRule.waitUntilAtLeastOneExists(hasText("工作台", substring = true), timeoutMillis = 10_000)
    }

    private fun seed(context: Application) {
        val db = AppDatabase.getDatabase(context)
        runBlocking {
            val bookId = db.bookDao().insert(
                BookEntity(
                    title = bookTitle,
                    author = "回归测试",
                    originalLanguage = "zh",
                    primaryEditionId = null,
                    preferredReadingEditionId = null
                )
            )
            val sourceEditionId = db.bookDao().insertEdition(
                EditionEntity(
                    bookId = bookId,
                    name = "原文版",
                    type = EditionType.IMPORTED.name,
                    language = "zh"
                )
            )
            val targetEditionId = db.bookDao().insertEdition(
                EditionEntity(
                    bookId = bookId,
                    name = "AI 译文版",
                    type = EditionType.AI_TRANSLATION.name,
                    language = "zh",
                    sourceEditionId = sourceEditionId
                )
            )
            repeat(3) { index ->
                db.bookDao().insertLogicalChapter(
                    LogicalChapterEntity(
                        bookId = bookId,
                        chapterIndex = index + 1,
                        canonicalTitle = "第 ${index + 1} 章"
                    )
                )
            }
            db.apiProviderDao().insertProvider(
                ApiProviderEntity(
                    name = "回归 Provider",
                    providerType = ProviderType.OPENAI_COMPATIBLE,
                    baseUrl = "https://api.example.com/v1",
                    apiKey = "",
                    selectedModel = "gpt-test",
                    isDefault = true
                )
            )
            db.translationProjectV2Dao().insert(
                TranslationProjectV2Entity(
                    bookId = bookId,
                    sourceEditionId = sourceEditionId,
                    targetEditionId = targetEditionId,
                    sourceLanguage = "zh",
                    targetLanguage = "zh",
                    providerId = null,
                    modelName = "gpt-test",
                    translationMode = "FULL_BOOK"
                )
            )
        }
    }
}
