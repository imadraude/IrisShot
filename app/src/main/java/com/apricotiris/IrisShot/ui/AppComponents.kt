package com.apricotiris.IrisShot.ui

import android.graphics.Bitmap
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R
import kotlinx.coroutines.launch

internal val AppButtonHeight = 40.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        ),
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTooltipIconButton(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltipText: String = contentDescription,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltipText) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(imageVector = imageVector, contentDescription = contentDescription)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortOrderSheet(
    selected: ContentSortOrder,
    options: List<ContentSortOrder>,
    onSelect: (ContentSortOrder) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(R.string.sort_order),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        options.forEach { order ->
            ListItem(
                headlineContent = { Text(stringResource(order.optionLabelRes)) },
                trailingContent = {
                    if (order == selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.common_selected),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = order == selected,
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                onSelect(order)
                            }
                        },
                        role = Role.RadioButton,
                    ),
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

@Composable
fun MainBottomNavigation(
    selected: AppDestination,
    onSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        AppDestination.entries.forEach { destination ->
            val icon = when (destination) {
                AppDestination.HOME -> Icons.Default.Home
                AppDestination.RESULTS -> Icons.Default.DateRange
                AppDestination.TEMPLATES -> Icons.AutoMirrored.Filled.List
                AppDestination.SETTINGS -> Icons.Default.Settings
            }
            val isSelected = destination == selected
            val label = stringResource(destination.labelRes)
            RetainedBottomNavigationItem(
                selected = isSelected,
                onActivate = { onSelected(destination) },
                icon = icon,
                label = label,
            )
        }
    }
}

