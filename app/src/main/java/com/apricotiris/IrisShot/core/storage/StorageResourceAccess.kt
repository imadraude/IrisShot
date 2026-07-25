package com.apricotiris.IrisShot.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Reads image resources represented by either absolute file paths or content URIs. */
class StorageResourceAccess(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun openInputStream(resource: String): InputStream? = resource.toUriOrNull()?.let {
        resolver.openInputStream(it)
    } ?: resource.takeIf(String::isNotBlank)?.let { path ->
        File(path).takeIf(File::isFile)?.let(::FileInputStream)
    }

    fun openOutputStream(resource: String, mode: String = "w"): OutputStream? =
        resource.toUriOrNull()?.let { resolver.openOutputStream(it, mode) }
            ?: resource.takeIf(String::isNotBlank)?.let { path ->
                val file = File(path)
                file.parentFile?.let { parent -> check(parent.exists() || parent.mkdirs()) }
                FileOutputStream(file, mode == "wa")
            }

    fun openFileDescriptor(resource: String, mode: String = "r"): ParcelFileDescriptor? =
        resource.toUriOrNull()?.let { resolver.openFileDescriptor(it, mode) }
            ?: resource.takeIf(String::isNotBlank)?.let { path ->
                val file = File(path)
                if (mode == "r" && !file.isFile) return@let null
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
            }

    fun decodeBitmap(resource: String, options: BitmapFactory.Options? = null): Bitmap? =
        if (resource.toUriOrNull() == null) {
            BitmapFactory.decodeFile(resource, options)
        } else {
            openInputStream(resource)?.use { BitmapFactory.decodeStream(it, null, options) }
        }

    fun openRegionDecoder(resource: String): ManagedRegionDecoder? {
        if (resource.toUriOrNull() == null) {
            val decoder = BitmapRegionDecoder.newInstance(resource, false) ?: return null
            return ManagedRegionDecoder(decoder, null)
        }
        val descriptor = openFileDescriptor(resource) ?: return null
        return try {
            val decoder = BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
                ?: run {
                    descriptor.close()
                    return null
                }
            ManagedRegionDecoder(decoder, descriptor)
        } catch (throwable: Throwable) {
            descriptor.close()
            throw throwable
        }
    }

    fun exists(resource: String): Boolean {
        val uri = resource.toUriOrNull() ?: return File(resource).exists()
        return runCatching {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() }
                ?: false
        }.getOrDefault(false)
    }

    fun length(resource: String): Long? {
        val uri = resource.toUriOrNull() ?: return File(resource).takeIf(File::isFile)?.length()
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) {
                    null
                } else {
                    cursor.getLong(0).takeIf { it >= 0L }
                }
            }
        }.getOrNull()
        return queried ?: runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
    }

    fun delete(resource: String): Boolean {
        val uri = resource.toUriOrNull()
        return if (uri == null) {
            val file = File(resource)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        } else {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        }
    }

    class ManagedRegionDecoder internal constructor(
        val decoder: BitmapRegionDecoder,
        private val descriptor: ParcelFileDescriptor?,
    ) : Closeable {
        val width: Int get() = decoder.width
        val height: Int get() = decoder.height

        fun decodeRegion(rect: Rect, options: BitmapFactory.Options? = null): Bitmap? =
            decoder.decodeRegion(rect, options)

        override fun close() {
            runCatching { decoder.recycle() }
            runCatching { descriptor?.close() }
        }
    }

    private fun String.toUriOrNull(): Uri? = takeIf(String::isNotBlank)
        ?.let(Uri::parse)
        ?.takeIf { it.scheme == "content" }
}
