package com.apricotiris.IrisShot.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.res.stringResource
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.ui.PrimaryButtonColors
import com.apricotiris.IrisShot.ui.AppButtonHeight
import com.apricotiris.IrisShot.ui.DEFAULT_NON_FOCUSED_REGION_OPACITY
import com.apricotiris.IrisShot.ui.DEFAULT_OVERLAY_PANEL_OPACITY
import com.apricotiris.IrisShot.ui.MAX_OVERLAY_OPACITY
import com.apricotiris.IrisShot.ui.MIN_OVERLAY_OPACITY
import com.apricotiris.IrisShot.ui.SettingsSectionHeader
import com.apricotiris.IrisShot.ui.SettingsSliderItem
import com.apricotiris.IrisShot.ui.TemplateTimingControls
import com.apricotiris.IrisShot.ui.normalizeOverlayOpacity
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OverlayConfigurationContent(
    template: GameCaptureTemplate,
    templates: List<GameCaptureTemplate>,
    packageFilter: String,
    templatesLoading: Boolean = false,
    includeEditingDraft: Boolean = false,
    initialScrollItemIndex: Int,
    initialScrollItemOffset: Int,
    onScrollPositionChange: (index: Int, offset: Int) -> Unit,
    onTemplateSelected: (GameCaptureTemplate) -> Unit,
    onNewTemplate: () -> Unit = {},
    onDirectionSelected: (ScrollDirection) -> Unit,
    onWaitChanged: (Long) -> Unit,
    onGestureDurationChanged: (Long) -> Unit = {},
    onStableTimeChanged: (Long) -> Unit = {},
    onPostGestureWaitEnabledChanged: (Boolean) -> Unit = {},
    onPostGestureWaitChanged: (Long) -> Unit = {},
    panelOpacity: Float = DEFAULT_OVERLAY_PANEL_OPACITY,
    nonFocusedRegionOpacity: Float = DEFAULT_NON_FOCUSED_REGION_OPACITY,
    onPanelOpacityChanging: (Float) -> Unit = {},
    onPanelOpacityChanged: (Float) -> Unit = {},
    onNonFocusedRegionOpacityChanging: (Float) -> Unit = {},
    onNonFocusedRegionOpacityChanged: (Float) -> Unit = {},
    onNonFocusedOpacityPreviewChanged: (Boolean) -> Unit = {},
    onStartCapture: () -> Unit,
    onEditRegions: () -> Unit,
    onLearnRegions: () -> Unit,
    onSaveTemplate: () -> Unit,
    onCollapse: () -> Unit,
    onExit: () -> Unit,
    startContentInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    var previewingNonFocusedOpacity by remember { mutableStateOf(false) }
    val normalizedPanelOpacity = normalizeOverlayOpacity(panelOpacity)
    val regularContentAlpha by animateFloatAsState(
        targetValue = if (previewingNonFocusedOpacity) 0f else 1f,
        animationSpec = tween(durationMillis = OverlayOpacityFadeDurationMillis),
        label = "悬浮窗页面内容可见性",
    )
    val nonFocusedSliderAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = OverlayOpacityFadeDurationMillis),
        label = "非焦点透明度滑条可见性",
    )
    val popupContentAlpha by animateFloatAsState(
        targetValue = if (previewingNonFocusedOpacity) 0f else normalizedPanelOpacity,
        animationSpec = tween(durationMillis = OverlayOpacityFadeDurationMillis),
        label = "模板菜单透明度",
    )
    val nonFocusedDetailsAlpha by animateFloatAsState(
        targetValue = if (previewingNonFocusedOpacity) 0f else 1f,
        animationSpec = tween(durationMillis = OverlayOpacityFadeDurationMillis),
        label = "非焦点透明度滑条说明",
    )
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollItemIndex.coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = initialScrollItemOffset.coerceAtLeast(0),
    )
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            onScrollPositionChange(index, offset)
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("overlay-configuration"),
        color = MaterialTheme.colorScheme.surface.copy(alpha = regularContentAlpha),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = regularContentAlpha),
        ),
        shadowElevation = 8.dp * regularContentAlpha,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("overlay-configuration-list"),
            contentPadding = PaddingValues(
                start = startContentInset,
                bottom = 12.dp,
            ),
        ) {
            item(key = "template-header") {
                SettingsSectionHeader(
                    title = stringResource(R.string.overlay_section_templates),
                    modifier = Modifier
                        .alpha(regularContentAlpha)
                        .testTag("overlay-template-section-header"),
                )
            }
            item(key = "template-selector") {
                OverlayTemplateSelector(
                    template = template,
                    templates = templates,
                    packageFilter = packageFilter,
                    templatesLoading = templatesLoading,
                    includeEditingDraft = includeEditingDraft,
                    contentAlpha = popupContentAlpha,
                    onTemplateSelected = onTemplateSelected,
                    onNewTemplate = onNewTemplate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(regularContentAlpha)
                        .padding(horizontal = 16.dp),
                )
            }

            item(key = "actions-header") {
                SettingsSectionHeader(
                    title = stringResource(R.string.overlay_section_actions),
                    modifier = Modifier
                        .alpha(regularContentAlpha)
                        .testTag("overlay-actions-header"),
                )
            }
            item(key = "actions") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(regularContentAlpha)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onStartCapture,
                        colors = PrimaryButtonColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(OverlayActionHeight)
                            .testTag("overlay-start-capture"),
                    ) {
                        Text(stringResource(R.string.overlay_start_capture))
                    }
                    OverlayActionRow(
                        firstLabel = stringResource(R.string.overlay_edit_regions),
                        onFirstClick = onEditRegions,
                        firstTag = "overlay-edit-regions",
                        secondLabel = stringResource(R.string.overlay_learn_regions),
                        onSecondClick = onLearnRegions,
                        secondTag = "overlay-learn-regions",
                    )
                    // Keep this action visible for existing templates as well: after editing a
                    // saved template, the user must be able to explicitly overwrite it.
                    OutlinedButton(
                            onClick = onSaveTemplate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(OverlayActionHeight)
                            .testTag("overlay-save-template"),
                    ) {
                            Text(
                                stringResource(
                                    if (template.id == 0L) {
                                        R.string.overlay_save_template
                                    } else {
                                        R.string.overlay_overwrite_saved_template
                                    },
                                ),
                            )
                        }
                    OverlayActionRow(
                        firstLabel = stringResource(R.string.overlay_collapse),
                        onFirstClick = onCollapse,
                        firstTag = "overlay-collapse",
                        secondLabel = stringResource(R.string.overlay_exit_capture),
                        onSecondClick = onExit,
                        secondTag = "overlay-exit",
                    )
                }
            }

            item(key = "capture-header") {
                SettingsSectionHeader(
                    title = stringResource(R.string.overlay_capture_settings),
                    modifier = Modifier
                        .alpha(regularContentAlpha)
                        .testTag("overlay-capture-header"),
                )
            }
            item(key = "direction-label") {
                Text(
                    text = stringResource(R.string.overlay_scroll_direction),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .alpha(regularContentAlpha)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item(key = "direction-control") {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(regularContentAlpha)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    ScrollDirection.entries.forEachIndexed { index, direction ->
                        SegmentedButton(
                            selected = template.scrollDirection == direction,
                            onClick = { onDirectionSelected(direction) },
                            shape = SegmentedButtonDefaults.itemShape(index, ScrollDirection.entries.size),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("overlay-direction-${direction.name}"),
                        ) {
                            Text(direction.shortLabel())
                        }
                    }
                }
            }

            item(key = "capture-timing") {
                TemplateTimingControls(
                    swipeDurationMs = template.gestureDurationMs,
                    stableTimeMs = template.gestureStableTimeMs,
                    postGestureWaitEnabled = template.postGestureWaitEnabled,
                    postGestureWaitMs = template.postGestureWaitMs,
                    minimumIntervalMs = template.waitAfterGestureMs,
                    onSwipeDurationChanged = onGestureDurationChanged,
                    onStableTimeChanged = onStableTimeChanged,
                    onPostGestureWaitEnabledChanged = onPostGestureWaitEnabledChanged,
                    onPostGestureWaitChanged = onPostGestureWaitChanged,
                    onMinimumIntervalChanged = onWaitChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(regularContentAlpha),
                    swipeDurationModifier = Modifier.testTag("overlay-swipe-duration-slider"),
                    stableTimeModifier = Modifier.testTag("overlay-stable-time-slider"),
                    postGestureWaitModifier = Modifier.testTag("overlay-post-gesture-wait-slider"),
                    postGestureWaitSwitchModifier = Modifier.testTag("overlay-post-gesture-wait-switch"),
                    minimumIntervalModifier = Modifier.testTag("overlay-wait-slider"),
                )
            }

            item(key = "opacity-header") {
                SettingsSectionHeader(
                    title = stringResource(R.string.overlay_opacity_settings),
                    modifier = Modifier
                        .alpha(regularContentAlpha)
                        .testTag("overlay-opacity-header"),
                )
            }
            item(key = "panel-opacity") {
                SettingsSliderItem(
                    title = stringResource(R.string.overlay_panel),
                    value = normalizeOverlayOpacity(panelOpacity) * 100f,
                    range = MIN_OVERLAY_OPACITY * 100f..MAX_OVERLAY_OPACITY * 100f,
                    steps = 0,
                    onValueChange = { onPanelOpacityChanged(it / 100f) },
                    onValueChangeImmediate = { onPanelOpacityChanging(it / 100f) },
                    valueFormatter = { "${it.roundToInt()}%" },
                    description = null,
                    modifier = Modifier
                        .alpha(regularContentAlpha)
                        .testTag("overlay-panel-opacity-slider"),
                )
            }
            item(key = "non-focused-region-opacity") {
                SettingsSliderItem(
                    title = stringResource(R.string.overlay_non_focused_region_box),
                    value = normalizeOverlayOpacity(nonFocusedRegionOpacity) * 100f,
                    range = MIN_OVERLAY_OPACITY * 100f..MAX_OVERLAY_OPACITY * 100f,
                    steps = 0,
                    onValueChange = { onNonFocusedRegionOpacityChanged(it / 100f) },
                    onValueChangeImmediate = {
                        onNonFocusedRegionOpacityChanging(it / 100f)
                    },
                    onDragStateChange = { dragging ->
                        previewingNonFocusedOpacity = dragging
                        onNonFocusedOpacityPreviewChanged(dragging)
                    },
                    valueFormatter = { "${it.roundToInt()}%" },
                    description = null,
                    detailsAlpha = nonFocusedDetailsAlpha,
                    modifier = Modifier
                        .alpha(nonFocusedSliderAlpha)
                        .testTag("overlay-non-focused-opacity-slider"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverlayTemplateSelector(
    template: GameCaptureTemplate,
    templates: List<GameCaptureTemplate>,
    packageFilter: String,
    templatesLoading: Boolean,
    includeEditingDraft: Boolean,
    contentAlpha: Float,
    onTemplateSelected: (GameCaptureTemplate) -> Unit,
    onNewTemplate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var lastDismissedAtMs by remember { mutableStateOf(0L) }
    val choices = remember(template, templates, packageFilter, includeEditingDraft) {
        groupOverlayTemplateChoices(
            template,
            templates,
            packageFilter,
            includeEditingDraft = includeEditingDraft,
        )
    }
    val selectedInOtherApplications = choices.otherApplications.any { choice ->
        sameTemplate(choice, template)
    }
    var showingOtherApplications by remember(template, choices) {
        mutableStateOf(selectedInOtherApplications)
    }
    val selectedTemplate = choices.all.any { choice ->
        sameTemplate(choice, template)
    }
    val unnamedTemplate = stringResource(R.string.overlay_unnamed_template)
    val selectTemplateDescription = stringResource(R.string.overlay_select_template)
    val title = when {
        templatesLoading -> stringResource(R.string.overlay_templates_loading)
        selectedTemplate -> template.templateName.ifBlank { unnamedTemplate }
        else -> stringResource(R.string.overlay_select_or_save_template)
    }
    fun dismissMenu() {
        expanded = false
        showingOtherApplications = selectedInOtherApplications
        lastDismissedAtMs = SystemClock.uptimeMillis()
    }
    BoxWithConstraints(modifier = modifier) {
        val menuWidth = maxWidth
        OutlinedButton(
            enabled = !templatesLoading,
            onClick = {
                val now = SystemClock.uptimeMillis()
                when {
                    expanded -> dismissMenu()
                    lastDismissedAtMs == 0L ||
                        now - lastDismissedAtMs >= DropdownDismissGuardMillis -> {
                        showingOtherApplications = selectedInOtherApplications
                        expanded = true
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(OverlayActionHeight)
                .semantics { contentDescription = selectTemplateDescription }
                .testTag("overlay-template-selector"),
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ExposedDropdownMenuDefaults.TrailingIcon(
                expanded = expanded && !templatesLoading,
            )
        }
        DropdownMenu(
            expanded = expanded && !templatesLoading,
            onDismissRequest = ::dismissMenu,
            modifier = Modifier
                .width(menuWidth)
                .offset(y = -DropdownMenuTopPaddingCorrection)
                .alpha(contentAlpha.coerceIn(0f, 1f)),
            properties = PopupProperties(focusable = false),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraSmall,
                shadowElevation = 8.dp,
            ) {
                AnimatedContent(
                    targetState = showingOtherApplications,
                    transitionSpec = {
                        if (targetState) {
                            slideInHorizontally(
                                animationSpec = tween(TemplatePageSlideDurationMillis),
                                initialOffsetX = { width -> width },
                            ) togetherWith slideOutHorizontally(
                                animationSpec = tween(TemplatePageSlideDurationMillis),
                                targetOffsetX = { width -> -width },
                            )
                        } else {
                            slideInHorizontally(
                                animationSpec = tween(TemplatePageSlideDurationMillis),
                                initialOffsetX = { width -> -width },
                            ) togetherWith slideOutHorizontally(
                                animationSpec = tween(TemplatePageSlideDurationMillis),
                                targetOffsetX = { width -> width },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = "模板列表页面切换",
                ) { showOtherApplications ->
                    if (showOtherApplications) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("overlay-template-page-other"),
                        ) {
                            TemplateChoiceGroup(
                                title = stringResource(R.string.overlay_other_app_templates),
                                emptyLabel = stringResource(R.string.overlay_no_other_app_templates),
                                choices = choices.otherApplications,
                                selected = template,
                                showPackageName = true,
                                headerAction = TemplateMenuHeaderAction.BACK,
                                onHeaderAction = { showingOtherApplications = false },
                                onTemplateSelected = {
                                    dismissMenu()
                                    onTemplateSelected(it)
                                },
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("overlay-template-page-current"),
                        ) {
                            TemplateChoiceGroup(
                                title = stringResource(R.string.overlay_current_app_templates),
                                emptyLabel = stringResource(R.string.overlay_no_current_app_templates),
                                choices = choices.currentApplication,
                                selected = template,
                                headerAction = TemplateMenuHeaderAction.MORE,
                                onHeaderAction = { showingOtherApplications = true },
                                onNewTemplate = {
                                    onNewTemplate()
                                    dismissMenu()
                                },
                                onTemplateSelected = {
                                    dismissMenu()
                                    onTemplateSelected(it)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TemplateChoiceGroup(
    title: String,
    emptyLabel: String,
    choices: List<GameCaptureTemplate>,
    selected: GameCaptureTemplate,
    showPackageName: Boolean = false,
    headerAction: TemplateMenuHeaderAction,
    onHeaderAction: () -> Unit,
    onNewTemplate: (() -> Unit)? = null,
    onTemplateSelected: (GameCaptureTemplate) -> Unit,
) {
    val temporaryConfiguration = stringResource(R.string.overlay_temporary_configuration)
    val packageNotRecorded = stringResource(R.string.overlay_package_not_recorded)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CompactTemplateMenuHeaderHeight)
            .testTag("overlay-template-header"),
        contentAlignment = Alignment.Center,
    ) {
        CompactTemplateMenuText(
            text = title,
            color = MaterialTheme.colorScheme.primary,
        )
        onNewTemplate?.let { createNew ->
            TextButton(
                onClick = createNew,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .height(CompactTemplateMenuHeaderHeight)
                    .testTag("overlay-template-new"),
            ) {
                CompactTemplateMenuText(
                    text = stringResource(R.string.overlay_new),
                    color = LocalContentColor.current,
                )
            }
        }
        TemplateMenuHeaderButton(
            action = headerAction,
            onClick = onHeaderAction,
            modifier = Modifier.align(
                if (headerAction == TemplateMenuHeaderAction.BACK) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ),
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.testTag("overlay-template-header-divider"),
    )
    if (choices.isEmpty()) {
        DropdownMenuItem(
            text = {
                Text(
                    text = emptyLabel,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            },
            modifier = Modifier.height(CompactTemplateMenuRowHeight),
            contentPadding = CompactTemplateMenuPadding,
            enabled = false,
            onClick = {},
        )
    }
    choices.forEach { choice ->
        val isSelected = sameTemplate(choice, selected)
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        text = choice.templateName.ifBlank { temporaryConfiguration },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showPackageName) {
                        Text(
                            text = choice.packageName.ifBlank { packageNotRecorded },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            leadingIcon = if (isSelected) {
                { Icon(Icons.Default.Check, contentDescription = null) }
            } else {
                null
            },
            onClick = { if (!isSelected) onTemplateSelected(choice) },
        )
    }
}

@Composable
private fun TemplateMenuHeaderButton(
    action: TemplateMenuHeaderAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
        modifier = modifier
            .height(CompactTemplateMenuHeaderHeight)
            .testTag(
                if (action == TemplateMenuHeaderAction.BACK) {
                    "overlay-template-back"
                } else {
                    "overlay-template-more"
                },
            ),
    ) {
        if (action == TemplateMenuHeaderAction.BACK) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            CompactTemplateMenuText(
                text = stringResource(R.string.overlay_back),
                color = LocalContentColor.current,
            )
        } else {
            CompactTemplateMenuText(
                text = stringResource(R.string.overlay_more),
                color = LocalContentColor.current,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun CompactTemplateMenuText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private enum class TemplateMenuHeaderAction { BACK, MORE }

private val CompactTemplateMenuHeaderHeight = 40.dp
private val CompactTemplateMenuRowHeight = 36.dp
private val CompactTemplateMenuPadding = PaddingValues(horizontal = 12.dp)
private val DropdownMenuTopPaddingCorrection = 8.dp
private val OverlayActionHeight = AppButtonHeight
private const val DropdownDismissGuardMillis = 240L
private const val OverlayOpacityFadeDurationMillis = 200
private const val TemplatePageSlideDurationMillis = 220

@Composable
private fun OverlayActionRow(
    firstLabel: String,
    onFirstClick: () -> Unit,
    firstTag: String,
    secondLabel: String,
    onSecondClick: () -> Unit,
    secondTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onFirstClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier
                .weight(1f)
                .height(OverlayActionHeight)
                .testTag(firstTag),
        ) {
            Text(firstLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        OutlinedButton(
            onClick = onSecondClick,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier
                .weight(1f)
                .height(OverlayActionHeight)
                .testTag(secondTag),
        ) {
            Text(secondLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

private fun sameTemplate(first: GameCaptureTemplate, second: GameCaptureTemplate): Boolean =
    first === second || (first.id != 0L && first.id == second.id)

@Composable
private fun ScrollDirection.shortLabel(): String = stringResource(
    when (this) {
        ScrollDirection.UP -> R.string.overlay_direction_up_short
        ScrollDirection.DOWN -> R.string.overlay_direction_down_short
        ScrollDirection.LEFT -> R.string.overlay_direction_left_short
        ScrollDirection.RIGHT -> R.string.overlay_direction_right_short
    },
)
