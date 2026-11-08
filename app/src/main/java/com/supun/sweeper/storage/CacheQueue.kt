package com.supun.sweeper.storage

/**
 * The "Clear caches" walk through the apps the user picked. Android does not let one app clear another app's
 * cache, so the screen opens each app's App info page in turn and the user taps "Clear cache" there.
 *
 * [position] is the app whose page is open (when [waitingForReturn]) or opens next. Kept as plain values so the
 * screen can save it across a process restart while the user is in Settings.
 */
data class CacheQueue(
    val packages: List<String>,
    val position: Int = 0,
    val waitingForReturn: Boolean = false,
    val cacheBefore: Long = 0,
) {
    val current: String? get() = packages.getOrNull(position)
    val finished: Boolean get() = position >= packages.size

    /** The App info page of [current] has been opened. */
    fun opened(): CacheQueue = if (finished) this else copy(waitingForReturn = true)

    /** The user came back from Settings: move on to the next app (only if a page was open). */
    fun returned(): CacheQueue =
        if (waitingForReturn) copy(position = position + 1, waitingForReturn = false) else this

    /** Skips the next app without opening it. */
    fun skip(): CacheQueue = if (finished || waitingForReturn) this else copy(position = position + 1)

    /** For saving in the screen state: "position|waiting|cacheBefore|pkg1,pkg2,...". */
    fun encode(): String = "$position|${if (waitingForReturn) 1 else 0}|$cacheBefore|${packages.joinToString(",")}"

    companion object {
        /**
         * The selected apps, largest cache first (the most worthwhile ones come first if the user stops early).
         * Returns null when nothing is selected.
         */
        fun start(selected: Collection<String>, apps: List<AppStorage>): CacheQueue? {
            val byPackage = apps.associateBy { it.packageName }
            val chosen = selected.filter { it in byPackage }.distinct()
            if (chosen.isEmpty()) return null
            val ordered = chosen.sortedWith(
                compareByDescending<String> { byPackage.getValue(it).cacheBytes }.thenBy { it },
            )
            return CacheQueue(ordered, cacheBefore = ordered.sumOf { byPackage.getValue(it).cacheBytes })
        }

        fun decode(text: String?): CacheQueue? {
            if (text.isNullOrEmpty()) return null
            val parts = text.split("|", limit = 4)
            if (parts.size != 4) return null
            val position = parts[0].toIntOrNull() ?: return null
            val before = parts[2].toLongOrNull() ?: return null
            val packages = parts[3].split(",").filter { it.isNotEmpty() }
            if (packages.isEmpty() || position < 0) return null
            return CacheQueue(packages, position.coerceAtMost(packages.size), parts[1] == "1", before)
        }
    }
}

/** Cache bytes of [packages] in a fresh scan ([apps]); apps that are gone count as 0. */
fun cacheOf(packages: Collection<String>, apps: List<AppStorage>): Long {
    val wanted = packages.toSet()
    return apps.filter { it.packageName in wanted }.sumOf { it.cacheBytes }
}
