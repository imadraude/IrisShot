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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class LogicalFrameWindow(
    val start: Int,
    val end: Int,
    val logicalAxisLength: Int,
    val frameWidth: Int,
    val frameHeight: Int,
    val horizontal: Boolean,
)

internal data class PreviewTargetSize(
    val width: Int,
    val height: Int,
)

internal fun boundedPreviewTargetSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxWidth: Int,
    maxHeight: Int,
    maxPixels: Long,
): PreviewTargetSize {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(maxWidth > 0 && maxHeight > 0 && maxPixels > 0L)
    val sourcePixels = sourceWidth.toLong() * sourceHeight
    val scale = minOf(
        1.0,
        maxWidth.toDouble() / sourceWidth,
        maxHeight.toDouble() / sourceHeight,
        sqrt(maxPixels.toDouble() / sourcePixels),
    )
    return PreviewTargetSize(
        width = Math.nextUp(sourceWidth * scale).toInt().coerceAtLeast(1),
        height = Math.nextUp(sourceHeight * scale).toInt().coerceAtLeast(1),
    )
}

/**
 * Locates an accepted keyframe inside the final logical scroll image.
 *
 * Forward captures append each new strip, while reverse captures prepend it. The source frame
 * paths can therefore be discarded after disk rollover without losing the viewport coordinates
 * needed by the result editor.
 */
internal fun logicalFrameWindow(
    result: LayeredCaptureResult,
    frameIndex: Int,
): LogicalFrameWindow? {
    val chunks = result.scrollChunks
    val direction = result.templateSnapshot?.scrollDirection ?: return null
    if (chunks.isEmpty()) return null
    val frames = result.sourceFrames.sortedBy { it.index }
    val targetPosition = frames.indexOfFirst { it.index == frameIndex }
    if (targetPosition < 0) return null
    val first = frames.firstOrNull() ?: return null
    val target = frames[targetPosition]
    if (target.width <= 0 || target.height <= 0 || first.width <= 0 || first.height <= 0) return null

    val horizontal = !direction.isVertical
    val firstAxisLength = if (horizontal) first.width else first.height
    val targetAxisLength = if (horizontal) target.width else target.height
    val logicalAxisLengthLong = chunks.sumOf {
        if (horizontal) it.width.toLong() else it.height.toLong()
    }
    if (logicalAxisLengthLong !in 1..Int.MAX_VALUE.toLong()) return null
    val logicalAxisLength = logicalAxisLengthLong.toInt()

    var cumulativeShift = 0L
    var totalShift = 0L
    frames.forEachIndexed { position, frame ->
        val shift = abs(if (horizontal) frame.offsetX.toLong() else frame.offsetY.toLong())
        totalShift += shift
        if (position <= targetPosition) cumulativeShift += shift
    }
    // A chunked result is built from the full first keyframe followed by one non-overlapping strip
    // per accepted frame. Reject inconsistent metadata instead of showing a neighboring viewport.
    if (firstAxisLength.toLong() + totalShift != logicalAxisLengthLong) return null

    val startLong = if (direction.isForward) {
        cumulativeShift
    } else {
        totalShift - cumulativeShift
    }
    val endLong = startLong + targetAxisLength
    if (startLong < 0L || endLong > logicalAxisLengthLong || endLong <= startLong) return null
    return LogicalFrameWindow(
        start = startLong.toInt(),
        end = endLong.toInt(),
        logicalAxisLength = logicalAxisLength,
        frameWidth = target.width,
        frameHeight = target.height,
        horizontal = horizontal,
    )
}

/** Builds a bounded keyframe viewport preview from the non-overlapping scroll chunks. */
internal object ChunkFramePreviewLoader {
    /** Builds a sharper bounded whole-image preview directly from lossless scroll chunks. */
    fun loadOverview(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        maxWidth: Int,
        maxHeight: Int,
        maxPixels: Long,
    ): PreviewBitmap? {
        require(maxWidth > 0 && maxHeight > 0 && maxPixels > 0L)
        val direction = result.templateSnapshot?.scrollDirection ?: return null
        val chunks = result.scrollChunks.takeIf { it.isNotEmpty() } ?: return null
        if (chunks.any { it.path.isBlank() || it.width <= 0 || it.height <= 0 }) return null

        val horizontal = !direction.isVertical
        val crossAxisLength = if (horizontal) chunks.first().height else chunks.first().width
        if (chunks.any { (if (horizontal) it.height else it.width) != crossAxisLength }) return null
        val mainAxisLengthLong = chunks.sumOf {
            if (horizontal) it.width.toLong() else it.height.toLong()
        }
        if (mainAxisLengthLong !in 1..Int.MAX_VALUE.toLong()) return null
        val sourceWidth = if (horizontal) mainAxisLengthLong.toInt() else crossAxisLength
        val sourceHeight = if (horizontal) crossAxisLength else mainAxisLengthLong.toInt()
        if (result.scrollContentWidth > 0 && result.scrollContentWidth != sourceWidth) return null
        if (result.scrollContentHeight > 0 && result.scrollContentHeight != sourceHeight) return null

        val target = boundedPreviewTargetSize(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxPixels = maxPixels,
        )
        val preview = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview).apply { drawColor(Color.BLACK) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val targetAxisLength = if (horizontal) target.width else target.height
        var logicalOffset = 0L
        chunks.forEach { chunk ->
            val chunkLength = if (horizontal) chunk.width else chunk.height
            val nextOffset = logicalOffset + chunkLength
            val destinationStart = scaledBoundary(
                logicalOffset,
                mainAxisLengthLong.toInt(),
                targetAxisLength,
            ).coerceIn(0, targetAxisLength - 1)
            val destinationEnd = scaledBoundary(
                nextOffset,
                mainAxisLengthLong.toInt(),
                targetAxisLength,
            ).coerceIn(destinationStart + 1, targetAxisLength)
            val source = Rect(0, 0, chunk.width, chunk.height)
            val destination = if (horizontal) {
                Rect(destinationStart, 0, destinationEnd, target.height)
            } else {
                Rect(0, destinationStart, target.width, destinationEnd)
            }
            if (!drawRegion(resourceAccess, chunk.path, source, destination, canvas, paint)) {
                preview.recycle()
                return null
            }
            logicalOffset = nextOffset
        }
        return PreviewBitmap(preview, sourceWidth, sourceHeight)
    }

