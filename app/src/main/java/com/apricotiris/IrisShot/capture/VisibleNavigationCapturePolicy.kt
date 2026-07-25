package com.apricotiris.IrisShot.capture

import com.apricotiris.IrisShot.core.model.FixedLayerConfig
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.RectConfig

internal fun captureNavigationTemplateForSession(
    template: GameCaptureTemplate,
    visibleNavigationContentBounds: RectConfig,
): GameCaptureTemplate? = navigationInclusiveTemplateForSession(
    template = template,
    visibleNavigationContentBounds = visibleNavigationContentBounds,
)

/**
 * Keeps physical system-navigation pixels in the capture while excluding them from motion
 * matching and gesture dispatch. A navigation strip is merged into an existing fixed layer on the
 * same side, or becomes a session-only fixed layer when that side was not already configured.
 */
internal fun navigationInclusiveTemplateForSession(
    template: GameCaptureTemplate,
    visibleNavigationContentBounds: RectConfig,
): GameCaptureTemplate? {
    val normalized = template.withNormalizedRegions()
    val display = RectConfig.full(
        normalized.screenWidth.coerceAtLeast(1),
        normalized.screenHeight.coerceAtLeast(1),
    )
    val content = visibleNavigationContentBounds
        .clampTo(display.width, display.height)
        .takeUnless { it.isEmpty }
        ?: return null
    val storedGeometryContent = normalized.navigationContentBoundsAtGeometry
        ?.clampTo(display.width, display.height)
        ?.takeUnless { it.isEmpty }
    val geometryContent = storedGeometryContent ?: display
    val capture = captureRegionIncludingNavigationBoundary(
        capture = normalized.captureRegion,
        content = content,
        geometryContent = storedGeometryContent,
        display = display,
    )
    val contentInsideCapture = capture.intersect(content) ?: return null
    val navigationSides = navigationSidesOverlappingCapture(capture, content)
    val authoredLayers = persistentFixedLayers(normalized)
    val authoredSides = authoredLayers.map(FixedLayerConfig::side).toSet()

    val adjustedStitch = if (storedGeometryContent == null) {
        normalized.stitchRegion
    } else {
        adaptStitchToNavigationBaseline(
            stitch = normalized.stitchRegion,
            capture = capture,
            geometryContent = geometryContent,
            liveContent = content,
            existingSides = authoredSides,
        )
    }
    val stitch = adjustedStitch.intersect(contentInsideCapture) ?: return null
    val gesture = normalized.gestureRegion.intersect(content) ?: return null
    val fixedLayers = authoredLayers.map { layer ->
        remapFixedLayerRepeatRegion(
            layer = layer,
            capture = capture,
            stitch = stitch,
            scrollDirectionIsVertical = normalized.scrollDirection.isVertical,
        )
    } + navigationSides
        .filterNot(authoredSides::contains)
        .map(::FixedLayerConfig)
    if (fixedLayers.size > 2) return null

    val inclusive = normalized.copy(
        captureRegion = capture,
        stitchRegion = stitch,
        fixedLayers = fixedLayers,
        gestureRegion = gesture,
        navigationContentBoundsAtGeometry = content.takeIf { storedGeometryContent != null },
    ).withNormalizedRegions()

    return inclusive.takeIf {
        it.captureRegion == capture &&
            content.contains(it.stitchRegion) &&
            stitch.contains(it.stitchRegion) &&
            content.contains(it.gestureRegion) &&
            gesture.contains(it.gestureRegion) &&
            navigationSides.all { side ->
                it.fixedLayers.any { layer -> layer.side == side } &&
                    it.fixedBandRegion(side) != null
            }
    }
}

/**
 * Keeps editable geometry in physical-display coordinates without storing a navigation-only fixed
 * layer. Learning clips this template to app content; a real capture reconstructs the live system
 * strip as a session-only fixed layer. User-authored fixed sides remain available when navigation
 * is hidden.
 */
