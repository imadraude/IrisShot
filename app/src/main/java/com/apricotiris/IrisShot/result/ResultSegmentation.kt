package com.apricotiris.IrisShot.result

import com.apricotiris.IrisShot.core.export.encodedImageWidth
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.CapturedFixedLayer
import com.apricotiris.IrisShot.core.model.FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.model.ScrollExportRegion

internal enum class ScrollBoundaryAction {
    ADD_ABOVE,
    ADD_BELOW,
    DELETE_ABOVE,
    DELETE_BELOW,
    TOGGLE_ABOVE,
    TOGGLE_BELOW,
}

internal data class ScrollBoundaryActions(
    val addAbove: Boolean,
    val addBelow: Boolean,
    val deleteAbove: Boolean,
    val deleteBelow: Boolean,
    val toggleAbove: Boolean,
    val toggleBelow: Boolean,
)

internal enum class ScrollBoundaryPositionLabel {
    STARTS_AT,
    ENDS_AT,
}

internal fun scrollBoundaryPositionLabel(
    boundaryIndex: Int,
    regionCount: Int,
): ScrollBoundaryPositionLabel = if (regionCount > 0 && boundaryIndex == regionCount) {
    ScrollBoundaryPositionLabel.ENDS_AT
} else {
    ScrollBoundaryPositionLabel.STARTS_AT
}

internal enum class ScrollExportUnavailableReason {
    NO_ENABLED_REGIONS,
    MINIMUM_EXCEEDS_MAXIMUM,
    REGION_TOO_SHORT,
    REGION_TOO_LONG,
}

internal data class ScrollExportAvailability(
    val minimumSegmentLength: Int,
    val maximumSegmentLength: Int,
    val unavailableReason: ScrollExportUnavailableReason?,
) {
    val canExport: Boolean get() = unavailableReason == null
}

internal fun scrollExportAvailability(
    regions: List<ScrollExportRegion>,
    maximumSegmentLength: Int,
    minimumSegmentLength: Int = 1,
): ScrollExportAvailability {
    val minimum = minimumSegmentLength.coerceAtLeast(1)
    val maximum = maximumSegmentLength.coerceAtLeast(0)
    val enabled = regions.filter(ScrollExportRegion::enabled)
    val unavailableReason = when {
        minimum > maximum -> ScrollExportUnavailableReason.MINIMUM_EXCEEDS_MAXIMUM
        enabled.isEmpty() -> ScrollExportUnavailableReason.NO_ENABLED_REGIONS
        enabled.any { it.length > maximum } -> ScrollExportUnavailableReason.REGION_TOO_LONG
        enabled.any { it.length < minimum } -> ScrollExportUnavailableReason.REGION_TOO_SHORT
        else -> null
    }
    return ScrollExportAvailability(
        minimumSegmentLength = minimum,
        maximumSegmentLength = maximum,
        unavailableReason = unavailableReason,
    )
}

internal fun normalizeScrollExportRegions(
    axisLength: Int,
    selectedStart: Int,
    selectedEnd: Int,
    storedRegions: List<ScrollExportRegion>,
    maximumSegmentLength: Int,
    minimumSegmentLength: Int = 1,
): List<ScrollExportRegion> {
    if (axisLength <= 0) return emptyList()
    val minimum = minimumSegmentLength.coerceIn(1, axisLength)
    val maximum = maximumSegmentLength.coerceIn(1, axisLength)
    val stored = storedRegions
        .map { region ->
            region.copy(
                start = region.start.coerceIn(0, axisLength),
                end = region.end.coerceIn(0, axisLength),
            )
        }
        .filter { it.end > it.start }
        .sortedBy(ScrollExportRegion::start)
    val storedIsContiguous = stored.isNotEmpty() && stored.zipWithNext().all { (first, second) ->
        first.end == second.start
    }
    val base = if (storedIsContiguous) {
        stored
    } else {
        val start = selectedStart.coerceIn(0, axisLength - 1)
        val requestedEnd = selectedEnd.takeIf { it > start } ?: axisLength
        val end = requestedEnd.coerceIn(start + 1, axisLength)
        listOf(ScrollExportRegion(start, end, enabled = true))
    }
    return base.flatMap { region ->
        splitScrollExportRegion(region, maximum, minimum)
    }
}

