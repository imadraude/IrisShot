package com.apricotiris.IrisShot.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess.ManagedRegionDecoder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** A logical range of the scroll layer plus the number of display pixels it should cover. */
data class ScrollViewportPreviewRequest(
    val start: Int,
    val end: Int,
    val targetCrossAxisPixels: Int,
    val targetAxisPixels: Int,
    val maxPixels: Long = DEFAULT_MAX_PIXELS,
) {
    companion object {
        const val DEFAULT_MAX_PIXELS = 10_000_000L
    }
}

/**
 * A sharp overlay for part of the editor's scroll layer.
 *
 * [start] and [end] remain in the full logical image coordinate system, so Compose can place
 * this bitmap over the low-resolution overview without changing crop geometry.
 */
data class ScrollViewportPreview(
    val bitmap: Bitmap,
    val start: Int,
    val end: Int,
    val horizontal: Boolean,
)

internal data class ScrollViewportGeometry(
    val horizontal: Boolean,
    val contentWidth: Int,
    val contentHeight: Int,
) {
    val axisLength: Int get() = if (horizontal) contentWidth else contentHeight
    val crossAxisLength: Int get() = if (horizontal) contentHeight else contentWidth
}

private data class ScrollViewportWindow(
    val start: Int,
    val end: Int,
) {
    val length: Int get() = end - start
}

internal inline fun <T> loadViewportPreviewSafely(block: () -> T?): T? = try {
    block()
} catch (_: OutOfMemoryError) {
    null
} catch (_: IllegalArgumentException) {
    null
}

/**
 * Decodes only the chunks intersecting the on-screen editor window. A legacy long image is used
 * as a fallback when chunks are unavailable, preserving compatibility with existing results.
 */
internal object ScrollChunkViewportPreviewLoader {
    fun load(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        request: ScrollViewportPreviewRequest,
    ): ScrollViewportPreview? {
        if (request.targetCrossAxisPixels <= 0 || request.targetAxisPixels <= 0 ||
            request.maxPixels <= 0L
        ) {
            return null
        }
        return loadViewportPreviewSafely {
            loadFromChunks(resourceAccess, result, request)
                ?: loadFromLongBitmap(resourceAccess, result, request)
        }
    }

    /** Returns chunk-derived editor geometry without opening an image resource. */
    fun chunkGeometry(result: LayeredCaptureResult): ScrollViewportGeometry? {
        val direction = result.templateSnapshot?.scrollDirection ?: return null
        val chunks = result.scrollChunks.takeIf { it.isNotEmpty() } ?: return null
        if (chunks.any { it.path.isBlank() || it.width <= 0 || it.height <= 0 }) return null
        val horizontal = !direction.isVertical
        val crossAxisLength = if (horizontal) chunks.first().height else chunks.first().width
        if (chunks.any { (if (horizontal) it.height else it.width) != crossAxisLength }) return null
        val axisLength = chunks.sumOf { if (horizontal) it.width.toLong() else it.height.toLong() }
        if (axisLength !in 1..Int.MAX_VALUE.toLong()) return null
        val geometry = if (horizontal) {
            ScrollViewportGeometry(true, axisLength.toInt(), crossAxisLength)
        } else {
            ScrollViewportGeometry(false, crossAxisLength, axisLength.toInt())
        }
        if (result.scrollContentWidth > 0 && result.scrollContentWidth != geometry.contentWidth) {
            return null
        }
        if (result.scrollContentHeight > 0 && result.scrollContentHeight != geometry.contentHeight) {
            return null
        }
        return geometry
    }

