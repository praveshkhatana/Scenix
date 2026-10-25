package com.scenix.tv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The hub sends an explicit `null` for every field it does not know (§1 says a missing field is a
 * neutral default, not an absent key). Android's `optString` answers that with the four-letter word
 * "null", which then reaches the screen as "Now: null". Every string read here goes through these
 * instead: a JSON null is an absent value.
 */
internal fun s(o: JSONObject, name: String, def: String = ""): String =
    if (o.isNull(name)) def else o.optString(name, def)

internal fun sa(a: JSONArray, i: Int, def: String = ""): String =
    if (a.isNull(i)) def else a.optString(i, def)

/**
 * The only shared data shape — `design/plugin-architecture.md` §1, field for field.
 *
 * The app never invents content and never re-shapes this: the hub sends it, we parse it, the
 * card plugins render it. Missing fields get neutral defaults so a hub that adds or drops a
 * field cannot crash the app (forward compatibility).
 */

/** `{ "now": "Match", "quality": "720p", "number": 12 }` */
data class Live(
    val now: String = "",
    val quality: String = "",
    // The channel's place in the operator's numbering ("12 · &TV"). The hub assigns it; 0 means the
    // hub did not number this one and the app falls back to its position in the row.
    val number: Int = 0,
) {
    companion object {
        fun from(o: JSONObject?): Live? {
            if (o == null) return null
            val l = Live(s(o, "now", ""), s(o, "quality", ""), o.optInt("number", 0))
            return if (l.now.isEmpty() && l.quality.isEmpty() && l.number <= 0) null else l
        }
    }
}

/** `{ "positionMs": 0, "watchedMs": 0 }` */
data class Progress(
    val positionMs: Long = 0,
    val watchedMs: Long = 0,
) {
    val fraction: Float
        get() = if (watchedMs <= 0L) 0f else (positionMs.toFloat() / watchedMs.toFloat()).coerceIn(0f, 1f)

    companion object {
        fun from(o: JSONObject?): Progress? = o?.let {
            Progress(it.optLong("positionMs", 0L), it.optLong("watchedMs", 0L))
        }
    }
}

/**
 * One entry of `sources[]` — the dedupe result means everything lives behind ONE tile, so the
 * app shows this list under "Play from" instead of ever showing two tiles for one title.
 */
data class Source(
    val provider: String = "",
    val label: String = "",
    val kind: String = "http",       // http | hls | dash | yt | torrent
    val quality: String = "",
    val sizeBytes: Long = 0,
    val risk: String = "",           // e.g. "0.7x" — the hub's measured realtime, shown honestly
    val mode: String = "",           // direct | proxy | gateway — gateway is plain seekable HTTP
    val score: Double = 0.0,         // the hub's ranking; higher first
    val why: List<String> = emptyList(),   // why the hub ranked it this way, shown to the viewer
) {
    /** Position in the hub's ranking, 1-based, for "Switched to Source 2". */
    companion object {
        fun from(o: JSONObject): Source = Source(
            provider = s(o, "provider", ""),
            label = s(o, "label", ""),
            kind = s(o, "kind", "http").lowercase(),
            quality = s(o, "quality", ""),
            sizeBytes = o.optLong("sizeBytes", 0L),
            risk = s(o, "risk", ""),
            mode = s(o, "mode", ""),
            score = o.optDouble("score", 0.0),
            why = strList(o.optJSONArray("why")),
        )

        private fun strList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) out.add(sa(a, i, ""))
            return out.filter { it.isNotBlank() }
        }
    }
}

/**
 * The item. `identity` is the merge key (`imdb:tt…`, else `norm:<slug>-<year>`); the app must
 * treat it as the primary key for everything (detail, progress, dedupe guard).
 */
