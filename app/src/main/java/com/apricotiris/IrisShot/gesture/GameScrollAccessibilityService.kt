package com.apricotiris.IrisShot.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.apricotiris.IrisShot.core.app.ForegroundObservationKind
import com.apricotiris.IrisShot.core.app.ForegroundWindowObservation
import com.apricotiris.IrisShot.core.model.CaptureTimingOptions
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.ScrollDirection
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal data class ScrollGesturePhase(
    val startFraction: Float,
    val endFraction: Float,
    val durationMs: Long,
)

internal data class PageStructureToken(
    val depth: Int,
    val siblingIndex: Int,
    val className: String,
    val viewId: String?,
    val childCount: Int,
    val stateFlags: Int,
    val stableLabel: String?,
)

internal fun acceptsPageContentChange(changeTypes: Int): Boolean =
    changeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED ||
        changeTypes and PAGE_LEVEL_CONTENT_CHANGE_MASK != 0

internal fun shouldCollectPageStructureSignature(
    hasForegroundListeners: Boolean,
    captureActive: Boolean,
): Boolean = hasForegroundListeners && !captureActive

internal fun canSamplePageStructureWindow(
    latestPackageName: String,
    ownPackageName: String,
): Boolean = latestPackageName.trim().isNotEmpty() &&
    latestPackageName.trim() != ownPackageName.trim()

internal class GestureOwnershipGate<T : Any> {
    internal class Claim<T : Any>(
        val owner: T,
        val generation: Long,
        val completion: (Boolean) -> Unit,
    ) {
        val cancellationRequested = AtomicBoolean(false)
        val finished = CountDownLatch(1)
    }

    private val nextGeneration = AtomicLong(1L)
    private val active = AtomicReference<Claim<T>?>(null)

    fun acquire(owner: T, completion: (Boolean) -> Unit): Claim<T>? {
        val claim = Claim(
            owner = owner,
            generation = nextGeneration.getAndUpdate { current ->
                if (current == Long.MAX_VALUE) 1L else current + 1L
            },
            completion = completion,
        )
        return claim.takeIf { active.compareAndSet(null, it) }
    }

    fun isActive(claim: Claim<T>): Boolean = active.get() === claim

    fun currentForOwner(owner: T): Claim<T>? =
        active.get()?.takeIf { it.owner === owner }

    fun requestCancellation(claim: Claim<T>): Boolean =
        isActive(claim) && claim.cancellationRequested.compareAndSet(false, true)

    fun finish(claim: Claim<T>, success: Boolean): Boolean {
        if (!active.compareAndSet(claim, null)) return false
        try {
            claim.completion(success)
        } finally {
            claim.finished.countDown()
        }
        return true
    }

