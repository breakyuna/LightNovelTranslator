package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.breakyuna.noveltranslator.ui.adaptive.rememberWindowSize
import com.breakyuna.noveltranslator.ui.screens.glossary.GlossaryScreen
import com.breakyuna.noveltranslator.ui.screens.preview.BilingualReaderScreen
import com.breakyuna.noveltranslator.ui.screens.projects.ProjectListScreen
import com.breakyuna.noveltranslator.ui.screens.settings.ApiSettingsScreen
import com.breakyuna.noveltranslator.ui.screens.tasks.TaskQueueScreen
import com.breakyuna.noveltranslator.ui.screens.translation.TranslationRunnerScreen
import com.breakyuna.noveltranslator.ui.screens.workspace.ProjectWorkspaceScreen
import com.breakyuna.noveltranslator.ui.viewmodel.AppViewModel
import kotlinx.coroutines.launch

// Screen compatibility alias
typealias Screen = AppDestination

@Composable
fun AppNavigation(
    viewModel: AppViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val userMessage by viewModel.userMessage.collectAsState()
    val activeProject by viewModel.activeProject.collectAsState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val windowSize = rememberWindowSize()

    // Determine if current route is a Top-Level Destination (to show bottom bar on phones)
    val isTopLevelDestination = currentRoute in listOf(
        AppDestination.Projects.route,
        AppDestination.Tasks.route,
        AppDestination.Settings.route
    ) || (currentRoute?.startsWith("settings") == true) || (currentRoute?.startsWith("tasks") == true) || (currentRoute?.startsWith("history") == true)

    // Handle floating messages
    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearMessage()
            }
        }
    }

    // Top-Level navigation handler
    val onNavigateToTopLevel: (TopLevelDestination) -> Unit = { destination ->
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Show bottom bar on Compact screens ONLY when on top-level pages
            if (windowSize.useBottomBar && isTopLevelDestination) {
                AppBottomBar(
                    currentRoute = currentRoute,
                    onNavigateToDestination = onNavigateToTopLevel
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = if (windowSize.useBottomBar && isTopLevelDestination) paddingValues.calculateBottomPadding() else 0.dp
                )
        ) {
            // Left Navigation Rail for Medium & Expanded (Tablets / Wide screens)
            if (windowSize.useNavRail) {
                AppNavigationRail(
                    currentRoute = currentRoute,
                    onNavigateToDestination = onNavigateToTopLevel,
                    isExpanded = windowSize.isExpanded
                )
            }

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                NavHost(
                    navController = navController,
                    startDestination = AppDestination.Projects.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // ==========================================
                    // 1. Projects (Top-Level)
                    // ==========================================
                    composable(AppDestination.Projects.route) {
                        ProjectListScreen(
                            viewModel = viewModel,
                            onSelectProject = { projectId ->
                                viewModel.setActiveProject(projectId)
                                navController.navigate(AppDestination.Workspace.createRoute(projectId))
                            },
                            onOpenSettings = {
                                navController.navigate(AppDestination.Settings.createRoute(-1))
                            }
                        )
                    }

                    // ==========================================
                    // 2. Tasks Queue & Audit History (Top-Level)
                    // ==========================================
                    composable(AppDestination.Tasks.route) {
                        TaskQueueScreen(
                            viewModel = viewModel,
                            initialTab = 0,
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable(AppDestination.History.route) {
                        TaskQueueScreen(
                            viewModel = viewModel,
                            initialTab = 1,
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    // ==========================================
                    // 4. Settings (Top-Level)
                    // ==========================================
                    composable(
                        route = AppDestination.Settings.route,
                        arguments = listOf(navArgument("tab") {
                            type = NavType.IntType
                            defaultValue = -1
                        })
                    ) { backStackEntry ->
                        val tab = backStackEntry.arguments?.getInt("tab") ?: -1
                        ApiSettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            initialTab = tab
                        )
                    }

                    // ==========================================
                    // Project Sub-Pages (Entered from Project)
                    // ==========================================
                    // Project Workspace / Home
                    composable(
                        route = AppDestination.Workspace.route,
                        arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val projId = backStackEntry.arguments?.getLong("projectId") ?: 0L
                        LaunchedEffect(projId) {
                            viewModel.setActiveProject(projId)
                        }
                        ProjectWorkspaceScreen(
                            viewModel = viewModel,
                            onBack = {
                                navController.popBackStack()
                            },
                            onNavigateToTranslation = {
                                navController.navigate(AppDestination.Translation.createRoute(projId))
                            },
                            onNavigateToGlossary = {
                                navController.navigate(AppDestination.Glossary.createRoute(projId))
                            },
                            onNavigateToReader = { chapterId ->
                                navController.navigate(AppDestination.Reader.createRoute(chapterId))
                            }
                        )
                    }

                    // Translation Runner
                    composable(
                        route = AppDestination.Translation.route,
                        arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                    ) {
                        TranslationRunnerScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToReader = { chId ->
                                navController.navigate(AppDestination.Reader.createRoute(chId))
                            },
                            onNavigateToTasks = {
                                navController.navigate(AppDestination.Tasks.route)
                            }
                        )
                    }

                    // Project Glossary
                    composable(
                        route = AppDestination.Glossary.route,
                        arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                    ) {
                        GlossaryScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    // Bilingual Reader
                    composable(
                        route = AppDestination.Reader.route,
                        arguments = listOf(navArgument("chapterId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val chapterId = backStackEntry.arguments?.getLong("chapterId") ?: 0L
                        BilingualReaderScreen(
                            chapterId = chapterId,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
