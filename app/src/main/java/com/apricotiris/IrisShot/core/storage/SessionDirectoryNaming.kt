package com.apricotiris.IrisShot.core.storage

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun formatSessionDirectoryName(
    timestamp: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): String = SimpleDateFormat(SESSION_DIRECTORY_NAME_PATTERN, Locale.ROOT).apply {
    this.timeZone = timeZone
}.format(Date(timestamp))

internal fun nextAvailableSessionDirectoryName(
    baseName: String,
    exists: (String) -> Boolean,
): String {
    var suffix = 1
    while (true) {
        val candidate = if (suffix == 1) baseName else "${baseName}_$suffix"
        if (!exists(candidate)) return candidate
        suffix++
    }
}

internal fun isTimestampSessionDirectoryName(name: String): Boolean =
    TIMESTAMP_SESSION_DIRECTORY_REGEX.matches(name)

private const val SESSION_DIRECTORY_NAME_PATTERN = "yyyy-MM-dd_HH-mm-ss"
private val TIMESTAMP_SESSION_DIRECTORY_REGEX =
    Regex("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}(?:_\\d+)?")
