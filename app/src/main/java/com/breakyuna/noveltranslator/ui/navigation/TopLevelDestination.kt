package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.breakyuna.noveltranslator.ui.i18n.AppStrings

enum class TopLevelDestination(
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    PROJECTS(
        route = "projects",
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder
    ),
    TASKS(
        route = "tasks",
        selectedIcon = Icons.Filled.FormatListNumbered,
        unselectedIcon = Icons.Outlined.FormatListNumbered
    ),
    SETTINGS(
        route = "settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    );

    fun getLabel(strings: AppStrings): String = when (this) {
        PROJECTS -> strings.navHome
        TASKS -> "后台任务"
        SETTINGS -> strings.navSettings
    }
}
