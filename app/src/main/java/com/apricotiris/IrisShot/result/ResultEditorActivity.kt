package com.apricotiris.IrisShot.result

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.lifecycleScope
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.capture.GameLongShotCaptureService
import com.apricotiris.IrisShot.core.export.ExportOptions
import com.apricotiris.IrisShot.core.export.ExportResult
import com.apricotiris.IrisShot.core.export.defaultCaptureExportBaseName
import com.apricotiris.IrisShot.core.export.FixedBandMetadataRepairResult
import com.apricotiris.IrisShot.core.export.LayeredImageExporter
import com.apricotiris.IrisShot.core.model.FixedAnchorPosition
import com.apricotiris.IrisShot.core.model.CapturedFixedLayer
import com.apricotiris.IrisShot.core.model.DefaultCaptureConfig
import com.apricotiris.IrisShot.core.model.FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.ImageOutputFormat
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.model.ResultExportRecord
import com.apricotiris.IrisShot.core.model.ScrollExportRegion
import com.apricotiris.IrisShot.core.storage.CaptureResultRepository
import com.apricotiris.IrisShot.core.storage.SessionFileStore
import com.apricotiris.IrisShot.core.storage.StorageLocationPreferences
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess
import com.apricotiris.IrisShot.core.storage.sourceFrameStorageResource
import com.apricotiris.IrisShot.ui.CaptureUiContract
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.LocalizedComponentActivity
import com.apricotiris.IrisShot.ui.localizedErrorOrFallback
import com.apricotiris.IrisShot.ui.theme.GameLongShotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.roundToInt

internal const val EDITOR_OVERVIEW_MAX_DIMENSION = 12_000
// About 46 MiB at the cap; source chunks are decoded and recycled one at a time.
internal const val EDITOR_OVERVIEW_MAX_PIXELS = 12_000_000L

internal fun shouldPersistFixedBandMetadataRepair(
    originalResult: LayeredCaptureResult,
    repair: FixedBandMetadataRepairResult,
): Boolean = repair.repairSucceeded && (
    repair.result.schemaVersion != originalResult.schemaVersion ||
        repair.result.fixedLayers != originalResult.fixedLayers
    )

