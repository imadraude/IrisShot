package com.apricotiris.IrisShot.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.apricotiris.IrisShot.core.export.StreamingPngEncoder
import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import com.apricotiris.IrisShot.core.model.ModelJsonCodec
import com.apricotiris.IrisShot.core.model.ScrollChunk
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File

data class StorageMigrationResult(
    val migratedSessions: Int,
    val source: String,
    val destination: String,
    val sourceCleanupComplete: Boolean,
    val isSuccess: Boolean = true,
    val issue: StorageMigrationIssue? = null,
)

enum class StorageMigrationIssue {
    SOURCE_UNAVAILABLE_DEFAULT_RESTORED,
    SOURCE_CLEANUP_INCOMPLETE,
}

internal fun storageResourceLengthsMatch(
    sourceReportedLength: Long,
    sourceResource: String,
    copiedReportedLength: Long,
    copiedResource: String,
    fallbackLength: (String) -> Long?,
): Boolean {
    val sourceLength = sourceReportedLength.takeIf { it >= 0L }
        ?: fallbackLength(sourceResource)?.takeIf { it >= 0L }
    val copiedLength = copiedReportedLength.takeIf { it >= 0L }
        ?: fallbackLength(copiedResource)?.takeIf { it >= 0L }
    return sourceLength == null || copiedLength == null || sourceLength == copiedLength
}

