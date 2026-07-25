package com.apricotiris.IrisShot.result

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.export.encodedImageWidth
import com.apricotiris.IrisShot.core.model.FixedAnchorPosition
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.ScrollExportRegion
import com.apricotiris.IrisShot.ui.AppTopBar
import com.apricotiris.IrisShot.ui.AppButtonHeight
import com.apricotiris.IrisShot.ui.ErrorState
import com.apricotiris.IrisShot.ui.LoadingState
import com.apricotiris.IrisShot.ui.PrimaryButtonColors
import com.apricotiris.IrisShot.ui.SettingsSectionHeader
import com.apricotiris.IrisShot.ui.SettingsSliderItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

data class SourceFrameUi(
    val index: Int,
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

data class ResultEditorUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val resultId: String = "",
    val sessionId: String = "",
    val topBitmap: Bitmap? = null,
    val scrollBitmap: Bitmap? = null,
    val scrollViewportPreview: ScrollViewportPreview? = null,
    val bottomBitmap: Bitmap? = null,
    val topHeightPixels: Int = 0,
    val topWidthPixels: Int = 0,
    val scrollWidthPixels: Int = 0,
    val scrollHeightPixels: Int = 0,
    val bottomHeightPixels: Int = 0,
    val bottomWidthPixels: Int = 0,
    val firstFixedSide: FixedSide? = null,
    val secondFixedSide: FixedSide? = null,
    val topFixedAnchorCount: Int = 1,
    val topFixedAnchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
    val topFixedRepeatRegion: RectConfig? = null,
    val bottomFixedAnchorCount: Int = 1,
    val bottomFixedAnchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
    val bottomFixedRepeatRegion: RectConfig? = null,
    /** New-format sources already apply corner ownership: parallel bands exclude end-cap corners. */
    val fixedBandsOwnCorners: Boolean = true,
    val horizontal: Boolean = false,
    val frames: List<SourceFrameUi> = emptyList(),
    val range: ClosedFloatingPointRange<Float> = 0f..1f,
    val scrollExportRegions: List<ScrollExportRegion> = emptyList(),
    val maximumScrollSegmentLength: Int = Int.MAX_VALUE,
    val minimumScrollSegmentLength: Int = 1,
    val includeTop: Boolean = true,
    val includeBottom: Boolean = true,
    val busyMessage: String? = null,
    val busyCancellable: Boolean = false,
    val statusMessage: String? = null,
    val canShare: Boolean = false,
    val hasMatchingExport: Boolean = false,
    val outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
    val jpegQuality: Int = ImageOutputFormat.DEFAULT_JPEG_QUALITY,
) {
    val scrollWidth: Int get() = scrollWidthPixels.takeIf { it > 0 } ?: scrollBitmap?.width ?: 0
    val scrollHeight: Int get() = scrollHeightPixels.takeIf { it > 0 } ?: scrollBitmap?.height ?: 0
    val axisLength: Int get() = if (horizontal) scrollWidth else scrollHeight
    val effectiveScrollExportRegions: List<ScrollExportRegion>
        get() = scrollExportRegions.takeIf { it.isNotEmpty() } ?: listOf(
            ScrollExportRegion(
                start = (axisLength * range.start).roundToInt().coerceIn(0, axisLength),
                end = (axisLength * range.endInclusive).roundToInt().coerceIn(0, axisLength),
            ),
        ).filter { it.end > it.start }
    val enabledScrollExportRegions: List<ScrollExportRegion>
        get() = effectiveScrollExportRegions.filter(ScrollExportRegion::enabled)
    val selectedStart: Int get() = effectiveScrollExportRegions.firstOrNull()?.start ?: 0
    val selectedEnd: Int get() = effectiveScrollExportRegions.lastOrNull()?.end ?: axisLength
    val selectedLength: Int get() = (selectedEnd - selectedStart).coerceAtLeast(0)
    val maximumSelectedRegionLength: Int
        get() = enabledScrollExportRegions.maxOfOrNull(ScrollExportRegion::length) ?: 0
    val exportCount: Int get() = enabledScrollExportRegions.size
    val finalWidth: Int
        get() = encodedImageWidth(
            outputFormat,
            (if (horizontal) maximumSelectedRegionLength else scrollWidth) +
                fixedWidth(firstFixedSide ?: if (horizontal) FixedSide.LEFT else FixedSide.TOP, includeTop, topWidthPixels, topBitmap) +
                fixedWidth(secondFixedSide ?: if (horizontal) FixedSide.RIGHT else FixedSide.BOTTOM, includeBottom, bottomWidthPixels, bottomBitmap),
        )
    val finalHeight: Int
        get() = (if (horizontal) scrollHeight else maximumSelectedRegionLength) +
            fixedHeight(firstFixedSide ?: if (horizontal) FixedSide.LEFT else FixedSide.TOP, includeTop, topHeightPixels, topBitmap) +
            fixedHeight(secondFixedSide ?: if (horizontal) FixedSide.RIGHT else FixedSide.BOTTOM, includeBottom, bottomHeightPixels, bottomBitmap)

    private fun fixedWidth(side: FixedSide?, included: Boolean, width: Int, bitmap: Bitmap?): Int =
        if (included && side in setOf(FixedSide.LEFT, FixedSide.RIGHT)) {
            width.takeIf { it > 0 } ?: bitmap?.width ?: 0
        } else {
            0
        }

    private fun fixedHeight(side: FixedSide?, included: Boolean, height: Int, bitmap: Bitmap?): Int =
        if (included && side in setOf(FixedSide.TOP, FixedSide.BOTTOM)) {
            height.takeIf { it > 0 } ?: bitmap?.height ?: 0
        } else {
            0
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultEditorScreen(
    state: ResultEditorUiState,
    compact: Boolean,
    onBack: () -> Unit,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onRangeFinished: () -> Unit,
    onScrollExportRegionsChange: ((List<ScrollExportRegion>) -> Unit)? = null,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit = {},
    onIncludeTop: (Boolean) -> Unit,
    onIncludeBottom: (Boolean) -> Unit,
    onRequestFramePreview: (Int) -> Unit,
    onReleaseFramePreview: (Int) -> Unit,
    onExport: () -> Unit,
    onViewExport: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onCancelBusy: () -> Unit,
    onStatusConsumed: () -> Unit,
    onOutputFormatChange: (ImageOutputFormat) -> Unit = {},
    onJpegQualityChange: (Int) -> Unit = {},
    exportLocation: String = "Pictures/GameLongShot",
    onExportLocationClick: () -> Unit = {},
    onExportLocationDoubleClick: () -> Unit = {},
    onExportLocationLongClick: () -> Unit = {},
    onExpectedSizeLongClick: () -> Unit = {},
) {
    var sourceFramesExpanded by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            onStatusConsumed()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = stringResource(R.string.result_editor_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.result_action_back),
                        )
                    }
                },
                actions = {
                    if (state.canShare) {
                        IconButton(onClick = onShare) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = stringResource(R.string.result_action_share),
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.loading -> LoadingState(
                    message = stringResource(R.string.result_editor_loading),
                )
                state.error != null -> ErrorState(error = state.error, onBack = onBack, onRetry = onRetry)
                state.scrollBitmap == null -> ErrorState(
                    error = stringResource(R.string.result_editor_missing_data),
                    onBack = onBack,
                    onRetry = onRetry,
                )
                
                else -> {
                    if (compact) {
                        LayeredPreviewList(
                            state = state,
                            onRangeChange = onRangeChange,
                            onRangeFinished = onRangeFinished,
                            onScrollExportRegionsChange = onScrollExportRegionsChange,
                            onScrollViewportPreviewRequest = onScrollViewportPreviewRequest,
                            onIncludeTop = onIncludeTop,
                            onIncludeBottom = onIncludeBottom,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(RESULT_EDITOR_LIST_TAG),
                            beforeItems = {
                                screenshotFormatItems(
                                    state = state,
                                    onOutputFormatChange = onOutputFormatChange,
                                    onJpegQualityChange = onJpegQualityChange,
                                )
                                exportControlItem(
                                    state = state,
                                    exportLocation = exportLocation,
                                    onExport = onExport,
                                    onViewExport = onViewExport,
                                    onExportLocationClick = onExportLocationClick,
                                    onExportLocationDoubleClick = onExportLocationDoubleClick,
                                    onExportLocationLongClick = onExportLocationLongClick,
                                    onExpectedSizeLongClick = onExpectedSizeLongClick,
                                )
                            },
                            afterItems = {
                                sourceFrameControlItems(
                                    state = state,
                                    sourceFramesExpanded = sourceFramesExpanded,
                                    onSourceFramesExpandedChange = { sourceFramesExpanded = it },
                                    onRequestFramePreview = onRequestFramePreview,
                                    onReleaseFramePreview = onReleaseFramePreview,
                                )
                            },
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LayeredPreviewList(
                                state = state,
                                onRangeChange = onRangeChange,
                                onRangeFinished = onRangeFinished,
                                onScrollExportRegionsChange = onScrollExportRegionsChange,
                                onScrollViewportPreviewRequest = onScrollViewportPreviewRequest,
                                onIncludeTop = onIncludeTop,
                                onIncludeBottom = onIncludeBottom,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .testTag(RESULT_EDITOR_LIST_TAG),
                                contentPadding = PaddingValues(bottom = 24.dp),
                            ) {
                                screenshotFormatItems(
                                    state = state,
                                    onOutputFormatChange = onOutputFormatChange,
                                    onJpegQualityChange = onJpegQualityChange,
                                )
                                exportControlItem(
                                    state = state,
                                    exportLocation = exportLocation,
                                    onExport = onExport,
                                    onViewExport = onViewExport,
                                    onExportLocationClick = onExportLocationClick,
                                    onExportLocationDoubleClick = onExportLocationDoubleClick,
                                    onExportLocationLongClick = onExportLocationLongClick,
                                    onExpectedSizeLongClick = onExpectedSizeLongClick,
                                )
                                sourceFrameControlItems(
                                    state = state,
                                    sourceFramesExpanded = sourceFramesExpanded,
                                    onSourceFramesExpandedChange = { sourceFramesExpanded = it },
                                    onRequestFramePreview = onRequestFramePreview,
                                    onReleaseFramePreview = onReleaseFramePreview,
                                )
                            }
                        }
                    }
                }
            }

            // Busy dialog
            if (state.busyMessage != null) {
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.62f))
                        .clickable(interactionSource = interactionSource, indication = null) {},
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(26.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                                Column(Modifier.padding(start = 14.dp)) {
                                    Text(
                                        state.busyMessage,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        stringResource(R.string.result_editor_processing),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (state.busyCancellable) {
                                TextButton(onClick = onCancelBusy) {
                                    Text(stringResource(R.string.result_editor_cancel_export))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The preview owns the screen's scroll container. Keeping pages in this list instead of in a
 * fixed-height nested list makes a long result scroll like the rest of the editor.
 */
@Composable
private fun LayeredPreviewList(
    state: ResultEditorUiState,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onRangeFinished: () -> Unit,
    onScrollExportRegionsChange: ((List<ScrollExportRegion>) -> Unit)?,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
    onIncludeTop: (Boolean) -> Unit,
    onIncludeBottom: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    beforeItems: LazyListScope.() -> Unit = {},
    afterItems: LazyListScope.() -> Unit = {},
) {
    if (state.scrollBitmap == null) return
    var draftRegions by remember(state.resultId) {
        mutableStateOf(state.effectiveScrollExportRegions)
    }
    LaunchedEffect(state.scrollExportRegions, state.range, state.axisLength) {
        draftRegions = state.effectiveScrollExportRegions
    }
    fun publishRegions(regions: List<ScrollExportRegion>, finished: Boolean) {
        draftRegions = regions
        if (onScrollExportRegionsChange != null) {
            onScrollExportRegionsChange(regions)
        } else if (regions.isNotEmpty() && state.axisLength > 0) {
            onRangeChange(
                regions.first().start.toFloat() / state.axisLength..
                    regions.last().end.toFloat() / state.axisLength,
            )
        }
        if (finished) onRangeFinished()
    }

    if (state.horizontal) {
        HorizontalLayeredPreviewList(
            state = state,
            draftRegions = draftRegions,
            onRegionsChange = { publishRegions(it, false) },
            onRegionsFinished = { publishRegions(it, true) },
            onScrollViewportPreviewRequest = onScrollViewportPreviewRequest,
            onIncludeTop = onIncludeTop,
            onIncludeBottom = onIncludeBottom,
            modifier = modifier,
            beforeItems = beforeItems,
            afterItems = afterItems,
        )
    } else {
        VerticalLayeredPreviewList(
            state = state,
            draftRegions = draftRegions,
            onRegionsChange = { publishRegions(it, false) },
            onRegionsFinished = { publishRegions(it, true) },
            onScrollViewportPreviewRequest = onScrollViewportPreviewRequest,
            onIncludeTop = onIncludeTop,
            onIncludeBottom = onIncludeBottom,
            modifier = modifier,
            beforeItems = beforeItems,
            afterItems = afterItems,
        )
    }
}

@Composable
private fun VerticalLayeredPreviewList(
    state: ResultEditorUiState,
    draftRegions: List<ScrollExportRegion>,
    onRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
    onIncludeTop: (Boolean) -> Unit,
    onIncludeBottom: (Boolean) -> Unit,
    modifier: Modifier,
    beforeItems: LazyListScope.() -> Unit,
    afterItems: LazyListScope.() -> Unit,
) {
    val scrollBitmap = checkNotNull(state.scrollBitmap)
    val topBitmap = state.topBitmap
    val bottomBitmap = state.bottomBitmap
    val topWidth = topBitmap?.let { state.topWidthPixels.takeIf { width -> width > 0 } ?: it.width } ?: 0
    val topHeight = topBitmap?.let { state.topHeightPixels.takeIf { height -> height > 0 } ?: it.height } ?: 0
    val bottomWidth = bottomBitmap?.let { state.bottomWidthPixels.takeIf { width -> width > 0 } ?: it.width } ?: 0
    val bottomHeight = bottomBitmap?.let { state.bottomHeightPixels.takeIf { height -> height > 0 } ?: it.height } ?: 0
    val fixedPreviews = buildList {
        if (topBitmap != null) {
            add(
                VerticalFixedPreview(
                    bitmap = topBitmap,
                    side = state.firstFixedSide ?: FixedSide.TOP,
                    width = topWidth,
                    height = topHeight,
                    anchorCount = state.topFixedAnchorCount,
                    anchorPosition = state.topFixedAnchorPosition,
                    repeatRegion = state.topFixedRepeatRegion,
                    enabled = state.includeTop,
                    onEnabledChange = onIncludeTop,
                ),
            )
        }
        if (bottomBitmap != null) {
            add(
                VerticalFixedPreview(
                    bitmap = bottomBitmap,
                    side = state.secondFixedSide ?: FixedSide.BOTTOM,
                    width = bottomWidth,
                    height = bottomHeight,
                    anchorCount = state.bottomFixedAnchorCount,
                    anchorPosition = state.bottomFixedAnchorPosition,
                    repeatRegion = state.bottomFixedRepeatRegion,
                    enabled = state.includeBottom,
                    onEnabledChange = onIncludeBottom,
                ),
            )
        }
    }
    val fixedBySide = fixedPreviews.associateBy(VerticalFixedPreview::side)
    fun parallelWindow(fixed: VerticalFixedPreview): PreviewAxisWindow =
        parallelBandPreviewWindow(
            sourceLength = fixed.height,
            repeatStart = fixed.repeatRegion?.top,
            repeatEnd = fixed.repeatRegion?.bottom,
            leadingCornerExtent = fixedBySide[FixedSide.TOP]?.height ?: 0,
            trailingCornerExtent = fixedBySide[FixedSide.BOTTOM]?.height ?: 0,
            fixedBandsOwnCorners = state.fixedBandsOwnCorners,
        )
    fun endCapWindow(fixed: VerticalFixedPreview): PreviewAxisWindow =
        endCapPreviewWindow(
            sourceLength = fixed.width,
            leadingCornerExtent = fixedBySide[FixedSide.LEFT]?.width ?: 0,
            trailingCornerExtent = fixedBySide[FixedSide.RIGHT]?.width ?: 0,
            keepLeadingCorner = fixedBySide[FixedSide.LEFT]?.enabled == true,
            keepTrailingCorner = fixedBySide[FixedSide.RIGHT]?.enabled == true,
        )
    val includedByPlacement = fixedPreviews.associateBy { verticalPreviewPlacement(it.side) }
    val top = includedByPlacement[VerticalPreviewPlacement.TOP]
    val left = includedByPlacement[VerticalPreviewPlacement.LEADING]
    val right = includedByPlacement[VerticalPreviewPlacement.TRAILING]
    val bottom = includedByPlacement[VerticalPreviewPlacement.BOTTOM]
    val axisLength = state.axisLength.coerceAtLeast(1)
    val selectedStart = draftRegions.firstOrNull()?.start ?: 0
    val selectedEnd = draftRegions.lastOrNull()?.end ?: axisLength
    val selectedLength = (selectedEnd - selectedStart).coerceAtLeast(0)
    val middleLayerLabel = stringResource(R.string.result_editor_middle_scroll_layer)
    val originalOrientationState = stringResource(R.string.result_editor_original_orientation_state)
    val verticalFittedState = stringResource(R.string.result_editor_vertical_fitted_state)
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = (maxWidth - 32.dp).coerceAtLeast(1.dp)
        val previewScale = verticalPreviewScale(
            availableWidth = availableWidth,
            scrollWidthPixels = state.scrollWidth,
            leftWidthPixels = left?.width ?: 0,
            rightWidthPixels = right?.width ?: 0,
        )
        val scrollPreviewWidth =
            (previewScale * state.scrollWidth.coerceAtLeast(1)).coerceAtLeast(1.dp)
        val leftWidth = left?.let {
            (previewScale * it.width.coerceAtLeast(1)).coerceAtLeast(1.dp)
        } ?: 0.dp
        val rightWidth = right?.let {
            (previewScale * it.width.coerceAtLeast(1)).coerceAtLeast(1.dp)
        } ?: 0.dp
        val paging = remember(state.resultId, axisLength, previewScale.value) {
            buildScrollPreviewPaging(
                axisLength = axisLength,
                naturalDisplayDpPerLogicalPixel = previewScale.value,
            )
        }
        var activeBoundaryDrag by remember(state.resultId, paging) {
            mutableStateOf<ActiveScrollBoundaryDrag?>(null)
        }
        var scrollColumnWidth by remember(paging) { mutableStateOf(0) }
        val viewportWindow = rememberScrollPreviewViewportWindow(
            paging = paging,
            listState = listState,
            targetCrossAxisPixels = scrollColumnWidth,
            onRequest = onScrollViewportPreviewRequest,
        )
        val minimumSelectionPixels = state.minimumScrollSegmentLength.coerceIn(1, axisLength)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            beforeItems()
            item(key = "layered-preview-header") {
                SettingsSectionHeader(title = stringResource(R.string.result_editor_layered_preview))
            }
            items(
                items = fixedPreviews,
                key = { fixed -> "vertical-fixed-toggle-" + fixed.side.name },
            ) { fixed ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    VerticalFixedLayerToggle(fixed)
                }
            }
            item(key = "vertical-scroll-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = middleLayerLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.result_editor_pixels_high,
                            selectedLength.coerceAtLeast(0),
                        ),
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (top != null) {
                item(key = "vertical-top-endcap") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag(VERTICAL_IMAGE_COMPOSITION_TAG),
                    ) {
                        val sourceWindow = endCapWindow(top)
                        VerticalEndCapPreview(
                            fixed = top,
                            sourceWindow = sourceWindow,
                            width = availableWidth,
                            height = fixedWidthLayerHeight(
                                previewWidth = availableWidth,
                                sourceWidth = sourceWindow.length,
                                sourceHeight = top.height,
                                fallback = previewScale * top.height.coerceAtLeast(1),
                                preserveAspect = true,
                            ).coerceAtLeast(1.dp),
                        )
                    }
                }
                item(key = "vertical-top-gap") { Spacer(Modifier.height(LAYER_PREVIEW_GAP)) }
            }
            items(
                count = paging.pageCount,
                key = { index -> scrollPreviewPageKey(state.resultId, horizontal = false, index) },
            ) { pageIndex ->
                val page = paging.page(pageIndex)
                VerticalScrollPreviewPage(
                    page = page,
                    paging = paging,
                    scrollBitmap = scrollBitmap,
                    viewportPreview = state.scrollViewportPreview,
                    viewportWindow = viewportWindow,
                    axisLength = axisLength,
                    scrollPreviewWidth = scrollPreviewWidth,
                    left = left,
                    leftWidth = leftWidth,
                    leftSourceWindow = left?.let(::parallelWindow),
                    right = right,
                    rightWidth = rightWidth,
                    rightSourceWindow = right?.let(::parallelWindow),
                    selectedStart = selectedStart,
                    selectedLength = selectedLength,
                    regions = draftRegions,
                    minimumSegmentLength = minimumSelectionPixels,
                    maximumSegmentLength = state.maximumScrollSegmentLength,
                    onRegionsChange = onRegionsChange,
                    onRegionsFinished = onRegionsFinished,
                    activeBoundaryDrag = activeBoundaryDrag,
                    onBoundaryDragStart = { boundaryIndex ->
                        activeBoundaryDrag = ActiveScrollBoundaryDrag(boundaryIndex, page.index)
                    },
                    onBoundaryDragFinished = { boundaryIndex ->
                        if (activeBoundaryDrag?.boundaryIndex == boundaryIndex) {
                            activeBoundaryDrag = null
                        }
                    },
                    onScrollColumnWidthChange = { scrollColumnWidth = it },
                    middleLayerLabel = middleLayerLabel,
                    originalOrientationState = originalOrientationState,
                    verticalFittedState = verticalFittedState,
                    showCompositionTag = top == null && page.index == 0,
                )
            }
            if (bottom != null) {
                item(key = "vertical-bottom-gap") { Spacer(Modifier.height(LAYER_PREVIEW_GAP)) }
                item(key = "vertical-bottom-endcap") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        val sourceWindow = endCapWindow(bottom)
                        VerticalEndCapPreview(
                            fixed = bottom,
                            sourceWindow = sourceWindow,
                            width = availableWidth,
                            height = fixedWidthLayerHeight(
                                previewWidth = availableWidth,
                                sourceWidth = sourceWindow.length,
                                sourceHeight = bottom.height,
                                fallback = previewScale * bottom.height.coerceAtLeast(1),
                                preserveAspect = true,
                            ).coerceAtLeast(1.dp),
                        )
                    }
                }
            }
            if (fixedPreviews.isEmpty()) {
                item(key = "vertical-no-fixed-layers") {
                    Text(
                        stringResource(R.string.result_editor_no_fixed_layers),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "vertical-preview-bottom-padding") { Spacer(Modifier.height(8.dp)) }
            afterItems()
        }
    }
}

