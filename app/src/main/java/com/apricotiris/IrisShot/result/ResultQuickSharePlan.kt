package com.apricotiris.IrisShot.result

import com.apricotiris.IrisShot.core.export.ExportResult
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.model.ResultExportRecord
import com.apricotiris.IrisShot.core.model.ScrollExportRegion

internal data class QuickShareFixedLayerGeometry(
    val side: FixedSide,
    val width: Int,
    val height: Int,
)

internal data class ResultQuickSharePlan(
    val result: LayeredCaptureResult,
    val enabledRegions: List<ScrollExportRegion>,
    val includeTop: Boolean,
    val includeBottom: Boolean,
    val availability: ScrollExportAvailability,
) {
    fun displayName(baseName: String, partIndex: Int): String? =
        if (enabledRegions.size > 1) {
            "%s_%02d".format(java.util.Locale.ROOT, baseName, partIndex + 1)
        } else {
            null
        }
}

internal fun buildResultQuickSharePlan(
    result: LayeredCaptureResult,
    scrollWidth: Int,
    scrollHeight: Int,
    firstFixedLayer: QuickShareFixedLayerGeometry?,
    secondFixedLayer: QuickShareFixedLayerGeometry?,
): ResultQuickSharePlan {
    val horizontal = result.templateSnapshot?.scrollDirection?.isVertical == false
    val safeScrollWidth = scrollWidth.coerceAtLeast(1)
    val safeScrollHeight = scrollHeight.coerceAtLeast(1)
    val axisLength = if (horizontal) safeScrollWidth else safeScrollHeight
    val previousExport = result.lastExport
    val includeTop = firstFixedLayer != null && (previousExport?.includeTop ?: true)
    val includeBottom = secondFixedLayer != null && (previousExport?.includeBottom ?: true)
    val minimumSegmentLength = minimumScrollExportRegionLength(
        horizontal = horizontal,
        fixedLayers = result.fixedLayers,
        includeFirstFixed = includeTop,
        includeSecondFixed = includeBottom,
        schemaVersion = result.schemaVersion,
    )
    val maximumSegmentLength = editorMaximumScrollSegmentLength(
        horizontal = horizontal,
        scrollWidth = safeScrollWidth,
        scrollHeight = safeScrollHeight,
        firstFixedSide = firstFixedLayer?.side,
        firstFixedWidth = firstFixedLayer?.width ?: 0,
        firstFixedHeight = firstFixedLayer?.height ?: 0,
        includeFirstFixed = includeTop,
        secondFixedSide = secondFixedLayer?.side,
        secondFixedWidth = secondFixedLayer?.width ?: 0,
        secondFixedHeight = secondFixedLayer?.height ?: 0,
        includeSecondFixed = includeBottom,
        outputFormat = result.outputFormat,
        outputAxisLimit = DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
    )
    val regions = normalizeScrollExportRegions(
        axisLength = axisLength,
        selectedStart = result.selectedScrollStart,
        selectedEnd = result.selectedScrollEnd,
        storedRegions = result.scrollExportRegions,
        maximumSegmentLength = maximumSegmentLength,
        minimumSegmentLength = minimumSegmentLength,
    )
    val normalizedResult = result.copy(
        selectedScrollStart = regions.firstOrNull()?.start ?: 0,
        selectedScrollEnd = regions.lastOrNull()?.end ?: axisLength,
        scrollExportRegions = regions,
    )
    val availability = scrollExportAvailability(
        regions = regions,
        maximumSegmentLength = maximumSegmentLength,
        minimumSegmentLength = minimumSegmentLength,
    )
    return ResultQuickSharePlan(
        result = normalizedResult,
        enabledRegions = if (availability.canExport) {
            regions.filter(ScrollExportRegion::enabled)
        } else {
            emptyList()
        },
        includeTop = includeTop,
        includeBottom = includeBottom,
        availability = availability,
    )
}

internal fun combineQuickShareExportParts(parts: List<ExportResult>): ExportResult {
    require(parts.isNotEmpty())
    val format = parts.first().format
    require(parts.all { it.format == format })
    return ExportResult(
        uris = parts.flatMap(ExportResult::uris),
        format = format,
        width = parts.maxOf(ExportResult::width),
        totalHeight = parts.sumOf(ExportResult::totalHeight),
        segmented = parts.size > 1 || parts.any(ExportResult::segmented),
        displayNames = parts.flatMap(ExportResult::displayNames),
    )
}

/**
 * Applies a legacy fixed-band repair without replacing unrelated edits made after [original]
 * was loaded. A concurrent fixed-band edit wins because the old repair no longer describes it.
 */
internal fun mergeFixedBandMetadataRepairIfCurrent(
    latest: LayeredCaptureResult,
    original: LayeredCaptureResult,
    repaired: LayeredCaptureResult,
): LayeredCaptureResult? {
    if (latest.id != original.id || repaired.id != original.id) return null
    if (latest.schemaVersion == repaired.schemaVersion &&
        latest.fixedLayers == repaired.fixedLayers
    ) {
        return latest
    }
    if (latest.schemaVersion != original.schemaVersion ||
        latest.fixedLayers != original.fixedLayers
    ) {
        return null
    }
    return latest.copy(
        schemaVersion = repaired.schemaVersion,
        fixedLayers = repaired.fixedLayers,
    )
}

/**
 * Attaches a quick-share export only while the persisted result still describes the exact
 * pixels that were exported. The latest object is retained so edits made while sharing are
 * never replaced with the older preparation snapshot.
 */
internal fun mergeQuickShareExportRecordIfCurrent(
    latest: LayeredCaptureResult,
    prepared: LayeredCaptureResult,
    record: ResultExportRecord,
): LayeredCaptureResult? {
    if (latest.id != prepared.id || latest.lastExport != prepared.lastExport) return null
    val includeTop = record.includeTop
    val includeBottom = record.includeBottom
    val preparedMatches = record.matches(
        result = prepared,
        selectedStart = prepared.selectedScrollStart,
        selectedEnd = prepared.selectedScrollEnd,
        includeTop = includeTop,
        includeBottom = includeBottom,
    )
    if (!preparedMatches) return null
    val latestMatches = record.matches(
        result = latest,
        selectedStart = latest.selectedScrollStart,
        selectedEnd = latest.selectedScrollEnd,
        includeTop = includeTop,
        includeBottom = includeBottom,
    )
    return latest.takeIf { latestMatches }?.copy(lastExport = record)
}
