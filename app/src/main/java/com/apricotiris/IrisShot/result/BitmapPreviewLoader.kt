package com.apricotiris.IrisShot.result

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess
import kotlin.math.roundToInt

internal data class PreviewBitmap(
    val bitmap: Bitmap?,
    val width: Int,
    val height: Int,
)

internal object BitmapPreviewLoader {
    /**
     * Decodes a bounded preview while retaining the logical dimensions of the represented image.
     *
     * Segmented captures keep [path] as a small whole-image preview, so its file bounds are not
     * the dimensions used by crop controls or exports. Legacy callers can omit [contentWidth] and
     * [contentHeight] and continue to use the source file bounds.
     */
    fun load(
        resourceAccess: StorageResourceAccess,
        path: String?,
        maxWidth: Int = 1_600,
        maxHeight: Int = 2_400,
        contentWidth: Int = 0,
        contentHeight: Int = 0,
    ): PreviewBitmap {
        require(maxWidth > 0 && maxHeight > 0)
        if (path.isNullOrBlank()) return PreviewBitmap(null, 0, 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resourceAccess.decodeBitmap(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return PreviewBitmap(null, 0, 0)

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight) {
            sample *= 2
        }
        val decoded = resourceAccess.decodeBitmap(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
        val bitmap = decoded?.let { source ->
            val scaleToFit = minOf(
                1f,
                maxWidth.toFloat() / source.width,
                maxHeight.toFloat() / source.height,
            )
            if (scaleToFit >= 1f) source else {
                val scaled = Bitmap.createScaledBitmap(
                    source,
                    (source.width * scaleToFit).roundToInt().coerceAtLeast(1),
                    (source.height * scaleToFit).roundToInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== source && !source.isRecycled) source.recycle()
                scaled
            }
        }
        return PreviewBitmap(
            bitmap = bitmap,
            width = contentWidth.takeIf { it > 0 } ?: bounds.outWidth,
            height = contentHeight.takeIf { it > 0 } ?: bounds.outHeight,
        )
    }
}
