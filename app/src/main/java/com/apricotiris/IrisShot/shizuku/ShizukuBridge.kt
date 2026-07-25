package com.apricotiris.IrisShot.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.apricotiris.IrisShot.ui.AppPreferences
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.IdentityHashMap
import java.util.UUID
import kotlin.concurrent.withLock
import rikka.shizuku.Shizuku

data class ShizukuState(
    val installed: Boolean,
    val binderAvailable: Boolean,
    val permissionGranted: Boolean,
    val modeRequested: Boolean,
) {
    val modeEnabled: Boolean
        get() = installed && binderAvailable && permissionGranted && modeRequested
}

enum class ShizukuSwipeResult {
    COMPLETED,
    OUTCOME_UNKNOWN,
    UNAVAILABLE,
}

internal fun classifyShizukuSwipeFailure(
    binderAvailable: Boolean,
    permissionGranted: Boolean,
): ShizukuSwipeResult = if (binderAvailable && permissionGranted) {
    ShizukuSwipeResult.OUTCOME_UNKNOWN
} else {
    ShizukuSwipeResult.UNAVAILABLE
}

internal fun reconciledShizukuModeRequest(
    requested: Boolean,
    installed: Boolean,
    binderAvailable: Boolean,
    permissionGranted: Boolean,
): Boolean = requested && installed && binderAvailable && permissionGranted

/**
 * Keeps UserService bind/reset state tied to an application-side generation token.
 *
 * Shizuku can deliver a callback after an earlier bind or removal request has timed out.  The
 * token is therefore deliberately distinct from the Binder identity: once invalidated, it can
 * never become current again, even if a late callback carries a live Binder.
 */
