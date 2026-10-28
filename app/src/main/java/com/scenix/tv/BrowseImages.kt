package com.scenix.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The native screen's picture loader: bounded memory, a handful of workers, and a tag so a recycled
 * card cannot receive the previous title's poster. Decoding happens off the UI thread; a missing or
 * slow image leaves the gradient placeholder, never blocks focus.
 */
object BrowseImages {

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "scenix-image").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String?, targetWidthPx: Int = 420) {
        val clean = url?.trim().orEmpty()
        view.tag = clean
        view.setImageDrawable(null)
        if (clean.isEmpty()) return
        cache.get(clean)?.let { view.setImageBitmap(it); return }

        pool.execute {
            val bitmap = decode(clean, targetWidthPx)
            if (bitmap != null) cache.put(clean, bitmap)
            main.post {
                if (view.tag == clean && bitmap != null) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun decode(url: String, targetWidthPx: Int): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Scenix/0.2 (Android TV)")
            if (conn.responseCode !in 200..299) null else {
                // One download, two decodes: bounds first, then the sampled bitmap. Opening the URL
                // twice doubled the network cost of every card on a cold shelf.
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size > 12 * 1024 * 1024) null else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    while (opts.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
                    val decode = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
                }
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        null
    }
}
