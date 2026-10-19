package com.breakyuna.noveltranslator.ui.components.apple

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.breakyuna.noveltranslator.ui.theme.*
import java.util.Locale

/* ============================================================================
 * The single reusable component kit for the app.
 *
 * Every screen composes from these surfaces so the whole product reads as one
 * design system: quiet tonal cards, hairline borders, pill indicators and a
 * consistent large-title header. Material 3 defaults are customized through
 * the theme; screens do not restyle raw primitives ad hoc.
 * ========================================================================== */

/**
 * Large title header with an optional subtitle and trailing actions.
 * Sits below the status bar; content scrolls beneath it.
 */
@Composable
fun AppLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = Spacing.compactHorizontalPadding, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailingContent != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                content = trailingContent
            )
        }
    }
}

/**
 * The standard content card: quiet surface, hairline border, optional press
 * feedback. All list cards in the app use this so elevation language is uniform.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = GroupedCardShape,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = PaddingValues(Spacing.l),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val animatedColor by animateColorAsState(
        targetValue = when {
            onClick != null && pressed -> containerColor.copy(alpha = 0.92f)
            else -> containerColor
        },
        animationSpec = tween(Motion.DurationFast),
        label = "AppCardPress"
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interactionSource, null) { onClick() } else Modifier),
        shape = shape,
        color = animatedColor,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/**
 * Subtle grouped surface container. Kept for settings-style grouped lists.
 */
@Composable
fun AppGroupedSurface(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    shape: Shape = GroupedCardShape,
    border: BorderStroke? = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(
        modifier = modifier,
        shape = shape,
        containerColor = backgroundColor,
        border = border,
        contentPadding = contentPadding,
        onClick = onClick,
        content = content
    )
}

/**
 * Hero card with a soft brand gradient. Used for shelf headers, empty states
 * and summary panels.
 */
@Composable
fun AppHeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = HeroCardShape,
    gradientColors: List<Color> = listOf(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        MaterialTheme.colorScheme.surface
    ),
    border: BorderStroke? = BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
    contentPadding: PaddingValues = PaddingValues(Spacing.l),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(gradientColors))
                .padding(contentPadding)
        ) {
            Column(content = content)
        }
    }
}

/**
 * Subtle pill badge for statuses and metadata.
 */
@Composable
fun AppBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null
) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(12.dp)
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = contentColor
            )
        }
    }
}

/**
 * Section header (uppercase kicker) with an optional footer note.
 */
@Composable
fun AppSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.compactHorizontalPadding + 4.dp,
                bottom = Spacing.s,
                top = Spacing.l
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (trailing != null) trailing()
    }
}

/**
 * Apple-style Section with optional header & footer.
 */
@Composable
fun AppSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    title: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit
) {
    val displayHeader = title ?: header
    Column(modifier = modifier.fillMaxWidth()) {
        if (!displayHeader.isNullOrBlank()) {
            AppSectionHeader(displayHeader)
        }
        AppGroupedSurface(
            contentPadding = PaddingValues(0.dp)
        ) {
            content()
        }
        if (!footer.isNullOrBlank()) {
            Text(
                text = footer,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(
                    start = Spacing.compactHorizontalPadding + 4.dp,
                    end = Spacing.compactHorizontalPadding + 4.dp,
                    top = Spacing.s
                )
            )
        }
    }
}

/**
 * Ultra-clean divider for grouped lists.
 */
@Composable
fun AppDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 16.dp,
    color: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
) {
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = 0.8.dp,
        color = color
    )
}

/**
 * Standard setting / menu row.
 */
@Composable
fun AppSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leadingIcon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBackground: Color = iconTint.copy(alpha = 0.12f),
    valueText: String? = null,
    showChevron: Boolean = true,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val displayIcon = leadingIcon ?: icon
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Spacing.minTouchTarget + 6.dp)
            .then(
                if (onClick != null) Modifier.clickable { onClick() } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (displayIcon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = displayIcon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!valueText.isNullOrBlank()) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (trailingContent != null) {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                trailingContent()
            }
        }

        if (showChevron && onClick != null && trailingContent == null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(13.dp)
            )
        }
    }
}

/**
 * Segmented control with a sliding tonal indicator.
 */
@Composable
fun AppSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEachIndexed { index, title ->
                val isSelected = index == selectedIndex
                Surface(
                    onClick = { onItemSelected(index) },
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                    border = if (isSelected) BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                    shadowElevation = if (isSelected) 1.dp else 0.dp,
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Standard status info row (label / value pair).
 */
@Composable
fun AppStatusRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = valueColor
        )
    }
}

/**
 * Primary action button. Flat brand fill, no elevation shadow.
 */
@Composable
fun AppPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.35f),
            disabledContentColor = contentColor.copy(alpha = 0.5f)
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

/**
 * Secondary quiet button.
 */
@Composable
fun AppSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colorScheme.primary
        ),
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

/**
 * Shared empty-state block: icon, title, description and optional actions.
 */
@Composable
fun AppEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actions: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actions != null) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
                content = actions
            )
        }
    }
}

/**
 * Rounded square icon container used across cards (tonal, consistent radius).
 */
@Composable
fun AppIconBubble(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .alpha(1f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}
