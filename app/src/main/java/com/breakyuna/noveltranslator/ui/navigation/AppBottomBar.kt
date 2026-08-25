package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings

@Composable
fun AppBottomBar(
    currentRoute: String?,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
            NavigationBar(
                modifier = Modifier.fillMaxWidth(),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets.navigationBars
            ) {
                val strings = LocalAppStrings.current
                TopLevelDestination.values().forEach { destination ->
                    val isSelected = when (destination) {
                        TopLevelDestination.PROJECTS -> currentRoute?.startsWith("projects") == true
                        TopLevelDestination.TASKS -> currentRoute?.startsWith("tasks") == true || currentRoute?.startsWith("history") == true
                        TopLevelDestination.SETTINGS -> currentRoute?.startsWith("settings") == true
                    }

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onNavigateToDestination(destination) },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = destination.getLabel(strings),
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = destination.getLabel(strings),
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    }
}
