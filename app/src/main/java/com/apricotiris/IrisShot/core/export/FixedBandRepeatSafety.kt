package com.apricotiris.IrisShot.core.export

import android.graphics.Bitmap
import com.apricotiris.IrisShot.core.model.CapturedFixedLayer
import com.apricotiris.IrisShot.core.model.FixedAnchorPosition
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.RectConfig
import kotlin.math.abs

private const val REPEAT_BACKGROUND_LUMINANCE_DELTA = 12f
private const val REPEAT_BACKGROUND_EDGE_DELTA = 5f

internal data class TileSafeRepeatSelection(
    val region: RectConfig,
    val anchorCount: Int,
)

internal fun fixedBandRunsAlongScrollAxis(
    side: FixedSide,
    horizontalScroll: Boolean,
): Boolean = when (side) {
    FixedSide.TOP, FixedSide.BOTTOM -> horizontalScroll
    FixedSide.LEFT, FixedSide.RIGHT -> !horizontalScroll
}

/**
 * Keeps one-off controls out of the texture tiled between fixed anchors.
 *
 * [logicalWidth] and [logicalHeight] are the fixed layer's stored dimensions. They may be larger
 * than [bitmap] when a bounded preview is used, so sampling maps every logical coordinate back to
 * the decoded bitmap instead of treating preview pixels as persisted coordinates.
 */
internal fun selectTileSafeRepeatRegion(
    bitmap: Bitmap,
    requested: RectConfig?,
    horizontal: Boolean,
    anchorCount: Int,
    anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
    logicalWidth: Int = bitmap.width,
    logicalHeight: Int = bitmap.height,
): TileSafeRepeatSelection? {
    if (bitmap.isRecycled || logicalWidth <= 0 || logicalHeight <= 0) return null
    val region = requested?.clampTo(logicalWidth, logicalHeight)?.takeUnless { it.isEmpty }
        ?: return null
    val axisLength = if (horizontal) region.width else region.height
    val crossLength = if (horizontal) region.height else region.width
    if (axisLength < 12 || crossLength <= 0) return null

    val bitmapAxisLength = if (horizontal) bitmap.width else bitmap.height
    val logicalAxisLength = if (horizontal) logicalWidth else logicalHeight
    val regionAxisStart = if (horizontal) region.left else region.top
    val bitmapRegionStart = mapLogicalPixel(
        logicalPixel = regionAxisStart,
        logicalLength = logicalAxisLength,
        bitmapLength = bitmapAxisLength,
    )
    val bitmapRegionEnd = mapLogicalBoundaryCeil(
        logicalBoundary = regionAxisStart + axisLength,
        logicalLength = logicalAxisLength,
        bitmapLength = bitmapAxisLength,
    ).coerceAtLeast(bitmapRegionStart + 1)
    val sampledAxisPixels = IntArray(bitmapRegionEnd - bitmapRegionStart)
    val bitmapAxisOffsets = IntArray(axisLength) { axisOffset ->
        val bitmapAxis = mapLogicalPixel(
            logicalPixel = regionAxisStart + axisOffset,
            logicalLength = logicalAxisLength,
            bitmapLength = bitmapAxisLength,
        )
        (bitmapAxis - bitmapRegionStart).coerceIn(sampledAxisPixels.indices)
    }
    val sampleCount = minOf(crossLength, 32).coerceAtLeast(1)
    val means = FloatArray(axisLength)
    val edges = FloatArray(axisLength)
    repeat(sampleCount) { sampleIndex ->
        val logicalCrossOffset = if (sampleCount == 1) 0 else {
            (sampleIndex.toLong() * (crossLength - 1) / (sampleCount - 1)).toInt()
        }
        if (horizontal) {
            val bitmapY = mapLogicalPixel(
                logicalPixel = region.top + logicalCrossOffset,
                logicalLength = logicalHeight,
                bitmapLength = bitmap.height,
            )
            bitmap.getPixels(
                sampledAxisPixels,
                0,
                sampledAxisPixels.size,
                bitmapRegionStart,
                bitmapY,
                sampledAxisPixels.size,
                1,
            )
        } else {
            val bitmapX = mapLogicalPixel(
                logicalPixel = region.left + logicalCrossOffset,
                logicalLength = logicalWidth,
                bitmapLength = bitmap.width,
            )
            bitmap.getPixels(
                sampledAxisPixels,
                0,
                1,
                bitmapX,
                bitmapRegionStart,
                1,
                sampledAxisPixels.size,
            )
        }
        var previousLuminance = 0
        means.indices.forEach { axisOffset ->
            val color = sampledAxisPixels[bitmapAxisOffsets[axisOffset]]
            val red = color shr 16 and 0xFF
            val green = color shr 8 and 0xFF
            val blue = color and 0xFF
            val luminance = (red * 77 + green * 150 + blue * 29) shr 8
            means[axisOffset] += luminance.toFloat()
            if (axisOffset > 0) {
                edges[axisOffset] += abs(luminance - previousLuminance).toFloat()
            }
            previousLuminance = luminance
        }
    }
    means.indices.forEach { index ->
        means[index] /= sampleCount
        edges[index] /= sampleCount
    }
    val dominantLuminance = means.sortedArray()[axisLength / 2]
    val hasSalientContent = means.indices.any { index ->
        abs(means[index] - dominantLuminance) > REPEAT_BACKGROUND_LUMINANCE_DELTA &&
            edges[index] > REPEAT_BACKGROUND_EDGE_DELTA
    }
    if (!hasSalientContent) return null

    val quiet = BooleanArray(axisLength) { index ->
        abs(means[index] - dominantLuminance) <= REPEAT_BACKGROUND_LUMINANCE_DELTA &&
            edges[index] <= REPEAT_BACKGROUND_EDGE_DELTA
    }
    val minimumRun = (axisLength / 24).coerceIn(8, 48).coerceAtMost(axisLength)
    val runs = buildList {
        var start = 0
        while (start < axisLength) {
            while (start < axisLength && !quiet[start]) start++
            if (start >= axisLength) break
            var end = start + 1
            while (end < axisLength && quiet[end]) end++
            if (end - start >= minimumRun) add(start until end)
            start = end
        }
    }
    if (runs.isEmpty()) return null

    val configuredAnchors = anchorCount.coerceIn(1, 2)
    val singleAnchorBackground = when (anchorPosition) {
        FixedAnchorPosition.LEADING -> runs.lastOrNull { it.last == axisLength - 1 }
        FixedAnchorPosition.TRAILING -> runs.firstOrNull { it.first == 0 }
    }
    val chosen = if (configuredAnchors == 1 && singleAnchorBackground != null) {
        singleAnchorBackground
    } else {
        runs.maxWithOrNull(
            compareBy<IntRange> { it.last - it.first + 1 }
                .thenBy { -abs((it.first + it.last) - axisLength) },
        ) ?: return null
    }
    if (chosen.first == 0 && chosen.last == axisLength - 1) return null

    val preservesSingleAnchor = when (anchorPosition) {
        FixedAnchorPosition.LEADING -> chosen.last == axisLength - 1
        FixedAnchorPosition.TRAILING -> chosen.first == 0
    }
    val resolvedAnchorCount = if (configuredAnchors == 1 && !preservesSingleAnchor) 2
        else configuredAnchors
    val safeRegion = if (horizontal) {
        RectConfig(
            left = region.left + chosen.first,
            top = region.top,
            right = region.left + chosen.last + 1,
            bottom = region.bottom,
        )
    } else {
        RectConfig(
            left = region.left,
            top = region.top + chosen.first,
            right = region.right,
            bottom = region.top + chosen.last + 1,
        )
    }
    return TileSafeRepeatSelection(safeRegion, resolvedAnchorCount)
}