@Composable
private fun HorizontalLayeredPreviewList(
    state: ResultEditorUiState,
    draftRegions: List<ScrollExportRegion>,
    onRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
    onIncludeTop: (Boolean) -> Unit,
    onIncludeBottom: (Boolean) -> Unit,
    modifier: Modifier,
    beforeItems: LazyListScope.() -> Unit,
    afterItems: LazyListScope.() -> Unit,
) {
    val scrollBitmap = checkNotNull(state.scrollBitmap)
    val topBitmap = state.topBitmap
    val bottomBitmap = state.bottomBitmap
    val topWidth = topBitmap?.let { state.topWidthPixels.takeIf { width -> width > 0 } ?: it.width } ?: 0
    val topHeight = topBitmap?.let { state.topHeightPixels.takeIf { height -> height > 0 } ?: it.height } ?: 0
    val bottomWidth = bottomBitmap?.let { state.bottomWidthPixels.takeIf { width -> width > 0 } ?: it.width } ?: 0
    val bottomHeight = bottomBitmap?.let { state.bottomHeightPixels.takeIf { height -> height > 0 } ?: it.height } ?: 0
    val fixedPreviews = buildList {
        if (topBitmap != null) {
            add(
                HorizontalFixedPreview(
                    bitmap = topBitmap,
                    side = state.firstFixedSide ?: FixedSide.LEFT,
                    width = topWidth,
                    height = topHeight,
                    anchorCount = state.topFixedAnchorCount,
                    anchorPosition = state.topFixedAnchorPosition,
                    repeatRegion = state.topFixedRepeatRegion,
                    enabled = state.includeTop,
                    onEnabledChange = onIncludeTop,
                ),
            )
        }
        if (bottomBitmap != null) {
            add(
                HorizontalFixedPreview(
                    bitmap = bottomBitmap,
                    side = state.secondFixedSide ?: FixedSide.RIGHT,
                    width = bottomWidth,
                    height = bottomHeight,
                    anchorCount = state.bottomFixedAnchorCount,
                    anchorPosition = state.bottomFixedAnchorPosition,
                    repeatRegion = state.bottomFixedRepeatRegion,
                    enabled = state.includeBottom,
                    onEnabledChange = onIncludeBottom,
                ),
            )
        }
    }
    val fixedBySide = fixedPreviews.associateBy(HorizontalFixedPreview::side)
    fun sourceWindow(fixed: HorizontalFixedPreview): PreviewAxisWindow =
        if (fixed.side.isParallelToHorizontalScroll()) {
            parallelBandPreviewWindow(
                sourceLength = fixed.width,
                repeatStart = fixed.repeatRegion?.left,
                repeatEnd = fixed.repeatRegion?.right,
                leadingCornerExtent = fixedBySide[FixedSide.LEFT]?.width ?: 0,
                trailingCornerExtent = fixedBySide[FixedSide.RIGHT]?.width ?: 0,
                fixedBandsOwnCorners = state.fixedBandsOwnCorners,
            )
        } else {
            endCapPreviewWindow(
                sourceLength = fixed.height,
                leadingCornerExtent = fixedBySide[FixedSide.TOP]?.height ?: 0,
                trailingCornerExtent = fixedBySide[FixedSide.BOTTOM]?.height ?: 0,
                keepLeadingCorner = fixedBySide[FixedSide.TOP]?.enabled == true,
                keepTrailingCorner = fixedBySide[FixedSide.BOTTOM]?.enabled == true,
            )
        }
    val fixedLabels = fixedPreviews.associate { fixed ->
        fixed.side to fixedLayerLabel(fixed.side)
    }
    val middleLayerLabel = stringResource(R.string.result_editor_middle_scroll_layer)
    val columns = buildList {
        fixedPreviews
            .filter { horizontalPreviewPlacement(it.side) == HorizontalPreviewPlacement.LEADING }
            .forEach { fixed ->
                add(
                    fixed.toPreviewColumn(
                        sourceWindow = sourceWindow(fixed),
                        label = fixedLabels.getValue(fixed.side),
                    ),
                )
            }
        add(
            HorizontalPreviewColumn(
                bitmap = scrollBitmap,
                label = middleLayerLabel,
                rotated = true,
                mainAxisPixels = state.scrollWidth,
                crossAxisPixels = state.scrollHeight,
            ),
        )
        fixedPreviews
            .filter { horizontalPreviewPlacement(it.side) == HorizontalPreviewPlacement.TRAILING }
            .forEach { fixed ->
                add(
                    fixed.toPreviewColumn(
                        sourceWindow = sourceWindow(fixed),
                        label = fixedLabels.getValue(fixed.side),
                    ),
                )
            }
    }
    val scrollColumnIndex = columns.indexOfFirst { it.fixedSide == null }
    val axisLength = state.axisLength.coerceAtLeast(1)
    val selectedStart = draftRegions.firstOrNull()?.start ?: 0
    val selectedEnd = draftRegions.lastOrNull()?.end ?: axisLength
    val selectedLength = (selectedEnd - selectedStart).coerceAtLeast(0)
    val originalOrientationState = stringResource(R.string.result_editor_original_orientation_state)
    val rotatedOrientationState = stringResource(R.string.result_editor_rotated_state)
    val rotatedFittedState = stringResource(R.string.result_editor_rotated_fitted_state)
    val listState = rememberLazyListState()

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = (maxWidth - 32.dp).coerceAtLeast(1.dp)
        val columnWidths = horizontalPreviewColumnWidths(
            availableWidth = availableWidth,
            crossAxisPixels = columns.map(HorizontalPreviewColumn::crossAxisPixels),
            minimumWidthIndices = columns.mapIndexedNotNull { index, column ->
                index.takeIf { column.fixedSide != null }
            }.toSet(),
        )
        val fallbackScale = horizontalPreviewScale(
            availableWidth = availableWidth,
            crossAxisPixels = columns.map(HorizontalPreviewColumn::crossAxisPixels),
        )
        val previewScale = horizontalSharedPreviewScale(
            displayedWidths = columnWidths,
            logicalCrossAxisPixels = columns.map(HorizontalPreviewColumn::crossAxisPixels),
            fallback = fallbackScale,
        )
        val paging = remember(state.resultId, axisLength, previewScale.value) {
            buildScrollPreviewPaging(
                axisLength = axisLength,
                naturalDisplayDpPerLogicalPixel = previewScale.value,
            )
        }
        var activeBoundaryDrag by remember(state.resultId, paging) {
            mutableStateOf<ActiveScrollBoundaryDrag?>(null)
        }
        var scrollColumnWidth by remember(paging) { mutableStateOf(0) }
        val viewportWindow = rememberScrollPreviewViewportWindow(
            paging = paging,
            listState = listState,
            targetCrossAxisPixels = scrollColumnWidth,
            onRequest = onScrollViewportPreviewRequest,
        )
        val minimumSelectionPixels = state.minimumScrollSegmentLength.coerceIn(1, axisLength)

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            beforeItems()
            item(key = "layered-preview-header") {
                SettingsSectionHeader(title = stringResource(R.string.result_editor_layered_preview))
            }
            item(key = "horizontal-layer-titles") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(HORIZONTAL_TITLE_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(LAYER_PREVIEW_GAP),
                    verticalAlignment = Alignment.Top,
                ) {
                    columns.forEachIndexed { index, column ->
                        HorizontalLayerTitle(column = column, width = columnWidths[index])
                    }
                }
            }
            item(key = "horizontal-title-gap") { Spacer(Modifier.height(LAYER_PREVIEW_GAP)) }
            items(
                count = paging.pageCount,
                key = { index -> scrollPreviewPageKey(state.resultId, horizontal = true, index) },
            ) { pageIndex ->
                val page = paging.page(pageIndex)
                HorizontalScrollPreviewPage(
                    page = page,
                    paging = paging,
                    columns = columns,
                    columnWidths = columnWidths,
                    scrollColumnIndex = scrollColumnIndex,
                    viewportPreview = state.scrollViewportPreview,
                    viewportWindow = viewportWindow,
                    axisLength = axisLength,
                    selectedStart = selectedStart,
                    selectedLength = selectedLength,
                    regions = draftRegions,
                    minimumSegmentLength = minimumSelectionPixels,
                    maximumSegmentLength = state.maximumScrollSegmentLength,
                    onRegionsChange = onRegionsChange,
                    onRegionsFinished = onRegionsFinished,
                    activeBoundaryDrag = activeBoundaryDrag,
                    onBoundaryDragStart = { boundaryIndex ->
                        activeBoundaryDrag = ActiveScrollBoundaryDrag(boundaryIndex, page.index)
                    },
                    onBoundaryDragFinished = { boundaryIndex ->
                        if (activeBoundaryDrag?.boundaryIndex == boundaryIndex) {
                            activeBoundaryDrag = null
                        }
                    },
                    onScrollColumnWidthChange = { scrollColumnWidth = it },
                    originalOrientationState = originalOrientationState,
                    rotatedOrientationState = rotatedOrientationState,
                    rotatedFittedState = rotatedFittedState,
                )
            }
            if (fixedPreviews.isEmpty()) {
                item(key = "horizontal-no-fixed-layers") {
                    Text(
                        stringResource(R.string.result_editor_no_fixed_layers),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "horizontal-preview-bottom-padding") { Spacer(Modifier.height(8.dp)) }
            afterItems()
        }
    }
}