internal fun scrollExportBoundaryPositions(regions: List<ScrollExportRegion>): List<Int> =
    if (regions.isEmpty()) emptyList() else buildList(regions.size + 1) {
        add(regions.first().start)
        regions.forEach { add(it.end) }
    }

internal fun moveScrollExportBoundary(
    regions: List<ScrollExportRegion>,
    boundaryIndex: Int,
    requestedPosition: Int,
    axisLength: Int,
    maximumSegmentLength: Int,
    minimumSegmentLength: Int = 1,
): List<ScrollExportRegion> {
    if (regions.isEmpty() || boundaryIndex !in 0..regions.size || axisLength <= 0) return regions
    val boundaries = scrollExportBoundaryPositions(regions)
    val minimum = minimumSegmentLength.coerceAtLeast(1)
    val maximum = maximumSegmentLength.coerceAtLeast(0)
    if (minimum > maximum) return regions
    var lower = if (boundaryIndex == 0) 0 else boundaries[boundaryIndex - 1] + minimum
    var upper = if (boundaryIndex == regions.size) {
        axisLength
    } else {
        boundaries[boundaryIndex + 1] - minimum
    }
    if (boundaryIndex > 0 && regions[boundaryIndex - 1].enabled) {
        upper = minOf(upper, regions[boundaryIndex - 1].start + maximum)
    }
    if (boundaryIndex < regions.size && regions[boundaryIndex].enabled) {
        lower = maxOf(lower, regions[boundaryIndex].end - maximum)
    }
    if (upper < lower) return regions
    val position = requestedPosition.coerceIn(lower, upper)
    return regions.mapIndexed { index, region ->
        when (index) {
            boundaryIndex - 1 -> region.copy(end = position)
            boundaryIndex -> region.copy(start = position)
            else -> region
        }
    }
}

internal fun scrollBoundaryActions(
    regions: List<ScrollExportRegion>,
    boundaryIndex: Int,
    maximumSegmentLength: Int = Int.MAX_VALUE,
    minimumSegmentLength: Int = 1,
    axisLength: Int = regions.lastOrNull()?.end ?: 0,
): ScrollBoundaryActions {
    val above = regions.getOrNull(boundaryIndex - 1)
    val below = regions.getOrNull(boundaryIndex)
    val minimum = minimumSegmentLength.coerceAtLeast(1)
    val maximum = maximumSegmentLength.coerceAtLeast(0)
    val limitsCompatible = minimum <= maximum
    fun canMerge(first: ScrollExportRegion?, second: ScrollExportRegion?): Boolean =
        first != null && second != null &&
            (!first.enabled || !second.enabled ||
                first.length.toLong() + second.length <= maximum.toLong())
    // Toggling is a visibility/editing operation, so it must remain available even when a
    // disabled region is currently longer than the export limit. Re-enabling it lets the normal
    // region normalization split it into exportable parts (or surface the existing limit error).
    fun canToggle(region: ScrollExportRegion?): Boolean = region != null
    val insertionLengthAllowed = limitsCompatible &&
        SCROLL_BOUNDARY_ADD_OFFSET_PX in minimum..maximum
    fun canAddInside(region: ScrollExportRegion?): Boolean = region != null &&
        insertionLengthAllowed &&
        region.length.toLong() >= SCROLL_BOUNDARY_ADD_OFFSET_PX.toLong() + minimum
    val leadingOutsideSpace = regions.firstOrNull()?.start?.coerceAtLeast(0) ?: 0
    val trailingOutsideSpace = regions.lastOrNull()?.let { region ->
        (axisLength.toLong() - region.end.toLong()).coerceAtLeast(0L)
    } ?: 0L
    return ScrollBoundaryActions(
        addAbove = if (boundaryIndex == 0) {
            insertionLengthAllowed && leadingOutsideSpace >= SCROLL_BOUNDARY_ADD_OFFSET_PX
        } else {
            canAddInside(above)
        },
        addBelow = if (boundaryIndex == regions.size) {
            insertionLengthAllowed && trailingOutsideSpace >= SCROLL_BOUNDARY_ADD_OFFSET_PX
        } else {
            canAddInside(below)
        },
        deleteAbove = boundaryIndex - 1 in 1 until regions.size &&
            canMerge(regions.getOrNull(boundaryIndex - 2), regions.getOrNull(boundaryIndex - 1)),
        deleteBelow = boundaryIndex + 1 in 1 until regions.size &&
            canMerge(regions.getOrNull(boundaryIndex), regions.getOrNull(boundaryIndex + 1)),
        toggleAbove = canToggle(above),
        toggleBelow = canToggle(below),
    )
}

