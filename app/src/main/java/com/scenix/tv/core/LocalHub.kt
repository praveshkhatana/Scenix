package com.scenix.tv.core

import android.content.Context
import android.util.Log
import com.scenix.tv.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Standalone mode: this app hosts the `/app/v1` contract itself (mirroring
 * Rule 2). It answers the same JSON a hub answers, over loopback, so the page cannot tell which
 * machine it is talking to. The only thing the UI branches on is what a backend says it can do —
 * the `capabilities` list in its manifest.
 *
 * Where the content comes from: addons, over plain HTTP, in the community manifest protocol
 * (`manifest`, `catalog`, `meta`, `stream`, `subtitles`). Which addons, and how each is configured,
 * belongs to the owner — it is read from this app's own settings, never from a constant, so a build
 * ships no content source at all and an empty home is a normal first-run state rather than a bug.
 *
 * What this mode honestly does: addons, direct HTTP/HLS/DASH, a local torrent engine, an optional
 * Real-Debrid account, profiles and an M3U live wall. What it does not have is stated in
 * `capabilities` instead of broken in the UI: there is no local file browser and no server-side
 * remux/track probing; a source is playable if it is direct or the engine/provider can hand over HTTP.
 * There is no live tuner, no local files, no
 * trailer search. Anything not listed is not offered.
 *
 * Everything the addons answer is cached under `filesDir`, along with this app's own profiles,
 * progress and memory of the titles it has shown. The home path is cache-first, so a slow addon
 * costs a shorter shelf rather than a hung TV; only a detail page and a Play press wait for an
 * answer.
 */
