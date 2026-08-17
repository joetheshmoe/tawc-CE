package me.phie.tawc.install

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runtime icon loader for the Flatpak store: downloads Flathub's app
 * icons on first view, caches them to `filesDir/flatpak-icons` on disk,
 * and holds decoded bitmaps in a bounded [LruCache]. Mirrors the launcher's
 * [me.phie.tawc.launcher.IconLoader] concurrency guard (ImageView.tag
 * re-check before applying) but fetches over HTTP instead of reading a
 * local path.
 */
class FlatpakIconLoader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sizePx: Int,
) {
    private val dir = File(context.filesDir, "flatpak-icons")

    private val cache = object : LruCache<String, Bitmap>(budgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun load(app: FlatpakApp, target: ImageView, fallbackRes: Int = 0) {
        val url = app.iconUrl
        if (url == null) {
            applyFallback(target, fallbackRes)
            target.tag = null
            return
        }
        cache.get(url)?.let {
            target.setImageBitmap(it)
            target.tag = url
            return
        }
        target.setImageDrawable(null)
        target.tag = url
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { loadBitmap(url) }
            if (target.tag != url) return@launch
            if (bmp == null) {
                applyFallback(target, fallbackRes)
                return@launch
            }
            cache.put(url, bmp)
            target.setImageBitmap(bmp)
        }
    }

    private fun applyFallback(target: ImageView, fallbackRes: Int) {
        if (fallbackRes != 0) target.setImageResource(fallbackRes) else target.setImageDrawable(null)
    }

    private fun loadBitmap(url: String): Bitmap? {
        val file = File(dir, Math.abs(url.hashCode()).toString() + ".png")
        if (file.isFile) return decode(file)
        val tmp = File(dir, file.name + ".part")
        return try {
            dir.mkdirs()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            } finally {
                conn.disconnect()
            }
            if (!tmp.renameTo(file)) return null
            decode(file)
        } catch (e: Exception) {
            tmp.delete()
            null
        }
    }

    private fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        val shorter = minOf(bounds.outWidth, bounds.outHeight)
        while (shorter / (sample * 2) >= sizePx) sample *= 2
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun budgetBytes(): Int {
        val perIcon = 4L * sizePx * sizePx * 2
        val floor = 32L * perIcon
        return maxOf(Runtime.getRuntime().maxMemory() / 8, floor)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
