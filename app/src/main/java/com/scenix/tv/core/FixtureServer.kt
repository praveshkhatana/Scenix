package com.scenix.tv.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bundled preview backend: the same page a hub serves, answered from `assets/fixtures` instead
 * of a network.
 *
 * `--es fixture fixtures/home.json` used to swap an in-process client, which was enough when the
 * native screens read models directly. The product is now one web page that only speaks HTTP, so the
 * fixtures are served over loopback exactly as standalone serves its own data. The page cannot tell
 * the difference, which is the point: the same code path as a real backend is what is being
 * previewed.
 *
 * It is deliberately read-only. Nothing in this mode is an owner setting: there is no addon admin,
 * no profile change and no progress write that outlives the session.
 */
class FixtureServer private constructor(
    private val context: Context,
    private val assetPath: String,
    assets: AssetManager,
) : LoopbackServer(assets) {

    private val dir: String = assetPath.substringBeforeLast('/', "fixtures")

    override fun route(method: String, target: String, body: String, res: Res) {
        val path = target.substringBefore('?')
        val query = queryOf(target)
        when {
            path == "/healthz" ->
                res.json(200, JSONObject().put("ok", true).put("backend", "fixture").put("fixture", assetPath))

            path == "/app/v1/manifest" -> res.json(200, manifest())

            path == "/app/v1/profiles" -> res.json(200, profiles())

            path.startsWith("/app/v1/profiles/") && path.endsWith("/unlock") ->
                res.json(200, JSONObject().put("ok", true).put("unlocked", true))

            path == "/app/v1/home" -> res.json(200, home())

            path.startsWith("/app/v1/row/") -> {
                val id = decode(path.removePrefix("/app/v1/row/"))
                val row = rows().firstOrNull { it.optString("id") == id }
                if (row == null) res.json(404, JSONObject().put("error", "no such row").put("rowId", id))
                else res.json(200, row)
            }

            path == "/app/v1/search" -> {
                val term = (query["q"] ?: "").trim()
                val items = allItems().filter { item ->
                    term.isEmpty() || item.optString("title").contains(term, ignoreCase = true) ||
                        item.optJSONArray("genres")?.let { g ->
                            (0 until g.length()).any { g.optString(it).contains(term, ignoreCase = true) }
                        } == true ||
                        item.optJSONArray("languages")?.let { l ->
                            (0 until l.length()).any { l.optString(it).contains(term, ignoreCase = true) }
                        } == true
                }
                val flat = JSONArray()
                for (item in items) flat.put(item)
                val rows = JSONArray().put(
                    JSONObject().put("id", "search")
                        .put("title", if (term.isEmpty()) "Search" else "Results for \"$term\"")
                        .put("card", "poster").put("items", flat)
                )
                res.json(200, JSONObject().put("q", term).put("rows", rows).put("items", flat))
            }

            path.startsWith("/app/v1/meta/") -> {
                val identity = decode(path.removePrefix("/app/v1/meta/"))
                val item = allItems().firstOrNull { it.optString("identity") == identity }
                if (item == null) res.json(404, JSONObject().put("error", "unknown title"))
                else res.json(200, JSONObject().put("item", item))
            }

            path == "/app/v1/play" || path == "/app/v1/switch" -> {
                val asked = parse(body)
                val identity = asked?.optString("identity", "") ?: ""
                val source = asked?.optString("source", "") ?: ""
                val answer = root().optJSONObject("play")?.let { play ->
                    play.optJSONObject(identity) ?: play.optJSONObject(source)
                }
                if (answer == null) {
                    res.json(409, JSONObject().put("error", "the bundled fixtures have no copy of this title")
                        .put("warming", false))
                } else {
                    res.json(200, answer)
                }
            }

            path == "/app/v1/progress" -> res.json(200, JSONObject().put("ok", true))

            path == "/app/v1/prefs" -> {
                val prefs = sibling("prefs.json").optJSONObject("prefs")
                    ?: sibling("prefs.json")
                res.json(
                    200,
                    JSONObject().put("profile", query["profile"] ?: prefs.optString("profile"))
                        .put("prefs", prefs).put("defaults", prefs),
                )
            }

            path == "/app/v1/settings" -> res.json(
                200,
                JSONObject().put("settings", JSONObject())
                    .put("defaults", JSONObject())
                    .put("rows", JSONArray())
                    .put("categories", JSONArray())
                    .put("catalogOptions", JSONObject())
                    .put("languageNames", JSONObject()),
            )

            path == "/app/v1/addons" -> res.json(
                200,
                JSONObject().put("addons", manifest().optJSONArray("addons") ?: JSONArray())
                    .put("file", "assets/$assetPath"),
            )

            path.startsWith("/app/v1/subs/") -> {
                val identity = decode(path.removePrefix("/app/v1/subs/"))
                val subs = root().optJSONObject("subs")?.optJSONArray(identity) ?: JSONArray()
                val langs = (query["lang"] ?: query["langs"] ?: "")
                    .split(",").map { it.trim() }.filter { it.isNotBlank() }
                res.json(
                    200,
                    JSONObject().put("identity", identity).put("subs", subs)
                        .put("langs", JSONArray(langs)),
                )
            }

            path.startsWith("/app/v1/trailer/") ->
                res.json(404, JSONObject().put("error", "no trailer in the bundled fixtures"))

            else -> static(res, path)
        }
    }

    // ---- fixture files --------------------------------------------------------------------------

    private var cached: JSONObject? = null

    @Synchronized
    private fun root(): JSONObject {
        cached?.let { return it }
        val o = try {
            context.assets.open(assetPath).bufferedReader().use { JSONObject(it.readText()) }
        } catch (t: Throwable) {
            Log.w(TAG, "fixture $assetPath unreadable", t)
            JSONObject()
        }
        cached = o
        return o
    }

    private fun sibling(name: String): JSONObject = try {
        val path = if (dir.isEmpty()) name else "$dir/$name"
        context.assets.open(path).bufferedReader().use { JSONObject(it.readText()) }
    } catch (t: Throwable) {
        Log.w(TAG, "fixture sibling $name missing", t)
        JSONObject()
    }

    private fun manifest(): JSONObject {
        val m = sibling("manifest.json").optJSONObject("manifest") ?: sibling("manifest.json")
        // A fixture is a preview, not a backend: it must say what it cannot do instead of offering
        // switches whose writes go nowhere.
        if (m.optJSONObject("capabilities") == null) {
            m.put(
                "capabilities",
                JSONObject()
                    .put("gateway", false).put("live", true).put("localFiles", false)
                    .put("remux", false).put("tracks", false).put("announce", false)
                    .put("search", true).put("subtitles", true)
                    .put("settingsAdmin", false).put("addonAdmin", false)
                    .put("prefsEdit", false).put("profileEdit", false)
                    .put("cacheAdmin", false).put("avatarFaces", false),
            )
        }
        return m
    }

    private fun profiles(): JSONObject = sibling("profiles.json")

    private fun home(): JSONObject {
        val o = root()
        return o.optJSONObject("home") ?: o
    }

    private fun rows(): List<JSONObject> {
        val arr = home().optJSONArray("rows") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun allItems(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (row in rows()) {
            val items = row.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    companion object {
        private const val TAG = "ScenixFixture"
        @Volatile private var instance: FixtureServer? = null
        @Volatile private var activePath: String? = null

        /** One server per fixture path, started the first time the shell asks for it. */
        fun portFor(context: Context, assetPath: String): Int {
            instance?.let { if (activePath == assetPath) return it.port }
            synchronized(this) {
                instance?.let { if (activePath == assetPath) return it.port }
                val s = FixtureServer(context.applicationContext, assetPath, context.assets)
                s.start()
                instance = s
                activePath = assetPath
                return s.port
            }
        }
    }
}
