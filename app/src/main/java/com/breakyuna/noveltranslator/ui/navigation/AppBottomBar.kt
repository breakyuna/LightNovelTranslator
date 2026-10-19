package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.Motion

/**
 * Phone bottom navigation: tonal pill indicator, icon + label for every item,
 * hairline top border. Item order matches [AppNavigationRail] exactly.
 */
@Composable
fun AppBottomBar(
    currentRoute: String?,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(
                thickness = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                TopLevelDestination.values().forEach { destination ->
                    val isSelected = when (destination) {
                        TopLevelDestination.BOOKSHELF -> currentRoute?.startsWith("bookshelf") == true
                        TopLevelDestination.HISTORY -> currentRoute?.startsWith("history") == true
                        TopLevelDestination.TASKS -> currentRoute?.startsWith("tasks") == true || currentRoute?.startsWith("workbench") == true
                        TopLevelDestination.SETTINGS -> currentRoute?.startsWith("settings") == true
                    }
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.06f else 1f,
                        animationSpec = tween(Motion.DurationMedium),
                        label = "bottomBarIconScale"
                    )

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onNavigateToDestination(destination) },
                        alwaysShowLabel = true,
                        icon = {
                            Icon(
                                imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = destination.getLabel(strings),
                                modifier = Modifier
                                    .size(23.dp)
                                    .scale(iconScale)
                            )
                        },
                        label = {
                            Text(
                                text = destination.getLabel(strings),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    }
}
