package com.apricotiris.IrisShot.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

internal data class StorageEntry(
    val relativePath: String,
    val name: String,
    val resource: String,
    val isDirectory: Boolean,
    val lastModified: Long = 0L,
    val length: Long = 0L,
)

internal interface StorageTreeBackend {
    val identity: String
    val rootFile: File?

    fun rootExists(): Boolean
    fun ensureDirectory(relativePath: String): StorageEntry
    fun find(relativePath: String): StorageEntry?
    fun listChildren(relativePath: String = ""): List<StorageEntry>
    fun write(relativePath: String, mimeType: String, writer: (OutputStream) -> Unit): StorageEntry
    fun delete(relativePath: String): Boolean
    fun owns(resource: String): Boolean
}

internal class FileStorageTreeBackend(root: File) : StorageTreeBackend {
    private val root = root.absoluteFile

    override val identity: String = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
    override val rootFile: File = root

    override fun rootExists(): Boolean = root.isDirectory

    override fun ensureDirectory(relativePath: String): StorageEntry {
        val directory = resolve(relativePath)
        check(!directory.exists() || directory.isDirectory) { "缓存路径不是文件夹: ${directory.absolutePath}" }
        check(directory.exists() || directory.mkdirs()) { "无法创建缓存文件夹: ${directory.absolutePath}" }
        return directory.toEntry(normalizeRelativePath(relativePath))
    }

    override fun find(relativePath: String): StorageEntry? {
        val normalized = normalizeRelativePath(relativePath)
        val file = resolve(normalized)
        return file.takeIf(File::exists)?.toEntry(normalized)
    }

    override fun listChildren(relativePath: String): List<StorageEntry> {
        val parentPath = normalizeRelativePath(relativePath)
        val directory = resolve(parentPath)
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().orEmpty().map { child ->
            child.toEntry(joinRelative(parentPath, child.name))
        }
    }

    override fun write(
        relativePath: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): StorageEntry {
        val normalized = normalizeRelativePath(relativePath)
        require(normalized.isNotEmpty()) { "不能覆盖缓存根目录" }
        val output = resolve(normalized)
        ensureDirectory(normalized.substringBeforeLast('/', ""))
        check(!output.exists() || output.isFile) { "缓存目标是文件夹: ${output.absolutePath}" }
        val temporary = File(output.parentFile, ".${output.name}.writing")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val buffered = BufferedOutputStream(fileOutput, IO_BUFFER_SIZE)
                writer(buffered)
                buffered.flush()
                fileOutput.fd.sync()
            }
            Os.rename(temporary.absolutePath, output.absolutePath)
            return output.toEntry(normalized)
        } catch (throwable: Throwable) {
            temporary.delete()
            throw throwable
        }
    }

    override fun delete(relativePath: String): Boolean {
        val normalized = normalizeRelativePath(relativePath)
        if (normalized.isEmpty()) return false
        val target = resolve(normalized)
        if (!target.exists()) return false
        return if (target.isDirectory) target.deleteRecursively() else target.delete()
    }

    override fun owns(resource: String): Boolean {
        val candidate = runCatching { File(resource).canonicalFile }.getOrNull() ?: return false
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
        return candidate == canonicalRoot || candidate.path.startsWith(canonicalRoot.path + File.separator)
    }

    fun overlaps(other: FileStorageTreeBackend): Boolean {
        val first = runCatching { root.canonicalFile }.getOrNull() ?: return false
        val second = runCatching { other.root.canonicalFile }.getOrNull() ?: return false
        return first == second ||
            first.path.startsWith(second.path + File.separator) ||
            second.path.startsWith(first.path + File.separator)
    }

    private fun resolve(relativePath: String): File {
        val normalized = normalizeRelativePath(relativePath)
        val candidate = if (normalized.isEmpty()) root else File(root, normalized.replace('/', File.separatorChar))
        check(owns(candidate.absolutePath)) { "缓存路径超出已授权目录" }
        return candidate
    }

    private fun File.toEntry(relativePath: String): StorageEntry = StorageEntry(
        relativePath = relativePath,
        name = name,
        resource = absolutePath,
        isDirectory = isDirectory,
        lastModified = lastModified(),
        length = if (isFile) length() else 0L,
    )
}

