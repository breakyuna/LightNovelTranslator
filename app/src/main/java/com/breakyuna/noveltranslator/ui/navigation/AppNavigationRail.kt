package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings

@Composable
fun AppNavigationRail(
    currentRoute: String?,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    isExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(if (isExpanded) 200.dp else 84.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(vertical = 16.dp, horizontal = if (isExpanded) 12.dp else 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val strings = LocalAppStrings.current

                // Brand / Header at top
                if (isExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = strings.appTitle,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 8.dp)
                            .size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Center spacer: push navigation buttons to the vertical center
                Spacer(modifier = Modifier.weight(1f))

                // Navigation items vertically centered
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TopLevelDestination.values().forEach { destination ->
                        val isSelected = when (destination) {
                            TopLevelDestination.PROJECTS -> currentRoute?.startsWith("projects") == true
                            TopLevelDestination.TASKS -> currentRoute?.startsWith("tasks") == true || currentRoute?.startsWith("history") == true
                            TopLevelDestination.SETTINGS -> currentRoute?.startsWith("settings") == true
                        }

                        if (isExpanded) {
                            NavigationDrawerItem(
                                selected = isSelected,
                                onClick = { onNavigateToDestination(destination) },
                                icon = {
                                    Icon(
                                        imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                        contentDescription = destination.getLabel(strings),
                                        modifier = Modifier.size(22.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = destination.getLabel(strings),
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    )
                                },
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    unselectedContainerColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            )
                        } else {
                            NavigationRailItem(
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
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                ),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                // Bottom spacer to complete vertical centering
                Spacer(modifier = Modifier.weight(1f))

                // Footer Info (Expanded only)
                if (isExpanded) {
                    Text(
                        text = "v2.5.0 · Pro Studio",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Subtle Vertical Divider
            VerticalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
    }
}
