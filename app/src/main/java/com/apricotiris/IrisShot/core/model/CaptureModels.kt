package com.apricotiris.IrisShot.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The complete capture workflow. UI visibility is decided by the coordinator. */
enum class CaptureState {
    IDLE,
    PERMISSION_CHECK,
    REGION_CONFIG,
    REGION_LEARNING_PREPARE,
    REGION_LEARNING_SCROLL,
    REGION_LEARNING_CAPTURE,
    REGION_LEARNING_ANALYZE,
    REGION_RESULT_CONFIRM,
    READY_TO_CAPTURE,
    CAPTURE_PREPARE,
    CAPTURE_SCROLL,
    CAPTURE_WAIT_STABLE,
    CAPTURE_FRAME,
    CAPTURE_PROCESS,
    CAPTURE_SHOW_CONTROL,
    CAPTURE_STOP_REQUESTED,
    CAPTURE_FINISHING,
    RESULT_EDIT,
    EXPORTING,
    ERROR
}

/** A rectangle in physical screen pixels. Right and bottom are exclusive. */
data class RectConfig(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun normalized(): RectConfig = RectConfig(
        left = min(left, right),
        top = min(top, bottom),
        right = max(left, right),
        bottom = max(top, bottom)
    )

    fun clampTo(width: Int, height: Int): RectConfig {
        val normalized = normalized()
        return RectConfig(
            normalized.left.coerceIn(0, width.coerceAtLeast(0)),
            normalized.top.coerceIn(0, height.coerceAtLeast(0)),
            normalized.right.coerceIn(0, width.coerceAtLeast(0)),
            normalized.bottom.coerceIn(0, height.coerceAtLeast(0))
        )
    }

    fun scale(scaleX: Float, scaleY: Float): RectConfig = RectConfig(
        (left * scaleX).roundToInt(),
        (top * scaleY).roundToInt(),
        (right * scaleX).roundToInt(),
        (bottom * scaleY).roundToInt()
    )

    fun intersect(other: RectConfig): RectConfig? {
        val intersection = RectConfig(
            max(left, other.left),
            max(top, other.top),
            min(right, other.right),
            min(bottom, other.bottom)
        )
        return intersection.takeUnless { it.isEmpty }
    }

    fun contains(other: RectConfig): Boolean =
        !other.isEmpty && left <= other.left && top <= other.top &&
            right >= other.right && bottom >= other.bottom

    companion object {
        fun full(width: Int, height: Int) = RectConfig(0, 0, width, height)
    }
}

enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT;

    val isVertical: Boolean get() = this == UP || this == DOWN
    val isForward: Boolean get() = this == UP || this == LEFT
}

/**
 * Physical orientation of the target display.
 *
 * Templates historically stored this value as a string, so the enum deliberately exposes the
 * same stable wire values instead of changing [GameCaptureTemplate.orientation]'s type.
 */
enum class ScreenOrientation(val serializedValue: String) {
    LANDSCAPE("landscape"),
    PORTRAIT("portrait");

    val isLandscape: Boolean get() = this == LANDSCAPE

    companion object {
        fun fromSerialized(
            value: String?,
            screenWidth: Int = 0,
            screenHeight: Int = 0,
        ): ScreenOrientation = when {
            value.equals(LANDSCAPE.serializedValue, ignoreCase = true) -> LANDSCAPE
            value.equals(PORTRAIT.serializedValue, ignoreCase = true) -> PORTRAIT
            screenWidth > 0 && screenHeight > 0 && screenWidth >= screenHeight -> LANDSCAPE
            screenWidth > 0 && screenHeight > 0 -> PORTRAIT
            else -> LANDSCAPE
        }
    }
}

enum class FixedSide {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

enum class FixedAnchorPosition {
    LEADING,
    TRAILING,
}

data class FixedLayerConfig(
    val side: FixedSide,
    val anchorCount: Int = 1,
    val anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
    val repeatRegion: RectConfig? = null,
)

/** The first version deliberately supports translation and a crisp, hard seam only. */
enum class StitchMethod {
    TRANSLATION_HARD_CUT,
    TRANSLATION
}

/** Image format used by both private capture files and public exports. */
enum class ImageOutputFormat {
    JPEG,
    PNG;

    val extension: String get() = if (this == JPEG) "jpg" else "png"
    val mimeType: String get() = if (this == JPEG) "image/jpeg" else "image/png"
    val displayName: String get() = if (this == JPEG) "JPEG" else "PNG"

    companion object {
        const val MIN_JPEG_QUALITY = 70
        const val MAX_JPEG_QUALITY = 100
        const val JPEG_QUALITY_STEP = 5
        const val DEFAULT_JPEG_QUALITY = 90

        fun normalizeJpegQuality(value: Int): Int {
            val clamped = value.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
            return ((clamped + JPEG_QUALITY_STEP / 2) / JPEG_QUALITY_STEP * JPEG_QUALITY_STEP)
                .coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)
        }
    }
}

