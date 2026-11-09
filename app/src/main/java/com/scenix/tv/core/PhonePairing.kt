package com.scenix.tv.core

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Phone pairing: the TV shows a QR that opens Scenix settings on a phone.
 *
 * Two backends, two URLs:
 *  - Standalone: the TV itself hosts the web UI on 0.0.0.0:8787 (LoopbackServer).
 *    The phone opens http://<tv-lan-ip>:8787/ and the TV answers UI files from assets/web/.
 *  - Hub: the TV is paired to a hub (typically :8788). The phone opens the hub URL directly;
 *    the hub's web UI already guards settings behind the household Settings PIN
 *    (POST /app/v1/settings/unlock -> X-Scenix-Admin-Session, see hub/web/js/adminlock.js).
 *    Nothing hub-side is needed for minimal scope.
 *
 * QR encoding prefers ZXing (com.google.zxing:core). If the library is unavailable at runtime
 * (e.g. in a pure-JVM unit test) [renderQr] returns null and the caller falls back to the
 * typed URL text — the Settings sheet always shows the URL below the QR for that reason.
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

    /** Fallback typed address: the same URL the QR encodes, never a bare IP. */
    @JvmStatic
    fun fallbackText(url: String): String = url.trim()

    /**
     * Render [url] as a QR Bitmap of at least [minSizePx] pixels per side.
     * Uses ZXing byte mode with error correction M. Returns null on any failure so the
     * caller can show the fallback text instead of a broken image.
     */
    fun renderQr(url: String, minSizePx: Int = 512): Bitmap? {
        val text = url.trim()
        if (text.isEmpty()) return null
        return try {
            val writer = QRCodeWriter()
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            // Encode at a module count the writer chooses, then scale to a pixel size
            // phones can read at couch distance.
            val matrix = writer.encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
            toBitmap(matrix, minSizePx)
        } catch (_: WriterException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun toBitmap(matrix: BitMatrix, minSizePx: Int): Bitmap {
        val w = matrix.width
        val h = matrix.height
        // Quiet zone: ZXing's MARGIN=1 is included in the matrix, but a TV's
        // dimmed projection needs a clean white border, so add an extra module all round.
        val quiet = 2
        val totalW = w + quiet * 2
        val totalH = h + quiet * 2
        // Scale so the bitmap is at least minSizePx on the short side, rounded up to an
        // integer multiple so modules stay crisp.
        val scale = maxOf(1, (minSizePx + totalW - 1) / totalW)
        val outW = totalW * scale
        val outH = totalH * scale
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.RGB_565)
        bmp.eraseColor(Color.WHITE)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (matrix.get(x, y)) {
                    val ox = (x + quiet) * scale
                    val oy = (y + quiet) * scale
                    for (sy in 0 until scale) {
                        for (sx in 0 until scale) {
                            bmp.setPixel(ox + sx, oy + sy, Color.BLACK)
                        }
                    }
                }
            }
        }
        return bmp
    }
}
