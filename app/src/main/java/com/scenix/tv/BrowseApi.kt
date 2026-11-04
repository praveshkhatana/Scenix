package com.scenix.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.scenix.tv.core.HubEndpoint
import com.scenix.tv.core.Item
import com.scenix.tv.core.Prefs
import com.scenix.tv.core.s
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** One shelf the native browse surface draws. Same shape as `/app/v1/home`, parsed once. */
data class BrowseRow(
    val id: String,
    val title: String,
    val card: String,
    val items: List<Item>,
    // How many titles the shelf really holds. The home payload sends a slice; the gap is what the
    // shelf's "see all" tile opens.
    val total: Int = items.size,
)

/** Which optional add-ons this household installed: the doors the navigation is allowed to draw. */
data class FeatureCaps(val live: Boolean, val games: Boolean)

/** One person the native profile gate offers. */
data class ProfileInfo(
    val id: String,
    val name: String,
    val kids: Boolean,
    val pinSet: Boolean,
    val locked: Boolean,
    val avatar: String,
    /** The band the hub holds a Kids profile to; blank when the profile has no age. */
    val age: String = "",
    /** The profile's own language list; empty means it inherits the household's. */
    val languages: List<String> = emptyList(),
)

/** A face cached by the hub. The TV receives only the hub path, never an upstream image host. */
data class AvatarChoice(val label: String, val image: String)

/**
 * The native read path: Home, Detail, the player and the progress writes that keep Continue Watching
 * alive. The hub is still the source of truth — this only speaks the same `/app/v1` contract the web
 * page does, with the same optional Basic token, and never ranks or re-shapes a source itself.
 *
 * Three workers: a play answer can take the hub's whole play budget, a progress tick must still get
 * through, and a subtitle request may not hold either up. Callbacks are always delivered on the main
 * thread.
 */
