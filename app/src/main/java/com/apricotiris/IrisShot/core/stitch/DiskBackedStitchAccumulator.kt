package com.apricotiris.IrisShot.core.stitch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.apricotiris.IrisShot.core.model.ScrollChunk
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.core.model.StitchMatchResult
import com.apricotiris.IrisShot.core.storage.SessionFileStore
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class FinalizedScroll(
    val bitmap: Bitmap,
    val chunks: List<ScrollChunk>,
    val logicalWidth: Int,
    val logicalHeight: Int,
) {
    val isChunked: Boolean get() = chunks.isNotEmpty()
}

/**
 * Stores the first scroll frame and every following non-overlapping strip as lossless disk chunks.
 *
 * Only the previous/current capture frames and a bounded preview remain in memory. In particular,
 * appending a frame never allocates a Bitmap whose size grows with the logical screenshot, so an
 * arbitrary session length no longer needs an in-memory pixel threshold.
 */
class DiskBackedStitchAccumulator(
    private val fileStore: SessionFileStore,
    private val sessionId: String,
    private val direction: ScrollDirection,
    previewTargetWidth: Int = 1,
    previewTargetHeight: Int = 1,
) : AutoCloseable {
    private val chunks = mutableListOf<ScrollChunk>()
    private var preview: Bitmap? = null
    private var chunkSequence = 0
    private val previewTargetWidth = previewTargetWidth.coerceAtLeast(1)
    private val previewTargetHeight = previewTargetHeight.coerceAtLeast(1)

    var logicalWidth: Int = 0
        private set
    var logicalHeight: Int = 0
        private set

    /** New captures always use the disk-backed representation. */
    val isChunked: Boolean get() = chunks.isNotEmpty()

    fun start(first: Bitmap) {
        check(preview == null && chunks.isEmpty()) { "拼接累积器已经开始" }
        val saved = fileStore.saveScrollChunk(sessionId, chunkSequence++, first)
        val initialPreview = try {
            createBoundedCopy(first, first.width, first.height)
        } catch (error: Throwable) {
            fileStore.deleteOwnedFile(saved.path, sessionId)
            throw error
        }
        chunks += saved
        logicalWidth = first.width
        logicalHeight = first.height
        preview = initialPreview
    }

    fun activeBitmap(): Bitmap = checkNotNull(preview) { "拼接累积器尚未开始" }

    fun append(
        next: Bitmap,
        match: StitchMatchResult,
        stitchEngine: StitchEngine,
    ) {
        val oldPreview = activeBitmap()
        val oldWidth = logicalWidth
        val oldHeight = logicalHeight
        val shift = kotlin.math.abs(if (direction.isVertical) match.dy else match.dx)
        require(shift > 0) { "新增滚动条带尺寸必须大于 0" }

        val newWidth = if (direction.isVertical) oldWidth else Math.addExact(oldWidth, shift)
        val newHeight = if (direction.isVertical) Math.addExact(oldHeight, shift) else oldHeight
        val strip = stitchEngine.extractNewContent(next, match, direction)
        var saved: ScrollChunk? = null
        var newPreview: Bitmap? = null
        try {
            saved = fileStore.saveScrollChunk(sessionId, chunkSequence++, strip)
            newPreview = appendPreview(
                oldPreview = oldPreview,
                strip = strip,
                oldWidth = oldWidth,
                oldHeight = oldHeight,
                newWidth = newWidth,
                newHeight = newHeight,
            )
        } catch (error: Throwable) {
            fileStore.deleteOwnedFile(saved?.path, sessionId)
            newPreview?.takeUnless { it.isRecycled }?.recycle()
            throw error
        } finally {
            if (!strip.isRecycled) strip.recycle()
        }
        if (direction.isForward) chunks += checkNotNull(saved) else chunks.add(0, checkNotNull(saved))
        logicalWidth = newWidth
        logicalHeight = newHeight
        preview = checkNotNull(newPreview)
        if (!oldPreview.isRecycled) oldPreview.recycle()
    }

    fun finalizeScroll(): FinalizedScroll {
        check(chunks.isNotEmpty()) { "拼接累积器尚未开始" }
        val finalPreview = buildPreview(chunks, logicalWidth, logicalHeight)
        preview?.takeUnless { it.isRecycled }?.recycle()
        preview = null
        return FinalizedScroll(finalPreview, chunks.toList(), logicalWidth, logicalHeight)
    }

    private fun appendPreview(
        oldPreview: Bitmap,
        strip: Bitmap,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
    ): Bitmap {
        val (previewWidth, previewHeight) = previewDimensions(newWidth, newHeight)
        val output = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (direction.isVertical) {
                val stripHeight = newHeight - oldHeight
                val oldScaledHeight = (oldHeight.toDouble() * previewHeight / newHeight)
                    .roundToInt().coerceIn(1, previewHeight)
                val stripScaledHeight = previewHeight - oldScaledHeight
                if (direction.isForward) {
                    canvas.drawBitmap(oldPreview, null, Rect(0, 0, previewWidth, oldScaledHeight), paint)
                    if (stripScaledHeight > 0) {
                        canvas.drawBitmap(
                            strip,
                            null,
                            Rect(0, oldScaledHeight, previewWidth, previewHeight),
                            paint,
                        )
                    }
                } else {
                    val leadingHeight = (stripHeight.toDouble() * previewHeight / newHeight)
                        .roundToInt().coerceIn(1, previewHeight)
                    canvas.drawBitmap(strip, null, Rect(0, 0, previewWidth, leadingHeight), paint)
                    if (leadingHeight < previewHeight) {
                        canvas.drawBitmap(
                            oldPreview,
                            null,
                            Rect(0, leadingHeight, previewWidth, previewHeight),
                            paint,
                        )
                    }
                }
            } else {
                val stripWidth = newWidth - oldWidth
                val oldScaledWidth = (oldWidth.toDouble() * previewWidth / newWidth)
                    .roundToInt().coerceIn(1, previewWidth)
                val stripScaledWidth = previewWidth - oldScaledWidth
                if (direction.isForward) {
                    canvas.drawBitmap(oldPreview, null, Rect(0, 0, oldScaledWidth, previewHeight), paint)
                    if (stripScaledWidth > 0) {
                        canvas.drawBitmap(
                            strip,
                            null,
                            Rect(oldScaledWidth, 0, previewWidth, previewHeight),
                            paint,
                        )
                    }
                } else {
                    val leadingWidth = (stripWidth.toDouble() * previewWidth / newWidth)
                        .roundToInt().coerceIn(1, previewWidth)
                    canvas.drawBitmap(strip, null, Rect(0, 0, leadingWidth, previewHeight), paint)
                    if (leadingWidth < previewWidth) {
                        canvas.drawBitmap(
                            oldPreview,
                            null,
                            Rect(leadingWidth, 0, previewWidth, previewHeight),
                            paint,
                        )
                    }
                }
            }
            return output
        } catch (error: Throwable) {
            if (!output.isRecycled) output.recycle()
            throw error
        }
    }

    private fun createBoundedCopy(source: Bitmap, width: Int, height: Int): Bitmap {
        val (previewWidth, previewHeight) = previewDimensions(width, height)
        return if (previewWidth == source.width && previewHeight == source.height) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(source, previewWidth, previewHeight, true)
        }
    }

    private fun buildPreview(
        orderedChunks: List<ScrollChunk>,
        width: Int,
        height: Int,
    ): Bitmap {
        val (previewWidth, previewHeight) = previewDimensions(width, height)
        val output = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            var logicalOffset = 0
            orderedChunks.forEach { chunk ->
                val bitmap = fileStore.resourceAccess.decodeBitmap(chunk.path)
                    ?: error("无法读取拼接分段: ${chunk.path}")
                try {
                    if (direction.isVertical) {
                        val start = (logicalOffset.toDouble() * previewHeight / height).roundToInt()
                        logicalOffset += chunk.height
                        val end = (logicalOffset.toDouble() * previewHeight / height).roundToInt()
                        canvas.drawBitmap(
                            bitmap,
                            null,
                            Rect(0, start, previewWidth, end.coerceAtLeast(start + 1)),
                            paint,
                        )
                    } else {
                        val start = (logicalOffset.toDouble() * previewWidth / width).roundToInt()
                        logicalOffset += chunk.width
                        val end = (logicalOffset.toDouble() * previewWidth / width).roundToInt()
                        canvas.drawBitmap(
                            bitmap,
                            null,
                            Rect(start, 0, end.coerceAtLeast(start + 1), previewHeight),
                            paint,
                        )
                    }
                } finally {
                    bitmap.recycle()
                }
            }
            return output
        } catch (error: Throwable) {
            if (!output.isRecycled) output.recycle()
            throw error
        }
    }

    private fun previewDimensions(width: Int, height: Int): Pair<Int, Int> {
        val totalPixels = width.toLong() * height
        val boundedPreviewScale = minOf(
            1.0,
            PREVIEW_MAX_WIDTH.toDouble() / width,
            PREVIEW_MAX_HEIGHT.toDouble() / height,
            sqrt(PREVIEW_MAX_PIXELS.toDouble() / totalPixels.coerceAtLeast(1L)),
        )
        val displayTargetScale = minOf(
            1.0,
            previewTargetWidth.toDouble() / width,
            previewTargetHeight.toDouble() / height,
        )
        // Keep the legacy bounded preview unless it would be smaller than the exact aspect-fit
        // bitmap needed by the on-screen strip. This avoids inflating an early full-screen frame
        // merely because the strip may eventually span the long axis of the display.
        val scale = maxOf(boundedPreviewScale, displayTargetScale)
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    override fun close() {
        preview?.takeUnless { it.isRecycled }?.recycle()
        preview = null
    }

    companion object {
        private const val PREVIEW_MAX_WIDTH = 1_200
        private const val PREVIEW_MAX_HEIGHT = 12_000
        private const val PREVIEW_MAX_PIXELS = 4_000_000L
    }
}
