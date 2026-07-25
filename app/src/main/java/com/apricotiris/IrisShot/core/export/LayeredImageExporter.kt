package com.apricotiris.IrisShot.core.export

import android.content.ContentValues
import android.content.Context
import android.app.ActivityManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.StringRes
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.CapturedFixedLayer
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.model.ScrollChunk
import com.apricotiris.IrisShot.core.storage.StorageLocationPreferences
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess.ManagedRegionDecoder
import com.apricotiris.IrisShot.ui.AppLocaleManager
import com.apricotiris.IrisShot.ui.AppPreferences
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

data class ExportOptions(
    val displayName: String? = null,
    val relativePath: String = "${Environment.DIRECTORY_PICTURES}/GameLongShot",
    val includeTop: Boolean = true,
    val includeBottom: Boolean = true,
    val maxTotalPixels: Long = 100_000_000L,
    val maxDecodedPixels: Long = 24_000_000L,
    /** Null means use the format persisted with the capture result. */
    val format: ImageOutputFormat? = null,
    /** Null means use the JPEG quality persisted with the capture result. */
    val jpegQuality: Int? = null,
    /** Null uses the saved destination, or the default MediaStore album when none is saved. */
    val destinationTreeUri: Uri? = null,
)

data class ExportResult(
    val uris: List<Uri>,
    val format: ImageOutputFormat,
    val width: Int,
    val totalHeight: Long,
    val segmented: Boolean = false,
    val displayNames: List<String>,
) {
    val uri: Uri get() = uris.first()
}

class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal const val JPEG_TILE_MAX_PIXELS = 1_000_000L
private const val EXPLICIT_FIXED_ANCHOR_SCHEMA_VERSION = 8

internal fun encodedImageWidth(format: ImageOutputFormat, width: Int): Int {
    require(width > 0)
    return if (format == ImageOutputFormat.JPEG && width % 2 != 0) {
        Math.addExact(width, 1)
    } else {
        width
    }
}

internal fun canCopyEncodedSourceWithoutReencoding(
    outputFormat: ImageOutputFormat,
    sourceIsPng: Boolean,
    sourceIsJpeg: Boolean,
    requestedJpegQuality: Int?,
): Boolean = when (outputFormat) {
    ImageOutputFormat.PNG -> sourceIsPng
    // A JPEG file does not retain its encoder's requested quality as reliable metadata. When the
    // caller explicitly chooses a quality, re-encode so the exported file actually honors it.
    ImageOutputFormat.JPEG -> sourceIsJpeg && requestedJpegQuality == null
}

private fun FixedSide.isParallelToScroll(horizontalScroll: Boolean): Boolean = when (this) {
    FixedSide.TOP, FixedSide.BOTTOM -> horizontalScroll
    FixedSide.LEFT, FixedSide.RIGHT -> !horizontalScroll
}

internal data class FixedBandMetadataRepairResult(
    val result: LayeredCaptureResult,
    val repairSucceeded: Boolean,
)

private data class FixedLayerMetadataRepairResult(
    val layer: CapturedFixedLayer,
    val repairSucceeded: Boolean,
)

internal fun estimateJpegScrollTilePixels(
    crossAxis: Int,
    mainAxis: Int,
    maxTilePixels: Long = JPEG_TILE_MAX_PIXELS,
): Long {
    if (crossAxis <= 0 || mainAxis <= 0) return 0L
    val tileLength = (maxTilePixels.coerceAtLeast(1L) / crossAxis)
        .coerceIn(1, mainAxis.toLong())
    return crossAxis.toLong() * tileLength
}

/** Full-range BT.601 conversion used by Android's raw-YUV JPEG encoder. */
internal fun rgbToJpegY(color: Int): Int {
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color and 0xFF
    return ((19_595 * red + 38_470 * green + 7_471 * blue + 32_768) shr 16)
        .coerceIn(0, 255)
}

internal fun rgbToJpegU(color: Int): Int {
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color and 0xFF
    return (((-11_059 * red - 21_709 * green + 32_768 * blue + 32_768) shr 16) + 128)
        .coerceIn(0, 255)
}

internal fun rgbToJpegV(color: Int): Int {
    val red = color ushr 16 and 0xFF
    val green = color ushr 8 and 0xFF
    val blue = color and 0xFF
    return (((32_768 * red - 27_439 * green - 5_329 * blue + 32_768) shr 16) + 128)
        .coerceIn(0, 255)
}

/** Writes a PNG or JPEG to MediaStore without repeating source decoding. */
class LayeredImageExporter(context: Context) {
    private val appContext = context.applicationContext
    private val textContext = AppLocaleManager.localizedContext(
        appContext,
        AppPreferences(context).loadAppLanguage(),
    )
    private val resolver = appContext.contentResolver
    private val resourceAccess = StorageResourceAccess(appContext)
    private val locationPreferences = StorageLocationPreferences(appContext)
    private val runtimeBitmapWorkingPixelLimit: Long = run {
        val memoryClassMb = appContext.getSystemService(ActivityManager::class.java).memoryClass.toLong()
        // Keep simultaneously resident bitmap storage below roughly 40% of the app heap; the
        // editor, JPEG encoder and framework allocations retain the remaining headroom.
        (memoryClassMb * 1024L * 1024L / 10L).coerceAtLeast(4_000_000L)
    }

    private fun text(@StringRes id: Int, vararg args: Any): String =
        textContext.getString(id, *args)

    /** Repairs legacy/imported repeat metadata from the fixed-band pixels themselves. */
    internal fun repairFixedBandMetadata(
        result: LayeredCaptureResult,
    ): FixedBandMetadataRepairResult {
        if (result.schemaVersion >= EXPLICIT_FIXED_ANCHOR_SCHEMA_VERSION ||
            result.fixedLayers.isEmpty()
        ) {
            return FixedBandMetadataRepairResult(result, repairSucceeded = true)
        }

        val scrollDirection = result.templateSnapshot?.scrollDirection
            ?: return FixedBandMetadataRepairResult(result, repairSucceeded = false)
        val horizontalScroll = !scrollDirection.isVertical
        val resolvedLayers = ArrayList<CapturedFixedLayer>(result.fixedLayers.size)
        result.fixedLayers.forEach { layer ->
            val repaired = resolveFixedLayerMetadata(layer, horizontalScroll)
            if (!repaired.repairSucceeded) {
                // Keep the legacy result atomic. Persisting only some repaired layers would make
                // a later retry unable to tell which metadata still came from the old schema.
                return FixedBandMetadataRepairResult(result, repairSucceeded = false)
            }
            resolvedLayers += repaired.layer
        }

        return FixedBandMetadataRepairResult(
            result = result.copy(
                schemaVersion = EXPLICIT_FIXED_ANCHOR_SCHEMA_VERSION,
                fixedLayers = resolvedLayers,
            ),
            repairSucceeded = true,
        )
    }

    internal fun resolveFixedBandMetadata(result: LayeredCaptureResult): LayeredCaptureResult =
        repairFixedBandMetadata(result).result

    internal fun deleteExportedOutput(uri: Uri, destinationTreeUri: Uri?) {
        deleteOutput(
            uri = uri,
            options = ExportOptions(destinationTreeUri = destinationTreeUri),
        )
    }

