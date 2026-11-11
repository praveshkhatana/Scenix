package com.scenix.tv.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The standalone half of the phone remote.
 *
 * A TV paired to a hub uses the hub's in-memory channel (`hub/core/remote.js`). A standalone TV is
 * its own hub, so it keeps the same small channel here and [LocalServer] exposes the same routes.
 * The contract is intentionally identical on both sides:
 *
 *   POST /app/v1/remote/register  {token, code?}        -> {ok, token, code, expiresAt}
 *   GET  /app/v1/remote/events    ?t=&after=            -> {events, last, phoneSeenAt, ...}
 *   POST /app/v1/remote/send      {token, events:[...]} -> {ok, last}
 *   GET  /app/v1/remote/state     ?t=                   -> {open, tvListening, ...}
 *   POST /app/v1/remote/close     {token}               -> {ok}
 *
 * Sessions live in process memory only. Registering the same token again refreshes it, which is
 * what a Settings screen does after a hub restart; closing it clears the queue and makes a poll
 * answer 410 so the phone stops immediately.
 */
object RemoteSession {
    const val TTL_MS = 15 * 60 * 1000L
    private const val MAX_SESSIONS = 16
    private const val MAX_EVENTS = 256
    private const val MAX_BATCH = 20
    private const val MAX_TEXT = 256
    private const val TOKEN_PATTERN = "^[a-f0-9]{32}$"
    private const val CODE_PATTERN = "^[A-Z2-9]{6}$"
    private const val CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val KEYS = setOf(
        "UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME", "MENU",
        "PLAY_PAUSE", "REWIND", "FAST_FORWARD", "DELETE",
    )

    private val lock = Any()
    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, Session>()

    data class Reply(val status: Int, val body: JSONObject)

    private class Event(val seq: Int, val json: JSONObject)

    private class Session(
        val token: String,
        var code: String,
        val createdAt: Long,
        var expiresAt: Long,
        var tvSeenAt: Long,
        var phoneSeenAt: Long,
        var seq: Int,
        val events: ArrayDeque<Event>,
        var closed: Boolean = false,
        var closedAt: Long = 0L,
    )

    @JvmStatic
    fun register(body: JSONObject): Reply = synchronized(lock) {
        prune()
        val token = body.optString("token").trim().lowercase()
        if (!Regex(TOKEN_PATTERN).matches(token)) {
            return@synchronized Reply(400, JSONObject().put("ok", false)
                .put("error", "a 32-character hex token is required"))
        }
        val supplied = body.optString("code").trim().uppercase()
        if (supplied.isNotEmpty() && !Regex(CODE_PATTERN).matches(supplied)) {
            return@synchronized Reply(400, JSONObject().put("ok", false)
                .put("error", "the code must be six letters or digits"))
        }
        if (sessions.size >= MAX_SESSIONS && !sessions.containsKey(token)) {
            sessions.entries.minByOrNull { it.value.tvSeenAt }?.let { sessions.remove(it.key) }
        }
        val now = System.currentTimeMillis()
        val prior = sessions[token]
        val code = supplied.ifBlank { prior?.code ?: newCode() }
        val session = Session(
            token = token,
            code = code,
            createdAt = prior?.createdAt ?: now,
            expiresAt = now + TTL_MS,
            tvSeenAt = now,
            phoneSeenAt = 0L,
            seq = prior?.seq ?: 0,
            events = prior?.events ?: ArrayDeque(),
        )
        sessions[token] = session
        Reply(200, JSONObject()
            .put("ok", true)
            .put("token", token)
            .put("code", code)
            .put("expiresAt", session.expiresAt)
            .put("ttlMs", TTL_MS))
    }

