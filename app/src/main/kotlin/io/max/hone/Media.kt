package io.max.hone

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Photo(val id: Long, val path: String, val bytes: Long, val taken: Long, val pixels: Long)

/**
 * Near-identical photos: shots taken within two minutes of the one before whose 64-bit difference
 * hashes are close. The biggest of each set (most pixels, then most bytes) is kept, the rest offered.
 */
suspend fun similarPhotos(context: Context, progress: (Float) -> Unit): CleanGroup = withContext(Dispatchers.IO) {
    val group = { files: List<FileItem> ->
        CleanGroup(
            "similar", "Similar photos", "Near-identical shots taken moments apart. The largest of each set is kept.",
            files.sumOf { it.bytes }, files.size, files.sortedByDescending { it.bytes },
        )
    }
    if (Build.VERSION.SDK_INT < 29) return@withContext group(emptyList())
    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val photos = mutableListOf<Photo>()
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE 'DCIM/%' OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE 'Pictures/%'",
        null, "${MediaStore.Images.Media.DATE_TAKEN} ASC",
    )?.use { c ->
        while (c.moveToNext()) {
            val path = c.getString(1) ?: continue
            if (path.contains("/.HoneTrash/") || path.contains("/Screenshots/")) continue
            photos += Photo(c.getLong(0), path, c.getLong(2), c.getLong(3), c.getLong(4) * c.getLong(5))
        }
    }
    val hashes = arrayOfNulls<Long>(photos.size)
    fun hash(i: Int): Long? = hashes[i] ?: runCatching {
        context.contentResolver.loadThumbnail(ContentUris.withAppendedId(uri, photos[i].id), Size(96, 96), null).let { dHash(it) }
    }.getOrNull().also { hashes[i] = it }

    val offered = mutableListOf<FileItem>()
    var set = mutableListOf<Int>()
    fun closeSet() {
        if (set.size > 1) {
            val keep = set.maxWith(compareBy({ photos[it].pixels }, { photos[it].bytes }))
            set.filter { it != keep }.forEach { offered += FileItem(photos[it].path, photos[it].bytes, photos[it].taken / 1000) }
        }
        set = mutableListOf()
    }
    for (i in photos.indices) {
        val prev = set.lastOrNull()
        // Only neighbours in time are hashed: most photos have none and cost nothing.
        val near = prev != null && photos[i].taken - photos[prev].taken <= 120_000 && photos[i].taken > 0
        val same = near && hash(prev!!).let { a -> hash(i).let { b -> a != null && b != null && java.lang.Long.bitCount(a xor b) <= 8 } }
        if (!same) closeSet()
        set += i
        if (i % 50 == 0) progress(i.toFloat() / photos.size)
    }
    closeSet()
    progress(1f)
    group(offered)
}

/** Difference hash: a 9x8 grey thumbnail, one bit per pair of neighbours. Same picture, same bits. */
fun dHash(bitmap: Bitmap): Long {
    val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    var h = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
        h = h shl 1
        if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) h = h or 1
    }
    return h
}

private fun luma(c: Int) = ((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114

/** Mobile and Wi-Fi bytes per app over the last 30 days; needs usage access, not the shell. */
data class DataUse(val mobile: Long, val wifi: Long) {
    val total get() = mobile + wifi
}

suspend fun dataUsage(context: Context): Map<String, DataUse> = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val pm = context.packageManager
    buildMap {
        for ((uid, t) in Net.byUid(context, now - 30L * 86_400_000, now)) {
            val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: continue
            put(pkg, DataUse(t.mobile, t.wifi))
        }
    }
}