class LocalHub private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val addons = AddonClient(appContext)

    /** Everything this app owns for itself: settings, profiles, what it has seen, where people stopped. */
    private val data = File(appContext.filesDir, "scenix").apply { mkdirs() }
    private val settingsFile = File(data, "settings.json")
    private val profilesFile = File(data, "profiles.json")
    private val libraryFile = File(data, "library.json")
    private val progressFile = File(data, "progress.json")
    private val listFile = File(data, "list.json")
    private val prefStore = File(data, "profile-prefs.json")

    // ---- the owner's settings -----------------------------------------------------------------

    /**
     * `{ addons: [...], audioLanguages: [...], subtitleLanguages: [...], adult: false }` in one file.
     * A brand-new install starts the same way a fresh hub does — with the handful of keyless, public
     * sources the product ships as defaults (metadata, subtitle search, watch links) — so the home
     * screen fills with content instead of an empty-state card. An owner who removes every addon keeps
     * an empty list: the seed applies to a settings file that has never been written, never to one
     * someone has edited.
     */
    fun settings(): JSONObject = synchronized(lock) {
        val o = readJson(settingsFile)
        val seededAddons = if (!settingsFile.exists()) JSONArray(DEFAULT_STANDALONE_ADDONS.map { JSONObject(it) }) else null
        JSONObject()
            .put("addons", o.optJSONArray("addons") ?: seededAddons ?: JSONArray())
            .put("audioLanguages", o.optJSONArray("audioLanguages") ?: JSONArray())
            .put("subtitleLanguages", o.optJSONArray("subtitleLanguages") ?: JSONArray())
            .put("adult", o.optBoolean("adult", false))
            .put("cacheTtlHours", o.optInt("cacheTtlHours", 6))
            .put("theme", o.optString("theme", "scenix-apple"))
            .put("debrid", o.optJSONObject("debrid") ?: defaultDebrid())
            .put("live", o.optJSONObject("live") ?: JSONObject())
            // The folder this TV may read, as a SAF tree URI plus the words the owner sees. Empty is
            // a normal state: there are no local files until someone picks a folder.
            .put("local", o.optJSONObject("local") ?: JSONObject())
    }

    /**
     * What a fresh standalone install has enabled, mirroring what a fresh hub seeds (addons.example.json):
     * keyless public sources only — the title catalogue and metadata, the subtitle search, the watch-link
     * service, and the household's own games shelf. No torrent index, no paid account, no credential.
     */
    private val DEFAULT_STANDALONE_ADDONS = listOf(
        mapOf("id" to "cinemeta", "name" to "Cinemeta",
            "url" to "https://v3-cinemeta.strem.io/manifest.json", "enabled" to true, "priority" to 40),
        mapOf("id" to "opensubtitles", "name" to "OpenSubtitles v3",
            "url" to "https://opensubtitles-v3.strem.io/manifest.json", "enabled" to true, "priority" to 41),
        mapOf("id" to "watchhub", "name" to "WatchHub",
            "url" to "https://watchhub.strem.io/manifest.json", "enabled" to true, "priority" to 42),
        mapOf("id" to "scenix-games", "name" to "Scenix Games",
            "url" to "https://praveshkhatana.github.io/scenix-games-addon/manifest.json",
            "enabled" to true, "priority" to 43),
    )

    /** Merge what the caller sent into what is stored, so a screen that edits one field keeps the rest. */
    fun setSettings(body: JSONObject): JSONObject = synchronized(lock) {
        val merged = settings()
        for (k in arrayOf("addons", "audioLanguages", "subtitleLanguages")) {
            if (body.has(k)) merged.put(k, body.optJSONArray(k) ?: JSONArray())
        }
        if (body.has("adult")) merged.put("adult", body.optBoolean("adult", false))
        if (body.has("cacheTtlHours")) merged.put("cacheTtlHours", body.optInt("cacheTtlHours", 6))
        if (body.has("theme")) merged.put("theme", body.optString("theme", "scenix-apple"))
        if (body.has("debrid")) {
            // A token is write-only: null removes it, a blank string keeps the stored one.
            val cur = merged.optJSONObject("debrid") ?: defaultDebrid()
            val next = body.optJSONObject("debrid") ?: JSONObject()
            val out = JSONObject(cur.toString())
            if (next.has("enabled")) out.put("enabled", next.optBoolean("enabled", false))
            if (next.has("provider")) out.put("provider", next.optString("provider", "real-debrid"))
            if (next.has("token")) {
                if (next.isNull("token")) out.put("token", "")
                else next.optString("token").takeIf { it.isNotBlank() }?.let { out.put("token", it) }
            }
            merged.put("debrid", out)
        }
        if (body.has("live")) merged.put("live", body.optJSONObject("live") ?: JSONObject())
        if (body.has("local")) {
            // A tree URI is a capability, not a preference: store it only if this app still holds the
            // grant, so a restored settings file cannot point at a folder the OS will refuse.
            val next = body.optJSONObject("local") ?: JSONObject()
            val uri = next.optString("tree").trim()
            val name = next.optString("name").trim()
            val out = JSONObject()
            if (uri.isNotBlank() && localLibrary.hasAccess(uri)) {
                out.put("tree", uri).put("name", name.ifBlank { "Local files" })
                localLibrary.refresh(uri)
            }
            merged.put("local", out)
        }
        writeJson(settingsFile, merged)
        merged
    }

    /**
     * The addons this TV has been told about. Content asks for the enabled ones; the list shown to the
     * owner includes the switched-off ones, because an addon that is off still has to be seen to be
     * turned back on.
     */
    fun addonSpecs(includeDisabled: Boolean = false): List<AddonSpec> {
        val arr = settings().optJSONArray("addons") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = s(o, "manifest", s(o, "url", "")).trim()
            if (url.isBlank()) return@mapNotNull null
            val name = s(o, "name", "").trim()
            AddonSpec(
                id = s(o, "id", "").ifBlank { slug(url) },
                name = name.ifBlank { hostOf(url) },
                manifestUrl = url,
                enabled = o.optBoolean("enabled", true),
            )
        }.filter { it.enabled || includeDisabled }
    }

    /** Addons this app can answer for right now — an unread manifest simply contributes nothing. */
    private fun addonManifests(): List<AddonManifest> =
        addonSpecs().mapNotNull { addons.manifest(it, wait = false, ttlMs = ttlMs()) }

    private fun ttlMs(): Long = settings().optLong("cacheTtlHours", 6L).coerceIn(1L, 24L) * 3600_000L

    /** Where this TV keeps its settings, named the way a hub names the file its addons live in. */
    fun settingsPath(): String = settingsFile.absolutePath

    /**
     * The envelope a hub answers `GET /app/v1/settings` with: what is stored, what an untouched file would
     * mean, the words the language codes in it stand for, and where the values live on this box. A screen
     * needs the defaults beside the settings to tell an owner's choice from a build's silence.
     */
    fun settingsEnvelope(): JSONObject = JSONObject()
        .put("settings", publicSettings())
        .put("defaults", defaultSettings())
        .put("languageNames", languageNames())
        .put("themes", themeCatalog())
        .put("debrid", debridStatus())
        .put("live", liveEnvelope())
        .put("file", settingsPath())

    private fun defaultDebrid(): JSONObject = JSONObject()
        .put("enabled", false).put("provider", "real-debrid").put("token", "")

    /** The settings shape a screen may see: the token is write-only and never crosses the contract. */
    private fun publicSettings(): JSONObject {
        val out = JSONObject(settings().toString())
        val d = out.optJSONObject("debrid") ?: defaultDebrid()
        d.remove("token")
        d.put("tokenSet", ((settings().optJSONObject("debrid") ?: JSONObject()).optString("token")).isNotBlank())
        out.put("debrid", d)
        return out
    }

    private fun debridToken(): String {
        val d = settings().optJSONObject("debrid") ?: return ""
        if (!d.optBoolean("enabled", false)) return ""
        if (d.optString("provider", "real-debrid") != "real-debrid") return ""
        return d.optString("token").trim()
    }

    fun debridStatus(): JSONObject {
        val d = settings().optJSONObject("debrid") ?: defaultDebrid()
        val token = d.optString("token").trim()
        val enabled = d.optBoolean("enabled", false)
        val ready = enabled && d.optString("provider", "real-debrid") == "real-debrid" && token.isNotBlank()
        val note = when {
            !enabled -> "off: P2P copies use this TV's engine"
            token.isBlank() -> "enabled, but no token is stored"
            else -> "Real-Debrid is configured; magnets are tried before the local engine"
        }
        return JSONObject()
            .put("enabled", enabled)
            .put("provider", d.optString("provider", "real-debrid"))
            .put("providerLabel", "Real-Debrid")
            .put("tokenSet", token.isNotBlank())
            .put("ready", ready)
            .put("providers", JSONArray().put(JSONObject().put("id", "real-debrid").put("label", "Real-Debrid")))
            .put("note", note)
    }

    /**
     * Live settings and the owner's channel list, in the shape the web and native settings expect.
     *
     * This is the channel manager's list, not the row: every channel is here, including one the owner
     * switched off, because a channel that is off still has to be seen to be switched back on. The
     * fields are the hub's `channelList()` fields — `identity`, `enabled`, `number`, `quality`,
     * `groups` — and the native manager ignores a tile without an `identity`, which is why this used
     * to draw an empty wall.
     */
    private fun liveEnvelope(): JSONObject {
        val live = settings().optJSONObject("live") ?: JSONObject()
        val hidden = ids(live.optJSONArray("hidden")).toSet()
        val groupsHidden = ids(live.optJSONArray("groupsHidden")).toSet()
        val channels = channelsJson(waitForFirst = true)
        val list = JSONArray()
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val identity = c.optString("identity", c.optString("id"))
            if (identity.isBlank()) continue
            val groups = jsonStrings(c.optJSONArray("groups"))
            val enabled = identity !in hidden && groups.none { it in groupsHidden }
            val number = liveHealth.numberFor(identity)
            list.put(
                JSONObject()
                    .put("identity", identity)
                    .put("name", c.optString("name"))
                    .put("group", c.optString("group"))
                    .put("groups", JSONArray(groups))
                    .put("logo", c.optString("logo"))
                    .put("quality", c.optString("quality"))
                    .put("enabled", enabled)
                    .put("number", if (number > 0) number else JSONObject.NULL)
                    .put("health", liveHealth.healthOf(identity))
                    .put("latencyMs", liveHealth.get(identity)?.optLong("latencyMs", 0L) ?: 0L)
            )
        }
        return JSONObject(live.toString())
            .put("channels", list)
            .put("groups", liveGroups(channels))
            .put("health", liveHealth.status())
    }

    private fun ids(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotBlank() && v !in out) out.add(v)
        }
        return out
    }

    /** What a settings file that says nothing means: no addon, no language and no channel preselected. */
    private fun defaultSettings(): JSONObject = JSONObject()
        .put("addons", JSONArray())
        .put("audioLanguages", JSONArray())
        .put("subtitleLanguages", JSONArray())
        .put("adult", false)
        .put("cacheTtlHours", 6)
        .put("theme", "scenix-apple")
        .put("debrid", defaultDebrid())
        .put("live", JSONObject())
        .put("local", JSONObject())

    /**
     * Words for the codes this backend actually holds. There is no table of every language baked in here —
     * which tongues a household listens in is not a fact about this app — so a screen labels exactly the
     * choices in front of it.
     */
    /** The theme registry shipped in the APK's assets, in the same shape the hub serves. */
    fun themeCatalog(): JSONArray {
        return try {
            val text = assets.open("themes.json").bufferedReader().use { it.readText() }
            JSONObject(text).optJSONArray("themes") ?: JSONArray()
        } catch (t: Throwable) {
            JSONArray().put(JSONObject().put("id", "scenix-apple")
                .put("name", "Apple").put("asset", "theme-apple.json").put("pack", "Scene"))
        }
    }

    /** `GET /app/v1/theme`: the selected tokens, so the bundled web UI follows the TV's theme too. */
    fun themeJson(): JSONObject {
        val themes = themeCatalog()
        val wanted = settings().optString("theme", "scenix-apple")
        var choice: JSONObject? = null
        for (i in 0 until themes.length()) {
            val o = themes.optJSONObject(i) ?: continue
            if (o.optString("id") == wanted) { choice = o; break }
        }
        // A saved theme this build no longer ships is not an error: the first shipped pack answers.
        if (choice == null && themes.length() > 0) choice = themes.optJSONObject(0)
        val id = choice?.optString("id") ?: "scenix-apple"
        val name = choice?.optString("name") ?: "Apple"
        val asset = choice?.optString("asset")?.takeIf { it.isNotBlank() } ?: "theme-apple.json"
        val tokens = try {
            JSONObject(assets.open(asset).bufferedReader().use { it.readText() })
        } catch (t: Throwable) {
            JSONObject()
        }
        return JSONObject().put("id", id).put("name", name).put("themes", themes).put("tokens", tokens)
    }

    private fun languageNames(): JSONObject {
        val codes = LinkedHashSet<String>()
        val owned = settings()
        for (k in arrayOf("audioLanguages", "subtitleLanguages")) {
            owned.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) codes.add(a.optString(i)) }
        }
        for (p in profileList()) {
            p.optJSONArray("languages")?.let { a -> for (i in 0 until a.length()) codes.add(a.optString(i)) }
        }
        val out = JSONObject()
        for (c in codes.filter { it.isNotBlank() }) out.put(c, Format.langName(c))
        return out
    }

    // ---- the contract in JSON -----------------------------------------------------------------
    // [LocalServer] answers these paths directly, so the standalone page cannot drift from the
    // contract a hub serves.

    fun manifestJson(): JSONObject {
        val manifests = addonManifests()
        // The same object of facts the hub answers with, so a client UI branches once and cannot tell
        // which machine it is talking to. The keys a hub sets from machinery this TV does not have are
        // stated here as false rather than left out: an absent key reads as "unknown", and the whole
        // point is that nothing here is unknown.
        val caps = JSONObject()
            .put("gateway", true)      // a local libtorrent engine streams torrents over loopback HTTP
            // An owner-supplied M3U is a channel list this TV can tune; there is no terrestrial tuner.
            .put("live", settings().optJSONObject("live")?.optString("playlist")?.isNotBlank() == true)
            // True only while the owner's folder is still readable. A revoked grant says so rather
            // than advertising a shelf of files the OS will not open.
            .put("localFiles", localLibrary.hasAccess(localTree()))
            .put("remux", false)       // a file plays as it is, or not at all
            .put("tracks", false)      // languages are not read out of containers, only from addons
            .put("announce", false)    // a TV does not announce itself as a backend
            .put("search", true)       // one word, answered across every installed addon
            .put("subtitles", manifests.any { it.has("subtitles") })
            .put("settingsAdmin", true)// addons and languages: GET+PUT /app/v1/settings, answered here
            .put("addonAdmin", true)   // addons are added, enabled, disabled and removed over HTTP
            .put("prefsEdit", true)    // the definition of "the stream I want" is kept on this TV
            .put("profileEdit", true)  // this backend creates, changes and removes the people
            .put("cacheAdmin", true)   // what was cached from upstream can be thrown away on purpose
            .put("avatarFaces", hasFaces())  // only if this build actually carries the pictures
        if (debridStatus().optBoolean("ready")) caps.put("debrid", true)
        // No key is left out. A key that is absent reads as "the backend did not say", and the point of
        // this list is that a TV answering for itself says something about every one of them.
        val rows = JSONArray()
        for (r in declaredRows(manifests)) {
            rows.put(JSONObject().put("id", r.id).put("title", r.title).put("card", r.card).put("order", r.order))
        }
        return JSONObject()
            .put("id", "standalone")
            .put("name", appContext.getString(R.string.app_name))
            .put("version", appContext.getString(R.string.app_name) + " standalone")
            .put("theme", settings().optString("theme", "scenix-apple"))
            .put("api", "/app/v1")
            .put("capabilities", caps)
            .put("addons", addonsJson())
            .put("rows", rows)
            .put("cards", JSONArray(listOf("poster", "hero", "continue")))
            .put("players", JSONArray(listOf("http", "hls", "dash")))
    }

    /**
     * The two optional doors, in the small shape the navigation asks for. Same facts as the
     * manifest carries: live is a playlist this TV can tune, games is an installed addon that
     * declares the games resource. A TV answering for itself draws the same bar as a hub.
     */
    fun capsJson(): JSONObject {
        val live = settings().optJSONObject("live")?.optString("playlist")?.isNotBlank() == true
        val games = addonManifests().any { it.has("games") }
        return JSONObject().put("live", live).put("games", games)
    }

    fun profilesJson(): JSONObject {
        ensureProfiles()
        val out = JSONArray()
        for (p in profileList()) {
            out.put(
                wireProfile(p)
                    .put("prefs", readProfilePrefs(s(p, "id", "")))
                    // The shelves this backend declares — its addons' catalogs — not the ones that happened
                    // to return something on one pass. A hub says the same thing about its own row list.
                    .put("rows", JSONArray(declaredRows(addonManifests()).map { it.id })),
            )
        }
        return JSONObject().put("profiles", out)
    }

    /**
     * A profile as it leaves this app, with the same fields a hub sends. The code itself never travels:
     * `pinSet` says one exists and `locked` says one will actually be asked for, because a flag with no code
     * behind it is a wall that checks nothing and a code with no flag is a gate that never opens. This
     * backend holds the secret, so it is the one that knows which of the two is true.
     */
    private fun wireProfile(p: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in p.keys()) if (k != "pin") out.put(k, p.opt(k))
        val pinSet = s(p, "pin", "").isNotEmpty()
        return out
            .put("pinSet", pinSet)
            .put("locked", pinSet && p.optBoolean("pinRequired", false))
            .put("effectivePrefs", effectivePrefs(p))
    }

    /** What this profile really ranks with: what the viewer wrote, with the household's defaults filling the gaps. */
    private fun effectivePrefs(p: JSONObject): JSONObject {
        val mine = readProfilePrefs(s(p, "id", ""))
        val out = JSONObject()
        for (k in mine.keys()) out.put(k, mine.opt(k))
        val owned = settings()
        for (k in arrayOf("audioLanguages", "subtitleLanguages")) {
            if ((out.optJSONArray(k)?.length() ?: 0) == 0) out.put(k, owned.optJSONArray(k) ?: JSONArray())
        }
        return out
    }

    /**
     * The live wall, cache-first.
     *
     * A playlist is a network resource the size of a book, and home must not wait for it. A cached
     * list is answered immediately and refreshed behind the caller; a cold list answers empty on the
     * home path and starts one background fetch. Only a screen that has nothing else to show —
     * the settings channel manager, or a Play press for a channel — asks to wait.
     */
    fun channelsJson(waitForFirst: Boolean = false): JSONArray {
        val live = settings().optJSONObject("live") ?: JSONObject()
        val playlist = live.optString("playlist").trim()
        if (playlist.isBlank()) return JSONArray()
        val cached = synchronized(lock) {
            if (playlist != liveCacheKey) {
                // A different address is a different wall. Do not show yesterday's channels under it.
                liveCache = JSONArray()
                liveVariants.clear()
                liveNames.clear()
                liveCacheKey = playlist
                liveCacheAt = 0L
            }
            liveCache
        }
        val fresh = System.currentTimeMillis() - liveCacheAt < LIVE_TTL_MS
        if (fresh) {
            startLiveProbe()
            return cached
        }
        if (cached.length() == 0 && !waitForFirst) {
            refreshLiveAsync(playlist)
            return cached
        }
        if (cached.length() == 0) {
            val text = fetchText(playlist, 9000)
            if (!text.isNullOrBlank()) applyPlaylist(playlist, text)
        } else {
            refreshLiveAsync(playlist)
        }
        startLiveProbe()
        return synchronized(lock) { liveCache }
    }

    private fun refreshLiveAsync(playlist: String) {
        if (liveLoading) return
        liveLoading = true
        Thread({
            try {
                val text = fetchText(playlist, 9000)
                if (!text.isNullOrBlank()) {
                    applyPlaylist(playlist, text)
                    startLiveProbe()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "live playlist refresh failed", t)
            } finally {
                liveLoading = false
            }
        }, "scenix-live-refresh").apply { isDaemon = true }.start()
    }

    private fun applyPlaylist(playlist: String, text: String) {
        data class Parsed(
            val identity: String, val name: String, val groups: List<String>,
            val logo: String, val url: String, val quality: String,
        )

        val live = settings().optJSONObject("live") ?: JSONObject()
        val parsed = ArrayList<Parsed>()
        val seen = HashSet<String>()
        var pending: JSONObject? = null
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", true)) {
                pending = JSONObject()
                    .put("name", line.substringAfter(',', "").trim())
                    .put("group", attr(line, "group-title"))
                    .put("logo", attr(line, "tvg-logo"))
                continue
            }
            if (line.startsWith("#") || pending == null) continue
            var name = pending.optString("name").trim()
            var quality = ""
            // A trailing "(1080p)" is the playlist's own quality label, not part of the channel name.
            Regex("\\((\\d{3,4}p)\\)\\s*$", RegexOption.IGNORE_CASE).find(name)?.let { m ->
                quality = m.groupValues[1].lowercase()
                name = name.substring(0, m.range.first).trim()
            }
            val logo = pending.optString("logo").trim()
            val groups = pending.optString("group").split(";")
                .map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { listOf("Live") }
            val identity = "norm:" + slugOf(name)
            val key = name.lowercase() + "|" + quality
            pending = null
            if (name.isBlank() || identity == "norm:" || !seen.add(key)) continue
            parsed.add(Parsed(identity, name, groups, logo, line, quality))
        }
        // By name, then lightest variant first: a Wi-Fi dip should land on the 480p feed, not the
        // 1080p one that stalls. qbits() keeps "1080p" numerically higher than "720p".
        parsed.sortWith(compareBy({ it.name.lowercase() }, { qbits(it.quality) }))

        val hidden = ids(live.optJSONArray("hidden")).toSet()
        val groupsHidden = ids(live.optJSONArray("groupsHidden")).toSet()
        val out = JSONArray()
        val variants = HashMap<String, MutableList<LiveVariant>>()
        val names = HashMap<String, String>()
        val numbered = ArrayList<String>()
        for (c in parsed) {
            val kind = if (c.url.contains(".m3u8", true)) "hls" else "http"
            variants.getOrPut(c.identity) { ArrayList() }.add(LiveVariant(c.url, c.quality, kind))
            names[c.identity] = c.name
            if (out.length() == 0 || lastIdentity(out) != c.identity) {
                out.put(
                    JSONObject()
                        .put("id", c.identity)
                        .put("identity", c.identity)
                        .put("name", c.name)
                        .put("group", c.groups.first())
                        .put("groups", JSONArray(c.groups))
                        .put("logo", c.logo)
                        .put("quality", c.quality)
                        .put("url", c.url),
                )
                if (c.identity !in hidden && c.groups.none { it in groupsHidden }) {
                    numbered.add(c.identity)
                }
            }
        }
        synchronized(lock) {
            liveCache = out
            liveVariants.clear()
            liveVariants.putAll(variants)
            liveNames.clear()
            liveNames.putAll(names)
            liveCacheKey = playlist
            liveCacheAt = System.currentTimeMillis()
        }
        liveHealth.assignNumbers(numbered)
        saveLiveCache()
    }

    /**
     * The parsed list survives a restart. Without this, every cold start would show no live row until
     * a playlist the size of a book had been fetched again — the exact wait this cache exists to avoid.
     */
    private fun saveLiveCache() {
        try {
            val (playlist, list) = synchronized(lock) { liveCacheKey to liveCache }
            if (playlist.isBlank() || list.length() == 0) return
            val root = JSONObject().put("playlist", playlist).put("at", System.currentTimeMillis())
                .put("channels", list)
            val tmp = File(data, "live-cache.json.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(liveCacheFile)) {
                liveCacheFile.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "live cache write failed", t)
        }
    }

    private fun loadLiveCache() {
        try {
            if (!liveCacheFile.exists()) return
            val root = JSONObject(liveCacheFile.readText())
            val playlist = root.optString("playlist")
            val list = root.optJSONArray("channels") ?: return
            val current = settings().optJSONObject("live")?.optString("playlist")?.trim().orEmpty()
            if (playlist.isBlank() || playlist != current) return
            val variants = HashMap<String, MutableList<LiveVariant>>()
            val names = HashMap<String, String>()
            for (i in 0 until list.length()) {
                val c = list.optJSONObject(i) ?: continue
                val identity = c.optString("identity")
                if (identity.isBlank()) continue
                val url = c.optString("url")
                if (url.isBlank()) continue
                val kind = if (url.contains(".m3u8", true)) "hls" else "http"
                variants.getOrPut(identity) { ArrayList() }.add(LiveVariant(url, c.optString("quality"), kind))
                names[identity] = c.optString("name")
            }
            synchronized(lock) {
                liveCache = list
                liveVariants.clear()
                liveVariants.putAll(variants)
                liveNames.clear()
                liveNames.putAll(names)
                liveCacheKey = playlist
                // Deliberately old: the first home answers from disk and refreshes in the background.
                liveCacheAt = 0L
            }
        } catch (t: Throwable) {
            Log.w(TAG, "live cache unreadable", t)
        }
    }

    private fun lastIdentity(arr: JSONArray): String =
        (arr.optJSONObject(arr.length() - 1) ?: JSONObject()).optString("identity")

    private fun qbits(quality: String): Int =
        quality.filter { it.isDigit() }.toIntOrNull() ?: 0

    /**
     * Ask the prober to check the playlist in the background. It is rate-limited and never runs while
     * a film is playing, so this is safe to call from the home path.
     */
    private fun startLiveProbe() {
        if (liveVariants.isEmpty()) return
        val live = settings().optJSONObject("live") ?: JSONObject()
        val hidden = ids(live.optJSONArray("hidden")).toSet()
        val groupsHidden = ids(live.optJSONArray("groupsHidden")).toSet()
        val visible = HashSet<String>()
        for (i in 0 until liveCache.length()) {
            val c = liveCache.optJSONObject(i) ?: continue
            val identity = c.optString("identity")
            if (identity.isBlank() || identity in hidden) continue
            if (jsonStrings(c.optJSONArray("groups")).any { it in groupsHidden }) continue
            visible.add(identity)
        }
        val targets = liveVariants.asSequence()
            .filter { it.key in visible }
            // Never checked first, then the oldest evidence: repeated passes walk the whole wall
            // instead of re-checking the same head of the alphabet.
            .sortedBy { liveHealth.get(it.key)?.optLong("checkedAt", 0L) ?: 0L }
            .take(MAX_PROBE_PER_PASS)
            .map { (identity, list) ->
                LiveTarget(identity, liveNames[identity].orEmpty(), list.toList())
            }
            .toList()
        liveHealth.maybeProbe(targets)
    }

    private fun liveGroups(channels: JSONArray): JSONArray {
        val groups = LinkedHashSet<String>()
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val list = c.optJSONArray("groups")
            if (list != null) {
                for (j in 0 until list.length()) {
                    val g = list.optString(j).trim()
                    if (g.isNotBlank()) groups.add(g)
                }
            } else {
                val g = c.optString("group").trim()
                if (g.isNotBlank()) groups.add(g)
            }
        }
        return JSONArray(groups.sorted().toList())
    }

    private fun attr(line: String, key: String): String {
        val m = Regex(key + "=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)
        if (m != null) return m.groupValues[1]
        val u = Regex(key + "=([^\\s,]+)", RegexOption.IGNORE_CASE).find(line)
        return u?.groupValues?.getOrNull(1) ?: ""
    }

    private fun fetchText(url: String, timeoutMs: Int): String? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val opened = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Scenix/standalone")
            }
            conn = opened
            if (opened.responseCode !in 200..299) null
            else opened.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * The live wall as §1 items: one tile per channel name, every variant as a source. The working
     * variant leads, then the lightest; a channel the prober watched fail sorts behind one that
     * answered, and one that was never checked sits between them (unknown is not dead).
     */
    private fun channelItems(): List<Item> {
        val out = ArrayList<Item>()
        val channels = channelsJson()
        val hidden = ids(settings().optJSONObject("live")?.optJSONArray("hidden")).toSet()
        val groupsHidden = ids(settings().optJSONObject("live")?.optJSONArray("groupsHidden")).toSet()
        for (i in 0 until channels.length()) {
            val c = channels.optJSONObject(i) ?: continue
            val identity = c.optString("identity", c.optString("id"))
            if (identity.isBlank() || identity in hidden) continue
            val groups = jsonStrings(c.optJSONArray("groups")).ifEmpty {
                listOf(c.optString("group", "Live")).filter { it.isNotBlank() }
            }
            if (groups.any { it in groupsHidden }) continue
            val name = c.optString("name", "Channel")
            val variants = liveVariants[identity].orEmpty()
            val working = liveHealth.workingUrl(identity)
            val ordered = variants.sortedWith(compareBy(
                { if (working != null && it.url == working) 0 else 1 },
                { qbits(it.quality) },
            ))
            val sources = ordered.mapIndexed { index, v ->
                Source(
                    provider = "live",
                    label = liveLabel(v, index, ordered.size),
                    kind = v.kind,
                    quality = v.quality,
                    mode = "direct",
                    score = if (working != null && v.url == working) 10.0 else 0.0,
                    why = listOfNotNull(
                        "live channel",
                        v.quality.takeIf { it.isNotBlank() },
                        liveHealth.healthOf(identity).takeIf { it == "working" }?.let { "this feed answered" },
                    ),
                )
            }
            val best = ordered.maxByOrNull { qbits(it.quality) }?.quality.orEmpty()
            val h = liveHealth.get(identity)
            out.add(Item(
                identity = identity,
                type = "channel",
                title = name,
                poster = c.optString("logo").takeIf { it.isNotBlank() },
                genres = groups,
                live = Live(
                    now = groups.firstOrNull().orEmpty(),
                    quality = best,
                    number = liveHealth.numberFor(identity),
                    health = liveHealth.healthOf(identity),
                    checkedAt = h?.optLong("checkedAt", 0L) ?: 0L,
                    latencyMs = h?.optLong("latencyMs", 0L) ?: 0L,
                    error = h?.optString("error").orEmpty(),
                ),
                sources = sources,
            ))
        }
        out.sortWith(compareBy({ liveHealth.rank(it.identity) }, { it.live?.number ?: Int.MAX_VALUE }, { it.title.lowercase() }))
        return out
    }

    /** "1080p · host" when a channel has several feeds, the plain quality when it has one. */
    private fun liveLabel(v: LiveVariant, index: Int, total: Int): String {
        val q = v.quality.ifBlank { "stream" }
        val short = hostOf(v.url).ifBlank { "#" + (index + 1) }
        return if (total <= 1) q else q + " · " + short
    }

    fun homeJson(profileId: String?): JSONObject =
        JSONObject().put("rows", JSONArray(buildRows(profileId).map { rowJson(it) }))

    fun rowJson(rowId: String, profileId: String?): JSONObject? =
        buildRows(profileId).firstOrNull { it.id == rowId }?.let { rowJson(it) }

    fun searchJson(q: String, profileId: String?): JSONObject {
        val term = q.trim()
        val found = ArrayList<Item>()
        if (term.isNotEmpty()) {
            for (am in addonManifests()) {
                if (!am.has("catalog")) continue
                for (c in am.searchableCatalogs()) {
                    val type = communityType(s(c, "type", "movie")) ?: continue
                    val extra = "search=" + term
                    val metas = addons.catalog(am, type, s(c, "id", ""), extra, wait = false, ttlMs = ttlMs())
                        ?: continue
                    found.addAll(itemsFromMetas(metas, am, type))
                }
            }
            // Whatever this TV already met is searchable with the network down, and it is the part that
            // answers at once: titles that have been on a shelf or played before.
            found.addAll(libraryItems().filter { it.title.contains(term, true) })
            found.addAll(localItems().filter { it.title.contains(term, true) })
        }
        val ceiling = kidsCeiling(profileId)
        val items = dedupe(found)
            .filterNot { ceiling >= 0 && impliedAge(it.maturity) > ceiling }
            .sortedByDescending { it.match }
        val rows = JSONArray()
        if (items.isNotEmpty()) {
            rows.put(
                JSONObject()
                    .put("id", "search").put("title", "Search results").put("card", "poster")
                    .put("items", JSONArray(items.map { itemJson(it) })),
            )
        }
        return JSONObject().put("rows", rows)
    }

    /**
     * The age a child's shelf stops at, or -1 for a profile that is not a child's. A hub records an explicit
     * `age` beside `kind`; when this TV holds none it falls back to the ceiling it has always used, so a
     * profile created before that field existed keeps behaving as it did.
     */
    private fun kidsCeiling(profileId: String?): Int {
        val p = profileList().firstOrNull { s(it, "id", "") == (profileId ?: "default") } ?: return -1
        val kids = s(p, "kind", "").equals("kids", true) || p.optBoolean("isKids", false)
        if (!kids) return -1
        val age = p.optInt("age", -1)
        return if (age in 2..18) age else KIDS_MAX_AGE
    }

    /**
     * The age a maturity label implies, or -1 when it says nothing. A blank label is unknown and stays
     * in: an addon that did not classify a title is not a reason to hide it from a kids profile.
     */
    private fun impliedAge(maturity: String): Int {
        val m = maturity.trim().lowercase()
        if (m.isEmpty()) return -1
        Regex("(\\d{1,2})").find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return when (m) {
            "u", "g", "tv-y" -> 0
            "pg", "tv-pg", "tv-14" -> 13
            "a", "r", "nc-17", "tv-ma" -> 18
            else -> -1
        }
    }

    // ---- local files -----------------------------------------------------------------------------
    // The owner picks a folder once; LocalLibrary walks it and remembers the files. A local item is
    // an ordinary §1 item of type "video" whose one source is this TV's own byte route, so the
    // player receives a URL exactly as it does for a hub, and seeking is Range over loopback.

    private fun localTree(): String = settings().optJSONObject("local")?.optString("tree").orEmpty()

    fun localPermissionName(): String = localLibrary.mediaPermissionName()
    fun localPermissionNames(): Array<String> = localLibrary.mediaPermissionNames()
    fun localPermission(): Boolean = localLibrary.mediaPermission()

    fun localStatus(): JSONObject {
        val local = settings().optJSONObject("local") ?: JSONObject()
        return localLibrary.status(localTree())
            .put("name", local.optString("name", ""))
    }

    fun localEntry(id: String): LocalLibrary.Entry? =
        if (id.startsWith("local:")) localLibrary.find(localTree(), id) else null

    fun localStream(id: String): LocalLibrary.Stream? {
        val entry = localEntry(id) ?: return null
        return localLibrary.open(entry)
    }

    private fun localItems(): List<Item> {
        val tree = localTree()
        if (!localLibrary.hasAccess(tree)) return emptyList()
        return localLibrary.entries(tree).map { e ->
            val quality = e.quality
            Item(
                identity = e.id,
                type = "video",
                title = titleFromFile(e.name),
                year = 0,
                genres = listOfNotNull(e.path.substringBeforeLast('/', "").takeIf { it.isNotBlank() }),
                addedAt = e.modifiedAt,
                sources = listOf(
                    Source(
                        provider = "local",
                        label = listOfNotNull(
                            quality.takeIf { it.isNotBlank() },
                            sizeLabel(e.sizeBytes).takeIf { e.sizeBytes > 0 },
                        ).joinToString(" · ").ifBlank { "file" },
                        kind = "file",
                        quality = quality,
                        sizeBytes = e.sizeBytes,
                        mode = "direct",
                    ),
                ),
            )
        }
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000L -> Math.round(bytes / 1e6).toString() + " MB"
        else -> Math.round(bytes / 1e3).toString() + " KB"
    }

    private fun titleFromFile(name: String): String =
        name.substringBeforeLast('.').replace('.', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
            .ifBlank { name }

    fun metaJson(identity: String, type: String?): JSONObject? {
        localEntry(identity)?.let { e ->
            val item = localItems().firstOrNull { it.identity == e.id } ?: return null
            rememberItem(item)
            return itemJson(item)
        }
        // A live channel is not a catalogue title: it has no addon meta and no sources lookup. Answer
        // from the parsed playlist so the native Detail/Play path can open it.
        channelItems().firstOrNull { it.identity == identity }?.let { return itemJson(it) }
        val seed = seedFromLibrary(identity)
        val asked = type?.takeIf { it.isNotBlank() } ?: knownType(identity)
        val answers = ArrayList<Item>()
        // An identity says nothing about whether it is a series or a film. Asking only as "movie" is
        // how a fresh Bluey tile answered with a 1976 drama and poisoned its own remembered record:
        // ask each addon as a series first (a series meta carries the episode list every later screen
        // needs), fall back to the film answer.
        val asks = asked?.let { a -> listOf(a) + listOf("series", "movie").filter { it != a } }
            ?: listOf("series", "movie")
        for (am in addonManifests()) {
            if (!am.has("meta") || !addressableBy(identity, am.spec.id)) continue
            val remote = remoteIdFor(identity, am.spec.id)
            for (ask in asks) {
                val meta = addons.meta(am, communityType(ask) ?: "movie", remote, wait = true, ttlMs = ttlMs())
                    ?: continue
                answers.add(Item.from(itemFromMeta(meta, am, ask)))
                break
            }
        }
        if (answers.isEmpty() && seed == null) return null
        val merged = dedupe(answers).firstOrNull() ?: seed!!
        // A series tile is thin under one addon and complete under another that indexes the same show
        // under its own id. The hub learned this the hard way when a 4,862-episode soap answered
        // "one episode": take the longer list BEFORE the answer ships, union rather than replace.
        var eps = merged.episodes
        if (merged.type == "series" && eps.size < 5) {
            Log.i("ScenixLocalHub", "meta $identity thin (${eps.size} episodes), joining siblings")
            eps = adoptEpisodeSiblings(merged, eps)
            Log.i("ScenixLocalHub", "meta $identity joined -> ${eps.size} episodes")
        }
        // Sources are a separate resource in this protocol, so they are looked up here, once, on a
        // round trip the viewer is already waiting for. Which profile is watching is not part of a
        // meta call, so the ceiling applied here is the one this app was left on.
        val withSources = merged.copy(
            episodes = eps,
            sources = applyCaps(collectSources(identity, merged.type, wait = true), null),
            subtitleLanguages = if (manifestsHaveSubs()) settings().optStringList("subtitleLanguages") else emptyList(),
        )
        rememberItem(withSources)
        return itemJson(withSources)
    }

    fun playJson(body: JSONObject): JSONObject {
        val identity = s(body, "identity", "")
        if (identity.isBlank()) return JSONObject().put("error", "identity required")
        if (identity.startsWith("local:")) {
            val e = localEntry(identity)
                ?: return JSONObject().put("error", "this file is no longer in the chosen folder")
            return JSONObject()
                .put("url", "/app/v1/local/" + identity)
                .put("kind", "http")
                .put("mode", "direct")
                .put("headers", JSONObject())
                .put("sizeBytes", e.sizeBytes)
                .put("sourceName", e.name)
                .put("title", titleFromFile(e.name))
                .put("why", JSONArray(listOf("local file", "served from this TV by Range")))
                .put("episode", JSONObject.NULL)
                .put("nextEpisode", JSONObject.NULL)
        }
        val type = s(body, "type", "").ifBlank { knownType(identity) ?: "movie" }
        val season = body.optInt("season", 0)
        val episode = body.optInt("episode", 0)
        // A series is one tile with many addresses: the addon names an episode `<show>:<season>:<episode>`.
        val address = if (season > 0 && episode > 0) "$identity:$season:$episode" else identity
        val profileId = s(body, "profile", "").ifBlank { null }
        if (type == "channel" || liveVariants.containsKey(identity) || identity.startsWith("live:")) {
            channelsJson(waitForFirst = true)
            val variants = liveVariants[identity].orEmpty()
            if (variants.isNotEmpty()) {
                val skip = exclusions(body.optJSONArray("exclude"))
                val wanted = wantedSource(body)
                val working = liveHealth.workingUrl(identity)
                // The feed that answered is the first thing tried; inside equal health the lightest
                // variant is, so a weak line lands on 480p instead of the 1080p feed that stalls.
                val ordered = variants.sortedWith(compareBy(
                    { if (working != null && it.url == working) 0 else 1 },
                    { qbits(it.quality) },
                ))
                val asked = wanted?.let { w ->
                    val label = s(w, "label", "").trim()
                    val quality = s(w, "quality", "").trim()
                    ordered.withIndex().firstOrNull { (index, v) ->
                        (label.isNotBlank() &&
                            (liveLabel(v, index, ordered.size).equals(label, true) || v.url == label)) ||
                            (quality.isNotBlank() && v.quality.equals(quality, true))
                    }?.value
                }
                val pick = asked ?: ordered.withIndex().firstOrNull { (index, v) ->
                    v.url.lowercase() !in skip && liveLabel(v, index, ordered.size).lowercase() !in skip
                }?.value
                if (pick != null) {
                    // A probe pass must not compete with the channel the viewer is watching.
                    liveHealth.quietFor(15 * 60_000L)
                    val alternatives = ordered.filter { it.url != pick.url }.map { v ->
                        variantJson(identity, v, ordered)
                    }
                    return JSONObject()
                        .put("url", pick.url)
                        .put("kind", pick.kind)
                        .put("mode", "direct")
                        .put("live", true)
                        .put("headers", liveHeaders(pick.url))
                        .put("title", s(body, "title", "Live"))
                        .put("sourceName", liveLabel(pick, 0, ordered.size))
                        .put("health", liveHealth.healthOf(identity))
                        .put("alternatives", JSONArray(alternatives))
                        .put("episode", JSONObject.NULL)
                        .put("nextEpisode", JSONObject.NULL)
                }
                return JSONObject()
                    .put("error", "every feed for this channel was already tried")
                    .put("live", true)
                    .put("sources", JSONArray(ordered.map { variantJson(identity, it, ordered) }))
                    .put("episode", JSONObject.NULL)
                    .put("nextEpisode", JSONObject.NULL)
            }
        }
        val sources = applyCaps(collectSources(address, type, wait = true), profileId)
        val skip = exclusions(body.optJSONArray("exclude"))
        val wanted = wantedSource(body)
        // The title a copy's own release name must match. The shelf remembered this tile long before
        // the press, so the ladder never needs the client to carry a name.
        val wantTitle = s(body, "title", "").ifBlank { seedFromLibrary(identity)?.title ?: "" }
        val wantYear = seedFromLibrary(identity)?.year ?: 0
        // Copies this player already watched fail. Ignoring `exclude` is what made "Next copy" re-pick
        // the same dead link forever — on a night when several upstreams died at once that is exactly
        // how a standalone TV read the evening as "nothing works".
        val fresh = sources.filter { src ->
            skip.isEmpty() || listOf(
                src.label.lowercase(), src.provider.lowercase(), src.quality.lowercase(),
                (urlOf(src) ?: urlFor(address, src))?.lowercase().orEmpty(),
            ).none { it.isNotBlank() && it in skip }
        }
        val named = wanted?.let { w ->
            val label = s(w, "label", "").trim()
            val quality = s(w, "quality", "").trim()
            val provider = s(w, "provider", "").trim()
            sources.firstOrNull { src ->
                (label.isNotBlank() && (src.label == label || (urlOf(src) ?: urlFor(address, src)) == label)) ||
                    (quality.isNotBlank() && src.quality.equals(quality, true)) ||
                    (provider.isNotBlank() && src.provider.equals(provider, true))
            }
        }
        val torrentRoom = TorrentEngine.freeBytes(appContext) - 300L * 1024 * 1024
        // Seat the copies the way the hub seats them: the named pick first, then direct files, then a
        // torrent that fits this TV's free space, then the rest. A 2160p copy that cannot fit never
        // holds the front seat while a working 720p copy waits behind it.
        val queue = ArrayList<Source>()
        if (named != null) queue.add(named)
        val rest = if (named != null) fresh.filter { it !== named } else fresh
        // A torrent whose swarm failed to answer on the last press drops behind the fresh ones this
        // press: its magnet keeps resolving in the background, but the next seat may be a livelier
        // swarm of the same episode, and finding out is faster than waiting on one dead torrent.
        fun torrentSeats(pred: (Source) -> Boolean) =
            rest.filter(pred).sortedBy { if (warmingSeat(address, it)) 1 else 0 }
        queue.addAll(rest.filter { it.mode == "direct" })
        queue.addAll(torrentSeats { it.mode == "torrent" && it.sizeBytes > 0 && it.sizeBytes <= torrentRoom })
        queue.addAll(torrentSeats { it.mode == "torrent" && it.sizeBytes == 0L })
        queue.addAll(torrentSeats { it.mode == "torrent" && it.sizeBytes > torrentRoom })
        queue.addAll(rest.filter { it.mode != "direct" && it.mode != "torrent" })
        Log.i("ScenixLocalHub", "play $address: ${sources.size} sources, ${queue.size} seats to try")
        // One press walks the seats until something plays, instead of answering after the first copy
        // and making the viewer press "Next copy" through every dead one. Same shape the hub's
        // picker proved on the 7th November: a dead copy costs its reason and the next one its chance.
        val token = debridToken()
        val tried = ArrayList<String>()
        var coldWarm = false
        val deadline = System.currentTimeMillis() + PLAY_TOTAL_BUDGET_MS
        for ((index, cand) in queue.withIndex()) {
            if (index >= PLAY_MAX_TRIES) break
            val left = deadline - System.currentTimeMillis()
            if (left <= 0L) { tried.add("out of time after ${tried.size} tries"); break }
            val label = cand.label.ifBlank { cand.provider }
            if (type != "channel" && wantTitle.isNotBlank()) {
                val mismatch = wrongReleaseName(cand, wantTitle, wantYear, season, episode)
                if (mismatch != null) { tried.add(mismatch); continue }
            }
            if (cand.mode == "torrent") {
                val magnet = torrentFor(address, cand)
                if (token.isNotBlank() && magnet != null) {
                    val d = Debrid.resolveMagnet(magnet, season, episode, token)
                    if (d.url != null) {
                        return playAnswer(debrid = d, cand = cand, season = season, episode = episode, tried = tried)
                    }
                }
                if (magnet == null) { tried.add("$label: this copy has no magnet the local engine can use"); continue }
                if (cand.sizeBytes > 0 && cand.sizeBytes > torrentRoom) {
                    tried.add("$label: this copy is larger than the free space on this TV"); continue
                }
                // A magnet this engine has never seen gets a short look — long enough for a tracker
                // announce, short enough that the walk still reaches the next seat inside the
                // player's patience. A magnet already resolving in the background gets the engine's
                // real patience, because its metadata is usually seconds away now.
                val wait = if (TorrentEngine.pending(magnet)) minOf(left, 30_000L) else minOf(left, 10_000L)
                val prepared = TorrentEngine.prepare(appContext, magnet, season, episode, wait)
                if (prepared == null) {
                    tried.add("$label: the P2P engine on this TV could not prepare this copy")
                    // A running engine does not forget a magnet: it keeps resolving in the background.
                    // Note the seat as "warming" — the next press tries it last, while fresh seats
                    // get their first look — and answer `warming` when the walk runs out.
                    if (TorrentEngine.hasSession()) {
                        coldWarm = true
                        warmingNote(address, magnet)
                    }
                    continue
                }
                // Hand ExoPlayer a copy whose FIRST bytes already exist. A cold torrent answers the
                // gateway's opening Range with "wait 45 s", and the player's own HTTP timeout fires
                // before that does — the loop that read "Still loading this copy…" forever on a
                // standalone TV. Pull the opening block here (the engine caches it), and if even that
                // doesn't arrive, let the walk move on instead of minting a URL that will stall.
                val target = TorrentEngine.find(prepared.hash, prepared.fileId)
                val warmed = target != null && TorrentEngine.read(
                    target, 0L, TORRENT_FIRST_CHUNK, minOf(deadline - System.currentTimeMillis(), 25_000L)
                ) != null
                if (!warmed) {
                    tried.add("$label: this copy has no peers answering yet")
                    coldWarm = true
                    warmingNote(address, magnet)
                    continue
                }
                return playAnswer(prepared = prepared, cand = cand, season = season, episode = episode, tried = tried)
            }
            // A hoster page the addon handed over is not a media file, but a debrid account can turn
            // it into one. Direct files are left alone: they already play and must not spend a provider call.
            if (token.isNotBlank() && cand.mode != "direct") {
                val hoster = urlFor(address, cand)
                if (hoster != null && hoster.startsWith("http")) {
                    val d = Debrid.unrestrict(hoster, token)
                    if (d.url != null) {
                        return playAnswer(debrid = d, cand = cand, season = season, episode = episode, tried = tried)
                    }
                }
            }
            val url = urlFor(address, cand)
            if (url == null) { tried.add("$label: stream link expired, open the title again"); continue }
            return playAnswer(url = url, cand = cand, season = season, episode = episode, tried = tried)
        }
        if (coldWarm) return warmingAnswer(address, tried)
        return JSONObject()
            .put("error", if (sources.isNotEmpty() && fresh.isEmpty()) "every copy here was already tried"
                else "nothing here is a file this TV can open on its own")
            .put("sources", JSONArray(sources.map { sourceJson(it) }))
            .put("tried", JSONArray(tried))
            .also { Log.i("ScenixLocalHub", "play $address failed: ${JSONArray(tried)}") }
    }

    /**
     * The engine HAS these copies — magnets accepted, metadata resolving — but no peer has sent the
     * opening bytes yet. That is preparation, not refusal: the client retries /play on its own
     * clock, the magnets keep resolving in the background, and a later try mints almost at once.
     */
    private fun warmingAnswer(address: String, tried: List<String>): JSONObject =
        JSONObject()
            .put("warming", true)
            .put("retryAfterMs", 6000L)
            .put("message", "this copy is still finding its first peers")
            .put("error", "the peer-to-peer copies on this TV had not started sending bytes yet")
            .put("tried", JSONArray(tried))
            .also { Log.i("ScenixLocalHub", "play $address warming: ${JSONArray(tried)}") }

    /** Seats whose swarm had not answered metadata on a recent press; they wait behind fresh ones. */
    private val warmingSeats = HashMap<String, HashMap<String, Long>>()

    private fun warmingSeat(address: String, src: Source): Boolean = synchronized(lock) {
        val magnet = torrentFor(address, src) ?: return false
        val hash = TorrentEngine.hashOf(magnet) ?: return false
        val at = warmingSeats[address]?.get(hash) ?: return false
        System.currentTimeMillis() - at < WARMING_DEMOTE_MS
    }

    private fun warmingNote(address: String, magnet: String) {
        val hash = TorrentEngine.hashOf(magnet) ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val seats = warmingSeats.getOrPut(address) { HashMap() }
            seats[hash] = now
            for (k in seats.keys.toList()) if (now - seats.getValue(k) > 10 * 60_000L) seats.remove(k)
        }
    }

    /**
     * One seat that answered, in the shape the player expects. `tried` rides along so the Quality
     * menu and the next press know what was already ruled out on this walk.
     */
    private fun playAnswer(
        url: String? = null,
        prepared: TorrentEngine.Prepared? = null,
        debrid: Debrid.Result? = null,
        cand: Source,
        season: Int,
        episode: Int,
        tried: List<String>,
    ): JSONObject {
        val episodeJson = if (season > 0 && episode > 0) JSONObject()
            .put("season", season).put("episode", episode) else JSONObject.NULL
        val nextJson = if (season > 0 && episode > 0) JSONObject()
            .put("season", season).put("episode", episode + 1) else JSONObject.NULL
        val out = JSONObject()
            .put("headers", JSONObject())
            .put("episode", episodeJson)
            .put("nextEpisode", nextJson)
        when {
            prepared != null -> out
                .put("url", "/app/v1/torrent/${prepared.hash}/${prepared.fileId}")
                .put("kind", "http")
                .put("mode", "gateway") // libtorrent streams it; the player sees ordinary HTTP
                .put("proxied", true)
                .put("sizeBytes", prepared.size)
                .put("sourceName", prepared.name)
                .put("why", JSONArray(cand.why))
            debrid != null -> out
                .put("url", debrid.url)
                .put("kind", debrid.kind)
                .put("mode", "debrid")
                .put("sizeBytes", if (debrid.fileSize > 0) debrid.fileSize else cand.sizeBytes)
                .put("sourceName", debrid.fileName ?: cand.label)
                .put("debrid", JSONObject().put("provider", "real-debrid").put("providerLabel", "Real-Debrid"))
                .put("why", JSONArray(listOf("${cand.provider} \u00B7 served by Real-Debrid")))
            else -> out
                .put("url", url)
                .put("kind", cand.kind)
                .put("mode", "direct")     // this device reads the file itself: no gateway, no re-mux
                .put("why", JSONArray(cand.why))
        }
        if (tried.isNotEmpty()) out.put("tried", JSONArray(tried))
        return out
    }

    /** The `source` a client names: an object (native player) or a plain label string (older screens). */
    private fun wantedSource(body: JSONObject): JSONObject? {
        body.optJSONObject("source")?.let { return it }
        val label = s(body, "source", "").trim().ifBlank { s(body, "provider", "").trim() }
        return if (label.isBlank()) null else JSONObject().put("label", label)
    }

    /** Sources this client already tried, lowercased for a forgiving comparison. */
    private fun exclusions(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        val out = HashSet<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim().lowercase()
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    private fun variantJson(identity: String, v: LiveVariant, all: List<LiveVariant>): JSONObject {
        val index = all.indexOf(v)
        val label = liveLabel(v, index, all.size)
        return JSONObject()
            .put("id", "live:" + Integer.toHexString((identity + "|" + v.url).hashCode()))
            .put("provider", "live")
            .put("label", label)
            .put("kind", v.kind)
            .put("quality", v.quality)
            .put("mode", "direct")
            .put("score", if (liveHealth.workingUrl(identity) == v.url) 10.0 else 0.0)
    }

    /**
     * What a live host often demands before it answers: a browser-shaped user agent and a referer from
     * its own origin. The hub sends the same pair; without them a great many feeds return 403 while
     * the playlist still looks healthy.
     */
    private fun liveHeaders(url: String): JSONObject = JSONObject()
        .put("User-Agent", LiveHealth.UA)
        .put("Referer", LiveHealth.originOf(url) + "/")

    fun prefsJson(profileId: String?): JSONObject {
        val who = profileId ?: "default"
        return JSONObject().put("profile", who).put("prefs", wirePrefs(who))
    }

    fun setPrefsJson(values: JSONObject, profileId: String?): JSONObject = synchronized(lock) {
        val who = profileId ?: "default"
        writeProfilePrefs(who, values)
        JSONObject().put("profile", who).put("prefs", wirePrefs(who))
    }

    /**
     * The shape a hub's `/app/v1/prefs` answers with, not the raw sparse file: quality steps a
     * settings screen can draw, the size/bitrate ceilings those steps imply, and the adult switch.
     * A page that gets only what the viewer typed cannot draw a single control, which is exactly what
     * happened before this existed.
     */
    private fun wirePrefs(profileId: String): JSONObject {
        val profile = profileList().firstOrNull { s(it, "id", "") == profileId }
        val out = effectivePrefs(profile ?: JSONObject())
        val kids = profile?.let { s(it, "kind", "").equals("kids", true) } == true
        val asked = s(out, "qualityCap", "").trim()
        val cap = when (asked.lowercase()) {
            "auto", "any", "highest", "off" -> "auto"
            "4k", "2160p", "uhd" -> "4k"
            "1080p", "1080", "fullhd", "fhd" -> "1080p"
            "720p", "720", "hd" -> "720p"
            "480p", "480", "sd" -> "480p"
            else -> if (kids) "720p" else "1080p"
        }
        val level = when (cap) {
            "480p" -> 2 to 2
            "720p" -> 3 to 4
            "1080p" -> 4 to 8
            "4k" -> 5 to 25
            else -> 99 to 25
        }
        val size = when {
            out.has("maxSizeGB") && out.optDouble("maxSizeGB", 0.0) > 0 -> out.optDouble("maxSizeGB")
            else -> level.second.toDouble()
        }
        val bitrate = when {
            out.has("maxBitrateMbps") && out.optDouble("maxBitrateMbps", 0.0) > 0 -> out.optDouble("maxBitrateMbps")
            level.first == 2 -> 4.0
            level.first == 3 -> 8.0
            else -> 0.0
        }
        out.put("qualityCap", cap)
        out.put("qualityCapAsked", if (asked.isBlank()) JSONObject.NULL else asked)
        out.put("quality", if (s(out, "quality", "") == "lowest") "lowest" else "highest")
        out.put("preferAdaptive", out.optBoolean("preferAdaptive", true))
        out.put("maxSizeGB", size)
        out.put("maxBitrateMbps", if (bitrate > 0) bitrate else JSONObject.NULL)
        out.put("maxSizeBytes", Math.round(size * 1e9))
        out.put("maxFileSizeBytes", Math.round(size * 1e9))
        out.put("maxBitrate", Math.round(bitrate * 1e6))
        out.put("xxxEnabled", out.optBoolean("xxxEnabled", false))
        out.put("qualityLevels", JSONArray(listOf("auto", "4k", "1080p", "720p", "480p")))
        return out
    }

    /**
     * The addons this TV has been told about, reported the way a hub reports its own: what each one is,
     * whether it is switched on, which resources its manifest declares. `error` says what is not working in
     * the owner's own words: the manifest is not fetched yet, or it did not answer. A TV keeps no per-addon
     * tally of what it streamed, so it does not invent one.
     */
    fun addonsJson(): JSONArray {
        val out = JSONArray()
        for (spec in addonSpecs(includeDisabled = true)) out.put(addonStats(spec))
        return out
    }

    private fun addonStats(spec: AddonSpec): JSONObject {
        val am = addons.manifest(spec, wait = false, ttlMs = ttlMs())
        val kind = when {
            am == null -> "video"
            am.catalogs.any { communityType(s(it, "type", "")) == "tv" } -> "channel"
            am.catalogs.any { communityType(s(it, "type", "")) != null } -> "title"
            else -> "video"
        }
        return JSONObject()
            .put("id", spec.id)
            .put("name", spec.name)
            .put("url", spec.manifestUrl)
            .put("native", false)   // nothing on a TV was shipped by a build: the owner typed every URL
            .put("enabled", spec.enabled)
            .put("kind", kind)
            .put("resources", JSONArray(resourceNames(am)))
            .put("catalogs", am?.catalogs?.size ?: 0)
            .put("error", if (am == null) "manifest not fetched yet" else JSONObject.NULL)
    }

    private fun resourceNames(am: AddonManifest?): List<String> = am?.resources?.keys?.sorted() ?: emptyList()

    /** One addon's report, found by the id or the URL a caller used. */
    private fun addonStatsFor(which: String): JSONObject? =
        addonSpecs(includeDisabled = true).firstOrNull {
            it.id == which || it.manifestUrl.trim() == which.trim() || slug(it.manifestUrl) == which
        }?.let { addonStats(it) }

    // ---- the owner's addons -------------------------------------------------------------------
    // Same verbs as the hub's own addon routes, answered here from the settings file: an installed
    // addon is a URL the owner typed, and nothing about it is decided by a build.

    /**
     * Take an addon by URL, checked the way a hub checks one. An address that is not http(s) is refused; an
     * address already installed is not installed twice; the manifest is read before the owner is told it
     * worked, because a TV that accepted a dead URL would show an empty shelf and never say why. The address
     * is stored the way a hub stores it, with the manifest file named, so the same URL means the same addon on
     * either backend.
     */
    fun addAddon(url: String, name: String = ""): JSONObject = synchronized(lock) {
        val address = manifestAddress(url)
            ?: return JSONObject().put("error", "url must be http(s)")
        val arr = settings().optJSONArray("addons") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (sameAddress(s(o, "manifest", s(o, "url", "")), address)) {
                return JSONObject().put("error", "already installed").put("id", slug(address))
            }
        }
        val spec = AddonSpec(slug(address), name.trim(), address, true)
        if (addons.manifest(spec, wait = true, ttlMs = 0) == null) {
            return JSONObject().put("error", "manifest not reachable").put("url", address)
        }
        val entry = JSONObject().put("url", address)
        if (name.isNotBlank()) entry.put("name", name.trim())
        arr.put(entry)
        setSettings(JSONObject().put("addons", arr))
        JSONObject().put("addon", addonStats(spec))
    }

    /** An addon address the way a hub reads one: http(s) only, and a directory gets its manifest file. */
    private fun manifestAddress(url: String): String? {
        val t = url.trim()
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return null
        val trimmed = t.trimEnd('/')
        return if (trimmed.endsWith("/manifest.json", true)) trimmed else "$trimmed/manifest.json"
    }

    private fun sameAddress(a: String, b: String): Boolean =
        a.trim().trimEnd('/').lowercase() == b.trim().trimEnd('/').lowercase()

    /** Switch one addon on or off, matched by id or by the URL it was given. */
    fun setAddonEnabled(which: String, enabled: Boolean): JSONObject? =
        if (editAddon(which) { it.put("enabled", enabled) }) addonStatsFor(which) else null

    /** Forget an addon entirely, and say which went. Its cached answers stay until the cache is cleared. */
    fun removeAddon(which: String): String? {
        val kept = JSONArray()
        val arr = settings().optJSONArray("addons") ?: JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (matchesAddon(o, which)) removed = true else kept.put(o)
        }
        if (!removed) return null
        setSettings(JSONObject().put("addons", kept))
        return which
    }

    private fun editAddon(which: String, patch: (JSONObject) -> Unit): Boolean = synchronized(lock) {
        val arr = settings().optJSONArray("addons") ?: return false
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (matchesAddon(o, which)) {
                patch(o)
                hit = true
            }
        }
        if (!hit) return false
        setSettings(JSONObject().put("addons", arr))
        return true
    }

    private fun matchesAddon(o: JSONObject, which: String): Boolean {
        val url = s(o, "manifest", s(o, "url", "")).trim()
        return s(o, "id", "") == which || url == which.trim() || slug(url) == which
    }

    /**
     * The languages a profile listens in — what a hub falls back to when a subtitle request names none, and
     * so what this backend falls back to as well.
     */
    fun subtitleLanguages(profileId: String?): List<String> {
        val who = profileId ?: "default"
        val p = profileList().firstOrNull { s(it, "id", "") == who } ?: return emptyList()
        return effectivePrefs(p).optStringList("audioLanguages")
    }

    /**
     * `GET /app/v1/subs/<identity>?lang=<the profile's subtitle languages>` — subtitles the installed
     * addons offer, in the profile's languages, asked one language at a time because that is how this
     * protocol addresses them. An addon that has no subtitles resource is not asked at all.
     */
    fun subsJson(identity: String, langs: List<String>, type: String?): JSONArray {
        val out = JSONArray()
        val t = communityType(type ?: knownType(identity) ?: "movie") ?: return out
        val seen = LinkedHashSet<String>()
        for (am in addonManifests()) {
            if (!am.has("subtitles") || !addressableBy(identity, am.spec.id)) continue
            for (lang in langs) {
                val arr = addons.subtitles(am, t, remoteIdFor(identity, am.spec.id), lang, wait = true, ttlMs = ttlMs())
                    ?: continue
                for (i in 0 until arr.length()) {
                    val sub = arr.optJSONObject(i) ?: continue
                    val url = s(sub, "url", "")
                    if (url.isBlank() || !seen.add(url)) continue
                    out.put(
                        JSONObject()
                            .put("id", s(sub, "id", "$identity-$lang"))
                            .put("addon", am.spec.name)
                            .put("lang", s(sub, "lang", lang))
                            .put("url", url)
                            .put("label", s(sub, "name", "")),
                    )
                }
            }
        }
        return out
    }

    fun progressJson(body: JSONObject): JSONObject = synchronized(lock) {
        val who = s(body, "profile", "default").ifBlank { "default" }
        val identity = s(body, "identity", "")
        if (identity.isBlank()) return JSONObject().put("error", "identity required")
        val root = readJson(progressFile)
        val forWho = root.optJSONObject(who) ?: JSONObject()
        val entry = body.optJSONObject("episode")
        forWhoPut(forWho, identity, body.optLong("positionMs", 0L), body.optLong("watchedMs", 0L), entry)
        root.put(who, forWho)
        writeJson(progressFile, root)
        rememberItem(body.optJSONObject("item"))
        JSONObject().put("ok", true).put("identity", identity)
    }

    /**
     * "Remove from Continue watching": keep the position, hide the tile. [forWhoPut] rebuilds the
     * entry on the next progress post, so pressing the title again re-arms the row — the same
     * contract the remote hub runs (core/store.js).
     */
    fun dismissJson(body: JSONObject): JSONObject = synchronized(lock) {
        val who = s(body, "profile", "default").ifBlank { "default" }
        val identity = s(body, "identity", "")
        if (identity.isBlank()) return JSONObject().put("error", "identity required")
        val root = readJson(progressFile)
        val forWho = root.optJSONObject(who) ?: return JSONObject().put("ok", true).put("dismissed", false)
        val entry = forWho.optJSONObject(identity) ?: return JSONObject().put("ok", true).put("dismissed", false)
        entry.put("dismissedAt", System.currentTimeMillis())
        root.put(who, forWho)
        writeJson(progressFile, root)
        JSONObject().put("ok", true).put("dismissed", true).put("identity", identity)
    }

    /** "Start over": rewind the record to never-started. Run before seeking, exactly like the hub. */
    fun resetProgressJson(body: JSONObject): JSONObject = synchronized(lock) {
        val who = s(body, "profile", "default").ifBlank { "default" }
        val identity = s(body, "identity", "")
        if (identity.isBlank()) return JSONObject().put("error", "identity required")
        val root = readJson(progressFile)
        val forWho = root.optJSONObject(who) ?: JSONObject()
        val cur = forWho.optJSONObject(identity)
        val fresh = JSONObject()
            .put("positionMs", 0L).put("watchedMs", 0L).put("updatedAt", System.currentTimeMillis())
            .put("episode", cur?.opt("episode") ?: JSONObject.NULL)
        forWho.put(identity, fresh)
        root.put(who, forWho)
        writeJson(progressFile, root)
        JSONObject().put("ok", true).put("identity", identity).put("progress", fresh)
    }

    // ---- My List, on this box (standalone mode speaks the same /app/v1/list contract as the hub) --

    fun listJson(profileId: String?): JSONObject = synchronized(lock) {
        val who = profileId?.ifBlank { "default" } ?: "default"
        JSONObject().put("profile", who).put("ids", readJson(listFile).optJSONArray(who) ?: JSONArray())
    }

    fun listPostJson(body: JSONObject): JSONObject = synchronized(lock) {
        val who = s(body, "profile", "default").ifBlank { "default" }
        val identity = s(body, "identity", "")
        if (identity.isBlank()) return JSONObject().put("error", "identity required")
        val root = readJson(listFile)
        val ids = root.optJSONArray(who) ?: JSONArray()
        val was = indexOfIdentity(ids, identity) >= 0
        val on = if (body.has("add")) body.optBoolean("add") else !was
        if (on) {
            if (!was) ids.put(0, identity)
        } else if (was) {
            val next = JSONArray()
            for (i in 0 until ids.length()) if (i != indexOfIdentity(ids, identity)) next.put(ids.optString(i))
            root.put(who, next)
            writeJson(listFile, root)
            return JSONObject().put("ok", true).put("identity", identity).put("inList", false).put("ids", next)
        }
        root.put(who, ids)
        writeJson(listFile, root)
        JSONObject().put("ok", true).put("identity", identity).put("inList", on).put("ids", ids)
    }

    private fun indexOfIdentity(ids: JSONArray, identity: String): Int {
        for (i in 0 until ids.length()) if (ids.optString(i) == identity) return i
        return -1
    }

    /** Drop every cached addon answer — what "Clear cached copies" in Settings does. */
    fun clearAddonCache() = addons.clearCache()

    /**
     * Ask every installed addon for its manifest right now, and report how many answered. Every other
     * path is cache-first, so this is what makes a freshly added addon fill the home screen instead of
     * waiting for the next visit to answer.
     */
    /**
     * Re-read every installed manifest and warm its catalogs. The home path is cache-only, so
     * installing an addon and immediately opening Home must not show an empty screen until the next
     * background pass: the first catalog fetch is kicked off here, and the next Home reload has it.
     */
    fun refreshAddons(): Int {
        var ok = 0
        for (spec in addonSpecs()) {
            val am = addons.manifest(spec, wait = true, ttlMs = ttlMs()) ?: continue
            ok++
            for (c in am.catalogs.take(MAX_CATALOGS_PER_ADDON)) {
                val type = communityType(s(c, "type", "movie")) ?: continue
                addons.catalog(am, type, s(c, "id", ""), "default", wait = false, ttlMs = ttlMs())
            }
        }
        return ok
    }

    // ---- rows ---------------------------------------------------------------------------------

    private data class LocalRow(
        val id: String, val title: String, val card: String,
        val order: Int, val scope: String, val items: List<Item>,
    )

    /** Which rows this backend has, before any profile's rules run. The manifest mirrors these ids. */
    private fun declaredRows(manifests: List<AddonManifest>): List<LocalRow> {
        val out = ArrayList<LocalRow>()
        out.add(LocalRow("continue", "Continue watching", "continue", 5, "all", emptyList()))
        out.add(LocalRow("justadded", "Recently added", "poster", 10, "all", emptyList()))
        if (settings().optJSONObject("live")?.optString("playlist")?.isNotBlank() == true) {
            out.add(LocalRow("live", "Live TV", "channel", 15, "all", emptyList()))
        }
        if (localLibrary.hasAccess(localTree())) {
            out.add(LocalRow("local", "Local files", "poster", 16, "all", emptyList()))
        }
        // The two section tabs are rows like any other, even when empty: the native Movies/Series grid
        // asks /app/v1/row/movies or /series, and a fresh install should answer "nothing yet" rather
        // than 404.
        out.add(LocalRow("movies", "Movies", "poster", 60, "all", emptyList()))
        out.add(LocalRow("series", "Series", "poster", 61, "all", emptyList()))
        var order = 20
        for (am in manifests) {
            for (c in am.catalogs.take(MAX_CATALOGS_PER_ADDON)) {
                val type = communityType(s(c, "type", "movie")) ?: continue
                out.add(
                    LocalRow(
                        id = rowIdFor(am, type, s(c, "id", "default")),
                        title = s(c, "name", am.spec.name),
                        card = "poster",
                        order = order++,
                        scope = "all",
                        items = emptyList(),
                    ),
                )
            }
        }
        return out
    }

    private fun rowIdFor(am: AddonManifest, type: String, catalogId: String): String =
        "addon:${am.spec.id}/$type/$catalogId"

    /**
     * The home rows. Cache-first by construction: [AddonClient] answers out of the on-device cache and
     * refreshes behind the caller, so this never waits on the network after the first cold fetch, and
     * an addon that is down costs one missing shelf rather than a hung screen.
     */
    private fun buildRows(profileId: String?): List<LocalRow> {
        val out = ArrayList<LocalRow>()

        // Continue watching: what this profile stopped, most recently stopped first. A tile the
        // viewer dismissed (dismissedAt) stays out until the next progress post rebuilds the entry.
        val who = profileId ?: "default"
        val progress = readJson(progressFile).optJSONObject(who)
        if (progress != null && progress.length() > 0) {
            val items = ArrayList<Item>()
            val names = ArrayList<String>()
            for (k in progress.keys()) names.add(k)
            names.sortByDescending { progress.optJSONObject(it)?.optLong("updatedAt") ?: 0L }
            for (identity in names.take(ROW_MAX)) {
                if ((progress.optJSONObject(identity)?.optLong("dismissedAt", 0L) ?: 0L) > 0L) continue
                val seed = seedFromLibrary(identity) ?: continue
                Progress.from(progress.optJSONObject(identity))?.let { items.add(seed.copy(progress = it)) }
            }
            if (items.isNotEmpty()) out.add(LocalRow("continue", "Continue watching", "continue", 5, "all", items))
        }

        // My List: the shelf this person stocked themselves, newest first, same order the hub's
        // rows/mylist.js sends.
        val listIds = readJson(listFile).optJSONArray(who)
        if (listIds != null && listIds.length() > 0) {
            val items = ArrayList<Item>()
            for (i in 0 until minOf(listIds.length(), ROW_MAX)) {
                val seed = seedFromLibrary(listIds.optString(i)) ?: continue
                items.add(seed)
            }
            if (items.isNotEmpty()) out.add(LocalRow("mylist", "My List", "poster", 12, "all", items))
        }

        // Recently added: this app's own memory of what it has put on a shelf, newest first.
        val library = libraryItems().filter { it.addedAt > 0 }.sortedByDescending { it.addedAt }.take(ROW_MAX)
        if (library.isNotEmpty()) out.add(LocalRow("justadded", "Recently added", "poster", 10, "all", library))

        // Live TV: the household's hidden channels and groups were already removed by channelItems();
        // now the profile's own lineup and order, then health and the stable zap number. This is the
        // same order the hub's rows/live.js applies, so the remote walks the same wall on either host.
        val profileLive = readProfilePrefs(who).optJSONObject("live")
        var liveChannels = channelItems().filter {
            it.identity !in ids(profileLive?.optJSONArray("hidden")).toSet()
        }
        val only = ids(profileLive?.optJSONArray("only"))
        if (only.isNotEmpty()) {
            val allowed = only.toSet()
            liveChannels = liveChannels.filter { it.identity in allowed }
        }
        val localOrder = ids(profileLive?.optJSONArray("order"))
        val wanted = when {
            localOrder.isNotEmpty() -> localOrder
            only.isNotEmpty() -> only
            else -> ids(settings().optJSONObject("live")?.optJSONArray("order"))
        }
        val liveRank = wanted.withIndex().associate { it.value to it.index }
        liveChannels = liveChannels.sortedWith(compareBy(
            { liveRank[it.identity] ?: Int.MAX_VALUE },
            { liveHealth.rank(it.identity) },
            { it.live?.number ?: Int.MAX_VALUE },
            { it.title.lowercase() },
        ))
        if (liveChannels.isNotEmpty()) out.add(LocalRow("live", "Live TV", "channel", 15, "all", liveChannels))
        val local = localItems()
        if (local.isNotEmpty()) out.add(LocalRow("local", "Local files", "poster", 16, "all", local))

        val allLibrary = libraryItems()
        val movies = allLibrary.filter { it.type == "movie" }
            .sortedByDescending { it.releasedAt }
        if (movies.isNotEmpty()) out.add(LocalRow("movies", "Movies", "poster", 60, "all", movies.take(300)))
        val series = allLibrary.filter { it.type == "series" }
            .sortedByDescending { it.releasedAt }
        if (series.isNotEmpty()) out.add(LocalRow("series", "Series", "poster", 61, "all", series.take(300)))

        for (am in addonManifests()) {
            if (!am.has("catalog")) continue
            for (c in am.catalogs.take(MAX_CATALOGS_PER_ADDON)) {
                val type = communityType(s(c, "type", "movie")) ?: continue
                val metas = addons.catalog(am, type, s(c, "id", ""), "default", wait = false, ttlMs = ttlMs())
                    ?: continue
                val items = itemsFromMetas(metas, am, type)
                if (items.isEmpty()) continue
                // Cinemeta's movie and series catalogs carry the same names ("Popular", "New"), and a
                // fresh install with the default sources on showed two "Popular" shelves side by side.
                // The second one says what it holds.
                val plain = s(c, "name", am.spec.name)
                val label = if (out.any { it.title == plain })
                    "$plain ${if (type == "series") "series" else if (type == "movie") "movies" else type}"
                else plain
                out.add(
                    LocalRow(
                        id = rowIdFor(am, type, s(c, "id", "default")),
                        title = label,
                        card = "poster",
                        order = 20 + out.size,
                        scope = "all",
                        items = items,
                    ),
                )
            }
        }

        // One tile per title across the whole page, the rule the hub applies for the same reason: a
        // second addon that knows the same film adds sources to the surviving tile, it does not add a
        // second copy of the same poster to the screen.
        //
        // A kids profile also sees only what its maturity label allows. The page this backend serves runs
        // no rules of its own, so the ceiling is applied here, where the shelves are built.
        val ceiling = kidsCeiling(profileId)
        val seen = LinkedHashSet<String>()
        val merged = ArrayList<LocalRow>()
        for (r in out) {
            val items = ArrayList<Item>()
            for (it in r.items) {
                if (r.id.startsWith("addon:") && !seen.add(it.identity)) continue
                if (ceiling >= 0 && impliedAge(it.maturity) > ceiling) continue
                items.add(it)
            }
            if (items.isNotEmpty()) merged.add(r.copy(items = items))
        }
        merged.forEach { rememberLibrary(it.items) }
        return merged.sortedBy { it.order }
    }

    // ---- addon answers -> the shared item model -------------------------------------------------

    private fun itemsFromMetas(metas: JSONArray, am: AddonManifest, type: String): List<Item> {
        val out = ArrayList<Item>()
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            out.add(Item.from(itemFromMeta(m, am, type)))
        }
        return out
    }

    /**
     * One addon `meta` object -> the §1 item shape. The identity gains its namespace here, which is
     * what lets two addons that name the same film differently still land on one tile: `imdb:tt…` is
     * the shared key, an addon's own id is namespaced by that addon.
     */
    private fun itemFromMeta(m: JSONObject, am: AddonManifest, type: String): JSONObject {
        val remote = s(m, "id", "")
        val t = communityType(s(m, "type", type)) ?: type
        val released = parseIso(s(m, "released", ""))
        val declaredYear = m.optInt("year", 0)
        val year = if (declaredYear in 1888..2100) declaredYear else released?.let { yearOf(it) } ?: 0
        val rating = s(m, "imdbRating", "")
        val match = rating.toDoubleOrNull()?.let { (it * 10).roundToInt() } ?: 0
        val runtime = runtimeMs(s(m, "runtime", ""))
        val episodes = episodesFromMeta(m)
        return JSONObject()
            .put("identity", identityFor(remote, am.spec.id))
            .put("type", t)
            .put("title", s(m, "name", s(m, "title", "")))
            .put("year", year)
            .put("poster", s(m, "poster", ""))
            .put("backdrop", s(m, "background", s(m, "logo", "")))
            .put("logo", s(m, "logo", ""))
            .put("match", match)
            .put("genres", JSONArray(jsonStrings(m.optJSONArray("genres"))))
            .put("languages", JSONArray(jsonStrings(m.optJSONArray("language"))))
            .put("overview", s(m, "description", ""))
            .put("releasedAt", released ?: 0L)
            .put("durationMs", runtime ?: 0L)
            .put("addedAt", parseIso(s(m, "released", "")) ?: 0L)
            .put("episodes", JSONArray(episodes))
    }

    /**
     * Episodes, when the addon lists them. Some addons publish a season/episode map, some publish
     * nothing; a series tile stays one tile either way, and the Play button names the earliest episode
     * it was told about.
     */
    private fun episodesFromMeta(m: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        // Some addons publish a season/episode map, some publish the flat episode list under
        // `episodes` and no `seasons` at all (the community soaps do exactly this). Read both;
        // answering zero for a show that HAS a published list is what read as "no episodes".
        val flat = m.optJSONArray("episodes")
        if (flat != null) {
            for (j in 0 until flat.length()) {
                val e = flat.optJSONObject(j) ?: continue
                val number = numberFromEpisode(s(e, "id", ""))
                val n = number ?: e.optInt("number", e.optInt("episode", 0))
                val season = e.optInt("season", seasonFromEpisode(s(e, "id", "")) ?: 0)
                if (season > 0 && n > 0) {
                    out.add(JSONObject().put("season", season).put("episode", n).put("title", s(e, "title", "")))
                }
            }
        }
        val seasons = m.optJSONArray("seasons") ?: return out
        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val number = season.optInt("number", season.optInt("index", i + 1))
            val eps = season.optJSONArray("episodes") ?: continue
            for (j in 0 until eps.length()) {
                val e = eps.optJSONObject(j) ?: continue
                val n = e.optInt("number", j + 1)
                if (number > 0 && n > 0) {
                    out.add(JSONObject().put("season", number).put("episode", n).put("title", s(e, "title", "")))
                }
            }
        }
        return out
    }

    /** A community episode id is often `<show>:<season>:<episode>`; read the numbers off it. */
    private fun seasonFromEpisode(id: String): Int? =
        Regex("""^(?:.*:)?(\d+):\d+$""").find(id)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    private fun numberFromEpisode(id: String): Int? =
        Regex("""^.*:(\d+)$""").find(id)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    private fun rowJson(r: LocalRow): JSONObject = JSONObject()
        .put("id", r.id).put("title", r.title).put("card", r.card).put("scope", r.scope).put("order", r.order)
        .put("items", JSONArray(r.items.map { itemJson(it) }))

    private fun itemJson(i: Item): JSONObject = JSONObject()
        .put("identity", i.identity).put("type", i.type).put("title", i.title).put("year", i.year)
        .put("poster", i.poster ?: "").put("backdrop", i.backdrop ?: "").put("logo", i.logo ?: "")
        .put("match", i.match).put("maturity", i.maturity).put("durationMs", i.durationMs)
        .put("genres", JSONArray(i.genres)).put("languages", JSONArray(i.languages))
        .put("addedAt", i.addedAt).put("releasedAt", i.releasedAt).put("inCinemas", i.inCinemas)
        .put("kids", i.kids)
        .put("subtitleLanguages", JSONArray(i.subtitleLanguages))
        .put("progress", i.progress?.let { p ->
            JSONObject()
                .put("positionMs", p.positionMs).put("watchedMs", p.watchedMs)
                .put("episode", p.episode?.let { JSONObject().put("season", it.season).put("episode", it.episode).put("title", it.title) } ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        .put("episodes", JSONArray(i.episodes.map { JSONObject().put("season", it.season).put("episode", it.episode).put("title", it.title) }))
        // A channel's health and zap number live on `live`, not on the item: the player reads
        // `item.live.number` for its station badge, and without this block every standalone channel
        // showed a zero in place of its number.
        .put("live", i.live?.let { l ->
            JSONObject()
                .put("now", l.now.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("quality", l.quality.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put("number", if (l.number > 0) l.number else JSONObject.NULL)
                .put("health", l.health.takeIf { it.isNotBlank() } ?: "unknown")
                .put("checkedAt", if (l.checkedAt > 0) l.checkedAt else JSONObject.NULL)
                .put("latencyMs", if (l.latencyMs > 0) l.latencyMs else JSONObject.NULL)
                .put("error", l.error.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        } ?: JSONObject.NULL)
        .put("sources", JSONArray(i.sources.map { sourceJson(it) }))

    private fun sourceJson(src: Source): JSONObject = JSONObject()
        .put("provider", src.provider).put("label", src.label).put("kind", src.kind)
        .put("quality", src.quality).put("sizeBytes", src.sizeBytes).put("risk", src.risk)
        .put("mode", src.mode).put("score", src.score).put("why", JSONArray(src.why))

    // ---- streams ------------------------------------------------------------------------------

    /**
     * Every source every installed addon offers for one address, ranked. Sources carry no URL in the
     * shared model — the player is handed the URL by `POST /app/v1/play`, which is what keeps a link
     * that expires out of every card on screen — so the URLs found here are remembered against the
     * address, keyed by the same fields the source list shows.
     */
    private fun collectSources(address: String, type: String, wait: Boolean): List<Source> {
        val t = communityType(type) ?: return emptyList()
        val found = ArrayList<Source>()
        val urls = HashMap<String, String>()
        val torrents = HashMap<String, String>()
        for (am in addonManifests()) {
            if (!am.has("stream") || !addressableBy(address, am.spec.id)) continue
            val streams = addons.streams(am, t, remoteIdFor(address, am.spec.id), wait, ttlMs = ttlMs()) ?: continue
            for (i in 0 until streams.length()) {
                val e = streams.optJSONObject(i) ?: continue
                val src = parseStream(e, am.spec.name) ?: continue
                found.add(src)
                urlOf(src)?.let { urls[srcKey(src)] = it }
                if (src.kind == "torrent") torrentUri(e)?.let { torrents[srcKey(src)] = it }
            }
        }
        if (found.isNotEmpty()) {
            sourceUrls[address] = urls
            sourceTorrents[address] = torrents
        }
        return rankSources(found)
    }

    private fun urlOf(src: Source): String? = src.why.firstOrNull { it.startsWith(URL_TAG) }?.removePrefix(URL_TAG)

    private fun urlFor(address: String, src: Source): String? = sourceUrls[address]?.get(srcKey(src))

    private fun torrentFor(address: String, src: Source): String? = sourceTorrents[address]?.get(srcKey(src))

    /** A magnet in whichever field the addon chose, or a magnet built from a bare info hash. */
    private fun torrentUri(e: JSONObject): String? {
        val magnet = listOf(s(e, "externalUrl", ""), s(e, "url", ""), s(e, "magnet", ""))
            .firstOrNull { it.startsWith("magnet:", true) }
        if (magnet.isNullOrBlank()) {
            val hash = s(e, "infoHash", s(e, "hash", ""))
            if (!Regex("^[a-fA-F0-9]{40}$").matches(hash)) return null
            return withTrackers("magnet:?xt=urn:btih:$hash")
        }
        return withTrackers(magnet)
    }

    /**
     * A torrent index like Torrentio names only the info hash, and a magnet without a `tr=` line
     * must find the swarm through the DHT alone — on a television that means metadata arrives in
     * 10 s or never. The well-known announce servers ride on every bare magnet we mint, which is
     * how a phone app resolves the same torrent in one announce.
     */
    private fun withTrackers(magnet: String): String {
        if (magnet.contains("tr=", true)) return magnet
        return magnet + TRACKERS.joinToString("") { "&tr=$it" }
    }

    /** Sources carry no URL, so the one an addon gave is remembered beside it under its shown fields. */
    private fun srcKey(src: Source): String = "${src.provider}|${src.kind}|${src.quality}|${src.sizeBytes}|${src.label}"

    private val sourceUrls = HashMap<String, HashMap<String, String>>()
    private val sourceTorrents = HashMap<String, HashMap<String, String>>()

    // ---- live TV ----------------------------------------------------------------------------
    // The owner pastes an M3U URL; this parses it into the same channel objects the hub's live row
    // carries. The URL stays in the app's settings, and the parsed list is cached so home does not
    // refetch a playlist on every shelf.
    // identity -> every quality variant of that channel name, in playlist order. A channel is ONE
    // tile with several sources, exactly as a film is; this is what lets a dead feed fail over to the
    // next variant of the same channel instead of to another channel.
    private val liveVariants = HashMap<String, MutableList<LiveVariant>>()
    private val liveNames = HashMap<String, String>()
    private var liveCacheAt = 0L
    private var liveCacheKey = ""
    private var liveCache: JSONArray = JSONArray()
    private val liveCacheFile = File(data, "live-cache.json")

    @Volatile
    private var liveLoading = false

    init {
        // A settings read is cheap and does not touch the network; this is what makes the live row
        // exist on the first frame after a restart.
        loadLiveCache()
    }
    private val liveHealth = LiveHealth(File(data, "live-health.json"))
    private val localLibrary = LocalLibrary(appContext, File(data, "local-index.json"))

    private val MAX_PROBE_PER_PASS = 80

    private val MEDIA_EXT = Regex("\\.(mp4|mkv|webm|m4v|mov|avi|ts|m2ts|flv|wmv|m3u8|mpd)(\\?|#|$)", RegexOption.IGNORE_CASE)

    /**
     * One upstream stream entry -> one [Source], the same reading the hub's addon host applies: quality
     * and size out of the text of the entry, and the kind decided by what the URL actually is.
     *
     * A magnet or info-hash entry is a torrent, which this mode has no engine for; a bare link to a
     * page is not a file either. Both stay in the list marked as what they are — `mode` is not
     * `direct`, so the UI shows them and a Play press never pretends they will start.
     */
    private fun parseStream(e: JSONObject, addonName: String): Source? {
        val nameLines = s(e, "name", "").split("\n")
        val tag = (nameLines.getOrNull(1) ?: nameLines.firstOrNull() ?: "").trim()
        val desc = s(e, "title", "")
        val magnet = s(e, "externalUrl", "").takeIf { it.startsWith("magnet:") } ?: ""
        val isTorrent = magnet.isNotEmpty() || s(e, "infoHash", "").isNotBlank()
        val direct = listOf(s(e, "url", ""), s(e, "externalUrl", ""), s(e, "yt", "")).firstOrNull { it.isNotBlank() } ?: ""
        val quality = QUALITY_P.find(tag)?.value ?: QUALITY_P.find(desc)?.value
            ?: if (FOUR_K.containsMatchIn("$tag $desc")) "2160p" else ""
        val size = e.optLong("videoSize", 0L).takeIf { it > 0 } ?: parseSize("$desc $tag")
        val playable = !isTorrent && direct.isNotBlank() && MEDIA_EXT.containsMatchIn(direct)
        val kind = when {
            isTorrent -> "torrent"
            direct.contains(".m3u8", true) -> "hls"
            direct.contains(".mpd", true) -> "dash"
            else -> "http"
        }
        val label = when {
            playable -> listOf(quality.ifBlank { "stream" }, Format.size(size ?: 0L)).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            isTorrent -> listOf("P2P", quality, Format.size(size ?: 0L)).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            else -> "link \u00B7 " + hostOf(direct)
        }
        val why = when {
            playable -> listOf(URL_TAG + direct, "$addonName \u00B7 a file this TV reads on its own")
            isTorrent -> listOf("$addonName \u00B7 streamed peer-to-peer on this TV")
            else -> listOf("not a file this player can open")
        } +
            // The release name rides on the source so the play walk can check it before spending
            // fifteen seconds on the P2P engine — the same wrong-title reject the hub's picker proved.
            // Addons put the provider word first and the release name on the line after it, with the
            // full filename in the description; read the lines that actually name the copy.
            listOf(NAME_TAG + listOf(tag, desc).joinToString(" "))
        return Source(
            provider = addonName,
            label = label,
            kind = kind,
            quality = quality,
            sizeBytes = size ?: 0L,
            risk = if (!playable && !isTorrent) "not a file" else "",
            mode = when {
                playable -> "direct"
                isTorrent -> "torrent"
                else -> "none"
            },
            score = when {
                playable -> 1.0 + qualityRank(quality) * 0.1
                isTorrent -> 0.5 + qualityRank(quality) * 0.1
                else -> 0.0
            },
            why = why,
        )
    }

    /** The ranking this app applies where a hub's engine would have: what plays, then what looks best. */
    private fun rankSources(list: List<Source>): List<Source> {
        val seen = LinkedHashSet<String>()
        val uniq = ArrayList<Source>()
        for (src in list) {
            if (!seen.add(srcKey(src))) continue
            uniq.add(src)
        }
        return uniq.sortedWith(
            compareByDescending<Source> { it.mode == "direct" }
                .thenByDescending { qualityRank(it.quality) }
                .thenByDescending { it.sizeBytes },
        )
    }

    /** The profile's caps, read when the request is made — the ceiling is never a constant here. */
    private fun applyCaps(sources: List<Source>, profileId: String?): List<Source> {
        val p = readProfilePrefs(profileId ?: "default")
        val cap = s(p, "quality", "").lowercase()
        val maxBytes = p.optLong("maxFileSizeBytes", 0L)
        // The profile's bitrate ceiling is not applied here because a community addon states no bitrate
        // for a stream: there is nothing on this path to compare it against. Size and resolution are.
        val capped = sources.filter { src ->
            (maxBytes <= 0L || src.sizeBytes == 0L || src.sizeBytes <= maxBytes) &&
                (cap.isBlank() || cap == "auto" || cap == "highest" || qualityRank(src.quality) <= qualityRank(cap))
        }
        // A cap that hides everything is a cap the viewer did not mean: show the list and let them see
        // why, rather than an empty "Play from".
        return capped.ifEmpty { sources }
    }

    private fun qualityRank(q: String): Int = when (q.lowercase().removeSuffix("p")) {
        "2160", "4k" -> 5
        "1080" -> 4
        "720" -> 3
        "480" -> 2
        "360" -> 1
        else -> 0
    }

    // ---- identity namespaces --------------------------------------------------------------------

    private fun identityFor(remoteId: String, addonId: String): String = when {
        remoteId.startsWith("tt") -> "imdb:" + remoteId.substringBefore(":")
        remoteId.startsWith("imdb:") || remoteId.startsWith("yt:") -> remoteId
        addonId.isNotBlank() -> "$addonId:$remoteId"
        else -> remoteId
    }

    private fun remoteIdFor(identity: String, addonId: String): String = when {
        identity.startsWith("imdb:") -> identity.removePrefix("imdb:")
        identity.startsWith("$addonId:") -> identity.removePrefix("$addonId:")
        else -> identity
    }

    /** Whether this addon can be asked for this identity at all: its own namespace, or a shared one. */
    private fun addressableBy(identity: String, addonId: String): Boolean =
        identity.startsWith("imdb:") || identity.startsWith("yt:") || identity.startsWith("$addonId:")

    /** Our `type` -> the type this protocol uses for the same thing. */
    private fun communityType(t: String): String? = when (t.lowercase()) {
        "movie" -> "movie"
        "series" -> "series"
        "channel", "tv" -> "tv"
        "video" -> null
        else -> null
    }

    // ---- this app's own memory -------------------------------------------------------------------

    private fun profileList(): List<JSONObject> {
        val arr = readJson(profilesFile).optJSONArray("profiles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** A build ships no household: exactly one neutral profile, created here, renamed by the viewer. */
    private fun ensureProfiles() {
        synchronized(lock) {
            if (profileList().isNotEmpty()) return
            writeJson(
                profilesFile,
                JSONObject().put("profiles", JSONArray().put(
                    JSONObject().put("id", "viewer").put("name", "Viewer")
                        .put("kind", "adult").put("pinRequired", false),
                )),
            )
        }
    }

    /**
     * Create a profile from the same fields a hub accepts: a name, whether this is a child, an age ceiling,
     * languages, a face. A name already taken is refused rather than numbered, so nobody ends up with
     * "Viewer" and "Viewer-2" without having chosen that.
     */
    fun addProfile(spec: JSONObject): JSONObject = synchronized(lock) {
        ensureProfiles()
        val name = s(spec, "name", "").trim()
        if (name.isBlank()) return@synchronized JSONObject().put("error", "a profile needs a name")
        val existing = profileList()
        val id = s(spec, "id", "").trim().ifBlank { profileId(name) }
        if (existing.any { s(it, "id", "") == id }) {
            return@synchronized JSONObject().put("error", "that profile already exists").put("id", id)
        }
        val p = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("kind", if (s(spec, "kind", "").equals("kids", true)) "kids" else "adult")
            .put("pinRequired", spec.optBoolean("pinRequired", false))
        if (!spec.optDouble("age", Double.NaN).isNaN()) p.put("age", spec.optInt("age"))
        spec.optJSONArray("languages")?.takeIf { it.length() > 0 }?.let { p.put("languages", it) }
        s(spec, "avatar", "").trim().takeIf { it.isNotEmpty() }?.let { p.put("avatar", it.take(400)) }
        val arr = JSONArray()
        existing.forEach { arr.put(it) }
        arr.put(p)
        writeJson(profilesFile, JSONObject().put("profiles", arr))
        JSONObject().put("profile", wireProfile(p))
    }

    /**
     * Change one profile, under the same whitelist a hub accepts and its rule about a face: a face travels as
     * a path this backend serves, so a TV never needs an image host to draw its start screen. A code and the
     * wish to be asked for one stay one decision, because the half-state is what leaves a viewer standing
     * behind a wall nothing can open.
     */
    fun patchProfile(id: String, patch: JSONObject): JSONObject = synchronized(lock) {
        val existing = profileList()
        val at = existing.indexOfFirst { s(it, "id", "") == id }
        if (at < 0) return@synchronized JSONObject().put("error", "no such profile").put("id", id)
        val next = JSONObject()
        for (k in existing[at].keys()) next.put(k, existing[at].opt(k))
        var changed = false
        for (k in arrayOf("name", "kind", "age", "languages", "rules", "prefs", "pin", "pinRequired", "avatar")) {
            if (!patch.has(k)) continue
            when (k) {
                "avatar" -> {
                    val face = s(patch, "avatar", "").trim().take(400)
                    if (face.isNotEmpty() && !face.startsWith("/") &&
                        !face.startsWith("http://") && !face.startsWith("https://")) {
                        return@synchronized JSONObject().put(
                            "error", "avatar must be a path this backend serves or an http(s) URL",
                        )
                    }
                    if (face.isEmpty()) next.remove(k) else next.put(k, face)
                }
                "pin" -> {
                    val code = s(patch, "pin", "").trim().take(12)
                    if (code.isEmpty()) next.remove("pin") else next.put("pin", code)
                    next.put("pinRequired", code.isNotEmpty())
                }
                "prefs" -> patch.optJSONObject("prefs")?.let { writeProfilePrefs(id, it) }
                else -> next.put(k, patch.opt(k))
            }
            changed = true
        }
        if (patch.optBoolean("pinRequired", false) && !patch.has("pin") && s(next, "pin", "").isEmpty()) {
            return@synchronized JSONObject().put("error", "set a PIN before asking for one")
        }
        if (!changed) return@synchronized JSONObject().put("error", "nothing to change")
        val arr = JSONArray()
        existing.forEachIndexed { i, p -> if (i != at) arr.put(p) }
        arr.put(next)
        writeJson(profilesFile, JSONObject().put("profiles", arr))
        JSONObject().put("profile", wireProfile(next))
    }

    /** Forget a profile. The last one stays: a TV with no person on it has no home screen to show. */
    fun removeProfile(id: String): JSONObject = synchronized(lock) {
        val existing = profileList()
        if (existing.size <= 1) return@synchronized JSONObject().put("error", "the last profile cannot be removed")
        val keep = existing.filter { s(it, "id", "") != id }
        if (keep.size == existing.size) return@synchronized JSONObject().put("error", "no such profile").put("id", id)
        writeJson(profilesFile, JSONObject().put("profiles", JSONArray(keep)))
        JSONObject().put("removed", id)
    }

    /**
     * Ask that a profile be opened. Here the code is this app's own secret, so it is compared here; against a
     * hub the client sends the code and asks, and never learns what the hub was holding either way.
     */
    fun unlockProfile(id: String, pin: String): JSONObject = synchronized(lock) {
        val held = profileList().firstOrNull { s(it, "id", "") == id }
            ?: return@synchronized JSONObject().put("error", "no such profile").put("id", id)
        val code = s(held, "pin", "")
        when {
            code.isEmpty() -> JSONObject().put("ok", true).put("unlocked", id).put("noPin", true)
            pin == code -> JSONObject().put("ok", true).put("unlocked", id)
            else -> JSONObject().put("error", "wrong PIN").put("unlocked", JSONObject.NULL)
        }
    }

    /** A profile id, with a fallback so a name made entirely of punctuation still gets one. */
    private fun profileId(name: String): String = slugOf(name).ifBlank { "p" }

    /**
     * The way a hub turns words into an identifier. A profile id and the slug a cached face is stored under
     * are derived the same way there, so they are derived the same way here: lowercased, with anything that is
     * not a letter or a digit becoming a dash.
     */
    private fun slugOf(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun writeProfilePrefs(who: String, values: JSONObject) {
        val root = readJson(prefStore)
        root.put(who, values)
        writeJson(prefStore, root)
    }

    // ---- faces -----------------------------------------------------------------------------------
    // The character list, and whatever pictures had already been cached where the app was built, come in from
    // the hub's own directory (build.gradle). A TV asks its own backend for a face and never learns a
    // third-party hostname. A build that carries no pictures says so with `avatarFaces: false` rather than
    // offering a rail of nothing.

    /** One cached face: the bytes, and the content type recorded beside them when they were cached. */
    class Face(val bytes: ByteArray, val type: String)

    private var faceIndex: Set<String>? = null

    private fun faceSlugs(): Set<String> {
        var cached = faceIndex
        if (cached == null) {
            val listed = runCatching { appContext.assets.list(FACE_DIR) }.getOrNull() ?: emptyArray()
            cached = listed.filter { it.endsWith(".img") }.map { it.removeSuffix(".img") }.toSet()
            faceIndex = cached
        }
        return cached
    }

    private fun hasFaces(): Boolean = faceSlugs().isNotEmpty()

    /** What the source called this picture: what it is, and how tall it is, so a card crops it knowingly. */
    private fun faceMeta(slug: String): JSONObject? = runCatching {
        JSONObject(readAsset("$FACE_DIR/$slug.json"))
    }.getOrNull()

    /**
     * The faces this build carries, in the shape a hub's list has. The bundled file names the characters a
     * household may choose; only the ones with a picture beside them are offered, which is what a hub answers
     * when it has cached nothing yet. `limit` bounds the candidates, as it does there, and `seed` is the same
     * reshuffle, so "another set" needs no memory on either side.
     */
    fun charactersJson(limit: Int?, seed: String = ""): JSONObject {
        val wanted = runCatching {
            JSONObject(readAsset("characters/characters.json")).optJSONArray("characters")
        }.getOrNull() ?: JSONArray()
        val n = (limit ?: 40).coerceIn(1, 200)
        val carried = faceSlugs()
        val out = ArrayList<JSONObject>()
        for (i in 0 until minOf(wanted.length(), n)) {
            val entry = wanted.optJSONObject(i) ?: continue
            val page = s(entry, "page", s(entry, "name", ""))
            val slug = slugOf(page)
            if (slug.isEmpty() || slug !in carried) continue
            val meta = faceMeta(slug)
            val w = meta?.optInt("width", 0) ?: 0
            val h = meta?.optInt("height", 0) ?: 0
            out.add(
                JSONObject()
                    .put("id", slug)
                    .put("name", s(entry, "name", ""))
                    .put("label", s(entry, "name", ""))
                    .put("image", "/app/v1/avatars/character/$slug")
                    .put("source", meta?.let { s(it, "attribution", "") } ?: "")
                    .put("page", meta?.let { s(it, "page", "") }.orEmpty().ifBlank { page })
                    .put("licence", meta?.opt("licence") ?: JSONObject.NULL)
                    .put("licenceUrl", meta?.opt("licenceUrl") ?: JSONObject.NULL)
                    .put("description", meta?.let { s(it, "description", "") } ?: "")
                    .put("from", meta?.let { s(it, "source", "") }.orEmpty().ifBlank { "wikipedia" })
                    .put("publisher", JSONObject.NULL)
                    .put("fullName", JSONObject.NULL)
                    .put("alignment", JSONObject.NULL)
                    .put("powerScore", JSONObject.NULL)
                    .put("width", w)
                    .put("height", h)
                    .put("portrait", h >= w),
            )
        }
        return JSONObject().put("characters", JSONArray(orderBySeed(out, seed)))
    }

    /** One cached face by slug, with an honest content type. */
    fun characterImage(id: String): Face? {
        if (!FACE_SLUG.matches(id)) return null
        val bytes = runCatching { appContext.assets.open("$FACE_DIR/$id.img").use { it.readBytes() } }.getOrNull()
            ?: return null
        return Face(bytes, faceMeta(id)?.optString("type")?.takeIf { it.isNotBlank() } ?: "image/jpeg")
    }

    /** The shuffle the hub uses, so a picker's "another set" is a reshuffle rather than a new question. */
    private fun orderBySeed(items: List<JSONObject>, seed: String): List<JSONObject> {
        if (seed.isEmpty()) return items
        fun key(t: String): Long {
            // The hub's own 32-bit FNV-1a. JavaScript wraps this arithmetic at 32 bits (`Math.imul`, then
            // `>>> 0`), so every step here is masked to 32 bits as well; otherwise the two backends would
            // shuffle the same picker into a different order and a household would see different faces on
            // whichever machine it happened to open.
            var x = 2166136261L
            for (ch in t) {
                x = (x xor ch.code.toLong()) and 0xFFFFFFFFL
                x = (x * 16777619L) and 0xFFFFFFFFL
            }
            return x
        }
        return items.sortedWith { a, b ->
            val ka = key(s(a, "id", "") + "|" + seed)
            val kb = key(s(b, "id", "") + "|" + seed)
            if (ka != kb) ka.compareTo(kb) else s(a, "id", "").compareTo(s(b, "id", ""))
        }
    }

    private fun readAsset(path: String): String =
        appContext.assets.open(path).reader().use { it.readText() }

    private fun readProfilePrefs(who: String): JSONObject = readJson(prefStore).optJSONObject(who) ?: JSONObject()

    private fun libraryItems(): List<Item> {
        val map = readJson(libraryFile).optJSONObject("items") ?: return emptyList()
        return map.keys().asSequence().mapNotNull { map.optJSONObject(it) }.map { Item.from(it) }.toList()
    }

    private fun seedFromLibrary(identity: String): Item? =
        readJson(libraryFile).optJSONObject("items")?.optJSONObject(identity)?.let { Item.from(it) }

    private fun knownType(identity: String): String? =
        readJson(libraryFile).optJSONObject("items")?.optJSONObject(identity)?.optString("type")?.takeIf { it.isNotBlank() }

    /**
     * Remember what a shelf showed, so Continue watching and Recently added answer with the network
     * down. Bounded, because this is a 2 GB TV: the newest titles this app has ever put on screen.
     */
    private fun rememberLibrary(items: List<Item>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val root = readJson(libraryFile)
            val map = root.optJSONObject("items") ?: JSONObject()
            var changed = false
            for (i in items) {
                if (i.identity.isBlank() || map.has(i.identity)) continue
                map.put(i.identity, itemJson(i.copy(addedAt = if (i.addedAt > 0) i.addedAt else System.currentTimeMillis())))
                changed = true
            }
            while (map.length() > LIBRARY_MAX) {
                // Drop the oldest entry; `keys()` is insertion-ordered, which is the order we added them.
                val oldest = map.keys().asSequence().firstOrNull() ?: break
                map.remove(oldest)
            }
            if (changed) {
                root.put("items", map)
                writeJson(libraryFile, root)
            }
        }
    }

    /** A detail page knows more than a shelf did, so it overwrites its own entry. */
    private fun rememberItem(o: JSONObject?) {
        if (o == null) return
        val identity = s(o, "identity", "")
        if (identity.isBlank()) return
        synchronized(lock) {
            val root = readJson(libraryFile)
            val map = root.optJSONObject("items") ?: JSONObject()
            // A thin tile must never overwrite a fatter one. The hub learned this when a search result
            // with no episodes replaced the remembered record that held 4,862 of them, and the next
            // open of the same show answered "one episode". Keep the longer episode list either way.
            val old = map.optJSONObject(identity)
            val merged = if (old != null &&
                o.optJSONArray("episodes").length() < old.optJSONArray("episodes").length()
            ) JSONObject(o.toString()).put("episodes", old.optJSONArray("episodes")) else o
            map.put(identity, merged)
            root.put("items", map)
            writeJson(libraryFile, root)
        }
    }

    private fun rememberItem(i: Item) = rememberItem(itemJson(i))

    // ---- merge ---------------------------------------------------------------------------------

    /** One tile per identity: the first answer keeps each field, the rest fill gaps and add sources. */
    private fun dedupe(items: List<Item>): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (it in items) {
            if (it.identity.isBlank()) continue
            val keep = out[it.identity] ?: it.also { n -> out[it.identity] = n }
            if (keep === it) continue
            out[it.identity] = keep.copy(
                title = keep.title.ifBlank { it.title },
                poster = keep.poster ?: it.poster,
                backdrop = keep.backdrop ?: it.backdrop,
                logo = keep.logo ?: it.logo,
                maturity = keep.maturity.ifBlank { it.maturity },
                type = if (keep.type == "movie" && it.type != "movie") it.type else keep.type,
                year = if (keep.year != 0) keep.year else it.year,
                match = if (keep.match != 0) keep.match else it.match,
                durationMs = if (keep.durationMs != 0L) keep.durationMs else it.durationMs,
                releasedAt = if (keep.releasedAt != 0L) keep.releasedAt else it.releasedAt,
                addedAt = if (keep.addedAt != 0L) keep.addedAt else it.addedAt,
                inCinemas = keep.inCinemas || it.inCinemas,
                genres = (keep.genres + it.genres).distinct(),
                languages = (keep.languages + it.languages).distinct(),
                episodes = unionEpisodes(keep.episodes, it.episodes),
                subtitleLanguages = (keep.subtitleLanguages + it.subtitleLanguages).distinct(),
                sources = rankSources(keep.sources + it.sources),
            )
        }
        return out.values.toList()
    }

    /**
     * One identity's episodes, from every source that has them: deduped by season/episode (the richer
     * title wins) and sorted. A thin addon and a complete one both speak for the same show; neither
     * is allowed to blank the other out.
     */
    private fun unionEpisodes(a: List<Episode>, b: List<Episode>): List<Episode> {
        val bySeat = LinkedHashMap<Pair<Int, Int>, Episode>()
        for (e in a + b) {
            if (e.season <= 0 || e.episode <= 0) continue
            val key = e.season to e.episode
            val old = bySeat[key]
            if (old == null || old.title.isBlank() && e.title.isNotBlank()) bySeat[key] = e
        }
        return bySeat.values.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * A series whose meta is thin, joined with the same show under other addons' ids, BEFORE the meta
     * answer ships. Cinemeta answers many Indian soaps with artwork and no episode list at all, while a
     * community addon that indexes the same title carries thousands of episodes under its own id: the
     * hub adopted the sibling the same way and that is what turned "no episodes" into "Season 1 - 4,862
     * episodes". Search is the only shared address space, so that is where the sibling is looked for.
     */
    private fun adoptEpisodeSiblings(item: Item, eps: List<Episode>): List<Episode> {
        if (item.title.isBlank()) return eps
        var out = eps
        for (am in addonManifests()) {
            if (!am.has("meta")) continue
            val term = item.title
            for (c in am.searchableCatalogs()) {
                val type = communityType(s(c, "type", "series")) ?: continue
                val metas = addons.catalog(am, type, s(c, "id", ""), "search=$term", wait = true, ttlMs = ttlMs())
                if (metas == null) continue
                for (i in 0 until metas.length()) {
                    val m = metas.optJSONObject(i) ?: continue
                    val siblingId = s(m, "id", "")
                    if (siblingId.isBlank() || remoteIdFor(item.identity, am.spec.id) == siblingId) continue
                    if (!s(m, "name", "").equals(term, ignoreCase = true)) continue
                    val full = addons.meta(am, type, siblingId, wait = true, ttlMs = ttlMs()) ?: continue
                    Log.i("ScenixLocalHub", "join: sibling $siblingId meta -> ${episodesFromMeta(full).size} episodes")
                    out = unionEpisodes(out, episodesFromMeta(full)
                        .map { Episode(it.optInt("season", 0), it.optInt("episode", 0), s(it, "title", "")) })
                    if (out.size >= 5) return out
                }
                if (out.size >= 5) return out
            }
            if (out.size >= 5) break
        }
        return out
    }

    /**
     * Does this copy's release name belong to the title the viewer pressed? Torrent addons name the
     * file, so a copy whose name matches neither the title nor the SxxExx being asked for is the wrong
     * show, and the P2P engine would spend fifteen seconds proving it. `null` = no objection (a copy
     * that carries no name stays innocent); otherwise the reason to tell the player.
     */
    private fun wrongReleaseName(cand: Source, wantTitle: String, wantYear: Int, season: Int, episode: Int): String? {
        val named = cand.why.firstOrNull { it.startsWith(NAME_TAG) }?.removePrefix(NAME_TAG)?.trim().orEmpty()
        if (named.isBlank()) return null
        val flat = named.lowercase()
        if (season > 0 && episode > 0) {
            // The copy names the very episode being played: the show's own words may be missing or
            // abbreviated, but the number is the address, and the address agrees.
            val code = "S%02dE%02d".format(season, episode)
            if (flat.contains(code.lowercase()) || flat.contains("S$season E$episode".lowercase())) return null
        }
        val want = NAME_WORD.splitToSequence(wantTitle.lowercase()).filter { it.length >= 3 }.toList()
        if (want.isEmpty()) return null
        val have = NAME_WORD.splitToSequence(named.lowercase()).filter { it.length >= 3 }.toSet()
        val hits = want.count { have.contains(it) }
        if (hits >= minOf(want.size, 2)) return null
        Log.i("ScenixLocalHub", "wrong-name reject: name=\"$named\" want=$want")
        val label = cand.label.ifBlank { cand.provider }
        return "$label: its release name doesn't match ${wantTitle.take(40)}"
    }

    // ---- small helpers --------------------------------------------------------------------------

    private val lock = Any()

    private fun readJson(f: File): JSONObject = synchronized(lock) {
        try {
            if (f.isFile) JSONObject(f.readText(Charsets.UTF_8)) else JSONObject()
        } catch (t: Throwable) {
            Log.w(TAG, "${f.name} unreadable, starting over", t)
            JSONObject()
        }
    }

    private fun writeJson(f: File, o: JSONObject) {
        synchronized(lock) {
            try {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(o.toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
                Unit
            } catch (t: Throwable) {
                Log.w(TAG, "${f.name} write failed", t)
            }
        }
    }

    private fun JSONObject.optStringList(name: String): List<String> = jsonStrings(optJSONArray(name))

    private fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "").take(12).ifBlank { "addon" }

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')

    private fun parseIso(iso: String): Long? = try {
        if (iso.isBlank()) null
        else java.time.Instant.parse(if (iso.length == 10) "${iso}T00:00:00Z" else iso).toEpochMilli()
    } catch (t: Throwable) {
        null
    }

    private fun yearOf(epochMs: Long): Int =
        java.util.Calendar.getInstance().apply { timeInMillis = epochMs }.get(java.util.Calendar.YEAR)

    private fun runtimeMs(runtime: String): Long? =
        MINUTES.find(runtime)?.groupValues?.get(1)?.toLongOrNull()?.times(60_000L)

    private fun parseSize(text: String): Long? = SIZE.find(text)?.let {
        val v = it.groupValues[1].toDoubleOrNull() ?: return null
        (v * when (it.groupValues[2].uppercase()) {
            "GB" -> 1e9
            "MB" -> 1e6
            else -> 1e3
        }).toLong()
    }

    /** One viewer's stopping place per title, under their profile id. */
    private fun forWhoPut(forWho: JSONObject, identity: String, positionMs: Long, watchedMs: Long, episode: JSONObject?) {
        forWho.put(
            identity,
            JSONObject()
                .put("positionMs", positionMs)
                .put("watchedMs", if (watchedMs > 0) watchedMs else positionMs)
                .put("updatedAt", System.currentTimeMillis())
                .put("episode", episode ?: JSONObject.NULL),
        )
    }

    private fun manifestsHaveSubs(): Boolean = addonManifests().any { it.has("subtitles") }

    /**
     * A brand-new standalone install ships with the default sources enabled AND their shelves warmed:
     * the home path is cache-only, so seeding the list without warming it would show the empty-state
     * card on the very screen the defaults exist to fill. This runs as the LAST field initialiser, not
     * in `init` — `settings()` synchronises on a lock that does not exist yet when `init` runs.
     */
    private val firstBoot: Unit = run {
        try {
            if (!settingsFile.exists()) {
                val seeded = settings().optJSONArray("addons")
                if (seeded != null && seeded.length() > 0) {
                    setSettings(JSONObject().put("addons", seeded))
                    Thread { refreshAddons() }.apply { isDaemon = true; name = "scenix-warm" }.start()
                }
            }
        } catch (t: Throwable) {
            // A first-boot convenience must never break the hub this object serves.
            android.util.Log.w("ScenixLocalHub", "first-boot seed skipped", t)
        }
    }

    companion object {
        private const val TAG = "ScenixLocal"
        private const val ROW_MAX = 30
        private const val MAX_CATALOGS_PER_ADDON = 6
        private const val LIBRARY_MAX = 800
        // Where the faces this build happens to carry live inside the APK, and the shape of the slug each is
        // named by. Both come from how a hub stores them, so the same path answers on either backend.
        private const val FACE_DIR = "characters/faces"
        private val FACE_SLUG = Regex("[a-z0-9-]+")
        // The age a kids profile is held to, the same default the app's own kids rule uses. A page served
        // by this backend runs no rules of its own, so this is the one that protects a kids shelf.
        private const val KIDS_MAX_AGE = 7
        private const val URL_TAG = "url="
        private const val NAME_TAG = "name="
        // One press walks this many copies of the title before it gives up, and the walk as a whole
        // stays inside the player's own patience (the client waits 40 s for /play and /switch). A
        // cold P2P engine on a television needs most of that budget for its FIRST copy: the magnet
        // keeps resolving in the background, so a second press finds it at once.
        private const val PLAY_MAX_TRIES = 6
        private const val PLAY_TOTAL_BUDGET_MS = 35_000L
        // The opening block a torrent must have in hand before its URL goes to the player.
        private const val TORRENT_FIRST_CHUNK = 256 * 1024
        // How long a seat that failed to answer stays demoted behind fresh swarms.
        private const val WARMING_DEMOTE_MS = 5 * 60_000L
        // Public announce servers, percent-encoded, added to every magnet that lacks its own `tr=`.
        // (The same list the big phone players ship; a magnet with no tracker is DHT-only luck.)
        private val TRACKERS = listOf(
            "udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce",
            "udp%3A%2F%2Ftracker.torrent.eu.org%3A451%2Fannounce",
            "udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce",
            "udp%3A%2F%2Fexodus.desync.com%3A66%2Fannounce",
            "udp%3A%2F%2Ftracker.tiny-vps.com%3A6969%2Fannounce",
            "udp%3A%2F%2Ftracker.theoks.net%3A6969%2Fannounce",
            "udp%3A%2F%2Ftracker.files.fm%3A6969%2Fannounce",
            "udp%3A%2F%2Ftracker.pomf.se%3A80%2Fannounce",
        )
        // A release name, split the way a title is read: words, not punctuation.
        private val NAME_WORD = Regex("[^a-z0-9]+")
        private const val LIVE_TTL_MS = 5 * 60_000L

        private val QUALITY_P = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
        private val FOUR_K = Regex("\\b4k\\b|\\buhd\\b|\\b2160\\b", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE)
        private val SIZE = Regex("([\\d.]+)\\s*(GB|MB|KB)", RegexOption.IGNORE_CASE)

        /**
         * One instance per process. [LocalServer] and the HTTP client must answer out of the same
         * cache, profiles and settings, or standalone mode would drift from the contract it serves.
         */
        @Volatile private var instance: LocalHub? = null

        fun get(context: Context): LocalHub = instance ?: synchronized(this) {
            instance ?: LocalHub(context).also { instance = it }
        }
    }
}
