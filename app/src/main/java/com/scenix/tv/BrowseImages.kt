package com.scenix.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The native screen's picture loader: bounded memory, a handful of workers, and a tag so a recycled
 * card cannot receive the previous title's poster. Decoding happens off the UI thread; a missing or
 * slow image leaves the gradient placeholder, never blocks focus.
 *
 * Two rules keep a 1 GB panel responsive:
 *
 *  - `sizedUrl` asks the CDN for the picture the tile actually needs. Asking for the largest and
 *    downsampling cost the network 25–40× more per tile: a 232 px preview strip pulled 1920×1080
 *    backdrops (560 KB each), and a 280 px card pulled an 1800×2667 TMDB `original` (1 MB). Sizing
 *    at the edge is also why those strips used to starve every poster on the page.
 *  - The queue is newest-first and every job re-checks its view's tag before spending a byte. A
 *    fast scroll used to leave a tail of stale downloads running in front of what was on screen.
 */
object BrowseImages {

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    // A tiny image must never queue behind a shelf of full decodes: its whole job is to arrive
    // first. One worker, a small request and an LruCache entry are enough.
    private val tinyPool = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, LinkedBlockingDeque()
    ) { r -> Thread(r, "scenix-image-tiny").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }
    // Three workers keep artwork loading progressive without competing with focus and layout on the
    // main render thread. Each card is a 20–40 KB CDN copy, so a cold row of six paints in about two
    // round trips instead of three; the queue is LIFO, so the card the viewer just scrolled to is
    // always the newest request and runs first, instead of waiting behind the tiles that already
    // left the screen.
    private val pool = ThreadPoolExecutor(
        3, 3, 30, TimeUnit.SECONDS, NewestFirstQueue()
    ).apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())

    private val tmdbSized = Regex("(image\\.tmdb\\.org/t/p/)(w\\d+|original)(/)")
    private val metahubPoster = Regex("(images\\.metahub\\.space/poster/)(small|medium|large)(/)")
    private val metahubBackdrop = Regex("(images\\.metahub\\.space/background/)(small|medium|large)(/)")
    private val metahubLogo = Regex("(images\\.metahub\\.space/logo/)(small|medium|large)(/)")
    private val episodeStill = Regex("(episodes\\.metahub\\.space/[^/]+/\\d+/\\d+/)(w\\d+|medium|large)(\\.jpg)")
    // The Games catalogue's art is Wikipedia fair-use covers. The `original` upload host serves a
    // 1.4 MB PNG per tile and 429-throttles the second tile, so the whole shelf looked blank. The
    // thumb variant is the same picture at the width the tile actually draws.
    // Wikimedia's path hash is `<c>/<c1c2>/` — one char, then two.
    private val wikiHash = "(?:[0-9a-f]/[0-9a-f]{2}/)?"
    private val wikiFull =
        Regex("(upload\\.wikimedia\\.org/wikipedia/([a-z-]+)/$wikiHash)([^/?#]+)(\\?[^#]*)?$")
    private val wikiThumb =
        Regex("(upload\\.wikimedia\\.org/wikipedia/([a-z-]+)/thumb/$wikiHash[^/]+/)(\\d+)px-")

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
        // A blurred lick of the artwork while the full frame is in flight, so the tile is never a
        // black hole on a cold scroll. Only worth a second request when the final picture is large:
        // a card's own 300–500 px copy already lands in one round trip.
        if (targetWidthPx > 480) {
            val small = sizedUrl(clean, 200)
            if (small != clean) {
                val tinyKey = "$small@tiny"
                val ready = cache.get(tinyKey)
                if (ready != null) {
                    view.setImageBitmap(ready)
                } else {
                    tinyPool.execute {
                        if (view.tag != requestKey) return@execute
                        val bmp = decode(small, 200)
                            ?: if (small != clean) decode(clean, 200) else null
                        if (bmp != null) {
                            cache.put(tinyKey, bmp)
                            main.post { if (view.tag == requestKey) view.setImageBitmap(bmp) }
                        }
                    }
                }
            }
        }

        val sized = sizedUrl(clean, targetWidthPx)
        pool.execute {
            // The card was rebound before this job reached a worker: fetching it now would only
            // spend bandwidth the viewer has already scrolled away from.
            if (view.tag != requestKey) return@execute
            // A CDN that does not publish the requested width (a small poster on an obscure title)
            // must not leave a blank tile: one retry at the URL the provider actually gave us.
            // Wikimedia answers 400 for widths that file's ladder does not publish, so its own
            // ladder runs first and the original is the last resort, not the first one.
            var bitmap: Bitmap? = decode(sized, targetWidthPx)
            for (alt in alternatesFor(clean, sized)) {
                if (bitmap != null) break
                bitmap = decode(alt, targetWidthPx)
            }
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
     * Retry URLs for a sized request the CDN refused. Wikimedia publishes a per-file width ladder
     * and answers 400 for anything off it, so walk the ladder down before falling back to the
     * original — the original is 6-10x the bytes and the reason whole Game shelves went blank.
     */
    private fun alternatesFor(clean: String, sized: String): List<String> {
        val out = ArrayList<String>(8)
        if (wikiFull.containsMatchIn(clean) || wikiThumb.containsMatchIn(clean)) {
            val seen = HashSet<String>()
            for (w in intArrayOf(1280, 1024, 800, 640, 500, 330, 250)) {
                val candidate = sizedUrl(clean, w)
                if (candidate != sized && candidate != clean && seen.add(candidate)) out.add(candidate)
            }
        }
        if (clean != sized) out.add(clean)
        return out
    }

    /**
     * The CDN's copy of this picture at the width this tile can actually show. TMDB and MetaHub
     * publish every poster/backdrop/logo/still at several widths; anything else keeps its full URL
     * and simply shows the gradient until it lands.
     */
    fun sizedUrl(url: String, targetWidthPx: Int): String {
        val t = targetWidthPx.coerceAtLeast(1)
        if (tmdbSized.containsMatchIn(url)) {
            val tier = when {
                t <= 200 -> "w185"
                t <= 400 -> "w342"
                t <= 620 -> "w500"
                t <= 900 -> "w780"
                t <= 1500 -> "w1280"
                else -> "original"
            }
            return tmdbSized.replace(url, "$1$tier$3")
        }
        if (metahubPoster.containsMatchIn(url)) {
            return metahubPoster.replace(url, "$1" + (if (t <= 340) "small" else "medium") + "$3")
        }
        if (metahubBackdrop.containsMatchIn(url)) {
            return metahubBackdrop.replace(url, "$1" + (if (t <= 640) "small" else "medium") + "$3")
        }
        if (metahubLogo.containsMatchIn(url)) {
            return metahubLogo.replace(url, "$1" + (if (t <= 400) "small" else "medium") + "$3")
        }
        if (episodeStill.containsMatchIn(url)) {
            return episodeStill.replace(url, "$1" + (if (t <= 360) "w300" else "w780") + "$3")
        }
        // Already a thumb variant? Retarget its width rather than stacking /thumb/ twice.
        // Wikimedia rejects arbitrary thumb widths ("use thumbnail sizes listed…"), so the request
        // must come from their ladder. Fair-use art lives under a language path and tops out at
        // 500px; free files under /commons/ go up to 1280px.
        val wikiLang = (wikiThumb.find(url) ?: wikiFull.find(url))?.groupValues?.get(2)
        val wikiWidth = when {
            wikiLang == "commons" -> when {
                t <= 360 -> 320
                t <= 700 -> 640
                else -> 1280
            }
            t <= 300 -> 250
            else -> 500
        }
        wikiThumb.find(url)?.let { m ->
            val width = wikiWidth
            val rest = url.substring(m.range.last + 1)   // the filename after `px-`
            return url.substring(0, m.range.first) + m.groupValues[1] + width + "px-" + rest
        }
        wikiFull.find(url)?.let { m ->
            val file = m.groupValues[3]
            if (file.isBlank()) return@let
            val width = wikiWidth
            val tail = if (file.endsWith(".svg")) ".svg.png" else ""
            // groupValues[1] is `upload.wikimedia.org/wikipedia/en/` plus any a/b/c hash dir; the
            // thumb form inserts `thumb/` after the language segment and repeats the filename.
            val head = m.groupValues[1].removePrefix("upload.wikimedia.org/wikipedia/")
            val lang = head.substringBefore('/')
            val rest = head.removePrefix("$lang/")
            val base = url.substring(0, url.indexOf("upload.wikimedia.org"))
            return "${base}upload.wikimedia.org/wikipedia/$lang/thumb/$rest$file/${width}px-$file$tail" +
                m.groupValues[4]
        }
        return url
    }

    /**
     * A blurred/tiny stand-in for glass cards while the real poster flies. Only TMDB publishes a
     * 92 px copy, and a URL that does not change is not worth a second request.
     */
    private fun tinyUrl(url: String): String? {
        if (!tmdbSized.containsMatchIn(url)) return null
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
                // Wikimedia's robot policy 429s a bare client UA; a declared purpose + contact URL
                // is what their image hosts accept. Other CDNs ignore it.
                conn.setRequestProperty(
                    "User-Agent",
                    "Scenix/0.2 (self-hosted media hub; https://github.com/praveshkhatana/Scenix)",
                )
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

/**
 * A blocking deque the pool takes from the head, so the most recent request is served first. The
 * executor only ever calls `offer`, and a rebinding screen always enqueues newer work than the
 * tiles it just left behind.
 */
private class NewestFirstQueue : LinkedBlockingDeque<Runnable>() {
    override fun offer(e: Runnable): Boolean = offerFirst(e)
}