internal fun applyScrollBoundaryAction(
    regions: List<ScrollExportRegion>,
    boundaryIndex: Int,
    action: ScrollBoundaryAction,
    maximumSegmentLength: Int,
    minimumSegmentLength: Int = 1,
    axisLength: Int = regions.lastOrNull()?.end ?: 0,
): List<ScrollExportRegion> {
    if (regions.isEmpty() || boundaryIndex !in 0..regions.size) return regions
    val minimum = minimumSegmentLength.coerceAtLeast(1)
    val available = scrollBoundaryActions(
        regions = regions,
        boundaryIndex = boundaryIndex,
        maximumSegmentLength = maximumSegmentLength,
        minimumSegmentLength = minimum,
        axisLength = axisLength,
    )
    val actionAvailable = when (action) {
        ScrollBoundaryAction.ADD_ABOVE -> available.addAbove
        ScrollBoundaryAction.ADD_BELOW -> available.addBelow
        ScrollBoundaryAction.DELETE_ABOVE -> available.deleteAbove
        ScrollBoundaryAction.DELETE_BELOW -> available.deleteBelow
        ScrollBoundaryAction.TOGGLE_ABOVE -> available.toggleAbove
        ScrollBoundaryAction.TOGGLE_BELOW -> available.toggleBelow
    }
    if (!actionAvailable) return regions
    val changed = when (action) {
        ScrollBoundaryAction.ADD_ABOVE -> if (boundaryIndex == 0) {
            val first = regions.first()
            listOf(
                ScrollExportRegion(
                    start = first.start - SCROLL_BOUNDARY_ADD_OFFSET_PX,
                    end = first.start,
                ),
            ) + regions
        } else {
            splitRegion(
                regions = regions,
                regionIndex = boundaryIndex - 1,
                splitPosition = regions[boundaryIndex - 1].end -
                    SCROLL_BOUNDARY_ADD_OFFSET_PX,
                minimum = minimum,
            )
        }
        ScrollBoundaryAction.ADD_BELOW -> if (boundaryIndex == regions.size) {
            val last = regions.last()
            regions + ScrollExportRegion(
                start = last.end,
                end = last.end + SCROLL_BOUNDARY_ADD_OFFSET_PX,
            )
        } else {
            splitRegion(
                regions = regions,
                regionIndex = boundaryIndex,
                splitPosition = regions[boundaryIndex].start +
                    SCROLL_BOUNDARY_ADD_OFFSET_PX,
                minimum = minimum,
            )
        }
        ScrollBoundaryAction.DELETE_ABOVE -> removeBoundary(regions, boundaryIndex - 1)
        ScrollBoundaryAction.DELETE_BELOW -> removeBoundary(regions, boundaryIndex + 1)
        ScrollBoundaryAction.TOGGLE_ABOVE -> toggleRegion(regions, boundaryIndex - 1)
        ScrollBoundaryAction.TOGGLE_BELOW -> toggleRegion(regions, boundaryIndex)
    }
    if (axisLength <= 0) return regions
    return normalizeScrollExportRegions(
        axisLength = axisLength,
        selectedStart = changed.first().start,
        selectedEnd = axisLength,
        storedRegions = changed,
        maximumSegmentLength = maximumSegmentLength,
        minimumSegmentLength = minimum,
    )
}

