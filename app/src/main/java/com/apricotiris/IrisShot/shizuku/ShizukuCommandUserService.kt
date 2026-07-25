package com.apricotiris.IrisShot.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** Runs in Shizuku's shell-identity user-service process. */
class ShizukuCommandUserService : IShizukuCommandService.Stub {
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "GameLongShot-ShizukuCommand").apply { isDaemon = true }
    }

    private var inputManager: Any? = null
    private val inputInjector by lazy {
        SystemInputEventInjector(inputManager)
    }
    private val activeSwipe = AtomicReference<ShizukuSwipeSession?>(null)
    private val swipeSessions = ConcurrentHashMap<Long, ShizukuSwipeSession>()
    private val nextSwipeSessionId = AtomicLong(1L)
    private val lastStartedSwipeSessionId = AtomicLong(0L)
    private val swipeRuntimeHealthy = AtomicBoolean(true)

    constructor() : super()

    constructor(context: Context) : super() {
        inputManager = context.getSystemService(Context.INPUT_SERVICE)
    }

    override fun execute(
        arguments: Array<out String>?,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        executor.execute {
            val result = executeCommand(
                arguments = arguments?.toList().orEmpty(),
                timeoutMs = timeoutMs.coerceIn(1L, MAX_COMMAND_TIMEOUT_MS),
                maxStdoutBytes = maxStdoutBytes.coerceIn(1, MAX_STDOUT_BYTES),
            )
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { rawOutput ->
                    DataOutputStream(BufferedOutputStream(rawOutput)).use { output ->
                        output.writeInt(ShizukuCommandProtocol.MAGIC)
                        output.writeInt(ShizukuCommandProtocol.VERSION)
                        output.writeInt(result.exitCode)
                        output.writeBoolean(result.timedOut)
                        output.writeBoolean(result.outputTruncated)
                        output.writeInt(result.stdout.size)
                        output.writeInt(result.stderr.size)
                        output.write(result.stdout)
                        output.write(result.stderr)
                    }
                }
            }.onFailure {
                runCatching { writeSide.close() }
            }
        }
        return readSide
    }

    override fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        holdMs: Long,
    ): Int {
        if (!swipeRuntimeHealthy.get()) return REMOTE_SWIPE_NOT_STARTED
        if (minOf(startX, startY, endX, endY) < 0 ||
            (startX == endX && startY == endY)
        ) return REMOTE_SWIPE_NOT_STARTED
        val swipe = ShizukuSwipeSession()
        if (!registerActiveSwipe(swipe)) return REMOTE_SWIPE_NOT_STARTED
        if (!swipe.claimExecution()) {
            finishActiveSwipe(swipe)
            return REMOTE_SWIPE_NOT_STARTED
        }
        val completed = performRegisteredSwipe(
            swipe = swipe,
            plan = buildShizukuSwipePlan(durationMs, holdMs),
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
        )
        return remoteSwipeResult(started = swipe.hasStarted(), completed = completed)
    }

    override fun reserveLinearSwipeSession(): Long {
        if (!swipeRuntimeHealthy.get()) return 0L
        if (!clearAbandonedReservation()) return 0L
        val sessionId = nextSwipeSessionId.getAndIncrement().takeIf { it > 0L } ?: return 0L
        val swipe = ShizukuSwipeSession(sessionId)
        swipeSessions[sessionId] = swipe
        if (registerActiveSwipe(swipe)) return sessionId
        swipeSessions.remove(sessionId, swipe)
        return 0L
    }

    override fun performLinearSwipe(
        sessionId: Long,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Int {
        if (!swipeRuntimeHealthy.get()) return REMOTE_SWIPE_NOT_STARTED
        if (minOf(startX, startY, endX, endY) < 0 ||
            (startX == endX && startY == endY) ||
            sessionId <= 0L
        ) return REMOTE_SWIPE_NOT_STARTED
        val swipe = swipeSessions[sessionId] ?: return REMOTE_SWIPE_NOT_STARTED
        if (activeSwipe.get() !== swipe || !swipe.claimExecution()) {
            return REMOTE_SWIPE_NOT_STARTED
        }
        return try {
            val completed = performRegisteredSwipe(
                swipe = swipe,
                plan = buildLinearShizukuSwipePlan(durationMs),
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
            )
            remoteSwipeResult(started = swipe.hasStarted(), completed = completed)
        } finally {
            swipeSessions.remove(sessionId, swipe)
        }
    }

    override fun performReservedSwipe(
        sessionId: Long,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        holdMs: Long,
    ): Int {
        if (!swipeRuntimeHealthy.get()) return REMOTE_SWIPE_NOT_STARTED
        if (minOf(startX, startY, endX, endY) < 0 ||
            (startX == endX && startY == endY) ||
            sessionId <= 0L
        ) return REMOTE_SWIPE_NOT_STARTED
        val swipe = swipeSessions[sessionId] ?: return REMOTE_SWIPE_NOT_STARTED
        if (activeSwipe.get() !== swipe || !swipe.claimExecution()) {
            return REMOTE_SWIPE_NOT_STARTED
        }
        return try {
            val completed = performRegisteredSwipe(
                swipe = swipe,
                plan = buildShizukuSwipePlan(durationMs, holdMs),
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
            )
            remoteSwipeResult(started = swipe.hasStarted(), completed = completed)
        } finally {
            swipeSessions.remove(sessionId, swipe)
        }
    }

    override fun cancelSwipe(sessionId: Long): Boolean {
        if (!swipeRuntimeHealthy.get()) return false
        val swipe = swipeSessions[sessionId] ?: return true
        if (swipe.requestCancellation()) finishActiveSwipe(swipe)
        if (!awaitSwipeFinishedAfterCancellation(swipe)) {
            terminateStuckSwipeRuntime(swipe, "cancel timeout")
        }
        return true
    }

    override fun awaitSwipeStarted(sessionId: Long, timeoutMs: Long): Boolean {
        if (!swipeRuntimeHealthy.get()) return false
        if (lastStartedSwipeSessionId.get() == sessionId) return true
        val swipe = swipeSessions[sessionId]
            ?: return lastStartedSwipeSessionId.get() == sessionId
        val timeout = timeoutMs.coerceIn(0L, MAX_SWIPE_START_AWAIT_MS)
        val started = try {
            swipe.awaitStarted(timeout)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return started || lastStartedSwipeSessionId.get() == sessionId
    }

    override fun releaseSwipeSession(sessionId: Long) {
        val swipe = swipeSessions[sessionId] ?: return
        if (activeSwipe.get() === swipe && swipe.requestCancellation()) {
            finishActiveSwipe(swipe)
        }
        if (activeSwipe.get() === swipe && !awaitSwipeFinishedAfterCancellation(swipe)) {
            terminateStuckSwipeRuntime(swipe, "release timeout")
        }
        swipeSessions.remove(sessionId, swipe)
    }

    private fun performRegisteredSwipe(
        swipe: ShizukuSwipeSession,
        plan: ShizukuSwipePlan,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
    ): Boolean {
        val task = try {
            executor.submit(Callable {
                try {
                    swipe.attachWorker()
                    if (swipe.isCancellationRequested()) {
                        false
                    } else {
                        injectSwipe(startX, startY, endX, endY, plan, swipe)
                    }
                } finally {
                    finishActiveSwipe(swipe)
                }
            })
        } catch (_: RejectedExecutionException) {
            finishActiveSwipe(swipe)
            return false
        }
        return try {
            task.get(plan.releaseElapsedMs + GESTURE_COMPLETION_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            swipe.requestCancellation()
            if (!awaitSwipeFinishedAfterCancellation(swipe)) {
                terminateStuckSwipeRuntime(swipe, "interrupted swipe cleanup timeout")
            }
            Thread.currentThread().interrupt()
            false
        } catch (_: CancellationException) {
            swipe.requestCancellation()
            if (!awaitSwipeFinishedAfterCancellation(swipe)) {
                terminateStuckSwipeRuntime(swipe, "cancelled swipe cleanup timeout")
            }
            false
        } catch (_: ExecutionException) {
            false
        } catch (_: TimeoutException) {
            swipe.requestCancellation()
            if (!awaitSwipeFinishedAfterCancellation(swipe)) {
                terminateStuckSwipeRuntime(swipe, "gesture execution timeout")
            }
            false
        }
    }

    override fun destroy() {
        activeSwipe.get()?.let { swipe ->
            if (swipe.requestCancellation()) finishActiveSwipe(swipe)
            if (!awaitSwipeFinishedAfterCancellation(swipe)) {
                terminateStuckSwipeRuntime(swipe, "service destroy cleanup timeout")
            }
        }
        swipeSessions.clear()
        executor.shutdownNow()
        try {
            executor.awaitTermination(GESTURE_CANCEL_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        System.exit(0)
    }

    private fun injectSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        plan: ShizukuSwipePlan,
        swipe: ShizukuSwipeSession,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        var pointerDown = false
        var currentX = startX.toFloat()
        var currentY = startY.toFloat()
        var interrupted = false
        return try {
            if (swipe.isCancellationRequested()) throw InterruptedException()
            sendPointerEvent(
                downTime = downTime,
                action = MotionEvent.ACTION_DOWN,
                x = currentX,
                y = currentY,
            )
            pointerDown = true
            swipe.markStarted()
            if (swipe.sessionId > 0L) lastStartedSwipeSessionId.set(swipe.sessionId)
            plan.movementSamples.drop(1).forEach { sample ->
                waitUntilUptime(downTime + sample.elapsedMs)
                currentX = startX + (endX - startX) * sample.distanceFraction
                currentY = startY + (endY - startY) * sample.distanceFraction
                sendPointerEvent(
                    downTime = downTime,
                    action = MotionEvent.ACTION_MOVE,
                    x = currentX,
                    y = currentY,
                )
            }
            currentX = endX.toFloat()
            currentY = endY.toFloat()
            waitInterruptibly(plan.endpointHoldDurationMs)
            sendPointerEvent(
                downTime = downTime,
                action = MotionEvent.ACTION_UP,
                x = currentX,
                y = currentY,
            )
            pointerDown = false
            true
        } catch (_: InterruptedException) {
            interrupted = true
            false
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "Shizuku swipe injection failed", error)
            false
        } finally {
            if (pointerDown) {
                runCatching {
                    sendPointerEvent(
                        downTime = downTime,
                        action = MotionEvent.ACTION_CANCEL,
                        x = currentX,
                        y = currentY,
                    )
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun registerActiveSwipe(swipe: ShizukuSwipeSession): Boolean =
        activeSwipe.compareAndSet(null, swipe)

    /** Reclaims a reserve request whose Binder reply never reached the app process. */
    private fun clearAbandonedReservation(): Boolean {
        val existing = activeSwipe.get() ?: return true
        if (!existing.isReserved() || !existing.requestCancellation()) return false
        finishActiveSwipe(existing)
        swipeSessions.remove(existing.sessionId, existing)
        return activeSwipe.get() == null
    }

    private fun finishActiveSwipe(swipe: ShizukuSwipeSession) {
        swipe.markStartFailed()
        activeSwipe.compareAndSet(swipe, null)
        swipe.markFinished()
    }

    private fun awaitSwipeFinishedAfterCancellation(swipe: ShizukuSwipeSession): Boolean {
        var interrupted = Thread.interrupted()
        var finished = false
        try {
            finished = swipe.awaitFinished(GESTURE_CANCEL_GRACE_MS)
        } catch (_: InterruptedException) {
            interrupted = true
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return finished
    }

    private fun terminateStuckSwipeRuntime(
        swipe: ShizukuSwipeSession,
        reason: String,
    ): Nothing {
        swipeRuntimeHealthy.set(false)
        Log.e(
            LOG_TAG,
            "Restarting Shizuku UserService after $reason; session=${swipe.sessionId}",
        )
        swipeSessions.clear()
        executor.shutdownNow()
        // This process is dedicated to the UserService. Keeping it alive could leave an injected
        // pointer and activeSwipe owned by an uninterruptible platform call forever.
        exitProcess(0)
    }

    private fun sendPointerEvent(
        downTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val eventTime = SystemClock.uptimeMillis()
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).also { event ->
            try {
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                check(inputInjector.inject(event, action)) {
                    "InputManager rejected the pointer event"
                }
            } finally {
                event.recycle()
            }
        }
    }

    private fun waitUntilUptime(targetUptimeMs: Long) {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val remaining = targetUptimeMs - SystemClock.uptimeMillis()
            if (remaining <= 0L) return
            Thread.sleep(remaining)
        }
    }

    private fun waitInterruptibly(durationMs: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (durationMs > 0L) Thread.sleep(durationMs)
    }

    private fun executeCommand(
        arguments: List<String>,
        timeoutMs: Long,
        maxStdoutBytes: Int,
    ): RemoteCommandResult {
        if (arguments.isEmpty()) return RemoteCommandResult.failure("Command is empty")
        val process = runCatching {
            ProcessBuilder(arguments).redirectErrorStream(false).start()
        }.getOrElse { return RemoteCommandResult.failure(it.message) }
        val stdout = executor.submit(Callable {
            process.inputStream.readLimited(maxStdoutBytes)
        })
        val stderr = executor.submit(Callable {
            process.errorStream.readLimited(MAX_STDERR_BYTES)
        })
        return try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                if (!process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                }
            }
            val stdoutResult = stdout.resultOrTruncated(DRAIN_TIMEOUT_MS)
            val stderrResult = stderr.resultOrTruncated(DRAIN_TIMEOUT_MS)
            RemoteCommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                stdout = stdoutResult.bytes,
                stderr = stderrResult.bytes,
                timedOut = !finished,
                outputTruncated = stdoutResult.truncated || stderrResult.truncated,
            )
        } catch (error: Throwable) {
            runCatching { process.destroyForcibly() }
            RemoteCommandResult.failure(error.message)
        } finally {
            stdout.cancel(true)
            stderr.cancel(true)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    private data class LimitedOutput(val bytes: ByteArray, val truncated: Boolean)

    private data class RemoteCommandResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
        val timedOut: Boolean,
        val outputTruncated: Boolean,
    ) {
        companion object {
            fun failure(message: String?) = RemoteCommandResult(
                exitCode = -1,
                stdout = byteArrayOf(),
                stderr = message.orEmpty().encodeToByteArray(),
                timedOut = false,
                outputTruncated = false,
            )
        }
    }

    private fun Future<LimitedOutput>.resultOrTruncated(timeoutMs: Long): LimitedOutput = try {
        get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        LimitedOutput(byteArrayOf(), true)
    } catch (_: ExecutionException) {
        LimitedOutput(byteArrayOf(), true)
    } catch (_: TimeoutException) {
        cancel(true)
        LimitedOutput(byteArrayOf(), true)
    }

    private fun InputStream.readLimited(maxBytes: Int): LimitedOutput {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var truncated = false
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            val remaining = maxBytes - output.size()
            if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
            if (count > remaining) truncated = true
        }
        return LimitedOutput(output.toByteArray(), truncated)
    }

    private companion object {
        const val LOG_TAG = "GameLongShot-Shizuku"
        const val MAX_COMMAND_TIMEOUT_MS = 15_000L
        const val PROCESS_DESTROY_GRACE_MS = 500L
        const val DRAIN_TIMEOUT_MS = 2_000L
        const val MAX_STDOUT_BYTES = 64 * 1024 * 1024
        const val MAX_STDERR_BYTES = 64 * 1024
        const val GESTURE_COMPLETION_GRACE_MS = 1_000L
        const val GESTURE_CANCEL_GRACE_MS = 2_000L
        const val MAX_SWIPE_START_AWAIT_MS = 5_000L
    }
}

/** Uses shell's INJECT_EVENTS permission without restricting delivery to the shell UID. */
private class SystemInputEventInjector(inputManager: Any?) {
    private val target: Any = inputManager ?: run {
        val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
        globalClass.getMethod("getInstance").invoke(null)
    }
    private val injectMethod = target.javaClass.getMethod(
        "injectInputEvent",
        InputEvent::class.java,
        Int::class.javaPrimitiveType,
    )

    fun inject(event: InputEvent, action: Int): Boolean {
        val syncMode = when (action) {
            MotionEvent.ACTION_MOVE -> INJECT_ASYNC
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> INJECT_WAIT_FOR_FINISH
            else -> INJECT_WAIT_FOR_RESULT
        }
        return injectMethod.invoke(target, event, syncMode) as? Boolean == true
    }

    private companion object {
        // android.os.InputEventInjectionSync.NONE / WAIT_FOR_RESULT
        const val INJECT_ASYNC = 0
        const val INJECT_WAIT_FOR_RESULT = 1
        const val INJECT_WAIT_FOR_FINISH = 2
    }
}

internal object ShizukuCommandProtocol {
    const val MAGIC = 0x474C535A
    const val VERSION = 1
}
