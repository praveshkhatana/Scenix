package com.scenix.tv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.scenix.tv.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import javax.net.ssl.HttpsURLConnection

/** Result callback, always delivered on the main thread. `value == null` means "nothing / failed". */
fun interface HubCb<T> {
    fun onResult(value: T?, error: String?)
}

/**
 * The hub surface from plugin-architecture.md §2. Every screen and every plugin talks to this
 * interface, never to a URL, which is what lets `--es fixture …` swap in [FixtureHub] and keep
 * the whole UI alive with the hub down.
 */
interface HubClient {
    val label: String
    fun manifest(cb: HubCb<Manifest>)
    fun profiles(cb: HubCb<List<Profile>>)
    fun home(profileId: String?, cb: HubCb<List<Row>>)
    fun row(rowId: String, profileId: String?, cb: HubCb<Row?>)
    fun search(q: String, profileId: String?, cb: HubCb<List<Row>>)
    /**
     * One title. `type` is the kind the app already knows from the row it came from (movie | series |
     * channel | video): an IMDB id carries no type, so a hub that proxies a community addon answers a
     * different film if it has to guess. Send what you know.
     */
    fun meta(identity: String, type: String? = null, cb: HubCb<Item?>)
    fun play(
        identity: String, provider: String?, profileId: String?,
        type: String? = null, season: Int = 0, episode: Int = 0, cb: HubCb<PlayTarget?>,
    )
    /**
     * Where the viewer stopped. `episode` names which one for a series, so the next Play press resumes
     * that episode rather than restarting S01E01 three seasons in.
     */
    fun progress(identity: String, positionMs: Long, profileId: String?, episode: Episode? = null)

    /**
     * Cheap reachability probe, used at startup and every retry. The app falls back to the bundled
     * fixtures when this says no, so a TV on flaky Wi-Fi still shows a home screen.
     */
    fun health(cb: HubCb<Boolean>) { cb.onResult(true, null) }

    /**
     * `GET /app/v1/addons` — what the hub is pulling content from, read-only on the TV. Optional by
     * contract: an older hub answers 404 and the Settings screen simply omits the section.
     */
    fun addons(cb: HubCb<List<Addon>>) { cb.onResult(emptyList(), null) }

    /**
     * `GET /app/v1/subs/<identity>?lang=hin,eng,pan` — subtitles the hub found for this title.
     * Optional: a hub without it answers 404 and the player just has embedded tracks.
     */
    fun subs(identity: String, langs: List<String>, profileId: String?, cb: HubCb<List<Sub>>) {
        cb.onResult(emptyList(), null)
    }

    /** `GET /app/v1/prefs` — how this profile wants sources ranked. Null when the hub has none yet. */
    fun prefs(profileId: String?, cb: HubCb<HubPrefs?>) { cb.onResult(null, null) }

    /** `POST /app/v1/prefs` — save that ranking. Answers with what the hub stored. */
    fun setPrefs(values: HubPrefs, profileId: String?, cb: HubCb<HubPrefs?>) { cb.onResult(null, null) }

    /**
     * `GET /app/v1/trailer/<identity>` — a short clip to play behind the billboard while the viewer
     * looks at a title, the way Netflix previews. Optional: a hub without it answers 404 and the
     * billboard simply keeps its still artwork.
     */
    fun trailer(identity: String, cb: HubCb<String?>) { cb.onResult(null, null) }

    /** Same, named for the profile, so trailer availability can be gated exactly like rows. */
    fun trailer(identity: String, profileId: String?, cb: HubCb<String?>) = trailer(identity, cb)
}

/**
 * Hub status codes the app reacts to differently from "unreachable".
 *
 * A kids profile gets `403` for any adult row or title, and the right answer is to hide that row or
 * screen quietly — an error toast would tell a child something about content they were never shown.
 */
object HubError {
    /** Thrown by the HTTP plumbing so the code survives into the callback's error string. */
    fun status(code: Int): String = "HTTP $code"

    fun forbidden(error: String?): Boolean = error != null && error.contains("403")

    /** Anything that is not a deliberate "you may not see this" — the hub is down or the path is wrong. */
    fun unreachable(error: String?): Boolean = error != null && !forbidden(error)
}

/** HttpURLConnection + `org.json` + a small thread pool. No Retrofit, no kotlinx-serialization. */
class HttpHub(context: Context, private val prefs: Prefs) : HubClient {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(POOL_THREADS, ThreadFactory { r ->
        Thread(r, "scenix-hub").apply { isDaemon = true }
    })

