package com.apricotiris.IrisShot.template

import androidx.annotation.StringRes
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.GameCaptureTemplate
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.FixedAnchorPosition
import com.apricotiris.IrisShot.core.model.FixedLayerConfig
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.RectConfig
import com.apricotiris.IrisShot.core.model.ScreenOrientation
import com.apricotiris.IrisShot.core.model.ScrollDirection
import com.apricotiris.IrisShot.core.model.hasDefaultPreviewGeometry
import com.apricotiris.IrisShot.core.model.withDefaultPreviewGeometry
import com.apricotiris.IrisShot.gesture.MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX
import com.apricotiris.IrisShot.gesture.maximumScrollGestureDistance
import com.apricotiris.IrisShot.gesture.moveScrollGestureRegionByPathCenter
import com.apricotiris.IrisShot.gesture.resolveTemplateScrollGesturePath
import com.apricotiris.IrisShot.gesture.scrollGestureAxisInset
import com.apricotiris.IrisShot.gesture.scrollGestureCrossAxisRegion
import com.apricotiris.IrisShot.gesture.scrollGestureMovementRegion
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class EditorPoint(val x: Float, val y: Float)

internal data class EditorFloatRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(point: EditorPoint): Boolean =
        point.x in left..right && point.y in top..bottom

    fun contains(other: EditorFloatRect): Boolean =
        other.width > 0f && other.height > 0f && left <= other.left && top <= other.top &&
            right >= other.right && bottom >= other.bottom

    fun intersects(other: EditorFloatRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

internal data class EditorLabelRequest(
    val anchor: EditorFloatRect,
    val width: Float,
    val height: Float,
)

internal data class ContainedEditorLabelRequest(
    val anchor: EditorFloatRect,
    val container: EditorFloatRect = anchor,
    val width: Float,
    val height: Float,
    val obstacles: List<EditorFloatRect> = emptyList(),
)

internal enum class EditorFrameEdge {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
}

internal data class EditorFrameBorderAllowance(
    val frame: EditorFloatRect,
    val edge: EditorFrameEdge,
)

internal fun placeEditorLabels(
    requests: List<EditorLabelRequest>,
    bounds: EditorFloatRect,
    gap: Float,
    obstacles: List<EditorFloatRect> = emptyList(),
    preferredSlots: List<EditorFloatRect> = emptyList(),
): List<EditorFloatRect> {
    if (requests.isEmpty()) return emptyList()
    val safeGap = gap.coerceAtLeast(0f)
    val occupied = obstacles.mapTo(mutableListOf()) { it.expanded(safeGap) }
    val placements = arrayOfNulls<EditorFloatRect>(requests.size)
    val placementOrder = requests.indices.sortedWith(
        compareBy<Int> { index ->
            requests[index].anchor.width.coerceAtLeast(0f) *
                requests[index].anchor.height.coerceAtLeast(0f)
        }.thenBy { it },
    )
    placementOrder.forEach { index ->
        val request = requests[index]
        val boxWidth = request.width.coerceIn(1f, bounds.width.coerceAtLeast(1f))
        val boxHeight = request.height.coerceIn(1f, bounds.height.coerceAtLeast(1f))
        val maxLeft = (bounds.right - boxWidth).coerceAtLeast(bounds.left)
        val maxTop = (bounds.bottom - boxHeight).coerceAtLeast(bounds.top)
        fun candidate(left: Float, top: Float): EditorFloatRect {
            val safeLeft = left.coerceIn(bounds.left, maxLeft)
            val safeTop = top.coerceIn(bounds.top, maxTop)
            return EditorFloatRect(
                safeLeft,
                safeTop,
                safeLeft + boxWidth,
                safeTop + boxHeight,
            )
        }
        val anchor = request.anchor
        val alignedLefts = listOf(
            anchor.left,
            (anchor.left + anchor.right - boxWidth) / 2f,
            anchor.right - boxWidth,
        )
        val alignedTops = listOf(
            anchor.top,
            (anchor.top + anchor.bottom - boxHeight) / 2f,
            anchor.bottom - boxHeight,
        )
        val candidates = buildList {
            preferredSlots.forEach { slot ->
                addAll(
                    editorLabelSlotCandidates(
                        slot = slot,
                        bounds = bounds,
                        anchor = anchor,
                        boxWidth = boxWidth,
                        boxHeight = boxHeight,
                        gap = safeGap,
                    ),
                )
            }
            alignedLefts.forEach { left ->
                add(candidate(left, anchor.top - boxHeight - safeGap))
                add(candidate(left, anchor.bottom + safeGap))
            }
            alignedTops.forEach { top ->
                add(candidate(anchor.left - boxWidth - safeGap, top))
                add(candidate(anchor.right + safeGap, top))
            }
            alignedLefts.forEach { left ->
                alignedTops.forEach { top -> add(candidate(left, top)) }
            }
        }.distinctBy { "${it.left.roundToInt()}:${it.top.roundToInt()}" }
        val placed = candidates.minWith(
            compareBy<EditorFloatRect> { candidate ->
                occupied.sumOf { used -> editorOverlapArea(candidate, used).toDouble() }
            }.thenBy { candidate ->
                editorOverlapArea(candidate, anchor)
            }.thenBy { candidate ->
                editorSeparationSquared(candidate, anchor)
            }.thenByDescending { candidate ->
                occupied.minOfOrNull { used -> editorSeparationSquared(candidate, used) }
                    ?: Float.MAX_VALUE
            },
        )
        occupied += placed.expanded(safeGap)
        placements[index] = placed
    }
    return placements.map(::requireNotNull)
}

/**
 * Places labels inside their own region, or inside an explicit containing region for a small
 * repeat/anchor block. Candidate generation never leaves [ContainedEditorLabelRequest.container].
 */
internal fun placeContainedEditorLabels(
    requests: List<ContainedEditorLabelRequest>,
    bounds: EditorFloatRect,
    gap: Float,
    obstacles: List<EditorFloatRect> = emptyList(),
): List<EditorFloatRect> {
    if (requests.isEmpty()) return emptyList()
    val safeGap = gap.coerceAtLeast(0f)
    val occupied = obstacles.mapTo(mutableListOf()) { it.expanded(safeGap) }
    val placements = arrayOfNulls<EditorFloatRect>(requests.size)
    val placementOrder = requests.indices.sortedWith(
        compareBy<Int> { index ->
            requests[index].anchor.width.coerceAtLeast(0f) *
                requests[index].anchor.height.coerceAtLeast(0f)
        }.thenBy { it },
    )
    placementOrder.forEach { index ->
        val request = requests[index]
        val container = request.container.intersection(bounds)
        if (container == null) {
            placements[index] = EditorFloatRect(bounds.left, bounds.top, bounds.left, bounds.top)
            return@forEach
        }
        val boxWidth = request.width.coerceIn(1f, container.width.coerceAtLeast(1f))
        val boxHeight = request.height.coerceIn(1f, container.height.coerceAtLeast(1f))
        val preferred = request.anchor.intersection(container) ?: container
        val candidates = buildList {
            addAll(
                editorLabelSlotCandidates(
                    slot = preferred,
                    bounds = container,
                    anchor = request.anchor,
                    boxWidth = boxWidth,
                    boxHeight = boxHeight,
                    gap = safeGap,
                ),
            )
            if (preferred != container) {
                addAll(
                    editorLabelSlotCandidates(
                        slot = container,
                        bounds = container,
                        anchor = request.anchor,
                        boxWidth = boxWidth,
                        boxHeight = boxHeight,
                        gap = safeGap,
                    ),
                )
            }
        }.distinctBy { "${it.left.roundToInt()}:${it.top.roundToInt()}" }
        val requestObstacles = request.obstacles.map { it.expanded(safeGap) }
        val unavailable = occupied + requestObstacles
        val placed = candidates
            .asSequence()
            .filter { candidate -> unavailable.none(candidate::intersects) }
            .minWithOrNull(
                compareByDescending<EditorFloatRect> { candidate ->
                editorOverlapArea(candidate, preferred)
            }.thenBy { candidate ->
                editorSeparationSquared(candidate, request.anchor)
            },
            ) ?: EditorFloatRect(container.left, container.top, container.left, container.top)
        if (placed.width > 0f && placed.height > 0f) {
            occupied += placed.expanded(safeGap)
        }
        placements[index] = placed
    }
    return placements.map(::requireNotNull)
}

internal fun editorFrameBorderObstacles(
    rect: EditorFloatRect,
    thickness: Float,
    allowedEdges: Set<EditorFrameEdge> = emptySet(),
): List<EditorFloatRect> {
    val safeThickness = thickness.coerceAtLeast(0f)
    if (rect.width <= 0f || rect.height <= 0f || safeThickness == 0f) return emptyList()
    val half = safeThickness / 2f
    return buildList {
        if (EditorFrameEdge.TOP !in allowedEdges) {
            add(EditorFloatRect(rect.left - half, rect.top - half, rect.right + half, rect.top + half))
        }
        if (EditorFrameEdge.BOTTOM !in allowedEdges) {
            add(
                EditorFloatRect(
                    rect.left - half,
                    rect.bottom - half,
                    rect.right + half,
                    rect.bottom + half,
                ),
            )
        }
        if (EditorFrameEdge.LEFT !in allowedEdges) {
            add(EditorFloatRect(rect.left - half, rect.top + half, rect.left + half, rect.bottom - half))
        }
        if (EditorFrameEdge.RIGHT !in allowedEdges) {
            add(
                EditorFloatRect(
                    rect.right - half,
                    rect.top + half,
                    rect.right + half,
                    rect.bottom - half,
                ),
            )
        }
    }
}

internal fun editorSharedFrameBorderAllowances(
    first: EditorFloatRect,
    second: EditorFloatRect,
    tolerance: Float = 0.5f,
): List<EditorFrameBorderAllowance> {
    val safeTolerance = tolerance.coerceAtLeast(0f)
    val horizontalOverlap = minOf(first.right, second.right) - maxOf(first.left, second.left)
    val verticalOverlap = minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
    return when {
        verticalOverlap > 0f && abs(first.right - second.left) <= safeTolerance -> listOf(
            EditorFrameBorderAllowance(first, EditorFrameEdge.RIGHT),
            EditorFrameBorderAllowance(second, EditorFrameEdge.LEFT),
        )
        verticalOverlap > 0f && abs(first.left - second.right) <= safeTolerance -> listOf(
            EditorFrameBorderAllowance(first, EditorFrameEdge.LEFT),
            EditorFrameBorderAllowance(second, EditorFrameEdge.RIGHT),
        )
        horizontalOverlap > 0f && abs(first.bottom - second.top) <= safeTolerance -> listOf(
            EditorFrameBorderAllowance(first, EditorFrameEdge.BOTTOM),
            EditorFrameBorderAllowance(second, EditorFrameEdge.TOP),
        )
        horizontalOverlap > 0f && abs(first.top - second.bottom) <= safeTolerance -> listOf(
            EditorFrameBorderAllowance(first, EditorFrameEdge.TOP),
            EditorFrameBorderAllowance(second, EditorFrameEdge.BOTTOM),
        )
        else -> emptyList()
    }
}

internal fun editorLabelRotationForContainer(
    defaultRotated: Boolean,
    unrotatedWidth: Float,
    unrotatedHeight: Float,
    container: EditorFloatRect,
): Boolean {
    fun overflow(rotated: Boolean): Float {
        val width = if (rotated) unrotatedHeight else unrotatedWidth
        val height = if (rotated) unrotatedWidth else unrotatedHeight
        return (width - container.width).coerceAtLeast(0f) +
            (height - container.height).coerceAtLeast(0f)
    }
    val defaultOverflow = overflow(defaultRotated)
    val alternateOverflow = overflow(!defaultRotated)
    return if (alternateOverflow < defaultOverflow) !defaultRotated else defaultRotated
}

internal fun editorLabelGutterSlots(
    bounds: EditorFloatRect,
    contentBounds: EditorFloatRect,
    gap: Float,
): List<EditorFloatRect> {
    if (bounds.width <= 0f || bounds.height <= 0f) return emptyList()
    val contentLeft = maxOf(bounds.left, contentBounds.left)
    val contentTop = maxOf(bounds.top, contentBounds.top)
    val contentRight = minOf(bounds.right, contentBounds.right)
    val contentBottom = minOf(bounds.bottom, contentBounds.bottom)
    if (contentLeft >= contentRight || contentTop >= contentBottom) return listOf(bounds)

    val safeGap = gap.coerceAtLeast(0f)
    return buildList {
        fun addIfUsable(slot: EditorFloatRect) {
            if (slot.width > 0f && slot.height > 0f) add(slot)
        }
        addIfUsable(
            EditorFloatRect(
                bounds.left,
                bounds.top,
                (contentLeft - safeGap).coerceAtLeast(bounds.left),
                bounds.bottom,
            ),
        )
        addIfUsable(
            EditorFloatRect(
                (contentRight + safeGap).coerceAtMost(bounds.right),
                bounds.top,
                bounds.right,
                bounds.bottom,
            ),
        )
        addIfUsable(
            EditorFloatRect(
                contentLeft,
                bounds.top,
                contentRight,
                (contentTop - safeGap).coerceAtLeast(bounds.top),
            ),
        )
        addIfUsable(
            EditorFloatRect(
                contentLeft,
                (contentBottom + safeGap).coerceAtMost(bounds.bottom),
                contentRight,
                bounds.bottom,
            ),
        )
    }
}

private fun editorLabelSlotCandidates(
    slot: EditorFloatRect,
    bounds: EditorFloatRect,
    anchor: EditorFloatRect,
    boxWidth: Float,
    boxHeight: Float,
    gap: Float,
): List<EditorFloatRect> {
    val fittedSlot = EditorFloatRect(
        left = maxOf(slot.left, bounds.left),
        top = maxOf(slot.top, bounds.top),
        right = minOf(slot.right, bounds.right),
        bottom = minOf(slot.bottom, bounds.bottom),
    )
    if (boxWidth > fittedSlot.width || boxHeight > fittedSlot.height) return emptyList()
    val lefts = editorLabelAxisStarts(
        minimum = fittedSlot.left,
        maximum = fittedSlot.right,
        itemSize = boxWidth,
        gap = gap,
        anchorStart = anchor.left,
        anchorEnd = anchor.right,
    )
    val tops = editorLabelAxisStarts(
        minimum = fittedSlot.top,
        maximum = fittedSlot.bottom,
        itemSize = boxHeight,
        gap = gap,
        anchorStart = anchor.top,
        anchorEnd = anchor.bottom,
    )
    return buildList {
        lefts.forEach { left ->
            tops.forEach { top ->
                add(EditorFloatRect(left, top, left + boxWidth, top + boxHeight))
            }
        }
    }
}

private fun editorLabelAxisStarts(
    minimum: Float,
    maximum: Float,
    itemSize: Float,
    gap: Float,
    anchorStart: Float,
    anchorEnd: Float,
): List<Float> {
    val maximumStart = maximum - itemSize
    if (maximumStart < minimum) return emptyList()
    val starts = buildList {
        add(anchorStart.coerceIn(minimum, maximumStart))
        add(((anchorStart + anchorEnd - itemSize) / 2f).coerceIn(minimum, maximumStart))
        add((anchorEnd - itemSize).coerceIn(minimum, maximumStart))
        val step = itemSize + gap
        var packed = minimum
        var candidateCount = 0
        while (candidateCount < MAX_LABEL_SLOT_AXIS_CANDIDATES) {
            add(packed)
            if (packed >= maximumStart) break
            packed = (packed + step).coerceAtMost(maximumStart)
            candidateCount += 1
        }
        add(maximumStart)
    }
    return starts.distinctBy { (it * 10f).roundToInt() }
}

private fun editorOverlapArea(first: EditorFloatRect, second: EditorFloatRect): Float {
    val width = (minOf(first.right, second.right) - maxOf(first.left, second.left))
        .coerceAtLeast(0f)
    val height = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top))
        .coerceAtLeast(0f)
    return width * height
}

