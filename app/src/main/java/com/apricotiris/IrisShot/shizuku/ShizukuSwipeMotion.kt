package com.apricotiris.IrisShot.shizuku

import com.apricotiris.IrisShot.core.model.CaptureTimingOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal const val MIN_SHIZUKU_SWIPE_DURATION_MS =
    CaptureTimingOptions.MIN_SWIPE_DURATION_MS
internal const val MAX_SHIZUKU_SWIPE_DURATION_MS =
    CaptureTimingOptions.MAX_SWIPE_DURATION_MS
internal const val MAX_SHIZUKU_ENDPOINT_HOLD_MS = 5_000L
internal const val REMOTE_SWIPE_NOT_STARTED = 0
internal const val REMOTE_SWIPE_COMPLETED = 1
internal const val REMOTE_SWIPE_OUTCOME_UNKNOWN = 2

internal fun remoteSwipeResult(started: Boolean, completed: Boolean): Int = when {
    completed -> REMOTE_SWIPE_COMPLETED
    started -> REMOTE_SWIPE_OUTCOME_UNKNOWN
    else -> REMOTE_SWIPE_NOT_STARTED
}

internal fun decodeRemoteSwipeResult(result: Int): ShizukuSwipeResult = when (result) {
    REMOTE_SWIPE_COMPLETED -> ShizukuSwipeResult.COMPLETED
    REMOTE_SWIPE_OUTCOME_UNKNOWN -> ShizukuSwipeResult.OUTCOME_UNKNOWN
    else -> ShizukuSwipeResult.UNAVAILABLE
}

internal data class ShizukuSwipeSample(
    val elapsedMs: Long,
    val distanceFraction: Float,
)

internal data class ShizukuSwipePlan(
    val movementSamples: List<ShizukuSwipeSample>,
    val endpointHoldDurationMs: Long,
) {
    val releaseElapsedMs: Long
        get() = movementSamples.last().elapsedMs + endpointHoldDurationMs
}

/**
 * Coordinates one remotely injected swipe without treating [Future.cancel] as proof that pointer
 * cleanup has finished. The start signal is resolved only after ACTION_DOWN succeeds; the finish
 * signal is delivered by the injection worker after its ACTION_CANCEL/ACTION_UP cleanup.
 */
internal class ShizukuSwipeSession(
    val sessionId: Long = 0L,
) {
    private val startResolved = CountDownLatch(1)
    private val started = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val cancellationRequested = AtomicBoolean(false)
    private val workerThread = AtomicReference<Thread?>(null)
    private val executionState = AtomicInteger(STATE_RESERVED)

    fun claimExecution(): Boolean =
        executionState.compareAndSet(STATE_RESERVED, STATE_RUNNING)

    fun attachWorker(thread: Thread = Thread.currentThread()) {
        check(workerThread.compareAndSet(null, thread)) { "Swipe worker already attached" }
        if (cancellationRequested.get()) thread.interrupt()
    }

    fun markStarted() {
        started.set(true)
        startResolved.countDown()
    }

    fun markStartFailed() {
        startResolved.countDown()
    }

    fun awaitStarted(timeoutMs: Long): Boolean {
        val resolved = startResolved.await(
            timeoutMs.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
        return resolved && started.get()
    }

    /** Returns true when a reservation was finished before its worker started. */
    fun requestCancellation(): Boolean {
        cancellationRequested.set(true)
        workerThread.get()?.interrupt()
        if (!executionState.compareAndSet(STATE_RESERVED, STATE_FINISHED)) return false
        markStartFailed()
        finished.countDown()
        return true
    }

    fun isCancellationRequested(): Boolean = cancellationRequested.get()

    fun isReserved(): Boolean = executionState.get() == STATE_RESERVED

    fun hasStarted(): Boolean = started.get()

    fun markFinished() {
        markStartFailed()
        executionState.set(STATE_FINISHED)
        workerThread.set(null)
        finished.countDown()
    }

    fun awaitFinished(timeoutMs: Long): Boolean =
        finished.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)

    private companion object {
        const val STATE_RESERVED = 0
        const val STATE_RUNNING = 1
        const val STATE_FINISHED = 2
    }
}