    fun awaitFinished(claim: Claim<T>, timeoutMs: Long): Boolean {
        if (!isActive(claim)) return true
        return claim.finished.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    fun awaitFinishedOrForceRelease(claim: Claim<T>, timeoutMs: Long): Boolean {
        if (awaitFinished(claim, timeoutMs)) return true
        finish(claim, false)
        return !isActive(claim)
    }

    fun abandonOwner(owner: T): Boolean {
        val claim = currentForOwner(owner) ?: return false
        return finish(claim, false)
    }
}

internal fun isCompactApplicationWindowBounds(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    screenWidth: Int,
    screenHeight: Int,
): Boolean {
    val width = (right - left).coerceAtLeast(0)
    val height = (bottom - top).coerceAtLeast(0)
    if (width == 0 || height == 0 || screenWidth <= 0 || screenHeight <= 0) return false
    return width.toLong() * 100L < screenWidth.toLong() * 90L ||
        height.toLong() * 100L < screenHeight.toLong() * 90L
}

internal fun buildPageStructureSignature(tokens: List<PageStructureToken>): String? {
    if (tokens.isEmpty()) return null
    val digest = MessageDigest.getInstance("SHA-256")
    tokens.forEach { token ->
        val serialized = buildString {
            append(token.depth)
            append('|')
            append(token.siblingIndex)
            append('|')
            append(token.className)
            append('|')
            append(token.viewId.orEmpty())
            append('|')
            append(token.childCount)
            append('|')
            append(token.stateFlags)
            append('|')
            append(token.stableLabel.orEmpty())
            append('\n')
        }
        digest.update(serialized.toByteArray(StandardCharsets.UTF_8))
    }
    val bytes = digest.digest()
    val hex = CharArray(PAGE_SIGNATURE_DIGEST_BYTES * 2)
    repeat(PAGE_SIGNATURE_DIGEST_BYTES) { index ->
        val value = bytes[index].toInt() and 0xFF
        hex[index * 2] = HEX_DIGITS[value ushr 4]
        hex[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
    }
    return "tree-v1:${String(hex)}"
}

private const val PAGE_LEVEL_CONTENT_CHANGE_MASK =
    AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or
        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED or
        AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE
private const val PAGE_SIGNATURE_DIGEST_BYTES = 16
private const val HEX_DIGITS = "0123456789abcdef"

/** Progressive ease-out followed by a stationary hold before ACTION_UP. */
internal fun buildEasedScrollGesturePhases(
    requestedDurationMs: Long,
    endpointHoldMs: Long = GameScrollAccessibilityService.RELEASE_HOLD_DURATION_MS,
): List<ScrollGesturePhase> {
    val movementDuration = requestedDurationMs.coerceIn(
        CaptureTimingOptions.MIN_SWIPE_DURATION_MS,
        CaptureTimingOptions.MAX_SWIPE_DURATION_MS,
    )
    val first = (movementDuration * 0.40f).toLong().coerceAtLeast(1L)
    val second = (movementDuration * 0.30f).toLong().coerceAtLeast(1L)
    val third = (movementDuration - first - second).coerceAtLeast(1L)
    return listOf(
        ScrollGesturePhase(0f, 0.65f, first),
        ScrollGesturePhase(0.65f, 0.90f, second),
        ScrollGesturePhase(0.90f, 1f, third),
        ScrollGesturePhase(
            1f,
            1f,
            endpointHoldMs.coerceIn(100L, 1_500L),
        ),
    )
}

/** A constant-speed stroke split into short continued phases so cancellation can stop it. */
internal fun buildLinearScrollGesturePhases(
    requestedDurationMs: Long,
    endpointHoldMs: Long = 0L,
): List<ScrollGesturePhase> {
    val movementDuration = requestedDurationMs.coerceIn(
        MIN_LINEAR_RECOGNITION_DURATION_MS,
        MAX_LINEAR_RECOGNITION_DURATION_MS,
    )
    val phaseCount = (
        (movementDuration + LINEAR_RECOGNITION_PHASE_MAX_DURATION_MS - 1L) /
            LINEAR_RECOGNITION_PHASE_MAX_DURATION_MS
        ).toInt().coerceAtLeast(1)
    return buildList {
        repeat(phaseCount) { index ->
            val startElapsed = movementDuration * index / phaseCount
            val endElapsed = movementDuration * (index + 1) / phaseCount
            add(
                ScrollGesturePhase(
                    startFraction = startElapsed.toFloat() / movementDuration,
                    endFraction = endElapsed.toFloat() / movementDuration,
                    durationMs = (endElapsed - startElapsed).coerceAtLeast(1L),
                ),
            )
        }
        if (endpointHoldMs > 0L) {
            add(
                ScrollGesturePhase(
                    1f,
                    1f,
                    endpointHoldMs.coerceIn(1L, 1_500L),
                ),
            )
        }
    }
}

private const val MIN_LINEAR_RECOGNITION_DURATION_MS = 1_000L
private const val MAX_LINEAR_RECOGNITION_DURATION_MS = 10_000L
private const val LINEAR_RECOGNITION_PHASE_MAX_DURATION_MS = 500L

/**
 * Injects capture gestures and reports stable foreground-page changes.
 */
class GameScrollAccessibilityService : AccessibilityService() {

    fun interface ForegroundWindowListener {
        fun onForegroundWindowChanged(observation: ForegroundWindowObservation)
    }

    fun interface NavigationKeyListener {
        fun onNavigationKeyPressed(keyCode: Int)
    }

    fun interface ConnectionListener {
        fun onConnectionChanged(connected: Boolean)
    }

    private val gestureHandler = Handler(Looper.getMainLooper())
    private val pageProbeHandler = Handler(Looper.getMainLooper())
    private val platformGestureExecutor = AtomicReference(newPlatformGestureExecutor())
    private val pageProbeSequences = mutableMapOf<PageProbeKey, PageProbeSequence>()

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    data class Request(
        val direction: Direction,
        val gestureRegion: Rect,
        val distancePx: Int,
        val durationMs: Long = DefaultCaptureConfig.GESTURE_DURATION_MS,
        val endpointHoldMs: Long = RELEASE_HOLD_DURATION_MS,
        /** Region whose movement axis determines the configured swipe distance. */
        val distanceRegion: Rect = Rect(gestureRegion),
        /** Live surface that constrains the movement axis without changing the perpendicular lane. */
        val movementAxisRegion: Rect = Rect(distanceRegion),
        /** Region available to alternate retry lanes on the cross axis. */
        val crossAxisRegion: Rect = Rect(distanceRegion),
        /** Alternate cross-axis lane used after a clean frame proves the prior swipe had no effect. */
        val crossAxisOffsetFraction: Float = 0f,
        /** Region recognition uses one constant-speed stroke instead of capture easing. */
        val linearMotion: Boolean = false,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        val previous = instance
        instance = this
        if (previous != null && previous !== this) previous.requestGestureCancellation()
        connectionListeners.forEach { listener ->
            runCatching { listener.onConnectionChanged(true) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val observedPackage = event.packageName?.toString()?.trim()?.takeIf(String::isNotEmpty)
            ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val hasForegroundListeners = foregroundWindowListeners.isNotEmpty()
                val collectPageStructure = shouldCollectPageStructureSignature(
                    hasForegroundListeners = hasForegroundListeners,
                    captureActive = captureActive.get(),
                ) && canSamplePageStructureWindow(
                    latestPackageName = observedPackage,
                    ownPackageName = packageName,
                )
                val sampledPageSignature = if (collectPageStructure) {
                    pageStructureSignature(observedPackage, event.windowId)
                } else null
                val observation = ForegroundWindowObservation(
                    packageName = observedPackage,
                    className = event.className?.toString(),
                    observedAtMs = SystemClock.uptimeMillis(),
                    windowId = event.windowId,
                    pageSignature = sampledPageSignature?.takeIf {
                        shouldCollectPageStructureSignature(
                            hasForegroundListeners = foregroundWindowListeners.isNotEmpty(),
                            captureActive = captureActive.get(),
                        )
                    },
                    kind = ForegroundObservationKind.WINDOW_STATE,
                )
                latestForegroundWindow.set(observation)
                dispatchForegroundObservation(observation)
                if (
                    shouldCollectPageStructureSignature(
                        hasForegroundListeners = foregroundWindowListeners.isNotEmpty(),
                        captureActive = captureActive.get(),
                    ) &&
                    canSamplePageStructureWindow(
                        latestPackageName = observedPackage,
                        ownPackageName = packageName,
                    )
                ) {
                    schedulePageSignatureSamples(observedPackage, event.windowId)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (
                    shouldCollectPageStructureSignature(
                        hasForegroundListeners = foregroundWindowListeners.isNotEmpty(),
                        captureActive = captureActive.get(),
                    ) &&
                    canSamplePageStructureWindow(
                        latestPackageName = observedPackage,
                        ownPackageName = packageName,
                    ) &&
                    acceptsPageContentChange(event.contentChangeTypes)
                ) {
                    schedulePageSignatureSamples(observedPackage, event.windowId)
                }
            }

            else -> Unit
        }
    }

    private fun dispatchForegroundObservation(observation: ForegroundWindowObservation) {
        foregroundWindowListeners.forEach { listener ->
            runCatching { listener.onForegroundWindowChanged(observation) }
        }
    }

    private fun schedulePageSignatureSamples(packageName: String, windowId: Int) {
        if (
            windowId < 0 ||
            !shouldCollectPageStructureSignature(
                hasForegroundListeners = foregroundWindowListeners.isNotEmpty(),
                captureActive = captureActive.get(),
            )
        ) return
        val key = PageProbeKey(packageName, windowId)
        pageProbeSequences[key]?.let { active ->
            active.trailingSequenceRequested = true
            return
        }
        val sequence = PageProbeSequence()
        pageProbeSequences[key] = sequence
        PAGE_SIGNATURE_SAMPLE_DELAYS_MS.forEachIndexed { index, delayMs ->
            pageProbeHandler.postDelayed(
                {
                    if (pageProbeSequences[key] !== sequence) return@postDelayed
                    if (
                        !shouldCollectPageStructureSignature(
                            hasForegroundListeners = foregroundWindowListeners.isNotEmpty(),
                            captureActive = captureActive.get(),
                        )
                    ) {
                        pageProbeSequences.remove(key, sequence)
                        return@postDelayed
                    }
                    val signature = pageStructureSignature(packageName, windowId)
                    if (
                        !shouldCollectPageStructureSignature(
                            hasForegroundListeners = foregroundWindowListeners.isNotEmpty(),
                            captureActive = captureActive.get(),
                        )
                    ) {
                        pageProbeSequences.remove(key, sequence)
                        return@postDelayed
                    }
                    signature?.let {
                        dispatchForegroundObservation(
                            ForegroundWindowObservation(
                                packageName = packageName,
                                observedAtMs = SystemClock.uptimeMillis(),
                                windowId = windowId,
                                pageSignature = it,
                                kind = ForegroundObservationKind.PAGE_CONTENT,
                            ),
                        )
                    }
                    if (index == PAGE_SIGNATURE_SAMPLE_DELAYS_MS.lastIndex) {
                        pageProbeSequences.remove(key, sequence)
                        if (sequence.trailingSequenceRequested) {
                            schedulePageSignatureSamples(packageName, windowId)
                        }
                    }
                },
                delayMs,
            )
        }
    }

    private fun pageStructureSignature(packageName: String, windowId: Int): String? =
        runCatching {
            val availableWindows = windows
            try {
                val root = availableWindows.firstOrNull { window -> window.id == windowId }
                    ?.root
                    ?: return@runCatching null
                if (root.packageName?.toString() != packageName) {
                    root.recycle()
                    return@runCatching null
                }
                buildPageStructureSignature(root.pageStructureTokens())
            } finally {
                availableWindows.forEach { window -> window.recycle() }
            }
        }.getOrNull()

    private fun AccessibilityNodeInfo.pageStructureTokens(): List<PageStructureToken> {
        val visits = ArrayDeque<NodeVisit>()
        val tokens = ArrayList<PageStructureToken>(MAX_PAGE_STRUCTURE_NODES)
        visits.add(NodeVisit(this, depth = 0, siblingIndex = 0))
        try {
            while (visits.isNotEmpty() && tokens.size < MAX_PAGE_STRUCTURE_NODES) {
                val visit = visits.removeFirst()
                val node = visit.node
                try {
                    val childCount = node.childCount
                    tokens += PageStructureToken(
                        depth = visit.depth,
                        siblingIndex = visit.siblingIndex,
                        className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                        viewId = node.viewIdResourceName?.substringAfterLast('/'),
                        childCount = childCount.coerceAtMost(MAX_RECORDED_CHILD_COUNT),
                        stateFlags = node.stableStateFlags(),
                        stableLabel = node.stablePageLabel(),
                    )
                    if (
                        visit.depth < MAX_PAGE_STRUCTURE_DEPTH &&
                        !node.isScrollable
                    ) {
                        val traversedChildren = childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
                        repeat(traversedChildren) { childIndex ->
                            node.getChild(childIndex)?.let { child ->
                                visits.addLast(
                                    NodeVisit(child, visit.depth + 1, childIndex),
                                )
                            }
                        }
                    }
                } finally {
                    node.recycle()
                }
            }
        } finally {
            while (visits.isNotEmpty()) visits.removeFirst().node.recycle()
        }
        return tokens
    }

    private fun AccessibilityNodeInfo.stableStateFlags(): Int =
        (if (isClickable) 1 else 0) or
            (if (isCheckable) 1 shl 1 else 0) or
            (if (isScrollable) 1 shl 2 else 0) or
            (if (isSelected) 1 shl 3 else 0) or
            (if (isHeading) 1 shl 4 else 0)

    private fun AccessibilityNodeInfo.stablePageLabel(): String? {
        val label = when {
            !paneTitle.isNullOrBlank() -> paneTitle
            isHeading -> text ?: contentDescription
            isClickable || isCheckable -> contentDescription ?: text
            else -> null
        }
        return label?.normalizedStablePageLabel()
    }

    private fun CharSequence.normalizedStablePageLabel(): String? {
        val normalized = buildString {
            var previousWasSpace = false
            var previousWasDigit = false
            this@normalizedStablePageLabel.forEach { source ->
                if (length >= MAX_STABLE_PAGE_LABEL_LENGTH) return@forEach
                when {
                    source.isWhitespace() -> {
                        if (isNotEmpty() && !previousWasSpace) append(' ')
                        previousWasSpace = true
                        previousWasDigit = false
                    }

                    source.isDigit() -> {
                        if (!previousWasDigit) append('#')
                        previousWasSpace = false
                        previousWasDigit = true
                    }

                    else -> {
                        append(source.lowercaseChar())
                        previousWasSpace = false
                        previousWasDigit = false
                    }
                }
            }
        }.trim()
        return normalized.takeIf(String::isNotEmpty)
    }

    private data class PageProbeKey(val packageName: String, val windowId: Int)

    private class PageProbeSequence {
        var trailingSequenceRequested: Boolean = false
    }

    private data class NodeVisit(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val siblingIndex: Int,
    )

    private fun refreshPageSamplingForListenerState() {
        pageProbeHandler.post {
            if (
                !shouldCollectPageStructureSignature(
                    hasForegroundListeners = foregroundWindowListeners.isNotEmpty(),
                    captureActive = captureActive.get(),
                )
            ) {
                pageProbeHandler.removeCallbacksAndMessages(null)
                pageProbeSequences.clear()
                return@post
            }
            latestForegroundWindow.get()?.let { latest ->
                if (!canSamplePageStructureWindow(
                        latestPackageName = latest.packageName,
                        ownPackageName = packageName,
                    )
                ) return@let
                schedulePageSignatureSamples(latest.packageName, latest.windowId ?: return@let)
            }
        }
    }

    private fun requestGestureCancellation(): GestureOwnershipGate.Claim<GameScrollAccessibilityService>? {
        val claim = gestureGate.currentForOwner(this) ?: return null
        if (gestureGate.requestCancellation(claim)) {
            gestureHandler.postDelayed(
                {
                    if (gestureGate.isActive(claim) && claim.cancellationRequested.get()) {
                        // A missing OEM callback must not leave the global gesture gate occupied.
                        // Dispatching the next gesture will make Android cancel any stale stroke.
                        replacePlatformGestureExecutor()
                        gestureGate.finish(claim, false)
                    }
                },
                GESTURE_CANCELLATION_CALLBACK_TIMEOUT_MS,
            )
        }
        return claim
    }

    override fun onInterrupt() {
        requestGestureCancellation()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            connectionListeners.forEach { listener ->
                runCatching { listener.onConnectionChanged(false) }
            }
        }
        pageProbeHandler.removeCallbacksAndMessages(null)
        gestureHandler.removeCallbacksAndMessages(null)
        platformGestureExecutor.get().shutdownNow()
        pageProbeSequences.clear()
        gestureGate.abandonOwner(this)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode in NAVIGATION_KEY_CODES) {
            navigationKeyListeners.forEach { listener ->
                runCatching { listener.onNavigationKeyPressed(event.keyCode) }
            }
        }
        if (!captureActive.get()) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP
        ) return false

        // Do not adjust volume while a capture is active.  A broadcast is used
        // rather than a direct service reference so capture can recover even if
        // Android recreated either service independently.
        sendBroadcast(
            Intent(ACTION_VOLUME_KEY_STOP)
                .setPackage(packageName)
                .putExtra(EXTRA_KEY_CODE, event.keyCode)
        )
        return true
    }

