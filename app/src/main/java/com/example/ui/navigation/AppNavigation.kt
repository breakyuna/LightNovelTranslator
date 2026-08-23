package com.example.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.ui.screens.glossary.GlossaryScreen
import com.example.ui.screens.preview.BilingualReaderScreen
import com.example.ui.screens.projects.ProjectListScreen
import com.example.ui.screens.settings.ApiSettingsScreen
import com.example.ui.screens.translation.TranslationRunnerScreen
import com.example.ui.screens.workspace.ProjectWorkspaceScreen
import com.example.ui.viewmodel.AppViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    object Projects : Screen("projects")
    object Workspace : Screen("workspace/{projectId}") {
        fun createRoute(projectId: Long) = "workspace/$projectId"
    }
    object Translation : Screen("translation/{projectId}") {
        fun createRoute(projectId: Long) = "translation/$projectId"
    }
    object Reader : Screen("reader/{chapterId}") {
        fun createRoute(chapterId: Long) = "reader/$chapterId"
    }
    object Glossary : Screen("glossary/{projectId}") {
        fun createRoute(projectId: Long) = "glossary/$projectId"
    }
    object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    viewModel: AppViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val userMessage by viewModel.userMessage.collectAsState()

    LaunchedEffect(userMessage) {
        userMessage?.let { msg ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(msg)
                viewModel.clearMessage()
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 840.dp
        val activeProjectId by viewModel.activeProjectId.collectAsState()

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            if (isWideScreen && activeProjectId != null) {
                // Adaptive Landscape / Large-screen Master-Detail Split
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // Left Master Pane: Project Workspace & Chapter list
                    Box(modifier = Modifier.width(360.dp).fillMaxHeight()) {
                        ProjectWorkspaceScreen(
                            viewModel = viewModel,
                            onBack = { viewModel.setActiveProject(null) },
                            onNavigateToTranslation = {
                                navController.navigate(Screen.Translation.createRoute(activeProjectId!!))
                            },
                            onNavigateToGlossary = {
                                navController.navigate(Screen.Glossary.createRoute(activeProjectId!!))
                            },
                            onNavigateToReader = { chapterId ->
                                navController.navigate(Screen.Reader.createRoute(chapterId))
                            }
                        )
                    }

                    VerticalDivider()

                    // Right Detail Pane: Dynamic Screen
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Translation.createRoute(activeProjectId!!)
                        ) {
                            composable(
                                route = Screen.Translation.route,
                                arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                            ) {
                                TranslationRunnerScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() },
                                    onNavigateToReader = { chId ->
                                        navController.navigate(Screen.Reader.createRoute(chId))
                                    }
                                )
                            }
                            composable(
                                route = Screen.Reader.route,
                                arguments = listOf(navArgument("chapterId") { type = NavType.LongType })
                            ) { backStackEntry ->
                                val chapterId = backStackEntry.arguments?.getLong("chapterId") ?: 0L
                                BilingualReaderScreen(
                                    chapterId = chapterId,
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable(
                                route = Screen.Glossary.route,
                                arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                            ) {
                                GlossaryScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.Settings.route) {
                                ApiSettingsScreen(
                                    viewModel = viewModel,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            } else {
                // Standard Single-Pane Responsive Navigation (Phones & Portrait)
                NavHost(
                    navController = navController,
                    startDestination = Screen.Projects.route,
                    modifier = Modifier.padding(padding)
                ) {
                    composable(Screen.Projects.route) {
                        ProjectListScreen(
                            viewModel = viewModel,
                            onSelectProject = { projId ->
                                viewModel.setActiveProject(projId)
                                navController.navigate(Screen.Workspace.createRoute(projId))
                            },
                            onOpenSettings = {
                                navController.navigate(Screen.Settings.route)
                            }
                        )
                    }

                    composable(
                        route = Screen.Workspace.route,
                        arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val projId = backStackEntry.arguments?.getLong("projectId") ?: 0L
                        viewModel.setActiveProject(projId)
                        ProjectWorkspaceScreen(
                            viewModel = viewModel,
                            onBack = {
                                viewModel.setActiveProject(null)
                                navController.popBackStack()
                            },
                            onNavigateToTranslation = {
                                navController.navigate(Screen.Translation.createRoute(projId))
                            },
                            onNavigateToGlossary = {
                                navController.navigate(Screen.Glossary.createRoute(projId))
                            },
                            onNavigateToReader = { chapterId ->
                                navController.navigate(Screen.Reader.createRoute(chapterId))
                            }
                        )
                    }

                    composable(
                        route = Screen.Translation.route,
                        arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                    ) {
                        TranslationRunnerScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() },
                            onNavigateToReader = { chId ->
                                navController.navigate(Screen.Reader.createRoute(chId))
                            }
                        )
                    }

                    composable(
                        route = Screen.Reader.route,
                        arguments = listOf(navArgument("chapterId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val chapterId = backStackEntry.arguments?.getLong("chapterId") ?: 0L
                        BilingualReaderScreen(
                            chapterId = chapterId,
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(
                        route = Screen.Glossary.route,
                        arguments = listOf(navArgument("projectId") { type = NavType.LongType })
                    ) {
                        GlossaryScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Settings.route) {
                        ApiSettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