@Composable
private fun LegacyLayeredPreview(
    state: ResultEditorUiState,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onRangeFinished: () -> Unit,
    onScrollExportRegionsChange: ((List<ScrollExportRegion>) -> Unit)?,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
    onIncludeTop: (Boolean) -> Unit,
    onIncludeBottom: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBitmap = state.scrollBitmap ?: return
    var draftRegions by remember(state.resultId) {
        mutableStateOf(state.effectiveScrollExportRegions)
    }
    LaunchedEffect(state.scrollExportRegions, state.range, state.axisLength) {
        draftRegions = state.effectiveScrollExportRegions
    }

    val topBitmap = state.topBitmap
    val bottomBitmap = state.bottomBitmap
    val topWidth = if (topBitmap != null) state.topWidthPixels.takeIf { it > 0 } ?: topBitmap.width else 0
    val topHeight = if (topBitmap != null) state.topHeightPixels.takeIf { it > 0 } ?: topBitmap.height else 0
    val scrollWidth = state.scrollWidth
    val scrollHeight = state.scrollHeight
    val bottomWidth = if (bottomBitmap != null) state.bottomWidthPixels.takeIf { it > 0 } ?: bottomBitmap.width else 0
    val bottomHeight = if (bottomBitmap != null) state.bottomHeightPixels.takeIf { it > 0 } ?: bottomBitmap.height else 0
    val draftStart = draftRegions.firstOrNull()?.start ?: 0
    val draftEnd = draftRegions.lastOrNull()?.end ?: state.axisLength
    val selectedLength = (draftEnd - draftStart).coerceAtLeast(0)
    fun publishRegions(regions: List<ScrollExportRegion>, finished: Boolean) {
        draftRegions = regions
        if (onScrollExportRegionsChange != null) {
            onScrollExportRegionsChange(regions)
        } else if (regions.isNotEmpty() && state.axisLength > 0) {
            onRangeChange(
                regions.first().start.toFloat() / state.axisLength..
                    regions.last().end.toFloat() / state.axisLength,
            )
        }
        if (finished) onRangeFinished()
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val previewWidth = (maxWidth - 32.dp).coerceAtLeast(1.dp)
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            SettingsSectionHeader(title = stringResource(R.string.result_editor_layered_preview))
            if (state.horizontal) {
                HorizontalLayeredPreviewContent(
                    availableWidth = previewWidth,
                    topBitmap = topBitmap,
                    topSide = state.firstFixedSide,
                    topWidth = topWidth,
                    topHeight = topHeight,
                    topAnchorCount = state.topFixedAnchorCount,
                    topAnchorPosition = state.topFixedAnchorPosition,
                    topRepeatRegion = state.topFixedRepeatRegion,
                    includeTop = state.includeTop,
                    onIncludeTop = onIncludeTop,
                    scrollBitmap = scrollBitmap,
                    scrollViewportPreview = state.scrollViewportPreview,
                    scrollWidth = scrollWidth,
                    scrollHeight = scrollHeight,
                    selectedStart = draftStart,
                    selectedLength = selectedLength,
                    axisLength = state.axisLength,
                    range = draftStart.toFloat() / state.axisLength.coerceAtLeast(1)..
                        draftEnd.toFloat() / state.axisLength.coerceAtLeast(1),
                    scrollExportRegions = draftRegions,
                    maximumScrollSegmentLength = state.maximumScrollSegmentLength,
                    minimumScrollSegmentLength = state.minimumScrollSegmentLength,
                    onScrollExportRegionsChange = { publishRegions(it, false) },
                    onScrollExportRegionsFinished = { publishRegions(it, true) },
                    onScrollViewportPreviewRequest = onScrollViewportPreviewRequest,
                    bottomBitmap = bottomBitmap,
                    bottomSide = state.secondFixedSide,
                    bottomWidth = bottomWidth,
                    bottomHeight = bottomHeight,
                    bottomAnchorCount = state.bottomFixedAnchorCount,
                    bottomAnchorPosition = state.bottomFixedAnchorPosition,
                    bottomRepeatRegion = state.bottomFixedRepeatRegion,
                    includeBottom = state.includeBottom,
                    onIncludeBottom = onIncludeBottom,
                    fixedBandsOwnCorners = state.fixedBandsOwnCorners,
                )
            } else {
                VerticalLayeredPreviewContent(
                    availableWidth = previewWidth,
                    topBitmap = topBitmap,
                    topSide = state.firstFixedSide,
                    topWidth = topWidth,
                    topHeight = topHeight,
                    topAnchorCount = state.topFixedAnchorCount,
                    topAnchorPosition = state.topFixedAnchorPosition,
                    topRepeatRegion = state.topFixedRepeatRegion,
                    includeTop = state.includeTop,
                    onIncludeTop = onIncludeTop,
                    scrollBitmap = scrollBitmap,
                    scrollViewportPreview = state.scrollViewportPreview,
                    scrollWidth = scrollWidth,
                    selectedStart = draftStart,
                    selectedLength = selectedLength,
                    axisLength = state.axisLength,
                    range = draftStart.toFloat() / state.axisLength.coerceAtLeast(1)..
                        draftEnd.toFloat() / state.axisLength.coerceAtLeast(1),
                    scrollExportRegions = draftRegions,
                    maximumScrollSegmentLength = state.maximumScrollSegmentLength,
                    minimumScrollSegmentLength = state.minimumScrollSegmentLength,
                    onScrollExportRegionsChange = { publishRegions(it, false) },
                    onScrollExportRegionsFinished = { publishRegions(it, true) },
                    onScrollViewportPreviewRequest = onScrollViewportPreviewRequest,
                    bottomBitmap = bottomBitmap,
                    bottomSide = state.secondFixedSide,
                    bottomWidth = bottomWidth,
                    bottomHeight = bottomHeight,
                    bottomAnchorCount = state.bottomFixedAnchorCount,
                    bottomAnchorPosition = state.bottomFixedAnchorPosition,
                    bottomRepeatRegion = state.bottomFixedRepeatRegion,
                    includeBottom = state.includeBottom,
                    onIncludeBottom = onIncludeBottom,
                    fixedBandsOwnCorners = state.fixedBandsOwnCorners,
                )
            }

            if (topBitmap == null && bottomBitmap == null) {
                Text(
                    stringResource(R.string.result_editor_no_fixed_layers),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class VerticalFixedPreview(
    val bitmap: Bitmap,
    val side: FixedSide,
    val width: Int,
    val height: Int,
    val anchorCount: Int,
    val anchorPosition: FixedAnchorPosition,
    val repeatRegion: RectConfig?,
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
)

internal enum class VerticalPreviewPlacement {
    TOP,
    LEADING,
    TRAILING,
    BOTTOM,
}

internal fun verticalPreviewPlacement(side: FixedSide): VerticalPreviewPlacement = when (side) {
    FixedSide.TOP -> VerticalPreviewPlacement.TOP
    FixedSide.LEFT -> VerticalPreviewPlacement.LEADING
    FixedSide.RIGHT -> VerticalPreviewPlacement.TRAILING
    FixedSide.BOTTOM -> VerticalPreviewPlacement.BOTTOM
}

internal fun verticalFixedLayerRepeats(side: FixedSide): Boolean =
    side == FixedSide.LEFT || side == FixedSide.RIGHT

internal fun verticalPreviewScale(
    availableWidth: Dp,
    scrollWidthPixels: Int,
    leftWidthPixels: Int,
    rightWidthPixels: Int,
): Dp {
    val gapCount = (if (leftWidthPixels > 0) 1 else 0) +
        (if (rightWidthPixels > 0) 1 else 0)
    val imageBudget = (availableWidth - LAYER_PREVIEW_GAP * gapCount).coerceAtLeast(1.dp)
    val totalWidthPixels = scrollWidthPixels.coerceAtLeast(1).toLong() +
        leftWidthPixels.coerceAtLeast(0).toLong() +
        rightWidthPixels.coerceAtLeast(0).toLong()
    return imageBudget / totalWidthPixels.coerceAtLeast(1L).toFloat()
}

internal data class PreviewAxisWindow(
    val originalLength: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val repeatStart: Int? = null,
    val repeatEnd: Int? = null,
) {
    val length: Int get() = (sourceEnd - sourceStart).coerceAtLeast(1)
}

internal fun parallelBandPreviewWindow(
    sourceLength: Int,
    repeatStart: Int?,
    repeatEnd: Int?,
    leadingCornerExtent: Int,
    trailingCornerExtent: Int,
    fixedBandsOwnCorners: Boolean,
): PreviewAxisWindow {
    val safeSourceLength = sourceLength.coerceAtLeast(1)
    val sourceStart = if (fixedBandsOwnCorners) {
        0
    } else {
        leadingCornerExtent.coerceIn(0, safeSourceLength - 1)
    }
    val sourceEnd = if (fixedBandsOwnCorners) {
        safeSourceLength
    } else {
        (safeSourceLength - trailingCornerExtent.coerceAtLeast(0))
            .coerceIn(sourceStart + 1, safeSourceLength)
    }
    fun adjustedRepeat(value: Int?): Int? = value
        ?.coerceIn(sourceStart, sourceEnd)
        ?.minus(sourceStart)
    return PreviewAxisWindow(
        originalLength = safeSourceLength,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        repeatStart = adjustedRepeat(repeatStart),
        repeatEnd = adjustedRepeat(repeatEnd),
    )
}

internal fun endCapPreviewWindow(
    sourceLength: Int,
    leadingCornerExtent: Int,
    trailingCornerExtent: Int,
    keepLeadingCorner: Boolean,
    keepTrailingCorner: Boolean,
): PreviewAxisWindow {
    val safeSourceLength = sourceLength.coerceAtLeast(1)
    val sourceStart = if (keepLeadingCorner) {
        0
    } else {
        leadingCornerExtent.coerceIn(0, safeSourceLength - 1)
    }
    val sourceEnd = if (keepTrailingCorner) {
        safeSourceLength
    } else {
        (safeSourceLength - trailingCornerExtent.coerceAtLeast(0))
            .coerceIn(sourceStart + 1, safeSourceLength)
    }
    return PreviewAxisWindow(
        originalLength = safeSourceLength,
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
    )
}

@Composable
private fun rememberAxisWindowBitmap(
    bitmap: Bitmap,
    horizontalAxis: Boolean,
    window: PreviewAxisWindow,
): Bitmap {
    val bitmapAxisLength = if (horizontalAxis) bitmap.width else bitmap.height
    val (bitmapStart, bitmapEnd) = inwardMappedPreviewRange(
        logicalStart = window.sourceStart,
        logicalEnd = window.sourceEnd,
        logicalLength = window.originalLength,
        previewLength = bitmapAxisLength,
    )
    val windowBitmap = remember(bitmap, horizontalAxis, bitmapStart, bitmapEnd) {
        if (bitmapStart == 0 && bitmapEnd == bitmapAxisLength) {
            bitmap
        } else if (horizontalAxis) {
            Bitmap.createBitmap(
                bitmap,
                bitmapStart,
                0,
                bitmapEnd - bitmapStart,
                bitmap.height,
            )
        } else {
            Bitmap.createBitmap(
                bitmap,
                0,
                bitmapStart,
                bitmap.width,
                bitmapEnd - bitmapStart,
            )
        }
    }
    DisposableEffect(windowBitmap, bitmap) {
        onDispose {
            if (windowBitmap !== bitmap && !windowBitmap.isRecycled) {
                windowBitmap.recycle()
            }
        }
    }
    return windowBitmap
}

@Composable
private fun VerticalLayeredPreviewContent(
    availableWidth: Dp,
    topBitmap: Bitmap?,
    topSide: FixedSide?,
    topWidth: Int,
    topHeight: Int,
    topAnchorCount: Int,
    topAnchorPosition: FixedAnchorPosition,
    topRepeatRegion: RectConfig?,
    includeTop: Boolean,
    onIncludeTop: (Boolean) -> Unit,
    scrollBitmap: Bitmap,
    scrollViewportPreview: ScrollViewportPreview?,
    scrollWidth: Int,
    selectedStart: Int,
    selectedLength: Int,
    axisLength: Int,
    range: ClosedFloatingPointRange<Float>,
    scrollExportRegions: List<ScrollExportRegion>,
    maximumScrollSegmentLength: Int,
    minimumScrollSegmentLength: Int,
    onScrollExportRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onScrollExportRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
    bottomBitmap: Bitmap?,
    bottomSide: FixedSide?,
    bottomWidth: Int,
    bottomHeight: Int,
    bottomAnchorCount: Int,
    bottomAnchorPosition: FixedAnchorPosition,
    bottomRepeatRegion: RectConfig?,
    includeBottom: Boolean,
    onIncludeBottom: (Boolean) -> Unit,
    fixedBandsOwnCorners: Boolean,
) {
    val fixedPreviews = buildList {
        if (topBitmap != null) {
            add(
                VerticalFixedPreview(
                    bitmap = topBitmap,
                    side = topSide ?: FixedSide.TOP,
                    width = topWidth,
                    height = topHeight,
                    anchorCount = topAnchorCount,
                    anchorPosition = topAnchorPosition,
                    repeatRegion = topRepeatRegion,
                    enabled = includeTop,
                    onEnabledChange = onIncludeTop,
                ),
            )
        }
        if (bottomBitmap != null) {
            add(
                VerticalFixedPreview(
                    bitmap = bottomBitmap,
                    side = bottomSide ?: FixedSide.BOTTOM,
                    width = bottomWidth,
                    height = bottomHeight,
                    anchorCount = bottomAnchorCount,
                    anchorPosition = bottomAnchorPosition,
                    repeatRegion = bottomRepeatRegion,
                    enabled = includeBottom,
                    onEnabledChange = onIncludeBottom,
                ),
            )
        }
    }
    val fixedBySide = fixedPreviews.associateBy(VerticalFixedPreview::side)
    fun parallelWindow(fixed: VerticalFixedPreview): PreviewAxisWindow =
        parallelBandPreviewWindow(
            sourceLength = fixed.height,
            repeatStart = fixed.repeatRegion?.top,
            repeatEnd = fixed.repeatRegion?.bottom,
            leadingCornerExtent = fixedBySide[FixedSide.TOP]?.height ?: 0,
            trailingCornerExtent = fixedBySide[FixedSide.BOTTOM]?.height ?: 0,
            fixedBandsOwnCorners = fixedBandsOwnCorners,
        )
    fun endCapWindow(fixed: VerticalFixedPreview): PreviewAxisWindow =
        endCapPreviewWindow(
            sourceLength = fixed.width,
            leadingCornerExtent = fixedBySide[FixedSide.LEFT]?.width ?: 0,
            trailingCornerExtent = fixedBySide[FixedSide.RIGHT]?.width ?: 0,
            keepLeadingCorner = fixedBySide[FixedSide.LEFT]?.enabled == true,
            keepTrailingCorner = fixedBySide[FixedSide.RIGHT]?.enabled == true,
        )
    val includedByPlacement = fixedPreviews
        .associateBy { verticalPreviewPlacement(it.side) }
    val top = includedByPlacement[VerticalPreviewPlacement.TOP]
    val left = includedByPlacement[VerticalPreviewPlacement.LEADING]
    val right = includedByPlacement[VerticalPreviewPlacement.TRAILING]
    val bottom = includedByPlacement[VerticalPreviewPlacement.BOTTOM]
    val previewScale = verticalPreviewScale(
        availableWidth = availableWidth,
        scrollWidthPixels = scrollWidth,
        leftWidthPixels = left?.width ?: 0,
        rightWidthPixels = right?.width ?: 0,
    )
    val scrollPreviewWidth =
        (previewScale * scrollWidth.coerceAtLeast(1)).coerceAtLeast(1.dp)
    val paging = remember(axisLength, previewScale.value) {
        buildScrollPreviewPaging(
            axisLength = axisLength,
            naturalDisplayDpPerLogicalPixel = previewScale.value,
        )
    }
    val listState = rememberLazyListState()
    var scrollColumnSize by remember(paging) { mutableStateOf(IntSize.Zero) }
    val viewportWindow = rememberScrollPreviewViewportWindow(
        paging = paging,
        listState = listState,
        targetCrossAxisPixels = scrollColumnSize.width,
        onRequest = onScrollViewportPreviewRequest,
    )
    val minimumSelectionPixels = minimumScrollSegmentLength.coerceIn(1, axisLength.coerceAtLeast(1))
    val middleLayerLabel = stringResource(R.string.result_editor_middle_scroll_layer)
    val originalOrientationState = stringResource(R.string.result_editor_original_orientation_state)
    val verticalFittedState = stringResource(R.string.result_editor_vertical_fitted_state)
    val leftLayerLabel = left?.let { fixedLayerLabel(it.side) }
    val rightLayerLabel = right?.let { fixedLayerLabel(it.side) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        fixedPreviews.forEach { fixed ->
            VerticalFixedLayerToggle(fixed)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.result_editor_middle_scroll_layer),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(
                    R.string.result_editor_pixels_high,
                    selectedLength.coerceAtLeast(0),
                ),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.fillMaxWidth().clipToBounds().testTag(VERTICAL_IMAGE_COMPOSITION_TAG)) {
            if (top != null) {
                val sourceWindow = endCapWindow(top)
                VerticalEndCapPreview(
                    fixed = top,
                    sourceWindow = sourceWindow,
                    width = availableWidth,
                    height = fixedWidthLayerHeight(
                        previewWidth = availableWidth,
                        sourceWidth = sourceWindow.length,
                        sourceHeight = top.height,
                        fallback = previewScale * top.height.coerceAtLeast(1),
                        preserveAspect = true,
                    ).coerceAtLeast(1.dp),
                )
            }
            val leftWidth = left?.let {
                (previewScale * it.width.coerceAtLeast(1)).coerceAtLeast(1.dp)
            } ?: 0.dp
            val rightWidth = right?.let {
                (previewScale * it.width.coerceAtLeast(1)).coerceAtLeast(1.dp)
            } ?: 0.dp
            val scrollOffset = leftWidth + if (left != null) LAYER_PREVIEW_GAP else 0.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(paging.viewportHeightDp.dp)
                    .clipToBounds(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    items(
                        count = paging.pageCount,
                        key = { index -> "scroll-page-$index" },
                    ) { pageIndex ->
                        val page = paging.page(pageIndex)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(page.displayHeightDp.dp),
                            horizontalArrangement = Arrangement.spacedBy(LAYER_PREVIEW_GAP),
                        ) {
                            if (left != null) {
                                RepeatedVerticalFixedBand(
                                    fixed = left,
                                    sourceWindow = parallelWindow(left),
                                    targetLength = selectedLength,
                                    targetStart = selectedStart,
                                    windowStart = page.start,
                                    windowEnd = page.end,
                                    width = leftWidth,
                                    height = page.displayHeightDp.dp,
                                )
                            }
                            VerticalScrollOverviewPage(
                                bitmap = scrollBitmap,
                                axisLength = axisLength,
                                page = page,
                                width = scrollPreviewWidth,
                                height = page.displayHeightDp.dp,
                            )
                            if (right != null) {
                                RepeatedVerticalFixedBand(
                                    fixed = right,
                                    sourceWindow = parallelWindow(right),
                                    targetLength = selectedLength,
                                    targetStart = selectedStart,
                                    windowStart = page.start,
                                    windowEnd = page.end,
                                    width = rightWidth,
                                    height = page.displayHeightDp.dp,
                                )
                            }
                        }
                    }
                }
                if (left != null) {
                    Box(
                        Modifier
                            .width(leftWidth)
                            .fillMaxHeight()
                            .testTag("$leftLayerLabel-image")
                            .semantics {
                                contentDescription = checkNotNull(leftLayerLabel)
                                stateDescription = verticalFittedState
                            },
                    )
                }
                Box(
                    modifier = Modifier
                        .offset(x = scrollOffset)
                        .width(scrollPreviewWidth)
                        .fillMaxHeight()
                        .onSizeChanged { scrollColumnSize = it }
                        .testTag("$middleLayerLabel-image")
                        .semantics {
                            contentDescription = middleLayerLabel
                            stateDescription = originalOrientationState
                        },
                ) {
                    ScrollViewportPreviewInViewport(
                        preview = scrollViewportPreview,
                        horizontal = false,
                        axisLength = axisLength,
                        viewportWindow = viewportWindow,
                        width = scrollPreviewWidth,
                        viewportHeight = paging.viewportHeightDp.dp,
                        label = stringResource(R.string.result_editor_middle_scroll_layer),
                    )
                    VirtualizedSegmentedCropControls(
                        regions = scrollExportRegions,
                        axisLength = axisLength,
                        viewportWindow = viewportWindow,
                        containerHeight = paging.viewportHeightDp.dp,
                        minimumSegmentLength = minimumSelectionPixels,
                        maximumSegmentLength = maximumScrollSegmentLength,
                        onRegionsChange = onScrollExportRegionsChange,
                        onRegionsFinished = onScrollExportRegionsFinished,
                        modifier = Modifier.fillMaxSize().testTag(VERTICAL_CROP_OVERLAY_TAG),
                    )
                }
                if (right != null) {
                    Box(
                        Modifier
                            .offset(x = scrollOffset + scrollPreviewWidth + LAYER_PREVIEW_GAP)
                            .width(rightWidth)
                            .fillMaxHeight()
                            .testTag("$rightLayerLabel-image")
                            .semantics {
                                contentDescription = checkNotNull(rightLayerLabel)
                                stateDescription = verticalFittedState
                            },
                    )
                }
            }
            if (bottom != null) {
                val sourceWindow = endCapWindow(bottom)
                VerticalEndCapPreview(
                    fixed = bottom,
                    sourceWindow = sourceWindow,
                    width = availableWidth,
                    height = fixedWidthLayerHeight(
                        previewWidth = availableWidth,
                        sourceWidth = sourceWindow.length,
                        sourceHeight = bottom.height,
                        fallback = previewScale * bottom.height.coerceAtLeast(1),
                        preserveAspect = true,
                    ).coerceAtLeast(1.dp),
                )
            }
        }
    }
}

@Composable
private fun VerticalFixedLayerToggle(fixed: VerticalFixedPreview) {
    val label = fixedLayerLabel(fixed.side)
    val extent = if (verticalFixedLayerRepeats(fixed.side)) fixed.width else fixed.height
    val dimension = stringResource(
        if (verticalFixedLayerRepeats(fixed.side)) {
            R.string.result_editor_dimension_width
        } else {
            R.string.result_editor_dimension_height
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$label-toggle")
            .toggleable(
                value = fixed.enabled,
                role = Role.Checkbox,
                onValueChange = fixed.onEnabledChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                R.string.result_editor_pixel_extent,
                extent.coerceAtLeast(0),
                dimension,
            ),
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Checkbox(
            checked = fixed.enabled,
            onCheckedChange = null,
            modifier = Modifier
                .size(24.dp)
                .testTag("$label-checkbox"),
        )
    }
}

@Composable
private fun VerticalEndCapPreview(
    fixed: VerticalFixedPreview,
    sourceWindow: PreviewAxisWindow,
    width: Dp,
    height: Dp,
) {
    val previewBitmap = rememberAxisWindowBitmap(
        bitmap = fixed.bitmap,
        horizontalAxis = true,
        window = sourceWindow,
    )
    RotatedLayerBitmap(
        bitmap = previewBitmap,
        label = fixedLayerLabel(fixed.side),
        rotated = false,
        width = width,
        height = height,
        alpha = if (fixed.enabled) 1f else DISABLED_LAYER_ALPHA,
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
private fun VerticalScrollOverviewPage(
    bitmap: Bitmap,
    axisLength: Int,
    page: ScrollPreviewPage,
    width: Dp,
    height: Dp,
) {
    val pageBitmap = rememberAxisWindowBitmap(
        bitmap = bitmap,
        horizontalAxis = false,
        window = PreviewAxisWindow(
            originalLength = axisLength,
            sourceStart = page.start,
            sourceEnd = page.end,
        ),
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        RotatedLayerBitmapContent(
            bitmap = pageBitmap,
            label = stringResource(R.string.result_editor_middle_scroll_layer),
            rotated = false,
            width = width,
            height = height,
            alpha = 1f,
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
private fun VerticalScrollPreviewPage(
    page: ScrollPreviewPage,
    paging: ScrollPreviewPaging,
    scrollBitmap: Bitmap,
    viewportPreview: ScrollViewportPreview?,
    viewportWindow: ScrollPreviewViewportWindow?,
    axisLength: Int,
    scrollPreviewWidth: Dp,
    left: VerticalFixedPreview?,
    leftWidth: Dp,
    leftSourceWindow: PreviewAxisWindow?,
    right: VerticalFixedPreview?,
    rightWidth: Dp,
    rightSourceWindow: PreviewAxisWindow?,
    selectedStart: Int,
    selectedLength: Int,
    regions: List<ScrollExportRegion>,
    minimumSegmentLength: Int,
    maximumSegmentLength: Int,
    onRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    activeBoundaryDrag: ActiveScrollBoundaryDrag?,
    onBoundaryDragStart: (Int) -> Unit,
    onBoundaryDragFinished: (Int) -> Unit,
    onScrollColumnWidthChange: (Int) -> Unit,
    middleLayerLabel: String,
    originalOrientationState: String,
    verticalFittedState: String,
    showCompositionTag: Boolean,
) {
    val pageHeight = page.displayHeightDp.dp
    val leftLabel = left?.let { fixedLayerLabel(it.side) }
    val rightLabel = right?.let { fixedLayerLabel(it.side) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(pageHeight)
            .zIndex(if (activeBoundaryDrag?.ownerPageIndex == page.index) 1f else 0f)
            .then(if (showCompositionTag) Modifier.testTag(VERTICAL_IMAGE_COMPOSITION_TAG) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(LAYER_PREVIEW_GAP),
    ) {
        if (left != null && leftSourceWindow != null) {
            RepeatedVerticalFixedBand(
                fixed = left,
                sourceWindow = leftSourceWindow,
                targetLength = selectedLength,
                targetStart = selectedStart,
                windowStart = page.start,
                windowEnd = page.end,
                width = leftWidth,
                height = pageHeight,
                modifier = if (page.index == 0) {
                    Modifier
                        .testTag("$leftLabel-image")
                        .semantics {
                            contentDescription = checkNotNull(leftLabel)
                            stateDescription = verticalFittedState
                        }
                } else {
                    Modifier
                },
            )
        }
        Box(
            modifier = Modifier
                .width(scrollPreviewWidth)
                .height(pageHeight)
                .onSizeChanged { onScrollColumnWidthChange(it.width) }
                .then(
                    if (page.index == 0) {
                        Modifier
                            .testTag("$middleLayerLabel-image")
                            .semantics {
                                contentDescription = middleLayerLabel
                                stateDescription = originalOrientationState
                            }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                VerticalScrollOverviewPage(
                    bitmap = scrollBitmap,
                    axisLength = axisLength,
                    page = page,
                    width = scrollPreviewWidth,
                    height = pageHeight,
                )
                if (viewportWindow != null) {
                    ScrollViewportPreviewInPage(
                        preview = viewportPreview,
                        horizontal = false,
                        page = page,
                        width = scrollPreviewWidth,
                        height = pageHeight,
                        label = middleLayerLabel,
                    )
                }
            }
            SegmentedCropControlsForPage(
                regions = regions,
                page = page,
                paging = paging,
                minimumSegmentLength = minimumSegmentLength,
                maximumSegmentLength = maximumSegmentLength,
                onRegionsChange = onRegionsChange,
                onRegionsFinished = onRegionsFinished,
                activeBoundaryDrag = activeBoundaryDrag,
                onBoundaryDragStart = onBoundaryDragStart,
                onBoundaryDragFinished = onBoundaryDragFinished,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (page.index == 0) Modifier.testTag(VERTICAL_CROP_OVERLAY_TAG) else Modifier),
            )
        }
        if (right != null && rightSourceWindow != null) {
            RepeatedVerticalFixedBand(
                fixed = right,
                sourceWindow = rightSourceWindow,
                targetLength = selectedLength,
                targetStart = selectedStart,
                windowStart = page.start,
                windowEnd = page.end,
                width = rightWidth,
                height = pageHeight,
                modifier = if (page.index == 0) {
                    Modifier
                        .testTag("$rightLabel-image")
                        .semantics {
                            contentDescription = checkNotNull(rightLabel)
                            stateDescription = verticalFittedState
                        }
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun HorizontalLayeredPreviewContent(
    availableWidth: Dp,
    topBitmap: Bitmap?,
    topSide: FixedSide?,
    topWidth: Int,
    topHeight: Int,
    topAnchorCount: Int,
    topAnchorPosition: FixedAnchorPosition,
    topRepeatRegion: RectConfig?,
    includeTop: Boolean,
    onIncludeTop: (Boolean) -> Unit,
    scrollBitmap: Bitmap,
    scrollViewportPreview: ScrollViewportPreview?,
    scrollWidth: Int,
    scrollHeight: Int,
    selectedStart: Int,
    selectedLength: Int,
    axisLength: Int,
    range: ClosedFloatingPointRange<Float>,
    scrollExportRegions: List<ScrollExportRegion>,
    maximumScrollSegmentLength: Int,
    minimumScrollSegmentLength: Int,
    onScrollExportRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onScrollExportRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
    bottomBitmap: Bitmap?,
    bottomSide: FixedSide?,
    bottomWidth: Int,
    bottomHeight: Int,
    bottomAnchorCount: Int,
    bottomAnchorPosition: FixedAnchorPosition,
    bottomRepeatRegion: RectConfig?,
    includeBottom: Boolean,
    onIncludeBottom: (Boolean) -> Unit,
    fixedBandsOwnCorners: Boolean,
) {
    val resolvedTopSide = topSide ?: FixedSide.LEFT
    val resolvedBottomSide = bottomSide ?: FixedSide.RIGHT
    val fixedPreviews = buildList {
        if (topBitmap != null) add(
            HorizontalFixedPreview(
                bitmap = topBitmap,
                side = resolvedTopSide,
                width = topWidth,
                height = topHeight,
                anchorCount = topAnchorCount,
                anchorPosition = topAnchorPosition,
                repeatRegion = topRepeatRegion,
                enabled = includeTop,
                onEnabledChange = onIncludeTop,
            ),
        )
        if (bottomBitmap != null) add(
            HorizontalFixedPreview(
                bitmap = bottomBitmap,
                side = resolvedBottomSide,
                width = bottomWidth,
                height = bottomHeight,
                anchorCount = bottomAnchorCount,
                anchorPosition = bottomAnchorPosition,
                repeatRegion = bottomRepeatRegion,
                enabled = includeBottom,
                onEnabledChange = onIncludeBottom,
            ),
        )
    }
    val fixedLayerLabels = fixedPreviews.associate { preview ->
        preview.side to fixedLayerLabel(preview.side)
    }
    val fixedBySide = fixedPreviews.associateBy(HorizontalFixedPreview::side)
    fun sourceWindow(fixed: HorizontalFixedPreview): PreviewAxisWindow =
        if (fixed.side.isParallelToHorizontalScroll()) {
            parallelBandPreviewWindow(
                sourceLength = fixed.width,
                repeatStart = fixed.repeatRegion?.left,
                repeatEnd = fixed.repeatRegion?.right,
                leadingCornerExtent = fixedBySide[FixedSide.LEFT]?.width ?: 0,
                trailingCornerExtent = fixedBySide[FixedSide.RIGHT]?.width ?: 0,
                fixedBandsOwnCorners = fixedBandsOwnCorners,
            )
        } else {
            endCapPreviewWindow(
                sourceLength = fixed.height,
                leadingCornerExtent = fixedBySide[FixedSide.TOP]?.height ?: 0,
                trailingCornerExtent = fixedBySide[FixedSide.BOTTOM]?.height ?: 0,
                keepLeadingCorner = fixedBySide[FixedSide.TOP]?.enabled == true,
                keepTrailingCorner = fixedBySide[FixedSide.BOTTOM]?.enabled == true,
            )
        }
    val columns = buildList {
        fixedPreviews
            .filter { horizontalPreviewPlacement(it.side) == HorizontalPreviewPlacement.LEADING }
            .forEach {
                add(
                    it.toPreviewColumn(
                        sourceWindow = sourceWindow(it),
                        label = fixedLayerLabels.getValue(it.side),
                    ),
                )
            }
        add(
            HorizontalPreviewColumn(
                bitmap = scrollBitmap,
                label = stringResource(R.string.result_editor_middle_scroll_layer),
                rotated = true,
                mainAxisPixels = scrollWidth,
                crossAxisPixels = scrollHeight,
            ),
        )
        fixedPreviews
            .filter { horizontalPreviewPlacement(it.side) == HorizontalPreviewPlacement.TRAILING }
            .forEach {
                add(
                    it.toPreviewColumn(
                        sourceWindow = sourceWindow(it),
                        label = fixedLayerLabels.getValue(it.side),
                    ),
                )
            }
    }
    val columnWidths = horizontalPreviewColumnWidths(
        availableWidth = availableWidth,
        crossAxisPixels = columns.map { it.crossAxisPixels },
        minimumWidthIndices = columns.mapIndexedNotNull { index, column ->
            index.takeIf { column.fixedSide != null }
        }.toSet(),
    )
    val fallbackPreviewScale = horizontalPreviewScale(
        availableWidth = availableWidth,
        crossAxisPixels = columns.map { it.crossAxisPixels },
    )
    val scrollColumnIndex = columns.indexOfFirst { it.fixedSide == null }
    val previewScale = horizontalSharedPreviewScale(
        displayedWidths = columnWidths,
        logicalCrossAxisPixels = columns.map(HorizontalPreviewColumn::crossAxisPixels),
        fallback = fallbackPreviewScale,
    )
    val paging = remember(axisLength, previewScale.value) {
        buildScrollPreviewPaging(
            axisLength = axisLength,
            naturalDisplayDpPerLogicalPixel = previewScale.value,
        )
    }
    val listState = rememberLazyListState()
    var scrollColumnSize by remember(paging) { mutableStateOf(IntSize.Zero) }
    val viewportWindow = rememberScrollPreviewViewportWindow(
        paging = paging,
        listState = listState,
        targetCrossAxisPixels = scrollColumnSize.width,
        onRequest = onScrollViewportPreviewRequest,
    )
    val minimumSelectionPixels = minimumScrollSegmentLength.coerceIn(1, axisLength.coerceAtLeast(1))
    val middleLayerLabel = stringResource(R.string.result_editor_middle_scroll_layer)
    val originalOrientationState = stringResource(R.string.result_editor_original_orientation_state)
    val rotatedOrientationState = stringResource(R.string.result_editor_rotated_state)
    val rotatedFittedState = stringResource(R.string.result_editor_rotated_fitted_state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(HORIZONTAL_TITLE_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(LAYER_PREVIEW_GAP),
            verticalAlignment = Alignment.Top,
        ) {
            columns.forEachIndexed { index, column ->
                HorizontalLayerTitle(
                    column = column,
                    width = columnWidths[index],
                )
            }
        }

        Spacer(Modifier.height(LAYER_PREVIEW_GAP))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(paging.viewportHeightDp.dp)
                .clipToBounds()
                .testTag(HORIZONTAL_IMAGE_STRIP_TAG),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(
                    count = paging.pageCount,
                    key = { index -> "horizontal-scroll-page-$index" },
                ) { pageIndex ->
                    val page = paging.page(pageIndex)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(page.displayHeightDp.dp),
                        horizontalArrangement = Arrangement.spacedBy(LAYER_PREVIEW_GAP),
                    ) {
                        columns.forEachIndexed { index, column ->
                            val width = columnWidths[index]
                            when {
                                column.fixedSide == null -> HorizontalScrollOverviewPage(
                                    column = column,
                                    axisLength = axisLength,
                                    page = page,
                                    width = width,
                                    height = page.displayHeightDp.dp,
                                )
                                column.fixedSide.isParallelToHorizontalScroll() -> {
                                    RepeatedHorizontalFixedBand(
                                        column = column,
                                        targetLength = selectedLength,
                                        targetStart = selectedStart,
                                        windowStart = page.start,
                                        windowEnd = page.end,
                                        width = width,
                                        height = page.displayHeightDp.dp,
                                        displayDpPerLogicalPixel = paging.displayDpPerLogicalPixel,
                                    )
                                }
                                else -> HorizontalEndCapPage(
                                    column = column,
                                    page = page,
                                    width = width,
                                    height = page.displayHeightDp.dp,
                                    displayDpPerLogicalPixel = paging.displayDpPerLogicalPixel,
                                )
                            }
                        }
                    }
                }
            }
            val scrollOffset = columnWidths
                .take(scrollColumnIndex)
                .fold(0.dp) { total, width -> total + width + LAYER_PREVIEW_GAP }
            columns.forEachIndexed { index, column ->
                if (column.fixedSide != null) {
                    val x = columnWidths
                        .take(index)
                        .fold(0.dp) { total, width -> total + width + LAYER_PREVIEW_GAP }
                    val fixedState = if (column.fixedSide.isParallelToHorizontalScroll()) {
                        rotatedFittedState
                    } else {
                        originalOrientationState
                    }
                    Box(
                        Modifier
                            .offset(x = x)
                            .width(columnWidths[index])
                            .fillMaxHeight()
                            .testTag("${column.label}-image")
                            .semantics {
                                contentDescription = column.label
                                stateDescription = fixedState
                            },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .offset(x = scrollOffset)
                    .width(columnWidths[scrollColumnIndex])
                    .fillMaxHeight()
                    .onSizeChanged { scrollColumnSize = it }
                    .testTag("$middleLayerLabel-image")
                    .semantics {
                        contentDescription = middleLayerLabel
                        stateDescription = rotatedOrientationState
                    },
            ) {
                ScrollViewportPreviewInViewport(
                    preview = scrollViewportPreview,
                    horizontal = true,
                    axisLength = axisLength,
                    viewportWindow = viewportWindow,
                    width = columnWidths[scrollColumnIndex],
                    viewportHeight = paging.viewportHeightDp.dp,
                    label = stringResource(R.string.result_editor_middle_scroll_layer),
                )
                VirtualizedSegmentedCropControls(
                    regions = scrollExportRegions,
                    axisLength = axisLength,
                    viewportWindow = viewportWindow,
                    containerHeight = paging.viewportHeightDp.dp,
                    minimumSegmentLength = minimumSelectionPixels,
                    maximumSegmentLength = maximumScrollSegmentLength,
                    onRegionsChange = onScrollExportRegionsChange,
                    onRegionsFinished = onScrollExportRegionsFinished,
                    modifier = Modifier.fillMaxSize().testTag(HORIZONTAL_CROP_OVERLAY_TAG),
                )
            }
        }
    }
}

private data class HorizontalFixedPreview(
    val bitmap: Bitmap,
    val side: FixedSide,
    val width: Int,
    val height: Int,
    val anchorCount: Int,
    val anchorPosition: FixedAnchorPosition,
    val repeatRegion: RectConfig?,
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
)

private data class HorizontalPreviewColumn(
    val bitmap: Bitmap,
    val label: String,
    val rotated: Boolean,
    val mainAxisPixels: Int,
    val crossAxisPixels: Int,
    val fixedSide: FixedSide? = null,
    val anchorCount: Int = 1,
    val anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
    val sourceWindow: PreviewAxisWindow? = null,
    val enabled: Boolean = true,
    val onEnabledChange: ((Boolean) -> Unit)? = null,
)

@Composable
private fun HorizontalScrollOverviewPage(
    column: HorizontalPreviewColumn,
    axisLength: Int,
    page: ScrollPreviewPage,
    width: Dp,
    height: Dp,
) {
    val pageBitmap = rememberAxisWindowBitmap(
        bitmap = column.bitmap,
        horizontalAxis = true,
        window = PreviewAxisWindow(
            originalLength = axisLength,
            sourceStart = page.start,
            sourceEnd = page.end,
        ),
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        RotatedLayerBitmapContent(
            bitmap = pageBitmap,
            label = column.label,
            rotated = true,
            width = width,
            height = height,
            alpha = 1f,
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
private fun HorizontalScrollPreviewPage(
    page: ScrollPreviewPage,
    paging: ScrollPreviewPaging,
    columns: List<HorizontalPreviewColumn>,
    columnWidths: List<Dp>,
    scrollColumnIndex: Int,
    viewportPreview: ScrollViewportPreview?,
    viewportWindow: ScrollPreviewViewportWindow?,
    axisLength: Int,
    selectedStart: Int,
    selectedLength: Int,
    regions: List<ScrollExportRegion>,
    minimumSegmentLength: Int,
    maximumSegmentLength: Int,
    onRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    activeBoundaryDrag: ActiveScrollBoundaryDrag?,
    onBoundaryDragStart: (Int) -> Unit,
    onBoundaryDragFinished: (Int) -> Unit,
    onScrollColumnWidthChange: (Int) -> Unit,
    originalOrientationState: String,
    rotatedOrientationState: String,
    rotatedFittedState: String,
) {
    val pageHeight = page.displayHeightDp.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(pageHeight)
            .zIndex(if (activeBoundaryDrag?.ownerPageIndex == page.index) 1f else 0f)
            .then(if (page.index == 0) Modifier.testTag(HORIZONTAL_IMAGE_STRIP_TAG) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(LAYER_PREVIEW_GAP),
    ) {
        columns.forEachIndexed { index, column ->
            val width = columnWidths[index]
            val semanticsModifier = if (page.index == 0) {
                val fixedState = when {
                    column.fixedSide == null -> rotatedOrientationState
                    column.fixedSide?.isParallelToHorizontalScroll() == true -> rotatedFittedState
                    else -> originalOrientationState
                }
                Modifier
                    .testTag(column.label + "-image")
                    .semantics {
                        contentDescription = column.label
                        stateDescription = fixedState
                    }
            } else {
                Modifier
            }
            when {
                index == scrollColumnIndex -> {
                    Box(
                        modifier = Modifier
                            .width(width)
                            .height(pageHeight)
                            .onSizeChanged { onScrollColumnWidthChange(it.width) }
                            .then(semanticsModifier),
                    ) {
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            HorizontalScrollOverviewPage(
                                column = column,
                                axisLength = axisLength,
                                page = page,
                                width = width,
                                height = pageHeight,
                            )
                            if (viewportWindow != null) {
                                ScrollViewportPreviewInPage(
                                    preview = viewportPreview,
                                    horizontal = true,
                                    page = page,
                                    width = width,
                                    height = pageHeight,
                                    label = column.label,
                                )
                            }
                        }
                        SegmentedCropControlsForPage(
                            regions = regions,
                            page = page,
                            paging = paging,
                            minimumSegmentLength = minimumSegmentLength,
                            maximumSegmentLength = maximumSegmentLength,
                            onRegionsChange = onRegionsChange,
                            onRegionsFinished = onRegionsFinished,
                            activeBoundaryDrag = activeBoundaryDrag,
                            onBoundaryDragStart = onBoundaryDragStart,
                            onBoundaryDragFinished = onBoundaryDragFinished,
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (page.index == 0) {
                                        Modifier.testTag(HORIZONTAL_CROP_OVERLAY_TAG)
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
                column.fixedSide?.isParallelToHorizontalScroll() == true -> {
                    RepeatedHorizontalFixedBand(
                        column = column,
                        targetLength = selectedLength,
                        targetStart = selectedStart,
                        windowStart = page.start,
                        windowEnd = page.end,
                        width = width,
                        height = pageHeight,
                        displayDpPerLogicalPixel = paging.displayDpPerLogicalPixel,
                        modifier = semanticsModifier,
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .width(width)
                            .height(pageHeight),
                    ) {
                        HorizontalEndCapPage(
                            column = column,
                            page = page,
                            width = width,
                            height = pageHeight,
                            displayDpPerLogicalPixel = paging.displayDpPerLogicalPixel,
                            modifier = semanticsModifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HorizontalEndCapPage(
    column: HorizontalPreviewColumn,
    page: ScrollPreviewPage,
    width: Dp,
    height: Dp,
    displayDpPerLogicalPixel: Float,
    modifier: Modifier = Modifier,
) {
    val sourceWindow = requireNotNull(column.sourceWindow)
    val sourceBitmap = rememberAxisWindowBitmap(
        bitmap = column.bitmap,
        horizontalAxis = false,
        window = sourceWindow,
    )
    val slice = horizontalEndCapPageSlice(
        sourceLength = sourceWindow.length,
        page = page,
        displayDpPerLogicalPixel = displayDpPerLogicalPixel,
    )
    val contentWidth = horizontalFixedPreviewContentWidth(
        slotWidth = width,
        crossAxisPixels = column.crossAxisPixels,
        displayDpPerLogicalPixel = displayDpPerLogicalPixel,
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .alpha(if (column.enabled) 1f else DISABLED_LAYER_ALPHA)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (slice != null) {
            val pageBitmap = rememberAxisWindowBitmap(
                bitmap = sourceBitmap,
                horizontalAxis = false,
                window = PreviewAxisWindow(
                    originalLength = sourceWindow.length,
                    sourceStart = slice.sourceStart,
                    sourceEnd = slice.sourceEnd,
                ),
            )
            val contentHeight = slice.displayHeightDp.dp
            Box(
                modifier = modifier
                    .align(Alignment.TopCenter)
                    .width(contentWidth)
                    .height(contentHeight),
            ) {
                RotatedLayerBitmapContent(
                    bitmap = pageBitmap,
                    label = column.label,
                    rotated = false,
                    width = contentWidth,
                    height = contentHeight,
                    alpha = 1f,
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}

internal data class HorizontalEndCapPageSlice(
    val sourceStart: Int,
    val sourceEnd: Int,
    val displayHeightDp: Float,
) {
    val sourceLength: Int get() = sourceEnd - sourceStart
}

internal fun horizontalEndCapPageSlice(
    sourceLength: Int,
    page: ScrollPreviewPage,
    displayDpPerLogicalPixel: Float,
): HorizontalEndCapPageSlice? {
    if (sourceLength <= 0) return null
    val start = page.start.coerceIn(0, sourceLength)
    val end = page.end.coerceIn(start, sourceLength)
    if (end <= start) return null
    val scale = displayDpPerLogicalPixel.takeIf { it.isFinite() && it > 0f } ?: return null
    return HorizontalEndCapPageSlice(
        sourceStart = start,
        sourceEnd = end,
        displayHeightDp = ((end - start) * scale).coerceAtLeast(1f),
    )
}

private fun HorizontalFixedPreview.toPreviewColumn(
    sourceWindow: PreviewAxisWindow,
    label: String,
): HorizontalPreviewColumn {
    val rotated = horizontalFixedLayerRotates(side)
    return HorizontalPreviewColumn(
        bitmap = bitmap,
        label = label,
        rotated = rotated,
        mainAxisPixels = sourceWindow.length,
        crossAxisPixels = if (rotated) height else width,
        fixedSide = side,
        anchorCount = anchorCount,
        anchorPosition = anchorPosition,
        sourceWindow = sourceWindow,
        enabled = enabled,
        onEnabledChange = onEnabledChange,
    )
}

internal enum class HorizontalPreviewPlacement {
    LEADING,
    TRAILING,
}

internal fun horizontalPreviewPlacement(side: FixedSide): HorizontalPreviewPlacement = when (side) {
    FixedSide.LEFT, FixedSide.BOTTOM -> HorizontalPreviewPlacement.LEADING
    FixedSide.RIGHT, FixedSide.TOP -> HorizontalPreviewPlacement.TRAILING
}

internal fun horizontalFixedLayerRotates(side: FixedSide): Boolean =
    side == FixedSide.TOP || side == FixedSide.BOTTOM

internal fun horizontalPreviewColumnWidths(
    availableWidth: Dp,
    crossAxisPixels: List<Int>,
    minimumWidthIndices: Set<Int>,
): List<Dp> {
    if (crossAxisPixels.isEmpty()) return emptyList()
    val gapWidth = LAYER_PREVIEW_GAP * (crossAxisPixels.size - 1).coerceAtLeast(0)
    val imageBudget = (availableWidth - gapWidth).coerceAtLeast(1.dp)
    val weights = crossAxisPixels.map { it.coerceAtLeast(0).toFloat() }
    val totalWeight = weights.sum()
    if (totalWeight <= 0f) {
        return List(crossAxisPixels.size) { imageBudget / crossAxisPixels.size }
    }
    val validMinimumWidthIndices = minimumWidthIndices.filterTo(mutableSetOf()) {
        it in crossAxisPixels.indices
    }
    if (imageBudget < HORIZONTAL_MIN_COLUMN_WIDTH * validMinimumWidthIndices.size) {
        return weights.map { imageBudget * (it / totalWeight) }
    }

    // Keep very narrow fixed layers wide enough for the native checkbox and its
    // touch target, then distribute the remaining width by the source aspect.
    val widths = MutableList(crossAxisPixels.size) { 0.dp }
    val flexible = BooleanArray(crossAxisPixels.size) { true }
    var remainingBudget = imageBudget
    var remainingWeight = totalWeight
    while (remainingWeight > 0f) {
        var clampedAny = false
        crossAxisPixels.indices.forEach { index ->
            if (!flexible[index] || index !in validMinimumWidthIndices) return@forEach
            val candidate = remainingBudget * (weights[index] / remainingWeight)
            if (candidate < HORIZONTAL_MIN_COLUMN_WIDTH) {
                widths[index] = HORIZONTAL_MIN_COLUMN_WIDTH
                flexible[index] = false
                remainingBudget -= HORIZONTAL_MIN_COLUMN_WIDTH
                remainingWeight -= weights[index]
                clampedAny = true
            }
        }
        if (!clampedAny) break
    }
    crossAxisPixels.indices.forEach { index ->
        if (flexible[index]) {
            widths[index] = if (remainingWeight > 0f) {
                remainingBudget * (weights[index] / remainingWeight)
            } else {
                remainingBudget / flexible.count { it }
            }
        }
    }
    return widths
}

internal fun horizontalFixedPreviewContentWidth(
    slotWidth: Dp,
    crossAxisPixels: Int,
    displayDpPerLogicalPixel: Float,
): Dp {
    if (slotWidth <= 0.dp || crossAxisPixels <= 0 ||
        !displayDpPerLogicalPixel.isFinite() || displayDpPerLogicalPixel <= 0f
    ) {
        return slotWidth
    }
    val naturalWidth = (crossAxisPixels * displayDpPerLogicalPixel)
        .takeIf(Float::isFinite)
        ?.dp
        ?: return slotWidth
    return naturalWidth.coerceIn(minOf(1.dp, slotWidth), slotWidth)
}

internal fun horizontalPreviewScale(
    availableWidth: Dp,
    crossAxisPixels: List<Int>,
): Dp {
    if (crossAxisPixels.isEmpty()) return 1.dp
    val gapWidth = LAYER_PREVIEW_GAP * (crossAxisPixels.size - 1).coerceAtLeast(0)
    val imageBudget = (availableWidth - gapWidth).coerceAtLeast(1.dp)
    val totalPixels = crossAxisPixels.sumOf { it.coerceAtLeast(0).toLong() }
    return if (totalPixels > 0L) {
        imageBudget / totalPixels.toFloat()
    } else {
        imageBudget / crossAxisPixels.size.toFloat()
    }
}

internal fun horizontalSharedPreviewScale(
    displayedWidths: List<Dp>,
    logicalCrossAxisPixels: List<Int>,
    fallback: Dp,
): Dp {
    val minimumScale = displayedWidths.zip(logicalCrossAxisPixels).mapNotNull { (width, pixels) ->
        if (pixels <= 0 || width <= 0.dp) return@mapNotNull null
        (width.value / pixels).takeIf { it.isFinite() && it > 0f }
    }.minOrNull()
    return minimumScale?.dp ?: fallback
}

internal data class RepeatedFixedBandPreviewSpan(
    val sourceStart: Int,
    val sourceEnd: Int,
    val destinationStart: Int,
    val destinationEnd: Int,
)

private data class FixedBandAxisGeometry(
    val repeatStart: Int,
    val repeatEnd: Int,
    val leadingLength: Int,
    val trailingLength: Int,
)

internal fun inwardMappedPreviewRange(
    logicalStart: Int,
    logicalEnd: Int,
    logicalLength: Int,
    previewLength: Int,
): Pair<Int, Int> {
    val safeLogicalLength = logicalLength.coerceAtLeast(1)
    val safePreviewLength = previewLength.coerceAtLeast(1)
    val start = ceil(
        logicalStart.coerceIn(0, safeLogicalLength).toDouble() /
            safeLogicalLength * safePreviewLength,
    ).toInt().coerceIn(0, safePreviewLength - 1)
    val end = floor(
        logicalEnd.coerceIn(0, safeLogicalLength).toDouble() /
            safeLogicalLength * safePreviewLength,
    ).toInt().coerceIn(start + 1, safePreviewLength)
    return start to end
}

private fun fixedBandAxisGeometry(
    sourceLength: Int,
    anchorCount: Int,
    repeatStart: Int?,
    repeatEnd: Int?,
    anchorPosition: FixedAnchorPosition,
): FixedBandAxisGeometry {
    val safeSourceLength = sourceLength.coerceAtLeast(1)
    val safeAnchorCount = anchorCount.coerceIn(1, 2)
    val singleTrailing = safeAnchorCount == 1 &&
        anchorPosition == FixedAnchorPosition.TRAILING
    val maximumRepeatEnd = safeSourceLength -
        if (safeAnchorCount == 2 || singleTrailing) 1 else 0
    val requestedStart = repeatStart?.coerceIn(0, safeSourceLength)
    val requestedEnd = repeatEnd?.coerceIn(0, safeSourceLength)
    val requestedIsValid = requestedStart != null && requestedEnd != null &&
        (if (singleTrailing) requestedStart == 0 else requestedStart > 0) &&
        requestedEnd > requestedStart &&
        requestedEnd <= maximumRepeatEnd
    val resolvedStart: Int
    val resolvedEnd: Int
    if (requestedIsValid) {
        resolvedStart = requestedStart
        resolvedEnd = requestedEnd
    } else if (safeSourceLength <= 2) {
        resolvedStart = 0
        resolvedEnd = safeSourceLength
    } else if (singleTrailing) {
        resolvedStart = 0
        resolvedEnd = (safeSourceLength * 0.8f).roundToInt()
            .coerceIn(1, safeSourceLength - 1)
    } else {
        resolvedStart = (safeSourceLength * 0.2f).roundToInt()
            .coerceIn(1, safeSourceLength - 1)
        resolvedEnd = if (safeAnchorCount == 2) {
            (safeSourceLength * 0.8f).roundToInt()
                .coerceIn(resolvedStart + 1, safeSourceLength - 1)
        } else {
            safeSourceLength
        }
    }
    return FixedBandAxisGeometry(
        repeatStart = resolvedStart,
        repeatEnd = resolvedEnd,
        leadingLength = if (singleTrailing) 0 else resolvedStart,
        trailingLength = if (safeAnchorCount == 2 || singleTrailing) {
            safeSourceLength - resolvedEnd
        } else {
            0
        },
    )
}

internal fun minimumRepeatedFixedBandTargetLength(
    sourceLength: Int,
    anchorCount: Int,
    repeatStart: Int?,
    repeatEnd: Int?,
    anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
): Int {
    val geometry = fixedBandAxisGeometry(
        sourceLength,
        anchorCount,
        repeatStart,
        repeatEnd,
        anchorPosition,
    )
    return (geometry.leadingLength + geometry.trailingLength).coerceAtLeast(1)
}

internal fun rangeWithMinimumSelectionPixels(
    range: ClosedFloatingPointRange<Float>,
    axisLength: Int,
    minimumSelectionPixels: Int,
): ClosedFloatingPointRange<Float> {
    if (axisLength <= 0) return range
    val requiredLength = minimumSelectionPixels.coerceIn(1, axisLength)
    val start = (axisLength * range.start)
        .roundToInt()
        .coerceIn(0, axisLength)
    val end = (axisLength * range.endInclusive)
        .roundToInt()
        .coerceIn(start, axisLength)
    if (end - start >= requiredLength) return range
    val adjustedStart = start.coerceAtMost(axisLength - requiredLength)
    val adjustedEnd = adjustedStart + requiredLength
    return (adjustedStart.toFloat() / axisLength)..(adjustedEnd.toFloat() / axisLength)
}

internal data class RepeatedFixedBandPreviewRepeat(
    val sourceStart: Int,
    val sourceEnd: Int,
    val destinationStart: Int,
    val destinationEnd: Int,
)

internal data class RepeatedFixedBandPreviewPlan(
    val leading: RepeatedFixedBandPreviewSpan?,
    val repeating: RepeatedFixedBandPreviewRepeat?,
    val trailing: RepeatedFixedBandPreviewSpan?,
) {
    val operationCount: Int
        get() = listOfNotNull(leading, repeating, trailing).size
}

internal fun repeatedFixedBandPreviewPlan(
    sourceLength: Int,
    targetLength: Int,
    anchorCount: Int,
    repeatStart: Int?,
    repeatEnd: Int?,
    anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
): RepeatedFixedBandPreviewPlan {
    if (sourceLength <= 0 || targetLength <= 0) {
        return RepeatedFixedBandPreviewPlan(null, null, null)
    }
    val geometry = fixedBandAxisGeometry(
        sourceLength,
        anchorCount,
        repeatStart,
        repeatEnd,
        anchorPosition,
    )
    val anchorLength = geometry.leadingLength + geometry.trailingLength
    if (targetLength < anchorLength) {
        val leadingLength = if (anchorLength > 0) {
            (targetLength.toLong() * geometry.leadingLength / anchorLength).toInt()
        } else {
            0
        }
        val trailingLength = targetLength - leadingLength
        return RepeatedFixedBandPreviewPlan(
            leading = if (leadingLength > 0) {
                RepeatedFixedBandPreviewSpan(0, leadingLength, 0, leadingLength)
            } else {
                null
            },
            repeating = null,
            trailing = if (trailingLength > 0) {
                RepeatedFixedBandPreviewSpan(
                sourceStart = sourceLength - trailingLength,
                sourceEnd = sourceLength,
                destinationStart = leadingLength,
                destinationEnd = targetLength,
                )
            } else {
                null
            },
        )
    }
    val repeatedTargetLength = targetLength - anchorLength
    return RepeatedFixedBandPreviewPlan(
        leading = if (geometry.leadingLength > 0) {
            RepeatedFixedBandPreviewSpan(
                sourceStart = 0,
                sourceEnd = geometry.leadingLength,
                destinationStart = 0,
                destinationEnd = geometry.leadingLength,
            )
        } else {
            null
        },
        repeating = if (repeatedTargetLength > 0) {
            RepeatedFixedBandPreviewRepeat(
            sourceStart = geometry.repeatStart,
                sourceEnd = geometry.repeatEnd,
                destinationStart = geometry.leadingLength,
                destinationEnd = geometry.leadingLength + repeatedTargetLength,
            )
        } else {
            null
        },
        trailing = if (geometry.trailingLength > 0) {
            RepeatedFixedBandPreviewSpan(
                sourceStart = sourceLength - geometry.trailingLength,
                sourceEnd = sourceLength,
                destinationStart = targetLength - geometry.trailingLength,
                destinationEnd = targetLength,
            )
        } else {
            null
        },
    )
}

private fun FixedSide.isParallelToHorizontalScroll(): Boolean =
    this == FixedSide.TOP || this == FixedSide.BOTTOM

@Composable
private fun HorizontalLayerTitle(
    column: HorizontalPreviewColumn,
    width: Dp,
) {
    val onEnabledChange = column.onEnabledChange
    Column(
        modifier = Modifier
            .width(width)
            .height(HORIZONTAL_TITLE_HEIGHT)
            .testTag("${column.label}-title")
            .then(
                if (onEnabledChange != null) {
                    Modifier.toggleable(
                        value = column.enabled,
                        role = Role.Checkbox,
                        onValueChange = onEnabledChange,
                    )
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HORIZONTAL_CHECKBOX_SLOT_HEIGHT)
                .testTag("${column.label}-checkbox-slot"),
            contentAlignment = Alignment.Center,
        ) {
            if (onEnabledChange != null) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    Checkbox(
                        checked = column.enabled,
                        onCheckedChange = null,
                        modifier = Modifier
                            .requiredSize(24.dp)
                            .testTag("${column.label}-checkbox"),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("${column.label}-label-slot"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = column.label,
                modifier = Modifier
                    .requiredWidth(HORIZONTAL_LABEL_LENGTH)
                    .rotate(90f)
                    .testTag("${column.label}-label"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            )
        }
    }
}

@Composable
private fun HorizontalPreviewColumnContent(
    column: HorizontalPreviewColumn,
    width: Dp,
    previewHeight: Dp,
    previewScale: Dp,
    selectedStart: Int,
    selectedLength: Int,
    axisLength: Int,
    range: ClosedFloatingPointRange<Float>,
    minimumSelectionPixels: Int,
    scrollExportRegions: List<ScrollExportRegion>,
    maximumScrollSegmentLength: Int,
    onScrollExportRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onScrollExportRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    scrollViewportPreview: ScrollViewportPreview?,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width)
            .height(previewHeight)
            .alpha(if (column.enabled) 1f else DISABLED_LAYER_ALPHA),
    ) {
        when {
            column.fixedSide == null -> HorizontalScrollOverviewPage(
                column = column,
                axisLength = axisLength,
                page = buildScrollPreviewPaging(axisLength, previewScale.value).page(0),
                width = width,
                height = previewHeight,
            )
            column.fixedSide.isParallelToHorizontalScroll() -> Box(
                modifier = Modifier
                    .width(width)
                    .height(previewHeight),
            ) {
                RepeatedHorizontalFixedBand(
                    column = column,
                    targetLength = selectedLength,
                    targetStart = selectedStart,
                    windowStart = 0,
                    windowEnd = axisLength,
                    width = width,
                    height = (previewScale * selectedLength.coerceAtLeast(1)).coerceAtLeast(1.dp),
                    displayDpPerLogicalPixel = previewScale.value,
                    modifier = Modifier.offset(
                        y = previewScale * selectedStart.coerceAtLeast(0),
                    ),
                )
            }
            else -> HorizontalEndCapPreview(
                column = column,
                width = width,
                height = (previewScale * column.mainAxisPixels.coerceAtLeast(1)).coerceAtLeast(1.dp),
                displayDpPerLogicalPixel = previewScale.value,
            )
        }
    }
}

@Composable
private fun HorizontalEndCapPreview(
    column: HorizontalPreviewColumn,
    width: Dp,
    height: Dp,
    displayDpPerLogicalPixel: Float,
) {
    val sourceWindow = requireNotNull(column.sourceWindow)
    val previewBitmap = rememberAxisWindowBitmap(
        bitmap = column.bitmap,
        horizontalAxis = false,
        window = sourceWindow,
    )
    val contentWidth = horizontalFixedPreviewContentWidth(
        slotWidth = width,
        crossAxisPixels = column.crossAxisPixels,
        displayDpPerLogicalPixel = displayDpPerLogicalPixel,
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        RotatedLayerBitmap(
            bitmap = previewBitmap,
            label = column.label,
            rotated = false,
            width = contentWidth,
            height = height,
            alpha = 1f,
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun HorizontalScrollCropPreview(
    column: HorizontalPreviewColumn,
    viewportPreview: ScrollViewportPreview?,
    width: Dp,
    height: Dp,
    axisLength: Int,
    range: ClosedFloatingPointRange<Float>,
    minimumSelectionPixels: Int,
    scrollExportRegions: List<ScrollExportRegion>,
    maximumScrollSegmentLength: Int,
    onScrollExportRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onScrollExportRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    onScrollViewportPreviewRequest: (ScrollViewportPreviewRequest?) -> Unit,
) {
    HorizontalScrollOverviewPage(
        column = column,
        axisLength = axisLength,
        page = buildScrollPreviewPaging(axisLength, height.value.coerceAtLeast(1f)).page(0),
        width = width,
        height = height,
    )
}

@Composable
private fun RepeatedHorizontalFixedBand(
    column: HorizontalPreviewColumn,
    targetLength: Int,
    targetStart: Int,
    windowStart: Int,
    windowEnd: Int,
    width: Dp,
    height: Dp,
    displayDpPerLogicalPixel: Float,
    modifier: Modifier = Modifier,
) {
    val sourceWindow = requireNotNull(column.sourceWindow)
    val sourceBitmap = rememberAxisWindowBitmap(
        bitmap = column.bitmap,
        horizontalAxis = true,
        window = sourceWindow,
    )
    val sourceLength = sourceWindow.length
    val plan = remember(
        sourceLength,
        targetLength,
        column.anchorCount,
        column.anchorPosition,
        sourceWindow.repeatStart,
        sourceWindow.repeatEnd,
    ) {
        repeatedFixedBandPreviewPlan(
            sourceLength = sourceLength,
            targetLength = targetLength,
            anchorCount = column.anchorCount,
            repeatStart = sourceWindow.repeatStart,
            repeatEnd = sourceWindow.repeatEnd,
            anchorPosition = column.anchorPosition,
        )
    }
    val repeatSourceStart = plan.repeating?.sourceStart
    val repeatSourceEnd = plan.repeating?.sourceEnd
    val repeatBitmap = remember(
        sourceBitmap,
        sourceLength,
        repeatSourceStart,
        repeatSourceEnd,
    ) {
        if (repeatSourceStart != null && repeatSourceEnd != null) {
            val (sourceStart, sourceEnd) = inwardMappedPreviewRange(
                logicalStart = repeatSourceStart,
                logicalEnd = repeatSourceEnd,
                logicalLength = sourceLength,
                previewLength = sourceBitmap.width,
            )
            Bitmap.createBitmap(
                sourceBitmap,
                sourceStart,
                0,
                sourceEnd - sourceStart,
                sourceBitmap.height,
            )
        } else {
            null
        }
    }
    DisposableEffect(repeatBitmap, sourceBitmap) {
        onDispose {
            if (repeatBitmap != null && repeatBitmap !== sourceBitmap && !repeatBitmap.isRecycled) {
                repeatBitmap.recycle()
            }
        }
    }
    val repeatShader = remember(repeatBitmap) {
        repeatBitmap?.let {
            BitmapShader(it, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
        }
    }
    val bitmapPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    }
    val shaderMatrix = remember { Matrix() }
    val windowLength = (windowEnd - windowStart).coerceAtLeast(1)
    val contentWidth = horizontalFixedPreviewContentWidth(
        slotWidth = width,
        crossAxisPixels = column.crossAxisPixels,
        displayDpPerLogicalPixel = displayDpPerLogicalPixel,
    )
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .alpha(if (column.enabled) 1f else DISABLED_LAYER_ALPHA)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .requiredSize(width = height, height = contentWidth)
                .rotate(90f),
        ) {
            if (targetLength <= 0 || sourceLength <= 0) return@Canvas
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                bitmapPaint.alpha = 255
                bitmapPaint.shader = null

                fun drawSpan(span: RepeatedFixedBandPreviewSpan?) {
                    if (span == null) return
                    val sourceStart =
                        (span.sourceStart.toFloat() / sourceLength * sourceBitmap.width)
                            .roundToInt()
                        .coerceIn(0, sourceBitmap.width - 1)
                    val sourceEnd = (span.sourceEnd.toFloat() / sourceLength * sourceBitmap.width)
                        .roundToInt()
                        .coerceIn(sourceStart + 1, sourceBitmap.width)
                    val destinationStart =
                        (targetStart + span.destinationStart - windowStart).toFloat() /
                            windowLength * size.width
                    val destinationEnd =
                        (targetStart + span.destinationEnd - windowStart).toFloat() /
                            windowLength * size.width
                    if (destinationEnd > destinationStart) {
                        nativeCanvas.drawBitmap(
                            sourceBitmap,
                            AndroidRect(sourceStart, 0, sourceEnd, sourceBitmap.height),
                            RectF(destinationStart, 0f, destinationEnd, size.height),
                            bitmapPaint,
                        )
                    }
                }

                drawSpan(plan.leading)
                val repeating = plan.repeating
                if (repeating != null && repeatBitmap != null && repeatShader != null) {
                    val destinationStart =
                        (targetStart + repeating.destinationStart - windowStart).toFloat() /
                            windowLength * size.width
                    val destinationEnd =
                        (targetStart + repeating.destinationEnd - windowStart).toFloat() /
                            windowLength * size.width
                    val tileWidth = (
                        (repeating.sourceEnd - repeating.sourceStart).toFloat() /
                            windowLength * size.width
                        ).coerceAtLeast(0.001f)
                    shaderMatrix.reset()
                    shaderMatrix.setScale(
                        tileWidth / repeatBitmap.width,
                        size.height / repeatBitmap.height,
                    )
                    shaderMatrix.postTranslate(destinationStart, 0f)
                    repeatShader.setLocalMatrix(shaderMatrix)
                    bitmapPaint.shader = repeatShader
                    nativeCanvas.drawRect(
                        destinationStart,
                        0f,
                        destinationEnd,
                        size.height,
                        bitmapPaint,
                    )
                    bitmapPaint.shader = null
                }
                drawSpan(plan.trailing)
            }
        }
    }
}

@Composable
private fun RepeatedVerticalFixedBand(
    fixed: VerticalFixedPreview,
    sourceWindow: PreviewAxisWindow,
    targetLength: Int,
    targetStart: Int,
    windowStart: Int,
    windowEnd: Int,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val sourceBitmap = rememberAxisWindowBitmap(
        bitmap = fixed.bitmap,
        horizontalAxis = false,
        window = sourceWindow,
    )
    val sourceLength = sourceWindow.length
    val plan = remember(
        sourceLength,
        targetLength,
        fixed.anchorCount,
        fixed.anchorPosition,
        sourceWindow.repeatStart,
        sourceWindow.repeatEnd,
    ) {
        repeatedFixedBandPreviewPlan(
            sourceLength = sourceLength,
            targetLength = targetLength,
            anchorCount = fixed.anchorCount,
            repeatStart = sourceWindow.repeatStart,
            repeatEnd = sourceWindow.repeatEnd,
            anchorPosition = fixed.anchorPosition,
        )
    }
    val repeatSourceStart = plan.repeating?.sourceStart
    val repeatSourceEnd = plan.repeating?.sourceEnd
    val repeatBitmap = remember(
        sourceBitmap,
        sourceLength,
        repeatSourceStart,
        repeatSourceEnd,
    ) {
        if (repeatSourceStart != null && repeatSourceEnd != null) {
            val (sourceStart, sourceEnd) = inwardMappedPreviewRange(
                logicalStart = repeatSourceStart,
                logicalEnd = repeatSourceEnd,
                logicalLength = sourceLength,
                previewLength = sourceBitmap.height,
            )
            Bitmap.createBitmap(
                sourceBitmap,
                0,
                sourceStart,
                sourceBitmap.width,
                sourceEnd - sourceStart,
            )
        } else {
            null
        }
    }
    DisposableEffect(repeatBitmap, sourceBitmap) {
        onDispose {
            if (repeatBitmap != null && repeatBitmap !== sourceBitmap && !repeatBitmap.isRecycled) {
                repeatBitmap.recycle()
            }
        }
    }
    val repeatShader = remember(repeatBitmap) {
        repeatBitmap?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.REPEAT)
        }
    }
    val bitmapPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    }
    val shaderMatrix = remember { Matrix() }
    val windowLength = (windowEnd - windowStart).coerceAtLeast(1)
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .alpha(if (fixed.enabled) 1f else DISABLED_LAYER_ALPHA)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (targetLength <= 0 || sourceLength <= 0) return@Canvas
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                bitmapPaint.alpha = 255
                bitmapPaint.shader = null

                fun drawSpan(span: RepeatedFixedBandPreviewSpan?) {
                    if (span == null) return
                    val sourceStart =
                        (span.sourceStart.toFloat() / sourceLength * sourceBitmap.height)
                            .roundToInt()
                            .coerceIn(0, sourceBitmap.height - 1)
                    val sourceEnd =
                        (span.sourceEnd.toFloat() / sourceLength * sourceBitmap.height)
                            .roundToInt()
                            .coerceIn(sourceStart + 1, sourceBitmap.height)
                    val destinationStart =
                        (targetStart + span.destinationStart - windowStart).toFloat() /
                            windowLength * size.height
                    val destinationEnd =
                        (targetStart + span.destinationEnd - windowStart).toFloat() /
                            windowLength * size.height
                    if (destinationEnd > destinationStart) {
                        nativeCanvas.drawBitmap(
                            sourceBitmap,
                            AndroidRect(0, sourceStart, sourceBitmap.width, sourceEnd),
                            RectF(0f, destinationStart, size.width, destinationEnd),
                            bitmapPaint,
                        )
                    }
                }

                drawSpan(plan.leading)
                val repeating = plan.repeating
                if (repeating != null && repeatBitmap != null && repeatShader != null) {
                    val destinationStart =
                        (targetStart + repeating.destinationStart - windowStart).toFloat() /
                            windowLength * size.height
                    val destinationEnd =
                        (targetStart + repeating.destinationEnd - windowStart).toFloat() /
                            windowLength * size.height
                    val tileHeight = (
                        (repeating.sourceEnd - repeating.sourceStart).toFloat() /
                            windowLength * size.height
                    ).coerceAtLeast(0.001f)
                    shaderMatrix.reset()
                    shaderMatrix.setScale(
                        size.width / repeatBitmap.width,
                        tileHeight / repeatBitmap.height,
                    )
                    shaderMatrix.postTranslate(0f, destinationStart)
                    repeatShader.setLocalMatrix(shaderMatrix)
                    bitmapPaint.shader = repeatShader
                    nativeCanvas.drawRect(
                        0f,
                        destinationStart,
                        size.width,
                        destinationEnd,
                        bitmapPaint,
                    )
                    bitmapPaint.shader = null
                }
                drawSpan(plan.trailing)
            }
        }
    }
}

@Composable
private fun RotatedLayerBitmap(
    bitmap: Bitmap,
    label: String,
    rotated: Boolean,
    width: Dp,
    height: Dp,
    alpha: Float,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val orientationState = stringResource(
        if (rotated) R.string.result_editor_rotated_state
        else R.string.result_editor_original_orientation_state,
    )
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("$label-image")
            .semantics {
                stateDescription = orientationState
            },
        contentAlignment = Alignment.Center,
    ) {
        RotatedLayerBitmapContent(
            bitmap = bitmap,
            label = label,
            rotated = rotated,
            width = width,
            height = height,
            alpha = alpha,
            contentScale = contentScale,
        )
    }
}

@Composable
private fun RotatedLayerBitmapContent(
    bitmap: Bitmap,
    label: String,
    rotated: Boolean,
    width: Dp,
    height: Dp,
    alpha: Float,
    contentScale: ContentScale,
) {
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    androidx.compose.foundation.Image(
        bitmap = imageBitmap,
        contentDescription = label,
        modifier = if (rotated) {
            Modifier
                .requiredSize(width = height, height = width)
                .rotate(90f)
        } else {
            Modifier.fillMaxSize()
        },
        contentScale = contentScale,
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
}

@Composable
private fun fixedLayerLabel(side: FixedSide?): String = stringResource(
    when (side) {
        FixedSide.TOP -> R.string.result_editor_top_fixed_layer
        FixedSide.BOTTOM -> R.string.result_editor_bottom_fixed_layer
        FixedSide.LEFT -> R.string.result_editor_left_fixed_layer
        FixedSide.RIGHT -> R.string.result_editor_right_fixed_layer
        null -> R.string.result_editor_fixed_layer
    },
)

internal fun fixedWidthLayerHeight(
    previewWidth: Dp,
    sourceWidth: Int,
    sourceHeight: Int,
    fallback: Dp,
    preserveAspect: Boolean,
): Dp = if (preserveAspect && sourceWidth > 0 && sourceHeight > 0) {
    (previewWidth * (sourceHeight.toFloat() / sourceWidth)).coerceAtLeast(1.dp)
} else {
    fallback
}

internal const val HORIZONTAL_IMAGE_STRIP_TAG = "horizontal-layer-strip"
internal const val HORIZONTAL_CROP_OVERLAY_TAG = "horizontal-shared-crop-overlay"
internal const val VERTICAL_IMAGE_COMPOSITION_TAG = "vertical-layer-composition"
internal const val VERTICAL_CROP_OVERLAY_TAG = "vertical-shared-crop-overlay"
internal const val EXPORT_LOCATION_ROW_TAG = "export-location-row"
internal const val EXPORT_LOCATION_PATH_TAG = "export-location-path"
internal const val EXPECTED_SIZE_ROW_TAG = "expected-size-row"
internal const val RESULT_FORMAT_JPEG_TAG = "result-format-jpeg"
internal const val RESULT_FORMAT_PNG_TAG = "result-format-png"
internal const val RESULT_JPEG_QUALITY_TAG = "result-jpeg-quality"
internal const val RESULT_EDITOR_LIST_TAG = "result-editor-list"

internal fun scrollBoundaryHandleTag(boundaryIndex: Int): String =
    "result-editor-scroll-boundary-$boundaryIndex"

private val RESULT_FORMAT_COMPACT_WIDTH = 340.dp
private val LAYER_PREVIEW_GAP = 4.dp
private val HORIZONTAL_TITLE_HEIGHT = 116.dp
private val HORIZONTAL_CHECKBOX_SLOT_HEIGHT = 24.dp
private val HORIZONTAL_MIN_COLUMN_WIDTH = 32.dp
private val HORIZONTAL_LABEL_LENGTH = 88.dp
private const val DISABLED_LAYER_ALPHA = 0.3f
private const val VIEWPORT_PREVIEW_DEBOUNCE_MS = 80L
private const val VIEWPORT_PREVIEW_BUFFER_FRACTION = 0.5f
private const val SCROLL_PREVIEW_PAGE_HEIGHT_DP = 256f
private const val SCROLL_PREVIEW_MIN_TRAILING_PAGE_HEIGHT_DP = 50f
private const val SCROLL_PREVIEW_VIEWPORT_MAX_HEIGHT_DP = 520f

private const val SCROLL_PREVIEW_PAGE_KEY_PREFIX = "scroll-preview-page:"

internal fun scrollPreviewPageKey(
    resultId: String,
    horizontal: Boolean,
    pageIndex: Int,
): String = "$SCROLL_PREVIEW_PAGE_KEY_PREFIX$pageIndex:${if (horizontal) 'h' else 'v'}:$resultId"

internal fun scrollPreviewPageIndex(key: Any?): Int? = (key as? String)
    ?.takeIf { it.startsWith(SCROLL_PREVIEW_PAGE_KEY_PREFIX) }
    ?.removePrefix(SCROLL_PREVIEW_PAGE_KEY_PREFIX)
    ?.substringBefore(':')
    ?.toIntOrNull()

internal data class ScrollPreviewPage(
    val index: Int,
    val start: Int,
    val end: Int,
    val displayHeightDp: Float,
) {
    val length: Int get() = end - start
}

/** Constant-memory page description for an arbitrarily long logical scroll layer. */
internal data class ScrollPreviewPaging(
    val axisLength: Int,
    val logicalPixelsPerPage: Int,
    val pageCount: Int,
    val displayDpPerLogicalPixel: Float,
    val viewportHeightDp: Float,
) {
    fun page(index: Int): ScrollPreviewPage {
        require(index in 0 until pageCount)
        val start = (index.toLong() * logicalPixelsPerPage)
            .coerceAtMost(axisLength.toLong())
            .toInt()
        val end = if (index == pageCount - 1) {
            axisLength
        } else {
            minOf(
                axisLength.toLong(),
                start.toLong() + logicalPixelsPerPage,
            ).toInt()
        }
        return ScrollPreviewPage(
            index = index,
            start = start,
            end = end,
            displayHeightDp = ((end - start) * displayDpPerLogicalPixel)
                .coerceAtLeast(1f),
        )
    }
}

internal fun buildScrollPreviewPaging(
    axisLength: Int,
    naturalDisplayDpPerLogicalPixel: Float,
    pageHeightDp: Float = SCROLL_PREVIEW_PAGE_HEIGHT_DP,
    viewportMaxHeightDp: Float = SCROLL_PREVIEW_VIEWPORT_MAX_HEIGHT_DP,
): ScrollPreviewPaging {
    val safeAxis = axisLength.coerceAtLeast(1)
    val safePageHeight = pageHeightDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val safeViewportHeight = viewportMaxHeightDp
        .takeIf { it.isFinite() && it > 0f }
        ?: safePageHeight
    val scale = naturalDisplayDpPerLogicalPixel
        .takeIf { it.isFinite() && it > 0f }
        ?.coerceAtMost(safePageHeight)
        ?: 1f
    val logicalPerPage = floor(safePageHeight / scale)
        .toLong()
        .coerceIn(1L, safeAxis.toLong())
        .toInt()
    val unmergedPageCount =
        ((safeAxis.toLong() + logicalPerPage - 1L) / logicalPerPage).toInt()
    val trailingPageLogicalPixels = safeAxis.toLong() -
        (unmergedPageCount - 1L) * logicalPerPage
    val minimumTrailingPageHeight = minOf(
        SCROLL_PREVIEW_MIN_TRAILING_PAGE_HEIGHT_DP,
        safePageHeight,
    )
    val pageCount = if (
        unmergedPageCount > 1 &&
        trailingPageLogicalPixels * scale < minimumTrailingPageHeight
    ) {
        unmergedPageCount - 1
    } else {
        unmergedPageCount
    }
    val totalDisplayHeight = safeAxis.toDouble() * scale
    val viewportHeight = minOf(totalDisplayHeight, safeViewportHeight.toDouble())
        .toFloat()
        .coerceAtLeast(1f)
    return ScrollPreviewPaging(
        axisLength = safeAxis,
        logicalPixelsPerPage = logicalPerPage,
        pageCount = pageCount,
        displayDpPerLogicalPixel = scale,
        viewportHeightDp = viewportHeight,
    )
}

internal data class ScrollPreviewPagePlacement(
    val pageIndex: Int,
    val offsetPx: Int,
    val sizePx: Int,
)

internal data class ScrollPreviewViewportWindow(
    val start: Float,
    val end: Float,
    val displayPixelsPerLogicalPixel: Float,
) {
    val length: Float get() = end - start
}

internal fun visibleScrollPreviewWindow(
    paging: ScrollPreviewPaging,
    placements: List<ScrollPreviewPagePlacement>,
    viewportStartPx: Int,
    viewportEndPx: Int,
): ScrollPreviewViewportWindow? {
    if (viewportEndPx <= viewportStartPx) return null
    var logicalStart = Float.POSITIVE_INFINITY
    var logicalEnd = Float.NEGATIVE_INFINITY
    var coveredPixels = 0f
    placements.sortedBy(ScrollPreviewPagePlacement::offsetPx).forEach { placement ->
        if (placement.pageIndex !in 0 until paging.pageCount || placement.sizePx <= 0) {
            return@forEach
        }
        val intersectionStart = maxOf(viewportStartPx, placement.offsetPx)
        val intersectionEnd = minOf(viewportEndPx, placement.offsetPx + placement.sizePx)
        if (intersectionEnd <= intersectionStart) return@forEach
        val page = paging.page(placement.pageIndex)
        val startFraction = (intersectionStart - placement.offsetPx).toFloat() / placement.sizePx
        val endFraction = (intersectionEnd - placement.offsetPx).toFloat() / placement.sizePx
        logicalStart = minOf(logicalStart, page.start + page.length * startFraction)
        logicalEnd = maxOf(logicalEnd, page.start + page.length * endFraction)
        coveredPixels += intersectionEnd - intersectionStart
    }
    if (!logicalStart.isFinite() || !logicalEnd.isFinite() || logicalEnd <= logicalStart ||
        coveredPixels <= 0f
    ) {
        return null
    }
    val start = logicalStart.coerceIn(0f, paging.axisLength.toFloat())
    val end = logicalEnd.coerceIn(start, paging.axisLength.toFloat())
    if (end <= start) return null
    return ScrollPreviewViewportWindow(
        start = start,
        end = end,
        displayPixelsPerLogicalPixel = coveredPixels / (end - start),
    )
}

internal fun bufferedScrollPreviewRequest(
    window: ScrollPreviewViewportWindow,
    axisLength: Int,
    targetCrossAxisPixels: Int,
    bufferFraction: Float = VIEWPORT_PREVIEW_BUFFER_FRACTION,
): ScrollViewportPreviewRequest? {
    if (axisLength <= 0 || targetCrossAxisPixels <= 0 || window.length <= 0f ||
        window.displayPixelsPerLogicalPixel <= 0f
    ) {
        return null
    }
    val safeBuffer = bufferFraction.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val buffer = window.length * safeBuffer
    val start = floor(window.start - buffer).toInt().coerceIn(0, axisLength - 1)
    val end = ceil(window.end + buffer).toInt().coerceIn(start + 1, axisLength)
    val targetAxisPixels = ceil(
        (end - start) * window.displayPixelsPerLogicalPixel,
    ).toInt().coerceAtLeast(1)
    return ScrollViewportPreviewRequest(
        start = start,
        end = end,
        targetCrossAxisPixels = targetCrossAxisPixels,
        targetAxisPixels = targetAxisPixels,
    )
}

internal data class IndexedScrollBoundary(
    val index: Int,
    val position: Int,
)

internal data class ActiveScrollBoundaryDrag(
    val boundaryIndex: Int,
    val ownerPageIndex: Int,
)

internal fun scrollBoundariesForPage(
    regions: List<ScrollExportRegion>,
    page: ScrollPreviewPage,
    paging: ScrollPreviewPaging,
    activeBoundaryDrag: ActiveScrollBoundaryDrag?,
): List<IndexedScrollBoundary> {
    val pageBoundaries = scrollBoundariesInViewport(
        regions = regions,
        logicalStart = page.start.toFloat(),
        logicalEnd = page.end.toFloat(),
    ).filter { boundary ->
        boundary.position >= page.start &&
            (boundary.position < page.end ||
                (page.index == paging.pageCount - 1 && boundary.position == page.end))
    }
    val activeBoundary = activeBoundaryDrag
        ?.takeIf { it.ownerPageIndex == page.index && it.boundaryIndex in 0..regions.size }
        ?.let { drag ->
            IndexedScrollBoundary(
                index = drag.boundaryIndex,
                position = if (drag.boundaryIndex == 0) {
                    regions.firstOrNull()?.start ?: 0
                } else {
                    regions.getOrNull(drag.boundaryIndex - 1)?.end ?: paging.axisLength
                },
            )
        }
    return buildList {
        pageBoundaries.forEach { boundary ->
            if (boundary.index != activeBoundaryDrag?.boundaryIndex) add(boundary)
        }
        if (activeBoundary != null) add(activeBoundary)
    }.sortedBy(IndexedScrollBoundary::index)
}

internal fun scrollBoundaryLinesForPage(
    regions: List<ScrollExportRegion>,
    page: ScrollPreviewPage,
    paging: ScrollPreviewPaging,
): List<IndexedScrollBoundary> = scrollBoundariesForPage(
    regions = regions,
    page = page,
    paging = paging,
    activeBoundaryDrag = null,
)

internal fun scrollBoundariesInViewport(
    regions: List<ScrollExportRegion>,
    logicalStart: Float,
    logicalEnd: Float,
    logicalBuffer: Float = 0f,
): List<IndexedScrollBoundary> {
    if (regions.isEmpty() || logicalEnd < logicalStart) return emptyList()
    val lower = logicalStart - logicalBuffer.coerceAtLeast(0f)
    val upper = logicalEnd + logicalBuffer.coerceAtLeast(0f)
    fun boundaryPosition(index: Int): Int = if (index == 0) {
        regions.first().start
    } else {
        regions[index - 1].end
    }
    var low = 0
    var high = regions.size + 1
    while (low < high) {
        val middle = (low + high) ushr 1
        if (boundaryPosition(middle) < lower) low = middle + 1 else high = middle
    }
    return buildList {
        var index = low
        while (index <= regions.size) {
            val position = boundaryPosition(index)
            if (position > upper) break
            add(IndexedScrollBoundary(index, position))
            index++
        }
    }
}

@Composable
private fun rememberScrollPreviewViewportWindow(
    paging: ScrollPreviewPaging,
    listState: LazyListState,
    targetCrossAxisPixels: Int,
    onRequest: (ScrollViewportPreviewRequest?) -> Unit,
): ScrollPreviewViewportWindow? {
    val latestOnRequest by rememberUpdatedState(onRequest)
    var window by remember(paging) { mutableStateOf<ScrollPreviewViewportWindow?>(null) }
    LaunchedEffect(paging, listState, targetCrossAxisPixels) {
        snapshotFlow {
            val layout = listState.layoutInfo
            visibleScrollPreviewWindow(
                paging = paging,
                placements = layout.visibleItemsInfo.mapNotNull { item ->
                    val pageIndex = scrollPreviewPageIndex(item.key) ?: return@mapNotNull null
                    ScrollPreviewPagePlacement(
                        pageIndex = pageIndex,
                        offsetPx = item.offset,
                        sizePx = item.size,
                    )
                },
                viewportStartPx = layout.viewportStartOffset,
                viewportEndPx = layout.viewportEndOffset,
            )
        }.collectLatest { updated ->
            window = updated
            if (updated == null || targetCrossAxisPixels <= 0) {
                latestOnRequest(null)
                return@collectLatest
            }
            delay(VIEWPORT_PREVIEW_DEBOUNCE_MS)
            latestOnRequest(
                bufferedScrollPreviewRequest(
                    window = updated,
                    axisLength = paging.axisLength,
                    targetCrossAxisPixels = targetCrossAxisPixels,
                ),
            )
        }
    }
    DisposableEffect(paging.axisLength) {
        onDispose { latestOnRequest(null) }
    }
    return window
}

internal data class ScrollViewportPageSlice(
    val sourceStart: Int,
    val sourceEnd: Int,
    val offsetFraction: Float,
    val lengthFraction: Float,
)

internal fun scrollViewportPageSlice(
    previewStart: Int,
    previewEnd: Int,
    bitmapAxisLength: Int,
    pageStart: Int,
    pageEnd: Int,
): ScrollViewportPageSlice? {
    if (previewEnd <= previewStart || bitmapAxisLength <= 0 || pageEnd <= pageStart ||
        previewEnd <= pageStart || previewStart >= pageEnd
    ) {
        return null
    }
    val intersectionStart = maxOf(pageStart, previewStart)
    val intersectionEnd = minOf(pageEnd, previewEnd)
    if (intersectionEnd <= intersectionStart) return null
    val previewLength = previewEnd - previewStart
    val pageLength = pageEnd - pageStart
    val sourceStart = floor(
        (intersectionStart - previewStart).toDouble() / previewLength * bitmapAxisLength,
    ).toInt().coerceIn(0, bitmapAxisLength - 1)
    val sourceEnd = ceil(
        (intersectionEnd - previewStart).toDouble() / previewLength * bitmapAxisLength,
    ).toInt().coerceIn(sourceStart + 1, bitmapAxisLength)
    return ScrollViewportPageSlice(
        sourceStart = sourceStart,
        sourceEnd = sourceEnd,
        offsetFraction = (intersectionStart - pageStart).toFloat() / pageLength,
        lengthFraction = (intersectionEnd - intersectionStart).toFloat() / pageLength,
    )
}

@Composable
private fun ScrollViewportPreviewInPage(
    preview: ScrollViewportPreview?,
    horizontal: Boolean,
    page: ScrollPreviewPage,
    width: Dp,
    height: Dp,
    label: String,
) {
    if (preview == null || preview.horizontal != horizontal || preview.bitmap.isRecycled ||
        preview.end <= page.start || preview.start >= page.end || preview.end <= preview.start
    ) {
        return
    }
    val bitmapAxisLength = if (horizontal) preview.bitmap.width else preview.bitmap.height
    val slice = scrollViewportPageSlice(
        previewStart = preview.start,
        previewEnd = preview.end,
        bitmapAxisLength = bitmapAxisLength,
        pageStart = page.start,
        pageEnd = page.end,
    ) ?: return
    val overlayHeight = (height * slice.lengthFraction).coerceAtLeast(1.dp)
    val imageBitmap = remember(preview.bitmap) { preview.bitmap.asImageBitmap() }
    Box(
        modifier = Modifier
            .offset(y = height * slice.offsetFraction)
            .width(width)
            .height(overlayHeight)
            .clipToBounds()
            .semantics { contentDescription = label },
        // Center the pre-rotation canvas so its post-rotation bounds line up with this page slice.
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = if (horizontal) {
                Modifier
                    .requiredSize(width = overlayHeight, height = width)
                    .rotate(90f)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            val sourceOffset = if (horizontal) {
                IntOffset(slice.sourceStart, 0)
            } else {
                IntOffset(0, slice.sourceStart)
            }
            val sourceSize = if (horizontal) {
                IntSize(slice.sourceEnd - slice.sourceStart, preview.bitmap.height)
            } else {
                IntSize(preview.bitmap.width, slice.sourceEnd - slice.sourceStart)
            }
            drawImage(
                image = imageBitmap,
                srcOffset = sourceOffset,
                srcSize = sourceSize,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(
                    size.width.roundToInt().coerceAtLeast(1),
                    size.height.roundToInt().coerceAtLeast(1),
                ),
                filterQuality = FilterQuality.High,
            )
        }
    }
}

internal fun scrollRegionsIntersectingRange(
    regions: List<ScrollExportRegion>,
    start: Int,
    end: Int,
): List<ScrollExportRegion> {
    if (regions.isEmpty() || end <= start) return emptyList()
    var low = 0
    var high = regions.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (regions[middle].end <= start) low = middle + 1 else high = middle
    }
    return buildList {
        var index = low
        while (index < regions.size && regions[index].start < end) {
            add(regions[index])
            index++
        }
    }
}

@Composable
private fun SegmentedCropControlsForPage(
    regions: List<ScrollExportRegion>,
    page: ScrollPreviewPage,
    paging: ScrollPreviewPaging,
    minimumSegmentLength: Int,
    maximumSegmentLength: Int,
    onRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    activeBoundaryDrag: ActiveScrollBoundaryDrag?,
    onBoundaryDragStart: (Int) -> Unit,
    onBoundaryDragFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val displayPixelsPerLogicalPixel = with(density) {
        paging.displayDpPerLogicalPixel.dp.toPx()
    }.coerceAtLeast(0.001f)
    val handleBoundaries = scrollBoundariesForPage(regions, page, paging, activeBoundaryDrag)
    val lineBoundaries = if (activeBoundaryDrag == null) {
        handleBoundaries
    } else {
        scrollBoundaryLinesForPage(regions, page, paging)
    }
    val intersectingRegions = scrollRegionsIntersectingRange(regions, page.start, page.end)
    val lineColor = MaterialTheme.colorScheme.primary
    val dimColor = Color.Black.copy(alpha = 0.52f)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            fun y(value: Int): Float =
                (value - page.start) * displayPixelsPerLogicalPixel
            fun dim(startPx: Float, endPx: Float) {
                val top = startPx.coerceIn(0f, size.height)
                val bottom = endPx.coerceIn(0f, size.height)
                if (bottom > top) {
                    drawRect(
                        dimColor,
                        topLeft = Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, bottom - top),
                    )
                }
            }
            val firstSelected = regions.firstOrNull()?.start
            val lastSelected = regions.lastOrNull()?.end
            if (firstSelected == null || lastSelected == null) {
                dim(0f, size.height)
            } else {
                if (firstSelected > page.start) dim(0f, y(firstSelected))
                if (lastSelected < page.end) dim(y(lastSelected), size.height)
                intersectingRegions.filterNot(ScrollExportRegion::enabled).forEach { region ->
                    dim(y(region.start), y(region.end))
                }
            }
            lineBoundaries.forEach { boundary ->
                val lineY = y(boundary.position)
                if (lineY in 0f..size.height) {
                    drawLine(
                        lineColor,
                        Offset(0f, lineY),
                        Offset(size.width, lineY),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
        handleBoundaries.forEach { boundary ->
            ViewportSegmentBoundaryHandle(
                position = boundary.position,
                positionYPx = (boundary.position - page.start) *
                    displayPixelsPerLogicalPixel,
                boundaryIndex = boundary.index,
                axisLength = paging.axisLength,
                containerHeight = page.displayHeightDp.dp,
                displayPixelsPerLogicalPixel = displayPixelsPerLogicalPixel,
                regions = regions,
                minimumSegmentLength = minimumSegmentLength,
                maximumSegmentLength = maximumSegmentLength,
                onRegionsChange = onRegionsChange,
                onRegionsFinished = onRegionsFinished,
                onDragStart = onBoundaryDragStart,
                onDragFinished = onBoundaryDragFinished,
            )
        }
    }
}

@Composable
private fun ScrollViewportPreviewInViewport(
    preview: ScrollViewportPreview?,
    horizontal: Boolean,
    axisLength: Int,
    viewportWindow: ScrollPreviewViewportWindow?,
    width: Dp,
    viewportHeight: Dp,
    label: String,
) {
    val density = LocalDensity.current
    val window = viewportWindow ?: return
    if (preview == null || preview.horizontal != horizontal || axisLength <= 0 ||
        preview.start !in 0 until axisLength || preview.end !in 1..axisLength ||
        preview.end <= preview.start || preview.bitmap.isRecycled ||
        preview.end <= window.start || preview.start >= window.end
    ) {
        return
    }
    val previewHeightPx = (preview.end - preview.start) * window.displayPixelsPerLogicalPixel
    val previewOffsetPx = (preview.start - window.start) * window.displayPixelsPerLogicalPixel
    if (!previewHeightPx.isFinite() || previewHeightPx <= 0f || !previewOffsetPx.isFinite()) return
    val previewHeight = with(density) { previewHeightPx.toDp() }
    val previewOffset = with(density) { previewOffsetPx.toDp() }
    Box(
        modifier = Modifier
            .width(width)
            .height(previewHeight)
            .offset(y = previewOffset)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        RotatedLayerBitmapContent(
            bitmap = preview.bitmap,
            label = label,
            rotated = horizontal,
            width = width,
            height = previewHeight,
            alpha = 1f,
            contentScale = ContentScale.FillBounds,
        )
    }
}

@Composable
private fun VirtualizedSegmentedCropControls(
    regions: List<ScrollExportRegion>,
    axisLength: Int,
    viewportWindow: ScrollPreviewViewportWindow?,
    containerHeight: Dp,
    minimumSegmentLength: Int,
    maximumSegmentLength: Int,
    onRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = viewportWindow ?: return
    val density = LocalDensity.current
    val handleBufferLogical = with(density) { 72.dp.toPx() } /
        window.displayPixelsPerLogicalPixel.coerceAtLeast(0.001f)
    val boundaries = scrollBoundariesInViewport(
        regions = regions,
        logicalStart = window.start,
        logicalEnd = window.end,
        logicalBuffer = handleBufferLogical,
    )
    val lineColor = MaterialTheme.colorScheme.primary
    val dimColor = Color.Black.copy(alpha = 0.52f)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            fun y(value: Int): Float =
                (value - window.start) * window.displayPixelsPerLogicalPixel
            fun dim(start: Float, end: Float) {
                val top = start.coerceIn(0f, size.height)
                val bottom = end.coerceIn(0f, size.height)
                if (bottom > top) {
                    drawRect(
                        dimColor,
                        topLeft = Offset(0f, top),
                        size = androidx.compose.ui.geometry.Size(size.width, bottom - top),
                    )
                }
            }
            dim(0f, y(regions.firstOrNull()?.start ?: 0))
            dim(y(regions.lastOrNull()?.end ?: axisLength), size.height)
            regions.asSequence()
                .filterNot(ScrollExportRegion::enabled)
                .filter { it.end > window.start && it.start < window.end }
                .forEach { region ->
                    dim(y(region.start), y(region.end))
                }
            boundaries.forEach { boundary ->
                val lineY = y(boundary.position)
                if (lineY in 0f..size.height) {
                    drawLine(
                        lineColor,
                        Offset(0f, lineY),
                        Offset(size.width, lineY),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
        boundaries.forEach { boundary ->
            ViewportSegmentBoundaryHandle(
                position = boundary.position,
                positionYPx = (boundary.position - window.start) *
                    window.displayPixelsPerLogicalPixel,
                boundaryIndex = boundary.index,
                axisLength = axisLength.coerceAtLeast(1),
                containerHeight = containerHeight,
                displayPixelsPerLogicalPixel = window.displayPixelsPerLogicalPixel,
                regions = regions,
                minimumSegmentLength = minimumSegmentLength,
                maximumSegmentLength = maximumSegmentLength,
                onRegionsChange = onRegionsChange,
                onRegionsFinished = onRegionsFinished,
            )
        }
    }
}

@Composable
private fun ViewportSegmentBoundaryHandle(
    position: Int,
    positionYPx: Float,
    boundaryIndex: Int,
    axisLength: Int,
    containerHeight: Dp,
    displayPixelsPerLogicalPixel: Float,
    regions: List<ScrollExportRegion>,
    minimumSegmentLength: Int,
    maximumSegmentLength: Int,
    onRegionsChange: (List<ScrollExportRegion>) -> Unit,
    onRegionsFinished: (List<ScrollExportRegion>) -> Unit,
    onDragStart: (Int) -> Unit = {},
    onDragFinished: (Int) -> Unit = {},
) {
    var menuExpanded by remember(boundaryIndex) { mutableStateOf(false) }
    var dragging by remember(boundaryIndex) { mutableStateOf(false) }
    var dragOffsetAdjustmentPx by remember(boundaryIndex) { mutableStateOf(0) }
    var visualPosition by remember(boundaryIndex) { mutableStateOf(position) }
    LaunchedEffect(position, dragging) {
        if (!dragging) visualPosition = position
    }
    val latestRegions by rememberUpdatedState(regions)
    val latestOnRegionsChange by rememberUpdatedState(onRegionsChange)
    val latestOnRegionsFinished by rememberUpdatedState(onRegionsFinished)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDragFinished by rememberUpdatedState(onDragFinished)
    val density = LocalDensity.current
    val axisPx = with(density) { containerHeight.toPx() }.coerceAtLeast(1f)
    val actions = scrollBoundaryActions(
        regions = regions,
        boundaryIndex = boundaryIndex,
        maximumSegmentLength = maximumSegmentLength,
        minimumSegmentLength = minimumSegmentLength,
        axisLength = axisLength,
    )
    val adjacentMax = regions.getOrNull(boundaryIndex - 1)?.let { it.enabled && it.length >= maximumSegmentLength } == true ||
        regions.getOrNull(boundaryIndex)?.let { it.enabled && it.length >= maximumSegmentLength } == true
    val toolbarHeight = 32.dp
    val handleHeight = if (adjacentMax) 50.dp else toolbarHeight
    val toolbarHeightPx = with(density) { toolbarHeight.toPx() }
    val handleHeightPx = with(density) { handleHeight.toPx() }
    val visualPositionYPx = segmentHandleVisualPositionYPx(
        visualPosition = visualPosition,
        modelPosition = position,
        modelPositionYPx = positionYPx,
        displayPixelsPerLogicalPixel = displayPixelsPerLogicalPixel,
    )
    val desiredOffset = segmentHandleOffset(
        positionYPx = visualPositionYPx,
        containerHeightPx = axisPx,
        toolbarHeightPx = toolbarHeightPx,
        handleHeightPx = handleHeightPx,
        constrainToContainer = false,
        placeAboveBoundary = boundaryIndex == regions.size,
    )
    val restingOffset = (desiredOffset + dragOffsetAdjustmentPx).coerceIn(
        0,
        (axisPx - handleHeightPx).roundToInt().coerceAtLeast(0),
    )
    val dragStartOffsetAdjustment by rememberUpdatedState(restingOffset - desiredOffset)
    val offset = if (dragging) desiredOffset + dragOffsetAdjustmentPx else restingOffset
    val positionLabel = stringResource(
        when (scrollBoundaryPositionLabel(boundaryIndex, regions.size)) {
            ScrollBoundaryPositionLabel.STARTS_AT -> R.string.result_editor_split_starts_at
            ScrollBoundaryPositionLabel.ENDS_AT -> R.string.result_editor_split_ends_at
        },
        visualPosition,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(handleHeight)
            .offset { IntOffset(0, offset) },
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 176.dp)
                .height(toolbarHeight),
            shape = MaterialTheme.shapes.small,
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    positionLabel,
                    modifier = Modifier
                        .widthIn(max = 136.dp)
                        .fillMaxHeight()
                        .testTag(scrollBoundaryHandleTag(boundaryIndex))
                        .pointerInput(
                            boundaryIndex,
                            axisLength,
                            displayPixelsPerLogicalPixel,
                            minimumSegmentLength,
                            maximumSegmentLength,
                        ) {
                            var active = latestRegions
                            var activePosition = position.toFloat()
                            detectDragGestures(
                                onDragStart = {
                                    dragOffsetAdjustmentPx = dragStartOffsetAdjustment
                                    dragging = true
                                    latestOnDragStart(boundaryIndex)
                                    active = latestRegions
                                    activePosition = scrollExportBoundaryPositions(active)
                                        .getOrElse(boundaryIndex) { position }
                                        .toFloat()
                                    visualPosition = activePosition.roundToInt()
                                },
                                onDragEnd = {
                                    latestOnRegionsFinished(active)
                                    latestOnDragFinished(boundaryIndex)
                                    dragging = false
                                },
                                onDragCancel = {
                                    latestOnRegionsFinished(active)
                                    latestOnDragFinished(boundaryIndex)
                                    dragging = false
                                },
                            ) { change, amount ->
                                change.consume()
                                activePosition += amount.y /
                                    displayPixelsPerLogicalPixel.coerceAtLeast(0.001f)
                                active = moveScrollExportBoundary(
                                    regions = active,
                                    boundaryIndex = boundaryIndex,
                                    requestedPosition = activePosition.roundToInt(),
                                    axisLength = axisLength,
                                    maximumSegmentLength = maximumSegmentLength,
                                    minimumSegmentLength = minimumSegmentLength,
                                )
                                activePosition = scrollExportBoundaryPositions(active)
                                    .getOrElse(boundaryIndex) { activePosition.roundToInt() }
                                    .toFloat()
                                visualPosition = activePosition.roundToInt()
                                latestOnRegionsChange(active)
                            }
                        }
                        .wrapContentHeight(Alignment.CenterVertically)
                        .padding(start = 8.dp, end = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.requiredSize(toolbarHeight),
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.result_editor_split_more),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            @Composable
                            fun item(enabled: Boolean, text: String, action: ScrollBoundaryAction) {
                                if (enabled) DropdownMenuItem(
                                    text = {
                                        Text(
                                            text,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        val updated = applyScrollBoundaryAction(
                                            latestRegions, boundaryIndex, action, maximumSegmentLength,
                                            minimumSegmentLength, axisLength,
                                        )
                                        onRegionsChange(updated)
                                        onRegionsFinished(updated)
                                    },
                                    modifier = Modifier.height(toolbarHeight),
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                )
                            }
                            item(actions.addAbove, stringResource(R.string.result_editor_split_add_above), ScrollBoundaryAction.ADD_ABOVE)
                            item(actions.addBelow, stringResource(R.string.result_editor_split_add_below), ScrollBoundaryAction.ADD_BELOW)
                            item(actions.deleteAbove, stringResource(R.string.result_editor_split_delete_above), ScrollBoundaryAction.DELETE_ABOVE)
                            item(actions.deleteBelow, stringResource(R.string.result_editor_split_delete_below), ScrollBoundaryAction.DELETE_BELOW)
                            if (actions.toggleAbove || actions.toggleBelow) HorizontalDivider()
                            item(
                                actions.toggleAbove,
                                stringResource(
                                    if (regions.getOrNull(boundaryIndex - 1)?.enabled == true) {
                                        R.string.result_editor_split_disable_above
                                    } else {
                                        R.string.result_editor_split_enable_above
                                    },
                                ),
                                ScrollBoundaryAction.TOGGLE_ABOVE,
                            )
                            item(
                                actions.toggleBelow,
                                stringResource(
                                    if (regions.getOrNull(boundaryIndex)?.enabled == true) {
                                        R.string.result_editor_split_disable_below
                                    } else {
                                        R.string.result_editor_split_enable_below
                                    },
                                ),
                                ScrollBoundaryAction.TOGGLE_BELOW,
                            )
                        }
                    }
                }
            }
        }
        if (adjacentMax) {
            Text(
                stringResource(R.string.result_editor_split_maximum_length, maximumSegmentLength),
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .padding(start = 8.dp, top = 1.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun segmentHandleVisualPositionYPx(
    visualPosition: Int,
    modelPosition: Int,
    modelPositionYPx: Float,
    displayPixelsPerLogicalPixel: Float,
): Float {
    val scale = displayPixelsPerLogicalPixel
        .takeIf { it.isFinite() && it > 0f }
        ?: return modelPositionYPx
    val logicalDelta = visualPosition.toLong() - modelPosition.toLong()
    return modelPositionYPx + logicalDelta * scale
}

internal fun segmentHandleOffset(
    positionYPx: Float,
    containerHeightPx: Float,
    toolbarHeightPx: Float,
    handleHeightPx: Float,
    constrainToContainer: Boolean = true,
    placeAboveBoundary: Boolean = false,
): Int {
    val alignmentOffset = if (placeAboveBoundary) handleHeightPx else toolbarHeightPx / 2f
    val desiredOffset = (positionYPx - alignmentOffset).roundToInt()
    if (!constrainToContainer) return desiredOffset
    val maximumOffset = (containerHeightPx - handleHeightPx)
        .roundToInt()
        .coerceAtLeast(0)
    return desiredOffset.coerceIn(0, maximumOffset)
}

@Composable
private fun CropMask(
    range: ClosedFloatingPointRange<Float>,
    horizontal: Boolean,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val start = if (horizontal) size.width * range.start else size.height * range.start
        val end = if (horizontal) size.width * range.endInclusive else size.height * range.endInclusive
        val scrim = Color.Black.copy(alpha = 0.5f)
        if (horizontal) {
            drawRect(scrim, size = androidx.compose.ui.geometry.Size(start, size.height))
            drawRect(
                scrim,
                topLeft = Offset(end, 0f),
                size = androidx.compose.ui.geometry.Size((size.width - end).coerceAtLeast(0f), size.height),
            )
            drawLine(lineColor, Offset(start, 0f), Offset(start, size.height), strokeWidth = 3.dp.toPx())
            drawLine(lineColor, Offset(end, 0f), Offset(end, size.height), strokeWidth = 3.dp.toPx())
        } else {
            drawRect(scrim, size = androidx.compose.ui.geometry.Size(size.width, start))
            drawRect(
                scrim,
                topLeft = Offset(0f, end),
                size = androidx.compose.ui.geometry.Size(size.width, (size.height - end).coerceAtLeast(0f)),
            )
            drawLine(lineColor, Offset(0f, start), Offset(size.width, start), strokeWidth = 3.dp.toPx())
            drawLine(lineColor, Offset(0f, end), Offset(size.width, end), strokeWidth = 3.dp.toPx())
        }
    }
}

@Composable
private fun CropHandle(
    isStart: Boolean,
    fraction: Float,
    otherFraction: Float,
    axisLength: Int,
    horizontal: Boolean,
    containerWidth: Dp,
    containerHeight: Dp,
    minimumSelectionPixels: Int = 1,
    showEndAbsolutePosition: Boolean,
    onEndPositionModeToggle: () -> Unit,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    onRangeFinished: (ClosedFloatingPointRange<Float>) -> Unit,
) {
    val latestFraction by rememberUpdatedState(fraction)
    val latestOtherFraction by rememberUpdatedState(otherFraction)
    val latestOnRangeChange by rememberUpdatedState(onRangeChange)
    val latestOnRangeFinished by rememberUpdatedState(onRangeFinished)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val touchSize = 44.dp
    val touchSizePx = with(density) { touchSize.toPx() }
    val axisSizePx = with(density) {
        (if (horizontal) containerWidth else containerHeight).toPx()
    }.coerceAtLeast(1f)
    val offset = (axisSizePx * fraction - touchSizePx / 2f)
        .roundToInt()
        .coerceIn(0, (axisSizePx - touchSizePx).roundToInt().coerceAtLeast(0))
    val minGap = (
        minimumSelectionPixels.coerceIn(1, axisLength.coerceAtLeast(1)).toFloat() /
            axisLength.coerceAtLeast(1)
        ).coerceAtLeast(0.001f)
    val labelPixels = (axisLength * fraction).roundToInt().coerceIn(0, axisLength)
    val displayedLabelPixels = if (isStart) {
        labelPixels
    } else {
        endHandleDisplayPixels(
            axisLength = axisLength,
            absolutePixels = labelPixels,
            showAbsolutePosition = showEndAbsolutePosition,
        )
    }
    val handleModifier = if (horizontal) {
        Modifier
            .width(touchSize)
            .fillMaxHeight()
            .offset { IntOffset(offset, 0) }
    } else {
        Modifier
            .fillMaxWidth()
            .height(touchSize)
            .offset { IntOffset(0, offset) }
    }

    Box(
        modifier = handleModifier,
        contentAlignment = if (horizontal) {
            if (isStart) Alignment.TopCenter else Alignment.BottomCenter
        } else {
            if (isStart) Alignment.CenterStart else Alignment.CenterEnd
        },
    ) {
        Box(
            modifier = (if (horizontal) {
                Modifier.width(touchSize).height(72.dp)
            } else {
                Modifier.width(96.dp).height(touchSize)
            }).pointerInput(isStart, axisSizePx, minGap) {
                var activeFraction = latestFraction
                var fixedBoundary = latestOtherFraction
                var activeRange = if (isStart) {
                    activeFraction..fixedBoundary
                } else {
                    fixedBoundary..activeFraction
                }
                detectDragGestures(
                    onDragStart = {
                        activeFraction = latestFraction
                        fixedBoundary = latestOtherFraction
                        activeRange = if (isStart) {
                            activeFraction..fixedBoundary
                        } else {
                            fixedBoundary..activeFraction
                        }
                    },
                    onDragEnd = { latestOnRangeFinished(activeRange) },
                    onDragCancel = { latestOnRangeFinished(activeRange) },
                ) { change, dragAmount ->
                    change.consume()
                    val delta = (if (horizontal) dragAmount.x else dragAmount.y) / axisSizePx
                    if (isStart) {
                        val maximumStart = (fixedBoundary - minGap).coerceAtLeast(0f)
                        activeFraction = (activeFraction + delta).coerceIn(0f, maximumStart)
                        activeRange = activeFraction..fixedBoundary
                    } else {
                        val minimumEnd = (fixedBoundary + minGap).coerceAtMost(1f)
                        activeFraction = (activeFraction + delta).coerceIn(minimumEnd, 1f)
                        activeRange = fixedBoundary..activeFraction
                    }
                    latestOnRangeChange(activeRange)
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val endLabelModifier = if (isStart) {
                Modifier
            } else {
                val edge = stringResource(
                    if (horizontal) R.string.result_editor_edge_right
                    else R.string.result_editor_edge_bottom,
                )
                val origin = stringResource(
                    if (horizontal) R.string.result_editor_edge_left
                    else R.string.result_editor_edge_top,
                )
                val toggleLabel = if (showEndAbsolutePosition) {
                    stringResource(R.string.result_editor_crop_toggle_relative, edge)
                } else {
                    stringResource(R.string.result_editor_crop_toggle_absolute, origin)
                }
                val cropScaleDescription = stringResource(
                    R.string.result_editor_crop_scale_description,
                    edge,
                )
                val cropStateDescription = if (showEndAbsolutePosition) {
                    stringResource(
                        R.string.result_editor_crop_absolute_state,
                        origin,
                        labelPixels,
                    )
                } else {
                    stringResource(
                        R.string.result_editor_crop_relative_state,
                        edge,
                        displayedLabelPixels,
                    )
                }
                Modifier
                    .clickable(
                        onClickLabel = toggleLabel,
                        onClick = onEndPositionModeToggle,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = cropScaleDescription
                        stateDescription = cropStateDescription
                    }
            }
            Surface(
                modifier = endLabelModifier,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                shadowElevation = 2.dp,
            ) {
                Text(
                    text = stringResource(
                        if (isStart) R.string.result_editor_crop_start_label
                        else R.string.result_editor_crop_end_label,
                        if (isStart) labelPixels else displayedLabelPixels,
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

internal fun endHandleDisplayPixels(
    axisLength: Int,
    absolutePixels: Int,
    showAbsolutePosition: Boolean,
): Int = if (showAbsolutePosition) {
    absolutePixels
} else {
    absolutePixels - axisLength.coerceAtLeast(0)
}

private fun LazyListScope.screenshotFormatItems(
    state: ResultEditorUiState,
    onOutputFormatChange: (ImageOutputFormat) -> Unit,
    onJpegQualityChange: (Int) -> Unit,
) {
    item(key = "format-header") {
        SettingsSectionHeader(title = stringResource(R.string.result_editor_format_section))
    }
    item(key = "format-control") {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val jpegSelected = state.outputFormat == ImageOutputFormat.JPEG
            val pngSelected = state.outputFormat == ImageOutputFormat.PNG
            val jpegLabel = if (maxWidth < RESULT_FORMAT_COMPACT_WIDTH) {
                stringResource(R.string.result_editor_jpg_smaller_compact)
            } else {
                stringResource(R.string.result_editor_jpg_smaller)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = jpegSelected,
                    onClick = { onOutputFormatChange(ImageOutputFormat.JPEG) },
                    label = {
                        Text(
                            jpegLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RESULT_FORMAT_JPEG_TAG),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = jpegSelected,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                        selectedBorderWidth = 1.dp,
                    ),
                )
                FilterChip(
                    selected = pngSelected,
                    onClick = { onOutputFormatChange(ImageOutputFormat.PNG) },
                    label = {
                        Text(
                            stringResource(R.string.result_editor_png_lossless),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(RESULT_FORMAT_PNG_TAG),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = pngSelected,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                        selectedBorderWidth = 1.dp,
                    ),
                )
            }
        }
    }
    item(key = "jpeg-quality") {
        AnimatedVisibility(
            visible = state.outputFormat == ImageOutputFormat.JPEG,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 220),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(durationMillis = 160)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 200),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(durationMillis = 140)),
            label = "JPG质量显示切换",
        ) {
            SettingsSliderItem(
                title = stringResource(R.string.result_editor_jpeg_quality),
                value = state.jpegQuality.toFloat(),
                range = 70f..100f,
                steps = 5,
                onValueChange = {
                    onJpegQualityChange(
                        ImageOutputFormat.normalizeJpegQuality(it.roundToInt()),
                    )
                },
                valueFormatter = {
                    "${ImageOutputFormat.normalizeJpegQuality(it.roundToInt())}%"
                },
                description = null,
                modifier = Modifier.testTag(RESULT_JPEG_QUALITY_TAG),
            )
        }
    }
}

private fun LazyListScope.exportControlItem(
    state: ResultEditorUiState,
    exportLocation: String,
    onExport: () -> Unit,
    onViewExport: () -> Unit,
    onExportLocationClick: () -> Unit,
    onExportLocationDoubleClick: () -> Unit,
    onExportLocationLongClick: () -> Unit,
    onExpectedSizeLongClick: () -> Unit,
) {
    item(key = "export") {
        ExportControl(
            state = state,
            exportLocation = exportLocation,
            onExport = onExport,
            onViewExport = onViewExport,
            onExportLocationClick = onExportLocationClick,
            onExportLocationDoubleClick = onExportLocationDoubleClick,
            onExportLocationLongClick = onExportLocationLongClick,
            onExpectedSizeLongClick = onExpectedSizeLongClick,
        )
    }
}

private fun LazyListScope.sourceFrameControlItems(
    state: ResultEditorUiState,
    sourceFramesExpanded: Boolean,
    onSourceFramesExpandedChange: (Boolean) -> Unit,
    onRequestFramePreview: (Int) -> Unit,
    onReleaseFramePreview: (Int) -> Unit,
) {
    item(key = "source-frames-header") {
        SourceFramesHeader(
            frameCount = state.frames.size,
            expanded = sourceFramesExpanded,
            onExpandedChange = onSourceFramesExpandedChange,
        )
    }
    if (sourceFramesExpanded) {
        if (state.frames.isEmpty()) {
            item(key = "source-frames-empty") {
                Text(
                    text = stringResource(R.string.result_editor_no_source_frames),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(
                items = state.frames,
                key = { frame -> "source-frame-${frame.index}" },
            ) { frame ->
                SourceFramePreview(
                    frame = frame,
                    onRequestFramePreview = onRequestFramePreview,
                    onReleaseFramePreview = onReleaseFramePreview,
                )
            }
        }
    }
}

@Composable
private fun SourceFramesHeader(
    frameCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandedChange(!expanded) }
            .padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.result_editor_source_frames),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (frameCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.result_source_frame_count,
                    frameCount,
                    frameCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = stringResource(
                if (expanded) R.string.result_editor_collapse_source_frames
                else R.string.result_editor_expand_source_frames,
            ),
            modifier = Modifier
                .size(32.dp)
                .rotate(if (expanded) 180f else 0f),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceFramePreview(
    frame: SourceFrameUi,
    onRequestFramePreview: (Int) -> Unit,
    onReleaseFramePreview: (Int) -> Unit,
) {
    LaunchedEffect(frame.index, frame.bitmap) {
        if (frame.bitmap == null) onRequestFramePreview(frame.index)
    }
    DisposableEffect(frame.index) {
        onDispose { onReleaseFramePreview(frame.index) }
    }
    val aspectRatio = when {
        frame.width > 0 && frame.height > 0 -> frame.width.toFloat() / frame.height
        frame.bitmap != null && frame.bitmap.width > 0 && frame.bitmap.height > 0 -> {
            frame.bitmap.width.toFloat() / frame.bitmap.height
        }
        else -> 16f / 9f
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.result_editor_source_frame_title,
                    frame.index + 1,
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatStoredSize(frame.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            frame.bitmap?.let { bitmap ->
                val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                androidx.compose.foundation.Image(
                    bitmap = imageBitmap,
                    contentDescription = stringResource(
                        R.string.result_editor_source_frame_preview,
                        frame.index + 1,
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            } ?: CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ExportControl(
    state: ResultEditorUiState,
    exportLocation: String,
    onExport: () -> Unit,
    onViewExport: () -> Unit,
    onExportLocationClick: () -> Unit,
    onExportLocationDoubleClick: () -> Unit,
    onExportLocationLongClick: () -> Unit,
    onExpectedSizeLongClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SettingsSectionHeader(stringResource(R.string.result_editor_export_section))
        ExportLocationItem(
            exportLocation = exportLocation,
            onClick = onExportLocationClick,
            onDoubleClick = onExportLocationDoubleClick,
            onLongClick = onExportLocationLongClick,
        )
        ExportInfoItem(
            label = stringResource(R.string.result_editor_expected_size),
            value = stringResource(
                R.string.result_editor_output_dimensions,
                state.exportCount,
                state.finalWidth,
                state.finalHeight,
            ),
            rowTag = EXPECTED_SIZE_ROW_TAG,
            onLongClick = onExpectedSizeLongClick,
        )
        AnimatedContent(
            targetState = state.hasMatchingExport,
            transitionSpec = {
                (fadeIn(tween(160)) + scaleIn(tween(200), initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(100)) + scaleOut(tween(140), targetScale = 0.96f))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            label = "查看截图操作切换",
        ) { showViewAction ->
            if (showViewAction) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onViewExport,
                        modifier = Modifier.weight(1f).height(AppButtonHeight),
                    ) {
                        Text(stringResource(R.string.result_editor_view_export))
                    }
                    Button(
                        onClick = onExport,
                        colors = PrimaryButtonColors,
                        modifier = Modifier.weight(1f).height(AppButtonHeight),
                    ) {
                        Text(stringResource(R.string.result_editor_export_again))
                    }
                }
            } else {
                Button(
                    onClick = onExport,
                    colors = PrimaryButtonColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(AppButtonHeight),
                ) {
                    Text(
                        text = stringResource(R.string.result_editor_export_to_gallery),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportLocationItem(
    exportLocation: String,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ExportInfoItem(
        label = stringResource(R.string.result_editor_export_location),
        value = exportLocation,
        valueTag = EXPORT_LOCATION_PATH_TAG,
        rowTag = EXPORT_LOCATION_ROW_TAG,
        onClick = onClick,
        onDoubleClick = onDoubleClick,
        onLongClick = onLongClick,
    )
}

@Composable
private fun ExportInfoItem(
    label: String,
    value: String,
    valueTag: String? = null,
    rowTag: String? = null,
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val horizontalGap = 12.dp
    val stackedGap = 4.dp
    val interactionModifier = if (
        onClick != null || onDoubleClick != null || onLongClick != null
    ) {
        Modifier.combinedClickable(
            onClick = onClick ?: {},
            onDoubleClick = onDoubleClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier
    }
    Layout(
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = valueTag?.let { Modifier.testTag(it) } ?: Modifier,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(rowTag?.let { Modifier.testTag(it) } ?: Modifier)
            .then(interactionModifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val label = measurables[0].measure(looseConstraints)
        val path = measurables[1].measure(looseConstraints)
        val horizontalGapPx = horizontalGap.roundToPx()
        val stackedGapPx = stackedGap.roundToPx()
        val inline = label.width + horizontalGapPx + path.width <= constraints.maxWidth &&
            path.height <= label.height
        val contentHeight = if (inline) {
            maxOf(label.height, path.height)
        } else {
            label.height + stackedGapPx + path.height
        }
        layout(constraints.maxWidth, contentHeight) {
            if (inline) {
                label.placeRelative(0, (contentHeight - label.height) / 2)
                path.placeRelative(
                    constraints.maxWidth - path.width,
                    (contentHeight - path.height) / 2,
                )
            } else {
                label.placeRelative(0, 0)
                path.placeRelative(0, label.height + stackedGapPx)
            }
        }
    }
}
