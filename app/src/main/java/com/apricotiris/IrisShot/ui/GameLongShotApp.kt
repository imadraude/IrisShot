package com.apricotiris.IrisShot.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.result.ResultListRoute
import com.apricotiris.IrisShot.ui.theme.GameLongShotTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun GameLongShotApp(
    permissions: PermissionUiState,
    service: CaptureServiceUiState,
    templates: List<TemplateUiModel>,
    settings: CaptureSettingsUiState,
    onProjection: () -> Unit,
    onProjectionPermission: () -> Unit = onProjection,
    onOverlayPermission: () -> Unit,
    onAccessibilityPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onShizukuModeChange: (Boolean) -> Unit,
    onOpenOverlay: () -> Unit,
    onStopCapture: () -> Unit,
    onRenameTemplate: (TemplateUiModel, String) -> Unit,
    onDuplicateTemplate: (TemplateUiModel) -> Unit,
    onDeleteTemplate: (TemplateUiModel) -> Unit,
    onDeleteTemplates: (Set<Long>) -> Unit,
    onNewTemplate: () -> Unit,
    onEditTemplate: (TemplateUiModel) -> Unit,
    onSettingsChange: (CaptureSettingsUiState) -> Unit,
    onSelectCacheDirectory: () -> Unit,
    onSelectExportDirectory: () -> Unit,
    onResetCacheDirectory: () -> Unit,
    onResetExportDirectory: () -> Unit,
    onOpenGithub: () -> Unit,
    languageTransitionPhase: LanguageTransitionPhase = LanguageTransitionPhase.IDLE,
    languageContentFadeGeneration: Int = 0,
    onLanguageTransitionCovered: () -> Unit = {},
    onLanguageTransitionFinished: () -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val appPreferences = remember(context) { AppPreferences(context) }
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val showRail = configuration.screenWidthDp >= 840
    val compactContent = configuration.screenWidthDp < 1_080
    val destinations = AppDestination.entries
    val destinationState = rememberSaveable { mutableIntStateOf(AppDestination.HOME.ordinal) }
    var resultSelectionActions by remember { mutableStateOf<ContentSelectionActions?>(null) }
    var templateSelectionActions by remember { mutableStateOf<ContentSelectionActions?>(null) }
    var resultSortOrder by rememberSaveable {
        mutableStateOf(appPreferences.loadResultSortOrder())
    }
    var templateSortOrder by rememberSaveable {
        mutableStateOf(appPreferences.loadTemplateSortOrder())
    }
    val updateResultSelectionActions = remember {
        { actions: ContentSelectionActions? -> resultSelectionActions = actions }
    }
    val updateTemplateSelectionActions = remember {
        { actions: ContentSelectionActions? -> templateSelectionActions = actions }
    }
    if (destinationState.intValue !in destinations.indices) {
        destinationState.intValue = AppDestination.HOME.ordinal
    }
    val selectDestination = remember(destinationState, focusManager, inputModeManager) {
        { selected: AppDestination ->
            if (destinationState.intValue != selected.ordinal) {
                if (inputModeManager.inputMode == InputMode.Touch) {
                    focusManager.clearFocus(force = true)
                }
                destinationState.intValue = selected.ordinal
            }
        }
    }
    val currentDestinationIndex = remember(destinationState) {
        { destinationState.intValue }
    }

    GameLongShotTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
    ) {
        val languageContentAlpha = rememberLanguageContentAlpha(
            generation = languageContentFadeGeneration,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = languageContentAlpha }
                    .then(
                        if (!languageTransitionPhase.usesLegacyCover) Modifier
                        else Modifier.clearAndSetSemantics {},
                    ),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    DestinationTopBar(
                        selectedOrdinal = destinationState,
                        onNewTemplate = onNewTemplate,
                        resultSelectionActions = resultSelectionActions,
                        templateSelectionActions = templateSelectionActions,
                        resultSortOrder = resultSortOrder,
                        templateSortOrder = templateSortOrder,
                        onResultSortOrderChange = { value ->
                            resultSortOrder = value
                            appPreferences.saveResultSortOrder(value)
                        },
                        onTemplateSortOrderChange = { value ->
                            templateSortOrder = value
                            appPreferences.saveTemplateSortOrder(value)
                        },
                    )
                },
                bottomBar = {
                    if (!showRail) {
                        DestinationBottomBar(destinationState, selectDestination)
                    }
                }
            ) { paddingValues ->
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    if (showRail) {
                        DestinationRail(destinationState, selectDestination)
                    }
                    RetainedDestinationLayout(
                        selectedIndex = destinationState.intValue,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) {
                        destinations.forEach { page ->
                            key(page) {
                                val active = destinationState.intValue == page.ordinal
                                val isActive = remember(page, currentDestinationIndex) {
                                    { currentDestinationIndex() == page.ordinal }
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(
                                            if (active) Modifier
                                            else Modifier.clearAndSetSemantics {},
                                        ),
                                ) {
                                    DestinationContent(
                                        destination = page,
                                        isActive = isActive,
                                        permissions = permissions,
                                        service = service,
                                        templates = templates,
                                        settings = settings,
                                        compact = compactContent || !showRail,
                                        onProjection = onProjection,
                                        onProjectionPermission = onProjectionPermission,
                                        onOverlayPermission = onOverlayPermission,
                                        onAccessibilityPermission = onAccessibilityPermission,
                                        onNotificationPermission = onNotificationPermission,
                                        onShizukuModeChange = onShizukuModeChange,
                                        onOpenOverlay = onOpenOverlay,
                                        onStopCapture = onStopCapture,
                                        onRenameTemplate = onRenameTemplate,
                                        onDuplicateTemplate = onDuplicateTemplate,
                                        onDeleteTemplate = onDeleteTemplate,
                                        onDeleteTemplates = onDeleteTemplates,
                                        onNewTemplate = onNewTemplate,
                                        onEditTemplate = onEditTemplate,
                                        onResultSelectionActionsChange = updateResultSelectionActions,
                                        onTemplateSelectionActionsChange = updateTemplateSelectionActions,
                                        resultSortOrder = resultSortOrder,
                                        templateSortOrder = templateSortOrder,
                                        onResultSortOrderChange = { value ->
                                            resultSortOrder = value
                                            appPreferences.saveResultSortOrder(value)
                                        },
                                        onSettingsChange = onSettingsChange,
                                        onSelectCacheDirectory = onSelectCacheDirectory,
                                        onSelectExportDirectory = onSelectExportDirectory,
                                        onResetCacheDirectory = onResetCacheDirectory,
                                        onResetExportDirectory = onResetExportDirectory,
                                        onOpenGithub = onOpenGithub,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            LegacyLanguageTransitionOverlay(
                phase = languageTransitionPhase,
                onCovered = onLanguageTransitionCovered,
                onFinished = onLanguageTransitionFinished,
            )
        }
    }
}

enum class LanguageTransitionPhase {
    IDLE,
    LEGACY_FADE_OUT,
    LEGACY_FADE_IN,
}

private val LanguageTransitionPhase.usesLegacyCover: Boolean
    get() = this == LanguageTransitionPhase.LEGACY_FADE_OUT ||
        this == LanguageTransitionPhase.LEGACY_FADE_IN

@Composable
private fun rememberLanguageContentAlpha(
    generation: Int,
): Float {
    val alpha = remember(generation) {
        Animatable(
            if (generation > 0) {
                LANGUAGE_CONTENT_FADE_START_ALPHA
            } else {
                1f
            },
        )
    }

    LaunchedEffect(generation) {
        if (generation > 0) {
            withFrameNanos { }
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = LANGUAGE_CONTENT_FADE_IN_DURATION_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }
    return alpha.value
}

@Composable
private fun LegacyLanguageTransitionOverlay(
    phase: LanguageTransitionPhase,
    onCovered: () -> Unit,
    onFinished: () -> Unit,
) {
    val alpha = remember {
        Animatable(if (phase == LanguageTransitionPhase.LEGACY_FADE_IN) 1f else 0f)
    }
    val currentOnCovered by rememberUpdatedState(onCovered)
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(phase) {
        when (phase) {
            LanguageTransitionPhase.LEGACY_FADE_OUT -> {
                alpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = LANGUAGE_FADE_OUT_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
                currentOnCovered()
            }
            LanguageTransitionPhase.LEGACY_FADE_IN -> {
                alpha.snapTo(1f)
                withFrameNanos { }
                alpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = LANGUAGE_FADE_IN_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
                currentOnFinished()
            }
            LanguageTransitionPhase.IDLE -> alpha.snapTo(0f)
        }
    }

    if (phase.usesLegacyCover) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = alpha.value))
                .clearAndSetSemantics {}
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        )
    }
}

