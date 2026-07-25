package com.apricotiris.IrisShot.capture

import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.core.model.StitchMatchResult
import com.apricotiris.IrisShot.core.model.StitchRejectionReason
import kotlin.math.abs

/**
 * Reconciles the motion prior after a frame was not accepted.
 *
 * [expectedShiftIncludingGesturePx] already includes [dispatchedGestureDistancePx]. Injection only
 * proves that Android accepted the pointer events, not that the target consumed them as a scroll.
 * A reliable match is measured from the last accepted keyframe, so it replaces the requested
 * cumulative distance. An unreliable match drops only the latest unconfirmed request. A duplicate
 * against the accepted keyframe proves zero cumulative movement; a terminal probe compares against
 * its pending frame instead and therefore only rolls back the latest request.
 */
internal fun reconcileRejectedFrameExpectedShift(
    expectedShiftIncludingGesturePx: Int,
    dispatchedGestureDistancePx: Int,
    match: StitchMatchResult,
    direction: ScrollDirection,
    duplicateComparedToPendingFrame: Boolean = false,
): Int {
    val shiftBeforeGesture = (
        expectedShiftIncludingGesturePx.toLong().coerceAtLeast(0L) -
            dispatchedGestureDistancePx.toLong().coerceAtLeast(0L)
        ).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    if (match.rejectionReason == StitchRejectionReason.DUPLICATE) {
        return if (duplicateComparedToPendingFrame) shiftBeforeGesture else 0
    }
    return observedRejectedFrameShift(match, direction) ?: shiftBeforeGesture
}

/**
 * Re-runs a visually reliable out-of-prior match against the same frame using its observed
 * displacement. This removes a stale gesture prior without injecting another swipe.
 */
internal inline fun recoverReliableUnexpectedShiftMatch(
    match: StitchMatchResult,
    direction: ScrollDirection,
    rematch: (observedShiftPx: Int) -> StitchMatchResult,
): StitchMatchResult {
    val observedShift = observedRejectedFrameShift(match, direction) ?: return match
    val recovered = rematch(observedShift)
    return recovered.takeIf(StitchMatchResult::accepted) ?: match
}

private fun observedRejectedFrameShift(
    match: StitchMatchResult,
    direction: ScrollDirection,
): Int? {
    if (match.accepted ||
        match.rejectionReason != StitchRejectionReason.UNEXPECTED_SHIFT ||
        !match.confidence.isFinite() ||
        match.confidence < DefaultCaptureConfig.MIN_MATCH_CONFIDENCE ||
        !match.overlapRatio.isFinite() ||
        match.overlapRatio < DefaultCaptureConfig.MIN_OVERLAP_RATIO ||
        match.overlapRatio >= 1f
    ) return null
    val signedShift = if (direction.isVertical) match.dy else match.dx
    val followsDirection = when (direction) {
        ScrollDirection.UP, ScrollDirection.LEFT -> signedShift < 0
        ScrollDirection.DOWN, ScrollDirection.RIGHT -> signedShift > 0
    }
    if (!followsDirection) return null
    val magnitude = abs(signedShift.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return magnitude.takeIf { it > MIN_TRACKABLE_REJECTED_SHIFT_PX }
}
private const val MIN_TRACKABLE_REJECTED_SHIFT_PX = 4
