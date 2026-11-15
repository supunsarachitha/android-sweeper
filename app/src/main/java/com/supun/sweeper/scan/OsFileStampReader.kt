package com.supun.sweeper.scan

import android.annotation.TargetApi
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.StructTimespec
import com.supun.sweeper.malware.FileStamp
import com.supun.sweeper.malware.FileStampReader
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Size, modified time and change time from `lstat` (links are not followed); the walk's attributes if that fails.
 * Android 8.1+ gives the times with sub-second precision; Android 8.0 only in whole seconds (the cache re-reads
 * files hashed within a few seconds of a change, see HASH_CACHE_RACY_MILLIS).
 */
object OsFileStampReader : FileStampReader {
    override fun read(path: Path, attrs: BasicFileAttributes): FileStamp =
        try {
            val st = Os.lstat(path.toString())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                FileStamp(st.st_size, st.st_mtim.millis(), st.st_ctim.millis())
            } else {
                FileStamp(st.st_size, st.st_mtime * 1000, st.st_ctime * 1000)
            }
        } catch (e: ErrnoException) {
            FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis(), FileStamp.UNKNOWN)
        }
}

@TargetApi(Build.VERSION_CODES.O_MR1) // StructTimespec (st_mtim, st_ctim) is Android 8.1+; callers check
private fun StructTimespec.millis(): Long = tv_sec * 1000 + tv_nsec / 1_000_000