private const val LANGUAGE_FADE_OUT_DURATION_MS = 150
private const val LANGUAGE_FADE_IN_DURATION_MS = 180
private const val LANGUAGE_CONTENT_FADE_IN_DURATION_MS = 160
private const val LANGUAGE_CONTENT_FADE_START_ALPHA = 0.92f

@Composable
private fun RetainedDestinationLayout(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val pageCount = AppDestination.entries.size
    val pages = remember(pageCount) {
        List(pageCount) { index ->
            DestinationAnimationState(
                initialAlpha = if (index == selectedIndex) 1f else 0f,
                initialOffset = destinationOffset(index, selectedIndex),
            )
        }
    }
    var transitionTarget by remember { mutableIntStateOf(-1) }
    var transitionGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedIndex) {
        val alreadySettled = pages.indices.all { index ->
            val targetAlpha = if (index == selectedIndex) 1f else 0f
            pages[index].alpha.value == targetAlpha &&
                pages[index].offset.value == destinationOffset(index, selectedIndex)
        }
        if (alreadySettled) return@LaunchedEffect

        transitionTarget = selectedIndex
        transitionGeneration += 1
        val transitionOwner = transitionGeneration
        try {
            coroutineScope {
                pages.forEachIndexed { index, page ->
                    val selected = index == selectedIndex
                    launch {
                        page.alpha.animateTo(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = if (selected) {
                                    DESTINATION_FADE_IN_DURATION_MS
                                } else {
                                    DESTINATION_FADE_OUT_DURATION_MS
                                },
                                delayMillis = if (selected) DESTINATION_FADE_IN_DELAY_MS else 0,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                    launch {
                        page.offset.animateTo(
                            targetValue = destinationOffset(index, selectedIndex),
                            animationSpec = tween(
                                durationMillis = DESTINATION_ANIMATION_DURATION_MS,
                                easing = FastOutSlowInEasing,
                            ),
                        )
                    }
                }
            }
        } finally {
            if (transitionGeneration == transitionOwner) transitionTarget = -1
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val slideDistancePx = with(density) { DESTINATION_SLIDE_DISTANCE.roundToPx() }
    val measurePolicy = remember(pages, selectedIndex, slideDistancePx) {
        MeasurePolicy { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val visiblePages = pages.indices.filter { index ->
                    index < placeables.size &&
                        (index == selectedIndex || pages[index].alpha.value > MIN_VISIBLE_ALPHA)
                }
                val drawOrder = visiblePages.filterNot { it == selectedIndex } +
                    visiblePages.filter { it == selectedIndex }

                drawOrder.forEach { index ->
                    placeables[index].placeRelativeWithLayer(
                        x = (pages[index].offset.value * slideDistancePx).roundToInt(),
                        y = 0,
                    ) {
                        alpha = pages[index].alpha.value.coerceIn(0f, 1f)
                    }
                }
            }
        }
    }
    Box(modifier = modifier.clipToBounds()) {
        Layout(
            content = content,
            modifier = Modifier.matchParentSize(),
            measurePolicy = measurePolicy,
        )
        if (transitionTarget >= 0) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clearAndSetSemantics {}
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                    it.consume()
                                }
                            }
                        }
                    },
            )
        }
    }
}