data class GameCaptureTemplate(
    val id: Long = 0L,
    val packageName: String = "",
    val appName: String = "",
    val templateName: String = "",
    val orientation: String = "landscape",
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val scrollDirection: ScrollDirection = ScrollDirection.UP,
    val captureRegion: RectConfig = RectConfig(0, 0, 0, 0),
    val stitchRegion: RectConfig = RectConfig(0, 0, 0, 0),
    val fixedLayers: List<FixedLayerConfig> = emptyList(),
    val gestureRegion: RectConfig = RectConfig(0, 0, 0, 0),
    /** App-content bounds used when this geometry was authored; null means the baseline is unknown. */
    val navigationContentBoundsAtGeometry: RectConfig? = null,
    val topFixedRegion: RectConfig? = null,
    val bottomFixedRegion: RectConfig? = null,
    val hudMaskRegions: List<RectConfig> = emptyList(),
    val gestureDistancePx: Int = DefaultCaptureConfig.DEFAULT_GESTURE_DISTANCE_PX,
    val gestureDurationMs: Long = DefaultCaptureConfig.GESTURE_DURATION_MS,
    /** Minimum visible duration of the learning/capture progress banner and live preview. */
    val waitAfterGestureMs: Long = DefaultCaptureConfig.WAIT_AFTER_GESTURE_MS,
    /** Time to keep the pointer down after it reaches the gesture endpoint. */
    val gestureStableTimeMs: Long = DefaultCaptureConfig.GESTURE_STABLE_TIME_MS,
    /** Whether to wait after the pointer is released before acquiring the next frame. */
    val postGestureWaitEnabled: Boolean = DefaultCaptureConfig.POST_GESTURE_WAIT_ENABLED,
    /** Time measured from gesture completion, independent of overlay removal and pointer hold. */
    val postGestureWaitMs: Long = DefaultCaptureConfig.POST_GESTURE_WAIT_MS,
    val waitAfterOverlayHiddenMs: Long = DefaultCaptureConfig.WAIT_AFTER_OVERLAY_HIDDEN_MS,
    val minOverlapRatio: Float = DefaultCaptureConfig.MIN_OVERLAP_RATIO,
    val maxOverlapRatio: Float = DefaultCaptureConfig.MAX_OVERLAP_RATIO,
    /** Version 0 means this template still needs the pre-template settings migration. */
    val captureSettingsVersion: Int = CURRENT_CAPTURE_SETTINGS_VERSION,
    val previewEnabled: Boolean = true,
    val cleanCapture: Boolean = false,
    val previewWidthDp: Int = DefaultCaptureConfig.PREVIEW_INITIAL_WIDTH_DP,
    val previewMaxHeightDp: Int = DefaultCaptureConfig.PREVIEW_MAX_HEIGHT_DP,
    val previewGapDurationMs: Int = DefaultCaptureConfig.PREVIEW_GAP_DURATION_MS,
    val previewCenterXRatio: Float = DefaultCaptureConfig.PREVIEW_CENTER_X_RATIO,
    val previewCenterYRatio: Float = DefaultCaptureConfig.PREVIEW_CENTER_Y_RATIO,
    val maxOutputHeight: Int = DefaultCaptureConfig.DEFAULT_MAX_OUTPUT_HEIGHT,
    val detectOverlap: Boolean = true,
    val cropDuplicate: Boolean = true,
    val keepOriginalFrames: Boolean = false,
    val keepTempFilesOnFailure: Boolean = true,
    val namingRule: String = "TIMESTAMP",
    val autoOpenResult: Boolean = true,
    val outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
    val jpegQuality: Int = ImageOutputFormat.DEFAULT_JPEG_QUALITY,
    val stitchMethod: StitchMethod = StitchMethod.TRANSLATION_HARD_CUT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
) {
    /**
     * Normalizes the editable regions into one or two axis-aligned fixed bands.
     *
     * When two bands meet at a corner, the band perpendicular to the scroll direction is the
     * end cap and owns that corner. The parallel band stops at the stitch region. This keeps the
     * captured fixed bitmaps disjoint while still covering the complete capture region.
     */
    fun withNormalizedRegions(): GameCaptureTemplate {
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val full = RectConfig.full(width, height)
        val capture = captureRegion.clampTo(width, height).takeUnless { it.isEmpty } ?: full
        val requestedStitchInCapture = stitchRegion.clampTo(width, height)
            .intersect(capture)
            ?: defaultStitchRegion(capture, scrollDirection)
        // A persisted template may retain the physical navigation strip in captureRegion while its
        // scroll geometry was authored against the app-content bounds. Keep that boundary in the
        // normalized stitch rectangle, but do not manufacture a persisted fixed layer for it.
        // Runtime learning clips capture geometry to app content. A real capture may instead keep
        // this physical strip and merge it into the fixed layer on the same side.
        val navigationGeometry = navigationContentBoundsAtGeometry
            ?.clampTo(width, height)
            ?.takeUnless { it.isEmpty }
        val navigationCapture = navigationGeometry?.intersect(capture)
        val navigationSides = if (navigationCapture == null) {
            emptySet()
        } else {
            buildSet {
                if (capture.top < navigationGeometry.top) add(FixedSide.TOP)
                if (capture.bottom > navigationGeometry.bottom) add(FixedSide.BOTTOM)
                if (capture.left < navigationGeometry.left) add(FixedSide.LEFT)
                if (capture.right > navigationGeometry.right) add(FixedSide.RIGHT)
            }
        }
        val requestedStitch = navigationCapture?.let { content ->
            requestedStitchInCapture.intersect(content)
                ?: defaultStitchRegion(content, scrollDirection)
        } ?: requestedStitchInCapture
        val explicitLayers = fixedLayers
            .distinctBy(FixedLayerConfig::side)
            .take(2)
        val requestedLayers = explicitLayers.ifEmpty {
            // Infer user-authored fixed bands from the original request, then remove only the
            // edges known to be navigation geometry. This prevents the implicit boundary above
            // from being serialized as a fixed layer when a template has just one authored side.
            inferFixedLayers(capture, requestedStitchInCapture, scrollDirection)
                .filterNot { it.side in navigationSides }
        }
        val normalizedLayerSeeds = requestedLayers.map { layer ->
            layer.copy(anchorCount = layer.anchorCount.coerceIn(1, 2))
        }
        val stitch = normalizeFixedStitch(
            capture = capture,
            requested = requestedStitch,
            sides = normalizedLayerSeeds.map(FixedLayerConfig::side) + navigationSides,
        )
        val normalizedLayers = normalizedLayerSeeds.map { layer ->
            val band = fixedBandRegion(capture, stitch, layer.side, scrollDirection)
            val requestedRepeat = layer.repeatRegion
                ?.clampTo(width, height)
                ?.intersect(band)
                ?.takeUnless { it.isEmpty }
                ?: defaultRepeatRegion(
                    band = band,
                    side = layer.side,
                    anchorCount = layer.anchorCount,
                    anchorPosition = layer.anchorPosition,
                )
            layer.copy(
                repeatRegion = normalizeRepeatRegion(
                    requested = requestedRepeat,
                    band = band,
                    side = layer.side,
                    anchorCount = layer.anchorCount,
                    anchorPosition = layer.anchorPosition,
                ),
            )
        }
        val gesture = gestureRegion.clampTo(width, height).takeUnless { it.isEmpty }
            ?: defaultGestureRegion(full)
        val maximumGestureDistance = (
            (if (scrollDirection.isVertical) stitch.height else stitch.width) * 0.90f
        ).toInt().coerceAtLeast(1)
        return copy(
            captureRegion = capture,
            stitchRegion = stitch,
            fixedLayers = normalizedLayers,
            gestureRegion = gesture,
            // Compatibility-only fields cannot reactivate removed modes or disable required work.
            cleanCapture = false,
            keepOriginalFrames = false,
            detectOverlap = true,
            cropDuplicate = true,
            keepTempFilesOnFailure = true,
            // Keep the persisted path executable even when a fixed-band edit has shortened the
            // scrolling layer. The editor and runtime use the same 90% bound.
            gestureDistancePx = gestureDistancePx.coerceIn(1, maximumGestureDistance),
            gestureDurationMs = CaptureTimingOptions.normalizeSwipeDurationMs(gestureDurationMs),
            waitAfterGestureMs = CaptureTimingOptions.normalizeMinimumIntervalMs(waitAfterGestureMs),
            gestureStableTimeMs = CaptureTimingOptions.normalizeStableTimeMs(gestureStableTimeMs),
            postGestureWaitMs = CaptureTimingOptions.normalizePostGestureWaitMs(postGestureWaitMs),
            waitAfterOverlayHiddenMs = waitAfterOverlayHiddenMs.coerceIn(100L, 1_500L),
            previewWidthDp = previewWidthDp.coerceIn(
                DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
                DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
            ),
            previewMaxHeightDp = previewMaxHeightDp.coerceIn(
                DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
                DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
            ),
            previewGapDurationMs = previewGapDurationMs.coerceIn(200, 3_000),
            previewCenterXRatio = previewCenterXRatio.normalizedRatio(
                DefaultCaptureConfig.PREVIEW_CENTER_X_RATIO,
            ),
            previewCenterYRatio = previewCenterYRatio.normalizedRatio(
                DefaultCaptureConfig.PREVIEW_CENTER_Y_RATIO,
            ),
            maxOutputHeight = maxOutputHeight.coerceIn(1_000, DefaultCaptureConfig.MAX_OUTPUT_DIMENSION),
            jpegQuality = ImageOutputFormat.normalizeJpegQuality(jpegQuality),
            // These fields are retained only for reading old templates. Fixed content is now
            // derived from captureRegion - stitchRegion and never edited as extra rectangles.
            topFixedRegion = null,
            bottomFixedRegion = null,
            hudMaskRegions = emptyList(),
        )
    }

    fun fixedBandRegion(side: FixedSide): RectConfig? {
        val normalized = withNormalizedRegions()
        if (normalized.fixedLayers.none { it.side == side }) return null
        return fixedBandRegion(
            normalized.captureRegion,
            normalized.stitchRegion,
            side,
            normalized.scrollDirection,
        )
            .takeUnless { it.isEmpty }
    }

    fun leadingFixedRegion(): RectConfig? = fixedBandRegion(
        if (scrollDirection.isVertical) FixedSide.TOP else FixedSide.LEFT,
    )

    fun trailingFixedRegion(): RectConfig? = fixedBandRegion(
        if (scrollDirection.isVertical) FixedSide.BOTTOM else FixedSide.RIGHT,
    )

    companion object {
        const val LEGACY_CAPTURE_SETTINGS_VERSION = 0
        const val CURRENT_CAPTURE_SETTINGS_VERSION = 1

        /** Produces a safe starting point for the current display that still requires confirmation. */
        fun createDefault(
            packageName: String,
            appName: String,
            templateName: String,
            screenWidth: Int,
            screenHeight: Int,
            scrollDirection: ScrollDirection = ScrollDirection.UP
        ): GameCaptureTemplate {
            val capture = RectConfig.full(screenWidth, screenHeight)
            val stitch = defaultStitchRegion(capture, scrollDirection)
            val gesture = defaultGestureRegion(capture)
            return GameCaptureTemplate(
                packageName = packageName,
                appName = appName,
                templateName = templateName,
                orientation = if (screenWidth >= screenHeight) "landscape" else "portrait",
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                scrollDirection = scrollDirection,
                captureRegion = capture,
                stitchRegion = stitch,
                fixedLayers = defaultFixedSides(scrollDirection).map(::FixedLayerConfig),
                gestureRegion = gesture,
                gestureDistancePx = if (scrollDirection.isVertical) {
                    (stitch.height * DefaultCaptureConfig.DEFAULT_GESTURE_DISTANCE_RATIO)
                        .roundToInt()
                } else {
                    (stitch.width * DefaultCaptureConfig.DEFAULT_GESTURE_DISTANCE_RATIO)
                        .roundToInt()
                }.coerceAtLeast(1)
            ).withNormalizedRegions()
        }

        fun defaultStitchRegion(
            captureRegion: RectConfig,
            scrollDirection: ScrollDirection,
        ): RectConfig {
            val capture = captureRegion.normalized()
            return if (scrollDirection.isVertical) {
                val inset = (capture.height * 0.12f).roundToInt()
                    .coerceAtMost((capture.height - 1) / 2)
                RectConfig(capture.left, capture.top + inset, capture.right, capture.bottom - inset)
            } else {
                val inset = (capture.width * 0.12f).roundToInt()
                    .coerceAtMost((capture.width - 1) / 2)
                RectConfig(capture.left + inset, capture.top, capture.right - inset, capture.bottom)
            }
        }

        fun inferFixedLayers(
            captureRegion: RectConfig,
            stitchRegion: RectConfig,
            scrollDirection: ScrollDirection,
        ): List<FixedLayerConfig> {
            val capture = captureRegion.normalized()
            val stitch = stitchRegion.intersect(capture) ?: return emptyList()
            return buildList {
                if (scrollDirection.isVertical) {
                    if (stitch.top > capture.top) add(FixedLayerConfig(FixedSide.TOP))
                    if (stitch.bottom < capture.bottom) add(FixedLayerConfig(FixedSide.BOTTOM))
                } else {
                    if (stitch.left > capture.left) add(FixedLayerConfig(FixedSide.LEFT))
                    if (stitch.right < capture.right) add(FixedLayerConfig(FixedSide.RIGHT))
                }
            }
        }

        private fun defaultFixedSides(direction: ScrollDirection): List<FixedSide> =
            if (direction.isVertical) {
                listOf(FixedSide.TOP, FixedSide.BOTTOM)
            } else {
                listOf(FixedSide.LEFT, FixedSide.RIGHT)
            }

        private fun normalizeFixedStitch(
            capture: RectConfig,
            requested: RectConfig,
            sides: List<FixedSide>,
        ): RectConfig {
            val selected = sides.toSet()
            val (left, right) = normalizeFixedAxis(
                captureStart = capture.left,
                captureEnd = capture.right,
                requestedStart = requested.left,
                requestedEnd = requested.right,
                leadingFixed = FixedSide.LEFT in selected,
                trailingFixed = FixedSide.RIGHT in selected,
            )
            val (top, bottom) = normalizeFixedAxis(
                captureStart = capture.top,
                captureEnd = capture.bottom,
                requestedStart = requested.top,
                requestedEnd = requested.bottom,
                leadingFixed = FixedSide.TOP in selected,
                trailingFixed = FixedSide.BOTTOM in selected,
            )
            return RectConfig(left, top, right, bottom)
        }

        private fun normalizeFixedAxis(
            captureStart: Int,
            captureEnd: Int,
            requestedStart: Int,
            requestedEnd: Int,
            leadingFixed: Boolean,
            trailingFixed: Boolean,
        ): Pair<Int, Int> {
            val length = captureEnd - captureStart
            if (length <= 1 || (!leadingFixed && !trailingFixed)) {
                return captureStart to captureEnd
            }
            val inset = defaultBandInset(length)
            return when {
                leadingFixed && trailingFixed && length >= 3 -> {
                    val requestedLeading = requestedStart.takeIf { it > captureStart }
                        ?: captureStart + inset
                    val start = requestedLeading.coerceIn(captureStart + 1, captureEnd - 2)
                    val requestedTrailing = requestedEnd.takeIf { it < captureEnd }
                        ?: captureEnd - inset
                    val end = requestedTrailing.coerceIn(start + 1, captureEnd - 1)
                    start to end
                }
                leadingFixed && !trailingFixed -> {
                    val fallback = captureStart + inset
                    val start = requestedStart.takeIf { it > captureStart }
                        ?.coerceAtMost(captureEnd - 1)
                        ?: fallback
                    start to captureEnd
                }
                !leadingFixed && trailingFixed -> {
                    val fallback = captureEnd - inset
                    val end = requestedEnd.takeIf { it < captureEnd }
                        ?.coerceAtLeast(captureStart + 1)
                        ?: fallback
                    captureStart to end
                }
                else -> captureStart to captureEnd
            }
        }

        private fun fixedBandRegion(
            capture: RectConfig,
            stitch: RectConfig,
            side: FixedSide,
            scrollDirection: ScrollDirection,
        ): RectConfig {
            val verticalScroll = scrollDirection.isVertical
            return when (side) {
                FixedSide.TOP -> RectConfig(
                    left = if (verticalScroll) capture.left else stitch.left,
                    top = capture.top,
                    right = if (verticalScroll) capture.right else stitch.right,
                    bottom = stitch.top,
                )
                FixedSide.BOTTOM -> RectConfig(
                    left = if (verticalScroll) capture.left else stitch.left,
                    top = stitch.bottom,
                    right = if (verticalScroll) capture.right else stitch.right,
                    bottom = capture.bottom,
                )
                FixedSide.LEFT -> RectConfig(
                    left = capture.left,
                    top = if (verticalScroll) stitch.top else capture.top,
                    right = stitch.left,
                    bottom = if (verticalScroll) stitch.bottom else capture.bottom,
                )
                FixedSide.RIGHT -> RectConfig(
                    left = stitch.right,
                    top = if (verticalScroll) stitch.top else capture.top,
                    right = capture.right,
                    bottom = if (verticalScroll) stitch.bottom else capture.bottom,
                )
            }
        }

        private fun defaultRepeatRegion(
            band: RectConfig,
            side: FixedSide,
            anchorCount: Int,
            anchorPosition: FixedAnchorPosition,
        ): RectConfig {
            val vertical = side == FixedSide.LEFT || side == FixedSide.RIGHT
            if ((vertical && band.height <= 2) ||
                (!vertical && band.width <= 2)
            ) {
                return band
            }
            val leadingRatio = DEFAULT_ANCHOR_RATIO
            val trailingRatio = if (anchorCount == 2) DEFAULT_ANCHOR_RATIO else 0f
            return if (vertical) {
                val leading = (band.height * leadingRatio).roundToInt().coerceIn(0, band.height - 1)
                val trailing = (band.height * trailingRatio).roundToInt()
                    .coerceIn(0, band.height - leading - 1)
                if (anchorCount == 1 && anchorPosition == FixedAnchorPosition.TRAILING) {
                    RectConfig(band.left, band.top, band.right, band.bottom - leading)
                } else {
                    RectConfig(band.left, band.top + leading, band.right, band.bottom - trailing)
                }
            } else {
                val leading = (band.width * leadingRatio).roundToInt().coerceIn(0, band.width - 1)
                val trailing = (band.width * trailingRatio).roundToInt()
                    .coerceIn(0, band.width - leading - 1)
                if (anchorCount == 1 && anchorPosition == FixedAnchorPosition.TRAILING) {
                    RectConfig(band.left, band.top, band.right - leading, band.bottom)
                } else {
                    RectConfig(band.left + leading, band.top, band.right - trailing, band.bottom)
                }
            }
        }

        private fun normalizeRepeatRegion(
            requested: RectConfig,
            band: RectConfig,
            side: FixedSide,
            anchorCount: Int,
            anchorPosition: FixedAnchorPosition,
        ): RectConfig {
            val vertical = side == FixedSide.LEFT || side == FixedSide.RIGHT
            val hasRequiredAnchorSpace = if (vertical) {
                when {
                    anchorCount >= 2 -> requested.top > band.top && requested.bottom < band.bottom
                    anchorPosition == FixedAnchorPosition.TRAILING -> requested.bottom < band.bottom
                    else -> requested.top > band.top
                }
            } else {
                when {
                    anchorCount >= 2 -> requested.left > band.left && requested.right < band.right
                    anchorPosition == FixedAnchorPosition.TRAILING -> requested.right < band.right
                    else -> requested.left > band.left
                }
            }
            if (!hasRequiredAnchorSpace) {
                // Older templates described the repeat axis using the scroll direction. When a
                // band is reinterpreted by its physical orientation, that metadata can span the
                // whole new axis; use a useful default instead of collapsing the block to 1 px.
                return defaultRepeatRegion(band, side, anchorCount, anchorPosition)
            }
            if (vertical) {
                if (band.height <= 2) return band
                if (anchorCount == 1 && anchorPosition == FixedAnchorPosition.TRAILING) {
                    val bottom = requested.bottom.coerceIn(band.top + 1, band.bottom - 1)
                    return RectConfig(band.left, band.top, band.right, bottom)
                }
                val maximumTop = band.bottom - if (anchorCount == 2) 2 else 1
                val top = requested.top.coerceIn(band.top + 1, maximumTop)
                val bottom = if (anchorCount == 1) band.bottom else {
                    requested.bottom.coerceIn(top + 1, band.bottom - 1)
                }
                return RectConfig(band.left, top, band.right, bottom)
            }
            if (band.width <= 2) return band
            if (anchorCount == 1 && anchorPosition == FixedAnchorPosition.TRAILING) {
                val right = requested.right.coerceIn(band.left + 1, band.right - 1)
                return RectConfig(band.left, band.top, right, band.bottom)
            }
            val maximumLeft = band.right - if (anchorCount == 2) 2 else 1
            val left = requested.left.coerceIn(band.left + 1, maximumLeft)
            val right = if (anchorCount == 1) band.right else {
                requested.right.coerceIn(left + 1, band.right - 1)
            }
            return RectConfig(left, band.top, right, band.bottom)
        }

        private fun defaultBandInset(length: Int): Int = (length * DEFAULT_FIXED_INSET_RATIO)
            .roundToInt()
            .coerceIn(1, (length - 1).coerceAtLeast(1))

        private fun defaultGestureRegion(screen: RectConfig): RectConfig {
            val insetX = (screen.width * 0.30f).roundToInt()
            val insetY = (screen.height * 0.28f).roundToInt()
            return RectConfig(
                screen.left + insetX,
                screen.top + insetY,
                screen.right - insetX,
                screen.bottom - insetY,
            )
        }

        private const val DEFAULT_FIXED_INSET_RATIO = 0.12f
        private const val DEFAULT_ANCHOR_RATIO = 0.20f
    }
}

