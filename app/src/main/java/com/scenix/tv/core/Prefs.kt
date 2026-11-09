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

    // This capability is intentionally process-memory only. It is proof for a single unlocked
    // Settings visit, not a preference that should survive app restart or be replayed to another Hub.
    @Volatile private var adminSessionToken = ""
    @Volatile private var adminSessionOrigin = ""
    @Volatile private var adminSessionExpiresAt = 0L

    /**
     * The visual theme's id in assets/themes.json. It is device-local; Settings mirrors only a
     * theme explicitly declared for both TV and web, so a TV-only layout cannot make another
     * surface silently fall back to a different look.
     */
    // Apple is the product's reference TV treatment.  Existing households keep their explicit
    // choice, while a new television opens directly into the polished remote-first theme instead
    // of inheriting the legacy dark pack.
    fun theme(): String = sp.getString(KEY_THEME, "scenix-apple") ?: "scenix-apple"
    fun setTheme(id: String) = sp.edit().putString(KEY_THEME, id.trim()).apply()

    fun setAdminSession(token: String, origin: String, expiresAt: Long) {
        val cleanToken = token.trim()
        val cleanOrigin = origin.trim().trimEnd('/').lowercase()
        val now = System.currentTimeMillis()
        if (cleanToken.isBlank() || cleanOrigin.isBlank() || expiresAt <= now) {
            clearAdminSession()
            return
        }
        adminSessionToken = cleanToken
        adminSessionOrigin = cleanOrigin
        // A malformed remote timestamp cannot turn a short-lived session into a process-lifetime one.
        adminSessionExpiresAt = minOf(expiresAt, now + 60L * 60L * 1000L)
    }

    fun adminSessionFor(origin: String): String {
        val cleanOrigin = origin.trim().trimEnd('/').lowercase()
        if (cleanOrigin.isBlank() || cleanOrigin != adminSessionOrigin ||
            System.currentTimeMillis() >= adminSessionExpiresAt
        ) {
            if (System.currentTimeMillis() >= adminSessionExpiresAt) clearAdminSession()
            return ""
        }
        return adminSessionToken
    }

    fun clearAdminSession() {
        adminSessionToken = ""
        adminSessionOrigin = ""
        adminSessionExpiresAt = 0L
    }

    /**
     * A decoder that says it supports HEVC but fails on the first 10-bit file is a fact about this
     * panel, learned the hard way. Once set, the player stops advertising HEVC to the hub and asks
     * for an H.264 copy instead of offering the same black screen again.
     */
    fun hevcBlocked(): Boolean = sp.getBoolean(KEY_HEVC_BLOCKED, false)
    fun setHevcBlocked(blocked: Boolean) = sp.edit().putBoolean(KEY_HEVC_BLOCKED, blocked).apply()

    /**
     * Audio codecs this panel advertised but could not actually open. A Sony BRAVIA lists AC3/EAC3
     * decoders and answers `format_supported=YES`, then `AudioTrack` creation fails on the built-in
     * speakers ("AudioTrack init failed 0 Config(48000, 252, 40000)") — the picture never starts even
     * though the hub was told the codec is fine. Remember it once, stop advertising it, and pick
     * another track in the same file instead of failing the copy.
     */
    fun audioBlockedCodecs(): Set<String> = sp.getString(KEY_AUDIO_BLOCKED, "")
        .orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

    fun blockAudioCodec(codec: String) {
        val c = codec.trim().lowercase()
        if (c.isBlank()) return
        val next = (audioBlockedCodecs() + c).sorted().joinToString(",")
        sp.edit().putString(KEY_AUDIO_BLOCKED, next).apply()
    }

    // ---- auto-update (core/Updates.kt) -----------------------------------------------------
    // On by default: a TV nobody thinks to update is a TV that quietly falls behind. The prompt
    // itself is still the person's decision — the check finds a build, the remote presses Install.
    fun autoUpdate(): Boolean = sp.getBoolean(KEY_AUTO_UPDATE, true)
    fun setAutoUpdate(on: Boolean) = sp.edit().putBoolean(KEY_AUTO_UPDATE, on).apply()

    /** When the last update check ran, so boot checks cost at most one GitHub call a day. */
    fun lastUpdateCheck(): Long = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
    fun setLastUpdateCheck(at: Long) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, at).apply()

    // ---- hub -----------------------------------------------------------------------------
    fun hubUrl(fallback: String): String = sp.getString(KEY_HUB, null)?.takeIf { it.isNotBlank() } ?: fallback
    fun setHubUrl(url: String) = sp.edit().putString(KEY_HUB, url.trim()).apply()

    /**
     * Every Hub this TV may probe at startup, in trusted order.
     *
     * A selected address is intentionally the complete list. A failed explicit choice must not
     * turn into hidden probes of other discovered/default LAN machines; reconnecting elsewhere is
     * a choice made from the pairing surface. Before an address has been selected, recovery and
     * first-run discovery may use the remembered/discovered/default candidates.
     */
    fun hubUrlCandidates(defaultCsv: String): List<String> {
        return HubCandidatePolicy.candidates(
            selected = sp.getString(KEY_HUB, null),
            lastWorking = sp.getString(KEY_HUB_LAST, null),
            discovered = discoveredHubs().map { it.url },
            defaultsCsv = defaultCsv,
        )
    }

    fun setHubLastWorking(url: String) = sp.edit().putString(KEY_HUB_LAST, url.trim()).apply()
    fun hubLastWorking(): String? = sp.getString(KEY_HUB_LAST, null)?.takeIf { it.isNotBlank() }

    /** Optional shared access token for a hub that sets HUB_TOKEN. Blank means "no token configured". */
    fun hubToken(): String = sp.getString(KEY_TOKEN, "") ?: ""
    fun setHubToken(token: String) = sp.edit().putString(KEY_TOKEN, token.trim()).apply()

    /**
     * The last answer to "which optional add-ons this household installed". Home paints its bar
     * before the hub can answer, so the previous shape is drawn first and corrected on the next
     * load — a tab never appears from a guess, only from a fact. Null until a hub has answered once.
     */
    fun addonCaps(): Pair<Boolean, Boolean>? {
        if (!sp.getBoolean(KEY_CAPS_KNOWN, false)) return null
        return sp.getBoolean(KEY_CAPS_LIVE, false) to sp.getBoolean(KEY_CAPS_GAMES, false)
    }

    fun setAddonCaps(live: Boolean, games: Boolean) {
        sp.edit().putBoolean(KEY_CAPS_KNOWN, true)
            .putBoolean(KEY_CAPS_LIVE, live)
            .putBoolean(KEY_CAPS_GAMES, games)
            .apply()
    }

    /**
     * A profile the web gate has unlocked in this app session. Native Detail needs it to open the
     * shared player without asking for the same PIN on every Play press. It is a local, expiring
     * trust mark, not a replacement for the hub's PIN check: a browser never sees it.
     */
    fun markProfileUnlocked(id: String) = sp.edit()
        .putString(KEY_PROFILE_UNLOCKED, id)
        .putLong(KEY_PROFILE_UNLOCKED_AT, System.currentTimeMillis())
        .apply()

    fun profileUnlocked(id: String): Boolean {
        val held = sp.getString(KEY_PROFILE_UNLOCKED, "") ?: ""
        val at = sp.getLong(KEY_PROFILE_UNLOCKED_AT, 0L)
        return id.isNotBlank() && held == id && System.currentTimeMillis() - at < PROFILE_SESSION_MS
    }

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

    /**
     * The viewer's own list. The web UI keeps it in localStorage; the television keeps the same
     * per-profile set here, so the two stay honest about the same household without needing the
     * hub to grow a new endpoint.
     */
    fun myList(profileId: String): MutableList<String> {
        val raw = sp.getString("mylist_$profileId", "") ?: ""
        if (raw.isBlank()) return ArrayList()
        return try {
            val arr = org.json.JSONArray(raw)
            ArrayList((0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotBlank() } })
        } catch (t: Throwable) {
            ArrayList()
        }
    }

    fun setMyList(profileId: String, ids: List<String>) {
        val arr = org.json.JSONArray()
        for (id in ids) arr.put(id)
        sp.edit().putString("mylist_$profileId", arr.toString()).apply()
    }

    /**
     * An accessibility zoom for bad eyes and far couches: one multiplier over the whole theme, so
     * text, cards, gaps and the hero all grow together instead of the app just shouting in one
     * place. Applied when the theme is next built — changing it offers an immediate relaunch.
     */
    fun uiZoom(): Float = sp.getFloat("ui_zoom", 1f)
    fun setUiZoom(f: Float) = sp.edit().putFloat("ui_zoom", f).apply()

    // ---- subtitles ---------------------------------------------------------------------------
    // A television is watched from a couch: the default caption size is comfortable at 100% but too
    // small at 150% zoom, and a viewer with a bright wall may need yellow. These are device
    // preferences (they describe this panel and this person's eyes), not hub profile data.
    /** 0 small, 1 medium, 2 large. */
    fun captionSize(): Int = sp.getInt(KEY_CAPTION_SIZE, 1).coerceIn(0, 2)
    fun setCaptionSize(v: Int) = sp.edit().putInt(KEY_CAPTION_SIZE, v.coerceIn(0, 2)).apply()
    /** white | yellow */
    fun captionColour(): String = sp.getString(KEY_CAPTION_COLOUR, "white") ?: "white"
    fun setCaptionColour(v: String) = sp.edit().putString(KEY_CAPTION_COLOUR, v).apply()
    /** none | dim | black */
    fun captionBackground(): String = sp.getString(KEY_CAPTION_BACKGROUND, "dim") ?: "dim"
    fun setCaptionBackground(v: String) = sp.edit().putString(KEY_CAPTION_BACKGROUND, v).apply()
    /** 0 = bottom, 1 = 5%, 2 = 10% up from the bottom edge. */
    fun captionOffset(): Int = sp.getInt(KEY_CAPTION_OFFSET, 1).coerceIn(0, 2)
    fun setCaptionOffset(v: Int) = sp.edit().putInt(KEY_CAPTION_OFFSET, v.coerceIn(0, 2)).apply()

    companion object {
        private const val TAG = "ScenixPrefs"
        private const val KEY_HUB = "hub_url"
        private const val KEY_HUB_LAST = "hub_url_last_working"
        private const val KEY_BACKEND = "backend_choice"
        private const val KEY_DISCOVERED = "discovered_hubs"
        private const val KEY_THEME = "theme"
        private const val KEY_HEVC_BLOCKED = "hevc_blocked"
        private const val KEY_AUDIO_BLOCKED = "audio_blocked_codecs"
        private const val KEY_AUTO_UPDATE = "auto_update"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_TOKEN = "hub_token"
        private const val KEY_CAPS_KNOWN = "addon_caps_known"
        private const val KEY_CAPS_LIVE = "addon_caps_live"
        private const val KEY_CAPS_GAMES = "addon_caps_games"
        private const val KEY_PROFILE_UNLOCKED = "profile_unlocked"
        private const val KEY_PROFILE_UNLOCKED_AT = "profile_unlocked_at"
        private const val PROFILE_SESSION_MS = 6L * 60 * 60 * 1000
        const val BACKEND_HUB = "hub"
        const val BACKEND_STANDALONE = "standalone"
        private const val KEY_FIXTURE = "fixture"
        private const val KEY_CAPTION_SIZE = "caption_size"
        private const val KEY_CAPTION_COLOUR = "caption_colour"
        private const val KEY_CAPTION_BACKGROUND = "caption_background"
        private const val KEY_CAPTION_OFFSET = "caption_offset"
    }
}
