package com.apricotiris.IrisShot.overlay

import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.Space
import android.widget.TextView
import android.view.ViewGroup
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.CaptureTimingOptions
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.FixedAnchorPosition
import com.apricotiris.IrisShot.core.model.FixedLayerConfig
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.RegionLearningResult
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.core.model.withDefaultPreviewGeometry
import com.apricotiris.IrisShot.core.model.withNewTemplateCaptureDefaults
import com.apricotiris.IrisShot.core.template.CrossAppOverwriteConfirmation
import com.apricotiris.IrisShot.core.template.TargetApplicationInfo
import com.apricotiris.IrisShot.gesture.GameScrollAccessibilityService
import com.apricotiris.IrisShot.gesture.maximumScrollGestureDistance
import com.apricotiris.IrisShot.gesture.moveScrollGestureRegionByPathCenter
import com.apricotiris.IrisShot.gesture.resolveScrollGesturePath
import com.apricotiris.IrisShot.gesture.scrollGestureAxisInset
import com.apricotiris.IrisShot.gesture.ScrollGesturePath
import com.apricotiris.IrisShot.template.EditorFloatRect
import com.apricotiris.IrisShot.template.EditorFrameBorderAllowance
import com.apricotiris.IrisShot.template.ContainedEditorLabelRequest
import com.apricotiris.IrisShot.template.EditorPoint
import com.apricotiris.IrisShot.template.editorFrameBorderObstacles
import com.apricotiris.IrisShot.template.editorLabelRotationForContainer
import com.apricotiris.IrisShot.template.editorSharedFrameBorderAllowances
import com.apricotiris.IrisShot.template.fixedBandRunsVertically
import com.apricotiris.IrisShot.template.fixedBandStretchesWithScroll
import com.apricotiris.IrisShot.template.fixedAnchorBlocks
import com.apricotiris.IrisShot.template.normalizeTemplateForEditor
import com.apricotiris.IrisShot.template.placeContainedEditorLabels
import com.apricotiris.IrisShot.template.shouldShowFixedAnchorBlockLabels
import com.apricotiris.IrisShot.template.shouldShowFixedBandLabels
import com.apricotiris.IrisShot.template.setFixedRegionSettingsContent
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.AppLanguage
import com.apricotiris.IrisShot.ui.AppLocaleManager
import com.apricotiris.IrisShot.ui.DEFAULT_NON_FOCUSED_REGION_OPACITY
import com.apricotiris.IrisShot.ui.normalizeOverlayOpacity
import com.apricotiris.IrisShot.ui.theme.GameLongShotTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val OVERLAY_OPACITY_FADE_DURATION_MS = 200L
private const val OVERLAY_TRANSITION_DURATION_MS = 180L
private const val MIN_REGION_EDITOR_GESTURE_DISTANCE_PX = 48
private const val REGION_EDITOR_GESTURE_TOUCH_RADIUS_DP = 14f
private const val GESTURE_MIN_DISTANCE_TOUCH_RADIUS_MULTIPLIER = 4f
private const val REGION_CONFIDENCE_VISIBLE_DURATION_MS = 6_000L
private const val REGION_CONFIDENCE_FADE_DURATION_MS = 320L
private const val REGION_EDITOR_HANDLE_PROXY_COUNT = 12
private const val FALLBACK_MAXIMUM_OBSCURING_OPACITY = 0.8f
private const val PASS_THROUGH_OPACITY_SAFETY_MARGIN = 0.01f
private const val OVERLAY_CONTROLLER_LOG_TAG = "OverlayController"
private const val TOUCH_PROXY_INSCRIBED_HALF_SIDE_FACTOR = 0.7f

internal fun interactiveOverlayWindowType(accessibilityOverlayAvailable: Boolean): Int =
    if (accessibilityOverlayAvailable) {
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

internal fun regionEditorPassThroughWindowAlpha(
    windowType: Int,
    applicationOverlayAlpha: Float,
): Float = if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
    1f
} else {
    applicationOverlayAlpha.coerceIn(0f, 1f)
}

internal fun shouldFallbackInteractiveOverlay(
    attemptedWindowType: Int,
    allViewsAttached: Boolean,
    applicationOverlayAvailable: Boolean,
): Boolean = !allViewsAttached &&
    attemptedWindowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
    applicationOverlayAvailable

internal fun canApplyOverlayConfigurationRefresh(
    expectedLoadGeneration: Long,
    currentLoadGeneration: Long,
    panelAttached: Boolean,
): Boolean = panelAttached &&
    expectedLoadGeneration == currentLoadGeneration

internal fun outsideTouchHitsProtectedInsets(
    rawX: Float,
    rawY: Float,
    contentLeft: Int,
    contentTop: Int,
    contentRight: Int,
    contentBottom: Int,
    guardPx: Int,
): Boolean {
    if (!rawX.isFinite() || !rawY.isFinite() || (rawX == 0f && rawY == 0f)) return false
    val guard = guardPx.coerceAtLeast(0)
    val guardedLeft = (contentLeft + guard).coerceAtMost(contentRight)
    val guardedTop = (contentTop + guard).coerceAtMost(contentBottom)
    val guardedRight = (contentRight - guard).coerceAtLeast(contentLeft)
    val guardedBottom = (contentBottom - guard).coerceAtLeast(contentTop)
    return rawX < guardedLeft || rawX >= guardedRight ||
        rawY < guardedTop || rawY >= guardedBottom
}

internal fun captureGapPreviewBounds(
    viewport: EditorFloatRect,
    controlBounds: EditorFloatRect,
    scrollDirection: ScrollDirection,
): EditorFloatRect {
    val referenceHeight = controlBounds.height.coerceAtLeast(1f)
    return if (scrollDirection.isVertical) {
        val left = controlBounds.right.coerceIn(viewport.left, viewport.right - 1f)
        EditorFloatRect(
            left = left,
            top = viewport.top,
            right = (left + referenceHeight).coerceAtMost(viewport.right),
            bottom = viewport.bottom,
        )
    } else {
        val bottom = controlBounds.top.coerceIn(viewport.top + 1f, viewport.bottom)
        EditorFloatRect(
            left = viewport.left,
            top = (bottom - referenceHeight).coerceAtLeast(viewport.top),
            right = viewport.right,
            bottom = bottom,
        )
    }
}

internal fun captureGapPreviewContentBounds(
    maximumBounds: EditorFloatRect,
    contentWidth: Int,
    contentHeight: Int,
    scrollDirection: ScrollDirection,
): EditorFloatRect {
    require(maximumBounds.width > 0f && maximumBounds.height > 0f)
    val sourceWidth = contentWidth.coerceAtLeast(1).toFloat()
    val sourceHeight = contentHeight.coerceAtLeast(1).toFloat()
    val scale = minOf(
        maximumBounds.width / sourceWidth,
        maximumBounds.height / sourceHeight,
    )
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    return if (scrollDirection.isVertical) {
        EditorFloatRect(
            left = maximumBounds.left,
            top = maximumBounds.bottom - height,
            right = maximumBounds.left + width,
            bottom = maximumBounds.bottom,
        )
    } else {
        val left = maximumBounds.left + (maximumBounds.width - width) / 2f
        EditorFloatRect(
            left = left,
            top = maximumBounds.bottom - height,
            right = left + width,
            bottom = maximumBounds.bottom,
        )
    }
}

internal fun captureGapControlBounds(
    viewport: EditorFloatRect,
    preferredWidth: Float,
    height: Float,
    bottomMargin: Float,
    horizontalMargin: Float,
    scrollDirection: ScrollDirection,
    reservePreviewSideSpace: Boolean = true,
): EditorFloatRect {
    val safeHorizontalMargin = horizontalMargin.coerceAtLeast(0f)
        .coerceAtMost((viewport.width - 1f).coerceAtLeast(0f) / 2f)
    val usable = EditorFloatRect(
        viewport.left + safeHorizontalMargin,
        viewport.top,
        viewport.right - safeHorizontalMargin,
        viewport.bottom,
    )
    val controlHeight = height.coerceIn(1f, usable.height.coerceAtLeast(1f))
    val maximumWidth = if (reservePreviewSideSpace && scrollDirection.isVertical) {
        (usable.width - controlHeight * 2f).coerceAtLeast(1f)
    } else {
        usable.width.coerceAtLeast(1f)
    }
    val controlWidth = preferredWidth.coerceIn(1f, maximumWidth)
    val bottom = (usable.bottom - bottomMargin.coerceAtLeast(0f))
        .coerceIn(usable.top + controlHeight, usable.bottom)
    val top = bottom - controlHeight
    val placedLeft = usable.left + (usable.width - controlWidth) / 2f
    return EditorFloatRect(placedLeft, top, placedLeft + controlWidth, bottom)
}

internal fun navigationContentBoundsFromVisibleInsets(
    screenWidth: Int,
    screenHeight: Int,
    insetLeft: Int,
    insetTop: Int,
    insetRight: Int,
    insetBottom: Int,
): RectConfig {
    val width = screenWidth.coerceAtLeast(1)
    val height = screenHeight.coerceAtLeast(1)
    if (insetLeft < 0 || insetTop < 0 || insetRight < 0 || insetBottom < 0 ||
        insetLeft + insetRight >= width || insetTop + insetBottom >= height
    ) return RectConfig.full(width, height)
    return RectConfig(
        left = insetLeft,
        top = insetTop,
        right = width - insetRight,
        bottom = height - insetBottom,
    )
}

internal data class LegacyVisibleNavigationInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

internal fun legacyVisibleNavigationInsetsWithoutIme(
    systemLeft: Int,
    systemRight: Int,
    systemBottom: Int,
    stableBottom: Int,
): LegacyVisibleNavigationInsets? {
    val bottom = systemBottom.coerceAtLeast(0)
    // Before API 30 the IME is folded into systemWindowInsetBottom. Stable insets describe only
    // persistent system decor, so a larger live bottom inset must not replace the last navigation
    // sample. A zero live inset remains meaningful because it represents an immersive target.
    if (bottom > stableBottom.coerceAtLeast(0)) return null
    return LegacyVisibleNavigationInsets(
        left = systemLeft.coerceAtLeast(0),
        right = systemRight.coerceAtLeast(0),
        bottom = bottom,
    )
}

internal class LegacyVisibleNavigationInsetsCache {
    private data class Sample(
        val screenWidth: Int,
        val screenHeight: Int,
        val insets: LegacyVisibleNavigationInsets,
    )

    @Volatile
    private var latest: Sample? = null

    fun remember(
        screenWidth: Int,
        screenHeight: Int,
        insets: LegacyVisibleNavigationInsets,
    ) {
        latest = Sample(
            screenWidth = screenWidth.coerceAtLeast(1),
            screenHeight = screenHeight.coerceAtLeast(1),
            insets = insets,
        )
    }

    fun resolve(screenWidth: Int, screenHeight: Int): LegacyVisibleNavigationInsets =
        latest
            ?.takeIf {
                it.screenWidth == screenWidth.coerceAtLeast(1) &&
                    it.screenHeight == screenHeight.coerceAtLeast(1)
            }
            ?.insets
            ?: LegacyVisibleNavigationInsets()
}

internal data class CaptureGapBannerTypography(
    val panelHorizontalPaddingDp: Int,
    val panelVerticalPaddingDp: Int,
    val statusEndPaddingDp: Int,
    val titleTextSizeSp: Int,
    val qualityTextSizeSp: Int,
    val minimumTextSizeSp: Int,
    val qualityMaxLines: Int,
    val actionTextSizeSp: Int,
    val actionHorizontalPaddingDp: Int,
    val actionMinimumWidthDp: Int,
    val compactTitle: Boolean,
)

/**
 * Keeps the capture banner's fixed 64 dp envelope while adapting only its internal typography.
 * A vertical capture on a portrait display leaves a deliberately narrow banner beside the
 * preview strip, so it needs a shorter title, a narrower action and one extra quality line.
 */
internal fun captureGapBannerTypography(controlWidthDp: Float): CaptureGapBannerTypography =
    if (controlWidthDp < 260f) {
        CaptureGapBannerTypography(
            panelHorizontalPaddingDp = 8,
            panelVerticalPaddingDp = 4,
            statusEndPaddingDp = 4,
            titleTextSizeSp = 11,
            qualityTextSizeSp = 9,
            minimumTextSizeSp = 8,
            qualityMaxLines = 2,
            actionTextSizeSp = 10,
            actionHorizontalPaddingDp = 4,
            actionMinimumWidthDp = 40,
            compactTitle = true,
        )
    } else {
        CaptureGapBannerTypography(
            panelHorizontalPaddingDp = 12,
            panelVerticalPaddingDp = 4,
            statusEndPaddingDp = 6,
            titleTextSizeSp = 12,
            qualityTextSizeSp = 10,
            minimumTextSizeSp = 8,
            qualityMaxLines = 1,
            actionTextSizeSp = 11,
            actionHorizontalPaddingDp = 8,
            actionMinimumWidthDp = 48,
            compactTitle = false,
        )
    }

internal data class RegionLabelAlphas(
    val background: Int,
    val marker: Int,
    val text: Int,
)

internal fun regionOutlineAlpha(
    nonFocusedOpacity: Float,
    active: Boolean,
    forceOpaque: Boolean,
): Int = if (active || forceOpaque) {
    0xFF
} else {
    (nonFocusedOpacity.coerceIn(0f, 1f) * 0xFF).roundToInt()
}

internal fun regionLabelAlphas(
    nonFocusedOpacity: Float,
    selected: Boolean,
    forceOpaque: Boolean,
): RegionLabelAlphas {
    val nonFocused = (nonFocusedOpacity.coerceIn(0f, 1f) * 0xFF).roundToInt()
    return when {
        forceOpaque -> RegionLabelAlphas(0xFF, 0xFF, 0xFF)
        selected -> RegionLabelAlphas(0xD8, 0xFF, 0xF0)
        else -> RegionLabelAlphas(nonFocused, nonFocused, nonFocused)
    }
}

internal data class OverlayTemplateChoiceGroups(
    val all: List<GameCaptureTemplate>,
    val currentApplication: List<GameCaptureTemplate>,
    val otherApplications: List<GameCaptureTemplate>,
)

internal fun groupOverlayTemplateChoices(
    editing: GameCaptureTemplate,
    templates: List<GameCaptureTemplate>,
    packageFilter: String,
    includeEditingDraft: Boolean = false,
): OverlayTemplateChoiceGroups {
    fun sameTemplate(first: GameCaptureTemplate, second: GameCaptureTemplate): Boolean =
        first === second || (first.id != 0L && first.id == second.id)

    val all = buildList {
        if (includeEditingDraft && editing.id == 0L) add(editing)
        templates.forEach { candidate ->
            if (none { sameTemplate(it, candidate) }) add(candidate)
        }
    }
    val currentPackage = packageFilter.trim()
    val current = if (currentPackage.isBlank()) emptyList()
        else all.filter { it.packageName == currentPackage }
    val other = all.filterNot { candidate -> current.any { sameTemplate(candidate, it) } }
    return OverlayTemplateChoiceGroups(all, current, other)
}

internal fun clampOverlayTopStartPosition(
    viewportLeft: Int,
    viewportTop: Int,
    viewportRight: Int,
    viewportBottom: Int,
    viewWidth: Int,
    viewHeight: Int,
    x: Int,
    y: Int,
): Pair<Int, Int> {
    val width = viewWidth.coerceAtLeast(1)
    val height = viewHeight.coerceAtLeast(1)
    val maximumX = (viewportRight - width).coerceAtLeast(viewportLeft)
    val maximumY = (viewportBottom - height).coerceAtLeast(viewportTop)
    return x.coerceIn(viewportLeft, maximumX) to y.coerceIn(viewportTop, maximumY)
}

/** Replaces a just-edited saved template in the selector instead of retaining its stale copy. */
internal fun mergeOverlayTemplateList(
    current: GameCaptureTemplate,
    templates: List<GameCaptureTemplate>,
): List<GameCaptureTemplate> {
    if (current.id == 0L) return templates.distinctBy(GameCaptureTemplate::id)
    val replaced = templates.map { candidate ->
        if (candidate.id == current.id) current else candidate
    }
    return (if (replaced.any { it.id == current.id }) replaced else replaced + current)
        .distinctBy(GameCaptureTemplate::id)
}

internal fun isNewOverlayTemplateDraftName(name: String): Boolean =
    name == "新建模板" || name == "New template"