    private fun newPlatformGestureExecutor() =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GameLongShot-PlatformGesture").apply { isDaemon = true }
        }

    private fun replacePlatformGestureExecutor() {
        val stale = platformGestureExecutor.get()
        val replacement = newPlatformGestureExecutor()
        if (platformGestureExecutor.compareAndSet(stale, replacement)) {
            stale.shutdownNow()
        } else {
            replacement.shutdownNow()
        }
    }

    private fun dispatchPlatformGesture(
        description: GestureDescription,
        callback: GestureResultCallback,
        onRejected: () -> Unit,
    ): Boolean = runCatching {
        platformGestureExecutor.get().execute {
            val accepted = runCatching {
                this@GameScrollAccessibilityService.dispatchGesture(
                    description,
                    callback,
                    gestureHandler,
                )
            }.getOrDefault(false)
            if (!accepted) gestureHandler.post(onRejected)
        }
        true
    }.getOrDefault(false)

    fun dispatchScroll(request: Request, callback: (Boolean) -> Unit): Boolean {
        val callbackDelivered = AtomicBoolean(false)
        fun finish(success: Boolean) {
            if (callbackDelivered.compareAndSet(false, true)) callback(success)
        }
        if (request.gestureRegion.width() <= 0 ||
            request.gestureRegion.height() <= 0 ||
            request.distancePx <= 0
        ) {
            finish(false)
            return false
        }

        val claim = gestureGate.acquire(this, ::finish) ?: run {
            finish(false)
            return false
        }

        var safeStartX = 0f
        var safeStartY = 0f
        var safeEndX = 0f
        var safeEndY = 0f
        var phases = emptyList<ScrollGesturePhase>()
        var previousStroke: GestureDescription.StrokeDescription? = null
        var lastDispatchedEndFraction = 0f
        fun finishGesture(success: Boolean) {
            gestureGate.finish(claim, success)
        }

        fun pointAt(fraction: Float): Pair<Float, Float> =
            (safeStartX + (safeEndX - safeStartX) * fraction) to
                (safeStartY + (safeEndY - safeStartY) * fraction)

        fun dispatchCancellationRelease(): Boolean {
            if (!gestureGate.isActive(claim) || callbackDelivered.get()) return false
            val continuedStroke = previousStroke ?: run {
                finishGesture(false)
                return true
            }
            return try {
                val (releaseX, releaseY) = pointAt(lastDispatchedEndFraction)
                val releasePath = Path().apply { moveTo(releaseX, releaseY) }
                val releaseStroke = continuedStroke.continueStroke(
                    releasePath,
                    0L,
                    GESTURE_CANCELLATION_RELEASE_DURATION_MS,
                    false,
                )
                previousStroke = releaseStroke
                val accepted = dispatchPlatformGesture(
                    GestureDescription.Builder().addStroke(releaseStroke).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            finishGesture(false)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            finishGesture(false)
                        }
                    },
                    onRejected = { finishGesture(false) },
                )
                if (!accepted) finishGesture(false)
                accepted
            } catch (_: Throwable) {
                finishGesture(false)
                false
            }
        }

        fun dispatchPhase(index: Int): Boolean {
            if (!gestureGate.isActive(claim) || callbackDelivered.get()) return false
            if (claim.cancellationRequested.get()) return dispatchCancellationRelease()
            return try {
                val phase = phases[index]
                val (phaseStartX, phaseStartY) = pointAt(phase.startFraction)
                val (phaseEndX, phaseEndY) = pointAt(phase.endFraction)
                val path = Path().apply {
                    moveTo(phaseStartX, phaseStartY)
                    // A move-only Path is Android's supported representation for keeping a
                    // continued pointer stationary. The final phase reaches the exact endpoint
                    // first, holds for the configured stable time, and only then emits ACTION_UP.
                    if (phase.startFraction != phase.endFraction) {
                        lineTo(phaseEndX, phaseEndY)
                    }
                }
                val continues = index < phases.lastIndex
                val stroke = previousStroke?.continueStroke(
                    path,
                    0L,
                    phase.durationMs,
                    continues,
                ) ?: GestureDescription.StrokeDescription(
                    path,
                    0L,
                    phase.durationMs,
                    continues,
                )
                previousStroke = stroke
                lastDispatchedEndFraction = phase.endFraction
                val accepted = dispatchPlatformGesture(
                    GestureDescription.Builder().addStroke(stroke).build(),
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (!gestureGate.isActive(claim) || callbackDelivered.get()) return
                            if (claim.cancellationRequested.get()) {
                                if (continues) {
                                    dispatchCancellationRelease()
                                } else {
                                    finishGesture(false)
                                }
                            } else if (index == phases.lastIndex) {
                                finishGesture(true)
                            } else if (!dispatchPhase(index + 1)) {
                                finishGesture(false)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            finishGesture(false)
                        }
                    },
                    onRejected = { finishGesture(false) },
                )
                if (!accepted) finishGesture(false)
                accepted
            } catch (_: Throwable) {
                // OEM accessibility implementations can throw synchronously when the service is
                // being rebound. Always release the global gate so later captures can retry.
                finishGesture(false)
                false
            }
        }

        return try {
            val region = Rect(request.gestureRegion)
            val distanceRegion = Rect(request.distanceRegion)
            val movementAxisRegion = Rect(request.movementAxisRegion)
            val crossAxisRegion = Rect(request.crossAxisRegion)
            val path = resolveScrollGesturePath(
                region = RectConfig(region.left, region.top, region.right, region.bottom),
                direction = request.direction.toScrollDirection(),
                requestedDistancePx = request.distancePx,
                distanceRegion = RectConfig(
                    distanceRegion.left,
                    distanceRegion.top,
                    distanceRegion.right,
                    distanceRegion.bottom,
                ),
                movementAxisRegion = RectConfig(
                    movementAxisRegion.left,
                    movementAxisRegion.top,
                    movementAxisRegion.right,
                    movementAxisRegion.bottom,
                ),
                crossAxisOffsetFraction = request.crossAxisOffsetFraction,
                crossAxisRegion = RectConfig(
                    crossAxisRegion.left,
                    crossAxisRegion.top,
                    crossAxisRegion.right,
                    crossAxisRegion.bottom,
                ),
            )
            safeStartX = path.start.x
            safeStartY = path.start.y
            safeEndX = path.end.x
            safeEndY = path.end.y
            phases = if (request.linearMotion) {
                buildLinearScrollGesturePhases(request.durationMs, request.endpointHoldMs)
            } else {
                buildEasedScrollGesturePhases(request.durationMs, request.endpointHoldMs)
            }
            dispatchPhase(0)
        } catch (_: Throwable) {
            // Path construction can also fail during a display/configuration transition.
            gestureGate.finish(claim, false)
            false
        }
    }

    suspend fun dispatchScroll(request: Request): Boolean = suspendCoroutine { continuation ->
        dispatchScroll(request) { continuation.resume(it) }
    }

    companion object {
        const val ACTION_VOLUME_KEY_STOP =
            "com.apricotiris.IrisShot.action.VOLUME_KEY_STOP"
        const val EXTRA_KEY_CODE = "key_code"
        internal const val RELEASE_HOLD_DURATION_MS = 500L

        @Volatile
        private var instance: GameScrollAccessibilityService? = null
        private val captureActive = AtomicBoolean(false)
        private val gestureGate = GestureOwnershipGate<GameScrollAccessibilityService>()
        private val latestForegroundWindow = AtomicReference<ForegroundWindowObservation?>(null)
        private val foregroundWindowListeners = CopyOnWriteArraySet<ForegroundWindowListener>()
        private val navigationKeyListeners = CopyOnWriteArraySet<NavigationKeyListener>()
        private val connectionListeners = CopyOnWriteArraySet<ConnectionListener>()
        private val NAVIGATION_KEY_CODES = setOf(
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
        )
        private val PAGE_SIGNATURE_SAMPLE_DELAYS_MS = longArrayOf(200L, 450L, 700L, 950L)
        private const val MAX_PAGE_STRUCTURE_NODES = 128
        private const val MAX_PAGE_STRUCTURE_DEPTH = 10
        private const val MAX_CHILDREN_PER_NODE = 32
        private const val MAX_RECORDED_CHILD_COUNT = 99
        private const val MAX_STABLE_PAGE_LABEL_LENGTH = 64
        private const val GESTURE_CANCELLATION_RELEASE_DURATION_MS = 1L
        private const val GESTURE_CANCELLATION_CALLBACK_TIMEOUT_MS = 750L

        fun isConnected(): Boolean = instance != null

        /**
         * Returns a context carrying this accessibility connection's overlay-window token.
         * A normal application or foreground-service context cannot safely add this window type.
         */
        internal fun createAccessibilityOverlayWindowContextOrNull(): Context? {
            // The AccessibilityService context owns the accessibility-window token. Some OEMs
            // return a generic token from createWindowContext(), which silently downgrades or
            // rejects TYPE_ACCESSIBILITY_OVERLAY. Use the connected service directly.
            return instance
        }

        internal fun addConnectionListener(listener: ConnectionListener) {
            connectionListeners += listener
            listener.onConnectionChanged(instance != null)
        }

        internal fun removeConnectionListener(listener: ConnectionListener) {
            connectionListeners -= listener
        }

        @Suppress("DEPRECATION")
        fun isCompactApplicationWindow(windowId: Int?): Boolean {
            val expectedWindowId = windowId?.takeIf { it >= 0 } ?: return false
            val service = instance ?: return false
            val metrics = service.resources.displayMetrics
            val windows = runCatching { service.windows.orEmpty() }.getOrDefault(emptyList())
            return try {
                windows.any { window ->
                    if (window.id != expectedWindowId ||
                        window.type != AccessibilityWindowInfo.TYPE_APPLICATION
                    ) {
                        return@any false
                    }
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    isCompactApplicationWindowBounds(
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels,
                    )
                }
            } catch (_: Throwable) {
                false
            } finally {
                windows.forEach { window -> runCatching { window.recycle() } }
            }
        }

        /**
         * Uses interactive-window bounds to identify freeform, split-screen and picture-in-picture
         * application windows at an outside-touch point. Full-screen app windows behind them are
         * intentionally ignored.
         */
        @Suppress("DEPRECATION")
        fun isPointInsideCompactApplicationWindow(screenX: Int, screenY: Int): Boolean {
            if (screenX < 0 || screenY < 0) return false
            val service = instance ?: return false
            val metrics = service.resources.displayMetrics
            val windows = runCatching { service.windows.orEmpty() }.getOrDefault(emptyList())
            return try {
                windows.any { window ->
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                        return@any false
                    }
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    bounds.contains(screenX, screenY) && isCompactApplicationWindowBounds(
                        left = bounds.left,
                        top = bounds.top,
                        right = bounds.right,
                        bottom = bounds.bottom,
                        screenWidth = metrics.widthPixels,
                        screenHeight = metrics.heightPixels,
                    )
                }
            } catch (_: Throwable) {
                false
            } finally {
                windows.forEach { window -> runCatching { window.recycle() } }
            }
        }

        fun addForegroundWindowListener(listener: ForegroundWindowListener) {
            foregroundWindowListeners += listener
            latestForegroundWindow.get()?.let(listener::onForegroundWindowChanged)
            instance?.refreshPageSamplingForListenerState()
        }

        fun removeForegroundWindowListener(listener: ForegroundWindowListener) {
            foregroundWindowListeners -= listener
            instance?.refreshPageSamplingForListenerState()
        }

        fun addNavigationKeyListener(listener: NavigationKeyListener) {
            navigationKeyListeners += listener
        }

        fun removeNavigationKeyListener(listener: NavigationKeyListener) {
            navigationKeyListeners -= listener
        }

        fun setCaptureActive(active: Boolean) {
            captureActive.set(active)
            instance?.refreshPageSamplingForListenerState()
        }

        fun abandonCurrentGesture() {
            instance?.requestGestureCancellation()
        }

        /** Called from a worker after an OEM omitted the normal gesture callback. */
        fun abandonCurrentGestureAndAwait(timeoutMs: Long): Boolean {
            val service = instance ?: return true
            val claim = service.requestGestureCancellation() ?: return true
            // Do not expose a busy window to the next capture when an OEM callback and the main
            // looper watchdog are both delayed. The generation-bound claim makes a late callback
            // harmless, and the next Android gesture will cancel any stale platform stroke.
            if (!gestureGate.awaitFinished(claim, timeoutMs)) {
                service.replacePlatformGestureExecutor()
                gestureGate.finish(claim, false)
            }
            return !gestureGate.isActive(claim)
        }

        fun dispatch(request: Request, callback: (Boolean) -> Unit): Boolean {
            val service = instance
            if (service == null) {
                callback(false)
                return false
            }
            return service.dispatchScroll(request, callback)
        }

        suspend fun dispatch(request: Request): Boolean {
            val service = instance ?: return false
            return service.dispatchScroll(request)
        }
    }

    private fun Direction.toScrollDirection(): ScrollDirection = when (this) {
        Direction.UP -> ScrollDirection.UP
        Direction.DOWN -> ScrollDirection.DOWN
        Direction.LEFT -> ScrollDirection.LEFT
        Direction.RIGHT -> ScrollDirection.RIGHT
    }
}
