package com.supun.sweeper.storage

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import com.supun.sweeper.files.ImagePlan
import com.supun.sweeper.files.OpenCheck
import com.supun.sweeper.files.OpenRules
import com.supun.sweeper.files.PathGuard
import com.supun.sweeper.files.PreviewKind
import com.supun.sweeper.files.PreviewRules
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the preview screen shows for a file. Everything here was read-only and size-capped. */
sealed interface PreviewContent {
    data class Image(val bitmap: Bitmap) : PreviewContent
    data class Text(val text: String, val truncated: Boolean) : PreviewContent
    data class Pdf(val page: Bitmap, val pageCount: Int) : PreviewContent
    data class Video(val frame: Bitmap?, val durationMillis: Long?) : PreviewContent
    data class Audio(val title: String?, val artist: String?, val durationMillis: Long?) : PreviewContent
    data class Apk(
        val label: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val icon: Bitmap?,
        val installed: Boolean,
    ) : PreviewContent

    /** Other types, and files over the size caps: the details block is all there is. */
    data class DetailsOnly(val reason: String?) : PreviewContent

    /** Missing, not allowed, corrupt or unreadable: "Can't preview this file". */
    data class Failed(val message: String) : PreviewContent
}

/**
 * Reads a file for the in-app preview. Read-only: the path is re-checked with the PathGuard rules first, nothing
 * is written or run, every read has a cap, and any failure turns into [PreviewContent.Failed].
 */
object FilePreviewer {
    const val CANT_PREVIEW = "Can't preview this file"

    suspend fun load(context: Context, path: String, screenWidth: Int, screenHeight: Int): PreviewContent =
        withContext(Dispatchers.IO) {
            try {
                loadChecked(context.applicationContext, path, screenWidth, screenHeight)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PreviewContent.Failed(CANT_PREVIEW)
            } catch (e: OutOfMemoryError) {
                PreviewContent.Failed(CANT_PREVIEW)
            }
        }

    private fun loadChecked(context: Context, path: String, screenWidth: Int, screenHeight: Int): PreviewContent {
        val file = when (val check = OpenRules.check(File(path), PathGuard(storageRoot()))) {
            is OpenCheck.Allowed -> check.file
            OpenCheck.Missing -> return PreviewContent.Failed("This file no longer exists")
            OpenCheck.NotAllowed -> return PreviewContent.Failed("This file can't be previewed from here")
        }
        val kind = PreviewRules.kindOf(file.name)
        val size = file.length()
        if (!PreviewRules.withinSizeCap(kind, size)) {
            return PreviewContent.DetailsOnly("This file is too big to preview")
        }
        return when (kind) {
            PreviewKind.Image -> image(file, screenWidth, screenHeight)
            PreviewKind.Text -> text(file, size)
            PreviewKind.Pdf -> pdf(file, screenWidth, screenHeight)
            PreviewKind.Video -> video(file)
            PreviewKind.Audio -> audio(file)
            PreviewKind.Apk -> apk(context, file)
            PreviewKind.Other -> PreviewContent.DetailsOnly(null)
        }
    }

    private fun failed() = PreviewContent.Failed(CANT_PREVIEW)

    private fun image(file: File, screenWidth: Int, screenHeight: Int): PreviewContent {
        // Header first: the picture is only decoded when the plan says it is safe, downscaled to the screen.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val plan = PreviewRules.imagePlan(bounds.outWidth, bounds.outHeight, screenWidth, screenHeight)
        if (plan !is ImagePlan.Decode) {
            return if (bounds.outWidth <= 0 || bounds.outHeight <= 0) failed()
            else PreviewContent.DetailsOnly("This picture is too big to preview")
        }
        val options = BitmapFactory.Options().apply { inSampleSize = plan.sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return failed()
        return PreviewContent.Image(bitmap)
    }

    private fun text(file: File, size: Long): PreviewContent {
        val buffer = ByteArray(PreviewRules.TEXT_READ_LIMIT)
        var read = 0
        FileInputStream(file).use { input ->
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
        }
        val preview = PreviewRules.decodeText(buffer, read, size) ?: return failed()
        return PreviewContent.Text(preview.text, preview.truncated)
    }

    private fun pdf(file: File, screenWidth: Int, screenHeight: Int): PreviewContent {
        // Closed by hand: PdfRenderer only became Closeable on Android 15, so `use` would crash on older phones.
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            val renderer = PdfRenderer(pfd)
            try {
                if (renderer.pageCount < 1) return failed()
                val page = renderer.openPage(0)
                try {
                    val target = PreviewRules.pdfRenderSize(page.width, page.height, screenWidth, screenHeight)
                        ?: return failed()
                    val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // PdfRenderer draws on whatever is there
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return PreviewContent.Pdf(bitmap, renderer.pageCount)
                } finally {
                    page.close()
                }
            } finally {
                renderer.close()
            }
        }
    }

    private fun video(file: File): PreviewContent {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val frame = retriever.getFrameAtTime(-1)
            if (frame == null && duration == null) return failed()
            return PreviewContent.Video(frame, duration)
        } finally {
            retriever.release()
        }
    }

    private fun audio(file: File): PreviewContent {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            if (duration == null && title == null) return failed()
            return PreviewContent.Audio(title, artist, duration)
        } finally {
            retriever.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun apk(context: Context, file: File): PreviewContent {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(file.path, 0) ?: return failed()
        val appInfo = info.applicationInfo ?: return failed()
        appInfo.sourceDir = file.path
        appInfo.publicSourceDir = file.path
        val label = appInfo.loadLabel(pm).toString().ifBlank { info.packageName }
        val icon = try {
            appInfo.loadIcon(pm).toBitmap(ICON_PX, ICON_PX)
        } catch (e: Exception) {
            null
        }
        val installed = try {
            PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(info.packageName, 0)) >=
                PackageInfoCompat.getLongVersionCode(info)
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return PreviewContent.Apk(
            label = label,
            packageName = info.packageName,
            versionName = info.versionName ?: "",
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            icon = icon,
            installed = installed,
        )
    }

    private const val ICON_PX = 192
}