private fun editorSeparationSquared(first: EditorFloatRect, second: EditorFloatRect): Float {
    val dx = maxOf(first.left - second.right, second.left - first.right, 0f)
    val dy = maxOf(first.top - second.bottom, second.top - first.bottom, 0f)
    return dx * dx + dy * dy
}

private fun EditorFloatRect.expanded(amount: Float): EditorFloatRect = EditorFloatRect(
    left - amount,
    top - amount,
    right + amount,
    bottom + amount,
)

private fun EditorFloatRect.intersection(other: EditorFloatRect): EditorFloatRect? {
    val intersection = EditorFloatRect(
        left = maxOf(left, other.left),
        top = maxOf(top, other.top),
        right = minOf(right, other.right),
        bottom = minOf(bottom, other.bottom),
    )
    return intersection.takeIf { it.width > 0f && it.height > 0f }
}

private const val MAX_LABEL_SLOT_AXIS_CANDIDATES = 32

internal data class FixedAnchorBlock(
    val position: FixedAnchorPosition,
    val region: RectConfig,
)

internal fun shouldShowFixedBandLabels(fixedBandCount: Int): Boolean = fixedBandCount > 1

internal fun shouldShowFixedAnchorBlockLabels(anchorCount: Int): Boolean = anchorCount > 1

