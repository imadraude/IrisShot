package com.apricotiris.IrisShot.result

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.app.ActivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.export.ExportOptions
import com.apricotiris.IrisShot.core.export.ExportResult
import com.apricotiris.IrisShot.core.export.LayeredImageExporter
import com.apricotiris.IrisShot.core.export.defaultCaptureExportBaseName
import com.apricotiris.IrisShot.core.model.FixedSide
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.model.ResultExportRecord
import com.apricotiris.IrisShot.core.storage.StorageResourceAccess
import com.apricotiris.IrisShot.core.storage.CaptureResultRepository
import com.apricotiris.IrisShot.core.storage.CaptureResultSummary
import com.apricotiris.IrisShot.core.storage.StorageLocationPreferences
import com.apricotiris.IrisShot.ui.CaptureUiContract
import com.apricotiris.IrisShot.ui.AppTopBar
import com.apricotiris.IrisShot.ui.AppPreferences
import com.apricotiris.IrisShot.ui.AppTooltipIconButton
import com.apricotiris.IrisShot.ui.ContentSortOrder
import com.apricotiris.IrisShot.ui.ContentSelectionActions
import com.apricotiris.IrisShot.ui.ContentSelectionTopBar
import com.apricotiris.IrisShot.ui.SortOrderSheet
import com.apricotiris.IrisShot.ui.localizedErrorOrFallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import kotlin.math.ceil

@Composable
fun ResultListRoute(
    isActive: () -> Boolean,
    refreshKey: Any?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onSelectionActionsChange: (ContentSelectionActions?) -> Unit = {},
    sortOrder: ContentSortOrder? = null,
    onSortOrderChange: ((ContentSortOrder) -> Unit)? = null,
) {
    val context = LocalContext.current
    val appPreferences = remember(context) { AppPreferences(context) }
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()
    val controller = remember(context) { ResultListController(context, scope) }
    val active = isActive()
    var localSelectionActions by remember { mutableStateOf<ContentSelectionActions?>(null) }
    var retainedSelectionActions by remember { mutableStateOf<ContentSelectionActions?>(null) }
    val transitionSelectionActions = localSelectionActions ?: retainedSelectionActions
    SideEffect {
        if (localSelectionActions != null) retainedSelectionActions = localSelectionActions
    }
    var internalSortOrder by rememberSaveable {
        mutableStateOf(appPreferences.loadResultSortOrder())
    }
    var showSortSheet by rememberSaveable { mutableStateOf(false) }
    val externallyControlledSort = sortOrder != null && onSortOrderChange != null
    val resolvedSortOrder = if (externallyControlledSort) sortOrder else internalSortOrder
        ?: ContentSortOrder.MODIFIED_DESC
    val updateSortOrder: (ContentSortOrder) -> Unit = { value ->
        if (externallyControlledSort) {
            onSortOrderChange?.invoke(value)
        } else {
            internalSortOrder = value
            appPreferences.saveResultSortOrder(value)
        }
        showSortSheet = false
    }
    val updateSelectionActions = remember(onSelectionActionsChange) {
        { actions: ContentSelectionActions? ->
            localSelectionActions = actions
            onSelectionActionsChange(actions)
        }
    }

    LaunchedEffect(active, refreshKey, resolvedSortOrder) {
        if (active) {
            controller.loadResults(sortOrder = resolvedSortOrder)
        }
    }
    DisposableEffect(lifecycleOwner, controller, active) {
        val observer = LifecycleEventObserver { _, event ->
            if (active && event == Lifecycle.Event.ON_RESUME) controller.loadResults()
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }

    if (onBack != null) {
        Scaffold(
            modifier = modifier,
            topBar = {
                Crossfade(
                    targetState = localSelectionActions != null,
                    animationSpec = tween(durationMillis = 180),
                    label = stringResource(R.string.result_list_selection_bar_animation),
                ) { selectionMode ->
                    if (selectionMode) {
                        transitionSelectionActions?.let { ContentSelectionTopBar(it) }
                    } else {
                        AppTopBar(
                            title = stringResource(R.string.result_list_title),
                            navigationIcon = {
                                AppTooltipIconButton(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.result_action_back),
                                    onClick = onBack,
                                )
                            },
                            actions = {
                                AppTooltipIconButton(
                                    imageVector = Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = stringResource(R.string.result_list_sort),
                                    tooltipText = stringResource(R.string.result_list_sort_tooltip),
                                    onClick = { showSortSheet = true },
                                )
                            },
                        )
                    }
                }
            },
        ) { paddingValues ->
            ResultListContent(
                controller = controller,
                isActive = isActive,
                onSelectionActionsChange = updateSelectionActions,
                sortOrder = resolvedSortOrder,
                modifier = Modifier.fillMaxSize().padding(paddingValues),
            )
        }
    } else {
        ResultListContent(
            controller = controller,
            isActive = isActive,
            onSelectionActionsChange = updateSelectionActions,
            sortOrder = resolvedSortOrder,
            modifier = modifier,
        )
    }

    if (showSortSheet) {
        SortOrderSheet(
            selected = resolvedSortOrder,
            options = ContentSortOrder.resultOptions,
            onSelect = updateSortOrder,
            onDismiss = { showSortSheet = false },
        )
    }
}

