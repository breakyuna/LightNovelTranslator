package com.breakyuna.noveltranslator.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.data.model.ProjectEntity
import com.breakyuna.noveltranslator.ui.i18n.LocalAppStrings
import com.breakyuna.noveltranslator.ui.theme.EmeraldAccent
import com.breakyuna.noveltranslator.ui.theme.PrimaryIndigo
import com.breakyuna.noveltranslator.ui.theme.SecondaryCyan
import com.breakyuna.noveltranslator.ui.theme.TertiaryAmber

enum class NavItem(
    val icon: ImageVector,
    val requiresProject: Boolean = false
) {
    PROJECTS(Icons.Default.LibraryBooks, requiresProject = false),
    WORKSPACE(Icons.Default.Dashboard, requiresProject = true),
    TRANSLATION(Icons.Default.Translate, requiresProject = true),
    TASK_QUEUE(Icons.Default.FormatListNumbered, requiresProject = false),
    GLOSSARY(Icons.Default.Book, requiresProject = true),
    READER(Icons.AutoMirrored.Filled.MenuBook, requiresProject = true),
    SETTINGS(Icons.Default.Settings, requiresProject = false),
    LOGS(Icons.Default.Dns, requiresProject = false);

    fun getTitle(strings: com.breakyuna.noveltranslator.ui.i18n.AppStrings): String {
        return when (this) {
            PROJECTS -> strings.navHome
            WORKSPACE -> strings.navWorkspace
            TRANSLATION -> strings.navTranslation
            TASK_QUEUE -> strings.taskQueueTitle
            GLOSSARY -> strings.navGlossary
            READER -> strings.navReader
            SETTINGS -> strings.navSettings
            LOGS -> strings.navLogs
        }
    }
}

@Composable
fun AppDrawerContent(
    currentRoute: String?,
    activeProject: ProjectEntity?,
    onNavigate: (NavItem) -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current

    ModalDrawerSheet(
        modifier = modifier.widthIn(max = 320.dp),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 12.dp, horizontal = 12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PrimaryIndigo),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoStories,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.appTitle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = strings.appSubtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onCloseDrawer,
                    modifier = Modifier.testTag("close_drawer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.MenuOpen,
                        contentDescription = strings.navCollapseDrawer,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Current Project Status Card in Drawer
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (activeProject != null) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    }
                ),
                border = BorderStroke(
                    1.dp,
                    if (activeProject != null) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                                if (activeProject != null) PrimaryIndigo.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (activeProject != null) Icons.Default.AutoStories else Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = if (activeProject != null) PrimaryIndigo else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.navCurrentProject,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = activeProject?.title ?: strings.navNoActiveProject,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Navigation Items List
            val primaryItems = listOf(
                NavItem.PROJECTS,
                NavItem.WORKSPACE,
                NavItem.TRANSLATION,
                NavItem.TASK_QUEUE,
                NavItem.GLOSSARY,
                NavItem.READER
            )

            val secondaryItems = listOf(
                NavItem.SETTINGS,
                NavItem.LOGS
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                primaryItems.forEach { item ->
                    val isEnabled = !item.requiresProject || activeProject != null
                    val isSelected = when (item) {
                        NavItem.PROJECTS -> currentRoute?.startsWith("projects") == true
                        NavItem.WORKSPACE -> currentRoute?.startsWith("workspace") == true
                        NavItem.TRANSLATION -> currentRoute?.startsWith("translation") == true
                        NavItem.TASK_QUEUE -> currentRoute?.startsWith("task_queue") == true
                        NavItem.GLOSSARY -> currentRoute?.startsWith("glossary") == true
                        NavItem.READER -> currentRoute?.startsWith("reader") == true
                        else -> false
                    }

                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.getTitle(strings),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else if (!isEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.getTitle(strings),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else if (!isEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                if (item.requiresProject && activeProject == null) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Text(
                                            text = "需选书",
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        },
                        selected = isSelected,
                        onClick = {
                            if (isEnabled) {
                                onNavigate(item)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nav_item_${item.name.lowercase()}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                secondaryItems.forEach { item ->
                    val isSelected = when (item) {
                        NavItem.SETTINGS -> currentRoute?.startsWith("settings") == true
                        NavItem.LOGS -> currentRoute?.startsWith("settings") == true
                        else -> false
                    }

                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.getTitle(strings),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = {
                            Text(
                                text = item.getTitle(strings),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        selected = isSelected,
                        onClick = { onNavigate(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nav_item_${item.name.lowercase()}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                }
            }

            // Bottom Footer
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "v2.5.0",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "LLM Engine",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