internal fun fixedAnchorBlocks(
    band: RectConfig,
    repeat: RectConfig,
    vertical: Boolean,
    anchorCount: Int,
    anchorPosition: FixedAnchorPosition = FixedAnchorPosition.LEADING,
): List<FixedAnchorBlock> {
    val fittedRepeat = repeat.intersect(band) ?: return emptyList()
    val leading = if (vertical) {
        RectConfig(band.left, band.top, band.right, fittedRepeat.top)
    } else {
        RectConfig(band.left, band.top, fittedRepeat.left, band.bottom)
    }
    val trailing = if (vertical) {
        RectConfig(band.left, fittedRepeat.bottom, band.right, band.bottom)
    } else {
        RectConfig(fittedRepeat.right, band.top, band.right, band.bottom)
    }
    return buildList {
        if ((anchorCount >= 2 || anchorPosition == FixedAnchorPosition.LEADING) && !leading.isEmpty) {
            add(FixedAnchorBlock(FixedAnchorPosition.LEADING, leading))
        }
        if ((anchorCount >= 2 || anchorPosition == FixedAnchorPosition.TRAILING) && !trailing.isEmpty) {
            add(FixedAnchorBlock(FixedAnchorPosition.TRAILING, trailing))
        }
    }
}

internal fun aspectFitRect(
    sourceWidth: Int,
    sourceHeight: Int,
    destination: EditorFloatRect,
): EditorFloatRect {
    require(sourceWidth > 0 && sourceHeight > 0)
    require(destination.width > 0f && destination.height > 0f)
    val scale = minOf(
        destination.width / sourceWidth,
        destination.height / sourceHeight,
    )
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = destination.left + (destination.width - width) / 2f
    val top = destination.top + (destination.height - height) / 2f
    return EditorFloatRect(left, top, left + width, top + height)
}

internal data class TemplateViewportTransform(
    val modelWidth: Int,
    val modelHeight: Int,
    val viewportWidth: Float,
    val viewportHeight: Float,
    val quarterTurn: Boolean,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    private val displayModelWidth: Int get() = if (quarterTurn) modelHeight else modelWidth
    private val displayModelHeight: Int get() = if (quarterTurn) modelWidth else modelHeight
    val contentWidth: Float get() = displayModelWidth * scale
    val contentHeight: Float get() = displayModelHeight * scale
    val contentRect: EditorFloatRect
        get() = EditorFloatRect(offsetX, offsetY, offsetX + contentWidth, offsetY + contentHeight)

    fun modelToView(point: EditorPoint): EditorPoint = if (quarterTurn) {
        EditorPoint(
            x = offsetX + (modelHeight - point.y) * scale,
            y = offsetY + point.x * scale,
        )
    } else {
        EditorPoint(
            x = offsetX + point.x * scale,
            y = offsetY + point.y * scale,
        )
    }

    fun modelToView(rect: RectConfig): EditorFloatRect = if (quarterTurn) {
        EditorFloatRect(
            left = offsetX + (modelHeight - rect.bottom) * scale,
            top = offsetY + rect.left * scale,
            right = offsetX + (modelHeight - rect.top) * scale,
            bottom = offsetY + rect.right * scale,
        )
    } else {
        EditorFloatRect(
            left = offsetX + rect.left * scale,
            top = offsetY + rect.top * scale,
            right = offsetX + rect.right * scale,
            bottom = offsetY + rect.bottom * scale,
        )
    }

    fun modelToView(rect: EditorFloatRect): EditorFloatRect = if (quarterTurn) {
        EditorFloatRect(
            left = offsetX + (modelHeight - rect.bottom) * scale,
            top = offsetY + rect.left * scale,
            right = offsetX + (modelHeight - rect.top) * scale,
            bottom = offsetY + rect.right * scale,
        )
    } else {
        EditorFloatRect(
            left = offsetX + rect.left * scale,
            top = offsetY + rect.top * scale,
            right = offsetX + rect.right * scale,
            bottom = offsetY + rect.bottom * scale,
        )
    }

    fun viewToModel(point: EditorPoint, clamp: Boolean = true): EditorPoint {
        val model = if (quarterTurn) {
            EditorPoint(
                x = (point.y - offsetY) / scale,
                y = modelHeight - (point.x - offsetX) / scale,
            )
        } else {
            EditorPoint(
                x = (point.x - offsetX) / scale,
                y = (point.y - offsetY) / scale,
            )
        }
        return if (clamp) EditorPoint(
            x = model.x.coerceIn(0f, modelWidth.toFloat()),
            y = model.y.coerceIn(0f, modelHeight.toFloat()),
        ) else model
    }

    fun viewDeltaToModel(delta: EditorPoint): EditorPoint = if (quarterTurn) {
        EditorPoint(
            x = delta.y / scale,
            y = -delta.x / scale,
        )
    } else {
        EditorPoint(
            x = delta.x / scale,
            y = delta.y / scale,
        )
    }

    companion object {
        fun calculate(
            modelWidth: Int,
            modelHeight: Int,
            viewportWidth: Float,
            viewportHeight: Float,
            quarterTurn: Boolean = modelWidth > modelHeight,
            screenOrientation: ScreenOrientation? = null,
        ): TemplateViewportTransform {
            require(modelWidth > 0 && modelHeight > 0)
            require(viewportWidth > 0f && viewportHeight > 0f)
            // The explicit template orientation is authoritative once selected. The legacy
            // quarterTurn default remains for callers that do not have template metadata.
            val resolvedQuarterTurn = screenOrientation?.isLandscape ?: quarterTurn
            val displayModelWidth = if (resolvedQuarterTurn) modelHeight else modelWidth
            val displayModelHeight = if (resolvedQuarterTurn) modelWidth else modelHeight
            val scale = minOf(
                viewportWidth / displayModelWidth,
                viewportHeight / displayModelHeight,
            )
            val contentWidth = displayModelWidth * scale
            val contentHeight = displayModelHeight * scale
            return TemplateViewportTransform(
                modelWidth = modelWidth,
                modelHeight = modelHeight,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                quarterTurn = resolvedQuarterTurn,
                scale = scale,
                offsetX = (viewportWidth - contentWidth) / 2f,
                offsetY = (viewportHeight - contentHeight) / 2f,
            )
        }
    }
}

internal enum class TemplateEditTarget(
    @param:StringRes val labelRes: Int,
    val fixedLayerIndex: Int? = null,
) {
    CAPTURE(R.string.template_editor_capture_region),
    STITCH(R.string.template_editor_stitch_region),
    GESTURE(R.string.template_editor_gesture_path),
    PREVIEW(R.string.template_editor_preview_region),
    FIXED_1(R.string.template_editor_fixed_band_1, 0),
    FIXED_2(R.string.template_editor_fixed_band_2, 1),
}

internal fun templateEditTargets(template: GameCaptureTemplate): List<TemplateEditTarget> {
    val normalized = normalizeTemplateForEditor(template)
    return buildList {
        add(TemplateEditTarget.CAPTURE)
        add(TemplateEditTarget.STITCH)
        add(TemplateEditTarget.GESTURE)
        add(TemplateEditTarget.PREVIEW)
        if (normalized.fixedLayers.getOrNull(0)?.let { layer ->
                fixedBandStretchesWithScroll(normalized.scrollDirection, layer.side)
            } == true
        ) {
            add(TemplateEditTarget.FIXED_1)
        }
        if (normalized.fixedLayers.getOrNull(1)?.let { layer ->
                fixedBandStretchesWithScroll(normalized.scrollDirection, layer.side)
            } == true
        ) {
            add(TemplateEditTarget.FIXED_2)
        }
    }
}

