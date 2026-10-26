package com.scenix.tv.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.scenix.tv.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection

/**
 * Our own image loader — no Glide/Coil on a 2 GB 32-bit TV.
 *
 *  * memory cache capped at 40 MB (byte-counted, not entry-counted);
 *  * two low-priority worker threads, so scrolling never competes with the UI thread;
 *  * `HttpURLConnection` with timeouts, redirects and a sane UA;
 *  * downsampled at decode time to the size the card actually draws (`inSampleSize`), then an
 *    exact scale if it is still oversized, so a 3 MB poster never becomes a 3 MB bitmap;
 *  * RGB_565 for small cards (half the bytes), ARGB_8888 for big art where banding would show;
 *  * requests superseded by a recycle are dropped before the network is touched.
 */
class ImageLoader(private val context: Context) {

    private val maxBytes: Int = minOf(MAX_CACHE_BYTES, (Runtime.getRuntime().maxMemory() / 4L).toInt())
    private val main = Handler(Looper.getMainLooper())

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(
            evicted: Boolean, key: String, value: Bitmap, newValue: Bitmap?,
        ) {
            // We never pool bitmaps (no inBitmap reuse in P1); just drop them.
        }
    }

    private val pool = Executors.newFixedThreadPool(THREADS, ThreadFactory { r ->
        Thread(r, "scenix-img").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    })

    private val seq = AtomicInteger(0)

    private class Req(val url: String, val token: Int, val view: ImageView)

    /** Load `url` into `view` sized for a `w`x`h` art box. Null url clears the image. */
    fun load(view: ImageView, url: String?, w: Int, h: Int, background: Int) {
        val token = seq.incrementAndGet()
        view.setTag(R.id.scenix_img_req, token)
        if (url.isNullOrBlank()) {
            view.setImageDrawable(null)
            view.setBackgroundColor(background)
            return
        }
        val key = cacheKey(url, w)
        val hit = cache.get(key)
        if (hit != null && !hit.isRecycled) {
            view.setImageBitmap(hit)
            return
        }
        view.setImageDrawable(null)
        view.setBackgroundColor(background)
        val req = Req(url, token, view)
        try {
            pool.execute { run(req, w, h, key) }
        } catch (t: Throwable) {
            Log.w(TAG, "image queue rejected", t)
        }
    }

    private fun run(req: Req, w: Int, h: Int, key: String) {
        // Superseded by a recycle? Drop it before spending a socket.
        if ((req.view.getTag(R.id.scenix_img_req) as? Int) != req.token) return
        val cached = cache.get(key)
        if (cached != null && !cached.isRecycled) {
            main.post { if (stillCurrent(req)) req.view.setImageBitmap(cached) }
            return
        }
        val bmp = fetch(req.url, w, h)
        if (bmp == null) return
        cache.put(key, bmp)
        main.post { if (stillCurrent(req)) req.view.setImageBitmap(bmp) }
    }

    private fun stillCurrent(req: Req): Boolean =
        (req.view.getTag(R.id.scenix_img_req) as? Int) == req.token

    private fun fetch(url: String, w: Int, h: Int): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 15000
                useCaches = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "image/avif,image/webp,image/png,image/jpeg,*/*")
            }
            if (conn is HttpsURLConnection) conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "image $code $url")
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return null
            decode(bytes, w, h)
        } catch (t: Throwable) {
            Log.w(TAG, "image fetch failed: $url (${t.message})")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun decode(bytes: ByteArray, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2

        val big = w >= BIG_ART_PX
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inPreferredConfig = if (big) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            inDither = !big
        }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        // Exact fit when the pow-two step left us well above the card size.
        val target = if (h > 0 && h > w) w else w
        if (bmp.width > target * 1.35f) {
            val outW = target.coerceAtMost(bmp.width)
            val outH = Math.round(outW.toFloat() * bmp.height / bmp.width).coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, outW, outH, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    /** The system is squeezing us — drop the cache (the TV has ~613 MB free, we do not want a GC cliff). */
    fun trim(keepBytes: Int = maxBytes / 3) {
        cache.trimToSize(keepBytes.coerceAtMost(maxBytes))
    }

    fun stats(): String {
        val count = cache.snapshot().size
        return "${cache.size() / 1024 / 1024} MB / ${maxBytes / 1024 / 1024} MB, $count bitmaps, ${cache.hitCount()} hits"
    }

    private fun cacheKey(url: String, w: Int) = "$url@$w"

    private companion object {
        const val TAG = "ScenixImages"
        const val MAX_CACHE_BYTES = 40 * 1024 * 1024   // the 40 MB cap
        const val THREADS = 2
        const val BIG_ART_PX = 700
        const val UA = "Scenix/0.1 (Android TV)"
    }
}
