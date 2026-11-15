package com.supun.sweeper.scan

import android.system.ErrnoException
import android.system.Os
import com.supun.sweeper.malware.FileStamp
import com.supun.sweeper.malware.FileStampReader
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Size, modified time and change time from `lstat` (links are not followed); the walk's attributes if that fails. */
object OsFileStampReader : FileStampReader {
    override fun read(path: Path, attrs: BasicFileAttributes): FileStamp =
        try {
            val st = Os.lstat(path.toString())
            FileStamp(st.st_size, st.st_mtime * 1000, st.st_ctime * 1000)
        } catch (e: ErrnoException) {
            FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis(), FileStamp.UNKNOWN)
        }
}
