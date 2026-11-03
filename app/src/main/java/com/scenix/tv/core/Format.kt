package com.scenix.tv.core

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Presentation-only formatters. They read the model's fields; they never decide what content is.
 */
object Format {

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
}
