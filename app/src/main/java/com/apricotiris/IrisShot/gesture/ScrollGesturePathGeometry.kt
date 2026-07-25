package com.apricotiris.IrisShot.gesture

import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.ScrollDirection
import kotlin.math.roundToInt

internal data class ScrollGesturePoint(
    val x: Float,
    val y: Float,
)

internal data class ScrollGesturePath(
    val start: ScrollGesturePoint,
    val end: ScrollGesturePoint,
    val distancePx: Int,
) {
    val center: ScrollGesturePoint
        get() = ScrollGesturePoint(
            x = (start.x + end.x) / 2f,
            y = (start.y + end.y) / 2f,
        )
}

internal fun maximumScrollGestureDistance(
    region: RectConfig,
    direction: ScrollDirection,
): Int {
    val normalized = region.normalized()
    val width = normalized.width.coerceAtLeast(1)
    val height = normalized.height.coerceAtLeast(1)
    val axisLength = if (direction.isVertical) height else width
    val ratioLimited = (axisLength * MAX_GESTURE_DISTANCE_RATIO).toInt()
    // The configured path may use the full 90% of the scrolling axis. The safety inset is
    // still applied to the perpendicular axis below, where it keeps the touch away from edges.
    return ratioLimited.coerceAtLeast(1)
}

internal fun scrollGestureAxisInset(
    region: RectConfig,
    direction: ScrollDirection,
): Float {
    val normalized = region.normalized()
    val axisLength = if (direction.isVertical) {
        normalized.height
    } else {
        normalized.width
    }.coerceAtLeast(1)
    return (axisLength * GESTURE_AXIS_INSET_RATIO).coerceAtLeast(1f)
}