internal fun navigationTemplateForPersistence(
    template: GameCaptureTemplate,
    visibleNavigationContentBounds: RectConfig,
): GameCaptureTemplate? {
    val normalized = template.withNormalizedRegions()
    val display = RectConfig.full(
        normalized.screenWidth.coerceAtLeast(1),
        normalized.screenHeight.coerceAtLeast(1),
    )
    val content = visibleNavigationContentBounds
        .clampTo(display.width, display.height)
        .takeUnless { it.isEmpty }
        ?: return null
    val storedGeometryContent = normalized.navigationContentBoundsAtGeometry
        ?.clampTo(display.width, display.height)
        ?.takeUnless { it.isEmpty }
    val capture = captureRegionIncludingNavigationBoundary(
        capture = normalized.captureRegion,
        content = content,
        geometryContent = storedGeometryContent,
        display = display,
    )
    val authoredLayers = persistentFixedLayers(
        template = normalized,
        fallbackGeometryContent = content,
    )
    val contentInsideCapture = capture.intersect(content) ?: return null
    val adjustedStitch = if (storedGeometryContent == null) {
        normalized.stitchRegion
    } else {
        adaptStitchToNavigationBaseline(
            stitch = normalized.stitchRegion,
            capture = capture,
            geometryContent = storedGeometryContent,
            liveContent = content,
            existingSides = authoredLayers.map(FixedLayerConfig::side).toSet(),
        )
    }
    val stitch = adjustedStitch.intersect(contentInsideCapture) ?: return null
    val gesture = normalized.gestureRegion.intersect(content) ?: return null
    val fixedLayers = authoredLayers.map { layer ->
        remapFixedLayerRepeatRegion(
            layer = layer,
            capture = capture,
            stitch = stitch,
            scrollDirectionIsVertical = normalized.scrollDirection.isVertical,
        )
    }
    val persisted = normalized.copy(
        captureRegion = capture,
        stitchRegion = stitch,
        fixedLayers = fixedLayers,
        gestureRegion = gesture,
        navigationContentBoundsAtGeometry = content,
    ).withNormalizedRegions()
    val persistedSides = persisted.fixedLayers.map(FixedLayerConfig::side)
    return persisted.takeIf {
        it.captureRegion == capture &&
            content.contains(it.stitchRegion) &&
            stitch.contains(it.stitchRegion) &&
            content.contains(it.gestureRegion) &&
            gesture.contains(it.gestureRegion) &&
            persistedSides == fixedLayers.map(FixedLayerConfig::side) &&
            it.navigationContentBoundsAtGeometry == content
    }
}

/**
 * Restores a physical display edge when an older template reaches the current navigation band or
 * exactly matches a recorded app-content edge. The perpendicular axis must cover the complete
 * content span, which distinguishes a former inset/full-content selection from a deliberate
 * partial crop inside the app. Accepting an edge already inside the live band also handles a
 * navigation bar that grew after a baseline-less legacy template was saved. Recorded bounds stay
 * exact so a new crop between an old and a larger live content area is not expanded accidentally.
 */
private fun captureRegionIncludingNavigationBoundary(
    capture: RectConfig,
    content: RectConfig,
    geometryContent: RectConfig?,
    display: RectConfig,
): RectConfig {
    fun spansHorizontally(bounds: RectConfig): Boolean =
        capture.left <= bounds.left && capture.right >= bounds.right
    fun spansVertically(bounds: RectConfig): Boolean =
        capture.top <= bounds.top && capture.bottom >= bounds.bottom
    fun expandsLeft(bounds: RectConfig, acceptInsideBand: Boolean): Boolean =
        bounds.left > display.left &&
            (capture.left == bounds.left || acceptInsideBand && capture.left < bounds.left) &&
            spansVertically(bounds)
    fun expandsTop(bounds: RectConfig, acceptInsideBand: Boolean): Boolean =
        bounds.top > display.top &&
            (capture.top == bounds.top || acceptInsideBand && capture.top < bounds.top) &&
            spansHorizontally(bounds)
    fun expandsRight(bounds: RectConfig, acceptInsideBand: Boolean): Boolean =
        bounds.right < display.right &&
            (capture.right == bounds.right || acceptInsideBand && capture.right > bounds.right) &&
            spansVertically(bounds)
    fun expandsBottom(bounds: RectConfig, acceptInsideBand: Boolean): Boolean =
        bounds.bottom < display.bottom &&
            (capture.bottom == bounds.bottom || acceptInsideBand && capture.bottom > bounds.bottom) &&
            spansHorizontally(bounds)
    val expandLeft = expandsLeft(content, acceptInsideBand = true) ||
        geometryContent?.let { expandsLeft(it, acceptInsideBand = false) } == true
    val expandTop = expandsTop(content, acceptInsideBand = true) ||
        geometryContent?.let { expandsTop(it, acceptInsideBand = false) } == true
    val expandRight = expandsRight(content, acceptInsideBand = true) ||
        geometryContent?.let { expandsRight(it, acceptInsideBand = false) } == true
    val expandBottom = expandsBottom(content, acceptInsideBand = true) ||
        geometryContent?.let { expandsBottom(it, acceptInsideBand = false) } == true
    return capture.copy(
        left = if (expandLeft) display.left else capture.left,
        top = if (expandTop) display.top else capture.top,
        right = if (expandRight) display.right else capture.right,
        bottom = if (expandBottom) display.bottom else capture.bottom,
    )
}

