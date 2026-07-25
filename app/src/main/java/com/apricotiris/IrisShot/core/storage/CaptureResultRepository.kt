package com.apricotiris.IrisShot.core.storage

import android.content.Context
import android.net.Uri
import com.apricotiris.IrisShot.R
import com.apricotiris.IrisShot.core.app.ApplicationLabelResolver
import com.apricotiris.IrisShot.core.model.CapturedFrame
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import java.util.UUID

data class CaptureResultSummary(
    val id: String,
    val sessionId: String,
    val schemaVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val frameCount: Int,
    val scrollLongBitmapPath: String,
    val templateName: String?,
    val appName: String,
    val recordName: String?,
    val storedSizeBytes: Long = 0L,
)

/** Resolves the persisted image backing one source frame in capture order. */
internal fun sourceFrameStorageResource(
    result: LayeredCaptureResult,
    sortedFrames: List<CapturedFrame>,
    position: Int,
): String? {
    val frame = sortedFrames.getOrNull(position) ?: return null
    frame.scrollBitmapPath.takeIf(String::isNotBlank)?.let { return it }

    val chunks = result.scrollChunks
    if (chunks.size != sortedFrames.size) return null
    val direction = result.templateSnapshot?.scrollDirection ?: return null
    val chunkPosition = if (direction.isForward) position else chunks.lastIndex - position
    return chunks.getOrNull(chunkPosition)?.path?.takeIf(String::isNotBlank)
}

internal fun sourceFrameStoredSizeBytes(
    result: LayeredCaptureResult,
    resourceLength: (String) -> Long?,
): Long {
    return sourceFrameStorageResources(result)
        .fold(0L) { total, resource ->
            val length = resourceLength(resource)?.coerceAtLeast(0L) ?: 0L
            if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
        }
}

internal fun sourceFrameStorageResources(result: LayeredCaptureResult): List<String> {
    val sortedFrames = result.sourceFrames.sortedBy { it.index }
    return sortedFrames.indices
        .mapNotNull { position -> sourceFrameStorageResource(result, sortedFrames, position) }
        .distinct()
}

