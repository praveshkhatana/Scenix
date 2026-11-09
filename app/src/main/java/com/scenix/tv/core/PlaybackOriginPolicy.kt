package com.scenix.tv.core

import java.net.URI

/**
 * Decides whether a playback answer may use the optimistic LAN startup policy.
 *
 * The controller and the media delivery URL are deliberately separate in this product: fixtures
 * and standalone both answer their control API from loopback while they may hand the player a
 * direct public stream.  A private controller address alone is therefore not evidence that the
 * bytes for a ladder are local.  The policy is intentionally pure so that this boundary is covered
 * without a device or a network.
 */
object PlaybackOriginPolicy {
    const val LOCAL_LADDER_INITIAL_BITRATE_BPS = 9_000_000L

    /**
     * A high initial estimate is safe only for the hub-owned adaptive HLS ladder.  Both origins
     * must match exactly (including effective port), and the delivery host must be local.
     */
    @JvmStatic
    fun usesLocalLadderSeed(
        controlOrigin: String,
        mediaUrl: String,
        kind: String,
        hasLadder: Boolean,
    ): Boolean {
        if (!hasLadder || !kind.equals("hls", ignoreCase = true)) return false
        val control = parse(controlOrigin) ?: return false
        val media = parse(mediaUrl) ?: return false
        return sameOrigin(control, media) && isLocalHost(media.host)
    }

    private fun parse(value: String): URI? = runCatching {
        URI(value).takeIf { !it.scheme.isNullOrBlank() && !it.host.isNullOrBlank() }
    }.getOrNull()

    private fun sameOrigin(a: URI, b: URI): Boolean {
        if (!a.scheme.equals(b.scheme, ignoreCase = true)) return false
        if (!a.host.equals(b.host, ignoreCase = true)) return false
        return effectivePort(a) == effectivePort(b)
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        uri.scheme.equals("http", ignoreCase = true) -> 80
        else -> -1
    }

    private fun isLocalHost(rawHost: String?): Boolean {
        val host = rawHost?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true
        if (host.contains(':') && (host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd"))) {
            return true
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 127 || a == 10 || (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) || (a == 169 && b == 254)
    }
}
