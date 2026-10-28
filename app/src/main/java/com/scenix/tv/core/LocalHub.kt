package com.scenix.tv.core

import android.content.Context
import android.util.Log
import com.scenix.tv.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Standalone mode: this app hosts the `/app/v1` contract itself (design/deployment-modes.md
 * Rule 2). It answers the same JSON a hub answers, over loopback, so the page cannot tell which
 * machine it is talking to. The only thing the UI branches on is what a backend says it can do —
 * the `capabilities` list in its manifest.
 *
 * Where the content comes from: addons, over plain HTTP, in the community manifest protocol
 * (`manifest`, `catalog`, `meta`, `stream`, `subtitles`). Which addons, and how each is configured,
 * belongs to the owner — it is read from this app's own settings, never from a constant, so a build
 * ships no content source at all and an empty home is a normal first-run state rather than a bug.
 *
 * What this mode honestly cannot do is stated in `capabilities` instead of broken in the UI: there is
 * no torrent engine here and no shared gateway, so a source is playable only if an addon hands back
 * an HTTP/HLS/DASH file this device can read by itself; there is no live tuner, no local files, no
 * trailer search. Anything not listed is not offered.
 *
 * Everything the addons answer is cached under `filesDir`, along with this app's own profiles,
 * progress and memory of the titles it has shown. The home path is cache-first, so a slow addon
 * costs a shorter shelf rather than a hung TV; only a detail page and a Play press wait for an
 * answer.
 */
