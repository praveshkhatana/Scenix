package com.scenix.tv.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * The HTTP half of standalone mode. The WebView front end speaks the `/app/v1` contract over HTTP
 * exactly as it does against a hub on another machine, so in this mode the app is its own hub: the
 * same routes on loopback, answered by the same [LocalHub] the native screens call, and the page
 * itself out of `assets/web`.
 *
 * One consequence worth stating: there is no second backend implementation to keep in step. The JSON
 * a hub answers and the JSON this answers come from the same functions, so a screen cannot behave one
 * way natively and another way in the WebView.
 *
 * Loopback only, ephemeral port, no authentication — nothing outside this TV can reach it, so a guest
 * on the network cannot browse a household they were not invited into.
 */
class LocalServer private constructor(
    private val hub: LocalHub,
    private val assets: AssetManager,
) {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(POOL_THREADS, ThreadFactory { r ->
        Thread(r, "scenix-server").apply { isDaemon = true }
    })
    @Volatile private var running = true

    /** The port the page and every `/app/v1` call hang off. */
    val port: Int get() = server.localPort

    fun start() {
        Thread(
            {
                while (running) {
                    try {
                        val socket = server.accept()
                        pool.execute { handle(socket) }
                    } catch (t: Throwable) {
                        if (running) Log.w(TAG, "accept failed", t)
                    }
                }
            },
            "scenix-accept",
        ).apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { server.close() } catch (t: Throwable) { Log.w(TAG, "close failed", t) }
    }

    // ---- one request, one response, then the connection closes ---------------------------------
    // HTTP/1.1 with `Connection: close`: the page makes a handful of fetches at a time, and a TV with
    // 2 GB of RAM has no business holding sockets open for it.

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val input = s.getInputStream()
                val reader = BufferedReader(input.reader(Charsets.UTF_8))
                val request = reader.readLine() ?: return
                val parts = request.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val target = parts[1]
                var length = 0
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isBlank()) break
                    if (h.length >= 15 && h.substring(0, 15).equals("Content-Length:", ignoreCase = true)) {
                        length = h.substring(15).trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (length > 0) String(readBody(input, length), Charsets.UTF_8) else ""
                route(method, target, body, Res(s.getOutputStream()))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "request failed: ${t.message}")
        }
    }

    private fun readBody(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n.coerceAtMost(MAX_BODY))
        var got = 0
        while (got < buf.size) {
            val r = input.read(buf, got, buf.size - got)
            if (r < 0) break
            got += r
        }
        return buf.copyOf(got)
    }

    // ---- routing --------------------------------------------------------------------------------
    // The contract, route for route, answered by LocalHub. Anything that is not a contract path is
    // the bundled page.

    private fun route(method: String, target: String, body: String, res: Res) {
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
                    res.json(if (answered.has("error")) 409 else 200, answered)
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
                    hub.refreshAddons { _, _ -> }
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

            else -> static(res, path)
        }
    }

    // ---- the bundled page ------------------------------------------------------------------------

    private fun static(res: Res, requested: String) {
        // The page is mounted at /ui/ by a hub, and its own asset paths are written that way, so a
        // standalone server answers /ui/app.css and /app.css as one file. That is what lets the same
        // untouched bundle run against either backend.
        val rel = when {
            requested == "/" || requested.isBlank() || requested == "/ui" || requested == "/ui/" -> "index.html"
            requested.startsWith("/ui/") -> requested.removePrefix("/ui/")
            else -> requested.removePrefix("/")
        }
        if (rel.contains("..")) {
            res.json(400, JSONObject().put("error", "bad path"))
            return
        }
        val bytes = readAsset(rel)
        when {
            bytes == null -> res.json(404, JSONObject().put("error", "not found"))
            bytes.size > MAX_STATIC -> res.json(413, JSONObject().put("error", "file too large"))
            else -> res.send(200, contentType(rel), bytes)
        }
    }

    /**
     * One asset, read with a ceiling. A file in this APK that outgrew the page it belongs to is refused
     * rather than streamed, because a TV with 2 GB of RAM has no room for a mistake like that.
     */
    private fun readAsset(rel: String): ByteArray? = try {
        assets.open("web/$rel").use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
                if (total > MAX_STATIC) break
            }
            out.toByteArray()
        }
    } catch (t: Throwable) {
        null
    }

    private fun contentType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "txt" -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private fun parse(text: String): JSONObject? = try {
        if (text.isBlank()) null else JSONObject(text)
    } catch (t: Throwable) {
        null
    }

    private fun queryOf(target: String): Map<String, String> {
        if (!target.contains('?')) return emptyMap()
        return target.substringAfter('?').split("&").mapNotNull { pair ->
            val k = decode(pair.substringBefore('='))
            if (k.isBlank()) null else k to decode(pair.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(text: String): String = try {
        java.net.URLDecoder.decode(text, "UTF-8")
    } catch (t: Throwable) {
        text
    }

    /** A hub's words for a refused addon, answered with the status a hub would use for them. */
    private fun addonErrorCode(error: String): Int = when (error) {
        "already installed" -> 409
        "manifest not reachable" -> 502
        else -> 400
    }

    /** One request's worth of answers, written to one socket: every route answers exactly once. */
    private inner class Res(private val stream: OutputStream) {
        fun json(code: Int, o: JSONObject) =
            send(code, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

        fun send(code: Int, type: String, bytes: ByteArray) {
            val status = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                403 -> "Forbidden"
                404 -> "Not Found"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                502 -> "Bad Gateway"
                503 -> "Service Unavailable"
                else -> "Error"
            }
            // The same rule a hub's static server uses: text is re-checked every time, so a page that changed
            // in a rebuild is never hidden behind a cache, and a picture that cannot change on this TV is kept.
            val cache = if (type.startsWith("text/") || type.startsWith("application/json")) {
                "no-cache"
            } else {
                "public, max-age=86400"
            }
            val head = buildString {
                append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                append("Content-Type: ").append(type).append("\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Cache-Control: ").append(cache).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            try {
                stream.write(head.toByteArray(Charsets.UTF_8))
                stream.write(bytes)
                stream.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "response write failed: ${t.message}")
            }
        }
    }

    companion object {
        const val TAG = "ScenixServer"
        const val POOL_THREADS = 3
        const val MAX_BODY = 256 * 1024

        // The ceiling a hub's own static server holds a page file to. The whole bundle is well under it; the
        // number is there so one wrong file cannot fill a TV's memory.
        private const val MAX_STATIC = 4 * 1024 * 1024

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
