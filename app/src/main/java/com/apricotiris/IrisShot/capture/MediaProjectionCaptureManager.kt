package com.apricotiris.IrisShot.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.apricotiris.IrisShot.core.image.BitmapFrameUtils

internal fun shouldPublishCapturedFrame(
    sourceIsCurrent: Boolean,
    stopped: Boolean,
    frameGeneration: Long,
    currentGeneration: Long,
): Boolean = sourceIsCurrent && !stopped && frameGeneration == currentGeneration

/**
 * Owns a single MediaProjection session and always exposes the newest frame.
 * Call [clearFrameBarrier] after overlays are removed, then [awaitFrame] so no
 * overlay-contaminated buffer can be returned.
 */
class MediaProjectionCaptureManager(context: Context) : AutoCloseable {

    data class DisplaySpec(
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        val rotation: Int,
    )

    data class CapturedFrame(
        val bitmap: Bitmap,
        val timestampNanos: Long,
        val rotation: Int,
    )

    private data class StoredFrame(
        val bitmap: Bitmap,
        val timestampNanos: Long,
        val generation: Long,
        val rotation: Int,
    )

    private val appContext = context.applicationContext
    private val projectionManager =
        appContext.getSystemService(MediaProjectionManager::class.java)
    private val captureThread = HandlerThread("GameLongShot-Capture").apply { start() }
    private val handler = Handler(captureThread.looper)
    private val lock = Object()

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var displaySpec: DisplaySpec? = null
    @Volatile private var stopped = false
    private var generation = 0L
    private var latestFrame: StoredFrame? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            synchronized(lock) {
                projection = null
                releaseDisplayLocked()
                stopped = true
                lock.notifyAll()
            }
            onProjectionStopped?.invoke()
        }
    }

    @Volatile var onProjectionStopped: (() -> Unit)? = null

    val isRunning: Boolean
        get() = projection != null && virtualDisplay != null && imageReader != null && !stopped

    val hasProjection: Boolean
        get() = projection != null && !stopped

    /** Must be invoked only after the foreground service has called startForeground. */
    fun start(resultCode: Int, resultData: Intent) {
        synchronized(lock) {
            if (projection != null) throw IllegalStateException("Projection is already running")
            stopped = false
            val mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw SecurityException("Screen capture permission was not granted")
            projection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, handler)
        }
    }

    fun start(resultCode: Int, resultData: Intent, spec: DisplaySpec) {
        start(resultCode, resultData)
        try {
            ensureDisplay(spec)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /** Rebuilds buffers after a rotation or window-size change. */
    fun ensureDisplay(spec: DisplaySpec) {
        require(spec.width > 0 && spec.height > 0 && spec.densityDpi > 0)
        synchronized(lock) {
            if (displaySpec == spec && virtualDisplay != null && imageReader != null) return
            check(projection != null && !stopped) { "Projection is not running" }
            if (virtualDisplay == null) createDisplayLocked(spec) else replaceReaderLocked(spec)
        }
    }

    /**
     * Disconnects the continuously-producing ImageReader while retaining both the user's grant
     * and the one allowed VirtualDisplay. Android 14+ forbids a second createVirtualDisplay call
     * on the same MediaProjection, so [ensureDisplay] reattaches a new Surface to this display.
     */
    fun pauseDisplayIf(shouldPause: () -> Boolean = { true }) {
        synchronized(lock) {
            if (projection == null || stopped || !shouldPause()) return
            pauseReaderLocked()
        }
    }

    /**
     * Invalidates every buffer queued before this point and returns a generation
     * token. Pass that token to [awaitFrame].
     */
    fun clearFrameBarrier(): Long = synchronized(lock) {
        generation += 1
        latestFrame?.bitmap?.recycle()
        latestFrame = null
        while (true) {
            val pending = imageReader?.acquireLatestImage() ?: break
            pending.close()
        }
        generation
    }

    /** Returns an owned Bitmap copy; caller must recycle it. */
    fun awaitFrame(
        afterGeneration: Long,
        timeoutMs: Long = 2_000L,
    ): CapturedFrame? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(1L)
        synchronized(lock) {
            while (!stopped) {
                val frame = latestFrame
                if (frame != null && frame.generation >= afterGeneration) {
                    return CapturedFrame(
                        bitmap = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false),
                        timestampNanos = frame.timestampNanos,
                        rotation = frame.rotation,
                    )
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) return null
                lock.wait(remaining)
            }
        }
        return null
    }

    private fun createDisplayLocked(spec: DisplaySpec) {
        val mediaProjection = projection ?: error("Projection is unavailable")
        val reader = createReaderLocked(spec)
        imageReader = reader
        displaySpec = spec
        virtualDisplay = mediaProjection.createVirtualDisplay(
            DISPLAY_NAME,
            spec.width,
            spec.height,
            spec.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        ) ?: run {
            reader.close()
            imageReader = null
            displaySpec = null
            throw IllegalStateException("Unable to create capture display")
        }
    }

    private fun replaceReaderLocked(spec: DisplaySpec) {
        val display = virtualDisplay ?: return createDisplayLocked(spec)
        val oldReader = imageReader
        val oldSpec = displaySpec
        val newReader = createReaderLocked(spec)
        imageReader = newReader
        displaySpec = spec
        latestFrame?.bitmap?.recycle()
        latestFrame = null
        generation += 1
        try {
            display.resize(spec.width, spec.height, spec.densityDpi)
            display.surface = newReader.surface
        } catch (error: Throwable) {
            imageReader = oldReader
            displaySpec = oldSpec
            newReader.setOnImageAvailableListener(null, null)
            newReader.close()
            throw error
        }
        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()
        lock.notifyAll()
    }

    private fun createReaderLocked(spec: DisplaySpec): ImageReader {
        val reader = ImageReader.newInstance(
            spec.width,
            spec.height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        )
        reader.setOnImageAvailableListener({ source ->
            var image: Image? = null
            var frameGeneration = 0L
            try {
                val validReader = synchronized(lock) {
                    frameGeneration = generation
                    source === imageReader && !stopped
                }
                if (!validReader) return@setOnImageAvailableListener
                image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                val bitmap = BitmapFrameUtils.imageToBitmap(image)
                synchronized(lock) {
                    if (!shouldPublishCapturedFrame(
                            sourceIsCurrent = source === imageReader,
                            stopped = stopped,
                            frameGeneration = frameGeneration,
                            currentGeneration = generation,
                        )
                    ) {
                        bitmap.recycle()
                        return@synchronized
                    }
                    latestFrame?.bitmap?.recycle()
                    latestFrame = StoredFrame(
                        bitmap = bitmap,
                        timestampNanos = image.timestamp.takeIf { it > 0L }
                            ?: SystemClock.elapsedRealtimeNanos(),
                        generation = frameGeneration,
                        rotation = spec.rotation,
                    )
                    lock.notifyAll()
                }
            } catch (_: IllegalStateException) {
                // Reader may have been closed while an orientation change raced us.
            } finally {
                image?.close()
            }
        }, handler)
        return reader
    }

    private fun releaseDisplayLocked() {
        pauseReaderLocked()
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        displaySpec = null
        lock.notifyAll()
    }

    private fun pauseReaderLocked() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.surface = null }
        runCatching { imageReader?.close() }
        imageReader = null
        latestFrame?.bitmap?.recycle()
        latestFrame = null
        generation += 1
        lock.notifyAll()
    }

    override fun close() {
        val mediaProjection: MediaProjection?
        synchronized(lock) {
            if (stopped && projection == null) {
                releaseDisplayLocked()
                captureThread.quitSafely()
                return
            }
            stopped = true
            releaseDisplayLocked()
            mediaProjection = projection
            projection = null
            runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
            lock.notifyAll()
        }
        runCatching { mediaProjection?.stop() }
        captureThread.quitSafely()
    }

    companion object {
        private const val DISPLAY_NAME = "GameLongShotDisplay"
        private const val MAX_IMAGES = 3
    }
}