internal fun validTemplateEditTarget(
    template: GameCaptureTemplate,
    requested: TemplateEditTarget,
): TemplateEditTarget = requested.takeIf(templateEditTargets(template)::contains)
    ?: TemplateEditTarget.CAPTURE

internal fun TemplateEditTarget.matchesFixedLayer(index: Int): Boolean =
    fixedLayerIndex == index

internal fun normalizeTemplateForEditor(template: GameCaptureTemplate): GameCaptureTemplate {
    val normalized = template.withNormalizedRegions()
    val normalizedLayers = normalized.fixedLayers.map { layer ->
        if (fixedBandStretchesWithScroll(normalized.scrollDirection, layer.side)) {
            layer
        } else {
            layer.copy(
                anchorCount = 1,
                anchorPosition = FixedAnchorPosition.LEADING,
                repeatRegion = null,
            )
        }
    }
    return if (normalizedLayers == normalized.fixedLayers) {
        normalized
    } else {
        normalized.copy(fixedLayers = normalizedLayers).withNormalizedRegions()
    }
}

internal fun templateWithFixedLayerCount(
    template: GameCaptureTemplate,
    count: Int,
): GameCaptureTemplate {
    val normalized = normalizeTemplateForEditor(template)
    val requestedCount = count.coerceIn(1, 2)
    if (normalized.fixedLayers.size == requestedCount) return normalized
    return if (requestedCount == 1) {
        val seed = normalized.fixedLayers.singleOrNull()
            ?: normalized.fixedLayers.firstOrNull()
            ?: FixedLayerConfig(defaultLeadingSide(normalized.scrollDirection))
        normalizeTemplateForEditor(normalized.copy(fixedLayers = listOf(seed)))
    } else {
        val layers = buildList {
            addAll(normalized.fixedLayers.take(2))
            val oppositeOfExisting = normalized.fixedLayers.firstOrNull()?.side?.let { side ->
                when (side) {
                    FixedSide.TOP -> FixedSide.BOTTOM
                    FixedSide.BOTTOM -> FixedSide.TOP
                    FixedSide.LEFT -> FixedSide.RIGHT
                    FixedSide.RIGHT -> FixedSide.LEFT
                }
            }
            val candidates = listOfNotNull(oppositeOfExisting) +
                defaultFixedSides(normalized.scrollDirection) + FixedSide.entries
            candidates.forEach { side ->
                if (size < 2 && none { it.side == side }) add(FixedLayerConfig(side))
            }
        }
        normalizeTemplateForEditor(normalized.copy(fixedLayers = layers))
    }
}

internal fun templateWithFixedLayerSide(
    template: GameCaptureTemplate,
    currentSide: FixedSide,
    requestedSide: FixedSide,
): GameCaptureTemplate {
    val normalized = normalizeTemplateForEditor(template)
    if (currentSide == requestedSide) return normalized
    if (normalized.fixedLayers.none { it.side == currentSide }) return normalized
    val requestedIsOccupied = normalized.fixedLayers.any { it.side == requestedSide }
    return normalizeTemplateForEditor(
        normalized.copy(
            fixedLayers = normalized.fixedLayers.map { layer ->
                when {
                    layer.side == currentSide -> layer.copy(
                        side = requestedSide,
                        repeatRegion = null,
                    )
                    requestedIsOccupied && layer.side == requestedSide -> layer.copy(
                        side = currentSide,
                        repeatRegion = null,
                    )
                    else -> layer
                }
            },
        ),
    )
}

internal fun templateWithAnchorCount(
    template: GameCaptureTemplate,
    count: Int,
): GameCaptureTemplate {
    val normalized = normalizeTemplateForEditor(template)
    val layer = normalized.fixedLayers.singleOrNull() ?: return normalized
    return templateWithAnchorCount(normalized, layer.side, count)
}

internal fun templateWithAnchorCount(
    template: GameCaptureTemplate,
    side: FixedSide,
    count: Int,
): GameCaptureTemplate {
    val normalized = normalizeTemplateForEditor(template)
    val requestedCount = count.coerceIn(1, 2)
    val selectedLayer = normalized.fixedLayers.firstOrNull { it.side == side } ?: return normalized
    if (!fixedBandStretchesWithScroll(normalized.scrollDirection, side)) return normalized
    if (selectedLayer.anchorCount == requestedCount) return normalized
    return normalizeTemplateForEditor(
        normalized.copy(
            fixedLayers = normalized.fixedLayers.map { layer ->
                if (layer.side != side) {
                    layer
                } else {
                    layer.copy(
                        anchorCount = requestedCount,
                        repeatRegion = null,
                    )
                }
            },
        ),
    )
}

internal fun templateWithAnchorPosition(
    template: GameCaptureTemplate,
    side: FixedSide,
    position: FixedAnchorPosition,
): GameCaptureTemplate {
    val normalized = normalizeTemplateForEditor(template)
    val selectedLayer = normalized.fixedLayers.firstOrNull { it.side == side } ?: return normalized
    if (!fixedBandStretchesWithScroll(normalized.scrollDirection, side)) return normalized
    if (selectedLayer.anchorPosition == position) return normalized
    return normalizeTemplateForEditor(
        normalized.copy(
            fixedLayers = normalized.fixedLayers.map { layer ->
                if (layer.side == side) {
                    layer.copy(anchorPosition = position, repeatRegion = null)
                } else {
                    layer
                }
            },
        ),
    )
}

internal fun templateWithScrollDirection(
    template: GameCaptureTemplate,
    direction: ScrollDirection,
    pixelsPerDp: Float = 1f,
): GameCaptureTemplate {
    val normalized = normalizeTemplateForEditor(template)
    if (direction == normalized.scrollDirection) return normalized
    val changed = normalized.copy(scrollDirection = direction)
    val withPreview = if (direction.isVertical != normalized.scrollDirection.isVertical) {
        changed.withDefaultPreviewGeometry(pixelsPerDp)
    } else {
        changed
    }
    return normalizeTemplateForEditor(withPreview)
}

/**
 * Updates the target display orientation without rewriting any existing region coordinates.
 * The preview is regenerated only while it still matches a default layout; a user-adjusted
 * preview remains untouched when the metadata changes.
 */
internal fun templateWithScreenOrientation(
    template: GameCaptureTemplate,
    orientation: ScreenOrientation,
    pixelsPerDp: Float = 1f,
): GameCaptureTemplate {
    val normalized = normalizeTemplateForEditor(template)
    val current = ScreenOrientation.fromSerialized(
        normalized.orientation,
        normalized.screenWidth,
        normalized.screenHeight,
    )
    if (current == orientation && normalized.orientation == orientation.serializedValue) {
        return normalized
    }
    val keepDefaultPreview = normalized.hasDefaultPreviewGeometry(pixelsPerDp)
    val changed = normalized.copy(orientation = orientation.serializedValue)
    return if (keepDefaultPreview) {
        changed.withDefaultPreviewGeometry(pixelsPerDp)
    } else {
        changed
    }
}

private fun defaultFixedSides(direction: ScrollDirection): List<FixedSide> =
    if (direction.isVertical) {
        listOf(FixedSide.TOP, FixedSide.BOTTOM)
    } else {
        listOf(FixedSide.LEFT, FixedSide.RIGHT)
    }

private fun defaultLeadingSide(direction: ScrollDirection): FixedSide =
    if (direction.isVertical) FixedSide.TOP else FixedSide.LEFT

internal fun fixedBandRunsVertically(side: FixedSide): Boolean =
    side == FixedSide.LEFT || side == FixedSide.RIGHT

internal fun fixedBandStretchesWithScroll(
    direction: ScrollDirection,
    side: FixedSide,
): Boolean = direction.isVertical == fixedBandRunsVertically(side)

internal enum class RegionDragHandle {
    NONE,
    MOVE,
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
}

internal enum class GesturePathDragHandle {
    NONE,
    START,
    MIDDLE,
    END,
}

internal data class TemplateGesturePath(
    val start: EditorPoint,
    val middle: EditorPoint,
    val end: EditorPoint,
    val distancePx: Int,
)

internal data class GesturePathWaterdropHandle(
    val handle: GesturePathDragHandle,
    val tip: EditorPoint,
    val center: EditorPoint,
    val normalX: Float,
    val normalY: Float,
    val radius: Float,
    val bounds: EditorFloatRect,
)