@Composable
fun AppNavigationRail(
    selected: AppDestination,
    onSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow ?: MaterialTheme.colorScheme.surface,
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(16.dp))
                // Minimalist non-gradient mark
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "GL",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        },
    ) {
        AppDestination.entries.forEach { destination ->
            val icon = when (destination) {
                AppDestination.HOME -> Icons.Default.Home
                AppDestination.RESULTS -> Icons.Default.DateRange
                AppDestination.TEMPLATES -> Icons.AutoMirrored.Filled.List
                AppDestination.SETTINGS -> Icons.Default.Settings
            }
            val isSelected = destination == selected
            val label = stringResource(destination.labelRes)
            RetainedNavigationRailItem(
                selected = isSelected,
                onActivate = { onSelected(destination) },
                icon = icon,
                label = label,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.long_screenshot_tool),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("v1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RowScope.RetainedBottomNavigationItem(
    selected: Boolean,
    onActivate: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val selectedState = rememberUpdatedState(selected)
    val onActivateState = rememberUpdatedState(onActivate)
    val stableOnActivate = remember { { onActivateState.value() } }
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    var itemFocused by remember { mutableStateOf(false) }
    val iconContent: @Composable () -> Unit = remember(icon) {
        { Icon(icon, contentDescription = null) }
    }
    val labelContent: @Composable () -> Unit = remember(label) {
        { Text(label, style = MaterialTheme.typography.labelMedium) }
    }

    Layout(
        modifier = Modifier
            .weight(1f)
            .focusRequester(focusRequester)
            .onFocusChanged { itemFocused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.isNavigationActivationKey()) {
                    stableOnActivate()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .clearAndSetSemantics {
                contentDescription = label
                this.selected = selected
                role = Role.Tab
                focused = itemFocused
                onClick {
                    stableOnActivate()
                    true
                }
                requestFocus {
                    focusRequester.requestFocus()
                    true
                }
            },
        content = {
            listOf(false, true).forEach { retainedSelection ->
                NavigationBarItem(
                    modifier = Modifier
                        .focusProperties { canFocus = false }
                        .clearAndSetSemantics {},
                    selected = retainedSelection,
                    onClick = stableOnActivate,
                    icon = iconContent,
                    label = labelContent,
                    alwaysShowLabel = false,
                    interactionSource = interactionSource,
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = placeables.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            placeables[if (selectedState.value) 1 else 0].placeRelative(0, 0)
        }
    }
}

@Composable
private fun RetainedNavigationRailItem(
    selected: Boolean,
    onActivate: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val selectedState = rememberUpdatedState(selected)
    val onActivateState = rememberUpdatedState(onActivate)
    val stableOnActivate = remember { { onActivateState.value() } }
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    var itemFocused by remember { mutableStateOf(false) }
    val iconContent: @Composable () -> Unit = remember(icon) {
        { Icon(icon, contentDescription = null) }
    }
    val labelContent: @Composable () -> Unit = remember(label) {
        { Text(label, style = MaterialTheme.typography.labelMedium) }
    }

    Layout(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { itemFocused = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.isNavigationActivationKey()) {
                    stableOnActivate()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .clearAndSetSemantics {
                contentDescription = label
                this.selected = selected
                role = Role.Tab
                focused = itemFocused
                onClick {
                    stableOnActivate()
                    true
                }
                requestFocus {
                    focusRequester.requestFocus()
                    true
                }
            },
        content = {
            listOf(false, true).forEach { retainedSelection ->
                NavigationRailItem(
                    modifier = Modifier
                        .focusProperties { canFocus = false }
                        .clearAndSetSemantics {},
                    selected = retainedSelection,
                    onClick = stableOnActivate,
                    icon = iconContent,
                    label = labelContent,
                    alwaysShowLabel = false,
                    interactionSource = interactionSource,
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOf { it.width }.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = placeables.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            placeables[if (selectedState.value) 1 else 0].placeRelative(0, 0)
        }
    }
}

private fun androidx.compose.ui.input.key.KeyEvent.isNavigationActivationKey(): Boolean =
    when (nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_CENTER,
        AndroidKeyEvent.KEYCODE_ENTER,
        AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
        AndroidKeyEvent.KEYCODE_SPACE -> true
        else -> false
    }

@Composable
internal fun CancelScrollWhenHidden(
    isActive: () -> Boolean,
    scrollableState: ScrollableState,
) {
    val active = isActive()
    LaunchedEffect(active, scrollableState) {
        if (!active) {
            scrollableState.scroll(MutatePriority.PreventUserInput) {}
        }
    }
}

@Composable
fun SettingsSectionHeader(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}



@Composable
fun SettingsSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
    )
}

@Composable
fun SettingsSliderItem(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    valueFormatter: (Float) -> String,
    description: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeImmediate: ((Float) -> Unit)? = null,
    onDragStateChange: ((Boolean) -> Unit)? = null,
    detailsAlpha: Float = 1f,
    headerControl: (@Composable () -> Unit)? = null,
) {
    var sliderValue by remember(value) { mutableFloatStateOf(value) }
    val formattedValue = valueFormatter(sliderValue)
    val accessibilityDescription = if (description.isNullOrBlank()) {
        title
    } else {
        stringResource(R.string.accessibility_title_description, title, description)
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)

    LaunchedEffect(isDragged) {
        currentOnDragStateChange?.invoke(isDragged)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column(
            modifier = Modifier
                .graphicsLayer { alpha = detailsAlpha.coerceIn(0f, 1f) },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier
                        .weight(1f)
                        .clearAndSetSemantics {}
                )
                headerControl?.invoke()
                Text(
                    text = formattedValue,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
        Slider(
            value = sliderValue.coerceIn(range),
            onValueChange = {
                sliderValue = it
                onValueChangeImmediate?.invoke(it)
            },
            onValueChangeFinished = {
                onValueChange(sliderValue)
            },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier
                .padding(top = 4.dp)
                .semantics {
                    contentDescription = accessibilityDescription
                    stateDescription = formattedValue
                }
        )
    }
}

@Composable
fun SettingsNavigationItem(
    title: String,
    subtitle: String? = null,
    valueText: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = Icons.AutoMirrored.Filled.ArrowForward,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
        },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        },
        trailingContent = if (valueText != null || trailingIcon != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (valueText != null) {
                        Text(
                            text = valueText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.padding(end = if (trailingIcon != null) 8.dp else 0.dp)
                        )
                    }
                    if (trailingIcon != null) {
                        Icon(
                            imageVector = trailingIcon,
                            contentDescription = null,
                            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier,
            )
    )
}

@Composable
fun PermissionStatusItem(
    title: String,
    description: String,
    icon: ImageVector,
    granted: Boolean,
    statusDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionable: Boolean = true,
    trailingText: String? = null,
    customTrailingContent: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        },
        trailingContent = {
            when {
                customTrailingContent != null -> customTrailingContent()
                trailingText != null -> Text(
                    text = trailingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Icon(
                    imageVector = if (granted) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = statusDescription,
                    tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = actionable, onClick = onClick)
    )
}

@Composable
fun TemplateListItem(
    name: String,
    appName: String,
    resolutionText: String,
    directionText: String,
    timeText: String,
    selected: Boolean,
    selectionMode: Boolean,
    menuExpanded: Boolean,
    onMenuClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val moreActionsDescription = stringResource(R.string.common_more_actions, name)
    val selectedDescription = stringResource(R.string.common_selected)
    val notSelectedDescription = stringResource(R.string.common_not_selected)
    ListItem(
        headlineContent = {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val infoText = buildString {
                    append("$resolutionText · $directionText")
                    if (timeText.isNotEmpty()) {
                         append(" · $timeText")
                    }
                }
                Text(
                    text = infoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        trailingContent = {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.clearAndSetSemantics {},
                )
            } else {
                Box {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = moreActionsDescription,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = onDismissMenu
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_edit_template)) },
                            onClick = {
                                onDismissMenu()
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            onClick = {
                                onDismissMenu()
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_create_copy)) },
                            onClick = {
                                onDismissMenu()
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                onDismissMenu()
                                onDelete()
                            }
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                if (selectionMode) {
                    stateDescription = if (selected) selectedDescription else notSelectedDescription
                }
            }
            .combinedClickable(
                onClick = if (selectionMode) onToggleSelection else onEdit,
                onLongClick = onLongClick,
            )
    )
}

@Composable
fun ResultListItem(
    filename: String,
    resolutionText: String,
    sizeText: String,
    timeText: String,
    thumbnail: Bitmap?,
    selected: Boolean,
    selectionMode: Boolean,
    menuExpanded: Boolean,
    onMenuClick: () -> Unit,
    onDismissMenu: () -> Unit,
    onView: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbnailImage = remember(thumbnail) { thumbnail?.asImageBitmap() }
    val actionsDescription = stringResource(R.string.common_actions)
    val selectedDescription = stringResource(R.string.common_selected)
    val notSelectedDescription = stringResource(R.string.common_not_selected)
    ListItem(
        headlineContent = {
            Text(
                text = filename,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                text = "$resolutionText · $sizeText · $timeText",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(56.dp, 40.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnailImage != null) {
                    androidx.compose.foundation.Image(
                        bitmap = thumbnailImage,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        trailingContent = {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    modifier = Modifier.clearAndSetSemantics {},
                )
            } else {
                Box {
                    IconButton(onClick = onMenuClick) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = actionsDescription,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = onDismissMenu
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_view)) },
                            onClick = {
                                onDismissMenu()
                                onView()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_share)) },
                            onClick = {
                                onDismissMenu()
                                onShare()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_rename)) },
                            onClick = {
                                onDismissMenu()
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.common_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = {
                                onDismissMenu()
                                onDelete()
                            }
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                this.selected = selected
                if (selectionMode) {
                    stateDescription = if (selected) selectedDescription else notSelectedDescription
                }
            }
            .combinedClickable(
                onClick = if (selectionMode) onToggleSelection else onView,
                onLongClick = onLongClick,
            )
    )
}

@Composable
fun EmptyState(
    title: String,
    description: String,
    icon: ImageVector,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) {
                    Text(text = actionLabel, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun ErrorState(
    error: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = stringResource(R.string.common_error),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(R.string.common_back))
                }
                Button(onClick = onRetry, colors = PrimaryButtonColors) {
                    Text(stringResource(R.string.common_retry), fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
    isDangerous: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resolvedConfirmLabel = confirmLabel ?: stringResource(R.string.common_confirm)
    val resolvedDismissLabel = dismissLabel ?: stringResource(R.string.common_cancel)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = resolvedConfirmLabel,
                    fontWeight = FontWeight.Medium,
                    color = if (isDangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = resolvedDismissLabel)
            }
        }
    )
}

val PrimaryButtonColors
    @Composable get() = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )
