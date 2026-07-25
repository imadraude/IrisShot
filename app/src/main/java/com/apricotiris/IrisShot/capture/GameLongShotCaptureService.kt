package com.apricotiris.IrisShot.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.inputmethod.InputMethodManager
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.apricotiris.IrisShot.MainActivity
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.app.ApplicationLabelResolver
import com.apricotiris.IrisShot.core.app.ForegroundApplicationUpdate
import com.apricotiris.IrisShot.core.app.ForegroundApplicationTracker
import com.apricotiris.IrisShot.core.app.ForegroundObservationKind
import com.apricotiris.IrisShot.core.app.ForegroundWindowObservation
import com.apricotiris.IrisShot.core.image.BitmapFrameUtils
import com.apricotiris.IrisShot.core.model.CaptureSessionConfig
import com.apricotiris.IrisShot.core.model.CaptureState
import com.apricotiris.IrisShot.core.model.CaptureTimingOptions
import com.apricotiris.IrisShot.core.model.CapturedFixedLayer
import com.apricotiris.IrisShot.core.model.CapturedFrame
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.FixedLayerConfig
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.model.ModelJsonCodec
import com.apricotiris.IrisShot.core.model.MotionEstimate
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.RegionLearningResult
import com.apricotiris.IrisShot.core.model.ScreenOrientation
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.core.model.StitchMatchResult
import com.apricotiris.IrisShot.core.model.StitchRejectionReason
import com.apricotiris.IrisShot.core.model.withDefaultPreviewGeometry
import com.apricotiris.IrisShot.core.model.withNewTemplateCaptureDefaults
import com.apricotiris.IrisShot.core.region.RegionLearningEngine
import com.apricotiris.IrisShot.core.storage.CaptureResultRepository
import com.apricotiris.IrisShot.core.storage.SessionFileStore
import com.apricotiris.IrisShot.core.stitch.DiskBackedStitchAccumulator
import com.apricotiris.IrisShot.core.stitch.FinalizedScroll
import com.apricotiris.IrisShot.core.stitch.StitchEngine
import com.apricotiris.IrisShot.core.template.TemplateRepository
import com.apricotiris.IrisShot.core.template.TemplateReferenceStore
import com.apricotiris.IrisShot.core.template.OverlayDraftRepository
import com.apricotiris.IrisShot.core.template.CrossAppOverwriteConfirmation
import com.apricotiris.IrisShot.core.template.TargetApplicationInfo
import com.apricotiris.IrisShot.core.template.TemplateSaveCommitResult
import com.apricotiris.IrisShot.gesture.GameScrollAccessibilityService
import com.apricotiris.IrisShot.gesture.MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX
import com.apricotiris.IrisShot.gesture.maximumScrollGestureDistance
import com.apricotiris.IrisShot.gesture.resolveTemplateScrollGesturePath
import com.apricotiris.IrisShot.gesture.scrollGestureCrossAxisRegion
import com.apricotiris.IrisShot.gesture.scrollGestureMovementRegion
import com.apricotiris.IrisShot.overlay.OverlayController
import com.apricotiris.IrisShot.shizuku.CompactApplicationWindowBounds
import com.apricotiris.IrisShot.shizuku.ShizukuBridge
import com.apricotiris.IrisShot.shizuku.ShizukuForegroundSnapshot
import com.apricotiris.IrisShot.shizuku.ShizukuInputNavigationSnapshotRequest
import com.apricotiris.IrisShot.shizuku.ShizukuSwipeResult
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.AppLanguage
import com.apricotiris.IrisShot.ui.AppLocaleManager
import com.apricotiris.IrisShot.ui.CaptureSettingsUiState
import com.apricotiris.IrisShot.ui.localizedMessageOrFallback
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val REVIEWABLE_MATCH_MIN_CONFIDENCE = 0.30f
private const val TERMINAL_REMAINDER_MIN_CONFIDENCE = 0.12f
private const val REQUIRED_TERMINAL_DUPLICATE_CONFIRMATIONS = 2
private const val TERMINAL_SHIFT_AGREEMENT_RATIO = 0.12f
private const val TERMINAL_TINY_PROBE_MAX_FRAME_DIFFERENCE = 24f
private const val TERMINAL_TINY_PROBE_MAX_CHANGED_RATIO = 0.26f
private const val ACCEPTED_TINY_PROBE_MIN_CONFIDENCE = 0.60f
private const val ACCEPTED_TINY_PROBE_CONFIDENCE_ADVANTAGE = 0.12f
private const val TERMINAL_PROBE_MAX_SHIFT_RATIO = 0.35f
private const val ACCEPTED_MATCH_STABILITY_CONFIDENCE = 0.70f
private const val MAX_TERMINAL_REMAINDER_REPLACEMENTS = 1
internal const val CAPTURE_FOREGROUND_SERVICE_TYPE_NONE = 0

internal data class CaptureInteractionOwner(
    val serviceInstanceId: Long,
    val jobToken: Long,
)

/** Serializes the process-wide accessibility capture flag across service and job generations. */
internal class CaptureInteractionOwnerGate(
    private val publishActive: (Boolean) -> Unit,
) {
    private val lock = Any()
    private var owner: CaptureInteractionOwner? = null
    private var newestAcceptedOwner: CaptureInteractionOwner? = null

    fun acquire(candidate: CaptureInteractionOwner): Boolean = synchronized(lock) {
        val newest = newestAcceptedOwner
        if (newest != null) {
            val generationOrder = compareValuesBy(
                candidate,
                newest,
                CaptureInteractionOwner::serviceInstanceId,
                CaptureInteractionOwner::jobToken,
            )
            if (generationOrder < 0 || (generationOrder == 0 && owner != candidate)) {
                return@synchronized false
            }
        }
        if (owner != candidate) {
            if (owner == null) publishActive(true)
            owner = candidate
            newestAcceptedOwner = candidate
        }
        true
    }

    fun release(expectedOwner: CaptureInteractionOwner): Boolean = synchronized(lock) {
        if (owner != expectedOwner) return@synchronized false
        publishActive(false)
        owner = null
        true
    }

    fun releaseService(serviceInstanceId: Long): Boolean = synchronized(lock) {
        if (owner?.serviceInstanceId != serviceInstanceId) return@synchronized false
        publishActive(false)
        owner = null
        true
    }
}

private class CaptureFrameHardTimeoutException :
    IllegalStateException("Capture frame exceeded its hard timeout")
/**
 * Returns the time still required to keep a progress banner visible.
 *
 * Work performed while the banner is actually visible counts toward the configured minimum, while
 * gesture settling and screen capture before the banner is drawn intentionally do not.
 */
internal fun remainingMinimumBannerVisibleMs(
    configuredMinimumVisibleMs: Long,
    elapsedSinceBannerDrawnMs: Long,
): Long = (
    CaptureTimingOptions.normalizeMinimumIntervalMs(configuredMinimumVisibleMs) -
        elapsedSinceBannerDrawnMs.coerceAtLeast(0L)
    ).coerceAtLeast(0L)

/** A requested capture-gap overlay may retain its preview until removal is confirmed. */
internal fun captureGapPreviewMayStillBeAttached(
    processingGapRequested: Boolean,
    processingGapVisible: Boolean,
): Boolean = processingGapRequested || processingGapVisible

internal fun CaptureSessionConfig.withTemplateRuntimeConfig(
    template: GameCaptureTemplate,
    previewMaxWidthPx: Int,
    previewMaxHeightPx: Int,
): CaptureSessionConfig = copy(
    templateId = template.id,
    outputFormat = template.outputFormat,
    jpegQuality = template.jpegQuality,
    maxOutputHeight = template.maxOutputHeight,
    previewMaxWidth = previewMaxWidthPx,
    previewMaxHeight = previewMaxHeightPx,
    enablePreviewBetweenScroll = template.previewEnabled,
    previewGapDurationMs = template.previewGapDurationMs,
)

internal fun CaptureSessionConfig.forNewCaptureJob(
    globalAutoOpenResult: Boolean,
): CaptureSessionConfig = copy(autoOpenResult = globalAutoOpenResult)

private val GESTURE_RETRY_CROSS_AXIS_LANES = listOf(0f, -0.22f, 0.22f)

internal data class CaptureGestureAttempt(
    val distancePx: Int,
    val crossAxisOffsetFraction: Float,
)

private data class CaptureGestureDispatch(
    val distancePx: Int,
    val completionConfirmed: Boolean,
)

private sealed interface CaptureGestureDispatchAttempt {
    data class Dispatched(val value: CaptureGestureDispatch) : CaptureGestureDispatchAttempt
    data object NotStarted : CaptureGestureDispatchAttempt
    data object FailedAfterStart : CaptureGestureDispatchAttempt
}

internal fun learningGestureDistance(
    configuredDistancePx: Int,
    physicalAxisLength: Int,
    maximumExecutableDistancePx: Int = configuredDistancePx,
): Int {
    val configured = configuredDistancePx.coerceAtLeast(1)
    val executableMaximum = maximumExecutableDistancePx.coerceAtLeast(1)
    val learningTarget = maxOf(
        MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX,
        physicalAxisLength.coerceAtLeast(1) / 5,
    )
    return minOf(configured, executableMaximum, learningTarget)
}

internal fun recognitionGestureDistance(
    configuredDistancePx: Int,
    maximumExecutableDistancePx: Int,
): Int {
    val executableMaximum = maximumExecutableDistancePx.coerceAtLeast(1)
    val minimumRecognitionDistance = (executableMaximum * 0.90f).roundToInt()
        .coerceIn(1, executableMaximum)
    return maxOf(configuredDistancePx.coerceAtLeast(1), minimumRecognitionDistance)
        .coerceAtMost(executableMaximum)
}

internal fun captureGestureAttempts(
    configuredDistancePx: Int,
    physicalAxisLength: Int,
    maximumExecutableDistancePx: Int = configuredDistancePx,
): List<CaptureGestureAttempt> {
    val configured = configuredDistancePx.coerceIn(
        1,
        maximumExecutableDistancePx.coerceAtLeast(1),
    )
    val shortDistance = learningGestureDistance(
        configuredDistancePx = configured,
        physicalAxisLength = physicalAxisLength,
        maximumExecutableDistancePx = maximumExecutableDistancePx,
    )
    // Normal capture must honor the configured path. Region learning deliberately uses the short
    // centre path below; keep it and the alternate lanes as fallbacks that are reached only after
    // a clean frame confirms that the preceding swipe had no effect.
    return buildList {
        // A transiently ineffective swipe is most often consumed by a child under the current
        // lane. Try the configured distance on the other safe lanes before shortening it, so one
        // missed gesture does not immediately slow every following frame.
        GESTURE_RETRY_CROSS_AXIS_LANES.forEach { lane ->
            add(CaptureGestureAttempt(configured, lane))
        }
        GESTURE_RETRY_CROSS_AXIS_LANES.forEach { lane ->
            add(CaptureGestureAttempt(shortDistance, lane))
        }
    }.distinct()
}

internal fun shouldContinueRecognitionSampling(
    sampleCount: Int,
    targetSampleCount: Int,
    stopRequested: Boolean,
    finishRequested: Boolean,
    gestureComplete: Boolean,
    nowMs: Long,
    deadlineMs: Long,
): Boolean = sampleCount < targetSampleCount &&
    !stopRequested &&
    !finishRequested &&
    !gestureComplete &&
    nowMs < deadlineMs

internal fun recognitionMotionSampleTarget(totalSampleLimit: Int): Int =
    (totalSampleLimit.coerceAtLeast(1) - 1).coerceAtLeast(1)

internal fun remainingRecognitionOperationTimeoutMs(
    nowMs: Long,
    hardDeadlineMs: Long,
    configuredTimeoutMs: Long,
): Long = if (nowMs >= hardDeadlineMs) {
    0L
} else {
    minOf(configuredTimeoutMs.coerceAtLeast(0L), hardDeadlineMs - nowMs)
}

internal fun hasSufficientRecognitionMotionSamples(
    motionSampleCount: Int,
    minimumRequired: Int = 2,
): Boolean = motionSampleCount >= minimumRequired.coerceAtLeast(1)

internal fun shouldCaptureFinalRecognitionFrame(
    gestureSettled: Boolean,
    stopRequested: Boolean,
    sampleCount: Int,
    targetSampleCount: Int,
    remainingBudgetMs: Long,
): Boolean = gestureSettled &&
    !stopRequested &&
    sampleCount < targetSampleCount.coerceAtLeast(1) &&
    remainingBudgetMs > 0L

internal fun recognitionDeadlineAfter(
    startMs: Long,
    durationMs: Long,
): Long {
    val duration = durationMs.coerceAtLeast(0L)
    return if (startMs > Long.MAX_VALUE - duration) Long.MAX_VALUE else startMs + duration
}

internal data class CaptureGestureRetryState(
    val attemptIndex: Int = 0,
    /** Cumulative history used only to back off retries of the configured gesture path. */
    val unconfirmedAttempts: Int = 0,
    /** Quiet gesture paths tried since the current accepted keyframe was established. */
    val currentKeyframeAttempts: Int = 0,
    val acceptedFallbackFrames: Int = 0,
)

internal fun nextCaptureGestureRetryState(
    current: CaptureGestureRetryState,
    attemptCount: Int,
    frameAccepted: Boolean,
    confirmedNoMotion: Boolean,
): CaptureGestureRetryState {
    val safeAttemptCount = attemptCount.coerceAtLeast(1)
    val currentAttemptIndex = current.attemptIndex.coerceIn(0, safeAttemptCount - 1)
    if (frameAccepted) {
        if (currentAttemptIndex == 0 || safeAttemptCount == 1) {
            return CaptureGestureRetryState()
        }
        val acceptedFallbackFrames = if (current.acceptedFallbackFrames == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            current.acceptedFallbackFrames.coerceAtLeast(0) + 1
        }
        // Keep a proven fallback for several frames, then probe the configured path again. The
        // interval grows after repeated failures so a consistently incompatible path is retried
        // rarely, while one transient missed swipe cannot lock the whole capture to short steps.
        val configuredPathProbeInterval = when {
            current.unconfirmedAttempts <= 1 -> 2
            current.unconfirmedAttempts == 2 -> 4
            else -> 8
        }
        return if (acceptedFallbackFrames >= configuredPathProbeInterval) {
            CaptureGestureRetryState(
                attemptIndex = 0,
                unconfirmedAttempts = current.unconfirmedAttempts.coerceAtLeast(1),
                currentKeyframeAttempts = 0,
            )
        } else {
            CaptureGestureRetryState(
                attemptIndex = currentAttemptIndex,
                unconfirmedAttempts = current.unconfirmedAttempts.coerceAtLeast(1),
                currentKeyframeAttempts = 0,
                acceptedFallbackFrames = acceptedFallbackFrames,
            )
        }
    }
    if (!confirmedNoMotion) return current
    return CaptureGestureRetryState(
        attemptIndex = (currentAttemptIndex + 1) % safeAttemptCount,
        unconfirmedAttempts = if (current.unconfirmedAttempts == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            current.unconfirmedAttempts.coerceAtLeast(0) + 1
        },
        currentKeyframeAttempts = if (current.currentKeyframeAttempts == Int.MAX_VALUE) {
            Int.MAX_VALUE
        } else {
            current.currentKeyframeAttempts.coerceAtLeast(0) + 1
        },
        acceptedFallbackFrames = 0,
    )
}

internal fun shouldDispatchCaptureGesture(hasPendingTerminalRemainder: Boolean): Boolean =
    !hasPendingTerminalRemainder

internal fun shouldRetryCaptureGestureStart(
    failedStartCount: Int,
    retryCount: Int,
    elapsedSinceFirstAttemptMs: Long,
    retryWindowMs: Long,
    retryDelayMs: Long,
    stopRequested: Boolean,
): Boolean {
    val safeRetryWindowMs = retryWindowMs.coerceAtLeast(0L)
    val safeRetryDelayMs = retryDelayMs.coerceAtLeast(0L)
    return !stopRequested &&
        failedStartCount in 1..retryCount.coerceAtLeast(0) &&
        safeRetryDelayMs <= safeRetryWindowMs &&
        elapsedSinceFirstAttemptMs.coerceAtLeast(0L) <= safeRetryWindowMs - safeRetryDelayMs
}

internal fun dispatchedShizukuGestureDistance(
    result: ShizukuSwipeResult,
    distancePx: Int,
    allowUnknownOutcome: Boolean,
): Int? = when (result) {
    ShizukuSwipeResult.COMPLETED -> distancePx
    ShizukuSwipeResult.OUTCOME_UNKNOWN -> distancePx.takeIf { allowUnknownOutcome }
    ShizukuSwipeResult.UNAVAILABLE -> null
}

internal fun observableShizukuGestureDistanceAfterTimeout(
    usesShizukuBackend: Boolean,
    gestureStarted: Boolean,
    pointerReleased: Boolean,
    distancePx: Int,
): Int? = distancePx.takeIf {
    usesShizukuBackend && gestureStarted && pointerReleased && distancePx > 0
}

internal fun nextTerminalNoMotionEvidence(
    currentEvidence: Int,
    dispatchedDistancePx: Int,
    gestureCompletionConfirmed: Boolean,
    frameAccepted: Boolean,
    duplicateFrame: Boolean,
    pendingConfirmationObserved: Boolean,
): Int = when {
    pendingConfirmationObserved || frameAccepted -> 0
    duplicateFrame && gestureCompletionConfirmed && dispatchedDistancePx > 0 ->
        if (currentEvidence == Int.MAX_VALUE) Int.MAX_VALUE else currentEvidence + 1
    !duplicateFrame -> 0
    else -> currentEvidence.coerceAtLeast(0)
}

internal fun nextObservedDuplicateGestureEvidence(
    currentEvidence: Int,
    dispatchedDistancePx: Int,
    frameAccepted: Boolean,
    duplicateFrame: Boolean,
    pendingConfirmationObserved: Boolean,
): Int = when {
    pendingConfirmationObserved || frameAccepted -> 0
    dispatchedDistancePx <= 0 -> currentEvidence.coerceAtLeast(0)
    duplicateFrame ->
        if (currentEvidence == Int.MAX_VALUE) Int.MAX_VALUE else currentEvidence + 1
    else -> 0
}

internal fun hasObservedNoMotionAcrossAllGestureAttempts(
    evidence: Int,
    attemptCount: Int,
): Boolean = evidence >= attemptCount.coerceAtLeast(1)

internal fun shouldStageTerminalRemainder(
    hadPendingTerminalRemainder: Boolean,
    frameAccepted: Boolean,
    candidateCanBeStaged: Boolean,
): Boolean = !hadPendingTerminalRemainder && !frameAccepted && candidateCanBeStaged

internal fun shouldReplaceFailedTerminalRemainder(
    pendingInvalidatedThisCycle: Boolean,
    candidateMatch: StitchMatchResult,
    direction: ScrollDirection,
    dispatchedGestureDistancePx: Int,
    replacementAttempts: Int,
): Boolean = pendingInvalidatedThisCycle &&
    dispatchedGestureDistancePx == 0 &&
    replacementAttempts in 0 until MAX_TERMINAL_REMAINDER_REPLACEMENTS &&
    canStageTerminalRemainder(candidateMatch, direction)

internal fun templatePreservingLearnedGestureRegion(
    learnedTemplate: GameCaptureTemplate,
    suggestedTemplate: GameCaptureTemplate,
): GameCaptureTemplate = suggestedTemplate.copy(
    gestureRegion = learnedTemplate.gestureRegion,
).withNormalizedRegions()

internal fun physicalTemplateForLearnedRegionEditor(
    preparedTemplate: GameCaptureTemplate,
    navigationSafeSuggestion: GameCaptureTemplate,
    learnedTemplate: GameCaptureTemplate,
    visibleNavigationContentBounds: RectConfig,
): GameCaptureTemplate = templatePreservingLearnedGestureRegion(
    learnedTemplate = learnedTemplate,
    suggestedTemplate = preparedTemplate.copy(
        id = 0L,
        templateName = navigationSafeSuggestion.templateName,
        stitchRegion = navigationSafeSuggestion.stitchRegion,
        gestureRegion = navigationSafeSuggestion.gestureRegion,
        fixedLayers = preparedTemplate.fixedLayers,
        navigationContentBoundsAtGeometry = visibleNavigationContentBounds,
    ).withNormalizedRegions(),
)

internal inline fun shouldAbortAfterBlockingSafetyCheck(
    stopRequested: () -> Boolean,
    blockingSafetyCheck: () -> Boolean,
): Boolean {
    if (stopRequested()) return true
    if (blockingSafetyCheck()) return true
    return stopRequested()
}

/**
 * A no-motion frame proves that the just-finished gesture added no displacement. The following
 * attempt uses another safe cross-axis lane so the same clickable child does not keep consuming
 * the synthetic touch; it is never dispatched until the clean frame has confirmed no motion.
 */
internal fun nextGestureRetryCrossAxisOffset(currentOffsetFraction: Float): Float {
    val currentIndex = GESTURE_RETRY_CROSS_AXIS_LANES.indices.minBy { index ->
        abs(GESTURE_RETRY_CROSS_AXIS_LANES[index] - currentOffsetFraction)
    }
    return GESTURE_RETRY_CROSS_AXIS_LANES[(currentIndex + 1) % GESTURE_RETRY_CROSS_AXIS_LANES.size]
}

internal fun removeUnobservedGestureDistance(
    expectedShiftSinceKeyframe: Int,
    dispatchedDistance: Int,
): Int = (expectedShiftSinceKeyframe - dispatchedDistance.coerceAtLeast(0)).coerceAtLeast(0)

internal fun remainingOverlayStableDelayMs(
    configuredWaitAfterOverlayHiddenMs: Long,
    elapsedSinceOverlayHiddenMs: Long,
): Long = (
    configuredWaitAfterOverlayHiddenMs.coerceIn(100L, 1_500L) -
        elapsedSinceOverlayHiddenMs.coerceAtLeast(0L)
    ).coerceAtLeast(0L)

internal fun postGestureWaitDurationMs(
    enabled: Boolean,
    configuredWaitMs: Long,
): Long = if (enabled) {
    CaptureTimingOptions.normalizePostGestureWaitMs(configuredWaitMs)
} else {
    0L
}

internal fun remainingForegroundPollDelayMs(
    periodMs: Long,
    elapsedMs: Long,
): Long = (periodMs.coerceAtLeast(0L) - elapsedMs.coerceAtLeast(0L)).coerceAtLeast(0L)

private enum class CaptureBackendMode { MEDIA_PROJECTION, SHIZUKU }

internal fun captureForegroundServiceType(
    usesShizukuBackend: Boolean,
    sdkInt: Int,
): Int = when {
    usesShizukuBackend && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    usesShizukuBackend -> CAPTURE_FOREGROUND_SERVICE_TYPE_NONE
    else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
}

internal enum class TerminalFixedSide {
    LEADING,
    TRAILING,
}

internal fun terminalFixedSideFor(direction: ScrollDirection): TerminalFixedSide =
    if (direction.isForward) TerminalFixedSide.TRAILING else TerminalFixedSide.LEADING

internal fun overlayTemplatesForDisplay(
    templates: List<GameCaptureTemplate>,
    targetWidth: Int,
    targetHeight: Int,
    scaleToResolution: (GameCaptureTemplate, Int, Int) -> GameCaptureTemplate,
): List<GameCaptureTemplate> {
    val targetOrientation = ScreenOrientation.fromSerialized(
        value = null,
        screenWidth = targetWidth,
        screenHeight = targetHeight,
    ).serializedValue
    return templates.map { template ->
        when {
            template.screenWidth == targetWidth && template.screenHeight == targetHeight -> {
                if (template.orientation.equals(targetOrientation, ignoreCase = true)) template
                else template.copy(orientation = targetOrientation)
            }
            template.screenWidth > 0 && template.screenHeight > 0 ->
                scaleToResolution(template, targetWidth, targetHeight).copy(
                    orientation = targetOrientation,
                )
            else -> template
        }
    }
}

internal fun provisionalOverlayTemplateForDisplay(
    source: GameCaptureTemplate?,
    targetWidth: Int,
    targetHeight: Int,
    createDefault: () -> GameCaptureTemplate,
    scaleToResolution: (GameCaptureTemplate, Int, Int) -> GameCaptureTemplate,
): GameCaptureTemplate {
    require(targetWidth > 0 && targetHeight > 0)
    val candidate = source?.takeIf { it.screenWidth > 0 && it.screenHeight > 0 }
        ?: createDefault()
    val fitted = if (
        candidate.screenWidth == targetWidth && candidate.screenHeight == targetHeight
    ) {
        candidate
    } else {
        scaleToResolution(candidate, targetWidth, targetHeight)
    }
    val orientation = ScreenOrientation.fromSerialized(
        value = null,
        screenWidth = targetWidth,
        screenHeight = targetHeight,
    ).serializedValue
    return fitted.copy(orientation = orientation).withNormalizedRegions()
}

internal fun canAcceptReviewableLowConfidenceMatch(
    match: StitchMatchResult,
    direction: ScrollDirection,
): Boolean {
    val mainShift = abs(if (direction.isVertical) match.dy else match.dx)
    return match.rejectionReason == StitchRejectionReason.LOW_CONFIDENCE &&
        match.confidence.isFinite() &&
        match.confidence >= REVIEWABLE_MATCH_MIN_CONFIDENCE &&
        mainShift > 4 &&
        match.overlapRatio in DefaultCaptureConfig.MIN_OVERLAP_RATIO..0.96f
}

internal fun canStageTerminalRemainder(
    match: StitchMatchResult,
    direction: ScrollDirection,
): Boolean {
    val signedShift = if (direction.isVertical) match.dy else match.dx
    val followsDirection = when (direction) {
        ScrollDirection.UP, ScrollDirection.LEFT -> signedShift < 0
        ScrollDirection.DOWN, ScrollDirection.RIGHT -> signedShift > 0
    }
    return match.rejectionReason == StitchRejectionReason.LOW_CONFIDENCE &&
        match.confidence.isFinite() &&
        match.confidence >= TERMINAL_REMAINDER_MIN_CONFIDENCE &&
        followsDirection &&
        !isDuplicateMatch(match) &&
        match.overlapRatio >= DefaultCaptureConfig.MIN_OVERLAP_RATIO &&
        match.overlapRatio < 1f
}

internal fun isDuplicateMatch(match: StitchMatchResult): Boolean =
    match.rejectionReason == StitchRejectionReason.DUPLICATE

internal fun isLikelyUnconsumedGesture(match: StitchMatchResult): Boolean =
    isDuplicateMatch(match) || match.nearlyIdentical

internal fun shouldTryAnotherGesturePathForQuietFrame(
    match: StitchMatchResult,
    retryState: CaptureGestureRetryState,
    attemptCount: Int,
): Boolean = isLikelyUnconsumedGesture(match) &&
    retryState.currentKeyframeAttempts < attemptCount.coerceAtLeast(1)

internal fun hasConfirmedTerminalRemainder(duplicateConfirmations: Int): Boolean =
    duplicateConfirmations >= REQUIRED_TERMINAL_DUPLICATE_CONFIRMATIONS

internal fun shouldPreferTinyTerminalMatch(
    match: StitchMatchResult,
    direction: ScrollDirection,
    frameLength: Int,
    baseline: StitchMatchResult,
): Boolean {
    // A pixel-identical frame is decisive. Structural stationarity may also be produced by a
    // sparse or periodic page, so it cannot by itself override an otherwise accepted baseline.
    if (isDuplicateMatch(match)) return !baseline.accepted || match.nearlyIdentical
    val usableAcceptedTerminalShift = match.accepted &&
        isTerminalProbeShift(match, direction, frameLength)
    val usableTinyRemainder = isTinyTerminalShift(match, direction, frameLength) &&
        canStageTerminalRemainder(match, direction)
    if (!baseline.accepted) return usableAcceptedTerminalShift || usableTinyRemainder
    // Periodic text rows can produce a plausible tiny shift without a prior. Do not replace an
    // already accepted full-distance match unless the tiny hypothesis is decisively stronger.
    return usableAcceptedTerminalShift &&
        match.confidence.isFinite() &&
        baseline.confidence.isFinite() &&
        match.confidence >= ACCEPTED_TINY_PROBE_MIN_CONFIDENCE &&
        match.confidence >= baseline.confidence + ACCEPTED_TINY_PROBE_CONFIDENCE_ADVANTAGE
}

internal fun terminalProbeMaximumShift(frameLength: Int): Int =
    floor(frameLength.coerceAtLeast(1) * TERMINAL_PROBE_MAX_SHIFT_RATIO)
        .toInt()
        .coerceAtLeast(1)

internal fun isTerminalProbeShift(
    match: StitchMatchResult,
    direction: ScrollDirection,
    frameLength: Int,
): Boolean {
    val shift = abs(if (direction.isVertical) match.dy else match.dx)
    return shift in 1..terminalProbeMaximumShift(frameLength)
}

internal fun isTinyTerminalShift(
    match: StitchMatchResult,
    direction: ScrollDirection,
    frameLength: Int,
): Boolean {
    val shift = abs(if (direction.isVertical) match.dy else match.dx)
    val normalMinimumShift = ceil(
        frameLength.coerceAtLeast(1) * (1f - DefaultCaptureConfig.MAX_OVERLAP_RATIO),
    ).toInt().coerceAtLeast(1)
    return shift in 1 until normalMinimumShift
}

internal fun shouldProbeAcceptedMatchForTinyTerminalShift(
    match: StitchMatchResult,
): Boolean = match.accepted &&
    match.frameDifference.isFinite() &&
    match.changedPixelRatio.isFinite() &&
    match.frameDifference <= TERMINAL_TINY_PROBE_MAX_FRAME_DIFFERENCE &&
    match.changedPixelRatio <= TERMINAL_TINY_PROBE_MAX_CHANGED_RATIO

internal fun shouldConfirmAcceptedMatchStability(match: StitchMatchResult): Boolean =
    match.accepted &&
        match.confidence.isFinite() &&
        match.confidence < ACCEPTED_MATCH_STABILITY_CONFIDENCE &&
        match.frameDifference.isFinite() &&
        match.changedPixelRatio.isFinite() &&
        match.frameDifference <= TERMINAL_TINY_PROBE_MAX_FRAME_DIFFERENCE &&
        match.changedPixelRatio <= TERMINAL_TINY_PROBE_MAX_CHANGED_RATIO

internal fun terminalRemainderMatchesAgree(
    pending: StitchMatchResult,
    confirmation: StitchMatchResult,
    direction: ScrollDirection,
): Boolean {
    if (!pending.confidence.isFinite() || !confirmation.confidence.isFinite()) return false
    val confirmationIsUsable = confirmation.accepted ||
        canStageTerminalRemainder(confirmation, direction)
    if (!confirmationIsUsable) return false

    val pendingMain = if (direction.isVertical) pending.dy else pending.dx
    val confirmationMain = if (direction.isVertical) confirmation.dy else confirmation.dx
    val pendingCross = if (direction.isVertical) pending.dx else pending.dy
    val confirmationCross = if (direction.isVertical) confirmation.dx else confirmation.dy
    val followsDirection = when (direction) {
        ScrollDirection.UP, ScrollDirection.LEFT -> pendingMain < 0 && confirmationMain < 0
        ScrollDirection.DOWN, ScrollDirection.RIGHT -> pendingMain > 0 && confirmationMain > 0
    }
    if (!followsDirection) return false

    val mainTolerance = maxOf(
        2,
        ceil(abs(pendingMain) * TERMINAL_SHIFT_AGREEMENT_RATIO).toInt(),
    )
    val crossTolerance = maxOf(
        2,
        ceil(abs(pendingCross) * TERMINAL_SHIFT_AGREEMENT_RATIO).toInt(),
    )
    return abs(pendingMain - confirmationMain) <= mainTolerance &&
        abs(pendingCross - confirmationCross) <= crossTolerance
}

internal enum class ForegroundDepartureAction {
    NONE,
    COLLAPSE_AND_STOP,
}

internal enum class ShizukuForegroundStatus {
    MATCHED,
    DEPARTED,
    UNKNOWN,
}

internal fun shizukuForegroundStatus(
    expectedPackage: String,
    foregroundPackage: String?,
): ShizukuForegroundStatus = when {
    expectedPackage.isBlank() || foregroundPackage.isNullOrBlank() ->
        ShizukuForegroundStatus.UNKNOWN
    foregroundPackage == expectedPackage -> ShizukuForegroundStatus.MATCHED
    else -> ShizukuForegroundStatus.DEPARTED
}

internal fun shouldStopForShizukuForeground(
    status: ShizukuForegroundStatus,
    unknownDurationMs: Long,
    unknownGraceMs: Long,
): Boolean = status == ShizukuForegroundStatus.DEPARTED ||
    (status == ShizukuForegroundStatus.UNKNOWN &&
        unknownDurationMs.coerceAtLeast(0L) >= unknownGraceMs.coerceAtLeast(0L))

internal fun foregroundDepartureAction(
    departedLockedTarget: Boolean,
    switchedAwayFromCaptureTarget: Boolean,
    jobRunning: Boolean,
): ForegroundDepartureAction {
    if (!jobRunning || (!departedLockedTarget && !switchedAwayFromCaptureTarget)) {
        return ForegroundDepartureAction.NONE
    }
    return ForegroundDepartureAction.COLLAPSE_AND_STOP
}

internal fun shouldGuardExpandedOverlay(
    jobRunning: Boolean,
    hasGuardedRegionEdit: Boolean,
): Boolean = jobRunning || hasGuardedRegionEdit

internal fun shouldCollapseExpandedOverlayForNavigation(
    expandedOverlayVisible: Boolean,
    jobRunning: Boolean,
    overlayOpening: Boolean = false,
): Boolean = (expandedOverlayVisible || overlayOpening) && !jobRunning

internal fun isUnarmedNavigationSurfacePackage(
    focusedApplicationPackage: String?,
    homePackages: Set<String>,
    systemUiPackage: String = "com.android.systemui",
): Boolean {
    val normalized = focusedApplicationPackage?.trim().orEmpty()
    return normalized.isNotEmpty() &&
        (normalized in homePackages || normalized == systemUiPackage)
}

internal fun hasFocusedApplicationChangedToNavigationSurface(
    openingFocusedApplicationPackage: String?,
    currentFocusedApplicationPackage: String?,
    homePackages: Set<String>,
    systemUiPackage: String = "com.android.systemui",
): Boolean {
    val opening = openingFocusedApplicationPackage?.trim().orEmpty()
    val current = currentFocusedApplicationPackage?.trim().orEmpty()
    return opening.isNotEmpty() && current.isNotEmpty() && opening != current &&
        isUnarmedNavigationSurfacePackage(current, homePackages, systemUiPackage)
}

internal fun isSystemNavigationSurface(
    foregroundPackage: String,
    foregroundComponent: String?,
    homePackages: Set<String>,
    systemUiPackage: String = "com.android.systemui",
): Boolean {
    val normalizedPackage = foregroundPackage.trim()
    if (normalizedPackage in homePackages) return true
    if (normalizedPackage != systemUiPackage) return false
    val normalizedComponent = foregroundComponent?.lowercase().orEmpty()
    return normalizedComponent.contains("recents") ||
        normalizedComponent.contains("recent") ||
        normalizedComponent.contains("overview")
}

internal fun expandedOverlayForegroundSurfaceIdentity(
    foregroundPackage: String,
    foregroundComponent: String?,
    homePackages: Set<String>,
    systemUiPackage: String = "com.android.systemui",
): String {
    val normalizedPackage = foregroundPackage.trim()
    if (normalizedPackage in homePackages) return "home:$normalizedPackage"
    if (normalizedPackage != systemUiPackage) return "app:$normalizedPackage"
    return if (
        isSystemNavigationSurface(
            foregroundPackage = normalizedPackage,
            foregroundComponent = foregroundComponent,
            homePackages = homePackages,
            systemUiPackage = systemUiPackage,
        )
    ) {
        "system:recents"
    } else {
        "system:transient"
    }
}

internal fun shouldDismissOverlayForForegroundSurfaceChange(
    overlayActive: Boolean,
    jobRunning: Boolean,
    previousSurfaceIdentity: String?,
    currentSurfaceIdentity: String,
    currentSurfaceIsNavigation: Boolean,
    currentSurfaceIsCompact: Boolean = false,
): Boolean {
    if (!overlayActive || jobRunning || currentSurfaceIsCompact ||
        previousSurfaceIdentity == null || previousSurfaceIdentity == currentSurfaceIdentity ||
        currentSurfaceIdentity == "system:transient"
    ) {
        return false
    }
    if (currentSurfaceIsNavigation) return true
    val previousIsApplication = previousSurfaceIdentity.startsWith("app:") ||
        previousSurfaceIdentity.startsWith("home:")
    val currentIsApplication = currentSurfaceIdentity.startsWith("app:") ||
        currentSurfaceIdentity.startsWith("home:")
    return previousIsApplication && currentIsApplication
}

internal fun retainExpandedOverlaySurfaceBaseline(
    previousSurfaceIdentity: String?,
    currentSurfaceIdentity: String,
): String = previousSurfaceIdentity ?: currentSurfaceIdentity

internal fun retainLastRealForegroundSurfaceIdentity(
    previousSurfaceIdentity: String?,
    observation: ForegroundWindowObservation,
    ownPackage: String,
    ownWindowIsActivity: Boolean,
    homePackages: Set<String>,
    inputMethodPackages: Set<String>,
    currentSurfaceIsCompact: Boolean,
    systemUiPackage: String = "com.android.systemui",
): String? {
    val foregroundPackage = observation.packageName.trim()
    if (observation.kind != ForegroundObservationKind.WINDOW_STATE ||
        foregroundPackage.isEmpty() ||
        foregroundPackage in inputMethodPackages ||
        currentSurfaceIsCompact ||
        (foregroundPackage == ownPackage && !ownWindowIsActivity)
    ) {
        return previousSurfaceIdentity
    }
    return expandedOverlayForegroundSurfaceIdentity(
        foregroundPackage = foregroundPackage,
        foregroundComponent = observation.className,
        homePackages = homePackages,
        systemUiPackage = systemUiPackage,
    ).takeUnless { it == "system:transient" } ?: previousSurfaceIdentity
}

internal fun resolveInitialExpandedOverlaySurfaceIdentity(
    recentSurfaceIdentity: String?,
    lastRealSurfaceIdentity: String?,
    trackedTargetPackage: String?,
): String? = recentSurfaceIdentity
    ?: lastRealSurfaceIdentity
    ?: trackedTargetPackage
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { "app:$it" }

internal fun isSystemDialogNavigationReason(reason: String?): Boolean = when (
    reason?.trim()?.lowercase()
) {
    "homekey", "recentapps" -> true
    else -> false
}

internal fun shouldCollapseAfterOutsideTouch(
    expandedOverlayVisible: Boolean,
    jobRunning: Boolean,
    startedInSystemBar: Boolean,
    touchesCompactApplicationWindow: Boolean,
    observedPackage: String?,
    systemUiPackages: Set<String> = setOf("com.android.systemui"),
): Boolean {
    if (!expandedOverlayVisible || jobRunning || startedInSystemBar ||
        touchesCompactApplicationWindow
    ) return false
    val observed = observedPackage?.trim().orEmpty()
    if (observed.isEmpty()) return true
    return observed !in systemUiPackages
}

internal enum class OutsideTouchHandling {
    KEEP_OPEN,
    COLLAPSE_NOW,
}

internal enum class OverlayOpenTargetPolicy {
    USE_TRACKED_TARGET,
    REFRESH_FOREGROUND_FIRST,
}

internal fun overlayOpenTargetPolicy(usesShizuku: Boolean): OverlayOpenTargetPolicy =
    if (usesShizuku) {
        OverlayOpenTargetPolicy.REFRESH_FOREGROUND_FIRST
    } else {
        OverlayOpenTargetPolicy.USE_TRACKED_TARGET
    }

internal fun shouldAdoptShizukuOverlayOpenTarget(
    foregroundPackage: String?,
    ownPackage: String,
    homePackages: Set<String>,
    inputMethodPackages: Set<String>,
    systemUiPackage: String = "com.android.systemui",
): Boolean {
    val normalized = foregroundPackage?.trim().orEmpty()
    return normalized.isNotEmpty() &&
        normalized != ownPackage &&
        normalized != systemUiPackage &&
        normalized !in homePackages &&
        normalized !in inputMethodPackages
}

internal fun captureResultPackageName(
    directForegroundPackage: String?,
    expandedOverlaySurfaceIdentity: String?,
    trackedTargetPackage: String?,
    templatePackage: String?,
): String {
    val surfacePackage = expandedOverlaySurfaceIdentity
        ?.trim()
        ?.takeIf { it.startsWith("app:") || it.startsWith("home:") }
        ?.substringAfter(':')
        ?.trim()
        ?.takeIf(String::isNotEmpty)
    return sequenceOf(
        directForegroundPackage,
        surfacePackage,
        trackedTargetPackage,
        templatePackage,
    ).mapNotNull { candidate -> candidate?.trim()?.takeIf(String::isNotEmpty) }
        .firstOrNull()
        .orEmpty()
}

internal fun canApplyPreparedOverlayConfiguration(
    expectedOpenGeneration: Long,
    currentOpenGeneration: Long,
    openingTemplateRevision: Long,
    currentTemplateRevision: Long,
): Boolean = expectedOpenGeneration == currentOpenGeneration &&
    openingTemplateRevision == currentTemplateRevision

internal fun resolveOutsideTouchHandling(
    expandedOverlayVisible: Boolean,
    jobRunning: Boolean,
    touchesSystemBar: Boolean,
    touchesKnownCompactWindow: Boolean,
    observedPackage: String?,
): OutsideTouchHandling {
    val canCollapse = shouldCollapseAfterOutsideTouch(
        expandedOverlayVisible = expandedOverlayVisible,
        jobRunning = jobRunning,
        startedInSystemBar = touchesSystemBar,
        touchesCompactApplicationWindow = touchesKnownCompactWindow,
        observedPackage = observedPackage,
    )
    if (!canCollapse) return OutsideTouchHandling.KEEP_OPEN
    return OutsideTouchHandling.COLLAPSE_NOW
}

internal fun recentForegroundPackage(
    observation: ForegroundWindowObservation?,
    nowUptimeMs: Long,
    maximumAgeMs: Long,
): String? {
    observation ?: return null
    val ageMs = nowUptimeMs - observation.observedAtMs
    return observation.packageName.takeIf {
        it.isNotBlank() && ageMs in 0L..maximumAgeMs.coerceAtLeast(0L)
    }
}

internal fun shouldUseAccessibilityObservationAsExpandedBaseline(
    captureUsesShizuku: Boolean,
    observation: ForegroundWindowObservation?,
    nowUptimeMs: Long,
    maximumAgeMs: Long,
): Boolean {
    if (captureUsesShizuku || observation == null ||
        observation.kind != ForegroundObservationKind.WINDOW_STATE
    ) return false
    val ageMs = nowUptimeMs - observation.observedAtMs
    return ageMs in 0L..maximumAgeMs.coerceAtLeast(0L)
}

internal fun expandedOverlayTargetPackage(
    guardedTargetPackage: String?,
    detectedTargetPackage: String?,
    trackedTargetPackage: String?,
    templateTargetPackage: String?,
    preferDetectedTarget: Boolean,
): String? {
    fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)
    val backendTarget = if (preferDetectedTarget) {
        detectedTargetPackage.nonBlank() ?: trackedTargetPackage.nonBlank()
    } else {
        trackedTargetPackage.nonBlank() ?: detectedTargetPackage.nonBlank()
    }
    return guardedTargetPackage.nonBlank()
        ?: backendTarget
        ?: templateTargetPackage.nonBlank()
}

private fun fixedLayerFileName(side: FixedSide): String =
    "fixed_${side.name.lowercase()}.png"

internal fun capturedFixedLayerMetadata(
    layer: FixedLayerConfig,
    band: RectConfig,
    bitmapPath: String,
    bitmapWidth: Int,
    bitmapHeight: Int,
): CapturedFixedLayer {
    require(bitmapWidth == band.width && bitmapHeight == band.height) {
        "Fixed layer crop ${bitmapWidth}x$bitmapHeight does not match ${band.width}x${band.height}"
    }
    val localRepeat = layer.repeatRegion?.intersect(band)?.let { repeat ->
        RectConfig(
            repeat.left - band.left,
            repeat.top - band.top,
            repeat.right - band.left,
            repeat.bottom - band.top,
        )
    }
    return CapturedFixedLayer(
        side = layer.side,
        bitmapPath = bitmapPath,
        width = bitmapWidth,
        height = bitmapHeight,
        anchorCount = layer.anchorCount.coerceIn(1, 2),
        anchorPosition = layer.anchorPosition,
        repeatRegion = localRepeat,
    )
}

/** Owns the terminal layer belonging to the most recently accepted keyframe. */
internal class AcceptedKeyframeValue<T : Any>(
    private val dispose: (T) -> Unit,
) : AutoCloseable {
    var value: T? = null
        private set

    fun commit(candidate: T?) {
        if (candidate == null || candidate === value) return
        val previous = value
        value = candidate
        previous?.let(dispose)
    }

    fun discard(candidate: T?) {
        if (candidate != null && candidate !== value) dispose(candidate)
    }

    override fun close() {
        value?.let(dispose)
        value = null
    }
}

private class PendingTerminalRemainder(
    val scroll: Bitmap,
    val terminalFixed: Bitmap?,
    val match: StitchMatchResult,
    val replacementAttempts: Int = 0,
) : AutoCloseable {
    var duplicateConfirmations: Int = 0

    override fun close() {
        scroll.takeUnless { it.isRecycled }?.recycle()
        terminalFixed?.takeUnless { it.isRecycled }?.recycle()
    }
}

private data class CaptureCompletion(
    val sessionId: String,
    val frameCount: Int,
    val stitchedWidth: Int,
    val stitchedHeight: Int,
    val scrollDirection: ScrollDirection,
    val autoOpenResult: Boolean,
)

private data class TimedCompactApplicationWindow(
    val bounds: CompactApplicationWindowBounds,
    val observedAtMs: Long,
)

/**
 * Foreground coordinator for clean-frame capture.  All expensive work is
 * serialized on one worker so state transitions, Bitmap ownership and disk
 * writes remain deterministic.
 */
class GameLongShotCaptureService : Service(), OverlayController.Callback {

    data class Snapshot(
        val state: CaptureState = CaptureState.IDLE,
        val message: String = "",
        val frameCount: Int = 0,
        val stitchedWidth: Int = 0,
        val stitchedHeight: Int = 0,
        val confidence: Float = 0f,
        val resultSessionId: String? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    private data class CaptureStatusDelivery(
        val snapshot: Snapshot,
        val foregroundGeneration: Long?,
    )

    private data class JobWakeLockLease(
        val jobToken: Long,
        val wakeLock: PowerManager.WakeLock,
    )

    private val serviceInstanceId = SERVICE_INSTANCE_COUNTER.incrementAndGet()
    private val serviceDestroyed = AtomicBoolean(false)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GameLongShot-Coordinator")
    }
    private val recognitionGestureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GameLongShot-RecognitionGesture").apply { isDaemon = true }
    }
    private val recognitionGestureCancellationExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GameLongShot-RecognitionCancel").apply { isDaemon = true }
        }
    private val captureGestureExecutor = AtomicReference(newCaptureGestureExecutor())
    private val captureFrameExecutor = AtomicReference(newCaptureFrameExecutor())
    private val backendCleanupExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "GameLongShot-BackendCleanup").apply { isDaemon = true }
    }
    private val notificationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GameLongShot-Notification").apply { isDaemon = true }
    }
    private val statusDispatcher = ConflatedExecutorDispatcher(
        executor = notificationExecutor,
        deliver = ::deliverCaptureStatus,
        onFailure = { error -> Log.w(TAG, "Unable to deliver capture status", error) },
    )
    // Accessibility delivers foreground changes in normal mode. Shizuku mode intentionally
    // does not require that service, so keep a small privileged poller for Home/Recents/app
    // switches while an expanded overlay is visible.
    private val foregroundPoller = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GameLongShot-ForegroundPoller").apply { isDaemon = true }
    }
    private val foregroundPollingGeneration = AtomicLong(0L)
    private val shizukuForegroundUnknownSinceMs = AtomicLong(-1L)
    private val restoreRegionConfigurationAfterSafetyStop = AtomicBoolean(false)
    private val foregroundPollingLock = Any()
    private var foregroundPollingFuture: Future<*>? = null
    private val currentSnapshot = AtomicReference(latestSnapshot.get())
    private val stopRequested = AtomicBoolean(false)
    private val exitRequested = AtomicBoolean(false)
    private val jobRunning = AtomicBoolean(false)
    private val jobGeneration = AtomicLong(0L)
    private val captureBackendGeneration = AtomicLong(0L)
    private val captureBackendLost = AtomicBoolean(false)
    private val pageSamplingPausedForJob = AtomicBoolean(false)
    private val activeRecognitionGesture = AtomicReference<RecognitionGestureHandle?>(null)
    private val pendingCaptureCompletion = AtomicReference<CaptureCompletion?>(null)
    private val lastOverlayBackHandledAtMs = AtomicLong(0L)
    private val jobLifecycleLock = Any()
    private val stateLock = Any()
    private val foregroundNotificationLock = Any()
    private val jobWakeLockGuard = Any()
    private val foregroundNotificationGeneration = AtomicLong(0L)
    private val preserveTerminalNotificationOnDestroy = AtomicBoolean(false)
    private var foregroundStarted = false
    private var foregroundServiceType = Int.MIN_VALUE
    private var activeJobWakeLockLease: JobWakeLockLease? = null

    private data class GuardedRegionEdit(
        val target: TargetApplicationInfo,
        val rollbackTemplate: GameCaptureTemplate,
    )

    private class RecognitionGestureHandle(
        val distancePx: Int,
        private val completion: CountDownLatch,
        private val succeeded: AtomicBoolean,
        private val cancelAction: () -> Unit,
    ) {
        private val cancelDelivered = AtomicBoolean(false)

        fun isComplete(): Boolean = completion.count == 0L

        fun awaitCompletion(timeoutMs: Long): Boolean {
            completion.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            return isComplete()
        }

        fun succeeded(): Boolean = isComplete() && succeeded.get()

        fun isCancellationRequested(): Boolean = cancelDelivered.get()

        fun cancel() {
            if (cancelDelivered.compareAndSet(false, true)) cancelAction()
        }
    }

    private lateinit var overlay: OverlayController
    private lateinit var powerManager: PowerManager
    private lateinit var templateRepository: TemplateRepository
    private lateinit var templateReferenceStore: TemplateReferenceStore
    private lateinit var overlayDraftRepository: OverlayDraftRepository
    private lateinit var appPreferences: AppPreferences
    @Volatile private var localizedServiceLanguage: AppLanguage? = null
    @Volatile private var localizedServiceContext: Context? = null
    private val localizationLock = Any()
    private lateinit var sessionFileStore: SessionFileStore
    private lateinit var resultRepository: CaptureResultRepository
    private lateinit var regionLearningEngine: RegionLearningEngine
    private lateinit var stitchEngine: StitchEngine
    private lateinit var applicationLabelResolver: ApplicationLabelResolver
    private lateinit var homePackages: Set<String>
    private lateinit var inputMethodPackages: Set<String>
    private lateinit var foregroundApplicationTracker: ForegroundApplicationTracker
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayOpenGeneration = AtomicLong(0L)
    private val overlayTemplateRevision = AtomicLong(0L)
    @Volatile private var captureBackend: CaptureBackend? = null
    @Volatile private var captureBackendMode = CaptureBackendMode.MEDIA_PROJECTION
    @Volatile private var template: GameCaptureTemplate? = null
    @Volatile private var sessionConfig = CaptureSessionConfig()
    @Volatile private var targetApplication = TargetApplicationInfo("", "")
    @Volatile private var explicitOverlayTemplateSelection = false
    @Volatile private var guardedRegionEdit: GuardedRegionEdit? = null
    @Volatile private var expandedOverlayVisible = false
    @Volatile private var overlayOpening = false
    private val overlayInputSnapshotRequestAtOpen =
        AtomicReference<ShizukuInputNavigationSnapshotRequest?>(null)
    @Volatile private var latestForegroundObservation: ForegroundWindowObservation? = null
    @Volatile private var latestShizukuForegroundObservation: ForegroundWindowObservation? = null
    @Volatile private var latestShizukuCompactWindow: TimedCompactApplicationWindow? = null
    @Volatile private var overlayForegroundBaselineIdentity: String? = null
    @Volatile private var lastRealForegroundSurfaceIdentity: String? = null
    @Volatile private var rejectPendingOverlaySave = false
    @Volatile private var pendingWaitAfterGestureMs: Long? = null
    @Volatile private var pendingGestureStableTimeMs: Long? = null
    @Volatile private var pendingGestureDurationMs: Long? = null
    @Volatile private var pendingGestureDistancePx: Int? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == GameScrollAccessibilityService.ACTION_VOLUME_KEY_STOP) {
                requestStop(serviceText(R.string.capture_service_stopped_by_volume_key))
            }
        }
    }

    private val resultOpenedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_RESULT_OPENED) return
            intent.getStringExtra(EXTRA_SESSION_ID)
                ?.takeIf(String::isNotBlank)
                ?.let(::acknowledgeCaptureResultOpened)
        }
    }

    @Suppress("DEPRECATION")
    private val systemDialogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS ||
                !isSystemDialogNavigationReason(intent.getStringExtra(SYSTEM_DIALOG_REASON_EXTRA)) ||
                !shouldCollapseExpandedOverlayForNavigation(
                    expandedOverlayVisible = expandedOverlayVisible,
                    jobRunning = jobRunning.get(),
                    overlayOpening = overlayOpening,
                )
            ) return
            cancelPendingOrCollapseIdleOverlay()
        }
    }

    private val foregroundWindowListener =
        GameScrollAccessibilityService.ForegroundWindowListener(::onForegroundWindowChanged)
    private val navigationKeyListener =
        GameScrollAccessibilityService.NavigationKeyListener(::onNavigationKeyPressed)

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        ACTIVE_SERVICE_INSTANCE_ID.set(serviceInstanceId)
        ACTIVE_SERVICE_INSTANCE.set(this)
        NOTIFICATION_OWNER_INSTANCE_ID.set(serviceInstanceId)
        isServiceRunning = true
        powerManager = getSystemService(PowerManager::class.java)
        appPreferences = AppPreferences(this)
        createNotificationChannel()
        templateRepository = TemplateRepository(this)
        templateReferenceStore = TemplateReferenceStore(this)
        overlayDraftRepository = OverlayDraftRepository(this)
        sessionFileStore = SessionFileStore(this)
        resultRepository = CaptureResultRepository(this, sessionFileStore)
        regionLearningEngine = RegionLearningEngine()
        stitchEngine = StitchEngine()
        applicationLabelResolver = ApplicationLabelResolver(this)
        ShizukuBridge.initialize(this)
        homePackages = resolveHomePackages()
        inputMethodPackages = resolveInputMethodPackages()
        foregroundApplicationTracker = ForegroundApplicationTracker(
            ownPackageName = packageName,
            homePackages = homePackages,
            inputMethodPackages = inputMethodPackages,
        )
        overlay = OverlayController(this, this)
        val persistedSettings = appPreferences.loadSettings()
        sessionConfig = sessionConfig.copy(
            outputFormat = persistedSettings.outputFormat,
            jpegQuality = persistedSettings.jpegQuality,
            maxOutputHeight = persistedSettings.maxOutputHeight,
            previewMaxWidth = dpToPixels(persistedSettings.previewWidthDp),
            previewMaxHeight = dpToPixels(persistedSettings.previewMaxHeightDp),
            enablePreviewBetweenScroll = persistedSettings.previewEnabled,
            previewGapDurationMs = persistedSettings.gapDurationMs,
            autoOpenResult = persistedSettings.autoOpenResult,
        )
        overlay.updateOutputOptions(sessionConfig.outputFormat, sessionConfig.jpegQuality)
        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            IntentFilter(GameScrollAccessibilityService.ACTION_VOLUME_KEY_STOP),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            systemDialogReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            resultOpenedReceiver,
            IntentFilter(ACTION_RESULT_OPENED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        GameScrollAccessibilityService.addForegroundWindowListener(foregroundWindowListener)
        GameScrollAccessibilityService.addNavigationKeyListener(navigationKeyListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            publish(
                Snapshot(
                    state = CaptureState.IDLE,
                    message = serviceText(R.string.capture_service_state_expired),
                )
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            when (intent.action) {
            ACTION_START_OVERLAY -> {
                // Android 14 requires the mediaProjection foreground type before
                // getMediaProjection() is called.
                updateInputs(intent)
                acceptCaptureBackendWhileForeground(
                    intent,
                    serviceText(R.string.capture_service_preparing_screen_capture),
                )
                transition(
                    CaptureState.PERMISSION_CHECK,
                    serviceText(R.string.capture_service_checking_environment),
                )
                if (captureBackend?.isReady != true) {
                    reportError(missingCapturePermissionMessage())
                } else if (!overlay.canDrawOverlays()) {
                    reportError(serviceText(R.string.capture_service_grant_overlay_permission))
                } else {
                    transition(
                        CaptureState.REGION_CONFIG,
                        serviceText(R.string.capture_service_open_target_and_use_entry),
                    )
                    overlay.showEntry(template)
                }
            }
            ACTION_SHOW_CONFIG -> {
                updateInputs(intent)
                acceptCaptureBackendWhileForeground(
                    intent,
                    serviceText(R.string.capture_service_waiting_for_region_configuration),
                )
                onOpenControls()
            }
            ACTION_START_CAPTURE -> {
                updateInputs(intent)
                acceptCaptureBackendWhileForeground(
                    intent,
                    serviceText(R.string.capture_service_about_to_start),
                )
                startCaptureJob()
            }
            ACTION_LEARN_REGION -> {
                updateInputs(intent)
                acceptCaptureBackendWhileForeground(
                    intent,
                    serviceText(R.string.capture_service_preparing_region_learning),
                )
                startRegionLearningJob()
            }
            ACTION_UPDATE_CONFIG -> {
                updateInputs(intent)
                val restoreEntryAfterRefresh = overlayOpening && !expandedOverlayVisible
                if (!expandedOverlayVisible) {
                    overlayOpenGeneration.incrementAndGet()
                    overlayOpening = false
                }
                overlay.refreshAppearance()
                if (restoreEntryAfterRefresh) overlay.showEntry(template)
                createNotificationChannel()
                currentSnapshot.get().takeIf { it.state != CaptureState.IDLE }?.let { snapshot ->
                    publish(
                        snapshot.copy(message = localizedMessageForAppearanceRefresh(snapshot.state)),
                    )
                }
                if (captureBackend?.isReady != true && !jobRunning.get()) stopSelf(startId)
            }
            ACTION_STOP_CAPTURE,
            GameScrollAccessibilityService.ACTION_VOLUME_KEY_STOP ->
                requestStop(serviceText(R.string.capture_service_stopping_safely))
            ACTION_EXIT_CAPTURE -> exitCaptureSession()
                else -> {
                    ensureForeground(serviceText(R.string.capture_service_running))
                    overlay.showEntry(template)
                }
            }
        } catch (error: Throwable) {
            reportError(
                error.userMessage(serviceText(R.string.capture_service_start_failed)),
                error,
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceDestroyed.set(true)
        ACTIVE_SERVICE_INSTANCE_ID.compareAndSet(serviceInstanceId, 0L)
        ACTIVE_SERVICE_INSTANCE.compareAndSet(this, null)
        statusDispatcher.close()
        isServiceRunning = false
        stopRequested.set(true)
        forceReleaseJobWakeLock()
        if (!preserveTerminalNotificationOnDestroy.get()) {
            deactivateForegroundNotification(forceGeneration = true)
        }
        // Cancellation owns its own bounded worker. Do not block the service main thread while a
        // Binder or accessibility gesture is unwinding during teardown.
        activeRecognitionGesture.get()?.let { gesture -> runCatching(gesture::cancel) }
        pendingCaptureCompletion.set(null)
        overlayInputSnapshotRequestAtOpen.getAndSet(null)?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        GameScrollAccessibilityService.removeForegroundWindowListener(foregroundWindowListener)
        GameScrollAccessibilityService.removeNavigationKeyListener(navigationKeyListener)
        foregroundApplicationTracker.unlockTarget()
        guardedRegionEdit = null
        releaseCaptureInteractionForService()
        runCatching { unregisterReceiver(stopReceiver) }
        runCatching { unregisterReceiver(systemDialogReceiver) }
        runCatching { unregisterReceiver(resultOpenedReceiver) }
        runCatching { overlay.close() }
        val backend = synchronized(jobLifecycleLock) {
            captureBackendGeneration.incrementAndGet()
            captureBackend.also { current ->
                captureBackend = null
                (current as? ProjectionCaptureBackend)?.onStopped = null
            }
        }
        runCatching { backend?.close() }
        stopForegroundPolling()
        foregroundPoller.shutdownNow()
        recognitionGestureCancellationExecutor.shutdown()
        recognitionGestureExecutor.shutdownNow()
        captureGestureExecutor.get().shutdownNow()
        captureFrameExecutor.get().shutdownNow()
        backendCleanupExecutor.shutdownNow()
        notificationExecutor.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onOpenControls() {
        pendingCaptureCompletion.get()?.let { completion ->
            showCaptureCompletionSafely(completion)
            return
        }
        if (captureBackend?.isReady != true) {
            reportError(missingCapturePermissionMessage())
            onOpenApp()
            return
        }
        rejectPendingOverlaySave = false
        if (!jobRunning.get()) stopRequested.set(false)
        val inputSnapshotRequest = if (
            captureBackendMode == CaptureBackendMode.SHIZUKU &&
            ShizukuBridge.isModeReady(this)
        ) {
            runCatching {
                ShizukuBridge.requestCurrentInputNavigationSnapshot(
                    SHIZUKU_OVERLAY_OPEN_INPUT_TIMEOUT_MS,
                )
            }.getOrNull()
        } else {
            null
        }
        overlayInputSnapshotRequestAtOpen.getAndSet(inputSnapshotRequest)?.cancel()
        overlayForegroundBaselineIdentity = initialOverlayForegroundSurfaceIdentity()
        overlayOpening = true
        val expectedOpenGeneration = overlayOpenGeneration.incrementAndGet()
        val openingTemplateRevision = overlayTemplateRevision.get()
        val openingTargetPackage = targetApplication.packageName.trim()
        val targetPolicy = overlayOpenTargetPolicy(
            usesShizuku = captureBackendMode == CaptureBackendMode.SHIZUKU,
        )
        val provisionalSpec = runCatching { currentDisplaySpec() }.getOrElse { error ->
            overlayOpening = false
            reportError(
                error.userMessage(serviceText(R.string.capture_service_open_overlay_failed)),
                error,
            )
            return
        }
        val provisional = provisionalOverlayTemplateFor(provisionalSpec)
        template = provisional
        applyTemplateRuntimeConfig(provisional)
        transition(
            CaptureState.REGION_CONFIG,
            serviceText(R.string.capture_service_confirm_regions_to_start),
        )
        overlay.showConfigurationPreparingTemplates(
            template = provisional,
            targetPackageName = openingTargetPackage,
        ) { refreshToken ->
            if (refreshToken == null) {
                overlayOpening = false
                reportError(serviceText(R.string.capture_service_open_overlay_failed))
                return@showConfigurationPreparingTemplates
            }
            worker.execute {
                runCatching {
                    if (targetPolicy == OverlayOpenTargetPolicy.REFRESH_FOREGROUND_FIRST) {
                        refreshShizukuTargetForOverlayOpen()
                    }
                    val spec = currentDisplaySpec()
                    val prepared = prepareTemplateFor(spec)
                    val templates = compatibleTemplatesFor(spec)
                    Triple(prepared, templates, targetApplication.packageName)
                }.onSuccess { (prepared, templates, targetPackageName) ->
                    mainHandler.post {
                        applyPreparedOverlayConfiguration(
                            expectedOpenGeneration = expectedOpenGeneration,
                            openingTemplateRevision = openingTemplateRevision,
                            prepared = prepared,
                            templates = templates,
                            targetPackageName = targetPackageName,
                            refreshToken = refreshToken,
                        )
                    }
                }.onFailure { error ->
                    mainHandler.post {
                        if (expectedOpenGeneration != overlayOpenGeneration.get()) return@post
                        overlayOpening = false
                        overlay.finishTemplateLoading(
                            token = refreshToken,
                            templates = emptyList(),
                            targetPackageName = targetApplication.packageName,
                        )
                        if (exitRequested.get()) return@post
                        if (captureBackend?.isReady != true) {
                            reportError(error.userMessage(missingCapturePermissionMessage()), error)
                            return@post
                        }
                        if (expandedOverlayVisible) {
                            Log.w(TAG, "Unable to refresh an already visible overlay", error)
                        } else {
                            reportError(
                                error.userMessage(
                                    serviceText(R.string.capture_service_open_overlay_failed),
                                ),
                                error,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun applyPreparedOverlayConfiguration(
        expectedOpenGeneration: Long,
        openingTemplateRevision: Long,
        prepared: GameCaptureTemplate,
        templates: List<GameCaptureTemplate>,
        targetPackageName: String,
        refreshToken: OverlayController.ConfigurationRefreshToken,
    ) {
        if (expectedOpenGeneration != overlayOpenGeneration.get()) return
        if (!overlay.isConfigurationRefreshActive(refreshToken) ||
            !canApplyPreparedOverlayConfiguration(
                expectedOpenGeneration = expectedOpenGeneration,
                currentOpenGeneration = overlayOpenGeneration.get(),
                openingTemplateRevision = openingTemplateRevision,
                currentTemplateRevision = overlayTemplateRevision.get(),
            )
        ) {
            overlay.finishTemplateLoading(
                token = refreshToken,
                templates = templates,
                targetPackageName = targetPackageName,
            )
            return
        }
        overlayOpening = false
        if (exitRequested.get()) return
        if (captureBackend?.isReady != true) {
            reportError(missingCapturePermissionMessage())
            return
        }
        template = prepared
        applyTemplateRuntimeConfig(prepared)
        transition(
            CaptureState.REGION_CONFIG,
            serviceText(R.string.capture_service_confirm_regions_to_start),
        )
        overlay.showConfiguration(
            prepared,
            templates,
            targetPackageName = targetPackageName,
            expectedRefreshToken = refreshToken,
            templatesLoading = false,
        )
    }

    override fun onExpandedOverlayShown() {
        lastOverlayBackHandledAtMs.set(0L)
        expandedOverlayVisible = true
        if (overlayForegroundBaselineIdentity == null) {
            overlayForegroundBaselineIdentity = initialOverlayForegroundSurfaceIdentity()
        }
        if (shouldGuardExpandedOverlay(jobRunning.get(), guardedRegionEdit != null)) {
            lockExpandedOverlayTarget()
        } else {
            // A compact bubble may have survived a cancelled task. Never carry that stale lock
            // into an idle configuration page opened from Home or this app itself.
            foregroundApplicationTracker.unlockTarget()
        }
        startForegroundPolling()
    }

    override fun onExpandedOverlayDismissed() {
        overlayOpenGeneration.incrementAndGet()
        overlayOpening = false
        overlayInputSnapshotRequestAtOpen.getAndSet(null)?.cancel()
        expandedOverlayVisible = false
        if (!jobRunning.get()) {
            latestShizukuForegroundObservation = null
            latestShizukuCompactWindow = null
            overlayForegroundBaselineIdentity = null
            stopForegroundPolling()
        }
        if (guardedRegionEdit == null && !jobRunning.get()) {
            foregroundApplicationTracker.unlockTarget()
        }
    }

    override fun onOverlayBackPressed(template: GameCaptureTemplate?) {
        val now = SystemClock.uptimeMillis()
        val previous = lastOverlayBackHandledAtMs.getAndSet(now)
        if (now - previous < OVERLAY_BACK_DEBOUNCE_MS) return
        // Back dismisses only the expanded control surface. The capture service remains alive and
        // the compact entry point stays available for the next action. This must also work when
        // a capture backend was revoked or is still being recreated: Back is a UI dismissal, not
        // a capture-permission operation.
        if (!isLiveServiceInstance()) return
        val normalizedTemplate = template?.withNormalizedRegions()
        val changedTemplate = normalizedTemplate?.takeIf { it != this.template }
        // Commit the current draft and release the foreground lock before collapsing.
        completeGuardedRegionEdit()
        overlay.showEntry(normalizedTemplate ?: this.template)
        changedTemplate?.let { changed ->
            mainHandler.post {
                if (isLiveServiceInstance()) onTemplateChanged(changed)
            }
        }
    }

    override fun onOverlayOutsideTouched(
        rawX: Float,
        rawY: Float,
        touchesSystemBar: Boolean,
        onConfirmed: () -> Unit,
    ) {
        val accessibilityConnected = GameScrollAccessibilityService.isConnected()
        val hasTouchCoordinates = rawX.isFinite() && rawY.isFinite() &&
            (rawX != 0f || rawY != 0f)
        val accessibilityCompactWindow = hasTouchCoordinates && accessibilityConnected &&
            GameScrollAccessibilityService.isPointInsideCompactApplicationWindow(
                rawX.roundToInt(),
                rawY.roundToInt(),
            )
        val nowUptimeMs = SystemClock.uptimeMillis()
        val shizukuCompactWindow = hasTouchCoordinates && !accessibilityConnected &&
            latestShizukuCompactWindow?.takeIf { window ->
                nowUptimeMs - window.observedAtMs in
                    0L..EXPANDED_OVERLAY_BASELINE_MAX_AGE_MS
            }?.bounds?.contains(rawX.roundToInt(), rawY.roundToInt()) == true
        fun observedPackage(): String? = if (accessibilityConnected) {
            recentForegroundPackage(
                latestForegroundObservation,
                nowUptimeMs,
                EXPANDED_OVERLAY_BASELINE_MAX_AGE_MS,
            )
        } else {
            recentForegroundPackage(
                latestShizukuForegroundObservation,
                nowUptimeMs,
                EXPANDED_OVERLAY_BASELINE_MAX_AGE_MS,
            )
        }
        val handling = resolveOutsideTouchHandling(
            expandedOverlayVisible = expandedOverlayVisible || overlayOpening,
            jobRunning = jobRunning.get(),
            touchesSystemBar = touchesSystemBar,
            touchesKnownCompactWindow = accessibilityCompactWindow || shizukuCompactWindow,
            observedPackage = observedPackage(),
        )
        when (handling) {
            OutsideTouchHandling.KEEP_OPEN -> Unit
            OutsideTouchHandling.COLLAPSE_NOW -> onConfirmed()
        }
    }

    override fun canShowCaptureGap(): Boolean = jobRunning.get()

    override fun canShowCaptureCompletion(resultSessionId: String): Boolean =
        isLiveServiceInstance() &&
            !jobRunning.get() &&
            !exitRequested.get() &&
            pendingCaptureCompletion.get()?.sessionId == resultSessionId

    override fun onCaptureCompletionConfirmed(resultSessionId: String) {
        synchronized(jobLifecycleLock) {
            if (consumeCaptureCompletion(resultSessionId) == null) return
            restoreCaptureControlsAfterCompletion()
        }
    }

    override fun onCaptureCompletionOpen(resultSessionId: String) {
        val completion = synchronized(jobLifecycleLock) {
            pendingCaptureCompletion.get()?.takeIf { it.sessionId == resultSessionId }
        } ?: return
        requestOpenCaptureCompletion(completion)
    }

    override fun onTemplateChanged(template: GameCaptureTemplate) {
        overlayOpening = false
        overlayTemplateRevision.incrementAndGet()
        completeGuardedRegionEdit()
        val merged = mergeTemplateIdentity(template).withNormalizedRegions()
        this.template = merged
        pendingWaitAfterGestureMs = merged.waitAfterGestureMs
        pendingGestureStableTimeMs = merged.gestureStableTimeMs
        pendingGestureDurationMs = merged.gestureDurationMs
        pendingGestureDistancePx = merged.gestureDistancePx
        applyTemplateRuntimeConfig(merged)
        worker.execute {
            overlayDraftRepository.save(merged)
        }
        if (!jobRunning.get()) {
            transition(
                CaptureState.READY_TO_CAPTURE,
                serviceText(R.string.capture_service_configuration_applied),
            )
        }
    }

    override fun onTemplateSelected(template: GameCaptureTemplate) {
        explicitOverlayTemplateSelection = true
        onTemplateChanged(template)
    }

    override fun onOutputOptionsChanged(format: ImageOutputFormat, jpegQuality: Int) {
        overlayOpening = false
        overlayTemplateRevision.incrementAndGet()
        val quality = AppPreferences.normalizeJpegQuality(jpegQuality)
        sessionConfig = sessionConfig.copy(outputFormat = format, jpegQuality = quality)
        overlay.updateOutputOptions(format, quality)
        val current = template?.copy(outputFormat = format, jpegQuality = quality)?.also {
            template = it
        }
        worker.execute {
            current?.let(overlayDraftRepository::save)
        }
    }

    override fun onResolveTargetApplication(onResolved: (TargetApplicationInfo?) -> Unit) {
        worker.execute {
            onResolved(resolveCurrentTargetApplication())
        }
    }

    override fun onSaveTemplate(
        template: GameCaptureTemplate,
        name: String,
        confirmation: CrossAppOverwriteConfirmation?,
        onResult: (OverlayController.TemplateSaveResult) -> Unit,
    ) {
        worker.execute {
            if (rejectPendingOverlaySave) {
                onResult(
                    OverlayController.TemplateSaveResult.Rejected(
                        serviceText(R.string.capture_service_left_target_edit_cancelled),
                    ),
                )
                return@execute
            }
            val target = resolveCurrentTargetApplication()
            if (target == null) {
                onResult(
                    OverlayController.TemplateSaveResult.Rejected(
                        serviceText(R.string.capture_service_cannot_identify_current_app),
                    ),
                )
                return@execute
            }
            val cleanName = name.trim().takeIf { it.isNotEmpty() }
                ?: target.appName.takeIf { it.isNotBlank() && it != target.packageName }
                ?: template.templateName.takeIf(String::isNotBlank)
                ?: serviceText(R.string.capture_service_game_template)
            val resolvedAppName = target.appName.takeIf {
                it.isNotBlank() && it != target.packageName
            } ?: template.appName.takeIf {
                it.isNotBlank() && it != template.packageName && template.packageName == target.packageName
            }.orEmpty()
            val candidate = template.copy(
                packageName = target.packageName,
                appName = resolvedAppName,
                templateName = cleanName,
            ).withNormalizedRegions()
            val previousTemplate = candidate.id.takeIf { it != 0L }
                ?.let(templateRepository::getById)
            var referenceFrame: Bitmap? = null
            runCatching {
                val backend = captureBackend?.takeIf { it.isReady }
                    ?: error(missingCapturePermissionMessage())
                val spec = currentDisplaySpec()
                check(candidate.screenWidth == spec.width && candidate.screenHeight == spec.height) {
                    serviceText(R.string.capture_service_template_screen_size_mismatch)
                }
                hideOverlayAndWait(
                    waitMs = candidate.waitAfterOverlayHiddenMs,
                    preservePendingUiRequest = true,
                    interruptible = false,
                )
                check(!rejectPendingOverlaySave) {
                    serviceText(R.string.capture_service_left_target_edit_cancelled)
                }
                referenceFrame = captureFreshFrame(backend, spec)
                check(!rejectPendingOverlaySave) {
                    serviceText(R.string.capture_service_left_target_edit_cancelled)
                }
                val visibleContent = overlay.currentVisibleNavigationContentBounds(
                    screenWidth = spec.width,
                    screenHeight = spec.height,
                )
                // Persist the user's physical selection and the current navigation baseline. The
                // live session policy decides whether the current navigation strip becomes fixed.
                val saveCandidate = navigationTemplateForPersistence(candidate, visibleContent)
                    ?: error(
                        serviceText(R.string.capture_service_navigation_overlaps_capture_regions),
                    )
                templateRepository.saveForTarget(saveCandidate, target, confirmation)
            }
                .onSuccess { commit ->
                    when (commit) {
                        is TemplateSaveCommitResult.RequiresConfirmation -> onResult(
                            OverlayController.TemplateSaveResult.ConfirmationRequired(
                                commit.confirmation,
                            ),
                        )
                        is TemplateSaveCommitResult.Saved -> {
                            val saved = commit.template
                            val referenceError = runCatching {
                                templateReferenceStore.save(saved.id, checkNotNull(referenceFrame))
                            }.exceptionOrNull()
                            if (referenceError != null) {
                                runCatching {
                                    if (previousTemplate == null) {
                                        check(templateRepository.delete(saved.id))
                                    } else {
                                        checkNotNull(
                                            templateRepository.replacePreservingTimestamps(previousTemplate),
                                        )
                                    }
                                }.onFailure { rollbackError ->
                                    Log.e(TAG, "Unable to roll back template after reference failure", rollbackError)
                                }
                                onResult(
                                    OverlayController.TemplateSaveResult.Rejected(
                                        serviceText(
                                            R.string.capture_service_template_reference_save_failed,
                                            referenceError.userMessage(
                                                serviceText(R.string.capture_service_unknown_error),
                                            ),
                                        ),
                                    ),
                                )
                                return@onSuccess
                            }
                            this.template = saved
                            completeGuardedRegionEdit()
                            overlayDraftRepository.save(saved)
                            transition(
                                CaptureState.READY_TO_CAPTURE,
                                serviceText(
                                    R.string.capture_service_template_saved,
                                    saved.templateName,
                                ),
                            )
                            onResult(OverlayController.TemplateSaveResult.Saved(saved))
                        }
                    }
                }
                .onFailure { error ->
                    onResult(
                        OverlayController.TemplateSaveResult.Rejected(
                            serviceText(
                                R.string.capture_service_template_save_failed,
                                error.userMessage(
                                    serviceText(R.string.capture_service_unknown_error),
                                ),
                            ),
                        ),
                    )
                }
                .also { referenceFrame?.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
    }

    override fun onEditRegions(template: GameCaptureTemplate) {
        onTemplateChanged(template)
        guardRegionEdit(template)
        transition(
            CaptureState.REGION_CONFIG,
            serviceText(R.string.capture_service_adjust_regions),
        )
    }

    override fun onLearnRegions(template: GameCaptureTemplate) {
        onTemplateChanged(template)
        guardRegionEdit(template)
        startRegionLearningJob()
    }

    override fun onStartCapture(template: GameCaptureTemplate) {
        onTemplateChanged(template)
        startCaptureJob()
    }

    override fun onStopCapture() =
        requestStop(serviceText(R.string.capture_service_user_requested_stop))

    override fun onExitCapture() = exitCaptureSession()

    override fun onOpenApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun startCaptureJob() {
        val jobToken = beginJob() ?: return
        submitCaptureJob(
            jobToken = jobToken,
            failureMessage = serviceText(R.string.capture_service_automatic_capture_failed),
        ) {
            var completion: CaptureCompletion? = null
            try {
                completion = captureLoop()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!stopRequested.get()) {
                    reportError(serviceText(R.string.capture_service_task_interrupted), interrupted)
                }
            } catch (error: Throwable) {
                reportError(
                    error.userMessage(serviceText(R.string.capture_service_automatic_capture_failed)),
                    error,
                )
            } finally {
                var backendLossWithoutCompletion = false
                try {
                    synchronized(jobLifecycleLock) {
                        if (!isLiveServiceInstance()) {
                            jobRunning.set(false)
                            return@synchronized
                        }
                        val completed = completion
                        if (!exitRequested.get() && completed != null) {
                            // Publish the completion before clearing jobRunning. Projection onStop
                            // can then distinguish a result handoff from a genuinely idle failure.
                            pendingCaptureCompletion.set(completed)
                        }
                        jobRunning.set(false)
                        if (exitRequested.get()) {
                            finishExitCaptureSession()
                        } else if (completed != null) {
                            if (completed.autoOpenResult) {
                                requestOpenCaptureCompletion(completed)
                            } else {
                                showCaptureCompletionSafely(completed)
                            }
                        } else if (captureBackend != null && !captureBackendLost.get() &&
                            currentSnapshot.get().state == CaptureState.READY_TO_CAPTURE
                        ) {
                            overlay.showEntry(template)
                        } else if (captureBackendLost.get()) {
                            backendLossWithoutCompletion = true
                        }
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to restore capture controls after the job", error)
                    jobRunning.set(false)
                } finally {
                    if (backendLossWithoutCompletion) {
                        reportError(serviceText(R.string.capture_service_projection_stopped))
                    }
                    finishJobForegroundPollingIfIdle()
                    pauseCaptureBackendAsync(jobToken)
                }
            }
        }
    }

    private fun captureLoop(): CaptureCompletion? {
        requireCaptureBackendAndGesture()
        // Settings changed while a capture is running apply to the next capture. Keeping one
        // immutable snapshot prevents the encoded file and its result metadata from diverging.
        val directForegroundPackage = refreshShizukuTargetApplication()
        val spec = currentDisplaySpec()
        val preparedTemplate = prepareTemplateFor(spec)
        val currentTarget = resolveCurrentTargetApplication()
        val capturedPackageName = captureResultPackageName(
            directForegroundPackage = directForegroundPackage,
            expandedOverlaySurfaceIdentity = overlayForegroundBaselineIdentity,
            trackedTargetPackage = currentTarget?.packageName,
            templatePackage = preparedTemplate.packageName,
        )
        val capturedAppName = chooseCaptureAppName(
            resolvedName = currentTarget
                ?.takeIf { it.packageName == capturedPackageName }
                ?.displayName
                ?: applicationLabelResolver.resolve(capturedPackageName),
            packageName = capturedPackageName,
            templateAppName = preparedTemplate.appName,
            templatePackageName = preparedTemplate.packageName,
            fallbackName = serviceText(R.string.capture_service_unknown_app),
        )
        // Keep the editable/persisted template in physical-display coordinates. Navigation-bar
        // clipping below is a runtime-only session snapshot.
        template = preparedTemplate
        applyTemplateRuntimeConfig(preparedTemplate)
        val captureConfig = sessionConfig.forNewCaptureJob(
            globalAutoOpenResult = appPreferences.loadSettings().autoOpenResult,
        )
        validateTemplate(preparedTemplate, spec)
        lockExpandedOverlayTarget()
        val backend = captureBackend ?: error(missingCapturePermissionMessage())

        transition(
            CaptureState.CAPTURE_PREPARE,
            serviceText(R.string.capture_service_removing_overlay_and_checking_navigation),
        )
        hideOverlayAndWait(preparedTemplate.waitAfterOverlayHiddenMs)
        if (stopRequested.get()) {
            transition(
                CaptureState.READY_TO_CAPTURE,
                serviceText(R.string.capture_service_capture_stopped_ready),
            )
            return null
        }
        if (shouldAbortCaptureAction()) return null
        val restoredSpec = currentDisplaySpec()
        check(restoredSpec.width == spec.width && restoredSpec.height == spec.height &&
            restoredSpec.rotation == spec.rotation
        ) { serviceText(R.string.capture_service_display_changed_after_overlay_hidden) }
        val navigationContentBounds = overlay.currentVisibleNavigationContentBounds(
            screenWidth = spec.width,
            screenHeight = spec.height,
        )
        val activeTemplate = captureNavigationTemplateForSession(
            template = preparedTemplate,
            visibleNavigationContentBounds = navigationContentBounds,
        ) ?: error(serviceText(R.string.capture_service_navigation_overlaps_capture_regions))
        validateTemplate(activeTemplate, spec)
        Log.i(
            TAG,
            "captureNavigationBounds content=$navigationContentBounds " +
                "capture=${activeTemplate.captureRegion} stitch=${activeTemplate.stitchRegion} " +
                "gesture=${activeTemplate.gestureRegion}",
        )

        val sessionId = sessionFileStore.createSession()
        val acceptedFrames = mutableListOf<CapturedFrame>()
        val seamPositions = mutableListOf<Int>()
        val lowConfidenceSeams = mutableListOf<Int>()
        var topPath: String? = null
        var bottomPath: String? = null
        var thumbnailPath: String? = null
        val capturedFixedLayers = linkedMapOf<FixedSide, CapturedFixedLayer>()
        // Keep stitching and export in physical screenshot coordinates. The small gap preview is
        // rotated only when the template's explicit orientation differs from those coordinates.
        val captureGapPreviewOrientation = captureGapPreviewOrientation(
            templateOrientation = activeTemplate.orientation,
            physicalWidth = spec.width,
            physicalHeight = spec.height,
            scrollDirection = activeTemplate.scrollDirection,
        )
        val displayedPreviewTarget = captureGapDisplayedTargetSize(
            screenWidth = spec.width,
            screenHeight = spec.height,
            referenceThickness = dpToPixels(DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP),
            scrollDirection = captureGapPreviewOrientation.scrollDirection,
        )
        val sourcePreviewTarget = captureGapPreviewOrientation.thumbnailMaxSize(
            displayedPreviewTarget.width,
            displayedPreviewTarget.height,
        )
        val stitchAccumulator = DiskBackedStitchAccumulator(
            fileStore = sessionFileStore,
            sessionId = sessionId,
            direction = activeTemplate.scrollDirection,
            previewTargetWidth = sourcePreviewTarget.width,
            previewTargetHeight = sourcePreviewTarget.height,
        )
        var previous: Bitmap? = null
        var preview: Bitmap? = null
        var displayedPreview: Bitmap? = null
        var processingGapRequested = false
        var processingGapVisible = false
        var processingGapGeneration = 0L
        var processingGapDrawLatch: CountDownLatch? = null
        val processingGapDrawnAtMs = AtomicLong(0L)
        val processingGapPresented = AtomicReference<Boolean?>(null)
        val retiredProcessingGapPreviews = CopyOnWriteArraySet<Bitmap>()
        var finalizedScroll: FinalizedScroll? = null
        var persistedScrollPath: String? = null
        var persistedCompletion: CaptureCompletion? = null
        val acceptedTerminalFixed = AcceptedKeyframeValue<Bitmap> { bitmap ->
            bitmap.takeUnless { it.isRecycled }?.recycle()
        }
        var pendingTerminalRemainder: PendingTerminalRemainder? = null
        var resultPersisted = false
        val initialMaximumDistance = maxGestureDistance(activeTemplate)
        val gestureDistance = activeTemplate.gestureDistancePx.coerceIn(
            1,
            initialMaximumDistance,
        )
        val gestureAttempts = captureGestureAttempts(
            configuredDistancePx = gestureDistance,
            physicalAxisLength = if (activeTemplate.scrollDirection.isVertical) {
                spec.height
            } else {
                spec.width
            },
            maximumExecutableDistancePx = maximumScrollGestureDistance(
                activeTemplate.stitchRegion,
                activeTemplate.scrollDirection,
            ),
        )
        var gestureRetryState = CaptureGestureRetryState()
        var identicalFrameEvidence = 0
        var observedDuplicateGestureEvidence = 0
        var expectedShiftSinceKeyframe = 0
        var confidenceTotal = 1f
        var matchCount = 1

        val terminalFixedSide = terminalFixedSideFor(activeTemplate.scrollDirection)
        val terminalPhysicalSide = when (terminalFixedSide) {
            TerminalFixedSide.LEADING -> if (activeTemplate.scrollDirection.isVertical) {
                FixedSide.TOP
            } else {
                FixedSide.LEFT
            }
            TerminalFixedSide.TRAILING -> if (activeTemplate.scrollDirection.isVertical) {
                FixedSide.BOTTOM
            } else {
                FixedSide.RIGHT
            }
        }
        val terminalFixedRegion = activeTemplate.fixedLayers
            .firstOrNull { it.side == terminalPhysicalSide }
            ?.let { activeTemplate.fixedBandRegion(it.side) }
        fun cropCandidateLayers(full: Bitmap): Pair<Bitmap, Bitmap?> {
            val scroll = cropFrame(full, activeTemplate.stitchRegion)
            return try {
                scroll to terminalFixedRegion?.let { region -> cropFrame(full, region) }
            } catch (error: Throwable) {
                scroll.recycle()
                throw error
            }
        }
        fun createCaptureGapPreview(image: Bitmap): Bitmap? {
            if (!captureConfig.enablePreviewBetweenScroll) return null
            val thumbnail = BitmapFrameUtils.thumbnail(
                image,
                sourcePreviewTarget.width,
                sourcePreviewTarget.height,
            )
            val ownedThumbnail = if (thumbnail === image) {
                image.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                thumbnail
            }
            return if (captureGapPreviewOrientation.rotateQuarterTurn) {
                runCatching {
                    BitmapFrameUtils.rotateClockwise90(ownedThumbnail)
                }.fold(
                    onSuccess = { rotated ->
                        if (!ownedThumbnail.isRecycled) ownedThumbnail.recycle()
                        rotated
                    },
                    onFailure = { ownedThumbnail },
                )
            } else {
                ownedThumbnail
            }
        }
        fun renderProcessingGap(
            message: String,
            frameCount: Int,
            processingFrameNumber: Int?,
            stitchedWidth: Int,
            stitchedHeight: Int,
            previewBitmap: Bitmap?,
            restartVisibilityTimer: Boolean,
            allowWhenStopping: Boolean = false,
        ) {
            if (stopRequested.get() && !allowWhenStopping) return
            processingGapGeneration++
            if (restartVisibilityTimer) {
                processingGapDrawnAtMs.set(0L)
            }
            val generation = processingGapGeneration
            val drawLatch = CountDownLatch(1)
            processingGapDrawLatch = drawLatch
            processingGapPresented.set(null)
            val contentSize = captureGapPreviewOrientation.contentSize(
                stitchedWidth,
                stitchedHeight,
            )
            val previouslyDisplayed = displayedPreview
                ?.takeUnless { it === previewBitmap || it.isRecycled }
            previouslyDisplayed?.let(retiredProcessingGapPreviews::add)
            displayedPreview = previewBitmap
            processingGapRequested = true
            processingGapVisible = true
            overlay.showCaptureGap(
                OverlayController.CaptureGapInfo(
                    frameCount = frameCount,
                    processingFrameNumber = processingFrameNumber,
                    stitchedWidth = stitchedWidth,
                    stitchedHeight = stitchedHeight,
                    confidence = confidenceTotal / matchCount,
                    preview = previewBitmap,
                    previewContentWidth = contentSize.width,
                    previewContentHeight = contentSize.height,
                    previewScrollDirection = captureGapPreviewOrientation.scrollDirection,
                    message = message,
                ),
            ) { presented ->
                if (processingGapGeneration == generation) {
                    processingGapPresented.set(presented)
                    if (presented) {
                        processingGapDrawnAtMs.compareAndSet(
                            0L,
                            SystemClock.elapsedRealtime(),
                        )
                    }
                }
                drawLatch.countDown()
                previouslyDisplayed?.let { retired ->
                    if (retiredProcessingGapPreviews.remove(retired) && !retired.isRecycled) {
                        retired.recycle()
                    }
                }
            }
        }
        fun displayProcessingGap(
            message: String,
            frameCount: Int = acceptedFrames.size,
            processingFrameNumber: Int? = null,
            stitchedWidth: Int = stitchAccumulator.logicalWidth,
            stitchedHeight: Int = stitchAccumulator.logicalHeight,
            previewBitmap: Bitmap? = preview,
            allowWhenStopping: Boolean = false,
        ) = renderProcessingGap(
            message = message,
            frameCount = frameCount,
            processingFrameNumber = processingFrameNumber,
            stitchedWidth = stitchedWidth,
            stitchedHeight = stitchedHeight,
            previewBitmap = previewBitmap,
            restartVisibilityTimer = true,
            allowWhenStopping = allowWhenStopping,
        )
        fun refreshProcessingGap(
            message: String,
            allowWhenStopping: Boolean = false,
        ) {
            if (!processingGapVisible) return
            renderProcessingGap(
                message = message,
                frameCount = acceptedFrames.size,
                processingFrameNumber = null,
                stitchedWidth = stitchAccumulator.logicalWidth,
                stitchedHeight = stitchAccumulator.logicalHeight,
                previewBitmap = preview,
                restartVisibilityTimer = false,
                allowWhenStopping = allowWhenStopping,
            )
        }
        fun waitForMinimumProcessingGapVisibility(interruptible: Boolean = true) {
            if (!processingGapRequested) return
            val drawLatch = processingGapDrawLatch
            if (drawLatch != null && drawLatch.count > 0L) {
                val deadline = SystemClock.elapsedRealtime() + OVERLAY_REMOVE_TIMEOUT_MS
                while (drawLatch.count > 0L) {
                    if (interruptible && stopRequested.get()) return
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    drawLatch.await(minOf(remaining, 80L), TimeUnit.MILLISECONDS)
                }
                if (drawLatch.count > 0L) {
                    Log.w(TAG, "Capture gap did not draw before its visibility timer")
                }
            }
            if (processingGapPresented.get() != true) {
                processingGapVisible = false
                throw IllegalStateException(
                    serviceText(R.string.capture_service_overlay_unavailable_saving),
                )
            }
            // If the first draw callback is late, keep polling in short slices. Once the actual
            // draw timestamp arrives the remaining duration is recomputed from that timestamp,
            // so time spent waiting on a blocked UI thread can never shorten the visible banner.
            val conservativeFallbackStartedAtMs = SystemClock.elapsedRealtime()
            while (!interruptible || !stopRequested.get()) {
                val visibleSinceMs = processingGapDrawnAtMs.get().takeIf { it > 0L }
                    ?: conservativeFallbackStartedAtMs
                val remainingVisibleMs = remainingMinimumBannerVisibleMs(
                    configuredMinimumVisibleMs = activeTemplate.waitAfterGestureMs,
                    elapsedSinceBannerDrawnMs = SystemClock.elapsedRealtime() - visibleSinceMs,
                )
                if (remainingVisibleMs <= 0L) break
                val slice = minOf(remainingVisibleMs, 80L)
                if (interruptible) sleepInterruptibly(slice) else Thread.sleep(slice)
            }
        }
        fun clearProcessingGap(
            waitMs: Long = 0L,
            interruptible: Boolean = true,
            enforceMinimumVisibleDuration: Boolean = interruptible,
        ) {
            var removalConfirmed = !processingGapRequested
            try {
                if (processingGapVisible && enforceMinimumVisibleDuration) {
                    waitForMinimumProcessingGapVisibility(interruptible)
                }
                if (processingGapRequested) {
                    hideOverlayAndWait(waitMs, interruptible = interruptible)
                    removalConfirmed = true
                } else if (waitMs > 0L) {
                    if (interruptible) sleepInterruptibly(waitMs) else Thread.sleep(waitMs)
                }
            } finally {
                processingGapGeneration++
                processingGapVisible = false
                processingGapDrawLatch = null
                processingGapDrawnAtMs.set(0L)
                processingGapPresented.set(null)
                if (removalConfirmed) {
                    processingGapRequested = false
                    displayedPreview
                        ?.takeUnless { it === preview || it.isRecycled }
                        ?.recycle()
                    retiredProcessingGapPreviews.forEach { retired ->
                        if (!retired.isRecycled) retired.recycle()
                    }
                    retiredProcessingGapPreviews.clear()
                    displayedPreview = null
                }
            }
        }

        fun persistConfirmedCapture(): CaptureCompletion {
            persistedCompletion?.let { return it }
            check(acceptedFrames.isNotEmpty()) { "No confirmed capture frame is available" }

            acceptedTerminalFixed.value?.let { terminal ->
                val existing = capturedFixedLayers[terminalPhysicalSide]
                if (existing != null) {
                    val path = sessionFileStore.saveBitmap(
                        sessionId,
                        fixedLayerFileName(terminalPhysicalSide),
                        terminal,
                    )
                    capturedFixedLayers[terminalPhysicalSide] = existing.copy(
                        bitmapPath = path,
                        width = terminal.width,
                        height = terminal.height,
                    )
                }
            }

            val orderedFixedLayers = activeTemplate.fixedLayers.mapNotNull { layer ->
                capturedFixedLayers[layer.side]
            }
            // Legacy slots remain populated so existing previews and old result readers can still
            // open new captures. Schema 7 exporters use fixedLayers and their physical sides.
            topPath = orderedFixedLayers.getOrNull(0)?.bitmapPath
            bottomPath = orderedFixedLayers.getOrNull(1)?.bitmapPath

            val finalized = finalizedScroll ?: stitchAccumulator.finalizeScroll().also {
                finalizedScroll = it
            }
            val scrollPath = persistedScrollPath ?: (
                if (finalized.isChunked) {
                    sessionFileStore.saveScrollResult(
                        sessionId = sessionId,
                        fileName = "scroll_preview",
                        bitmap = finalized.bitmap,
                    )
                } else {
                    sessionFileStore.saveScrollResult(
                        sessionId = sessionId,
                        bitmap = finalized.bitmap,
                    )
                }
                ).also { persistedScrollPath = it }
            val selectedEnd = if (activeTemplate.scrollDirection.isVertical) {
                finalized.logicalHeight
            } else finalized.logicalWidth
            val result = LayeredCaptureResult(
                topFixedBitmapPath = topPath,
                fixedLayers = orderedFixedLayers,
                scrollLongBitmapPath = scrollPath,
                thumbnailBitmapPath = thumbnailPath,
                scrollChunks = finalized.chunks,
                scrollContentWidth = finalized.logicalWidth,
                scrollContentHeight = finalized.logicalHeight,
                bottomFixedBitmapPath = bottomPath,
                selectedScrollStart = 0,
                selectedScrollEnd = selectedEnd,
                seamPositions = seamPositions.sorted(),
                sourceFrames = acceptedFrames,
                id = sessionId,
                sessionId = sessionId,
                appName = capturedAppName,
                capturedPackageName = capturedPackageName,
                templateSnapshot = activeTemplate,
                lowConfidenceSeams = lowConfidenceSeams.sorted(),
                outputFormat = captureConfig.outputFormat,
                jpegQuality = captureConfig.jpegQuality,
            )
            val completion = CaptureCompletion(
                sessionId = sessionId,
                frameCount = acceptedFrames.size,
                stitchedWidth = finalized.logicalWidth,
                stitchedHeight = finalized.logicalHeight,
                scrollDirection = captureGapPreviewOrientation.scrollDirection,
                autoOpenResult = captureConfig.autoOpenResult,
            )
            resultRepository.save(result)
            persistedCompletion = completion
            resultPersisted = true
            return completion
        }

        try {
            val firstFull = captureFreshFrame(backend, spec)
            try {
                displayProcessingGap(
                    message = serviceText(R.string.capture_service_processing_frame, 1),
                    frameCount = 0,
                    processingFrameNumber = 1,
                    stitchedWidth = activeTemplate.stitchRegion.width,
                    stitchedHeight = activeTemplate.stitchRegion.height,
                    previewBitmap = null,
                )
                if (activeTemplate.id != 0L && !templateReferenceStore.exists(activeTemplate.id)) {
                    runCatching {
                        val reference = firstFull.copy(Bitmap.Config.ARGB_8888, true)
                        try {
                            templateReferenceStore.save(activeTemplate.id, reference)
                        } finally {
                            if (!reference.isRecycled) reference.recycle()
                        }
                    }
                        .onFailure { Log.w(TAG, "Unable to save template reference image", it) }
                }
                runCatching {
                    val thumbnailSource = cropFrame(firstFull, activeTemplate.captureRegion)
                    val thumbnail = try {
                        createResultThumbnail(thumbnailSource)
                    } finally {
                        thumbnailSource.recycle()
                    }
                    try {
                        thumbnailPath = sessionFileStore.saveBitmap(
                            sessionId = sessionId,
                            fileName = "result_thumbnail.jpg",
                            bitmap = thumbnail,
                            format = Bitmap.CompressFormat.JPEG,
                            quality = 94,
                        )
                    } finally {
                        thumbnail.recycle()
                    }
                }.onFailure { Log.w(TAG, "Unable to save result thumbnail", it) }
                val (firstScroll, firstTerminalFixed) = cropCandidateLayers(firstFull)
                acceptedTerminalFixed.commit(firstTerminalFixed)
                previous = firstScroll
                stitchAccumulator.start(firstScroll)
                activeTemplate.fixedLayers.forEach { layer ->
                    val region = activeTemplate.fixedBandRegion(layer.side) ?: return@forEach
                    val fixed = cropFrame(firstFull, region)
                    try {
                        val path = sessionFileStore.saveBitmap(
                            sessionId,
                            fixedLayerFileName(layer.side),
                            fixed,
                        )
                        capturedFixedLayers[layer.side] = capturedFixedLayerMetadata(
                            layer = layer,
                            band = region,
                            bitmapPath = path,
                            bitmapWidth = fixed.width,
                            bitmapHeight = fixed.height,
                        )
                    } finally {
                        fixed.recycle()
                    }
                }
                acceptedFrames += CapturedFrame(
                    index = 0,
                    confidence = 1f,
                    accepted = true,
                    width = firstScroll.width,
                    height = firstScroll.height,
                )
            } finally {
                firstFull.recycle()
            }
            preview = createCaptureGapPreview(stitchAccumulator.activeBitmap())
            publishProgress(
                serviceText(R.string.capture_service_first_frame_saved),
                acceptedFrames.size,
                stitchAccumulator.activeBitmap(),
                1f,
                logicalWidth = stitchAccumulator.logicalWidth,
                logicalHeight = stitchAccumulator.logicalHeight,
            )
            refreshProcessingGap(serviceText(R.string.capture_service_first_frame_saved))
            waitForMinimumProcessingGapVisibility()

            // No session frame-count, confidence or total-pixel cap. Each accepted frame only adds
            // its non-overlapping strip to disk; no growing long Bitmap exists during capture.
            captureFrames@ while (!stopRequested.get()) {
                if (stopRequested.get()) break
                val hadPendingTerminalRemainder = pendingTerminalRemainder != null
                val cycleStartedAtMs = SystemClock.elapsedRealtime()
                try {
                    clearProcessingGap()
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to remove the capture gap; saving confirmed content", error)
                    stopRequested.set(true)
                    transition(
                        CaptureState.CAPTURE_STOP_REQUESTED,
                        serviceText(R.string.capture_service_overlay_unavailable_saving),
                    )
                    break
                }
                if (stopRequested.get()) break
                // The removal callback marks the first point at which a clean frame is possible;
                // do not count WindowManager removal latency as hidden-screen stabilization time.
                val overlayHiddenAtMs = SystemClock.elapsedRealtime()
                val currentSpec = try {
                    currentDisplaySpec()
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to read display geometry; saving confirmed content", error)
                    stopRequested.set(true)
                    runCatching {
                        transition(
                            CaptureState.CAPTURE_STOP_REQUESTED,
                            serviceText(R.string.capture_service_display_changed_saving),
                        )
                    }.onFailure { transitionError ->
                        Log.w(TAG, "Unable to publish display-loss stop state", transitionError)
                    }
                    break
                }
                if (currentSpec.width != spec.width || currentSpec.height != spec.height ||
                    currentSpec.rotation != spec.rotation
                ) {
                    stopRequested.set(true)
                    transition(
                        CaptureState.CAPTURE_STOP_REQUESTED,
                        serviceText(R.string.capture_service_display_changed_saving),
                    )
                    break
                }
                val attemptedFrameNumber = acceptedFrames.size + 1
                val gestureAttempt = gestureAttempts[gestureRetryState.attemptIndex]
                val gestureStartedAtMs = SystemClock.elapsedRealtime()
                val dispatchedGestureDistance: Int
                val gestureCompletionConfirmed: Boolean
                val gestureFinishedAtMs: Long
                if (shouldDispatchCaptureGesture(hadPendingTerminalRemainder)) {
                    // Window removal is asynchronous below WindowManager. Give InputDispatcher a
                    // brief handoff before ACTION_DOWN; this time is included in the existing
                    // overlay-hidden stability interval.
                    sleepInterruptibly(CAPTURE_INPUT_HANDOFF_DELAY_MS)
                    if (stopRequested.get()) break
                    transition(
                        CaptureState.CAPTURE_SCROLL,
                        serviceText(
                            R.string.capture_service_performing_automatic_swipe,
                            acceptedFrames.size,
                        ),
                    )
                    if (shouldAbortCaptureAction()) break
                    val dispatch = dispatchCaptureGesture(
                        template = activeTemplate,
                        distance = gestureAttempt.distancePx,
                        crossAxisOffsetFraction = gestureAttempt.crossAxisOffsetFraction,
                        movementAxisRegion = navigationContentBounds,
                        crossAxisRegion = navigationContentBounds,
                        allowUnknownShizukuOutcome = true,
                    )
                    if (dispatch == null) {
                        stopRequested.set(true)
                        transition(
                            CaptureState.CAPTURE_STOP_REQUESTED,
                            serviceText(R.string.capture_service_swipe_unresponsive_saving),
                        )
                        break
                    }
                    dispatchedGestureDistance = dispatch.distancePx
                    gestureCompletionConfirmed = dispatch.completionConfirmed
                    gestureFinishedAtMs = SystemClock.elapsedRealtime()
                    expectedShiftSinceKeyframe = Math.addExact(
                        expectedShiftSinceKeyframe,
                        dispatchedGestureDistance,
                    )
                    if (stopRequested.get()) break

                    transition(
                        CaptureState.CAPTURE_WAIT_STABLE,
                        serviceText(R.string.capture_service_waiting_for_stable_frame),
                    )
                    sleepInterruptibly(
                        postGestureWaitDurationMs(
                            enabled = activeTemplate.postGestureWaitEnabled,
                            configuredWaitMs = activeTemplate.postGestureWaitMs,
                        ),
                    )
                    if (stopRequested.get()) break
                    sleepInterruptibly(
                        remainingOverlayStableDelayMs(
                            configuredWaitAfterOverlayHiddenMs = activeTemplate.waitAfterOverlayHiddenMs,
                            elapsedSinceOverlayHiddenMs = SystemClock.elapsedRealtime() - overlayHiddenAtMs,
                        )
                    )
                } else {
                    // Confirm a staged tail by re-sampling the exact same position. A second swipe
                    // would compare two different positions and can turn an uncertain remainder
                    // into a false match or skip content entirely.
                    transition(
                        CaptureState.CAPTURE_SCROLL,
                        serviceText(R.string.capture_service_confirming_scroll_end),
                    )
                    transition(
                        CaptureState.CAPTURE_WAIT_STABLE,
                        serviceText(R.string.capture_service_confirming_scroll_end),
                    )
                    dispatchedGestureDistance = 0
                    gestureCompletionConfirmed = false
                    gestureFinishedAtMs = gestureStartedAtMs
                    sleepInterruptibly(DYNAMIC_MATCH_RETRY_DELAY_MS)
                }
                if (shouldAbortCaptureAction()) break
                transition(
                    CaptureState.CAPTURE_FRAME,
                    serviceText(R.string.capture_service_getting_clean_frame),
                )
                val captureStartedAtMs = SystemClock.elapsedRealtime()
                val full = try {
                    captureFreshFrame(backend, spec)
                } catch (interrupted: InterruptedException) {
                    throw interrupted
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to capture the next frame; saving confirmed content", error)
                    stopRequested.set(true)
                    transition(
                        CaptureState.CAPTURE_STOP_REQUESTED,
                        serviceText(R.string.capture_service_frame_unavailable_saving),
                    )
                    break
                }
                val capturedAtMs = SystemClock.elapsedRealtime()
                val firstCandidate = try {
                    displayProcessingGap(
                        message = serviceText(
                            R.string.capture_service_processing_frame,
                            attemptedFrameNumber,
                        ),
                        processingFrameNumber = attemptedFrameNumber,
                    )
                    cropCandidateLayers(full)
                } finally {
                    full.recycle()
                }
                var current = firstCandidate.first
                var terminalCandidate = firstCandidate.second
                var ownsCurrent = true
                try {

                transition(
                    CaptureState.CAPTURE_PROCESS,
                    serviceText(R.string.capture_service_matching_overlap),
                )
                val last = previous
                    ?: error(serviceText(R.string.capture_service_missing_previous_keyframe))
                var pendingConfirmationObserved = false
                var pendingConfirmationFrameCount = 0
                var pendingInvalidatedThisCycle = false
                var failedPendingReplacementAttempts = 0
                val pendingRemainder = pendingTerminalRemainder
                var match = if (pendingRemainder != null) {
                    val terminalProbe = stitchEngine.match(
                        pendingRemainder.scroll,
                        current,
                        activeTemplate.scrollDirection,
                        allowTinyShift = true,
                        allowStructuralStationary = true,
                    )
                    if (isDuplicateMatch(terminalProbe)) {
                        val confirmationMatch = stitchEngine.match(
                            last,
                            current,
                            activeTemplate.scrollDirection,
                            // Re-evaluate the staged seam without feeding its own displacement
                            // back as a prior. The pending/current duplicate proves the page is
                            // stable, but it must not let a periodic or animated false tiny match
                            // confirm itself.
                            expectedShiftPx = null,
                            allowTinyShift = true,
                        )
                        if (terminalRemainderMatchesAgree(
                                pending = pendingRemainder.match,
                                confirmation = confirmationMatch,
                                direction = activeTemplate.scrollDirection,
                            )
                        ) {
                            pendingRemainder.duplicateConfirmations++
                            pendingConfirmationObserved = true
                            pendingConfirmationFrameCount =
                                pendingRemainder.duplicateConfirmations + 1
                            if (hasConfirmedTerminalRemainder(
                                    pendingRemainder.duplicateConfirmations,
                                )
                            ) {
                                current.recycle()
                                acceptedTerminalFixed.discard(terminalCandidate)
                                current = pendingRemainder.scroll
                                terminalCandidate = pendingRemainder.terminalFixed
                                pendingTerminalRemainder = null
                                pendingRemainder.match.copy(
                                    accepted = true,
                                    reason = serviceText(
                                        R.string.capture_service_short_scroll_confirming_end,
                                    ),
                                    rejectionReason = null,
                                )
                            } else {
                                terminalProbe.copy(
                                    reason = serviceText(
                                        R.string.capture_service_duplicate_confirming_end,
                                    ),
                                )
                            }
                        } else {
                            pendingInvalidatedThisCycle = true
                            failedPendingReplacementAttempts = pendingRemainder.replacementAttempts
                            pendingRemainder.close()
                            pendingTerminalRemainder = null
                            confirmationMatch
                        }
                    } else {
                        pendingInvalidatedThisCycle = true
                        failedPendingReplacementAttempts = pendingRemainder.replacementAttempts
                        pendingRemainder.close()
                        pendingTerminalRemainder = null
                        stitchEngine.match(
                            last,
                            current,
                            activeTemplate.scrollDirection,
                            expectedShiftPx = expectedShiftSinceKeyframe,
                            allowStructuralStationary = true,
                        )
                    }
                } else {
                    stitchEngine.match(
                        last,
                        current,
                        activeTemplate.scrollDirection,
                        expectedShiftPx = expectedShiftSinceKeyframe,
                        allowStructuralStationary = true,
                    )
                }
                var retryAttempt = 0
                while (!match.accepted &&
                    !isDuplicateMatch(match) &&
                    !stopRequested.get() &&
                    retryAttempt < DYNAMIC_MATCH_RETRY_COUNT
                ) {
                    // Do not scroll farther away from the last keyframe after an uncertain sample.
                    // Re-sample the same position and keep whichever structural match is stronger.
                    val retryDelayMs = if (processingGapVisible) {
                        maxOf(
                            DYNAMIC_MATCH_RETRY_DELAY_MS,
                            activeTemplate.waitAfterOverlayHiddenMs,
                        )
                    } else {
                        DYNAMIC_MATCH_RETRY_DELAY_MS
                    }
                    try {
                        clearProcessingGap(retryDelayMs)
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (error: Exception) {
                        Log.w(
                            TAG,
                            "Unable to remove the capture gap before retry; saving confirmed content",
                            error,
                        )
                        stopRequested.set(true)
                        transition(
                            CaptureState.CAPTURE_STOP_REQUESTED,
                            serviceText(R.string.capture_service_overlay_unavailable_saving),
                        )
                        break@captureFrames
                    }
                    if (shouldAbortCaptureAction()) break
                    val retryFull = try {
                        captureFreshFrame(backend, spec)
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (error: Exception) {
                        Log.w(TAG, "Unable to capture a retry frame; saving confirmed content", error)
                        stopRequested.set(true)
                        transition(
                            CaptureState.CAPTURE_STOP_REQUESTED,
                            serviceText(R.string.capture_service_frame_unavailable_saving),
                        )
                        break@captureFrames
                    }
                    val retryCandidate = try {
                        displayProcessingGap(
                            message = serviceText(
                                R.string.capture_service_reprocessing_frame,
                                attemptedFrameNumber,
                            ),
                            processingFrameNumber = attemptedFrameNumber,
                        )
                        cropCandidateLayers(retryFull)
                    } finally {
                        retryFull.recycle()
                    }
                    val retryCurrent = retryCandidate.first
                    val retryMatch = try {
                        stitchEngine.match(
                            last,
                            retryCurrent,
                            activeTemplate.scrollDirection,
                            expectedShiftPx = expectedShiftSinceKeyframe,
                            allowStructuralStationary = true,
                        )
                    } catch (error: Throwable) {
                        retryCurrent.recycle()
                        acceptedTerminalFixed.discard(retryCandidate.second)
                        throw error
                    }
                    if (retryMatch.accepted ||
                        isDuplicateMatch(retryMatch) ||
                        retryMatch.confidence > match.confidence
                    ) {
                        current.recycle()
                        acceptedTerminalFixed.discard(terminalCandidate)
                        current = retryCurrent
                        terminalCandidate = retryCandidate.second
                        match = retryMatch
                    } else {
                        retryCurrent.recycle()
                        acceptedTerminalFixed.discard(retryCandidate.second)
                    }
                    retryAttempt++
                }
                if (shouldConfirmAcceptedMatchStability(match) && !stopRequested.get()) {
                    // Stretch overscroll can leave the first post-release frame geometrically
                    // distorted even after the configured wait. Verify a low-confidence accepted
                    // candidate at the same page position before making it the next keyframe.
                    val stabilityDelayMs = maxOf(
                        ACCEPTED_MATCH_STABILITY_CONFIRMATION_DELAY_MS,
                        activeTemplate.waitAfterOverlayHiddenMs,
                    )
                    try {
                        clearProcessingGap(stabilityDelayMs)
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (error: Exception) {
                        Log.w(
                            TAG,
                            "Unable to remove the capture gap before stability confirmation",
                            error,
                        )
                        stopRequested.set(true)
                        transition(
                            CaptureState.CAPTURE_STOP_REQUESTED,
                            serviceText(R.string.capture_service_overlay_unavailable_saving),
                        )
                        break@captureFrames
                    }
                    if (shouldAbortCaptureAction()) break
                    val stabilityFull = try {
                        captureFreshFrame(backend, spec)
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (error: Exception) {
                        Log.w(TAG, "Unable to capture a stability confirmation frame", error)
                        stopRequested.set(true)
                        transition(
                            CaptureState.CAPTURE_STOP_REQUESTED,
                            serviceText(R.string.capture_service_frame_unavailable_saving),
                        )
                        break@captureFrames
                    }
                    val stabilityCandidate = try {
                        displayProcessingGap(
                            message = serviceText(
                                R.string.capture_service_reprocessing_frame,
                                attemptedFrameNumber,
                            ),
                            processingFrameNumber = attemptedFrameNumber,
                        )
                        cropCandidateLayers(stabilityFull)
                    } finally {
                        stabilityFull.recycle()
                    }
                    val stabilityCurrent = stabilityCandidate.first
                    val samePositionMatch = try {
                        stitchEngine.match(
                            current,
                            stabilityCurrent,
                            activeTemplate.scrollDirection,
                            expectedShiftPx = null,
                            allowTinyShift = true,
                            allowStructuralStationary = true,
                        )
                    } catch (error: Throwable) {
                        stabilityCurrent.recycle()
                        acceptedTerminalFixed.discard(stabilityCandidate.second)
                        throw error
                    }
                    if (samePositionMatch.nearlyIdentical) {
                        stabilityCurrent.recycle()
                        acceptedTerminalFixed.discard(stabilityCandidate.second)
                    } else {
                        val stabilizedMatch = try {
                            stitchEngine.match(
                                last,
                                stabilityCurrent,
                                activeTemplate.scrollDirection,
                                expectedShiftPx = expectedShiftSinceKeyframe,
                                allowStructuralStationary = true,
                            )
                        } catch (error: Throwable) {
                            stabilityCurrent.recycle()
                            acceptedTerminalFixed.discard(stabilityCandidate.second)
                            throw error
                        }
                        current.recycle()
                        acceptedTerminalFixed.discard(terminalCandidate)
                        current = stabilityCurrent
                        terminalCandidate = stabilityCandidate.second
                        match = stabilizedMatch
                    }
                }
                match = recoverReliableUnexpectedShiftMatch(
                    match = match,
                    direction = activeTemplate.scrollDirection,
                ) { observedShift ->
                    stitchEngine.match(
                        last,
                        current,
                        activeTemplate.scrollDirection,
                        expectedShiftPx = observedShift,
                        allowStructuralStationary = true,
                    )
                }
                val needsTinyTerminalProbe = (
                    !match.accepted &&
                        !isDuplicateMatch(match) &&
                        (match.rejectionReason == StitchRejectionReason.LOW_CONFIDENCE ||
                            match.rejectionReason == StitchRejectionReason.OVERLAP_TOO_HIGH)
                    ) || shouldProbeAcceptedMatchForTinyTerminalShift(match)
                if (needsTinyTerminalProbe) {
                    val baselineMatch = match
                    val frameLength = if (activeTemplate.scrollDirection.isVertical) {
                        current.height
                    } else current.width
                    val terminalProbeMaxShift = terminalProbeMaximumShift(frameLength)
                    val tinyTerminalMatch = stitchEngine.match(
                        last,
                        current,
                        activeTemplate.scrollDirection,
                        expectedShiftPx = null,
                        allowTinyShift = true,
                        allowStructuralStationary = true,
                        maximumShiftPx = terminalProbeMaxShift,
                    )
                    if (shouldPreferTinyTerminalMatch(
                            tinyTerminalMatch,
                            activeTemplate.scrollDirection,
                            frameLength,
                            baseline = baselineMatch,
                        )
                    ) {
                        match = if (tinyTerminalMatch.accepted &&
                            isTinyTerminalShift(
                                tinyTerminalMatch,
                                activeTemplate.scrollDirection,
                                frameLength,
                            )
                        ) {
                            tinyTerminalMatch.copy(
                                accepted = false,
                                reason = serviceText(
                                    R.string.capture_service_short_scroll_confirming_end,
                                ),
                                rejectionReason = StitchRejectionReason.LOW_CONFIDENCE,
                            )
                        } else tinyTerminalMatch
                    }
                }
                val mainShift = abs(
                    if (activeTemplate.scrollDirection.isVertical) match.dy else match.dx
                )
                val dynamicMatchCanBeReviewed = canAcceptReviewableLowConfidenceMatch(
                    match = match,
                    direction = activeTemplate.scrollDirection,
                )
                val reliableSmallStep =
                    match.rejectionReason == StitchRejectionReason.OVERLAP_TOO_HIGH &&
                        match.confidence >= SMALL_STEP_SOFT_ACCEPT_CONFIDENCE &&
                        !isDuplicateMatch(match)
                if (!match.accepted &&
                    mainShift > END_SHIFT_THRESHOLD_PX &&
                    (dynamicMatchCanBeReviewed || reliableSmallStep)
                ) {
                    // Dynamic games may have no completely static card interior. A geometrically
                    // plausible structural match is retained as a reviewable low-confidence seam
                    // instead of deterministically discarding every frame after the first.
                    match = match.copy(
                        accepted = true,
                        reason = serviceText(
                            R.string.capture_service_dynamic_frame_structural_stitch,
                        ),
                        rejectionReason = null,
                    )
                }
                Log.d(
                    TAG,
                    "stitchMatch direction=${activeTemplate.scrollDirection} " +
                        "stitch=${activeTemplate.stitchRegion} gesture=${activeTemplate.gestureRegion} " +
                        "requested=$expectedShiftSinceKeyframe gestureDistance=$dispatchedGestureDistance " +
                        "crossAxisLane=${gestureAttempt.crossAxisOffsetFraction} " +
                        "dx=${match.dx} dy=${match.dy} overlap=${match.overlapRatio} " +
                        "confidence=${match.confidence} accepted=${match.accepted} " +
                        "reason=${match.rejectionReason} retries=$retryAttempt",
                )
                // DUPLICATE may be established either by raw pixels or by stable structural
                // blocks when animated regions continue changing at the end of a list.
                val duplicateFrame = isDuplicateMatch(match)
                val frameAccepted = match.accepted
                if (!frameAccepted) {
                    // Input injection does not prove that the target consumed the swipe. Keep a
                    // trustworthy observed displacement from the accepted keyframe, otherwise
                    // remove the latest unconfirmed request before trying a shorter/alternate path.
                    expectedShiftSinceKeyframe = reconcileRejectedFrameExpectedShift(
                        expectedShiftIncludingGesturePx = expectedShiftSinceKeyframe,
                        dispatchedGestureDistancePx = dispatchedGestureDistance,
                        match = match,
                        direction = activeTemplate.scrollDirection,
                        duplicateComparedToPendingFrame = pendingConfirmationObserved,
                    )
                }
                identicalFrameEvidence = nextTerminalNoMotionEvidence(
                    currentEvidence = identicalFrameEvidence,
                    dispatchedDistancePx = dispatchedGestureDistance,
                    gestureCompletionConfirmed = gestureCompletionConfirmed,
                    frameAccepted = frameAccepted,
                    duplicateFrame = duplicateFrame,
                    pendingConfirmationObserved = pendingConfirmationObserved,
                )
                observedDuplicateGestureEvidence = nextObservedDuplicateGestureEvidence(
                    currentEvidence = observedDuplicateGestureEvidence,
                    dispatchedDistancePx = dispatchedGestureDistance,
                    frameAccepted = frameAccepted,
                    duplicateFrame = duplicateFrame,
                    pendingConfirmationObserved = pendingConfirmationObserved,
                )

                val replaceFailedTerminalRemainder = shouldReplaceFailedTerminalRemainder(
                    pendingInvalidatedThisCycle = pendingInvalidatedThisCycle,
                    candidateMatch = match,
                    direction = activeTemplate.scrollDirection,
                    dispatchedGestureDistancePx = dispatchedGestureDistance,
                    replacementAttempts = failedPendingReplacementAttempts,
                )
                val stageTerminalRemainder = shouldStageTerminalRemainder(
                    hadPendingTerminalRemainder = hadPendingTerminalRemainder,
                    frameAccepted = frameAccepted,
                    candidateCanBeStaged = canStageTerminalRemainder(
                        match,
                        activeTemplate.scrollDirection,
                    ),
                ) || replaceFailedTerminalRemainder
                val frameMessage = if (stageTerminalRemainder) {
                    serviceText(R.string.capture_service_confirming_scroll_end)
                } else localizedStitchRejectionMessage(match.rejectionReason, match.reason)
                gestureRetryState = nextCaptureGestureRetryState(
                    current = gestureRetryState,
                    attemptCount = gestureAttempts.size,
                    frameAccepted = frameAccepted,
                    confirmedNoMotion = isLikelyUnconsumedGesture(match) &&
                        dispatchedGestureDistance > 0,
                )

                if (frameAccepted) {
                    val oldLength = if (activeTemplate.scrollDirection.isVertical) {
                        stitchAccumulator.logicalHeight
                    } else stitchAccumulator.logicalWidth
                    val shift = abs(
                        if (activeTemplate.scrollDirection.isVertical) match.dy else match.dx
                    )
                    stitchAccumulator.append(
                        current,
                        match,
                        stitchEngine,
                    )
                    acceptedTerminalFixed.commit(terminalCandidate)
                    terminalCandidate = null
                    previous = current
                    ownsCurrent = false
                    last.recycle()
                    expectedShiftSinceKeyframe = 0
                    val saved = CapturedFrame(
                        index = acceptedFrames.size,
                        offsetX = match.dx,
                        offsetY = match.dy,
                        confidence = match.confidence,
                        accepted = true,
                        matchReason = match.reason,
                        width = current.width,
                        height = current.height,
                    )
                    acceptedFrames += saved
                    val seam = if (activeTemplate.scrollDirection.isForward) oldLength else shift
                    if (!activeTemplate.scrollDirection.isForward) {
                        seamPositions.indices.forEach { index -> seamPositions[index] += shift }
                        lowConfidenceSeams.indices.forEach { index -> lowConfidenceSeams[index] += shift }
                    }
                    seamPositions += seam
                    if (match.confidence < LOW_CONFIDENCE_THRESHOLD) lowConfidenceSeams += seam
                    confidenceTotal += match.confidence
                    matchCount++
                } else if (stageTerminalRemainder) {
                    pendingTerminalRemainder?.close()
                    pendingTerminalRemainder = PendingTerminalRemainder(
                        scroll = current,
                        terminalFixed = terminalCandidate,
                        match = match,
                        replacementAttempts = if (replaceFailedTerminalRemainder) {
                            failedPendingReplacementAttempts + 1
                        } else {
                            0
                        },
                    )
                    terminalCandidate = null
                    ownsCurrent = false
                } else {
                    current.recycle()
                    ownsCurrent = false
                }
                val averageConfidence = confidenceTotal / matchCount
                val image = stitchAccumulator.activeBitmap()
                val previousPreview = preview
                preview = createCaptureGapPreview(image)
                if (previousPreview !== displayedPreview) {
                    previousPreview?.takeUnless { it.isRecycled }?.recycle()
                }
                val progressMessage = when {
                    pendingConfirmationFrameCount > 0 ->
                        serviceText(
                            R.string.capture_service_checking_scroll_end_progress,
                            pendingConfirmationFrameCount.coerceAtMost(
                                REQUIRED_TERMINAL_DUPLICATE_CONFIRMATIONS + 1,
                            ),
                            REQUIRED_TERMINAL_DUPLICATE_CONFIRMATIONS + 1,
                        )
                    duplicateFrame ->
                        serviceText(
                            R.string.capture_service_checking_scroll_end_progress,
                            (maxOf(
                                identicalFrameEvidence,
                                observedDuplicateGestureEvidence,
                            ) + 1).coerceIn(2, gestureAttempts.size.coerceAtLeast(2)),
                            gestureAttempts.size.coerceAtLeast(2),
                        )
                    frameMessage != null -> frameMessage
                    frameAccepted -> serviceText(
                        R.string.capture_service_accepted_frame,
                        acceptedFrames.size,
                    )
                    else -> serviceText(R.string.capture_service_low_confidence_skipped)
                }
                publishProgress(
                    progressMessage,
                    acceptedFrames.size,
                    image,
                    averageConfidence,
                    logicalWidth = stitchAccumulator.logicalWidth,
                    logicalHeight = stitchAccumulator.logicalHeight,
                )
                refreshProcessingGap(progressMessage)
                val processedAtMs = SystemClock.elapsedRealtime()
                Log.d(
                    TAG,
                    "captureCycleTiming frame=${acceptedFrames.size} " +
                        "gestureMs=${gestureFinishedAtMs - gestureStartedAtMs} " +
                        "settleAndSafetyMs=${captureStartedAtMs - gestureFinishedAtMs} " +
                        "backendMs=${capturedAtMs - captureStartedAtMs} " +
                        "processMs=${processedAtMs - capturedAtMs} " +
                        "totalMs=${processedAtMs - cycleStartedAtMs}",
                )
                // Stable/capture latency happened before this overlay existed. Only matching,
                // preview generation and this final remainder count toward the user's configured
                // minimum banner/preview visibility.
                waitForMinimumProcessingGapVisibility()

                // A stable tiny remainder only proves that it is safe to append. Exhaust every
                // real gesture path from that keyframe before stopping. Completed gestures remain
                // the primary evidence; repeated duplicate observations also bound UNKNOWN
                // Shizuku outcomes so they cannot loop forever.
                if (hasObservedNoMotionAcrossAllGestureAttempts(
                        evidence = identicalFrameEvidence,
                        attemptCount = gestureAttempts.size,
                    ) || hasObservedNoMotionAcrossAllGestureAttempts(
                        evidence = observedDuplicateGestureEvidence,
                        attemptCount = gestureAttempts.size,
                    )
                ) {
                    stopRequested.set(true)
                    transition(
                        CaptureState.CAPTURE_STOP_REQUESTED,
                        serviceText(R.string.capture_service_duplicate_reached_end),
                    )
                } else if (!frameAccepted &&
                    !duplicateFrame &&
                    !stageTerminalRemainder &&
                    retryAttempt >= DYNAMIC_MATCH_RETRY_COUNT &&
                    !shouldTryAnotherGesturePathForQuietFrame(
                        match = match,
                        retryState = gestureRetryState,
                        attemptCount = gestureAttempts.size,
                    ) &&
                    !stopRequested.get()
                ) {
                    // A visibly changed frame may already be far from the accepted keyframe, so
                    // another swipe could remove all overlap. Quiet frames instead exhaust the
                    // bounded alternate gesture paths before reaching this failure branch.
                    stopRequested.set(true)
                    transition(
                        CaptureState.CAPTURE_STOP_REQUESTED,
                        serviceText(R.string.capture_service_no_reliable_connection),
                    )
                }
                } finally {
                    acceptedTerminalFixed.discard(terminalCandidate)
                    if (ownsCurrent && !current.isRecycled) current.recycle()
                }
            }

            val omittedUnconfirmedTail = pendingTerminalRemainder != null
            runCatching {
                transition(
                    CaptureState.CAPTURE_FINISHING,
                    if (omittedUnconfirmedTail) {
                        serviceText(R.string.capture_service_unconfirmed_tail_omitted)
                    } else {
                        serviceText(R.string.capture_service_saving_layered_screenshot)
                    },
                )
            }.onFailure { Log.w(TAG, "Unable to publish capture finishing state", it) }
            runCatching { clearProcessingGap() }
                .onFailure { Log.w(TAG, "Unable to remove the capture gap before saving", it) }
            runCatching {
                displayProcessingGap(
                    message = if (omittedUnconfirmedTail) {
                        serviceText(R.string.capture_service_saving_confirmed_content)
                    } else {
                        serviceText(R.string.capture_service_saving_frame, acceptedFrames.size)
                    },
                    allowWhenStopping = true,
                )
            }.onFailure { Log.w(TAG, "Unable to show capture saving state", it) }
            val completion = persistConfirmedCapture()
            runCatching {
                transition(
                    CaptureState.RESULT_EDIT,
                    if (omittedUnconfirmedTail) {
                        serviceText(R.string.capture_service_complete_tail_omitted)
                    } else {
                        serviceText(
                            R.string.capture_service_complete_with_frames,
                            acceptedFrames.size,
                        )
                    },
                    frameCount = acceptedFrames.size,
                    width = completion.stitchedWidth,
                    height = completion.stitchedHeight,
                    confidence = confidenceTotal / matchCount,
                    resultSessionId = sessionId,
                )
            }.onFailure { Log.w(TAG, "Unable to publish persisted result state", it) }
            runCatching {
                transition(
                    CaptureState.READY_TO_CAPTURE,
                    if (omittedUnconfirmedTail) {
                        serviceText(R.string.capture_service_saved_tail_omitted)
                    } else {
                        serviceText(R.string.capture_service_saved_ready_again)
                    },
                    frameCount = acceptedFrames.size,
                    width = completion.stitchedWidth,
                    height = completion.stitchedHeight,
                    confidence = confidenceTotal / matchCount,
                    resultSessionId = sessionId,
                )
            }.onFailure { Log.w(TAG, "Unable to publish persisted ready state", it) }
            return completion
        } catch (error: Throwable) {
            persistedCompletion?.takeIf { resultPersisted }?.let { completion ->
                Log.w(TAG, "Capture result was saved before a non-critical handoff failure", error)
                return completion
            }
            if (acceptedFrames.isNotEmpty() &&
                error is Exception &&
                error !is InterruptedException
            ) {
                stopRequested.set(true)
                Log.e(TAG, "Capture failed after a confirmed frame; attempting to save it", error)
                val recovered = runCatching { persistConfirmedCapture() }
                recovered.getOrNull()?.let { completion ->
                    runCatching {
                        transition(
                            CaptureState.CAPTURE_FINISHING,
                            serviceText(R.string.capture_service_saving_confirmed_content),
                        )
                    }.onFailure { stateError ->
                        Log.w(TAG, "Unable to publish recovered capture finishing state", stateError)
                    }
                    runCatching {
                        transition(
                            CaptureState.RESULT_EDIT,
                            serviceText(
                                R.string.capture_service_complete_with_frames,
                                acceptedFrames.size,
                            ),
                            frameCount = acceptedFrames.size,
                            width = completion.stitchedWidth,
                            height = completion.stitchedHeight,
                            confidence = confidenceTotal / matchCount,
                            resultSessionId = sessionId,
                        )
                    }.onFailure { stateError ->
                        Log.w(TAG, "Unable to publish recovered result state", stateError)
                    }
                    runCatching {
                        transition(
                            CaptureState.READY_TO_CAPTURE,
                            serviceText(R.string.capture_service_saved_ready_again),
                            frameCount = acceptedFrames.size,
                            width = completion.stitchedWidth,
                            height = completion.stitchedHeight,
                            confidence = confidenceTotal / matchCount,
                            resultSessionId = sessionId,
                        )
                    }.onFailure { stateError ->
                        Log.w(TAG, "Unable to publish recovered ready state", stateError)
                    }
                    return completion
                }
                recovered.exceptionOrNull()?.let(error::addSuppressed)
            }
            if (!resultPersisted) {
                runCatching { sessionFileStore.releaseRetainedSession(sessionId) }
            }
            throw error
        } finally {
            runCatching {
                clearProcessingGap(
                    interruptible = false,
                    enforceMinimumVisibleDuration = false,
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to synchronously remove capture processing overlay", error)
            }
            val attachedPreview = displayedPreview.takeIf {
                captureGapPreviewMayStillBeAttached(
                    processingGapRequested = processingGapRequested,
                    processingGapVisible = processingGapVisible,
                )
            }
            displayedPreview
                ?.takeUnless { it === preview || it === attachedPreview || it.isRecycled }
                ?.recycle()
            preview
                ?.takeUnless { it === attachedPreview || it.isRecycled }
                ?.recycle()
            previous?.takeUnless { it.isRecycled }?.recycle()
            pendingTerminalRemainder?.close()
            acceptedTerminalFixed.close()
            finalizedScroll?.bitmap?.takeUnless { it.isRecycled }?.recycle()
            stitchAccumulator.close()
        }
    }

    private fun startRegionLearningJob() {
        val jobToken = beginJob() ?: return
        submitCaptureJob(
            jobToken = jobToken,
            failureMessage = serviceText(R.string.capture_service_learning_failed),
        ) recognitionJob@ {
            val samples = mutableListOf<Bitmap>()
            var stoppedConfiguration: Pair<GameCaptureTemplate, List<GameCaptureTemplate>>? = null
            var recognitionGesture: RecognitionGestureHandle? = null
            var recognitionCleanupDeadlineMs: Long? = null
            try {
                requireCaptureBackendAndGesture()
                refreshShizukuTargetApplication()
                val spec = currentDisplaySpec()
                val preparedTemplate = prepareTemplateFor(spec)
                template = preparedTemplate
                if (guardedRegionEdit == null) {
                    if (rejectPendingOverlaySave) return@recognitionJob
                    guardRegionEdit(preparedTemplate)
                }
                validateTemplate(preparedTemplate, spec)
                val backend = captureBackend ?: error(missingCapturePermissionMessage())
                transition(
                    CaptureState.REGION_LEARNING_PREPARE,
                    serviceText(R.string.capture_service_preparing_auto_learning),
                )
                val targetSamples = RECOGNITION_MAX_SAMPLE_COUNT
                val motionSampleTarget = recognitionMotionSampleTarget(targetSamples)
                hideOverlayAndWait(preparedTemplate.waitAfterOverlayHiddenMs)
                if (stopRequested.get()) {
                    transition(
                        CaptureState.REGION_CONFIG,
                        serviceText(R.string.capture_service_region_learning_stopped),
                    )
                    if (!rejectPendingOverlaySave) {
                        stoppedConfiguration = preparedTemplate to compatibleTemplatesFor(spec)
                    }
                    return@recognitionJob
                }
                if (shouldAbortCaptureAction()) {
                    if (restoreRegionConfigurationAfterSafetyStop.get() &&
                        !rejectPendingOverlaySave
                    ) {
                        stoppedConfiguration = preparedTemplate to compatibleTemplatesFor(spec)
                    }
                    return@recognitionJob
                }
                val restoredSpec = currentDisplaySpec()
                check(restoredSpec.width == spec.width && restoredSpec.height == spec.height &&
                    restoredSpec.rotation == spec.rotation
                ) { serviceText(R.string.capture_service_display_changed_after_overlay_hidden) }
                val navigationContentBounds = overlay.currentVisibleNavigationContentBounds(
                    screenWidth = spec.width,
                    screenHeight = spec.height,
                )
                val activeTemplate = navigationSafeTemplateForSession(
                    template = preparedTemplate,
                    visibleNavigationContentBounds = navigationContentBounds,
                ) ?: error(serviceText(R.string.capture_service_navigation_overlaps_learning_regions))
                validateTemplate(activeTemplate, spec)
                Log.i(
                    TAG,
                    "learningNavigationBounds content=$navigationContentBounds " +
                        "capture=${activeTemplate.captureRegion} " +
                        "gesture=${activeTemplate.gestureRegion}",
                )
                // Keep the first frame clean. The touch-to-finish overlay is attached only after
                // the injected ACTION_DOWN has already targeted the underlying application.
                samples += captureRecognitionSample(backend, spec)
                val recognitionDistance = recognitionGestureDistance(
                    configuredDistancePx = activeTemplate.gestureDistancePx,
                    maximumExecutableDistancePx = maximumScrollGestureDistance(
                        activeTemplate.stitchRegion,
                        activeTemplate.scrollDirection,
                    ),
                )
                val recognitionGesturePath = resolveTemplateScrollGesturePath(
                    template = activeTemplate,
                    requestedDistancePx = recognitionDistance,
                    movementAxisRegion = navigationContentBounds,
                    crossAxisRegion = navigationContentBounds,
                )
                transition(
                    CaptureState.REGION_LEARNING_SCROLL,
                    serviceText(R.string.capture_service_short_sampling_swipe),
                )
                if (shouldAbortCaptureAction()) {
                    if (restoreRegionConfigurationAfterSafetyStop.get() &&
                        !rejectPendingOverlaySave
                    ) {
                        stoppedConfiguration = preparedTemplate to compatibleTemplatesFor(spec)
                    }
                    return@recognitionJob
                }
                val gestureHandle = dispatchRecognitionGesture(
                    template = activeTemplate,
                    distance = recognitionDistance,
                    movementAxisRegion = navigationContentBounds,
                    crossAxisRegion = navigationContentBounds,
                ) ?: throw IllegalStateException(
                    automaticGestureFailureMessage(
                        serviceText(R.string.capture_service_learning_swipe_failed),
                    ),
                )
                recognitionGesture = gestureHandle
                val recognitionMotionDeadlineMs = recognitionDeadlineAfter(
                    startMs = SystemClock.elapsedRealtime(),
                    durationMs = DefaultCaptureConfig.REGION_RECOGNITION_DURATION_MS,
                )
                val recognitionSamplingDeadlineMs = recognitionDeadlineAfter(
                    startMs = recognitionMotionDeadlineMs,
                    durationMs = RECOGNITION_SAMPLING_OVERRUN_GRACE_MS,
                )
                recognitionCleanupDeadlineMs = recognitionDeadlineAfter(
                    startMs = recognitionSamplingDeadlineMs,
                    durationMs = RECOGNITION_CLEANUP_GRACE_MS,
                )
                val finishRecognitionRequested = AtomicBoolean(false)
                val statusDrawn = CountDownLatch(1)
                val statusBounds = AtomicReference<RectConfig?>(null)
                overlay.showRegionRecognitionCountdown(
                    durationMs = (recognitionMotionDeadlineMs - SystemClock.elapsedRealtime())
                        .coerceAtLeast(1L),
                    deadlineElapsedRealtimeMs = recognitionMotionDeadlineMs,
                    touchEnableDelayMs = if (captureBackendMode == CaptureBackendMode.SHIZUKU) {
                        0L
                    } else {
                        RECOGNITION_ACCESSIBILITY_OVERLAY_ATTACH_DELAY_MS
                    },
                    onFinishEarly = {
                        finishRecognitionRequested.set(true)
                        gestureHandle.cancel()
                    },
                    onDrawn = { bounds ->
                        statusBounds.set(bounds)
                        statusDrawn.countDown()
                    },
                )
                val recognitionStatusShown = statusDrawn.await(
                    RECOGNITION_OVERLAY_DRAW_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS,
                )
                if (!recognitionStatusShown) {
                    if (shouldAbortCaptureAction()) {
                        if (restoreRegionConfigurationAfterSafetyStop.get() &&
                            !rejectPendingOverlaySave
                        ) {
                            stoppedConfiguration = preparedTemplate to compatibleTemplatesFor(spec)
                        }
                        return@recognitionJob
                    }
                    throw IllegalStateException(
                        serviceText(R.string.capture_service_recognition_status_not_shown),
                    )
                }
                transition(
                    CaptureState.REGION_LEARNING_CAPTURE,
                    serviceText(
                        R.string.capture_service_collecting_sample,
                        samples.size + 1,
                        targetSamples,
                    ),
                )
                var nextSampleAtMs = SystemClock.elapsedRealtime()
                var motionSampleCount = 0
                while (shouldContinueRecognitionSampling(
                        sampleCount = samples.size,
                        targetSampleCount = motionSampleTarget,
                        stopRequested = stopRequested.get(),
                        finishRequested = finishRecognitionRequested.get(),
                        gestureComplete = gestureHandle.isComplete(),
                        nowMs = SystemClock.elapsedRealtime(),
                        // Do not start another frame after the visible countdown reaches zero.
                        // A frame already in flight may use the small sampling overrun below.
                        deadlineMs = recognitionMotionDeadlineMs,
                    )
                ) {
                    val remainingUntilSampleMs =
                        nextSampleAtMs - SystemClock.elapsedRealtime()
                    if (remainingUntilSampleMs > 0L) {
                        sleepInterruptibly(minOf(remainingUntilSampleMs, 80L))
                        continue
                    }
                    val nowBeforeSafetyCheck = SystemClock.elapsedRealtime()
                    val safetyCheckBudgetMs = remainingRecognitionOperationTimeoutMs(
                        nowMs = nowBeforeSafetyCheck,
                        hardDeadlineMs = recognitionSamplingDeadlineMs,
                        configuredTimeoutMs = SHIZUKU_CAPTURE_FOREGROUND_QUERY_TIMEOUT_MS,
                    )
                    if (shouldAbortCaptureAction(safetyCheckBudgetMs)) break
                    val frameTimeoutMs = remainingRecognitionOperationTimeoutMs(
                        nowMs = SystemClock.elapsedRealtime(),
                        hardDeadlineMs = recognitionSamplingDeadlineMs,
                        configuredTimeoutMs = RECOGNITION_FRAME_TIMEOUT_MS,
                    )
                    if (frameTimeoutMs <= 0L) break
                    val frameStartedDuringMotion = !gestureHandle.isComplete() &&
                        SystemClock.elapsedRealtime() < recognitionMotionDeadlineMs
                    runCatching {
                        captureRecognitionSample(backend, spec, frameTimeoutMs)
                    }.onSuccess { frame ->
                        samples += frame
                        if (frameStartedDuringMotion &&
                            !gestureHandle.isComplete() &&
                            SystemClock.elapsedRealtime() < recognitionMotionDeadlineMs
                        ) {
                            motionSampleCount += 1
                        }
                    }.onFailure { error ->
                        Log.w(
                            TAG,
                            "Skipping unavailable recognition frame ${samples.size + 1}",
                            error,
                        )
                    }
                    nextSampleAtMs += RECOGNITION_FRAME_INTERVAL_MS
                    val nowMs = SystemClock.elapsedRealtime()
                    if (nextSampleAtMs < nowMs) {
                        nextSampleAtMs = nowMs + RECOGNITION_MIN_RETRY_DELAY_MS
                    }
                }
                var gestureSettled = gestureHandle.isComplete()
                if (!gestureSettled && !finishRecognitionRequested.get() &&
                    !stopRequested.get()
                ) {
                    // If all requested frames arrived early, keep the visible countdown and give
                    // the natural five-second gesture a chance to report completion.
                    val naturalWaitMs = remainingRecognitionOperationTimeoutMs(
                        nowMs = SystemClock.elapsedRealtime(),
                        hardDeadlineMs = recognitionMotionDeadlineMs,
                        configuredTimeoutMs = Long.MAX_VALUE,
                    )
                    if (naturalWaitMs > 0L) {
                        gestureSettled = gestureHandle.awaitCompletion(naturalWaitMs)
                    }
                }
                val userFinishedRecognition = finishRecognitionRequested.get() || stopRequested.get()
                if (userFinishedRecognition) {
                    gestureHandle.cancel()
                }
                val cleanupDeadline = recognitionCleanupDeadlineMs
                    ?: recognitionDeadlineAfter(
                        SystemClock.elapsedRealtime(),
                        RECOGNITION_CLEANUP_GRACE_MS,
                    )
                if (!stopRequested.get()) {
                    // Keep the centered status visible while the gesture callback settles and the
                    // captured samples are analyzed. Its bounds remain excluded from recognition.
                    overlay.showRegionRecognitionProcessing()
                }
                if (!gestureSettled && !userFinishedRecognition) {
                    // Allow the normal completion callback a short grace after the countdown has
                    // reached its deadline. The panel now shows a non-countdown processing state.
                    val naturalCallbackBudgetMs = remainingRecognitionOperationTimeoutMs(
                        nowMs = SystemClock.elapsedRealtime(),
                        hardDeadlineMs = cleanupDeadline,
                        configuredTimeoutMs = RECOGNITION_NATURAL_CALLBACK_GRACE_MS,
                    )
                    if (naturalCallbackBudgetMs > 0L) {
                        gestureSettled = gestureHandle.awaitCompletion(naturalCallbackBudgetMs)
                    }
                }
                if (!gestureSettled) {
                    gestureHandle.cancel()
                    val callbackBudgetMs = remainingRecognitionOperationTimeoutMs(
                        nowMs = SystemClock.elapsedRealtime(),
                        hardDeadlineMs = cleanupDeadline,
                        configuredTimeoutMs = RECOGNITION_GESTURE_FINISH_GRACE_MS,
                    )
                    if (callbackBudgetMs > 0L) {
                        gestureSettled = gestureHandle.awaitCompletion(callbackBudgetMs)
                    }
                }
                gestureSettled = gestureSettled || gestureHandle.isComplete()
                if (!gestureSettled && !stopRequested.get()) {
                    throw IllegalStateException(
                        automaticGestureFailureMessage(
                            serviceText(R.string.capture_service_learning_swipe_failed),
                        ),
                    )
                }
                if (gestureSettled && !gestureHandle.succeeded() &&
                    !finishRecognitionRequested.get() && !stopRequested.get()
                ) {
                    throw IllegalStateException(
                        automaticGestureFailureMessage(
                            serviceText(R.string.capture_service_learning_swipe_failed),
                        ),
                    )
                }
                if (stopRequested.get()) {
                    transition(
                        CaptureState.REGION_CONFIG,
                        serviceText(R.string.capture_service_region_learning_stopped),
                    )
                    if (!rejectPendingOverlaySave) {
                        stoppedConfiguration = preparedTemplate to compatibleTemplatesFor(spec)
                    }
                    return@recognitionJob
                }
                check(hasSufficientRecognitionMotionSamples(motionSampleCount)) {
                    automaticGestureFailureMessage(
                        serviceText(R.string.capture_service_learning_swipe_failed),
                    )
                }
                if (gestureSettled) {
                    val cleanDelayMs = remainingRecognitionOperationTimeoutMs(
                        nowMs = SystemClock.elapsedRealtime(),
                        hardDeadlineMs = cleanupDeadline,
                        configuredTimeoutMs = RECOGNITION_FINAL_CLEAN_FRAME_DELAY_MS,
                    )
                    if (cleanDelayMs > 0L) sleepInterruptibly(cleanDelayMs)
                    val finalFrameTimeoutMs = remainingRecognitionOperationTimeoutMs(
                        nowMs = SystemClock.elapsedRealtime(),
                        hardDeadlineMs = cleanupDeadline,
                        configuredTimeoutMs = RECOGNITION_FRAME_TIMEOUT_MS,
                    )
                    if (!shouldCaptureFinalRecognitionFrame(
                            gestureSettled = gestureSettled,
                            stopRequested = stopRequested.get(),
                            sampleCount = samples.size,
                            targetSampleCount = targetSamples,
                            remainingBudgetMs = finalFrameTimeoutMs,
                        )
                    ) {
                        Log.w(TAG, "Recognition cleanup fence elapsed before final clean frame")
                    } else {
                        runCatching {
                            captureRecognitionSample(backend, spec, finalFrameTimeoutMs)
                        }.onSuccess(samples::add).onFailure { error ->
                            Log.w(TAG, "Unable to capture the final clean recognition frame", error)
                        }
                    }
                }
                check(samples.size >= 2) {
                    automaticGestureFailureMessage(
                        serviceText(R.string.capture_service_learning_swipe_failed),
                    )
                }
                val generatingSuggestion =
                    serviceText(R.string.capture_service_generating_region_suggestion)
                transition(CaptureState.REGION_LEARNING_ANALYZE, generatingSuggestion)
                // No more frames are captured after this point, so the full-screen status window
                // can stop intercepting touches while its centered progress panel stays visible.
                overlay.showRegionRecognitionProcessing(passThroughTouches = true)
                val sampleWidth = samples.first().width
                val sampleHeight = samples.first().height
                val gestureForAnalysis = activeTemplate.gestureRegion.scale(
                    sampleWidth.toFloat() / spec.width,
                    sampleHeight.toFloat() / spec.height,
                )
                val rawSuggestion = regionLearningEngine.analyze(
                    samples,
                    activeTemplate.scrollDirection,
                    gestureForAnalysis,
                    excludedRegions = listOfNotNull(
                        statusBounds.get()?.scale(
                            sampleWidth.toFloat() / spec.width,
                            sampleHeight.toFloat() / spec.height,
                        ),
                    ),
                ).scaleToDisplay(sampleWidth, sampleHeight, spec.width, spec.height)
                if (stopRequested.get()) {
                    transition(
                        CaptureState.REGION_CONFIG,
                        serviceText(R.string.capture_service_region_learning_stopped),
                    )
                    if (!rejectPendingOverlaySave) {
                        stoppedConfiguration = preparedTemplate to compatibleTemplatesFor(spec)
                    }
                    return@recognitionJob
                }
                val suggestion = rawSuggestion.copy(
                    suggestedStitchRegion = rawSuggestion.suggestedStitchRegion
                        .intersect(activeTemplate.captureRegion)
                        ?.intersect(navigationContentBounds)
                        ?: activeTemplate.stitchRegion,
                    suggestedGestureRegion = rawSuggestion.suggestedGestureRegion
                        .intersect(navigationContentBounds)
                        ?: activeTemplate.gestureRegion,
                    suggestedTopFixedRegion = rawSuggestion.suggestedTopFixedRegion
                        ?.intersect(navigationContentBounds),
                    suggestedBottomFixedRegion = rawSuggestion.suggestedBottomFixedRegion
                        ?.intersect(navigationContentBounds),
                    suggestedHudMasks = rawSuggestion.suggestedHudMasks.mapNotNull { region ->
                        region.intersect(navigationContentBounds)
                    },
                    uncertainRegions = rawSuggestion.uncertainRegions.mapNotNull { region ->
                        region.intersect(navigationContentBounds)
                    },
                )
                val runtimeSuggested = activeTemplate.copy(
                    id = 0L,
                    templateName = preparedTemplate.appName.ifBlank {
                        targetApplication.displayName.ifBlank {
                            serviceText(R.string.capture_service_current_game)
                        }
                    },
                    stitchRegion = suggestion.suggestedStitchRegion,
                    gestureRegion = suggestion.suggestedGestureRegion,
                ).withNormalizedRegions()
                val safeRuntimeSuggested = navigationSafeTemplateForSession(
                    template = runtimeSuggested,
                    visibleNavigationContentBounds = navigationContentBounds,
                ) ?: error(serviceText(R.string.capture_service_learning_result_overlaps_navigation))
                // Analysis runs on navigation-safe pixels, but the editor keeps the user's physical
                // capture selection. The baseline prevents normalization from turning the excluded
                // system strip into an authored fixed layer and lets immersive sessions restore it.
                val suggested = physicalTemplateForLearnedRegionEditor(
                    preparedTemplate = preparedTemplate,
                    navigationSafeSuggestion = safeRuntimeSuggested,
                    learnedTemplate = activeTemplate,
                    visibleNavigationContentBounds = navigationContentBounds,
                )
                val suggestedRecognitionDistance = suggested.gestureDistancePx.coerceIn(
                    1,
                    maximumScrollGestureDistance(
                        suggested.stitchRegion,
                        suggested.scrollDirection,
                    ),
                )
                val suggestedRecognitionPath = resolveTemplateScrollGesturePath(
                    template = suggested,
                    requestedDistancePx = suggestedRecognitionDistance,
                    movementAxisRegion = navigationContentBounds,
                    crossAxisRegion = navigationContentBounds,
                )
                if (suggestedRecognitionPath != recognitionGesturePath) {
                    Log.w(
                        TAG,
                        "Recognition gesture was clipped by final stitch geometry: " +
                            "captured=$recognitionGesturePath final=$suggestedRecognitionPath",
                    )
                }
                val editorSuggestion = suggestion.copy(
                    suggestedGestureRegion = suggested.gestureRegion,
                )
                template = suggested
                transition(
                    CaptureState.REGION_RESULT_CONFIRM,
                    serviceText(R.string.capture_service_learning_complete),
                    confidence = suggestion.confidence,
                )
                overlay.showRegionEditor(suggested, editorSuggestion)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!stopRequested.get()) {
                    reportError(serviceText(R.string.capture_service_learning_interrupted), interrupted)
                }
            } catch (error: Throwable) {
                reportError(
                    error.userMessage(serviceText(R.string.capture_service_learning_failed)),
                    error,
                )
            } finally {
                recognitionGesture?.let { gesture ->
                    val cleanupTimeoutMs = recognitionCleanupDeadlineMs?.let { deadline ->
                        remainingRecognitionOperationTimeoutMs(
                            nowMs = SystemClock.elapsedRealtime(),
                            hardDeadlineMs = deadline,
                            configuredTimeoutMs = RECOGNITION_GESTURE_FINISH_GRACE_MS,
                        )
                    } ?: RECOGNITION_GESTURE_FINISH_GRACE_MS
                    cancelAndClearRecognitionGesture(
                        gesture = gesture,
                        timeoutMs = cleanupTimeoutMs,
                    )
                }
                if (currentSnapshot.get().state != CaptureState.REGION_RESULT_CONFIRM) {
                    completeGuardedRegionEdit()
                }
                samples.forEach { if (!it.isRecycled) it.recycle() }
                try {
                    synchronized(jobLifecycleLock) {
                        if (!isLiveServiceInstance()) {
                            jobRunning.set(false)
                            return@synchronized
                        }
                        jobRunning.set(false)
                        if (exitRequested.get()) {
                            finishExitCaptureSession()
                        } else if (!rejectPendingOverlaySave && captureBackend != null &&
                            !captureBackendLost.get()
                        ) {
                            stoppedConfiguration?.let { (stoppedTemplate, availableTemplates) ->
                                if (currentSnapshot.get().state == CaptureState.CAPTURE_STOP_REQUESTED) {
                                    transition(
                                        CaptureState.REGION_CONFIG,
                                        currentSnapshot.get().message,
                                    )
                                }
                                overlay.showConfiguration(
                                    stoppedTemplate,
                                    availableTemplates,
                                    targetPackageName = targetApplication.packageName,
                                )
                            }
                        }
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to restore region controls after recognition", error)
                    jobRunning.set(false)
                } finally {
                    finishJobForegroundPollingIfIdle()
                    pauseCaptureBackendAsync(jobToken)
                }
            }
        }
    }

    private fun beginJob(): Long? = synchronized(jobLifecycleLock) {
        if (!jobRunning.compareAndSet(false, true)) {
            publish(
                currentSnapshot.get().copy(
                    message = serviceText(R.string.capture_service_task_already_running),
                ),
            )
            return@synchronized null
        }
        pendingCaptureCompletion.set(null)
        stopRequested.set(false)
        shizukuForegroundUnknownSinceMs.set(-1L)
        restoreRegionConfigurationAfterSafetyStop.set(false)
        startForegroundPolling()
        jobGeneration.incrementAndGet()
    }

    private fun submitCaptureJob(
        jobToken: Long,
        failureMessage: String,
        task: () -> Unit,
    ) {
        try {
            check(attachJobKeepAlive(jobToken)) {
                serviceText(R.string.capture_service_open_overlay_failed)
            }
            check(acquireCaptureInteraction(jobToken)) { failureMessage }
            acquireJobWakeLock(jobToken)
            worker.execute {
                try {
                    task()
                } finally {
                    releaseCaptureInteraction(jobToken)
                    scheduleJobKeepAliveRelease(jobToken)
                    releaseJobWakeLock(jobToken)
                }
            }
        } catch (error: Throwable) {
            failCaptureJobSubmission(jobToken, error.userMessage(failureMessage), error)
        }
    }

    private fun attachJobKeepAlive(jobToken: Long): Boolean {
        val completion = CountDownLatch(1)
        val attached = AtomicBoolean(false)
        overlay.attachCaptureKeepAlive(jobToken) { succeeded ->
            attached.set(succeeded)
            completion.countDown()
        }
        return runCatching {
            completion.await(OVERLAY_KEEPALIVE_ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                attached.get()
        }.onFailure { error ->
            Log.w(TAG, "Unable to await the capture keepalive overlay", error)
        }.getOrDefault(false)
    }

    private fun scheduleJobKeepAliveRelease(jobToken: Long) {
        mainHandler.postDelayed(
            { overlay.detachCaptureKeepAlive(jobToken) },
            OVERLAY_KEEPALIVE_HANDOFF_DELAY_MS,
        )
    }

    private fun failCaptureJobSubmission(
        jobToken: Long,
        message: String,
        error: Throwable,
    ) {
        val rejectedCurrentJob = synchronized(jobLifecycleLock) {
            if (jobGeneration.get() == jobToken) {
                jobRunning.set(false)
                true
            } else {
                false
            }
        }
        overlay.detachCaptureKeepAlive(jobToken)
        releaseCaptureInteraction(jobToken)
        releaseJobWakeLock(jobToken)
        if (rejectedCurrentJob) {
            finishJobForegroundPollingIfIdle()
            pauseCaptureBackendAsync(jobToken)
        }
        reportError(message, error)
    }

    private fun acquireJobWakeLock(jobToken: Long) {
        val wakeLock = runCatching {
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:capture-job",
            ).apply {
                setReferenceCounted(false)
                acquire(JOB_WAKE_LOCK_SAFETY_TIMEOUT_MS)
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to acquire the capture wake lock", error)
        }.getOrNull() ?: return

        var accepted = false
        val previous = synchronized(jobWakeLockGuard) {
            if (isLiveServiceInstance() && jobGeneration.get() == jobToken) {
                accepted = true
                activeJobWakeLockLease.also { activeJobWakeLockLease = JobWakeLockLease(jobToken, wakeLock) }
            } else {
                null
            }
        }
        if (!accepted) {
            releaseWakeLock(wakeLock)
            return
        }
        previous?.wakeLock?.let(::releaseWakeLock)
    }

    private fun releaseJobWakeLock(jobToken: Long) {
        val lease = synchronized(jobWakeLockGuard) {
            activeJobWakeLockLease
                ?.takeIf { it.jobToken == jobToken }
                ?.also { activeJobWakeLockLease = null }
        }
        lease?.wakeLock?.let(::releaseWakeLock)
    }

    private fun forceReleaseJobWakeLock() {
        val lease = synchronized(jobWakeLockGuard) {
            activeJobWakeLockLease.also { activeJobWakeLockLease = null }
        }
        lease?.wakeLock?.let(::releaseWakeLock)
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure { error ->
            Log.w(TAG, "Unable to release the capture wake lock", error)
        }
    }

    private fun newCaptureGestureExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GameLongShot-CaptureGesture").apply { isDaemon = true }
        }

    private fun newCaptureFrameExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GameLongShot-CaptureFrame").apply { isDaemon = true }
        }

    private fun replaceCaptureGestureExecutor(stale: ExecutorService) {
        val replacement = newCaptureGestureExecutor()
        if (captureGestureExecutor.compareAndSet(stale, replacement)) {
            stale.shutdownNow()
        } else {
            replacement.shutdownNow()
        }
    }

    private fun replaceCaptureFrameExecutor(stale: ExecutorService) {
        val replacement = newCaptureFrameExecutor()
        if (captureFrameExecutor.compareAndSet(stale, replacement)) {
            stale.shutdownNow()
        } else {
            replacement.shutdownNow()
        }
    }

    private fun captureBackendFrameWithHardTimeout(
        backend: CaptureBackend,
        expectedSpec: MediaProjectionCaptureManager.DisplaySpec,
        timeoutMs: Long,
    ): Bitmap {
        val executor = captureFrameExecutor.get()
        val abandoned = AtomicBoolean(false)
        val completedBitmap = AtomicReference<Bitmap?>(null)
        val task = executor.submit<Bitmap> {
            backend.captureFreshFrame(expectedSpec, timeoutMs).also { bitmap ->
                completedBitmap.set(bitmap)
                if (abandoned.get() && completedBitmap.compareAndSet(bitmap, null) &&
                    !bitmap.isRecycled
                ) {
                    bitmap.recycle()
                }
            }
        }
        fun abandonTask() {
            abandoned.set(true)
            completedBitmap.getAndSet(null)
                ?.takeUnless { it.isRecycled }
                ?.recycle()
            task.cancel(true)
        }
        return try {
            val bitmap = task.get(
                timeoutMs.coerceAtLeast(1L) + CAPTURE_FRAME_HARD_TIMEOUT_GRACE_MS,
                TimeUnit.MILLISECONDS,
            )
            check(completedBitmap.compareAndSet(bitmap, null)) {
                "Capture frame ownership was abandoned"
            }
            bitmap
        } catch (interrupted: InterruptedException) {
            abandonTask()
            replaceCaptureFrameExecutor(executor)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (timeout: TimeoutException) {
            abandonTask()
            replaceCaptureFrameExecutor(executor)
            throw CaptureFrameHardTimeoutException()
        } catch (error: ExecutionException) {
            abandonTask()
            throw error.cause ?: error
        } catch (cancelled: CancellationException) {
            abandonTask()
            throw cancelled
        }
    }

    private fun pauseCaptureBackendAsync(completedJobGeneration: Long) {
        val backend = captureBackend ?: return
        runCatching {
            backendCleanupExecutor.execute {
                runCatching {
                    backend.pause {
                        captureBackend === backend &&
                            jobGeneration.get() == completedJobGeneration &&
                            !jobRunning.get()
                    }
                }
                    .onFailure { Log.w(TAG, "Unable to pause the capture backend", it) }
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to schedule capture backend cleanup", error)
        }
    }

    private fun acquireCaptureInteraction(jobToken: Long): Boolean {
        if (!isLiveServiceInstance() || jobGeneration.get() != jobToken) return false
        val owner = CaptureInteractionOwner(serviceInstanceId, jobToken)
        if (!CAPTURE_INTERACTION_OWNER_GATE.acquire(owner)) return false
        if (isLiveServiceInstance() && jobGeneration.get() == jobToken) {
            pageSamplingPausedForJob.set(true)
            return true
        }
        releaseCaptureInteraction(jobToken)
        return false
    }

    private fun releaseCaptureInteraction(jobToken: Long) {
        val released = CAPTURE_INTERACTION_OWNER_GATE.release(
            CaptureInteractionOwner(serviceInstanceId, jobToken),
        )
        if (released) resumePageSamplingAfterCapture()
    }

    private fun releaseCaptureInteractionForService() {
        val released = CAPTURE_INTERACTION_OWNER_GATE.releaseService(serviceInstanceId)
        if (released) {
            resumePageSamplingAfterCapture()
        } else {
            pageSamplingPausedForJob.set(false)
        }
    }

    private fun resumePageSamplingAfterCapture() {
        if (pageSamplingPausedForJob.getAndSet(false)) {
            foregroundApplicationTracker.requestLockedPageSignatureRebase()
        }
    }

    private fun finishJobForegroundPollingIfIdle() {
        mainHandler.post {
            if (!jobRunning.get() && !expandedOverlayVisible) {
                latestShizukuForegroundObservation = null
                latestShizukuCompactWindow = null
                overlayForegroundBaselineIdentity = null
                stopForegroundPolling()
            }
        }
    }

    private fun consumeCaptureCompletion(resultSessionId: String): CaptureCompletion? {
        while (true) {
            val pending = pendingCaptureCompletion.get() ?: return null
            if (pending.sessionId != resultSessionId) return null
            if (pendingCaptureCompletion.compareAndSet(pending, null)) return pending
        }
    }

    private fun acknowledgeCaptureResultOpened(resultSessionId: String) {
        synchronized(jobLifecycleLock) {
            if (consumeCaptureCompletion(resultSessionId) == null) return
            restoreCaptureControlsAfterCompletion()
        }
    }

    private fun restoreCaptureControlsAfterCompletion() {
        if (captureBackend != null && !captureBackendLost.get()) {
            runCatching { overlay.showEntry(template) }
                .onFailure { Log.w(TAG, "Unable to restore capture entry after completion", it) }
        } else {
            runCatching { overlay.removeAll() }
                .onFailure { Log.w(TAG, "Unable to remove completion overlay", it) }
            runCatching { releaseCaptureBackendSession() }
                .onFailure { Log.w(TAG, "Unable to release stopped capture backend", it) }
            runCatching {
                transition(
                    CaptureState.IDLE,
                    serviceText(R.string.capture_service_projection_stopped),
                    resultSessionId = null,
                    updateForeground = false,
                )
            }.onFailure { Log.w(TAG, "Unable to publish completed capture shutdown", it) }
            stopSelf()
        }
    }

    private fun requestOpenCaptureCompletion(completion: CaptureCompletion) {
        if (pendingCaptureCompletion.get()?.sessionId != completion.sessionId) return
        if (captureBackend != null && !captureBackendLost.get()) {
            runCatching { overlay.showEntry(template) }
                .onFailure { Log.w(TAG, "Unable to restore capture entry while opening result", it) }
        } else {
            runCatching { overlay.removeAll() }
                .onFailure { Log.w(TAG, "Unable to hide capture overlay while opening result", it) }
        }
        if (openResult(completion.sessionId)) {
            mainHandler.postDelayed(
                {
                    if (canShowCaptureCompletion(completion.sessionId)) {
                        showCaptureCompletionSafely(completion)
                    }
                },
                AUTO_OPEN_RESULT_FALLBACK_DELAY_MS,
            )
        } else {
            showCaptureCompletionSafely(completion)
        }
    }

    private fun showCaptureCompletionSafely(completion: CaptureCompletion) {
        runCatching { showCaptureCompletion(completion) }
            .onFailure { Log.e(TAG, "Unable to show capture completion", it) }
    }

    private fun showCaptureCompletion(completion: CaptureCompletion) {
        overlay.showCaptureCompletion(
            OverlayController.CaptureCompletionInfo(
                resultSessionId = completion.sessionId,
                frameCount = completion.frameCount,
                stitchedWidth = completion.stitchedWidth,
                stitchedHeight = completion.stitchedHeight,
                scrollDirection = completion.scrollDirection,
            ),
        )
    }

    private fun requireCaptureBackendAndGesture() {
        check(captureBackend?.isReady == true) { missingCapturePermissionMessage() }
        check(overlay.canDrawOverlays()) {
            serviceText(R.string.capture_service_overlay_permission_disabled)
        }
        if (captureBackendMode != CaptureBackendMode.SHIZUKU) {
            check(GameScrollAccessibilityService.isConnected()) {
                serviceText(R.string.capture_service_accessibility_disabled)
            }
        }
    }

    private fun validateTemplate(
        template: GameCaptureTemplate,
        spec: MediaProjectionCaptureManager.DisplaySpec,
    ) {
        check(template.screenWidth == spec.width && template.screenHeight == spec.height)
        check(!template.captureRegion.clampTo(spec.width, spec.height).isEmpty) {
            serviceText(R.string.capture_service_invalid_capture_region)
        }
        check(!template.stitchRegion.clampTo(spec.width, spec.height).isEmpty) {
            serviceText(R.string.capture_service_invalid_stitch_region)
        }
        check(template.captureRegion.contains(template.stitchRegion)) {
            serviceText(R.string.capture_service_stitch_outside_capture)
        }
        check(template.fixedLayers.size <= 2) {
            serviceText(R.string.capture_service_invalid_fixed_region_count)
        }
        check(template.fixedLayers.map { it.side }.distinct().size == template.fixedLayers.size) {
            serviceText(R.string.capture_service_duplicate_fixed_positions)
        }
        val fixedBands = template.fixedLayers.map { layer ->
            val band = template.fixedBandRegion(layer.side)
            check(band != null && !band.isEmpty) {
                serviceText(R.string.capture_service_invalid_fixed_region, layer.side.name)
            }
            layer.repeatRegion?.let { repeat ->
                check(band.contains(repeat)) {
                    serviceText(R.string.capture_service_repeat_outside_fixed_region)
                }
            }
            requireNotNull(band)
        }
        fixedBands.forEachIndexed { index, band ->
            fixedBands.drop(index + 1).forEach { other ->
                check(band.intersect(other) == null) {
                    serviceText(R.string.capture_service_fixed_regions_overlap)
                }
            }
        }
        check(!template.gestureRegion.clampTo(spec.width, spec.height).isEmpty) {
            serviceText(R.string.capture_service_invalid_gesture_region)
        }
    }

    private fun provisionalOverlayTemplateFor(
        spec: MediaProjectionCaptureManager.DisplaySpec,
    ): GameCaptureTemplate {
        val target = targetApplication
        return provisionalOverlayTemplateForDisplay(
            source = template ?: overlayDraftRepository.load(),
            targetWidth = spec.width,
            targetHeight = spec.height,
            createDefault = {
                GameCaptureTemplate.createDefault(
                    packageName = target.packageName,
                    appName = target.appName,
                    templateName = serviceText(R.string.capture_service_game_page),
                    screenWidth = spec.width,
                    screenHeight = spec.height,
                ).withDefaultCaptureSettings(appPreferences.loadSettings())
                    .withNewTemplateCaptureDefaults()
                    .withDefaultPreviewGeometry(resources.displayMetrics.density)
            },
            scaleToResolution = templateRepository::scaleToResolution,
        )
    }

    private fun prepareTemplateFor(spec: MediaProjectionCaptureManager.DisplaySpec): GameCaptureTemplate {
        val orientation = if (spec.width >= spec.height) "landscape" else "portrait"
        val target = refreshDetectedTarget()
        var source = template ?: overlayDraftRepository.load()
        val sourcePackage = source?.packageName
        if (!explicitOverlayTemplateSelection &&
            target.packageName.isNotBlank() &&
            sourcePackage?.isNotBlank() == true &&
            sourcePackage != target.packageName
        ) {
            source = templateRepository.findCompatible(
                target.packageName,
                spec.width,
                spec.height,
                orientation,
            ).firstOrNull()?.template
        }
        val prepared = when {
            source == null || source.screenWidth <= 0 || source.screenHeight <= 0 ->
                GameCaptureTemplate.createDefault(
                    packageName = target.packageName,
                    appName = target.appName,
                    templateName = serviceText(R.string.capture_service_game_page),
                    screenWidth = spec.width,
                    screenHeight = spec.height,
                ).withDefaultCaptureSettings(appPreferences.loadSettings())
                    .withNewTemplateCaptureDefaults()
                    .withDefaultPreviewGeometry(resources.displayMetrics.density)
            source.screenWidth != spec.width || source.screenHeight != spec.height ->
                templateRepository.scaleToResolution(source, spec.width, spec.height)
            else -> source
        }
        val selectedOrientation = ScreenOrientation.fromSerialized(
            value = null,
            screenWidth = prepared.screenWidth,
            screenHeight = prepared.screenHeight,
        ).serializedValue
        return prepared.copy(
            packageName = prepared.packageName.ifBlank { target.packageName },
            appName = prepared.appName.ifBlank { target.appName },
            orientation = selectedOrientation,
        ).withNormalizedRegions()
    }

    private fun GameCaptureTemplate.withDefaultCaptureSettings(
        settings: CaptureSettingsUiState,
    ): GameCaptureTemplate = copy(
        previewEnabled = settings.previewEnabled,
        cleanCapture = false,
        previewWidthDp = settings.previewWidthDp,
        previewMaxHeightDp = settings.previewMaxHeightDp,
        previewGapDurationMs = settings.gapDurationMs,
        gestureDistancePx = settings.scrollDistancePx,
        gestureDurationMs = settings.gestureDurationMs.toLong(),
        waitAfterGestureMs = settings.waitAfterGestureMs.toLong(),
        postGestureWaitEnabled = settings.postGestureWaitEnabled,
        postGestureWaitMs = settings.postGestureWaitMs.toLong(),
        waitAfterOverlayHiddenMs = settings.waitStableMs.toLong(),
        maxOutputHeight = settings.maxOutputHeight,
        detectOverlap = true,
        cropDuplicate = true,
        keepOriginalFrames = false,
        keepTempFilesOnFailure = true,
        namingRule = settings.namingRule,
        autoOpenResult = settings.autoOpenResult,
        outputFormat = settings.outputFormat,
        jpegQuality = settings.jpegQuality,
    ).withNormalizedRegions()

    private fun applyTemplateRuntimeConfig(value: GameCaptureTemplate) {
        val normalized = value.withNormalizedRegions()
        pendingWaitAfterGestureMs = normalized.waitAfterGestureMs
        pendingGestureStableTimeMs = normalized.gestureStableTimeMs
        pendingGestureDurationMs = normalized.gestureDurationMs
        pendingGestureDistancePx = normalized.gestureDistancePx
        sessionConfig = sessionConfig.withTemplateRuntimeConfig(
            template = normalized,
            previewMaxWidthPx = dpToPixels(normalized.previewWidthDp),
            previewMaxHeightPx = dpToPixels(normalized.previewMaxHeightDp),
        )
        overlay.updateOutputOptions(normalized.outputFormat, normalized.jpegQuality)
    }

    private fun dpToPixels(value: Int): Int =
        (value.coerceAtLeast(1) * resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    private fun compatibleTemplatesFor(
        spec: MediaProjectionCaptureManager.DisplaySpec,
    ): List<GameCaptureTemplate> {
        return overlayTemplatesForDisplay(
            templates = templateRepository.getAll(),
            targetWidth = spec.width,
            targetHeight = spec.height,
            scaleToResolution = templateRepository::scaleToResolution,
        )
    }

    private fun onForegroundWindowChanged(observation: ForegroundWindowObservation) {
        latestForegroundObservation = observation
        // Scrolling changes a page's accessibility tree by design. During an automatic job that
        // must not be interpreted as leaving the locked target; WINDOW_STATE still reports real
        // app/Home/Recents transitions.
        if (jobRunning.get() && observation.kind == ForegroundObservationKind.PAGE_CONTENT) return
        val normalizedPackage = observation.packageName.trim()
        val isWindowState = observation.kind == ForegroundObservationKind.WINDOW_STATE
        val isOwnActivity = normalizedPackage == packageName &&
            observation.className?.let(::isOwnActivityClassName) == true
        val compactWindow = isWindowState &&
            GameScrollAccessibilityService.isCompactApplicationWindow(observation.windowId)
        lastRealForegroundSurfaceIdentity = retainLastRealForegroundSurfaceIdentity(
            previousSurfaceIdentity = lastRealForegroundSurfaceIdentity,
            observation = observation,
            ownPackage = packageName,
            ownWindowIsActivity = isOwnActivity,
            homePackages = homePackages,
            inputMethodPackages = inputMethodPackages,
            currentSurfaceIsCompact = compactWindow,
            systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
        )
        if (isWindowState && normalizedPackage.isNotEmpty() &&
            normalizedPackage !in inputMethodPackages &&
            (normalizedPackage != packageName || isOwnActivity)
        ) {
            val navigationSurface = isSystemNavigationSurface(
                foregroundPackage = normalizedPackage,
                foregroundComponent = observation.className,
                homePackages = homePackages,
                systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
            )
            val currentSurfaceIdentity = expandedOverlayForegroundSurfaceIdentity(
                foregroundPackage = normalizedPackage,
                foregroundComponent = observation.className,
                homePackages = homePackages,
                systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
            )
            val shouldDismiss = shouldDismissOverlayForForegroundSurfaceChange(
                overlayActive = overlayOpening || expandedOverlayVisible,
                jobRunning = jobRunning.get(),
                previousSurfaceIdentity = overlayForegroundBaselineIdentity,
                currentSurfaceIdentity = currentSurfaceIdentity,
                currentSurfaceIsNavigation = navigationSurface,
                currentSurfaceIsCompact = compactWindow,
            )
            if (!compactWindow && currentSurfaceIdentity != "system:transient") {
                overlayForegroundBaselineIdentity = retainExpandedOverlaySurfaceBaseline(
                    previousSurfaceIdentity = overlayForegroundBaselineIdentity,
                    currentSurfaceIdentity = currentSurfaceIdentity,
                )
            }
            if (shouldDismiss) {
                adoptForegroundTargetBeforeIdleOverlayDismissal(observation)
                cancelPendingOrCollapseIdleOverlay()
                return
            }
        }
        if (compactWindow) return
        handleForegroundApplicationUpdate(foregroundApplicationTracker.observe(observation))
    }

    private fun onNavigationKeyPressed(@Suppress("UNUSED_PARAMETER") keyCode: Int) {
        if (
            shouldCollapseExpandedOverlayForNavigation(
                expandedOverlayVisible = expandedOverlayVisible,
                jobRunning = jobRunning.get(),
                overlayOpening = overlayOpening,
            )
        ) {
            cancelPendingOrCollapseIdleOverlay()
            return
        }
        handleForegroundApplicationUpdate(foregroundApplicationTracker.departLockedTarget())
    }

    private fun cancelPendingOrCollapseIdleOverlay() {
        val wasExpanded = expandedOverlayVisible
        overlayInputSnapshotRequestAtOpen.getAndSet(null)?.cancel()
        overlayOpenGeneration.incrementAndGet()
        overlayOpening = false
        overlayForegroundBaselineIdentity = null
        if (wasExpanded) {
            overlay.collapseExpandedOverlay()
        } else {
            overlay.showEntry(template)
        }
    }

    private fun startForegroundPolling() {
        if (captureBackendMode != CaptureBackendMode.SHIZUKU) return
        synchronized(foregroundPollingLock) {
            val existing = foregroundPollingFuture
            if (existing != null && !existing.isDone && !existing.isCancelled) return
            val generation = foregroundPollingGeneration.incrementAndGet()
            foregroundPollingFuture = foregroundPoller.submit {
                var cyclesSinceActivityVerification = SHIZUKU_ACTIVITY_VERIFICATION_CYCLES
                val openingInputSnapshot = overlayInputSnapshotRequestAtOpen
                    .getAndSet(null)
                    ?.await()
                val openingFocusedPackage = openingInputSnapshot
                    ?.focusedApplicationPackage
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                var appSwitchDetectionArmed = openingInputSnapshot?.appSwitchPending == false
                try {
                    while (foregroundPollingGeneration.get() == generation && isLiveServiceInstance()) {
                        val pollStartedAtMs = SystemClock.uptimeMillis()
                        if (captureBackendMode == CaptureBackendMode.SHIZUKU &&
                            ShizukuBridge.isModeReady(this@GameLongShotCaptureService)
                        ) {
                            val baselinePackage = overlayForegroundBaselineIdentity
                                ?.takeIf { it.startsWith("app:") || it.startsWith("home:") }
                                ?.substringAfter(':')
                            val inputSnapshot = runCatching {
                                ShizukuBridge.currentInputNavigationSnapshot(
                                    SHIZUKU_FOREGROUND_POLL_TIMEOUT_MS,
                                    allowUserServiceBinding = false,
                                    commandLockTimeoutMs = 0L,
                                )
                            }.getOrNull()
                            if (inputSnapshot != null && !inputSnapshot.appSwitchPending) {
                                appSwitchDetectionArmed = true
                            }
                            val changedFromOpeningToNavigation =
                                hasFocusedApplicationChangedToNavigationSurface(
                                    openingFocusedApplicationPackage = openingFocusedPackage,
                                    currentFocusedApplicationPackage =
                                        inputSnapshot?.focusedApplicationPackage,
                                    homePackages = homePackages,
                                    systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
                                )
                            if (changedFromOpeningToNavigation) {
                                collapseIdleOverlayForPolledNavigation(generation)
                            } else if (inputSnapshot?.appSwitchPending == true) {
                                if (appSwitchDetectionArmed) {
                                    collapseIdleOverlayForPolledNavigation(generation)
                                }
                            } else {
                                val focusedPackage = inputSnapshot?.focusedApplicationPackage
                                val verifyActivity = baselinePackage == null ||
                                    focusedPackage == null ||
                                    focusedPackage != baselinePackage ||
                                    cyclesSinceActivityVerification >=
                                    SHIZUKU_ACTIVITY_VERIFICATION_CYCLES
                                if (verifyActivity) {
                                    cyclesSinceActivityVerification = 0
                                } else {
                                    cyclesSinceActivityVerification += 1
                                }
                                if (verifyActivity) {
                                    val foregroundSnapshot = runCatching {
                                        ShizukuBridge.currentResumedActivitySnapshot(
                                            SHIZUKU_FOREGROUND_POLL_TIMEOUT_MS,
                                            allowUserServiceBinding = false,
                                            commandLockTimeoutMs = 0L,
                                        )
                                    }.getOrNull()
                                    foregroundSnapshot?.let {
                                        onShizukuForegroundSnapshot(it, generation)
                                    }
                                }
                            }
                        }
                        val remainingDelayMs = remainingForegroundPollDelayMs(
                            periodMs = SHIZUKU_FOREGROUND_POLL_PERIOD_MS,
                            elapsedMs = SystemClock.uptimeMillis() - pollStartedAtMs,
                        )
                        try {
                            if (remainingDelayMs > 0L) Thread.sleep(remainingDelayMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                } catch (error: Throwable) {
                    if (isLiveServiceInstance()) Log.w(TAG, "Shizuku foreground polling stopped", error)
                } finally {
                    synchronized(foregroundPollingLock) {
                        if (foregroundPollingGeneration.get() == generation) {
                            foregroundPollingFuture = null
                        }
                    }
                }
            }
        }
    }

    private fun stopForegroundPolling() {
        synchronized(foregroundPollingLock) {
            foregroundPollingGeneration.incrementAndGet()
            foregroundPollingFuture?.cancel(true)
            foregroundPollingFuture = null
        }
    }

    private fun onShizukuForegroundSnapshot(
        snapshot: ShizukuForegroundSnapshot,
        generation: Long,
    ) {
        if (!isForegroundPollingGenerationCurrent(generation)) return
        val normalized = snapshot.packageName?.trim().orEmpty()
        val observedAtMs = SystemClock.uptimeMillis()
        val foregroundComponent = if (
            normalized == packageName || normalized == SHIZUKU_SYSTEM_UI_PACKAGE
        ) {
            snapshot.componentName?.lowercase()
        } else {
            null
        }
        latestShizukuForegroundObservation = ForegroundWindowObservation(
            packageName = normalized,
            className = foregroundComponent,
            observedAtMs = observedAtMs,
        )
        latestShizukuCompactWindow = snapshot.compactWindowBounds?.let { bounds ->
            TimedCompactApplicationWindow(bounds, observedAtMs)
        }
        if (normalized.isEmpty() || normalized in inputMethodPackages) return

        if (!isForegroundPollingGenerationCurrent(generation)) return
        val navigationSurface = isSystemNavigationSurface(
            foregroundPackage = normalized,
            foregroundComponent = foregroundComponent,
            homePackages = homePackages,
            systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
        )
        val currentSurfaceIdentity = expandedOverlayForegroundSurfaceIdentity(
            foregroundPackage = normalized,
            foregroundComponent = foregroundComponent,
            homePackages = homePackages,
            systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
        )
        val compactWindow = snapshot.compactWindowBounds != null
        val shouldDismiss = shouldDismissOverlayForForegroundSurfaceChange(
            overlayActive = overlayOpening || expandedOverlayVisible,
            jobRunning = jobRunning.get(),
            previousSurfaceIdentity = overlayForegroundBaselineIdentity,
            currentSurfaceIdentity = currentSurfaceIdentity,
            currentSurfaceIsNavigation = navigationSurface,
            currentSurfaceIsCompact = compactWindow,
        )
        if (!compactWindow && currentSurfaceIdentity != "system:transient") {
            overlayForegroundBaselineIdentity = retainExpandedOverlaySurfaceBaseline(
                previousSurfaceIdentity = overlayForegroundBaselineIdentity,
                currentSurfaceIdentity = currentSurfaceIdentity,
            )
        }
        if (shouldDismiss) {
            mainHandler.post {
                if (isForegroundPollingGenerationCurrent(generation) &&
                    (overlayOpening || expandedOverlayVisible) && !jobRunning.get()
                ) {
                    adoptForegroundTargetBeforeIdleOverlayDismissal(
                        ForegroundWindowObservation(
                            packageName = normalized,
                            className = foregroundComponent,
                            observedAtMs = observedAtMs,
                        ),
                    )
                    cancelPendingOrCollapseIdleOverlay()
                }
            }
            return
        }
        if (compactWindow) return

        // TYPE_APPLICATION_OVERLAY windows do not become the resumed activity, but a user can
        // switch back to GameLongShot itself from Recents. This is only a capture-safety signal;
        // an idle overlay is collapsed by outside touch or explicit Home/Recents navigation.
        if (normalized == packageName) {
            if (jobRunning.get() &&
                foregroundComponent?.substringBefore('/') == packageName.lowercase()
            ) {
                val update = foregroundApplicationTracker.departLockedTarget()
                if (update.departedLockedTarget != null) {
                    handleForegroundApplicationUpdate(update, pollingGeneration = generation)
                }
            }
            return
        }

        // dumpsys reports Recents as SystemUI while the overview surface is open. It is a
        // navigation departure, unlike a regular transient window observed by accessibility.
        val update = if (normalized == SHIZUKU_SYSTEM_UI_PACKAGE) {
            if (!isSystemNavigationSurface(
                    foregroundPackage = normalized,
                    foregroundComponent = foregroundComponent,
                    homePackages = homePackages,
                    systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
                )
            ) {
                return
            }
            foregroundApplicationTracker.departLockedTarget()
        } else {
            foregroundApplicationTracker.observe(
                ForegroundWindowObservation(
                    packageName = normalized,
                    observedAtMs = SystemClock.uptimeMillis(),
                ),
            )
        }
        if (!isForegroundPollingGenerationCurrent(generation)) return
        if (update.departedLockedTarget != null || update.targetChanged) {
            handleForegroundApplicationUpdate(update, pollingGeneration = generation)
        }
    }

    private fun collapseIdleOverlayForPolledNavigation(generation: Long) {
        if (!isForegroundPollingGenerationCurrent(generation)) return
        mainHandler.post {
            if (isForegroundPollingGenerationCurrent(generation) &&
                shouldCollapseExpandedOverlayForNavigation(
                    expandedOverlayVisible = expandedOverlayVisible,
                    jobRunning = jobRunning.get(),
                    overlayOpening = overlayOpening,
                )
            ) {
                cancelPendingOrCollapseIdleOverlay()
            }
        }
    }

    private fun adoptForegroundTargetBeforeIdleOverlayDismissal(
        observation: ForegroundWindowObservation,
    ) {
        val foregroundPackage = observation.packageName.trim()
        if (foregroundPackage.isEmpty() ||
            foregroundPackage == packageName ||
            foregroundPackage == SHIZUKU_SYSTEM_UI_PACKAGE ||
            foregroundPackage in homePackages ||
            foregroundPackage in inputMethodPackages
        ) return

        val update = foregroundApplicationTracker.observe(observation)
        if (update.targetChanged) explicitOverlayTemplateSelection = false
        update.targetPackageName?.takeIf(String::isNotBlank)?.let { targetPackageName ->
            if (targetApplication.packageName != targetPackageName) {
                targetApplication = resolveTargetApplication(targetPackageName)
            }
        }
    }

    private fun isForegroundPollingGenerationCurrent(generation: Long?): Boolean =
        generation == null || (
            isLiveServiceInstance() &&
                captureBackendMode == CaptureBackendMode.SHIZUKU &&
                foregroundPollingGeneration.get() == generation
            )

    private fun handleForegroundApplicationUpdate(
        update: ForegroundApplicationUpdate,
        pollingGeneration: Long? = null,
    ) {
        if (!isForegroundPollingGenerationCurrent(pollingGeneration)) return
        val overlayWasExpanded = expandedOverlayVisible
        val departedPackage = update.departedLockedTarget
        val jobWasRunning = jobRunning.get()
        val editToCancel = if (jobWasRunning) {
            departedPackage?.let { packageName ->
                guardedRegionEdit?.takeIf { it.target.packageName == packageName }
            }
        } else {
            null
        }
        val switchedAwayFromCaptureTarget = jobWasRunning &&
            update.targetChanged &&
            update.targetPackageName?.takeIf(String::isNotBlank)?.let { foregroundPackage ->
                targetApplication.packageName.isNotBlank() &&
                    foregroundPackage != targetApplication.packageName
            } == true
        val departureAction = foregroundDepartureAction(
            departedLockedTarget = departedPackage != null,
            switchedAwayFromCaptureTarget = switchedAwayFromCaptureTarget,
            jobRunning = jobWasRunning,
        )
        if (departureAction == ForegroundDepartureAction.COLLAPSE_AND_STOP &&
            departedPackage != null
        ) {
            rejectPendingOverlaySave = true
        }
        if (editToCancel != null) {
            guardedRegionEdit = null
        }
        if (departureAction == ForegroundDepartureAction.COLLAPSE_AND_STOP) {
            overlay.removeAll()
            requestStop(serviceText(R.string.capture_service_left_target_stopping))
        }
        if (!update.targetChanged && editToCancel == null) return
        if (update.targetChanged && !overlayWasExpanded) {
            explicitOverlayTemplateSelection = false
        }

        runCatching {
            worker.execute {
                if (!isForegroundPollingGenerationCurrent(pollingGeneration)) return@execute
                if (!jobWasRunning && !overlayWasExpanded) {
                    update.targetPackageName?.takeIf(String::isNotBlank)?.let { foregroundPackage ->
                        targetApplication = resolveTargetApplication(foregroundPackage)
                    }
                }
                editToCancel?.let { cancelled ->
                    if (!isForegroundPollingGenerationCurrent(pollingGeneration)) return@execute
                    val restored = cancelled.rollbackTemplate.withNormalizedRegions()
                    template = restored
                    applyTemplateRuntimeConfig(restored)
                    overlayDraftRepository.save(restored)
                    transition(
                        CaptureState.READY_TO_CAPTURE,
                        serviceText(R.string.capture_service_left_target_edit_cancelled_short),
                    )
                    if (isForegroundPollingGenerationCurrent(pollingGeneration)) {
                        overlay.showEntry(restored)
                    }
                }
            }
        }.onFailure { error ->
            if (isLiveServiceInstance()) Log.w(TAG, "Unable to process foreground app change", error)
        }
    }

    private fun initialOverlayForegroundSurfaceIdentity(): String? {
        if (captureBackendMode == CaptureBackendMode.SHIZUKU) return null
        val observation = latestForegroundObservation?.takeIf { candidate ->
            shouldUseAccessibilityObservationAsExpandedBaseline(
                captureUsesShizuku = false,
                observation = candidate,
                nowUptimeMs = SystemClock.uptimeMillis(),
                maximumAgeMs = EXPANDED_OVERLAY_BASELINE_MAX_AGE_MS,
            )
        }
        val observedIdentity = observation?.let { candidate ->
            val observedPackage = candidate.packageName.trim()
            val ownOverlayWindow = observedPackage == packageName &&
                candidate.className?.let(::isOwnActivityClassName) != true
            val compactWindow = GameScrollAccessibilityService
                .isCompactApplicationWindow(candidate.windowId)
            if (observedPackage.isEmpty() || observedPackage in inputMethodPackages ||
                ownOverlayWindow || compactWindow
            ) {
                null
            } else {
                expandedOverlayForegroundSurfaceIdentity(
                    foregroundPackage = observedPackage,
                    foregroundComponent = candidate.className,
                    homePackages = homePackages,
                    systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
                ).takeUnless { it == "system:transient" }
            }
        }
        return resolveInitialExpandedOverlaySurfaceIdentity(
            recentSurfaceIdentity = observedIdentity,
            lastRealSurfaceIdentity = lastRealForegroundSurfaceIdentity,
            trackedTargetPackage = foregroundApplicationTracker.currentTargetPackageName(),
        )
    }

    private fun isOwnActivityClassName(className: String): Boolean {
        val normalized = className.trim()
        return normalized.endsWith("Activity", ignoreCase = true) &&
            (normalized.startsWith(packageName, ignoreCase = true) ||
                normalized.substringBefore('/').equals(packageName, ignoreCase = true))
    }

    private fun lockExpandedOverlayTarget() {
        val targetPackage = expandedOverlayTargetPackage(
            guardedTargetPackage = guardedRegionEdit?.target?.packageName,
            detectedTargetPackage = targetApplication.packageName,
            trackedTargetPackage = foregroundApplicationTracker.currentTargetPackageName(),
            templateTargetPackage = template?.packageName,
            preferDetectedTarget = captureBackendMode == CaptureBackendMode.SHIZUKU,
        ) ?: return
        foregroundApplicationTracker.lockTarget(targetPackage, SystemClock.uptimeMillis())
    }

    private fun guardRegionEdit(
        editedTemplate: GameCaptureTemplate,
        rollbackTemplate: GameCaptureTemplate = editedTemplate,
    ) {
        val targetPackage = foregroundApplicationTracker.currentTargetPackageName()
            ?: targetApplication.packageName.takeIf(String::isNotBlank)
            ?: editedTemplate.packageName.takeIf(String::isNotBlank)
            ?: return
        val lockedPackage = foregroundApplicationTracker.lockTarget(
            targetPackage,
            SystemClock.uptimeMillis(),
        ) ?: return
        val lockedTarget = if (targetApplication.packageName == lockedPackage) {
            targetApplication
        } else {
            resolveTargetApplication(lockedPackage)
        }
        guardedRegionEdit = GuardedRegionEdit(
            target = lockedTarget,
            rollbackTemplate = rollbackTemplate,
        )
        rejectPendingOverlaySave = false
    }

    private fun completeGuardedRegionEdit() {
        if (guardedRegionEdit == null) return
        guardedRegionEdit = null
        foregroundApplicationTracker.unlockTarget()
        rejectPendingOverlaySave = false
    }

    private fun refreshShizukuTargetApplication(): String? {
        if (captureBackendMode != CaptureBackendMode.SHIZUKU) return null
        val foregroundPackage = recentForegroundPackage(
            observation = latestShizukuForegroundObservation,
            nowUptimeMs = SystemClock.uptimeMillis(),
            maximumAgeMs = SHIZUKU_CAPTURE_FOREGROUND_CACHE_MAX_AGE_MS,
        ) ?: runCatching {
            ShizukuBridge.currentResumedActivitySnapshot(
                timeoutMs = SHIZUKU_CAPTURE_FOREGROUND_QUERY_TIMEOUT_MS,
                allowUserServiceBinding = false,
                commandLockTimeoutMs = SHIZUKU_CAPTURE_FOREGROUND_LOCK_TIMEOUT_MS,
            )?.packageName
        }.onFailure { error ->
            Log.w(TAG, "Unable to refresh the Shizuku target with a bounded query", error)
        }.getOrNull()
        val normalizedForegroundPackage = foregroundPackage
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        updateShizukuTargetApplication(normalizedForegroundPackage)
        return normalizedForegroundPackage
    }

    private fun refreshShizukuTargetForOverlayOpen() {
        if (captureBackendMode != CaptureBackendMode.SHIZUKU) return
        val foregroundPackage = runCatching {
            ShizukuBridge.currentForegroundPackage(OVERLAY_OPEN_TARGET_REFRESH_TIMEOUT_MS)
        }.onFailure { error ->
            Log.w(TAG, "Unable to refresh the foreground target before opening the overlay", error)
        }.getOrNull()?.trim()
        if (!shouldAdoptShizukuOverlayOpenTarget(
                foregroundPackage = foregroundPackage,
                ownPackage = packageName,
                homePackages = homePackages,
                inputMethodPackages = inputMethodPackages,
                systemUiPackage = SHIZUKU_SYSTEM_UI_PACKAGE,
            )
        ) return
        val targetPackage = checkNotNull(foregroundPackage)

        val update = foregroundApplicationTracker.observe(
            ForegroundWindowObservation(
                packageName = targetPackage,
                observedAtMs = SystemClock.uptimeMillis(),
            ),
        )
        if (update.targetChanged) explicitOverlayTemplateSelection = false
        targetApplication = resolveTargetApplication(targetPackage)
    }

    private fun updateShizukuTargetApplication(foregroundPackage: String): Boolean {
        if (foregroundPackage == packageName ||
            foregroundPackage == SHIZUKU_SYSTEM_UI_PACKAGE ||
            foregroundPackage in homePackages ||
            foregroundPackage in inputMethodPackages
        ) return false
        targetApplication = resolveTargetApplication(foregroundPackage)
        return true
    }

    private fun currentShizukuForegroundStatus(
        maximumQueryWaitMs: Long = SHIZUKU_CAPTURE_FOREGROUND_QUERY_TIMEOUT_MS,
    ): ShizukuForegroundStatus {
        val startedAtMs = SystemClock.elapsedRealtime()
        val nowUptimeMs = SystemClock.uptimeMillis()
        val cachedPackage = recentForegroundPackage(
            latestShizukuForegroundObservation,
            nowUptimeMs,
            SHIZUKU_CAPTURE_FOREGROUND_CACHE_MAX_AGE_MS,
        )
        val queryWaitMs = minOf(
            SHIZUKU_CAPTURE_FOREGROUND_QUERY_TIMEOUT_MS,
            maximumQueryWaitMs.coerceAtLeast(0L),
        )
        val foregroundPackage = cachedPackage ?: if (queryWaitMs > 0L) {
            runCatching {
                ShizukuBridge.currentResumedActivitySnapshot(
                    timeoutMs = queryWaitMs,
                    allowUserServiceBinding = false,
                    commandLockTimeoutMs = minOf(
                        SHIZUKU_CAPTURE_FOREGROUND_LOCK_TIMEOUT_MS,
                        queryWaitMs,
                    ),
                )?.packageName
            }.onFailure { error ->
                Log.w(TAG, "Unable to perform bounded Shizuku foreground safety check", error)
            }.getOrNull()
        } else {
            null
        }
        if (targetApplication.packageName.isBlank() && !foregroundPackage.isNullOrBlank()) {
            updateShizukuTargetApplication(foregroundPackage)
        }
        val status = shizukuForegroundStatus(
            expectedPackage = targetApplication.packageName,
            foregroundPackage = foregroundPackage,
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        if (elapsedMs >= SHIZUKU_SLOW_SAFETY_CHECK_LOG_MS) {
            Log.w(
                TAG,
                "Slow Shizuku foreground safety check durationMs=$elapsedMs status=$status " +
                    "cacheHit=${cachedPackage != null}",
            )
        }
        return status
    }

    private fun stopIfShizukuTargetUnsafe(
        maximumQueryWaitMs: Long = SHIZUKU_CAPTURE_FOREGROUND_QUERY_TIMEOUT_MS,
    ): Boolean {
        if (captureBackendMode != CaptureBackendMode.SHIZUKU) return false
        val status = currentShizukuForegroundStatus(maximumQueryWaitMs)
        val nowMs = SystemClock.elapsedRealtime()
        val unknownDurationMs = if (status == ShizukuForegroundStatus.UNKNOWN) {
            val existing = shizukuForegroundUnknownSinceMs.get()
            val startedAtMs = if (existing >= 0L) {
                existing
            } else if (shizukuForegroundUnknownSinceMs.compareAndSet(-1L, nowMs)) {
                nowMs
            } else {
                shizukuForegroundUnknownSinceMs.get().coerceAtLeast(0L)
            }
            (nowMs - startedAtMs).coerceAtLeast(0L)
        } else {
            shizukuForegroundUnknownSinceMs.set(-1L)
            0L
        }
        if (!shouldStopForShizukuForeground(
                status = status,
                unknownDurationMs = unknownDurationMs,
                unknownGraceMs = SHIZUKU_FOREGROUND_UNKNOWN_GRACE_MS,
            )
        ) return false
        restoreRegionConfigurationAfterSafetyStop.set(status == ShizukuForegroundStatus.UNKNOWN)
        overlay.removeAll()
        requestStop(
            if (status == ShizukuForegroundStatus.DEPARTED) {
                serviceText(R.string.capture_service_left_target_stopping)
            } else {
                serviceText(R.string.capture_service_cannot_confirm_target_shizuku_stopped)
            },
        )
        return true
    }

    private fun refreshDetectedTarget(): TargetApplicationInfo {
        val detectedPackage = foregroundApplicationTracker.currentTargetPackageName()
            ?: targetApplication.packageName
        if (detectedPackage.isBlank()) return targetApplication
        return resolveTargetApplication(detectedPackage).also { targetApplication = it }
    }

    private fun resolveCurrentTargetApplication(): TargetApplicationInfo? {
        guardedRegionEdit?.target?.let { return it }
        val detectedPackage = foregroundApplicationTracker.currentTargetPackageName()
            ?: targetApplication.packageName
        if (detectedPackage.isBlank()) return null
        return resolveTargetApplication(detectedPackage).also { targetApplication = it }
    }

    private fun missingCapturePermissionMessage(): String =
        if (captureBackendMode == CaptureBackendMode.SHIZUKU) {
            serviceText(R.string.capture_service_shizuku_permission_invalid)
        } else {
            serviceText(R.string.capture_service_projection_permission_invalid)
        }

    private fun automaticGestureFailureMessage(
        prefix: String = serviceText(R.string.capture_service_automatic_swipe_failed),
    ): String =
        if (captureBackendMode == CaptureBackendMode.SHIZUKU) {
            serviceText(R.string.capture_service_check_shizuku_and_path, prefix)
        } else {
            serviceText(R.string.capture_service_check_accessibility_and_region, prefix)
        }

    @Suppress("DEPRECATION")
    private fun resolveHomePackages(): Set<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packages = packageManager
            .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNullTo(linkedSetOf()) { it.activityInfo?.packageName?.takeIf(String::isNotBlank) }
        packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            ?.takeIf(String::isNotBlank)
            ?.let(packages::add)
        return packages
    }

    private fun resolveInputMethodPackages(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java)
            .enabledInputMethodList
            .mapTo(hashSetOf()) { it.packageName }
    }.getOrDefault(emptySet())

    private fun resolveTargetApplication(
        targetPackageName: String,
        suppliedName: String? = null,
    ): TargetApplicationInfo {
        val resolvedName = applicationLabelResolver.resolve(targetPackageName)
            ?: suppliedName?.trim()?.takeIf { it.isNotEmpty() && it != targetPackageName }
            ?: ""
        return TargetApplicationInfo(targetPackageName, resolvedName)
    }

    private fun mergeTemplateIdentity(incoming: GameCaptureTemplate): GameCaptureTemplate {
        val existing = template
        val target = targetApplication
        return incoming.copy(
            // id=0 intentionally means a transient/unsaved configuration. Explicit saves return
            // the generated id through OverlayController.Callback.onSaveTemplate.
            id = incoming.id,
            packageName = incoming.packageName.ifBlank {
                existing?.packageName?.ifBlank { target.packageName } ?: target.packageName
            },
            appName = incoming.appName.ifBlank {
                existing?.appName?.ifBlank { target.appName } ?: target.appName
            },
        ).withNormalizedRegions()
    }

    private fun hideOverlayAndWait(
        waitMs: Long,
        preservePendingUiRequest: Boolean = false,
        interruptible: Boolean = true,
        removalTimeoutMs: Long = OVERLAY_REMOVE_TIMEOUT_MS,
    ) {
        val removed = CountDownLatch(1)
        overlay.hideForCapture(preservePendingUiRequest) { removed.countDown() }
        var interrupted = false
        try {
            val removedInTime = if (interruptible) {
                removed.await(
                    removalTimeoutMs.coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
            } else {
                val deadline = SystemClock.elapsedRealtime() + removalTimeoutMs.coerceAtLeast(0L)
                var completed = false
                while (!completed) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    try {
                        completed = removed.await(remaining, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                completed
            }
            check(removedInTime) { serviceText(R.string.capture_service_overlay_not_removed) }
            val cleanFrameDelayMs = waitMs.coerceIn(0L, 1_500L)
            if (interruptible) {
                sleepInterruptibly(cleanFrameDelayMs)
            } else {
                val deadline = SystemClock.elapsedRealtime() + cleanFrameDelayMs
                while (true) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    try {
                        Thread.sleep(remaining)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun captureFreshFrame(
        backend: CaptureBackend,
        expectedSpec: MediaProjectionCaptureManager.DisplaySpec,
    ): Bitmap {
        val startedAtMs = SystemClock.elapsedRealtime()
        return captureBackendFrameWithHardTimeout(
            backend = backend,
            expectedSpec = expectedSpec,
            timeoutMs = FRAME_TIMEOUT_MS,
        ).also { bitmap ->
            Log.d(
                TAG,
                "captureBackendTiming mode=$captureBackendMode " +
                    "durationMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                    "size=${bitmap.width}x${bitmap.height}",
            )
        }
    }

    /** Region learning needs motion, not eight retained 4K RGBA buffers. */
    private fun captureLearningSample(
        backend: CaptureBackend,
        expectedSpec: MediaProjectionCaptureManager.DisplaySpec,
        sample: Int,
        total: Int,
        minimumVisibleMs: Long,
    ): Bitmap {
        val full = captureFreshFrame(backend, expectedSpec)
        val capturedAtMs = SystemClock.elapsedRealtime()
        val statusDrawn = CountDownLatch(1)
        val statusDrawnAtMs = AtomicLong(0L)
        overlay.showLearningStatus(
            sample,
            total,
            serviceText(R.string.capture_service_processing_current_frame),
        ) {
            statusDrawnAtMs.compareAndSet(0L, SystemClock.elapsedRealtime())
            statusDrawn.countDown()
        }
        val largest = maxOf(full.width, full.height)
        val processed = if (largest <= LEARNING_SAMPLE_MAX_DIMENSION) {
            full
        } else {
            val scale = LEARNING_SAMPLE_MAX_DIMENSION.toFloat() / largest
            try {
                BitmapFrameUtils.downscaleForAnalysis(full, scale)
            } finally {
                full.recycle()
            }
        }
        val processedAtMs = SystemClock.elapsedRealtime()
        return try {
            val drawnInTime = statusDrawn.await(
                OVERLAY_REMOVE_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
            if (!drawnInTime) {
                Log.w(TAG, "Learning status did not draw in time for sample $sample/$total")
            }
            val conservativeFallbackStartedAtMs = SystemClock.elapsedRealtime()
            while (!stopRequested.get()) {
                val visibleSinceMs = statusDrawnAtMs.get().takeIf { it > 0L }
                    ?: conservativeFallbackStartedAtMs
                val remainingVisibleMs = remainingMinimumBannerVisibleMs(
                    configuredMinimumVisibleMs = minimumVisibleMs,
                    elapsedSinceBannerDrawnMs = SystemClock.elapsedRealtime() - visibleSinceMs,
                )
                if (remainingVisibleMs <= 0L) break
                sleepInterruptibly(minOf(remainingVisibleMs, 80L))
            }
            Log.d(
                TAG,
                "learningFrameTiming sample=$sample/$total " +
                    "processMs=${processedAtMs - capturedAtMs} " +
                    "drawAndMinimumWaitMs=${SystemClock.elapsedRealtime() - processedAtMs}",
            )
            processed
        } catch (error: Throwable) {
            processed.takeUnless { it.isRecycled }?.recycle()
            throw error
        }
    }

    private fun captureRecognitionSample(
        backend: CaptureBackend,
        expectedSpec: MediaProjectionCaptureManager.DisplaySpec,
        timeoutMs: Long = RECOGNITION_FRAME_TIMEOUT_MS,
    ): Bitmap {
        val startedAtMs = SystemClock.elapsedRealtime()
        val full = captureBackendFrameWithHardTimeout(
            backend = backend,
            expectedSpec = expectedSpec,
            timeoutMs = timeoutMs.coerceIn(1L, RECOGNITION_FRAME_TIMEOUT_MS),
        )
        val largest = maxOf(full.width, full.height)
        val processed = if (largest <= RECOGNITION_SAMPLE_MAX_DIMENSION) {
            full
        } else {
            val scale = RECOGNITION_SAMPLE_MAX_DIMENSION.toFloat() / largest
            try {
                BitmapFrameUtils.downscaleForAnalysis(full, scale)
            } finally {
                full.recycle()
            }
        }
        Log.d(
            TAG,
            "recognitionFrameTiming durationMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                "size=${processed.width}x${processed.height}",
        )
        return processed
    }

    private fun RegionLearningResult.scaleToDisplay(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): RegionLearningResult {
        val scaleX = targetWidth.toFloat() / sourceWidth
        val scaleY = targetHeight.toFloat() / sourceHeight
        fun RectConfig.scaled(): RectConfig = scale(scaleX, scaleY).clampTo(targetWidth, targetHeight)
        return copy(
            suggestedStitchRegion = suggestedStitchRegion.scaled(),
            suggestedGestureRegion = suggestedGestureRegion.scaled(),
            suggestedTopFixedRegion = suggestedTopFixedRegion?.scaled(),
            suggestedBottomFixedRegion = suggestedBottomFixedRegion?.scaled(),
            suggestedHudMasks = suggestedHudMasks.map { it.scaled() }.filterNot { it.isEmpty },
            uncertainRegions = uncertainRegions.map { it.scaled() }.filterNot { it.isEmpty },
            motionEstimate = motionEstimate?.let { estimate ->
                MotionEstimate(
                    dx = (estimate.dx * scaleX).roundToInt(),
                    dy = (estimate.dy * scaleY).roundToInt(),
                    confidence = estimate.confidence,
                    inlierRatio = estimate.inlierRatio,
                )
            },
        )
    }

    private fun registerRecognitionGesture(gesture: RecognitionGestureHandle): Boolean {
        while (true) {
            val existing = activeRecognitionGesture.get()
            if (existing != null && !existing.isComplete()) return false
            if (activeRecognitionGesture.compareAndSet(existing, gesture)) return true
        }
    }

    private fun cancelAndClearRecognitionGesture(
        gesture: RecognitionGestureHandle,
        timeoutMs: Long,
    ) {
        val deadlineMs = recognitionDeadlineAfter(
            startMs = SystemClock.elapsedRealtime(),
            durationMs = timeoutMs,
        )
        if (!gesture.isComplete()) {
            runCatching(gesture::cancel).onFailure { error ->
                Log.w(TAG, "Unable to cancel the active recognition gesture", error)
            }
        }
        var restoreInterrupt = Thread.interrupted()
        var completed = gesture.isComplete()
        while (!completed) {
            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) break
            try {
                completed = gesture.awaitCompletion(remainingMs)
            } catch (_: InterruptedException) {
                restoreInterrupt = true
            }
        }
        if (!completed) {
            Log.w(TAG, "Recognition gesture did not settle before cleanup timeout")
            if (captureBackendMode != CaptureBackendMode.SHIZUKU) {
                GameScrollAccessibilityService.abandonCurrentGestureAndAwait(0L)
            }
        }
        activeRecognitionGesture.compareAndSet(gesture, null)
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }

    private fun requestShizukuRecognitionGestureCancellation(
        sessionId: Long,
        completion: CountDownLatch,
    ) {
        val cancellation = Runnable {
            runCatching { ShizukuBridge.cancelSwipe(sessionId) }
                .onFailure { error ->
                    Log.w(TAG, "Unable to cancel recognition session $sessionId", error)
                }
            ShizukuBridge.releaseSwipeSession(sessionId)
            completion.countDown()
        }
        runCatching {
            recognitionGestureCancellationExecutor.execute(cancellation)
        }.onFailure {
            cancellation.run()
        }
    }

    private fun dispatchRecognitionGesture(
        template: GameCaptureTemplate,
        distance: Int,
        movementAxisRegion: RectConfig? = null,
        crossAxisRegion: RectConfig? = null,
    ): RecognitionGestureHandle? {
        val safeMovementRegion = scrollGestureMovementRegion(
            template = template,
            liveMovementRegion = movementAxisRegion,
        )
        val captureBounds = template.captureRegion.normalized()
        val safeCrossAxisRegion = crossAxisRegion
            ?.normalized()
            ?.intersect(captureBounds)
            ?.takeUnless { it.isEmpty }
            ?: scrollGestureCrossAxisRegion(template)
        val path = resolveTemplateScrollGesturePath(
            template = template,
            requestedDistancePx = distance,
            movementAxisRegion = safeMovementRegion,
            crossAxisRegion = safeCrossAxisRegion,
        )
        val completion = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        if (captureBackendMode == CaptureBackendMode.SHIZUKU) {
            val sessionId = ShizukuBridge.reserveLinearSwipeSession() ?: return null
            val handle = RecognitionGestureHandle(
                distancePx = path.distancePx,
                completion = completion,
                succeeded = succeeded,
                cancelAction = {
                    requestShizukuRecognitionGestureCancellation(sessionId, completion)
                },
            )
            if (!registerRecognitionGesture(handle)) {
                ShizukuBridge.releaseSwipeSession(sessionId)
                return null
            }
            val future = runCatching {
                recognitionGestureExecutor.submit {
                    try {
                        val result = ShizukuBridge.performLinearSwipe(
                            sessionId = sessionId,
                            startX = path.start.x.roundToInt(),
                            startY = path.start.y.roundToInt(),
                            endX = path.end.x.roundToInt(),
                            endY = path.end.y.roundToInt(),
                            durationMs = DefaultCaptureConfig.REGION_RECOGNITION_DURATION_MS,
                        )
                        succeeded.set(result != ShizukuSwipeResult.UNAVAILABLE)
                    } finally {
                        ShizukuBridge.releaseSwipeSession(sessionId)
                        completion.countDown()
                    }
                }
            }.getOrNull()
            if (future == null) {
                runCatching { ShizukuBridge.cancelSwipe(sessionId) }
                ShizukuBridge.releaseSwipeSession(sessionId)
                completion.countDown()
                activeRecognitionGesture.compareAndSet(handle, null)
                return null
            }
            if (!ShizukuBridge.awaitSwipeStarted(sessionId, RECOGNITION_GESTURE_START_TIMEOUT_MS)) {
                cancelAndClearRecognitionGesture(
                    gesture = handle,
                    timeoutMs = RECOGNITION_GESTURE_FINISH_GRACE_MS,
                )
                return null
            }
            return handle
        }

        val request = GameScrollAccessibilityService.Request(
            direction = template.scrollDirection.toGestureDirection(),
            gestureRegion = Rect(
                template.gestureRegion.left,
                template.gestureRegion.top,
                template.gestureRegion.right,
                template.gestureRegion.bottom,
            ),
            distancePx = path.distancePx,
            durationMs = DefaultCaptureConfig.REGION_RECOGNITION_DURATION_MS,
            endpointHoldMs = 0L,
            distanceRegion = Rect(
                template.stitchRegion.left,
                template.stitchRegion.top,
                template.stitchRegion.right,
                template.stitchRegion.bottom,
            ),
            movementAxisRegion = Rect(
                safeMovementRegion.left,
                safeMovementRegion.top,
                safeMovementRegion.right,
                safeMovementRegion.bottom,
            ),
            crossAxisRegion = Rect(
                safeCrossAxisRegion.left,
                safeCrossAxisRegion.top,
                safeCrossAxisRegion.right,
                safeCrossAxisRegion.bottom,
            ),
            linearMotion = true,
        )
        val handle = RecognitionGestureHandle(
            distancePx = path.distancePx,
            completion = completion,
            succeeded = succeeded,
            cancelAction = GameScrollAccessibilityService::abandonCurrentGesture,
        )
        if (!registerRecognitionGesture(handle)) return null
        val accepted = GameScrollAccessibilityService.dispatch(request) { success ->
            succeeded.set(success)
            completion.countDown()
        }
        if (!accepted) {
            activeRecognitionGesture.compareAndSet(handle, null)
            return null
        }
        return handle
    }

    private fun dispatchCaptureGesture(
        template: GameCaptureTemplate,
        distance: Int,
        crossAxisOffsetFraction: Float = 0f,
        movementAxisRegion: RectConfig? = null,
        crossAxisRegion: RectConfig? = null,
        allowUnknownShizukuOutcome: Boolean = false,
    ): CaptureGestureDispatch? {
        var failedStartCount = 0
        val retryWindowStartedAtMs = SystemClock.elapsedRealtime()
        while (!stopRequested.get()) {
            when (val attempt = dispatchCaptureGestureOnce(
                template = template,
                distance = distance,
                crossAxisOffsetFraction = crossAxisOffsetFraction,
                movementAxisRegion = movementAxisRegion,
                crossAxisRegion = crossAxisRegion,
                allowUnknownShizukuOutcome = allowUnknownShizukuOutcome,
            )) {
                is CaptureGestureDispatchAttempt.Dispatched -> return attempt.value
                CaptureGestureDispatchAttempt.FailedAfterStart -> return null
                CaptureGestureDispatchAttempt.NotStarted -> {
                    failedStartCount += 1
                    val shouldRetryStart = shouldRetryCaptureGestureStart(
                        failedStartCount = failedStartCount,
                        retryCount = CAPTURE_GESTURE_START_RETRY_COUNT,
                        elapsedSinceFirstAttemptMs =
                            SystemClock.elapsedRealtime() - retryWindowStartedAtMs,
                        retryWindowMs = CAPTURE_GESTURE_START_RETRY_WINDOW_MS,
                        retryDelayMs = CAPTURE_GESTURE_START_RETRY_DELAY_MS,
                        stopRequested = stopRequested.get(),
                    )
                    if (!shouldRetryStart) return null
                    Log.w(
                        TAG,
                        "Capture gesture did not reach ACTION_DOWN; retrying start " +
                            "$failedStartCount/$CAPTURE_GESTURE_START_RETRY_COUNT",
                    )
                    sleepInterruptibly(CAPTURE_GESTURE_START_RETRY_DELAY_MS)
                }
            }
        }
        return null
    }

    private fun dispatchCaptureGestureOnce(
        template: GameCaptureTemplate,
        distance: Int,
        crossAxisOffsetFraction: Float,
        movementAxisRegion: RectConfig?,
        crossAxisRegion: RectConfig?,
        allowUnknownShizukuOutcome: Boolean,
    ): CaptureGestureDispatchAttempt {
        val usesShizukuBackend = captureBackendMode == CaptureBackendMode.SHIZUKU
        val shizukuSessionId = if (usesShizukuBackend) {
            ShizukuBridge.reserveLinearSwipeSession()
                ?: return CaptureGestureDispatchAttempt.NotStarted
        } else {
            null
        }
        val executor = captureGestureExecutor.get()
        val task = runCatching {
            executor.submit<CaptureGestureDispatchAttempt> {
                dispatchGesture(
                    template = template,
                    distance = distance,
                    crossAxisOffsetFraction = crossAxisOffsetFraction,
                    movementAxisRegion = movementAxisRegion,
                    crossAxisRegion = crossAxisRegion,
                    allowUnknownShizukuOutcome = allowUnknownShizukuOutcome,
                    shizukuSessionId = shizukuSessionId,
                )
            }
        }.getOrElse { error ->
            Log.w(TAG, "Unable to schedule the capture gesture", error)
            shizukuSessionId?.let(ShizukuBridge::releaseSwipeSession)
            return CaptureGestureDispatchAttempt.NotStarted
        }
        var gestureStarted = !usesShizukuBackend
        return try {
            if (shizukuSessionId != null) {
                gestureStarted = ShizukuBridge.awaitSwipeStarted(
                    shizukuSessionId,
                    CAPTURE_GESTURE_START_TIMEOUT_MS,
                )
                if (!gestureStarted) {
                    // The reserved remote session gives an explicit ACTION_DOWN fence. Do not
                    // report a distance when the worker was merely queued, binding, or rejected.
                    val cancelled = ShizukuBridge.cancelSwipe(shizukuSessionId)
                    task.cancel(true)
                    replaceCaptureGestureExecutor(executor)
                    return if (cancelled) {
                        CaptureGestureDispatchAttempt.NotStarted
                    } else {
                        // A failed cancellation cannot exclude a late ACTION_DOWN, so another
                        // swipe would be unsafe even though the start fence timed out.
                        CaptureGestureDispatchAttempt.FailedAfterStart
                    }
                }
            }
            task.get(CAPTURE_GESTURE_EXECUTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            if (shizukuSessionId != null) {
                ShizukuBridge.cancelSwipe(shizukuSessionId)
            } else {
                GameScrollAccessibilityService.abandonCurrentGestureAndAwait(
                    ACCESSIBILITY_GESTURE_CANCEL_WAIT_MS,
                )
            }
            task.cancel(true)
            replaceCaptureGestureExecutor(executor)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (timeout: TimeoutException) {
            val pointerReleased = if (shizukuSessionId != null) {
                ShizukuBridge.cancelSwipe(shizukuSessionId)
            } else {
                GameScrollAccessibilityService.abandonCurrentGestureAndAwait(
                    ACCESSIBILITY_GESTURE_CANCEL_WAIT_MS,
                )
                true
            }
            task.cancel(true)
            replaceCaptureGestureExecutor(executor)
            Log.w(
                TAG,
                "Capture gesture exceeded its execution timeout; " +
                    "gestureStarted=$gestureStarted pointerReleased=$pointerReleased",
                timeout,
            )
            val observableDistance = observableShizukuGestureDistanceAfterTimeout(
                usesShizukuBackend = usesShizukuBackend,
                gestureStarted = gestureStarted,
                pointerReleased = pointerReleased,
                distancePx = distance,
            )
            if (observableDistance != null) {
                // A remote RPC can outlive the client timeout after ACTION_DOWN or MOVE. Once the
                // idle fence proves the pointer was released, inspect the next frame just like an
                // explicit UNKNOWN outcome instead of ending an otherwise recoverable capture.
                CaptureGestureDispatchAttempt.Dispatched(
                    CaptureGestureDispatch(
                        distancePx = observableDistance,
                        completionConfirmed = false,
                    ),
                )
            } else if (!gestureStarted && pointerReleased) {
                CaptureGestureDispatchAttempt.NotStarted
            } else {
                CaptureGestureDispatchAttempt.FailedAfterStart
            }
        } catch (error: ExecutionException) {
            Log.w(TAG, "Capture gesture failed", error.cause ?: error)
            CaptureGestureDispatchAttempt.FailedAfterStart
        } finally {
            shizukuSessionId?.let(ShizukuBridge::releaseSwipeSession)
        }
    }

    private fun dispatchGesture(
        template: GameCaptureTemplate,
        distance: Int,
        crossAxisOffsetFraction: Float = 0f,
        movementAxisRegion: RectConfig? = null,
        crossAxisRegion: RectConfig? = null,
        allowUnknownShizukuOutcome: Boolean = false,
        shizukuSessionId: Long? = null,
    ): CaptureGestureDispatchAttempt {
        val safeMovementRegion = scrollGestureMovementRegion(
            template = template,
            liveMovementRegion = movementAxisRegion,
        )
        val captureBounds = template.captureRegion.normalized()
        val safeCrossAxisRegion = crossAxisRegion
            ?.normalized()
            ?.intersect(captureBounds)
            ?.takeUnless { it.isEmpty }
            ?: scrollGestureCrossAxisRegion(template)
        val path = resolveTemplateScrollGesturePath(
            template = template,
            requestedDistancePx = distance,
            crossAxisOffsetFraction = crossAxisOffsetFraction,
            movementAxisRegion = safeMovementRegion,
            crossAxisRegion = safeCrossAxisRegion,
        )
        if (captureBackendMode == CaptureBackendMode.SHIZUKU) {
            val sessionId = shizukuSessionId
                ?: return CaptureGestureDispatchAttempt.NotStarted
            val result = ShizukuBridge.performReservedSwipe(
                sessionId = sessionId,
                startX = path.start.x.roundToInt(),
                startY = path.start.y.roundToInt(),
                endX = path.end.x.roundToInt(),
                endY = path.end.y.roundToInt(),
                durationMs = template.gestureDurationMs,
                holdMs = template.gestureStableTimeMs,
            )
            if (result != ShizukuSwipeResult.COMPLETED) {
                Log.w(
                    TAG,
                    "Shizuku swipe result=$result path=${path.start}->${path.end} " +
                        "distance=${path.distancePx} allowUnknown=$allowUnknownShizukuOutcome",
                )
            }
            val dispatchedDistance = dispatchedShizukuGestureDistance(
                result = result,
                distancePx = path.distancePx,
                allowUnknownOutcome = allowUnknownShizukuOutcome,
            )
            return dispatchedDistance?.let { distancePx ->
                CaptureGestureDispatchAttempt.Dispatched(
                    CaptureGestureDispatch(
                        distancePx = distancePx,
                        completionConfirmed = result == ShizukuSwipeResult.COMPLETED,
                    ),
                )
            } ?: CaptureGestureDispatchAttempt.FailedAfterStart
        }
        val complete = CountDownLatch(1)
        val succeeded = AtomicBoolean(false)
        val region = template.gestureRegion
        val request = GameScrollAccessibilityService.Request(
            direction = template.scrollDirection.toGestureDirection(),
            gestureRegion = Rect(region.left, region.top, region.right, region.bottom),
            distancePx = path.distancePx,
                distanceRegion = Rect(
                    template.stitchRegion.left,
                    template.stitchRegion.top,
                    template.stitchRegion.right,
                    template.stitchRegion.bottom,
                ),
                movementAxisRegion = Rect(
                    safeMovementRegion.left,
                    safeMovementRegion.top,
                    safeMovementRegion.right,
                    safeMovementRegion.bottom,
                ),
                crossAxisRegion = Rect(
                    safeCrossAxisRegion.left,
                    safeCrossAxisRegion.top,
                    safeCrossAxisRegion.right,
                    safeCrossAxisRegion.bottom,
                ),
            durationMs = template.gestureDurationMs,
            endpointHoldMs = template.gestureStableTimeMs,
            crossAxisOffsetFraction = crossAxisOffsetFraction,
        )
        val accepted = GameScrollAccessibilityService.dispatch(request) {
            succeeded.set(it)
            complete.countDown()
        }
        if (!accepted) return CaptureGestureDispatchAttempt.NotStarted
        val callbackCompleted = complete.await(
            template.gestureDurationMs + template.gestureStableTimeMs + GESTURE_CALLBACK_GRACE_MS,
            TimeUnit.MILLISECONDS,
        )
        if (!callbackCompleted) {
            val released = runCatching {
                GameScrollAccessibilityService.abandonCurrentGestureAndAwait(
                    ACCESSIBILITY_GESTURE_CANCEL_WAIT_MS,
                )
            }.getOrDefault(false)
            Log.w(
                TAG,
                "Accessibility gesture callback timed out; gesture state released=$released",
            )
        }
        val completed = callbackCompleted && succeeded.get()
        return if (completed) {
            CaptureGestureDispatchAttempt.Dispatched(
                CaptureGestureDispatch(
                    distancePx = path.distancePx,
                    completionConfirmed = true,
                ),
            )
        } else {
            CaptureGestureDispatchAttempt.FailedAfterStart
        }
    }

    private fun cropFrame(bitmap: Bitmap, region: RectConfig): Bitmap {
        val cropped = BitmapFrameUtils.crop(
            bitmap,
            region.clampTo(bitmap.width, bitmap.height),
        )
        return if (cropped === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else cropped
    }

    private fun createResultThumbnail(source: Bitmap): Bitmap {
        val targetWidth = RESULT_THUMBNAIL_WIDTH
        val targetHeight = RESULT_THUMBNAIL_HEIGHT
        val targetRatio = targetWidth.toFloat() / targetHeight
        val sourceRatio = source.width.toFloat() / source.height.coerceAtLeast(1)
        val sourceRect = if (sourceRatio > targetRatio) {
            val cropWidth = (source.height * targetRatio).roundToInt().coerceIn(1, source.width)
            val left = (source.width - cropWidth) / 2
            Rect(left, 0, left + cropWidth, source.height)
        } else {
            val cropHeight = (source.width / targetRatio).roundToInt().coerceIn(1, source.height)
            val top = (source.height - cropHeight) / 2
            Rect(0, top, source.width, top + cropHeight)
        }
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { output ->
            Canvas(output).drawBitmap(
                source,
                sourceRect,
                Rect(0, 0, targetWidth, targetHeight),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private fun maxGestureDistance(template: GameCaptureTemplate): Int {
        return maximumScrollGestureDistance(template.stitchRegion, template.scrollDirection)
    }

    private fun requestStop(message: String) {
        stopRequested.set(true)
        activeRecognitionGesture.get()?.cancel()
        if (jobRunning.get()) {
            transition(CaptureState.CAPTURE_STOP_REQUESTED, message)
        } else {
            stopRequested.set(false)
            transition(
                CaptureState.READY_TO_CAPTURE,
                serviceText(R.string.capture_service_task_stopped_ready),
            )
            overlay.showEntry(template)
            pauseCaptureBackendAsync(jobGeneration.get())
        }
    }

    private fun shouldAbortCaptureAction(
        maximumShizukuSafetyWaitMs: Long = SHIZUKU_CAPTURE_FOREGROUND_QUERY_TIMEOUT_MS,
    ): Boolean = shouldAbortAfterBlockingSafetyCheck(
        stopRequested = stopRequested::get,
        blockingSafetyCheck = {
            stopIfShizukuTargetUnsafe(maximumShizukuSafetyWaitMs)
        },
    )

    private fun exitCaptureSession() = synchronized(jobLifecycleLock) {
        exitRequested.set(true)
        stopRequested.set(true)
        activeRecognitionGesture.get()?.cancel()
        pendingCaptureCompletion.set(null)
        if (jobRunning.get()) {
            transition(
                CaptureState.CAPTURE_STOP_REQUESTED,
                serviceText(R.string.capture_service_ending_capture),
            )
        } else {
            finishExitCaptureSession()
        }
    }

    private fun finishExitCaptureSession() {
        exitRequested.set(false)
        stopRequested.set(true)
        pendingCaptureCompletion.set(null)
        overlay.removeAll()
        releaseCaptureBackendSession()
        transition(
            CaptureState.IDLE,
            serviceText(R.string.capture_service_exited_capture),
            resultSessionId = null,
        )
        stopSelf()
    }

    private fun releaseCaptureBackendSession(
        expectedGeneration: Long = captureBackendGeneration.get(),
    ): Boolean = synchronized(jobLifecycleLock) {
        if (captureBackendGeneration.get() != expectedGeneration) return@synchronized false
        captureBackendGeneration.incrementAndGet()
        val backend = captureBackend
        captureBackend = null
        (backend as? ProjectionCaptureBackend)?.onStopped = null
        runCatching { backend?.close() }
            .onFailure { Log.w(TAG, "Unable to close the capture backend", it) }
        captureBackendLost.set(false)
        deactivateForegroundNotification()
        true
    }

    private fun acceptCaptureBackendWhileForeground(intent: Intent, message: String) {
        // The first call satisfies Android 14's media-projection ordering requirement. Rechecking
        // after backend installation repairs a concurrent old-session teardown without blocking
        // capture status publication on notification Binder work.
        ensureForeground(message)
        acceptCaptureBackend(intent)
        ensureForeground(message)
    }

    private fun acceptCaptureBackend(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.intentExtra(EXTRA_RESULT_DATA)
        val replacementRequested = captureBackendMode == CaptureBackendMode.SHIZUKU ||
            (resultCode == Activity.RESULT_OK && resultData != null)

        synchronized(jobLifecycleLock) {
            if (!isLiveServiceInstance()) return@synchronized
            if (replacementRequested && jobRunning.get()) {
                Log.w(TAG, "Ignoring capture backend replacement while a job is active")
                return@synchronized
            }
            val installedBackendGeneration = if (replacementRequested) {
                captureBackendGeneration.incrementAndGet()
            } else {
                captureBackendGeneration.get()
            }
            if (captureBackendMode == CaptureBackendMode.SHIZUKU) {
                if (captureBackend !is ShizukuCaptureBackend) {
                    val previous = captureBackend
                    captureBackend = null
                    (previous as? ProjectionCaptureBackend)?.onStopped = null
                    runCatching { previous?.close() }
                        .onFailure { Log.w(TAG, "Unable to close the previous capture backend", it) }
                    captureBackend = ShizukuCaptureBackend(this)
                }
                captureBackendLost.set(false)
                return@synchronized
            }
            if (resultCode == Int.MIN_VALUE || resultData == null) {
                if (captureBackend is ShizukuCaptureBackend) {
                    runCatching { captureBackend?.close() }
                    captureBackend = null
                }
                return@synchronized
            }
            check(resultCode == Activity.RESULT_OK) {
                serviceText(R.string.capture_service_user_denied_projection)
            }
            val previous = captureBackend
            captureBackend = null
            (previous as? ProjectionCaptureBackend)?.onStopped = null
            runCatching { previous?.close() }
                .onFailure { Log.w(TAG, "Unable to close the previous capture backend", it) }
            captureBackendLost.set(false)
            val backend = ProjectionCaptureBackend(this)
            captureBackend = backend
            backend.onStopped = {
                handleProjectionBackendStopped(backend, installedBackendGeneration)
            }
            try {
                backend.start(resultCode, resultData)
            } catch (error: Throwable) {
                backend.onStopped = null
                if (captureBackend === backend) {
                    captureBackendGeneration.incrementAndGet()
                    captureBackend = null
                }
                runCatching { backend.close() }
                throw error
            }
        }
    }

    private fun handleProjectionBackendStopped(
        backend: ProjectionCaptureBackend,
        backendGeneration: Long,
    ) {
        synchronized(jobLifecycleLock) {
            if (!isLiveServiceInstance() ||
                captureBackend !== backend ||
                captureBackendGeneration.get() != backendGeneration
            ) {
                return@synchronized
            }
            captureBackendLost.set(true)
            when {
                jobRunning.get() -> {
                    stopRequested.set(true)
                    runCatching {
                        transition(
                            CaptureState.CAPTURE_STOP_REQUESTED,
                            serviceText(R.string.capture_service_projection_stopped_saving),
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to publish projection-loss stop state", error)
                    }
                }
                pendingCaptureCompletion.get() != null -> {
                    // The result is already durable. Preserve its Activity ACK / completion
                    // fallback instead of treating projection teardown as an idle fatal error.
                    runCatching { publish(currentSnapshot.get()) }
                        .onFailure { error ->
                            Log.w(TAG, "Unable to refresh projection-loss completion state", error)
                        }
                }
                else -> scheduleIdleProjectionLossCleanup(backend, backendGeneration)
            }
        }
    }

    private fun scheduleIdleProjectionLossCleanup(
        backend: ProjectionCaptureBackend,
        backendGeneration: Long,
    ) {
        mainHandler.post {
            synchronized(jobLifecycleLock) {
                if (!isLiveServiceInstance() ||
                    captureBackend !== backend ||
                    captureBackendGeneration.get() != backendGeneration ||
                    jobRunning.get() ||
                    pendingCaptureCompletion.get() != null
                ) {
                    return@synchronized
                }
                reportError(serviceText(R.string.capture_service_projection_stopped))
            }
        }
    }

    private fun updateInputs(intent: Intent) {
        if (intent.hasExtra(EXTRA_USE_SHIZUKU)) {
            val requestedMode = if (intent.getBooleanExtra(EXTRA_USE_SHIZUKU, false)) {
                CaptureBackendMode.SHIZUKU
            } else {
                CaptureBackendMode.MEDIA_PROJECTION
            }
            synchronized(jobLifecycleLock) {
                if (!jobRunning.get()) {
                    captureBackendMode = requestedMode
                } else if (captureBackendMode != requestedMode) {
                    Log.w(TAG, "Ignoring capture backend mode change while a job is active")
                }
            }
        }
        val templateId = intent.getLongExtra(EXTRA_TEMPLATE_ID, 0L)
        if (templateId != 0L) templateRepository.getById(templateId)?.let { template = it }
        intent.getStringExtra(EXTRA_TEMPLATE_JSON)?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { ModelJsonCodec.templateFromJson(JSONObject(raw)) }
                .onSuccess { template = it }
                .onFailure { Log.w(TAG, "Ignoring invalid transient template", it) }
        }
        val suppliedTargetName = intent.getStringExtra(EXTRA_TARGET_APP_NAME)
        intent.getStringExtra(EXTRA_TARGET_PACKAGE)?.takeIf { it.isNotBlank() }?.let { targetPackageName ->
            targetApplication = resolveTargetApplication(targetPackageName, suppliedTargetName)
        }
        if (suppliedTargetName != null &&
            suppliedTargetName.isNotBlank() &&
            suppliedTargetName != targetApplication.packageName &&
            targetApplication.packageName.isNotBlank()
        ) {
            targetApplication = targetApplication.copy(appName = suppliedTargetName)
        }
        template?.let(::applyTemplateRuntimeConfig)
        val previewEnabled = intent.takeIf { it.hasExtra(EXTRA_PREVIEW_ENABLED) }
            ?.getBooleanExtra(EXTRA_PREVIEW_ENABLED, true)
        val previewWidthDp = intent.takeIf { it.hasExtra(EXTRA_PREVIEW_WIDTH_DP) }
            ?.getIntExtra(EXTRA_PREVIEW_WIDTH_DP, DefaultCaptureConfig.PREVIEW_INITIAL_WIDTH_DP)
            ?.coerceIn(
                DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
                DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
            )
        val previewMaxHeightDp = intent.takeIf { it.hasExtra(EXTRA_PREVIEW_MAX_HEIGHT_DP) }
            ?.getIntExtra(EXTRA_PREVIEW_MAX_HEIGHT_DP, DefaultCaptureConfig.PREVIEW_MAX_HEIGHT_DP)
            ?.coerceIn(
                DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
                DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
            )
        val previewWidth = previewWidthDp?.let(::dpToPixels) ?: sessionConfig.previewMaxWidth
        val previewMaxHeight = previewMaxHeightDp?.let(::dpToPixels) ?: sessionConfig.previewMaxHeight
        val previewGapDuration = if (intent.hasExtra(EXTRA_PREVIEW_GAP_DURATION_MS)) {
            intent.getIntExtra(
                EXTRA_PREVIEW_GAP_DURATION_MS,
                DefaultCaptureConfig.PREVIEW_GAP_DURATION_MS,
            ).coerceIn(200, 3_000)
        } else sessionConfig.previewGapDurationMs
        val maxOutput = intent.getIntExtra(
            EXTRA_MAX_OUTPUT_HEIGHT,
            sessionConfig.maxOutputHeight,
        ).coerceIn(1_000, DefaultCaptureConfig.MAX_OUTPUT_DIMENSION)
        if (intent.hasExtra(EXTRA_WAIT_AFTER_GESTURE_MS)) {
            pendingWaitAfterGestureMs = intent.getLongExtra(
                EXTRA_WAIT_AFTER_GESTURE_MS,
                DefaultCaptureConfig.WAIT_AFTER_GESTURE_MS,
            ).let(CaptureTimingOptions::normalizeMinimumIntervalMs)
        }
        if (intent.hasExtra(EXTRA_GESTURE_DURATION_MS)) {
            pendingGestureDurationMs = intent.getLongExtra(
                EXTRA_GESTURE_DURATION_MS,
                DefaultCaptureConfig.GESTURE_DURATION_MS,
            ).let(CaptureTimingOptions::normalizeSwipeDurationMs)
        }
        if (intent.hasExtra(EXTRA_SCROLL_DISTANCE_PX)) {
            pendingGestureDistancePx = intent.getIntExtra(
                EXTRA_SCROLL_DISTANCE_PX,
                pendingGestureDistancePx ?: DefaultCaptureConfig.DEFAULT_GESTURE_DISTANCE_PX,
            ).coerceAtLeast(1)
        }
        val waitAfterOverlayHiddenMs = intent.takeIf { it.hasExtra(EXTRA_WAIT_STABLE_MS) }
            ?.getLongExtra(EXTRA_WAIT_STABLE_MS, DefaultCaptureConfig.WAIT_AFTER_OVERLAY_HIDDEN_MS)
            ?.coerceIn(100L, 1_500L)
        val outputFormat = intent.getStringExtra(EXTRA_OUTPUT_FORMAT)?.let { stored ->
            runCatching { ImageOutputFormat.valueOf(stored) }.getOrNull()
        } ?: sessionConfig.outputFormat
        val jpegQuality = if (intent.hasExtra(EXTRA_JPEG_QUALITY)) {
            AppPreferences.normalizeJpegQuality(intent.getIntExtra(EXTRA_JPEG_QUALITY, 90))
        } else sessionConfig.jpegQuality
        sessionConfig = sessionConfig.copy(
            templateId = templateId.takeIf { it != 0L } ?: sessionConfig.templateId,
            maxOutputHeight = maxOutput,
            previewMaxWidth = previewWidth,
            previewMaxHeight = previewMaxHeight,
            outputFormat = outputFormat,
            jpegQuality = jpegQuality,
            previewGapDurationMs = previewGapDuration,
            autoOpenResult = intent.takeIf { it.hasExtra(EXTRA_AUTO_OPEN_RESULT) }
                ?.getBooleanExtra(EXTRA_AUTO_OPEN_RESULT, true)
                ?: sessionConfig.autoOpenResult,
            enablePreviewBetweenScroll = previewEnabled ?: sessionConfig.enablePreviewBetweenScroll,
        )
        template?.let { configured ->
            template = configured.copy(
                waitAfterGestureMs = pendingWaitAfterGestureMs ?: configured.waitAfterGestureMs,
                gestureStableTimeMs = pendingGestureStableTimeMs ?: configured.gestureStableTimeMs,
                gestureDurationMs = pendingGestureDurationMs ?: configured.gestureDurationMs,
                gestureDistancePx = pendingGestureDistancePx ?: configured.gestureDistancePx,
                waitAfterOverlayHiddenMs = waitAfterOverlayHiddenMs
                    ?: configured.waitAfterOverlayHiddenMs,
                previewEnabled = previewEnabled ?: configured.previewEnabled,
                cleanCapture = false,
                previewWidthDp = previewWidthDp ?: configured.previewWidthDp,
                previewMaxHeightDp = previewMaxHeightDp ?: configured.previewMaxHeightDp,
                previewGapDurationMs = previewGapDuration,
                maxOutputHeight = maxOutput,
                keepOriginalFrames = false,
                autoOpenResult = sessionConfig.autoOpenResult,
                outputFormat = outputFormat,
                jpegQuality = jpegQuality,
            )
        }
        overlay.updateOutputOptions(outputFormat, jpegQuality)
    }

    private fun currentDisplaySpec(): MediaProjectionCaptureManager.DisplaySpec {
        // A Service is not a visual Context. Calling Context.display or
        // WindowManager.maximumWindowMetrics from it throws on recent Android
        // versions. DisplayManager returns the physical default display without
        // requiring an Activity/window token, which is exactly the coordinate
        // space used by MediaProjection and accessibility gestures.
        val displayManager = getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        val physicalDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error(serviceText(R.string.capture_service_no_default_display))
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        physicalDisplay.getRealMetrics(metrics)
        return MediaProjectionCaptureManager.DisplaySpec(
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            physicalDisplay.rotation,
        )
    }

    private fun openResult(sessionId: String): Boolean {
        val intent = Intent(ACTION_VIEW_RESULT)
            .setClassName(packageName, RESULT_ACTIVITY_CLASS)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return runCatching { startActivity(intent) }
            .onFailure { error ->
                Log.e(TAG, serviceText(R.string.capture_service_result_saved_open_failed), error)
                runCatching {
                    publish(
                        currentSnapshot.get().copy(
                            message = serviceText(R.string.capture_service_result_saved_open_failed),
                            resultSessionId = sessionId,
                        ),
                    )
                }.onFailure { publishError ->
                    Log.w(TAG, "Unable to publish result-open failure", publishError)
                }
            }
            .isSuccess
    }

    private fun transition(
        state: CaptureState,
        message: String,
        frameCount: Int = currentSnapshot.get().frameCount,
        width: Int = currentSnapshot.get().stitchedWidth,
        height: Int = currentSnapshot.get().stitchedHeight,
        confidence: Float = currentSnapshot.get().confidence,
        resultSessionId: String? = currentSnapshot.get().resultSessionId,
        updateForeground: Boolean = true,
    ) {
        synchronized(stateLock) {
            val previous = currentSnapshot.get().state
            if (!isAllowedTransition(previous, state)) {
                Log.w(TAG, "Non-canonical state transition: $previous -> $state")
            }
            publish(
                Snapshot(
                    state = state,
                    message = message,
                    frameCount = frameCount,
                    stitchedWidth = width,
                    stitchedHeight = height,
                    confidence = confidence,
                    resultSessionId = resultSessionId,
                ),
                updateForeground = updateForeground,
            )
        }
    }

    private fun publishProgress(
        message: String,
        frameCount: Int,
        bitmap: Bitmap,
        confidence: Float,
        logicalWidth: Int = bitmap.width,
        logicalHeight: Int = bitmap.height,
    ) = publish(
        currentSnapshot.get().copy(
            message = message,
            frameCount = frameCount,
            stitchedWidth = logicalWidth,
            stitchedHeight = logicalHeight,
            confidence = confidence,
            updatedAt = System.currentTimeMillis(),
        )
    )

    private fun publish(snapshot: Snapshot, updateForeground: Boolean = true) {
        if (!isLiveServiceInstance()) return
        val fresh = snapshot.copy(updatedAt = System.currentTimeMillis())
        currentSnapshot.set(fresh)
        latestSnapshot.set(fresh)
        val notificationGeneration = if (updateForeground && fresh.state != CaptureState.IDLE) {
            activeForegroundNotificationGeneration()
        } else {
            null
        }
        statusDispatcher.submit(
            CaptureStatusDelivery(
                snapshot = fresh,
                foregroundGeneration = notificationGeneration,
            ),
        )
    }

    private fun deliverCaptureStatus(delivery: CaptureStatusDelivery) {
        if (!isLiveServiceInstance()) return
        val snapshot = delivery.snapshot
        runCatching {
            sendBroadcast(
                Intent(ACTION_STATE_CHANGED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_STATE, snapshot.state.name)
                    .putExtra(EXTRA_MESSAGE, snapshot.message)
                    .putExtra(EXTRA_FRAME_COUNT, snapshot.frameCount)
                    .putExtra(EXTRA_STITCHED_WIDTH, snapshot.stitchedWidth)
                    .putExtra(EXTRA_STITCHED_HEIGHT, snapshot.stitchedHeight)
                    .putExtra(EXTRA_CONFIDENCE, snapshot.confidence)
                    .putExtra(EXTRA_RESULT_SESSION_ID, snapshot.resultSessionId)
                    .putExtra(EXTRA_SERVICE_INSTANCE_ID, serviceInstanceId)
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to broadcast the capture state", error)
        }

        val generation = delivery.foregroundGeneration ?: return
        if (!isForegroundNotificationActive(generation)) return
        val notification = buildNotification(snapshot.message, snapshot)
        synchronized(FOREGROUND_NOTIFICATION_OPERATION_LOCK) {
            if (!isLiveServiceInstance() ||
                NOTIFICATION_OWNER_INSTANCE_ID.get() != serviceInstanceId ||
                !isForegroundNotificationActive(generation)
            ) {
                return
            }
            val startedAtMs = SystemClock.elapsedRealtime()
            Log.d(TAG, "captureNotification notify:start generation=$generation state=${snapshot.state}")
            runCatching {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification,
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to update the capture notification", error)
            }
            Log.d(
                TAG,
                "captureNotification notify:end generation=$generation state=${snapshot.state} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
        }
    }

    private fun reportError(message: String, error: Throwable? = null) {
        error?.let { Log.e(TAG, message, it) }
        if (!isLiveServiceInstance()) return
        stopRequested.set(true)
        activeRecognitionGesture.get()?.cancel()
        val canRetry = captureBackend != null && !captureBackendLost.get() &&
            overlay.canDrawOverlays()
        if (canRetry) {
            val retryStatePublished = runCatching {
                transition(CaptureState.ERROR, message)
            }.onFailure { foregroundError ->
                Log.e(TAG, "无法保持截图服务前台运行，正在退出", foregroundError)
            }.isSuccess
            if (retryStatePublished) {
                // Keep the foreground notification and overlay stop entry while the
                // user adjusts a recoverable gesture/region/stitch problem.
                overlay.showEntry(template)
                return
            }
        }

        runCatching {
            if (currentSnapshot.get().state != CaptureState.ERROR) {
                transition(CaptureState.ERROR, message, updateForeground = false)
            }
        }.onFailure { publishError ->
            Log.e(TAG, "无法发布截图错误状态", publishError)
        }
        try {
            runCatching { overlay.removeAll() }
            runCatching { releaseCaptureBackendSession() }
                .onFailure { releaseError -> Log.e(TAG, "无法完整释放截图后端", releaseError) }
            runCatching {
                transition(
                    CaptureState.IDLE,
                    message,
                    resultSessionId = null,
                    updateForeground = false,
                )
            }.onFailure { publishError ->
                Log.e(TAG, "无法发布截图服务停止状态", publishError)
            }
            val dismissed = Snapshot(state = CaptureState.IDLE, message = message)
            scheduleTerminalNotification(message, dismissed)
        } finally {
            stopSelf()
        }
    }

    private fun ensureForeground(message: String, snapshot: Snapshot = currentSnapshot.get()) {
        if (!isLiveServiceInstance()) return
        preserveTerminalNotificationOnDestroy.set(false)
        val foregroundType = captureForegroundServiceType(
            usesShizukuBackend = captureBackendMode == CaptureBackendMode.SHIZUKU,
            sdkInt = Build.VERSION.SDK_INT,
        )
        val activationGeneration = synchronized(foregroundNotificationLock) {
            if (foregroundStarted && foregroundServiceType == foregroundType) {
                null
            } else {
                foregroundNotificationGeneration.incrementAndGet().also {
                    // Reserve this generation before the Binder call so teardown can invalidate it
                    // without ever waiting for the notification system.
                    foregroundStarted = true
                    foregroundServiceType = foregroundType
                }
            }
        }

        if (activationGeneration == null) {
            activeForegroundNotificationGeneration()?.let { generation ->
                statusDispatcher.submit(
                    CaptureStatusDelivery(
                        snapshot = snapshot.copy(message = message),
                        foregroundGeneration = generation,
                    ),
                )
            }
            return
        }

        val notification = buildNotification(message, snapshot)
        try {
            // Foreground startup must not wait behind a stale instance's vendor notification
            // Binder call. Async notify/remove operations remain serialized and will republish the
            // current owner's state when that call eventually returns.
            if (!isLiveServiceInstance() ||
                NOTIFICATION_OWNER_INSTANCE_ID.get() != serviceInstanceId ||
                !isForegroundNotificationActive(activationGeneration)
            ) {
                return
            }
            startForeground(NOTIFICATION_ID, notification, foregroundType)
        } catch (error: Throwable) {
            val cleanupGeneration = synchronized(foregroundNotificationLock) {
                if (foregroundNotificationGeneration.get() == activationGeneration) {
                    foregroundStarted = false
                    foregroundServiceType = Int.MIN_VALUE
                    foregroundNotificationGeneration.incrementAndGet()
                } else {
                    null
                }
            }
            cleanupGeneration?.let(::scheduleForegroundRemoval)
            throw error
        }
    }

    private fun activeForegroundNotificationGeneration(): Long? =
        synchronized(foregroundNotificationLock) {
            foregroundNotificationGeneration.get().takeIf { foregroundStarted }
        }

    private fun isForegroundNotificationActive(generation: Long): Boolean =
        synchronized(foregroundNotificationLock) {
            foregroundStarted && foregroundNotificationGeneration.get() == generation
        }

    private fun deactivateForegroundNotification(forceGeneration: Boolean = false) {
        val cleanupGeneration = synchronized(foregroundNotificationLock) {
            if (!forceGeneration && !foregroundStarted && foregroundServiceType == Int.MIN_VALUE) {
                null
            } else {
                foregroundNotificationGeneration.incrementAndGet().also {
                    foregroundStarted = false
                    foregroundServiceType = Int.MIN_VALUE
                }
            }
        }
        cleanupGeneration?.let(::scheduleForegroundRemoval)
    }

    private fun scheduleForegroundRemoval(generation: Long) {
        runCatching {
            notificationExecutor.execute {
                val ownerChangedDuringRemoval = synchronized(FOREGROUND_NOTIFICATION_OPERATION_LOCK) {
                    if (NOTIFICATION_OWNER_INSTANCE_ID.get() != serviceInstanceId) {
                        return@synchronized false
                    }
                    val stillInactive = synchronized(foregroundNotificationLock) {
                        !foregroundStarted && foregroundNotificationGeneration.get() == generation
                    }
                    if (!stillInactive) return@synchronized false
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                        .onFailure { error ->
                            Log.w(TAG, "Unable to remove the capture foreground notification", error)
                        }
                    NOTIFICATION_OWNER_INSTANCE_ID.get() != serviceInstanceId
                }
                if (ownerChangedDuringRemoval) requestActiveForegroundReassertion()
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to schedule foreground notification removal", error)
        }
    }

    private fun requestActiveForegroundReassertion() {
        ACTIVE_SERVICE_INSTANCE.get()?.reassertForegroundAsync()
    }

    private fun reassertForegroundAsync() {
        mainHandler.post {
            if (!isLiveServiceInstance()) return@post
            val activeForeground = synchronized(foregroundNotificationLock) {
                if (!foregroundStarted) {
                    null
                } else {
                    foregroundNotificationGeneration.get() to foregroundServiceType
                }
            } ?: return@post
            val (generation, foregroundType) = activeForeground
            if (NOTIFICATION_OWNER_INSTANCE_ID.get() != serviceInstanceId ||
                !isForegroundNotificationActive(generation)
            ) {
                return@post
            }
            val snapshot = currentSnapshot.get()
            val notification = buildNotification(snapshot.message, snapshot)
            runCatching {
                startForeground(NOTIFICATION_ID, notification, foregroundType)
            }.onFailure { error ->
                Log.w(TAG, "Unable to restore foreground state after stale removal", error)
            }
        }
    }

    private fun scheduleTerminalNotification(message: String, snapshot: Snapshot) {
        val generation = synchronized(foregroundNotificationLock) {
            foregroundNotificationGeneration.get().takeIf { !foregroundStarted }
        } ?: return
        preserveTerminalNotificationOnDestroy.set(true)
        runCatching {
            notificationExecutor.execute {
                val notification = buildNotification(message, snapshot)
                synchronized(FOREGROUND_NOTIFICATION_OPERATION_LOCK) {
                    if (NOTIFICATION_OWNER_INSTANCE_ID.get() != serviceInstanceId) {
                        return@execute
                    }
                    val stillInactive = synchronized(foregroundNotificationLock) {
                        !foregroundStarted && foregroundNotificationGeneration.get() == generation
                    }
                    if (!stillInactive) return@execute
                    runCatching {
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            notification,
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "Unable to publish the final capture notification", error)
                    }
                }
            }
        }.onFailure { error ->
            preserveTerminalNotificationOnDestroy.set(false)
            Log.w(TAG, "Unable to schedule the final capture notification", error)
        }
    }

    private fun isLiveServiceInstance(): Boolean =
        !serviceDestroyed.get() && ACTIVE_SERVICE_INSTANCE_ID.get() == serviceInstanceId

    private fun buildNotification(message: String, snapshot: Snapshot): Notification {
        val taskActive = snapshot.state in setOf(
            CaptureState.REGION_LEARNING_PREPARE,
            CaptureState.REGION_LEARNING_SCROLL,
            CaptureState.REGION_LEARNING_CAPTURE,
            CaptureState.REGION_LEARNING_ANALYZE,
            CaptureState.CAPTURE_PREPARE,
            CaptureState.CAPTURE_SCROLL,
            CaptureState.CAPTURE_WAIT_STABLE,
            CaptureState.CAPTURE_FRAME,
            CaptureState.CAPTURE_PROCESS,
            CaptureState.CAPTURE_SHOW_CONTROL,
            CaptureState.CAPTURE_STOP_REQUESTED,
            CaptureState.CAPTURE_FINISHING,
        )
        val controlIntent = Intent(this, GameLongShotCaptureService::class.java).apply {
            action = if (taskActive) ACTION_STOP_CAPTURE else ACTION_EXIT_CAPTURE
        }
        val controlPending = PendingIntent.getService(
            this,
            2,
            controlIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val contentIntent = snapshot.resultSessionId?.let { sessionId ->
            Intent(ACTION_VIEW_RESULT)
                .setClassName(packageName, RESULT_ACTIVITY_CLASS)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } ?: Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val openPending = PendingIntent.getActivity(
            this,
            1,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificationTitle = if (snapshot.frameCount > 0) {
            serviceText(
                R.string.capture_service_notification_title_with_frames,
                snapshot.frameCount,
            )
        } else {
            serviceText(R.string.capture_service_notification_title)
        }
        // This runs on the notification executor after foreground startup. Do not add a Shizuku
        // Binder health check here; vendor Binder calls may remain blocked while the target app is
        // foregrounded.
        val ongoing = captureBackend != null && !captureBackendLost.get() &&
            snapshot.state != CaptureState.IDLE
        val builder = Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(notificationTitle)
            .setContentText(message)
            .setContentIntent(openPending)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (ongoing) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    serviceText(
                        if (taskActive) {
                            R.string.capture_service_notification_stop
                        } else {
                            R.string.capture_service_notification_exit
                        },
                    ),
                    controlPending,
                ).build()
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            serviceText(R.string.capture_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = serviceText(R.string.capture_notification_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun sleepInterruptibly(durationMs: Long) {
        var remaining = durationMs.coerceAtLeast(0L)
        while (remaining > 0L && !stopRequested.get()) {
            val slice = minOf(remaining, 80L)
            Thread.sleep(slice)
            remaining -= slice
        }
    }

    private fun isAllowedTransition(from: CaptureState, to: CaptureState): Boolean {
        if (from == to || to == CaptureState.ERROR || to == CaptureState.CAPTURE_STOP_REQUESTED) return true
        return to in (ALLOWED_TRANSITIONS[from] ?: emptySet())
    }

    private fun ScrollDirection.toGestureDirection() = when (this) {
        ScrollDirection.UP -> GameScrollAccessibilityService.Direction.UP
        ScrollDirection.DOWN -> GameScrollAccessibilityService.Direction.DOWN
        ScrollDirection.LEFT -> GameScrollAccessibilityService.Direction.LEFT
        ScrollDirection.RIGHT -> GameScrollAccessibilityService.Direction.RIGHT
    }

    private fun Intent.intentExtra(key: String): Intent? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

    private fun Throwable.userMessage(fallback: String): String = when (this) {
        CaptureBackendException.ProjectionFrameUnavailable -> serviceText(
            R.string.capture_service_backend_projection_frame_unavailable,
        )
        CaptureBackendException.ShizukuUnavailable -> serviceText(
            R.string.capture_service_backend_shizuku_unavailable,
        )
        is CaptureBackendException.ShizukuSizeMismatch -> serviceText(
            R.string.capture_service_backend_size_mismatch,
            actual,
            expected,
        )
        else -> currentLocalizedServiceContext().localizedMessageOrFallback(message, fallback)
    }

    private fun localizedMessageForAppearanceRefresh(state: CaptureState): String = when (state) {
        CaptureState.PERMISSION_CHECK -> serviceText(R.string.capture_service_checking_environment)
        CaptureState.REGION_CONFIG -> serviceText(R.string.capture_service_confirm_regions_to_start)
        CaptureState.READY_TO_CAPTURE -> serviceText(R.string.capture_service_configuration_applied)
        CaptureState.REGION_LEARNING_PREPARE ->
            serviceText(R.string.capture_service_preparing_auto_learning)
        CaptureState.REGION_LEARNING_SCROLL ->
            serviceText(R.string.capture_service_short_sampling_swipe)
        CaptureState.REGION_LEARNING_CAPTURE ->
            serviceText(R.string.capture_service_preparing_region_learning)
        CaptureState.REGION_LEARNING_ANALYZE ->
            serviceText(R.string.capture_service_generating_region_suggestion)
        CaptureState.REGION_RESULT_CONFIRM -> serviceText(R.string.capture_service_learning_complete)
        CaptureState.CAPTURE_PREPARE ->
            serviceText(R.string.capture_service_removing_overlay_and_checking_navigation)
        CaptureState.CAPTURE_WAIT_STABLE ->
            serviceText(R.string.capture_service_waiting_for_stable_frame)
        CaptureState.CAPTURE_FRAME -> serviceText(R.string.capture_service_getting_clean_frame)
        CaptureState.CAPTURE_PROCESS -> serviceText(R.string.capture_service_matching_overlap)
        CaptureState.CAPTURE_STOP_REQUESTED -> serviceText(R.string.capture_service_stopping_safely)
        CaptureState.CAPTURE_FINISHING -> serviceText(R.string.capture_service_ending_capture)
        CaptureState.RESULT_EDIT -> serviceText(R.string.capture_service_saved_ready_again)
        CaptureState.EXPORTING -> serviceText(R.string.capture_service_saving_layered_screenshot)
        CaptureState.ERROR -> serviceText(R.string.capture_service_start_failed)
        CaptureState.IDLE,
        CaptureState.CAPTURE_SCROLL,
        CaptureState.CAPTURE_SHOW_CONTROL -> serviceText(R.string.capture_service_running)
    }

    private fun localizedStitchRejectionMessage(
        rejectionReason: StitchRejectionReason?,
        fallback: String?,
    ): String? {
        val messageResource = when (rejectionReason) {
            StitchRejectionReason.NO_OVERLAP -> R.string.capture_service_stitch_no_overlap
            StitchRejectionReason.DUPLICATE -> R.string.capture_service_stitch_duplicate
            StitchRejectionReason.WRONG_DIRECTION -> R.string.capture_service_stitch_wrong_direction
            StitchRejectionReason.OVERLAP_TOO_LOW -> R.string.capture_service_stitch_overlap_too_low
            StitchRejectionReason.OVERLAP_TOO_HIGH -> R.string.capture_service_stitch_overlap_too_high
            StitchRejectionReason.UNEXPECTED_SHIFT -> R.string.capture_service_stitch_unexpected_shift
            StitchRejectionReason.LOW_CONFIDENCE -> R.string.capture_service_stitch_low_confidence
            null -> null
        }
        if (messageResource != null) return serviceText(messageResource)
        return fallback?.takeIf(String::isNotBlank)?.let { detail ->
            currentLocalizedServiceContext().localizedMessageOrFallback(
                detail,
                serviceText(R.string.capture_service_low_confidence_skipped),
            )
        }
    }

    private fun serviceText(@StringRes resourceId: Int, vararg formatArgs: Any): String {
        val context = currentLocalizedServiceContext()
        return if (formatArgs.isEmpty()) {
            context.getString(resourceId)
        } else {
            context.getString(resourceId, *formatArgs)
        }
    }

    private fun currentLocalizedServiceContext(): Context {
        val language = appPreferences.loadAppLanguage()
        localizedServiceContext?.takeIf { localizedServiceLanguage == language }?.let { return it }
        return synchronized(localizationLock) {
            localizedServiceContext?.takeIf { localizedServiceLanguage == language }
                ?: AppLocaleManager.localizedContext(this, language).also { localized ->
                    localizedServiceLanguage = language
                    localizedServiceContext = localized
                }
        }
    }

    companion object {
        private const val TAG = "GameLongShotService"
        private const val NOTIFICATION_CHANNEL_ID = "game_long_shot_capture"
        private const val NOTIFICATION_ID = 4107
        private const val RESULT_ACTIVITY_CLASS =
            "com.apricotiris.IrisShot.result.ResultEditorActivity"
        private const val FRAME_TIMEOUT_MS = 2_500L
        private const val CAPTURE_FRAME_HARD_TIMEOUT_GRACE_MS = 250L
        private const val OVERLAY_REMOVE_TIMEOUT_MS = 1_200L
        private const val OVERLAY_KEEPALIVE_ATTACH_TIMEOUT_MS = 1_200L
        private const val OVERLAY_KEEPALIVE_HANDOFF_DELAY_MS = 1_500L
        // The reserved Shizuku session is bound before this timeout starts. Allow the full
        // configured motion/hold plus remote completion cleanup after ACTION_DOWN is confirmed.
        private const val CAPTURE_GESTURE_EXECUTION_TIMEOUT_MS = 16_000L
        private const val CAPTURE_GESTURE_START_TIMEOUT_MS = 2_000L
        private const val CAPTURE_GESTURE_START_RETRY_COUNT = 2
        private const val CAPTURE_GESTURE_START_RETRY_DELAY_MS = 220L
        private const val CAPTURE_GESTURE_START_RETRY_WINDOW_MS = 4_000L
        private const val CAPTURE_INPUT_HANDOFF_DELAY_MS = 80L
        private const val JOB_WAKE_LOCK_SAFETY_TIMEOUT_MS = 30L * 60L * 1_000L
        private const val GESTURE_CALLBACK_GRACE_MS = 1_800L
        private const val ACCESSIBILITY_GESTURE_CANCEL_WAIT_MS = 900L
        private const val OVERLAY_BACK_DEBOUNCE_MS = 250L
        private const val EXPANDED_OVERLAY_BASELINE_MAX_AGE_MS = 1_500L
        private const val OVERLAY_OPEN_TARGET_REFRESH_TIMEOUT_MS = 700L
        private const val SHIZUKU_CAPTURE_FOREGROUND_CACHE_MAX_AGE_MS = 1_500L
        private const val SHIZUKU_CAPTURE_FOREGROUND_QUERY_TIMEOUT_MS = 600L
        private const val SHIZUKU_CAPTURE_FOREGROUND_LOCK_TIMEOUT_MS = 120L
        private const val SHIZUKU_SLOW_SAFETY_CHECK_LOG_MS = 400L
        private const val SHIZUKU_FOREGROUND_UNKNOWN_GRACE_MS = 3_000L
        private const val SHIZUKU_OVERLAY_OPEN_INPUT_TIMEOUT_MS = 100L
        private const val SHIZUKU_FOREGROUND_POLL_PERIOD_MS = 140L
        private const val SHIZUKU_FOREGROUND_POLL_TIMEOUT_MS = 700L
        private const val SHIZUKU_ACTIVITY_VERIFICATION_CYCLES = 6
        private const val SYSTEM_DIALOG_REASON_EXTRA = "reason"
        private const val SHIZUKU_SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RESULT_THUMBNAIL_WIDTH = 336
        private const val RESULT_THUMBNAIL_HEIGHT = 240
        private const val DYNAMIC_MATCH_RETRY_DELAY_MS = 180L
        private const val DYNAMIC_MATCH_RETRY_COUNT = 2
        private const val ACCEPTED_MATCH_STABILITY_CONFIRMATION_DELAY_MS = 350L
        private const val LEARNING_SAMPLE_MAX_DIMENSION = 1280
        private const val RECOGNITION_FRAME_TIMEOUT_MS = 1_500L
        private const val RECOGNITION_SAMPLE_MAX_DIMENSION = 960
        private const val RECOGNITION_MAX_SAMPLE_COUNT = 25
        private const val RECOGNITION_FRAME_INTERVAL_MS = 240L
        private const val RECOGNITION_MIN_RETRY_DELAY_MS = 50L
        private const val RECOGNITION_ACCESSIBILITY_OVERLAY_ATTACH_DELAY_MS = 120L
        private const val RECOGNITION_SAMPLING_OVERRUN_GRACE_MS = 300L
        private const val RECOGNITION_OVERLAY_DRAW_TIMEOUT_MS = 1_200L
        private const val RECOGNITION_GESTURE_START_TIMEOUT_MS = 2_000L
        // Shizuku cancellation can wait for a 3.5 s cancel RPC and a 4 s release owner.
        private const val RECOGNITION_GESTURE_FINISH_GRACE_MS = 8_000L
        private const val RECOGNITION_NATURAL_CALLBACK_GRACE_MS = 250L
        private const val RECOGNITION_CLEANUP_GRACE_MS = 2_500L
        private const val RECOGNITION_FINAL_CLEAN_FRAME_DELAY_MS = 100L
        private const val END_SHIFT_THRESHOLD_PX = 4
        // Try the centre and both alternate cross-axis lanes before treating no motion as the end.
        private const val LOW_CONFIDENCE_THRESHOLD = .65f
        private const val SMALL_STEP_SOFT_ACCEPT_CONFIDENCE = .65f

        const val ACTION_START_OVERLAY =
            "com.apricotiris.IrisShot.action.START_OVERLAY"
        const val ACTION_SHOW_CONFIG =
            "com.apricotiris.IrisShot.action.SHOW_CONFIG"
        const val ACTION_START_CAPTURE =
            "com.apricotiris.IrisShot.action.START_CAPTURE"
        const val ACTION_LEARN_REGION =
            "com.apricotiris.IrisShot.action.LEARN_REGION"
        const val ACTION_UPDATE_CONFIG =
            "com.apricotiris.IrisShot.action.UPDATE_CONFIG"
        const val ACTION_STOP_CAPTURE =
            "com.apricotiris.IrisShot.action.STOP_CAPTURE"
        const val ACTION_EXIT_CAPTURE =
            "com.apricotiris.IrisShot.action.EXIT_CAPTURE"
        const val ACTION_STATE_CHANGED =
            "com.apricotiris.IrisShot.action.STATE_CHANGED"
        const val ACTION_VIEW_RESULT =
            "com.apricotiris.IrisShot.action.VIEW_RESULT"
        const val ACTION_RESULT_OPENED =
            "com.apricotiris.IrisShot.action.RESULT_OPENED"

        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_USE_SHIZUKU = "use_shizuku"
        const val EXTRA_TEMPLATE_ID = "template_id"
        const val EXTRA_TEMPLATE_JSON = "template_json"
        const val EXTRA_TARGET_PACKAGE = "target_package"
        const val EXTRA_TARGET_APP_NAME = "target_app_name"
        const val EXTRA_PREVIEW_ENABLED = "preview_enabled"
        const val EXTRA_PREVIEW_WIDTH_DP = "preview_width_dp"
        const val EXTRA_PREVIEW_MAX_HEIGHT_DP = "preview_max_height_dp"
        const val EXTRA_PREVIEW_GAP_DURATION_MS = "preview_gap_duration_ms"
        const val EXTRA_WAIT_AFTER_GESTURE_MS = "wait_after_gesture_ms"
        const val EXTRA_GESTURE_DURATION_MS = "gesture_duration_ms"
        const val EXTRA_WAIT_STABLE_MS = "wait_stable_ms"
        const val EXTRA_SCROLL_DISTANCE_PX = "scroll_distance_px"
        const val EXTRA_MAX_OUTPUT_HEIGHT = "max_output_height"
        const val EXTRA_KEEP_TEMP_FILES_ON_FAILURE = "keep_temp_files_on_failure"
        const val EXTRA_AUTO_OPEN_RESULT = "auto_open_result"
        const val EXTRA_OUTPUT_FORMAT = "output_format"
        const val EXTRA_JPEG_QUALITY = "jpeg_quality"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_FRAME_COUNT = "frame_count"
        const val EXTRA_STITCHED_WIDTH = "stitched_width"
        const val EXTRA_STITCHED_HEIGHT = "stitched_height"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_RESULT_SESSION_ID = "result_session_id"
        const val EXTRA_SERVICE_INSTANCE_ID = "service_instance_id"

        private const val AUTO_OPEN_RESULT_FALLBACK_DELAY_MS = 1_500L

        private val latestSnapshot = AtomicReference(Snapshot())
        fun latestSnapshot(): Snapshot = latestSnapshot.get()

        @Volatile private var isServiceRunning = false
        fun isRunning(): Boolean = isServiceRunning
        internal fun activeServiceInstanceId(): Long = ACTIVE_SERVICE_INSTANCE_ID.get()

        private val SERVICE_INSTANCE_COUNTER = AtomicLong(0L)
        private val ACTIVE_SERVICE_INSTANCE_ID = AtomicLong(0L)
        private val ACTIVE_SERVICE_INSTANCE = AtomicReference<GameLongShotCaptureService?>(null)
        private val NOTIFICATION_OWNER_INSTANCE_ID = AtomicLong(0L)
        private val FOREGROUND_NOTIFICATION_OPERATION_LOCK = Any()
        private val CAPTURE_INTERACTION_OWNER_GATE = CaptureInteractionOwnerGate(
            GameScrollAccessibilityService::setCaptureActive,
        )

        private val ALLOWED_TRANSITIONS: Map<CaptureState, Set<CaptureState>> = mapOf(
            CaptureState.IDLE to setOf(CaptureState.PERMISSION_CHECK, CaptureState.REGION_CONFIG),
            CaptureState.PERMISSION_CHECK to setOf(CaptureState.REGION_CONFIG),
            CaptureState.REGION_CONFIG to setOf(
                CaptureState.READY_TO_CAPTURE,
                CaptureState.REGION_LEARNING_PREPARE,
                CaptureState.CAPTURE_PREPARE,
                CaptureState.IDLE,
            ),
            CaptureState.READY_TO_CAPTURE to setOf(
                CaptureState.REGION_CONFIG,
                CaptureState.REGION_LEARNING_PREPARE,
                CaptureState.CAPTURE_PREPARE,
                CaptureState.IDLE,
            ),
            CaptureState.REGION_LEARNING_PREPARE to setOf(
                CaptureState.REGION_LEARNING_SCROLL,
                CaptureState.REGION_LEARNING_ANALYZE,
            ),
            CaptureState.REGION_LEARNING_SCROLL to setOf(CaptureState.REGION_LEARNING_CAPTURE),
            CaptureState.REGION_LEARNING_CAPTURE to setOf(
                CaptureState.REGION_LEARNING_SCROLL,
                CaptureState.REGION_LEARNING_ANALYZE,
            ),
            CaptureState.REGION_LEARNING_ANALYZE to setOf(CaptureState.REGION_RESULT_CONFIRM),
            CaptureState.REGION_RESULT_CONFIRM to setOf(
                CaptureState.REGION_CONFIG,
                CaptureState.READY_TO_CAPTURE,
                CaptureState.IDLE,
            ),
            CaptureState.CAPTURE_PREPARE to setOf(CaptureState.CAPTURE_SCROLL),
            CaptureState.CAPTURE_SCROLL to setOf(CaptureState.CAPTURE_WAIT_STABLE),
            CaptureState.CAPTURE_WAIT_STABLE to setOf(CaptureState.CAPTURE_FRAME),
            CaptureState.CAPTURE_FRAME to setOf(CaptureState.CAPTURE_PROCESS),
            CaptureState.CAPTURE_PROCESS to setOf(
                CaptureState.CAPTURE_SHOW_CONTROL,
                CaptureState.CAPTURE_SCROLL,
                CaptureState.CAPTURE_FINISHING,
            ),
            CaptureState.CAPTURE_SHOW_CONTROL to setOf(
                CaptureState.CAPTURE_SCROLL,
                CaptureState.CAPTURE_FINISHING,
            ),
            CaptureState.CAPTURE_STOP_REQUESTED to setOf(
                CaptureState.CAPTURE_FINISHING,
                CaptureState.REGION_CONFIG,
                CaptureState.READY_TO_CAPTURE,
                CaptureState.IDLE,
            ),
            CaptureState.CAPTURE_FINISHING to setOf(CaptureState.RESULT_EDIT),
            CaptureState.RESULT_EDIT to setOf(
                CaptureState.IDLE,
                CaptureState.PERMISSION_CHECK,
                CaptureState.REGION_CONFIG,
                CaptureState.READY_TO_CAPTURE,
            ),
            CaptureState.EXPORTING to setOf(CaptureState.RESULT_EDIT),
            CaptureState.ERROR to setOf(
                CaptureState.IDLE,
                CaptureState.PERMISSION_CHECK,
                CaptureState.REGION_CONFIG,
                CaptureState.CAPTURE_PREPARE,
                CaptureState.REGION_LEARNING_PREPARE,
            ),
        )
    }
}

internal fun chooseCaptureAppName(
    resolvedName: String?,
    packageName: String?,
    templateAppName: String?,
    templatePackageName: String?,
    fallbackName: String,
): String = resolvedName
    ?.trim()
    ?.takeIf { it.isNotEmpty() && it != packageName }
    ?: templateAppName
        ?.trim()
        ?.takeIf {
            it.isNotEmpty() &&
                it != packageName &&
                (templatePackageName.isNullOrBlank() || templatePackageName == packageName)
        }
    ?: fallbackName
