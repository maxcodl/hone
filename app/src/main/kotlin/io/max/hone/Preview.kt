package io.max.hone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.ThumbnailUtils
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
private val VIDEO = setOf("mp4", "mkv", "mov", "webm", "3gp", "m4v")
private val APK = setOf("apk")

private fun ext(path: String) = path.substringAfterLast('.', "").lowercase()

/** Small decoded previews, shared by every list; about 120 KB each. */
private val thumbs = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

/** A preview of the file: the picture, a video frame or the APK's own icon. Null when there is none. */
private fun decode(context: Context, path: String, px: Int): Bitmap? = runCatching {
    when (ext(path)) {
        in IMAGE -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
        in VIDEO -> if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(File(path), Size(px, px), CancellationSignal()) else null
        in APK -> {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return@runCatching null
            info.sourceDir = path
            info.publicSourceDir = path
            val d = info.loadIcon(pm)
            Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, px, px); d.draw(Canvas(it)) }
        }
        else -> null
    }
}.getOrNull()

/** Thumbnail when the file has one, otherwise [fallback] on a round tile. */
@Composable
fun FileThumb(path: String, fallback: ImageVector, size: Dp = 40.dp) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val canPreview = ext(path) in IMAGE || ext(path) in VIDEO || ext(path) in APK
    val bmp by produceState(thumbs.get(path), path) {
        if (value == null && canPreview) {
            value = withContext(Dispatchers.IO) { decode(context, path, 160)?.asImageBitmap()?.also { thumbs.put(path, it) } }
        }
    }
    val b = bmp
    if (b != null) {
        Image(b, null, Modifier.size(size).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(fallback, null, Modifier.size(size / 2), tint = cs.onSurfaceVariant)
        }
    }
}

/** Large preview with the file's details. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreviewDialog(path: String, size: Long, modified: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val canPreview = ext(path) in IMAGE || ext(path) in VIDEO || ext(path) in APK
    val big by produceState<ImageBitmap?>(null, path) {
        if (canPreview) value = withContext(Dispatchers.IO) { decode(context, path, 1080)?.asImageBitmap() }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(path.substringAfterLast('/'), maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val b = big
                when {
                    b != null -> Image(
                        b, null,
                        Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit,
                    )
                    canPreview -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                Text(listOfNotNull(bytes(size), modified).joinToString("  ·  "), style = MaterialTheme.typography.titleSmall)
                Text(path.removePrefix("$ROOT/"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/** Section a cleanup group is split into, by what kind of file it is. */
fun sectionOf(groupId: String, path: String): String {
    if (groupId == "temp") return when {
        path.endsWith("/.thumbnails") || path.contains("/.thumbnails/") -> "Thumbnail caches"
        ext(path) == "log" -> "Logs"
        ext(path) in setOf("part", "crdownload", "partial") -> "Unfinished downloads"
        else -> "Temp files"
    }
    if (groupId == "empty" || groupId == "leftovers") return path.removePrefix("$ROOT/").substringBefore('/')
    return when (ext(path)) {
        in IMAGE, "dng", "raw" -> "Images"
        in VIDEO, "avi", "ts" -> "Videos"
        "mp3", "m4a", "flac", "wav", "ogg", "opus", "aac", "amr" -> "Audio"
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "odt", "csv", "epub" -> "Documents"
        "apk", "apks", "xapk", "apkm" -> "APKs"
        "zip", "rar", "7z", "tar", "gz", "xz", "zst", "bz2" -> "Archives"
        else -> "Other"
    }
}
