package com.scenix.tv.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The only history a browser port can honestly offer.
 *
 * A web game keeps its saves inside the engine's own storage; there is no file on this TV to list
 * and no server to ask. What we *can* measure is time actually spent here, so a game's page can
 * say "played for 2H 15M" and show the sessions that produced it instead of inventing a campaign
 * percentage. Recording is keyed by identity (the hub's stable `game:<id>`), never by title.
 */
object GameSessions {

    private const val PREFS = "scenix_game_sessions"
    private const val MAX_KEPT = 8
    private const val OPEN_PREFIX = "open:"
    private const val LIST_PREFIX = "list:"

    data class Session(val startMs: Long, val durationMs: Long)

    fun recordStart(ctx: Context, identity: String, now: Long = System.currentTimeMillis()): Long {
        if (identity.isBlank()) return 0L
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // A second onResume without an onPause (a dialog on top) must not restart the clock.
        val open = prefs.getLong(OPEN_PREFIX + identity, 0L)
        if (open > 0L) return open
        prefs.edit().putLong(OPEN_PREFIX + identity, now).apply()
        return now
    }

    fun recordEnd(ctx: Context, identity: String, now: Long = System.currentTimeMillis()) {
        if (identity.isBlank()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val start = prefs.getLong(OPEN_PREFIX + identity, 0L)
        if (start <= 0L) return
        val duration = (now - start).coerceAtLeast(1_000L)
        val previous = read(ctx, identity)
        val next = JSONArray()
        next.put(JSONObject().put("s", start).put("d", duration))
        previous.take(MAX_KEPT - 1).forEach { next.put(JSONObject().put("s", it.startMs).put("d", it.durationMs)) }
        prefs.edit().putString(LIST_PREFIX + identity, next.toString()).remove(OPEN_PREFIX + identity).apply()
    }

    fun list(ctx: Context, identity: String): List<Session> = read(ctx, identity)

    fun totalMs(ctx: Context, identity: String): Long = read(ctx, identity).sumOf { it.durationMs }

    fun lastPlayed(ctx: Context, identity: String): Long = read(ctx, identity).maxOfOrNull { it.startMs } ?: 0L

    private fun read(ctx: Context, identity: String): List<Session> {
        if (identity.isBlank()) return emptyList()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LIST_PREFIX + identity, null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Session>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = o.optLong("s", 0L)
            val duration = o.optLong("d", 0L)
            if (start > 0L && duration > 0L) out += Session(start, duration)
        }
        return out.sortedByDescending { it.startMs }
    }
}
