package com.scenix.tv.core

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Presentation-only formatters. They read the model's fields; they never decide what content is.
 */
object Format {

    fun duration(ms: Long): String {
        if (ms <= 0L) return ""
        val totalMin = (ms / 60000L).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m} min"
        }
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    fun timeLeft(item: Item): String {
        val p = item.progress ?: return ""
        val left = p.watchedMs - p.positionMs
        if (p.watchedMs <= 0L || left <= 0L) return duration(p.positionMs) + " in"
        return duration(left) + " left"
    }

    fun match(item: Item): String = if (item.match > 0) "${item.match}% Match" else ""

    fun year(item: Item): String = if (item.year > 0) item.year.toString() else ""

    fun maturity(item: Item): String = item.maturity

    fun sourceCount(item: Item): String = when (item.sources.size) {
        0 -> ""
        1 -> "1 source"
        else -> "${item.sources.size} sources"
    }

    fun languages(item: Item): String = item.languages.joinToString(" · ") { langName(it) }

    /**
     * Language codes to words, in English, looked up in the system's own language table. No list of
     * languages is baked into this app: which ones a household listens in is not a fact about the app,
     * and a table here would be one more thing a build got wrong for everyone else. An unknown code is
     * shown as it arrived.
     */
    fun langName(code: String): String {
        val c = code.trim()
        if (c.isEmpty()) return ""
        val named = runCatching { Locale.forLanguageTag(c).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()
        return named?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
            ?: c.uppercase(Locale.US)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0L) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val i = ln(bytes.toDouble()) / ln(1024.0)
        val idx = i.toInt().coerceAtMost(units.size - 1)
        val v = bytes / 1024.0.pow(idx.toDouble())
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[idx])
    }

    fun ago(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        val days = ((System.currentTimeMillis() - epochMs) / 86_400_000L).coerceAtLeast(0L)
        return when {
            days <= 0L -> "ADDED today"
            days < 7L -> "ADDED ${days}d"
            days < 30L -> "ADDED ${(days / 7)}w"
            else -> "ADDED ${(days / 30)}mo"
        }
    }

    fun bitrate(bps: Int): String = when {
        bps <= 0 -> ""
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        else -> String.format(Locale.US, "%d kbps", bps / 1000)
    }

    fun seconds(ms: Long): String = "${(ms / 1000L).coerceAtLeast(0L)}s"

    /**
     * How a source is named on screen. The hub sends its own provider ids (`adaptive`, `torrent`,
     * `youtube`, `live`); this turns them into words a person reads, and it never prints a
     * third-party product name — the upstream services are anonymous adapters behind the hub.
     *
     * A provider this build has never seen is printed as-is, so a new hub provider still shows up
     * instead of disappearing.
     */
    fun provider(s: Source): String {
        val key = s.provider.ifBlank { s.kind }.lowercase()
        return when (key) {
            "adaptive", "debrid", "http", "hls", "dash" -> "Adaptive"
            "torrent" -> "Torrent (size-capped)"
            "youtube", "yt" -> "YouTube"
            "live" -> "Live"
            // An addon's own id is internal detail, so an unknown one is named by what the source
            // actually is. A hub that labels its addons differently can then never put a word on
            // this TV's screen that the owner did not choose.
            else -> when (s.kind.lowercase()) {
                "torrent" -> "Torrent (size-capped)"
                "yt", "youtube" -> "YouTube"
                "hls", "http", "dash", "mp4" -> "Adaptive"
                else -> key.ifBlank { "Source" }
            }
        }
    }
}