    /** Disk/CPU intensive; callers must invoke this from a worker thread. */
    fun export(
        result: LayeredCaptureResult,
        options: ExportOptions = ExportOptions(),
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: ((completedPercent: Int, totalPercent: Int) -> Unit)? = null,
    ): ExportResult {
        require(options.maxTotalPixels > 0 && options.maxDecodedPixels > 0)
        val effectiveOptions = options.copy(
            destinationTreeUri = options.destinationTreeUri
                ?: locationPreferences.exportTreeUri,
        )
        val outputFormat = options.format ?: result.outputFormat
        val jpegQuality = ImageOutputFormat.normalizeJpegQuality(
            options.jpegQuality ?: result.jpegQuality,
        )
        val safeProgress = onProgress?.let { callback ->
            { completed: Int, total: Int -> runCatching { callback(completed, total) }.getOrNull(); Unit }
        }
        throwIfCancelled(isCancelled)
        if (result.fixedLayers.isNotEmpty()) {
            val resolvedResult = resolveFixedBandMetadata(result)
            return exportFixedBandResult(
                result = resolvedResult,
                options = effectiveOptions,
                format = outputFormat,
                jpegQuality = jpegQuality,
                onProgress = safeProgress,
                isCancelled = isCancelled,
            )
        }
        if (result.scrollChunks.isNotEmpty()) {
            return exportChunked(
                result = result,
                options = effectiveOptions,
                format = outputFormat,
                onProgress = safeProgress,
                isCancelled = isCancelled,
            )
        }
        val scrollInfo = inspect(result.scrollLongBitmapPath)
            ?: throw ExportException(
                text(R.string.export_error_scroll_image_missing, result.scrollLongBitmapPath),
            )
        val horizontal = result.templateSnapshot?.scrollDirection?.isVertical == false
        val axisLength = if (horizontal) scrollInfo.width else scrollInfo.height
        val selectedStart = result.selectedScrollStart.coerceIn(0, axisLength)
        val requestedEnd = result.selectedScrollEnd
        val selectedEnd = if (requestedEnd <= selectedStart) axisLength else {
            requestedEnd.coerceIn(selectedStart + 1, axisLength)
        }
        if (selectedEnd <= selectedStart) {
            throw ExportException(text(R.string.export_error_empty_crop))
        }

        // Before schema 2, horizontal captures could contain top/bottom paths that the old
        // exporter intentionally ignored. Only schema 2+ stores left/right bands in those slots.
        val supportsAxisFixedLayers = !horizontal || result.schemaVersion >= 2
        val leadingPath = result.topFixedBitmapPath.takeIf {
            effectiveOptions.includeTop && supportsAxisFixedLayers
        }
        val trailingPath = result.bottomFixedBitmapPath.takeIf {
            effectiveOptions.includeBottom && supportsAxisFixedLayers
        }
        val leadingInfo = leadingPath?.let { path ->
            inspect(path) ?: throw ExportException(
                text(R.string.export_error_fixed_layer_missing, path),
            )
        }
        val trailingInfo = trailingPath?.let { path ->
            inspect(path) ?: throw ExportException(
                text(R.string.export_error_fixed_layer_missing, path),
            )
        }
        val selectedLength = selectedEnd - selectedStart
        val predictedWidth: Int
        val predictedHeight: Int
        if (horizontal) {
            val leadingWidth = leadingInfo?.scaledWidthForHeight(scrollInfo.height) ?: 0
            val trailingWidth = trailingInfo?.scaledWidthForHeight(scrollInfo.height) ?: 0
            predictedWidth = safeIntDimension(
                leadingWidth.toLong() + selectedLength + trailingWidth,
                R.string.export_dimension_width,
            )
            predictedHeight = scrollInfo.height
        } else {
            val leadingHeight = leadingInfo?.scaledHeightForWidth(scrollInfo.width) ?: 0
            val trailingHeight = trailingInfo?.scaledHeightForWidth(scrollInfo.width) ?: 0
            predictedWidth = scrollInfo.width
            predictedHeight = safeIntDimension(
                leadingHeight.toLong() + selectedLength + trailingHeight,
                R.string.export_dimension_height,
            )
        }
        validateSize(predictedWidth, predictedHeight, effectiveOptions, outputFormat)
        val baseName = safeBaseName(
            effectiveOptions.displayName ?: defaultCaptureExportBaseName(result),
        )
        val displayName = "$baseName.${outputFormat.extension}"
        safeProgress?.invoke(0, 100)

        val canCopySource = leadingPath == null && trailingPath == null &&
            selectedStart == 0 && selectedEnd == axisLength &&
            canCopyEncodedSourceWithoutReencoding(
                outputFormat = outputFormat,
                sourceIsPng = outputFormat == ImageOutputFormat.PNG &&
                    isPng(result.scrollLongBitmapPath),
                sourceIsJpeg = outputFormat == ImageOutputFormat.JPEG &&
                    isJpeg(result.scrollLongBitmapPath),
                requestedJpegQuality = options.jpegQuality,
            )
        if (canCopySource) {
            val uri = copyImageToMediaStore(
                result.scrollLongBitmapPath,
                displayName,
                effectiveOptions,
                outputFormat,
                safeProgress,
                isCancelled,
            )
            return ExportResult(
                uris = listOf(uri),
                format = outputFormat,
                width = scrollInfo.width,
                totalHeight = scrollInfo.height.toLong(),
                displayNames = listOf(displayName),
            )
        }

        if (outputFormat == ImageOutputFormat.JPEG) {
            val plan = buildSingleSourcePlan(
                scrollPath = result.scrollLongBitmapPath,
                scrollInfo = scrollInfo,
                selectedStart = selectedStart,
                selectedEnd = selectedEnd,
                leadingPath = leadingPath,
                leadingInfo = leadingInfo,
                trailingPath = trailingPath,
                trailingInfo = trailingInfo,
                horizontal = horizontal,
            )
            return exportCompositionPlan(
                plan = plan,
                displayName = displayName,
                format = outputFormat,
                jpegQuality = jpegQuality,
                options = effectiveOptions,
                onProgress = safeProgress,
                isCancelled = isCancelled,
            )
        }

        val decodedPixels = scrollInfo.pixelCount +
            (leadingInfo?.pixelCount ?: 0L) + (trailingInfo?.pixelCount ?: 0L)
        val decodeLimit = minOf(effectiveOptions.maxDecodedPixels, runtimeBitmapWorkingPixelLimit)
        if (outputFormat == ImageOutputFormat.PNG && decodedPixels > decodeLimit) {
            throw ExportException(
                text(R.string.export_error_decode_limit, decodedPixels, decodeLimit),
            )
        }
        val jpegScrollTileBudget = if (outputFormat == ImageOutputFormat.JPEG) {
            val crossAxis = if (horizontal) scrollInfo.height else scrollInfo.width
            val outputPixels = predictedWidth.toLong() * predictedHeight
            val auxiliaryBudget = minOf(
                effectiveOptions.maxDecodedPixels,
                (runtimeBitmapWorkingPixelLimit - outputPixels).coerceAtLeast(0L),
            )
            // JPEG supports efficient random region access, so small tiles minimize memory.
            // PNG is sequentially compressed; give it the full remaining bitmap budget to avoid
            // repeatedly decoding a tall historical PNG from the beginning for every tiny tile.
            val scrollTileBudget = if (isPng(result.scrollLongBitmapPath)) {
                auxiliaryBudget
            } else {
                minOf(JPEG_TILE_MAX_PIXELS, auxiliaryBudget)
            }
            val scrollTilePixels = estimateJpegScrollTilePixels(
                crossAxis,
                selectedLength,
                scrollTileBudget,
            )
            val largestAuxiliaryBitmap = maxOf(
                scrollTilePixels,
                leadingInfo?.pixelCount ?: 0L,
                trailingInfo?.pixelCount ?: 0L,
            )
            if (largestAuxiliaryBitmap > auxiliaryBudget) {
                throw ExportException(
                    text(R.string.export_error_jpeg_auxiliary_memory),
                )
            }
            validateJpegWorkingSet(
                outputWidth = predictedWidth,
                outputHeight = predictedHeight,
                auxiliaryPixels = largestAuxiliaryBitmap,
                pixelLimit = runtimeBitmapWorkingPixelLimit,
            )
            scrollTileBudget
        } else 0L

        val owned = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        fun own(bitmap: Bitmap): Bitmap = bitmap.also(owned::add)
        try {
            throwIfCancelled(isCancelled)
            if (outputFormat == ImageOutputFormat.JPEG) {
                return exportJpegTiled(
                    scrollPath = result.scrollLongBitmapPath,
                    scrollInfo = scrollInfo,
                    selectedStart = selectedStart,
                    selectedEnd = selectedEnd,
                    leadingPath = leadingPath,
                    leadingInfo = leadingInfo,
                    trailingPath = trailingPath,
                    trailingInfo = trailingInfo,
                    horizontal = horizontal,
                    outputWidth = predictedWidth,
                    outputHeight = predictedHeight,
                    scrollTilePixelLimit = jpegScrollTileBudget,
                    displayName = displayName,
                    quality = jpegQuality,
                    options = effectiveOptions,
                    onProgress = safeProgress,
                    isCancelled = isCancelled,
                )
            }
            val scroll = own(loadBitmap(result.scrollLongBitmapPath))
            safeProgress?.invoke(5, 100)
            return if (horizontal) {
                exportHorizontal(
                    scroll = scroll,
                    selectedStart = selectedStart,
                    selectedEnd = selectedEnd,
                    leadingPath = leadingPath,
                    trailingPath = trailingPath,
                    displayName = displayName,
                    options = effectiveOptions,
                    owned = owned,
                    own = ::own,
                    onProgress = safeProgress,
                    isCancelled = isCancelled,
                )
            } else {
                exportVertical(
                    scroll = scroll,
                    selectedStart = selectedStart,
                    selectedEnd = selectedEnd,
                    leadingPath = leadingPath,
                    trailingPath = trailingPath,
                    displayName = displayName,
                    options = effectiveOptions,
                    owned = owned,
                    own = ::own,
                    onProgress = safeProgress,
                    isCancelled = isCancelled,
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is ExportException) throw error
            if (error is OutOfMemoryError) {
                throw ExportException(text(R.string.export_error_out_of_memory), error)
            }
            throw ExportException(
                text(
                    R.string.export_error_format_save_failed,
                    outputFormat.displayName,
                    error.message ?: error.javaClass.simpleName,
                ),
                error,
            )
        } finally {
            owned.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        }
    }

    /** Exports schema-7 fixed layers using their physical sides around the scroll layer. */
    private fun exportFixedBandResult(
        result: LayeredCaptureResult,
        options: ExportOptions,
        format: ImageOutputFormat,
        jpegQuality: Int,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): ExportResult {
        val horizontal = result.templateSnapshot?.scrollDirection?.isVertical == false
        val scrollWidth: Int
        val scrollHeight: Int
        val scrollSpans: List<PlannedCompositionSpan>

        if (result.scrollChunks.isNotEmpty()) {
            val chunks = validateScrollChunks(result, horizontal)
            val axisLength = if (horizontal) {
                safeIntDimension(
                    chunks.sumOf { it.width.toLong() },
                    R.string.export_dimension_width,
                )
            } else {
                safeIntDimension(
                    chunks.sumOf { it.height.toLong() },
                    R.string.export_dimension_height,
                )
            }
            val (selectedStart, selectedEnd) = selectedScrollRange(result, axisLength)
            val selectedLength = selectedEnd - selectedStart
            scrollWidth = if (horizontal) selectedLength else chunks.first().width
            scrollHeight = if (horizontal) chunks.first().height else selectedLength
            var cursor = 0
            scrollSpans = buildList {
                chunks.forEach { chunk ->
                    val chunkLength = if (horizontal) chunk.width else chunk.height
                    val chunkEnd = Math.addExact(cursor, chunkLength)
                    val intersectionStart = maxOf(selectedStart, cursor)
                    val intersectionEnd = minOf(selectedEnd, chunkEnd)
                    if (intersectionEnd > intersectionStart) {
                        val localStart = intersectionStart - cursor
                        val localEnd = intersectionEnd - cursor
                        val outputStart = intersectionStart - selectedStart
                        add(
                            if (horizontal) {
                                PlannedCompositionSpan(
                                    sourceId = chunk.path,
                                    source = PixelRect(localStart, 0, localEnd, chunk.height),
                                    destination = PixelRect(
                                        outputStart,
                                        0,
                                        outputStart + localEnd - localStart,
                                        chunk.height,
                                    ),
                                )
                            } else {
                                PlannedCompositionSpan(
                                    sourceId = chunk.path,
                                    source = PixelRect(0, localStart, chunk.width, localEnd),
                                    destination = PixelRect(
                                        0,
                                        outputStart,
                                        chunk.width,
                                        outputStart + localEnd - localStart,
                                    ),
                                )
                            },
                        )
                    }
                    cursor = chunkEnd
                }
            }
            if (scrollSpans.isEmpty()) {
                throw ExportException(text(R.string.export_error_crop_has_no_segments))
            }
        } else {
            val scrollInfo = inspect(result.scrollLongBitmapPath)
                ?: throw ExportException(
                    text(R.string.export_error_scroll_image_missing, result.scrollLongBitmapPath),
                )
            val axisLength = if (horizontal) scrollInfo.width else scrollInfo.height
            val (selectedStart, selectedEnd) = selectedScrollRange(result, axisLength)
            if (horizontal) {
                scrollWidth = selectedEnd - selectedStart
                scrollHeight = scrollInfo.height
                scrollSpans = listOf(
                    PlannedCompositionSpan(
                        sourceId = result.scrollLongBitmapPath,
                        source = PixelRect(selectedStart, 0, selectedEnd, scrollInfo.height),
                        destination = PixelRect(0, 0, scrollWidth, scrollHeight),
                    ),
                )
            } else {
                scrollWidth = scrollInfo.width
                scrollHeight = selectedEnd - selectedStart
                scrollSpans = listOf(
                    PlannedCompositionSpan(
                        sourceId = result.scrollLongBitmapPath,
                        source = PixelRect(0, selectedStart, scrollInfo.width, selectedEnd),
                        destination = PixelRect(0, 0, scrollWidth, scrollHeight),
                    ),
                )
            }
        }

        val allFixedLayersBySide = result.fixedLayers.associateBy(CapturedFixedLayer::side)
        val allFixedLayerInfoBySide = result.fixedLayers.associate { layer ->
            layer.side to inspect(layer.bitmapPath)
        }
        fun leadingAdjacentExtent(side: FixedSide): Int = when (side) {
            FixedSide.TOP, FixedSide.BOTTOM -> allFixedLayerInfoBySide[FixedSide.LEFT]?.width
                ?: allFixedLayersBySide[FixedSide.LEFT]?.width
                ?: 0
            FixedSide.LEFT, FixedSide.RIGHT -> allFixedLayerInfoBySide[FixedSide.TOP]?.height
                ?: allFixedLayersBySide[FixedSide.TOP]?.height
                ?: 0
        }.coerceAtLeast(0)
        fun trailingAdjacentExtent(side: FixedSide): Int = when (side) {
            FixedSide.TOP, FixedSide.BOTTOM -> allFixedLayerInfoBySide[FixedSide.RIGHT]?.width
                ?: allFixedLayersBySide[FixedSide.RIGHT]?.width
                ?: 0
            FixedSide.LEFT, FixedSide.RIGHT -> allFixedLayerInfoBySide[FixedSide.BOTTOM]?.height
                ?: allFixedLayersBySide[FixedSide.BOTTOM]?.height
                ?: 0
        }.coerceAtLeast(0)

        val selectedFixedLayers = result.fixedLayers.filterIndexed { index, _ ->
            when (index) {
                0 -> options.includeTop
                1 -> options.includeBottom
                else -> true
            }
        }
        if (selectedFixedLayers.map { it.side }.distinct().size != selectedFixedLayers.size) {
            throw ExportException(text(R.string.export_error_duplicate_fixed_side))
        }
        val fixedBands = selectedFixedLayers.mapIndexed { index, layer ->
            val info = allFixedLayerInfoBySide[layer.side] ?: inspect(layer.bitmapPath)
                ?: throw ExportException(
                    text(
                        R.string.export_error_fixed_region_missing,
                        index + 1,
                        layer.bitmapPath,
                    ),
                )
            if ((layer.width > 0 && layer.width != info.width) ||
                (layer.height > 0 && layer.height != info.height)
            ) {
                throw ExportException(
                    text(
                        R.string.export_error_fixed_region_size_mismatch,
                        index + 1,
                        layer.width,
                        layer.height,
                        info.width,
                        info.height,
                    ),
                )
            }
            capturedFixedBandSource(
                layer = layer,
                actualWidth = info.width,
                actualHeight = info.height,
                repeatSourceLeadingInset = if (
                    result.schemaVersion < FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION &&
                    layer.side.isParallelToScroll(horizontal)
                ) {
                    leadingAdjacentExtent(layer.side)
                } else {
                    0
                },
                repeatSourceTrailingInset = if (
                    result.schemaVersion < FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION &&
                    layer.side.isParallelToScroll(horizontal)
                ) {
                    trailingAdjacentExtent(layer.side)
                } else {
                    0
                },
                leadingCornerExtent = if (!layer.side.isParallelToScroll(horizontal)) {
                    leadingAdjacentExtent(layer.side)
                } else {
                    0
                },
                trailingCornerExtent = if (!layer.side.isParallelToScroll(horizontal)) {
                    trailingAdjacentExtent(layer.side)
                } else {
                    0
                },
            )
        }
        val planned = try {
            FixedBandCompositionPlanner.build(
                horizontalScroll = horizontal,
                scrollWidth = scrollWidth,
                scrollHeight = scrollHeight,
                scrollSpans = scrollSpans,
                fixedBands = fixedBands,
            )
        } catch (error: IllegalArgumentException) {
            throw ExportException(text(R.string.export_error_fixed_composition_invalid), error)
        } catch (error: ArithmeticException) {
            throw ExportException(text(R.string.export_error_fixed_composition_too_large), error)
        }
        val plan = CompositionPlan(
            width = planned.width,
            height = planned.height,
            spans = planned.spans.map { span ->
                CompositionSpan(
                    path = span.sourceId,
                    source = span.source.toAndroidRect(),
                    destination = span.destination.toAndroidRect(),
                )
            },
        )
        val baseName = safeBaseName(options.displayName ?: defaultCaptureExportBaseName(result))
        return exportCompositionPlan(
            plan = plan,
            displayName = "$baseName.${format.extension}",
            format = format,
            jpegQuality = jpegQuality,
            options = options,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    private fun selectedScrollRange(result: LayeredCaptureResult, axisLength: Int): Pair<Int, Int> {
        val selectedStart = result.selectedScrollStart.coerceIn(0, axisLength)
        val requestedEnd = result.selectedScrollEnd
        val selectedEnd = if (requestedEnd <= selectedStart) {
            axisLength
        } else {
            requestedEnd.coerceIn(selectedStart + 1, axisLength)
        }
        if (selectedEnd <= selectedStart) {
            throw ExportException(text(R.string.export_error_empty_crop))
        }
        return selectedStart to selectedEnd
    }

    private fun PixelRect.toAndroidRect(): Rect = Rect(left, top, right, bottom)

    /** Exports all private scroll chunks as one public image. */
    private fun exportChunked(
        result: LayeredCaptureResult,
        options: ExportOptions,
        format: ImageOutputFormat,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): ExportResult {
        val horizontal = result.templateSnapshot?.scrollDirection?.isVertical == false
        val chunks = validateScrollChunks(result, horizontal)
        val axisLength = if (horizontal) {
            safeIntDimension(
                chunks.sumOf { it.width.toLong() },
                R.string.export_dimension_width,
            )
        } else {
            safeIntDimension(
                chunks.sumOf { it.height.toLong() },
                R.string.export_dimension_height,
            )
        }
        val selectedStart = result.selectedScrollStart.coerceIn(0, axisLength)
        val requestedEnd = result.selectedScrollEnd
        val selectedEnd = if (requestedEnd <= selectedStart) axisLength else {
            requestedEnd.coerceIn(selectedStart + 1, axisLength)
        }
        if (selectedEnd <= selectedStart) {
            throw ExportException(text(R.string.export_error_empty_crop))
        }

        var cursor = 0
        val selections = buildList {
            chunks.forEach { chunk ->
                val chunkLength = if (horizontal) chunk.width else chunk.height
                val chunkEnd = cursor + chunkLength
                val intersectionStart = maxOf(selectedStart, cursor)
                val intersectionEnd = minOf(selectedEnd, chunkEnd)
                if (intersectionEnd > intersectionStart) {
                    add(
                        ChunkSelection(
                            chunk = chunk,
                            localStart = intersectionStart - cursor,
                            localEnd = intersectionEnd - cursor,
                            outputStart = intersectionStart - selectedStart,
                        ),
                    )
                }
                cursor = chunkEnd
            }
        }
        if (selections.isEmpty()) {
            throw ExportException(text(R.string.export_error_crop_has_no_segments))
        }

        val baseName = safeBaseName(options.displayName ?: defaultCaptureExportBaseName(result))
        val displayName = "$baseName.${format.extension}"
        val leadingPath = result.topFixedBitmapPath.takeIf { options.includeTop }
        val trailingPath = result.bottomFixedBitmapPath.takeIf { options.includeBottom }
        val leadingInfo = leadingPath?.let { path ->
            inspect(path) ?: throw ExportException(
                text(R.string.export_error_fixed_layer_missing, path),
            )
        }
        val trailingInfo = trailingPath?.let { path ->
            inspect(path) ?: throw ExportException(
                text(R.string.export_error_fixed_layer_missing, path),
            )
        }
        val selectedLength = selectedEnd - selectedStart
        val plan = buildChunkedCompositionPlan(
            horizontal = horizontal,
            scrollCrossAxis = if (horizontal) chunks.first().height else chunks.first().width,
            selectedLength = selectedLength,
            selections = selections,
            leadingPath = leadingPath,
            leadingInfo = leadingInfo,
            trailingPath = trailingPath,
            trailingInfo = trailingInfo,
        )
        return exportCompositionPlan(
            plan = plan,
            displayName = displayName,
            format = format,
            jpegQuality = ImageOutputFormat.normalizeJpegQuality(
                options.jpegQuality ?: result.jpegQuality,
            ),
            options = options,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
    }

    private fun validateScrollChunks(
        result: LayeredCaptureResult,
        horizontal: Boolean,
    ): List<ScrollChunk> {
        val chunks = result.scrollChunks
        if (chunks.isEmpty()) throw ExportException(text(R.string.export_error_chunks_empty))
        chunks.forEachIndexed { index, chunk ->
            if (chunk.path.isBlank() || chunk.width <= 0 || chunk.height <= 0) {
                throw ExportException(
                    text(R.string.export_error_chunk_metadata_invalid, index + 1),
                )
            }
            val actual = inspect(chunk.path)
                ?: throw ExportException(
                    text(R.string.export_error_chunk_missing, index + 1, chunk.path),
                )
            if (actual.width != chunk.width || actual.height != chunk.height) {
                throw ExportException(
                    text(
                        R.string.export_error_chunk_size_mismatch,
                        index + 1,
                        chunk.width,
                        chunk.height,
                        actual.width,
                        actual.height,
                    ),
                )
            }
        }

        val derivedWidth: Int
        val derivedHeight: Int
        if (horizontal) {
            val expectedHeight = chunks.first().height
            if (chunks.any { it.height != expectedHeight }) {
                throw ExportException(
                    text(R.string.export_error_horizontal_chunk_height_mismatch),
                )
            }
            derivedWidth = safeIntDimension(
                chunks.sumOf { it.width.toLong() },
                R.string.export_dimension_width,
            )
            derivedHeight = expectedHeight
        } else {
            val expectedWidth = chunks.first().width
            if (chunks.any { it.width != expectedWidth }) {
                throw ExportException(
                    text(R.string.export_error_vertical_chunk_width_mismatch),
                )
            }
            derivedWidth = expectedWidth
            derivedHeight = safeIntDimension(
                chunks.sumOf { it.height.toLong() },
                R.string.export_dimension_height,
            )
        }
        val contentWidth = result.scrollContentWidth.takeIf { it > 0 } ?: derivedWidth
        val contentHeight = result.scrollContentHeight.takeIf { it > 0 } ?: derivedHeight
        if (contentWidth != derivedWidth || contentHeight != derivedHeight) {
            throw ExportException(
                text(
                    R.string.export_error_chunk_content_size_mismatch,
                    contentWidth,
                    contentHeight,
                    derivedWidth,
                    derivedHeight,
                ),
            )
        }
        return chunks
    }

    private fun buildSingleSourcePlan(
        scrollPath: String,
        scrollInfo: ImageInfo,
        selectedStart: Int,
        selectedEnd: Int,
        leadingPath: String?,
        leadingInfo: ImageInfo?,
        trailingPath: String?,
        trailingInfo: ImageInfo?,
        horizontal: Boolean,
    ): CompositionPlan {
        val selectedLength = selectedEnd - selectedStart
        val spans = mutableListOf<CompositionSpan>()
        return if (horizontal) {
            val leadingWidth = leadingInfo?.scaledWidthForHeight(scrollInfo.height) ?: 0
            val trailingWidth = trailingInfo?.scaledWidthForHeight(scrollInfo.height) ?: 0
            val width = safeIntDimension(
                leadingWidth.toLong() + selectedLength + trailingWidth,
                R.string.export_dimension_width,
            )
            leadingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, leadingInfo!!.width, leadingInfo.height),
                    Rect(0, 0, leadingWidth, scrollInfo.height),
                )
            }
            spans += CompositionSpan(
                scrollPath,
                Rect(selectedStart, 0, selectedEnd, scrollInfo.height),
                Rect(leadingWidth, 0, leadingWidth + selectedLength, scrollInfo.height),
            )
            trailingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, trailingInfo!!.width, trailingInfo.height),
                    Rect(width - trailingWidth, 0, width, scrollInfo.height),
                )
            }
            CompositionPlan(width, scrollInfo.height, spans)
        } else {
            val leadingHeight = leadingInfo?.scaledHeightForWidth(scrollInfo.width) ?: 0
            val trailingHeight = trailingInfo?.scaledHeightForWidth(scrollInfo.width) ?: 0
            val height = safeIntDimension(
                leadingHeight.toLong() + selectedLength + trailingHeight,
                R.string.export_dimension_height,
            )
            leadingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, leadingInfo!!.width, leadingInfo.height),
                    Rect(0, 0, scrollInfo.width, leadingHeight),
                )
            }
            spans += CompositionSpan(
                scrollPath,
                Rect(0, selectedStart, scrollInfo.width, selectedEnd),
                Rect(0, leadingHeight, scrollInfo.width, leadingHeight + selectedLength),
            )
            trailingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, trailingInfo!!.width, trailingInfo.height),
                    Rect(0, height - trailingHeight, scrollInfo.width, height),
                )
            }
            CompositionPlan(scrollInfo.width, height, spans)
        }
    }

    private fun buildChunkedCompositionPlan(
        horizontal: Boolean,
        scrollCrossAxis: Int,
        selectedLength: Int,
        selections: List<ChunkSelection>,
        leadingPath: String?,
        leadingInfo: ImageInfo?,
        trailingPath: String?,
        trailingInfo: ImageInfo?,
    ): CompositionPlan {
        val spans = mutableListOf<CompositionSpan>()
        return if (horizontal) {
            val leadingWidth = leadingInfo?.scaledWidthForHeight(scrollCrossAxis) ?: 0
            val trailingWidth = trailingInfo?.scaledWidthForHeight(scrollCrossAxis) ?: 0
            val width = safeIntDimension(
                leadingWidth.toLong() + selectedLength + trailingWidth,
                R.string.export_dimension_width,
            )
            leadingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, leadingInfo!!.width, leadingInfo.height),
                    Rect(0, 0, leadingWidth, scrollCrossAxis),
                )
            }
            selections.forEach { selection ->
                val length = selection.localEnd - selection.localStart
                val left = leadingWidth + selection.outputStart
                spans += CompositionSpan(
                    selection.chunk.path,
                    Rect(
                        selection.localStart,
                        0,
                        selection.localEnd,
                        selection.chunk.height,
                    ),
                    Rect(left, 0, left + length, scrollCrossAxis),
                )
            }
            trailingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, trailingInfo!!.width, trailingInfo.height),
                    Rect(width - trailingWidth, 0, width, scrollCrossAxis),
                )
            }
            CompositionPlan(width, scrollCrossAxis, spans)
        } else {
            val leadingHeight = leadingInfo?.scaledHeightForWidth(scrollCrossAxis) ?: 0
            val trailingHeight = trailingInfo?.scaledHeightForWidth(scrollCrossAxis) ?: 0
            val height = safeIntDimension(
                leadingHeight.toLong() + selectedLength + trailingHeight,
                R.string.export_dimension_height,
            )
            leadingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, leadingInfo!!.width, leadingInfo.height),
                    Rect(0, 0, scrollCrossAxis, leadingHeight),
                )
            }
            selections.forEach { selection ->
                val length = selection.localEnd - selection.localStart
                val top = leadingHeight + selection.outputStart
                spans += CompositionSpan(
                    selection.chunk.path,
                    Rect(
                        0,
                        selection.localStart,
                        selection.chunk.width,
                        selection.localEnd,
                    ),
                    Rect(0, top, scrollCrossAxis, top + length),
                )
            }
            trailingPath?.let { path ->
                spans += CompositionSpan(
                    path,
                    Rect(0, 0, trailingInfo!!.width, trailingInfo.height),
                    Rect(0, height - trailingHeight, scrollCrossAxis, height),
                )
            }
            CompositionPlan(scrollCrossAxis, height, spans)
        }
    }

    private fun exportCompositionPlan(
        plan: CompositionPlan,
        displayName: String,
        format: ImageOutputFormat,
        jpegQuality: Int,
        options: ExportOptions,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): ExportResult {
        // YUV 4:2:0/4:2:2 requires an even width. Preserve every selected source pixel and add a
        // single white padding column instead of rejecting a valid odd-width crop.
        val encodedWidth = encodedImageWidth(format, plan.width)
        val encodedPlan = if (encodedWidth != plan.width) {
            plan.copy(width = encodedWidth)
        } else {
            plan
        }
        validateSize(encodedPlan.width, encodedPlan.height, options, format)
        onProgress?.invoke(0, 100)
        val uri = when (format) {
            ImageOutputFormat.PNG -> {
                CompositionRowProvider(resourceAccess, encodedPlan, isCancelled).use { rows ->
                    writePngToMediaStore(
                        displayName = displayName,
                        width = encodedPlan.width,
                        height = encodedPlan.height,
                        options = options,
                        onProgress = onProgress,
                        isCancelled = isCancelled,
                        rowProvider = rows::row,
                    )
                }
            }
            ImageOutputFormat.JPEG -> writeYuvJpegToMediaStore(
                plan = encodedPlan,
                displayName = displayName,
                quality = jpegQuality,
                options = options,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
        }
        return ExportResult(
            uris = listOf(uri),
            format = format,
            width = encodedPlan.width,
            totalHeight = encodedPlan.height.toLong(),
            segmented = false,
            displayNames = listOf(displayName),
        )
    }

    private fun writeYuvJpegToMediaStore(
        plan: CompositionPlan,
        displayName: String,
        quality: Int,
        options: ExportOptions,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): Uri {
        if (plan.width > JPEG_MAX_DIMENSION || plan.height > JPEG_MAX_DIMENSION) {
            throw ExportException(
                text(R.string.export_error_jpeg_dimension_use_png, JPEG_MAX_DIMENSION),
            )
        }
        val useNv21 = plan.height % 2 == 0
        val bytesPerImage = if (useNv21) {
            plan.width.toLong() * plan.height * 3L / 2L
        } else {
            plan.width.toLong() * plan.height * 2L
        }
        if (bytesPerImage > Int.MAX_VALUE) {
            throw ExportException(text(R.string.export_error_jpeg_array_limit))
        }
        val runtime = Runtime.getRuntime()
        val reserve = maxOf(24L * 1024L * 1024L, runtime.maxMemory() / 8L)
        val minimumBandPixels = plan.width.toLong() * if (useNv21) 2L else 1L
        val minimumWorkingBytes = minimumBandPixels * COMPOSITION_BYTES_PER_PIXEL_ESTIMATE
        if (bytesPerImage + minimumWorkingBytes + reserve > runtime.maxMemory()) {
            throw ExportException(
                text(
                    R.string.export_error_jpeg_buffer_memory,
                    bytesPerImage / 1024L / 1024L,
                ),
            )
        }

        val yuv = try {
            ByteArray(bytesPerImage.toInt())
        } catch (error: OutOfMemoryError) {
            throw ExportException(
                text(
                    R.string.export_error_jpeg_buffer_allocation,
                    bytesPerImage / 1024L / 1024L,
                ),
                error,
            )
        }
        val usedHeap = runtime.totalMemory() - runtime.freeMemory()
        val currentlyAvailable = (runtime.maxMemory() - usedHeap - reserve).coerceAtLeast(0L)
        val safeBandPixels = minOf(
            COMPOSITE_BAND_MAX_PIXELS,
            (currentlyAvailable / COMPOSITION_BYTES_PER_PIXEL_ESTIMATE)
                .coerceAtLeast(minimumBandPixels),
        )
        try {
            CompositionRowReader(resourceAccess, plan, isCancelled, safeBandPixels).use { reader ->
                if (useNv21) {
                    fillNv21(yuv, plan, reader, safeBandPixels, onProgress, isCancelled)
                } else {
                    fillYuy2(yuv, plan, reader, safeBandPixels, onProgress, isCancelled)
                }
            }
        } catch (error: OutOfMemoryError) {
            throw ExportException(
                text(R.string.export_error_jpeg_composition_memory),
                error,
            )
        }
        throwIfCancelled(isCancelled)

        val uri = createPendingImage(displayName, options, ImageOutputFormat.JPEG)
        try {
            resolver.openOutputStream(uri, "w")?.use { raw ->
                BufferedOutputStream(raw, IO_BUFFER_SIZE).use { output ->
                    onProgress?.invoke(72, 100)
                    val image = YuvImage(
                        yuv,
                        if (useNv21) ImageFormat.NV21 else ImageFormat.YUY2,
                        plan.width,
                        plan.height,
                        null,
                    )
                    check(
                        image.compressToJpeg(
                            Rect(0, 0, plan.width, plan.height),
                            ImageOutputFormat.normalizeJpegQuality(quality),
                            output,
                        ),
                    ) { text(R.string.export_error_jpeg_system_encoding) }
                }
            } ?: throw ExportException(text(R.string.export_error_open_output_stream))
            throwIfCancelled(isCancelled)
            val encoded = inspect(uri)
            if (encoded?.width != plan.width || encoded.height != plan.height) {
                throw ExportException(text(R.string.export_error_jpeg_dimension_validation))
            }
            onProgress?.invoke(97, 100)
            publishPendingImage(uri, options)
            onProgress?.invoke(100, 100)
            return uri
        } catch (error: Throwable) {
            deleteOutput(uri, options)
            if (error is ExportException) throw error
            if (error is CancellationException) throw error
            throw ExportException(
                text(
                    R.string.export_error_jpeg_save_failed,
                    error.message ?: error.javaClass.simpleName,
                ),
                error,
            )
        }
    }

    private fun fillNv21(
        output: ByteArray,
        plan: CompositionPlan,
        reader: CompositionRowReader,
        maxBandPixels: Long,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ) {
        val width = plan.width
        val height = plan.height
        val frameSize = width * height
        var y = 0
        val requestedRows = evenBandRowCount(width, maxBandPixels)
        while (y < height) {
            throwIfCancelled(isCancelled)
            val rowCount = minOf(requestedRows, height - y)
            val pixels = reader.readRows(y, rowCount)
            var localY = 0
            while (localY < rowCount) {
                var x = 0
                val firstRow = localY * width
                val secondRow = firstRow + width
                while (x < width) {
                    val p0 = flattenOnWhite(pixels[firstRow + x])
                    val p1 = flattenOnWhite(pixels[firstRow + x + 1])
                    val p2 = flattenOnWhite(pixels[secondRow + x])
                    val p3 = flattenOnWhite(pixels[secondRow + x + 1])
                    val globalTop = (y + localY) * width + x
                    output[globalTop] = rgbToJpegY(p0).toByte()
                    output[globalTop + 1] = rgbToJpegY(p1).toByte()
                    output[globalTop + width] = rgbToJpegY(p2).toByte()
                    output[globalTop + width + 1] = rgbToJpegY(p3).toByte()
                    val chroma = frameSize + ((y + localY) / 2) * width + x
                    output[chroma] = average4(
                        rgbToJpegV(p0),
                        rgbToJpegV(p1),
                        rgbToJpegV(p2),
                        rgbToJpegV(p3),
                    ).toByte()
                    output[chroma + 1] = average4(
                        rgbToJpegU(p0),
                        rgbToJpegU(p1),
                        rgbToJpegU(p2),
                        rgbToJpegU(p3),
                    ).toByte()
                    x += 2
                }
                localY += 2
            }
            y += rowCount
            onProgress?.invoke(5 + (y.toLong() * 62L / height).toInt(), 100)
        }
    }

    private fun fillYuy2(
        output: ByteArray,
        plan: CompositionPlan,
        reader: CompositionRowReader,
        maxBandPixels: Long,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ) {
        val width = plan.width
        val height = plan.height
        val requestedRows = bandRowCount(width, maxBandPixels)
        var y = 0
        while (y < height) {
            throwIfCancelled(isCancelled)
            val rowCount = minOf(requestedRows, height - y)
            val pixels = reader.readRows(y, rowCount)
            for (localY in 0 until rowCount) {
                val sourceRow = localY * width
                var target = (y + localY) * width * 2
                var x = 0
                while (x < width) {
                    val first = flattenOnWhite(pixels[sourceRow + x])
                    val second = flattenOnWhite(pixels[sourceRow + x + 1])
                    output[target++] = rgbToJpegY(first).toByte()
                    output[target++] = ((rgbToJpegU(first) + rgbToJpegU(second) + 1) / 2).toByte()
                    output[target++] = rgbToJpegY(second).toByte()
                    output[target++] = ((rgbToJpegV(first) + rgbToJpegV(second) + 1) / 2).toByte()
                    x += 2
                }
            }
            y += rowCount
            onProgress?.invoke(5 + (y.toLong() * 62L / height).toInt(), 100)
        }
    }

    private fun bandRowCount(width: Int, maxBandPixels: Long = COMPOSITE_BAND_MAX_PIXELS): Int =
        (maxBandPixels / width.coerceAtLeast(1).toLong())
            .coerceIn(1L, 1_024L)
            .toInt()

    private fun evenBandRowCount(width: Int, maxBandPixels: Long): Int {
        val rows = bandRowCount(width, maxBandPixels).coerceAtLeast(2)
        return rows - rows % 2
    }

    private fun flattenOnWhite(color: Int): Int {
        val alpha = color ushr 24 and 0xFF
        if (alpha == 255) return color
        if (alpha == 0) return Color.WHITE
        val inverse = 255 - alpha
        val red = ((color ushr 16 and 0xFF) * alpha + 255 * inverse + 127) / 255
        val green = ((color ushr 8 and 0xFF) * alpha + 255 * inverse + 127) / 255
        val blue = ((color and 0xFF) * alpha + 255 * inverse + 127) / 255
        return Color.rgb(red, green, blue)
    }

    private fun average4(first: Int, second: Int, third: Int, fourth: Int): Int =
        (first + second + third + fourth + 2) / 4

    private fun inspect(uri: Uri): ImageInfo? = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                ImageInfo(bounds.outWidth, bounds.outHeight)
            } else null
        }
    }.getOrNull()

    /**
     * Composes JPEG into one final bitmap while decoding the long scroll layer in bounded tiles.
     * This avoids retaining a second full-size source bitmap beside the output bitmap.
     */
    private fun exportJpegTiled(
        scrollPath: String,
        scrollInfo: ImageInfo,
        selectedStart: Int,
        selectedEnd: Int,
        leadingPath: String?,
        leadingInfo: ImageInfo?,
        trailingPath: String?,
        trailingInfo: ImageInfo?,
        horizontal: Boolean,
        outputWidth: Int,
        outputHeight: Int,
        scrollTilePixelLimit: Long,
        displayName: String,
        quality: Int,
        options: ExportOptions,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): ExportResult {
        throwIfCancelled(isCancelled)
        val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output).apply { drawColor(Color.WHITE) }
            onProgress?.invoke(5, 100)
            if (horizontal) {
                val leadingWidth = leadingInfo?.scaledWidthForHeight(scrollInfo.height) ?: 0
                val trailingWidth = trailingInfo?.scaledWidthForHeight(scrollInfo.height) ?: 0
                leadingPath?.let { path ->
                    drawFileLayer(
                        canvas,
                        path,
                        Rect(0, 0, leadingWidth, outputHeight),
                        isCancelled,
                    )
                }
                drawScrollTiles(
                    canvas = canvas,
                    path = scrollPath,
                    source = Rect(selectedStart, 0, selectedEnd, scrollInfo.height),
                    destinationLeft = leadingWidth,
                    destinationTop = 0,
                    horizontal = true,
                    tilePixelLimit = scrollTilePixelLimit,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                trailingPath?.let { path ->
                    drawFileLayer(
                        canvas,
                        path,
                        Rect(outputWidth - trailingWidth, 0, outputWidth, outputHeight),
                        isCancelled,
                    )
                }
            } else {
                val leadingHeight = leadingInfo?.scaledHeightForWidth(scrollInfo.width) ?: 0
                val trailingHeight = trailingInfo?.scaledHeightForWidth(scrollInfo.width) ?: 0
                leadingPath?.let { path ->
                    drawFileLayer(
                        canvas,
                        path,
                        Rect(0, 0, outputWidth, leadingHeight),
                        isCancelled,
                    )
                }
                drawScrollTiles(
                    canvas = canvas,
                    path = scrollPath,
                    source = Rect(0, selectedStart, scrollInfo.width, selectedEnd),
                    destinationLeft = 0,
                    destinationTop = leadingHeight,
                    horizontal = false,
                    tilePixelLimit = scrollTilePixelLimit,
                    onProgress = onProgress,
                    isCancelled = isCancelled,
                )
                trailingPath?.let { path ->
                    drawFileLayer(
                        canvas,
                        path,
                        Rect(0, outputHeight - trailingHeight, outputWidth, outputHeight),
                        isCancelled,
                    )
                }
            }
            onProgress?.invoke(35, 100)
            val uri = writeJpegToMediaStore(
                displayName = displayName,
                bitmap = output,
                quality = quality,
                options = options,
                onProgress = onProgress,
                isCancelled = isCancelled,
            )
            return ExportResult(
                uris = listOf(uri),
                format = ImageOutputFormat.JPEG,
                width = outputWidth,
                totalHeight = outputHeight.toLong(),
                displayNames = listOf(displayName),
            )
        } finally {
            if (!output.isRecycled) output.recycle()
        }
    }

    private fun drawFileLayer(
        canvas: Canvas,
        path: String,
        destination: Rect,
        isCancelled: () -> Boolean,
    ) {
        if (destination.isEmpty) return
        throwIfCancelled(isCancelled)
        val bitmap = loadBitmap(path)
        try {
            canvas.drawBitmap(bitmap, null, destination, FILTER_BITMAP_PAINT)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun drawScrollTiles(
        canvas: Canvas,
        path: String,
        source: Rect,
        destinationLeft: Int,
        destinationTop: Int,
        horizontal: Boolean,
        tilePixelLimit: Long,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ) {
        val decoder = resourceAccess.openRegionDecoder(path)
            ?: throw ExportException(text(R.string.export_error_open_scroll_decoder, path))
        try {
            val crossAxis = if (horizontal) source.height() else source.width()
            val mainLength = if (horizontal) source.width() else source.height()
            val tileLength = (tilePixelLimit.coerceAtLeast(1L) / crossAxis.coerceAtLeast(1))
                .coerceIn(1, mainLength.toLong())
                .toInt()
            var completed = 0
            while (completed < mainLength) {
                throwIfCancelled(isCancelled)
                val next = minOf(mainLength, completed + tileLength)
                val tileRegion = if (horizontal) {
                    Rect(source.left + completed, source.top, source.left + next, source.bottom)
                } else {
                    Rect(source.left, source.top + completed, source.right, source.top + next)
                }
                val tile = decoder.decodeRegion(
                    tileRegion,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ) ?: throw ExportException(text(R.string.export_error_decode_scroll_tile))
                try {
                    val left = if (horizontal) destinationLeft + completed else destinationLeft
                    val top = if (horizontal) destinationTop else destinationTop + completed
                    canvas.drawBitmap(tile, left.toFloat(), top.toFloat(), null)
                } finally {
                    if (!tile.isRecycled) tile.recycle()
                }
                completed = next
                onProgress?.invoke(8 + (completed * 22L / mainLength).toInt(), 100)
            }
        } finally {
            decoder.close()
        }
    }

    private fun exportVertical(
        scroll: Bitmap,
        selectedStart: Int,
        selectedEnd: Int,
        leadingPath: String?,
        trailingPath: String?,
        displayName: String,
        options: ExportOptions,
        owned: MutableSet<Bitmap>,
        own: (Bitmap) -> Bitmap,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): ExportResult {
        throwIfCancelled(isCancelled)
        val leading = leadingPath?.let(::loadBitmap)?.let(own)?.let {
            alignWidth(it, scroll.width, owned, own)
        }
        val trailing = trailingPath?.let(::loadBitmap)?.let(own)?.let {
            alignWidth(it, scroll.width, owned, own)
        }
        val scrollHeight = selectedEnd - selectedStart
        val outputWidth = scroll.width
        val outputHeight = (leading?.height ?: 0) + scrollHeight + (trailing?.height ?: 0)
        validateSize(outputWidth, outputHeight, options, ImageOutputFormat.PNG)

        val leadingHeight = leading?.height ?: 0
        val scrollEndY = leadingHeight + scrollHeight
        val row = IntArray(outputWidth)
        val uri = writePngToMediaStore(
            displayName = displayName,
            width = outputWidth,
            height = outputHeight,
            options = options,
            onProgress = onProgress,
            isCancelled = isCancelled,
        ) { y ->
            when {
                y < leadingHeight -> leading!!.getPixels(row, 0, outputWidth, 0, y, outputWidth, 1)
                y < scrollEndY -> scroll.getPixels(
                    row,
                    0,
                    outputWidth,
                    0,
                    selectedStart + y - leadingHeight,
                    outputWidth,
                    1,
                )
                else -> trailing!!.getPixels(
                    row,
                    0,
                    outputWidth,
                    0,
                    y - scrollEndY,
                    outputWidth,
                    1,
                )
            }
            row
        }
        return ExportResult(
            uris = listOf(uri),
            format = ImageOutputFormat.PNG,
            width = outputWidth,
            totalHeight = outputHeight.toLong(),
            displayNames = listOf(displayName),
        )
    }

    private fun exportHorizontal(
        scroll: Bitmap,
        selectedStart: Int,
        selectedEnd: Int,
        leadingPath: String?,
        trailingPath: String?,
        displayName: String,
        options: ExportOptions,
        owned: MutableSet<Bitmap>,
        own: (Bitmap) -> Bitmap,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): ExportResult {
        throwIfCancelled(isCancelled)
        val leading = leadingPath?.let(::loadBitmap)?.let(own)?.let {
            alignHeight(it, scroll.height, owned, own)
        }
        val trailing = trailingPath?.let(::loadBitmap)?.let(own)?.let {
            alignHeight(it, scroll.height, owned, own)
        }
        val scrollWidth = selectedEnd - selectedStart
        val leadingWidth = leading?.width ?: 0
        val outputWidth = leadingWidth + scrollWidth + (trailing?.width ?: 0)
        val outputHeight = scroll.height
        validateSize(outputWidth, outputHeight, options, ImageOutputFormat.PNG)

        val row = IntArray(outputWidth)
        val uri = writePngToMediaStore(
            displayName = displayName,
            width = outputWidth,
            height = outputHeight,
            options = options,
            onProgress = onProgress,
            isCancelled = isCancelled,
        ) { y ->
            leading?.getPixels(row, 0, outputWidth, 0, y, leading.width, 1)
            scroll.getPixels(
                row,
                leadingWidth,
                outputWidth,
                selectedStart,
                y,
                scrollWidth,
                1,
            )
            trailing?.getPixels(
                row,
                leadingWidth + scrollWidth,
                outputWidth,
                0,
                y,
                trailing.width,
                1,
            )
            row
        }
        return ExportResult(
            uris = listOf(uri),
            format = ImageOutputFormat.PNG,
            width = outputWidth,
            totalHeight = outputHeight.toLong(),
            displayNames = listOf(displayName),
        )
    }

    private fun alignWidth(
        source: Bitmap,
        width: Int,
        owned: MutableSet<Bitmap>,
        own: (Bitmap) -> Bitmap,
    ): Bitmap {
        if (source.width == width) return source
        val height = (source.height.toDouble() * width / source.width).roundToInt().coerceAtLeast(1)
        return own(Bitmap.createScaledBitmap(source, width, height, true)).also {
            owned.remove(source)
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun alignHeight(
        source: Bitmap,
        height: Int,
        owned: MutableSet<Bitmap>,
        own: (Bitmap) -> Bitmap,
    ): Bitmap {
        if (source.height == height) return source
        val width = (source.width.toDouble() * height / source.height).roundToInt().coerceAtLeast(1)
        return own(Bitmap.createScaledBitmap(source, width, height, true)).also {
            owned.remove(source)
            if (!source.isRecycled) source.recycle()
        }
    }

    private fun writePngToMediaStore(
        displayName: String,
        width: Int,
        height: Int,
        options: ExportOptions,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
        rowProvider: (Int) -> IntArray,
    ): Uri {
        throwIfCancelled(isCancelled)
        val uri = createPendingImage(displayName, options, ImageOutputFormat.PNG)
        try {
            resolver.openOutputStream(uri, "w")?.use { raw ->
                BufferedOutputStream(raw, IO_BUFFER_SIZE).use { output ->
                    var lastProgress = 5
                    StreamingPngEncoder.write(
                        output = output,
                        width = width,
                        height = height,
                        isCancelled = isCancelled,
                        cancellationMessage = text(R.string.export_png_cancelled),
                    ) { y ->
                        val progress = 5 + ((y + 1L) * 90L / height).toInt()
                        if (progress >= lastProgress + 2 || y == height - 1) {
                            lastProgress = progress
                            onProgress?.invoke(progress, 100)
                        }
                        rowProvider(y)
                    }
                }
            } ?: throw ExportException(text(R.string.export_error_open_output_stream))
            throwIfCancelled(isCancelled)
            publishPendingImage(uri, options)
            onProgress?.invoke(100, 100)
            return uri
        } catch (error: Throwable) {
            deleteOutput(uri, options)
            throw error
        }
    }

    private fun writeJpegToMediaStore(
        displayName: String,
        bitmap: Bitmap,
        quality: Int,
        options: ExportOptions,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
    ): Uri {
        throwIfCancelled(isCancelled)
        val temporary = File.createTempFile("irisshot_export_", ".jpg", appContext.cacheDir)
        try {
            FileOutputStream(temporary).use { raw ->
                val output = BufferedOutputStream(raw, IO_BUFFER_SIZE)
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    text(R.string.export_error_jpeg_encoding)
                }
                output.flush()
                raw.fd.sync()
            }
            if (temporary.length() <= JPEG_SIGNATURE.size || !isJpeg(temporary.absolutePath)) {
                throw ExportException(text(R.string.export_error_jpeg_result_invalid))
            }
            val encodedInfo = inspect(temporary.absolutePath)
            if (encodedInfo?.width != bitmap.width || encodedInfo.height != bitmap.height) {
                throw ExportException(text(R.string.export_error_jpeg_dimension_validation))
            }
            onProgress?.invoke(80, 100)
            throwIfCancelled(isCancelled)
            return copyImageToMediaStore(
                source = temporary.absolutePath,
                displayName = displayName,
                options = options,
                format = ImageOutputFormat.JPEG,
                onProgress = onProgress,
                isCancelled = isCancelled,
                progressStart = 80,
                progressEnd = 98,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun copyImageToMediaStore(
        source: String,
        displayName: String,
        options: ExportOptions,
        format: ImageOutputFormat,
        onProgress: ((Int, Int) -> Unit)?,
        isCancelled: () -> Boolean,
        progressStart: Int = 0,
        progressEnd: Int = 95,
    ): Uri {
        throwIfCancelled(isCancelled)
        val uri = createPendingImage(displayName, options, format)
        try {
            resolver.openOutputStream(uri, "w")?.use { raw ->
                BufferedOutputStream(raw, IO_BUFFER_SIZE).use { output ->
                    val sourceInput = resourceAccess.openInputStream(source)
                        ?: throw ExportException(
                            text(R.string.export_error_read_source_image, source),
                        )
                    sourceInput.use { input ->
                        val buffer = ByteArray(IO_BUFFER_SIZE)
                        val total = resourceAccess.length(source)?.coerceAtLeast(1L) ?: 1L
                        var copied = 0L
                        var lastProgress = 0
                        while (true) {
                            throwIfCancelled(isCancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val progress = progressStart +
                                (copied * (progressEnd - progressStart).toLong() / total).toInt()
                            if (progress >= lastProgress + 5) {
                                lastProgress = progress
                                onProgress?.invoke(progress, 100)
                            }
                        }
                    }
                }
            } ?: throw ExportException(text(R.string.export_error_open_output_stream))
            throwIfCancelled(isCancelled)
            publishPendingImage(uri, options)
            onProgress?.invoke(100, 100)
            return uri
        } catch (error: Throwable) {
            deleteOutput(uri, options)
            throw error
        }
    }

    private fun createPendingImage(
        displayName: String,
        options: ExportOptions,
        format: ImageOutputFormat,
    ): Uri {
        val treeUri = options.destinationTreeUri
        if (treeUri != null) {
            if (!DocumentsContract.isTreeUri(treeUri)) {
                throw ExportException(text(R.string.export_error_invalid_destination))
            }
            val parent = runCatching {
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri),
                )
            }.getOrElse { error ->
                throw ExportException(text(R.string.export_error_invalid_destination), error)
            }
            return runCatching {
                DocumentsContract.createDocument(
                    resolver,
                    parent,
                    format.mimeType,
                    displayName,
                )
            }.getOrElse { error ->
                throw ExportException(text(R.string.export_error_create_destination_image), error)
            } ?: throw ExportException(text(R.string.export_error_create_destination_image))
        }
        val path = options.relativePath.trim('/').ifBlank {
            "${Environment.DIRECTORY_PICTURES}/GameLongShot"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ExportException(
                text(R.string.export_error_mediastore_create, format.displayName),
            )
    }

    private fun publishPendingImage(uri: Uri, options: ExportOptions) {
        if (options.destinationTreeUri != null) return
        val updated = resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null)
        if (updated != 1) {
            throw ExportException(text(R.string.export_error_publish_gallery))
        }
    }

    private fun deleteOutput(uri: Uri, options: ExportOptions) {
        runCatching {
            if (options.destinationTreeUri == null) {
                resolver.delete(uri, null, null)
            } else {
                DocumentsContract.deleteDocument(resolver, uri)
            }
        }
    }

    private fun validateSize(
        width: Int,
        height: Int,
        @Suppress("UNUSED_PARAMETER") options: ExportOptions,
        format: ImageOutputFormat,
    ) {
        if (width <= 0 || height <= 0) {
            throw ExportException(
                text(R.string.export_error_output_size_invalid, format.displayName),
            )
        }
    }

    private fun validateJpegWorkingSet(
        outputWidth: Int,
        outputHeight: Int,
        auxiliaryPixels: Long,
        pixelLimit: Long,
    ) {
        if (outputWidth > DefaultCaptureConfig.MAX_OUTPUT_DIMENSION ||
            outputHeight > DefaultCaptureConfig.MAX_OUTPUT_DIMENSION
        ) {
            throw ExportException(
                text(
                    R.string.export_error_jpeg_dimension_crop,
                    DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
                ),
            )
        }
        val outputPixels = outputWidth.toLong() * outputHeight
        val workingPixels = auxiliaryPixels + outputPixels
        if (workingPixels > pixelLimit) {
            throw ExportException(
                text(R.string.export_error_jpeg_working_set, workingPixels, pixelLimit),
            )
        }
    }

    private fun safeIntDimension(value: Long, @StringRes labelRes: Int): Int {
        if (value !in 1..Int.MAX_VALUE.toLong()) {
            throw ExportException(
                text(
                    R.string.export_error_output_dimension_invalid,
                    text(labelRes),
                    value,
                ),
            )
        }
        return value.toInt()
    }

    private fun throwIfCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException(text(R.string.export_cancelled))
    }

    private fun loadBitmap(path: String): Bitmap = resourceAccess.decodeBitmap(
        path,
        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
    ) ?: throw ExportException(text(R.string.export_error_read_layer, path))

    private fun inspect(path: String): ImageInfo? {
        if (path.isBlank() || !resourceAccess.exists(path)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resourceAccess.decodeBitmap(path, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            ImageInfo(bounds.outWidth, bounds.outHeight)
        } else null
    }

    private fun isPng(resource: String): Boolean {
        val length = resourceAccess.length(resource) ?: return false
        if (!resourceAccess.exists(resource) || length < PNG_SIGNATURE.size) {
            return false
        }
        return runCatching {
            resourceAccess.openInputStream(resource)?.use { input ->
                val signature = ByteArray(PNG_SIGNATURE.size)
                input.read(signature) == signature.size && signature.contentEquals(PNG_SIGNATURE)
            } ?: false
        }.getOrDefault(false)
    }

    private fun isJpeg(resource: String): Boolean {
        val length = resourceAccess.length(resource) ?: return false
        if (!resourceAccess.exists(resource) || length < JPEG_SIGNATURE.size) {
            return false
        }
        return runCatching {
            resourceAccess.openInputStream(resource)?.use { input ->
                val signature = ByteArray(JPEG_SIGNATURE.size)
                input.read(signature) == signature.size && signature.contentEquals(JPEG_SIGNATURE)
            } ?: false
        }.getOrDefault(false)
    }

    private fun safeBaseName(name: String): String = name.substringBeforeLast('.', name)
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .take(100)
        .ifBlank { "GameLongShot_${System.currentTimeMillis()}" }

    private data class ImageInfo(val width: Int, val height: Int) {
        val pixelCount: Long get() = width.toLong() * height

        fun scaledHeightForWidth(targetWidth: Int): Int =
            ((height.toDouble() * targetWidth / width).roundToInt()).coerceAtLeast(1)

        fun scaledWidthForHeight(targetHeight: Int): Int =
            ((width.toDouble() * targetHeight / height).roundToInt()).coerceAtLeast(1)
    }

    private data class ChunkSelection(
        val chunk: ScrollChunk,
        val localStart: Int,
        val localEnd: Int,
        val outputStart: Int,
    )

    private data class CompositionSpan(
        val path: String,
        val source: Rect,
        val destination: Rect,
    )

    private data class CompositionPlan(
        val width: Int,
        val height: Int,
        val spans: List<CompositionSpan>,
    )

    @Suppress("DEPRECATION")
    private inner class CompositionRowReader(
        private val resourceAccess: StorageResourceAccess,
        private val plan: CompositionPlan,
        private val isCancelled: () -> Boolean,
        private val maxScaledCachePixels: Long = MAX_CACHED_SCALED_SPAN_PIXELS,
    ) : Closeable {
        private val decoderCacheLimit = plan.spans.asSequence()
            .map { it.path }
            .distinct()
            .take(MAX_COMPOSITION_DECODERS)
            .count()
            .coerceAtLeast(1)
        private val decoders = object : LinkedHashMap<String, ManagedRegionDecoder>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ManagedRegionDecoder>,
            ): Boolean {
                val remove = size > decoderCacheLimit
                if (remove) eldest.value.close()
                return remove
            }
        }
        private val scaledSpans = LinkedHashMap<CompositionSpan, Bitmap>(4, 0.75f, true)
        private var scaledSpanPixels = 0L

        fun readRows(firstY: Int, rowCount: Int): IntArray {
            require(firstY >= 0 && rowCount > 0 && firstY + rowCount <= plan.height)
            val output = IntArray(Math.multiplyExact(plan.width, rowCount))
            Arrays.fill(output, Color.WHITE)
            val band = Rect(0, firstY, plan.width, firstY + rowCount)
            plan.spans.forEach { span ->
                if (isCancelled()) throw CancellationException(text(R.string.export_cancelled))
                val target = Rect(span.destination)
                if (!target.intersect(band)) return@forEach
                scaledSpan(span)?.let { scaled ->
                    scaled.getPixels(
                        output,
                        (target.top - firstY) * plan.width + target.left,
                        plan.width,
                        target.left - span.destination.left,
                        target.top - span.destination.top,
                        target.width(),
                        target.height(),
                    )
                    return@forEach
                }
                val source = mappedSource(span, target)
                if (source.isEmpty || target.isEmpty) return@forEach
                val decoded = decoderFor(span.path).decodeRegion(
                    source,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                    },
                ) ?: throw ExportException(
                    text(R.string.export_error_read_layer_region, span.path),
                )
                val targetWidth = target.width()
                val targetHeight = target.height()
                val rendered = if (decoded.width == targetWidth && decoded.height == targetHeight) {
                    decoded
                } else {
                    Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                        if (it !== decoded && !decoded.isRecycled) decoded.recycle()
                    }
                }
                try {
                    rendered.getPixels(
                        output,
                        (target.top - firstY) * plan.width + target.left,
                        plan.width,
                        0,
                        0,
                        targetWidth,
                        targetHeight,
                    )
                } finally {
                    if (!rendered.isRecycled) rendered.recycle()
                }
            }
            return output
        }

        private fun scaledSpan(span: CompositionSpan): Bitmap? {
            if (span.source.width() == span.destination.width() &&
                span.source.height() == span.destination.height()
            ) {
                return null
            }
            val targetPixels = span.destination.width().toLong() * span.destination.height()
            if (targetPixels > maxScaledCachePixels) return null
            scaledSpans[span]?.let { return it }
            val decoded = decoderFor(span.path).decodeRegion(
                span.source,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            ) ?: throw ExportException(
                text(R.string.export_error_read_scaled_layer, span.path),
            )
            val scaled = if (decoded.width == span.destination.width() &&
                decoded.height == span.destination.height()
            ) {
                decoded
            } else {
                Bitmap.createScaledBitmap(
                    decoded,
                    span.destination.width(),
                    span.destination.height(),
                    true,
                ).also {
                    if (it !== decoded && !decoded.isRecycled) decoded.recycle()
                }
            }
            scaledSpans[span] = scaled
            scaledSpanPixels += targetPixels
            trimScaledSpanCache()
            return scaled
        }

        private fun trimScaledSpanCache() {
            val iterator = scaledSpans.entries.iterator()
            while (scaledSpanPixels > maxScaledCachePixels && iterator.hasNext()) {
                val entry = iterator.next()
                iterator.remove()
                val bitmap = entry.value
                scaledSpanPixels -= bitmap.width.toLong() * bitmap.height
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        private fun decoderFor(path: String): ManagedRegionDecoder {
            decoders[path]?.let { return it }
            return (resourceAccess.openRegionDecoder(path)
                ?: throw ExportException(
                    text(R.string.export_error_open_layer_decoder, path),
                )).also { decoders[path] = it }
        }

        private fun mappedSource(span: CompositionSpan, target: Rect): Rect {
            val destination = span.destination
            val source = span.source
            return Rect(
                mapFloor(target.left, destination.left, destination.width(), source.left, source.width()),
                mapFloor(target.top, destination.top, destination.height(), source.top, source.height()),
                mapCeil(target.right, destination.left, destination.width(), source.left, source.width()),
                mapCeil(target.bottom, destination.top, destination.height(), source.top, source.height()),
            )
        }

        private fun mapFloor(
            value: Int,
            destinationStart: Int,
            destinationLength: Int,
            sourceStart: Int,
            sourceLength: Int,
        ): Int = sourceStart + (
            (value - destinationStart).toLong() * sourceLength / destinationLength
            ).toInt()

        private fun mapCeil(
            value: Int,
            destinationStart: Int,
            destinationLength: Int,
            sourceStart: Int,
            sourceLength: Int,
        ): Int {
            val numerator = (value - destinationStart).toLong() * sourceLength
            return sourceStart + ((numerator + destinationLength - 1L) / destinationLength).toInt()
        }

        override fun close() {
            scaledSpans.values.forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            scaledSpans.clear()
            scaledSpanPixels = 0L
            decoders.values.forEach { decoder ->
                decoder.close()
            }
            decoders.clear()
        }
    }

    private fun resolveFixedLayerMetadata(
        layer: CapturedFixedLayer,
        horizontalScroll: Boolean,
    ): FixedLayerMetadataRepairResult {
        if (layer.repeatRegion == null ||
            !fixedBandRunsAlongScrollAxis(layer.side, horizontalScroll)
        ) {
            return FixedLayerMetadataRepairResult(layer, repairSucceeded = true)
        }
        val info = runCatching { inspect(layer.bitmapPath) }.getOrNull()
            ?: return FixedLayerMetadataRepairResult(layer, repairSucceeded = false)
        val sampleSize = runCatching {
            var candidate = 1
            while (ceilDivide(info.width, candidate).toLong() *
                ceilDivide(info.height, candidate) > FIXED_BAND_ANALYSIS_MAX_PIXELS
            ) {
                candidate = Math.multiplyExact(candidate, 2)
            }
            candidate
        }.getOrNull()
            ?: return FixedLayerMetadataRepairResult(layer, repairSucceeded = false)
        val bitmap = runCatching {
            resourceAccess.decodeBitmap(
                layer.bitmapPath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        }.getOrNull()
            ?: return FixedLayerMetadataRepairResult(layer, repairSucceeded = false)
        return try {
            runCatching {
                resolveTileSafeFixedLayer(
                    layer = layer,
                    bitmap = bitmap,
                    logicalWidth = info.width,
                    logicalHeight = info.height,
                    horizontalScroll = horizontalScroll,
                )
            }.fold(
                onSuccess = { repaired ->
                    FixedLayerMetadataRepairResult(repaired, repairSucceeded = true)
                },
                onFailure = {
                    FixedLayerMetadataRepairResult(layer, repairSucceeded = false)
                },
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun ceilDivide(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private inner class CompositionRowProvider(
        resourceAccess: StorageResourceAccess,
        private val plan: CompositionPlan,
        isCancelled: () -> Boolean,
    ) : Closeable {
        private val reader = CompositionRowReader(resourceAccess, plan, isCancelled)
        private val row = IntArray(plan.width)
        private val bandRows = (COMPOSITE_BAND_MAX_PIXELS / plan.width.coerceAtLeast(1).toLong())
            .coerceIn(1L, 1_024L)
            .toInt()
        private var cachedStart = -1
        private var cachedRowCount = 0
        private var cachedPixels = IntArray(0)

        fun row(y: Int): IntArray {
            if (y < cachedStart || y >= cachedStart + cachedRowCount) {
                cachedStart = y
                cachedRowCount = minOf(bandRows, plan.height - y)
                cachedPixels = reader.readRows(cachedStart, cachedRowCount)
            }
            System.arraycopy(
                cachedPixels,
                (y - cachedStart) * plan.width,
                row,
                0,
                plan.width,
            )
            return row
        }

        override fun close() {
            cachedPixels = IntArray(0)
            reader.close()
        }
    }

    companion object {
        private const val IO_BUFFER_SIZE = 128 * 1024
        private const val JPEG_MAX_DIMENSION = 65_535
        private const val COMPOSITE_BAND_MAX_PIXELS = 4_000_000L
        private const val FIXED_BAND_ANALYSIS_MAX_PIXELS = 2_000_000L
        private const val MAX_CACHED_SCALED_SPAN_PIXELS = 4_000_000L
        private const val MAX_COMPOSITION_DECODERS = 256
        private const val COMPOSITION_BYTES_PER_PIXEL_ESTIMATE = 16L
        private val FILTER_BITMAP_PAINT = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }
}

/** Minimal scanline PNG encoder (RGBA8 + Sub filter), optimized for speed and bounded memory. */
internal object StreamingPngEncoder {
    fun write(
        output: OutputStream,
        width: Int,
        height: Int,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        cancellationMessage: String = "PNG saving was cancelled",
        rowProvider: (Int) -> IntArray,
    ) {
        require(width > 0 && height > 0)
        val data = DataOutputStream(output)
        data.write(PNG_SIGNATURE)
        val header = ByteArrayOutputStream(13).also { bytes ->
            DataOutputStream(bytes).use { headerData ->
                headerData.writeInt(width)
                headerData.writeInt(height)
                headerData.writeByte(8)
                headerData.writeByte(6)
                headerData.writeByte(0)
                headerData.writeByte(0)
                headerData.writeByte(0)
            }
        }.toByteArray()
        writeChunk(data, "IHDR", header, 0, header.size)

        val scanline = ByteArray(1 + width * 4)
        val idat = IdatChunkOutputStream(data)
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            DeflaterOutputStream(idat, deflater, IDAT_BUFFER_SIZE).use { compressed ->
                for (y in 0 until height) {
                    if (isCancelled()) throw CancellationException(cancellationMessage)
                    val pixels = rowProvider(y)
                    require(pixels.size >= width) { "PNG 行像素不足" }
                    scanline[0] = 1 // Sub filter: improves size without an expensive adaptive pass.
                    var offset = 1
                    var previousR = 0
                    var previousG = 0
                    var previousB = 0
                    var previousA = 0
                    for (x in 0 until width) {
                        val color = pixels[x]
                        val a = color ushr 24 and 0xFF
                        val r = color ushr 16 and 0xFF
                        val g = color ushr 8 and 0xFF
                        val b = color and 0xFF
                        scanline[offset++] = (r - previousR).toByte()
                        scanline[offset++] = (g - previousG).toByte()
                        scanline[offset++] = (b - previousB).toByte()
                        scanline[offset++] = (a - previousA).toByte()
                        previousR = r
                        previousG = g
                        previousB = b
                        previousA = a
                    }
                    compressed.write(scanline)
                }
            }
        } finally {
            deflater.end()
        }
        writeChunk(data, "IEND", EMPTY_BYTES, 0, 0)
        data.flush()
    }

    private class IdatChunkOutputStream(
        private val target: DataOutputStream,
    ) : OutputStream() {
        private val buffer = ByteArray(IDAT_CHUNK_SIZE)
        private var count = 0

        override fun write(value: Int) {
            if (count == buffer.size) flushChunk()
            buffer[count++] = value.toByte()
        }

        override fun write(source: ByteArray, offset: Int, length: Int) {
            var sourceOffset = offset
            var remaining = length
            while (remaining > 0) {
                if (count == buffer.size) flushChunk()
                val copied = minOf(remaining, buffer.size - count)
                source.copyInto(buffer, count, sourceOffset, sourceOffset + copied)
                count += copied
                sourceOffset += copied
                remaining -= copied
            }
        }

        override fun flush() {
            flushChunk()
        }

        override fun close() {
            flushChunk()
        }

        private fun flushChunk() {
            if (count == 0) return
            writeChunk(target, "IDAT", buffer, 0, count)
            count = 0
        }
    }

    private fun writeChunk(
        output: DataOutputStream,
        type: String,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val typeBytes = type.toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(bytes, offset, length)
        }
        output.writeInt(length)
        output.write(typeBytes)
        if (length > 0) output.write(bytes, offset, length)
        output.writeInt(crc.value.toInt())
    }

    private const val IDAT_CHUNK_SIZE = 64 * 1024
    private const val IDAT_BUFFER_SIZE = 64 * 1024
    private val EMPTY_BYTES = ByteArray(0)
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