private const val DESTINATION_ANIMATION_DURATION_MS = 300
private const val DESTINATION_FADE_IN_DURATION_MS = 240
private const val DESTINATION_FADE_IN_DELAY_MS = 40
private const val DESTINATION_FADE_OUT_DURATION_MS = 160
private const val MIN_VISIBLE_ALPHA = 0.001f
private val DESTINATION_SLIDE_DISTANCE = 24.dp

private class DestinationAnimationState(
    initialAlpha: Float,
    initialOffset: Float,
) {
    val alpha = Animatable(initialAlpha)
    val offset = Animatable(initialOffset)
}

private fun destinationOffset(index: Int, selectedIndex: Int): Float = when {
    index < selectedIndex -> -1f
    index > selectedIndex -> 1f
    else -> 0f
}

@Composable
private fun DestinationTopBar(
    selectedOrdinal: State<Int>,
    onNewTemplate: () -> Unit,
    resultSelectionActions: ContentSelectionActions?,
    templateSelectionActions: ContentSelectionActions?,
    resultSortOrder: ContentSortOrder,
    templateSortOrder: ContentSortOrder,
    onResultSortOrderChange: (ContentSortOrder) -> Unit,
    onTemplateSortOrderChange: (ContentSortOrder) -> Unit,
) {
    val selected = AppDestination.entries.getOrElse(selectedOrdinal.value) { AppDestination.HOME }
    var sortSheetDestination by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    val selectionActions = when (selected) {
        AppDestination.RESULTS -> resultSelectionActions
        AppDestination.TEMPLATES -> templateSelectionActions
        else -> null
    }
    val selectionMode = selectionActions != null
    var retainedSelectionActions by remember { mutableStateOf<ContentSelectionActions?>(null) }
    val transitionSelectionActions = selectionActions ?: retainedSelectionActions
    SideEffect {
        if (selectionActions != null) retainedSelectionActions = selectionActions
    }

    LaunchedEffect(selected) {
        if (sortSheetDestination != selected) sortSheetDestination = null
    }

    Crossfade(
        targetState = selectionMode,
        animationSpec = tween(durationMillis = 180),
        label = "destination selection top bar",
    ) { showSelectionTopBar ->
        if (showSelectionTopBar) {
            transitionSelectionActions?.let { ContentSelectionTopBar(it) }
        } else {
            AppTopBar(
                title = if (selected == AppDestination.HOME) {
                    stringResource(R.string.app_name)
                } else {
                    stringResource(selected.labelRes)
                },
                actions = {
                    if (selected == AppDestination.TEMPLATES) {
                        AppTooltipIconButton(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.new_template),
                            onClick = onNewTemplate,
                        )
                    }
                    if (selected == AppDestination.RESULTS || selected == AppDestination.TEMPLATES) {
                        AppTooltipIconButton(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(R.string.sort_order),
                            tooltipText = stringResource(R.string.sort_order_tooltip),
                            onClick = { sortSheetDestination = selected },
                        )
                    }
                },
            )
        }
    }

    sortSheetDestination?.let { destination ->
        val currentOrder = if (destination == AppDestination.RESULTS) {
            resultSortOrder
        } else {
            templateSortOrder
        }
        SortOrderSheet(
            selected = currentOrder,
            options = if (destination == AppDestination.RESULTS) {
                ContentSortOrder.resultOptions
            } else {
                ContentSortOrder.templateOptions
            },
            onSelect = { value ->
                if (destination == AppDestination.RESULTS) {
                    onResultSortOrderChange(value)
                } else {
                    onTemplateSortOrderChange(value)
                }
                sortSheetDestination = null
            },
            onDismiss = { sortSheetDestination = null },
        )
    }
}

