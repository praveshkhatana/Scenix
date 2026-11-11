package com.scenix.tv.core

import android.graphics.Bitmap
import android.graphics.Color
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Phone pairing: the TV shows a QR that puts the hub's remote page in a phone's hands.
 *
 * Two backends, two URLs:
 *  - Standalone: the TV itself hosts the web UI on 0.0.0.0:8787 (LoopbackServer).
 *    The phone opens http://<tv-lan-ip>:8787/ and the TV answers UI files from assets/web/.
 *  - Hub: the TV is paired to a hub (typically :8788). The phone opens the hub URL; its Settings
 *    stay guarded by the household PIN (POST /app/v1/settings/unlock ->
 *    X-Scenix-Admin-Session, see hub/web/js/adminlock.js).
 *
 * The QR itself is drawn by [QrCode], the app's own encoder, so the APK has no barcode library.
 * The pairing URL is still printed under the code: a camera that refuses the screen's glare must
 * not strand the person holding the phone.
 */
object PhonePairing {
    const val STANDALONE_PORT = 8787
    const val HUB_PORT = 8788

    /** Normalise a user/persisted hub URL to an origin without trailing slash, or null. */
    @JvmStatic
    fun normaliseOrigin(raw: String?): String? {
        var u = raw?.trim()?.trimEnd('/') ?: return null
        if (u.isEmpty()) return null
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
        val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (host.isBlank()) return null
        val scheme = if (uri.scheme.equals("https", true)) "https" else "http"
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }

    /** First non-loopback site-local IPv4, or null when the TV is offline. */
    @JvmStatic
    fun lanIp(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { n -> Collections.list(n.inetAddresses).asSequence() }
            .firstOrNull { a -> a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Standalone pairing URL; null when no LAN IP can be enumerated. */
    @JvmStatic
    fun standaloneUrl(lanIp: String?, port: Int = STANDALONE_PORT): String? {
        val ip = lanIp?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "http://$ip:$port"
    }

    /**
     * The URL the QR encodes.
     *  - standalone=true: http://<lanIp>:8787
     *  - hub mode: the persisted/selected hub origin (Prefs.hubUrl / HubEndpoint.base result)
     */
    @JvmStatic
    fun pairingUrl(standalone: Boolean, hubOrigin: String?, lanIp: String?, standalonePort: Int = STANDALONE_PORT): String? {
        return if (standalone) {
            standaloneUrl(lanIp, standalonePort)
        } else {
            normaliseOrigin(hubOrigin)
        }
    }

    /**
     * The Settings screen of the web UI, as a deep link on [url].
     *
     * The web front-end routes on the fragment (`#/settings`), so a bare origin drops a phone on the
     * home board and makes the person hunt for the gear — while the row that printed this QR says
     * "open Settings". One hash added here makes the sheet and the page agree. It is fragment-only,
     * so neither server needs to know about it: the hub on :8788 and this TV's own :8787 answer the
     * same `index.html` and the page moves itself to Settings.
     */
    @JvmStatic
    fun settingsUrl(url: String?): String? {
        val origin = url?.trim()?.trimEnd('/') ?: return null
        if (origin.isEmpty() || origin.contains('#')) return origin.ifEmpty { null }
        return "$origin/#/settings"
    }

    /** Fallback typed address: the same URL the QR encodes, never a bare IP. */
    @JvmStatic
    fun fallbackText(url: String): String = url.trim()

    /**
     * Render [url] as a QR Bitmap of at least [minSizePx] pixels per side.
     * Uses the in-house byte-mode encoder at error correction M. Returns null on any failure so
     * the caller can show the fallback text instead of a broken image.
     */
    fun renderQr(url: String, minSizePx: Int = 512): Bitmap? {
        val text = url.trim()
        if (text.isEmpty()) return null
        return try {
            val qr = QrCode.encode(text) ?: return null
            toBitmap(qr, minSizePx)
        } catch (_: Throwable) {
            null
        }
    }

    private fun toBitmap(qr: QrCode, minSizePx: Int): Bitmap {
        // A little white margin beyond the symbol's own light border keeps the code readable when
        // the TV dims the sheet; modules are scaled by a whole number so edges stay crisp.
        val quiet = 2
        val total = qr.size + quiet * 2
        val scale = maxOf(1, (minSizePx + total - 1) / total)
        val out = total * scale
        val bmp = Bitmap.createBitmap(out, out, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (!qr.getModule(x, y)) continue
                val ox = (x + quiet) * scale
                val oy = (y + quiet) * scale
                for (sy in 0 until scale) {
                    for (sx in 0 until scale) {
                        bmp.setPixel(ox + sx, oy + sy, Color.BLACK)
                    }
                }
            }
        }
        return bmp
    }
}
