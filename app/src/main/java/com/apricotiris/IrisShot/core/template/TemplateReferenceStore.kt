package com.apricotiris.IrisShot.core.template

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TemplateReferenceStore(context: Context) {
    private val appContext = context.applicationContext
    private val files = TemplateReferenceFiles(
        File(appContext.filesDir, REFERENCE_DIRECTORY),
    )

    fun exists(id: Long): Boolean = synchronized(STORE_LOCK) {
        files.exists(id)
    }

    fun save(id: Long, bitmap: Bitmap): String = synchronized(STORE_LOCK) {
        saveBitmapLocked(id, bitmap)
    }

    fun save(id: Long, uri: Uri): String = synchronized(STORE_LOCK) {
        val bitmap = decodeReference(uri)
        try {
            saveBitmapLocked(id, bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun saveBitmapLocked(id: Long, bitmap: Bitmap): String {
        require(!bitmap.isRecycled) { "参考图已经被释放" }
        require(bitmap.width > 0 && bitmap.height > 0) { "参考图尺寸无效" }
        return files.replace(id) { temporary ->
            FileOutputStream(temporary).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput, BUFFER_SIZE)
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "参考图编码失败"
                }
                output.flush()
                fileOutput.fd.sync()
            }
        }
    }

    private fun decodeReference(uri: Uri): Bitmap = try {
        val source = ImageDecoder.createSource(appContext.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
            decoder.setTargetSampleSize(referenceSampleSize(info.size.width, info.size.height))
        }
    } catch (_: Exception) {
        decodeReferenceWithBitmapFactory(uri)
    }

    private fun decodeReferenceWithBitmapFactory(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: error("无法读取所选参考图")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是有效图片" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = referenceSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return appContext.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("无法解码所选参考图")
    }

    private fun referenceSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > REFERENCE_MAX_DIMENSION) {
            sample *= 2
        }
        return sample
    }

    fun copy(fromId: Long, toId: Long): Boolean = synchronized(STORE_LOCK) {
        files.copy(fromId, toId)
    }

    fun delete(id: Long): Boolean = synchronized(STORE_LOCK) {
        files.delete(id)
    }

    fun resource(id: Long): String? = synchronized(STORE_LOCK) {
        files.resource(id)
    }

    internal fun clear() = synchronized(STORE_LOCK) {
        files.clear()
    }

    companion object {
        private const val REFERENCE_DIRECTORY = "template_references"
        private const val JPEG_QUALITY = 92
        private const val REFERENCE_MAX_DIMENSION = 2_048
        private const val BUFFER_SIZE = 128 * 1024
        private val STORE_LOCK = Any()
    }
}

internal class TemplateReferenceFiles(private val rootDirectory: File) {
    fun exists(id: Long): Boolean = id > 0L && referenceFile(id).isFile

    fun resource(id: Long): String? = id.takeIf { it > 0L }
        ?.let(::referenceFile)
        ?.takeIf(File::isFile)
        ?.absolutePath

    fun replace(id: Long, writer: (File) -> Unit): String {
        val target = referenceFile(id)
        check(rootDirectory.exists() || rootDirectory.mkdirs()) {
            "无法创建模板参考图目录"
        }
        val temporary = File(rootDirectory, ".${target.name}.writing")
        if (temporary.exists()) temporary.delete()
        try {
            writer(temporary)
            check(temporary.isFile && temporary.length() > 0L) { "参考图写入失败" }
            moveReplacing(temporary, target)
            return target.absolutePath
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun copy(fromId: Long, toId: Long): Boolean {
        val source = referenceFile(fromId)
        if (!source.isFile) return false
        replace(toId) { temporary -> source.inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        } }
        return true
    }

    fun delete(id: Long): Boolean {
        if (id <= 0L) return false
        val target = referenceFile(id)
        return !target.exists() || target.delete()
    }

    fun clear() {
        if (!rootDirectory.exists()) return
        rootDirectory.listFiles().orEmpty().forEach { file ->
            if (file.isFile) file.delete()
        }
        rootDirectory.delete()
    }

    private fun referenceFile(id: Long): File {
        require(id > 0L) { "模板 id 必须大于 0" }
        return File(rootDirectory, "template_$id.reference")
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