/**
 * Resolves the three gesture handles in view coordinates. The handle side and overflow behavior
 * mirror the floating region editor so the same path has the same editing affordance in previews.
 */
internal fun gesturePathWaterdropHandles(
    start: EditorPoint,
    middle: EditorPoint,
    end: EditorPoint,
    viewport: EditorFloatRect,
    handleInset: Float,
    handleRadius: Float,
    outerPadding: Float,
): List<GesturePathWaterdropHandle> {
    val inset = handleInset.coerceAtLeast(0f)
    val radius = handleRadius.coerceAtLeast(0f)
    val padding = outerPadding.coerceAtLeast(0f)
    val vertical = abs(end.y - start.y) >= abs(end.x - start.x)
    val defaultNormalX = if (vertical) 1f else 0f
    val defaultNormalY = if (vertical) 0f else 1f

    fun handle(
        mode: GesturePathDragHandle,
        tip: EditorPoint,
        normalX: Float,
        normalY: Float,
    ): GesturePathWaterdropHandle {
        val center = EditorPoint(
            x = tip.x + normalX * inset,
            y = tip.y + normalY * inset,
        )
        val tangentX = -normalY
        val tangentY = normalX
        val sideAX = center.x + tangentX * radius
        val sideAY = center.y + tangentY * radius
        val sideBX = center.x - tangentX * radius
        val sideBY = center.y - tangentY * radius
        val farX = center.x + normalX * radius
        val farY = center.y + normalY * radius
        return GesturePathWaterdropHandle(
            handle = mode,
            tip = tip,
            center = center,
            normalX = normalX,
            normalY = normalY,
            radius = radius,
            bounds = EditorFloatRect(
                left = minOf(tip.x, sideAX, sideBX, farX),
                top = minOf(tip.y, sideAY, sideBY, farY),
                right = maxOf(tip.x, sideAX, sideBX, farX),
                bottom = maxOf(tip.y, sideAY, sideBY, farY),
            ),
        )
    }

    fun handles(normalX: Float, normalY: Float) = listOf(
        handle(GesturePathDragHandle.START, start, normalX, normalY),
        handle(GesturePathDragHandle.MIDDLE, middle, normalX, normalY),
        handle(GesturePathDragHandle.END, end, normalX, normalY),
    )

    fun overflow(handles: List<GesturePathWaterdropHandle>): Float = handles.sumOf { handle ->
        val bounds = handle.bounds
        (
            (viewport.left + padding - bounds.left).coerceAtLeast(0f) +
                (viewport.top + padding - bounds.top).coerceAtLeast(0f) +
                (bounds.right - (viewport.right - padding)).coerceAtLeast(0f) +
                (bounds.bottom - (viewport.bottom - padding)).coerceAtLeast(0f)
            ).toDouble()
    }.toFloat()

    val defaultHandles = handles(defaultNormalX, defaultNormalY)
    val oppositeHandles = handles(-defaultNormalX, -defaultNormalY)
    return if (overflow(oppositeHandles) < overflow(defaultHandles)) {
        oppositeHandles
    } else {
        defaultHandles
    }
}

internal fun hitTestGesturePathWaterdropHandles(
    handles: List<GesturePathWaterdropHandle>,
    point: EditorPoint,
    touchRadius: Float,
): GesturePathDragHandle {
    val radiusSquared = touchRadius.coerceAtLeast(0f).let { it * it }
    return handles.mapNotNull { handle ->
        val dx = point.x - handle.center.x
        val dy = point.y - handle.center.y
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared <= radiusSquared) {
            handle.handle to distanceSquared
        } else {
            null
        }
    }.minByOrNull { it.second }?.first ?: GesturePathDragHandle.NONE
}

internal fun gesturePathBounds(
    start: EditorPoint,
    end: EditorPoint,
    padding: Float,
): EditorFloatRect {
    val safePadding = padding.coerceAtLeast(0f)
    return EditorFloatRect(
        left = minOf(start.x, end.x) - safePadding,
        top = minOf(start.y, end.y) - safePadding,
        right = maxOf(start.x, end.x) + safePadding,
        bottom = maxOf(start.y, end.y) + safePadding,
    )
}

internal fun templateGesturePath(
    template: GameCaptureTemplate,
    minimumDistancePx: Int = MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX,
): TemplateGesturePath {
    val path = resolveTemplateScrollGesturePath(
        template = template,
        requestedDistancePx = maxOf(template.gestureDistancePx, minimumDistancePx),
    )
    return TemplateGesturePath(
        start = EditorPoint(path.start.x, path.start.y),
        middle = EditorPoint(path.center.x, path.center.y),
        end = EditorPoint(path.end.x, path.end.y),
        distancePx = path.distancePx,
    )
}

internal fun hitTestTemplateGesturePath(
    template: GameCaptureTemplate,
    point: EditorPoint,
    touchRadius: Float,
    minimumDistancePx: Int = MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX,
): GesturePathDragHandle {
    val path = templateGesturePath(template, minimumDistancePx)
    val radiusSquared = touchRadius.coerceAtLeast(0f).let { it * it }
    return listOf(
        GesturePathDragHandle.START to path.start,
        GesturePathDragHandle.MIDDLE to path.middle,
        GesturePathDragHandle.END to path.end,
    ).mapNotNull { (handle, target) ->
        val dx = point.x - target.x
        val dy = point.y - target.y
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared <= radiusSquared) handle to distanceSquared else null
    }.minByOrNull { it.second }?.first ?: GesturePathDragHandle.NONE
}

internal fun dragTemplateGesturePath(
    template: GameCaptureTemplate,
    handle: GesturePathDragHandle,
    deltaX: Float,
    deltaY: Float,
    minimumDistancePx: Int = MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX,
): GameCaptureTemplate {
    if (handle == GesturePathDragHandle.NONE) return template
    val editing = normalizeTemplateForEditor(template)
    val movementBounds = scrollGestureMovementRegion(editing)
    val crossAxisBounds = scrollGestureCrossAxisRegion(editing)
    if (handle == GesturePathDragHandle.MIDDLE) {
        val dragBounds = if (editing.scrollDirection.isVertical) {
            RectConfig(
                left = crossAxisBounds.left,
                top = movementBounds.top,
                right = crossAxisBounds.right,
                bottom = movementBounds.bottom,
            )
        } else {
            RectConfig(
                left = movementBounds.left,
                top = crossAxisBounds.top,
                right = movementBounds.right,
                bottom = crossAxisBounds.bottom,
            )
        }
        return normalizeTemplateForEditor(
            editing.copy(
                gestureRegion = moveScrollGestureRegionByPathCenter(
                    region = editing.gestureRegion,
                    bounds = dragBounds,
                    direction = editing.scrollDirection,
                    deltaX = deltaX,
                    deltaY = deltaY,
                ),
            ),
        )
    }

    val path = templateGesturePath(editing, minimumDistancePx)
    val vertical = editing.scrollDirection.isVertical
    val directionSign = when (editing.scrollDirection) {
        ScrollDirection.UP, ScrollDirection.LEFT -> -1f
        ScrollDirection.DOWN, ScrollDirection.RIGHT -> 1f
    }
    val axisDelta = if (vertical) deltaY else deltaX
    val fixedAxis = when (handle) {
        GesturePathDragHandle.START -> if (vertical) path.end.y else path.end.x
        GesturePathDragHandle.END -> if (vertical) path.start.y else path.start.x
        GesturePathDragHandle.NONE, GesturePathDragHandle.MIDDLE -> return editing
    }
    val requestedDistance = when (handle) {
        GesturePathDragHandle.START -> path.distancePx - directionSign * axisDelta
        GesturePathDragHandle.END -> path.distancePx + directionSign * axisDelta
        GesturePathDragHandle.NONE, GesturePathDragHandle.MIDDLE -> return editing
    }
    val maximum = maximumScrollGestureDistance(editing.stitchRegion, editing.scrollDirection)
    val minimum = maxOf(
        MIN_EXECUTABLE_SCROLL_GESTURE_DISTANCE_PX,
        minimumDistancePx,
    ).coerceAtMost(maximum).coerceAtLeast(1)
    val movementStart = if (vertical) {
        movementBounds.top.toFloat()
    } else {
        movementBounds.left.toFloat()
    }
    val movementEnd = if (vertical) {
        movementBounds.bottom.toFloat()
    } else {
        movementBounds.right.toFloat()
    }
    val movementInset = scrollGestureAxisInset(movementBounds, editing.scrollDirection)
    val safeStart = movementStart + movementInset
    val safeEnd = movementEnd - movementInset
    val availableFromFixed = when (handle) {
        GesturePathDragHandle.START -> if (directionSign > 0f) {
            fixedAxis - safeStart
        } else {
            safeEnd - fixedAxis
        }
        GesturePathDragHandle.END -> if (directionSign > 0f) {
            safeEnd - fixedAxis
        } else {
            fixedAxis - safeStart
        }
        GesturePathDragHandle.NONE, GesturePathDragHandle.MIDDLE -> 0f
    }
    val allowedMaximum = minOf(maximum, floor(availableFromFixed).toInt())
    if (allowedMaximum < minimum) return editing

    // Keeping parity stable lets the non-dragged endpoint remain pixel-identical after the
    // integer distance is converted back to a centered path.
    val parity = path.distancePx and 1
    val parityMinimum = minimum + ((parity - (minimum and 1) + 2) % 2)
    val parityMaximum = allowedMaximum - (((allowedMaximum and 1) - parity + 2) % 2)
    if (parityMaximum < parityMinimum) return editing
    val selectedDistance = (
        ((requestedDistance - parity) / 2f).roundToInt() * 2 + parity
        ).coerceIn(parityMinimum, parityMaximum)
    val selectedCenter = when (handle) {
        GesturePathDragHandle.START -> fixedAxis - directionSign * selectedDistance / 2f
        GesturePathDragHandle.END -> fixedAxis + directionSign * selectedDistance / 2f
        GesturePathDragHandle.NONE, GesturePathDragHandle.MIDDLE -> return editing
    }
    val region = editing.gestureRegion.normalized()
    val updatedRegion = if (vertical) {
        val halfHeight = minOf(
            region.height / 2f,
            selectedCenter,
            editing.screenHeight - selectedCenter,
        ).coerceAtLeast(1f)
        RectConfig(
            region.left,
            (selectedCenter - halfHeight).roundToInt(),
            region.right,
            (selectedCenter + halfHeight).roundToInt(),
        )
    } else {
        val halfWidth = minOf(
            region.width / 2f,
            selectedCenter,
            editing.screenWidth - selectedCenter,
        ).coerceAtLeast(1f)
        RectConfig(
            (selectedCenter - halfWidth).roundToInt(),
            region.top,
            (selectedCenter + halfWidth).roundToInt(),
            region.bottom,
        )
    }
    return normalizeTemplateForEditor(
        editing.copy(
            gestureRegion = updatedRegion,
            gestureDistancePx = selectedDistance,
        ),
    )
}