data class Item(
    val identity: String = "",
    val type: String = "movie",      // movie | series | channel | video
    val title: String = "",
    val year: Int = 0,
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val match: Int = 0,
    val maturity: String = "",
    val durationMs: Long = 0,
    val languages: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val addedAt: Long = 0,           // first sweep the hub saw it
    val releasedAt: Long = 0,        // from Cinemeta
    val inCinemas: Boolean = false,  // released within 8 weeks
    val live: Live? = null,
    val progress: Progress? = null,
    val kids: Boolean = false,       // optional hub annotation; kids-guard uses it if present
    val sources: List<Source> = emptyList(),
) {
    /** Artwork to draw for a card of this item, given the card's aspect. */
    fun artFor(wide: Boolean): String? =
        (if (wide) backdrop ?: poster ?: logo else poster ?: logo ?: backdrop)?.takeIf { it.isNotBlank() }

    fun isLive(): Boolean = type == "channel" || live != null

    companion object {
        fun from(o: JSONObject): Item = Item(
            identity = s(o, "identity", ""),
            type = s(o, "type", "movie"),
            title = s(o, "title", ""),
            year = o.optInt("year", 0),
            poster = s(o, "poster", "").takeIf { it.isNotBlank() },
            backdrop = s(o, "backdrop", "").takeIf { it.isNotBlank() },
            logo = s(o, "logo", "").takeIf { it.isNotBlank() },
            match = o.optInt("match", 0),
            maturity = s(o, "maturity", ""),
            durationMs = o.optLong("durationMs", 0L),
            languages = strList(o.optJSONArray("languages")),
            genres = strList(o.optJSONArray("genres")),
            addedAt = o.optLong("addedAt", 0L),
            releasedAt = o.optLong("releasedAt", 0L),
            inCinemas = o.optBoolean("inCinemas", false),
            live = Live.from(o.optJSONObject("live")),
            progress = Progress.from(o.optJSONObject("progress")),
            kids = o.optBoolean("kids", false) || s(o, "scope", "").equals("kids", true),
            sources = srcList(o.optJSONArray("sources")),
        )

        fun list(a: JSONArray?): List<Item> {
            if (a == null) return emptyList()
            val out = ArrayList<Item>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(from(o))
            }
            return out
        }

        private fun strList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) out.add(sa(a, i, ""))
            return out.filter { it.isNotBlank() }
        }

        private fun srcList(a: JSONArray?): List<Source> {
            if (a == null) return emptyList()
            val out = ArrayList<Source>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(Source.from(o))
            }
            return out
        }
    }
}

/**
 * A home row as the hub sends it: `{ id, title, card, items[] }` plus the `scope`/`order` a row
 * plugin declares (see the row plugins). The app holds no content knowledge: it renders whatever arrives.
 */
data class Row(
    val id: String = "",
    val title: String = "",
    val card: String = "poster",     // the card visual to use; unknown ids fall back to poster
    val scope: String = "all",       // all | adult | kids
    val order: Int = 0,
    val max: Int = 0,                // hub-side cap (top10 sends 10); 0 = no cap
    val items: List<Item> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject): Row = Row(
            id = s(o, "id", ""),
            title = s(o, "title", ""),
            card = s(o, "card", "poster"),
            scope = s(o, "scope", "all"),
            order = o.optInt("order", 0),
            max = o.optInt("max", 0),
            items = Item.list(o.optJSONArray("items")),
        )

        fun list(a: JSONArray?): List<Row> {
            if (a == null) return emptyList()
            val out = ArrayList<Row>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(from(o))
            }
            return out.sortedBy { it.order }
        }
    }
}

/** Profile from `GET /app/v1/profiles` (profiles + per-profile rules). */
data class Profile(
    val id: String = "",
    val name: String = "",
    val isKids: Boolean = false,
    val hasPin: Boolean = false,     // hub-side flag; the PIN itself lives on the app (Prefs)
    val hiddenRows: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    // Track order the player prefers. Absent on an older hub, where `languages` is used instead.
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
) {
    companion object {
        private fun arr(o: JSONObject?, vararg names: String): List<String> {
            for (n in names) {
                val a = o?.optJSONArray(n) ?: continue
                return (0 until a.length()).map { sa(a, it, "") }.filter { it.isNotBlank() }
            }
            return emptyList()
        }

        fun from(o: JSONObject): Profile {
            // The hub keeps playback taste under `prefs`; a hand-written profile may put it on the row.
            val prefs = o.optJSONObject("prefs")
            val kind = s(o, "kind", "")
            return Profile(
                id = s(o, "id", s(o, "name", "")),
                name = s(o, "name", ""),
                isKids = kind.equals("kids", ignoreCase = true) ||
                    o.optBoolean("isKids", o.optBoolean("kids", false)),
                hasPin = o.optBoolean("pinRequired", o.optBoolean("hasPin", o.optBoolean("pin", false))),
                hiddenRows = arr(o, "hiddenRows", "hidden"),
                languages = arr(o, "languages").ifEmpty { arr(prefs, "languages") },
                audioLanguages = arr(o, "audioLanguages", "audio")
                    .ifEmpty { arr(prefs, "audioLanguages", "audio") },
                subtitleLanguages = arr(o, "subtitleLanguages", "subtitles")
                    .ifEmpty { arr(prefs, "subtitleLanguages", "subtitles") },
            )
        }

        fun list(a: JSONArray?): List<Profile> {
            if (a == null) return emptyList()
            val out = ArrayList<Profile>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(from(o))
            }
            return out
        }
    }
}

