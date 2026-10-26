package com.scenix.tv.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.scenix.tv.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.math.roundToInt

/**
 * Standalone mode: this app hosts the `/app/v1` contract itself (design/deployment-modes.md
 * Rule 2). `HttpHub` and `LocalHub` are two implementations of one [HubClient], so no screen, card,
 * rule or player in this app knows which one it is talking to. The only thing the UI branches on is
 * what a backend says it can do — the `capabilities` list in its manifest.
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
class LocalHub private constructor(context: Context) : HubClient {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(POOL_THREADS, ThreadFactory { r ->
        Thread(r, "scenix-local").apply { isDaemon = true }
    })
    private val addons = AddonClient(appContext)

    /** Everything this app owns for itself: settings, profiles, what it has seen, where people stopped. */
    private val data = File(appContext.filesDir, "scenix").apply { mkdirs() }
    private val settingsFile = File(data, "settings.json")
    private val profilesFile = File(data, "profiles.json")
    private val libraryFile = File(data, "library.json")
    private val progressFile = File(data, "progress.json")
    private val prefStore = File(data, "profile-prefs.json")

    override val label: String get() = "this TV (standalone)"

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

    fun addonSpecs(): List<AddonSpec> {
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
        }.filter { it.enabled }
    }

    /** Addons this app can answer for right now — an unread manifest simply contributes nothing. */
    private fun addonManifests(): List<AddonManifest> =
        addonSpecs().mapNotNull { addons.manifest(it, wait = false, ttlMs = ttlMs()) }

    private fun ttlMs(): Long = settings().optLong("cacheTtlHours", 6L).coerceIn(1L, 24L) * 3600_000L

    // ---- the contract in JSON -----------------------------------------------------------------
    // [LocalServer] answers these paths directly and the [HubClient] methods at the bottom wrap the
    // same calls, so the native screens and the WebView front end of standalone mode cannot drift.

    fun manifestJson(): JSONObject {
        val manifests = addonManifests()
        val caps = ArrayList(
            listOf(
                "direct-http",        // a file this device plays by itself
                "addon-catalogs",     // shelves come from the installed addons
                "local-cache",        // answers survive the network being down
                "local-profiles",     // profiles are created and kept on this TV
                "profile-edit",
                "local-prefs",
                "search",
            ),
        )
        if (manifests.any { it.has("subtitles") }) caps.add("subs")
        // Deliberately absent: "torrent-gateway", "live-tv", "local-files", "trailers". A hub with an
        // engine offers them; this device does not, so the UI hides them instead of failing on tap.
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
            .put("capabilities", JSONArray(caps))
            .put("addons", addonsJson())
            .put("rows", rows)
            .put("cards", JSONArray(listOf("poster", "hero", "continue")))
            .put("players", JSONArray(listOf("http", "hls", "dash")))
    }

    fun profilesJson(): JSONObject {
        ensureProfiles()
        val stored = readJson(prefStore)
        val out = JSONArray()
        for (p in profileList()) {
            val id = s(p, "id", "")
            out.put(
                JSONObject()
                    .put("id", id)
                    .put("name", s(p, "name", ""))
                    .put("isKids", p.optBoolean("isKids", false))
                    .put("hasPin", p.optBoolean("hasPin", false))
                    .put("prefs", stored.optJSONObject(id) ?: JSONObject()),
            )
        }
        return JSONObject().put("profiles", out)
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
        val items = dedupe(found).sortedByDescending { it.match }
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

    fun prefsJson(profileId: String?): JSONObject =
        JSONObject().put("prefs", readProfilePrefs(profileId ?: "default"))

    fun setPrefsJson(values: JSONObject, profileId: String?): JSONObject = synchronized(lock) {
        val who = profileId ?: "default"
        val root = readJson(prefStore)
        root.put(who, values)
        writeJson(prefStore, root)
        JSONObject().put("prefs", values)
    }

    /**
     * The addons this TV is pulling from, in the shape the Settings screen already renders for a hub's
     * list. `lastError` says what is not working in the owner's own words: the manifest is not fetched
     * yet, or it did not answer.
     */
    fun addonsJson(): JSONArray {
        val out = JSONArray()
        for (spec in addonSpecs()) {
            val am = addons.manifest(spec, wait = false, ttlMs = ttlMs())
            out.put(
                JSONObject()
                    .put("id", spec.id)
                    .put("name", spec.name)
                    .put("origin", "user")
                    .put("url", spec.manifestUrl)
                    .put("enabled", spec.enabled)
                    .put("kinds", JSONArray(kindsOf(am)))
                    .put("count", am?.catalogs?.size ?: 0)
                    .put("lastError", if (am == null) "manifest not fetched yet" else ""),
            )
        }
        return out
    }

    private fun kindsOf(am: AddonManifest?): List<String> = when {
        am == null -> emptyList()
        else -> listOfNotNull(
            "title".takeIf { am.has("catalog") },
            "stream".takeIf { am.has("stream") },
            "subtitle".takeIf { am.has("subtitles") },
        )
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
    fun refreshAddons(cb: HubCb<Int>) = reply({
        addonSpecs().count { addons.manifest(it, wait = true, ttlMs = ttlMs()) != null }
    }, cb)

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
        val seen = LinkedHashSet<String>()
        val merged = ArrayList<LocalRow>()
        for (r in out) {
            val items = ArrayList<Item>()
            for (it in r.items) {
                if (r.id.startsWith("addon:") && !seen.add(it.identity)) continue
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
        val bits = listOf(quality, size)
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
        val maxBitrate = p.optLong("maxBitrate", 0L)
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
                    JSONObject().put("id", "viewer").put("name", "Viewer").put("isKids", false).put("hasPin", false),
                )),
            )
        }
    }

    fun addProfile(name: String, isKids: Boolean): JSONObject = synchronized(lock) {
        ensureProfiles()
        val existing = profileList()
        val base = slug(name.ifBlank { "viewer" })
        var n = 1
        var id = base
        while (existing.any { s(it, "id", "") == id }) id = "$base-${++n}"
        val p = JSONObject()
            .put("id", id)
            .put("name", name.ifBlank { "Viewer ${existing.size + 1}" })
            .put("isKids", isKids)
            .put("hasPin", false)
        val arr = JSONArray()
        existing.forEach { arr.put(it) }
        arr.put(p)
        writeJson(profilesFile, JSONObject().put("profiles", arr))
        p
    }

    fun removeProfile(id: String): Boolean {
        synchronized(lock) {
            val existing = profileList()
            val keep = existing.filter { s(it, "id", "") != id }
            if (keep.size == existing.size || keep.isEmpty()) return false
            writeJson(profilesFile, JSONObject().put("profiles", JSONArray(keep)))
            return true
        }
    }

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

    // ---- the HubClient surface: the same shapes, delivered on the main thread ----------------------

    private fun <T> reply(work: () -> T, cb: HubCb<T>) {
        try {
            pool.execute {
                var v: T? = null
                var err: String? = null
                try {
                    v = work()
                } catch (t: Throwable) {
                    err = t.message ?: t.javaClass.simpleName
                    Log.w(TAG, "local hub call failed", t)
                }
                val value = v
                main.post { cb.onResult(value, err) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "local hub queue rejected", t)
            main.post { cb.onResult(null, "busy") }
        }
    }

    override fun manifest(cb: HubCb<Manifest>) = reply({ Manifest.from(manifestJson()) }, cb)

    override fun profiles(cb: HubCb<List<Profile>>) =
        reply({ Profile.list(profilesJson().optJSONArray("profiles")) }, cb)

    override fun home(profileId: String?, cb: HubCb<List<Row>>) =
        reply({ Row.list(homeJson(profileId).optJSONArray("rows")) }, cb)

    override fun row(rowId: String, profileId: String?, cb: HubCb<Row?>) =
        reply({ rowJson(rowId, profileId)?.let { Row.from(it) } }, cb)

    override fun search(q: String, profileId: String?, cb: HubCb<List<Row>>) =
        reply({ Row.list(searchJson(q, profileId).optJSONArray("rows")) }, cb)

    override fun meta(identity: String, type: String?, cb: HubCb<Item?>) =
        reply({ metaJson(identity, type)?.let { Item.from(it) } }, cb)

    override fun play(
        identity: String, provider: String?, profileId: String?, type: String?, season: Int, episode: Int, cb: HubCb<PlayTarget?>,
    ) = reply(
        {
            PlayTarget.from(
                playJson(
                    JSONObject()
                        .put("identity", identity)
                        .put("type", type ?: JSONObject.NULL)
                        .put("profile", profileId ?: JSONObject.NULL)
                        .put("source", provider ?: JSONObject.NULL)
                        .put("season", season)
                        .put("episode", episode),
                ),
            )
        },
        cb,
    )

    override fun progress(identity: String, positionMs: Long, profileId: String?, episode: Episode?) {
        val body = JSONObject()
            .put("identity", identity)
            .put("positionMs", positionMs)
            .put("profile", profileId ?: "default")
        if (episode != null) {
            body.put("episode", JSONObject()
                .put("season", episode.season).put("episode", episode.episode).put("title", episode.title))
        }
        try {
            pool.execute {
                try {
                    progressJson(body)
                } catch (t: Throwable) {
                    Log.w(TAG, "local progress write failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "local progress queue rejected", t)
        }
    }

    /** This backend is always there: standalone has no network peer to lose. */
    override fun health(cb: HubCb<Boolean>) { cb.onResult(true, null) }

    override fun addons(cb: HubCb<List<Addon>>) = reply({ Addon.list(addonsJson()) }, cb)

    override fun subs(identity: String, langs: List<String>, profileId: String?, cb: HubCb<List<Sub>>) =
        reply({ Sub.list(subsJson(identity, langs, knownType(identity))) }, cb)

    override fun prefs(profileId: String?, cb: HubCb<HubPrefs?>) =
        reply({ HubPrefs.from(prefsJson(profileId).optJSONObject("prefs") ?: JSONObject()) }, cb)

    override fun setPrefs(values: HubPrefs, profileId: String?, cb: HubCb<HubPrefs?>) =
        reply({ HubPrefs.from(setPrefsJson(values.toJson(profileId ?: ""), profileId).optJSONObject("prefs") ?: JSONObject()) }, cb)

    /** No trailer search here — and the manifest does not advertise one, so the UI never asks. */
    override fun trailer(identity: String, cb: HubCb<String?>) { cb.onResult(null, null) }

    private fun manifestsHaveSubs(): Boolean = addonManifests().any { it.has("subtitles") }

    companion object {
        private const val TAG = "ScenixLocal"
        private const val POOL_THREADS = 3
        private const val ROW_MAX = 30
        private const val MAX_CATALOGS_PER_ADDON = 6
        private const val LIBRARY_MAX = 800
        private const val URL_TAG = "url="

        private val QUALITY_P = Regex("(\\d{3,4})p", RegexOption.IGNORE_CASE)
        private val FOUR_K = Regex("\\b4k\\b|\\buhd\\b|\\b2160\\b", RegexOption.IGNORE_CASE)
        private val MINUTES = Regex("(\\d+)\\s*min", RegexOption.IGNORE_CASE)
        private val SIZE = Regex("([\\d.]+)\\s*(GB|MB|KB)", RegexOption.IGNORE_CASE)

        /**
         * One instance per process. The native screens and [LocalServer] must answer out of the same
         * cache, profiles and settings, or the two front ends of standalone mode would drift apart on
         * the same TV.
         */
        @Volatile private var instance: LocalHub? = null

        fun get(context: Context): LocalHub = instance ?: synchronized(this) {
            instance ?: LocalHub(context).also { instance = it }
        }
    }
}
