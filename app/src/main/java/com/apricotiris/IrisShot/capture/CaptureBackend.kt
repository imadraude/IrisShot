package com.apricotiris.IrisShot.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.apricotiris.IrisShot.shizuku.ShizukuBridge

internal interface CaptureBackend : AutoCloseable {
    val isReady: Boolean
    val usesShizuku: Boolean

    fun captureFreshFrame(
        expectedSpec: MediaProjectionCaptureManager.DisplaySpec,
        timeoutMs: Long,
    ): Bitmap

    fun pause(shouldPause: () -> Boolean = { true })
}

internal sealed class CaptureBackendException : IllegalStateException() {
    data object ProjectionFrameUnavailable : CaptureBackendException()
    data object ShizukuUnavailable : CaptureBackendException()
    data class ShizukuSizeMismatch(
        val actual: String,
        val expected: String,
    ) : CaptureBackendException()
}

internal class ProjectionCaptureBackend(context: Context) : CaptureBackend {
    private val manager = MediaProjectionCaptureManager(context)

    var onStopped: (() -> Unit)?
        get() = manager.onProjectionStopped
        set(value) {
            manager.onProjectionStopped = value
        }

    override val isReady: Boolean
        get() = manager.hasProjection

    override val usesShizuku: Boolean = false

    fun start(resultCode: Int, resultData: Intent) {
        manager.start(resultCode, resultData)
    }

    override fun captureFreshFrame(
        expectedSpec: MediaProjectionCaptureManager.DisplaySpec,
        timeoutMs: Long,
    ): Bitmap {
        manager.ensureDisplay(expectedSpec)
        val barrier = manager.clearFrameBarrier()
        return manager.awaitFrame(barrier, timeoutMs)?.bitmap
            ?: throw CaptureBackendException.ProjectionFrameUnavailable
    }

    override fun pause(shouldPause: () -> Boolean) = manager.pauseDisplayIf(shouldPause)

    override fun close() = manager.close()
}

internal class ShizukuCaptureBackend(context: Context) : CaptureBackend {
    private val appContext = context.applicationContext

    override val isReady: Boolean
        get() = ShizukuBridge.isModeReady(appContext)

    override val usesShizuku: Boolean = true

    override fun captureFreshFrame(
        expectedSpec: MediaProjectionCaptureManager.DisplaySpec,
        timeoutMs: Long,
    ): Bitmap {
        if (!isReady) throw CaptureBackendException.ShizukuUnavailable
        val bitmap = ShizukuBridge.captureScreen(timeoutMs)
        if (bitmap.width != expectedSpec.width || bitmap.height != expectedSpec.height) {
            val actual = "${bitmap.width}x${bitmap.height}"
            val expected = "${expectedSpec.width}x${expectedSpec.height}"
            bitmap.recycle()
            throw CaptureBackendException.ShizukuSizeMismatch(actual, expected)
        }
        return bitmap
    }

    override fun pause(shouldPause: () -> Boolean) = Unit

    override fun close() = Unit
}