/** Repository facade used by capture services and result editing. */
class CaptureResultRepository(
    private val context: Context,
    val fileStore: SessionFileStore = SessionFileStore(context)
) {
    private val applicationLabelResolver = ApplicationLabelResolver(context)
    private val storageLocationPreferences = StorageLocationPreferences(context)

    @Synchronized
    fun save(result: LayeredCaptureResult): LayeredCaptureResult {
        val now = System.currentTimeMillis()
        val id = result.id.ifBlank { UUID.randomUUID().toString() }
        val sessionId = result.sessionId.ifBlank { id }
        val persisted = result.copy(
            id = id,
            sessionId = sessionId,
            createdAt = result.createdAt.takeIf { it > 0L } ?: now,
            updatedAt = now
        )
        fileStore.saveResultMetadata(persisted)
        return persisted
    }

    @Synchronized
    fun update(result: LayeredCaptureResult): LayeredCaptureResult {
        require(result.id.isNotBlank()) { "更新结果前必须先保存" }
        val directSessionId = result.sessionId.ifBlank { result.id }
        val previous = fileStore.loadResultMetadata(directSessionId)
            ?: getById(result.id)
            ?: error("结果不存在: ${result.id}")
        val updated = result.copy(
            sessionId = result.sessionId.ifBlank { previous.sessionId },
            createdAt = previous.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        fileStore.saveResultMetadata(updated)
        val previousExportDestination = previous.lastExport?.destinationTreeUri
        if (previousExportDestination != updated.lastExport?.destinationTreeUri) {
            releaseStoredExportPermissionIfUnreferenced(previousExportDestination)
        }
        return updated
    }

    /** Atomically applies [transform] to the latest on-disk value, or leaves it unchanged. */
    fun updateAtomically(
        id: String,
        transform: (LayeredCaptureResult) -> LayeredCaptureResult?,
    ): LayeredCaptureResult? {
        var previous: LayeredCaptureResult? = null
        val updated = fileStore.updateResultMetadataAtomically(id) { current ->
            previous = current
            transform(current)?.copy(
                sessionId = current.sessionId,
                createdAt = current.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
        } ?: return null
        val previousExportDestination = previous?.lastExport?.destinationTreeUri
        if (previousExportDestination != updated.lastExport?.destinationTreeUri) {
            releaseStoredExportPermissionIfUnreferenced(previousExportDestination)
        }
        return updated
    }

    @Synchronized
    fun getById(id: String): LayeredCaptureResult? =
        fileStore.loadResultMetadata(id)
            ?: fileStore.listResults().firstOrNull { it.id == id || it.sessionId == id }

    fun load(id: String): LayeredCaptureResult? = getById(id)

    @Synchronized
    fun getAll(): List<LayeredCaptureResult> = fileStore.listResults()

    fun list(): List<LayeredCaptureResult> = getAll()

    @Synchronized
    fun summaries(includeStoredSize: Boolean = false): List<CaptureResultSummary> {
        val results = getAll()
        val frameSizes = if (includeStoredSize) {
            fileStore.sourceFrameSizesBytes(results)
        } else {
            emptyMap()
        }
        return results.map { result ->
            val snapshot = result.templateSnapshot
            val legacyPackageName = snapshot?.packageName
            val legacyLabel = legacyPackageName
                ?.takeIf(String::isNotBlank)
                ?.let(::resolveApplicationLabel)
            CaptureResultSummary(
                id = result.id,
                sessionId = result.sessionId,
                schemaVersion = result.schemaVersion,
                createdAt = result.createdAt,
                updatedAt = result.updatedAt,
                frameCount = result.sourceFrames.size,
                scrollLongBitmapPath = result.scrollLongBitmapPath,
                templateName = snapshot?.templateName,
                appName = chooseResultAppName(
                    storedAppName = result.appName,
                    templateAppName = snapshot?.appName,
                    packageName = legacyPackageName,
                    resolvedLegacyLabel = legacyLabel,
                    fallbackName = context.getString(R.string.common_unknown_app),
                ),
                recordName = result.recordName,
                storedSizeBytes = frameSizes[result.sessionId.ifBlank { result.id }] ?: 0L,
            )
        }
    }

    private fun resolveApplicationLabel(packageName: String): String? =
        applicationLabelResolver.resolve(packageName)

    @Synchronized
    fun delete(id: String): Boolean {
        val result = getById(id) ?: return false
        val deleted = fileStore.deleteSession(result.sessionId.ifBlank { result.id })
        if (deleted) {
            releaseStoredExportPermissionIfUnreferenced(result.lastExport?.destinationTreeUri)
        }
        return deleted
    }

    private fun releaseStoredExportPermissionIfUnreferenced(uriString: String?) {
        val uri = uriString
            ?.takeIf(String::isNotBlank)
            ?.let(Uri::parse)
            ?.takeIf { it.scheme == "content" }
            ?: return
        runCatching { releaseExportPermissionIfUnreferenced(uri) }
    }

    @Synchronized
    fun releaseExportPermissionIfUnreferenced(uri: Uri?) {
        if (uri?.scheme != "content") return
        val stillReferenced = fileStore.listResults().any { result ->
            result.lastExport?.destinationTreeUri
                ?.let(Uri::parse) == uri
        }
        if (!stillReferenced) storageLocationPreferences.releasePermissionIfUnused(uri)
    }

}

internal fun chooseResultAppName(
    storedAppName: String?,
    templateAppName: String?,
    packageName: String?,
    resolvedLegacyLabel: String?,
    fallbackName: String,
): String = sequenceOf(storedAppName, templateAppName, resolvedLegacyLabel)
    .mapNotNull { candidate ->
        candidate?.trim()?.takeIf { it.isNotEmpty() && it != packageName }
    }
    .firstOrNull()
    ?: fallbackName
