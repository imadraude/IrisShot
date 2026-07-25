package com.apricotiris.IrisShot.result

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.export.ExportResult
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.model.ResultExportRecord
import com.apricotiris.IrisShot.core.model.ScrollExportRegion
import java.security.MessageDigest

internal fun Context.localizedScrollExportUnavailableMessage(
    availability: ScrollExportAvailability,
): String? = when (availability.unavailableReason) {
    ScrollExportUnavailableReason.NO_ENABLED_REGIONS ->
        getString(R.string.result_editor_invalid_scroll_range)
    ScrollExportUnavailableReason.MINIMUM_EXCEEDS_MAXIMUM -> getString(
        R.string.result_editor_export_minimum_exceeds_limit,
        availability.minimumSegmentLength,
        availability.maximumSegmentLength,
    )
    ScrollExportUnavailableReason.REGION_TOO_SHORT -> getString(
        R.string.result_editor_export_region_too_short,
        availability.minimumSegmentLength,
    )
    ScrollExportUnavailableReason.REGION_TOO_LONG -> getString(
        R.string.result_editor_export_region_too_long,
        availability.maximumSegmentLength,
    )
    null -> null
}

internal fun resultExportParameterSignature(
    result: LayeredCaptureResult,
    selectedStart: Int,
    selectedEnd: Int,
    includeTop: Boolean,
    includeBottom: Boolean,
): String = buildResultExportParameterSignature(
    result = result,
    selectedStart = selectedStart,
    selectedEnd = selectedEnd,
    includeTop = includeTop,
    includeBottom = includeBottom,
    includeRegions = true,
)

internal fun legacyResultExportParameterSignature(
    result: LayeredCaptureResult,
    selectedStart: Int,
    selectedEnd: Int,
    includeTop: Boolean,
    includeBottom: Boolean,
): String = buildResultExportParameterSignature(
    result = result,
    selectedStart = selectedStart,
    selectedEnd = selectedEnd,
    includeTop = includeTop,
    includeBottom = includeBottom,
    includeRegions = false,
)

