package com.apricotiris.IrisShot.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess
import kotlin.math.roundToInt

internal data class ThumbnailCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toAndroidRect(): Rect = Rect(left, top, right, bottom)
}

internal fun thumbnailCrop(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): ThumbnailCrop {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(targetWidth > 0 && targetHeight > 0)
    val sourceRatio = sourceWidth.toDouble() / sourceHeight
    val targetRatio = targetWidth.toDouble() / targetHeight
    return if (sourceRatio > targetRatio) {
        val width = (sourceHeight * targetRatio).roundToInt().coerceIn(1, sourceWidth)
        val left = (sourceWidth - width) / 2
        ThumbnailCrop(left, 0, left + width, sourceHeight)
    } else {
        val height = (sourceWidth / targetRatio).roundToInt().coerceIn(1, sourceHeight)
        val top = (sourceHeight - height) / 2
        ThumbnailCrop(0, top, sourceWidth, top + height)
    }
}

internal fun thumbnailSampleSize(
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

/** Decodes one sharp viewport instead of shrinking an entire long screenshot into a thin strip. */
internal object ResultThumbnailLoader {
    fun load(
        resourceAccess: StorageResourceAccess,
        result: LayeredCaptureResult,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        require(targetWidth > 0 && targetHeight > 0)
        return candidatePaths(result).firstNotNullOfOrNull { path ->
            decodeViewport(resourceAccess, path, targetWidth, targetHeight)
        }
    }

    private fun candidatePaths(result: LayeredCaptureResult): Sequence<String> = sequence {
        result.thumbnailBitmapPath?.takeIf(String::isNotBlank)?.let { yield(it) }
        result.sourceFrames.sortedBy { it.index }.firstOrNull()?.let { first ->
            first.fullBitmapPath?.takeIf(String::isNotBlank)?.let { yield(it) }
            first.scrollBitmapPath.takeIf(String::isNotBlank)?.let { yield(it) }
        }
        result.scrollChunks
            .maxByOrNull { chunk -> chunk.width.toLong() * chunk.height }
            ?.path
            ?.takeIf(String::isNotBlank)
            ?.let { yield(it) }
        result.scrollLongBitmapPath.takeIf(String::isNotBlank)?.let { yield(it) }
    }.distinct()

    private fun decodeViewport(
        resourceAccess: StorageResourceAccess,
        path: String,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        val decoder = runCatching { resourceAccess.openRegionDecoder(path) }.getOrNull() ?: return null
        return try {
            if (decoder.width <= 0 || decoder.height <= 0) return null
            val crop = thumbnailCrop(decoder.width, decoder.height, targetWidth, targetHeight)
            val decoded = decoder.decodeRegion(
                crop.toAndroidRect(),
                BitmapFactory.Options().apply {
                    inSampleSize = thumbnailSampleSize(
                        crop.width,
                        crop.height,
                        targetWidth,
                        targetHeight,
                    )
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ) ?: return null
            if (decoded.width == targetWidth && decoded.height == targetHeight) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also { scaled ->
                    if (scaled !== decoded && !decoded.isRecycled) decoded.recycle()
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            decoder.close()
        }
    }
}