    private fun loadFromChunks(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        request: ScrollViewportPreviewRequest,
    ): ScrollViewportPreview? {
        val geometry = chunkGeometry(result) ?: return null
        val window = normalizedWindow(request, geometry.axisLength) ?: return null
        val target = targetSize(request, geometry.horizontal)
        var ownedPreview: Bitmap? = null
        try {
            val preview = Bitmap.createBitmap(
                target.width,
                target.height,
                Bitmap.Config.ARGB_8888,
            )
            ownedPreview = preview
            val canvas = Canvas(preview).apply { drawColor(Color.BLACK) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var chunkStart = 0L
            var covered = 0L
            for (chunk in result.scrollChunks) {
                val chunkLength = if (geometry.horizontal) chunk.width else chunk.height
                val chunkEnd = chunkStart + chunkLength
                val intersectionStart = maxOf(window.start.toLong(), chunkStart)
                val intersectionEnd = minOf(window.end.toLong(), chunkEnd)
                if (intersectionEnd > intersectionStart) {
                    val localStart = (intersectionStart - chunkStart).toInt()
                    val localEnd = (intersectionEnd - chunkStart).toInt()
                    val source = if (geometry.horizontal) {
                        Rect(localStart, 0, localEnd, chunk.height)
                    } else {
                        Rect(0, localStart, chunk.width, localEnd)
                    }
                    val destination = destinationRect(
                        horizontal = geometry.horizontal,
                        sourceStart = intersectionStart - window.start,
                        sourceEnd = intersectionEnd - window.start,
                        sourceAxisLength = window.length,
                        target = target,
                    )
                    if (!drawRegion(
                            resourceAccess,
                            chunk.path,
                            source,
                            destination,
                            canvas,
                            paint,
                        )
                    ) {
                        return null
                    }
                    covered += intersectionEnd - intersectionStart
                }
                chunkStart = chunkEnd
                if (chunkStart >= window.end) break
            }
            if (covered != window.length.toLong()) return null
            val completed = ScrollViewportPreview(
                preview,
                window.start,
                window.end,
                geometry.horizontal,
            )
            ownedPreview = null
            return completed
        } finally {
            ownedPreview?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }

    private fun loadFromLongBitmap(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        request: ScrollViewportPreviewRequest,
    ): ScrollViewportPreview? {
        val direction = result.templateSnapshot?.scrollDirection ?: return null
        val path = result.scrollLongBitmapPath.takeIf { it.isNotBlank() } ?: return null
        val horizontal = !direction.isVertical
        return withDecoder(resourceAccess, path) { decoder ->
            val geometry = legacyGeometry(result, horizontal, decoder) ?: return@withDecoder null
            val window = normalizedWindow(request, geometry.axisLength) ?: return@withDecoder null
            val decoderAxisLength = if (horizontal) decoder.width else decoder.height
            val sourceStart = floor(window.start.toDouble() * decoderAxisLength / geometry.axisLength)
                .toInt().coerceIn(0, decoderAxisLength - 1)
            val sourceEnd = ceil(window.end.toDouble() * decoderAxisLength / geometry.axisLength)
                .toInt().coerceIn(sourceStart + 1, decoderAxisLength)
            val source = if (horizontal) {
                Rect(sourceStart, 0, sourceEnd, decoder.height)
            } else {
                Rect(0, sourceStart, decoder.width, sourceEnd)
            }
            val target = targetSize(request, horizontal)
            var ownedBitmap: Bitmap? = null
            try {
                val decoded = decodeRegion(decoder, source, target.width, target.height)
                    ?: return@withDecoder null
                ownedBitmap = decoded
                if (decoded.width != target.width || decoded.height != target.height) {
                    val scaled = Bitmap.createScaledBitmap(
                        decoded,
                        target.width,
                        target.height,
                        true,
                    )
                    ownedBitmap = scaled
                    if (scaled !== decoded && !decoded.isRecycled) decoded.recycle()
                }
                val completed = ScrollViewportPreview(
                    checkNotNull(ownedBitmap),
                    window.start,
                    window.end,
                    horizontal,
                )
                ownedBitmap = null
                completed
            } finally {
                ownedBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    private fun legacyGeometry(
        result: LayeredCaptureResult,
        horizontal: Boolean,
        decoder: ManagedRegionDecoder,
    ): ScrollViewportGeometry? {
        val contentWidth = result.scrollContentWidth.takeIf { it > 0 } ?: decoder.width
        val contentHeight = result.scrollContentHeight.takeIf { it > 0 } ?: decoder.height
        if (contentWidth <= 0 || contentHeight <= 0) return null
        return ScrollViewportGeometry(horizontal, contentWidth, contentHeight)
    }

    private fun normalizedWindow(
        request: ScrollViewportPreviewRequest,
        axisLength: Int,
    ): ScrollViewportWindow? {
        if (axisLength <= 0) return null
        val start = request.start.coerceIn(0, axisLength - 1)
        val end = request.end.coerceIn(start + 1, axisLength)
        return ScrollViewportWindow(start, end)
    }

    private fun targetSize(
        request: ScrollViewportPreviewRequest,
        horizontal: Boolean): PreviewTargetSize {
        val requestedWidth = if (horizontal) request.targetAxisPixels else request.targetCrossAxisPixels
        val requestedHeight = if (horizontal) request.targetCrossAxisPixels else request.targetAxisPixels
        val pixels = requestedWidth.toLong() * requestedHeight
        val scale = if (pixels > request.maxPixels) {
            kotlin.math.sqrt(request.maxPixels.toDouble() / pixels)
        } else {
            1.0
        }
        return PreviewTargetSize(
            width = (requestedWidth * scale).roundToInt().coerceAtLeast(1),
            height = (requestedHeight * scale).roundToInt().coerceAtLeast(1),
        )
    }

    private fun destinationRect(
        horizontal: Boolean,
        sourceStart: Long,
        sourceEnd: Long,
        sourceAxisLength: Int,
        target: PreviewTargetSize,
    ): Rect {
        val targetAxisLength = if (horizontal) target.width else target.height
        val start = scaledBoundary(sourceStart, sourceAxisLength, targetAxisLength)
            .coerceIn(0, targetAxisLength - 1)
        val end = scaledBoundary(sourceEnd, sourceAxisLength, targetAxisLength)
            .coerceIn(start + 1, targetAxisLength)
        return if (horizontal) {
            Rect(start, 0, end, target.height)
        } else {
            Rect(0, start, target.width, end)
        }
    }

    private fun scaledBoundary(offset: Long, sourceLength: Int, targetLength: Int): Int =
        ((offset * targetLength + sourceLength / 2L) / sourceLength).toInt()

    private fun drawRegion(
        resourceAccess: StorageResourceAccess,
        path: String,
        source: Rect,
        destination: Rect,
        canvas: Canvas,
        paint: Paint,
    ): Boolean = withDecoder(resourceAccess, path) { decoder ->
        if (source.left < 0 || source.top < 0 || source.right > decoder.width ||
            source.bottom > decoder.height || source.isEmpty || destination.isEmpty
        ) {
            return@withDecoder false
        }
        val decoded = decodeRegion(decoder, source, destination.width(), destination.height())
            ?: return@withDecoder false
        try {
            canvas.drawBitmap(decoded, null, destination, paint)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
        true
    } ?: false

    private fun decodeRegion(
        decoder: ManagedRegionDecoder,
        source: Rect,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? = decoder.decodeRegion(
        source,
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize(
                source.width(),
                source.height(),
                targetWidth.coerceAtLeast(1),
                targetHeight.coerceAtLeast(1),
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )

    private fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth &&
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample
    }

    @Suppress("DEPRECATION")
    private inline fun <T> withDecoder(
        resourceAccess: StorageResourceAccess,
        path: String,
        block: (ManagedRegionDecoder) -> T,
    ): T? {
        val decoder = runCatching { resourceAccess.openRegionDecoder(path) }.getOrNull() ?: return null
        return try {
            block(decoder)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { decoder.close() }
        }
    }
}