class LocalHub private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val addons = AddonClient(appContext)

    /** Everything this app owns for itself: settings, profiles, what it has seen, where people stopped. */
    private val data = File(appContext.filesDir, "scenix").apply { mkdirs() }
    private val settingsFile = File(data, "settings.json")
    private val profilesFile = File(data, "profiles.json")
    private val libraryFile = File(data, "library.json")
    private val progressFile = File(data, "progress.json")
    private val prefStore = File(data, "profile-prefs.json")

    // ---- the owner's settings -----------------------------------------------------------------

    /**
     * `{ addons: [...], audioLanguages: [...], subtitleLanguages: [...], adult: false }` in one file.
     * Shipped empty: no addon, no language and no channel is preselected by a build, because none of
     * them is the same between two households.
     */
    fun settings(): JSONObject = synchronized(lock) {
        val o = readJson(settingsFile)
        JSONObject()
            .put("addons", o.optJSONArray("addons") ?: JSONArray())
            .put("audioLanguages", o.optJSONArray("audioLanguages") ?: JSONArray())
            .put("subtitleLanguages", o.optJSONArray("subtitleLanguages") ?: JSONArray())
            .put("adult", o.optBoolean("adult", false))
            .put("cacheTtlHours", o.optInt("cacheTtlHours", 6))
    }

    /** Merge what the caller sent into what is stored, so a screen that edits one field keeps the rest. */
    fun setSettings(body: JSONObject): JSONObject = synchronized(lock) {
        val merged = settings()
        for (k in arrayOf("addons", "audioLanguages", "subtitleLanguages")) {
            if (body.has(k)) merged.put(k, body.optJSONArray(k) ?: JSONArray())
        }
        if (body.has("adult")) merged.put("adult", body.optBoolean("adult", false))
        if (body.has("cacheTtlHours")) merged.put("cacheTtlHours", body.optInt("cacheTtlHours", 6))
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
        .put("settings", settings())
        .put("defaults", defaultSettings())
        .put("languageNames", languageNames())
        .put("file", settingsPath())

    /** What a settings file that says nothing means: no addon, no language and no channel preselected. */
    private fun defaultSettings(): JSONObject = JSONObject()
        .put("addons", JSONArray())
        .put("audioLanguages", JSONArray())
        .put("subtitleLanguages", JSONArray())
        .put("adult", false)
        .put("cacheTtlHours", 6)

    /**
     * Words for the codes this backend actually holds. There is no table of every language baked in here —
     * which tongues a household listens in is not a fact about this app — so a screen labels exactly the
     * choices in front of it.
     */
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
            .put("gateway", false)     // no engine fetches a torrent here: a source is a URL or nothing
            .put("live", false)        // no channel list to tune
            .put("localFiles", false)  // no device file browser: sources come from addons
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
            .put("theme", "scenix-dark")
            .put("api", "/app/v1")
            .put("capabilities", caps)
            .put("addons", addonsJson())
            .put("rows", rows)
            .put("cards", JSONArray(listOf("poster", "hero", "continue")))
            .put("players", JSONArray(listOf("http", "hls", "dash")))
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

    fun metaJson(identity: String, type: String?): JSONObject? {
        val t = type?.takeIf { it.isNotBlank() } ?: knownType(identity) ?: "movie"
        val answers = ArrayList<Item>()
        for (am in addonManifests()) {
            if (!am.has("meta") || !addressableBy(identity, am.spec.id)) continue
            val meta = addons.meta(am, communityType(t) ?: "movie", remoteIdFor(identity, am.spec.id), wait = true, ttlMs = ttlMs())
                ?: continue
            answers.add(Item.from(itemFromMeta(meta, am, t)))
        }
        val seed = seedFromLibrary(identity)
        if (answers.isEmpty() && seed == null) return null
        val merged = dedupe(answers).firstOrNull() ?: seed!!
        // Sources are a separate resource in this protocol, so they are looked up here, once, on a
        // round trip the viewer is already waiting for. Which profile is watching is not part of a
        // meta call, so the ceiling applied here is the one this app was left on.
        val withSources = merged.copy(
            sources = applyCaps(collectSources(identity, t, wait = true), null),
            subtitleLanguages = if (manifestsHaveSubs()) settings().optStringList("subtitleLanguages") else emptyList(),
        )
        rememberItem(withSources)
        return itemJson(withSources)
    }

    fun playJson(body: JSONObject): JSONObject {
        val identity = s(body, "identity", "")
        if (identity.isBlank()) return JSONObject().put("error", "identity required")
        val type = s(body, "type", "").ifBlank { knownType(identity) ?: "movie" }
        val season = body.optInt("season", 0)
        val episode = body.optInt("episode", 0)
        // A series is one tile with many addresses: the addon names an episode `<show>:<season>:<episode>`.
        val address = if (season > 0 && episode > 0) "$identity:$season:$episode" else identity
        val profileId = s(body, "profile", "").ifBlank { null }
        val sources = applyCaps(collectSources(address, type, wait = true), profileId)
        val wanted = s(body, "source", s(body, "provider", ""))
        val pick = sources.firstOrNull { it.mode == "direct" && (wanted.isBlank() || it.label == wanted || it.provider == wanted) }
            ?: sources.firstOrNull { it.mode == "direct" }
            ?: return JSONObject()
                .put("error", "nothing here is a file this TV can open on its own")
                .put("sources", JSONArray(sources.map { sourceJson(it) }))
        val url = urlFor(address, pick) ?: return JSONObject().put("error", "stream link expired, open the title again")
        return JSONObject()
            .put("url", url)
            .put("kind", pick.kind)
            .put("mode", "direct")     // this device reads the file itself: no gateway, no re-mux
            .put("headers", JSONObject())
            .put("why", JSONArray(pick.why))
            .put("episode", if (season > 0 && episode > 0) JSONObject()
                .put("season", season).put("episode", episode) else JSONObject.NULL)
            .put("nextEpisode", if (season > 0 && episode > 0) JSONObject()
                .put("season", season).put("episode", episode + 1) else JSONObject.NULL)
    }

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

        // Continue watching: what this profile stopped, most recently stopped first.
        val who = profileId ?: "default"
        val progress = readJson(progressFile).optJSONObject(who)
        if (progress != null && progress.length() > 0) {
            val items = ArrayList<Item>()
            val names = ArrayList<String>()
            for (k in progress.keys()) names.add(k)
            names.sortByDescending { progress.optJSONObject(it)?.optLong("updatedAt") ?: 0L }
            for (identity in names.take(ROW_MAX)) {
                val seed = seedFromLibrary(identity) ?: continue
                Progress.from(progress.optJSONObject(identity))?.let { items.add(seed.copy(progress = it)) }
            }
            if (items.isNotEmpty()) out.add(LocalRow("continue", "Continue watching", "continue", 5, "all", items))
        }

        // Recently added: this app's own memory of what it has put on a shelf, newest first.
        val library = libraryItems().filter { it.addedAt > 0 }.sortedByDescending { it.addedAt }.take(ROW_MAX)
        if (library.isNotEmpty()) out.add(LocalRow("justadded", "Recently added", "poster", 10, "all", library))

        for (am in addonManifests()) {
            if (!am.has("catalog")) continue
            for (c in am.catalogs.take(MAX_CATALOGS_PER_ADDON)) {
                val type = communityType(s(c, "type", "movie")) ?: continue
                val metas = addons.catalog(am, type, s(c, "id", ""), "default", wait = false, ttlMs = ttlMs())
                    ?: continue
                val items = itemsFromMetas(metas, am, type)
                if (items.isEmpty()) continue
                out.add(
                    LocalRow(
                        id = rowIdFor(am, type, s(c, "id", "default")),
                        title = s(c, "name", am.spec.name),
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
        for (am in addonManifests()) {
            if (!am.has("stream") || !addressableBy(address, am.spec.id)) continue
            val streams = addons.streams(am, t, remoteIdFor(address, am.spec.id), wait, ttlMs = ttlMs()) ?: continue
            for (i in 0 until streams.length()) {
                val e = streams.optJSONObject(i) ?: continue
                val src = parseStream(e, am.spec.name) ?: continue
                found.add(src)
                urlOf(src)?.let { urls[srcKey(src)] = it }
            }
        }
        if (found.isNotEmpty()) sourceUrls[address] = urls
        return rankSources(found)
    }

    private fun urlOf(src: Source): String? = src.why.firstOrNull { it.startsWith(URL_TAG) }?.removePrefix(URL_TAG)

    private fun urlFor(address: String, src: Source): String? = sourceUrls[address]?.get(srcKey(src))

    /** Sources carry no URL, so the one an addon gave is remembered beside it under its shown fields. */
    private fun srcKey(src: Source): String = "${src.provider}|${src.kind}|${src.quality}|${src.sizeBytes}|${src.label}"

    private val sourceUrls = HashMap<String, HashMap<String, String>>()

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
            isTorrent -> listOf("torrent", quality, Format.size(size ?: 0L)).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            else -> "link \u00B7 " + hostOf(direct)
        }
        val why = when {
            playable -> listOf(URL_TAG + direct, "$addonName \u00B7 a file this TV reads on its own")
            isTorrent -> listOf("torrent \u00B7 no engine in standalone mode")
            else -> listOf("not a file this player can open")
        }
        return Source(
            provider = addonName,
            label = label,
            kind = kind,
            quality = quality,
            sizeBytes = size ?: 0L,
            risk = if (!playable && !isTorrent) "not a file" else "",
            mode = if (playable) "direct" else "none",
            score = if (playable) 1.0 + qualityRank(quality) * 0.1 else 0.0,
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
            map.put(identity, o)
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
                episodes = if (keep.episodes.isNotEmpty()) keep.episodes else it.episodes,
                subtitleLanguages = (keep.subtitleLanguages + it.subtitleLanguages).distinct(),
                sources = rankSources(keep.sources + it.sources),
            )
        }
        return out.values.toList()
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