/** Mirrors the accessibility gesture's 65% / 25% / 10% progressive ease-out. */
internal fun buildShizukuSwipePlan(
    requestedDurationMs: Long,
    requestedHoldMs: Long,
    sampleIntervalMs: Long = SHIZUKU_SWIPE_SAMPLE_INTERVAL_MS,
): ShizukuSwipePlan {
    val duration = requestedDurationMs.coerceIn(
        MIN_SHIZUKU_SWIPE_DURATION_MS,
        MAX_SHIZUKU_SWIPE_DURATION_MS,
    )
    val hold = requestedHoldMs.coerceIn(0L, MAX_SHIZUKU_ENDPOINT_HOLD_MS)
    val interval = sampleIntervalMs.coerceAtLeast(1L)
    val firstDuration = (duration * 40L / 100L).coerceAtLeast(1L)
    val secondDuration = (duration * 30L / 100L).coerceAtLeast(1L)
    val thirdDuration = (duration - firstDuration - secondDuration).coerceAtLeast(1L)
    val phases = listOf(
        SwipePhase(0f, 0.65f, firstDuration),
        SwipePhase(0.65f, 0.90f, secondDuration),
        SwipePhase(0.90f, 1f, thirdDuration),
    )
    val samples = mutableListOf(ShizukuSwipeSample(elapsedMs = 0L, distanceFraction = 0f))
    var phaseStartTime = 0L
    phases.forEach { phase ->
        val stepCount = ((phase.durationMs + interval - 1L) / interval).coerceAtLeast(1L)
        for (step in 1L..stepCount) {
            val progress = step.toFloat() / stepCount.toFloat()
            samples += ShizukuSwipeSample(
                elapsedMs = phaseStartTime + phase.durationMs * step / stepCount,
                distanceFraction = phase.startFraction +
                    (phase.endFraction - phase.startFraction) * progress,
            )
        }
        phaseStartTime += phase.durationMs
    }
    return ShizukuSwipePlan(
        movementSamples = samples,
        endpointHoldDurationMs = hold,
    )
}

/** Constant-speed path used only by continuous region recognition. */
internal fun buildLinearShizukuSwipePlan(
    requestedDurationMs: Long,
    sampleIntervalMs: Long = SHIZUKU_SWIPE_SAMPLE_INTERVAL_MS,
): ShizukuSwipePlan {
    val duration = requestedDurationMs.coerceIn(
        MIN_LINEAR_RECOGNITION_SWIPE_DURATION_MS,
        MAX_LINEAR_RECOGNITION_SWIPE_DURATION_MS,
    )
    val interval = sampleIntervalMs.coerceAtLeast(1L)
    val stepCount = ((duration + interval - 1L) / interval).coerceAtLeast(1L)
    return ShizukuSwipePlan(
        movementSamples = (0L..stepCount).map { step ->
            ShizukuSwipeSample(
                elapsedMs = duration * step / stepCount,
                distanceFraction = step.toFloat() / stepCount.toFloat(),
            )
        },
        endpointHoldDurationMs = 0L,
    )
}

private data class SwipePhase(
    val startFraction: Float,
    val endFraction: Float,
    val durationMs: Long,
)

// One injected event per display frame is sufficient for a smooth path and avoids paying a
// Binder round-trip for redundant sub-frame samples on devices where injection is expensive.
private const val SHIZUKU_SWIPE_SAMPLE_INTERVAL_MS = 16L
private const val MIN_LINEAR_RECOGNITION_SWIPE_DURATION_MS = 1_000L
private const val MAX_LINEAR_RECOGNITION_SWIPE_DURATION_MS = 10_000L
