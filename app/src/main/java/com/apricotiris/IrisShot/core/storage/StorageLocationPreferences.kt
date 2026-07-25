package com.apricotiris.IrisShot.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File

internal class StorageLocationPersistenceException(message: String) : IllegalStateException(message)

/** Persists the user-selected cache and export directory trees. */
class StorageLocationPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val cacheTreeUri: Uri?
        get() = preferences.getString(KEY_CACHE_TREE_URI, null).toContentUriOrNull()

    val exportTreeUri: Uri?
        get() = preferences.getString(KEY_EXPORT_TREE_URI, null).toContentUriOrNull()

    val cacheDisplayName: String
        get() = preferences.getString(KEY_CACHE_DISPLAY_NAME, null)
            ?.takeIf(String::isNotBlank)
            ?: defaultCacheDisplayName(appContext)

    val exportDisplayName: String
        get() = preferences.getString(KEY_EXPORT_DISPLAY_NAME, null)
            ?.takeIf(String::isNotBlank)
            ?: DEFAULT_EXPORT_DISPLAY_NAME

    fun setCacheLocation(
        uri: Uri?,
        displayName: String? = null,
        releasePreviousPermission: Boolean = true,
    ) {
        val previous = cacheTreeUri
        val previousDisplayName = preferences.getString(KEY_CACHE_DISPLAY_NAME, null)
        persistTreePermission(uri)
        val committed = preferences.edit()
            .putOrRemove(KEY_CACHE_TREE_URI, uri?.toString())
            .putOrRemove(KEY_CACHE_DISPLAY_NAME, displayName?.trim()?.takeIf(String::isNotBlank))
            .commit()
        if (!committed) {
            preferences.edit()
                .putOrRemove(KEY_CACHE_TREE_URI, previous?.toString())
                .putOrRemove(KEY_CACHE_DISPLAY_NAME, previousDisplayName)
                .commit()
            throw StorageLocationPersistenceException("无法保存缓存目录设置，已保留原位置")
        }
        if (releasePreviousPermission) releasePermissionIfUnused(previous)
    }

    fun setExportLocation(
        uri: Uri?,
        displayName: String? = null,
        releasePreviousPermission: Boolean = true,
    ) {
        val previous = exportTreeUri
        val previousDisplayName = preferences.getString(KEY_EXPORT_DISPLAY_NAME, null)
        persistTreePermission(uri)
        val committed = preferences.edit()
            .putOrRemove(KEY_EXPORT_TREE_URI, uri?.toString())
            .putOrRemove(KEY_EXPORT_DISPLAY_NAME, displayName?.trim()?.takeIf(String::isNotBlank))
            .commit()
        if (!committed) {
            preferences.edit()
                .putOrRemove(KEY_EXPORT_TREE_URI, previous?.toString())
                .putOrRemove(KEY_EXPORT_DISPLAY_NAME, previousDisplayName)
                .commit()
            releasePermissionIfUnused(uri)
            throw StorageLocationPersistenceException("无法保存导出目录设置，已保留原位置")
        }
        if (releasePreviousPermission) releasePermissionIfUnused(previous)
    }

    fun clearExportLocation(releasePreviousPermission: Boolean = true) = setExportLocation(
        uri = null,
        releasePreviousPermission = releasePreviousPermission,
    )

    private fun persistTreePermission(uri: Uri?) {
        if (uri == null) return
        require(uri.scheme == "content") { "目录选择器必须返回 content URI" }
        val existing = appContext.contentResolver.persistedUriPermissions.firstOrNull {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (existing != null) return
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun releasePermissionIfUnused(uri: Uri?) {
        if (uri == null || uri == cacheTreeUri || uri == exportTreeUri) return
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun android.content.SharedPreferences.Editor.putOrRemove(
        key: String,
        value: String?,
    ): android.content.SharedPreferences.Editor = if (value == null) remove(key) else putString(key, value)

    private fun String?.toContentUriOrNull(): Uri? = this
        ?.takeIf(String::isNotBlank)
        ?.let(Uri::parse)
        ?.takeIf { it.scheme == "content" }

    companion object {
        private const val PREFS_NAME = "storage_locations"
        private const val KEY_CACHE_TREE_URI = "cache_tree_uri"
        private const val KEY_CACHE_DISPLAY_NAME = "cache_display_name"
        private const val KEY_EXPORT_TREE_URI = "export_tree_uri"
        private const val KEY_EXPORT_DISPLAY_NAME = "export_display_name"

        const val DEFAULT_EXPORT_DISPLAY_NAME = "Pictures/GameLongShot"

        fun defaultCacheDirectory(context: Context): File =
            context.applicationContext.externalCacheDir ?: context.applicationContext.cacheDir

        fun defaultCacheDisplayName(context: Context): String =
            defaultCacheDirectory(context).absolutePath
    }
}