/** Stores capture sessions in external cache by default or in a user-authorized SAF tree. */
class SessionFileStore(
    context: Context,
    rootName: String? = null,
    private val locationPreferences: StorageLocationPreferences = StorageLocationPreferences(context),
) {
    private val appContext = context.applicationContext
    val resourceAccess = StorageResourceAccess(appContext)
    private val rootNameOverride = rootName?.takeIf(String::isNotBlank)?.let(::safeSegment)

    @Volatile
    private var backendState: StorageTreeBackend = configuredBackend()

    private var backend: StorageTreeBackend
        get() {
            val current = backendState
            if (current.identity == configuredBackendIdentity()) return current
            return synchronized(backendRefreshLock) {
                val latest = backendState
                if (latest.identity == configuredBackendIdentity()) latest else configuredBackend().also {
                    backendState = it
                }
            }
        }
        set(value) {
            backendState = value
        }

    private val backendRefreshLock = Any()
    @Volatile private var migrationEnsured = false

    /** Returns the default file root, or null when a SAF tree is selected. Does not create it. */
    fun rootDirectory(): File? = backend.rootFile

    /** Creates a timestamp-named session, adding _2, _3, ... only for same-second collisions. */
    fun createSession(): String = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val current = backend
        val name = allocateSessionName(current, System.currentTimeMillis())
        createOwnedSession(name)
        registerActiveSession(activeSessionKey(current, name))
        name
    }

    /** Compatibility entry point for callers that restore an already-known session name. */
    fun createSession(sessionId: String): String = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val safeId = safeSegment(sessionId)
        createOwnedSession(safeId)
        registerActiveSession(activeSessionKey(backend, safeId))
        safeId
    }

    fun sessionSizeBytes(sessionId: String): Long =
        sessionSizesBytes(listOf(sessionId))[sessionId] ?: 0L

    fun sessionSizesBytes(sessionIds: Collection<String>): Map<String, Long> = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val current = backend
        sessionIds.distinct().associateWith { sessionId ->
            val safeId = safeSegment(sessionId)
            if (current.find(safeId)?.isDirectory == true) directorySizeBytes(current, safeId) else 0L
        }
    }

    fun sourceFrameSizesBytes(
        results: Collection<LayeredCaptureResult>,
    ): Map<String, Long> = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val current = backend
        results.associate { result ->
            val sessionId = result.sessionId.ifBlank { result.id }
            val lengths = sourceFrameResourceLengths(current, result)
            sessionId to sourceFrameStoredSizeBytes(result, lengths::get)
        }
    }

    fun sourceFrameResourceLengths(result: LayeredCaptureResult): Map<String, Long> =
        synchronized(STORAGE_OPERATION_LOCK) {
            ensureMigrated()
            sourceFrameResourceLengths(backend, result)
        }

    private fun sourceFrameResourceLengths(
        current: StorageTreeBackend,
        result: LayeredCaptureResult,
    ): Map<String, Long> {
        val resources = sourceFrameStorageResources(result).toMutableSet()
        if (resources.isEmpty()) return emptyMap()
        val sessionId = result.sessionId.ifBlank { result.id }
        val safeId = safeSegment(sessionId)
        if (current.find(safeId)?.isDirectory != true) return emptyMap()

        return mutableMapOf<String, Long>().also { lengths ->
            collectResourceLengths(
                current = current,
                relativePath = safeId,
                remainingResources = resources,
                lengths = lengths,
            )
        }
    }

    private fun directorySizeBytes(current: StorageTreeBackend, relativePath: String): Long =
        current.listChildren(relativePath).fold(0L) { total, entry ->
            val length = if (entry.isDirectory) {
                directorySizeBytes(current, entry.relativePath)
            } else {
                entry.length.coerceAtLeast(0L)
            }
            if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
        }

    private fun collectResourceLengths(
        current: StorageTreeBackend,
        relativePath: String,
        remainingResources: MutableSet<String>,
        lengths: MutableMap<String, Long>,
    ) {
        if (remainingResources.isEmpty()) return
        current.listChildren(relativePath).forEach { entry ->
            if (remainingResources.isEmpty()) return
            if (entry.isDirectory) {
                collectResourceLengths(
                    current = current,
                    relativePath = entry.relativePath,
                    remainingResources = remainingResources,
                    lengths = lengths,
                )
            } else if (remainingResources.remove(entry.resource)) {
                lengths[entry.resource] = entry.length
                    .takeIf { it >= 0L }
                    ?: resourceAccess.length(entry.resource)
                    ?: 0L
            }
        }
    }

    fun saveBitmap(
        sessionId: String,
        fileName: String,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100,
    ): String {
        ensureMigrated()
        val actualName = ensureExtension(safeSegment(File(fileName).name), format)
        return saveBitmapAt(sessionId, actualName, bitmap, format, quality)
    }

    /** Saves reusable middle-layer pixels independently from the user's eventual export choice. */
    fun saveScrollResult(
        sessionId: String,
        bitmap: Bitmap,
        fileName: String = "scroll_long",
    ): String {
        ensureMigrated()
        ensureOwnedSession(sessionId, createIfMissing = true)
        val actualName = internalScrollSourceFileName(safeSegment(File(fileName).name))
        val target = sessionPath(sessionId, actualName)
        return backend.write(target, INTERNAL_SCROLL_SOURCE_MIME_TYPE) { output ->
            writePng(bitmap, output)
        }.resource
    }

    fun saveScrollChunk(sessionId: String, chunkIndex: Int, bitmap: Bitmap): ScrollChunk {
        ensureMigrated()
        require(chunkIndex >= 0)
        ensureOwnedSession(sessionId, createIfMissing = true)
        val target = sessionPath(
            sessionId,
            "$SCROLL_CHUNKS_DIRECTORY/chunk_${chunkIndex.toString().padStart(4, '0')}.png",
        )
        val path = backend.write(target, "image/png") { output -> writePng(bitmap, output) }.resource
        return ScrollChunk(path = path, width = bitmap.width, height = bitmap.height)
    }

    fun saveResultMetadata(result: LayeredCaptureResult): String = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val sessionId = result.sessionId.ifBlank { result.id }
        require(sessionId.isNotBlank()) { "结果必须包含 id 或 sessionId" }
        ensureOwnedSession(sessionId, createIfMissing = true)
        val current = backend
        check(validateResultResources(result, current)) { "结果资源不属于当前缓存目录或已经丢失" }
        val resource = writeResult(current, safeSegment(sessionId), result).resource
        unregisterActiveSession(activeSessionKey(current, sessionId))
        resource
    }

    /** Reads, conditionally transforms, and writes one result under the process-wide storage lock. */
    fun updateResultMetadataAtomically(
        resultId: String,
        transform: (LayeredCaptureResult) -> LayeredCaptureResult?,
    ): LayeredCaptureResult? = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val currentBackend = backend
        val safeId = safeSegment(resultId)
        val current = readResult(currentBackend, safeId)
            ?.takeIf(::isValidCaptureResult)
            ?: listResults(currentBackend).firstOrNull { result ->
                result.id == resultId || result.sessionId == resultId
            }
            ?: return@synchronized null
        val updated = transform(current) ?: return@synchronized null
        saveResultMetadata(updated)
        updated
    }

    @Synchronized
    fun loadResultMetadata(sessionId: String): LayeredCaptureResult? {
        ensureMigrated()
        return readResult(backend, safeSegment(sessionId))?.takeIf(::isValidCaptureResult)
    }

    @Synchronized
    fun listResults(): List<LayeredCaptureResult> {
        ensureMigrated()
        return listResults(backend)
    }

    fun listSessionIds(): List<String> {
        ensureMigrated()
        val current = backend
        return current.listChildren()
            .filter(StorageEntry::isDirectory)
            .filter { readResult(current, it.name)?.let(::isValidCaptureResult) == true }
            .map(StorageEntry::name)
    }

    fun deleteSession(sessionId: String): Boolean = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val safeId = safeSegment(sessionId)
        val current = backend
        ACTIVE_SESSION_COUNTS.remove(activeSessionKey(current, safeId))
        val directory = current.find(safeId)?.takeIf(StorageEntry::isDirectory)
            ?: return@synchronized false
        if (!isOwnedSessionDirectory(current, directory)) return@synchronized false
        current.delete(safeId)
    }

    /** Marks a failed, intentionally retained session as inactive so later cleanup can manage it. */
    fun releaseRetainedSession(sessionId: String) = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        unregisterActiveSession(activeSessionKey(backend, safeSegment(sessionId)))
    }

    /** Deletes only a regular file found inside the specified owned capture session. */
    fun deleteOwnedFile(path: String?, sessionId: String): Boolean = synchronized(STORAGE_OPERATION_LOCK) {
        ensureMigrated()
        val current = backend
        if (path.isNullOrBlank() || !current.owns(path)) return@synchronized false
        val safeId = safeSegment(sessionId)
        val session = current.find(safeId)?.takeIf(StorageEntry::isDirectory)
            ?: return@synchronized false
        if (!isOwnedSessionDirectory(current, session)) return@synchronized false
        val entry = findResourceInDirectory(current, session.relativePath, path)
            ?.takeUnless(StorageEntry::isDirectory)
            ?: return@synchronized false
        current.delete(entry.relativePath)
    }

    /**
     * Transactionally copies all current sessions to a new tree (or back to external cache).
     * The preference changes only after every copied session has been verified; failures throw.
     */
    fun changeCacheLocation(treeUri: Uri?, displayName: String? = null): StorageMigrationResult =
        synchronized(STORAGE_OPERATION_LOCK) {
            ensureMigrated()
            require(rootNameOverride == null) { "测试缓存根不能切换存储位置" }
            check(ACTIVE_SESSION_COUNTS.isEmpty()) { "截图任务进行中，不能切换缓存位置" }
            val sourceBackend = backend
            val sourceTreeUri = locationPreferences.cacheTreeUri
            val targetBackend = backendFor(treeUri)
            if (sourceBackend.identity == targetBackend.identity) {
                locationPreferences.setCacheLocation(treeUri, displayName)
                return@synchronized StorageMigrationResult(
                    0,
                    sourceBackend.identity,
                    targetBackend.identity,
                    true,
                )
            }
            if (storageTreesOverlap(sourceBackend, targetBackend)) {
                locationPreferences.releasePermissionIfUnused(treeUri)
                error("新缓存目录不能位于当前缓存目录内部，也不能包含当前缓存目录")
            }

            val sourceSessions = try {
                sourceBackend.listChildren()
                    .filter(StorageEntry::isDirectory)
                    .mapNotNull { directory ->
                        readResult(sourceBackend, directory.name)
                            ?.takeIf(::isValidCaptureResult)
                            ?.let { result -> directory to result }
                    }
            } catch (throwable: Throwable) {
                locationPreferences.releasePermissionIfUnused(treeUri)
                if (treeUri == null && sourceTreeUri != null) {
                    locationPreferences.setCacheLocation(
                        uri = null,
                        displayName = null,
                        releasePreviousPermission = false,
                    )
                    backend = targetBackend
                    return@synchronized StorageMigrationResult(
                        migratedSessions = 0,
                        source = sourceBackend.identity,
                        destination = targetBackend.identity,
                        sourceCleanupComplete = false,
                        isSuccess = false,
                        issue = StorageMigrationIssue.SOURCE_UNAVAILABLE_DEFAULT_RESTORED,
                    )
                }
                throw throwable
            }
            val copiedTargets = mutableListOf<String>()
            try {
                ensureCacheRootReady(targetBackend)
                sourceSessions.forEach { (sourceSession, sourceResult) ->
                    val timestamp = sourceResult.createdAt.takeIf { it > 0L }
                        ?: sourceSession.lastModified.takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                    val preferredName = sourceSession.name.takeIf(::isTimestampSessionDirectoryName)
                        ?: formatSessionDirectoryName(timestamp)
                    val targetName = nextAvailableSessionDirectoryName(preferredName) { candidate ->
                        targetBackend.find(candidate) != null
                    }
                    copiedTargets += targetName
                    copySession(
                        sourceBackend = sourceBackend,
                        sourceSession = sourceSession.name,
                        targetBackend = targetBackend,
                        targetSession = targetName,
                        sourceResult = sourceResult,
                    )
                }
            } catch (throwable: Throwable) {
                copiedTargets.forEach { runCatching { targetBackend.delete(it) } }
                locationPreferences.releasePermissionIfUnused(treeUri)
                throw throwable
            }

            try {
                locationPreferences.setCacheLocation(
                    uri = treeUri,
                    displayName = displayName,
                    releasePreviousPermission = false,
                )
            } catch (throwable: Throwable) {
                if (throwable !is StorageLocationPersistenceException) {
                    copiedTargets.forEach { runCatching { targetBackend.delete(it) } }
                    locationPreferences.releasePermissionIfUnused(treeUri)
                }
                throw throwable
            }
            backend = targetBackend
            val cleanupComplete = sourceSessions.map { (directory, _) ->
                runCatching { sourceBackend.delete(directory.name) }.getOrDefault(false)
            }.all { it }
            if (cleanupComplete) locationPreferences.releasePermissionIfUnused(sourceTreeUri)
            StorageMigrationResult(
                migratedSessions = sourceSessions.size,
                source = sourceBackend.identity,
                destination = targetBackend.identity,
                sourceCleanupComplete = cleanupComplete,
                isSuccess = cleanupComplete,
                issue = if (cleanupComplete) null else {
                    StorageMigrationIssue.SOURCE_CLEANUP_INCOMPLETE
                },
            )
        }

    private fun configuredBackend(): StorageTreeBackend = rootNameOverride?.let { rootName ->
        FileStorageTreeBackend(File(StorageLocationPreferences.defaultCacheDirectory(appContext), rootName))
    } ?: backendFor(locationPreferences.cacheTreeUri)

    private fun configuredBackendIdentity(): String = rootNameOverride?.let { rootName ->
        val root = File(StorageLocationPreferences.defaultCacheDirectory(appContext), rootName)
        runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    } ?: locationPreferences.cacheTreeUri?.toString()
        ?: run {
            val root = StorageLocationPreferences.defaultCacheDirectory(appContext)
            runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
        }

    private fun backendFor(treeUri: Uri?): StorageTreeBackend = if (treeUri != null) {
        require(rootNameOverride == null) { "自定义 rootName 不能与 SAF 缓存目录同时使用" }
        SafStorageTreeBackend(appContext, treeUri)
    } else {
        val defaultRoot = StorageLocationPreferences.defaultCacheDirectory(appContext)
        FileStorageTreeBackend(rootNameOverride?.let { File(defaultRoot, it) } ?: defaultRoot)
    }

    /** Lazily runs the one-time private-files migration at the first real repository I/O. */
    fun ensureMigrated() {
        if (rootNameOverride != null || migrationEnsured) return
        synchronized(LEGACY_MIGRATION_LOCK) {
            if (migrationEnsured) return
            val state = appContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(MIGRATION_PREFS_NAME, Context.MODE_PRIVATE)
            if (state.getInt(KEY_MIGRATION_VERSION, 0) >= LEGACY_MIGRATION_VERSION) {
                migrationEnsured = true
                return
            }
            val legacyRoot = File(appContext.filesDir, LEGACY_ROOT_NAME)
            if (!legacyRoot.exists()) {
                migrationEnsured = markLegacyMigrationComplete(state)
                return
            }
            if (!legacyRoot.isDirectory) {
                Log.w(TAG, "旧截图路径不是文件夹，将在后续操作中重试: ${legacyRoot.absolutePath}")
                return
            }
            val legacyEntries = legacyRoot.listFiles()
            if (legacyEntries == null) {
                Log.w(TAG, "无法枚举旧截图目录，将在后续操作中重试: ${legacyRoot.absolutePath}")
                return
            }
            val legacySessions = legacyEntries.filter(File::isDirectory)
            if (legacySessions.isEmpty()) {
                if (legacyEntries.isEmpty() && legacyRoot.delete()) {
                    migrationEnsured = markLegacyMigrationComplete(state)
                }
                return
            }

            val targetBackend = backend
            ensureCacheRootReady(targetBackend)
            var allMigrated = true
            legacySessions.forEach { sourceSession ->
                val migrated = runCatching {
                    migrateLegacySession(sourceSession, targetBackend)
                }.onFailure { error ->
                    Log.w(TAG, "旧截图迁移失败，将在下次启动重试: ${sourceSession.name}", error)
                }.getOrDefault(false)
                if (!migrated) allMigrated = false
            }
            if (allMigrated) {
                val cleanupComplete = !legacyRoot.exists() || legacyRoot.deleteRecursively()
                if (cleanupComplete) {
                    migrationEnsured = markLegacyMigrationComplete(state)
                }
            }
        }
    }

    private fun markLegacyMigrationComplete(state: android.content.SharedPreferences): Boolean =
        state.edit()
            .putInt(KEY_MIGRATION_VERSION, LEGACY_MIGRATION_VERSION)
            .commit()

    private fun migrateLegacySession(sourceSession: File, targetBackend: StorageTreeBackend): Boolean {
        val metadata = File(sourceSession, RESULT_METADATA_FILE)
        if (!metadata.isFile) return false
        val sourceResult = runCatching {
            ModelJsonCodec.resultFromJson(JSONObject(metadata.readText(Charsets.UTF_8)))
        }.getOrNull()?.takeIf(::isValidCaptureResult) ?: return false

        val alreadyMigrated = sourceResult.id.takeIf(String::isNotBlank)?.let { sourceId ->
            listResults(targetBackend).firstOrNull { it.id == sourceId }
        }
        if (alreadyMigrated != null && validateResultResources(alreadyMigrated, targetBackend)) {
            return sourceSession.deleteRecursively()
        }

        ensureCacheRootReady(targetBackend)
        val timestamp = sourceResult.createdAt.takeIf { it > 0L }
            ?: metadata.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val targetSession = allocateSessionName(targetBackend, timestamp)
        val sourceBackend = FileStorageTreeBackend(checkNotNull(sourceSession.parentFile))
        return try {
            copySession(
                sourceBackend = sourceBackend,
                sourceSession = sourceSession.name,
                targetBackend = targetBackend,
                targetSession = targetSession,
                sourceResult = sourceResult,
            )
            sourceSession.deleteRecursively()
        } catch (throwable: Throwable) {
            targetBackend.delete(targetSession)
            throw throwable
        }
    }

    private fun copySession(
        sourceBackend: StorageTreeBackend,
        sourceSession: String,
        targetBackend: StorageTreeBackend,
        targetSession: String,
        sourceResult: LayeredCaptureResult?,
    ) {
        targetBackend.ensureDirectory(targetSession)
        targetBackend.write(
            joinRelative(targetSession, SESSION_MARKER_FILE),
            "text/plain",
        ) { output -> output.write(SESSION_MARKER_CONTENT) }
        val resourceMap = linkedMapOf<String, String>()

        fun copyDirectory(sourceRelative: String, targetRelative: String) {
            targetBackend.ensureDirectory(targetRelative)
            sourceBackend.listChildren(sourceRelative).forEach { child ->
                val destination = joinRelative(targetRelative, child.name)
                if (child.isDirectory) {
                    copyDirectory(child.relativePath, destination)
                } else if (child.name != RESULT_METADATA_FILE && child.name !in SESSION_MARKER_FILES) {
                    val copied = targetBackend.write(destination, mimeTypeFor(child.name)) { output ->
                        checkNotNull(resourceAccess.openInputStream(child.resource)) {
                            "无法读取缓存文件: ${child.name}"
                        }.use { input -> BufferedInputStream(input).use { it.copyTo(output) } }
                    }
                    check(
                        storageResourceLengthsMatch(
                            sourceReportedLength = child.length,
                            sourceResource = child.resource,
                            copiedReportedLength = copied.length,
                            copiedResource = copied.resource,
                            fallbackLength = resourceAccess::length,
                        ),
                    ) {
                        "缓存文件复制不完整: ${child.name}"
                    }
                    resourceMap[child.resource] = copied.resource
                }
            }
        }

        copyDirectory(sourceSession, targetSession)
        if (sourceResult == null) return

        val rewritten = rewriteResultPaths(sourceResult, targetBackend, targetSession, resourceMap::get)
        writeResult(targetBackend, targetSession, rewritten)
        val verified = readResult(targetBackend, targetSession)
        check(verified != null && verified.id == rewritten.id &&
            verified.sessionId == targetSession && validateResultResources(verified, targetBackend)) {
            "迁移后的 result.json 校验失败: $targetSession"
        }
    }

    private fun rewriteResultPaths(
        result: LayeredCaptureResult,
        targetBackend: StorageTreeBackend,
        targetSession: String,
        directMapper: (String) -> String?,
    ): LayeredCaptureResult {
        fun map(path: String?): String? {
            if (path.isNullOrBlank()) return path
            directMapper(path)?.let { return it }
            val relativeSuffix = path.substringAfterLast("/$LEGACY_ROOT_NAME/", "")
                .substringAfter('/', "")
                .takeIf(String::isNotBlank)
                ?: return path
            return targetBackend.find(joinRelative(targetSession, relativeSuffix))?.resource ?: path
        }

        return result.copy(
            sessionId = targetSession,
            topFixedBitmapPath = map(result.topFixedBitmapPath),
            fixedLayers = result.fixedLayers.map { layer ->
                layer.copy(bitmapPath = map(layer.bitmapPath).orEmpty())
            },
            scrollLongBitmapPath = map(result.scrollLongBitmapPath).orEmpty(),
            thumbnailBitmapPath = map(result.thumbnailBitmapPath),
            scrollChunks = result.scrollChunks.map { chunk ->
                chunk.copy(path = map(chunk.path).orEmpty())
            },
            bottomFixedBitmapPath = map(result.bottomFixedBitmapPath),
            sourceFrames = result.sourceFrames.map { frame ->
                frame.copy(
                    fullBitmapPath = map(frame.fullBitmapPath),
                    scrollBitmapPath = map(frame.scrollBitmapPath).orEmpty(),
                    analysisBitmapPath = map(frame.analysisBitmapPath),
                )
            },
        )
    }

    private fun validateResultResources(
        result: LayeredCaptureResult,
        owner: StorageTreeBackend,
    ): Boolean {
        val hasScrollContent = result.scrollLongBitmapPath.isNotBlank() || result.scrollChunks.isNotEmpty()
        return hasScrollContent && resultResourcePaths(result).all { resource ->
            owner.owns(resource) && resourceAccess.exists(resource)
        }
    }

    private fun resultResourcePaths(result: LayeredCaptureResult): Sequence<String> = sequence {
        result.topFixedBitmapPath?.takeIf(String::isNotBlank)?.let { yield(it) }
        result.fixedLayers.map { it.bitmapPath }.filter(String::isNotBlank).forEach { yield(it) }
        result.scrollLongBitmapPath.takeIf(String::isNotBlank)?.let { yield(it) }
        result.scrollChunks.map(ScrollChunk::path).filter(String::isNotBlank).forEach { yield(it) }
        result.bottomFixedBitmapPath?.takeIf(String::isNotBlank)?.let { yield(it) }
        result.sourceFrames.forEach { frame ->
            frame.fullBitmapPath?.takeIf(String::isNotBlank)?.let { yield(it) }
            frame.scrollBitmapPath.takeIf(String::isNotBlank)?.let { yield(it) }
            frame.analysisBitmapPath?.takeIf(String::isNotBlank)?.let { yield(it) }
        }
    }

    private fun writeResult(
        targetBackend: StorageTreeBackend,
        sessionId: String,
        result: LayeredCaptureResult,
    ): StorageEntry {
        val bytes = ModelJsonCodec.resultToJson(result).toString(2).toByteArray(Charsets.UTF_8)
        return targetBackend.write(sessionPath(sessionId, RESULT_METADATA_FILE), "application/json") { output ->
            output.write(bytes)
        }
    }

    private fun readResult(sourceBackend: StorageTreeBackend, sessionId: String): LayeredCaptureResult? {
        val entry = sourceBackend.find(sessionPath(sessionId, RESULT_METADATA_FILE)) ?: return null
        if (entry.isDirectory) return null
        val result = runCatching {
            checkNotNull(resourceAccess.openInputStream(entry.resource)).bufferedReader(Charsets.UTF_8).use {
                ModelJsonCodec.resultFromJson(JSONObject(it.readText()))
            }
        }.getOrNull() ?: return null
        val storedSessionId = result.sessionId.ifBlank { result.id }
        return result.takeIf { storedSessionId == sessionId }
    }

    private fun listResults(sourceBackend: StorageTreeBackend): List<LayeredCaptureResult> =
        sourceBackend.listChildren()
            .asSequence()
            .filter(StorageEntry::isDirectory)
            .mapNotNull { readResult(sourceBackend, it.name) }
            .filter(::isValidCaptureResult)
            .sortedByDescending(LayeredCaptureResult::updatedAt)
            .toList()

    private fun allocateSessionName(targetBackend: StorageTreeBackend, timestamp: Long): String =
        nextAvailableSessionDirectoryName(formatSessionDirectoryName(timestamp)) { candidate ->
            targetBackend.find(candidate) != null
        }

    private fun sessionPath(sessionId: String, relativePath: String): String =
        joinRelative(safeSegment(sessionId), sanitizeNestedPath(relativePath))

    private fun saveBitmapAt(
        sessionId: String,
        relativeName: String,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
    ): String {
        require(!bitmap.isRecycled) { "Bitmap 已释放" }
        ensureOwnedSession(sessionId, createIfMissing = true)
        val safeRelativeName = sanitizeNestedPath(relativeName)
        return backend.write(
            sessionPath(sessionId, safeRelativeName),
            mimeTypeFor(safeRelativeName),
        ) { output ->
            check(bitmap.compress(format, quality.coerceIn(0, 100), output)) { "Bitmap 编码失败" }
        }.resource
    }

    private fun createOwnedSession(sessionId: String) {
        val current = backend
        ensureCacheRootReady(current)
        val existing = current.find(sessionId)
        if (existing != null) {
            check(existing.isDirectory && isOwnedSessionDirectory(current, existing)) {
                "缓存目录中已存在同名的非截图文件夹: $sessionId"
            }
            return
        }
        current.ensureDirectory(sessionId)
        try {
            current.write(sessionPath(sessionId, SESSION_MARKER_FILE), "text/plain") { output ->
                output.write(SESSION_MARKER_CONTENT)
            }
        } catch (throwable: Throwable) {
            current.delete(sessionId)
            throw throwable
        }
    }

    private fun ensureCacheRootReady(targetBackend: StorageTreeBackend) {
        targetBackend.ensureDirectory("")
        if (targetBackend.rootFile == null && targetBackend.find(NOMEDIA_FILE) == null) {
            runCatching {
                targetBackend.write(NOMEDIA_FILE, "application/octet-stream") { }
            }.onFailure { error ->
                Log.w(TAG, "所选缓存目录不支持 .nomedia，将继续使用该目录", error)
            }
        }
    }

    private fun ensureOwnedSession(sessionId: String, createIfMissing: Boolean) {
        val safeId = safeSegment(sessionId)
        val directory = backend.find(safeId)
        if (directory == null && createIfMissing) {
            createOwnedSession(safeId)
            return
        }
        check(directory != null && directory.isDirectory && isOwnedSessionDirectory(backend, directory)) {
            "拒绝写入非截图缓存目录: $safeId"
        }
    }

    private fun isOwnedSessionDirectory(
        sourceBackend: StorageTreeBackend,
        directory: StorageEntry,
    ): Boolean {
        if (!directory.isDirectory) return false
        val hasSessionMarker = SESSION_MARKER_FILES.any { marker ->
            sourceBackend.find(joinRelative(directory.relativePath, marker)) != null
        }
        if (hasSessionMarker) return true
        return readResult(sourceBackend, directory.name)?.let(::isValidCaptureResult) == true
    }

    private fun isValidCaptureResult(result: LayeredCaptureResult): Boolean =
        (result.id.isNotBlank() || result.sessionId.isNotBlank()) &&
            (result.scrollLongBitmapPath.isNotBlank() || result.scrollChunks.isNotEmpty())

    private fun storageTreesOverlap(
        first: StorageTreeBackend,
        second: StorageTreeBackend,
    ): Boolean = when {
        first is FileStorageTreeBackend && second is FileStorageTreeBackend -> first.overlaps(second)
        first is SafStorageTreeBackend && second is SafStorageTreeBackend -> first.overlaps(second)
        else -> false
    }

    private fun activeSessionKey(owner: StorageTreeBackend, sessionId: String): String =
        "${owner.identity}\n${safeSegment(sessionId)}"

    private fun registerActiveSession(key: String) {
        ACTIVE_SESSION_COUNTS[key] = (ACTIVE_SESSION_COUNTS[key] ?: 0) + 1
    }

    private fun unregisterActiveSession(key: String) {
        val remaining = (ACTIVE_SESSION_COUNTS[key] ?: return) - 1
        if (remaining <= 0) ACTIVE_SESSION_COUNTS.remove(key) else ACTIVE_SESSION_COUNTS[key] = remaining
    }

    private fun findResourceInDirectory(
        owner: StorageTreeBackend,
        parentPath: String,
        resource: String,
        visited: MutableSet<String> = hashSetOf(),
    ): StorageEntry? {
        if (!visited.add(parentPath) || visited.size > MAX_RESOURCE_SEARCH_DIRECTORIES) return null
        val children = runCatching { owner.listChildren(parentPath) }.getOrDefault(emptyList())
        children.forEach { child ->
            if (sameStorageResource(child.resource, resource)) return child
            if (child.isDirectory) {
                findResourceInDirectory(owner, child.relativePath, resource, visited)?.let { return it }
            }
        }
        return null
    }

    private fun sameStorageResource(first: String, second: String): Boolean {
        val firstUri = runCatching { Uri.parse(first) }.getOrNull()?.takeIf { it.scheme == "content" }
        val secondUri = runCatching { Uri.parse(second) }.getOrNull()?.takeIf { it.scheme == "content" }
        if (firstUri != null || secondUri != null) return firstUri != null && firstUri == secondUri
        val firstFile = runCatching { File(first).canonicalFile }.getOrNull() ?: return false
        val secondFile = runCatching { File(second).canonicalFile }.getOrNull() ?: return false
        return firstFile == secondFile
    }

    private fun sanitizeNestedPath(value: String): String = value.replace('\\', '/')
        .split('/')
        .filter(String::isNotBlank)
        .joinToString("/") { safeSegment(it) }
        .also { require(it.isNotBlank()) { "无效文件路径" } }

    private fun writePng(bitmap: Bitmap, output: java.io.OutputStream) {
        require(!bitmap.isRecycled) { "Bitmap 已释放" }
        val row = IntArray(bitmap.width)
        StreamingPngEncoder.write(output, bitmap.width, bitmap.height) { y ->
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            row
        }
    }

    private fun ensureExtension(name: String, format: Bitmap.CompressFormat): String {
        if (name.substringAfterLast('.', "").isNotBlank()) return name
        val extension = when {
            format == Bitmap.CompressFormat.JPEG -> "jpg"
            format.name.startsWith("WEBP") -> "webp"
            else -> "png"
        }
        return "$name.$extension"
    }

    private fun safeSegment(value: String): String {
        val clean = value.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        require(clean.isNotBlank() && clean != "..") { "无效文件名: $value" }
        return clean.take(120)
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "SessionFileStore"
        const val DEFAULT_ROOT_NAME = ""
        const val RESULT_METADATA_FILE = "result.json"
        private const val LEGACY_ROOT_NAME = "capture_sessions"
        private const val SCROLL_CHUNKS_DIRECTORY = "scroll_chunks"
        private const val SESSION_MARKER_FILE = "irisshot.session"
        // External cache folders can outlive the package that originally created them.
        private val LEGACY_SESSION_MARKER_FILE = listOf("game", "long", "shot.session").joinToString("")
        private val SESSION_MARKER_FILES = setOf(SESSION_MARKER_FILE, LEGACY_SESSION_MARKER_FILE)
        private const val NOMEDIA_FILE = ".nomedia"
        private val SESSION_MARKER_CONTENT = "GameLongShot\n".toByteArray(Charsets.US_ASCII)
        private const val MIGRATION_PREFS_NAME = "capture_storage_migration_state"
        private const val KEY_MIGRATION_VERSION = "completed_version"
        private const val LEGACY_MIGRATION_VERSION = 1
        private const val MAX_RESOURCE_SEARCH_DIRECTORIES = 4_096
        private val LEGACY_MIGRATION_LOCK = Any()
        private val STORAGE_OPERATION_LOCK = Any()
        private val ACTIVE_SESSION_COUNTS = mutableMapOf<String, Int>()
    }
}
