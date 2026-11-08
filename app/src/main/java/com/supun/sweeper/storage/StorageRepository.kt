package com.supun.sweeper.storage

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Reads storage sizes from Android. Only reads; never changes or deletes anything. */
class StorageRepository(context: Context) {
    private val appContext = context.applicationContext
    private val statsManager: StorageStatsManager =
        appContext.getSystemService(StorageStatsManager::class.java)

    /** Internal volume size and free space; falls back to StatFs if the stats service fails. */
    suspend fun internalVolume(): VolumeInfo = withContext(Dispatchers.IO) {
        try {
            VolumeInfo(
                total = statsManager.getTotalBytes(StorageManager.UUID_DEFAULT),
                free = statsManager.getFreeBytes(StorageManager.UUID_DEFAULT),
            )
        } catch (e: Exception) {
            statFsVolume()
        }
    }

    /**
     * Sizes of every installed app. Apps whose stats cannot be read are skipped.
     * [onProgress] is called from a background thread after each app.
     */
    suspend fun apps(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<AppStorage> =
        withContext(Dispatchers.IO) {
            val pm = appContext.packageManager
            val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            val user = Process.myUserHandle()
            val result = ArrayList<AppStorage>(installed.size)
            installed.forEachIndexed { index, info ->
                ensureActive()
                try {
                    val stats = statsManager.queryStatsForPackage(
                        StorageManager.UUID_DEFAULT,
                        info.packageName,
                        user,
                    )
                    val cache = stats.cacheBytes.coerceAtLeast(0)
                    result.add(
                        AppStorage(
                            packageName = info.packageName,
                            label = info.loadLabel(pm).toString(),
                            isSystem = isSystemApp(info.flags),
                            appBytes = stats.appBytes.coerceAtLeast(0),
                            // Android's dataBytes already includes the cache; keep the parts disjoint.
                            dataBytes = (stats.dataBytes - cache).coerceAtLeast(0),
                            cacheBytes = cache,
                        ),
                    )
                } catch (e: Exception) {
                    // NameNotFound, SecurityException, IOException: skip this app.
                }
                onProgress(index + 1, installed.size)
            }
            result
        }

    /** Category split of used space. Pass [knownApps] to avoid scanning the apps again. */
    suspend fun breakdown(knownApps: List<AppStorage>? = null): StorageBreakdown {
        val volume = internalVolume()
        val appList = knownApps ?: apps()
        val external = withContext(Dispatchers.IO) {
            try {
                statsManager.queryExternalStatsForUser(StorageManager.UUID_DEFAULT, Process.myUserHandle())
            } catch (e: Exception) {
                null
            }
        }
        return StorageBreakdown(
            total = volume.total,
            used = volume.used,
            apps = appList.sumOf { it.totalBytes },
            images = external?.imageBytes?.coerceAtLeast(0) ?: 0,
            video = external?.videoBytes?.coerceAtLeast(0) ?: 0,
            audio = external?.audioBytes?.coerceAtLeast(0) ?: 0,
        )
    }

    companion object {
        /** A system app that the user has not updated. Updated system apps count as user apps. */
        internal fun isSystemApp(flags: Int): Boolean =
            flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0

        /** The data partition via StatFs (also backs /sdcard on modern phones). */
        fun statFsVolume(): VolumeInfo {
            val stat = StatFs(Environment.getDataDirectory().path)
            return VolumeInfo(total = stat.totalBytes, free = stat.availableBytes)
        }
    }
}