internal class SafStorageTreeBackend(
    context: Context,
    internal val treeUri: Uri,
) : StorageTreeBackend {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)

    override val identity: String = treeUri.toString()
    override val rootFile: File? = null

    override fun rootExists(): Boolean = queryEntry(rootDocumentUri, "") != null

    override fun ensureDirectory(relativePath: String): StorageEntry {
        var current = rootEntry()
        splitRelativePath(relativePath).forEachIndexed { index, segment ->
            val traversed = splitRelativePath(relativePath).take(index + 1).joinToString("/")
            val existing = findChild(current, segment)
            current = when {
                existing == null -> {
                    val uri = checkNotNull(
                        DocumentsContract.createDocument(
                            resolver,
                            Uri.parse(current.resource),
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            segment,
                        )
                    ) { "无法创建缓存文件夹: $segment" }
                    queryEntry(uri, traversed) ?: error("无法读取新建缓存文件夹: $segment")
                }
                existing.isDirectory -> existing.copy(relativePath = traversed)
                else -> error("缓存路径不是文件夹: $segment")
            }
        }
        return current
    }

    override fun find(relativePath: String): StorageEntry? {
        val segments = splitRelativePath(relativePath)
        var current = rootEntryOrNull() ?: return null
        if (segments.isEmpty()) return current
        segments.forEachIndexed { index, segment ->
            current = findChild(current, segment) ?: return null
            current = current.copy(relativePath = segments.take(index + 1).joinToString("/"))
        }
        return current
    }

    override fun listChildren(relativePath: String): List<StorageEntry> {
        val normalized = normalizeRelativePath(relativePath)
        val parent = find(normalized)?.takeIf(StorageEntry::isDirectory) ?: return emptyList()
        return queryChildren(Uri.parse(parent.resource)).map { child ->
            child.copy(relativePath = joinRelative(normalized, child.name))
        }
    }

    override fun write(
        relativePath: String,
        mimeType: String,
        writer: (OutputStream) -> Unit,
    ): StorageEntry {
        val normalized = normalizeRelativePath(relativePath)
        require(normalized.isNotEmpty()) { "不能覆盖缓存根目录" }
        val parentPath = normalized.substringBeforeLast('/', "")
        val fileName = normalized.substringAfterLast('/')
        val parent = ensureDirectory(parentPath)
        val existing = findChild(parent, fileName)
        check(existing?.isDirectory != true) { "缓存目标是文件夹: $fileName" }
        if (existing == null) {
            val target = createDocument(parent, fileName, mimeType, normalized)
            return try {
                writeDocument(target, writer)
                queryEntry(Uri.parse(target.resource), normalized) ?: target.copy(relativePath = normalized)
            } catch (throwable: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(target.resource)) }
                throw throwable
            }
        }

        val transactionId = System.nanoTime().toString(36)
        val staged = createDocument(
            parent = parent,
            displayName = "$fileName.gls-writing-$transactionId",
            mimeType = mimeType,
            relativePath = normalized,
        )
        try {
            writeDocument(staged, writer)
            replaceExistingDocument(
                parent = parent,
                existing = existing,
                staged = staged,
                fileName = fileName,
                mimeType = mimeType,
                relativePath = normalized,
                transactionId = transactionId,
            )
            runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(staged.resource)) }
            return find(normalized) ?: error("无法读取替换后的缓存文件: $fileName")
        } catch (throwable: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(staged.resource)) }
            throw throwable
        }
    }

    override fun delete(relativePath: String): Boolean {
        val normalized = normalizeRelativePath(relativePath)
        if (normalized.isEmpty()) return false
        val target = find(normalized) ?: return false
        return DocumentsContract.deleteDocument(resolver, Uri.parse(target.resource))
    }

    override fun owns(resource: String): Boolean {
        val uri = runCatching { Uri.parse(resource) }.getOrNull() ?: return false
        if (uri.scheme != "content" || uri.authority != treeUri.authority) return false
        if (uri == rootDocumentUri) return true
        val hasSameTree = runCatching { DocumentsContract.getTreeDocumentId(uri) == rootDocumentId }
            .getOrDefault(false)
        if (!hasSameTree) return false
        return runCatching {
            DocumentsContract.isChildDocument(resolver, rootDocumentUri, uri)
        }.getOrDefault(false)
    }

    fun overlaps(other: SafStorageTreeBackend): Boolean {
        if (treeUri.authority != other.treeUri.authority) return false
        if (rootDocumentId == other.rootDocumentId) return true
        val otherRootUri = other.rootDocumentUri
        val providerReportsOverlap = runCatching {
            DocumentsContract.isChildDocument(resolver, rootDocumentUri, otherRootUri) ||
                DocumentsContract.isChildDocument(resolver, otherRootUri, rootDocumentUri)
        }.getOrDefault(false)
        return providerReportsOverlap ||
            hierarchicalDocumentIdsOverlap(rootDocumentId, other.rootDocumentId)
    }

    private fun rootEntry(): StorageEntry = rootEntryOrNull() ?: error("缓存目录授权已失效")

    private fun rootEntryOrNull(): StorageEntry? = queryEntry(rootDocumentUri, "")

    private fun findChild(parent: StorageEntry, name: String): StorageEntry? =
        queryChildren(Uri.parse(parent.resource)).firstOrNull { it.name == name }

    private fun createDocument(
        parent: StorageEntry,
        displayName: String,
        mimeType: String,
        relativePath: String,
    ): StorageEntry {
        val uri = checkNotNull(
            DocumentsContract.createDocument(
                resolver,
                Uri.parse(parent.resource),
                mimeType,
                displayName,
            )
        ) { "无法创建缓存文件: $displayName" }
        return queryEntry(uri, relativePath) ?: error("无法读取新建缓存文件: $displayName")
    }

    private fun writeDocument(target: StorageEntry, writer: (OutputStream) -> Unit) {
        val outputStream = runCatching {
            resolver.openOutputStream(Uri.parse(target.resource), "rwt")
        }.getOrNull() ?: resolver.openOutputStream(Uri.parse(target.resource), "w")
        checkNotNull(outputStream) {
            "无法写入缓存文件: ${target.name}"
        }.use { raw -> BufferedOutputStream(raw, IO_BUFFER_SIZE).use(writer) }
    }

    private fun replaceExistingDocument(
        parent: StorageEntry,
        existing: StorageEntry,
        staged: StorageEntry,
        fileName: String,
        mimeType: String,
        relativePath: String,
        transactionId: String,
    ) {
        val backupName = "$fileName.gls-backup-$transactionId"
        val renamedBackup = runCatching {
            DocumentsContract.renameDocument(resolver, Uri.parse(existing.resource), backupName)
        }.getOrNull()
        if (renamedBackup != null) {
            val renamedTarget = runCatching {
                DocumentsContract.renameDocument(resolver, Uri.parse(staged.resource), fileName)
            }.getOrNull()
            if (renamedTarget != null) {
                runCatching { DocumentsContract.deleteDocument(resolver, renamedBackup) }
                return
            }
            runCatching { DocumentsContract.renameDocument(resolver, renamedBackup, fileName) }
            error("无法完成缓存文件替换: $fileName")
        }

        val backup = createDocument(parent, backupName, mimeType, relativePath)
        var deleteBackup = true
        try {
            copyDocument(Uri.parse(existing.resource), Uri.parse(backup.resource))
            try {
                copyDocument(Uri.parse(staged.resource), Uri.parse(existing.resource))
            } catch (writeError: Throwable) {
                val restored = runCatching {
                    copyDocument(Uri.parse(backup.resource), Uri.parse(existing.resource))
                }.isSuccess
                if (!restored) {
                    deleteBackup = false
                    throw IllegalStateException(
                        "缓存文件替换失败，旧内容保留在 ${backup.name}",
                        writeError,
                    )
                }
                throw writeError
            }
        } finally {
            if (deleteBackup) {
                runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(backup.resource)) }
            }
        }
    }

    private fun copyDocument(source: Uri, target: Uri) {
        val input = checkNotNull(resolver.openInputStream(source)) { "无法读取缓存事务文件" }
        val output = runCatching { resolver.openOutputStream(target, "rwt") }.getOrNull()
            ?: resolver.openOutputStream(target, "w")
        checkNotNull(output) { "无法写入缓存事务文件" }
        input.use { rawInput ->
            BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { bufferedInput ->
                output.use { rawOutput ->
                    BufferedOutputStream(rawOutput, IO_BUFFER_SIZE).use { bufferedOutput ->
                        bufferedInput.copyTo(bufferedOutput)
                    }
                }
            }
        }
    }

    private fun queryChildren(parentUri: Uri): List<StorageEntry> {
        val parentId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return resolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val resource = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                    add(
                        StorageEntry(
                            relativePath = name,
                            name = name,
                            resource = resource.toString(),
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            lastModified = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                            length = if (cursor.isNull(4)) -1L else cursor.getLong(4),
                        )
                    )
                }
            }
        }.orEmpty()
    }

    private fun queryEntry(uri: Uri, relativePath: String): StorageEntry? =
        resolver.query(uri, DOCUMENT_PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            StorageEntry(
                relativePath = relativePath,
                name = cursor.getString(1).orEmpty(),
                resource = uri.toString(),
                isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                lastModified = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                length = if (cursor.isNull(4)) -1L else cursor.getLong(4),
            )
        }

    companion object {
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

internal fun normalizeRelativePath(value: String): String = value
    .replace('\\', '/')
    .trim('/')
    .takeIf(String::isNotEmpty)
    ?.split('/')
    ?.also { segments ->
        require(segments.none { it.isBlank() || it == "." || it == ".." }) { "无效缓存相对路径" }
    }
    ?.joinToString("/")
    .orEmpty()

internal fun splitRelativePath(value: String): List<String> =
    normalizeRelativePath(value).takeIf(String::isNotEmpty)?.split('/').orEmpty()

internal fun joinRelative(parent: String, child: String): String =
    if (parent.isBlank()) child else "${normalizeRelativePath(parent)}/${normalizeRelativePath(child)}"

internal fun hierarchicalDocumentIdsOverlap(first: String, second: String): Boolean {
    fun isDescendant(parent: String, child: String): Boolean =
        child.startsWith(parent.trimEnd('/') + "/")
    return first == second || isDescendant(first, second) || isDescendant(second, first)
}

private const val IO_BUFFER_SIZE = 128 * 1024
