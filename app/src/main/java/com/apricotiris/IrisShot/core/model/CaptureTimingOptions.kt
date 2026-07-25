package com.apricotiris.IrisShot.core.model

import kotlin.math.abs

/**
 * Values shared by every editor that exposes the capture gesture timing parameters.
 *
 * Keeping the options here prevents the full-screen template editor and the floating editor from
 * accepting subtly different values. The minimum banner interval intentionally has a single
 * 50 ms value before continuing in 100 ms increments.
 */
object CaptureTimingOptions {
    const val MIN_SWIPE_DURATION_MS = 200L
    const val MAX_SWIPE_DURATION_MS = 2_000L

    val swipeDurationMs: List<Long> =
        (MIN_SWIPE_DURATION_MS..MAX_SWIPE_DURATION_MS step 100L).toList()
    val stableTimeMs: List<Long> = (100L..1_500L step 100L).toList()
    val postGestureWaitMs: List<Long> = (100L..1_500L step 100L).toList()
    val minimumIntervalMs: List<Long> = listOf(50L) + (100L..1_500L step 100L)

    fun normalizeSwipeDurationMs(value: Long): Long = nearest(value, swipeDurationMs)

    fun normalizeStableTimeMs(value: Long): Long = nearest(value, stableTimeMs)

    fun normalizePostGestureWaitMs(value: Long): Long = nearest(value, postGestureWaitMs)

    fun normalizeMinimumIntervalMs(value: Long): Long = nearest(value, minimumIntervalMs)

    fun nearestIndex(value: Long, options: List<Long>): Int {
        require(options.isNotEmpty()) { "Timing options must not be empty" }
        val clamped = value.coerceIn(options.first(), options.last())
        return options.indices.minBy { index -> abs(options[index] - clamped) }
    }

    private fun nearest(value: Long, options: List<Long>): Long =
        options[nearestIndex(value, options)]
}
