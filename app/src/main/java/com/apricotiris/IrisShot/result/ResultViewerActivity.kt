package com.apricotiris.IrisShot.result

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.model.ResultExportRecord
import com.apricotiris.IrisShot.core.storage.CaptureResultRepository
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.LocalizedComponentActivity
import com.apricotiris.IrisShot.ui.localizedErrorOrFallback
import com.apricotiris.IrisShot.ui.theme.GameLongShotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ResultViewerLaunchPayload(
    val resources: List<String>,
    val displayNames: List<String>,
)

internal fun shouldUsePersistedResultViewerTransport(resourceCount: Int): Boolean =
    resourceCount > 1

internal fun persistedResultViewerLaunchPayload(
    record: ResultExportRecord?,
    expectedSignature: String?,
    expectedExportedAt: Long,
    expectedFirstResource: String?,
): ResultViewerLaunchPayload? {
    if (record == null || expectedSignature.isNullOrBlank() || expectedFirstResource.isNullOrBlank() ||
        record.parameterSignature != expectedSignature || record.exportedAt != expectedExportedAt ||
        record.uris.firstOrNull() != expectedFirstResource
    ) {
        return null
    }
    return ResultViewerLaunchPayload(record.uris, record.displayNames)
}

internal fun viewerSampleSize(
    width: Int,
    height: Int,
    maxPixels: Long,
    maxDimension: Int,
): Int {
    require(width > 0 && height > 0 && maxPixels > 0 && maxDimension > 0)
    var sample = 1
    while (width.toLong() / sample * (height.toLong() / sample) > maxPixels ||
        width / sample > maxDimension || height / sample > maxDimension
    ) {
        sample *= 2
    }
    return sample
}

internal object ExportPreviewLoader {
    internal data class LoadedPreview(
        val bitmap: Bitmap,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sampleSize: Int,
    )

    fun load(
        resourceAccess: StorageResourceAccess,
        resource: String,
        maxPixels: Long = MAX_PREVIEW_PIXELS,
        maxDimension: Int = MAX_PREVIEW_DIMENSION,
    ): Bitmap? = loadPreview(resourceAccess, resource, maxPixels, maxDimension)?.bitmap

    fun loadPreview(
        resourceAccess: StorageResourceAccess,
        resource: String,
        maxPixels: Long = MAX_PREVIEW_PIXELS,
        maxDimension: Int = MAX_PREVIEW_DIMENSION,
    ): LoadedPreview? {
        if (resource.isBlank()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resourceAccess.decodeBitmap(resource, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sampleSize = viewerSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxPixels = maxPixels,
            maxDimension = maxDimension,
        )
        val bitmap = resourceAccess.decodeBitmap(
            resource,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            },
        ) ?: return null
        return LoadedPreview(
            bitmap = bitmap,
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            sampleSize = sampleSize,
        )
    }

    private const val MAX_PREVIEW_PIXELS = 5_000_000L
    private const val MAX_PREVIEW_DIMENSION = 8_192
}

internal object ExportRegionLoader {
    fun load(
        resourceAccess: StorageResourceAccess,
        resource: String,
        request: ViewerRegionRequest,
    ): Bitmap? {
        if (resource.isBlank() || request.width <= 0 || request.height <= 0 ||
            request.sampleSize <= 0
        ) {
            return null
        }
        return resourceAccess.openRegionDecoder(resource)?.use { managed ->
            if (request.left < 0 || request.top < 0 ||
                request.right > managed.width || request.bottom > managed.height
            ) {
                return@use null
            }
            managed.decodeRegion(
                Rect(request.left, request.top, request.right, request.bottom),
                BitmapFactory.Options().apply {
                    inSampleSize = request.sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inScaled = false
                },
            )
        }
    }
}

