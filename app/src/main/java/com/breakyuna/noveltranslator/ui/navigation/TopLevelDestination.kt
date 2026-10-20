package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Assistant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.breakyuna.noveltranslator.ui.i18n.AppStrings
import com.breakyuna.noveltranslator.ui.i18n.EnglishStrings

enum class TopLevelDestination(
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    BOOKSHELF(
        route = "bookshelf",
        selectedIcon = Icons.Filled.LibraryBooks,
        unselectedIcon = Icons.Outlined.LibraryBooks
    ),
    HISTORY(
        route = "history",
        selectedIcon = Icons.Filled.History,
        unselectedIcon = Icons.Outlined.History
    ),
    TASKS(
        route = "tasks",
        selectedIcon = Icons.Filled.FormatListNumbered,
        unselectedIcon = Icons.Outlined.FormatListNumbered
    ),
    AGENT(
        route = "agent",
        selectedIcon = Icons.Filled.Assistant,
        unselectedIcon = Icons.Outlined.Assistant
    ),
    SETTINGS(
        route = "settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    );

    fun getLabel(strings: AppStrings): String = when (this) {
        BOOKSHELF -> if (strings === EnglishStrings) "Bookshelf" else "书架"
        HISTORY -> if (strings === EnglishStrings) "History" else "阅读历史"
        TASKS -> if (strings === EnglishStrings) "Workbench" else "工作台"
        AGENT -> if (strings === EnglishStrings) "Agent" else "助手"
        SETTINGS -> strings.navSettings
    }
}
