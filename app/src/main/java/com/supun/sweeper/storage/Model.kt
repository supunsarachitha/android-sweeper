package com.supun.sweeper.storage

/** Total and free bytes of a storage volume. */
data class VolumeInfo(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
}

/**
 * Sizes of one installed app. The three sizes do not overlap: [dataBytes] is the app's data
 * WITHOUT its cache, so [totalBytes] matches what Android Settings shows for the app.
 */
data class AppStorage(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val appBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
}

enum class AppSort { TOTAL, CACHE, DATA, APP }

/** Largest first by the chosen size; equal sizes are ordered by label (case-insensitive), then package. */
fun sortApps(list: List<AppStorage>, sort: AppSort): List<AppStorage> {
    val size: (AppStorage) -> Long = when (sort) {
        AppSort.TOTAL -> AppStorage::totalBytes
        AppSort.CACHE -> AppStorage::cacheBytes
        AppSort.DATA -> AppStorage::dataBytes
        AppSort.APP -> AppStorage::appBytes
    }
    return list.sortedWith(
        compareByDescending<AppStorage> { size(it) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { it.packageName },
    )
}

fun filterApps(list: List<AppStorage>, includeSystem: Boolean): List<AppStorage> =
    if (includeSystem) list else list.filter { !it.isSystem }

fun totalCache(list: List<AppStorage>): Long = list.sumOf { it.cacheBytes }

/** How the used space of a volume splits into categories. */
data class StorageBreakdown(
    val total: Long,
    val used: Long,
    val apps: Long,
    val images: Long,
    val video: Long,
    val audio: Long,
) {
    val systemAndOther: Long get() = (used - apps - images - video - audio).coerceAtLeast(0)

    /** Bar width: the volume size, or the sum of the parts if they somehow exceed it. */
    private val barBytes: Long get() = maxOf(total, apps + images + video + audio + systemAndOther)

    private fun fraction(bytes: Long): Float =
        if (barBytes <= 0) 0f else (bytes.toDouble() / barBytes).coerceIn(0.0, 1.0).toFloat()

    val appsFraction: Float get() = fraction(apps)
    val imagesFraction: Float get() = fraction(images)
    val videoFraction: Float get() = fraction(video)
    val audioFraction: Float get() = fraction(audio)
    val systemAndOtherFraction: Float get() = fraction(systemAndOther)
}