/** Applies the canonical starting values only when a user creates a new template. */
fun GameCaptureTemplate.withNewTemplateCaptureDefaults(): GameCaptureTemplate = copy(
    gestureDurationMs = DefaultCaptureConfig.GESTURE_DURATION_MS,
    gestureStableTimeMs = DefaultCaptureConfig.GESTURE_STABLE_TIME_MS,
    postGestureWaitEnabled = DefaultCaptureConfig.POST_GESTURE_WAIT_ENABLED,
    postGestureWaitMs = DefaultCaptureConfig.POST_GESTURE_WAIT_MS,
    waitAfterGestureMs = DefaultCaptureConfig.WAIT_AFTER_GESTURE_MS,
    outputFormat = ImageOutputFormat.JPEG,
    jpegQuality = ImageOutputFormat.DEFAULT_JPEG_QUALITY,
).withNormalizedRegions()

data class PreviewRegionGeometry(
    val widthDp: Int,
    val maxHeightDp: Int,
    val centerXRatio: Float,
    val centerYRatio: Float,
)

/**
 * Builds the default preview envelope in full-screen capture coordinates.
 *
 * Vertical captures use a right-side strip. Horizontal captures use a strip immediately above
 * the bottom-center capture banner. The banner itself has the same nominal height as the strip.
 */
