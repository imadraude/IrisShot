package com.apricotiris.IrisShot.result

import kotlin.math.ceil
import kotlin.math.floor

/** A source-image region decoded at [sampleSize] for the high-resolution viewer. */
data class ViewerRegionRequest(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val sampleSize: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Chooses a sharper, viewport-sized source region when the overview bitmap would be enlarged.
 *
 * The returned rectangle includes a small prefetch gutter and is aligned to stable source-image
 * boundaries so ordinary pan updates do not continuously restart region decoding.
 */
internal fun viewerRegionRequest(
    sourceWidth: Int,
    sourceHeight: Int,
    overviewSampleSize: Int,
    viewportWidth: Float,
    viewportHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): ViewerRegionRequest? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || overviewSampleSize <= 1 ||
        !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
        viewportWidth <= 0f || viewportHeight <= 0f ||
        !zoom.isFinite() || zoom <= 0f ||
        !offsetX.isFinite() || !offsetY.isFinite()
    ) {
        return null
    }
    val fitScale = minOf(
        viewportWidth / sourceWidth.toFloat(),
        viewportHeight / sourceHeight.toFloat(),
    )
    if (!fitScale.isFinite() || fitScale <= 0f) return null
    val displayScale = fitScale * zoom
    val requestedSample = viewerDetailSampleSize(displayScale)
    if (requestedSample >= overviewSampleSize) return null

    val imageLeft = viewportWidth / 2f + offsetX - sourceWidth * displayScale / 2f
    val imageTop = viewportHeight / 2f + offsetY - sourceHeight * displayScale / 2f
    val visibleLeft = floor((-imageLeft / displayScale).coerceIn(0f, sourceWidth.toFloat()))
        .toInt()
    val visibleTop = floor((-imageTop / displayScale).coerceIn(0f, sourceHeight.toFloat()))
        .toInt()
    val visibleRight = ceil(
        ((viewportWidth - imageLeft) / displayScale).coerceIn(0f, sourceWidth.toFloat()),
    ).toInt()
    val visibleBottom = ceil(
        ((viewportHeight - imageTop) / displayScale).coerceIn(0f, sourceHeight.toFloat()),
    ).toInt()
    if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null

    val gutterX = ((visibleRight - visibleLeft) * DETAIL_PREFETCH_RATIO).toInt()
    val gutterY = ((visibleBottom - visibleTop) * DETAIL_PREFETCH_RATIO).toInt()
    val alignment = (DETAIL_REGION_ALIGNMENT.toLong() * requestedSample)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val left = alignDown((visibleLeft - gutterX).coerceAtLeast(0), alignment)
    val top = alignDown((visibleTop - gutterY).coerceAtLeast(0), alignment)
    val right = alignUp(
        (visibleRight + gutterX).coerceAtMost(sourceWidth),
        alignment,
    ).coerceAtMost(sourceWidth)
    val bottom = alignUp(
        (visibleBottom + gutterY).coerceAtMost(sourceHeight),
        alignment,
    ).coerceAtMost(sourceHeight)
    if (right <= left || bottom <= top) return null
    return ViewerRegionRequest(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        sampleSize = requestedSample,
    )
}

internal fun viewerDetailSampleSize(displayScale: Float): Int {
    if (!displayScale.isFinite() || displayScale <= 0f) return 1
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2) {
        val next = sample * 2
        if (next.toFloat() * displayScale > 1f) break
        sample = next
    }
    return sample
}

private fun alignDown(value: Int, alignment: Int): Int =
    value / alignment.coerceAtLeast(1) * alignment.coerceAtLeast(1)

private fun alignUp(value: Int, alignment: Int): Int {
    val safeAlignment = alignment.coerceAtLeast(1)
    return ((value.toLong() + safeAlignment - 1L) / safeAlignment * safeAlignment)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

private const val DETAIL_PREFETCH_RATIO = 0.125f
private const val DETAIL_REGION_ALIGNMENT = 64
