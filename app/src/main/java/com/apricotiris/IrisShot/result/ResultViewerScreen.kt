package com.apricotiris.IrisShot.result

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.ui.AppTopBar
import kotlinx.coroutines.delay

data class ResultViewerUiState(
    val resources: List<String> = emptyList(),
    val displayNames: List<String> = emptyList(),
    val selectedIndex: Int = 0,
    val bitmap: Bitmap? = null,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val overviewSampleSize: Int = 1,
    val detailBitmap: Bitmap? = null,
    val detailRequest: ViewerRegionRequest? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

@Composable
fun ResultViewerScreen(
    state: ResultViewerUiState,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
    onRetry: () -> Unit,
    onDetailRequest: (ViewerRegionRequest?) -> Unit = {},
) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.result_viewer_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.result_action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (state.resources.size > 1) {
                val selectedIndex = state.selectedIndex.coerceIn(state.resources.indices)
                val tabListState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
                LaunchedEffect(selectedIndex) {
                    tabListState.scrollToItem(selectedIndex)
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface),
                    state = tabListState,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    items(
                        count = state.resources.size,
                        key = { index -> index },
                    ) { index ->
                        val label = state.displayNames.getOrNull(index)
                            ?.substringBeforeLast('.')
                            ?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.result_viewer_image_number, index + 1)
                        Tab(
                            selected = index == state.selectedIndex,
                            onClick = { onSelect(index) },
                            modifier = Modifier.widthIn(min = 96.dp, max = 180.dp),
                            text = {
                                Text(
                                    text = label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loading -> CircularProgressIndicator(color = Color.White)
                    state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = state.error,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.result_action_retry))
                        }
                    }
                    state.bitmap != null -> ZoomableResultImage(
                        state = state,
                        onDetailRequest = onDetailRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableResultImage(
    state: ResultViewerUiState,
    onDetailRequest: (ViewerRegionRequest?) -> Unit,
) {
    val bitmap = checkNotNull(state.bitmap)
    val zoomDescription = stringResource(R.string.result_viewer_zoom_description)
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val viewportWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val overviewSampleSize = state.overviewSampleSize.coerceAtLeast(1)
        val sourceWidth = state.sourceWidth.takeIf { it > 0 }
            ?: (bitmap.width.toLong() * overviewSampleSize).coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        val sourceHeight = state.sourceHeight.takeIf { it > 0 }
            ?: (bitmap.height.toLong() * overviewSampleSize).coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        val fitScale = minOf(
            viewportWidth / sourceWidth.coerceAtLeast(1),
            viewportHeight / sourceHeight.coerceAtLeast(1),
        )
        val fittedWidth = sourceWidth * fitScale
        val fittedHeight = sourceHeight * fitScale
        val maxZoom = viewerMaximumZoom(
            viewportWidth = viewportWidth,
            fittedWidth = fittedWidth,
        )
        val doubleTapZoom = viewerDoubleTapZoom(
            viewportWidth = viewportWidth,
            fittedWidth = fittedWidth,
            maximumZoom = maxZoom,
        )
        var scale by remember(bitmap, constraints.maxWidth, constraints.maxHeight) {
            mutableFloatStateOf(1f)
        }
        var offset by remember(bitmap, constraints.maxWidth, constraints.maxHeight) {
            mutableStateOf(Offset.Zero)
        }

        fun clamp(candidate: Offset, candidateScale: Float): Offset {
            val maxX = ((fittedWidth * candidateScale - viewportWidth) / 2f).coerceAtLeast(0f)
            val maxY = ((fittedHeight * candidateScale - viewportHeight) / 2f).coerceAtLeast(0f)
            return Offset(
                candidate.x.coerceIn(-maxX, maxX),
                candidate.y.coerceIn(-maxY, maxY),
            )
        }

        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            val nextScale = (scale * zoomChange).coerceIn(MIN_ZOOM, maxZoom)
            offset = clamp(offset + panChange, nextScale)
            scale = nextScale
        }
        val regionRequest = remember(
            sourceWidth,
            sourceHeight,
            overviewSampleSize,
            viewportWidth,
            viewportHeight,
            scale,
            offset,
        ) {
            viewerRegionRequest(
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                overviewSampleSize = overviewSampleSize,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                zoom = scale,
                offsetX = offset.x,
                offsetY = offset.y,
            )
        }
        val latestDetailRequest by rememberUpdatedState(onDetailRequest)
        LaunchedEffect(regionRequest) {
            delay(REGION_DECODE_DEBOUNCE_MS)
            latestDetailRequest(regionRequest)
        }
        val bitmapPaint = remember {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                isDither = true
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(transformState)
                .pointerInput(bitmap, doubleTapZoom) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > MIN_ZOOM) MIN_ZOOM else doubleTapZoom
                            offset = Offset.Zero
                        },
                    )
                }
                .semantics {
                    contentDescription = zoomDescription
                },
        ) {
            val displayScale = fitScale * scale
            val imageLeft = viewportWidth / 2f + offset.x - sourceWidth * displayScale / 2f
            val imageTop = viewportHeight / 2f + offset.y - sourceHeight * displayScale / 2f
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(
                        imageLeft,
                        imageTop,
                        imageLeft + sourceWidth * displayScale,
                        imageTop + sourceHeight * displayScale,
                    ),
                    bitmapPaint,
                )
                val detailBitmap = state.detailBitmap
                val detail = state.detailRequest
                if (detailBitmap != null && detail != null && !detailBitmap.isRecycled) {
                    canvas.nativeCanvas.drawBitmap(
                        detailBitmap,
                        null,
                        RectF(
                            imageLeft + detail.left * displayScale,
                            imageTop + detail.top * displayScale,
                            imageLeft + detail.right * displayScale,
                            imageTop + detail.bottom * displayScale,
                        ),
                        bitmapPaint,
                    )
                }
            }
        }
    }
}

internal fun viewerMaximumZoom(
    viewportWidth: Float,
    fittedWidth: Float,
): Float {
    val widthFillZoom = viewerWidthFillZoom(viewportWidth, fittedWidth)
    return maxOf(BASE_MAX_ZOOM, widthFillZoom * WIDTH_FILL_ZOOM_MULTIPLIER)
}

internal fun viewerDoubleTapZoom(
    viewportWidth: Float,
    fittedWidth: Float,
    maximumZoom: Float = viewerMaximumZoom(viewportWidth, fittedWidth),
): Float = maxOf(
    DOUBLE_TAP_ZOOM,
    viewerWidthFillZoom(viewportWidth, fittedWidth),
).coerceAtMost(maximumZoom)

private fun viewerWidthFillZoom(viewportWidth: Float, fittedWidth: Float): Float {
    if (!viewportWidth.isFinite() || !fittedWidth.isFinite() ||
        viewportWidth <= 0f || fittedWidth <= 0f
    ) {
        return MIN_ZOOM
    }
    return (viewportWidth / fittedWidth).coerceAtLeast(MIN_ZOOM)
}

private const val MIN_ZOOM = 1f
private const val BASE_MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val WIDTH_FILL_ZOOM_MULTIPLIER = 2.5f
private const val REGION_DECODE_DEBOUNCE_MS = 80L
