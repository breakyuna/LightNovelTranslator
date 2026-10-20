package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.Motion
import com.breakyuna.noveltranslator.ui.theme.Spacing

/**
 * Tablet / wide-screen navigation rail: brand mark on top, labeled items with a
 * tonal pill indicator, hairline divider on the right. Order and destinations
 * are identical to [AppBottomBar].
 */
@Composable
fun AppNavigationRail(
    currentRoute: String?,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(88.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = Spacing.m, horizontal = Spacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Brand mark
                Box(
                    modifier = Modifier
                        .padding(top = Spacing.xs, bottom = Spacing.l)
                        .size(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = strings.appTitle,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    TopLevelDestination.values().forEach { destination ->
                        val isSelected = when (destination) {
                            TopLevelDestination.BOOKSHELF -> currentRoute?.startsWith("bookshelf") == true
                            TopLevelDestination.HISTORY -> currentRoute?.startsWith("history") == true
                            TopLevelDestination.TASKS -> currentRoute?.startsWith("tasks") == true || currentRoute?.startsWith("workbench") == true
                            TopLevelDestination.AGENT -> currentRoute?.startsWith("agent") == true
                            TopLevelDestination.SETTINGS -> currentRoute?.startsWith("settings") == true
                        }
                        val iconScale by animateFloatAsState(
                            targetValue = if (isSelected) 1.05f else 1f,
                            animationSpec = tween(Motion.DurationMedium),
                            label = "railIconScale"
                        )

                        NavigationRailItem(
                            selected = isSelected,
                            onClick = { onNavigateToDestination(destination) },
                            alwaysShowLabel = true,
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.getLabel(strings),
                                    modifier = Modifier
                                        .size(22.dp)
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
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                HorizontalDivider(
                    modifier = Modifier
                        .width(28.dp)
                        .padding(vertical = Spacing.s),
                    thickness = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )
            }

            VerticalDivider(
                thickness = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
        }
    }
}