internal fun useDarkOverlayTheme(themeMode: String, systemUiMode: Int): Boolean =
    when (themeMode.trim().uppercase()) {
        "LIGHT" -> false
        "DARK" -> true
        else -> systemUiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

internal fun overlayWarningColor(darkTheme: Boolean): Int =
    if (darkTheme) 0xFFFFD166.toInt() else 0xFF7D5800.toInt()

internal fun constrainOverlayToolbarPosition(
    proposedX: Int,
    proposedY: Int,
    handleLeft: Int,
    handleTop: Int,
    handleRight: Int,
    handleBottom: Int,
    viewportLeft: Int,
    viewportTop: Int,
    viewportRight: Int,
    viewportBottom: Int,
): Pair<Int, Int> {
    val minimumX = viewportLeft - handleLeft
    val maximumX = viewportRight - handleRight
    val minimumY = viewportTop - handleTop
    val maximumY = viewportBottom - handleBottom
    return proposedX.coerceIn(minimumX, maximumX.coerceAtLeast(minimumX)) to
        proposedY.coerceIn(minimumY, maximumY.coerceAtLeast(minimumY))
}

internal data class OverlayToolbarMeasurement(
    val actionsWidth: Int,
    val toolbarWidth: Int,
)

private data class RegionEditorHandleTouchProxy(
    val view: View,
    val bounds: Rect,
    val params: WindowManager.LayoutParams,
)

internal fun overlayToolbarMeasurement(
    naturalActionsWidth: Int,
    safeViewportWidth: Int,
    outerMargin: Int,
    chromeWidth: Int,
    showAllActions: Boolean,
): OverlayToolbarMeasurement {
    val maximumActionsWidth = (
        safeViewportWidth - outerMargin * 2 - chromeWidth
    ).coerceAtLeast(1)
    val naturalWidth = naturalActionsWidth.coerceAtLeast(1)
    val actionsWidth = if (showAllActions) {
        naturalWidth
    } else {
        naturalWidth.coerceAtMost(maximumActionsWidth)
    }
    return OverlayToolbarMeasurement(
        actionsWidth = actionsWidth,
        toolbarWidth = chromeWidth + actionsWidth,
    )
}

private class OverlayComposeLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this).apply {
        performAttach()
        performRestore(null)
    }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        if (registry.currentState == Lifecycle.State.INITIALIZED) {
            registry.currentState = Lifecycle.State.CREATED
        }
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** WindowManager UI used while the target game remains foreground. */
@SuppressLint("SetTextI18n")
class OverlayController(
    context: Context,
    private val callback: Callback,
) : AutoCloseable {

    interface Callback {
        fun onOpenApp() = Unit
        fun onOpenControls() = Unit
        fun onExpandedOverlayShown() = Unit
        fun onExpandedOverlayDismissed() = Unit
        /** Called when the expanded overlay consumes the system Back key. */
        fun onOverlayBackPressed(template: GameCaptureTemplate?) = Unit
        /**
         * Gives the service a chance to distinguish the underlying app from a notification shade
         * or a compact/freeform app window before confirming an outside-touch collapse.
         */
        fun onOverlayOutsideTouched(
            rawX: Float,
            rawY: Float,
            touchesSystemBar: Boolean,
            onConfirmed: () -> Unit,
        ) = onConfirmed()
        fun canShowCaptureGap() = true
        fun canShowCaptureCompletion(resultSessionId: String) = true
        fun onCaptureCompletionConfirmed(resultSessionId: String) = Unit
        fun onCaptureCompletionOpen(resultSessionId: String) = Unit
        fun onTemplateChanged(template: GameCaptureTemplate) = Unit
        fun onTemplateSelected(template: GameCaptureTemplate) = onTemplateChanged(template)
        fun onOutputOptionsChanged(format: ImageOutputFormat, jpegQuality: Int) = Unit
        fun onResolveTargetApplication(onResolved: (TargetApplicationInfo?) -> Unit) =
            onResolved(null)
        fun onSaveTemplate(
            template: GameCaptureTemplate,
            name: String,
            confirmation: CrossAppOverwriteConfirmation?,
            onResult: (TemplateSaveResult) -> Unit,
        ) = Unit
        fun onEditRegions(template: GameCaptureTemplate) = Unit
        fun onLearnRegions(template: GameCaptureTemplate) = Unit
        fun onStartCapture(template: GameCaptureTemplate) = Unit
        fun onStopCapture() = Unit
        fun onExitCapture() = Unit
    }

    sealed interface TemplateSaveResult {
        data class Saved(val template: GameCaptureTemplate) : TemplateSaveResult
        data class ConfirmationRequired(
            val confirmation: CrossAppOverwriteConfirmation,
        ) : TemplateSaveResult
        data class Rejected(val message: String) : TemplateSaveResult
    }

    class ConfigurationRefreshToken internal constructor(
        val loadGeneration: Long,
        val template: GameCaptureTemplate,
    )

    data class CaptureGapInfo(
        val frameCount: Int,
        val processingFrameNumber: Int? = null,
        val stitchedWidth: Int,
        val stitchedHeight: Int,
        val confidence: Float,
        val preview: Bitmap? = null,
        val previewContentWidth: Int = preview?.width ?: 1,
        val previewContentHeight: Int = preview?.height ?: 1,
        val previewScrollDirection: ScrollDirection = ScrollDirection.UP,
        val message: String = "",
    )

    data class CaptureCompletionInfo(
        val resultSessionId: String,
        val frameCount: Int,
        val stitchedWidth: Int,
        val stitchedHeight: Int,
        val scrollDirection: ScrollDirection,
    )

    private data class CaptureGapOverlay(
        val panel: LinearLayout,
        val title: TextView,
        val quality: TextView,
        var info: CaptureGapInfo,
        var previewFrame: FrameLayout? = null,
        var previewImage: ImageView? = null,
        val pendingPresentationCallbacks: MutableList<(Boolean) -> Unit> = mutableListOf(),
        var presentationCallbackScheduled: Boolean = false,
    )

    private data class RegionRecognitionOverlay(
        val root: FrameLayout,
        val title: TextView,
        val subtitle: TextView,
        val progress: ComposeView,
        val countdownUpdater: Runnable,
        val windowManager: WindowManager,
    )

    private data class InteractiveOverlayWindowHost(
        val context: Context,
        val windowManager: WindowManager,
        val windowType: Int,
    )

    private val appContext = context.applicationContext
    private val windowContext = createOverlayWindowContext(appContext)
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val appPreferences = AppPreferences(appContext)
    private var overlayDarkTheme = resolveOverlayDarkTheme()
    private var overlayLanguage = appPreferences.loadAppLanguage()
    private var overlayContext = createOverlayThemeContext(
        windowContext,
        overlayDarkTheme,
        overlayLanguage,
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachedViews = linkedSetOf<View>()
    private val attachedViewWindowManagers = linkedMapOf<View, WindowManager>()
    private val composeLifecycleOwners = linkedMapOf<View, OverlayComposeLifecycleOwner>()
    /**
     * API 33+ dispatches the system Back gesture directly to the window's
     * [OnBackInvokedDispatcher]. Keep the dispatcher together with its callback
     * so it can be unregistered before an overlay view is removed or rebuilt.
     */
    private data class BackInvokedRegistration(
        val dispatcher: OnBackInvokedDispatcher,
        val callback: OnBackInvokedCallback,
    )

    private val backInvokedRegistrations = linkedMapOf<View, BackInvokedRegistration>()
    private val horizontallyAnimatedViews = linkedSetOf<View>()
    private var currentTemplate: GameCaptureTemplate? = null
    private var availableTemplates: List<GameCaptureTemplate> = emptyList()
    private var availableTemplatesLoading = false
    private var targetPackageForTemplateFilter: String = ""
    private var outputFormat: ImageOutputFormat = ImageOutputFormat.JPEG
    private var jpegQuality: Int = 90
    private var entryPosition = intArrayOf(dp(20), dp(120))
    private var entryBubble: View? = null
    private var isExplicitNewTemplateDraft = false
    private var configurationPanel: ConfigurationPanel? = null
    private var configurationDismissLayer: View? = null
    private val legacyVisibleNavigationInsetsCache = LegacyVisibleNavigationInsetsCache()
    private var captureGapOverlay: CaptureGapOverlay? = null
    private var captureKeepAliveView: View? = null
    private var captureKeepAliveOwnerToken: Long? = null
    private var captureCompletionInfo: CaptureCompletionInfo? = null
    private var regionRecognitionOverlay: RegionRecognitionOverlay? = null
    private var regionEditorLayoutUpdater: (() -> Unit)? = null
    private var activeRegionEditorWindowType: Int? = null
    private var activeRegionEditorOverwriteExisting: Boolean? = null
    private var configurationScrollItemIndex = 0
    private var configurationScrollItemOffset = 0
    private var configurationLoadGeneration = 0L
    private var uiGeneration = 0L
    private var closed = false
    private var configurationCallbacksRegistered = true
    private val accessibilityConnectionListener =
        GameScrollAccessibilityService.ConnectionListener {
            mainHandler.post {
                if (!closed) rebuildRegionEditorForCurrentOverlayConnection()
            }
        }
    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            mainHandler.post {
                if (closed) return@post
                captureCompletionInfo?.let { completion ->
                    showCaptureCompletion(completion)
                    return@post
                }
                configurationPanel
                    ?.takeIf { it.view.isAttachedToWindow }
                    ?.let(::updateConfigurationPanelLayout)
                updateConfigurationDismissLayerLayout()
                regionEditorLayoutUpdater?.invoke()
                updateEntryBubblePosition()
            }
        }

        override fun onLowMemory() = Unit
    }

    init {
        appContext.registerComponentCallbacks(configurationCallbacks)
        GameScrollAccessibilityService.addConnectionListener(accessibilityConnectionListener)
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    fun updateOutputOptions(format: ImageOutputFormat, quality: Int) {
        outputFormat = format
        jpegQuality = normalizeJpegQuality(quality)
    }

    fun refreshAppearance(): Unit = onMain {
        if (closed) return@onMain
        val entryVisible = entryBubble?.isAttachedToWindow == true
        val editingConfiguration = configurationPanel
            ?.takeIf { it.view.isAttachedToWindow }
            ?.editing
        val captureGap = captureGapOverlay
            ?.takeIf { it.panel.isAttachedToWindow }
        val captureGapInfo = captureGap?.info
        val pendingCaptureGapCallbacks = captureGap
            ?.pendingPresentationCallbacks
            ?.toList()
            .orEmpty()
        val completion = captureCompletionInfo
        val regionTemplate = currentTemplate.takeIf { regionEditorLayoutUpdater != null }
        if (!refreshOverlayTheme()) return@onMain
        captureGap?.pendingPresentationCallbacks?.clear()

        when {
            editingConfiguration != null -> {
                removeAllNow()
                showConfiguration(
                    editingConfiguration,
                    availableTemplates,
                    targetPackageName = targetPackageForTemplateFilter,
                )
            }
            captureGapInfo != null -> {
                captureGapOverlay = null
                showCaptureGap(
                    captureGapInfo,
                    pendingCaptureGapCallbacks.takeIf { it.isNotEmpty() }?.let { callbacks ->
                        { presented -> callbacks.forEach { callback -> callback(presented) } }
                    },
                )
            }
            completion != null -> showCaptureCompletion(completion)
            regionTemplate != null -> showRegionEditor(
                regionTemplate,
                overwriteExisting = regionTemplate.id != 0L,
            )
            entryVisible -> showEntry(currentTemplate)
        }
    }

    fun showConfigurationPreparingTemplates(
        template: GameCaptureTemplate,
        targetPackageName: String,
        onShown: (ConfigurationRefreshToken?) -> Unit,
    ): Unit = onMain {
        configurationLoadGeneration += 1L
        showConfiguration(
            template = template,
            templates = emptyList(),
            targetPackageName = targetPackageName,
            templatesLoading = true,
        )
        val panel = configurationPanel?.takeIf { attachedViews.contains(it.view) }
        onShown(
            if (panel != null) {
                ConfigurationRefreshToken(
                    loadGeneration = configurationLoadGeneration,
                    template = currentTemplate ?: template,
                )
            } else {
                null
            },
        )
    }

    fun isConfigurationRefreshActive(token: ConfigurationRefreshToken): Boolean =
        canApplyOverlayConfigurationRefresh(
            expectedLoadGeneration = token.loadGeneration,
            currentLoadGeneration = configurationLoadGeneration,
            // Appearance changes may rebuild the Compose panel while the worker is loading.
            // The load generation, rather than the old View instance, owns that request.
            panelAttached = configurationPanel?.view?.isAttachedToWindow == true,
        )

    fun finishTemplateLoading(
        token: ConfigurationRefreshToken,
        templates: List<GameCaptureTemplate>,
        targetPackageName: String,
    ): Unit = onMain {
        if (closed || token.loadGeneration != configurationLoadGeneration) return@onMain
        targetPackageForTemplateFilter = targetPackageName.trim()
        val editing = configurationPanel?.editing ?: currentTemplate ?: token.template
        availableTemplates = mergeOverlayTemplateList(editing, templates)
        availableTemplatesLoading = false
        configurationPanel
            ?.takeIf { it.view.isAttachedToWindow }
            ?.updateTemplates(availableTemplates, targetPackageForTemplateFilter)
    }

    fun showEntry(template: GameCaptureTemplate? = currentTemplate): Unit = onMain {
        if (closed || !canDrawOverlays()) return@onMain
        refreshOverlayTheme()
        template?.let { currentTemplate = it }
        callback.onExpandedOverlayDismissed()
        removeAllNow(animate = true)
        val bubble = button(overlayString(R.string.overlay_entry_long_screenshot), accent = true) { view ->
            removeViewsNow(listOf(view))
            callback.onOpenControls()
        }.apply {
            typeface = Typeface.DEFAULT_BOLD
            elevation = dp(8).toFloat()
        }
        val params = baseParams(dp(88), dp(40), focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            val viewport = previewViewport(0)
            val clamped = clampOverlayTopStartPosition(
                viewportLeft = viewport.left,
                viewportTop = viewport.top,
                viewportRight = viewport.right,
                viewportBottom = viewport.bottom,
                viewWidth = dp(88),
                viewHeight = dp(40),
                x = entryPosition[0],
                y = entryPosition[1],
            )
            x = clamped.first
            y = clamped.second
            entryPosition[0] = x
            entryPosition[1] = y
        }
        var downX = 0f
        var downY = 0f
        var originX = 0
        var originY = 0
        var dragged = false
        var layoutUpdatePosted = false
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    dragged = dragged || abs(dx) > dp(5) || abs(dy) > dp(5)
                    val viewport = previewViewport(0)
                    val clamped = clampOverlayTopStartPosition(
                        viewportLeft = viewport.left,
                        viewportTop = viewport.top,
                        viewportRight = viewport.right,
                        viewportBottom = viewport.bottom,
                        viewWidth = bubble.width.takeIf { it > 0 } ?: dp(88),
                        viewHeight = bubble.height.takeIf { it > 0 } ?: dp(40),
                        x = originX + dx.roundToInt(),
                        y = originY + dy.roundToInt(),
                    )
                    params.x = clamped.first
                    params.y = clamped.second
                    entryPosition[0] = params.x
                    entryPosition[1] = params.y
                    if (!layoutUpdatePosted) {
                        layoutUpdatePosted = true
                        bubble.postOnAnimation {
                            layoutUpdatePosted = false
                            if (bubble.isAttachedToWindow) {
                                runCatching { windowManager.updateViewLayout(bubble, params) }
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) bubble.performClick()
                    true
                }
                else -> false
            }
        }
        entryBubble = bubble
        addView(bubble, params)
    }

    fun showConfiguration(
        template: GameCaptureTemplate,
        templates: List<GameCaptureTemplate> = availableTemplates,
        targetPackageName: String? = null,
        expectedRefreshToken: ConfigurationRefreshToken? = null,
        templatesLoading: Boolean = availableTemplatesLoading,
    ): Unit = onMain {
        if (closed || !canDrawOverlays()) return@onMain
        if (expectedRefreshToken != null &&
            !isConfigurationRefreshActive(expectedRefreshToken)
        ) return@onMain
        val themeChanged = refreshOverlayTheme()
        if (targetPackageName != null) {
            targetPackageForTemplateFilter = targetPackageName.trim()
        }
        val normalized = template.withNormalizedRegions()
        if (normalized.id != 0L || !isNewOverlayTemplateDraftName(normalized.templateName)) {
            isExplicitNewTemplateDraft = false
        }
        currentTemplate = normalized
        outputFormat = normalized.outputFormat
        jpegQuality = normalizeJpegQuality(normalized.jpegQuality)
        availableTemplates = mergeOverlayTemplateList(normalized, templates)
        availableTemplatesLoading = templatesLoading
        configurationPanel?.takeIf { !themeChanged && it.view.isAttachedToWindow }?.let { existing ->
            existing.update(
                normalized,
                availableTemplates,
                targetPackageForTemplateFilter,
                availableTemplatesLoading,
            )
            updateConfigurationPanelLayout(existing)
            return@onMain
        }

        callback.onExpandedOverlayShown()
        removeAllNow(animate = true)
        val opacitySettings = appPreferences.loadOverlayOpacitySettings()
        val opacityPreview = RegionSelectionView(overlayContext).apply {
            alpha = 0f
            setOpacityPreviewMode(true)
            setNonFocusedOpacity(opacitySettings.nonFocusedRegionOpacity)
            update(
                screenWidth = normalized.screenWidth,
                screenHeight = normalized.screenHeight,
                scrollDirection = normalized.scrollDirection,
                capture = normalized.captureRegion,
                stitch = normalized.stitchRegion,
                gesture = normalized.gestureRegion,
                gestureDistancePx = normalized.gestureDistancePx,
                fixedLayers = normalized.fixedLayers,
            )
            setDisplayCutoutInsets(currentDisplayCutoutInsets(this))
            setOnApplyWindowInsetsListener { _, insets ->
                rememberLegacyVisibleNavigationInsets(insets)
                setDisplayCutoutInsets(displayCutoutInsets(insets))
                insets
            }
        }
        addView(
            opacityPreview,
            baseParams(-1, -1, focusable = false).apply {
                gravity = Gravity.TOP or Gravity.START
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                allowLayoutInDisplayCutout()
            },
        )
        // Keep this receiver over the complete physical display (apart from cutouts). The panel
        // itself remains above it, while blank strips that become available after immersive bars
        // hide still collapse the expanded surface in landscape.
        val dismissBounds = captureGapViewport(margin = 0)
        val dismissLayer = View(overlayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnApplyWindowInsetsListener { _, insets ->
                rememberLegacyVisibleNavigationInsets(insets)
                mainHandler.post { updateConfigurationDismissLayerLayout() }
                insets
            }
            var downInside = false
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downInside = true
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (downInside) {
                            requestOutsideTouchAction(event) { collapseExpandedOverlay() }
                            performClick()
                        }
                        downInside = false
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        downInside = false
                        true
                    }
                    else -> true
                }
            }
        }
        addView(
            dismissLayer,
            baseParams(dismissBounds.width(), dismissBounds.height(), focusable = false).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dismissBounds.left
                y = dismissBounds.top
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                allowLayoutInDisplayCutout()
            },
        )
        configurationDismissLayer = dismissLayer
        dismissLayer.requestApplyInsets()
        val panel = ConfigurationPanel(
            initialTemplate = normalized,
            initialTemplates = availableTemplates,
            initialPackageFilter = targetPackageForTemplateFilter,
            initialTemplatesLoading = availableTemplatesLoading,
            opacityPreview = opacityPreview,
            initialPanelOpacity = opacitySettings.panelOpacity,
            initialNonFocusedRegionOpacity = opacitySettings.nonFocusedRegionOpacity,
        )
        configurationPanel = panel
        installOutsideTouchHandler(panel.view) { event ->
            requestOutsideTouchAction(event) { collapseExpandedOverlay() }
        }
        panel.view.setOnApplyWindowInsetsListener { _, insets ->
            rememberLegacyVisibleNavigationInsets(insets)
            mainHandler.post {
                if (configurationPanel === panel && panel.view.isAttachedToWindow) {
                    updateConfigurationPanelLayout(panel, panelSafeInsets(insets))
                    updateConfigurationDismissLayerLayout()
                }
            }
            insets
        }
        addView(
            panel.view,
            configurationPanelLayoutParams(),
            animateHorizontally = true,
        )
        panel.view.requestApplyInsets()
    }

    private inner class ConfigurationPanel(
        initialTemplate: GameCaptureTemplate,
        initialTemplates: List<GameCaptureTemplate>,
        initialPackageFilter: String,
        initialTemplatesLoading: Boolean,
        private val opacityPreview: RegionSelectionView,
        initialPanelOpacity: Float,
        initialNonFocusedRegionOpacity: Float,
    ) {
        var editing = initialTemplate.withNormalizedRegions()
            private set

        private var templates = initialTemplates
        private var packageFilter = initialPackageFilter
        private val editingState = mutableStateOf(editing)
        private val templatesState = mutableStateOf(templates)
        private val packageFilterState = mutableStateOf(packageFilter)
        private val templatesLoadingState = mutableStateOf(initialTemplatesLoading)
        private val includeEditingDraftState = mutableStateOf(isExplicitNewTemplateDraft)
        private val panelOpacityState = mutableStateOf(normalizeOverlayOpacity(initialPanelOpacity))
        private val nonFocusedRegionOpacityState = mutableStateOf(
            normalizeOverlayOpacity(initialNonFocusedRegionOpacity),
        )
        private val startContentInsetPx = mutableStateOf(currentPanelSafeInsets().left)
        private val themeSettings = appPreferences.loadSettings()
        val view = ComposeView(overlayContext).apply {
            alpha = normalizeOverlayOpacity(initialPanelOpacity)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                GameLongShotTheme(
                    themeMode = themeSettings.themeMode,
                    dynamicColor = themeSettings.dynamicColor,
                ) {
                    val visibleTemplate = editingState.value
                    val startContentInset = with(LocalDensity.current) {
                        startContentInsetPx.value.toDp()
                    }
                    OverlayConfigurationContent(
                        template = visibleTemplate,
                        templates = templatesState.value,
                        packageFilter = packageFilterState.value,
                        templatesLoading = templatesLoadingState.value,
                        includeEditingDraft = includeEditingDraftState.value,
                        initialScrollItemIndex = configurationScrollItemIndex,
                        initialScrollItemOffset = configurationScrollItemOffset,
                        onScrollPositionChange = { index, offset ->
                            configurationScrollItemIndex = index
                            configurationScrollItemOffset = offset
                        },
                        onTemplateSelected = ::selectTemplate,
                        onNewTemplate = ::selectNewTemplate,
                        onDirectionSelected = ::selectDirection,
                        onGestureDurationChanged = ::selectGestureDuration,
                        onWaitChanged = ::selectWaitAfterGesture,
                        onStableTimeChanged = ::selectGestureStableTime,
                        onPostGestureWaitEnabledChanged = ::selectPostGestureWaitEnabled,
                        onPostGestureWaitChanged = ::selectPostGestureWait,
                        panelOpacity = panelOpacityState.value,
                        nonFocusedRegionOpacity = nonFocusedRegionOpacityState.value,
                        onPanelOpacityChanging = ::previewPanelOpacity,
                        onPanelOpacityChanged = ::commitPanelOpacity,
                        onNonFocusedRegionOpacityChanging = ::previewNonFocusedRegionOpacity,
                        onNonFocusedRegionOpacityChanged = ::commitNonFocusedRegionOpacity,
                        onNonFocusedOpacityPreviewChanged = ::setOpacityPreviewVisible,
                        onStartCapture = {
                            currentTemplate = editing
                            callback.onStartCapture(editing)
                        },
                        onEditRegions = {
                            callback.onEditRegions(editing)
                            showRegionEditor(
                                editing,
                                overwriteExisting = editing.id != 0L,
                            )
                        },
                        onLearnRegions = {
                            currentTemplate = editing
                            callback.onLearnRegions(editing)
                        },
                        onSaveTemplate = {
                            showTemplateNamePanel(
                                template = editing,
                                createNew = false,
                                onCancel = {
                                    showConfiguration(editing, availableTemplates)
                                },
                            )
                        },
                        onCollapse = { showEntry(editing) },
                        onExit = { callback.onExitCapture() },
                        startContentInset = startContentInset,
                    )
                }
            }
        }

        fun updateStartContentInset(insetPx: Int) {
            if (startContentInsetPx.value != insetPx) {
                startContentInsetPx.value = insetPx
            }
        }

        fun updatePreviewOcclusion(bounds: Rect) {
            opacityPreview.setLabelOcclusions(
                listOf(
                    RectF(
                        bounds.left.toFloat(),
                        bounds.top.toFloat(),
                        bounds.right.toFloat(),
                        bounds.bottom.toFloat(),
                    ),
                ),
            )
        }

        fun updatePreviewInsets() {
            opacityPreview.setDisplayCutoutInsets(currentDisplayCutoutInsets(opacityPreview))
            opacityPreview.requestApplyInsets()
        }

        private fun previewPanelOpacity(value: Float) {
            val normalized = normalizeOverlayOpacity(value)
            panelOpacityState.value = normalized
            view.alpha = normalized
        }

        private fun commitPanelOpacity(value: Float) {
            val normalized = normalizeOverlayOpacity(value)
            panelOpacityState.value = normalized
            view.alpha = normalized
            appPreferences.saveOverlayPanelOpacity(normalized)
        }

        private fun previewNonFocusedRegionOpacity(value: Float) {
            val normalized = normalizeOverlayOpacity(value)
            nonFocusedRegionOpacityState.value = normalized
            opacityPreview.setNonFocusedOpacity(normalized)
        }

        private fun commitNonFocusedRegionOpacity(value: Float) {
            val normalized = normalizeOverlayOpacity(value)
            nonFocusedRegionOpacityState.value = normalized
            opacityPreview.setNonFocusedOpacity(normalized)
            appPreferences.saveNonFocusedRegionOpacity(normalized)
        }

        private fun setOpacityPreviewVisible(visible: Boolean) {
            view.animate().cancel()
            view.alpha = panelOpacityState.value
            opacityPreview.animate().cancel()
            opacityPreview.animate()
                .alpha(if (visible) 1f else 0f)
                .setDuration(OVERLAY_OPACITY_FADE_DURATION_MS)
                .start()
        }

        init {
            bindComposeLifecycle(view, view)
            publishState()
        }

        fun update(
            template: GameCaptureTemplate,
            refreshedTemplates: List<GameCaptureTemplate>,
            refreshedPackageFilter: String,
            templatesLoading: Boolean,
        ) {
            dismissDropdowns()
            editing = template.withNormalizedRegions()
            templates = refreshedTemplates
            packageFilter = refreshedPackageFilter
            templatesLoadingState.value = templatesLoading
            outputFormat = editing.outputFormat
            jpegQuality = normalizeJpegQuality(editing.jpegQuality)
            publishState()
        }

        fun updateTemplates(
            refreshedTemplates: List<GameCaptureTemplate>,
            refreshedPackageFilter: String,
        ) {
            templates = refreshedTemplates
            packageFilter = refreshedPackageFilter
            templatesLoadingState.value = false
            publishState()
        }

        private fun selectTemplate(choice: GameCaptureTemplate) {
            isExplicitNewTemplateDraft = false
            editing = choice.withNormalizedRegions()
            outputFormat = editing.outputFormat
            jpegQuality = normalizeJpegQuality(editing.jpegQuality)
            currentTemplate = editing
            publishState()
            callback.onTemplateSelected(editing)
            callback.onOutputOptionsChanged(outputFormat, jpegQuality)
        }

        private fun selectNewTemplate() {
            val now = System.currentTimeMillis()
            val currentPackage = packageFilter.trim()
            editing = editing.copy(
                id = 0L,
                packageName = currentPackage,
                appName = editing.appName.takeIf { editing.packageName == currentPackage }.orEmpty(),
                templateName = overlayString(R.string.overlay_new_template),
                createdAt = now,
                updatedAt = now,
            ).withNewTemplateCaptureDefaults()
            isExplicitNewTemplateDraft = true
            currentTemplate = editing
            publishState()
            callback.onTemplateSelected(editing)
        }

        private fun selectDirection(direction: ScrollDirection) {
            if (direction == editing.scrollDirection) return
            val previousDirection = editing.scrollDirection
            val changed = editing.copy(
                scrollDirection = direction,
                stitchRegion = GameCaptureTemplate.defaultStitchRegion(editing.captureRegion, direction),
            )
            editing = if (direction.isVertical != previousDirection.isVertical) {
                changed.withDefaultPreviewGeometry(overlayContext.resources.displayMetrics.density)
            } else {
                changed.withNormalizedRegions()
            }
            currentTemplate = editing
            publishState()
            callback.onTemplateChanged(editing)
        }

        private fun selectWaitAfterGesture(waitMs: Long) {
            val selected = CaptureTimingOptions.normalizeMinimumIntervalMs(waitMs)
            if (selected == editing.waitAfterGestureMs) return
            editing = editing.copy(waitAfterGestureMs = selected)
            currentTemplate = editing
            publishState()
            callback.onTemplateChanged(editing)
        }

        private fun selectGestureDuration(durationMs: Long) {
            val selected = CaptureTimingOptions.normalizeSwipeDurationMs(durationMs)
            if (selected == editing.gestureDurationMs) return
            editing = editing.copy(gestureDurationMs = selected)
            currentTemplate = editing
            publishState()
            callback.onTemplateChanged(editing)
        }

        private fun selectGestureStableTime(stableTimeMs: Long) {
            val selected = CaptureTimingOptions.normalizeStableTimeMs(stableTimeMs)
            if (selected == editing.gestureStableTimeMs) return
            editing = editing.copy(gestureStableTimeMs = selected)
            currentTemplate = editing
            publishState()
            callback.onTemplateChanged(editing)
        }

        private fun selectPostGestureWaitEnabled(enabled: Boolean) {
            if (enabled == editing.postGestureWaitEnabled) return
            editing = editing.copy(postGestureWaitEnabled = enabled)
            currentTemplate = editing
            publishState()
            callback.onTemplateChanged(editing)
        }

        private fun selectPostGestureWait(waitMs: Long) {
            val selected = CaptureTimingOptions.normalizePostGestureWaitMs(waitMs)
            if (selected == editing.postGestureWaitMs) return
            editing = editing.copy(postGestureWaitMs = selected)
            currentTemplate = editing
            publishState()
            callback.onTemplateChanged(editing)
        }

        private fun publishState() {
            editingState.value = editing
            templatesState.value = templates
            packageFilterState.value = packageFilter
            includeEditingDraftState.value = isExplicitNewTemplateDraft && editing.id == 0L
            opacityPreview.update(
                screenWidth = editing.screenWidth,
                screenHeight = editing.screenHeight,
                scrollDirection = editing.scrollDirection,
                capture = editing.captureRegion,
                stitch = editing.stitchRegion,
                gesture = editing.gestureRegion,
                gestureDistancePx = editing.gestureDistancePx,
                fixedLayers = editing.fixedLayers,
            )
        }

        fun dismissDropdowns() = Unit
    }

    private fun createInteractiveOverlayWindowHost(
        preferAccessibilityOverlay: Boolean = true,
    ): InteractiveOverlayWindowHost? {
        val accessibilityHost = if (preferAccessibilityOverlay) {
            GameScrollAccessibilityService.createAccessibilityOverlayWindowContextOrNull()
                ?.let { accessibilityWindowContext ->
                    runCatching {
                        InteractiveOverlayWindowHost(
                            context = createOverlayThemeContext(
                                accessibilityWindowContext,
                                overlayDarkTheme,
                                overlayLanguage,
                            ),
                            windowManager = accessibilityWindowContext.getSystemService(
                                WindowManager::class.java,
                            ),
                            windowType = interactiveOverlayWindowType(
                                accessibilityOverlayAvailable = true,
                            ),
                        )
                    }.getOrNull()
                }
        } else {
            null
        }
        if (accessibilityHost != null) return accessibilityHost
        if (!canDrawOverlays()) return null
        return InteractiveOverlayWindowHost(
            context = overlayContext,
            windowManager = windowManager,
            windowType = interactiveOverlayWindowType(accessibilityOverlayAvailable = false),
        )
    }

    private fun rebuildRegionEditorForCurrentOverlayConnection() {
        val accessibilityConnected = GameScrollAccessibilityService.isConnected()
        val currentWindowType = activeRegionEditorWindowType
        if (currentWindowType == null) {
            if (!accessibilityConnected &&
                attachedViews.any { view ->
                    (view.layoutParams as? WindowManager.LayoutParams)?.type ==
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                }
            ) {
                // The system invalidates the accessibility token at disconnect. Drop any
                // countdown or other trusted interactive window still tracked by this controller.
                removeAllNow()
            }
            return
        }
        val expectedWindowType = interactiveOverlayWindowType(
            accessibilityOverlayAvailable = accessibilityConnected,
        )
        if (currentWindowType == expectedWindowType) return
        val editing = currentTemplate
        val overwriteExisting = activeRegionEditorOverwriteExisting
        removeAllNow()
        if (editing != null) {
            showRegionEditor(
                template = editing,
                suggestion = null,
                overwriteExisting = overwriteExisting ?: (editing.id != 0L),
            )
        }
    }

    fun showRegionEditor(
        template: GameCaptureTemplate,
        suggestion: RegionLearningResult? = null,
        overwriteExisting: Boolean = suggestion == null && template.id != 0L,
    ): Unit = showRegionEditor(
        template = template,
        suggestion = suggestion,
        overwriteExisting = overwriteExisting,
        allowAccessibilityFallback = true,
    )

    private fun showRegionEditor(
        template: GameCaptureTemplate,
        suggestion: RegionLearningResult?,
        overwriteExisting: Boolean,
        allowAccessibilityFallback: Boolean,
    ): Unit = onMain {
        if (closed) return@onMain
        refreshOverlayTheme()
        val editorWindowHost = createInteractiveOverlayWindowHost(
            preferAccessibilityOverlay = allowAccessibilityFallback,
        ) ?: return@onMain
        val editorContext = editorWindowHost.context
        val editorWindowManager = editorWindowHost.windowManager
        val editorWindowType = editorWindowHost.windowType
        removeAllNow(animate = false)
        var editing = normalizeTemplateForEditor(
            if (suggestion == null) template else template.copy(
                stitchRegion = suggestion.suggestedStitchRegion,
                gestureRegion = suggestion.suggestedGestureRegion,
            ),
        )
        val gestureMaximum = maximumScrollGestureDistance(
            editing.stitchRegion,
            editing.scrollDirection,
        )
        val displayMetrics = editorContext.resources.displayMetrics
        val gestureAxisScale = if (editing.scrollDirection.isVertical) {
            displayMetrics.heightPixels.toFloat() / editing.screenHeight.coerceAtLeast(1)
        } else {
            displayMetrics.widthPixels.toFloat() / editing.screenWidth.coerceAtLeast(1)
        }.takeIf { it > 0f } ?: 1f
        val gestureMinimum = maxOf(
            MIN_REGION_EDITOR_GESTURE_DISTANCE_PX,
            kotlin.math.ceil(
                REGION_EDITOR_GESTURE_TOUCH_RADIUS_DP * displayMetrics.density *
                    GESTURE_MIN_DISTANCE_TOUCH_RADIUS_MULTIPLIER /
                    gestureAxisScale,
            ).toInt(),
        ).coerceAtMost(gestureMaximum)
        editing = editing.copy(
            gestureDistancePx = editing.gestureDistancePx.coerceIn(
                gestureMinimum.coerceAtLeast(1),
                gestureMaximum,
            ),
        )
        currentTemplate = editing
        val fixedPanelTemplate = mutableStateOf(editing)
        var refreshEditorTouchableRegion: () -> Unit = {}
        var updateEditorWindowFocusability: () -> Unit = {}
        @Suppress("DEPRECATION")
        val root = FrameLayout(editorContext).apply {
            fitsSystemWindows = false
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        val passThroughWindowAlpha = regionEditorPassThroughWindowAlpha(
            windowType = editorWindowType,
            applicationOverlayAlpha = maximumPassThroughOverlayAlpha(),
        )
        val rootParams = baseParams(
            -1,
            -1,
            focusable = false,
            windowType = editorWindowType,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = passThroughWindowAlpha
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            allowLayoutInDisplayCutout()
        }
        val selection = RegionSelectionView(editorContext).apply {
            setNonFocusedOpacity(
                appPreferences.loadOverlayOpacitySettings().nonFocusedRegionOpacity,
            )
            update(
                screenWidth = editing.screenWidth,
                screenHeight = editing.screenHeight,
                scrollDirection = editing.scrollDirection,
                capture = editing.captureRegion,
                stitch = editing.stitchRegion,
                gesture = editing.gestureRegion,
                gestureDistancePx = editing.gestureDistancePx,
                fixedLayers = editing.fixedLayers,
            )
            onRegionsChanged = { capture, stitch, gesture, gestureDistancePx, fixedLayers ->
                editing = normalizeTemplateForEditor(
                    editing.copy(
                        captureRegion = capture,
                        stitchRegion = stitch,
                        gestureRegion = gesture,
                        gestureDistancePx = gestureDistancePx,
                        fixedLayers = fixedLayers,
                    ),
                )
                currentTemplate = editing
                fixedPanelTemplate.value = editing
                refreshEditorTouchableRegion()
            }
        }
        root.addView(selection, FrameLayout.LayoutParams(-1, -1))
        var interceptEditorBackgroundTouches = false
        val touchProxy = View(editorContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnTouchListener { _, event ->
                val forwarded = MotionEvent.obtain(event)
                try {
                    selection.dispatchTouchEvent(forwarded)
                } finally {
                    forwarded.recycle()
                }
            }
        }
        val touchProxyParams = baseParams(
            -1,
            -1,
            focusable = false,
            windowType = editorWindowType,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            alpha = 0f
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
            allowLayoutInDisplayCutout()
        }
        fun createHandleTouchProxy(): RegionEditorHandleTouchProxy {
            val bounds = Rect()
            val proxy = View(editorContext).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                setOnTouchListener { _, event ->
                    val forwarded = MotionEvent.obtain(event)
                    forwarded.offsetLocation(bounds.left.toFloat(), bounds.top.toFloat())
                    try {
                        selection.dispatchTouchEvent(forwarded)
                    } finally {
                        forwarded.recycle()
                    }
                }
            }
            val params = baseParams(
                1,
                1,
                focusable = false,
                windowType = editorWindowType,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                alpha = 0f
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setFitInsetsTypes(0)
                }
                allowLayoutInDisplayCutout()
            }
            return RegionEditorHandleTouchProxy(proxy, bounds, params)
        }
        val handleTouchProxies = List(REGION_EDITOR_HANDLE_PROXY_COUNT) {
            createHandleTouchProxy()
        }
        var touchRefreshPosted = false
        refreshEditorTouchableRegion = refresh@{
            if (touchRefreshPosted) return@refresh
            touchRefreshPosted = true
            touchProxy.post {
                touchRefreshPosted = false
                if (!touchProxy.isAttachedToWindow || !attachedViews.contains(touchProxy)) {
                    return@post
                }
                val shouldBeTouchable = interceptEditorBackgroundTouches
                val rootAlpha = if (shouldBeTouchable) 1f else passThroughWindowAlpha
                if (rootParams.alpha != rootAlpha) {
                    rootParams.alpha = rootAlpha
                    runCatching {
                        editorWindowManager.updateViewLayout(root, rootParams)
                    }.onFailure { error ->
                        Log.w(
                            OVERLAY_CONTROLLER_LOG_TAG,
                            "Unable to update the region editor visual window",
                            error,
                        )
                    }
                }
                val currentlyTouchable = touchProxyParams.flags and
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
                if (currentlyTouchable != shouldBeTouchable) {
                    touchProxyParams.flags = if (shouldBeTouchable) {
                        touchProxyParams.flags and
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    } else {
                        touchProxyParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    }
                }
                touchProxyParams.alpha = if (shouldBeTouchable) 1f else 0f
                runCatching {
                    editorWindowManager.updateViewLayout(touchProxy, touchProxyParams)
                }.onFailure { error ->
                    Log.w(
                        OVERLAY_CONTROLLER_LOG_TAG,
                        "Unable to update the full-screen editor touch proxy",
                        error,
                    )
                }

                val handleBounds = if (shouldBeTouchable) {
                    emptyList()
                } else {
                    selection.editableHandleBounds()
                }
                handleTouchProxies.forEachIndexed { index, proxy ->
                    val bounds = handleBounds.getOrNull(index)
                    val enabled = bounds != null
                    if (bounds != null) proxy.bounds.set(bounds) else proxy.bounds.setEmpty()
                    proxy.params.width = bounds?.width()?.coerceAtLeast(1) ?: 1
                    proxy.params.height = bounds?.height()?.coerceAtLeast(1) ?: 1
                    proxy.params.x = bounds?.left ?: 0
                    proxy.params.y = bounds?.top ?: 0
                    proxy.params.alpha = if (enabled) 1f else 0f
                    proxy.params.flags = if (enabled) {
                        proxy.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    } else {
                        proxy.params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    }
                    if (proxy.view.isAttachedToWindow) {
                        runCatching {
                            editorWindowManager.updateViewLayout(proxy.view, proxy.params)
                        }.onFailure { error ->
                            Log.w(
                                OVERLAY_CONTROLLER_LOG_TAG,
                                "Unable to update region editor handle proxy $index",
                                error,
                            )
                        }
                    }
                }
                updateEditorWindowFocusability()
            }
        }
        selection.addOnLayoutChangeListener {
                _,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom,
            ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                refreshEditorTouchableRegion()
            }
        }

        val fixedPanel = ComposeView(editorContext).apply {
            visibility = View.GONE
            alpha = 0f
            isClickable = true
            elevation = dp(8).toFloat()
        }
        val fixedPanelStartContentInsetPx = mutableStateOf(0)
        var updateFixedPanelLayoutForCurrentTemplate: (() -> Unit)? = null
        fun currentEditorPanelSafeInsets(): Rect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                panelSafeInsets(editorWindowManager.currentWindowMetrics.windowInsets)
            } else {
                root.rootWindowInsets?.let(::panelSafeInsets) ?: Rect()
            }
        val fixedPanelParams = baseParams(
            1,
            1,
            focusable = true,
            windowType = editorWindowType,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            allowLayoutInDisplayCutout()
        }

        val toolbar = LinearLayout(editorContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(46)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            val surface = themeColor(android.R.attr.colorBackgroundFloating, Color.WHITE)
            background = rounded(
                Color.argb(232, Color.red(surface), Color.green(surface), Color.blue(surface)),
                dp(8).toFloat(),
            )
            elevation = dp(8).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        fun dragHandle(description: String) = label("≡", 19f, getTextColor(true), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(2), 0)
            contentDescription = description
        }
        val leftDragHandle = dragHandle(overlayString(R.string.overlay_drag_toolbar_from_left))
        val rightDragHandle = dragHandle(overlayString(R.string.overlay_drag_toolbar_from_right))
        toolbar.addView(leftDragHandle, LinearLayout.LayoutParams(dp(26), -1))
        val toolbarActions = LinearLayout(editorContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val actionScroller = HorizontalScrollView(editorContext).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(toolbarActions, FrameLayout.LayoutParams(-2, -2))
        }
        toolbar.addView(actionScroller, LinearLayout.LayoutParams(-2, -2))
        var requestToolbarWidthUpdate: (() -> Unit)? = null
        lateinit var modeSelector: Button
        modeSelector = toolbarButton("${overlayString(R.string.overlay_capture_region)} ▼") {
            PopupMenu(editorContext, modeSelector).apply {
                val options = selection.availableEditModeOptions()
                options.forEachIndexed { index, option ->
                    val coloredLabel = SpannableString(option.label).apply {
                        setSpan(
                            ForegroundColorSpan(option.color),
                            0,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    menu.add(0, index, index, coloredLabel)
                }
                setOnMenuItemClickListener { item ->
                    options.getOrNull(item.itemId)?.let { selection.selectEditMode(it.label) } == true
                }
                show()
            }
        }
        fun updateModeSelector(label: String) {
            modeSelector.text = "$label ▼"
            modeSelector.setTextColor(selection.editModeColor(label))
            modeSelector.post { requestToolbarWidthUpdate?.invoke() }
        }
        selection.onEditModeChanged = { label ->
            updateModeSelector(label)
            refreshEditorTouchableRegion()
        }
        selection.onHandleDragStateChanged = { dragging ->
            toolbar.visibility = if (dragging) View.INVISIBLE else View.VISIBLE
        }
        updateModeSelector(overlayString(R.string.overlay_capture_region))
        toolbarActions.addView(modeSelector)
        toolbarActions.addView(space(dp(1)))
        lateinit var touchModeButton: Button
        fun updateTouchModeButton() {
            val label = overlayString(
                if (interceptEditorBackgroundTouches) {
                    R.string.overlay_region_touch_fullscreen
                } else {
                    R.string.overlay_region_touch_passthrough
                },
            )
            touchModeButton.text = label
            touchModeButton.contentDescription = label
            touchModeButton.post { requestToolbarWidthUpdate?.invoke() }
        }
        touchModeButton = toolbarButton("") {
            interceptEditorBackgroundTouches = !interceptEditorBackgroundTouches
            updateTouchModeButton()
            refreshEditorTouchableRegion()
        }
        updateTouchModeButton()
        toolbarActions.addView(touchModeButton)
        toolbarActions.addView(space(dp(1)))

        fun applyFixedLayout(updated: GameCaptureTemplate) {
            val normalized = normalizeTemplateForEditor(updated)
            val maximumGestureDistance = maximumScrollGestureDistance(
                normalized.stitchRegion,
                normalized.scrollDirection,
            )
            editing = normalized.copy(
                gestureDistancePx = normalized.gestureDistancePx.coerceIn(
                    1,
                    maximumGestureDistance,
                ),
            )
            currentTemplate = editing
            fixedPanelTemplate.value = editing
            selection.update(
                screenWidth = editing.screenWidth,
                screenHeight = editing.screenHeight,
                scrollDirection = editing.scrollDirection,
                capture = editing.captureRegion,
                stitch = editing.stitchRegion,
                gesture = editing.gestureRegion,
                gestureDistancePx = editing.gestureDistancePx,
                fixedLayers = editing.fixedLayers,
            )
            refreshEditorTouchableRegion()
            updateFixedPanelLayoutForCurrentTemplate?.invoke()
        }
        val themeSettings = appPreferences.loadSettings()
        fixedPanel.setFixedRegionSettingsContent(
            templateState = fixedPanelTemplate,
            themeMode = themeSettings.themeMode,
            dynamicColor = themeSettings.dynamicColor,
            onTemplateChange = ::applyFixedLayout,
            startContentInsetPx = fixedPanelStartContentInsetPx,
        )

        fun updateFixedPanelOcclusion() {
            selection.setLabelOcclusions(
                if (fixedPanel.visibility == View.VISIBLE) {
                    listOf(
                        RectF(
                            fixedPanelParams.x.toFloat(),
                            fixedPanelParams.y.toFloat(),
                            (fixedPanelParams.x + fixedPanelParams.width).toFloat(),
                            (fixedPanelParams.y + fixedPanelParams.height).toFloat(),
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        }

        var fixedPanelExpanded = false
        var fixedPanelOperationGeneration = 0L

        fun hideFixedPanel() {
            if (!fixedPanelExpanded) return
            fixedPanelExpanded = false
            val operationGeneration = ++fixedPanelOperationGeneration
            fixedPanel.animate().cancel()
            fixedPanel.animate()
                .translationX(-fixedPanel.width.toFloat().coerceAtLeast(1f))
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    if (
                        fixedPanelExpanded ||
                        operationGeneration != fixedPanelOperationGeneration
                    ) return@withEndAction
                    fixedPanel.visibility = View.GONE
                    updateFixedPanelOcclusion()
                    unregisterBackInvokedCallback(fixedPanel)
                    if (fixedPanel.isAttachedToWindow) {
                        composeLifecycleOwners.remove(fixedPanel)?.destroy()
                        fixedPanel.disposeComposition()
                        attachedViews.remove(fixedPanel)
                        val owningWindowManager =
                            attachedViewWindowManagers.remove(fixedPanel) ?: editorWindowManager
                        runCatching { owningWindowManager.removeViewImmediate(fixedPanel) }
                    }
                }
                .start()
        }

        installOutsideTouchHandler(fixedPanel) { hideFixedPanel() }

        fun showFixedPanel() {
            if (fixedPanelExpanded) {
                hideFixedPanel()
                return
            }
            fixedPanelExpanded = true
            val operationGeneration = ++fixedPanelOperationGeneration
            fixedPanel.animate().cancel()
            updateFixedPanelLayoutForCurrentTemplate?.invoke()
            fixedPanel.visibility = View.VISIBLE
            if (!fixedPanel.isAttachedToWindow) {
                // Hiding disposes this secondary window. Rebind before the next attach so
                // repeated open/close cycles never reuse a destroyed lifecycle owner.
                bindComposeLifecycle(fixedPanel, fixedPanel, handleBack = false)
                val attached = addView(
                    fixedPanel,
                    fixedPanelParams,
                    owningWindowManager = editorWindowManager,
                )
                if (!attached) {
                    fixedPanelExpanded = false
                    fixedPanel.visibility = View.GONE
                    return
                }
            }
            fixedPanel.post {
                if (
                    !fixedPanelExpanded ||
                    operationGeneration != fixedPanelOperationGeneration ||
                    !fixedPanel.isAttachedToWindow
                ) return@post
                fixedPanel.animate().cancel()
                fixedPanel.translationX = -fixedPanel.width.toFloat()
                fixedPanel.alpha = 0f
                fixedPanel.animate().translationX(0f).alpha(1f).setDuration(200L).start()
                updateFixedPanelOcclusion()
            }
        }

        toolbarActions.addView(
            toolbarButton(overlayString(R.string.overlay_fixed_region_settings)) { showFixedPanel() },
        )
        toolbarActions.addView(space(dp(1)))
        toolbarActions.addView(toolbarButton(overlayString(R.string.overlay_cancel)) {
            val original = normalizeTemplateForEditor(template)
            currentTemplate = original
            callback.onTemplateChanged(original)
            showConfiguration(original, availableTemplates)
        })
        toolbarActions.addView(space(dp(1)))
        toolbarActions.addView(toolbarButton(overlayString(
            if (editing.id == 0L) R.string.overlay_save else R.string.overlay_save_as,
        )) {
            currentTemplate = editing
            showTemplateNamePanel(
                template = editing,
                createNew = editing.id != 0L,
                onCancel = {
                    showRegionEditor(
                        editing,
                        suggestion = null,
                        overwriteExisting = overwriteExisting,
                    )
                },
            )
        })
        toolbarActions.addView(space(dp(1)))
        toolbarActions.addView(toolbarButton(overlayString(R.string.overlay_done), accent = true) {
            currentTemplate = editing
            callback.onTemplateChanged(editing)
            showConfiguration(editing, availableTemplates)
        })
        toolbar.addView(rightDragHandle, LinearLayout.LayoutParams(dp(26), -1))
        val toolbarParams = baseParams(
            -2,
            -2,
            focusable = false,
            windowType = editorWindowType,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(8)
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            allowLayoutInDisplayCutout()
        }
        updateEditorWindowFocusability = updateFocus@{
            val shouldBeFocusable = interceptEditorBackgroundTouches
            val currentlyFocusable = toolbarParams.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
            if (currentlyFocusable == shouldBeFocusable) return@updateFocus
            toolbarParams.flags = if (shouldBeFocusable) {
                toolbarParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                toolbarParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (toolbar.isAttachedToWindow) {
                runCatching {
                    editorWindowManager.updateViewLayout(toolbar, toolbarParams)
                }.onFailure { error ->
                    Log.w(
                        OVERLAY_CONTROLLER_LOG_TAG,
                        "Unable to update the region editor toolbar focusability",
                        error,
                    )
                }
            }
            if (shouldBeFocusable) {
                installBackKeyHandler(toolbar, toolbarParams)
            } else {
                unregisterBackInvokedCallback(toolbar)
                toolbar.setOnKeyListener(null)
                toolbar.clearFocus()
            }
        }
        var toolbarLayoutUpdatePosted = false
        fun updateToolbarWindowOnAnimation() {
            if (toolbarLayoutUpdatePosted) return
            toolbarLayoutUpdatePosted = true
            toolbar.postOnAnimation {
                toolbarLayoutUpdatePosted = false
                if (toolbar.isAttachedToWindow) {
                    runCatching { editorWindowManager.updateViewLayout(toolbar, toolbarParams) }
                }
            }
        }
        fixedPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateFixedPanelOcclusion()
        }

        fun updateFixedPanelLayout(safeInsets: Rect) {
            val bounds = fixedSettingsPanelBounds(
                safeInsetsOverride = safeInsets,
                metricsWindowManager = editorWindowManager,
            )
            if (fixedPanelStartContentInsetPx.value != safeInsets.left) {
                fixedPanelStartContentInsetPx.value = safeInsets.left
            }
            if (
                fixedPanelParams.width != bounds.width() ||
                fixedPanelParams.height != bounds.height() ||
                fixedPanelParams.x != bounds.left ||
                fixedPanelParams.y != bounds.top
            ) {
                fixedPanelParams.width = bounds.width()
                fixedPanelParams.height = bounds.height()
                fixedPanelParams.x = bounds.left
                fixedPanelParams.y = bounds.top
                if (fixedPanel.isAttachedToWindow) {
                    runCatching {
                        editorWindowManager.updateViewLayout(fixedPanel, fixedPanelParams)
                    }
                }
                updateFixedPanelOcclusion()
            }
        }
        updateFixedPanelLayoutForCurrentTemplate = {
            updateFixedPanelLayout(currentEditorPanelSafeInsets())
        }
        fun updateToolbarActionWidth(safeViewport: Rect) {
            val safeMargin = dp(8)
            val fixedChromeWidth = dp(26) * 2 + toolbar.paddingLeft + toolbar.paddingRight
            toolbarActions.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val measurement = overlayToolbarMeasurement(
                naturalActionsWidth = toolbarActions.measuredWidth,
                safeViewportWidth = safeViewport.width(),
                outerMargin = safeMargin,
                chromeWidth = fixedChromeWidth,
                showAllActions = safeViewport.width() > safeViewport.height(),
            )
            val scrollerLayoutParams = actionScroller.layoutParams
            if (scrollerLayoutParams.width != measurement.actionsWidth) {
                scrollerLayoutParams.width = measurement.actionsWidth
                actionScroller.layoutParams = scrollerLayoutParams
                actionScroller.requestLayout()
            }
            if (toolbarParams.width != measurement.toolbarWidth) {
                toolbarParams.width = measurement.toolbarWidth
                toolbar.requestLayout()
            }
        }
        fun updateRegionEditorLayout() {
            val currentSafeInsets = currentOverlaySafeInsets(root, editorWindowManager)
            val safeViewport = previewViewport(
                margin = 0,
                safeInsetsOverride = currentSafeInsets,
                metricsWindowManager = editorWindowManager,
            )
            updateToolbarActionWidth(safeViewport)
            selection.setDisplayCutoutInsets(
                currentDisplayCutoutInsets(root, editorWindowManager),
            )
            selection.setHandleTouchableInsets(currentSafeInsets.toRegionHandleTouchableInsets())
            refreshEditorTouchableRegion()
            val safeMargin = dp(8)
            toolbarParams.x = safeViewport.left + safeMargin
            toolbarParams.y = safeViewport.top + safeMargin
            updateToolbarWindowOnAnimation()
            updateFixedPanelLayout(currentEditorPanelSafeInsets())
            root.requestApplyInsets()
        }
        requestToolbarWidthUpdate = {
            updateToolbarActionWidth(
                previewViewport(
                    margin = 0,
                    safeInsetsOverride = currentOverlaySafeInsets(root, editorWindowManager),
                    metricsWindowManager = editorWindowManager,
                ),
            )
            updateToolbarWindowOnAnimation()
        }
        regionEditorLayoutUpdater = ::updateRegionEditorLayout
        root.setOnApplyWindowInsetsListener { _, insets ->
            rememberLegacyVisibleNavigationInsets(insets)
            val attachedSafeInsets = safeInsets(insets)
            val safeViewport = previewViewport(
                margin = 0,
                safeInsetsOverride = attachedSafeInsets,
                metricsWindowManager = editorWindowManager,
            )
            selection.setDisplayCutoutInsets(displayCutoutInsets(insets))
            selection.setHandleTouchableInsets(attachedSafeInsets.toRegionHandleTouchableInsets())
            refreshEditorTouchableRegion()
            val safeMargin = dp(8)
            updateToolbarActionWidth(safeViewport)
            toolbarParams.x = safeViewport.left + safeMargin
            toolbarParams.y = safeViewport.top + safeMargin
            updateToolbarWindowOnAnimation()
            updateFixedPanelLayout(panelSafeInsets(insets))
            insets
        }
        root.post {
            updateRegionEditorLayout()
        }
        fun installToolbarDrag(handle: View) {
            var dragDownX = 0f
            var dragDownY = 0f
            var dragOriginX = 0
            var dragOriginY = 0
            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragDownX = event.rawX
                        dragDownY = event.rawY
                        dragOriginX = toolbarParams.x
                        dragOriginY = toolbarParams.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val safeViewport = previewViewport(
                            margin = 0,
                            safeInsetsOverride = overlaySafeInsets(root),
                            metricsWindowManager = editorWindowManager,
                        )
                        val safeMargin = dp(8)
                        val proposedX = (dragOriginX + event.rawX - dragDownX).roundToInt()
                        val proposedY = (dragOriginY + event.rawY - dragDownY).roundToInt()
                        val (left, top) = constrainOverlayToolbarPosition(
                            proposedX = proposedX,
                            proposedY = proposedY,
                            handleLeft = handle.left,
                            handleTop = handle.top,
                            handleRight = handle.right,
                            handleBottom = handle.bottom,
                            viewportLeft = safeViewport.left + safeMargin,
                            viewportTop = safeViewport.top + safeMargin,
                            viewportRight = safeViewport.right - safeMargin,
                            viewportBottom = safeViewport.bottom - safeMargin,
                        )
                        toolbarParams.x = left
                        toolbarParams.y = top
                        updateToolbarWindowOnAnimation()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                    else -> false
                }
            }
        }
        installToolbarDrag(leftDragHandle)
        installToolbarDrag(rightDragHandle)

        suggestion?.let {
            val regionHint = overlayString(R.string.overlay_confirm_fixed_regions_and_path)
            val hint = label(
                overlayString(
                    R.string.overlay_suggestion_confidence,
                    (it.confidence * 100).roundToInt(),
                    regionHint,
                ),
                12f,
                Color.WHITE,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(7), dp(12), dp(7))
                val accent = accentColor()
                background = rounded(
                    Color.argb(232, Color.red(accent), Color.green(accent), Color.blue(accent)),
                    dp(8).toFloat(),
                )
                setTextColor(if (Color.luminance(accent) > 0.45f) Color.BLACK else Color.WHITE)
            }
            root.addView(hint, FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(18)
            })
            val fadeOut = Runnable {
                if (!hint.isAttachedToWindow) return@Runnable
                hint.animate()
                    .alpha(0f)
                    .setDuration(REGION_CONFIDENCE_FADE_DURATION_MS)
                    .withEndAction {
                        (hint.parent as? ViewGroup)?.removeView(hint)
                    }
                    .start()
            }
            hint.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    hint.removeCallbacks(fadeOut)
                    hint.animate().cancel()
                    hint.removeOnAttachStateChangeListener(this)
                }
            })
            hint.postDelayed(fadeOut, REGION_CONFIDENCE_VISIBLE_DURATION_MS)
        }

        val editorViewsAdded = buildList {
            add(
                addView(
                    root,
                    rootParams,
                    owningWindowManager = editorWindowManager,
                ),
            )
            add(
                addView(
                    touchProxy,
                    touchProxyParams,
                    owningWindowManager = editorWindowManager,
                ),
            )
            handleTouchProxies.forEach { proxy ->
                add(
                    addView(
                        proxy.view,
                        proxy.params,
                        owningWindowManager = editorWindowManager,
                    ),
                )
            }
            refreshEditorTouchableRegion()
            add(
                addView(
                    toolbar,
                    toolbarParams,
                    owningWindowManager = editorWindowManager,
                ),
            )
        }
        val allEditorViewsAttached = editorViewsAdded.all { it }
        if (!allEditorViewsAttached) {
            // A service can disconnect between host selection and the first addView call. The
            // accessibility token then becomes invalid and WindowManager rejects the whole
            // batch. Remove any partial batch before retrying once with the app-overlay host.
            removeAllNow(invalidateRequests = false, animate = false)
            if (shouldFallbackInteractiveOverlay(
                    attemptedWindowType = editorWindowType,
                    allViewsAttached = allEditorViewsAttached,
                    applicationOverlayAvailable = allowAccessibilityFallback && canDrawOverlays(),
                )
            ) {
                showRegionEditor(
                    template = template,
                    suggestion = suggestion,
                    overwriteExisting = overwriteExisting,
                    allowAccessibilityFallback = false,
                )
            } else {
                Log.e(
                    OVERLAY_CONTROLLER_LOG_TAG,
                    "Unable to attach the region editor overlay window",
                )
            }
            return@onMain
        }
        activeRegionEditorWindowType = editorWindowType
        activeRegionEditorOverwriteExisting = overwriteExisting
        callback.onExpandedOverlayShown()
    }

    fun showLearningStatus(
        sample: Int,
        total: Int,
        message: String,
        onDrawn: (() -> Unit)? = null,
    ): Unit = onMain {
        if (closed || !canDrawOverlays()) {
            return@onMain
        }
        refreshOverlayTheme()
        removeAllNow()
        val panel = verticalPanel().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(label(
                overlayString(R.string.overlay_region_learning_progress, sample, total),
                15f,
                getTextColor(true),
                true,
            ))
            addView(label(message, 12f, getTextColor(false)))
            addView(
                button(overlayString(R.string.overlay_stop)) { callback.onStopCapture() },
                matchMargins(top = 7),
            )
        }
        onDrawn?.let { callback ->
            panel.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                private var delivered = false

                override fun onDraw() {
                    if (delivered) return
                    delivered = true
                    // Release the worker on the next display frame so this draw has reached the
                    // renderer before a fast learning pass is allowed to remove the status.
                    panel.postOnAnimation(callback)
                    panel.post {
                        if (panel.viewTreeObserver.isAlive) {
                            panel.viewTreeObserver.removeOnDrawListener(this)
                        }
                    }
                }
            })
        }
        addView(panel, baseParams(windowWidth(270), -2, false).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(18)
            y = dp(18)
        })
    }

    /**
     * Keeps one lightweight full-screen touch target alive during the continuous recognition
     * gesture. The transparent area only catches the user's explicit early-finish tap; callers
     * receive the visible panel bounds so captured pixels can be excluded from motion analysis.
     */
    fun showRegionRecognitionCountdown(
        durationMs: Long,
        deadlineElapsedRealtimeMs: Long? = null,
        touchEnableDelayMs: Long = 0L,
        onFinishEarly: () -> Unit,
        onDrawn: (RectConfig) -> Unit,
    ): Unit = showRegionRecognitionCountdown(
        durationMs = durationMs,
        deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
        touchEnableDelayMs = touchEnableDelayMs,
        onFinishEarly = onFinishEarly,
        onDrawn = onDrawn,
        allowAccessibilityFallback = true,
    )

    private fun showRegionRecognitionCountdown(
        durationMs: Long,
        deadlineElapsedRealtimeMs: Long?,
        touchEnableDelayMs: Long,
        onFinishEarly: () -> Unit,
        onDrawn: (RectConfig) -> Unit,
        allowAccessibilityFallback: Boolean,
    ): Unit = onMain {
        if (closed) return@onMain
        refreshOverlayTheme()
        val countdownWindowHost = createInteractiveOverlayWindowHost(
            preferAccessibilityOverlay = allowAccessibilityFallback,
        ) ?: return@onMain
        val countdownContext = countdownWindowHost.context
        val countdownWindowManager = countdownWindowHost.windowManager
        val countdownWindowType = countdownWindowHost.windowType
        removeAllNow()
        val root = FrameLayout(countdownContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
        }
        val surface = themeColor(android.R.attr.colorBackgroundFloating, Color.WHITE)
        val panel = LinearLayout(countdownContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))
            background = rounded(
                Color.argb(190, Color.red(surface), Color.green(surface), Color.blue(surface)),
                dp(12).toFloat(),
            )
            elevation = dp(10).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        val title = label("", 22f, getTextColor(true), bold = true).apply {
            gravity = Gravity.CENTER
        }
        val subtitle = label(
            overlayString(R.string.overlay_region_recognition_tap_finish),
            15f,
            getTextColor(false),
        ).apply {
            gravity = Gravity.CENTER
        }
        val themeSettings = appPreferences.loadSettings()
        val progress = ComposeView(countdownContext).apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setContent {
                GameLongShotTheme(
                    themeMode = themeSettings.themeMode,
                    dynamicColor = themeSettings.dynamicColor,
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
            }
        }
        panel.addView(title, LinearLayout.LayoutParams(-2, -2))
        panel.addView(subtitle, matchMargins(top = 5))
        panel.addView(
            progress,
            LinearLayout.LayoutParams(-1, dp(4)).apply {
                topMargin = dp(12)
                marginStart = dp(16)
                marginEnd = dp(16)
            },
        )
        root.addView(
            panel,
            FrameLayout.LayoutParams(windowWidth(300), -2, Gravity.CENTER),
        )
        bindComposeLifecycle(root, progress, handleBack = false)

        val nowMs = SystemClock.elapsedRealtime()
        val safeDurationMs = durationMs.coerceAtLeast(1L)
        val deadlineMs = deadlineElapsedRealtimeMs?.coerceAtLeast(nowMs + 1L)
            ?: nowMs + safeDurationMs
        val initialRemainingMs = (deadlineMs - nowMs).coerceAtLeast(0L)
        title.text = overlayString(
            R.string.overlay_region_recognition_countdown,
            initialRemainingMs / 1_000f,
        )
        val updateCountdown = object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow) return
                val remainingMs = (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                title.text = overlayString(
                    R.string.overlay_region_recognition_countdown,
                    remainingMs / 1_000f,
                )
                if (remainingMs > 0L) {
                    root.postDelayed(this, minOf(100L, remainingMs))
                }
            }
        }
        var finishDelivered = false
        root.setOnClickListener {
            if (finishDelivered) return@setOnClickListener
            finishDelivered = true
            title.text = overlayString(R.string.overlay_region_recognition_finishing)
            subtitle.visibility = View.GONE
            onFinishEarly()
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                root.post(updateCountdown)
            }

            override fun onViewDetachedFromWindow(view: View) {
                root.removeCallbacks(updateCountdown)
                root.removeOnAttachStateChangeListener(this)
            }
        })
        root.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var delivered = false

            override fun onDraw() {
                if (delivered || panel.width <= 0 || panel.height <= 0) return
                delivered = true
                panel.postOnAnimation {
                    if (!panel.isAttachedToWindow) return@postOnAnimation
                    val location = IntArray(2)
                    panel.getLocationOnScreen(location)
                    val shadowMargin = dp(14)
                    val metrics = countdownContext.resources.displayMetrics
                    onDrawn(
                        RectConfig(
                            location[0] - shadowMargin,
                            location[1] - shadowMargin,
                            location[0] + panel.width + shadowMargin,
                            location[1] + panel.height + shadowMargin,
                        ).clampTo(metrics.widthPixels, metrics.heightPixels),
                    )
                    if (root.viewTreeObserver.isAlive) {
                        root.viewTreeObserver.removeOnDrawListener(this)
                    }
                }
            }
        })
        val initiallyNotTouchable = touchEnableDelayMs > 0L
        val params = baseParams(
            -1,
            -1,
            focusable = false,
            windowType = countdownWindowType,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (initiallyNotTouchable) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            allowLayoutInDisplayCutout()
        }
        val countdownAttached = addView(
            root,
            params,
            owningWindowManager = countdownWindowManager,
        )
        if (!countdownAttached) {
            removeAllNow(invalidateRequests = false, animate = false)
            if (shouldFallbackInteractiveOverlay(
                    attemptedWindowType = countdownWindowType,
                    allViewsAttached = false,
                    applicationOverlayAvailable = allowAccessibilityFallback && canDrawOverlays(),
                )
            ) {
                showRegionRecognitionCountdown(
                    durationMs = durationMs,
                    deadlineElapsedRealtimeMs = deadlineElapsedRealtimeMs,
                    touchEnableDelayMs = touchEnableDelayMs,
                    onFinishEarly = onFinishEarly,
                    onDrawn = onDrawn,
                    allowAccessibilityFallback = false,
                )
            } else {
                Log.e(
                    OVERLAY_CONTROLLER_LOG_TAG,
                    "Unable to attach the region recognition countdown overlay window",
                )
            }
            return@onMain
        }
        regionRecognitionOverlay = RegionRecognitionOverlay(
            root = root,
            title = title,
            subtitle = subtitle,
            progress = progress,
            countdownUpdater = updateCountdown,
            windowManager = countdownWindowManager,
        )
        if (initiallyNotTouchable) {
            root.postDelayed(
                {
                    if (!root.isAttachedToWindow || !root.isClickable) return@postDelayed
                    val enabledParams = root.layoutParams as? WindowManager.LayoutParams ?: return@postDelayed
                    enabledParams.flags = enabledParams.flags and
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    runCatching { countdownWindowManager.updateViewLayout(root, enabledParams) }
                },
                touchEnableDelayMs.coerceAtLeast(1L),
            )
        }
    }

    /** Keeps the centered recognition panel visible while the captured samples are analyzed. */
    fun showRegionRecognitionProcessing(passThroughTouches: Boolean = false): Unit = onMain {
        if (closed) return@onMain
        val recognition = regionRecognitionOverlay
            ?.takeIf { it.root.isAttachedToWindow }
            ?: return@onMain
        recognition.root.removeCallbacks(recognition.countdownUpdater)
        recognition.root.setOnClickListener(null)
        recognition.root.isClickable = false
        recognition.title.text = overlayString(R.string.overlay_region_recognition_processing)
        recognition.subtitle.visibility = View.GONE
        recognition.progress.visibility = View.VISIBLE
        if (passThroughTouches) {
            val params = recognition.root.layoutParams as? WindowManager.LayoutParams
                ?: return@onMain
            if (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching {
                    recognition.windowManager.updateViewLayout(recognition.root, params)
                }.onFailure { error ->
                    Log.w(
                        OVERLAY_CONTROLLER_LOG_TAG,
                        "Unable to make the recognition processing overlay touch-through",
                        error,
                    )
                }
            }
        }
    }

    /** Displayed only in the gap after a frame is processed. */
    fun showCaptureGap(
        info: CaptureGapInfo,
        onPresented: ((Boolean) -> Unit)? = null,
    ): Unit = onMain {
        if (closed || !canDrawOverlays() || !callback.canShowCaptureGap()) {
            onPresented?.invoke(false)
            return@onMain
        }
        refreshOverlayTheme()
        val viewportRect = captureGapViewport(margin = 0)
        val previewViewportRect = captureGapViewport(
            margin = dp(DefaultCaptureConfig.PREVIEW_EDGE_MARGIN_DP),
        )
        val viewport = EditorFloatRect(
            viewportRect.left.toFloat(),
            viewportRect.top.toFloat(),
            viewportRect.right.toFloat(),
            viewportRect.bottom.toFloat(),
        )
        val previewViewport = EditorFloatRect(
            previewViewportRect.left.toFloat(),
            previewViewportRect.top.toFloat(),
            previewViewportRect.right.toFloat(),
            previewViewportRect.bottom.toFloat(),
        )
        val controlBounds = captureGapControlBounds(
            viewport = viewport,
            preferredWidth = windowWidth(430).toFloat(),
            height = dp(DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP).toFloat(),
            bottomMargin = dp(DefaultCaptureConfig.PREVIEW_EDGE_MARGIN_DP).toFloat(),
            horizontalMargin = dp(DefaultCaptureConfig.PREVIEW_EDGE_MARGIN_DP).toFloat(),
            scrollDirection = info.previewScrollDirection,
        )
        val density = overlayContext.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val typography = captureGapBannerTypography(controlBounds.width / density)
        captureGapOverlay
            ?.takeIf { it.panel.isAttachedToWindow }
            ?.let { existing ->
                existing.info = info
                updateCaptureGapText(existing, info, typography)
                updateCaptureGapPreview(
                    overlay = existing,
                    info = info,
                    previewViewport = previewViewport,
                    controlBounds = controlBounds,
                    onDrawn = null,
                )
                scheduleCaptureGapPresentation(existing, onPresented)
                existing.panel.invalidate()
                return@onMain
            }

        removeAllNow()
        val titleView = label(
            captureGapTitle(info, typography.compactTitle),
            typography.titleTextSizeSp.toFloat(),
            getTextColor(true),
            true,
        ).apply {
            includeFontPadding = true
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            setPadding(0, 0, 0, 0)
            setAutoSizeTextTypeUniformWithConfiguration(
                typography.minimumTextSizeSp,
                typography.titleTextSizeSp,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }
        val qualityView = label(
            captureGapQuality(info),
            typography.qualityTextSizeSp.toFloat(),
            qualityColor(info.confidence),
        ).apply {
            includeFontPadding = true
            gravity = Gravity.CENTER_VERTICAL
            maxLines = typography.qualityMaxLines
            setHorizontallyScrolling(false)
            setPadding(0, 0, 0, 0)
            setAutoSizeTextTypeUniformWithConfiguration(
                typography.minimumTextSizeSp,
                typography.qualityTextSizeSp,
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }
        val panel = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP)
            setPadding(
                dp(typography.panelHorizontalPaddingDp),
                dp(typography.panelVerticalPaddingDp),
                dp(typography.panelHorizontalPaddingDp),
                dp(typography.panelVerticalPaddingDp),
            )
            val surface = themeColor(android.R.attr.colorBackgroundFloating, Color.WHITE)
            background = rounded(
                Color.argb(246, Color.red(surface), Color.green(surface), Color.blue(surface)),
                dp(8).toFloat(),
            )
            elevation = dp(8).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        panel.addView(LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(typography.statusEndPaddingDp), 0)
            addView(
                titleView,
                LinearLayout.LayoutParams(-1, 0, 1f),
            )
            addView(
                qualityView,
                LinearLayout.LayoutParams(
                    -1,
                    0,
                    if (typography.qualityMaxLines > 1) 1.6f else 1f,
                ),
            )
        }, LinearLayout.LayoutParams(0, -1, 1f))
        panel.addView(
            button(overlayString(R.string.overlay_stop), accent = true) {
                callback.onStopCapture()
            }.apply {
                textSize = typography.actionTextSizeSp.toFloat()
                minWidth = dp(typography.actionMinimumWidthDp)
                setPadding(
                    dp(typography.actionHorizontalPaddingDp),
                    dp(4),
                    dp(typography.actionHorizontalPaddingDp),
                    dp(4),
                )
            },
            LinearLayout.LayoutParams(-2, dp(40)).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        val panelAttached = addView(
            panel,
            baseParams(
                controlBounds.width.roundToInt().coerceAtLeast(1),
                controlBounds.height.roundToInt().coerceAtLeast(1),
                false,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = controlBounds.left.roundToInt()
                y = controlBounds.top.roundToInt()
                allowLayoutInDisplayCutout()
            },
        )
        if (!panelAttached) {
            onPresented?.invoke(false)
            return@onMain
        }
        val overlay = CaptureGapOverlay(
            panel = panel,
            title = titleView,
            quality = qualityView,
            info = info,
        )
        captureGapOverlay = overlay
        scheduleCaptureGapPresentation(overlay, onPresented)
        panel.invalidate()
        updateCaptureGapPreview(
            overlay = overlay,
            info = info,
            previewViewport = previewViewport,
            controlBounds = controlBounds,
            onDrawn = null,
        )
    }

    /** Persistent result action shown after capture cleanup when automatic opening is disabled. */
    fun showCaptureCompletion(info: CaptureCompletionInfo): Unit = onMain {
        if (closed ||
            !canDrawOverlays() ||
            !callback.canShowCaptureCompletion(info.resultSessionId)
        ) {
            return@onMain
        }
        refreshOverlayTheme()
        removeAllNow()
        if (!callback.canShowCaptureCompletion(info.resultSessionId)) return@onMain
        captureCompletionInfo = info

        val viewport = captureGapViewport(margin = 0).let { bounds ->
            EditorFloatRect(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
            )
        }
        val controlBounds = captureGapControlBounds(
            viewport = viewport,
            preferredWidth = windowWidth(430).toFloat(),
            height = dp(DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP).toFloat(),
            bottomMargin = dp(DefaultCaptureConfig.PREVIEW_EDGE_MARGIN_DP).toFloat(),
            horizontalMargin = dp(DefaultCaptureConfig.PREVIEW_EDGE_MARGIN_DP).toFloat(),
            scrollDirection = info.scrollDirection,
            reservePreviewSideSpace = false,
        )
        val density = overlayContext.resources.displayMetrics.density.coerceAtLeast(0.1f)
        val typography = captureGapBannerTypography(controlBounds.width / density)
        val panel = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(DefaultCaptureConfig.PREVIEW_REFERENCE_HEIGHT_DP)
            setPadding(
                dp(typography.panelHorizontalPaddingDp),
                dp(typography.panelVerticalPaddingDp),
                dp(typography.panelHorizontalPaddingDp),
                dp(typography.panelVerticalPaddingDp),
            )
            val surface = themeColor(android.R.attr.colorBackgroundFloating, Color.WHITE)
            background = rounded(
                Color.argb(246, Color.red(surface), Color.green(surface), Color.blue(surface)),
                dp(8).toFloat(),
            )
            elevation = dp(8).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        panel.addView(
            LinearLayout(overlayContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, dp(4), 0)
                addView(label(
                    overlayString(R.string.overlay_capture_completed),
                    typography.titleTextSizeSp.toFloat(),
                    getTextColor(true),
                    true,
                ))
                addView(
                    label(
                        overlayString(
                            R.string.overlay_frame_count_dimensions,
                            info.frameCount,
                            info.stitchedWidth,
                            info.stitchedHeight,
                        ),
                        typography.qualityTextSizeSp.toFloat(),
                        getTextColor(false),
                    ).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
            },
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        panel.addView(
            button(overlayString(R.string.overlay_confirm)) {
                callback.onCaptureCompletionConfirmed(info.resultSessionId)
            }.apply {
                textSize = typography.actionTextSizeSp.toFloat()
                setPadding(dp(8), dp(4), dp(8), dp(4))
            },
            LinearLayout.LayoutParams(-2, dp(40)).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        panel.addView(space(dp(4)))
        panel.addView(
            button(overlayString(R.string.overlay_open), accent = true) {
                callback.onCaptureCompletionOpen(info.resultSessionId)
            }.apply {
                textSize = typography.actionTextSizeSp.toFloat()
                setPadding(dp(8), dp(4), dp(8), dp(4))
            },
            LinearLayout.LayoutParams(-2, dp(40)).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
        )
        addView(
            panel,
            baseParams(
                controlBounds.width.roundToInt().coerceAtLeast(1),
                controlBounds.height.roundToInt().coerceAtLeast(1),
                false,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = controlBounds.left.roundToInt()
                y = controlBounds.top.roundToInt()
                allowLayoutInDisplayCutout()
            },
        )
    }

    private fun captureGapTitle(info: CaptureGapInfo, compact: Boolean): String =
        if (compact) {
            val displayedFrame = info.processingFrameNumber ?: info.frameCount
            overlayString(
                R.string.overlay_gap_compact_frame,
                displayedFrame,
                info.stitchedWidth,
                info.stitchedHeight,
            )
        } else if (info.processingFrameNumber != null) {
            overlayString(
                R.string.overlay_gap_processing_frame,
                info.processingFrameNumber,
                info.stitchedWidth,
                info.stitchedHeight,
            )
        } else {
            overlayString(
                R.string.overlay_gap_stitched_frames,
                info.frameCount,
                info.stitchedWidth,
                info.stitchedHeight,
            )
        }

    private fun captureGapQuality(info: CaptureGapInfo): String =
        overlayString(
            R.string.overlay_gap_quality,
            (info.confidence * 100).roundToInt(),
            info.message.ifBlank { overlayString(R.string.overlay_gap_default_message) },
        )

    private fun updateCaptureGapText(
        overlay: CaptureGapOverlay,
        info: CaptureGapInfo,
        typography: CaptureGapBannerTypography,
    ) {
        overlay.title.text = captureGapTitle(info, typography.compactTitle)
        overlay.quality.text = captureGapQuality(info)
        overlay.quality.setTextColor(qualityColor(info.confidence))
    }

    private fun updateCaptureGapPreview(
        overlay: CaptureGapOverlay,
        info: CaptureGapInfo,
        previewViewport: EditorFloatRect,
        controlBounds: EditorFloatRect,
        onDrawn: (() -> Unit)?,
    ) {
        val preview = info.preview?.takeUnless(Bitmap::isRecycled)
        val previewBounds = preview
            ?.let {
                captureGapPreviewBounds(
                    viewport = previewViewport,
                    controlBounds = controlBounds,
                    scrollDirection = info.previewScrollDirection,
                )
            }
            ?.let { maximumBounds ->
                captureGapPreviewContentBounds(
                    maximumBounds = maximumBounds,
                    contentWidth = info.previewContentWidth,
                    contentHeight = info.previewContentHeight,
                    scrollDirection = info.previewScrollDirection,
                )
            }
        if (preview == null || previewBounds == null) {
            overlay.previewImage?.setImageDrawable(null)
            overlay.previewFrame?.visibility = View.INVISIBLE
            runAfterNextDraw(overlay.panel, onDrawn)
            overlay.panel.invalidate()
            return
        }

        val frameWidth = previewBounds.width.roundToInt().coerceAtLeast(1)
        val frameHeight = previewBounds.height.roundToInt().coerceAtLeast(1)
        val existingFrame = overlay.previewFrame
        val existingImage = overlay.previewImage
        if (existingFrame != null && existingImage != null && existingFrame.isAttachedToWindow) {
            runAfterNextDraw(existingFrame, onDrawn)
            existingImage.setImageBitmap(preview)
            existingFrame.visibility = View.VISIBLE
            val params = (existingFrame.layoutParams as WindowManager.LayoutParams).apply {
                width = frameWidth
                height = frameHeight
                gravity = Gravity.TOP or Gravity.START
                x = previewBounds.left.roundToInt()
                y = previewBounds.top.roundToInt()
                allowLayoutInDisplayCutout()
            }
            runCatching { windowManager.updateViewLayout(existingFrame, params) }
            existingFrame.invalidate()
            return
        }

        val previewImage = ImageView(overlayContext).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(preview)
        }
        val previewFrame = FrameLayout(overlayContext).apply {
            addView(
                previewImage,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            foreground = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = 0f
                setStroke(dp(1), Color.WHITE)
            }
        }
        overlay.previewFrame = previewFrame
        overlay.previewImage = previewImage
        runAfterNextDraw(previewFrame, onDrawn)
        addView(
            previewFrame,
            baseParams(frameWidth, frameHeight, false).apply {
                gravity = Gravity.TOP or Gravity.START
                x = previewBounds.left.roundToInt()
                y = previewBounds.top.roundToInt()
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                allowLayoutInDisplayCutout()
            },
        )
    }

    private fun runAfterNextDraw(view: View, callback: (() -> Unit)?) {
        callback ?: return
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            private var delivered = false

            override fun onDraw() {
                if (delivered) return
                delivered = true
                view.postOnAnimation(callback)
                view.post {
                    if (view.viewTreeObserver.isAlive) {
                        view.viewTreeObserver.removeOnDrawListener(this)
                    }
                }
            }
        })
    }

    private fun scheduleCaptureGapPresentation(
        overlay: CaptureGapOverlay,
        callback: ((Boolean) -> Unit)?,
    ) {
        callback?.let(overlay.pendingPresentationCallbacks::add)
        if (overlay.pendingPresentationCallbacks.isEmpty() || overlay.presentationCallbackScheduled) {
            return
        }
        overlay.presentationCallbackScheduled = true
        runAfterNextDraw(overlay.panel) {
            completeCaptureGapPresentation(
                overlay = overlay,
                presented = captureGapOverlay === overlay && overlay.panel.isAttachedToWindow,
            )
        }
    }

    private fun completeCaptureGapPresentation(
        overlay: CaptureGapOverlay,
        presented: Boolean,
    ) {
        overlay.presentationCallbackScheduled = false
        val callbacks = overlay.pendingPresentationCallbacks.toList()
        overlay.pendingPresentationCallbacks.clear()
        callbacks.forEach { callback -> callback(presented) }
    }

    /** Completely removes surfaces; INVISIBLE is deliberately not used. */
    fun hideForCapture(
        preservePendingUiRequest: Boolean = false,
        onRemoved: (() -> Unit)? = null,
    ): Unit = onMain {
        if (!preservePendingUiRequest) callback.onExpandedOverlayDismissed()
        rememberLegacyVisibleNavigationInsetsFromAttachedOverlay()
        attachedViews.asSequence().mapNotNull(View::findFocus).firstOrNull()?.let { focused ->
            runCatching {
                overlayContext.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(focused.windowToken, 0)
            }
            focused.clearFocus()
        }
        removeAllNow(invalidateRequests = !preservePendingUiRequest)
        // Two main-loop turns allow SurfaceFlinger to consume the removal before
        // the capture coordinator starts its explicit clean-frame delay.
        mainHandler.post { mainHandler.post { onRemoved?.invoke() } }
    }

    /**
     * Keeps one passive application-overlay window attached while capture UI is hidden. Some
     * vendor process freezers ignore an otherwise valid foreground service when its notification
     * permission is denied, but continue to exempt a process that owns an overlay window.
     */
    fun attachCaptureKeepAlive(
        ownerToken: Long,
        onAttached: (Boolean) -> Unit,
    ): Unit = onMain {
        if (closed || !canDrawOverlays()) {
            onAttached(false)
            return@onMain
        }
        captureKeepAliveView
            ?.takeIf { it.isAttachedToWindow }
            ?.let {
                captureKeepAliveOwnerToken = ownerToken
                onAttached(true)
                return@onMain
            }
        removeCaptureKeepAliveNow()

        val anchor = View(overlayContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val params = baseParams(
            1,
            1,
            focusable = false,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = maximumPassThroughOverlayAlpha()
            flags = flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            setTitle("${appContext.packageName}:capture-keepalive")
        }
        val attached = runCatching {
            windowManager.addView(anchor, params)
            true
        }.onFailure { error ->
            Log.e(OVERLAY_CONTROLLER_LOG_TAG, "Unable to attach capture keepalive overlay", error)
        }.getOrDefault(false)
        if (attached) {
            captureKeepAliveView = anchor
            captureKeepAliveOwnerToken = ownerToken
        }
        onAttached(attached)
    }

    fun detachCaptureKeepAlive(ownerToken: Long): Unit = onMain {
        removeCaptureKeepAliveNow(ownerToken)
    }

    fun removeAll(): Unit = onMain {
        callback.onExpandedOverlayDismissed()
        removeAllNow()
    }

    /**
     * Requests the same draft-preserving collapse used by Back. System navigation is observed by
     * the service, but the controller owns the in-progress template shown by an expanded window.
     */
    fun collapseExpandedOverlay(): Unit = onMain {
        if (!closed) callback.onOverlayBackPressed(currentTemplate)
    }

    override fun close(): Unit = onMain {
        closed = true
        GameScrollAccessibilityService.removeConnectionListener(accessibilityConnectionListener)
        if (configurationCallbacksRegistered) {
            appContext.unregisterComponentCallbacks(configurationCallbacks)
            configurationCallbacksRegistered = false
        }
        removeCaptureKeepAliveNow()
        callback.onExpandedOverlayDismissed()
        removeAllNow()
    }

    @Suppress("DEPRECATION")
    private fun showTemplateNamePanel(
        template: GameCaptureTemplate,
        createNew: Boolean,
        requestedName: String? = null,
        onCancel: () -> Unit,
    ) {
        val requestGeneration = uiGeneration
        callback.onResolveTargetApplication { target ->
            onMain {
                if (closed || !canDrawOverlays() || requestGeneration != uiGeneration) return@onMain
                refreshOverlayTheme()
                callback.onExpandedOverlayShown()
                removeAllNow(animate = true)
                val savingAsNew = createNew || template.id == 0L
                val panel = verticalPanel().apply {
                    setPadding(dp(18), dp(16), dp(18), dp(14))
                    addView(label(
                        overlayString(
                            if (savingAsNew) {
                                R.string.overlay_save_new_template
                            } else {
                                R.string.overlay_overwrite_saved_template
                            },
                        ),
                        17f,
                        getTextColor(true),
                        true,
                    ))
                    addView(label(
                        overlayString(R.string.overlay_template_name),
                        12f,
                        getTextColor(false),
                    ))
                }
                val input = EditText(overlayContext).apply {
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    setText(
                        requestedName?.takeIf { it.isNotBlank() }
                            ?: template.templateName.takeIf {
                                !savingAsNew && it.isNotBlank()
                            }
                            ?: target?.appName?.takeIf { it.isNotBlank() && it != target.packageName }
                            ?: template.appName.takeIf {
                                it.isNotBlank() &&
                                    it != template.packageName &&
                                    (target == null || template.packageName == target.packageName)
                            }
                            ?: template.templateName.takeIf { it.isNotBlank() }
                            ?: overlayString(R.string.overlay_game_template),
                    )
                    setSelection(text.length)
                    hint = overlayString(R.string.overlay_enter_template_name)
                    textSize = 14f
                }
                panel.addView(input, matchMargins(top = 6))
                target?.let {
                    panel.addView(
                        label(
                            overlayString(R.string.overlay_current_application, it.displayName),
                            11f,
                            getTextColor(false),
                        ),
                        matchMargins(top = 2),
                    )
                    panel.addView(
                        label(
                            overlayString(R.string.overlay_package_name, it.packageName),
                            11f,
                            getTextColor(false),
                        ),
                        matchMargins(),
                    )
                }
                val errorLabel = label(
                    overlayString(
                        if (target == null) {
                            R.string.overlay_cannot_identify_current_app
                        } else {
                            R.string.overlay_enter_template_name
                        },
                    ),
                    11f,
                    errorColor(),
                ).apply {
                    visibility = if (target == null) View.VISIBLE else View.GONE
                }
                panel.addView(errorLabel)
                val actions = LinearLayout(overlayContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                }
                actions.addView(button(overlayString(R.string.overlay_cancel)) { onCancel() }, weighted())
                actions.addView(space(dp(8)))
                val saveButton = button(overlayString(
                    if (savingAsNew) R.string.overlay_save else R.string.overlay_overwrite,
                ), accent = true) { view ->
                    val cleanName = input.text?.toString()?.trim().orEmpty()
                    if (cleanName.isEmpty()) {
                        errorLabel.text = overlayString(R.string.overlay_enter_template_name)
                        errorLabel.visibility = View.VISIBLE
                        return@button
                    }
                    if (target == null) {
                        errorLabel.text = overlayString(R.string.overlay_cannot_identify_current_app)
                        errorLabel.visibility = View.VISIBLE
                        return@button
                    }
                    errorLabel.visibility = View.GONE
                    val now = System.currentTimeMillis()
                    val candidate = if (createNew) {
                        template.copy(
                            id = 0L,
                            templateName = cleanName,
                            createdAt = now,
                            updatedAt = now,
                        )
                    } else template.copy(templateName = cleanName)
                    submitTemplateSave(
                        candidate = candidate,
                        name = cleanName,
                        confirmation = null,
                        trigger = view,
                        onCancel = onCancel,
                    )
                }.apply { isEnabled = target != null }
                actions.addView(saveButton, weighted())
                panel.addView(actions, matchMargins(top = 8))
                val params = baseParams(windowWidth(320), -2, focusable = true).apply {
                    gravity = Gravity.CENTER
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                }
                addView(panel, params)
                input.requestFocus()
            }
        }
    }

    private fun submitTemplateSave(
        candidate: GameCaptureTemplate,
        name: String,
        confirmation: CrossAppOverwriteConfirmation?,
        trigger: View,
        onCancel: () -> Unit,
    ) {
        if (closed || !canDrawOverlays()) return
        val requestGeneration = uiGeneration
        trigger.isEnabled = false
        (trigger as? Button)?.text = overlayString(R.string.overlay_saving)
        callback.onSaveTemplate(candidate, name, confirmation) { result ->
            onMain {
                if (closed || !canDrawOverlays() || requestGeneration != uiGeneration) return@onMain
                when (result) {
                    is TemplateSaveResult.Saved -> {
                        val saved = result.template
                        isExplicitNewTemplateDraft = false
                        currentTemplate = saved
                        val refreshed = (availableTemplates.filterNot { it.id == saved.id } + saved)
                            .sortedByDescending { it.updatedAt }
                        showConfiguration(saved, refreshed)
                    }
                    is TemplateSaveResult.ConfirmationRequired -> {
                        showCrossAppOverwriteConfirmation(
                            confirmation = result.confirmation,
                            onBack = {
                                showTemplateNamePanel(
                                    template = candidate,
                                    createNew = false,
                                    requestedName = name,
                                    onCancel = onCancel,
                                )
                            },
                            onConfirm = { confirmView ->
                                submitTemplateSave(
                                    candidate = candidate,
                                    name = name,
                                    confirmation = result.confirmation,
                                    trigger = confirmView,
                                    onCancel = onCancel,
                                )
                            },
                        )
                    }
                    is TemplateSaveResult.Rejected -> {
                        showTemplateSaveError(result.message) {
                            showTemplateNamePanel(
                                template = candidate,
                                createNew = candidate.id == 0L,
                                requestedName = name,
                                onCancel = onCancel,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showCrossAppOverwriteConfirmation(
        confirmation: CrossAppOverwriteConfirmation,
        onBack: () -> Unit,
        onConfirm: (View) -> Unit,
    ) {
        if (closed || !canDrawOverlays()) return
        refreshOverlayTheme()
        removeAllNow(animate = true)
        val panel = verticalPanel().apply {
            setPadding(dp(18), dp(16), dp(18), dp(14))
            addView(label(
                overlayString(R.string.overlay_overwrite_other_app_title),
                17f,
                getTextColor(true),
                true,
            ))
            addView(
                label(
                    overlayString(
                        R.string.overlay_overwrite_other_app_message,
                        confirmation.sourceAppName,
                        confirmation.targetAppName,
                    ),
                    13f,
                    getTextColor(false),
                ),
                matchMargins(top = 8),
            )
            addView(LinearLayout(overlayContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                addView(button(overlayString(R.string.overlay_back_to_edit)) { onBack() }, weighted())
                addView(space(dp(8)))
                addView(
                    button(
                        overlayString(R.string.overlay_overwrite_anyway),
                        accent = true,
                        click = onConfirm,
                    ),
                    weighted(),
                )
            }, matchMargins(top = 12))
        }
        addView(panel, baseParams(windowWidth(340), -2, focusable = true).apply { gravity = Gravity.CENTER })
    }

    private fun showTemplateSaveError(message: String, onDismiss: () -> Unit) {
        if (closed || !canDrawOverlays()) return
        refreshOverlayTheme()
        removeAllNow(animate = true)
        val panel = verticalPanel().apply {
            setPadding(dp(18), dp(16), dp(18), dp(14))
            addView(label(
                overlayString(R.string.overlay_template_save_failed),
                17f,
                getTextColor(true),
                true,
            ))
            addView(label(message, 13f, errorColor()), matchMargins(top = 8))
            addView(
                button(overlayString(R.string.overlay_back), accent = true) { onDismiss() },
                matchMargins(top = 12),
            )
        }
        addView(panel, baseParams(windowWidth(320), -2, focusable = true).apply { gravity = Gravity.CENTER })
    }

    private fun addView(
        view: View,
        params: WindowManager.LayoutParams,
        animateHorizontally: Boolean = false,
        owningWindowManager: WindowManager = windowManager,
    ): Boolean {
        return runCatching {
            val animateIn = animateHorizontally && view.visibility == View.VISIBLE && view.alpha > 0f
            val targetAlpha = view.alpha
            if (animateIn) {
                view.translationX = edgeTransitionOffset(view, params)
                view.alpha = 0f
                horizontallyAnimatedViews += view
            }
            owningWindowManager.addView(view, params)
            attachedViews += view
            attachedViewWindowManagers[view] = owningWindowManager
            composeLifecycleOwners[view]?.start()
            installBackKeyHandler(view, params)
            if (animateIn) {
                view.post {
                    if (!attachedViews.contains(view) || !view.isAttachedToWindow) return@post
                    view.animate().cancel()
                    view.animate()
                        .translationX(0f)
                        .alpha(targetAlpha)
                        .setDuration(OVERLAY_TRANSITION_DURATION_MS)
                        .start()
                    }
            }
            true
        }.onFailure { error ->
            Log.e(
                OVERLAY_CONTROLLER_LOG_TAG,
                "Unable to add overlay view=${view.javaClass.simpleName} " +
                    "size=${params.width}x${params.height} flags=${params.flags}",
                error,
            )
        }.getOrDefault(false)
    }

    private fun installBackKeyHandler(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) return
        registerBackInvokedCallback(view)
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP) callback.onOverlayBackPressed(currentTemplate)
            true
        }
        // A full-screen editor root and its toolbar are focusable windows. Requesting focus for
        // those roots makes Back reliable even when the accessibility service is disabled; text
        // input panels keep their EditText focus and still bubble the event to this listener.
        if (!containsEditableDescendant(view)) {
            view.isFocusableInTouchMode = true
            view.post {
                if (attachedViews.contains(view) && view.isAttachedToWindow && !view.hasFocus()) {
                    view.requestFocus()
                }
            }
        }
    }

    private fun installOutsideTouchHandler(
        view: View,
        onOutsideTouch: (MotionEvent) -> Unit,
    ) {
        view.setOnTouchListener { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) {
                false
            } else {
                onOutsideTouch(event)
                true
            }
        }
    }

    private fun requestOutsideTouchAction(
        event: MotionEvent,
        action: () -> Unit,
    ) {
        val requestGeneration = uiGeneration
        val contentViewport = previewViewport(0)
        callback.onOverlayOutsideTouched(
            rawX = event.rawX,
            rawY = event.rawY,
            touchesSystemBar = outsideTouchHitsProtectedInsets(
                rawX = event.rawX,
                rawY = event.rawY,
                contentLeft = contentViewport.left,
                contentTop = contentViewport.top,
                contentRight = contentViewport.right,
                contentBottom = contentViewport.bottom,
                guardPx = dp(8),
            ),
        ) {
            onMain {
                if (!closed && requestGeneration == uiGeneration) action()
            }
        }
    }

    /**
     * Overlay windows do not have an Activity to bridge Android's predictive
     * Back callback into Compose. Register on the actual window dispatcher so
     * Back works in Shizuku mode too, where the accessibility navigation-key
     * listener is intentionally not enabled.
     */
    @Suppress("NewApi")
    private fun registerBackInvokedCallback(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backInvokedRegistrations.remove(view)?.let { previous ->
            runCatching { previous.dispatcher.unregisterOnBackInvokedCallback(previous.callback) }
        }
        val dispatcher = runCatching { view.findOnBackInvokedDispatcher() }.getOrNull() ?: return
        val invoked = object : OnBackInvokedCallback {
            override fun onBackInvoked() {
                // Prefer the Compose dispatcher when this root owns one. This
                // preserves any screen-local BackHandler while retaining the
                // service callback as a fallback for ordinary View windows.
                val composeDispatcher = composeLifecycleOwners[view]?.onBackPressedDispatcher
                if (composeDispatcher?.hasEnabledCallbacks() == true) {
                    composeDispatcher.onBackPressed()
                } else {
                    callback.onOverlayBackPressed(currentTemplate)
                }
            }
        }
        runCatching {
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                invoked,
            )
            backInvokedRegistrations[view] = BackInvokedRegistration(dispatcher, invoked)
        }
    }

    @Suppress("NewApi")
    private fun unregisterBackInvokedCallback(view: View) {
        val registration = backInvokedRegistrations.remove(view) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            registration.dispatcher.unregisterOnBackInvokedCallback(registration.callback)
        }
    }

    private fun containsEditableDescendant(view: View): Boolean {
        if (view is EditText) return true
        if (view !is ViewGroup) return false
        repeat(view.childCount) { index ->
            if (containsEditableDescendant(view.getChildAt(index))) return true
        }
        return false
    }

    private fun edgeTransitionOffset(
        view: View,
        params: WindowManager.LayoutParams,
    ): Float {
        val width = view.width.takeIf { it > 0 }
            ?: params.width.takeIf { it > 0 }
            ?: if (params.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                overlayContext.resources.displayMetrics.widthPixels
            } else {
                windowWidth(320)
            }
        val absoluteGravity = Gravity.getAbsoluteGravity(
            params.gravity,
            view.layoutDirection,
        )
        return when (absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK) {
            Gravity.RIGHT, Gravity.CENTER_HORIZONTAL -> width.toFloat()
            else -> -width.toFloat()
        }
    }

    private fun bindComposeLifecycle(
        root: View,
        composeView: ComposeView,
        handleBack: Boolean = true,
    ) {
        val owner = OverlayComposeLifecycleOwner()
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        root.setViewTreeOnBackPressedDispatcherOwner(owner)
        if (handleBack) {
            owner.onBackPressedDispatcher.addCallback(
                owner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        callback.onOverlayBackPressed(currentTemplate)
                    }
                },
            )
        }
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeLifecycleOwners[root] = owner
    }

    private fun removeAllNow(
        invalidateRequests: Boolean = true,
        animate: Boolean = false,
    ) {
        if (invalidateRequests) uiGeneration += 1L
        captureGapOverlay?.let { completeCaptureGapPresentation(it, presented = false) }
        captureGapOverlay = null
        captureCompletionInfo = null
        regionRecognitionOverlay = null
        regionEditorLayoutUpdater = null
        activeRegionEditorWindowType = null
        activeRegionEditorOverwriteExisting = null
        configurationPanel?.dismissDropdowns()
        configurationPanel = null
        configurationDismissLayer = null
        val views = attachedViews.toList()
        val animatedViews = if (animate) views.filter(horizontallyAnimatedViews::contains) else emptyList()
        if (animatedViews.isNotEmpty()) {
            removeViewsNow(views - animatedViews.toSet())
            animatedViews.forEach { view ->
                if (!view.isAttachedToWindow) return@forEach
                val params = view.layoutParams as? WindowManager.LayoutParams
                val offset = params?.let { edgeTransitionOffset(view, it) }
                    ?: -view.width.toFloat().coerceAtLeast(windowWidth(320).toFloat())
                view.animate().cancel()
                view.animate()
                    .translationX(offset)
                    .alpha(0f)
                    .setDuration(OVERLAY_TRANSITION_DURATION_MS)
                    .start()
            }
            mainHandler.postDelayed(
                { removeViewsNow(animatedViews) },
                OVERLAY_TRANSITION_DURATION_MS,
            )
            return
        }
        removeViewsNow(views)
    }

    private fun removeViewsNow(views: Collection<View>) {
        views.forEach { view ->
            val wasAttached = attachedViews.remove(view)
            val owningWindowManager = attachedViewWindowManagers.remove(view) ?: windowManager
            horizontallyAnimatedViews.remove(view)
            if (entryBubble === view) entryBubble = null
            unregisterBackInvokedCallback(view)
            if (!wasAttached) return@forEach
            view.animate().cancel()
            composeLifecycleOwners.remove(view)?.destroy()
            runCatching { owningWindowManager.removeViewImmediate(view) }
        }
        if (attachedViews.isEmpty()) {
            // Defensive cleanup for a secondary panel that may have been detached by its own
            // animation callback rather than through removeViewsNow.
            backInvokedRegistrations.keys.toList().forEach(::unregisterBackInvokedCallback)
            composeLifecycleOwners.values.forEach(OverlayComposeLifecycleOwner::destroy)
            composeLifecycleOwners.clear()
            attachedViewWindowManagers.clear()
        }
    }

    private fun removeCaptureKeepAliveNow(expectedOwnerToken: Long? = null) {
        if (expectedOwnerToken != null && captureKeepAliveOwnerToken != expectedOwnerToken) return
        val anchor = captureKeepAliveView
        captureKeepAliveView = null
        captureKeepAliveOwnerToken = null
        anchor?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { error ->
                    Log.w(OVERLAY_CONTROLLER_LOG_TAG, "Unable to remove capture keepalive overlay", error)
                }
        }
    }

    private fun baseParams(
        width: Int,
        height: Int,
        focusable: Boolean,
        windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    ) =
        WindowManager.LayoutParams(
            width,
            height,
            windowType,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        )

    /**
     * A non-touchable application-overlay window still counts as an obscuring window. Keep the
     * full-screen editor below Android's touch-obscuring threshold while pass-through is active;
     * the small handle proxy windows remain touchable and opaque only where editing is intended.
     */
    @Suppress("NewApi")
    private fun maximumPassThroughOverlayAlpha(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        val maximum = runCatching {
            appContext.getSystemService(InputManager::class.java)
                ?.maximumObscuringOpacityForTouch
        }.getOrNull() ?: FALLBACK_MAXIMUM_OBSCURING_OPACITY
        return (maximum - PASS_THROUGH_OPACITY_SAFETY_MARGIN).coerceIn(0f, 1f)
    }

    private fun WindowManager.LayoutParams.allowLayoutInDisplayCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun verticalPanel() = LinearLayout(overlayContext).apply {
        orientation = LinearLayout.VERTICAL
        val surface = themeColor(android.R.attr.colorBackgroundFloating, Color.WHITE)
        background = rounded(Color.argb(246, Color.red(surface), Color.green(surface), Color.blue(surface)), dp(8).toFloat())
        elevation = dp(8).toFloat()
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
    }

    private fun getTextColor(primary: Boolean = true): Int = themeColor(
        if (primary) android.R.attr.textColorPrimary else android.R.attr.textColorSecondary,
        if (primary) Color.BLACK else Color.DKGRAY,
    )

    private fun label(textValue: String, size: Float, color: Int? = null, bold: Boolean = false) =
        TextView(overlayContext).apply {
            text = textValue
            textSize = size
            setTextColor(color ?: getTextColor(size >= 14f))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(textValue: String, accent: Boolean = false, click: (View) -> Unit) =
        Button(overlayContext).apply {
            text = textValue
            textSize = 12f
            isAllCaps = false
            minHeight = dp(40)
            maxHeight = dp(40)
            minWidth = dp(48)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            if (accent) {
                val accentColor = accentColor()
                backgroundTintList = ColorStateList.valueOf(accentColor)
                setTextColor(if (Color.luminance(accentColor) > 0.45f) Color.BLACK else Color.WHITE)
            }
            setOnClickListener(click)
        }

    private fun toolbarButton(
        textValue: String,
        accent: Boolean = false,
        contentDescription: String? = null,
        click: (View) -> Unit,
    ) = button(textValue, accent, click).apply {
        textSize = 10f
        minHeight = dp(40)
        minWidth = dp(34)
        setPadding(dp(5), dp(2), dp(5), dp(2))
        contentDescription?.let { this.contentDescription = it }
    }

    private fun rounded(
        color: Int,
        radius: Float,
        strokeColor: Int? = null,
        strokeWidth: Int = 0,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeColor != null && strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)
    private fun space(width: Int) = Space(overlayContext).apply { layoutParams = LinearLayout.LayoutParams(width, 1) }
    private fun matchMargins(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * overlayContext.resources.displayMetrics.density).roundToInt()
    private fun windowWidth(preferredDp: Int): Int = minOf(
        dp(preferredDp),
        (overlayContext.resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(1),
    )

    private fun configurationPanelBounds(
        safeInsetsOverride: Rect? = null,
        metricsWindowManager: WindowManager = windowManager,
    ): Rect {
        val panelInsets = safeInsetsOverride ?: currentPanelSafeInsets()
        val fullViewport = previewViewport(
            margin = 0,
            safeInsetsOverride = panelInsets,
            metricsWindowManager = metricsWindowManager,
        )
        val portrait = fullViewport.height() > fullViewport.width()
        val margin = dp(if (portrait) 12 else 18)
        val viewport = previewViewport(
            margin = margin,
            safeInsetsOverride = panelInsets,
            metricsWindowManager = metricsWindowManager,
        )
        val panelRight = minOf(
            dp(if (portrait) 328 else 350) + panelInsets.left,
            viewport.right.coerceAtLeast(1),
        )
        return Rect(
            0,
            viewport.top,
            panelRight,
            viewport.bottom,
        )
    }

    private fun fixedSettingsPanelBounds(
        safeInsetsOverride: Rect? = null,
        metricsWindowManager: WindowManager = windowManager,
    ): Rect = configurationPanelBounds(safeInsetsOverride, metricsWindowManager)

    private fun configurationPanelLayoutParams(safeInsetsOverride: Rect? = null): WindowManager.LayoutParams {
        val bounds = configurationPanelBounds(safeInsetsOverride)
        return baseParams(bounds.width(), bounds.height(), true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            allowLayoutInDisplayCutout()
        }
    }

    private fun updateConfigurationPanelLayout(
        panel: ConfigurationPanel,
        safeInsetsOverride: Rect? = null,
    ) {
        if (!panel.view.isAttachedToWindow) return
        val panelInsets = safeInsetsOverride ?: currentPanelSafeInsets()
        panel.updateStartContentInset(panelInsets.left)
        panel.updatePreviewOcclusion(configurationPanelBounds(panelInsets))
        panel.updatePreviewInsets()
        val updated = configurationPanelLayoutParams(panelInsets)
        val current = panel.view.layoutParams as? WindowManager.LayoutParams
        if (
            current != null &&
            current.width == updated.width &&
            current.height == updated.height &&
            current.gravity == updated.gravity &&
            current.x == updated.x &&
            current.y == updated.y
        ) return
        runCatching { windowManager.updateViewLayout(panel.view, updated) }
    }

    private fun updateConfigurationDismissLayerLayout() {
        val layer = configurationDismissLayer?.takeIf { it.isAttachedToWindow } ?: return
        val bounds = captureGapViewport(margin = 0)
        val current = layer.layoutParams as? WindowManager.LayoutParams ?: return
        if (current.width == bounds.width() && current.height == bounds.height() &&
            current.x == bounds.left && current.y == bounds.top
        ) return
        current.width = bounds.width()
        current.height = bounds.height()
        current.x = bounds.left
        current.y = bounds.top
        runCatching { windowManager.updateViewLayout(layer, current) }
    }

    private fun updateEntryBubblePosition() {
        val bubble = entryBubble?.takeIf { it.isAttachedToWindow } ?: return
        val params = bubble.layoutParams as? WindowManager.LayoutParams ?: return
        val viewport = previewViewport(0)
        val clamped = clampOverlayTopStartPosition(
            viewportLeft = viewport.left,
            viewportTop = viewport.top,
            viewportRight = viewport.right,
            viewportBottom = viewport.bottom,
            viewWidth = bubble.width.takeIf { it > 0 } ?: dp(88),
            viewHeight = bubble.height.takeIf { it > 0 } ?: dp(40),
            x = params.x,
            y = params.y,
        )
        if (params.x == clamped.first && params.y == clamped.second) return
        params.x = clamped.first
        params.y = clamped.second
        entryPosition[0] = params.x
        entryPosition[1] = params.y
        runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    @Suppress("DEPRECATION")
    private fun captureGapViewport(margin: Int): Rect {
        val displayWidth: Int
        val displayHeight: Int
        val cutoutInsets: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            displayWidth = metrics.bounds.width()
            displayHeight = metrics.bounds.height()
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.displayCutout(),
            )
            cutoutInsets = Rect(insets.left, insets.top, insets.right, insets.bottom)
        } else {
            val display = windowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            val cutout = display.cutout
            cutoutInsets = Rect(
                cutout?.safeInsetLeft ?: 0,
                cutout?.safeInsetTop ?: 0,
                cutout?.safeInsetRight ?: 0,
                cutout?.safeInsetBottom ?: 0,
            )
        }
        val safeMargin = margin.coerceAtLeast(0)
        val left = (cutoutInsets.left + safeMargin).coerceAtMost(displayWidth - 1)
        val top = (cutoutInsets.top + safeMargin).coerceAtMost(displayHeight - 1)
        val right = (displayWidth - cutoutInsets.right - safeMargin).coerceAtLeast(left + 1)
        val bottom = (displayHeight - cutoutInsets.bottom - safeMargin).coerceAtLeast(top + 1)
        return Rect(left, top, right, bottom)
    }

    @Suppress("DEPRECATION")
    private fun previewViewport(
        margin: Int,
        safeInsetsOverride: Rect? = null,
        metricsWindowManager: WindowManager = windowManager,
    ): Rect {
        val displayWidth: Int
        val displayHeight: Int
        val safeInsets: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = metricsWindowManager.currentWindowMetrics
            displayWidth = metrics.bounds.width()
            displayHeight = metrics.bounds.height()
            safeInsets = safeInsetsOverride ?: run {
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                Rect(insets.left, insets.top, insets.right, insets.bottom)
            }
        } else {
            val display = metricsWindowManager.defaultDisplay
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
            val attachedInsets = safeInsetsOverride
                ?: configurationPanel?.view?.rootWindowInsets?.let(::safeInsets)
                ?: attachedViews.asSequence()
                    .mapNotNull { it.rootWindowInsets }
                    .firstOrNull()
                    ?.let(::safeInsets)
            safeInsets = attachedInsets ?: legacyDisplaySafeInsets(display, displayWidth, displayHeight)
        }
        val left = (safeInsets.left + margin).coerceAtMost(displayWidth - 1)
        val top = (safeInsets.top + margin).coerceAtMost(displayHeight - 1)
        val right = (displayWidth - safeInsets.right - margin).coerceAtLeast(left + 1)
        val bottom = (displayHeight - safeInsets.bottom - margin).coerceAtLeast(top + 1)
        return Rect(left, top, right, bottom)
    }

    @Suppress("DEPRECATION")
    private fun currentPanelSafeInsets(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return panelSafeInsets(windowManager.currentWindowMetrics.windowInsets)
        }
        return configurationPanel?.view?.rootWindowInsets?.let(::panelSafeInsets)
            ?: attachedViews.asSequence()
                .mapNotNull { it.rootWindowInsets }
                .firstOrNull()
                ?.let(::panelSafeInsets)
            ?: Rect()
    }

    /**
     * Returns the physical-display area that is not currently occupied by a *visible* navigation
     * bar. This is sampled only after capture overlays have been removed, so an immersive target
     * keeps the full display while a conventional app with three-button navigation loses only the
     * live navigation-bar strip.
     */
    @Suppress("DEPRECATION")
    fun currentVisibleNavigationContentBounds(
        screenWidth: Int,
        screenHeight: Int,
    ): RectConfig {
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val navigationInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets.getInsets(
                WindowInsets.Type.navigationBars(),
            )
            LegacyVisibleNavigationInsets(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom,
            )
        } else {
            // On Android 10 the attached overlay receives the live (visibility-aware) legacy
            // system-window insets. Stable insets would incorrectly crop immersive targets, so
            // retain the latest attached value before removing the overlay and leave the
            // status-bar edge alone.
            val rootInsets = configurationPanel?.view?.rootWindowInsets
                ?: attachedViews.asSequence()
                    .mapNotNull { it.rootWindowInsets }
                    .firstOrNull()
            rootInsets
                ?.let(::legacyVisibleNavigationInsets)
                ?.also { liveInsets ->
                    legacyVisibleNavigationInsetsCache.remember(width, height, liveInsets)
                }
                ?: legacyVisibleNavigationInsetsCache.resolve(width, height)
        }
        return navigationContentBoundsFromVisibleInsets(
            screenWidth = width,
            screenHeight = height,
            insetLeft = navigationInsets.left,
            insetTop = navigationInsets.top,
            insetRight = navigationInsets.right,
            insetBottom = navigationInsets.bottom,
        )
    }

    @Suppress("DEPRECATION")
    private fun rememberLegacyVisibleNavigationInsetsFromAttachedOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        val rootInsets = configurationPanel?.view?.rootWindowInsets
            ?: attachedViews.asSequence()
                .mapNotNull { it.rootWindowInsets }
                .firstOrNull()
            ?: return
        rememberLegacyVisibleNavigationInsets(rootInsets)
    }

    @Suppress("DEPRECATION")
    private fun rememberLegacyVisibleNavigationInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val visibleNavigation = legacyVisibleNavigationInsets(insets) ?: return
        legacyVisibleNavigationInsetsCache.remember(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            insets = visibleNavigation,
        )
    }

    @Suppress("DEPRECATION")
    private fun legacyVisibleNavigationInsets(insets: WindowInsets) =
        legacyVisibleNavigationInsetsWithoutIme(
            systemLeft = insets.systemWindowInsetLeft,
            systemRight = insets.systemWindowInsetRight,
            systemBottom = insets.systemWindowInsetBottom,
            stableBottom = insets.stableInsetBottom,
        )

    @Suppress("DEPRECATION")
    private fun legacyDisplaySafeInsets(display: Display, width: Int, height: Int): Rect {
        val cutout = display.cutout
        val result = Rect(
            cutout?.safeInsetLeft ?: 0,
            cutout?.safeInsetTop ?: 0,
            cutout?.safeInsetRight ?: 0,
            cutout?.safeInsetBottom ?: 0,
        )
        result.top = maxOf(result.top, systemDimension("status_bar_height"))

        val resources = appContext.resources
        val showNavigationBarId = resources.getIdentifier("config_showNavigationBar", "bool", "android")
        val hasNavigationBar = showNavigationBarId == 0 || runCatching {
            resources.getBoolean(showNavigationBarId)
        }.getOrDefault(true)
        if (!hasNavigationBar) return result

        val canMoveId = resources.getIdentifier("config_navBarCanMove", "bool", "android")
        val canMove = canMoveId != 0 && runCatching { resources.getBoolean(canMoveId) }.getOrDefault(false)
        if (width > height && canMove) {
            val navigationWidth = systemDimension("navigation_bar_width")
            if (display.rotation == Surface.ROTATION_270) {
                result.left = maxOf(result.left, navigationWidth)
            } else {
                result.right = maxOf(result.right, navigationWidth)
            }
        } else {
            result.bottom = maxOf(result.bottom, systemDimension("navigation_bar_height"))
        }
        return result
    }

    private fun systemDimension(name: String): Int {
        val resources = appContext.resources
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id == 0) 0 else runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0)
    }

    private fun accentColor(): Int = themeColor(android.R.attr.colorAccent, 0xFF006A5D.toInt())

    private fun errorColor(): Int = themeColor(android.R.attr.colorError, 0xFFBA1A1A.toInt())

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!overlayContext.theme.resolveAttribute(attribute, value, true)) return fallback
        if (value.resourceId != 0) {
            return runCatching {
                overlayContext.resources.getColorStateList(value.resourceId, overlayContext.theme).defaultColor
            }.getOrElse { fallback }
        }
        return if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
            value.data
        } else {
            fallback
        }
    }

    private fun resolveOverlayDarkTheme(): Boolean = useDarkOverlayTheme(
        themeMode = appPreferences.loadThemeMode(),
        systemUiMode = appContext.resources.configuration.uiMode,
    )

    private fun refreshOverlayTheme(): Boolean {
        val useDarkTheme = resolveOverlayDarkTheme()
        val language = appPreferences.loadAppLanguage()
        if (useDarkTheme == overlayDarkTheme && language == overlayLanguage) return false
        overlayDarkTheme = useDarkTheme
        overlayLanguage = language
        overlayContext = createOverlayThemeContext(windowContext, useDarkTheme, language)
        return true
    }

    private fun createOverlayThemeContext(
        base: Context,
        darkTheme: Boolean,
        language: AppLanguage,
    ): Context {
        val configuration = Configuration(base.resources.configuration).apply {
            val locale = AppLocaleManager.locale(language)
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
            val nightMode = if (darkTheme) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
        val configuredContext = base.createConfigurationContext(configuration)
        return ContextThemeWrapper(configuredContext, R.style.Theme_GameLongShot)
    }

    private fun overlayString(@StringRes resourceId: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) {
            overlayContext.getString(resourceId)
        } else {
            overlayContext.getString(resourceId, *formatArgs)
        }

    private fun createOverlayWindowContext(base: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return base
        val displayManager = base.getSystemService(DisplayManager::class.java)
        @Suppress("DEPRECATION")
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return base
        val displayContext = base.createDisplayContext(defaultDisplay)
        return displayContext.createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            null,
        )
    }

    @Suppress("DEPRECATION")
    private fun overlaySafeInsets(view: View): Rect {
        val insets = view.rootWindowInsets ?: return Rect()
        return safeInsets(insets)
    }

    private fun currentOverlaySafeInsets(
        view: View,
        metricsWindowManager: WindowManager = windowManager,
    ): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            safeInsets(metricsWindowManager.currentWindowMetrics.windowInsets)
        } else {
            overlaySafeInsets(view)
        }

    @Suppress("DEPRECATION")
    private fun safeInsets(insets: WindowInsets): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            Rect(safe.left, safe.top, safe.right, safe.bottom)
        } else {
            Rect(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom,
            )
        }

    @Suppress("DEPRECATION")
    private fun displayCutoutInsets(insets: WindowInsets): RegionCutoutInsets =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            RegionCutoutInsets(
                left = cutout.left,
                top = cutout.top,
                right = cutout.right,
                bottom = cutout.bottom,
                boundingRects = insets.displayCutout?.boundingRects
                    ?.map { bounds ->
                        EditorFloatRect(
                            bounds.left.toFloat(),
                            bounds.top.toFloat(),
                            bounds.right.toFloat(),
                            bounds.bottom.toFloat(),
                        )
                    }
                    .orEmpty(),
            )
        } else {
            val cutout = insets.displayCutout
            RegionCutoutInsets(
                left = cutout?.safeInsetLeft ?: 0,
                top = cutout?.safeInsetTop ?: 0,
                right = cutout?.safeInsetRight ?: 0,
                bottom = cutout?.safeInsetBottom ?: 0,
                boundingRects = cutout?.boundingRects
                    ?.map { bounds ->
                        EditorFloatRect(
                            bounds.left.toFloat(),
                            bounds.top.toFloat(),
                            bounds.right.toFloat(),
                            bounds.bottom.toFloat(),
                        )
                    }
                    .orEmpty(),
            )
        }

    private fun currentDisplayCutoutInsets(
        view: View,
        metricsWindowManager: WindowManager = windowManager,
    ): RegionCutoutInsets =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayCutoutInsets(metricsWindowManager.currentWindowMetrics.windowInsets)
        } else {
            view.rootWindowInsets?.let(::displayCutoutInsets) ?: RegionCutoutInsets()
        }

    @Suppress("DEPRECATION")
    private fun panelSafeInsets(insets: WindowInsets): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val visibleBars = insets.getInsets(WindowInsets.Type.systemBars())
            val cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            Rect(
                maxOf(visibleBars.left, cutout.left),
                maxOf(visibleBars.top, cutout.top),
                maxOf(visibleBars.right, cutout.right),
                maxOf(visibleBars.bottom, cutout.bottom),
            )
        } else {
            val cutout = insets.displayCutout
            Rect(
                maxOf(insets.systemWindowInsetLeft, cutout?.safeInsetLeft ?: 0),
                maxOf(insets.systemWindowInsetTop, cutout?.safeInsetTop ?: 0),
                maxOf(insets.systemWindowInsetRight, cutout?.safeInsetRight ?: 0),
                maxOf(insets.systemWindowInsetBottom, cutout?.safeInsetBottom ?: 0),
            )
        }

    private fun fixedSideShortLabel(side: FixedSide): String = when (side) {
        FixedSide.TOP -> overlayString(R.string.overlay_side_top)
        FixedSide.BOTTOM -> overlayString(R.string.overlay_side_bottom)
        FixedSide.LEFT -> overlayString(R.string.overlay_side_left)
        FixedSide.RIGHT -> overlayString(R.string.overlay_side_right)
    }

    private fun Rect.toRegionHandleTouchableInsets() = RegionHandleTouchableInsets(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    private fun fixedAnchorPositionShortLabel(
        position: FixedAnchorPosition,
        vertical: Boolean,
    ): String = when (position) {
        FixedAnchorPosition.LEADING -> fixedSideShortLabel(
            if (vertical) FixedSide.TOP else FixedSide.LEFT,
        )
        FixedAnchorPosition.TRAILING -> fixedSideShortLabel(
            if (vertical) FixedSide.BOTTOM else FixedSide.RIGHT,
        )
    }

    private fun normalizeJpegQuality(value: Int): Int =
        ImageOutputFormat.normalizeJpegQuality(value)

    private fun qualityColor(confidence: Float): Int = when {
        confidence >= .75f -> accentColor()
        confidence >= .55f -> overlayWarningColor(overlayDarkTheme)
        else -> errorColor()
    }
}

