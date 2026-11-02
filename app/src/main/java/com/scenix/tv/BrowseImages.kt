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
    // Four simultaneous JPEG decodes were enough to monopolise the emulator/low-end TV cores
    // exactly while a remote press needed a frame. Two workers keep artwork loading progressive
    // without competing with focus and layout on the main render thread.
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "scenix-image").apply { isDaemon = true } }
    // A tiny image must never queue behind a shelf of full decodes: its whole job is to arrive
    // first. One worker, a ~5 KB request and an LruCache entry are enough.
    private val tinyPool = Executors.newFixedThreadPool(1) { r -> Thread(r, "scenix-image-tiny").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val tmdbSized = Regex("(image\\.tmdb\\.org/t/p/)(w\\d+|original)(/)")

    /**
     * Load into a recycled view. `onResult` is called on the main thread with true/false when the URL
     * settles, so a card can swap between a title logo and typed fallback text without guessing.
     */
    fun load(view: ImageView, url: String?, targetWidthPx: Int = 420, onResult: ((Boolean) -> Unit)? = null) {
        val clean = url?.trim().orEmpty()
        // A scene backdrop deliberately uses a small sampled bitmap while the hero uses a sharp
        // one. Cache those as distinct variants: a low-resolution stage image must never replace
        // the hero/card bitmap just because it arrived first.
        val requestKey = cacheKey(clean, targetWidthPx)
        // RecyclerView rebinds a card every time it leaves and re-enters the window. Re-setting the
        // same bitmap clears the drawable first, which costs a frame and a bitmap upload for nothing.
        if (view.tag == requestKey && view.drawable != null) { onResult?.invoke(true); return }
        view.tag = requestKey
        if (clean.isEmpty()) {
            view.setImageDrawable(null)
            onResult?.invoke(false)
            return
        }
        cache.get(requestKey)?.let {
            // Do not clear a cached bitmap first. That produces a black flash and a second
            // invalidation during a hero LEFT/RIGHT step even though the image is ready now.
            view.setImageBitmap(it)
            onResult?.invoke(true)
            return
        }
        view.setImageDrawable(null)
        // A blurred lick of the artwork while the full frame is in flight. TMDB serves the same
        // picture at 92 px for a few kilobytes, so the tile is never a black hole on a cold scroll.
        tinyUrl(clean)?.let { small ->
            val tinyKey = "$small@tiny"
            val ready = cache.get(tinyKey)
            if (ready != null) {
                view.setImageBitmap(ready)
            } else {
                tinyPool.execute {
                    val bmp = decode(small, 24)
                    if (bmp != null) {
                        cache.put(tinyKey, bmp)
                        main.post { if (view.tag == requestKey) view.setImageBitmap(bmp) }
                    }
                }
            }
        }

        pool.execute {
            // Fetch at the size the card will actually show: TMDB serves the same picture at
            // w300/w500/w780 for a fraction of the `original` bytes, so a 283px tile never
            // downloads a 6.8 MB poster. (Decoding was already sampled; this trims the wire.)
            val bitmap = decode(sizedUrl(clean, targetWidthPx), targetWidthPx)
            if (bitmap != null) cache.put(requestKey, bitmap)
            main.post {
                if (view.tag == requestKey) {
                    if (bitmap != null) {
                        // Art that arrives after the placeholder was placed should fade up into it
                        // (opacity only, entry family), not pop into existence mid-scroll.
                        val wasEmpty = view.drawable == null
                        view.setImageBitmap(bitmap)
                        if (wasEmpty) {
                            view.alpha = 0f
                            view.animate().alpha(1f).setDuration(240).start()
                        }
                    }
                    onResult?.invoke(bitmap != null)
                }
            }
        }
    }

    /**
     * The same picture at a size that is almost free. TMDB is the household's dominant source;
     * anything else keeps its full URL and simply shows the gradient until it lands.
     */
    /** TMDB width variant nearest the card's real width; non-TMDB URLs keep their own size. */
    private fun sizedUrl(url: String, targetWidthPx: Int): String {
        if (!url.contains("image.tmdb.org/t/p/")) return url
        val w = when {
            targetWidthPx <= 100 -> 154
            targetWidthPx <= 170 -> 185
            targetWidthPx <= 280 -> 300
            targetWidthPx <= 440 -> 500
            targetWidthPx <= 700 -> 780
            else -> 1280
        }
        return tmdbSized.replace(url, "$1w$w$3")
    }

    private fun tinyUrl(url: String): String? {
        if (!url.contains("image.tmdb.org/t/p/")) return null
        val small = tmdbSized.replace(url, "$1w92$3")
        return if (small != url) small else null
    }

    private fun cacheKey(url: String, targetWidthPx: Int): String {
        // Bucket widths so close layout measurements reuse a decode while a 360px atmosphere
        // image and a 1100px hero still remain separate assets.
        val bucket = ((targetWidthPx.coerceAtLeast(1) + 79) / 80) * 80
        return "$url@$bucket"
    }

    /**
     * Fetch with an explicit redirect hop chain. `instanceFollowRedirects` silently refuses
     * cross-HOST redirects, and the hub's title logos live on image CDNs that answer with exactly
     * that — images.metahub.space 307s to live.metahub.space, so every logo came back undecoded
     * and plates kept their fallback text (owner ask, 2026-11-02). http(s) targets only, bounded
     * hops, relative Location headers resolved against the current URL.
     */
    private fun fetchBytes(url: String): ByteArray? {
        var current = url
        var hop = 0
        while (hop++ < 5) {
            val conn = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (t: Throwable) {
                return null
            }
            try {
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Scenix/0.2 (Android TV)")
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.headerFields["Location"]?.firstOrNull()
                        ?: conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) return null
                    current = try { URL(URL(current), loc).toString() } catch (t: Throwable) { return null }
                    if (!current.startsWith("http")) return null
                    continue
                }
                if (code !in 200..299) return null
                // One download, two decodes: bounds first, then the sampled bitmap. Opening the URL
                // twice doubled the network cost of every card on a cold shelf.
                return conn.inputStream.use { it.readBytes() }
            } catch (t: Throwable) {
                return null
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun decode(url: String, targetWidthPx: Int): Bitmap? {
        return try {
            val bytes = fetchBytes(url) ?: return null
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
        } catch (t: Throwable) {
            null
        }
    }
}