class BrowseApi(private val context: Context, private val prefs: Prefs) {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "scenix-browse").apply { isDaemon = true }
    }

    fun load(profileId: String, done: (rows: List<BrowseRow>?, error: String?) -> Unit) {
        request("/app/v1/home", "profile=" + enc(profileId), "GET", null, 20000) { root, _, error ->
            done(root?.let { parse(it) }, error)
        }
    }

    /**
     * The optional add-ons this hub has installed. The navigation asks this before it draws a Games
     * or Live TV door: a tab for a mode the household never added is a door to an empty room. A hub
     * too old to answer returns null, and the caller keeps its previous shape.
     */
    fun caps(done: (FeatureCaps?) -> Unit) {
        request("/app/v1/caps", "", "GET", null, 8000) { root, status, _ ->
            val body = root
            done(
                if (status in 200..299 && body != null && body.has("live") && body.has("games")) {
                    FeatureCaps(body.optBoolean("live", false), body.optBoolean("games", false))
                } else null
            )
        }
    }

    /** One section row (Movies, Series), as a native grid instead of a WebView browse page. */
    fun row(profileId: String, rowId: String, done: (title: String, items: List<Item>?, error: String?) -> Unit) {
        request("/app/v1/row/" + enc(rowId), "profile=" + enc(profileId), "GET", null, 20000) { root, _, error ->
            if (root == null) { done(rowId, null, error); return@request }
            done(
                s(root, "title", rowId),
                Item.list(root.optJSONArray("items")),
                error,
            )
        }
    }

    /**
     * The Game mode shelves (`GET /app/v1/games`). A game is not a title — it carries a play address
     * and a controller flag instead of sources — so this hands the raw rows over rather than forcing
     * them through [Item].
     */
    fun games(done: (rows: JSONArray?, error: String?) -> Unit) {
        request("/app/v1/games", "", "GET", null, 12000) { root, _, error ->
            done(root?.optJSONArray("rows"), error)
        }
    }

    /** The household settings PIN: verify it, or change the lock (the current PIN is required). */
    fun unlockSettings(pin: String, done: (ok: Boolean, error: String?) -> Unit) {
        request("/app/v1/settings/unlock", "", "POST", JSONObject().put("pin", pin), 10000) { root, status, error ->
            done(root?.optBoolean("ok") == true, error ?: if (status == 403) "That PIN is not right" else null)
        }
    }

    fun saveSettingsLock(
        enabled: Boolean,
        pin: String?,
        current: String?,
        done: (root: JSONObject?, error: String?) -> Unit,
    ) {
        val body = JSONObject().put("enabled", enabled)
        if (!pin.isNullOrBlank()) body.put("pin", pin)
        if (!current.isNullOrBlank()) body.put("pinCurrent", current)
        request("/app/v1/settings/lock", "", "POST", body, 12000) { root, _, error -> done(root, error) }
    }

    /** Installed add-ons, with the same enable/remove/add calls the web settings uses. */
    fun addons(done: (addons: JSONArray?, error: String?) -> Unit) {
        request("/app/v1/addons", "", "GET", null, 12000) { root, _, error ->
            done(root?.optJSONArray("addons"), error)
        }
    }

    fun setAddon(id: String, on: Boolean, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/addons/" + enc(id) + "/" + (if (on) "enable" else "disable"),
            "", "POST", null, 12000) { root, _, error -> done(root, error) }
    }

    fun removeAddon(id: String, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/addons/" + enc(id), "", "DELETE", null, 12000) { root, _, error -> done(root, error) }
    }

    fun addAddon(url: String, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/addons", "", "POST", JSONObject().put("url", url), 15000) { root, _, error ->
            done(root, error)
        }
    }

    /** The hub's settings document plus the row metadata the Home editor needs. */
    fun settings(done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/settings", "", "GET", null, 12000) { root, _, error -> done(root, error) }
    }

    /** A settings patch (the hub merges it, as the web settings writer does). */
    fun saveSettings(patch: JSONObject, done: (root: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/settings", "", "PUT", patch, 12000) { root, _, error -> done(root, error) }
    }

    /** The person's playback preferences, as the web settings page reads them. */
    fun prefs(profileId: String, done: (prefs: JSONObject?, error: String?) -> Unit) {
        request("/app/v1/prefs", "profile=" + enc(profileId), "GET", null, 12000) { root, _, error ->
            done(root?.optJSONObject("prefs"), error)
        }
    }

    /** `POST /app/v1/prefs` merges the patch into the profile and answers the resolved prefs. */
    fun savePrefs(profileId: String, patch: JSONObject, done: (prefs: JSONObject?, error: String?) -> Unit) {
        val body = JSONObject(patch.toString()).put("profile", profileId)
        request("/app/v1/prefs", "", "POST", body, 12000) { root, _, error ->
            done(root?.optJSONObject("prefs"), error)
        }
    }

    /** The hub's search index, hit as the letters arrive: `GET /app/v1/search?q=&profile=`. */
    fun search(profileId: String, query: String, done: (items: List<Item>?, error: String?) -> Unit) {
        request(
            "/app/v1/search",
            "q=" + enc(query) + "&profile=" + enc(profileId),
            "GET", null, 15000,
        ) { root, _, error ->
            val arr = root?.optJSONArray("items")
            if (arr == null) done(emptyList(), error) else done(Item.list(arr), null)
        }
    }

    /** A real genre shelf: crime films, not titles whose name contains the word "crime". */
    fun genre(profileId: String, genre: String, done: (items: List<Item>?, error: String?) -> Unit) {
        request(
            "/app/v1/genre",
            "name=" + enc(genre) + "&profile=" + enc(profileId),
            "GET", null, 30000,
        ) { root, _, error ->
            val arr = root?.optJSONArray("items")
            if (arr == null) done(emptyList(), error) else done(Item.list(arr), null)
        }
    }

    /** One catalogue an installed addon publishes, opened from the Browse addons row. */
    fun addonCatalog(
        profileId: String,
        addonId: String,
        catalogId: String,
        done: (items: List<Item>?, error: String?) -> Unit,
    ) {
        request(
            "/app/v1/addon-catalog",
            "addon=" + enc(addonId) + "&catalog=" + enc(catalogId) + "&profile=" + enc(profileId),
            "GET", null, 30000,
        ) { root, _, error ->
            val arr = root?.optJSONArray("items")
            if (arr == null) done(emptyList(), error) else done(Item.list(arr), null)
        }
    }

    /** The household, for the native profile gate. PINs are checked by `unlock` below. */
    fun profiles(done: (profiles: List<ProfileInfo>?, error: String?) -> Unit) {
        request("/app/v1/profiles", "", "GET", null, 15000) { root, _, error ->
            val arr = root?.optJSONArray("profiles") ?: root?.optJSONArray("items")
            if (arr == null) {
                done(null, error ?: "The hub did not send profiles")
                return@request
            }
            val out = ArrayList<ProfileInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val langs = ArrayList<String>()
                o.optJSONArray("languages")?.let { la ->
                    for (j in 0 until la.length()) {
                        val v = la.optString(j, "")
                        if (v.isNotBlank()) langs.add(v)
                    }
                }
                out.add(
                    ProfileInfo(
                        id = s(o, "id", ""),
                        name = s(o, "name", ""),
                        kids = o.optBoolean("isKids", o.optBoolean("kids", false)) ||
                            s(o, "kind", "").equals("kids", true),
                        pinSet = o.optBoolean("pinSet", o.optBoolean("hasPin", o.optBoolean("pinRequired", false))),
                        locked = o.optBoolean("locked", false),
                        avatar = s(o, "avatar", ""),
                        age = if (o.isNull("age")) "" else o.opt("age")?.toString().orEmpty(),
                        languages = langs,
                    )
                )
            }
            done(out, null)
        }
    }

    /** Cached character faces for the native profile editor. Empty is a valid offline answer. */
    fun avatarCharacters(seed: String, done: (choices: List<AvatarChoice>?, error: String?) -> Unit) {
        request("/app/v1/avatars/characters", "limit=24&seed=" + enc(seed), "GET", null, 15000) { root, _, error ->
            val arr = root?.optJSONArray("characters")
            if (arr == null) {
                done(null, error ?: "The hub has no cached character faces yet")
                return@request
            }
            val choices = ArrayList<AvatarChoice>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val image = s(item, "image", s(item, "src", ""))
                if (image.isNotBlank()) choices.add(AvatarChoice(s(item, "label", s(item, "name", "Avatar")), image))
            }
            done(choices, null)
        }
    }

    fun unlock(profileId: String, pin: String, done: (ok: Boolean, error: String?) -> Unit) {
        request(
            "/app/v1/profiles/${URLEncoder.encode(profileId, "UTF-8")}/unlock",
            "",
            "POST",
            JSONObject().put("pin", pin),
            15000,
        ) { root, _, error ->
            done(root?.optBoolean("ok") == true, error)
        }
    }

    /**
     * Create, change or remove a person. The status is handed over with the body because a refusal is
     * not one thing: a locked profile answers 403 with `locked`, an act that belongs to an adult answers
     * 403 with `gate: 'profiles'`, and only the body says which. The caller asks for the PIN the hub
     * named, then retries the same call with `pinCurrent`.
     */
    fun createProfile(body: JSONObject, done: (root: JSONObject?, status: Int, error: String?) -> Unit) =
        request("/app/v1/profiles", "", "POST", body, 15000) { root, status, error ->
            done(root, status, error)
        }

    /** `PUT /app/v1/profiles/<id>` — the hub answers PUT and PATCH with the same handler. */
    fun updateProfile(
        profileId: String,
        body: JSONObject,
        done: (root: JSONObject?, status: Int, error: String?) -> Unit,
    ) = request("/app/v1/profiles/" + enc(profileId), "", "PUT", body, 15000) { root, status, error ->
        done(root, status, error)
    }

    /** A DELETE may carry the body that proves the profile's PIN; no body means an unlocked profile. */
    fun deleteProfile(
        profileId: String,
        body: JSONObject,
        done: (root: JSONObject?, status: Int, error: String?) -> Unit,
    ) = request("/app/v1/profiles/" + enc(profileId), "", "DELETE", body, 15000) { root, status, error ->
        done(root, status, error)
    }

    fun absolute(path: String): String {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://")) return path
        return HubEndpoint.base(context) + (if (path.startsWith("/")) path else "/$path")
    }

    fun meta(identity: String, profileId: String, done: (item: JSONObject?, error: String?) -> Unit) {
        val path = "/app/v1/meta/" + enc(identity)
        // A cold hub resolving an eight-season series through its addons can sit well past the old
        // 20 s; a timeout there strands the player's Episodes menu on an empty list. A 404 is also
        // often a one-round-trip miss rather than a missing title: the row the person just tapped
        // came from a catalogue the hub may still be resolving. One quiet retry turns "not found"
        // into the detail page they asked for.
        fun attempt(tries: Int) {
            request(path, "profile=" + enc(profileId), "GET", null, 45000) { root, status, error ->
                val item = root?.optJSONObject("item") ?: root
                val missing = status == 404 || root?.optString("error") == "not found"
                // The hub now distinguishes "the addons said nothing" (404) from "an addon is still
                // answering" (503 warming, with its own retryAfterMs). Both deserve a quiet retry:
                // the answer is in transit, and the second ask lands in a warm cache.
                val warming = status == 503 &&
                    (root?.opt("warming") != null || root?.optString("error") == "warming")
                if ((missing || warming) && tries < 2) {
                    val wait = if (warming)
                        root?.optLong("retryAfterMs")?.takeIf { it in 300..5000 } ?: 1200L else 700L
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        { attempt(tries + 1) }, wait,
                    )
                    return@request
                }
                // A kids profile tapping an adult title gets a real answer — "the rules of this
                // profile say no" — and deserves those words, not a status code. The hub now names
                // the rule and the evidence, so the screen can say which setting did it.
                if (status == 403 && (root?.opt("droppedByRule") != null || root?.has("tier") == true)) {
                    val why = root?.optString("detail")?.takeIf { it.isNotBlank() }
                        ?: "this profile can't watch this title"
                    done(null, why)
                    return@request
                }
                done(item, error)
            }
        }
        attempt(0)
    }

    /**
     * `POST /app/v1/play`. The reply body is handed over even on a non-2xx answer: a `503 warming`
     * carries `retryAfterMs`, `alternatives` and the hub's own words, and that is the difference
     * between "still preparing" and an error card. `error` here means only that no JSON came back.
     */
    fun play(body: JSONObject, done: (answer: JSONObject?, status: Int, error: String?) -> Unit) =
        request("/app/v1/play", "", "POST", body, 40000) { root, status, error ->
            done(root, status, error)
        }

    /** `POST /app/v1/switch` — the same question again, with the copies already tried excluded. */
    fun switchSource(body: JSONObject, done: (answer: JSONObject?, status: Int, error: String?) -> Unit) =
        request("/app/v1/switch", "", "POST", body, 40000) { root, status, error ->
            done(root, status, error)
        }

    /**
     * `GET /app/v1/subs/<identity>?profile=&lang=`. Optional: a subtitle list that arrives late is
     * still usable, a hub without subtitle addons answers an empty list, and neither delays playback.
     */
    fun subs(
        identity: String,
        profileId: String,
        langs: String,
        done: (subs: JSONArray?, error: String?) -> Unit,
    ) {
        val query = ArrayList<String>()
        if (profileId.isNotBlank()) query.add("profile=" + enc(profileId))
        if (langs.isNotBlank()) query.add("lang=" + enc(langs))
        request("/app/v1/subs/" + enc(identity), query.joinToString("&"), "GET", null, 12000) { root, _, error ->
            done(root?.optJSONArray("subs"), error)
        }
    }

    /** `POST /app/v1/progress`. Fire-and-forget: losing one tick must never disturb the picture. */
    fun progress(body: JSONObject, done: (answer: JSONObject?, error: String?) -> Unit = { _, _ -> }) =
        request("/app/v1/progress", "", "POST", body, 10000) { root, _, error ->
            done(root, error)
        }

    /**
     * "Remove from Continue watching": the tile leaves Home, the position stays. Pressing the title
     * again posts progress and the row re-arms by itself (hub core/store.js).
     */
    fun dismiss(profileId: String, identity: String, done: (ok: Boolean) -> Unit = { }) =
        request("/app/v1/progress/dismiss", "", "POST",
            JSONObject().put("profile", profileId).put("identity", identity), 6000) { root, _, _ ->
            done(root?.optBoolean("dismissed", false) ?: false)
        }

    /** "Start over": the hub rewinds its record to never-started. Run BEFORE seeking to 0. */
    fun resetProgress(profileId: String, identity: String, done: (ok: Boolean) -> Unit = { }) =
        request("/app/v1/progress/reset", "", "POST",
            JSONObject().put("profile", profileId).put("identity", identity), 6000) { root, _, _ ->
            done(root?.optBoolean("ok", false) ?: false)
        }

    /**
     * My List, on the hub: one shelf for every screen in the house. GET answers the whole array so
     * the app can cache it per profile; POST adds or removes one identity.
     */
    fun myList(profileId: String, done: (ids: List<String>?, error: String?) -> Unit) {
        val query = if (profileId.isBlank()) "" else "profile=" + enc(profileId)
        request("/app/v1/list", query, "GET", null, 6000) { root, _, error ->
            val arr = root?.optJSONArray("ids")
            val ids = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) ids.add(arr.optString(i))
            done(if (error == null) ids else null, error)
        }
    }

    fun setMyList(profileId: String, identity: String, add: Boolean, done: (inList: Boolean) -> Unit = { }) =
        request("/app/v1/list", "", "POST",
            JSONObject().put("profile", profileId).put("identity", identity).put("add", add), 6000) { root, _, _ ->
            done(root?.optBoolean("inList", add) ?: add)
        }

    /** Like / love / dislike, per profile; `none` clears it. */
    fun react(profileId: String, identity: String, reaction: String, done: (String?) -> Unit = { }) =
        request("/app/v1/reaction", "", "POST",
            JSONObject().put("profile", profileId).put("identity", identity).put("reaction", reaction),
            6000) { root, _, _ -> done(root?.optString("reaction").orEmpty().takeIf { it.isNotBlank() }) }

    /** Mark a title (or the episode the player reports) seen, or clear the mark. */
    fun setSeen(profileId: String, identity: String, seen: Boolean, done: (Boolean) -> Unit = { }) =
        request("/app/v1/seen", "", "POST",
            JSONObject().put("profile", profileId).put("identity", identity).put("seen", seen),
            6000) { root, _, _ -> done(root?.optBoolean("seen", seen) ?: seen) }

    /**
     * `GET /app/v1/trailer/<identity>` — the hub looks the clip up on its own box and answers a
     * playable URL. The proxied form is preferred: a signed clip demands headers a bare player
     * will not replay, and the hub proxy replays them.
     */
    fun trailer(identity: String, done: (url: String?, error: String?) -> Unit) {
        request("/app/v1/trailer/" + enc(identity), "", "GET", null, 30000) { root, _, error ->
            val proxied = root?.optString("proxiedUrl", "").orEmpty()
            val direct = root?.optString("url", "").orEmpty()
            done(proxied.ifBlank { direct }.takeIf { it.isNotBlank() }, error)
        }
    }

    private fun request(
        path: String,
        query: String,
        method: String,
        body: JSONObject?,
        timeoutMs: Int,
        done: (JSONObject?, Int, String?) -> Unit,
    ) {
        pool.execute {
            var root: JSONObject? = null
            var status = 0
            var error: String? = null
            try {
                val base = HubEndpoint.base(context)
                val url = "$base$path" + (if (query.isBlank()) "" else "?$query")
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10000
                    conn.readTimeout = timeoutMs
                    conn.requestMethod = method
                    conn.setRequestProperty("Accept", "application/json")
                    val token = prefs.hubToken()
                    if (token.isNotBlank()) {
                        val basic = Base64.encodeToString("scenix:$token".toByteArray(), Base64.NO_WRAP)
                        conn.setRequestProperty("Authorization", "Basic $basic")
                    }
                    if (body != null) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    status = conn.responseCode
                    val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                    if (text.isNotBlank()) {
                        root = try {
                            JSONObject(text)
                        } catch (t: Throwable) {
                            Log.w(TAG, "native $path answered non-JSON", t)
                            null
                        }
                    }
                    if (status !in 200..299) {
                        error = root?.optString("error", "")?.takeIf { it.isNotBlank() }
                            ?: "The hub answered $status"
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "native request $path failed", t)
                error = t.message ?: "The hub is not answering"
            }
            main.post { done(root, status, error) }
        }
    }

    private fun parse(root: JSONObject): List<BrowseRow> {
        val arr = root.optJSONArray("rows") ?: return emptyList()
        val out = ArrayList<BrowseRow>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val itemsArr = r.optJSONArray("items") ?: continue
            val items = ArrayList<Item>()
            for (j in 0 until itemsArr.length()) {
                val o = itemsArr.optJSONObject(j) ?: continue
                val item = Item.from(o)
                if (item.identity.isNotBlank()) items.add(item)
            }
            if (items.isEmpty()) continue
            out.add(
                BrowseRow(
                    id = s(r, "id", ""),
                    title = s(r, "title", ""),
                    card = s(r, "card", "poster"),
                    items = items,
                    total = r.optInt("total", items.size),
                )
            )
        }
        return out
    }

    private companion object {
        const val TAG = "ScenixBrowse"

        /** A path segment that survives a title identity and never doubles a slash. */
        fun enc(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
