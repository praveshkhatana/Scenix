package com.scenix.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.scenix.tv.core.Item
import com.scenix.tv.core.Prefs
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
)

/** One person the native profile gate offers. */
data class ProfileInfo(
    val id: String,
    val name: String,
    val kids: Boolean,
    val pinSet: Boolean,
    val locked: Boolean,
    val avatar: String,
)

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
                out.add(
                    ProfileInfo(
                        id = o.optString("id", ""),
                        name = o.optString("name", ""),
                        kids = o.optBoolean("isKids", o.optBoolean("kids", false)) ||
                            o.optString("kind", "").equals("kids", true),
                        pinSet = o.optBoolean("pinSet", o.optBoolean("hasPin", o.optBoolean("pinRequired", false))),
                        locked = o.optBoolean("locked", false),
                        avatar = o.optString("avatar", ""),
                    )
                )
            }
            done(out, null)
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

    fun absolute(path: String): String {
        if (path.isBlank() || path.startsWith("http://") || path.startsWith("https://")) return path
        return WebShellActivity.apiBase(context) + (if (path.startsWith("/")) path else "/$path")
    }

    fun meta(identity: String, profileId: String, done: (item: JSONObject?, error: String?) -> Unit) {
        val path = "/app/v1/meta/" + enc(identity)
        request(path, "profile=" + enc(profileId), "GET", null, 20000) { root, _, error ->
            done(root?.optJSONObject("item") ?: root, error)
        }
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
                val base = WebShellActivity.apiBase(context)
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
                    id = r.optString("id", ""),
                    title = r.optString("title", ""),
                    card = r.optString("card", "poster"),
                    items = items,
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