class ResultViewerActivity : LocalizedComponentActivity() {
    private lateinit var resourceAccess: StorageResourceAccess
    private var state by mutableStateOf(ResultViewerUiState())
    private var loadJob: Job? = null
    private var launchJob: Job? = null
    private var detailJob: Job? = null
    private var currentBitmap: Bitmap? = null
    private var currentDetailBitmap: Bitmap? = null
    private var requestedDetail: ViewerRegionRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        resourceAccess = StorageResourceAccess(this)
        val fallbackPayload = directIntentPayload(intent)
        val selectedIndex = savedInstanceState?.getInt(STATE_SELECTED_INDEX) ?: 0
        val themeSettings = AppPreferences(this).loadSettings()
        setContent {
            GameLongShotTheme(
                themeMode = themeSettings.themeMode,
                dynamicColor = themeSettings.dynamicColor,
            ) {
                ResultViewerScreen(
                    state = state,
                    onBack = ::finish,
                    onSelect = ::loadPage,
                    onRetry = { loadPage(state.selectedIndex) },
                    onDetailRequest = ::loadDetailRegion,
                )
            }
        }
        val resultId = intent.getStringExtra(EXTRA_RESULT_ID)?.takeIf(String::isNotBlank)
        if (resultId == null) {
            applyLaunchPayload(fallbackPayload, selectedIndex)
        } else {
            launchJob = lifecycleScope.launch {
                val persistedPayload = withContext(Dispatchers.IO) {
                    val record = CaptureResultRepository(this@ResultViewerActivity)
                        .getById(resultId)
                        ?.lastExport
                    persistedResultViewerLaunchPayload(
                        record = record,
                        expectedSignature = intent.getStringExtra(EXTRA_EXPORT_SIGNATURE),
                        expectedExportedAt = intent.getLongExtra(EXTRA_EXPORTED_AT, Long.MIN_VALUE),
                        expectedFirstResource = intent.data?.toString(),
                    )
                }
                if (!isFinishing && !isDestroyed) {
                    applyLaunchPayload(persistedPayload ?: fallbackPayload, selectedIndex)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_INDEX, state.selectedIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        launchJob?.cancel()
        loadJob?.cancel()
        detailJob?.cancel()
        currentBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        currentDetailBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        currentBitmap = null
        currentDetailBitmap = null
        super.onDestroy()
    }

    private fun applyLaunchPayload(payload: ResultViewerLaunchPayload, selectedIndex: Int) {
        val resources = payload.resources.mapNotNull { value ->
            runCatching { Uri.parse(value) }.getOrNull()
                ?.takeIf { it.scheme == "content" }
                ?.toString()
        }
        state = ResultViewerUiState(resources = resources, displayNames = payload.displayNames)
        if (resources.isEmpty()) {
            state = state.copy(
                loading = false,
                error = getString(R.string.result_viewer_no_images),
            )
        } else {
            loadPage(selectedIndex)
        }
    }

    @Suppress("DEPRECATION")
    private fun directIntentPayload(intent: Intent): ResultViewerLaunchPayload {
        val legacyResources = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        if (legacyResources.isNotEmpty()) {
            return ResultViewerLaunchPayload(
                resources = legacyResources,
                displayNames = intent.getStringArrayListExtra(EXTRA_DISPLAY_NAMES).orEmpty(),
            )
        }
        val sharedResources = buildList {
            intent.data?.let { add(it) }
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { add(it) }
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().forEach(::add)
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { index -> clip.getItemAt(index).uri?.let(::add) }
            }
        }.map(Uri::toString).distinct()
        val fallbackName = intent.getStringExtra(EXTRA_FALLBACK_DISPLAY_NAME)
        return ResultViewerLaunchPayload(
            resources = sharedResources,
            displayNames = listOfNotNull(fallbackName),
        )
    }

    private fun loadPage(index: Int) {
        if (state.resources.isEmpty()) return
        val selected = index.coerceIn(state.resources.indices)
        if (selected == state.selectedIndex && state.bitmap != null && state.error == null) return
        loadJob?.cancel()
        clearDetailRegion()
        val resource = state.resources[selected]
        state = state.copy(
            selectedIndex = selected,
            bitmap = null,
            sourceWidth = 0,
            sourceHeight = 0,
            overviewSampleSize = 1,
            loading = true,
            error = null,
        )
        loadJob = lifecycleScope.launch {
            val runningJob = kotlin.coroutines.coroutineContext[Job]
            var pending: ExportPreviewLoader.LoadedPreview? = null
            try {
                pending = withContext(Dispatchers.IO) {
                    ExportPreviewLoader.loadPreview(resourceAccess, resource)
                }
                val loaded = pending ?: error(getString(R.string.result_viewer_load_failed))
                if (isFinishing || isDestroyed || state.selectedIndex != selected) return@launch
                val previous = currentBitmap
                currentBitmap = loaded.bitmap
                pending = null
                state = state.copy(
                    bitmap = loaded.bitmap,
                    sourceWidth = loaded.sourceWidth,
                    sourceHeight = loaded.sourceHeight,
                    overviewSampleSize = loaded.sampleSize,
                    loading = false,
                    error = null,
                )
                previous?.let { bitmap ->
                    releaseBitmapLater(bitmap) { bitmap === currentBitmap }
                }
            } catch (error: Throwable) {
                if (runningJob?.isCancelled != true && state.selectedIndex == selected) {
                    state = state.copy(
                        bitmap = null,
                        loading = false,
                        error = localizedErrorOrFallback(
                            error,
                            R.string.result_viewer_load_failed,
                        ),
                    )
                }
            } finally {
                pending?.bitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
                if (loadJob === runningJob) loadJob = null
            }
        }
    }

    private fun loadDetailRegion(request: ViewerRegionRequest?) {
        if (request == null) {
            clearDetailRegion()
            return
        }
        if (request == requestedDetail && state.detailRequest == request &&
            state.detailBitmap != null
        ) {
            return
        }
        val selected = state.selectedIndex
        val resource = state.resources.getOrNull(selected) ?: return
        requestedDetail = request
        detailJob?.cancel()
        detailJob = lifecycleScope.launch {
            val runningJob = kotlin.coroutines.coroutineContext[Job]
            var pending: Bitmap? = null
            try {
                pending = withContext(Dispatchers.IO) {
                    ExportRegionLoader.load(resourceAccess, resource, request)
                }
                val loaded = pending ?: return@launch
                if (isFinishing || isDestroyed || state.selectedIndex != selected ||
                    requestedDetail != request
                ) {
                    return@launch
                }
                val previous = currentDetailBitmap
                currentDetailBitmap = loaded
                pending = null
                state = state.copy(
                    detailBitmap = loaded,
                    detailRequest = request,
                )
                previous?.let { bitmap ->
                    releaseBitmapLater(bitmap) { bitmap === currentDetailBitmap }
                }
            } catch (_: Throwable) {
                if (runningJob?.isCancelled != true && requestedDetail == request) {
                    // Keep the overview (and any previously decoded detail) usable. A later pan or
                    // zoom change will naturally issue another bounded region request.
                    requestedDetail = null
                }
            } finally {
                pending?.takeUnless(Bitmap::isRecycled)?.recycle()
                if (detailJob === runningJob) detailJob = null
            }
        }
    }

    private fun clearDetailRegion() {
        requestedDetail = null
        detailJob?.cancel()
        detailJob = null
        val previous = currentDetailBitmap
        currentDetailBitmap = null
        if (state.detailBitmap != null || state.detailRequest != null) {
            state = state.copy(detailBitmap = null, detailRequest = null)
        }
        previous?.let { bitmap ->
            releaseBitmapLater(bitmap) { bitmap === currentDetailBitmap }
        }
    }

    private fun releaseBitmapLater(bitmap: Bitmap, isCurrent: () -> Boolean) {
        window.decorView.postDelayed({
            if (!isCurrent() && !bitmap.isRecycled) bitmap.recycle()
        }, BITMAP_RECYCLE_DELAY_MS)
    }

    companion object {
        private const val EXTRA_URIS = "export_uris"
        private const val EXTRA_DISPLAY_NAMES = "export_display_names"
        private const val EXTRA_RESULT_ID = "export_result_id"
        private const val EXTRA_EXPORT_SIGNATURE = "export_signature"
        private const val EXTRA_EXPORTED_AT = "exported_at"
        private const val EXTRA_FALLBACK_DISPLAY_NAME = "fallback_display_name"
        private const val STATE_SELECTED_INDEX = "selected_index"
        private const val BITMAP_RECYCLE_DELAY_MS = 1_000L

        fun createIntent(
            context: Context,
            uris: List<Uri>,
            displayNames: List<String>,
        ): Intent = Intent(context, ResultViewerActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map(Uri::toString)))
            putStringArrayListExtra(EXTRA_DISPLAY_NAMES, ArrayList(displayNames))
        }

        fun createResultIntent(
            context: Context,
            resultId: String,
            record: ResultExportRecord,
        ): Intent {
            require(resultId.isNotBlank() && record.uris.isNotEmpty())
            return Intent(context, ResultViewerActivity::class.java).apply {
                putExtra(EXTRA_RESULT_ID, resultId)
                putExtra(EXTRA_EXPORT_SIGNATURE, record.parameterSignature)
                putExtra(EXTRA_EXPORTED_AT, record.exportedAt)
                data = Uri.parse(record.uris.first())
                record.displayNames.firstOrNull()?.let { name ->
                    putExtra(EXTRA_FALLBACK_DISPLAY_NAME, name)
                }
            }
        }
    }
}