internal fun editorMaximumScrollSegmentLength(
    horizontal: Boolean,
    scrollWidth: Int,
    scrollHeight: Int,
    firstFixedSide: FixedSide?,
    firstFixedWidth: Int,
    firstFixedHeight: Int,
    includeFirstFixed: Boolean,
    secondFixedSide: FixedSide?,
    secondFixedWidth: Int,
    secondFixedHeight: Int,
    includeSecondFixed: Boolean,
    outputFormat: ImageOutputFormat = ImageOutputFormat.PNG,
    outputAxisLimit: Int = DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
): Int {
    var outputCrossAxis = if (horizontal) scrollHeight else scrollWidth
    var fixedAxisExtent = 0
    fun include(side: FixedSide?, width: Int, height: Int, enabled: Boolean) {
        if (!enabled || side == null) return
        if (horizontal) {
            if (side == FixedSide.LEFT || side == FixedSide.RIGHT) fixedAxisExtent += width
            else outputCrossAxis += height
        } else {
            if (side == FixedSide.TOP || side == FixedSide.BOTTOM) fixedAxisExtent += height
            else outputCrossAxis += width
        }
    }
    include(firstFixedSide, firstFixedWidth, firstFixedHeight, includeFirstFixed)
    include(secondFixedSide, secondFixedWidth, secondFixedHeight, includeSecondFixed)
    return maximumExportableScrollLength(
        outputAxisLimit = outputAxisLimit.coerceIn(1, DefaultCaptureConfig.MAX_OUTPUT_DIMENSION),
        outputCrossAxisLength = outputCrossAxis,
        fixedAxisExtent = fixedAxisExtent,
        outputFormat = outputFormat,
        outputAxisIsWidth = horizontal,
    )
}

internal fun minimumScrollExportRegionLength(
    horizontal: Boolean,
    fixedLayers: List<CapturedFixedLayer>,
    includeFirstFixed: Boolean,
    includeSecondFixed: Boolean,
    schemaVersion: Int,
): Int {
    if (fixedLayers.isEmpty()) return 1
    val allBySide = fixedLayers.associateBy(CapturedFixedLayer::side)
    fun leadingAdjacentExtent(side: FixedSide): Int = when (side) {
        FixedSide.TOP, FixedSide.BOTTOM -> allBySide[FixedSide.LEFT]?.width ?: 0
        FixedSide.LEFT, FixedSide.RIGHT -> allBySide[FixedSide.TOP]?.height ?: 0
    }.coerceAtLeast(0)
    fun trailingAdjacentExtent(side: FixedSide): Int = when (side) {
        FixedSide.TOP, FixedSide.BOTTOM -> allBySide[FixedSide.RIGHT]?.width ?: 0
        FixedSide.LEFT, FixedSide.RIGHT -> allBySide[FixedSide.BOTTOM]?.height ?: 0
    }.coerceAtLeast(0)
    return fixedLayers.filterIndexed { index, layer ->
        val included = when (index) {
            0 -> includeFirstFixed
            1 -> includeSecondFixed
            else -> true
        }
        included && if (horizontal) {
            layer.side == FixedSide.TOP || layer.side == FixedSide.BOTTOM
        } else {
            layer.side == FixedSide.LEFT || layer.side == FixedSide.RIGHT
        }
    }.maxOfOrNull { layer ->
        val sourceLength = if (horizontal) layer.width else layer.height
        val repeatStart = if (horizontal) layer.repeatRegion?.left else layer.repeatRegion?.top
        val repeatEnd = if (horizontal) layer.repeatRegion?.right else layer.repeatRegion?.bottom
        val window = parallelBandPreviewWindow(
            sourceLength = sourceLength,
            repeatStart = repeatStart,
            repeatEnd = repeatEnd,
            leadingCornerExtent = leadingAdjacentExtent(layer.side),
            trailingCornerExtent = trailingAdjacentExtent(layer.side),
            fixedBandsOwnCorners = schemaVersion >= FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION,
        )
        minimumRepeatedFixedBandTargetLength(
            sourceLength = window.length,
            anchorCount = layer.anchorCount,
            repeatStart = window.repeatStart,
            repeatEnd = window.repeatEnd,
            anchorPosition = layer.anchorPosition,
        )
    } ?: 1
}