private fun buildResultExportParameterSignature(
    result: LayeredCaptureResult,
    selectedStart: Int,
    selectedEnd: Int,
    includeTop: Boolean,
    includeBottom: Boolean,
    includeRegions: Boolean,
): String {
    val fields = buildList {
        add(if (includeRegions) "result-export-v2" else "result-export-v1")
        add(result.id)
        add(result.schemaVersion.toString())
        add(selectedStart.toString())
        add(selectedEnd.toString())
        if (includeRegions) {
            result.scrollExportRegions.forEach { region ->
                add(region.start.toString())
                add(region.end.toString())
                add(region.enabled.toString())
            }
        }
        add(includeTop.toString())
        add(includeBottom.toString())
        add(result.outputFormat.name)
        add(
            if (result.outputFormat == ImageOutputFormat.JPEG) {
                ImageOutputFormat.normalizeJpegQuality(result.jpegQuality).toString()
            } else {
                "lossless"
            },
        )
        add(result.templateSnapshot?.scrollDirection?.name.orEmpty())
        add(result.scrollContentWidth.toString())
        add(result.scrollContentHeight.toString())
        add(result.scrollLongBitmapPath)
        result.scrollChunks.forEach { chunk ->
            add(chunk.path)
            add(chunk.width.toString())
            add(chunk.height.toString())
        }
        if (result.fixedLayers.isNotEmpty()) {
            result.fixedLayers.forEachIndexed { index, layer ->
                val included = if (index == 0) includeTop else includeBottom
                if (!included) return@forEachIndexed
                add(layer.side.name)
                add(layer.bitmapPath)
                add(layer.width.toString())
                add(layer.height.toString())
                add(layer.anchorCount.toString())
                if (includeRegions) add(layer.anchorPosition.name)
                layer.repeatRegion?.let { repeat ->
                    add(repeat.left.toString())
                    add(repeat.top.toString())
                    add(repeat.right.toString())
                    add(repeat.bottom.toString())
                }
            }
        } else {
            if (includeTop) add(result.topFixedBitmapPath.orEmpty())
            if (includeBottom) add(result.bottomFixedBitmapPath.orEmpty())
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}

internal fun ExportResult.toExportRecord(
    result: LayeredCaptureResult,
    selectedStart: Int,
    selectedEnd: Int,
    includeTop: Boolean,
    includeBottom: Boolean,
    destinationTreeUri: Uri? = null,
    exportedAt: Long = System.currentTimeMillis(),
): ResultExportRecord = ResultExportRecord(
    uris = uris.map(Uri::toString),
    destinationTreeUri = destinationTreeUri?.toString(),
    format = format,
    width = width,
    totalHeight = totalHeight,
    segmented = segmented,
    displayNames = displayNames,
    includeTop = includeTop,
    includeBottom = includeBottom,
    parameterSignature = resultExportParameterSignature(
        result = result,
        selectedStart = selectedStart,
        selectedEnd = selectedEnd,
        includeTop = includeTop,
        includeBottom = includeBottom,
    ),
    exportedAt = exportedAt,
)

internal fun ResultExportRecord.matches(
    result: LayeredCaptureResult,
    selectedStart: Int,
    selectedEnd: Int,
    includeTop: Boolean,
    includeBottom: Boolean,
): Boolean {
    if (uris.isEmpty() || parameterSignature.isBlank()) return false
    if (parameterSignature == resultExportParameterSignature(
        result = result,
        selectedStart = selectedStart,
        selectedEnd = selectedEnd,
        includeTop = includeTop,
        includeBottom = includeBottom,
    )) {
        return true
    }
    val regions = result.scrollExportRegions
    val legacyCompatible = regions.isEmpty() ||
        (regions.size == 1 && regions.single().enabled &&
            regions.single().start == selectedStart && regions.single().end == selectedEnd)
    return legacyCompatible && parameterSignature == legacyResultExportParameterSignature(
        result = result,
        selectedStart = selectedStart,
        selectedEnd = selectedEnd,
        includeTop = includeTop,
        includeBottom = includeBottom,
    )
}

internal fun ResultExportRecord.toExportResult(): ExportResult? {
    val parsedUris = uris.mapNotNull { value ->
        runCatching { Uri.parse(value) }.getOrNull()?.takeIf { it.scheme == "content" }
    }
    if (parsedUris.size != uris.size || parsedUris.isEmpty()) return null
    return ExportResult(
        uris = parsedUris,
        format = format,
        width = width,
        totalHeight = totalHeight,
        segmented = segmented,
        displayNames = displayNames,
    )
}

internal fun ResultExportRecord.hasReadableUris(contentResolver: ContentResolver): Boolean =
    uris.isNotEmpty() && uris.all { value ->
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return@all false
        if (uri.scheme != "content") return@all false
        runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

internal fun mergeResultEditorSelection(
    latest: LayeredCaptureResult,
    selectedStart: Int,
    selectedEnd: Int,
    regions: List<ScrollExportRegion>,
    outputFormat: ImageOutputFormat,
    jpegQuality: Int,
): LayeredCaptureResult = latest.copy(
    selectedScrollStart = selectedStart,
    selectedScrollEnd = selectedEnd,
    scrollExportRegions = regions,
    outputFormat = outputFormat,
    jpegQuality = jpegQuality,
)

/** Merges a completed editor export unless another export or crop won while it was rendering. */
internal fun mergeCompletedResultEditorExport(
    latest: LayeredCaptureResult,
    expected: LayeredCaptureResult,
    exported: LayeredCaptureResult,
    record: ResultExportRecord,
): LayeredCaptureResult? {
    if (latest.id != expected.id || exported.id != expected.id) return null
    val inputsUnchanged = latest.selectedScrollStart == expected.selectedScrollStart &&
        latest.selectedScrollEnd == expected.selectedScrollEnd &&
        latest.scrollExportRegions == expected.scrollExportRegions &&
        latest.outputFormat == expected.outputFormat &&
        latest.jpegQuality == expected.jpegQuality &&
        latest.lastExport == expected.lastExport
    if (!inputsUnchanged) return null
    val merged = mergeResultEditorSelection(
        latest = latest,
        selectedStart = exported.selectedScrollStart,
        selectedEnd = exported.selectedScrollEnd,
        regions = exported.scrollExportRegions,
        outputFormat = exported.outputFormat,
        jpegQuality = exported.jpegQuality,
    ).copy(lastExport = record)
    return merged.takeIf {
        record.matches(
            result = it,
            selectedStart = it.selectedScrollStart,
            selectedEnd = it.selectedScrollEnd,
            includeTop = record.includeTop,
            includeBottom = record.includeBottom,
        )
    }
}
