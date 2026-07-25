package com.apricotiris.IrisShot.core.export

import com.apricotiris.IrisShot.core.model.LayeredCaptureResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun defaultCaptureExportBaseName(
    result: LayeredCaptureResult,
    fallbackTimestamp: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault(),
): String = formatCaptureExportBaseName(
    timestamp = result.createdAt.takeIf { it > 0L } ?: fallbackTimestamp,
    packageName = result.capturedPackageName.takeIf(String::isNotBlank)
        ?: result.templateSnapshot?.packageName,
    timeZone = timeZone,
)

internal fun formatCaptureExportBaseName(
    timestamp: Long,
    packageName: String?,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val formattedTime = SimpleDateFormat(EXPORT_TIME_PATTERN, Locale.ROOT).apply {
        this.timeZone = timeZone
    }.format(Date(timestamp))
    val packageSuffix = packageName
        ?.trim()
        ?.replace('.', '_')
        ?.replace(INVALID_PACKAGE_SUFFIX_CHARACTER, "_")
        ?.trim('_')
        ?.takeIf(String::isNotEmpty)
        ?: UNKNOWN_PACKAGE_SUFFIX
    return "${EXPORT_FILE_PREFIX}_${formattedTime}_$packageSuffix"
}

private const val EXPORT_FILE_PREFIX = "GameLongShot"
private const val EXPORT_TIME_PATTERN = "yyyyMMdd_HHmmss"
private const val UNKNOWN_PACKAGE_SUFFIX = "unknown"
private val INVALID_PACKAGE_SUFFIX_CHARACTER = Regex("[^A-Za-z0-9_]")