fun defaultPreviewRegionGeometry(
    screenWidth: Int,
    screenHeight: Int,
    pixelsPerDp: Float,
    scrollDirection: ScrollDirection,
    screenOrientation: String? = null,
): PreviewRegionGeometry {
    val actualWidthPx = screenWidth.coerceAtLeast(1).toFloat()
    val actualHeightPx = screenHeight.coerceAtLeast(1).toFloat()
    val density = pixelsPerDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val marginPx = DefaultCaptureConfig.PREVIEW_EDGE_MARGIN_DP * density
    val referenceHeightPx = DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP * density

    /*
     * Existing callers omit screenOrientation and retain the physical-coordinate behavior used
     * by older templates. A newly selected orientation is authoritative when it disagrees with
     * the dimensions (for example, a template created before a reference image was selected), so
     * calculate the default in logical screen coordinates and rotate the result back afterwards.
     */
    val selectedOrientation = screenOrientation?.let {
        ScreenOrientation.fromSerialized(it, screenWidth, screenHeight)
    }
    val physicalIsLandscape = actualWidthPx >= actualHeightPx
    val rotateLogicalLayout = selectedOrientation != null &&
        selectedOrientation.isLandscape != physicalIsLandscape
    val widthPx = if (rotateLogicalLayout) actualHeightPx else actualWidthPx
    val heightPx = if (rotateLogicalLayout) actualWidthPx else actualHeightPx

    val requestedWidthDp: Int
    val requestedHeightDp: Int
    val centerXRatio: Float
    val centerYRatio: Float
    if (scrollDirection.isVertical) {
        requestedWidthDp = DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP
        requestedHeightDp = ((heightPx - marginPx * 2f) / density).roundToInt()
        val actualWidthPx = (
            requestedWidthDp.coerceIn(
                DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
                DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
            ) * density
        ).coerceAtMost(widthPx)
        val right = (widthPx - marginPx).coerceIn(actualWidthPx, widthPx)
        centerXRatio = ((right - actualWidthPx / 2f) / widthPx).coerceIn(0f, 1f)
        centerYRatio = 0.5f
    } else {
        requestedWidthDp = ((widthPx - marginPx * 2f) / density).roundToInt()
        requestedHeightDp = DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP
        val actualHeightPx = (
            requestedHeightDp.coerceIn(
                DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
                DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
            ) * density
        ).coerceAtMost(heightPx)
        val referenceTop = (heightPx - marginPx - referenceHeightPx).coerceIn(0f, heightPx)
        val bottom = referenceTop.coerceIn(actualHeightPx, heightPx)
        centerXRatio = 0.5f
        centerYRatio = ((bottom - actualHeightPx / 2f) / heightPx).coerceIn(0f, 1f)
    }
    val boundedWidthDp = requestedWidthDp.coerceIn(
            DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
            DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
        )
    val boundedHeightDp = requestedHeightDp.coerceIn(
            DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
            DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
        )
    return PreviewRegionGeometry(
        widthDp = if (rotateLogicalLayout) boundedHeightDp else boundedWidthDp,
        maxHeightDp = if (rotateLogicalLayout) boundedWidthDp else boundedHeightDp,
        centerXRatio = if (rotateLogicalLayout) centerYRatio else centerXRatio,
        centerYRatio = if (rotateLogicalLayout) centerXRatio else centerYRatio,
    )
}

