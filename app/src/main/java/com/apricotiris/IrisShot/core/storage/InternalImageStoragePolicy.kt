package com.apricotiris.IrisShot.core.storage

/**
 * Reusable capture-session source images remain lossless so changing the eventual export format
 * never causes an extra lossy generation. Export preferences live on the result model instead.
 */
internal const val INTERNAL_SCROLL_SOURCE_MIME_TYPE = "image/png"

internal fun internalScrollSourceFileName(fileName: String): String {
    require(fileName.isNotBlank()) { "内部滚动图文件名不能为空" }
    val extension = fileName.substringAfterLast('.', "")
    val baseName = when {
        extension.equals("jpg", ignoreCase = true) ||
            extension.equals("jpeg", ignoreCase = true) ||
            extension.equals("png", ignoreCase = true) -> fileName.substringBeforeLast('.')
        else -> fileName
    }
    return "$baseName.png"
}