@Composable
private fun ResultListContent(
    controller: ResultListController,
    isActive: () -> Boolean,
    onSelectionActionsChange: (ContentSelectionActions?) -> Unit,
    sortOrder: ContentSortOrder,
    modifier: Modifier,
) {
    ResultListScreen(
        isActive = isActive,
        results = controller.results,
        thumbnails = controller.thumbnails,
        onView = controller::openResultEditor,
        onShare = controller::shareResult,
        onShareSelected = controller::shareResults,
        onRename = controller::renameResult,
        onDelete = controller::deleteResult,
        onDeleteSelected = controller::deleteResults,
        onRequestThumbnail = controller::loadThumbnail,
        onSelectionActionsChange = onSelectionActionsChange,
        sortOrder = sortOrder,
        modifier = modifier,
    )
}

private class ResultListController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val repository = CaptureResultRepository(context)
    private val exporter = LayeredImageExporter(context)
    private val resourceAccess = StorageResourceAccess(context)
    private val storageLocationPreferences = StorageLocationPreferences(context)
    private val thumbnailCache = LinkedHashMap<String, Bitmap>(32, 0.75f, true)
    private val thumbnailJobs = mutableMapOf<String, Job>()
    private val failedThumbnails = mutableSetOf<String>()
    private val resultVersions = mutableMapOf<String, Long>()
    private val resultIds = hashSetOf<String>()
    private val thumbnailSemaphore = Semaphore(THUMBNAIL_CONCURRENCY)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbnailWidth = ceil(THUMBNAIL_WIDTH_DP * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(THUMBNAIL_WIDTH_DP.toInt())
        .coerceAtMost(MAX_THUMBNAIL_WIDTH)
    private val thumbnailHeight = ceil(THUMBNAIL_HEIGHT_DP * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(THUMBNAIL_HEIGHT_DP.toInt())
        .coerceAtMost(MAX_THUMBNAIL_HEIGHT)
    private val maxThumbnailCacheBytes = run {
        val memoryClassMb = context.getSystemService(ActivityManager::class.java).memoryClass
        (memoryClassMb.toLong() * 1024L * 1024L / 32L)
            .coerceIn(MIN_THUMBNAIL_CACHE_BYTES, MAX_THUMBNAIL_CACHE_BYTES)
    }
    private val backgroundPrefetchCount = (
        maxThumbnailCacheBytes / (thumbnailWidth.toLong() * thumbnailHeight * 4L)
        ).toInt().coerceIn(MIN_BACKGROUND_PREFETCH, MAX_BACKGROUND_PREFETCH)
    private var thumbnailCacheBytes = 0L
    private var loadJob: Job? = null
    private var currentSortOrder = ContentSortOrder.MODIFIED_DESC
    private var closed = false

    var results by mutableStateOf<List<CaptureResultSummary>>(emptyList())
        private set
    val thumbnails = mutableStateMapOf<String, Bitmap?>()

    fun loadResults(sortOrder: ContentSortOrder = currentSortOrder) {
        if (closed) return
        if (sortOrder != currentSortOrder) {
            thumbnailJobs.values.forEach(Job::cancel)
            thumbnailJobs.clear()
        }
        currentSortOrder = sortOrder
        loadJob?.cancel()
        loadJob = scope.launch {
            val list = withContext(Dispatchers.IO) {
                sortResults(
                    repository.summaries(includeStoredSize = true),
                    sortOrder,
                )
            }
            if (closed) return@launch
            results = list
            val validIds = list.mapTo(hashSetOf()) { it.id }
            resultIds.clear()
            resultIds.addAll(validIds)
            val nextVersions = list.associate { it.id to it.updatedAt }
            val staleIds = (thumbnailCache.keys + thumbnailJobs.keys + failedThumbnails).filter { id ->
                id !in validIds || resultVersions[id] != nextVersions[id]
            }.toSet()
            staleIds.forEach(::removeCachedThumbnail)
            staleIds.forEach { id ->
                thumbnailJobs.remove(id)?.cancel()
            }
            failedThumbnails.removeAll(staleIds)
            resultVersions.clear()
            resultVersions.putAll(nextVersions)
            list.take(backgroundPrefetchCount).forEach { summary -> loadThumbnail(summary.id) }
        }
    }

    fun loadThumbnail(resultId: String) {
        if (closed) return
        thumbnailCache[resultId]?.let { cached ->
            if (thumbnails[resultId] !== cached) thumbnails[resultId] = cached
            return
        }
        if (resultId in thumbnailJobs || resultId in failedThumbnails) return
        if (resultId !in resultIds) return
        thumbnailJobs[resultId] = scope.launch {
            var pending: Bitmap? = null
            try {
                pending = thumbnailSemaphore.withPermit {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val result = repository.getById(resultId) ?: return@runCatching null
                            ResultThumbnailLoader.load(
                                resourceAccess = resourceAccess,
                                result = result,
                                targetWidth = thumbnailWidth,
                                targetHeight = thumbnailHeight,
                            )
                        }.getOrNull()
                    }
                }
                val bitmap = pending
                if (bitmap == null) {
                    failedThumbnails += resultId
                    return@launch
                }
                if (closed || resultId !in resultIds) return@launch
                putCachedThumbnail(resultId, bitmap)
                pending = null
            } finally {
                if (thumbnailJobs[resultId] === kotlin.coroutines.coroutineContext[Job]) {
                    thumbnailJobs.remove(resultId)
                }
                pending?.takeUnless(Bitmap::isRecycled)?.recycle()
            }
        }
    }

    fun openResultEditor(resultId: String) {
        context.startActivity(
            Intent(CaptureUiContract.ACTION_VIEW_RESULT)
                .setClassName(context, CaptureUiContract.RESULT_ACTIVITY_CLASS)
                .putExtra(CaptureUiContract.EXTRA_SESSION_ID, resultId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun shareResult(summary: CaptureResultSummary) = shareResults(listOf(summary))

    fun shareResults(summaries: List<CaptureResultSummary>) {
        val uniqueSummaries = summaries.distinctBy(CaptureResultSummary::id)
        if (uniqueSummaries.isEmpty()) return
        scope.launch {
            Toast.makeText(
                context,
                context.getString(R.string.result_share_preparing),
                Toast.LENGTH_SHORT,
            ).show()
            val publishedUris = mutableListOf<PublishedQuickShareUri>()
            val preparedShare = try {
                withContext(Dispatchers.IO) {
                    val exportJob = kotlin.coroutines.coroutineContext[Job]
                    val exportDestination = storageLocationPreferences.exportTreeUri
                    val prepared = uniqueSummaries.map { summary ->
                        val loaded = repository.getById(summary.id)
                            ?: error(context.getString(R.string.result_not_found))
                        val repaired = exporter.resolveFixedBandMetadata(loaded)
                        val fixedMetadataChanged = repaired.schemaVersion != loaded.schemaVersion ||
                            repaired.fixedLayers != loaded.fixedLayers
                        val result = if (fixedMetadataChanged) {
                            repository.updateAtomically(loaded.id) { latest ->
                                mergeFixedBandMetadataRepairIfCurrent(
                                    latest = latest,
                                    original = loaded,
                                    repaired = repaired,
                                )
                            } ?: repository.getById(loaded.id)
                                ?.let(exporter::resolveFixedBandMetadata)
                                ?: repaired
                        } else {
                            loaded
                        }
                        val plan = createQuickSharePlan(result)
                        plan.availability.unavailableReason?.let {
                            error(
                                context.localizedScrollExportUnavailableMessage(plan.availability)
                                    ?: context.getString(R.string.result_no_shareable_files),
                            )
                        }
                        check(plan.enabledRegions.isNotEmpty()) {
                            context.getString(R.string.result_no_shareable_files)
                        }
                        val baseName = defaultCaptureExportBaseName(plan.result)
                        val parts = plan.enabledRegions.mapIndexed { index, region ->
                            exporter.export(
                                result = plan.result.copy(
                                    selectedScrollStart = region.start,
                                    selectedScrollEnd = region.end,
                                ),
                                options = ExportOptions(
                                    displayName = plan.displayName(baseName, index),
                                    includeTop = plan.includeTop,
                                    includeBottom = plan.includeBottom,
                                    destinationTreeUri = exportDestination,
                                ),
                                isCancelled = { exportJob?.isActive == false },
                            ).also { part ->
                                part.uris.forEach { uri ->
                                    publishedUris += PublishedQuickShareUri(
                                        uri = uri,
                                        destinationTreeUri = exportDestination,
                                    )
                                }
                            }
                        }
                        val value = combineQuickShareExportParts(parts)
                        val record = value.toExportRecord(
                            result = plan.result,
                            selectedStart = plan.result.selectedScrollStart,
                            selectedEnd = plan.result.selectedScrollEnd,
                            includeTop = plan.includeTop,
                            includeBottom = plan.includeBottom,
                            destinationTreeUri = exportDestination,
                        )
                        PreparedQuickShareExport(
                            result = plan.result,
                            export = value,
                            record = record,
                        )
                    }
                    val exported = prepared.map(PreparedQuickShareExport::export)
                    val uris = exported.flatMap(ExportResult::uris)
                    check(uris.isNotEmpty()) {
                        context.getString(R.string.result_no_shareable_files)
                    }
                    val mimeTypes = exported.map { it.format.mimeType }.distinct()
                    val mimeType = mimeTypes.singleOrNull() ?: "image/*"
                    val intent = if (uris.size == 1) {
                        Intent(Intent.ACTION_SEND).apply {
                            type = mimeType
                            putExtra(Intent.EXTRA_STREAM, uris.single())
                            clipData = ClipData.newUri(
                                context.contentResolver,
                                "GameLongShot",
                                uris.single(),
                            )
                        }
                    } else {
                        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                            type = mimeType
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            clipData = ClipData.newUri(
                                context.contentResolver,
                                "GameLongShot",
                                uris.first(),
                            ).apply {
                                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                            }
                        }
                    }
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    PreparedQuickShareIntent(intent = intent, exports = prepared)
                }
            } catch (error: CancellationException) {
                rollbackPublishedQuickShareUris(publishedUris)
                throw error
            } catch (error: Throwable) {
                rollbackPublishedQuickShareUris(publishedUris)
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.result_share_prepare_failed,
                        context.localizedErrorOrFallback(error, R.string.result_error_unknown),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            try {
                context.startActivity(
                    Intent.createChooser(
                        preparedShare.intent,
                        context.getString(R.string.result_share_chooser_title),
                    )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: Throwable) {
                rollbackPublishedQuickShareUris(publishedUris)
                Toast.makeText(
                    context,
                    context.getString(R.string.result_no_share_apps),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            // A successful chooser launch transfers URI ownership to the receiving app.
            withContext(NonCancellable + Dispatchers.IO) {
                persistQuickShareExports(preparedShare.exports)
            }
        }
    }

    private fun createQuickSharePlan(result: LayeredCaptureResult): ResultQuickSharePlan {
        val horizontal = result.templateSnapshot?.scrollDirection?.isVertical == false
        val scrollGeometry = resolveScrollGeometry(result, horizontal)
            ?: error(context.getString(R.string.result_no_shareable_files))
        val firstFixedLayer = resolveFixedLayerGeometry(result, index = 0, horizontal = horizontal)
        val secondFixedLayer = resolveFixedLayerGeometry(result, index = 1, horizontal = horizontal)
        return buildResultQuickSharePlan(
            result = result,
            scrollWidth = scrollGeometry.width,
            scrollHeight = scrollGeometry.height,
            firstFixedLayer = firstFixedLayer,
            secondFixedLayer = secondFixedLayer,
        )
    }

    private fun resolveScrollGeometry(
        result: LayeredCaptureResult,
        horizontal: Boolean,
    ): QuickShareImageGeometry? {
        val chunks = result.scrollChunks
        if (chunks.isNotEmpty() && chunks.all { it.width > 0 && it.height > 0 }) {
            return if (horizontal) {
                QuickShareImageGeometry(
                    width = Math.toIntExact(chunks.sumOf { it.width.toLong() }),
                    height = chunks.first().height,
                )
            } else {
                QuickShareImageGeometry(
                    width = chunks.first().width,
                    height = Math.toIntExact(chunks.sumOf { it.height.toLong() }),
                )
            }
        }
        return resolveImageGeometry(result.scrollLongBitmapPath)
            ?: if (result.scrollContentWidth > 0 && result.scrollContentHeight > 0) {
                QuickShareImageGeometry(result.scrollContentWidth, result.scrollContentHeight)
            } else {
                null
            }
    }

    private fun resolveFixedLayerGeometry(
        result: LayeredCaptureResult,
        index: Int,
        horizontal: Boolean,
    ): QuickShareFixedLayerGeometry? {
        val storedLayer = result.fixedLayers.getOrNull(index)
        val path = storedLayer?.bitmapPath ?: when (index) {
            0 -> result.topFixedBitmapPath
            1 -> result.bottomFixedBitmapPath
            else -> null
        }
        val geometry = path?.let(::resolveImageGeometry) ?: return null
        val fallbackSide = when (index) {
            0 -> if (horizontal) FixedSide.LEFT else FixedSide.TOP
            1 -> if (horizontal) FixedSide.RIGHT else FixedSide.BOTTOM
            else -> return null
        }
        return QuickShareFixedLayerGeometry(
            side = storedLayer?.side ?: fallbackSide,
            width = geometry.width,
            height = geometry.height,
        )
    }

    private fun resolveImageGeometry(resource: String): QuickShareImageGeometry? {
        if (resource.isBlank()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resourceAccess.decodeBitmap(resource, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            QuickShareImageGeometry(options.outWidth, options.outHeight)
        } else {
            null
        }
    }

    private fun persistQuickShareExports(exports: List<PreparedQuickShareExport>) {
        exports.forEach { item ->
            runCatching {
                repository.updateAtomically(item.result.id) { latest ->
                    mergeQuickShareExportRecordIfCurrent(
                        latest = latest,
                        prepared = item.result,
                        record = item.record,
                    )
                }
            }
        }
    }

    private suspend fun rollbackPublishedQuickShareUris(
        publishedUris: MutableList<PublishedQuickShareUri>,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            deletePublishedQuickShareUris(publishedUris)
            publishedUris.clear()
        }
    }

    private fun deletePublishedQuickShareUris(publishedUris: List<PublishedQuickShareUri>) {
        publishedUris.asReversed().forEach { published ->
            runCatching {
                if (published.destinationTreeUri == null) {
                    context.contentResolver.delete(published.uri, null, null)
                } else {
                    DocumentsContract.deleteDocument(context.contentResolver, published.uri)
                }
            }
        }
    }

    fun renameResult(id: String, newName: String) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val result = repository.getById(id)
                        ?: error(context.getString(R.string.result_not_found))
                    repository.update(result.copy(recordName = newName))
                }
            }.onSuccess {
                loadResults()
                Toast.makeText(
                    context,
                    context.getString(R.string.result_rename_success),
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.result_rename_failed,
                        context.localizedErrorOrFallback(error, R.string.result_error_unknown),
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun deleteResult(id: String) = deleteResults(setOf(id))

    fun deleteResults(ids: Set<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { ids.count(repository::delete) }
            }
                .onSuccess { deletedCount ->
                    loadResults()
                    val message = if (deletedCount == ids.size) {
                        context.resources.getQuantityString(
                            R.plurals.result_records_deleted,
                            deletedCount,
                            deletedCount,
                        )
                    } else {
                        context.resources.getQuantityString(
                            R.plurals.result_records_deleted_partial,
                            deletedCount,
                            deletedCount,
                            ids.size - deletedCount,
                        )
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
                .onFailure { error ->
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.result_delete_failed,
                            context.localizedErrorOrFallback(error, R.string.result_error_unknown),
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    fun close() {
        closed = true
        loadJob?.cancel()
        thumbnailJobs.values.forEach(Job::cancel)
        thumbnailJobs.clear()
        failedThumbnails.clear()
        resultVersions.clear()
        resultIds.clear()
        thumbnails.clear()
        thumbnailCache.values.forEach(::recycleThumbnailLater)
        thumbnailCache.clear()
        thumbnailCacheBytes = 0L
    }

    private fun putCachedThumbnail(id: String, bitmap: Bitmap) {
        removeCachedThumbnail(id)
        val bytes = bitmap.allocationByteCount.toLong().coerceAtLeast(1L)
        while (thumbnailCache.isNotEmpty() && thumbnailCacheBytes + bytes > maxThumbnailCacheBytes) {
            removeCachedThumbnail(thumbnailCache.entries.iterator().next().key)
        }
        thumbnailCache[id] = bitmap
        thumbnailCacheBytes += bytes
        thumbnails[id] = bitmap
    }

    private fun removeCachedThumbnail(id: String) {
        val removed = thumbnailCache.remove(id) ?: return
        thumbnailCacheBytes = (thumbnailCacheBytes - removed.allocationByteCount).coerceAtLeast(0L)
        thumbnails.remove(id)
        recycleThumbnailLater(removed)
    }

    private fun recycleThumbnailLater(bitmap: Bitmap) {
        mainHandler.postDelayed({
            if (!bitmap.isRecycled && bitmap !in thumbnailCache.values) bitmap.recycle()
        }, BITMAP_RECYCLE_DELAY_MS)
    }

    private data class QuickShareImageGeometry(
        val width: Int,
        val height: Int,
    )

    private data class PublishedQuickShareUri(
        val uri: Uri,
        val destinationTreeUri: Uri?,
    )

    private data class PreparedQuickShareExport(
        val result: LayeredCaptureResult,
        val export: ExportResult,
        val record: ResultExportRecord,
    )

    private data class PreparedQuickShareIntent(
        val intent: Intent,
        val exports: List<PreparedQuickShareExport>,
    )

    companion object {
        private const val THUMBNAIL_WIDTH_DP = 56f
        private const val THUMBNAIL_HEIGHT_DP = 40f
        private const val MAX_THUMBNAIL_WIDTH = 336
        private const val MAX_THUMBNAIL_HEIGHT = 240
        private const val THUMBNAIL_CONCURRENCY = 1
        private const val MIN_BACKGROUND_PREFETCH = 8
        private const val MAX_BACKGROUND_PREFETCH = 24
        private const val MIN_THUMBNAIL_CACHE_BYTES = 4L * 1024L * 1024L
        private const val MAX_THUMBNAIL_CACHE_BYTES = 12L * 1024L * 1024L
        private const val BITMAP_RECYCLE_DELAY_MS = 1_000L
    }
}