/**
 * One addon the hub reports — built into it, or installed by the owner. Read-only on the TV: the
 * Settings screen shows the list so the owner can see what the hub is pulling from, and nothing else.
 *
 * Every field is optional. A hub that reports no counts, or no errors, still renders.
 */
data class Addon(
    val id: String = "",
    val name: String = "",
    val origin: String = "",        // "native" | "user" (anything else prints as-is)
    val kinds: List<String> = emptyList(),
    val count: Int = 0,             // items it contributed to the catalogue
    val lastError: String = "",     // last fetch failure, if any
    val enabled: Boolean = true,
) {
    companion object {
        fun from(o: JSONObject): Addon = Addon(
            id = s(o, "id", ""),
            name = s(o, "name", s(o, "id", "")),
            origin = s(o, "origin", s(o, "type", "")),
            kinds = strList(o.optJSONArray("kinds") ?: o.optJSONArray("types")),
            count = o.optInt("count", o.optInt("items", 0)),
            lastError = s(o, "lastError", s(o, "error", "")),
            enabled = o.optBoolean("enabled", true),
        )

        fun list(a: JSONArray?): List<Addon> {
            if (a == null) return emptyList()
            val out = ArrayList<Addon>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                out.add(from(o))
            }
            return out
        }

        private fun strList(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) out.add(sa(a, i, ""))
            return out.filter { it.isNotBlank() }
        }
    }
}

/**
 * `GET /app/v1/manifest` — rows, cards, rules, providers, theme id. The Settings screen renders
 * this read-only; the app never depends on its contents to draw anything.
 */
data class Manifest(
    val version: String = "",
    val theme: String = "",
    val name: String = "",
    val rows: List<String> = emptyList(),
    val cards: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val providers: List<String> = emptyList(),
    val addons: List<Addon> = emptyList(),   // optional section; absent on older hubs
    val raw: JSONObject = JSONObject(),
) {
    companion object {
        fun from(o: JSONObject): Manifest = Manifest(
            version = s(o, "version", ""),
            theme = s(o, "theme", ""),
            name = s(o, "name", ""),
            rows = ids(o.optJSONArray("rows")),
            cards = ids(o.optJSONArray("cards")),
            rules = ids(o.optJSONArray("rules")),
            providers = ids(o.optJSONArray("providers")),
            addons = Addon.list(o.optJSONArray("addons")),
            raw = o,
        )

        private fun ids(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                when {
                    o != null -> out.add(s(o, "id", s(o, "name", "")))
                    else -> out.add(sa(a, i, ""))
                }
            }
            return out.filter { it.isNotBlank() }
        }
    }
}

/**
 * `POST /app/v1/play` -> `{ url, headers, kind, mode, why }`.
 *
 * `mode` says how the hub is serving it: `direct` (the origin), `proxy` (the hub streaming it
 * through) or `gateway` (the hub resolved something awkward — a torrent, a signed link — into an
 * ordinary seekable HTTP stream). `gateway` and `proxy` are played exactly the same way; only
 * `magnet:` URLs are not Media3's business.
 */
data class PlayTarget(
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val kind: String = "http",
    val expiresAt: Long = 0,
    val mode: String = "",
    val why: List<String> = emptyList(),
) {
    /** True when Media3 can open this at all. */
    fun playable(): Boolean = url.startsWith("http")

    companion object {
        fun from(o: JSONObject): PlayTarget {
            val headers = LinkedHashMap<String, String>()
            o.optJSONObject("headers")?.let { h ->
                for (k in h.keys()) headers[k] = s(h, k, "")
            }
            return PlayTarget(
                url = s(o, "url", s(o, "uri", "")),
                headers = headers,
                kind = s(o, "kind", "http").lowercase(),
                expiresAt = o.optLong("expiresAt", 0L),
                mode = s(o, "mode", ""),
                why = jsonStrings(o.optJSONArray("why")),
            )
        }
    }
}

