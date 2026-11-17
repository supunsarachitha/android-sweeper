package com.supun.sweeper.files

import java.io.File

const val APK_MIME = "application/vnd.android.package-archive"
const val UNKNOWN_MIME = "*/*"

/** Why a file may or may not be handed to another app. */
sealed interface OpenCheck {
    /** Safe to open: [file] is the canonical path. */
    data class Allowed(val file: File) : OpenCheck

    /** The file is gone (or is no longer a regular file). */
    data object Missing : OpenCheck

    /** Outside the storage root, a symlink, in the trash or in Android/data|obb. */
    data object NotAllowed : OpenCheck
}

/** Pure rules for opening a file in another app; no Android classes so they run in JVM tests. */
object OpenRules {
    /** Lower-case extension without the dot, or "" when the name has none. */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    fun isApk(name: String): Boolean = extensionOf(name) == "apk"

    /**
     * MIME type for [name]. APK is fixed; otherwise [systemLookup] (Android's MimeTypeMap) is asked first,
     * then a small built-in table, and last "*/*".
     */
    fun mimeType(name: String, systemLookup: (String) -> String? = { null }): String {
        val ext = extensionOf(name)
        if (ext.isEmpty()) return UNKNOWN_MIME
        if (ext == "apk") return APK_MIME
        val fromSystem = systemLookup(ext)
        if (!fromSystem.isNullOrBlank()) return fromSystem
        return BUILT_IN[ext] ?: UNKNOWN_MIME
    }

    /**
     * Re-checks [file] right before it is opened: it must still exist and pass the same [PathGuard] rules
     * as every removal (canonical inside the storage root, no symlink, not the trash, not Android/data|obb).
     */
    fun check(file: File, guard: PathGuard): OpenCheck {
        if (!file.exists()) return OpenCheck.Missing
        val canonical = guard.validate(file) ?: return OpenCheck.NotAllowed
        return OpenCheck.Allowed(canonical)
    }

    private val BUILT_IN = mapOf(
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "log" to "text/plain",
        "csv" to "text/csv",
        "html" to "text/html",
        "htm" to "text/html",
        "xml" to "text/xml",
        "json" to "application/json",
        "zip" to "application/zip",
        "gz" to "application/gzip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "wav" to "audio/x-wav",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}

/** Result of trying to open a file; [message] is what the user sees, null when an app was started. */
enum class OpenResult(val message: String?) {
    Started(null),
    NoApp("No app on this phone can open this file"),
    Missing("This file no longer exists"),
    NotAllowed("This file can't be opened from here"),
    Failed("Couldn't open this file"),
}
