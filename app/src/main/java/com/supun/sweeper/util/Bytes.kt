package com.supun.sweeper.util

import java.util.Locale

private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

/**
 * Formats a byte count the way Android's own Settings app does (decimal units, 1 KB = 1000 B),
 * so the numbers here match what the user sees in Settings > Storage.
 */
fun formatBytes(bytes: Long): String {
    require(bytes >= 0) { "bytes must not be negative: $bytes" }
    if (bytes < 1000) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val pattern = if (value < 10) "%.2f %s" else if (value < 100) "%.1f %s" else "%.0f %s"
    return String.format(Locale.US, pattern, value, UNITS[unit])
}

/** Fraction of [total] that is [used], clamped to 0..1 (0 when total is 0). */
fun usedFraction(used: Long, total: Long): Float =
    if (total <= 0) 0f else (used.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
