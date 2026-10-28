package com.scenix.tv.core

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * The HTTP half of standalone mode. The web shell speaks the `/app/v1` contract over HTTP exactly
 * as it does against a hub on another machine, so in this mode the app is its own hub: the same
 * routes on loopback, answered by the same [LocalHub] the page calls, and the page itself out of
 * `assets/web`.
 *
 * One consequence worth stating: there is no second backend implementation to keep in step. The JSON
 * a hub answers and the JSON this answers come from the same functions, so the page cannot tell
 * which machine it is talking to.
 */
class LocalServer private constructor(
    private val hub: LocalHub,
    assets: AssetManager,
) : LoopbackServer(assets) {

    override fun route(method: String, target: String, body: String, res: Res) {
        // substringBefore's second argument is the value returned when the delimiter is ABSENT, so asking
        // for "/" there silently renamed every path without a query string to the site root.
        val path = target.substringBefore('?')
        val query = queryOf(target)
        when {
            path == "/healthz" ->
                res.json(200, JSONObject().put("ok", true).put("backend", "standalone"))

            path == "/app/v1/manifest" ->
                res.json(200, hub.manifestJson())

            path == "/app/v1/profiles" && method == "POST" -> {
                val o = parse(body)
                val out = if (o == null) JSONObject().put("error", "body must be JSON") else hub.addProfile(o)
                if (out.has("error")) res.json(400, out)
                else res.json(200, JSONObject().put("ok", true).put("profile", out.opt("profile")))
            }

            path == "/app/v1/profiles" -> res.json(200, hub.profilesJson())

            path.startsWith("/app/v1/profiles/") && method == "POST" -> {
                // The one place a PIN is checked. This backend holds the code, so it may compare it; a hub
                // never lets the asking machine see one, and the UI never sends it anywhere but here.
                val id = decode(path.removePrefix("/app/v1/profiles/").removeSuffix("/unlock"))
                val out = hub.unlockProfile(id, parse(body)?.optString("pin", "") ?: "")
                when {
                    s(out, "error", "") == "no such profile" -> res.json(404, out)
                    out.has("error") -> res.json(403, out)
                    else -> res.json(200, out)
                }
            }

            path.startsWith("/app/v1/profiles/") && (method == "PUT" || method == "PATCH") -> {
                val id = decode(path.removePrefix("/app/v1/profiles/"))
                val o = parse(body)
                val out = if (o == null) JSONObject().put("error", "body must be JSON") else hub.patchProfile(id, o)
                if (s(out, "error", "") == "no such profile") res.json(404, out)
                else if (out.has("error")) res.json(400, out)
                else res.json(200, JSONObject().put("ok", true).put("profile", out.opt("profile")))
            }

            path.startsWith("/app/v1/profiles/") && method == "DELETE" -> {
                val id = decode(path.removePrefix("/app/v1/profiles/"))
                val out = hub.removeProfile(id)
                if (s(out, "error", "") == "no such profile") res.json(404, out)
                else if (out.has("error")) res.json(400, out)
                else res.json(200, JSONObject().put("ok", true).put("removed", out.opt("removed")))
            }

            path == "/app/v1/home" -> res.json(200, hub.homeJson(query["profile"]))

            path.startsWith("/app/v1/row/") -> {
                // Unwrapped, exactly as a hub answers one row: `{id, title, card, items}`.
                val rowId = decode(path.removePrefix("/app/v1/row/"))
                val row = hub.rowJson(rowId, query["profile"])
                if (row == null) res.json(404, JSONObject().put("error", "no such row").put("rowId", rowId))
                else res.json(200, row)
            }

            path == "/app/v1/search" -> {
                // A hub answers a search with a flat list of items; this backend also has the shelf it
                // grouped them into. Both are sent, so the same page reads either.
                val term = query["q"] ?: ""
                val found = hub.searchJson(term, query["profile"])
                val flat = JSONArray()
                found.optJSONArray("rows")?.let { rows ->
                    for (i in 0 until rows.length()) {
                        rows.optJSONObject(i)?.optJSONArray("items")?.let { a ->
                            for (j in 0 until a.length()) {
                                val item = a.optJSONObject(j) ?: continue
                                flat.put(item)
                            }
                        }
                    }
                }
                res.json(200, found.put("q", term).put("items", flat))
            }

            path.startsWith("/app/v1/meta/") -> {
                val item = hub.metaJson(decode(path.removePrefix("/app/v1/meta/")), query["type"])
                if (item == null) res.json(404, JSONObject().put("error", "unknown title"))
                else res.json(200, JSONObject().put("item", item))
            }

            path == "/app/v1/play" || path == "/app/v1/switch" -> {
                // `switch` asks the same question a second time: give me another source for this title. A
                // hub re-probes the file and re-decides; this TV has nothing to re-probe, so it asks its
                // addons again and answers in the same shape.
                val o = parse(body)
                if (o == null) res.json(400, JSONObject().put("error", "body must be JSON"))
                else {
                    val answered = hub.playJson(o)
                    // A local torrent answer is a path this server owns. Make it absolute for the page
                    // and the native player, which do not share this server's base URL otherwise.
                    val u = answered.optString("url", "")
                    if (u.startsWith("/app/v1/torrent/")) {
                        answered.put("url", "http://127.0.0.1:$port$u")
                    }
                    val code = when {
                        !answered.has("error") -> 200
                        answered.optBoolean("warming") -> 503
                        else -> 409
                    }
                    res.json(code, answered)
                }
            }

            path == "/app/v1/progress" -> {
                val o = parse(body)
                if (o == null) res.json(400, JSONObject().put("error", "body must be JSON"))
                else res.json(200, hub.progressJson(o))
            }

            path == "/app/v1/prefs" -> {
                val o = parse(body)
                when {
                    method == "POST" && o != null -> res.json(
                        200,
                        hub.setPrefsJson(o, query["profile"] ?: o.optString("profile", "").ifBlank { null }),
                    )
                    method == "POST" -> res.json(400, JSONObject().put("error", "body must be JSON"))
                    else -> res.json(200, hub.prefsJson(query["profile"]))
                }
            }

            path == "/app/v1/settings" -> {
                val o = parse(body)
                when {
                    // A hub is patched with PUT; POST is accepted too so a page has one less thing to
                    // know about which machine it is on.
                    (method == "PUT" || method == "POST") && o != null -> {
                        // `addonsResynced` is the one effect a TV can cause: new addon URLs mean the shelves
                        // are fetched again. There is no live tuner here to reload.
                        val touchedAddons = o.has("addons")
                        res.json(
                            200,
                            JSONObject().put("ok", true).put("settings", hub.setSettings(o))
                                .put("effects", JSONObject().put("addonsResynced", touchedAddons)),
                        )
                    }
                    method == "PUT" || method == "POST" ->
                        res.json(400, JSONObject().put("error", "expected a JSON object of settings"))
                    else -> res.json(200, hub.settingsEnvelope())
                }
            }

            path == "/app/v1/addons" && method == "POST" -> {
                val o = parse(body)
                val out = if (o == null) JSONObject().put("error", "an addon is a URL")
                    else hub.addAddon(o.optString("url", o.optString("manifest", "")), o.optString("name", ""))
                if (out.has("error")) res.json(addonErrorCode(s(out, "error", "")), out)
                else {
                    hub.refreshAddons()
                    res.json(
                        200,
                        JSONObject().put("ok", true).put("addon", out.opt("addon"))
                            .put("addons", hub.addonsJson()),
                    )
                }
            }

            path.startsWith("/app/v1/addons/") && method == "POST" -> {
                val rest = path.removePrefix("/app/v1/addons/")
                val which = decode(rest.substringBefore('/'))
                val verb = rest.substringAfter('/', "")
                val changed = if (verb == "enable" || verb == "disable")
                    hub.setAddonEnabled(which, verb == "enable") else null
                if (changed == null) res.json(404, JSONObject().put("error", "no such addon").put("id", which))
                else res.json(200, JSONObject().put("ok", true).put("addon", changed))
            }

            path.startsWith("/app/v1/addons/") && method == "DELETE" -> {
                val which = decode(path.removePrefix("/app/v1/addons/"))
                val removed = hub.removeAddon(which)
                if (removed == null) res.json(404, JSONObject().put("error", "no such addon").put("id", which))
                else res.json(200, JSONObject().put("ok", true).put("removed", removed).put("addons", hub.addonsJson()))
            }

            path == "/app/v1/addons" ->
                res.json(
                    200,
                    JSONObject().put("addons", hub.addonsJson())
                        // A hub names the file its addons are stored in, so an owner can go look at it. Here
                        // that file is the settings file, and it is named the same way.
                        .put("file", hub.settingsPath()),
                )

            path == "/app/v1/cache/clear" && method == "POST" -> {
                // Every addon response is kept for a while so a shelf redraws without the network. This
                // throws that away: the next look at a catalogue is a real one.
                res.json(200, JSONObject().put("ok", true).put("cleared", hub.clearAddonCache()))
            }

            path.startsWith("/app/v1/trailer/") ->
                // This backend does not advertise trailers, so a UI that branches on capabilities never
                // asks. One that does not is answered in the same words a hub uses.
                res.json(
                    404,
                    JSONObject().put("error", "no trailer")
                        .put("identity", decode(path.removePrefix("/app/v1/trailer/")))
                        .put("reason", "this TV plays only the files it is given"),
                )

            path.startsWith("/app/v1/subs/") -> {
                val identity = decode(path.removePrefix("/app/v1/subs/"))
                // A hub reads the wanted languages from `lang` and falls back to what the profile listens
                // in; the page spells the same list `langs`. Both are read here, and the fallback is the
                // profile's own, so neither client has to know which machine it is answering.
                val asked = query["lang"] ?: query["langs"] ?: ""
                val langs = asked.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    .ifEmpty { hub.subtitleLanguages(query["profile"]) }
                res.json(
                    200,
                    JSONObject().put("identity", identity).put("langs", JSONArray(langs))
                        .put("subs", hub.subsJson(identity, langs, query["type"])),
                )
            }

            path == "/app/v1/avatars/characters" ->
                res.json(200, hub.charactersJson(query["limit"]?.toIntOrNull(), query["seed"] ?: ""))

            path.startsWith("/app/v1/avatars/character/") -> {
                val face = hub.characterImage(decode(path.removePrefix("/app/v1/avatars/character/")))
                if (face == null) res.json(404, JSONObject().put("error", "no cached face"))
                else res.send(200, face.type, face.bytes)
            }

            // A hub's avatar proxy fetches a face from somewhere else and keeps it. A TV fetches nothing at
            // run time — whatever faces it has came in with the build — so this answers the way a hub with no
            // avatar service answers, and a page that was going to ask falls back to drawing a face itself.
            path.startsWith("/app/v1/avatars/proxy") ->
                res.json(503, JSONObject().put("error", "no avatar service on this TV"))

            path == "/app/v1/avatars/warm" && method == "POST" ->
                res.json(200, JSONObject().put("ok", true).put("faces", 0))

            path.startsWith("/app/v1/torrent/") -> torrent(res, method, path)

            else -> static(res, path)
        }
    }

    /**
     * A prepared torrent file with HTTP Range support. The first chunk is awaited before the head is
     * written so a cold swarm answers 503 (warming) rather than a truncated 200; after that every read
     * waits only for the pieces covering that byte range.
     */
    private fun torrent(res: Res, method: String, path: String) {
        val rest = path.removePrefix("/app/v1/torrent/")
        val hash = decode(rest.substringBefore('/'))
        val fileId = rest.substringAfter('/', "").toIntOrNull()
        if (hash.isBlank() || fileId == null) {
            return res.json(400, JSONObject().put("error", "torrent and file are required"))
        }
        val opened = TorrentEngine.find(hash, fileId)
            ?: return res.json(404, JSONObject().put("error", "torrent not prepared").put("hash", hash))
        val size = opened.prepared.size
        var start = 0L
        var end = size - 1
        var partial = false
        val range = res.headers["range"]?.trim().orEmpty()
        if (range.startsWith("bytes=", ignoreCase = true)) {
            val spec = range.substringAfter('=').substringBefore(',').trim()
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            start = when {
                a.isEmpty() && b.isNotEmpty() -> (size - b.toLongOrNull().orZero()).coerceAtLeast(0L)
                else -> a.toLongOrNull() ?: 0L
            }
            end = if (b.isEmpty()) size - 1 else b.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
            partial = true
        }
        if (start < 0 || start >= size || end < start) {
            return res.json(416, JSONObject().put("error", "range not satisfiable"))
        }
        val length = end - start + 1
        val type = TorrentEngine.mimeOf(opened.prepared.name)
        val extra = LinkedHashMap<String, String>()
        extra["Accept-Ranges"] = "bytes"
        if (partial) extra["Content-Range"] = "bytes $start-$end/$size"
        if (method == "HEAD") {
            res.begin(if (partial) 206 else 200, type, length, extra)
            return res.end()
        }

        // Wait for the first chunk before committing the status line. A cold copy then answers 503,
        // which the player already knows how to retry; a 200 followed by silence is not honest.
        val first = TorrentEngine.read(opened, start, TORRENT_CHUNK)
            ?: return res.json(503, JSONObject().put("error", "torrent warming").put("warming", true).put("retryAfterMs", 5000))
        res.begin(if (partial) 206 else 200, type, length, extra)
        if (!res.write(first)) return
        var at = start + first.size
        while (at <= end) {
            val want = minOf(TORRENT_CHUNK.toLong(), end - at + 1).toInt()
            val chunk = TorrentEngine.read(opened, at, want) ?: break
            if (chunk.isEmpty()) break
            if (!res.write(chunk)) break
            at += chunk.size
        }
        res.end()
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun addonErrorCode(error: String): Int = when (error) {
        "already installed" -> 409
        "manifest not reachable" -> 502
        else -> 400
    }


    companion object {
        private const val TORRENT_CHUNK = 256 * 1024
        @Volatile private var instance: LocalServer? = null

        /**
         * One server per process, on one port, started when the WebView front end asks for it. The
         * port is ephemeral and never written down: the page is loaded from it and every call the page
         * makes is relative, so nothing else in this app has to know the number.
         */
        fun portFor(context: Context): Int {
            instance?.let { return it.port }
            synchronized(this) {
                instance?.let { return it.port }
                val s = LocalServer(LocalHub.get(context), context.assets)
                s.start()
                instance = s
                return s.port
            }
        }
    }
}
