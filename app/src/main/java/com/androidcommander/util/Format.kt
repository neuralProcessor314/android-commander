package com.androidcommander.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

fun formatMtime(millis: Long): String = dateFmt.format(Date(millis))

/** Right-justified, <=6 chars, matching the fixed SIZE column (instruction section 6). */
fun formatSize(bytes: Long): String {
    val s = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024L * 1024 -> "${bytes / 1024}K"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)}M"
        else -> "${bytes / (1024L * 1024 * 1024)}G"
    }
    return s.padStart(6).takeLast(6)
}

/** Ellipsise the head so the tail (most specific part) always stays visible; never wraps. */
fun ellipsiseHead(path: String, maxChars: Int): String {
    if (path.length <= maxChars) return path
    if (maxChars <= 1) return path.takeLast(maxChars)
    return "…" + path.takeLast(maxChars - 1)
}
