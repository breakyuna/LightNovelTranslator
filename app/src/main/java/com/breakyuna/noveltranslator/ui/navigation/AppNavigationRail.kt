package com.breakyuna.noveltranslator.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings

@Composable
fun AppNavigationRail(
    currentRoute: String?,
    onNavigateToDestination: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(68.dp),
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
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val strings = LocalAppStrings.current

                // Compact Brand Header at top
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp, bottom = 12.dp)
                        .size(36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = strings.appTitle,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Vertical centering spacer
                Spacer(modifier = Modifier.weight(1f))

                // Navigation items vertically centered without text
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TopLevelDestination.values().forEach { destination ->
                        val isSelected = when (destination) {
                            TopLevelDestination.PROJECTS -> currentRoute?.startsWith("bookshelf") == true
                            TopLevelDestination.TASKS -> currentRoute?.startsWith("tasks") == true || currentRoute?.startsWith("workbench") == true || currentRoute?.startsWith("history") == true
                            TopLevelDestination.SETTINGS -> currentRoute?.startsWith("settings") == true
                        }

                        NavigationRailItem(
                            selected = isSelected,
                            onClick = { onNavigateToDestination(destination) },
                            alwaysShowLabel = false,
                            icon = {
                                Icon(
                                    imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.getLabel(strings),
                                    modifier = Modifier.size(26.dp)
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            ),
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }

                // Bottom spacer to complete vertical centering
                Spacer(modifier = Modifier.weight(1f))
            }

            // Subtle Vertical Divider
            VerticalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
        }
    }
}