    @JvmStatic
    fun events(token: String, after: Int): Reply = synchronized(lock) {
        val session = find(token)
            ?: return@synchronized Reply(404, JSONObject().put("ok", false)
                .put("error", "no such remote session").put("expired", true))
        if (session.closed) return@synchronized gone()
        val now = System.currentTimeMillis()
        // The screen is still polling, so keep its QR valid while Settings stays open.
        session.tvSeenAt = now
        session.expiresAt = now + TTL_MS
        val out = JSONArray()
        for (event in session.events) {
            if (event.seq > after) out.put(event.json)
        }
        Reply(200, JSONObject()
            .put("ok", true)
            .put("events", out)
            .put("last", session.seq)
            .put("code", session.code)
            .put("expiresAt", session.expiresAt)
            .put("phoneSeenAt", session.phoneSeenAt)
            .put("closed", false))
    }

    @JvmStatic
    fun send(body: JSONObject): Reply = synchronized(lock) {
        val token = body.optString("token").trim().lowercase()
        val session = find(token)
            ?: return@synchronized Reply(404, JSONObject().put("ok", false)
                .put("error", "no such remote session").put("expired", true))
        if (session.closed) return@synchronized gone()
        val incoming = cleanEvents(body.optJSONArray("events"))
            ?: return@synchronized Reply(400, JSONObject().put("ok", false)
                .put("error", "send 1-20 key or text events"))
        session.phoneSeenAt = System.currentTimeMillis()
        for (i in 0 until incoming.length()) {
            val event = incoming.optJSONObject(i) ?: continue
            session.seq += 1
            session.events.addLast(Event(session.seq, JSONObject(event.toString()).put("seq", session.seq)))
        }
        while (session.events.size > MAX_EVENTS) session.events.removeFirst()
        Reply(200, JSONObject().put("ok", true).put("last", session.seq))
    }

    @JvmStatic
    fun state(token: String): Reply = synchronized(lock) {
        val session = find(token)
            ?: return@synchronized Reply(404, JSONObject().put("ok", false)
                .put("error", "no such remote session").put("expired", true))
        if (session.closed) return@synchronized gone()
        val now = System.currentTimeMillis()
        session.phoneSeenAt = now
        Reply(200, JSONObject()
            .put("ok", true)
            .put("open", true)
            .put("code", session.code)
            .put("expiresAt", session.expiresAt)
            .put("tvSeenAt", session.tvSeenAt)
            .put("tvListening", now - session.tvSeenAt < 15_000L)
            .put("closed", false))
    }

    @JvmStatic
    fun close(body: JSONObject): Reply = synchronized(lock) {
        val token = body.optString("token").trim().lowercase()
        val session = sessions[token]
        if (session == null) {
            Reply(200, JSONObject().put("ok", true).put("alreadyClosed", true))
        } else {
            session.closed = true
            session.closedAt = System.currentTimeMillis()
            session.events.clear()
            Reply(200, JSONObject().put("ok", true))
        }
    }

    private fun find(token: String): Session? {
        prune()
        if (!Regex(TOKEN_PATTERN).matches(token)) return null
        return sessions[token]
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) ->
            (session.closed && now - session.closedAt > 60_000L) || session.expiresAt <= now
        }
    }

    private fun gone(): Reply = Reply(410, JSONObject().put("ok", false)
        .put("error", "the TV closed the remote").put("closed", true))

    private fun newCode(): String = (1..6).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        .joinToString("")

    private fun cleanEvents(raw: JSONArray?): JSONArray? {
        if (raw == null || raw.length() !in 1..MAX_BATCH) return null
        val out = JSONArray()
        for (i in 0 until raw.length()) {
            val event = raw.optJSONObject(i) ?: return null
            when (event.optString("type")) {
                "key" -> {
                    val key = event.optString("key").trim().uppercase()
                    if (key !in KEYS) return null
                    out.put(JSONObject().put("type", "key").put("key", key))
                }
                "text" -> {
                    val text = event.optString("text")
                    if (text.isEmpty() || text.length > MAX_TEXT) return null
                    out.put(JSONObject().put("type", "text").put("text", text))
                }
                else -> return null
            }
        }
        return out
    }
}