internal fun resolveScrollGesturePath(
    region: RectConfig,
    direction: ScrollDirection,
    requestedDistancePx: Int,
    distanceRegion: RectConfig = region,
    movementAxisRegion: RectConfig? = null,
    crossAxisOffsetFraction: Float = 0f,
    crossAxisRegion: RectConfig? = null,
): ScrollGesturePath {
    val normalized = region.normalized()
    val limit = distanceRegion.normalized()
    val movementBounds = (movementAxisRegion ?: distanceRegion).normalized()
    val crossAxisBounds = (crossAxisRegion ?: region).normalized()
    val maximumDistance = maximumScrollGestureDistance(limit, direction)
    val distance = requestedDistancePx
        .coerceIn(1, maximumDistance)
    val halfDistance = distance / 2f
    // A configured stitch region may end exactly at a display edge. Starting a synthetic touch
    // there lets the status/navigation gesture consumer win even though Android reports a
    // successfully injected gesture. Keep both movement endpoints inside the app surface.
    val movementAxisInset = scrollGestureAxisInset(movementBounds, direction)
    val retryLaneOffset = crossAxisOffsetFraction.coerceIn(
        -MAX_CROSS_AXIS_OFFSET_FRACTION,
        MAX_CROSS_AXIS_OFFSET_FRACTION,
    )
    // Keep the cross-axis touch location from the gesture region while using the scrolling
    // region for the movement axis. This lets the editable arrow reach 90% of the scroll area
    // even when the legacy gesture rectangle is narrower.
    val rawCenterX = (normalized.left + normalized.right) / 2f +
        if (direction.isVertical) crossAxisBounds.width * retryLaneOffset else 0f
    val rawCenterY = (normalized.top + normalized.bottom) / 2f +
        if (direction.isVertical) 0f else crossAxisBounds.height * retryLaneOffset
    val centerX = if (direction.isVertical) {
        rawCenterX
    } else {
        coerceBetween(
            rawCenterX,
            movementBounds.left.toFloat() + halfDistance + movementAxisInset,
            movementBounds.right.toFloat() - halfDistance - movementAxisInset,
        )
    }
    val centerY = if (direction.isVertical) {
        coerceBetween(
            rawCenterY,
            movementBounds.top.toFloat() + halfDistance + movementAxisInset,
            movementBounds.bottom.toFloat() - halfDistance - movementAxisInset,
        )
    } else {
        rawCenterY
    }

    val raw = when (direction) {
        ScrollDirection.UP -> ScrollGesturePath(
            start = ScrollGesturePoint(centerX, centerY + halfDistance),
            end = ScrollGesturePoint(centerX, centerY - halfDistance),
            distancePx = distance,
        )
        ScrollDirection.DOWN -> ScrollGesturePath(
            start = ScrollGesturePoint(centerX, centerY - halfDistance),
            end = ScrollGesturePoint(centerX, centerY + halfDistance),
            distancePx = distance,
        )
        ScrollDirection.LEFT -> ScrollGesturePath(
            start = ScrollGesturePoint(centerX + halfDistance, centerY),
            end = ScrollGesturePoint(centerX - halfDistance, centerY),
            distancePx = distance,
        )
        ScrollDirection.RIGHT -> ScrollGesturePath(
            start = ScrollGesturePoint(centerX - halfDistance, centerY),
            end = ScrollGesturePoint(centerX + halfDistance, centerY),
            distancePx = distance,
        )
    }
    // Only the movement axis needs a system-gesture safety inset. The configured cross-axis
    // position is user-authored and may sit directly beside either edge of the capture box.
    val minimumX = if (direction.isVertical) crossAxisBounds.left.toFloat() else movementBounds.left.toFloat()
    val maximumX = if (direction.isVertical) {
        roundSafeExclusiveUpperBound(crossAxisBounds.right)
    } else {
        roundSafeExclusiveUpperBound(movementBounds.right)
    }
    val minimumY = if (direction.isVertical) movementBounds.top.toFloat() else crossAxisBounds.top.toFloat()
    val maximumY = if (direction.isVertical) {
        roundSafeExclusiveUpperBound(movementBounds.bottom)
    } else {
        roundSafeExclusiveUpperBound(crossAxisBounds.bottom)
    }
    return raw.copy(
        start = ScrollGesturePoint(
            x = coerceBetween(raw.start.x, minimumX, maximumX),
            y = coerceBetween(raw.start.y, minimumY, maximumY),
        ),
        end = ScrollGesturePoint(
            x = coerceBetween(raw.end.x, minimumX, maximumX),
            y = coerceBetween(raw.end.y, minimumY, maximumY),
        ),
    )
}

/**
 * Returns the physical surface on which a learned gesture may safely move. The scrolling region
 * still controls the maximum distance, but fixed-band edits must not move an already validated
 * gesture's center or edge safety margins.
 */
internal fun scrollGestureMovementRegion(
    template: GameCaptureTemplate,
    liveMovementRegion: RectConfig? = null,
): RectConfig {
    val capture = template.captureRegion.normalized()
    val candidate = liveMovementRegion
        ?: template.navigationContentBoundsAtGeometry
        ?.intersect(capture)
        ?.takeUnless { it.isEmpty }
        ?: template.stitchRegion.normalized()
    return candidate.intersect(capture)?.takeUnless { it.isEmpty } ?: capture
}

/**
 * Keeps the perpendicular gesture lane independent from fixed-band width. A side fixed layer
 * may narrow the stitch rectangle, but it must not prevent the editable gesture arrow from using
 * the corresponding edge of the capture/content surface.
 */
internal fun scrollGestureCrossAxisRegion(template: GameCaptureTemplate): RectConfig {
    val capture = template.captureRegion.normalized()
    return template.navigationContentBoundsAtGeometry
        ?.intersect(capture)
        ?.takeUnless { it.isEmpty }
        ?: capture
}