private fun adaptStitchToNavigationBaseline(
    stitch: RectConfig,
    capture: RectConfig,
    geometryContent: RectConfig,
    liveContent: RectConfig,
    existingSides: Set<FixedSide>,
): RectConfig {
    var adjusted = stitch
    val topDelta = liveContent.top - geometryContent.top
    val bottomDelta = geometryContent.bottom - liveContent.bottom
    val leftDelta = liveContent.left - geometryContent.left
    val rightDelta = geometryContent.right - liveContent.right

    val topTracksContent = stitch.top == maxOf(capture.top, geometryContent.top)
    val bottomTracksContent = stitch.bottom == minOf(capture.bottom, geometryContent.bottom)
    val leftTracksContent = stitch.left == maxOf(capture.left, geometryContent.left)
    val rightTracksContent = stitch.right == minOf(capture.right, geometryContent.right)

    if ((FixedSide.TOP in existingSides || topTracksContent) &&
        capture.top < maxOf(geometryContent.top, liveContent.top)
    ) {
        adjusted = adjusted.copy(top = adjusted.top + topDelta)
    }
    if ((FixedSide.BOTTOM in existingSides || bottomTracksContent) &&
        capture.bottom > minOf(geometryContent.bottom, liveContent.bottom)
    ) {
        adjusted = adjusted.copy(bottom = adjusted.bottom - bottomDelta)
    }
    if ((FixedSide.LEFT in existingSides || leftTracksContent) &&
        capture.left < maxOf(geometryContent.left, liveContent.left)
    ) {
        adjusted = adjusted.copy(left = adjusted.left + leftDelta)
    }
    if ((FixedSide.RIGHT in existingSides || rightTracksContent) &&
        capture.right > minOf(geometryContent.right, liveContent.right)
    ) {
        adjusted = adjusted.copy(right = adjusted.right - rightDelta)
    }
    adjusted = adjusted.copy(
        left = adjusted.left.coerceAtLeast(maxOf(capture.left, liveContent.left)),
        top = adjusted.top.coerceAtLeast(maxOf(capture.top, liveContent.top)),
        right = adjusted.right.coerceAtMost(minOf(capture.right, liveContent.right)),
        bottom = adjusted.bottom.coerceAtMost(minOf(capture.bottom, liveContent.bottom)),
    )
    return adjusted
}

private fun remapFixedLayerRepeatRegion(
    layer: FixedLayerConfig,
    capture: RectConfig,
    stitch: RectConfig,
    scrollDirectionIsVertical: Boolean,
): FixedLayerConfig {
    val repeat = layer.repeatRegion ?: return layer
    val band = fixedBandForGeometry(
        capture = capture,
        stitch = stitch,
        side = layer.side,
        scrollDirectionIsVertical = scrollDirectionIsVertical,
    )
    val remapped = if (layer.side == FixedSide.TOP || layer.side == FixedSide.BOTTOM) {
        RectConfig(repeat.left, band.top, repeat.right, band.bottom)
    } else {
        RectConfig(band.left, repeat.top, band.right, repeat.bottom)
    }
    return layer.copy(repeatRegion = remapped)
}

private fun fixedBandForGeometry(
    capture: RectConfig,
    stitch: RectConfig,
    side: FixedSide,
    scrollDirectionIsVertical: Boolean,
): RectConfig = when (side) {
    FixedSide.TOP -> RectConfig(
        if (scrollDirectionIsVertical) capture.left else stitch.left,
        capture.top,
        if (scrollDirectionIsVertical) capture.right else stitch.right,
        stitch.top,
    )
    FixedSide.BOTTOM -> RectConfig(
        if (scrollDirectionIsVertical) capture.left else stitch.left,
        stitch.bottom,
        if (scrollDirectionIsVertical) capture.right else stitch.right,
        capture.bottom,
    )
    FixedSide.LEFT -> RectConfig(
        capture.left,
        if (scrollDirectionIsVertical) stitch.top else capture.top,
        stitch.left,
        if (scrollDirectionIsVertical) stitch.bottom else capture.bottom,
    )
    FixedSide.RIGHT -> RectConfig(
        stitch.right,
        if (scrollDirectionIsVertical) stitch.top else capture.top,
        capture.right,
        if (scrollDirectionIsVertical) stitch.bottom else capture.bottom,
    )
}

private fun navigationSidesOverlappingCapture(
    capture: RectConfig,
    content: RectConfig,
): List<FixedSide> = buildList {
    if (capture.top < content.top) add(FixedSide.TOP)
    if (capture.bottom > content.bottom) add(FixedSide.BOTTOM)
    if (capture.left < content.left) add(FixedSide.LEFT)
    if (capture.right > content.right) add(FixedSide.RIGHT)
}

/**
 * Returns only fixed layers that cover real app content in the geometry where they were authored.
 * A layer wholly outside that content rectangle was synthesized for a visible navigation bar and
 * must not become permanent template geometry.
 *
 * Unknown templates retain their explicit layers during a session. The explicit persistence path
 * may supply its confirmed live content bounds to remove a navigation-only layer before saving.
 */