internal class ShizukuServiceEpochGate<T : Any>(
    initialEpoch: Long = 1L,
) {
    internal class Generation<T : Any>(
        val epoch: Long,
    ) {
        private val invalidated = AtomicBoolean(false)
        internal val resetIssued = AtomicBoolean(false)

        @Volatile
        private var value: T? = null

        @Volatile
        internal var bindStarted: Boolean = false

        internal fun attach(value: T, sameValue: (T, T) -> Boolean): Boolean {
            // A generation represents one bind request. A second, different callback is stale;
            // accepting it would let an old UserService replace the binder held by an active swipe.
            this.value?.let { existing -> return sameValue(existing, value) }
            this.value = value
            return true
        }

        internal fun valueOrNull(): T? = value

        internal fun invalidate(): Boolean = invalidated.compareAndSet(false, true)

        internal fun isInvalidated(): Boolean = invalidated.get()
    }

    internal data class Reset<T : Any>(
        val generation: Generation<T>,
        val shouldRemoveRemoteService: Boolean,
    )

    private val monitor = Object()
    private val nextEpoch = AtomicLong(initialEpoch.coerceAtLeast(1L))
    private var current: Generation<T>? = null
    private val removals = IdentityHashMap<Generation<T>, Boolean>()

    /** Returns the active generation, including one whose bind callback is still pending. */
    fun currentGeneration(): Generation<T>? = synchronized(monitor) { current }

    fun isCurrent(generation: Generation<T>): Boolean = synchronized(monitor) {
        current === generation && !generation.isInvalidated()
    }

    fun beginBinding(): Generation<T>? = synchronized(monitor) {
        // Remote removal is per-generation (each bind uses a unique tag), so a slow old removal
        // must not prevent a fresh service from being created.
        if (current != null) return@synchronized null
        Generation<T>(nextEpoch.getAndUpdate { epoch ->
            if (epoch == Long.MAX_VALUE) 1L else epoch + 1L
        }).also { generation ->
            current = generation
            monitor.notifyAll()
        }
    }

    /** Marks a generation before an external bind call is started. */
    private fun markBindStarted(generation: Generation<T>): Boolean = synchronized(monitor) {
        if (current !== generation || generation.isInvalidated()) return@synchronized false
        generation.bindStarted = true
        true
    }

    /** Executes the external bind without holding the gate monitor. */
    fun invokeBindIfCurrent(generation: Generation<T>, bind: () -> Unit): Boolean =
        if (!markBindStarted(generation)) {
            false
        } else {
            bind()
            true
        }

    fun acceptConnected(
        generation: Generation<T>,
        value: T,
        sameValue: (T, T) -> Boolean = { first, second -> first === second },
    ): Boolean = synchronized(monitor) {
        if (current !== generation || generation.isInvalidated()) {
            return@synchronized false
        }
        if (!generation.attach(value, sameValue)) return@synchronized false
        monitor.notifyAll()
        true
    }

    /**
     * Invalidates [expected] when supplied, without ever replacing a newer current generation.
     * The returned removal plan is exclusive: only the first reset of a bound generation may
     * remove its remote UserService process.
     */
    fun beginReset(expected: Generation<T>? = null): Reset<T>? = synchronized(monitor) {
        val target = expected ?: current ?: return@synchronized null
        target.invalidate()
        if (current === target) current = null
        val shouldRemove = target.bindStarted && target.resetIssued.compareAndSet(false, true)
        if (shouldRemove) removals[target] = true
        monitor.notifyAll()
        Reset(target, shouldRemoveRemoteService = shouldRemove)
    }

    /** Completes only the removal belonging to [generation]; stale watchdogs are ignored. */
    fun finishRemoval(generation: Generation<T>): Boolean = synchronized(monitor) {
        if (removals.remove(generation) == null) return@synchronized false
        monitor.notifyAll()
        true
    }

    fun awaitChange(timeoutMs: Long): Boolean = synchronized(monitor) {
        if (timeoutMs <= 0L) return@synchronized false
        try {
            TimeUnit.MILLISECONDS.timedWait(monitor, timeoutMs)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}

/**
 * Tracks when a per-generation binding tombstone can be forgotten.
 *
 * A bind call may finish after its first remove request.  Publishing [bindCallCompleted] before
 * recording that a late cleanup is required would let the removal thread delete the tombstone in
 * between those two writes, so [markBindCallCompleted] deliberately publishes them in that order.
 */
internal class ShizukuUserServiceBindingLifecycle {
    private val bindCallCompleted = AtomicBoolean(false)
    private val removalStarted = AtomicBoolean(false)
    private val removalCompleted = AtomicBoolean(false)
    private val lateCleanupRequired = AtomicBoolean(false)
    private val lateCleanupStarted = AtomicBoolean(false)
    private val lateCleanupCompleted = AtomicBoolean(false)

    fun markBindCallCompleted(requiresLateCleanup: Boolean) {
        if (requiresLateCleanup) lateCleanupRequired.set(true)
        bindCallCompleted.set(true)
    }

    fun claimRemoval(): Boolean = removalStarted.compareAndSet(false, true)

    fun markRemovalCompleted() {
        removalCompleted.set(true)
    }

    fun claimLateCleanup(): Boolean {
        lateCleanupRequired.set(true)
        return lateCleanupStarted.compareAndSet(false, true)
    }

    fun markLateCleanupCompleted() {
        lateCleanupCompleted.set(true)
    }

    fun canReleaseTombstone(): Boolean =
        bindCallCompleted.get() &&
            removalCompleted.get() &&
            (!lateCleanupRequired.get() || lateCleanupCompleted.get())
}

/** Distinguishes a cancelled queued task from a Binder call that is already executing. */
internal class ShizukuRpcCompletionFence {
    private enum class State { PENDING, RUNNING, FINISHED }

    private val state = AtomicReference(State.PENDING)
    val completion = CountDownLatch(1)

    fun tryStart(): Boolean = state.compareAndSet(State.PENDING, State.RUNNING)

    fun cancelBeforeStart(): Boolean {
        if (!state.compareAndSet(State.PENDING, State.FINISHED)) return false
        completion.countDown()
        return true
    }

    fun finish() {
        if (state.getAndSet(State.FINISHED) != State.FINISHED) completion.countDown()
    }
}

internal data class ShizukuBoundService<T : Any>(
    val generation: ShizukuServiceEpochGate.Generation<T>,
    val service: T,
)

/**
 * Serializes ordinary swipes with the full reserve-to-release lifetime of a cancellable swipe.
 * Public handles are generated in the app process, so a delayed call can never resolve to a
 * remote session whose numeric id was reused after the UserService restarted.
 */
internal class ShizukuSwipeSessionGate<T>(
    initialHandle: Long = 1L,
) {
    internal class Lease<T>(
        val handle: Long,
        val remoteSessionId: Long,
        val owner: T,
    ) {
        private val releaseClaimed = AtomicBoolean(false)
        private val releaseFinished = CountDownLatch(1)
        private var remoteRpcCount = 0
        private var deferredReleaseCount = 0
        private var finishPending = false

        internal fun claimRelease(): Boolean = releaseClaimed.compareAndSet(false, true)
        internal fun isClosing(): Boolean = releaseClaimed.get()
        internal fun deferRelease() {
            deferredReleaseCount = Math.addExact(deferredReleaseCount, 1)
        }
        internal fun isReleaseDeferred(): Boolean = deferredReleaseCount > 0
        internal fun beginRemoteRpc() {
            remoteRpcCount += 1
        }
        internal fun endRemoteRpc(): Boolean {
            remoteRpcCount = (remoteRpcCount - 1).coerceAtLeast(0)
            return finishPending && remoteRpcCount == 0 && deferredReleaseCount == 0
        }
        internal fun requestFinish(): Boolean {
            finishPending = true
            return remoteRpcCount == 0 && deferredReleaseCount == 0
        }
        internal fun resumeDeferredFinish(): Boolean {
            if (deferredReleaseCount <= 0) return false
            deferredReleaseCount -= 1
            return finishPending && remoteRpcCount == 0 && deferredReleaseCount == 0
        }
        internal fun markReleased() = releaseFinished.countDown()
        internal fun awaitReleased(timeoutMs: Long): Boolean = try {
            releaseFinished.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private class Claim<T>(
        val token: Long,
        val owner: T?,
        val kind: ClaimKind,
        val lease: Lease<T>? = null,
    ) {
        private val invalidated = AtomicBoolean(false)
        private val releaseDeferred = AtomicBoolean(false)

        fun invalidate() {
            invalidated.set(true)
        }

        fun isInvalidated(): Boolean = invalidated.get()

        fun deferRelease() {
            releaseDeferred.set(true)
        }

        fun isReleaseDeferred(): Boolean = releaseDeferred.get()
    }

    private enum class ClaimKind { ORDINARY, RESERVATION }

    private val permit = Semaphore(1, true)
    private val nextHandle = AtomicLong(initialHandle.coerceAtLeast(1L))
    private val nextClaimToken = AtomicLong(1L)
    private val activeClaim = AtomicReference<Claim<T>?>(null)

    fun reserve(
        owner: T,
        timeoutMs: Long = DEFAULT_SWIPE_GATE_TIMEOUT_MS,
        ownerIsValid: (T) -> Boolean = { true },
        discardRemoteSession: (Long) -> Unit = {},
        reserveRemoteSession: () -> Long?,
    ): Lease<T>? {
        val claim = acquireClaim(owner, timeoutMs, ClaimKind.RESERVATION) ?: return null
        var keepClaim = false
        try {
            if (!ownerIsValid(owner)) return null
            val remoteSessionId = reserveRemoteSession()?.takeIf { it > 0L } ?: return null
            val lease = Lease(
                handle = nextPublicHandle(),
                remoteSessionId = remoteSessionId,
                owner = owner,
            )
            val reserved = Claim(claim.token, owner, ClaimKind.RESERVATION, lease)
            if (!activeClaim.compareAndSet(claim, reserved)) {
                runCatching { discardRemoteSession(remoteSessionId) }
                return null
            }
            keepClaim = true
            return lease
        } finally {
            if (!keepClaim) releaseClaim(claim)
        }
    }

    fun resolve(handle: Long): Lease<T>? = activeClaim.get()?.lease?.takeIf { lease ->
        lease.handle == handle && !lease.isClosing()
    }

    fun claimRelease(handle: Long): Lease<T>? = activeClaim.get()?.lease?.takeIf { lease ->
        lease.handle == handle && lease.claimRelease()
    }

    fun awaitRelease(handle: Long, timeoutMs: Long): Boolean {
        val lease = activeClaim.get()?.lease?.takeIf { it.handle == handle } ?: return true
        return lease.awaitReleased(timeoutMs)
    }

    /** Waits until neither an ordinary swipe nor a reserved swipe owns the shared permit. */
    fun awaitIdle(timeoutMs: Long): Boolean {
        if (!tryAcquire(timeoutMs)) return false
        permit.release()
        return true
    }

    fun finish(lease: Lease<T>) {
        synchronized(lease) {
            if (lease.requestFinish()) finishNow(lease)
        }
    }

    private fun finishNow(lease: Lease<T>) {
        val claim = activeClaim.get()?.takeIf { it.lease === lease }
        if (claim != null) releaseClaim(claim)
        lease.markReleased()
    }

    fun deferLeaseRelease(handle: Long): Lease<T>? {
        val claim = activeClaim.get() ?: return null
        val lease = claim.lease?.takeIf { it.handle == handle } ?: return null
        return synchronized(lease) {
            if (activeClaim.get() !== claim) return@synchronized null
            lease.deferRelease()
            lease
        }
    }

    fun finishDeferredLeaseRelease(lease: Lease<T>) {
        synchronized(lease) {
            if (!lease.isReleaseDeferred()) return
            if (lease.resumeDeferredFinish()) finishNow(lease)
        }
    }

    fun beginLeaseRpc(handle: Long): Lease<T>? {
        val claim = activeClaim.get() ?: return null
        val lease = claim.lease?.takeIf { it.handle == handle } ?: return null
        return synchronized(lease) {
            if (activeClaim.get() !== claim) return@synchronized null
            lease.beginRemoteRpc()
            lease
        }
    }

    fun endLeaseRpc(lease: Lease<T>) {
        synchronized(lease) {
            if (lease.endRemoteRpc()) finishNow(lease)
        }
    }

    fun invalidateOwner(matches: (T) -> Boolean): Lease<T>? {
        while (true) {
            val claim = activeClaim.get() ?: return null
            val owner = claim.owner ?: return null
            if (!matches(owner)) return null
            val lease = claim.lease
            // A close owner is already performing the bounded remote cleanup. Do not publish the
            // permit or its completion until that owner reaches finish().
            if (lease != null && !lease.claimRelease()) return null
            if (lease != null) return lease
            if (claim.kind == ClaimKind.ORDINARY) {
                claim.invalidate()
                return null
            }
            if (activeClaim.compareAndSet(claim, null)) {
                permit.release()
                return null
            }
        }
    }

    /** Keeps an ordinary swipe claim closed while its timed-out Binder call is still executing. */
    fun deferOrdinaryRelease(owner: T): Boolean {
        val claim = activeClaim.get() ?: return false
        return synchronized(claim) {
            if (claim.kind != ClaimKind.ORDINARY || claim.owner !== owner ||
                activeClaim.get() !== claim
            ) return@synchronized false
            claim.deferRelease()
            true
        }
    }

    fun finishDeferredOrdinaryRelease(owner: T) {
        val claim = activeClaim.get() ?: return
        synchronized(claim) {
            if (claim.kind != ClaimKind.ORDINARY || claim.owner !== owner ||
                !claim.isReleaseDeferred()
            ) return
            releaseClaim(claim)
        }
    }

    /** Invalidates both a reserved swipe and a permit held while a Binder call is still pending. */
    fun invalidateAll(): Lease<T>? {
        while (true) {
            val claim = activeClaim.get() ?: return null
            val lease = claim.lease
            if (lease != null && !lease.claimRelease()) return null
            if (lease != null) return lease
            if (claim.kind == ClaimKind.ORDINARY) {
                claim.invalidate()
                return null
            }
            if (activeClaim.compareAndSet(claim, null)) {
                permit.release()
                return null
            }
        }
    }

    fun <R> withOrdinarySwipe(
        owner: T? = null,
        timeoutMs: Long = DEFAULT_SWIPE_GATE_TIMEOUT_MS,
        ownerIsValid: (T?) -> Boolean = { true },
        action: () -> R,
    ): R? {
        val claim = acquireClaim(
            owner = owner,
            timeoutMs = timeoutMs,
            kind = ClaimKind.ORDINARY,
        ) ?: return null
        return try {
            if (claim.isInvalidated() || !ownerIsValid(owner)) return null
            // A service reset may invalidate this claim while a Binder call is returning. The
            // identity-based release below already prevents the stale call from releasing a newer
            // claim, so preserve its outcome instead of turning a possibly-started swipe into an
            // incorrect "not started" result.
            action()
        } finally {
            synchronized(claim) {
                if (!claim.isReleaseDeferred()) releaseClaim(claim)
            }
        }
    }

    private fun acquireClaim(
        owner: T?,
        timeoutMs: Long,
        kind: ClaimKind,
    ): Claim<T>? {
        if (!tryAcquire(timeoutMs)) return null
        val claim = Claim(
            token = nextClaimToken.getAndUpdate { current ->
                if (current == Long.MAX_VALUE) 1L else current + 1L
            },
            owner = owner,
            kind = kind,
        )
        if (!activeClaim.compareAndSet(null, claim)) {
            permit.release()
            error("Swipe session permit is inconsistent")
        }
        return claim
    }

    private fun releaseClaim(claim: Claim<T>) {
        if (activeClaim.compareAndSet(claim, null)) permit.release()
    }

    private fun tryAcquire(timeoutMs: Long): Boolean = try {
        permit.tryAcquire(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun nextPublicHandle(): Long {
        while (true) {
            val candidate = nextHandle.getAndUpdate { current ->
                if (current == Long.MAX_VALUE) 1L else current + 1L
            }
            if (candidate > 0L && activeClaim.get()?.lease?.handle != candidate) return candidate
        }
    }
}

private const val DEFAULT_SWIPE_GATE_TIMEOUT_MS = 2_000L

enum class ShizukuPermissionRequest {
    ALREADY_GRANTED,
    REQUESTED,
    MANAGER_NOT_INSTALLED,
    SERVICE_UNAVAILABLE,
}

internal data class CompactApplicationWindowBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun contains(x: Int, y: Int): Boolean =
        right > left && bottom > top && x in left until right && y in top until bottom
}

internal data class ShizukuForegroundSnapshot(
    val packageName: String?,
    val componentName: String?,
    val compactWindowBounds: CompactApplicationWindowBounds?,
)

internal data class FocusedWindowIdentity(
    val packageName: String,
    val componentName: String?,
)

internal data class ShizukuInputNavigationSnapshot(
    val appSwitchPending: Boolean,
    val focusedApplicationPackage: String?,
)

internal fun remainingInputSnapshotBudgetNanos(
    deadlineNanos: Long,
    nowNanos: Long,
): Long = (deadlineNanos - nowNanos).coerceAtLeast(0L)

internal class ShizukuInputNavigationSnapshotRequest(
    private val future: Future<ShizukuInputNavigationSnapshot?>,
    private val deadlineNanos: Long,
) {
    fun await(): ShizukuInputNavigationSnapshot? {
        val remainingNanos = remainingInputSnapshotBudgetNanos(
            deadlineNanos = deadlineNanos,
            nowNanos = System.nanoTime(),
        )
        if (remainingNanos == 0L) {
            if (!future.isDone) future.cancel(true)
            return completedResult()
        }
        return try {
            future.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            if (!future.cancel(true)) completedResult() else null
        } catch (_: InterruptedException) {
            cancel()
            Thread.currentThread().interrupt()
            null
        } catch (_: Throwable) {
            null
        }
    }

    fun cancel() {
        future.cancel(true)
    }

    private fun completedResult(): ShizukuInputNavigationSnapshot? {
        if (!future.isDone || future.isCancelled) return null
        return runCatching { future.get() }.getOrNull()
    }
}

internal fun parseInputNavigationSnapshot(output: String): ShizukuInputNavigationSnapshot {
    val appSwitchPending = APP_SWITCH_PENDING_PATTERN.containsMatchIn(output)
    val focusedApplicationPackage = FOCUSED_APPLICATION_PATTERN
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
    return ShizukuInputNavigationSnapshot(appSwitchPending, focusedApplicationPackage)
}

internal fun parseFocusedWindowIdentity(output: String): FocusedWindowIdentity? {
    val focusLine = output.lineSequence().firstOrNull { line ->
        line.contains("mCurrentFocus=")
    } ?: output.lineSequence().firstOrNull { line ->
        line.contains("mFocusedWindow=")
    } ?: return null
    FOCUSED_WINDOW_COMPONENT_PATTERN.find(focusLine)?.groupValues?.getOrNull(1)?.let { component ->
        return FocusedWindowIdentity(
            packageName = component.substringBefore('/'),
            componentName = component,
        )
    }
    val windowName = FOCUSED_WINDOW_NAME_PATTERN.find(focusLine)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        .orEmpty()
    if (SYSTEM_UI_FOCUS_MARKERS.any { marker -> windowName.contains(marker, ignoreCase = true) }) {
        return FocusedWindowIdentity(
            packageName = SYSTEM_UI_PACKAGE,
            componentName = "$SYSTEM_UI_PACKAGE/$windowName",
        )
    }
    return null
}

/** Parses the resumed freeform, split-screen or picture-in-picture task from dumpsys activity. */
internal fun parseTopCompactApplicationWindowBounds(
    output: String,
): CompactApplicationWindowBounds? = output.lineSequence()
    .firstOrNull { line ->
        (line.contains("topResumedActivity") || line.contains("mResumedActivity")) &&
            COMPACT_WINDOW_MODE_PATTERN.containsMatchIn(line)
    }
    ?.let { line ->
        COMPACT_WINDOW_BOUNDS_PATTERN.find(line)?.groupValues?.let { values ->
            CompactApplicationWindowBounds(
                left = values[1].toInt(),
                top = values[2].toInt(),
                right = values[3].toInt(),
                bottom = values[4].toInt(),
            )
        }
    }

private val COMPACT_WINDOW_MODE_PATTERN =
    Regex("(?:mode|mWindowingMode|windowingMode)=(?:freeform|multi-window|pinned)")
private val COMPACT_WINDOW_BOUNDS_PATTERN = Regex(
    "(?:boundsRect\\(|mBounds=Rect\\()(-?\\d+),\\s*(-?\\d+)\\s*-\\s*(-?\\d+),\\s*(-?\\d+)\\)",
)
private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
private val FOCUSED_WINDOW_COMPONENT_PATTERN = Regex(
    "(?:mCurrentFocus|mFocusedWindow)[^\\n]*?\\s(?:u\\d+\\s+)?" +
        "([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/\\.?[A-Za-z0-9_$.]+)",
)
private val FOCUSED_WINDOW_NAME_PATTERN = Regex(
    "(?:mCurrentFocus|mFocusedWindow)[^\\n]*?\\su\\d+\\s+([^}\\s]+)",
)
private val SYSTEM_UI_FOCUS_MARKERS = listOf(
    "NotificationShade",
    "StatusBar",
    "Keyguard",
    "GlobalActions",
    "NavigationBar",
)
private val APP_SWITCH_PENDING_PATTERN = Regex(
    "^\\s*AppSwitch:\\s*pending\\b",
    setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
)
private val FOCUSED_APPLICATION_PATTERN = Regex(
    "^\\s*displayId=\\d+,\\s*name='ActivityRecord\\{[^\\n]*?\\su\\d+\\s+" +
        "([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)(?:/|\\s)",
    RegexOption.MULTILINE,
)

/** Keeps every privileged operation behind a narrow, argument-safe API. */
object ShizukuBridge {
    private val ioExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "GameLongShot-ShizukuIO").apply { isDaemon = true }
    }
    private val userServiceRpcExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "GameLongShot-ShizukuRpc").apply { isDaemon = true }
    }
    private val commandLock = ReentrantLock()
    private val swipeSessionGate =
        ShizukuSwipeSessionGate<ShizukuBoundService<IShizukuCommandService>>()
    private val userServiceEpochGate = ShizukuServiceEpochGate<IShizukuCommandService>()
    private data class UserServiceBinding(
        val args: Shizuku.UserServiceArgs,
        val connection: ServiceConnection,
        val lifecycle: ShizukuUserServiceBindingLifecycle =
            ShizukuUserServiceBindingLifecycle(),
    )

    private val userServiceBindings = ConcurrentHashMap<
        ShizukuServiceEpochGate.Generation<IShizukuCommandService>,
        UserServiceBinding,
    >()
    private val userServiceLifecycleExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "GameLongShot-ShizukuLifecycle").apply { isDaemon = true }
    }
    private val listenersRegistered = AtomicBoolean(false)
    private val userServiceMonitor = Object()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var userServiceLaunchNonce: String? = null

    @Volatile
    private var stateChangedCallback: (() -> Unit)? = null

    @Volatile
    private var permissionResultCallback: ((Int, Boolean) -> Unit)? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        stateChangedCallback?.invoke()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        resetUserService()
        stateChangedCallback?.invoke()
    }
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            permissionResultCallback?.invoke(
                requestCode,
                grantResult == PackageManager.PERMISSION_GRANTED,
            )
            stateChangedCallback?.invoke()
        }

    private fun createUserServiceConnection(
        generation: ShizukuServiceEpochGate.Generation<IShizukuCommandService>,
        bindingProvider: () -> UserServiceBinding?,
    ): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = binder?.let(IShizukuCommandService.Stub::asInterface)
            if (connected == null || !userServiceEpochGate.acceptConnected(
                    generation,
                    connected,
                    sameValue = { first, second ->
                        first.asBinder() === second.asBinder()
                    },
                )
            ) {
                // A callback from a timed-out bind must never become the next active service. Each
                // generation has its own tag, so cleanup cannot clear a newer connection.
                cleanupStaleUserServiceGeneration(generation, bindingProvider())
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            resetUserService(generation)
        }
    }

    private fun createUserServiceBinding(
        generation: ShizukuServiceEpochGate.Generation<IShizukuCommandService>,
    ): UserServiceBinding? {
        val application = appContext ?: return null
        val nonce = userServiceLaunchNonce ?: return null
        val identity = "${nonce.replace("-", "").take(12)}_${generation.epoch.toString(36)}"
        val args = Shizuku.UserServiceArgs(
            ComponentName(application, ShizukuCommandUserService::class.java),
        )
            .daemon(false)
            .tag("${USER_SERVICE_TAG}_$identity")
            .version(USER_SERVICE_VERSION)
            .debuggable(
                application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            )
            // Keep the process name short and shell-safe while still isolating late removals.
            .processNameSuffix("command_$identity")
        lateinit var binding: UserServiceBinding
        val connection = createUserServiceConnection(generation) { binding }
        return UserServiceBinding(args = args, connection = connection).also { binding = it }
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        val application = context.applicationContext
        synchronized(userServiceMonitor) {
            if (appContext != null) return
            userServiceLaunchNonce = UUID.randomUUID().toString()
            // Publish the context last so a concurrent binder request always observes a nonce.
            appContext = application
        }
    }

    fun state(context: Context): ShizukuState {
        initialize(context)
        val installed = isInstalled(context)
        val binderAvailable = installed && isBinderReady()
        val permissionGranted = binderAvailable && isPermissionGranted()
        val applicationContext = context.applicationContext
        val requested = loadModeEnabled(applicationContext, installed)
        val reconciledRequest = reconciledShizukuModeRequest(
            requested = requested,
            installed = installed,
            binderAvailable = binderAvailable,
            permissionGranted = permissionGranted,
        )
        if (requested && !reconciledRequest) {
            AppPreferences(applicationContext).saveShizukuModeEnabled(false)
        }
        return ShizukuState(
            installed = installed,
            binderAvailable = binderAvailable,
            permissionGranted = permissionGranted,
            modeRequested = reconciledRequest,
        )
    }

    fun isInstalled(context: Context): Boolean = isManagerInstalled(context.applicationContext)

    fun isBinderReady(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = isBinderReady() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isModeEnabled(context: Context): Boolean {
        return state(context).modeRequested
    }

    fun isModeReady(context: Context): Boolean = state(context).modeEnabled

    fun setModeEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        AppPreferences(context.applicationContext).saveShizukuModeEnabled(enabled)
        stateChangedCallback?.invoke()
    }

    fun startListening(
        context: Context,
        onStateChanged: () -> Unit,
        onPermissionResult: (requestCode: Int, granted: Boolean) -> Unit,
    ) {
        initialize(context)
        stateChangedCallback = onStateChanged
        permissionResultCallback = onPermissionResult
        if (listenersRegistered.compareAndSet(false, true)) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        }
    }

    fun stopListening() {
        if (listenersRegistered.compareAndSet(true, false)) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        }
        stateChangedCallback = null
        permissionResultCallback = null
    }

    fun requestPermission(context: Context, requestCode: Int): ShizukuPermissionRequest {
        val current = state(context)
        if (!current.installed) return ShizukuPermissionRequest.MANAGER_NOT_INSTALLED
        if (!current.binderAvailable) return ShizukuPermissionRequest.SERVICE_UNAVAILABLE
        if (current.permissionGranted) return ShizukuPermissionRequest.ALREADY_GRANTED
        return runCatching {
            Shizuku.requestPermission(requestCode)
            ShizukuPermissionRequest.REQUESTED
        }.getOrDefault(ShizukuPermissionRequest.SERVICE_UNAVAILABLE)
    }

    fun capturePng(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): ByteArray? =
        runCommand(
            arguments = listOf("/system/bin/screencap", "-p"),
            timeoutMs = timeoutMs,
            maxStdoutBytes = MAX_SCREENSHOT_BYTES,
            commandLockTimeoutMs = timeoutMs,
        ).takeIf(CommandResult::successful)?.stdout

    fun captureBitmap(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap? {
        val png = capturePng(timeoutMs) ?: return null
        return BitmapFactory.decodeByteArray(png, 0, png.size)
    }

    fun captureScreen(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): Bitmap =
        captureBitmap(timeoutMs) ?: throw IllegalStateException("Shizuku screen capture failed")

    fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        holdMs: Long = 0L,
    ): ShizukuSwipeResult {
        if (minOf(startX, startY, endX, endY) < 0 ||
            (startX == endX && startY == endY)
        ) return ShizukuSwipeResult.UNAVAILABLE
        if (!isBinderReady() || !isPermissionGranted()) {
            return ShizukuSwipeResult.UNAVAILABLE
        }
        val duration = durationMs.coerceIn(
            MIN_SHIZUKU_SWIPE_DURATION_MS,
            MAX_SHIZUKU_SWIPE_DURATION_MS,
        )
        val hold = holdMs.coerceIn(0L, MAX_SHIZUKU_ENDPOINT_HOLD_MS)
        // No remote call has started when binding fails, so this cannot be an ambiguous swipe.
        // Reporting UNKNOWN here makes capture count unchanged frames as attempted gestures and
        // can eventually turn a mid-page UserService failure into a false end-of-list result.
        val owner = ensureUserService(USER_SERVICE_BIND_TIMEOUT_MS)
            ?: return ShizukuSwipeResult.UNAVAILABLE
        val result = swipeSessionGate.withOrdinarySwipe(
            owner = owner,
            ownerIsValid = { candidate -> candidate?.let(::isCurrentUserService) == true },
        ) {
            val remoteResult = callUserServiceRpc(
                owner = owner,
                timeoutMs = duration + hold + SWIPE_COMPLETION_RPC_GRACE_MS,
                onTimeout = { completion ->
                    deferOrdinarySwipeUntilRpcFinishes(owner, completion)
                },
            ) {
                owner.service.performSwipe(startX, startY, endX, endY, duration, hold)
            } ?: run {
                resetUserService(owner.generation)
                return@withOrdinarySwipe classifySwipeFailureFromRuntimeState()
            }
            decodeRemoteSwipeResult(remoteResult)
        }
        if (result != null) return result
        return ShizukuSwipeResult.UNAVAILABLE
    }

    fun awaitOrdinarySwipeIdle(timeoutMs: Long): Boolean =
        swipeSessionGate.awaitIdle(timeoutMs)

    fun reserveLinearSwipeSession(): Long? {
        if (!isBinderReady() || !isPermissionGranted()) return null
        val owner = ensureUserService(USER_SERVICE_BIND_TIMEOUT_MS) ?: return null
        var reserveRpcAttempted = false
        var reserveRpcFailed = false
        var orphanCleanupFailed = false
        val lease = runCatching {
            swipeSessionGate.reserve(
                owner = owner,
                ownerIsValid = ::isCurrentUserService,
                discardRemoteSession = { remoteSessionId ->
                    val released = callUserServiceRpc(
                        owner = owner,
                        timeoutMs = SWIPE_RELEASE_RPC_TIMEOUT_MS,
                        requireCurrent = false,
                    ) {
                        owner.service.releaseSwipeSession(remoteSessionId)
                    }
                    if (released == null) orphanCleanupFailed = true
                },
            ) {
                reserveRpcAttempted = true
                val remoteSessionId = callUserServiceRpc(
                    owner = owner,
                    timeoutMs = SWIPE_RESERVE_RPC_TIMEOUT_MS,
                ) {
                    owner.service.reserveLinearSwipeSession()
                }
                if (remoteSessionId == null) reserveRpcFailed = true
                remoteSessionId?.takeIf { it > 0L }
            }
        }.getOrElse {
            resetUserService(owner.generation)
            return null
        }
        if (lease == null) {
            if ((reserveRpcAttempted && reserveRpcFailed) || orphanCleanupFailed) {
                resetUserService(owner.generation)
            }
            return null
        }
        if (owner.service.asBinder().isBinderAlive && isCurrentUserService(owner)) return lease.handle

        swipeSessionGate.claimRelease(lease.handle)?.let { staleLease ->
            val rpcLease = swipeSessionGate.beginLeaseRpc(staleLease.handle)
            try {
                callUserServiceRpc(
                    owner = owner,
                    timeoutMs = SWIPE_RELEASE_RPC_TIMEOUT_MS,
                    requireCurrent = false,
                    onTimeout = { completion ->
                        deferLinearSwipeUntilRpcFinishes(staleLease.handle, completion)
                    },
                ) {
                    owner.service.releaseSwipeSession(staleLease.remoteSessionId)
                }
            } finally {
                if (rpcLease != null) swipeSessionGate.endLeaseRpc(rpcLease)
                swipeSessionGate.finish(staleLease)
            }
        }
        resetUserService(owner.generation)
        return null
    }

    fun performLinearSwipe(
        sessionId: Long,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): ShizukuSwipeResult {
        if (sessionId <= 0L || minOf(startX, startY, endX, endY) < 0 ||
            (startX == endX && startY == endY)
        ) return ShizukuSwipeResult.UNAVAILABLE
        if (!isBinderReady() || !isPermissionGranted()) {
            return ShizukuSwipeResult.UNAVAILABLE
        }
        val duration = durationMs.coerceIn(
            MIN_LINEAR_RECOGNITION_SWIPE_DURATION_MS,
            MAX_LINEAR_RECOGNITION_SWIPE_DURATION_MS,
        )
        val lease = swipeSessionGate.resolve(sessionId) ?: return ShizukuSwipeResult.UNAVAILABLE
        val owner = lease.owner
        if (!owner.service.asBinder().isBinderAlive) {
            resetUserService(owner.generation)
            return ShizukuSwipeResult.UNAVAILABLE
        }
        val rpcLease = swipeSessionGate.beginLeaseRpc(sessionId)
            ?: return ShizukuSwipeResult.UNAVAILABLE
        val remoteResult = try {
            callUserServiceRpc(
                owner = owner,
                timeoutMs = duration + SWIPE_COMPLETION_RPC_GRACE_MS,
                onTimeout = { completion ->
                    deferLinearSwipeUntilRpcFinishes(sessionId, completion)
                },
            ) {
                owner.service.performLinearSwipe(
                    lease.remoteSessionId,
                    startX,
                    startY,
                    endX,
                    endY,
                    duration,
                )
            }
        } finally {
            swipeSessionGate.endLeaseRpc(rpcLease)
        }
        if (remoteResult == null) {
            resetUserService(owner.generation)
            return classifySwipeFailureFromRuntimeState()
        }
        return decodeRemoteSwipeResult(remoteResult)
    }

    fun performReservedSwipe(
        sessionId: Long,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        holdMs: Long,
    ): ShizukuSwipeResult {
        if (sessionId <= 0L || minOf(startX, startY, endX, endY) < 0 ||
            (startX == endX && startY == endY)
        ) return ShizukuSwipeResult.UNAVAILABLE
        if (!isBinderReady() || !isPermissionGranted()) return ShizukuSwipeResult.UNAVAILABLE
        val duration = durationMs.coerceIn(
            MIN_SHIZUKU_SWIPE_DURATION_MS,
            MAX_SHIZUKU_SWIPE_DURATION_MS,
        )
        val hold = holdMs.coerceIn(0L, MAX_SHIZUKU_ENDPOINT_HOLD_MS)
        val lease = swipeSessionGate.resolve(sessionId) ?: return ShizukuSwipeResult.UNAVAILABLE
        val owner = lease.owner
        if (!owner.service.asBinder().isBinderAlive) {
            resetUserService(owner.generation)
            return ShizukuSwipeResult.UNAVAILABLE
        }
        val rpcLease = swipeSessionGate.beginLeaseRpc(sessionId)
            ?: return ShizukuSwipeResult.UNAVAILABLE
        val remoteResult = try {
            callUserServiceRpc(
                owner = owner,
                timeoutMs = duration + hold + SWIPE_COMPLETION_RPC_GRACE_MS,
                onTimeout = { completion ->
                    deferLinearSwipeUntilRpcFinishes(sessionId, completion)
                },
            ) {
                owner.service.performReservedSwipe(
                    lease.remoteSessionId,
                    startX,
                    startY,
                    endX,
                    endY,
                    duration,
                    hold,
                )
            }
        } finally {
            swipeSessionGate.endLeaseRpc(rpcLease)
        }
        if (remoteResult == null) {
            resetUserService(owner.generation)
            return classifySwipeFailureFromRuntimeState()
        }
        return decodeRemoteSwipeResult(remoteResult)
    }

    fun cancelSwipe(sessionId: Long): Boolean {
        if (sessionId <= 0L) return false
        val lease = swipeSessionGate.resolve(sessionId) ?: return false
        val owner = lease.owner
        if (!owner.service.asBinder().isBinderAlive) {
            resetUserService(owner.generation)
            return false
        }
        val rpcLease = swipeSessionGate.beginLeaseRpc(sessionId)
            ?: return false
        val cancelled = try {
            callUserServiceRpc(
                owner = owner,
                timeoutMs = SWIPE_CANCEL_RPC_TIMEOUT_MS,
                onTimeout = { completion ->
                    deferLinearSwipeUntilRpcFinishes(sessionId, completion)
                },
            ) { owner.service.cancelSwipe(lease.remoteSessionId) }
        } finally {
            swipeSessionGate.endLeaseRpc(rpcLease)
        }
        if (cancelled == null) {
            resetUserService(owner.generation)
            return false
        }
        if (!cancelled) resetUserService(owner.generation)
        return cancelled
    }

    /**
     * Waits until the active UserService swipe has successfully injected ACTION_DOWN.
     *
     * This intentionally does not acquire the swipe gate: the reservation owns it until release,
     * while this handshake must run concurrently with the blocking performLinearSwipe Binder call.
     */
    fun awaitSwipeStarted(sessionId: Long, timeoutMs: Long): Boolean {
        if (sessionId <= 0L) return false
        if (!isBinderReady() || !isPermissionGranted()) return false
        val lease = swipeSessionGate.resolve(sessionId) ?: return false
        val timeout = timeoutMs.coerceIn(0L, MAX_SWIPE_START_AWAIT_MS)
        val owner = lease.owner
        if (!owner.service.asBinder().isBinderAlive) {
            resetUserService(owner.generation)
            return false
        }
        val rpcLease = swipeSessionGate.beginLeaseRpc(sessionId)
            ?: return false
        val started = try {
            callUserServiceRpc(
                owner = owner,
                timeoutMs = timeout + SWIPE_START_RPC_GRACE_MS,
                onTimeout = { completion ->
                    deferLinearSwipeUntilRpcFinishes(sessionId, completion)
                },
            ) { owner.service.awaitSwipeStarted(lease.remoteSessionId, timeout) }
        } finally {
            swipeSessionGate.endLeaseRpc(rpcLease)
        }
        return started ?: run {
            resetUserService(owner.generation)
            false
        }
    }

    fun releaseSwipeSession(sessionId: Long) {
        if (sessionId <= 0L) return
        val lease = swipeSessionGate.claimRelease(sessionId)
        if (lease == null) {
            swipeSessionGate.awaitRelease(sessionId, SWIPE_RELEASE_WAIT_TIMEOUT_MS)
            return
        }
        val owner = lease.owner
        val rpcLease = swipeSessionGate.beginLeaseRpc(sessionId)
        try {
            if (owner.service.asBinder().isBinderAlive) {
                val released = callUserServiceRpc(
                    owner = owner,
                    timeoutMs = SWIPE_RELEASE_RPC_TIMEOUT_MS,
                    requireCurrent = false,
                    onTimeout = { completion ->
                        deferLinearSwipeUntilRpcFinishes(sessionId, completion)
                    },
                ) { owner.service.releaseSwipeSession(lease.remoteSessionId) }
                if (released == null) {
                    resetUserService(owner.generation)
                }
            } else {
                resetUserService(owner.generation)
            }
        } finally {
            if (rpcLease != null) swipeSessionGate.endLeaseRpc(rpcLease)
            swipeSessionGate.finish(lease)
        }
    }

    private fun <R> callUserServiceRpc(
        owner: ShizukuBoundService<IShizukuCommandService>,
        timeoutMs: Long,
        requireCurrent: Boolean = true,
        onTimeout: ((CountDownLatch) -> Unit)? = null,
        operation: () -> R,
    ): R? {
        if (!owner.service.asBinder().isBinderAlive ||
            (requireCurrent && !isCurrentUserService(owner))
        ) return null
        val fence = ShizukuRpcCompletionFence()
        val call = runCatching {
            userServiceRpcExecutor.submit(Callable {
                if (!fence.tryStart()) {
                    throw CancellationException("Shizuku RPC was cancelled before execution")
                }
                try {
                    check(!requireCurrent || isCurrentUserService(owner)) {
                        "Stale Shizuku UserService generation"
                    }
                    operation()
                } finally {
                    // Future.cancel(true) marks a Binder call cancelled before the remote transact
                    // necessarily returns. This latch represents the actual worker lifetime.
                    fence.finish()
                }
            })
        }.getOrNull() ?: return null
        return try {
            call.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            call.cancel(true)
            fence.cancelBeforeStart()
            if (fence.completion.count > 0L) runCatching { onTimeout?.invoke(fence.completion) }
            Thread.currentThread().interrupt()
            null
        } catch (_: TimeoutException) {
            call.cancel(true)
            fence.cancelBeforeStart()
            runCatching { onTimeout?.invoke(fence.completion) }
            null
        } catch (_: Throwable) {
            call.cancel(true)
            fence.cancelBeforeStart()
            if (fence.completion.count > 0L) runCatching { onTimeout?.invoke(fence.completion) }
            null
        }
    }

    private fun deferOrdinarySwipeUntilRpcFinishes(
        owner: ShizukuBoundService<IShizukuCommandService>,
        completion: CountDownLatch,
    ) {
        if (!swipeSessionGate.deferOrdinaryRelease(owner)) return
        val drain = Runnable {
            awaitRemoteSwipeDrain(completion)
            swipeSessionGate.finishDeferredOrdinaryRelease(owner)
        }
        runCatching {
            ioExecutor.execute {
                drain.run()
            }
        }.onFailure {
            runCatching {
                userServiceLifecycleExecutor.schedule(
                    drain,
                    REMOTE_SWIPE_DRAIN_FENCE_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun deferLinearSwipeUntilRpcFinishes(
        sessionId: Long,
        completion: CountDownLatch,
    ) {
        val lease = swipeSessionGate.deferLeaseRelease(sessionId) ?: return
        val drain = Runnable {
            awaitRemoteSwipeDrain(completion)
            swipeSessionGate.finishDeferredLeaseRelease(lease)
        }
        runCatching {
            ioExecutor.execute {
                drain.run()
            }
        }.onFailure {
            runCatching {
                userServiceLifecycleExecutor.schedule(
                    drain,
                    REMOTE_SWIPE_DRAIN_FENCE_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun awaitRemoteSwipeDrain(completion: CountDownLatch) {
        val deadline = SystemClock.elapsedRealtime() + REMOTE_SWIPE_DRAIN_FENCE_MS
        var interrupted = false
        try {
            while (completion.count > 0L) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    completion.await(remaining, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun classifySwipeFailureFromRuntimeState(): ShizukuSwipeResult {
        val binderAvailable = isBinderReady()
        val permissionGranted = binderAvailable && isPermissionGranted()
        // A remote false/exception can happen after ACTION_DOWN or MOVE was accepted. Never claim
        // the swipe did not occur while the runtime remains usable; capture must inspect the frame.
        return classifyShizukuSwipeFailure(binderAvailable, permissionGranted)
    }

    fun tryGrantOverlay(packageName: String, userId: Int): Boolean {
        if (!isSafePackageName(packageName) || userId < 0) return false
        val packageGrant = runCommand(
            listOf(
                "/system/bin/cmd",
                "appops",
                "set",
                "--user",
                userId.toString(),
                packageName,
                "SYSTEM_ALERT_WINDOW",
                "allow",
            ),
            timeoutMs = PERMISSION_COMMAND_TIMEOUT_MS,
        )
        // Some vendor builds keep SYSTEM_ALERT_WINDOW at UID scope even for an app-specific
        // settings toggle. Restore both scopes so Settings.canDrawOverlays() observes the grant.
        val uidGrant = runCommand(
            listOf(
                "/system/bin/cmd",
                "appops",
                "set",
                "--user",
                userId.toString(),
                "--uid",
                packageName,
                "SYSTEM_ALERT_WINDOW",
                "allow",
            ),
            timeoutMs = PERMISSION_COMMAND_TIMEOUT_MS,
        )
        return packageGrant.successful || uidGrant.successful
    }

    fun tryGrantNotification(packageName: String, userId: Int): Boolean {
        if (!isSafePackageName(packageName) || userId < 0) return false
        val arguments = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                "/system/bin/pm",
                "grant",
                "--user",
                userId.toString(),
                packageName,
                "android.permission.POST_NOTIFICATIONS",
            )
        } else {
            listOf(
                "/system/bin/cmd",
                "appops",
                "set",
                "--user",
                userId.toString(),
                packageName,
                "POST_NOTIFICATION",
                "allow",
            )
        }
        return runCommand(arguments, PERMISSION_COMMAND_TIMEOUT_MS).successful
    }

    internal fun currentForegroundSnapshot(
        timeoutMs: Long = TOP_PACKAGE_TIMEOUT_MS,
    ): ShizukuForegroundSnapshot? {
        val resumedActivity = currentResumedActivitySnapshot(timeoutMs)
        val resumedPackage = resumedActivity?.packageName
        val resumedComponent = resumedActivity?.componentName
        val compactWindowBounds = resumedActivity?.compactWindowBounds
        val windowDump = runCommand(
            listOf("/system/bin/dumpsys", "window"),
            timeoutMs,
            MAX_DUMPSYS_BYTES,
        )
        val focusedWindow = windowDump.takeIf { it.successful }
            ?.stdout
            ?.decodeToString()
            ?.let(::parseFocusedWindowIdentity)
        val ownPackageName = appContext?.packageName
        val focusedWindowIsOwnOverlay = focusedWindow?.packageName == ownPackageName &&
            resumedPackage != null && resumedPackage != ownPackageName
        val selectedWindow = focusedWindow.takeUnless { focusedWindowIsOwnOverlay }
        val selectedPackage = selectedWindow?.packageName ?: resumedPackage
        val selectedComponent = selectedWindow?.componentName ?: resumedComponent
        if (selectedPackage == null && compactWindowBounds == null) return null
        return ShizukuForegroundSnapshot(
            packageName = selectedPackage,
            componentName = selectedComponent,
            compactWindowBounds = compactWindowBounds,
        )
    }

    /**
     * Reads the resumed activity without the additional window dump. The overlay poller uses
     * this to verify focus changes and refresh compact-window bounds when the input snapshot is
     * inconclusive.
     */
    internal fun currentResumedActivitySnapshot(
        timeoutMs: Long = TOP_PACKAGE_TIMEOUT_MS,
        allowUserServiceBinding: Boolean = true,
        commandLockTimeoutMs: Long? = null,
    ): ShizukuForegroundSnapshot? {
        val activityDump = runCommand(
            arguments = listOf("/system/bin/dumpsys", "activity", "activities"),
            timeoutMs = timeoutMs,
            maxStdoutBytes = MAX_DUMPSYS_BYTES,
            allowUserServiceBinding = allowUserServiceBinding,
            commandLockTimeoutMs = commandLockTimeoutMs,
        )
        if (!activityDump.successful) return null
        val output = activityDump.stdout.decodeToString()
        val resumedPackage = parseTopPackage(output)
        val resumedComponent = parseTopComponent(output)
        val compactWindowBounds = parseTopCompactApplicationWindowBounds(output)
        if (resumedPackage == null && compactWindowBounds == null) return null
        return ShizukuForegroundSnapshot(
            packageName = resumedPackage,
            componentName = resumedComponent,
            compactWindowBounds = compactWindowBounds,
        )
    }

    internal fun currentInputNavigationSnapshot(
        timeoutMs: Long = TOP_PACKAGE_TIMEOUT_MS,
        allowUserServiceBinding: Boolean = true,
        commandLockTimeoutMs: Long? = null,
    ): ShizukuInputNavigationSnapshot? {
        val inputDump = runCommand(
            arguments = listOf("/system/bin/dumpsys", "input"),
            timeoutMs = timeoutMs,
            maxStdoutBytes = MAX_DUMPSYS_BYTES,
            allowUserServiceBinding = allowUserServiceBinding,
            commandLockTimeoutMs = commandLockTimeoutMs,
        )
        if (!inputDump.successful) return null
        return parseInputNavigationSnapshot(inputDump.stdout.decodeToString())
    }

    /** Starts the pre-open query without making the caller wait for UserService binding. */
    internal fun requestCurrentInputNavigationSnapshot(
        timeoutMs: Long,
    ): ShizukuInputNavigationSnapshotRequest {
        val budgetMs = timeoutMs.coerceAtLeast(1L)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
        val query = ioExecutor.submit(Callable {
            currentInputNavigationSnapshot(
                timeoutMs = budgetMs,
                commandLockTimeoutMs = budgetMs,
            )
        })
        return ShizukuInputNavigationSnapshotRequest(query, deadlineNanos)
    }

    fun currentForegroundPackage(timeoutMs: Long = TOP_PACKAGE_TIMEOUT_MS): String? =
        currentForegroundSnapshot(timeoutMs)?.packageName

    private fun runCommand(
        arguments: List<String>,
        timeoutMs: Long,
        maxStdoutBytes: Int = DEFAULT_STDOUT_LIMIT_BYTES,
        allowUserServiceBinding: Boolean = true,
        commandLockTimeoutMs: Long? = null,
    ): CommandResult {
        if (arguments.isEmpty() || !isBinderReady() || !isPermissionGranted()) {
            return CommandResult.unavailable()
        }
        val lockAcquired = try {
            if (commandLockTimeoutMs == null) {
                commandLock.lock()
                true
            } else {
                commandLock.tryLock(
                    commandLockTimeoutMs.coerceAtLeast(0L),
                    TimeUnit.MILLISECONDS,
                )
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!lockAcquired) return CommandResult.timedOut()
        return try {
            val owner = ensureUserService(
                timeoutMs = minOf(USER_SERVICE_BIND_TIMEOUT_MS, timeoutMs.coerceAtLeast(1L)),
                allowBinding = allowUserServiceBinding,
            ) ?: return CommandResult.failure("Shizuku user service is unavailable")
            if (Thread.currentThread().isInterrupted) return CommandResult.timedOut()
            executeBoundCommand(owner, arguments, timeoutMs, maxStdoutBytes)
        } finally {
            commandLock.unlock()
        }
    }

    private fun executeBoundCommand(
        owner: ShizukuBoundService<IShizukuCommandService>,
        arguments: List<String>,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): CommandResult {
        val output = callUserServiceRpc(
            owner = owner,
            timeoutMs = minOf(
                timeoutMs.coerceAtLeast(1L),
                COMMAND_START_RPC_TIMEOUT_MS,
            ),
        ) {
            owner.service.execute(
                arguments.toTypedArray(),
                timeoutMs.coerceAtLeast(1L),
                maxStdoutBytes.coerceIn(1, MAX_SCREENSHOT_BYTES),
            )
        } ?: run {
            resetUserService(owner.generation)
            return CommandResult.failure("Shizuku command start timed out")
        }
        val reader = ioExecutor.submit(Callable {
            readCommandResult(output, maxStdoutBytes)
        })
        return try {
            reader.get(
                timeoutMs.coerceAtLeast(1L) + COMMAND_RESULT_GRACE_MS,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: TimeoutException) {
            runCatching { output.close() }
            reader.cancel(true)
            CommandResult.timedOut()
        } catch (_: InterruptedException) {
            reader.cancel(true)
            Thread.currentThread().interrupt()
            CommandResult.timedOut()
        } catch (error: Throwable) {
            CommandResult.failure(error.message)
        } finally {
            runCatching { output.close() }
        }
    }

    private fun ensureUserService(
        timeoutMs: Long,
        allowBinding: Boolean = true,
    ): ShizukuBoundService<IShizukuCommandService>? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        var ownedGeneration: ShizukuServiceEpochGate.Generation<IShizukuCommandService>? = null
        while (true) {
            val current = userServiceEpochGate.currentGeneration()
            val connected = current?.valueOrNull()
            if (current != null && userServiceEpochGate.isCurrent(current)) {
                if (connected?.asBinder()?.isBinderAlive == true) {
                    return ShizukuBoundService(current, connected)
                }
                if (connected != null) {
                    resetUserService(current)
                    if (!allowBinding) return null
                    continue
                }
            }
            if (!allowBinding) return null

            if (current == null) {
                val generation = userServiceEpochGate.beginBinding()
                if (generation != null) {
                    ownedGeneration = generation
                    val binding = createUserServiceBinding(generation)
                    if (binding == null) {
                        resetUserService(generation)
                        return null
                    }
                    userServiceBindings[generation] = binding
                    val launched = runCatching {
                        ioExecutor.execute {
                            var invoked = false
                            try {
                                invoked = runCatching {
                                    userServiceEpochGate.invokeBindIfCurrent(generation) {
                                        // The Binder call intentionally runs outside the epoch-gate
                                        // monitor. A stalled Shizuku server must not block reset or
                                        // the watchdog from making progress.
                                        Shizuku.bindUserService(binding.args, binding.connection)
                                    }
                                }.getOrElse {
                                    resetUserService(generation)
                                    false
                                }
                            } finally {
                                val requiresLateCleanup =
                                    !invoked || !userServiceEpochGate.isCurrent(generation)
                                binding.lifecycle.markBindCallCompleted(requiresLateCleanup)
                                if (requiresLateCleanup) {
                                    // Reset may win while bindUserService is still in flight. The
                                    // unique tag lets us remove only this late service.
                                    cleanupStaleUserServiceGeneration(generation, binding)
                                }
                                maybeReleaseUserServiceBinding(generation, binding)
                            }
                        }
                    }.isSuccess
                    if (!launched) {
                        resetUserService(generation)
                    }
                }
            }

            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0L) {
                // Only tear down a generation this caller started. Another caller may have
                // replaced the service while this one was waiting.
                ownedGeneration?.let { generation ->
                    if (userServiceEpochGate.currentGeneration() === generation) {
                        resetUserService(generation)
                    }
                }
                return null
            }
            if (!userServiceEpochGate.awaitChange(
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L),
                )
            ) {
                return null
            }
        }
    }

    private fun isCurrentUserService(expected: ShizukuBoundService<IShizukuCommandService>): Boolean =
        userServiceEpochGate.isCurrent(expected.generation) &&
            expected.generation.valueOrNull() === expected.service &&
            expected.service.asBinder().isBinderAlive

    private fun invalidateSwipeOwner(owner: ShizukuBoundService<IShizukuCommandService>?) {
        if (owner == null) return
        val staleLease = swipeSessionGate.invalidateOwner { candidate ->
            candidate.generation === owner.generation
        }
        staleLease?.let { lease ->
            // The owner was invalidated before its caller could issue release. Keep the remote
            // session cleanup explicit; a later generation must not inherit an active pointer.
            runCatching {
                ioExecutor.execute {
                    val rpcLease = swipeSessionGate.beginLeaseRpc(lease.handle)
                    try {
                        runCatching {
                            callUserServiceRpc(
                                owner = lease.owner,
                                timeoutMs = SWIPE_RELEASE_RPC_TIMEOUT_MS,
                                requireCurrent = false,
                                onTimeout = { completion ->
                                    deferLinearSwipeUntilRpcFinishes(lease.handle, completion)
                                },
                            ) {
                                lease.owner.service.releaseSwipeSession(lease.remoteSessionId)
                            }
                        }
                    } finally {
                        if (rpcLease != null) swipeSessionGate.endLeaseRpc(rpcLease)
                        swipeSessionGate.finish(lease)
                    }
                }
            }.onFailure {
                swipeSessionGate.finish(lease)
            }
        }
    }

    private fun resetUserService(
        expected: ShizukuBoundService<IShizukuCommandService>? = null,
    ) {
        val generation = userServiceEpochGate.currentGeneration()
        if (generation == null) {
            invalidateSwipeOwner(expected)
            return
        }
        if (expected != null && expected.generation !== generation) {
            invalidateSwipeOwner(expected)
            return
        }
        resetUserService(generation)
    }

    private fun resetUserService(
        generation: ShizukuServiceEpochGate.Generation<IShizukuCommandService>,
    ) {
        val reset = userServiceEpochGate.beginReset(generation) ?: return
        invalidateSwipeOwner(
            generation.valueOrNull()?.let { service -> ShizukuBoundService(generation, service) },
        )
        if (reset.shouldRemoveRemoteService) {
            removeUserServiceProcess(generation)
        } else if (!generation.bindStarted) {
            userServiceBindings.remove(generation)
        }
    }

    private fun cleanupStaleUserServiceGeneration(
        generation: ShizukuServiceEpochGate.Generation<IShizukuCommandService>,
        bindingOverride: UserServiceBinding? = null,
    ) {
        resetUserService(generation)
        if (!generation.bindStarted) return
        val binding = bindingOverride ?: userServiceBindings[generation] ?: return
        removeUserServiceProcess(generation, binding)
        // The original bind Binder call can return after the watchdog and the first removal. Send
        // an idempotent exact-tag removal from the bind completion/stale callback path as well.
        if (!binding.lifecycle.claimLateCleanup()) return
        runCatching {
            ioExecutor.execute {
                try {
                    runCatching {
                        Shizuku.unbindUserService(binding.args, binding.connection, true)
                    }
                    runCatching {
                        Shizuku.unbindUserService(binding.args, binding.connection, false)
                    }
                } finally {
                    binding.lifecycle.markLateCleanupCompleted()
                    maybeReleaseUserServiceBinding(generation, binding)
                }
            }
        }
    }

    private fun maybeReleaseUserServiceBinding(
        generation: ShizukuServiceEpochGate.Generation<IShizukuCommandService>,
        binding: UserServiceBinding,
    ) {
        if (!binding.lifecycle.canReleaseTombstone()) return
        userServiceBindings.remove(generation, binding)
    }

    private fun removeUserServiceProcess(
        generation: ShizukuServiceEpochGate.Generation<IShizukuCommandService>,
        bindingOverride: UserServiceBinding? = null,
    ) {
        val binding = bindingOverride ?: userServiceBindings[generation]
        if (binding == null) {
            userServiceEpochGate.finishRemoval(generation)
            return
        }
        if (!binding.lifecycle.claimRemoval()) return
        val finished = AtomicBoolean(false)
        val watchdog = AtomicReference<ScheduledFuture<*>?>(null)
        fun finishRemoval() {
            if (!finished.compareAndSet(false, true)) return
            watchdog.get()?.cancel(false)
            userServiceEpochGate.finishRemoval(generation)
        }
        watchdog.set(
            userServiceLifecycleExecutor.schedule(
                ::finishRemoval,
                USER_SERVICE_UNBIND_WATCHDOG_MS,
                TimeUnit.MILLISECONDS,
            ),
        )
        runCatching {
            ioExecutor.execute {
                try {
                    Shizuku.unbindUserService(binding.args, binding.connection, true)
                } finally {
                    // API 13.1.5 leaves the client-side connection cache behind after
                    // remove=true. Detach this generation's callback without touching newer tags.
                    runCatching {
                        Shizuku.unbindUserService(binding.args, binding.connection, false)
                    }
                    binding.lifecycle.markRemovalCompleted()
                    finishRemoval()
                    maybeReleaseUserServiceBinding(generation, binding)
                }
            }
        }.onFailure {
            finishRemoval()
        }
    }

    private fun readCommandResult(
        descriptor: ParcelFileDescriptor,
        maxStdoutBytes: Int,
    ): CommandResult = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { rawInput ->
        DataInputStream(BufferedInputStream(rawInput)).use { input ->
            check(input.readInt() == ShizukuCommandProtocol.MAGIC) {
                "Invalid Shizuku command response"
            }
            check(input.readInt() == ShizukuCommandProtocol.VERSION) {
                "Unsupported Shizuku command response"
            }
            val exitCode = input.readInt()
            val timedOut = input.readBoolean()
            val outputTruncated = input.readBoolean()
            val stdoutSize = input.readInt()
            val stderrSize = input.readInt()
            check(stdoutSize in 0..maxStdoutBytes.coerceIn(1, MAX_SCREENSHOT_BYTES)) {
                "Invalid Shizuku stdout size"
            }
            check(stderrSize in 0..MAX_STDERR_BYTES) { "Invalid Shizuku stderr size" }
            val stdout = ByteArray(stdoutSize).also(input::readFully)
            val stderr = ByteArray(stderrSize).also(input::readFully).decodeToString()
            CommandResult(exitCode, stdout, stderr, timedOut, outputTruncated)
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: String,
        val timedOut: Boolean,
        val outputTruncated: Boolean,
    ) {
        val successful: Boolean
            get() = exitCode == 0 && !timedOut && !outputTruncated

        companion object {
            fun unavailable() = failure("Shizuku is not ready")
            fun timedOut() = CommandResult(-1, byteArrayOf(), "Command timed out", true, false)
            fun failure(message: String?) =
                CommandResult(-1, byteArrayOf(), message.orEmpty(), false, false)
        }
    }

    const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    private const val SCREENSHOT_TIMEOUT_MS = 8_000L
    private const val PERMISSION_COMMAND_TIMEOUT_MS = 4_000L
    private const val TOP_PACKAGE_TIMEOUT_MS = 4_000L
    private const val COMMAND_RESULT_GRACE_MS = 500L
    private const val COMMAND_START_RPC_TIMEOUT_MS = 2_000L
    private const val USER_SERVICE_BIND_TIMEOUT_MS = 5_000L
    private const val USER_SERVICE_UNBIND_WATCHDOG_MS = 5_000L
    private const val USER_SERVICE_VERSION = 8
    private const val USER_SERVICE_TAG = "game_long_shot_command"
    private const val MAX_SCREENSHOT_BYTES = 64 * 1024 * 1024
    private const val MAX_DUMPSYS_BYTES = 2 * 1024 * 1024
    private const val DEFAULT_STDOUT_LIMIT_BYTES = 256 * 1024
    private const val MAX_STDERR_BYTES = 64 * 1024
    private const val MIN_LINEAR_RECOGNITION_SWIPE_DURATION_MS = 1_000L
    private const val MAX_LINEAR_RECOGNITION_SWIPE_DURATION_MS = 10_000L
    private const val MAX_SWIPE_START_AWAIT_MS = 5_000L
    // The remote service may spend 1 s waiting for completion and another 2 s cancelling an
    // injected pointer. Keep the client timeout beyond that entire cleanup path.
    private const val SWIPE_COMPLETION_RPC_GRACE_MS = 4_000L
    private const val SWIPE_START_RPC_GRACE_MS = 1_000L
    private const val SWIPE_RESERVE_RPC_TIMEOUT_MS = 2_000L
    private const val SWIPE_CANCEL_RPC_TIMEOUT_MS = 3_500L
    private const val SWIPE_RELEASE_RPC_TIMEOUT_MS = 3_500L
    private const val SWIPE_RELEASE_WAIT_TIMEOUT_MS = 4_000L
    private const val REMOTE_SWIPE_DRAIN_FENCE_MS = 20_000L
    private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    private val TOP_PACKAGE_PATTERNS = listOf(
        Regex("(?:topResumedActivity|mResumedActivity)[^\\n]*?\\s(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/"),
        Regex("mCurrentFocus[^\\n]*?\\s(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/"),
    )
    private val TOP_COMPONENT_PATTERNS = listOf(
        // Some Android builds print a relative class after the slash (for example
        // `com.example/.MainActivity`). Keep the leading dot in the component value so callers
        // can still distinguish Recents/Overview from an ordinary SystemUI window.
        Regex("(?:topResumedActivity|mResumedActivity)[^\\n]*?\\s(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/\\.?[A-Za-z0-9_$.]+)"),
        Regex("mCurrentFocus[^\\n]*?\\s(?:u\\d+\\s+)?([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+/\\.?[A-Za-z0-9_$.]+)"),
    )
    private fun isManagerInstalled(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                MANAGER_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(
                MANAGER_PACKAGE,
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
        }
    }.isSuccess

    private fun isSafePackageName(packageName: String): Boolean =
        PACKAGE_NAME_PATTERN.matches(packageName)

    private fun loadModeEnabled(context: Context, installed: Boolean): Boolean {
        val preferences = AppPreferences(context)
        val requested = preferences.loadShizukuModeEnabled()
        if (requested && !installed) preferences.saveShizukuModeEnabled(false)
        return requested && installed
    }

    private fun parseTopPackage(output: String): String? =
        TOP_PACKAGE_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(output)?.groupValues?.getOrNull(1)
        }

    private fun parseTopComponent(output: String): String? =
        TOP_COMPONENT_PATTERNS.firstNotNullOfOrNull { pattern ->
            pattern.find(output)?.groupValues?.getOrNull(1)
        }
}
