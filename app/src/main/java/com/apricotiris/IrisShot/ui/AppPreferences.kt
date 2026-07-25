package com.apricotiris.IrisShot.ui

import android.content.Context
import com.apricotiris.IrisShot.core.model.CaptureTimingOptions
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.storage.StorageLocationPreferences

data class OverlayOpacitySettings(
    val panelOpacity: Float = DEFAULT_OVERLAY_PANEL_OPACITY,
    val nonFocusedRegionOpacity: Float = DEFAULT_NON_FOCUSED_REGION_OPACITY,
)

const val MIN_OVERLAY_OPACITY = 0.10f
const val MAX_OVERLAY_OPACITY = 1f
const val DEFAULT_OVERLAY_PANEL_OPACITY = 1f
const val DEFAULT_NON_FOCUSED_REGION_OPACITY = 1f

fun normalizeOverlayOpacity(value: Float): Float =
    value.coerceIn(MIN_OVERLAY_OPACITY, MAX_OVERLAY_OPACITY)

class AppPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val storageLocations = StorageLocationPreferences(appContext)

    /** Legacy combined view used by activities that only need defaults and application settings. */
    fun loadSettings(): CaptureSettingsUiState = loadGlobalSettings(loadDefaultCaptureSettings())

    fun loadThemeMode(defaultValue: String = "SYSTEM"): String =
        preferences.getString(KEY_THEME_MODE, defaultValue) ?: defaultValue

    fun loadAppLanguage(): AppLanguage = AppLanguage.fromStoredValue(
        preferences.getString(KEY_APP_LANGUAGE, null),
    )

    fun saveAppLanguage(language: AppLanguage) {
        preferences.edit().putString(KEY_APP_LANGUAGE, language.storedValue).apply()
    }

    fun loadShizukuModeEnabled(): Boolean =
        preferences.getBoolean(KEY_SHIZUKU_MODE_ENABLED, false)

    fun saveShizukuModeEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SHIZUKU_MODE_ENABLED, enabled).apply()
    }

    fun loadDefaultCaptureSettings(): CaptureSettingsUiState {
        val defaults = CaptureSettingsUiState()
        return defaults.copy(
            previewEnabled = preferences.getBoolean(KEY_PREVIEW_ENABLED, defaults.previewEnabled),
            previewWidthDp = preferences.getInt(KEY_PREVIEW_WIDTH, defaults.previewWidthDp),
            previewMaxHeightDp = preferences.getInt(KEY_PREVIEW_MAX_HEIGHT, defaults.previewMaxHeightDp),
            gapDurationMs = preferences.getInt(KEY_GAP_DURATION, defaults.gapDurationMs),
            waitAfterGestureMs = CaptureTimingOptions.normalizeMinimumIntervalMs(
                preferences.getInt(KEY_WAIT_MS, defaults.waitAfterGestureMs).toLong(),
            ).toInt(),
            gestureDurationMs = CaptureTimingOptions.normalizeSwipeDurationMs(
                preferences.getInt(KEY_GESTURE_MS, defaults.gestureDurationMs).toLong(),
            ).toInt(),
            postGestureWaitEnabled = preferences.getBoolean(
                KEY_POST_GESTURE_WAIT_ENABLED,
                defaults.postGestureWaitEnabled,
            ),
            postGestureWaitMs = CaptureTimingOptions.normalizePostGestureWaitMs(
                preferences.getInt(
                    KEY_POST_GESTURE_WAIT_MS,
                    defaults.postGestureWaitMs,
                ).toLong(),
            ).toInt(),
            waitStableMs = preferences.getInt(KEY_WAIT_STABLE, defaults.waitStableMs),
            scrollDistancePx = preferences.getInt(KEY_SCROLL_DISTANCE, defaults.scrollDistancePx),
            maxOutputHeight = preferences.getInt(KEY_MAX_OUTPUT_HEIGHT, defaults.maxOutputHeight),
            namingRule = NamingRule.normalizeStoredValue(
                preferences.getString(KEY_NAMING_RULE, defaults.namingRule)
                    ?: defaults.namingRule,
            ),
            autoOpenResult = preferences.getBoolean(KEY_AUTO_OPEN, defaults.autoOpenResult),
            outputFormat = preferences.getString(KEY_OUTPUT_FORMAT, defaults.outputFormat.name)
                ?.let { stored -> runCatching { ImageOutputFormat.valueOf(stored) }.getOrNull() }
                ?: defaults.outputFormat,
            jpegQuality = normalizeJpegQuality(
                preferences.getInt(KEY_JPEG_QUALITY, defaults.jpegQuality),
            ),
        )
    }

    /** Applies only device-wide fields, leaving capture parameters from [base] untouched. */
    fun loadGlobalSettings(
        base: CaptureSettingsUiState = CaptureSettingsUiState(),
    ): CaptureSettingsUiState = base.copy(
        cacheLocation = if (storageLocations.cacheTreeUri == null) {
            "Android/data/${appContext.packageName}/cache"
        } else {
            storageLocations.cacheDisplayName
        },
        cacheLocationIsCustom = storageLocations.cacheTreeUri != null,
        saveLocation = storageLocations.exportDisplayName,
        saveLocationIsCustom = storageLocations.exportTreeUri != null,
        appLanguage = loadAppLanguage(),
        themeMode = loadThemeMode(base.themeMode),
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, base.dynamicColor),
    )

    /** Compatibility entry point; new callers should save capture and global fields separately. */
    fun saveSettings(value: CaptureSettingsUiState) {
        saveDefaultCaptureSettings(value)
        saveGlobalSettings(value)
    }

    fun saveDefaultCaptureSettings(value: CaptureSettingsUiState) {
        preferences.edit()
            .remove(KEY_CLEAN_CAPTURE)
            .remove(KEY_KEEP_ORIGINAL)
            .remove(KEY_DETECT_OVERLAP)
            .remove(KEY_CROP_DUPLICATE)
            .remove(KEY_KEEP_TEMP)
            .putBoolean(KEY_PREVIEW_ENABLED, value.previewEnabled)
            .putInt(KEY_PREVIEW_WIDTH, value.previewWidthDp)
            .putInt(KEY_PREVIEW_MAX_HEIGHT, value.previewMaxHeightDp)
            .putInt(KEY_GAP_DURATION, value.gapDurationMs)
            .putInt(
                KEY_WAIT_MS,
                CaptureTimingOptions.normalizeMinimumIntervalMs(
                    value.waitAfterGestureMs.toLong(),
                ).toInt(),
            )
            .putInt(
                KEY_GESTURE_MS,
                CaptureTimingOptions.normalizeSwipeDurationMs(
                    value.gestureDurationMs.toLong(),
                ).toInt(),
            )
            .putBoolean(KEY_POST_GESTURE_WAIT_ENABLED, value.postGestureWaitEnabled)
            .putInt(
                KEY_POST_GESTURE_WAIT_MS,
                CaptureTimingOptions.normalizePostGestureWaitMs(
                    value.postGestureWaitMs.toLong(),
                ).toInt(),
            )
            .putInt(KEY_WAIT_STABLE, value.waitStableMs)
            .putInt(KEY_SCROLL_DISTANCE, value.scrollDistancePx)
            .putInt(KEY_MAX_OUTPUT_HEIGHT, value.maxOutputHeight)
            .putString(KEY_NAMING_RULE, NamingRule.normalizeStoredValue(value.namingRule))
            .putBoolean(KEY_AUTO_OPEN, value.autoOpenResult)
            .putString(KEY_OUTPUT_FORMAT, value.outputFormat.name)
            .putInt(KEY_JPEG_QUALITY, normalizeJpegQuality(value.jpegQuality))
            .apply()
    }

    fun saveGlobalSettings(value: CaptureSettingsUiState) {
        preferences.edit()
            .putString(KEY_APP_LANGUAGE, value.appLanguage.storedValue)
            .putString(KEY_THEME_MODE, value.themeMode)
            .putBoolean(KEY_DYNAMIC_COLOR, value.dynamicColor)
            .apply()
    }

    fun loadOverlayOpacitySettings(): OverlayOpacitySettings = OverlayOpacitySettings(
        panelOpacity = normalizeOverlayOpacity(
            preferences.getFloat(KEY_OVERLAY_PANEL_OPACITY, DEFAULT_OVERLAY_PANEL_OPACITY),
        ),
        nonFocusedRegionOpacity = normalizeOverlayOpacity(
            preferences.getFloat(
                KEY_NON_FOCUSED_REGION_OPACITY,
                DEFAULT_NON_FOCUSED_REGION_OPACITY,
            ),
        ),
    )

    fun saveOverlayPanelOpacity(value: Float) {
        preferences.edit()
            .putFloat(KEY_OVERLAY_PANEL_OPACITY, normalizeOverlayOpacity(value))
            .apply()
    }

    fun saveNonFocusedRegionOpacity(value: Float) {
        preferences.edit()
            .putFloat(KEY_NON_FOCUSED_REGION_OPACITY, normalizeOverlayOpacity(value))
            .apply()
    }

    fun resetDefaultCaptureSettings() {
        preferences.edit()
            .remove(KEY_PREVIEW_ENABLED)
            .remove(KEY_CLEAN_CAPTURE)
            .remove(KEY_PREVIEW_WIDTH)
            .remove(KEY_PREVIEW_MAX_HEIGHT)
            .remove(KEY_GAP_DURATION)
            .remove(KEY_WAIT_MS)
            .remove(KEY_GESTURE_MS)
            .remove(KEY_POST_GESTURE_WAIT_ENABLED)
            .remove(KEY_POST_GESTURE_WAIT_MS)
            .remove(KEY_WAIT_STABLE)
            .remove(KEY_SCROLL_DISTANCE)
            .remove(KEY_MAX_OUTPUT_HEIGHT)
            .remove(KEY_DETECT_OVERLAP)
            .remove(KEY_CROP_DUPLICATE)
            .remove(KEY_KEEP_ORIGINAL)
            .remove(KEY_KEEP_TEMP)
            .remove(KEY_NAMING_RULE)
            .remove(KEY_AUTO_OPEN)
            .remove(KEY_OUTPUT_FORMAT)
            .remove(KEY_JPEG_QUALITY)
            .apply()
    }

    fun loadResultSortOrder(): ContentSortOrder = ContentSortOrder.fromStoredValue(
        preferences.getString(KEY_RESULT_SORT_ORDER, null),
    )

    fun saveResultSortOrder(value: ContentSortOrder) {
        preferences.edit().putString(KEY_RESULT_SORT_ORDER, value.name).apply()
    }

    fun loadTemplateSortOrder(): ContentSortOrder = ContentSortOrder.fromStoredValue(
        preferences.getString(KEY_TEMPLATE_SORT_ORDER, null),
        ContentSortOrder.templateOptions,
    )

    fun saveTemplateSortOrder(value: ContentSortOrder) {
        preferences.edit().putString(KEY_TEMPLATE_SORT_ORDER, value.name).apply()
    }

    companion object {
        const val PREFS_NAME = "game_long_shot_preferences"
        private const val KEY_PREVIEW_ENABLED = "preview_enabled"
        private const val KEY_CLEAN_CAPTURE = "clean_capture"
        private const val KEY_PREVIEW_WIDTH = "preview_width_dp"
        private const val KEY_PREVIEW_MAX_HEIGHT = "preview_max_height_dp"
        private const val KEY_GAP_DURATION = "gap_duration_ms"
        private const val KEY_WAIT_MS = "wait_after_gesture_ms"
        private const val KEY_GESTURE_MS = "gesture_duration_ms"
        private const val KEY_POST_GESTURE_WAIT_ENABLED = "post_gesture_wait_enabled"
        private const val KEY_POST_GESTURE_WAIT_MS = "post_gesture_wait_ms"
        private const val KEY_WAIT_STABLE = "wait_stable_ms"
        private const val KEY_SCROLL_DISTANCE = "scroll_distance_px"
        private const val KEY_MAX_OUTPUT_HEIGHT = "max_output_height"
        private const val KEY_DETECT_OVERLAP = "detect_overlap"
        private const val KEY_CROP_DUPLICATE = "crop_duplicate"
        private const val KEY_KEEP_ORIGINAL = "keep_original_frames"
        private const val KEY_KEEP_TEMP = "keep_temp_files_on_failure"
        private const val KEY_NAMING_RULE = "naming_rule"
        private const val KEY_AUTO_OPEN = "auto_open_result"
        private const val KEY_OUTPUT_FORMAT = "output_format"
        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_SHIZUKU_MODE_ENABLED = "shizuku_mode_enabled"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_OVERLAY_PANEL_OPACITY = "overlay_panel_opacity"
        private const val KEY_NON_FOCUSED_REGION_OPACITY = "non_focused_region_opacity"
        private const val KEY_RESULT_SORT_ORDER = "result_sort_order"
        private const val KEY_TEMPLATE_SORT_ORDER = "template_sort_order"

        fun normalizeJpegQuality(value: Int): Int =
            ImageOutputFormat.normalizeJpegQuality(value)
    }
}