@Composable
private fun DestinationBottomBar(
    selectedOrdinal: State<Int>,
    onSelected: (AppDestination) -> Unit,
) {
    val selected = AppDestination.entries.getOrElse(selectedOrdinal.value) { AppDestination.HOME }
    MainBottomNavigation(selected, onSelected)
}

@Composable
private fun DestinationRail(
    selectedOrdinal: State<Int>,
    onSelected: (AppDestination) -> Unit,
) {
    val selected = AppDestination.entries.getOrElse(selectedOrdinal.value) { AppDestination.HOME }
    AppNavigationRail(
        selected = selected,
        onSelected = onSelected,
        modifier = Modifier.fillMaxHeight(),
    )
}

@Composable
private fun DestinationContent(
    destination: AppDestination,
    isActive: () -> Boolean,
    permissions: PermissionUiState,
    service: CaptureServiceUiState,
    templates: List<TemplateUiModel>,
    settings: CaptureSettingsUiState,
    compact: Boolean,
    onProjection: () -> Unit,
    onProjectionPermission: () -> Unit,
    onOverlayPermission: () -> Unit,
    onAccessibilityPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onShizukuModeChange: (Boolean) -> Unit,
    onOpenOverlay: () -> Unit,
    onStopCapture: () -> Unit,
    onRenameTemplate: (TemplateUiModel, String) -> Unit,
    onDuplicateTemplate: (TemplateUiModel) -> Unit,
    onDeleteTemplate: (TemplateUiModel) -> Unit,
    onDeleteTemplates: (Set<Long>) -> Unit,
    onNewTemplate: () -> Unit,
    onEditTemplate: (TemplateUiModel) -> Unit,
    onResultSelectionActionsChange: (ContentSelectionActions?) -> Unit,
    onTemplateSelectionActionsChange: (ContentSelectionActions?) -> Unit,
    resultSortOrder: ContentSortOrder,
    templateSortOrder: ContentSortOrder,
    onResultSortOrderChange: (ContentSortOrder) -> Unit,
    onSettingsChange: (CaptureSettingsUiState) -> Unit,
    onSelectCacheDirectory: () -> Unit,
    onSelectExportDirectory: () -> Unit,
    onResetCacheDirectory: () -> Unit,
    onResetExportDirectory: () -> Unit,
    onOpenGithub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (destination) {
        AppDestination.HOME -> HomeScreen(
                isActive = isActive,
                permissions = permissions,
                service = service,
                compact = compact,
                onProjection = onProjection,
                onProjectionPermission = onProjectionPermission,
                onOverlay = onOverlayPermission,
                onAccessibility = onAccessibilityPermission,
                onNotification = onNotificationPermission,
                onShizukuModeChange = onShizukuModeChange,
                onOpenOverlay = onOpenOverlay,
                onStop = onStopCapture,
                modifier = modifier.fillMaxSize(),
            )
        AppDestination.RESULTS -> ResultListRoute(
                isActive = isActive,
                refreshKey = service.resultSessionId,
                onSelectionActionsChange = onResultSelectionActionsChange,
                sortOrder = resultSortOrder,
                onSortOrderChange = onResultSortOrderChange,
                modifier = modifier.fillMaxSize(),
            )
        AppDestination.TEMPLATES -> TemplateScreen(
                isActive = isActive,
                templates = templates,
                onRename = onRenameTemplate,
                onDuplicate = onDuplicateTemplate,
                onDelete = onDeleteTemplate,
                onDeleteSelected = onDeleteTemplates,
                onNewTemplate = onNewTemplate,
                onEdit = onEditTemplate,
                onSelectionActionsChange = onTemplateSelectionActionsChange,
                sortOrder = templateSortOrder,
                modifier = modifier.fillMaxSize(),
            )
        AppDestination.SETTINGS -> SettingsScreen(
                isActive = isActive,
                settings = settings,
                onChange = onSettingsChange,
                onSelectCacheDirectory = onSelectCacheDirectory,
                onSelectExportDirectory = onSelectExportDirectory,
                onResetCacheDirectory = onResetCacheDirectory,
                onResetExportDirectory = onResetExportDirectory,
                onOpenGithub = onOpenGithub,
                modifier = modifier.fillMaxSize(),
            )
    }
}
