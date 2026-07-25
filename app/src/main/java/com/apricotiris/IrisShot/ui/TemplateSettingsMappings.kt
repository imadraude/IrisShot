package com.apricotiris.IrisShot.ui

import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.CaptureTimingOptions
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.ImageOutputFormat

/** Combines one template's capture parameters with device-wide application settings. */
fun GameCaptureTemplate.toCaptureSettingsUiState(
    globalSettings: CaptureSettingsUiState,
): CaptureSettingsUiState = globalSettings.copy(
    previewEnabled = previewEnabled,
    previewWidthDp = previewWidthDp,
    previewMaxHeightDp = previewMaxHeightDp,
    gapDurationMs = previewGapDurationMs,
    waitAfterGestureMs = waitAfterGestureMs.toInt(),
    gestureDurationMs = gestureDurationMs.toInt(),
    postGestureWaitEnabled = postGestureWaitEnabled,
    postGestureWaitMs = postGestureWaitMs.toInt(),
    waitStableMs = waitAfterOverlayHiddenMs.toInt(),
    scrollDistancePx = gestureDistancePx,
    maxOutputHeight = maxOutputHeight,
    namingRule = NamingRule.normalizeStoredValue(namingRule),
    autoOpenResult = autoOpenResult,
    outputFormat = outputFormat,
    jpegQuality = jpegQuality,
)

/** Applies only capture-related settings, preserving identity, regions and preview placement. */
fun GameCaptureTemplate.withCaptureSettings(
    settings: CaptureSettingsUiState,
): GameCaptureTemplate = copy(
    captureSettingsVersion = GameCaptureTemplate.CURRENT_CAPTURE_SETTINGS_VERSION,
    previewEnabled = settings.previewEnabled,
    cleanCapture = false,
    previewWidthDp = settings.previewWidthDp.coerceIn(
        DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
        DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
    ),
    previewMaxHeightDp = settings.previewMaxHeightDp.coerceIn(
        DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP,
        DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP,
    ),
    previewGapDurationMs = settings.gapDurationMs.coerceIn(200, 3_000),
    waitAfterGestureMs = CaptureTimingOptions.normalizeMinimumIntervalMs(
        settings.waitAfterGestureMs.toLong(),
    ),
    gestureDurationMs = CaptureTimingOptions.normalizeSwipeDurationMs(
        settings.gestureDurationMs.toLong(),
    ),
    postGestureWaitEnabled = settings.postGestureWaitEnabled,
    postGestureWaitMs = CaptureTimingOptions.normalizePostGestureWaitMs(
        settings.postGestureWaitMs.toLong(),
    ),
    waitAfterOverlayHiddenMs = settings.waitStableMs.toLong().coerceIn(100L, 1_500L),
    gestureDistancePx = settings.scrollDistancePx.coerceAtLeast(1),
    maxOutputHeight = settings.maxOutputHeight.coerceIn(
        1_000,
        DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
    ),
    detectOverlap = true,
    cropDuplicate = true,
    keepOriginalFrames = false,
    keepTempFilesOnFailure = true,
    namingRule = NamingRule.normalizeStoredValue(settings.namingRule),
    autoOpenResult = settings.autoOpenResult,
    outputFormat = settings.outputFormat,
    jpegQuality = ImageOutputFormat.normalizeJpegQuality(settings.jpegQuality),
)

/** Copies the former application-wide values exactly once for templates saved by older versions. */
fun GameCaptureTemplate.migrateLegacyCaptureSettings(
    legacySettings: CaptureSettingsUiState,
): GameCaptureTemplate = if (
    captureSettingsVersion >= GameCaptureTemplate.CURRENT_CAPTURE_SETTINGS_VERSION
) {
    this
} else {
    withCaptureSettings(legacySettings)
}

fun GameCaptureTemplate.withDefaultCaptureSettings(): GameCaptureTemplate =
    withCaptureSettings(CaptureSettingsUiState())