    /** Re-read every request so a Settings change (or an adb intent) takes effect at once. */
    private fun base(): String = prefs.hubUrl(appContext.getString(R.string.hub_url)).trimEnd('/')

    override val label: String get() = base()

    // ---- endpoints -----------------------------------------------------------------------
    override fun manifest(cb: HubCb<Manifest>) = get("/app/v1/manifest", emptyMap(), { o -> Manifest.from(o) }, cb)

    override fun profiles(cb: HubCb<List<Profile>>) = get("/app/v1/profiles", emptyMap(), { o ->
        when {
            o.optJSONArray("profiles") != null -> Profile.list(o.optJSONArray("profiles"))
            o.optJSONArray("items") != null -> Profile.list(o.optJSONArray("items"))
            else -> Profile.list(o.optJSONArray("data"))
        }
    }, cb)

    override fun home(profileId: String?, cb: HubCb<List<Row>>) =
        get("/app/v1/home", q(profileId), { o -> Row.list(o.optJSONArray("rows")) }, cb)

    override fun addons(cb: HubCb<List<Addon>>) = get("/app/v1/addons", emptyMap(), { o ->
        Addon.list(o.optJSONArray("addons") ?: o.optJSONArray("items"))
    }, cb)

    override fun row(rowId: String, profileId: String?, cb: HubCb<Row?>) =
        get("/app/v1/row/$rowId", q(profileId), { o -> Row.from(o.optJSONObject("row") ?: o) }, cb)

    override fun search(q: String, profileId: String?, cb: HubCb<List<Row>>) =
        get("/app/v1/search", q(profileId).plus("q" to q), { o ->
            when {
                o.optJSONArray("rows") != null -> Row.list(o.optJSONArray("rows"))
                o.optJSONArray("items") != null -> listOf(Row(id = "search", title = "Search results", card = "poster", items = Item.list(o.optJSONArray("items"))))
                else -> emptyList()
            }
        }, cb)

    override fun meta(identity: String, type: String?, cb: HubCb<Item?>) =
        get(
            "/app/v1/meta/$identity",
            if (type.isNullOrBlank()) emptyMap() else mapOf("type" to type),
            { o -> Item.from(o.optJSONObject("item") ?: o) },
            cb,
        )

    override fun play(
        identity: String, provider: String?, profileId: String?,
        type: String?, season: Int, episode: Int, cb: HubCb<PlayTarget?>,
    ) = post(
        "/app/v1/play",
        JSONObject()
            .put("identity", identity)
            .put("provider", provider ?: JSONObject.NULL)
            .put("profile", profileId ?: JSONObject.NULL)
            .put("type", type ?: JSONObject.NULL)
            // 0 means "hub, you choose" — the hub defaults to the episode this profile stopped on, else
            // the first one. Naming one is how a source switch mid-episode stays on that episode.
            .put("season", season)
            .put("episode", episode),
        { o -> PlayTarget.from(o) },
        cb,
    )

    override fun trailer(identity: String, cb: HubCb<String?>) = trailer(identity, null, cb)

    override fun trailer(identity: String, profileId: String?, cb: HubCb<String?>) =
        get(
            "/app/v1/trailer/$identity",
            q(profileId),
            { o -> s(o, "url", "").takeIf { it.isNotBlank() } },
            cb,
        )

    override fun subs(identity: String, langs: List<String>, profileId: String?, cb: HubCb<List<Sub>>) =
        get("/app/v1/subs/$identity",
            q(profileId).let {
                if (langs.isEmpty()) it else it + ("lang" to langs.joinToString(","))
            },
            { o -> Sub.list(o.optJSONArray("subs") ?: o.optJSONArray("items")) },
            cb)

    override fun prefs(profileId: String?, cb: HubCb<HubPrefs?>) =
        get("/app/v1/prefs", q(profileId), { o -> HubPrefs.from(o.optJSONObject("prefs") ?: o) }, cb)

    override fun setPrefs(values: HubPrefs, profileId: String?, cb: HubCb<HubPrefs?>) =
        post("/app/v1/prefs", values.toJson(profileId ?: ""),
            { o -> HubPrefs.from(o.optJSONObject("prefs") ?: o) }, cb)

