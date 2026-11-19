package com.turbooptimizer.util

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "N/D"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return String.format(Locale.getDefault(), if (value >= 100) "%.0f %s" else "%.1f %s", value, units[i])
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatDate(ts: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

fun formatRelative(ts: Long?): String {
    if (ts == null || ts <= 0) return "N/D"
    return DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

fun formatPercent(value: Float?): String = value?.let { String.format(Locale.getDefault(), "%.0f %%", it) } ?: "N/D"

fun formatTemp(value: Float?): String = value?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "N/D"