fun GameCaptureTemplate.withDefaultPreviewGeometry(pixelsPerDp: Float): GameCaptureTemplate {
    val geometry = defaultPreviewRegionGeometry(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        pixelsPerDp = pixelsPerDp,
        scrollDirection = scrollDirection,
        screenOrientation = orientation,
    )
    return copy(
        previewWidthDp = geometry.widthDp,
        previewMaxHeightDp = geometry.maxHeightDp,
        previewCenterXRatio = geometry.centerXRatio,
        previewCenterYRatio = geometry.centerYRatio,
    ).withNormalizedRegions()
}

fun GameCaptureTemplate.hasDefaultPreviewGeometry(pixelsPerDp: Float): Boolean {
    val expected = defaultPreviewRegionGeometry(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        pixelsPerDp = pixelsPerDp,
        scrollDirection = scrollDirection,
        screenOrientation = orientation,
    )
    // Accept the physical-coordinate default as well so templates saved before the orientation
    // selector was introduced remain recognized as unedited defaults.
    val physicalExpected = defaultPreviewRegionGeometry(
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        pixelsPerDp = pixelsPerDp,
        scrollDirection = scrollDirection,
    )
    val xTolerance = 1.5f / screenWidth.coerceAtLeast(1)
    val yTolerance = 1.5f / screenHeight.coerceAtLeast(1)
    fun matches(candidate: PreviewRegionGeometry): Boolean =
        previewWidthDp == candidate.widthDp &&
            previewMaxHeightDp == candidate.maxHeightDp &&
            abs(previewCenterXRatio - candidate.centerXRatio) <= xTolerance &&
            abs(previewCenterYRatio - candidate.centerYRatio) <= yTolerance
    val matchesLegacyDefaults = previewWidthDp == DefaultCaptureConfig.PREVIEW_INITIAL_WIDTH_DP &&
        previewMaxHeightDp == DefaultCaptureConfig.PREVIEW_MAX_HEIGHT_DP &&
        abs(previewCenterXRatio - DefaultCaptureConfig.PREVIEW_CENTER_X_RATIO) <= xTolerance &&
        abs(previewCenterYRatio - DefaultCaptureConfig.PREVIEW_CENTER_Y_RATIO) <= yTolerance
    return matches(expected) || matches(physicalExpected) || matchesLegacyDefaults
}