    /** Fire and forget: progress lives on the hub so it survives a reinstall. */
    override fun progress(identity: String, positionMs: Long, profileId: String?, episode: Episode?) {
        val body = JSONObject()
            .put("identity", identity)
            .put("positionMs", positionMs)
            .put("profile", profileId ?: JSONObject.NULL)
        if (episode != null) {
            body.put("episode", JSONObject()
                .put("season", episode.season)
                .put("episode", episode.episode)
                .put("title", episode.title))
        }
        val url = base() + "/app/v1/progress"
        try {
            pool.execute {
                try { postRaw(url, body) } catch (t: Throwable) { Log.w(TAG, "progress post failed: ${t.message}") }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "progress queue rejected", t)
        }
    }

    /** `GET /healthz` — one byte of truth about whether the hub is up. */
    override fun health(cb: HubCb<Boolean>) {
        try {
            pool.execute {
                var ok = false
                try {
                    val conn = open(base() + "/healthz", "GET")
                    try {
                        ok = conn.responseCode in 200..299
                    } finally {
                        conn.disconnect()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "health probe failed: ${t.message}")
                }
                main.post { cb.onResult(ok, null) }
            }
        } catch (t: Throwable) {
            main.post { cb.onResult(false, "busy") }
        }
    }

    // ---- plumbing -------------------------------------------------------------------------
    private fun q(profileId: String?): Map<String, String> =
        if (profileId.isNullOrBlank()) emptyMap() else mapOf("profile" to profileId)

    private fun <T> get(path: String, query: Map<String, String>, parse: (JSONObject) -> T, cb: HubCb<T>) {
        val url = buildString {
            append(base()); append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
                )
            }
        }
        dispatch("GET", url, null, parse, cb)
    }

    private fun <T> post(path: String, body: JSONObject, parse: (JSONObject) -> T, cb: HubCb<T>) =
        dispatch("POST", base() + path, body, parse, cb)

    private fun <T> dispatch(method: String, url: String, body: JSONObject?, parse: (JSONObject) -> T, cb: HubCb<T>) {
        try {
            pool.execute {
                var value: T? = null
                var err: String? = null
                try {
                    val o = if (method == "GET") getRaw(url) else postRaw(url, body ?: JSONObject())
                    if (o == null) err = "no response from $url" else value = parse(o)
                } catch (t: Throwable) {
                    err = t.message ?: t.javaClass.simpleName
                    Log.w(TAG, "$method $url failed", t)
                }
                val v = value
                main.post { cb.onResult(v, err) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "hub queue rejected", t)
            main.post { cb.onResult(null, "busy") }
        }
    }

    private fun getRaw(url: String): JSONObject? {
        val conn = open(url, "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $url -> $code")
                // 403 is a decision, not a failure: HubError.forbidden() reads this string.
                throw java.io.IOException(HubError.status(code))
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun postRaw(url: String, body: JSONObject): JSONObject? {
        val conn = open(url, "POST")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "POST $url -> $code")
                throw java.io.IOException(HubError.status(code))
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 6000
        conn.readTimeout = 12000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "Scenix/0.1 (Android TV)")
        if (conn is HttpsURLConnection) conn.instanceFollowRedirects = true
        return conn
    }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    private companion object {
        const val TAG = "ScenixHub"
        const val POOL_THREADS = 3
    }
}

/**
 * Offline client backed by a fixture file under `assets/fixtures`, selected with
 * `--es fixture fixtures/home.json`. Same shapes as the hub, so every plugin renders unchanged.
 */
class FixtureHub(context: Context, private val assetPath: String) : HubClient {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var cached: JSONObject? = null

    override val label: String get() = "fixture:$assetPath"