class ResultEditorActivity : LocalizedComponentActivity() {
    private lateinit var repository: CaptureResultRepository
    private lateinit var exporter: LayeredImageExporter
    private lateinit var resourceAccess: StorageResourceAccess
    private val storageLocationPreferences by lazy { StorageLocationPreferences(this) }
    private var currentResult: LayeredCaptureResult? = null
    private var uiState by mutableStateOf(ResultEditorUiState())
    private var exportLocation by mutableStateOf(StorageLocationPreferences.DEFAULT_EXPORT_DISPLAY_NAME)
    private var exportDestinationOverride: Uri? = null
    private var restoredOutputFormat: ImageOutputFormat? = null
    private var restoredJpegQuality: Int? = null
    private lateinit var exportDirectoryLauncher: ActivityResultLauncher<Uri?>
    private var latestExport: ExportResult? = null
    private val ownedPreviewBitmaps = Collections.synchronizedList(mutableListOf<Bitmap>())
    private val framePreviewCache = LinkedHashMap<Int, Bitmap>(16, 0.75f, true)
    private val framePreviewJobs = mutableMapOf<Int, Job>()
    private val framePreviewSemaphore = Semaphore(FRAME_PREVIEW_CONCURRENCY)
    private val scrollViewportPreviewSemaphore = Semaphore(1)
    private var scrollViewportPreviewJob: Job? = null
    private var requestedScrollViewportPreview: ScrollViewportPreviewRequest? = null
    private var cropSaveJob: Job? = null
    private var exportJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        exportDestinationOverride = savedInstanceState
            ?.getString(STATE_EXPORT_DESTINATION_URI)
            ?.let(Uri::parse)
        exportLocation = savedInstanceState?.getString(STATE_EXPORT_DESTINATION_NAME)
            ?: storageLocationPreferences.exportDisplayName
        restoredOutputFormat = savedInstanceState
            ?.getString(STATE_OUTPUT_FORMAT)
            ?.let { value -> runCatching { ImageOutputFormat.valueOf(value) }.getOrNull() }
        restoredJpegQuality = savedInstanceState
            ?.takeIf { it.containsKey(STATE_JPEG_QUALITY) }
            ?.getInt(STATE_JPEG_QUALITY)
        exportDirectoryLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            uri?.let(::selectExportDirectory)
        }
        val fileStore = SessionFileStore(this)
        repository = CaptureResultRepository(this, fileStore)
        exporter = LayeredImageExporter(this)
        resourceAccess = fileStore.resourceAccess
        val themeSettings = AppPreferences(this).loadSettings()
        setContent {
            GameLongShotTheme(
                themeMode = themeSettings.themeMode,
                dynamicColor = themeSettings.dynamicColor,
            ) {
                val configuration = LocalConfiguration.current
                ResultEditorScreen(
                    state = uiState,
                    compact = configuration.screenWidthDp < 840,
                    onBack = ::finish,
                    onRangeChange = ::updateRange,
                    onRangeFinished = ::persistCropSelection,
                    onScrollExportRegionsChange = ::updateScrollExportRegions,
                    onScrollViewportPreviewRequest = ::loadScrollViewportPreview,
                    onIncludeTop = ::updateIncludeTop,
                    onIncludeBottom = ::updateIncludeBottom,
                    onOutputFormatChange = ::updateOutputFormat,
                    onJpegQualityChange = ::updateJpegQuality,
                    onRequestFramePreview = ::loadFramePreview,
                    onReleaseFramePreview = ::cancelFramePreviewLoad,
                    onExport = ::exportResult,
                    onViewExport = ::openLatestExport,
                    onShare = ::shareLatestExport,
                    onRetry = ::loadIncomingResult,
                    onCancelBusy = ::cancelExport,
                    onStatusConsumed = { uiState = uiState.copy(statusMessage = null) },
                    exportLocation = exportLocation,
                    onExportLocationClick = {
                        Toast.makeText(
                            this,
                            getString(R.string.result_editor_export_location_hint),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onExportLocationDoubleClick = ::openExportDirectoryPicker,
                    onExportLocationLongClick = ::copyExportLocation,
                    onExpectedSizeLongClick = ::copyExpectedSize,
                )
            }
        }
        acknowledgeCaptureResultOpened()
        loadIncomingResult()
    }

    private fun acknowledgeCaptureResultOpened() {
        val sessionId = intent.getStringExtra(CaptureUiContract.EXTRA_SESSION_ID)
            ?: intent.getStringExtra(EXTRA_RESULT_ID)
            ?: return
        sendBroadcast(
            Intent(GameLongShotCaptureService.ACTION_RESULT_OPENED)
                .setPackage(packageName)
                .putExtra(GameLongShotCaptureService.EXTRA_SESSION_ID, sessionId),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        exportDestinationOverride?.let { uri ->
            outState.putString(STATE_EXPORT_DESTINATION_URI, uri.toString())
            outState.putString(STATE_EXPORT_DESTINATION_NAME, exportLocation)
        }
        if (currentResult != null) {
            outState.putString(STATE_OUTPUT_FORMAT, uiState.outputFormat.name)
            outState.putInt(STATE_JPEG_QUALITY, uiState.jpegQuality)
        }
    }

    override fun onDestroy() {
        cropSaveJob?.cancel()
        exportJob?.cancel()
        clearOwnedPreviews()
        super.onDestroy()
    }

    private fun loadIncomingResult() {
        uiState = uiState.copy(loading = true, error = null, busyMessage = null)
        lifecycleScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val requestedId = intent.getStringExtra(CaptureUiContract.EXTRA_SESSION_ID)
                        ?: intent.getStringExtra(EXTRA_RESULT_ID)
                    val result = when {
                        !requestedId.isNullOrBlank() -> repository.getById(requestedId)
                            ?: repository.getAll().firstOrNull { it.sessionId == requestedId }
                        else -> repository.getAll().firstOrNull()
                    } ?: error(
                        if (requestedId.isNullOrBlank()) {
                            getString(R.string.result_editor_no_editable_result)
                        } else {
                            getString(R.string.result_editor_result_not_found, requestedId)
                        },
                    )
                    loadEditorData(result)
                }
            }
            loaded.onSuccess(::applyLoadedData).onFailure { error ->
                uiState = uiState.copy(
                    loading = false,
                    error = localizedErrorOrFallback(
                        error,
                        R.string.result_editor_load_failed,
                    ),
                    busyMessage = null,
                )
            }
        }
    }

    private fun loadEditorData(originalResult: LayeredCaptureResult): LoadedEditorData {
        val fixedBandRepair = exporter.repairFixedBandMetadata(originalResult)
        val result = fixedBandRepair.result
        if (shouldPersistFixedBandMetadataRepair(originalResult, fixedBandRepair)) {
            // Keep preview, later crop saves, quick sharing and export signatures on one geometry.
            runCatching { repository.fileStore.saveResultMetadata(result) }
        }
        val horizontal = result.templateSnapshot?.scrollDirection?.isVertical == false
        val supportsAxisFixedLayers = !horizontal || result.schemaVersion >= 2
        val configuredFixedLayers = result.fixedLayers.take(2)
        val firstFixedLayer = configuredFixedLayers.getOrNull(0)
        val secondFixedLayer = configuredFixedLayers.getOrNull(1)
        val firstFixedPath = firstFixedLayer?.bitmapPath
            ?: result.topFixedBitmapPath.takeIf { supportsAxisFixedLayers }
        val secondFixedPath = secondFixedLayer?.bitmapPath
            ?: result.bottomFixedBitmapPath.takeIf { supportsAxisFixedLayers }
        val firstFixedSide = firstFixedLayer?.side ?: if (horizontal) {
            FixedSide.LEFT
        } else {
            FixedSide.TOP
        }
        val secondFixedSide = secondFixedLayer?.side ?: if (horizontal) {
            FixedSide.RIGHT
        } else {
            FixedSide.BOTTOM
        }
        val top = BitmapPreviewLoader.load(
            resourceAccess,
            firstFixedPath,
            maxWidth = 1_600,
            maxHeight = 480,
        )
        val scroll = ChunkFramePreviewLoader.loadOverview(
            resourceAccess = resourceAccess,
            result = result,
            maxWidth = EDITOR_OVERVIEW_MAX_DIMENSION,
            maxHeight = EDITOR_OVERVIEW_MAX_DIMENSION,
            maxPixels = EDITOR_OVERVIEW_MAX_PIXELS,
        ) ?: BitmapPreviewLoader.load(
            resourceAccess = resourceAccess,
            path = result.scrollLongBitmapPath,
            maxWidth = 1_600,
            maxHeight = 2_400,
            contentWidth = result.scrollContentWidth,
            contentHeight = result.scrollContentHeight,
        )
        val bottom = BitmapPreviewLoader.load(
            resourceAccess,
            secondFixedPath,
            maxWidth = 1_600,
            maxHeight = 480,
        )
        val sortedSourceFrames = result.sourceFrames.sortedBy { it.index }
        val frameResourceLengths = repository.fileStore.sourceFrameResourceLengths(result)
        val frames = sortedSourceFrames.mapIndexed { position, frame ->
            SourceFrameUi(
                index = frame.index,
                bitmap = null,
                width = frame.width,
                height = frame.height,
                sizeBytes = sourceFrameStorageResource(
                    result = result,
                    sortedFrames = sortedSourceFrames,
                    position = position,
                )?.let(frameResourceLengths::get)?.coerceAtLeast(0L) ?: 0L,
            )
        }
        require(scroll.bitmap != null && scroll.width > 0 && scroll.height > 0) {
            getString(R.string.result_editor_scroll_preview_invalid)
        }
        val axisLength = if (horizontal) scroll.width else scroll.height
        val selectedStart = result.selectedScrollStart.coerceIn(0, axisLength - 1)
        val selectedEnd = result.selectedScrollEnd
            .takeIf { it > selectedStart }
            ?.coerceIn(selectedStart + 1, axisLength)
            ?: axisLength
        val exportRecord = result.lastExport?.takeIf { record ->
            (!record.includeTop || top.bitmap != null) &&
                (!record.includeBottom || bottom.bitmap != null) &&
                record.matches(
                    result = result,
                    selectedStart = selectedStart,
                    selectedEnd = selectedEnd,
                    includeTop = record.includeTop,
                    includeBottom = record.includeBottom,
                ) && record.hasReadableUris(contentResolver)
        }
        return LoadedEditorData(
            result,
            top,
            scroll,
            bottom,
            firstFixedSide,
            secondFixedSide,
            firstFixedLayer,
            secondFixedLayer,
            frames,
            exportRecord,
        )
    }

    private fun applyLoadedData(data: LoadedEditorData) {
        clearOwnedPreviews()
        val selectedFormat = restoredOutputFormat ?: data.result.outputFormat
        val selectedJpegQuality = ImageOutputFormat.normalizeJpegQuality(
            restoredJpegQuality ?: data.result.jpegQuality,
        )
        restoredOutputFormat = null
        restoredJpegQuality = null
        val horizontal = data.result.templateSnapshot?.scrollDirection?.isVertical == false
        val axisLength = if (horizontal) data.scroll.width else data.scroll.height
        val includeTop = data.exportRecord?.includeTop ?: (data.top.bitmap != null)
        val includeBottom = data.exportRecord?.includeBottom ?: (data.bottom.bitmap != null)
        val minimumSegmentLength = minimumScrollExportRegionLength(
            horizontal = horizontal,
            fixedLayers = data.result.fixedLayers,
            includeFirstFixed = includeTop,
            includeSecondFixed = includeBottom,
            schemaVersion = data.result.schemaVersion,
        )
        val maximumSegmentLength = editorMaximumScrollSegmentLength(
            horizontal = horizontal,
            scrollWidth = data.scroll.width,
            scrollHeight = data.scroll.height,
            firstFixedSide = data.firstFixedSide.takeIf { data.top.bitmap != null },
            firstFixedWidth = data.top.width,
            firstFixedHeight = data.top.height,
            includeFirstFixed = includeTop,
            secondFixedSide = data.secondFixedSide.takeIf { data.bottom.bitmap != null },
            secondFixedWidth = data.bottom.width,
            secondFixedHeight = data.bottom.height,
            includeSecondFixed = includeBottom,
            outputFormat = selectedFormat,
            outputAxisLimit = DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
        )
        val normalizedRegions = normalizeScrollExportRegions(
            axisLength = axisLength,
            selectedStart = data.result.selectedScrollStart,
            selectedEnd = data.result.selectedScrollEnd,
            storedRegions = data.result.scrollExportRegions,
            maximumSegmentLength = maximumSegmentLength,
            minimumSegmentLength = minimumSegmentLength,
        )
        val effectiveResult = data.result.copy(
            outputFormat = selectedFormat,
            jpegQuality = selectedJpegQuality,
            selectedScrollStart = normalizedRegions.firstOrNull()?.start ?: 0,
            selectedScrollEnd = normalizedRegions.lastOrNull()?.end ?: axisLength,
            scrollExportRegions = normalizedRegions,
        )
        currentResult = effectiveResult
        latestExport = data.exportRecord?.toExportResult()
        listOfNotNull(data.top.bitmap, data.scroll.bitmap, data.bottom.bitmap).forEach(::ownPreview)
        val start = effectiveResult.selectedScrollStart.coerceIn(0, axisLength - 1)
        val requestedEnd = effectiveResult.selectedScrollEnd
        val end = if (requestedEnd <= start) axisLength else requestedEnd.coerceIn(start + 1, axisLength)
        val startFraction = start.toFloat() / axisLength
        val endFraction = end.toFloat() / axisLength
        val exportRecord = data.exportRecord
        val exportMatchesSelection = latestExport != null && exportRecord != null &&
            exportRecord.matches(
                result = effectiveResult,
                selectedStart = start,
                selectedEnd = end,
                includeTop = exportRecord.includeTop,
                includeBottom = exportRecord.includeBottom,
            )
        val exportAvailability = scrollExportAvailability(
            regions = normalizedRegions,
            maximumSegmentLength = maximumSegmentLength,
            minimumSegmentLength = minimumSegmentLength,
        )
        uiState = uiState.copy(
            loading = false,
            error = null,
            resultId = data.result.id,
            sessionId = data.result.sessionId,
            topBitmap = data.top.bitmap,
            scrollBitmap = data.scroll.bitmap,
            scrollViewportPreview = null,
            bottomBitmap = data.bottom.bitmap,
            topHeightPixels = data.top.height,
            topWidthPixels = data.top.width,
            scrollWidthPixels = data.scroll.width,
            scrollHeightPixels = data.scroll.height,
            bottomHeightPixels = data.bottom.height,
            bottomWidthPixels = data.bottom.width,
            firstFixedSide = data.firstFixedSide.takeIf { data.top.bitmap != null },
            secondFixedSide = data.secondFixedSide.takeIf { data.bottom.bitmap != null },
            topFixedAnchorCount = data.firstFixedLayer?.anchorCount ?: 1,
            topFixedAnchorPosition = data.firstFixedLayer?.anchorPosition
                ?: FixedAnchorPosition.LEADING,
            topFixedRepeatRegion = data.firstFixedLayer?.repeatRegion,
            bottomFixedAnchorCount = data.secondFixedLayer?.anchorCount ?: 1,
            bottomFixedAnchorPosition = data.secondFixedLayer?.anchorPosition
                ?: FixedAnchorPosition.LEADING,
            bottomFixedRepeatRegion = data.secondFixedLayer?.repeatRegion,
            fixedBandsOwnCorners =
                data.result.schemaVersion >= FIXED_BAND_CORNER_OWNERSHIP_SCHEMA_VERSION,
            horizontal = horizontal,
            frames = data.frames,
            range = startFraction..endFraction,
            scrollExportRegions = normalizedRegions,
            maximumScrollSegmentLength = maximumSegmentLength,
            minimumScrollSegmentLength = minimumSegmentLength,
            includeTop = includeTop,
            includeBottom = includeBottom,
            busyMessage = null,
            statusMessage = localizedScrollExportUnavailableMessage(exportAvailability),
            canShare = exportMatchesSelection,
            hasMatchingExport = exportMatchesSelection,
            outputFormat = selectedFormat,
            jpegQuality = selectedJpegQuality,
        )
        if (data.result.scrollExportRegions != normalizedRegions ||
            data.result.selectedScrollStart != effectiveResult.selectedScrollStart ||
            data.result.selectedScrollEnd != effectiveResult.selectedScrollEnd
        ) {
            persistCropSelection()
        }
    }

    private fun updateRange(range: ClosedFloatingPointRange<Float>) {
        val axisLength = uiState.axisLength.coerceAtLeast(1)
        val start = (axisLength * range.start).roundToInt().coerceIn(0, axisLength - 1)
        val end = (axisLength * range.endInclusive).roundToInt().coerceIn(start + 1, axisLength)
        updateScrollExportRegions(listOf(ScrollExportRegion(start, end)))
    }

    private fun updateScrollExportRegions(regions: List<ScrollExportRegion>) {
        val axisLength = uiState.axisLength.coerceAtLeast(1)
        val normalized = normalizeScrollExportRegions(
            axisLength = axisLength,
            selectedStart = regions.firstOrNull()?.start ?: uiState.selectedStart,
            selectedEnd = regions.lastOrNull()?.end ?: uiState.selectedEnd,
            storedRegions = regions,
            maximumSegmentLength = uiState.maximumScrollSegmentLength,
            minimumSegmentLength = uiState.minimumScrollSegmentLength,
        )
        val start = normalized.firstOrNull()?.start ?: 0
        val end = normalized.lastOrNull()?.end ?: axisLength
        uiState = uiState.copy(
            range = start.toFloat() / axisLength..end.toFloat() / axisLength,
            scrollExportRegions = normalized,
            statusMessage = null,
            canShare = false,
            hasMatchingExport = false,
        )
    }

    private fun updateIncludeTop(include: Boolean) {
        uiState = resegmentForFixedLayerSelection(
            uiState.copy(includeTop = include, statusMessage = null),
        )
        persistCropSelection()
        refreshExportAvailability()
    }

    private fun updateIncludeBottom(include: Boolean) {
        uiState = resegmentForFixedLayerSelection(
            uiState.copy(includeBottom = include, statusMessage = null),
        )
        persistCropSelection()
        refreshExportAvailability()
    }

    private fun resegmentForFixedLayerSelection(state: ResultEditorUiState): ResultEditorUiState {
        val result = currentResult ?: return state
        val minimum = minimumScrollExportRegionLength(
            horizontal = state.horizontal,
            fixedLayers = result.fixedLayers,
            includeFirstFixed = state.includeTop,
            includeSecondFixed = state.includeBottom,
            schemaVersion = result.schemaVersion,
        )
        val maximum = editorMaximumScrollSegmentLength(
            horizontal = state.horizontal,
            scrollWidth = state.scrollWidth,
            scrollHeight = state.scrollHeight,
            firstFixedSide = state.firstFixedSide,
            firstFixedWidth = state.topWidthPixels,
            firstFixedHeight = state.topHeightPixels,
            includeFirstFixed = state.includeTop,
            secondFixedSide = state.secondFixedSide,
            secondFixedWidth = state.bottomWidthPixels,
            secondFixedHeight = state.bottomHeightPixels,
            includeSecondFixed = state.includeBottom,
            outputFormat = state.outputFormat,
            outputAxisLimit = DefaultCaptureConfig.MAX_OUTPUT_DIMENSION,
        )
        val regions = normalizeScrollExportRegions(
            axisLength = state.axisLength,
            selectedStart = state.selectedStart,
            selectedEnd = state.selectedEnd,
            storedRegions = state.effectiveScrollExportRegions,
            maximumSegmentLength = maximum,
            minimumSegmentLength = minimum,
        )
        val availability = scrollExportAvailability(
            regions = regions,
            maximumSegmentLength = maximum,
            minimumSegmentLength = minimum,
        )
        return state.copy(
            maximumScrollSegmentLength = maximum,
            minimumScrollSegmentLength = minimum,
            scrollExportRegions = regions,
            range = regions.first().start.toFloat() / state.axisLength..
                regions.last().end.toFloat() / state.axisLength,
            statusMessage = localizedScrollExportUnavailableMessage(availability),
            canShare = false,
            hasMatchingExport = false,
        )
    }

    private fun updateOutputFormat(format: ImageOutputFormat) {
        if (format == uiState.outputFormat) return
        currentResult = currentResult?.copy(
            outputFormat = format,
            jpegQuality = uiState.jpegQuality,
        )
        uiState = resegmentForFixedLayerSelection(
            uiState.copy(outputFormat = format, statusMessage = null),
        )
        persistCropSelection()
        refreshExportAvailability()
    }

    private fun updateJpegQuality(quality: Int) {
        val normalized = ImageOutputFormat.normalizeJpegQuality(quality)
        if (normalized == uiState.jpegQuality) return
        currentResult = currentResult?.copy(
            outputFormat = uiState.outputFormat,
            jpegQuality = normalized,
        )
        uiState = uiState.copy(jpegQuality = normalized, statusMessage = null)
        refreshExportAvailability()
    }

    private fun refreshExportAvailability() {
        val result = currentResult
        val record = result?.lastExport
        val available = latestExport != null && record != null && record.matches(
            result = result,
            selectedStart = uiState.selectedStart,
            selectedEnd = uiState.selectedEnd,
            includeTop = uiState.includeTop,
            includeBottom = uiState.includeBottom,
        )
        uiState = uiState.copy(
            canShare = available,
            hasMatchingExport = available,
        )
    }

    private fun persistCropSelection() {
        val result = currentResult ?: return
        val start = uiState.selectedStart
        val end = uiState.selectedEnd
        val outputFormat = uiState.outputFormat
        val jpegQuality = uiState.jpegQuality
        val regions = uiState.effectiveScrollExportRegions
        if (end <= start) return
        val previousSave = cropSaveJob
        cropSaveJob = lifecycleScope.launch {
            previousSave?.join()
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.updateAtomically(result.id) { latest ->
                        mergeResultEditorSelection(
                            latest = latest,
                            selectedStart = start,
                            selectedEnd = end,
                            regions = regions,
                            outputFormat = outputFormat,
                            jpegQuality = jpegQuality,
                        )
                    } ?: error("Result was removed while saving its editor state")
                }
            }.onSuccess { updated ->
                currentResult = updated.copy(
                    outputFormat = uiState.outputFormat,
                    jpegQuality = uiState.jpegQuality,
                )
                val availability = scrollExportAvailability(
                    regions = uiState.effectiveScrollExportRegions,
                    maximumSegmentLength = uiState.maximumScrollSegmentLength,
                    minimumSegmentLength = uiState.minimumScrollSegmentLength,
                )
                uiState = uiState.copy(
                    statusMessage = if (availability.canExport) null else uiState.statusMessage,
                )
                refreshExportAvailability()
            }.onFailure { error ->
                uiState = uiState.copy(
                    statusMessage = getString(
                        R.string.result_editor_crop_save_failed,
                        localizedErrorOrFallback(error, R.string.result_error_unknown),
                    ),
                )
            }
            if (cropSaveJob === kotlin.coroutines.coroutineContext[Job]) cropSaveJob = null
        }
    }

    private fun exportResult() {
        if (exportJob != null) {
            uiState = uiState.copy(
                statusMessage = getString(R.string.result_editor_export_already_running),
            )
            return
        }
        val baseResult = currentResult ?: return
        val selectedStart = uiState.selectedStart
        val selectedEnd = uiState.selectedEnd
        val exportRegions = uiState.enabledScrollExportRegions
        val allExportRegions = uiState.effectiveScrollExportRegions
        val exportAvailability = scrollExportAvailability(
            regions = allExportRegions,
            maximumSegmentLength = uiState.maximumScrollSegmentLength,
            minimumSegmentLength = uiState.minimumScrollSegmentLength,
        )
        val unavailableMessage = localizedScrollExportUnavailableMessage(exportAvailability)
        if (selectedEnd <= selectedStart || unavailableMessage != null) {
            uiState = uiState.copy(
                statusMessage = unavailableMessage
                    ?: getString(R.string.result_editor_invalid_scroll_range),
            )
            return
        }
        val includeTop = uiState.includeTop
        val includeBottom = uiState.includeBottom
        val outputFormat = uiState.outputFormat
        val jpegQuality = uiState.jpegQuality
        val exportDestination = exportDestinationOverride
            ?: storageLocationPreferences.exportTreeUri
        uiState = uiState.copy(
            busyMessage = localizedExportProgress(completed = 0, total = exportRegions.size),
            busyCancellable = true,
            statusMessage = null,
        )
        exportJob = lifecycleScope.launch {
            val runningJob = kotlin.coroutines.coroutineContext[Job]
            var newlyPersistedDestination: Uri? = null
            var destinationPermissionReferencedByResult = false
            var exportCommitted = false
            var completedExport: ExportResult? = null
            var committedResult: LayeredCaptureResult? = null
            val publishedUris = mutableListOf<Uri>()
            suspend fun rollbackPublishedUris() {
                withContext(NonCancellable + Dispatchers.IO) {
                    publishedUris.asReversed().forEach { uri ->
                        runCatching { exporter.deleteExportedOutput(uri, exportDestination) }
                    }
                    publishedUris.clear()
                }
            }
            fun applyCommittedExportState() {
                val exported = completedExport ?: return
                val persisted = committedResult ?: return
                currentResult = persisted
                latestExport = exported
                uiState = uiState.copy(
                    canShare = true,
                    hasMatchingExport = true,
                    statusMessage = null,
                )
            }
            try {
                val prepared = withContext(Dispatchers.IO) {
                    cropSaveJob?.join()
                    exportDestination?.let { destination ->
                        val alreadyGranted = contentResolver.persistedUriPermissions.any {
                            it.uri == destination && it.isReadPermission && it.isWritePermission
                        }
                        if (!alreadyGranted) {
                            contentResolver.takePersistableUriPermission(
                                destination,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                            newlyPersistedDestination = destination
                        }
                    }
                    val latestResult = repository.getById(baseResult.id) ?: baseResult
                    val resultForExport = latestResult.copy(
                        selectedScrollStart = selectedStart,
                        selectedScrollEnd = selectedEnd,
                        scrollExportRegions = allExportRegions,
                        outputFormat = outputFormat,
                        jpegQuality = jpegQuality,
                    )
                    val baseName = defaultCaptureExportBaseName(resultForExport)
                    val parts = exportRegions.mapIndexed { index, region ->
                        exporter.export(
                            result = resultForExport.copy(
                                selectedScrollStart = region.start,
                                selectedScrollEnd = region.end,
                            ),
                            options = ExportOptions(
                                displayName = if (exportRegions.size > 1) {
                                    "%s_%02d".format(Locale.ROOT, baseName, index + 1)
                                } else {
                                    null
                                },
                                includeTop = includeTop,
                                includeBottom = includeBottom,
                                format = outputFormat,
                                jpegQuality = jpegQuality,
                                destinationTreeUri = exportDestination,
                            ),
                            isCancelled = { runningJob?.isActive == false },
                        ) { completed, total ->
                            val safeTotal = total.coerceAtLeast(1)
                            val globalCompleted = index * 100 + completed * 100 / safeTotal
                            val globalTotal = exportRegions.size * 100
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed && runningJob?.isActive == true) {
                                    uiState = uiState.copy(
                                        busyMessage = localizedExportProgress(
                                            globalCompleted,
                                            globalTotal,
                                        ),
                                    )
                                }
                            }
                        }.also { part -> publishedUris += part.uris }
                    }
                    val value = ExportResult(
                        uris = parts.flatMap(ExportResult::uris),
                        format = outputFormat,
                        width = parts.maxOf(ExportResult::width),
                        totalHeight = parts.sumOf(ExportResult::totalHeight),
                        segmented = parts.size > 1,
                        displayNames = parts.flatMap(ExportResult::displayNames),
                    )
                    val record = value.toExportRecord(
                        result = resultForExport,
                        selectedStart = selectedStart,
                        selectedEnd = selectedEnd,
                        includeTop = includeTop,
                        includeBottom = includeBottom,
                        destinationTreeUri = exportDestination,
                    )
                    PreparedEditorExport(
                        export = value,
                        expectedResult = latestResult,
                        resultForExport = resultForExport,
                        record = record,
                    )
                }
                completedExport = prepared.export
                currentCoroutineContext().ensureActive()
                // After this point the published files and their metadata change ownership together.
                withContext(NonCancellable + Dispatchers.IO) {
                    committedResult = repository.updateAtomically(prepared.resultForExport.id) { latest ->
                        mergeCompletedResultEditorExport(
                            latest = latest,
                            expected = prepared.expectedResult,
                            exported = prepared.resultForExport,
                            record = prepared.record,
                        )
                    } ?: throw ConcurrentModificationException(
                        "Result changed while the export was being rendered",
                    )
                    destinationPermissionReferencedByResult = true
                    exportCommitted = true
                }
                applyCommittedExportState()
            } catch (_: CancellationException) {
                if (exportCommitted) {
                    applyCommittedExportState()
                } else {
                    rollbackPublishedUris()
                    uiState = uiState.copy(statusMessage = null)
                    refreshExportAvailability()
                }
            } catch (error: Throwable) {
                if (exportCommitted) {
                    applyCommittedExportState()
                } else {
                    rollbackPublishedUris()
                    uiState = uiState.copy(
                        statusMessage = getString(
                            R.string.result_editor_export_failed,
                            localizedErrorOrFallback(
                                error,
                                R.string.result_editor_export_retry_shorter_range,
                            ),
                        ),
                    )
                    refreshExportAvailability()
                }
            } finally {
                if (!destinationPermissionReferencedByResult) {
                    storageLocationPreferences.releasePermissionIfUnused(
                        newlyPersistedDestination,
                    )
                }
                if (exportJob === runningJob) exportJob = null
                uiState = uiState.copy(busyMessage = null, busyCancellable = false)
            }
        }
    }

    private fun cancelExport() {
        exportJob?.cancel()
        uiState = uiState.copy(
            busyMessage = null,
            busyCancellable = false,
            statusMessage = null,
        )
        refreshExportAvailability()
    }

    private fun openLatestExport() {
        val exported = latestExport?.takeIf { uiState.hasMatchingExport } ?: run {
            uiState = uiState.copy(
                statusMessage = getString(R.string.result_editor_export_unavailable),
            )
            return
        }
        val result = currentResult
        val record = result?.lastExport
        val viewerIntent = if (!shouldUsePersistedResultViewerTransport(exported.uris.size)) {
            ResultViewerActivity.createIntent(
                context = this,
                uris = exported.uris,
                displayNames = exported.displayNames,
            )
        } else if (record != null) {
            ResultViewerActivity.createResultIntent(
                context = this,
                resultId = result.id,
                record = record,
            )
        } else {
            uiState = uiState.copy(
                statusMessage = getString(R.string.result_editor_export_unavailable),
            )
            return
        }
        startActivity(viewerIntent)
    }

    private fun shareLatestExport() {
        val exported = latestExport ?: run {
            uiState = uiState.copy(
                statusMessage = getString(R.string.result_editor_export_before_share),
            )
            return
        }
        val mimeType = exported.format.mimeType
        val shareIntent = if (exported.uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, exported.uri)
                clipData = ClipData.newUri(contentResolver, "GameLongShot", exported.uri)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(exported.uris))
                clipData = ClipData.newUri(contentResolver, "GameLongShot", exported.uris.first()).apply {
                    exported.uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
            }
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching {
            startActivity(
                Intent.createChooser(
                    shareIntent,
                    getString(R.string.result_share_chooser_title),
                ),
            )
        }.onFailure {
            uiState = uiState.copy(statusMessage = getString(R.string.result_no_share_apps))
        }
    }

    private fun openExportDirectoryPicker() {
        exportDirectoryLauncher.launch(
            exportDestinationOverride ?: storageLocationPreferences.exportTreeUri,
        )
    }

    private fun selectExportDirectory(uri: Uri) {
        exportDestinationOverride = uri
        exportLocation = friendlyDirectoryName(uri)
        Toast.makeText(
            this,
            getString(R.string.result_editor_export_location_changed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun copyExportLocation() {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.result_editor_export_location),
                exportLocation,
            ),
        )
    }

    private fun copyExpectedSize() {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.result_editor_expected_size),
                getString(
                    R.string.result_editor_output_dimensions,
                    uiState.exportCount,
                    uiState.finalWidth,
                    uiState.finalHeight,
                ),
            ),
        )
    }

    private fun friendlyDirectoryName(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        if (!documentId.isNullOrBlank()) {
            val volumeAndPath = documentId.split(':', limit = 2)
            val volume = when (volumeAndPath.first().lowercase(Locale.ROOT)) {
                "primary" -> getString(R.string.result_editor_internal_storage)
                "home" -> getString(R.string.result_editor_documents)
                else -> volumeAndPath.first()
            }
            val relativePath = volumeAndPath.getOrNull(1)
                ?.replace('\\', '/')
                ?.trim('/')
                .orEmpty()
            return if (relativePath.isBlank()) volume else "$volume/$relativePath"
        }
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank {
            getString(R.string.result_editor_selected_directory)
        }
    }

    private fun localizedExportProgress(completed: Int, total: Int): String {
        val percent = if (total <= 0) 0 else (completed * 100 / total).coerceIn(0, 100)
        return getString(R.string.result_export_progress, percent)
    }

    private fun ownPreview(bitmap: Bitmap) {
        if (!ownedPreviewBitmaps.contains(bitmap)) ownedPreviewBitmaps += bitmap
    }

    private fun loadScrollViewportPreview(request: ScrollViewportPreviewRequest?) {
        if (request == null) {
            requestedScrollViewportPreview = null
            scrollViewportPreviewJob?.cancel()
            scrollViewportPreviewJob = null
            clearScrollViewportPreview()
            return
        }
        if (request == requestedScrollViewportPreview &&
            uiState.scrollViewportPreview?.let { it.start == request.start && it.end == request.end } == true
        ) {
            return
        }
        val result = currentResult ?: return
        requestedScrollViewportPreview = request
        scrollViewportPreviewJob?.cancel()
        scrollViewportPreviewJob = lifecycleScope.launch {
            val runningJob = kotlin.coroutines.coroutineContext[Job]
            var pending: ScrollViewportPreview? = null
            try {
                withContext(Dispatchers.IO) {
                    scrollViewportPreviewSemaphore.withPermit {
                        // Assign inside the IO context so prompt cancellation while returning to
                        // the main dispatcher cannot orphan a freshly decoded bitmap.
                        pending = ScrollChunkViewportPreviewLoader.load(
                            resourceAccess,
                            result,
                            request,
                        )
                    }
                }
                val loaded = pending ?: run {
                    if (requestedScrollViewportPreview == request) {
                        requestedScrollViewportPreview = null
                        clearScrollViewportPreview()
                    }
                    return@launch
                }
                if (isFinishing || isDestroyed || currentResult?.id != result.id ||
                    requestedScrollViewportPreview != request
                ) {
                    return@launch
                }
                val previous = uiState.scrollViewportPreview?.bitmap
                uiState = uiState.copy(scrollViewportPreview = loaded)
                ownPreview(loaded.bitmap)
                previous?.takeIf { it !== loaded.bitmap }?.let(::releasePreviewLater)
                pending = null
            } catch (_: OutOfMemoryError) {
                pending?.bitmap?.let { bitmap ->
                    ownedPreviewBitmaps.remove(bitmap)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                pending = null
                if (requestedScrollViewportPreview == request) {
                    requestedScrollViewportPreview = null
                    clearScrollViewportPreview()
                }
            } finally {
                pending?.bitmap?.let { bitmap ->
                    ownedPreviewBitmaps.remove(bitmap)
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
                if (scrollViewportPreviewJob === runningJob) {
                    scrollViewportPreviewJob = null
                }
            }
        }
    }

    private fun clearScrollViewportPreview() {
        val previous = uiState.scrollViewportPreview?.bitmap ?: return
        uiState = uiState.copy(scrollViewportPreview = null)
        releasePreviewLater(previous)
    }

    private fun loadFramePreview(frameIndex: Int) {
        if (uiState.frames.firstOrNull { it.index == frameIndex }?.bitmap != null) return
        framePreviewCache[frameIndex]?.let { cached ->
            uiState = uiState.copy(
                frames = uiState.frames.map { frame ->
                    if (frame.index == frameIndex) frame.copy(bitmap = cached) else frame
                },
            )
            return
        }
        if (frameIndex in framePreviewJobs) return
        val result = currentResult ?: return
        val source = result.sourceFrames.firstOrNull { it.index == frameIndex } ?: return
        framePreviewJobs[frameIndex] = lifecycleScope.launch {
            val runningJob = kotlin.coroutines.coroutineContext[Job]
            var pendingPreview: Bitmap? = null
            try {
                withContext(Dispatchers.IO) {
                    framePreviewSemaphore.withPermit {
                        pendingPreview = if (source.scrollBitmapPath.isNotBlank()) {
                            BitmapPreviewLoader.load(
                                resourceAccess,
                                source.scrollBitmapPath,
                                maxWidth = FRAME_PREVIEW_MAX_WIDTH,
                                maxHeight = FRAME_PREVIEW_MAX_HEIGHT,
                            ).bitmap
                        } else {
                            ChunkFramePreviewLoader.load(
                                resourceAccess = resourceAccess,
                                result = result,
                                frameIndex = frameIndex,
                                maxWidth = FRAME_PREVIEW_MAX_WIDTH,
                                maxHeight = FRAME_PREVIEW_MAX_HEIGHT,
                            )
                        }
                    }
                }
                val preview = pendingPreview ?: return@launch
                if (isFinishing || isDestroyed) return@launch
                if (framePreviewCache.size >= MAX_FRAME_PREVIEWS) {
                    val oldest = framePreviewCache.entries.iterator().next()
                    framePreviewCache.remove(oldest.key)
                    ownedPreviewBitmaps.remove(oldest.value)
                    uiState = uiState.copy(
                        frames = uiState.frames.map { frame ->
                            if (frame.index == oldest.key) frame.copy(bitmap = null) else frame
                        },
                    )
                    releasePreviewLater(oldest.value)
                }
                framePreviewCache[frameIndex] = preview
                ownPreview(preview)
                pendingPreview = null
                uiState = uiState.copy(
                    frames = uiState.frames.map { frame ->
                        if (frame.index == frameIndex) frame.copy(bitmap = preview) else frame
                    },
                )
            } finally {
                if (framePreviewJobs[frameIndex] === runningJob) {
                    framePreviewJobs.remove(frameIndex)
                }
                pendingPreview?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    private fun cancelFramePreviewLoad(frameIndex: Int) {
        framePreviewJobs.remove(frameIndex)?.cancel()
    }

    private fun releasePreviewLater(bitmap: Bitmap) {
        ownedPreviewBitmaps.remove(bitmap)
        window.decorView.postDelayed({
            if (!bitmap.isRecycled && bitmap !in ownedPreviewBitmaps) bitmap.recycle()
        }, BITMAP_RECYCLE_DELAY_MS)
    }

    private fun clearOwnedPreviews() {
        scrollViewportPreviewJob?.cancel()
        scrollViewportPreviewJob = null
        requestedScrollViewportPreview = null
        framePreviewJobs.values.forEach(Job::cancel)
        framePreviewJobs.clear()
        framePreviewCache.clear()
        ownedPreviewBitmaps.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        ownedPreviewBitmaps.clear()
        if (uiState.scrollViewportPreview != null) {
            uiState = uiState.copy(scrollViewportPreview = null)
        }
    }

    private data class LoadedEditorData(
        val result: LayeredCaptureResult,
        val top: PreviewBitmap,
        val scroll: PreviewBitmap,
        val bottom: PreviewBitmap,
        val firstFixedSide: FixedSide,
        val secondFixedSide: FixedSide,
        val firstFixedLayer: CapturedFixedLayer?,
        val secondFixedLayer: CapturedFixedLayer?,
        val frames: List<SourceFrameUi>,
        val exportRecord: ResultExportRecord?,
    )

    private data class PreparedEditorExport(
        val export: ExportResult,
        val expectedResult: LayeredCaptureResult,
        val resultForExport: LayeredCaptureResult,
        val record: ResultExportRecord,
    )

    companion object {
        const val EXTRA_RESULT_ID = "result_id"
        private const val MAX_FRAME_PREVIEWS = 12
        private const val FRAME_PREVIEW_CONCURRENCY = 2
        private const val FRAME_PREVIEW_MAX_WIDTH = 720
        private const val FRAME_PREVIEW_MAX_HEIGHT = 960
        private const val BITMAP_RECYCLE_DELAY_MS = 1_000L
        private const val STATE_EXPORT_DESTINATION_URI = "export_destination_uri"
        private const val STATE_EXPORT_DESTINATION_NAME = "export_destination_name"
        private const val STATE_OUTPUT_FORMAT = "output_format"
        private const val STATE_JPEG_QUALITY = "jpeg_quality"
    }
}