internal fun hitTestRegion(
    template: GameCaptureTemplate,
    target: TemplateEditTarget,
    point: EditorPoint,
    handleInset: Float,
    handleTouchRadius: Float,
    pixelsPerDp: Float = 1f,
): RegionDragHandle {
    val floatRect = if (target.fixedLayerIndex != null) {
        template.fixedLayers.getOrNull(target.fixedLayerIndex)
            ?.takeIf { fixedBandStretchesWithScroll(template.scrollDirection, it.side) }
            ?.repeatRegion
            ?.toEditorFloatRect()
            ?: return RegionDragHandle.NONE
    } else when (target) {
        TemplateEditTarget.CAPTURE -> template.captureRegion.toEditorFloatRect()
        TemplateEditTarget.STITCH -> template.stitchRegion.toEditorFloatRect()
        TemplateEditTarget.GESTURE -> template.gestureRegion.toEditorFloatRect()
        TemplateEditTarget.PREVIEW -> previewModelRect(template, pixelsPerDp)
        TemplateEditTarget.FIXED_1, TemplateEditTarget.FIXED_2 ->
            return RegionDragHandle.NONE
    }
    val candidates = ALL_REGION_RESIZE_HANDLES.mapNotNull { handle ->
        if (!isHandleAllowed(template, target, handle)) return@mapNotNull null
        val center = resizeHandleCenter(floatRect, handle, handleInset)
        val actualInset = resizeHandleInset(floatRect, handle, center)
        val radius = minOf(
            handleTouchRadius.coerceAtLeast(0f),
            actualInset * MAX_HANDLE_RADIUS_TO_INSET_RATIO,
        )
        val dx = point.x - center.x
        val dy = point.y - center.y
        val distanceSquared = dx * dx + dy * dy
        if (distanceSquared <= radius * radius) handle to distanceSquared else null
    }
    candidates.minByOrNull { it.second }?.let { return it.first }
    return if (floatRect.contains(point)) RegionDragHandle.MOVE else RegionDragHandle.NONE
}

private fun resizeHandleInset(
    rect: EditorFloatRect,
    handle: RegionDragHandle,
    center: EditorPoint,
): Float = when (handle) {
    RegionDragHandle.LEFT -> center.x - rect.left
    RegionDragHandle.TOP -> center.y - rect.top
    RegionDragHandle.RIGHT -> rect.right - center.x
    RegionDragHandle.BOTTOM -> rect.bottom - center.y
    RegionDragHandle.NONE, RegionDragHandle.MOVE -> 0f
}.coerceAtLeast(0f)