data class CaptureSessionConfig(
    val templateId: Long = 0L,
    val outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
    val jpegQuality: Int = ImageOutputFormat.DEFAULT_JPEG_QUALITY,
    val maxOutputHeight: Int = DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
    val previewMaxWidth: Int = 720,
    val previewMaxHeight: Int = 720,
    val enablePreviewBetweenScroll: Boolean = true,
    val previewGapDurationMs: Int = DefaultCaptureConfig.PREVIEW_GAP_DURATION_MS,
    val autoOpenResult: Boolean = true,
    val exportTopFixed: Boolean = true,
    val exportBottomFixed: Boolean = true
)

data class CapturedFrame(
    val index: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val fullBitmapPath: String? = null,
    val scrollBitmapPath: String = "",
    val analysisBitmapPath: String? = null,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val confidence: Float = 0f,
    val accepted: Boolean = false,
    val matchReason: String? = null,
    val width: Int = 0,
    val height: Int = 0
)

/** One non-overlapping, logically ordered piece of a disk-backed scroll result. */
data class ScrollChunk(
    val path: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

/** One contiguous scroll-layer range shown and exported by the result editor. */
data class ScrollExportRegion(
    val start: Int = 0,
    val end: Int = 0,
    val enabled: Boolean = true,
) {
    val length: Int get() = (end - start).coerceAtLeast(0)
}

data class CapturedFixedLayer(
    val side: FixedSide,
    val bitmapPath: String,
    val width: Int,
    val height: Int,
    val anchorCount: Int = 1,
    val anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
    val repeatRegion: RectConfig? = null,
)

/** A successfully published export and the exact editor parameters that produced it. */
data class ResultExportRecord(
    val uris: List<String> = emptyList(),
    val destinationTreeUri: String? = null,
    val format: ImageOutputFormat = ImageOutputFormat.JPEG,
    val width: Int = 0,
    val totalHeight: Long = 0L,
    val segmented: Boolean = false,
    val displayNames: List<String> = emptyList(),
    val includeTop: Boolean = true,
    val includeBottom: Boolean = true,
    val parameterSignature: String = "",
    val exportedAt: Long = System.currentTimeMillis(),
)

/** Paths are private app files; export is performed lazily from these layers. */
const val FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION = 9

data class LayeredCaptureResult(
    val schemaVersion: Int = FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION,
    val topFixedBitmapPath: String? = null,
    val fixedLayers: List<CapturedFixedLayer> = emptyList(),
    val scrollLongBitmapPath: String = "",
    val thumbnailBitmapPath: String? = null,
    val scrollChunks: List<ScrollChunk> = emptyList(),
    val scrollContentWidth: Int = 0,
    val scrollContentHeight: Int = 0,
    val bottomFixedBitmapPath: String? = null,
    val selectedScrollStart: Int = 0,
    val selectedScrollEnd: Int = 0,
    val scrollExportRegions: List<ScrollExportRegion> = emptyList(),
    val seamPositions: List<Int> = emptyList(),
    val sourceFrames: List<CapturedFrame> = emptyList(),
    val id: String = "",
    val sessionId: String = "",
    val appName: String = "",
    val capturedPackageName: String = "",
    val recordName: String? = null,
    val templateSnapshot: GameCaptureTemplate? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lowConfidenceSeams: List<Int> = emptyList(),
    val outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
    val jpegQuality: Int = ImageOutputFormat.DEFAULT_JPEG_QUALITY,
    val lastExport: ResultExportRecord? = null,
)

data class MotionBlockScore(
    val blockX: Int,
    val blockY: Int,
    var followMainMotionScore: Float,
    var fixedScore: Float,
    var unstableScore: Float,
    var unknownScore: Float
)

data class MotionEstimate(
    val dx: Int,
    val dy: Int,
    val confidence: Float,
    val inlierRatio: Float
)

data class RegionLearningResult(
    val suggestedStitchRegion: RectConfig,
    val suggestedGestureRegion: RectConfig,
    val suggestedTopFixedRegion: RectConfig?,
    val suggestedBottomFixedRegion: RectConfig?,
    val suggestedHudMasks: List<RectConfig>,
    val confidence: Float,
    val uncertainRegions: List<RectConfig>,
    val motionEstimate: MotionEstimate? = null,
    val blockScores: List<MotionBlockScore> = emptyList()
)

enum class StitchRejectionReason {
    NO_OVERLAP,
    DUPLICATE,
    WRONG_DIRECTION,
    OVERLAP_TOO_LOW,
    OVERLAP_TOO_HIGH,
    UNEXPECTED_SHIFT,
    LOW_CONFIDENCE,
}

/** dx/dy is the displacement of matching content in current relative to previous. */
data class StitchMatchResult(
    val dx: Int,
    val dy: Int,
    val overlapRatio: Float,
    val confidence: Float,
    val accepted: Boolean,
    val reason: String? = null,
    val rejectionReason: StitchRejectionReason? = null,
    val frameDifference: Float = Float.POSITIVE_INFINITY,
    val changedPixelRatio: Float = 1f,
) {
    val nearlyIdentical: Boolean
        get() = frameDifference <= 2.0f && changedPixelRatio <= 0.02f
}

object DefaultCaptureConfig {
    const val WAIT_AFTER_OVERLAY_HIDDEN_MS = 300L
    const val WAIT_AFTER_GESTURE_MS = 500L
    const val GESTURE_DURATION_MS = 300L
    const val GESTURE_STABLE_TIME_MS = 500L
    const val POST_GESTURE_WAIT_ENABLED = true
    const val POST_GESTURE_WAIT_MS = 1_000L
    const val DEFAULT_GESTURE_DISTANCE_PX = 500
    /** A visible starting path that still leaves enough overlap for reliable stitching. */
    const val DEFAULT_GESTURE_DISTANCE_RATIO = 0.65f
    const val REGION_LEARNING_SAMPLE_COUNT = 8
    const val REGION_RECOGNITION_DURATION_MS = 6_000L
    const val ANALYSIS_SCALE = 0.5f
    const val BLOCK_SIZE = 32
    // A gesture may travel up to 90% of the scroll region, leaving roughly 10% overlap.
    const val MIN_OVERLAP_RATIO = 0.10f
    const val TARGET_OVERLAP_RATIO = 0.50f
    // Game card lists often advance by only one row. Searching from 3% keeps those small
    // movements in range; overlap alone is never treated as end-of-list evidence.
    const val MAX_OVERLAP_RATIO = 0.97f
    // Dynamic card art and particle backgrounds are allowed; uncertain seams remain reviewable.
    const val MIN_MATCH_CONFIDENCE = 0.38f
    const val PREVIEW_MAX_HEIGHT_DP = 240
    const val PREVIEW_INITIAL_WIDTH_DP = 120
    const val PREVIEW_MIN_SIZE_DP = 48
    const val PREVIEW_MAX_SIZE_DP = 2_000
    const val PREVIEW_EDGE_MARGIN_DP = 18
    const val PREVIEW_REFERENCE_HEIGHT_DP = 64
    const val PREVIEW_GAP_DURATION_MS = 1_000
    const val PREVIEW_CENTER_X_RATIO = 0.18f
    const val PREVIEW_CENTER_Y_RATIO = 0.78f
    const val DEFAULT_MAX_OUTPUT_HEIGHT = 28_000

    /** Android Bitmap and many encoders become unreliable near their dimension limits. */
    const val MAX_OUTPUT_DIMENSION = 32_000
    // Caps individual Bitmap allocations in stitch helpers. Live capture and streaming export use
    // disk strips and do not apply this threshold to the complete output.
    const val MAX_SINGLE_BITMAP_PIXELS = 20_000_000L
}

private fun Float.normalizedRatio(fallback: Float): Float =
    takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: fallback