/**
 * `GET /app/v1/subs/<identity>?lang=…` -> `[{ id, addon, lang, url }]`.
 *
 * A subtitle the hub found for this title. `addon` names where it came from, which the app shows as
 * a source word ("Subtitles · Hindi"), never a third-party product name.
 */
data class Sub(
    val id: String = "",
    val addon: String = "",
    val lang: String = "",
    val url: String = "",
    val label: String = "",
) {
    /** Mime type Media3 needs to pick a decoder, guessed from the URL (same values as MimeTypes). */
    val mimeType: String
        get() {
            val u = url.lowercase()
            return when {
                u.contains(".vtt") -> "text/vtt"
                u.contains(".ssa") || u.contains(".ass") -> "text/x-ssa"
                u.contains(".ttml") || u.contains(".dfxp") -> "application/ttml+xml"
                else -> "application/x-subrip"
            }
        }

    companion object {
        fun from(o: JSONObject): Sub = Sub(
            id = s(o, "id", s(o, "url", "")),
            addon = s(o, "addon", s(o, "provider", "")),
            lang = s(o, "lang", s(o, "language", "")),
            url = s(o, "url", s(o, "uri", "")),
            label = s(o, "label", ""),
        )

        fun list(a: JSONArray?): List<Sub> {
            if (a == null) return emptyList()
            val out = ArrayList<Sub>(a.length())
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (s(o, "url", "").isNotBlank()) out.add(from(o))
            }
            return out
        }
    }
}

/**
 * `GET`/`POST /app/v1/prefs` — how this profile wants the hub to rank sources.
 *
 * The TV only edits and displays it; the ranking itself happens on the hub, which is why the app
 * shows back the `why[]` the hub produced for a sample title.
 */
data class HubPrefs(
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val quality: String = "",           // "" = auto, else 2160 / 1080 / 720 / 480
    val maxFileSizeBytes: Long = 0,     // 0 = no cap
    val maxBitrateBps: Int = 0,         // 0 = no cap
    // The dark-red tier. Off means the hub does not just hide it, it stops recording that it exists
    // for this profile: no row, no search hit, no history entry. So the default is false, here and
    // on the hub, and the only place it is ever mentioned is one line in Settings.
    val lateNight: Boolean = false,
    val raw: JSONObject = JSONObject(),
) {
    fun toJson(profileId: String): JSONObject {
        val o = JSONObject(raw.toString())  // copy, so unknown hub fields survive the round trip
        o.put("profile", profileId)
        o.put("audioLanguages", JSONArray(audioLanguages))
        o.put("subtitleLanguages", JSONArray(subtitleLanguages))
        o.put("quality", quality)
        o.put("maxFileSizeBytes", maxFileSizeBytes)
        o.put("maxBitrate", maxBitrateBps)
        o.put("xxxEnabled", lateNight)
        return o
    }

    companion object {
        fun from(o: JSONObject): HubPrefs = HubPrefs(
            audioLanguages = jsonStrings(o.optJSONArray("audioLanguages") ?: o.optJSONArray("audio")),
            subtitleLanguages = jsonStrings(o.optJSONArray("subtitleLanguages") ?: o.optJSONArray("subtitles")),
            quality = s(o, "quality", o.optInt("maxHeight", 0).let { if (it > 0) it.toString() else "" }),
            maxFileSizeBytes = o.optLong("maxFileSizeBytes", o.optLong("maxSizeBytes", 0L)),
            maxBitrateBps = o.optInt("maxBitrate", o.optInt("maxBitrateBps", 0)),
            lateNight = o.optBoolean("xxxEnabled", o.optBoolean("lateNightMode", false)),
            raw = o,
        )
    }
}

/** Shared tolerant parse: a JSON array of strings, or an empty list. Never throws. */
internal fun jsonStrings(a: JSONArray?): List<String> {
    if (a == null) return emptyList()
    val out = ArrayList<String>(a.length())
    for (i in 0 until a.length()) {
        val v = sa(a, i, "")
        if (v.isNotBlank()) out.add(v)
    }
    return out
}