internal fun resolveTileSafeFixedLayer(
    layer: CapturedFixedLayer,
    bitmap: Bitmap,
    logicalWidth: Int,
    logicalHeight: Int,
    horizontalScroll: Boolean,
): CapturedFixedLayer {
    if (!fixedBandRunsAlongScrollAxis(layer.side, horizontalScroll)) return layer
    val horizontalRepeat = layer.side == FixedSide.TOP || layer.side == FixedSide.BOTTOM
    val selection = selectTileSafeRepeatRegion(
        bitmap = bitmap,
        requested = layer.repeatRegion,
        horizontal = horizontalRepeat,
        anchorCount = layer.anchorCount,
        anchorPosition = layer.anchorPosition,
        logicalWidth = logicalWidth,
        logicalHeight = logicalHeight,
    ) ?: return layer
    return layer.copy(
        anchorCount = selection.anchorCount,
        repeatRegion = selection.region,
    )
}

private fun mapLogicalPixel(
    logicalPixel: Int,
    logicalLength: Int,
    bitmapLength: Int,
): Int = (logicalPixel.toLong() * bitmapLength / logicalLength)
    .toInt()
    .coerceIn(0, bitmapLength - 1)

private fun mapLogicalBoundaryCeil(
    logicalBoundary: Int,
    logicalLength: Int,
    bitmapLength: Int,
): Int = ((logicalBoundary.toLong() * bitmapLength + logicalLength - 1L) / logicalLength)
    .toInt()
    .coerceIn(1, bitmapLength)