internal enum class RegionResizeHandleEdge { LEFT, TOP, RIGHT, BOTTOM }

internal data class RegionCutoutInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val boundingRects: List<EditorFloatRect> = emptyList(),
)

internal data class RegionHandleTouchableInsets(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

internal data class RegionHandleTouchablePlacement(
    val offsetX: Float,
    val offsetY: Float,
    val touchRadius: Float,
)

/**
 * Moves a handle's visual and touch center into the portion of the physical display where an
 * overlay can actually receive touches. The edited edge itself remains at its original physical
 * coordinate; callers apply only this translation to the waterdrop geometry.
 */
internal fun regionHandleTouchablePlacement(
    centerX: Float,
    centerY: Float,
    requestedTouchRadius: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    insets: RegionHandleTouchableInsets,
): RegionHandleTouchablePlacement {
    val width = viewportWidth.coerceAtLeast(0f)
    val height = viewportHeight.coerceAtLeast(0f)
    val contentLeft = insets.left.coerceAtLeast(0).toFloat().coerceAtMost(width)
    val contentTop = insets.top.coerceAtLeast(0).toFloat().coerceAtMost(height)
    val contentRight = (width - insets.right.coerceAtLeast(0))
        .coerceIn(contentLeft, width)
    val contentBottom = (height - insets.bottom.coerceAtLeast(0))
        .coerceIn(contentTop, height)
    val radius = minOf(
        requestedTouchRadius.coerceAtLeast(0f),
        (contentRight - contentLeft) / 2f,
        (contentBottom - contentTop) / 2f,
    ).coerceAtLeast(0f)
    val minimumCenterX = contentLeft + radius
    val maximumCenterX = contentRight - radius
    val minimumCenterY = contentTop + radius
    val maximumCenterY = contentBottom - radius
    val targetCenterX = centerX.coerceIn(
        minimumCenterX,
        maximumCenterX.coerceAtLeast(minimumCenterX),
    )
    val targetCenterY = centerY.coerceIn(
        minimumCenterY,
        maximumCenterY.coerceAtLeast(minimumCenterY),
    )
    return RegionHandleTouchablePlacement(
        offsetX = targetCenterX - centerX,
        offsetY = targetCenterY - centerY,
        touchRadius = radius,
    )
}

internal fun regionHandleFractions(
    rect: EditorFloatRect,
    edge: RegionResizeHandleEdge,
    viewportWidth: Float,
    viewportHeight: Float,
    cutoutInsets: RegionCutoutInsets,
): List<Float> {
    val entersCutoutBand = when (edge) {
        RegionResizeHandleEdge.LEFT ->
            cutoutInsets.left > 0 && rect.left < cutoutInsets.left
        RegionResizeHandleEdge.TOP ->
            cutoutInsets.top > 0 && rect.top < cutoutInsets.top
        RegionResizeHandleEdge.RIGHT ->
            cutoutInsets.right > 0 && rect.right > viewportWidth - cutoutInsets.right
        RegionResizeHandleEdge.BOTTOM ->
            cutoutInsets.bottom > 0 && rect.bottom > viewportHeight - cutoutInsets.bottom
    }
    val relevantBounds = cutoutInsets.boundingRects.filter { bounds ->
        when (edge) {
            RegionResizeHandleEdge.LEFT -> bounds.left <= cutoutInsets.left
            RegionResizeHandleEdge.TOP -> bounds.top <= cutoutInsets.top
            RegionResizeHandleEdge.RIGHT ->
                bounds.right >= viewportWidth - cutoutInsets.right
            RegionResizeHandleEdge.BOTTOM ->
                bounds.bottom >= viewportHeight - cutoutInsets.bottom
        }
    }
    val overlapsCutout = if (cutoutInsets.boundingRects.isEmpty()) {
        true
    } else {
        relevantBounds.any { bounds ->
            when (edge) {
                RegionResizeHandleEdge.LEFT, RegionResizeHandleEdge.RIGHT ->
                    rect.top < bounds.bottom && rect.bottom > bounds.top
                RegionResizeHandleEdge.TOP, RegionResizeHandleEdge.BOTTOM ->
                    rect.left < bounds.right && rect.right > bounds.left
            }
        }
    }
    val touchesCutout = entersCutoutBand && overlapsCutout
    return if (touchesCutout) listOf(0.25f, 0.5f, 0.75f) else listOf(0.5f)
}

internal data class RegionWaterdropGeometry(
    val tipX: Float,
    val tipY: Float,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val bounds: EditorFloatRect,
)

internal fun regionWaterdropGeometry(
    rect: EditorFloatRect,
    edge: RegionResizeHandleEdge,
    requestedInset: Float,
    maximumRadius: Float,
    minimumRadius: Float,
    edgeFraction: Float = 0.5f,
): RegionWaterdropGeometry {
    val fraction = edgeFraction.coerceIn(0f, 1f)
    val horizontalInset = minOf(requestedInset, rect.width.coerceAtLeast(0f) / 2f)
    val verticalInset = minOf(requestedInset, rect.height.coerceAtLeast(0f) / 2f)
    val tipX: Float
    val tipY: Float
    val centerX: Float
    val centerY: Float
    val actualInset: Float
    when (edge) {
        RegionResizeHandleEdge.LEFT -> {
            tipX = rect.left
            tipY = rect.top + rect.height * fraction
            centerX = rect.left + horizontalInset
            centerY = tipY
            actualInset = horizontalInset
        }
        RegionResizeHandleEdge.TOP -> {
            tipX = rect.left + rect.width * fraction
            tipY = rect.top
            centerX = tipX
            centerY = rect.top + verticalInset
            actualInset = verticalInset
        }
        RegionResizeHandleEdge.RIGHT -> {
            tipX = rect.right
            tipY = rect.top + rect.height * fraction
            centerX = rect.right - horizontalInset
            centerY = tipY
            actualInset = horizontalInset
        }
        RegionResizeHandleEdge.BOTTOM -> {
            tipX = rect.left + rect.width * fraction
            tipY = rect.bottom
            centerX = tipX
            centerY = rect.bottom - verticalInset
            actualInset = verticalInset
        }
    }
    val radius = minOf(maximumRadius, actualInset * 0.48f).coerceAtLeast(minimumRadius)
    val bounds = when (edge) {
        RegionResizeHandleEdge.LEFT -> EditorFloatRect(
            tipX,
            centerY - radius,
            centerX + radius,
            centerY + radius,
        )
        RegionResizeHandleEdge.TOP -> EditorFloatRect(
            centerX - radius,
            tipY,
            centerX + radius,
            centerY + radius,
        )
        RegionResizeHandleEdge.RIGHT -> EditorFloatRect(
            centerX - radius,
            centerY - radius,
            tipX,
            centerY + radius,
        )
        RegionResizeHandleEdge.BOTTOM -> EditorFloatRect(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            tipY,
        )
    }
    return RegionWaterdropGeometry(tipX, tipY, centerX, centerY, radius, bounds)
}

internal data class GestureDistanceLabelPlacement(
    val bounds: EditorFloatRect,
    val usesPathExtension: Boolean,
)

internal fun placeGestureDistanceLabelGeometry(
    start: EditorPoint,
    end: EditorPoint,
    center: EditorPoint,
    unitX: Float,
    unitY: Float,
    normalX: Float,
    normalY: Float,
    labelWidth: Float,
    labelHeight: Float,
    viewport: EditorFloatRect,
    gap: Float,
    obstacles: List<EditorFloatRect> = emptyList(),
): GestureDistanceLabelPlacement {
    val safeWidth = labelWidth.coerceAtMost(viewport.width).coerceAtLeast(1f)
    val safeHeight = labelHeight.coerceAtMost(viewport.height).coerceAtLeast(1f)
    fun centered(centerX: Float, centerY: Float) = EditorFloatRect(
        centerX - safeWidth / 2f,
        centerY - safeHeight / 2f,
        centerX + safeWidth / 2f,
        centerY + safeHeight / 2f,
    )
    fun fits(candidate: EditorFloatRect): Boolean =
        candidate.left >= viewport.left && candidate.top >= viewport.top &&
            candidate.right <= viewport.right && candidate.bottom <= viewport.bottom &&
            obstacles.none(candidate::intersects)

    val safeGap = gap.coerceAtLeast(0f)
    val normalHalfExtent = kotlin.math.abs(normalX) * safeWidth / 2f +
        kotlin.math.abs(normalY) * safeHeight / 2f
    val preferred = centered(
        center.x - normalX * (normalHalfExtent + safeGap),
        center.y - normalY * (normalHalfExtent + safeGap),
    )
    if (fits(preferred)) return GestureDistanceLabelPlacement(preferred, false)

    val axisHalfExtent = kotlin.math.abs(unitX) * safeWidth / 2f +
        kotlin.math.abs(unitY) * safeHeight / 2f
    fun extension(anchor: EditorPoint, sign: Float): EditorFloatRect {
        var centerX = anchor.x + unitX * sign * (axisHalfExtent + safeGap)
        var centerY = anchor.y + unitY * sign * (axisHalfExtent + safeGap)
        if (kotlin.math.abs(unitY) >= kotlin.math.abs(unitX)) {
            centerX = centerX.coerceIn(
                viewport.left + safeWidth / 2f,
                viewport.right - safeWidth / 2f,
            )
        } else {
            centerY = centerY.coerceIn(
                viewport.top + safeHeight / 2f,
                viewport.bottom - safeHeight / 2f,
            )
        }
        return centered(centerX, centerY)
    }
    val extensions = listOf(extension(end, 1f), extension(start, -1f))
    extensions.firstOrNull(::fits)?.let {
        return GestureDistanceLabelPlacement(it, true)
    }

    val fallback = extensions.first()
    val clampedLeft = fallback.left.coerceIn(
        viewport.left,
        (viewport.right - safeWidth).coerceAtLeast(viewport.left),
    )
    val clampedTop = fallback.top.coerceIn(
        viewport.top,
        (viewport.bottom - safeHeight).coerceAtLeast(viewport.top),
    )
    val clamped = EditorFloatRect(
        clampedLeft,
        clampedTop,
        clampedLeft + safeWidth,
        clampedTop + safeHeight,
    )
    if (fits(clamped)) return GestureDistanceLabelPlacement(clamped, true)

    val searched = placeContainedEditorLabels(
        requests = listOf(
            ContainedEditorLabelRequest(
                anchor = EditorFloatRect(
                    minOf(start.x, end.x),
                    minOf(start.y, end.y),
                    maxOf(start.x, end.x),
                    maxOf(start.y, end.y),
                ),
                container = viewport,
                width = safeWidth,
                height = safeHeight,
                obstacles = obstacles,
            ),
        ),
        bounds = viewport,
        gap = safeGap,
    ).single()
    return GestureDistanceLabelPlacement(searched, true)
}

/** Full-screen editor for capture, fixed/repeating regions and the gesture path. */
internal data class RegionEditModeOption(val label: String, val color: Int)

internal data class RegionEditorColors(
    val capture: Int,
    val stitch: Int,
    val gesture: Int,
    val fixed1: Int,
    val fixed2: Int,
)

internal fun regionEditorColors(darkTheme: Boolean): RegionEditorColors =
    if (darkTheme) {
        RegionEditorColors(
            capture = 0xFF64B5F6.toInt(),
            stitch = 0xFF81C784.toInt(),
            gesture = 0xFFFFD54F.toInt(),
            fixed1 = 0xFFF06292.toInt(),
            fixed2 = 0xFFCE93D8.toInt(),
        )
    } else {
        RegionEditorColors(
            capture = 0xFF1565C0.toInt(),
            stitch = 0xFF2E7D32.toInt(),
            gesture = 0xFFA15C00.toInt(),
            fixed1 = 0xFFAD1457.toInt(),
            fixed2 = 0xFF6A1B9A.toInt(),
        )
    }

internal class RegionSelectionView(context: Context) : View(context) {
    private enum class DragMode { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM }
    private enum class GestureDragMode { NONE, START, MIDDLE, END }

    private enum class EditMode(@param:StringRes val labelRes: Int, val fixedLayerIndex: Int? = null) {
        CAPTURE(R.string.overlay_capture_region),
        STITCH(R.string.overlay_scroll_region),
        GESTURE(R.string.overlay_gesture_path),
        FIXED_1(R.string.overlay_fixed_band_1, 0),
        FIXED_2(R.string.overlay_fixed_band_2, 1),
    }

    private val density = resources.displayMetrics.density
    private val shadePaint = Paint().apply { color = 0x90000000.toInt() }
    private val capturePaint: Paint
    private val stitchPaint: Paint
    private val gesturePaint: Paint
    private val gestureFillPaint: Paint
    private val gestureDistanceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fixedLayerPaints: List<Paint>
    private val fixedLayerFillPaints: List<Paint>
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelTextColor: Int

    private var modelWidth = 1
    private var modelHeight = 1
    private var captureRect = RectF()
    private var stitchRect = RectF()
    private var gestureRect = RectF()
    private var gestureDistancePx = 1
    private var repeatRects = mutableListOf<RectF?>()
    private var fixedLayers: List<FixedLayerConfig> = emptyList()
    private var scrollDirection = ScrollDirection.UP
    private var dragMode = DragMode.NONE
    private var gestureDragMode = GestureDragMode.NONE
    private var gestureEndpointDragAnchor: GestureEndpointDragAnchor? = null
    private var lastX = 0f
    private var lastY = 0f
    private var editMode = EditMode.CAPTURE
    private var labelOcclusionRects: List<RectF> = emptyList()
    private var displayCutoutInsets = RegionCutoutInsets()
    private var handleTouchableInsets = RegionHandleTouchableInsets()
    private var nonFocusedOpacity = DEFAULT_NON_FOCUSED_REGION_OPACITY
    private var opacityPreviewMode = false
    private var handleDragActive = false

    var onRegionsChanged: ((RectConfig, RectConfig, RectConfig, Int, List<FixedLayerConfig>) -> Unit)? = null
    var onEditModeChanged: ((String) -> Unit)? = null
    var onHandleDragStateChanged: ((Boolean) -> Unit)? = null

    init {
        val nightMask = android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isDark = context.resources.configuration.uiMode and nightMask ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colors = regionEditorColors(isDark)
        val backgroundAttributes = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.colorBackgroundFloating),
        )
        labelBackgroundPaint.color = (backgroundAttributes.getColor(
            0,
            if (isDark) 0xFF1B1B1F.toInt() else Color.WHITE,
        ) and 0x00FFFFFF) or 0xE8000000.toInt()
        backgroundAttributes.recycle()
        val textAttributes = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary),
        )
        labelTextColor = textAttributes.getColor(
            0,
            if (isDark) Color.WHITE else 0xFF1B1B1F.toInt(),
        )
        textAttributes.recycle()
        capturePaint = stroke(colors.capture)
        stitchPaint = stroke(colors.stitch)
        gesturePaint = stroke(colors.gesture)
        gestureFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.gesture }
        fixedLayerPaints = listOf(stroke(colors.fixed1), stroke(colors.fixed2))
        fixedLayerFillPaints = listOf(colors.fixed1, colors.fixed2).map { color ->
            Paint().apply { this.color = (color and 0x00FFFFFF) or 0x2A000000 }
        }
        textPaint.apply {
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            color = 0xFF1B1B1F.toInt()
        }
        gestureDistanceTextPaint.apply {
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            color = labelTextColor
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    fun update(
        screenWidth: Int,
        screenHeight: Int,
        scrollDirection: ScrollDirection,
        capture: RectConfig,
        stitch: RectConfig,
        gesture: RectConfig,
        gestureDistancePx: Int,
        fixedLayers: List<FixedLayerConfig>,
    ) {
        modelWidth = screenWidth.coerceAtLeast(1)
        modelHeight = screenHeight.coerceAtLeast(1)
        this.scrollDirection = scrollDirection
        captureRect = capture.toRectF()
        stitchRect = stitch.toRectF()
        gestureRect = gesture.toRectF()
        this.gestureDistancePx = gestureDistancePx
        this.fixedLayers = fixedLayers.take(2).map { layer ->
            if (fixedBandStretchesWithScroll(scrollDirection, layer.side)) {
                layer
            } else {
                layer.copy(
                    anchorCount = 1,
                    anchorPosition = FixedAnchorPosition.LEADING,
                    repeatRegion = null,
                )
            }
        }
        repeatRects = this.fixedLayers.map { layer ->
            layer.repeatRegion
                ?.takeIf { fixedBandStretchesWithScroll(scrollDirection, layer.side) }
                ?.toRectF()
        }.toMutableList()
        normalizeGeometry()
        ensureCurrentEditMode()
        invalidate()
    }

    fun setRegions(
        capture: RectConfig,
        stitch: RectConfig,
        gesture: RectConfig,
        gestureDistancePx: Int,
        fixedLayers: List<FixedLayerConfig>,
    ) {
        captureRect = capture.toRectF()
        stitchRect = stitch.toRectF()
        gestureRect = gesture.toRectF()
        this.gestureDistancePx = gestureDistancePx
        this.fixedLayers = fixedLayers.take(2).map { layer ->
            if (fixedBandStretchesWithScroll(scrollDirection, layer.side)) {
                layer
            } else {
                layer.copy(
                    anchorCount = 1,
                    anchorPosition = FixedAnchorPosition.LEADING,
                    repeatRegion = null,
                )
            }
        }
        repeatRects = this.fixedLayers.map { layer ->
            layer.repeatRegion
                ?.takeIf { fixedBandStretchesWithScroll(scrollDirection, layer.side) }
                ?.toRectF()
        }.toMutableList()
        normalizeGeometry()
        ensureCurrentEditMode()
        notifyChanged()
        invalidate()
    }

    fun selectNextMode(): String {
        val modes = availableEditModes()
        val current = modes.indexOf(editMode)
        editMode = modes[(current + 1).coerceAtLeast(0) % modes.size]
        onEditModeChanged?.invoke(editModeLabel(editMode))
        invalidate()
        return editModeLabel(editMode)
    }

    fun availableEditModeLabels(): List<String> = availableEditModes().map(::editModeLabel)

    fun availableEditModeOptions(): List<RegionEditModeOption> =
        availableEditModes().map { mode -> RegionEditModeOption(editModeLabel(mode), editModeColor(mode)) }

    fun editModeColor(label: String): Int = availableEditModes()
        .firstOrNull { editModeLabel(it) == label }
        ?.let(::editModeColor)
        ?: capturePaint.color

    fun selectEditMode(label: String): Boolean {
        val selected = availableEditModes().firstOrNull { editModeLabel(it) == label } ?: return false
        editMode = selected
        onEditModeChanged?.invoke(editModeLabel(editMode))
        invalidate()
        return true
    }

    fun setLabelOcclusions(rects: List<RectF>) {
        labelOcclusionRects = rects.map { source -> RectF(source).apply {
            val padding = 6f * density
            inset(-padding, -padding)
        } }
        invalidate()
    }

    fun setDisplayCutoutInsets(value: RegionCutoutInsets) {
        if (displayCutoutInsets == value) return
        displayCutoutInsets = value
        invalidate()
    }

    fun setHandleTouchableInsets(value: RegionHandleTouchableInsets) {
        if (handleTouchableInsets == value) return
        handleTouchableInsets = value
        invalidate()
    }

    fun setNonFocusedOpacity(value: Float) {
        val normalized = normalizeOverlayOpacity(value)
        if (nonFocusedOpacity == normalized) return
        nonFocusedOpacity = normalized
        invalidate()
    }

    fun setOpacityPreviewMode(enabled: Boolean) {
        if (opacityPreviewMode == enabled) return
        opacityPreviewMode = enabled
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width.toFloat() / modelWidth
        val sy = height.toFloat() / modelHeight
        val visibleCapture = captureRect.scaled(sx, sy)
        val visibleStitch = stitchRect.scaled(sx, sy)
        if (!opacityPreviewMode) {
            canvas.drawRect(0f, 0f, width.toFloat(), visibleCapture.top, shadePaint)
            canvas.drawRect(
                0f,
                visibleCapture.bottom,
                width.toFloat(),
                height.toFloat(),
                shadePaint,
            )
            canvas.drawRect(
                0f,
                visibleCapture.top,
                visibleCapture.left,
                visibleCapture.bottom,
                shadePaint,
            )
            canvas.drawRect(
                visibleCapture.right,
                visibleCapture.top,
                width.toFloat(),
                visibleCapture.bottom,
                shadePaint,
            )
        }

        val labels = mutableListOf(
            RegionLabel(
                localizedString(R.string.overlay_capture_region),
                visibleCapture,
                captureRect,
                capturePaint.color,
                selected = !opacityPreviewMode && editMode == EditMode.CAPTURE,
            ),
            RegionLabel(
                localizedString(R.string.overlay_scroll_region),
                visibleStitch,
                stitchRect,
                stitchPaint.color,
                selected = !opacityPreviewMode && editMode == EditMode.STITCH,
            ),
        )
        val frameRects = mutableListOf(visibleCapture, visibleStitch)
        val visibleFixedBands = mutableMapOf<Int, RectF>()
        fixedLayers.forEachIndexed { index, layer ->
            fixedBandRect(layer.side)?.let { band ->
                val visible = band.scaled(sx, sy)
                visibleFixedBands[index] = visible
                frameRects += visible
                val paint = fixedLayerPaints[index.coerceIn(fixedLayerPaints.indices)]
                val selected = !opacityPreviewMode && editMode.fixedLayerIndex == index
                if (selected) {
                    canvas.drawRect(
                        visible,
                        fixedLayerFillPaints[index.coerceIn(fixedLayerFillPaints.indices)],
                    )
                }
                drawRegionOutline(canvas, visible, paint, active = false)
                if (shouldShowFixedBandLabels(fixedLayers.size)) {
                    labels += RegionLabel(
                        localizedString(
                            R.string.overlay_fixed_band_label,
                            fixedSideShortLabel(layer.side),
                        ),
                        visible,
                        band,
                        paint.color,
                        selected,
                    )
                }
            }
        }
        val visibleRepeats = fixedLayers.mapIndexedNotNull { index, layer ->
            if (!fixedBandStretchesWithScroll(scrollDirection, layer.side)) {
                return@mapIndexedNotNull null
            }
            val source = repeatRects.getOrNull(index) ?: return@mapIndexedNotNull null
            val band = fixedBandRect(layer.side) ?: return@mapIndexedNotNull null
            val repeat = RectF(source)
            if (!repeat.intersect(band)) return@mapIndexedNotNull null
            Triple(index, layer, repeat to repeat.scaled(sx, sy))
        }
        visibleRepeats.forEach { (index, layer, repeatData) ->
            val (repeat, visible) = repeatData
            frameRects += visible
            val paint = fixedLayerPaints[index.coerceIn(fixedLayerPaints.indices)]
            val selected = !opacityPreviewMode && editMode.fixedLayerIndex == index
            if (selected) {
                canvas.drawRect(
                    visible,
                    fixedLayerFillPaints[index.coerceIn(fixedLayerFillPaints.indices)],
                )
            }
            drawRegionOutline(
                canvas,
                visible,
                paint,
                selected,
                allowLeft = !fixedBandRunsVertically(layer.side) &&
                    (layer.anchorCount == 2 ||
                        layer.anchorPosition == FixedAnchorPosition.LEADING),
                allowTop = fixedBandRunsVertically(layer.side) &&
                    (layer.anchorCount == 2 ||
                        layer.anchorPosition == FixedAnchorPosition.LEADING),
                allowRight = !fixedBandRunsVertically(layer.side) &&
                    (layer.anchorCount == 2 ||
                        layer.anchorPosition == FixedAnchorPosition.TRAILING),
                allowBottom = fixedBandRunsVertically(layer.side) &&
                    (layer.anchorCount == 2 ||
                        layer.anchorPosition == FixedAnchorPosition.TRAILING),
            )
            val blockViews = fixedBandRect(layer.side)?.let { band ->
                fixedAnchorBlocks(
                    band = band.toConfig(),
                    repeat = repeat.toConfig(),
                    vertical = fixedBandRunsVertically(layer.side),
                    anchorCount = layer.anchorCount,
                    anchorPosition = layer.anchorPosition,
                ).map { block -> block to block.region.toRectF().scaled(sx, sy) }
            }.orEmpty()
            blockViews.mapTo(frameRects) { it.second }
            val bandContainer = visibleFixedBands[index] ?: visible
            labels += RegionLabel(
                name = if (fixedLayers.size == 1) {
                    localizedString(R.string.overlay_repeat_region)
                } else {
                    localizedString(
                        R.string.overlay_fixed_band_repeat_region,
                        fixedSideShortLabel(layer.side),
                    )
                },
                rect = visible,
                model = repeat,
                color = paint.color,
                selected = selected,
                container = bandContainer,
                allowedFrameBorders = blockViews.flatMap { (_, blockView) ->
                    editorSharedFrameBorderAllowances(visible.toEditorRect(), blockView.toEditorRect())
                },
            )
            if (shouldShowFixedAnchorBlockLabels(layer.anchorCount)) {
                blockViews.forEach { (block, blockView) ->
                    val model = block.region.toRectF()
                    val blockName = fixedAnchorBlockLabel(block.position, layer.side)
                    labels += RegionLabel(
                        name = if (fixedLayers.size == 1) {
                            blockName
                        } else {
                            localizedString(
                                R.string.overlay_side_named_item,
                                fixedSideShortLabel(layer.side),
                                blockName,
                            )
                        },
                        rect = blockView,
                        model = model,
                        color = paint.color,
                        selected = selected,
                        container = bandContainer,
                        allowedFrameBorders = editorSharedFrameBorderAllowances(
                            blockView.toEditorRect(),
                            visible.toEditorRect(),
                        ),
                    )
                }
            }
        }
        drawRegionOutline(
            canvas,
            visibleStitch,
            stitchPaint,
            !opacityPreviewMode && editMode == EditMode.STITCH,
        )
        drawRegionOutline(
            canvas,
            visibleCapture,
            capturePaint,
            !opacityPreviewMode && editMode == EditMode.CAPTURE,
        )
        val gestureGeometry = gestureDisplayGeometry(
            sx = sx,
            sy = sy,
            labelObstacles = frameRects.flatMap { frame ->
                editorFrameBorderObstacles(frame.toEditorRect(), 2f * density)
            },
        )
        drawRegionLabels(canvas, labels, frameRects, gestureGeometry)
        drawGesturePath(canvas, gestureGeometry)
    }

    private data class EditableHandleTouchCircle(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
    )

    fun editableHandleBounds(): List<Rect> = editableHandleTouchCircles().mapNotNull { circle ->
        // Overlay windows have rectangular touch regions. Keep each proxy strictly inside the
        // circular hit target so its corners never consume a background touch or fall through to
        // the region MOVE gesture.
        val halfSide = circle.radius * TOUCH_PROXY_INSCRIBED_HALF_SIDE_FACTOR
        val bounds = Rect(
            ceil(circle.centerX - halfSide).toInt(),
            ceil(circle.centerY - halfSide).toInt(),
            floor(circle.centerX + halfSide).toInt(),
            floor(circle.centerY + halfSide).toInt(),
        )
        bounds.takeIf { it.intersect(0, 0, width, height) }
    }

    private fun editableHandleTouchCircles(): List<EditableHandleTouchCircle> {
        if (opacityPreviewMode || width <= 0 || height <= 0) return emptyList()
        val sx = width.toFloat() / modelWidth.coerceAtLeast(1)
        val sy = height.toFloat() / modelHeight.coerceAtLeast(1)
        val result = mutableListOf<EditableHandleTouchCircle>()
        if (editMode == EditMode.GESTURE) {
            gestureDisplayGeometry(sx, sy)?.handles?.forEach { (_, handle) ->
                result += EditableHandleTouchCircle(
                    centerX = handle.center.x,
                    centerY = handle.center.y,
                    radius = handle.touchRadius,
                )
            }
            return result
        }

        val visibleRect = activeRect()?.scaled(sx, sy) ?: return emptyList()
        val maximumTouchRadius = RESIZE_HANDLE_TOUCH_RADIUS_DP * density
        activeResizeDragModes().forEach { mode ->
            resizeHandleFractions(visibleRect, mode).forEach handle@ { fraction ->
                val handle = positionedWaterdropGeometry(
                    visibleRect,
                    mode.toResizeHandleEdge() ?: return@handle,
                    fraction,
                    maximumTouchRadius,
                )
                if (handle.touchRadius <= 0f) return@handle
                result += EditableHandleTouchCircle(
                    centerX = handle.geometry.centerX,
                    centerY = handle.geometry.centerY,
                    radius = handle.touchRadius,
                )
            }
        }
        return result
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (opacityPreviewMode) return false
        val x = event.x * modelWidth / width.coerceAtLeast(1)
        val y = event.y * modelHeight / height.coerceAtLeast(1)
        val gestureEditing = editMode == EditMode.GESTURE
        val rect = if (gestureEditing) {
            null
        } else {
            activeRect() ?: return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gestureEditing) {
                    gestureDragMode = hitTestGesture(event.x, event.y)
                    if (gestureDragMode == GestureDragMode.NONE) return false
                    gestureEndpointDragAnchor = createGestureEndpointDragAnchor(
                        mode = gestureDragMode,
                        pointerAxis = if (scrollDirection.isVertical) y else x,
                    )
                } else {
                    dragMode = hitTest(requireNotNull(rect), x, y)
                    if (dragMode == DragMode.NONE) return false
                }
                setHandleDragActive(
                    dragMode == DragMode.LEFT ||
                        dragMode == DragMode.TOP ||
                        dragMode == DragMode.RIGHT ||
                        dragMode == DragMode.BOTTOM ||
                        gestureDragMode == GestureDragMode.START ||
                        gestureDragMode == GestureDragMode.END,
                )
                lastX = x
                lastY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY
                if (gestureEditing) {
                    when (gestureDragMode) {
                        GestureDragMode.START, GestureDragMode.END ->
                            gestureEndpointDragAnchor?.let { anchor ->
                                dragGestureEndpoint(
                                    anchor = anchor,
                                    pointerAxis = if (scrollDirection.isVertical) y else x,
                                )
                            }
                        GestureDragMode.MIDDLE -> dragGesturePath(gestureDragMode, dx, dy)
                        GestureDragMode.NONE -> Unit
                    }
                } else when (dragMode) {
                    DragMode.MOVE -> if (editMode == EditMode.CAPTURE) {
                        val moveX = dx.coerceIn(-captureRect.left, modelWidth - captureRect.right)
                        val moveY = dy.coerceIn(-captureRect.top, modelHeight - captureRect.bottom)
                        captureRect.offset(moveX, moveY)
                        stitchRect.offset(moveX, moveY)
                        repeatRects.forEach { it?.offset(moveX, moveY) }
                    } else if (editMode == EditMode.STITCH) {
                        val moveX = dx.coerceIn(
                            captureRect.left - stitchRect.left,
                            captureRect.right - stitchRect.right,
                        )
                        val moveY = dy.coerceIn(
                            captureRect.top - stitchRect.top,
                            captureRect.bottom - stitchRect.bottom,
                        )
                        stitchRect.offset(moveX, moveY)
                    } else if (editMode.fixedLayerIndex != null) {
                        val verticalBand = activeFixedLayer()
                            ?.let { fixedBandRunsVertically(it.side) }
                            ?: scrollDirection.isVertical
                        requireNotNull(rect).offset(
                            if (verticalBand) 0f else dx,
                            if (verticalBand) dy else 0f,
                        )
                    } else {
                        requireNotNull(rect).offset(dx, dy)
                    }
                    DragMode.LEFT,
                    DragMode.TOP,
                    DragMode.RIGHT,
                    DragMode.BOTTOM,
                    -> {
                        if (editMode == EditMode.STITCH && !hasFixedBandAt(dragMode)) {
                            resizeSharedStitchEdge(dragMode, dx, dy)
                        } else {
                            when (dragMode) {
                                DragMode.LEFT -> requireNotNull(rect).left += dx
                                DragMode.TOP -> requireNotNull(rect).top += dy
                                DragMode.RIGHT -> requireNotNull(rect).right += dx
                                DragMode.BOTTOM -> requireNotNull(rect).bottom += dy
                                DragMode.NONE, DragMode.MOVE -> Unit
                            }
                        }
                    }
                    DragMode.NONE -> Unit
                }
                if (!gestureEditing) constrainActive(requireNotNull(rect))
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                setHandleDragActive(false)
                dragMode = DragMode.NONE
                gestureDragMode = GestureDragMode.NONE
                gestureEndpointDragAnchor = null
                notifyChanged()
                return true
            }
        }
        return false
    }

    override fun onDetachedFromWindow() {
        setHandleDragActive(false)
        super.onDetachedFromWindow()
    }

    private fun setHandleDragActive(active: Boolean) {
        if (handleDragActive == active) return
        handleDragActive = active
        onHandleDragStateChanged?.invoke(active)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        normalizeGestureDistance()
    }

    private fun currentGesturePath(): ScrollGesturePath {
        return resolveScrollGesturePath(
            region = gestureRect.toConfig(),
            direction = scrollDirection,
            requestedDistancePx = gestureDistancePx,
            distanceRegion = stitchRect.toConfig(),
        )
    }

    private fun normalizeGestureDistance() {
        val maximum = maximumGestureDistance()
        val minimum = gestureMinimumDistancePx(maximum)
        gestureDistancePx = gestureDistancePx.coerceIn(minimum, maximum)
    }

    private fun gestureMinimumDistancePx(maximum: Int): Int {
        val axisScale = if (scrollDirection.isVertical) {
            height.toFloat() / modelHeight.coerceAtLeast(1)
        } else {
            width.toFloat() / modelWidth.coerceAtLeast(1)
        }.takeIf { it > 0f } ?: 1f
        val minimumHandleSpacingInModel = kotlin.math.ceil(
            (GESTURE_HANDLE_TOUCH_RADIUS_DP * density *
                GESTURE_MIN_DISTANCE_TOUCH_RADIUS_MULTIPLIER) / axisScale,
        ).toInt()
        return maxOf(MIN_GESTURE_DISTANCE_PX, minimumHandleSpacingInModel)
            .coerceAtMost(maximum)
            .coerceAtLeast(1)
    }

    /** The arrow may span at most 90% of the actual scrolling layer. */
    private fun maximumGestureDistance(): Int =
        maximumScrollGestureDistance(stitchRect.toConfig(), scrollDirection)

    private fun dragGesturePath(mode: GestureDragMode, dx: Float, dy: Float) {
        if (mode == GestureDragMode.NONE) return
        if (mode == GestureDragMode.MIDDLE) {
            gestureRect = moveScrollGestureRegionByPathCenter(
                region = gestureRect.toConfig(),
                bounds = RectConfig.full(modelWidth, modelHeight),
                direction = scrollDirection,
                deltaX = dx,
                deltaY = dy,
            ).toRectF()
            return
        }
    }

    private fun createGestureEndpointDragAnchor(
        mode: GestureDragMode,
        pointerAxis: Float,
    ): GestureEndpointDragAnchor? {
        if (mode != GestureDragMode.START && mode != GestureDragMode.END) return null
        val path = currentGesturePath()
        val fixedAxis = when (mode) {
            GestureDragMode.START -> if (scrollDirection.isVertical) path.end.y else path.end.x
            GestureDragMode.END -> if (scrollDirection.isVertical) path.start.y else path.start.x
            GestureDragMode.NONE, GestureDragMode.MIDDLE -> return null
        }
        return GestureEndpointDragAnchor(
            mode = mode,
            pointerAxis = pointerAxis,
            fixedAxis = fixedAxis,
            initialDistance = path.distancePx,
            initialRegion = RectF(gestureRect),
        )
    }

    private fun dragGestureEndpoint(
        anchor: GestureEndpointDragAnchor,
        pointerAxis: Float,
    ) {
        val directionSign = when (scrollDirection) {
            ScrollDirection.UP, ScrollDirection.LEFT -> -1f
            ScrollDirection.DOWN, ScrollDirection.RIGHT -> 1f
        }
        val axisDelta = pointerAxis - anchor.pointerAxis
        val requestedDistance = when (anchor.mode) {
            GestureDragMode.START -> anchor.initialDistance - directionSign * axisDelta
            GestureDragMode.END -> anchor.initialDistance + directionSign * axisDelta
            GestureDragMode.NONE, GestureDragMode.MIDDLE -> return
        }
        val maximum = maximumGestureDistance()
        val minimum = gestureMinimumDistancePx(maximum)
        val movementStart = if (scrollDirection.isVertical) {
            stitchRect.top.toFloat()
        } else {
            stitchRect.left.toFloat()
        }
        val movementEnd = if (scrollDirection.isVertical) {
            stitchRect.bottom.toFloat()
        } else {
            stitchRect.right.toFloat()
        }
        val movementInset = scrollGestureAxisInset(stitchRect.toConfig(), scrollDirection)
        val safeStart = movementStart + movementInset
        val safeEnd = movementEnd - movementInset
        val availableFromFixed = when (anchor.mode) {
            GestureDragMode.START -> if (directionSign > 0f) {
                anchor.fixedAxis - safeStart
            } else {
                safeEnd - anchor.fixedAxis
            }
            GestureDragMode.END -> if (directionSign > 0f) {
                safeEnd - anchor.fixedAxis
            } else {
                anchor.fixedAxis - safeStart
            }
            GestureDragMode.NONE, GestureDragMode.MIDDLE -> 0f
        }
        val allowedMinimum = maxOf(minimum, 1)
        val allowedMaximum = minOf(maximum, floor(availableFromFixed).toInt())
        if (allowedMaximum < allowedMinimum) return

        val parity = anchor.initialDistance and 1
        val parityMinimum = allowedMinimum + ((parity - (allowedMinimum and 1) + 2) % 2)
        val parityMaximum = allowedMaximum - (((allowedMaximum and 1) - parity + 2) % 2)
        if (parityMaximum < parityMinimum) return
        val selectedDistance = (
            ((requestedDistance - parity) / 2f).roundToInt() * 2 + parity
            ).coerceIn(parityMinimum, parityMaximum)
        val selectedCenter = when (anchor.mode) {
            GestureDragMode.START -> anchor.fixedAxis - directionSign * selectedDistance / 2f
            GestureDragMode.END -> anchor.fixedAxis + directionSign * selectedDistance / 2f
            GestureDragMode.NONE, GestureDragMode.MIDDLE -> return
        }
        gestureRect.set(anchor.initialRegion)
        if (scrollDirection.isVertical) {
            val halfHeight = minOf(
                anchor.initialRegion.height() / 2f,
                selectedCenter,
                modelHeight - selectedCenter,
            ).coerceAtLeast(1f)
            gestureRect.top = selectedCenter - halfHeight
            gestureRect.bottom = selectedCenter + halfHeight
        } else {
            val halfWidth = minOf(
                anchor.initialRegion.width() / 2f,
                selectedCenter,
                modelWidth - selectedCenter,
            ).coerceAtLeast(1f)
            gestureRect.left = selectedCenter - halfWidth
            gestureRect.right = selectedCenter + halfWidth
        }
        gestureDistancePx = selectedDistance
    }

    private fun hitTestGesture(viewX: Float, viewY: Float): GestureDragMode {
        val sx = width.toFloat() / modelWidth.coerceAtLeast(1)
        val sy = height.toFloat() / modelHeight.coerceAtLeast(1)
        val geometry = gestureDisplayGeometry(sx, sy) ?: return GestureDragMode.NONE
        return geometry.handles
            .mapNotNull { (mode, handle) ->
                val dx = viewX - handle.center.x
                val dy = viewY - handle.center.y
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared <= handle.touchRadius * handle.touchRadius) {
                    mode to distanceSquared
                } else {
                    null
                }
            }
            .minByOrNull { it.second }
            ?.first
            ?: GestureDragMode.NONE
    }

    private fun availableEditModes(): List<EditMode> = buildList {
        add(EditMode.CAPTURE)
        add(EditMode.STITCH)
        add(EditMode.GESTURE)
        if (repeatRects.getOrNull(0) != null && fixedLayers.getOrNull(0)?.let { layer ->
                fixedBandStretchesWithScroll(scrollDirection, layer.side)
            } == true
        ) add(EditMode.FIXED_1)
        if (repeatRects.getOrNull(1) != null && fixedLayers.getOrNull(1)?.let { layer ->
                fixedBandStretchesWithScroll(scrollDirection, layer.side)
            } == true
        ) add(EditMode.FIXED_2)
    }

    private fun ensureCurrentEditMode() {
        if (editMode in availableEditModes()) return
        editMode = EditMode.CAPTURE
        onEditModeChanged?.invoke(editModeLabel(editMode))
    }

    private fun constrainActive(rect: RectF) {
        when (editMode) {
            EditMode.CAPTURE -> {
                constrain(rect, 0f, 0f, modelWidth.toFloat(), modelHeight.toFloat())
                normalizeStitchForFixedLayers()
                normalizeRepeatRegions()
            }
            EditMode.STITCH -> {
                constrain(
                    rect,
                    captureRect.left,
                    captureRect.top,
                    captureRect.right,
                    captureRect.bottom,
                )
                normalizeStitchForFixedLayers()
                normalizeGestureDistance()
                normalizeRepeatRegions()
            }
            EditMode.GESTURE -> constrain(rect, 0f, 0f, modelWidth.toFloat(), modelHeight.toFloat())
            EditMode.FIXED_1, EditMode.FIXED_2 -> normalizeRepeatRegions()
        }
    }

    private fun normalizeGeometry() {
        constrain(captureRect, 0f, 0f, modelWidth.toFloat(), modelHeight.toFloat())
        constrain(stitchRect, captureRect.left, captureRect.top, captureRect.right, captureRect.bottom)
        normalizeStitchForFixedLayers()
        constrain(gestureRect, 0f, 0f, modelWidth.toFloat(), modelHeight.toFloat())
        normalizeGestureDistance()
        normalizeRepeatRegions()
    }

    private fun normalizeStitchForFixedLayers() {
        val minSize = minOf(MIN_REGION_SIZE_DP * density, captureRect.width(), captureRect.height())
            .coerceAtLeast(1f)
        val sides = effectiveFixedSides()
        val horizontal = normalizeFixedAxis(
            captureStart = captureRect.left,
            captureEnd = captureRect.right,
            requestedStart = stitchRect.left,
            requestedEnd = stitchRect.right,
            leadingFixed = FixedSide.LEFT in sides,
            trailingFixed = FixedSide.RIGHT in sides,
            requestedMinimumSize = minSize,
        )
        val vertical = normalizeFixedAxis(
            captureStart = captureRect.top,
            captureEnd = captureRect.bottom,
            requestedStart = stitchRect.top,
            requestedEnd = stitchRect.bottom,
            leadingFixed = FixedSide.TOP in sides,
            trailingFixed = FixedSide.BOTTOM in sides,
            requestedMinimumSize = minSize,
        )
        stitchRect.set(horizontal.first, vertical.first, horizontal.second, vertical.second)
    }

    /**
     * A stitch edge without a fixed band is also the capture edge. Moving both edges keeps the
     * edited rectangle representable by the template model instead of letting normalization undo
     * the change when the editor is closed.
     */
    private fun resizeSharedStitchEdge(mode: DragMode, dx: Float, dy: Float) {
        val minimumWidth = minOf(MIN_REGION_SIZE_DP * density, modelWidth.toFloat())
            .coerceAtLeast(1f)
        val minimumHeight = minOf(MIN_REGION_SIZE_DP * density, modelHeight.toFloat())
            .coerceAtLeast(1f)
        when (mode) {
            DragMode.LEFT -> {
                val maximum = (stitchRect.right - minimumWidth).coerceAtLeast(0f)
                val edge = (stitchRect.left + dx).coerceIn(0f, maximum)
                captureRect.left = edge
                stitchRect.left = edge
            }
            DragMode.TOP -> {
                val maximum = (stitchRect.bottom - minimumHeight).coerceAtLeast(0f)
                val edge = (stitchRect.top + dy).coerceIn(0f, maximum)
                captureRect.top = edge
                stitchRect.top = edge
            }
            DragMode.RIGHT -> {
                val minimum = (stitchRect.left + minimumWidth)
                    .coerceAtMost(modelWidth.toFloat())
                val edge = (stitchRect.right + dx).coerceIn(minimum, modelWidth.toFloat())
                captureRect.right = edge
                stitchRect.right = edge
            }
            DragMode.BOTTOM -> {
                val minimum = (stitchRect.top + minimumHeight)
                    .coerceAtMost(modelHeight.toFloat())
                val edge = (stitchRect.bottom + dy).coerceIn(minimum, modelHeight.toFloat())
                captureRect.bottom = edge
                stitchRect.bottom = edge
            }
            DragMode.NONE, DragMode.MOVE -> Unit
        }
    }

    private fun hasFixedBandAt(mode: DragMode): Boolean {
        val side = when (mode) {
            DragMode.LEFT -> FixedSide.LEFT
            DragMode.TOP -> FixedSide.TOP
            DragMode.RIGHT -> FixedSide.RIGHT
            DragMode.BOTTOM -> FixedSide.BOTTOM
            DragMode.NONE, DragMode.MOVE -> return false
        }
        return side in effectiveFixedSides()
    }

    /** Mirrors GameCaptureTemplate's legacy fixed-layer inference for pre-layer templates. */
    private fun effectiveFixedSides(): Set<FixedSide> {
        val configured = fixedLayers.mapTo(linkedSetOf<FixedSide>(), FixedLayerConfig::side)
        if (configured.isNotEmpty()) return configured
        return buildSet {
            if (scrollDirection.isVertical) {
                if (stitchRect.top > captureRect.top) add(FixedSide.TOP)
                if (stitchRect.bottom < captureRect.bottom) add(FixedSide.BOTTOM)
            } else {
                if (stitchRect.left > captureRect.left) add(FixedSide.LEFT)
                if (stitchRect.right < captureRect.right) add(FixedSide.RIGHT)
            }
        }
    }

    private fun normalizeFixedAxis(
        captureStart: Float,
        captureEnd: Float,
        requestedStart: Float,
        requestedEnd: Float,
        leadingFixed: Boolean,
        trailingFixed: Boolean,
        requestedMinimumSize: Float,
    ): Pair<Float, Float> {
        val length = (captureEnd - captureStart).coerceAtLeast(1f)
        if (!leadingFixed && !trailingFixed) return captureStart to captureEnd
        val fixedEdgeSpace = if (leadingFixed && trailingFixed) 2f else 1f
        val minimumSize = minOf(
            requestedMinimumSize,
            (length - fixedEdgeSpace).coerceAtLeast(1f),
        )
        return when {
            leadingFixed && trailingFixed -> {
                val maximumStart = (captureEnd - minimumSize - 1f)
                    .coerceAtLeast(captureStart + 1f)
                val start = requestedStart.coerceIn(captureStart + 1f, maximumStart)
                val end = requestedEnd.coerceIn(
                    (start + minimumSize).coerceAtMost(captureEnd - 1f),
                    captureEnd - 1f,
                )
                start to end
            }
            leadingFixed -> {
                val maximumStart = (captureEnd - minimumSize).coerceAtLeast(captureStart + 1f)
                requestedStart.coerceIn(captureStart + 1f, maximumStart) to captureEnd
            }
            else -> {
                val minimumEnd = (captureStart + minimumSize).coerceAtMost(captureEnd - 1f)
                captureStart to requestedEnd.coerceIn(minimumEnd, captureEnd - 1f)
            }
        }
    }

    private fun normalizeRepeatRegions() {
        while (repeatRects.size < fixedLayers.size) repeatRects += null
        while (repeatRects.size > fixedLayers.size) repeatRects.removeAt(repeatRects.lastIndex)
        fixedLayers.forEachIndexed { index, layer ->
            if (fixedBandStretchesWithScroll(scrollDirection, layer.side)) {
                normalizeRepeatRegion(index, layer)
            } else {
                repeatRects[index] = null
            }
        }
    }

    private fun editModeColor(mode: EditMode): Int = when (mode) {
        EditMode.CAPTURE -> capturePaint.color
        EditMode.STITCH -> stitchPaint.color
        EditMode.GESTURE -> gesturePaint.color
        EditMode.FIXED_1 -> fixedLayerPaints[0].color
        EditMode.FIXED_2 -> fixedLayerPaints[1].color
    }

    private fun normalizeRepeatRegion(index: Int, layer: FixedLayerConfig) {
        val band = fixedBandRect(layer.side) ?: run {
            repeatRects[index] = null
            return
        }
        val repeat = repeatRects[index]
            ?: defaultRepeatRect(band, layer).also { repeatRects[index] = it }
        if (fixedBandRunsVertically(layer.side)) {
            repeat.left = band.left
            repeat.right = band.right
            if (band.height() <= 2f) {
                repeat.top = band.top
                repeat.bottom = band.bottom
            } else if (layer.anchorCount == 1 &&
                layer.anchorPosition == FixedAnchorPosition.TRAILING
            ) {
                repeat.top = band.top
                repeat.bottom = repeat.bottom.coerceIn(band.top + 1f, band.bottom - 1f)
            } else {
                repeat.top = repeat.top.coerceIn(band.top + 1f, band.bottom - 1f)
                repeat.bottom = if (layer.anchorCount == 1) {
                    band.bottom
                } else {
                    repeat.bottom.coerceIn(repeat.top + 1f, band.bottom - 1f)
                }
            }
        } else {
            repeat.top = band.top
            repeat.bottom = band.bottom
            if (band.width() <= 2f) {
                repeat.left = band.left
                repeat.right = band.right
            } else if (layer.anchorCount == 1 &&
                layer.anchorPosition == FixedAnchorPosition.TRAILING
            ) {
                repeat.left = band.left
                repeat.right = repeat.right.coerceIn(band.left + 1f, band.right - 1f)
            } else {
                repeat.left = repeat.left.coerceIn(band.left + 1f, band.right - 1f)
                repeat.right = if (layer.anchorCount == 1) {
                    band.right
                } else {
                    repeat.right.coerceIn(repeat.left + 1f, band.right - 1f)
                }
            }
        }
    }

    private fun defaultRepeatRect(band: RectF, layer: FixedLayerConfig): RectF {
        val trailingSingle = layer.anchorCount == 1 &&
            layer.anchorPosition == FixedAnchorPosition.TRAILING
        return if (fixedBandRunsVertically(layer.side)) {
            val anchor = (band.height() * DEFAULT_ANCHOR_RATIO)
                .coerceIn(1f, (band.height() - 1f).coerceAtLeast(1f))
            RectF(
                band.left,
                if (trailingSingle) band.top else band.top + anchor,
                band.right,
                if (layer.anchorCount == 2 || trailingSingle) band.bottom - anchor else band.bottom,
            )
        } else {
            val anchor = (band.width() * DEFAULT_ANCHOR_RATIO)
                .coerceIn(1f, (band.width() - 1f).coerceAtLeast(1f))
            RectF(
                if (trailingSingle) band.left else band.left + anchor,
                band.top,
                if (layer.anchorCount == 2 || trailingSingle) band.right - anchor else band.right,
                band.bottom,
            )
        }
    }

    private fun fixedBandRect(side: FixedSide): RectF? = when (side) {
        FixedSide.TOP -> RectF(
            if (scrollDirection.isVertical) captureRect.left else stitchRect.left,
            captureRect.top,
            if (scrollDirection.isVertical) captureRect.right else stitchRect.right,
            stitchRect.top,
        )
        FixedSide.BOTTOM -> RectF(
            if (scrollDirection.isVertical) captureRect.left else stitchRect.left,
            stitchRect.bottom,
            if (scrollDirection.isVertical) captureRect.right else stitchRect.right,
            captureRect.bottom,
        )
        FixedSide.LEFT -> RectF(
            captureRect.left,
            if (scrollDirection.isVertical) stitchRect.top else captureRect.top,
            stitchRect.left,
            if (scrollDirection.isVertical) stitchRect.bottom else captureRect.bottom,
        )
        FixedSide.RIGHT -> RectF(
            stitchRect.right,
            if (scrollDirection.isVertical) stitchRect.top else captureRect.top,
            captureRect.right,
            if (scrollDirection.isVertical) stitchRect.bottom else captureRect.bottom,
        )
    }.takeIf { it.width() >= 1f && it.height() >= 1f }

    private fun fixedSideShortLabel(side: FixedSide): String = when (side) {
        FixedSide.TOP -> localizedString(R.string.overlay_side_top)
        FixedSide.BOTTOM -> localizedString(R.string.overlay_side_bottom)
        FixedSide.LEFT -> localizedString(R.string.overlay_side_left)
        FixedSide.RIGHT -> localizedString(R.string.overlay_side_right)
    }

    private fun fixedAnchorBlockLabel(position: FixedAnchorPosition, side: FixedSide): String = when (position) {
        FixedAnchorPosition.LEADING -> localizedString(
            if (fixedBandRunsVertically(side)) {
                R.string.overlay_top_fixed_block
            } else {
                R.string.overlay_left_fixed_block
            },
        )
        FixedAnchorPosition.TRAILING -> localizedString(
            if (fixedBandRunsVertically(side)) {
                R.string.overlay_bottom_fixed_block
            } else {
                R.string.overlay_right_fixed_block
            },
        )
    }

    private fun editModeLabel(mode: EditMode): String = localizedString(mode.labelRes)

    private fun localizedString(@StringRes resourceId: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) {
            context.getString(resourceId)
        } else {
            context.getString(resourceId, *formatArgs)
        }

    private fun activeFixedLayer(): FixedLayerConfig? =
        editMode.fixedLayerIndex?.let(fixedLayers::getOrNull)

    private fun activeRect(): RectF? = when (editMode) {
        EditMode.CAPTURE -> captureRect
        EditMode.STITCH -> stitchRect
        EditMode.GESTURE -> null
        EditMode.FIXED_1, EditMode.FIXED_2 ->
            editMode.fixedLayerIndex?.let(repeatRects::getOrNull)
    }

    private fun activeResizeDragModes(): List<DragMode> {
        val repeatLayer = activeFixedLayer()
        if (repeatLayer == null) {
            return listOf(DragMode.LEFT, DragMode.TOP, DragMode.BOTTOM, DragMode.RIGHT)
        }
        val leading = repeatLayer.anchorCount == 2 ||
            repeatLayer.anchorPosition == FixedAnchorPosition.LEADING
        val trailing = repeatLayer.anchorCount == 2 ||
            repeatLayer.anchorPosition == FixedAnchorPosition.TRAILING
        return if (fixedBandRunsVertically(repeatLayer.side)) {
            buildList {
                if (leading) add(DragMode.TOP)
                if (trailing) add(DragMode.BOTTOM)
            }
        } else {
            buildList {
                if (leading) add(DragMode.LEFT)
                if (trailing) add(DragMode.RIGHT)
            }
        }
    }

    private fun hitTest(rect: RectF, x: Float, y: Float): DragMode {
        val viewScaleX = width.coerceAtLeast(1).toFloat() / modelWidth
        val viewScaleY = height.coerceAtLeast(1).toFloat() / modelHeight
        val visibleRect = rect.scaled(viewScaleX, viewScaleY)
        val viewX = x * viewScaleX
        val viewY = y * viewScaleY
        val maximumTouchRadius = RESIZE_HANDLE_TOUCH_RADIUS_DP * density
        val candidates = mutableListOf<Pair<DragMode, Float>>()
        activeResizeDragModes().forEach { mode ->
            resizeHandleFractions(visibleRect, mode).forEach { fraction ->
                val handle = positionedWaterdropGeometry(
                    visibleRect,
                    mode.toResizeHandleEdge() ?: return@forEach,
                    fraction,
                    maximumTouchRadius,
                )
                val dx = viewX - handle.geometry.centerX
                val dy = viewY - handle.geometry.centerY
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared <= handle.touchRadius * handle.touchRadius) {
                    candidates += mode to distanceSquared
                }
            }
        }
        candidates.minByOrNull { it.second }?.let { return it.first }
        return if (rect.contains(x, y)) DragMode.MOVE else DragMode.NONE
    }

    private fun gestureDisplayGeometry(
        sx: Float,
        sy: Float,
        labelObstacles: List<EditorFloatRect> = emptyList(),
    ): GestureDisplayGeometry? {
        if (width <= 0 || height <= 0 || sx <= 0f || sy <= 0f) return null
        val path = currentGesturePath()
        val start = PointF(path.start.x * sx, path.start.y * sy)
        val end = PointF(path.end.x * sx, path.end.y * sy)
        val center = PointF((start.x + end.x) / 2f, (start.y + end.y) / 2f)
        val pathLength = kotlin.math.hypot(end.x - start.x, end.y - start.y)
        if (pathLength <= 0f) return null
        val unitX = (end.x - start.x) / pathLength
        val unitY = (end.y - start.y) / pathLength
        val defaultNormalX = if (scrollDirection.isVertical) 1f else 0f
        val defaultNormalY = if (scrollDirection.isVertical) 0f else 1f

        fun handles(normalX: Float, normalY: Float) = listOf(
            GestureDragMode.START to gestureHandleGeometry(start, normalX, normalY),
            GestureDragMode.MIDDLE to gestureHandleGeometry(center, normalX, normalY),
            GestureDragMode.END to gestureHandleGeometry(end, normalX, normalY),
        )
        val outerPadding = GESTURE_OUTER_PADDING_DP * density
        fun overflow(values: List<Pair<GestureDragMode, GestureHandleGeometry>>): Float =
            values.sumOf { (_, handle) ->
                val bounds = handle.bounds
                (
                    (outerPadding - bounds.left).coerceAtLeast(0f) +
                        (outerPadding - bounds.top).coerceAtLeast(0f) +
                        (bounds.right - (width - outerPadding)).coerceAtLeast(0f) +
                        (bounds.bottom - (height - outerPadding)).coerceAtLeast(0f)
                    ).toDouble()
            }.toFloat()

        val defaultHandles = handles(defaultNormalX, defaultNormalY)
        val oppositeHandles = handles(-defaultNormalX, -defaultNormalY)
        val useOpposite = overflow(oppositeHandles) < overflow(defaultHandles)
        val normalX = if (useOpposite) -defaultNormalX else defaultNormalX
        val normalY = if (useOpposite) -defaultNormalY else defaultNormalY
        val selectedHandles = if (useOpposite) oppositeHandles else defaultHandles
        val distanceText = "${path.distancePx} px"
        val metrics = gestureDistanceTextPaint.fontMetrics
        val labelWidth = gestureDistanceTextPaint.measureText(distanceText) +
            GESTURE_LABEL_PADDING_X_DP * density * 2f
        val labelHeight = metrics.descent - metrics.ascent +
            GESTURE_LABEL_PADDING_Y_DP * density * 2f
        val labelBounds = placeGestureDistanceLabel(
            start = start,
            end = end,
            center = center,
            unitX = unitX,
            unitY = unitY,
            normalX = normalX,
            normalY = normalY,
            labelWidth = labelWidth,
            labelHeight = labelHeight,
            handleBounds = selectedHandles.map { it.second.bounds },
            obstacles = labelObstacles,
        )
        return GestureDisplayGeometry(
            start = start,
            end = end,
            unitX = unitX,
            unitY = unitY,
            distanceText = distanceText,
            distanceLabelBounds = labelBounds,
            handles = selectedHandles,
        )
    }

    private fun gestureHandleGeometry(
        tip: PointF,
        normalX: Float,
        normalY: Float,
    ): GestureHandleGeometry {
        val inset = GESTURE_HANDLE_INSET_DP * density
        val radius = GESTURE_HANDLE_RADIUS_DP * density
        val originalCenter = PointF(tip.x + normalX * inset, tip.y + normalY * inset)
        val placement = regionHandleTouchablePlacement(
            centerX = originalCenter.x,
            centerY = originalCenter.y,
            requestedTouchRadius = GESTURE_HANDLE_TOUCH_RADIUS_DP * density,
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            insets = handleTouchableInsets,
        )
        val translatedTip = PointF(tip.x + placement.offsetX, tip.y + placement.offsetY)
        val center = PointF(
            originalCenter.x + placement.offsetX,
            originalCenter.y + placement.offsetY,
        )
        val tangentX = -normalY
        val tangentY = normalX
        val sideA = PointF(center.x + tangentX * radius, center.y + tangentY * radius)
        val sideB = PointF(center.x - tangentX * radius, center.y - tangentY * radius)
        val far = PointF(center.x + normalX * radius, center.y + normalY * radius)
        val bounds = RectF(
            minOf(translatedTip.x, sideA.x, sideB.x, far.x),
            minOf(translatedTip.y, sideA.y, sideB.y, far.y),
            maxOf(translatedTip.x, sideA.x, sideB.x, far.x),
            maxOf(translatedTip.y, sideA.y, sideB.y, far.y),
        )
        return GestureHandleGeometry(
            translatedTip,
            center,
            normalX,
            normalY,
            radius,
            placement.touchRadius,
            bounds,
        )
    }

    private fun placeGestureDistanceLabel(
        start: PointF,
        end: PointF,
        center: PointF,
        unitX: Float,
        unitY: Float,
        normalX: Float,
        normalY: Float,
        labelWidth: Float,
        labelHeight: Float,
        handleBounds: List<RectF>,
        obstacles: List<EditorFloatRect>,
    ): RectF {
        val outerPadding = GESTURE_OUTER_PADDING_DP * density
        val bounds = EditorFloatRect(
            outerPadding,
            outerPadding,
            (width - outerPadding).coerceAtLeast(outerPadding + 1f),
            (height - outerPadding).coerceAtLeast(outerPadding + 1f),
        )
        return placeGestureDistanceLabelGeometry(
            start = EditorPoint(start.x, start.y),
            end = EditorPoint(end.x, end.y),
            center = EditorPoint(center.x, center.y),
            unitX = unitX,
            unitY = unitY,
            normalX = normalX,
            normalY = normalY,
            labelWidth = labelWidth,
            labelHeight = labelHeight,
            viewport = bounds,
            gap = GESTURE_LABEL_GAP_DP * density,
            obstacles = buildList {
                addAll(obstacles)
                labelOcclusionRects.mapTo(this) { it.toEditorRect() }
                handleBounds.mapTo(this) { it.toEditorRect() }
            },
        ).bounds.toAndroidRect()
    }

    private fun drawGesturePath(canvas: Canvas, geometry: GestureDisplayGeometry?) {
        geometry ?: return
        val active = !opacityPreviewMode && editMode == EditMode.GESTURE
        val alpha = regionOutlineAlpha(
            nonFocusedOpacity = nonFocusedOpacity,
            active = active,
            forceOpaque = opacityPreviewMode,
        )
        val previousStrokeWidth = gesturePaint.strokeWidth
        val previousStrokeCap = gesturePaint.strokeCap
        gesturePaint.alpha = alpha
        gesturePaint.strokeWidth = GESTURE_LINE_WIDTH_DP * density
        gesturePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(
            geometry.start.x,
            geometry.start.y,
            geometry.end.x,
            geometry.end.y,
            gesturePaint,
        )
        gesturePaint.strokeWidth = previousStrokeWidth
        gesturePaint.strokeCap = previousStrokeCap

        gestureFillPaint.alpha = alpha
        canvas.drawCircle(
            geometry.start.x,
            geometry.start.y,
            GESTURE_START_RADIUS_DP * density,
            gestureFillPaint,
        )
        val arrowLength = GESTURE_ARROW_LENGTH_DP * density
        val arrowHalfWidth = GESTURE_ARROW_HALF_WIDTH_DP * density
        val perpendicularX = -geometry.unitY
        val perpendicularY = geometry.unitX
        val arrowBaseX = geometry.end.x - geometry.unitX * arrowLength
        val arrowBaseY = geometry.end.y - geometry.unitY * arrowLength
        val arrow = Path().apply {
            moveTo(geometry.end.x, geometry.end.y)
            lineTo(
                arrowBaseX + perpendicularX * arrowHalfWidth,
                arrowBaseY + perpendicularY * arrowHalfWidth,
            )
            lineTo(
                arrowBaseX - perpendicularX * arrowHalfWidth,
                arrowBaseY - perpendicularY * arrowHalfWidth,
            )
            close()
        }
        canvas.drawPath(arrow, gestureFillPaint)
        if (active) {
            geometry.handles.forEach { (_, handle) -> drawGestureWaterdrop(canvas, handle) }
        }

        val labelAlphas = regionLabelAlphas(
            nonFocusedOpacity = nonFocusedOpacity,
            selected = active,
            forceOpaque = opacityPreviewMode,
        )
        if (geometry.distanceLabelBounds.width() <= 0f ||
            geometry.distanceLabelBounds.height() <= 0f
        ) {
            return
        }
        labelBackgroundPaint.alpha = labelAlphas.background
        canvas.drawRoundRect(
            geometry.distanceLabelBounds,
            GESTURE_LABEL_CORNER_DP * density,
            GESTURE_LABEL_CORNER_DP * density,
            labelBackgroundPaint,
        )
        gestureDistanceTextPaint.color = withAlpha(gesturePaint.color, labelAlphas.text)
        val metrics = gestureDistanceTextPaint.fontMetrics
        canvas.drawText(
            geometry.distanceText,
            geometry.distanceLabelBounds.centerX() -
                gestureDistanceTextPaint.measureText(geometry.distanceText) / 2f,
            geometry.distanceLabelBounds.centerY() - (metrics.ascent + metrics.descent) / 2f,
            gestureDistanceTextPaint,
        )
    }

    private fun drawGestureWaterdrop(canvas: Canvas, geometry: GestureHandleGeometry) {
        val tip = geometry.tip
        val center = geometry.center
        val inwardX = geometry.normalX
        val inwardY = geometry.normalY
        val tangentX = -inwardY
        val tangentY = inwardX
        val centerDistance = kotlin.math.hypot(center.x - tip.x, center.y - tip.y)
        val radius = geometry.radius
        fun point(along: Float, across: Float) = PointF(
            tip.x + inwardX * along + tangentX * across,
            tip.y + inwardY * along + tangentY * across,
        )
        val neckA = point(centerDistance * 0.42f, -radius * 0.12f)
        val shoulderA = point(centerDistance - radius, -radius)
        val sideA = point(centerDistance, -radius)
        val farShoulderA = point(centerDistance + radius * 0.55f, -radius)
        val farA = point(centerDistance + radius, -radius * 0.55f)
        val far = point(centerDistance + radius, 0f)
        val farB = point(centerDistance + radius, radius * 0.55f)
        val farShoulderB = point(centerDistance + radius * 0.55f, radius)
        val sideB = point(centerDistance, radius)
        val shoulderB = point(centerDistance - radius, radius)
        val neckB = point(centerDistance * 0.42f, radius * 0.12f)
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            cubicTo(neckA.x, neckA.y, shoulderA.x, shoulderA.y, sideA.x, sideA.y)
            cubicTo(farShoulderA.x, farShoulderA.y, farA.x, farA.y, far.x, far.y)
            cubicTo(farB.x, farB.y, farShoulderB.x, farShoulderB.y, sideB.x, sideB.y)
            cubicTo(shoulderB.x, shoulderB.y, neckB.x, neckB.y, tip.x, tip.y)
            close()
        }
        canvas.drawPath(path, gestureFillPaint)
    }

    private fun drawRegionOutline(
        canvas: Canvas,
        rect: RectF,
        paint: Paint,
        active: Boolean,
        allowLeft: Boolean = true,
        allowTop: Boolean = true,
        allowRight: Boolean = true,
        allowBottom: Boolean = true,
        forceOpaque: Boolean = false,
    ) {
        val previousAlpha = paint.alpha
        paint.alpha = regionOutlineAlpha(nonFocusedOpacity, active, forceOpaque)
        paint.strokeWidth = (if (active) 3f else 2f) * density
        canvas.drawRect(rect, paint)
        if (active) {
            fun drawHandles(mode: DragMode, allowed: Boolean) {
                if (!allowed) return
                resizeHandleFractions(rect, mode).forEach { fraction ->
                    drawWaterdropResizeHandle(canvas, rect, paint, mode, fraction)
                }
            }
            drawHandles(DragMode.LEFT, allowLeft)
            drawHandles(DragMode.TOP, allowTop)
            drawHandles(DragMode.RIGHT, allowRight)
            drawHandles(DragMode.BOTTOM, allowBottom)
        }
        paint.alpha = previousAlpha
    }

    private fun resizeHandleFractions(rect: RectF, mode: DragMode): List<Float> {
        val edge = mode.toResizeHandleEdge() ?: return emptyList()
        return regionHandleFractions(
            rect = rect.toEditorRect(),
            edge = edge,
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            cutoutInsets = displayCutoutInsets,
        )
    }

    private fun drawWaterdropResizeHandle(
        canvas: Canvas,
        rect: RectF,
        paint: Paint,
        mode: DragMode,
        fraction: Float,
    ) {
        val edge = mode.toResizeHandleEdge() ?: return
        val geometry = positionedWaterdropGeometry(
            rect,
            edge,
            fraction,
            RESIZE_HANDLE_TOUCH_RADIUS_DP * density,
        ).geometry
        val tip = PointF(geometry.tipX, geometry.tipY)
        val center = PointF(geometry.centerX, geometry.centerY)
        val inwardX = when (mode) {
            DragMode.LEFT -> 1f
            DragMode.RIGHT -> -1f
            else -> 0f
        }
        val inwardY = when (mode) {
            DragMode.TOP -> 1f
            DragMode.BOTTOM -> -1f
            else -> 0f
        }
        val tangentX = -inwardY
        val tangentY = inwardX
        val centerDistance = kotlin.math.hypot(center.x - tip.x, center.y - tip.y)
        val radius = geometry.radius
        fun point(along: Float, across: Float) = PointF(
            tip.x + inwardX * along + tangentX * across,
            tip.y + inwardY * along + tangentY * across,
        )
        val neckA = point(centerDistance * 0.42f, -radius * 0.12f)
        val shoulderA = point(centerDistance - radius, -radius)
        val sideA = point(centerDistance, -radius)
        val farShoulderA = point(centerDistance + radius * 0.55f, -radius)
        val farA = point(centerDistance + radius, -radius * 0.55f)
        val far = point(centerDistance + radius, 0f)
        val farB = point(centerDistance + radius, radius * 0.55f)
        val farShoulderB = point(centerDistance + radius * 0.55f, radius)
        val sideB = point(centerDistance, radius)
        val shoulderB = point(centerDistance - radius, radius)
        val neckB = point(centerDistance * 0.42f, radius * 0.12f)
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            cubicTo(neckA.x, neckA.y, shoulderA.x, shoulderA.y, sideA.x, sideA.y)
            cubicTo(farShoulderA.x, farShoulderA.y, farA.x, farA.y, far.x, far.y)
            cubicTo(farB.x, farB.y, farShoulderB.x, farShoulderB.y, sideB.x, sideB.y)
            cubicTo(shoulderB.x, shoulderB.y, neckB.x, neckB.y, tip.x, tip.y)
            close()
        }
        val previousStyle = paint.style
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        paint.style = previousStyle
    }

    private fun DragMode.toResizeHandleEdge(): RegionResizeHandleEdge? = when (this) {
        DragMode.LEFT -> RegionResizeHandleEdge.LEFT
        DragMode.TOP -> RegionResizeHandleEdge.TOP
        DragMode.RIGHT -> RegionResizeHandleEdge.RIGHT
        DragMode.BOTTOM -> RegionResizeHandleEdge.BOTTOM
        DragMode.NONE, DragMode.MOVE -> null
    }

    private data class PositionedWaterdropGeometry(
        val geometry: RegionWaterdropGeometry,
        val touchRadius: Float,
    )

    private fun positionedWaterdropGeometry(
        rect: RectF,
        edge: RegionResizeHandleEdge,
        fraction: Float = 0.5f,
        maximumTouchRadius: Float = RESIZE_HANDLE_TOUCH_RADIUS_DP * density,
    ): PositionedWaterdropGeometry {
        val base = regionWaterdropGeometry(
            rect = rect.toEditorRect(),
            edge = edge,
            requestedInset = RESIZE_HANDLE_INSET_DP * density,
            maximumRadius = WATERDROP_MAX_RADIUS_DP * density,
            minimumRadius = WATERDROP_MIN_RADIUS_DP * density,
            edgeFraction = fraction,
        )
        val actualInset = kotlin.math.hypot(
            base.centerX - base.tipX,
            base.centerY - base.tipY,
        )
        val placement = regionHandleTouchablePlacement(
            centerX = base.centerX,
            centerY = base.centerY,
            requestedTouchRadius = minOf(
                maximumTouchRadius,
                actualInset * MAX_HANDLE_RADIUS_TO_INSET_RATIO,
            ),
            viewportWidth = width.toFloat(),
            viewportHeight = height.toFloat(),
            insets = handleTouchableInsets,
        )
        val offsetX = placement.offsetX
        val offsetY = placement.offsetY
        return PositionedWaterdropGeometry(
            geometry = base.copy(
                tipX = base.tipX + offsetX,
                tipY = base.tipY + offsetY,
                centerX = base.centerX + offsetX,
                centerY = base.centerY + offsetY,
                bounds = EditorFloatRect(
                    base.bounds.left + offsetX,
                    base.bounds.top + offsetY,
                    base.bounds.right + offsetX,
                    base.bounds.bottom + offsetY,
                ),
            ),
            touchRadius = placement.touchRadius,
        )
    }

    private fun waterdropGeometry(
        rect: RectF,
        edge: RegionResizeHandleEdge,
        fraction: Float = 0.5f,
    ): RegionWaterdropGeometry = positionedWaterdropGeometry(rect, edge, fraction).geometry

    private fun activeHandleObstacles(
        gestureGeometry: GestureDisplayGeometry?,
    ): List<EditorFloatRect> {
        val sx = width.toFloat() / modelWidth
        val sy = height.toFloat() / modelHeight
        val obstacles = mutableListOf<EditorFloatRect>()
        gestureGeometry?.let { geometry ->
            if (geometry.distanceLabelBounds.width() > 0f &&
                geometry.distanceLabelBounds.height() > 0f
            ) {
                obstacles += geometry.distanceLabelBounds.toEditorRect()
            }
            if (!opacityPreviewMode && editMode == EditMode.GESTURE) {
                geometry.handles.mapTo(obstacles) { it.second.bounds.toEditorRect() }
            }
        }
        if (opacityPreviewMode || editMode == EditMode.GESTURE) return obstacles
        val visibleRect = activeRect()?.scaled(sx, sy) ?: return obstacles
        val repeatLayer = activeFixedLayer()
        val allowLeftRight = repeatLayer?.let { !fixedBandRunsVertically(it.side) } ?: true
        val allowTopBottom = repeatLayer?.let { fixedBandRunsVertically(it.side) } ?: true
        return obstacles.apply {
            if (allowLeftRight) {
                resizeHandleFractions(visibleRect, DragMode.LEFT).forEach { fraction ->
                    add(waterdropGeometry(visibleRect, RegionResizeHandleEdge.LEFT, fraction).bounds)
                }
                resizeHandleFractions(visibleRect, DragMode.RIGHT).forEach { fraction ->
                    add(waterdropGeometry(visibleRect, RegionResizeHandleEdge.RIGHT, fraction).bounds)
                }
            }
            if (allowTopBottom) {
                resizeHandleFractions(visibleRect, DragMode.TOP).forEach { fraction ->
                    add(waterdropGeometry(visibleRect, RegionResizeHandleEdge.TOP, fraction).bounds)
                }
                resizeHandleFractions(visibleRect, DragMode.BOTTOM).forEach { fraction ->
                    add(waterdropGeometry(visibleRect, RegionResizeHandleEdge.BOTTOM, fraction).bounds)
                }
            }
        }
    }

    private fun drawRegionLabels(
        canvas: Canvas,
        labels: List<RegionLabel>,
        frameRects: List<RectF>,
        gestureGeometry: GestureDisplayGeometry?,
    ) {
        if (labels.isEmpty() || width <= 0 || height <= 0) return
        val outerPadding = 5f * density
        val innerPaddingX = 7f * density
        val innerPaddingY = 4f * density
        val gap = 5f * density
        val markerDiameter = 7f * density
        val markerGap = 5f * density
        val bounds = RectF(outerPadding, outerPadding, width - outerPadding, height - outerPadding)
        val infos = labels.map { label ->
            "${label.name}  ${label.model.width().roundToInt()}×${label.model.height().roundToInt()}"
        }
        val textStart = innerPaddingX + markerDiameter + markerGap
        val maximumTextWidth = (bounds.width() - textStart - innerPaddingX).coerceAtLeast(1f)
        textPaint.textSize = 12f * density
        val minimumTextSize = 9f * density
        while (textPaint.textSize > minimumTextSize &&
            infos.maxOf { textPaint.measureText(it) } > maximumTextWidth
        ) {
            textPaint.textSize = (textPaint.textSize - density).coerceAtLeast(minimumTextSize)
        }
        val metrics = textPaint.fontMetrics
        val boxHeight = metrics.descent - metrics.ascent + innerPaddingY * 2
        val labelWidths = labels.indices.map { index ->
            textStart + textPaint.measureText(infos[index]) + innerPaddingX
        }
        val labelRotations = labels.indices.map { index ->
            editorLabelRotationForContainer(
                defaultRotated = false,
                unrotatedWidth = labelWidths[index],
                unrotatedHeight = boxHeight,
                container = labels[index].container.insetCopy(gap).toEditorRect(),
            )
        }
        val requests = labels.indices.map { index ->
            val label = labels[index]
            val rotated = labelRotations[index]
            ContainedEditorLabelRequest(
                anchor = label.rect.insetCopy(gap).toEditorRect(),
                container = label.container.insetCopy(gap).toEditorRect(),
                width = if (rotated) boxHeight else labelWidths[index],
            height = if (rotated) labelWidths[index] else boxHeight,
            obstacles = frameRects
                .flatMap { frame ->
                    val editorFrame = frame.toEditorRect()
                    val allowedEdges = label.allowedFrameBorders
                        .filter { allowance -> allowance.frame == editorFrame }
                        .mapTo(mutableSetOf()) { it.edge }
                    editorFrameBorderObstacles(editorFrame, 2f * density, allowedEdges)
                },
            )
        }
        val placements = placeContainedEditorLabels(
            requests = requests,
            bounds = bounds.toEditorRect(),
            gap = gap,
            obstacles = buildList {
                labelOcclusionRects.mapTo(this) { it.toEditorRect() }
                addAll(activeHandleObstacles(gestureGeometry))
            },
        )
        labels.forEachIndexed { index, label ->
            val placed = placements[index].toAndroidRect()
            if (placed.width() <= 0f || placed.height() <= 0f) return@forEachIndexed
            val alphas = regionLabelAlphas(
                nonFocusedOpacity = nonFocusedOpacity,
                selected = label.selected,
                forceOpaque = label.forceOpaque,
            )
            val checkpoint = canvas.save()
            val rotated = labelRotations[index]
            if (rotated) {
                canvas.translate(placed.right, placed.top)
                canvas.rotate(90f)
            } else {
                canvas.translate(placed.left, placed.top)
            }
            val visibleWidth = if (rotated) placed.height() else placed.width()
            val visibleHeight = if (rotated) placed.width() else placed.height()
            canvas.clipRect(0f, 0f, visibleWidth, visibleHeight)
            labelBackgroundPaint.alpha = alphas.background
            canvas.drawRoundRect(
                RectF(0f, 0f, visibleWidth, visibleHeight),
                6f * density,
                6f * density,
                labelBackgroundPaint,
            )
            labelMarkerPaint.color = label.color
            labelMarkerPaint.alpha = alphas.marker
            canvas.drawCircle(
                innerPaddingX + markerDiameter / 2f,
                visibleHeight / 2f,
                markerDiameter / 2f,
                labelMarkerPaint,
            )
            textPaint.color = withAlpha(label.color, alphas.text)
            canvas.drawText(
                ellipsizeLabel(
                    text = infos[index],
                    availableWidth = visibleWidth - textStart - innerPaddingX,
                ),
                textStart,
                innerPaddingY - metrics.ascent,
                textPaint,
            )
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun ellipsizeLabel(text: String, availableWidth: Float): String {
        if (availableWidth <= 0f) return ""
        if (textPaint.measureText(text) <= availableWidth) return text
        val suffix = "..."
        val suffixWidth = textPaint.measureText(suffix)
        if (suffixWidth >= availableWidth) return ""
        val count = textPaint.breakText(text, true, availableWidth - suffixWidth, null)
        return text.take(count) + suffix
    }

    private fun constrain(
        rect: RectF,
        boundLeft: Float,
        boundTop: Float,
        boundRight: Float,
        boundBottom: Float,
    ) {
        val minWidth = minOf(MIN_REGION_SIZE_DP * density, boundRight - boundLeft).coerceAtLeast(1f)
        val minHeight = minOf(MIN_REGION_SIZE_DP * density, boundBottom - boundTop).coerceAtLeast(1f)
        if (rect.width() < minWidth) rect.right = rect.left + minWidth
        if (rect.height() < minHeight) rect.bottom = rect.top + minHeight
        if (rect.left < boundLeft) rect.offset(boundLeft - rect.left, 0f)
        if (rect.top < boundTop) rect.offset(0f, boundTop - rect.top)
        if (rect.right > boundRight) rect.offset(boundRight - rect.right, 0f)
        if (rect.bottom > boundBottom) rect.offset(0f, boundBottom - rect.bottom)
        rect.left = rect.left.coerceIn(boundLeft, (boundRight - 1f).coerceAtLeast(boundLeft))
        rect.top = rect.top.coerceIn(boundTop, (boundBottom - 1f).coerceAtLeast(boundTop))
        rect.right = rect.right.coerceIn(rect.left + 1f, boundRight)
        rect.bottom = rect.bottom.coerceIn(rect.top + 1f, boundBottom)
    }

    private fun notifyChanged() {
        normalizeGeometry()
        ensureCurrentEditMode()
        val updatedLayers = fixedLayers.mapIndexed { index, layer ->
            layer.copy(repeatRegion = repeatRects.getOrNull(index)?.toConfig())
        }
        fixedLayers = updatedLayers
        onRegionsChanged?.invoke(
            captureRect.toConfig(),
            stitchRect.toConfig(),
            gestureRect.toConfig(),
            gestureDistancePx,
            updatedLayers,
        )
    }

    private data class GestureHandleGeometry(
        val tip: PointF,
        val center: PointF,
        val normalX: Float,
        val normalY: Float,
        val radius: Float,
        val touchRadius: Float,
        val bounds: RectF,
    )

    private data class GestureEndpointDragAnchor(
        val mode: GestureDragMode,
        val pointerAxis: Float,
        val fixedAxis: Float,
        val initialDistance: Int,
        val initialRegion: RectF,
    )

    private data class GestureDisplayGeometry(
        val start: PointF,
        val end: PointF,
        val unitX: Float,
        val unitY: Float,
        val distanceText: String,
        val distanceLabelBounds: RectF,
        val handles: List<Pair<GestureDragMode, GestureHandleGeometry>>,
    )

    private data class RegionLabel(
        val name: String,
        val rect: RectF,
        val model: RectF,
        val color: Int,
        val selected: Boolean = false,
        val forceOpaque: Boolean = false,
        val container: RectF = rect,
        val allowedFrameBorders: List<EditorFrameBorderAllowance> = emptyList(),
    )

    private fun RectConfig.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    private fun RectF.toEditorRect() = EditorFloatRect(left, top, right, bottom)
    private fun RectF.insetCopy(amount: Float): RectF {
        val safeAmount = amount.coerceAtLeast(0f)
        val insetX = minOf(safeAmount, (width() - 1f).coerceAtLeast(0f) / 2f)
        val insetY = minOf(safeAmount, (height() - 1f).coerceAtLeast(0f) / 2f)
        return RectF(left + insetX, top + insetY, right - insetX, bottom - insetY)
    }
    private fun EditorFloatRect.toAndroidRect() = RectF(left, top, right, bottom)
    private fun RectF.toConfig() = RectConfig(
        left.roundToInt(),
        top.roundToInt(),
        right.roundToInt(),
        bottom.roundToInt(),
    )
    private fun RectF.scaled(sx: Float, sy: Float) = RectF(left * sx, top * sy, right * sx, bottom * sy)
    private fun stroke(colorValue: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private companion object {
        const val DEFAULT_ANCHOR_RATIO = 0.20f
        const val MIN_REGION_SIZE_DP = 56f
        const val RESIZE_HANDLE_INSET_DP = 20f
        const val RESIZE_HANDLE_TOUCH_RADIUS_DP = 14f
        const val MAX_HANDLE_RADIUS_TO_INSET_RATIO = 0.70f
        const val WATERDROP_MAX_RADIUS_DP = 8f
        const val WATERDROP_MIN_RADIUS_DP = 2f
        const val MIN_GESTURE_DISTANCE_PX = 48
        const val GESTURE_LINE_WIDTH_DP = 1.5f
        const val GESTURE_START_RADIUS_DP = 5.5f
        const val GESTURE_ARROW_LENGTH_DP = 12f
        const val GESTURE_ARROW_HALF_WIDTH_DP = 6f
        const val GESTURE_HANDLE_INSET_DP = 20f
        const val GESTURE_HANDLE_RADIUS_DP = 8f
        const val GESTURE_HANDLE_TOUCH_RADIUS_DP = 14f
        const val GESTURE_LABEL_PADDING_X_DP = 7f
        const val GESTURE_LABEL_PADDING_Y_DP = 4f
        const val GESTURE_LABEL_GAP_DP = 6f
        const val GESTURE_LABEL_CORNER_DP = 6f
        const val GESTURE_OUTER_PADDING_DP = 5f
    }
}