private fun persistentFixedLayers(
    template: GameCaptureTemplate,
    fallbackGeometryContent: RectConfig? = null,
): List<FixedLayerConfig> {
    val width = template.screenWidth.coerceAtLeast(1)
    val height = template.screenHeight.coerceAtLeast(1)
    val storedGeometryContent = template.navigationContentBoundsAtGeometry
        ?.clampTo(width, height)
        ?.takeUnless { it.isEmpty }
    val geometryContent = storedGeometryContent ?: fallbackGeometryContent
        ?.clampTo(width, height)
        ?.takeUnless { it.isEmpty }
        ?: return template.fixedLayers
    val navigationSides = navigationSidesOverlappingCapture(
        template.captureRegion,
        geometryContent,
    ).toSet()
    return template.fixedLayers.filter { layer ->
        layer.side !in navigationSides ||
            template.fixedBandRegion(layer.side)?.intersect(geometryContent) != null
    }
}

/**
 * Restricts a template to the part of the physical display that is not occupied by a currently
 * visible navigation bar.
 *
 * The returned copy remains in physical display coordinates and can be used either for the active
 * capture session or for a template saved together with a reference frame from the same visible
 * content bounds. A null result means that clipping removed the capture, stitch or gesture region
 * and the caller must fail safely instead of silently expanding a region back into system UI.
 */
internal fun navigationSafeTemplateForSession(
    template: GameCaptureTemplate,
    visibleNavigationContentBounds: RectConfig,
): GameCaptureTemplate? {
    val normalized = template.withNormalizedRegions()
    val display = RectConfig.full(
        normalized.screenWidth.coerceAtLeast(1),
        normalized.screenHeight.coerceAtLeast(1),
    )
    val content = visibleNavigationContentBounds
        .clampTo(display.width, display.height)
        .takeUnless { it.isEmpty }
        ?: return null
    val storedGeometryContent = normalized.navigationContentBoundsAtGeometry
        ?.clampTo(display.width, display.height)
        ?.takeUnless { it.isEmpty }
    val geometryContent = storedGeometryContent ?: display
    val authoredLayers = persistentFixedLayers(normalized)
    // Legacy projection templates were already stored in physical coordinates and must retain
    // their old clip-only behavior. Once a baseline exists, however, an implicit navigation edge
    // can be moved back out when the bar hides instead of remaining as a stale crop boundary.
    val adjustedStitch = if (storedGeometryContent == null) {
        normalized.stitchRegion
    } else {
        adaptStitchToNavigationBaseline(
            stitch = normalized.stitchRegion,
            capture = normalized.captureRegion,
            geometryContent = geometryContent,
            liveContent = content,
            existingSides = authoredLayers.map(FixedLayerConfig::side).toSet(),
        )
    }
    val capture = normalized.captureRegion.intersect(content) ?: return null
    val stitch = adjustedStitch.intersect(capture) ?: return null
    val gesture = normalized.gestureRegion.intersect(content) ?: return null

    // If clipping removes an entire fixed band, retaining its side would make
    // withNormalizedRegions() manufacture a replacement band inside valid app content. Drop only
    // those fully removed bands; partially visible bands keep their configured anchor semantics.
    val retainedFixedLayers = authoredLayers.mapNotNull { layer ->
        // Evaluate visibility against the post-adaptation geometry. A thin authored band can move
        // with the stitch edge when navigation appears; the pre-adaptation normalized band would
        // incorrectly classify it as navigation-only.
        fixedBandForGeometry(
            capture = capture,
            stitch = stitch,
            side = layer.side,
            scrollDirectionIsVertical = normalized.scrollDirection.isVertical,
        ).takeUnless { it.isEmpty }
            ?: return@mapNotNull null
        remapFixedLayerRepeatRegion(
            layer = layer,
            capture = capture,
            stitch = stitch,
            scrollDirectionIsVertical = normalized.scrollDirection.isVertical,
        )
    }
    val safe = normalized.copy(
        captureRegion = capture,
        stitchRegion = stitch,
        fixedLayers = retainedFixedLayers,
        gestureRegion = gesture,
        navigationContentBoundsAtGeometry = content.takeIf {
            storedGeometryContent != null
        },
    ).withNormalizedRegions()

    // Keep this policy fail-closed if future model normalization rules change. Session geometry
    // must never grow outside either the original user selection or current app-content bounds.
    return safe.takeIf {
        content.contains(it.captureRegion) &&
            capture.contains(it.captureRegion) &&
            content.contains(it.stitchRegion) &&
            stitch.contains(it.stitchRegion) &&
            content.contains(it.gestureRegion) &&
            gesture.contains(it.gestureRegion)
    }
}