    fun load(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        frameIndex: Int,
        maxWidth: Int = 320,
        maxHeight: Int = 180,
    ): Bitmap? {
        require(maxWidth > 0 && maxHeight > 0)
        val window = logicalFrameWindow(result, frameIndex) ?: return null
        val scale = minOf(
            1f,
            maxWidth.toFloat() / window.frameWidth,
            maxHeight.toFloat() / window.frameHeight,
        )
        val previewWidth = (window.frameWidth * scale).roundToInt().coerceAtLeast(1)
        val previewHeight = (window.frameHeight * scale).roundToInt().coerceAtLeast(1)
        return loadFromChunks(resourceAccess, result, window, previewWidth, previewHeight)
            ?: loadFromOverview(resourceAccess, result, window, previewWidth, previewHeight)
    }

    private fun loadFromChunks(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        window: LogicalFrameWindow,
        previewWidth: Int,
        previewHeight: Int,
    ): Bitmap? {
        val preview = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview).apply { drawColor(Color.BLACK) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val frameAxisLength = window.end - window.start
        var chunkStart = 0L
        var coveredLength = 0L
        for (chunk in result.scrollChunks) {
            val chunkLength = if (window.horizontal) chunk.width else chunk.height
            if (chunkLength <= 0 || chunk.width <= 0 || chunk.height <= 0) {
                preview.recycle()
                return null
            }
            val chunkEnd = chunkStart + chunkLength
            val intersectionStart = maxOf(window.start.toLong(), chunkStart)
            val intersectionEnd = minOf(window.end.toLong(), chunkEnd)
            if (intersectionEnd > intersectionStart) {
                val localStart = (intersectionStart - chunkStart).toInt()
                val localEnd = (intersectionEnd - chunkStart).toInt()
                val targetAxisLength = if (window.horizontal) previewWidth else previewHeight
                val destinationStart = scaledBoundary(
                    intersectionStart - window.start,
                    frameAxisLength,
                    targetAxisLength,
                ).coerceIn(0, targetAxisLength - 1)
                val destinationEnd = scaledBoundary(
                    intersectionEnd - window.start,
                    frameAxisLength,
                    targetAxisLength,
                ).coerceIn(destinationStart + 1, targetAxisLength)
                val source = if (window.horizontal) {
                    Rect(localStart, 0, localEnd, chunk.height)
                } else {
                    Rect(0, localStart, chunk.width, localEnd)
                }
                val destination = if (window.horizontal) {
                    Rect(destinationStart, 0, destinationEnd, previewHeight)
                } else {
                    Rect(0, destinationStart, previewWidth, destinationEnd)
                }
                if (!drawRegion(resourceAccess, chunk.path, source, destination, canvas, paint)) {
                    preview.recycle()
                    return null
                }
                coveredLength += intersectionEnd - intersectionStart
            }
            chunkStart = chunkEnd
            if (chunkStart >= window.end) break
        }
        if (coveredLength != frameAxisLength.toLong()) {
            preview.recycle()
            return null
        }
        return preview
    }

    private fun loadFromOverview(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        window: LogicalFrameWindow,
        previewWidth: Int,
        previewHeight: Int,
    ): Bitmap? {
        val path = result.scrollLongBitmapPath.takeIf { it.isNotBlank() } ?: return null
        return withDecoder(resourceAccess, path) { decoder ->
            val previewAxisLength = if (window.horizontal) decoder.width else decoder.height
            val sourceStart = (window.start.toLong() * previewAxisLength / window.logicalAxisLength)
                .toInt().coerceIn(0, (previewAxisLength - 1).coerceAtLeast(0))
            val sourceEnd = (
                (window.end.toLong() * previewAxisLength + window.logicalAxisLength - 1L) /
                    window.logicalAxisLength
                ).toInt().coerceIn(sourceStart + 1, previewAxisLength)
            val source = if (window.horizontal) {
                Rect(sourceStart, 0, sourceEnd, decoder.height)
            } else {
                Rect(0, sourceStart, decoder.width, sourceEnd)
            }
            decodeRegion(decoder, source, previewWidth, previewHeight)?.let { decoded ->
                if (decoded.width == previewWidth && decoded.height == previewHeight) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, previewWidth, previewHeight, true).also {
                        if (it !== decoded && !decoded.isRecycled) decoded.recycle()
                    }
                }
            }
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
            inSampleSize = calculateSampleSize(
                source.width(),
                source.height(),
                targetWidth.coerceAtLeast(1),
                targetHeight.coerceAtLeast(1),
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )

    private fun calculateSampleSize(
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
        val decoder = runCatching { resourceAccess.openRegionDecoder(path) }.getOrNull()
            ?: return null
        return try {
            block(decoder)
        } catch (_: Exception) {
            null
        } finally {
            decoder.close()
        }
    }
}