internal fun resolveTemplateScrollGesturePath(
    template: GameCaptureTemplate,
    requestedDistancePx: Int,
    crossAxisOffsetFraction: Float = 0f,
    movementAxisRegion: RectConfig? = null,
    crossAxisRegion: RectConfig? = null,
): ScrollGesturePath {
    val movementBounds = scrollGestureMovementRegion(template, movementAxisRegion)
    val capture = template.captureRegion.normalized()
    val crossAxisBounds = crossAxisRegion
        ?.normalized()
        ?.intersect(capture)
        ?: scrollGestureCrossAxisRegion(template)
    return resolveScrollGesturePath(
        region = template.gestureRegion,
        direction = template.scrollDirection,
        requestedDistancePx = requestedDistancePx,
        distanceRegion = template.stitchRegion,
        movementAxisRegion = movementBounds,
        crossAxisOffsetFraction = crossAxisOffsetFraction,
        crossAxisRegion = crossAxisBounds,
    )
}

/**
 * Moves the gesture path by its visible center. On the cross axis the backing rectangle shrinks
 * near an edge instead of stopping half its old width away from that edge.
 */
internal fun moveScrollGestureRegionByPathCenter(
    region: RectConfig,
    bounds: RectConfig,
    direction: ScrollDirection,
    deltaX: Float,
    deltaY: Float,
): RectConfig {
    val source = region.normalized()
    val limit = bounds.normalized()
    if (limit.isEmpty) return source

    fun movedAxis(start: Int, end: Int, lower: Int, upper: Int, delta: Float): Pair<Int, Int> {
        val length = (end - start).coerceIn(1, (upper - lower).coerceAtLeast(1))
        val requestedStart = start + delta
        val clampedStart = requestedStart.coerceIn(lower.toFloat(), (upper - length).toFloat())
        val resolvedStart = clampedStart.roundToInt().coerceIn(lower, upper - length)
        return resolvedStart to resolvedStart + length
    }

    fun movedCrossAxis(start: Int, end: Int, lower: Int, upper: Int, delta: Float): Pair<Int, Int> {
        val requestedCenter = ((start + end) / 2f + delta)
            .coerceIn(lower + 0.5f, upper - 0.5f)
        val originalHalf = (end - start).coerceAtLeast(1) / 2f
        val availableHalf = minOf(requestedCenter - lower, upper - requestedCenter)
        val half = minOf(originalHalf, availableHalf).coerceAtLeast(0.5f)
        var resolvedStart = (requestedCenter - half).roundToInt().coerceIn(lower, upper - 1)
        var resolvedEnd = (requestedCenter + half).roundToInt().coerceIn(resolvedStart + 1, upper)
        val centerError = requestedCenter - (resolvedStart + resolvedEnd) / 2f
        if (centerError > 0.25f && resolvedEnd < upper) {
            resolvedStart++
            resolvedEnd++
        } else if (centerError < -0.25f && resolvedStart > lower) {
            resolvedStart--
            resolvedEnd--
        }
        return resolvedStart to resolvedEnd
    }

    val horizontal = if (direction.isVertical) {
        movedCrossAxis(source.left, source.right, limit.left, limit.right, deltaX)
    } else {
        movedAxis(source.left, source.right, limit.left, limit.right, deltaX)
    }
    val vertical = if (direction.isVertical) {
        movedAxis(source.top, source.bottom, limit.top, limit.bottom, deltaY)
    } else {
        movedCrossAxis(source.top, source.bottom, limit.top, limit.bottom, deltaY)
    }
    return RectConfig(horizontal.first, vertical.first, horizontal.second, vertical.second)
}

private fun coerceBetween(value: Float, first: Float, second: Float): Float {
    val lower = minOf(first, second)
    val upper = maxOf(first, second)
    return value.coerceIn(lower, upper)
}

/** Largest practical touch coordinate that still rounds below an exclusive pixel boundary. */
private fun roundSafeExclusiveUpperBound(exclusiveUpper: Int): Float =
    Math.nextDown(exclusiveUpper.toFloat() - 0.5f)

private const val GESTURE_AXIS_INSET_RATIO = 0.05f
private const val MAX_CROSS_AXIS_OFFSET_FRACTION = 0.35f
private const val MAX_GESTURE_DISTANCE_RATIO = 0.90f
internal const val MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX = 48
