package com.apricotiris.IrisShot.template

import android.graphics.Bitmap
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint as AndroidPaint
import android.graphics.RectF as AndroidRectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.FixedAnchorPosition
import com.apricotiris.IrisShot.core.model.FixedLayerConfig
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.ScreenOrientation
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.overlay.placeGestureDistanceLabelGeometry
import com.apricotiris.IrisShot.ui.AppTopBar
import com.apricotiris.IrisShot.ui.AppButtonHeight
import com.apricotiris.IrisShot.ui.DEFAULT_NON_FOCUSED_REGION_OPACITY
import com.apricotiris.IrisShot.ui.TemplateTimingControls
import com.apricotiris.IrisShot.ui.normalizeOverlayOpacity
import com.apricotiris.IrisShot.ui.theme.GameLongShotTheme
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class TemplateEditorScreenState(
    val template: GameCaptureTemplate,
    val referenceBitmap: Bitmap? = null,
    val hasReference: Boolean = false,
    val isNew: Boolean = false,
    val isSaving: Boolean = false,
    val statusMessage: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TemplateEditorScreen(
    state: TemplateEditorScreenState,
    onBack: () -> Unit,
    onTemplateChange: (GameCaptureTemplate) -> Unit,
    onPickReference: () -> Unit,
    onPickApplication: () -> Unit,
    onSave: () -> Unit,
    onStatusConsumed: () -> Unit,
    nonFocusedRegionOpacity: Float = DEFAULT_NON_FOCUSED_REGION_OPACITY,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    state.statusMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            onStatusConsumed()
        }
    }
    val saveEnabled = state.template.templateName.isNotBlank() &&
        state.template.packageName.isNotBlank() &&
        !state.isSaving
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopBar(
                title = stringResource(
                    if (state.isNew) R.string.template_editor_new_title
                    else R.string.template_editor_edit_title,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.template_editor_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = saveEnabled) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                stringResource(R.string.template_editor_save),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { contentPadding ->
        TemplateEditorContent(
            state = state,
            onTemplateChange = onTemplateChange,
            onPickReference = onPickReference,
            onPickApplication = onPickApplication,
            nonFocusedRegionOpacity = nonFocusedRegionOpacity,
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun TemplateEditorContent(
    state: TemplateEditorScreenState,
    onTemplateChange: (GameCaptureTemplate) -> Unit,
    onPickReference: () -> Unit,
    onPickApplication: () -> Unit,
    nonFocusedRegionOpacity: Float,
    contentPadding: PaddingValues,
) {
    var selectedTarget by remember { mutableStateOf(TemplateEditTarget.CAPTURE) }
    var referenceVisible by remember(state.referenceBitmap) { mutableStateOf(true) }
    val editorTemplate = remember(state.template) { normalizeTemplateForEditor(state.template) }
    val availableTargets = templateEditTargets(editorTemplate)
    val activeTarget = validTemplateEditTarget(editorTemplate, selectedTarget)
    LaunchedEffect(editorTemplate) {
        if (editorTemplate != state.template) onTemplateChange(editorTemplate)
    }
    LaunchedEffect(activeTarget) {
        if (selectedTarget != activeTarget) selectedTarget = activeTarget
    }
    val editorState = if (editorTemplate == state.template) {
        state
    } else {
        state.copy(template = editorTemplate)
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        val wideLayout = maxWidth >= 720.dp
        if (wideLayout) {
            Row(Modifier.fillMaxSize()) {
                TemplateCanvasPane(
                    template = editorTemplate,
                    bitmap = state.referenceBitmap.takeIf { referenceVisible },
                    selectedTarget = activeTarget,
                    nonFocusedRegionOpacity = nonFocusedRegionOpacity,
                    onTemplateChange = onTemplateChange,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, top = 12.dp, bottom = 16.dp),
                )
                TemplateEditorControls(
                    state = editorState,
                    selectedTarget = activeTarget,
                    availableTargets = availableTargets,
                    onSelectedTargetChange = { selectedTarget = it },
                    onTemplateChange = onTemplateChange,
                    onPickReference = onPickReference,
                    referenceVisible = referenceVisible,
                    onReferenceVisibilityChange = { referenceVisible = it },
                    onPickApplication = onPickApplication,
                    modifier = Modifier
                        .widthIn(min = 300.dp, max = 360.dp)
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                TemplateCanvasPane(
                    template = editorTemplate,
                    bitmap = state.referenceBitmap.takeIf { referenceVisible },
                    selectedTarget = activeTarget,
                    nonFocusedRegionOpacity = nonFocusedRegionOpacity,
                    onTemplateChange = onTemplateChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp, max = 560.dp)
                        .height(540.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
                TemplateEditorControls(
                    state = editorState,
                    selectedTarget = activeTarget,
                    availableTargets = availableTargets,
                    onSelectedTargetChange = { selectedTarget = it },
                    onTemplateChange = onTemplateChange,
                    onPickReference = onPickReference,
                    referenceVisible = referenceVisible,
                    onReferenceVisibilityChange = { referenceVisible = it },
                    onPickApplication = onPickApplication,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun TemplateCanvasPane(
    template: GameCaptureTemplate,
    bitmap: Bitmap?,
    selectedTarget: TemplateEditTarget,
    nonFocusedRegionOpacity: Float,
    onTemplateChange: (GameCaptureTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val regionColors = templateEditorRegionColors(darkTheme)
    val labelBackgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val nonFocusedAlpha = normalizeOverlayOpacity(nonFocusedRegionOpacity)
    val captureColor = regionColors.capture
    val stitchColor = regionColors.stitch
    val gestureColor = regionColors.gesture
    val previewColor = regionColors.preview
    val fixedLayerColors = listOf(regionColors.fixed1, regionColors.fixed2)
    fun fixedLayerColor(index: Int): Color = fixedLayerColors.getOrElse(index) {
        fixedLayerColors.last()
    }
    val bitmapPaint = remember {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
    }
    val captureRegionLabel = stringResource(R.string.template_editor_capture_region)
    val stitchRegionLabel = stringResource(R.string.template_editor_stitch_region)
    val previewRegionLabel = stringResource(R.string.template_editor_preview_region)
    val repeatRegionLabel = stringResource(R.string.template_editor_repeat_region)
    val fixedSideShortLabels = FixedSide.entries.associateWith { fixedSideShortLabel(it) }
    val fixedSideRegionLabels = FixedSide.entries.associateWith { fixedSideRegionLabel(it) }
    val fixedSideRepeatLabels = FixedSide.entries.associateWith { side ->
        stringResource(
            R.string.template_editor_side_repeat_region,
            fixedSideRegionLabels.getValue(side),
        )
    }
    val anchorBlockLabels = buildMap {
        listOf(true, false).forEach { vertical ->
            FixedAnchorPosition.entries.forEach { position ->
                put(
                    vertical to position,
                    fixedAnchorBlockLabel(position = position, vertical = vertical),
                )
            }
        }
    }
    val fixedAnchorBlockLabels = buildMap {
        FixedSide.entries.forEach { side ->
            FixedAnchorPosition.entries.forEach { position ->
                val block = anchorBlockLabels.getValue(
                    fixedBandRunsVertically(side) to position,
                )
                put(
                    side to position,
                    stringResource(
                        R.string.template_editor_side_anchor_block,
                        fixedSideShortLabels.getValue(side),
                        block,
                    ),
                )
            }
        }
    }
    val density = LocalDensity.current
    val gestureDistanceTextPaint = remember(density.density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f * density.density
        }
    }
    val currentTemplate = rememberUpdatedState(template)
    val currentChange = rememberUpdatedState(onTemplateChange)
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .background(surfaceColor, RoundedCornerShape(8.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .pointerInput(
                    selectedTarget,
                    viewportSize,
                    template.screenWidth,
                    template.screenHeight,
                    template.scrollDirection,
                    template.orientation,
                ) {
                    if (viewportSize.width <= 0 || viewportSize.height <= 0) return@pointerInput
                    var startTemplate: GameCaptureTemplate? = null
                    var dragHandle = RegionDragHandle.NONE
                    var gestureDragHandle = GesturePathDragHandle.NONE
                    var accumulated = Offset.Zero
                    val transform = TemplateViewportTransform.calculate(
                        modelWidth = template.screenWidth.coerceAtLeast(1),
                        modelHeight = template.screenHeight.coerceAtLeast(1),
                        viewportWidth = viewportSize.width.toFloat(),
                        viewportHeight = viewportSize.height.toFloat(),
                        screenOrientation = ScreenOrientation.fromSerialized(
                            template.orientation,
                            template.screenWidth,
                            template.screenHeight,
                        ),
                    )
                    val minimumGestureDistancePx = kotlin.math.ceil(
                        with(density) {
                            RESIZE_HANDLE_TOUCH_RADIUS.toPx()
                        } * 4f / transform.scale,
                    ).toInt()
                    detectDragGestures(
                        onDragStart = { viewPoint ->
                            val active = currentTemplate.value
                            val modelPoint = transform.viewToModel(
                                EditorPoint(viewPoint.x, viewPoint.y),
                            )
                            startTemplate = active
                            accumulated = Offset.Zero
                            if (selectedTarget == TemplateEditTarget.GESTURE) {
                                dragHandle = RegionDragHandle.NONE
                                val path = templateGesturePath(active, minimumGestureDistancePx)
                                val handles = gesturePathWaterdropHandles(
                                    start = transform.modelToView(path.start),
                                    middle = transform.modelToView(path.middle),
                                    end = transform.modelToView(path.end),
                                    viewport = EditorFloatRect(
                                        0f,
                                        0f,
                                        viewportSize.width.toFloat(),
                                        viewportSize.height.toFloat(),
                                    ),
                                    handleInset = with(density) {
                                        GESTURE_HANDLE_INSET.toPx()
                                    },
                                    handleRadius = with(density) {
                                        GESTURE_HANDLE_RADIUS.toPx()
                                    },
                                    outerPadding = with(density) {
                                        GESTURE_OUTER_PADDING.toPx()
                                    },
                                )
                                gestureDragHandle = hitTestGesturePathWaterdropHandles(
                                    handles = handles,
                                    point = EditorPoint(viewPoint.x, viewPoint.y),
                                    touchRadius = with(density) {
                                        RESIZE_HANDLE_TOUCH_RADIUS.toPx()
                                    },
                                )
                            } else {
                                gestureDragHandle = GesturePathDragHandle.NONE
                                dragHandle = hitTestRegion(
                                    template = active,
                                    target = selectedTarget,
                                    point = modelPoint,
                                    handleInset = with(density) {
                                        RESIZE_HANDLE_INSET.toPx()
                                    } / transform.scale,
                                    handleTouchRadius = with(density) {
                                        RESIZE_HANDLE_TOUCH_RADIUS.toPx()
                                    } / transform.scale,
                                    pixelsPerDp = density.density,
                                )
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val base = startTemplate ?: return@detectDragGestures
                            if (dragHandle == RegionDragHandle.NONE &&
                                gestureDragHandle == GesturePathDragHandle.NONE
                            ) return@detectDragGestures
                            change.consume()
                            accumulated += dragAmount
                            val modelDelta = transform.viewDeltaToModel(
                                EditorPoint(accumulated.x, accumulated.y),
                            )
                            currentChange.value(
                                if (selectedTarget == TemplateEditTarget.GESTURE) {
                                    dragTemplateGesturePath(
                                        template = base,
                                        handle = gestureDragHandle,
                                        deltaX = modelDelta.x,
                                        deltaY = modelDelta.y,
                                        minimumDistancePx = minimumGestureDistancePx,
                                    )
                                } else {
                                    dragTemplateRegion(
                                        template = base,
                                        target = selectedTarget,
                                        handle = dragHandle,
                                        deltaX = modelDelta.x,
                                        deltaY = modelDelta.y,
                                        pixelsPerDp = density.density,
                                    )
                                },
                            )
                        },
                    )
                },
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            val transform = TemplateViewportTransform.calculate(
                modelWidth = template.screenWidth.coerceAtLeast(1),
                modelHeight = template.screenHeight.coerceAtLeast(1),
                viewportWidth = size.width,
                viewportHeight = size.height,
                screenOrientation = ScreenOrientation.fromSerialized(
                    template.orientation,
                    template.screenWidth,
                    template.screenHeight,
                ),
            )
            val content = transform.contentRect
            drawRect(
                color = Color.Black.copy(alpha = 0.82f),
                topLeft = Offset(content.left, content.top),
                size = Size(content.width, content.height),
            )
            if (bitmap != null) {
                drawReferenceBitmap(bitmap, transform, bitmapPaint)
            }
            drawCaptureShade(transform, template.captureRegion)
            val captureView = transform.modelToView(template.captureRegion)
            val stitchView = transform.modelToView(template.stitchRegion)
            val minimumGestureDistancePx = kotlin.math.ceil(
                RESIZE_HANDLE_TOUCH_RADIUS.toPx() * 4f / transform.scale,
            ).toInt()
            val gesturePath = templateGesturePath(template, minimumGestureDistancePx)
            val gestureStartView = transform.modelToView(gesturePath.start)
            val gestureMiddleView = transform.modelToView(gesturePath.middle)
            val gestureEndView = transform.modelToView(gesturePath.end)
            val previewModel = previewModelRect(template, density.density)
            val previewView = transform.modelToView(previewModel)
            val fixedBands = template.fixedLayers.mapIndexedNotNull { index, layer ->
                template.fixedBandRegion(layer.side)?.let { band ->
                    index to Triple(layer.side, band, transform.modelToView(band))
                }
            }
            val repeatLayers = template.fixedLayers.mapIndexedNotNull { index, layer ->
                if (!fixedBandStretchesWithScroll(template.scrollDirection, layer.side)) {
                    return@mapIndexedNotNull null
                }
                layer.repeatRegion?.let { repeat ->
                    index to Triple(layer, repeat, transform.modelToView(repeat))
                }
            }
            val fixedAnchorBlockViews = repeatLayers.flatMap { (index, repeatData) ->
                val (layer, repeat, _) = repeatData
                template.fixedBandRegion(layer.side)?.let { band ->
                    fixedAnchorBlocks(
                        band = band,
                        repeat = repeat,
                        vertical = fixedBandRunsVertically(layer.side),
                        anchorCount = layer.anchorCount,
                        anchorPosition = layer.anchorPosition,
                    ).map { block ->
                        index to Triple(layer.side, block, transform.modelToView(block.region))
                    }
                }.orEmpty()
            }
            val frameRects = buildList {
                add(captureView)
                add(stitchView)
                add(previewView)
                fixedBands.mapTo(this) { it.second.third }
                repeatLayers.mapTo(this) { it.second.third }
                fixedAnchorBlockViews.mapTo(this) { it.second.third }
            }
            val gestureGeometry = templateGestureDisplayGeometry(
                start = gestureStartView,
                middle = gestureMiddleView,
                end = gestureEndView,
                distancePx = gesturePath.distancePx,
                bounds = EditorFloatRect(0f, 0f, size.width, size.height),
                labelPaint = gestureDistanceTextPaint,
                labelObstacles = frameRects.flatMap { frame ->
                    editorFrameBorderObstacles(frame, 2.dp.toPx())
                },
            )
            fixedBands.forEach { (index, bandData) ->
                drawFixedBand(
                    rect = bandData.third,
                    color = fixedLayerColor(index),
                    selected = selectedTarget.matchesFixedLayer(index),
                    nonFocusedAlpha = nonFocusedAlpha,
                )
            }
            drawEditableRegion(
                captureView,
                captureColor,
                selectedTarget == TemplateEditTarget.CAPTURE,
                nonFocusedAlpha,
            )
            drawEditableRegion(
                stitchView,
                stitchColor,
                selectedTarget == TemplateEditTarget.STITCH,
                nonFocusedAlpha,
            )
            repeatLayers.forEach { (index, repeatData) ->
                val (layer, _, visibleRepeat) = repeatData
                val anchorCount = layer.anchorCount
                val leadingHandle = anchorCount == 2 ||
                    layer.anchorPosition == FixedAnchorPosition.LEADING
                val trailingHandle = anchorCount == 2 ||
                    layer.anchorPosition == FixedAnchorPosition.TRAILING
                val repeatModelHandles = if (fixedBandRunsVertically(layer.side)) {
                    buildSet {
                        if (leadingHandle) add(RegionDragHandle.TOP)
                        if (trailingHandle) add(RegionDragHandle.BOTTOM)
                    }
                } else {
                    buildSet {
                        if (leadingHandle) add(RegionDragHandle.LEFT)
                        if (trailingHandle) add(RegionDragHandle.RIGHT)
                    }
                }
                drawRepeatRegion(
                    rect = visibleRepeat,
                    color = fixedLayerColor(index),
                    selected = selectedTarget.matchesFixedLayer(index),
                    nonFocusedAlpha = nonFocusedAlpha,
                    resizeHandles = modelHandlesToView(
                        repeatModelHandles,
                        transform.quarterTurn,
                    ),
                )
            }
            drawPreviewAnchor(
                transform = transform,
                template = template,
                color = previewColor,
                selected = selectedTarget == TemplateEditTarget.PREVIEW,
                nonFocusedAlpha = nonFocusedAlpha,
            )
            drawTemplateRegionLabels(
                bounds = EditorFloatRect(0f, 0f, size.width, size.height),
                contentBounds = content,
                quarterTurn = transform.quarterTurn,
                backgroundColor = labelBackgroundColor,
                nonFocusedAlpha = nonFocusedAlpha,
                obstacles = gestureGeometry?.let { geometry ->
                    buildList {
                        add(geometry.distanceLabelBounds)
                        if (selectedTarget == TemplateEditTarget.GESTURE) {
                            geometry.handles.mapTo(this) { it.bounds }
                        }
                    }
                }.orEmpty(),
                labels = buildList {
                    add(TemplateRegionLabel(
                        captureRegionLabel,
                        template.captureRegion,
                        captureView,
                        captureColor,
                        selectedTarget == TemplateEditTarget.CAPTURE,
                    ))
                    add(TemplateRegionLabel(
                        stitchRegionLabel,
                        template.stitchRegion,
                        stitchView,
                        stitchColor,
                        selectedTarget == TemplateEditTarget.STITCH,
                    ))
                    if (shouldShowFixedBandLabels(template.fixedLayers.size)) {
                        fixedBands.forEach { (index, bandData) ->
                            val (side, band, bandView) = bandData
                            add(TemplateRegionLabel(
                                fixedSideRegionLabels.getValue(side),
                                band,
                                bandView,
                                fixedLayerColor(index),
                                selectedTarget.matchesFixedLayer(index),
                            ))
                        }
                    }
                    repeatLayers.forEach { (index, repeatData) ->
                        val (layer, repeat, repeatView) = repeatData
                        val name = if (template.fixedLayers.size == 1) {
                            repeatRegionLabel
                        } else {
                            fixedSideRepeatLabels.getValue(layer.side)
                        }
                        val bandView = fixedBands.firstOrNull { it.first == index }
                            ?.second
                            ?.third
                            ?: repeatView
                        add(
                            TemplateRegionLabel(
                                name,
                                repeat,
                                repeatView,
                                fixedLayerColor(index),
                                selectedTarget.matchesFixedLayer(index),
                                containerRect = bandView,
                                allowedFrameBorders = fixedAnchorBlockViews
                                    .filter { it.first == index }
                                    .flatMap { editorSharedFrameBorderAllowances(repeatView, it.second.third) },
                            ),
                        )
                    }
                    fixedAnchorBlockViews
                        .filter { (index, _) ->
                            shouldShowFixedAnchorBlockLabels(
                                template.fixedLayers.getOrNull(index)?.anchorCount ?: 1,
                            )
                        }
                        .forEach { (index, blockData) ->
                        val (side, block, blockView) = blockData
                        val blockLabel = anchorBlockLabels.getValue(
                            fixedBandRunsVertically(side) to block.position,
                        )
                        add(
                            TemplateRegionLabel(
                                if (template.fixedLayers.size == 1) {
                                    blockLabel
                                } else {
                                    fixedAnchorBlockLabels.getValue(side to block.position)
                                },
                                block.region,
                                blockView,
                                fixedLayerColor(index),
                                selectedTarget.matchesFixedLayer(index),
                                containerRect = fixedBands.firstOrNull { it.first == index }
                                    ?.second
                                    ?.third
                                    ?: blockView,
                                allowedFrameBorders = repeatLayers
                                    .firstOrNull { it.first == index }
                                    ?.second
                                    ?.third
                                    ?.let { repeatView ->
                                        editorSharedFrameBorderAllowances(blockView, repeatView)
                                    }
                                    .orEmpty(),
                            ),
                        )
                    }
                    add(TemplateRegionLabel(
                        previewRegionLabel,
                        RectConfig(
                            previewModel.left.roundToInt(),
                            previewModel.top.roundToInt(),
                            previewModel.right.roundToInt(),
                            previewModel.bottom.roundToInt(),
                        ),
                        previewView,
                        previewColor,
                        selectedTarget == TemplateEditTarget.PREVIEW,
                        valueText = "${template.previewWidthDp} dp × ${template.previewMaxHeightDp} dp",
                    ))
                },
            )
            drawTemplateGesturePath(
                geometry = gestureGeometry,
                color = gestureColor,
                backgroundColor = labelBackgroundColor,
                labelPaint = gestureDistanceTextPaint,
                selected = selectedTarget == TemplateEditTarget.GESTURE,
                nonFocusedAlpha = nonFocusedAlpha,
            )
        }
    }
}

private fun DrawScope.drawReferenceBitmap(
    bitmap: Bitmap,
    transform: TemplateViewportTransform,
    paint: AndroidPaint,
) {
    val modelBounds = EditorFloatRect(
        left = 0f,
        top = 0f,
        right = transform.modelWidth.toFloat(),
        bottom = transform.modelHeight.toFloat(),
    )
    val imageRect = aspectFitRect(bitmap.width, bitmap.height, modelBounds)
    val content = transform.contentRect
    val matrix = AndroidMatrix().apply {
        if (transform.quarterTurn) {
            setValues(
                floatArrayOf(
                    0f, -transform.scale, transform.offsetX + transform.modelHeight * transform.scale,
                    transform.scale, 0f, transform.offsetY,
                    0f, 0f, 1f,
                ),
            )
        } else {
            setValues(
                floatArrayOf(
                    transform.scale, 0f, transform.offsetX,
                    0f, transform.scale, transform.offsetY,
                    0f, 0f, 1f,
                ),
            )
        }
    }
    val canvas = drawContext.canvas.nativeCanvas
    val checkpoint = canvas.save()
    try {
        canvas.clipRect(content.left, content.top, content.right, content.bottom)
        canvas.concat(matrix)
        canvas.drawBitmap(
            bitmap,
            null,
            AndroidRectF(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom),
            paint,
        )
    } finally {
        canvas.restoreToCount(checkpoint)
    }
}

@Composable
private fun TemplateEditorControls(
    state: TemplateEditorScreenState,
    selectedTarget: TemplateEditTarget,
    availableTargets: List<TemplateEditTarget>,
    onSelectedTargetChange: (TemplateEditTarget) -> Unit,
    onTemplateChange: (GameCaptureTemplate) -> Unit,
    onPickReference: () -> Unit,
    referenceVisible: Boolean,
    onReferenceVisibilityChange: (Boolean) -> Unit,
    onPickApplication: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val template = state.template
    val pixelsPerDp = LocalDensity.current.density
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.template_editor_basic_info),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = template.templateName,
            onValueChange = { onTemplateChange(template.copy(templateName = it.take(40))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.template_editor_template_name)) },
            singleLine = true,
        )
        Column {
            ListItem(
                headlineContent = {
                    Text(
                        template.appName.ifBlank {
                            stringResource(R.string.template_editor_app_not_bound)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        template.packageName.ifBlank {
                            stringResource(R.string.template_editor_select_app_hint)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    OutlinedButton(onClick = onPickApplication) {
                        Text(stringResource(R.string.template_editor_select))
                    }
                },
                modifier = Modifier.testTag("basic-app-row"),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(
                            if (state.hasReference) R.string.template_editor_reference_selected
                            else R.string.template_editor_reference_optional,
                        ),
                    )
                },
                supportingContent = {
                    Text("${template.screenWidth} × ${template.screenHeight} · ${orientationLabel(template)}")
                },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.hasReference) {
                            OutlinedButton(
                                onClick = { onReferenceVisibilityChange(!referenceVisible) },
                                enabled = state.referenceBitmap != null,
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                modifier = Modifier.testTag("reference-visibility-toggle"),
                            ) {
                                Text(
                                    stringResource(
                                        if (referenceVisible) R.string.template_editor_hide
                                        else R.string.template_editor_show,
                                    ),
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onPickReference,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            modifier = Modifier.testTag("reference-picker"),
                        ) {
                            Text(
                                stringResource(
                                    if (state.hasReference) R.string.template_editor_replace
                                    else R.string.template_editor_select,
                                ),
                            )
                        }
                    }
                },
                modifier = Modifier.testTag("basic-reference-row"),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        HorizontalDivider()
        Text(
            stringResource(R.string.template_editor_edit_regions),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        TargetSelector(selectedTarget, availableTargets, onSelectedTargetChange)
        Text(
            selectedTargetDescription(template, selectedTarget),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
        )
        FixedRegionSettings(template, onTemplateChange)

        HorizontalDivider()
        Text(
            stringResource(R.string.template_editor_screen_orientation),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        val screenOrientation = ScreenOrientation.fromSerialized(
            template.orientation,
            template.screenWidth,
            template.screenHeight,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScreenOrientation.entries.forEachIndexed { index, orientation ->
                SegmentedButton(
                    selected = screenOrientation == orientation,
                    onClick = {
                        onTemplateChange(
                            templateWithScreenOrientation(template, orientation, pixelsPerDp),
                        )
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ScreenOrientation.entries.size),
                    modifier = Modifier.testTag("template-screen-orientation-${orientation.serializedValue}"),
                ) {
                    Text(screenOrientationLabel(orientation))
                }
            }
        }
        Text(
            stringResource(R.string.template_editor_scroll_direction),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ScrollDirection.entries.forEachIndexed { index, direction ->
                SegmentedButton(
                    selected = template.scrollDirection == direction,
                    onClick = {
                        onTemplateChange(
                            templateWithScrollDirection(template, direction, pixelsPerDp),
                        )
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, ScrollDirection.entries.size),
                ) {
                    Text(directionShortLabel(direction))
                }
            }
        }

        HorizontalDivider()
        Text(
            stringResource(R.string.template_editor_capture_timing),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        TemplateTimingControls(
            swipeDurationMs = template.gestureDurationMs,
            stableTimeMs = template.gestureStableTimeMs,
            postGestureWaitEnabled = template.postGestureWaitEnabled,
            postGestureWaitMs = template.postGestureWaitMs,
            minimumIntervalMs = template.waitAfterGestureMs,
            onSwipeDurationChanged = { durationMs ->
                onTemplateChange(template.copy(gestureDurationMs = durationMs))
            },
            onStableTimeChanged = { stableTimeMs ->
                onTemplateChange(template.copy(gestureStableTimeMs = stableTimeMs))
            },
            onPostGestureWaitEnabledChanged = { enabled ->
                onTemplateChange(template.copy(postGestureWaitEnabled = enabled))
            },
            onPostGestureWaitChanged = { waitMs ->
                onTemplateChange(template.copy(postGestureWaitMs = waitMs))
            },
            onMinimumIntervalChanged = { intervalMs ->
                onTemplateChange(template.copy(waitAfterGestureMs = intervalMs))
            },
        )
    }
}

@Composable
internal fun FixedRegionSettings(
    template: GameCaptureTemplate,
    onTemplateChange: (GameCaptureTemplate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.template_editor_fixed_band_count),
            style = MaterialTheme.typography.titleSmall,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(1, 2).forEachIndexed { index, count ->
                SegmentedButton(
                    selected = template.fixedLayers.size == count,
                    onClick = { onTemplateChange(templateWithFixedLayerCount(template, count)) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("fixed-layer-count-$count"),
                ) {
                    Text(
                        text = stringResource(R.string.template_editor_item_count, count),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        FixedRegionControls(template, onTemplateChange)
    }
}

internal fun ComposeView.setFixedRegionSettingsContent(
    templateState: State<GameCaptureTemplate>,
    themeMode: String,
    dynamicColor: Boolean,
    onTemplateChange: (GameCaptureTemplate) -> Unit,
    topContentInsetPx: State<Int>? = null,
    startContentInsetPx: State<Int>? = null,
) {
    setContent {
        GameLongShotTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
            val density = LocalDensity.current
            val topInset = with(density) {
                (topContentInsetPx?.value ?: 0).toDp()
            }
            val startInset = with(density) {
                (startContentInsetPx?.value ?: 0).toDp()
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(
                            start = 14.dp + startInset,
                            top = 12.dp + topInset,
                            end = 14.dp,
                            bottom = 12.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.template_editor_fixed_region_settings),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("fixed-region-settings-title"),
                    )
                    FixedRegionSettings(
                        template = templateState.value,
                        onTemplateChange = onTemplateChange,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixedRegionControls(
    template: GameCaptureTemplate,
    onTemplateChange: (GameCaptureTemplate) -> Unit,
) {
    val visibleTemplate = template.takeIf { it.fixedLayers.isNotEmpty() }
        ?: templateWithFixedLayerCount(template, 1)
    val fixedLayers = visibleTemplate.fixedLayers.take(2)
    val firstLayer = fixedLayers.first()
    val currentSecondLayer = fixedLayers.getOrNull(1)
    var displayedSecondLayer by remember { mutableStateOf(currentSecondLayer) }
    LaunchedEffect(currentSecondLayer) {
        if (currentSecondLayer != null) displayedSecondLayer = currentSecondLayer
    }
    val secondLayerProgress by animateFloatAsState(
        targetValue = if (currentSecondLayer == null) 0f else 1f,
        animationSpec = fixedRegionTween(),
        finishedListener = { progress ->
            if (progress == 0f) displayedSecondLayer = null
        },
        label = "固定带分组宽度",
    )
    val renderSecondLayer = displayedSecondLayer != null &&
        (currentSecondLayer != null || secondLayerProgress > MinimumAnimatedWeight)

    Column {
        val dualLayer = currentSecondLayer != null
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (dualLayer) {
                    stringResource(R.string.template_editor_fixed_band_number_position, 1)
                } else {
                    stringResource(R.string.template_editor_fixed_band_position)
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
            if (dualLayer) {
                Text(
                    stringResource(R.string.template_editor_fixed_band_number_position, 2),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(
            targetState = dualLayer,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = 0.96f))
            },
            modifier = Modifier.fillMaxWidth(),
            label = "固定带位置控件切换",
        ) { showDropdowns ->
            if (showDropdowns) {
                val dropdownLayers = listOfNotNull(
                    firstLayer,
                    currentSecondLayer ?: displayedSecondLayer,
                ).take(2)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(FixedSideControlHeight),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    dropdownLayers.forEachIndexed { index, layer ->
                        FixedSideDropdown(
                            layerIndex = index,
                            selected = layer.side,
                            onSelected = { side ->
                                onTemplateChange(
                                    templateWithFixedLayerSide(template, layer.side, side),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                FixedSideSegmentedControl(
                    layer = firstLayer,
                    onSideSelected = { side ->
                        onTemplateChange(
                            templateWithFixedLayerSide(template, firstLayer.side, side),
                        )
                    },
                )
            }
        }

        val repeatSettingLayers = fixedLayers.mapIndexedNotNull { index, layer ->
            (index to layer).takeIf {
                fixedBandStretchesWithScroll(visibleTemplate.scrollDirection, layer.side)
            }
        }
        if (repeatSettingLayers.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeatSettingLayers.forEach { (index, layer) ->
                    FixedAnchorCountGroup(
                        layerIndex = index,
                        layer = layer,
                        title = if (fixedLayers.size == 1) {
                            stringResource(R.string.template_editor_fixed_anchor_count)
                        } else {
                            stringResource(
                                R.string.template_editor_fixed_anchor_number_count,
                                index + 1,
                            )
                        },
                        onCountSelected = { anchorCount ->
                            onTemplateChange(
                                templateWithAnchorCount(template, layer.side, anchorCount),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        val currentFirstPositionLayer = fixedLayers.getOrNull(0)?.takeIf { layer ->
            layer.anchorCount == 1 &&
                fixedBandStretchesWithScroll(visibleTemplate.scrollDirection, layer.side)
        }
        val currentSecondPositionLayer = fixedLayers.getOrNull(1)?.takeIf { layer ->
            layer.anchorCount == 1 &&
                fixedBandStretchesWithScroll(visibleTemplate.scrollDirection, layer.side)
        }
        var displayedFirstPositionLayer by remember { mutableStateOf(currentFirstPositionLayer) }
        var displayedSecondPositionLayer by remember { mutableStateOf(currentSecondPositionLayer) }
        LaunchedEffect(currentFirstPositionLayer) {
            if (currentFirstPositionLayer != null) {
                displayedFirstPositionLayer = currentFirstPositionLayer
            }
        }
        LaunchedEffect(currentSecondPositionLayer) {
            if (currentSecondPositionLayer != null) {
                displayedSecondPositionLayer = currentSecondPositionLayer
            }
        }
        val firstPositionProgress by animateFloatAsState(
            targetValue = if (currentFirstPositionLayer == null) 0f else 1f,
            animationSpec = fixedRegionTween(),
            finishedListener = { progress ->
                if (progress == 0f) displayedFirstPositionLayer = null
            },
            label = "固定带1固定块位置宽度",
        )
        val secondPositionProgress by animateFloatAsState(
            targetValue = if (currentSecondPositionLayer == null) 0f else 1f,
            animationSpec = fixedRegionTween(),
            finishedListener = { progress ->
                if (progress == 0f) displayedSecondPositionLayer = null
            },
            label = "固定带2固定块位置宽度",
        )
        val renderFirstPosition = displayedFirstPositionLayer != null &&
            (currentFirstPositionLayer != null || firstPositionProgress > MinimumAnimatedWeight)
        val renderSecondPosition = displayedSecondPositionLayer != null &&
            (currentSecondPositionLayer != null || secondPositionProgress > MinimumAnimatedWeight)
        Column(
            modifier = Modifier.animateContentSize(animationSpec = fixedRegionTween()),
        ) {
            if (renderFirstPosition || renderSecondPosition) {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    displayedFirstPositionLayer?.takeIf { renderFirstPosition }?.let { layer ->
                        FixedAnchorPositionGroup(
                            layerIndex = 0,
                            layer = layer,
                            onPositionSelected = { position ->
                                onTemplateChange(
                                    templateWithAnchorPosition(template, layer.side, position),
                                )
                            },
                            modifier = Modifier
                                .weight(firstPositionProgress.coerceAtLeast(MinimumAnimatedWeight))
                                .animatedGroupAppearance(firstPositionProgress),
                        )
                    }
                    if (renderFirstPosition && renderSecondPosition) {
                        Spacer(
                            Modifier.width(
                                16.dp * minOf(firstPositionProgress, secondPositionProgress),
                            ),
                        )
                    }
                    displayedSecondPositionLayer?.takeIf { renderSecondPosition }?.let { layer ->
                        FixedAnchorPositionGroup(
                            layerIndex = 1,
                            layer = layer,
                            onPositionSelected = { position ->
                                onTemplateChange(
                                    templateWithAnchorPosition(template, layer.side, position),
                                )
                            },
                            modifier = Modifier
                                .weight(secondPositionProgress.coerceAtLeast(MinimumAnimatedWeight))
                                .animatedGroupAppearance(secondPositionProgress),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FixedAnchorCountGroup(
    layerIndex: Int,
    layer: FixedLayerConfig,
    title: String,
    onCountSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        FixedAnchorCountControl(
            layerIndex = layerIndex,
            layer = layer,
            onCountSelected = onCountSelected,
        )
    }
}

@Composable
private fun FixedAnchorPositionGroup(
    layerIndex: Int,
    layer: FixedLayerConfig,
    onPositionSelected: (FixedAnchorPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(
                R.string.template_editor_fixed_anchor_number_position,
                layerIndex + 1,
            ),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        FixedAnchorPositionControl(
            layerIndex = layerIndex,
            layer = layer,
            onPositionSelected = onPositionSelected,
        )
    }
}

private fun Modifier.animatedGroupAppearance(progress: Float): Modifier =
    if (progress >= 0.999f) {
        this
    } else {
        graphicsLayer {
            alpha = progress
            scaleX = 0.92f + 0.08f * progress
            scaleY = 0.92f + 0.08f * progress
            clip = true
        }
    }

private fun <T> fixedRegionTween() = tween<T>(
    durationMillis = FixedRegionAnimationDurationMillis,
    easing = FastOutSlowInEasing,
)

private const val FixedRegionAnimationDurationMillis = 220
private const val MinimumAnimatedWeight = 0.001f

private val FixedSideControlHeight = AppButtonHeight

@Composable
private fun FixedSideSegmentedControl(
    layer: FixedLayerConfig,
    onSideSelected: (FixedSide) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .height(FixedSideControlHeight),
    ) {
        FixedSide.entries.forEachIndexed { optionIndex, side ->
            SegmentedButton(
                selected = layer.side == side,
                onClick = { onSideSelected(side) },
                shape = SegmentedButtonDefaults.itemShape(optionIndex, FixedSide.entries.size),
                modifier = Modifier
                    .weight(1f)
                    .testTag("fixed-side-0-${side.name}"),
            ) {
                Text(fixedSideShortLabel(side))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixedSideDropdown(
    layerIndex: Int,
    selected: FixedSide,
    onSelected: (FixedSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var lastDismissedAtMillis by remember { mutableStateOf(0L) }
    fun dismissMenu() {
        expanded = false
        lastDismissedAtMillis = SystemClock.uptimeMillis()
    }
    val positionContentDescription = stringResource(
        R.string.template_editor_fixed_band_position_description,
        layerIndex + 1,
    )
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = {
                val now = SystemClock.uptimeMillis()
                when {
                    expanded -> dismissMenu()
                    lastDismissedAtMillis == 0L ||
                        now - lastDismissedAtMillis >= FixedSideDismissGuardMillis -> {
                        expanded = true
                    }
                }
            },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(FixedSideControlHeight)
                .semantics { contentDescription = positionContentDescription }
                .testTag("fixed-side-selector-$layerIndex"),
        ) {
            Text(
                text = fixedSideShortLabel(selected),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = ::dismissMenu,
        ) {
            FixedSide.entries.forEach { side ->
                DropdownMenuItem(
                    text = { Text(fixedSideShortLabel(side)) },
                    onClick = {
                        dismissMenu()
                        onSelected(side)
                    },
                    modifier = Modifier.testTag("fixed-side-$layerIndex-${side.name}"),
                )
            }
        }
    }
}

private const val FixedSideDismissGuardMillis = 240L

@Composable
private fun FixedAnchorCountControl(
    layerIndex: Int,
    layer: FixedLayerConfig,
    onCountSelected: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(1, 2).forEachIndexed { optionIndex, count ->
            SegmentedButton(
                selected = layer.anchorCount == count,
                onClick = { onCountSelected(count) },
                shape = SegmentedButtonDefaults.itemShape(optionIndex, 2),
                modifier = Modifier
                    .weight(1f)
                    .testTag("fixed-anchor-$layerIndex-$count"),
            ) {
                Text(
                    text = stringResource(R.string.template_editor_item_count, count),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

@Composable
private fun FixedAnchorPositionControl(
    layerIndex: Int,
    layer: FixedLayerConfig,
    onPositionSelected: (FixedAnchorPosition) -> Unit,
) {
    val verticalBand = fixedBandRunsVertically(layer.side)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        FixedAnchorPosition.entries.forEachIndexed { optionIndex, position ->
            SegmentedButton(
                selected = layer.anchorPosition == position,
                onClick = { onPositionSelected(position) },
                shape = SegmentedButtonDefaults.itemShape(
                    optionIndex,
                    FixedAnchorPosition.entries.size,
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("fixed-anchor-position-$layerIndex-${position.name}"),
            ) {
                Text(fixedAnchorPositionShortLabel(position, verticalBand))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TargetSelector(
    selected: TemplateEditTarget,
    targets: List<TemplateEditTarget>,
    onSelected: (TemplateEditTarget) -> Unit,
) {
    val fixedTargets = targets.filter { it.fixedLayerIndex != null }
    val hasSecondFixedTarget = fixedTargets.size > 1
    val secondTargetProgress by animateFloatAsState(
        targetValue = if (hasSecondFixedTarget) 1f else 0f,
        animationSpec = fixedRegionTween(),
        label = "编辑区域第三行拆分",
    )
    val renderSecondFixedTarget = hasSecondFixedTarget ||
        secondTargetProgress > MinimumAnimatedWeight
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TargetSelectorRow(
            selected = selected,
            targets = PrimaryTemplateTargetRow,
            onSelected = onSelected,
        )
        TargetSelectorRow(
            selected = selected,
            targets = SecondaryTemplateTargetRow,
            onSelected = onSelected,
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                fixedTargets.firstOrNull()?.let { target ->
                    TargetSelectorButton(
                        target = target,
                        selected = selected == target,
                        onSelected = onSelected,
                        index = 0,
                        count = if (renderSecondFixedTarget) 2 else 1,
                        shape = splitTargetShape(index = 0, progress = secondTargetProgress),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (renderSecondFixedTarget) {
                    val target = fixedTargets.getOrNull(1) ?: TemplateEditTarget.FIXED_2
                    TargetSelectorButton(
                        target = target,
                        selected = selected == target,
                        onSelected = onSelected,
                        index = 1,
                        count = 2,
                        shape = splitTargetShape(index = 1, progress = secondTargetProgress),
                        modifier = Modifier
                            .weight(secondTargetProgress.coerceAtLeast(MinimumAnimatedWeight))
                            .splitTargetAppearance(secondTargetProgress),
                    )
                }
            }
        }
    }
}

private val PrimaryTemplateTargetRow = listOf(
    TemplateEditTarget.CAPTURE,
    TemplateEditTarget.STITCH,
)

private val SecondaryTemplateTargetRow = listOf(
    TemplateEditTarget.GESTURE,
    TemplateEditTarget.PREVIEW,
)

@Composable
private fun TargetSelectorRow(
    selected: TemplateEditTarget,
    targets: List<TemplateEditTarget>,
    onSelected: (TemplateEditTarget) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        targets.forEachIndexed { index, target ->
            TargetSelectorButton(
                target = target,
                selected = selected == target,
                onSelected = onSelected,
                index = index,
                count = targets.size,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SingleChoiceSegmentedButtonRowScope.TargetSelectorButton(
    target: TemplateEditTarget,
    selected: Boolean,
    onSelected: (TemplateEditTarget) -> Unit,
    index: Int,
    count: Int,
    shape: Shape? = null,
    modifier: Modifier = Modifier,
) {
    SegmentedButton(
        selected = selected,
        onClick = { onSelected(target) },
        shape = shape ?: SegmentedButtonDefaults.itemShape(index, count),
        modifier = modifier.testTag("template-target-${target.name}"),
    ) {
        Text(stringResource(target.labelRes), maxLines = 1)
    }
}

internal fun splitTargetInnerCornerPercent(progress: Float): Int =
    ((1f - progress.coerceIn(0f, 1f)) * 50f).roundToInt()

private fun splitTargetShape(index: Int, progress: Float): Shape {
    val innerCorner = splitTargetInnerCornerPercent(progress)
    return if (index == 0) {
        RoundedCornerShape(
            topStartPercent = 50,
            topEndPercent = innerCorner,
            bottomEndPercent = innerCorner,
            bottomStartPercent = 50,
        )
    } else {
        RoundedCornerShape(
            topStartPercent = innerCorner,
            topEndPercent = 50,
            bottomEndPercent = 50,
            bottomStartPercent = innerCorner,
        )
    }
}

private fun Modifier.splitTargetAppearance(progress: Float): Modifier = graphicsLayer {
    alpha = progress.coerceIn(0f, 1f)
    clip = true
}

private fun DrawScope.drawCaptureShade(
    transform: TemplateViewportTransform,
    capture: RectConfig,
) {
    val content = transform.contentRect
    val visible = transform.modelToView(capture)
    val shade = Color.Black.copy(alpha = 0.42f)
    drawRect(shade, Offset(content.left, content.top), Size(content.width, (visible.top - content.top).coerceAtLeast(0f)))
    drawRect(shade, Offset(content.left, visible.bottom), Size(content.width, (content.bottom - visible.bottom).coerceAtLeast(0f)))
    drawRect(shade, Offset(content.left, visible.top), Size((visible.left - content.left).coerceAtLeast(0f), visible.height))
    drawRect(shade, Offset(visible.right, visible.top), Size((content.right - visible.right).coerceAtLeast(0f), visible.height))
}

private data class TemplateGestureDisplayGeometry(
    val start: EditorPoint,
    val end: EditorPoint,
    val unitX: Float,
    val unitY: Float,
    val distanceText: String,
    val distanceLabelBounds: EditorFloatRect,
    val handles: List<GesturePathWaterdropHandle>,
)

private fun DrawScope.templateGestureDisplayGeometry(
    start: EditorPoint,
    middle: EditorPoint,
    end: EditorPoint,
    distancePx: Int,
    bounds: EditorFloatRect,
    labelPaint: AndroidPaint,
    labelObstacles: List<EditorFloatRect>,
): TemplateGestureDisplayGeometry? {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = kotlin.math.hypot(dx, dy)
    if (length <= 0f || bounds.width <= 0f || bounds.height <= 0f) return null
    val unitX = dx / length
    val unitY = dy / length
    val outerPadding = GESTURE_OUTER_PADDING.toPx()
    val handles = gesturePathWaterdropHandles(
        start = start,
        middle = middle,
        end = end,
        viewport = bounds,
        handleInset = GESTURE_HANDLE_INSET.toPx(),
        handleRadius = GESTURE_HANDLE_RADIUS.toPx(),
        outerPadding = outerPadding,
    )
    val normalX = handles.firstOrNull()?.normalX ?: if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) 1f else 0f
    val normalY = handles.firstOrNull()?.normalY ?: if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) 0f else 1f
    val distanceText = "$distancePx px"
    val metrics = labelPaint.fontMetrics
    val labelWidth = labelPaint.measureText(distanceText) + GESTURE_LABEL_PADDING_X.toPx() * 2f
    val labelHeight = metrics.descent - metrics.ascent + GESTURE_LABEL_PADDING_Y.toPx() * 2f
    val horizontalPadding = minOf(outerPadding, (bounds.width - 1f).coerceAtLeast(0f) / 2f)
    val verticalPadding = minOf(outerPadding, (bounds.height - 1f).coerceAtLeast(0f) / 2f)
    val labelViewport = EditorFloatRect(
        left = bounds.left + horizontalPadding,
        top = bounds.top + verticalPadding,
        right = bounds.right - horizontalPadding,
        bottom = bounds.bottom - verticalPadding,
    )
    val placement = placeGestureDistanceLabelGeometry(
        start = start,
        end = end,
        center = middle,
        unitX = unitX,
        unitY = unitY,
        normalX = normalX,
        normalY = normalY,
        labelWidth = labelWidth,
        labelHeight = labelHeight,
        viewport = labelViewport,
        gap = GESTURE_LABEL_GAP.toPx(),
        obstacles = buildList {
            addAll(labelObstacles)
            handles.mapTo(this) { it.bounds }
        },
    )
    return TemplateGestureDisplayGeometry(
        start = start,
        end = end,
        unitX = unitX,
        unitY = unitY,
        distanceText = distanceText,
        distanceLabelBounds = placement.bounds,
        handles = handles,
    )
}

private fun DrawScope.drawTemplateGesturePath(
    geometry: TemplateGestureDisplayGeometry?,
    color: Color,
    backgroundColor: Int,
    labelPaint: AndroidPaint,
    selected: Boolean,
    nonFocusedAlpha: Float,
) {
    geometry ?: return
    val alpha = if (selected) 1f else nonFocusedAlpha
    val startOffset = Offset(geometry.start.x, geometry.start.y)
    val endOffset = Offset(geometry.end.x, geometry.end.y)
    drawLine(
        color = color.copy(alpha = alpha),
        start = startOffset,
        end = endOffset,
        strokeWidth = GESTURE_LINE_WIDTH.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(
        color = color.copy(alpha = alpha),
        radius = GESTURE_START_RADIUS.toPx(),
        center = startOffset,
    )
    val arrowLength = GESTURE_ARROW_LENGTH.toPx()
    val arrowHalfWidth = GESTURE_ARROW_HALF_WIDTH.toPx()
    val perpendicularX = -geometry.unitY
    val perpendicularY = geometry.unitX
    val baseX = geometry.end.x - geometry.unitX * arrowLength
    val baseY = geometry.end.y - geometry.unitY * arrowLength
    drawPath(
        path = Path().apply {
            moveTo(geometry.end.x, geometry.end.y)
            lineTo(
                baseX + perpendicularX * arrowHalfWidth,
                baseY + perpendicularY * arrowHalfWidth,
            )
            lineTo(
                baseX - perpendicularX * arrowHalfWidth,
                baseY - perpendicularY * arrowHalfWidth,
            )
            close()
        },
        color = color.copy(alpha = alpha),
    )
    if (selected) {
        geometry.handles.forEach { handle -> drawGestureWaterdrop(handle, color) }
    }

    val label = geometry.distanceLabelBounds
    if (label.width <= 0f || label.height <= 0f) return
    val canvas = drawContext.canvas.nativeCanvas
    val backgroundPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        this.color = multiplyArgbAlpha(backgroundColor, if (selected) 0.88f else nonFocusedAlpha)
    }
    canvas.drawRoundRect(
        AndroidRectF(label.left, label.top, label.right, label.bottom),
        GESTURE_LABEL_CORNER.toPx(),
        GESTURE_LABEL_CORNER.toPx(),
        backgroundPaint,
    )
    labelPaint.color = multiplyArgbAlpha(color.toArgb(), if (selected) 0.92f else nonFocusedAlpha)
    val metrics = labelPaint.fontMetrics
    canvas.drawText(
        geometry.distanceText,
        (label.left + label.right - labelPaint.measureText(geometry.distanceText)) / 2f,
        (label.top + label.bottom) / 2f - (metrics.ascent + metrics.descent) / 2f,
        labelPaint,
    )
}

private fun DrawScope.drawGestureWaterdrop(
    handle: GesturePathWaterdropHandle,
    color: Color,
) {
    val tip = handle.tip
    val center = handle.center
    val inwardX = handle.normalX
    val inwardY = handle.normalY
    val tangentX = -inwardY
    val tangentY = inwardX
    val centerDistance = kotlin.math.hypot(center.x - tip.x, center.y - tip.y)
    val radius = handle.radius
    fun point(along: Float, across: Float): Offset = Offset(
        tip.x + inwardX * along + tangentX * across,
        tip.y + inwardY * along + tangentY * across,
    )
    val path = Path().apply {
        val neckA = point(centerDistance * 0.42f, -radius * 0.12f)
        val shoulderA = point(centerDistance - radius, -radius)
        val sideA = point(centerDistance, -radius)
        val farShoulderA = point(centerDistance + radius * 0.55f, -radius)
        val farA = point(centerDistance + radius, -radius * 0.55f)
        val far = point(centerDistance + radius, 0f)
        val farB = point(centerDistance + radius, radius * 0.55f)
        val farShoulderB = point(centerDistance + radius * 0.55f, radius)
        val sideB = point(centerDistance, radius)
        val shoulderB = point(centerDistance - radius, radius)
        val neckB = point(centerDistance * 0.42f, radius * 0.12f)
        moveTo(tip.x, tip.y)
        cubicTo(neckA.x, neckA.y, shoulderA.x, shoulderA.y, sideA.x, sideA.y)
        cubicTo(farShoulderA.x, farShoulderA.y, farA.x, farA.y, far.x, far.y)
        cubicTo(farB.x, farB.y, farShoulderB.x, farShoulderB.y, sideB.x, sideB.y)
        cubicTo(shoulderB.x, shoulderB.y, neckB.x, neckB.y, tip.x, tip.y)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawEditableRegion(
    rect: EditorFloatRect,
    color: Color,
    selected: Boolean,
    nonFocusedAlpha: Float,
    resizeHandles: Set<RegionDragHandle> = ALL_RESIZE_HANDLES,
) {
    if (selected) {
        drawRect(
            color = color.copy(alpha = 0.14f),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
        )
    }
    drawRect(
        color = color.copy(alpha = if (selected) 1f else nonFocusedAlpha),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        style = Stroke(width = if (selected) 3.dp.toPx() else 2.dp.toPx()),
    )
    if (!selected) return
    if (RegionDragHandle.LEFT in resizeHandles) {
        drawWaterdropResizeHandle(rect, RegionDragHandle.LEFT, color)
    }
    if (RegionDragHandle.RIGHT in resizeHandles) {
        drawWaterdropResizeHandle(rect, RegionDragHandle.RIGHT, color)
    }
    if (RegionDragHandle.TOP in resizeHandles) {
        drawWaterdropResizeHandle(rect, RegionDragHandle.TOP, color)
    }
    if (RegionDragHandle.BOTTOM in resizeHandles) {
        drawWaterdropResizeHandle(rect, RegionDragHandle.BOTTOM, color)
    }
}

private fun DrawScope.drawFixedBand(
    rect: EditorFloatRect,
    color: Color,
    selected: Boolean,
    nonFocusedAlpha: Float,
) {
    if (selected) {
        drawRect(
            color = color.copy(alpha = 0.14f),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
        )
    }
    drawRect(
        color = color.copy(alpha = if (selected) 1f else nonFocusedAlpha),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        style = Stroke(width = if (selected) 2.dp.toPx() else 1.dp.toPx()),
    )
}

private fun DrawScope.drawRepeatRegion(
    rect: EditorFloatRect,
    color: Color,
    selected: Boolean,
    nonFocusedAlpha: Float,
    resizeHandles: Set<RegionDragHandle>,
) {
    if (selected) {
        drawRect(
            color = color.copy(alpha = 0.10f),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
        )
    }
    drawRect(
        color = color.copy(alpha = if (selected) 0.78f else nonFocusedAlpha),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        style = Stroke(
            width = if (selected) 2.dp.toPx() else 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
        ),
    )
    if (selected) {
        if (RegionDragHandle.LEFT in resizeHandles) {
            drawWaterdropResizeHandle(rect, RegionDragHandle.LEFT, color)
        }
        if (RegionDragHandle.RIGHT in resizeHandles) {
            drawWaterdropResizeHandle(rect, RegionDragHandle.RIGHT, color)
        }
        if (RegionDragHandle.TOP in resizeHandles) {
            drawWaterdropResizeHandle(rect, RegionDragHandle.TOP, color)
        }
        if (RegionDragHandle.BOTTOM in resizeHandles) {
            drawWaterdropResizeHandle(rect, RegionDragHandle.BOTTOM, color)
        }
    }
}

private fun DrawScope.drawWaterdropResizeHandle(
    rect: EditorFloatRect,
    handle: RegionDragHandle,
    color: Color,
) {
    val tip = when (handle) {
        RegionDragHandle.LEFT -> EditorPoint(rect.left, (rect.top + rect.bottom) / 2f)
        RegionDragHandle.TOP -> EditorPoint((rect.left + rect.right) / 2f, rect.top)
        RegionDragHandle.RIGHT -> EditorPoint(rect.right, (rect.top + rect.bottom) / 2f)
        RegionDragHandle.BOTTOM -> EditorPoint((rect.left + rect.right) / 2f, rect.bottom)
        RegionDragHandle.NONE, RegionDragHandle.MOVE -> return
    }
    val center = resizeHandleCenter(rect, handle, RESIZE_HANDLE_INSET.toPx())
    val inwardX = when (handle) {
        RegionDragHandle.LEFT -> 1f
        RegionDragHandle.RIGHT -> -1f
        else -> 0f
    }
    val inwardY = when (handle) {
        RegionDragHandle.TOP -> 1f
        RegionDragHandle.BOTTOM -> -1f
        else -> 0f
    }
    val tangentX = -inwardY
    val tangentY = inwardX
    val centerDistance = kotlin.math.hypot(center.x - tip.x, center.y - tip.y)
    val radius = minOf(8.dp.toPx(), centerDistance * 0.48f).coerceAtLeast(2.dp.toPx())
    fun point(along: Float, across: Float): Offset = Offset(
        tip.x + inwardX * along + tangentX * across,
        tip.y + inwardY * along + tangentY * across,
    )
    val path = Path().apply {
        val neckA = point(centerDistance * 0.42f, -radius * 0.12f)
        val shoulderA = point(centerDistance - radius, -radius)
        val sideA = point(centerDistance, -radius)
        val farShoulderA = point(centerDistance + radius * 0.55f, -radius)
        val farA = point(centerDistance + radius, -radius * 0.55f)
        val far = point(centerDistance + radius, 0f)
        val farB = point(centerDistance + radius, radius * 0.55f)
        val farShoulderB = point(centerDistance + radius * 0.55f, radius)
        val sideB = point(centerDistance, radius)
        val shoulderB = point(centerDistance - radius, radius)
        val neckB = point(centerDistance * 0.42f, radius * 0.12f)
        moveTo(tip.x, tip.y)
        cubicTo(neckA.x, neckA.y, shoulderA.x, shoulderA.y, sideA.x, sideA.y)
        cubicTo(farShoulderA.x, farShoulderA.y, farA.x, farA.y, far.x, far.y)
        cubicTo(farB.x, farB.y, farShoulderB.x, farShoulderB.y, sideB.x, sideB.y)
        cubicTo(shoulderB.x, shoulderB.y, neckB.x, neckB.y, tip.x, tip.y)
        close()
    }
    drawPath(path, color)
}

private fun modelHandlesToView(
    handles: Set<RegionDragHandle>,
    quarterTurn: Boolean,
): Set<RegionDragHandle> {
    if (!quarterTurn) return handles
    return handles.mapTo(mutableSetOf()) { handle ->
        when (handle) {
            RegionDragHandle.LEFT -> RegionDragHandle.TOP
            RegionDragHandle.TOP -> RegionDragHandle.RIGHT
            RegionDragHandle.RIGHT -> RegionDragHandle.BOTTOM
            RegionDragHandle.BOTTOM -> RegionDragHandle.LEFT
            RegionDragHandle.NONE, RegionDragHandle.MOVE -> handle
        }
    }
}

private val ALL_RESIZE_HANDLES = setOf(
    RegionDragHandle.LEFT,
    RegionDragHandle.TOP,
    RegionDragHandle.RIGHT,
    RegionDragHandle.BOTTOM,
)

private fun DrawScope.drawPreviewAnchor(
    transform: TemplateViewportTransform,
    template: GameCaptureTemplate,
    color: Color,
    selected: Boolean,
    nonFocusedAlpha: Float,
) {
    val rect = transform.modelToView(previewModelRect(template, density))
    val center = Offset(
        x = (rect.left + rect.right) / 2f,
        y = (rect.top + rect.bottom) / 2f,
    )
    if (selected) {
        drawRect(
            color = color.copy(alpha = 0.20f),
            topLeft = Offset(rect.left, rect.top),
            size = Size(rect.width, rect.height),
        )
    }
    drawRect(
        color = color.copy(alpha = if (selected) 1f else nonFocusedAlpha),
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        style = Stroke(
            width = if (selected) 3.dp.toPx() else 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())),
            cap = StrokeCap.Round,
        ),
    )
    if (selected) {
        ALL_RESIZE_HANDLES.forEach { handle ->
            drawWaterdropResizeHandle(rect, handle, color)
        }
        drawCircle(
            color = color,
            radius = 6.dp.toPx(),
            center = center,
        )
    }
}

private data class TemplateRegionLabel(
    val name: String,
    val modelRect: RectConfig,
    val viewRect: EditorFloatRect,
    val color: Color,
    val selected: Boolean,
    val valueText: String? = null,
    val containerRect: EditorFloatRect = viewRect,
    val allowedFrameBorders: List<EditorFrameBorderAllowance> = emptyList(),
)

private fun DrawScope.drawTemplateRegionLabels(
    bounds: EditorFloatRect,
    contentBounds: EditorFloatRect,
    quarterTurn: Boolean,
    backgroundColor: Int,
    nonFocusedAlpha: Float,
    obstacles: List<EditorFloatRect>,
    labels: List<TemplateRegionLabel>,
) {
    if (bounds.width <= 0f || bounds.height <= 0f || labels.isEmpty()) return
    val innerPaddingX = 7.dp.toPx()
    val innerPaddingY = 4.dp.toPx()
    val markerDiameter = 7.dp.toPx()
    val markerGap = 5.dp.toPx()
    val gap = 5.dp.toPx()
    val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 12.dp.toPx()
    }
    val texts = labels.map { label ->
        "${label.name}  ${label.valueText ?: "${label.modelRect.width}×${label.modelRect.height}"}"
    }
    val availableLabelLength = if (quarterTurn) bounds.height else bounds.width
    val maximumTextWidth = (
        availableLabelLength - innerPaddingX * 2f - markerDiameter - markerGap
    ).coerceAtLeast(1f)
    val minimumTextSize = 9.dp.toPx()
    while (textPaint.textSize > minimumTextSize &&
        texts.maxOf { textPaint.measureText(it) } > maximumTextWidth
    ) {
        textPaint.textSize = (textPaint.textSize - 1.dp.toPx()).coerceAtLeast(minimumTextSize)
    }
    val metrics = textPaint.fontMetrics
    val boxHeight = metrics.descent - metrics.ascent + innerPaddingY * 2f
    val textStart = innerPaddingX + markerDiameter + markerGap
    val labelWidths = labels.indices.map { index ->
        ceil(textStart + textPaint.measureText(texts[index]) + innerPaddingX)
    }
    val labelRotations = List(labels.size) { quarterTurn }
    val requests = labels.indices.map { index ->
        val labelWidth = labelWidths[index]
        val rotated = labelRotations[index]
        EditorLabelRequest(
            anchor = labels[index].viewRect,
            width = if (rotated) boxHeight else labelWidth,
            height = if (rotated) labelWidth else boxHeight,
        )
    }
    val placements = placeEditorLabels(
        requests = requests,
        bounds = bounds,
        gap = gap,
        obstacles = obstacles,
        preferredSlots = editorLabelGutterSlots(bounds, contentBounds, gap),
    )
    val backgroundPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val markerPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG)
    val canvas = drawContext.canvas.nativeCanvas
    placements.forEachIndexed { index, placed ->
        if (placed.width <= 0f || placed.height <= 0f) return@forEachIndexed
        val checkpoint = canvas.save()
        val rotated = labelRotations[index]
        if (rotated) {
            canvas.translate(placed.right, placed.top)
            canvas.rotate(90f)
        } else {
            canvas.translate(placed.left, placed.top)
        }
        val visibleWidth = if (rotated) placed.height else placed.width
        val visibleHeight = if (rotated) placed.width else placed.height
        canvas.clipRect(0f, 0f, visibleWidth, visibleHeight)
        val labelColor = labels[index].color.toArgb()
        val selected = labels[index].selected
        backgroundPaint.color = multiplyArgbAlpha(
            backgroundColor,
            if (selected) 0.88f else nonFocusedAlpha,
        )
        canvas.drawRoundRect(
            AndroidRectF(0f, 0f, visibleWidth, visibleHeight),
            6.dp.toPx(),
            6.dp.toPx(),
            backgroundPaint,
        )
        markerPaint.color = multiplyArgbAlpha(
            labelColor,
            if (selected) 0.92f else nonFocusedAlpha,
        )
        canvas.drawCircle(
            innerPaddingX + markerDiameter / 2f,
            boxHeight / 2f,
            markerDiameter / 2f,
            markerPaint,
        )
        textPaint.color = multiplyArgbAlpha(
            editorLabelTextColor(labelColor),
            if (selected) 0.92f else nonFocusedAlpha,
        )
        canvas.drawText(
            ellipsizeCanvasLabel(
                text = texts[index],
                paint = textPaint,
                availableWidth = visibleWidth - textStart - innerPaddingX,
            ),
            textStart,
            innerPaddingY - metrics.ascent,
            textPaint,
        )
        canvas.restoreToCount(checkpoint)
    }
}

private fun ellipsizeCanvasLabel(
    text: String,
    paint: AndroidPaint,
    availableWidth: Float,
): String {
    if (availableWidth <= 0f) return ""
    if (paint.measureText(text) <= availableWidth + CANVAS_LABEL_WIDTH_TOLERANCE_PX) return text
    val suffix = "..."
    val suffixWidth = paint.measureText(suffix)
    if (suffixWidth >= availableWidth) return ""
    val count = paint.breakText(text, true, availableWidth - suffixWidth, null)
    return text.take(count) + suffix
}

private fun insetEditorLabelRect(rect: EditorFloatRect, inset: Float): EditorFloatRect {
    val horizontal = minOf(inset.coerceAtLeast(0f), (rect.width - 1f).coerceAtLeast(0f) / 2f)
    val vertical = minOf(inset.coerceAtLeast(0f), (rect.height - 1f).coerceAtLeast(0f) / 2f)
    return EditorFloatRect(
        left = rect.left + horizontal,
        top = rect.top + vertical,
        right = rect.right - horizontal,
        bottom = rect.bottom - vertical,
    )
}

private fun multiplyArgbAlpha(color: Int, multiplier: Float): Int {
    val alpha = ((color ushr 24) * multiplier.coerceIn(0f, 1f)).roundToInt()
        .coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (alpha shl 24)
}

private val RESIZE_HANDLE_INSET = 20.dp
private val RESIZE_HANDLE_TOUCH_RADIUS = 14.dp
private val GESTURE_LINE_WIDTH = 1.5.dp
private val GESTURE_START_RADIUS = 5.5.dp
private val GESTURE_ARROW_LENGTH = 12.dp
private val GESTURE_ARROW_HALF_WIDTH = 6.dp
private val GESTURE_HANDLE_INSET = 20.dp
private val GESTURE_HANDLE_RADIUS = 8.dp
private val GESTURE_LABEL_PADDING_X = 7.dp
private val GESTURE_LABEL_PADDING_Y = 4.dp
private val GESTURE_LABEL_GAP = 6.dp
private val GESTURE_LABEL_CORNER = 6.dp
private val GESTURE_OUTER_PADDING = 5.dp
private const val CANVAS_LABEL_WIDTH_TOLERANCE_PX = 0.5f

internal data class TemplateEditorRegionColors(
    val capture: Color,
    val stitch: Color,
    val gesture: Color,
    val fixed1: Color,
    val fixed2: Color,
    val preview: Color,
)

internal fun templateEditorRegionColors(darkTheme: Boolean): TemplateEditorRegionColors =
    if (darkTheme) {
        TemplateEditorRegionColors(
            capture = Color(0xFF64B5F6),
            stitch = Color(0xFF81C784),
            gesture = Color(0xFFFFD54F),
            fixed1 = Color(0xFFF06292),
            fixed2 = Color(0xFFCE93D8),
            preview = Color(0xFF80CBC4),
        )
    } else {
        TemplateEditorRegionColors(
            capture = Color(0xFF1565C0),
            stitch = Color(0xFF2E7D32),
            gesture = Color(0xFFA15C00),
            fixed1 = Color(0xFFAD1457),
            fixed2 = Color(0xFF6A1B9A),
            preview = Color(0xFF00838F),
        )
    }

internal fun editorLabelTextColor(regionColor: Int): Int = regionColor

@Composable
private fun selectedTargetDescription(
    template: GameCaptureTemplate,
    target: TemplateEditTarget,
): String = when (target) {
    TemplateEditTarget.CAPTURE -> template.captureRegion.description()
    TemplateEditTarget.STITCH -> template.stitchRegion.description()
    TemplateEditTarget.GESTURE -> template.gestureRegion.description()
    TemplateEditTarget.PREVIEW -> {
        val x = (template.previewCenterXRatio * 100).roundToInt()
        val y = (template.previewCenterYRatio * 100).roundToInt()
        stringResource(
            R.string.template_editor_preview_description,
            template.previewWidthDp,
            template.previewMaxHeightDp,
            x,
            y,
        )
    }
    TemplateEditTarget.FIXED_1, TemplateEditTarget.FIXED_2 ->
        target.fixedLayerIndex
            ?.let(template.fixedLayers::getOrNull)
            ?.repeatRegion
            ?.description()
            ?: stringResource(R.string.template_editor_not_set)
}

private fun RectConfig.description(): String =
    "$left, $top · ${width} × $height"

@Composable
private fun directionShortLabel(direction: ScrollDirection): String = stringResource(when (direction) {
    ScrollDirection.UP -> R.string.template_editor_direction_up
    ScrollDirection.DOWN -> R.string.template_editor_direction_down
    ScrollDirection.LEFT -> R.string.template_editor_direction_left
    ScrollDirection.RIGHT -> R.string.template_editor_direction_right
})

@Composable
private fun fixedSideShortLabel(side: FixedSide): String = stringResource(when (side) {
    FixedSide.TOP -> R.string.template_editor_direction_up
    FixedSide.BOTTOM -> R.string.template_editor_direction_down
    FixedSide.LEFT -> R.string.template_editor_direction_left
    FixedSide.RIGHT -> R.string.template_editor_direction_right
})

@Composable
private fun fixedSideRegionLabel(side: FixedSide): String = stringResource(when (side) {
    FixedSide.TOP -> R.string.template_editor_top_fixed_band
    FixedSide.BOTTOM -> R.string.template_editor_bottom_fixed_band
    FixedSide.LEFT -> R.string.template_editor_left_fixed_band
    FixedSide.RIGHT -> R.string.template_editor_right_fixed_band
})

@Composable
private fun fixedAnchorBlockLabel(position: FixedAnchorPosition, vertical: Boolean): String =
    stringResource(when (position) {
        FixedAnchorPosition.LEADING -> if (vertical) {
            R.string.template_editor_top_anchor_block
        } else {
            R.string.template_editor_left_anchor_block
        }
        FixedAnchorPosition.TRAILING -> if (vertical) {
            R.string.template_editor_bottom_anchor_block
        } else {
            R.string.template_editor_right_anchor_block
        }
    })

@Composable
private fun fixedAnchorPositionShortLabel(
    position: FixedAnchorPosition,
    vertical: Boolean,
): String = stringResource(when (position) {
    FixedAnchorPosition.LEADING -> if (vertical) {
        R.string.template_editor_direction_up
    } else {
        R.string.template_editor_direction_left
    }
    FixedAnchorPosition.TRAILING -> if (vertical) {
        R.string.template_editor_direction_down
    } else {
        R.string.template_editor_direction_right
    }
})

@Composable
private fun orientationLabel(template: GameCaptureTemplate): String =
    ScreenOrientation.fromSerialized(
        template.orientation,
        template.screenWidth,
        template.screenHeight,
    ).let { screenOrientationLabel(it) }

@Composable
private fun screenOrientationLabel(orientation: ScreenOrientation): String = stringResource(
    when (orientation) {
        ScreenOrientation.LANDSCAPE -> R.string.template_editor_orientation_landscape
        ScreenOrientation.PORTRAIT -> R.string.template_editor_orientation_portrait
    },
)