internal fun resizeHandleCenter(
    rect: EditorFloatRect,
    handle: RegionDragHandle,
    requestedInset: Float,
): EditorPoint {
    val inset = requestedInset.coerceAtLeast(0f)
    val horizontalInset = minOf(inset, rect.width.coerceAtLeast(0f) / 2f)
    val verticalInset = minOf(inset, rect.height.coerceAtLeast(0f) / 2f)
    return when (handle) {
        RegionDragHandle.LEFT -> EditorPoint(rect.left + horizontalInset, (rect.top + rect.bottom) / 2f)
        RegionDragHandle.TOP -> EditorPoint((rect.left + rect.right) / 2f, rect.top + verticalInset)
        RegionDragHandle.RIGHT -> EditorPoint(rect.right - horizontalInset, (rect.top + rect.bottom) / 2f)
        RegionDragHandle.BOTTOM -> EditorPoint((rect.left + rect.right) / 2f, rect.bottom - verticalInset)
        RegionDragHandle.NONE, RegionDragHandle.MOVE ->
            EditorPoint((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
    }
}

internal fun templateLabelRotationDegrees(quarterTurn: Boolean): Float =
    if (quarterTurn) 90f else 0f

internal fun dragTemplateRegion(
    template: GameCaptureTemplate,
    target: TemplateEditTarget,
    handle: RegionDragHandle,
    deltaX: Float,
    deltaY: Float,
    pixelsPerDp: Float = 1f,
): GameCaptureTemplate {
    if (handle == RegionDragHandle.NONE) return template
    val editing = normalizeTemplateForEditor(template)
    val fullBounds = RectConfig.full(editing.screenWidth, editing.screenHeight)
    val minimumSize = (minOf(editing.screenWidth, editing.screenHeight) * 0.025f)
        .roundToInt()
        .coerceAtLeast(24)
    return when (target) {
        TemplateEditTarget.CAPTURE -> {
            val capture = dragRect(
                editing.captureRegion,
                fullBounds,
                handle,
                deltaX,
                deltaY,
                minimumSize,
                minimumSize,
            )
            val stitch = if (handle == RegionDragHandle.MOVE) {
                val appliedX = capture.left - editing.captureRegion.left
                val appliedY = capture.top - editing.captureRegion.top
                editing.stitchRegion.offset(appliedX, appliedY)
            } else editing.stitchRegion
            val fixedLayers = editing.fixedLayers.map { layer ->
                val repeat = layer.repeatRegion?.let { region ->
                    if (handle == RegionDragHandle.MOVE) {
                        region.offset(
                            capture.left - editing.captureRegion.left,
                            capture.top - editing.captureRegion.top,
                        )
                    } else {
                        region.intersect(capture)
                    }
                }
                layer.copy(repeatRegion = repeat)
            }
            normalizeTemplateForEditor(
                editing.copy(
                    captureRegion = capture,
                    stitchRegion = stitch,
                    fixedLayers = fixedLayers,
                ),
            )
        }
        TemplateEditTarget.STITCH -> {
            val (capture, stitch) = if (
                handle != RegionDragHandle.MOVE && !isFixedBandHandle(editing, handle)
            ) {
                resizeSharedStitchEdge(
                    capture = editing.captureRegion,
                    stitch = editing.stitchRegion,
                    fullBounds = fullBounds,
                    handle = handle,
                    deltaX = deltaX,
                    deltaY = deltaY,
                    minimumSize = minimumSize,
                )
            } else {
                editing.captureRegion to dragRect(
                    editing.stitchRegion,
                    editing.captureRegion,
                    handle,
                    deltaX,
                    deltaY,
                    minimumSize,
                    minimumSize,
                )
            }
            normalizeTemplateForEditor(
                editing.copy(captureRegion = capture, stitchRegion = stitch),
            )
        }
        TemplateEditTarget.FIXED_1, TemplateEditTarget.FIXED_2 -> dragFixedLayerRegion(
            template = editing,
            target = target,
            handle = handle,
            deltaX = deltaX,
            deltaY = deltaY,
            minimumSize = minimumSize,
        )
        TemplateEditTarget.GESTURE -> normalizeTemplateForEditor(editing.copy(
            gestureRegion = dragRect(
                editing.gestureRegion,
                fullBounds,
                handle,
                deltaX,
                deltaY,
                minimumSize,
                minimumSize,
            ),
        ))
        TemplateEditTarget.PREVIEW -> dragPreviewRegion(
            template = editing,
            handle = handle,
            deltaX = deltaX,
            deltaY = deltaY,
            pixelsPerDp = pixelsPerDp,
        )
    }
}

internal fun previewModelRect(
    template: GameCaptureTemplate,
    pixelsPerDp: Float,
): EditorFloatRect {
    val editing = normalizeTemplateForEditor(template)
    val density = pixelsPerDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val screenWidth = editing.screenWidth.coerceAtLeast(1).toFloat()
    val screenHeight = editing.screenHeight.coerceAtLeast(1).toFloat()
    val width = (editing.previewWidthDp * density).coerceAtMost(screenWidth)
    val height = (editing.previewMaxHeightDp * density).coerceAtMost(screenHeight)
    val requestedCenterX = screenWidth * editing.previewCenterXRatio
    val requestedCenterY = screenHeight * editing.previewCenterYRatio
    val left = (requestedCenterX - width / 2f).coerceIn(0f, screenWidth - width)
    val top = (requestedCenterY - height / 2f).coerceIn(0f, screenHeight - height)
    return EditorFloatRect(left, top, left + width, top + height)
}

private fun dragPreviewRegion(
    template: GameCaptureTemplate,
    handle: RegionDragHandle,
    deltaX: Float,
    deltaY: Float,
    pixelsPerDp: Float,
): GameCaptureTemplate {
    val density = pixelsPerDp.takeIf { it.isFinite() && it > 0f } ?: 1f
    val source = previewModelRect(template, density)
    if (handle == RegionDragHandle.MOVE) {
        return movePreviewCenter(
            template,
            EditorPoint(
                x = (source.left + source.right) / 2f + deltaX,
                y = (source.top + source.bottom) / 2f + deltaY,
            ),
        )
    }

    val resized = resizePreviewRect(
        source = source,
        screenWidth = template.screenWidth.coerceAtLeast(1).toFloat(),
        screenHeight = template.screenHeight.coerceAtLeast(1).toFloat(),
        handle = handle,
        deltaX = deltaX,
        deltaY = deltaY,
        minimumWidth = DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP * density,
        maximumWidth = DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP * density,
        minimumHeight = DefaultCaptureConfig.PREVIEW_MIN_SIZE_DP * density,
        maximumHeight = DefaultCaptureConfig.PREVIEW_MAX_SIZE_DP * density,
    )
    return normalizeTemplateForEditor(
        template.copy(
            previewWidthDp = (resized.width / density).roundToInt(),
            previewMaxHeightDp = (resized.height / density).roundToInt(),
            previewCenterXRatio = ((resized.left + resized.right) / 2f / template.screenWidth.coerceAtLeast(1))
                .coerceIn(0f, 1f),
            previewCenterYRatio = ((resized.top + resized.bottom) / 2f / template.screenHeight.coerceAtLeast(1))
                .coerceIn(0f, 1f),
        ),
    )
}

private fun resizePreviewRect(
    source: EditorFloatRect,
    screenWidth: Float,
    screenHeight: Float,
    handle: RegionDragHandle,
    deltaX: Float,
    deltaY: Float,
    minimumWidth: Float,
    maximumWidth: Float,
    minimumHeight: Float,
    maximumHeight: Float,
): EditorFloatRect {
    val minWidth = minimumWidth.coerceIn(1f, screenWidth)
    val maxWidth = maximumWidth.coerceIn(minWidth, screenWidth)
    val minHeight = minimumHeight.coerceIn(1f, screenHeight)
    val maxHeight = maximumHeight.coerceIn(minHeight, screenHeight)
    return when (handle) {
        RegionDragHandle.LEFT -> {
            val left = (source.left + deltaX).coerceIn(
                (source.right - maxWidth).coerceAtLeast(0f),
                source.right - minWidth,
            )
            source.copy(left = left)
        }
        RegionDragHandle.TOP -> {
            val top = (source.top + deltaY).coerceIn(
                (source.bottom - maxHeight).coerceAtLeast(0f),
                source.bottom - minHeight,
            )
            source.copy(top = top)
        }
        RegionDragHandle.RIGHT -> {
            val right = (source.right + deltaX).coerceIn(
                source.left + minWidth,
                (source.left + maxWidth).coerceAtMost(screenWidth),
            )
            source.copy(right = right)
        }
        RegionDragHandle.BOTTOM -> {
            val bottom = (source.bottom + deltaY).coerceIn(
                source.top + minHeight,
                (source.top + maxHeight).coerceAtMost(screenHeight),
            )
            source.copy(bottom = bottom)
        }
        RegionDragHandle.NONE, RegionDragHandle.MOVE -> source
    }
}

private fun dragFixedLayerRegion(
    template: GameCaptureTemplate,
    target: TemplateEditTarget,
    handle: RegionDragHandle,
    deltaX: Float,
    deltaY: Float,
    minimumSize: Int,
): GameCaptureTemplate {
    val layerIndex = target.fixedLayerIndex ?: return template
    val layer = template.fixedLayers.getOrNull(layerIndex) ?: return template
    if (!fixedBandStretchesWithScroll(template.scrollDirection, layer.side)) return template
    val source = layer.repeatRegion ?: return template
    val band = template.fixedBandRegion(layer.side) ?: return template
    if (!isHandleAllowed(template, target, handle)) return template
    val vertical = fixedBandRunsVertically(layer.side)
    val dragged = dragRect(
        source = source.fitInside(band),
        bounds = band,
        handle = handle,
        deltaX = if (vertical) 0f else deltaX,
        deltaY = if (vertical) deltaY else 0f,
        minimumWidth = if (vertical) band.width else minimumSize,
        minimumHeight = if (vertical) minimumSize else band.height,
    )
    val repeat = normalizeRepeatForEditor(
        repeat = dragged,
        band = band,
        anchorCount = layer.anchorCount,
        anchorPosition = layer.anchorPosition,
        vertical = vertical,
    )
    return normalizeTemplateForEditor(
        template.copy(
            fixedLayers = template.fixedLayers.mapIndexed { index, existing ->
                if (index == layerIndex) existing.copy(repeatRegion = repeat) else existing
            },
        ),
    )
}

private fun normalizeRepeatForEditor(
    repeat: RectConfig,
    band: RectConfig,
    anchorCount: Int,
    anchorPosition: FixedAnchorPosition,
    vertical: Boolean,
): RectConfig {
    if (vertical) {
        if (band.height <= 2) return band
        if (anchorCount == 1 && anchorPosition == FixedAnchorPosition.TRAILING) {
            val bottom = repeat.bottom.coerceIn(band.top + 1, band.bottom - 1)
            return RectConfig(band.left, band.top, band.right, bottom)
        }
        val trailingInset = if (anchorCount == 2) 1 else 0
        val maximumTop = (band.bottom - trailingInset - 1).coerceAtLeast(band.top + 1)
        val top = repeat.top.coerceIn(band.top + 1, maximumTop)
        val bottom = if (anchorCount == 1) {
            band.bottom
        } else {
            repeat.bottom.coerceIn(top + 1, band.bottom - 1)
        }
        return RectConfig(band.left, top, band.right, bottom)
    }
    if (band.width <= 2) return band
    if (anchorCount == 1 && anchorPosition == FixedAnchorPosition.TRAILING) {
        val right = repeat.right.coerceIn(band.left + 1, band.right - 1)
        return RectConfig(band.left, band.top, right, band.bottom)
    }
    val trailingInset = if (anchorCount == 2) 1 else 0
    val maximumLeft = (band.right - trailingInset - 1).coerceAtLeast(band.left + 1)
    val left = repeat.left.coerceIn(band.left + 1, maximumLeft)
    val right = if (anchorCount == 1) {
        band.right
    } else {
        repeat.right.coerceIn(left + 1, band.right - 1)
    }
    return RectConfig(left, band.top, right, band.bottom)
}

private fun isHandleAllowed(
    template: GameCaptureTemplate,
    target: TemplateEditTarget,
    handle: RegionDragHandle,
): Boolean {
    if (handle == RegionDragHandle.NONE) return false
    if (handle == RegionDragHandle.MOVE) return true
    val repeatLayer = target.fixedLayerIndex?.let { layerIndex ->
        template.fixedLayers.getOrNull(layerIndex)
    }
    val repeatVertical = repeatLayer?.let { fixedBandRunsVertically(it.side) }
    val repeatHasLeadingHandle = repeatLayer?.let {
        it.anchorCount == 2 || it.anchorPosition == FixedAnchorPosition.LEADING
    } == true
    val repeatHasTrailingHandle = repeatLayer?.let {
        it.anchorCount == 2 || it.anchorPosition == FixedAnchorPosition.TRAILING
    } == true
    return when (handle) {
        RegionDragHandle.LEFT -> repeatLayer == null ||
            (repeatVertical == false && repeatHasLeadingHandle)
        RegionDragHandle.RIGHT -> repeatLayer == null ||
            (repeatVertical == false && repeatHasTrailingHandle)
        RegionDragHandle.TOP -> repeatLayer == null ||
            (repeatVertical == true && repeatHasLeadingHandle)
        RegionDragHandle.BOTTOM -> repeatLayer == null ||
            (repeatVertical == true && repeatHasTrailingHandle)
        RegionDragHandle.NONE, RegionDragHandle.MOVE -> true
    }
}

private fun isFixedBandHandle(
    template: GameCaptureTemplate,
    handle: RegionDragHandle,
): Boolean {
    val side = when (handle) {
        RegionDragHandle.LEFT -> FixedSide.LEFT
        RegionDragHandle.TOP -> FixedSide.TOP
        RegionDragHandle.RIGHT -> FixedSide.RIGHT
        RegionDragHandle.BOTTOM -> FixedSide.BOTTOM
        RegionDragHandle.NONE, RegionDragHandle.MOVE -> return false
    }
    return template.fixedLayers.any { it.side == side }
}

private fun resizeSharedStitchEdge(
    capture: RectConfig,
    stitch: RectConfig,
    fullBounds: RectConfig,
    handle: RegionDragHandle,
    deltaX: Float,
    deltaY: Float,
    minimumSize: Int,
): Pair<RectConfig, RectConfig> {
    val dx = deltaX.roundToInt()
    val dy = deltaY.roundToInt()
    return when (handle) {
        RegionDragHandle.LEFT -> {
            val maximum = (stitch.right - minimumSize).coerceAtLeast(fullBounds.left)
            val edge = (capture.left + dx).coerceIn(fullBounds.left, maximum)
            capture.copy(left = edge) to stitch.copy(left = edge)
        }
        RegionDragHandle.TOP -> {
            val maximum = (stitch.bottom - minimumSize).coerceAtLeast(fullBounds.top)
            val edge = (capture.top + dy).coerceIn(fullBounds.top, maximum)
            capture.copy(top = edge) to stitch.copy(top = edge)
        }
        RegionDragHandle.RIGHT -> {
            val minimum = (stitch.left + minimumSize).coerceAtMost(fullBounds.right)
            val edge = (capture.right + dx).coerceIn(minimum, fullBounds.right)
            capture.copy(right = edge) to stitch.copy(right = edge)
        }
        RegionDragHandle.BOTTOM -> {
            val minimum = (stitch.top + minimumSize).coerceAtMost(fullBounds.bottom)
            val edge = (capture.bottom + dy).coerceIn(minimum, fullBounds.bottom)
            capture.copy(bottom = edge) to stitch.copy(bottom = edge)
        }
        RegionDragHandle.NONE, RegionDragHandle.MOVE -> capture to stitch
    }
}

internal fun movePreviewCenter(
    template: GameCaptureTemplate,
    point: EditorPoint,
): GameCaptureTemplate = template.copy(
    previewCenterXRatio = (point.x / template.screenWidth.coerceAtLeast(1)).coerceIn(0f, 1f),
    previewCenterYRatio = (point.y / template.screenHeight.coerceAtLeast(1)).coerceIn(0f, 1f),
).let(::normalizeTemplateForEditor)

private fun RectConfig.toEditorFloatRect(): EditorFloatRect = EditorFloatRect(
    left.toFloat(),
    top.toFloat(),
    right.toFloat(),
    bottom.toFloat(),
)

private fun dragRect(
    source: RectConfig,
    bounds: RectConfig,
    handle: RegionDragHandle,
    deltaX: Float,
    deltaY: Float,
    minimumWidth: Int,
    minimumHeight: Int,
): RectConfig {
    val safeMinimumWidth = minimumWidth.coerceIn(1, bounds.width.coerceAtLeast(1))
    val safeMinimumHeight = minimumHeight.coerceIn(1, bounds.height.coerceAtLeast(1))
    val dx = deltaX.roundToInt()
    val dy = deltaY.roundToInt()
    if (handle == RegionDragHandle.MOVE) {
        val appliedX = dx.coerceIn(bounds.left - source.left, bounds.right - source.right)
        val appliedY = dy.coerceIn(bounds.top - source.top, bounds.bottom - source.bottom)
        return source.offset(appliedX, appliedY)
    }
    var left = source.left
    var top = source.top
    var right = source.right
    var bottom = source.bottom
    when (handle) {
        RegionDragHandle.LEFT -> {
            val edgeMinimum = safeMinimumWidth.coerceAtMost((right - bounds.left).coerceAtLeast(1))
            left = (source.left + dx).coerceIn(bounds.left, right - edgeMinimum)
        }
        RegionDragHandle.TOP -> {
            val edgeMinimum = safeMinimumHeight.coerceAtMost((bottom - bounds.top).coerceAtLeast(1))
            top = (source.top + dy).coerceIn(bounds.top, bottom - edgeMinimum)
        }
        RegionDragHandle.RIGHT -> {
            val edgeMinimum = safeMinimumWidth.coerceAtMost((bounds.right - left).coerceAtLeast(1))
            right = (source.right + dx).coerceIn(left + edgeMinimum, bounds.right)
        }
        RegionDragHandle.BOTTOM -> {
            val edgeMinimum = safeMinimumHeight.coerceAtMost((bounds.bottom - top).coerceAtLeast(1))
            bottom = (source.bottom + dy).coerceIn(top + edgeMinimum, bounds.bottom)
        }
        RegionDragHandle.NONE, RegionDragHandle.MOVE -> Unit
    }
    return RectConfig(left, top, right, bottom)
}

private fun RectConfig.offset(dx: Int, dy: Int): RectConfig = RectConfig(
    left = left + dx,
    top = top + dy,
    right = right + dx,
    bottom = bottom + dy,
)

private fun RectConfig.fitInside(bounds: RectConfig): RectConfig {
    val fittedWidth = width.coerceIn(1, bounds.width)
    val fittedHeight = height.coerceIn(1, bounds.height)
    val fittedLeft = left.coerceIn(bounds.left, bounds.right - fittedWidth)
    val fittedTop = top.coerceIn(bounds.top, bounds.bottom - fittedHeight)
    return RectConfig(
        fittedLeft,
        fittedTop,
        fittedLeft + fittedWidth,
        fittedTop + fittedHeight,
    )
}

private val ALL_REGION_RESIZE_HANDLES = listOf(
    RegionDragHandle.LEFT,
    RegionDragHandle.TOP,
    RegionDragHandle.RIGHT,
    RegionDragHandle.BOTTOM,
)
private const val MAX_HANDLE_RADIUS_TO_INSET_RATIO = 0.70f
