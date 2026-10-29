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

    /**
     * Load into a recycled view. `onResult` is called on the main thread with true/false when the URL
     * settles, so a card can swap between a title logo and typed fallback text without guessing.
     */
    fun load(view: ImageView, url: String?, targetWidthPx: Int = 420, onResult: ((Boolean) -> Unit)? = null) {
        val clean = url?.trim().orEmpty()
        // RecyclerView rebinds a card every time it leaves and re-enters the window. Re-setting the
        // same bitmap clears the drawable first, which costs a frame and a bitmap upload for nothing.
        if (view.tag == clean && view.drawable != null) { onResult?.invoke(true); return }
        view.tag = clean
        view.setImageDrawable(null)
        if (clean.isEmpty()) { onResult?.invoke(false); return }
        cache.get(clean)?.let {
            view.setImageBitmap(it)
            onResult?.invoke(true)
            return
        }

        pool.execute {
            val bitmap = decode(clean, targetWidthPx)
            if (bitmap != null) cache.put(clean, bitmap)
            main.post {
                if (view.tag == clean) {
                    if (bitmap != null) view.setImageBitmap(bitmap)
                    onResult?.invoke(bitmap != null)
                }
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