internal fun maximumExportableScrollLength(
    outputAxisLimit: Int,
    outputCrossAxisLength: Int,
    fixedAxisExtent: Int,
    maxTotalPixels: Long = DEFAULT_EXPORT_MAX_TOTAL_PIXELS,
    outputFormat: ImageOutputFormat = ImageOutputFormat.PNG,
    outputAxisIsWidth: Boolean = false,
): Int {
    val rawCrossAxis = outputCrossAxisLength.coerceAtLeast(1)
    val encodedCrossAxis = if (outputAxisIsWidth) {
        rawCrossAxis
    } else {
        encodedImageWidth(outputFormat, rawCrossAxis)
    }
    val axisLimit = outputAxisLimit.coerceAtLeast(1)
    val encodedAxisBudget = minOf(
        axisLimit.toLong(),
        maxTotalPixels.coerceAtLeast(encodedCrossAxis.toLong()) / encodedCrossAxis,
    ).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    val rawAxisBudget = if (
        outputAxisIsWidth && outputFormat == ImageOutputFormat.JPEG &&
        encodedAxisBudget % 2 != 0 && encodedAxisBudget > 1
    ) {
        encodedAxisBudget - 1
    } else {
        encodedAxisBudget
    }
    return (rawAxisBudget - fixedAxisExtent.coerceAtLeast(0)).coerceAtLeast(1)
}

private fun splitScrollExportRegion(
    region: ScrollExportRegion,
    maximum: Int,
    minimum: Int,
): List<ScrollExportRegion> {
    // A disabled range stays visible but is not exported, so it does not need artificial
    // maximum-length dividers.
    if (!region.enabled || region.length <= maximum) return listOf(region)
    val effectiveMinimum = minimum.takeIf { it <= maximum } ?: 1
    val lengths = mutableListOf<Int>()
    var remaining = region.length
    while (remaining > maximum) {
        lengths += maximum
        remaining -= maximum
    }
    lengths += remaining
    if (lengths.last() < effectiveMinimum) {
        var needed = effectiveMinimum - lengths.last()
        for (index in lengths.lastIndex - 1 downTo 0) {
            val transferable = (lengths[index] - effectiveMinimum).coerceAtLeast(0)
            val moved = minOf(needed, transferable)
            lengths[index] -= moved
            lengths[lengths.lastIndex] += moved
            needed -= moved
            if (needed == 0) break
        }
    }
    var start = region.start
    return lengths.map { length ->
        ScrollExportRegion(start, start + length, region.enabled).also { start += length }
    }
}

private fun splitRegion(
    regions: List<ScrollExportRegion>,
    regionIndex: Int,
    splitPosition: Int,
    minimum: Int,
): List<ScrollExportRegion> {
    val region = regions.getOrNull(regionIndex) ?: return regions
    if (splitPosition - region.start < minimum || region.end - splitPosition < minimum) {
        return regions
    }
    return buildList(regions.size + 1) {
        addAll(regions.take(regionIndex))
        add(region.copy(end = splitPosition))
        add(region.copy(start = splitPosition))
        addAll(regions.drop(regionIndex + 1))
    }
}

private fun removeBoundary(
    regions: List<ScrollExportRegion>,
    boundaryIndex: Int,
): List<ScrollExportRegion> {
    if (boundaryIndex !in 1 until regions.size) return regions
    val leading = regions[boundaryIndex - 1]
    val trailing = regions[boundaryIndex]
    val merged = ScrollExportRegion(
        start = leading.start,
        end = trailing.end,
        // Never reactivate content merely because the line separating it was removed.
        enabled = leading.enabled && trailing.enabled,
    )
    return buildList(regions.size - 1) {
        addAll(regions.take(boundaryIndex - 1))
        add(merged)
        addAll(regions.drop(boundaryIndex + 1))
    }
}

private fun toggleRegion(
    regions: List<ScrollExportRegion>,
    regionIndex: Int,
): List<ScrollExportRegion> = regions.mapIndexed { index, region ->
    if (index == regionIndex) region.copy(enabled = !region.enabled) else region
}

internal const val DEFAULT_EXPORT_MAX_TOTAL_PIXELS = 100_000_000L
internal const val SCROLL_BOUNDARY_ADD_OFFSET_PX = 200
