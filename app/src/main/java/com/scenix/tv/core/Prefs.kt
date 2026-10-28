package com.scenix.tv.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Local persistence for the shell: which backend this TV talks to, and the hub addresses it has
 * been offered or has worked.
 *
 * Profiles, PINs, hidden rows and watch progress live on the hub, so a reinstall keeps them. This
 * file only holds what the app must know before it can talk to anything — which is exactly why no
 * address is ever baked into the build.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("scenix", Context.MODE_PRIVATE)

    // ---- hub -----------------------------------------------------------------------------
    fun hubUrl(fallback: String): String = sp.getString(KEY_HUB, null)?.takeIf { it.isNotBlank() } ?: fallback
    fun setHubUrl(url: String) = sp.edit().putString(KEY_HUB, url.trim()).apply()

    /**
     * Every hub this TV is allowed to talk to, in the order it should try them.
     *
     * A hub is just a Node process the user can run on any machine, so the same TV may face a LAN
     * address today, a hosted address tomorrow and a different machine next year. Listing several
     * and remembering which one last answered means the TV keeps working when the hub moves,
     * without anyone re-typing an address on a 10-foot remote.
     *
     * Order is the order of trust: the address this TV chose, then wherever it last actually got an
     * answer, then whatever the LAN announcement has seen, then a pre-seeded default list.
     */
    fun hubUrlCandidates(defaultCsv: String): List<String> {
        val out = ArrayList<String>()
        fun add(u: String?) {
            val t = u?.trim()?.trimEnd('/') ?: return
            if (t.isNotEmpty() && !out.contains(t)) out.add(t)
        }
        // explicit choice in Settings wins, then wherever the TV last actually got an answer.
        add(sp.getString(KEY_HUB, null))
        add(sp.getString(KEY_HUB_LAST, null))
        discoveredHubs().forEach { add(it.url) }
        defaultCsv.split(',').forEach { add(it) }
        return out
    }

    fun setHubLastWorking(url: String) = sp.edit().putString(KEY_HUB_LAST, url.trim()).apply()
    fun hubLastWorking(): String? = sp.getString(KEY_HUB_LAST, null)?.takeIf { it.isNotBlank() }

    /** Optional shared access token for a hub that sets HUB_TOKEN. Blank means "no token configured". */
    fun hubToken(): String = sp.getString(KEY_TOKEN, "") ?: ""
    fun setHubToken(token: String) = sp.edit().putString(KEY_TOKEN, token.trim()).apply()

    // ---- which backend this TV talks to ------------------------------------------------------
    //
    // A build names no machine, so a stranger's first run has three ways in — a hub announcing
    // itself on the LAN, an address typed by hand, or this app hosting the contract itself. Which
    // one was chosen is remembered, so the app does not ask again, and "nothing paired" is a
    // state the UI can act on instead of a spinner.

    /** "" = never chose | "hub" = a hub was picked | "standalone" = this TV hosts the contract. */
    fun backendChoice(): String = sp.getString(KEY_BACKEND, "") ?: ""
    fun setBackendChoice(choice: String) = sp.edit().putString(KEY_BACKEND, choice).apply()
    fun standalone(): Boolean = backendChoice() == BACKEND_STANDALONE

    /** Hubs found by the LAN announcement, kept so the next run can list them by name. */
    fun discoveredHubs(): List<HubAddress> {
        val raw = sp.getString(KEY_DISCOVERED, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url", "").trim().trimEnd('/')
                if (u.isEmpty()) null else HubAddress(u, o.optString("name", ""))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "discovered hub list unreadable", t)
            emptyList()
        }
    }

    fun setDiscoveredHubs(list: List<HubAddress>) {
        val arr = org.json.JSONArray()
        for (h in list) arr.put(org.json.JSONObject().put("url", h.url).put("name", h.name))
        sp.edit().putString(KEY_DISCOVERED, arr.toString()).apply()
    }

    /** Remember one address the viewer chose, so Settings can print its name back. */
    fun setHubName(url: String, name: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.isEmpty()) return
        val list = ArrayList(discoveredHubs().filter { it.url != clean })
        list.add(HubAddress(clean, name))
        setDiscoveredHubs(list)
    }

    /** `--es fixture <asset-path>`: when set, the app reads rows from assets instead of the network. */
    fun fixture(): String? = sp.getString(KEY_FIXTURE, null)?.takeIf { it.isNotBlank() }
    fun setFixture(path: String?) = sp.edit().putString(KEY_FIXTURE, path ?: "").apply()

    companion object {
        private const val TAG = "ScenixPrefs"
        private const val KEY_HUB = "hub_url"
        private const val KEY_HUB_LAST = "hub_url_last_working"
        private const val KEY_BACKEND = "backend_choice"
        private const val KEY_DISCOVERED = "discovered_hubs"
        private const val KEY_TOKEN = "hub_token"
        const val BACKEND_HUB = "hub"
        const val BACKEND_STANDALONE = "standalone"
        private const val KEY_FIXTURE = "fixture"
    }
}