    @Synchronized
    private fun root(): JSONObject {
        cached?.let { return it }
        val o = try {
            JSONObject(appContext.assets.open(assetPath).bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            Log.w(TAG, "fixture $assetPath unreadable", t)
            JSONObject()
        }
        cached = o
        return o
    }

    private fun <T> reply(value: () -> T, cb: HubCb<T>) {
        var v: T? = null
        var err: String? = null
        try {
            v = value()
        } catch (t: Throwable) {
            err = t.message
            Log.w(TAG, "fixture read failed", t)
        }
        main.post { cb.onResult(v, err) }
    }

    override fun manifest(cb: HubCb<Manifest>) = reply({
        val o = sibling("manifest.json").optJSONObject("manifest") ?: sibling("manifest.json")
        Manifest.from(o)
    }, cb)

    /** The bundled manifest carries the same optional `addons` section the live hub is adding. */
    override fun addons(cb: HubCb<List<Addon>>) = reply({
        val o = sibling("manifest.json")
        Addon.list(o.optJSONArray("addons")
            ?: o.optJSONObject("manifest")?.optJSONArray("addons"))
    }, cb)

    override fun profiles(cb: HubCb<List<Profile>>) = reply({
        val o = sibling("profiles.json")
        Profile.list(o.optJSONArray("profiles") ?: o.optJSONArray("items"))
    }, cb)

    override fun home(profileId: String?, cb: HubCb<List<Row>>) = reply({ rows() }, cb)

    override fun row(rowId: String, profileId: String?, cb: HubCb<Row?>) =
        reply({ rows().firstOrNull { it.id == rowId } }, cb)

    override fun search(q: String, profileId: String?, cb: HubCb<List<Row>>) = reply({
        val needle = q.trim().lowercase()
        val items = if (needle.isEmpty()) emptyList() else allItems().filter {
            it.title.lowercase().contains(needle) ||
                it.genres.any { g -> g.lowercase().contains(needle) } ||
                it.languages.any { l -> l.lowercase().contains(needle) }
        }
        listOf(Row(id = "search", title = "Results for \"$q\"", card = "poster", order = 0, items = items))
    }, cb)

    override fun meta(identity: String, type: String?, cb: HubCb<Item?>) =
        reply({ allItems().firstOrNull { it.identity == identity } }, cb)

    override fun play(
        identity: String, provider: String?, profileId: String?,
        type: String?, season: Int, episode: Int, cb: HubCb<PlayTarget?>,
    ) = reply({
        val play = root().optJSONObject("play")
        val fromFixture = play?.optJSONObject(identity) ?: play?.optJSONObject(provider ?: "")
        if (fromFixture != null) {
            PlayTarget.from(fromFixture)
        } else {
            // Tolerant: a fixture source may carry its own url.
            val item = allItems().firstOrNull { it.identity == identity }
            val src = item?.sources?.firstOrNull { it.kind.isNotBlank() }
            val url = play?.optString(src?.provider ?: "", "")?.takeIf { it.isNotBlank() }
            if (url.isNullOrEmpty()) null else PlayTarget(url = url, kind = src?.kind ?: "http")
        }
    }, cb)

    override fun progress(identity: String, positionMs: Long, profileId: String?, episode: Episode?) {
        Log.i(TAG, "progress (fixture) $identity ${positionMs}ms profile=$profileId")
    }

    /** `subs` map in the fixture file, keyed by identity, same shape as the hub's answer. */
    override fun subs(identity: String, langs: List<String>, profileId: String?, cb: HubCb<List<Sub>>) = reply({
        val arr = root().optJSONObject("subs")?.optJSONArray(identity)
        val all = Sub.list(arr)
        if (langs.isEmpty()) all else all.filter { s -> langs.any { l -> s.lang.startsWith(l) } }
    }, cb)

    private var fixturePrefs: HubPrefs? = null

    override fun prefs(profileId: String?, cb: HubCb<HubPrefs?>) = reply({
        fixturePrefs ?: HubPrefs.from(sibling("prefs.json").optJSONObject("prefs") ?: sibling("prefs.json"))
    }, cb)

    override fun setPrefs(values: HubPrefs, profileId: String?, cb: HubCb<HubPrefs?>) = reply({
        fixturePrefs = values
        values
    }, cb)

    /** The fixture client is what the app uses when the real hub is not answering. */
    override fun health(cb: HubCb<Boolean>) {
        cb.onResult(false, null)
    }

    private fun rows(): List<Row> {
        val o = root()
        val arr: JSONArray? = o.optJSONArray("rows") ?: o.optJSONObject("home")?.optJSONArray("rows")
        return Row.list(arr)
    }

    private fun allItems(): List<Item> = rows().flatMap { it.items }

    private fun sibling(name: String): JSONObject = try {
        val dir = assetPath.substringBeforeLast('/', "fixtures")
        val path = if (dir.isEmpty()) name else "$dir/$name"
        JSONObject(appContext.assets.open(path).bufferedReader().use { it.readText() })
    } catch (t: Throwable) {
        Log.w(TAG, "fixture sibling $name missing", t)
        JSONObject()
    }

    private companion object { const val TAG = "ScenixFixture" }
}
