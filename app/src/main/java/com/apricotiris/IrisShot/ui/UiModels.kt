package com.apricotiris.IrisShot.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.ImageOutputFormat

enum class AppDestination(@param:StringRes val labelRes: Int) {
    HOME(R.string.destination_home),
    RESULTS(R.string.destination_results),
    TEMPLATES(R.string.destination_templates),
    SETTINGS(R.string.destination_settings),
}

data class PermissionUiState(
    val projectionGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val shizukuInstalled: Boolean = false,
    val shizukuBinderAvailable: Boolean = false,
    val shizukuPermissionGranted: Boolean = false,
    val shizukuModeEnabled: Boolean = false,
) {
    val shizukuModeReady: Boolean
        get() = shizukuModeEnabled && shizukuInstalled &&
            shizukuBinderAvailable && shizukuPermissionGranted
}

data class CaptureServiceUiState(
    val running: Boolean = false,
    val state: String = "IDLE",
    val message: String = "",
    val frameCount: Int = 0,
    val resultSessionId: String? = null,
)

data class TemplateUiModel(
    val id: Long,
    val name: String,
    val appName: String,
    val packageName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val direction: String,
    val updatedAt: Long,
    val updatedAtLabel: String,
) {
    val resolution: String get() = "${screenWidth} × ${screenHeight}"
    @get:StringRes
    val directionLabelRes: Int
        get() = when (direction.uppercase()) {
            "UP" -> R.string.direction_up
            "DOWN" -> R.string.direction_down
            "LEFT" -> R.string.direction_left
            "RIGHT" -> R.string.direction_right
            else -> 0
        }
}

data class CaptureSettingsUiState(
    val previewEnabled: Boolean = true,
    val previewWidthDp: Int = DefaultCaptureConfig.PREVIEW_INITIAL_WIDTH_DP,
    val previewMaxHeightDp: Int = DefaultCaptureConfig.PREVIEW_MAX_HEIGHT_DP,
    val gapDurationMs: Int = DefaultCaptureConfig.PREVIEW_GAP_DURATION_MS,
    val waitAfterGestureMs: Int = DefaultCaptureConfig.WAIT_AFTER_GESTURE_MS.toInt(),
    val gestureDurationMs: Int = DefaultCaptureConfig.GESTURE_DURATION_MS.toInt(),
    val postGestureWaitEnabled: Boolean = DefaultCaptureConfig.POST_GESTURE_WAIT_ENABLED,
    val postGestureWaitMs: Int = DefaultCaptureConfig.POST_GESTURE_WAIT_MS.toInt(),
    val waitStableMs: Int = DefaultCaptureConfig.WAIT_AFTER_OVERLAY_HIDDEN_MS.toInt(),
    val scrollDistancePx: Int = DefaultCaptureConfig.DEFAULT_GESTURE_DISTANCE_PX,
    val maxOutputHeight: Int = DefaultCaptureConfig.DEFAULT_MAX_OUTPUT_HEIGHT,
    val cacheLocation: String = "Android/data/com.apricotiris.IrisShot/cache",
    val cacheLocationIsCustom: Boolean = false,
    val saveLocation: String = "Pictures/GameLongShot",
    val saveLocationIsCustom: Boolean = false,
    val namingRule: String = NamingRule.TIMESTAMP.storedValue,
    val autoOpenResult: Boolean = true,
    val outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG,
    val jpegQuality: Int = ImageOutputFormat.DEFAULT_JPEG_QUALITY,
    val appLanguage: AppLanguage = AppLanguage.default,
    val themeMode: String = "SYSTEM", // SYSTEM, LIGHT, DARK
    val dynamicColor: Boolean = true,
)

object CaptureUiContract {
    const val SERVICE_CLASS = "com.apricotiris.IrisShot.capture.GameLongShotCaptureService"
    const val RESULT_ACTIVITY_CLASS = "com.apricotiris.IrisShot.result.ResultEditorActivity"

    const val ACTION_START_OVERLAY = "com.apricotiris.IrisShot.action.START_OVERLAY"
    const val ACTION_START_CAPTURE = "com.apricotiris.IrisShot.action.START_CAPTURE"
    const val ACTION_LEARN_REGION = "com.apricotiris.IrisShot.action.LEARN_REGION"
    const val ACTION_STOP_CAPTURE = "com.apricotiris.IrisShot.action.STOP_CAPTURE"
    const val ACTION_EXIT_CAPTURE = "com.apricotiris.IrisShot.action.EXIT_CAPTURE"
    const val ACTION_UPDATE_CONFIG = "com.apricotiris.IrisShot.action.UPDATE_CONFIG"
    const val ACTION_STATE_CHANGED = "com.apricotiris.IrisShot.action.STATE_CHANGED"
    const val ACTION_VIEW_RESULT = "com.apricotiris.IrisShot.action.VIEW_RESULT"

    const val EXTRA_RESULT_CODE = "projection_result_code"
    const val EXTRA_RESULT_DATA = "projection_result_data"
    const val EXTRA_TEMPLATE_ID = "template_id"
    const val EXTRA_TEMPLATE_JSON = "template_json"
    const val EXTRA_STATE = "state"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_FRAME_COUNT = "frame_count"
    const val EXTRA_RESULT_SESSION_ID = "result_session_id"
    const val EXTRA_SERVICE_INSTANCE_ID = "service_instance_id"
    const val EXTRA_SESSION_ID = "session_id"
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
    const val EXTRA_USE_SHIZUKU = "use_shizuku"
}

fun Context.areNotificationsReady(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()
