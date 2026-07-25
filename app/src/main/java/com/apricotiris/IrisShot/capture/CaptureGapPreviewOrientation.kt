package com.apricotiris.IrisShot.capture

import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.core.model.ScreenOrientation

/**
 * Describes how a capture-gap preview must be presented when the template's logical display
 * orientation differs from the physical screenshot orientation.
 *
 * Template regions remain stored in physical screenshot coordinates. The preview bitmap is the
 * only thing rotated for display, so its thumbnail constraints are exchanged before scaling while
 * the saved preview frame dimensions stay in physical coordinates.
 */
internal data class CaptureGapPreviewOrientation(
    val rotateQuarterTurn: Boolean,
    val scrollDirection: ScrollDirection,
) {
    fun thumbnailMaxSize(maxWidth: Int, maxHeight: Int): CaptureGapPreviewSize {
        val safeWidth = maxWidth.coerceAtLeast(1)
        val safeHeight = maxHeight.coerceAtLeast(1)
        return if (rotateQuarterTurn) {
            CaptureGapPreviewSize(width = safeHeight, height = safeWidth)
        } else {
            CaptureGapPreviewSize(width = safeWidth, height = safeHeight)
        }
    }

    fun contentSize(width: Int, height: Int): CaptureGapPreviewSize {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        return if (rotateQuarterTurn) {
            CaptureGapPreviewSize(width = safeHeight, height = safeWidth)
        } else {
            CaptureGapPreviewSize(width = safeWidth, height = safeHeight)
        }
    }
}

internal data class CaptureGapPreviewSize(
    val width: Int,
    val height: Int,
)

internal fun captureGapDisplayedTargetSize(
    screenWidth: Int,
    screenHeight: Int,
    referenceThickness: Int,
    scrollDirection: ScrollDirection,
): CaptureGapPreviewSize {
    val safeWidth = screenWidth.coerceAtLeast(1)
    val safeHeight = screenHeight.coerceAtLeast(1)
    val thickness = referenceThickness.coerceAtLeast(1)
    return if (scrollDirection.isVertical) {
        CaptureGapPreviewSize(
            width = minOf(thickness, safeWidth),
            height = safeHeight,
        )
    } else {
        CaptureGapPreviewSize(
            width = safeWidth,
            height = minOf(thickness, safeHeight),
        )
    }
}

/**
 * Resolves the same quarter-turn used by [TemplateViewportTransform] without making capture code
 * depend on template-editor UI geometry.
 */
internal fun captureGapPreviewOrientation(
    templateOrientation: String?,
    physicalWidth: Int,
    physicalHeight: Int,
    scrollDirection: ScrollDirection,
): CaptureGapPreviewOrientation {
    val safeWidth = physicalWidth.coerceAtLeast(1)
    val safeHeight = physicalHeight.coerceAtLeast(1)
    val selectedOrientation = ScreenOrientation.fromSerialized(
        templateOrientation,
        safeWidth,
        safeHeight,
    )
    val physicalIsLandscape = safeWidth >= safeHeight
    val rotateQuarterTurn = selectedOrientation.isLandscape != physicalIsLandscape
    return CaptureGapPreviewOrientation(
        rotateQuarterTurn = rotateQuarterTurn,
        scrollDirection = if (rotateQuarterTurn) {
            scrollDirection.rotatedClockwise()
        } else {
            scrollDirection
        },
    )
}

private fun ScrollDirection.rotatedClockwise(): ScrollDirection = when (this) {
    ScrollDirection.UP -> ScrollDirection.RIGHT
    ScrollDirection.DOWN -> ScrollDirection.LEFT
    ScrollDirection.LEFT -> ScrollDirection.UP
    ScrollDirection.RIGHT -> ScrollDirection.DOWN
}
