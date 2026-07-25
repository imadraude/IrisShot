package com.apricotiris.IrisShot.core.image

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import com.apricotiris.IrisShot.core.model.RectConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal fun packRgbaPlane(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): ByteBuffer {
    require(width > 0 && height > 0)
    require(rowStride > 0 && pixelStride > 0)
    val source = buffer.duplicate()
    val sourceStart = source.position()
    val sourceLimit = source.limit()
    val packedByteCount = Math.multiplyExact(Math.multiplyExact(width, height), 4)
    val packed = ByteBuffer.allocateDirect(packedByteCount).order(ByteOrder.nativeOrder())

    if (pixelStride == 4) {
        val packedRowBytes = Math.multiplyExact(width, 4)
        val row = source.duplicate()
        for (y in 0 until height) {
            val rowStart = sourceStart + y * rowStride
            val rowEnd = rowStart + packedRowBytes
            require(rowStart >= sourceStart && rowEnd <= sourceLimit) {
                "Image buffer 比声明尺寸短（rowStride=$rowStride, pixelStride=$pixelStride）"
            }
            row.limit(sourceLimit)
            row.position(rowStart)
            row.limit(rowEnd)
            packed.put(row)
        }
    } else {
        for (y in 0 until height) {
            val rowStart = sourceStart + y * rowStride
            for (x in 0 until width) {
                val pixelStart = rowStart + x * pixelStride
                require(pixelStart < sourceLimit) {
                    "Image buffer 比声明尺寸短（rowStride=$rowStride, pixelStride=$pixelStride）"
                }
                when {
                    pixelStride > 4 && pixelStart + 3 < sourceLimit -> {
                        packed.put(source.get(pixelStart))
                        packed.put(source.get(pixelStart + 1))
                        packed.put(source.get(pixelStart + 2))
                        packed.put(source.get(pixelStart + 3))
                    }
                    pixelStride == 3 && pixelStart + 2 < sourceLimit -> {
                        packed.put(source.get(pixelStart))
                        packed.put(source.get(pixelStart + 1))
                        packed.put(source.get(pixelStart + 2))
                        packed.put(0xFF.toByte())
                    }
                    else -> {
                        val luminance = source.get(pixelStart)
                        packed.put(luminance)
                        packed.put(luminance)
                        packed.put(luminance)
                        packed.put(0xFF.toByte())
                    }
                }
            }
        }
    }
    return packed.apply { rewind() }
}

object BitmapFrameUtils {
    /**
     * Converts an ImageReader RGBA image without assuming tightly packed rows.
     * Both row padding and uncommon pixel strides are handled explicitly.
     */
    @JvmStatic
    fun imageToBitmap(image: Image, cropRegion: RectConfig? = null): Bitmap {
        require(image.planes.isNotEmpty()) { "Image 没有可读取的 plane" }
        val width = image.width
        val height = image.height
        require(width > 0 && height > 0) { "Image 尺寸无效: ${width}x$height" }

        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(rowStride > 0 && pixelStride > 0) {
            "Image stride 无效: row=$rowStride, pixel=$pixelStride"
        }

        val packed = packRgbaPlane(
            buffer = plane.buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(packed)

        val region = cropRegion?.clampTo(width, height) ?: return bitmap
        require(!region.isEmpty) { "裁剪区域为空: $cropRegion" }
        if (region == RectConfig.full(width, height)) return bitmap
        return Bitmap.createBitmap(bitmap, region.left, region.top, region.width, region.height).also {
            bitmap.recycle()
        }
    }

    @JvmStatic
    fun crop(bitmap: Bitmap, region: RectConfig): Bitmap {
        val safe = region.clampTo(bitmap.width, bitmap.height)
        require(!safe.isEmpty) { "裁剪区域不在 Bitmap 内: $region" }
        return Bitmap.createBitmap(bitmap, safe.left, safe.top, safe.width, safe.height)
    }

    @JvmStatic
    fun downscaleForAnalysis(bitmap: Bitmap, scale: Float = 0.5f): Bitmap {
        require(scale > 0f && scale <= 1f) { "分析缩放比例必须位于 (0, 1]" }
        if (scale == 1f) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    @JvmStatic
    fun thumbnail(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        require(maxWidth > 0 && maxHeight > 0)
        val scale = minOf(
            1f,
            maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height
        )
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /** Returns a clockwise quarter-turn copy without taking ownership of [bitmap]. */
    @JvmStatic
    fun rotateClockwise90(bitmap: Bitmap): Bitmap {
        require(!bitmap.isRecycled) { "Bitmap is recycled" }
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    }

}
