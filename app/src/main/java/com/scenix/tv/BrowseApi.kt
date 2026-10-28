package com.scenix.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.scenix.tv.core.Item
import com.scenix.tv.core.Prefs
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

/**
 * The small native read path for the browse surface. The hub remains the source of truth; this only
 * fetches `/app/v1/home` and turns the same item JSON into the model the native cards render.
 *
 * It deliberately does not become a second HubClient: there is one call here because there is one
 * native screen. Detail, Player and Settings still use the web contract.
 */
class BrowseApi(private val context: Context, private val prefs: Prefs) {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "scenix-browse").apply { isDaemon = true } }

    fun load(profileId: String, done: (rows: List<BrowseRow>?, error: String?) -> Unit) {
        pool.execute {
            var rows: List<BrowseRow>? = null
            var error: String? = null
            try {
                val base = WebShellActivity.apiBase(context)
                val url = "$base/app/v1/home?profile=${URLEncoder.encode(profileId, "UTF-8")}"
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Accept", "application/json")
                    val token = prefs.hubToken()
                    if (token.isNotBlank()) {
                        val basic = Base64.encodeToString("scenix:$token".toByteArray(), Base64.NO_WRAP)
                        conn.setRequestProperty("Authorization", "Basic $basic")
                    }
                    if (conn.responseCode !in 200..299) {
                        error = "The hub answered ${conn.responseCode}"
                    } else {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        rows = parse(JSONObject(body))
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "browse load failed", t)
                error = t.message ?: "The hub is not answering"
            }
            main.post { done(rows, error) }
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

    private companion object { const val TAG = "ScenixBrowse" }
}
